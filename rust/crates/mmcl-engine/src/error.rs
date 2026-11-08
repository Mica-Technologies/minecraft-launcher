// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! [`EngineError`]: every failure an engine operation reports. Each variant maps to a
//! localization key ([`EngineError::l10n_key`]) and its MessageFormat arguments
//! ([`EngineError::l10n_args`]); [`EngineError::message`] renders it in the active locale, which
//! is what a front end shows.

use mmcl_auth::{AuthOutcome, Problem, SignInError};
use mmcl_config::ConfigError;
use mmcl_modpack::ModpackError;
use mmcl_runtime::RuntimeError;

use crate::model::PackId;
use crate::session::SessionId;

/// The result type of engine operations.
pub type EngineResult<T> = Result<T, EngineError>;

/// Why a launch was refused before anything was prepared.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum LaunchRefusal {
    /// The pack already has a preparing or running game (`{0}` = pack name).
    PackAlreadyRunning { pack_name: String },
    /// The account is already playing another pack.
    AccountBusy {
        account_name: String,
        pack_name: String,
    },
    /// One-at-a-time mode and another game is active.
    AnotherGameRunning { pack_name: String },
    /// The announcement feed disabled gameplay.
    GameplayDisabled,
}

/// Every failure an engine operation reports.
#[derive(Debug, thiserror::Error)]
pub enum EngineError {
    /// The operation exists in the API but its body is not written yet.
    #[error("not yet implemented: {0}")]
    NotImplemented(&'static str),
    /// No pack with this id is known (installed or available).
    #[error("unknown pack {0}")]
    PackNotFound(PackId),
    /// The pack is known but not installed.
    #[error("pack {0} is not installed")]
    PackNotInstalled(PackId),
    /// The pack is launching or running, so its files may not change.
    #[error("pack {0} is in use by a game")]
    PackInUse(String),
    /// No session with this id.
    #[error("unknown session {0}")]
    SessionNotFound(SessionId),
    /// No signed-in account with this UUID.
    #[error("unknown account {0}")]
    AccountNotFound(String),
    /// The launch account could not be resolved.
    #[error("no usable account: {0:?}")]
    Account(Problem),
    /// Admission refused the launch.
    #[error("launch refused: {0:?}")]
    LaunchRefused(LaunchRefusal),
    /// A sign-in flow failed before Microsoft returned a token.
    #[error(transparent)]
    SignIn(SignInError),
    /// The sign-in chain (Xbox, XSTS, Minecraft) refused the account.
    #[error("sign-in failed: {0:?}")]
    Auth(Box<AuthOutcome>),
    /// Another sign-in is already running.
    #[error("a sign-in is already running")]
    SignInBusy,
    /// A pack operation failed.
    #[error(transparent)]
    Modpack(#[from] ModpackError),
    /// A Java runtime operation failed.
    #[error(transparent)]
    Runtime(#[from] RuntimeError),
    /// A settings file operation failed.
    #[error(transparent)]
    Config(#[from] ConfigError),
    /// A setting value was rejected (`key` = the config key).
    #[error("invalid value for setting {key}: {reason}")]
    InvalidSetting { key: &'static str, reason: String },
    /// A local file operation failed.
    #[error(transparent)]
    Io(#[from] std::io::Error),
    /// The launcher is offline and the operation needs the network.
    #[error("the launcher is offline")]
    Offline,
    /// The operation was cancelled.
    #[error("cancelled")]
    Cancelled,
    /// The engine was shut down.
    #[error("the engine has shut down")]
    ShutDown,
    /// Anything else (already localized text).
    #[error("{0}")]
    Other(String),
}

impl EngineError {
    /// The localization key of the user-facing message.
    pub fn l10n_key(&self) -> &'static str {
        match self {
            EngineError::NotImplemented(_) => "engine.error.notImplemented",
            EngineError::PackNotFound(_) => "engine.error.packNotFound",
            EngineError::PackNotInstalled(_) => "engine.error.packNotInstalled",
            // Neutral wording: verify, update, restore, uninstall and launch all refuse with it.
            EngineError::PackInUse(_) => "engine.error.packInUse",
            EngineError::SessionNotFound(_) => "engine.error.sessionNotFound",
            EngineError::AccountNotFound(_) => "engine.error.accountNotFound",
            EngineError::Account(Problem::NoAccount) => "engine.error.account.none",
            EngineError::Account(Problem::OverrideMissing) => {
                "engine.error.account.overrideMissing"
            }
            EngineError::Account(Problem::NeedsSignIn) => "engine.error.account.needsSignIn",
            EngineError::LaunchRefused(r) => match r {
                LaunchRefusal::PackAlreadyRunning { .. } => "launch.refused.packRunning",
                LaunchRefusal::AccountBusy { .. } => "launch.refused.accountBusy",
                LaunchRefusal::AnotherGameRunning { .. } => "launch.refused.anotherGame",
                LaunchRefusal::GameplayDisabled => "engine.error.gameplayDisabled",
            },
            EngineError::SignIn(e) => e.l10n_key(),
            EngineError::Auth(o) => o.l10n_key().unwrap_or("signIn.failed"),
            EngineError::SignInBusy => "engine.error.signInBusy",
            EngineError::Modpack(e) => e.key(),
            EngineError::Runtime(e) => e.l10n_key(),
            EngineError::Config(_) => "engine.error.generic",
            EngineError::InvalidSetting { .. } => "engine.error.invalidSetting",
            EngineError::Io(_) => "engine.error.generic",
            EngineError::Offline => "engine.error.offline",
            EngineError::Cancelled => "engine.error.cancelled",
            EngineError::ShutDown => "engine.error.shutDown",
            EngineError::Other(_) => "engine.error.generic",
        }
    }

    /// The MessageFormat arguments for [`Self::l10n_key`].
    pub fn l10n_args(&self) -> Vec<String> {
        match self {
            EngineError::NotImplemented(op) => vec![(*op).to_string()],
            EngineError::PackNotFound(id) | EngineError::PackNotInstalled(id) => {
                vec![id.as_str().to_string()]
            }
            EngineError::PackInUse(name) => vec![name.clone()],
            EngineError::SessionNotFound(id) => vec![id.0.to_string()],
            EngineError::AccountNotFound(uuid) => vec![uuid.clone()],
            EngineError::LaunchRefused(r) => match r {
                LaunchRefusal::PackAlreadyRunning { pack_name }
                | LaunchRefusal::AnotherGameRunning { pack_name } => vec![pack_name.clone()],
                LaunchRefusal::AccountBusy {
                    account_name,
                    pack_name,
                } => vec![account_name.clone(), pack_name.clone()],
                LaunchRefusal::GameplayDisabled => Vec::new(),
            },
            EngineError::Auth(o) if matches!(**o, AuthOutcome::XboxRestricted(_)) => match **o {
                AuthOutcome::XboxRestricted(x) => vec![x.to_string()],
                _ => Vec::new(),
            },
            EngineError::SignIn(SignInError::Denied(d) | SignInError::WamFailed(d)) => {
                vec![d.clone()]
            }
            EngineError::Modpack(e) => e.args(),
            EngineError::Runtime(e) => e.l10n_args(),
            EngineError::Config(e) => vec![e.to_string()],
            EngineError::InvalidSetting { key, reason } => vec![(*key).to_string(), reason.clone()],
            EngineError::Io(e) => vec![e.to_string()],
            EngineError::Other(s) => vec![s.clone()],
            _ => Vec::new(),
        }
    }

    /// The localized message.
    pub fn message(&self) -> String {
        match self {
            EngineError::SignIn(e) => e.message(),
            EngineError::Auth(o) => o
                .message()
                .unwrap_or_else(|| mmcl_i18n::t!("signIn.failed").into_owned()),
            _ => {
                let args = self.l10n_args();
                let fmt: Vec<mmcl_i18n::FmtArg> = args
                    .iter()
                    .map(|a| mmcl_i18n::FmtArg::from(a.as_str()))
                    .collect();
                mmcl_i18n::format(self.l10n_key(), &fmt)
            }
        }
    }

    /// `true` for [`EngineError::NotImplemented`].
    pub fn is_not_implemented(&self) -> bool {
        matches!(self, EngineError::NotImplemented(_))
    }

    /// `true` when the user cancelled (front ends show nothing).
    pub fn is_cancelled(&self) -> bool {
        match self {
            EngineError::Cancelled => true,
            EngineError::SignIn(e) => matches!(e, SignInError::Cancelled),
            EngineError::Auth(o) => o.is_cancelled(),
            EngineError::Runtime(e) => e.is_cancelled(),
            _ => false,
        }
    }
}

impl From<SignInError> for EngineError {
    fn from(e: SignInError) -> Self {
        EngineError::SignIn(e)
    }
}

impl From<AuthOutcome> for EngineError {
    /// A non-`Ok` outcome. A flow error becomes [`EngineError::SignIn`].
    fn from(o: AuthOutcome) -> Self {
        match o {
            AuthOutcome::Flow(e) => EngineError::SignIn(e),
            other => EngineError::Auth(Box::new(other)),
        }
    }
}

impl From<anyhow::Error> for EngineError {
    fn from(e: anyhow::Error) -> Self {
        EngineError::Other(e.to_string())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn every_engine_key_exists_in_the_bundle() {
        let samples = [
            EngineError::NotImplemented("launch"),
            EngineError::PackNotFound(PackId::new("x")),
            EngineError::PackNotInstalled(PackId::new("x")),
            EngineError::PackInUse("x".into()),
            EngineError::SessionNotFound(SessionId(1)),
            EngineError::AccountNotFound("u".into()),
            EngineError::Account(Problem::NoAccount),
            EngineError::Account(Problem::OverrideMissing),
            EngineError::Account(Problem::NeedsSignIn),
            EngineError::LaunchRefused(LaunchRefusal::GameplayDisabled),
            EngineError::LaunchRefused(LaunchRefusal::PackAlreadyRunning {
                pack_name: "p".into(),
            }),
            EngineError::SignInBusy,
            EngineError::InvalidSetting {
                key: "maxRAM",
                reason: "r".into(),
            },
            EngineError::Offline,
            EngineError::Cancelled,
            EngineError::ShutDown,
            EngineError::Other("o".into()),
        ];
        for e in samples {
            assert!(
                mmcl_i18n::contains(e.l10n_key()),
                "missing key {}",
                e.l10n_key()
            );
            assert!(!e.message().is_empty());
        }
    }

    #[test]
    fn engine_messages_render_single_apostrophes() {
        // Apostrophes are always literal in this launcher's MessageFormat, so a doubled `''`
        // in the bundle would show up doubled on screen.
        for e in [
            EngineError::NotImplemented("x"),
            EngineError::PackNotInstalled(PackId::new("x")),
            EngineError::Offline,
            EngineError::InvalidSetting {
                key: "maxRAM",
                reason: "r".into(),
            },
        ] {
            assert!(!e.message().contains("''"), "{}", e.message());
        }
        assert!(
            EngineError::PackInUse("Pack".into())
                .message()
                .contains("Pack")
        );
    }

    #[test]
    fn not_implemented_names_the_operation() {
        let e = EngineError::NotImplemented("launch_pack");
        assert!(e.is_not_implemented());
        assert!(e.message().contains("launch_pack"));
    }

    #[test]
    fn a_cancelled_flow_is_cancelled() {
        let e: EngineError = AuthOutcome::Flow(SignInError::Cancelled).into();
        assert!(e.is_cancelled());
        assert!(!EngineError::Offline.is_cancelled());
    }
}
