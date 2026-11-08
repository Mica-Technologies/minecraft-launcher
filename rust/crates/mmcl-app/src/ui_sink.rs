// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The logger's dialog side (`mmcl_log::UiSink`, Java `Logger.logError` showing a dialog).
//!
//! The blocking variants wait for the dialog only when called off the GPUI thread; on the GPUI
//! thread they post the dialog and return, because waiting there would freeze the run loop
//! that has to draw it.

use std::sync::OnceLock;
use std::sync::mpsc;
use std::thread::ThreadId;

use mmcl_log::UiSink;
use mmcl_ui::GuiHandle;

static GUI_THREAD: OnceLock<ThreadId> = OnceLock::new();

/// Records the calling thread as the GPUI thread (call from inside `Application::run`).
pub fn mark_gui_thread() {
    let _ = GUI_THREAD.set(std::thread::current().id());
}

fn on_gui_thread() -> bool {
    GUI_THREAD
        .get()
        .is_some_and(|id| *id == std::thread::current().id())
}

/// The installed sink.
pub struct GuiUiSink {
    pub gui: GuiHandle,
}

#[derive(Clone, Copy)]
enum Kind {
    Plain,
    Multiline,
}

impl GuiUiSink {
    fn show(&self, kind: Kind, msg: &str, wait: bool) {
        let msg = msg.to_string();
        let (tx, rx) = mpsc::channel::<()>();
        let posted = self.gui.run(move |cx| {
            cx.spawn(async move |cx| {
                match kind {
                    Kind::Plain => mmcl_ui::dialogs::error(cx, &msg, None).await,
                    Kind::Multiline => mmcl_ui::dialogs::error_multiline(cx, &msg, None).await,
                }
                let _ = tx.send(());
            })
            .detach();
        });
        if posted && wait && !on_gui_thread() {
            let _ = rx.recv();
        }
    }
}

impl UiSink for GuiUiSink {
    fn error_modal(&self, msg: &str) {
        self.show(Kind::Plain, msg, true);
    }

    fn error_async(&self, msg: &str) {
        self.show(Kind::Plain, msg, false);
    }

    fn error_multiline(&self, msg: &str) {
        self.show(Kind::Multiline, msg, true);
    }

    fn error_retry(&self, msg: &str, retry: &str) -> bool {
        let (msg, retry) = (msg.to_string(), retry.to_string());
        let (tx, rx) = mpsc::channel::<bool>();
        let posted = self.gui.run(move |cx| {
            cx.spawn(async move |cx| {
                let again = mmcl_ui::dialogs::error_retry(cx, &msg, &retry, None).await;
                let _ = tx.send(again);
            })
            .detach();
        });
        // On the GPUI thread the answer cannot be awaited; no retry.
        if !posted || on_gui_thread() {
            return false;
        }
        rx.recv().unwrap_or(false)
    }

    fn warn_toast(&self, title: &str, msg: &str) {
        mmcl_ui_kit::notify::warn(title, msg);
    }

    fn has_window(&self) -> bool {
        self.gui.is_started()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_the_marked_thread_is_the_gui_thread() {
        mark_gui_thread();
        assert!(on_gui_thread());
        let other = std::thread::spawn(on_gui_thread).join().unwrap();
        assert!(!other);
    }
}
