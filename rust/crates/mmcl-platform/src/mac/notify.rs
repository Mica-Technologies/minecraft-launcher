// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Notification Center banners through `osascript` (`display notification`), which works for
//! an unbundled binary as well as the app bundle.

use std::process::{Command, Stdio};
use std::time::{Duration, Instant};

use crate::error::{PlatformError, Result};

/// An AppleScript string literal: quotes and backslashes escaped, line breaks as spaces.
pub fn applescript_string(s: &str) -> String {
    let mut out = String::with_capacity(s.len() + 2);
    out.push('"');
    for c in s.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\r' | '\n' => out.push(' '),
            c if c < ' ' => {}
            c => out.push(c),
        }
    }
    out.push('"');
    out
}

/// The script that shows one banner.
pub fn script(title: &str, body: &str) -> String {
    format!(
        "display notification {} with title {}",
        applescript_string(body),
        applescript_string(title)
    )
}

/// Shows a banner. Waits at most 5 s for `osascript`.
pub fn show(title: &str, body: &str) -> Result<()> {
    let mut child = Command::new("/usr/bin/osascript")
        .arg("-e")
        .arg(script(title, body))
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()?;
    let deadline = Instant::now() + Duration::from_secs(5);
    loop {
        match child.try_wait()? {
            Some(status) if status.success() => return Ok(()),
            Some(status) => {
                return Err(PlatformError::Other(format!(
                    "osascript exited with {status}"
                )));
            }
            None if Instant::now() >= deadline => {
                let _ = child.kill();
                let _ = child.wait();
                return Err(PlatformError::Other("osascript timed out".into()));
            }
            None => std::thread::sleep(Duration::from_millis(25)),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn strings_cannot_break_out_of_the_script() {
        assert_eq!(applescript_string("a\"b\\c\nd"), "\"a\\\"b\\\\c d\"");
        assert_eq!(
            script("T", "B"),
            "display notification \"B\" with title \"T\""
        );
    }
}
