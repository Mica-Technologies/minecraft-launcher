// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Every launcher folder and file (`LocalPathConstants` + `LocalPathManager`).
//!
//! The layout under the root mirrors the Java launcher exactly, so pointing the Rust build at
//! the Java data folder later only means switching [`crate::identity::ACTIVE_IDENTITY`].
//!
//! Roots:
//! - **client**: `MMCL_DATA_DIR` when set and non-empty, else `<home>/<identity data folder>`
//!   (`~/.MicaMinecraftLauncherRS`, dev `~/.MicaMinecraftLauncherRSDEV`). The same on every OS:
//!   not XDG and not `%APPDATA%`, exactly like Java's `user.home`.
//! - **server**: the working directory at startup (snapshot once, see [`snapshot_startup_cwd`]).
//!
//! There is no lazily-resolved global root. A [`DataPaths`] is built from an explicit
//! [`GameMode`], [`current`] returns `None` until the mode is set, and code that runs before the
//! mode is known (single-instance token, MCP endpoint file, startup log) uses the client-anchored
//! associated functions, which never depend on the mode.

use std::ffi::OsString;
use std::path::{Component, Path, PathBuf};
use std::sync::OnceLock;

use crate::identity::AppIdentity;
use crate::mode::{self, GameMode};

/// Environment variable that overrides the client data folder (tests, side-by-side runs).
pub const DATA_DIR_ENV: &str = "MMCL_DATA_DIR";

/// `config`
pub const CONFIG_FOLDER: &str = "config";
/// `metadata`
pub const METADATA_FOLDER: &str = "metadata";
/// `installs` (one folder per pack)
pub const MODPACK_FOLDER: &str = "installs";
/// `logs`
pub const LOG_FOLDER: &str = "logs";
/// `runtime`
pub const RUNTIME_FOLDER: &str = "runtime";
/// `shared`
pub const SHARED_FOLDER: &str = "shared";
/// `configuration.json` (in the config folder)
pub const CONFIG_FILE_NAME: &str = "configuration.json";
/// Suffix of the config file while it is being written.
pub const CONFIG_TMP_SUFFIX: &str = ".tmp";
/// `client.mica` (legacy, in the config folder)
pub const CLIENT_TOKEN_FILE_NAME: &str = "client.mica";
/// `player.mica` (legacy, in the config folder)
pub const AUTH_ACCOUNT_REMEMBERED_FILE_NAME: &str = "player.mica";
/// `update.mica` (in the config folder)
pub const LAUNCHER_UPDATE_INFO_FILE_NAME: &str = "update.mica";
/// Extension of locally cached Mojang manifests.
pub const MINECRAFT_MANIFEST_LOCAL_FILE_EXTENSION: &str = ".manifest";
/// `version.manifest` (in the config folder)
pub const MINECRAFT_VERSION_MANIFEST_FILE_NAME: &str = "version.manifest";
/// `libraries.manifest` (in a pack folder)
pub const MINECRAFT_LIBRARY_MANIFEST_FILE_NAME: &str = "libraries.manifest";
/// `single-instance.token` (always in the *client* config folder)
pub const IPC_TOKEN_FILE_NAME: &str = "single-instance.token";
/// `mcp-endpoint.json` (always in the *client* config folder)
pub const MCP_ENDPOINT_FILE_NAME: &str = "mcp-endpoint.json";
/// `machine-key.bin` (install secret of the machine-bound cipher, in the config folder)
pub const INSTALL_SECRET_FILE_NAME: &str = "machine-key.bin";
/// `profiles` (one encrypted folder per account, in the config folder)
pub const PROFILES_FOLDER: &str = "profiles";
/// `imported-manifests` (manifests of imported packs, in the config folder)
pub const IMPORTED_MANIFESTS_FOLDER: &str = "imported-manifests";
/// `mcp-manifests` (manifests authored through MCP, in the config folder)
pub const MCP_MANIFESTS_FOLDER: &str = "mcp-manifests";
/// `loader-logos` (placeholder logo cache, in the config folder)
pub const LOADER_LOGOS_FOLDER: &str = "loader-logos";
/// `icons` (extracted file-type icons, in the config folder)
pub const ICONS_FOLDER: &str = "icons";
/// `cache` (under the root)
pub const CACHE_FOLDER: &str = "cache";
/// `official-launcher-exports` (under the root)
pub const OFFICIAL_LAUNCHER_EXPORTS_FOLDER: &str = "official-launcher-exports";
/// `install_index.json` (in the installs folder)
pub const INSTALL_INDEX_FILE_NAME: &str = "install_index.json";
/// `manifest_cache` (pack manifest cache keyed by `sha256(url)`, in the installs folder)
pub const MANIFEST_CACHE_FOLDER: &str = "manifest_cache";
/// `.backups` (pack backups, in the installs folder)
pub const BACKUPS_FOLDER: &str = ".backups";
/// `startup.log` (always in the *client* logs folder)
pub const STARTUP_LOG_FILE_NAME: &str = "startup.log";
/// `.log`
pub const LOG_FILE_EXTENSION: &str = ".log";
/// `.jar`
pub const JAR_FILE_EXTENSION: &str = ".jar";
/// `chrono` form of Java's `yyyy-MM-dd--HH-mm-ss` (session and game log names, local time).
pub const LOG_FILE_STAMP_FORMAT: &str = "%Y-%m-%d--%H-%M-%S";
/// `chrono` form of Java's `yyyyMMdd-HHmmss` (quarantined config names).
pub const CORRUPT_STAMP_FORMAT: &str = "%Y%m%d-%H%M%S";

/// Pack-relative asset folders (`LocalPathConstants.MINECRAFT_ASSET_*`).
pub mod pack {
    /// `assets`
    pub const ASSETS: &str = "assets";
    /// `assets/indexes`
    pub const ASSETS_INDEXES: [&str; 2] = ["assets", "indexes"];
    /// `assets/objects`
    pub const ASSETS_OBJECTS: [&str; 2] = ["assets", "objects"];
    /// `assets/virtual` (legacy pre-1.7 flat tree per index id)
    pub const ASSETS_VIRTUAL: [&str; 2] = ["assets", "virtual"];
    /// `resources` (1.6 `map_to_resources`)
    pub const RESOURCES: &str = "resources";
    /// `bin`
    pub const BIN: &str = "bin";
}

static STARTUP_CWD: OnceLock<PathBuf> = OnceLock::new();

/// Records the working directory as the server root. Call it first thing in `main()`; later
/// calls (and the first lazy use) return the same snapshot, so a `chdir` cannot move the root.
pub fn snapshot_startup_cwd() -> &'static Path {
    STARTUP_CWD.get_or_init(|| {
        std::env::current_dir()
            .map(|p| normalize_lexical(&p))
            .unwrap_or_else(|_| PathBuf::from("."))
    })
}

/// Resolves the client data root. Pure over its inputs.
///
/// `override_dir` is the value of [`DATA_DIR_ENV`]: when non-empty it wins, made absolute
/// against `cwd`. Otherwise `<home>/<data_dir_name>`, or `<cwd>/<data_dir_name>` if the home
/// directory is unknown.
pub fn resolve_client_root(
    identity: &AppIdentity,
    override_dir: Option<OsString>,
    home: Option<PathBuf>,
    cwd: &Path,
) -> PathBuf {
    if let Some(dir) = override_dir.filter(|d| !d.is_empty()) {
        let dir = PathBuf::from(dir);
        let absolute = if dir.is_absolute() {
            dir
        } else {
            cwd.join(dir)
        };
        return normalize_lexical(&absolute);
    }
    let base = home.unwrap_or_else(|| cwd.to_path_buf());
    base.join(identity.data_dir_name)
}

/// The client data root of this process (environment and identity applied).
pub fn client_root() -> PathBuf {
    resolve_client_root(
        AppIdentity::current(),
        std::env::var_os(DATA_DIR_ENV),
        dirs::home_dir(),
        snapshot_startup_cwd(),
    )
}

/// The server data root of this process (the startup working directory).
pub fn server_root() -> PathBuf {
    snapshot_startup_cwd().to_path_buf()
}

/// Lexical normalisation: removes `.` and resolves `..` against earlier components, without
/// touching the file system (no symlink resolution, works for missing paths).
pub fn normalize_lexical(path: &Path) -> PathBuf {
    let mut out = PathBuf::new();
    for component in path.components() {
        match component {
            Component::CurDir => {}
            Component::ParentDir => {
                let popped = match out.components().next_back() {
                    Some(Component::Normal(_)) => out.pop(),
                    _ => false,
                };
                if !popped && !matches!(out.components().next_back(), Some(Component::RootDir)) {
                    out.push("..");
                }
            }
            other => out.push(other.as_os_str()),
        }
    }
    if out.as_os_str().is_empty() {
        out.push(".");
    }
    out
}

/// Every launcher path under one root.
#[derive(Clone, Debug, PartialEq, Eq, Hash)]
pub struct DataPaths {
    root: PathBuf,
    mode: GameMode,
}

/// The name other specs use for the same type.
pub type LauncherPaths = DataPaths;

impl DataPaths {
    /// The paths for a mode: the client root for [`GameMode::Client`], the startup working
    /// directory for [`GameMode::Server`].
    pub fn for_mode(mode: GameMode) -> DataPaths {
        match mode {
            GameMode::Client => DataPaths::client(),
            GameMode::Server => DataPaths::server(),
        }
    }

    /// The client paths, whatever the game mode (for code that runs before the mode is set).
    pub fn client() -> DataPaths {
        DataPaths {
            root: client_root(),
            mode: GameMode::Client,
        }
    }

    /// The server paths (root = startup working directory).
    pub fn server() -> DataPaths {
        DataPaths {
            root: server_root(),
            mode: GameMode::Server,
        }
    }

    /// Paths under an explicit root (tests, tools).
    pub fn at_root(root: impl Into<PathBuf>, mode: GameMode) -> DataPaths {
        DataPaths {
            root: root.into(),
            mode,
        }
    }

    /// The data root.
    pub fn root(&self) -> &Path {
        &self.root
    }

    /// The mode these paths were built for.
    pub fn mode(&self) -> GameMode {
        self.mode
    }

    /// `<root>/config` (`getLauncherConfigFolderPath`).
    pub fn config_dir(&self) -> PathBuf {
        self.root.join(CONFIG_FOLDER)
    }

    /// `<root>/config/configuration.json`.
    pub fn config_file(&self) -> PathBuf {
        self.config_dir().join(CONFIG_FILE_NAME)
    }

    /// `<root>/config/configuration.json.tmp` (written, synced, then renamed over the config).
    pub fn config_tmp_file(&self) -> PathBuf {
        self.config_dir()
            .join(format!("{CONFIG_FILE_NAME}{CONFIG_TMP_SUFFIX}"))
    }

    /// `<root>/config/configuration.json.corrupt-yyyyMMdd-HHmmss` (a quarantined config).
    pub fn config_corrupt_file(&self, at: &chrono::NaiveDateTime) -> PathBuf {
        self.config_dir().join(format!(
            "{CONFIG_FILE_NAME}.corrupt-{}",
            at.format(CORRUPT_STAMP_FORMAT)
        ))
    }

    /// `<root>/config/client.mica` (legacy client token).
    pub fn client_token_file(&self) -> PathBuf {
        self.config_dir().join(CLIENT_TOKEN_FILE_NAME)
    }

    /// `<root>/config/player.mica` (legacy remembered account).
    pub fn remembered_account_file(&self) -> PathBuf {
        self.config_dir().join(AUTH_ACCOUNT_REMEMBERED_FILE_NAME)
    }

    /// `<root>/config/update.mica`.
    pub fn update_info_file(&self) -> PathBuf {
        self.config_dir().join(LAUNCHER_UPDATE_INFO_FILE_NAME)
    }

    /// `<root>/config/version.manifest`.
    pub fn mc_version_manifest_file(&self) -> PathBuf {
        self.config_dir().join(MINECRAFT_VERSION_MANIFEST_FILE_NAME)
    }

    /// `<root>/config/machine-key.bin`.
    pub fn machine_key_file(&self) -> PathBuf {
        self.config_dir().join(INSTALL_SECRET_FILE_NAME)
    }

    /// `<root>/config/profiles` (`profiles/<uuid>/` per account).
    pub fn profiles_dir(&self) -> PathBuf {
        self.config_dir().join(PROFILES_FOLDER)
    }

    /// `<root>/config/imported-manifests`.
    pub fn imported_manifests_dir(&self) -> PathBuf {
        self.config_dir().join(IMPORTED_MANIFESTS_FOLDER)
    }

    /// `<root>/config/mcp-manifests`.
    pub fn mcp_manifests_dir(&self) -> PathBuf {
        self.config_dir().join(MCP_MANIFESTS_FOLDER)
    }

    /// `<root>/config/loader-logos`.
    pub fn loader_logos_dir(&self) -> PathBuf {
        self.config_dir().join(LOADER_LOGOS_FOLDER)
    }

    /// `<root>/config/icons`.
    pub fn icons_dir(&self) -> PathBuf {
        self.config_dir().join(ICONS_FOLDER)
    }

    /// `<root>/metadata` (`getLauncherMetadataFolderPath`).
    pub fn metadata_dir(&self) -> PathBuf {
        self.root.join(METADATA_FOLDER)
    }

    /// `<root>/metadata/<sha1>.png` (image cache entry).
    pub fn metadata_image_file(&self, sha1_hex: &str) -> PathBuf {
        self.metadata_dir().join(format!("{sha1_hex}.png"))
    }

    /// `<root>/installs` (`getLauncherModpackFolderPath`).
    pub fn installs_dir(&self) -> PathBuf {
        self.root.join(MODPACK_FOLDER)
    }

    /// `<root>/installs/<sanitized pack name>`.
    pub fn pack_install_dir(&self, sanitized_pack_name: &str) -> PathBuf {
        self.installs_dir().join(sanitized_pack_name)
    }

    /// `<root>/installs/install_index.json`.
    pub fn install_index_file(&self) -> PathBuf {
        self.installs_dir().join(INSTALL_INDEX_FILE_NAME)
    }

    /// `<root>/installs/manifest_cache`.
    pub fn manifest_cache_dir(&self) -> PathBuf {
        self.installs_dir().join(MANIFEST_CACHE_FOLDER)
    }

    /// `<root>/logs` (`getLauncherLogFolderPath`).
    pub fn logs_dir(&self) -> PathBuf {
        self.root.join(LOG_FOLDER)
    }

    /// `<root>/logs/<AppNameTrimmed>_<MODE>_<yyyy-MM-dd--HH-mm-ss>.log`, with this value's mode.
    pub fn session_log_file(&self, now: &chrono::NaiveDateTime) -> PathBuf {
        self.session_log_file_for(self.mode, now)
    }

    /// [`DataPaths::session_log_file`] for an explicit mode.
    pub fn session_log_file_for(&self, mode: GameMode, now: &chrono::NaiveDateTime) -> PathBuf {
        self.logs_dir().join(format!(
            "{}_{}_{}{}",
            AppIdentity::current().app_name_trimmed,
            mode.as_str(),
            now.format(LOG_FILE_STAMP_FORMAT),
            LOG_FILE_EXTENSION
        ))
    }

    /// `<root>/runtime` (`getLauncherRuntimeFolderPath`).
    pub fn runtime_dir(&self) -> PathBuf {
        self.root.join(RUNTIME_FOLDER)
    }

    /// `<root>/runtime/<component>`.
    pub fn runtime_component_dir(&self, component: &str) -> PathBuf {
        self.runtime_dir().join(component)
    }

    /// `<root>/shared`.
    pub fn shared_dir(&self) -> PathBuf {
        self.root.join(SHARED_FOLDER)
    }

    /// `<root>/shared/assets` (`getLauncherSharedAssetsFolderPath`).
    pub fn shared_assets_dir(&self) -> PathBuf {
        self.shared_dir().join(pack::ASSETS)
    }

    /// `<root>/cache` (`CacheManager`).
    pub fn cache_dir(&self) -> PathBuf {
        self.root.join(CACHE_FOLDER)
    }

    /// `<root>/official-launcher-exports`.
    pub fn official_launcher_exports_dir(&self) -> PathBuf {
        self.root.join(OFFICIAL_LAUNCHER_EXPORTS_FOLDER)
    }

    // ---- Client-anchored paths: independent of the game mode. ----

    /// `<client root>/config` (`getClientConfigFolderPath`), whatever the mode.
    pub fn client_config_dir() -> PathBuf {
        DataPaths::client().config_dir()
    }

    /// `<client root>/config/single-instance.token`. Both the running launcher and an
    /// OS-spawned forwarder (started from another working directory) must agree on it.
    pub fn single_instance_token_file() -> PathBuf {
        DataPaths::client_config_dir().join(IPC_TOKEN_FILE_NAME)
    }

    /// `<client root>/config/mcp-endpoint.json`.
    pub fn mcp_endpoint_file() -> PathBuf {
        DataPaths::client_config_dir().join(MCP_ENDPOINT_FILE_NAME)
    }

    /// `<client root>/logs/startup.log`.
    pub fn startup_log_file() -> PathBuf {
        DataPaths::client().logs_dir().join(STARTUP_LOG_FILE_NAME)
    }
}

/// The paths for the current game mode, or `None` while the mode is unset.
pub fn current() -> Option<DataPaths> {
    mode::current().map(DataPaths::for_mode)
}

/// The paths for the current game mode.
///
/// # Panics
///
/// When the game mode has not been set yet. Reading a mode-dependent path that early is the
/// bug class the Java launcher kept shipping; use [`DataPaths::client`] for pre-mode code.
pub fn expect_current() -> DataPaths {
    current().expect(
        "launcher paths requested before the game mode was set; use DataPaths::client() for code that runs before the command line is applied",
    )
}

/// Joins pack-relative segments onto a folder.
pub fn join_segments(base: &Path, segments: &[&str]) -> PathBuf {
    segments.iter().fold(base.to_path_buf(), |p, s| p.join(s))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::identity::{JAVA_RELEASE, RUST_DEV, RUST_RELEASE};

    #[test]
    fn client_root_is_home_anchored() {
        let home = PathBuf::from("/home/u");
        let cwd = PathBuf::from("/srv/x");
        assert_eq!(
            resolve_client_root(&RUST_RELEASE, None, Some(home.clone()), &cwd),
            home.join(".MicaMinecraftLauncherRS")
        );
        assert_eq!(
            resolve_client_root(&RUST_DEV, None, Some(home.clone()), &cwd),
            home.join(".MicaMinecraftLauncherRSDEV")
        );
        assert_eq!(
            resolve_client_root(&JAVA_RELEASE, None, Some(home.clone()), &cwd),
            home.join(".MicaMinecraftLauncher")
        );
        assert_eq!(
            resolve_client_root(&RUST_RELEASE, None, None, &cwd),
            cwd.join(".MicaMinecraftLauncherRS")
        );
    }

    #[test]
    fn override_wins_and_is_made_absolute() {
        let home = PathBuf::from("/home/u");
        let cwd = std::env::temp_dir();
        let abs = cwd.join("data-root");
        assert_eq!(
            resolve_client_root(
                &RUST_RELEASE,
                Some(abs.clone().into_os_string()),
                Some(home.clone()),
                &cwd
            ),
            abs
        );
        assert_eq!(
            resolve_client_root(
                &RUST_RELEASE,
                Some("rel/./dir".into()),
                Some(home.clone()),
                &cwd
            ),
            cwd.join("rel").join("dir")
        );
        assert_eq!(
            resolve_client_root(&RUST_RELEASE, Some("".into()), Some(home.clone()), &cwd),
            home.join(".MicaMinecraftLauncherRS")
        );
    }

    #[test]
    fn lexical_normalisation() {
        let base = std::env::temp_dir();
        assert_eq!(
            normalize_lexical(&base.join("a").join(".").join("b").join("..").join("c")),
            base.join("a").join("c")
        );
        assert_eq!(normalize_lexical(Path::new("a/../..")), PathBuf::from(".."));
        assert_eq!(normalize_lexical(Path::new("./")), PathBuf::from("."));
    }
}
