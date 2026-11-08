// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! `SensitiveDataRedactor`: scrubs auth secrets from command lines and log text.
//!
//! [`redact`] removes access and client tokens (and the token part of a legacy
//! `token:<t>:<uuid>` session string, keeping the UUID so the line stays recognisable).
//! [`redact_strict`] additionally removes every UUID, dashed or not, for text leaving the
//! machine's trust boundary (MCP output, shared logs). Word boundaries are ASCII, as in Java.

use std::borrow::Cow;
use std::sync::LazyLock;

use regex::Regex;

/// The replacement text.
pub const REDACTED: &str = "[REDACTED]";

struct Rule {
    re: Regex,
    replacement: &'static str,
}

fn rule(pattern: &str, replacement: &'static str) -> Rule {
    Rule {
        re: Regex::new(pattern).expect("static redaction pattern"),
        replacement,
    }
}

static BASIC: LazyLock<Vec<Rule>> = LazyLock::new(|| {
    vec![
        rule(r"(--accessToken)([=\s]+)\S+", "${1}${2}[REDACTED]"),
        rule(r"(--clientToken)([=\s]+)\S+", "${1}${2}[REDACTED]"),
        rule(
            r"token:[A-Za-z0-9._-]+:([0-9a-fA-F-]{32,36})",
            "token:[REDACTED]:${1}",
        ),
    ]
});

static STRICT_EXTRA: LazyLock<Vec<Rule>> = LazyLock::new(|| {
    vec![
        rule(
            r"(?-u:\b)[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}(?-u:\b)",
            REDACTED,
        ),
        rule(r"(?-u:\b)[0-9a-fA-F]{32}(?-u:\b)", REDACTED),
    ]
});

fn apply<'a>(input: Cow<'a, str>, rules: &[Rule]) -> Cow<'a, str> {
    let mut out = input;
    for r in rules {
        if let Cow::Owned(s) = r.re.replace_all(&out, r.replacement) {
            out = Cow::Owned(s);
        }
    }
    out
}

/// Removes tokens. Text without secrets is returned borrowed and unchanged.
pub fn redact(s: &str) -> Cow<'_, str> {
    if s.is_empty() {
        return Cow::Borrowed(s);
    }
    apply(Cow::Borrowed(s), &BASIC)
}

/// [`redact`], then every dashed or undashed UUID. A 40-char SHA-1 survives (no word
/// boundary after 32 chars); a bare 32-char MD5 does not, which is an accepted false positive.
pub fn redact_strict(s: &str) -> Cow<'_, str> {
    if s.is_empty() {
        return Cow::Borrowed(s);
    }
    apply(redact(s), &STRICT_EXTRA)
}

/// [`redact`] for an optional value (`None` passes through).
pub fn redact_opt(s: Option<&str>) -> Option<Cow<'_, str>> {
    s.map(redact)
}

/// [`redact_strict`] for an optional value (`None` passes through).
pub fn redact_strict_opt(s: Option<&str>) -> Option<Cow<'_, str>> {
    s.map(redact_strict)
}

#[cfg(test)]
mod tests {
    use super::*;

    const FAKE_TOKEN: &str = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ0ZXN0In0.Zm9vYmFyYmF6cXV4";
    const FAKE_UUID_DASHED: &str = "069a79f4-44e9-4726-a5be-fca90e38aaf5";
    const FAKE_UUID_UNDASHED: &str = "069a79f444e94726a5befca90e38aaf5";

    #[test]
    fn redact_passes_null_through() {
        assert_eq!(redact_opt(None), None);
    }

    #[test]
    fn redact_passes_empty_string_through() {
        assert_eq!(redact(""), "");
    }

    #[test]
    fn redact_leaves_ordinary_log_lines_untouched() {
        let line = "[12:04:11] [main/INFO]: Loaded 412 mods in 8.2s";
        assert_eq!(redact(line), line);
        assert!(matches!(redact(line), Cow::Borrowed(_)));
    }

    #[test]
    fn redact_strips_space_separated_access_token() {
        let out = redact(&format!("--accessToken {FAKE_TOKEN} --version 1.20.1")).into_owned();
        assert!(!out.contains(FAKE_TOKEN));
        assert!(out.contains("--accessToken"));
        assert!(out.contains("--version 1.20.1"));
        assert_eq!(out, "--accessToken [REDACTED] --version 1.20.1");
    }

    #[test]
    fn redact_strips_equals_separated_access_token() {
        let out = redact(&format!("--accessToken={FAKE_TOKEN}")).into_owned();
        assert!(!out.contains(FAKE_TOKEN));
        assert!(out.contains("--accessToken"));
    }

    #[test]
    fn redact_strips_client_token_in_both_separator_forms() {
        let spaced = redact(&format!("--clientToken {FAKE_TOKEN}")).into_owned();
        let equals = redact(&format!("--clientToken={FAKE_TOKEN}")).into_owned();
        assert!(!spaced.contains(FAKE_TOKEN));
        assert!(!equals.contains(FAKE_TOKEN));
    }

    #[test]
    fn redact_strips_legacy_session_token_but_keeps_trailing_uuid() {
        let out = redact(&format!(
            "--session token:{FAKE_TOKEN}:{FAKE_UUID_UNDASHED}"
        ))
        .into_owned();
        assert!(!out.contains(FAKE_TOKEN));
        assert!(out.contains(FAKE_UUID_UNDASHED));
    }

    #[test]
    fn redact_strips_every_secret_on_a_line_not_just_the_first() {
        let out = redact(&format!(
            "java --accessToken {FAKE_TOKEN} --clientToken {FAKE_TOKEN} -Xmx8G"
        ))
        .into_owned();
        assert!(!out.contains(FAKE_TOKEN));
        assert!(out.contains("-Xmx8G"));
    }

    #[test]
    fn redact_strict_passes_null_and_empty_through() {
        assert_eq!(redact_strict_opt(None), None);
        assert_eq!(redact_strict(""), "");
    }

    #[test]
    fn redact_strict_strips_dashed_uuid() {
        let out = redact_strict(&format!("--uuid {FAKE_UUID_DASHED}")).into_owned();
        assert!(!out.contains(FAKE_UUID_DASHED));
    }

    #[test]
    fn redact_strict_strips_undashed_uuid() {
        let out = redact_strict(&format!("--uuid {FAKE_UUID_UNDASHED}")).into_owned();
        assert!(!out.contains(FAKE_UUID_UNDASHED));
    }

    #[test]
    fn redact_strict_also_strips_the_legacy_session_tail_that_redact_preserves() {
        let input = format!("--session token:{FAKE_TOKEN}:{FAKE_UUID_UNDASHED}");
        assert!(redact(&input).contains(FAKE_UUID_UNDASHED));
        let strict = redact_strict(&input).into_owned();
        assert!(!strict.contains(FAKE_TOKEN));
        assert!(!strict.contains(FAKE_UUID_UNDASHED));
    }

    #[test]
    fn redact_strict_still_strips_tokens_like_redact_does() {
        let out = redact_strict(&format!("--accessToken={FAKE_TOKEN}")).into_owned();
        assert!(!out.contains(FAKE_TOKEN));
    }

    #[test]
    fn redact_strict_leaves_sha1_hashes_alone() {
        let sha1 = "da39a3ee5e6b4b0d3255bfef95601890afd80709";
        let out = redact_strict(&format!("Verifying artifact {sha1}")).into_owned();
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
        let out = redact_strict(&format!("md5 {md5}")).into_owned();
        assert!(!out.contains(md5));
    }

    #[test]
    fn redact_strict_scrubs_a_realistic_launch_command_line() {
        let cmd = format!(
            "java -Xmx8G -cp forge.jar net.minecraft.client.main.Main --username Steve --uuid {FAKE_UUID_UNDASHED} --accessToken {FAKE_TOKEN} --userType msa --versionType release"
        );
        let out = redact_strict(&cmd).into_owned();
        assert!(!out.contains(FAKE_TOKEN));
        assert!(!out.contains(FAKE_UUID_UNDASHED));
        assert!(out.contains("--username Steve"));
        assert!(out.contains("-Xmx8G"));
        assert!(out.contains("--userType msa"));
    }

    #[test]
    fn word_boundaries_are_ascii_like_java() {
        // An accented letter right before the hex run is not a word char in Java's ASCII
        // semantics, so the UUID is still found.
        let out = redact_strict(&format!("\u{e9}{FAKE_UUID_UNDASHED}")).into_owned();
        assert!(!out.contains(FAKE_UUID_UNDASHED));
    }
}
