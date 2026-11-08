// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Parsing and building JSON-RPC 2.0 messages (Java `JsonRpcCodec`).
//!
//! serde_json is strict where Gson was lenient (single quotes, comments, unquoted keys are
//! refused): a safer, harmless divergence. Invalid ids are never echoed back, since that would
//! reflect caller-controlled structure.

use serde_json::{Map, Value};

use super::{JSONRPC_VERSION, JsonRpcMessage, codes};

/// Parses one request. On failure returns the complete error response to send.
pub fn parse(raw: &str) -> Result<JsonRpcMessage, Value> {
    if raw.trim().is_empty() {
        return Err(error(None, codes::PARSE_ERROR, "Empty request", None));
    }
    let root: Value = match serde_json::from_str(raw) {
        Ok(v) => v,
        Err(_) => return Err(error(None, codes::PARSE_ERROR, "Malformed JSON", None)),
    };
    let obj = match root {
        Value::Null => return Err(error(None, codes::PARSE_ERROR, "Malformed JSON", None)),
        Value::Array(_) => {
            return Err(error(
                None,
                codes::INVALID_REQUEST,
                "Batch requests are not supported",
                None,
            ));
        }
        Value::Object(o) => o,
        _ => {
            return Err(error(
                None,
                codes::INVALID_REQUEST,
                "Request must be a JSON object",
                None,
            ));
        }
    };

    let id = match obj.get("id") {
        None => None,
        Some(Value::Null) => {
            return Err(error(
                None,
                codes::INVALID_REQUEST,
                "Request id must not be null",
                None,
            ));
        }
        Some(v @ (Value::String(_) | Value::Number(_))) => Some(v.clone()),
        Some(_) => {
            return Err(error(
                None,
                codes::INVALID_REQUEST,
                "Request id must be a string or number",
                None,
            ));
        }
    };

    let version_ok = match obj.get("jsonrpc") {
        Some(Value::String(s)) => s == JSONRPC_VERSION,
        // Gson's getAsString renders the number 2.0 as "2.0".
        Some(Value::Number(n)) => n.to_string() == JSONRPC_VERSION,
        _ => false,
    };
    if !version_ok {
        return Err(error(
            id.as_ref(),
            codes::INVALID_REQUEST,
            "Missing or unsupported \"jsonrpc\" version",
            None,
        ));
    }

    let method = match obj.get("method") {
        Some(Value::String(s)) => s.clone(),
        _ => {
            return Err(error(
                id.as_ref(),
                codes::INVALID_REQUEST,
                "Missing or non-string \"method\"",
                None,
            ));
        }
    };
    if method.trim().is_empty() {
        return Err(error(
            id.as_ref(),
            codes::INVALID_REQUEST,
            "Method name must not be blank",
            None,
        ));
    }

    let params = match obj.get("params") {
        None | Some(Value::Null) => None,
        Some(v @ (Value::Object(_) | Value::Array(_))) => Some(v.clone()),
        Some(_) => {
            return Err(error(
                id.as_ref(),
                codes::INVALID_REQUEST,
                "\"params\" must be an object or array",
                None,
            ));
        }
    };

    Ok(JsonRpcMessage { id, method, params })
}

/// `{"jsonrpc":"2.0","id":<id>,"result":<result or {}>}`.
pub fn result(id: Option<&Value>, result: Option<Value>) -> Value {
    let mut m = Map::new();
    m.insert("jsonrpc".into(), Value::String(JSONRPC_VERSION.into()));
    m.insert("id".into(), id.cloned().unwrap_or(Value::Null));
    let r = match result {
        None | Some(Value::Null) => Value::Object(Map::new()),
        Some(v) => v,
    };
    m.insert("result".into(), r);
    Value::Object(m)
}

/// `{"jsonrpc":"2.0","id":<id or null>,"error":{"code","message","data"?}}`. The `id` member is
/// always present.
pub fn error(id: Option<&Value>, code: i64, message: &str, data: Option<Value>) -> Value {
    let mut e = Map::new();
    e.insert("code".into(), Value::from(code));
    e.insert("message".into(), Value::String(message.to_string()));
    if let Some(d) = data {
        e.insert("data".into(), d);
    }
    let mut m = Map::new();
    m.insert("jsonrpc".into(), Value::String(JSONRPC_VERSION.into()));
    m.insert("id".into(), id.cloned().unwrap_or(Value::Null));
    m.insert("error".into(), Value::Object(e));
    Value::Object(m)
}

/// Compact JSON.
pub fn encode(v: &Value) -> String {
    serde_json::to_string(v).unwrap_or_else(|_| "{}".to_string())
}

#[cfg(test)]
mod tests {
    //! Port of `JsonRpcCodecTest`.
    use super::*;
    use serde_json::json;

    fn err_of(raw: &str) -> Value {
        parse(raw).expect_err("expected an error")
    }

    fn code(v: &Value) -> i64 {
        v["error"]["code"].as_i64().unwrap()
    }

    fn msg(v: &Value) -> &str {
        v["error"]["message"].as_str().unwrap()
    }

    #[test]
    fn a_well_formed_request_parses() {
        let m = parse(r#"{"jsonrpc":"2.0","id":1,"method":"ping"}"#).unwrap();
        assert_eq!(m.id, Some(json!(1)));
        assert_eq!(m.method, "ping");
        assert!(m.params.is_none());
        assert!(!m.is_notification());
    }

    #[test]
    fn a_string_id_is_kept() {
        let m = parse(r#"{"jsonrpc":"2.0","id":"abc","method":"ping"}"#).unwrap();
        assert_eq!(m.id, Some(json!("abc")));
    }

    #[test]
    fn a_message_without_id_is_a_notification() {
        let m = parse(r#"{"jsonrpc":"2.0","method":"notifications/initialized"}"#).unwrap();
        assert!(m.is_notification());
    }

    #[test]
    fn an_empty_body_is_a_parse_error() {
        for raw in ["", "   ", "\n\t"] {
            let e = err_of(raw);
            assert_eq!(code(&e), codes::PARSE_ERROR);
            assert_eq!(msg(&e), "Empty request");
            assert!(e["id"].is_null());
        }
    }

    #[test]
    fn malformed_json_is_a_parse_error() {
        let e = err_of("{not json");
        assert_eq!(code(&e), codes::PARSE_ERROR);
        assert_eq!(msg(&e), "Malformed JSON");
        assert!(e.as_object().unwrap().contains_key("id"));
        assert!(e["id"].is_null());
    }

    #[test]
    fn a_bare_null_root_is_a_parse_error() {
        let e = err_of("null");
        assert_eq!(code(&e), codes::PARSE_ERROR);
        assert_eq!(msg(&e), "Malformed JSON");
    }

    #[test]
    fn trailing_garbage_is_refused() {
        assert_eq!(
            code(&err_of(r#"{"jsonrpc":"2.0","method":"ping"} x"#)),
            codes::PARSE_ERROR
        );
    }

    #[test]
    fn batches_are_refused_even_empty() {
        for raw in ["[]", r#"[{"jsonrpc":"2.0","id":1,"method":"ping"}]"#] {
            let e = err_of(raw);
            assert_eq!(code(&e), codes::INVALID_REQUEST);
            assert_eq!(msg(&e), "Batch requests are not supported");
            assert!(e["id"].is_null());
        }
    }

    #[test]
    fn a_scalar_root_is_refused() {
        for raw in ["42", "\"x\"", "true"] {
            let e = err_of(raw);
            assert_eq!(code(&e), codes::INVALID_REQUEST);
            assert_eq!(msg(&e), "Request must be a JSON object");
        }
    }

    #[test]
    fn a_null_id_is_refused_and_not_echoed() {
        let e = err_of(r#"{"jsonrpc":"2.0","id":null,"method":"ping"}"#);
        assert_eq!(msg(&e), "Request id must not be null");
        assert!(e["id"].is_null());
    }

    #[test]
    fn a_structured_id_is_refused_and_never_echoed() {
        for id in ["{\"a\":1}", "[1]", "true"] {
            let e = err_of(&format!(r#"{{"jsonrpc":"2.0","id":{id},"method":"ping"}}"#));
            assert_eq!(code(&e), codes::INVALID_REQUEST);
            assert_eq!(msg(&e), "Request id must be a string or number");
            assert!(e["id"].is_null());
        }
    }

    #[test]
    fn a_missing_jsonrpc_version_is_refused_with_the_id_echoed() {
        let e = err_of(r#"{"id":7,"method":"ping"}"#);
        assert_eq!(code(&e), codes::INVALID_REQUEST);
        assert_eq!(msg(&e), "Missing or unsupported \"jsonrpc\" version");
        assert_eq!(e["id"], json!(7));
    }

    #[test]
    fn a_wrong_jsonrpc_version_is_rejected() {
        for v in ["\"1.0\"", "\"2\"", "{}", "null"] {
            let e = err_of(&format!(r#"{{"jsonrpc":{v},"id":1,"method":"ping"}}"#));
            assert_eq!(code(&e), codes::INVALID_REQUEST);
        }
    }

    #[test]
    fn the_number_two_point_oh_is_accepted_like_gson() {
        assert!(parse(r#"{"jsonrpc":2.0,"id":1,"method":"ping"}"#).is_ok());
    }

    #[test]
    fn a_missing_or_non_string_method_is_refused() {
        for raw in [
            r#"{"jsonrpc":"2.0","id":1}"#,
            r#"{"jsonrpc":"2.0","id":1,"method":5}"#,
            r#"{"jsonrpc":"2.0","id":1,"method":null}"#,
        ] {
            let e = err_of(raw);
            assert_eq!(msg(&e), "Missing or non-string \"method\"");
            assert_eq!(e["id"], json!(1));
        }
    }

    #[test]
    fn a_blank_method_is_refused() {
        let e = err_of(r#"{"jsonrpc":"2.0","id":"q","method":"  "}"#);
        assert_eq!(msg(&e), "Method name must not be blank");
        assert_eq!(e["id"], json!("q"));
    }

    #[test]
    fn scalar_params_are_refused() {
        for p in ["1", "\"x\"", "true"] {
            let e = err_of(&format!(
                r#"{{"jsonrpc":"2.0","id":1,"method":"ping","params":{p}}}"#
            ));
            assert_eq!(msg(&e), "\"params\" must be an object or array");
        }
    }

    #[test]
    fn null_params_are_absent() {
        let m = parse(r#"{"jsonrpc":"2.0","id":1,"method":"ping","params":null}"#).unwrap();
        assert!(m.params.is_none());
    }

    #[test]
    fn array_params_read_as_an_empty_object() {
        let m = parse(r#"{"jsonrpc":"2.0","id":1,"method":"ping","params":[1,2]}"#).unwrap();
        assert!(m.params.is_some());
        assert!(m.params_object().is_empty());
    }

    #[test]
    fn object_params_are_kept() {
        let m = parse(r#"{"jsonrpc":"2.0","id":1,"method":"x","params":{"name":"a"}}"#).unwrap();
        assert_eq!(m.params_object()["name"], json!("a"));
    }

    #[test]
    fn unknown_top_level_fields_are_ignored() {
        assert!(parse(r#"{"jsonrpc":"2.0","id":1,"method":"ping","extra":{"x":1}}"#).is_ok());
    }

    #[test]
    fn result_shape_and_null_result_becomes_empty_object() {
        let r = result(Some(&json!(3)), None);
        assert_eq!(r, json!({"jsonrpc":"2.0","id":3,"result":{}}));
        let r = result(Some(&json!("a")), Some(json!({"x":1})));
        assert_eq!(r["result"]["x"], json!(1));
    }

    #[test]
    fn error_shape_includes_data_only_when_supplied() {
        let e = error(Some(&json!(1)), -32601, "nope", None);
        assert!(!e["error"].as_object().unwrap().contains_key("data"));
        let e = error(Some(&json!(1)), -32601, "nope", Some(json!({"k":1})));
        assert_eq!(e["error"]["data"]["k"], json!(1));
    }

    #[test]
    fn the_id_member_is_present_when_null() {
        let e = error(None, -32700, "", None);
        assert!(e.as_object().unwrap().contains_key("id"));
        assert_eq!(e["error"]["message"], json!(""));
    }

    #[test]
    fn encode_is_compact_and_round_trips() {
        let v = result(Some(&json!(1)), Some(json!({"a":[1,2],"b":"c"})));
        let s = encode(&v);
        assert!(!s.contains(' '));
        assert!(!s.contains('\n'));
        let back: Value = serde_json::from_str(&s).unwrap();
        assert_eq!(back, v);
        assert!(s.starts_with(r#"{"jsonrpc":"2.0","id":1,"result""#));
    }

    #[test]
    fn a_large_numeric_id_is_echoed() {
        let m = parse(r#"{"jsonrpc":"2.0","id":12345678901234,"method":"ping"}"#).unwrap();
        assert_eq!(
            encode(&result(m.id.as_ref(), None)),
            r#"{"jsonrpc":"2.0","id":12345678901234,"result":{}}"#
        );
    }
}
