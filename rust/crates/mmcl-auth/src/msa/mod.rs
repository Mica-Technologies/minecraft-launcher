// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The Microsoft / Xbox / Minecraft protocol (replaces `minecraft_authenticator` 3.0.6).
//!
//! 1. OAuth against `login.live.com`: authorization code (web view), refresh token (renewal),
//!    device code (`oauth20_connect.srf`).
//! 2. Xbox Live user authentication with the Microsoft access token as the RPS ticket (`d=`
//!    prefix for `XboxLive.signin` tokens, `t=` for `service::...::MBI_SSL` tokens from the device
//!    code flow and WAM).
//! 3. XSTS for `rp://api.minecraftservices.com/`.
//! 4. Minecraft `launcher/login`, entitlements, profile; the `xuid` comes from the access token.
//!
//! The Xbox profile-settings call the Java library made after step 4 is dropped: the launcher
//! never used its result, and its failure used to fail the whole sign-in.

mod client;
mod error;

pub use client::{DeviceCode, DevicePoll, MsaClient, OAuthTokens, RenewFailure, xuid_from_jwt};
pub use error::{MsaError, REJECTING_OAUTH_ERRORS};

use std::collections::BTreeMap;
use std::fmt;

use base64::Engine;

use crate::authfile::{
    AuthFile, EXTRA_FLOW, EXTRA_MSA_CLIENT_ID, EXTRA_SCOPE, EXTRA_WAM_ACCOUNT_ID,
};

/// The official Minecraft launcher's MSA client ID (no secret).
pub const OFFICIAL_CLIENT_ID: &str = "00000000402b5328";
/// OAuth authorize endpoint.
pub const AUTHORIZE_URL: &str = "https://login.live.com/oauth20_authorize.srf";
/// OAuth token endpoint.
pub const TOKEN_URL: &str = "https://login.live.com/oauth20_token.srf";
/// The desktop redirect the official client ID allows.
pub const REDIRECT_URL: &str = "https://login.live.com/oauth20_desktop.srf";
/// The device authorization endpoint of `login.live.com`.
pub const DEVICE_CODE_URL: &str = "https://login.live.com/oauth20_connect.srf";
/// The scope of the web view sign-in (the Java launcher's).
pub const SCOPE_XBOX_SIGNIN: &str = "XboxLive.signin offline_access";
/// The scope of device code and WAM tokens (RPS ticket form `t=`).
pub const SCOPE_MBI_SSL: &str = "service::user.auth.xboxlive.com::MBI_SSL";
/// The device code grant type.
pub const DEVICE_CODE_GRANT: &str = "urn:ietf:params:oauth:grant-type:device_code";
/// Xbox Live user authentication.
pub const XBL_URL: &str = "https://user.auth.xboxlive.com/user/authenticate";
/// XSTS authorization.
pub const XSTS_URL: &str = "https://xsts.auth.xboxlive.com/xsts/authorize";
/// Minecraft launcher login.
pub const MC_LOGIN_URL: &str = "https://api.minecraftservices.com/launcher/login";
/// Minecraft entitlements (`?requestId=<launcher client uuid>`).
pub const MC_ENTITLEMENTS_URL: &str = "https://api.minecraftservices.com/entitlements/license";
/// Minecraft profile.
pub const MC_PROFILE_URL: &str = "https://api.minecraftservices.com/minecraft/profile";

/// Which flow produced a Microsoft token.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum SignInKind {
    /// The `--msa-signin` web view helper (authorization code).
    WebView,
    /// The device code at microsoft.com/link.
    DeviceCode,
    /// The Windows account broker.
    Wam,
}

impl SignInKind {
    /// The `mmcl.flow` extra property value.
    pub fn as_str(self) -> &'static str {
        match self {
            SignInKind::WebView => "webView",
            SignInKind::DeviceCode => "deviceCode",
            SignInKind::Wam => "wam",
        }
    }

    /// Parses an `mmcl.flow` value.
    pub fn parse(s: &str) -> Option<SignInKind> {
        match s {
            "webView" => Some(SignInKind::WebView),
            "deviceCode" => Some(SignInKind::DeviceCode),
            "wam" => Some(SignInKind::Wam),
            _ => None,
        }
    }
}

/// The Microsoft OAuth result every sign-in flow produces, and the input of the shared Xbox
/// chain.
#[derive(Clone, PartialEq, Eq)]
pub struct MsaTokens {
    /// The Microsoft access token (the Xbox RPS ticket).
    pub access_token: String,
    /// The refresh token; `None` for WAM, which renews through the broker.
    pub refresh_token: Option<String>,
    /// The client ID the tokens were issued to.
    pub client_id: String,
    /// The scope the tokens were issued for.
    pub scope: String,
    /// Which flow produced them.
    pub kind: SignInKind,
    /// The WAM web account id (WAM only).
    pub wam_account_id: Option<String>,
}

impl fmt::Debug for MsaTokens {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("MsaTokens")
            .field("access_token", &"<redacted>")
            .field(
                "refresh_token",
                &self.refresh_token.as_ref().map(|_| "<redacted>"),
            )
            .field("client_id", &self.client_id)
            .field("scope", &self.scope)
            .field("kind", &self.kind)
            .finish()
    }
}

/// The XBL `RpsTicket` for an access token issued for `scope`: `t=` for the `MBI_SSL` service
/// scope, `d=` otherwise (`XboxLive.signin`).
pub fn rps_ticket(access_token: &str, scope: &str) -> String {
    if scope.contains("MBI_SSL") {
        format!("t={access_token}")
    } else {
        format!("d={access_token}")
    }
}

impl MsaTokens {
    /// The XBL `RpsTicket`.
    pub fn rps_ticket(&self) -> String {
        rps_ticket(&self.access_token, &self.scope)
    }

    /// The authentication file for these tokens under a launcher client id. The web view sign-in
    /// with the official client ID writes no extra properties, exactly like the Java launcher.
    pub fn to_auth_file(&self, launcher_client_id: uuid::Uuid) -> AuthFile {
        let mut extra = BTreeMap::new();
        if self.kind != SignInKind::WebView {
            extra.insert(EXTRA_FLOW.to_string(), self.kind.as_str().to_string());
        }
        if self.scope != SCOPE_XBOX_SIGNIN {
            extra.insert(EXTRA_SCOPE.to_string(), self.scope.clone());
        }
        if !self.client_id.eq_ignore_ascii_case(OFFICIAL_CLIENT_ID) {
            extra.insert(EXTRA_MSA_CLIENT_ID.to_string(), self.client_id.clone());
        }
        if let Some(id) = &self.wam_account_id {
            extra.insert(EXTRA_WAM_ACCOUNT_ID.to_string(), id.clone());
        }
        AuthFile {
            client_id: launcher_client_id,
            refresh_token: self.refresh_token.clone().unwrap_or_default(),
            extra,
        }
    }
}

impl AuthFile {
    /// The OAuth scope of the refresh token (default: the web view scope).
    pub fn scope(&self) -> &str {
        self.extra(EXTRA_SCOPE).unwrap_or(SCOPE_XBOX_SIGNIN)
    }

    /// The Microsoft client ID of the refresh token (default: the official ID).
    pub fn msa_client_id(&self) -> &str {
        self.extra(EXTRA_MSA_CLIENT_ID)
            .unwrap_or(OFFICIAL_CLIENT_ID)
    }

    /// The flow that produced the file (default: web view, which is what Java files are).
    pub fn flow(&self) -> SignInKind {
        self.extra(EXTRA_FLOW)
            .and_then(SignInKind::parse)
            .unwrap_or(SignInKind::WebView)
    }

    /// The WAM web account id.
    pub fn wam_account_id(&self) -> Option<&str> {
        self.extra(EXTRA_WAM_ACCOUNT_ID)
    }
}

/// The user's `clientId`: Base64url **with** padding (Java `getUrlEncoder()`) of the launcher
/// client UUID string.
pub fn encode_client_id(launcher_client_id: &uuid::Uuid) -> String {
    base64::engine::general_purpose::URL_SAFE.encode(launcher_client_id.to_string().as_bytes())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rps_ticket_prefix_follows_the_scope() {
        assert_eq!(rps_ticket("abc", SCOPE_XBOX_SIGNIN), "d=abc");
        assert_eq!(rps_ticket("abc", SCOPE_MBI_SSL), "t=abc");
    }

    #[test]
    fn client_id_is_padded_base64url_of_the_uuid_string() {
        let id = uuid::Uuid::parse_str("12345678-90ab-cdef-1234-567890abcdef").unwrap();
        let enc = encode_client_id(&id);
        assert_eq!(enc.len(), 48);
        assert_eq!(enc, "MTIzNDU2NzgtOTBhYi1jZGVmLTEyMzQtNTY3ODkwYWJjZGVm");
    }

    #[test]
    fn web_view_files_carry_no_extras_and_others_remember_their_scope() {
        let id = uuid::Uuid::nil();
        let web = MsaTokens {
            access_token: "a".into(),
            refresh_token: Some("r".into()),
            client_id: OFFICIAL_CLIENT_ID.into(),
            scope: SCOPE_XBOX_SIGNIN.into(),
            kind: SignInKind::WebView,
            wam_account_id: None,
        };
        let f = web.to_auth_file(id);
        assert!(f.extra.is_empty());
        assert_eq!(f.scope(), SCOPE_XBOX_SIGNIN);
        assert_eq!(f.flow(), SignInKind::WebView);
        let device = MsaTokens {
            scope: SCOPE_MBI_SSL.into(),
            kind: SignInKind::DeviceCode,
            ..web.clone()
        };
        let f = device.to_auth_file(id);
        assert_eq!(f.scope(), SCOPE_MBI_SSL);
        assert_eq!(f.flow(), SignInKind::DeviceCode);
        assert_eq!(f.msa_client_id(), OFFICIAL_CLIENT_ID);
        let wam = MsaTokens {
            refresh_token: None,
            client_id: "my-app".into(),
            kind: SignInKind::Wam,
            wam_account_id: Some("acc".into()),
            ..device
        };
        let f = wam.to_auth_file(id);
        assert_eq!(f.refresh_token, "");
        assert_eq!(f.msa_client_id(), "my-app");
        assert_eq!(f.wam_account_id(), Some("acc"));
        let back = AuthFile::read_compressed(&f.write_compressed().unwrap()).unwrap();
        assert_eq!(back.flow(), SignInKind::Wam);
    }
}
