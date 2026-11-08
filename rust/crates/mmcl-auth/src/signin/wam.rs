// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The Windows account broker (WAM): `WebAuthenticationCoreManager`, shown over the launcher
//! window through `IWebAuthenticationCoreManagerInterop::RequestTokenForWindowAsync`.
//!
//! Built behind a flag: it is offered only when `auth.wam` is on **and** `auth.clientId` holds
//! a client ID of our own (WAM does not accept the official launcher ID). The broker returns an
//! access token for the `MBI_SSL` Xbox scope and no refresh token; renewals ask the broker
//! again silently for the same web account ([`renew_silently`]).

use futures::FutureExt;
use futures::future::BoxFuture;
use mmcl_net::CancelToken;

use super::{SignInError, SignInFlow};
use crate::msa::{MsaTokens, OFFICIAL_CLIENT_ID, SCOPE_MBI_SSL, SignInKind};

/// The Microsoft account provider id.
pub const WAM_PROVIDER_ID: &str = "https://login.microsoft.com";
/// The authority for personal Microsoft accounts.
pub const WAM_AUTHORITY: &str = "consumers";

/// Whether the broker may show UI.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum WamPrompt {
    /// Always show the account picker (adding an account).
    ForceAuthentication,
    /// Show UI only if needed.
    Default,
}

/// Everything a broker token request needs, built and checked without touching Windows.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct WamRequestSpec {
    pub provider_id: String,
    pub authority: String,
    pub scope: String,
    pub client_id: String,
    pub prompt: WamPrompt,
}

impl WamRequestSpec {
    /// The interactive request for `client_id`, or [`SignInError::WamUnavailable`] when WAM is
    /// not enabled, the client ID is blank or the official one, or this is not Windows.
    pub fn interactive(client_id: &str, wam_enabled: bool) -> Result<WamRequestSpec, SignInError> {
        let client_id = client_id.trim();
        if !wam_enabled
            || client_id.is_empty()
            || client_id.eq_ignore_ascii_case(OFFICIAL_CLIENT_ID)
            || !cfg!(windows)
        {
            return Err(SignInError::WamUnavailable);
        }
        Ok(WamRequestSpec {
            provider_id: WAM_PROVIDER_ID.to_string(),
            authority: WAM_AUTHORITY.to_string(),
            scope: SCOPE_MBI_SSL.to_string(),
            client_id: client_id.to_string(),
            prompt: WamPrompt::ForceAuthentication,
        })
    }

    /// The silent request a renewal makes.
    pub fn silent(client_id: &str) -> WamRequestSpec {
        WamRequestSpec {
            provider_id: WAM_PROVIDER_ID.to_string(),
            authority: WAM_AUTHORITY.to_string(),
            scope: SCOPE_MBI_SSL.to_string(),
            client_id: client_id.trim().to_string(),
            prompt: WamPrompt::Default,
        }
    }
}

/// A broker answer: the access token and the web account it belongs to.
#[derive(Clone, PartialEq, Eq)]
pub struct WamToken {
    pub access_token: String,
    pub account_id: String,
}

impl std::fmt::Debug for WamToken {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("WamToken")
            .field("account_id", &self.account_id)
            .finish_non_exhaustive()
    }
}

/// The WAM flow over a top-level window (`HWND` as an integer, from the window's raw handle).
#[derive(Clone, Debug)]
pub struct WamFlow {
    spec: WamRequestSpec,
    hwnd: isize,
}

impl WamFlow {
    /// A flow for `client_id` over `hwnd`; fails unless WAM may be used (see
    /// [`WamRequestSpec::interactive`]).
    pub fn new(client_id: &str, wam_enabled: bool, hwnd: isize) -> Result<WamFlow, SignInError> {
        Ok(WamFlow {
            spec: WamRequestSpec::interactive(client_id, wam_enabled)?,
            hwnd,
        })
    }

    /// The request this flow makes.
    pub fn spec(&self) -> &WamRequestSpec {
        &self.spec
    }
}

impl SignInFlow for WamFlow {
    fn kind(&self) -> SignInKind {
        SignInKind::Wam
    }

    fn run<'a>(&'a self, cancel: &'a CancelToken) -> BoxFuture<'a, Result<MsaTokens, SignInError>> {
        async move {
            mmcl_log::debug(&mmcl_i18n::t!("log.authManager.wamRequest"));
            let spec = self.spec.clone();
            let hwnd = self.hwnd;
            let task = tokio::task::spawn_blocking(move || imp::request_for_window(hwnd, &spec));
            let token = tokio::select! {
                r = task => r.map_err(|_| SignInError::WamFailed("task".into()))??,
                _ = cancel.cancelled() => return Err(SignInError::Cancelled),
            };
            Ok(MsaTokens {
                access_token: token.access_token,
                refresh_token: None,
                client_id: self.spec.client_id.clone(),
                scope: self.spec.scope.clone(),
                kind: SignInKind::Wam,
                wam_account_id: Some(token.account_id),
            })
        }
        .boxed()
    }
}

/// Asks the broker for a fresh token for a known web account without UI (renewals of WAM
/// sign-ins). Blocking; run it off async threads.
pub fn renew_silently(client_id: &str, account_id: &str) -> Result<WamToken, SignInError> {
    imp::silent(&WamRequestSpec::silent(client_id), account_id)
}

#[cfg(windows)]
mod imp {
    use windows::Security::Authentication::Web::Core::{
        WebAuthenticationCoreManager, WebTokenRequest, WebTokenRequestPromptType,
        WebTokenRequestResult, WebTokenRequestStatus,
    };
    use windows::Win32::Foundation::HWND;
    use windows::Win32::System::WinRT::IWebAuthenticationCoreManagerInterop;
    use windows::core::HSTRING;
    use windows_future::IAsyncOperation;

    use super::{SignInError, WamPrompt, WamRequestSpec, WamToken};

    fn fail(e: windows::core::Error) -> SignInError {
        SignInError::WamFailed(format!("0x{:08X}", e.code().0))
    }

    fn request(spec: &WamRequestSpec) -> Result<WebTokenRequest, SignInError> {
        let provider = WebAuthenticationCoreManager::FindAccountProviderWithAuthorityAsync(
            &HSTRING::from(spec.provider_id.as_str()),
            &HSTRING::from(spec.authority.as_str()),
        )
        .and_then(|op| op.join())
        .map_err(fail)?;
        let prompt = match spec.prompt {
            WamPrompt::ForceAuthentication => WebTokenRequestPromptType::ForceAuthentication,
            WamPrompt::Default => WebTokenRequestPromptType::Default,
        };
        WebTokenRequest::CreateWithPromptType(
            &provider,
            &HSTRING::from(spec.scope.as_str()),
            &HSTRING::from(spec.client_id.as_str()),
            prompt,
        )
        .map_err(fail)
    }

    fn read(result: WebTokenRequestResult) -> Result<WamToken, SignInError> {
        let status = result.ResponseStatus().map_err(fail)?;
        if status == WebTokenRequestStatus::UserCancel {
            return Err(SignInError::Cancelled);
        }
        if status != WebTokenRequestStatus::Success {
            let message = result
                .ResponseError()
                .and_then(|e| e.ErrorMessage())
                .map(|m| m.to_string())
                .unwrap_or_else(|_| format!("status {}", status.0));
            return Err(SignInError::WamFailed(message));
        }
        let response = result
            .ResponseData()
            .and_then(|v| v.GetAt(0))
            .map_err(fail)?;
        let access_token = response.Token().map_err(fail)?.to_string();
        let account_id = response
            .WebAccount()
            .and_then(|a| a.Id())
            .map_err(fail)?
            .to_string();
        Ok(WamToken {
            access_token,
            account_id,
        })
    }

    pub(super) fn request_for_window(
        hwnd: isize,
        spec: &WamRequestSpec,
    ) -> Result<WamToken, SignInError> {
        let req = request(spec)?;
        let interop: IWebAuthenticationCoreManagerInterop = windows::core::factory::<
            WebAuthenticationCoreManager,
            IWebAuthenticationCoreManagerInterop,
        >()
        .map_err(fail)?;
        // SAFETY: `hwnd` is the launcher's own top-level window handle, valid while it is open;
        // the call only reads it to parent the broker dialog.
        let op: IAsyncOperation<WebTokenRequestResult> = unsafe {
            interop.RequestTokenForWindowAsync(HWND(hwnd as *mut core::ffi::c_void), &req)
        }
        .map_err(fail)?;
        read(op.join().map_err(fail)?)
    }

    pub(super) fn silent(spec: &WamRequestSpec, account_id: &str) -> Result<WamToken, SignInError> {
        let req = request(spec)?;
        let provider = req.WebAccountProvider().map_err(fail)?;
        let account =
            WebAuthenticationCoreManager::FindAccountAsync(&provider, &HSTRING::from(account_id))
                .and_then(|op| op.join())
                .map_err(fail)?;
        let result =
            WebAuthenticationCoreManager::GetTokenSilentlyWithWebAccountAsync(&req, &account)
                .and_then(|op| op.join())
                .map_err(fail)?;
        read(result)
    }
}

#[cfg(not(windows))]
mod imp {
    use super::{SignInError, WamRequestSpec, WamToken};

    pub(super) fn request_for_window(
        _hwnd: isize,
        _spec: &WamRequestSpec,
    ) -> Result<WamToken, SignInError> {
        Err(SignInError::WamUnavailable)
    }

    pub(super) fn silent(
        _spec: &WamRequestSpec,
        _account_id: &str,
    ) -> Result<WamToken, SignInError> {
        Err(SignInError::WamUnavailable)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn wam_needs_the_flag_and_a_client_id_of_our_own() {
        assert_eq!(
            WamRequestSpec::interactive("my-azure-app", false),
            Err(SignInError::WamUnavailable)
        );
        assert_eq!(
            WamRequestSpec::interactive(OFFICIAL_CLIENT_ID, true),
            Err(SignInError::WamUnavailable)
        );
        assert_eq!(
            WamRequestSpec::interactive("   ", true),
            Err(SignInError::WamUnavailable)
        );
        assert!(WamFlow::new(OFFICIAL_CLIENT_ID, true, 0).is_err());
    }

    #[cfg(windows)]
    #[test]
    fn the_interactive_request_targets_personal_accounts_and_the_xbox_scope() {
        let spec =
            WamRequestSpec::interactive(" 11111111-2222-3333-4444-555555555555 ", true).unwrap();
        assert_eq!(spec.provider_id, "https://login.microsoft.com");
        assert_eq!(spec.authority, "consumers");
        assert_eq!(spec.scope, "service::user.auth.xboxlive.com::MBI_SSL");
        assert_eq!(spec.client_id, "11111111-2222-3333-4444-555555555555");
        assert_eq!(spec.prompt, WamPrompt::ForceAuthentication);
        let flow = WamFlow::new("app", true, 0).unwrap();
        assert_eq!(flow.kind(), SignInKind::Wam);
        assert_eq!(flow.spec().client_id, "app");
    }

    #[test]
    fn the_silent_request_shows_no_forced_prompt() {
        let spec = WamRequestSpec::silent("app");
        assert_eq!(spec.prompt, WamPrompt::Default);
        assert_eq!(spec.scope, SCOPE_MBI_SSL);
    }

    #[cfg(not(windows))]
    #[test]
    fn wam_is_unavailable_off_windows() {
        assert_eq!(
            WamRequestSpec::interactive("app", true),
            Err(SignInError::WamUnavailable)
        );
        assert_eq!(
            renew_silently("app", "acc"),
            Err(SignInError::WamUnavailable)
        );
    }
}
