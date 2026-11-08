// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Proxy settings (`proxyEnable`, `proxyHost`, `proxyPort`, `proxyType` in the config).
//!
//! This crate sits below the config store, so the app registers a provider with
//! [`set_settings_provider`]; settings are read lazily on first use and again on
//! [`crate::reload_proxy`]. Only `http(s)` sources use the proxy (**Guard:** in Java the `jar:`
//! handler threw with a proxy set, which broke every embedded Forge library for proxy users).

use std::sync::Arc;

use parking_lot::RwLock;

/// The proxy part of the launcher config.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct ProxySettings {
    pub enabled: bool,
    pub host: String,
    pub port: i64,
    /// `"SOCKS"` (any case) or anything else for HTTP.
    pub kind: String,
}

/// Which protocol the proxy speaks.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ProxyKind {
    Http,
    Socks,
}

impl ProxyKind {
    /// Java's `Proxy.Type` name, as logged.
    pub fn java_name(self) -> &'static str {
        match self {
            ProxyKind::Http => "HTTP",
            ProxyKind::Socks => "SOCKS",
        }
    }
}

/// A resolved proxy, ready for the HTTP client.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ProxyConfig {
    pub kind: ProxyKind,
    pub host: String,
    pub port: u16,
}

impl ProxyConfig {
    /// The proxy URL handed to reqwest. SOCKS uses `socks5h` so host names are resolved by the
    /// proxy (as Java's SOCKS socket does for unresolved addresses), not leaked to local DNS.
    pub fn url(&self) -> String {
        let host = if self.host.contains(':') && !self.host.starts_with('[') {
            format!("[{}]", self.host)
        } else {
            self.host.clone()
        };
        match self.kind {
            ProxyKind::Socks => format!("socks5h://{host}:{}", self.port),
            ProxyKind::Http => format!("http://{host}:{}", self.port),
        }
    }
}

/// Why the settings could not be turned into a proxy (logged as `log.network.proxyConfigFailed`).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ProxySettingsError(pub String);

/// Resolves settings: disabled or a blank host is no proxy; an out-of-range port is an error.
pub fn resolve(s: &ProxySettings) -> Result<Option<ProxyConfig>, ProxySettingsError> {
    let host = s.host.trim();
    if !s.enabled || host.is_empty() {
        return Ok(None);
    }
    let port = u16::try_from(s.port)
        .map_err(|_| ProxySettingsError(format!("port out of range: {}", s.port)))?;
    let kind = if s.kind.trim().eq_ignore_ascii_case("SOCKS") {
        ProxyKind::Socks
    } else {
        ProxyKind::Http
    };
    Ok(Some(ProxyConfig {
        kind,
        host: host.to_string(),
        port,
    }))
}

type Provider = Arc<dyn Fn() -> Result<ProxySettings, String> + Send + Sync>;

static PROVIDER: RwLock<Option<Provider>> = RwLock::new(None);

/// Registers where proxy settings come from (the app passes a closure over its config store).
/// Takes effect at the next [`crate::reload_proxy`] or first client use.
pub fn set_settings_provider(
    f: impl Fn() -> Result<ProxySettings, String> + Send + Sync + 'static,
) {
    *PROVIDER.write() = Some(Arc::new(f));
}

/// Removes the provider (no proxy from then on, after a reload).
pub fn clear_settings_provider() {
    *PROVIDER.write() = None;
}

/// Reads the current settings. No provider means no proxy.
pub(crate) fn current_settings() -> Result<ProxySettings, String> {
    let p = PROVIDER.read().clone();
    match p {
        Some(f) => f(),
        None => Ok(ProxySettings::default()),
    }
}

/// The proxy the current settings resolve to, for clients built outside this crate (sign-in).
/// `None` when no proxy is set or the settings are invalid.
pub fn current_proxy() -> Option<ProxyConfig> {
    current_settings()
        .ok()
        .and_then(|s| resolve(&s).ok())
        .flatten()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn s(enabled: bool, host: &str, port: i64, kind: &str) -> ProxySettings {
        ProxySettings {
            enabled,
            host: host.into(),
            port,
            kind: kind.into(),
        }
    }

    #[test]
    fn disabled_or_blank_host_is_no_proxy() {
        assert_eq!(resolve(&s(false, "proxy", 8080, "HTTP")), Ok(None));
        assert_eq!(resolve(&s(true, "  ", 8080, "HTTP")), Ok(None));
    }

    #[test]
    fn socks_in_any_case_gives_socks_and_anything_else_http() {
        let socks = resolve(&s(true, "proxy.lan", 1080, "socks"))
            .unwrap()
            .unwrap();
        assert_eq!(socks.kind, ProxyKind::Socks);
        assert_eq!(socks.url(), "socks5h://proxy.lan:1080");
        let http = resolve(&s(true, "proxy.lan", 3128, "whatever"))
            .unwrap()
            .unwrap();
        assert_eq!(http.url(), "http://proxy.lan:3128");
    }

    #[test]
    fn ipv6_hosts_are_bracketed_and_bad_ports_fail() {
        let v6 = resolve(&s(true, "::1", 3128, "HTTP")).unwrap().unwrap();
        assert_eq!(v6.url(), "http://[::1]:3128");
        assert!(resolve(&s(true, "proxy", 70000, "HTTP")).is_err());
        assert!(resolve(&s(true, "proxy", -1, "HTTP")).is_err());
    }
}
