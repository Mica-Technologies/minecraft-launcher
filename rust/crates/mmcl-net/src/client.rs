// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The two shared HTTP clients (async reqwest over rustls).
//!
//! * **download client:** connect timeout 10 s, idle read timeout 30 s per read (Java's
//!   `setReadTimeout`), no response decompression (so `Content-Length` is the real byte count
//!   progress and caps use; Java sent no `Accept-Encoding`), redirects followed up to 20 hops,
//!   same scheme or http to https only (never https to http).
//! * **bounded client:** the same, but no automatic redirects: bounded fetches walk them by
//!   hand so every hop can be checked for HTTPS.
//!
//! Both carry the generic `MicaMinecraftLauncher/<version>` User-Agent and nothing else that
//! could identify the user. Both are rebuilt by [`reload_proxy`]. Without a proxy the clients
//! ignore `HTTP_PROXY`-style environment variables, as the Java launcher did.

use std::sync::Arc;
use std::time::Duration;

use arc_swap::ArcSwapOption;
use mmcl_i18n::{t, tf};
use reqwest::redirect;

use crate::error::DownloadError;
use crate::proxy::{self, ProxyConfig};

/// Connect timeout of every request.
pub const CONNECT_TIMEOUT: Duration = Duration::from_secs(10);
/// Idle timeout of each read.
pub const READ_TIMEOUT: Duration = Duration::from_secs(30);
/// Redirect hops the download client follows.
pub const MAX_FOLLOWED_REDIRECTS: usize = 20;

/// The pair of clients for one proxy setting.
#[derive(Clone, Debug)]
pub struct Clients {
    pub(crate) download: reqwest::Client,
    pub(crate) bounded: reqwest::Client,
    proxy: Option<ProxyConfig>,
}

impl Clients {
    /// Builds both clients with `user_agent` and an optional proxy.
    pub fn build(user_agent: &str, proxy: Option<&ProxyConfig>) -> Result<Clients, DownloadError> {
        let make = |policy: redirect::Policy| -> Result<reqwest::Client, DownloadError> {
            let mut b = reqwest::Client::builder()
                .user_agent(user_agent)
                .connect_timeout(CONNECT_TIMEOUT)
                .read_timeout(READ_TIMEOUT)
                .no_gzip()
                .redirect(policy);
            match proxy {
                Some(p) => {
                    let px = reqwest::Proxy::all(p.url())
                        .map_err(|e| DownloadError::Client(e.to_string()))?;
                    b = b.proxy(px);
                }
                None => b = b.no_proxy(),
            }
            b.build().map_err(|e| DownloadError::Client(e.to_string()))
        };
        Ok(Clients {
            download: make(download_redirect_policy())?,
            bounded: make(redirect::Policy::none())?,
            proxy: proxy.cloned(),
        })
    }

    /// The proxy these clients use.
    pub fn proxy(&self) -> Option<&ProxyConfig> {
        self.proxy.as_ref()
    }
}

fn download_redirect_policy() -> redirect::Policy {
    redirect::Policy::custom(|attempt| {
        if attempt.previous().len() >= MAX_FOLLOWED_REDIRECTS {
            return attempt.error("too many redirects");
        }
        // Every hop seen so far, not only the last: an https -> http -> http chain must not
        // pass because the second hop came from plain http.
        let any_https = attempt
            .previous()
            .iter()
            .any(|u| u.scheme().eq_ignore_ascii_case("https"));
        if redirect_allowed(any_https, attempt.url().scheme()) {
            attempt.follow()
        } else {
            attempt.error("refusing a redirect that downgrades HTTPS or leaves http(s)")
        }
    })
}

/// The download client's redirect rule: to https always; to http only while the chain has
/// never been on https; anything else never.
pub(crate) fn redirect_allowed(chain_was_https: bool, to_scheme: &str) -> bool {
    match to_scheme.to_ascii_lowercase().as_str() {
        "https" => true,
        "http" => !chain_was_https,
        _ => false,
    }
}

/// The User-Agent: `MicaMinecraftLauncher/<version>` (`dev` when unstamped). Generic on purpose.
pub fn user_agent() -> String {
    let info = mmcl_core::build::info();
    mmcl_core::AppIdentity::current().user_agent(info.version)
}

static CLIENTS: ArcSwapOption<Clients> = ArcSwapOption::const_empty();

/// The current clients, built from the proxy settings on first use.
pub fn clients() -> Result<Arc<Clients>, DownloadError> {
    if let Some(c) = CLIENTS.load_full() {
        return Ok(c);
    }
    reload_proxy_inner()
}

/// Re-reads the proxy settings and rebuilds both clients. Call after the settings change.
pub fn reload_proxy() {
    if let Err(e) = reload_proxy_inner() {
        mmcl_log::error_silent(&e.to_string());
    }
}

fn reload_proxy_inner() -> Result<Arc<Clients>, DownloadError> {
    let ua = user_agent();
    let proxy = match proxy::current_settings()
        .map_err(proxy::ProxySettingsError)
        .and_then(|s| proxy::resolve(&s))
    {
        Ok(p) => p,
        Err(_) => {
            mmcl_log::warn_silent(&t!("log.network.proxyConfigFailed"));
            None
        }
    };
    let built = match &proxy {
        Some(p) => match Clients::build(&ua, Some(p)) {
            Ok(c) => {
                mmcl_log::std(&tf!(
                    "log.network.proxyConfigured",
                    p.kind.java_name(),
                    p.host.as_str(),
                    p.port.to_string()
                ));
                c
            }
            Err(_) => {
                mmcl_log::warn_silent(&t!("log.network.proxyConfigFailed"));
                Clients::build(&ua, None)?
            }
        },
        None => Clients::build(&ua, None)?,
    };
    let built = Arc::new(built);
    CLIENTS.store(Some(built.clone()));
    Ok(built)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn user_agent_names_the_launcher_only() {
        let ua = user_agent();
        assert!(ua.starts_with("MicaMinecraftLauncher/"), "{ua}");
        assert!(!ua.contains('@'));
        assert_eq!(ua.split('/').count(), 2);
    }

    #[test]
    fn redirects_never_downgrade_https_or_leave_http() {
        assert!(redirect_allowed(false, "http"));
        assert!(redirect_allowed(false, "https"));
        assert!(redirect_allowed(true, "HTTPS"));
        assert!(!redirect_allowed(true, "http"));
        assert!(!redirect_allowed(false, "file"));
        assert!(!redirect_allowed(true, "ftp"));
    }

    #[test]
    fn clients_build_with_and_without_a_proxy() {
        assert!(Clients::build("UA/1", None).is_ok());
        let p = ProxyConfig {
            kind: proxy::ProxyKind::Socks,
            host: "127.0.0.1".into(),
            port: 1080,
        };
        let c = Clients::build("UA/1", Some(&p)).unwrap();
        assert_eq!(c.proxy(), Some(&p));
    }
}
