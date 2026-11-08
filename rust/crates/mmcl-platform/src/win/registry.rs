// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! HKCU class registration writes (no admin rights needed).

use crate::error::{PlatformError, Result};
use crate::register::RegEntry;

/// Writes every entry as a string value under `HKEY_CURRENT_USER`.
pub fn write_entries(entries: &[RegEntry]) -> Result<()> {
    write_entries_under(windows_registry::CURRENT_USER, entries)
}

/// Writes every entry under `root` (tests use a scratch subkey).
pub fn write_entries_under(root: &windows_registry::Key, entries: &[RegEntry]) -> Result<()> {
    for e in entries {
        let key = root
            .create(&e.key)
            .map_err(|err| PlatformError::Other(format!("{}: {err}", e.key)))?;
        key.set_string(&e.name, &e.value)
            .map_err(|err| PlatformError::Other(format!("{}\\{}: {err}", e.key, e.name)))?;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn writes_the_layout_under_a_scratch_root() {
        let scratch = format!(r"Software\Classes\mmcl-test-{}", std::process::id());
        let root = windows_registry::CURRENT_USER.create(&scratch).unwrap();
        let entries = crate::register::windows_registry_entries(
            r"C:\x.exe",
            r"C:\i.ico",
            "Test.Modpack",
            true,
            true,
        );
        write_entries_under(&root, &entries).unwrap();
        let cmd = root
            .open(r"Software\Classes\mmcl\shell\open\command")
            .unwrap();
        assert_eq!(cmd.get_string("").unwrap(), "\"C:\\x.exe\" \"%1\"");
        let proto = root.open(r"Software\Classes\mmcl").unwrap();
        assert_eq!(proto.get_string("URL Protocol").unwrap(), "");
        drop((cmd, proto, root));
        windows_registry::CURRENT_USER
            .remove_tree(&scratch)
            .unwrap();
    }
}
