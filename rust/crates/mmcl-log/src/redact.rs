// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Scrubs credentials out of text before it reaches a log (Java `SensitiveDataRedactor`).
//!
//! [`redact`] runs on every launcher log line. It removes the values of `--accessToken` and
//! `--clientToken` (space or `=` separated) and the token in a legacy `token:<token>:<uuid>`
//! session string, keeping the trailing UUID on purpose so the line stays recognisable.
//!
//! [`redact_strict`] is for text that leaves the machine or reaches another process (MCP output,
//! shared crash reports): it does everything [`redact`] does and also removes dashed UUIDs and
//! bare 32-hex runs. MD5 hashes are an accepted false positive; SHA-1s (40 hex) survive because
//! the pattern is word-boundary anchored.

use std::borrow::Cow;
use std::sync::LazyLock;

use regex::Regex;

/// What a removed secret is replaced with.
pub const REDACTED: &str = "[REDACTED]";

static ACCESS_TOKEN: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"(--accessToken)([=\s]+)\S+").expect("access token pattern"));

static CLIENT_TOKEN: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"(--clientToken)([=\s]+)\S+").expect("client token pattern"));

static LEGACY_SESSION: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"token:[A-Za-z0-9._-]+:([0-9a-fA-F-]{32,36})").expect("legacy session pattern")
});

static UUID_DASHED: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b")
        .expect("dashed uuid pattern")
});

static UUID_UNDASHED: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"\b[0-9a-fA-F]{32}\b").expect("undashed uuid pattern"));

/// Applies `re` to `input`, keeping the borrow when nothing matched.
fn replace<'a>(input: Cow<'a, str>, re: &Regex, with: &str) -> Cow<'a, str> {
    match input {
        Cow::Borrowed(s) => re.replace_all(s, with),
        Cow::Owned(s) => match re.replace_all(&s, with) {
            Cow::Borrowed(_) => Cow::Owned(s),
            Cow::Owned(changed) => Cow::Owned(changed),
        },
    }
}

/// Removes launch tokens from `input`. Text without secrets comes back borrowed.
pub fn redact(input: &str) -> Cow<'_, str> {
    if input.is_empty() {
        return Cow::Borrowed(input);
    }
    let out = replace(Cow::Borrowed(input), &ACCESS_TOKEN, "${1}${2}[REDACTED]");
    let out = replace(out, &CLIENT_TOKEN, "${1}${2}[REDACTED]");
    replace(out, &LEGACY_SESSION, "token:[REDACTED]:${1}")
}

/// [`redact`] plus every dashed UUID and bare 32-hex run.
pub fn redact_strict(input: &str) -> Cow<'_, str> {
    if input.is_empty() {
        return Cow::Borrowed(input);
    }
    let out = redact(input);
    let out = replace(out, &UUID_DASHED, REDACTED);
    replace(out, &UUID_UNDASHED, REDACTED)
}

#[cfg(test)]
mod tests {
    //! Port of `SensitiveDataRedactorTest`.
    use super::*;

    fn r(s: String) -> String {
        redact(&s).into_owned()
    }

    fn rs(s: String) -> String {
        redact_strict(&s).into_owned()
    }

    const FAKE_TOKEN: &str = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ0ZXN0In0.Zm9vYmFyYmF6cXV4";
    const FAKE_UUID_DASHED: &str = "069a79f4-44e9-4726-a5be-fca90e38aaf5";
    const FAKE_UUID_UNDASHED: &str = "069a79f444e94726a5befca90e38aaf5";

    #[test]
    fn redact_passes_empty_string_through() {
        assert_eq!(redact(""), "");
        assert_eq!(redact_strict(""), "");
    }

    #[test]
    fn redact_leaves_ordinary_log_lines_untouched_and_borrowed() {
        let line = "[12:04:11] [main/INFO]: Loaded 412 mods in 8.2s";
        let out = redact(line);
        assert_eq!(out, line);
        assert!(matches!(out, Cow::Borrowed(_)));
    }

    #[test]
    fn redact_strips_space_separated_access_token() {
        let out = r(format!("--accessToken {FAKE_TOKEN} --version 1.20.1"));
        assert!(!out.contains(FAKE_TOKEN));
        assert!(out.contains("--accessToken"));
        assert!(out.contains("--version 1.20.1"));
        assert_eq!(out, "--accessToken [REDACTED] --version 1.20.1");
    }

    #[test]
    fn redact_strips_equals_separated_access_token() {
        let out = r(format!("--accessToken={FAKE_TOKEN}"));
        assert!(!out.contains(FAKE_TOKEN));
        assert_eq!(out, "--accessToken=[REDACTED]");
    }

    #[test]
    fn redact_strips_client_token_in_both_separator_forms() {
        let spaced = r(format!("--clientToken {FAKE_TOKEN}"));
        let equals = r(format!("--clientToken={FAKE_TOKEN}"));
        assert!(!spaced.contains(FAKE_TOKEN));
        assert!(!equals.contains(FAKE_TOKEN));
    }

    #[test]
    fn redact_strips_legacy_session_token_but_keeps_trailing_uuid() {
        let out = r(format!("--session token:{FAKE_TOKEN}:{FAKE_UUID_UNDASHED}"));
        assert!(!out.contains(FAKE_TOKEN));
        assert!(out.contains(FAKE_UUID_UNDASHED));
        assert_eq!(
            out,
            format!("--session token:[REDACTED]:{FAKE_UUID_UNDASHED}")
        );
    }

    #[test]
    fn redact_strips_every_secret_on_a_line_not_just_the_first() {
        let out = r(format!(
            "java --accessToken {FAKE_TOKEN} --clientToken {FAKE_TOKEN} -Xmx8G"
        ));
        assert!(!out.contains(FAKE_TOKEN));
        assert!(out.contains("-Xmx8G"));
    }

    #[test]
    fn redact_strict_strips_dashed_uuid() {
        let out = rs(format!("--uuid {FAKE_UUID_DASHED}"));
        assert!(!out.contains(FAKE_UUID_DASHED));
    }

    #[test]
    fn redact_strict_strips_undashed_uuid() {
        let out = rs(format!("--uuid {FAKE_UUID_UNDASHED}"));
        assert!(!out.contains(FAKE_UUID_UNDASHED));
    }

    #[test]
    fn redact_strict_also_strips_the_legacy_session_tail_that_redact_preserves() {
        let input = format!("--session token:{FAKE_TOKEN}:{FAKE_UUID_UNDASHED}");
        assert!(redact(&input).contains(FAKE_UUID_UNDASHED));
        let strict = redact_strict(&input);
        assert!(!strict.contains(FAKE_TOKEN));
        assert!(!strict.contains(FAKE_UUID_UNDASHED));
    }

    #[test]
    fn redact_strict_still_strips_tokens_like_redact_does() {
        let out = rs(format!("--accessToken={FAKE_TOKEN}"));
        assert!(!out.contains(FAKE_TOKEN));
    }

    #[test]
    fn redact_strict_leaves_sha1_hashes_alone() {
        let sha1 = "da39a3ee5e6b4b0d3255bfef95601890afd80709";
        let out = rs(format!("Verifying artifact {sha1}"));
        assert!(out.contains(sha1));
    }

    #[test]
    fn redact_strict_does_not_touch_short_hex_runs() {
        let line = "exit code 0x1F, chunk a3f9c2";
        assert_eq!(redact_strict(line), line);
    }

    #[test]
    fn redact_strict_leaves_ordinary_log_lines_untouched() {
        let line = "[12:04:11] [main/INFO]: Loaded 412 mods in 8.2s";
        assert_eq!(redact_strict(line), line);
    }

    #[test]
    fn redact_strict_redacts_bare_md5_as_an_accepted_false_positive() {
        let md5 = "d41d8cd98f00b204e9800998ecf8427e";
        let out = rs(format!("md5 {md5}"));
        assert!(!out.contains(md5));
    }

    #[test]
    fn redact_strict_scrubs_a_realistic_launch_command_line() {
        let cmd = format!(
            "java -Xmx8G -cp forge.jar net.minecraft.client.main.Main --username Steve --uuid \
             {FAKE_UUID_UNDASHED} --accessToken {FAKE_TOKEN} --userType msa --versionType release"
        );
        let out = redact_strict(&cmd);
        assert!(!out.contains(FAKE_TOKEN));
        assert!(!out.contains(FAKE_UUID_UNDASHED));
        assert!(out.contains("--username Steve"));
        assert!(out.contains("-Xmx8G"));
        assert!(out.contains("--userType msa"));
    }
}
