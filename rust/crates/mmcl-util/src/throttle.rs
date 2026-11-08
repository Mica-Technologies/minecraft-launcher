// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The pure core of the battery download throttle (`PowerStateManager`).
//!
//! The probe and the config gate live in `mmcl-platform::power`; this is the closed-loop
//! deadline arithmetic, kept pure so it can be driven by a synthetic clock. It tracks how far
//! ahead of a byte budget a stream is and asks the caller to sleep off the difference, so a
//! sleep that overshoots on battery (common: timers coarsen) is corrected on the next chunk
//! instead of collapsing the rate the way a fixed per-chunk sleep does.

use std::cell::Cell;
use std::time::{Duration, Instant};

/// Throttled rate per download stream, in bytes per second (512 KiB/s).
pub const BATTERY_THROTTLE_BYTES_PER_SEC: i64 = 512 * 1024;

/// Below this charge (percent) downloads are throttled while discharging.
pub const BATTERY_THROTTLE_PCT_THRESHOLD: i32 = 30;

/// A gap longer than this resets the accounting window instead of "catching up".
pub const STALE_WINDOW_NANOS: i64 = 2_000_000_000;

/// Deficits shorter than this are not worth a sleep.
pub const MIN_SLEEP_NANOS: i64 = 12_000_000;

/// Per-stream throttle state: `[window_start_ns, bytes_in_window]`.
pub type ThrottleState = [i64; 2];

/// How long to sleep after reading `chunk` bytes at `now` (ns on a monotonic clock) to hold
/// the stream at `bps`. Updates `state`. Returns 0 when there is no limit or no data.
pub fn next_throttle_sleep_nanos(state: &mut ThrottleState, now: i64, chunk: i64, bps: i64) -> i64 {
    if bps <= 0 || chunk <= 0 {
        return 0;
    }
    if state[0] == 0 || now - state[0] > STALE_WINDOW_NANOS {
        state[0] = now;
        state[1] = 0;
    }
    state[1] += chunk;
    let target = (state[1] as i128 * 1_000_000_000 / bps as i128) as i64;
    let deficit = target - (now - state[0]);
    let sleep = if deficit >= MIN_SLEEP_NANOS {
        deficit
    } else {
        0
    };
    if state[1] >= bps {
        // Project past the advised sleep so the next window starts when reading resumes.
        state[0] = now + sleep;
        state[1] = 0;
    }
    sleep
}

thread_local! {
    static STATE: Cell<ThrottleState> = const { Cell::new([0, 0]) };
}

fn monotonic_nanos() -> i64 {
    static ANCHOR: std::sync::OnceLock<Instant> = std::sync::OnceLock::new();
    let anchor = *ANCHOR.get_or_init(Instant::now);
    // +1 so the very first reading is never the "unset" value 0.
    anchor.elapsed().as_nanos() as i64 + 1
}

/// Sleeps the calling download thread as needed to hold it at `bps` after reading `chunk`
/// bytes. The per-thread state means each stream is limited separately. Call only when the
/// throttle is engaged (see `mmcl_platform::power::should_throttle_downloads`).
pub fn throttle_current_thread(chunk: usize, bps: i64) {
    let mut st = STATE.with(Cell::get);
    let sleep = next_throttle_sleep_nanos(&mut st, monotonic_nanos(), chunk as i64, bps);
    STATE.with(|s| s.set(st));
    if sleep > 0 {
        std::thread::sleep(Duration::from_nanos(sleep as u64));
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const CAP_BPS: i64 = BATTERY_THROTTLE_BYTES_PER_SEC;
    const CHUNK: i64 = 8192;
    const IO_NANOS_PER_CHUNK: i64 = 50_000;

    fn simulate_deadline_rate(total: i64, overshoot: f64) -> f64 {
        let mut st = [0, 0];
        let mut clock = 0i64;
        let mut delivered = 0i64;
        while delivered < total {
            clock += IO_NANOS_PER_CHUNK;
            let sleep = next_throttle_sleep_nanos(&mut st, clock, CHUNK, CAP_BPS);
            clock += (sleep as f64 * overshoot) as i64;
            delivered += CHUNK;
        }
        delivered as f64 / (clock as f64 / 1e9)
    }

    fn simulate_fixed_sleep_rate(total: i64, overshoot: f64) -> f64 {
        let mut clock = 0i64;
        let mut delivered = 0i64;
        while delivered < total {
            clock += IO_NANOS_PER_CHUNK;
            let sleep = CHUNK * 1_000_000_000 / CAP_BPS;
            clock += (sleep as f64 * overshoot) as i64;
            delivered += CHUNK;
        }
        delivered as f64 / (clock as f64 / 1e9)
    }

    #[test]
    fn converges_to_cap_when_sleeps_land_exactly() {
        let rate = simulate_deadline_rate(64 * 1024 * 1024, 1.0);
        assert!(rate <= CAP_BPS as f64 * 1.05, "{rate}");
        assert!(rate >= CAP_BPS as f64 * 0.90, "{rate}");
    }

    #[test]
    fn converges_to_cap_despite_heavy_sleep_overshoot() {
        let rate = simulate_deadline_rate(64 * 1024 * 1024, 5.0);
        assert!(rate >= CAP_BPS as f64 * 0.90, "{rate}");
        assert!(rate <= CAP_BPS as f64 * 1.05, "{rate}");
    }

    #[test]
    fn fixed_sleep_model_collapses_under_overshoot() {
        let fixed = simulate_fixed_sleep_rate(64 * 1024 * 1024, 5.0);
        let deadline = simulate_deadline_rate(64 * 1024 * 1024, 5.0);
        assert!(fixed < CAP_BPS as f64 * 0.40, "{fixed}");
        assert!(deadline > fixed * 2.0);
    }

    #[test]
    fn sub_floor_deficit_does_not_sleep() {
        let mut st = [0, 0];
        assert_eq!(next_throttle_sleep_nanos(&mut st, 1_000, 1, CAP_BPS), 0);
    }

    #[test]
    fn disabled_or_empty_chunk_never_sleeps() {
        let mut st = [0, 0];
        assert_eq!(next_throttle_sleep_nanos(&mut st, 1_000, CHUNK, 0), 0);
        assert_eq!(next_throttle_sleep_nanos(&mut st, 1_000, 0, CAP_BPS), 0);
    }

    #[test]
    fn stale_gap_resets_window_instead_of_catching_up() {
        let mut st = [0, 0];
        next_throttle_sleep_nanos(&mut st, 0, CHUNK, CAP_BPS);
        next_throttle_sleep_nanos(&mut st, 5 * 1_000_000_000, CHUNK, CAP_BPS);
        assert_eq!(st[1], CHUNK);
    }

    #[test]
    fn live_throttle_sleeps_once_ahead_of_budget() {
        let start = Instant::now();
        // 64 KiB at 1 MiB/s should take about 62 ms.
        for _ in 0..8 {
            throttle_current_thread(8192, 1024 * 1024);
        }
        assert!(start.elapsed() >= Duration::from_millis(40));
    }
}
