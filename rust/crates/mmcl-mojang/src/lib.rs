// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Pure models for Mojang, Forge and Fabric launch metadata.
//!
//! No network, no async, no file I/O beyond the one-time host probe in
//! [`host::HostInfo::detect`]. The launch pipeline (`mmcl-launch`), runtime
//! manager (`mmcl-runtime`) and the export/import/editor code build on it.
//!
//! - [`host`]: [`HostInfo`], emulating the Java `os.arch` / `os.version` that
//!   Mojang rules were written against.
//! - [`rules`]: rule evaluation and argument flattening (`ManifestRuleUtilities`).
//! - [`args`]: Java-compatible command-line splitting and single-pass
//!   `${placeholder}` substitution.
//! - [`version_manifest`], [`client_json`], [`asset_index`]: Mojang manifests.
//! - [`maven`]: Maven coordinates with traversal checks.
//! - [`forge_profile`], [`fabric_profile`], [`runtime_index`]: loader and Java
//!   runtime metadata.

pub mod args;
pub mod asset_index;
pub mod client_json;
pub mod error;
pub mod fabric_profile;
pub mod forge_profile;
pub mod host;
pub mod maven;
pub mod rules;
pub mod runtime_index;
pub mod version_manifest;

pub use args::{split_command_line, substitute_placeholders, substitute_placeholders_str};
pub use asset_index::{AssetIndex, AssetObject, LegacyLayout};
pub use client_json::{ClientJson, GameSide, ResolvedLibrary};
pub use error::ManifestError;
pub use host::{Arch, HostInfo, Os};
pub use maven::{Coord, CoordError};
pub use rules::{evaluate_rules, flatten_arguments, flatten_arguments_vec};
pub use version_manifest::{VersionEntry, VersionManifest};

#[cfg(test)]
mod golden_tests {
    //! End-to-end checks over a realistic modern client.json fragment on every host.

    use crate::host::presets::*;
    use crate::{ClientJson, HostInfo, split_command_line, substitute_placeholders};
    use std::collections::HashMap;

    const CLIENT_1_21: &str = r#"{
      "arguments": {
        "game": ["--username", "${auth_player_name}", "--version", "${version_name}",
                 "--gameDir", "${game_directory}", "--assetsDir", "${assets_root}",
                 {"rules": [{"action": "allow", "features": {"is_demo_user": true}}], "value": "--demo"},
                 {"rules": [{"action": "allow", "features": {"has_custom_resolution": true}}],
                  "value": ["--width", "${resolution_width}", "--height", "${resolution_height}"]}],
        "jvm": [
          {"rules": [{"action": "allow", "os": {"name": "osx"}}], "value": ["-XstartOnFirstThread"]},
          {"rules": [{"action": "allow", "os": {"name": "windows"}}],
           "value": "-XX:HeapDumpPath=MojangTricksIntelDriversForPerformance_javaw.exe_minecraft.exe.heapdump"},
          {"rules": [{"action": "allow", "os": {"name": "windows", "version": "^10\\."}}],
           "value": ["-Dos.name=Windows 10", "-Dos.version=10.0"]},
          {"rules": [{"action": "allow", "os": {"arch": "x86"}}], "value": "-Xss1M"},
          "-Djava.library.path=${natives_directory}", "-cp", "${classpath}"]
      },
      "assetIndex": {"id": "19", "url": "https://piston-meta.mojang.com/v1/packages/x/19.json", "sha1": "x"},
      "javaVersion": {"component": "java-runtime-delta", "majorVersion": 21},
      "mainClass": "net.minecraft.client.main.Main",
      "libraries": [
        {"name": "org.lwjgl:lwjgl:3.3.3", "downloads": {"artifact": {"path": "org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3.jar", "sha1": "a", "url": "https://l/a"}}},
        {"name": "org.lwjgl:lwjgl:3.3.3:natives-macos-arm64",
         "downloads": {"artifact": {"path": "org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3-natives-macos-arm64.jar", "sha1": "b", "url": "https://l/b"}},
         "rules": [{"action": "allow", "os": {"name": "osx"}}]},
        {"name": "org.lwjgl:lwjgl:3.3.3:natives-windows",
         "downloads": {"artifact": {"path": "org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3-natives-windows.jar", "sha1": "c", "url": "https://l/c"}},
         "rules": [{"action": "allow", "os": {"name": "windows"}}]}
      ]
    }"#;

    fn jvm(host: &HostInfo) -> Vec<String> {
        ClientJson::parse(CLIENT_1_21.as_bytes())
            .unwrap()
            .jvm_arguments_vec(host)
    }

    #[test]
    fn jvm_arguments_per_host() {
        assert_eq!(
            jvm(&windows_x64()),
            vec![
                "-XX:HeapDumpPath=MojangTricksIntelDriversForPerformance_javaw.exe_minecraft.exe.heapdump",
                "-Dos.name=Windows 10",
                "-Dos.version=10.0",
                "-Djava.library.path=${natives_directory}",
                "-cp",
                "${classpath}"
            ]
        );
        assert_eq!(
            jvm(&mac_intel()),
            vec![
                "-XstartOnFirstThread",
                "-Xss1M",
                "-Djava.library.path=${natives_directory}",
                "-cp",
                "${classpath}"
            ]
        );
        assert_eq!(
            jvm(&mac_arm()),
            vec![
                "-XstartOnFirstThread",
                "-Djava.library.path=${natives_directory}",
                "-cp",
                "${classpath}"
            ]
        );
        assert_eq!(
            jvm(&linux_x64()),
            vec![
                "-Djava.library.path=${natives_directory}",
                "-cp",
                "${classpath}"
            ]
        );
        assert_eq!(jvm(&windows_x86())[3], "-Xss1M");
    }

    #[test]
    fn string_and_vec_forms_agree_on_every_host() {
        let cj = ClientJson::parse(CLIENT_1_21.as_bytes()).unwrap();
        for h in all() {
            assert_eq!(
                cj.jvm_arguments_vec(&h),
                split_command_line(&cj.jvm_arguments(&h))
            );
            assert_eq!(
                cj.game_arguments_vec(&h),
                split_command_line(&cj.game_arguments(&h))
            );
        }
    }

    #[test]
    fn libraries_per_host() {
        let cj = ClientJson::parse(CLIENT_1_21.as_bytes()).unwrap();
        let paths = |h: &HostInfo| -> Vec<String> {
            cj.libraries(h)
                .unwrap()
                .into_iter()
                .map(|l| l.path)
                .collect()
        };
        assert_eq!(paths(&mac_arm()).len(), 2);
        assert!(paths(&windows_x64())[1].ends_with("natives-windows.jar"));
        assert_eq!(paths(&linux_x64()).len(), 1);
        assert_eq!(cj.java_major(), 21);
        assert_eq!(cj.runtime_component(), "java-runtime-delta");
        assert_eq!(cj.asset_index_id().unwrap(), "19");
    }

    #[test]
    fn game_arguments_substitute_without_demo_or_resolution() {
        let cj = ClientJson::parse(CLIENT_1_21.as_bytes()).unwrap();
        let mut argv = cj.game_arguments_vec(&linux_x64());
        let map: HashMap<&str, &str> = [
            ("${auth_player_name}", "Steve"),
            ("${version_name}", "1.21.5"),
            ("${game_directory}", "/p/Pack"),
            ("${assets_root}", "/l/shared/assets"),
        ]
        .into();
        substitute_placeholders(&mut argv, &map);
        assert_eq!(
            argv,
            vec![
                "--username",
                "Steve",
                "--version",
                "1.21.5",
                "--gameDir",
                "/p/Pack",
                "--assetsDir",
                "/l/shared/assets"
            ]
        );
    }
}
