// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! "Add to Minecraft Launcher" (Java `OfficialLauncherExporter`): a `custom` profile in the
//! official launcher's `launcher_profiles.json` pointing at a Mica-managed copy of the pack's
//! mods and configs, plus the loader install that profile needs. The profile key is a stable,
//! name-based UUID so re-exporting updates the same profile, and removal finds it again.

use std::path::{Path, PathBuf};
use std::sync::Arc;

use md5::{Digest, Md5};
use serde_json::{Map, Value};

use mmcl_core::DataPaths;
use mmcl_modpack::GameModPack;

use super::Engine;
use crate::error::{EngineError, EngineResult};
use crate::event::{Notification, NotificationKind, OperationId};
use crate::model::{OfficialExportOutcome, OfficialExportReport, OfficialExportStatus, PackId};

/// The namespace of the profile keys (same as Java, so both launchers manage one profile).
const MICA_NAMESPACE: &str = "7b21f6a4-3b4a-4c0d-9c2e-1de9b1c8a420";
const PROFILES_FILE: &str = "launcher_profiles.json";

/// The official launcher's data folder.
pub fn resolve_dot_minecraft() -> PathBuf {
    let home = dirs_home();
    if cfg!(target_os = "windows") {
        if let Some(appdata) = std::env::var_os("APPDATA").filter(|a| !a.is_empty()) {
            return PathBuf::from(appdata).join(".minecraft");
        }
        return home.join("AppData").join("Roaming").join(".minecraft");
    }
    if cfg!(target_os = "macos") {
        return home
            .join("Library")
            .join("Application Support")
            .join("minecraft");
    }
    home.join(".minecraft")
}

fn dirs_home() -> PathBuf {
    std::env::var_os(if cfg!(windows) { "USERPROFILE" } else { "HOME" })
        .map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from("."))
}

/// `true` when the official launcher's profile list exists.
pub fn is_official_launcher_available() -> bool {
    resolve_dot_minecraft().join(PROFILES_FILE).is_file()
}

/// A folder name from a profile name: control characters dropped, `<>:"/\|?*` as `_`, at
/// most 80 characters, `modpack` when nothing is left.
pub fn sanitize_folder_name(name: &str) -> String {
    let cleaned: String = name
        .chars()
        .filter(|c| (*c as u32) >= 0x20 && *c != '\u{7f}')
        .map(|c| if "<>:\"/\\|?*".contains(c) { '_' } else { c })
        .collect();
    let mut cleaned = cleaned.trim().to_string();
    if cleaned.chars().count() > 80 {
        cleaned = cleaned
            .chars()
            .take(80)
            .collect::<String>()
            .trim()
            .to_string();
    }
    if cleaned.is_empty() {
        "modpack".to_string()
    } else {
        cleaned
    }
}

/// `<pack name> (Mica)`.
pub fn profile_display_name(pack_name: &str, vanilla: bool) -> String {
    let base = if pack_name.trim().is_empty() {
        if vanilla { "Minecraft" } else { "Modpack" }
    } else {
        pack_name
    };
    format!("{base} (Mica)")
}

/// Java `UUID.nameUUIDFromBytes` (MD5, version 3).
pub fn name_uuid_from_bytes(bytes: &[u8]) -> String {
    let mut d: [u8; 16] = Md5::digest(bytes).into();
    d[6] = (d[6] & 0x0f) | 0x30;
    d[8] = (d[8] & 0x3f) | 0x80;
    uuid::Uuid::from_bytes(d).hyphenated().to_string()
}

/// The profile key of a pack: `mica-vanilla:<mc>` or `mica-modpack:<url or name>` under the
/// Mica namespace.
pub fn stable_profile_key(vanilla_version: Option<&str>, url: Option<&str>, name: &str) -> String {
    let identity = match vanilla_version {
        Some(v) => format!("mica-vanilla:{v}"),
        None => format!("mica-modpack:{}", url.unwrap_or(name)),
    };
    name_uuid_from_bytes(format!("{MICA_NAMESPACE}:{identity}").as_bytes())
}

/// The last few non-blank lines of an installer's error output (what a toast can show when
/// the installer gave no message of its own).
pub fn last_lines(text: &str) -> String {
    const KEEP: usize = 3;
    let lines: Vec<&str> = text
        .lines()
        .map(str::trim_end)
        .filter(|l| !l.trim().is_empty())
        .collect();
    lines[lines.len().saturating_sub(KEEP)..].join("\n")
}

/// The Mojang runtime component a Minecraft version runs on (Java's heuristic).
pub fn pick_runtime_component(mc_version: Option<&str>) -> &'static str {
    let Some(v) = mc_version.map(str::trim).filter(|v| !v.is_empty()) else {
        return "jre-legacy";
    };
    let parts: Vec<&str> = v.split(['.', '-', '_']).collect();
    let num = |i: usize| -> Option<i64> {
        match parts.get(i) {
            Some(p) => p.parse().ok(),
            None => Some(0),
        }
    };
    let (Some(major), Some(minor), Some(patch)) = (num(0), num(1), num(2)) else {
        return "jre-legacy";
    };
    if major != 1 {
        return "java-runtime-delta";
    }
    match (minor, patch) {
        (m, _) if m <= 16 => "jre-legacy",
        (17, _) => "java-runtime-alpha",
        (18, p) if p < 2 => "java-runtime-beta",
        (m, _) if m < 20 => "java-runtime-gamma",
        (20, p) if p < 5 => "java-runtime-gamma",
        _ => "java-runtime-delta",
    }
}

fn identity_of(pack: &GameModPack) -> (Option<String>, String) {
    let vanilla = pack.vanilla_mc_version().map(str::to_string);
    let key = stable_profile_key(vanilla.as_deref(), pack.manifest_url(), &pack.name());
    (vanilla, key)
}

/// The export game folder of a pack.
pub fn export_game_dir(paths: &DataPaths, pack: &GameModPack) -> PathBuf {
    paths
        .official_launcher_exports_dir()
        .join(sanitize_folder_name(&profile_display_name(
            &pack.name(),
            pack.is_vanilla(),
        )))
}

fn copy_recursively(src: &Path, dst: &Path) -> std::io::Result<()> {
    std::fs::create_dir_all(dst)?;
    for e in std::fs::read_dir(src)?.flatten() {
        let ft = e.file_type()?;
        if ft.is_symlink() {
            continue;
        }
        let to = dst.join(e.file_name());
        if ft.is_dir() {
            copy_recursively(&e.path(), &to)?;
        } else {
            std::fs::copy(e.path(), &to)?;
        }
    }
    Ok(())
}

/// Copies the pack's mods, configs, resource and shader packs and option files into the
/// export folder (folders are replaced, not merged).
pub fn copy_pack_contents(root: &Path, game_dir: &Path) -> std::io::Result<()> {
    for d in ["mods", "config", "resourcepacks", "shaderpacks"] {
        let src = root.join(d);
        if !src.is_dir() {
            continue;
        }
        let dst = game_dir.join(d);
        if dst.exists() {
            std::fs::remove_dir_all(&dst)?;
        }
        copy_recursively(&src, &dst)?;
    }
    for f in [
        "options.txt",
        "optionsof.txt",
        "optionsshaders.txt",
        "servers.dat",
    ] {
        let src = root.join(f);
        if src.is_file() {
            std::fs::create_dir_all(game_dir)?;
            std::fs::copy(&src, game_dir.join(f))?;
        }
    }
    Ok(())
}

fn read_profiles(file: &Path) -> std::io::Result<Map<String, Value>> {
    let text = std::fs::read_to_string(file)?;
    Ok(match serde_json::from_str::<Value>(&text) {
        Ok(Value::Object(m)) => m,
        _ => Map::new(),
    })
}

fn write_profiles(file: &Path, json: &Map<String, Value>) -> std::io::Result<()> {
    let text = serde_json::to_string_pretty(json).map_err(std::io::Error::other)?;
    let tmp = file.with_file_name(format!("{PROFILES_FILE}.tmp"));
    {
        use std::io::Write;
        let mut f = std::fs::File::create(&tmp)?;
        f.write_all(text.as_bytes())?;
        f.sync_all()?;
    }
    std::fs::rename(&tmp, file)
}

fn iso_now() -> String {
    chrono::Utc::now()
        .format("%Y-%m-%dT%H:%M:%S%.3fZ")
        .to_string()
}

/// The pack logo as a 64x64 PNG data URI.
fn logo_icon(root: &Path) -> Option<String> {
    let logo = root.join("logo.png");
    if !logo.is_file() {
        return None;
    }
    let encoded = (|| -> Result<String, String> {
        let img = image::open(&logo).map_err(|e| e.to_string())?;
        let small = img.resize_exact(64, 64, image::imageops::FilterType::CatmullRom);
        let mut buf = std::io::Cursor::new(Vec::new());
        small
            .write_to(&mut buf, image::ImageFormat::Png)
            .map_err(|e| e.to_string())?;
        use base64::Engine as _;
        Ok(format!(
            "data:image/png;base64,{}",
            base64::engine::general_purpose::STANDARD.encode(buf.into_inner())
        ))
    })();
    match encoded {
        Ok(s) => Some(s),
        Err(e) => {
            mmcl_log::warn_silent(&mmcl_i18n::tf!("log.officialExporter.encodeIconFailed", e));
            None
        }
    }
}

/// The installed Mica runtime's java (`javaw.exe` on Windows) for a component.
fn runtime_java(paths: &DataPaths, component: &str) -> Option<String> {
    let root = paths.runtime_component_dir(component);
    let exe = if cfg!(windows) { "javaw.exe" } else { "java" };
    std::fs::read_dir(&root)
        .ok()?
        .flatten()
        .filter(|e| e.path().is_dir())
        .map(|e| e.path().join("bin").join(exe))
        .find(|p| p.is_file())
        .and_then(|p| std::path::absolute(p).ok())
        .map(|p| p.display().to_string())
}

/// What a profile entry is built from.
pub(crate) struct ProfileInputs {
    pub name: String,
    pub version_id: String,
    pub game_dir: PathBuf,
    pub java_dir: Option<String>,
    pub max_ram_mb: i64,
    pub custom_jvm_args: String,
    pub icon: Option<String>,
}

/// Fills (or refreshes) one profile entry, keeping `created` and any keys it does not own.
pub(crate) fn populate_profile(profile: &mut Map<String, Value>, i: &ProfileInputs) {
    let now = iso_now();
    profile.insert("name".into(), Value::from(i.name.clone()));
    profile.insert("type".into(), Value::from("custom"));
    profile.insert("lastVersionId".into(), Value::from(i.version_id.clone()));
    profile.insert(
        "gameDir".into(),
        Value::from(
            std::path::absolute(&i.game_dir)
                .unwrap_or_else(|_| i.game_dir.clone())
                .display()
                .to_string(),
        ),
    );
    match &i.java_dir {
        Some(j) => {
            profile.insert("javaDir".into(), Value::from(j.clone()));
        }
        None => {
            profile.remove("javaDir");
        }
    }
    if profile.get("created").is_none_or(Value::is_null) {
        profile.insert("created".into(), Value::from(now.clone()));
    }
    profile.insert("lastUsed".into(), Value::from(now));
    let mut args = format!("-Xmx{}M", i.max_ram_mb);
    if !i.custom_jvm_args.trim().is_empty() {
        args.push(' ');
        args.push_str(i.custom_jvm_args.trim());
    }
    profile.insert("javaArgs".into(), Value::from(args));
    if let Some(icon) = &i.icon {
        profile.insert("icon".into(), Value::from(icon.clone()));
    }
}

/// Merges `profile` under `key` into the profile file.
pub(crate) fn upsert_profile(
    file: &Path,
    key: &str,
    inputs: &ProfileInputs,
) -> std::io::Result<()> {
    let mut json = read_profiles(file)?;
    let mut profiles = match json.remove("profiles") {
        Some(Value::Object(m)) => m,
        _ => Map::new(),
    };
    let mut profile = match profiles.remove(key) {
        Some(Value::Object(m)) => m,
        _ => Map::new(),
    };
    populate_profile(&mut profile, inputs);
    profiles.insert(key.to_string(), Value::Object(profile));
    json.insert("profiles".into(), Value::Object(profiles));
    write_profiles(file, &json)
}

/// Removes the profile `key`; `true` when it was there.
pub(crate) fn remove_profile(file: &Path, key: &str) -> std::io::Result<bool> {
    let mut json = read_profiles(file)?;
    let removed = match json.get_mut("profiles") {
        Some(Value::Object(p)) => p.remove(key).is_some(),
        _ => false,
    };
    if removed {
        write_profiles(file, &json)?;
    }
    Ok(removed)
}

impl Engine {
    async fn version_id_of(&self, pack: &Arc<GameModPack>) -> EngineResult<String> {
        if let Some(v) = pack.vanilla_mc_version() {
            if v.trim().is_empty() {
                return Err(EngineError::Other(
                    mmcl_i18n::t!("officialExport.error.noMcVersion").into_owned(),
                ));
            }
            return Ok(v.to_string());
        }
        let fail =
            |e: String| EngineError::Other(mmcl_i18n::tf!("import.export.loaderResolveFailed", e));
        let mc = pack
            .minecraft_version()
            .await
            .map_err(|e| fail(e.to_string()))?
            .unwrap_or_default();
        let lv = pack
            .loader_version()
            .await
            .map_err(|e| fail(e.to_string()))?
            .unwrap_or_default();
        let kind = pack
            .mod_loader_type()
            .and_then(|t| mmcl_launch::LoaderKind::from_type(&t).ok());
        Ok(mmcl_launch::export_install::compute_version_id(
            kind, &mc, &lv,
        ))
    }

    /// The pre-export facts of the official-launcher export (the UI's confirm step): whether
    /// the official launcher is there, the version id the profile will use, whether that
    /// version is installed there, and whether the pack already has a profile. Resolving the
    /// version id of a modded pack builds its loader (may download the loader installer).
    pub async fn official_export_status(&self, id: &PackId) -> EngineResult<OfficialExportStatus> {
        let pack = self.require_installed(id)?;
        let dot_mc = resolve_dot_minecraft();
        let available = dot_mc.join(PROFILES_FILE).is_file();
        let vanilla = pack.is_vanilla();
        let version_id = self.version_id_of(&pack).await?;
        let loader_installed =
            vanilla || mmcl_launch::export_install::is_version_installed(&dot_mc, &version_id);
        Ok(OfficialExportStatus {
            available,
            has_export: self.has_official_export(id),
            profile_name: profile_display_name(&pack.name(), vanilla),
            dot_minecraft: dot_mc,
            version_id,
            loader_installed,
            vanilla,
        })
    }

    /// Installs the pack's loader into the official launcher (Forge / NeoForge run their
    /// installer, Fabric writes its profile JSON). Returns the installed version id; a failure
    /// is a localized `officialExport.installLoader.failed.body` error. A vanilla pack needs
    /// nothing and succeeds at once.
    pub async fn install_official_loader(&self, id: &PackId) -> EngineResult<String> {
        let pack = self.require_installed(id)?;
        let dot_mc = resolve_dot_minecraft();
        let version_id = self.version_id_of(&pack).await?;
        if pack.is_vanilla() {
            return Ok(version_id);
        }
        let _busy = super::launch::BusyGuard::take(self, id, &pack.name())?;
        match self.run_official_loader_install(&pack, &dot_mc).await {
            None => Ok(version_id),
            Some(failure) => Err(failure),
        }
    }

    /// Runs the loader installer; `None` on success, else [`EngineError::LoaderInstallFailed`]
    /// with the installer's error text.
    async fn run_official_loader_install(
        &self,
        pack: &Arc<GameModPack>,
        dot_mc: &Path,
    ) -> Option<EngineError> {
        let r = mmcl_launch::export_install::install_loader_for_official_launcher(
            &self.inner.launch_env,
            pack,
            dot_mc,
            &mmcl_net::CancelToken::new(),
        )
        .await;
        if r.success {
            return None;
        }
        if let Some(stderr) = &r.installer_stderr {
            mmcl_log::warn_silent(stderr);
        }
        let loader = pack.loader_name().await.ok().flatten().unwrap_or_default();
        let message = if r.message.trim().is_empty() {
            r.installer_stderr
                .as_deref()
                .map(last_lines)
                .unwrap_or_default()
        } else {
            r.message.clone()
        };
        Some(EngineError::LoaderInstallFailed { message, loader })
    }

    /// [`Engine::export_official`], also returning the loader installer's error when the
    /// install ran and failed (the text Java's `installLoader.failed` toast showed).
    pub async fn export_official_report(
        &self,
        id: &PackId,
        install_loader: bool,
        op: OperationId,
    ) -> EngineResult<OfficialExportReport> {
        let res = self.export_official_inner(id, install_loader, op).await;
        self.emit(crate::event::EngineEvent::OperationFinished(op));
        res.map(|(outcome, failure)| OfficialExportReport {
            outcome,
            loader_install_error: failure.as_ref().map(EngineError::message),
            loader_installer_message: failure
                .as_ref()
                .and_then(|f| f.installer_message().map(str::to_string)),
        })
    }

    /// Writes the pack's official-launcher profile: verifies the files (modded packs, progress
    /// under `op`), installs the loader when it is missing and `install_loader` is set, copies
    /// the pack content into the export folder and upserts the profile. Sends no
    /// notification; the outcome says whether the loader is still missing.
    pub async fn export_official(
        &self,
        id: &PackId,
        install_loader: bool,
        op: OperationId,
    ) -> EngineResult<OfficialExportOutcome> {
        let res = self.export_official_inner(id, install_loader, op).await;
        self.emit(crate::event::EngineEvent::OperationFinished(op));
        res.map(|(outcome, _)| outcome)
    }

    /// The export; also returns the loader-install failure text, if the install ran and failed.
    async fn export_official_inner(
        &self,
        id: &PackId,
        install_loader: bool,
        op: OperationId,
    ) -> EngineResult<(OfficialExportOutcome, Option<EngineError>)> {
        let pack = self.require_installed(id)?;
        let dot_mc = resolve_dot_minecraft();
        let profiles_file = dot_mc.join(PROFILES_FILE);
        if !profiles_file.is_file() {
            return Err(EngineError::Other(mmcl_i18n::tf!(
                "officialExport.notInstalled.body",
                dot_mc.display().to_string()
            )));
        }
        let vanilla = pack.is_vanilla();
        if !vanilla {
            self.verify_one(&pack, op).await?;
        }
        let version_id = self.version_id_of(&pack).await?;
        let mut loader_installed =
            vanilla || mmcl_launch::export_install::is_version_installed(&dot_mc, &version_id);
        let mut install_failure = None;
        if install_loader && !loader_installed {
            install_failure = self.run_official_loader_install(&pack, &dot_mc).await;
            loader_installed =
                mmcl_launch::export_install::is_version_installed(&dot_mc, &version_id);
        }
        let root = pack.checked_pack_root()?;
        let game_dir = export_game_dir(&self.inner.paths, &pack);
        let (vanilla_version, key) = identity_of(&pack);
        let name = profile_display_name(&pack.name(), vanilla);
        let mc_for_runtime = match &vanilla_version {
            Some(v) => Some(v.clone()),
            None => pack.minecraft_version().await.ok().flatten(),
        };
        let inputs = ProfileInputs {
            name: name.clone(),
            version_id: version_id.clone(),
            game_dir: game_dir.clone(),
            java_dir: runtime_java(
                &self.inner.paths,
                pick_runtime_component(mc_for_runtime.as_deref()),
            ),
            max_ram_mb: self.inner.config.max_ram_mb(),
            custom_jvm_args: self.inner.config.custom_jvm_args(),
            icon: None,
        };
        let file = profiles_file.clone();
        let shown = profiles_file.display().to_string();
        let res = tokio::task::spawn_blocking(move || -> Result<(), EngineError> {
            std::fs::create_dir_all(&game_dir)
                .and_then(|()| {
                    if vanilla {
                        Ok(())
                    } else {
                        copy_pack_contents(&root, &game_dir)
                    }
                })
                .map_err(|e| {
                    EngineError::Other(mmcl_i18n::tf!(
                        "import.export.prepareDirFailed",
                        e.to_string()
                    ))
                })?;
            let inputs = ProfileInputs {
                icon: logo_icon(&root),
                ..inputs
            };
            upsert_profile(&file, &key, &inputs).map_err(|e| {
                EngineError::Other(mmcl_i18n::tf!(
                    "import.export.updateProfilesFailed",
                    e.to_string()
                ))
            })
        })
        .await
        .map_err(|e| EngineError::Other(e.to_string()))?;
        res?;
        mmcl_log::std(&mmcl_i18n::tf!(
            "log.officialExporter.wroteProfile",
            name.as_str(),
            shown
        ));
        Ok((
            OfficialExportOutcome {
                profile_name: name,
                version_id,
                loader_installed,
            },
            install_failure,
        ))
    }

    /// `ExportRequest::OfficialLauncher`: [`Engine::export_official`] with the loader install,
    /// reporting the outcome (and a failed loader install) as notifications.
    pub(super) async fn export_official_notifying(
        &self,
        id: &PackId,
        op: OperationId,
    ) -> EngineResult<()> {
        let (outcome, install_failure) = self.export_official_inner(id, true, op).await?;
        if let Some(failure) = install_failure {
            self.notify(Notification::new(
                NotificationKind::Warning,
                mmcl_i18n::t!("officialExport.installLoader.failed.title"),
                failure.message(),
            ));
        }
        if outcome.loader_installed {
            self.notify(Notification::new(
                NotificationKind::Success,
                mmcl_i18n::t!("officialExport.success.title"),
                mmcl_i18n::tf!("officialExport.success.body", outcome.profile_name),
            ));
        } else {
            self.notify(Notification::new(
                NotificationKind::Warning,
                mmcl_i18n::t!("officialExport.successButLoaderMissing.title"),
                mmcl_i18n::tf!(
                    "officialExport.successButLoaderMissing.body",
                    outcome.version_id
                ),
            ));
        }
        Ok(())
    }

    /// Whether the official launcher's data folder (with its profile list) exists.
    pub fn official_launcher_available(&self) -> bool {
        is_official_launcher_available()
    }

    /// Whether the pack has a profile in the official launcher.
    pub fn has_official_export(&self, id: &PackId) -> bool {
        let Some(pack) = self.installed_pack(id) else {
            return false;
        };
        let file = resolve_dot_minecraft().join(PROFILES_FILE);
        let (_, key) = identity_of(&pack);
        match read_profiles(&file) {
            Ok(json) => {
                matches!(json.get("profiles"), Some(Value::Object(p)) if p.contains_key(&key))
            }
            Err(_) => false,
        }
    }

    /// Removes the pack's official-launcher profile and its export folder.
    pub async fn remove_official_export(&self, id: &PackId) -> EngineResult<()> {
        let pack = self.require_installed(id)?;
        let file = resolve_dot_minecraft().join(PROFILES_FILE);
        let (_, key) = identity_of(&pack);
        let game_dir = export_game_dir(&self.inner.paths, &pack);
        tokio::task::spawn_blocking(move || -> EngineResult<()> {
            if file.is_file() {
                match remove_profile(&file, &key) {
                    Ok(true) => mmcl_log::std(&mmcl_i18n::tf!(
                        "log.officialExporter.removedProfile",
                        key.as_str()
                    )),
                    Ok(false) => mmcl_log::debug(&mmcl_i18n::tf!(
                        "log.officialExporter.removeNoProfile",
                        key.as_str()
                    )),
                    Err(e) => {
                        return Err(EngineError::Other(mmcl_i18n::tf!(
                            "import.export.updateProfilesFailed",
                            e.to_string()
                        )));
                    }
                }
            } else {
                mmcl_log::std(&mmcl_i18n::t!("log.officialExporter.removeProfilesMissing"));
            }
            if game_dir.is_dir() {
                match std::fs::remove_dir_all(&game_dir) {
                    Ok(()) => mmcl_log::std(&mmcl_i18n::tf!(
                        "log.officialExporter.deletedGameDir",
                        game_dir.display().to_string()
                    )),
                    Err(e) => mmcl_log::warn_silent(&mmcl_i18n::tf!(
                        "log.officialExporter.deleteGameDirFailed",
                        e.to_string()
                    )),
                }
            }
            Ok(())
        })
        .await
        .map_err(|e| EngineError::Other(e.to_string()))?
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn installer_output_is_cut_to_its_last_lines() {
        assert_eq!(last_lines(""), "");
        assert_eq!(last_lines("a\n\nb\nc\n  \nd\n"), "b\nc\nd");
        assert_eq!(last_lines("only"), "only");
    }

    #[test]
    fn name_uuids_match_java() {
        // Java: UUID.nameUUIDFromBytes("hello".getBytes(UTF_8))
        assert_eq!(
            name_uuid_from_bytes(b"hello"),
            "5d41402a-bc4b-3a76-b971-9d911017c592"
        );
        let a = stable_profile_key(None, Some("https://x/p.json"), "P");
        assert_eq!(
            a,
            stable_profile_key(None, Some("https://x/p.json"), "Other")
        );
        assert_ne!(a, stable_profile_key(Some("1.20.1"), None, "P"));
    }

    #[test]
    fn folder_names_are_sanitized() {
        assert_eq!(sanitize_folder_name("A/B:C (Mica)"), "A_B_C (Mica)");
        assert_eq!(sanitize_folder_name("  \u{1}  "), "modpack");
        assert_eq!(sanitize_folder_name(&"x".repeat(100)).len(), 80);
        assert_eq!(profile_display_name("", true), "Minecraft (Mica)");
        assert_eq!(profile_display_name("Alto", false), "Alto (Mica)");
    }

    #[test]
    fn runtime_components_follow_the_version() {
        assert_eq!(pick_runtime_component(None), "jre-legacy");
        assert_eq!(pick_runtime_component(Some("1.12.2")), "jre-legacy");
        assert_eq!(pick_runtime_component(Some("1.17.1")), "java-runtime-alpha");
        assert_eq!(pick_runtime_component(Some("1.18.1")), "java-runtime-beta");
        assert_eq!(pick_runtime_component(Some("1.18.2")), "java-runtime-gamma");
        assert_eq!(pick_runtime_component(Some("1.20.4")), "java-runtime-gamma");
        assert_eq!(pick_runtime_component(Some("1.20.5")), "java-runtime-delta");
        assert_eq!(pick_runtime_component(Some("26.1")), "java-runtime-delta");
        assert_eq!(pick_runtime_component(Some("abc")), "jre-legacy");
    }

    #[test]
    fn profiles_merge_and_remove_without_touching_others() {
        let d = tempfile::tempdir().unwrap();
        let file = d.path().join(PROFILES_FILE);
        std::fs::write(
            &file,
            r#"{"profiles":{"other":{"name":"Keep"}},"settings":{"x":1}}"#,
        )
        .unwrap();
        let inputs = ProfileInputs {
            name: "Alto (Mica)".into(),
            version_id: "1.20.1-forge-47.2.0".into(),
            game_dir: d.path().join("g"),
            java_dir: None,
            max_ram_mb: 4096,
            custom_jvm_args: " -XX:+UseG1GC ".into(),
            icon: None,
        };
        upsert_profile(&file, "k", &inputs).unwrap();
        let json: Value = serde_json::from_str(&std::fs::read_to_string(&file).unwrap()).unwrap();
        assert_eq!(json["profiles"]["other"]["name"], "Keep");
        assert_eq!(json["settings"]["x"], 1);
        let p = &json["profiles"]["k"];
        assert_eq!(p["type"], "custom");
        assert_eq!(p["javaArgs"], "-Xmx4096M -XX:+UseG1GC");
        let created = p["created"].clone();
        upsert_profile(&file, "k", &inputs).unwrap();
        let json: Value = serde_json::from_str(&std::fs::read_to_string(&file).unwrap()).unwrap();
        assert_eq!(json["profiles"]["k"]["created"], created);
        assert!(remove_profile(&file, "k").unwrap());
        assert!(!remove_profile(&file, "k").unwrap());
    }

    #[test]
    fn pack_contents_copy_replaces_folders() {
        let d = tempfile::tempdir().unwrap();
        let root = d.path().join("pack");
        std::fs::create_dir_all(root.join("mods/sub")).unwrap();
        std::fs::write(root.join("mods/sub/a.jar"), "a").unwrap();
        std::fs::write(root.join("options.txt"), "o").unwrap();
        let game = d.path().join("game");
        std::fs::create_dir_all(game.join("mods")).unwrap();
        std::fs::write(game.join("mods/stale.jar"), "s").unwrap();
        copy_pack_contents(&root, &game).unwrap();
        assert!(game.join("mods/sub/a.jar").is_file());
        assert!(!game.join("mods/stale.jar").exists());
        assert!(game.join("options.txt").is_file());
    }
}
