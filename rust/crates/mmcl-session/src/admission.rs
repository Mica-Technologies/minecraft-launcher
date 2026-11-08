// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Whether a launch may start next to the games already active (Java `LaunchAdmission`).
//!
//! One pack runs once at a time: it has one install folder (saves, options, mods, natives) and a
//! running game locks its jars on Windows. One account plays one game at a time: Minecraft signs
//! the older session out when an account logs in twice. Server launches have no account and
//! never collide on one.

use std::sync::Arc;

use crate::session::GameSession;

/// The admission verdict.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum Outcome {
    Ok,
    /// The same pack is already preparing or running.
    PackAlreadyRunning,
    /// The same account is already playing another game.
    AccountBusy,
    /// One-at-a-time mode and some other game is active.
    AnotherGameRunning,
}

impl Outcome {
    /// The localization key of the warning shown when a launch is refused (`None` for `Ok`).
    pub fn l10n_key(self) -> Option<&'static str> {
        match self {
            Outcome::Ok => None,
            Outcome::PackAlreadyRunning => Some("launch.refused.packRunning"),
            Outcome::AccountBusy => Some("launch.refused.accountBusy"),
            Outcome::AnotherGameRunning => Some("launch.refused.anotherGame"),
        }
    }
}

/// An outcome plus the session it conflicts with.
#[derive(Clone, Debug)]
pub struct Decision {
    pub outcome: Outcome,
    pub conflicting: Option<Arc<GameSession>>,
}

impl Decision {
    /// Whether the launch may go ahead.
    pub fn ok(&self) -> bool {
        self.outcome == Outcome::Ok
    }

    /// The localized reason a launch of `pack_friendly_name` was refused (`""` when admitted).
    pub fn message(&self, pack_friendly_name: &str) -> String {
        let other_pack = self
            .conflicting
            .as_ref()
            .map(|s| s.pack_name().to_string())
            .unwrap_or_default();
        match self.outcome {
            Outcome::Ok => String::new(),
            Outcome::PackAlreadyRunning => {
                mmcl_i18n::tf!("launch.refused.packRunning", pack_friendly_name)
            }
            Outcome::AccountBusy => {
                let account = self
                    .conflicting
                    .as_ref()
                    .and_then(|s| s.account_name().map(str::to_string))
                    .unwrap_or_default();
                mmcl_i18n::tf!("launch.refused.accountBusy", account, other_pack)
            }
            Outcome::AnotherGameRunning => {
                mmcl_i18n::tf!("launch.refused.anotherGame", other_pack)
            }
        }
    }

    pub(crate) fn admitted() -> Self {
        Decision {
            outcome: Outcome::Ok,
            conflicting: None,
        }
    }
}

/// Checks `candidate` against `others` (the candidate itself and inactive sessions are
/// skipped). The specific conflict (same pack, then same account) is reported first, even in
/// one-at-a-time mode.
pub fn check(
    candidate: &GameSession,
    others: &[Arc<GameSession>],
    one_at_a_time: bool,
) -> Decision {
    let active = || {
        others
            .iter()
            .filter(|s| !std::ptr::eq(s.as_ref(), candidate) && s.is_active())
    };
    for s in active() {
        if s.pack_key() == candidate.pack_key() {
            return Decision {
                outcome: Outcome::PackAlreadyRunning,
                conflicting: Some(Arc::clone(s)),
            };
        }
        if let Some(uuid) = s.account_uuid()
            && Some(uuid) == candidate.account_uuid()
        {
            return Decision {
                outcome: Outcome::AccountBusy,
                conflicting: Some(Arc::clone(s)),
            };
        }
    }
    if one_at_a_time && let Some(s) = active().next() {
        return Decision {
            outcome: Outcome::AnotherGameRunning,
            conflicting: Some(Arc::clone(s)),
        };
    }
    Decision::admitted()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::test_support::session;

    #[test]
    fn different_packs_on_different_accounts_run_side_by_side() {
        assert!(
            check(
                &session("p2", Some("b")),
                &[session("p1", Some("a"))],
                false
            )
            .ok()
        );
    }

    #[test]
    fn a_pack_can_only_run_once() {
        let running = session("p1", Some("a"));
        let d = check(
            &session("p1", Some("b")),
            std::slice::from_ref(&running),
            false,
        );
        assert_eq!(d.outcome, Outcome::PackAlreadyRunning);
        assert!(Arc::ptr_eq(d.conflicting.as_ref().unwrap(), &running));
    }

    #[test]
    fn an_account_can_only_play_one_game() {
        let running = session("p1", Some("a"));
        let d = check(
            &session("p2", Some("a")),
            std::slice::from_ref(&running),
            false,
        );
        assert_eq!(d.outcome, Outcome::AccountBusy);
        assert!(Arc::ptr_eq(d.conflicting.as_ref().unwrap(), &running));
    }

    #[test]
    fn server_launches_have_no_account_to_collide_on() {
        assert!(check(&session("p2", None), &[session("p1", None)], false).ok());
    }

    #[test]
    fn ended_sessions_do_not_block() {
        let ended = session("p1", Some("a"));
        ended.end_without_game();
        assert!(check(&session("p1", Some("a")), &[ended], true).ok());
    }

    #[test]
    fn one_at_a_time_blocks_any_other_active_game() {
        let d = check(&session("p2", Some("b")), &[session("p1", Some("a"))], true);
        assert_eq!(d.outcome, Outcome::AnotherGameRunning);
    }

    #[test]
    fn the_specific_conflict_is_reported_even_in_one_at_a_time_mode() {
        assert_eq!(
            check(&session("p1", Some("b")), &[session("p1", Some("a"))], true).outcome,
            Outcome::PackAlreadyRunning
        );
    }

    #[test]
    fn the_candidate_does_not_conflict_with_itself() {
        let s = session("p1", Some("a"));
        assert!(check(&s, std::slice::from_ref(&s), true).ok());
    }

    #[test]
    fn refusals_name_a_message() {
        assert_eq!(Outcome::Ok.l10n_key(), None);
        assert!(Outcome::AccountBusy.l10n_key().is_some());
    }
}
