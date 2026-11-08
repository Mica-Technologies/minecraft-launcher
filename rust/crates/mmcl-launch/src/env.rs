// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! What a launch runs with: [`LaunchEnv`] (paths, host, network, pool, runtimes, config, scan
//! seam), the [`LaunchConfig`] settings seam, the server-mode heap override ([`HeapOverride`])
//! and the account's launch arguments ([`AuthArguments`]).

use std::sync::Arc;

use futures::future::BoxFuture;
use mmcl_auth::User;
use mmcl_core::{DataPaths, GameMode};
use mmcl_manifest::Side;
use mmcl_modpack::{GameModPack, ModpackError, NetHttp, PackHttp, ScanFrequency};
use mmcl_mojang::HostInfo;
use mmcl_net::{CancelToken, DownloadPool};
use mmcl_progress::ProgressSink;
use mmcl_runtime::RuntimeManager;

use crate::command::parse_heap_arg_mb;

/// The settings a launch reads (Java `ConfigManager` / `RuntimeConfig`).
pub trait LaunchConfig: Send + Sync {
    /// `minRAM` in MB (`-Xms`).
    fn min_ram_mb(&self) -> i64;
    /// `maxRAM` in MB (`-Xmx`).
    fn max_ram_mb(&self) -> i64;
    /// The custom JVM args string (already validated by the config layer).
    fn custom_jvm_args(&self) -> String;
    /// `lwjglArmPatch`.
    fn lwjgl_arm_patch_enabled(&self) -> bool;
    /// The per-pack "always verify on launch" opt-out of the fast path.
    fn always_verify_on_launch(&self, manifest_url: &str) -> bool;
    /// The effective pre-launch scan frequency for a pack.
    fn scan_frequency_for(&self, manifest_url: Option<&str>) -> ScanFrequency;
}

impl LaunchConfig for mmcl_config::Config {
    fn min_ram_mb(&self) -> i64 {
        mmcl_config::Config::min_ram_mb(self)
    }
    fn max_ram_mb(&self) -> i64 {
        mmcl_config::Config::max_ram_mb(self)
    }
    fn custom_jvm_args(&self) -> String {
        mmcl_config::Config::custom_jvm_args(self)
    }
    fn lwjgl_arm_patch_enabled(&self) -> bool {
        self.lwjgl_arm_patch()
    }
    fn always_verify_on_launch(&self, manifest_url: &str) -> bool {
        mmcl_config::Config::always_verify_on_launch(self, manifest_url)
    }
    fn scan_frequency_for(&self, manifest_url: Option<&str>) -> ScanFrequency {
        match manifest_url.filter(|u| !u.trim().is_empty()) {
            Some(u) => self.effective_scan_frequency_for_pack(u),
            None => self.default_scan_frequency(),
        }
    }
}

/// A fixed configuration (tests, tools, and callers without a config store).
#[derive(Clone, Debug)]
pub struct StaticConfig {
    pub min_ram_mb: i64,
    pub max_ram_mb: i64,
    pub custom_jvm_args: String,
    pub lwjgl_arm_patch: bool,
    pub always_verify: bool,
    pub scan_frequency: ScanFrequency,
}

impl Default for StaticConfig {
    fn default() -> Self {
        StaticConfig {
            min_ram_mb: 512,
            max_ram_mb: 2048,
            custom_jvm_args: String::new(),
            lwjgl_arm_patch: true,
            always_verify: false,
            scan_frequency: ScanFrequency::Disabled,
        }
    }
}

impl LaunchConfig for StaticConfig {
    fn min_ram_mb(&self) -> i64 {
        self.min_ram_mb
    }
    fn max_ram_mb(&self) -> i64 {
        self.max_ram_mb
    }
    fn custom_jvm_args(&self) -> String {
        self.custom_jvm_args.clone()
    }
    fn lwjgl_arm_patch_enabled(&self) -> bool {
        self.lwjgl_arm_patch
    }
    fn always_verify_on_launch(&self, _manifest_url: &str) -> bool {
        self.always_verify
    }
    fn scan_frequency_for(&self, _manifest_url: Option<&str>) -> ScanFrequency {
        self.scan_frequency
    }
}

/// The pre-launch malware scan (Java `scanModPackRootFolder`).
pub trait PackScanner: Send + Sync {
    /// Scans the pack folder; an error blocks the launch.
    fn scan<'a>(
        &'a self,
        pack: &'a GameModPack,
        sink: Arc<dyn ProgressSink>,
        cancel: CancelToken,
    ) -> BoxFuture<'a, Result<(), ModpackError>>;
}

/// The real scanner: [`mmcl_modpack::scan::scan_pack_root`].
#[derive(Clone, Copy, Debug, Default)]
pub struct DefaultScanner;

impl PackScanner for DefaultScanner {
    fn scan<'a>(
        &'a self,
        pack: &'a GameModPack,
        sink: Arc<dyn ProgressSink>,
        cancel: CancelToken,
    ) -> BoxFuture<'a, Result<(), ModpackError>> {
        Box::pin(mmcl_modpack::scan::scan_pack_root(pack, sink, cancel))
    }
}

/// Server-mode heap sizes from the command line or the environment (Q-4: the Java launcher
/// inherited its own JVM's `-Xms` / `-Xmx`; there is no launcher JVM in Rust).
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct HeapOverride {
    pub xms_mb: Option<u64>,
    pub xmx_mb: Option<u64>,
}

/// The CLI flag for the server's initial heap.
pub const XMS_FLAG: &str = "--xms";
/// The CLI flag for the server's maximum heap.
pub const XMX_FLAG: &str = "--xmx";
/// The environment variable for the server's initial heap.
pub const XMS_ENV: &str = "MMCL_XMS";
/// The environment variable for the server's maximum heap.
pub const XMX_ENV: &str = "MMCL_XMX";

impl HeapOverride {
    /// Reads `--xms` / `--xmx` (`--xms 4g` or `--xms=4g`) from `args`, falling back to
    /// `MMCL_XMS` / `MMCL_XMX` from `env`. Values take the JVM's size forms (`4g`, `4096m`,
    /// `4194304k`, bare bytes) and may carry the `-Xms` / `-Xmx` prefix. Unparseable or zero
    /// values are ignored (the config value is kept).
    pub fn from_args_env(args: &[String], env: impl Fn(&str) -> Option<String>) -> HeapOverride {
        let flag = |name: &str| -> Option<String> {
            let mut it = args.iter();
            while let Some(a) = it.next() {
                if a.eq_ignore_ascii_case(name) {
                    return it.next().cloned();
                }
                if let Some((k, v)) = a.split_once('=')
                    && k.eq_ignore_ascii_case(name)
                {
                    return Some(v.to_string());
                }
            }
            None
        };
        let pick = |flag_name: &str, env_name: &str, prefix: &str| {
            flag(flag_name)
                .and_then(|v| parse_size_mb(&v, prefix))
                .or_else(|| env(env_name).and_then(|v| parse_size_mb(&v, prefix)))
        };
        HeapOverride {
            xms_mb: pick(XMS_FLAG, XMS_ENV, "-Xms"),
            xmx_mb: pick(XMX_FLAG, XMX_ENV, "-Xmx"),
        }
    }

    /// [`HeapOverride::from_args_env`] over this process's arguments and environment.
    pub fn from_process() -> HeapOverride {
        let args: Vec<String> = std::env::args().skip(1).collect();
        HeapOverride::from_args_env(&args, |k| std::env::var(k).ok())
    }
}

fn parse_size_mb(value: &str, prefix: &str) -> Option<u64> {
    let v = value.trim();
    // `get` (not slicing): a non-ASCII value must not panic on a char boundary.
    let arg = if v.get(..4).is_some_and(|p| p.eq_ignore_ascii_case(prefix)) {
        v.to_string()
    } else {
        format!("{prefix}{v}")
    };
    let mb = parse_heap_arg_mb(&arg);
    (mb > 0).then_some(mb)
}

/// Everything a launch runs with. Cheap to clone (all `Arc`s).
#[derive(Clone)]
pub struct LaunchEnv {
    /// The data folder and game mode.
    pub paths: Arc<DataPaths>,
    /// The host the rules and natives are resolved for.
    pub host: HostInfo,
    /// Network access.
    pub http: Arc<dyn PackHttp>,
    /// The shared bounded download pool.
    pub pool: DownloadPool,
    /// Java runtimes.
    pub runtimes: Arc<RuntimeManager>,
    /// Settings.
    pub config: Arc<dyn LaunchConfig>,
    /// The terminal UI is active (stdout and stderr are piped either way).
    pub tui: bool,
    /// Server-mode heap override.
    pub heap_override: HeapOverride,
    /// The pre-launch scanner.
    pub scanner: Arc<dyn PackScanner>,
}

impl std::fmt::Debug for LaunchEnv {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("LaunchEnv")
            .field("root", &self.paths.root())
            .field("mode", &self.paths.mode())
            .field("host", &self.host)
            .finish_non_exhaustive()
    }
}

impl LaunchEnv {
    /// A production environment: real network, the global pool, the real scanner and the
    /// process's heap override. The host is the runtime manager's.
    pub fn new(
        paths: Arc<DataPaths>,
        runtimes: Arc<RuntimeManager>,
        config: Arc<dyn LaunchConfig>,
    ) -> LaunchEnv {
        LaunchEnv {
            host: runtimes.host().clone(),
            paths,
            http: NetHttp::shared(),
            pool: DownloadPool::global().clone(),
            runtimes,
            config,
            tui: false,
            heap_override: HeapOverride::from_process(),
            scanner: Arc::new(DefaultScanner),
        }
    }

    /// Client or server.
    pub fn mode(&self) -> GameMode {
        self.paths.mode()
    }

    /// The manifest side of [`LaunchEnv::mode`].
    pub fn side(&self) -> Side {
        mmcl_modpack::managed::side_of(self.mode())
    }

    /// `"client"` / `"server"` (Forge `data` keys and processor sides).
    pub fn side_name(&self) -> &'static str {
        match self.mode() {
            GameMode::Client => "client",
            GameMode::Server => "server",
        }
    }
}

/// The account a client launch plays as.
#[derive(Clone, PartialEq, Eq)]
pub struct Account {
    pub name: String,
    pub uuid: String,
    pub access_token: String,
    /// `msa` for Microsoft accounts.
    pub user_type: String,
    pub xuid: String,
    pub client_id: String,
}

impl std::fmt::Debug for Account {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Account")
            .field("name", &self.name)
            .field("uuid", &self.uuid)
            .field("access_token", &"<redacted>")
            .finish_non_exhaustive()
    }
}

impl From<&User> for Account {
    fn from(u: &User) -> Self {
        Account {
            name: u.name.clone(),
            uuid: u.uuid.clone(),
            access_token: u.access_token.clone().unwrap_or_default(),
            user_type: u
                .user_type
                .clone()
                .filter(|t| !t.trim().is_empty())
                .unwrap_or_else(|| mmcl_auth::user::USER_TYPE_MSA.to_string()),
            xuid: u.xuid.clone().unwrap_or_default(),
            client_id: u.client_id.clone().unwrap_or_default(),
        }
    }
}

/// The auth placeholders of one launch (all empty in server mode).
#[derive(Clone, Default, PartialEq, Eq)]
pub struct AuthArguments {
    pub player_name: String,
    pub uuid: String,
    pub access_token: String,
    /// `token:<token>:<uuid>`.
    pub session: String,
    pub user_type: String,
    pub xuid: String,
    pub client_id: String,
}

impl std::fmt::Debug for AuthArguments {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("AuthArguments")
            .field("player_name", &self.player_name)
            .field("uuid", &self.uuid)
            .finish_non_exhaustive()
    }
}

impl AuthArguments {
    /// Server mode: everything empty.
    pub fn none() -> AuthArguments {
        AuthArguments::default()
    }

    /// The arguments of `account`.
    pub fn of(account: &Account) -> AuthArguments {
        AuthArguments {
            player_name: account.name.clone(),
            uuid: account.uuid.clone(),
            access_token: account.access_token.clone(),
            session: format!("token:{}:{}", account.access_token, account.uuid),
            user_type: if account.user_type.trim().is_empty() {
                mmcl_auth::user::USER_TYPE_MSA.to_string()
            } else {
                account.user_type.clone()
            },
            xuid: account.xuid.clone(),
            client_id: account.client_id.clone(),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn args(v: &[&str]) -> Vec<String> {
        v.iter().map(|s| s.to_string()).collect()
    }

    #[test]
    fn heap_override_from_flags_and_env() {
        let none = |_: &str| None;
        let h = HeapOverride::from_args_env(&args(&["-s", "--xms", "4g", "--xmx=8192m"]), none);
        assert_eq!(h.xms_mb, Some(4096));
        assert_eq!(h.xmx_mb, Some(8192));
        let h = HeapOverride::from_args_env(&args(&["--XMX", "-Xmx2G"]), none);
        assert_eq!(h.xmx_mb, Some(2048));
        let env = |k: &str| match k {
            XMS_ENV => Some("1024m".to_string()),
            XMX_ENV => Some("garbage".to_string()),
            _ => None,
        };
        let h = HeapOverride::from_args_env(&args(&[]), env);
        assert_eq!(
            h,
            HeapOverride {
                xms_mb: Some(1024),
                xmx_mb: None
            }
        );
        // A flag wins over the environment.
        let h = HeapOverride::from_args_env(&args(&["--xms", "2g"]), env);
        assert_eq!(h.xms_mb, Some(2048));
        // Non-ASCII values neither panic nor parse.
        assert_eq!(
            HeapOverride::from_args_env(&args(&["--xmx", "abc€"]), none).xmx_mb,
            None
        );
        assert_eq!(
            HeapOverride::from_args_env(&args(&["--xmx", "€"]), none).xmx_mb,
            None
        );
        // Zero is ignored.
        assert_eq!(
            HeapOverride::from_args_env(&args(&["--xms", "0"]), none).xms_mb,
            None
        );
    }

    #[test]
    fn auth_arguments_from_account() {
        let u = User {
            uuid: "u".repeat(32),
            name: "Steve".into(),
            access_token: Some("tok".into()),
            user_type: None,
            xuid: Some("123".into()),
            client_id: None,
        };
        let a = AuthArguments::of(&Account::from(&u));
        assert_eq!(a.session, format!("token:tok:{}", "u".repeat(32)));
        assert_eq!(a.user_type, "msa");
        assert_eq!(a.xuid, "123");
        assert!(!format!("{a:?}").contains("tok:"));
        assert_eq!(AuthArguments::none().session, "");
    }
}
