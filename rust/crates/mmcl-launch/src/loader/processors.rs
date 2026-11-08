// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Forge / NeoForge install processors (Java `runForgeProcessors`): the patched-client check,
//! processor libraries (contained paths, HTTPS only, hash-checked), argument resolution, and
//! running each processor jar with the pack's Java runtime.
//!
//! Fix over Java (B-4): files extracted from the installer for the processors
//! (`libraries/forge-installer-data/`) are dropped when the installer's SHA-1 changes, so a
//! Forge version change in the same pack never reuses a stale `client.lzma`.

use std::collections::{HashMap, HashSet};
use std::fs;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::Duration;

use indexmap::IndexMap;
use mmcl_i18n::{t, tf};
use mmcl_mojang::forge_profile::{InstallProfile, SidedValue};
use mmcl_mojang::maven;
use mmcl_progress::ProgressSink;
use mmcl_util::hash::{HashAlgorithm, constant_time_hex_eq, try_file_hash};
use mmcl_util::process::{ChildIoMode, build_command};

use super::forge::{INSTALL_PROFILE_JSON, remove_if_exists};
use crate::batch::{blocking, join_rel};
use crate::error::{LaunchError, Result};
use crate::lerr;
use crate::run::PackRun;

/// Each processor may run this long.
pub const PROCESSOR_TIMEOUT: Duration = Duration::from_secs(10 * 60);
/// Download attempts for a processor library.
pub const LIBRARY_ATTEMPTS: u32 = 3;
/// The folder (under `libraries/`) of files extracted from the installer.
pub const INSTALLER_DATA_DIR: &str = "forge-installer-data";
/// The installer SHA-1 the extracted files came from.
pub const INSTALLER_SHA_FILE: &str = ".installer-sha1";

/// What the processors need from the loader.
pub(crate) struct ProcessorEnv {
    pub installer: PathBuf,
    pub entries: Arc<HashSet<String>>,
    pub minecraft_version: String,
}

/// The inputs of argument resolution.
#[derive(Clone, Debug)]
pub struct ArgContext {
    pub data: IndexMap<String, SidedValue>,
    pub side: String,
    pub libs: PathBuf,
    pub root: PathBuf,
    pub installer: PathBuf,
    pub minecraft_version: String,
    pub minecraft_jar: PathBuf,
}

fn abs(p: &Path) -> PathBuf {
    std::path::absolute(p).unwrap_or_else(|_| p.to_path_buf())
}

fn lib_path(libs: &Path, coord: &str) -> Result<PathBuf> {
    Ok(abs(&join_rel(
        libs,
        &maven::to_relative_path_strict(coord)?,
    )))
}

/// Resolves one `data` value: `'literal'`, `[coord]`, `/installer/path` (extracted under
/// `forge-installer-data` when missing), or a literal.
pub fn resolve_data_value(value: &str, c: &ArgContext) -> Result<String> {
    if value.len() >= 2 && value.starts_with('\'') && value.ends_with('\'') {
        return Ok(value[1..value.len() - 1].to_string());
    }
    if value.starts_with('[') && value.ends_with(']') {
        return Ok(lib_path(&c.libs, value)?.to_string_lossy().into_owned());
    }
    if let Some(entry) = value.strip_prefix('/') {
        let base = abs(&c.libs.join(INSTALLER_DATA_DIR));
        let target = mmcl_core::paths::normalize_lexical(&join_rel(&base, entry));
        if entry.is_empty() || entry.contains('\0') || !target.starts_with(&base) || target == base
        {
            return Err(lerr!("forge.error.unsafeLibraryPath", value));
        }
        if !target.exists() {
            match crate::jar::extract_entry(&c.installer, entry, &target) {
                Ok(true) => {}
                Ok(false) => return Err(lerr!("forge.error.installerEntryMissing", entry)),
                Err(e) => {
                    mmcl_log::warn_silent(&e.to_string());
                    return Err(lerr!("forge.error.installerExtractFailed", entry));
                }
            }
        }
        return Ok(target.to_string_lossy().into_owned());
    }
    Ok(value.to_string())
}

/// Resolves one processor argument: `{KEY}` (data first, then the magic tokens), `[coord]`,
/// or a literal. An unknown token passes through with a warning.
pub fn resolve_processor_arg(arg: &str, c: &ArgContext) -> Result<String> {
    if arg.len() >= 2 && arg.starts_with('{') && arg.ends_with('}') {
        let key = &arg[1..arg.len() - 1];
        if let Some(v) = c.data.get(key).and_then(|s| s.get(&c.side)) {
            return resolve_data_value(v, c);
        }
        let s = |p: &Path| p.to_string_lossy().into_owned();
        return Ok(match key {
            "SIDE" => c.side.clone(),
            "MINECRAFT_JAR" => s(&c.minecraft_jar),
            "MINECRAFT_VERSION" => c.minecraft_version.clone(),
            "ROOT" => s(&c.root),
            "LIBRARY_DIR" => s(&c.libs),
            "INSTALLER" => s(&c.installer),
            _ => {
                mmcl_log::warn_silent(&tf!("log.forgeLoader.unrecognizedProcessorToken", arg));
                arg.to_string()
            }
        });
    }
    if arg.starts_with('[') && arg.ends_with(']') {
        return Ok(lib_path(&c.libs, arg)?.to_string_lossy().into_owned());
    }
    Ok(arg.to_string())
}

/// Refuses a processor-library path with a NUL, a leading separator or a drive letter, and
/// resolves it inside `libs` (lexically; a sibling with a shared prefix is outside).
pub fn checked_library_path(libs: &Path, path: &str) -> Result<PathBuf> {
    let b = path.as_bytes();
    if path.contains('\0') || path.starts_with(['/', '\\']) || (b.len() > 1 && b[1] == b':') {
        return Err(lerr!("forge.error.unsafeLibraryPath", path));
    }
    let base = mmcl_core::paths::normalize_lexical(&abs(libs));
    let resolved = mmcl_core::paths::normalize_lexical(&join_rel(&base, path));
    if !resolved.starts_with(&base) || resolved == base {
        return Err(lerr!("forge.error.libraryEscapes", path));
    }
    Ok(resolved)
}

fn sha1_matches(p: &Path, expected: &str) -> bool {
    try_file_hash(p, HashAlgorithm::Sha1).is_ok_and(|h| constant_time_hex_eq(&h, expected.trim()))
}

/// Declared processor-artifact hashes (by forward-slash path) and the ones verified so far.
#[derive(Debug, Default)]
struct Declared {
    hashes: HashMap<String, Option<String>>,
    verified: HashSet<String>,
}

impl Declared {
    fn verify(&mut self, libs: &Path, rel: &str) -> Result<()> {
        let Some(Some(sha)) = self.hashes.get(rel) else {
            return Ok(());
        };
        if self.verified.contains(rel) {
            return Ok(());
        }
        if !sha1_matches(&join_rel(libs, rel), sha) {
            return Err(lerr!("forge.error.processorHashFailed", rel));
        }
        self.verified.insert(rel.to_string());
        Ok(())
    }
}

/// Runs the install processors for the run's side.
pub(crate) async fn run_processors(
    pe: &ProcessorEnv,
    run: &PackRun,
    sink: &Arc<dyn ProgressSink>,
    runtime_component: &str,
) -> Result<()> {
    let side = run.env.side_name().to_string();
    let libs = run.libraries_dir()?;
    let installer = pe.installer.clone();
    let profile = {
        let i = installer.clone();
        match blocking(move || crate::jar::read_entry(&i, INSTALL_PROFILE_JSON)).await? {
            Ok(Some(b)) => InstallProfile::parse(&b)
                .map_err(|_| lerr!("forge.error.installProfileUnreadable"))?,
            Ok(None) => {
                mmcl_log::std(&t!("log.forgeLoader.noInstallProfileLegacy"));
                return Ok(());
            }
            Err(_) => return Err(lerr!("forge.error.installProfileUnreadable")),
        }
    };
    if !profile.has_processors_and_data() {
        mmcl_log::std(&t!("log.forgeLoader.noProcessorsSkipping"));
        return Ok(());
    }
    fs::create_dir_all(&libs)?;
    refresh_installer_data(&installer, &libs).await?;

    // The patched client: skip everything when it exists and matches its hash.
    let mut patched_output: Option<PathBuf> = None;
    if let Some(coord) = profile.data_value("PATCHED", &side) {
        let f = lib_path(&libs, &coord)?;
        if fs::metadata(&f).is_ok_and(|m| m.is_file() && m.len() > 0) {
            let sha = profile.patched_sha(&side);
            let ok = match &sha {
                None => true,
                Some(s) => {
                    let (p, s) = (f.clone(), s.clone());
                    blocking(move || sha1_matches(&p, &s)).await?
                }
            };
            if ok {
                mmcl_log::std(&t!("log.forgeLoader.patchedClientExists"));
                return Ok(());
            }
            mmcl_log::warn_silent(&t!("log.forgeLoader.patchedClientCorrupt"));
            remove_if_exists(&f).map_err(|_| {
                lerr!(
                    "log.forgeLoader.patchedClientUndeletable",
                    f.display().to_string()
                )
            })?;
        }
        patched_output = Some(f);
    }

    let declared = acquire_libraries(pe, run, &profile, &libs, sink).await?;
    let res = run_all(pe, run, &profile, declared, sink, runtime_component).await;
    if res.is_err() {
        // A processor run that stopped part-way (failure, timeout or cancel, also between two
        // processors) must not leave a patched client that the next launch trusts.
        if let Some(p) = &patched_output
            && p.exists()
            && remove_if_exists(p).is_err()
        {
            mmcl_log::warn_silent(&tf!(
                "log.forgeLoader.patchedClientUndeletable",
                p.display().to_string()
            ));
        }
    }
    res
}

async fn run_all(
    pe: &ProcessorEnv,
    run: &PackRun,
    profile: &InstallProfile,
    declared: Declared,
    sink: &Arc<dyn ProgressSink>,
    runtime_component: &str,
) -> Result<()> {
    let side = run.env.side_name().to_string();
    let libs = run.libraries_dir()?;
    let root = run.root()?;
    let installer = pe.installer.clone();
    run.check_cancelled()?;

    let java = run
        .env
        .runtimes
        .java_path(runtime_component, &run.cancel)
        .await?;
    let ctx = ArgContext {
        data: profile.data(),
        side: side.clone(),
        libs: abs(&libs),
        root: abs(&root),
        installer: abs(&installer),
        minecraft_version: pe.minecraft_version.clone(),
        minecraft_jar: abs(&root.join("bin").join("minecraft.jar")),
    };
    mmcl_log::std(&tf!(
        "log.forgeLoader.runningProcessors",
        &pe.minecraft_version
    ));
    let processors: Vec<_> = profile
        .processors()
        .into_iter()
        .filter(|p| p.applies_to(&side))
        .collect();
    let total = processors.len();
    let mut declared = declared;
    for (i, proc) in processors.iter().enumerate() {
        run.check_cancelled()?;
        let jar_coord = proc.jar.clone().ok_or_else(|| {
            LaunchError::Manifest(mmcl_mojang::ManifestError::MissingField("jar".into()))
        })?;
        let jar_rel = maven::to_relative_path_strict(&jar_coord)?;
        declared.verify(&libs, &jar_rel)?;
        let jar_path = abs(&join_rel(&libs, &jar_rel));
        let mut cp = vec![jar_path.to_string_lossy().into_owned()];
        for c in &proc.classpath {
            let rel = maven::to_relative_path_strict(c)?;
            declared.verify(&libs, &rel)?;
            cp.push(abs(&join_rel(&libs, &rel)).to_string_lossy().into_owned());
        }
        let main = {
            let j = jar_path.clone();
            match blocking(move || crate::jar::read_main_class(&j)).await? {
                Ok(Some(m)) => m,
                Ok(None) => return Err(lerr!("forge.error.processorNoMainClass", &jar_coord)),
                Err(_) => return Err(lerr!("forge.error.processorManifestUnreadable", &jar_coord)),
            }
        };
        let args = proc.args.as_ref().ok_or_else(|| {
            LaunchError::Manifest(mmcl_mojang::ManifestError::MissingField("args".into()))
        })?;
        let mut argv = vec![
            java.to_string_lossy().into_owned(),
            "-cp".to_string(),
            crate::command::join_classpath(&cp, crate::command::PATH_SEPARATOR),
            main,
        ];
        {
            let (args, c) = (args.clone(), ctx.clone());
            let resolved = blocking(move || {
                args.iter()
                    .map(|a| resolve_processor_arg(a, &c))
                    .collect::<Result<Vec<String>>>()
            })
            .await??;
            argv.extend(resolved);
        }
        mmcl_log::std(&tf!(
            "log.forgeLoader.runningProcessor",
            (i + 1).to_string(),
            total.to_string(),
            &jar_coord
        ));
        run_one(&argv, &root, &run.cancel, &jar_coord).await?;
        sink.submit_progress(
            &tf!("forgeLoader.completedProcessor", &jar_coord),
            10.0 / total.max(1) as f64,
        );
    }
    mmcl_log::std(&t!("log.forgeLoader.processorsCompleted"));
    Ok(())
}

/// Clears `forge-installer-data` when it was extracted from another installer, and records
/// the current installer's SHA-1.
async fn refresh_installer_data(installer: &Path, libs: &Path) -> Result<()> {
    let (i, data) = (installer.to_path_buf(), libs.join(INSTALLER_DATA_DIR));
    blocking(move || -> std::io::Result<()> {
        let sha = try_file_hash(&i, HashAlgorithm::Sha1)?;
        let marker = data.join(INSTALLER_SHA_FILE);
        let recorded = fs::read_to_string(&marker)
            .ok()
            .map(|s| s.trim().to_string());
        if recorded.as_deref() == Some(sha.as_str()) {
            return Ok(());
        }
        if data.exists() {
            fs::remove_dir_all(&data)?;
        }
        fs::create_dir_all(&data)?;
        crate::jar::write_atomic(&marker, sha.as_bytes())
    })
    .await??;
    Ok(())
}

/// Makes every `install_profile.libraries` artifact present and verified.
async fn acquire_libraries(
    pe: &ProcessorEnv,
    run: &PackRun,
    profile: &InstallProfile,
    libs: &Path,
    sink: &Arc<dyn ProgressSink>,
) -> Result<Declared> {
    let mut d = Declared::default();
    for lib in profile.libraries() {
        run.check_cancelled()?;
        let Some(a) = lib.artifact() else {
            continue;
        };
        let path = a.path.clone().ok_or_else(|| {
            LaunchError::Manifest(mmcl_mojang::ManifestError::MissingField("path".into()))
        })?;
        let url = a.url.clone().unwrap_or_default();
        let sha = a.sha1.clone();
        if sha.is_none() {
            mmcl_log::warn_silent(&tf!("log.forgeLoader.libraryNoHash", &path));
        }
        d.hashes.insert(path.clone(), sha.clone());
        let resolved = checked_library_path(libs, &path)?;
        if resolved.is_file() {
            let ok = match &sha {
                None => true,
                Some(s) => {
                    let (p, s) = (resolved.clone(), s.clone());
                    blocking(move || sha1_matches(&p, &s)).await?
                }
            };
            if ok {
                if sha.is_some() {
                    d.verified.insert(path.clone());
                }
                continue;
            }
            mmcl_log::warn_silent(&tf!("log.forgeLoader.existingLibraryFailedHash", &path));
            let _ = remove_if_exists(&resolved);
        }
        if url.trim().is_empty() {
            let entry = format!("maven/{path}");
            if !pe.entries.contains(&entry) {
                // B-12: kept (the processor fails later), but say so.
                mmcl_log::warn_silent(&tf!("log.forgeLoader.processorLibraryUnavailable", &path));
                continue;
            }
            let (i, r) = (pe.installer.clone(), resolved.clone());
            match blocking(move || crate::jar::extract_entry(&i, &entry, &r)).await? {
                Ok(true) => {}
                _ => return Err(lerr!("forge.error.embeddedExtractFailed", &path)),
            }
            if let Some(s) = &sha {
                let (p, s) = (resolved.clone(), s.clone());
                if !blocking(move || sha1_matches(&p, &s)).await? {
                    let _ = remove_if_exists(&resolved);
                    return Err(lerr!("forge.error.embeddedHashFailed", &path));
                }
                d.verified.insert(path.clone());
            }
            continue;
        }
        if !url.trim().to_ascii_lowercase().starts_with("https://") {
            return Err(lerr!("forge.error.nonHttpsLibrary", &url));
        }
        if let Some(p) = resolved.parent() {
            fs::create_dir_all(p)?;
        }
        let mut ok = false;
        for attempt in 1..=LIBRARY_ATTEMPTS {
            run.check_cancelled()?;
            if let Err(e) = run.env.http.download(url.trim(), &resolved, None).await {
                if e.is_cancelled() {
                    return Err(LaunchError::Cancelled);
                }
                mmcl_log::warn_silent(&e.to_string());
            } else {
                let good = match &sha {
                    None => true,
                    Some(s) => {
                        let (p, s) = (resolved.clone(), s.clone());
                        blocking(move || sha1_matches(&p, &s)).await?
                    }
                };
                if good {
                    ok = true;
                    break;
                }
            }
            let _ = remove_if_exists(&resolved);
            mmcl_log::warn_silent(&tf!(
                "log.forgeLoader.libraryFailedHashAttempt",
                attempt.to_string(),
                LIBRARY_ATTEMPTS.to_string(),
                &path
            ));
        }
        if !ok {
            return Err(lerr!(
                "forge.error.processorLibHashFailed",
                LIBRARY_ATTEMPTS.to_string(),
                &path,
                &url
            ));
        }
        if sha.is_some() {
            d.verified.insert(path.clone());
        }
        sink.submit_progress(&tf!("forgeLoader.downloadedProcessorLib", &path), 1.0);
    }
    Ok(d)
}

/// Runs one processor: cwd the pack root, stdin closed, stdout/stderr to the launcher's;
/// at most [`PROCESSOR_TIMEOUT`]; killed on cancel, timeout or any failure.
async fn run_one(
    argv: &[String],
    root: &Path,
    cancel: &mmcl_net::CancelToken,
    jar: &str,
) -> Result<()> {
    let mut cmd = build_command(argv, root, ChildIoMode::Inherit)
        .map_err(|e| lerr!("forge.error.processorRunFailed", e.to_string()))?;
    cmd.stdin(std::process::Stdio::null());
    let mut cmd = tokio::process::Command::from(cmd);
    cmd.kill_on_drop(true);
    let mut child = cmd
        .spawn()
        .map_err(|e| lerr!("forge.error.processorRunFailed", e.to_string()))?;
    let outcome = tokio::select! {
        r = child.wait() => Some(r),
        _ = tokio::time::sleep(PROCESSOR_TIMEOUT) => None,
        _ = cancel.cancelled() => {
            let _ = child.kill().await;
            return Err(LaunchError::Cancelled);
        }
    };
    match outcome {
        None => {
            let _ = child.kill().await;
            Err(lerr!("forge.error.processorTimedOut", jar))
        }
        Some(Err(e)) => {
            let _ = child.kill().await;
            Err(lerr!("forge.error.processorRunFailed", e.to_string()))
        }
        Some(Ok(status)) if status.success() => Ok(()),
        Some(Ok(status)) => Err(lerr!(
            "forge.error.processorExitCode",
            status
                .code()
                .map_or_else(|| "?".to_string(), |c| c.to_string()),
            jar
        )),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ctx(dir: &Path) -> ArgContext {
        let mut data = IndexMap::new();
        data.insert(
            "MAPPINGS".to_string(),
            SidedValue {
                client: Some("[de.oceanlabs.mcp:mcp_config:1.20.1:mappings@txt]".into()),
                server: None,
            },
        );
        data.insert(
            "BINPATCH".to_string(),
            SidedValue {
                client: Some("/data/client.lzma".into()),
                server: None,
            },
        );
        data.insert(
            "SIDE".to_string(),
            SidedValue {
                client: Some("'fromdata'".into()),
                server: None,
            },
        );
        data.insert(
            "ESCAPE".to_string(),
            SidedValue {
                client: Some("/../../evil".into()),
                server: None,
            },
        );
        let installer = dir.join("installer.jar");
        fs::write(
            &installer,
            crate::jar::build_jar(&[("data/client.lzma", b"lzma")]).unwrap(),
        )
        .unwrap();
        ArgContext {
            data,
            side: "client".into(),
            libs: dir.join("libraries"),
            root: dir.to_path_buf(),
            installer,
            minecraft_version: "1.20.1".into(),
            minecraft_jar: dir.join("bin/minecraft.jar"),
        }
    }

    #[test]
    fn processor_args_resolve() {
        let d = tempfile::tempdir().unwrap();
        let c = ctx(d.path());
        // Data beats magic.
        assert_eq!(resolve_processor_arg("{SIDE}", &c).unwrap(), "fromdata");
        let mut c2 = c.clone();
        c2.data.shift_remove("SIDE");
        assert_eq!(resolve_processor_arg("{SIDE}", &c2).unwrap(), "client");
        assert_eq!(
            resolve_processor_arg("{MINECRAFT_VERSION}", &c).unwrap(),
            "1.20.1"
        );
        assert_eq!(
            resolve_processor_arg("{MINECRAFT_JAR}", &c).unwrap(),
            c.minecraft_jar.to_string_lossy()
        );
        assert_eq!(
            resolve_processor_arg("{ROOT}", &c).unwrap(),
            c.root.to_string_lossy()
        );
        assert_eq!(
            resolve_processor_arg("{LIBRARY_DIR}", &c).unwrap(),
            c.libs.to_string_lossy()
        );
        assert_eq!(
            resolve_processor_arg("{INSTALLER}", &c).unwrap(),
            c.installer.to_string_lossy()
        );
        assert_eq!(resolve_processor_arg("{NOPE}", &c).unwrap(), "{NOPE}");
        assert_eq!(resolve_processor_arg("--task", &c).unwrap(), "--task");
        let m = resolve_processor_arg("{MAPPINGS}", &c).unwrap();
        assert!(
            m.replace('\\', "/")
                .ends_with("de/oceanlabs/mcp/mcp_config/1.20.1/mcp_config-1.20.1-mappings.txt"),
            "{m}"
        );
        let b =
            resolve_processor_arg("[net.minecraftforge:forge:1.20.1-47.2.0:client]", &c).unwrap();
        assert!(
            b.replace('\\', "/")
                .ends_with("forge-1.20.1-47.2.0-client.jar")
        );
        assert!(resolve_processor_arg("[a:../b:1]", &c).is_err());
    }

    #[test]
    fn installer_paths_extract_once_and_stay_contained() {
        let d = tempfile::tempdir().unwrap();
        let c = ctx(d.path());
        let p = resolve_processor_arg("{BINPATCH}", &c).unwrap();
        assert_eq!(fs::read(&p).unwrap(), b"lzma");
        assert!(Path::new(&p).starts_with(abs(&c.libs.join(INSTALLER_DATA_DIR))));
        let e = resolve_processor_arg("{ESCAPE}", &c).unwrap_err();
        assert_eq!(e.key(), "forge.error.unsafeLibraryPath");
        let e = resolve_data_value("/data/missing.lzma", &c).unwrap_err();
        assert_eq!(e.key(), "forge.error.installerEntryMissing");
        assert_eq!(resolve_data_value("'quoted'", &c).unwrap(), "quoted");
        assert_eq!(resolve_data_value("plain", &c).unwrap(), "plain");
    }

    #[test]
    fn library_paths_are_validated() {
        let d = tempfile::tempdir().unwrap();
        let libs = d.path().join("libraries");
        for bad in ["a\0b", "/abs/x.jar", "\\abs\\x.jar", "C:/x.jar"] {
            assert_eq!(
                checked_library_path(&libs, bad).unwrap_err().key(),
                "forge.error.unsafeLibraryPath",
                "{bad:?}"
            );
        }
        for bad in ["../x.jar", "a/../../x.jar", "../libraries-evil/x.jar", "."] {
            assert_eq!(
                checked_library_path(&libs, bad).unwrap_err().key(),
                "forge.error.libraryEscapes",
                "{bad:?}"
            );
        }
        assert!(
            checked_library_path(&libs, "a/b/c.jar")
                .unwrap()
                .ends_with("c.jar")
        );
    }

    #[test]
    fn declared_hashes_are_checked_once() {
        let d = tempfile::tempdir().unwrap();
        let libs = d.path();
        fs::create_dir_all(libs.join("a")).unwrap();
        fs::write(libs.join("a/b.jar"), b"jar").unwrap();
        let mut decl = Declared::default();
        decl.hashes
            .insert("a/b.jar".into(), Some(mmcl_util::hash::sha1_hex(b"jar")));
        decl.hashes.insert("a/c.jar".into(), Some("00".repeat(20)));
        decl.hashes.insert("a/d.jar".into(), None);
        decl.verify(libs, "a/b.jar").unwrap();
        assert!(decl.verified.contains("a/b.jar"));
        decl.verify(libs, "a/d.jar").unwrap();
        decl.verify(libs, "undeclared.jar").unwrap();
        assert_eq!(
            decl.verify(libs, "a/c.jar").unwrap_err().key(),
            "forge.error.processorHashFailed"
        );
    }

    #[tokio::test]
    async fn installer_data_is_dropped_when_the_installer_changes() {
        let d = tempfile::tempdir().unwrap();
        let libs = d.path().join("libraries");
        let inst = d.path().join("i.jar");
        fs::write(&inst, b"v1").unwrap();
        refresh_installer_data(&inst, &libs).await.unwrap();
        let stale = libs.join(INSTALLER_DATA_DIR).join("data/client.lzma");
        fs::create_dir_all(stale.parent().unwrap()).unwrap();
        fs::write(&stale, b"old").unwrap();
        refresh_installer_data(&inst, &libs).await.unwrap();
        assert!(stale.exists(), "same installer keeps the data");
        fs::write(&inst, b"v2").unwrap();
        refresh_installer_data(&inst, &libs).await.unwrap();
        assert!(!stale.exists(), "a new installer drops it");
        assert_eq!(
            fs::read_to_string(libs.join(INSTALLER_DATA_DIR).join(INSTALLER_SHA_FILE)).unwrap(),
            mmcl_util::hash::sha1_hex(b"v2")
        );
    }

    #[tokio::test]
    async fn a_failing_processor_reports_its_exit_code() {
        let d = tempfile::tempdir().unwrap();
        let cancel = mmcl_net::CancelToken::new();
        let argv: Vec<String> = if cfg!(windows) {
            vec!["cmd".into(), "/C".into(), "exit 3".into()]
        } else {
            vec!["sh".into(), "-c".into(), "exit 3".into()]
        };
        let e = run_one(&argv, d.path(), &cancel, "x:y:1")
            .await
            .unwrap_err();
        assert_eq!(e.key(), "forge.error.processorExitCode");
        assert_eq!(e.args()[0], "3");
        let ok: Vec<String> = if cfg!(windows) {
            vec!["cmd".into(), "/C".into(), "exit 0".into()]
        } else {
            vec!["true".into()]
        };
        run_one(&ok, d.path(), &cancel, "x").await.unwrap();
        let e = run_one(
            &["definitely-not-a-binary-xyz".to_string()],
            d.path(),
            &cancel,
            "x",
        )
        .await
        .unwrap_err();
        assert_eq!(e.key(), "forge.error.processorRunFailed");
    }
}
