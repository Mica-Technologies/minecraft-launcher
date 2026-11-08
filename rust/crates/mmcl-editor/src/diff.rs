// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The Diff dialog's text.
//!
//! Java compared the two JSON texts line by line by position, so one inserted line marked every
//! following line as changed. [`line_diff`] uses a real line diff (`similar`, Myers/LCS) and
//! prints each change hunk under an `@@ -old +new @@` header; [`positional_diff`] keeps the Java
//! output for reference and tests.

use similar::{ChangeTag, TextDiff};

/// Java `String.split("\n")`: trailing empty strings dropped.
pub fn java_split_lines(s: &str) -> Vec<&str> {
    let mut parts: Vec<&str> = s.split('\n').collect();
    while parts.last().is_some_and(|p| p.is_empty()) {
        parts.pop();
    }
    parts
}

/// Java's positional diff: for every index, `- old` / `+ new` (trimmed) where the lines differ.
pub fn positional_diff(original: &str, current: &str) -> String {
    let o = java_split_lines(original);
    let c = java_split_lines(current);
    let mut out = String::new();
    for i in 0..o.len().max(c.len()) {
        let ol = o.get(i).copied().unwrap_or("");
        let cl = c.get(i).copied().unwrap_or("");
        if ol != cl {
            if !ol.is_empty() {
                out.push_str("- ");
                out.push_str(ol.trim());
                out.push('\n');
            }
            if !cl.is_empty() {
                out.push_str("+ ");
                out.push_str(cl.trim());
                out.push('\n');
            }
        }
    }
    out
}

/// A line diff of `original` -> `current`: each hunk starts with `@@ -<old line> +<new line> @@`
/// (1-based), followed by `- removed` and `+ added` lines (trimmed). No unchanged context is
/// printed. Empty when the texts have the same lines.
pub fn line_diff(original: &str, current: &str) -> String {
    // Every line newline-terminated, so the last line compares like the others.
    let terminated = |s: &str| -> String {
        java_split_lines(s)
            .iter()
            .map(|l| format!("{l}\n"))
            .collect()
    };
    let o = terminated(original);
    let c = terminated(current);
    let diff = TextDiff::from_lines(&o, &c);
    let mut out = String::new();
    for group in diff.grouped_ops(0) {
        let Some(first) = group.first() else { continue };
        let old_start = first.old_range().start + 1;
        let new_start = first.new_range().start + 1;
        out.push_str(&format!("@@ -{old_start} +{new_start} @@\n"));
        for op in &group {
            for change in diff.iter_changes(op) {
                let prefix = match change.tag() {
                    ChangeTag::Delete => "- ",
                    ChangeTag::Insert => "+ ",
                    ChangeTag::Equal => continue,
                };
                out.push_str(prefix);
                out.push_str(change.value().trim());
                out.push('\n');
            }
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn java_split_drops_trailing_empties_only() {
        assert_eq!(java_split_lines("a\nb\n\n"), ["a", "b"]);
        assert_eq!(java_split_lines("a\n\nb"), ["a", "", "b"]);
        assert!(java_split_lines("").is_empty());
    }

    #[test]
    fn positional_diff_matches_java() {
        assert_eq!(positional_diff("a\nb\nc", "a\nB\nc"), "- b\n+ B\n");
        assert_eq!(positional_diff("a", "a\n  b"), "+ b\n");
        // One inserted line shifts everything after it.
        assert_eq!(
            positional_diff("a\nb", "x\na\nb"),
            "- a\n+ x\n- b\n+ a\n+ b\n"
        );
        assert_eq!(positional_diff("same", "same"), "");
    }

    #[test]
    fn line_diff_reports_only_the_insertion() {
        let d = line_diff("a\nb\nc", "a\nx\nb\nc");
        assert_eq!(d, "@@ -2 +2 @@\n+ x\n");
    }

    #[test]
    fn line_diff_groups_replacements() {
        let orig = "{\n  \"packName\": \"A\",\n  \"packVersion\": \"1.0.0\"\n}";
        let curr = "{\n  \"packName\": \"B\",\n  \"packVersion\": \"1.0.0\"\n}";
        assert_eq!(
            line_diff(orig, curr),
            "@@ -2 +2 @@\n- \"packName\": \"A\",\n+ \"packName\": \"B\",\n"
        );
        assert_eq!(line_diff(orig, orig), "");
        // A trailing newline alone is not a change (Java split semantics).
        assert_eq!(line_diff("a\nb", "a\nb\n"), "");
    }

    #[test]
    fn line_diff_handles_removals_at_the_end() {
        assert_eq!(line_diff("a\nb\nc", "a"), "@@ -2 +2 @@\n- b\n- c\n");
    }
}
