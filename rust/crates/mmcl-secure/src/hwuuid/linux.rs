// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! oshi's Linux hardware-UUID chain (`LinuxComputerSystem.queryUUID`), with the host access
//! behind [`Probe`] so the parsing is tested on every OS.
//!
//! ```text
//! Sysfs.queryUUID      first line of /sys/devices/virtual/dmi/id/product_uuid, if non-empty and != "None"
//! Dmidecode.queryUUID  if elevated: first line of `dmidecode -t system` containing "UUID:",
//!                      line.split("UUID:")[1].trim()
//! Lshal.queryUUID      first line of `lshal` containing "system.hardware.uuid =",
//!                      ParseUtil.getSingleQuoteStringValue(line)
//! Lshw.queryUUID       if elevated: over every line of `lshw -C system`, the LAST line that has
//!                      "uuid:" and neither "product:" nor "serial:", line.split("uuid:")[1].trim()
//! otherwise            "unknown"
//! ```
//!
//! Java exceptions inside the parsers (a marker with nothing after it, a single quote) become
//! [`OshiFailure`], which the fingerprint turns into `""`.

use super::{OshiFailure, UNKNOWN};
use crate::javastr;

/// The sysfs file oshi reads first.
pub const PRODUCT_UUID_PATH: &str = "/sys/devices/virtual/dmi/id/product_uuid";

/// Host access the chain needs.
pub trait Probe {
    /// oshi `FileUtil.getStringFromFile`: the first line of a readable UTF-8 file, else `""`.
    fn first_line(&self, path: &str) -> String;
    /// oshi `UserGroupInfo.isElevated()`: `id -u` is 0.
    fn is_elevated(&self) -> bool;
    /// oshi `ExecutingCommand.runNative(cmd)`: stdout lines, or empty when it cannot run.
    fn run(&self, command: &str) -> Vec<String>;
}

/// Runs oshi's chain against `probe`.
pub fn query_uuid(probe: &dyn Probe) -> Result<String, OshiFailure> {
    let sysfs = probe.first_line(PRODUCT_UUID_PATH);
    if !sysfs.is_empty() && sysfs != "None" {
        return Ok(sysfs);
    }
    let elevated = probe.is_elevated();
    if elevated && let Some(uuid) = dmidecode(&probe.run("dmidecode -t system"))? {
        return Ok(uuid);
    }
    if let Some(uuid) = lshal(&probe.run("lshal"))? {
        return Ok(uuid);
    }
    if elevated && let Some(uuid) = lshw(&probe.run("lshw -C system"))? {
        return Ok(uuid);
    }
    Ok(UNKNOWN.to_string())
}

/// `Dmidecode.queryUUID` over the command's output.
pub fn dmidecode(lines: &[String]) -> Result<Option<String>, OshiFailure> {
    const MARKER: &str = "UUID:";
    match lines.iter().find(|l| l.contains(MARKER)) {
        Some(line) => javastr::split_second(line, MARKER)
            .map(|v| Some(javastr::trim(v).to_string()))
            .ok_or(OshiFailure),
        None => Ok(None),
    }
}

/// `Lshal.queryUUID` over the command's output.
pub fn lshal(lines: &[String]) -> Result<Option<String>, OshiFailure> {
    const MARKER: &str = "system.hardware.uuid =";
    match lines.iter().find(|l| l.contains(MARKER)) {
        Some(line) => javastr::string_between(line, '\'')
            .map(Some)
            .ok_or(OshiFailure),
        None => Ok(None),
    }
}

/// The UUID part of `Lshw`'s static initializer over the command's output.
pub fn lshw(lines: &[String]) -> Result<Option<String>, OshiFailure> {
    let mut uuid = None;
    for line in lines {
        if line.contains("product:") {
            javastr::split_second(line, "product:").ok_or(OshiFailure)?;
        } else if line.contains("serial:") {
            javastr::split_second(line, "serial:").ok_or(OshiFailure)?;
        } else if line.contains("uuid:") {
            let value = javastr::split_second(line, "uuid:").ok_or(OshiFailure)?;
            uuid = Some(javastr::trim(value).to_string());
        }
    }
    Ok(uuid)
}

/// The real host, for Linux builds.
#[cfg(target_os = "linux")]
pub struct HostProbe;

#[cfg(target_os = "linux")]
impl Probe for HostProbe {
    fn first_line(&self, path: &str) -> String {
        // oshi decodes strictly: malformed UTF-8 is an IOException, which reads as "".
        match std::fs::read(path).map(String::from_utf8) {
            Ok(Ok(text)) => javastr::lines(&text)
                .first()
                .map(|l| l.to_string())
                .unwrap_or_default(),
            _ => String::new(),
        }
    }

    fn is_elevated(&self) -> bool {
        // SAFETY: geteuid has no preconditions and cannot fail.
        unsafe { libc::geteuid() == 0 }
    }

    fn run(&self, command: &str) -> Vec<String> {
        run_native(command)
    }
}

/// `ExecutingCommand.runNative(String)`: split on single spaces, run with the environment
/// replaced by `LC_ALL=C` (the program is still looked up on this process's `PATH`, like
/// `Runtime.exec`), and read stdout as UTF-8 lines. Any failure gives no lines.
#[cfg(unix)]
pub fn run_native(command: &str) -> Vec<String> {
    use std::process::{Command, Stdio};

    let mut parts = command.split(' ');
    let Some(program) = parts.next().filter(|p| !p.is_empty()) else {
        return Vec::new();
    };
    let Some(resolved) = find_on_path(program) else {
        return Vec::new();
    };
    let output = Command::new(resolved)
        .args(parts)
        .env_clear()
        .env("LC_ALL", "C")
        .stdin(Stdio::null())
        .stderr(Stdio::null())
        .output();
    match output {
        Ok(out) => javastr::lines(&String::from_utf8_lossy(&out.stdout))
            .into_iter()
            .map(str::to_string)
            .collect(),
        Err(_) => Vec::new(),
    }
}

#[cfg(unix)]
fn find_on_path(program: &str) -> Option<std::path::PathBuf> {
    use std::os::unix::fs::PermissionsExt;

    if program.contains('/') {
        return Some(program.into());
    }
    let path = std::env::var_os("PATH")?;
    std::env::split_paths(&path)
        .map(|dir| dir.join(program))
        .find(|candidate| {
            std::fs::metadata(candidate)
                .map(|m| m.is_file() && m.permissions().mode() & 0o111 != 0)
                .unwrap_or(false)
        })
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::cell::RefCell;
    use std::collections::HashMap;

    #[derive(Default)]
    struct FakeProbe {
        sysfs: String,
        elevated: bool,
        outputs: HashMap<&'static str, Vec<String>>,
        ran: RefCell<Vec<String>>,
    }

    impl Probe for FakeProbe {
        fn first_line(&self, path: &str) -> String {
            assert_eq!(path, PRODUCT_UUID_PATH);
            self.sysfs.clone()
        }
        fn is_elevated(&self) -> bool {
            self.elevated
        }
        fn run(&self, command: &str) -> Vec<String> {
            self.ran.borrow_mut().push(command.to_string());
            self.outputs.get(command).cloned().unwrap_or_default()
        }
    }

    fn lines(text: &[&str]) -> Vec<String> {
        text.iter().map(|s| s.to_string()).collect()
    }

    #[test]
    fn sysfs_wins_and_is_not_trimmed() {
        let probe = FakeProbe {
            sysfs: "4c4c4544-0042 ".into(),
            ..Default::default()
        };
        assert_eq!(query_uuid(&probe), Ok("4c4c4544-0042 ".into()));
        assert!(probe.ran.borrow().is_empty());
    }

    #[test]
    fn a_normal_user_without_hal_gets_unknown() {
        let probe = FakeProbe::default();
        assert_eq!(query_uuid(&probe), Ok("unknown".into()));
        assert_eq!(*probe.ran.borrow(), vec!["lshal".to_string()]);
    }

    #[test]
    fn none_in_sysfs_is_ignored() {
        let probe = FakeProbe {
            sysfs: "None".into(),
            ..Default::default()
        };
        assert_eq!(query_uuid(&probe), Ok("unknown".into()));
    }

    #[test]
    fn root_reads_dmidecode() {
        let mut probe = FakeProbe {
            elevated: true,
            ..Default::default()
        };
        probe.outputs.insert(
            "dmidecode -t system",
            lines(&[
                "# dmidecode 3.5",
                "System Information",
                "\tManufacturer: Dell Inc.",
                "\tUUID: 4c4c4544-0042-1234-8000-b2c04f563432",
            ]),
        );
        assert_eq!(
            query_uuid(&probe),
            Ok("4c4c4544-0042-1234-8000-b2c04f563432".into())
        );
    }

    #[test]
    fn non_root_skips_dmidecode_and_lshw() {
        let mut probe = FakeProbe::default();
        probe
            .outputs
            .insert("dmidecode -t system", lines(&["\tUUID: X"]));
        probe
            .outputs
            .insert("lshw -C system", lines(&["  uuid: Y"]));
        assert_eq!(query_uuid(&probe), Ok("unknown".into()));
    }

    #[test]
    fn lshal_value_is_single_quoted() {
        let mut probe = FakeProbe::default();
        probe.outputs.insert(
            "lshal",
            lines(&["  system.hardware.uuid = 'AB-CD'  (string)"]),
        );
        assert_eq!(query_uuid(&probe), Ok("AB-CD".into()));
    }

    #[test]
    fn lshal_with_one_quote_throws() {
        let mut probe = FakeProbe::default();
        probe
            .outputs
            .insert("lshal", lines(&["system.hardware.uuid = 'AB"]));
        assert_eq!(query_uuid(&probe), Err(OshiFailure));
    }

    #[test]
    fn lshw_takes_the_last_uuid_line_without_product_or_serial() {
        assert_eq!(
            lshw(&lines(&[
                "    product: Box (SKU)",
                "    uuid: FIRST",
                "    serial: 123 uuid: IGNORED",
                "    uuid: LAST",
            ])),
            Ok(Some("LAST".into()))
        );
        assert_eq!(lshw(&lines(&["    uuid:"])), Err(OshiFailure));
    }

    #[test]
    fn dmidecode_marker_without_value_throws() {
        assert_eq!(dmidecode(&lines(&["\tUUID:"])), Err(OshiFailure));
        assert_eq!(dmidecode(&lines(&["nothing"])), Ok(None));
    }

    #[cfg(target_os = "linux")]
    #[test]
    fn host_probe_reads_the_first_line() {
        let dir = tempfile::tempdir().unwrap();
        let file = dir.path().join("uuid");
        std::fs::write(&file, "abc\ndef\n").unwrap();
        assert_eq!(HostProbe.first_line(file.to_str().unwrap()), "abc");
        assert_eq!(HostProbe.first_line("/nonexistent/mmcl"), "");
    }
}
