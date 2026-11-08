// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! `string | string[]` manifest values (`packLogoURL`, `packBackgroundURL`).
//!
//! An array means several distinct showcase images the UI cycles through, not mirrors.

use serde::{Deserialize, Deserializer, Serialize, Serializer};
use serde_json::Value;

use crate::lenient::coerce_string;

/// An ordered list of trimmed, non-blank strings read from a bare string or an array.
#[derive(Clone, Debug, Default, PartialEq, Eq, Hash)]
pub struct StringOrArray(Vec<String>);

impl StringOrArray {
    /// Normalises the values: each is trimmed and blanks are dropped, order kept.
    pub fn of<I, S>(values: I) -> Self
    where
        I: IntoIterator<Item = S>,
        S: AsRef<str>,
    {
        StringOrArray(
            values
                .into_iter()
                .map(|v| v.as_ref().trim().to_string())
                .filter(|v| !v.is_empty())
                .collect(),
        )
    }

    /// Reads a JSON value: `null` is empty, an array contributes each non-null primitive's
    /// text, a primitive contributes its text. Objects (which Gson rejects) contribute nothing.
    pub fn from_value(v: &Value) -> Self {
        match v {
            Value::Array(items) => StringOrArray::of(items.iter().filter_map(coerce_string)),
            other => StringOrArray::of(coerce_string(other)),
        }
    }

    /// The JSON form: empty is `null`, one value a bare string, several an array.
    pub fn to_value(&self) -> Value {
        match self.0.as_slice() {
            [] => Value::Null,
            [one] => Value::String(one.clone()),
            many => Value::Array(many.iter().cloned().map(Value::String).collect()),
        }
    }

    /// Every value, in order.
    pub fn all(&self) -> &[String] {
        &self.0
    }

    /// The first value, if any.
    pub fn first(&self) -> Option<&str> {
        self.0.first().map(String::as_str)
    }

    /// Number of values.
    pub fn len(&self) -> usize {
        self.0.len()
    }

    /// True when there are no values.
    pub fn is_empty(&self) -> bool {
        self.0.is_empty()
    }
}

impl Serialize for StringOrArray {
    fn serialize<S: Serializer>(&self, s: S) -> Result<S::Ok, S::Error> {
        self.to_value().serialize(s)
    }
}

impl<'de> Deserialize<'de> for StringOrArray {
    fn deserialize<D: Deserializer<'de>>(d: D) -> Result<Self, D::Error> {
        Ok(StringOrArray::from_value(&Value::deserialize(d)?))
    }
}

/// serde adapter for an optional field: absent or `null` is `None` (Gson leaves the field
/// null), anything else is `Some`, possibly empty.
pub fn optional<'de, D: Deserializer<'de>>(d: D) -> Result<Option<StringOrArray>, D::Error> {
    let v = Value::deserialize(d)?;
    Ok(if v.is_null() {
        None
    } else {
        Some(StringOrArray::from_value(&v))
    })
}

/// `skip_serializing_if` helper: Gson drops a field whose adapter writes `null`.
pub fn is_absent(v: &Option<StringOrArray>) -> bool {
    v.as_ref().is_none_or(StringOrArray::is_empty)
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[derive(Serialize, Deserialize, Default)]
    struct Holder {
        #[serde(
            rename = "packLogoURL",
            default,
            deserialize_with = "optional",
            skip_serializing_if = "is_absent"
        )]
        pack_logo_url: Option<StringOrArray>,
    }

    #[test]
    fn deserializes_bare_string_to_singleton_list() {
        let h: Holder = serde_json::from_str("{\"packLogoURL\":\"https://a/logo.png\"}").unwrap();
        let v = h.pack_logo_url.unwrap();
        assert_eq!(v.all(), ["https://a/logo.png"]);
        assert_eq!(v.first(), Some("https://a/logo.png"));
        assert!(!v.is_empty());
    }

    #[test]
    fn deserializes_array_preserving_order() {
        let h: Holder = serde_json::from_str(
            "{\"packLogoURL\":[\"https://a/logo.png\",\"https://b/logo.png\"]}",
        )
        .unwrap();
        let v = h.pack_logo_url.unwrap();
        assert_eq!(v.all(), ["https://a/logo.png", "https://b/logo.png"]);
        assert_eq!(v.first(), Some("https://a/logo.png"));
    }

    #[test]
    fn absent_field_is_none_and_empty_string_is_empty() {
        let absent: Holder = serde_json::from_str("{}").unwrap();
        assert!(absent.pack_logo_url.is_none());
        let null: Holder = serde_json::from_str("{\"packLogoURL\":null}").unwrap();
        assert!(null.pack_logo_url.is_none());
        let blank: Holder = serde_json::from_str("{\"packLogoURL\":\"\"}").unwrap();
        let v = blank.pack_logo_url.unwrap();
        assert!(v.is_empty());
        assert_eq!(v.first(), None);
    }

    #[test]
    fn normalization_trims_and_drops_blanks() {
        let v = StringOrArray::of(["  https://a/logo.png  ", "", "   ", "https://b/x"]);
        assert_eq!(v.all(), ["https://a/logo.png", "https://b/x"]);
    }

    #[test]
    fn primitives_and_null_elements() {
        let v = StringOrArray::from_value(&json!(["a", null, 5, true, {"x": 1}]));
        assert_eq!(v.all(), ["a", "5", "true"]);
        assert_eq!(StringOrArray::from_value(&json!(7)).all(), ["7"]);
    }

    #[test]
    fn serializes_single_as_bare_string_and_many_as_array() {
        let single = Holder {
            pack_logo_url: Some(StringOrArray::of(["https://a/logo.png"])),
        };
        assert_eq!(
            serde_json::to_string(&single).unwrap(),
            "{\"packLogoURL\":\"https://a/logo.png\"}"
        );
        let many = Holder {
            pack_logo_url: Some(StringOrArray::of([
                "https://a/logo.png",
                "https://b/logo.png",
            ])),
        };
        assert_eq!(
            serde_json::to_string(&many).unwrap(),
            "{\"packLogoURL\":[\"https://a/logo.png\",\"https://b/logo.png\"]}"
        );
        let empty = Holder {
            pack_logo_url: Some(StringOrArray::default()),
        };
        assert_eq!(serde_json::to_string(&empty).unwrap(), "{}");
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
        let a: StringOrArray = serde_json::from_str("\"https://a\"").unwrap();
        let b: StringOrArray = serde_json::from_str("[\"https://a\"]").unwrap();
        assert_eq!(a, b);
    }
}
