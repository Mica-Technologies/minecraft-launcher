// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The ordered rule table. Each detector looks for its regex in the crash text and, on a hit,
//! builds a diagnosis. [`DETECTORS`] runs most-specific first; the first hit wins.
//!
//! The patterns are the Java ones with one adjustment: Java's `\w` and `\d` are ASCII-only,
//! while the Rust `regex` crate's are Unicode, so they are spelled as explicit ASCII classes
//! (`[0-9A-Za-z_]`, `[0-9]`) to match exactly what the Java matched.

use std::path::Path;
use std::sync::LazyLock;

use regex::Regex;

use crate::analyze::{Env, PackInfo};
use crate::diagnosis::{Category, CrashAction, CrashDiagnosis, PackFolder, Severity, Suggestion};
use crate::text::{Text, TextArg};

/// The input every detector sees.
pub(crate) struct Input<'a> {
    pub text: &'a str,
    pub pack: Option<&'a PackInfo<'a>>,
    pub env: &'a Env,
}

/// Names each rule of the table, in run order.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum DetectorId {
    /// `java.lang.OutOfMemoryError`.
    OutOfMemory,
    /// Class compiled for a newer Java.
    JavaVersion,
    /// No space left on the disk.
    DiskFull,
    /// File locked by another process.
    FileLock,
    /// Native library failed to load.
    NativeLibrary,
    /// OptiFine and Sodium both present.
    OptiFineSodium,
    /// Mixin failed to apply.
    Mixin,
    /// Mod built for another Minecraft version.
    ModMinecraftVersion,
    /// Structured Forge dependency message.
    ForgeDependency,
    /// JEI / REI recipe collision.
    JeiRei,
    /// Generic mod-loading failure.
    ModLoading,
    /// Audio init failure.
    Audio,
    /// GPU / OpenGL init failure.
    Gpu,
    /// World data unreadable.
    WorldCorruption,
    /// Session rejected.
    Auth,
}

/// One rule of the table.
pub(crate) struct Detector {
    pub id: DetectorId,
    pub detect: fn(&Input<'_>) -> Option<CrashDiagnosis>,
}

/// The rule table, most specific first. The ordering is the point: OOM beats the downstream
/// NoClassDef noise; OptiFine + Sodium beats mixin; MC-version beats the Forge-dependency and
/// generic mod-loading rules; JEI/REI beats generic mod-loading.
#[rustfmt::skip]
pub(crate) static DETECTORS: &[Detector] = &[
    Detector { id: DetectorId::OutOfMemory, detect: detect_out_of_memory },
    Detector { id: DetectorId::JavaVersion, detect: detect_java_version },
    Detector { id: DetectorId::DiskFull, detect: detect_disk_full },
    Detector { id: DetectorId::FileLock, detect: detect_file_lock },
    Detector { id: DetectorId::NativeLibrary, detect: detect_native_library },
    Detector { id: DetectorId::OptiFineSodium, detect: detect_optifine_sodium },
    Detector { id: DetectorId::Mixin, detect: detect_mixin },
    Detector { id: DetectorId::ModMinecraftVersion, detect: detect_mod_mc_version },
    Detector { id: DetectorId::ForgeDependency, detect: detect_forge_dependency },
    Detector { id: DetectorId::JeiRei, detect: detect_jei_rei },
    Detector { id: DetectorId::ModLoading, detect: detect_mod_loading },
    Detector { id: DetectorId::Audio, detect: detect_audio },
    Detector { id: DetectorId::Gpu, detect: detect_gpu },
    Detector { id: DetectorId::WorldCorruption, detect: detect_world },
    Detector { id: DetectorId::Auth, detect: detect_auth },
];

/// The detector ids in run order.
pub fn detector_order() -> impl Iterator<Item = DetectorId> {
    DETECTORS.iter().map(|d| d.id)
}

fn re(pattern: &str) -> Regex {
    // The patterns are constants covered by a test that compiles every one of them.
    Regex::new(pattern).unwrap_or_else(|e| panic!("invalid crash pattern {pattern}: {e}"))
}

macro_rules! pattern {
    ($name:ident, $pat:expr) => {
        pub(crate) static $name: LazyLock<Regex> = LazyLock::new(|| re($pat));
    };
}

pattern!(OOM, r"java\.lang\.OutOfMemoryError(?::\s*([^\r\n]+))?");
pattern!(
    JAVA_VERSION,
    r"(java\.lang\.UnsupportedClassVersionError|has been compiled by a more recent version of the Java Runtime|class file version ([0-9]+)\.[0-9]+)"
);
pattern!(CLASS_FILE_VERSION, r"class file version ([0-9]+)\.[0-9]+");
pattern!(
    DISK_FULL,
    r"(No space left on device|There is not enough space on the disk|Disk full)"
);
pattern!(
    FILE_LOCK,
    r"The process cannot access the file because it is being used by another process|FileSystemException:.*Access is denied|java\.nio\.file\.AccessDeniedException|Sharing violation"
);
pattern!(
    NATIVE_LIB,
    r"java\.lang\.UnsatisfiedLinkError|Could not (?:locate|load) the native library"
);
pattern!(OPTIFINE, r"(?i)optifine|optifabric");
pattern!(SODIUM, r"(?i)\bsodium\b|me\.jellysquid\.mods\.sodium");
pattern!(
    MIXIN,
    r"org\.spongepowered\.asm\.mixin\.transformer\.throwables\.Mixin[0-9A-Za-z_]+|MixinApplyError|MixinTransformerError"
);
pattern!(
    MIXIN_OWNER,
    r"mixins?\.([a-z0-9_\-]+)\.json|@Mixin\(([^)]+)\)"
);
pattern!(
    MOD_MC_VERSION,
    r"(?:requires|requires loaded|compiled for|compiled against)\s+(?:Minecraft\s+)?(?:version\s+)?([0-9]+\.[0-9]+(?:\.[0-9]+)?)\s+(?:but(?: you have| we have| this version is)?|but is being loaded by)\s+(?:Minecraft\s+)?(?:version\s+)?([0-9]+\.[0-9]+(?:\.[0-9]+)?)"
);
pattern!(
    FORGE_DEPENDENCY,
    r"Mod\s+'([^']+)'\s*\([^)]+\)\s*requires\s+([0-9A-Za-z_\-]+)\s+([0-9.\[\](),~^]+)|Mod\s+([0-9A-Za-z_\-]+)\s+requires\s+([0-9A-Za-z_\-]+)\s+version\s+([0-9.\[\](),~^]+)"
);
pattern!(
    JEI_REI_DUP,
    r"Duplicate recipe(?: id| identifier)|mezz\.jei.*RecipeRegistry|me\.shedaniel\.rei.*Recipe(?:Display|Registry)Exception"
);
pattern!(
    JEI_AND_REI_DUAL,
    r"(?si)(?:mezz\.jei|jei-[0-9]|just enough items).*?(?:me\.shedaniel\.rei|roughly enough items|rei-[0-9])|(?:me\.shedaniel\.rei|roughly enough items|rei-[0-9]).*?(?:mezz\.jei|jei-[0-9]|just enough items)"
);
pattern!(
    MOD_LOADING,
    r"net\.minecraftforge\.fml\.(?:loading\.moddiscovery\.[0-9A-Za-z_$]+|ModLoadingException)|requires Minecraft Forge|missing dependencies|java\.lang\.NoClassDefFoundError|java\.lang\.ClassNotFoundException"
);
pattern!(
    MISSING_CLASS,
    r"(?:NoClassDefFoundError|ClassNotFoundException):\s*([0-9A-Za-z_/.$]+)"
);
pattern!(
    AUDIO,
    r"Could not (?:open|create|initialize) (?:audio|sound|OpenAL)|OpenAL [Ee]rror|AL_INVALID_(?:DEVICE|OPERATION)|com\.mojang\.blaze3d\.audio\.[0-9A-Za-z_]+Exception|paInternalError|ALSA lib|No default audio device"
);
pattern!(
    GPU,
    r"(?:Pixel format not accelerated|Failed to create OpenGL|GLFW.*(?:error|fail)|org\.lwjgl\.opengl\.GLException|Could not (?:create|initialize) display|GL_OUT_OF_MEMORY)"
);
pattern!(
    WORLD,
    r"RegionFileException|ChunkLoadException|Failed to load chunk|net\.minecraft\.nbt\.NbtException|Invalid level format|Could not load level"
);
pattern!(
    AUTH,
    r"Could not authenticate against|Bad login|Invalid session ID|Authentication error"
);
pattern!(
    EXCEPTION_PHRASE,
    r"((?:[a-zA-Z_$][0-9A-Za-z_$]*\.)+[A-Z][0-9A-Za-z_$]*(?:Exception|Error))(?::\s*([^\r\n]+))?"
);

/// Every pattern, for the compile test.
#[cfg(test)]
pub(crate) fn all_patterns() -> Vec<&'static Regex> {
    vec![
        &OOM,
        &JAVA_VERSION,
        &CLASS_FILE_VERSION,
        &DISK_FULL,
        &FILE_LOCK,
        &NATIVE_LIB,
        &OPTIFINE,
        &SODIUM,
        &MIXIN,
        &MIXIN_OWNER,
        &MOD_MC_VERSION,
        &FORGE_DEPENDENCY,
        &JEI_REI_DUP,
        &JEI_AND_REI_DUAL,
        &MOD_LOADING,
        &MISSING_CLASS,
        &AUDIO,
        &GPU,
        &WORLD,
        &AUTH,
        &EXCEPTION_PHRASE,
    ]
}

fn diagnosis(
    category: Category,
    severity: Severity,
    title: Text,
    summary: Text,
    suggestions: Vec<Suggestion>,
) -> Option<CrashDiagnosis> {
    Some(CrashDiagnosis {
        category,
        severity,
        title,
        summary,
        suggestions,
    })
}

fn critical(
    category: Category,
    title: Text,
    summary: Text,
    suggestions: Vec<Suggestion>,
) -> Option<CrashDiagnosis> {
    diagnosis(category, Severity::Critical, title, summary, suggestions)
}

// ---------------------------------------------------------------------------------------------
//  Shared suggestion builders
// ---------------------------------------------------------------------------------------------

/// Primary "Open Mods Folder" + "Open Install Folder"; empty without a pack.
fn open_folder_suggestions(pack: Option<&PackInfo<'_>>) -> Vec<Suggestion> {
    if pack.is_none() {
        return Vec::new();
    }
    vec![
        Suggestion::primary(
            Text::key("crash.action.openModsFolder"),
            CrashAction::OpenPackFolder(PackFolder::Mods),
        ),
        Suggestion::of(
            Text::key("crash.action.openInstallFolder"),
            CrashAction::OpenPackFolder(PackFolder::Root),
        ),
    ]
}

/// "Open Crash Reports"; empty without a pack.
fn open_crash_reports_suggestion(pack: Option<&PackInfo<'_>>) -> Vec<Suggestion> {
    if pack.is_none() {
        return Vec::new();
    }
    vec![Suggestion::of(
        Text::key("crash.action.openCrashReports"),
        CrashAction::OpenPackFolder(PackFolder::CrashReports),
    )]
}

fn open_mods_folder(primary: bool) -> Suggestion {
    Suggestion {
        label: Text::key("crash.action.openModsFolder"),
        action: Some(CrashAction::OpenPackFolder(PackFolder::Mods)),
        primary,
    }
}

/// A "Disable <jar>" suggestion for the first jar in `<root>/mods` (by name, so the choice is
/// deterministic) whose lower-cased name ends in `.jar` and contains `substring`. `None`
/// without a pack root, a mods folder, or a match.
pub(crate) fn maybe_disable_mod(
    pack: Option<&PackInfo<'_>>,
    substring: &str,
) -> Option<Suggestion> {
    let root: &Path = pack?.root?;
    let mods = root.join("mods");
    if !mods.is_dir() {
        return None;
    }
    let entries = std::fs::read_dir(&mods).ok()?;
    let mut candidates: Vec<(String, std::path::PathBuf)> = entries
        .filter_map(Result::ok)
        .filter_map(|e| {
            let name = e.file_name().to_string_lossy().into_owned();
            let lower = name.to_lowercase();
            (lower.ends_with(".jar") && lower.contains(substring)).then(|| (name, e.path()))
        })
        .collect();
    candidates.sort_by(|a, b| a.0.cmp(&b.0));
    let (name, path) = candidates.into_iter().next()?;
    Some(Suggestion::of(
        Text::format("crash.action.disableMod", vec![TextArg::Str(name)]),
        CrashAction::DisableMod(path),
    ))
}

fn non_blank(s: Option<&str>) -> Option<&str> {
    s.filter(|v| !v.trim().is_empty())
}

// ---------------------------------------------------------------------------------------------
//  Detectors
// ---------------------------------------------------------------------------------------------

fn detect_out_of_memory(input: &Input<'_>) -> Option<CrashDiagnosis> {
    let caps = OOM.captures(input.text)?;
    let detail = non_blank(caps.get(1).map(|m| m.as_str()));

    let current_max_mb = input.env.max_ram_mb;
    let current_gb = (current_max_mb / 1024).max(1);
    let system_gb = input.env.system_ram_gb;
    // Suggest +2 GB, capped at about 75% of system RAM (leave headroom for the OS).
    let cap = if system_gb > 0 {
        (system_gb * 3 / 4).max(4)
    } else {
        u64::MAX
    };
    let suggested_gb = current_gb.saturating_add(2).min(cap);

    let mut suggestions = Vec::new();
    if suggested_gb > current_gb {
        suggestions.push(Suggestion::primary(
            Text::format("crash.oom.increaseRam", vec![gb(suggested_gb)]),
            CrashAction::SetMaxRamMb(suggested_gb.saturating_mul(1024)),
        ));
    }
    suggestions.push(Suggestion::of(
        Text::key("crash.oom.openRamSettings"),
        CrashAction::OpenSettings,
    ));

    let detail_part = match detail {
        Some(d) => Text::format("crash.oom.detailSuffix", vec![d.trim().into()]),
        None => Text::empty(),
    };
    let mut summary = Text::format(
        "crash.oom.summary",
        vec![detail_part.into(), gb(current_gb)],
    );
    if system_gb > 0 {
        summary = Text::Concat(vec![
            summary,
            Text::format("crash.oom.systemTotal", vec![gb(system_gb)]),
        ]);
    }
    critical(
        Category::OutOfMemory,
        Text::key("crash.oom.title"),
        summary,
        suggestions,
    )
}

fn gb(n: u64) -> TextArg {
    TextArg::Int(i64::try_from(n).unwrap_or(i64::MAX))
}

/// Class-file major version → Java release: `>= 49` subtracts 44 (61 → "17"); anything else
/// (or a non-number) is returned as-is.
pub(crate) fn java_release_for_class_file_version(major: &str) -> String {
    match major.parse::<i64>() {
        Ok(v) if v >= 49 => (v - 44).to_string(),
        _ => major.to_owned(),
    }
}

fn detect_java_version(input: &Input<'_>) -> Option<CrashDiagnosis> {
    if !JAVA_VERSION.is_match(input.text) {
        return None;
    }
    let target_part = match CLASS_FILE_VERSION.captures(input.text) {
        Some(c) => Text::format(
            "crash.javaVersion.targetSuffix",
            vec![java_release_for_class_file_version(&c[1]).into()],
        ),
        None => Text::empty(),
    };
    let manifest_part = if input.pack.is_some() {
        Text::key("crash.javaVersion.manifestSuffix")
    } else {
        Text::empty()
    };
    critical(
        Category::JavaVersion,
        Text::key("crash.javaVersion.title"),
        Text::format(
            "crash.javaVersion.summary",
            vec![target_part.into(), manifest_part.into()],
        ),
        vec![
            Suggestion::primary(
                Text::key("crash.action.openRuntime"),
                CrashAction::OpenRuntime,
            ),
            Suggestion::of(
                Text::key("crash.action.openSettings"),
                CrashAction::OpenSettings,
            ),
        ],
    )
}

fn detect_disk_full(input: &Input<'_>) -> Option<CrashDiagnosis> {
    if !DISK_FULL.is_match(input.text) {
        return None;
    }
    critical(
        Category::DiskIo,
        Text::key("crash.diskFull.title"),
        Text::key("crash.diskFull.summary"),
        open_folder_suggestions(input.pack),
    )
}

fn detect_file_lock(input: &Input<'_>) -> Option<CrashDiagnosis> {
    if !FILE_LOCK.is_match(input.text) {
        return None;
    }
    let mut suggestions = Vec::new();
    // The Java added this even without a pack and then failed inside its task; it only makes
    // sense with a pack to open.
    if input.pack.is_some() {
        suggestions.push(Suggestion::primary(
            Text::key("crash.action.openInstallFolder"),
            CrashAction::OpenPackFolder(PackFolder::Root),
        ));
    }
    suggestions.extend(open_crash_reports_suggestion(input.pack));
    critical(
        Category::FileLock,
        Text::key("crash.fileLock.title"),
        Text::key("crash.fileLock.summary"),
        suggestions,
    )
}

fn detect_native_library(input: &Input<'_>) -> Option<CrashDiagnosis> {
    if !NATIVE_LIB.is_match(input.text) {
        return None;
    }
    let mut suggestions = Vec::new();
    // Reinstall is the canonical fix, but needs a manifest URL to install from.
    if input.pack.and_then(|p| non_blank(p.url)).is_some() {
        suggestions.push(Suggestion::primary(
            Text::key("crash.action.reinstallModpack"),
            CrashAction::ReinstallPack,
        ));
    }
    suggestions.extend(open_folder_suggestions(input.pack));
    suggestions.extend(open_crash_reports_suggestion(input.pack));
    critical(
        Category::NativeLibrary,
        Text::key("crash.nativeLib.title"),
        Text::key("crash.nativeLib.summary"),
        suggestions,
    )
}

fn detect_optifine_sodium(input: &Input<'_>) -> Option<CrashDiagnosis> {
    if !OPTIFINE.is_match(input.text) || !SODIUM.is_match(input.text) {
        return None;
    }
    let mut suggestions: Vec<Suggestion> = [
        maybe_disable_mod(input.pack, "optifine"),
        maybe_disable_mod(input.pack, "sodium"),
    ]
    .into_iter()
    .flatten()
    .collect();
    if suggestions.is_empty() {
        suggestions.push(open_mods_folder(true));
    } else {
        suggestions[0].primary = true;
        suggestions.push(open_mods_folder(false));
    }
    critical(
        Category::LoaderConflict,
        Text::key("crash.optifineSodium.title"),
        Text::key("crash.optifineSodium.summary"),
        suggestions,
    )
}

fn detect_mixin(input: &Input<'_>) -> Option<CrashDiagnosis> {
    if !MIXIN.is_match(input.text) {
        return None;
    }
    // Only `mixins.<mod>.json` (or `@Mixin(...)`) is captured; `<mod>.mixins.json` is not.
    // Kept as-is for parity with the Java.
    let culprit = MIXIN_OWNER
        .captures(input.text)
        .and_then(|c| c.get(1).or_else(|| c.get(2)))
        .map(|m| m.as_str().to_owned());
    let culprit_part = match &culprit {
        Some(m) => Text::format("crash.mixin.culpritSuffix", vec![m.as_str().into()]),
        None => Text::empty(),
    };
    let mut suggestions = Vec::new();
    if let Some(m) = &culprit
        && let Some(disable) = maybe_disable_mod(input.pack, &m.to_lowercase())
    {
        suggestions.push(disable.into_primary());
    }
    suggestions.extend(open_folder_suggestions(input.pack));
    suggestions.extend(open_crash_reports_suggestion(input.pack));
    critical(
        Category::MixinConflict,
        Text::key("crash.mixin.title"),
        Text::format("crash.mixin.summary", vec![culprit_part.into()]),
        suggestions,
    )
}

fn detect_mod_mc_version(input: &Input<'_>) -> Option<CrashDiagnosis> {
    let c = MOD_MC_VERSION.captures(input.text)?;
    let required = c[1].to_owned();
    let current = c[2].to_owned();
    critical(
        Category::ModLoading,
        Text::format(
            "crash.modMcMismatch.title",
            vec![required.clone().into(), current.clone().into()],
        ),
        Text::format(
            "crash.modMcMismatch.summary",
            vec![required.into(), current.into()],
        ),
        open_folder_suggestions(input.pack),
    )
}

fn detect_forge_dependency(input: &Input<'_>) -> Option<CrashDiagnosis> {
    let c = FORGE_DEPENDENCY.captures(input.text)?;
    // Either alternation branch fires; take whichever groups matched.
    let pick = |a: usize, b: usize| -> String {
        c.get(a)
            .or_else(|| c.get(b))
            .map(|m| m.as_str().to_owned())
            .unwrap_or_default()
    };
    let requiring = pick(1, 4);
    let required = pick(2, 5);
    let range = pick(3, 6);
    critical(
        Category::ModLoading,
        Text::key("crash.forgeDep.title"),
        Text::format(
            "crash.forgeDep.summary",
            vec![requiring.into(), required.into(), range.into()],
        ),
        open_folder_suggestions(input.pack),
    )
}

fn detect_jei_rei(input: &Input<'_>) -> Option<CrashDiagnosis> {
    let dup = JEI_REI_DUP.is_match(input.text);
    let dual = JEI_AND_REI_DUAL.is_match(input.text);
    if !dup && !dual {
        return None;
    }
    let mut suggestions = Vec::new();
    let (title, summary) = if dual {
        suggestions.extend(
            [
                maybe_disable_mod(input.pack, "jei"),
                maybe_disable_mod(input.pack, "rei"),
            ]
            .into_iter()
            .flatten(),
        );
        if let Some(first) = suggestions.first_mut() {
            first.primary = true;
        }
        ("crash.jeiRei.titleDual", "crash.jeiRei.summaryDual")
    } else {
        (
            "crash.jeiRei.titleCollision",
            "crash.jeiRei.summaryCollision",
        )
    };
    suggestions.push(open_mods_folder(false));
    critical(
        Category::ModLoading,
        Text::key(title),
        Text::key(summary),
        suggestions,
    )
}

fn detect_mod_loading(input: &Input<'_>) -> Option<CrashDiagnosis> {
    if !MOD_LOADING.is_match(input.text) {
        return None;
    }
    let summary = match MISSING_CLASS.captures(input.text) {
        Some(c) => Text::format(
            "crash.modLoading.summaryWithClass",
            vec![c[1].replace('/', ".").into()],
        ),
        None => Text::key("crash.modLoading.summaryGeneric"),
    };
    critical(
        Category::ModLoading,
        Text::key("crash.modLoading.title"),
        summary,
        open_folder_suggestions(input.pack),
    )
}

fn detect_audio(input: &Input<'_>) -> Option<CrashDiagnosis> {
    if !AUDIO.is_match(input.text) {
        return None;
    }
    // Non-fatal: the game carries on without sound.
    diagnosis(
        Category::Audio,
        Severity::Warning,
        Text::key("crash.audio.title"),
        Text::key("crash.audio.summary"),
        open_crash_reports_suggestion(input.pack),
    )
}

fn detect_gpu(input: &Input<'_>) -> Option<CrashDiagnosis> {
    if !GPU.is_match(input.text) {
        return None;
    }
    critical(
        Category::Gpu,
        Text::key("crash.gpu.title"),
        Text::key("crash.gpu.summary"),
        Vec::new(),
    )
}

fn detect_world(input: &Input<'_>) -> Option<CrashDiagnosis> {
    if !WORLD.is_match(input.text) {
        return None;
    }
    critical(
        Category::WorldCorruption,
        Text::key("crash.world.title"),
        Text::key("crash.world.summary"),
        open_folder_suggestions(input.pack),
    )
}

fn detect_auth(input: &Input<'_>) -> Option<CrashDiagnosis> {
    if !AUTH.is_match(input.text) {
        return None;
    }
    critical(
        Category::Auth,
        Text::key("crash.auth.title"),
        Text::key("crash.auth.summary"),
        vec![Suggestion::primary(
            Text::key("crash.action.openSettings"),
            CrashAction::OpenSettings,
        )],
    )
}
