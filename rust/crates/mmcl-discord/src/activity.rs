// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The activity Discord shows, invite links and the presence-field sanitiser.

use mmcl_core::identity::URL_SCHEME_PREFIX;
use serde_json::{Value, json};

use crate::DISCORD_SECRET_MAX_BYTES;

/// Activity type "Playing". Discord requires the field; the Java library crashed without it.
pub const ACTIVITY_TYPE_PLAYING: u8 = 0;

/// Party privacy "public", so friends see the Join button.
pub const PARTY_PRIVACY_PUBLIC: u8 = 1;

/// The longest presence field (in characters) taken from a server-supplied value.
pub const PRESENCE_FIELD_MAX_CHARS: usize = 96;

/// The join party re-sent with every presence update while a join secret is staged.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct JoinParty {
    /// The party id (one random UUID per process).
    pub party_id: String,
    /// The join secret: an `mmcl://join?...` link.
    pub secret: String,
    /// Current party size.
    pub size: u32,
    /// Party cap. Discord shows Join only when `max > size`.
    pub max: u32,
}

/// One rich presence activity.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Activity {
    /// The second line ("In Menus", "In Game (Minecraft)").
    pub state: String,
    /// The first line (the screen or "Mod Pack: X").
    pub details: String,
    /// Elapsed-time start, Unix epoch seconds.
    pub start: i64,
    /// Large image asset key.
    pub large_image: String,
    /// Large image hover text.
    pub large_text: String,
    /// Small image asset key.
    pub small_image: String,
    /// Small image hover text.
    pub small_text: String,
    /// The join party, when invites are on for the running pack.
    pub join: Option<JoinParty>,
}

impl Activity {
    /// The activity object of a `SET_ACTIVITY` command.
    pub fn to_json(&self) -> Value {
        let mut v = json!({
            "type": ACTIVITY_TYPE_PLAYING,
            "state": self.state,
            "details": self.details,
            "timestamps": { "start": self.start },
            "assets": {
                "large_image": self.large_image,
                "large_text": self.large_text,
                "small_image": self.small_image,
                "small_text": self.small_text,
            },
        });
        if let Some(join) = &self.join {
            v["party"] = json!({
                "id": join.party_id,
                "size": [join.size, join.max],
                "privacy": PARTY_PRIVACY_PUBLIC,
            });
            v["secrets"] = json!({ "join": join.secret });
        }
        v
    }
}

/// Drops control characters (`< 0x20` and `0x7F`) from the first 96 characters of `value`.
/// A server-supplied pack name with newlines would otherwise render as a multi-line card.
pub fn sanitize_presence_field(value: &str) -> String {
    value
        .chars()
        .take(PRESENCE_FIELD_MAX_CHARS)
        .filter(|&c| c >= '\u{20}' && c != '\u{7F}')
        .collect()
}

fn is_blank(s: &str) -> bool {
    s.chars().all(char::is_whitespace)
}

/// `application/x-www-form-urlencoded`, as Java's `URLEncoder.encode(s, UTF_8)`: keeps
/// `A-Z a-z 0-9 . - * _`, turns a space into `+`, everything else into `%XX`.
pub fn form_encode(s: &str) -> String {
    url::form_urlencoded::byte_serialize(s.as_bytes()).collect()
}

/// `mmcl://join?url=<form-encoded manifest URL>`, or `None` for a missing or blank URL.
pub fn build_invite_link(manifest_url: &str) -> Option<String> {
    if is_blank(manifest_url) {
        return None;
    }
    Some(format!(
        "{URL_SCHEME_PREFIX}join?url={}",
        form_encode(manifest_url)
    ))
}

/// The invite link for a pack. `vanilla_version` is the pack's Minecraft version when it is a
/// vanilla pack (and `None` for modded packs): a non-blank one gives
/// `mmcl://join?vanilla=<version>`; otherwise the manifest-URL form is used.
pub fn build_invite_link_from_pack(
    vanilla_version: Option<&str>,
    manifest_url: Option<&str>,
) -> Option<String> {
    if let Some(version) = vanilla_version.filter(|v| !is_blank(v)) {
        return Some(format!(
            "{URL_SCHEME_PREFIX}join?vanilla={}",
            form_encode(version)
        ));
    }
    manifest_url.and_then(build_invite_link)
}

/// The join secret for a manifest URL: the invite link when it fits Discord's 128-byte limit.
pub fn build_join_secret(manifest_url: &str) -> Option<String> {
    build_invite_link(manifest_url).filter(|s| s.len() <= DISCORD_SECRET_MAX_BYTES)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample(join: Option<JoinParty>) -> Activity {
        Activity {
            state: "In Menus".into(),
            details: "Settings".into(),
            start: 1_700_000_000,
            large_image: "mica_minecraft_launcher".into(),
            large_text: "Mica Minecraft Launcher".into(),
            small_image: "clipboard".into(),
            small_text: "Settings".into(),
            join,
        }
    }

    #[test]
    fn activity_json_always_has_type_and_omits_party_without_join() {
        let v = sample(None).to_json();
        assert_eq!(v["type"], 0);
        assert_eq!(v["state"], "In Menus");
        assert_eq!(v["details"], "Settings");
        assert_eq!(v["timestamps"]["start"], 1_700_000_000i64);
        assert_eq!(v["assets"]["large_image"], "mica_minecraft_launcher");
        assert_eq!(v["assets"]["small_text"], "Settings");
        assert!(v.get("party").is_none());
        assert!(v.get("secrets").is_none());
    }

    #[test]
    fn activity_json_carries_party_and_secret_when_staged() {
        let v = sample(Some(JoinParty {
            party_id: "p".into(),
            secret: "mmcl://join?url=x".into(),
            size: 1,
            max: 4,
        }))
        .to_json();
        assert_eq!(v["party"]["id"], "p");
        assert_eq!(v["party"]["size"], json!([1, 4]));
        assert_eq!(v["party"]["privacy"], 1);
        assert_eq!(v["secrets"]["join"], "mmcl://join?url=x");
    }

    #[test]
    fn sanitize_drops_control_chars_and_caps_length() {
        assert_eq!(sanitize_presence_field("A\nB\r\tC\u{7F}D"), "ABCD");
        assert_eq!(sanitize_presence_field(""), "");
        let long: String = "x".repeat(200);
        assert_eq!(sanitize_presence_field(&long).chars().count(), 96);
        // The cap applies before filtering, like Java.
        let mut s = "\n".repeat(90);
        s.push_str("abcdefghij");
        assert_eq!(sanitize_presence_field(&s), "abcdef");
        assert_eq!(sanitize_presence_field("Pack ✓ Ünïcode"), "Pack ✓ Ünïcode");
    }

    #[test]
    fn form_encode_matches_java_url_encoder() {
        assert_eq!(form_encode("a b"), "a+b");
        assert_eq!(form_encode("A-z0_9.*"), "A-z0_9.*");
        assert_eq!(form_encode("~"), "%7E");
        assert_eq!(
            form_encode("https://h/p?q=1&r=2"),
            "https%3A%2F%2Fh%2Fp%3Fq%3D1%26r%3D2"
        );
        assert_eq!(form_encode("é"), "%C3%A9");
    }

    #[test]
    fn invite_link_from_manifest_url() {
        assert_eq!(
            build_invite_link("https://example.com/p.json").as_deref(),
            Some("mmcl://join?url=https%3A%2F%2Fexample.com%2Fp.json")
        );
        assert_eq!(build_invite_link(""), None);
        assert_eq!(build_invite_link("   "), None);
    }

    #[test]
    fn invite_link_from_pack_prefers_vanilla_version() {
        assert_eq!(
            build_invite_link_from_pack(Some("1.20.1"), Some("https://x/y")).as_deref(),
            Some("mmcl://join?vanilla=1.20.1")
        );
        assert_eq!(
            build_invite_link_from_pack(Some(" "), Some("https://x/y")).as_deref(),
            Some("mmcl://join?url=https%3A%2F%2Fx%2Fy")
        );
        assert_eq!(
            build_invite_link_from_pack(None, Some("https://x/y")).as_deref(),
            Some("mmcl://join?url=https%3A%2F%2Fx%2Fy")
        );
        assert_eq!(build_invite_link_from_pack(None, None), None);
    }

    #[test]
    fn join_secret_respects_the_128_byte_limit() {
        let prefix_len = "mmcl://join?url=".len();
        let fits = "a".repeat(DISCORD_SECRET_MAX_BYTES - prefix_len);
        assert_eq!(
            build_join_secret(&fits).map(|s| s.len()),
            Some(DISCORD_SECRET_MAX_BYTES)
        );
        let too_long = "a".repeat(DISCORD_SECRET_MAX_BYTES - prefix_len + 1);
        assert_eq!(build_join_secret(&too_long), None);
        // Encoding expansion counts: 40 slashes become 120 bytes.
        assert_eq!(build_join_secret(&"/".repeat(40)), None);
        assert_eq!(build_join_secret(""), None);
    }
}
