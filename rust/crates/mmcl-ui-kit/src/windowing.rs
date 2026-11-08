// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Secondary-window behaviour GPUI 0.3.11 lacks (JavaFX `Stage` features):
//!
//! - [`OwnerLink`]: an owned window (stays above its owner, minimises with it) and optionally
//!   a modal one (the owner takes no input while it is open) — `initOwner` / `initModality`.
//! - [`set_min_size`] / [`apply_scaled_min_size`]: a minimum window size that can change while
//!   the window is open, so it follows the interface scale.
//! - [`BoundsMemory`]: a secondary window reopens where and as large as it was when it closed
//!   (GPUI has no hide, so closing forgets the bounds).
//! - [`WindowDragExt::window_drag_region`]: a custom title bar / navbar that drags the window.
//!
//! The native work is in `mmcl_platform::window`; this module adds the GPUI glue.
//!
//! Opening an owned modal window:
//!
//! ```ignore
//! let owner = windowing::native(window); // in the owner's handler, before open_window
//! cx.open_window(options(windowing::recall_or_centered(cx, "help", size)), move |window, cx| {
//!     let link = OwnerLink::attach(window, owner, true).shared();
//!     link.release_on_close(window, cx);
//!     let _bounds = windowing::track_bounds("help", window, cx); // keep alive in the view
//!     cx.new(|cx| HelpView::new(link, window, cx))
//! });
//! ```

use std::cell::RefCell;
use std::collections::HashMap;
use std::rc::Rc;

use gpui::{
    App, Bounds, Context, Global, InteractiveElement, MouseButton, Pixels, SharedString, Size,
    Subscription, Window, WindowBounds, WindowControlArea, WindowId, point, px, size,
};
pub use mmcl_platform::handle::NativeHandle;
use mmcl_platform::window::{LogicalSize, WindowLink};

/// The native handle of a GPUI window (capture an owner's handle with this before opening the
/// child: inside the owner's own update the owner window cannot be borrowed again).
pub fn native(window: &Window) -> NativeHandle {
    mmcl_platform::handle::native_handle(window)
}

// ---------------------------------------------------------------------------------------------
// Owned and modal windows
// ---------------------------------------------------------------------------------------------

/// A secondary window's tie to its owner. Keep it in the child's root view; release it when the
/// child closes ([`SharedOwnerLink::release_on_close`] does that for windows without a
/// close handler of their own; windows with one call [`OwnerLink::release`] in it right
/// before returning `true`). Dropping it releases too, but on Windows only a release **before**
/// the child is destroyed hands the activation back to the owner cleanly.
#[derive(Debug)]
pub struct OwnerLink(WindowLink);

impl OwnerLink {
    /// Makes `child` owned by the window behind `owner`, and modal over it when `modal`.
    pub fn attach(child: &Window, owner: NativeHandle, modal: bool) -> OwnerLink {
        OwnerLink(WindowLink::new(native(child), owner, modal))
    }

    /// No owner (an inert link, for windows opened without one).
    pub fn none() -> OwnerLink {
        OwnerLink(WindowLink::none())
    }

    /// Whether the native relationship is in place.
    pub fn is_active(&self) -> bool {
        self.0.is_active()
    }

    /// Whether the link was asked to be modal.
    pub fn is_modal(&self) -> bool {
        self.0.is_modal()
    }

    /// Undoes it (re-enables a modal owner). Safe to call more than once.
    pub fn release(&mut self) {
        self.0.release();
    }

    /// A clonable handle, for sharing between the view and a close hook.
    pub fn shared(self) -> SharedOwnerLink {
        SharedOwnerLink(Rc::new(RefCell::new(self)))
    }
}

/// A shared [`OwnerLink`].
#[derive(Debug, Clone)]
pub struct SharedOwnerLink(Rc<RefCell<OwnerLink>>);

impl SharedOwnerLink {
    /// Releases the link.
    pub fn release(&self) {
        self.0.borrow_mut().release();
    }

    /// Whether the native relationship is in place.
    pub fn is_active(&self) -> bool {
        self.0.borrow().is_active()
    }

    /// Installs a `should_close` handler that releases the link and lets the window close.
    /// GPUI keeps **one** such handler per window, so a window that needs its own (an unsaved
    /// changes prompt) calls [`SharedOwnerLink::release`] from it instead.
    pub fn release_on_close(&self, window: &Window, cx: &App) {
        let link = self.clone();
        window.on_window_should_close(cx, move |_, _| {
            link.release();
            true
        });
    }
}

// ---------------------------------------------------------------------------------------------
// Runtime minimum size
// ---------------------------------------------------------------------------------------------

/// The last minimum applied per window, so calling from `render` stays cheap.
#[derive(Default)]
struct MinSizes(HashMap<WindowId, (f32, f32)>);

impl Global for MinSizes {}

/// `base` (the minimum at 100 %) scaled to an interface-scale percent. Pure.
pub fn scaled_min_size(base: Size<Pixels>, percent: i32) -> Size<Pixels> {
    let factor = crate::scale::nearest(percent) as f32 / 100.0;
    size(base.width * factor, base.height * factor)
}

/// Sets the window's minimum content size now (logical pixels), replacing GPUI's
/// `window_min_size`; the window grows when it is smaller. Returns whether it applied.
pub fn set_min_size(window: &Window, min: Size<Pixels>) -> bool {
    mmcl_platform::window::set_min_content_size(
        native(window),
        Some(LogicalSize::new(
            f64::from(f32::from(min.width)),
            f64::from(f32::from(min.height)),
        )),
        f64::from(window.scale_factor()),
    )
}

/// Applies `base_at_100` scaled by the current interface scale, only when it changed since the
/// last call for this window (safe to call from every `render`, and again after
/// `scale::set_percent`).
pub fn apply_scaled_min_size(window: &Window, base_at_100: Size<Pixels>, cx: &mut App) -> bool {
    let min = scaled_min_size(base_at_100, crate::scale::percent(cx));
    let key = (f32::from(min.width), f32::from(min.height));
    let id = window.window_handle().window_id();
    if cx.default_global::<MinSizes>().0.get(&id) == Some(&key) {
        return true;
    }
    let ok = set_min_size(window, min);
    if ok {
        cx.global_mut::<MinSizes>().0.insert(id, key);
    }
    ok
}

// ---------------------------------------------------------------------------------------------
// Bounds memory
// ---------------------------------------------------------------------------------------------

/// The last bounds of each secondary window, by a caller-chosen key (`"help"`,
/// `"runningGames"`, ...), for this session.
#[derive(Debug, Default)]
pub struct BoundsMemory(HashMap<SharedString, WindowBounds>);

impl Global for BoundsMemory {}

impl BoundsMemory {
    /// The remembered bounds for `key`.
    pub fn get(&self, key: &str) -> Option<WindowBounds> {
        self.0.get(key).copied()
    }

    /// Remembers `bounds` for `key`. Zero-sized bounds (a window torn down before layout) are
    /// ignored.
    pub fn set(&mut self, key: impl Into<SharedString>, bounds: WindowBounds) {
        let b = bounds.get_bounds();
        if b.size.width > px(0.0) && b.size.height > px(0.0) {
            self.0.insert(key.into(), bounds);
        }
    }
}

/// Remembers the window's current bounds (windowed, maximised or fullscreen with its restore
/// bounds) under `key`.
pub fn remember_bounds(key: impl Into<SharedString>, window: &Window, cx: &mut App) {
    let bounds = window.window_bounds();
    cx.default_global::<BoundsMemory>().set(key, bounds);
}

/// Keeps `key`'s remembered bounds current while the window moves and resizes. Hold the
/// subscription in the window's root view.
pub fn track_bounds<T: 'static>(
    key: impl Into<SharedString>,
    window: &mut Window,
    cx: &mut Context<T>,
) -> Subscription {
    let key: SharedString = key.into();
    remember_bounds(key.clone(), window, cx);
    cx.observe_window_bounds(window, move |_, window, cx| {
        remember_bounds(key.clone(), window, cx);
    })
}

/// Moves remembered bounds back onto a screen: the screen it overlaps most (else the first) is
/// chosen, the size is capped to that screen, and the window is shifted so it lies fully
/// inside. With no screens known the bounds are returned unchanged. Pure.
pub fn fit_on_screen(saved: Bounds<Pixels>, screens: &[Bounds<Pixels>]) -> Bounds<Pixels> {
    let area =
        |b: Bounds<Pixels>| f32::from(b.size.width).max(0.0) * f32::from(b.size.height).max(0.0);
    let Some(screen) = screens
        .iter()
        .copied()
        .map(|s| {
            (
                s,
                if s.intersects(&saved) {
                    area(s.intersect(&saved))
                } else {
                    0.0
                },
            )
        })
        .fold(None::<(Bounds<Pixels>, f32)>, |best, (s, a)| match best {
            Some((_, ba)) if ba >= a => best,
            _ => Some((s, a)),
        })
        .map(|(s, _)| s)
    else {
        return saved;
    };
    let w = saved.size.width.min(screen.size.width);
    let h = saved.size.height.min(screen.size.height);
    let max_x = screen.origin.x + screen.size.width - w;
    let max_y = screen.origin.y + screen.size.height - h;
    let x = saved.origin.x.max(screen.origin.x).min(max_x);
    let y = saved.origin.y.max(screen.origin.y).min(max_y);
    Bounds::new(point(x, y), size(w, h))
}

/// The bounds to open `key`'s window with: the remembered ones fitted onto today's screens, or
/// `default_size` centred on the primary display.
pub fn recall_or_centered(cx: &App, key: &str, default_size: Size<Pixels>) -> WindowBounds {
    let Some(saved) = cx.try_global::<BoundsMemory>().and_then(|m| m.get(key)) else {
        return WindowBounds::Windowed(Bounds::centered(None, default_size, cx));
    };
    let screens: Vec<Bounds<Pixels>> = cx.displays().iter().map(|d| d.visible_bounds()).collect();
    match saved {
        WindowBounds::Windowed(b) => WindowBounds::Windowed(fit_on_screen(b, &screens)),
        WindowBounds::Maximized(b) => WindowBounds::Maximized(fit_on_screen(b, &screens)),
        WindowBounds::Fullscreen(b) => WindowBounds::Fullscreen(fit_on_screen(b, &screens)),
    }
}

// ---------------------------------------------------------------------------------------------
// Drag region
// ---------------------------------------------------------------------------------------------

/// What a press in a drag region does.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DragGesture {
    /// Start moving the window.
    Move,
    /// Title-bar double click: zoom (Linux) or the user's macOS preference.
    DoubleClick,
    /// Open the window-manager menu (Linux client-side decorations).
    WindowMenu,
    /// Nothing.
    None,
}

/// The gesture for a mouse press. Pure.
pub fn drag_gesture(button: MouseButton, click_count: usize) -> DragGesture {
    match button {
        MouseButton::Left if click_count >= 2 => DragGesture::DoubleClick,
        MouseButton::Left => DragGesture::Move,
        MouseButton::Right if cfg!(target_os = "linux") => DragGesture::WindowMenu,
        _ => DragGesture::None,
    }
}

/// Makes an element drag its window, for a custom title bar or navbar.
///
/// Windows: the element becomes a caption hit-test area (`HTCAPTION`), so the OS drags, snaps
/// and double-click-maximises natively. macOS and Linux: a left press starts
/// `start_window_move`, a double click zooms (macOS honours the user's title-bar double-click
/// setting) and, on Linux, a right press opens the window menu. Put buttons and other controls
/// on top as children: their own handlers should stop propagation of mouse-down.
pub trait WindowDragExt: InteractiveElement + Sized {
    /// Turns this element into a window drag region.
    fn window_drag_region(self) -> Self {
        let el = self.window_control_area(WindowControlArea::Drag);
        #[cfg(not(windows))]
        let el = el
            .on_mouse_down(MouseButton::Left, |ev, window, _| {
                match drag_gesture(MouseButton::Left, ev.click_count) {
                    DragGesture::DoubleClick => {
                        #[cfg(target_os = "macos")]
                        window.titlebar_double_click();
                        #[cfg(not(target_os = "macos"))]
                        window.zoom_window();
                    }
                    DragGesture::Move => window.start_window_move(),
                    _ => {}
                }
            })
            .on_mouse_down(MouseButton::Right, |ev, window, _| {
                if drag_gesture(MouseButton::Right, ev.click_count) == DragGesture::WindowMenu {
                    window.show_window_menu(ev.position);
                }
            });
        el
    }
}

impl<E: InteractiveElement + Sized> WindowDragExt for E {}

#[cfg(test)]
mod tests {
    use super::*;

    fn b(x: f32, y: f32, w: f32, h: f32) -> Bounds<Pixels> {
        Bounds::new(point(px(x), px(y)), size(px(w), px(h)))
    }

    #[test]
    fn min_sizes_follow_the_interface_scale() {
        let base = size(px(800.0), px(600.0));
        assert_eq!(scaled_min_size(base, 100), base);
        assert_eq!(scaled_min_size(base, 150), size(px(1200.0), px(900.0)));
        assert_eq!(scaled_min_size(base, 50), size(px(400.0), px(300.0)));
        assert_eq!(
            scaled_min_size(base, 120),
            scaled_min_size(base, 125),
            "snapped to the offered percents"
        );
    }

    #[test]
    fn bounds_on_a_screen_stay_put() {
        let screens = [b(0.0, 0.0, 1920.0, 1080.0)];
        let saved = b(100.0, 100.0, 800.0, 600.0);
        assert_eq!(fit_on_screen(saved, &screens), saved);
        assert_eq!(fit_on_screen(saved, &[]), saved, "no screens: unchanged");
    }

    #[test]
    fn off_screen_bounds_come_back() {
        let screens = [b(0.0, 0.0, 1920.0, 1080.0), b(1920.0, 0.0, 1280.0, 1024.0)];
        // Hanging off the right edge of the second screen.
        assert_eq!(
            fit_on_screen(b(3000.0, 500.0, 800.0, 600.0), &screens),
            b(2400.0, 424.0, 800.0, 600.0)
        );
        // On a monitor that is gone: moved onto the first screen.
        assert_eq!(
            fit_on_screen(b(-3000.0, -2000.0, 800.0, 600.0), &screens),
            b(0.0, 0.0, 800.0, 600.0)
        );
        // Larger than the screen: capped.
        assert_eq!(
            fit_on_screen(b(10.0, 10.0, 4000.0, 3000.0), &screens[..1]),
            b(0.0, 0.0, 1920.0, 1080.0)
        );
    }

    #[test]
    fn the_screen_with_most_overlap_wins() {
        let screens = [b(0.0, 0.0, 1920.0, 1080.0), b(1920.0, 0.0, 1920.0, 1080.0)];
        // Mostly on the second screen, poking above it.
        assert_eq!(
            fit_on_screen(b(2000.0, -50.0, 800.0, 600.0), &screens),
            b(2000.0, 0.0, 800.0, 600.0)
        );
    }

    #[test]
    fn bounds_memory_ignores_empty_bounds() {
        let mut m = BoundsMemory::default();
        m.set("help", WindowBounds::Windowed(b(0.0, 0.0, 0.0, 0.0)));
        assert_eq!(m.get("help"), None);
        let max = WindowBounds::Maximized(b(5.0, 5.0, 900.0, 700.0));
        m.set("help", max);
        assert_eq!(m.get("help"), Some(max));
    }

    #[test]
    fn drag_gestures() {
        assert_eq!(drag_gesture(MouseButton::Left, 1), DragGesture::Move);
        assert_eq!(drag_gesture(MouseButton::Left, 2), DragGesture::DoubleClick);
        assert_eq!(drag_gesture(MouseButton::Middle, 1), DragGesture::None);
        let right = drag_gesture(MouseButton::Right, 1);
        if cfg!(target_os = "linux") {
            assert_eq!(right, DragGesture::WindowMenu);
        } else {
            assert_eq!(right, DragGesture::None);
        }
    }

    #[gpui::test]
    fn bounds_are_recalled_or_centred(cx: &mut gpui::TestAppContext) {
        cx.update(|cx| {
            let default = size(px(640.0), px(480.0));
            let first = recall_or_centered(cx, "help", default);
            assert_eq!(first.get_bounds().size, default);
            let saved = WindowBounds::Windowed(b(40.0, 40.0, 700.0, 500.0));
            cx.default_global::<BoundsMemory>().set("help", saved);
            let again = recall_or_centered(cx, "help", default);
            assert_eq!(again.get_bounds().size, size(px(700.0), px(500.0)));
        });
    }
}
