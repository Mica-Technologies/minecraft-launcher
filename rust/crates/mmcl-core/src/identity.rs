// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Which launcher this build *is* to the operating system.
//!
//! While the Rust port lives beside the Java launcher it is a separate product: its own display
//! name, data folder, ports, installer UpgradeCodes, AppUserModelID, bundle id and Linux package
//! name, and it does not register `mmcl://` or `.mmcjson` (Java keeps those). Every one of those
//! values lives in [`AppIdentity`], and [`ACTIVE_IDENTITY`] picks the set. Switching the Rust
//! build over to the Java identity later (so its installer upgrades the Java install in place
//! and it reads the Java data folder) is a change to that one constant.

use crate::build::Flavor;

/// The two identity sets this code base knows.
#[derive(Copy, Clone, Debug, PartialEq, Eq, Hash)]
pub enum IdentityKind {
    /// The Rust build installed side by side with the Java launcher (the current state).
    RustSideBySide,
    /// The Java launcher's identity, for when the Rust build replaces it.
    Java,
}

/// **The single switch.** Change this to [`IdentityKind::Java`] at switch-over.
pub const ACTIVE_IDENTITY: IdentityKind = IdentityKind::RustSideBySide;

/// Every OS-visible name, id and port of one launcher flavour.
#[derive(Copy, Clone, Debug, PartialEq, Eq)]
pub struct AppIdentity {
    pub kind: IdentityKind,
    pub flavor: Flavor,
    /// Human-readable product name for windows, menus, Add/Remove Programs and dialogs.
    pub display_name: &'static str,
    /// The Java-compatible application name (`LAUNCHER_APPLICATION_NAME`).
    pub app_name: &'static str,
    /// `app_name` without spaces (`LAUNCHER_APPLICATION_NAME_TRIMMED`); prefixes session log files.
    pub app_name_trimmed: &'static str,
    /// Name of the client data folder under the user's home directory (leading dot included).
    pub data_dir_name: &'static str,
    /// Loopback port of the single-instance lock.
    pub single_instance_port: u16,
    /// Default loopback port of the MCP server.
    pub mcp_port: u16,
    /// Windows AppUserModelID set at startup (taskbar grouping, jump list, toasts).
    pub win_aumid: &'static str,
    /// macOS `CFBundleIdentifier`.
    pub mac_bundle_id: &'static str,
    /// Linux deb/rpm package name.
    pub linux_package_name: &'static str,
    /// Linux `.desktop` file id.
    pub linux_desktop_id: &'static str,
    /// Windows MSI UpgradeCode (both install scopes share it).
    pub win_upgrade_code: &'static str,
    /// Windows Burn bundle UpgradeCode.
    pub win_bundle_upgrade_code: &'static str,
    /// Windows ProgID for `.mmcjson`, when this identity registers the file type.
    pub win_modpack_progid: Option<&'static str>,
    /// Whether this build registers the `mmcl://` URL scheme with the OS.
    pub registers_url_scheme: bool,
    /// Whether this build registers the `.mmcjson` file type with the OS.
    pub registers_modpack_file_type: bool,
    /// Prefix of this product's GitHub release tags (`rs-` for the Rust build).
    pub release_tag_prefix: &'static str,
    /// Whether this product's releases are published as GitHub pre-releases (which the
    /// `/releases/latest` endpoint skips).
    pub releases_are_prereleases: bool,
    /// Product token of the HTTP User-Agent (`<token>/<version>`). Generic: no personal data.
    pub user_agent_product: &'static str,
}

impl AppIdentity {
    /// The identity for a kind and flavour.
    pub const fn of(kind: IdentityKind, flavor: Flavor) -> AppIdentity {
        match (kind, flavor) {
            (IdentityKind::RustSideBySide, Flavor::Release) => RUST_RELEASE,
            (IdentityKind::RustSideBySide, Flavor::Dev) => RUST_DEV,
            (IdentityKind::Java, Flavor::Release) => JAVA_RELEASE,
            (IdentityKind::Java, Flavor::Dev) => JAVA_DEV,
        }
    }

    /// The identity of the running build: [`ACTIVE_IDENTITY`] in this build's flavour.
    pub fn current() -> &'static AppIdentity {
        match (ACTIVE_IDENTITY, crate::build::flavor()) {
            (IdentityKind::RustSideBySide, Flavor::Release) => &RUST_RELEASE,
            (IdentityKind::RustSideBySide, Flavor::Dev) => &RUST_DEV,
            (IdentityKind::Java, Flavor::Release) => &JAVA_RELEASE,
            (IdentityKind::Java, Flavor::Dev) => &JAVA_DEV,
        }
    }

    /// `true` for the dev flavour.
    pub const fn is_dev(&self) -> bool {
        matches!(self.flavor, Flavor::Dev)
    }

    /// The HTTP User-Agent for a version string: `MicaMinecraftLauncher/<version>`, with `dev`
    /// standing in for an unstamped version.
    pub fn user_agent(&self, version: &str) -> String {
        let version = if version.trim().is_empty() || crate::build::is_unstamped(version) {
            "dev"
        } else {
            version.trim()
        };
        format!("{}/{}", self.user_agent_product, version)
    }
}

/// The URL scheme of launcher deep links. Shared by every identity: the Rust build recognises
/// `mmcl://` arguments even though it does not register the scheme while side by side.
pub const URL_SCHEME: &str = "mmcl";
/// `URL_SCHEME` plus `://`, matched case-insensitively.
pub const URL_SCHEME_PREFIX: &str = "mmcl://";
/// Windows description of the URL protocol.
pub const WIN_URL_DESCRIPTION: &str = "URL:Mica Minecraft Launcher Protocol";
/// Modpack file extension without the dot.
pub const MODPACK_EXTENSION: &str = "mmcjson";
/// MIME type of modpack files.
pub const MODPACK_MIME: &str = "application/x-mmcjson";
/// Publisher shown in installers and Add/Remove Programs.
pub const VENDOR: &str = "Mica Technologies";
/// One-line product description used by installers.
pub const DESCRIPTION: &str =
    "Mica Minecraft Launcher \u{2014} a cross-platform Minecraft Forge modpack launcher.";

/// Rust build, release flavour, side by side with Java.
pub const RUST_RELEASE: AppIdentity = AppIdentity {
    kind: IdentityKind::RustSideBySide,
    flavor: Flavor::Release,
    display_name: "Mica Minecraft Launcher (Rust)",
    app_name: "Mica Minecraft Launcher",
    app_name_trimmed: "MicaMinecraftLauncher",
    data_dir_name: ".MicaMinecraftLauncherRS",
    single_instance_port: 47831,
    mcp_port: 47833,
    win_aumid: "com.micatechnologies.minecraft.launcher.rs",
    mac_bundle_id: "com.micatechnologies.minecraft.launcher.rs",
    linux_package_name: "mica-minecraft-launcher-rs",
    linux_desktop_id: "mica-minecraft-launcher-rs.desktop",
    win_upgrade_code: "71cbbacc-9bc5-4759-a454-1cb7c7ff2296",
    win_bundle_upgrade_code: "4667401f-350d-42ef-ba29-974794f9c279",
    win_modpack_progid: None,
    registers_url_scheme: false,
    registers_modpack_file_type: false,
    release_tag_prefix: "rs-",
    releases_are_prereleases: true,
    user_agent_product: "MicaMinecraftLauncher",
};

/// Rust build, dev flavour, side by side with Java.
pub const RUST_DEV: AppIdentity = AppIdentity {
    kind: IdentityKind::RustSideBySide,
    flavor: Flavor::Dev,
    display_name: "Mica Minecraft Launcher (Rust) DEV",
    app_name: "Mica Minecraft Launcher DEV",
    app_name_trimmed: "MicaMinecraftLauncherDEV",
    data_dir_name: ".MicaMinecraftLauncherRSDEV",
    single_instance_port: 47832,
    mcp_port: 47834,
    win_aumid: "com.micatechnologies.minecraft.launcher.rsDEV",
    mac_bundle_id: "com.micatechnologies.minecraft.launcher.rs.dev",
    linux_package_name: "mica-minecraft-launcher-rs-dev",
    linux_desktop_id: "mica-minecraft-launcher-rs-dev.desktop",
    win_upgrade_code: "f23786db-dbfd-414d-8e15-a690df0d4f9c",
    win_bundle_upgrade_code: "4854aa25-3b2f-462c-bf7c-4bc145b891ac",
    win_modpack_progid: None,
    registers_url_scheme: false,
    registers_modpack_file_type: false,
    release_tag_prefix: "rs-",
    releases_are_prereleases: true,
    user_agent_product: "MicaMinecraftLauncher",
};

/// The Java launcher's release identity (the switch-over target).
pub const JAVA_RELEASE: AppIdentity = AppIdentity {
    kind: IdentityKind::Java,
    flavor: Flavor::Release,
    display_name: "Mica Minecraft Launcher",
    app_name: "Mica Minecraft Launcher",
    app_name_trimmed: "MicaMinecraftLauncher",
    data_dir_name: ".MicaMinecraftLauncher",
    single_instance_port: 47821,
    mcp_port: 47823,
    win_aumid: "com.micatechnologies.minecraft.launcher.LauncherCore",
    mac_bundle_id: "com.micatechnologies.minecraft.launcher.LauncherCore",
    linux_package_name: "mica-minecraft-launcher",
    linux_desktop_id: "mica-minecraft-launcher.desktop",
    win_upgrade_code: "3699f9a3-0d10-4228-ab2f-b86ca9a500ac",
    win_bundle_upgrade_code: "b3a4f7c2-1d9e-4f88-9b06-3a2c1c7e9501",
    win_modpack_progid: Some("MicaMinecraftLauncher.Modpack"),
    registers_url_scheme: true,
    registers_modpack_file_type: true,
    release_tag_prefix: "",
    releases_are_prereleases: false,
    user_agent_product: "MicaMinecraftLauncher",
};

/// The Java launcher's dev identity. Java shares one MSI/bundle UpgradeCode between flavours (a
/// known wart); the values are kept as Java has them, and dev builds never register the scheme.
pub const JAVA_DEV: AppIdentity = AppIdentity {
    kind: IdentityKind::Java,
    flavor: Flavor::Dev,
    display_name: "Mica Minecraft Launcher DEV",
    app_name: "Mica Minecraft Launcher DEV",
    app_name_trimmed: "MicaMinecraftLauncherDEV",
    data_dir_name: ".MicaMinecraftLauncherDEV",
    single_instance_port: 47822,
    mcp_port: 47824,
    win_aumid: "com.micatechnologies.minecraft.launcher.LauncherCoreDEV",
    mac_bundle_id: "com.micatechnologies.minecraft.launcher.LauncherCore",
    linux_package_name: "mica-minecraft-launcher-dev",
    linux_desktop_id: "mica-minecraft-launcher-dev.desktop",
    win_upgrade_code: "3699f9a3-0d10-4228-ab2f-b86ca9a500ac",
    win_bundle_upgrade_code: "b3a4f7c2-1d9e-4f88-9b06-3a2c1c7e9501",
    win_modpack_progid: Some("MicaMinecraftLauncher.ModpackDEV"),
    registers_url_scheme: false,
    registers_modpack_file_type: false,
    release_tag_prefix: "",
    releases_are_prereleases: false,
    user_agent_product: "MicaMinecraftLauncher",
};

#[cfg(test)]
mod tests {
    use super::*;

    const ALL: [AppIdentity; 4] = [RUST_RELEASE, RUST_DEV, JAVA_RELEASE, JAVA_DEV];

    #[test]
    fn of_returns_the_matching_set() {
        for id in ALL {
            assert_eq!(AppIdentity::of(id.kind, id.flavor), id);
        }
    }

    #[test]
    fn rust_identity_never_collides_with_java() {
        for rust in [RUST_RELEASE, RUST_DEV] {
            for java in [JAVA_RELEASE, JAVA_DEV] {
                assert_ne!(rust.data_dir_name, java.data_dir_name);
                assert_ne!(rust.single_instance_port, java.single_instance_port);
                assert_ne!(rust.mcp_port, java.mcp_port);
                assert_ne!(rust.single_instance_port, java.mcp_port);
                assert_ne!(rust.mcp_port, java.single_instance_port);
                assert_ne!(rust.win_aumid, java.win_aumid);
                assert_ne!(rust.mac_bundle_id, java.mac_bundle_id);
                assert_ne!(rust.linux_package_name, java.linux_package_name);
                assert_ne!(rust.win_upgrade_code, java.win_upgrade_code);
                assert_ne!(rust.win_bundle_upgrade_code, java.win_bundle_upgrade_code);
            }
            assert!(!rust.registers_url_scheme);
            assert!(!rust.registers_modpack_file_type);
            assert!(rust.win_modpack_progid.is_none());
            assert_eq!(rust.release_tag_prefix, "rs-");
        }
    }

    #[test]
    fn flavours_differ_within_an_identity() {
        for (a, b) in [(RUST_RELEASE, RUST_DEV), (JAVA_RELEASE, JAVA_DEV)] {
            assert_ne!(a.data_dir_name, b.data_dir_name);
            assert_ne!(a.single_instance_port, b.single_instance_port);
            assert_ne!(a.mcp_port, b.mcp_port);
            assert_ne!(a.win_aumid, b.win_aumid);
        }
        // The Rust build fixes Java's shared-UpgradeCode wart.
        assert_ne!(RUST_RELEASE.win_upgrade_code, RUST_DEV.win_upgrade_code);
    }

    #[test]
    fn java_values_match_the_java_launcher() {
        assert_eq!(JAVA_RELEASE.data_dir_name, ".MicaMinecraftLauncher");
        assert_eq!(JAVA_DEV.data_dir_name, ".MicaMinecraftLauncherDEV");
        assert_eq!(JAVA_RELEASE.single_instance_port, 47821);
        assert_eq!(JAVA_DEV.single_instance_port, 47822);
        assert_eq!(JAVA_RELEASE.mcp_port, 47823);
        assert_eq!(JAVA_DEV.mcp_port, 47824);
        assert_eq!(
            JAVA_DEV.win_aumid,
            format!("{}DEV", JAVA_RELEASE.win_aumid).as_str()
        );
        for id in ALL {
            assert_eq!(id.app_name_trimmed, id.app_name.replace(' ', ""));
            assert_eq!(
                id.linux_desktop_id,
                format!("{}.desktop", id.linux_package_name)
            );
        }
    }

    #[test]
    fn data_folder_names_follow_the_trimmed_name() {
        assert_eq!(
            JAVA_RELEASE.data_dir_name,
            format!(".{}", JAVA_RELEASE.app_name_trimmed)
        );
        assert_eq!(
            JAVA_DEV.data_dir_name,
            format!(".{}", JAVA_DEV.app_name_trimmed)
        );
    }

    #[test]
    fn active_identity_is_the_side_by_side_one() {
        assert_eq!(ACTIVE_IDENTITY, IdentityKind::RustSideBySide);
        assert_eq!(AppIdentity::current().kind, ACTIVE_IDENTITY);
        assert_eq!(AppIdentity::current().flavor, crate::build::flavor());
    }

    #[test]
    fn user_agent_is_generic() {
        assert_eq!(
            RUST_RELEASE.user_agent("2026.05.12"),
            "MicaMinecraftLauncher/2026.05.12"
        );
        assert_eq!(RUST_RELEASE.user_agent(""), "MicaMinecraftLauncher/dev");
        assert_eq!(
            RUST_RELEASE.user_agent("0.0.0-no-git-tag"),
            "MicaMinecraftLauncher/dev"
        );
    }
}
