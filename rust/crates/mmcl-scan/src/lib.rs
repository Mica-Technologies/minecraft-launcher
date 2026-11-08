// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The pre-launch security scan of the Mica Minecraft Launcher, replacing the Java launcher's
//! vendored jarscanner + ASM with a self-contained Rust port.
//!
//! Two passes run over a pack folder before launch:
//!
//! * [`neko`]: the Nekodetector Fractureiser scanner, i.e. stage 1 bytecode signatures in every
//!   jar plus the stage 2 machine-level indicators of compromise;
//! * [`supplemental`]: heuristics for embedded executables, natives in odd places, webhook and
//!   paste-host URLs, other launchers' credential files, IPv4 literals and clipboard stealers.
//!
//! Manifest-supplied exclusions go through the [`exclusion`] policy gate inside both scanners.
//! Blocking results become a [`ScanBlocked`] error whose message is localized. Both passes read
//! classes with the small purpose-built decoder in [`classfile`].
//!
//! Orchestration (scan frequency, acknowledgements, toasts) belongs to the modpack crate; this
//! crate only scans.

pub mod blocked;
pub mod classfile;
pub mod exclusion;
pub mod neko;
pub mod supplemental;
mod walk;

pub use blocked::{ScanBlocked, clean_finding_line};
pub use exclusion::{filter_untrusted, rejection_reason};
pub use mmcl_net::CancelToken;
pub use neko::{NekoError, Results, Stage2Env};
pub use supplemental::{Finding, Kind, Severity};

/// Strips the ANSI colour codes the Java scanner put in its progress lines (`ESC[31m`,
/// `ESC[32m`, `ESC[37m`, `ESC[0m`). The Rust scanner emits none, but text relayed from elsewhere
/// may still carry them.
pub fn strip_ansi(s: &str) -> String {
    s.replace("\u{1b}[31m", "")
        .replace("\u{1b}[32m", "")
        .replace("\u{1b}[37m", "")
        .replace("\u{1b}[0m", "")
}

#[cfg(test)]
mod tests {
    #[test]
    fn strip_ansi_removes_the_four_codes() {
        assert_eq!(
            super::strip_ansi("\u{1b}[32mStarting\u{1b}[0m - \u{1b}[31mx\u{1b}[37m"),
            "Starting - x"
        );
    }
}
