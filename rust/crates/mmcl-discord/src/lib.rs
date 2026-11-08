// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Discord Rich Presence (Rust port of `DiscordRpcUtility`).
//!
//! The launcher talks to the local Discord desktop client over its IPC channel: the named pipe
//! `\\.\pipe\discord-ipc-N` on Windows (opened overlapped, so a pending read never blocks a
//! presence write), a Unix socket `discord-ipc-N` elsewhere. No token or login is involved.
//!
//! - [`frame`]: the wire format (`u32 LE opcode`, `u32 LE length`, UTF-8 JSON).
//! - [`transport`]: where the pipe or socket lives, and the [`Connector`] seam.
//! - [`client`]: handshake, `READY`, the `ACTIVITY_JOIN` subscription and the
//!   `SET_ACTIVITY` commands.
//! - [`activity`]: the activity JSON, invite links and the field sanitiser.
//! - [`presence`]: the state machine the launcher calls ([`Presence`]).
//!
//! The application wires settings, the running-game check and deep-link handling in through a
//! [`PresenceHost`]; until one is set, presence stays off.

pub mod activity;
pub mod client;
pub mod error;
pub mod frame;
pub mod presence;
pub mod transport;

pub use activity::{
    Activity, JoinParty, build_invite_link, build_invite_link_from_pack, build_join_secret,
    sanitize_presence_field,
};
pub use error::IpcError;
pub use presence::{Presence, PresenceHost, PresenceOptions};
pub use transport::{BoxStream, Connector, IpcStream, SystemConnector};

/// The launcher's Discord application (client) ID.
pub const CLIENT_ID: &str = "841860482029846528";

/// Discord rejects join secrets longer than this many UTF-8 bytes.
pub const DISCORD_SECRET_MAX_BYTES: usize = 128;

/// The image key of the launcher logo in the Discord application's art assets.
pub const IMAGE_LAUNCHER: &str = "mica_minecraft_launcher";

/// The small image shown while browsing the launcher's screens.
pub const IMAGE_MENU: &str = "clipboard";

/// The small image shown while a game runs.
pub const IMAGE_GAME: &str = "game";

#[cfg(test)]
mod tests {
    #[test]
    fn every_key_this_crate_uses_exists() {
        for key in [
            "discordRpc.state.inMenus",
            "discordRpc.state.inGame",
            "discordRpc.details.loading",
            "discordRpc.details.modPack",
            "discordRpc.image.launcher",
            "discordRpc.image.inGame",
            "log.discordRpc.setupFailed",
            "log.discordRpc.updateFailed",
            "log.discordRpc.exitFailed",
            "log.discordRpc.joinReceived",
            "log.discordRpc.joinNotRecognized",
            "log.discordRpc.joinHandleFailed",
            "log.discordRpc.joinSecretTooLong",
            "log.discordRpc.notRunning",
            "log.discordRpc.connected",
            "log.discordRpc.disconnected",
            "log.discordRpc.commandRejected",
        ] {
            assert!(mmcl_i18n::english(key).is_some(), "missing key {key}");
        }
    }
}
