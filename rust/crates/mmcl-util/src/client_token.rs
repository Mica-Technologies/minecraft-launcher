// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The launcher's client token (`SystemUtilities.getClientToken`): a UUID stored in
//! `<config>/client.mica`, created on first use and reused afterwards.
//!
//! The file holds the token as-is (UTF-8, no newline) so the Java launcher reads the same
//! value from the same folder.

use std::path::Path;
use std::sync::OnceLock;

use mmcl_core::DataPaths;
use mmcl_i18n::t;

/// Reads the token from `file`, or generates a UUID v4 (lowercase, dashed) and writes it
/// there without a newline. A failed write is logged and the new token is still used.
pub fn load_or_create(file: &Path) -> String {
    tracing::debug!("{}", t!("CLIENT_TOKEN_CHECKING_TEXT"));
    let mut token = None;
    if file.is_file() {
        match std::fs::read_to_string(file) {
            Ok(s) => token = Some(s),
            Err(e) => tracing::error!(error = %e, "{}", t!("UNABLE_READ_STORED_CLIENT_TOKEN_TEXT")),
        }
    }
    let token = token.unwrap_or_else(|| {
        let fresh = uuid::Uuid::new_v4().to_string();
        tracing::info!(
            "{} {}",
            t!("NEW_CLIENT_TOKEN_TEXT"),
            crate::redact::REDACTED
        );
        let written = file
            .parent()
            .map_or(Ok(()), std::fs::create_dir_all)
            .and_then(|()| std::fs::write(file, fresh.as_bytes()));
        match written {
            Ok(()) => tracing::debug!("{}", t!("STORED_CLIENT_TOKEN_TEXT")),
            Err(e) => tracing::error!(error = %e, "{}", t!("UNABLE_SAVE_CLIENT_TOKEN_TEXT")),
        }
        fresh
    });
    tracing::debug!(
        "{} {}",
        t!("LOADED_CLIENT_TOKEN_TEXT"),
        crate::redact::REDACTED
    );
    token
}

/// The session's client token, read or created on first call from `paths`' config folder
/// (`<config>/client.mica`) and memoized for the rest of the process.
pub fn client_token(paths: &DataPaths) -> &'static str {
    static TOKEN: OnceLock<String> = OnceLock::new();
    TOKEN.get_or_init(|| load_or_create(&paths.client_token_file()))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn creates_then_reuses_the_token() {
        let dir = tempfile::tempdir().unwrap();
        let file = dir.path().join("config").join("client.mica");
        let first = load_or_create(&file);
        assert!(uuid::Uuid::parse_str(&first).is_ok());
        assert_eq!(first, first.to_lowercase());
        assert_eq!(
            std::fs::read_to_string(&file).unwrap(),
            first,
            "no trailing newline"
        );
        assert_eq!(load_or_create(&file), first);
    }

    #[test]
    fn existing_file_is_used_as_is() {
        let dir = tempfile::tempdir().unwrap();
        let file = dir.path().join("client.mica");
        std::fs::write(&file, "java-written-token").unwrap();
        assert_eq!(load_or_create(&file), "java-written-token");
    }

    #[test]
    fn unwritable_location_still_yields_a_token() {
        let dir = tempfile::tempdir().unwrap();
        let blocker = dir.path().join("config");
        std::fs::write(&blocker, "not a folder").unwrap();
        let token = load_or_create(&blocker.join("client.mica"));
        assert_eq!(token.len(), 36);
    }
}
