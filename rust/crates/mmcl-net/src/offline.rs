// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The offline latch and the connectivity probe.
//!
//! **Guard:** every successful real request ([`note_network_success`]) clears the latch. A
//! startup probe that failed briefly (Wi-Fi still associating) used to strand the session in
//! cache-only mode while downloads were working.
//!
//! **Guard:** the probe tries several independent hosts and only reports offline when all fail,
//! so one flaky host cannot latch offline mode. Like Java's raw socket, it ignores the HTTP
//! proxy: a proxy-only network reads as offline until the first real request clears it.

use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::time::Duration;

use mmcl_i18n::t;

/// Hosts the probe tries, in order: Mojang's manifest host (the real dependency), then three
/// public anchors (Cloudflare, Google, Quad9).
pub const CONNECTIVITY_PROBE_HOSTS: [(&str, u16); 4] = [
    ("launchermeta.mojang.com", 443),
    ("1.1.1.1", 443),
    ("8.8.8.8", 443),
    ("9.9.9.9", 443),
];

/// Per-host connect timeout of the probe.
pub const CONNECTIVITY_PROBE_TIMEOUT: Duration = Duration::from_secs(3);

/// An offline flag plus a change counter (so front ends can tell a flip happened).
#[derive(Debug, Default)]
pub struct OfflineLatch {
    offline: AtomicBool,
    generation: AtomicU64,
}

impl OfflineLatch {
    pub const fn new() -> Self {
        OfflineLatch {
            offline: AtomicBool::new(false),
            generation: AtomicU64::new(0),
        }
    }

    pub fn is_offline(&self) -> bool {
        self.offline.load(Ordering::SeqCst)
    }

    /// Sets the flag. Returns the previous value.
    pub fn set(&self, offline: bool) -> bool {
        let prev = self.offline.swap(offline, Ordering::SeqCst);
        if prev != offline {
            self.generation.fetch_add(1, Ordering::SeqCst);
        }
        prev
    }

    /// Bumped every time the flag changes value.
    pub fn generation(&self) -> u64 {
        self.generation.load(Ordering::SeqCst)
    }

    /// Clears the flag after a successful request. Returns `true` when it was set (and logs
    /// `log.network.connectivityRestored` then).
    pub fn note_success(&self) -> bool {
        // Cheap read first: this runs after every request.
        if !self.is_offline() {
            return false;
        }
        if self.set(false) {
            mmcl_log::std(&t!("log.network.connectivityRestored"));
            return true;
        }
        false
    }

    /// Probes `hosts` in order with `timeout` each and stops at the first that accepts a TCP
    /// connection. All failing sets the flag and logs `log.network.connectivityCheckFailed`.
    pub async fn probe(&self, hosts: &[(&str, u16)], timeout: Duration) -> bool {
        for (host, port) in hosts {
            let connect = tokio::net::TcpStream::connect((*host, *port));
            if let Ok(Ok(_stream)) = tokio::time::timeout(timeout, connect).await {
                self.set(false);
                return true;
            }
        }
        mmcl_log::warn_silent(&t!("log.network.connectivityCheckFailed"));
        self.set(true);
        false
    }
}

static GLOBAL: OfflineLatch = OfflineLatch::new();

/// The process-wide latch.
pub fn latch() -> &'static OfflineLatch {
    &GLOBAL
}

/// `true` while the launcher is in offline mode.
pub fn is_offline() -> bool {
    GLOBAL.is_offline()
}

/// Sets offline mode (Java `setOfflineMode`).
pub fn set_offline(offline: bool) {
    GLOBAL.set(offline);
}

/// Counter bumped on every offline/online flip (for an engine event).
pub fn offline_generation() -> u64 {
    GLOBAL.generation()
}

/// Called after every successful real request (download, string fetch, bounded fetch, 304).
pub fn note_network_success() {
    GLOBAL.note_success();
}

/// Probes [`CONNECTIVITY_PROBE_HOSTS`] and sets the latch from the result.
pub async fn check_network_availability() -> bool {
    GLOBAL
        .probe(&CONNECTIVITY_PROBE_HOSTS, CONNECTIVITY_PROBE_TIMEOUT)
        .await
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn any_success_clears_the_latch() {
        let l = OfflineLatch::new();
        assert!(!l.note_success());
        l.set(true);
        let g = l.generation();
        assert!(l.note_success());
        assert!(!l.is_offline());
        assert_eq!(l.generation(), g + 1);
    }

    #[tokio::test]
    async fn probe_succeeds_on_a_later_host_when_the_first_fails() {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let port = listener.local_addr().unwrap().port();
        // A port that refuses: bind then drop to get a free one.
        let dead = {
            let l = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
            l.local_addr().unwrap().port()
        };
        let l = OfflineLatch::new();
        l.set(true);
        assert!(
            l.probe(
                &[("127.0.0.1", dead), ("127.0.0.1", port)],
                Duration::from_secs(2)
            )
            .await
        );
        assert!(!l.is_offline());
    }

    #[tokio::test]
    async fn probe_latches_offline_only_when_every_host_fails() {
        let dead = {
            let l = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
            l.local_addr().unwrap().port()
        };
        let l = OfflineLatch::new();
        assert!(
            !l.probe(&[("127.0.0.1", dead)], Duration::from_millis(500))
                .await
        );
        assert!(l.is_offline());
    }
}
