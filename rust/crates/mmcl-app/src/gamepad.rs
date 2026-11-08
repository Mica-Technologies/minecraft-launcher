// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Gamepad navigation: a `gilrs` poller on its own thread, mapped through `mmcl-ui-kit`'s
//! [`nav_for_button`] and [`AxisRepeater`], delivered to the active launcher window with
//! [`dispatch_nav`] exactly as the matching keys would be.
//!
//! The d-pad and the left stick share one repeater (one move on press, then repeats while
//! held), so a held direction scrolls a list at the same pace whichever control holds it.
//! Input only reaches a launcher window that is active: a pad driving the game never moves
//! focus in the launcher behind it.

use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, Instant};

use futures::StreamExt as _;
use futures::channel::mpsc;
use gpui::App;
use mmcl_ui_kit::focus::{AxisRepeater, Direction, GamepadButton, NavInput, nav_for_button};

/// How long the poller waits for an event while a direction is held (the repeat clock).
pub const HELD_TICK: Duration = Duration::from_millis(16);
/// How long it waits while nothing is held.
pub const IDLE_TICK: Duration = Duration::from_millis(100);

static RUNNING: AtomicBool = AtomicBool::new(false);

/// The launcher's name for a `gilrs` button (`None` for buttons it does not use).
pub fn map_button(button: gilrs::Button) -> Option<GamepadButton> {
    use gilrs::Button as B;
    Some(match button {
        B::South => GamepadButton::South,
        B::East => GamepadButton::East,
        B::West => GamepadButton::West,
        B::North => GamepadButton::North,
        B::DPadUp => GamepadButton::DPadUp,
        B::DPadDown => GamepadButton::DPadDown,
        B::DPadLeft => GamepadButton::DPadLeft,
        B::DPadRight => GamepadButton::DPadRight,
        B::LeftTrigger => GamepadButton::LeftBumper,
        B::RightTrigger => GamepadButton::RightBumper,
        B::Start => GamepadButton::Start,
        B::Select => GamepadButton::Select,
        _ => return None,
    })
}

/// Whether a button is a d-pad direction (those go through the repeater, not the press path).
pub fn is_dpad(button: GamepadButton) -> bool {
    matches!(
        button,
        GamepadButton::DPadUp
            | GamepadButton::DPadDown
            | GamepadButton::DPadLeft
            | GamepadButton::DPadRight
    )
}

/// The direction a set of held d-pad buttons points (the first in up, down, left, right order).
pub fn dpad_direction(up: bool, down: bool, left: bool, right: bool) -> Option<Direction> {
    if up {
        Some(Direction::Up)
    } else if down {
        Some(Direction::Down)
    } else if left {
        Some(Direction::Left)
    } else if right {
        Some(Direction::Right)
    } else {
        None
    }
}

/// The device-independent part of the poller: presses become inputs, the held direction (the
/// d-pad first, else the stick) goes through one [`AxisRepeater`].
#[derive(Debug, Default)]
pub struct PadMapper {
    repeater: AxisRepeater,
}

impl PadMapper {
    pub fn new() -> Self {
        Self::default()
    }

    /// A button went down: the input to send now, if any (d-pad presses come from [`tick`]).
    ///
    /// [`tick`]: PadMapper::tick
    pub fn press(&mut self, button: GamepadButton) -> Option<NavInput> {
        if is_dpad(button) {
            return None;
        }
        nav_for_button(button)
    }

    /// One poll: `dpad` is the held d-pad direction, `(x, y)` the left stick (`y` up positive,
    /// as `gilrs` reports it), `dt` the time since the last poll.
    pub fn tick(
        &mut self,
        dpad: Option<Direction>,
        x: f32,
        y: f32,
        dt: Duration,
    ) -> Option<NavInput> {
        let dir = dpad.or_else(|| AxisRepeater::direction_of(x, -y));
        self.repeater.update(dir, dt).map(NavInput::Move)
    }

    /// Whether a direction is held (the poller ticks faster then).
    pub fn holding(&self, dpad: Option<Direction>, x: f32, y: f32) -> bool {
        dpad.is_some() || AxisRepeater::direction_of(x, -y).is_some()
    }
}

/// Starts the poller thread and the foreground pump (once per process). Pads that cannot be
/// opened (no driver, no udev access) leave a debug line and nothing else.
pub fn start(cx: &mut App) {
    if RUNNING.swap(true, Ordering::SeqCst) {
        return;
    }
    let (tx, mut rx) = mpsc::unbounded::<NavInput>();
    let spawned = std::thread::Builder::new()
        .name("mmcl-gamepad".into())
        .spawn(move || poll(tx));
    if let Err(e) = spawned {
        tracing::debug!(
            "{}",
            mmcl_i18n::tf!("log.gamepad.unavailable", e.to_string())
        );
        RUNNING.store(false, Ordering::SeqCst);
        return;
    }
    cx.spawn(async move |cx| {
        while let Some(input) = rx.next().await {
            cx.update(|cx| deliver(cx, input));
        }
    })
    .detach();
}

/// Sends `input` to the active launcher window, if one is active.
fn deliver(cx: &mut App, input: NavInput) {
    let Some(window) = cx.active_window() else {
        return;
    };
    let _ = window.update(cx, |_, window, cx| {
        if window.is_window_active() {
            mmcl_ui_kit::focus::dispatch_nav(window, cx, input);
        }
    });
}

fn poll(tx: mpsc::UnboundedSender<NavInput>) {
    let mut gilrs = match gilrs::Gilrs::new() {
        Ok(g) => g,
        Err(e) => {
            tracing::debug!(
                "{}",
                mmcl_i18n::tf!("log.gamepad.unavailable", e.to_string())
            );
            RUNNING.store(false, Ordering::SeqCst);
            return;
        }
    };
    let mut mapper = PadMapper::new();
    let mut last = Instant::now();
    let mut wait = IDLE_TICK;
    loop {
        while let Some(event) = gilrs.next_event_blocking(Some(wait)) {
            match event.event {
                gilrs::EventType::ButtonPressed(button, _) => {
                    if let Some(input) = map_button(button).and_then(|b| mapper.press(b))
                        && tx.unbounded_send(input).is_err()
                    {
                        return;
                    }
                }
                gilrs::EventType::Connected => {
                    let name = gilrs.gamepad(event.id).name().to_string();
                    tracing::debug!("{}", mmcl_i18n::tf!("log.gamepad.connected", name));
                }
                _ => {}
            }
            wait = Duration::ZERO;
        }
        let (dpad, x, y) = held(&gilrs);
        let now = Instant::now();
        let dt = now - last;
        last = now;
        if let Some(input) = mapper.tick(dpad, x, y, dt)
            && tx.unbounded_send(input).is_err()
        {
            return;
        }
        if tx.is_closed() {
            return;
        }
        wait = if mapper.holding(dpad, x, y) {
            HELD_TICK
        } else {
            IDLE_TICK
        };
    }
}

/// The held d-pad direction and left stick of the first pad that holds anything.
fn held(gilrs: &gilrs::Gilrs) -> (Option<Direction>, f32, f32) {
    use gilrs::{Axis, Button};
    for (_, pad) in gilrs.gamepads() {
        let dpad = dpad_direction(
            pad.is_pressed(Button::DPadUp),
            pad.is_pressed(Button::DPadDown),
            pad.is_pressed(Button::DPadLeft),
            pad.is_pressed(Button::DPadRight),
        );
        let (x, y) = (pad.value(Axis::LeftStickX), pad.value(Axis::LeftStickY));
        if dpad.is_some() || AxisRepeater::direction_of(x, -y).is_some() {
            return (dpad, x, y);
        }
    }
    (None, 0.0, 0.0)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn buttons_map_to_the_kit_layout() {
        assert_eq!(map_button(gilrs::Button::South), Some(GamepadButton::South));
        assert_eq!(
            map_button(gilrs::Button::LeftTrigger),
            Some(GamepadButton::LeftBumper)
        );
        assert_eq!(
            map_button(gilrs::Button::RightTrigger),
            Some(GamepadButton::RightBumper)
        );
        assert_eq!(map_button(gilrs::Button::LeftTrigger2), None);
        assert_eq!(map_button(gilrs::Button::Mode), None);
    }

    #[test]
    fn presses_activate_and_go_back_but_dpad_waits_for_the_tick() {
        let mut m = PadMapper::new();
        assert_eq!(m.press(GamepadButton::South), Some(NavInput::Activate));
        assert_eq!(m.press(GamepadButton::East), Some(NavInput::Back));
        assert_eq!(m.press(GamepadButton::RightBumper), Some(NavInput::Next));
        assert_eq!(m.press(GamepadButton::DPadDown), None);
        assert_eq!(m.press(GamepadButton::North), None);
    }

    #[test]
    fn a_held_dpad_moves_once_then_repeats() {
        let mut m = PadMapper::new();
        let down = Some(Direction::Down);
        assert_eq!(
            m.tick(down, 0.0, 0.0, Duration::ZERO),
            Some(NavInput::Move(Direction::Down))
        );
        assert_eq!(m.tick(down, 0.0, 0.0, Duration::from_millis(100)), None);
        assert_eq!(
            m.tick(down, 0.0, 0.0, AxisRepeater::INITIAL_DELAY),
            Some(NavInput::Move(Direction::Down))
        );
        assert_eq!(m.tick(None, 0.0, 0.0, Duration::from_millis(16)), None);
    }

    #[test]
    fn the_stick_points_up_when_pushed_up() {
        let mut m = PadMapper::new();
        // gilrs reports up as positive Y.
        assert_eq!(
            m.tick(None, 0.0, 0.9, Duration::ZERO),
            Some(NavInput::Move(Direction::Up))
        );
        assert!(m.holding(None, 0.0, 0.9));
        assert!(!m.holding(None, 0.1, -0.1));
        // The d-pad wins over the stick.
        let mut m = PadMapper::new();
        assert_eq!(
            m.tick(Some(Direction::Left), 0.0, 0.9, Duration::ZERO),
            Some(NavInput::Move(Direction::Left))
        );
    }

    #[test]
    fn dpad_direction_prefers_vertical() {
        assert_eq!(
            dpad_direction(true, false, true, false),
            Some(Direction::Up)
        );
        assert_eq!(
            dpad_direction(false, false, false, true),
            Some(Direction::Right)
        );
        assert_eq!(dpad_direction(false, false, false, false), None);
    }
}
