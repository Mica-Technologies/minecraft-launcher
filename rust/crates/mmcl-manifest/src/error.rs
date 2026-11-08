// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Errors for manifest parsing.

/// Why a manifest body could not be turned into a [`crate::PackManifest`].
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum ManifestParseError {
    /// Neither strict JSON nor the lenient `json5` fallback could parse the text.
    /// `message` is the strict parser's error, which is the more useful of the two.
    #[error("manifest is not valid JSON: {message}")]
    Syntax { message: String },
    /// The text parsed, but its top-level value is not a JSON object.
    #[error("manifest JSON is not an object")]
    NotAnObject,
}

impl ManifestParseError {
    /// The localization key for showing this error to a user. `{0}` is the detail message
    /// for [`ManifestParseError::Syntax`].
    pub fn key(&self) -> &'static str {
        match self {
            ManifestParseError::Syntax { .. } => "error.manifest.syntax",
            ManifestParseError::NotAnObject => "error.manifest.notObject",
        }
    }

    /// Arguments for [`Self::key`].
    pub fn args(&self) -> Vec<String> {
        match self {
            ManifestParseError::Syntax { message } => vec![message.clone()],
            ManifestParseError::NotAnObject => Vec::new(),
        }
    }
}
