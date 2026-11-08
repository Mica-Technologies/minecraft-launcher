// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The "Recent Modpacks" jump-list category (`WindowsJumpList`).
//!
//! Built on a dedicated short-lived thread that owns its STA. Each entry is an `IShellLinkW`
//! running `<exe> mmcl://play?name=<pack>`; one bad entry does not stop the rest. Only
//! `CommitList` applies the new list, so on any failure `AbortList` keeps the previous one. The
//! list belongs to the process AUMID ([`super::set_app_user_model_id`]).

use std::path::Path;

use windows::Win32::Storage::EnhancedStorage::PKEY_Title;
use windows::Win32::System::Com::StructuredStorage::PROPVARIANT;
use windows::Win32::System::Com::{CLSCTX_INPROC_SERVER, CoCreateInstance};
use windows::Win32::UI::Shell::Common::{IObjectArray, IObjectCollection};
use windows::Win32::UI::Shell::PropertiesSystem::IPropertyStore;
use windows::Win32::UI::Shell::{
    DestinationList, EnumerableObjectCollection, ICustomDestinationList, IShellLinkW, ShellLink,
};
use windows::core::{HSTRING, Interface};

use super::com::Apartment;
use crate::error::{PlatformError, Result};
use crate::host::RecentPack;

/// Replaces the jump list with `packs` (already limited and ordered by the caller).
pub fn refresh(exe: &Path, packs: &[RecentPack]) -> Result<()> {
    let exe = exe.to_path_buf();
    let packs = packs.to_vec();
    std::thread::Builder::new()
        .name("mmcl-jumplist".into())
        .spawn(move || {
            let apartment = Apartment::sta();
            if let Some(hr) = apartment.failed {
                tracing::warn!(
                    "{}",
                    mmcl_i18n::tf!("log.jumpList.coInitFailed", format!("{:08X}", hr as u32))
                );
                return Ok(());
            }
            crate::guard(|| Some(build(&exe, &packs))).unwrap_or(Ok(()))
        })
        .map_err(PlatformError::Io)?
        .join()
        .unwrap_or(Ok(()))
}

fn hr_log(what: &'static str, e: &windows::core::Error) {
    tracing::warn!(
        "{}",
        mmcl_i18n::tf!(
            "log.jumpList.hresultFailure",
            what,
            format!("{:08X}", e.code().0 as u32)
        )
    );
}

fn build(exe: &Path, packs: &[RecentPack]) -> Result<()> {
    let list: ICustomDestinationList =
        unsafe { CoCreateInstance(&DestinationList, None, CLSCTX_INPROC_SERVER) }.map_err(|e| {
            hr_log("CoCreateInstance(DestinationList)", &e);
            PlatformError::hr("CoCreateInstance(DestinationList)", e)
        })?;
    let mut min_slots = 0u32;
    let _removed: IObjectArray = unsafe { list.BeginList(&mut min_slots) }.map_err(|e| {
        hr_log("BeginList", &e);
        PlatformError::hr("BeginList", e)
    })?;
    let result = fill_and_commit(&list, exe, packs);
    if result.is_err() {
        let _ = unsafe { list.AbortList() };
    }
    result
}

fn fill_and_commit(list: &ICustomDestinationList, exe: &Path, packs: &[RecentPack]) -> Result<()> {
    let collection: IObjectCollection =
        unsafe { CoCreateInstance(&EnumerableObjectCollection, None, CLSCTX_INPROC_SERVER) }
            .map_err(|e| {
                hr_log("CoCreateInstance(EnumerableObjectCollection)", &e);
                PlatformError::hr("CoCreateInstance(EnumerableObjectCollection)", e)
            })?;
    unsafe { collection.Clear() }.map_err(|e| PlatformError::hr("Clear", e))?;
    let exe_w = HSTRING::from(exe.as_os_str());
    let mut added = 0usize;
    for pack in packs.iter().filter(|p| !p.pack_name.trim().is_empty()) {
        match make_link(&exe_w, pack) {
            Ok(link) => match unsafe { collection.AddObject(&link) } {
                Ok(()) => added += 1,
                Err(e) => hr_log("AddObject", &e),
            },
            Err(e) => hr_log("IShellLinkW", &e),
        }
    }
    // An empty category is E_INVALIDARG; with nothing to list, committing the empty list
    // clears the old entries.
    if added > 0 {
        let array: IObjectArray = collection
            .cast()
            .map_err(|e| PlatformError::hr("QueryInterface(IObjectArray)", e))?;
        let category = HSTRING::from(mmcl_i18n::t!("jumpList.category.recentModpacks").as_ref());
        unsafe { list.AppendCategory(&category, &array) }.map_err(|e| {
            hr_log("AppendCategory", &e);
            PlatformError::hr("AppendCategory", e)
        })?;
    }
    unsafe { list.CommitList() }.map_err(|e| {
        hr_log("CommitList", &e);
        PlatformError::hr("CommitList", e)
    })
}

fn make_link(exe: &HSTRING, pack: &RecentPack) -> windows::core::Result<IShellLinkW> {
    let display = pack.safe_display_name();
    let link: IShellLinkW = unsafe { CoCreateInstance(&ShellLink, None, CLSCTX_INPROC_SERVER) }?;
    unsafe {
        link.SetPath(exe)?;
        link.SetArguments(&HSTRING::from(mmcl_util::deeplink::play_link(
            &pack.pack_name,
        )))?;
        link.SetDescription(&HSTRING::from(mmcl_i18n::tf!(
            "jumpList.tooltip.play",
            display.as_str()
        )))?;
        link.SetIconLocation(exe, 0)?;
    }
    let store: IPropertyStore = link.cast()?;
    let title = PROPVARIANT::from(display.as_str());
    unsafe {
        if let Err(e) = store
            .SetValue(&PKEY_Title, &title)
            .and_then(|_| store.Commit())
        {
            tracing::debug!(
                "{}",
                mmcl_i18n::tf!("log.jumpList.propertyStoreFailed", e.to_string())
            );
        }
    }
    Ok(link)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Rewrites the test binary's own jump list; run by hand with `--ignored`.
    #[test]
    #[ignore = "writes a real jump list"]
    fn the_jump_list_commits() {
        let exe = std::env::current_exe().unwrap();
        let packs = vec![
            RecentPack::new("Alto Pack", "Alto", 2),
            RecentPack::new(" ", "skip", 1),
        ];
        refresh(&exe, &packs).unwrap();
        refresh(&exe, &[]).unwrap();
    }
}
