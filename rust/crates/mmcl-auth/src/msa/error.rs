// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Protocol errors. Display text never carries tokens or response bodies.

use crate::http::TransportError;

/// OAuth `error` codes that mean the saved credentials are dead and the user must sign in again.
pub const REJECTING_OAUTH_ERRORS: &[&str] = &[
    "invalid_grant",
    "interaction_required",
    "unauthorized_client",
    "consent_required",
];

/// Why the Microsoft -> Xbox -> Minecraft chain failed.
#[derive(Clone, Debug, PartialEq, Eq, thiserror::Error)]
pub enum MsaError {
    /// The token endpoint answered with an OAuth error (`invalid_grant`, ...).
    #[error("Cannot get oAuth token: {error}")]
    OAuth {
        error: String,
        description: Option<String>,
    },
    /// Xbox Live user authentication failed with this HTTP status.
    #[error("Cannot authenticate with xbl, http code {0}")]
    XblHttp(u16),
    /// Xbox Live returned no user hash.
    #[error("Xui (user hashes) cannot be missing")]
    XblNoXui,
    /// XSTS refused the account.
    #[error("Cannot authorize with xsts for minecraft services (XErr {xerr})")]
    Xsts { xerr: i64, message: Option<String> },
    /// XSTS returned no user hash.
    #[error("XSTS returned no user hashes")]
    XstsNoXui,
    /// The Minecraft launcher login failed with this HTTP status.
    #[error("Cannot login into minecraft with xbox, http code {0}")]
    McLoginHttp(u16),
    /// The entitlements call failed with this HTTP status.
    #[error("Cannot get minecraft entitlements, http code {0}")]
    EntitlementsHttp(u16),
    /// No entitlements: the account does not own the game. The wording keeps the Java
    /// launcher's `not have bought` substring.
    #[error("This account does not have bought minecraft")]
    NotOwned,
    /// The profile call failed with this HTTP status (404: no profile created yet).
    #[error("Cannot get minecraft profile data, http code {0}")]
    ProfileHttp(u16),
    /// The Minecraft access token is not a readable JWT.
    #[error("Could not parse minecraft access token")]
    BadAccessToken,
    /// No response.
    #[error("{0}")]
    Transport(TransportError),
    /// A response was not the expected JSON; names the step.
    #[error("Unexpected response from {0}")]
    Parse(&'static str),
    /// The whole chain took longer than the sign-in timeout.
    #[error("timed out")]
    Timeout,
}

impl MsaError {
    /// `true` when the saved credentials are dead: the account needs to sign in again rather
    /// than retry later. Covers the OAuth refusal codes (`invalid_grant`: revoked or expired
    /// refresh token) and a missing game licence. The Java launcher looked for an "invalid
    /// credentials" message the library never produced, so it treated these as transient.
    pub fn credentials_rejected(&self) -> bool {
        match self {
            MsaError::OAuth { error, .. } => REJECTING_OAUTH_ERRORS
                .iter()
                .any(|e| error.eq_ignore_ascii_case(e)),
            MsaError::NotOwned => true,
            _ => false,
        }
    }

    /// A data-free name for logs (the Java logged the exception's simple class name only).
    pub fn kind_name(&self) -> &'static str {
        match self {
            MsaError::OAuth { .. } => "OAuthError",
            MsaError::XblHttp(_) => "XblHttp",
            MsaError::XblNoXui => "XblNoXui",
            MsaError::Xsts { .. } => "XstsError",
            MsaError::XstsNoXui => "XstsNoXui",
            MsaError::McLoginHttp(_) => "McLoginHttp",
            MsaError::EntitlementsHttp(_) => "EntitlementsHttp",
            MsaError::NotOwned => "NotOwned",
            MsaError::ProfileHttp(_) => "ProfileHttp",
            MsaError::BadAccessToken => "BadAccessToken",
            MsaError::Transport(t) => t.kind_name(),
            MsaError::Parse(_) => "Parse",
            MsaError::Timeout => "Timeout",
        }
    }
}

impl From<TransportError> for MsaError {
    fn from(e: TransportError) -> Self {
        MsaError::Transport(e)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn invalid_grant_and_not_owned_mean_sign_in_again() {
        let oauth = |e: &str| MsaError::OAuth {
            error: e.into(),
            description: Some("token: abc".into()),
        };
        assert!(oauth("invalid_grant").credentials_rejected());
        assert!(oauth("interaction_required").credentials_rejected());
        assert!(!oauth("temporarily_unavailable").credentials_rejected());
        assert!(MsaError::NotOwned.credentials_rejected());
        assert!(!MsaError::Timeout.credentials_rejected());
        assert!(!MsaError::XblHttp(401).credentials_rejected());
    }

    #[test]
    fn display_keeps_the_java_substring_and_hides_descriptions() {
        assert!(MsaError::NotOwned.to_string().contains("not have bought"));
        let e = MsaError::OAuth {
            error: "invalid_grant".into(),
            description: Some("secret fragment".into()),
        };
        assert!(!e.to_string().contains("secret"));
    }
}
