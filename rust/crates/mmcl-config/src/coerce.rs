// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Type-tolerant reads that mirror Gson's `JsonElement.getAs*` (the Java tests pin them).
//!
//! Each function returns `None` where Gson would throw; the store then falls back to the
//! caller's default and logs a warning. Out-of-range integers are refused instead of being
//! truncated to garbage the way Gson's `intValue()` does.

use serde_json::Value;

/// `getAsString`: strings as-is, numbers and booleans as text, a one-element array as its
/// element.
pub fn as_string(v: &Value) -> Option<String> {
    match v {
        Value::String(s) => Some(s.clone()),
        Value::Number(n) => Some(n.to_string()),
        Value::Bool(b) => Some(b.to_string()),
        Value::Array(a) if a.len() == 1 => as_string(&a[0]),
        _ => None,
    }
}

/// `getAsBoolean`: booleans; any string or number goes through `Boolean.parseBoolean`, so only
/// a case-insensitive `"true"` is true and it never fails.
pub fn as_bool(v: &Value) -> Option<bool> {
    match v {
        Value::Bool(b) => Some(*b),
        Value::String(s) => Some(s.eq_ignore_ascii_case("true")),
        Value::Number(_) => Some(false),
        Value::Array(a) if a.len() == 1 => as_bool(&a[0]),
        _ => None,
    }
}

/// `getAsLong`: numbers (fractions truncate toward zero) and strings `Long.parseLong` accepts.
pub fn as_i64(v: &Value) -> Option<i64> {
    match v {
        Value::Number(n) => {
            if let Some(i) = n.as_i64() {
                Some(i)
            } else if n.as_u64().is_some() {
                None // above i64::MAX
            } else {
                n.as_f64().and_then(f64_to_i64)
            }
        }
        Value::String(s) => parse_java_long(s),
        Value::Array(a) if a.len() == 1 => as_i64(&a[0]),
        _ => None,
    }
}

/// `getAsInt`, range-checked.
pub fn as_i32(v: &Value) -> Option<i32> {
    as_i64(v).and_then(|i| i32::try_from(i).ok())
}

/// `getAsDouble`: numbers and strings `Double.parseDouble` accepts.
pub fn as_f64(v: &Value) -> Option<f64> {
    match v {
        Value::Number(n) => n.as_f64(),
        Value::String(_) => mmcl_util::json::as_gson_f64(v),
        Value::Array(a) if a.len() == 1 => as_f64(&a[0]),
        _ => None,
    }
}

fn f64_to_i64(f: f64) -> Option<i64> {
    if !f.is_finite() {
        return None;
    }
    let t = f.trunc();
    // i64::MAX is not representable; 2^63 is the first value out of range.
    if (-9_223_372_036_854_775_808.0..9_223_372_036_854_775_808.0).contains(&t) {
        Some(t as i64)
    } else {
        None
    }
}

/// `Long.parseLong`: an optional sign then ASCII digits, nothing else (no whitespace).
fn parse_java_long(s: &str) -> Option<i64> {
    let digits = s.strip_prefix(['+', '-']).unwrap_or(s);
    if digits.is_empty() || !digits.bytes().all(|b| b.is_ascii_digit()) {
        return None;
    }
    s.parse::<i64>().ok()
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn string_coercion() {
        assert_eq!(as_string(&json!("abc")).as_deref(), Some("abc"));
        assert_eq!(as_string(&json!(42)).as_deref(), Some("42"));
        assert_eq!(as_string(&json!(2.5)).as_deref(), Some("2.5"));
        assert_eq!(as_string(&json!(true)).as_deref(), Some("true"));
        assert_eq!(as_string(&json!(["x"])).as_deref(), Some("x"));
        assert_eq!(as_string(&json!([])), None);
        assert_eq!(as_string(&json!(["a", "b"])), None);
        assert_eq!(as_string(&json!({})), None);
        assert_eq!(as_string(&json!([null])), None);
    }

    #[test]
    fn bool_coercion() {
        assert_eq!(as_bool(&json!(true)), Some(true));
        assert_eq!(as_bool(&json!("TRUE")), Some(true));
        assert_eq!(as_bool(&json!("abc")), Some(false));
        assert_eq!(as_bool(&json!("42")), Some(false));
        assert_eq!(as_bool(&json!(42)), Some(false));
        assert_eq!(as_bool(&json!([true])), Some(true));
        assert_eq!(as_bool(&json!([true, false])), None);
        assert_eq!(as_bool(&json!({})), None);
    }

    #[test]
    fn integer_coercion() {
        assert_eq!(as_i32(&json!(42)), Some(42));
        assert_eq!(as_i32(&json!(2.5)), Some(2));
        assert_eq!(as_i32(&json!(-2.5)), Some(-2));
        assert_eq!(as_i32(&json!("42")), Some(42));
        assert_eq!(as_i32(&json!("+42")), Some(42));
        assert_eq!(as_i32(&json!(" 42")), None);
        assert_eq!(as_i32(&json!("2.5")), None);
        assert_eq!(as_i32(&json!("abc")), None);
        assert_eq!(as_i32(&json!(true)), None);
        assert_eq!(as_i32(&json!([42])), Some(42));
        assert_eq!(as_i32(&json!([1, 2])), None);
        assert_eq!(as_i32(&json!({})), None);
        // Range-checked instead of Gson's truncation.
        assert_eq!(as_i32(&json!(9_000_000_000i64)), None);
        assert_eq!(as_i64(&json!(9_000_000_000i64)), Some(9_000_000_000));
        assert_eq!(as_i64(&json!(u64::MAX)), None);
        assert_eq!(as_i64(&json!(1e300)), None);
    }

    #[test]
    fn double_coercion() {
        assert_eq!(as_f64(&json!(2.5)), Some(2.5));
        assert_eq!(as_f64(&json!(42)), Some(42.0));
        assert_eq!(as_f64(&json!("42")), Some(42.0));
        assert_eq!(as_f64(&json!("2.5")), Some(2.5));
        assert_eq!(as_f64(&json!("abc")), None);
        assert_eq!(as_f64(&json!(true)), None);
        assert_eq!(as_f64(&json!([2.5])), Some(2.5));
        assert_eq!(as_f64(&json!({})), None);
    }
}
