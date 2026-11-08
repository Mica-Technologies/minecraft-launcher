// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! JSON helpers: `JSONUtilities`, `JsonHelper`, `FileUtilities` and `StringOrArray`.
//!
//! Parsing is Gson-compatible: strict JSON first, then a lenient (`json5`) fallback for the
//! comments, single quotes and unquoted names Gson accepts in hand-edited files. Printing
//! matches Gson's output (two-space pretty printing, HTML-safe escaping), so files the Rust
//! build writes diff cleanly against the Java launcher's. Getters follow Gson's coercion:
//! a JSON `null` counts as absent, `getAsString` on a number or boolean gives its text, and
//! `getAsInt` on a numeric string parses it.

use std::io;
use std::path::Path;

use serde_json::{Map, Value};

pub use mmcl_manifest::StringOrArray;
pub use mmcl_manifest::gson::Syntax;

/// Files are read and written as UTF-8 (`FileUtilities.persistenceCharset`).
pub const PERSISTENCE_CHARSET: &str = "UTF-8";

/// A JSON helper failure.
#[derive(Debug, thiserror::Error)]
pub enum JsonError {
    /// Neither the strict nor the lenient parser accepted the text.
    #[error("invalid JSON: {0}")]
    Syntax(String),
    /// The top-level value had the wrong type.
    #[error("expected a JSON {expected}")]
    WrongTopLevel { expected: &'static str },
    /// A required key was absent, `null`, or of the wrong type.
    #[error("Required JSON key '{key}' is missing or {problem}")]
    Required { key: String, problem: &'static str },
    /// Reading or writing a file failed.
    #[error(transparent)]
    Io(#[from] io::Error),
}

// ---- parsing and printing ---------------------------------------------------------------

/// Parses JSON text as Gson does (strict, then lenient), reporting which syntax was needed.
pub fn parse_with_syntax(text: &str) -> Result<(Value, Syntax), JsonError> {
    mmcl_manifest::gson::parse_lenient(text).map_err(|e| match e {
        mmcl_manifest::ManifestParseError::Syntax { message } => JsonError::Syntax(message),
        other => JsonError::Syntax(other.to_string()),
    })
}

/// Parses JSON text as Gson does. A leading byte-order mark is ignored.
pub fn parse(text: &str) -> Result<Value, JsonError> {
    parse_with_syntax(text).map(|(v, _)| v)
}

/// `JSONUtilities.stringToObject`.
pub fn string_to_object(text: &str) -> Result<Map<String, Value>, JsonError> {
    match parse(text)? {
        Value::Object(m) => Ok(m),
        _ => Err(JsonError::WrongTopLevel { expected: "object" }),
    }
}

/// `JSONUtilities.stringToArray`.
pub fn string_to_array(text: &str) -> Result<Vec<Value>, JsonError> {
    match parse(text)? {
        Value::Array(a) => Ok(a),
        _ => Err(JsonError::WrongTopLevel { expected: "array" }),
    }
}

/// Compact output, as `new Gson().toJson(...)`.
pub fn to_compact_string(value: &Value) -> String {
    mmcl_manifest::gson::to_compact_string(value)
}

/// Pretty output (two-space indent), as Gson's `setPrettyPrinting()`.
pub fn to_pretty_string(value: &Value) -> String {
    mmcl_manifest::gson::to_pretty_string(value)
}

// ---- files -------------------------------------------------------------------------------

/// `FileUtilities.readAsString`: the whole file as UTF-8.
pub fn read_string(path: &Path) -> io::Result<String> {
    std::fs::read_to_string(path)
}

/// `FileUtilities.writeFromString`: replaces the whole file with `s` (UTF-8), creating the
/// parent folder if needed.
pub fn write_string(path: &Path, s: &str) -> io::Result<()> {
    if let Some(parent) = path.parent()
        && !parent.as_os_str().is_empty()
    {
        std::fs::create_dir_all(parent)?;
    }
    std::fs::write(path, s.as_bytes())
}

/// Reads a whole file as JSON (Gson-lenient).
pub fn read_json(path: &Path) -> Result<Value, JsonError> {
    parse(&read_string(path)?)
}

/// `FileUtilities.readAsJsonObject`.
pub fn read_json_object(path: &Path) -> Result<Map<String, Value>, JsonError> {
    string_to_object(&read_string(path)?)
}

/// `FileUtilities.readAsJsonArray`.
pub fn read_json_array(path: &Path) -> Result<Vec<Value>, JsonError> {
    string_to_array(&read_string(path)?)
}

/// `FileUtilities.writeFromJson`: Gson pretty printing.
pub fn write_json(path: &Path, value: &Value) -> Result<(), JsonError> {
    write_string(path, &to_pretty_string(value))?;
    Ok(())
}

// ---- null-safe getters (`JsonHelper`) ---------------------------------------------------

/// The value under `key` unless it is absent or JSON `null`.
fn present<'a>(obj: Option<&'a Map<String, Value>>, key: &str) -> Option<&'a Value> {
    obj?.get(key).filter(|v| !v.is_null())
}

/// Gson `getAsString`: strings as-is, numbers and booleans as their text, a single-element
/// array as its element. Objects and other arrays have no string form.
pub fn as_gson_string(v: &Value) -> Option<String> {
    match v {
        Value::String(s) => Some(s.clone()),
        Value::Number(n) => Some(n.to_string()),
        Value::Bool(b) => Some(b.to_string()),
        Value::Array(a) if a.len() == 1 => as_gson_string(&a[0]),
        _ => None,
    }
}

/// Gson `getAsLong`: numbers (truncated toward zero, as `Number.longValue`) and numeric
/// strings.
pub fn as_gson_i64(v: &Value) -> Option<i64> {
    match v {
        Value::Number(n) => n
            .as_i64()
            .or_else(|| n.as_u64().map(|u| u as i64))
            .or_else(|| n.as_f64().filter(|f| f.is_finite()).map(|f| f as i64)),
        Value::String(s) => {
            let t = s.trim();
            t.parse::<i64>().ok().or_else(|| {
                mmcl_manifest::lenient::parse_java_double(t)
                    .filter(|f| f.is_finite())
                    .map(|f| f as i64)
            })
        }
        Value::Array(a) if a.len() == 1 => as_gson_i64(&a[0]),
        _ => None,
    }
}

/// Gson `getAsDouble`: numbers and numeric strings.
pub fn as_gson_f64(v: &Value) -> Option<f64> {
    match v {
        Value::Number(n) => n.as_f64(),
        Value::String(s) => mmcl_manifest::lenient::parse_java_double(s),
        Value::Array(a) if a.len() == 1 => as_gson_f64(&a[0]),
        _ => None,
    }
}

/// Gson `getAsBoolean`: booleans, and strings through `Boolean.parseBoolean` (only a
/// case-insensitive `"true"`).
pub fn as_gson_bool(v: &Value) -> Option<bool> {
    match v {
        Value::Bool(b) => Some(*b),
        Value::String(s) => Some(s.eq_ignore_ascii_case("true")),
        Value::Array(a) if a.len() == 1 => as_gson_bool(&a[0]),
        _ => None,
    }
}

/// `JsonHelper.getString`. Absent, `null` or uncoercible values give `default`.
pub fn get_string(obj: Option<&Map<String, Value>>, key: &str, default: &str) -> String {
    present(obj, key)
        .and_then(as_gson_string)
        .unwrap_or_else(|| default.to_string())
}

/// `JsonHelper.getString` with an optional default.
pub fn get_opt_string(obj: Option<&Map<String, Value>>, key: &str) -> Option<String> {
    present(obj, key).and_then(as_gson_string)
}

/// `JsonHelper.getRequiredString`.
pub fn get_required_string(
    obj: Option<&Map<String, Value>>,
    key: &str,
) -> Result<String, JsonError> {
    present(obj, key)
        .and_then(as_gson_string)
        .ok_or_else(|| JsonError::Required {
            key: key.to_string(),
            problem: "null",
        })
}

/// `JsonHelper.getInt`: in-range integral values; anything else gives `default`.
pub fn get_int(obj: Option<&Map<String, Value>>, key: &str, default: i32) -> i32 {
    present(obj, key)
        .and_then(as_gson_i64)
        .and_then(|v| i32::try_from(v).ok())
        .unwrap_or(default)
}

/// `JsonHelper.getLong`.
pub fn get_long(obj: Option<&Map<String, Value>>, key: &str, default: i64) -> i64 {
    present(obj, key).and_then(as_gson_i64).unwrap_or(default)
}

/// `JsonHelper.getBoolean`.
pub fn get_bool(obj: Option<&Map<String, Value>>, key: &str, default: bool) -> bool {
    present(obj, key).and_then(as_gson_bool).unwrap_or(default)
}

/// `JsonHelper.getDouble`.
pub fn get_double(obj: Option<&Map<String, Value>>, key: &str, default: f64) -> f64 {
    present(obj, key).and_then(as_gson_f64).unwrap_or(default)
}

/// `JsonHelper.getJsonObject`: the object under `key`, or `None`.
pub fn get_object<'a>(
    obj: Option<&'a Map<String, Value>>,
    key: &str,
) -> Option<&'a Map<String, Value>> {
    present(obj, key).and_then(Value::as_object)
}

/// `JsonHelper.getRequiredJsonObject`.
pub fn get_required_object<'a>(
    obj: Option<&'a Map<String, Value>>,
    key: &str,
) -> Result<&'a Map<String, Value>, JsonError> {
    get_object(obj, key).ok_or_else(|| JsonError::Required {
        key: key.to_string(),
        problem: "not a JSON object",
    })
}

/// `JsonHelper.getJsonArray`: the array under `key`, or `None`.
pub fn get_array<'a>(obj: Option<&'a Map<String, Value>>, key: &str) -> Option<&'a Vec<Value>> {
    present(obj, key).and_then(Value::as_array)
}

/// `JsonHelper.getRequiredJsonArray`.
pub fn get_required_array<'a>(
    obj: Option<&'a Map<String, Value>>,
    key: &str,
) -> Result<&'a Vec<Value>, JsonError> {
    get_array(obj, key).ok_or_else(|| JsonError::Required {
        key: key.to_string(),
        problem: "not a JSON array",
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde::{Deserialize, Serialize};
    use serde_json::json;

    #[derive(Serialize, Deserialize, Default)]
    struct Holder {
        #[serde(
            rename = "packLogoURL",
            default,
            skip_serializing_if = "Option::is_none"
        )]
        pack_logo_url: Option<StringOrArray>,
    }

    // ---- StringOrArrayTest ----

    #[test]
    fn deserializes_bare_string_to_singleton_list() {
        let h: Holder = serde_json::from_str(r#"{"packLogoURL":"https://a/logo.png"}"#).unwrap();
        let v = h.pack_logo_url.unwrap();
        assert_eq!(v.all(), ["https://a/logo.png"]);
        assert_eq!(v.first(), Some("https://a/logo.png"));
        assert!(!v.is_empty());
    }

    #[test]
    fn deserializes_array_preserving_order() {
        let h: Holder =
            serde_json::from_str(r#"{"packLogoURL":["https://a/logo.png","https://b/logo.png"]}"#)
                .unwrap();
        let v = h.pack_logo_url.unwrap();
        assert_eq!(v.all(), ["https://a/logo.png", "https://b/logo.png"]);
        assert_eq!(v.first(), Some("https://a/logo.png"));
    }

    #[test]
    fn absent_field_leaves_none_and_empty_string_is_empty() {
        let absent: Holder = serde_json::from_str("{}").unwrap();
        assert!(absent.pack_logo_url.is_none());
        let blank: Holder = serde_json::from_str(r#"{"packLogoURL":""}"#).unwrap();
        let v = blank.pack_logo_url.unwrap();
        assert!(v.is_empty());
        assert_eq!(v.first(), None);
    }

    #[test]
    fn normalization_trims_and_drops_blanks() {
        let v = StringOrArray::of(["  https://a/logo.png  ", "", "   ", "https://b/x"]);
        assert_eq!(v.all(), ["https://a/logo.png", "https://b/x"]);
        let from_json = StringOrArray::from_value(&json!(["a", null, 3, true, " "]));
        assert_eq!(from_json.all(), ["a", "3", "true"]);
    }

    #[test]
    fn serializes_single_as_bare_string_and_many_as_array() {
        let single = Holder {
            pack_logo_url: Some(StringOrArray::of(["https://a/logo.png"])),
        };
        assert_eq!(
            serde_json::to_string(&single).unwrap(),
            r#"{"packLogoURL":"https://a/logo.png"}"#
        );
        let many = Holder {
            pack_logo_url: Some(StringOrArray::of([
                "https://a/logo.png",
                "https://b/logo.png",
            ])),
        };
        assert_eq!(
            serde_json::to_string(&many).unwrap(),
            r#"{"packLogoURL":["https://a/logo.png","https://b/logo.png"]}"#
        );
        assert_eq!(
            serde_json::to_value(StringOrArray::default()).unwrap(),
            Value::Null
        );
    }

    #[test]
    fn round_trips_array_through_json() {
        let original = StringOrArray::of(["https://a", "https://b", "https://c"]);
        let h = Holder {
            pack_logo_url: Some(original.clone()),
        };
        let back: Holder = serde_json::from_str(&serde_json::to_string(&h).unwrap()).unwrap();
        assert_eq!(back.pack_logo_url, Some(original));
    }

    #[test]
    fn value_equality_ignores_string_vs_singleton_array_origin() {
        let a: StringOrArray = serde_json::from_str(r#""https://a""#).unwrap();
        let b: StringOrArray = serde_json::from_str(r#"["https://a"]"#).unwrap();
        assert_eq!(a, b);
        use std::hash::{BuildHasher, RandomState};
        let s = RandomState::new();
        assert_eq!(s.hash_one(&a), s.hash_one(&b));
    }

    // ---- JSONUtilitiesTest ----

    #[test]
    fn string_to_object_parses_json_object() {
        let obj = string_to_object(r#"{"name":"Test Pack","version":2}"#).unwrap();
        assert_eq!(get_string(Some(&obj), "name", ""), "Test Pack");
        assert_eq!(get_int(Some(&obj), "version", 0), 2);
    }

    #[test]
    fn string_to_array_parses_json_array() {
        let arr = string_to_array(r#"["a","b","c"]"#).unwrap();
        assert_eq!(arr.len(), 3);
        assert_eq!(arr[1], "b");
    }

    #[test]
    fn object_to_string_and_back_round_trips_exactly() {
        let original = json!({"packName": "Example", "minRam": 4096});
        let serialized = to_compact_string(&original);
        let reparsed = string_to_object(&serialized).unwrap();
        assert_eq!(Value::Object(reparsed), original);
    }

    #[test]
    fn array_to_string_and_back_round_trips_exactly() {
        let original = json!(["one", "two"]);
        let reparsed = string_to_array(&to_compact_string(&original)).unwrap();
        assert_eq!(Value::Array(reparsed), original);
    }

    #[test]
    fn pretty_produces_multi_line_output_unlike_compact() {
        let obj = json!({"a": 1, "b": 2});
        assert_eq!(to_compact_string(&obj).lines().count(), 1);
        assert!(to_pretty_string(&obj).lines().count() > 1);
        assert_eq!(to_pretty_string(&obj), "{\n  \"a\": 1,\n  \"b\": 2\n}");
    }

    #[test]
    fn both_printers_parse_back_identically() {
        let v = json!({"packLogoURL": "https://a/logo.png"});
        assert_eq!(
            parse(&to_compact_string(&v)).unwrap(),
            parse(&to_pretty_string(&v)).unwrap()
        );
    }

    #[test]
    fn lenient_fallback_accepts_gson_extensions() {
        let (v, syntax) = parse_with_syntax("{ // comment\n name: 'x', }").unwrap();
        assert_eq!(syntax, Syntax::Lenient);
        assert_eq!(v["name"], "x");
        assert_eq!(parse("{\"a\":1}").map(|v| v["a"].clone()).unwrap(), 1);
        assert!(parse("{nope").is_err());
        assert!(matches!(
            string_to_object("[1]"),
            Err(JsonError::WrongTopLevel { expected: "object" })
        ));
    }

    // ---- JsonHelper ----

    #[test]
    fn getters_follow_gson_coercion() {
        let obj = string_to_object(
            r#"{"n":5,"s":"7","b":"TRUE","f":2.5,"nul":null,"o":{"x":1},"a":[1,2],"str":true}"#,
        )
        .unwrap();
        let o = Some(&obj);
        assert_eq!(get_string(o, "n", "d"), "5");
        assert_eq!(get_string(o, "str", "d"), "true");
        assert_eq!(get_string(o, "nul", "d"), "d");
        assert_eq!(get_string(o, "missing", "d"), "d");
        assert_eq!(get_int(o, "s", 0), 7);
        assert_eq!(get_int(o, "f", 0), 2);
        assert_eq!(get_long(o, "n", 0), 5);
        assert!(get_bool(o, "b", false));
        assert_eq!(get_double(o, "f", 0.0), 2.5);
        assert_eq!(get_double(o, "s", 0.0), 7.0);
        assert!(get_object(o, "o").is_some());
        assert!(get_object(o, "a").is_none());
        assert_eq!(get_array(o, "a").map(Vec::len), Some(2));
        assert_eq!(get_string(None, "n", "d"), "d");
    }

    #[test]
    fn required_getters_report_the_java_messages() {
        let obj = string_to_object(r#"{"nul":null,"s":"x"}"#).unwrap();
        let o = Some(&obj);
        assert_eq!(get_required_string(o, "s").unwrap(), "x");
        assert_eq!(
            get_required_string(o, "nul").unwrap_err().to_string(),
            "Required JSON key 'nul' is missing or null"
        );
        assert_eq!(
            get_required_object(o, "s").unwrap_err().to_string(),
            "Required JSON key 's' is missing or not a JSON object"
        );
        assert_eq!(
            get_required_array(o, "missing").unwrap_err().to_string(),
            "Required JSON key 'missing' is missing or not a JSON array"
        );
    }

    #[test]
    fn files_round_trip_as_utf8() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("sub").join("x.json");
        let v = json!({"name": "caf\u{e9}", "list": [1, 2]});
        write_json(&path, &v).unwrap();
        assert_eq!(read_json(&path).unwrap(), v);
        assert_eq!(Value::Object(read_json_object(&path).unwrap()), v);
        write_string(&path, "[1]").unwrap();
        assert_eq!(read_json_array(&path).unwrap().len(), 1);
        assert!(matches!(
            read_json(&dir.path().join("missing.json")),
            Err(JsonError::Io(_))
        ));
    }
}
