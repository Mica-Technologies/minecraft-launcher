// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Every signed-in account (`AccountManager`): several at once, one default, a single-flight
//! refresh per account, and the one-shot migration from the old single-account files.
//!
//! One mutex guards all state. Listeners run outside it, on whichever thread made the change;
//! a listener that panics is logged and the others still run. Refreshes run through the
//! injected [`Spawn`]; the futures handed out are shared, so callers that join an in-flight
//! refresh all see the same result.

use std::fmt;
use std::panic::AssertUnwindSafe;
use std::path::PathBuf;
use std::sync::Arc;
use std::sync::atomic::{AtomicU64, Ordering};

use futures::FutureExt;
use futures::channel::oneshot;
use futures::future::{BoxFuture, Shared};
use indexmap::IndexMap;
use mmcl_i18n::{t, tf};
use mmcl_secure::Cipher;
use parking_lot::Mutex;

use crate::classify::is_renewal_due;
use crate::spawn::Spawn;
use crate::store::{self, AUTH_FILE, AccountStore, CACHED_USER_FILE, RENEWAL_FILE, is_safe_uuid};
use crate::user::User;

/// An account's state.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum Status {
    /// Usable.
    Ready,
    /// A refresh is running; still usable.
    Refreshing,
    /// Microsoft refused the saved credentials; the account must sign in again.
    NeedsSignIn,
}

/// A snapshot of one account.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct AccountInfo {
    pub uuid: String,
    /// `""` when unknown.
    pub display_name: String,
    pub status: Status,
    pub is_default: bool,
    /// Whether it survives a restart ("Stay signed in").
    pub remembered: bool,
    pub last_used_ms: i64,
}

/// A failed refresh.
#[derive(Clone, PartialEq, Eq, thiserror::Error)]
#[error("{message}")]
pub struct RenewalError {
    /// A data-free description (an error kind name).
    pub message: String,
    /// `true` when Microsoft refused the credentials: the account needs to sign in again.
    pub credentials_rejected: bool,
    /// A rotated authentication file to keep although the renewal failed (the refresh grant
    /// succeeded and a later step did not). Never handed to callers of a refresh future.
    pub rotated_auth_file: Option<Vec<u8>>,
}

impl fmt::Debug for RenewalError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("RenewalError")
            .field("message", &self.message)
            .field("credentials_rejected", &self.credentials_rejected)
            .field("rotated", &self.rotated_auth_file.is_some())
            .finish()
    }
}

impl RenewalError {
    /// A failure without a rotated file.
    pub fn new(message: impl Into<String>, credentials_rejected: bool) -> RenewalError {
        RenewalError {
            message: message.into(),
            credentials_rejected,
            rotated_auth_file: None,
        }
    }

    fn without_rotated(mut self) -> RenewalError {
        self.rotated_auth_file = None;
        self
    }
}

/// A successful renewal: the fresh user and the (possibly rotated) gzip'd authentication file.
#[derive(Clone)]
pub struct Renewal {
    pub user: User,
    pub auth_file_gz: Vec<u8>,
}

impl fmt::Debug for Renewal {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("Renewal")
            .field("user", &self.user)
            .finish_non_exhaustive()
    }
}

/// Exchanges saved credentials for fresh tokens.
pub trait Renewer: Send + Sync {
    fn renew(&self, auth_file_gz: Vec<u8>) -> BoxFuture<'static, Result<Renewal, RenewalError>>;
}

/// Where the default account is persisted (`configuration.json` `defaultAccountUuid`).
pub trait DefaultAccountSetting: Send + Sync {
    /// The persisted default, `""` for none.
    fn get(&self) -> String;
    /// Persists the default, `""` for none.
    fn set(&self, uuid: &str);
}

/// A refresh in flight or done. Cloneable; every clone sees the same result.
pub type RefreshFuture = Shared<BoxFuture<'static, Result<User, RenewalError>>>;

/// Identifies a listener for [`AccountManager::remove_listener`].
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub struct ListenerId(u64);

type Listener = Arc<dyn Fn() + Send + Sync>;
type Clock = Arc<dyn Fn() -> i64 + Send + Sync>;

struct Session {
    uuid: String,
    display_name: String,
    user: Option<User>,
    renewed_at_ms: i64,
    last_used_ms: i64,
    remembered: bool,
    status: Status,
    memory_auth_file: Option<Vec<u8>>,
    /// The in-flight refresh and its identity (pointer identity in the Java).
    pending: Option<(u64, RefreshFuture)>,
}

impl Session {
    fn new(uuid: &str) -> Session {
        Session {
            uuid: uuid.to_string(),
            display_name: String::new(),
            user: None,
            renewed_at_ms: 0,
            last_used_ms: 0,
            remembered: false,
            status: Status::Ready,
            memory_auth_file: None,
            pending: None,
        }
    }
}

#[derive(Default)]
struct State {
    sessions: IndexMap<String, Session>,
    default_uuid: Option<String>,
    loaded: bool,
    /// An old flat-layout authentication file whose account is not known yet.
    unidentified_legacy_auth_file: Option<Vec<u8>>,
    /// The single renewal of that file, once started.
    legacy_pending: Option<RefreshFuture>,
}

struct Inner {
    store: AccountStore,
    legacy_dir: PathBuf,
    cipher: Arc<dyn Cipher>,
    default_setting: Arc<dyn DefaultAccountSetting>,
    renewer: Arc<dyn Renewer>,
    clock: Clock,
    spawner: Arc<dyn Spawn>,
    refresh_interval_ms: i64,
    state: Mutex<State>,
    listeners: Mutex<Vec<(ListenerId, Listener)>>,
    next_id: AtomicU64,
}

/// The accounts. Cheap to clone; clones share state.
#[derive(Clone)]
pub struct AccountManager {
    inner: Arc<Inner>,
}

impl fmt::Debug for AccountManager {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("AccountManager")
            .field("store", &self.inner.store)
            .finish_non_exhaustive()
    }
}

fn ready(r: Result<User, RenewalError>) -> RefreshFuture {
    futures::future::ready(r).boxed().shared()
}

/// The configured default if it is still signed in.
pub fn resolve_default<'a>(
    configured: &str,
    mut available: impl Iterator<Item = &'a str>,
) -> Option<String> {
    if configured.trim().is_empty() {
        return None;
    }
    available
        .any(|a| a == configured)
        .then(|| configured.to_string())
}

impl AccountManager {
    /// A manager. `legacy_dir` is the config folder that held the old single-account files;
    /// `cipher` is what they were written with; `refresh_interval_ms` is how old a token may get
    /// (4 h in production).
    #[allow(clippy::too_many_arguments)]
    pub fn new(
        store: AccountStore,
        legacy_dir: impl Into<PathBuf>,
        cipher: Arc<dyn Cipher>,
        default_setting: Arc<dyn DefaultAccountSetting>,
        renewer: Arc<dyn Renewer>,
        clock: Arc<dyn Fn() -> i64 + Send + Sync>,
        spawner: Arc<dyn Spawn>,
        refresh_interval_ms: i64,
    ) -> AccountManager {
        AccountManager {
            inner: Arc::new(Inner {
                store,
                legacy_dir: legacy_dir.into(),
                cipher,
                default_setting,
                renewer,
                clock,
                spawner,
                refresh_interval_ms,
                state: Mutex::new(State::default()),
                listeners: Mutex::new(Vec::new()),
                next_id: AtomicU64::new(1),
            }),
        }
    }

    /// The store.
    pub fn store(&self) -> &AccountStore {
        &self.inner.store
    }

    /// The clock (epoch millis).
    pub fn now_ms(&self) -> i64 {
        (self.inner.clock)()
    }

    /// The refresh interval.
    pub fn refresh_interval_ms(&self) -> i64 {
        self.inner.refresh_interval_ms
    }

    // ---------------------------------------------------------------- queries

    /// Every signed-in account, most recently used first.
    pub fn accounts(&self) -> Vec<AccountInfo> {
        let st = self.inner.lock_loaded();
        st.by_recent_use()
            .into_iter()
            .map(|s| st.snapshot(s))
            .collect()
    }

    /// One account.
    pub fn account(&self, uuid: &str) -> Option<AccountInfo> {
        let st = self.inner.lock_loaded();
        st.sessions.get(uuid).map(|s| st.snapshot(s))
    }

    /// The user of an account (with its current access token); `None` when unknown or not yet
    /// identified.
    pub fn user(&self, uuid: &str) -> Option<User> {
        self.inner
            .lock_loaded()
            .sessions
            .get(uuid)
            .and_then(|s| s.user.clone())
    }

    /// The default account's uuid.
    pub fn default_uuid(&self) -> Option<String> {
        self.inner.lock_loaded().default_uuid.clone()
    }

    /// The default account's user.
    pub fn default_user(&self) -> Option<User> {
        let st = self.inner.lock_loaded();
        st.default_uuid
            .as_ref()
            .and_then(|u| st.sessions.get(u))
            .and_then(|s| s.user.clone())
    }

    /// Whether the login screen can be skipped: a default account, or an old single-account
    /// login still to be identified by its first renewal.
    pub fn has_default(&self) -> bool {
        let st = self.inner.lock_loaded();
        st.default_uuid.is_some() || st.unidentified_legacy_auth_file.is_some()
    }

    /// Whether an account's refresh is due (unknown accounts: `false`).
    pub fn is_refresh_due(&self, uuid: &str) -> bool {
        let st = self.inner.lock_loaded();
        st.sessions.get(uuid).is_some_and(|s| self.inner.is_due(s))
    }

    /// When an account was last renewed (`0` when unknown).
    pub fn renewed_at_ms(&self, uuid: &str) -> i64 {
        self.inner
            .lock_loaded()
            .sessions
            .get(uuid)
            .map_or(0, |s| s.renewed_at_ms)
    }

    /// The account's in-flight refresh.
    pub fn pending_refresh(&self, uuid: &str) -> Option<RefreshFuture> {
        self.inner
            .lock_loaded()
            .sessions
            .get(uuid)
            .and_then(|s| s.pending.as_ref().map(|(_, f)| f.clone()))
    }

    // ---------------------------------------------------------------- changes

    /// Makes an account the default (`None`: no default; every account stays signed in and the
    /// next start shows the login screen). `false` when `uuid` is not signed in.
    pub fn set_default(&self, uuid: Option<&str>) -> bool {
        {
            let mut st = self.inner.lock_loaded();
            if let Some(u) = uuid
                && !st.sessions.contains_key(u)
            {
                return false;
            }
            if st.default_uuid.as_deref() == uuid {
                return true;
            }
            st.default_uuid = uuid.map(str::to_string);
            if let Some(u) = uuid {
                self.inner.touch(&mut st, u);
            }
            self.inner.persist_default(&st);
        }
        self.inner.fire_changed();
        true
    }

    /// Adds or replaces an account after an interactive sign-in. Without `remember` the account
    /// is held in memory only and any stored copy is deleted. It becomes the default when
    /// `make_default` is set or there is no default. `false` when the user's uuid is unusable.
    pub fn add_signed_in(
        &self,
        user: User,
        auth_file_gz: Vec<u8>,
        remember: bool,
        make_default: bool,
    ) -> bool {
        if !is_safe_uuid(&user.uuid) {
            return false;
        }
        {
            let mut st = self.inner.lock_loaded();
            self.inner
                .add_locked(&mut st, user, auth_file_gz, remember, make_default);
        }
        self.inner.fire_changed();
        true
    }

    /// Signs an account out and forgets it, deleting its stored credentials. The most recently
    /// used remaining account becomes the default if it was the default.
    pub fn remove(&self, uuid: &str) -> bool {
        {
            let mut st = self.inner.lock_loaded();
            let Some(session) = st.sessions.shift_remove(uuid) else {
                return false;
            };
            if session.remembered {
                self.inner.store.remove(uuid);
            }
            if st.default_uuid.as_deref() == Some(uuid) {
                st.default_uuid = st.by_recent_use().first().map(|s| s.uuid.clone());
                self.inner.persist_default(&st);
            }
        }
        self.inner.fire_changed();
        true
    }

    /// Refreshes an account when due, or always with `force`. One refresh per account is in
    /// flight at a time: a second call joins it. A fresh token completes at once without
    /// touching the network.
    pub fn refresh(&self, uuid: &str, force: bool) -> RefreshFuture {
        let (fut, id, auth, tx);
        {
            let mut st = self.inner.lock_loaded();
            let inner = &self.inner;
            let Some(session) = st.sessions.get_mut(uuid) else {
                return ready(Err(RenewalError::new("unknown account", false)));
            };
            if let Some((_, p)) = &session.pending {
                return p.clone();
            }
            if !force
                && !inner.is_due(session)
                && let Some(u) = &session.user
            {
                return ready(Ok(u.clone()));
            }
            let saved = if session.remembered {
                inner.store.read_auth_file(uuid)
            } else {
                session.memory_auth_file.clone()
            };
            let Some(saved) = saved else {
                session.status = Status::NeedsSignIn;
                drop(st);
                self.inner.fire_changed();
                return ready(Err(RenewalError::new("no saved credentials", true)));
            };
            let (sender, receiver) = oneshot::channel::<Result<User, RenewalError>>();
            id = inner.next_id.fetch_add(1, Ordering::Relaxed);
            fut = receiver
                .map(|r| r.unwrap_or_else(|_| Err(RenewalError::new("cancelled", false))))
                .boxed()
                .shared();
            session.pending = Some((id, fut.clone()));
            session.status = Status::Refreshing;
            auth = saved;
            tx = sender;
        }
        self.inner.fire_changed();
        let inner = self.inner.clone();
        let uuid = uuid.to_string();
        let renewal = self.inner.renewer.renew(auth);
        // If the task is dropped unrun (runtime shutting down, queue closed) or panics, the
        // pending slot must not keep handing out a future that never completes successfully.
        let guard = OnDrop::new({
            let inner = self.inner.clone();
            let uuid = uuid.clone();
            move || inner.abandon_refresh(&uuid, id)
        });
        self.inner.spawner.spawn(
            async move {
                let _guard = guard;
                let result = run_renewal(renewal).await;
                inner.complete_refresh(&uuid, id, result, tx);
            }
            .boxed(),
        );
        fut
    }

    /// Loads the stored accounts now (normally done on first use). Loading decrypts every
    /// account's files, about three PBKDF2 runs each, so front ends call this off the UI thread
    /// before their first query.
    pub fn load_now(&self) {
        drop(self.inner.lock_loaded());
    }

    /// Refreshes the default account when due, the way startup needs it; also identifies an old
    /// single-account login whose cached user was unreadable (its renewal reveals the account,
    /// which is then stored and made the default).
    pub fn refresh_default(&self) -> RefreshFuture {
        let (sender, fut, legacy) = {
            let mut st = self.inner.lock_loaded();
            if let Some(current) = st.default_uuid.clone() {
                drop(st);
                return self.refresh(&current, false);
            }
            let Some(legacy) = st.unidentified_legacy_auth_file.clone() else {
                return ready(Err(RenewalError::new("no default account", true)));
            };
            if let Some(p) = &st.legacy_pending {
                return p.clone();
            }
            let (sender, receiver) = oneshot::channel::<Result<User, RenewalError>>();
            let fut = receiver
                .map(|r| r.unwrap_or_else(|_| Err(RenewalError::new("cancelled", false))))
                .boxed()
                .shared();
            st.legacy_pending = Some(fut.clone());
            (sender, fut, legacy)
        };
        let this = self.clone();
        let renewal = self.inner.renewer.renew(legacy);
        let guard = OnDrop::new({
            let inner = self.inner.clone();
            let fut = fut.clone();
            move || {
                let mut st = inner.state.lock();
                if st.legacy_pending.as_ref().is_some_and(|p| p.ptr_eq(&fut)) {
                    st.legacy_pending = None;
                }
            }
        });
        self.inner.spawner.spawn(
            async move {
                let _guard = guard;
                let result = run_renewal(renewal).await;
                let outcome = match result {
                    Ok(r) => {
                        {
                            let mut st = this.inner.state.lock();
                            st.unidentified_legacy_auth_file = None;
                            st.legacy_pending = None;
                        }
                        this.inner.delete_legacy_files();
                        let user = r.user.clone();
                        if this.add_signed_in(r.user, r.auth_file_gz, true, true) {
                            Ok(user)
                        } else {
                            // Microsoft named an account the store cannot hold; it can never be
                            // used, so the old login is gone either way.
                            Err(RenewalError::new(
                                "renewal returned an unusable account",
                                true,
                            ))
                        }
                    }
                    Err(e) => {
                        {
                            let mut st = this.inner.state.lock();
                            st.legacy_pending = None;
                            if e.credentials_rejected {
                                st.unidentified_legacy_auth_file = None;
                            }
                        }
                        if e.credentials_rejected {
                            this.inner.delete_legacy_files();
                        }
                        Err(e.without_rotated())
                    }
                };
                let _ = sender.send(outcome);
            }
            .boxed(),
        );
        fut
    }

    /// Registers a listener for any change to the account list, a status or the default.
    pub fn add_listener(&self, f: Arc<dyn Fn() + Send + Sync>) -> ListenerId {
        let id = ListenerId(self.inner.next_id.fetch_add(1, Ordering::Relaxed));
        self.inner.listeners.lock().push((id, f));
        id
    }

    /// Removes a listener.
    pub fn remove_listener(&self, id: ListenerId) {
        self.inner.listeners.lock().retain(|(i, _)| *i != id);
    }
}

/// Runs a closure when dropped.
struct OnDrop<F: FnOnce()>(Option<F>);

impl<F: FnOnce()> OnDrop<F> {
    fn new(f: F) -> OnDrop<F> {
        OnDrop(Some(f))
    }
}

impl<F: FnOnce()> Drop for OnDrop<F> {
    fn drop(&mut self) {
        if let Some(f) = self.0.take() {
            f();
        }
    }
}

/// Runs a renewal, turning a panic into a transient failure.
async fn run_renewal(
    renewal: BoxFuture<'static, Result<Renewal, RenewalError>>,
) -> Result<Renewal, RenewalError> {
    match AssertUnwindSafe(renewal).catch_unwind().await {
        Ok(r) => r,
        Err(_) => Err(RenewalError::new("panic", false)),
    }
}

impl State {
    fn by_recent_use(&self) -> Vec<&Session> {
        let mut list: Vec<&Session> = self.sessions.values().collect();
        list.sort_by_key(|s| std::cmp::Reverse(s.last_used_ms));
        list
    }

    fn snapshot(&self, s: &Session) -> AccountInfo {
        AccountInfo {
            uuid: s.uuid.clone(),
            display_name: s.display_name.clone(),
            status: s.status,
            is_default: self.default_uuid.as_deref() == Some(s.uuid.as_str()),
            remembered: s.remembered,
            last_used_ms: s.last_used_ms,
        }
    }
}

impl Inner {
    fn now(&self) -> i64 {
        (self.clock)()
    }

    fn is_due(&self, s: &Session) -> bool {
        s.user.is_none() || is_renewal_due(s.renewed_at_ms, self.now(), self.refresh_interval_ms)
    }

    /// Locks the state, loading it on first use.
    fn lock_loaded(&self) -> parking_lot::MutexGuard<'_, State> {
        let mut st = self.state.lock();
        if !st.loaded {
            // Set first: a failure part-way never retries.
            st.loaded = true;
            self.load(&mut st);
        }
        st
    }

    /// Frees the pending slot of a refresh whose task ended without completing it (dropped
    /// unrun or panicked). A no-op when the refresh completed normally.
    fn abandon_refresh(&self, uuid: &str, id: u64) {
        let changed = {
            let mut st = self.state.lock();
            match st.sessions.get_mut(uuid) {
                Some(s) if s.pending.as_ref().is_some_and(|(p, _)| *p == id) => {
                    s.pending = None;
                    if s.status == Status::Refreshing {
                        s.status = Status::Ready;
                    }
                    true
                }
                _ => false,
            }
        };
        if changed {
            self.fire_changed();
        }
    }

    fn load(&self, st: &mut State) {
        let migrated = self.migrate_legacy_layout(st);
        for entry in self.store.list() {
            let mut s = Session::new(&entry.uuid);
            s.remembered = true;
            // A cached user is only this folder's identity when it names this folder's account
            // (the identity behind an entry never silently changes).
            s.user = self
                .store
                .read_cached_user(&entry.uuid)
                .filter(|u| u.uuid == entry.uuid);
            s.renewed_at_ms = self.store.read_renewal_ms(&entry.uuid);
            s.last_used_ms = entry.last_used_ms;
            s.display_name = match &s.user {
                Some(u) => u.name.clone(),
                None => entry.display_name.clone(),
            };
            st.sessions.insert(entry.uuid.clone(), s);
        }
        if let Some(m) = &migrated {
            // The flat files were the active account, so it was the default by definition.
            self.default_setting.set(m);
        }
        let configured = self.default_setting.get();
        st.default_uuid = resolve_default(&configured, st.sessions.keys().map(String::as_str));
    }

    /// Copies the old flat files into the store, verifies, then deletes them.
    fn migrate_legacy_layout(&self, st: &mut State) -> Option<String> {
        let legacy_auth = self.legacy_dir.join(AUTH_FILE);
        if !legacy_auth.is_file() {
            return None;
        }
        let Some(auth) = store::read_auth_file_at(&legacy_auth, self.cipher.as_ref()) else {
            mmcl_log::warn_silent(&t!("log.accountStore.legacyUnreadable"));
            self.delete_legacy_files();
            return None;
        };
        let user = store::read_cached_user_at(
            &self.legacy_dir.join(CACHED_USER_FILE),
            self.cipher.as_ref(),
        );
        let Some(user) = user.filter(|u| is_safe_uuid(&u.uuid)) else {
            st.unidentified_legacy_auth_file = Some(auth);
            return None;
        };
        let renewed =
            store::read_renewal_ms_at(&self.legacy_dir.join(RENEWAL_FILE), self.cipher.as_ref());
        let result = self
            .persist(&user, &auth, renewed, self.now())
            .and_then(|()| {
                if self.store.read_auth_file(&user.uuid).is_none() {
                    Err(std::io::Error::other(
                        "migrated authentication file did not read back",
                    ))
                } else {
                    Ok(())
                }
            });
        if let Err(e) = result {
            mmcl_log::warn_silent(&tf!("log.accountStore.migrationFailed", e.to_string()));
            return None;
        }
        self.delete_legacy_files();
        mmcl_log::std(&t!("log.accountStore.migrated"));
        Some(user.uuid)
    }

    fn delete_legacy_files(&self) {
        for name in [AUTH_FILE, CACHED_USER_FILE, RENEWAL_FILE] {
            let p = self.legacy_dir.join(name);
            if let Err(e) = std::fs::remove_file(&p)
                && e.kind() != std::io::ErrorKind::NotFound
            {
                mmcl_log::warn_silent(&tf!("log.accountStore.legacyDeleteFailed", name));
            }
        }
    }

    fn add_locked(
        &self,
        st: &mut State,
        user: User,
        auth_file_gz: Vec<u8>,
        remember: bool,
        make_default: bool,
    ) {
        let now = self.now();
        let uuid = user.uuid.clone();
        let session = st
            .sessions
            .entry(uuid.clone())
            .or_insert_with(|| Session::new(&uuid));
        session.display_name = user.name.clone();
        session.renewed_at_ms = now;
        session.last_used_ms = now;
        session.status = Status::Ready;
        session.remembered = remember;
        if remember {
            session.memory_auth_file = None;
            if let Err(e) = self.persist(&user, &auth_file_gz, now, now) {
                // Keep the sign-in usable for this run even though it won't survive a restart.
                mmcl_log::error_async(&tf!("log.accountStore.persistFailed", e.to_string()));
                session.remembered = false;
                session.memory_auth_file = Some(auth_file_gz);
            }
        } else {
            session.memory_auth_file = Some(auth_file_gz);
            // An older stored copy would otherwise come back on the next start.
            self.store.remove(&uuid);
        }
        session.user = Some(user);
        if make_default || st.default_uuid.is_none() {
            st.default_uuid = Some(uuid);
        }
        self.persist_default(st);
    }

    fn complete_refresh(
        &self,
        uuid: &str,
        id: u64,
        result: Result<Renewal, RenewalError>,
        tx: oneshot::Sender<Result<User, RenewalError>>,
    ) {
        let result = match result {
            Ok(r) if r.user.uuid == uuid => Ok(r),
            // The identity behind an entry never silently changes.
            Ok(_) => Err(RenewalError::new(
                "renewal returned a different account",
                true,
            )),
            Err(e) => Err(e),
        };
        {
            let mut st = self.state.lock();
            let now = self.now();
            if let Some(session) = st.sessions.get_mut(uuid)
                && session.pending.as_ref().is_some_and(|(p, _)| *p == id)
            {
                session.pending = None;
                match &result {
                    Ok(r) => {
                        session.user = Some(r.user.clone());
                        session.display_name = r.user.name.clone();
                        session.renewed_at_ms = now;
                        session.status = Status::Ready;
                        if session.remembered {
                            let last_used = session.last_used_ms;
                            if let Err(e) = self.persist(&r.user, &r.auth_file_gz, now, last_used) {
                                mmcl_log::error_async(&tf!(
                                    "log.accountStore.persistFailed",
                                    e.to_string()
                                ));
                            }
                        } else {
                            session.memory_auth_file = Some(r.auth_file_gz.clone());
                        }
                    }
                    Err(e) => {
                        // A transient failure leaves the account usable on its current token.
                        session.status = if e.credentials_rejected {
                            Status::NeedsSignIn
                        } else {
                            Status::Ready
                        };
                        if let Some(rotated) = &e.rotated_auth_file {
                            // Keep the rotated refresh token so the next renewal uses it.
                            if session.remembered {
                                match self.store.write_auth_file(uuid, rotated) {
                                    Ok(()) => {
                                        mmcl_log::debug(&t!("log.authManager.rotatedTokenSaved"))
                                    }
                                    Err(e) => mmcl_log::error_async(&tf!(
                                        "log.accountStore.persistFailed",
                                        e.to_string()
                                    )),
                                }
                            } else {
                                session.memory_auth_file = Some(rotated.clone());
                            }
                        }
                    }
                }
            }
            // A session removed while its refresh ran stays removed; nothing is persisted.
        }
        self.fire_changed();
        let _ = tx.send(match result {
            Ok(r) => Ok(r.user),
            Err(e) => Err(e.without_rotated()),
        });
    }

    /// Writes every file of a remembered account; the first IO error aborts.
    fn persist(
        &self,
        user: &User,
        auth_file_gz: &[u8],
        renewed_at_ms: i64,
        last_used_ms: i64,
    ) -> std::io::Result<()> {
        self.store.write_auth_file(&user.uuid, auth_file_gz)?;
        self.store.write_cached_user(user)?;
        self.store.write_renewal_ms(&user.uuid, renewed_at_ms)?;
        self.store.write_meta(&user.uuid, &user.name, last_used_ms)
    }

    fn touch(&self, st: &mut State, uuid: &str) {
        let now = self.now();
        if let Some(s) = st.sessions.get_mut(uuid) {
            s.last_used_ms = now;
            if s.remembered
                && let Err(e) = self.store.write_meta(&s.uuid, &s.display_name, now)
            {
                mmcl_log::warn_silent(&tf!("log.accountStore.persistFailed", e.to_string()));
            }
        }
    }

    /// Persists the default. A session-only default can't be the default after a restart, so
    /// the most recently used remembered account is persisted in its place.
    fn persist_default(&self, st: &State) {
        let current = st.default_uuid.as_ref().and_then(|u| st.sessions.get(u));
        match current {
            None => self.default_setting.set(""),
            Some(s) if s.remembered => self.default_setting.set(&s.uuid),
            Some(_) => {
                let stand_in = st
                    .by_recent_use()
                    .into_iter()
                    .find(|s| s.remembered)
                    .map(|s| s.uuid.clone())
                    .unwrap_or_default();
                self.default_setting.set(&stand_in);
            }
        }
    }

    fn fire_changed(&self) {
        let listeners: Vec<Listener> = self
            .listeners
            .lock()
            .iter()
            .map(|(_, l)| l.clone())
            .collect();
        for l in listeners {
            if std::panic::catch_unwind(AssertUnwindSafe(|| l())).is_err() {
                mmcl_log::warn_silent(&tf!("log.accountStore.listenerFailed", "panic"));
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::store::PROFILES_DIR;
    use crate::testutil::*;
    use std::path::Path;
    use std::sync::atomic::AtomicUsize;

    const INTERVAL: i64 = 4 * 60 * 60 * 1000;
    const T0: i64 = 1_700_000_000_000;

    struct Fixture {
        dir: tempfile::TempDir,
        store: AccountStore,
        clock: ManualClock,
        defaults: Arc<InMemoryDefault>,
        renewer: Arc<ScriptedRenewer>,
        manual: Arc<ManualSpawner>,
    }

    impl Fixture {
        fn new() -> Fixture {
            let dir = tempfile::tempdir().unwrap();
            let store = AccountStore::new(dir.path().join(PROFILES_DIR), Arc::new(FakeCipher));
            Fixture {
                dir,
                store,
                clock: ManualClock::new(T0),
                defaults: Arc::new(InMemoryDefault::default()),
                renewer: Arc::new(ScriptedRenewer::default()),
                manual: Arc::new(ManualSpawner::default()),
            }
        }

        fn legacy(&self) -> &Path {
            self.dir.path()
        }

        fn with(&self, cipher: Arc<dyn Cipher>, spawner: Arc<dyn Spawn>) -> AccountManager {
            AccountManager::new(
                self.store.clone(),
                self.legacy(),
                cipher,
                self.defaults.clone(),
                self.renewer.clone(),
                self.clock.as_fn(),
                spawner,
                INTERVAL,
            )
        }

        fn inline(&self) -> AccountManager {
            self.with(Arc::new(FakeCipher), Arc::new(crate::spawn::InlineSpawner))
        }

        fn manual(&self) -> AccountManager {
            self.with(Arc::new(FakeCipher), self.manual.clone())
        }

        fn write_legacy(&self, user: Option<&User>, auth: &[u8], renewed_at: i64) {
            std::fs::write(
                self.legacy().join("player.mica"),
                FakeCipher.encrypt(auth).unwrap(),
            )
            .unwrap();
            if let Some(u) = user {
                let json = format!(
                    "{{\"uuid\":\"{}\",\"name\":\"{}\",\"accessToken\":\"{}\",\"type\":\"msa\"}}",
                    u.uuid,
                    u.name,
                    u.access_token.as_deref().unwrap()
                );
                std::fs::write(
                    self.legacy().join("cached_user.json"),
                    mmcl_secure::cipher::encode_base64(
                        &FakeCipher.encrypt(json.as_bytes()).unwrap(),
                    ),
                )
                .unwrap();
            }
            std::fs::write(
                self.legacy().join("renewal.timestamp"),
                renewed_at.to_string(),
            )
            .unwrap();
        }

        fn legacy_remains(&self) -> bool {
            ["player.mica", "cached_user.json", "renewal.timestamp"]
                .iter()
                .any(|n| self.legacy().join(n).exists())
        }
    }

    fn uuids(a: &[AccountInfo]) -> Vec<String> {
        a.iter().map(|a| a.uuid.clone()).collect()
    }

    fn get(f: RefreshFuture) -> Result<User, RenewalError> {
        futures::executor::block_on(f)
    }

    fn token(u: &User) -> &str {
        u.access_token.as_deref().unwrap()
    }

    #[test]
    fn no_accounts_means_no_default() {
        let fx = Fixture::new();
        let m = fx.inline();
        assert!(m.accounts().is_empty());
        assert!(!m.has_default());
        assert!(m.default_user().is_none());
    }

    #[test]
    fn a_remembered_sign_in_survives_a_restart_as_the_default() {
        let fx = Fixture::new();
        fx.inline()
            .add_signed_in(user('a', "token-a"), auth_file("a"), true, true);
        let restarted = fx.inline();
        assert_eq!(restarted.default_uuid(), Some(uuid('a')));
        assert_eq!(token(&restarted.default_user().unwrap()), "token-a");
        assert!(restarted.account(&uuid('a')).unwrap().remembered);
    }

    #[test]
    fn several_accounts_stay_signed_in_together() {
        let fx = Fixture::new();
        let m = fx.inline();
        m.add_signed_in(user('a', "ta"), auth_file("a"), true, true);
        fx.clock.advance(1000);
        m.add_signed_in(user('b', "tb"), auth_file("b"), true, true);
        assert_eq!(uuids(&m.accounts()), vec![uuid('b'), uuid('a')]);
        assert_eq!(token(&m.user(&uuid('a')).unwrap()), "ta");
        assert_eq!(token(&m.user(&uuid('b')).unwrap()), "tb");
        assert_eq!(m.default_uuid(), Some(uuid('b')));
        assert_eq!(fx.inline().accounts().len(), 2);
    }

    #[test]
    fn a_session_only_sign_in_is_never_written_and_is_gone_after_a_restart() {
        let fx = Fixture::new();
        let m = fx.inline();
        m.add_signed_in(user('a', "ta"), auth_file("a"), true, true);
        fx.clock.advance(1000);
        m.add_signed_in(user('b', "tb"), auth_file("b"), false, true);
        assert_eq!(m.default_uuid(), Some(uuid('b')));
        assert!(!m.account(&uuid('b')).unwrap().remembered);
        assert!(!fx.store.root().join(uuid('b')).exists());
        let restarted = fx.inline();
        assert!(restarted.account(&uuid('b')).is_none());
        assert_eq!(restarted.default_uuid(), Some(uuid('a')));
    }

    #[test]
    fn a_session_only_default_persists_the_most_recent_remembered_account() {
        let fx = Fixture::new();
        let m = fx.inline();
        m.add_signed_in(user('a', "ta"), auth_file("a"), true, true);
        fx.clock.advance(1000);
        m.add_signed_in(user('c', "tc"), auth_file("c"), true, true);
        fx.clock.advance(1000);
        m.add_signed_in(user('b', "tb"), auth_file("b"), false, true);
        assert_eq!(*fx.defaults.0.lock(), uuid('c'));
        m.remove(&uuid('a'));
        m.remove(&uuid('c'));
        m.set_default(None);
        m.set_default(Some(&uuid('b')));
        assert_eq!(*fx.defaults.0.lock(), "");
    }

    #[test]
    fn signing_in_again_without_remember_me_deletes_the_stored_copy() {
        let fx = Fixture::new();
        let m = fx.inline();
        m.add_signed_in(user('a', "ta"), auth_file("a"), true, true);
        m.add_signed_in(user('a', "ta2"), auth_file("a2"), false, true);
        assert!(fx.store.read_auth_file(&uuid('a')).is_none());
        assert!(fx.inline().account(&uuid('a')).is_none());
    }

    #[test]
    fn a_user_without_a_usable_uuid_is_refused() {
        let fx = Fixture::new();
        let m = fx.inline();
        let mut evil = user('a', "t");
        evil.uuid = "../evil".into();
        assert!(!m.add_signed_in(evil, auth_file("x"), true, true));
        assert!(m.accounts().is_empty());
    }

    #[test]
    fn switching_the_default_is_immediate_and_notifies_listeners() {
        let fx = Fixture::new();
        let m = fx.inline();
        m.add_signed_in(user('a', "ta"), auth_file("a"), true, true);
        m.add_signed_in(user('b', "tb"), auth_file("b"), true, true);
        let changes = Arc::new(AtomicUsize::new(0));
        let c = changes.clone();
        m.add_listener(Arc::new(move || {
            c.fetch_add(1, Ordering::SeqCst);
        }));
        assert!(m.set_default(Some(&uuid('a'))));
        assert_eq!(token(&m.default_user().unwrap()), "ta");
        assert_eq!(changes.load(Ordering::SeqCst), 1);
        assert_eq!(*fx.defaults.0.lock(), uuid('a'));
        assert!(m.set_default(Some(&uuid('a'))));
        assert_eq!(changes.load(Ordering::SeqCst), 1);
    }

    #[test]
    fn a_panicking_listener_does_not_stop_the_others() {
        let fx = Fixture::new();
        let m = fx.inline();
        let hits = Arc::new(AtomicUsize::new(0));
        m.add_listener(Arc::new(|| panic!("listener bug")));
        let h = hits.clone();
        let id = m.add_listener(Arc::new(move || {
            h.fetch_add(1, Ordering::SeqCst);
        }));
        m.add_signed_in(user('a', "ta"), auth_file("a"), true, true);
        assert_eq!(hits.load(Ordering::SeqCst), 1);
        m.remove_listener(id);
        m.remove(&uuid('a'));
        assert_eq!(hits.load(Ordering::SeqCst), 1);
    }

    #[test]
    fn an_unknown_account_cannot_become_the_default() {
        let fx = Fixture::new();
        let m = fx.inline();
        m.add_signed_in(user('a', "ta"), auth_file("a"), true, true);
        assert!(!m.set_default(Some(&uuid('f'))));
        assert_eq!(m.default_uuid(), Some(uuid('a')));
    }

    #[test]
    fn clearing_the_default_keeps_every_account() {
        let fx = Fixture::new();
        let m = fx.inline();
        m.add_signed_in(user('a', "ta"), auth_file("a"), true, true);
        assert!(m.set_default(None));
        assert!(!m.has_default());
        assert_eq!(m.accounts().len(), 1);
        assert!(!fx.inline().has_default());
    }

    #[test]
    fn removing_the_default_promotes_the_most_recently_used_account() {
        let fx = Fixture::new();
        let m = fx.inline();
        m.add_signed_in(user('a', "ta"), auth_file("a"), true, true);
        fx.clock.advance(1000);
        m.add_signed_in(user('b', "tb"), auth_file("b"), true, true);
        fx.clock.advance(1000);
        m.add_signed_in(user('c', "tc"), auth_file("c"), true, true);
        fx.clock.advance(1000);
        m.set_default(Some(&uuid('a')));
        assert!(m.remove(&uuid('a')));
        assert_eq!(m.default_uuid(), Some(uuid('c')));
        assert!(fx.store.read_auth_file(&uuid('a')).is_none());
        assert!(!m.remove(&uuid('a')));
    }

    #[test]
    fn resolve_default_only_accepts_a_signed_in_account() {
        let set = ["a", "b"];
        assert_eq!(resolve_default("a", set.iter().copied()), Some("a".into()));
        assert_eq!(resolve_default("z", set.iter().copied()), None);
        assert_eq!(resolve_default("", ["a"].iter().copied()), None);
        assert_eq!(resolve_default("  ", ["a"].iter().copied()), None);
    }

    #[test]
    fn a_fresh_token_is_not_refreshed() {
        let fx = Fixture::new();
        let m = fx.inline();
        m.add_signed_in(user('a', "ta"), auth_file("a"), true, true);
        assert_eq!(token(&get(m.refresh(&uuid('a'), false)).unwrap()), "ta");
        assert_eq!(fx.renewer.calls.load(Ordering::SeqCst), 0);
    }

    #[test]
    fn a_stale_token_is_refreshed_and_persisted() {
        let fx = Fixture::new();
        let m = fx.inline();
        m.add_signed_in(user('a', "old"), auth_file("a"), true, true);
        fx.clock.advance(INTERVAL);
        fx.renewer.script(|_| {
            Ok(Renewal {
                user: user('a', "new"),
                auth_file_gz: auth_file("a-renewed"),
            })
        });
        assert_eq!(token(&get(m.refresh(&uuid('a'), false)).unwrap()), "new");
        assert_eq!(*fx.renewer.last_input.lock(), Some(auth_file("a")));
        assert_eq!(
            fx.store.read_auth_file(&uuid('a')),
            Some(auth_file("a-renewed"))
        );
        assert_eq!(fx.store.read_renewal_ms(&uuid('a')), fx.clock.get());
        assert_eq!(token(&fx.inline().user(&uuid('a')).unwrap()), "new");
    }

    #[test]
    fn one_refresh_per_account_is_in_flight_at_a_time() {
        let fx = Fixture::new();
        let m = fx.manual();
        m.add_signed_in(user('a', "ta"), auth_file("a"), true, true);
        m.add_signed_in(user('b', "tb"), auth_file("b"), true, true);
        fx.clock.advance(INTERVAL);
        let first = m.refresh(&uuid('a'), false);
        let second = m.refresh(&uuid('a'), true);
        let other = m.refresh(&uuid('b'), false);
        assert!(first.ptr_eq(&second));
        assert!(!first.ptr_eq(&other));
        assert_eq!(m.account(&uuid('a')).unwrap().status, Status::Refreshing);
        assert!(m.pending_refresh(&uuid('a')).unwrap().ptr_eq(&first));
        fx.manual.run_queued();
        assert_eq!(fx.renewer.calls.load(Ordering::SeqCst), 2);
        assert!(get(first).is_ok() && get(other).is_ok());
        assert!(m.pending_refresh(&uuid('a')).is_none());
        assert_eq!(m.account(&uuid('a')).unwrap().status, Status::Ready);
    }

    #[test]
    fn rejected_credentials_mean_sign_in_again() {
        let fx = Fixture::new();
        let m = fx.inline();
        m.add_signed_in(user('a', "ta"), auth_file("a"), true, true);
        fx.renewer
            .script(|_| Err(RenewalError::new("invalid credentials", true)));
        let e = get(m.refresh(&uuid('a'), true)).unwrap_err();
        assert!(e.credentials_rejected);
        assert_eq!(m.account(&uuid('a')).unwrap().status, Status::NeedsSignIn);
    }

    #[test]
    fn a_transient_failure_keeps_the_account_usable() {
        let fx = Fixture::new();
        let m = fx.inline();
        m.add_signed_in(user('a', "ta"), auth_file("a"), true, true);
        fx.renewer
            .script(|_| Err(RenewalError::new("timed out", false)));
        assert!(get(m.refresh(&uuid('a'), true)).is_err());
        assert_eq!(m.account(&uuid('a')).unwrap().status, Status::Ready);
        assert_eq!(token(&m.user(&uuid('a')).unwrap()), "ta");
    }

    #[test]
    fn a_rotated_refresh_token_is_kept_when_the_renewal_fails_later() {
        let fx = Fixture::new();
        let m = fx.inline();
        m.add_signed_in(user('a', "ta"), auth_file("a"), true, true);
        fx.renewer.script(|_| {
            Err(RenewalError {
                message: "XblHttp".into(),
                credentials_rejected: false,
                rotated_auth_file: Some(auth_file("a-rotated")),
            })
        });
        let e = get(m.refresh(&uuid('a'), true)).unwrap_err();
        assert!(e.rotated_auth_file.is_none(), "callers never see the bytes");
        assert_eq!(
            fx.store.read_auth_file(&uuid('a')),
            Some(auth_file("a-rotated"))
        );
        assert_eq!(token(&m.user(&uuid('a')).unwrap()), "ta");
    }

    #[test]
    fn a_renewal_that_comes_back_as_another_account_is_rejected() {
        let fx = Fixture::new();
        let m = fx.inline();
        m.add_signed_in(user('a', "ta"), auth_file("a"), true, true);
        fx.renewer.script(|_| {
            Ok(Renewal {
                user: user('b', "tb"),
                auth_file_gz: auth_file("b"),
            })
        });
        assert!(get(m.refresh(&uuid('a'), true)).is_err());
        assert_eq!(token(&m.user(&uuid('a')).unwrap()), "ta");
        assert!(m.account(&uuid('b')).is_none());
    }

    #[test]
    fn a_session_only_account_refreshes_from_memory() {
        let fx = Fixture::new();
        let m = fx.inline();
        m.add_signed_in(user('a', "ta"), auth_file("mem"), false, true);
        fx.renewer.script(|_| {
            Ok(Renewal {
                user: user('a', "ta2"),
                auth_file_gz: auth_file("mem2"),
            })
        });
        assert_eq!(token(&get(m.refresh(&uuid('a'), true)).unwrap()), "ta2");
        assert_eq!(*fx.renewer.last_input.lock(), Some(auth_file("mem")));
        assert!(fx.store.read_auth_file(&uuid('a')).is_none());
    }

    #[test]
    fn refreshing_an_unknown_account_fails() {
        let fx = Fixture::new();
        assert!(get(fx.inline().refresh(&uuid('f'), true)).is_err());
    }

    #[test]
    fn a_missing_auth_file_marks_needs_sign_in() {
        let fx = Fixture::new();
        let m = fx.inline();
        m.add_signed_in(user('a', "ta"), auth_file("a"), true, true);
        std::fs::remove_file(fx.store.root().join(uuid('a')).join(AUTH_FILE)).unwrap();
        let e = get(m.refresh(&uuid('a'), true)).unwrap_err();
        assert!(e.credentials_rejected);
        assert_eq!(m.account(&uuid('a')).unwrap().status, Status::NeedsSignIn);
    }

    #[test]
    fn a_removed_account_stays_removed_when_its_refresh_lands() {
        let fx = Fixture::new();
        let m = fx.manual();
        m.add_signed_in(user('a', "ta"), auth_file("a"), true, true);
        let pending = m.refresh(&uuid('a'), true);
        m.remove(&uuid('a'));
        fx.manual.run_queued();
        assert!(get(pending).is_ok());
        assert!(m.account(&uuid('a')).is_none());
        assert!(fx.store.read_auth_file(&uuid('a')).is_none());
    }

    #[test]
    fn a_removed_and_re_added_account_ignores_the_old_refresh() {
        let fx = Fixture::new();
        let m = fx.manual();
        m.add_signed_in(user('a', "ta"), auth_file("a"), true, true);
        let _pending = m.refresh(&uuid('a'), true);
        m.remove(&uuid('a'));
        m.add_signed_in(user('a', "fresh"), auth_file("a2"), true, true);
        fx.manual.run_queued();
        assert_eq!(token(&m.user(&uuid('a')).unwrap()), "fresh");
        assert_eq!(fx.store.read_auth_file(&uuid('a')), Some(auth_file("a2")));
    }

    #[test]
    fn the_old_active_account_moves_into_the_store_as_the_default() {
        let fx = Fixture::new();
        fx.write_legacy(Some(&user('a', "ta")), &auth_file("a"), T0 - 1000);
        let m = fx.inline();
        assert_eq!(m.default_uuid(), Some(uuid('a')));
        assert_eq!(token(&m.default_user().unwrap()), "ta");
        assert_eq!(m.renewed_at_ms(&uuid('a')), T0 - 1000);
        assert_eq!(fx.store.read_auth_file(&uuid('a')), Some(auth_file("a")));
        assert!(!fx.legacy_remains());
    }

    #[test]
    fn the_old_active_account_overwrites_a_stale_archived_copy() {
        let fx = Fixture::new();
        fx.store
            .write_auth_file(&uuid('a'), &auth_file("stale"))
            .unwrap();
        fx.store.write_meta(&uuid('a'), "Playera", 1).unwrap();
        fx.write_legacy(Some(&user('a', "ta")), &auth_file("current"), T0);
        let m = fx.inline();
        assert_eq!(m.accounts().len(), 1);
        assert_eq!(
            fx.store.read_auth_file(&uuid('a')),
            Some(auth_file("current"))
        );
    }

    #[test]
    fn archived_accounts_sit_alongside_the_migrated_default() {
        let fx = Fixture::new();
        fx.store
            .write_auth_file(&uuid('b'), &auth_file("b"))
            .unwrap();
        fx.store.write_meta(&uuid('b'), "Playerb", 1).unwrap();
        fx.write_legacy(Some(&user('a', "ta")), &auth_file("a"), T0);
        let m = fx.inline();
        let mut ids = uuids(&m.accounts());
        ids.sort();
        assert_eq!(ids, vec![uuid('a'), uuid('b')]);
        assert_eq!(m.default_uuid(), Some(uuid('a')));
    }

    #[test]
    fn migration_runs_once() {
        let fx = Fixture::new();
        fx.write_legacy(Some(&user('a', "ta")), &auth_file("a"), T0);
        fx.inline().accounts();
        let again = fx.inline();
        assert_eq!(again.accounts().len(), 1);
        assert_eq!(again.default_uuid(), Some(uuid('a')));
    }

    #[test]
    fn an_unidentified_old_login_is_identified_by_its_first_renewal() {
        let fx = Fixture::new();
        fx.write_legacy(None, &auth_file("a"), T0);
        fx.renewer.script(|_| {
            Ok(Renewal {
                user: user('a', "ta"),
                auth_file_gz: auth_file("a2"),
            })
        });
        let m = fx.inline();
        assert!(m.has_default());
        assert_eq!(m.default_uuid(), None);
        assert_eq!(get(m.refresh_default()).unwrap().uuid, uuid('a'));
        assert_eq!(m.default_uuid(), Some(uuid('a')));
        assert_eq!(fx.store.read_auth_file(&uuid('a')), Some(auth_file("a2")));
        assert!(!fx.legacy_remains());
    }

    #[test]
    fn the_unidentified_login_renews_only_once_when_asked_twice() {
        let fx = Fixture::new();
        fx.write_legacy(None, &auth_file("a"), T0);
        let m = fx.manual();
        let one = m.refresh_default();
        let two = m.refresh_default();
        assert!(one.ptr_eq(&two));
        fx.manual.run_queued();
        assert_eq!(fx.renewer.calls.load(Ordering::SeqCst), 1);
        assert!(get(one).is_ok());
    }

    #[test]
    fn an_old_login_from_another_machine_is_dropped() {
        let fx = Fixture::new();
        std::fs::write(fx.legacy().join("player.mica"), [9u8, 9, 9]).unwrap();
        let m = fx.with(
            Arc::new(OtherMachine),
            Arc::new(crate::spawn::InlineSpawner),
        );
        assert!(!m.has_default());
        assert!(!fx.legacy().join("player.mica").exists());
    }

    #[test]
    fn a_dropped_refresh_task_frees_the_account_for_the_next_refresh() {
        let fx = Fixture::new();
        let m = fx.manual();
        m.add_signed_in(user('a', "ta"), auth_file("a"), true, true);
        let first = m.refresh(&uuid('a'), true);
        // The runtime shuts down (or the queue closes) before the task runs.
        fx.manual.0.lock().clear();
        assert!(m.pending_refresh(&uuid('a')).is_none());
        assert_eq!(m.account(&uuid('a')).unwrap().status, Status::Ready);
        assert!(get(first.clone()).is_err(), "the abandoned refresh fails");
        let second = m.refresh(&uuid('a'), true);
        assert!(!second.ptr_eq(&first), "a new refresh starts");
        fx.manual.run_queued();
        assert!(get(second).is_ok());
        assert_eq!(m.account(&uuid('a')).unwrap().status, Status::Ready);
    }

    #[test]
    fn a_dropped_legacy_renewal_can_be_started_again() {
        let fx = Fixture::new();
        fx.write_legacy(None, &auth_file("a"), T0);
        let m = fx.manual();
        let first = m.refresh_default();
        fx.manual.0.lock().clear();
        let second = m.refresh_default();
        assert!(!second.ptr_eq(&first));
        fx.manual.run_queued();
        assert_eq!(get(second).unwrap().uuid, uuid('a'));
    }

    #[test]
    fn a_cached_user_naming_another_account_is_not_that_folders_identity() {
        let fx = Fixture::new();
        fx.store
            .write_auth_file(&uuid('a'), &auth_file("a"))
            .unwrap();
        // b's cached user copied into a's folder.
        let b_json = format!(
            "{{\"uuid\":\"{}\",\"name\":\"Playerb\",\"accessToken\":\"tb\"}}",
            uuid('b')
        );
        std::fs::write(
            fx.store.root().join(uuid('a')).join(CACHED_USER_FILE),
            mmcl_secure::cipher::encode_base64(&FakeCipher.encrypt(b_json.as_bytes()).unwrap()),
        )
        .unwrap();
        let m = fx.inline();
        assert!(m.account(&uuid('a')).is_some());
        assert!(m.user(&uuid('a')).is_none());
        assert!(m.is_refresh_due(&uuid('a')));
    }

    #[test]
    fn refresh_default_with_no_account_fails_as_login_expired() {
        let fx = Fixture::new();
        let e = get(fx.inline().refresh_default()).unwrap_err();
        assert!(e.credentials_rejected);
    }
}
