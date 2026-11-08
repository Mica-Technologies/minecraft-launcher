// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Installing a pack's loader into the official Minecraft Launcher's `.minecraft` (Java
//! `LoaderInstallerRunner`): Forge/NeoForge run their verified installer with
//! `--installClient`; Fabric's profile JSON is written into `versions/<id>/<id>.json`.
//!
//! There is no launcher JVM in Rust (Q-3): the installer runs on the pack's own Minecraft
//! version's Java runtime, falling back to `java-runtime-delta`.

use std::fs;
use std::path::Path;
use std::sync::Arc;
use std::time::Duration;

use mmcl_i18n::{t, tf};
use mmcl_modpack::GameModPack;
use mmcl_net::CancelToken;
use mmcl_util::process::{ChildIoMode, build_command};
use tokio::io::{AsyncBufReadExt, AsyncReadExt, BufReader};

use crate::env::LaunchEnv;
use crate::loader::{LoaderKind, ModLoader, loader_of};

/// The installer may run this long.
pub const INSTALLER_TIMEOUT: Duration = Duration::from_secs(180);
/// The runtime used when the pack's own cannot be resolved.
pub const FALLBACK_COMPONENT: &str = "java-runtime-delta";

/// The outcome of an install.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct InstallResult {
    pub success: bool,
    /// A localized message.
    pub message: String,
    /// The installer's stderr, when it ran and failed.
    pub installer_stderr: Option<String>,
}

impl InstallResult {
    fn ok(message: String) -> Self {
        InstallResult {
            success: true,
            message,
            installer_stderr: None,
        }
    }
    fn fail(message: String) -> Self {
        InstallResult {
            success: false,
            message,
            installer_stderr: None,
        }
    }
    fn fail_with(message: String, stderr: String) -> Self {
        InstallResult {
            success: false,
            message,
            installer_stderr: Some(stderr),
        }
    }
}

/// The Mojang-launcher version id a pack's loader installs (Java
/// `OfficialLauncherExporter.computeVersionId`).
pub fn compute_version_id(
    kind: Option<LoaderKind>,
    mc_version: &str,
    loader_version: &str,
) -> String {
    match kind {
        None => mc_version.to_string(),
        Some(LoaderKind::Forge | LoaderKind::NeoForge) => loader_version.to_string(),
        Some(LoaderKind::Fabric) => format!("fabric-loader-{loader_version}-{mc_version}"),
    }
}

/// `true` when `<dot_mc>/versions/<id>/<id>.json` exists.
pub fn is_version_installed(dot_mc: &Path, id: &str) -> bool {
    !id.trim().is_empty()
        && dot_mc
            .join("versions")
            .join(id)
            .join(format!("{id}.json"))
            .is_file()
}

/// Installs the loader of `pack` into `dot_mc`.
pub async fn install_loader_for_official_launcher(
    env: &LaunchEnv,
    pack: &Arc<GameModPack>,
    dot_mc: &Path,
    cancel: &CancelToken,
) -> InstallResult {
    if !dot_mc.is_dir() {
        return InstallResult::fail(tf!(
            "import.export.dataFolderMissing",
            dot_mc.display().to_string()
        ));
    }
    if pack.is_vanilla() {
        return InstallResult::ok(t!("officialExport.loader.vanilla").into_owned());
    }
    let Some(t) = pack.mod_loader_type() else {
        return InstallResult::fail(t!("officialExport.loader.noType").into_owned());
    };
    let kind = match LoaderKind::from_type(&t) {
        Ok(k) => k,
        Err(_) => {
            return InstallResult::fail(tf!("officialExport.loader.unsupportedType", t.trim()));
        }
    };
    match kind {
        LoaderKind::Fabric => write_fabric_profile(env, pack, dot_mc).await,
        LoaderKind::Forge | LoaderKind::NeoForge => run_installer(env, pack, dot_mc, cancel).await,
    }
}

async fn run_installer(
    env: &LaunchEnv,
    pack: &Arc<GameModPack>,
    dot_mc: &Path,
    cancel: &CancelToken,
) -> InstallResult {
    let loader: Arc<dyn ModLoader> = match loader_of(env, pack).await {
        Ok(Some(l)) => l,
        Ok(None) => {
            return InstallResult::fail(t!("officialExport.loader.unverifiable").into_owned());
        }
        Err(e) => {
            return InstallResult::fail(tf!("officialExport.loader.obtainFailed", e.to_string()));
        }
    };
    let Some(file) = loader.installer_file() else {
        return InstallResult::fail(t!("officialExport.loader.unverifiable").into_owned());
    };
    // About to execute it: forget the session check and verify again.
    if let Err(e) = file
        .reverify(&mmcl_modpack::PrepareContext::NONE, env.http.as_ref())
        .await
    {
        return InstallResult::fail(tf!("officialExport.loader.obtainFailed", e.to_string()));
    }
    let installer = file.full_path();
    if !fs::metadata(&installer).is_ok_and(|m| m.is_file() && m.len() > 0) {
        return InstallResult::fail(t!("officialExport.loader.unverifiable").into_owned());
    }
    mmcl_log::debug(&tf!(
        "log.loaderInstallerRunner.verifiedInstaller",
        installer.display().to_string()
    ));
    let mc = loader.minecraft_version();
    let component = crate::vanilla::required_runtime_component(env, &mc)
        .await
        .unwrap_or_else(|_| FALLBACK_COMPONENT.to_string());
    let java = match env.runtimes.java_path(&component, cancel).await {
        Ok(j) => j,
        Err(_) => match env.runtimes.java_path(FALLBACK_COMPONENT, cancel).await {
            Ok(j) => j,
            Err(e) => {
                return InstallResult::fail(tf!(
                    "officialExport.loader.spawnFailed",
                    e.to_string()
                ));
            }
        },
    };
    let abs = |p: &Path| std::path::absolute(p).unwrap_or_else(|_| p.to_path_buf());
    let argv = vec![
        java.to_string_lossy().into_owned(),
        "-jar".to_string(),
        abs(&installer).to_string_lossy().into_owned(),
        "--installClient".to_string(),
        abs(dot_mc).to_string_lossy().into_owned(),
    ];
    mmcl_log::std(&tf!("log.loaderInstallerRunner.running", argv.join(" ")));
    let cwd = abs(dot_mc);
    let mut cmd = match build_command(&argv, &cwd, ChildIoMode::Pipe) {
        Ok(c) => c,
        Err(e) => {
            return InstallResult::fail(tf!("officialExport.loader.spawnFailed", e.to_string()));
        }
    };
    cmd.stdin(std::process::Stdio::null());
    let mut cmd = tokio::process::Command::from(cmd);
    cmd.kill_on_drop(true);
    let mut child = match cmd.spawn() {
        Ok(c) => c,
        Err(e) => {
            return InstallResult::fail(tf!("officialExport.loader.spawnFailed", e.to_string()));
        }
    };
    let stderr_task = child.stderr.take().map(|mut s| {
        tokio::spawn(async move {
            let mut buf = Vec::new();
            let _ = s.read_to_end(&mut buf).await;
            String::from_utf8_lossy(&buf).into_owned()
        })
    });
    let stdout_task = child.stdout.take().map(|s| {
        tokio::spawn(async move {
            let mut lines = BufReader::new(s).lines();
            while let Ok(Some(l)) = lines.next_line().await {
                mmcl_log::debug(&format!("[loader-installer] {l}"));
            }
        })
    });
    let status = tokio::select! {
        s = child.wait() => s,
        _ = tokio::time::sleep(INSTALLER_TIMEOUT) => {
            let _ = child.kill().await;
            return InstallResult::fail(tf!("officialExport.loader.timedOut", INSTALLER_TIMEOUT.as_secs().to_string()));
        }
        _ = cancel.cancelled() => {
            let _ = child.kill().await;
            return InstallResult::fail(t!("officialExport.loader.interrupted").into_owned());
        }
    };
    let stderr = match stderr_task {
        Some(h) => h.await.unwrap_or_default(),
        None => String::new(),
    };
    if let Some(h) = stdout_task {
        let _ = h.await;
    }
    let status = match status {
        Ok(s) => s,
        Err(e) => {
            return InstallResult::fail(tf!("officialExport.loader.spawnFailed", e.to_string()));
        }
    };
    if !status.success() {
        let code = status
            .code()
            .map_or_else(|| "?".to_string(), |c| c.to_string());
        return InstallResult::fail_with(tf!("officialExport.loader.exitCode", code), stderr);
    }
    let id = compute_version_id(Some(loader.kind()), &mc, &loader.loader_version());
    if !is_version_installed(dot_mc, &id) {
        return InstallResult::fail_with(
            tf!("officialExport.loader.versionDirMissing", &id),
            stderr,
        );
    }
    InstallResult::ok(tf!("officialExport.loader.installed", &id))
}

async fn write_fabric_profile(env: &LaunchEnv, pack: &GameModPack, dot_mc: &Path) -> InstallResult {
    let Some(url) = pack
        .mod_loader_url()
        .map(str::trim)
        .filter(|u| !u.is_empty())
    else {
        return InstallResult::fail(t!("officialExport.loader.noFabricUrl").into_owned());
    };
    // Data, not code: a plain GET like Java (B-23).
    let body = match env.http.get_string(url).await {
        Ok(b) => b,
        Err(e) => {
            return InstallResult::fail(tf!("officialExport.loader.fabricFailed", e.to_string()));
        }
    };
    write_fabric_profile_body(&body, dot_mc)
}

/// Writes a Fabric profile body into `versions/<id>/<id>.json` atomically.
pub fn write_fabric_profile_body(body: &str, dot_mc: &Path) -> InstallResult {
    if body.trim().is_empty() {
        return InstallResult::fail(t!("officialExport.loader.fabricEmpty").into_owned());
    }
    let id = match serde_json::from_str::<serde_json::Value>(body) {
        Ok(v) => match v.get("id").and_then(|i| i.as_str()) {
            Some(id) if !id.trim().is_empty() => id.to_string(),
            _ => return InstallResult::fail(t!("officialExport.loader.fabricNoId").into_owned()),
        },
        Err(e) => {
            return InstallResult::fail(tf!("officialExport.loader.fabricFailed", e.to_string()));
        }
    };
    if id.contains(['/', '\\', ':', '\0']) || id.contains("..") {
        return InstallResult::fail(tf!("officialExport.loader.fabricFailed", &id));
    }
    let dir = dot_mc.join("versions").join(&id);
    let res = (|| -> std::io::Result<()> {
        fs::create_dir_all(&dir)?;
        let tmp = dir.join(format!("{id}.json.tmp"));
        let target = dir.join(format!("{id}.json"));
        fs::write(&tmp, body)?;
        if fs::rename(&tmp, &target).is_err() {
            fs::copy(&tmp, &target)?;
            let _ = fs::remove_file(&tmp);
        }
        Ok(())
    })();
    match res {
        Ok(()) => InstallResult::ok(tf!("officialExport.loader.fabricInstalled", &id)),
        Err(e) => InstallResult::fail(tf!("officialExport.loader.fabricFailed", e.to_string())),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn version_ids() {
        assert_eq!(compute_version_id(None, "1.21.5", ""), "1.21.5");
        assert_eq!(
            compute_version_id(
                Some(LoaderKind::Forge),
                "1.12.2",
                "1.12.2-forge-14.23.5.2860"
            ),
            "1.12.2-forge-14.23.5.2860"
        );
        assert_eq!(
            compute_version_id(Some(LoaderKind::NeoForge), "1.21.1", "neoforge-21.1.95"),
            "neoforge-21.1.95"
        );
        assert_eq!(
            compute_version_id(Some(LoaderKind::Fabric), "1.21.5", "0.16.10"),
            "fabric-loader-0.16.10-1.21.5"
        );
    }

    #[test]
    fn fabric_profile_write() {
        let d = tempfile::tempdir().unwrap();
        let r = write_fabric_profile_body(r#"{"id":"fabric-loader-0.16.10-1.21.5"}"#, d.path());
        assert!(r.success, "{r:?}");
        assert!(is_version_installed(
            d.path(),
            "fabric-loader-0.16.10-1.21.5"
        ));
        assert!(!write_fabric_profile_body("  ", d.path()).success);
        assert!(!write_fabric_profile_body(r#"{"x":1}"#, d.path()).success);
        assert!(!write_fabric_profile_body(r#"{"id":"../evil"}"#, d.path()).success);
        assert!(!is_version_installed(d.path(), ""));
    }

    #[tokio::test]
    async fn vanilla_and_missing_folder() {
        let fx = crate::test_support::Fixture::new(mmcl_core::GameMode::Client);
        let pack = fx.vanilla_pack("1.21.5");
        let c = CancelToken::new();
        let r = install_loader_for_official_launcher(&fx.env, &pack, fx.dir.path(), &c).await;
        assert!(r.success);
        let r =
            install_loader_for_official_launcher(&fx.env, &pack, &fx.dir.path().join("nope"), &c)
                .await;
        assert!(!r.success);
    }
}
