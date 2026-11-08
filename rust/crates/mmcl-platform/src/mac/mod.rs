// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! macOS integration: NSWindow appearance, the dock tile (progress, badge, attention) and
//! Notification Center. AppKit objects are touched on the main thread only; calls from other
//! threads hop to the main dispatch queue.

pub mod appearance;
pub mod dock;
pub mod notify;

use objc2::MainThreadMarker;

/// Runs `f` on the main thread: now when already there, else asynchronously on the main queue.
pub(crate) fn on_main(f: impl FnOnce(MainThreadMarker) + Send + 'static) {
    if let Some(mtm) = MainThreadMarker::new() {
        crate::guard(|| f(mtm));
        return;
    }
    dispatch2::DispatchQueue::main().exec_async(move || {
        if let Some(mtm) = MainThreadMarker::new() {
            crate::guard(|| f(mtm));
        }
    });
}
