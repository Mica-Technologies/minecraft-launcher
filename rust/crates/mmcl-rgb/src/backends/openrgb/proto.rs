// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The OpenRGB SDK wire protocol (Java `OpenRgbProtocol`), little-endian, protocol version 3.

use std::collections::HashMap;
use std::io::{self, Read, Write};

use crate::error::{RgbError, RgbResult};
use crate::key::KeyboardKey;

pub const MAGIC: &[u8; 4] = b"ORGB";
pub const HEADER_LEN: usize = 16;
/// The protocol version the client asks for. Servers answer in the requested version, so
/// asking for 3 keeps the parser's layout valid.
pub const PROTOCOL_VERSION: u32 = 3;

pub const REQUEST_CONTROLLER_COUNT: u32 = 0;
pub const REQUEST_CONTROLLER_DATA: u32 = 1;
pub const REQUEST_PROTOCOL_VERSION: u32 = 40;
pub const SET_CLIENT_NAME: u32 = 50;
pub const RGBCONTROLLER_UPDATELEDS: u32 = 1050;
pub const RGBCONTROLLER_SETCUSTOMMODE: u32 = 1100;

/// OpenRGB device type of keyboards.
pub const DEVICE_TYPE_KEYBOARD: u32 = 5;

/// Writes one packet (header and body in a single flush).
pub fn write_packet(
    w: &mut impl Write,
    device: u32,
    packet_id: u32,
    body: &[u8],
) -> io::Result<()> {
    let mut buf = Vec::with_capacity(HEADER_LEN + body.len());
    buf.extend_from_slice(MAGIC);
    buf.extend_from_slice(&device.to_le_bytes());
    buf.extend_from_slice(&packet_id.to_le_bytes());
    buf.extend_from_slice(&(body.len() as u32).to_le_bytes());
    buf.extend_from_slice(body);
    w.write_all(&buf)?;
    w.flush()
}

/// Reads one packet and returns its body. A packet with another id is drained (so the stream
/// stays aligned) and reported as an error.
pub fn read_packet(r: &mut impl Read, expected_id: u32) -> RgbResult<Vec<u8>> {
    let mut header = [0u8; HEADER_LEN];
    r.read_exact(&mut header)
        .map_err(|e| RgbError::protocol(format!("OpenRGB header read failed: {e}")))?;
    if &header[0..4] != MAGIC {
        return Err(RgbError::protocol("OpenRGB stream desynced (bad magic)"));
    }
    let id = u32::from_le_bytes(header[8..12].try_into().unwrap_or_default());
    let len = u32::from_le_bytes(header[12..16].try_into().unwrap_or_default()) as usize;
    let mut body = vec![0u8; len];
    r.read_exact(&mut body)
        .map_err(|e| RgbError::protocol(format!("OpenRGB body read failed: {e}")))?;
    if id != expected_id {
        return Err(RgbError::protocol(format!(
            "OpenRGB: expected packet {expected_id}, got {id}"
        )));
    }
    Ok(body)
}

/// The `UPDATELEDS` body: `u32 data_size`, `u16 count`, then `R, G, B, 0` per LED.
/// `colors` are `0x00RRGGBB`.
pub fn update_leds_body(colors: &[u32]) -> Vec<u8> {
    let n = colors.len().min(u16::MAX as usize);
    let size = 4 + 2 + 4 * n;
    let mut out = Vec::with_capacity(size);
    out.extend_from_slice(&(size as u32).to_le_bytes());
    out.extend_from_slice(&(n as u16).to_le_bytes());
    for &c in &colors[..n] {
        out.extend_from_slice(&[(c >> 16) as u8, (c >> 8) as u8, c as u8, 0]);
    }
    out
}

/// The parts of `REQUEST_CONTROLLER_DATA` the backend keeps.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ControllerData {
    pub device_type: u32,
    pub name: String,
    pub description: String,
    pub led_names: Vec<String>,
}

struct Cursor<'a> {
    buf: &'a [u8],
    pos: usize,
}

impl<'a> Cursor<'a> {
    fn take(&mut self, n: usize) -> RgbResult<&'a [u8]> {
        let end = self
            .pos
            .checked_add(n)
            .filter(|&e| e <= self.buf.len())
            .ok_or_else(|| {
                RgbError::protocol("OpenRGB controller data truncated or schema mismatch")
            })?;
        let s = &self.buf[self.pos..end];
        self.pos = end;
        Ok(s)
    }
    fn u16(&mut self) -> RgbResult<u16> {
        Ok(u16::from_le_bytes(
            self.take(2)?.try_into().unwrap_or_default(),
        ))
    }
    fn u32(&mut self) -> RgbResult<u32> {
        Ok(u32::from_le_bytes(
            self.take(4)?.try_into().unwrap_or_default(),
        ))
    }
    fn skip_u32s(&mut self, n: usize) -> RgbResult<()> {
        self.take(
            n.checked_mul(4)
                .ok_or_else(|| RgbError::protocol("overflow"))?,
        )
        .map(|_| ())
    }
    /// `u16 len` (including the trailing NUL), then the bytes; one trailing NUL is stripped.
    fn string(&mut self) -> RgbResult<String> {
        let len = self.u16()? as usize;
        if len == 0 {
            return Ok(String::new());
        }
        let bytes = self.take(len)?;
        let bytes = bytes.strip_suffix(&[0]).unwrap_or(bytes);
        Ok(String::from_utf8_lossy(bytes).into_owned())
    }
}

/// Parses a protocol-3 controller data body. Any underflow is an error, never a panic.
pub fn parse_controller_data(body: &[u8]) -> RgbResult<ControllerData> {
    let mut c = Cursor { buf: body, pos: 0 };
    c.u32()?; // data_size
    let device_type = c.u32()?;
    let name = c.string()?;
    let description = c.string()?;
    c.string()?; // version
    c.string()?; // serial
    c.string()?; // location
    let num_modes = c.u16()?;
    c.u32()?; // active mode
    for _ in 0..num_modes {
        c.string()?;
        // value, flags, speed_min/max, brightness_min/max, colors_min/max, speed, brightness,
        // direction, color_mode
        c.skip_u32s(12)?;
        let n = c.u16()? as usize;
        c.skip_u32s(n)?;
    }
    let num_zones = c.u16()?;
    for _ in 0..num_zones {
        c.string()?;
        c.skip_u32s(4)?; // type, leds_min, leds_max, leds_count
        let matrix_size = c.u16()?;
        if matrix_size > 0 {
            let h = c.u32()? as usize;
            let w = c.u32()? as usize;
            c.skip_u32s(
                h.checked_mul(w)
                    .ok_or_else(|| RgbError::protocol("matrix overflow"))?,
            )?;
        }
    }
    let num_leds = c.u16()?;
    let mut led_names = Vec::with_capacity(num_leds as usize);
    for _ in 0..num_leds {
        led_names.push(c.string()?);
        c.u32()?; // value
    }
    Ok(ControllerData {
        device_type,
        name,
        description,
        led_names,
    })
}

/// Log label for an OpenRGB device type.
pub fn device_type_label(t: u32) -> String {
    match t {
        0 => "motherboard",
        1 => "DRAM",
        2 => "GPU",
        3 => "cooler",
        4 => "LED strip",
        5 => "keyboard",
        6 => "mouse",
        7 => "mousemat",
        8 => "headset",
        9 => "headset stand",
        10 => "gamepad",
        11 => "light",
        12 => "speaker",
        13 => "virtual",
        _ => return format!("device (type={t})"),
    }
    .to_string()
}

/// Uppercases, trims, and strips a leading `KEY:` / `KEY ` and a trailing ` KEY`.
pub fn normalize_led_name(name: &str) -> String {
    let mut s = name.to_uppercase().trim().to_string();
    if let Some(rest) = s.strip_prefix("KEY:").or_else(|| s.strip_prefix("KEY ")) {
        s = rest.trim().to_string();
    }
    if let Some(rest) = s.strip_suffix(" KEY") {
        s = rest.trim().to_string();
    }
    s
}

/// The key a normalised LED name stands for.
pub fn key_for_normalized(name: &str) -> Option<KeyboardKey> {
    if let Some(k) = KeyboardKey::from_canonical_name(name) {
        return Some(k);
    }
    Some(match name {
        "LSHIFT" | "LEFTSHIFT" | "LEFT SHIFT" => KeyboardKey::LeftShift,
        "LCTRL" | "LEFTCTRL" | "LEFT CTRL" | "LEFT CONTROL" => KeyboardKey::LeftCtrl,
        "ESC" => KeyboardKey::Escape,
        "RETURN" => KeyboardKey::Enter,
        n if n.len() == 1 => KeyboardKey::num(n.parse().ok()?)?,
        _ => return None,
    })
}

/// LED index per key for a keyboard's LED names (the first LED wins for a key).
pub fn led_name_map(led_names: &[String]) -> HashMap<KeyboardKey, usize> {
    let mut out = HashMap::new();
    for (i, n) in led_names.iter().enumerate() {
        let norm = normalize_led_name(n);
        if norm.is_empty() {
            continue;
        }
        if let Some(k) = key_for_normalized(&norm) {
            out.entry(k).or_insert(i);
        }
    }
    out
}

#[cfg(test)]
pub(crate) mod fixture {
    //! Builds protocol-3 controller blobs the way an OpenRGB 0.9 server lays them out.

    pub struct Blob(pub Vec<u8>);

    impl Blob {
        pub fn u16(&mut self, v: u16) -> &mut Self {
            self.0.extend_from_slice(&v.to_le_bytes());
            self
        }
        pub fn u32(&mut self, v: u32) -> &mut Self {
            self.0.extend_from_slice(&v.to_le_bytes());
            self
        }
        pub fn str(&mut self, s: &str) -> &mut Self {
            self.u16(s.len() as u16 + 1);
            self.0.extend_from_slice(s.as_bytes());
            self.0.push(0);
            self
        }
    }

    /// A keyboard with one mode (two colours), a matrix zone, and the given LEDs.
    pub fn keyboard(leds: &[&str]) -> Vec<u8> {
        let mut b = Blob(Vec::new());
        b.u32(0)
            .u32(5)
            .str("Razer Huntsman V3 Pro")
            .str("Razer Keyboard Device")
            .str("")
            .str("PM1234")
            .str("HID: /dev/hidraw3");
        b.u16(1).u32(0);
        b.str("Direct");
        for v in [0u32, 0x20, 0, 0, 0, 100, 1, 1, 0, 100, 0, 1] {
            b.u32(v);
        }
        b.u16(2).u32(0xFF0000).u32(0x00FF00);
        b.u16(1)
            .str("Keyboard")
            .u32(2)
            .u32(leds.len() as u32)
            .u32(leds.len() as u32)
            .u32(leds.len() as u32);
        b.u16(4 + 4 + 4 * 2).u32(1).u32(2).u32(0).u32(1);
        b.u16(leds.len() as u16);
        for (i, l) in leds.iter().enumerate() {
            b.str(l).u32(i as u32);
        }
        // Trailing colours block (ignored by the parser).
        b.u16(leds.len() as u16);
        for _ in leds {
            b.u32(0);
        }
        let len = b.0.len() as u32;
        b.0[0..4].copy_from_slice(&len.to_le_bytes());
        b.0
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn header_round_trip() {
        let mut buf = Vec::new();
        write_packet(&mut buf, 7, REQUEST_CONTROLLER_DATA, &[1, 2, 3]).unwrap();
        assert_eq!(&buf[0..4], b"ORGB");
        assert_eq!(u32::from_le_bytes(buf[4..8].try_into().unwrap()), 7);
        assert_eq!(u32::from_le_bytes(buf[12..16].try_into().unwrap()), 3);
        let body = read_packet(&mut buf.as_slice(), REQUEST_CONTROLLER_DATA).unwrap();
        assert_eq!(body, vec![1, 2, 3]);
    }

    #[test]
    fn wrong_id_drains_the_body_and_keeps_alignment() {
        let mut buf = Vec::new();
        write_packet(&mut buf, 0, 99, &[9; 10]).unwrap();
        write_packet(&mut buf, 0, REQUEST_CONTROLLER_COUNT, &2u32.to_le_bytes()).unwrap();
        let mut r = buf.as_slice();
        assert!(read_packet(&mut r, REQUEST_CONTROLLER_COUNT).is_err());
        let body = read_packet(&mut r, REQUEST_CONTROLLER_COUNT).unwrap();
        assert_eq!(body, 2u32.to_le_bytes());
    }

    #[test]
    fn bad_magic_and_short_reads_are_errors() {
        let mut bad = b"NOPE".to_vec();
        bad.extend_from_slice(&[0; 12]);
        let e = read_packet(&mut bad.as_slice(), 0).unwrap_err();
        assert!(e.to_string().contains("desynced"));
        assert!(read_packet(&mut &b"ORGB"[..], 0).is_err());
        let mut short = Vec::new();
        write_packet(&mut short, 0, 0, &[1, 2, 3, 4]).unwrap();
        short.truncate(18);
        assert!(read_packet(&mut short.as_slice(), 0).is_err());
    }

    #[test]
    fn update_leds_layout() {
        let body = update_leds_body(&[0x112233, 0xAABBCC]);
        assert_eq!(body.len(), 4 + 2 + 8);
        assert_eq!(u32::from_le_bytes(body[0..4].try_into().unwrap()), 14);
        assert_eq!(u16::from_le_bytes(body[4..6].try_into().unwrap()), 2);
        assert_eq!(&body[6..], &[0x11, 0x22, 0x33, 0, 0xAA, 0xBB, 0xCC, 0]);
    }

    #[test]
    fn parses_a_protocol_3_keyboard_blob() {
        let blob = fixture::keyboard(&["Key: Escape", "Key: W", "Key: A", "Key: Left Shift"]);
        let d = parse_controller_data(&blob).unwrap();
        assert_eq!(d.device_type, DEVICE_TYPE_KEYBOARD);
        assert_eq!(d.name, "Razer Huntsman V3 Pro");
        assert_eq!(d.description, "Razer Keyboard Device");
        assert_eq!(
            d.led_names,
            ["Key: Escape", "Key: W", "Key: A", "Key: Left Shift"]
        );
        let map = led_name_map(&d.led_names);
        assert_eq!(map.get(&KeyboardKey::Escape), Some(&0));
        assert_eq!(map.get(&KeyboardKey::W), Some(&1));
        assert_eq!(map.get(&KeyboardKey::LeftShift), Some(&3));
    }

    #[test]
    fn truncated_blob_is_an_error_not_a_panic() {
        let blob = fixture::keyboard(&["Key: W", "Key: A"]);
        for cut in [0, 3, 8, 20, 60, blob.len() - 12] {
            assert!(parse_controller_data(&blob[..cut]).is_err(), "cut at {cut}");
        }
    }

    #[test]
    fn led_name_normaliser() {
        let cases = [
            ("Key: W", Some(KeyboardKey::W)),
            ("Key W", Some(KeyboardKey::W)),
            ("W", Some(KeyboardKey::W)),
            ("W Key", Some(KeyboardKey::W)),
            ("Left Shift", Some(KeyboardKey::LeftShift)),
            ("LCTRL", Some(KeyboardKey::LeftCtrl)),
            ("Key: Left Control", Some(KeyboardKey::LeftCtrl)),
            ("Esc", Some(KeyboardKey::Escape)),
            ("Return", Some(KeyboardKey::Enter)),
            ("1", Some(KeyboardKey::Num1)),
            ("Key: 9", Some(KeyboardKey::Num9)),
            ("0", None),
            ("Key: Right Shift", None),
            ("", None),
        ];
        for (name, want) in cases {
            assert_eq!(
                key_for_normalized(&normalize_led_name(name)),
                want,
                "{name:?}"
            );
        }
        let map = led_name_map(&["W".into(), "Key: W".into()]);
        assert_eq!(map[&KeyboardKey::W], 0, "first LED wins");
    }
}
