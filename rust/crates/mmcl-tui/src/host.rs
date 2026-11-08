// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! [`TuiHost`]: everything the terminal UI needs from the launcher, and [`EngineHost`], its
//! implementation over [`mmcl_engine::Engine`].
//!
//! Blocking methods (install, uninstall, launch, stop) are called from worker threads, never
//! from the UI thread, and never from inside the engine's runtime. Errors come back as the
//! localized text the UI shows.

use std::sync::Arc;

use mmcl_engine::{
    Engine, EngineError, EngineEvent, LaunchOptions, LogSubscription, OperationProgress, PackId,
    PackSummary, SessionId, SessionInfo, Setting, Settings,
};
use parking_lot::Mutex;
use tokio::sync::broadcast::error::RecvError;

/// What changed behind the UI's back.
#[derive(Clone, Debug)]
pub enum HostEvent {
    /// The installed (or available) pack list changed.
    PacksChanged,
    /// The available-pack fetch finished.
    AvailableReady,
    /// A session started, changed or went away.
    SessionsChanged(Option<Arc<SessionInfo>>),
    /// An operation progressed (a launch reports under its session id).
    Progress(OperationProgress),
    /// A setting changed.
    SettingChanged,
    /// The accounts changed (the status bar shows the default account).
    AccountsChanged,
    /// The launcher is shutting down.
    ShuttingDown,
}

/// Callback the host calls with each [`HostEvent`] (from any thread).
pub type HostEventSink = Arc<dyn Fn(HostEvent) + Send + Sync>;

/// Everything the TUI needs from the rest of the launcher.
pub trait TuiHost: Send + Sync + 'static {
    /// The installed packs, in library order.
    fn installed_packs(&self) -> Vec<PackSummary>;
    /// The installable packs; `None` while the first fetch is in flight.
    fn available_packs(&self) -> Option<Vec<PackSummary>>;
    /// Installs an available pack (blocking).
    fn install_available(&self, pack: &PackSummary) -> Result<(), String>;
    /// Installs a pack from its manifest URL (blocking).
    fn install_by_url(&self, url: &str) -> Result<(), String>;
    /// Uninstalls a pack and removes its files (blocking).
    fn uninstall(&self, pack: &PackId) -> Result<(), String>;
    /// Starts a launch: account resolution, admission, then the prepare pipeline. Returns once
    /// the session exists; the error is the localized reason (the TUI cannot sign in).
    fn launch(&self, pack: &PackId) -> Result<SessionId, String>;
    /// Cancels a preparing launch.
    fn cancel_launch(&self, session: SessionId);
    /// Every session, oldest first.
    fn sessions(&self) -> Vec<SessionInfo>;
    /// One session.
    fn session(&self, id: SessionId) -> Option<SessionInfo> {
        self.sessions().into_iter().find(|s| s.id == id)
    }
    /// A live, lossless log subscription (the last `max_lines` lines first).
    fn subscribe_log(&self, session: SessionId, max_lines: usize) -> Option<LogSubscription>;
    /// Asks a running game to exit (blocking).
    fn stop_game(&self, session: SessionId) -> Result<(), String>;
    /// The default account's name.
    fn signed_in_name(&self) -> Option<String>;
    /// The current settings.
    fn settings(&self) -> Settings;
    /// Writes one setting; the error is the localized validation message.
    fn set(&self, setting: Setting) -> Result<(), String>;
    /// Applies a saved locale override (`""` = the OS language) to the UI now.
    fn apply_locale(&self, tag: &str);
    /// Whether the OS is in dark mode.
    fn os_is_dark(&self) -> bool;
    /// Wall-clock milliseconds.
    fn now_ms(&self) -> i64 {
        chrono::Utc::now().timestamp_millis()
    }
    /// Registers the UI's event callback (called once, before the UI starts).
    fn attach(&self, sink: HostEventSink);
}

/// The localized reason a launch was refused, with the TUI's own wording for account problems.
pub fn launch_error_message(e: &EngineError, account_name: Option<&str>) -> String {
    match e.l10n_key() {
        "engine.error.account.overrideMissing" => {
            mmcl_i18n::t!("tui.launch.overrideAccountMissing").into_owned()
        }
        "engine.error.account.needsSignIn" => {
            mmcl_i18n::tf!("tui.launch.accountNeedsSignIn", account_name.unwrap_or(""))
        }
        "engine.error.account.none" => mmcl_i18n::t!("tui.auth.noAccount").into_owned(),
        _ => e.message(),
    }
}

/// [`TuiHost`] over the engine.
pub struct EngineHost {
    engine: Engine,
    available: Arc<Mutex<Option<Vec<PackSummary>>>>,
    sink: Arc<Mutex<Option<HostEventSink>>>,
}

impl EngineHost {
    /// Wraps `engine` and starts the available-pack fetch.
    pub fn new(engine: Engine) -> Arc<EngineHost> {
        let host = Arc::new(EngineHost {
            engine,
            available: Arc::new(Mutex::new(None)),
            sink: Arc::new(Mutex::new(None)),
        });
        host.start_available_fetch();
        host
    }

    fn emit(sink: &Mutex<Option<HostEventSink>>, e: HostEvent) {
        let s = sink.lock().clone();
        if let Some(s) = s {
            s(e);
        }
    }

    fn start_available_fetch(&self) {
        let engine = self.engine.clone();
        let slot = self.available.clone();
        let sink = self.sink.clone();
        self.engine.handle().spawn(async move {
            let list = engine.available_packs().await;
            *slot.lock() = Some(list);
            EngineHost::emit(&sink, HostEvent::AvailableReady);
        });
    }

    fn account_name_for(&self, pack: &PackId) -> Option<String> {
        let accounts = self.engine.accounts();
        let over = self
            .engine
            .config()
            .account_override_for_pack(pack.as_str());
        match over {
            Some(uuid) => accounts
                .into_iter()
                .find(|a| a.uuid == uuid)
                .map(|a| a.name),
            None => accounts.into_iter().find(|a| a.is_default).map(|a| a.name),
        }
    }
}

impl TuiHost for EngineHost {
    fn installed_packs(&self) -> Vec<PackSummary> {
        self.engine.installed_packs()
    }

    fn available_packs(&self) -> Option<Vec<PackSummary>> {
        self.available.lock().clone()
    }

    fn install_available(&self, pack: &PackSummary) -> Result<(), String> {
        let name = pack.friendly_name.clone();
        let r = self
            .engine
            .block_on(self.engine.install_available_pack(&name));
        r.map(|_| ()).map_err(|e| e.message())
    }

    fn install_by_url(&self, url: &str) -> Result<(), String> {
        self.engine
            .block_on(self.engine.install_pack_by_url(url))
            .map(|_| ())
            .map_err(|e| e.message())
    }

    fn uninstall(&self, pack: &PackId) -> Result<(), String> {
        self.engine
            .block_on(self.engine.uninstall_pack(pack, true))
            .map_err(|e| e.message())
    }

    fn launch(&self, pack: &PackId) -> Result<SessionId, String> {
        self.engine
            .block_on(self.engine.launch(pack, LaunchOptions::default()))
            .map_err(|e| launch_error_message(&e, self.account_name_for(pack).as_deref()))
    }

    fn cancel_launch(&self, session: SessionId) {
        let _ = self.engine.cancel_launch(session);
    }

    fn sessions(&self) -> Vec<SessionInfo> {
        self.engine.sessions()
    }

    fn session(&self, id: SessionId) -> Option<SessionInfo> {
        self.engine.session(id)
    }

    fn subscribe_log(&self, session: SessionId, max_lines: usize) -> Option<LogSubscription> {
        self.engine.subscribe_log(session, max_lines).ok()
    }

    fn stop_game(&self, session: SessionId) -> Result<(), String> {
        self.engine
            .block_on(self.engine.stop_game(session, false))
            .map_err(|e| e.message())
    }

    fn signed_in_name(&self) -> Option<String> {
        self.engine
            .default_account()
            .map(|a| a.name)
            .filter(|n| !n.trim().is_empty())
    }

    fn settings(&self) -> Settings {
        self.engine.settings()
    }

    fn set(&self, setting: Setting) -> Result<(), String> {
        self.engine
            .set(setting)
            .map(|_| ())
            .map_err(|e| e.message())
    }

    fn apply_locale(&self, tag: &str) {
        mmcl_i18n::set_locale(mmcl_i18n::locale::resolve(tag));
    }

    fn os_is_dark(&self) -> bool {
        mmcl_platform::theme::is_os_dark()
    }

    fn attach(&self, sink: HostEventSink) {
        *self.sink.lock() = Some(sink);
        let mut rx = self.engine.subscribe();
        let slot = self.sink.clone();
        let available = self.available.clone();
        let engine = self.engine.clone();
        let spawned = std::thread::Builder::new()
            .name("tui-events".into())
            .spawn(move || {
                loop {
                    let ev = match rx.blocking_recv() {
                        Ok(ev) => ev,
                        Err(RecvError::Lagged(_)) => {
                            // Re-read everything the UI shows.
                            EngineHost::emit(&slot, HostEvent::PacksChanged);
                            EngineHost::emit(&slot, HostEvent::SessionsChanged(None));
                            continue;
                        }
                        Err(RecvError::Closed) => break,
                    };
                    let out = match ev {
                        EngineEvent::PacksChanged | EngineEvent::PackChanged(_) => {
                            // Keep the available list fresh once it was loaded.
                            let mut a = available.lock();
                            if a.is_some() {
                                let fresh = engine.available_packs_if_ready();
                                if !fresh.is_empty() {
                                    *a = Some(fresh);
                                }
                            }
                            Some(HostEvent::PacksChanged)
                        }
                        EngineEvent::SessionStarted(s) | EngineEvent::SessionChanged(s) => {
                            Some(HostEvent::SessionsChanged(Some(s)))
                        }
                        EngineEvent::SessionRemoved(_) => Some(HostEvent::SessionsChanged(None)),
                        EngineEvent::Progress(p) => Some(HostEvent::Progress(p)),
                        EngineEvent::SettingChanged(_) => Some(HostEvent::SettingChanged),
                        EngineEvent::AccountsChanged => Some(HostEvent::AccountsChanged),
                        EngineEvent::ShuttingDown => {
                            EngineHost::emit(&slot, HostEvent::ShuttingDown);
                            break;
                        }
                        _ => None,
                    };
                    if let Some(e) = out {
                        EngineHost::emit(&slot, e);
                    }
                }
            });
        if let Err(e) = spawned {
            tracing::warn!("{}", mmcl_i18n::tf!("log.tui.eventsFailed", e.to_string()));
        }
    }
}
