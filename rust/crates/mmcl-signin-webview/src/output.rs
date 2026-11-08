// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The helper's stdout protocol and exit codes.
//!
//! stdout carries the captured redirect URL as its last line: the parent takes the first line
//! that is the redirect and parses it (`mmcl_auth::signin::parse_callback`). The only other
//! lines are the `mmcl-signin:` page lines of [`crate::page`], which never carry a URL. Logs go through
//! `tracing` (stderr at most), never stdout. The URL holds a single-use authorization code, so
//! it is never logged.

use std::io::{self, Write};

/// The redirect was captured and printed on stdout.
pub const EXIT_REDIRECT: i32 = 0;
/// The window or web view could not be created, or stdout could not be written.
pub const EXIT_FAILED: i32 = 1;
/// The user closed the window before sign-in finished.
pub const EXIT_CANCELLED: i32 = 2;
/// The arguments were missing or invalid (see [`crate::ConfigError`]).
pub const EXIT_BAD_ARGS: i32 = 3;
/// `--watch-stdin` was given and stdin closed: the parent stopped waiting.
pub const EXIT_PARENT_GONE: i32 = 4;

/// How a helper run ended, as the parent sees it from the exit code.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum HelperExit {
    /// [`EXIT_REDIRECT`]
    Redirect,
    /// [`EXIT_FAILED`]
    Failed,
    /// [`EXIT_CANCELLED`]
    Cancelled,
    /// [`EXIT_BAD_ARGS`]
    BadArgs,
    /// [`EXIT_PARENT_GONE`]
    ParentGone,
}

impl HelperExit {
    /// The process exit code.
    pub const fn code(self) -> i32 {
        match self {
            HelperExit::Redirect => EXIT_REDIRECT,
            HelperExit::Failed => EXIT_FAILED,
            HelperExit::Cancelled => EXIT_CANCELLED,
            HelperExit::BadArgs => EXIT_BAD_ARGS,
            HelperExit::ParentGone => EXIT_PARENT_GONE,
        }
    }

    /// Decodes an exit code; unknown codes (a crash, a signal) are `None`.
    pub const fn from_code(code: i32) -> Option<HelperExit> {
        match code {
            EXIT_REDIRECT => Some(HelperExit::Redirect),
            EXIT_FAILED => Some(HelperExit::Failed),
            EXIT_CANCELLED => Some(HelperExit::Cancelled),
            EXIT_BAD_ARGS => Some(HelperExit::BadArgs),
            EXIT_PARENT_GONE => Some(HelperExit::ParentGone),
            _ => None,
        }
    }
}

/// Writes the redirect URL as the single stdout line and flushes. Line breaks and other control
/// characters are dropped first, so the URL can never split into a second line that the parent
/// would misread.
pub fn emit_redirect(out: &mut impl Write, url: &str) -> io::Result<()> {
    let clean: String = url.chars().filter(|c| !c.is_control()).collect();
    writeln!(out, "{clean}")?;
    out.flush()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_url_is_the_only_line_on_stdout() {
        let mut buf = Vec::new();
        emit_redirect(
            &mut buf,
            "https://login.live.com/oauth20_desktop.srf?code=M.C1&lc=1033",
        )
        .unwrap();
        assert_eq!(
            String::from_utf8(buf).unwrap(),
            "https://login.live.com/oauth20_desktop.srf?code=M.C1&lc=1033\n"
        );
    }

    #[test]
    fn a_line_break_cannot_add_a_second_line() {
        let mut buf = Vec::new();
        emit_redirect(&mut buf, "https://x.test/cb?code=a\r\nb\u{0}").unwrap();
        let s = String::from_utf8(buf).unwrap();
        assert_eq!(s, "https://x.test/cb?code=ab\n");
        assert_eq!(s.lines().count(), 1);
    }

    #[test]
    fn a_closed_stdout_is_an_error() {
        struct Closed;
        impl Write for Closed {
            fn write(&mut self, _: &[u8]) -> io::Result<usize> {
                Err(io::ErrorKind::BrokenPipe.into())
            }
            fn flush(&mut self) -> io::Result<()> {
                Ok(())
            }
        }
        assert!(emit_redirect(&mut Closed, "https://x.test/").is_err());
    }

    #[test]
    fn the_contract_codes_are_zero_for_success_and_two_for_a_close() {
        assert_eq!(EXIT_REDIRECT, 0);
        assert_eq!(EXIT_CANCELLED, 2);
    }

    #[test]
    fn every_exit_code_round_trips() {
        for e in [
            HelperExit::Redirect,
            HelperExit::Failed,
            HelperExit::Cancelled,
            HelperExit::BadArgs,
            HelperExit::ParentGone,
        ] {
            assert_eq!(HelperExit::from_code(e.code()), Some(e));
        }
        assert_eq!(HelperExit::from_code(101), None);
        assert_eq!(HelperExit::from_code(-1), None);
    }
}
