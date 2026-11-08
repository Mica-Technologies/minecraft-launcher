// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Localization for the Mica Minecraft Launcher (Rust port of `LocalizationManager`,
//! `LocaleBootstrap` and `SupportedLocales`).
//!
//! The Java launcher's `.properties` bundles in `src/main/resources/lang/` are embedded at build
//! time, untouched, together with the Rust overlay in `rust/i18n/` (overlay keys win). Lookups are
//! dynamic, so the UI language can change at runtime with [`set_locale`]; nothing binds a string
//! at startup.
//!
//! ```
//! use mmcl_i18n::{t, tf};
//! mmcl_i18n::set_locale(mmcl_i18n::Locale::en_us());
//! assert_eq!(t!("main.pagination.pageOfPages"), "Page {0} of {1}");
//! assert_eq!(tf!("main.pagination.pageOfPages", 2, 5), "Page 2 of 5");
//! ```
//!
//! Semantics follow the Java launcher exactly:
//!
//! * [`get`]: a missing key returns the key itself;
//! * [`format`]: with no arguments the raw template is returned (`{0}` untouched); otherwise the
//!   `MessageFormat` subset in [`msgfmt`] runs with every apostrophe literal, and a malformed
//!   pattern gives `template [arg1, arg2]`;
//! * locale resolution walks Java's `ResourceBundle` candidate chain ([`bundle::candidates`]),
//!   with child bundles inheriting missing keys from their parents.

use std::borrow::Cow;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, LazyLock, Mutex};

use arc_swap::ArcSwap;

pub mod bundle;
pub mod locale;
pub mod msgfmt;
pub mod number;
pub mod properties;
mod supported;

pub use locale::{LanguageIdentifier, Locale};
pub use msgfmt::{FmtArg, PatternError};
pub use supported::{SUPPORTED, display_name, supported_locales};

/// Looks up a localized string: `t!("main.title")`. A missing key yields the key itself.
#[macro_export]
macro_rules! t {
    ($key:expr $(,)?) => {
        $crate::get($key)
    };
}

/// Formats a localized template: `tf!("main.pagination.pageOfPages", page, total)`.
///
/// Each argument goes through `FmtArg::from`, so strings, `&String`, numbers, booleans, paths and
/// `Option`s work directly; wrap anything else in `FmtArg::display(..)`. Pass `n.to_string()`
/// for a number that must not get locale digit grouping.
#[macro_export]
macro_rules! tf {
    ($key:expr $(,)?) => {
        $crate::format($key, &[])
    };
    ($key:expr, $($arg:expr),+ $(,)?) => {
        $crate::format($key, &[$($crate::FmtArg::from($arg)),+])
    };
}

struct Active {
    /// The locale asked for (numbers are formatted in it, like Java's default locale).
    requested: Locale,
    /// The locale of the most specific bundle found.
    locale: Locale,
    chain: Vec<&'static bundle::FileBundle>,
}

impl Active {
    fn new(requested: Locale) -> Self {
        let resolved = bundle::resolve(&requested);
        Active {
            requested,
            locale: resolved.locale,
            chain: resolved.chain,
        }
    }

    fn lookup(&self, key: &str) -> Option<&'static str> {
        self.chain.iter().find_map(|b| b.get(key))
    }
}

/// Identifies a listener registered with [`Localizer::on_locale_changed`].
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct ListenerId(u64);

type Listener = Arc<dyn Fn(&Locale) + Send + Sync>;

/// A localizer: the active bundle chain plus the requested locale, swapped atomically.
///
/// The process-wide instance behind the free functions is [`localizer()`]; separate instances are
/// useful in tests and tools that need a locale of their own.
pub struct Localizer {
    active: ArcSwap<Active>,
    revision: AtomicU64,
    next_listener: AtomicU64,
    listeners: Mutex<Vec<(ListenerId, Listener)>>,
}

impl Localizer {
    /// A localizer showing `locale`.
    pub fn new(locale: Locale) -> Self {
        Localizer {
            active: ArcSwap::from_pointee(Active::new(locale)),
            revision: AtomicU64::new(0),
            next_listener: AtomicU64::new(0),
            listeners: Mutex::new(Vec::new()),
        }
    }

    /// Switches language. Resolves and loads the new chain first, then swaps the
    /// `(bundle, locale)` pair in one step, so a reader never sees a new bundle with an old
    /// locale. Listeners run afterwards, on the calling thread.
    pub fn set_locale(&self, locale: Locale) {
        let next = Arc::new(Active::new(locale));
        let shown = next.locale.clone();
        self.active.store(next);
        self.revision.fetch_add(1, Ordering::AcqRel);
        let listeners: Vec<Listener> = match self.listeners.lock() {
            Ok(l) => l.iter().map(|(_, f)| f.clone()).collect(),
            Err(poisoned) => poisoned.get_ref().iter().map(|(_, f)| f.clone()).collect(),
        };
        for f in listeners {
            f(&shown);
        }
    }

    /// The locale of the most specific bundle found ([`Locale::ROOT`] when English is shown
    /// because nothing more specific exists), as Java's `currentLocale()`.
    pub fn current_locale(&self) -> Locale {
        self.active.load().locale.clone()
    }

    /// The locale last passed to [`set_locale`](Self::set_locale).
    pub fn requested_locale(&self) -> Locale {
        self.active.load().requested.clone()
    }

    /// Increases by one on every locale change; views can compare it to know when to rebuild
    /// cached strings.
    pub fn revision(&self) -> u64 {
        self.revision.load(Ordering::Acquire)
    }

    /// Calls `f` with the new bundle locale after every [`set_locale`](Self::set_locale).
    pub fn on_locale_changed(&self, f: impl Fn(&Locale) + Send + Sync + 'static) -> ListenerId {
        let id = ListenerId(self.next_listener.fetch_add(1, Ordering::Relaxed));
        let mut l = self.listeners.lock().unwrap_or_else(|p| p.into_inner());
        l.push((id, Arc::new(f)));
        id
    }

    /// Removes a listener added with [`on_locale_changed`](Self::on_locale_changed).
    pub fn remove_listener(&self, id: ListenerId) {
        let mut l = self.listeners.lock().unwrap_or_else(|p| p.into_inner());
        l.retain(|(i, _)| *i != id);
    }

    /// The localized value, or `None` when no bundle in the chain has `key`.
    pub fn lookup(&self, key: &str) -> Option<&'static str> {
        self.active.load().lookup(key)
    }

    /// True when `key` resolves in the active chain.
    pub fn contains(&self, key: &str) -> bool {
        self.lookup(key).is_some()
    }

    /// Java `get`: the localized value, or the key itself when missing.
    pub fn get(&self, key: &str) -> Cow<'static, str> {
        match self.lookup(key) {
            Some(v) => Cow::Borrowed(v),
            None => Cow::Owned(key.to_string()),
        }
    }

    /// Java `getOr`: the localized value, or `fallback` when missing.
    pub fn get_or<'a>(&self, key: &str, fallback: &'a str) -> Cow<'a, str> {
        match self.lookup(key) {
            Some(v) => Cow::Borrowed(v),
            None => Cow::Borrowed(fallback),
        }
    }

    /// Java `format`: see the crate docs for the exact rules.
    pub fn format(&self, key: &str, args: &[FmtArg]) -> String {
        let active = self.active.load();
        let template: &str = active.lookup(key).unwrap_or(key);
        format_template(template, args, &active.requested)
    }
}

/// Formats a template that is already in hand (not a key) with the [`format`] rules: no args
/// returns it raw, a malformed pattern returns `template [a, b]`.
pub fn format_template(template: &str, args: &[FmtArg], number_locale: &Locale) -> String {
    if args.is_empty() {
        return template.to_string();
    }
    match msgfmt::format(template, args, number_locale) {
        Ok(s) => s,
        Err(_) => {
            let joined: Vec<String> = args.iter().map(FmtArg::to_java_string).collect();
            format!("{template} [{}]", joined.join(", "))
        }
    }
}

static GLOBAL: LazyLock<Localizer> = LazyLock::new(|| Localizer::new(locale::detect_os()));

/// The process-wide localizer. Until [`init`] runs it shows the OS locale, as Java does before
/// `LocaleBootstrap` applies an override.
pub fn localizer() -> &'static Localizer {
    &GLOBAL
}

/// Applies the startup locale (normally `locale::resolve(config override)`), once, after the
/// config is loaded.
pub fn init(locale: Locale) {
    GLOBAL.set_locale(locale);
}

/// Switches the UI language at runtime. Callers then refresh their windows.
pub fn set_locale(locale: Locale) {
    GLOBAL.set_locale(locale);
}

/// The locale of the most specific bundle in use (root when English is the fallback).
pub fn current_locale() -> Locale {
    GLOBAL.current_locale()
}

/// The locale last requested with [`init`] or [`set_locale`].
pub fn requested_locale() -> Locale {
    GLOBAL.requested_locale()
}

/// Counter bumped on every locale change.
pub fn revision() -> u64 {
    GLOBAL.revision()
}

/// Registers a callback run after every locale change on the global localizer.
pub fn on_locale_changed(f: impl Fn(&Locale) + Send + Sync + 'static) -> ListenerId {
    GLOBAL.on_locale_changed(f)
}

/// Removes a global locale-change callback.
pub fn remove_listener(id: ListenerId) {
    GLOBAL.remove_listener(id)
}

/// Localized value for `key`; the key itself when missing.
pub fn get(key: &str) -> Cow<'static, str> {
    GLOBAL.get(key)
}

/// Localized value for `key`; `fallback` when missing.
pub fn get_or<'a>(key: &str, fallback: &'a str) -> Cow<'a, str> {
    GLOBAL.get_or(key, fallback)
}

/// Localized value for `key`, or `None` when missing.
pub fn lookup(key: &str) -> Option<&'static str> {
    GLOBAL.lookup(key)
}

/// True when `key` exists in the active chain (always true for any English key).
pub fn contains(key: &str) -> bool {
    GLOBAL.contains(key)
}

/// Formats the template for `key` with `args` (Java `LocalizationManager.format`).
pub fn format(key: &str, args: &[FmtArg]) -> String {
    GLOBAL.format(key, args)
}

/// The English (root bundle, Java + overlay) value for `key`, whatever the active locale.
pub fn english(key: &str) -> Option<&'static str> {
    bundle::root().get(key)
}

/// Every key of the English root bundle (Java + overlay), in no particular order.
pub fn english_keys() -> impl Iterator<Item = &'static str> {
    bundle::root().keys()
}
