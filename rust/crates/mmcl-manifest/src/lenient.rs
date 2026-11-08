// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Gson-compatible field adapters for serde.
//!
//! Every adapter reads the field as a [`serde_json::Value`] and coerces it the way Gson's
//! built-in type adapters do, and none of them fails: where Gson would throw (an object where
//! a string belongs, a fractional port), the field reads as absent instead. Being more
//! forgiving than Java is safe here, since every manifest Java loads still loads; the editor
//! uses [`crate::gson::compat_problems`] to keep what it saves loadable by Java.
//!
//! Use them with `#[serde(default, deserialize_with = "...")]`, and deserialize from a
//! `Value` (as [`crate::PackManifest::parse`] does) so duplicate keys have already collapsed
//! to the last one.

use serde::de::DeserializeOwned;
use serde::{Deserialize, Deserializer};
use serde_json::{Number, Value};

/// Gson's `String` adapter: strings as-is, numbers and booleans as their text, `null` and
/// containers as absent.
pub fn coerce_string(v: &Value) -> Option<String> {
    match v {
        Value::String(s) => Some(s.clone()),
        Value::Number(n) => Some(n.to_string()),
        Value::Bool(b) => Some(b.to_string()),
        _ => None,
    }
}

/// Gson's `boolean` adapter: booleans as-is, strings through `Boolean.parseBoolean` (only a
/// case-insensitive `"true"` is true). Numbers, which Gson rejects, read as true when
/// non-zero. Everything else is false.
pub fn coerce_bool(v: &Value) -> bool {
    match v {
        Value::Bool(b) => *b,
        Value::String(s) => s.eq_ignore_ascii_case("true"),
        Value::Number(n) => n.as_f64().is_some_and(|f| f != 0.0),
        _ => false,
    }
}

/// A JSON number as an `i32` when it is integral and in range (Gson's `nextInt`).
pub fn number_to_i32(n: &Number) -> Option<i32> {
    if let Some(i) = n.as_i64() {
        return i32::try_from(i).ok();
    }
    if n.as_u64().is_some() {
        return None;
    }
    let f = n.as_f64()?;
    if f.fract() == 0.0 && f >= f64::from(i32::MIN) && f <= f64::from(i32::MAX) {
        Some(f as i32)
    } else {
        None
    }
}

/// A numeric string as an `i32`: `Integer.parseInt`, then Gson's fallback of parsing it as a
/// double that must be integral.
pub fn str_to_i32(s: &str) -> Option<i32> {
    let t = s.trim();
    if let Ok(i) = t.parse::<i32>() {
        return Some(i);
    }
    let f = parse_java_double(t)?;
    if f.is_finite() && f.fract() == 0.0 && f >= f64::from(i32::MIN) && f <= f64::from(i32::MAX) {
        Some(f as i32)
    } else {
        None
    }
}

/// Gson's `Integer` adapter: numbers and numeric strings.
pub fn coerce_i32(v: &Value) -> Option<i32> {
    match v {
        Value::Number(n) => number_to_i32(n),
        Value::String(s) => str_to_i32(s),
        _ => None,
    }
}

/// `Double.parseDouble` semantics for the inputs manifests use: surrounding whitespace is
/// ignored, an optional `d`/`f` suffix is accepted, and `NaN` / `Infinity` are spelled the
/// Java way. Returns `None` where Java throws `NumberFormatException`.
pub fn parse_java_double(s: &str) -> Option<f64> {
    let t = s.trim();
    let t = t
        .strip_suffix(['d', 'D', 'f', 'F'])
        .filter(|rest| rest.ends_with(|c: char| c.is_ascii_digit() || c == '.'))
        .unwrap_or(t);
    match t {
        "NaN" | "+NaN" | "-NaN" => return Some(f64::NAN),
        "Infinity" | "+Infinity" => return Some(f64::INFINITY),
        "-Infinity" => return Some(f64::NEG_INFINITY),
        _ => {}
    }
    if t.is_empty()
        || !t
            .chars()
            .all(|c| c.is_ascii_digit() || matches!(c, '.' | 'e' | 'E' | '+' | '-'))
        || !t.chars().any(|c| c.is_ascii_digit())
    {
        return None;
    }
    t.parse::<f64>().ok()
}

/// serde adapter: [`coerce_string`].
pub fn string<'de, D: Deserializer<'de>>(d: D) -> Result<Option<String>, D::Error> {
    Ok(coerce_string(&Value::deserialize(d)?))
}

/// serde adapter: [`coerce_bool`].
pub fn boolean<'de, D: Deserializer<'de>>(d: D) -> Result<bool, D::Error> {
    Ok(coerce_bool(&Value::deserialize(d)?))
}

/// serde adapter: [`coerce_i32`].
pub fn opt_i32<'de, D: Deserializer<'de>>(d: D) -> Result<Option<i32>, D::Error> {
    Ok(coerce_i32(&Value::deserialize(d)?))
}

/// serde adapter for a list of objects: `null` elements and elements that are not objects
/// are dropped; a value that is not an array reads as absent.
pub fn vec_skip_null<'de, D, T>(d: D) -> Result<Option<Vec<T>>, D::Error>
where
    D: Deserializer<'de>,
    T: DeserializeOwned,
{
    Ok(objects_of(Value::deserialize(d)?))
}

/// The conversion behind [`vec_skip_null`], usable on an already-parsed value.
pub fn objects_of<T: DeserializeOwned>(v: Value) -> Option<Vec<T>> {
    match v {
        Value::Array(items) => Some(
            items
                .into_iter()
                .filter(Value::is_object)
                .filter_map(|item| serde_json::from_value(item).ok())
                .collect(),
        ),
        _ => None,
    }
}

/// serde adapter for a list of strings: `null` and container elements are dropped, numbers
/// and booleans are coerced to text; a value that is not an array reads as absent.
pub fn string_list<'de, D: Deserializer<'de>>(d: D) -> Result<Option<Vec<String>>, D::Error> {
    Ok(match Value::deserialize(d)? {
        Value::Array(items) => Some(items.iter().filter_map(coerce_string).collect()),
        _ => None,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn strings_coerce_numbers_and_booleans() {
        assert_eq!(coerce_string(&json!("6")), Some("6".into()));
        assert_eq!(coerce_string(&json!(6)), Some("6".into()));
        assert_eq!(coerce_string(&json!(true)), Some("true".into()));
        assert_eq!(coerce_string(&json!(null)), None);
        assert_eq!(coerce_string(&json!({"a": 1})), None);
    }

    #[test]
    fn booleans_follow_parse_boolean() {
        assert!(coerce_bool(&json!(true)));
        assert!(coerce_bool(&json!("TRUE")));
        assert!(!coerce_bool(&json!("yes")));
        assert!(!coerce_bool(&json!(null)));
        assert!(coerce_bool(&json!(1)));
        assert!(!coerce_bool(&json!(0)));
    }

    #[test]
    fn ints_accept_numeric_strings_and_integral_doubles() {
        assert_eq!(coerce_i32(&json!(8585)), Some(8585));
        assert_eq!(coerce_i32(&json!("8585")), Some(8585));
        assert_eq!(coerce_i32(&json!(" 8585 ")), Some(8585));
        assert_eq!(coerce_i32(&json!(8585.0)), Some(8585));
        assert_eq!(coerce_i32(&json!("8585.0")), Some(8585));
        assert_eq!(coerce_i32(&json!(85.5)), None);
        assert_eq!(coerce_i32(&json!("abc")), None);
        assert_eq!(coerce_i32(&json!(4_000_000_000_i64)), None);
        assert_eq!(coerce_i32(&json!(true)), None);
    }

    #[test]
    fn java_double_parsing() {
        assert_eq!(parse_java_double("1.5"), Some(1.5));
        assert_eq!(parse_java_double(" 6 "), Some(6.0));
        assert_eq!(parse_java_double("2d"), Some(2.0));
        assert_eq!(parse_java_double("1e3"), Some(1000.0));
        assert_eq!(parse_java_double("Infinity"), Some(f64::INFINITY));
        assert!(parse_java_double("NaN").unwrap().is_nan());
        assert_eq!(parse_java_double("inf"), None);
        assert_eq!(parse_java_double("lots"), None);
        assert_eq!(parse_java_double(""), None);
        assert_eq!(parse_java_double("."), None);
    }

    #[test]
    fn object_lists_drop_nulls_and_non_objects() {
        #[derive(serde::Deserialize, Debug, PartialEq)]
        struct E {
            a: i32,
        }
        let got: Option<Vec<E>> = objects_of(json!([{"a": 1}, null, "x", 3, {"a": 2}]));
        assert_eq!(got, Some(vec![E { a: 1 }, E { a: 2 }]));
        assert_eq!(objects_of::<E>(json!("x")), None);
    }
}
