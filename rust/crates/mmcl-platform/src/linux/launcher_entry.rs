// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Taskbar progress on Linux: the `com.canonical.Unity.LauncherEntry.Update` signal, honoured
//! by KDE Plasma's task manager and Ubuntu's Dash-to-Dock (an improvement over AWT, which only
//! drew progress on Unity and KDE).

use std::collections::HashMap;

use zbus::zvariant::Value;

use super::{block_on, desktop_stem, reset_session, session};
use crate::progress::{Command, ProgressBackend};
use mmcl_util::Progress;

const PATH: &str = "/com/micatechnologies/launcher/LauncherEntry";
const INTERFACE: &str = "com.canonical.Unity.LauncherEntry";

/// What the dock entry shows.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct EntryState {
    pub progress: f64,
    pub progress_visible: bool,
    pub urgent: bool,
    pub count: i64,
    pub count_visible: bool,
}

impl EntryState {
    /// Nothing shown.
    pub const CLEAR: EntryState = EntryState {
        progress: 0.0,
        progress_visible: false,
        urgent: false,
        count: 0,
        count_visible: false,
    };

    /// The state after `cmd`, from `prev`; `None` for commands that change nothing.
    pub fn after(prev: EntryState, cmd: Command) -> Option<EntryState> {
        Some(match cmd {
            Command::Attach(_) => return None,
            // The entry has no indeterminate mode; a visible empty bar says "busy".
            Command::Set(Progress::Indeterminate) => EntryState {
                progress: 0.0,
                progress_visible: true,
                ..prev
            },
            Command::Set(Progress::Fraction(f)) => EntryState {
                progress: if f.is_nan() { 0.0 } else { f.clamp(0.0, 1.0) },
                progress_visible: true,
                ..prev
            },
            Command::Stop => EntryState {
                progress: 0.0,
                progress_visible: false,
                ..prev
            },
            Command::FullError => EntryState {
                progress: 1.0,
                progress_visible: true,
                urgent: true,
                count: 1,
                count_visible: true,
            },
            Command::Shutdown => EntryState::CLEAR,
        })
    }
}

/// `application://<desktop id>`.
pub fn app_uri() -> String {
    format!("application://{}.desktop", desktop_stem())
}

fn emit(state: EntryState) -> Result<(), String> {
    block_on(async move {
        let conn = session().await?;
        let mut props: HashMap<&str, Value<'_>> = HashMap::new();
        props.insert("progress", Value::F64(state.progress));
        props.insert("progress-visible", Value::Bool(state.progress_visible));
        props.insert("urgent", Value::Bool(state.urgent));
        props.insert("count", Value::I64(state.count));
        props.insert("count-visible", Value::Bool(state.count_visible));
        conn.emit_signal(
            None::<zbus::names::BusName<'_>>,
            PATH,
            INTERFACE,
            "Update",
            &(app_uri(), props),
        )
        .await
        .map_err(|e| e.to_string())
    })
    .unwrap_or_else(|| Err("no D-Bus runtime".into()))
}

/// The Linux progress backend for [`crate::progress`].
pub struct LauncherEntryBackend {
    state: EntryState,
}

impl Default for LauncherEntryBackend {
    fn default() -> Self {
        LauncherEntryBackend {
            state: EntryState::CLEAR,
        }
    }
}

impl ProgressBackend for LauncherEntryBackend {
    fn apply(&mut self, cmd: Command) {
        let Some(next) = EntryState::after(self.state, cmd) else {
            return;
        };
        self.state = next;
        if let Err(e) = emit(next) {
            reset_session();
            tracing::debug!("{}", mmcl_i18n::tf!("log.taskbar.updateFailed", e));
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn states_follow_commands() {
        let s =
            EntryState::after(EntryState::CLEAR, Command::Set(Progress::Fraction(0.4))).unwrap();
        assert_eq!((s.progress, s.progress_visible), (0.4, true));
        let s = EntryState::after(s, Command::Stop).unwrap();
        assert!(!s.progress_visible);
        let e = EntryState::after(s, Command::FullError).unwrap();
        assert!(e.urgent && e.count_visible && e.count == 1);
        assert_eq!(
            EntryState::after(e, Command::Shutdown),
            Some(EntryState::CLEAR)
        );
        assert_eq!(
            EntryState::after(e, Command::Attach(crate::NativeHandle::Unsupported)),
            None
        );
        assert!(app_uri().starts_with("application://") && app_uri().ends_with(".desktop"));
    }
}
