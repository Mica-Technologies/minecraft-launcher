// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Pure text helpers of the terminal UI (Java `TuiApp.formatDuration`, `truncate`,
//! `statusLine`, `headerText`, `formatPackRow`, `formatAvailableRow`). They take the
//! [`Localizer`] to render through, so tests can feed an English one.

use mmcl_engine::PackSummary;
use mmcl_i18n::{FmtArg, Localizer};
use unicode_width::UnicodeWidthStr;

/// Columns assumed when the terminal size is unknown.
pub const DEFAULT_COLUMNS: u16 = 120;

/// The ellipsis that ends a truncated string.
pub const ELLIPSIS: char = '\u{2026}';

/// A playtime as `2h 30m`, `45m`, `<1m` or `0m` (integer truncation, never rounding). Numbers
/// are formatted here, so no locale digit grouping is inserted.
pub fn format_duration(ms: i64, l: &Localizer) -> String {
    if ms <= 0 {
        return l.format("tui.duration.minutes", &[FmtArg::from("0")]);
    }
    let total_minutes = ms / 60_000;
    let hours = total_minutes / 60;
    let minutes = total_minutes % 60;
    if hours > 0 {
        let (h, m) = (hours.to_string(), minutes.to_string());
        return l.format(
            "tui.duration.hoursMinutes",
            &[FmtArg::from(h.as_str()), FmtArg::from(m.as_str())],
        );
    }
    if minutes > 0 {
        let m = minutes.to_string();
        return l.format("tui.duration.minutes", &[FmtArg::from(m.as_str())]);
    }
    l.get("tui.duration.underMinute").into_owned()
}

/// `s` cut to `max` characters, the last one replaced by `…` when it was longer. `None` gives
/// `""`; `max == 0` gives `"…"`. Counts Unicode scalar values (Java counted UTF-16 units).
pub fn truncate(s: Option<&str>, max: usize) -> String {
    let Some(s) = s else {
        return String::new();
    };
    if s.chars().count() <= max {
        return s.to_string();
    }
    if max == 0 {
        return ELLIPSIS.to_string();
    }
    let mut out: String = s.chars().take(max - 1).collect();
    out.push(ELLIPSIS);
    out
}

/// `s` padded with spaces to `width` terminal columns (Java `%-Ns`; display width, so wide
/// CJK characters count twice).
pub fn pad(s: &str, width: usize) -> String {
    let w = s.width();
    if w >= width {
        return s.to_string();
    }
    let mut out = String::with_capacity(s.len() + width - w);
    out.push_str(s);
    out.extend(std::iter::repeat_n(' ', width - w));
    out
}

/// The header line that fits `cols` columns (`None` = unknown, 120 assumed): the full form,
/// then shorter ones, so a hint is never cut mid-word.
pub fn header_text(cols: Option<u16>, l: &Localizer) -> String {
    let cols = usize::from(cols.unwrap_or(DEFAULT_COLUMNS));
    let views = format!(
        "[L] {}  [B] {}  [G] {}  [S] {}",
        l.get("tui.view.library"),
        l.get("tui.view.browse"),
        l.get("tui.view.logs"),
        l.get("tui.view.settings")
    );
    let back = format!("[Esc] {}", l.get("tui.nav.back"));
    let quit = format!("[q] {}", l.get("tui.nav.quit"));
    let full = format!(
        "  {}   {views}    [Enter] {}  {back}  {quit}",
        l.get("tui.appName"),
        l.get("tui.nav.actions")
    );
    let medium = format!("  {views}    {back}  {quit}");
    let compact = format!("  [L] [B] [G] [S]    {back}  {quit}");
    let minimal = "  [L][B][G][S]  [Esc][q]".to_string();
    [full, medium, compact]
        .into_iter()
        .find(|c| c.width() <= cols)
        .unwrap_or(minimal)
}

/// The status bar: running count, total playtime and the signed-in account.
pub fn status_line(running: usize, total_ms: i64, user: Option<&str>, l: &Localizer) -> String {
    let user = match user.map(str::trim).filter(|u| !u.is_empty()) {
        Some(u) => u.to_string(),
        None => l.get("tui.status.signedOut").into_owned(),
    };
    let count = running.to_string();
    let duration = format_duration(total_ms, l);
    format!(
        "  {}",
        l.format(
            "tui.status.line",
            &[
                FmtArg::from(count.as_str()),
                FmtArg::from(duration.as_str()),
                FmtArg::from(user.as_str()),
            ],
        )
    )
}

/// "Last played" for a pack (`metadata.lastPlayed.*`), as the GUI shows it.
pub fn last_played(last_played_ms: i64, now_ms: i64, l: &Localizer) -> String {
    let label = mmcl_manifest::play_time::last_played(last_played_ms, now_ms);
    if label.amount == 0.0 {
        l.get(label.key).into_owned()
    } else {
        l.format(label.key, &[FmtArg::Int(label.amount as i64)])
    }
}

/// The name a pack row shows: the friendly name, else the pack name.
pub fn pack_display_name(p: &PackSummary) -> &str {
    if p.friendly_name.trim().is_empty() {
        &p.name
    } else {
        &p.friendly_name
    }
}

fn version_or_unknown(v: &str) -> &str {
    if v.trim().is_empty() { "?" } else { v }
}

/// One Library row: `● Name…  v1.2  last: 2 days ago  2h 30m`.
pub fn format_pack_row(p: &PackSummary, running: bool, now_ms: i64, l: &Localizer) -> String {
    let marker = if running { "\u{25CF} " } else { "  " };
    let name = truncate(Some(pack_display_name(p)), 28);
    let version = truncate(Some(version_or_unknown(&p.version)), 12);
    let last = truncate(Some(&last_played(p.last_played_ms, now_ms, l)), 12);
    format!(
        "{marker}{}  v{}  {} {}  {}",
        pad(&name, 28),
        pad(&version, 12),
        l.get("tui.row.lastPlayed"),
        pad(&last, 12),
        format_duration(p.total_play_time_ms, l)
    )
}

/// One Browse row: `Name…  v1.2`.
pub fn format_available_row(p: &PackSummary) -> String {
    format!(
        "{}  v{}",
        pad(&truncate(Some(pack_display_name(p)), 34), 34),
        truncate(Some(version_or_unknown(&p.version)), 14)
    )
}

/// A pack name made safe for a thread name (`tui-launch-<name>`).
pub fn sanitize_for_thread(name: &str) -> String {
    let s: String = name
        .chars()
        .map(|c| {
            if c.is_ascii_alphanumeric() || c == '-' || c == '_' {
                c
            } else {
                '_'
            }
        })
        .take(40)
        .collect();
    if s.is_empty() { "pack".to_string() } else { s }
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;
    use mmcl_engine::{PackId, PackLoadState};
    use mmcl_i18n::Locale;
    use std::sync::LazyLock;

    pub(crate) static EN: LazyLock<Localizer> = LazyLock::new(|| Localizer::new(Locale::en_us()));

    pub(crate) fn en() -> &'static Localizer {
        &EN
    }

    pub(crate) fn pack(name: &str, url: &str) -> PackSummary {
        PackSummary {
            id: PackId::new(url),
            name: name.to_string(),
            friendly_name: name.to_string(),
            version: "1.0".into(),
            installed_version: None,
            installed: true,
            update_available: false,
            is_vanilla: false,
            mod_loader: Some("forge".into()),
            minecraft_version: None,
            unstable: false,
            load_state: PackLoadState::Loaded,
            load_error: None,
            manifest_url: Some(url.to_string()),
            logo_url: None,
            logo_count: 0,
            background_url: None,
            background_count: 0,
            min_ram_gb: 4.0,
            last_played_ms: 0,
            total_play_time_ms: 0,
            launch_count: 0,
            never_played: true,
            unread_news: 0,
            active: false,
            ..Default::default()
        }
    }

    // TuiAppFormattingTest

    #[test]
    fn duration_zero_and_negative_are_zero_minutes() {
        assert_eq!(format_duration(0, en()), "0m");
        assert_eq!(format_duration(-1000, en()), "0m");
    }

    #[test]
    fn duration_under_a_minute() {
        assert_eq!(format_duration(1, en()), "<1m");
        assert_eq!(format_duration(59_999, en()), "<1m");
    }

    #[test]
    fn duration_minutes_only() {
        assert_eq!(format_duration(60_000, en()), "1m");
        assert_eq!(format_duration(45 * 60_000, en()), "45m");
        assert_eq!(format_duration(59 * 60_000, en()), "59m");
    }

    #[test]
    fn duration_hours_and_minutes() {
        assert_eq!(format_duration(60 * 60_000, en()), "1h 0m");
        assert_eq!(format_duration(150 * 60_000, en()), "2h 30m");
    }

    #[test]
    fn duration_truncates_rather_than_rounds() {
        assert_eq!(format_duration(90_500, en()), "1m");
    }

    #[test]
    fn duration_has_no_digit_grouping() {
        assert_eq!(format_duration(2_000 * 3_600_000, en()), "2000h 0m");
    }

    #[test]
    fn truncate_none_is_empty() {
        assert_eq!(truncate(None, 5), "");
    }

    #[test]
    fn truncate_at_or_under_max_is_unchanged() {
        assert_eq!(truncate(Some("hello"), 5), "hello");
        assert_eq!(truncate(Some("hi"), 10), "hi");
    }

    #[test]
    fn truncate_over_max_ends_with_ellipsis() {
        assert_eq!(truncate(Some("abcdefghij"), 5), "abcd\u{2026}");
    }

    #[test]
    fn truncate_result_length_equals_max() {
        let out = truncate(Some("a very long modpack name indeed"), 12);
        assert_eq!(out.chars().count(), 12);
    }

    #[test]
    fn truncate_max_zero_is_ellipsis() {
        assert_eq!(truncate(Some("abc"), 0), "\u{2026}");
    }

    // New tests

    #[test]
    fn header_picks_the_longest_form_that_fits() {
        let full = header_text(Some(200), en());
        assert!(full.contains("Mica Launcher"));
        assert!(full.contains("[Enter] actions"));
        let full_w = full.width() as u16;
        assert_eq!(header_text(Some(full_w), en()), full);

        let medium = header_text(Some(full_w - 1), en());
        assert!(!medium.contains("Mica Launcher"));
        assert!(medium.contains("[L] Library"));
        let medium_w = medium.width() as u16;

        let compact = header_text(Some(medium_w - 1), en());
        assert!(compact.starts_with("  [L] [B] [G] [S]"));
        let compact_w = compact.width() as u16;
        assert_eq!(header_text(Some(compact_w), en()), compact);

        let minimal = header_text(Some(compact_w - 1), en());
        assert_eq!(minimal, "  [L][B][G][S]  [Esc][q]");
        assert_eq!(header_text(Some(5), en()), minimal);
    }

    #[test]
    fn header_defaults_to_120_columns() {
        assert_eq!(header_text(None, en()), header_text(Some(120), en()));
    }

    #[test]
    fn status_line_shows_count_playtime_and_user() {
        let s = status_line(2, 150 * 60_000, Some("Steve"), en());
        assert_eq!(
            s,
            "  2 running   \u{00B7}   total playtime 2h 30m   \u{00B7}   Steve"
        );
        let signed_out = status_line(0, 0, None, en());
        assert!(signed_out.ends_with("(signed out)"));
        assert!(status_line(0, 0, Some("  "), en()).ends_with("(signed out)"));
    }

    #[test]
    fn pack_row_pads_and_truncates() {
        let mut p = pack("A really long modpack name that overflows", "u");
        p.version = "1.2.3-beta-extra-long".into();
        p.total_play_time_ms = 150 * 60_000;
        let row = format_pack_row(&p, true, 0, en());
        assert!(row.starts_with("\u{25CF} A really long modpack name \u{2026}"));
        assert!(row.contains("v1.2.3-beta-\u{2026}"));
        assert!(row.contains("last: Never"));
        assert!(row.ends_with("2h 30m"));

        let mut short = pack("Short", "u");
        short.version = String::new();
        let row = format_pack_row(&short, false, 0, en());
        assert!(row.starts_with("  Short"));
        assert_eq!(&row[2..2 + 28], pad("Short", 28));
        assert!(row.contains("v?           "));
    }

    #[test]
    fn friendly_name_falls_back_to_pack_name() {
        let mut p = pack("Name", "u");
        p.friendly_name = " ".into();
        assert_eq!(pack_display_name(&p), "Name");
    }

    #[test]
    fn available_row_layout() {
        let p = pack("Pack", "u");
        assert_eq!(
            format_available_row(&p),
            format!("{}  v1.0", pad("Pack", 34))
        );
    }

    #[test]
    fn pad_uses_display_width() {
        assert_eq!(pad("ab", 4), "ab  ");
        assert_eq!(pad("\u{65E5}\u{672C}", 6), "\u{65E5}\u{672C}  ");
        assert_eq!(pad("abcdef", 3), "abcdef");
    }

    #[test]
    fn thread_names_are_sanitized() {
        assert_eq!(sanitize_for_thread("My Pack: 1.0"), "My_Pack__1_0");
        assert_eq!(sanitize_for_thread(""), "pack");
    }
}
