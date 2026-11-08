// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The "scan blocked this launch" error and its user-facing message (Java
//! `ModpackScanDetectionException`, localized here where Java was hardcoded English).

use std::fmt;
use std::path::{Path, PathBuf};

use mmcl_i18n::{t, tf};

use crate::neko::Results;
use crate::supplemental::{Finding, Severity};

/// A pre-launch scan found blocking issues.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct ScanBlocked {
    /// Neko stage 1 hits (jar paths) followed by supplemental HIGH findings
    /// (`Finding::to_string()`).
    pub stage1_and_high: Vec<String>,
    /// Neko stage 2 hits (machine-level indicators).
    pub stage2: Vec<String>,
    /// The pack's display name, for the header.
    pub pack_name: Option<String>,
    /// The pack folder, stripped from the start of each line.
    pub pack_root: Option<PathBuf>,
}

impl ScanBlocked {
    /// Builds the error from a Neko run plus the supplemental findings that block (HIGH only;
    /// MEDIUM never blocks). Returns `None` when nothing blocks.
    pub fn from_results(
        neko: &Results,
        supplemental: &[Finding],
        pack_name: Option<String>,
        pack_root: Option<PathBuf>,
    ) -> Option<ScanBlocked> {
        let mut stage1_and_high = neko.stage1.clone();
        stage1_and_high.extend(
            supplemental
                .iter()
                .filter(|f| f.severity == Severity::High)
                .map(Finding::to_string),
        );
        if stage1_and_high.is_empty() && neko.stage2.is_empty() {
            return None;
        }
        Some(ScanBlocked {
            stage1_and_high,
            stage2: neko.stage2.clone(),
            pack_name,
            pack_root,
        })
    }

    /// Every distinct finding line, in order (stage 1 / HIGH first, then stage 2).
    pub fn unique_findings(&self) -> Vec<&str> {
        let mut out: Vec<&str> = Vec::new();
        for s in self.stage1_and_high.iter().chain(self.stage2.iter()) {
            if !out.contains(&s.as_str()) {
                out.push(s);
            }
        }
        out
    }

    /// The multi-line message shown to the user (bullets kept; the caller logs it with
    /// `error_multiline`).
    pub fn message(&self) -> String {
        let uniq = self.unique_findings();
        if uniq.is_empty() {
            return t!("scan.blocked.none").into_owned();
        }
        let label = match self.pack_name.as_deref() {
            Some(n) if !n.trim().is_empty() => tf!("scan.blocked.packLabel", n),
            _ => t!("scan.blocked.thisModpack").into_owned(),
        };
        let mut out = tf!("scan.blocked.header", label, uniq.len());
        out.push_str("\n\n");
        for raw in uniq {
            out.push_str(&tf!(
                "scan.blocked.item",
                clean_finding_line(raw, self.pack_root.as_deref())
            ));
            out.push('\n');
        }
        out.push('\n');
        out.push_str(&t!("scan.blocked.footer"));
        out
    }
}

impl fmt::Display for ScanBlocked {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.message())
    }
}

impl std::error::Error for ScanBlocked {}

fn is_java_space(c: char) -> bool {
    matches!(c, ' ' | '\t' | '\n' | '\x0B' | '\x0C' | '\r')
}

/// Strips a leading `^\[[A-Za-z]+]\s+` severity tag.
fn strip_severity_tag(s: &str) -> &str {
    let Some(rest) = s.strip_prefix('[') else {
        return s;
    };
    let letters = rest.bytes().take_while(u8::is_ascii_alphabetic).count();
    if letters == 0 {
        return s;
    }
    let Some(after) = rest[letters..].strip_prefix(']') else {
        return s;
    };
    let trimmed = after.trim_start_matches(is_java_space);
    if trimmed.len() == after.len() {
        return s;
    }
    trimmed
}

/// One finding line as shown in the message: trimmed, without its severity tag, and relative to
/// the pack root when it starts with it (native separators first, then a `/`-normalised
/// comparison that cuts the same number of characters off the original, keeping its separators).
pub fn clean_finding_line(raw: &str, pack_root: Option<&Path>) -> String {
    let s = strip_severity_tag(raw.trim());
    let Some(root) = pack_root else {
        return s.to_owned();
    };
    let root = root.to_string_lossy();
    if root.trim().is_empty() {
        return s.to_owned();
    }
    let sep = std::path::MAIN_SEPARATOR;
    let mut prefix = root.to_string();
    if !prefix.ends_with(sep) {
        prefix.push(sep);
    }
    if let Some(rest) = s.strip_prefix(prefix.as_str()) {
        return rest.to_owned();
    }
    let mut prefix_slash = root.replace('\\', "/");
    if !prefix_slash.ends_with('/') {
        prefix_slash.push('/');
    }
    let s_slash = s.replace('\\', "/");
    if s_slash.starts_with(&prefix_slash) {
        // `\` and `/` are both one byte, so the byte length carries over to the original.
        return s[prefix_slash.len()..].to_owned();
    }
    s.to_owned()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn severity_tag_stripping() {
        assert_eq!(strip_severity_tag("[HIGH] a"), "a");
        assert_eq!(strip_severity_tag("[MEDIUM]\t\ta"), "a");
        assert_eq!(strip_severity_tag("[HIGH]a"), "[HIGH]a");
        assert_eq!(strip_severity_tag("[] a"), "[] a");
        assert_eq!(strip_severity_tag("[H1] a"), "[H1] a");
        assert_eq!(strip_severity_tag("plain"), "plain");
    }
}
