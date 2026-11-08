# Mica Minecraft Launcher: Rust architecture

This is the contract every crate in `rust/` is built against. It is the Rust port of the Java/JavaFX
launcher in `../src/main/java`. The per-subsystem porting specs in `docs/spec/*.md` describe the
behaviour in detail; this file decides how the pieces fit.

## Decisions already made

| Topic | Decision |
|---|---|
| Scope | Full feature parity with the Java launcher. |
| UI | GPUI via the `gpui-pre` 0.3.11 snapshot (imported under the name `gpui`), `gpui-pre-platform` (`gpui_platform::application()`), and `gpui-component` 0.9 for widgets. No Tauri, no web UI. A web view exists only in the sign-in helper process. |
| Fix or copy | Fix the bugs each spec flags, taking the spec's recommended fix. **Never** change an on-disk format the Java launcher reads or writes (config JSON, pack sidecars, manifests, install index, encrypted account files, image cache names): both launchers must read each other's files, so the later switch to the Java data folder just works. |
| Identity | The Rust build is a separate product while it lives beside Java: display name `Mica Minecraft Launcher (Rust)`, data folder `~/.MicaMinecraftLauncherRS` (dev: `~/.MicaMinecraftLauncherRSDEV`, override `MMCL_DATA_DIR`), its own installer UpgradeCodes, bundle ID, AUMID, `-rs` Linux package name, ports 47831-47834, and it does **not** register `mmcl://` or `.mmcjson` (Java keeps those). All of this lives in one `AppIdentity` value in `mmcl-core`, so switching to the Java identity later is one change. Releases are GitHub pre-releases tagged `rs-*`. |
| Folder layout | Inside the data folder, every path mirrors Java's `LocalPathManager` exactly. |
| Sign-in | Two flows ship now, both using the official client ID `00000000402b5328`: (1) **web view**: the app re-executes itself as `--msa-signin` helper, which opens a `tao` + `wry` window on Microsoft's page and prints the `oauth20_desktop.srf` redirect URL on stdout; (2) **device code**: microsoft.com/link with a code, first-class (default in gamepad/handheld mode such as Steam Deck, one click away elsewhere). (3) **WAM** (the Windows account broker) is built behind a flag: it activates only when a configured client ID of our own is set (`auth.clientId` + `auth.wam`). All three only produce a Microsoft OAuth result; the Xbox -> XSTS -> Minecraft chain is shared. |
| Packaging | Same installer set as Java: Windows MSI + EXE bootstrapper, macOS DMG + PKG, Linux DEB + RPM (+ AppImage, tarball). See `docs/spec/packaging-ci.md`. |

## Crates and their order

Lower layers never depend on higher ones. A crate may depend on any crate in a lower layer.

| Layer | Crate | Owns | Spec |
|---|---|---|---|
| 0 | `mmcl-core` | `AppIdentity`, build info, `Side`/game mode, `LauncherArgs` parser, `DataPaths` (every folder), launcher constants | core-config-i18n |
| 0 | `mmcl-i18n` | `.properties` bundles (read from `../src/main/resources/lang` at build time, plus the Rust overlay in `rust/i18n/`), MessageFormat, `SupportedLocales`, locale bootstrap, `t!`/`tf!` macros | core-config-i18n |
| 0 | `mmcl-progress` | launch progress tracker, `ProgressSink`, section progress | modpack-model |
| 0 | `mmcl-theme` | the 7 theme token tables as plain data (no GPUI), theme resolution | gui-shell |
| 0 | `mmcl-mojang` | pure Mojang/Forge manifest models, rule evaluation, argument flattening, `HostInfo` | launch-pipeline |
| 0 | `mmcl-manifest` | pure hosted-pack (`.mmcjson`) schema with Gson-compatible leniency, editor document model | modpack-model |
| 0 | `mmcl-crash` | pure crash analyzer | auth-session-crash |
| 1 | `mmcl-log` | `tracing` subscriber, `[App/LEVEL]` format, redaction, session log file | core-config-i18n |
| 1 | `mmcl-util` | version compare, URL validation, JVM args, unit formatting, small fs helpers | utilities-platform |
| 1 | `mmcl-secure` | machine-bound AES-256-GCM cipher, byte-compatible with Java; hardware UUID | auth-session-crash + utilities-platform |
| 2 | `mmcl-config` | `ConfigStore` (Java-compatible JSON, debounced atomic writes, migrations) | core-config-i18n |
| 2 | `mmcl-net` | HTTP client (reqwest, rustls), downloads with retry/stall watchdog/hash check, conditional GET, offline state, proxies | utilities-platform |
| 2 | `mmcl-archive` | contained zip/tar.gz extraction, natives extraction | utilities-platform |
| 3 | `mmcl-scan` | JVM class-file decoder, Nekodetector port, supplemental scanner | core-config-i18n |
| 3 | `mmcl-runtime` | Java runtime manager (Mojang components, Liberica/Adoptium fallback) | launch-pipeline |
| 3 | `mmcl-auth` | MSA/Xbox/XSTS/Minecraft protocol, `AuthFile`, `AccountStore`, `AccountManager`, `LaunchAccountResolver`, the `SignInFlow` implementations (web view helper client, device code, WAM) | auth-session-crash |
| 3 | `mmcl-discord` | Discord IPC client and presence | utilities-platform |
| 3 | `mmcl-rgb` | RGB engine, effects, backends (vendor DLL backends isolated in a helper process) | rgb-tui |
| 3 | `mmcl-platform` | OS integration (theme probe, DWM/Mica, taskbar, jump list, tray, notifications, single-instance IPC, macOS dock/menu, registration helpers) via raw window handles | utilities-platform |
| 4 | `mmcl-modpack` | pack runtime, fetcher + cache, file sync, install index, manager, sidecars, backups, export, importers (`import` module) | modpack-model |
| 5 | `mmcl-launch` | `ModLoader` trait, Forge/NeoForge/Fabric/vanilla, processors, classpath, arguments, process start | launch-pipeline |
| 5 | `mmcl-session` | `GameSession`, `GameSessionRegistry`, `GameLog`, `RunningGameFollower` | auth-session-crash |
| 6 | `mmcl-engine` | the application facade every front end uses (see below) | all |
| 7 | `mmcl-mcp` | MCP server (protocol, transports, tools, resources, approval) over `mmcl-engine` | mcp |
| 7 | `mmcl-tui` | ratatui terminal UI over `mmcl-engine` | rgb-tui |
| 7 | `mmcl-signin-webview` | the `tao` + `wry` sign-in helper window (runs only in the `--msa-signin` helper process) | gui-shell + auth-session-crash |
| 8 | `mmcl-ui-kit` | GPUI widgets on gpui-component, theming bridge from `mmcl-theme`, icons, fonts, the `Screen` trait and `ScreenRegistry` | gui-editor-games + gui-shell |
| 9 | `mmcl-ui` | window shell, navigation, main, library/browse, sign-in screens (device code + web view launcher), settings, runtime management, progress, help | gui-shell |
| 9 | `mmcl-editor` | modpack editor | gui-editor-games |
| 9 | `mmcl-running-games` | Running Games window / docked view, console, crash card | gui-editor-games |
| 10 | `mmcl-app` | `main()`: args, startup guard, single instance, mode dispatch (GUI, TUI, server, `--mcp` stdio relay, `--msa-signin` helper), wires the UI crates into the shell | core-config-i18n + packaging-ci |
| tool | `xtask` | `cargo xtask dist <os>` packaging, versioning, checksums (not shipped) | packaging-ci |

`mmcl-ui`, `mmcl-editor` and `mmcl-running-games` do not depend on each other. They each provide
screens through the `Screen` trait in `mmcl-ui-kit`; `mmcl-app` registers them with the shell.

## `mmcl-engine`: the one facade

GUI, TUI, MCP and server mode never reach into lower crates for application operations; they call
`mmcl-engine`. It owns:

- the tokio runtime (`Engine::spawn` runs a future on it and returns a runtime-agnostic
  `oneshot`-backed future, so GPUI's executor can await it),
- `ConfigStore`, `AccountManager`, the pack manager, `RuntimeManager`, `GameSessionRegistry`,
  Discord presence and RGB,
- an event bus: `Engine::subscribe() -> tokio::sync::broadcast::Receiver<EngineEvent>` (pack list
  changed, progress, session started/output/exited, account changed, offline state, notifications),
- the operations: install/update/uninstall/verify pack, launch pack (admission, account resolution,
  prepare, start), stop game, sign in/out, set default account, settings get/set, runtime
  install/remove, backups, export/import.

Lower crates expose plain async APIs and know nothing about the engine.

## UI: start from the Java launcher

The Java launcher's UI is the starting point, not a template to copy exactly. Work from its layout
and adapt it freely where GPUI suggests something better; the aim is a UI that is recognisably the
same launcher, not a reinvented one.

- **Start from the Java layout.** Begin with the same screens, navigation (nav bar, title-bar
  controls, account switcher) and broad arrangement of each screen (hero card, library grid,
  settings sections, editor panes, Running Games tabs and console), with the website-brand look from
  the theme tokens and the same icons and fonts. References: the Java FXML in
  `../src/main/resources/gui/`, the CSS in `../src/main/resources/ui/`, and the layout sections of
  the gui-shell and gui-editor-games specs.
- **Change it where GPUI does it better.** Moving, regrouping or restyling things is fine when it
  suits GPUI and gpui-component or reads better. Do not imitate JavaFX quirks. Note notable layout
  changes in `deviations`.
- **Lose nothing.** Every control, menu item, context menu, shortcut, tooltip, dialog, empty state,
  error state, progress display and setting the Java UI has must still exist somewhere sensible.
  Before reporting a screen done, walk its Java controller and FXML and check off each feature; list
  any you could not port in `gaps`, never drop one silently.
- **Improve it.** Smoother motion, faster list rendering (virtualised lists), better keyboard and
  gamepad focus, clearer empty and error states, fixes for the UI bugs the specs flag.

## Conventions

- **Edition 2024**, workspace version/edition/license via `*.workspace = true`.
- **Shared dependency versions** are in the root `[workspace.dependencies]`; use `dep.workspace = true`
  for anything listed there. A crate may add other dependencies in its own `Cargo.toml`.
  Do not edit the root `Cargo.toml` from a crate agent.
- **Async:** tokio for all I/O. GPUI code never blocks its thread on I/O; it awaits engine futures.
- **Errors:** `thiserror` enums per crate; `anyhow` only in `mmcl-engine` and `mmcl-app`. An error a
  user sees maps to a localization key.
- **Logging:** `tracing` macros everywhere; `mmcl-log` installs the subscriber. Never log tokens: use
  `mmcl_log::redact`.
- **Localization:** every user-visible string and every log message goes through `mmcl-i18n`.
  Reuse existing keys from `../src/main/resources/lang/DisplayStrings.properties` where they fit.
  New keys follow `<screen>.<element>.<purpose>` and go in English into
  `rust/i18n/DisplayStrings.properties` (the Rust overlay). Translations of the overlay are generated
  at the end with the repo's translate tool. Parameterised messages use `{0}` slots, never
  concatenation.
- **Paths:** only through `mmcl_core::DataPaths`; never hard-code a folder.
- **Platform code:** `#[cfg(target_os = ...)]` modules with a no-op fallback, so every crate builds on
  every OS. Code for macOS and Linux must compile in CI even though it can't run on the dev machine.
- **Tests:** unit tests in each crate (`#[cfg(test)]`), no network, no mocking framework; seams take
  their dependencies as arguments or traits. Port the Java tests listed in each spec.
- **Style:** `rustfmt` defaults; `cargo clippy` clean with `-D warnings` for new code.
- **File headers:** a short GPL-3.0 notice: `// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.`

## Building on the dev machine

- Use the shared target directory (`rust/target`); cargo's build lock lets only one build run at a
  time, which is intended. Builds use 6 jobs (`rust/.cargo/config.toml`).
- Prefer `cargo check -p <crate>` and `cargo test -p <crate>` over workspace-wide builds.
- If cargo fails because another crate's `Cargo.toml` is mid-edit, wait a minute and retry; never edit
  another crate to make yours build. Report missing upstream APIs instead.
