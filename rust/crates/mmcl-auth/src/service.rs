// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The launcher-wide auth facade (`MCLauncherAuthManager`): owns the [`AccountManager`], the
//! protocol client, the rate limiter, the status callback and the timeouts; runs interactive
//! sign-ins and renewals; picks and refreshes the launch account.

use std::sync::Arc;
use std::time::Duration;

use futures::FutureExt;
use futures::future::BoxFuture;
use mmcl_core::DataPaths;
use mmcl_i18n::{t, tf};
use mmcl_net::CancelToken;
use mmcl_secure::{Cipher, MachineCipher};

use crate::accounts::{
    AccountInfo, AccountManager, DefaultAccountSetting, RefreshFuture, Renewal, RenewalError,
    Renewer, Status,
};
use crate::authfile::AuthFile;
use crate::classify::AuthOutcome;
use crate::limiter::RateLimiter;
use crate::msa::{MsaClient, MsaError, MsaTokens, OFFICIAL_CLIENT_ID, SignInKind};
use crate::resolve::{Blocked, resolve};
use crate::signin::{
    DeviceCodeFlow, PromptCallback, SignInError, SignInFlow, WamFlow, WebViewHelperFlow,
    offered_flows,
};
use crate::spawn::{SerialSpawner, Spawn};
use crate::status::{StatusCallback, StatusHub};
use crate::store::AccountStore;
use crate::user::User;

/// A token older than this is refreshed before use (4 h).
pub const TOKEN_REFRESH_INTERVAL_MS: i64 = 4 * 60 * 60 * 1000;
/// Past this a background refresh starts at startup (3 h).
pub const TOKEN_SOFT_REFRESH_INTERVAL_MS: i64 = 3 * 60 * 60 * 1000;

/// The timeouts (production values in [`Default`]).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct AuthTimeouts {
    /// One whole Xbox/Minecraft chain run (60 s).
    pub chain: Duration,
    /// How long a launch waits for its account's refresh (60 s).
    pub launch_wait: Duration,
    /// How long the blocking startup renewal waits (120 s).
    pub renew_existing: Duration,
}

impl Default for AuthTimeouts {
    fn default() -> Self {
        AuthTimeouts {
            chain: Duration::from_secs(60),
            launch_wait: Duration::from_secs(60),
            renew_existing: Duration::from_secs(120),
        }
    }
}

/// The default account kept in `configuration.json` (`defaultAccountUuid`).
#[derive(Clone, Debug)]
pub struct ConfigDefaultAccount(pub mmcl_config::Config);

impl DefaultAccountSetting for ConfigDefaultAccount {
    fn get(&self) -> String {
        self.0.default_account_uuid()
    }
    fn set(&self, uuid: &str) {
        self.0.set_default_account_uuid(uuid);
    }
}

/// The production renewer: rate limit, refresh grant, Xbox chain, under the chain timeout.
pub struct ChainRenewer {
    msa: Arc<MsaClient>,
    limiter: Arc<RateLimiter>,
    status: Arc<StatusHub>,
    timeout: Duration,
}

impl ChainRenewer {
    /// A renewer over the shared client, limiter and status.
    pub fn new(
        msa: Arc<MsaClient>,
        limiter: Arc<RateLimiter>,
        status: Arc<StatusHub>,
        timeout: Duration,
    ) -> ChainRenewer {
        ChainRenewer {
            msa,
            limiter,
            status,
            timeout,
        }
    }

    async fn renew_file(&self, gz: Vec<u8>) -> Result<Renewal, RenewalError> {
        let signing_in = t!("authManager.status.signingIn");
        self.status
            .report(&signing_in, &t!("authManager.status.preparingContact"));
        self.limiter.acquire(Some(&self.status)).await;
        let file = match AuthFile::read_compressed(&gz) {
            Ok(f) => f,
            Err(e) => {
                self.limiter.record_failure();
                mmcl_log::warn_silent(&tf!("log.authManager.authErrorType", e.kind_name()));
                return Err(RenewalError::new(e.kind_name(), false));
            }
        };
        self.status
            .report(&signing_in, &t!("authManager.status.contactingServers"));
        mmcl_log::std(&tf!(
            "log.authManager.renewingToken",
            self.timeout.as_secs().to_string()
        ));
        let run = async {
            if file.flow() == SignInKind::Wam {
                self.renew_wam(&file).await
            } else {
                self.msa.renew(&file).await
            }
        };
        match tokio::time::timeout(self.timeout, run).await {
            Err(_) => {
                mmcl_log::error_silent(&tf!(
                    "log.authManager.authTimedOut",
                    self.timeout.as_secs().to_string()
                ));
                self.limiter.record_failure();
                Err(RenewalError::new("timed out", false))
            }
            Ok(Ok((user, renewed))) => {
                let auth_file_gz = renewed
                    .write_compressed()
                    .map_err(|e| RenewalError::new(e.kind_name(), false))?;
                self.limiter.record_success();
                mmcl_log::std(&t!("log.authManager.tokenRenewedSuccess"));
                self.status
                    .report(&signing_in, &t!("authManager.status.signedIn"));
                Ok(Renewal { user, auth_file_gz })
            }
            Ok(Err(failure)) => {
                // Only the kind is logged: protocol messages can carry token fragments.
                mmcl_log::warn_silent(&t!("log.authManager.tokenRenewalFailed"));
                mmcl_log::warn_silent(&tf!(
                    "log.authManager.authErrorType",
                    failure.error.kind_name()
                ));
                self.limiter.record_failure();
                Err(RenewalError {
                    message: failure.error.kind_name().to_string(),
                    credentials_rejected: failure.error.credentials_rejected(),
                    rotated_auth_file: failure.rotated.and_then(|f| f.write_compressed().ok()),
                })
            }
        }
    }

    /// A WAM file renews by asking the broker again for the same web account.
    async fn renew_wam(
        &self,
        file: &AuthFile,
    ) -> Result<(User, AuthFile), crate::msa::RenewFailure> {
        let fail = |error: MsaError| crate::msa::RenewFailure {
            error,
            rotated: None,
        };
        let Some(account) = file.wam_account_id().map(str::to_string) else {
            return Err(fail(MsaError::OAuth {
                error: "invalid_grant".into(),
                description: None,
            }));
        };
        let client_id = file.msa_client_id().to_string();
        let token = tokio::task::spawn_blocking(move || {
            crate::signin::wam_renew_silently(&client_id, &account)
        })
        .await
        .map_err(|_| fail(MsaError::Parse("wam")))?
        .map_err(|e| {
            fail(match e {
                // The broker could not renew silently: the user has to sign in again.
                SignInError::WamFailed(_) | SignInError::Cancelled => MsaError::OAuth {
                    error: "interaction_required".into(),
                    description: None,
                },
                _ => MsaError::Parse("wam"),
            })
        })?;
        self.msa
            .renew_with_access_token(file, &token.access_token)
            .await
            .map_err(fail)
    }
}

impl Renewer for Arc<ChainRenewer> {
    fn renew(&self, auth_file_gz: Vec<u8>) -> BoxFuture<'static, Result<Renewal, RenewalError>> {
        let this = self.clone();
        async move { this.renew_file(auth_file_gz).await }.boxed()
    }
}

/// The auth facade.
pub struct AuthService {
    manager: AccountManager,
    msa: Arc<MsaClient>,
    limiter: Arc<RateLimiter>,
    status: Arc<StatusHub>,
    spawner: Arc<dyn Spawn>,
    timeouts: AuthTimeouts,
    config: Option<mmcl_config::Config>,
}

impl std::fmt::Debug for AuthService {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("AuthService")
            .field("manager", &self.manager)
            .field("timeouts", &self.timeouts)
            .finish_non_exhaustive()
    }
}

impl AuthService {
    /// The production service for a data folder: the machine cipher, the account store in
    /// `profiles/`, the default account in `config`, refreshes on one serial task of `runtime`.
    pub fn new(
        paths: &DataPaths,
        config: mmcl_config::Config,
        runtime: &tokio::runtime::Handle,
    ) -> Result<Arc<AuthService>, MsaError> {
        let cipher: Arc<dyn Cipher> = Arc::new(MachineCipher::for_paths(paths));
        let msa = Arc::new(MsaClient::production()?);
        let limiter = Arc::new(RateLimiter::default());
        let status = Arc::new(StatusHub::default());
        let timeouts = AuthTimeouts::default();
        let renewer = Arc::new(ChainRenewer::new(
            msa.clone(),
            limiter.clone(),
            status.clone(),
            timeouts.chain,
        ));
        let spawner: Arc<dyn Spawn> = Arc::new(SerialSpawner::new(runtime));
        let manager = AccountManager::new(
            AccountStore::new(paths.profiles_dir(), cipher.clone()),
            paths.config_dir(),
            cipher,
            Arc::new(ConfigDefaultAccount(config.clone())),
            Arc::new(renewer),
            Arc::new(chrono_now_ms),
            spawner.clone(),
            TOKEN_REFRESH_INTERVAL_MS,
        );
        Ok(AuthService::from_parts(
            manager,
            msa,
            limiter,
            status,
            spawner,
            timeouts,
            Some(config),
        ))
    }

    /// A service from its parts (tests, tools).
    pub fn from_parts(
        manager: AccountManager,
        msa: Arc<MsaClient>,
        limiter: Arc<RateLimiter>,
        status: Arc<StatusHub>,
        spawner: Arc<dyn Spawn>,
        timeouts: AuthTimeouts,
        config: Option<mmcl_config::Config>,
    ) -> Arc<AuthService> {
        Arc::new(AuthService {
            manager,
            msa,
            limiter,
            status,
            spawner,
            timeouts,
            config,
        })
    }

    /// The accounts.
    pub fn accounts(&self) -> &AccountManager {
        &self.manager
    }

    /// The protocol client.
    pub fn msa(&self) -> &Arc<MsaClient> {
        &self.msa
    }

    /// The rate limiter.
    pub fn limiter(&self) -> &Arc<RateLimiter> {
        &self.limiter
    }

    /// Sets or clears the `(section, detail)` status callback of the startup progress screen.
    pub fn set_status_callback(&self, cb: Option<StatusCallback>) {
        self.status.set(cb);
    }

    // ---------------------------------------------------------------- flows

    /// The configured Microsoft client ID (`auth.clientId`; the official one by default).
    pub fn client_id(&self) -> String {
        self.config
            .as_ref()
            .map_or_else(|| OFFICIAL_CLIENT_ID.to_string(), |c| c.auth_client_id())
    }

    /// Whether WAM may be offered (`auth.wam` and a client ID of our own).
    pub fn wam_active(&self) -> bool {
        self.config.as_ref().is_some_and(|c| c.wam_active())
    }

    /// The flows a sign-in screen offers, first the default (device code in gamepad mode).
    pub fn offered_flows(&self, gamepad_mode: bool, web_view_supported: bool) -> Vec<SignInKind> {
        offered_flows(gamepad_mode, web_view_supported, self.wam_active())
    }

    /// The web view helper flow (official client ID, the UI language).
    pub fn web_view_flow(&self) -> WebViewHelperFlow {
        WebViewHelperFlow::new(self.msa.clone())
            .with_title(t!("signInHelper.window.title").into_owned())
            .with_locale(mmcl_i18n::requested_locale().to_tag())
    }

    /// The device code flow with the configured client ID; `on_prompt` gets the code to show.
    pub fn device_code_flow(&self, on_prompt: PromptCallback) -> DeviceCodeFlow {
        DeviceCodeFlow::new(self.msa.clone(), on_prompt).with_client_id(self.client_id())
    }

    /// The WAM flow over the launcher window `hwnd`, when WAM is active.
    pub fn wam_flow(&self, hwnd: isize) -> Result<WamFlow, SignInError> {
        WamFlow::new(&self.client_id(), self.wam_active(), hwnd)
    }

    // ---------------------------------------------------------------- sign-in

    /// Runs `flow`, then the shared chain, and adds the account. `make_default` is the login
    /// screen's behaviour; the Add-account window passes `false`.
    pub async fn sign_in(
        &self,
        flow: &dyn SignInFlow,
        cancel: &CancelToken,
        remember: bool,
        make_default: bool,
    ) -> AuthOutcome {
        match flow.run(cancel).await {
            Ok(tokens) => self.complete_sign_in(&tokens, remember, make_default).await,
            Err(e) => {
                mmcl_log::warn_silent(&tf!(
                    "log.authManager.signInFlowFailed",
                    flow.kind().as_str(),
                    e.kind_name()
                ));
                AuthOutcome::Flow(e)
            }
        }
    }

    /// The chain and account registration for tokens a flow produced.
    pub async fn complete_sign_in(
        &self,
        tokens: &MsaTokens,
        remember: bool,
        make_default: bool,
    ) -> AuthOutcome {
        self.limiter.acquire(None).await;
        mmcl_log::std(&tf!(
            "log.authManager.authenticatingMicrosoft",
            self.timeouts.chain.as_secs().to_string()
        ));
        let result = tokio::time::timeout(self.timeouts.chain, self.msa.sign_in(tokens)).await;
        self.register(result, remember, make_default)
    }

    /// The web view sign-in from an authorization code; the account becomes the default.
    pub async fn login_with_microsoft(&self, code: &str, remember: bool) -> AuthOutcome {
        self.sign_in_with_code(code, remember, true).await
    }

    /// The Add-account sign-in from an authorization code; the default is kept.
    pub async fn add_account_with_microsoft(&self, code: &str, remember: bool) -> AuthOutcome {
        self.sign_in_with_code(code, remember, false).await
    }

    async fn sign_in_with_code(
        &self,
        code: &str,
        remember: bool,
        make_default: bool,
    ) -> AuthOutcome {
        self.limiter.acquire(None).await;
        mmcl_log::std(&tf!(
            "log.authManager.authenticatingMicrosoft",
            self.timeouts.chain.as_secs().to_string()
        ));
        let result =
            tokio::time::timeout(self.timeouts.chain, self.msa.sign_in_with_code(code)).await;
        self.register(result, remember, make_default)
    }

    fn register(
        &self,
        result: Result<Result<(User, AuthFile), MsaError>, tokio::time::error::Elapsed>,
        remember: bool,
        make_default: bool,
    ) -> AuthOutcome {
        let (user, file) = match result {
            Err(_) => {
                mmcl_log::error_silent(&tf!(
                    "log.authManager.microsoftAuthTimedOut",
                    self.timeouts.chain.as_secs().to_string()
                ));
                self.limiter.record_failure();
                return AuthOutcome::Flow(SignInError::Msa(MsaError::Timeout));
            }
            Ok(Err(e)) => {
                mmcl_log::warn_silent(&t!("log.authManager.loginException"));
                mmcl_log::warn_silent(&tf!("log.authManager.authErrorType", e.kind_name()));
                self.limiter.record_failure();
                return match AuthOutcome::from_msa_error(&e) {
                    AuthOutcome::Other => AuthOutcome::Flow(SignInError::Msa(e)),
                    o => o,
                };
            }
            Ok(Ok(v)) => v,
        };
        mmcl_log::std(&t!("log.authManager.microsoftAuthResponse"));
        let gz = match file.write_compressed() {
            Ok(g) => g,
            Err(e) => {
                mmcl_log::warn_silent(&tf!("log.authManager.authErrorType", e.kind_name()));
                self.limiter.record_failure();
                return AuthOutcome::Other;
            }
        };
        if !self
            .manager
            .add_signed_in(user.clone(), gz, remember, make_default)
        {
            self.limiter.record_failure();
            return AuthOutcome::BadCredentials;
        }
        self.limiter.record_success();
        AuthOutcome::Ok(user)
    }

    // ---------------------------------------------------------------- default account

    /// Whether the login screen can be skipped.
    pub fn has_existing_login(&self) -> bool {
        self.manager.has_default()
    }

    /// The default user.
    pub fn logged_in_user(&self) -> Option<User> {
        self.manager.default_user()
    }

    /// The default user if its cached copy is complete. Never touches the network.
    pub fn load_cached_user_now(&self) -> Option<User> {
        self.manager
            .default_user()
            .filter(|u| !u.name.trim().is_empty() && !u.uuid.trim().is_empty())
    }

    /// Refreshes the default account when due, in the background.
    pub fn renew_existing_login_async(&self) -> BoxFuture<'static, AuthOutcome> {
        self.manager.refresh_default().map(to_auth_result).boxed()
    }

    /// Refreshes the default account and waits (up to 120 s).
    pub async fn renew_existing_login(&self) -> AuthOutcome {
        let signing_in = t!("authManager.status.signingIn");
        self.status
            .report(&signing_in, &t!("authManager.status.checkingSession"));
        match tokio::time::timeout(
            self.timeouts.renew_existing,
            self.renew_existing_login_async(),
        )
        .await
        {
            Ok(o) => {
                if o.is_ok() {
                    mmcl_log::std(&t!("log.authManager.signInComplete"));
                }
                o
            }
            Err(_) => AuthOutcome::Other,
        }
    }

    /// The default account's in-flight refresh.
    pub fn pending_refresh(&self) -> Option<BoxFuture<'static, AuthOutcome>> {
        let uuid = self.manager.default_uuid()?;
        let f = self.manager.pending_refresh(&uuid)?;
        Some(f.map(to_auth_result).boxed())
    }

    /// Signs the default account out and deletes its credentials.
    pub fn logout(&self) {
        if let Some(u) = self.manager.default_uuid() {
            self.manager.remove(&u);
        }
    }

    /// Clears the default but keeps every account (the next start shows the login screen).
    pub fn archive_and_logout(&self) {
        self.manager.set_default(None);
    }

    /// Makes another signed-in account the default.
    pub fn switch_to(&self, uuid: &str) -> bool {
        self.manager.set_default(Some(uuid))
    }

    // ---------------------------------------------------------------- launches

    /// The user a launch plays as: the pack's override, else the default; refreshed first when
    /// due. A transient refresh failure or timeout launches on the cached token; refused
    /// credentials block with "needs sign-in".
    pub async fn user_for_launch(&self, override_uuid: Option<&str>) -> Result<User, Blocked> {
        let resolution = resolve(override_uuid, &self.manager.accounts());
        let Some(uuid) = resolution.uuid.clone().filter(|_| resolution.ok()) else {
            return Err(Blocked(resolution));
        };
        let refresh = self.manager.refresh(&uuid, false);
        match tokio::time::timeout(self.timeouts.launch_wait, refresh).await {
            Ok(Ok(user)) => return Ok(user),
            Ok(Err(e)) if e.credentials_rejected => {
                mmcl_log::warn_silent(&t!("log.authManager.launchCredentialsRejected"));
                return Err(Blocked::needs_sign_in(&resolution));
            }
            Ok(Err(e)) => {
                mmcl_log::warn_silent(&tf!("log.authManager.launchRefreshFailed", e.message));
            }
            Err(_) => {
                // The refresh keeps running in the background.
                mmcl_log::warn_silent(&tf!("log.authManager.launchRefreshFailed", "Timeout"));
            }
        }
        self.manager
            .user(&uuid)
            .ok_or_else(|| Blocked::needs_sign_in(&resolution))
    }

    /// Starts background refreshes of accounts in the soft-refresh window (3-4 h old), and of
    /// non-default accounts past 4 h (startup renews the default itself). Fire and forget;
    /// never fails the caller.
    pub fn try_preemptive_background_renewal(&self) {
        if mmcl_net::is_offline() {
            return;
        }
        let now = self.manager.now_ms();
        let accounts: Vec<(AccountInfo, i64)> = self
            .manager
            .accounts()
            .into_iter()
            .map(|a| {
                let renewed = self.manager.renewed_at_ms(&a.uuid);
                (a, renewed)
            })
            .collect();
        let targets = preemptive_targets(&accounts, now, self.manager.refresh_interval_ms());
        if !targets.is_empty() {
            mmcl_log::std(&t!("log.authManager.softRefreshKickoff"));
        }
        for uuid in targets {
            let fut: RefreshFuture = self.manager.refresh(&uuid, true);
            self.spawner.spawn(
                async move {
                    if let Err(e) = fut.await {
                        mmcl_log::warn_silent(&tf!(
                            "log.authManager.preemptiveRenewalFailed",
                            e.message
                        ));
                    }
                }
                .boxed(),
            );
        }
    }
}

/// Which accounts a startup background renewal refreshes: not `NeedsSignIn`, a known renewal
/// time at least 3 h old, and not the default when it is past the hard interval.
pub fn preemptive_targets(
    accounts: &[(AccountInfo, i64)],
    now_ms: i64,
    hard_interval_ms: i64,
) -> Vec<String> {
    accounts
        .iter()
        .filter(|(a, renewed)| {
            if a.status == Status::NeedsSignIn || *renewed == 0 {
                return false;
            }
            let elapsed = now_ms - renewed;
            if elapsed < TOKEN_SOFT_REFRESH_INTERVAL_MS {
                return false;
            }
            !(elapsed >= hard_interval_ms && a.is_default)
        })
        .map(|(a, _)| a.uuid.clone())
        .collect()
}

/// A refresh result as a screen sees it.
pub fn to_auth_result(r: Result<User, RenewalError>) -> AuthOutcome {
    match r {
        Ok(u) => AuthOutcome::Ok(u),
        Err(e) if e.credentials_rejected => AuthOutcome::LoginExpired,
        Err(_) => AuthOutcome::Other,
    }
}

fn chrono_now_ms() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map_or(0, |d| d.as_millis() as i64)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::http::Method;
    use crate::msa::{
        MC_ENTITLEMENTS_URL, MC_LOGIN_URL, MC_PROFILE_URL, TOKEN_URL, XBL_URL, XSTS_URL,
    };
    use crate::resolve::Problem;
    use crate::store::PROFILES_DIR;
    use crate::testutil::*;

    const INTERVAL: i64 = TOKEN_REFRESH_INTERVAL_MS;
    const T0: i64 = 1_700_000_000_000;

    struct Fx {
        _dir: tempfile::TempDir,
        clock: ManualClock,
        renewer: Arc<ScriptedRenewer>,
        manual: Arc<ManualSpawner>,
        service: Arc<AuthService>,
        transport: Arc<ScriptedTransport>,
    }

    fn fx(timeouts: AuthTimeouts) -> Fx {
        let dir = tempfile::tempdir().unwrap();
        let clock = ManualClock::new(T0);
        let renewer = Arc::new(ScriptedRenewer::default());
        let manual = Arc::new(ManualSpawner::default());
        let manager = AccountManager::new(
            AccountStore::new(dir.path().join(PROFILES_DIR), Arc::new(FakeCipher)),
            dir.path(),
            Arc::new(FakeCipher),
            Arc::new(InMemoryDefault::default()),
            renewer.clone(),
            clock.as_fn(),
            manual.clone(),
            INTERVAL,
        );
        let transport = Arc::new(ScriptedTransport::default());
        let service = AuthService::from_parts(
            manager,
            Arc::new(MsaClient::new(transport.clone())),
            Arc::new(RateLimiter::new(Duration::ZERO)),
            Arc::new(StatusHub::default()),
            manual.clone(),
            timeouts,
            None,
        );
        Fx {
            _dir: dir,
            clock,
            renewer,
            manual,
            service,
            transport,
        }
    }

    /// One account `a`, signed in, now past the refresh interval.
    fn stale(f: &Fx) {
        f.service
            .accounts()
            .add_signed_in(user('a', "cached"), auth_file("a"), true, true);
        f.clock.advance(INTERVAL + 1);
    }

    async fn launch(f: &Fx) -> Result<User, Blocked> {
        let svc = f.service.clone();
        let task = tokio::spawn(async move { svc.user_for_launch(None).await });
        for _ in 0..1000 {
            tokio::task::yield_now().await;
            if !f.manual.0.lock().is_empty() {
                break;
            }
        }
        f.manual.run_queued();
        task.await.unwrap()
    }

    #[tokio::test]
    async fn successful_refresh_launches_with_the_new_token() {
        let f = fx(AuthTimeouts::default());
        stale(&f);
        f.renewer.script(|input| {
            Ok(Renewal {
                user: user('a', "fresh"),
                auth_file_gz: input.to_vec(),
            })
        });
        let u = launch(&f).await.unwrap();
        assert_eq!(u.access_token.as_deref(), Some("fresh"));
    }

    #[tokio::test]
    async fn transient_failure_launches_with_the_cached_token() {
        let f = fx(AuthTimeouts::default());
        stale(&f);
        f.renewer
            .script(|_| Err(RenewalError::new("timed out", false)));
        let u = launch(&f).await.unwrap();
        assert_eq!(u.access_token.as_deref(), Some("cached"));
    }

    #[tokio::test]
    async fn rejected_credentials_block_the_launch_with_needs_sign_in() {
        let f = fx(AuthTimeouts::default());
        stale(&f);
        f.renewer
            .script(|_| Err(RenewalError::new("invalid credentials", true)));
        let blocked = launch(&f).await.unwrap_err();
        assert_eq!(blocked.problem(), Some(Problem::NeedsSignIn));
        assert_eq!(
            f.service.accounts().account(&uuid('a')).unwrap().status,
            Status::NeedsSignIn
        );
    }

    #[tokio::test]
    async fn timeout_launches_with_the_cached_token() {
        let f = fx(AuthTimeouts {
            launch_wait: Duration::from_millis(20),
            ..AuthTimeouts::default()
        });
        stale(&f);
        // The queued refresh is never run, so the launch times out waiting.
        let u = f.service.user_for_launch(None).await.unwrap();
        assert_eq!(u.access_token.as_deref(), Some("cached"));
        assert!(f.service.accounts().pending_refresh(&uuid('a')).is_some());
    }

    #[tokio::test]
    async fn no_account_blocks_the_launch() {
        let f = fx(AuthTimeouts::default());
        assert_eq!(
            f.service.user_for_launch(None).await.unwrap_err().problem(),
            Some(Problem::NoAccount)
        );
        assert_eq!(
            f.service
                .user_for_launch(Some(&uuid('z')))
                .await
                .unwrap_err()
                .problem(),
            Some(Problem::OverrideMissing)
        );
    }

    #[tokio::test]
    async fn renewal_results_map_like_java() {
        let f = fx(AuthTimeouts::default());
        assert_eq!(
            f.service.renew_existing_login_async().await,
            AuthOutcome::LoginExpired
        );
        assert_eq!(
            to_auth_result(Err(RenewalError::new("x", false))),
            AuthOutcome::Other
        );
    }

    #[test]
    fn preemptive_renewal_picks_the_soft_window_and_stale_non_defaults() {
        let now = T0;
        let a = |uuid: &str, status, is_default| AccountInfo {
            uuid: uuid.into(),
            display_name: String::new(),
            status,
            is_default,
            remembered: true,
            last_used_ms: 0,
        };
        let hour = 3_600_000;
        let list = vec![
            (a("fresh", Status::Ready, false), now - hour),
            (a("soft", Status::Ready, false), now - 3 * hour - 1),
            (a("softdefault", Status::Ready, true), now - 3 * hour - 1),
            (a("staledefault", Status::Ready, true), now - 5 * hour),
            (a("stalenondefault", Status::Ready, false), now - 5 * hour),
            (a("needs", Status::NeedsSignIn, false), now - 5 * hour),
            (a("unknown", Status::Ready, false), 0),
        ];
        assert_eq!(
            preemptive_targets(&list, now, INTERVAL),
            vec!["soft", "softdefault", "stalenondefault"]
        );
    }

    #[tokio::test]
    async fn logout_archive_and_switch() {
        let f = fx(AuthTimeouts::default());
        let m = f.service.accounts();
        m.add_signed_in(user('a', "ta"), auth_file("a"), true, true);
        f.clock.advance(10);
        m.add_signed_in(user('b', "tb"), auth_file("b"), true, true);
        assert!(f.service.has_existing_login());
        assert_eq!(f.service.load_cached_user_now().unwrap().uuid, uuid('b'));
        assert!(f.service.switch_to(&uuid('a')));
        assert_eq!(f.service.logged_in_user().unwrap().uuid, uuid('a'));
        f.service.archive_and_logout();
        assert!(!f.service.has_existing_login());
        assert_eq!(m.accounts().len(), 2);
        f.service.switch_to(&uuid('b'));
        f.service.logout();
        assert!(m.account(&uuid('b')).is_none());
        assert_eq!(m.default_uuid(), Some(uuid('a')));
    }

    fn script_chain(t: &ScriptedTransport, entitlements: &str) {
        let payload = base64::Engine::encode(
            &base64::engine::general_purpose::URL_SAFE_NO_PAD,
            br#"{"xuid":"42"}"#,
        );
        t.on(
            Method::Post,
            TOKEN_URL,
            200,
            r#"{"access_token":"AT","refresh_token":"RT"}"#,
        );
        t.on(
            Method::Post,
            XBL_URL,
            200,
            r#"{"Token":"X","DisplayClaims":{"xui":[{"uhs":"U"}]}}"#,
        );
        t.on(
            Method::Post,
            XSTS_URL,
            200,
            r#"{"Token":"S","DisplayClaims":{"xui":[{"uhs":"U"}]}}"#,
        );
        t.on(
            Method::Post,
            MC_LOGIN_URL,
            200,
            &format!(r#"{{"access_token":"h.{payload}.s"}}"#),
        );
        t.on(Method::Get, MC_ENTITLEMENTS_URL, 200, entitlements);
        t.on(
            Method::Get,
            MC_PROFILE_URL,
            200,
            &format!(r#"{{"id":"{}","name":"Alex"}}"#, uuid('c')),
        );
    }

    #[tokio::test]
    async fn a_code_sign_in_adds_the_account_as_the_default() {
        let f = fx(AuthTimeouts::default());
        script_chain(&f.transport, r#"{"items":[{"name":"game_minecraft"}]}"#);
        let o = f.service.login_with_microsoft("CODE", true).await;
        assert_eq!(o.user().unwrap().name, "Alex");
        assert_eq!(f.service.accounts().default_uuid(), Some(uuid('c')));
        let stored = f
            .service
            .accounts()
            .store()
            .read_auth_file(&uuid('c'))
            .unwrap();
        assert_eq!(
            AuthFile::read_compressed(&stored).unwrap().refresh_token,
            "RT"
        );
    }

    #[tokio::test]
    async fn an_account_that_does_not_own_the_game_is_not_added() {
        let f = fx(AuthTimeouts::default());
        script_chain(&f.transport, r#"{"items":[]}"#);
        assert_eq!(
            f.service.add_account_with_microsoft("CODE", true).await,
            AuthOutcome::NotOwned
        );
        assert!(f.service.accounts().accounts().is_empty());
        assert_eq!(f.service.limiter().failures(), 1);
    }

    struct FixedFlow(Result<MsaTokens, SignInError>);

    impl SignInFlow for FixedFlow {
        fn kind(&self) -> SignInKind {
            SignInKind::DeviceCode
        }
        fn run<'a>(&'a self, _c: &'a CancelToken) -> BoxFuture<'a, Result<MsaTokens, SignInError>> {
            let r = self.0.clone();
            async move { r }.boxed()
        }
    }

    #[tokio::test]
    async fn a_flow_sign_in_keeps_the_default_when_adding() {
        let f = fx(AuthTimeouts::default());
        f.service
            .accounts()
            .add_signed_in(user('a', "ta"), auth_file("a"), true, true);
        script_chain(&f.transport, r#"{"items":[{"name":"game_minecraft"}]}"#);
        let tokens = MsaTokens {
            access_token: "DEVICE-AT".into(),
            refresh_token: Some("DEVICE-RT".into()),
            client_id: OFFICIAL_CLIENT_ID.into(),
            scope: crate::msa::SCOPE_MBI_SSL.into(),
            kind: SignInKind::DeviceCode,
            wam_account_id: None,
        };
        let o = f
            .service
            .sign_in(&FixedFlow(Ok(tokens)), &CancelToken::new(), true, false)
            .await;
        assert!(o.is_ok(), "{o:?}");
        assert_eq!(f.service.accounts().default_uuid(), Some(uuid('a')));
        let stored = f
            .service
            .accounts()
            .store()
            .read_auth_file(&uuid('c'))
            .unwrap();
        let file = AuthFile::read_compressed(&stored).unwrap();
        assert_eq!(file.flow(), SignInKind::DeviceCode);
        assert_eq!(file.refresh_token, "DEVICE-RT");
        let xbl = f.transport.requests_to(XBL_URL)[0].body_text();
        assert!(xbl.contains("\"RpsTicket\":\"t=DEVICE-AT\""), "{xbl}");
    }

    #[tokio::test]
    async fn a_cancelled_flow_is_reported_as_cancelled() {
        let f = fx(AuthTimeouts::default());
        let o = f
            .service
            .sign_in(
                &FixedFlow(Err(SignInError::Cancelled)),
                &CancelToken::new(),
                true,
                true,
            )
            .await;
        assert!(o.is_cancelled());
        assert!(f.transport.requests.lock().is_empty());
    }

    #[tokio::test]
    async fn the_chain_renewer_classifies_invalid_grant_as_rejected() {
        let t = Arc::new(ScriptedTransport::default());
        t.on(
            Method::Post,
            TOKEN_URL,
            400,
            r#"{"error":"invalid_grant","error_description":"x"}"#,
        );
        let limiter = Arc::new(RateLimiter::new(Duration::ZERO));
        let r = Arc::new(ChainRenewer::new(
            Arc::new(MsaClient::new(t)),
            limiter.clone(),
            Arc::new(StatusHub::default()),
            Duration::from_secs(5),
        ));
        let gz = AuthFile::new(uuid::Uuid::new_v4(), "RT")
            .write_compressed()
            .unwrap();
        let e = r.renew(gz).await.unwrap_err();
        assert!(e.credentials_rejected);
        assert_eq!(e.message, "OAuthError");
        assert_eq!(limiter.failures(), 1);
        // Skip the backoff the failure started.
        limiter.record_success();
        let e = r.renew(b"garbage".to_vec()).await.unwrap_err();
        assert!(!e.credentials_rejected);
    }

    #[tokio::test]
    async fn the_chain_renewer_hands_back_the_rotated_token_on_a_late_failure() {
        let t = Arc::new(ScriptedTransport::default());
        t.on(
            Method::Post,
            TOKEN_URL,
            200,
            r#"{"access_token":"AT","refresh_token":"RT2"}"#,
        );
        t.on(Method::Post, XBL_URL, 500, "");
        let status = Arc::new(StatusHub::default());
        let seen = Arc::new(parking_lot::Mutex::new(Vec::<String>::new()));
        let s = seen.clone();
        status.set(Some(Arc::new(move |_: &str, d: &str| {
            s.lock().push(d.to_string())
        })));
        let r = Arc::new(ChainRenewer::new(
            Arc::new(MsaClient::new(t)),
            Arc::new(RateLimiter::new(Duration::ZERO)),
            status,
            Duration::from_secs(5),
        ));
        let gz = AuthFile::new(uuid::Uuid::new_v4(), "RT1")
            .write_compressed()
            .unwrap();
        let e = r.renew(gz).await.unwrap_err();
        assert!(!e.credentials_rejected);
        let rotated = AuthFile::read_compressed(&e.rotated_auth_file.unwrap()).unwrap();
        assert_eq!(rotated.refresh_token, "RT2");
        assert!(!seen.lock().is_empty(), "status was reported");
    }
}
