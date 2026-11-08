// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Port of `LauncherArgsTest`, plus the Rust-only forms.

use mmcl_core::args::{
    self, CommandLine, LauncherArgs, ModeAction, MsaSigninArgs, PreSession, gamepad_flag,
    likely_client, tui_requested,
};

const NO_TUI: bool = false;
const TUI: bool = true;

fn v(args: &[&str]) -> Vec<String> {
    args.iter().map(|s| s.to_string()).collect()
}

fn parse(args: &[&str], tui: bool) -> LauncherArgs {
    LauncherArgs::parse(&v(args), tui)
}

#[test]
fn no_arguments_infers_the_game_mode() {
    let a = parse(&[], NO_TUI);
    assert_eq!(a.mode_action, ModeAction::Infer);
    assert_eq!(a.modpack_selection, "");
    assert!(!a.invalid);
    assert!(!a.has_pending_uri());
}

#[test]
fn empty_arguments_are_treated_as_empty() {
    // Java's null argv; Rust has no null slice, so an empty one stands in.
    assert_eq!(
        LauncherArgs::parse(&[], NO_TUI).mode_action,
        ModeAction::Infer
    );
}

#[test]
fn client_flag_alone_selects_client_mode() {
    let a = parse(&["-c"], NO_TUI);
    assert_eq!(a.mode_action, ModeAction::Client);
    assert_eq!(a.modpack_selection, "");
}

#[test]
fn server_flag_alone_selects_server_mode() {
    let a = parse(&["-s"], NO_TUI);
    assert_eq!(a.mode_action, ModeAction::Server);
    assert_eq!(a.modpack_selection, "");
}

#[test]
fn mode_flags_are_case_insensitive() {
    assert_eq!(parse(&["-C"], NO_TUI).mode_action, ModeAction::Client);
    assert_eq!(parse(&["-S"], NO_TUI).mode_action, ModeAction::Server);
}

#[test]
fn client_flag_with_modpack_selects_both() {
    let a = parse(&["-c", "All the Mods 9"], NO_TUI);
    assert_eq!(a.mode_action, ModeAction::Client);
    assert_eq!(a.modpack_selection, "All the Mods 9");
    assert!(!a.invalid);
}

#[test]
fn server_flag_with_modpack_selects_both() {
    let a = parse(&["-s", "SkyFactory"], NO_TUI);
    assert_eq!(a.mode_action, ModeAction::Server);
    assert_eq!(a.modpack_selection, "SkyFactory");
}

#[test]
fn bare_modpack_name_infers_the_mode() {
    // Deliberate fix over Java (which set no mode and then crashed at logger setup).
    let a = parse(&["MyPack"], NO_TUI);
    assert_eq!(a.mode_action, ModeAction::Infer);
    assert_eq!(a.modpack_selection, "MyPack");
    assert!(!a.invalid);
}

#[test]
fn two_bare_positionals_are_invalid() {
    assert!(parse(&["one", "two"], NO_TUI).invalid);
}

#[test]
fn three_or_more_arguments_are_invalid() {
    assert!(parse(&["-c", "a", "b"], NO_TUI).invalid);
}

#[test]
fn invalid_form_selects_no_mode_and_no_modpack() {
    let a = parse(&["one", "two"], NO_TUI);
    assert_eq!(a.mode_action, ModeAction::None);
    assert_eq!(a.modpack_selection, "");
}

#[test]
fn deep_link_is_captured_and_implies_client_mode() {
    let a = parse(&["mmcl://play?name=Test"], NO_TUI);
    assert!(a.has_pending_uri());
    assert_eq!(a.pending_uri.as_deref(), Some("mmcl://play?name=Test"));
    assert_eq!(a.mode_action, ModeAction::Client);
    assert_eq!(a.modpack_selection, "");
    assert!(!a.invalid);
}

#[test]
fn deep_link_wins_from_any_position_and_overrides_other_forms() {
    let a = parse(&["-s", "pack", "MMCL://open", "mmcl://second"], NO_TUI);
    assert!(a.has_pending_uri());
    assert_eq!(a.pending_uri.as_deref(), Some("MMCL://open"));
    assert_eq!(a.mode_action, ModeAction::Client);
    assert!(
        !a.invalid,
        "a deep link must not be rejected by the positional grammar"
    );
}

#[test]
fn non_launcher_uri_is_not_treated_as_a_deep_link() {
    let a = parse(&["https://example.com"], NO_TUI);
    assert!(!a.has_pending_uri());
    assert_eq!(a.pending_uri, None);
    assert!(!args::is_launcher_uri("mmcl:/x"));
    assert!(!args::is_launcher_uri("mmc"));
}

#[test]
fn tui_mode_implies_client_mode() {
    let a = parse(&["--tui"], TUI);
    assert_eq!(a.mode_action, ModeAction::Client);
    assert_eq!(a.modpack_selection, "");
}

#[test]
fn tui_mode_strips_flags_and_keeps_the_bare_token() {
    let a = parse(&["--tui", "-c", "MyPack"], TUI);
    assert_eq!(a.mode_action, ModeAction::Client);
    assert_eq!(a.modpack_selection, "MyPack");
}

#[test]
fn tui_mode_accepts_the_cli_alias() {
    assert_eq!(parse(&["--cli", "MyPack"], TUI).modpack_selection, "MyPack");
}

#[test]
fn tui_mode_takes_the_last_bare_token_when_several_are_given() {
    assert_eq!(
        parse(&["--tui", "first", "second"], TUI).modpack_selection,
        "second"
    );
}

#[test]
fn tui_mode_never_reports_invalid() {
    assert!(!parse(&["--tui", "a", "b", "c"], TUI).invalid);
}

#[test]
fn deep_link_still_wins_over_tui_mode() {
    let a = parse(&["--tui", "mmcl://open"], TUI);
    assert!(a.has_pending_uri());
    assert_eq!(a.mode_action, ModeAction::Client);
}

#[test]
fn tui_detection_is_case_sensitive() {
    assert!(tui_requested(&v(&["--cli"])));
    assert!(tui_requested(&v(&["x", "--tui"])));
    assert!(!tui_requested(&v(&["--TUI"])));
    assert!(!tui_requested(&v(&["-c"])));
}

#[test]
fn restart_arguments_drop_the_auto_launch_and_link() {
    let a = parse(&["-c", "Pack"], NO_TUI).for_restart();
    assert_eq!(a.mode_action, ModeAction::Client);
    assert_eq!(a.modpack_selection, "");
    let b = parse(&["mmcl://open"], NO_TUI).for_restart();
    assert!(!b.has_pending_uri());
}

#[test]
fn likely_client_guess() {
    assert!(likely_client(&v(&[])));
    assert!(likely_client(&v(&["-c", "pack"])));
    assert!(!likely_client(&v(&["-s"])));
    assert!(!likely_client(&v(&["--server"])));
    assert!(!likely_client(&v(&["--cli"])));
    // Case-sensitive, like Java's early check.
    assert!(likely_client(&v(&["-S"])));
}

#[test]
fn pre_session_forms() {
    assert_eq!(args::pre_session(&v(&[])), PreSession::Normal);
    assert_eq!(args::pre_session(&v(&["--mcp"])), PreSession::Mcp);
    assert_eq!(args::pre_session(&v(&["--MCP", "x", "y"])), PreSession::Mcp);
    assert_eq!(args::pre_session(&v(&["-c", "--mcp"])), PreSession::Normal);
    assert_eq!(
        args::pre_session(&v(&["--diag-manifest", "https://x/p.mmcjson"])),
        PreSession::DiagManifest("https://x/p.mmcjson".into())
    );
    assert_eq!(
        args::pre_session(&v(&["--diag-manifest"])),
        PreSession::Normal
    );
    assert_eq!(
        args::pre_session(&v(&["--diag-manifest", "a", "b"])),
        PreSession::Normal
    );
    assert_eq!(
        args::pre_session(&v(&["--msa-signin"])),
        PreSession::MsaSignin(MsaSigninArgs::default())
    );
    assert!(PreSession::Mcp.stdout_is_protocol());
    assert!(PreSession::MsaSignin(MsaSigninArgs::default()).stdout_is_protocol());
    assert!(!PreSession::Normal.stdout_is_protocol());
    assert!(!PreSession::Mcp.records_launch());
    assert!(PreSession::Normal.records_launch());
}

#[test]
fn msa_signin_arguments_round_trip() {
    let original = MsaSigninArgs {
        login_url: Some(
            "https://login.live.com/oauth20_authorize.srf?client_id=00000000402b5328&a=b".into(),
        ),
        redirect_prefix: Some("https://login.live.com/oauth20_desktop.srf".into()),
        title: Some("Sign in = Microsoft".into()),
        user_agent: Some("--weird-agent".into()),
        locale: Some("pt-BR".into()),
        extra: vec!["--future-flag".into()],
    };
    let argv = original.to_argv();
    assert_eq!(argv[0], "--msa-signin");
    match args::pre_session(&argv) {
        PreSession::MsaSignin(parsed) => assert_eq!(parsed, original),
        other => panic!("unexpected {other:?}"),
    }
}

#[test]
fn msa_signin_accepts_space_separated_values() {
    let parsed = MsaSigninArgs::parse(&v(&["--url", "https://a", "--title", "T", "--locale"]));
    assert_eq!(parsed.login_url.as_deref(), Some("https://a"));
    assert_eq!(parsed.title.as_deref(), Some("T"));
    assert_eq!(parsed.locale, None);
    assert_eq!(parsed.extra, vec!["--locale".to_string()]);
}

#[test]
fn gamepad_switches() {
    assert_eq!(gamepad_flag(&v(&[])), None);
    assert_eq!(gamepad_flag(&v(&["--gamepad"])), Some(true));
    assert_eq!(gamepad_flag(&v(&["--handheld"])), Some(true));
    assert_eq!(gamepad_flag(&v(&["--no-gamepad"])), Some(false));
    assert_eq!(
        gamepad_flag(&v(&["--gamepad", "--no-gamepad"])),
        Some(false)
    );
    assert_eq!(gamepad_flag(&v(&["--GAMEPAD"])), None);
}

#[test]
fn rust_only_flags_do_not_break_the_positional_grammar() {
    let cl = CommandLine::parse(&v(&["--gamepad", "-c", "MyPack"]));
    assert_eq!(cl.pre_session, PreSession::Normal);
    assert_eq!(cl.gamepad_flag, Some(true));
    assert!(!cl.tui);
    assert_eq!(cl.session.mode_action, ModeAction::Client);
    assert_eq!(cl.session.modpack_selection, "MyPack");
    assert!(!cl.session.invalid);
    assert!(cl.likely_client());

    let tui = CommandLine::parse(&v(&["--tui", "--handheld", "Pack"]));
    assert!(tui.tui);
    assert_eq!(tui.session.modpack_selection, "Pack");

    let server = CommandLine::parse(&v(&["-s", "Pack", "--no-gamepad"]));
    assert_eq!(server.session.mode_action, ModeAction::Server);
    assert!(!server.likely_client());

    let helper = CommandLine::parse(&v(&["--msa-signin", "--url=https://x"]));
    assert!(!helper.likely_client());
}

#[test]
fn usage_names_the_binary() {
    let usage = args::usage_synopsis("mica");
    assert!(usage.starts_with("mica [ -s [modpack_name] | -c [modpack_name] | modpack_name ]"));
    assert!(usage.contains("--gamepad"));
}
