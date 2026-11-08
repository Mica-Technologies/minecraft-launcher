// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Gamepad-friendly focus navigation.
//!
//! Handheld / gamepad mode (Steam Deck, SteamOS, `--gamepad`) drives the launcher with a d-pad,
//! sticks and face buttons, so everything interactive must be reachable and operable without a
//! pointer. This module provides:
//!
//! - **Actions** ([`NavUp`], [`NavDown`], [`NavLeft`], [`NavRight`], [`NavNext`], [`NavPrev`],
//!   [`NavActivate`], [`NavBack`], [`NavMenu`]) and their key bindings ([`bind_nav_keys`]): arrows
//!   move spatially inside a [`NAV_CONTEXT`] scope, Tab / Shift-Tab walk the tab order.
//! - **Spatial navigation**: [`nav_scope`] wraps a screen root; [`nav_marker`] records each
//!   focusable element's bounds while it is laid out; the scope answers arrow actions by moving
//!   focus to the nearest target in that direction ([`nearest_in_direction`], pure and tested).
//! - **Activation**: [`Activatable::on_activate`] makes Enter, Space, a click and [`NavActivate`]
//!   (gamepad A) all run one handler.
//! - **Gamepad input mapping**: whatever polls the pad maps buttons through [`nav_for_button`]
//!   and held directions through [`AxisRepeater`], then calls [`dispatch_nav`].
//! - [`focus_ring`]: the visible keyboard/gamepad focus outline.

use std::collections::HashMap;
use std::rc::Rc;
use std::time::Duration;

use gpui::{
    App, Bounds, ClickEvent, Div, ElementId, FocusHandle, Global, Hsla, InteractiveElement,
    IntoElement, KeyBinding, KeyDownEvent, ParentElement, Pixels, Stateful,
    StatefulInteractiveElement, Styled, Window, WindowId, actions, canvas, div,
};

use crate::scale::u;
use crate::theme::{ThemeExt, hsla};

actions!(
    mmcl_nav,
    [
        /// Move focus up (d-pad / stick up, arrow up).
        NavUp,
        /// Move focus down.
        NavDown,
        /// Move focus left.
        NavLeft,
        /// Move focus right.
        NavRight,
        /// Next tab stop (Tab, right bumper).
        NavNext,
        /// Previous tab stop (Shift-Tab, left bumper).
        NavPrev,
        /// Activate the focused element (Enter, Space, gamepad A).
        NavActivate,
        /// Back / cancel (Escape, gamepad B).
        NavBack,
        /// Open the context or options menu (gamepad Start / Menu).
        NavMenu,
    ]
);

/// The key context spatial navigation is bound in. [`nav_scope`] sets it on its root.
pub const NAV_CONTEXT: &str = "MmclNav";

/// Binds the navigation keys. Arrows only act inside a [`NAV_CONTEXT`] scope and never inside a
/// text input (`!Input`), so caret movement keeps working.
pub fn bind_nav_keys(cx: &mut App) {
    let scoped = "MmclNav && !Input";
    cx.bind_keys([
        KeyBinding::new("up", NavUp, Some(scoped)),
        KeyBinding::new("down", NavDown, Some(scoped)),
        KeyBinding::new("left", NavLeft, Some(scoped)),
        KeyBinding::new("right", NavRight, Some(scoped)),
        KeyBinding::new("tab", NavNext, Some(NAV_CONTEXT)),
        KeyBinding::new("shift-tab", NavPrev, Some(NAV_CONTEXT)),
        KeyBinding::new("escape", NavBack, Some(scoped)),
    ]);
}

/// A direction for spatial navigation.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Direction {
    /// Towards smaller y.
    Up,
    /// Towards larger y.
    Down,
    /// Towards smaller x.
    Left,
    /// Towards larger x.
    Right,
}

/// A rectangle in logical pixels (plain data, so the algorithm is testable without GPUI).
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Rect {
    /// Left.
    pub x: f32,
    /// Top.
    pub y: f32,
    /// Width.
    pub w: f32,
    /// Height.
    pub h: f32,
}

impl Rect {
    /// A rectangle.
    pub const fn new(x: f32, y: f32, w: f32, h: f32) -> Self {
        Rect { x, y, w, h }
    }

    fn right(&self) -> f32 {
        self.x + self.w
    }

    fn bottom(&self) -> f32 {
        self.y + self.h
    }

    fn center(&self) -> (f32, f32) {
        (self.x + self.w / 2.0, self.y + self.h / 2.0)
    }
}

impl From<Bounds<Pixels>> for Rect {
    fn from(b: Bounds<Pixels>) -> Self {
        Rect::new(
            f32::from(b.origin.x),
            f32::from(b.origin.y),
            f32::from(b.size.width),
            f32::from(b.size.height),
        )
    }
}

/// The index of the best candidate to move to from `from` in `dir`, or `None`.
///
/// A candidate qualifies when it lies beyond `from`'s leading edge in that direction (a little
/// overlap is tolerated). Among those, the score favours small distance along the direction and
/// penalises sideways offset more heavily, with a bonus for candidates that overlap `from` on the
/// cross axis (so moving down from a button picks the one below it, not a nearer one off to the
/// side) - the usual TV/console focus-engine heuristic.
pub fn nearest_in_direction(from: Rect, candidates: &[Rect], dir: Direction) -> Option<usize> {
    const SLACK: f32 = 1.0;
    let (fx, fy) = from.center();
    let mut best: Option<(usize, f32)> = None;
    for (i, c) in candidates.iter().enumerate() {
        if *c == from {
            continue;
        }
        let (cx, cy) = c.center();
        let (along, cross, beyond, overlaps) = match dir {
            Direction::Up => (
                from.y - c.bottom(),
                (cx - fx).abs(),
                c.bottom() <= from.y + SLACK || cy < from.y,
                c.x < from.right() && c.right() > from.x,
            ),
            Direction::Down => (
                c.y - from.bottom(),
                (cx - fx).abs(),
                c.y >= from.bottom() - SLACK || cy > from.bottom(),
                c.x < from.right() && c.right() > from.x,
            ),
            Direction::Left => (
                from.x - c.right(),
                (cy - fy).abs(),
                c.right() <= from.x + SLACK || cx < from.x,
                c.y < from.bottom() && c.bottom() > from.y,
            ),
            Direction::Right => (
                c.x - from.right(),
                (cy - fy).abs(),
                c.x >= from.right() - SLACK || cx > from.right(),
                c.y < from.bottom() && c.bottom() > from.y,
            ),
        };
        if !beyond {
            continue;
        }
        let along = along.max(0.0);
        let mut score = along + cross * 2.0;
        if overlaps {
            score *= 0.5;
        }
        if best.is_none_or(|(_, s)| score < s) {
            best = Some((i, score));
        }
    }
    best.map(|(i, _)| i)
}

/// The target to focus when nothing is focused yet: the top-most, then left-most.
pub fn first_target(candidates: &[Rect]) -> Option<usize> {
    candidates
        .iter()
        .enumerate()
        .min_by(|(_, a), (_, b)| {
            (a.y, a.x)
                .partial_cmp(&(b.y, b.x))
                .unwrap_or(std::cmp::Ordering::Equal)
        })
        .map(|(i, _)| i)
}

/// Focus targets laid out this frame, per window.
#[derive(Default)]
struct NavRegistry {
    windows: HashMap<WindowId, Vec<(FocusHandle, Rect)>>,
}

impl Global for NavRegistry {}

/// An invisible child that records `handle`'s bounds for spatial navigation. Put it inside the
/// focusable element, which must be `.relative()` (the marker fills it absolutely).
pub fn nav_marker(handle: &FocusHandle) -> impl IntoElement {
    let handle = handle.clone();
    canvas(
        move |bounds, window, cx| {
            let id = window.window_handle().window_id();
            cx.default_global::<NavRegistry>()
                .windows
                .entry(id)
                .or_default()
                .push((handle, Rect::from(bounds)));
        },
        |_, _, _, _| {},
    )
    .absolute()
    .top_0()
    .left_0()
    .size_full()
}

/// Wraps a screen (or any region) so arrow keys and the d-pad move focus spatially among the
/// [`nav_marker`]s inside it, and Tab / bumpers walk the tab order.
pub fn nav_scope(id: impl Into<ElementId>) -> Stateful<Div> {
    div()
        .id(id)
        .key_context(NAV_CONTEXT)
        .relative()
        // First child: clears last frame's targets before the content records this frame's.
        .child(
            canvas(
                |_, window, cx| {
                    let id = window.window_handle().window_id();
                    cx.default_global::<NavRegistry>()
                        .windows
                        .insert(id, Vec::new());
                },
                |_, _, _, _| {},
            )
            .absolute()
            .size_0(),
        )
        .on_action(|_: &NavUp, window, cx| {
            move_focus(window, cx, Direction::Up);
        })
        .on_action(|_: &NavDown, window, cx| {
            move_focus(window, cx, Direction::Down);
        })
        .on_action(|_: &NavLeft, window, cx| {
            move_focus(window, cx, Direction::Left);
        })
        .on_action(|_: &NavRight, window, cx| {
            move_focus(window, cx, Direction::Right);
        })
        .on_action(|_: &NavNext, window, cx| window.focus_next(cx))
        .on_action(|_: &NavPrev, window, cx| window.focus_prev(cx))
}

/// Moves focus spatially in `dir` among the targets recorded in this window's last frame.
/// Returns whether focus moved.
pub fn move_focus(window: &mut Window, cx: &mut App, dir: Direction) -> bool {
    let id = window.window_handle().window_id();
    let targets: Vec<(FocusHandle, Rect)> = cx
        .try_global::<NavRegistry>()
        .and_then(|r| r.windows.get(&id))
        .cloned()
        .unwrap_or_default();
    if targets.is_empty() {
        return false;
    }
    let rects: Vec<Rect> = targets.iter().map(|(_, r)| *r).collect();
    let current = targets.iter().position(|(h, _)| h.is_focused(window));
    let next = match current {
        Some(i) => nearest_in_direction(rects[i], &rects, dir),
        None => first_target(&rects),
    };
    match next {
        Some(i) => {
            window.focus(&targets[i].0, cx);
            true
        }
        None => false,
    }
}

/// A child element id under `parent` (for keyed state and animations of a component).
pub fn child_id(parent: &ElementId, name: impl Into<gpui::SharedString>) -> ElementId {
    ElementId::NamedChild(std::sync::Arc::new(parent.clone()), name.into())
}

/// A stable focus handle for a stateless (`RenderOnce`) component, kept for as long as the
/// element keeps rendering under the same id.
pub fn focus_handle_for(id: &ElementId, window: &mut Window, cx: &mut App) -> FocusHandle {
    window
        .use_keyed_state(child_id(id, "mmcl-focus"), cx, |_, cx| {
            cx.focus_handle().tab_stop(true)
        })
        .read(cx)
        .clone()
}

/// One handler for click, Enter, Space and gamepad A.
pub type ActivateHandler = Rc<dyn Fn(&mut Window, &mut App)>;

/// Activation on any stateful element.
pub trait Activatable: InteractiveElement + StatefulInteractiveElement + Sized {
    /// Runs `handler` on click, Enter, Space or [`NavActivate`] while focused.
    fn on_activate(self, handler: ActivateHandler) -> Self {
        let on_click = handler.clone();
        let on_key = handler.clone();
        let on_nav = handler;
        self.on_click(move |_: &ClickEvent, window, cx| on_click(window, cx))
            .on_key_down(move |event: &KeyDownEvent, window, cx| {
                let key = event.keystroke.key.as_str();
                if (key == "enter" || key == "space") && !event.keystroke.modifiers.modified() {
                    cx.stop_propagation();
                    on_key(window, cx);
                }
            })
            .on_action(move |_: &NavActivate, window, cx| on_nav(window, cx))
    }
}

impl<E: InteractiveElement + StatefulInteractiveElement> Activatable for E {}

/// The visible focus outline colour (`-md-primary`, as the Java `:focus-visible` border).
pub fn focus_color(cx: &App) -> Hsla {
    hsla(cx.tokens().md_primary)
}

/// Adds a 2 px primary outline while `handle` has keyboard/gamepad focus.
pub fn focus_ring<E: Styled + InteractiveElement>(el: E, cx: &App) -> E {
    let color = focus_color(cx);
    el.border_2()
        .border_color(gpui::transparent_black())
        .focus_visible(move |s| s.border_color(color))
}

/// Standard gamepad buttons (SDL / Xbox layout names), as any pad poller reports them.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum GamepadButton {
    /// A / Cross.
    South,
    /// B / Circle.
    East,
    /// X / Square.
    West,
    /// Y / Triangle.
    North,
    /// D-pad up.
    DPadUp,
    /// D-pad down.
    DPadDown,
    /// D-pad left.
    DPadLeft,
    /// D-pad right.
    DPadRight,
    /// Left bumper.
    LeftBumper,
    /// Right bumper.
    RightBumper,
    /// Start / Menu.
    Start,
    /// Select / View / Back.
    Select,
}

/// A navigation input, independent of the device that produced it.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum NavInput {
    /// Spatial move.
    Move(Direction),
    /// Next tab stop.
    Next,
    /// Previous tab stop.
    Prev,
    /// Activate.
    Activate,
    /// Back / cancel.
    Back,
    /// Menu.
    Menu,
}

/// Maps a gamepad button to a navigation input (`None` for buttons the launcher leaves free).
pub const fn nav_for_button(button: GamepadButton) -> Option<NavInput> {
    Some(match button {
        GamepadButton::South => NavInput::Activate,
        GamepadButton::East => NavInput::Back,
        GamepadButton::DPadUp => NavInput::Move(Direction::Up),
        GamepadButton::DPadDown => NavInput::Move(Direction::Down),
        GamepadButton::DPadLeft => NavInput::Move(Direction::Left),
        GamepadButton::DPadRight => NavInput::Move(Direction::Right),
        GamepadButton::LeftBumper => NavInput::Prev,
        GamepadButton::RightBumper => NavInput::Next,
        GamepadButton::Start => NavInput::Menu,
        GamepadButton::West | GamepadButton::North | GamepadButton::Select => return None,
    })
}

/// Dispatches a navigation input to the focused element (or the window root when nothing is
/// focused), exactly as the matching key would.
pub fn dispatch_nav(window: &mut Window, cx: &mut App, input: NavInput) {
    let action: Box<dyn gpui::Action> = match input {
        NavInput::Move(Direction::Up) => Box::new(NavUp),
        NavInput::Move(Direction::Down) => Box::new(NavDown),
        NavInput::Move(Direction::Left) => Box::new(NavLeft),
        NavInput::Move(Direction::Right) => Box::new(NavRight),
        NavInput::Next => Box::new(NavNext),
        NavInput::Prev => Box::new(NavPrev),
        NavInput::Activate => Box::new(NavActivate),
        NavInput::Back => Box::new(NavBack),
        NavInput::Menu => Box::new(NavMenu),
    };
    window.dispatch_action(action, cx);
}

/// Turns a held analog stick (or a held d-pad) into repeated moves: one move when the stick
/// crosses the dead zone, then after [`AxisRepeater::INITIAL_DELAY`] one every
/// [`AxisRepeater::REPEAT`] while held. Feed it every poll with the elapsed time.
#[derive(Debug, Clone, Default)]
pub struct AxisRepeater {
    held: Option<Direction>,
    held_for: Duration,
    since_repeat: Duration,
    repeating: bool,
}

impl AxisRepeater {
    /// Stick deflection (0..1) that counts as a direction.
    pub const DEAD_ZONE: f32 = 0.5;
    /// Hold time before repeating starts.
    pub const INITIAL_DELAY: Duration = Duration::from_millis(400);
    /// Time between repeated moves.
    pub const REPEAT: Duration = Duration::from_millis(120);

    /// A repeater with nothing held.
    pub fn new() -> Self {
        Self::default()
    }

    /// The direction a stick position points, if outside the dead zone (dominant axis wins;
    /// `y` grows downwards).
    pub fn direction_of(x: f32, y: f32) -> Option<Direction> {
        if x.abs() < Self::DEAD_ZONE && y.abs() < Self::DEAD_ZONE {
            return None;
        }
        Some(if x.abs() >= y.abs() {
            if x > 0.0 {
                Direction::Right
            } else {
                Direction::Left
            }
        } else if y > 0.0 {
            Direction::Down
        } else {
            Direction::Up
        })
    }

    /// Updates with the current direction (`None` = released) after `dt`; returns a move to
    /// perform now, if any.
    pub fn update(&mut self, dir: Option<Direction>, dt: Duration) -> Option<Direction> {
        if dir != self.held {
            self.held = dir;
            self.held_for = Duration::ZERO;
            self.since_repeat = Duration::ZERO;
            self.repeating = false;
            return dir;
        }
        let dir = dir?;
        self.held_for += dt;
        if !self.repeating {
            if self.held_for >= Self::INITIAL_DELAY {
                self.repeating = true;
                self.since_repeat = Duration::ZERO;
                return Some(dir);
            }
            return None;
        }
        self.since_repeat += dt;
        if self.since_repeat >= Self::REPEAT {
            self.since_repeat -= Self::REPEAT;
            return Some(dir);
        }
        None
    }

    /// Feeds a stick position.
    pub fn update_axis(&mut self, x: f32, y: f32, dt: Duration) -> Option<Direction> {
        self.update(Self::direction_of(x, y), dt)
    }
}

/// The minimum touch/gamepad target size (px) the kit's controls keep in handheld mode.
pub const MIN_TARGET: f32 = 40.0;

/// A spacer helper so focus rings never clip: wrap a control in this when its parent clips.
pub fn ring_room() -> Div {
    div().p(u(2.0))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn grid() -> Vec<Rect> {
        // 3 x 2 grid of 100x40 buttons, 20 px gaps.
        let mut v = Vec::new();
        for row in 0..2 {
            for col in 0..3 {
                v.push(Rect::new(
                    col as f32 * 120.0,
                    row as f32 * 60.0,
                    100.0,
                    40.0,
                ));
            }
        }
        v
    }

    #[test]
    fn moves_through_a_grid() {
        let g = grid();
        assert_eq!(nearest_in_direction(g[0], &g, Direction::Right), Some(1));
        assert_eq!(nearest_in_direction(g[1], &g, Direction::Right), Some(2));
        assert_eq!(nearest_in_direction(g[2], &g, Direction::Right), None);
        assert_eq!(nearest_in_direction(g[0], &g, Direction::Down), Some(3));
        assert_eq!(nearest_in_direction(g[4], &g, Direction::Up), Some(1));
        assert_eq!(nearest_in_direction(g[5], &g, Direction::Left), Some(4));
        assert_eq!(nearest_in_direction(g[3], &g, Direction::Left), None);
        assert_eq!(nearest_in_direction(g[0], &g, Direction::Up), None);
    }

    #[test]
    fn prefers_aligned_over_nearer_offset() {
        let from = Rect::new(0.0, 0.0, 100.0, 40.0);
        let candidates = [
            from,
            // Directly below, further away.
            Rect::new(0.0, 120.0, 100.0, 40.0),
            // Closer but far off to the side.
            Rect::new(400.0, 60.0, 100.0, 40.0),
        ];
        assert_eq!(
            nearest_in_direction(from, &candidates, Direction::Down),
            Some(1)
        );
    }

    #[test]
    fn first_target_is_top_left() {
        let g = grid();
        let mut shuffled = g.clone();
        shuffled.reverse();
        assert_eq!(shuffled[first_target(&shuffled).unwrap()], g[0]);
        assert_eq!(first_target(&[]), None);
    }

    #[test]
    fn buttons_map_to_inputs() {
        assert_eq!(
            nav_for_button(GamepadButton::South),
            Some(NavInput::Activate)
        );
        assert_eq!(nav_for_button(GamepadButton::East), Some(NavInput::Back));
        assert_eq!(
            nav_for_button(GamepadButton::DPadLeft),
            Some(NavInput::Move(Direction::Left))
        );
        assert_eq!(
            nav_for_button(GamepadButton::RightBumper),
            Some(NavInput::Next)
        );
        assert_eq!(nav_for_button(GamepadButton::North), None);
    }

    #[test]
    fn stick_direction_and_dead_zone() {
        assert_eq!(AxisRepeater::direction_of(0.2, 0.3), None);
        assert_eq!(AxisRepeater::direction_of(0.9, 0.3), Some(Direction::Right));
        assert_eq!(AxisRepeater::direction_of(-0.9, 0.3), Some(Direction::Left));
        assert_eq!(AxisRepeater::direction_of(0.1, -0.8), Some(Direction::Up));
        assert_eq!(AxisRepeater::direction_of(0.1, 0.8), Some(Direction::Down));
    }

    #[test]
    fn held_direction_repeats_after_a_delay() {
        let mut r = AxisRepeater::new();
        let tick = Duration::from_millis(20);
        assert_eq!(r.update(Some(Direction::Down), tick), Some(Direction::Down));
        let mut moves = 0;
        // 380 ms more: still inside the initial delay.
        for _ in 0..19 {
            if r.update(Some(Direction::Down), tick).is_some() {
                moves += 1;
            }
        }
        assert_eq!(moves, 0);
        // Crossing 400 ms starts repeating; then one every 120 ms.
        assert_eq!(r.update(Some(Direction::Down), tick), Some(Direction::Down));
        let mut repeats = 0;
        for _ in 0..30 {
            // 600 ms
            if r.update(Some(Direction::Down), tick).is_some() {
                repeats += 1;
            }
        }
        assert_eq!(repeats, 5);
        // Release, then a new direction moves at once.
        assert_eq!(r.update(None, tick), None);
        assert_eq!(r.update(Some(Direction::Left), tick), Some(Direction::Left));
    }
}
