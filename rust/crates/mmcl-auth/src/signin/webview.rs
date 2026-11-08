// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The web view sign-in: the launcher re-executes itself as the `--msa-signin` helper
//! (`mmcl-signin-webview`), reads the redirect URL from the helper's stdout and exchanges the
//! code in it.
//!
//! The helper's contract (stdout carries the redirect URL as its last line; exit codes 0 =
//! redirect, 1 = failed, 2 = closed by the user, 3 = bad arguments, 4 = parent gone) is mirrored
//! here because this crate sits below the helper crate. Before the redirect the helper may print
//! `mmcl-signin:loading` / `mmcl-signin:loaded` / `mmcl-signin:load-failed` page lines
//! ([`SignInPageEvent`]); a `reload` line on its stdin loads the page again
//! ([`PageReloader`]).

use std::path::PathBuf;
use std::process::Stdio;
use std::sync::Arc;
use std::time::Duration;

use futures::FutureExt;
use futures::future::BoxFuture;
use mmcl_core::MsaSigninArgs;
use mmcl_i18n::tf;
use mmcl_net::CancelToken;
use tokio::io::{AsyncBufReadExt, BufReader};
use tokio::process::Command;

use super::callback::{BROWSER_USER_AGENT, login_url_for, parse_callback};
use super::{SignInError, SignInFlow};
use crate::msa::{
    MsaClient, MsaTokens, OFFICIAL_CLIENT_ID, REDIRECT_URL, SCOPE_XBOX_SIGNIN, SignInKind,
};

/// Helper exit: the redirect was printed.
const EXIT_REDIRECT: i32 = 0;
/// Helper exit: the window or web view failed.
const EXIT_FAILED: i32 = 1;
/// Helper exit: the user closed the window.
const EXIT_CANCELLED: i32 = 2;
/// Helper exit: bad arguments.
const EXIT_BAD_ARGS: i32 = 3;
/// Helper exit: the parent stopped waiting.
const EXIT_PARENT_GONE: i32 = 4;
/// Makes the helper close when its stdin closes (the parent cancelled or died).
const ARG_WATCH_STDIN: &str = "--watch-stdin";
/// How long a cancelled helper gets to close on its own before it is killed.
const CANCEL_GRACE: Duration = Duration::from_secs(3);
/// Prefix of the helper's page lines (`mmcl_signin_webview::PAGE_LINE_PREFIX`).
pub const PAGE_LINE_PREFIX: &str = "mmcl-signin:";
/// The stdin line that makes the helper load the page again (`mmcl_signin_webview::RELOAD_COMMAND`).
pub const RELOAD_COMMAND: &str = "reload";

/// What the helper's web view reports about Microsoft's page before the redirect.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum SignInPageEvent {
    /// The page is loading (show a spinner).
    Loading,
    /// The page is up.
    Loaded,
    /// The page could not be reached (show "Try again").
    LoadFailed,
}

impl SignInPageEvent {
    /// Parses a helper page line; `None` for anything else.
    pub fn parse_line(line: &str) -> Option<SignInPageEvent> {
        match line.trim().strip_prefix(PAGE_LINE_PREFIX)? {
            "loading" => Some(SignInPageEvent::Loading),
            "loaded" => Some(SignInPageEvent::Loaded),
            "load-failed" => Some(SignInPageEvent::LoadFailed),
            _ => None,
        }
    }
}

/// Receives the helper's page events (called on the flow's task).
pub type PageEventCallback = Arc<dyn Fn(SignInPageEvent) + Send + Sync>;

/// Asks a running helper to load Microsoft's page again (the "Try again" button). Cheap to
/// clone; a reload asked for while no helper runs does nothing.
#[derive(Clone, Default)]
pub struct PageReloader {
    tx: Arc<parking_lot::Mutex<Option<futures::channel::mpsc::UnboundedSender<()>>>>,
}

impl std::fmt::Debug for PageReloader {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("PageReloader")
            .field("attached", &self.tx.lock().is_some())
            .finish()
    }
}

impl PageReloader {
    /// Asks the running helper to reload. `false` when no helper is running.
    pub fn reload(&self) -> bool {
        self.tx
            .lock()
            .as_ref()
            .is_some_and(|tx| tx.unbounded_send(()).is_ok())
    }

    /// Whether a helper is attached.
    pub fn is_attached(&self) -> bool {
        self.tx.lock().is_some()
    }

    fn attach(&self) -> futures::channel::mpsc::UnboundedReceiver<()> {
        let (tx, rx) = futures::channel::mpsc::unbounded();
        *self.tx.lock() = Some(tx);
        rx
    }

    fn detach(&self) {
        self.tx.lock().take();
    }
}

/// The web view helper flow.
#[derive(Clone)]
pub struct WebViewHelperFlow {
    msa: Arc<MsaClient>,
    program: Option<PathBuf>,
    client_id: String,
    title: Option<String>,
    locale: Option<String>,
    on_page: Option<PageEventCallback>,
    reloader: PageReloader,
}

impl std::fmt::Debug for WebViewHelperFlow {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("WebViewHelperFlow")
            .field("program", &self.program)
            .field("client_id", &self.client_id)
            .finish_non_exhaustive()
    }
}

impl WebViewHelperFlow {
    /// A flow that runs the current executable as the helper, with the official client ID.
    pub fn new(msa: Arc<MsaClient>) -> WebViewHelperFlow {
        WebViewHelperFlow {
            msa,
            program: None,
            client_id: OFFICIAL_CLIENT_ID.to_string(),
            title: None,
            locale: None,
            on_page: None,
            reloader: PageReloader::default(),
        }
    }

    /// Reports the helper's page events ([`SignInPageEvent`]) to `callback`.
    pub fn with_page_events(mut self, callback: PageEventCallback) -> Self {
        self.on_page = Some(callback);
        self
    }

    /// The handle that asks the running helper to reload its page. Clones share the helper.
    pub fn reloader(&self) -> PageReloader {
        self.reloader.clone()
    }

    /// Uses another helper executable (default: `std::env::current_exe()`).
    pub fn with_program(mut self, program: impl Into<PathBuf>) -> Self {
        self.program = Some(program.into());
        self
    }

    /// Uses another client ID (its app registration must allow the desktop redirect).
    pub fn with_client_id(mut self, client_id: impl Into<String>) -> Self {
        self.client_id = client_id.into();
        self
    }

    /// The window title.
    pub fn with_title(mut self, title: impl Into<String>) -> Self {
        self.title = Some(title.into());
        self
    }

    /// The BCP-47 tag of the UI language for the helper.
    pub fn with_locale(mut self, locale: impl Into<String>) -> Self {
        self.locale = Some(locale.into());
        self
    }

    /// The helper's arguments (starting with `--msa-signin`).
    pub fn helper_argv(&self) -> Vec<String> {
        MsaSigninArgs {
            login_url: Some(login_url_for(&self.client_id)),
            redirect_prefix: Some(REDIRECT_URL.to_string()),
            title: self.title.clone(),
            user_agent: Some(BROWSER_USER_AGENT.to_string()),
            locale: self.locale.clone(),
            extra: vec![ARG_WATCH_STDIN.to_string()],
        }
        .to_argv()
    }

    /// Runs the helper and returns the authorization code.
    pub async fn obtain_code(&self, cancel: &CancelToken) -> Result<String, SignInError> {
        let program = match &self.program {
            Some(p) => p.clone(),
            None => std::env::current_exe().map_err(|_| SignInError::HelperUnavailable)?,
        };
        let mut child = Command::new(&program)
            .args(self.helper_argv())
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::inherit())
            .kill_on_drop(true)
            .spawn()
            .map_err(|e| {
                mmcl_log::warn_silent(&tf!(
                    "log.authManager.helperSpawnFailed",
                    format!("{:?}", e.kind())
                ));
                SignInError::HelperUnavailable
            })?;
        // Kept open while waiting: closing it tells the helper to exit.
        let mut stdin = child.stdin.take();
        let stdout = child.stdout.take().ok_or(SignInError::HelperUnavailable)?;
        let mut lines = BufReader::new(stdout).lines();
        let mut reloads = self.reloader.attach();
        let on_page = self.on_page.clone();
        let mut on_line = move |l: &str| {
            if let (Some(cb), Some(e)) = (&on_page, SignInPageEvent::parse_line(l)) {
                cb(e);
            }
        };

        let line = loop {
            tokio::select! {
                l = first_redirect_line_with(&mut lines, &mut on_line) => break l,
                Some(()) = futures::StreamExt::next(&mut reloads) => {
                    if let Some(s) = stdin.as_mut() {
                        use tokio::io::AsyncWriteExt;
                        let line = format!("{RELOAD_COMMAND}\n");
                        if s.write_all(line.as_bytes()).await.is_err() || s.flush().await.is_err() {
                            mmcl_log::debug(&mmcl_i18n::t!("log.authManager.helperReloadFailed"));
                        }
                    }
                }
                _ = cancel.cancelled() => {
                    self.reloader.detach();
                    drop(stdin);
                    if tokio::time::timeout(CANCEL_GRACE, child.wait()).await.is_err() {
                        let _ = child.kill().await;
                    }
                    return Err(SignInError::Cancelled);
                }
            }
        };
        self.reloader.detach();
        drop(stdin);
        let code = match tokio::time::timeout(CANCEL_GRACE, child.wait()).await {
            Ok(Ok(status)) => status.code(),
            _ => {
                let _ = child.kill().await;
                None
            }
        };
        if code != Some(EXIT_REDIRECT) {
            mmcl_log::debug(&tf!(
                "log.authManager.helperExited",
                code.map_or_else(|| "-".to_string(), |c| c.to_string())
            ));
        }
        helper_outcome(code, line.as_deref())
    }
}

/// The first stdout line that is the redirect. Anything else a web engine or toolkit prints on
/// stdout is skipped rather than taken for the result; `None` at end of output.
#[cfg(test)]
async fn first_redirect_line<R: tokio::io::AsyncBufRead + Unpin>(
    lines: &mut tokio::io::Lines<R>,
) -> Option<String> {
    first_redirect_line_with(lines, &mut |_| {}).await
}

/// [`first_redirect_line`], handing every other line to `other` (page lines, noise) first.
/// Cancel safe: dropped between lines, nothing is lost.
async fn first_redirect_line_with<R: tokio::io::AsyncBufRead + Unpin>(
    lines: &mut tokio::io::Lines<R>,
    other: &mut (dyn FnMut(&str) + Send),
) -> Option<String> {
    while let Ok(Some(line)) = lines.next_line().await {
        if is_redirect(line.trim()) {
            return Some(line);
        }
        other(&line);
    }
    None
}

/// `true` when `url` is the desktop redirect itself, not merely a URL that begins with its
/// text (`https://login.live.com/oauth20_desktop.srf.example.net/?code=...` is not).
pub fn is_redirect(url: &str) -> bool {
    url.strip_prefix(REDIRECT_URL)
        .is_some_and(|rest| rest.is_empty() || rest.starts_with(['?', '#']))
}

/// Interprets a helper run: a redirect line wins (its code, or the error in it); otherwise the
/// exit code says what happened.
pub fn helper_outcome(exit_code: Option<i32>, line: Option<&str>) -> Result<String, SignInError> {
    if let Some(url) = line.map(str::trim).filter(|l| is_redirect(l)) {
        let cb = parse_callback(url);
        return match (cb.code, cb.error) {
            (Some(code), _) => Ok(code),
            (None, Some(e)) if e.contains("user has denied access") => Err(SignInError::Cancelled),
            (None, e) => Err(SignInError::Denied(e.unwrap_or_default())),
        };
    }
    Err(match exit_code {
        Some(EXIT_CANCELLED) | Some(EXIT_PARENT_GONE) => SignInError::Cancelled,
        Some(EXIT_FAILED) | Some(EXIT_BAD_ARGS) | Some(EXIT_REDIRECT) => {
            SignInError::HelperFailed(exit_code)
        }
        other => SignInError::HelperFailed(other),
    })
}

impl SignInFlow for WebViewHelperFlow {
    fn kind(&self) -> SignInKind {
        SignInKind::WebView
    }

    fn run<'a>(&'a self, cancel: &'a CancelToken) -> BoxFuture<'a, Result<MsaTokens, SignInError>> {
        async move {
            let code = self.obtain_code(cancel).await?;
            let t = self
                .msa
                .exchange_code(&self.client_id, &code, REDIRECT_URL)
                .await?;
            Ok(MsaTokens {
                access_token: t.access_token,
                refresh_token: t.refresh_token,
                client_id: self.client_id.clone(),
                scope: SCOPE_XBOX_SIGNIN.to_string(),
                kind: SignInKind::WebView,
                wam_account_id: None,
            })
        }
        .boxed()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::testutil::ScriptedTransport;

    #[test]
    fn the_helper_gets_the_login_url_redirect_and_browser_agent() {
        let flow = WebViewHelperFlow::new(Arc::new(MsaClient::new(Arc::new(
            ScriptedTransport::default(),
        ))))
        .with_locale("de-DE");
        let argv = flow.helper_argv();
        assert_eq!(argv[0], "--msa-signin");
        let parsed = MsaSigninArgs::parse(&argv[1..]);
        assert_eq!(
            parsed.login_url.as_deref(),
            Some(crate::signin::login_url().as_str())
        );
        assert_eq!(parsed.redirect_prefix.as_deref(), Some(REDIRECT_URL));
        assert_eq!(parsed.user_agent.as_deref(), Some(BROWSER_USER_AGENT));
        assert_eq!(parsed.locale.as_deref(), Some("de-DE"));
        assert!(parsed.extra.iter().any(|a| a == "--watch-stdin"));
    }

    #[test]
    fn helper_results_map_to_codes_and_errors() {
        assert_eq!(
            helper_outcome(
                Some(0),
                Some("https://login.live.com/oauth20_desktop.srf?code=M.C1&lc=1033\n")
            ),
            Ok("M.C1".to_string())
        );
        assert_eq!(
            helper_outcome(
                Some(0),
                Some(
                    "https://login.live.com/oauth20_desktop.srf?error=access_denied&error_description=The+user+has+denied+access"
                )
            ),
            Err(SignInError::Cancelled)
        );
        assert_eq!(
            helper_outcome(
                Some(0),
                Some(
                    "https://login.live.com/oauth20_desktop.srf?error=server_error&error_description=Busy"
                )
            ),
            Err(SignInError::Denied("Busy".into()))
        );
        assert_eq!(helper_outcome(Some(2), None), Err(SignInError::Cancelled));
        assert_eq!(helper_outcome(Some(4), None), Err(SignInError::Cancelled));
        assert_eq!(
            helper_outcome(Some(1), None),
            Err(SignInError::HelperFailed(Some(1)))
        );
        assert_eq!(
            helper_outcome(None, None),
            Err(SignInError::HelperFailed(None))
        );
        // A stray line that is not the redirect is ignored.
        assert_eq!(
            helper_outcome(Some(1), Some("hello")),
            Err(SignInError::HelperFailed(Some(1)))
        );
    }

    #[test]
    fn only_the_redirect_itself_counts() {
        assert!(is_redirect("https://login.live.com/oauth20_desktop.srf"));
        assert!(is_redirect(
            "https://login.live.com/oauth20_desktop.srf?code=x"
        ));
        assert!(is_redirect("https://login.live.com/oauth20_desktop.srf#x"));
        assert!(!is_redirect(
            "https://login.live.com/oauth20_desktop.srf.example.net/?code=x"
        ));
        assert!(!is_redirect(
            "https://example.net/?https://login.live.com/oauth20_desktop.srf"
        ));
        assert_eq!(
            helper_outcome(
                Some(0),
                Some("https://login.live.com/oauth20_desktop.srfx?code=evil")
            ),
            Err(SignInError::HelperFailed(Some(0)))
        );
    }

    #[tokio::test]
    async fn stray_stdout_lines_before_the_redirect_are_skipped() {
        let out: &[u8] =
            b"Gtk-Message: noise\n\nhttps://login.live.com/oauth20_desktop.srf?code=M.C9\n";
        let mut lines = BufReader::new(out).lines();
        assert_eq!(
            first_redirect_line(&mut lines).await.as_deref(),
            Some("https://login.live.com/oauth20_desktop.srf?code=M.C9")
        );
        let none: &[u8] = b"only noise\n";
        assert_eq!(
            first_redirect_line(&mut BufReader::new(none).lines()).await,
            None
        );
    }

    #[tokio::test]
    async fn page_lines_reach_the_callback_before_the_redirect() {
        let out: &[u8] = b"mmcl-signin:loading\nnoise\nmmcl-signin:load-failed\nmmcl-signin:loading\nmmcl-signin:loaded\nhttps://login.live.com/oauth20_desktop.srf?code=M.C2\nmmcl-signin:loaded\n";
        let mut lines = BufReader::new(out).lines();
        let mut seen = Vec::new();
        let line = first_redirect_line_with(&mut lines, &mut |l| {
            if let Some(e) = SignInPageEvent::parse_line(l) {
                seen.push(e);
            }
        })
        .await;
        assert_eq!(
            line.as_deref(),
            Some("https://login.live.com/oauth20_desktop.srf?code=M.C2")
        );
        assert_eq!(
            seen,
            [
                SignInPageEvent::Loading,
                SignInPageEvent::LoadFailed,
                SignInPageEvent::Loading,
                SignInPageEvent::Loaded
            ]
        );
        // An old parent that only wants the redirect still finds it.
        let mut lines = BufReader::new(out).lines();
        assert!(first_redirect_line(&mut lines).await.is_some());
        assert_eq!(
            helper_outcome(Some(0), Some("mmcl-signin:loaded")),
            Err(SignInError::HelperFailed(Some(0)))
        );
    }

    #[test]
    fn page_lines_parse_strictly() {
        assert_eq!(
            SignInPageEvent::parse_line(" mmcl-signin:loaded\r"),
            Some(SignInPageEvent::Loaded)
        );
        assert_eq!(SignInPageEvent::parse_line("mmcl-signin:future"), None);
        assert_eq!(SignInPageEvent::parse_line("loaded"), None);
    }

    #[test]
    fn a_reload_without_a_helper_does_nothing() {
        let r = PageReloader::default();
        assert!(!r.reload());
        assert!(!r.is_attached());
        let mut rx = r.attach();
        assert!(r.reload());
        assert_eq!(rx.try_recv().ok(), Some(()));
        r.detach();
        assert!(!r.reload());
    }

    #[tokio::test]
    async fn a_helper_that_cannot_start_is_unavailable() {
        let flow = WebViewHelperFlow::new(Arc::new(MsaClient::new(Arc::new(
            ScriptedTransport::default(),
        ))))
        .with_program("this-program-does-not-exist-mmcl");
        assert_eq!(
            flow.obtain_code(&CancelToken::new()).await,
            Err(SignInError::HelperUnavailable)
        );
    }
}
