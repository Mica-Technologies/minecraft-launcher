// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The process-wide lifecycle holder (Java `McpBootstrap`).
//!
//! The app builds one [`McpBootstrap`] over its [`McpHost`] and calls
//! [`start_if_enabled`](McpBootstrap::start_if_enabled) after the installed-pack list is loaded
//! (GUI mode only: the consent prompt needs a window, and headless would fail closed),
//! [`refresh`](McpBootstrap::refresh) when the server or state-change toggles flip, and
//! [`stop`](McpBootstrap::stop) in cleanup. Nothing here ever fails launcher startup.
//!
//! Grants and the activity log outlive server restarts within one run; `stop` revokes the
//! grants.

use std::path::PathBuf;
use std::sync::Arc;

use parking_lot::Mutex;

use crate::activity::ActivityLog;
use crate::approval::{
    ConsentPrompt, GrantStore, LauncherAuthorizer, Policy, PolicyBacking, PolicyStore, RiskClass,
    Settings,
};
use crate::resources::{ResourceRegistry, launcher as launcher_resources};
use crate::server::McpServer;
use crate::session::SessionSnapshot;
use crate::tools::{LauncherActions, LauncherView, ToolRegistry, mutating, read_only};
use crate::{BoxError, logging, now_ms, token};

/// The six config keys the MCP server reads.
pub trait McpConfig: Send + Sync {
    /// `mcpServerEnabled`; an error leaves the feature off.
    fn server_enabled(&self) -> Result<bool, BoxError>;
    /// `mcpAllowStateChanges`.
    fn allow_state_changes(&self) -> bool;
    /// `mcpAutoApproveReadOnly` (read on every call).
    fn auto_approve_read_only(&self) -> bool;
    /// `mcpToolPolicies`, a JSON object as a string.
    fn tool_policies(&self) -> String;
    fn set_tool_policies(&self, json: &str);
    /// `mcpPort`.
    fn port(&self) -> u16;
    /// `mcpToken`.
    fn token(&self) -> String;
    fn set_token(&self, token: &str);
}

/// What the app supplies so this crate can build a server without knowing GPUI or launcher
/// internals.
pub trait McpHost: Send + Sync {
    fn config(&self) -> Arc<dyn McpConfig>;
    /// Where `mcp-endpoint.json` goes (the client config folder, whatever the mode).
    fn endpoint_file(&self) -> PathBuf {
        crate::endpoint::default_path()
    }
    fn launcher_version(&self) -> String;
    fn view(&self) -> Arc<dyn LauncherView>;
    fn actions(&self) -> Arc<dyn LauncherActions>;
    fn consent(&self) -> Arc<dyn ConsentPrompt>;
    /// Other literals that must never leave the process (the single-instance IPC token).
    fn extra_secrets(&self) -> Vec<String> {
        Vec::new()
    }
}

/// One row of the Settings page's tool list.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ToolDescriptor {
    pub name: String,
    /// Localized.
    pub title: String,
    pub risk_class: RiskClass,
}

struct ConfigPolicies(Arc<dyn McpConfig>);

impl PolicyBacking for ConfigPolicies {
    fn read(&self) -> Result<String, BoxError> {
        Ok(self.0.tool_policies())
    }
    fn write(&self, json: &str) -> Result<(), BoxError> {
        self.0.set_tool_policies(json);
        Ok(())
    }
}

struct ConfigSettings {
    config: Arc<dyn McpConfig>,
    policies: Arc<PolicyStore>,
}

impl Settings for ConfigSettings {
    fn server_enabled(&self) -> Result<bool, BoxError> {
        self.config.server_enabled()
    }
    fn auto_approve_read_only(&self) -> Result<bool, BoxError> {
        Ok(self.config.auto_approve_read_only())
    }
    fn policy_for(&self, tool: &str) -> Result<Option<Policy>, BoxError> {
        Ok(self.policies.policy_for(tool))
    }
}

/// The lifecycle holder.
pub struct McpBootstrap {
    host: Arc<dyn McpHost>,
    server: Mutex<Option<McpServer>>,
    grants: Arc<GrantStore>,
    activity: Arc<ActivityLog>,
    policies: Arc<PolicyStore>,
}

impl McpBootstrap {
    pub fn new(host: Arc<dyn McpHost>) -> McpBootstrap {
        let policies = Arc::new(PolicyStore::new(Box::new(ConfigPolicies(host.config()))));
        McpBootstrap {
            host,
            server: Mutex::new(None),
            grants: Arc::new(GrantStore::new()),
            activity: Arc::new(ActivityLog::new()),
            policies,
        }
    }

    /// Starts the server when `mcpServerEnabled` is on and it is not already running.
    pub fn start_if_enabled(&self) {
        let mut guard = self.server.lock();
        if guard.is_some() {
            return;
        }
        let config = self.host.config();
        match config.server_enabled() {
            Ok(true) => {}
            Ok(false) => return,
            Err(_) => {
                logging::warn("log.mcpBootstrap.settingUnreadable", &[]);
                return;
            }
        }
        match self.build_and_start(config) {
            Ok(server) => *guard = Some(server),
            Err(e) => logging::error_with("log.mcpBootstrap.startFailed", &[], &e),
        }
    }

    fn build_and_start(&self, config: Arc<dyn McpConfig>) -> Result<McpServer, BoxError> {
        let view = self.host.view();
        let mut tools = ToolRegistry::new();
        read_only::register_all(&mut tools, view.clone())?;
        if config.allow_state_changes() {
            mutating::register_all(&mut tools, view.clone(), self.host.actions())?;
            logging::std("log.mcpBootstrap.writeToolsEnabled", &[]);
        }
        let mut resources = ResourceRegistry::new();
        launcher_resources::register_all(&mut resources, view)?;
        let settings = Arc::new(ConfigSettings {
            config: config.clone(),
            policies: self.policies.clone(),
        });
        let authorizer = LauncherAuthorizer::new(
            settings,
            self.grants.clone(),
            self.host.consent(),
            Arc::new(now_ms),
        );
        let server = McpServer::new(
            Arc::new(tools),
            Arc::new(resources),
            Arc::new(authorizer),
            self.activity.clone(),
            &self.host.launcher_version(),
        );
        for s in self.host.extra_secrets() {
            server.add_secret(&s);
        }
        let token = self.ensure_token();
        let endpoint = self.host.endpoint_file();
        server.start(config.port(), Some(&endpoint), &token)?;
        Ok(server)
    }

    /// Stops the server (if any) and revokes every session grant. Harmless when never started.
    pub fn stop(&self) {
        let server = self.server.lock().take();
        if let Some(s) = server
            && std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| s.stop())).is_err()
        {
            logging::warn("log.mcpBootstrap.stopUnclean", &[]);
        }
        self.grants.revoke_all();
    }

    /// `stop` then `start_if_enabled` (the tool registry is built at start, so the toggles
    /// need a restart).
    pub fn refresh(&self) {
        self.stop();
        self.start_if_enabled();
    }

    pub fn is_running(&self) -> bool {
        self.server
            .lock()
            .as_ref()
            .is_some_and(McpServer::is_running)
    }

    /// The bound port; 0 when stopped.
    pub fn port(&self) -> u16 {
        self.server.lock().as_ref().map_or(0, McpServer::port)
    }

    /// The connected clients; empty when stopped.
    pub fn sessions(&self) -> Vec<SessionSnapshot> {
        self.server
            .lock()
            .as_ref()
            .map(McpServer::sessions)
            .unwrap_or_default()
    }

    /// Every tool, read-only and state-changing, whatever the config says, so Settings can
    /// show and pre-configure policies while the server is off.
    pub fn describe_tools(&self) -> Vec<ToolDescriptor> {
        let mut tools = ToolRegistry::new();
        let view = self.host.view();
        if read_only::register_all(&mut tools, view.clone()).is_err()
            || mutating::register_all(&mut tools, view, self.host.actions()).is_err()
        {
            logging::warn("log.mcpBootstrap.listToolsFailed", &[]);
        }
        tools
            .all()
            .iter()
            .map(|t| ToolDescriptor {
                name: t.name().to_string(),
                title: t.display_title(),
                risk_class: t.risk_class(),
            })
            .collect()
    }

    /// The persisted token, generating (and storing) one when it is missing or malformed.
    pub fn ensure_token(&self) -> String {
        let config = self.host.config();
        let c2 = config.clone();
        token::ensure::<BoxError, _, _>(
            || Ok(config.token()),
            |t| {
                c2.set_token(t);
                Ok(())
            },
        )
    }

    /// Replaces the token, restarts the server with it, and returns it.
    pub fn regenerate_token(&self) -> String {
        let t = token::generate();
        self.host.config().set_token(&t);
        logging::std("log.mcpBootstrap.tokenRegenerated", &[]);
        self.refresh();
        t
    }

    pub fn activity(&self) -> &ActivityLog {
        &self.activity
    }

    pub fn policies(&self) -> &PolicyStore {
        &self.policies
    }

    pub fn grants(&self) -> &GrantStore {
        &self.grants
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::approval::{Answer, ConsentRequest};
    use crate::tools::test_support::*;

    #[derive(Default)]
    struct Cfg {
        enabled: Mutex<bool>,
        writes: Mutex<bool>,
        policies: Mutex<String>,
        token: Mutex<String>,
        unreadable: bool,
    }

    impl McpConfig for Cfg {
        fn server_enabled(&self) -> Result<bool, BoxError> {
            if self.unreadable {
                return Err(crate::seam_error("bad"));
            }
            Ok(*self.enabled.lock())
        }
        fn allow_state_changes(&self) -> bool {
            *self.writes.lock()
        }
        fn auto_approve_read_only(&self) -> bool {
            true
        }
        fn tool_policies(&self) -> String {
            self.policies.lock().clone()
        }
        fn set_tool_policies(&self, json: &str) {
            *self.policies.lock() = json.into();
        }
        fn port(&self) -> u16 {
            0
        }
        fn token(&self) -> String {
            self.token.lock().clone()
        }
        fn set_token(&self, t: &str) {
            *self.token.lock() = t.into();
        }
    }

    struct NoWindow;
    impl ConsentPrompt for NoWindow {
        fn is_available(&self) -> bool {
            false
        }
        fn ask(&self, _: ConsentRequest) -> Answer {
            Answer::Deny
        }
    }

    struct Host {
        cfg: Arc<Cfg>,
        dir: tempfile::TempDir,
    }

    impl McpHost for Host {
        fn config(&self) -> Arc<dyn McpConfig> {
            self.cfg.clone()
        }
        fn endpoint_file(&self) -> PathBuf {
            self.dir
                .path()
                .join("config")
                .join(crate::endpoint::FILENAME)
        }
        fn launcher_version(&self) -> String {
            "1.0".into()
        }
        fn view(&self) -> Arc<dyn LauncherView> {
            Arc::new(StubView::default())
        }
        fn actions(&self) -> Arc<dyn LauncherActions> {
            Arc::new(StubActions::default())
        }
        fn consent(&self) -> Arc<dyn ConsentPrompt> {
            Arc::new(NoWindow)
        }
    }

    fn boot(cfg: Cfg) -> (McpBootstrap, Arc<Host>) {
        let host = Arc::new(Host {
            cfg: Arc::new(cfg),
            dir: tempfile::tempdir().unwrap(),
        });
        (McpBootstrap::new(host.clone()), host)
    }

    #[test]
    fn disabled_binds_nothing_and_writes_no_token() {
        let (b, h) = boot(Cfg::default());
        b.start_if_enabled();
        assert!(!b.is_running());
        assert_eq!(b.port(), 0);
        assert!(h.cfg.token.lock().is_empty());
        assert!(!h.endpoint_file().exists());
        b.stop();
    }

    #[test]
    fn an_unreadable_setting_leaves_it_off() {
        let (b, _) = boot(Cfg {
            unreadable: true,
            ..Cfg::default()
        });
        b.start_if_enabled();
        assert!(!b.is_running());
    }

    #[test]
    fn enabled_starts_publishes_the_endpoint_and_stops() {
        let cfg = Cfg::default();
        *cfg.enabled.lock() = true;
        let (b, h) = boot(cfg);
        b.start_if_enabled();
        assert!(b.is_running());
        assert!(b.port() > 0);
        let tok = h.cfg.token.lock().clone();
        assert!(token::is_well_formed(&tok));
        let e = crate::endpoint::read_live(&h.endpoint_file()).unwrap();
        assert_eq!(e.port, b.port());
        assert_eq!(e.token, tok);
        b.start_if_enabled();
        b.grants().grant("c", "t", u64::MAX);
        b.stop();
        assert!(!b.is_running());
        assert!(!h.endpoint_file().exists());
        assert!(b.grants().is_empty());
        b.stop();
    }

    #[test]
    fn regenerate_replaces_the_token_and_restarts() {
        let cfg = Cfg::default();
        *cfg.enabled.lock() = true;
        let (b, h) = boot(cfg);
        b.start_if_enabled();
        let old = h.cfg.token.lock().clone();
        let new = b.regenerate_token();
        assert_ne!(old, new);
        assert_eq!(*h.cfg.token.lock(), new);
        assert!(b.is_running());
        assert_eq!(
            crate::endpoint::read(&h.endpoint_file()).unwrap().token,
            new
        );
        b.stop();
    }

    #[test]
    fn describe_tools_lists_all_fifteen_regardless_of_config() {
        let (b, _) = boot(Cfg::default());
        let d = b.describe_tools();
        assert_eq!(d.len(), 15);
        assert_eq!(d[0].name, "list_modpacks");
        assert_eq!(d[14].name, "stop_game");
        assert_eq!(d[12].risk_class, RiskClass::Destructive);
        assert_eq!(d[13].risk_class, RiskClass::Execute);
        assert!(!d[0].title.is_empty());
    }

    #[test]
    fn policies_are_config_backed() {
        let (b, h) = boot(Cfg::default());
        b.policies()
            .set_policy("launch_modpack", Some(Policy::Disabled));
        assert_eq!(*h.cfg.policies.lock(), r#"{"launch_modpack":"DISABLED"}"#);
        assert_eq!(
            b.policies().policy_for("LAUNCH_MODPACK"),
            Some(Policy::Disabled)
        );
    }

    #[test]
    fn ensure_token_keeps_a_good_one() {
        let cfg = Cfg::default();
        *cfg.token.lock() = "c".repeat(64);
        let (b, _) = boot(cfg);
        assert_eq!(b.ensure_token(), "c".repeat(64));
    }
}
