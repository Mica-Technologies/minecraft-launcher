// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The authentication file (`player.mica` after decryption): gzip of UTF-8 JSON, in the format
//! `minecraft_authenticator` writes, so the Java launcher and this one read each other's files.
//!
//! ```json
//! {"warning":"DO NOT SHARE THIS FILE. ...",
//!  "file":{"clientId":{"most":-123,"least":456},"refreshToken":"M.C5...",
//!          "extraProperties":{},"type":"microsoft"}}
//! ```
//!
//! `extraProperties` is a string map the Java library keeps but never fills. This launcher uses
//! it for what a refresh needs to know about tokens that did not come from the web view sign-in
//! (the [`EXTRA_SCOPE`], [`EXTRA_MSA_CLIENT_ID`], [`EXTRA_FLOW`] and [`EXTRA_WAM_ACCOUNT_ID`]
//! keys). A file without them is a plain web view sign-in, exactly what Java writes.

use std::collections::BTreeMap;
use std::fmt;
use std::io::{Read, Write};

use flate2::Compression;
use flate2::read::MultiGzDecoder;
use flate2::write::GzEncoder;
use serde_json::{Map, Value};
use uuid::Uuid;

/// The `warning` member.
pub const FILE_WARNING: &str =
    "DO NOT SHARE THIS FILE. IT CONTAINS LOGIN INFORMATION FOR YOUR MINECRAFT ACCOUNT!";
/// The only supported `file.type`.
pub const TYPE_MICROSOFT: &str = "microsoft";

/// Extra property: the OAuth scope the refresh token was issued for. Absent means
/// `XboxLive.signin offline_access` (the web view sign-in).
pub const EXTRA_SCOPE: &str = "mmcl.scope";
/// Extra property: the Microsoft OAuth client ID, when it is not the official launcher ID.
pub const EXTRA_MSA_CLIENT_ID: &str = "mmcl.msaClientId";
/// Extra property: which sign-in flow produced the file (`webView`, `deviceCode`, `wam`).
pub const EXTRA_FLOW: &str = "mmcl.flow";
/// Extra property: the WAM web account id (WAM files have no refresh token).
pub const EXTRA_WAM_ACCOUNT_ID: &str = "mmcl.wamAccountId";

/// Why an authentication file could not be read or written.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum AuthFileError {
    /// Not valid gzip.
    #[error("the authentication file is not valid gzip")]
    Gzip,
    /// Not the expected JSON (Java: "Cannot parse authentication file").
    #[error("Cannot parse authentication file")]
    Parse,
    /// Writing the gzip stream failed.
    #[error("the authentication file could not be compressed")]
    Write,
}

impl AuthFileError {
    /// A data-free name for logs.
    pub fn kind_name(&self) -> &'static str {
        match self {
            AuthFileError::Gzip => "AuthFileGzip",
            AuthFileError::Parse => "AuthFileParse",
            AuthFileError::Write => "AuthFileWrite",
        }
    }
}

/// A Microsoft authentication file.
#[derive(Clone, PartialEq, Eq)]
pub struct AuthFile {
    /// The launcher client UUID, created at the first interactive sign-in and kept across
    /// renewals. Its string form is the entitlements `requestId` and, Base64url-encoded, the
    /// user's `clientId`.
    pub client_id: Uuid,
    /// The Microsoft OAuth refresh token (empty for WAM files).
    pub refresh_token: String,
    /// `extraProperties`.
    pub extra: BTreeMap<String, String>,
}

impl fmt::Debug for AuthFile {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("AuthFile")
            .field("client_id", &self.client_id)
            .field("refresh_token", &"<redacted>")
            .field("extra", &self.extra)
            .finish()
    }
}

impl AuthFile {
    /// A file with no extra properties.
    pub fn new(client_id: Uuid, refresh_token: impl Into<String>) -> AuthFile {
        AuthFile {
            client_id,
            refresh_token: refresh_token.into(),
            extra: BTreeMap::new(),
        }
    }

    /// The same file with a new refresh token (a renewal keeps the client id and extras).
    pub fn with_refresh_token(&self, refresh_token: impl Into<String>) -> AuthFile {
        AuthFile {
            client_id: self.client_id,
            refresh_token: refresh_token.into(),
            extra: self.extra.clone(),
        }
    }

    /// An extra property, `None` when absent or blank.
    pub fn extra(&self, key: &str) -> Option<&str> {
        self.extra
            .get(key)
            .map(String::as_str)
            .filter(|v| !v.trim().is_empty())
    }

    /// Parses gzip'd bytes (any valid gzip, single or multi member).
    pub fn read_compressed(gz: &[u8]) -> Result<AuthFile, AuthFileError> {
        let mut json = Vec::new();
        MultiGzDecoder::new(gz)
            .read_to_end(&mut json)
            .map_err(|_| AuthFileError::Gzip)?;
        let text = String::from_utf8_lossy(&json);
        AuthFile::from_json_str(&text)
    }

    /// Parses the JSON text.
    pub fn from_json_str(json: &str) -> Result<AuthFile, AuthFileError> {
        let root: Value = serde_json::from_str(json).map_err(|_| AuthFileError::Parse)?;
        let file = root
            .as_object()
            .and_then(|o| o.get("file"))
            .and_then(Value::as_object)
            .ok_or(AuthFileError::Parse)?;
        if file.get("type").and_then(java_string).as_deref() != Some(TYPE_MICROSOFT) {
            return Err(AuthFileError::Parse);
        }
        let client = file
            .get("clientId")
            .and_then(Value::as_object)
            .ok_or(AuthFileError::Parse)?;
        let most = client
            .get("most")
            .and_then(java_long)
            .ok_or(AuthFileError::Parse)?;
        let least = client
            .get("least")
            .and_then(java_long)
            .ok_or(AuthFileError::Parse)?;
        let refresh_token = file
            .get("refreshToken")
            .and_then(java_string)
            .ok_or(AuthFileError::Parse)?;
        let mut extra = BTreeMap::new();
        if let Some(props) = file.get("extraProperties").and_then(Value::as_object) {
            for (k, v) in props {
                if let Some(s) = java_string(v) {
                    extra.insert(k.clone(), s);
                }
            }
        }
        Ok(AuthFile {
            client_id: Uuid::from_u64_pair(most as u64, least as u64),
            refresh_token,
            extra,
        })
    }

    /// The JSON text, members in the Java library's order, compact.
    pub fn to_json_string(&self) -> String {
        let (most, least) = self.client_id.as_u64_pair();
        let mut client = Map::new();
        client.insert("most".into(), Value::from(most as i64));
        client.insert("least".into(), Value::from(least as i64));
        let mut props = Map::new();
        for (k, v) in &self.extra {
            props.insert(k.clone(), Value::from(v.as_str()));
        }
        let mut file = Map::new();
        file.insert("clientId".into(), Value::Object(client));
        file.insert(
            "refreshToken".into(),
            Value::from(self.refresh_token.as_str()),
        );
        file.insert("extraProperties".into(), Value::Object(props));
        file.insert("type".into(), Value::from(TYPE_MICROSOFT));
        let mut root = Map::new();
        root.insert("warning".into(), Value::from(FILE_WARNING));
        root.insert("file".into(), Value::Object(file));
        Value::Object(root).to_string()
    }

    /// The gzip'd JSON, what `player.mica` holds before encryption.
    pub fn write_compressed(&self) -> Result<Vec<u8>, AuthFileError> {
        let mut enc = GzEncoder::new(Vec::new(), Compression::default());
        enc.write_all(self.to_json_string().as_bytes())
            .map_err(|_| AuthFileError::Write)?;
        enc.finish().map_err(|_| AuthFileError::Write)
    }
}

/// Gson `getAsString` on a primitive: strings as-is, numbers and booleans as text.
fn java_string(v: &Value) -> Option<String> {
    match v {
        Value::String(s) => Some(s.clone()),
        Value::Number(n) => Some(n.to_string()),
        Value::Bool(b) => Some(b.to_string()),
        _ => None,
    }
}

/// Gson `getAsLong`: integral numbers, or a string holding one.
fn java_long(v: &Value) -> Option<i64> {
    match v {
        Value::Number(n) => n
            .as_i64()
            .or_else(|| n.as_u64().map(|u| u as i64))
            .or_else(|| n.as_f64().map(|f| f as i64)),
        Value::String(s) => s.trim().parse().ok(),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn round_trips_through_gzip_with_negative_halves() {
        let id = Uuid::from_u64_pair(-1234567890123456789i64 as u64, 987654321);
        let mut f = AuthFile::new(id, "M.C5_dummy.0.U.-token");
        f.extra.insert(EXTRA_SCOPE.into(), "s".into());
        let gz = f.write_compressed().unwrap();
        assert_eq!(&gz[..2], &[0x1f, 0x8b]);
        assert_eq!(AuthFile::read_compressed(&gz).unwrap(), f);
    }

    #[test]
    fn writes_the_java_layout() {
        let id = Uuid::from_u64_pair(-1234567890123456789i64 as u64, 987654321);
        let json = AuthFile::new(id, "rt").to_json_string();
        assert_eq!(
            json,
            "{\"warning\":\"DO NOT SHARE THIS FILE. IT CONTAINS LOGIN INFORMATION FOR YOUR MINECRAFT ACCOUNT!\",\
             \"file\":{\"clientId\":{\"most\":-1234567890123456789,\"least\":987654321},\
             \"refreshToken\":\"rt\",\"extraProperties\":{},\"type\":\"microsoft\"}}"
        );
    }

    /// The exact text the Java library's Gson writes (member order, no extra spaces), as the
    /// Java launcher stores it in `player.mica`.
    #[test]
    fn reads_what_the_java_library_writes() {
        let java = "{\"warning\":\"DO NOT SHARE THIS FILE. IT CONTAINS LOGIN INFORMATION FOR YOUR MINECRAFT ACCOUNT!\",\"file\":{\"clientId\":{\"most\":5764607523034234880,\"least\":-9223372036854775807},\"refreshToken\":\"M.C507_BAY.0.U.-dummy\",\"extraProperties\":{},\"type\":\"microsoft\"}}";
        let mut enc = GzEncoder::new(Vec::new(), Compression::default());
        enc.write_all(java.as_bytes()).unwrap();
        let f = AuthFile::read_compressed(&enc.finish().unwrap()).unwrap();
        assert_eq!(f.refresh_token, "M.C507_BAY.0.U.-dummy");
        let (m, l) = f.client_id.as_u64_pair();
        assert_eq!(m as i64, 5764607523034234880);
        assert_eq!(l as i64, -9223372036854775807);
        assert!(f.extra.is_empty());
        assert_eq!(f.to_json_string(), java);
    }

    #[test]
    fn extra_properties_are_optional_and_other_types_are_refused() {
        let ok = "{\"file\":{\"clientId\":{\"most\":1,\"least\":2},\"refreshToken\":\"r\",\"type\":\"microsoft\"}}";
        assert!(AuthFile::from_json_str(ok).is_ok());
        let other = ok.replace("microsoft", "mojang");
        assert_eq!(AuthFile::from_json_str(&other), Err(AuthFileError::Parse));
        assert_eq!(AuthFile::from_json_str("[]"), Err(AuthFileError::Parse));
        assert_eq!(
            AuthFile::read_compressed(b"not gzip"),
            Err(AuthFileError::Gzip)
        );
    }

    #[test]
    fn debug_hides_the_refresh_token() {
        let f = AuthFile::new(Uuid::nil(), "very-secret");
        assert!(!format!("{f:?}").contains("very-secret"));
    }
}
