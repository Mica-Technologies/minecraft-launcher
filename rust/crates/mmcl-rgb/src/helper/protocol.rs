// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The line protocol between the launcher and the `--rgb-helper` child: one JSON object per
//! line on the child's stdin (requests) and stdout (replies and log lines).

use serde::{Deserialize, Serialize};

use crate::color::RgbColor;
use crate::error::{RgbError, RgbResult};
use crate::frame::RgbFrame;
use crate::key::KeyboardKey;

/// Bumped when the protocol changes; the child announces it in [`Reply::Ready`].
pub const PROTOCOL_VERSION: u32 = 1;

/// Launcher -> helper.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "op", rename_all = "snake_case")]
pub enum Request {
    Available,
    Start,
    Render {
        /// Background, `0x00RRGGBB`.
        bg: u32,
        /// `(canonical key name, 0x00RRGGBB)` overrides.
        #[serde(default, skip_serializing_if = "Vec::is_empty")]
        keys: Vec<(String, u32)>,
    },
    Shutdown,
    Exit,
}

/// Helper -> launcher.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "t", rename_all = "snake_case")]
pub enum Reply {
    /// Sent once at startup.
    Ready { kind: String, version: u32 },
    /// A request succeeded (`value` carries `available`'s answer).
    Ok {
        #[serde(default, skip_serializing_if = "Option::is_none")]
        value: Option<bool>,
    },
    /// A request failed.
    Err { msg: String },
    /// A log line from the helper, re-logged by the launcher at `level`
    /// (`error`, `warn`, `info`, `debug`).
    Log { level: String, msg: String },
}

impl Request {
    /// The render request for `frame`.
    pub fn render(frame: &RgbFrame) -> Request {
        Request::Render {
            bg: frame.background().pack_rgb(),
            keys: frame
                .overrides()
                .map(|(k, c)| (k.canonical_name().to_string(), c.pack_rgb()))
                .collect(),
        }
    }

    /// The request's name for logs.
    pub fn op(&self) -> &'static str {
        match self {
            Request::Available => "available",
            Request::Start => "start",
            Request::Render { .. } => "render",
            Request::Shutdown => "shutdown",
            Request::Exit => "exit",
        }
    }
}

/// The frame a render request carries (unknown key names are ignored).
pub fn frame_of(bg: u32, keys: &[(String, u32)]) -> RgbFrame {
    RgbFrame::new(
        RgbColor::from_packed(bg),
        keys.iter().filter_map(|(n, c)| {
            KeyboardKey::from_canonical_name(n).map(|k| (k, RgbColor::from_packed(*c)))
        }),
    )
}

/// One JSON line (no trailing newline).
pub fn encode<T: Serialize>(msg: &T) -> String {
    serde_json::to_string(msg).unwrap_or_else(|_| "{}".to_string())
}

/// Parses one line.
pub fn decode<'a, T: Deserialize<'a>>(line: &'a str) -> RgbResult<T> {
    serde_json::from_str(line.trim()).map_err(|e| RgbError::protocol(format!("rgb helper: {e}")))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn requests_round_trip() {
        let frame = RgbFrame::new(
            RgbColor::new(1, 2, 3),
            [(KeyboardKey::LeftShift, RgbColor::WHITE)],
        );
        let r = Request::render(&frame);
        let line = encode(&r);
        assert_eq!(
            line,
            r#"{"op":"render","bg":66051,"keys":[["LEFT_SHIFT",16777215]]}"#
        );
        let back: Request = decode(&line).unwrap();
        let Request::Render { bg, keys } = back else {
            panic!()
        };
        assert_eq!(frame_of(bg, &keys), frame);
        assert_eq!(encode(&Request::Available), r#"{"op":"available"}"#);
        assert_eq!(
            decode::<Request>(r#"{"op":"render","bg":0}"#).unwrap(),
            Request::Render {
                bg: 0,
                keys: vec![]
            }
        );
    }

    #[test]
    fn replies_round_trip() {
        for r in [
            Reply::Ready {
                kind: "corsair".into(),
                version: PROTOCOL_VERSION,
            },
            Reply::Ok { value: None },
            Reply::Ok { value: Some(false) },
            Reply::Err { msg: "x".into() },
            Reply::Log {
                level: "warn".into(),
                msg: "y".into(),
            },
        ] {
            assert_eq!(decode::<Reply>(&encode(&r)).unwrap(), r);
        }
        assert!(decode::<Reply>("garbage").is_err());
    }
}
