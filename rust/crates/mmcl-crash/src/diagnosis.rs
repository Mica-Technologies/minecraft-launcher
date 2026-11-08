// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The analyzer's output: [`CrashDiagnosis`], its enums, and [`Suggestion`]s whose actions are
//! data ([`CrashAction`]) for the front end to carry out.

use std::path::{Path, PathBuf};

use crate::text::{Text, TextArg};

/// Failure family; picks the icon and broad category text in the GUI.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Category {
    /// Java heap exhausted (`java.lang.OutOfMemoryError`).
    OutOfMemory,
    /// A class compiled for a newer Java than the runtime in use.
    JavaVersion,
    /// Forge / loader could not load the pack's mods.
    ModLoading,
    /// A mixin patch failed to apply.
    MixinConflict,
    /// Two mods that cannot coexist (OptiFine + Sodium).
    LoaderConflict,
    /// Graphics / OpenGL initialization failure.
    Gpu,
    /// Audio initialization failure (non-fatal).
    Audio,
    /// A native library (LWJGL, JNI) failed to load.
    NativeLibrary,
    /// A save file could not be read.
    WorldCorruption,
    /// The disk ran out of space.
    DiskIo,
    /// A file was locked by another process.
    FileLock,
    /// The game rejected the session token.
    Auth,
    /// Nothing specific was recognised.
    Unknown,
}

impl Category {
    /// The Java enum constant name (`OUT_OF_MEMORY`, ...), for MCP and log parity.
    pub fn as_str(self) -> &'static str {
        match self {
            Category::OutOfMemory => "OUT_OF_MEMORY",
            Category::JavaVersion => "JAVA_VERSION",
            Category::ModLoading => "MOD_LOADING",
            Category::MixinConflict => "MIXIN_CONFLICT",
            Category::LoaderConflict => "LOADER_CONFLICT",
            Category::Gpu => "GPU",
            Category::Audio => "AUDIO",
            Category::NativeLibrary => "NATIVE_LIBRARY",
            Category::WorldCorruption => "WORLD_CORRUPTION",
            Category::DiskIo => "DISK_IO",
            Category::FileLock => "FILE_LOCK",
            Category::Auth => "AUTH",
            Category::Unknown => "UNKNOWN",
        }
    }
}

/// How serious the failure is; drives the banner colour.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Severity {
    /// The game could not run.
    Critical,
    /// The game kept going in a degraded state (audio only).
    Warning,
    /// Informational (unused by the current detectors, kept for parity).
    Info,
}

impl Severity {
    /// The Java enum constant name (`CRITICAL`, ...).
    pub fn as_str(self) -> &'static str {
        match self {
            Severity::Critical => "CRITICAL",
            Severity::Warning => "WARNING",
            Severity::Info => "INFO",
        }
    }
}

/// A folder of the pack that a suggestion opens.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum PackFolder {
    /// The pack's install (root) folder.
    Root,
    /// `<root>/mods`.
    Mods,
    /// `<root>/crash-reports`.
    CrashReports,
}

impl PackFolder {
    /// The sub-folder name under the pack root (`""` for the root itself), as the Java uses
    /// in `log.crashAnalyzer.openFolderFailed`.
    pub fn subfolder(self) -> &'static str {
        match self {
            PackFolder::Root => "",
            PackFolder::Mods => "mods",
            PackFolder::CrashReports => "crash-reports",
        }
    }

    /// The folder's path under `root`.
    pub fn path_in(self, root: &Path) -> PathBuf {
        match self {
            PackFolder::Root => root.to_path_buf(),
            other => root.join(other.subfolder()),
        }
    }
}

/// What a suggestion does when clicked. The analyzer only describes it; the front end runs it.
///
/// Behaviour the front end owes each action (from the Java):
/// - `SetMaxRamMb`: set `maxRam` in the config, then log `log.crashAnalyzer.bumpedRam` with the
///   value in GB.
/// - `OpenSettings` / `OpenRuntime`: ask the main window `may_navigate_away()` first (it protects
///   unsaved work), then show the Settings / Runtime screen; failure logs
///   `log.crashAnalyzer.openSettingsFailed` / `openRuntimeFailed`.
/// - `OpenPackFolder`: create the folder if missing ([`crate::actions::ensure_pack_folder`]),
///   then open it in the OS file manager; failure logs `log.crashAnalyzer.openFolderFailed`.
/// - `ReinstallPack`: on a background task, log `log.crashAnalyzer.reinstallRequested`, then
///   `if uninstall(pack) { install_by_url(url) }` (uninstall refuses while the pack runs);
///   failure logs `log.crashAnalyzer.reinstallFailed`.
/// - `DisableMod`: [`crate::actions::disable_mod`] (rename `x.jar` to `x.jar.disabled`), logging
///   `log.crashAnalyzer.renamedMod` or `log.crashAnalyzer.disableModFailed`.
/// - `OpenUrl`: open in the browser; failure logs `log.crashAnalyzer.openSearchFailed`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum CrashAction {
    /// Raise the launcher's max RAM to this many MB.
    SetMaxRamMb(u64),
    /// Go to the Settings screen.
    OpenSettings,
    /// Go to the Runtime management screen.
    OpenRuntime,
    /// Open a folder of the pack.
    OpenPackFolder(PackFolder),
    /// Uninstall the pack and install it again from its URL.
    ReinstallPack,
    /// Disable the mod jar at this path.
    DisableMod(PathBuf),
    /// Open this URL in the browser.
    OpenUrl(String),
}

/// One suggested next step.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Suggestion {
    /// Button label.
    pub label: Text,
    /// What the button does; `None` for a hint with no action.
    pub action: Option<CrashAction>,
    /// Whether this is the primary call to action (emphasised).
    pub primary: bool,
}

impl Suggestion {
    /// A non-primary suggestion.
    pub fn of(label: Text, action: CrashAction) -> Self {
        Suggestion {
            label,
            action: Some(action),
            primary: false,
        }
    }

    /// The primary suggestion.
    pub fn primary(label: Text, action: CrashAction) -> Self {
        Suggestion {
            label,
            action: Some(action),
            primary: true,
        }
    }

    /// A hint: a label with no action.
    pub fn hint(label: Text) -> Self {
        Suggestion {
            label,
            action: None,
            primary: false,
        }
    }

    /// The same suggestion marked primary.
    pub fn into_primary(mut self) -> Self {
        self.primary = true;
        self
    }
}

/// The structured result of analyzing a crash.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CrashDiagnosis {
    /// Failure family.
    pub category: Category,
    /// How serious it is.
    pub severity: Severity,
    /// Short headline.
    pub title: Text,
    /// One or two sentences on the cause.
    pub summary: Text,
    /// Ordered suggestions; the first primary one is the main call to action.
    pub suggestions: Vec<Suggestion>,
}

impl CrashDiagnosis {
    /// The fallback when nothing could be identified.
    pub fn unknown(exit_code: i32) -> Self {
        Self::unknown_with_suggestions(exit_code, Vec::new())
    }

    /// The fallback carrying suggestions (the web-search hint).
    pub fn unknown_with_suggestions(exit_code: i32, suggestions: Vec<Suggestion>) -> Self {
        CrashDiagnosis {
            category: Category::Unknown,
            severity: Severity::Critical,
            title: Text::key("crash.unknown.title"),
            summary: Text::format(
                "crash.unknown.summary",
                vec![TextArg::Int(i64::from(exit_code))],
            ),
            suggestions,
        }
    }
}
