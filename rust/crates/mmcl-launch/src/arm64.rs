// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! LWJGL2 on ARM64 macOS and Linux (Java `Lwjgl2ArmPatcher`): Minecraft 1.12 and older ship
//! x86-64 natives only, so they are swapped for community ARM64 builds, JNA is replaced, and
//! `text2speech` becomes a no-op stub. Every failure here is non-fatal.
//!
//! Fix over Java (B-5): only `1.x` versions with `x <= 12` are LWJGL2; a year-based `26.1`
//! is not.

use std::fs;
use std::io;
use std::path::{Path, PathBuf};

use mmcl_i18n::{t, tf};
use mmcl_modpack::PackHttp;
use mmcl_mojang::Os;

use crate::env::LaunchEnv;

/// The replacement-jar cache under `<pack>/libraries`.
pub const CACHE_DIR: &str = "arm64-natives";

const MAC_LWJGL: &str = "https://github.com/MinecraftMachina/lwjgl/releases/download/2.9.4-20150209-mmachina.2/lwjgl-platform-2.9.4-nightly-20150209-natives-osx.jar";
const MAC_JINPUT: &str =
    "https://github.com/r58Playz/jinput-m1/raw/main/plugins/OSX/bin/jinput-platform-2.0.5.jar";
const LINUX_LWJGL: &str = "https://github.com/theofficialgman/lwjgl3-binaries-arm64/raw/lwjgl-2.9.4/lwjgl-platform-2.9.4-nightly-20150209-natives-linux.jar";
const LINUX_JINPUT: &str = "https://github.com/theofficialgman/lwjgl3-binaries-arm64/raw/lwjgl-2.9.4/jinput-platform-2.0.5-natives-linux.jar";
const JNA: &str = "https://repo1.maven.org/maven2/net/java/dev/jna/jna/5.15.0/jna-5.15.0.jar";

const LWJGL_CACHE: &str = "lwjgl-arm64-natives.jar";
const JINPUT_CACHE: &str = "jinput-arm64-natives.jar";
const JNA_CACHE: &str = "jna-arm64.jar";

/// `true` for `1.x` with `x <= 12` (LWJGL2). Anything else, including `26.1` and snapshots,
/// is not.
pub fn is_lwjgl2_version(mc: &str) -> bool {
    let parts: Vec<&str> = mc.split('.').collect();
    if parts.len() < 2 || parts[0] != "1" {
        return false;
    }
    match parts[1].trim().parse::<u32>() {
        Ok(minor) => minor <= 12,
        Err(_) => {
            mmcl_log::debug(&tf!("log.lwjgl2Patcher.couldNotParseMcVersion", mc));
            false
        }
    }
}

/// Whether patching applies: enabled in settings, ARM64 host, not Windows, LWJGL2 version.
pub fn is_needed_for(enabled: bool, os: Os, arm64: bool, mc: &str) -> bool {
    enabled && arm64 && os != Os::Windows && is_lwjgl2_version(mc)
}

/// [`is_needed_for`] for a launch environment.
pub fn is_needed(env: &LaunchEnv, mc: &str) -> bool {
    is_needed_for(
        env.config.lwjgl_arm_patch_enabled(),
        env.host.os,
        env.host.is_arm64(),
        mc,
    )
}

/// The JVM flags patched natives need.
pub fn jvm_flags(natives: &str) -> Vec<String> {
    vec![
        format!("-Dorg.lwjgl.librarypath={natives}"),
        format!("-Dnet.java.games.input.librarypath={natives}"),
        "-Djna.nosys=false".into(),
        "-Djna.boot.library.path=".into(),
        "-Dmojang.text2speech.enabled=false".into(),
    ]
}

/// The generated no-op `text2speech` jar.
pub fn text2speech_stub_jar() -> io::Result<Vec<u8>> {
    crate::jar::build_jar(&[
        (
            crate::jar::MANIFEST_ENTRY,
            b"Manifest-Version: 1.0\r\nCreated-By: Mica Minecraft Launcher\r\n\r\n",
        ),
        (
            "com/mojang/text2speech/Narrator.class",
            &crate::classfile::narrator_class(),
        ),
        (
            "com/mojang/text2speech/NarratorNoop.class",
            &crate::classfile::narrator_noop_class(),
        ),
    ])
}

/// Renames `controllable.jar` to `controllable.jar.arm64disabled` (it crashes on ARM64).
pub fn disable_incompatible_mods(mods_dir: &Path) {
    let jar = mods_dir.join("controllable.jar");
    if !jar.is_file() {
        return;
    }
    let to = mods_dir.join("controllable.jar.arm64disabled");
    match fs::rename(&jar, &to) {
        Ok(()) => mmcl_log::std(&tf!(
            "log.lwjgl2Patcher.disabledIncompatibleMod",
            "controllable.jar",
            "controllable.jar.arm64disabled"
        )),
        Err(_) => mmcl_log::warn_silent(&tf!(
            "log.lwjgl2Patcher.failedToDisableIncompatibleMod",
            "controllable.jar"
        )),
    }
}

/// Which jars a library file gets replaced with.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Replacement {
    Lwjgl,
    Jinput,
    Jna,
    Text2Speech,
}

/// What `file_name` is replaced with on this OS (`suffix` is the OS's natives suffix).
pub fn replacement_for(file_name: &str, suffix: &str) -> Option<Replacement> {
    if file_name.ends_with(suffix) && file_name.contains("lwjgl-platform") {
        return Some(Replacement::Lwjgl);
    }
    if file_name.ends_with(suffix) && file_name.contains("jinput-platform") {
        return Some(Replacement::Jinput);
    }
    if file_name.starts_with("jna-")
        && file_name.ends_with(".jar")
        && !file_name.contains("platform")
    {
        return Some(Replacement::Jna);
    }
    if file_name.starts_with("text2speech-") && file_name.ends_with(".jar") {
        return Some(Replacement::Text2Speech);
    }
    None
}

/// Patches `natives_dir` and `libraries_dir` in place. Never fails the launch: on an I/O
/// error the cache is dropped so the next launch fetches it again.
pub async fn patch_natives(
    http: &dyn PackHttp,
    natives_dir: &Path,
    libraries_dir: &Path,
    cache_dir: &Path,
) {
    let os = mmcl_mojang::HostInfo::detect().os;
    patch_natives_for(http, os, natives_dir, libraries_dir, cache_dir).await
}

/// [`patch_natives`] for an explicit OS (tests).
pub async fn patch_natives_for(
    http: &dyn PackHttp,
    os: Os,
    natives_dir: &Path,
    libraries_dir: &Path,
    cache_dir: &Path,
) {
    mmcl_log::std(&t!("log.lwjgl2Patcher.replacingNatives"));
    let (lwjgl_url, jinput_url, suffix) = match os {
        Os::MacOs => (MAC_LWJGL, MAC_JINPUT, "-natives-osx.jar"),
        _ => (LINUX_LWJGL, LINUX_JINPUT, "-natives-linux.jar"),
    };
    let cache = |n: &str| cache_dir.join(n);
    let res: io::Result<()> = async {
        fs::create_dir_all(cache_dir)?;
        for (url, name, key) in [
            (
                lwjgl_url,
                LWJGL_CACHE,
                "log.lwjgl2Patcher.downloadingLwjglFrom",
            ),
            (
                jinput_url,
                JINPUT_CACHE,
                "log.lwjgl2Patcher.downloadingJinputFrom",
            ),
            (JNA, JNA_CACHE, "log.lwjgl2Patcher.downloadingJnaFrom"),
        ] {
            let dest = cache(name);
            if dest.is_file() {
                continue;
            }
            mmcl_log::std(&tf!(key, url));
            http.download(url, &dest, None)
                .await
                .map_err(|e| io::Error::other(e.to_string()))?;
        }
        let (libs, cache_dir, natives, suffix) = (
            libraries_dir.to_path_buf(),
            cache_dir.to_path_buf(),
            natives_dir.to_path_buf(),
            suffix.to_string(),
        );
        crate::batch::blocking(move || apply_patch(&libs, &cache_dir, &natives, &suffix)).await??;
        Ok(())
    }
    .await;
    match res {
        Ok(()) => mmcl_log::std(&t!("log.lwjgl2Patcher.completedSuccessfully")),
        Err(e) => {
            mmcl_log::warn_silent(&tf!("log.lwjgl2Patcher.patchingFailed", e.to_string()));
            for n in [LWJGL_CACHE, JINPUT_CACHE, JNA_CACHE] {
                let _ = fs::remove_file(cache(n));
            }
        }
    }
}

fn apply_patch(libs: &Path, cache_dir: &Path, natives: &Path, suffix: &str) -> io::Result<()> {
    let stub = text2speech_stub_jar()?;
    let files: Vec<PathBuf> = walkdir::WalkDir::new(libs)
        .into_iter()
        .filter_entry(|e| e.path() != cache_dir)
        .filter_map(|e| e.ok())
        .filter(|e| e.file_type().is_file())
        .map(|e| e.into_path())
        .collect();
    for f in files {
        let name = crate::batch::file_name_of(&f);
        let Some(r) = replacement_for(&name, suffix) else {
            continue;
        };
        let res = match r {
            Replacement::Lwjgl => fs::copy(cache_dir.join(LWJGL_CACHE), &f).map(|_| ()),
            Replacement::Jinput => fs::copy(cache_dir.join(JINPUT_CACHE), &f).map(|_| ()),
            Replacement::Jna => fs::copy(cache_dir.join(JNA_CACHE), &f).map(|_| ()),
            Replacement::Text2Speech => {
                mmcl_log::std(&tf!(
                    "log.lwjgl2Patcher.replacingText2speech",
                    f.display().to_string()
                ));
                crate::jar::write_atomic(&f, &stub)
            }
        };
        match res {
            Ok(()) => mmcl_log::debug(&tf!("log.lwjgl2Patcher.replacingFile", name)),
            Err(e) => {
                mmcl_log::warn_silent(&tf!(
                    "log.lwjgl2Patcher.failedToReplace",
                    name,
                    e.to_string()
                ));
                return Err(e);
            }
        }
    }
    mmcl_log::std(&tf!(
        "log.lwjgl2Patcher.extractingNativesTo",
        natives.display().to_string()
    ));
    fs::create_dir_all(natives)?;
    for n in [LWJGL_CACHE, JINPUT_CACHE] {
        mmcl_archive::extract_jar(&cache_dir.join(n), natives)
            .map_err(|e| io::Error::other(e.to_string()))?;
    }
    set_native_permissions(natives);
    Ok(())
}

#[cfg(unix)]
fn set_native_permissions(natives: &Path) {
    use std::os::unix::fs::PermissionsExt;
    for e in walkdir::WalkDir::new(natives)
        .into_iter()
        .filter_map(|e| e.ok())
    {
        let n = e.file_name().to_string_lossy();
        if n.ends_with(".dylib") || n.ends_with(".so") || n.ends_with(".jnilib") {
            if let Ok(m) = e.metadata() {
                let mut p = m.permissions();
                p.set_mode(p.mode() | 0o555);
                let _ = fs::set_permissions(e.path(), p);
            }
        }
    }
}

#[cfg(not(unix))]
fn set_native_permissions(_natives: &Path) {}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::test_support::FakeHttp;

    #[test]
    fn lwjgl2_versions() {
        assert!(is_lwjgl2_version("1.12.2"));
        assert!(is_lwjgl2_version("1.7.10"));
        assert!(!is_lwjgl2_version("1.13"));
        assert!(!is_lwjgl2_version("26.1"));
        assert!(!is_lwjgl2_version("1"));
        assert!(!is_lwjgl2_version("20w14a"));
        assert!(!is_lwjgl2_version("1.x"));
    }

    #[test]
    fn is_needed_matrix() {
        assert!(is_needed_for(true, Os::MacOs, true, "1.12.2"));
        assert!(is_needed_for(true, Os::Linux, true, "1.7.10"));
        assert!(!is_needed_for(true, Os::Windows, true, "1.12.2"));
        assert!(!is_needed_for(false, Os::MacOs, true, "1.12.2"));
        assert!(!is_needed_for(true, Os::MacOs, false, "1.12.2"));
        assert!(!is_needed_for(true, Os::MacOs, true, "1.20.1"));
    }

    #[test]
    fn replacement_rules() {
        let s = "-natives-osx.jar";
        assert_eq!(
            replacement_for("lwjgl-platform-2.9.4-nightly-20150209-natives-osx.jar", s),
            Some(Replacement::Lwjgl)
        );
        assert_eq!(
            replacement_for("jinput-platform-2.0.5-natives-osx.jar", s),
            Some(Replacement::Jinput)
        );
        assert_eq!(replacement_for("jna-4.4.0.jar", s), Some(Replacement::Jna));
        assert_eq!(replacement_for("jna-platform-4.4.0.jar", s), None);
        assert_eq!(
            replacement_for("text2speech-1.10.3.jar", s),
            Some(Replacement::Text2Speech)
        );
        assert_eq!(
            replacement_for("lwjgl-platform-2.9.4-natives-linux.jar", s),
            None
        );
    }

    #[test]
    fn stub_jar_has_both_classes() {
        let d = tempfile::tempdir().unwrap();
        let p = d.path().join("t.jar");
        fs::write(&p, text2speech_stub_jar().unwrap()).unwrap();
        let names = crate::jar::entry_names(&p).unwrap();
        assert!(names.contains("com/mojang/text2speech/Narrator.class"));
        assert!(names.contains("com/mojang/text2speech/NarratorNoop.class"));
        assert_eq!(
            text2speech_stub_jar().unwrap(),
            text2speech_stub_jar().unwrap()
        );
    }

    #[test]
    fn controllable_is_disabled() {
        let d = tempfile::tempdir().unwrap();
        fs::write(d.path().join("controllable.jar"), b"x").unwrap();
        disable_incompatible_mods(d.path());
        assert!(d.path().join("controllable.jar.arm64disabled").is_file());
        assert!(!d.path().join("controllable.jar").exists());
        disable_incompatible_mods(d.path());
    }

    #[tokio::test]
    async fn patch_replaces_and_extracts_and_is_non_fatal() {
        let d = tempfile::tempdir().unwrap();
        let libs = d.path().join("libraries");
        let natives = d.path().join("natives");
        let cache = libs.join(CACHE_DIR);
        let lwjgl_dir = libs.join("org/lwjgl/lwjgl/lwjgl-platform/2.9.4");
        fs::create_dir_all(&lwjgl_dir).unwrap();
        let target = lwjgl_dir.join("lwjgl-platform-2.9.4-nightly-20150209-natives-osx.jar");
        fs::write(&target, b"x86").unwrap();
        let t2s = libs.join("com/mojang/text2speech/1.10.3/text2speech-1.10.3.jar");
        fs::create_dir_all(t2s.parent().unwrap()).unwrap();
        fs::write(&t2s, b"old").unwrap();

        let http = FakeHttp::new();
        let arm_lwjgl = crate::jar::build_jar(&[("liblwjgl.dylib", b"arm")]).unwrap();
        http.body(MAC_LWJGL, arm_lwjgl.clone());
        http.body(
            MAC_JINPUT,
            crate::jar::build_jar(&[("libjinput-osx.jnilib", b"j")]).unwrap(),
        );
        http.body(JNA, b"jna".to_vec());
        patch_natives_for(http.as_ref(), Os::MacOs, &natives, &libs, &cache).await;
        assert_eq!(fs::read(&target).unwrap(), arm_lwjgl);
        assert!(natives.join("liblwjgl.dylib").is_file());
        assert!(natives.join("libjinput-osx.jnilib").is_file());
        assert_ne!(fs::read(&t2s).unwrap(), b"old");

        // A failing download is non-fatal and drops the cache.
        let d2 = tempfile::tempdir().unwrap();
        let http = FakeHttp::new();
        let cache2 = d2.path().join("c");
        patch_natives_for(
            http.as_ref(),
            Os::Linux,
            &d2.path().join("n"),
            d2.path(),
            &cache2,
        )
        .await;
        assert!(!cache2.join(LWJGL_CACHE).exists());
    }
}
