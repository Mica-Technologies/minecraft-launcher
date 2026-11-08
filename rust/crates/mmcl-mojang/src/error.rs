// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The crate's error type. Every variant maps to a localisation key plus its
//! `{0}`-style arguments, so callers can render it in any locale.

/// An error reading one of the manifests this crate models.
#[derive(Debug, thiserror::Error, Clone, PartialEq, Eq)]
pub enum ManifestError {
    /// The bytes were not valid JSON (or not the expected top-level shape).
    #[error("the manifest couldn't be read: {0}")]
    Parse(String),
    /// A required field was missing, null, or of an unusable type.
    #[error("required JSON key '{0}' is missing or null")]
    MissingField(String),
    /// The Minecraft version was not in the version manifest.
    #[error("Minecraft version {0} wasn't found")]
    VersionNotFound(String),
    /// A required Forge version manifest field (`id`, `mainClass`) was missing.
    #[error("the Forge version manifest is missing a required field: {0}")]
    ForgeMissingField(String),
    /// The Forge installer had neither a `version.json` nor a legacy `versionInfo`.
    #[error("unable to find the Forge version file in the installer")]
    ForgeVersionFileMissing,
    /// An asset index object had a hash that is not a hex SHA-1.
    #[error("asset {0} has an invalid hash")]
    BadAssetHash(String),
    /// `assetIndex.id` is not usable as a file name (it names
    /// `indexes/<id>.json` and `assets/virtual/<id>`).
    #[error("the asset index id {0} is not a safe file name")]
    UnsafeAssetIndexId(String),
    /// The Mojang runtime index has no entry for this platform.
    #[error("Mojang's runtime index has no entry for platform {0}")]
    RuntimePlatformNotFound(String),
    /// The Mojang runtime index has no such component for this platform.
    #[error("Java runtime {component} isn't available for platform {platform}")]
    RuntimeComponentNotFound { component: String, platform: String },
    /// The component exists but its entry list is empty.
    #[error("there are no downloads for Java runtime {component} on platform {platform}")]
    RuntimeNoEntries { component: String, platform: String },
}

impl ManifestError {
    /// The localisation key for this error (`DisplayStrings.properties`).
    pub fn l10n_key(&self) -> &'static str {
        match self {
            ManifestError::Parse(_) => "mojang.error.parse",
            ManifestError::MissingField(_) => "mojang.error.missingField",
            ManifestError::VersionNotFound(_) => "minecraft.error.versionNotFound",
            ManifestError::ForgeMissingField(_) => "forge.error.manifestMissingField",
            ManifestError::ForgeVersionFileMissing => "UNABLE_FIND_FORGE_VERSION_FILE_TEXT",
            ManifestError::BadAssetHash(_) => "mojang.error.badAssetHash",
            ManifestError::UnsafeAssetIndexId(_) => "mojang.error.unsafeAssetIndexId",
            ManifestError::RuntimePlatformNotFound(_) => "runtime.error.platformNotFound",
            ManifestError::RuntimeComponentNotFound { .. } => "runtime.error.componentNotFound",
            ManifestError::RuntimeNoEntries { .. } => "runtime.error.noEntries",
        }
    }

    /// The MessageFormat arguments for [`Self::l10n_key`].
    pub fn l10n_args(&self) -> Vec<String> {
        match self {
            ManifestError::Parse(s)
            | ManifestError::MissingField(s)
            | ManifestError::VersionNotFound(s)
            | ManifestError::ForgeMissingField(s)
            | ManifestError::BadAssetHash(s)
            | ManifestError::UnsafeAssetIndexId(s)
            | ManifestError::RuntimePlatformNotFound(s) => vec![s.clone()],
            ManifestError::RuntimeComponentNotFound {
                component,
                platform,
            }
            | ManifestError::RuntimeNoEntries {
                component,
                platform,
            } => vec![component.clone(), platform.clone()],
            ManifestError::ForgeVersionFileMissing => Vec::new(),
        }
    }
}

impl From<serde_json::Error> for ManifestError {
    fn from(e: serde_json::Error) -> Self {
        ManifestError::Parse(e.to_string())
    }
}

/// Small JSON helpers shared by the models (Gson-style leniency).
pub(crate) mod json {
    use serde_json::{Map, Value};

    use super::ManifestError;

    /// Gson `getAsString` on a primitive: strings as-is, numbers and booleans
    /// stringified. `None` for null, arrays and objects.
    pub fn primitive_string(v: &Value) -> Option<String> {
        match v {
            Value::String(s) => Some(s.clone()),
            Value::Number(n) => Some(n.to_string()),
            Value::Bool(b) => Some(b.to_string()),
            _ => None,
        }
    }

    /// `obj[key]` as a string, or `None` when absent, null, or not a primitive.
    pub fn opt_string(obj: &Map<String, Value>, key: &str) -> Option<String> {
        obj.get(key).and_then(primitive_string)
    }

    /// `JsonHelper.getRequiredString`: absent, null or non-primitive is an error.
    pub fn required_string(obj: &Map<String, Value>, key: &str) -> Result<String, ManifestError> {
        opt_string(obj, key).ok_or_else(|| ManifestError::MissingField(key.to_string()))
    }

    /// `obj[key]` as an object, or `None`.
    pub fn opt_object<'a>(
        obj: &'a Map<String, Value>,
        key: &str,
    ) -> Option<&'a Map<String, Value>> {
        obj.get(key).and_then(Value::as_object)
    }

    /// `obj[key]` as an array slice, or `None`.
    pub fn opt_array<'a>(obj: &'a Map<String, Value>, key: &str) -> Option<&'a [Value]> {
        obj.get(key).and_then(Value::as_array).map(Vec::as_slice)
    }

    /// Gson `getAsInt`: integer numbers, truncated floats, or numeric strings.
    pub fn as_i32(v: &Value) -> Option<i32> {
        match v {
            Value::Number(n) => n
                .as_i64()
                .or_else(|| n.as_f64().map(|f| f.trunc() as i64))
                .and_then(|i| i32::try_from(i).ok()),
            Value::String(s) => s.trim().parse().ok(),
            _ => None,
        }
    }

    /// Gson `getAsBoolean`: booleans, or the string `"true"` (case-insensitive).
    pub fn as_bool(v: &Value) -> Option<bool> {
        match v {
            Value::Bool(b) => Some(*b),
            Value::String(s) => Some(s.eq_ignore_ascii_case("true")),
            _ => None,
        }
    }

    /// Parse bytes into a top-level JSON object.
    pub fn parse_object(bytes: &[u8]) -> Result<Map<String, Value>, ManifestError> {
        match serde_json::from_slice::<Value>(bytes)? {
            Value::Object(m) => Ok(m),
            _ => Err(ManifestError::Parse("expected a JSON object".to_string())),
        }
    }
}
