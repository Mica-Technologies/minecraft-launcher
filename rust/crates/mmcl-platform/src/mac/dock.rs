// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The dock tile (`MacOsDockManager`): progress, badge and attention requests. The dock menu
//! itself goes through GPUI's `cx.set_dock_menu` from [`crate::menus::dock_menu`].
//!
//! Progress mirrors AWT: the dock tile gets a content view showing the app icon with a bar
//! along the bottom, then `display()`. The dock cannot draw an indeterminate bar cleanly, so
//! indeterminate shows as a static 50%. Clearing removes the content view.

use std::cell::RefCell;

use objc2::MainThreadMarker;
use objc2::rc::Retained;
use objc2_app_kit::{
    NSApplication, NSImageView, NSProgressIndicator, NSProgressIndicatorStyle,
    NSRequestUserAttentionType, NSView,
};
use objc2_foundation::{NSPoint, NSRect, NSSize, NSString};

use super::on_main;
use crate::progress::{Command, ProgressBackend, percent};
use mmcl_util::Progress;

struct Tile {
    view: Retained<NSImageView>,
    bar: Retained<NSProgressIndicator>,
}

thread_local! {
    // Main thread only.
    static TILE: RefCell<Option<Tile>> = const { RefCell::new(None) };
}

/// Dock progress `0..=100`, or `None` to clear.
pub fn set_progress(pct: Option<u64>) {
    on_main(move |mtm| {
        let app = NSApplication::sharedApplication(mtm);
        let tile = app.dockTile();
        match pct {
            None => {
                tile.setContentView(None);
                tile.display();
                TILE.with(|t| t.borrow_mut().take());
            }
            Some(p) => {
                TILE.with(|t| {
                    let mut t = t.borrow_mut();
                    if t.is_none() {
                        *t = make_tile(&app, tile.size(), mtm);
                    }
                    let Some(t) = t.as_ref() else {
                        tracing::warn!(
                            "{}",
                            mmcl_i18n::tf!("log.macDock.progressUpdateFailed", "no app icon")
                        );
                        return;
                    };
                    t.bar.setDoubleValue(p as f64);
                    let view: &NSView = &t.view;
                    tile.setContentView(Some(view));
                    tile.display();
                });
            }
        }
    });
}

fn make_tile(app: &NSApplication, size: NSSize, mtm: MainThreadMarker) -> Option<Tile> {
    let icon = app.applicationIconImage()?;
    let view = NSImageView::imageViewWithImage(&icon, mtm);
    view.setFrame(NSRect::new(NSPoint::new(0.0, 0.0), size));
    let frame = NSRect::new(
        NSPoint::new(size.width * 0.08, size.height * 0.06),
        NSSize::new(size.width * 0.84, size.height * 0.14),
    );
    let bar = NSProgressIndicator::initWithFrame(mtm.alloc(), frame);
    bar.setStyle(NSProgressIndicatorStyle::Bar);
    bar.setIndeterminate(false);
    bar.setMinValue(0.0);
    bar.setMaxValue(100.0);
    view.addSubview(&bar);
    Some(Tile { view, bar })
}

/// The dock badge (`Some("1")`) or none.
pub fn set_badge(label: Option<&str>) {
    let label = label.map(str::to_string);
    on_main(move |mtm| {
        let tile = NSApplication::sharedApplication(mtm).dockTile();
        let s = label.as_deref().map(NSString::from_str);
        tile.setBadgeLabel(s.as_deref());
        tile.display();
    });
}

/// Bounces the dock icon: `critical` until the user activates the app, else once.
pub fn request_attention(critical: bool) {
    on_main(move |mtm| {
        let kind = if critical {
            NSRequestUserAttentionType::CriticalRequest
        } else {
            NSRequestUserAttentionType::InformationalRequest
        };
        NSApplication::sharedApplication(mtm).requestUserAttention(kind);
    });
}

/// Removes the dock progress.
pub fn stop() {
    set_progress(None);
}

/// The error state: a full bar.
pub fn set_error() {
    set_progress(Some(100));
}

/// Removes the badge.
pub fn clear_badge() {
    set_badge(None);
}

/// Clears progress and badge. (The dock menu is the app's, through GPUI.)
pub fn shutdown() {
    stop();
    clear_badge();
}

/// The macOS progress backend for [`crate::progress`].
pub struct DockBackend;

impl ProgressBackend for DockBackend {
    fn apply(&mut self, cmd: Command) {
        match cmd {
            Command::Attach(_) => {}
            Command::Set(Progress::Fraction(f)) => set_progress(Some(percent(f))),
            Command::Set(Progress::Indeterminate) => set_progress(Some(50)),
            Command::Stop => set_progress(None),
            Command::FullError => {
                // Error = a full bar; the badge stays until the next session (the update
                // stays available).
                set_progress(Some(100));
                request_attention(false);
                set_badge(Some("1"));
            }
            Command::Shutdown => {
                set_progress(None);
                set_badge(None);
            }
        }
    }
}
