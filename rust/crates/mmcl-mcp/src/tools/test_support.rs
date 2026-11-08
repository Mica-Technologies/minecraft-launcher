// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Hand-rolled stubs of the launcher seams for unit tests.

use parking_lot::Mutex;

use super::*;

#[derive(Default)]
pub(crate) struct StubView {
    pub packs: Mutex<Vec<PackSummary>>,
    pub manifests: Mutex<Vec<(String, String)>>,
    pub crashes: Mutex<Vec<(String, CrashInfo)>>,
    pub footprint: Mutex<Option<PackFootprint>>,
    pub status: Mutex<LauncherStatus>,
    pub running: Mutex<Vec<RunningGame>>,
}

impl StubView {
    pub fn with_packs(packs: Vec<PackSummary>) -> StubView {
        let v = StubView::default();
        *v.packs.lock() = packs;
        v
    }
}

pub(crate) fn pack(name: &str, installed: bool) -> PackSummary {
    PackSummary {
        friendly_name: name.to_string(),
        version: "1.0.0".into(),
        latest_version: "1.1.0".into(),
        update_available: installed,
        mod_loader: "forge".into(),
        installed,
        unstable: false,
    }
}

pub(crate) fn running(name: &str) -> RunningGame {
    RunningGame {
        friendly_name: name.into(),
        account: "Steve".into(),
        phase: "running".into(),
        uptime_seconds: 12,
    }
}

impl LauncherView for StubView {
    fn packs(&self) -> Vec<PackSummary> {
        self.packs.lock().clone()
    }
    fn manifest_of(&self, n: &str) -> Option<String> {
        self.manifests
            .lock()
            .iter()
            .find(|(k, _)| k == n)
            .map(|(_, v)| v.clone())
    }
    fn latest_crash_of(&self, n: &str) -> Option<CrashInfo> {
        self.crashes
            .lock()
            .iter()
            .find(|(k, _)| k == n)
            .map(|(_, v)| v.clone())
    }
    fn footprint_of(&self, _: &str) -> Option<PackFootprint> {
        *self.footprint.lock()
    }
    fn status(&self) -> LauncherStatus {
        self.status.lock().clone()
    }
    fn running_games(&self) -> Vec<RunningGame> {
        self.running.lock().clone()
    }
}

/// Records every call; answers `ok` with a fixed message unless told otherwise.
#[derive(Default)]
pub(crate) struct StubActions {
    pub calls: Mutex<Vec<String>>,
    pub fail: bool,
    pub blocked: Mutex<Option<String>>,
}

impl StubActions {
    fn out(&self, call: String) -> Outcome {
        self.calls.lock().push(call.clone());
        if self.fail {
            Outcome::failed(format!("failed {call}"))
        } else {
            Outcome::ok(format!("did {call}"))
        }
    }
}

impl LauncherActions for StubActions {
    fn install_from_url(&self, url: &str) -> Outcome {
        self.out(format!("install {url}"))
    }
    fn uninstall(&self, n: &str) -> Outcome {
        self.out(format!("uninstall {n}"))
    }
    fn launch(&self, n: &str, a: Option<&str>) -> Outcome {
        self.out(format!("launch {n} {a:?}"))
    }
    fn why_launch_blocked(&self, _: &str, _: Option<&str>) -> Option<String> {
        self.blocked.lock().clone()
    }
    fn stop_game(&self, n: &str) -> Outcome {
        self.out(format!("stop {n}"))
    }
    fn create_pack(&self, n: &str, l: Option<&str>, u: Option<&str>) -> Outcome {
        self.out(format!("create {n} {l:?} {u:?}"))
    }
    fn fork_pack(&self, s: &str, n: &str) -> Outcome {
        self.out(format!("fork {s} {n}"))
    }
    fn add_mod(&self, p: &str, m: &str, r: &str, l: &str) -> Outcome {
        self.out(format!("add {p} {m} {r} {l}"))
    }
    fn remove_mod(&self, p: &str, i: &str) -> Outcome {
        self.out(format!("remove {p} {i}"))
    }
}

pub(crate) fn args(v: serde_json::Value) -> Args {
    v.as_object().cloned().unwrap_or_default()
}

pub(crate) fn ctx() -> CallContext {
    CallContext::new("test", "sid")
}
