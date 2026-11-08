// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The modpack editor (`MCLauncherModPackEditorGui`).
//!
//! | Module | What |
//! |---|---|
//! | [`screen`] | the GPUI screen ([`ModpackEditor`]): navbar, toolbar, tabs, bottom bar, dialogs |
//! | [`search_dialog`] | the Modrinth search dialog of the Mods tab |
//! | [`model`] | the editor state without GPUI: document, form, tables, dirty check |
//! | [`form`] | the metadata form and its document mapping |
//! | [`table`], [`row`] | one file-list tab's rows, view order, selection |
//! | [`loaders`] | Forge / NeoForge / Fabric version pickers |
//! | [`modrinth`] | Modrinth search, version resolution and SHA-512 verification |
//! | [`hashing`] | one-pass SHA-1 + SHA-256 + MD5 + SHA-512 |
//! | [`url_check`] | HEAD checks |
//! | [`diff`] | the Diff dialog text (line diff) |
//! | [`icons`] | Modrinth icon LRU and preview images |
//! | [`backend`] | the I/O seam ([`EditorBackend`]) and its engine implementation |
//!
//! Wiring (done by `mmcl-app`): `mmcl_editor::init(cx, EditorServices::new(backend))` once,
//! then `mmcl_editor::register(cx)` to put the screen in the [`ScreenRegistry`]. The Game
//! Library opens it with `Navigator::go_to(cx, ScreenRequest::with_payload(ScreenId::EDITOR,
//! pack_summary))` (a [`PackSummary`] or a [`PackId`] payload pre-loads that pack; no payload
//! opens a blank document).

pub mod backend;
pub mod diff;
pub mod form;
pub mod hashing;
pub mod icons;
pub mod loaders;
pub mod model;
pub mod modrinth;
pub mod row;
pub mod screen;
pub mod search_dialog;
pub mod table;
pub mod url_check;

use std::rc::Rc;
use std::sync::Arc;

use gpui::{App, AppContext as _, Entity, Global, Window};
use mmcl_engine::{PackId, PackSummary};
use mmcl_ui_kit::{HelpTopic, ScreenId, ScreenRegistry, ScreenRequest};

pub use backend::{EditorBackend, EngineBackend, NetError};
pub use model::EditorModel;
pub use screen::ModpackEditor;

/// Opens the help window on a topic (the shell's help window).
pub type HelpHandler = Rc<dyn Fn(HelpTopic, &mut Window, &mut App)>;
/// Quits the app through the shell's quit path (with its games-still-running check).
pub type QuitHandler = Rc<dyn Fn(&mut App)>;

/// What the editor needs from the app.
#[derive(Clone)]
pub struct EditorServices {
    /// Network and pack I/O.
    pub backend: Arc<dyn EditorBackend>,
    /// Opens the help window; `None` lets the shell draw its own corner help button.
    pub open_help: Option<HelpHandler>,
    /// The shell's `request_quit` (after the discard confirmation on window close); `None`
    /// quits the app directly.
    pub request_quit: Option<QuitHandler>,
}

impl EditorServices {
    /// Services over a backend, with no shell hooks.
    pub fn new(backend: Arc<dyn EditorBackend>) -> EditorServices {
        EditorServices {
            backend,
            open_help: None,
            request_quit: None,
        }
    }

    /// Sets the help hook.
    pub fn with_help(mut self, f: impl Fn(HelpTopic, &mut Window, &mut App) + 'static) -> Self {
        self.open_help = Some(Rc::new(f));
        self
    }

    /// Sets the quit hook.
    pub fn with_quit(mut self, f: impl Fn(&mut App) + 'static) -> Self {
        self.request_quit = Some(Rc::new(f));
        self
    }
}

impl Global for EditorServices {}

/// Installs the editor's services (once, at GUI start-up).
pub fn init(cx: &mut App, services: EditorServices) {
    cx.set_global(services);
}

/// The installed services. Panics when [`init`] was never called (a wiring bug).
pub fn services(cx: &App) -> EditorServices {
    cx.try_global::<EditorServices>()
        .cloned()
        .expect("mmcl_editor::init must run before the editor opens")
}

/// The pack the editor should pre-load, from a screen request payload.
pub fn initial_pack(req: &mut ScreenRequest, backend: &dyn EditorBackend) -> Option<PackSummary> {
    if let Some(p) = req.take_payload::<PackSummary>() {
        return Some(p);
    }
    if let Some(p) = req.take_payload::<Option<PackSummary>>() {
        return p;
    }
    req.take_payload::<PackId>()
        .and_then(|id| backend.pack(&id))
}

/// Registers the editor screen under [`ScreenId::EDITOR`].
pub fn register(cx: &mut App) {
    ScreenRegistry::register_view::<ModpackEditor>(cx, ScreenId::EDITOR, |mut req, window, cx| {
        let services = services(cx);
        let initial = initial_pack(&mut req, services.backend.as_ref());
        ModpackEditor::new(services, initial, window, cx)
    });
}

/// Builds an editor view directly (for hosts that do not go through the registry).
pub fn open_editor(
    window: &mut Window,
    cx: &mut App,
    initial: Option<PackSummary>,
) -> Entity<ModpackEditor> {
    let services = services(cx);
    cx.new(|cx| ModpackEditor::new(services, initial, window, cx))
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Every localization key the crate's source names resolves in the English bundle (Java
    /// bundle or the Rust overlay).
    #[test]
    fn every_key_in_the_source_resolves() {
        let re = regex::Regex::new(
            r#""((?:editor|dialog|log\.editor|common|tooltip|main)\.[A-Za-z0-9_.]+[A-Za-z0-9])""#,
        )
        .unwrap();
        let dir = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("src");
        let mut missing = Vec::new();
        let mut seen = 0;
        for entry in std::fs::read_dir(&dir).unwrap() {
            let path = entry.unwrap().path();
            if path.extension().is_none_or(|e| e != "rs") {
                continue;
            }
            let text = std::fs::read_to_string(&path).unwrap();
            for c in re.captures_iter(&text) {
                let key = &c[1];
                seen += 1;
                if mmcl_i18n::english(key).is_none() {
                    missing.push(format!("{}: {key}", path.display()));
                }
            }
        }
        assert!(seen > 100, "only {seen} keys found");
        assert!(missing.is_empty(), "missing keys: {missing:#?}");
    }

    #[test]
    fn payloads_select_the_initial_pack() {
        let b = backend::fake::FakeBackend::default();
        let mut req = ScreenRequest::new(ScreenId::EDITOR);
        assert!(initial_pack(&mut req, &b).is_none());
        let mut req = ScreenRequest::with_payload(ScreenId::EDITOR, PackId::new("x"));
        assert!(initial_pack(&mut req, &b).is_none());
    }
}
