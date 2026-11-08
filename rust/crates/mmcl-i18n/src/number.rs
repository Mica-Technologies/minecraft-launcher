// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Locale-aware number formatting matching what Java 26's `NumberFormat.getInstance(locale)`
//! (CLDR data) emits for the launcher's languages.
//!
//! A hand table rather than ICU4X: Java's `DecimalFormat` ignores CLDR's minimum-grouping-digits
//! and Indian grouping, and uses Latin digits for plain `ar`, so the table is both smaller and
//! closer to the Java output. Every entry was checked against JDK 26 output.

use crate::locale::Locale;

/// Separators and signs for one locale.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct NumberSymbols {
    /// Thousands separator.
    pub group: &'static str,
    /// Decimal separator.
    pub decimal: &'static str,
    /// Prefix for negative numbers (`-`, U+2212 for Swedish, LRM + `-` for Arabic).
    pub minus: &'static str,
    /// Text before a percent value (`%` for Turkish).
    pub percent_prefix: &'static str,
    /// Text after a percent value (`%`, NBSP + `%`, ...).
    pub percent_suffix: &'static str,
}

const NBSP: &str = "\u{a0}";
const NNBSP: &str = "\u{202f}";

const fn sym(
    group: &'static str,
    decimal: &'static str,
    minus: &'static str,
    percent_prefix: &'static str,
    percent_suffix: &'static str,
) -> NumberSymbols {
    NumberSymbols {
        group,
        decimal,
        minus,
        percent_prefix,
        percent_suffix,
    }
}

/// English-style symbols; the default for unknown languages and the root locale.
pub const EN: NumberSymbols = sym(",", ".", "-", "", "%");

impl NumberSymbols {
    /// The symbols Java uses for `locale`.
    pub fn for_locale(locale: &Locale) -> NumberSymbols {
        let region = locale.region();
        match (locale.language(), region) {
            ("de" | "it", "CH" | "LI") => sym("'", ".", "-", "", "%"),
            ("de", "AT") => sym(NBSP, ",", "-", "", "\u{a0}%"),
            ("de", _) => sym(".", ",", "-", "", "\u{a0}%"),
            ("fr", "CA") => sym(NBSP, ",", "-", "", "\u{a0}%"),
            ("fr", _) => sym(NNBSP, ",", "-", "", "\u{a0}%"),
            ("es", "MX" | "US" | "419" | "PR" | "DO" | "GT" | "HN" | "NI" | "PA" | "SV") => EN,
            ("es", "" | "ES") => sym(".", ",", "-", "", "\u{a0}%"),
            ("es", _) => sym(".", ",", "-", "", "%"),
            ("pt", "PT") => sym(NBSP, ",", "-", "", "%"),
            ("pt", _) => sym(".", ",", "-", "", "%"),
            ("it", _) => sym(".", ",", "-", "", "%"),
            ("nl", _) => sym(".", ",", "-", "", "%"),
            ("tr", _) => sym(".", ",", "-", "%", ""),
            ("ru", _) => sym(NBSP, ",", "-", "", "\u{a0}%"),
            ("pl", _) => sym(NBSP, ",", "-", "", "%"),
            ("uk", _) => sym(NBSP, ",", "-", "", "%"),
            ("cs" | "sk", _) => sym(NBSP, ",", "-", "", "\u{a0}%"),
            ("sv" | "fi" | "nb" | "no" | "nn", _) => sym(NBSP, ",", "\u{2212}", "", "\u{a0}%"),
            ("da", _) => sym(".", ",", "-", "", "\u{a0}%"),
            ("ar", _) => sym(",", ".", "\u{200e}-", "", "\u{200e}%\u{200e}"),
            ("he", _) => sym(",", ".", "\u{200e}-", "", "%"),
            ("en", "ZA") => sym(NBSP, ",", "-", "", "%"),
            _ => EN,
        }
    }
}

/// Groups a string of ASCII digits in threes with `sep`.
fn group_digits(digits: &str, sep: &str) -> String {
    let n = digits.len();
    let mut out = String::with_capacity(n + n / 3 * sep.len());
    for (i, c) in digits.chars().enumerate() {
        if i > 0 && (n - i).is_multiple_of(3) {
            out.push_str(sep);
        }
        out.push(c);
    }
    out
}

/// Formats an integer with grouping (`NumberFormat.getInstance(locale).format(long)`).
pub fn format_integer(value: i128, sym: &NumberSymbols) -> String {
    let digits = value.unsigned_abs().to_string();
    let mut out = String::new();
    if value < 0 {
        out.push_str(sym.minus);
    }
    out.push_str(&group_digits(&digits, sym.group));
    out
}

/// Formats a double with grouping and at most `max_fraction` fraction digits, rounded half-even
/// on the exact binary value (Java's `DecimalFormat` default). `-0` keeps its sign, as in Java.
pub fn format_float(value: f64, max_fraction: usize, sym: &NumberSymbols) -> String {
    if value.is_nan() {
        return "NaN".to_string();
    }
    let mut out = String::new();
    if value.is_sign_negative() {
        out.push_str(sym.minus);
    }
    if value.is_infinite() {
        out.push('\u{221e}');
        return out;
    }
    // Rust's precision formatting rounds the exact binary value, ties to even.
    let fixed = format!("{:.*}", max_fraction, value.abs());
    let (int_part, frac_part) = match fixed.split_once('.') {
        Some((i, f)) => (i, f.trim_end_matches('0')),
        None => (fixed.as_str(), ""),
    };
    out.push_str(&group_digits(int_part, sym.group));
    if !frac_part.is_empty() {
        out.push_str(sym.decimal);
        out.push_str(frac_part);
    }
    out
}

/// Formats a ratio as a whole percent (`NumberFormat.getPercentInstance`): `0.256` gives `26%`.
pub fn format_percent_float(value: f64, sym: &NumberSymbols) -> String {
    let scaled = value * 100.0;
    wrap_percent(
        &format_float(scaled, 0, sym),
        scaled.is_sign_negative() && !scaled.is_nan(),
        sym,
    )
}

/// [`format_percent_float`] for an integer ratio (`12` gives `1,200%`).
pub fn format_percent_integer(value: i128, sym: &NumberSymbols) -> String {
    let scaled = value.saturating_mul(100);
    wrap_percent(&format_integer(scaled, sym), scaled < 0, sym)
}

fn wrap_percent(formatted: &str, negative: bool, sym: &NumberSymbols) -> String {
    let body = if negative {
        formatted.strip_prefix(sym.minus).unwrap_or(formatted)
    } else {
        formatted
    };
    let mut out = String::new();
    if negative {
        out.push_str(sym.minus);
    }
    out.push_str(sym.percent_prefix);
    out.push_str(body);
    out.push_str(sym.percent_suffix);
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    fn s(tag: &str) -> NumberSymbols {
        NumberSymbols::for_locale(&Locale::parse(tag))
    }

    /// Expected strings are the JDK 26 output of
    /// `MessageFormat("{0}|{1}|{2}|{3}|{4}|{5}", locale).format(1234567L, -1234567L, 1234.5678, -0.5, 100000L, 12L)`.
    #[test]
    fn matches_java_per_locale() {
        let cases = [
            ("en-US", "1,234,567|-1,234,567|1,234.568|-0.5|100,000|12"),
            ("es", "1.234.567|-1.234.567|1.234,568|-0,5|100.000|12"),
            (
                "fr",
                "1\u{202f}234\u{202f}567|-1\u{202f}234\u{202f}567|1\u{202f}234,568|-0,5|100\u{202f}000|12",
            ),
            ("de", "1.234.567|-1.234.567|1.234,568|-0,5|100.000|12"),
            ("pt-BR", "1.234.567|-1.234.567|1.234,568|-0,5|100.000|12"),
            ("it", "1.234.567|-1.234.567|1.234,568|-0,5|100.000|12"),
            (
                "ru",
                "1\u{a0}234\u{a0}567|-1\u{a0}234\u{a0}567|1\u{a0}234,568|-0,5|100\u{a0}000|12",
            ),
            ("ja", "1,234,567|-1,234,567|1,234.568|-0.5|100,000|12"),
            ("ko", "1,234,567|-1,234,567|1,234.568|-0.5|100,000|12"),
            ("zh-CN", "1,234,567|-1,234,567|1,234.568|-0.5|100,000|12"),
            ("zh-TW", "1,234,567|-1,234,567|1,234.568|-0.5|100,000|12"),
            (
                "ar",
                "1,234,567|\u{200e}-1,234,567|1,234.568|\u{200e}-0.5|100,000|12",
            ),
            ("hi", "1,234,567|-1,234,567|1,234.568|-0.5|100,000|12"),
            ("nl", "1.234.567|-1.234.567|1.234,568|-0,5|100.000|12"),
            (
                "pl",
                "1\u{a0}234\u{a0}567|-1\u{a0}234\u{a0}567|1\u{a0}234,568|-0,5|100\u{a0}000|12",
            ),
            ("tr", "1.234.567|-1.234.567|1.234,568|-0,5|100.000|12"),
            (
                "sv",
                "1\u{a0}234\u{a0}567|\u{2212}1\u{a0}234\u{a0}567|1\u{a0}234,568|\u{2212}0,5|100\u{a0}000|12",
            ),
            ("de-CH", "1'234'567|-1'234'567|1'234.568|-0.5|100'000|12"),
            (
                "pt-PT",
                "1\u{a0}234\u{a0}567|-1\u{a0}234\u{a0}567|1\u{a0}234,568|-0,5|100\u{a0}000|12",
            ),
            ("es-MX", "1,234,567|-1,234,567|1,234.568|-0.5|100,000|12"),
            ("en-IN", "1,234,567|-1,234,567|1,234.568|-0.5|100,000|12"),
            (
                "fr-CA",
                "1\u{a0}234\u{a0}567|-1\u{a0}234\u{a0}567|1\u{a0}234,568|-0,5|100\u{a0}000|12",
            ),
            ("und", "1,234,567|-1,234,567|1,234.568|-0.5|100,000|12"),
        ];
        for (tag, expected) in cases {
            let y = s(tag);
            let got = [
                format_integer(1234567, &y),
                format_integer(-1234567, &y),
                format_float(1234.5678, 3, &y),
                format_float(-0.5, 3, &y),
                format_integer(100000, &y),
                format_integer(12, &y),
            ]
            .join("|");
            assert_eq!(got, expected, "locale {tag}");
        }
    }

    #[test]
    fn float_edge_cases_match_java() {
        let en = EN;
        assert_eq!(format_float(0.0625, 3, &en), "0.062");
        assert_eq!(format_float(0.0005, 3, &en), "0.001");
        assert_eq!(format_float(0.0015, 3, &en), "0.002");
        assert_eq!(format_float(2.5, 3, &en), "2.5");
        assert_eq!(format_float(1.0005, 3, &en), "1");
        assert_eq!(format_float(-0.0, 3, &en), "-0");
        assert_eq!(format_float(-0.0001, 3, &en), "-0");
        assert_eq!(format_float(f64::NAN, 3, &en), "NaN");
        assert_eq!(format_float(f64::INFINITY, 3, &en), "\u{221e}");
        assert_eq!(format_float(f64::NEG_INFINITY, 3, &en), "-\u{221e}");
        assert_eq!(format_float(1e20, 3, &en), "100,000,000,000,000,000,000");
        assert_eq!(format_float(1234.5, 0, &en), "1,234");
        assert_eq!(format_float(1235.5, 0, &en), "1,236");
        assert_eq!(format_float(2.5, 0, &en), "2");
        assert_eq!(format_float(-2.5, 0, &en), "-2");
        assert_eq!(format_float(0.0625, 3, &s("de")), "0,062");
        assert_eq!(format_float(-3.5, 3, &s("sv")), "\u{2212}3,5");
        assert_eq!(
            format_integer(i64::MIN as i128, &en),
            "-9,223,372,036,854,775,808"
        );
    }

    /// JDK 26 `{0,number,percent}` output.
    #[test]
    fn percent_matches_java() {
        assert_eq!(format_percent_float(0.256, &EN), "26%");
        assert_eq!(format_percent_float(-0.256, &EN), "-26%");
        assert_eq!(format_percent_float(12.5, &EN), "1,250%");
        assert_eq!(format_percent_float(0.256, &s("fr")), "26\u{a0}%");
        assert_eq!(format_percent_float(-0.256, &s("tr")), "-%26");
        assert_eq!(format_percent_float(12.5, &s("tr")), "%1.250");
        assert_eq!(
            format_percent_float(-0.256, &s("ar")),
            "\u{200e}-26\u{200e}%\u{200e}"
        );
        assert_eq!(format_percent_float(-0.256, &s("sv")), "\u{2212}26\u{a0}%");
        assert_eq!(format_percent_integer(12, &s("pl")), "1\u{a0}200%");
    }
}
