// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Fabric meta launcher profile (`bin/fabric-profile.json`, spec 3.6, 8.5).

use serde_json::{Map, Value};

use crate::error::ManifestError;
use crate::error::json::{opt_array, opt_object, opt_string};
use crate::host::HostInfo;
use crate::rules::{flatten_arguments, flatten_arguments_vec};

/// Fabric's server main class.
pub const FABRIC_SERVER_MAIN_CLASS: &str =
    "net.fabricmc.loader.impl.launch.server.FabricServerLauncher";

/// Profile URL for a Minecraft + loader version pair.
pub fn profile_url(minecraft_version: &str, loader_version: &str) -> String {
    format!(
        "https://meta.fabricmc.net/v2/versions/loader/{minecraft_version}/{loader_version}/profile/json"
    )
}

/// Why a Fabric profile could not be used. Each case is reported separately.
#[derive(Clone, Debug, PartialEq, Eq, thiserror::Error)]
pub enum FabricProfileError {
    #[error("the Fabric profile is not valid JSON: {0}")]
    Malformed(String),
    #[error("the Fabric profile is not a JSON object")]
    NotAnObject,
    #[error("the Fabric profile is missing the required field {0}")]
    MissingField(&'static str),
}

impl FabricProfileError {
    /// The localisation key for this error (Rust overlay bundle).
    pub fn l10n_key(&self) -> &'static str {
        match self {
            FabricProfileError::Malformed(_) => "mojang.error.fabricMalformed",
            FabricProfileError::NotAnObject => "mojang.error.fabricNotObject",
            FabricProfileError::MissingField(_) => "mojang.error.fabricMissingField",
        }
    }

    /// The MessageFormat arguments for [`Self::l10n_key`].
    pub fn l10n_args(&self) -> Vec<String> {
        match self {
            FabricProfileError::Malformed(m) => vec![m.clone()],
            FabricProfileError::NotAnObject => Vec::new(),
            FabricProfileError::MissingField(f) => vec![(*f).to_string()],
        }
    }
}

impl From<FabricProfileError> for ManifestError {
    fn from(e: FabricProfileError) -> Self {
        match e {
            FabricProfileError::Malformed(m) => ManifestError::Parse(m),
            FabricProfileError::NotAnObject => {
                ManifestError::Parse("expected a JSON object".into())
            }
            FabricProfileError::MissingField(f) => ManifestError::MissingField(f.into()),
        }
    }
}

/// A `libraries[]` entry.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct FabricLibrary {
    pub name: String,
    /// Repository base URL, if given.
    pub url: Option<String>,
    pub sha1: Option<String>,
}

impl FabricLibrary {
    /// The repository base with a trailing `/`: `url` if non-blank, else by
    /// prefix (`net.fabricmc:` → Fabric maven, `net.minecraft:` → Mojang,
    /// else Maven Central).
    pub fn repository_base(&self) -> String {
        let base = match self.url.as_deref().map(str::trim) {
            Some(u) if !u.is_empty() => u.to_string(),
            _ if self.name.starts_with("net.fabricmc:") => {
                "https://maven.fabricmc.net/".to_string()
            }
            _ if self.name.starts_with("net.minecraft:") => {
                "https://libraries.minecraft.net/".to_string()
            }
            _ => "https://repo1.maven.org/maven2/".to_string(),
        };
        if base.ends_with('/') {
            base
        } else {
            format!("{base}/")
        }
    }
}

/// A validated Fabric profile.
#[derive(Clone, Debug, PartialEq)]
pub struct FabricProfile {
    raw: Map<String, Value>,
    main_class: String,
    inherits_from: String,
}

impl FabricProfile {
    /// Parse and validate: `mainClass`, `inheritsFrom` and a `libraries` array
    /// are required.
    pub fn parse(bytes: &[u8]) -> Result<Self, FabricProfileError> {
        let v: Value = serde_json::from_slice(bytes)
            .map_err(|e| FabricProfileError::Malformed(e.to_string()))?;
        let Value::Object(raw) = v else {
            return Err(FabricProfileError::NotAnObject);
        };
        let main_class =
            opt_string(&raw, "mainClass").ok_or(FabricProfileError::MissingField("mainClass"))?;
        let inherits_from = opt_string(&raw, "inheritsFrom")
            .ok_or(FabricProfileError::MissingField("inheritsFrom"))?;
        if opt_array(&raw, "libraries").is_none() {
            return Err(FabricProfileError::MissingField("libraries"));
        }
        Ok(FabricProfile {
            raw,
            main_class,
            inherits_from,
        })
    }

    /// `id`, if present.
    pub fn id(&self) -> Option<String> {
        opt_string(&self.raw, "id")
    }

    /// `mainClass`.
    pub fn main_class(&self) -> &str {
        &self.main_class
    }

    /// `inheritsFrom` (the Minecraft version).
    pub fn minecraft_version(&self) -> &str {
        &self.inherits_from
    }

    /// The loader version derived from `id`.
    pub fn loader_version(&self) -> String {
        derive_loader_version(self.id().as_deref(), &self.inherits_from)
    }

    fn arguments(&self, kind: &str) -> Option<&[Value]> {
        opt_object(&self.raw, "arguments").and_then(|a| opt_array(a, kind))
    }

    /// `arguments.game` flattened.
    pub fn minecraft_arguments(&self, host: &HostInfo) -> String {
        flatten_arguments(self.arguments("game"), host)
    }

    /// `arguments.jvm` flattened.
    pub fn jvm_arguments(&self, host: &HostInfo) -> String {
        flatten_arguments(self.arguments("jvm"), host)
    }

    /// `arguments.jvm` as argv tokens.
    pub fn jvm_arguments_vec(&self, host: &HostInfo) -> Vec<String> {
        flatten_arguments_vec(self.arguments("jvm"), host)
    }

    /// Library objects with a non-blank `name`, in declaration order.
    pub fn libraries(&self) -> Vec<FabricLibrary> {
        opt_array(&self.raw, "libraries")
            .unwrap_or(&[])
            .iter()
            .filter_map(Value::as_object)
            .filter_map(|o| {
                let name = opt_string(o, "name").filter(|n| !n.trim().is_empty())?;
                Some(FabricLibrary {
                    name,
                    url: opt_string(o, "url"),
                    sha1: opt_string(o, "sha1").filter(|s| !s.trim().is_empty()),
                })
            })
            .collect()
    }
}

/// Strip a `fabric-loader-` prefix and a `-<mc>` suffix from the profile id.
/// Blank id → `""`.
pub fn derive_loader_version(id: Option<&str>, minecraft_version: &str) -> String {
    let Some(id) = id.filter(|i| !i.trim().is_empty()) else {
        return String::new();
    };
    let mut working = id.strip_prefix("fabric-loader-").unwrap_or(id);
    if !minecraft_version.trim().is_empty()
        && let Some(stripped) = working.strip_suffix(&format!("-{minecraft_version}"))
    {
        working = stripped;
    }
    working.to_string()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::host::presets::mac_arm;

    const PROFILE: &str = r#"{ "id": "fabric-loader-0.16.10-1.21.5", "inheritsFrom": "1.21.5",
      "mainClass": "net.fabricmc.loader.impl.launch.knot.KnotClient",
      "arguments": {"game": [], "jvm": ["-DFabricMcEmu= net.minecraft.client.main.Main "]},
      "libraries": [ {"name": "net.fabricmc:fabric-loader:0.16.10", "url": "https://maven.fabricmc.net/", "sha1": "abc"},
                     {"name": "org.ow2.asm:asm:9.7.1", "url": "https://maven.fabricmc.net"},
                     {"name": "net.fabricmc:intermediary:1.21.5"},
                     {"name": "net.minecraft:x:1"},
                     {"name": "  "}, "junk" ] }"#;

    #[test]
    fn parses_a_profile() {
        let p = FabricProfile::parse(PROFILE.as_bytes()).unwrap();
        assert_eq!(
            p.main_class(),
            "net.fabricmc.loader.impl.launch.knot.KnotClient"
        );
        assert_eq!(p.minecraft_version(), "1.21.5");
        assert_eq!(p.loader_version(), "0.16.10");
        assert_eq!(p.minecraft_arguments(&mac_arm()), "");
        assert_eq!(
            p.jvm_arguments_vec(&mac_arm()),
            vec!["-DFabricMcEmu= net.minecraft.client.main.Main "]
        );
        let libs = p.libraries();
        assert_eq!(libs.len(), 4);
        assert_eq!(libs[0].sha1.as_deref(), Some("abc"));
        assert_eq!(libs[1].repository_base(), "https://maven.fabricmc.net/");
        assert_eq!(libs[2].repository_base(), "https://maven.fabricmc.net/");
        assert_eq!(
            libs[3].repository_base(),
            "https://libraries.minecraft.net/"
        );
        let other = FabricLibrary {
            name: "a:b:1".into(),
            url: Some(" ".into()),
            sha1: None,
        };
        assert_eq!(other.repository_base(), "https://repo1.maven.org/maven2/");
    }

    #[test]
    fn each_failure_has_its_own_error() {
        assert!(matches!(
            FabricProfile::parse(b"{"),
            Err(FabricProfileError::Malformed(_))
        ));
        assert_eq!(
            FabricProfile::parse(b"[]"),
            Err(FabricProfileError::NotAnObject)
        );
        assert_eq!(
            FabricProfile::parse(br#"{"inheritsFrom": "1", "libraries": []}"#),
            Err(FabricProfileError::MissingField("mainClass"))
        );
        assert_eq!(
            FabricProfile::parse(br#"{"mainClass": "m", "libraries": []}"#),
            Err(FabricProfileError::MissingField("inheritsFrom"))
        );
        assert_eq!(
            FabricProfile::parse(br#"{"mainClass": "m", "inheritsFrom": "1", "libraries": {}}"#),
            Err(FabricProfileError::MissingField("libraries"))
        );
    }

    #[test]
    fn each_failure_has_its_own_localisation_key() {
        let errors = [
            FabricProfileError::Malformed("eof".into()),
            FabricProfileError::NotAnObject,
            FabricProfileError::MissingField("libraries"),
        ];
        let keys: std::collections::HashSet<_> = errors.iter().map(|e| e.l10n_key()).collect();
        assert_eq!(keys.len(), 3);
        assert_eq!(errors[0].l10n_args(), vec!["eof"]);
        assert!(errors[1].l10n_args().is_empty());
        assert_eq!(errors[2].l10n_args(), vec!["libraries"]);
    }

    #[test]
    fn loader_version_derivation() {
        assert_eq!(
            derive_loader_version(Some("fabric-loader-0.16.10-1.21.5"), "1.21.5"),
            "0.16.10"
        );
        assert_eq!(
            derive_loader_version(Some("fabric-loader-0.16.10-1.21.5"), ""),
            "0.16.10-1.21.5"
        );
        assert_eq!(derive_loader_version(Some("custom"), "1.21.5"), "custom");
        assert_eq!(derive_loader_version(Some(" "), "1.21.5"), "");
        assert_eq!(derive_loader_version(None, "1.21.5"), "");
    }

    #[test]
    fn profile_urls() {
        assert_eq!(
            profile_url("1.21.5", "0.16.10"),
            "https://meta.fabricmc.net/v2/versions/loader/1.21.5/0.16.10/profile/json"
        );
    }
}
