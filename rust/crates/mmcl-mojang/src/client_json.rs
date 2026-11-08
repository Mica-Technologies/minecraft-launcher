// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The per-version client.json, a.k.a. the "library manifest" (spec 3.2, 3.3, 5).
//!
//! Kept as an order-preserving [`serde_json::Value`] with typed accessors, so
//! unknown or odd fields never fail a parse that Java's Gson would accept.

use serde_json::{Map, Value};

use crate::args::split_command_line;
use crate::error::ManifestError;
use crate::error::json::{
    as_i32, opt_array, opt_object, opt_string, parse_object, required_string,
};
use crate::host::{HostInfo, Os, PLATFORM_MACOS, PLATFORM_UNIX, PLATFORM_WINDOWS};
use crate::rules::{evaluate_rules_value, flatten_arguments, flatten_arguments_vec};

/// `RuntimeConstants.DEFAULT_JAVA_MAJOR_VERSION`.
pub const DEFAULT_JAVA_MAJOR_VERSION: i32 = 8;
/// `RuntimeConstants.DEFAULT_RUNTIME_COMPONENT`.
pub const DEFAULT_RUNTIME_COMPONENT: &str = "jre-legacy";
/// Main class used when a vanilla client.json declares none.
pub const DEFAULT_VANILLA_MAIN_CLASS: &str = "net.minecraft.client.main.Main";

/// Which game jar to download.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum GameSide {
    Client,
    Server,
}

impl GameSide {
    /// `"client"` or `"server"`.
    pub fn as_str(self) -> &'static str {
        match self {
            GameSide::Client => "client",
            GameSide::Server => "server",
        }
    }
}

/// A `{url, sha1, size?}` download.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Download {
    pub url: String,
    pub sha1: String,
    pub size: Option<u64>,
}

/// The `assetIndex` block.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct AssetIndexRef {
    pub id: String,
    pub url: String,
    /// Java ignored this (bug B-3); the Rust launcher verifies the index with it.
    pub sha1: Option<String>,
    pub total_size: Option<u64>,
}

/// `logging.client`, returned only when both `argument` and `file.url` exist.
/// Parsed but not used at launch (B-19).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct LoggingConfig {
    pub argument: String,
    pub url: String,
    pub sha1: Option<String>,
    pub id: Option<String>,
}

/// One library or native resolved for the host.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ResolvedLibrary {
    /// The library's Maven `name`.
    pub name: String,
    pub url: String,
    /// The manifest `path`, with forward slashes (relative to `<pack>/libraries`).
    pub path: String,
    pub sha1: String,
    /// `true` for a classifier native that is extracted to `bin/natives`.
    pub native: bool,
}

/// Result of [`ClientJson::resolve_libraries`].
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct LibraryResolution {
    /// Manifest order, each native pushed before its own artifact.
    pub libraries: Vec<ResolvedLibrary>,
    /// Compact JSON of each entry skipped for lacking `name` + `downloads`.
    /// Callers log each with `log.libraryManifest.skippedUnknownType`.
    pub skipped: Vec<String>,
}

/// A parsed client.json.
#[derive(Clone, Debug, PartialEq)]
pub struct ClientJson {
    raw: Map<String, Value>,
}

impl ClientJson {
    /// Parse client.json bytes (must be a JSON object).
    pub fn parse(bytes: &[u8]) -> Result<Self, ManifestError> {
        parse_object(bytes).map(|raw| ClientJson { raw })
    }

    /// Wrap an already-parsed object.
    pub fn from_value(value: Value) -> Result<Self, ManifestError> {
        match value {
            Value::Object(raw) => Ok(ClientJson { raw }),
            _ => Err(ManifestError::Parse("expected a JSON object".to_string())),
        }
    }

    /// The underlying JSON object.
    pub fn raw(&self) -> &Map<String, Value> {
        &self.raw
    }

    /// `id`, if present.
    pub fn id(&self) -> Option<String> {
        opt_string(&self.raw, "id")
    }

    /// `javaVersion.majorVersion`, default 8. May be non-positive in a broken
    /// manifest; the runtime manager maps that to the legacy runtime.
    pub fn java_major(&self) -> i32 {
        opt_object(&self.raw, "javaVersion")
            .and_then(|j| j.get("majorVersion"))
            .and_then(as_i32)
            .unwrap_or(DEFAULT_JAVA_MAJOR_VERSION)
    }

    /// `javaVersion.component`, default `"jre-legacy"`.
    pub fn runtime_component(&self) -> String {
        opt_object(&self.raw, "javaVersion")
            .and_then(|j| opt_string(j, "component"))
            .unwrap_or_else(|| DEFAULT_RUNTIME_COMPONENT.to_string())
    }

    /// `mainClass`, or `None`.
    pub fn main_class(&self) -> Option<String> {
        opt_string(&self.raw, "mainClass")
    }

    /// [`Self::main_class`] or `net.minecraft.client.main.Main`.
    pub fn main_class_or_default(&self) -> String {
        self.main_class()
            .unwrap_or_else(|| DEFAULT_VANILLA_MAIN_CLASS.to_string())
    }

    /// Presence of the `arguments` key (1.13+ format).
    pub fn has_modern_arguments(&self) -> bool {
        self.raw.contains_key("arguments")
    }

    fn arguments_array(&self, kind: &str) -> Option<&[Value]> {
        opt_object(&self.raw, "arguments").and_then(|a| opt_array(a, kind))
    }

    fn has_arguments_kind(&self, kind: &str) -> bool {
        opt_object(&self.raw, "arguments").is_some_and(|a| a.contains_key(kind))
    }

    /// `arguments.jvm` flattened, or `""`.
    pub fn jvm_arguments(&self, host: &HostInfo) -> String {
        flatten_arguments(self.arguments_array("jvm"), host)
    }

    /// `arguments.jvm` as argv tokens.
    pub fn jvm_arguments_vec(&self, host: &HostInfo) -> Vec<String> {
        flatten_arguments_vec(self.arguments_array("jvm"), host)
    }

    /// `arguments.game` flattened when present, else `minecraftArguments`,
    /// else `""`.
    pub fn game_arguments(&self, host: &HostInfo) -> String {
        if self.has_arguments_kind("game") {
            return flatten_arguments(self.arguments_array("game"), host);
        }
        opt_string(&self.raw, "minecraftArguments").unwrap_or_default()
    }

    /// [`Self::game_arguments`] as argv tokens.
    pub fn game_arguments_vec(&self, host: &HostInfo) -> Vec<String> {
        if self.has_arguments_kind("game") {
            return flatten_arguments_vec(self.arguments_array("game"), host);
        }
        opt_string(&self.raw, "minecraftArguments")
            .map(|s| split_command_line(&s))
            .unwrap_or_default()
    }

    /// `assetIndex.id` (required). Java `extractAssetIndexVersion`.
    ///
    /// The id names `shared/assets/indexes/<id>.json` and
    /// `<pack>/assets/virtual/<id>`, so one that is not a plain file name
    /// (separators, `..`, NUL, a drive colon) is rejected rather than joined.
    pub fn asset_index_id(&self) -> Result<String, ManifestError> {
        let ai = opt_object(&self.raw, "assetIndex")
            .ok_or_else(|| ManifestError::MissingField("assetIndex".into()))?;
        checked_asset_index_id(required_string(ai, "id")?)
    }

    /// The whole `assetIndex` block; `id` and `url` are required, and `id`
    /// is checked as in [`Self::asset_index_id`].
    pub fn asset_index(&self) -> Result<AssetIndexRef, ManifestError> {
        let ai = opt_object(&self.raw, "assetIndex")
            .ok_or_else(|| ManifestError::MissingField("assetIndex".into()))?;
        Ok(AssetIndexRef {
            id: checked_asset_index_id(required_string(ai, "id")?)?,
            url: required_string(ai, "url")?,
            sha1: opt_string(ai, "sha1").filter(|s| !s.trim().is_empty()),
            total_size: ai.get("totalSize").and_then(Value::as_u64),
        })
    }

    /// `downloads.client` or `downloads.server` (`url` and `sha1` required).
    pub fn game_download(&self, side: GameSide) -> Result<Download, ManifestError> {
        let downloads = opt_object(&self.raw, "downloads")
            .ok_or_else(|| ManifestError::MissingField("downloads".into()))?;
        let d = opt_object(downloads, side.as_str())
            .ok_or_else(|| ManifestError::MissingField(side.as_str().into()))?;
        Ok(Download {
            url: required_string(d, "url")?,
            sha1: required_string(d, "sha1")?,
            size: d.get("size").and_then(Value::as_u64),
        })
    }

    /// `logging.client` when both `argument` and `file.url` are present.
    pub fn logging_config(&self) -> Option<LoggingConfig> {
        let client = opt_object(&self.raw, "logging").and_then(|l| opt_object(l, "client"))?;
        let argument = opt_string(client, "argument")?;
        let file = opt_object(client, "file")?;
        Some(LoggingConfig {
            argument,
            url: opt_string(file, "url")?,
            sha1: opt_string(file, "sha1"),
            id: opt_string(file, "id"),
        })
    }

    /// The raw `libraries` array, if present.
    pub fn raw_libraries(&self) -> Option<&[Value]> {
        opt_array(&self.raw, "libraries")
    }

    /// Resolve the libraries and natives that apply to `host`
    /// (`GameLibraryManifest.extractLibraries`).
    pub fn resolve_libraries(&self, host: &HostInfo) -> Result<LibraryResolution, ManifestError> {
        let libs = self
            .raw_libraries()
            .ok_or_else(|| ManifestError::MissingField("libraries".into()))?;
        let mut out = LibraryResolution::default();
        for lib in libs {
            // Java's format check: an entry needs both `downloads` and `name`.
            let Some((obj, downloads)) = lib
                .as_object()
                .filter(|o| o.contains_key("name"))
                .and_then(|o| opt_object(o, "downloads").map(|d| (o, d)))
            else {
                out.skipped.push(lib.to_string());
                continue;
            };
            let name = opt_string(obj, "name").unwrap_or_default();
            let artifact = match opt_object(downloads, "artifact") {
                Some(a) => Some((
                    required_string(a, "path")?,
                    required_string(a, "sha1")?,
                    required_string(a, "url")?,
                )),
                None => None,
            };
            let allowed = evaluate_rules_value(obj.get("rules"), host);

            if let Some(classifiers) = opt_object(downloads, "classifiers") {
                let os_keys = [
                    (PLATFORM_WINDOWS, host.os == Os::Windows),
                    (PLATFORM_MACOS, host.os == Os::MacOs),
                    (PLATFORM_UNIX, host.os == Os::Linux),
                ];
                for (os_key, host_is) in os_keys {
                    if !(allowed && host_is) {
                        continue;
                    }
                    let key = resolve_native_classifier_key(lib, os_key, host);
                    if let Some(native) = opt_object(classifiers, &key) {
                        out.libraries.push(ResolvedLibrary {
                            name: name.clone(),
                            path: required_string(native, "path")?,
                            url: required_string(native, "url")?,
                            sha1: required_string(native, "sha1")?,
                            native: true,
                        });
                    }
                }
            }

            if let Some((path, sha1, url)) = artifact
                && allowed
            {
                out.libraries.push(ResolvedLibrary {
                    name,
                    url,
                    path,
                    sha1,
                    native: false,
                });
            }
        }
        Ok(out)
    }

    /// [`Self::resolve_libraries`] without the skipped list.
    pub fn libraries(&self, host: &HostInfo) -> Result<Vec<ResolvedLibrary>, ManifestError> {
        self.resolve_libraries(host).map(|r| r.libraries)
    }
}

/// Reject an asset index id that is not a single plain path component.
fn checked_asset_index_id(id: String) -> Result<String, ManifestError> {
    let unsafe_id = id.trim().is_empty()
        || id == "."
        || id.contains("..")
        || id.contains(['/', '\\', ':', '\0']);
    if unsafe_id {
        Err(ManifestError::UnsafeAssetIndexId(id))
    } else {
        Ok(id)
    }
}

/// The classifier key for `os_key` (`windows`, `osx`, `linux`): the library's
/// `natives[os_key]` with `${arch}` replaced by `64` when Java `os.arch`
/// contains `64` (so `aarch64` → `64`), else `32`; or `natives-<os_key>` when
/// there is no mapping.
pub fn resolve_native_classifier_key(lib: &Value, os_key: &str, host: &HostInfo) -> String {
    let mapped = lib
        .get("natives")
        .and_then(Value::as_object)
        .and_then(|n| opt_string(n, os_key));
    match mapped {
        Some(key) => {
            let arch = if host.is_64_bit_java_arch() {
                "64"
            } else {
                "32"
            };
            key.replace("${arch}", arch)
        }
        None => format!("natives-{os_key}"),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::host::presets::*;
    use serde_json::json;

    fn cj(v: Value) -> ClientJson {
        ClientJson::from_value(v).unwrap()
    }

    // ---- GameLibraryManifestParsingTest ----

    #[test]
    fn reads_the_declared_java_major_version() {
        assert_eq!(
            cj(json!({"javaVersion": {"majorVersion": 17}})).java_major(),
            17
        );
        assert_eq!(
            cj(json!({"javaVersion": {"majorVersion": 21}})).java_major(),
            21
        );
    }

    #[test]
    fn java_major_version_falls_back_to_default() {
        assert_eq!(cj(json!({})).java_major(), 8);
        assert_eq!(cj(json!({"javaVersion": {}})).java_major(), 8);
        assert_eq!(
            cj(json!({"javaVersion": {"majorVersion": "x"}})).java_major(),
            8
        );
    }

    #[test]
    fn reads_the_declared_runtime_component() {
        assert_eq!(
            cj(json!({"javaVersion": {"component": "jre-legacy"}})).runtime_component(),
            "jre-legacy"
        );
        assert_eq!(
            cj(json!({"javaVersion": {"component": "java-runtime-gamma"}})).runtime_component(),
            "java-runtime-gamma"
        );
    }

    #[test]
    fn runtime_component_falls_back_to_default() {
        assert_eq!(cj(json!({})).runtime_component(), "jre-legacy");
        assert_eq!(
            cj(json!({"javaVersion": {}})).runtime_component(),
            "jre-legacy"
        );
    }

    #[test]
    fn reads_the_declared_main_class() {
        let m = cj(json!({"mainClass": "net.minecraft.client.main.Main"}));
        assert_eq!(
            m.main_class().as_deref(),
            Some("net.minecraft.client.main.Main")
        );
    }

    #[test]
    fn main_class_is_none_when_absent() {
        assert_eq!(cj(json!({})).main_class(), None);
        assert_eq!(
            cj(json!({})).main_class_or_default(),
            "net.minecraft.client.main.Main"
        );
    }

    #[test]
    fn flattens_the_modern_jvm_arguments_array() {
        assert_eq!(
            cj(json!({"arguments": {"jvm": ["-Xmx2G"]}})).jvm_arguments(&linux_x64()),
            "-Xmx2G"
        );
    }

    #[test]
    fn jvm_arguments_are_empty_for_a_legacy_manifest() {
        assert_eq!(cj(json!({})).jvm_arguments(&linux_x64()), "");
        assert!(cj(json!({})).jvm_arguments_vec(&linux_x64()).is_empty());
    }

    #[test]
    fn jvm_arguments_are_empty_when_arguments_block_has_no_jvm_key() {
        assert_eq!(cj(json!({"arguments": {}})).jvm_arguments(&linux_x64()), "");
    }

    #[test]
    fn flattens_the_modern_game_arguments_array() {
        let m = cj(json!({"arguments": {"game": ["--username", "${auth_player_name}"]}}));
        assert_eq!(
            m.game_arguments(&linux_x64()),
            "--username ${auth_player_name}"
        );
    }

    #[test]
    fn falls_back_to_legacy_minecraft_arguments_string() {
        let m = cj(
            json!({"minecraftArguments": "--username ${auth_player_name} --version ${version_name}"}),
        );
        assert_eq!(
            m.game_arguments(&linux_x64()),
            "--username ${auth_player_name} --version ${version_name}"
        );
        assert_eq!(
            m.game_arguments_vec(&linux_x64()),
            vec![
                "--username",
                "${auth_player_name}",
                "--version",
                "${version_name}"
            ]
        );
        assert!(!m.has_modern_arguments());
    }

    #[test]
    fn legacy_fallback_applies_when_arguments_lacks_game() {
        let m = cj(json!({"arguments": {"jvm": []}, "minecraftArguments": "--legacy"}));
        assert_eq!(m.game_arguments(&linux_x64()), "--legacy");
    }

    #[test]
    fn game_arguments_are_empty_when_neither_form_is_present() {
        assert_eq!(cj(json!({})).game_arguments(&linux_x64()), "");
    }

    #[test]
    fn modern_game_arguments_take_precedence_over_legacy_fallback() {
        let m = cj(
            json!({"arguments": {"game": ["--modernArg"]}, "minecraftArguments": "--legacyArg"}),
        );
        assert_eq!(m.game_arguments(&linux_x64()), "--modernArg");
        assert_eq!(m.game_arguments_vec(&linux_x64()), vec!["--modernArg"]);
        assert!(m.has_modern_arguments());
    }

    fn logging(
        argument: Option<&str>,
        url: Option<&str>,
        sha1: Option<&str>,
        id: Option<&str>,
    ) -> ClientJson {
        let mut file = Map::new();
        for (k, v) in [("url", url), ("sha1", sha1), ("id", id)] {
            if let Some(v) = v {
                file.insert(k.into(), json!(v));
            }
        }
        let mut client = Map::new();
        if let Some(a) = argument {
            client.insert("argument".into(), json!(a));
        }
        client.insert("file".into(), Value::Object(file));
        cj(json!({"logging": {"client": client}}))
    }

    #[test]
    fn reads_a_complete_logging_config() {
        let m = logging(
            Some("-Dlog4j.configurationFile=${path}"),
            Some("https://example.test/log4j2.xml"),
            Some("abc123"),
            Some("client-1.12.xml"),
        );
        assert_eq!(
            m.logging_config(),
            Some(LoggingConfig {
                argument: "-Dlog4j.configurationFile=${path}".into(),
                url: "https://example.test/log4j2.xml".into(),
                sha1: Some("abc123".into()),
                id: Some("client-1.12.xml".into()),
            })
        );
    }

    #[test]
    fn logging_config_is_none_when_logging_block_absent() {
        assert_eq!(cj(json!({})).logging_config(), None);
    }

    #[test]
    fn logging_config_is_none_when_client_block_absent() {
        assert_eq!(cj(json!({"logging": {}})).logging_config(), None);
    }

    #[test]
    fn logging_config_is_none_when_argument_missing() {
        assert_eq!(
            logging(None, Some("https://x/l.xml"), Some("abc"), Some("id")).logging_config(),
            None
        );
    }

    #[test]
    fn logging_config_is_none_when_file_url_missing() {
        assert_eq!(
            logging(Some("-D=${path}"), None, None, None).logging_config(),
            None
        );
    }

    #[test]
    fn reads_the_asset_index_id() {
        let m = cj(
            json!({"assetIndex": {"id": "1.20", "url": "https://x/1.20.json", "sha1": "ab", "totalSize": 5}}),
        );
        assert_eq!(m.asset_index_id().unwrap(), "1.20");
        let ai = m.asset_index().unwrap();
        assert_eq!(ai.url, "https://x/1.20.json");
        assert_eq!(ai.sha1.as_deref(), Some("ab"));
        assert_eq!(ai.total_size, Some(5));
    }

    #[test]
    fn errors_when_asset_index_block_is_missing() {
        assert_eq!(
            cj(json!({})).asset_index_id(),
            Err(ManifestError::MissingField("assetIndex".into()))
        );
    }

    #[test]
    fn errors_when_asset_index_id_is_missing() {
        assert_eq!(
            cj(json!({"assetIndex": {}})).asset_index_id(),
            Err(ManifestError::MissingField("id".into()))
        );
        assert_eq!(
            cj(json!({"assetIndex": {"id": null}})).asset_index_id(),
            Err(ManifestError::MissingField("id".into()))
        );
    }

    #[test]
    fn asset_index_id_must_be_a_plain_file_name() {
        for id in [
            "../../config/x",
            "a/b",
            "a\\b",
            "C:x",
            "..",
            ".",
            " ",
            "a\0b",
        ] {
            let m = cj(json!({"assetIndex": {"id": id, "url": "https://x"}}));
            assert_eq!(
                m.asset_index_id(),
                Err(ManifestError::UnsafeAssetIndexId(id.into())),
                "{id:?}"
            );
            assert!(m.asset_index().is_err(), "{id:?}");
        }
        for id in ["1.20", "19", "pre-1.6", "legacy", "3"] {
            let m = cj(json!({"assetIndex": {"id": id, "url": "https://x"}}));
            assert_eq!(m.asset_index_id().unwrap(), id);
            assert_eq!(m.asset_index().unwrap().id, id);
        }
        assert_eq!(
            ManifestError::UnsafeAssetIndexId("x".into()).l10n_key(),
            "mojang.error.unsafeAssetIndexId"
        );
    }

    #[test]
    fn game_downloads() {
        let m = cj(
            json!({"downloads": {"client": {"url": "https://c", "sha1": "c1", "size": 9},
                                         "server": {"url": "https://s", "sha1": "s1"}}}),
        );
        assert_eq!(
            m.game_download(GameSide::Client).unwrap(),
            Download {
                url: "https://c".into(),
                sha1: "c1".into(),
                size: Some(9)
            }
        );
        assert_eq!(m.game_download(GameSide::Server).unwrap().url, "https://s");
        assert!(
            cj(json!({"downloads": {}}))
                .game_download(GameSide::Server)
                .is_err()
        );
        assert!(cj(json!({})).game_download(GameSide::Client).is_err());
    }

    #[test]
    fn parse_rejects_non_objects() {
        assert!(ClientJson::parse(b"[]").is_err());
        assert!(ClientJson::parse(b"{").is_err());
        assert!(
            ClientJson::parse(br#"{"id":"1.20.1"}"#)
                .unwrap()
                .id()
                .is_some()
        );
    }

    // ---- GameLibraryManifestClassifierTest ----

    #[test]
    fn falls_back_to_conventional_key_when_no_natives_mapping_present() {
        let lib = json!({});
        let h = linux_x64();
        assert_eq!(
            resolve_native_classifier_key(&lib, "windows", &h),
            "natives-windows"
        );
        assert_eq!(
            resolve_native_classifier_key(&lib, "osx", &h),
            "natives-osx"
        );
        assert_eq!(
            resolve_native_classifier_key(&lib, "linux", &h),
            "natives-linux"
        );
    }

    #[test]
    fn falls_back_when_natives_mapping_lacks_the_requested_os() {
        let lib = json!({"natives": {"windows": "natives-windows"}});
        assert_eq!(
            resolve_native_classifier_key(&lib, "linux", &linux_x64()),
            "natives-linux"
        );
    }

    #[test]
    fn uses_explicit_natives_mapping_when_present() {
        let lib = json!({"natives": {"osx": "natives-macos-custom"}});
        assert_eq!(
            resolve_native_classifier_key(&lib, "osx", &mac_arm()),
            "natives-macos-custom"
        );
    }

    #[test]
    fn expands_arch_placeholder_to_the_host_architecture() {
        let lib = json!({"natives": {"windows": "natives-windows-${arch}"}});
        assert_eq!(
            resolve_native_classifier_key(&lib, "windows", &windows_x64()),
            "natives-windows-64"
        );
        assert_eq!(
            resolve_native_classifier_key(&lib, "windows", &windows_x86()),
            "natives-windows-32"
        );
    }

    #[test]
    fn expands_arch_placeholder_anywhere_in_the_value() {
        let lib = json!({"natives": {"linux": "${arch}-natives-${arch}"}});
        assert_eq!(
            resolve_native_classifier_key(&lib, "linux", &linux_x86()),
            "32-natives-32"
        );
    }

    #[test]
    fn leaves_values_without_a_placeholder_unchanged() {
        let lib = json!({"natives": {"linux": "natives-linux"}});
        assert_eq!(
            resolve_native_classifier_key(&lib, "linux", &linux_x64()),
            "natives-linux"
        );
    }

    #[test]
    fn arch_token_is_only_ever_64_or_32() {
        let lib = json!({"natives": {"windows": "${arch}"}});
        for h in all() {
            let r = resolve_native_classifier_key(&lib, "windows", &h);
            assert!(r == "64" || r == "32", "{r}");
        }
        assert_eq!(
            resolve_native_classifier_key(&lib, "windows", &mac_arm()),
            "64",
            "aarch64 → 64"
        );
    }

    // ---- GameLibraryManifestExtractLibrariesTest ----

    fn artifact(path: &str, sha1: &str, url: &str) -> Value {
        json!({"path": path, "sha1": sha1, "url": url})
    }

    fn entry(
        name: &str,
        artifact: Option<Value>,
        rules: Option<Value>,
        classifiers: Option<Value>,
    ) -> Value {
        let mut downloads = Map::new();
        if let Some(a) = artifact {
            downloads.insert("artifact".into(), a);
        }
        if let Some(c) = classifiers {
            downloads.insert("classifiers".into(), c);
        }
        let mut lib = Map::new();
        lib.insert("name".into(), json!(name));
        lib.insert("downloads".into(), Value::Object(downloads));
        if let Some(r) = rules {
            lib.insert("rules".into(), r);
        }
        Value::Object(lib)
    }

    fn manifest(libs: Vec<Value>) -> ClientJson {
        cj(json!({ "libraries": libs }))
    }

    fn not_current(h: &HostInfo) -> &'static str {
        if h.platform_name() == "windows" {
            "linux"
        } else {
            "windows"
        }
    }

    fn hosts() -> Vec<HostInfo> {
        vec![windows_x64(), mac_arm(), linux_x64()]
    }

    #[test]
    fn manifest_with_no_libraries_yields_an_empty_list() {
        assert!(manifest(vec![]).libraries(&linux_x64()).unwrap().is_empty());
    }

    #[test]
    fn missing_libraries_key_is_an_error() {
        assert_eq!(
            cj(json!({})).libraries(&linux_x64()),
            Err(ManifestError::MissingField("libraries".into()))
        );
    }

    #[test]
    fn includes_a_library_with_no_rules_for_the_current_platform() {
        for h in hosts() {
            let m = manifest(vec![entry(
                "com.example:lib:1.0",
                Some(artifact(
                    "com/example/lib/1.0/lib-1.0.jar",
                    "abc123",
                    "https://example.test/lib.jar",
                )),
                None,
                None,
            )]);
            let libs = m.libraries(&h).unwrap();
            assert_eq!(libs.len(), 1);
            assert_eq!(libs[0].path, "com/example/lib/1.0/lib-1.0.jar");
            assert_eq!(libs[0].name, "com.example:lib:1.0");
            assert!(!libs[0].native);
        }
    }

    #[test]
    fn excludes_a_library_whose_rules_disallow_every_platform() {
        let m = manifest(vec![entry(
            "c:l:1",
            Some(artifact("p", "s", "u")),
            Some(json!([{"action": "disallow"}])),
            None,
        )]);
        for h in hosts() {
            assert!(m.libraries(&h).unwrap().is_empty());
        }
    }

    #[test]
    fn excludes_a_library_restricted_to_another_platform() {
        for h in hosts() {
            let m = manifest(vec![entry(
                "c:l:1",
                Some(artifact("p", "s", "u")),
                Some(json!([{"action": "allow", "os": {"name": not_current(&h)}}])),
                None,
            )]);
            assert!(m.libraries(&h).unwrap().is_empty());
        }
    }

    #[test]
    fn includes_a_library_explicitly_allowed_for_the_current_platform() {
        for h in hosts() {
            let m = manifest(vec![entry(
                "c:l:1",
                Some(artifact("p", "s", "u")),
                Some(json!([{"action": "allow", "os": {"name": h.platform_name()}}])),
                None,
            )]);
            assert_eq!(m.libraries(&h).unwrap().len(), 1);
        }
    }

    #[test]
    fn skips_an_entry_missing_the_required_downloads_key_without_erroring() {
        let m = manifest(vec![
            json!({"name": "com.example:lib:1.0", "url": "https://libraries.minecraft.net/"}),
        ]);
        let r = m.resolve_libraries(&linux_x64()).unwrap();
        assert!(r.libraries.is_empty());
        assert_eq!(r.skipped.len(), 1);
        assert!(r.skipped[0].contains("com.example:lib:1.0"));
    }

    #[test]
    fn multiple_libraries_are_all_resolved_independently() {
        let m = manifest(vec![
            entry(
                "c:a:1",
                Some(artifact("a.jar", "sha1a", "https://example.test/a.jar")),
                None,
                None,
            ),
            entry(
                "c:b:1",
                Some(artifact("b.jar", "sha1b", "https://example.test/b.jar")),
                None,
                None,
            ),
        ]);
        let libs = m.libraries(&linux_x64()).unwrap();
        assert_eq!(
            libs.iter().map(|l| l.path.as_str()).collect::<Vec<_>>(),
            vec!["a.jar", "b.jar"]
        );
    }

    #[test]
    fn resolves_and_includes_the_native_classifier_for_the_current_platform() {
        for h in hosts() {
            let classifiers = json!({ format!("natives-{}", h.platform_name()):
                artifact("natives/lib-natives.jar", "deadbeef", "https://example.test/lib-natives.jar") });
            let m = manifest(vec![entry(
                "c:l:1",
                Some(artifact(
                    "lib.jar",
                    "sha1val",
                    "https://example.test/lib.jar",
                )),
                None,
                Some(classifiers),
            )]);
            let libs = m.libraries(&h).unwrap();
            assert_eq!(libs.len(), 2);
            let native = libs.iter().find(|l| l.native).expect("native entry");
            assert_eq!(native.path, "natives/lib-natives.jar");
            assert_eq!(native.sha1, "deadbeef");
        }
    }

    #[test]
    fn natives_come_before_their_own_artifact() {
        let classifiers = json!({"natives-windows": artifact("n.jar", "n", "https://n")});
        let m = manifest(vec![
            entry(
                "c:first:1",
                Some(artifact("first.jar", "f", "https://f")),
                None,
                None,
            ),
            entry(
                "c:l:1",
                Some(artifact("lib.jar", "l", "https://l")),
                None,
                Some(classifiers),
            ),
        ]);
        let libs = m.libraries(&windows_x64()).unwrap();
        assert_eq!(
            libs.iter()
                .map(|l| (l.path.as_str(), l.native))
                .collect::<Vec<_>>(),
            vec![("first.jar", false), ("n.jar", true), ("lib.jar", false)]
        );
    }

    #[test]
    fn excludes_the_native_classifier_when_the_library_rules_disallow_the_current_platform() {
        for h in hosts() {
            let classifiers = json!({ format!("natives-{}", h.platform_name()): artifact("n.jar", "d", "https://n") });
            let m = manifest(vec![entry(
                "c:l:1",
                Some(artifact("lib.jar", "s", "https://l")),
                Some(json!([{"action": "allow", "os": {"name": not_current(&h)}}])),
                Some(classifiers),
            )]);
            assert!(
                m.libraries(&h).unwrap().is_empty(),
                "neither artifact nor native"
            );
        }
    }

    #[test]
    fn does_not_include_a_native_entry_when_classifiers_lack_the_current_platform_key() {
        for h in hosts() {
            let classifiers = json!({ format!("natives-{}", not_current(&h)): artifact("o.jar", "s", "https://o") });
            let m = manifest(vec![entry(
                "c:l:1",
                Some(artifact("lib.jar", "s", "https://l")),
                None,
                Some(classifiers),
            )]);
            let libs = m.libraries(&h).unwrap();
            assert_eq!(libs.len(), 1);
            assert!(!libs[0].native);
        }
    }

    #[test]
    fn native_mapping_with_arch_resolves_the_classifier() {
        let lib = json!({"name": "org.lwjgl.lwjgl:lwjgl-platform:2.9.4",
            "natives": {"linux": "natives-linux", "osx": "natives-osx", "windows": "natives-windows-${arch}"},
            "downloads": {"classifiers": {
                "natives-windows-64": artifact("w64.jar", "a", "https://w64"),
                "natives-windows-32": artifact("w32.jar", "b", "https://w32")}}});
        let m = manifest(vec![lib]);
        assert_eq!(m.libraries(&windows_x64()).unwrap()[0].path, "w64.jar");
        assert_eq!(m.libraries(&windows_x86()).unwrap()[0].path, "w32.jar");
        assert!(m.libraries(&linux_x64()).unwrap().is_empty());
    }

    #[test]
    fn bsd_hosts_get_artifacts_but_no_classifier_natives() {
        let classifiers = json!({"natives-linux": artifact("n.jar", "n", "https://n")});
        let m = manifest(vec![entry(
            "c:l:1",
            Some(artifact("l.jar", "l", "https://l")),
            None,
            Some(classifiers),
        )]);
        let libs = m.libraries(&freebsd_x64()).unwrap();
        assert_eq!(libs.len(), 1);
        assert!(!libs[0].native);
    }

    #[test]
    fn modern_split_natives_go_through_the_artifact_branch() {
        let m = manifest(vec![entry(
            "org.lwjgl:lwjgl:3.3.1:natives-macos-arm64",
            Some(artifact(
                "org/lwjgl/lwjgl/3.3.1/lwjgl-3.3.1-natives-macos-arm64.jar",
                "x",
                "https://x",
            )),
            Some(json!([{"action": "allow", "os": {"name": "osx"}}])),
            None,
        )]);
        let libs = m.libraries(&mac_arm()).unwrap();
        assert_eq!(libs.len(), 1);
        assert!(!libs[0].native, "classpath, not bin/natives");
        assert!(m.libraries(&windows_x64()).unwrap().is_empty());
    }

    #[test]
    fn artifact_missing_a_required_field_is_an_error() {
        let m = manifest(vec![entry(
            "c:l:1",
            Some(json!({"path": "p", "url": "u"})),
            None,
            None,
        )]);
        assert_eq!(
            m.libraries(&linux_x64()),
            Err(ManifestError::MissingField("sha1".into()))
        );
    }

    #[test]
    fn classifier_only_library_contributes_just_the_native() {
        let classifiers = json!({"natives-osx": artifact("n.jar", "n", "https://n")});
        let m = manifest(vec![entry("c:l:1", None, None, Some(classifiers))]);
        let libs = m.libraries(&mac_intel()).unwrap();
        assert_eq!(libs.len(), 1);
        assert!(libs[0].native);
    }
}
