// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The `APPINFOTYPE` block passed to `InitSDK` (Java `ChromaAppInfo`): packed, UTF-16LE,
//! zero-filled, 3592 bytes.

pub const TITLE_OFFSET: usize = 0;
pub const TITLE_LEN: usize = 512;
pub const DESCRIPTION_OFFSET: usize = 512;
pub const DESCRIPTION_LEN: usize = 2048;
pub const AUTHOR_NAME_OFFSET: usize = 2560;
pub const AUTHOR_NAME_LEN: usize = 512;
pub const AUTHOR_CONTACT_OFFSET: usize = 3072;
pub const AUTHOR_CONTACT_LEN: usize = 512;
pub const SUPPORTED_DEVICE_OFFSET: usize = 3584;
pub const CATEGORY_OFFSET: usize = 3588;
pub const TOTAL_LEN: usize = 3592;

pub const DEVICE_KEYBOARD: u32 = 0x01;
pub const DEVICE_MOUSE: u32 = 0x02;
pub const DEVICE_HEADSET: u32 = 0x04;
pub const DEVICE_MOUSEPAD: u32 = 0x08;
pub const DEVICE_KEYPAD: u32 = 0x10;
pub const DEVICE_CHROMALINK: u32 = 0x20;
pub const DEVICE_ALL: u32 = DEVICE_KEYBOARD
    | DEVICE_MOUSE
    | DEVICE_HEADSET
    | DEVICE_MOUSEPAD
    | DEVICE_KEYPAD
    | DEVICE_CHROMALINK;
pub const CATEGORY_APPLICATION: u32 = 1;
pub const CATEGORY_GAME: u32 = 2;

pub const APP_TITLE: &str = "Mica Minecraft Launcher";
pub const APP_DESCRIPTION: &str = "Modpack-aware RGB lighting for Minecraft sessions.";
pub const APP_AUTHOR_NAME: &str = "Mica Technologies";
pub const APP_AUTHOR_CONTACT: &str = "https://github.com/Mica-Technologies/minecraft-launcher";

/// Copies `s` as UTF-16LE into `buf[off..off + cap]`, truncated to `cap - 2` bytes so the last
/// wchar is always NUL.
fn put_wide(buf: &mut [u8], off: usize, cap: usize, s: &str) {
    let max = cap.saturating_sub(2);
    let mut i = 0;
    for unit in s.encode_utf16() {
        if i + 2 > max {
            break;
        }
        buf[off + i..off + i + 2].copy_from_slice(&unit.to_le_bytes());
        i += 2;
    }
}

/// Builds the block.
pub fn build(
    title: &str,
    description: &str,
    author_name: &str,
    author_contact: &str,
    supported_devices: u32,
    category: u32,
) -> Vec<u8> {
    let mut b = vec![0u8; TOTAL_LEN];
    put_wide(&mut b, TITLE_OFFSET, TITLE_LEN, title);
    put_wide(&mut b, DESCRIPTION_OFFSET, DESCRIPTION_LEN, description);
    put_wide(&mut b, AUTHOR_NAME_OFFSET, AUTHOR_NAME_LEN, author_name);
    put_wide(
        &mut b,
        AUTHOR_CONTACT_OFFSET,
        AUTHOR_CONTACT_LEN,
        author_contact,
    );
    b[SUPPORTED_DEVICE_OFFSET..SUPPORTED_DEVICE_OFFSET + 4]
        .copy_from_slice(&supported_devices.to_le_bytes());
    b[CATEGORY_OFFSET..CATEGORY_OFFSET + 4].copy_from_slice(&category.to_le_bytes());
    b
}

/// The launcher's block.
pub fn launcher() -> Vec<u8> {
    build(
        APP_TITLE,
        APP_DESCRIPTION,
        APP_AUTHOR_NAME,
        APP_AUTHOR_CONTACT,
        DEVICE_ALL,
        CATEGORY_APPLICATION,
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    fn wide_at(b: &[u8], off: usize, cap: usize) -> String {
        let units: Vec<u16> = b[off..off + cap]
            .chunks(2)
            .map(|c| u16::from_le_bytes([c[0], c[1]]))
            .take_while(|&u| u != 0)
            .collect();
        String::from_utf16(&units).unwrap()
    }

    #[test]
    fn layout_and_values() {
        let b = launcher();
        assert_eq!(b.len(), 3592);
        assert_eq!(wide_at(&b, TITLE_OFFSET, TITLE_LEN), APP_TITLE);
        assert_eq!(
            wide_at(&b, DESCRIPTION_OFFSET, DESCRIPTION_LEN),
            APP_DESCRIPTION
        );
        assert_eq!(
            wide_at(&b, AUTHOR_NAME_OFFSET, AUTHOR_NAME_LEN),
            APP_AUTHOR_NAME
        );
        assert_eq!(
            wide_at(&b, AUTHOR_CONTACT_OFFSET, AUTHOR_CONTACT_LEN),
            APP_AUTHOR_CONTACT
        );
        assert_eq!(u32::from_le_bytes(b[3584..3588].try_into().unwrap()), 0x3F);
        assert_eq!(u32::from_le_bytes(b[3588..3592].try_into().unwrap()), 1);
    }

    #[test]
    fn long_strings_are_truncated_with_a_nul() {
        let long = "x".repeat(400);
        let b = build(&long, "", "", "", 0, 2);
        assert_eq!(wide_at(&b, 0, 512).len(), 255);
        assert_eq!(&b[510..512], &[0, 0]);
        assert_eq!(b[512], 0, "nothing spills into the description");
    }
}
