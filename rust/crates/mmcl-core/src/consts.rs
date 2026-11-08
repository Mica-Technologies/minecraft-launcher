// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Launcher constants shared across subsystems (`consts/*` in Java). Ports and every other
//! OS-visible id live in [`crate::identity`]; folder and file names live in [`crate::paths`].

/// `LauncherConstants`: settings bounds and exit codes.
pub mod launcher {
    /// Lower bound of the minimum-RAM slider, in GB.
    pub const SETTINGS_MIN_RAM_MIN: f64 = 0.2;
    /// Upper bound of the minimum-RAM slider, in GB.
    pub const SETTINGS_MIN_RAM_MAX: f64 = 32.0;
    /// Lower bound of the maximum-RAM slider, in GB.
    pub const SETTINGS_MAX_RAM_MIN: f64 = 1.0;
    /// Upper bound of the maximum-RAM slider, in GB.
    pub const SETTINGS_MAX_RAM_MAX: f64 = 64.0;
    /// Normal exit status.
    pub const EXIT_STATUS_CODE_GOOD: i32 = 0;
    /// Exit status of a refused or failed start (second launch not delivered, bad arguments).
    pub const EXIT_STATUS_CODE_ERROR: i32 = 1;
}

/// `ModPackConstants`.
pub mod modpack {
    /// Modpack manifest extension (with the dot).
    pub const MODPACK_FILE_EXTENSION: &str = ".mmcjson";
    /// File-picker glob for modpack manifests.
    pub const MODPACK_FILE_GLOB: &str = "*.mmcjson";
    /// File-type description of modpack manifests.
    pub const MODPACK_FILE_DESCRIPTION: &str = "Mica Minecraft Modpack";
    /// Forge mod loader id (the default).
    pub const MOD_LOADER_FORGE: &str = "forge";
    /// NeoForge mod loader id.
    pub const MOD_LOADER_NEOFORGE: &str = "neoforge";
    /// Fabric mod loader id.
    pub const MOD_LOADER_FABRIC: &str = "fabric";
    /// The loader assumed when a manifest names none.
    pub const MOD_LOADER_DEFAULT: &str = MOD_LOADER_FORGE;
    /// Mojang rule OS name for Windows.
    pub const PLATFORM_WINDOWS: &str = "windows";
    /// Mojang rule OS name for macOS.
    pub const PLATFORM_MACOS: &str = "osx";
    /// Mojang rule OS name for Linux.
    pub const PLATFORM_UNIX: &str = "linux";
    /// The hosted list of installable packs.
    pub const AVAILABLE_PACKS_MANIFEST_URL: &str =
        "https://micauseaststorage.blob.core.windows.net/mc-launcher-api/installable.json";
    /// The list key inside [`AVAILABLE_PACKS_MANIFEST_URL`].
    pub const AVAILABLE_PACKS_MANIFEST_LIST_KEY: &str = "available";
    /// Prefix of first-party pack manifests (the v6 `.json` -> `.mmcjson` migration gate).
    pub const FIRST_PARTY_MANIFEST_PREFIX: &str =
        "https://micauseaststorage.blob.core.windows.net/mc-launcher-api/";
    /// Mojang's version manifest (v2).
    pub const MINECRAFT_VERSION_MANIFEST_URL: &str =
        "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";

    /// `"<name>: <version>"` (Java's `MODPACK_FRIENDLY_NAME_TEMPLATE`, `%s: %s`).
    pub fn friendly_name(name: &str, version: &str) -> String {
        format!("{name}: {version}")
    }

    /// Pack-relative files and folders, as path segments (join with
    /// [`crate::paths::join_segments`]).
    pub mod local {
        pub const FORGE_JAR: [&str; 2] = ["bin", "modpack.jar"];
        pub const FABRIC_PROFILE: [&str; 2] = ["bin", "fabric-profile.json"];
        pub const MINECRAFT_JAR: [&str; 2] = ["bin", "minecraft.jar"];
        pub const MODS: [&str; 1] = ["mods"];
        pub const LIBRARIES: [&str; 1] = ["libraries"];
        pub const CONFIGS: [&str; 1] = ["config"];
        pub const NATIVES: [&str; 2] = ["bin", "natives"];
        pub const RESOURCEPACKS: [&str; 1] = ["resourcepacks"];
        pub const SHADERPACKS: [&str; 1] = ["shaderpacks"];
        pub const ASSETS: [&str; 1] = ["assets"];
        pub const LOGO: [&str; 2] = ["bin", "logo.png"];
        pub const BACKGROUND: [&str; 2] = ["bin", "background.png"];
    }
}

/// `ManifestConstants`.
pub mod manifest {
    /// Placeholder for the first two hash characters in [`MINECRAFT_ASSET_SERVER_URL_TEMPLATE`].
    pub const MINECRAFT_ASSET_SERVER_URL_FOLDER_KEY: &str = "folder";
    /// Placeholder for the full hash in [`MINECRAFT_ASSET_SERVER_URL_TEMPLATE`].
    pub const MINECRAFT_ASSET_SERVER_URL_HASH_KEY: &str = "hash";
    /// Mojang asset server URL template.
    pub const MINECRAFT_ASSET_SERVER_URL_TEMPLATE: &str =
        "https://resources.download.minecraft.net/folder/hash";
    pub const JSON_FILE_EXTENSION: &str = ".json";
    pub const MINECRAFT_ASSET_MANIFEST_OBJECTS_KEY: &str = "objects";
    pub const MINECRAFT_ASSET_MANIFEST_OBJECT_HASH_KEY: &str = "hash";
    pub const MINECRAFT_LIBRARY_MANIFEST_LIBRARIES_KEY: &str = "libraries";
    pub const MINECRAFT_LIBRARY_MANIFEST_LIBRARY_DOWNLOADS_KEY: &str = "downloads";
    pub const MINECRAFT_LIBRARY_MANIFEST_LIBRARY_NAME_KEY: &str = "name";
    pub const MINECRAFT_LIBRARY_MANIFEST_LIBRARY_DOWNLOADS_ARTIFACT_KEY: &str = "artifact";

    /// The download URL of an asset object: `.../<first two hex chars>/<hash>`. A hash shorter
    /// than two characters is used whole for the folder part.
    pub fn asset_url(hash: &str) -> String {
        let folder = hash.get(..2).unwrap_or(hash);
        format!("https://resources.download.minecraft.net/{folder}/{hash}")
    }
}

/// `ForgeConstants`: key names of the Forge `version.json`.
pub mod forge {
    pub const FORGE_JAR_VERSION_FILE_NAME: &str = "version.json";
    pub const ID_KEY: &str = "id";
    pub const INHERITS_FROM_KEY: &str = "inheritsFrom";
    pub const MINECRAFT_ARGS_KEY: &str = "minecraftArguments";
    pub const MAIN_CLASS_KEY: &str = "mainClass";
    pub const LIBRARIES_KEY: &str = "libraries";
    pub const LIBRARY_NAME_KEY: &str = "name";
    pub const LIBRARY_URL_KEY: &str = "url";
    pub const LIBRARY_DOWNLOADS_KEY: &str = "downloads";
    pub const LIBRARY_ARTIFACT_KEY: &str = "artifact";
    pub const LIBRARY_PATH_KEY: &str = "path";
    pub const LIBRARY_SHA1_KEY: &str = "sha1";
    pub const LIBRARY_SERVER_REQ_KEY: &str = "serverreq";
    pub const LIBRARY_CLIENT_REQ_KEY: &str = "clientreq";
}

/// `RuntimeConstants`.
pub mod runtime {
    pub const DEFAULT_RUNTIME_COMPONENT: &str = "jre-legacy";
    pub const DEFAULT_JAVA_MAJOR_VERSION: u32 = 8;
    pub const MOJANG_RUNTIME_INDEX_URL: &str = "https://launchermeta.mojang.com/v1/products/java-runtime/2ec0cc96c44e5a76b9c8b7c39df7210883d12871/all.json";
    pub const MOJANG_RUNTIME_INDEX_FILE_NAME: &str = "mojang-runtime-index.json";
    pub const RUNTIME_COMPONENT_SUBFOLDER_TEMPLATE: &str = "{COMPONENT}";
    pub const RUNTIME_MANIFEST_FILE_NAME: &str = "runtime-manifest.json";
    pub const RUNTIME_VERSION_FILE_NAME: &str = ".version";
    /// Java executable inside a Windows runtime.
    pub const WIN_JAVA_EXEC_PATH: [&str; 2] = ["bin", "java.exe"];
    /// Java executable inside a macOS runtime.
    pub const MAC_JAVA_EXEC_PATH: [&str; 5] = ["jre.bundle", "Contents", "Home", "bin", "java"];
    /// Java executable inside a Linux runtime.
    pub const LNX_JAVA_EXEC_PATH: [&str; 2] = ["bin", "java"];

    /// Mojang's runtime platform key for an OS and architecture (`std::env::consts` values).
    ///
    /// Fix over Java: the architecture is mapped explicitly, so `x86_64` Linux is `linux` (Java's
    /// substring test would have picked `linux-i386` for a JVM reporting `x86_64`).
    pub fn mojang_platform_key_for(os: &str, arch: &str) -> &'static str {
        let arm = matches!(arch, "aarch64" | "arm");
        match os {
            "windows" => {
                if arm {
                    "windows-arm64"
                } else if arch == "x86" {
                    "windows-x86"
                } else {
                    "windows-x64"
                }
            }
            "macos" => {
                if arm {
                    "mac-os-arm64"
                } else {
                    "mac-os"
                }
            }
            _ => {
                if arch == "x86" {
                    "linux-i386"
                } else {
                    "linux"
                }
            }
        }
    }

    /// [`mojang_platform_key_for`] of this build's target.
    pub fn mojang_platform_key() -> &'static str {
        mojang_platform_key_for(std::env::consts::OS, std::env::consts::ARCH)
    }

    /// The Java executable's path segments inside a runtime folder for an OS.
    pub fn java_exec_segments_for(os: &str) -> &'static [&'static str] {
        match os {
            "windows" => &WIN_JAVA_EXEC_PATH,
            "macos" => &MAC_JAVA_EXEC_PATH,
            _ => &LNX_JAVA_EXEC_PATH,
        }
    }

    /// [`java_exec_segments_for`] of this build's target.
    pub fn java_exec_segments() -> &'static [&'static str] {
        java_exec_segments_for(std::env::consts::OS)
    }
}

/// `GUIConstants`.
pub mod gui {
    pub const URL_MINECRAFT_USER_ICONS_USER_REPLACE_KEY: &str = "{user}";
    pub const URL_MINECRAFT_USER_ICONS: &str = "https://minotar.net/armor/bust/{user}/100.png";
    pub const URL_MINECRAFT_NO_MOD_PACK_IMAGE: &str =
        "https://cdn.pixabay.com/photo/2016/11/11/14/49/minecraft-1816996_960_720.png";
    pub const TITLE_SPLIT_CHAR: &str = " | ";

    /// The avatar URL for a player name or UUID.
    pub fn user_icon_url(user: &str) -> String {
        URL_MINECRAFT_USER_ICONS.replace(URL_MINECRAFT_USER_ICONS_USER_REPLACE_KEY, user)
    }
}

/// `UpdateCheckConstants` and the other release links.
pub mod release {
    pub const UPDATE_CHECK_API_URL: &str =
        "https://api.github.com/repos/Mica-Technologies/minecraft-launcher/releases/latest";
    /// All releases, newest first (the Rust build's `rs-*` pre-releases are only listed here).
    pub const RELEASES_API_URL: &str =
        "https://api.github.com/repos/Mica-Technologies/minecraft-launcher/releases";
    pub const RELEASES_PAGE: &str =
        "https://github.com/Mica-Technologies/minecraft-launcher/releases/latest";
    pub const UPDATE_CHECK_LATEST_URL_KEY: &str = "html_url";
    pub const UPDATE_CHECK_LATEST_VERSION_KEY: &str = "tag_name";
    pub const HELP_URL: &str = "https://github.com/Mica-Technologies/minecraft-launcher/issues";
    pub const ABOUT_URL: &str = "https://minecraft.micatechnologies.com/";
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn platform_keys() {
        use runtime::mojang_platform_key_for as key;
        assert_eq!(key("windows", "x86_64"), "windows-x64");
        assert_eq!(key("windows", "aarch64"), "windows-arm64");
        assert_eq!(key("macos", "aarch64"), "mac-os-arm64");
        assert_eq!(key("macos", "x86_64"), "mac-os");
        assert_eq!(key("linux", "x86_64"), "linux");
        assert_eq!(key("linux", "x86"), "linux-i386");
    }

    #[test]
    fn asset_url_and_templates() {
        assert_eq!(
            manifest::asset_url("abcdef"),
            "https://resources.download.minecraft.net/ab/abcdef"
        );
        assert_eq!(
            manifest::MINECRAFT_ASSET_SERVER_URL_TEMPLATE
                .replace(manifest::MINECRAFT_ASSET_SERVER_URL_FOLDER_KEY, "ab")
                .replace(manifest::MINECRAFT_ASSET_SERVER_URL_HASH_KEY, "abcdef"),
            manifest::asset_url("abcdef")
        );
        assert_eq!(
            gui::user_icon_url("Steve"),
            "https://minotar.net/armor/bust/Steve/100.png"
        );
        assert_eq!(modpack::friendly_name("Pack", "1.0"), "Pack: 1.0");
        assert_eq!(
            runtime::java_exec_segments_for("windows"),
            &["bin", "java.exe"]
        );
    }
}
