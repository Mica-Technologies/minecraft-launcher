// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! X11 window hints on GPUI's windows: `WM_TRANSIENT_FOR` (owned windows), `_NET_WM_STATE_MODAL`
//! (modal) and the `WM_NORMAL_HINTS` minimum size.
//!
//! X11 window ids are server-wide, so this uses a private connection of its own (opened on
//! first use from `$DISPLAY`) rather than GPUI's. Without an X server (Wayland without
//! XWayland, headless) every call returns `false`.

use std::sync::LazyLock;

use parking_lot::Mutex;
use x11rb::connection::Connection;
use x11rb::properties::WmSizeHints;
use x11rb::protocol::xproto::{
    AtomEnum, ClientMessageEvent, ConfigureWindowAux, ConnectionExt as _, EventMask, Window,
};
use x11rb::rust_connection::RustConnection;
use x11rb::wrapper::ConnectionExt as _;

/// `_NET_WM_STATE_REMOVE` / `_NET_WM_STATE_ADD`.
const NET_WM_STATE_REMOVE: u32 = 0;
const NET_WM_STATE_ADD: u32 = 1;
/// Source indication "normal application" for EWMH client messages.
const SOURCE_APPLICATION: u32 = 1;

type BoxError = Box<dyn std::error::Error>;

struct X {
    conn: RustConnection,
    root: Window,
}

static CONN: LazyLock<Mutex<Option<X>>> = LazyLock::new(|| {
    if std::env::var_os("DISPLAY").is_none() {
        return Mutex::new(None);
    }
    match RustConnection::connect(None) {
        Ok((conn, screen)) => {
            let root = conn.setup().roots.get(screen).map(|s| s.root);
            Mutex::new(root.map(|root| X { conn, root }))
        }
        Err(e) => {
            tracing::debug!(
                "{}",
                mmcl_i18n::tf!("log.window.x11Unavailable", e.to_string())
            );
            Mutex::new(None)
        }
    }
});

/// Runs `f` on the shared connection; `false` without one or on any X error.
fn with_x(f: impl FnOnce(&X) -> Result<(), BoxError>) -> bool {
    crate::guard(|| {
        let guard = CONN.lock();
        let Some(x) = guard.as_ref() else {
            return false;
        };
        match f(x).and_then(|()| x.conn.flush().map_err(Into::into)) {
            Ok(()) => true,
            Err(e) => {
                tracing::debug!(
                    "{}",
                    mmcl_i18n::tf!("log.window.x11Unavailable", e.to_string())
                );
                false
            }
        }
    })
}

fn atom(x: &X, name: &[u8]) -> Result<u32, BoxError> {
    Ok(x.conn.intern_atom(false, name)?.reply()?.atom)
}

/// Sets (`Some`) or deletes (`None`) `WM_TRANSIENT_FOR` on `child`: the window manager keeps it
/// above `owner`, iconifies it with `owner` and usually leaves it out of the task list.
pub fn set_transient_for(child: u32, owner: Option<u32>) -> bool {
    with_x(|x| {
        match owner {
            Some(o) => {
                x.conn.change_property32(
                    x11rb::protocol::xproto::PropMode::REPLACE,
                    child,
                    AtomEnum::WM_TRANSIENT_FOR,
                    AtomEnum::WINDOW,
                    &[o],
                )?;
            }
            None => {
                x.conn
                    .delete_property(child, AtomEnum::WM_TRANSIENT_FOR.into())?;
            }
        }
        Ok(())
    })
}

/// The `_NET_WM_STATE` client-message payload adding or removing one state atom. Pure.
pub(crate) fn net_wm_state_data(add: bool, state: u32) -> [u32; 5] {
    let action = if add {
        NET_WM_STATE_ADD
    } else {
        NET_WM_STATE_REMOVE
    };
    [action, state, 0, SOURCE_APPLICATION, 0]
}

/// Adds or removes `_NET_WM_STATE_MODAL` on a mapped `child` (EWMH: a modal transient window;
/// most window managers then refuse focus to its owner).
pub fn set_modal_state(child: u32, modal: bool) -> bool {
    with_x(|x| {
        let net_wm_state = atom(x, b"_NET_WM_STATE")?;
        let modal_atom = atom(x, b"_NET_WM_STATE_MODAL")?;
        let event = ClientMessageEvent::new(
            32,
            child,
            net_wm_state,
            net_wm_state_data(modal, modal_atom),
        );
        x.conn.send_event(
            false,
            x.root,
            EventMask::SUBSTRUCTURE_REDIRECT | EventMask::SUBSTRUCTURE_NOTIFY,
            event,
        )?;
        Ok(())
    })
}

/// Sets (or clears) the minimum size in `WM_NORMAL_HINTS`, keeping the other hints GPUI set,
/// and grows the window when it is smaller.
pub fn set_min_size(window: u32, min: Option<(i32, i32)>) -> bool {
    with_x(|x| {
        let mut hints = WmSizeHints::get_normal_hints(&x.conn, window)?
            .reply()?
            .unwrap_or_default();
        hints.min_size = min;
        hints.set_normal_hints(&x.conn, window)?;
        if let Some(min) = min {
            let geo = x.conn.get_geometry(window)?.reply()?;
            let current = (i32::from(geo.width), i32::from(geo.height));
            if let Some((w, h)) = crate::window::grow_to_min(current, min) {
                let aux = ConfigureWindowAux::new()
                    .width(u32::try_from(w).unwrap_or(0))
                    .height(u32::try_from(h).unwrap_or(0));
                x.conn.configure_window(window, &aux)?;
            }
        }
        Ok(())
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn net_wm_state_messages_follow_ewmh() {
        assert_eq!(net_wm_state_data(true, 42), [1, 42, 0, 1, 0]);
        assert_eq!(net_wm_state_data(false, 42), [0, 42, 0, 1, 0]);
    }
}
