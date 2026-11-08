// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The web view sign-in URL and the redirect parsing (`MicrosoftSignIn`).

use crate::msa::{AUTHORIZE_URL, OFFICIAL_CLIENT_ID, REDIRECT_URL, SCOPE_XBOX_SIGNIN};

/// The User-Agent the web view uses: Microsoft's pages degrade for unknown agents.
pub const BROWSER_USER_AGENT: &str =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:147.0) Gecko/20100101 Firefox/147.0";

/// Java `URLEncoder.encode(...).replace("+", "%20")`.
fn encode(v: &str) -> String {
    url::form_urlencoded::byte_serialize(v.as_bytes())
        .collect::<String>()
        .replace('+', "%20")
}

/// The authorize URL for the official client ID. `prompt=select_account` is required: without
/// it a web view that still holds a Microsoft session signs the cached account back in, so a
/// second account could never be added.
pub fn login_url() -> String {
    login_url_for(OFFICIAL_CLIENT_ID)
}

/// The authorize URL for `client_id`.
pub fn login_url_for(client_id: &str) -> String {
    format!(
        "{AUTHORIZE_URL}?client_id={}&response_type=code&scope={}&redirect_uri={}&prompt=select_account",
        encode(client_id),
        encode(SCOPE_XBOX_SIGNIN),
        encode(REDIRECT_URL)
    )
}

/// What the redirect said.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Callback {
    /// The authorization code.
    pub code: Option<String>,
    /// The error text (`error_description`, or `""`).
    pub error: Option<String>,
}

impl Callback {
    /// `true` when the user declined (Microsoft's `access_denied` description says so).
    pub fn user_cancelled(&self) -> bool {
        self.error
            .as_deref()
            .is_some_and(|e| e.contains("user has denied access"))
    }
}

/// Parses a redirect location. The first `code` wins; any `error` wins over a code; an empty or
/// missing code is an error.
pub fn parse_callback(location: &str) -> Callback {
    let mut code: Option<String> = None;
    let mut saw_error = false;
    let mut description: Option<String> = None;
    if let Some((_, query)) = location.split_once('?') {
        let query = query.split('#').next().unwrap_or("");
        for (name, value) in url::form_urlencoded::parse(query.as_bytes()) {
            match name.as_ref() {
                "code" if code.is_none() => code = Some(value.into_owned()),
                "error" => saw_error = true,
                "error_description" => description = Some(value.into_owned()),
                _ => {}
            }
        }
    }
    match code {
        Some(c) if !saw_error && !c.is_empty() => Callback {
            code: Some(c),
            error: None,
        },
        _ => Callback {
            code: None,
            error: Some(description.unwrap_or_default()),
        },
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_login_url_is_exact() {
        assert_eq!(
            login_url(),
            "https://login.live.com/oauth20_authorize.srf?client_id=00000000402b5328&response_type=code&scope=XboxLive.signin%20offline_access&redirect_uri=https%3A%2F%2Flogin.live.com%2Foauth20_desktop.srf&prompt=select_account"
        );
    }

    #[test]
    fn a_code_is_extracted() {
        let c = parse_callback("https://login.live.com/oauth20_desktop.srf?code=M.C1_abc&lc=1033");
        assert_eq!(c.code.as_deref(), Some("M.C1_abc"));
        assert_eq!(c.error, None);
        assert!(!c.user_cancelled());
    }

    #[test]
    fn the_first_code_wins() {
        let c = parse_callback("https://x/?code=one&code=two");
        assert_eq!(c.code.as_deref(), Some("one"));
    }

    #[test]
    fn an_error_wins_over_a_code() {
        let c = parse_callback("https://x/?code=abc&error=access_denied");
        assert_eq!(c.code, None);
        assert_eq!(c.error.as_deref(), Some(""));
    }

    #[test]
    fn the_error_description_is_decoded() {
        let c = parse_callback(
            "https://login.live.com/oauth20_desktop.srf?error=access_denied&error_description=The+user+has+denied+access+to+the+scope+requested+by+the+client+application.",
        );
        assert_eq!(
            c.error.as_deref(),
            Some("The user has denied access to the scope requested by the client application.")
        );
        assert!(c.user_cancelled());
        let pct = parse_callback("https://x/?error=e&error_description=a%20b%2Cc");
        assert_eq!(pct.error.as_deref(), Some("a b,c"));
    }

    #[test]
    fn the_fragment_is_stripped() {
        let c = parse_callback("https://x/?code=abc#frag");
        assert_eq!(c.code.as_deref(), Some("abc"));
    }

    #[test]
    fn an_empty_code_is_an_error() {
        let c = parse_callback("https://x/?code=");
        assert_eq!(c.code, None);
        assert_eq!(c.error.as_deref(), Some(""));
        let flag = parse_callback("https://x/?code");
        assert_eq!(flag.code, None);
    }

    #[test]
    fn no_query_is_an_unknown_error() {
        let c = parse_callback("https://login.live.com/oauth20_desktop.srf");
        assert_eq!(c.code, None);
        assert_eq!(c.error.as_deref(), Some(""));
        assert!(!c.user_cancelled());
    }
}
