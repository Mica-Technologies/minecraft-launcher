// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Gson-compatible JSON reading and writing.
//!
//! * [`parse_lenient`]: strict `serde_json` first, then a `json5` retry (comments, single
//!   quotes, unquoted keys, trailing commas), mirroring Gson's lenient reader closely enough
//!   for every real manifest. Duplicate keys keep the last value, in the position of the
//!   first, like Gson's `JsonObject`.
//! * [`to_pretty_string`] / [`to_compact_string`]: byte-for-byte what the Java launcher's
//!   `JSONUtilities.getPrettyGson()` / `getGson()` print for a `JsonObject`: two-space
//!   indent, `"key": value`, `[]` / `{}` for empty containers, no trailing newline, object
//!   members whose value is `null` left out (Gson is not built with `serializeNulls`), and Gson's
//!   default HTML-safe escaping (`<`, `>`, `&`, `=`, `'` as `<` and so on, plus U+2028 and
//!   U+2029). Matching it keeps a manifest saved by either launcher's editor diff-free.
//! * [`compat_problems`]: what Gson's reflective binding of `GameModPack` would reject, so
//!   the editor can refuse to save a manifest the Java launcher cannot load.

use serde_json::{Map, Number, Value};

use crate::error::ManifestParseError;

/// Which parser accepted a manifest body.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Syntax {
    /// Standard JSON.
    Standard,
    /// Only the lenient `json5` fallback accepted it. Callers log
    /// [`crate::LOG_KEY_LENIENT_SYNTAX`] as a warning.
    Lenient,
}

/// Parses JSON text the way the Java launcher's Gson does, returning the value and which
/// syntax was needed. A leading UTF-8 byte-order mark is ignored.
pub fn parse_lenient(text: &str) -> Result<(Value, Syntax), ManifestParseError> {
    let text = text.strip_prefix('\u{feff}').unwrap_or(text);
    match serde_json::from_str::<Value>(text) {
        Ok(v) => Ok((v, Syntax::Standard)),
        Err(strict) => match json5::from_str::<Value>(text) {
            Ok(v) => Ok((v, Syntax::Lenient)),
            Err(_) => Err(ManifestParseError::Syntax {
                message: strict.to_string(),
            }),
        },
    }
}

/// Parses JSON text leniently and requires an object at the top.
pub fn parse_object(text: &str) -> Result<(Map<String, Value>, Syntax), ManifestParseError> {
    match parse_lenient(text)? {
        (Value::Object(map), syntax) => Ok((map, syntax)),
        _ => Err(ManifestParseError::NotAnObject),
    }
}

/// Gson pretty printing (`new GsonBuilder().setPrettyPrinting().create().toJson(element)`).
pub fn to_pretty_string(value: &Value) -> String {
    let mut out = String::new();
    write_value(&mut out, value, 0, true);
    out
}

/// Gson compact printing (`new Gson().toJson(element)`), HTML-safe escaping included.
pub fn to_compact_string(value: &Value) -> String {
    let mut out = String::new();
    write_value(&mut out, value, 0, false);
    out
}

fn newline_indent(out: &mut String, depth: usize) {
    out.push('\n');
    for _ in 0..depth {
        out.push_str("  ");
    }
}

fn write_value(out: &mut String, value: &Value, depth: usize, pretty: bool) {
    match value {
        Value::Null => out.push_str("null"),
        Value::Bool(b) => out.push_str(if *b { "true" } else { "false" }),
        Value::Number(n) => write_number(out, n),
        Value::String(s) => write_string(out, s),
        Value::Array(items) => {
            out.push('[');
            for (i, item) in items.iter().enumerate() {
                if i > 0 {
                    out.push(',');
                }
                if pretty {
                    newline_indent(out, depth + 1);
                }
                write_value(out, item, depth + 1, pretty);
            }
            if pretty && !items.is_empty() {
                newline_indent(out, depth);
            }
            out.push(']');
        }
        Value::Object(map) => {
            // Gson is built without `serializeNulls()`, so its writer drops an object member
            // whose value is `null` (name included). Array elements still print as `null`.
            let members: Vec<(&String, &Value)> =
                map.iter().filter(|(_, v)| !v.is_null()).collect();
            out.push('{');
            for (i, (key, item)) in members.iter().enumerate() {
                if i > 0 {
                    out.push(',');
                }
                if pretty {
                    newline_indent(out, depth + 1);
                }
                write_string(out, key);
                out.push_str(if pretty { ": " } else { ":" });
                write_value(out, item, depth + 1, pretty);
            }
            if pretty && !members.is_empty() {
                newline_indent(out, depth);
            }
            out.push('}');
        }
    }
}

fn write_number(out: &mut String, n: &Number) {
    // Integers print as-is. Gson keeps a parsed number's source text; serde_json keeps the
    // value, so `1.50` prints as `1.5`. No live manifest stores a fractional number.
    out.push_str(&n.to_string());
}

/// Writes a JSON string literal with Gson's HTML-safe escaping.
pub fn write_string(out: &mut String, s: &str) {
    out.push('"');
    for c in s.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\t' => out.push_str("\\t"),
            '\u{8}' => out.push_str("\\b"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\u{c}' => out.push_str("\\f"),
            '<' | '>' | '&' | '=' | '\'' | '\u{2028}' | '\u{2029}' => {
                out.push_str(&format!("\\u{:04x}", c as u32));
            }
            c if (c as u32) < 0x20 => out.push_str(&format!("\\u{:04x}", c as u32)),
            c => out.push(c),
        }
    }
    out.push('"');
}

// ---------------------------------------------------------------------------------------------
// Gson binding compatibility
// ---------------------------------------------------------------------------------------------

/// The Java field type a manifest key binds to.
#[derive(Clone, Copy)]
enum Ty {
    Str,
    Bool,
    Int,
    StrOrArray,
    StrList,
    ObjList(&'static [(&'static str, Ty)]),
}

// Each list binds to a different Java class, and Gson ignores keys its class has no field
// for: `packMods` is `GameMod` (name + side flags), `packConfigs` / `packInitialFiles` are
// `GameAsset` (side flags, no name), resource and shader packs are plain `ManagedGameFile`.

/// `GameMod`.
const MOD_FIELDS: &[(&str, Ty)] = &[
    ("name", Ty::Str),
    ("remote", Ty::Str),
    ("local", Ty::Str),
    ("sha1", Ty::Str),
    ("md5", Ty::Str),
    ("sha256", Ty::Str),
    ("clientReq", Ty::Bool),
    ("serverReq", Ty::Bool),
];

/// `GameAsset`.
const ASSET_FIELDS: &[(&str, Ty)] = &[
    ("remote", Ty::Str),
    ("local", Ty::Str),
    ("sha1", Ty::Str),
    ("md5", Ty::Str),
    ("sha256", Ty::Str),
    ("clientReq", Ty::Bool),
    ("serverReq", Ty::Bool),
];

/// `ManagedGameFile`.
const FILE_FIELDS: &[(&str, Ty)] = &[
    ("remote", Ty::Str),
    ("local", Ty::Str),
    ("sha1", Ty::Str),
    ("md5", Ty::Str),
    ("sha256", Ty::Str),
];

const ACK_FIELDS: &[(&str, Ty)] = &[
    ("fileSha256", Ty::Str),
    ("innerSha256", Ty::Str),
    ("kind", Ty::Str),
    ("locator", Ty::Str),
    ("reason", Ty::Str),
];

const NEWS_FIELDS: &[(&str, Ty)] = &[
    ("id", Ty::Str),
    ("title", Ty::Str),
    ("body", Ty::Str),
    ("date", Ty::Str),
    ("type", Ty::Str),
    ("url", Ty::Str),
    ("pinned", Ty::Bool),
    ("expires", Ty::Str),
];

const LINK_FIELDS: &[(&str, Ty)] = &[
    ("title", Ty::Str),
    ("url", Ty::Str),
    ("description", Ty::Str),
];

const PACK_FIELDS: &[(&str, Ty)] = &[
    ("packName", Ty::Str),
    ("packVersion", Ty::Str),
    ("packURL", Ty::Str),
    ("packUnstable", Ty::Bool),
    ("packCustomDiscordRpc", Ty::Bool),
    ("packLogoURL", Ty::StrOrArray),
    ("packLogoSha1", Ty::Str),
    ("packBackgroundURL", Ty::StrOrArray),
    ("packBackgroundSha1", Ty::Str),
    ("packMinRAMGB", Ty::Str),
    ("packForgeURL", Ty::Str),
    ("packForgeHash", Ty::Str),
    ("packModLoader", Ty::Str),
    ("packModLoaderURL", Ty::Str),
    ("packModLoaderHash", Ty::Str),
    ("packDefaultServerHost", Ty::Str),
    ("packDefaultServerPort", Ty::Int),
    ("packDefaultServerName", Ty::Str),
    ("packImportedSkipSync", Ty::Bool),
    ("packScanExclusions", Ty::StrList),
    ("packScanAcknowledgements", Ty::ObjList(ACK_FIELDS)),
    ("packNews", Ty::ObjList(NEWS_FIELDS)),
    ("packLinks", Ty::ObjList(LINK_FIELDS)),
    ("packMods", Ty::ObjList(MOD_FIELDS)),
    ("packConfigs", Ty::ObjList(ASSET_FIELDS)),
    ("packResourcePacks", Ty::ObjList(FILE_FIELDS)),
    ("packShaderPacks", Ty::ObjList(FILE_FIELDS)),
    ("packInitialFiles", Ty::ObjList(ASSET_FIELDS)),
    // `GameModPack.manifestUrl` is a plain (non-transient) field, so Gson binds it too; the
    // fetcher overwrites it after loading.
    ("manifestUrl", Ty::Str),
];

/// Whether Gson's `JsonElement.getAsString` succeeds: any primitive, or an array holding
/// exactly one element it succeeds on. (`null` elements are skipped before this is asked.)
fn gson_as_string_ok(v: &Value) -> bool {
    match v {
        Value::Object(_) | Value::Null => false,
        Value::Array(items) => items.len() == 1 && gson_as_string_ok(&items[0]),
        _ => true,
    }
}

fn token(v: &Value) -> &'static str {
    match v {
        Value::Null => "NULL",
        Value::Bool(_) => "BOOLEAN",
        Value::Number(_) => "NUMBER",
        Value::String(_) => "STRING",
        Value::Array(_) => "BEGIN_ARRAY",
        Value::Object(_) => "BEGIN_OBJECT",
    }
}

/// Reports, in document order, every value the Java launcher's Gson binding would reject
/// when reading this manifest (for example an object where a string belongs, a fractional
/// server port, or a list entry that is not an object). Empty means Java can load it.
///
/// The Rust parser accepts all of these (it drops what it cannot use), so this check exists
/// for the editor: a manifest it saves must load in both launchers.
pub fn compat_problems(value: &Value) -> Vec<String> {
    let mut problems = Vec::new();
    match value {
        Value::Object(map) => check_object(map, PACK_FIELDS, "$", &mut problems),
        other => problems.push(format!(
            "Expected BEGIN_OBJECT but was {} at path $",
            token(other)
        )),
    }
    problems
}

fn check_object(
    map: &Map<String, Value>,
    fields: &[(&str, Ty)],
    path: &str,
    problems: &mut Vec<String>,
) {
    for (key, ty) in fields {
        if let Some(v) = map.get(*key) {
            check_value(v, *ty, &format!("{path}.{key}"), problems);
        }
    }
}

fn check_value(v: &Value, ty: Ty, path: &str, problems: &mut Vec<String>) {
    if v.is_null() {
        return;
    }
    match ty {
        Ty::Str => {
            if v.is_array() || v.is_object() {
                problems.push(format!(
                    "Expected a string but was {} at path {path}",
                    token(v)
                ));
            }
        }
        Ty::Bool => {
            if !(v.is_boolean() || v.is_string()) {
                problems.push(format!(
                    "Expected a boolean but was {} at path {path}",
                    token(v)
                ));
            }
        }
        Ty::Int => match v {
            Value::Number(n) => {
                if crate::lenient::number_to_i32(n).is_none() {
                    problems.push(format!("Expected an int but was {n} at path {path}"));
                }
            }
            Value::String(s) => {
                if crate::lenient::str_to_i32(s).is_none() {
                    problems.push(format!("For input string: \"{s}\" at path {path}"));
                }
            }
            other => problems.push(format!(
                "Expected an int but was {} at path {path}",
                token(other)
            )),
        },
        Ty::StrOrArray => match v {
            Value::Object(_) => problems.push(format!(
                "Expected a string or an array of strings but was BEGIN_OBJECT at path {path}"
            )),
            Value::Array(items) => {
                for (i, item) in items.iter().enumerate() {
                    if !item.is_null() && !gson_as_string_ok(item) {
                        problems.push(format!(
                            "Expected a string but was {} at path {path}[{i}]",
                            token(item)
                        ));
                    }
                }
            }
            _ => {}
        },
        Ty::StrList => match v {
            Value::Array(items) => {
                for (i, item) in items.iter().enumerate() {
                    check_value(item, Ty::Str, &format!("{path}[{i}]"), problems);
                }
            }
            other => problems.push(format!(
                "Expected BEGIN_ARRAY but was {} at path {path}",
                token(other)
            )),
        },
        Ty::ObjList(fields) => match v {
            Value::Array(items) => {
                for (i, item) in items.iter().enumerate() {
                    let item_path = format!("{path}[{i}]");
                    match item {
                        Value::Null => {}
                        Value::Object(map) => check_object(map, fields, &item_path, problems),
                        other => problems.push(format!(
                            "Expected BEGIN_OBJECT but was {} at path {item_path}",
                            token(other)
                        )),
                    }
                }
            }
            other => problems.push(format!(
                "Expected BEGIN_ARRAY but was {} at path {path}",
                token(other)
            )),
        },
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn pretty_output_matches_gson_layout() {
        let v = json!({"a": 1, "b": [], "c": {}, "d": ["x", true, null], "e": {"f": "g"}});
        let expected = "{\n  \"a\": 1,\n  \"b\": [],\n  \"c\": {},\n  \"d\": [\n    \"x\",\n    true,\n    null\n  ],\n  \"e\": {\n    \"f\": \"g\"\n  }\n}";
        assert_eq!(to_pretty_string(&v), expected);
    }

    #[test]
    fn null_object_members_are_left_out_like_gson() {
        let v = json!({"a": null, "b": 1, "c": [null], "d": {"e": null}, "f": null});
        assert_eq!(to_compact_string(&v), "{\"b\":1,\"c\":[null],\"d\":{}}");
        assert_eq!(
            to_pretty_string(&v),
            "{\n  \"b\": 1,\n  \"c\": [\n    null\n  ],\n  \"d\": {}\n}"
        );
        assert_eq!(to_pretty_string(&json!({"only": null})), "{}");
    }

    #[test]
    fn compat_checks_each_list_against_its_own_java_class() {
        // Resource and shader packs bind to ManagedGameFile, which has no name or side flags,
        // and configs / initial files to GameAsset, which has no name: Gson ignores those keys.
        let ignored = json!({
            "packResourcePacks": [{"name": {"x": 1}, "clientReq": 7, "serverReq": [1]}],
            "packShaderPacks": [{"clientReq": {"a": 1}}],
            "packConfigs": [{"name": ["a", "b"]}],
            "packInitialFiles": [{"name": {}}]
        });
        assert!(
            compat_problems(&ignored).is_empty(),
            "{:?}",
            compat_problems(&ignored)
        );
        let rejected = json!({
            "packConfigs": [{"clientReq": 7}],
            "packInitialFiles": [{"serverReq": {}}],
            "packMods": [{"name": {}}],
            "manifestUrl": {"a": 1}
        });
        assert_eq!(
            compat_problems(&rejected).len(),
            4,
            "{:?}",
            compat_problems(&rejected)
        );
    }

    #[test]
    fn compat_string_or_array_follows_get_as_string() {
        assert!(compat_problems(&json!({"packLogoURL": [["one"], "two", null]})).is_empty());
        assert_eq!(
            compat_problems(&json!({"packLogoURL": [["a", "b"], [null], {}]})).len(),
            3
        );
    }

    #[test]
    fn compact_output_has_no_whitespace() {
        let v = json!({"a": [1, 2], "b": "c"});
        assert_eq!(to_compact_string(&v), "{\"a\":[1,2],\"b\":\"c\"}");
    }

    #[test]
    fn html_characters_are_escaped_like_gson() {
        let v = json!("a<b>&c='d'\u{2028}\u{2029}");
        assert_eq!(
            to_compact_string(&v),
            "\"a\\u003cb\\u003e\\u0026c\\u003d\\u0027d\\u0027\\u2028\\u2029\""
        );
    }

    #[test]
    fn control_characters_use_gson_escapes() {
        let v = json!("\"\\\t\u{8}\n\r\u{c}\u{1}\u{1f}");
        assert_eq!(
            to_compact_string(&v),
            "\"\\\"\\\\\\t\\b\\n\\r\\f\\u0001\\u001f\""
        );
    }

    #[test]
    fn keys_are_escaped_too() {
        let v = json!({"a=b": 1});
        assert_eq!(to_compact_string(&v), "{\"a\\u003db\":1}");
    }

    #[test]
    fn non_ascii_is_written_raw() {
        assert_eq!(
            to_compact_string(&json!("caf\u{e9} \u{1F600}")),
            "\"caf\u{e9} \u{1F600}\""
        );
    }

    #[test]
    fn pretty_output_reparses_to_the_same_value() {
        let v = json!({"url": "https://a.test/?x=1&y=<2>", "n": -5, "f": 1.5});
        let back: Value = serde_json::from_str(&to_pretty_string(&v)).unwrap();
        assert_eq!(back, v);
    }

    #[test]
    fn strict_json_reports_standard_syntax() {
        let (v, syntax) = parse_lenient("{\"a\":1}").unwrap();
        assert_eq!(syntax, Syntax::Standard);
        assert_eq!(v, json!({"a": 1}));
    }

    #[test]
    fn comments_and_single_quotes_fall_back_to_lenient() {
        let text = "{\n // comment\n 'packName': 'X', /* block */ unquoted: 2,\n}";
        let (v, syntax) = parse_lenient(text).unwrap();
        assert_eq!(syntax, Syntax::Lenient);
        assert_eq!(v["packName"], json!("X"));
        assert_eq!(v["unquoted"].as_f64(), Some(2.0));
    }

    #[test]
    fn garbage_is_a_syntax_error() {
        assert!(matches!(
            parse_lenient("not json"),
            Err(ManifestParseError::Syntax { .. })
        ));
    }

    #[test]
    fn a_byte_order_mark_is_ignored() {
        let (v, _) = parse_lenient("\u{feff}{\"a\":1}").unwrap();
        assert_eq!(v, json!({"a": 1}));
    }

    #[test]
    fn duplicate_keys_keep_the_last_value_in_the_first_position() {
        let (map, _) = parse_object("{\"a\":1,\"b\":2,\"a\":3}").unwrap();
        assert_eq!(map.keys().collect::<Vec<_>>(), vec!["a", "b"]);
        assert_eq!(map["a"], json!(3));
    }

    #[test]
    fn parse_object_rejects_non_objects() {
        assert_eq!(parse_object("[1]"), Err(ManifestParseError::NotAnObject));
        assert_eq!(parse_object("null"), Err(ManifestParseError::NotAnObject));
    }

    #[test]
    fn compat_accepts_what_gson_coerces() {
        let v = json!({
            "packName": 5, "packUnstable": "true", "packDefaultServerPort": "8585",
            "packLogoURL": ["a", 1, null], "packScanExclusions": ["x", 2],
            "packMods": [null, {"clientReq": "false", "sha1": 12}]
        });
        assert!(compat_problems(&v).is_empty(), "{:?}", compat_problems(&v));
    }

    #[test]
    fn compat_reports_what_gson_rejects() {
        let v = json!({
            "packName": {"x": 1},
            "packUnstable": 1,
            "packDefaultServerPort": 85.5,
            "packMods": ["junk"],
            "packConfigs": "nope",
            "packLogoURL": {"a": 1},
            "packNews": [{"title": ["t"]}]
        });
        let problems = compat_problems(&v);
        assert_eq!(problems.len(), 7, "{problems:?}");
        assert!(problems[0].contains("$.packName"));
        assert!(problems.iter().any(|p| p.contains("$.packMods[0]")));
        assert!(problems.iter().any(|p| p.contains("$.packNews[0].title")));
        assert!(compat_problems(&json!([1])).len() == 1);
    }
}
