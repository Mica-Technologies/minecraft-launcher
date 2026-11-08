// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The launcher's command-line grammar (`LauncherArgs`), as pure functions.
//!
//! Three layers, checked in this order by `main()`:
//!
//! 1. [`pre_session`]: forms handled before the single-instance lock and the session loop:
//!    `--mcp` (stdio relay), `--msa-signin` (the web-view sign-in helper) and
//!    `--diag-manifest <url>`.
//! 2. Flags that change how a session runs but not which session it is: `--cli`/`--tui`
//!    ([`tui_requested`]) and the gamepad/handheld switches ([`gamepad_flag`]). The Rust-only
//!    flags are removed by [`strip_rust_only_flags`] before the positional grammar sees argv.
//! 3. [`LauncherArgs::parse`]: the session grammar `[] | -c | -s | <name> | -c <name> |
//!    -s <name>`, plus `mmcl://` deep links from any position.
//!
//! [`CommandLine::parse`] runs all three.

use crate::identity::URL_SCHEME_PREFIX;

/// Client mode flag (case-insensitive).
pub const ARG_CLIENT_MODE: &str = "-c";
/// Server mode flag (case-insensitive).
pub const ARG_SERVER_MODE: &str = "-s";
/// Long server spelling; only the early "likely client" check knows it (case-sensitive).
pub const ARG_SERVER_LONG: &str = "--server";
/// Developer manifest diagnostic (`--diag-manifest <url>`, case-insensitive).
pub const ARG_DIAG_MANIFEST: &str = "--diag-manifest";
/// MCP stdio relay; must be the first argument (case-insensitive).
pub const ARG_MCP: &str = "--mcp";
/// Web-view sign-in helper; must be the first argument (case-insensitive). Rust only.
pub const ARG_MSA_SIGNIN: &str = "--msa-signin";
/// Terminal UI (case-sensitive).
pub const ARG_CLI: &str = "--cli";
/// Terminal UI alias (case-sensitive).
pub const ARG_TUI: &str = "--tui";
/// Force gamepad/handheld mode on (case-sensitive). Rust only.
pub const ARG_GAMEPAD: &str = "--gamepad";
/// Alias of [`ARG_GAMEPAD`]. Rust only.
pub const ARG_HANDHELD: &str = "--handheld";
/// Force gamepad/handheld mode off, overriding auto-detection (case-sensitive). Rust only.
pub const ARG_NO_GAMEPAD: &str = "--no-gamepad";

/// What the session should do about the game mode.
#[derive(Copy, Clone, Debug, PartialEq, Eq, Hash)]
pub enum ModeAction {
    /// Select client mode.
    Client,
    /// Select server mode.
    Server,
    /// No mode given: infer it from the environment.
    Infer,
    /// Leave the mode untouched. Only an invalid command line produces this (the caller prints
    /// usage and exits before the mode matters).
    None,
}

/// The decision [`LauncherArgs::parse`] makes about a session's argv.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct LauncherArgs {
    pub mode_action: ModeAction,
    /// The modpack name to pre-select or auto-launch, `""` when none was given.
    pub modpack_selection: String,
    /// The `mmcl://` deep link found in argv.
    pub pending_uri: Option<String>,
    /// The arguments match no accepted form: print usage and exit.
    pub invalid: bool,
}

impl LauncherArgs {
    /// Decides what a session argv means. Pure: no globals, no I/O.
    ///
    /// Precedence: a deep link anywhere wins and implies client mode (only the first one
    /// counts); then TUI mode implies client and takes the last bare token as the selection;
    /// then the positional grammar.
    ///
    /// Deliberate fix over Java: a bare `<name>` infers the mode instead of setting none. Java
    /// left the mode unset there, which crashed the session at logger setup (spec §17.1).
    pub fn parse(args: &[String], tui_enabled: bool) -> LauncherArgs {
        if let Some(uri) = first_launcher_uri(args) {
            return LauncherArgs::new(ModeAction::Client, "", Some(uri.to_string()), false);
        }

        if tui_enabled {
            let selection = args
                .iter()
                .rfind(|a| {
                    let a = a.as_str();
                    a != ARG_CLI
                        && a != ARG_TUI
                        && !a.eq_ignore_ascii_case(ARG_CLIENT_MODE)
                        && !a.eq_ignore_ascii_case(ARG_SERVER_MODE)
                })
                .cloned()
                .unwrap_or_default();
            return LauncherArgs::new(ModeAction::Client, &selection, None, false);
        }

        let is_client = |s: &str| s.eq_ignore_ascii_case(ARG_CLIENT_MODE);
        let is_server = |s: &str| s.eq_ignore_ascii_case(ARG_SERVER_MODE);
        match args {
            [] => LauncherArgs::new(ModeAction::Infer, "", None, false),
            [one] if is_client(one) => LauncherArgs::new(ModeAction::Client, "", None, false),
            [one] if is_server(one) => LauncherArgs::new(ModeAction::Server, "", None, false),
            [name] => LauncherArgs::new(ModeAction::Infer, name, None, false),
            [flag, name] if is_client(flag) => {
                LauncherArgs::new(ModeAction::Client, name, None, false)
            }
            [flag, name] if is_server(flag) => {
                LauncherArgs::new(ModeAction::Server, name, None, false)
            }
            _ => LauncherArgs::new(ModeAction::None, "", None, true),
        }
    }

    fn new(mode_action: ModeAction, selection: &str, uri: Option<String>, invalid: bool) -> Self {
        LauncherArgs {
            mode_action,
            modpack_selection: selection.to_string(),
            pending_uri: uri,
            invalid,
        }
    }

    /// `true` when a deep link was found.
    pub fn has_pending_uri(&self) -> bool {
        self.pending_uri.is_some()
    }

    /// The arguments for a session started by an in-process restart: the same mode, but no
    /// modpack auto-launch and no deep link (both were consumed by the first session). Java
    /// re-used the original argv, which auto-launched a CLI-named pack again after every
    /// sign-out or account switch (spec §17.2).
    pub fn for_restart(&self) -> LauncherArgs {
        LauncherArgs {
            mode_action: self.mode_action,
            modpack_selection: String::new(),
            pending_uri: None,
            invalid: self.invalid,
        }
    }
}

/// `true` when `s` starts with `mmcl://` (ASCII case-insensitive).
pub fn is_launcher_uri(s: &str) -> bool {
    let prefix = URL_SCHEME_PREFIX.as_bytes();
    s.len() >= prefix.len() && s.as_bytes()[..prefix.len()].eq_ignore_ascii_case(prefix)
}

/// The first argument that is a launcher deep link.
pub fn first_launcher_uri(args: &[String]) -> Option<&str> {
    args.iter().map(String::as_str).find(|a| is_launcher_uri(a))
}

/// `true` when any argument is exactly `--cli` or `--tui` (case-sensitive).
pub fn tui_requested(args: &[String]) -> bool {
    args.iter().any(|a| a == ARG_CLI || a == ARG_TUI)
}

/// The early "this will probably be a GUI client" guess: no argument is exactly `-s` or
/// `--server`, and the TUI is not requested.
pub fn likely_client(args: &[String]) -> bool {
    !tui_requested(args)
        && !args
            .iter()
            .any(|a| a == ARG_SERVER_MODE || a == ARG_SERVER_LONG)
}

/// The gamepad/handheld switch given on the command line: `Some(true)` for `--gamepad` or
/// `--handheld`, `Some(false)` for `--no-gamepad`, `None` when absent. The last one wins.
pub fn gamepad_flag(args: &[String]) -> Option<bool> {
    args.iter().fold(None, |acc, a| match a.as_str() {
        ARG_GAMEPAD | ARG_HANDHELD => Some(true),
        ARG_NO_GAMEPAD => Some(false),
        _ => acc,
    })
}

/// `true` for flags that exist only in the Rust launcher and must not reach the positional
/// grammar (which would otherwise count them and reject the command line).
pub fn is_rust_only_flag(arg: &str) -> bool {
    matches!(arg, ARG_GAMEPAD | ARG_HANDHELD | ARG_NO_GAMEPAD)
}

/// argv without the Rust-only flags, in the original order.
pub fn strip_rust_only_flags(args: &[String]) -> Vec<String> {
    args.iter()
        .filter(|a| !is_rust_only_flag(a))
        .cloned()
        .collect()
}

/// The usage line printed after `INVALID_ARGS_SPECIFIED_TEXT` and `USAGE_TEXT` (the caller
/// localizes those): `<bin> [ -s [modpack_name] | -c [modpack_name] | modpack_name ]`, plus the
/// Rust-only switches.
pub fn usage_synopsis(binary_name: &str) -> String {
    format!(
        "{binary_name} [ -s [modpack_name] | -c [modpack_name] | modpack_name ] [ --cli | --tui ] [ --gamepad | --no-gamepad ]"
    )
}

/// The file name of the running executable, for [`usage_synopsis`].
pub fn binary_name() -> String {
    std::env::current_exe()
        .ok()
        .and_then(|p| p.file_name().map(|n| n.to_string_lossy().into_owned()))
        .unwrap_or_else(|| "mica-minecraft-launcher".to_string())
}

/// Arguments of the `--msa-signin` web-view helper process.
///
/// The main process builds them with [`MsaSigninArgs::to_argv`] and re-executes itself; the
/// helper parses them with [`MsaSigninArgs::parse`]. Options take `--name value` or
/// `--name=value`. Unknown arguments are kept in `extra` so a newer parent never breaks an older
/// helper.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct MsaSigninArgs {
    /// The Microsoft sign-in page to open (`--url`).
    pub login_url: Option<String>,
    /// Navigation prefix that ends the flow; the matching URL is printed on stdout
    /// (`--redirect-prefix`).
    pub redirect_prefix: Option<String>,
    /// Window title (`--title`).
    pub title: Option<String>,
    /// User-Agent for the web view (`--user-agent`).
    pub user_agent: Option<String>,
    /// BCP-47 tag of the UI language (`--locale`).
    pub locale: Option<String>,
    /// Arguments the helper did not recognise.
    pub extra: Vec<String>,
}

impl MsaSigninArgs {
    const URL: &'static str = "--url";
    const REDIRECT: &'static str = "--redirect-prefix";
    const TITLE: &'static str = "--title";
    const USER_AGENT: &'static str = "--user-agent";
    const LOCALE: &'static str = "--locale";

    /// Parses the arguments that follow `--msa-signin`.
    pub fn parse(rest: &[String]) -> MsaSigninArgs {
        let mut out = MsaSigninArgs::default();
        let mut i = 0;
        while i < rest.len() {
            let arg = &rest[i];
            let (name, inline) = match arg.split_once('=') {
                Some((n, v)) if n.starts_with("--") => (n, Some(v.to_string())),
                _ => (arg.as_str(), None),
            };
            let slot = match name {
                Self::URL => Some(&mut out.login_url),
                Self::REDIRECT => Some(&mut out.redirect_prefix),
                Self::TITLE => Some(&mut out.title),
                Self::USER_AGENT => Some(&mut out.user_agent),
                Self::LOCALE => Some(&mut out.locale),
                _ => None,
            };
            match slot {
                Some(slot) => {
                    if let Some(v) = inline {
                        *slot = Some(v);
                    } else if let Some(v) = rest.get(i + 1) {
                        *slot = Some(v.clone());
                        i += 1;
                    } else {
                        out.extra.push(arg.clone());
                    }
                }
                None => out.extra.push(arg.clone()),
            }
            i += 1;
        }
        out
    }

    /// The full helper argv, starting with `--msa-signin`. Values use the `--name=value` form
    /// so a value that starts with `--` cannot be mistaken for an option.
    pub fn to_argv(&self) -> Vec<String> {
        let mut argv = vec![ARG_MSA_SIGNIN.to_string()];
        let pairs = [
            (Self::URL, &self.login_url),
            (Self::REDIRECT, &self.redirect_prefix),
            (Self::TITLE, &self.title),
            (Self::USER_AGENT, &self.user_agent),
            (Self::LOCALE, &self.locale),
        ];
        for (name, value) in pairs {
            if let Some(v) = value {
                argv.push(format!("{name}={v}"));
            }
        }
        argv.extend(self.extra.iter().cloned());
        argv
    }
}

/// Process modes decided before the session loop.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum PreSession {
    /// `--mcp`: relay MCP between stdio and the running launcher. stdout is protocol-only.
    Mcp,
    /// `--msa-signin`: run the web-view sign-in helper. stdout carries only the redirect URL.
    MsaSignin(MsaSigninArgs),
    /// `--diag-manifest <url>`: print the manifest diagnostic and exit.
    DiagManifest(String),
    /// A normal launcher session (GUI, TUI or server).
    Normal,
}

impl PreSession {
    /// `true` for modes whose stdout belongs to a protocol: nothing else may write to it, so
    /// the console logger must not be installed.
    pub fn stdout_is_protocol(&self) -> bool {
        matches!(self, PreSession::Mcp | PreSession::MsaSignin(_))
    }

    /// `true` when the startup log should record the launch (every mode except the MCP relay,
    /// which an MCP client may spawn many times a minute).
    pub fn records_launch(&self) -> bool {
        !matches!(self, PreSession::Mcp)
    }
}

/// Picks the pre-session mode. `--diag-manifest` needs exactly one following argument;
/// `--mcp` and `--msa-signin` must come first and take any trailing arguments.
pub fn pre_session(args: &[String]) -> PreSession {
    match args {
        [flag, url] if flag.eq_ignore_ascii_case(ARG_DIAG_MANIFEST) => {
            PreSession::DiagManifest(url.clone())
        }
        [flag, ..] if flag.eq_ignore_ascii_case(ARG_MCP) => PreSession::Mcp,
        [flag, rest @ ..] if flag.eq_ignore_ascii_case(ARG_MSA_SIGNIN) => {
            PreSession::MsaSignin(MsaSigninArgs::parse(rest))
        }
        _ => PreSession::Normal,
    }
}

/// The whole command line, decided once in `main()`.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct CommandLine {
    /// argv as given (without the program name).
    pub raw: Vec<String>,
    pub pre_session: PreSession,
    /// `--cli` / `--tui` given.
    pub tui: bool,
    /// The explicit gamepad/handheld switch, if any.
    pub gamepad_flag: Option<bool>,
    /// The session grammar's decision (over argv without the Rust-only flags). Meaningful only
    /// when `pre_session` is [`PreSession::Normal`].
    pub session: LauncherArgs,
}

impl CommandLine {
    /// Parses argv (without the program name).
    pub fn parse(args: &[String]) -> CommandLine {
        let pre_session = pre_session(args);
        let tui = tui_requested(args);
        let gamepad_flag = gamepad_flag(args);
        let session = LauncherArgs::parse(&strip_rust_only_flags(args), tui);
        CommandLine {
            raw: args.to_vec(),
            pre_session,
            tui,
            gamepad_flag,
            session,
        }
    }

    /// Parses this process's argv. Arguments that are not valid Unicode are converted lossily.
    pub fn from_env() -> CommandLine {
        let args: Vec<String> = std::env::args_os()
            .skip(1)
            .map(|a| a.to_string_lossy().into_owned())
            .collect();
        CommandLine::parse(&args)
    }

    /// The early "likely a GUI client" guess over the raw argv.
    pub fn likely_client(&self) -> bool {
        self.pre_session == PreSession::Normal && likely_client(&self.raw)
    }
}
