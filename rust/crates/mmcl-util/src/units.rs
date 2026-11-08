// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! `UnitFormatter`: localized byte sizes and transfer rates for the UI.
//!
//! Binary multiples (1 KB = 1024 B). The number uses the UI locale's decimal separator, an
//! exact number of fraction digits and no grouping, and is passed into the message as a
//! pre-formatted string. Not for machine-facing text (MCP output, parsed logs).

use mmcl_i18n::number::NumberSymbols;
use mmcl_i18n::{Localizer, localizer};

const KIB: f64 = 1024.0;
const MIB: f64 = KIB * 1024.0;
const GIB: f64 = MIB * 1024.0;

/// `value` with exactly `decimals` fraction digits, no grouping, in `sym`'s decimal symbol.
pub fn number(value: f64, decimals: usize, sym: &NumberSymbols) -> String {
    let s = format!("{value:.decimals$}");
    if sym.decimal == "." {
        s
    } else {
        s.replace('.', sym.decimal)
    }
}

/// A size such as `512 B`, `84 KB`, `2.4 MB` or `1.25 GB` in the active UI language.
pub fn bytes(n: i64) -> String {
    bytes_with(localizer(), n)
}

/// [`bytes`] with an explicit localizer.
pub fn bytes_with(l: &Localizer, n: i64) -> String {
    let sym = NumberSymbols::for_locale(&l.requested_locale());
    let b = n.max(0) as f64;
    let (key, text) = if b < KIB {
        ("units.bytes", number(b, 0, &sym))
    } else if b < MIB {
        ("units.kilobytes", number(b / KIB, 0, &sym))
    } else if b < GIB {
        ("units.megabytes", number(b / MIB, 1, &sym))
    } else {
        ("units.gigabytes", number(b / GIB, 2, &sym))
    };
    l.format(key, &[text.into()])
}

/// A rate such as `512 B/s`, `84.0 KB/s` or `2.4 MB/s` in the active UI language.
pub fn bytes_per_second(rate: f64) -> String {
    bytes_per_second_with(localizer(), rate)
}

/// [`bytes_per_second`] with an explicit localizer.
pub fn bytes_per_second_with(l: &Localizer, rate: f64) -> String {
    let sym = NumberSymbols::for_locale(&l.requested_locale());
    let r = if rate.is_nan() { 0.0 } else { rate.max(0.0) };
    let (key, text) = if r < KIB {
        ("units.bytesPerSecond", number(r, 0, &sym))
    } else if r < MIB {
        ("units.kilobytesPerSecond", number(r / KIB, 1, &sym))
    } else {
        ("units.megabytesPerSecond", number(r / MIB, 1, &sym))
    };
    l.format(key, &[text.into()])
}

#[cfg(test)]
mod tests {
    use super::*;
    use mmcl_i18n::Locale;

    fn en() -> Localizer {
        Localizer::new(Locale::en_us())
    }

    #[test]
    fn sizes_pick_the_right_unit_and_precision() {
        let l = en();
        assert_eq!(bytes_with(&l, -5), "0 B");
        assert_eq!(bytes_with(&l, 512), "512 B");
        assert_eq!(bytes_with(&l, 1023), "1023 B");
        assert_eq!(bytes_with(&l, 1024), "1 KB");
        assert_eq!(bytes_with(&l, 86_016), "84 KB");
        assert_eq!(bytes_with(&l, 2_516_582), "2.4 MB");
        assert_eq!(bytes_with(&l, 1_342_177_280), "1.25 GB");
        // No grouping even for large numbers.
        assert_eq!(bytes_with(&l, 5_000 * 1024 * 1024 * 1024), "5000.00 GB");
    }

    #[test]
    fn rates_pick_the_right_unit_and_precision() {
        let l = en();
        assert_eq!(bytes_per_second_with(&l, -1.0), "0 B/s");
        assert_eq!(bytes_per_second_with(&l, f64::NAN), "0 B/s");
        assert_eq!(bytes_per_second_with(&l, 512.0), "512 B/s");
        assert_eq!(bytes_per_second_with(&l, 86_016.0), "84.0 KB/s");
        assert_eq!(bytes_per_second_with(&l, 2_516_582.0), "2.4 MB/s");
        assert_eq!(bytes_per_second_with(&l, 5.0 * GIB), "5120.0 MB/s");
    }

    #[test]
    fn decimal_separator_follows_the_locale() {
        let de = NumberSymbols::for_locale(&Locale::parse("de-DE"));
        assert_eq!(number(2.4, 1, &de), "2,4");
        assert_eq!(number(1234.5, 2, &de), "1234,50");
        assert_eq!(number(3.0, 0, &de), "3");
    }
}
