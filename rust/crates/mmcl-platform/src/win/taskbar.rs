// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Taskbar button progress through `ITaskbarList3` (`TaskbarProgressManager`).
//!
//! Runs on the progress worker's STA. The COM object is created lazily once a window is
//! attached; calls fail until the taskbar button exists (GPUI owns the window procedure, so
//! `TaskbarButtonCreated` cannot be caught), so a failed init or call drops the object and the
//! next command retries.

use windows::Win32::System::Com::{CLSCTX_INPROC_SERVER, CoCreateInstance};
use windows::Win32::UI::Shell::{
    ITaskbarList3, TBPF_ERROR, TBPF_INDETERMINATE, TBPF_NOPROGRESS, TBPF_NORMAL, TaskbarList,
};

use super::hwnd;
use crate::handle::NativeHandle;
use crate::progress::{Command, ProgressBackend, percent};
use mmcl_util::Progress;

/// The one taskbar wrapper of the session.
#[derive(Default)]
pub struct TaskbarBackend {
    hwnd: Option<isize>,
    list: Option<ITaskbarList3>,
    /// The last value shown, re-applied after a lazy (re)init.
    last: Option<Command>,
}

impl TaskbarBackend {
    fn list(&mut self) -> Option<&ITaskbarList3> {
        if self.list.is_none() {
            self.hwnd?;
            let created = unsafe {
                CoCreateInstance::<_, ITaskbarList3>(&TaskbarList, None, CLSCTX_INPROC_SERVER)
                    .and_then(|l| l.HrInit().map(|_| l))
            };
            match created {
                Ok(l) => self.list = Some(l),
                Err(e) => {
                    tracing::debug!(
                        "{}",
                        mmcl_i18n::tf!("log.taskbar.initFailed", e.to_string())
                    );
                    return None;
                }
            }
        }
        self.list.as_ref()
    }

    fn show(&mut self, cmd: Command) {
        let Some(h) = self.hwnd else {
            return;
        };
        let w = hwnd(h);
        let Some(list) = self.list() else {
            return;
        };
        let (result, key) = unsafe {
            match cmd {
                Command::Set(Progress::Fraction(f)) => (
                    list.SetProgressState(w, TBPF_NORMAL)
                        .and_then(|_| list.SetProgressValue(w, percent(f), 100)),
                    "log.taskbar.updateFailed",
                ),
                Command::Set(Progress::Indeterminate) => (
                    list.SetProgressState(w, TBPF_INDETERMINATE),
                    "log.taskbar.updateFailed",
                ),
                Command::Stop | Command::Shutdown => (
                    list.SetProgressState(w, TBPF_NOPROGRESS),
                    "log.taskbar.stopFailed",
                ),
                Command::FullError => (
                    list.SetProgressState(w, TBPF_ERROR)
                        .and_then(|_| list.SetProgressValue(w, 100, 100)),
                    "log.taskbar.errorOverlayFailed",
                ),
                Command::Attach(_) => (Ok(()), "log.taskbar.updateFailed"),
            }
        };
        if let Err(e) = result {
            tracing::debug!("{}", mmcl_i18n::tf!(key, e.to_string()));
            // Retry with a fresh object next time (the button may not have existed yet).
            self.list = None;
        }
    }
}

impl ProgressBackend for TaskbarBackend {
    fn apply(&mut self, cmd: Command) {
        match cmd {
            Command::Attach(NativeHandle::Win32 { hwnd }) => {
                if self.hwnd != Some(hwnd) {
                    self.hwnd = Some(hwnd);
                    self.list = None;
                }
                if let Some(last) = self.last {
                    self.show(last);
                }
            }
            Command::Attach(_) => {}
            Command::Shutdown => {
                self.show(Command::Stop);
                self.list = None;
                self.hwnd = None;
                self.last = None;
            }
            other => {
                self.last = Some(other);
                self.show(other);
            }
        }
    }
}
