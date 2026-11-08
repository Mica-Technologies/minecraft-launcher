// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The `java.text.MessageFormat` subset the bundles use, with the launcher's apostrophe rule.
//!
//! The Java launcher doubles every `'` before calling `MessageFormat.format`, so apostrophes are
//! always literal and MessageFormat quoting never happens. This formatter therefore treats `'` as
//! an ordinary character everywhere, including inside `choice` sub-messages (where Java's
//! re-formatting of the chosen text would turn a single `'` back into a quote: a Java bug this
//! port fixes, as the spec asks).
//!
//! Supported elements:
//!
//! * `{n}`: the argument formatted by type (strings as-is, numbers with locale grouping and up to
//!   three fraction digits, booleans as `true`/`false`, null as `null`);
//! * `{n,number}`, `{n,number,integer}`, `{n,number,percent}`;
//! * `{n,choice,limit#text|limit<text|...}`, with `≤` as a synonym for `#` and `∞`/`-∞` limits;
//!   a chosen text containing `{` is formatted again with the same arguments.
//!
//! As in Java, the whole pattern is parsed before anything is formatted, a missing argument
//! renders as the literal `{n}`, extra arguments are ignored, and a lone `}` outside an element is
//! literal text. `{n,date}`, `{n,time}`, `{n,number,currency}` and `DecimalFormat` sub-patterns are
//! parsed but rejected with [`PatternError::Unsupported`] (no bundle uses them).

use std::borrow::Cow;
use std::fmt;
use std::path::{Path, PathBuf};

use crate::locale::Locale;
use crate::number::{self, NumberSymbols};

/// One `MessageFormat` argument.
#[derive(Debug, Clone, PartialEq)]
pub enum FmtArg<'a> {
    /// Text, inserted as-is.
    Str(Cow<'a, str>),
    /// A signed integer, formatted with locale grouping.
    Int(i64),
    /// An unsigned integer too large for `i64`.
    UInt(u64),
    /// A floating-point number: grouping and at most three fraction digits, half-even.
    Float(f64),
    /// `true` / `false`.
    Bool(bool),
    /// Java `null`: renders as `null`.
    Null,
}

impl<'a> FmtArg<'a> {
    /// Wraps anything `Display` as a string argument.
    pub fn display(value: impl fmt::Display) -> FmtArg<'static> {
        FmtArg::Str(Cow::Owned(value.to_string()))
    }

    /// The numeric value, if this argument is a number.
    fn as_f64(&self) -> Option<f64> {
        match self {
            FmtArg::Int(i) => Some(*i as f64),
            FmtArg::UInt(u) => Some(*u as f64),
            FmtArg::Float(f) => Some(*f),
            _ => None,
        }
    }

    fn as_i128(&self) -> Option<i128> {
        match self {
            FmtArg::Int(i) => Some(i128::from(*i)),
            FmtArg::UInt(u) => Some(i128::from(*u)),
            _ => None,
        }
    }

    /// Java's `String.valueOf(arg)`: what the debug fallback (`template [a, b]`) shows. Numbers
    /// are not grouped here, and doubles print the way `Double.toString` does (`2.0`, `1.0E20`).
    pub fn to_java_string(&self) -> String {
        match self {
            FmtArg::Str(s) => s.to_string(),
            FmtArg::Int(i) => i.to_string(),
            FmtArg::UInt(u) => u.to_string(),
            FmtArg::Float(f) => java_double_to_string(*f),
            FmtArg::Bool(b) => b.to_string(),
            FmtArg::Null => "null".to_string(),
        }
    }
}

fn java_double_to_string(f: f64) -> String {
    if f.is_nan() {
        return "NaN".into();
    }
    if f.is_infinite() {
        return if f > 0.0 { "Infinity" } else { "-Infinity" }.into();
    }
    let a = f.abs();
    if a == 0.0 || (1e-3..1e7).contains(&a) {
        let s = format!("{f}");
        if s.contains('.') { s } else { format!("{s}.0") }
    } else {
        let s = format!("{f:e}");
        let (mantissa, exp) = s.split_once('e').unwrap_or((&s, "0"));
        let mantissa = if mantissa.contains('.') {
            mantissa.to_string()
        } else {
            format!("{mantissa}.0")
        };
        format!("{mantissa}E{exp}")
    }
}

macro_rules! from_int {
    ($($t:ty),*) => {$(
        impl From<$t> for FmtArg<'_> {
            fn from(v: $t) -> Self { FmtArg::Int(i64::from(v)) }
        }
        impl From<&$t> for FmtArg<'_> {
            fn from(v: &$t) -> Self { FmtArg::Int(i64::from(*v)) }
        }
    )*};
}
from_int!(i8, i16, i32, i64, u8, u16, u32);

macro_rules! from_wide {
    ($($t:ty),*) => {$(
        impl From<$t> for FmtArg<'_> {
            fn from(v: $t) -> Self {
                match i64::try_from(v) {
                    Ok(i) => FmtArg::Int(i),
                    Err(_) => FmtArg::UInt(v as u64),
                }
            }
        }
        impl From<&$t> for FmtArg<'_> {
            fn from(v: &$t) -> Self { FmtArg::from(*v) }
        }
    )*};
}
from_wide!(u64, usize);

impl From<isize> for FmtArg<'_> {
    fn from(v: isize) -> Self {
        FmtArg::Int(v as i64)
    }
}
impl From<&isize> for FmtArg<'_> {
    fn from(v: &isize) -> Self {
        FmtArg::Int(*v as i64)
    }
}
impl From<f64> for FmtArg<'_> {
    fn from(v: f64) -> Self {
        FmtArg::Float(v)
    }
}
impl From<&f64> for FmtArg<'_> {
    fn from(v: &f64) -> Self {
        FmtArg::Float(*v)
    }
}
impl From<f32> for FmtArg<'_> {
    fn from(v: f32) -> Self {
        FmtArg::Float(f64::from(v))
    }
}
impl From<&f32> for FmtArg<'_> {
    fn from(v: &f32) -> Self {
        FmtArg::Float(f64::from(*v))
    }
}
impl From<bool> for FmtArg<'_> {
    fn from(v: bool) -> Self {
        FmtArg::Bool(v)
    }
}
impl From<&bool> for FmtArg<'_> {
    fn from(v: &bool) -> Self {
        FmtArg::Bool(*v)
    }
}
impl From<char> for FmtArg<'_> {
    fn from(v: char) -> Self {
        FmtArg::Str(Cow::Owned(v.to_string()))
    }
}
impl<'a> From<&'a str> for FmtArg<'a> {
    fn from(v: &'a str) -> Self {
        FmtArg::Str(Cow::Borrowed(v))
    }
}
impl<'a> From<&&'a str> for FmtArg<'a> {
    fn from(v: &&'a str) -> Self {
        FmtArg::Str(Cow::Borrowed(v))
    }
}
impl From<String> for FmtArg<'_> {
    fn from(v: String) -> Self {
        FmtArg::Str(Cow::Owned(v))
    }
}
impl<'a> From<&'a String> for FmtArg<'a> {
    fn from(v: &'a String) -> Self {
        FmtArg::Str(Cow::Borrowed(v.as_str()))
    }
}
impl<'a> From<Cow<'a, str>> for FmtArg<'a> {
    fn from(v: Cow<'a, str>) -> Self {
        FmtArg::Str(v)
    }
}
impl<'a> From<&'a Cow<'a, str>> for FmtArg<'a> {
    fn from(v: &'a Cow<'a, str>) -> Self {
        FmtArg::Str(Cow::Borrowed(v.as_ref()))
    }
}
impl<'a> From<&'a Path> for FmtArg<'a> {
    fn from(v: &'a Path) -> Self {
        FmtArg::Str(v.to_string_lossy())
    }
}
impl<'a> From<&'a PathBuf> for FmtArg<'a> {
    fn from(v: &'a PathBuf) -> Self {
        FmtArg::Str(v.to_string_lossy())
    }
}
impl From<PathBuf> for FmtArg<'_> {
    fn from(v: PathBuf) -> Self {
        FmtArg::Str(Cow::Owned(v.to_string_lossy().into_owned()))
    }
}
impl<'a> From<&'a Locale> for FmtArg<'a> {
    fn from(v: &'a Locale) -> Self {
        FmtArg::Str(Cow::Owned(v.to_tag()))
    }
}
impl<'a, T: Into<FmtArg<'a>>> From<Option<T>> for FmtArg<'a> {
    fn from(v: Option<T>) -> Self {
        v.map_or(FmtArg::Null, Into::into)
    }
}

/// Why a pattern could not be formatted (Java's `IllegalArgumentException` cases).
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum PatternError {
    /// A `{` without its `}`.
    #[error("unmatched braces in the pattern")]
    UnmatchedBrace,
    /// The argument index is not an integer in `0..10000`.
    #[error("can't parse argument number: {0}")]
    BadArgumentIndex(String),
    /// An unknown format type (`{0,badtype}`).
    #[error("unknown format type: {0}")]
    UnknownFormatType(String),
    /// A format Java supports but this port does not (`date`, `time`, `currency`, sub-patterns).
    #[error("unsupported format: {0}")]
    Unsupported(String),
    /// A `choice` pattern that does not parse.
    #[error("malformed choice pattern: {0}")]
    BadChoice(String),
    /// A number format applied to a non-numeric argument.
    #[error("cannot format given argument as a number")]
    NotANumber,
}

#[derive(Debug, Clone)]
enum Piece {
    Text(String),
    Arg { index: usize, kind: Kind },
}

#[derive(Debug, Clone)]
enum Kind {
    Plain,
    Number,
    Integer,
    Percent,
    Choice(Vec<(f64, String)>),
}

/// Java caps argument indices at 10,000 (`exceeds the ArgumentIndex implementation limit`).
const MAX_ARGUMENT_INDEX: i64 = 10_000;

/// Formats `pattern` with `args`, numbers in `locale`'s style.
pub fn format(pattern: &str, args: &[FmtArg], locale: &Locale) -> Result<String, PatternError> {
    let pieces = parse(pattern)?;
    let symbols = NumberSymbols::for_locale(locale);
    render(&pieces, args, &symbols)
}

fn render(pieces: &[Piece], args: &[FmtArg], sym: &NumberSymbols) -> Result<String, PatternError> {
    let mut out = String::new();
    for piece in pieces {
        match piece {
            Piece::Text(t) => out.push_str(t),
            Piece::Arg { index, kind } => {
                let Some(arg) = args.get(*index) else {
                    out.push('{');
                    out.push_str(&index.to_string());
                    out.push('}');
                    continue;
                };
                match kind {
                    Kind::Plain => out.push_str(&format_plain(arg, sym)),
                    Kind::Number => out.push_str(&format_number(arg, 3, sym)?),
                    Kind::Integer => out.push_str(&format_number(arg, 0, sym)?),
                    Kind::Percent => match arg {
                        FmtArg::Float(f) => out.push_str(&number::format_percent_float(*f, sym)),
                        _ => {
                            let i = arg.as_i128().ok_or(PatternError::NotANumber)?;
                            out.push_str(&number::format_percent_integer(i, sym));
                        }
                    },
                    Kind::Choice(items) => {
                        let value = arg.as_f64().ok_or(PatternError::NotANumber)?;
                        let chosen = choose(items, value);
                        if chosen.contains('{') {
                            let nested = parse(chosen)?;
                            out.push_str(&render(&nested, args, sym)?);
                        } else {
                            out.push_str(chosen);
                        }
                    }
                }
            }
        }
    }
    Ok(out)
}

fn format_plain(arg: &FmtArg, sym: &NumberSymbols) -> String {
    match arg {
        FmtArg::Str(s) => s.to_string(),
        FmtArg::Int(_) | FmtArg::UInt(_) => {
            number::format_integer(arg.as_i128().unwrap_or_default(), sym)
        }
        FmtArg::Float(f) => number::format_float(*f, 3, sym),
        FmtArg::Bool(b) => b.to_string(),
        FmtArg::Null => "null".to_string(),
    }
}

fn format_number(
    arg: &FmtArg,
    max_fraction: usize,
    sym: &NumberSymbols,
) -> Result<String, PatternError> {
    match arg {
        FmtArg::Int(_) | FmtArg::UInt(_) => Ok(number::format_integer(
            arg.as_i128().unwrap_or_default(),
            sym,
        )),
        FmtArg::Float(f) => Ok(number::format_float(*f, max_fraction, sym)),
        _ => Err(PatternError::NotANumber),
    }
}

/// `ChoiceFormat.format(double)`: walk the limits until one fails, take the previous item (the
/// first item when the value is below every limit).
fn choose(items: &[(f64, String)], value: f64) -> &str {
    let mut i = 0;
    while i < items.len() {
        // `!(a >= b)` rather than `a < b` so NaN selects the first item, as in Java.
        #[allow(clippy::neg_cmp_op_on_partial_ord)]
        if !(value >= items[i].0) {
            break;
        }
        i += 1;
    }
    let i = i.saturating_sub(1);
    &items[i].1
}

#[derive(PartialEq, Eq, Clone, Copy)]
enum Seg {
    Index,
    Type,
    Modifier,
}

/// Port of `MessageFormat.applyPattern` with quoting disabled (every `'` is literal).
fn parse(pattern: &str) -> Result<Vec<Piece>, PatternError> {
    let mut pieces = Vec::new();
    let mut text = String::new();
    let mut in_element = false;
    let mut seg = Seg::Index;
    let mut segs = [String::new(), String::new(), String::new()];
    let mut depth = 0usize;
    for ch in pattern.chars() {
        if !in_element {
            if ch == '{' {
                if !text.is_empty() {
                    pieces.push(Piece::Text(std::mem::take(&mut text)));
                }
                in_element = true;
                seg = Seg::Index;
                depth = 0;
                for s in &mut segs {
                    s.clear();
                }
            } else {
                text.push(ch);
            }
            continue;
        }
        let cur = match seg {
            Seg::Index => 0,
            Seg::Type => 1,
            Seg::Modifier => 2,
        };
        match ch {
            ',' if seg != Seg::Modifier => {
                seg = if seg == Seg::Index {
                    Seg::Type
                } else {
                    Seg::Modifier
                };
            }
            '{' => {
                depth += 1;
                segs[cur].push(ch);
            }
            '}' => {
                if depth == 0 {
                    in_element = false;
                    pieces.push(make_element(&segs)?);
                } else {
                    depth -= 1;
                    segs[cur].push(ch);
                }
            }
            ' ' if seg == Seg::Type && segs[1].is_empty() => {}
            _ => segs[cur].push(ch),
        }
    }
    if in_element {
        return Err(PatternError::UnmatchedBrace);
    }
    if !text.is_empty() {
        pieces.push(Piece::Text(text));
    }
    Ok(pieces)
}

/// Java's `Integer.parseInt`: optional sign, ASCII digits, `i32` range.
fn parse_java_int(s: &str) -> Option<i64> {
    let digits = s.strip_prefix(['+', '-']).unwrap_or(s);
    if digits.is_empty() || !digits.bytes().all(|b| b.is_ascii_digit()) {
        return None;
    }
    let v: i64 = s.parse().ok()?;
    if v < i64::from(i32::MIN) || v > i64::from(i32::MAX) {
        return None;
    }
    Some(v)
}

fn make_element(segs: &[String; 3]) -> Result<Piece, PatternError> {
    let index = parse_java_int(&segs[0])
        .filter(|i| (0..MAX_ARGUMENT_INDEX).contains(i))
        .ok_or_else(|| PatternError::BadArgumentIndex(segs[0].clone()))?;
    let index = index as usize;
    let ty = segs[1].trim().to_lowercase();
    let kind = match ty.as_str() {
        "" => Kind::Plain,
        "number" => match segs[2].trim().to_lowercase().as_str() {
            "" => Kind::Number,
            "integer" => Kind::Integer,
            "percent" => Kind::Percent,
            "currency" => return Err(PatternError::Unsupported("number,currency".into())),
            other => return Err(PatternError::Unsupported(format!("number,{other}"))),
        },
        "date" | "time" => return Err(PatternError::Unsupported(ty)),
        "choice" => Kind::Choice(parse_choice(&segs[2])?),
        _ => return Err(PatternError::UnknownFormatType(segs[1].clone())),
    };
    Ok(Piece::Arg { index, kind })
}

/// The next representable double above `x` (Java's `ChoiceFormat.nextDouble`).
fn next_double(x: f64) -> f64 {
    if x.is_nan() || x == f64::INFINITY {
        return x;
    }
    if x == 0.0 {
        return f64::from_bits(1);
    }
    let bits = x.to_bits();
    if x > 0.0 {
        f64::from_bits(bits + 1)
    } else {
        f64::from_bits(bits - 1)
    }
}

/// Java's `Double.parseDouble` for choice limits (trimmed; `∞` handled by the caller).
fn parse_limit(s: &str) -> Option<f64> {
    let t = s.trim_matches(|c: char| c <= ' ');
    let t = t
        .strip_suffix(['d', 'D', 'f', 'F'])
        .filter(|r| !r.is_empty() && !r.ends_with(['e', 'E']))
        .unwrap_or(t);
    match t {
        "Infinity" | "+Infinity" => Some(f64::INFINITY),
        "-Infinity" => Some(f64::NEG_INFINITY),
        "NaN" | "+NaN" | "-NaN" => Some(f64::NAN),
        _ => {
            if t.is_empty()
                || t.bytes()
                    .any(|b| b.is_ascii_alphabetic() && b != b'e' && b != b'E')
            {
                return None;
            }
            t.parse().ok()
        }
    }
}

/// Port of `ChoiceFormat.applyPattern` (quotes literal).
fn parse_choice(pattern: &str) -> Result<Vec<(f64, String)>, PatternError> {
    let mut items: Vec<(f64, String)> = Vec::new();
    let mut limit_buf = String::new();
    let mut text_buf = String::new();
    let mut in_text = false;
    let mut start = 0.0f64;
    let mut old_start = f64::NAN;
    for ch in pattern.chars() {
        if !in_text && (ch == '<' || ch == '#' || ch == '\u{2264}') {
            if limit_buf.is_empty() {
                return Err(PatternError::BadChoice(
                    "each interval must contain a number before a format".into(),
                ));
            }
            start = match limit_buf.as_str() {
                "\u{221e}" => f64::INFINITY,
                "-\u{221e}" => f64::NEG_INFINITY,
                other => parse_limit(other)
                    .ok_or_else(|| PatternError::BadChoice(format!("bad limit {other:?}")))?,
            };
            if ch == '<' && start.is_finite() {
                start = next_double(start);
            }
            if start <= old_start {
                return Err(PatternError::BadChoice(
                    "incorrect order of intervals, must be in ascending order".into(),
                ));
            }
            limit_buf.clear();
            in_text = true;
        } else if ch == '|' {
            items.push((start, std::mem::take(&mut text_buf)));
            old_start = start;
            in_text = false;
        } else if in_text {
            text_buf.push(ch);
        } else {
            limit_buf.push(ch);
        }
    }
    if in_text {
        items.push((start, text_buf));
    }
    if items.is_empty() {
        return Err(PatternError::BadChoice("no choices".into()));
    }
    Ok(items)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn en(pattern: &str, args: &[FmtArg]) -> Result<String, PatternError> {
        format(pattern, args, &Locale::en_us())
    }

    fn ok(pattern: &str, args: &[FmtArg]) -> String {
        en(pattern, args).unwrap_or_else(|e| panic!("{pattern:?}: {e}"))
    }

    fn i(v: i64) -> FmtArg<'static> {
        FmtArg::Int(v)
    }
    fn f(v: f64) -> FmtArg<'static> {
        FmtArg::Float(v)
    }
    fn s(v: &str) -> FmtArg<'_> {
        FmtArg::from(v)
    }

    /// Every expectation below is JDK 26 output of
    /// `new MessageFormat(pattern.replace("'", "''"), Locale.US).format(args)`.
    #[test]
    fn matches_java_message_format() {
        let mods = "{0,choice,0#0 mods|1#1 mod|1<{0} mods}";
        assert_eq!(ok("Page {0} of {1}", &[i(2), i(5)]), "Page 2 of 5");
        assert_eq!(ok("Page {0} of {1}", &[i(2)]), "Page 2 of {1}");
        assert_eq!(ok("{0,number}", &[i(1234)]), "1,234");
        assert_eq!(ok("{0,number}", &[f(1234.5)]), "1,234.5");
        assert_eq!(ok("{0,number,integer}", &[f(1234.5)]), "1,234");
        assert_eq!(ok("{0,number,integer}", &[f(1235.5)]), "1,236");
        assert_eq!(ok("{0,number,integer}", &[f(2.5)]), "2");
        assert_eq!(ok("{0,number,integer}", &[i(12345)]), "12,345");
        assert_eq!(ok("{0,number,percent}", &[f(0.256)]), "26%");
        assert_eq!(en("{0,number}", &[s("str")]), Err(PatternError::NotANumber));
        assert_eq!(ok(mods, &[i(0)]), "0 mods");
        assert_eq!(ok(mods, &[i(1)]), "1 mod");
        assert_eq!(ok(mods, &[i(5)]), "5 mods");
        assert_eq!(ok(mods, &[i(1000)]), "1,000 mods");
        assert_eq!(ok(mods, &[i(-1)]), "0 mods");
        assert_eq!(ok(mods, &[f(1.5)]), "1.5 mods");
        assert_eq!(ok("{0,choice,0#a|1#b|2#c}", &[f(1.5)]), "b");
        assert!(en("{0,choice,1#one|0#zero}", &[i(1)]).is_err());
        assert_eq!(ok("{0,choice,-1#neg|0#zero|1<pos}", &[i(-5)]), "neg");
        assert_eq!(ok("{0,choice,0#a{1}b|1#x}", &[i(0), s("Y")]), "aYb");
        assert_eq!(ok("{0,choice,0\u{2264}a|1#b}", &[i(0)]), "a");
        assert_eq!(
            ok("{0,choice,0#a|\u{221e}#inf}", &[f(f64::INFINITY)]),
            "inf"
        );
        assert_eq!(ok("{0,choice,-\u{221e}#ninf|0#a}", &[i(-5)]), "ninf");
        assert!(en("{0,choice,0#a|1#b}", &[s("str")]).is_err());
        assert!(en("{0,choice,0#a|1#b}", &[FmtArg::Bool(true)]).is_err());
        assert!(en("{0,choice,}", &[i(1)]).is_err());
        assert_eq!(ok("{0,choice,0#a|1}", &[i(1)]), "a");
        assert_eq!(ok("{0,choice,0#a|}", &[i(1)]), "a");
        assert!(en("{0,choice,0#a{b|1#c}", &[i(0)]).is_err());
        assert_eq!(ok("{0,choice,0#{0,number,integer} x|1#y}", &[i(0)]), "0 x");
        assert_eq!(ok("{0,choice,0#zero|1#one {0}}", &[i(1000)]), "one 1,000");
        assert_eq!(ok("{0,choice, 0 # a | 1 # b }", &[i(1)]), " b ");
        assert_eq!(ok("{0,choice,0#a|1#b#c}", &[i(1)]), "b#c");
        assert_eq!(ok("{0,choice,0#a|1#b}", &[]), "{0}");
        assert_eq!(ok("it's {0,choice,0#a'b|1#c'{0}d}", &[i(0)]), "it's a'b");
        assert_eq!(ok("l'outil {0} n'a pas", &[s("X")]), "l'outil X n'a pas");
        assert_eq!(ok("'{0}'", &[s("x")]), "'x'");
        assert_eq!(ok("a } b {0}", &[s("x")]), "a } b x");
        assert_eq!(en("{0", &[s("x")]), Err(PatternError::UnmatchedBrace));
        assert!(en("{foo}", &[s("x")]).is_err());
        assert!(en("{0,badtype}", &[s("x")]).is_err());
        assert!(en("{ 0 }", &[s("x")]).is_err());
        assert_eq!(ok("{00}", &[s("x")]), "x");
        assert_eq!(ok("{+0}", &[s("x")]), "x");
        assert_eq!(ok("{-0}", &[s("x")]), "x");
        assert!(en("{-1}", &[s("x")]).is_err());
        assert!(en("{}", &[s("x")]).is_err());
        assert_eq!(ok("{9999}", &[f(-2.5)]), "{9999}");
        assert!(en("{10000}", &[s("x")]).is_err());
        assert!(en("{2147483648}", &[s("x")]).is_err());
        assert_eq!(ok("{0,NUMBER}", &[i(5000)]), "5,000");
        assert_eq!(ok("{0, number}", &[i(5000)]), "5,000");
        assert_eq!(ok("{0,number, integer}", &[f(5000.7)]), "5,001");
        assert_eq!(ok("{0,number,INTEGER}", &[f(5000.7)]), "5,001");
        assert!(en("{0 ,number}", &[i(5000)]).is_err());
        assert_eq!(ok("{0}", &[FmtArg::Bool(true)]), "true");
        assert_eq!(ok("{0}", &[FmtArg::Null]), "null");
        assert_eq!(ok("{1}{0}", &[s("a"), s("b")]), "ba");
        assert_eq!(ok("x {0} y {0}", &[i(5)]), "x 5 y 5");
        assert!(en("{0,number}", &[FmtArg::Bool(true)]).is_err());
        assert_eq!(ok("{0}{12}", &[s("a")]), "a{12}");
        assert_eq!(ok("{0,choice,0#a|1#b}{1,number}", &[i(1)]), "b{1}");
        assert!(en("{0,number,integer,extra}", &[i(1)]).is_err());
        assert_eq!(ok("{0,choice,0#a,b|1#c}", &[i(0)]), "a,b");
        assert_eq!(ok("{0,choice,0#{1}|1#c}", &[i(0)]), "{1}");
        assert!(en("{0,choice,0#'{'|1#c}", &[i(0)]).is_err());
        assert_eq!(ok("{0,number,integer}", &[f(-2.5)]), "-2");
    }

    /// Java renders `c{0}d` here because its nested re-format turns the single `'` back into a
    /// quote; the launcher's rule is that apostrophes are always literal.
    #[test]
    fn apostrophes_in_choice_sub_messages_are_literal() {
        assert_eq!(ok("it's {0,choice,0#a'b|1#c'{0}d}", &[i(1)]), "it's c'1d");
    }

    #[test]
    fn unsupported_java_formats_are_errors() {
        assert!(matches!(
            en("{0,date}", &[i(1)]),
            Err(PatternError::Unsupported(_))
        ));
        assert!(matches!(
            en("{0,number,#.##}", &[f(1.234)]),
            Err(PatternError::Unsupported(_))
        ));
        assert!(matches!(
            en("{0,number,currency}", &[f(1.0)]),
            Err(PatternError::Unsupported(_))
        ));
    }

    #[test]
    fn locale_specific_numbers() {
        let fr = Locale::parse("fr");
        assert_eq!(
            format("{0,number,percent}", &[f(0.256)], &fr).unwrap(),
            "26\u{a0}%"
        );
        let ar = Locale::parse("ar");
        assert_eq!(
            format("{0,choice,0#a|1#{0} b}", &[i(5)], &ar).unwrap(),
            "5 b"
        );
        assert_eq!(
            format("{0,number,integer}", &[f(1234.5)], &ar).unwrap(),
            "1,234"
        );
    }

    #[test]
    fn java_to_string() {
        assert_eq!(f(2.0).to_java_string(), "2.0");
        assert_eq!(f(1e20).to_java_string(), "1.0E20");
        assert_eq!(f(1.5e-5).to_java_string(), "1.5E-5");
        assert_eq!(f(1234.5).to_java_string(), "1234.5");
        assert_eq!(i(1234).to_java_string(), "1234");
        assert_eq!(FmtArg::Null.to_java_string(), "null");
    }

    #[test]
    fn conversions() {
        let owned = String::from("x");
        let p = PathBuf::from("a");
        let args: Vec<FmtArg> = vec![
            1u8.into(),
            (&5usize).into(),
            u64::MAX.into(),
            "s".into(),
            (&owned).into(),
            (&p).into(),
            Some(3i32).into(),
            Option::<i32>::None.into(),
        ];
        assert_eq!(args[2], FmtArg::UInt(u64::MAX));
        assert_eq!(args[7], FmtArg::Null);
        assert_eq!(ok("{2}", &args), "18,446,744,073,709,551,615");
    }
}
