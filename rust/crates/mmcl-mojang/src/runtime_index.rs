// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Mojang's Java runtime index (`all.json`) and per-component file manifests
//! (spec 3.7).

use indexmap::IndexMap;
use serde::Deserialize;

use crate::error::ManifestError;

/// Where the runtime index is fetched from.
pub const RUNTIME_INDEX_URL: &str = "https://launchermeta.mojang.com/v1/products/java-runtime/2ec0cc96c44e5a76b9c8b7c39df7210883d12871/all.json";

/// A `{sha1, size, url}` file reference.
#[derive(Clone, Debug, PartialEq, Eq, Deserialize)]
pub struct FileRef {
    #[serde(default)]
    pub sha1: Option<String>,
    #[serde(default)]
    pub size: Option<u64>,
    pub url: String,
}

/// An entry's `version` block.
#[derive(Clone, Debug, PartialEq, Eq, Deserialize)]
pub struct RuntimeVersion {
    pub name: String,
    #[serde(default)]
    pub released: Option<String>,
}

/// One element of a component's array. Only `[0]` is ever used.
#[derive(Clone, Debug, PartialEq, Eq, Deserialize)]
pub struct RuntimeEntry {
    pub manifest: FileRef,
    pub version: RuntimeVersion,
}

/// `all.json`: platform → component → entries, in file order.
#[derive(Clone, Debug, Default, PartialEq, Eq, Deserialize)]
#[serde(transparent)]
pub struct RuntimeIndex {
    pub platforms: IndexMap<String, IndexMap<String, Vec<RuntimeEntry>>>,
}

impl RuntimeIndex {
    /// Parse `all.json` (or the cached `runtime/mojang-runtime-index.json`).
    ///
    /// Only the top level must be an object. A platform that is not an object,
    /// a component that is not an array and an entry that does not fit
    /// [`RuntimeEntry`] are dropped, so a shape change in one platform Mojang
    /// publishes (say `gamecore`) cannot break runtime installs everywhere.
    /// Java navigated the tree lazily and only read the one entry it needed.
    pub fn parse(bytes: &[u8]) -> Result<Self, ManifestError> {
        let raw = crate::error::json::parse_object(bytes)?;
        let mut platforms = IndexMap::with_capacity(raw.len());
        for (platform, components) in raw {
            let serde_json::Value::Object(components) = components else {
                continue;
            };
            let mut parsed = IndexMap::with_capacity(components.len());
            for (component, entries) in components {
                let serde_json::Value::Array(entries) = entries else {
                    continue;
                };
                let entries: Vec<RuntimeEntry> = entries
                    .into_iter()
                    .filter_map(|e| serde_json::from_value(e).ok())
                    .collect();
                parsed.insert(component, entries);
            }
            platforms.insert(platform, parsed);
        }
        Ok(RuntimeIndex { platforms })
    }

    /// `index[platform][component][0]`, with the three Java error cases.
    pub fn entry(&self, platform: &str, component: &str) -> Result<&RuntimeEntry, ManifestError> {
        let p = self
            .platforms
            .get(platform)
            .ok_or_else(|| ManifestError::RuntimePlatformNotFound(platform.to_string()))?;
        let c = p
            .get(component)
            .ok_or_else(|| ManifestError::RuntimeComponentNotFound {
                component: component.to_string(),
                platform: platform.to_string(),
            })?;
        c.first().ok_or_else(|| ManifestError::RuntimeNoEntries {
            component: component.to_string(),
            platform: platform.to_string(),
        })
    }
}

/// `downloads` of a file entry.
#[derive(Clone, Debug, Default, PartialEq, Eq, Deserialize)]
pub struct RuntimeFileDownloads {
    #[serde(default)]
    pub raw: Option<FileRef>,
    #[serde(default)]
    pub lzma: Option<FileRef>,
}

/// One `files` entry of a component manifest.
#[derive(Clone, Debug, PartialEq, Eq, Deserialize)]
#[serde(tag = "type", rename_all = "lowercase")]
pub enum RuntimeFile {
    Directory,
    File {
        #[serde(default)]
        executable: bool,
        #[serde(default)]
        downloads: RuntimeFileDownloads,
    },
    /// Skipped on every OS (B-8).
    Link {
        #[serde(default)]
        target: String,
    },
    /// Any type the launcher does not know.
    #[serde(other)]
    Unknown,
}

/// A per-component `manifest.json`: relative path → entry, in file order
/// (directories come before the files inside them).
#[derive(Clone, Debug, Default, PartialEq, Eq, Deserialize)]
pub struct ComponentManifest {
    pub files: IndexMap<String, RuntimeFile>,
}

impl ComponentManifest {
    /// Parse a component manifest.
    pub fn parse(bytes: &[u8]) -> Result<Self, ManifestError> {
        Ok(serde_json::from_slice(bytes)?)
    }
}

/// The Java runtime component Mojang uses for a Java major version:
/// 8 → `jre-legacy`, 16 → `java-runtime-alpha`, 17 → `java-runtime-gamma`,
/// 21 → `java-runtime-delta`, 25 → `java-runtime-epsilon`; otherwise the
/// nearest component at or above (≤8 legacy, ≤16 alpha, ≤17 gamma, ≤21 delta,
/// else epsilon). Non-positive → legacy.
pub fn component_for_major(major: i32) -> &'static str {
    match major {
        i32::MIN..=8 => "jre-legacy",
        9..=16 => "java-runtime-alpha",
        17 => "java-runtime-gamma",
        18..=21 => "java-runtime-delta",
        _ => "java-runtime-epsilon",
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const INDEX: &str = r#"{
      "windows-x64": {
        "java-runtime-gamma": [{"availability": {"group": 1, "progress": 100},
           "manifest": {"sha1": "m1", "size": 1, "url": "https://piston-meta/manifest.json"},
           "version": {"name": "17.0.8", "released": "2023-01-01"}}],
        "jre-legacy": []
      },
      "gamecore": {}
    }"#;

    #[test]
    fn looks_up_the_first_entry() {
        let idx = RuntimeIndex::parse(INDEX.as_bytes()).unwrap();
        let e = idx.entry("windows-x64", "java-runtime-gamma").unwrap();
        assert_eq!(e.version.name, "17.0.8");
        assert_eq!(e.manifest.url, "https://piston-meta/manifest.json");
        assert_eq!(
            idx.platforms.keys().collect::<Vec<_>>(),
            vec!["windows-x64", "gamecore"]
        );
    }

    #[test]
    fn lookup_errors() {
        let idx = RuntimeIndex::parse(INDEX.as_bytes()).unwrap();
        assert_eq!(
            idx.entry("linux", "jre-legacy").unwrap_err().l10n_key(),
            "runtime.error.platformNotFound"
        );
        let e = idx.entry("windows-x64", "java-runtime-delta").unwrap_err();
        assert_eq!(e.l10n_key(), "runtime.error.componentNotFound");
        assert_eq!(e.l10n_args(), vec!["java-runtime-delta", "windows-x64"]);
        assert_eq!(
            idx.entry("windows-x64", "jre-legacy")
                .unwrap_err()
                .l10n_key(),
            "runtime.error.noEntries"
        );
    }

    #[test]
    fn odd_shapes_elsewhere_do_not_break_the_lookup() {
        let raw = r#"{
          "gamecore": "unexpected",
          "linux-i386": {"jre-legacy": {"not": "an array"}},
          "windows-x64": {
            "java-runtime-gamma": [{"manifest": {"url": 3}},
              {"manifest": {"url": "https://m"}, "version": {"name": "17.0.8"}}],
            "java-runtime-delta": [{"version": {"name": "no manifest"}}]
          }
        }"#;
        let idx = RuntimeIndex::parse(raw.as_bytes()).unwrap();
        assert_eq!(
            idx.entry("windows-x64", "java-runtime-gamma")
                .unwrap()
                .manifest
                .url,
            "https://m"
        );
        assert_eq!(
            idx.entry("windows-x64", "java-runtime-delta")
                .unwrap_err()
                .l10n_key(),
            "runtime.error.noEntries"
        );
        assert_eq!(
            idx.entry("gamecore", "x").unwrap_err().l10n_key(),
            "runtime.error.platformNotFound"
        );
        assert!(RuntimeIndex::parse(b"[]").is_err());
        assert!(RuntimeIndex::parse(b"{").is_err());
    }

    #[test]
    fn parses_component_manifest_in_order() {
        let raw = r#"{"files": {
            "bin": {"type": "directory"},
            "bin/java": {"type": "file", "executable": true,
                         "downloads": {"raw": {"sha1": "r", "size": 1, "url": "https://raw"},
                                       "lzma": {"sha1": "l", "size": 1, "url": "https://lzma"}}},
            "legal/LICENSE": {"type": "link", "target": "../x"},
            "weird": {"type": "socket"}
        }}"#;
        let m = ComponentManifest::parse(raw.as_bytes()).unwrap();
        let keys: Vec<_> = m.files.keys().map(String::as_str).collect();
        assert_eq!(keys, vec!["bin", "bin/java", "legal/LICENSE", "weird"]);
        assert_eq!(m.files["bin"], RuntimeFile::Directory);
        match &m.files["bin/java"] {
            RuntimeFile::File {
                executable,
                downloads,
            } => {
                assert!(*executable);
                assert_eq!(downloads.raw.as_ref().unwrap().url, "https://raw");
            }
            other => panic!("{other:?}"),
        }
        assert_eq!(
            m.files["legal/LICENSE"],
            RuntimeFile::Link {
                target: "../x".into()
            }
        );
        assert_eq!(m.files["weird"], RuntimeFile::Unknown);
    }

    #[test]
    fn major_to_component_table() {
        let cases = [
            (8, "jre-legacy"),
            (16, "java-runtime-alpha"),
            (17, "java-runtime-gamma"),
            (21, "java-runtime-delta"),
            (25, "java-runtime-epsilon"),
            (0, "jre-legacy"),
            (-3, "jre-legacy"),
            (7, "jre-legacy"),
            (11, "java-runtime-alpha"),
            (19, "java-runtime-delta"),
            (22, "java-runtime-epsilon"),
            (99, "java-runtime-epsilon"),
        ];
        for (major, comp) in cases {
            assert_eq!(component_for_major(major), comp, "{major}");
        }
        assert_eq!(
            component_for_major(crate::client_json::DEFAULT_JAVA_MAJOR_VERSION),
            crate::client_json::DEFAULT_RUNTIME_COMPONENT
        );
    }
}
