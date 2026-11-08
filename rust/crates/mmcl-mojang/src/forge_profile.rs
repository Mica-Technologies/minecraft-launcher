// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Forge/NeoForge installer metadata (spec 3.5, 8.1-8.3): the launcher-profile
//! `version.json` (or legacy `install_profile.versionInfo`) and the
//! `install_profile.json` data and processors.

use indexmap::IndexMap;
use serde_json::{Map, Value};

use crate::error::ManifestError;
use crate::error::json::{
    as_bool, opt_array, opt_object, opt_string, parse_object, primitive_string,
};
use crate::host::HostInfo;
use crate::rules::{flatten_arguments, flatten_arguments_vec};

/// Legacy Forge's server main class (B-22).
pub const LEGACY_FORGE_SERVER_MAIN_CLASS: &str =
    "net.minecraftforge.fml.relauncher.ServerLaunchWrapper";

/// The Forge version manifest: `version.json` or legacy `versionInfo`.
#[derive(Clone, Debug, PartialEq)]
pub struct ForgeVersionManifest {
    raw: Map<String, Value>,
    id: String,
    main_class: String,
}

impl ForgeVersionManifest {
    /// Pick the manifest the way Java does: `version.json` if the installer has
    /// one, else `install_profile.versionInfo` if it is an object, else
    /// `UNABLE_FIND_FORGE_VERSION_FILE_TEXT`.
    pub fn select(
        version_json: Option<&[u8]>,
        install_profile: Option<&[u8]>,
    ) -> Result<Self, ManifestError> {
        if let Some(bytes) = version_json {
            return Self::from_object(parse_object(bytes)?);
        }
        if let Some(bytes) = install_profile
            && let Some(info) = parse_object(bytes)?.remove("versionInfo")
            && let Value::Object(info) = info
        {
            return Self::from_object(info);
        }
        Err(ManifestError::ForgeVersionFileMissing)
    }

    /// Validate an already-parsed manifest: `id` and `mainClass` are required.
    pub fn from_object(raw: Map<String, Value>) -> Result<Self, ManifestError> {
        let id =
            opt_string(&raw, "id").ok_or_else(|| ManifestError::ForgeMissingField("id".into()))?;
        let main_class = opt_string(&raw, "mainClass")
            .ok_or_else(|| ManifestError::ForgeMissingField("mainClass".into()))?;
        Ok(ForgeVersionManifest {
            raw,
            id,
            main_class,
        })
    }

    /// The underlying object.
    pub fn raw(&self) -> &Map<String, Value> {
        &self.raw
    }

    /// The version id, e.g. `1.20.1-forge-47.2.0` (the loader version).
    pub fn id(&self) -> &str {
        &self.id
    }

    /// `mainClass`.
    pub fn main_class(&self) -> &str {
        &self.main_class
    }

    /// `inheritsFrom`, else the id before `-forge`, else the id.
    pub fn minecraft_version(&self) -> String {
        if let Some(v) = opt_string(&self.raw, "inheritsFrom") {
            return v;
        }
        match self.id.find("-forge") {
            Some(i) => self.id[..i].to_string(),
            None => self.id.clone(),
        }
    }

    /// `minecraftArguments` if present, else `arguments.game` flattened, else `""`.
    pub fn minecraft_arguments(&self, host: &HostInfo) -> String {
        if let Some(s) = opt_string(&self.raw, "minecraftArguments") {
            return s;
        }
        flatten_arguments(self.arguments("game"), host)
    }

    /// `arguments.jvm` flattened, or `""`.
    pub fn jvm_arguments(&self, host: &HostInfo) -> String {
        flatten_arguments(self.arguments("jvm"), host)
    }

    /// `arguments.jvm` as argv tokens.
    pub fn jvm_arguments_vec(&self, host: &HostInfo) -> Vec<String> {
        flatten_arguments_vec(self.arguments("jvm"), host)
    }

    fn arguments(&self, kind: &str) -> Option<&[Value]> {
        opt_object(&self.raw, "arguments").and_then(|a| opt_array(a, kind))
    }

    /// `None` for modlauncher / bootstraplauncher packs (they use the client
    /// main class), else legacy `ServerLaunchWrapper`.
    pub fn server_main_class(&self) -> Option<&'static str> {
        server_main_class_for(&self.main_class)
    }

    /// The `libraries` entries as typed views (entries without a `name` are
    /// returned too; callers decide).
    pub fn libraries(&self) -> Vec<ForgeLibrary<'_>> {
        opt_array(&self.raw, "libraries")
            .unwrap_or(&[])
            .iter()
            .filter_map(Value::as_object)
            .map(ForgeLibrary)
            .collect()
    }
}

/// See [`ForgeVersionManifest::server_main_class`].
pub fn server_main_class_for(client_main_class: &str) -> Option<&'static str> {
    if client_main_class.starts_with("cpw.mods.modlauncher")
        || client_main_class.starts_with("cpw.mods.bootstraplauncher")
    {
        None
    } else {
        Some(LEGACY_FORGE_SERVER_MAIN_CLASS)
    }
}

/// A Forge `libraries[]` entry (version.json shape, legacy fields included).
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct ForgeLibrary<'a>(pub &'a Map<String, Value>);

impl<'a> ForgeLibrary<'a> {
    /// `name` (Maven coordinate).
    pub fn name(&self) -> Option<String> {
        opt_string(self.0, "name")
    }
    /// Legacy repository `url`.
    pub fn url(&self) -> Option<String> {
        opt_string(self.0, "url")
    }
    /// `downloads.artifact`.
    pub fn artifact(&self) -> Option<ArtifactRef> {
        let a = opt_object(self.0, "downloads").and_then(|d| opt_object(d, "artifact"))?;
        Some(ArtifactRef {
            path: opt_string(a, "path"),
            url: opt_string(a, "url"),
            sha1: opt_string(a, "sha1").filter(|s| !s.trim().is_empty()),
            size: a.get("size").and_then(Value::as_u64),
        })
    }
    /// `clientreq`, default `true`.
    pub fn client_required(&self) -> bool {
        self.0.get("clientreq").and_then(as_bool).unwrap_or(true)
    }
    /// `serverreq`, default `true`.
    pub fn server_required(&self) -> bool {
        self.0.get("serverreq").and_then(as_bool).unwrap_or(true)
    }
    /// The raw object.
    pub fn raw(&self) -> &'a Map<String, Value> {
        self.0
    }
}

/// A `downloads.artifact` block; every field optional.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ArtifactRef {
    pub path: Option<String>,
    pub url: Option<String>,
    /// Non-blank SHA-1 only.
    pub sha1: Option<String>,
    pub size: Option<u64>,
}

/// One install-profile processor.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Processor {
    /// `None` = every side.
    pub sides: Option<Vec<String>>,
    pub jar: Option<String>,
    pub classpath: Vec<String>,
    /// `None` when `args` is absent (an error at run time).
    pub args: Option<Vec<String>>,
}

impl Processor {
    /// Whether the processor runs for `side` (`"client"` / `"server"`).
    pub fn applies_to(&self, side: &str) -> bool {
        self.sides
            .as_ref()
            .is_none_or(|s| s.iter().any(|x| x == side))
    }
}

/// `install_profile.json`.
#[derive(Clone, Debug, PartialEq)]
pub struct InstallProfile {
    raw: Map<String, Value>,
}

impl InstallProfile {
    /// Parse `install_profile.json`.
    pub fn parse(bytes: &[u8]) -> Result<Self, ManifestError> {
        parse_object(bytes).map(|raw| InstallProfile { raw })
    }

    /// The raw object.
    pub fn raw(&self) -> &Map<String, Value> {
        &self.raw
    }

    /// `true` when both `processors` and `data` are present (modern installer).
    pub fn has_processors_and_data(&self) -> bool {
        self.raw.contains_key("processors") && self.raw.contains_key("data")
    }

    /// `data`: key → (client, server) values, in file order.
    pub fn data(&self) -> IndexMap<String, SidedValue> {
        let mut out = IndexMap::new();
        if let Some(d) = opt_object(&self.raw, "data") {
            for (k, v) in d {
                if let Some(o) = v.as_object() {
                    out.insert(
                        k.clone(),
                        SidedValue {
                            client: opt_string(o, "client"),
                            server: opt_string(o, "server"),
                        },
                    );
                }
            }
        }
        out
    }

    /// `data[key][side]`.
    pub fn data_value(&self, key: &str, side: &str) -> Option<String> {
        opt_object(&self.raw, "data")
            .and_then(|d| opt_object(d, key))
            .and_then(|o| opt_string(o, side))
    }

    /// `data.PATCHED_SHA[side]`, trimmed, single quotes stripped, blank → `None`.
    pub fn patched_sha(&self, side: &str) -> Option<String> {
        self.data_value("PATCHED_SHA", side)
            .and_then(|s| normalize_patched_sha(&s))
    }

    /// `processors[]`.
    pub fn processors(&self) -> Vec<Processor> {
        let strings = |v: Option<&Value>| -> Option<Vec<String>> {
            v.and_then(Value::as_array)
                .map(|a| a.iter().filter_map(primitive_string).collect())
        };
        opt_array(&self.raw, "processors")
            .unwrap_or(&[])
            .iter()
            .filter_map(Value::as_object)
            .map(|p| Processor {
                sides: strings(p.get("sides")),
                jar: opt_string(p, "jar"),
                classpath: strings(p.get("classpath")).unwrap_or_default(),
                args: strings(p.get("args")),
            })
            .collect()
    }

    /// `libraries[]` (processor libraries).
    pub fn libraries(&self) -> Vec<ForgeLibrary<'_>> {
        opt_array(&self.raw, "libraries")
            .unwrap_or(&[])
            .iter()
            .filter_map(Value::as_object)
            .map(ForgeLibrary)
            .collect()
    }
}

/// A `data` entry's per-side values.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct SidedValue {
    pub client: Option<String>,
    pub server: Option<String>,
}

impl SidedValue {
    /// The value for `"client"` or `"server"`.
    pub fn get(&self, side: &str) -> Option<&str> {
        match side {
            "client" => self.client.as_deref(),
            "server" => self.server.as_deref(),
            _ => None,
        }
    }
}

/// Trim, strip single quotes, blank → `None` (PATCHED_SHA values look like `'abc'`).
pub fn normalize_patched_sha(s: &str) -> Option<String> {
    let v = s.trim().replace('\'', "");
    let v = v.trim();
    (!v.is_empty()).then(|| v.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::host::presets::linux_x64;
    use serde_json::json;

    fn bytes(v: Value) -> Vec<u8> {
        v.to_string().into_bytes()
    }

    #[test]
    fn selects_version_json_first() {
        let vj = bytes(
            json!({"id": "1.20.1-forge-47.2.0", "inheritsFrom": "1.20.1",
            "mainClass": "cpw.mods.bootstraplauncher.BootstrapLauncher",
            "arguments": {"game": ["--launchTarget", "forgeclient"], "jvm": ["-DignoreList=a,b", "-p", "${library_directory}/x"]}}),
        );
        let ip = bytes(json!({"versionInfo": {"id": "legacy", "mainClass": "x"}}));
        let m = ForgeVersionManifest::select(Some(&vj), Some(&ip)).unwrap();
        assert_eq!(m.id(), "1.20.1-forge-47.2.0");
        assert_eq!(m.minecraft_version(), "1.20.1");
        assert_eq!(
            m.minecraft_arguments(&linux_x64()),
            "--launchTarget forgeclient"
        );
        assert_eq!(
            m.jvm_arguments_vec(&linux_x64()),
            vec!["-DignoreList=a,b", "-p", "${library_directory}/x"]
        );
        assert_eq!(m.server_main_class(), None);
    }

    #[test]
    fn falls_back_to_legacy_version_info() {
        let ip = bytes(
            json!({"install": {}, "versionInfo": {"id": "1.7.10-Forge10.13.4.1614-1.7.10",
            "mainClass": "net.minecraft.launchwrapper.Launch",
            "minecraftArguments": "--username ${auth_player_name} --tweakClass cpw.mods.fml.common.launcher.FMLTweaker",
            "libraries": [{"name": "net.minecraftforge:forge:1.7.10-10.13.4.1614-1.7.10", "url": "http://files.minecraftforge.net/maven/"},
                          {"name": "x:y:1", "clientreq": false, "serverreq": true}]}}),
        );
        let m = ForgeVersionManifest::select(None, Some(&ip)).unwrap();
        assert_eq!(
            m.minecraft_version(),
            "1.7.10-Forge10.13.4.1614-1.7.10",
            "no -forge, no inheritsFrom → id"
        );
        assert!(
            m.minecraft_arguments(&linux_x64())
                .starts_with("--username")
        );
        assert_eq!(m.server_main_class(), Some(LEGACY_FORGE_SERVER_MAIN_CLASS));
        let libs = m.libraries();
        assert_eq!(libs.len(), 2);
        assert!(libs[0].client_required() && libs[0].server_required());
        assert!(!libs[1].client_required());
        assert!(libs[0].artifact().is_none());
    }

    #[test]
    fn id_before_dash_forge_is_the_minecraft_version() {
        let m = ForgeVersionManifest::from_object(
            json!({"id": "1.12.2-forge-14.23.5.2860", "mainClass": "m"})
                .as_object()
                .unwrap()
                .clone(),
        )
        .unwrap();
        assert_eq!(m.minecraft_version(), "1.12.2");
    }

    #[test]
    fn missing_files_and_fields() {
        assert_eq!(
            ForgeVersionManifest::select(None, None),
            Err(ManifestError::ForgeVersionFileMissing)
        );
        let ip = bytes(json!({"versionInfo": "nope"}));
        assert_eq!(
            ForgeVersionManifest::select(None, Some(&ip)),
            Err(ManifestError::ForgeVersionFileMissing)
        );
        let vj = bytes(json!({"mainClass": "m"}));
        assert_eq!(
            ForgeVersionManifest::select(Some(&vj), None),
            Err(ManifestError::ForgeMissingField("id".into()))
        );
        let vj = bytes(json!({"id": "i"}));
        let e = ForgeVersionManifest::select(Some(&vj), None).unwrap_err();
        assert_eq!(e, ManifestError::ForgeMissingField("mainClass".into()));
        assert_eq!(e.l10n_key(), "forge.error.manifestMissingField");
    }

    #[test]
    fn library_artifact_view() {
        let vj = bytes(json!({"id": "i", "mainClass": "m", "libraries": [
            {"name": "net.minecraftforge:forge:1.20.1-47.2.0", "downloads": {"artifact":
                {"path": "net/minecraftforge/forge/1.20.1-47.2.0/forge-1.20.1-47.2.0.jar", "url": "", "sha1": " ", "size": 3}}}]}));
        let m = ForgeVersionManifest::select(Some(&vj), None).unwrap();
        let a = m.libraries()[0].artifact().unwrap();
        assert_eq!(a.url.as_deref(), Some(""));
        assert_eq!(a.sha1, None, "blank sha1 dropped");
        assert_eq!(a.size, Some(3));
    }

    #[test]
    fn install_profile_data_and_processors() {
        let ip = InstallProfile::parse(&bytes(json!({
            "data": {
                "MAPPINGS": {"client": "[de.oceanlabs.mcp:mcp_config:1.20.1:mappings@txt]", "server": "[s]"},
                "BINPATCH": {"client": "/data/client.lzma", "server": "/data/server.lzma"},
                "PATCHED_SHA": {"client": " 'abc123' ", "server": "''"}
            },
            "processors": [
                {"sides": ["client"], "jar": "net.minecraftforge:installertools:1.3.0",
                 "classpath": ["net.md-5:SpecialSource:1.11.0"], "args": ["--task", "MCP_DATA", "--output", "{MAPPINGS}"],
                 "outputs": {"x": "y"}},
                {"jar": "a:b:1", "args": []},
                {"sides": ["server"], "jar": "c:d:1"}
            ],
            "libraries": [{"name": "a:b:1", "downloads": {"artifact": {"path": "a/b/1/b-1.jar", "url": "https://x", "sha1": "s"}}}]
        })))
        .unwrap();
        assert!(ip.has_processors_and_data());
        let data = ip.data();
        assert_eq!(
            data.keys().collect::<Vec<_>>(),
            vec!["MAPPINGS", "BINPATCH", "PATCHED_SHA"]
        );
        assert_eq!(data["BINPATCH"].get("server"), Some("/data/server.lzma"));
        assert_eq!(
            ip.data_value("MAPPINGS", "client").as_deref(),
            Some("[de.oceanlabs.mcp:mcp_config:1.20.1:mappings@txt]")
        );
        assert_eq!(ip.patched_sha("client").as_deref(), Some("abc123"));
        assert_eq!(ip.patched_sha("server"), None);
        let procs = ip.processors();
        assert_eq!(procs.len(), 3);
        assert!(procs[0].applies_to("client") && !procs[0].applies_to("server"));
        assert!(procs[1].applies_to("server"), "no sides → every side");
        assert_eq!(procs[0].classpath, vec!["net.md-5:SpecialSource:1.11.0"]);
        assert_eq!(procs[2].args, None);
        assert_eq!(
            ip.libraries()[0].artifact().unwrap().path.as_deref(),
            Some("a/b/1/b-1.jar")
        );
        let legacy = InstallProfile::parse(&bytes(json!({"versionInfo": {}}))).unwrap();
        assert!(!legacy.has_processors_and_data());
        assert!(legacy.processors().is_empty());
    }

    #[test]
    fn patched_sha_normalisation() {
        assert_eq!(normalize_patched_sha("'abc'").as_deref(), Some("abc"));
        assert_eq!(normalize_patched_sha("  abc ").as_deref(), Some("abc"));
        assert_eq!(normalize_patched_sha(" ' ' "), None);
        assert_eq!(normalize_patched_sha(""), None);
    }

    #[test]
    fn server_main_class_rules() {
        assert_eq!(server_main_class_for("cpw.mods.modlauncher.Launcher"), None);
        assert_eq!(
            server_main_class_for("cpw.mods.bootstraplauncher.BootstrapLauncher"),
            None
        );
        assert_eq!(
            server_main_class_for("net.minecraft.launchwrapper.Launch"),
            Some(LEGACY_FORGE_SERVER_MAIN_CLASS)
        );
    }
}
