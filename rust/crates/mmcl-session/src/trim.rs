// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Pure log-trimming helpers (Java `gui/LogTrimPolicy`).
//!
//! Java counts UTF-16 units; these count bytes of a UTF-8 `str`. Every offset returned lands on
//! a char boundary, so slicing with it never panics.
//!
//! A line cap of `0` means "unlimited" (the Settings dropdown's unlimited choice).

/// The smallest slack the display trim allows before it cuts.
pub const MIN_SLACK: usize = 50;

/// Whether a line cap means "no limit".
pub fn is_unlimited(max_lines: usize) -> bool {
    max_lines == 0
}

/// How far past the cap a display may grow before it is cut back: a fifth of the cap, at
/// least [`MIN_SLACK`]. Trimming in steps keeps a fast log from re-cutting on every line.
pub fn slack_for(max_lines: usize) -> usize {
    MIN_SLACK.max(max_lines / 5)
}

/// Whether a display holding `display_line_count` lines should be cut back to `max_lines`.
pub fn should_trim_display(display_line_count: usize, max_lines: usize) -> bool {
    if is_unlimited(max_lines) {
        return false;
    }
    display_line_count > max_lines + slack_for(max_lines)
}

/// How many lines to drop from the front of a display (0 until the slack is used up, then back
/// to the cap).
pub fn display_lines_to_drop(display_line_count: usize, max_lines: usize) -> usize {
    if should_trim_display(display_line_count, max_lines) {
        display_line_count - max_lines
    } else {
        0
    }
}

/// The offset just after the `lines_to_drop`-th `'\n'` (or after the last one, when there are
/// fewer). 0 when nothing should be dropped.
pub fn display_drop_offset(text: &str, lines_to_drop: usize) -> usize {
    if text.is_empty() || lines_to_drop == 0 {
        return 0;
    }
    let mut idx = 0;
    for _ in 0..lines_to_drop {
        match text[idx..].find('\n') {
            Some(n) => idx += n + 1,
            None => break,
        }
        if idx >= text.len() {
            break;
        }
    }
    idx
}

/// The last `max_lines` lines of `text` (all of it when unlimited or shorter).
pub fn tail_lines(text: &str, max_lines: usize) -> &str {
    &text[tail_start(text, max_lines)..]
}

/// Where the last `max_lines` lines of `text` start. A final `'\n'` ends the last line rather
/// than starting another. 0 when unlimited or when there are not that many lines.
pub fn tail_start(text: &str, max_lines: usize) -> usize {
    if text.is_empty() || is_unlimited(max_lines) {
        return 0;
    }
    let bytes = text.as_bytes();
    let mut end = bytes.len();
    if bytes[end - 1] == b'\n' {
        end -= 1;
    }
    let mut seen = 0;
    for i in (0..end).rev() {
        if bytes[i] == b'\n' {
            seen += 1;
            if seen == max_lines {
                return i + 1;
            }
        }
    }
    0
}

/// Where to cut a capture buffer that has grown past `trigger` so that about `retain` bytes are
/// left: just after the first `'\n'` at or after `len - retain`, or that raw offset (moved
/// forward to a char boundary) when no newline follows. 0 when no cut is due.
pub fn full_log_drop_offset(buf: &str, trigger: usize, retain: usize) -> usize {
    let len = buf.len();
    if len <= trigger {
        return 0;
    }
    let Some(drop_to) = len.checked_sub(retain).filter(|d| *d > 0) else {
        return 0;
    };
    if let Some(n) = buf.as_bytes()[drop_to..].iter().position(|b| *b == b'\n') {
        return drop_to + n + 1;
    }
    let mut cut = drop_to;
    while !buf.is_char_boundary(cut) {
        cut += 1;
    }
    cut
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn zero_means_unlimited_and_disables_trimming() {
        assert!(is_unlimited(0));
        assert!(!should_trim_display(10_000_000, 0));
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
        assert_eq!(display_drop_offset(text, 1), 2);
        assert_eq!(display_drop_offset(text, 2), 4);
        assert_eq!(display_drop_offset(text, 3), 6);
    }

    #[test]
    fn drop_offset_is_zero_when_nothing_should_be_dropped() {
        assert_eq!(display_drop_offset("a\nb\n", 0), 0);
    }

    #[test]
    fn drop_offset_handles_empty_text() {
        assert_eq!(display_drop_offset("", 3), 0);
    }

    #[test]
    fn drop_offset_stops_at_the_last_newline_when_asked_for_more_lines_than_exist() {
        let text = "a\nb\nc";
        let off = display_drop_offset(text, 99);
        assert_eq!(off, 4);
        assert!(off <= text.len());
    }

    #[test]
    fn drop_offset_on_text_with_no_newlines_drops_nothing() {
        assert_eq!(display_drop_offset("single line", 1), 0);
    }

    #[test]
    fn full_log_does_not_trim_below_the_trigger() {
        assert_eq!(full_log_drop_offset("a\nb\nc\n", 100, 50), 0);
    }

    #[test]
    fn full_log_does_not_trim_exactly_at_the_trigger() {
        let buf = "x".repeat(100);
        assert_eq!(full_log_drop_offset(&buf, 100, 50), 0);
    }

    #[test]
    fn full_log_trim_advances_to_the_next_line_boundary() {
        assert_eq!(full_log_drop_offset("aaaaaaaaa\nbbbbbbbbb\n", 10, 5), 20);
    }

    #[test]
    fn full_log_falls_back_to_the_raw_offset_when_no_newline_follows() {
        assert_eq!(full_log_drop_offset(&"a".repeat(20), 10, 5), 15);
    }

    #[test]
    fn full_log_raw_cut_lands_on_a_char_boundary() {
        // 10 two-byte chars: raw cut at 20 - 5 = 15 falls inside a char, moves to 16.
        let buf = "é".repeat(10);
        let off = full_log_drop_offset(&buf, 10, 5);
        assert_eq!(off, 16);
        assert!(buf.is_char_boundary(off));
    }

    #[test]
    fn retain_larger_than_buffer_drops_nothing() {
        assert_eq!(full_log_drop_offset("abcdef", 2, 100), 0);
    }

    #[test]
    fn retain_below_trigger_yields_forward_progress() {
        let buf = "line\n".repeat(200);
        let off = full_log_drop_offset(&buf, 500, 400);
        assert!(off > 0);
        assert!(buf.len() - off <= 400 + 5);
    }

    #[test]
    fn tail_lines_keeps_the_last_lines_only() {
        assert_eq!(tail_lines("a\nb\nc\nd\n", 2), "c\nd\n");
        assert_eq!(tail_lines("a\nb\nc\nd", 2), "c\nd");
    }

    #[test]
    fn tail_lines_leaves_short_or_unlimited_text_alone() {
        let text = "a\nb\n";
        assert_eq!(tail_lines(text, 2), text);
        assert_eq!(tail_lines(text, 10), text);
        assert_eq!(tail_lines(text, 0), text);
        assert_eq!(tail_lines("", 5), "");
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
