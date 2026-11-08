// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Desktop integration driven by the engine's events: what Java spread over
//! `NotificationManager`, `JumpListManager.refresh`, `LauncherCore` (taskbar launch progress,
//! the "Ready to play" toast) and `SystemMenuBarManager.attachTo`.
//!
//! - **Toasts:** every [`EngineEvent::Notification`] becomes an OS notification through
//!   [`mmcl_ui_kit::notify`], whose backend this module points at `mmcl_platform::notify`
//!   (Windows tray balloons, which need no Start-menu AUMID shortcut; Linux tray + D-Bus),
//!   falling back to the kit's own backend.
//! - **Taskbar / dock progress** for each preparing launch (the rolled-up step fraction), ended
//!   when the launch is ready, fails or goes away. Several launches combine.
//! - **"Ready to play"** also for a short launch while the launcher is not focused (the engine
//!   only toasts launches that took longer than ten seconds).
//! - **Recent packs:** the menu bar, the macOS dock menu, and the Windows jump list / Linux
//!   `.desktop` actions, at start-up and after each launch.
//!
//! It is opt-in ([`crate::install_desktop_integration`]): an app that wires the same pieces
//! itself must not install it too, or every toast shows twice. The live "Resizable windows"
//! setting is not part of it; [`crate::init`] always applies that.

use std::collections::{HashMap, HashSet};
use std::sync::Arc;
use std::time::{Duration, Instant};

use gpui::{App, Global, Window};
use mmcl_engine::{EngineEvent, SessionId, SessionInfo, SessionPhase, StepStateView};
use mmcl_i18n::{t, tf};
use mmcl_platform::progress::{self, LaunchId};
use mmcl_ui_kit::notify::{self, Kind, NotificationBackend, SystemBackend};

use super::{controller, menu_bar};
use crate::services::{self, UiEvent};

/// The engine toasts "Ready to play" itself after this long (`LauncherCore`'s 10 s).
pub const ENGINE_READY_TOAST_AFTER: Duration = Duration::from_secs(10);
/// The front end toasts a launch that was ready sooner than this while the launcher is not
/// focused. Kept below [`ENGINE_READY_TOAST_AFTER`] so the two never both fire: the engine's
/// clock starts before the session event reaches this thread.
pub const UI_READY_TOAST_BEFORE: Duration = Duration::from_millis(9_000);

/// The notification backend the launcher uses: `mmcl_platform::notify` first, then the kit's
/// default (`notify-rust`, `notify-send`).
#[derive(Debug, Default)]
pub struct PlatformToasts;

fn platform_kind(kind: Kind) -> mmcl_platform::notify::Kind {
    match kind {
        Kind::Info => mmcl_platform::notify::Kind::Info,
        Kind::Success => mmcl_platform::notify::Kind::Success,
        Kind::Warning => mmcl_platform::notify::Kind::Warn,
        Kind::Error => mmcl_platform::notify::Kind::Error,
    }
}

impl NotificationBackend for PlatformToasts {
    fn show(&self, kind: Kind, title: &str, body: &str) -> Result<(), String> {
        match mmcl_platform::notify::deliver(platform_kind(kind), title, body) {
            Ok(()) => Ok(()),
            Err(platform) => SystemBackend
                .show(kind, title, body)
                .map_err(|fallback| format!("{platform}; {fallback}")),
        }
    }

    fn shutdown(&self) {
        mmcl_platform::notify::shutdown();
    }
}

/// The toast kind of an engine notification.
pub fn toast_kind(kind: mmcl_engine::NotificationKind) -> Kind {
    match kind {
        mmcl_engine::NotificationKind::Info => Kind::Info,
        mmcl_engine::NotificationKind::Success => Kind::Success,
        mmcl_engine::NotificationKind::Warning => Kind::Warning,
        mmcl_engine::NotificationKind::Error => Kind::Error,
    }
}

/// Shows an engine notification as a toast.
pub fn show_notification(n: &mmcl_engine::Notification) {
    match toast_kind(n.kind) {
        Kind::Info => notify::info(&n.title, &n.body),
        Kind::Success => notify::success(&n.title, &n.body),
        Kind::Warning => notify::warn(&n.title, &n.body),
        Kind::Error => notify::error(&n.title, &n.body),
    }
}

/// The taskbar owner id of a game session (kept apart from the progress screens' ids).
pub fn launch_id(session: SessionId) -> LaunchId {
    LaunchId((1 << 62) | session.0)
}

/// Whether every step reached an end state, and whether any failed.
pub fn steps_finished(info: &SessionInfo) -> (bool, bool) {
    let terminal = !info.steps.is_empty()
        && info.steps.iter().all(|s| {
            matches!(
                s.state,
                StepStateView::Done | StepStateView::Failed | StepStateView::Skipped
            )
        });
    let failed = info.steps.iter().any(|s| s.state == StepStateView::Failed);
    (terminal, failed)
}

/// The rolled-up launch fraction: finished steps count 1, a running step its progress.
pub fn rollup(info: &SessionInfo) -> f64 {
    if info.steps.is_empty() {
        return 0.0;
    }
    let sum: f64 = info
        .steps
        .iter()
        .map(|s| match s.state {
            StepStateView::Done | StepStateView::Failed | StepStateView::Skipped => 1.0,
            StepStateView::Running => s.progress.clamp(0.0, 1.0),
            StepStateView::Pending => 0.0,
        })
        .sum();
    sum / info.steps.len() as f64
}

/// What the taskbar should do for a session snapshot.
#[derive(Debug, Clone, Copy, PartialEq)]
pub enum TaskbarStep {
    /// Show this fraction.
    Show(f64),
    /// The launch is ready, failed or ended: drop it from the bar.
    End,
}

/// The taskbar rule (`setLaunchProgress` while preparing, `endLaunchProgress` when ready or
/// failed).
pub fn taskbar_step(info: &SessionInfo) -> TaskbarStep {
    let (terminal, _) = steps_finished(info);
    if info.phase == SessionPhase::Preparing && !terminal {
        TaskbarStep::Show(rollup(info))
    } else {
        TaskbarStep::End
    }
}

/// Whether the front end shows "Ready to play" (`elapsedMs > 10_000 || !isLauncherFocused()`,
/// minus the long launches the engine already toasts).
pub fn ready_toast_due(ready: bool, failed: bool, elapsed: Duration, focused: bool) -> bool {
    ready && !failed && !focused && elapsed < UI_READY_TOAST_BEFORE
}

/// The "Ready to play" body for a pack name.
pub fn ready_body(pack_name: &str) -> String {
    if pack_name.trim().is_empty() {
        t!("notification.launch.ready.body").into_owned()
    } else {
        tf!("notification.launch.ready.bodyNamed", pack_name)
    }
}

/// Per-session bookkeeping.
#[derive(Default)]
struct Launches {
    seen: HashMap<SessionId, Instant>,
    ended: HashSet<SessionId>,
    toasted: HashSet<SessionId>,
    recents_refreshed: HashSet<SessionId>,
}

#[derive(Default)]
struct Desktop {
    launches: Launches,
    subscribed: bool,
}

impl Global for Desktop {}

/// Installs the notification backend (once per process).
pub fn install_notifications() {
    notify::set_backend(Arc::new(PlatformToasts));
}

/// Whether [`install`] ran.
pub fn is_installed(cx: &App) -> bool {
    cx.try_global::<Desktop>().is_some_and(|d| d.subscribed)
}

/// Turns the integration on (once per app; see [`crate::install_desktop_integration`]): the
/// notification backend, the engine-event subscription and the menu re-sync on every screen
/// swap (Java's `attachTo`). Without an engine only the backend and the menu hook install.
pub fn install(cx: &mut App) {
    if is_installed(cx) {
        return;
    }
    cx.default_global::<Desktop>().subscribed = true;
    install_notifications();
    super::hooks::on_screen_shown(cx, |_, _, cx| menu_bar::refresh(cx));
    if let Some(events) = services::events(cx) {
        cx.subscribe(&events, |_, event: &UiEvent, cx| on_event(event, cx))
            .detach();
    }
    if controller::is_started(cx) {
        mmcl_platform::recents::refresh();
    }
    menu_bar::refresh(cx);
}

/// The main window is up: bind the taskbar to it and, with the integration on, publish the
/// recent packs and rebuild the menus.
pub fn on_main_window_opened(window: &mut Window, cx: &mut App) {
    progress::attach(&*window);
    menu_bar::invalidate(cx);
    if is_installed(cx) {
        mmcl_platform::recents::refresh();
        menu_bar::refresh(cx);
    }
}

fn on_event(event: &UiEvent, cx: &mut App) {
    match event {
        UiEvent::Engine(EngineEvent::Notification(n)) => show_notification(n),
        UiEvent::Engine(EngineEvent::SessionStarted(info))
        | UiEvent::Engine(EngineEvent::SessionChanged(info)) => on_session(info, cx),
        UiEvent::Engine(EngineEvent::SessionRemoved(id)) => {
            let d = cx.default_global::<Desktop>();
            d.launches.seen.remove(id);
            d.launches.toasted.remove(id);
            d.launches.recents_refreshed.remove(id);
            if d.launches.ended.insert(*id) {
                progress::end_launch_progress(launch_id(*id));
            }
        }
        UiEvent::Engine(EngineEvent::PacksChanged)
        | UiEvent::Engine(EngineEvent::PackChanged(_))
        | UiEvent::Engine(EngineEvent::SettingChanged(_))
        | UiEvent::Lagged => menu_bar::refresh(cx),
        _ => {}
    }
}

fn on_session(info: &Arc<SessionInfo>, cx: &mut App) {
    let id = info.id;
    let now = Instant::now();
    let focused = controller::handle(cx).is_some_and(|h| h.is_launcher_focused());
    let d = cx.default_global::<Desktop>();
    let started = *d.launches.seen.entry(id).or_insert(now);
    // The taskbar.
    match taskbar_step(info) {
        TaskbarStep::Show(f) if !d.launches.ended.contains(&id) => {
            progress::set_launch_progress(launch_id(id), progress::Progress::Fraction(f));
        }
        TaskbarStep::Show(_) => {}
        TaskbarStep::End => {
            if d.launches.ended.insert(id) {
                progress::end_launch_progress(launch_id(id));
            }
        }
    }
    // "Ready to play" for a short launch the user tabbed away from.
    let (ready, failed) = steps_finished(info);
    if ready
        && d.launches.toasted.insert(id)
        && ready_toast_due(ready, failed, now - started, focused)
    {
        notify::success(
            &t!("notification.launch.ready.title"),
            &ready_body(&info.pack_name),
        );
    }
    // A launch that reached the game updates every recent-packs surface.
    let launched = info.phase == SessionPhase::Running || info.started_ms > 0;
    let refresh_recents = launched && d.launches.recents_refreshed.insert(id);
    if refresh_recents {
        mmcl_platform::recents::refresh();
        menu_bar::refresh(cx);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use mmcl_engine::{LaunchStepView, PackId};

    fn step(state: StepStateView, progress: f64) -> LaunchStepView {
        LaunchStepView {
            id: "s".into(),
            label: "Step".into(),
            state,
            progress,
            sub_text: String::new(),
            error: None,
        }
    }

    fn session(phase: SessionPhase, steps: Vec<LaunchStepView>) -> SessionInfo {
        SessionInfo {
            id: SessionId(3),
            pack: PackId("p".into()),
            pack_name: "Pack".into(),
            account_uuid: None,
            account_name: None,
            phase,
            cancelled: false,
            started_ms: 0,
            ended_ms: 0,
            exit_code: None,
            steps,
            has_crash_report: false,
            log_file: None,
            log_truncated: false,
        }
    }

    #[test]
    fn the_taskbar_follows_the_preparing_steps_and_ends_when_ready() {
        let s = session(
            SessionPhase::Preparing,
            vec![
                step(StepStateView::Done, 1.0),
                step(StepStateView::Running, 0.5),
                step(StepStateView::Pending, 0.0),
                step(StepStateView::Skipped, 0.0),
            ],
        );
        assert_eq!(taskbar_step(&s), TaskbarStep::Show(0.625));
        let ready = session(
            SessionPhase::Preparing,
            vec![
                step(StepStateView::Done, 1.0),
                step(StepStateView::Skipped, 0.0),
            ],
        );
        assert_eq!(taskbar_step(&ready), TaskbarStep::End);
        assert_eq!(steps_finished(&ready), (true, false));
        let failed = session(SessionPhase::Failed, vec![step(StepStateView::Failed, 0.2)]);
        assert_eq!(taskbar_step(&failed), TaskbarStep::End);
        assert_eq!(steps_finished(&failed), (true, true));
        let empty = session(SessionPhase::Preparing, vec![]);
        assert_eq!(taskbar_step(&empty), TaskbarStep::Show(0.0));
        assert_eq!(steps_finished(&empty), (false, false));
    }

    #[test]
    fn ready_toast_only_for_short_unfocused_successful_launches() {
        let short = Duration::from_secs(3);
        assert!(ready_toast_due(true, false, short, false));
        assert!(!ready_toast_due(true, false, short, true), "focused");
        assert!(!ready_toast_due(true, true, short, false), "failed");
        assert!(!ready_toast_due(false, false, short, false), "not ready");
        assert!(
            !ready_toast_due(true, false, ENGINE_READY_TOAST_AFTER, false),
            "the engine toasts long launches itself"
        );
        assert!(UI_READY_TOAST_BEFORE < ENGINE_READY_TOAST_AFTER);
    }

    #[test]
    fn ready_body_names_the_pack() {
        assert!(ready_body("Alto").contains("Alto"));
        assert_eq!(
            ready_body(" "),
            mmcl_i18n::english("notification.launch.ready.body").unwrap()
        );
    }

    #[test]
    fn engine_notifications_keep_their_kind() {
        use mmcl_engine::NotificationKind as N;
        assert_eq!(toast_kind(N::Info), Kind::Info);
        assert_eq!(toast_kind(N::Success), Kind::Success);
        assert_eq!(toast_kind(N::Warning), Kind::Warning);
        assert_eq!(toast_kind(N::Error), Kind::Error);
        assert_eq!(
            platform_kind(Kind::Warning),
            mmcl_platform::notify::Kind::Warn
        );
    }

    #[test]
    fn session_ids_never_collide_with_screen_ids() {
        assert_ne!(launch_id(SessionId(1)), LaunchId(1));
        assert_ne!(launch_id(SessionId(1)), launch_id(SessionId(2)));
    }
}
