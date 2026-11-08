// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! A tiny CSS reader for the launcher's token sheets.
//!
//! This module is shared verbatim by `build.rs` (which turns the seven `ui-tokens-*.css` sheets
//! into const tables) and by the library (colour parsing at runtime, and the tests that re-check
//! the generated tables against the sheets). It therefore uses `std` only.

/// Removes every `/* ... */` comment. An unterminated comment swallows the rest of the input,
/// as a CSS parser would.
pub fn strip_comments(css: &str) -> String {
    let mut out = String::with_capacity(css.len());
    let mut rest = css;
    while let Some(start) = rest.find("/*") {
        out.push_str(&rest[..start]);
        match rest[start + 2..].find("*/") {
            Some(end) => rest = &rest[start + 2 + end + 2..],
            None => return out,
        }
    }
    out.push_str(rest);
    out
}

/// One rule block: its selector list (whitespace squeezed) and its declarations in order.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Rule {
    /// The selectors, split on `,` and whitespace-normalised.
    pub selectors: Vec<String>,
    /// `(property, value)` pairs in source order; values are trimmed.
    pub declarations: Vec<(String, String)>,
}

/// Splits comment-free CSS into its top-level rule blocks. Nested blocks are not part of the
/// token sheets' grammar and are not supported.
pub fn rules(css: &str) -> Vec<Rule> {
    let css = strip_comments(css);
    let mut out = Vec::new();
    let mut rest = css.as_str();
    while let Some(open) = rest.find('{') {
        let selector_text = &rest[..open];
        let Some(close) = rest[open + 1..].find('}') else {
            break;
        };
        let body = &rest[open + 1..open + 1 + close];
        let selectors = selector_text
            .split(',')
            .map(|s| s.split_whitespace().collect::<Vec<_>>().join(" "))
            .filter(|s| !s.is_empty())
            .collect();
        out.push(Rule {
            selectors,
            declarations: declarations(body),
        });
        rest = &rest[open + 1 + close + 1..];
    }
    out
}

/// Parses `prop: value;` pairs from a block body. A trailing declaration without `;` counts.
pub fn declarations(body: &str) -> Vec<(String, String)> {
    body.split(';')
        .filter_map(|decl| {
            let (prop, value) = decl.split_once(':')?;
            let prop = prop.trim();
            let value = value.split_whitespace().collect::<Vec<_>>().join(" ");
            if prop.is_empty() || value.is_empty() {
                None
            } else {
                Some((prop.to_string(), value))
            }
        })
        .collect()
}

/// The `-color-*` / `-md-*` tokens declared in a sheet's `.root` block, in order.
/// Returns `None` when the sheet has no `.root` block.
pub fn root_tokens(css: &str) -> Option<Vec<(String, String)>> {
    let root = rules(css)
        .into_iter()
        .find(|r| r.selectors.iter().any(|s| s == ".root"))?;
    Some(
        root.declarations
            .into_iter()
            .filter(|(k, _)| k.starts_with("-color-") || k.starts_with("-md-"))
            .collect(),
    )
}

/// Parses a CSS colour into `0xRRGGBBAA`.
///
/// Accepts `#RGB`, `#RGBA`, `#RRGGBB`, `#RRGGBBAA`, `rgb(r, g, b)`, `rgba(r, g, b, a)` (alpha
/// 0..1, or a percentage), and the names `white`, `black` and `transparent`. Alpha is rounded to
/// the nearest of 256 steps. Returns `None` for anything else (including token references such as
/// `-md-surface-container-high`).
pub fn parse_color(value: &str) -> Option<u32> {
    let v = value.trim().to_ascii_lowercase();
    match v.as_str() {
        "white" => return Some(0xFFFF_FFFF),
        "black" => return Some(0x0000_00FF),
        "transparent" => return Some(0x0000_0000),
        _ => {}
    }
    if let Some(hex) = v.strip_prefix('#') {
        return parse_hex(hex);
    }
    let inner = v
        .strip_prefix("rgba(")
        .or_else(|| v.strip_prefix("rgb("))?
        .strip_suffix(')')?;
    let parts: Vec<&str> = inner.split(',').map(str::trim).collect();
    if parts.len() != 3 && parts.len() != 4 {
        return None;
    }
    let mut channels = [0u32; 3];
    for (slot, part) in channels.iter_mut().zip(&parts) {
        let n: f64 = part.parse().ok()?;
        if !(0.0..=255.0).contains(&n) {
            return None;
        }
        *slot = n.round() as u32;
    }
    let alpha = match parts.get(3) {
        None => 1.0,
        Some(a) => match a.strip_suffix('%') {
            Some(p) => p.trim().parse::<f64>().ok()? / 100.0,
            None => a.parse::<f64>().ok()?,
        },
    };
    if !(0.0..=1.0).contains(&alpha) {
        return None;
    }
    let a = (alpha * 255.0).round() as u32;
    Some((channels[0] << 24) | (channels[1] << 16) | (channels[2] << 8) | a)
}

fn parse_hex(hex: &str) -> Option<u32> {
    if !hex.bytes().all(|b| b.is_ascii_hexdigit()) {
        return None;
    }
    let expand = |s: &str| -> String { s.chars().flat_map(|c| [c, c]).collect() };
    let full = match hex.len() {
        3 => format!("{}ff", expand(hex)),
        4 => expand(hex),
        6 => format!("{hex}ff"),
        8 => hex.to_string(),
        _ => return None,
    };
    u32::from_str_radix(&full, 16).ok()
}

/// Turns a token name into a Rust field name: `-color-bg-soft` -> `color_bg_soft`.
pub fn field_name(token: &str) -> String {
    token.trim_start_matches('-').replace('-', "_")
}
