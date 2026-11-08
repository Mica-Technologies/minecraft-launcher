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
//!
//! Window relationships follow Java's stages: the quick-start wizard and the import
//! confirmation are owned and modal (`WINDOW_MODAL`), Add Mod is owned but not modal
//! (`Modality.NONE`), and the MCP consent prompt is unowned so it shows while the main window
//! is minimised. The detail modal stays an in-window overlay over the main window, as Java's
//! `StackPane` overlay was, and is modal there already.

pub mod account_prompt;
pub mod add_mod;
pub mod consent;
pub mod detail;
pub mod import_confirm;
pub mod mod_conflict;
pub mod quick_start;
pub mod widgets;

use std::collections::HashMap;
use std::future::Future;
use std::path::PathBuf;
use std::pin::Pin;

use gpui::{
    AnyView, AnyWindowHandle, App, AppContext as _, Bounds, DisplayId, Global, Pixels, Size,
    Window, WindowBounds, WindowDecorations, WindowHandle, WindowId, WindowKind, WindowOptions, px,
    size,
};
use gpui_component::{Root, TitleBar};
use mmcl_engine::Engine;
use mmcl_ui_kit::SaveDialog;
use mmcl_ui_kit::windowing::{self, BoundsMemory, NativeHandle, OwnerLink, SharedOwnerLink};

/// How a secondary window opens.
pub struct WindowSpec {
    /// The OS window title.
    pub title: String,
    /// Its size (px at 100 %).
    pub size: (f32, f32),
    /// Its minimum size when resizable (px at 100 %); it follows later interface-scale changes.
    pub min_size: Option<(f32, f32)>,
    /// Whether the user can resize it.
    pub resizable: bool,
    /// Whether the owner takes no input while it is open (JavaFX `Modality.WINDOW_MODAL`).
    /// Without an owner it has no effect.
    pub modal: bool,
    /// Remembers its bounds under this key for the session, so it reopens where and as large
    /// as it was (resizable windows).
    pub bounds_key: Option<&'static str>,
}

/// Who a secondary window belongs to (JavaFX `Stage.initOwner`). An owned window stays above
/// its owner and minimises with it; a modal one also blocks it ([`WindowSpec::modal`]).
#[derive(Debug, Clone, Copy, Default)]
pub enum Owner {
    /// The main launcher window, looked up when the window opens.
    #[default]
    Main,
    /// A launcher window, read when the window opens. Use [`Owner::of`] instead from inside
    /// that window's own handler: GPUI cannot borrow a window during its own update.
    Window(AnyWindowHandle),
    /// A window captured with [`Owner::of`].
    Captured {
        handle: AnyWindowHandle,
        native: NativeHandle,
        display: Option<DisplayId>,
    },
    /// No owner: an independent top-level window (still placed on the main window's display).
    /// For prompts that must show while the main window is minimised (MCP consent).
    Unowned,
}

impl Owner {
    /// `window` as the owner, captured now (works inside its own handlers).
    pub fn of(window: &Window, cx: &App) -> Owner {
        Owner::Captured {
            handle: window.window_handle(),
            native: windowing::native(window),
            display: window.display(cx).map(|d| d.id()),
        }
    }

    /// The owner's native handle (`None` when unowned or gone) and display.
    fn resolve(self, cx: &mut App) -> (Option<NativeHandle>, Option<DisplayId>) {
        let read = |h: AnyWindowHandle, cx: &mut App| {
            h.update(cx, |_, window, cx| {
                (
                    Some(windowing::native(window)),
                    window.display(cx).map(|d| d.id()),
                )
            })
            .unwrap_or((None, None))
        };
        match self {
            Owner::Main => match mmcl_ui::shell::main_window_handle(cx) {
                Some(h) => read(h, cx),
                None => (None, None),
            },
            Owner::Window(h) => read(h, cx),
            Owner::Captured {
                handle,
                native,
                display,
            } => {
                if cx.windows().contains(&handle) {
                    (Some(native), display)
                } else {
                    (None, None)
                }
            }
            Owner::Unowned => {
                let display = match mmcl_ui::shell::main_window_handle(cx) {
                    Some(h) => read(h, cx).1,
                    None => None,
                };
                (None, display)
            }
        }
    }
}

impl From<Option<AnyWindowHandle>> for Owner {
    /// `Some` window, else the main window.
    fn from(h: Option<AnyWindowHandle>) -> Owner {
        h.map(Owner::Window).unwrap_or_default()
    }
}

/// The owner links of the open secondary windows, released when each window closes.
#[derive(Default)]
struct OwnerLinks {
    links: HashMap<WindowId, SharedOwnerLink>,
    watching: bool,
}

impl Global for OwnerLinks {}

fn register_link(id: WindowId, link: SharedOwnerLink, cx: &mut App) {
    let g = cx.default_global::<OwnerLinks>();
    g.links.insert(id, link);
    if !std::mem::replace(&mut g.watching, true) {
        // The safety net for windows closed some other way (app exit, a remove_window that
        // skipped close_window): a modal owner must never stay disabled.
        cx.on_window_closed(|cx, id| release_link(id, cx)).detach();
    }
}

fn release_link(id: WindowId, cx: &mut App) {
    let link = cx
        .try_global::<OwnerLinks>()
        .is_some_and(|g| g.links.contains_key(&id))
        .then(|| cx.global_mut::<OwnerLinks>().links.remove(&id))
        .flatten();
    if let Some(link) = link {
        link.release();
    }
}

/// Undoes `window`'s tie to its owner (re-enabling a modal owner). Call it from a custom
/// `on_window_should_close` handler right before returning `true`; safe to call twice.
pub fn release_owner(window: &Window, cx: &mut App) {
    release_link(window.window_handle().window_id(), cx);
}

/// Closes a secondary window: releases its owner first (so the owner gets the activation
/// back), then removes it.
pub fn close_window(window: &mut Window, cx: &mut App) {
    release_owner(window, cx);
    window.remove_window();
}

/// The opening bounds: the remembered ones for `key` (fitted onto today's screens), else
/// `size` centred on `display`.
fn opening_bounds(
    key: Option<&str>,
    display: Option<DisplayId>,
    size: Size<Pixels>,
    cx: &App,
) -> WindowBounds {
    match key {
        Some(k)
            if cx
                .try_global::<BoundsMemory>()
                .and_then(|m| m.get(k))
                .is_some() =>
        {
            windowing::recall_or_centered(cx, k, size)
        }
        _ => WindowBounds::Windowed(Bounds::centered(display, size, cx)),
    }
}

/// Opens a themed secondary window (launcher title bar, solid surface, title-bar chrome per the
/// theme) centred on `owner`'s display, owned by it (and modal over it when the spec says so).
/// The root view comes from `build`; the window's root is gpui-component's [`Root`] so
/// in-window dialogs work in it. The window gets a close handler that releases the owner; a
/// dialog that installs its own (GPUI keeps one) calls [`release_owner`] in it, and closes
/// itself with [`close_window`].
pub fn open_secondary_window(
    spec: WindowSpec,
    owner: impl Into<Owner>,
    cx: &mut App,
    build: impl FnOnce(&mut Window, &mut App) -> AnyView,
) -> Option<WindowHandle<Root>> {
    let (owner_native, display) = owner.into().resolve(cx);
    let scale = mmcl_ui::scale::factor(cx);
    let window_size = size(px(spec.size.0 * scale), px(spec.size.1 * scale));
    let min_base = spec.min_size.map(|(w, h)| size(px(w), px(h)));
    let options = WindowOptions {
        window_bounds: Some(opening_bounds(spec.bounds_key, display, window_size, cx)),
        display_id: display,
        kind: WindowKind::Normal,
        is_resizable: spec.resizable,
        // The opening minimum; the runtime one below replaces it and follows later scale
        // changes (kept for Wayland, where the runtime one is a no-op).
        window_min_size: min_base
            .map(|m| windowing::scaled_min_size(m, mmcl_ui_kit::scale::percent(cx))),
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
            let (modal, bounds_key) = (spec.modal, spec.bounds_key);
            let _ = handle.update(cx, |_, window, cx| {
                window.set_window_title(&title);
                mmcl_ui::theme::install_theme_on(window, cx);
                if let Some(owner) = owner_native {
                    let link = OwnerLink::attach(window, owner, modal).shared();
                    register_link(window.window_handle().window_id(), link, cx);
                }
                // The default close: release the owner, then close. Dialogs with their own
                // handler replace this one and call `release_owner` themselves.
                window.on_window_should_close(cx, |window, cx| {
                    release_owner(window, cx);
                    true
                });
                if let Some(base) = min_base {
                    windowing::apply_scaled_min_size(window, base, cx);
                    cx.observe_global_in::<mmcl_ui_kit::scale::UiScale>(
                        window,
                        move |_, window, cx| {
                            windowing::apply_scaled_min_size(window, base, cx);
                        },
                    )
                    .detach();
                }
                if let Some(key) = bounds_key {
                    windowing::track_bounds(key, window, cx).detach();
                }
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

/// A file dialog that is already showing; resolves to the chosen path.
pub type PathFuture = Pin<Box<dyn Future<Output = Option<PathBuf>>>>;

/// Shows a save dialog parented on the launcher window [`host_window`] picks (unparented when
/// there is none). Call it synchronously, then await the future.
pub fn save_dialog(dialog: SaveDialog, cx: &mut App) -> PathFuture {
    if let Some(host) = host_window(cx, None)
        && let Ok(fut) = host.update(cx, |_, window, _| -> PathFuture {
            Box::pin(dialog.clone().save(Some(window)))
        })
    {
        return fut;
    }
    Box::pin(dialog.save(None))
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
        let owner = Owner::of(window, cx);
        quick_start::show_quick_start_if_needed(e.clone(), owner, cx);
    });
    let e = engine.clone();
    hb::set_open_detail_handler(cx, move |id, window, cx| {
        detail::show_pack_detail(e.clone(), id, window, cx);
    });
    hb::set_confirm_import_handler(cx, |request, cx| {
        import_confirm::confirm_import(request, Owner::Main, cx)
    });
    hb::set_confirm_import_preview_handler(cx, |preview, cx| {
        import_confirm::confirm_preview(preview, Owner::Main, cx)
    });
    let e = engine.clone();
    hb::set_play_handler(cx, move |id, cx| {
        account_prompt::start_play(e.clone(), id, mmcl_engine::LaunchOptions::default(), cx);
    });
}

#[cfg(test)]
mod tests {
    use std::path::Path;

    use super::*;

    #[test]
    fn no_handle_means_the_main_window() {
        assert!(matches!(Owner::from(None), Owner::Main));
        assert!(matches!(Owner::default(), Owner::Main));
    }

    #[gpui::test]
    fn remembered_bounds_win_over_centring(cx: &mut gpui::TestAppContext) {
        cx.update(|cx| {
            let default = size(px(600.0), px(520.0));
            let first = opening_bounds(Some("dialog-test"), None, default, cx);
            assert_eq!(first.get_bounds().size, default);
            let saved = Bounds::new(gpui::point(px(10.0), px(20.0)), size(px(700.0), px(560.0)));
            cx.default_global::<BoundsMemory>()
                .set("dialog-test", WindowBounds::Windowed(saved));
            let again = opening_bounds(Some("dialog-test"), None, default, cx);
            assert_eq!(again.get_bounds().size, saved.size);
            // Without a key the remembered bounds are never used.
            assert_eq!(
                opening_bounds(None, None, default, cx).get_bounds().size,
                default
            );
        });
    }

    /// Releasing a window that has no link is a no-op (closing an unowned dialog).
    #[gpui::test]
    fn releasing_an_unknown_window_is_harmless(cx: &mut gpui::TestAppContext) {
        let window = cx.add_empty_window();
        window.update(|window, cx| {
            release_owner(window, cx);
            release_owner(window, cx);
        });
    }

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
