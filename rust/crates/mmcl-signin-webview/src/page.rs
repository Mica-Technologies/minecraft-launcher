// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Page-load reporting: the helper tells the parent when Microsoft's page is loading, loaded or
//! could not be reached, so the launcher can show its loading spinner and its "couldn't reach
//! Microsoft's sign-in page, Try again" state (Java `SignInPanel.watchPageLoads`).
//!
//! # Protocol
//!
//! Before the final redirect line the helper may print any number of page lines on stdout:
//!
//! ```text
//! mmcl-signin:loading
//! mmcl-signin:loaded
//! mmcl-signin:load-failed
//! ```
//!
//! A page line never carries a URL. The redirect line (see [`crate::output`]) is unchanged and
//! is still the last line; a parent that only looks for the redirect skips page lines like any
//! other stray output, so the contract stays backwards compatible.
//!
//! With `--watch-stdin`, the parent may write [`RELOAD_COMMAND`] as a line on stdin to load the
//! sign-in page again (the "Try again" button). Closing stdin still means "the parent is gone".
//!
//! # Deciding "loaded" and "load failed"
//!
//! Web engines do not report a failed load the same way (WebView2 finishes the navigation on
//! its own error page), so [`PageLoadTracker`] combines four signals:
//!
//! - the engine's "started" and "finished" page-load events,
//! - a "ready" message the page itself posts once a real `https` document is up (an
//!   initialization script; engine error pages do not run it),
//! - a reachability probe of the sign-in host made when the window opens.
//!
//! "Ready" always means loaded. A "finished" without "ready" is taken as loaded after
//! [`READY_GRACE`] unless the probe found the host unreachable, in which case it is a failure. A
//! load still unfinished after [`LOAD_TIMEOUT`] is a failure, and so is an unreachable host with
//! nothing loaded [`PROBE_GRACE`] after the probe said so.

use std::io::{self, Write};
use std::time::{Duration, Instant};

/// Prefix of every page line on stdout.
pub const PAGE_LINE_PREFIX: &str = "mmcl-signin:";
/// The stdin line that reloads the sign-in page.
pub const RELOAD_COMMAND: &str = "reload";
/// The message the initialization script posts through the IPC channel.
pub const READY_MESSAGE: &str = "mmcl-signin-ready";
/// How long a finished load waits for the page's own "ready" message.
pub const READY_GRACE: Duration = Duration::from_millis(2_000);
/// How long a load may run before it counts as failed.
pub const LOAD_TIMEOUT: Duration = Duration::from_secs(45);
/// How long an unreachable host waits for a page anyway (a proxy may still get through).
pub const PROBE_GRACE: Duration = Duration::from_secs(3);
/// How long the reachability probe tries to connect.
pub const PROBE_TIMEOUT: Duration = Duration::from_secs(8);

/// The initialization script: posts [`READY_MESSAGE`] once an `https` document is up.
pub const READY_SCRIPT: &str = r#"(function () {
  function ready() {
    try {
      if (location.protocol === 'https:' && window.ipc && document.body) {
        window.ipc.postMessage('mmcl-signin-ready');
      }
    } catch (e) {}
  }
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', ready);
  } else {
    ready();
  }
})();"#;

/// What the page is doing.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum PageEvent {
    /// Microsoft's page is loading.
    Loading,
    /// The page is up.
    Loaded,
    /// The page could not be reached.
    LoadFailed,
}

impl PageEvent {
    /// The word after [`PAGE_LINE_PREFIX`].
    pub const fn word(self) -> &'static str {
        match self {
            PageEvent::Loading => "loading",
            PageEvent::Loaded => "loaded",
            PageEvent::LoadFailed => "load-failed",
        }
    }

    /// The stdout line (without the line break).
    pub fn line(self) -> String {
        format!("{PAGE_LINE_PREFIX}{}", self.word())
    }

    /// Parses a page line; `None` for anything else (the redirect, stray output, unknown words
    /// from a newer helper).
    pub fn parse_line(line: &str) -> Option<PageEvent> {
        match line.trim().strip_prefix(PAGE_LINE_PREFIX)? {
            "loading" => Some(PageEvent::Loading),
            "loaded" => Some(PageEvent::Loaded),
            "load-failed" => Some(PageEvent::LoadFailed),
            _ => None,
        }
    }
}

/// Writes one page line and flushes.
pub fn emit_page_event(out: &mut impl Write, event: PageEvent) -> io::Result<()> {
    writeln!(out, "{}", event.line())?;
    out.flush()
}

/// `true` when a stdin line asks for a reload.
pub fn is_reload_command(line: &str) -> bool {
    line.trim().eq_ignore_ascii_case(RELOAD_COMMAND)
}

/// Turns the engine's page-load signals into [`PageEvent`]s, each reported once per change.
/// Pure: the caller passes the time.
#[derive(Clone, Debug, Default)]
pub struct PageLoadTracker {
    /// The last event reported.
    reported: Option<PageEvent>,
    /// When the running load started (none once it finished).
    started_at: Option<Instant>,
    /// When the load finished without the page saying it is ready.
    finished_unready_at: Option<Instant>,
    /// When the probe found the host unreachable.
    unreachable_at: Option<Instant>,
    /// The page posted its ready message for the current load.
    ready: bool,
    /// The redirect was captured: nothing more is reported.
    done: bool,
}

impl PageLoadTracker {
    /// A tracker with nothing reported.
    pub fn new() -> PageLoadTracker {
        PageLoadTracker::default()
    }

    fn report(&mut self, e: PageEvent) -> Option<PageEvent> {
        if self.done || self.reported == Some(e) {
            return None;
        }
        self.reported = Some(e);
        Some(e)
    }

    /// The last event reported.
    pub fn reported(&self) -> Option<PageEvent> {
        self.reported
    }

    /// A main-frame load started (also the initial load and a reload).
    pub fn on_started(&mut self, now: Instant) -> Option<PageEvent> {
        self.started_at = Some(now);
        self.finished_unready_at = None;
        self.ready = false;
        self.report(PageEvent::Loading)
    }

    /// The engine says the load finished (possibly on its own error page).
    pub fn on_finished(&mut self, now: Instant) -> Option<PageEvent> {
        self.started_at = None;
        if self.ready {
            return self.report(PageEvent::Loaded);
        }
        self.finished_unready_at = Some(now);
        None
    }

    /// The page posted its ready message: a real document is up.
    pub fn on_ready(&mut self) -> Option<PageEvent> {
        self.ready = true;
        self.started_at = None;
        self.finished_unready_at = None;
        self.unreachable_at = None;
        self.report(PageEvent::Loaded)
    }

    /// The reachability probe could not connect to the sign-in host.
    pub fn on_unreachable(&mut self, now: Instant) -> Option<PageEvent> {
        if !self.ready && self.reported != Some(PageEvent::Loaded) {
            self.unreachable_at = Some(now);
        }
        None
    }

    /// The redirect was captured; later signals report nothing.
    pub fn on_redirect(&mut self) {
        self.done = true;
    }

    /// A reload was asked for: the probe result no longer applies.
    pub fn on_reload(&mut self) {
        self.unreachable_at = None;
    }

    /// Reports what the passing of time decided (a grace or timeout ran out).
    pub fn poll(&mut self, now: Instant) -> Option<PageEvent> {
        if self.done || self.ready {
            return None;
        }
        if let Some(f) = self.finished_unready_at
            && now.duration_since(f) >= READY_GRACE
        {
            self.finished_unready_at = None;
            return if self.unreachable_at.is_some() {
                self.report(PageEvent::LoadFailed)
            } else {
                self.report(PageEvent::Loaded)
            };
        }
        if let Some(s) = self.started_at
            && now.duration_since(s) >= LOAD_TIMEOUT
        {
            self.started_at = None;
            return self.report(PageEvent::LoadFailed);
        }
        if let Some(u) = self.unreachable_at
            && now.duration_since(u) >= PROBE_GRACE
            && self.finished_unready_at.is_none()
        {
            return self.report(PageEvent::LoadFailed);
        }
        None
    }

    /// The next moment [`PageLoadTracker::poll`] may report something.
    pub fn next_deadline(&self) -> Option<Instant> {
        if self.done || self.ready {
            return None;
        }
        [
            self.finished_unready_at.map(|f| f + READY_GRACE),
            self.started_at.map(|s| s + LOAD_TIMEOUT),
            self.unreachable_at
                .filter(|_| self.finished_unready_at.is_none())
                .filter(|_| self.reported != Some(PageEvent::LoadFailed))
                .map(|u| u + PROBE_GRACE),
        ]
        .into_iter()
        .flatten()
        .min()
    }
}

/// The `host:443` the probe connects to, from the sign-in page address. `None` for an address
/// without a host.
pub fn probe_target(login_url: &str) -> Option<(String, u16)> {
    let u = url::Url::parse(login_url).ok()?;
    let host = u.host_str()?.to_string();
    Some((host, u.port_or_known_default().unwrap_or(443)))
}

/// `true` when a proxy is configured through the environment, so a direct connection says
/// nothing about whether the web view gets through.
pub fn proxy_in_environment(get: impl Fn(&str) -> Option<String>) -> bool {
    ["HTTPS_PROXY", "https_proxy", "ALL_PROXY", "all_proxy"]
        .iter()
        .any(|k| get(k).is_some_and(|v| !v.trim().is_empty()))
}

/// Tries a TCP connection to `host:port` (any resolved address) within `timeout`. Blocking.
pub fn host_reachable(host: &str, port: u16, timeout: Duration) -> bool {
    use std::net::{TcpStream, ToSocketAddrs};
    let Ok(addrs) = (host, port).to_socket_addrs() else {
        return false;
    };
    addrs
        .into_iter()
        .any(|a| TcpStream::connect_timeout(&a, timeout).is_ok())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn page_lines_round_trip_and_never_look_like_the_redirect() {
        for e in [PageEvent::Loading, PageEvent::Loaded, PageEvent::LoadFailed] {
            assert_eq!(PageEvent::parse_line(&e.line()), Some(e));
            assert!(!e.line().starts_with("https://"));
        }
        assert_eq!(
            PageEvent::parse_line("  mmcl-signin:loaded \r"),
            Some(PageEvent::Loaded)
        );
        assert_eq!(PageEvent::parse_line("mmcl-signin:other"), None);
        assert_eq!(
            PageEvent::parse_line("https://login.live.com/oauth20_desktop.srf?code=x"),
            None
        );
        let mut buf = Vec::new();
        emit_page_event(&mut buf, PageEvent::LoadFailed).unwrap();
        assert_eq!(String::from_utf8(buf).unwrap(), "mmcl-signin:load-failed\n");
    }

    #[test]
    fn reload_commands_are_recognised() {
        assert!(is_reload_command("reload\n"));
        assert!(is_reload_command(" RELOAD "));
        assert!(!is_reload_command("reloads"));
    }

    #[test]
    fn a_ready_page_is_loaded_at_once() {
        let t0 = Instant::now();
        let mut t = PageLoadTracker::new();
        assert_eq!(t.on_started(t0), Some(PageEvent::Loading));
        assert_eq!(t.on_started(t0), None, "reported once");
        assert_eq!(t.on_ready(), Some(PageEvent::Loaded));
        assert_eq!(t.on_finished(t0), None);
        assert_eq!(t.poll(t0 + LOAD_TIMEOUT * 2), None);
        assert_eq!(t.next_deadline(), None);
    }

    #[test]
    fn a_finished_load_without_ready_is_loaded_after_the_grace() {
        let t0 = Instant::now();
        let mut t = PageLoadTracker::new();
        t.on_started(t0);
        assert_eq!(t.on_finished(t0), None);
        assert_eq!(t.next_deadline(), Some(t0 + READY_GRACE));
        assert_eq!(t.poll(t0 + READY_GRACE / 2), None);
        assert_eq!(t.poll(t0 + READY_GRACE), Some(PageEvent::Loaded));
    }

    #[test]
    fn an_unreachable_host_turns_the_error_page_into_a_failure() {
        let t0 = Instant::now();
        let mut t = PageLoadTracker::new();
        t.on_started(t0);
        t.on_unreachable(t0);
        t.on_finished(t0 + Duration::from_millis(200));
        assert_eq!(
            t.poll(t0 + Duration::from_millis(200) + READY_GRACE),
            Some(PageEvent::LoadFailed)
        );
        // The page arrives after all (a proxy, a retry by the engine).
        assert_eq!(t.on_ready(), Some(PageEvent::Loaded));
    }

    #[test]
    fn an_unreachable_host_with_no_page_fails_after_the_probe_grace() {
        let t0 = Instant::now();
        let mut t = PageLoadTracker::new();
        t.on_started(t0);
        t.on_unreachable(t0);
        assert_eq!(t.next_deadline(), Some(t0 + PROBE_GRACE));
        assert_eq!(t.poll(t0 + PROBE_GRACE), Some(PageEvent::LoadFailed));
        assert_eq!(t.poll(t0 + PROBE_GRACE * 2), None, "reported once");
        // Try again: a new load starts.
        t.on_reload();
        assert_eq!(t.on_started(t0 + PROBE_GRACE * 3), Some(PageEvent::Loading));
    }

    #[test]
    fn a_load_that_never_finishes_times_out() {
        let t0 = Instant::now();
        let mut t = PageLoadTracker::new();
        t.on_started(t0);
        assert_eq!(t.next_deadline(), Some(t0 + LOAD_TIMEOUT));
        assert_eq!(t.poll(t0 + LOAD_TIMEOUT), Some(PageEvent::LoadFailed));
    }

    #[test]
    fn nothing_is_reported_after_the_redirect() {
        let t0 = Instant::now();
        let mut t = PageLoadTracker::new();
        t.on_redirect();
        assert_eq!(t.on_started(t0), None);
        assert_eq!(t.on_ready(), None);
        assert_eq!(t.poll(t0 + LOAD_TIMEOUT), None);
    }

    #[test]
    fn the_probe_targets_the_login_host() {
        assert_eq!(
            probe_target("https://login.live.com/oauth20_authorize.srf?x=1"),
            Some(("login.live.com".into(), 443))
        );
        assert_eq!(
            probe_target("https://example.test:8443/a"),
            Some(("example.test".into(), 8443))
        );
        assert_eq!(probe_target("not a url"), None);
    }

    #[test]
    fn environment_proxies_skip_the_probe() {
        assert!(!proxy_in_environment(|_| None));
        assert!(proxy_in_environment(
            |k| (k == "https_proxy").then(|| "http://proxy:3128".to_string())
        ));
        assert!(!proxy_in_environment(|_| Some("  ".to_string())));
    }

    #[test]
    fn a_probe_failure_after_the_page_loaded_changes_nothing() {
        let t0 = Instant::now();
        let mut t = PageLoadTracker::new();
        t.on_started(t0);
        t.on_finished(t0);
        assert_eq!(t.poll(t0 + READY_GRACE), Some(PageEvent::Loaded));
        t.on_unreachable(t0 + READY_GRACE);
        assert_eq!(t.next_deadline(), None);
        assert_eq!(t.poll(t0 + READY_GRACE + PROBE_GRACE), None);
    }
}
