// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! `ProcessUtilities`: starting the game and helper processes.
//!
//! - [`launch`] starts a child from an argv with one of three stdio modes, strips secrets
//!   from its environment (never from the launcher's own) and logs the redacted command.
//! - [`split_command_line`] is the legacy string-to-argv splitter.
//! - [`execute_string_command`] is the legacy blocking variant with a retry prompt.

use std::ffi::OsStr;
use std::io::{self, BufRead, BufReader, Read, Write};
use std::path::Path;
use std::process::{Child, Command, ExitStatus, Stdio};
use std::sync::atomic::{AtomicU8, Ordering};
use std::thread;

use mmcl_i18n::tf;

use crate::redact::redact;

/// What happens to a child's standard streams.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ChildIoMode {
    /// stdout and stderr are piped. **The caller must drain both**, or the child blocks once
    /// a pipe buffer fills ("JVM in Task Manager but no window").
    Pipe,
    /// stdout and stderr go to the null device.
    Discard,
    /// stdin, stdout and stderr are all inherited. stdin matters for headless server mode,
    /// where systemd's `StandardInput=socket` feeds console commands into the launcher.
    Inherit,
}

/// Substrings that mark an environment variable name as sensitive (checked uppercased).
pub const SENSITIVE_ENV_NAME_PARTS: &[&str] = &[
    "TOKEN",
    "SECRET",
    "PASSWORD",
    "PASSWD",
    "PASSPHRASE",
    "API_KEY",
    "APIKEY",
    "PRIVATE_KEY",
    "ACCESS_KEY",
    "CREDENTIAL",
    "COOKIE",
    "SESSION_KEY",
    "AUTH_KEY",
];

/// Prefixes that mark an environment variable name as sensitive (checked uppercased).
pub const SENSITIVE_ENV_NAME_PREFIXES: &[&str] = &[
    "AWS_",
    "AZURE_",
    "GCP_",
    "GCLOUD_",
    "GOOGLE_APPLICATION_",
    "GITHUB_TOKEN",
    "GH_TOKEN",
    "NPM_TOKEN",
    "PYPI_TOKEN",
    "OPENAI_",
    "ANTHROPIC_",
    "STRIPE_",
    "TWILIO_",
    "SENDGRID_",
];

/// `true` when a child (Minecraft, Forge, mods: unsandboxed code) should not inherit the
/// variable. Deliberately conservative: `TOKENIZER_MODE` is stripped too.
pub fn is_sensitive_env_name(name: &str) -> bool {
    let upper = name.to_ascii_uppercase();
    SENSITIVE_ENV_NAME_PARTS.iter().any(|p| upper.contains(p))
        || SENSITIVE_ENV_NAME_PREFIXES
            .iter()
            .any(|p| upper.starts_with(p))
}

/// Removes sensitive names from an environment map (the testable form of the filter).
pub fn strip_sensitive_env<V>(env: &mut std::collections::HashMap<String, V>) {
    env.retain(|k, _| !is_sensitive_env_name(k));
}

/// Removes every sensitive variable of the launcher's environment from `cmd`'s environment.
pub fn strip_sensitive_env_from(cmd: &mut Command) {
    for (name, _) in std::env::vars_os() {
        if let Some(n) = name.to_str()
            && is_sensitive_env_name(n)
        {
            cmd.env_remove(&name);
        }
    }
}

// ---- console-window handling on Windows ---------------------------------------------------

const HIDE_AUTO: u8 = 0;
const HIDE_YES: u8 = 1;
const HIDE_NO: u8 = 2;
static HIDE_CHILD_CONSOLES: AtomicU8 = AtomicU8::new(HIDE_AUTO);

/// Overrides whether children are started with `CREATE_NO_WINDOW` (Windows only). The GUI
/// sets `true` so helpers such as `powershell` do not flash a console; server/console mode
/// sets `false` so [`ChildIoMode::Inherit`] shares the console. Without a call, the launcher
/// decides by whether it has a console of its own.
pub fn set_hide_child_consoles(hide: bool) {
    HIDE_CHILD_CONSOLES.store(if hide { HIDE_YES } else { HIDE_NO }, Ordering::Relaxed);
}

/// Whether children get `CREATE_NO_WINDOW`.
pub fn hide_child_consoles() -> bool {
    match HIDE_CHILD_CONSOLES.load(Ordering::Relaxed) {
        HIDE_YES => true,
        HIDE_NO => false,
        _ => !has_own_console(),
    }
}

#[cfg(windows)]
fn has_own_console() -> bool {
    // SAFETY: GetConsoleWindow takes no arguments and only reads process state.
    !unsafe { windows_sys::Win32::System::Console::GetConsoleWindow() }.is_null()
}

#[cfg(not(windows))]
fn has_own_console() -> bool {
    true
}

#[cfg(windows)]
fn apply_platform_flags(cmd: &mut Command) {
    use std::os::windows::process::CommandExt;
    const CREATE_NO_WINDOW: u32 = 0x0800_0000;
    if hide_child_consoles() {
        cmd.creation_flags(CREATE_NO_WINDOW);
    }
}

#[cfg(not(windows))]
fn apply_platform_flags(_cmd: &mut Command) {}

// ---- launching --------------------------------------------------------------------------

/// Builds (but does not start) the command [`launch`] would run.
pub fn build_command<S: AsRef<OsStr>>(
    argv: &[S],
    cwd: &Path,
    mode: ChildIoMode,
) -> io::Result<Command> {
    let Some((program, args)) = argv.split_first() else {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "empty command line",
        ));
    };
    let mut cmd = Command::new(program);
    cmd.args(args).current_dir(cwd);
    match mode {
        ChildIoMode::Pipe => {
            cmd.stdout(Stdio::piped()).stderr(Stdio::piped());
        }
        ChildIoMode::Discard => {
            cmd.stdout(Stdio::null()).stderr(Stdio::null());
        }
        ChildIoMode::Inherit => {
            cmd.stdin(Stdio::inherit())
                .stdout(Stdio::inherit())
                .stderr(Stdio::inherit());
        }
    }
    strip_sensitive_env_from(&mut cmd);
    apply_platform_flags(&mut cmd);
    Ok(cmd)
}

/// Starts `argv` in `cwd` (`launchCommand(List, String, ChildIoMode)`). Logs the command
/// with tokens redacted; the spawn itself uses the real argv.
pub fn launch<S: AsRef<OsStr>>(argv: &[S], cwd: &Path, mode: ChildIoMode) -> io::Result<Child> {
    let mut cmd = build_command(argv, cwd, mode)?;
    let joined = argv
        .iter()
        .map(|a| a.as_ref().to_string_lossy().into_owned())
        .collect::<Vec<_>>()
        .join(" ");
    tracing::info!(
        "{}",
        tf!("log.processUtil.launchingCommand", redact(&joined))
    );
    cmd.spawn()
}

/// [`launch`] for a single command string, split with [`split_command_line`]. Prefer argv.
pub fn launch_str(command: &str, cwd: &Path, mode: ChildIoMode) -> io::Result<Child> {
    launch(&split_command_line(command), cwd, mode)
}

/// Java `Character.isWhitespace`: ASCII whitespace and the information separators
/// U+001C..U+001F, plus Unicode space/line/paragraph separators except the no-break spaces.
pub fn is_java_whitespace(c: char) -> bool {
    match c {
        '\t' | '\n' | '\u{b}' | '\u{c}' | '\r' | '\u{1c}'..='\u{1f}' | ' ' => true,
        '\u{a0}' | '\u{2007}' | '\u{202f}' => false,
        '\u{1680}'
        | '\u{2000}'..='\u{2006}'
        | '\u{2008}'..='\u{200a}'
        | '\u{2028}'
        | '\u{2029}'
        | '\u{205f}'
        | '\u{3000}' => true,
        _ => false,
    }
}

/// Splits a command line into arguments. A `"` toggles quoting and starts a token (so `""`
/// gives an empty argument); unquoted whitespace ends a started token; backslashes are
/// literal (Windows paths such as `"C:\dir\"`); adjacent quoted parts join; an unclosed
/// trailing quote still emits the last token.
pub fn split_command_line(s: &str) -> Vec<String> {
    let mut out = Vec::new();
    let mut cur = String::new();
    let mut in_quotes = false;
    let mut started = false;
    for c in s.chars() {
        if c == '"' {
            in_quotes = !in_quotes;
            started = true;
        } else if is_java_whitespace(c) && !in_quotes {
            if started {
                out.push(std::mem::take(&mut cur));
                started = false;
            }
        } else {
            cur.push(c);
            started = true;
        }
    }
    if started {
        out.push(cur);
    }
    out
}

/// Copies `from` to `to` line by line until EOF (the `StreamGobbler`).
fn tee<R: Read + Send + 'static, W: Write + Send + 'static>(
    from: R,
    mut to: W,
) -> thread::JoinHandle<()> {
    thread::spawn(move || {
        let mut reader = BufReader::new(from);
        let mut line = Vec::new();
        loop {
            line.clear();
            match reader.read_until(b'\n', &mut line) {
                Ok(0) | Err(_) => break,
                Ok(_) => {
                    let _ = to.write_all(&line);
                    let _ = to.flush();
                }
            }
        }
    })
}

/// Runs `command` to completion (`executeStringCommand`), the legacy blocking path.
///
/// With `enhanced_logging`, stdout and stderr are piped and teed to the launcher's own
/// streams by two reader threads; without it they are inherited. (Inheriting while also
/// reading made the readers see an immediate EOF, so it is one or the other.) A non-zero
/// exit calls `ask_retry` (the "game crashed, reload?" prompt, see
/// [`crash_prompt_texts`]); `true` runs the command again.
pub fn execute_string_command(
    command: &str,
    cwd: &Path,
    enhanced_logging: bool,
    ask_retry: &mut dyn FnMut() -> bool,
) -> io::Result<()> {
    let mut retry_number = 0u32;
    loop {
        let argv = split_command_line(command);
        let mode = if enhanced_logging {
            ChildIoMode::Pipe
        } else {
            ChildIoMode::Inherit
        };
        let mut cmd = build_command(&argv, cwd, mode)?;
        let redacted = redact(command);
        if retry_number > 0 {
            tracing::info!(
                "{}",
                tf!(
                    "log.processUtil.executingCommandRetry",
                    retry_number.to_string(),
                    &redacted
                )
            );
        } else {
            tracing::info!("{}", tf!("log.processUtil.executingCommand", &redacted));
        }
        let mut child = cmd.spawn()?;
        let gobblers = if enhanced_logging {
            let out = child.stdout.take().map(|s| tee(s, io::stdout()));
            let err = child.stderr.take().map(|s| tee(s, io::stderr()));
            vec![out, err]
        } else {
            Vec::new()
        };
        let status = child.wait()?;
        for g in gobblers.into_iter().flatten() {
            let _ = g.join();
        }
        if status.success() || !ask_retry() {
            return Ok(());
        }
        retry_number += 1;
    }
}

/// Waits for `child` (`waitForProcess`); a non-zero exit calls `on_crash` once (the same
/// prompt as [`execute_string_command`], without a retry).
pub fn wait_for_process(child: &mut Child, on_crash: &mut dyn FnMut()) -> io::Result<ExitStatus> {
    let status = child.wait()?;
    if !status.success() {
        on_crash();
    }
    Ok(status)
}

/// The localized message and retry-button texts of the "game crashed" prompt.
pub fn crash_prompt_texts() -> (String, String) {
    (
        mmcl_i18n::get("processUtil.gameCrashed.message").into_owned(),
        mmcl_i18n::get("processUtil.gameCrashed.retry").into_owned(),
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashMap;

    fn env_of(keys: &[&str]) -> HashMap<String, String> {
        keys.iter()
            .map(|k| (k.to_string(), "value".to_string()))
            .collect()
    }

    // ---- ProcessUtilitiesSplitCommandLineTest ----

    #[test]
    fn splits_on_plain_whitespace() {
        assert_eq!(
            split_command_line("java -jar app.jar"),
            ["java", "-jar", "app.jar"]
        );
    }

    #[test]
    fn collapses_runs_of_whitespace_between_tokens() {
        assert_eq!(split_command_line("a    b"), ["a", "b"]);
    }

    #[test]
    fn keeps_quoted_segment_with_internal_spaces_as_one_token() {
        assert_eq!(
            split_command_line("\"-Dname=My Pack\" -jar app.jar"),
            ["-Dname=My Pack", "-jar", "app.jar"]
        );
    }

    #[test]
    fn preserves_explicitly_empty_quoted_argument() {
        assert_eq!(split_command_line("a \"\" b"), ["a", "", "b"]);
    }

    #[test]
    fn leaves_backslashes_literal_for_windows_paths() {
        let r = split_command_line("\"C:\\dir\\\" -jar");
        assert!(r[0].contains("C:\\dir\\"));
    }

    #[test]
    fn handles_empty_string() {
        assert!(split_command_line("").is_empty());
    }

    #[test]
    fn handles_whitespace_only_string() {
        assert!(split_command_line("   ").is_empty());
    }

    #[test]
    fn trailing_unclosed_quote_still_emits_final_token() {
        assert_eq!(split_command_line("a \"b unclosed"), ["a", "b unclosed"]);
    }

    #[test]
    fn adjacent_quoted_segments_join_into_one_token() {
        assert_eq!(split_command_line("\"foo\"\"bar\""), ["foobar"]);
    }

    #[test]
    fn whitespace_follows_java_rules() {
        assert_eq!(split_command_line("a\u{2003}b"), ["a", "b"]);
        assert_eq!(split_command_line("a\u{a0}b"), ["a\u{a0}b"]);
    }

    // ---- ProcessUtilitiesSensitiveEnvTest ----

    #[test]
    fn strips_exact_sensitive_names() {
        let mut env = env_of(&["AWS_SECRET_ACCESS_KEY", "GITHUB_TOKEN", "MY_PASSWORD"]);
        strip_sensitive_env(&mut env);
        assert!(env.is_empty());
    }

    #[test]
    fn strips_case_insensitively() {
        let mut env = env_of(&["my_secret_thing", "Api_Key_Value"]);
        strip_sensitive_env(&mut env);
        assert!(env.is_empty());
    }

    #[test]
    fn strips_by_cloud_provider_prefix() {
        let mut env = env_of(&["AWS_REGION", "AZURE_CLIENT_ID", "OPENAI_API_BASE"]);
        strip_sensitive_env(&mut env);
        assert!(env.is_empty());
    }

    #[test]
    fn keeps_ordinary_variables_untouched() {
        let mut env = env_of(&["PATH", "HOME", "JAVA_HOME", "LANG"]);
        strip_sensitive_env(&mut env);
        assert_eq!(env.len(), 4);
    }

    #[test]
    fn keeps_non_sensitive_variable_while_stripping_sensitive_one() {
        let mut env = env_of(&["PATH", "DISCORD_TOKEN"]);
        strip_sensitive_env(&mut env);
        assert_eq!(env, env_of(&["PATH"]));
    }

    #[test]
    fn empty_maps_are_no_ops() {
        let mut env: HashMap<String, String> = HashMap::new();
        strip_sensitive_env(&mut env);
        assert!(env.is_empty());
    }

    #[test]
    fn does_not_spare_a_variable_that_merely_contains_an_unrelated_substring() {
        let mut env = env_of(&["TOKENIZER_MODE"]);
        strip_sensitive_env(&mut env);
        assert!(
            env.is_empty(),
            "substring match on TOKEN is intentionally conservative"
        );
    }

    // ---- launching ----

    #[test]
    fn empty_argv_is_refused() {
        let empty: [&str; 0] = [];
        assert_eq!(
            launch(&empty, Path::new("."), ChildIoMode::Discard)
                .unwrap_err()
                .kind(),
            io::ErrorKind::InvalidInput
        );
    }

    #[cfg(windows)]
    fn echo_argv(text: &str) -> Vec<String> {
        vec!["cmd".into(), "/C".into(), format!("echo {text}")]
    }

    #[cfg(not(windows))]
    fn echo_argv(text: &str) -> Vec<String> {
        vec!["sh".into(), "-c".into(), format!("echo {text}")]
    }

    #[test]
    fn piped_child_output_can_be_read() {
        let mut child = launch(
            &echo_argv("hello"),
            &std::env::temp_dir(),
            ChildIoMode::Pipe,
        )
        .unwrap();
        let mut out = String::new();
        child
            .stdout
            .take()
            .unwrap()
            .read_to_string(&mut out)
            .unwrap();
        assert!(child.wait().unwrap().success());
        assert!(out.contains("hello"));
    }

    #[test]
    fn crash_prompt_runs_only_on_failure() {
        #[cfg(windows)]
        let fail = vec!["cmd".to_string(), "/C".into(), "exit 3".into()];
        #[cfg(not(windows))]
        let fail = vec!["sh".to_string(), "-c".into(), "exit 3".into()];
        let mut child = launch(&fail, &std::env::temp_dir(), ChildIoMode::Discard).unwrap();
        let mut crashed = 0;
        let status = wait_for_process(&mut child, &mut || crashed += 1).unwrap();
        assert!(!status.success());
        assert_eq!(crashed, 1);

        let mut asks = 0;
        let cmd = format!("{} {} \"exit 3\"", fail[0], fail[1]);
        execute_string_command(&cmd, &std::env::temp_dir(), true, &mut || {
            asks += 1;
            asks < 2
        })
        .unwrap();
        assert_eq!(asks, 2, "one retry, then the user gives up");
    }
}
