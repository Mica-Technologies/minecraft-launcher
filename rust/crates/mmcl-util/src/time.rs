// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! `TimeUtilities`: the legacy time-of-day greeting (English on purpose, as in Java).

use chrono::{Local, NaiveTime, Timelike};

/// "Good Morning" before noon, "Good Afternoon" until 17:00, "Good Evening" after.
pub fn friendly_greeting() -> &'static str {
    greeting_at(Local::now().time())
}

/// [`friendly_greeting`] for a given local time.
pub fn greeting_at(t: NaiveTime) -> &'static str {
    match t.hour() {
        0..=11 => "Good Morning",
        12..=16 => "Good Afternoon",
        _ => "Good Evening",
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn at(h: u32, m: u32) -> NaiveTime {
        NaiveTime::from_hms_opt(h, m, 0).unwrap()
    }

    #[test]
    fn boundaries() {
        assert_eq!(greeting_at(at(0, 0)), "Good Morning");
        assert_eq!(greeting_at(at(11, 59)), "Good Morning");
        assert_eq!(greeting_at(at(12, 0)), "Good Afternoon");
        assert_eq!(greeting_at(at(16, 59)), "Good Afternoon");
        assert_eq!(greeting_at(at(17, 0)), "Good Evening");
        assert_eq!(greeting_at(at(23, 59)), "Good Evening");
        assert!(!friendly_greeting().is_empty());
    }
}
