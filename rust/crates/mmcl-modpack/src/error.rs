// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The crate's error type. Every variant renders through a localization key, so the text a
//! user sees follows the UI language; [`ModpackError::key`] and [`ModpackError::args`] expose the
//! key for front ends that localize themselves.

use std::io;
use std::path::PathBuf;

use mmcl_i18n::{t, tf};
use mmcl_net::DownloadError;
use mmcl_scan::ScanBlocked;

/// Everything the modpack runtime can fail with.
#[derive(Debug, thiserror::Error)]
pub enum ModpackError {
    /// A managed file resolves outside the folder it belongs to.
    #[error("{}", tf!("managedFile.error.outsidePack", .0.display().to_string()))]
    OutsidePack(PathBuf),
    /// A managed file's URL is not HTTPS (nor a trusted `jar:file:` URL).
    #[error("{}", tf!("managedFile.error.nonHttps", .0))]
    NonHttps(String),
    /// Every download attempt produced bytes that fail the declared hash.
    #[error("{}", tf!("managedFile.error.hashFailed", .attempts.to_string(), .path.display().to_string(), .url))]
    HashFailed {
        attempts: u32,
        path: PathBuf,
        url: String,
    },
    /// A local-only file (no URL) is missing.
    #[error("{}", tf!("managedFile.error.missingNoUrl", .0.display().to_string()))]
    MissingNoUrl(PathBuf),
    /// Offline, and the file on disk does not match its hash.
    #[error("{}", tf!("managedFile.error.offlineHashMismatch", .0.display().to_string()))]
    OfflineHashMismatch(PathBuf),
    /// Offline, and the file is missing.
    #[error("{}", tf!("managedFile.error.offlineMissing", .0.display().to_string()))]
    OfflineMissing(PathBuf),
    /// The pack is a failed-load placeholder: its manifest could not be fetched.
    #[error("{}", tf!("fileSync.error.manifestFetchFailed", .0))]
    ManifestFetchFailed(String),
    /// The pack is an install-index stub that was never fully loaded.
    #[error("{}", tf!("fileSync.error.manifestStub", .0))]
    ManifestStub(String),
    /// Required mods are missing after the sync.
    #[error("{}", tf!("fileSync.error.modsMissing", .pack, .missing.to_string(), .required.to_string(), .names))]
    ModsMissing {
        pack: String,
        missing: usize,
        required: usize,
        names: String,
    },
    /// The mod downloads did not finish within 30 minutes.
    #[error("{}", t!("fileSync.error.modsTimedOut"))]
    ModsTimedOut,
    /// The mod downloads were cancelled.
    #[error("{}", t!("fileSync.error.modsInterrupted"))]
    ModsInterrupted,
    /// A mod download failed.
    #[error("{}", tf!("fileSync.error.modsFailed", .0))]
    ModsFailed(String),
    /// The manifest names a loader the launcher does not support.
    #[error("{}", tf!("modpack.error.unsupportedLoader", .0))]
    UnsupportedLoader(String),
    /// No loader factory has been registered (the launch crate registers one at startup).
    #[error("{}", t!("modpack.error.noLoaderFactory"))]
    NoLoaderFactory,
    /// The pack name has no ASCII letters or digits, so it has no folder of its own.
    #[error("{}", tf!("modpack.error.emptyFolderName", .0))]
    EmptyFolderName(String),
    /// A download failed.
    #[error("{}", tf!("modpack.error.downloadFailed", .path.display().to_string(), .source.to_string()))]
    Download {
        path: PathBuf,
        #[source]
        source: DownloadError,
    },
    /// A manifest could not be loaded (the message is already localized).
    #[error("{0}")]
    Manifest(String),
    /// The pack is not in the available list.
    #[error("{}", tf!("modpack.error.notAvailable", .0))]
    NotAvailable(String),
    /// The pack is launching or running.
    #[error("{}", tf!("notification.pack.running.uninstall", .0))]
    PackRunning(String),
    /// The pre-launch security scan found blocking issues.
    #[error("{}", .0.message())]
    ScanDetection(Box<ScanBlocked>),
    /// The security scan itself failed.
    #[error("{0}")]
    Scan(String),
    /// An importer refused or failed (the message is already localized).
    #[error("{0}")]
    Import(String),
    /// A local read or write failed.
    #[error("{0}")]
    Io(#[from] io::Error),
}

impl ModpackError {
    /// The localization key of the message.
    pub fn key(&self) -> &'static str {
        match self {
            ModpackError::OutsidePack(_) => "managedFile.error.outsidePack",
            ModpackError::NonHttps(_) => "managedFile.error.nonHttps",
            ModpackError::HashFailed { .. } => "managedFile.error.hashFailed",
            ModpackError::MissingNoUrl(_) => "managedFile.error.missingNoUrl",
            ModpackError::OfflineHashMismatch(_) => "managedFile.error.offlineHashMismatch",
            ModpackError::OfflineMissing(_) => "managedFile.error.offlineMissing",
            ModpackError::ManifestFetchFailed(_) => "fileSync.error.manifestFetchFailed",
            ModpackError::ManifestStub(_) => "fileSync.error.manifestStub",
            ModpackError::ModsMissing { .. } => "fileSync.error.modsMissing",
            ModpackError::ModsTimedOut => "fileSync.error.modsTimedOut",
            ModpackError::ModsInterrupted => "fileSync.error.modsInterrupted",
            ModpackError::ModsFailed(_) => "fileSync.error.modsFailed",
            ModpackError::UnsupportedLoader(_) => "modpack.error.unsupportedLoader",
            ModpackError::NoLoaderFactory => "modpack.error.noLoaderFactory",
            ModpackError::EmptyFolderName(_) => "modpack.error.emptyFolderName",
            ModpackError::Download { .. } => "modpack.error.downloadFailed",
            ModpackError::NotAvailable(_) => "modpack.error.notAvailable",
            ModpackError::PackRunning(_) => "notification.pack.running.uninstall",
            ModpackError::Manifest(_)
            | ModpackError::ScanDetection(_)
            | ModpackError::Scan(_)
            | ModpackError::Import(_)
            | ModpackError::Io(_) => "modpack.error.generic",
        }
    }

    /// The message arguments for [`ModpackError::key`] (for `generic`, the whole message).
    pub fn args(&self) -> Vec<String> {
        let p = |p: &PathBuf| p.display().to_string();
        match self {
            ModpackError::OutsidePack(x)
            | ModpackError::MissingNoUrl(x)
            | ModpackError::OfflineHashMismatch(x)
            | ModpackError::OfflineMissing(x) => vec![p(x)],
            ModpackError::NonHttps(s)
            | ModpackError::ManifestFetchFailed(s)
            | ModpackError::ManifestStub(s)
            | ModpackError::ModsFailed(s)
            | ModpackError::UnsupportedLoader(s)
            | ModpackError::EmptyFolderName(s)
            | ModpackError::NotAvailable(s)
            | ModpackError::PackRunning(s) => vec![s.clone()],
            ModpackError::HashFailed {
                attempts,
                path,
                url,
            } => vec![attempts.to_string(), p(path), url.clone()],
            ModpackError::ModsMissing {
                pack,
                missing,
                required,
                names,
            } => vec![
                pack.clone(),
                missing.to_string(),
                required.to_string(),
                names.clone(),
            ],
            ModpackError::Download { path, source } => vec![p(path), source.to_string()],
            ModpackError::ModsTimedOut
            | ModpackError::ModsInterrupted
            | ModpackError::NoLoaderFactory => Vec::new(),
            other => vec![other.to_string()],
        }
    }

    /// `true` for the two "never launch an unloaded manifest" guards.
    pub fn is_incomplete_manifest(&self) -> bool {
        matches!(
            self,
            ModpackError::ManifestFetchFailed(_) | ModpackError::ManifestStub(_)
        )
    }
}

/// The crate's result type.
pub type Result<T, E = ModpackError> = std::result::Result<T, E>;
