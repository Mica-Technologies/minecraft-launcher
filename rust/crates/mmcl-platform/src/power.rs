// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Power state and the battery download throttle (`PowerStateManager`).
//!
//! The probe (`starship-battery`) is cached for 5 s so the per-chunk hook stays cheap. Only
//! internal batteries count (**Guard:** Windows reports a plugged-in UPS at 100% as "not on AC
//! line"), and the machine is on battery only when every internal battery is actively
//! discharging (**Guard:** a full battery briefly reports "not on line" while plugged in).
//! The throttle math is the closed-loop deadline core in [`mmcl_util::throttle`].

use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, Instant};

use parking_lot::Mutex;

pub use mmcl_util::throttle::{BATTERY_THROTTLE_BYTES_PER_SEC, BATTERY_THROTTLE_PCT_THRESHOLD};

/// How long one probe result is reused.
pub const PROBE_TTL: Duration = Duration::from_secs(5);

/// Name fragments (uppercased) that mark an external UPS rather than an internal battery.
pub const UPS_MARKERS: [&str; 7] = [
    "UPS",
    "APC ",
    "CYBERPOWER",
    "TRIPP LITE",
    "TRIPPLITE",
    "EATON",
    "MINUTEMAN",
];

/// One power source as the probe saw it.
#[derive(Debug, Clone, PartialEq)]
pub struct Source {
    /// Model and vendor strings, if the OS reports them.
    pub model: Option<String>,
    pub vendor: Option<String>,
    /// `true` only while the source is actively discharging.
    pub discharging: bool,
    /// State of charge, `0.0..=1.0`.
    pub charge: f32,
}

/// The evaluated power state.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct PowerState {
    pub on_battery: bool,
    /// Battery percentage of the first internal battery, `0..=100`, or -1 when unknown.
    pub percent: i32,
}

impl PowerState {
    /// "Not on battery, unknown percentage": what a failed probe reports.
    pub const UNKNOWN: PowerState = PowerState {
        on_battery: false,
        percent: -1,
    };
}

/// Whether a source with this model and vendor is an external UPS.
pub fn is_external_ups(model: Option<&str>, vendor: Option<&str>) -> bool {
    [model, vendor].into_iter().flatten().any(|s| {
        let up = s.to_uppercase();
        UPS_MARKERS.iter().any(|m| up.contains(m))
    })
}

/// Evaluates the sources: UPS units are skipped; on battery when at least one internal battery
/// exists and all of them are discharging.
pub fn evaluate(sources: &[Source]) -> PowerState {
    let internal: Vec<&Source> = sources
        .iter()
        .filter(|s| !is_external_ups(s.model.as_deref(), s.vendor.as_deref()))
        .collect();
    let on_battery = !internal.is_empty() && internal.iter().all(|s| s.discharging);
    let percent = internal
        .first()
        .map(|s| (s.charge.clamp(0.0, 1.0) * 100.0).round() as i32)
        .unwrap_or(-1);
    PowerState {
        on_battery,
        percent,
    }
}

/// Whether downloads should be throttled. Pure: `setting` is `batteryThrottleEnable` (`None`
/// while the config is not ready, which counts as on). Checks `on_battery` **first** so the
/// common plugged-in case never touches the config (**Guard:** reading the synchronized config
/// on every chunk convoyed the download pool).
pub fn should_throttle(state: PowerState, setting: impl FnOnce() -> Option<bool>) -> bool {
    if !state.on_battery {
        return false;
    }
    if !setting().unwrap_or(true) {
        return false;
    }
    state.percent < 0 || state.percent < BATTERY_THROTTLE_PCT_THRESHOLD
}

fn probe() -> Result<Vec<Source>, String> {
    let manager = starship_battery::Manager::new().map_err(|e| e.to_string())?;
    let batteries = manager.batteries().map_err(|e| e.to_string())?;
    let mut out = Vec::new();
    for b in batteries {
        let b = b.map_err(|e| e.to_string())?;
        out.push(Source {
            model: b.model().map(str::to_string),
            vendor: b.vendor().map(str::to_string),
            discharging: b.state() == starship_battery::State::Discharging,
            charge: b.state_of_charge().value,
        });
    }
    Ok(out)
}

static CACHE: Mutex<Option<(Instant, PowerState)>> = Mutex::new(None);
static THROTTLED: AtomicBool = AtomicBool::new(false);

/// The current power state (cached for [`PROBE_TTL`]). A probe failure logs
/// `log.powerState.probeFailed` and reports [`PowerState::UNKNOWN`].
pub fn state() -> PowerState {
    let mut cache = CACHE.lock();
    if let Some((at, s)) = *cache
        && at.elapsed() < PROBE_TTL
    {
        return s;
    }
    let s = match crate::guard(|| Some(probe())) {
        Some(Ok(sources)) => evaluate(&sources),
        Some(Err(e)) => {
            tracing::warn!("{}", mmcl_i18n::tf!("log.powerState.probeFailed", e));
            PowerState::UNKNOWN
        }
        None => PowerState::UNKNOWN,
    };
    *cache = Some((Instant::now(), s));
    s
}

/// Whether the machine runs on battery.
pub fn is_on_battery() -> bool {
    state().on_battery
}

/// The battery percentage, or -1.
pub fn battery_percentage() -> i32 {
    state().percent
}

/// Whether downloads are throttled right now (see [`should_throttle`]).
pub fn should_throttle_downloads() -> bool {
    should_throttle(state(), || {
        crate::host::host().and_then(|h| h.battery_throttle_enabled())
    })
}

/// The per-chunk hook: sleeps the calling download thread as needed to hold it at
/// [`BATTERY_THROTTLE_BYTES_PER_SEC`] while throttling. Logs once when the throttle engages.
pub fn maybe_throttle(chunk: usize) {
    if chunk == 0 || !should_throttle_downloads() {
        return;
    }
    if !THROTTLED.swap(true, Ordering::SeqCst) {
        tracing::info!(
            "{}",
            mmcl_i18n::tf!(
                "log.powerState.throttleEngaged",
                BATTERY_THROTTLE_BYTES_PER_SEC.to_string()
            )
        );
    }
    mmcl_util::throttle::throttle_current_thread(chunk, BATTERY_THROTTLE_BYTES_PER_SEC);
}

/// Whether the throttle engaged at any point this session (for UI).
pub fn was_throttled_this_session() -> bool {
    THROTTLED.load(Ordering::SeqCst)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn src(model: &str, discharging: bool, charge: f32) -> Source {
        Source {
            model: Some(model.into()),
            vendor: None,
            discharging,
            charge,
        }
    }

    #[test]
    fn ups_units_are_not_batteries() {
        assert!(is_external_ups(Some("Back-UPS ES 700"), None));
        assert!(is_external_ups(None, Some("APC Corp")));
        assert!(is_external_ups(Some("cyberpower cp1500"), None));
        assert!(!is_external_ups(Some("DELL 7FHHV"), Some("SMP")));
        assert!(!is_external_ups(None, None));
        // "APC" without the space is not a marker (e.g. a model code containing it).
        assert!(!is_external_ups(Some("XAPC1"), None));
    }

    #[test]
    fn on_battery_only_when_every_internal_battery_discharges() {
        assert_eq!(
            evaluate(&[]),
            PowerState {
                on_battery: false,
                percent: -1
            }
        );
        assert_eq!(
            evaluate(&[src("Bat", true, 0.25)]),
            PowerState {
                on_battery: true,
                percent: 25
            }
        );
        assert!(!evaluate(&[src("Bat", true, 0.5), src("Bat2", false, 1.0)]).on_battery);
        // A UPS that claims to discharge is ignored, and alone it means "not on battery".
        assert!(!evaluate(&[src("Smart-UPS", true, 1.0)]).on_battery);
        assert!(evaluate(&[src("Smart-UPS", false, 1.0), src("Bat", true, 0.9)]).on_battery);
    }

    #[test]
    fn throttle_gate_checks_battery_before_the_setting() {
        let plugged = PowerState {
            on_battery: false,
            percent: 10,
        };
        assert!(!should_throttle(plugged, || panic!(
            "config must not be read when plugged in"
        )));
        let low = PowerState {
            on_battery: true,
            percent: 10,
        };
        assert!(should_throttle(low, || None));
        assert!(should_throttle(low, || Some(true)));
        assert!(!should_throttle(low, || Some(false)));
        let high = PowerState {
            on_battery: true,
            percent: 80,
        };
        assert!(!should_throttle(high, || Some(true)));
        let unknown = PowerState {
            on_battery: true,
            percent: -1,
        };
        assert!(should_throttle(unknown, || Some(true)));
    }

    #[test]
    fn the_probe_never_panics_and_is_cached() {
        let a = state();
        let b = state();
        assert_eq!(a, b);
        maybe_throttle(0);
    }
}
