// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The presence state machine (`DiscordRpcUtility`).
//!
//! The public methods are synchronous and cheap: they decide what to show on the caller's
//! thread and hand the activity to a worker thread (`mmcl-discord-ipc`) that owns the
//! connection on a small tokio runtime of its own. The worker connects lazily, at most once
//! per [`PresenceOptions::reconnect_interval`] (Java tried once and then failed on every
//! update), stays silent while Discord is not running, re-sends the current activity after a
//! reconnect, answers pings and dispatches `ACTIVITY_JOIN` events.

use std::panic::{AssertUnwindSafe, catch_unwind};
use std::sync::{Arc, OnceLock, mpsc as std_mpsc};
use std::thread;
use std::time::{Duration, Instant};

use mmcl_i18n::{t, tf};
use parking_lot::{Mutex, RwLock};
use tokio::sync::mpsc;

use crate::activity::{Activity, JoinParty, build_join_secret, sanitize_presence_field};
use crate::client::{ConnEvent, Connection, SetActivity};
use crate::transport::{Connector, SystemConnector};
use crate::{CLIENT_ID, DISCORD_SECRET_MAX_BYTES, IMAGE_GAME, IMAGE_LAUNCHER, IMAGE_MENU};

/// The party size staged with a join secret.
pub const PARTY_SIZE: u32 = 1;

/// The party cap staged with a join secret (Discord needs `max > size` to show Join).
pub const PARTY_MAX: u32 = 4;

/// What presence needs from the rest of the launcher.
pub trait PresenceHost: Send + Sync {
    /// The `discordRpcEnable` setting.
    fn rpc_enabled(&self) -> bool;
    /// The `discordInvitesEnable` setting.
    fn invites_enabled(&self) -> bool;
    /// Whether any game session is RUNNING (menu presence then stays on the game).
    fn any_game_running(&self) -> bool;
    /// Handles an `mmcl://` join secret (the deep-link dispatcher, which still confirms).
    /// Called on a background pool thread; it may block.
    fn handle_join(&self, uri: &str);
}

/// Timings, adjustable for tests.
#[derive(Debug, Clone)]
pub struct PresenceOptions {
    /// The Discord application ID.
    pub client_id: String,
    /// The shortest gap between two connection attempts.
    pub reconnect_interval: Duration,
    /// The wait between clearing the activity and closing the pipe in [`Presence::exit`].
    pub exit_grace: Duration,
    /// How long [`Presence::exit`] waits for the worker to finish.
    pub exit_timeout: Duration,
}

impl Default for PresenceOptions {
    fn default() -> Self {
        PresenceOptions {
            client_id: CLIENT_ID.to_string(),
            reconnect_interval: Duration::from_secs(30),
            exit_grace: Duration::from_millis(500),
            exit_timeout: Duration::from_secs(4),
        }
    }
}

enum Command {
    Set(Activity),
    Exit(std_mpsc::Sender<()>),
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct StagedJoin {
    secret: String,
    size: u32,
    max: u32,
}

struct Shared {
    host: RwLock<Option<Arc<dyn PresenceHost>>>,
    connector: Arc<dyn Connector>,
    options: PresenceOptions,
}

impl Shared {
    fn host(&self) -> Option<Arc<dyn PresenceHost>> {
        self.host.read().clone()
    }

    fn rpc_enabled(&self) -> bool {
        self.host().is_some_and(|h| h.rpc_enabled())
    }
}

/// Discord Rich Presence for the launcher.
pub struct Presence {
    shared: Arc<Shared>,
    party_id: String,
    join: Mutex<Option<StagedJoin>>,
    worker: Mutex<Option<mpsc::UnboundedSender<Command>>>,
}

static GLOBAL: OnceLock<Presence> = OnceLock::new();

fn now_epoch_secs() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0)
}

impl Presence {
    /// The process-wide presence, talking to the real Discord client. Off until
    /// [`Presence::set_host`] is called.
    pub fn global() -> &'static Presence {
        GLOBAL.get_or_init(|| Presence::new(Arc::new(SystemConnector), PresenceOptions::default()))
    }

    /// A presence over `connector`. Most code uses [`Presence::global`].
    pub fn new(connector: Arc<dyn Connector>, options: PresenceOptions) -> Presence {
        Presence {
            shared: Arc::new(Shared {
                host: RwLock::new(None),
                connector,
                options,
            }),
            party_id: uuid::Uuid::new_v4().to_string(),
            join: Mutex::new(None),
            worker: Mutex::new(None),
        }
    }

    /// Wires in settings, the running-game check and join handling.
    pub fn set_host(&self, host: Arc<dyn PresenceHost>) {
        *self.shared.host.write() = Some(host);
    }

    /// The party id: one random UUID per process, so the party survives screen changes.
    pub fn party_id(&self) -> &str {
        &self.party_id
    }

    /// The join secret currently staged, if any.
    pub fn staged_join_secret(&self) -> Option<String> {
        self.join.lock().as_ref().map(|j| j.secret.clone())
    }

    fn clear_join(&self) {
        *self.join.lock() = None;
    }

    /// The activity for these fields, with the staged join party (re-sent on every update so
    /// the Join button survives screen changes).
    #[allow(clippy::too_many_arguments)]
    pub fn build_activity(
        &self,
        state: &str,
        details: &str,
        start: i64,
        large_key: &str,
        large_text: &str,
        small_key: &str,
        small_text: &str,
    ) -> Activity {
        let join = self.join.lock().as_ref().map(|j| JoinParty {
            party_id: self.party_id.clone(),
            secret: j.secret.clone(),
            size: j.size,
            max: j.max,
        });
        Activity {
            state: state.to_string(),
            details: details.to_string(),
            start,
            large_image: large_key.to_string(),
            large_text: large_text.to_string(),
            small_image: small_key.to_string(),
            small_text: small_text.to_string(),
            join,
        }
    }

    /// Shows an activity, when the `discordRpcEnable` setting is on. `start` is the
    /// elapsed-time origin in Unix epoch seconds.
    #[allow(clippy::too_many_arguments)]
    pub fn set_rich_presence(
        &self,
        state: &str,
        details: &str,
        start: i64,
        large_key: &str,
        large_text: &str,
        small_key: &str,
        small_text: &str,
    ) {
        if !self.shared.rpc_enabled() {
            return;
        }
        let activity = self.build_activity(
            state, details, start, large_key, large_text, small_key, small_text,
        );
        self.send(Command::Set(activity));
    }

    /// "In menus" on `screen`. Does nothing while a game runs: presence follows the game.
    pub fn set_menu_presence(&self, screen: &str) {
        if self.shared.host().is_some_and(|h| h.any_game_running()) {
            return;
        }
        self.clear_join();
        let launcher = t!("discordRpc.image.launcher");
        self.set_rich_presence(
            &t!("discordRpc.state.inMenus"),
            screen,
            now_epoch_secs(),
            IMAGE_LAUNCHER,
            &launcher,
            IMAGE_MENU,
            screen,
        );
    }

    /// "In game" for `pack`. A pack with its own Discord RPC (`custom_rpc`) gets the
    /// launcher's presence out of the way instead. With invites on and a manifest URL, a join
    /// secret is staged (or, when it would exceed 128 bytes, the Join button is dropped).
    pub fn set_game_presence(&self, pack: &str, custom_rpc: bool, manifest_url: Option<&str>) {
        if custom_rpc {
            self.exit();
            return;
        }
        let invites = self.shared.host().is_some_and(|h| h.invites_enabled());
        match manifest_url.filter(|u| invites && !u.trim().is_empty()) {
            Some(url) => match build_join_secret(url) {
                Some(secret) => {
                    *self.join.lock() = Some(StagedJoin {
                        secret,
                        size: PARTY_SIZE,
                        max: PARTY_MAX,
                    });
                }
                None => {
                    tracing::warn!(
                        "{}",
                        tf!(
                            "log.discordRpc.joinSecretTooLong",
                            DISCORD_SECRET_MAX_BYTES.to_string()
                        )
                    );
                    self.clear_join();
                }
            },
            None => self.clear_join(),
        }
        self.set_rich_presence(
            &t!("discordRpc.state.inGame"),
            &tf!("discordRpc.details.modPack", sanitize_presence_field(pack)),
            now_epoch_secs(),
            IMAGE_LAUNCHER,
            &t!("discordRpc.image.launcher"),
            IMAGE_GAME,
            &t!("discordRpc.image.inGame"),
        );
    }

    /// Clears the activity and closes the connection (blocking up to
    /// [`PresenceOptions::exit_timeout`]; wrap it in `run_bounded` at shutdown). The join
    /// data is reset so a later presence does not carry this session's secret. A later
    /// presence update reconnects.
    pub fn exit(&self) {
        let sender = self.worker.lock().clone();
        if let Some(tx) = sender {
            let (ack_tx, ack_rx) = std_mpsc::channel();
            if tx.send(Command::Exit(ack_tx)).is_ok() {
                let _ = ack_rx.recv_timeout(self.shared.options.exit_timeout);
            }
        }
        self.clear_join();
    }

    fn send(&self, cmd: Command) {
        let mut worker = self.worker.lock();
        let cmd = match worker.as_ref() {
            Some(tx) => match tx.send(cmd) {
                Ok(()) => return,
                // The worker died; start a new one below.
                Err(mpsc::error::SendError(cmd)) => cmd,
            },
            None => cmd,
        };
        let (tx, rx) = mpsc::unbounded_channel();
        if start_worker(self.shared.clone(), rx).is_ok() && tx.send(cmd).is_ok() {
            *worker = Some(tx);
        }
    }
}

fn start_worker(shared: Arc<Shared>, rx: mpsc::UnboundedReceiver<Command>) -> std::io::Result<()> {
    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .inspect_err(|e| mmcl_log::warn_silent_err(&t!("log.discordRpc.setupFailed"), e))?;
    thread::Builder::new()
        .name("mmcl-discord-ipc".into())
        .spawn(move || runtime.block_on(Worker::new(shared).run(rx)))
        .inspect_err(|e| mmcl_log::warn_silent_err(&t!("log.discordRpc.setupFailed"), e))?;
    Ok(())
}

enum Step {
    Command(Option<Command>),
    Event(Option<ConnEvent>),
    Retry,
}

struct Worker {
    shared: Arc<Shared>,
    conn: Option<Connection>,
    events: Option<mpsc::UnboundedReceiver<ConnEvent>>,
    desired: Option<Activity>,
    last_attempt: Option<Instant>,
}

async fn recv_event(events: &mut Option<mpsc::UnboundedReceiver<ConnEvent>>) -> Option<ConnEvent> {
    match events {
        Some(rx) => rx.recv().await,
        None => std::future::pending().await,
    }
}

async fn sleep_until(at: Option<Instant>) {
    match at {
        Some(at) => tokio::time::sleep_until(at.into()).await,
        None => std::future::pending().await,
    }
}

impl Worker {
    fn new(shared: Arc<Shared>) -> Worker {
        Worker {
            shared,
            conn: None,
            events: None,
            desired: None,
            last_attempt: None,
        }
    }

    fn interval(&self) -> Duration {
        self.shared.options.reconnect_interval
    }

    fn attempt_due(&self) -> bool {
        self.last_attempt
            .is_none_or(|t| t.elapsed() >= self.interval())
    }

    fn retry_at(&self) -> Option<Instant> {
        if self.conn.is_some() || self.desired.is_none() {
            return None;
        }
        Some(
            self.last_attempt
                .map(|t| t + self.interval())
                .unwrap_or_else(Instant::now),
        )
    }

    async fn run(mut self, mut rx: mpsc::UnboundedReceiver<Command>) {
        loop {
            let retry_at = self.retry_at();
            let step = tokio::select! {
                cmd = rx.recv() => Step::Command(cmd),
                ev = recv_event(&mut self.events) => Step::Event(ev),
                _ = sleep_until(retry_at) => Step::Retry,
            };
            match step {
                Step::Command(None) => break,
                Step::Command(Some(Command::Set(activity))) => {
                    self.desired = Some(activity);
                    if self.conn.is_none() && self.attempt_due() {
                        self.connect().await;
                    }
                    self.push_desired().await;
                }
                Step::Command(Some(Command::Exit(ack))) => {
                    self.desired = None;
                    self.events = None;
                    if let Some(conn) = self.conn.take()
                        && let Err(e) = conn.clear_and_close(self.shared.options.exit_grace).await
                    {
                        mmcl_log::warn_silent_err(&t!("log.discordRpc.exitFailed"), &e);
                    }
                    // A later update may connect right away.
                    self.last_attempt = None;
                    let _ = ack.send(());
                }
                Step::Event(ev) => self.on_event(ev).await,
                Step::Retry => {
                    if self.shared.rpc_enabled() {
                        self.connect().await;
                        self.push_desired().await;
                    } else {
                        // Presence was turned off meanwhile: stop retrying.
                        self.desired = None;
                    }
                }
            }
        }
        if let Some(mut conn) = self.conn.take() {
            let _ = conn.close().await;
        }
    }

    async fn connect(&mut self) {
        self.last_attempt = Some(Instant::now());
        let opened = match self.shared.connector.connect().await {
            Ok(Some(opened)) => opened,
            Ok(None) => {
                tracing::debug!(
                    "{}",
                    tf!(
                        "log.discordRpc.notRunning",
                        self.interval().as_secs().to_string()
                    )
                );
                return;
            }
            Err(e) => {
                mmcl_log::warn_silent_err(&t!("log.discordRpc.setupFailed"), &e);
                return;
            }
        };
        let (stream, endpoint) = opened;
        let (tx, rx) = mpsc::unbounded_channel();
        match Connection::open(stream, endpoint, &self.shared.options.client_id, tx).await {
            Ok(mut conn) => {
                tracing::debug!("{}", tf!("log.discordRpc.connected", conn.endpoint()));
                // on_ready: the "in menus / loading" presence, as Java's onReady sent.
                let launcher = t!("discordRpc.image.launcher");
                let ready = Activity {
                    state: t!("discordRpc.state.inMenus").into_owned(),
                    details: t!("discordRpc.details.loading").into_owned(),
                    start: now_epoch_secs(),
                    large_image: IMAGE_LAUNCHER.to_string(),
                    large_text: launcher.to_string(),
                    small_image: IMAGE_LAUNCHER.to_string(),
                    small_text: launcher.into_owned(),
                    join: None,
                };
                if let Err(e) = conn.set_activity(SetActivity::Show(&ready)).await {
                    mmcl_log::warn_silent_err(&t!("log.discordRpc.updateFailed"), &e);
                    return;
                }
                self.conn = Some(conn);
                self.events = Some(rx);
            }
            Err(e) => mmcl_log::warn_silent_err(&t!("log.discordRpc.setupFailed"), &e),
        }
    }

    async fn push_desired(&mut self) {
        let (Some(conn), Some(activity)) = (self.conn.as_mut(), self.desired.as_ref()) else {
            return;
        };
        if let Err(e) = conn.set_activity(SetActivity::Show(activity)).await {
            mmcl_log::warn_silent_err(&t!("log.discordRpc.updateFailed"), &e);
            self.drop_connection();
        }
    }

    fn drop_connection(&mut self) {
        self.conn = None;
        self.events = None;
        self.last_attempt = Some(Instant::now());
    }

    async fn on_event(&mut self, ev: Option<ConnEvent>) {
        match ev {
            None => self.drop_connection(),
            Some(ConnEvent::Closed(reason)) => {
                tracing::debug!("{}", tf!("log.discordRpc.disconnected", reason));
                self.drop_connection();
            }
            Some(ConnEvent::Ping(payload)) => {
                if let Some(conn) = self.conn.as_mut()
                    && conn.pong(&payload).await.is_err()
                {
                    self.drop_connection();
                }
            }
            Some(ConnEvent::CommandError(msg)) => {
                tracing::debug!("{}", tf!("log.discordRpc.commandRejected", msg));
            }
            Some(ConnEvent::Join(secret)) => dispatch_join(self.shared.host(), secret),
        }
    }
}

/// Routes a join secret: an `mmcl://` link goes to the host's deep-link handler on the
/// background pool; anything else is logged and dropped.
fn dispatch_join(host: Option<Arc<dyn PresenceHost>>, secret: String) {
    tracing::info!("{}", tf!("log.discordRpc.joinReceived", secret.as_str()));
    if !mmcl_util::deeplink::is_launcher_uri(Some(&secret)) {
        tracing::warn!(
            "{}",
            tf!("log.discordRpc.joinNotRecognized", secret.as_str())
        );
        return;
    }
    let Some(host) = host else {
        return;
    };
    mmcl_util::tasks::spawn(move || {
        if catch_unwind(AssertUnwindSafe(|| host.handle_join(&secret))).is_err() {
            tracing::warn!("{}", t!("log.discordRpc.joinHandleFailed"));
        }
    });
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::frame::{Frame, Opcode, read_frame, write_frame};
    use crate::transport::{BoxStream, ConnectFuture};
    use serde_json::{Value, json};
    use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};

    struct TestHost {
        rpc: AtomicBool,
        invites: AtomicBool,
        running: AtomicBool,
        joins: Mutex<Vec<String>>,
    }

    impl TestHost {
        fn new() -> Arc<TestHost> {
            Arc::new(TestHost {
                rpc: AtomicBool::new(true),
                invites: AtomicBool::new(true),
                running: AtomicBool::new(false),
                joins: Mutex::new(Vec::new()),
            })
        }
    }

    impl PresenceHost for TestHost {
        fn rpc_enabled(&self) -> bool {
            self.rpc.load(Ordering::SeqCst)
        }
        fn invites_enabled(&self) -> bool {
            self.invites.load(Ordering::SeqCst)
        }
        fn any_game_running(&self) -> bool {
            self.running.load(Ordering::SeqCst)
        }
        fn handle_join(&self, uri: &str) {
            self.joins.lock().push(uri.to_string());
        }
    }

    /// Discord is not running: counts the attempts.
    struct Absent(AtomicUsize);

    impl Connector for Absent {
        fn connect(&self) -> ConnectFuture<'_> {
            self.0.fetch_add(1, Ordering::SeqCst);
            Box::pin(async { Ok(None) })
        }
    }

    type Injector = tokio::sync::mpsc::UnboundedSender<(Opcode, Value)>;

    /// A fake Discord: every connection gets a server task on its own thread that answers
    /// the handshake and records every frame.
    struct FakeDiscord {
        frames: Arc<Mutex<Vec<Frame>>>,
        inject: Arc<Mutex<Option<Injector>>>,
        connections: AtomicUsize,
    }

    impl FakeDiscord {
        fn new() -> Arc<FakeDiscord> {
            Arc::new(FakeDiscord {
                frames: Arc::new(Mutex::new(Vec::new())),
                inject: Arc::new(Mutex::new(None)),
                connections: AtomicUsize::new(0),
            })
        }

        fn frames(&self) -> Vec<Frame> {
            self.frames.lock().clone()
        }

        fn wait_for(&self, what: &str, pred: impl Fn(&[Frame]) -> bool) -> Vec<Frame> {
            let deadline = Instant::now() + Duration::from_secs(5);
            loop {
                let frames = self.frames();
                if pred(&frames) {
                    return frames;
                }
                assert!(
                    Instant::now() < deadline,
                    "timed out waiting for {what}: {frames:?}"
                );
                thread::sleep(Duration::from_millis(5));
            }
        }

        fn inject(&self, op: Opcode, payload: Value) {
            let tx = self.inject.lock().clone().expect("no connection");
            tx.send((op, payload)).unwrap();
        }
    }

    impl Connector for Arc<FakeDiscord> {
        fn connect(&self) -> ConnectFuture<'_> {
            self.connections.fetch_add(1, Ordering::SeqCst);
            let (client, mut server) = tokio::io::duplex(1 << 16);
            let frames = self.frames.clone();
            let (inj_tx, mut inj_rx) = tokio::sync::mpsc::unbounded_channel();
            *self.inject.lock() = Some(inj_tx);
            thread::spawn(move || {
                let rt = tokio::runtime::Builder::new_current_thread()
                    .enable_all()
                    .build()
                    .unwrap();
                rt.block_on(async move {
                    let hs = read_frame(&mut server).await.unwrap();
                    frames.lock().push(hs);
                    write_frame(
                        &mut server,
                        Opcode::Frame,
                        &json!({"cmd":"DISPATCH","evt":"READY","data":{"v":1}}),
                    )
                    .await
                    .unwrap();
                    let (mut r, mut w) = tokio::io::split(server);
                    let writer = tokio::spawn(async move {
                        while let Some((op, payload)) = inj_rx.recv().await {
                            if write_frame(&mut w, op, &payload).await.is_err() {
                                break;
                            }
                        }
                    });
                    while let Ok(f) = read_frame(&mut r).await {
                        frames.lock().push(f);
                    }
                    writer.abort();
                });
            });
            Box::pin(async move { Ok(Some((Box::new(client) as BoxStream, "fake".to_string()))) })
        }
    }

    fn fast_options() -> PresenceOptions {
        PresenceOptions {
            exit_grace: Duration::from_millis(1),
            ..PresenceOptions::default()
        }
    }

    fn activities(frames: &[Frame]) -> Vec<Value> {
        frames
            .iter()
            .filter(|f| f.op == Opcode::Frame && f.payload["cmd"] == "SET_ACTIVITY")
            .map(|f| f.payload["args"].clone())
            .collect()
    }

    #[test]
    fn nothing_happens_without_a_host_or_when_disabled() {
        let connector = Arc::new(Absent(AtomicUsize::new(0)));
        let p = Presence::new(connector.clone(), fast_options());
        p.set_menu_presence("Settings");
        let host = TestHost::new();
        host.rpc.store(false, Ordering::SeqCst);
        p.set_host(host);
        p.set_menu_presence("Settings");
        p.set_game_presence("Pack", false, Some("https://x/y.json"));
        thread::sleep(Duration::from_millis(50));
        assert_eq!(connector.0.load(Ordering::SeqCst), 0);
        assert!(p.worker.lock().is_none());
    }

    #[test]
    fn reconnects_at_most_once_per_interval_while_discord_is_absent() {
        let connector = Arc::new(Absent(AtomicUsize::new(0)));
        let p = Presence::new(connector.clone(), fast_options());
        p.set_host(TestHost::new());
        for _ in 0..5 {
            p.set_menu_presence("Settings");
        }
        thread::sleep(Duration::from_millis(100));
        assert_eq!(connector.0.load(Ordering::SeqCst), 1);
    }

    #[test]
    fn retries_on_a_timer_after_the_interval() {
        let connector = Arc::new(Absent(AtomicUsize::new(0)));
        let p = Presence::new(
            connector.clone(),
            PresenceOptions {
                reconnect_interval: Duration::from_millis(40),
                ..fast_options()
            },
        );
        p.set_host(TestHost::new());
        p.set_menu_presence("Settings");
        let deadline = Instant::now() + Duration::from_secs(5);
        while connector.0.load(Ordering::SeqCst) < 3 {
            assert!(Instant::now() < deadline, "no timer retries");
            thread::sleep(Duration::from_millis(10));
        }
    }

    #[test]
    fn game_presence_stages_and_menu_presence_clears_the_join_secret() {
        let p = Presence::new(Arc::new(Absent(AtomicUsize::new(0))), fast_options());
        let host = TestHost::new();
        p.set_host(host.clone());

        p.set_game_presence("Pack", false, Some("https://example.com/p.json"));
        assert_eq!(
            p.staged_join_secret().as_deref(),
            Some("mmcl://join?url=https%3A%2F%2Fexample.com%2Fp.json")
        );
        let a = p.build_activity("s", "d", 0, "l", "lt", "s", "st");
        let join = a.join.expect("join party");
        assert_eq!((join.size, join.max), (1, 4));
        assert_eq!(join.party_id, p.party_id());

        // While a game runs, menu presence is ignored and the secret survives.
        host.running.store(true, Ordering::SeqCst);
        p.set_menu_presence("Settings");
        assert!(p.staged_join_secret().is_some());

        host.running.store(false, Ordering::SeqCst);
        p.set_menu_presence("Settings");
        assert!(p.staged_join_secret().is_none());
    }

    #[test]
    fn game_presence_without_invites_or_with_long_url_has_no_join() {
        let p = Presence::new(Arc::new(Absent(AtomicUsize::new(0))), fast_options());
        let host = TestHost::new();
        p.set_host(host.clone());

        p.set_game_presence(
            "Pack",
            false,
            Some(&format!("https://x/{}", "a".repeat(200))),
        );
        assert!(p.staged_join_secret().is_none());

        p.set_game_presence("Pack", false, Some("https://x/y"));
        assert!(p.staged_join_secret().is_some());
        p.set_game_presence("Pack", false, None);
        assert!(p.staged_join_secret().is_none());

        host.invites.store(false, Ordering::SeqCst);
        p.set_game_presence("Pack", false, Some("https://x/y"));
        assert!(p.staged_join_secret().is_none());

        host.invites.store(true, Ordering::SeqCst);
        p.set_game_presence("Pack", false, Some("  "));
        assert!(p.staged_join_secret().is_none());
    }

    #[test]
    fn party_id_is_stable_per_presence() {
        let p = Presence::new(Arc::new(Absent(AtomicUsize::new(0))), fast_options());
        let id = p.party_id().to_string();
        assert!(uuid::Uuid::parse_str(&id).is_ok());
        assert_eq!(p.party_id(), id);
    }

    #[test]
    fn full_round_trip_with_a_fake_discord() {
        let discord = FakeDiscord::new();
        let p = Presence::new(Arc::new(discord.clone()), fast_options());
        let host = TestHost::new();
        p.set_host(host.clone());

        p.set_game_presence("My\nPack", false, Some("https://example.com/p.json"));
        let frames = discord.wait_for("game presence", |f| activities(f).len() >= 2);
        assert_eq!(frames[0].op, Opcode::Handshake);
        assert_eq!(frames[0].payload["client_id"], CLIENT_ID);
        assert!(
            frames
                .iter()
                .any(|f| f.payload["cmd"] == "SUBSCRIBE" && f.payload["evt"] == "ACTIVITY_JOIN")
        );
        let acts = activities(&frames);
        // on_ready first, then the requested presence.
        assert_eq!(
            acts[0]["activity"]["details"],
            t!("discordRpc.details.loading").as_ref()
        );
        assert_eq!(acts[0]["activity"]["assets"]["small_image"], IMAGE_LAUNCHER);
        let game = &acts[1]["activity"];
        assert_eq!(game["type"], 0);
        assert_eq!(game["state"], t!("discordRpc.state.inGame").as_ref());
        assert_eq!(
            game["details"],
            tf!("discordRpc.details.modPack", "MyPack").as_str()
        );
        assert_eq!(game["assets"]["small_image"], IMAGE_GAME);
        assert_eq!(game["party"]["size"], json!([1, 4]));
        assert_eq!(game["party"]["id"], p.party_id());
        assert_eq!(
            game["secrets"]["join"],
            "mmcl://join?url=https%3A%2F%2Fexample.com%2Fp.json"
        );
        assert_eq!(acts[1]["pid"], std::process::id());

        // Pings are answered with the same payload.
        discord.inject(Opcode::Ping, json!({"n":7}));
        discord.wait_for("pong", |f| {
            f.iter()
                .any(|f| f.op == Opcode::Pong && f.payload == json!({"n":7}))
        });

        // A join event reaches the host; a foreign secret does not.
        discord.inject(
            Opcode::Frame,
            json!({"cmd":"DISPATCH","evt":"ACTIVITY_JOIN","data":{"secret":"https://evil"}}),
        );
        discord.inject(
            Opcode::Frame,
            json!({"cmd":"DISPATCH","evt":"ACTIVITY_JOIN","data":{"secret":"mmcl://join?url=a"}}),
        );
        let deadline = Instant::now() + Duration::from_secs(5);
        while host.joins.lock().is_empty() {
            assert!(Instant::now() < deadline, "join not dispatched");
            thread::sleep(Duration::from_millis(5));
        }
        assert_eq!(*host.joins.lock(), vec!["mmcl://join?url=a".to_string()]);

        // Exit clears both ways, closes, and resets the join data.
        p.exit();
        assert!(p.staged_join_secret().is_none());
        let frames = discord.wait_for("close", |f| f.iter().any(|f| f.op == Opcode::Close));
        let acts = activities(&frames);
        let n = acts.len();
        assert_eq!(acts[n - 2], json!({"pid": std::process::id()}));
        assert_eq!(acts[n - 1]["activity"], json!({}));
        assert_eq!(frames.last().unwrap().op, Opcode::Close);

        // A later update reconnects straight away.
        p.set_menu_presence("Settings");
        discord.wait_for("reconnect", |f| {
            f.iter().filter(|f| f.op == Opcode::Handshake).count() == 2
        });
        assert_eq!(discord.connections.load(Ordering::SeqCst), 2);
    }

    #[test]
    fn custom_rpc_pack_exits_instead_of_showing_presence() {
        let discord = FakeDiscord::new();
        let p = Presence::new(Arc::new(discord.clone()), fast_options());
        p.set_host(TestHost::new());
        p.set_menu_presence("Settings");
        discord.wait_for("menu", |f| activities(f).len() >= 2);
        p.set_game_presence("Pack", true, Some("https://x/y"));
        let frames = discord.wait_for("close", |f| f.iter().any(|f| f.op == Opcode::Close));
        assert!(
            !activities(&frames)
                .iter()
                .any(|a| a["activity"]["state"] == t!("discordRpc.state.inGame").as_ref())
        );
        assert!(p.staged_join_secret().is_none());
    }

    #[test]
    fn exit_without_a_connection_returns_immediately() {
        let p = Presence::new(Arc::new(Absent(AtomicUsize::new(0))), fast_options());
        let start = Instant::now();
        p.exit();
        assert!(start.elapsed() < Duration::from_secs(1));
    }
}
