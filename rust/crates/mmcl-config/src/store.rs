// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! `ConfigStore`: the one `configuration.json` document, its typed reads, and its writes.
//!
//! - **One lock.** Every read and change goes through a single `parking_lot::Mutex` around the
//!   document, which removes Java's snapshot-retry races.
//! - **Load** (in [`ConfigStore::open`]): parse (strict JSON, then the lenient Gson-like
//!   fallback); a zero-byte, non-object or unparsable file is renamed to
//!   `configuration.json.corrupt-yyyyMMdd-HHmmss` and replaced by first-run defaults; then the
//!   schema migration runs.
//! - **Writes** are debounced (500 ms by default) on a `mica-config-write` thread, and each is
//!   atomic: `configuration.json.tmp` is written, fsynced, made owner-only, then renamed over
//!   the file. Lock order is the write lock, then the document lock, never the reverse.
//! - **Durable writes** ([`ConfigStore::mutate_durable`]) write synchronously whether or not a
//!   write was pending (Java's `flushNow` after an unscheduled change was a no-op).
//! - **No dialogs under the lock.** Errors are reported through [`StoreHooks::on_error_async`]
//!   after the document lock is released; the default hook is `mmcl_log::error_async`.

use std::fmt;
use std::io;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Weak};
use std::time::{Duration, Instant};

use mmcl_i18n::{t, tf};
use parking_lot::{Condvar, Mutex};
use serde_json::{Map, Value};

use crate::coerce;
use crate::keys::*;
use crate::migrate::{self, MigrationReport};

/// The JSON document type.
pub type Document = Map<String, Value>;

/// The default write debounce (Java `WRITE_DEBOUNCE_MS`).
pub const WRITE_DEBOUNCE: Duration = Duration::from_millis(500);

/// Callbacks the store raises. Each is called without any store lock held.
#[derive(Clone)]
pub struct StoreHooks {
    /// A localized error the user should see (Java `logErrorAsync`). Must not block.
    pub on_error_async: Arc<dyn Fn(&str) + Send + Sync>,
    /// The `debug` setting changed (Java `Logger.setDebugLoggingEnabled`).
    pub on_debug_changed: Arc<dyn Fn(bool) + Send + Sync>,
}

impl Default for StoreHooks {
    fn default() -> Self {
        StoreHooks {
            on_error_async: Arc::new(mmcl_log::error_async),
            on_debug_changed: Arc::new(mmcl_log::set_debug_enabled),
        }
    }
}

impl StoreHooks {
    /// Hooks that do nothing (tests).
    pub fn silent() -> Self {
        StoreHooks {
            on_error_async: Arc::new(|_| {}),
            on_debug_changed: Arc::new(|_| {}),
        }
    }
}

impl fmt::Debug for StoreHooks {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("StoreHooks").finish_non_exhaustive()
    }
}

/// Store settings that are not part of the document.
#[derive(Clone, Debug)]
pub struct StoreOptions {
    /// How long a scheduled write waits for more changes.
    pub debounce: Duration,
    /// Dev build: `debug` always reads true.
    pub is_dev: bool,
    /// Default of `mcpPort`.
    pub default_mcp_port: u16,
}

impl Default for StoreOptions {
    fn default() -> Self {
        StoreOptions {
            debounce: WRITE_DEBOUNCE,
            is_dev: mmcl_core::build::is_dev(),
            default_mcp_port: mmcl_core::AppIdentity::current().mcp_port,
        }
    }
}

/// How the document came to be when the store opened.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum LoadOutcome {
    /// The file was read.
    Loaded,
    /// No file: first-run defaults.
    Missing,
    /// The file was unreadable; it was moved to `quarantined` (when the rename worked) and
    /// replaced by first-run defaults.
    Corrupt { quarantined: Option<PathBuf> },
    /// In-memory store (tests, previews): nothing was read.
    InMemory,
}

#[derive(Default)]
struct DebounceState {
    deadline: Option<Instant>,
    shutdown: bool,
}

#[derive(Default)]
struct Debounce {
    state: Mutex<DebounceState>,
    cv: Condvar,
}

struct Inner {
    path: Option<PathBuf>,
    doc: Mutex<Document>,
    write_lock: Mutex<()>,
    debounce: Arc<Debounce>,
    thread_started: AtomicBool,
    hooks: StoreHooks,
    options: StoreOptions,
    load_outcome: LoadOutcome,
    writes: AtomicU64,
}

/// The configuration document. Cheap to clone (shared handle).
#[derive(Clone)]
pub struct ConfigStore {
    inner: Arc<Inner>,
}

impl fmt::Debug for ConfigStore {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("ConfigStore")
            .field("path", &self.inner.path)
            .field("load_outcome", &self.inner.load_outcome)
            .finish_non_exhaustive()
    }
}

/// The first-run document (`populateFirstRunDefaults`, after `configVersion`).
pub fn first_run_defaults() -> Document {
    let mut doc = Document::new();
    doc.insert(CONFIG_VERSION_KEY.into(), Value::from(CONFIG_VERSION));
    doc.insert(MIN_RAM_KEY.into(), Value::from(MIN_RAM_MEGABYTES_DEFAULT));
    doc.insert(MAX_RAM_KEY.into(), Value::from(MAX_RAM_MEGABYTES_DEFAULT));
    doc.insert(
        LOG_DEBUG_ENABLE_KEY.into(),
        Value::from(LOG_DEBUG_ENABLE_DEFAULT),
    );
    doc.insert(
        DISCORD_RPC_ENABLE_KEY.into(),
        Value::from(DISCORD_RPC_ENABLE_DEFAULT),
    );
    doc.insert(
        RESIZE_WINDOWS_ENABLE_KEY.into(),
        Value::from(RESIZE_WINDOWS_ENABLE_DEFAULT),
    );
    doc.insert(MOD_PACKS_INSTALLED_KEY.into(), Value::Array(Vec::new()));
    doc
}

/// Why a config file could not be read as a document.
#[derive(Debug, thiserror::Error)]
pub enum ParseError {
    #[error("{0}")]
    Io(#[from] io::Error),
    #[error("the file is empty")]
    Empty,
    #[error("the file is not UTF-8 text")]
    NotUtf8,
    #[error("{0}")]
    Syntax(String),
    #[error("the file does not hold a JSON object")]
    NotAnObject,
}

impl ParseError {
    /// The localized description (the `Display` text is English, for logs).
    pub fn localized(&self) -> String {
        match self {
            ParseError::Io(e) => e.to_string(),
            ParseError::Empty => t!("config.error.parse.empty").into_owned(),
            ParseError::NotUtf8 => t!("config.error.parse.notUtf8").into_owned(),
            ParseError::Syntax(detail) => tf!("config.error.parse.syntax", detail),
            ParseError::NotAnObject => t!("config.error.parse.notAnObject").into_owned(),
        }
    }
}

/// Parses config text: strict JSON, then the lenient fallback (comments, single quotes,
/// unquoted keys, trailing commas). A leading UTF-8 BOM is ignored.
pub fn parse_document(bytes: &[u8]) -> Result<Document, ParseError> {
    let text = std::str::from_utf8(bytes).map_err(|_| ParseError::NotUtf8)?;
    let text = text.strip_prefix('\u{feff}').unwrap_or(text);
    if text.trim().is_empty() {
        return Err(ParseError::Empty);
    }
    match mmcl_util::json::parse(text) {
        Ok(Value::Object(map)) => Ok(map),
        Ok(_) => Err(ParseError::NotAnObject),
        Err(e) => Err(ParseError::Syntax(e.to_string())),
    }
}

/// Serializes the document the way it is written to disk: compact UTF-8 JSON in insertion
/// order.
pub fn serialize_document(doc: &Document) -> Vec<u8> {
    // Serializing a map of `Value`s cannot fail.
    serde_json::to_vec(doc).unwrap_or_else(|_| b"{}".to_vec())
}

/// `configuration.json.corrupt-<stamp>` next to `file`.
pub fn corrupt_path_for(file: &Path, at: &chrono::NaiveDateTime) -> PathBuf {
    let mut name = file
        .file_name()
        .map(|n| n.to_os_string())
        .unwrap_or_default();
    name.push(format!(
        ".corrupt-{}",
        at.format(mmcl_core::paths::CORRUPT_STAMP_FORMAT)
    ));
    file.with_file_name(name)
}

fn pack_count(doc: &Document) -> i64 {
    match doc.get(MOD_PACKS_INSTALLED_KEY) {
        Some(Value::Array(a)) => a.len() as i64,
        _ => -1,
    }
}

impl ConfigStore {
    /// Opens the config at `file` with default options: load, quarantine a corrupt file,
    /// populate first-run defaults, migrate.
    pub fn open(file: PathBuf, hooks: StoreHooks) -> ConfigStore {
        ConfigStore::open_with(file, hooks, StoreOptions::default())
    }

    /// Opens `<root>/config/configuration.json` for these paths.
    pub fn open_for(paths: &mmcl_core::DataPaths, hooks: StoreHooks) -> ConfigStore {
        ConfigStore::open(paths.config_file(), hooks)
    }

    /// [`open`](Self::open) with explicit options.
    pub fn open_with(file: PathBuf, hooks: StoreHooks, options: StoreOptions) -> ConfigStore {
        let (doc, outcome, errors) = load_from_disk(&file);
        let fresh = !matches!(outcome, LoadOutcome::Loaded);
        let store = ConfigStore::build(Some(file), doc, outcome, hooks, options);
        for e in errors {
            (store.inner.hooks.on_error_async)(&e);
        }
        if fresh {
            mmcl_log::std(&t!("CONFIG_RESET_SUCCESS_TEXT"));
            store.schedule_write();
        }
        store.run_migration();
        store
    }

    /// A store over `doc` with no file behind it: reads and changes work, writes do nothing.
    /// No migration runs.
    pub fn in_memory(doc: Document) -> ConfigStore {
        ConfigStore::in_memory_with(doc, StoreHooks::silent(), StoreOptions::default())
    }

    /// [`in_memory`](Self::in_memory) with explicit hooks and options.
    pub fn in_memory_with(doc: Document, hooks: StoreHooks, options: StoreOptions) -> ConfigStore {
        ConfigStore::build(None, doc, LoadOutcome::InMemory, hooks, options)
    }

    fn build(
        path: Option<PathBuf>,
        doc: Document,
        load_outcome: LoadOutcome,
        hooks: StoreHooks,
        options: StoreOptions,
    ) -> ConfigStore {
        ConfigStore {
            inner: Arc::new(Inner {
                path,
                doc: Mutex::new(doc),
                write_lock: Mutex::new(()),
                debounce: Arc::new(Debounce::default()),
                thread_started: AtomicBool::new(false),
                hooks,
                options,
                load_outcome,
                writes: AtomicU64::new(0),
            }),
        }
    }

    fn run_migration(&self) -> MigrationReport {
        let report = migrate::migrate(&mut self.inner.doc.lock());
        migrate::log_report(&report);
        if report.migrated {
            self.schedule_write();
        }
        report
    }

    /// The file behind the store (`None` for an in-memory store).
    pub fn path(&self) -> Option<&Path> {
        self.inner.path.as_deref()
    }

    /// How the document was obtained.
    pub fn load_outcome(&self) -> &LoadOutcome {
        &self.inner.load_outcome
    }

    /// The store's options.
    pub fn options(&self) -> &StoreOptions {
        &self.inner.options
    }

    /// The store's hooks.
    pub fn hooks(&self) -> &StoreHooks {
        &self.inner.hooks
    }

    /// Number of completed disk writes (diagnostics and tests).
    pub fn write_count(&self) -> u64 {
        self.inner.writes.load(Ordering::SeqCst)
    }

    /// `true` while a debounced write is waiting.
    pub fn has_pending_write(&self) -> bool {
        self.inner.debounce.state.lock().deadline.is_some()
    }

    // ---- reads ---------------------------------------------------------------------------

    /// Runs `f` on the live document without scheduling a write.
    pub fn read<R>(&self, f: impl FnOnce(&Document) -> R) -> R {
        f(&self.inner.doc.lock())
    }

    /// A deep copy of the document.
    pub fn snapshot(&self) -> Document {
        self.inner.doc.lock().clone()
    }

    /// The raw value under `key` (cloned), `None` when absent.
    pub fn get_value(&self, key: &str) -> Option<Value> {
        self.inner.doc.lock().get(key).cloned()
    }

    /// `true` when `key` is present (a JSON `null` counts as present).
    pub fn contains(&self, key: &str) -> bool {
        self.inner.doc.lock().contains_key(key)
    }

    fn coerced<T: Clone + fmt::Display>(
        &self,
        key: &str,
        default: T,
        init: Option<Value>,
        type_name: &str,
        coerce: fn(&Value) -> Option<T>,
    ) -> T {
        let mut inserted = false;
        let result = {
            let mut doc = self.inner.doc.lock();
            if let Some(init) = init
                && !doc.contains_key(key)
            {
                doc.insert(key.to_string(), init);
                inserted = true;
            }
            match doc.get(key) {
                None | Some(Value::Null) => Ok(default.clone()),
                Some(v) => coerce(v).ok_or(()),
            }
        };
        if inserted {
            self.schedule_write();
        }
        match result {
            Ok(v) => v,
            Err(()) => {
                mmcl_log::warn_silent(&tf!(
                    "log.configStore.typeMismatch",
                    key,
                    type_name,
                    default.to_string()
                ));
                default
            }
        }
    }

    /// The string under `key`; `default` when absent, `null` or not coercible. Never writes.
    pub fn get_string(&self, key: &str, default: &str) -> String {
        self.coerced(key, default.to_string(), None, "string", coerce::as_string)
    }

    /// The boolean under `key`; `default` when absent, `null` or not coercible. Never writes.
    pub fn get_bool(&self, key: &str, default: bool) -> bool {
        self.coerced(key, default, None, "boolean", coerce::as_bool)
    }

    /// The `i32` under `key`; `default` when absent, `null`, not coercible or out of range.
    pub fn get_i32(&self, key: &str, default: i32) -> i32 {
        self.coerced(key, default, None, "int", coerce::as_i32)
    }

    /// The `i64` under `key`; `default` when absent, `null` or not coercible.
    pub fn get_i64(&self, key: &str, default: i64) -> i64 {
        self.coerced(key, default, None, "long", coerce::as_i64)
    }

    /// The `f64` under `key`; `default` when absent, `null` or not coercible.
    pub fn get_f64(&self, key: &str, default: f64) -> f64 {
        self.coerced(key, default, None, "double", coerce::as_f64)
    }

    /// Like [`get_string`](Self::get_string), but an absent key is first set to `default`
    /// (and a write scheduled). A present `null` is not replaced.
    pub fn get_or_init_string(&self, key: &str, default: &str) -> String {
        self.coerced(
            key,
            default.to_string(),
            Some(Value::from(default)),
            "string",
            coerce::as_string,
        )
    }

    /// Get-or-init for a boolean.
    pub fn get_or_init_bool(&self, key: &str, default: bool) -> bool {
        self.coerced(
            key,
            default,
            Some(Value::from(default)),
            "boolean",
            coerce::as_bool,
        )
    }

    /// Get-or-init for an `i32`.
    pub fn get_or_init_i32(&self, key: &str, default: i32) -> i32 {
        self.coerced(
            key,
            default,
            Some(Value::from(default)),
            "int",
            coerce::as_i32,
        )
    }

    /// Get-or-init for an `i64`.
    pub fn get_or_init_i64(&self, key: &str, default: i64) -> i64 {
        self.coerced(
            key,
            default,
            Some(Value::from(default)),
            "long",
            coerce::as_i64,
        )
    }

    // ---- changes -------------------------------------------------------------------------

    /// Applies `f` to the live document, then schedules a write.
    pub fn mutate<R>(&self, f: impl FnOnce(&mut Document) -> R) -> R {
        let r = f(&mut self.inner.doc.lock());
        self.schedule_write();
        r
    }

    /// Applies `f` to the live document without scheduling a write (for reads that repair
    /// structure the way Java's getters did, such as inserting a missing `modpacks` array).
    pub fn mutate_quiet<R>(&self, f: impl FnOnce(&mut Document) -> R) -> R {
        f(&mut self.inner.doc.lock())
    }

    /// Applies `f`, then writes to disk before returning (cancelling any pending write).
    pub fn mutate_durable<R>(&self, f: impl FnOnce(&mut Document) -> R) -> R {
        let r = f(&mut self.inner.doc.lock());
        self.take_pending();
        self.write_now();
        r
    }

    /// Sets `key` and schedules a write.
    pub fn set_value(&self, key: &str, value: Value) {
        self.mutate(|d| {
            d.insert(key.to_string(), value);
        });
    }

    /// Sets `key` and writes now.
    pub fn set_value_durable(&self, key: &str, value: Value) {
        self.mutate_durable(|d| {
            d.insert(key.to_string(), value);
        });
    }

    /// Removes `key` and schedules a write. Returns the old value.
    pub fn remove(&self, key: &str) -> Option<Value> {
        self.mutate(|d| d.shift_remove(key))
    }

    /// Replaces the whole document (import), migrates it, and writes it now.
    pub fn replace(&self, doc: Document) -> MigrationReport {
        // Swap and migrate under one lock, so no reader ever sees the unmigrated import.
        let report = {
            let mut live = self.inner.doc.lock();
            *live = doc;
            migrate::migrate(&mut live)
        };
        migrate::log_report(&report);
        self.take_pending();
        self.write_now();
        report
    }

    // ---- writes --------------------------------------------------------------------------

    /// Schedules a write after the debounce delay, replacing any pending one, so a burst of
    /// changes becomes one write.
    pub fn schedule_write(&self) {
        if self.inner.path.is_none() {
            return;
        }
        self.ensure_writer_thread();
        let deb = &self.inner.debounce;
        let mut st = deb.state.lock();
        st.deadline = Some(Instant::now() + self.inner.options.debounce);
        deb.cv.notify_all();
    }

    fn take_pending(&self) -> bool {
        self.inner.debounce.state.lock().deadline.take().is_some()
    }

    /// Writes now if a write is pending; otherwise waits for any write in progress. Call on
    /// every exit and relaunch path.
    pub fn flush(&self) {
        if self.take_pending() {
            self.write_now();
        } else {
            // A debounced write may be running on the writer thread: wait for it.
            drop(self.inner.write_lock.lock());
        }
    }

    /// Best-effort flush that never blocks (panic hook, signal handlers). Returns `true` when
    /// nothing was left unwritten.
    pub fn try_flush(&self) -> bool {
        let Some(_w) = self.inner.write_lock.try_lock() else {
            return false;
        };
        let pending = match self.inner.debounce.state.try_lock() {
            Some(mut st) => st.deadline.take().is_some(),
            None => return false,
        };
        if !pending {
            return true;
        }
        let Some(doc) = self.inner.doc.try_lock() else {
            // Put the write back (and wake the writer thread) so it still happens.
            if let Some(mut st) = self.inner.debounce.state.try_lock() {
                st.deadline = Some(Instant::now());
                self.inner.debounce.cv.notify_all();
            }
            return false;
        };
        let snapshot = doc.clone();
        drop(doc);
        self.inner.write_snapshot(&snapshot).is_ok()
    }

    /// Writes the document to disk now (atomic replace).
    pub fn write_now(&self) {
        self.inner.write_now();
    }

    fn ensure_writer_thread(&self) {
        if self.inner.thread_started.swap(true, Ordering::SeqCst) {
            return;
        }
        let deb = self.inner.debounce.clone();
        let weak: Weak<Inner> = Arc::downgrade(&self.inner);
        let spawned = std::thread::Builder::new()
            .name("mica-config-write".into())
            .spawn(move || writer_loop(deb, weak));
        if spawned.is_err() {
            // No thread: let later schedules try again; flush() still writes synchronously.
            self.inner.thread_started.store(false, Ordering::SeqCst);
        }
    }
}

fn writer_loop(deb: Arc<Debounce>, weak: Weak<Inner>) {
    let mut st = deb.state.lock();
    loop {
        if st.shutdown {
            return;
        }
        match st.deadline {
            None => deb.cv.wait(&mut st),
            Some(due) => {
                if Instant::now() >= due {
                    // The deadline is claimed under the write lock (in `write_if_due`), never
                    // here: otherwise a `flush` between the claim and the write would find
                    // nothing pending and no write in progress, and return before the change
                    // reached the disk.
                    drop(st);
                    match weak.upgrade() {
                        Some(inner) => inner.write_if_due(),
                        None => return,
                    }
                    st = deb.state.lock();
                } else {
                    deb.cv.wait_until(&mut st, due);
                }
            }
        }
    }
}

impl Inner {
    /// The writer thread's write: takes the write lock, then claims the pending write only if
    /// it is still due (a later `schedule_write` may have moved it, or a `flush` may have
    /// taken it). Lock order: write lock, debounce state, document.
    fn write_if_due(&self) {
        let _w = self.write_lock.lock();
        let due = {
            let mut st = self.debounce.state.lock();
            match st.deadline {
                Some(d) if Instant::now() >= d => {
                    st.deadline = None;
                    true
                }
                _ => false,
            }
        };
        if due {
            let snapshot = self.doc.lock().clone();
            let _ = self.write_snapshot(&snapshot);
        }
    }

    fn write_now(&self) {
        if self.path.is_none() {
            return;
        }
        let _w = self.write_lock.lock();
        // Taken under the write lock, so writes land in snapshot order.
        let snapshot = self.doc.lock().clone();
        let _ = self.write_snapshot(&snapshot);
    }

    /// Writes `snapshot`; the caller holds the write lock.
    fn write_snapshot(&self, snapshot: &Document) -> io::Result<()> {
        let Some(path) = &self.path else {
            return Ok(());
        };
        let bytes = serialize_document(snapshot);
        mmcl_log::debug(&tf!(
            "log.configStore.writeNow",
            bytes.len().to_string(),
            pack_count(snapshot).to_string(),
            path
        ));
        let lock = mmcl_core::filelock::file_lock(path);
        let _f = lock.lock();
        match mmcl_core::fsx::atomic_write(path, &bytes, true) {
            Ok(outcome) => {
                if let Some(e) = outcome.permission_error {
                    let key = if cfg!(unix) {
                        "log.filePermissions.posixTightenFailed"
                    } else {
                        "log.filePermissions.aclTightenFailed"
                    };
                    mmcl_log::warn_silent(&tf!(key, path, e.to_string()));
                }
                if outcome.fell_back_to_plain_replace {
                    mmcl_log::warn_silent(&t!("log.configStore.atomicMoveUnsupported"));
                }
                self.writes.fetch_add(1, Ordering::SeqCst);
                Ok(())
            }
            Err(e) => {
                (self.hooks.on_error_async)(&t!("CONFIG_SAVE_ERROR_TEXT"));
                mmcl_log::throwable(&e);
                Err(e)
            }
        }
    }
}

impl Drop for Inner {
    fn drop(&mut self) {
        let pending = {
            let mut st = self.debounce.state.lock();
            st.shutdown = true;
            self.debounce.cv.notify_all();
            st.deadline.take().is_some()
        };
        if pending {
            self.write_now();
        }
    }
}

/// Reads the file, quarantining it when corrupt. Returns the document, how it was obtained,
/// and the user-facing errors to raise once the store exists.
fn load_from_disk(file: &Path) -> (Document, LoadOutcome, Vec<String>) {
    let lock = mmcl_core::filelock::file_lock(file);
    let _g = lock.lock();
    if !file.is_file() {
        return (first_run_defaults(), LoadOutcome::Missing, Vec::new());
    }
    let parsed = std::fs::read(file)
        .map_err(ParseError::from)
        .and_then(|bytes| parse_document(&bytes).map(|d| (d, bytes.len())));
    match parsed {
        Ok((doc, size)) => {
            mmcl_log::debug(&tf!(
                "log.configStore.loadFromDisk",
                size.to_string(),
                pack_count(&doc).to_string(),
                file
            ));
            (doc, LoadOutcome::Loaded, Vec::new())
        }
        Err(err) => {
            let quarantined = quarantine(file);
            mmcl_log::throwable(&err);
            (
                first_run_defaults(),
                LoadOutcome::Corrupt { quarantined },
                vec![t!("CONFIG_EXISTS_CORRUPT_RESET_ERROR_TEXT").into_owned()],
            )
        }
    }
}

/// Moves a corrupt config aside so the user can recover settings from it.
fn quarantine(file: &Path) -> Option<PathBuf> {
    let dst = corrupt_path_for(file, &chrono::Local::now().naive_local());
    match std::fs::rename(file, &dst) {
        Ok(()) => {
            mmcl_log::std(&tf!("log.configStore.preservedCorrupt", &dst));
            Some(dst)
        }
        Err(e) => {
            mmcl_log::warn_silent(&tf!("log.configStore.preserveCorruptFailed", e.to_string()));
            None
        }
    }
}
