// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Play-time label buckets. They return a localization key and an amount, so the bucket
//! boundaries and plurals are testable without asserting on English text; the UI formats
//! the key with the amount (`{0}`).

const MINUTE: i64 = 60_000;
const HOUR: i64 = 3_600_000;
const DAY: i64 = 86_400_000;

/// A localization key plus the number to format into it.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Label {
    pub key: &'static str,
    pub amount: f64,
}

impl Label {
    fn new(key: &'static str, amount: f64) -> Self {
        Label { key, amount }
    }

    /// True for the keys that format the amount as a whole number (every key except the
    /// fractional total-play-time hours and days).
    pub fn is_integral(&self) -> bool {
        !matches!(
            self.key,
            "gameModPackMetadata.totalPlayTime.hours" | "gameModPackMetadata.totalPlayTime.days"
        )
    }
}

/// "Last played" label for a timestamp. `0` means never played; anything under a minute
/// ago, including a timestamp in the future, is "just now".
pub fn last_played(last_played_ms: i64, now_ms: i64) -> Label {
    if last_played_ms == 0 {
        return Label::new("metadata.lastPlayed.never", 0.0);
    }
    let elapsed = now_ms.saturating_sub(last_played_ms);
    if elapsed < MINUTE {
        return Label::new("metadata.lastPlayed.justNow", 0.0);
    }
    if elapsed < HOUR {
        let mins = elapsed / MINUTE;
        let key = if mins == 1 {
            "metadata.lastPlayed.minuteAgo"
        } else {
            "metadata.lastPlayed.minutesAgo"
        };
        return Label::new(key, mins as f64);
    }
    if elapsed < DAY {
        let hours = elapsed / HOUR;
        let key = if hours == 1 {
            "metadata.lastPlayed.hourAgo"
        } else {
            "metadata.lastPlayed.hoursAgo"
        };
        return Label::new(key, hours as f64);
    }
    let days = elapsed / DAY;
    let key = if days == 1 {
        "metadata.lastPlayed.dayAgo"
    } else {
        "metadata.lastPlayed.daysAgo"
    };
    Label::new(key, days as f64)
}

/// "Total play time" label. Under an hour counts whole minutes (a non-zero total under a
/// minute is "0 minutes" through the plural key, not the zero key); hours and days keep
/// their fraction.
pub fn total_play_time(total_ms: i64) -> Label {
    if total_ms == 0 {
        return Label::new("gameModPackMetadata.totalPlayTime.zero", 0.0);
    }
    let minutes = total_ms / MINUTE;
    if minutes < 60 {
        let key = if minutes == 1 {
            "gameModPackMetadata.totalPlayTime.minute"
        } else {
            "gameModPackMetadata.totalPlayTime.minutes"
        };
        return Label::new(key, minutes as f64);
    }
    let hours = minutes as f64 / 60.0;
    if hours < 24.0 {
        return Label::new("gameModPackMetadata.totalPlayTime.hours", hours);
    }
    Label::new("gameModPackMetadata.totalPlayTime.days", hours / 24.0)
}

#[cfg(test)]
mod tests {
    use super::*;

    const NOW: i64 = 1_700_000_000_000;

    fn key(ago: i64) -> &'static str {
        last_played(NOW - ago, NOW).key
    }

    fn amount(ago: i64) -> f64 {
        last_played(NOW - ago, NOW).amount
    }

    #[test]
    fn never_played() {
        assert_eq!(last_played(0, NOW).key, "metadata.lastPlayed.never");
    }

    #[test]
    fn last_played_buckets() {
        assert_eq!(key(0), "metadata.lastPlayed.justNow");
        assert_eq!(key(MINUTE - 1), "metadata.lastPlayed.justNow");
        assert_eq!(key(MINUTE), "metadata.lastPlayed.minuteAgo");
        assert_eq!(amount(MINUTE), 1.0);
        assert_eq!(key(2 * MINUTE), "metadata.lastPlayed.minutesAgo");
        assert_eq!(amount(2 * MINUTE), 2.0);
        assert_eq!(key(HOUR - 1), "metadata.lastPlayed.minutesAgo");
        assert_eq!(amount(HOUR - 1), 59.0);
        assert_eq!(key(HOUR), "metadata.lastPlayed.hourAgo");
        assert_eq!(amount(HOUR), 1.0);
        assert_eq!(key(5 * HOUR), "metadata.lastPlayed.hoursAgo");
        assert_eq!(amount(5 * HOUR), 5.0);
        assert_eq!(key(DAY - 1), "metadata.lastPlayed.hoursAgo");
        assert_eq!(amount(DAY - 1), 23.0);
        assert_eq!(key(DAY), "metadata.lastPlayed.dayAgo");
        assert_eq!(amount(DAY), 1.0);
        assert_eq!(key(400 * DAY), "metadata.lastPlayed.daysAgo");
        assert_eq!(amount(400 * DAY), 400.0);
    }

    #[test]
    fn future_timestamp_is_just_now() {
        assert_eq!(
            last_played(NOW + DAY, NOW).key,
            "metadata.lastPlayed.justNow"
        );
    }

    #[test]
    fn total_play_time_buckets() {
        assert_eq!(
            total_play_time(0).key,
            "gameModPackMetadata.totalPlayTime.zero"
        );
        let sub = total_play_time(30_000);
        assert_eq!(sub.key, "gameModPackMetadata.totalPlayTime.minutes");
        assert_eq!(sub.amount, 0.0);
        assert_eq!(
            total_play_time(MINUTE).key,
            "gameModPackMetadata.totalPlayTime.minute"
        );
        let several = total_play_time(45 * MINUTE);
        assert_eq!(several.key, "gameModPackMetadata.totalPlayTime.minutes");
        assert_eq!(several.amount, 45.0);
        assert_eq!(
            total_play_time(HOUR - 1).key,
            "gameModPackMetadata.totalPlayTime.minutes"
        );
        let hour = total_play_time(HOUR);
        assert_eq!(hour.key, "gameModPackMetadata.totalPlayTime.hours");
        assert_eq!(hour.amount, 1.0);
        assert!(!hour.is_integral());
        assert_eq!(total_play_time(3 * HOUR + 30 * MINUTE).amount, 3.5);
        assert_eq!(
            total_play_time(DAY - MINUTE).key,
            "gameModPackMetadata.totalPlayTime.hours"
        );
        let day = total_play_time(DAY);
        assert_eq!(day.key, "gameModPackMetadata.totalPlayTime.days");
        assert_eq!(day.amount, 1.0);
        assert_eq!(total_play_time(2 * DAY + 12 * HOUR).amount, 2.5);
        assert!(several.is_integral());
    }
}
