// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Sign-in outcomes, error classification and the renewal-due rule.

use crate::msa::MsaError;
use crate::signin::SignInError;
use crate::user::User;

/// The result of an interactive sign-in or a renewal as screens see it (Java
/// `MCLauncherAuthResult` and its five sentinels, plus finer Xbox and flow outcomes).
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum AuthOutcome {
    /// Signed in.
    Ok(User),
    /// Microsoft did not accept the credentials (`ERROR_BAD_USERNAME_PASSWORD`).
    BadCredentials,
    /// The saved sign-in is no longer valid (`ERROR_LOGIN_EXPIRED`).
    LoginExpired,
    /// The account does not own Minecraft: Java Edition (`ERROR_NOT_OWNED`).
    NotOwned,
    /// A required value was missing (`ERROR_NO_VAL`).
    NoValue,
    /// Anything else (`ERROR_OTHER`).
    Other,
    /// The account has no Xbox profile yet (XSTS `2148916233`).
    NoXboxAccount,
    /// Xbox Live refused the account: country (`2148916235`), adult verification
    /// (`2148916236`/`7`), child account outside a family (`2148916238`) or another `XErr`.
    XboxRestricted(i64),
    /// The account owns the game but has no Minecraft profile (profile HTTP 404).
    NoProfile,
    /// The sign-in flow itself did not produce a Microsoft token (cancelled, code expired,
    /// helper failed, ...).
    Flow(SignInError),
}

/// XSTS: the account has no Xbox profile.
pub const XERR_NO_XBOX: i64 = 2_148_916_233;
/// XSTS: Xbox Live is not available in the account's country.
pub const XERR_COUNTRY: i64 = 2_148_916_235;
/// XSTS: adult verification needed (South Korea).
pub const XERR_ADULT_A: i64 = 2_148_916_236;
/// XSTS: adult verification needed (South Korea).
pub const XERR_ADULT_B: i64 = 2_148_916_237;
/// XSTS: a child account must be in a family group.
pub const XERR_CHILD: i64 = 2_148_916_238;

impl AuthOutcome {
    /// `true` for [`AuthOutcome::Ok`].
    pub fn is_ok(&self) -> bool {
        matches!(self, AuthOutcome::Ok(_))
    }

    /// The user of a successful outcome.
    pub fn user(&self) -> Option<&User> {
        match self {
            AuthOutcome::Ok(u) => Some(u),
            _ => None,
        }
    }

    /// `true` when the user cancelled (shown differently from a failure).
    pub fn is_cancelled(&self) -> bool {
        matches!(self, AuthOutcome::Flow(SignInError::Cancelled))
    }

    /// The localization key of the message a sign-in screen shows; `None` for success.
    pub fn l10n_key(&self) -> Option<&'static str> {
        Some(match self {
            AuthOutcome::Ok(_) => return None,
            AuthOutcome::BadCredentials => "signIn.error.badCredentials",
            AuthOutcome::LoginExpired => "signIn.error.loginExpired",
            AuthOutcome::NotOwned => "signIn.notOwned",
            AuthOutcome::NoValue | AuthOutcome::Other => "signIn.failed",
            AuthOutcome::NoXboxAccount => "signIn.error.noXbox",
            AuthOutcome::XboxRestricted(x) => match *x {
                XERR_COUNTRY => "signIn.error.xboxCountry",
                XERR_ADULT_A | XERR_ADULT_B => "signIn.error.xboxAdultVerification",
                XERR_CHILD => "signIn.error.xboxChild",
                _ => "signIn.error.xboxRestricted",
            },
            AuthOutcome::NoProfile => "signIn.error.noProfile",
            AuthOutcome::Flow(e) => e.l10n_key(),
        })
    }

    /// The localized message; `None` for success.
    pub fn message(&self) -> Option<String> {
        let key = self.l10n_key()?;
        Some(match self {
            AuthOutcome::XboxRestricted(x) => mmcl_i18n::tf!(key, x.to_string()),
            AuthOutcome::Flow(e) => e.message(),
            _ => mmcl_i18n::t!(key).into_owned(),
        })
    }

    /// The outcome of an interactive sign-in that failed in the protocol chain.
    pub fn from_msa_error(e: &MsaError) -> AuthOutcome {
        match e {
            MsaError::NotOwned => AuthOutcome::NotOwned,
            MsaError::ProfileHttp(404) => AuthOutcome::NoProfile,
            MsaError::Xsts {
                xerr: XERR_NO_XBOX, ..
            } => AuthOutcome::NoXboxAccount,
            MsaError::Xsts { xerr, .. } if *xerr != 0 => AuthOutcome::XboxRestricted(*xerr),
            _ => AuthOutcome::Other,
        }
    }
}

/// Classifies an error message the way the Java `processAuthException` does: case-insensitive
/// substrings, `not have bought` first, then `invalid credentials`, then `no value present`.
pub fn classify(message: Option<&str>) -> AuthOutcome {
    if is_not_bought(message) {
        AuthOutcome::NotOwned
    } else if is_invalid_credentials(message) {
        AuthOutcome::BadCredentials
    } else if is_no_value_present(message) {
        AuthOutcome::NoValue
    } else {
        AuthOutcome::Other
    }
}

fn contains_ci(message: Option<&str>, needle: &str) -> bool {
    message.is_some_and(|m| m.to_lowercase().contains(needle))
}

/// Java `checkIfExceptionIsNotBought`.
pub fn is_not_bought(message: Option<&str>) -> bool {
    contains_ci(message, "not have bought")
}

/// Java `checkIfExceptionIsInvalidCredentials`.
pub fn is_invalid_credentials(message: Option<&str>) -> bool {
    contains_ci(message, "invalid credentials")
}

/// Java `checkIfExceptionIsNoValuePresent`.
pub fn is_no_value_present(message: Option<&str>) -> bool {
    contains_ci(message, "no value present")
}

/// Whether a token renewed at `last_ms` is due at `now_ms`: unknown (`<= 0`) is due; a clock
/// that went backwards is deliberately *not* due (no re-login loop).
pub fn is_renewal_due(last_ms: i64, now_ms: i64, interval_ms: i64) -> bool {
    if last_ms <= 0 {
        return true;
    }
    now_ms.saturating_sub(last_ms) >= interval_ms
}

#[cfg(test)]
mod tests {
    use super::*;

    const NOW: i64 = 1_700_000_000_000;
    const FOUR_HOURS: i64 = 4 * 60 * 60 * 1000;

    #[test]
    fn renewal_due_boundaries() {
        assert!(!is_renewal_due(NOW, NOW, FOUR_HOURS));
        assert!(!is_renewal_due(NOW - 3_600_000, NOW, FOUR_HOURS));
        assert!(!is_renewal_due(NOW - FOUR_HOURS + 1, NOW, FOUR_HOURS));
        assert!(is_renewal_due(NOW - FOUR_HOURS, NOW, FOUR_HOURS));
        assert!(is_renewal_due(NOW - FOUR_HOURS * 3, NOW, FOUR_HOURS));
        assert!(is_renewal_due(0, NOW, FOUR_HOURS));
        assert!(is_renewal_due(-1, NOW, FOUR_HOURS));
        assert!(!is_renewal_due(NOW + 86_400_000, NOW, FOUR_HOURS));
        let ten_minutes = 600_000;
        assert!(is_renewal_due(NOW - ten_minutes, NOW, ten_minutes));
        assert!(!is_renewal_due(NOW - ten_minutes, NOW, FOUR_HOURS));
    }

    #[test]
    fn not_bought_classification() {
        assert!(is_not_bought(Some(
            "This account does not have bought minecraft"
        )));
        assert!(is_not_bought(Some("ACCOUNT DOES NOT HAVE BOUGHT THE GAME")));
        assert!(!is_not_bought(Some("connection timed out")));
        assert!(!is_not_bought(None));
    }

    #[test]
    fn invalid_credentials_classification() {
        assert!(is_invalid_credentials(Some("invalid credentials supplied")));
        assert!(is_invalid_credentials(Some("Invalid Credentials")));
        assert!(!is_invalid_credentials(Some("no value present")));
        assert!(!is_invalid_credentials(None));
    }

    #[test]
    fn no_value_classification() {
        assert!(is_no_value_present(Some("No value present")));
        assert!(!is_no_value_present(Some("invalid credentials")));
        assert!(!is_no_value_present(None));
    }

    #[test]
    fn process_auth_exception_mapping() {
        assert_eq!(
            classify(Some("account does not have bought the game")),
            AuthOutcome::NotOwned
        );
        assert_eq!(
            classify(Some("invalid credentials")),
            AuthOutcome::BadCredentials
        );
        assert_eq!(classify(Some("no value present")), AuthOutcome::NoValue);
        assert_eq!(
            classify(Some("the server closed the connection")),
            AuthOutcome::Other
        );
        assert_eq!(classify(None), AuthOutcome::Other);
        assert_eq!(classify(Some("")), AuthOutcome::Other);
        assert_eq!(
            classify(Some(
                "invalid credentials: account does not have bought the game"
            )),
            AuthOutcome::NotOwned
        );
    }

    #[test]
    fn xbox_errors_get_their_own_outcomes() {
        let x = |xerr| MsaError::Xsts {
            xerr,
            message: None,
        };
        assert_eq!(
            AuthOutcome::from_msa_error(&x(XERR_NO_XBOX)),
            AuthOutcome::NoXboxAccount
        );
        assert_eq!(
            AuthOutcome::from_msa_error(&x(XERR_CHILD)).l10n_key(),
            Some("signIn.error.xboxChild")
        );
        assert_eq!(
            AuthOutcome::from_msa_error(&MsaError::NotOwned),
            AuthOutcome::NotOwned
        );
        assert_eq!(
            AuthOutcome::from_msa_error(&MsaError::ProfileHttp(404)),
            AuthOutcome::NoProfile
        );
        assert_eq!(
            AuthOutcome::from_msa_error(&MsaError::XblHttp(500)),
            AuthOutcome::Other
        );
    }

    #[test]
    fn every_outcome_message_key_exists_in_english() {
        let outcomes = [
            AuthOutcome::BadCredentials,
            AuthOutcome::LoginExpired,
            AuthOutcome::NotOwned,
            AuthOutcome::NoValue,
            AuthOutcome::Other,
            AuthOutcome::NoXboxAccount,
            AuthOutcome::XboxRestricted(XERR_COUNTRY),
            AuthOutcome::XboxRestricted(XERR_ADULT_A),
            AuthOutcome::XboxRestricted(XERR_CHILD),
            AuthOutcome::XboxRestricted(1),
            AuthOutcome::NoProfile,
        ];
        for o in outcomes {
            let key = o.l10n_key().unwrap();
            assert!(mmcl_i18n::english(key).is_some(), "missing i18n key {key}");
        }
    }
}
