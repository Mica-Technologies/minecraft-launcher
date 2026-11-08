// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Which account a launch uses (`LaunchAccountResolver`): the pack's override, else the default.
//! An override never quietly falls back to the default: playing on the wrong account is worse
//! than one extra click.

use crate::accounts::{AccountInfo, Status};

/// Why no account could be chosen.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum Problem {
    /// Nothing is signed in (or there is no default).
    NoAccount,
    /// The pack's override names an account that is not signed in.
    OverrideMissing,
    /// The chosen account must sign in again.
    NeedsSignIn,
}

/// The resolver's answer.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Resolution {
    /// The account to launch with; `None` when there is a problem.
    pub uuid: Option<String>,
    pub problem: Option<Problem>,
    /// The chosen account's name, for messages.
    pub account_name: Option<String>,
    /// Whether the choice came from the pack's override.
    pub from_override: bool,
}

impl Resolution {
    /// `true` when an account was chosen.
    pub fn ok(&self) -> bool {
        self.problem.is_none()
    }
}

/// A launch that cannot go ahead.
#[derive(Clone, Debug, PartialEq, Eq, thiserror::Error)]
#[error("launch blocked: {:?}", .0.problem)]
pub struct Blocked(pub Resolution);

impl Blocked {
    /// A "needs sign-in" block for the account `resolution` chose.
    pub fn needs_sign_in(resolution: &Resolution) -> Blocked {
        Blocked(Resolution {
            uuid: None,
            problem: Some(Problem::NeedsSignIn),
            account_name: resolution.account_name.clone(),
            from_override: resolution.from_override,
        })
    }

    /// The problem.
    pub fn problem(&self) -> Option<Problem> {
        self.0.problem
    }
}

/// Picks the launch account. A blank override is no override. A `Refreshing` account can still
/// launch.
pub fn resolve(override_uuid: Option<&str>, accounts: &[AccountInfo]) -> Resolution {
    let override_uuid = override_uuid.filter(|o| !o.trim().is_empty());
    let has_override = override_uuid.is_some();
    let chosen = accounts.iter().find(|a| match override_uuid {
        Some(o) => a.uuid == o,
        None => a.is_default,
    });
    match chosen {
        None => Resolution {
            uuid: None,
            problem: Some(if has_override {
                Problem::OverrideMissing
            } else {
                Problem::NoAccount
            }),
            account_name: None,
            from_override: has_override,
        },
        Some(a) if a.status == Status::NeedsSignIn => Resolution {
            uuid: None,
            problem: Some(Problem::NeedsSignIn),
            account_name: Some(a.display_name.clone()),
            from_override: has_override,
        },
        Some(a) => Resolution {
            uuid: Some(a.uuid.clone()),
            problem: None,
            account_name: Some(a.display_name.clone()),
            from_override: has_override,
        },
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn acct(uuid: &str, name: &str, status: Status, is_default: bool) -> AccountInfo {
        AccountInfo {
            uuid: uuid.into(),
            display_name: name.into(),
            status,
            is_default,
            remembered: true,
            last_used_ms: 0,
        }
    }

    fn two() -> Vec<AccountInfo> {
        vec![
            acct("a", "Alex", Status::Ready, true),
            acct("b", "Steve", Status::Ready, false),
        ]
    }

    #[test]
    fn no_override_uses_the_default() {
        let r = resolve(None, &two());
        assert!(r.ok());
        assert_eq!(r.uuid.as_deref(), Some("a"));
        assert_eq!(r.account_name.as_deref(), Some("Alex"));
        assert!(!r.from_override);
    }

    #[test]
    fn a_blank_override_is_no_override() {
        let r = resolve(Some("  "), &two());
        assert_eq!(r.uuid.as_deref(), Some("a"));
        assert!(!r.from_override);
    }

    #[test]
    fn an_override_wins_over_the_default() {
        let r = resolve(Some("b"), &two());
        assert_eq!(r.uuid.as_deref(), Some("b"));
        assert!(r.from_override);
    }

    #[test]
    fn an_override_for_an_account_that_is_gone_blocks_instead_of_falling_back() {
        let r = resolve(Some("z"), &two());
        assert_eq!(r.problem, Some(Problem::OverrideMissing));
        assert_eq!(r.uuid, None);
        assert!(r.from_override);
    }

    #[test]
    fn an_override_account_that_needs_signing_in_blocks() {
        let accounts = vec![
            acct("a", "Alex", Status::Ready, true),
            acct("b", "Steve", Status::NeedsSignIn, false),
        ];
        let r = resolve(Some("b"), &accounts);
        assert_eq!(r.problem, Some(Problem::NeedsSignIn));
        assert_eq!(r.account_name.as_deref(), Some("Steve"));
        assert!(r.from_override);
    }

    #[test]
    fn a_default_that_needs_signing_in_blocks() {
        let accounts = vec![acct("a", "Alex", Status::NeedsSignIn, true)];
        let r = resolve(None, &accounts);
        assert_eq!(r.problem, Some(Problem::NeedsSignIn));
        assert!(!r.from_override);
    }

    #[test]
    fn a_refreshing_account_can_still_launch() {
        let accounts = vec![acct("a", "Alex", Status::Refreshing, true)];
        assert!(resolve(None, &accounts).ok());
    }

    #[test]
    fn no_accounts_at_all() {
        let r = resolve(None, &[]);
        assert_eq!(r.problem, Some(Problem::NoAccount));
        assert!(!r.from_override);
        assert_eq!(Blocked(r).to_string(), "launch blocked: Some(NoAccount)");
    }
}
