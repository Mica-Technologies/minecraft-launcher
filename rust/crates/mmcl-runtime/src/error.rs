// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! [`RuntimeError`]: every way a runtime verify, install or delete can fail.
//!
//! Each variant maps to a localization key ([`RuntimeError::l10n_key`]) and its MessageFormat
//! arguments ([`RuntimeError::l10n_args`]); `Display` renders the message in the active locale.

use std::io;

use mmcl_i18n::FmtArg;
use mmcl_mojang::ManifestError;
use mmcl_net::DownloadError;

/// Why a runtime operation failed.
#[derive(Debug, thiserror::Error)]
pub enum RuntimeError {
    /// The caller cancelled (Java: thread interrupt). Never papered over with an old install.
    #[error("{}", localized(self))]
    Cancelled,
    /// The runtime is used by a running game, so it cannot be deleted.
    #[error("{}", localized(self))]
    InUse { component: String },
    /// A Mojang runtime could not be installed and no complete earlier install exists.
    #[error("{}", localized(self))]
    InstallFailed {
        component: String,
        #[source]
        cause: Box<RuntimeError>,
    },
    /// The Liberica JRE 8 could not be installed and no complete earlier install exists.
    #[error("{}", localized(self))]
    Jre8InstallFailed {
        #[source]
        cause: Box<RuntimeError>,
    },
    /// The host has no Mojang runtime platform and no other source either.
    #[error("{}", localized(self))]
    PlatformNotFound { platform: String },
    /// A runtime index or component manifest lookup or parse failed.
    #[error("{}", localized(self))]
    Manifest(#[from] ManifestError),
    /// A download failed (after the network layer's own retries).
    #[error("{}", localized(self))]
    Download(#[from] DownloadError),
    /// A local file operation failed.
    #[error("{}", localized(self))]
    Io(#[from] io::Error),
    /// A JSON document (API response, cached index) could not be read.
    #[error("{}", localized(self))]
    Json(String),
    /// A Mojang runtime file kept failing its SHA-1 after every attempt.
    #[error("{}", localized(self))]
    FileHashFailed {
        attempts: u32,
        relative_path: String,
        url: String,
    },
    /// One runtime file task failed; carries the cause text.
    #[error("{}", localized(self))]
    FileFailed(String),
    /// The Bell-SW API returned no JRE 8 release.
    #[error("{}", localized(self))]
    NoJre8Info,
    /// The downloaded JRE 8 archive failed its SHA-1.
    #[error("{}", localized(self))]
    Jre8HashFailed { file: String },
    /// A downloaded runtime archive (Liberica or Adoptium) failed its digest.
    #[error("{}", localized(self))]
    ArchiveHashFailed { algorithm: String, file: String },
    /// The runtime was installed but contains no Java executable.
    #[error("{}", localized(self))]
    JavaExecMissing,
    /// Extracting a runtime archive failed.
    #[error("{}", localized(self))]
    Extract(#[from] mmcl_archive::ExtractError),
    /// No Liberica or Adoptium build of this Java version exists for the host.
    #[error("{}", localized(self))]
    NoAltBuild { major: u32 },
    /// The component has no known Java version to fetch from a non-Mojang source.
    #[error("{}", localized(self))]
    UnsupportedComponent { component: String },
    /// An API named an archive whose file name is not a plain file name.
    #[error("{}", localized(self))]
    UnsafeArchiveName { name: String },
}

impl RuntimeError {
    /// `true` when the operation was cancelled (directly or as the cause of a wrapper).
    pub fn is_cancelled(&self) -> bool {
        match self {
            RuntimeError::Cancelled => true,
            RuntimeError::Download(e) => e.is_cancelled(),
            RuntimeError::InstallFailed { cause, .. }
            | RuntimeError::Jre8InstallFailed { cause } => cause.is_cancelled(),
            _ => false,
        }
    }

    /// The localization key of the message a user sees.
    pub fn l10n_key(&self) -> &'static str {
        match self {
            RuntimeError::Cancelled => "runtime.error.interrupted",
            RuntimeError::InUse { .. } => "runtime.error.inUse",
            RuntimeError::InstallFailed { .. } => "log.runtimeManager.installFailed",
            RuntimeError::Jre8InstallFailed { .. } => "log.runtimeManager.jre8InstallFailed",
            RuntimeError::PlatformNotFound { .. } => "runtime.error.platformNotFound",
            RuntimeError::Manifest(e) => e.l10n_key(),
            RuntimeError::Download(e) => e.message_key(),
            RuntimeError::Io(_) => "runtime.error.io",
            RuntimeError::Json(_) => "runtime.error.json",
            RuntimeError::FileHashFailed { .. } => "runtime.error.fileHashFailed",
            RuntimeError::FileFailed(_) => "runtime.error.fileFailed",
            RuntimeError::NoJre8Info => "runtime.error.noJre8Info",
            RuntimeError::Jre8HashFailed { .. } => "runtime.error.jre8HashFailed",
            RuntimeError::ArchiveHashFailed { .. } => "runtime.error.archiveHashFailed",
            RuntimeError::JavaExecMissing => "log.runtimeManager.javaExecMissing",
            RuntimeError::Extract(_) => "runtime.error.extractFailed",
            RuntimeError::NoAltBuild { .. } => "runtime.error.noAltBuild",
            RuntimeError::UnsupportedComponent { .. } => "runtime.error.unsupportedComponent",
            RuntimeError::UnsafeArchiveName { .. } => "runtime.error.unsafeArchiveName",
        }
    }

    /// The MessageFormat arguments for [`Self::l10n_key`].
    pub fn l10n_args(&self) -> Vec<String> {
        match self {
            RuntimeError::Cancelled | RuntimeError::NoJre8Info | RuntimeError::JavaExecMissing => {
                Vec::new()
            }
            RuntimeError::InUse { component }
            | RuntimeError::UnsupportedComponent { component } => {
                vec![component.clone()]
            }
            RuntimeError::InstallFailed { component, cause } => {
                vec![component.clone(), cause.to_string()]
            }
            RuntimeError::Jre8InstallFailed { cause } => vec![cause.to_string()],
            RuntimeError::PlatformNotFound { platform } => vec![platform.clone()],
            RuntimeError::Manifest(e) => e.l10n_args(),
            // The network error's own text carries the URL and status; the key's template
            // takes it as {0}.
            RuntimeError::Download(e) => vec![e.to_string()],
            RuntimeError::Io(e) => vec![e.to_string()],
            RuntimeError::Json(m) | RuntimeError::FileFailed(m) => vec![m.clone()],
            RuntimeError::FileHashFailed {
                attempts,
                relative_path,
                url,
            } => vec![attempts.to_string(), relative_path.clone(), url.clone()],
            RuntimeError::Jre8HashFailed { file } => vec![file.clone()],
            RuntimeError::ArchiveHashFailed { algorithm, file } => {
                vec![algorithm.clone(), file.clone()]
            }
            RuntimeError::Extract(e) => vec![e.to_string()],
            RuntimeError::NoAltBuild { major } => vec![major.to_string()],
            RuntimeError::UnsafeArchiveName { name } => vec![name.clone()],
        }
    }
}

fn localized(e: &RuntimeError) -> String {
    // A network error renders as its own (English, Java-compatible) text rather than through
    // a template whose arity we do not own.
    if let RuntimeError::Download(d) = e {
        return d.to_string();
    }
    let args = e.l10n_args();
    let fmt: Vec<FmtArg<'_>> = args.iter().map(|a| FmtArg::from(a.as_str())).collect();
    mmcl_i18n::format(e.l10n_key(), &fmt)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn keys_and_args() {
        let e = RuntimeError::InUse {
            component: "java-runtime-gamma".into(),
        };
        assert_eq!(e.l10n_key(), "runtime.error.inUse");
        assert_eq!(e.l10n_args(), vec!["java-runtime-gamma"]);
        assert!(e.to_string().contains("java-runtime-gamma"));

        let e = RuntimeError::FileHashFailed {
            attempts: 3,
            relative_path: "bin/java".into(),
            url: "https://x/y".into(),
        };
        let s = e.to_string();
        assert!(
            s.contains("bin/java") && s.contains("https://x/y") && s.contains('3'),
            "{s}"
        );
    }

    #[test]
    fn cancellation_is_seen_through_wrappers() {
        let e = RuntimeError::InstallFailed {
            component: "c".into(),
            cause: Box::new(RuntimeError::Cancelled),
        };
        assert!(e.is_cancelled());
        assert!(RuntimeError::Download(DownloadError::Cancelled("u".into())).is_cancelled());
        assert!(!RuntimeError::NoJre8Info.is_cancelled());
    }

    #[test]
    fn every_key_exists_in_the_bundles() {
        let samples = [
            RuntimeError::Cancelled,
            RuntimeError::InUse {
                component: "c".into(),
            },
            RuntimeError::InstallFailed {
                component: "c".into(),
                cause: Box::new(RuntimeError::NoJre8Info),
            },
            RuntimeError::Jre8InstallFailed {
                cause: Box::new(RuntimeError::NoJre8Info),
            },
            RuntimeError::PlatformNotFound {
                platform: "p".into(),
            },
            RuntimeError::Io(io::Error::other("x")),
            RuntimeError::Json("x".into()),
            RuntimeError::FileFailed("x".into()),
            RuntimeError::NoJre8Info,
            RuntimeError::Jre8HashFailed { file: "f".into() },
            RuntimeError::ArchiveHashFailed {
                algorithm: "SHA-256".into(),
                file: "f".into(),
            },
            RuntimeError::JavaExecMissing,
            RuntimeError::NoAltBuild { major: 17 },
            RuntimeError::UnsupportedComponent {
                component: "c".into(),
            },
            RuntimeError::UnsafeArchiveName {
                name: "../x".into(),
            },
        ];
        for e in samples {
            assert!(
                mmcl_i18n::contains(e.l10n_key()),
                "missing key {}",
                e.l10n_key()
            );
        }
    }
}
