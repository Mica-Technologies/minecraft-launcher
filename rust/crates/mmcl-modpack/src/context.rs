// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The prepare context (Java `LaunchPrepareContext` + `LaunchVerifyMode`): how one prepare run
//! verifies files and where it audits re-downloads.
//!
//! **Guard:** there is no global "current launch". Several packs prepare at once on one shared
//! download pool, so the context travels with the pack ([`crate::GameModPack::prepare_context`])
//! and is passed explicitly to every managed-file check.

use std::path::{Path, PathBuf};

use mmcl_manifest::HashAlgo;

/// How hard a prepare run checks files already on disk.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Hash)]
pub enum VerifyMode {
    /// Hash every file against its strongest declared hash.
    #[default]
    Full,
    /// Trust a non-empty file that exists (no hashing). Used when the manifest is unchanged
    /// and the pack was fully verified recently.
    FastPath,
}

impl VerifyMode {
    /// The Java enum name (`FULL` / `FAST_PATH`).
    pub fn name(self) -> &'static str {
        match self {
            VerifyMode::Full => "FULL",
            VerifyMode::FastPath => "FAST_PATH",
        }
    }
}

/// One prepare run's verify mode and audit target. Immutable.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct PrepareContext {
    pub verify_mode: VerifyMode,
    audit_pack_root: Option<PathBuf>,
    audit_launch_id: i32,
}

impl Default for PrepareContext {
    fn default() -> Self {
        PrepareContext::NONE
    }
}

impl PrepareContext {
    /// Full verify, no audit: outside any prepare run, and for "Verify this pack now" (there is
    /// no launch number to record against).
    pub const NONE: PrepareContext = PrepareContext {
        verify_mode: VerifyMode::Full,
        audit_pack_root: None,
        audit_launch_id: -1,
    };

    /// The context of one launch: `mode`, auditing re-downloads into `pack_root`'s audit log
    /// under `launch_id` (no audit when `pack_root` is `None`).
    pub fn for_launch(mode: VerifyMode, pack_root: Option<PathBuf>, launch_id: i32) -> Self {
        PrepareContext {
            verify_mode: mode,
            audit_pack_root: pack_root,
            audit_launch_id: launch_id,
        }
    }

    /// "Verify this pack now": hash everything, record nothing.
    pub fn full_verify() -> Self {
        PrepareContext::NONE
    }

    /// `true` when re-downloads are audited.
    pub fn is_auditing(&self) -> bool {
        self.audit_pack_root.is_some()
    }

    /// The pack folder whose audit log receives re-downloads.
    pub fn audit_pack_root(&self) -> Option<&Path> {
        self.audit_pack_root.as_deref()
    }

    /// The launch number recorded with each re-download (-1 when none).
    pub fn audit_launch_id(&self) -> i32 {
        self.audit_launch_id
    }

    /// Records a re-download of an existing file in the audit log. A no-op without an audit
    /// target; never fails the caller.
    pub fn record_redownload(
        &self,
        path: &Path,
        old_hash: Option<&str>,
        new_hash: Option<&str>,
        expected: Option<&str>,
        algo: Option<HashAlgo>,
    ) {
        if let Some(root) = &self.audit_pack_root {
            crate::audit::record_redownload(
                root,
                self.audit_launch_id,
                path,
                old_hash,
                new_hash,
                expected,
                algo.map(HashAlgo::key),
            );
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn none_is_full_without_audit() {
        let c = PrepareContext::NONE;
        assert_eq!(c.verify_mode, VerifyMode::Full);
        assert!(!c.is_auditing());
        assert_eq!(c.audit_launch_id(), -1);
        assert_eq!(PrepareContext::default(), PrepareContext::NONE);
        assert_eq!(PrepareContext::full_verify(), PrepareContext::NONE);
    }

    #[test]
    fn none_records_nothing() {
        let dir = tempfile::tempdir().unwrap();
        // No audit root: nothing is written anywhere.
        PrepareContext::NONE.record_redownload(
            &dir.path().join("mods/a.jar"),
            Some("aa"),
            Some("aa"),
            Some("bb"),
            Some(HashAlgo::Sha1),
        );
        assert_eq!(std::fs::read_dir(dir.path()).unwrap().count(), 0);
    }

    #[test]
    fn for_launch_audits_into_the_root() {
        let dir = tempfile::tempdir().unwrap();
        let c = PrepareContext::for_launch(VerifyMode::FastPath, Some(dir.path().into()), 7);
        assert!(c.is_auditing());
        assert_eq!(c.verify_mode, VerifyMode::FastPath);
        c.record_redownload(
            &dir.path().join("mods").join("a.jar"),
            Some("aa"),
            Some("aa"),
            Some("bb"),
            Some(HashAlgo::Sha1),
        );
        let log = std::fs::read_to_string(dir.path().join(crate::audit::AUDIT_FILE)).unwrap();
        assert!(log.contains("\"launch\":7"));
        assert!(log.contains("\"file\":\"mods/a.jar\""));
    }
}
