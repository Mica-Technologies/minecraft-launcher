// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The launcher's Model Context Protocol server (Java `mcp/`).
//!
//! Off by default. When enabled the launcher serves JSON-RPC 2.0 over MCP's Streamable HTTP
//! transport (POST only, no SSE) on `127.0.0.1` only, behind a persisted 256-bit bearer token,
//! an Origin check and body-size / media-type checks ([`transport::guard`]). Clients handshake
//! with `initialize`, get an `Mcp-Session-Id`, then list and call [`tools`] (7 read-only, 8
//! state-changing behind a second opt-in) and read three [`resources`] under `mica://`.
//!
//! Every tool call and resource read passes: the content gate (`validate_before_approval`) →
//! the approval engine ([`approval::resolve`]) → an optional consent prompt
//! ([`approval::ConsentPrompt`], implemented by the GUI) → invocation on one serial dispatch
//! thread → strict redaction of every outbound text block → the in-memory [`activity`] log.
//!
//! - [`bootstrap::McpBootstrap`]: the process-wide lifecycle holder the app drives.
//! - [`server::McpServer`]: sessions, serial dispatch, the loopback listener, the endpoint file.
//! - [`relay::run`]: the `--mcp` stdio ↔ HTTP relay for clients that can only spawn processes.
//! - [`client_setup`]: the Settings page's "Connecting a client" snippets.
//! - [`live`]: the engine-backed [`tools::LauncherView`], [`tools::LauncherActions`],
//!   [`bootstrap::McpConfig`] and [`bootstrap::McpHost`].
//!
//! The port is the identity's MCP port ([`mmcl_core::AppIdentity::mcp_port`]): 47833 release,
//! 47834 dev for the side-by-side Rust build, a pair separate from the single-instance ports.

pub mod activity;
pub mod approval;
pub mod bootstrap;
pub mod client_setup;
pub mod endpoint;
pub mod handler;
pub mod live;
mod logging;
pub mod protocol;
pub mod relay;
pub mod resources;
pub mod server;
pub mod session;
pub mod token;
pub mod tools;
pub mod transport;

pub use activity::ActivityLog;
pub use bootstrap::{McpBootstrap, McpConfig, McpHost, ToolDescriptor};
pub use server::{McpServer, Response};

/// A failure from a seam the app implements (settings reads, tool bodies, resource reads).
pub type BoxError = Box<dyn std::error::Error + Send + Sync + 'static>;

/// How the server runs (Java `McpServerMode`). Only [`Off`](Self::Off) and
/// [`WithLauncher`](Self::WithLauncher) are built; the tray/background mode is planned.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum McpServerMode {
    /// Nothing binds.
    Off,
    /// The server lives as long as the launcher window does.
    WithLauncher,
    /// Planned: keep serving from the tray after the window closes.
    TrayBackground,
}

/// Errors of this crate's own operations.
#[derive(Debug, thiserror::Error)]
pub enum McpError {
    /// A registration was refused (bad name, duplicate, wrong scheme).
    #[error("{0}")]
    InvalidArgument(String),
    /// The operation does not fit the current state (already running, duplicate name).
    #[error("{0}")]
    IllegalState(String),
    /// A socket or file operation failed.
    #[error(transparent)]
    Io(#[from] std::io::Error),
}

/// Milliseconds since the Unix epoch.
pub fn now_ms() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

/// A simple message-carrying error for seams.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SeamError(pub String);

impl std::fmt::Display for SeamError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.0)
    }
}

impl std::error::Error for SeamError {}

/// Wraps a message as a [`BoxError`].
pub fn seam_error(msg: impl Into<String>) -> BoxError {
    Box::new(SeamError(msg.into()))
}

#[cfg(test)]
mod tests {
    /// Every localization key this crate looks up exists in the English bundle.
    #[test]
    fn every_key_the_crate_uses_exists() {
        for key in [
            "dialog.button.cancel",
            "dialog.mcp.consent.body",
            "dialog.mcp.consent.button.allowOnce",
            "dialog.mcp.consent.button.allowSession",
            "dialog.mcp.consent.header",
            "dialog.mcp.consent.noArguments",
            "dialog.mcp.consent.title",
            "log.mcpActions.createFailed",
            "log.mcpActions.editManifestFailed",
            "log.mcpActions.forkFailed",
            "log.mcpActions.installFailed",
            "log.mcpActions.launchFailed",
            "log.mcpActions.stopGameFailed",
            "log.mcpActions.uninstallFailed",
            "log.mcpAuthorizer.allowedAnswer",
            "log.mcpAuthorizer.denied",
            "log.mcpAuthorizer.deniedAnswer",
            "log.mcpAuthorizer.resolveFailed",
            "log.mcpBootstrap.listToolsFailed",
            "log.mcpBootstrap.settingUnreadable",
            "log.mcpBootstrap.startFailed",
            "log.mcpBootstrap.stopUnclean",
            "log.mcpBootstrap.tokenRegenerated",
            "log.mcpBootstrap.writeToolsEnabled",
            "log.mcpConsent.detailFailed",
            "log.mcpConsent.promptFailed",
            "log.mcpConsent.promptTimedOut",
            "log.mcpEndpointFile.removeFailed",
            "log.mcpRequestHandler.clientInitialized",
            "log.mcpRequestHandler.preApprovalFailed",
            "log.mcpRequestHandler.refusedBeforeApproval",
            "log.mcpRequestHandler.resourceApprovalFailed",
            "log.mcpRequestHandler.resourceReadFailed",
            "log.mcpRequestHandler.toolApprovalFailed",
            "log.mcpRequestHandler.toolFailed",
            "log.mcpServer.dispatchFailed",
            "log.mcpServer.listening",
            "log.mcpServer.portUnavailable",
            "log.mcpServer.stopped",
            "log.mcpTransport.serveFailed",
            "log.mcpView.footprintFailed",
            "log.mcpView.manifestLoadFailed",
        ] {
            assert!(mmcl_i18n::english(key).is_some(), "missing {key}");
        }
    }
}
