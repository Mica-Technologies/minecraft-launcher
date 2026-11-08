// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! `LogTrimPolicy`: the arithmetic behind the two log caps.
//!
//! The visible view is capped in **lines** (the `consoleLogMaxLines` setting, `0` = unlimited);
//! the in-memory full-log capture is capped in **characters**. Both exist so a long session cannot
//! exhaust memory. The view trims only once it is past its cap by a slack (a fifth of the cap,
//! at least [`MIN_SLACK`]), then cuts back to the cap, so a busy game does not trim on every
//! batch.
//!
//! Every offset is a byte offset. `'\n'` is ASCII, so scanning bytes for it always lands on a
//! char boundary; the one fallback that may not ([`full_log_drop_offset`] with no newline after
//! the cut) is rounded forward to the next boundary.

/// The smallest slack the view gets before it trims.
pub const MIN_SLACK: i32 = 50;

/// Whether a line cap means "keep everything" (`0` or negative).
pub fn is_unlimited(max_lines: i32) -> bool {
    max_lines <= 0
}

/// How far past the cap the view may grow before it trims.
pub fn slack_for(max_lines: i32) -> i32 {
    MIN_SLACK.max(max_lines / 5)
}

/// Whether a view of `count` lines should trim.
pub fn should_trim_display(count: i32, max_lines: i32) -> bool {
    !is_unlimited(max_lines)
        && i64::from(count) > i64::from(max_lines) + i64::from(slack_for(max_lines))
}

/// How many lines to drop from the front of a view of `count` lines (cuts back to the cap).
pub fn display_lines_to_drop(count: i32, max_lines: i32) -> i32 {
    if should_trim_display(count, max_lines) {
        count - max_lines
    } else {
        0
    }
}

/// The offset just after the first `n` newlines (stops at the last one when there are fewer);
/// `0` for `None`, `""` or `n <= 0`. Never past the end.
pub fn display_drop_offset(text: Option<&str>, n: i32) -> usize {
    let Some(text) = text else { return 0 };
    if n <= 0 || text.is_empty() {
        return 0;
    }
    let mut seen = 0;
    let mut offset = 0;
    for (i, b) in text.bytes().enumerate() {
        if b == b'\n' {
            seen += 1;
            offset = i + 1;
            if seen == n {
                break;
            }
        }
    }
    offset
}

/// The start of the last `max_lines` lines, scanning backward (so the cost is the tail's
/// length, not the whole capture's). `0` for empty text or an unlimited cap. A final `'\n'` ends
/// the last line; an unterminated last line counts as a line.
pub fn tail_start(text: &str, max_lines: i32) -> usize {
    if text.is_empty() || is_unlimited(max_lines) {
        return 0;
    }
    let bytes = text.as_bytes();
    // A terminating newline belongs to the last line, not a new empty one.
    let end = if bytes.last() == Some(&b'\n') {
        bytes.len() - 1
    } else {
        bytes.len()
    };
    let mut seen = 0;
    for (i, &b) in bytes[..end].iter().enumerate().rev() {
        if b == b'\n' {
            seen += 1;
            if seen == max_lines {
                return i + 1;
            }
        }
    }
    0
}

/// The last `max_lines` lines of `text` (`None` gives `""`).
pub fn tail_lines(text: Option<&str>, max_lines: i32) -> String {
    match text {
        None => String::new(),
        Some(t) => t[tail_start(t, max_lines)..].to_string(),
    }
}

/// How much of the full-log capture to drop: `0` while its length is at most `trigger`;
/// otherwise just after the first newline at or after `len - retain` (or that point itself when
/// no newline follows, rounded forward to a char boundary).
pub fn full_log_drop_offset(buf: Option<&str>, trigger: usize, retain: usize) -> usize {
    let Some(buf) = buf else { return 0 };
    let len = buf.len();
    if len <= trigger {
        return 0;
    }
    let drop_to = len.saturating_sub(retain);
    if drop_to == 0 {
        return 0;
    }
    match buf.as_bytes()[drop_to..].iter().position(|&b| b == b'\n') {
        Some(i) => drop_to + i + 1,
        None => {
            let mut at = drop_to;
            while at < len && !buf.is_char_boundary(at) {
                at += 1;
            }
            at
        }
    }
}

#[cfg(test)]
mod tests {
    //! Port of `LogTrimPolicyTest`.
    use super::*;

    #[test]
    fn zero_means_unlimited_and_disables_trimming() {
        assert!(is_unlimited(0));
        assert!(!should_trim_display(10_000_000, 0));
    }

    #[test]
    fn negative_caps_are_treated_as_unlimited() {
        assert!(is_unlimited(-1));
        assert!(!should_trim_display(999_999, -1));
    }

    #[test]
    fn positive_cap_is_not_unlimited() {
        assert!(!is_unlimited(1));
    }

    #[test]
    fn slack_is_a_fifth_of_the_cap_for_large_caps() {
        assert_eq!(slack_for(10_000), 2_000);
    }

    #[test]
    fn slack_is_floored_for_small_caps() {
        assert_eq!(slack_for(100), MIN_SLACK);
        assert_eq!(slack_for(0), MIN_SLACK);
    }

    #[test]
    fn does_not_trim_at_or_below_the_cap() {
        assert!(!should_trim_display(10_000, 10_000));
        assert!(!should_trim_display(9_999, 10_000));
    }

    #[test]
    fn does_not_trim_within_the_slack_window() {
        assert!(!should_trim_display(12_000, 10_000));
    }

    #[test]
    fn trims_once_past_the_slack_window() {
        assert!(should_trim_display(12_001, 10_000));
    }

    #[test]
    fn small_cap_uses_the_floored_slack() {
        assert!(!should_trim_display(150, 100));
        assert!(should_trim_display(151, 100));
    }

    #[test]
    fn drop_offset_lands_just_after_the_nth_newline() {
        let text = "a\nb\nc\nd\n";
        assert_eq!(display_drop_offset(Some(text), 1), 2);
        assert_eq!(display_drop_offset(Some(text), 2), 4);
        assert_eq!(display_drop_offset(Some(text), 3), 6);
    }

    #[test]
    fn drop_offset_is_zero_when_nothing_should_be_dropped() {
        assert_eq!(display_drop_offset(Some("a\nb\n"), 0), 0);
        assert_eq!(display_drop_offset(Some("a\nb\n"), -5), 0);
    }

    #[test]
    fn drop_offset_handles_null_and_empty_text() {
        assert_eq!(display_drop_offset(None, 3), 0);
        assert_eq!(display_drop_offset(Some(""), 3), 0);
    }

    #[test]
    fn drop_offset_stops_at_the_last_newline_when_asked_for_more_lines_than_exist() {
        let text = "a\nb\nc";
        let off = display_drop_offset(Some(text), 99);
        assert_eq!(off, 4);
        assert!(off <= text.len());
    }

    #[test]
    fn drop_offset_on_text_with_no_newlines_drops_nothing() {
        assert_eq!(display_drop_offset(Some("single line"), 1), 0);
    }

    #[test]
    fn full_log_does_not_trim_below_the_trigger() {
        assert_eq!(full_log_drop_offset(Some("a\nb\nc\n"), 100, 50), 0);
    }

    #[test]
    fn full_log_does_not_trim_exactly_at_the_trigger() {
        let buf = "x".repeat(100);
        assert_eq!(full_log_drop_offset(Some(&buf), 100, 50), 0);
    }

    #[test]
    fn full_log_trim_advances_to_the_next_line_boundary() {
        // trigger 10, retain 5 -> raw drop_to = 20 - 5 = 15; the next newline is at 19.
        assert_eq!(
            full_log_drop_offset(Some("aaaaaaaaa\nbbbbbbbbb\n"), 10, 5),
            20
        );
    }

    #[test]
    fn full_log_falls_back_to_the_raw_offset_when_no_newline_follows() {
        assert_eq!(
            full_log_drop_offset(Some("aaaaaaaaaaaaaaaaaaaa"), 10, 5),
            15
        );
    }

    #[test]
    fn full_log_handles_null_buffer() {
        assert_eq!(full_log_drop_offset(None, 10, 5), 0);
    }

    #[test]
    fn retain_below_trigger_yields_forward_progress() {
        let buf = "line\n".repeat(200);
        let off = full_log_drop_offset(Some(&buf), 500, 400);
        assert!(off > 0);
        assert!(buf.len() - off <= 400 + 5);
    }

    #[test]
    fn full_log_fallback_lands_on_a_char_boundary() {
        // 'é' is two bytes; a raw cut inside it must move forward.
        let buf = "ééééééééééé";
        let off = full_log_drop_offset(Some(buf), 4, 5);
        assert!(buf.is_char_boundary(off));
    }

    #[test]
    fn tail_lines_keeps_the_last_lines_only() {
        assert_eq!(tail_lines(Some("a\nb\nc\nd\n"), 2), "c\nd\n");
        assert_eq!(tail_lines(Some("a\nb\nc\nd"), 2), "c\nd");
    }

    #[test]
    fn tail_lines_leaves_short_or_unlimited_text_alone() {
        let text = "a\nb\n";
        assert_eq!(tail_lines(Some(text), 2), text);
        assert_eq!(tail_lines(Some(text), 10), text);
        assert_eq!(tail_lines(Some(text), 0), text);
        assert_eq!(tail_lines(None, 5), "");
        assert_eq!(tail_lines(Some(""), 5), "");
    }

    #[test]
    fn tail_start_finds_the_last_lines_from_the_end() {
        assert_eq!(tail_start("a\nb\nc\nd\n", 2), 4);
        assert_eq!(tail_start("a\nb\nc\nd", 2), 4);
        assert_eq!(tail_start("\n\n\n", 2), 1);
        assert_eq!(tail_start("a\nb\n", 2), 0);
        assert_eq!(tail_start("a\nb\n", 0), 0);
        assert_eq!(tail_start("", 3), 0);
    }

    #[test]
    fn display_lines_to_drop_waits_for_the_slack_then_cuts_back_to_the_cap() {
        assert_eq!(display_lines_to_drop(1000, 1000), 0);
        assert_eq!(display_lines_to_drop(1200, 1000), 0);
        assert_eq!(display_lines_to_drop(1201, 1000), 201);
        assert_eq!(display_lines_to_drop(6000, 1000), 5000);
        assert_eq!(display_lines_to_drop(1_000_000, 0), 0);
    }
}
