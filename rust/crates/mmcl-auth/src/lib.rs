// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Microsoft account sign-in for the Mica Minecraft Launcher (Rust port of `game/auth/` and of
//! the parts of `minecraft_authenticator` 3.0.6 the Java launcher uses).
//!
//! - [`msa`]: the protocol, implemented directly: OAuth (code, refresh, device code) against
//!   `login.live.com`, Xbox Live, XSTS, Minecraft launcher login, entitlements and profile, and
//!   the [`AuthFile`] (`player.mica` content) format. The Xbox profile-settings call the Java
//!   library made (and never used) is dropped.
//! - [`signin`]: the [`SignInFlow`] trait and its implementations, which only produce a Microsoft
//!   OAuth result ([`MsaTokens`]): [`WebViewHelperFlow`] (re-executes the launcher as the
//!   `--msa-signin` helper), [`DeviceCodeFlow`] (a code entered at microsoft.com/link; the
//!   default on handhelds) and [`WamFlow`] (the Windows account broker, behind `auth.wam` and a
//!   client ID of our own). The Xbox -> XSTS -> Minecraft chain after it is shared.
//! - [`store`]: [`AccountStore`], the per-account encrypted folders, byte-compatible with Java.
//! - [`accounts`]: [`AccountManager`], several accounts at once, one default, single-flight
//!   refreshes, legacy single-account migration.
//! - [`resolve`]: which account a launch uses.
//! - [`service`]: [`AuthService`], the launcher-wide facade (rate limit, timeouts, status
//!   callback, sign-in, launch-time refresh, background renewal).
//!
//! Tokens are never logged: errors from the protocol layer are logged by kind only.

pub mod accounts;
pub mod authfile;
pub mod classify;
pub mod http;
pub mod limiter;
pub mod msa;
pub mod resolve;
pub mod service;
pub mod signin;
pub mod spawn;
pub mod status;
pub mod store;
pub mod user;

#[cfg(test)]
mod testutil;

pub use accounts::{
    AccountInfo, AccountManager, DefaultAccountSetting, ListenerId, RefreshFuture, Renewal,
    RenewalError, Renewer, Status,
};
pub use authfile::AuthFile;
pub use classify::{AuthOutcome, classify, is_renewal_due};
pub use http::{HttpRequest, HttpResponse, HttpTransport, ReqwestTransport, TransportError};
pub use limiter::RateLimiter;
pub use msa::{MsaClient, MsaError};
pub use resolve::{Blocked, Problem, Resolution, resolve};
pub use service::{AuthService, AuthTimeouts, ConfigDefaultAccount};
pub use signin::{
    DeviceCodeFlow, DeviceCodePrompt, MsaTokens, SignInError, SignInFlow, SignInKind, WamFlow,
    WebViewHelperFlow,
};
pub use spawn::{InlineSpawner, SerialSpawner, Spawn};
pub use status::StatusHub;
pub use store::{AccountStore, Entry};
pub use user::User;
