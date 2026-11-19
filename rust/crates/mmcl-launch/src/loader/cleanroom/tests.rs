// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

use std::collections::HashSet;

use mmcl_mojang::HostInfo;
use mmcl_mojang::host::presets;

use super::*;

/// The real `version.json` and `install_profile.json` of the Cleanroom 0.7.14-alpha installer,
/// byte for byte.
const VERSION_JSON_0_7_14: &str =
    include_str!("../../../testdata/cleanroom-0.7.14-alpha/version.json");
const INSTALL_PROFILE_0_7_14: &str =
    include_str!("../../../testdata/cleanroom-0.7.14-alpha/install_profile.json");
const UNIVERSAL: &str =
    "com/cleanroommc/cleanroom/0.7.14-alpha/cleanroom-0.7.14-alpha-universal.jar";

fn real_install() -> CleanroomInstall {
    parse_install(
        Some(VERSION_JSON_0_7_14.as_bytes()),
        Some(INSTALL_PROFILE_0_7_14.as_bytes()),
    )
    .unwrap()
}

fn real_state() -> State {
    State::of(real_install()).unwrap()
}

#[test]
fn the_real_installer_profiles_read_as_documented() {
    let install = real_install();
    let vj = &install.version_json;
    assert_eq!(vj["id"], "Cleanroom-0.7.14-alpha");
    assert!(vj.get("inheritsFrom").is_none(), "standalone profile");
    let s = real_state();
    assert_eq!(s.id, "Cleanroom-0.7.14-alpha");
    // Never the standalone id: assets, version lookups and the UI need 1.12.2.
    assert_eq!(s.minecraft_version, "1.12.2");
    assert_eq!(s.main_class, "top.outlands.foundation.boot.Foundation");
    let profile = s.profile.as_ref().unwrap();
    assert_eq!(
        profile.server_main_class().as_deref(),
        Some("top.outlands.foundation.boot.Foundation")
    );
    assert!(profile.jvm_args().is_empty());
    // One `--tweakClass`, from `minecraftArguments`.
    let args = mmcl_mojang::split_command_line(&s.minecraft_arguments);
    assert_eq!(args.iter().filter(|a| *a == "--tweakClass").count(), 1);
    assert!(args.contains(&"net.minecraftforge.fml.common.launcher.FMLTweaker".to_string()));
}

#[test]
fn the_runtime_comes_from_the_loader_profile_not_vanilla() {
    let s = real_state();
    // Vanilla 1.12.2 would say jre-legacy / 8.
    assert_eq!(s.manifest.runtime_component(), "java-runtime-epsilon");
    assert_eq!(s.manifest.java_major(), 25);
    assert_eq!(s.manifest.asset_index_id().unwrap(), "1.12");
    let client = s
        .manifest
        .game_download(mmcl_mojang::GameSide::Client)
        .unwrap();
    // The vanilla 1.12.2 client jar.
    assert_eq!(client.sha1, "0f275bc1547d01fa5f56ba34bdc87d981ee12daf");
    let server = s
        .manifest
        .game_download(mmcl_mojang::GameSide::Server)
        .unwrap();
    assert_eq!(server.sha1, "886945bfb2b978778c3a0288fd7fab09d315b25f");
}

#[test]
fn the_universal_jar_is_split_off_and_comes_from_the_installer() {
    let s = real_state();
    let own: Vec<String> = s.own.libraries().iter().filter_map(|l| l.name()).collect();
    assert_eq!(own, ["com.cleanroommc:cleanroom:0.7.14-alpha:universal"]);
    let total = serde_json::from_str::<Value>(VERSION_JSON_0_7_14).unwrap()["libraries"]
        .as_array()
        .unwrap()
        .len();
    assert_eq!(total, 217);
    assert_eq!(s.manifest.raw_libraries().unwrap().len(), total - 1);

    let entries: HashSet<String> = [format!("maven/{UNIVERSAL}")].into();
    let installer = std::env::temp_dir().join("installs/P/bin/modpack.jar");
    let plan = plan_embedded(&s.own, &entries, &installer).unwrap();
    assert_eq!(plan.len(), 1);
    assert!(plan[0].url.starts_with("jar:file:"), "{}", plan[0].url);
    assert!(plan[0].url.ends_with(&format!("!/maven/{UNIVERSAL}")));
    assert_eq!(
        plan[0].sha1.as_deref(),
        Some("9e7ed10176074d58903f72358d4f54dba0c50677")
    );
    assert_eq!(plan[0].local.replace('\\', "/"), UNIVERSAL);

    // An installer without the jar it lists is refused, not fetched from a made-up URL.
    let e = plan_embedded(&s.own, &HashSet::new(), &installer).unwrap_err();
    assert_eq!(e.key(), "launch.error.cleanroomEmbeddedLibraryMissing");
}

fn resolved(host: &HostInfo) -> Vec<mmcl_mojang::ResolvedLibrary> {
    real_state()
        .manifest
        .resolve_libraries(host)
        .expect("every remaining library has a URL")
        .libraries
}

#[test]
fn no_vanilla_lwjgl_2_or_vanilla_library_is_in_the_profile() {
    for host in presets::all() {
        for lib in resolved(&host) {
            assert!(!lib.url.trim().is_empty(), "{}", lib.name);
            assert!(
                !(lib.name.starts_with("org.lwjgl.lwjgl:") || lib.name.contains(":2.9.")),
                "LWJGL 2 on {host:?}: {}",
                lib.name
            );
            assert!(!lib.name.starts_with("net.minecraft:"), "{}", lib.name);
        }
    }
    let names: Vec<String> = resolved(&presets::windows_x64())
        .into_iter()
        .map(|l| l.name)
        .collect();
    assert!(names.contains(&"org.lwjgl:lwjgl:3.4.3".to_string()));
    assert!(names.contains(&"com.cleanroommc:lwjgly:2.3.0".to_string()));
}

/// The classifier jars a host extracts.
fn natives(host: &HostInfo) -> Vec<String> {
    resolved(host)
        .into_iter()
        .filter(|l| l.native)
        .map(|l| {
            let file = l.path.rsplit('/').next().unwrap().to_string();
            file.trim_end_matches(".jar").to_string()
        })
        .collect()
}

fn classifiers(host: &HostInfo) -> HashSet<String> {
    natives(host)
        .iter()
        .map(|n| n[n.find("-natives-").unwrap() + 1..].to_string())
        .collect()
}

#[test]
fn legacy_natives_entries_resolve_per_host() {
    let one = |c: &str| -> HashSet<String> { [c.to_string()].into() };
    // Windows x64: 7 LWJGL 3 modules, text2speech and jinput, all `natives-windows`.
    assert_eq!(natives(&presets::windows_x64()).len(), 9);
    assert_eq!(classifiers(&presets::windows_x64()), one("natives-windows"));
    assert_eq!(natives(&presets::windows_arm64()).len(), 7);
    assert_eq!(
        classifiers(&presets::windows_arm64()),
        one("natives-windows-arm64")
    );
    assert_eq!(
        classifiers(&presets::windows_x86()),
        one("natives-windows-x86")
    );
    // Linux x64: LWJGL, text2speech and jinput.
    assert_eq!(natives(&presets::linux_x64()).len(), 9);
    assert_eq!(classifiers(&presets::linux_x64()), one("natives-linux"));
    assert_eq!(natives(&presets::linux_arm64()).len(), 7);
    assert_eq!(
        classifiers(&presets::linux_arm64()),
        one("natives-linux-arm64")
    );
    // macOS Intel: LWJGL and jinput (no text2speech natives for macOS).
    assert_eq!(natives(&presets::mac_intel()).len(), 8);
    assert_eq!(
        classifiers(&presets::mac_intel()),
        ["natives-macos".to_string(), "natives-osx".to_string()].into()
    );
    assert_eq!(natives(&presets::mac_arm()).len(), 7);
    assert_eq!(classifiers(&presets::mac_arm()), one("natives-macos-arm64"));
    // A BSD host gets none (Cleanroom's freebsd entries name an OS the rules never match).
    assert!(natives(&presets::freebsd_x64()).is_empty());
}

#[test]
fn natives_get_their_maven_paths_and_stay_off_a_server() {
    let s = real_state();
    let host = presets::windows_x64();
    let client = s
        .manifest
        .resolve_libraries_for(&host, Some(mmcl_mojang::GameSide::Client))
        .unwrap();
    let lwjgl = client
        .libraries
        .iter()
        .find(|l| l.native && l.name == "org.lwjgl:lwjgl:3.4.3")
        .unwrap();
    assert_eq!(
        lwjgl.path,
        "org/lwjgl/lwjgl/3.4.3/lwjgl-3.4.3-natives-windows.jar"
    );
    assert_eq!(lwjgl.sha1, "27e3d2f08b2bcf4fb56a58b836d9437419b980d7");
    // Every natives entry is `"side": "client"`; the installer's server mode skips them.
    let server = s
        .manifest
        .resolve_libraries_for(&host, Some(mmcl_mojang::GameSide::Server))
        .unwrap();
    assert!(server.libraries.iter().all(|l| !l.native));
    assert_eq!(
        server.libraries.len(),
        client.libraries.iter().filter(|l| !l.native).count()
    );
}

#[test]
fn arch_ruled_artifacts_follow_the_host_too() {
    let quic = |h: &HostInfo| -> Vec<String> {
        resolved(h)
            .into_iter()
            .filter(|l| l.name.starts_with("io.netty:netty-codec-native-quic:"))
            .map(|l| l.name)
            .collect()
    };
    assert_eq!(
        quic(&presets::windows_x64()),
        ["io.netty:netty-codec-native-quic:4.2.16.Final:windows-x86_64"]
    );
    assert_eq!(
        quic(&presets::mac_arm()),
        ["io.netty:netty-codec-native-quic:4.2.16.Final:osx-aarch_64"]
    );
    assert!(quic(&presets::windows_arm64()).is_empty());
}

#[test]
fn server_facts_mirror_the_installer_server_mode() {
    let install = real_install();
    let profile = install.profile.unwrap();
    assert_eq!(
        profile.server_game_args(),
        [
            "--tweakClass",
            "net.minecraftforge.fml.common.launcher.FMLServerTweaker"
        ]
    );
}

#[test]
fn a_profile_less_installer_still_names_its_server_facts() {
    let install = parse_install(Some(VERSION_JSON_0_7_14.as_bytes()), Some(b"not json")).unwrap();
    assert!(install.profile.is_none());
    let s = State::of(install).unwrap();
    assert_eq!(s.minecraft_version, "1.12.2");
    assert!(parse_install(None, Some(INSTALL_PROFILE_0_7_14.as_bytes())).is_err());
}

#[test]
fn clearing_natives_removes_stale_lwjgl_2_files() {
    let d = tempfile::tempdir().unwrap();
    let natives = d.path().join("bin/natives");
    fs::create_dir_all(&natives).unwrap();
    fs::write(natives.join("lwjgl64.dll"), b"lwjgl2").unwrap();
    clear_natives(&natives).unwrap();
    assert!(natives.is_dir());
    assert_eq!(fs::read_dir(&natives).unwrap().count(), 0);
    // Absent is fine.
    let missing = d.path().join("other");
    clear_natives(&missing).unwrap();
    assert!(missing.is_dir());
}
