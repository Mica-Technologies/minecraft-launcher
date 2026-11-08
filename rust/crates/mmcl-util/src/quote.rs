// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Shell and desktop-entry quoting helpers from `DesktopShortcutManager`.
//!
//! These are security-relevant: pack names come from remote manifests and end up in
//! `.desktop` `Exec=` lines, shell scripts and Windows shortcut arguments. Ported exactly.

use std::path::{Path, PathBuf};

use mmcl_core::args::ARG_CLIENT_MODE as ARG_CLIENT;

/// Replaces each of `< > : " / \ | ? *` with `_`, then trims as Java's `String.trim` does
/// (every char up to U+0020 at either end). It does **not** strip inner control characters,
/// CR/LF or `..` (the tests pin this; callers that need more do it themselves).
pub fn sanitize_file_name(name: &str) -> String {
    name.chars()
        .map(|c| match c {
            '<' | '>' | ':' | '"' | '/' | '\\' | '|' | '?' | '*' => '_',
            other => other,
        })
        .collect::<String>()
        .trim_matches(|c: char| c <= ' ')
        .to_string()
}

/// POSIX single quoting: `'` + `s` with each `'` as `'\''` + `'`. `None` gives `''`.
pub fn shell_single_quote(s: Option<&str>) -> String {
    match s {
        None => "''".to_string(),
        Some(s) => format!("'{}'", s.replace('\'', "'\\''")),
    }
}

/// `.desktop` `Exec=` quoting: wrapped in `"` with `"`, `` ` ``, `$` and `\` backslash-escaped.
/// `None` gives `""`.
pub fn desktop_exec_quote(s: Option<&str>) -> String {
    let Some(s) = s else {
        return "\"\"".to_string();
    };
    let mut out = String::with_capacity(s.len() + 2);
    out.push('"');
    for c in s.chars() {
        if matches!(c, '"' | '`' | '$' | '\\') {
            out.push('\\');
        }
        out.push(c);
    }
    out.push('"');
    out
}

/// Windows command-line quoting that round-trips through `CommandLineToArgvW`: wrapped in
/// `"`, a run of backslashes before an embedded `"` is doubled and the quote escaped, and a
/// trailing run is doubled. `None` gives `""`.
pub fn windows_cmd_quote(s: Option<&str>) -> String {
    let Some(s) = s else {
        return "\"\"".to_string();
    };
    let mut out = String::with_capacity(s.len() + 2);
    out.push('"');
    let mut backslashes = 0usize;
    for c in s.chars() {
        match c {
            '\\' => backslashes += 1,
            '"' => {
                out.extend(std::iter::repeat_n('\\', backslashes * 2 + 1));
                out.push('"');
                backslashes = 0;
            }
            other => {
                out.extend(std::iter::repeat_n('\\', backslashes));
                backslashes = 0;
                out.push(other);
            }
        }
    }
    out.extend(std::iter::repeat_n('\\', backslashes * 2));
    out.push('"');
    out
}

/// Drops `\r`, `\n` and NUL so a value cannot start a new line in a `.desktop` file or
/// script. `None` gives `""`.
pub fn strip_line_terminators(s: Option<&str>) -> String {
    s.map(|s| {
        s.chars()
            .filter(|c| !matches!(c, '\r' | '\n' | '\0'))
            .collect()
    })
    .unwrap_or_default()
}

/// `true` unless the path looks like a jar or a Java binary (`isNativeExecutable`).
pub fn is_native_executable(launcher_path: &str) -> bool {
    let lower = launcher_path.to_lowercase();
    !(lower.ends_with(".jar")
        || lower.ends_with("java")
        || lower.ends_with("java.exe")
        || lower.ends_with("javaw")
        || lower.ends_with("javaw.exe"))
}

/// The shortcut arguments that open the launcher on `pack_name` (`buildArguments` for the
/// native-executable case, which is the only one the Rust build has): `-c <name>`, with the
/// name in double quotes when it contains a space.
pub fn shortcut_arguments(pack_name: &str) -> String {
    let quoted = if pack_name.contains(' ') {
        format!("\"{pack_name}\"")
    } else {
        pack_name.to_string()
    };
    format!("{ARG_CLIENT} {quoted}")
}

/// Picks a stable executable from `candidates` (`pickBestExecutable`): the first whose
/// extension-less, lowercased file name equals, contains or is contained in
/// `app_name_trimmed` (lowercased), else the case-insensitively first name. `None` when
/// there are no candidates.
pub fn pick_best_executable<'a>(
    candidates: &'a [PathBuf],
    app_name_trimmed: &str,
) -> Option<&'a Path> {
    let app = app_name_trimmed.to_lowercase();
    let name_of = |p: &Path| {
        p.file_name()
            .map(|n| n.to_string_lossy().into_owned())
            .unwrap_or_default()
    };
    let mut best: Option<&Path> = None;
    for f in candidates {
        let name = name_of(f);
        let lower = name.to_lowercase();
        let base = match lower.rfind('.') {
            Some(dot) if dot > 0 => &lower[..dot],
            _ => lower.as_str(),
        };
        if !app.is_empty() && (base == app || base.contains(&app) || app.contains(base)) {
            return Some(f);
        }
        let better = best.is_none_or(|b| lower < name_of(b).to_lowercase());
        if better {
            best = Some(f);
        }
    }
    best
}

#[cfg(test)]
mod tests {
    use super::*;

    // ---- DesktopShortcutManagerQuotingTest ----

    #[test]
    fn null_input_returns_empty_string() {
        assert_eq!(strip_line_terminators(None), "");
    }

    #[test]
    fn plain_text_passes_through_unchanged() {
        assert_eq!(strip_line_terminators(Some("SkyFactory 5")), "SkyFactory 5");
    }

    #[test]
    fn carriage_return_line_feed_and_nul_are_all_removed() {
        assert_eq!(strip_line_terminators(Some("a\rb\nc")), "abc");
        assert_eq!(strip_line_terminators(Some("a\0bc")), "abc");
        assert_eq!(strip_line_terminators(Some("a\r\n\0bc")), "abc");
    }

    #[test]
    fn a_forged_exec_line_injection_attempt_collapses_to_one_line() {
        assert_eq!(
            strip_line_terminators(Some("Innocent Pack\nExec=rm -rf ~")),
            "Innocent PackExec=rm -rf ~"
        );
    }

    #[test]
    fn shell_single_quote_null_becomes_empty_quoted_string() {
        assert_eq!(shell_single_quote(None), "''");
    }

    #[test]
    fn shell_single_quote_wraps_plain_text_in_quotes() {
        assert_eq!(shell_single_quote(Some("SkyFactory")), "'SkyFactory'");
    }

    #[test]
    fn shell_single_quote_escapes_embedded_single_quotes_posix_style() {
        assert_eq!(shell_single_quote(Some("Bob's Pack")), "'Bob'\\''s Pack'");
    }

    #[test]
    fn shell_single_quote_neutralizes_command_injection_attempt() {
        assert_eq!(
            shell_single_quote(Some("pack'; rm -rf ~ #")),
            "'pack'\\''; rm -rf ~ #'"
        );
    }

    #[test]
    fn shell_single_quote_does_not_interpret_dollar_signs_or_backticks() {
        assert_eq!(
            shell_single_quote(Some("$(whoami) && `id`")),
            "'$(whoami) && `id`'"
        );
    }

    #[test]
    fn desktop_exec_quote_null_becomes_empty_quoted_string() {
        assert_eq!(desktop_exec_quote(None), "\"\"");
    }

    #[test]
    fn desktop_exec_quote_wraps_plain_text_in_double_quotes() {
        assert_eq!(desktop_exec_quote(Some("SkyFactory")), "\"SkyFactory\"");
    }

    #[test]
    fn desktop_exec_quote_escapes_the_four_special_characters() {
        assert_eq!(
            desktop_exec_quote(Some("a\"b`c$d\\e")),
            "\"a\\\"b\\`c\\$d\\\\e\""
        );
    }

    #[test]
    fn desktop_exec_quote_neutralizes_command_substitution_attempt() {
        assert_eq!(desktop_exec_quote(Some("$(rm -rf ~)")), "\"\\$(rm -rf ~)\"");
    }

    #[test]
    fn desktop_exec_quote_neutralizes_backtick_injection_attempt() {
        assert_eq!(desktop_exec_quote(Some("`rm -rf ~`")), "\"\\`rm -rf ~\\`\"");
    }

    #[test]
    fn windows_cmd_quote_null_becomes_empty_quoted_string() {
        assert_eq!(windows_cmd_quote(None), "\"\"");
    }

    #[test]
    fn windows_cmd_quote_wraps_plain_text_in_double_quotes() {
        assert_eq!(windows_cmd_quote(Some("SkyFactory")), "\"SkyFactory\"");
    }

    #[test]
    fn windows_cmd_quote_escapes_embedded_double_quotes() {
        assert_eq!(
            windows_cmd_quote(Some("pack\" --launcher-flag \"")),
            "\"pack\\\" --launcher-flag \\\"\""
        );
    }

    #[test]
    fn windows_cmd_quote_doubles_backslashes_only_when_immediately_before_a_quote() {
        assert_eq!(windows_cmd_quote(Some("C:\\path\\")), "\"C:\\path\\\\\"");
        assert_eq!(
            windows_cmd_quote(Some("C:\\path\\to\\file")),
            "\"C:\\path\\to\\file\""
        );
    }

    #[test]
    fn windows_cmd_quote_handles_backslashes_immediately_preceding_an_embedded_quote() {
        assert_eq!(windows_cmd_quote(Some("a\\\"b")), "\"a\\\\\\\"b\"");
    }

    // ---- DesktopShortcutManagerSanitizeTest ----

    #[test]
    fn ordinary_name_passes_through_unchanged() {
        assert_eq!(sanitize_file_name("My Modpack 1.0"), "My Modpack 1.0");
    }

    #[test]
    fn forward_slash_is_replaced_with_underscore() {
        assert_eq!(sanitize_file_name("a/b"), "a_b");
    }

    #[test]
    fn backslash_is_replaced_with_underscore() {
        assert_eq!(sanitize_file_name("a\\b"), "a_b");
    }

    #[test]
    fn all_nine_forbidden_characters_are_replaced() {
        assert_eq!(sanitize_file_name("<>:\"/\\|?*"), "_________");
    }

    #[test]
    fn path_traversal_separators_are_neutralized() {
        assert_eq!(sanitize_file_name("../../etc"), ".._.._etc");
    }

    #[test]
    fn bare_dot_dot_is_not_stripped_because_it_contains_no_forbidden_character() {
        assert_eq!(sanitize_file_name(".."), "..");
    }

    #[test]
    fn control_characters_are_not_stripped() {
        assert_eq!(sanitize_file_name("name\0withNul"), "name\0withNul");
    }

    #[test]
    fn carriage_return_and_line_feed_are_not_stripped() {
        // Java's trim() would drop leading/trailing CR/LF too; inner ones stay.
        assert_eq!(sanitize_file_name("line1\r\nline2"), "line1\r\nline2");
    }

    #[test]
    fn leading_and_trailing_whitespace_is_trimmed() {
        assert_eq!(sanitize_file_name("  trimmed  "), "trimmed");
    }

    #[test]
    fn empty_string_input_produces_empty_string_output() {
        assert_eq!(sanitize_file_name(""), "");
    }

    #[test]
    fn whitespace_only_input_produces_empty_output() {
        assert_eq!(sanitize_file_name("   "), "");
    }

    #[test]
    fn name_that_is_only_forbidden_characters_stays_non_empty() {
        assert_eq!(sanitize_file_name("///"), "___");
    }

    // ---- DesktopShortcutManagerCommandBuildingTest (native-executable case) ----

    #[test]
    fn jar_path_is_not_native() {
        assert!(!is_native_executable("C:\\MicaLauncher\\launcher.jar"));
    }

    #[test]
    fn bare_java_and_javaw_are_not_native() {
        assert!(!is_native_executable("/usr/bin/java"));
        assert!(!is_native_executable("/usr/bin/javaw"));
        assert!(!is_native_executable("C:\\jdk\\bin\\java.exe"));
        assert!(!is_native_executable("C:\\jdk\\bin\\javaw.exe"));
    }

    #[test]
    fn a_real_native_executable_path_is_native() {
        assert!(is_native_executable(
            "C:\\Program Files\\Mica Minecraft Launcher\\MicaLauncher.exe"
        ));
        assert!(is_native_executable(
            "/Applications/Mica Launcher.app/Contents/MacOS/Mica Launcher"
        ));
    }

    #[test]
    fn any_path_ending_in_java_is_misclassified_as_non_native() {
        assert!(!is_native_executable("/opt/CustomJava"));
    }

    #[test]
    fn native_executable_gets_just_client_mode_and_pack_name() {
        assert_eq!(
            shortcut_arguments("SkyFactory"),
            format!("{ARG_CLIENT} SkyFactory")
        );
    }

    #[test]
    fn native_executable_with_spaces_in_pack_name_gets_it_double_quoted() {
        assert_eq!(
            shortcut_arguments("All The Mods 9"),
            format!("{ARG_CLIENT} \"All The Mods 9\"")
        );
    }

    #[test]
    fn empty_candidates_returns_none() {
        assert_eq!(pick_best_executable(&[], "MicaMinecraftLauncher"), None);
    }

    #[test]
    fn exact_application_name_match_is_preferred_regardless_of_order() {
        let app = "MicaMinecraftLauncher";
        let decoy = PathBuf::from("SomeOtherTool.exe");
        let hit = PathBuf::from(format!("{app}.exe"));
        let a = [decoy.clone(), hit.clone()];
        let b = [hit.clone(), decoy];
        assert_eq!(pick_best_executable(&a, app), Some(hit.as_path()));
        assert_eq!(pick_best_executable(&b, app), Some(hit.as_path()));
    }

    #[test]
    fn with_no_name_match_the_alphabetically_first_candidate_wins() {
        let c = [
            PathBuf::from("Zeta-unrelated-9x7q.bin"),
            PathBuf::from("Middle-unrelated-9x7q.bin"),
            PathBuf::from("alpha-unrelated-9x7q.bin"),
        ];
        assert_eq!(
            pick_best_executable(&c, "MicaMinecraftLauncher"),
            Some(Path::new("alpha-unrelated-9x7q.bin"))
        );
    }

    #[test]
    fn single_candidate_is_always_returned_even_without_a_match() {
        let c = [PathBuf::from("totally-unrelated-9x7q.bin")];
        assert_eq!(
            pick_best_executable(&c, "MicaMinecraftLauncher"),
            Some(c[0].as_path())
        );
    }
}
