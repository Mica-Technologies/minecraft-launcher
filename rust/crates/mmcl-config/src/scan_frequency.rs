// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! How often the pre-launch security scan runs (Java `game.modpack.ScanFrequency`). Stored in
//! the config by enum name.

use std::fmt;

/// Pre-launch scan frequency.
#[derive(Copy, Clone, Debug, PartialEq, Eq, Hash, Default)]
pub enum ScanFrequency {
    /// Scan before every launch.
    EveryTime,
    /// Scan at most once a day, and whenever the manifest changed.
    #[default]
    Daily,
    /// Scan only when the manifest changed.
    OnChangesOnly,
    /// Never scan.
    Disabled,
}

impl ScanFrequency {
    /// The default (`DAILY`).
    pub const DEFAULT: ScanFrequency = ScanFrequency::Daily;

    /// Every value, in Java's declaration order.
    pub const ALL: [ScanFrequency; 4] = [
        ScanFrequency::EveryTime,
        ScanFrequency::Daily,
        ScanFrequency::OnChangesOnly,
        ScanFrequency::Disabled,
    ];

    /// The Java enum name stored in the config.
    pub const fn name(self) -> &'static str {
        match self {
            ScanFrequency::EveryTime => "EVERY_TIME",
            ScanFrequency::Daily => "DAILY",
            ScanFrequency::OnChangesOnly => "ON_CHANGES_ONLY",
            ScanFrequency::Disabled => "DISABLED",
        }
    }

    /// Java `valueOf`: exact (case-sensitive) name, else `None`.
    pub fn from_name(name: &str) -> Option<ScanFrequency> {
        ScanFrequency::ALL.into_iter().find(|f| f.name() == name)
    }

    /// Java `fromNameSafe`: unknown or missing names give [`DEFAULT`](Self::DEFAULT).
    pub fn from_name_safe(name: Option<&str>) -> ScanFrequency {
        name.and_then(ScanFrequency::from_name)
            .unwrap_or(ScanFrequency::DEFAULT)
    }

    /// Localization key of the display label (`scanFrequency.<NAME>`).
    pub fn label_key(self) -> String {
        format!("scanFrequency.{}", self.name())
    }

    /// The localized display label.
    pub fn label(self) -> String {
        let english = match self {
            ScanFrequency::EveryTime => "Every time",
            ScanFrequency::Daily => "Daily (and on changes)",
            ScanFrequency::OnChangesOnly => "On changes only",
            ScanFrequency::Disabled => "Disabled",
        };
        mmcl_i18n::get_or(&self.label_key(), english).into_owned()
    }
}

impl fmt::Display for ScanFrequency {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.name())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn names_round_trip() {
        for f in ScanFrequency::ALL {
            assert_eq!(ScanFrequency::from_name(f.name()), Some(f));
            assert!(mmcl_i18n::english(&f.label_key()).is_some());
        }
        assert_eq!(ScanFrequency::from_name("daily"), None);
        assert_eq!(
            ScanFrequency::from_name_safe(Some("bogus")),
            ScanFrequency::Daily
        );
        assert_eq!(ScanFrequency::from_name_safe(None), ScanFrequency::Daily);
    }
}
