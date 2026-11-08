// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Bell-SW Liberica and Eclipse Adoptium release lookups (spec 3.8).
//!
//! `jre-legacy` is always Liberica 8u392 (Mojang's 8u51 is too old for Forge). Where Mojang
//! publishes no runtime at all (Linux ARM64, open question Q-2) every other component is
//! fetched as the latest Liberica JRE of its Java major version, with Adoptium (Temurin) as the
//! second source.

use std::path::{Path, PathBuf};

use mmcl_mojang::{Arch, HostInfo, Os};
use serde_json::Value;

use crate::error::RuntimeError;

/// The JRE 8u392 query. `{OS}` and `{ARCH}` are filled by [`api_url`].
pub const LIBERICA_JRE8_API_TEMPLATE: &str = "https://api.bell-sw.com/v1/liberica/releases?version-feature=8&version-update=392&bitness=64&installation-type=archive&os={OS}&arch={ARCH}&bundle-type=jre";

/// The latest JRE of one feature version. `{MAJOR}`, `{OS}` and `{ARCH}` are filled by
/// [`latest_api_url`].
pub const LIBERICA_LATEST_API_TEMPLATE: &str = "https://api.bell-sw.com/v1/liberica/releases?version-feature={MAJOR}&version-modifier=latest&bitness=64&installation-type=archive&package-type=tar.gz&os={OS}&arch={ARCH}&bundle-type=jre";

/// Adoptium's latest Temurin JRE of one feature version. `{MAJOR}`, `{OS}` and `{ARCH}` are
/// filled by [`adoptium_api_url`].
pub const ADOPTIUM_LATEST_API_TEMPLATE: &str = "https://api.adoptium.net/v3/assets/latest/{MAJOR}/hotspot?architecture={ARCH}&image_type=jre&os={OS}&vendor=eclipse";

/// Bell-SW's `os` and `arch` query tokens: Windows is always `("windows", "x86")` (Bell-SW has
/// no Windows ARM JRE 8; x64 runs under emulation), macOS `("macos", arm|x86)`, everything else
/// `("linux", arm|x86)`, where `arm` means the Java `os.arch` contains `aarch64`.
pub fn os_arch(host: &HostInfo) -> (&'static str, &'static str) {
    if host.os == Os::Windows {
        return ("windows", "x86");
    }
    let arch = if host.java_os_arch.contains("aarch64") {
        "arm"
    } else {
        "x86"
    };
    if host.os == Os::MacOs {
        ("macos", arch)
    } else {
        ("linux", arch)
    }
}

/// [`LIBERICA_JRE8_API_TEMPLATE`] with both placeholders substituted.
pub fn api_url(os: &str, arch: &str) -> String {
    LIBERICA_JRE8_API_TEMPLATE
        .replace("{OS}", os)
        .replace("{ARCH}", arch)
}

/// [`LIBERICA_LATEST_API_TEMPLATE`] for one Java major.
pub fn latest_api_url(major: u32, os: &str, arch: &str) -> String {
    LIBERICA_LATEST_API_TEMPLATE
        .replace("{MAJOR}", &major.to_string())
        .replace("{OS}", os)
        .replace("{ARCH}", arch)
}

/// [`ADOPTIUM_LATEST_API_TEMPLATE`] for one Java major on `host`.
pub fn adoptium_api_url(major: u32, host: &HostInfo) -> String {
    let os = match host.os {
        Os::Windows => "windows",
        Os::MacOs => "mac",
        Os::Linux | Os::Other => "linux",
    };
    let arch = match host.arch {
        Arch::Aarch64 => "aarch64",
        Arch::X86 => "x32",
        Arch::X86_64 | Arch::Other => "x64",
    };
    ADOPTIUM_LATEST_API_TEMPLATE
        .replace("{MAJOR}", &major.to_string())
        .replace("{OS}", os)
        .replace("{ARCH}", arch)
}

/// The folder a Liberica archive extracts to: `jre8u392` for `("jre", 8, 392)`.
pub fn extracted_folder_name(
    bundle_type: &str,
    feature_version: i64,
    update_version: i64,
) -> String {
    format!("{bundle_type}{feature_version}u{update_version}")
}

/// `primary` when it exists, else `alt` when it exists (the macOS `.jre` suffix), else
/// `primary`, so a fresh install always has a definite target.
pub fn resolve_effective_folder(primary: &Path, alt: &Path) -> PathBuf {
    if primary.exists() {
        primary.to_path_buf()
    } else if alt.exists() {
        alt.to_path_buf()
    } else {
        primary.to_path_buf()
    }
}

/// Which digest an archive download is checked with.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Digest {
    Sha1,
    Sha256,
}

impl Digest {
    /// The algorithm's display name.
    pub fn name(self) -> &'static str {
        match self {
            Digest::Sha1 => "SHA-1",
            Digest::Sha256 => "SHA-256",
        }
    }
}

/// Where a release came from (recorded in the `.source` file of a non-Mojang runtime).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ReleaseSource {
    Liberica,
    Adoptium,
}

impl ReleaseSource {
    /// The token stored on disk and shown in logs.
    pub fn as_str(self) -> &'static str {
        match self {
            ReleaseSource::Liberica => "liberica",
            ReleaseSource::Adoptium => "adoptium",
        }
    }

    /// Parses [`Self::as_str`].
    pub fn parse(s: &str) -> Option<Self> {
        match s.trim() {
            "liberica" => Some(ReleaseSource::Liberica),
            "adoptium" => Some(ReleaseSource::Adoptium),
            _ => None,
        }
    }

    /// The display name for log lines.
    pub fn display_name(self) -> &'static str {
        match self {
            ReleaseSource::Liberica => "Bell-SW Liberica",
            ReleaseSource::Adoptium => "Eclipse Adoptium",
        }
    }
}

/// One downloadable JRE archive.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Release {
    pub source: ReleaseSource,
    /// The version string written to the `.version` marker (e.g. `8u392+9`, `17.0.13+12`).
    pub version: String,
    /// Bell-SW `bundleType` (`jre`); Adoptium gives `jre` too.
    pub bundle_type: String,
    pub feature_version: i64,
    pub update_version: i64,
    /// Plain archive file name, kept next to the runtime.
    pub filename: String,
    pub download_url: String,
    pub digest: Digest,
    pub hash: String,
    /// `true` for `.tar.gz`, `false` for `.zip`.
    pub tar_gz: bool,
}

fn json_err(e: impl std::fmt::Display) -> RuntimeError {
    RuntimeError::Json(e.to_string())
}

fn req_str(obj: &serde_json::Map<String, Value>, key: &str) -> Result<String, RuntimeError> {
    match obj.get(key) {
        Some(Value::String(s)) => Ok(s.clone()),
        Some(Value::Number(n)) => Ok(n.to_string()),
        Some(Value::Bool(b)) => Ok(b.to_string()),
        _ => Err(RuntimeError::Json(format!("missing field {key}"))),
    }
}

fn opt_int(obj: &serde_json::Map<String, Value>, key: &str, default: i64) -> i64 {
    match obj.get(key) {
        Some(Value::Number(n)) => n.as_i64().unwrap_or(default),
        Some(Value::String(s)) => s.trim().parse().unwrap_or(default),
        _ => default,
    }
}

/// Parses a Bell-SW releases response (a JSON array) and returns element `[0]`, or the first
/// `tar.gz` element when `require_tar_gz` is set. `Ok(None)` for an empty array.
pub fn parse_liberica(bytes: &[u8], require_tar_gz: bool) -> Result<Option<Release>, RuntimeError> {
    let v: Value = serde_json::from_slice(bytes).map_err(json_err)?;
    let Value::Array(items) = v else {
        return Err(RuntimeError::Json("expected a JSON array".into()));
    };
    for item in &items {
        let Value::Object(obj) = item else {
            return Err(RuntimeError::Json("expected a JSON object".into()));
        };
        let package_type = obj
            .get("packageType")
            .and_then(Value::as_str)
            .unwrap_or("tar.gz")
            .to_string();
        if require_tar_gz && package_type != "tar.gz" {
            continue;
        }
        return Ok(Some(Release {
            source: ReleaseSource::Liberica,
            version: req_str(obj, "version")?,
            bundle_type: req_str(obj, "bundleType")?,
            feature_version: opt_int(obj, "featureVersion", 8),
            update_version: opt_int(obj, "updateVersion", 0),
            filename: req_str(obj, "filename")?,
            download_url: req_str(obj, "downloadUrl")?,
            digest: Digest::Sha1,
            hash: req_str(obj, "sha1")?,
            tar_gz: package_type == "tar.gz",
        }));
    }
    Ok(None)
}

/// Parses an Adoptium `assets/latest` response: the first element whose binary package is a
/// `.tar.gz` (or `.zip`) JRE with a SHA-256 checksum. `Ok(None)` when none fits.
pub fn parse_adoptium(bytes: &[u8]) -> Result<Option<Release>, RuntimeError> {
    let v: Value = serde_json::from_slice(bytes).map_err(json_err)?;
    let Value::Array(items) = v else {
        return Err(RuntimeError::Json("expected a JSON array".into()));
    };
    for item in &items {
        let Some(pkg) = item.pointer("/binary/package").and_then(Value::as_object) else {
            continue;
        };
        let (Ok(name), Ok(link), Ok(checksum)) = (
            req_str(pkg, "name"),
            req_str(pkg, "link"),
            req_str(pkg, "checksum"),
        ) else {
            continue;
        };
        let tar_gz = name.ends_with(".tar.gz");
        if !tar_gz && !name.ends_with(".zip") {
            continue;
        }
        let version = item
            .get("release_name")
            .and_then(Value::as_str)
            .map(str::to_string)
            .or_else(|| {
                item.pointer("/version/semver")
                    .and_then(Value::as_str)
                    .map(str::to_string)
            })
            .unwrap_or_else(|| name.clone());
        let ver = item.get("version").and_then(Value::as_object);
        let feature = ver.map(|o| opt_int(o, "major", 0)).unwrap_or(0);
        let update = ver.map(|o| opt_int(o, "security", 0)).unwrap_or(0);
        return Ok(Some(Release {
            source: ReleaseSource::Adoptium,
            version,
            bundle_type: "jre".into(),
            feature_version: feature,
            update_version: update,
            filename: name,
            download_url: link,
            digest: Digest::Sha256,
            hash: checksum,
            tar_gz,
        }));
    }
    Ok(None)
}

/// `true` when `name` is a plain file name (no separators, no `..`, no NUL, not empty), so
/// joining it to the runtime folder cannot leave it.
pub fn is_plain_file_name(name: &str) -> bool {
    !name.is_empty() && name != "." && name != ".." && !name.contains(['/', '\\', '\0', ':'])
}

#[cfg(test)]
mod tests {
    use super::*;
    use mmcl_mojang::host::presets;

    #[test]
    fn os_arch_tokens_per_host() {
        let cases = [
            (presets::windows_x64(), ("windows", "x86")),
            (presets::windows_arm64(), ("windows", "x86")),
            (presets::windows_x86(), ("windows", "x86")),
            (presets::mac_intel(), ("macos", "x86")),
            (presets::mac_arm(), ("macos", "arm")),
            (presets::linux_x64(), ("linux", "x86")),
            (presets::linux_arm64(), ("linux", "arm")),
            (presets::freebsd_x64(), ("linux", "x86")),
        ];
        for (host, want) in cases {
            assert_eq!(os_arch(&host), want, "{host:?}");
        }
    }

    #[test]
    fn arch_token_matches_the_build_target() {
        let (_, arch) = os_arch(HostInfo::detect());
        if cfg!(windows) {
            assert_eq!(arch, "x86");
        } else if cfg!(target_arch = "aarch64") {
            assert_eq!(arch, "arm");
        } else {
            assert_eq!(arch, "x86");
        }
    }

    #[test]
    fn substitutes_both_placeholders() {
        let url = api_url("linux", "arm");
        assert!(url.contains("os=linux"), "{url}");
        assert!(url.contains("arch=arm"), "{url}");
        assert!(url.starts_with("https://api.bell-sw.com/"));
        assert!(!url.contains('{'), "{url}");
    }

    #[test]
    fn requests_8u392_specifically() {
        let url = api_url("windows", "x86");
        assert!(url.contains("version-feature=8"));
        assert!(url.contains("version-update=392"));
        assert!(url.contains("bundle-type=jre"));
    }

    #[test]
    fn latest_urls() {
        let url = latest_api_url(17, "linux", "arm");
        assert!(url.contains("version-feature=17"));
        assert!(url.contains("version-modifier=latest"));
        assert!(url.contains("os=linux") && url.contains("arch=arm"));
        assert!(!url.contains('{'));
        let a = adoptium_api_url(21, &presets::linux_arm64());
        assert_eq!(
            a,
            "https://api.adoptium.net/v3/assets/latest/21/hotspot?architecture=aarch64&image_type=jre&os=linux&vendor=eclipse"
        );
    }

    #[test]
    fn folder_names() {
        assert_eq!(extracted_folder_name("jre", 8, 392), "jre8u392");
        assert_eq!(extracted_folder_name("jre", 8, 1), "jre8u1");
        assert_ne!(
            extracted_folder_name("jre", 8, 1),
            extracted_folder_name("jre", 8, 2)
        );
    }

    #[test]
    fn effective_folder_fallbacks() {
        let d = tempfile::tempdir().unwrap();
        let primary = d.path().join("jre8u392");
        let alt = d.path().join("jre8u392.jre");
        assert_eq!(resolve_effective_folder(&primary, &alt), primary, "neither");
        std::fs::create_dir_all(&alt).unwrap();
        assert_eq!(resolve_effective_folder(&primary, &alt), alt, "only alt");
        std::fs::create_dir_all(&primary).unwrap();
        assert_eq!(resolve_effective_folder(&primary, &alt), primary, "both");
    }

    #[test]
    fn parses_liberica() {
        let raw = r#"[{"version":"8u392+9","bundleType":"jre","featureVersion":8,"updateVersion":392,
            "sha1":"abc","filename":"bellsoft-jre8u392+9-linux-aarch64.tar.gz",
            "downloadUrl":"https://download.bell-sw.com/x.tar.gz","packageType":"tar.gz"}]"#;
        let r = parse_liberica(raw.as_bytes(), false).unwrap().unwrap();
        assert_eq!(r.version, "8u392+9");
        assert_eq!(
            extracted_folder_name(&r.bundle_type, r.feature_version, r.update_version),
            "jre8u392"
        );
        assert!(r.tar_gz);
        assert_eq!(r.digest, Digest::Sha1);
        assert_eq!(parse_liberica(b"[]", false).unwrap(), None);
        assert!(parse_liberica(b"{}", false).is_err());
        let zip = r#"[{"version":"v","bundleType":"jre","sha1":"s","filename":"f.zip","downloadUrl":"u","packageType":"zip"}]"#;
        let r = parse_liberica(zip.as_bytes(), false).unwrap().unwrap();
        assert!(!r.tar_gz);
        assert_eq!((r.feature_version, r.update_version), (8, 0), "defaults");
        assert_eq!(parse_liberica(zip.as_bytes(), true).unwrap(), None);
        let missing = r#"[{"bundleType":"jre"}]"#;
        assert!(parse_liberica(missing.as_bytes(), false).is_err());
    }

    #[test]
    fn parses_adoptium() {
        let raw = r#"[{"binary":{"image_type":"jre","package":{"checksum":"deadbeef",
            "link":"https://github.com/adoptium/x.tar.gz",
            "name":"OpenJDK17U-jre_aarch64_linux_hotspot_17.0.13_11.tar.gz","size":1}},
            "release_name":"jdk-17.0.13+11","version":{"major":17,"minor":0,"security":13,"semver":"17.0.13+11"}}]"#;
        let r = parse_adoptium(raw.as_bytes()).unwrap().unwrap();
        assert_eq!(r.source, ReleaseSource::Adoptium);
        assert_eq!(r.version, "jdk-17.0.13+11");
        assert_eq!(r.digest, Digest::Sha256);
        assert_eq!(r.hash, "deadbeef");
        assert!(r.tar_gz);
        assert_eq!((r.feature_version, r.update_version), (17, 13));
        assert_eq!(parse_adoptium(b"[]").unwrap(), None);
        assert_eq!(parse_adoptium(br#"[{"binary":{}}]"#).unwrap(), None);
    }

    #[test]
    fn plain_file_names() {
        assert!(is_plain_file_name("bellsoft-jre8u392+9-linux-amd64.tar.gz"));
        for bad in ["", ".", "..", "../x.tar.gz", "a/b", "a\\b", "C:x", "a\0b"] {
            assert!(!is_plain_file_name(bad), "{bad:?}");
        }
    }

    #[test]
    fn release_source_round_trip() {
        for s in [ReleaseSource::Liberica, ReleaseSource::Adoptium] {
            assert_eq!(ReleaseSource::parse(s.as_str()), Some(s));
        }
        assert_eq!(ReleaseSource::parse("mojang"), None);
    }
}
