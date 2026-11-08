// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Interactive sign-in flows. Every flow only produces a Microsoft OAuth result
//! ([`MsaTokens`]); [`crate::AuthService::sign_in`] runs the shared Xbox -> XSTS -> Minecraft
//! chain after it.
//!
//! - [`WebViewHelperFlow`]: re-executes the launcher as `--msa-signin`, which shows Microsoft's
//!   page in a `tao` + `wry` window and prints the `oauth20_desktop.srf` redirect on stdout; the
//!   code in it is exchanged for tokens.
//! - [`DeviceCodeFlow`]: a code the user enters at microsoft.com/link on any device. First-class,
//!   and the default in gamepad/handheld mode (Steam Deck) where typing a password into a web
//!   view is awkward.
//! - [`WamFlow`]: the Windows account broker. Only when `auth.wam` is on and a client ID of our
//!   own is configured; compiled on Windows, an error elsewhere.

mod callback;
mod device_code;
mod wam;
mod webview;

pub use callback::{BROWSER_USER_AGENT, Callback, login_url, login_url_for, parse_callback};
pub use device_code::{DeviceCodeFlow, DeviceCodePrompt, PromptCallback};
pub use wam::{
    WAM_AUTHORITY, WAM_PROVIDER_ID, WamFlow, WamPrompt, WamRequestSpec, WamToken,
    renew_silently as wam_renew_silently,
};
pub use webview::{
    PAGE_LINE_PREFIX, PageEventCallback, PageReloader, RELOAD_COMMAND, SignInPageEvent,
    WebViewHelperFlow, helper_outcome,
};

pub use crate::msa::{MsaTokens, REDIRECT_URL, SignInKind};

use futures::future::BoxFuture;
use mmcl_net::CancelToken;

use crate::msa::MsaError;

/// Why a sign-in flow produced no Microsoft token.
#[derive(Clone, Debug, PartialEq, Eq, thiserror::Error)]
pub enum SignInError {
    /// The user closed the window, cancelled or declined on Microsoft's page.
    #[error("sign-in was cancelled")]
    Cancelled,
    /// Microsoft's page reported an error (its `error_description`).
    #[error("Microsoft did not complete the sign-in")]
    Denied(String),
    /// The device code expired before it was used.
    #[error("the device code expired")]
    DeviceCodeExpired,
    /// The device code sign-in was declined on the other device.
    #[error("the device code sign-in was declined")]
    DeviceCodeDeclined,
    /// No web view on this platform, or the helper could not be started.
    #[error("the sign-in window is not available")]
    HelperUnavailable,
    /// The helper exited without a result (exit code, if any).
    #[error("the sign-in window failed")]
    HelperFailed(Option<i32>),
    /// WAM is not enabled, not configured with a client ID of our own, or not on Windows.
    #[error("Windows account sign-in is not available")]
    WamUnavailable,
    /// The broker refused (its message).
    #[error("Windows account sign-in failed")]
    WamFailed(String),
    /// A protocol call of the flow failed.
    #[error("{0}")]
    Msa(MsaError),
}

impl From<MsaError> for SignInError {
    fn from(e: MsaError) -> Self {
        SignInError::Msa(e)
    }
}

impl SignInError {
    /// A data-free name for logs.
    pub fn kind_name(&self) -> &'static str {
        match self {
            SignInError::Cancelled => "Cancelled",
            SignInError::Denied(_) => "Denied",
            SignInError::DeviceCodeExpired => "DeviceCodeExpired",
            SignInError::DeviceCodeDeclined => "DeviceCodeDeclined",
            SignInError::HelperUnavailable => "HelperUnavailable",
            SignInError::HelperFailed(_) => "HelperFailed",
            SignInError::WamUnavailable => "WamUnavailable",
            SignInError::WamFailed(_) => "WamFailed",
            SignInError::Msa(e) => e.kind_name(),
        }
    }

    /// The localization key of the user-facing message.
    pub fn l10n_key(&self) -> &'static str {
        match self {
            SignInError::Cancelled => "signIn.error.cancelled",
            SignInError::Denied(_) => "signIn.error.denied",
            SignInError::DeviceCodeExpired => "signIn.error.deviceCodeExpired",
            SignInError::DeviceCodeDeclined => "signIn.error.deviceCodeDeclined",
            SignInError::HelperUnavailable => "signIn.error.helperUnavailable",
            SignInError::HelperFailed(_) => "signIn.error.helperFailed",
            SignInError::WamUnavailable => "signIn.error.wamUnavailable",
            SignInError::WamFailed(_) => "signIn.error.wamFailed",
            SignInError::Msa(MsaError::Transport(_)) => "signIn.error.network",
            SignInError::Msa(MsaError::Timeout) => "signIn.error.timedOut",
            SignInError::Msa(MsaError::OAuth { .. }) => "signIn.error.badCredentials",
            SignInError::Msa(_) => "signIn.error.protocol",
        }
    }

    /// The localized message.
    pub fn message(&self) -> String {
        match self {
            SignInError::Denied(d) | SignInError::WamFailed(d) => {
                mmcl_i18n::tf!(self.l10n_key(), d.as_str())
            }
            _ => mmcl_i18n::t!(self.l10n_key()).into_owned(),
        }
    }
}

/// An interactive sign-in that ends with a Microsoft token.
pub trait SignInFlow: Send + Sync {
    /// Which flow this is.
    fn kind(&self) -> SignInKind;

    /// Runs the flow until Microsoft hands out a token, the user cancels, or `cancel` fires.
    fn run<'a>(&'a self, cancel: &'a CancelToken) -> BoxFuture<'a, Result<MsaTokens, SignInError>>;
}

/// The flows to offer, in order: the device code first in gamepad mode (or where there is no
/// web view), the web view first on a desktop; WAM, when active, leads on Windows.
pub fn offered_flows(
    gamepad_mode: bool,
    web_view_supported: bool,
    wam_active: bool,
) -> Vec<SignInKind> {
    let mut out = Vec::new();
    if wam_active && cfg!(windows) && !gamepad_mode {
        out.push(SignInKind::Wam);
    }
    if gamepad_mode || !web_view_supported {
        out.push(SignInKind::DeviceCode);
        if web_view_supported {
            out.push(SignInKind::WebView);
        }
    } else {
        out.push(SignInKind::WebView);
        out.push(SignInKind::DeviceCode);
    }
    if wam_active && cfg!(windows) && gamepad_mode {
        out.push(SignInKind::Wam);
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn handhelds_default_to_the_device_code() {
        assert_eq!(
            offered_flows(true, true, false),
            vec![SignInKind::DeviceCode, SignInKind::WebView]
        );
        assert_eq!(
            offered_flows(false, true, false),
            vec![SignInKind::WebView, SignInKind::DeviceCode]
        );
        assert_eq!(
            offered_flows(false, false, false),
            vec![SignInKind::DeviceCode]
        );
    }

    #[test]
    fn wam_is_offered_only_when_active_on_windows() {
        let flows = offered_flows(false, true, true);
        assert_eq!(flows.contains(&SignInKind::Wam), cfg!(windows));
        if cfg!(windows) {
            assert_eq!(flows[0], SignInKind::Wam);
        }
    }

    #[test]
    fn every_sign_in_error_key_exists_in_english() {
        let errors = [
            SignInError::Cancelled,
            SignInError::Denied("x".into()),
            SignInError::DeviceCodeExpired,
            SignInError::DeviceCodeDeclined,
            SignInError::HelperUnavailable,
            SignInError::HelperFailed(Some(1)),
            SignInError::WamUnavailable,
            SignInError::WamFailed("x".into()),
            SignInError::Msa(MsaError::Timeout),
            SignInError::Msa(MsaError::Transport(crate::http::TransportError::Connect)),
            SignInError::Msa(MsaError::Parse("x")),
            SignInError::Msa(MsaError::OAuth {
                error: "e".into(),
                description: None,
            }),
        ];
        for e in errors {
            assert!(
                mmcl_i18n::english(e.l10n_key()).is_some(),
                "missing i18n key {}",
                e.l10n_key()
            );
            assert!(!e.message().is_empty());
        }
    }
}
