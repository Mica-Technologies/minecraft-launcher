// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Turns the `--msa-signin` arguments into a checked [`HelperConfig`].
//!
//! Pure: no window, no I/O. Defaults match the Java launcher's `MicrosoftSignIn`, so the parent
//! only has to pass `--url`.

use mmcl_core::MsaSigninArgs;

/// Where Microsoft sends the browser when sign-in ends (`Constants.MICROSOFT_OAUTH_REDIRECT_URL`).
/// The only redirect the official client ID `00000000402b5328` allows.
pub const DEFAULT_REDIRECT_PREFIX: &str = "https://login.live.com/oauth20_desktop.srf";

/// The desktop-browser User-Agent the sign-in page is loaded with. Microsoft's pages degrade for
/// agents they do not recognise. It is a generic browser string, not a personal identifier.
pub const DEFAULT_USER_AGENT: &str =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:147.0) Gecko/20100101 Firefox/147.0";

/// Optional flag (carried in [`MsaSigninArgs::extra`]): the helper closes, with
/// [`crate::EXIT_PARENT_GONE`], when its stdin reaches end-of-file. A parent that spawns the
/// helper with a piped stdin can then cancel the sign-in by closing the pipe, and a helper whose
/// parent died does not linger on screen. Without the flag stdin is ignored, so a parent that
/// spawns with a null stdin still works.
pub const ARG_WATCH_STDIN: &str = "--watch-stdin";

/// Checked settings for one helper run.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct HelperConfig {
    /// The Microsoft authorize page to open (an `https` address).
    pub login_url: String,
    /// The navigation prefix that ends the flow (an `https` address).
    pub redirect_prefix: String,
    /// Window title from the parent, or `None` for the localized default.
    pub title: Option<String>,
    /// User-Agent of the web view.
    pub user_agent: String,
    /// BCP-47 tag of the UI language, when the parent passed one.
    pub locale: Option<String>,
    /// Close when stdin reaches end-of-file ([`ARG_WATCH_STDIN`]).
    pub watch_stdin: bool,
}

/// Why the arguments cannot start a sign-in.
#[derive(Clone, Debug, PartialEq, Eq, thiserror::Error)]
pub enum ConfigError {
    /// No `--url` was given (or it was blank).
    #[error("no sign-in page address was given")]
    MissingUrl,
    /// `--url` is not an `https` address with a host.
    #[error("the sign-in page address is not an https address: {0}")]
    InvalidUrl(String),
    /// `--redirect-prefix` is not an `https` address with a host.
    #[error("the redirect prefix is not an https address: {0}")]
    InvalidRedirectPrefix(String),
}

impl ConfigError {
    /// The localization key of the message logged for this error.
    pub fn message_key(&self) -> &'static str {
        match self {
            ConfigError::MissingUrl => "signInHelper.log.missingUrl",
            ConfigError::InvalidUrl(_) => "signInHelper.log.invalidUrl",
            ConfigError::InvalidRedirectPrefix(_) => "signInHelper.log.invalidPrefix",
        }
    }

    /// The localized message for this error.
    pub fn localized(&self) -> String {
        match self {
            ConfigError::MissingUrl => mmcl_i18n::t!(self.message_key()).into_owned(),
            ConfigError::InvalidUrl(v) | ConfigError::InvalidRedirectPrefix(v) => {
                mmcl_i18n::tf!(self.message_key(), v.as_str())
            }
        }
    }
}

/// A trimmed, non-empty value, or `None`.
fn non_blank(value: &Option<String>) -> Option<String> {
    value
        .as_deref()
        .map(str::trim)
        .filter(|v| !v.is_empty())
        .map(str::to_string)
}

/// `true` when `value` is an absolute `https` URL with a non-empty host and no whitespace or
/// control characters.
pub fn is_https_url(value: &str) -> bool {
    if value.chars().any(|c| c.is_whitespace() || c.is_control()) {
        return false;
    }
    match url::Url::parse(value) {
        Ok(u) => u.scheme() == "https" && u.host_str().is_some_and(|h| !h.is_empty()),
        Err(_) => false,
    }
}

impl HelperConfig {
    /// Checks the parsed `--msa-signin` arguments and fills in the defaults.
    pub fn from_args(args: &MsaSigninArgs) -> Result<HelperConfig, ConfigError> {
        let login_url = non_blank(&args.login_url).ok_or(ConfigError::MissingUrl)?;
        if !is_https_url(&login_url) {
            return Err(ConfigError::InvalidUrl(login_url));
        }
        let redirect_prefix =
            non_blank(&args.redirect_prefix).unwrap_or_else(|| DEFAULT_REDIRECT_PREFIX.to_string());
        if !is_https_url(&redirect_prefix) {
            return Err(ConfigError::InvalidRedirectPrefix(redirect_prefix));
        }
        Ok(HelperConfig {
            login_url,
            redirect_prefix,
            title: non_blank(&args.title),
            user_agent: non_blank(&args.user_agent)
                .unwrap_or_else(|| DEFAULT_USER_AGENT.to_string()),
            locale: non_blank(&args.locale),
            watch_stdin: args
                .extra
                .iter()
                .any(|a| a.eq_ignore_ascii_case(ARG_WATCH_STDIN)),
        })
    }

    /// The window title: the parent's, else the localized default.
    pub fn window_title(&self) -> String {
        match &self.title {
            Some(t) => t.clone(),
            None => mmcl_i18n::t!("signInHelper.window.title").into_owned(),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const LOGIN: &str = "https://login.live.com/oauth20_authorize.srf?client_id=00000000402b5328&response_type=code&scope=XboxLive.signin%20offline_access&redirect_uri=https%3A%2F%2Flogin.live.com%2Foauth20_desktop.srf&prompt=select_account";

    fn args(url: Option<&str>) -> MsaSigninArgs {
        MsaSigninArgs {
            login_url: url.map(str::to_string),
            ..Default::default()
        }
    }

    #[test]
    fn only_the_url_is_needed_and_the_rest_defaults_to_the_java_values() {
        let cfg = HelperConfig::from_args(&args(Some(LOGIN))).unwrap();
        assert_eq!(cfg.login_url, LOGIN);
        assert_eq!(cfg.redirect_prefix, DEFAULT_REDIRECT_PREFIX);
        assert_eq!(cfg.user_agent, DEFAULT_USER_AGENT);
        assert_eq!(cfg.title, None);
        assert_eq!(cfg.locale, None);
        assert!(!cfg.watch_stdin);
    }

    #[test]
    fn a_missing_or_blank_url_is_refused() {
        assert_eq!(
            HelperConfig::from_args(&args(None)),
            Err(ConfigError::MissingUrl)
        );
        assert_eq!(
            HelperConfig::from_args(&args(Some("   "))),
            Err(ConfigError::MissingUrl)
        );
    }

    #[test]
    fn only_https_addresses_are_opened() {
        for bad in [
            "http://login.live.com/oauth20_authorize.srf",
            "file:///etc/passwd",
            "javascript:alert(1)",
            "about:blank",
            "not a url",
            "https://",
            "https://login.live.com/a b",
        ] {
            assert!(
                matches!(
                    HelperConfig::from_args(&args(Some(bad))),
                    Err(ConfigError::InvalidUrl(_)) | Err(ConfigError::MissingUrl)
                ),
                "{bad} should be refused"
            );
        }
    }

    #[test]
    fn a_non_https_redirect_prefix_is_refused() {
        let mut a = args(Some(LOGIN));
        a.redirect_prefix = Some("http://localhost/cb".into());
        assert_eq!(
            HelperConfig::from_args(&a),
            Err(ConfigError::InvalidRedirectPrefix(
                "http://localhost/cb".into()
            ))
        );
    }

    #[test]
    fn parent_values_override_the_defaults_and_blanks_do_not() {
        let a = MsaSigninArgs {
            login_url: Some(format!("  {LOGIN} ")),
            redirect_prefix: Some("https://example.test/done".into()),
            title: Some("Add an account".into()),
            user_agent: Some("  ".into()),
            locale: Some("de-DE".into()),
            extra: vec!["--watch-stdin".into(), "--future-flag".into()],
        };
        let cfg = HelperConfig::from_args(&a).unwrap();
        assert_eq!(cfg.login_url, LOGIN);
        assert_eq!(cfg.redirect_prefix, "https://example.test/done");
        assert_eq!(cfg.title.as_deref(), Some("Add an account"));
        assert_eq!(cfg.window_title(), "Add an account");
        assert_eq!(cfg.user_agent, DEFAULT_USER_AGENT);
        assert_eq!(cfg.locale.as_deref(), Some("de-DE"));
        assert!(cfg.watch_stdin);
    }

    #[test]
    fn the_argv_the_parent_builds_round_trips() {
        let sent = MsaSigninArgs {
            login_url: Some(LOGIN.into()),
            extra: vec![ARG_WATCH_STDIN.into()],
            ..Default::default()
        };
        let argv = sent.to_argv();
        let parsed = match mmcl_core::args::pre_session(&argv) {
            mmcl_core::PreSession::MsaSignin(a) => a,
            other => panic!("not the helper mode: {other:?}"),
        };
        let cfg = HelperConfig::from_args(&parsed).unwrap();
        assert_eq!(cfg.login_url, LOGIN);
        assert!(cfg.watch_stdin);
    }

    #[test]
    fn every_error_has_an_english_message() {
        for e in [
            ConfigError::MissingUrl,
            ConfigError::InvalidUrl("x".into()),
            ConfigError::InvalidRedirectPrefix("y".into()),
        ] {
            assert!(
                mmcl_i18n::english(e.message_key()).is_some(),
                "missing key {}",
                e.message_key()
            );
        }
        assert!(mmcl_i18n::english("signInHelper.window.title").is_some());
    }
}
