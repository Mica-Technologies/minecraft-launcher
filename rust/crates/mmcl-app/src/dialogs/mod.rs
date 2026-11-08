// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The launcher's own dialogs that no UI crate owns (gui-editor-games §11 puts them here):
//!
//! | Module | Java origin | Entry point |
//! |---|---|---|
//! | [`quick_start`] | `MCLauncherQuickStartWizard` | [`quick_start::show_quick_start`] |
//! | [`detail`] | `MCLauncherModpackDetailModal`, `ModpackContentBrowser` | [`detail::show_pack_detail`] |
//! | [`import_confirm`] | `MCLauncherImportConfirmDialog` | [`import_confirm::confirm_import`] |
//! | [`mod_conflict`] | `LauncherCore.promptForConflicts` | [`mod_conflict::confirm_mod_conflicts`] |
//! | [`account_prompt`] | `LauncherCore.resolveLaunchUser` | [`account_prompt::resolve_launch_account`], [`account_prompt::play`] |
//! | [`consent`] | `FxConsentPrompt` | [`consent::GpuiConsentPresenter::prompt`] |
//! | [`add_mod`] | `ModrinthAddModDialog` | [`add_mod::show_add_mod`] |
//!
//! [`install`] connects them to the hooks `mmcl-ui`'s Home and Browse screens expose (quick
//! start, detail modal, import confirm, Play).
//!
//! `crate::gui::setup_ui` calls [`install`]; the launch flow, the MCP host and the detail hooks
//! (`crate::detail_hooks`) reach the rest.

pub mod account_prompt;
pub mod add_mod;
pub mod consent;
pub mod detail;
pub mod import_confirm;
pub mod mod_conflict;
pub mod quick_start;
pub mod widgets;

use gpui::{
    AnyView, AnyWindowHandle, App, AppContext as _, Bounds, Window, WindowBounds,
    WindowDecorations, WindowHandle, WindowKind, WindowOptions, px, size,
};
use gpui_component::{Root, TitleBar};
use mmcl_engine::Engine;

/// How a secondary window opens.
pub struct WindowSpec {
    /// The OS window title.
    pub title: String,
    /// Its size (px at 100 %).
    pub size: (f32, f32),
    /// Its minimum size when resizable (px at 100 %).
    pub min_size: Option<(f32, f32)>,
    /// Whether the user can resize it.
    pub resizable: bool,
}

/// Opens a themed secondary window (launcher title bar, solid surface, title-bar chrome per the
/// theme) centred on `owner`'s display (the main window when `None`). The root view comes from
/// `build`; the window's root is gpui-component's [`Root`] so in-window dialogs work in it.
pub fn open_secondary_window(
    spec: WindowSpec,
    owner: Option<AnyWindowHandle>,
    cx: &mut App,
    build: impl FnOnce(&mut Window, &mut App) -> AnyView,
) -> Option<WindowHandle<Root>> {
    let owner = owner.or_else(|| mmcl_ui::shell::main_window_handle(cx));
    let display = owner.and_then(|h| {
        h.update(cx, |_, window, cx| window.display(cx).map(|d| d.id()))
            .ok()
            .flatten()
    });
    let scale = mmcl_ui::scale::factor(cx);
    let window_size = size(px(spec.size.0 * scale), px(spec.size.1 * scale));
    let options = WindowOptions {
        window_bounds: Some(WindowBounds::Windowed(Bounds::centered(
            display,
            window_size,
            cx,
        ))),
        display_id: display,
        kind: WindowKind::Normal,
        is_resizable: spec.resizable,
        window_min_size: spec
            .min_size
            .map(|(w, h)| size(px(w * scale), px(h * scale))),
        window_background: mmcl_ui::theme::secondary_window_background(),
        // The Wayland app id / X11 WM_CLASS: the package name, which the .desktop file's
        // StartupWMClass carries (not the desktop-file id with its ".desktop" suffix).
        app_id: Some(
            mmcl_core::AppIdentity::current()
                .linux_package_name
                .to_string(),
        ),
        window_decorations: Some(WindowDecorations::Client),
        focus: true,
        show: true,
        ..TitleBar::window_options()
    };
    let opened = cx.open_window(options, |window, cx| {
        let view = build(window, cx);
        cx.new(|cx| Root::new(view, window, cx))
    });
    match opened {
        Ok(handle) => {
            let title = spec.title;
            let _ = handle.update(cx, |_, window, cx| {
                window.set_window_title(&title);
                mmcl_ui::theme::install_theme_on(window, cx);
            });
            Some(handle)
        }
        Err(e) => {
            tracing::error!(
                "{}",
                mmcl_i18n::tf!("log.dialogs.windowFailed", spec.title, e.to_string())
            );
            None
        }
    }
}

/// The launcher window an in-window dialog goes into: `preferred` when it is still open, else
/// the main window, else the active one.
pub fn host_window(cx: &App, preferred: Option<AnyWindowHandle>) -> Option<AnyWindowHandle> {
    let windows = cx.windows();
    preferred
        .filter(|w| windows.contains(w))
        .or_else(|| mmcl_ui::shell::main_window_handle(cx))
        .or_else(|| cx.active_window())
        .or_else(|| windows.first().copied())
}

/// Whether `window`'s root is gpui-component's [`Root`] (needed for in-window dialogs).
pub fn has_root(window: &Window) -> bool {
    matches!(window.root::<Root>(), Some(Some(_)))
}

/// Installs the dialogs into `mmcl-ui`'s hooks: the quick-start wizard, the pack detail modal,
/// the import-confirm dialog and the Play flow (account prompts and the mod-conflict dialog).
pub fn install(cx: &mut App, engine: &Engine) {
    use mmcl_ui::screens::home_browse as hb;
    let e = engine.clone();
    hb::set_quick_start_handler(cx, move |window, cx| {
        quick_start::show_quick_start_if_needed(e.clone(), Some(window.window_handle()), cx);
    });
    let e = engine.clone();
    hb::set_open_detail_handler(cx, move |id, window, cx| {
        detail::show_pack_detail(e.clone(), id, window, cx);
    });
    hb::set_confirm_import_handler(cx, |request, cx| {
        import_confirm::confirm_import(import_confirm::ImportPreview::from_hook(&request), None, cx)
    });
    let e = engine.clone();
    hb::set_play_handler(cx, move |id, cx| {
        account_prompt::start_play(e.clone(), id, mmcl_engine::LaunchOptions::default(), cx);
    });
}

#[cfg(test)]
mod tests {
    use std::path::Path;

    /// Every literal key passed to `t!` / `tf!` in this module exists in the English bundle.
    #[test]
    fn every_used_key_resolves() {
        let dir = Path::new(env!("CARGO_MANIFEST_DIR")).join("src/dialogs");
        let mut missing = Vec::new();
        let mut files = Vec::new();
        let mut stack = vec![dir];
        while let Some(d) = stack.pop() {
            for entry in std::fs::read_dir(&d).unwrap() {
                let path = entry.unwrap().path();
                if path.is_dir() {
                    stack.push(path);
                } else {
                    files.push(path);
                }
            }
        }
        for path in files {
            let text = std::fs::read_to_string(&path).unwrap();
            for marker in ["t!(\"", "tf!(\"", "key: \"", "_KEY: &str = \""] {
                let mut rest = text.as_str();
                while let Some(i) = rest.find(marker) {
                    let after = &rest[i + marker.len()..];
                    if let Some(end) = after.find('"') {
                        let key = &after[..end];
                        if key.contains('.')
                            && !key.contains(' ')
                            && !key.contains('{')
                            && mmcl_i18n::english(key).is_none()
                        {
                            missing.push(format!(
                                "{}: {key}",
                                path.file_name().unwrap().to_string_lossy()
                            ));
                        }
                    }
                    rest = after;
                }
            }
        }
        assert!(missing.is_empty(), "missing keys: {missing:#?}");
    }
}
