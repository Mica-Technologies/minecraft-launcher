// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! `.lnk` files through `IShellLinkW` + `IPersistFile` (replaces Java's PowerShell
//! `WScript.Shell` call: no 30 s timeout, no output drain, no PowerShell dependency).

use std::path::Path;

use windows::Win32::System::Com::{CLSCTX_INPROC_SERVER, CoCreateInstance, IPersistFile};
use windows::Win32::UI::Shell::{IShellLinkW, ShellLink};
use windows::core::{HSTRING, Interface};

use super::com::Apartment;
use crate::error::{PlatformError, Result};

/// Writes a shortcut at `lnk` that runs `exe args`, with `icon` (index 0) when given. Runs on
/// a short-lived STA thread so it works from any caller.
pub fn save(lnk: &Path, exe: &Path, args: &str, icon: Option<&Path>) -> Result<()> {
    let (lnk, exe, args, icon) = (
        lnk.to_path_buf(),
        exe.to_path_buf(),
        args.to_string(),
        icon.map(Path::to_path_buf),
    );
    std::thread::Builder::new()
        .name("mmcl-shortcut".into())
        .spawn(move || -> Result<()> {
            let apartment = Apartment::sta();
            if let Some(hr) = apartment.failed {
                return Err(PlatformError::Hresult {
                    what: "CoInitializeEx",
                    code: hr as u32,
                });
            }
            crate::guard(|| Some(save_on_sta(&lnk, &exe, &args, icon.as_deref())))
                .unwrap_or_else(|| Err(PlatformError::Other("panic".into())))
        })
        .map_err(PlatformError::Io)?
        .join()
        .unwrap_or_else(|_| Err(PlatformError::Other("panic".into())))
}

fn save_on_sta(lnk: &Path, exe: &Path, args: &str, icon: Option<&Path>) -> Result<()> {
    unsafe {
        let link: IShellLinkW = CoCreateInstance(&ShellLink, None, CLSCTX_INPROC_SERVER)
            .map_err(|e| PlatformError::hr("CoCreateInstance(ShellLink)", e))?;
        link.SetPath(&HSTRING::from(exe.as_os_str()))
            .map_err(|e| PlatformError::hr("SetPath", e))?;
        link.SetArguments(&HSTRING::from(args))
            .map_err(|e| PlatformError::hr("SetArguments", e))?;
        if let Some(dir) = exe.parent() {
            let _ = link.SetWorkingDirectory(&HSTRING::from(dir.as_os_str()));
        }
        if let Some(icon) = icon {
            let _ = link.SetIconLocation(&HSTRING::from(icon.as_os_str()), 0);
        }
        let file: IPersistFile = link
            .cast()
            .map_err(|e| PlatformError::hr("QueryInterface(IPersistFile)", e))?;
        file.Save(&HSTRING::from(lnk.as_os_str()), true)
            .map_err(|e| PlatformError::hr("IPersistFile::Save", e))?;
    }
    Ok(())
}
