// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! MCP sessions (Java `McpSession`, `McpSessionRegistry`).
//!
//! A session is one client handshake. It holds no credential: only the launcher-assigned id,
//! timestamps, a call counter and the client's self-reported (untrusted) name and version. The
//! client name is half of every session grant key, so only the first handshake may set it.

use std::sync::Arc;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::Duration;

use indexmap::IndexMap;
use parking_lot::Mutex;
use rand::RngCore;

use crate::tools::CallContext;

/// At most this many sessions live at once; a newcomer evicts the least recently active.
pub const MAX_SESSIONS: usize = 8;
/// A session idle this long is gone.
pub const IDLE_TIMEOUT: Duration = Duration::from_secs(30 * 60);
/// [`IDLE_TIMEOUT`] in milliseconds.
pub const IDLE_TIMEOUT_MS: u64 = 30 * 60 * 1000;

#[derive(Default)]
struct Identity {
    client_name: String,
    client_version: String,
    initialized: bool,
}

/// One client session.
pub struct Session {
    id: String,
    connected_at_ms: u64,
    call_count: AtomicU64,
    last_activity_ms: AtomicU64,
    identity: Mutex<Identity>,
}

impl std::fmt::Debug for Session {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Session")
            .field("id", &self.id)
            .field("client_name", &self.client_name())
            .finish_non_exhaustive()
    }
}

impl Session {
    /// A new, not yet initialized session.
    pub fn new(id: impl Into<String>, now_ms: u64) -> Session {
        Session {
            id: id.into(),
            connected_at_ms: now_ms,
            call_count: AtomicU64::new(0),
            last_activity_ms: AtomicU64::new(now_ms),
            identity: Mutex::new(Identity::default()),
        }
    }

    pub fn id(&self) -> &str {
        &self.id
    }

    pub fn connected_at_ms(&self) -> u64 {
        self.connected_at_ms
    }

    pub fn call_count(&self) -> u64 {
        self.call_count.load(Ordering::Relaxed)
    }

    pub fn last_activity_ms(&self) -> u64 {
        self.last_activity_ms.load(Ordering::Relaxed)
    }

    /// `""` before the handshake.
    pub fn client_name(&self) -> String {
        self.identity.lock().client_name.clone()
    }

    /// `""` before the handshake.
    pub fn client_version(&self) -> String {
        self.identity.lock().client_version.clone()
    }

    pub fn is_initialized(&self) -> bool {
        self.identity.lock().initialized
    }

    /// Records the client's identity. Only the first call takes effect (`true`); a later
    /// `initialize` cannot rename the client and so cannot pick up another client's grants.
    pub fn initialize(&self, name: &str, version: &str) -> bool {
        let mut id = self.identity.lock();
        if id.initialized {
            return false;
        }
        id.client_name = name.to_string();
        id.client_version = version.to_string();
        id.initialized = true;
        true
    }

    /// Marks activity; a tool call also counts.
    pub fn record_activity(&self, now_ms: u64, was_tool_call: bool) {
        self.last_activity_ms.store(now_ms, Ordering::Relaxed);
        if was_tool_call {
            self.call_count.fetch_add(1, Ordering::Relaxed);
        }
    }

    /// What tool code is told about the caller.
    pub fn call_context(&self) -> CallContext {
        CallContext::new(&self.client_name(), &self.id)
    }

    /// A copy for the Settings page.
    pub fn snapshot(&self) -> SessionSnapshot {
        let id = self.identity.lock();
        SessionSnapshot {
            id: self.id.clone(),
            client_name: id.client_name.clone(),
            client_version: id.client_version.clone(),
            initialized: id.initialized,
            connected_at_ms: self.connected_at_ms,
            last_activity_ms: self.last_activity_ms(),
            call_count: self.call_count(),
        }
    }

    fn is_idle(&self, now_ms: u64) -> bool {
        now_ms.saturating_sub(self.last_activity_ms()) >= IDLE_TIMEOUT_MS
    }
}

/// A point-in-time copy of a session.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct SessionSnapshot {
    pub id: String,
    pub client_name: String,
    pub client_version: String,
    pub initialized: bool,
    pub connected_at_ms: u64,
    pub last_activity_ms: u64,
    pub call_count: u64,
}

/// The live sessions.
#[derive(Default)]
pub struct SessionRegistry {
    sessions: Mutex<IndexMap<String, Arc<Session>>>,
}

/// 16 CSPRNG bytes as 32 lower-case hex characters: unguessable, so one client cannot adopt
/// another's handshake.
pub fn new_session_id() -> String {
    let mut b = [0u8; 16];
    rand::rng().fill_bytes(&mut b);
    hex::encode(b)
}

impl SessionRegistry {
    pub fn new() -> SessionRegistry {
        SessionRegistry::default()
    }

    /// Registers a new session. Idle sessions are swept first; then, while the registry is
    /// full, the least recently active one is evicted (it gets 404 and re-initializes) rather
    /// than refusing the newcomer.
    pub fn create(&self, now_ms: u64) -> Arc<Session> {
        let mut map = self.sessions.lock();
        map.retain(|_, s| !s.is_idle(now_ms));
        while map.len() >= MAX_SESSIONS {
            let oldest = map
                .iter()
                .min_by_key(|(_, s)| s.last_activity_ms())
                .map(|(k, _)| k.clone());
            match oldest {
                Some(k) => {
                    map.shift_remove(&k);
                }
                None => break,
            }
        }
        let mut id = new_session_id();
        while map.contains_key(&id) {
            id = new_session_id();
        }
        let s = Arc::new(Session::new(id.clone(), now_ms));
        map.insert(id, s.clone());
        s
    }

    /// The session with `id`, idle or not.
    pub fn find(&self, id: &str) -> Option<Arc<Session>> {
        self.sessions.lock().get(id).cloned()
    }

    /// The session with `id` unless it has gone idle, in which case it is removed.
    pub fn find_live(&self, id: &str, now_ms: u64) -> Option<Arc<Session>> {
        let mut map = self.sessions.lock();
        let s = map.get(id)?.clone();
        if s.is_idle(now_ms) {
            if map.get(id).is_some_and(|cur| Arc::ptr_eq(cur, &s)) {
                map.shift_remove(id);
            }
            return None;
        }
        Some(s)
    }

    /// Ends a session; `true` when one was removed.
    pub fn remove(&self, id: &str) -> bool {
        self.sessions.lock().shift_remove(id).is_some()
    }

    /// Removes idle sessions; returns how many.
    pub fn evict_idle(&self, now_ms: u64) -> usize {
        let mut map = self.sessions.lock();
        let before = map.len();
        map.retain(|_, s| !s.is_idle(now_ms));
        before - map.len()
    }

    pub fn clear(&self) {
        self.sessions.lock().clear();
    }

    /// Every session, oldest registration first.
    pub fn all(&self) -> Vec<Arc<Session>> {
        self.sessions.lock().values().cloned().collect()
    }

    pub fn len(&self) -> usize {
        self.sessions.lock().len()
    }

    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }
}

#[cfg(test)]
mod tests {
    //! Port of `McpSessionRegistryTest`.
    use super::*;

    #[test]
    fn ids_are_32_lower_hex_and_distinct() {
        let r = SessionRegistry::new();
        let a = r.create(0);
        let b = r.create(0);
        assert_ne!(a.id(), b.id());
        for s in [&a, &b] {
            assert_eq!(s.id().len(), 32);
            assert!(
                s.id()
                    .chars()
                    .all(|c| c.is_ascii_hexdigit() && !c.is_ascii_uppercase())
            );
        }
    }

    #[test]
    fn an_idle_session_expires_on_lookup() {
        let r = SessionRegistry::new();
        let s = r.create(1000);
        assert!(r.find_live(s.id(), 1000 + IDLE_TIMEOUT_MS - 1).is_some());
        assert!(r.find_live(s.id(), 1000 + IDLE_TIMEOUT_MS).is_none());
        assert!(r.find(s.id()).is_none());
    }

    #[test]
    fn activity_keeps_a_session_alive() {
        let r = SessionRegistry::new();
        let s = r.create(0);
        s.record_activity(IDLE_TIMEOUT_MS - 10, false);
        assert!(r.find_live(s.id(), IDLE_TIMEOUT_MS + 10).is_some());
    }

    #[test]
    fn evict_idle_removes_only_idle_sessions() {
        let r = SessionRegistry::new();
        let old = r.create(0);
        let fresh = r.create(IDLE_TIMEOUT_MS - 1);
        assert_eq!(r.evict_idle(IDLE_TIMEOUT_MS), 1);
        assert!(r.find(old.id()).is_none());
        assert!(r.find(fresh.id()).is_some());
    }

    #[test]
    fn the_least_recently_active_is_evicted_at_the_cap() {
        let r = SessionRegistry::new();
        let mut all = Vec::new();
        for i in 0..MAX_SESSIONS as u64 {
            all.push(r.create(100 + i));
        }
        all[0].record_activity(500, false);
        let newcomer = r.create(600);
        assert_eq!(r.len(), MAX_SESSIONS);
        assert!(r.find(all[0].id()).is_some(), "recently active survives");
        assert!(
            r.find(all[1].id()).is_none(),
            "least recently active evicted"
        );
        assert!(r.find(newcomer.id()).is_some());
    }

    #[test]
    fn idle_sessions_are_swept_before_an_active_one_is_evicted() {
        let r = SessionRegistry::new();
        let idle = r.create(0);
        let mut active = Vec::new();
        for _ in 1..MAX_SESSIONS {
            active.push(r.create(IDLE_TIMEOUT_MS));
        }
        r.create(IDLE_TIMEOUT_MS + 5);
        assert!(r.find(idle.id()).is_none());
        for a in &active {
            assert!(r.find(a.id()).is_some());
        }
    }

    #[test]
    fn repeated_restarts_never_exceed_the_cap() {
        let r = SessionRegistry::new();
        for i in 0..100 {
            r.create(i);
            assert!(r.len() <= MAX_SESSIONS);
        }
    }

    #[test]
    fn the_first_handshake_sets_identity() {
        let s = Session::new("x", 0);
        assert!(!s.is_initialized());
        assert!(s.initialize("Claude", "1.0"));
        assert!(!s.initialize("Evil", "2.0"));
        assert_eq!(s.client_name(), "Claude");
        assert_eq!(s.client_version(), "1.0");
    }

    #[test]
    fn tool_calls_are_counted() {
        let s = Session::new("x", 0);
        s.record_activity(1, true);
        s.record_activity(2, false);
        s.record_activity(3, true);
        assert_eq!(s.call_count(), 2);
        assert_eq!(s.last_activity_ms(), 3);
    }

    #[test]
    fn remove_and_clear() {
        let r = SessionRegistry::new();
        let s = r.create(0);
        assert!(r.remove(s.id()));
        assert!(!r.remove(s.id()));
        r.create(0);
        r.clear();
        assert!(r.is_empty());
    }

    #[test]
    fn call_context_coalesces_a_blank_name() {
        let s = Session::new("sid", 0);
        let ctx = s.call_context();
        assert_eq!(ctx.client_name, "Unknown client");
        assert_eq!(ctx.session_id, "sid");
    }
}
