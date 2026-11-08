// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The signed-in Minecraft user (`minecraft_authenticator.login.User`).

use std::fmt;

use serde::{Deserialize, Serialize};

/// A signed-in Minecraft user. The game-launch subsystem builds `--uuid`, `--username`,
/// `--accessToken`, `--userType`, `--xuid` and `--clientId` from it.
///
/// Serialized as the `cached_user.json` JSON object: members `uuid`, `name`, `accessToken`,
/// `type`, `xuid`, `clientId` in that order, a `None` member left out (Gson without
/// `serializeNulls`).
#[derive(Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct User {
    /// The Minecraft profile id, normally 32 hex digits without dashes.
    pub uuid: String,
    /// The Minecraft player name.
    pub name: String,
    /// The Minecraft services access token (a JWT).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub access_token: Option<String>,
    /// Always `"msa"` for Microsoft accounts.
    #[serde(rename = "type", default, skip_serializing_if = "Option::is_none")]
    pub user_type: Option<String>,
    /// The Xbox user id from the access token.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub xuid: Option<String>,
    /// Base64url of the launcher client UUID string.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub client_id: Option<String>,
}

/// The user type of every Microsoft account.
pub const USER_TYPE_MSA: &str = "msa";

impl fmt::Debug for User {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("User")
            .field("uuid", &self.uuid)
            .field("name", &self.name)
            .field(
                "access_token",
                &self.access_token.as_ref().map(|_| "<redacted>"),
            )
            .field("user_type", &self.user_type)
            .field("xuid", &self.xuid)
            .field("client_id", &self.client_id)
            .finish()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn steve() -> User {
        User {
            uuid: "a".repeat(32),
            name: "Steve".into(),
            access_token: Some("secret".into()),
            user_type: Some("msa".into()),
            xuid: Some("123".into()),
            client_id: None,
        }
    }

    #[test]
    fn serializes_in_java_member_order_and_omits_none() {
        let json = serde_json::to_string(&steve()).unwrap();
        assert_eq!(
            json,
            format!(
                "{{\"uuid\":\"{}\",\"name\":\"Steve\",\"accessToken\":\"secret\",\"type\":\"msa\",\"xuid\":\"123\"}}",
                "a".repeat(32)
            )
        );
    }

    #[test]
    fn debug_never_shows_the_token() {
        let s = format!("{:?}", steve());
        assert!(!s.contains("secret"), "{s}");
        assert!(s.contains("redacted"));
    }
}
