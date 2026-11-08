// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Remote announcements and kill switches (`AnnouncementManager`).
//!
//! `announce.json` carries `login`, `home`, `config`, `modpacksedit` (strings) and
//! `disableGameplay`, `disableModpacksEdit` (booleans). Each key present overwrites its field;
//! missing keys keep the previous value. [`Announcements::start_check_async`] is idempotent
//! while a fetch is in flight; [`Announcements::check_future`] returns the last one so a screen
//! can re-render when it settles. Dismissals last for the session and are keyed by the exact
//! text, so a changed announcement shows again.

use std::collections::HashSet;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, LazyLock};

use futures::FutureExt;
use futures::future::{BoxFuture, Shared};
use mmcl_i18n::t;
use parking_lot::{Mutex, RwLock};
use serde_json::Value;

use crate::bounded::{SMALL_JSON_MAX_BYTES, download_string_bounded};
use crate::pool::runtime_handle;

/// Where the announcements live.
pub const ANNOUNCEMENT_URL: &str = "https://micauseaststorage.blob.core.windows.net/mc-launcher-api/launcher-remote-config/announce.json";
/// Body cap.
pub const ANNOUNCEMENT_MAX_BYTES: u64 = SMALL_JSON_MAX_BYTES;

/// The announcement values.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct AnnouncementState {
    pub login: String,
    pub home: String,
    pub config: String,
    pub modpacks_edit: String,
    pub disable_gameplay: bool,
    pub disable_modpacks_edit: bool,
}

/// Why a document could not be applied.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AnnouncementParseError(pub String);

fn as_string(v: &Value, key: &str) -> Result<String, AnnouncementParseError> {
    // Gson's `getAsString` accepts any primitive.
    match v {
        Value::String(s) => Ok(s.clone()),
        Value::Number(n) => Ok(n.to_string()),
        Value::Bool(b) => Ok(b.to_string()),
        _ => Err(AnnouncementParseError(format!("{key} is not a string"))),
    }
}

fn as_bool(v: &Value, key: &str) -> Result<bool, AnnouncementParseError> {
    // Gson's `getAsBoolean`: a boolean, or a string that is "true" in any case.
    match v {
        Value::Bool(b) => Ok(*b),
        Value::String(s) => Ok(s.eq_ignore_ascii_case("true")),
        _ => Err(AnnouncementParseError(format!("{key} is not a boolean"))),
    }
}

impl AnnouncementState {
    /// Applies the keys present in `body`, in Java's order; a bad value stops there (the keys
    /// before it stay applied, like Java's).
    pub fn apply_json(&mut self, body: &str) -> Result<(), AnnouncementParseError> {
        let v: Value =
            serde_json::from_str(body).map_err(|e| AnnouncementParseError(e.to_string()))?;
        let Value::Object(map) = v else {
            return Err(AnnouncementParseError("not a JSON object".into()));
        };
        if let Some(x) = map.get("login") {
            self.login = as_string(x, "login")?;
        }
        if let Some(x) = map.get("home") {
            self.home = as_string(x, "home")?;
        }
        if let Some(x) = map.get("config") {
            self.config = as_string(x, "config")?;
        }
        if let Some(x) = map.get("modpacksedit") {
            self.modpacks_edit = as_string(x, "modpacksedit")?;
        }
        if let Some(x) = map.get("disableGameplay") {
            self.disable_gameplay = as_bool(x, "disableGameplay")?;
        }
        if let Some(x) = map.get("disableModpacksEdit") {
            self.disable_modpacks_edit = as_bool(x, "disableModpacksEdit")?;
        }
        Ok(())
    }
}

/// A fetch that settles to `()`; clone it to await it from several places.
pub type CheckFuture = Shared<BoxFuture<'static, ()>>;

/// The announcement store.
#[derive(Default)]
pub struct Announcements {
    state: RwLock<AnnouncementState>,
    in_flight: Mutex<Option<(CheckFuture, Arc<AtomicBool>)>>,
    dismissed: Mutex<HashSet<String>>,
}

static GLOBAL: LazyLock<Arc<Announcements>> = LazyLock::new(|| Arc::new(Announcements::default()));

impl Announcements {
    /// The process-wide store.
    pub fn global() -> &'static Arc<Announcements> {
        &GLOBAL
    }

    /// Starts a fetch of [`ANNOUNCEMENT_URL`] unless one is in flight, and returns it.
    pub fn start_check_async(self: &Arc<Self>) -> CheckFuture {
        let mut slot = self.in_flight.lock();
        if let Some((f, done)) = slot.as_ref()
            && !done.load(Ordering::SeqCst)
        {
            return f.clone();
        }
        let me = self.clone();
        let done = Arc::new(AtomicBool::new(false));
        let done_task = done.clone();
        // The task marks itself done, so a new fetch can start even if nobody awaited this one.
        let task = runtime_handle().spawn(async move {
            me.check().await;
            done_task.store(true, Ordering::SeqCst);
        });
        let fut: BoxFuture<'static, ()> = async move {
            let _ = task.await;
        }
        .boxed();
        let shared = fut.shared();
        *slot = Some((shared.clone(), done));
        shared
    }

    /// The last fetch started, if any.
    pub fn check_future(&self) -> Option<CheckFuture> {
        self.in_flight.lock().as_ref().map(|(f, _)| f.clone())
    }

    /// Fetches and applies the announcements now. Failures are logged
    /// (`log.announcement.loadFailed`) and keep the previous values.
    pub async fn check(&self) {
        match download_string_bounded(ANNOUNCEMENT_URL, ANNOUNCEMENT_MAX_BYTES).await {
            Ok(body) => self.apply_body(&body),
            Err(e) => {
                mmcl_log::error_silent(&t!("log.announcement.loadFailed"));
                mmcl_log::throwable(&e);
            }
        }
    }

    /// Applies a fetched body (logs a parse failure).
    pub fn apply_body(&self, body: &str) {
        let mut st = self.state.write();
        if let Err(e) = st.apply_json(body) {
            mmcl_log::error_silent(&t!("log.announcement.loadFailed"));
            mmcl_log::debug(&e.0);
        }
    }

    /// A snapshot of every value.
    pub fn state(&self) -> AnnouncementState {
        self.state.read().clone()
    }

    pub fn login(&self) -> String {
        self.state.read().login.clone()
    }

    pub fn home(&self) -> String {
        self.state.read().home.clone()
    }

    pub fn config(&self) -> String {
        self.state.read().config.clone()
    }

    pub fn modpacks_edit(&self) -> String {
        self.state.read().modpacks_edit.clone()
    }

    pub fn disable_gameplay(&self) -> bool {
        self.state.read().disable_gameplay
    }

    pub fn disable_modpacks_edit(&self) -> bool {
        self.state.read().disable_modpacks_edit
    }

    /// Hides `text` for the rest of the session.
    pub fn dismiss_for_session(&self, text: &str) {
        if !text.is_empty() {
            self.dismissed.lock().insert(text.to_string());
        }
    }

    /// `true` when exactly this text was dismissed.
    pub fn is_dismissed(&self, text: &str) -> bool {
        self.dismissed.lock().contains(text)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn present_keys_overwrite_and_missing_keys_keep_previous_values() {
        let mut s = AnnouncementState::default();
        s.apply_json(r#"{"login":"Maintenance tonight","disableGameplay":true}"#)
            .unwrap();
        assert_eq!(s.login, "Maintenance tonight");
        assert!(s.disable_gameplay);
        s.apply_json(r#"{"home":"Welcome","disableGameplay":false}"#)
            .unwrap();
        assert_eq!(s.login, "Maintenance tonight");
        assert_eq!(s.home, "Welcome");
        assert!(!s.disable_gameplay);
        assert!(!s.disable_modpacks_edit);
    }

    #[test]
    fn bad_documents_fail_and_keep_earlier_keys() {
        let mut s = AnnouncementState::default();
        assert!(s.apply_json("not json").is_err());
        assert!(s.apply_json("[1]").is_err());
        assert!(s.apply_json(r#"{"login":"a","home":{"x":1}}"#).is_err());
        assert_eq!(s.login, "a");
        assert_eq!(s.home, "");
    }

    #[test]
    fn dismissal_is_keyed_by_exact_text() {
        let a = Announcements::default();
        a.dismiss_for_session("Old news");
        a.dismiss_for_session("");
        assert!(a.is_dismissed("Old news"));
        assert!(!a.is_dismissed("Old news!"));
        assert!(!a.is_dismissed(""));
    }

    #[test]
    fn apply_body_updates_the_store() {
        let a = Announcements::default();
        a.apply_body(r#"{"config":"c","modpacksedit":"m","disableModpacksEdit":true}"#);
        let st = a.state();
        assert_eq!((st.config.as_str(), st.modpacks_edit.as_str()), ("c", "m"));
        assert!(a.disable_modpacks_edit());
    }
}
