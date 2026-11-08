// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Test fakes (Java `FakeProcess` and the `session(...)` helper).

use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};

use parking_lot::Mutex;

use crate::process::{ExitCallback, ProcessHandle};
use crate::session::GameSession;

/// A session on a fixed clock (1000 ms).
pub(crate) fn session(pack: &str, account: Option<&str>) -> Arc<GameSession> {
    GameSession::new(
        None,
        pack,
        format!("Pack {pack}"),
        account.map(str::to_string),
        account.map(|a| format!("Player {a}")),
        Arc::new(|| 1_000),
    )
}

/// Alive until [`finish`](Self::finish); records graceful vs forced stops, which finish it with
/// 143 / 137 like a signalled JVM.
#[derive(Default)]
pub(crate) struct FakeProcess {
    state: Mutex<(Option<i32>, Vec<ExitCallback>)>,
    destroyed: AtomicBool,
    destroyed_forcibly: AtomicBool,
}

impl FakeProcess {
    pub(crate) fn new() -> Arc<Self> {
        Arc::new(Self::default())
    }

    pub(crate) fn finish(&self, code: i32) {
        let callbacks = {
            let mut st = self.state.lock();
            if st.0.is_some() {
                return;
            }
            st.0 = Some(code);
            std::mem::take(&mut st.1)
        };
        for cb in callbacks {
            cb(code);
        }
    }

    pub(crate) fn destroyed(&self) -> bool {
        self.destroyed.load(Ordering::SeqCst)
    }

    pub(crate) fn destroyed_forcibly(&self) -> bool {
        self.destroyed_forcibly.load(Ordering::SeqCst)
    }
}

impl ProcessHandle for FakeProcess {
    fn pid(&self) -> Option<u32> {
        None
    }

    fn is_alive(&self) -> bool {
        self.state.lock().0.is_none()
    }

    fn terminate(&self) {
        self.destroyed.store(true, Ordering::SeqCst);
        self.finish(143);
    }

    fn kill(&self) {
        self.destroyed_forcibly.store(true, Ordering::SeqCst);
        self.finish(137);
    }

    fn on_exit(&self, callback: ExitCallback) {
        let code = {
            let mut st = self.state.lock();
            match st.0 {
                Some(c) => c,
                None => {
                    st.1.push(callback);
                    return;
                }
            }
        };
        callback(code);
    }
}
