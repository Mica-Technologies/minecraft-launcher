// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The engine's process-wide network hooks and the offline network seam.
//!
//! - [`install`]: registers the `mmcl-net` proxy settings provider (`proxyEnable`, `proxyHost`,
//!   `proxyPort`, `proxyType` from the config; reloaded on every settings change through
//!   `SideEffects::reload_proxy`) and the battery download throttle (`mmcl-platform`'s power
//!   probe plus the `batteryThrottleEnable` setting), so every download honours both.
//! - [`OfflineHttp`]: a [`PackHttp`] that is always offline and never touches the network, for
//!   tests and for tools that must not reach out.

use std::path::Path;
use std::sync::atomic::{AtomicBool, Ordering};

use futures::future::BoxFuture;
use mmcl_config::Config;
use mmcl_modpack::PackHttp;
use mmcl_net::{BoundedFetchResult, DownloadError, DownloadTracker, ProxySettings};

/// The proxy part of `config`, as `mmcl-net` reads it.
pub fn proxy_settings_of(config: &Config) -> ProxySettings {
    ProxySettings {
        enabled: config.proxy_enable(),
        host: config.proxy_host(),
        port: i64::from(config.proxy_port()),
        kind: config.proxy_type(),
    }
}

/// The battery throttle decision for a power state and the `batteryThrottleEnable` setting:
/// the per-stream cap while throttling, else `None`. The setting is read only on battery.
pub fn throttle_bytes_per_second(
    state: mmcl_platform::power::PowerState,
    setting: impl FnOnce() -> Option<bool>,
) -> Option<i64> {
    mmcl_platform::power::should_throttle(state, setting)
        .then_some(mmcl_platform::power::BATTERY_THROTTLE_BYTES_PER_SEC)
}

/// The throttle policy over the platform power probe (cached 5 s) and the config.
struct BatteryThrottle {
    config: Config,
}

static THROTTLE_LOGGED: AtomicBool = AtomicBool::new(false);

impl mmcl_net::ThrottlePolicy for BatteryThrottle {
    fn bytes_per_second(&self) -> Option<i64> {
        let state = mmcl_platform::power::state();
        let bps = throttle_bytes_per_second(state, || Some(self.config.battery_throttle()))?;
        if !THROTTLE_LOGGED.swap(true, Ordering::SeqCst) {
            mmcl_log::std(&mmcl_i18n::tf!(
                "log.powerState.throttleEngaged",
                bps.to_string()
            ));
        }
        Some(bps)
    }
}

/// Registers the proxy settings provider and the battery throttle policy over `config`, then
/// reloads the proxy. Process-wide: the last engine to call it wins.
pub fn install(config: &Config) {
    let c = config.clone();
    mmcl_net::set_proxy_settings_provider(move || Ok(proxy_settings_of(&c)));
    mmcl_net::reload_proxy();
    mmcl_net::set_throttle_policy(BatteryThrottle {
        config: config.clone(),
    });
}

/// A [`PackHttp`] that is permanently offline: every request fails at once with no I/O, and
/// the connectivity probe answers "offline". Local (`file:`) manifests still load.
#[derive(Clone, Copy, Debug, Default)]
pub struct OfflineHttp;

fn refused(url: &str) -> DownloadError {
    DownloadError::Request {
        url: url.to_string(),
        message: mmcl_i18n::t!("engine.error.offline").into_owned(),
    }
}

impl PackHttp for OfflineHttp {
    fn is_offline(&self) -> bool {
        true
    }

    fn refresh_network_availability(&self) -> BoxFuture<'_, bool> {
        Box::pin(async { false })
    }

    fn get_conditional<'a>(
        &'a self,
        url: &'a str,
        _max_bytes: u64,
        _etag: Option<&'a str>,
        _last_modified: Option<&'a str>,
    ) -> BoxFuture<'a, Result<BoundedFetchResult, DownloadError>> {
        Box::pin(async move { Err(refused(url)) })
    }

    fn get_bounded<'a>(
        &'a self,
        url: &'a str,
        _max_bytes: u64,
    ) -> BoxFuture<'a, Result<String, DownloadError>> {
        Box::pin(async move { Err(refused(url)) })
    }

    fn get_string<'a>(&'a self, url: &'a str) -> BoxFuture<'a, Result<String, DownloadError>> {
        Box::pin(async move { Err(refused(url)) })
    }

    fn download<'a>(
        &'a self,
        url: &'a str,
        _dest: &'a Path,
        _tracker: Option<std::sync::Arc<DownloadTracker>>,
    ) -> BoxFuture<'a, Result<(), DownloadError>> {
        Box::pin(async move { Err(refused(url)) })
    }

    fn download_bounded<'a>(
        &'a self,
        url: &'a str,
        _dest: &'a Path,
        _max_bytes: u64,
    ) -> BoxFuture<'a, Result<(), DownloadError>> {
        Box::pin(async move { Err(refused(url)) })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use mmcl_config::{ConfigStore, Document};
    use mmcl_platform::power::PowerState;

    fn config() -> Config {
        Config::new(ConfigStore::in_memory(Document::new()))
    }

    #[test]
    fn proxy_settings_follow_the_config() {
        let c = config();
        assert_eq!(
            proxy_settings_of(&c),
            ProxySettings {
                enabled: false,
                host: String::new(),
                port: 8080,
                kind: "HTTP".into(),
            }
        );
        c.set_proxy_host("proxy.example");
        c.set_proxy_port(3128);
        c.set_proxy_type("SOCKS");
        c.set_proxy_enable(true);
        let s = proxy_settings_of(&c);
        assert!(s.enabled);
        assert_eq!(s.host, "proxy.example");
        assert_eq!(s.port, 3128);
        let resolved = mmcl_net::proxy::resolve(&s).unwrap().unwrap();
        assert_eq!(resolved.url(), "socks5h://proxy.example:3128");
    }

    #[test]
    fn the_throttle_needs_battery_low_charge_and_the_setting() {
        let on_ac = PowerState {
            on_battery: false,
            percent: 10,
        };
        let low = PowerState {
            on_battery: true,
            percent: 10,
        };
        let full = PowerState {
            on_battery: true,
            percent: 90,
        };
        let mut read = false;
        assert_eq!(
            throttle_bytes_per_second(on_ac, || {
                read = true;
                Some(true)
            }),
            None
        );
        assert!(!read, "the setting is never read on AC power");
        assert_eq!(
            throttle_bytes_per_second(low, || Some(true)),
            Some(mmcl_platform::power::BATTERY_THROTTLE_BYTES_PER_SEC)
        );
        assert_eq!(throttle_bytes_per_second(low, || Some(false)), None);
        assert_eq!(throttle_bytes_per_second(full, || Some(true)), None);
    }

    #[test]
    fn offline_http_never_reaches_the_network() {
        let rt = tokio::runtime::Builder::new_current_thread()
            .build()
            .unwrap();
        let h = OfflineHttp;
        assert!(h.is_offline());
        assert!(!rt.block_on(h.refresh_network_availability()));
        assert!(
            rt.block_on(h.get_bounded("https://example.com/x.json", 10))
                .is_err()
        );
        let d = tempfile::tempdir().unwrap();
        let dest = d.path().join("x");
        assert!(
            rt.block_on(h.download("https://example.com/x", &dest, None))
                .is_err()
        );
        assert!(!dest.exists());
    }
}
