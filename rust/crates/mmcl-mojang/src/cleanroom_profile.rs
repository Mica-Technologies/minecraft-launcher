// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Cleanroom installer metadata. The Cleanroom installer jar (a Forge 1.12.2 fork on Java 25 and
//! LWJGL 3) carries two files:
//!
//! - `version.json`: a *standalone* launcher profile (no `inheritsFrom`) with its own
//!   `javaVersion`, `assetIndex`, `downloads.client/server` and every library, natives included.
//!   It has the client.json shape, so it is read as a [`crate::ClientJson`].
//! - `install_profile.json`: Forge's spec-1 shape without processors, plus Cleanroom's own
//!   fields, read here: the Minecraft version, the server main class and tweakers, and JVM
//!   arguments.

use serde_json::{Map, Value};

use crate::error::ManifestError;
use crate::error::json::{opt_array, opt_string, parse_object, primitive_string};

/// The Minecraft version every Cleanroom release targets (the installer's own constant).
pub const CLEANROOM_MINECRAFT_VERSION: &str = "1.12.2";
/// The id prefix of a Cleanroom version (`Cleanroom-0.7.14-alpha`).
pub const CLEANROOM_ID_PREFIX: &str = "Cleanroom-";
/// The server tweaker the installer falls back to when `serverTweakers` is empty.
pub const DEFAULT_SERVER_TWEAKER: &str = "net.minecraftforge.fml.common.launcher.FMLServerTweaker";

/// The parts of Cleanroom's `install_profile.json` the launcher uses.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct CleanroomProfile {
    raw: Map<String, Value>,
}

impl CleanroomProfile {
    /// Parses `install_profile.json` (must be a JSON object).
    pub fn parse(bytes: &[u8]) -> Result<Self, ManifestError> {
        parse_object(bytes).map(|raw| CleanroomProfile { raw })
    }

    /// `minecraft`, when present and non-blank.
    pub fn minecraft(&self) -> Option<String> {
        opt_string(&self.raw, "minecraft").filter(|s| !s.trim().is_empty())
    }

    /// `version` (the launcher version id, e.g. `Cleanroom-0.7.14-alpha`).
    pub fn version(&self) -> Option<String> {
        opt_string(&self.raw, "version").filter(|s| !s.trim().is_empty())
    }

    /// `serverMainClass`, when present and non-blank.
    pub fn server_main_class(&self) -> Option<String> {
        opt_string(&self.raw, "serverMainClass").filter(|s| !s.trim().is_empty())
    }

    /// `serverTweakers`, or [`DEFAULT_SERVER_TWEAKER`] when absent or empty (as the installer
    /// does).
    pub fn server_tweakers(&self) -> Vec<String> {
        let list = strings(opt_array(&self.raw, "serverTweakers"));
        if list.is_empty() {
            vec![DEFAULT_SERVER_TWEAKER.to_string()]
        } else {
            list
        }
    }

    /// `jvmArgs` (JVM arguments for both sides; empty for 0.7.14-alpha).
    pub fn jvm_args(&self) -> Vec<String> {
        strings(opt_array(&self.raw, "jvmArgs"))
    }

    /// The server's game arguments: `--tweakClass <tweaker>` per server tweaker.
    pub fn server_game_args(&self) -> Vec<String> {
        self.server_tweakers()
            .into_iter()
            .flat_map(|t| ["--tweakClass".to_string(), t])
            .collect()
    }
}

fn strings(a: Option<&[Value]>) -> Vec<String> {
    a.unwrap_or(&[])
        .iter()
        .filter_map(primitive_string)
        .filter(|s| !s.trim().is_empty())
        .collect()
}

/// The Minecraft version of a Cleanroom install: the profile's `minecraft`, else the version
/// JSON's `inheritsFrom` (no current release has one), else [`CLEANROOM_MINECRAFT_VERSION`].
/// Never the standalone id (`Cleanroom-0.7.14-alpha`), which is not a Minecraft version.
pub fn cleanroom_minecraft_version(
    profile: Option<&CleanroomProfile>,
    version_json: &Map<String, Value>,
) -> String {
    profile
        .and_then(CleanroomProfile::minecraft)
        .or_else(|| opt_string(version_json, "inheritsFrom").filter(|s| !s.trim().is_empty()))
        .unwrap_or_else(|| CLEANROOM_MINECRAFT_VERSION.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn obj(v: Value) -> Map<String, Value> {
        v.as_object().unwrap().clone()
    }

    #[test]
    fn reads_the_profile_fields() {
        let p = CleanroomProfile::parse(
            br#"{"minecraft":"1.12.2","version":"Cleanroom-0.7.14-alpha",
                "serverMainClass":"top.outlands.foundation.boot.Foundation",
                "serverTweakers":["net.minecraftforge.fml.common.launcher.FMLServerTweaker"],
                "jvmArgs":["-Dx=1"],"processors":[]}"#,
        )
        .unwrap();
        assert_eq!(p.minecraft().as_deref(), Some("1.12.2"));
        assert_eq!(p.version().as_deref(), Some("Cleanroom-0.7.14-alpha"));
        assert_eq!(
            p.server_main_class().as_deref(),
            Some("top.outlands.foundation.boot.Foundation")
        );
        assert_eq!(p.jvm_args(), ["-Dx=1"]);
        assert_eq!(
            p.server_game_args(),
            [
                "--tweakClass",
                "net.minecraftforge.fml.common.launcher.FMLServerTweaker"
            ]
        );
    }

    #[test]
    fn missing_tweakers_fall_back_like_the_installer() {
        let p = CleanroomProfile::parse(br#"{"serverTweakers":[]}"#).unwrap();
        assert_eq!(p.server_tweakers(), [DEFAULT_SERVER_TWEAKER]);
        assert!(p.server_main_class().is_none());
        assert!(p.jvm_args().is_empty());
        assert!(CleanroomProfile::parse(b"[]").is_err());
    }

    #[test]
    fn minecraft_version_is_never_the_standalone_id() {
        let vj = obj(json!({"id": "Cleanroom-0.7.14-alpha"}));
        let p = CleanroomProfile::parse(br#"{"minecraft":"1.12.2"}"#).unwrap();
        assert_eq!(cleanroom_minecraft_version(Some(&p), &vj), "1.12.2");
        assert_eq!(cleanroom_minecraft_version(None, &vj), "1.12.2");
        let blank = CleanroomProfile::parse(br#"{"minecraft":" "}"#).unwrap();
        assert_eq!(cleanroom_minecraft_version(Some(&blank), &vj), "1.12.2");
        let inherits = obj(json!({"id": "Cleanroom-9", "inheritsFrom": "1.12.2"}));
        assert_eq!(cleanroom_minecraft_version(None, &inherits), "1.12.2");
    }
}
