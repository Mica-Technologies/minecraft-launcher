// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Copy-on-write listener lists whose callbacks run outside every lock, each one isolated so a
//! panicking listener never keeps the others from hearing about a change.

use std::panic::{AssertUnwindSafe, catch_unwind};
use std::sync::Arc;
use std::sync::atomic::{AtomicU64, Ordering};

use parking_lot::Mutex;

/// Identifies a registered listener, for removing it again.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub struct ListenerId(pub(crate) u64);

static NEXT_ID: AtomicU64 = AtomicU64::new(1);

impl ListenerId {
    pub(crate) fn next() -> Self {
        ListenerId(NEXT_ID.fetch_add(1, Ordering::Relaxed))
    }
}

/// An immutable listener list snapshot.
pub(crate) type ListenerList<T> = Arc<Vec<(ListenerId, Arc<T>)>>;

/// A list of listeners of type `T` (usually a `dyn Fn`).
pub(crate) struct Listeners<T: ?Sized> {
    list: Mutex<ListenerList<T>>,
}

impl<T: ?Sized> Default for Listeners<T> {
    fn default() -> Self {
        Listeners {
            list: Mutex::new(Arc::new(Vec::new())),
        }
    }
}

impl<T: ?Sized> Listeners<T> {
    pub(crate) fn add(&self, listener: Arc<T>) -> ListenerId {
        let id = ListenerId::next();
        self.add_with_id(id, listener);
        id
    }

    pub(crate) fn add_with_id(&self, id: ListenerId, listener: Arc<T>) {
        let mut list = self.list.lock();
        let mut next = Vec::clone(&list);
        next.push((id, listener));
        *list = Arc::new(next);
    }

    pub(crate) fn remove(&self, id: ListenerId) -> bool {
        let mut list = self.list.lock();
        if !list.iter().any(|(i, _)| *i == id) {
            return false;
        }
        let next: Vec<_> = list.iter().filter(|(i, _)| *i != id).cloned().collect();
        *list = Arc::new(next);
        true
    }

    /// The current listeners; call them after releasing every other lock.
    pub(crate) fn snapshot(&self) -> ListenerList<T> {
        self.list.lock().clone()
    }

    /// Calls `f` for every listener, isolating panics.
    pub(crate) fn for_each(&self, mut f: impl FnMut(&T)) {
        for (_, l) in self.snapshot().iter() {
            run_isolated(|| f(l));
        }
    }
}

/// Runs `f`, logging (not propagating) a panic.
pub(crate) fn run_isolated(f: impl FnOnce()) {
    if catch_unwind(AssertUnwindSafe(f)).is_err() {
        tracing::warn!("{}", mmcl_i18n::t!("log.session.listenerPanicked"));
    }
}
