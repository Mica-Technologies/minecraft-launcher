// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The `tracing` bridge: a [`Layer`] that turns events into launcher log lines.
//!
//! Levels map as `ERROR` → `ERROR`, `WARN` → `WARN`, `INFO` → `STD`, `DEBUG`/`TRACE` → `DEBUG`
//! (gated like `logDebug`). Events from the launcher's own crates (targets starting `mmcl`) pass
//! at every level; dependencies such as `reqwest` and `hyper` pass at `WARN` and above only, so
//! they do not flood the launcher log. `log`-crate records are bridged too (via `tracing-log`).
//!
//! Events logged through `tracing` are always "silent": no dialog or toast. Use the crate's
//! free functions ([`crate::warn`], [`crate::error`]) for those.

use std::fmt::{self, Write as _};
use std::sync::Arc;

use tracing::field::{Field, Visit};
use tracing::subscriber::Interest;
use tracing::{Event, Metadata, Subscriber};
use tracing_subscriber::layer::{Context, Layer};

use crate::logger::{Level, Logger};

/// Formats `tracing` events through a [`Logger`].
pub struct LauncherLayer {
    logger: Arc<Logger>,
}

impl LauncherLayer {
    /// A layer writing into `logger`.
    pub fn new(logger: Arc<Logger>) -> Self {
        LauncherLayer { logger }
    }
}

/// `true` for targets that belong to the launcher (crate names `mmcl_*`).
pub fn is_launcher_target(target: &str) -> bool {
    target.starts_with("mmcl")
}

/// The launcher level for a `tracing` level.
pub fn map_level(level: &tracing::Level) -> Level {
    match *level {
        tracing::Level::ERROR => Level::Error,
        tracing::Level::WARN => Level::Warn,
        tracing::Level::INFO => Level::Std,
        _ => Level::Debug,
    }
}

/// Whether an event passes the target filter (debug gating is applied later, per event).
pub fn target_enabled(meta: &Metadata<'_>) -> bool {
    is_launcher_target(meta.target()) || *meta.level() <= tracing::Level::WARN
}

impl<S: Subscriber> Layer<S> for LauncherLayer {
    fn register_callsite(&self, meta: &'static Metadata<'static>) -> Interest {
        if !target_enabled(meta) {
            Interest::never()
        } else if *meta.level() >= tracing::Level::DEBUG {
            // The debug flag changes at runtime, so ask per event.
            Interest::sometimes()
        } else {
            Interest::always()
        }
    }

    fn enabled(&self, meta: &Metadata<'_>, _ctx: Context<'_, S>) -> bool {
        target_enabled(meta)
            && (map_level(meta.level()) != Level::Debug || self.logger.debug_allowed())
    }

    fn on_event(&self, event: &Event<'_>, _ctx: Context<'_, S>) {
        let level = map_level(event.metadata().level());
        let mut visitor = MessageVisitor::default();
        event.record(&mut visitor);
        self.logger.log(level, &visitor.finish());
    }
}

/// Collects the `message` field, then `key=value` for the other fields.
#[derive(Default)]
pub(crate) struct MessageVisitor {
    message: String,
    fields: String,
}

impl MessageVisitor {
    fn push_field(&mut self, name: &str, value: fmt::Arguments<'_>) {
        // `log.target`, `log.file`, ... are bridge metadata, not content.
        if name.starts_with("log.") {
            return;
        }
        let _ = write!(self.fields, " {name}={value}");
    }

    pub(crate) fn finish(self) -> String {
        if self.message.is_empty() {
            self.fields.trim_start().to_string()
        } else {
            self.message + &self.fields
        }
    }
}

impl Visit for MessageVisitor {
    fn record_str(&mut self, field: &Field, value: &str) {
        if field.name() == "message" {
            self.message.push_str(value);
        } else {
            self.push_field(field.name(), format_args!("{value}"));
        }
    }

    fn record_error(&mut self, field: &Field, value: &(dyn std::error::Error + 'static)) {
        self.push_field(field.name(), format_args!("{value}"));
    }

    fn record_debug(&mut self, field: &Field, value: &dyn fmt::Debug) {
        if field.name() == "message" {
            let _ = write!(self.message, "{value:?}");
        } else {
            self.push_field(field.name(), format_args!("{value:?}"));
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::logger::tests::logger;
    use tracing_subscriber::layer::SubscriberExt;

    #[test]
    fn events_become_launcher_lines() {
        let (l, cap) = logger(false);
        let l = Arc::new(l);
        let subscriber = tracing_subscriber::registry().with(LauncherLayer::new(l.clone()));
        tracing::subscriber::with_default(subscriber, || {
            tracing::info!(target: "mmcl_core", "hello {}", 42);
            tracing::warn!(target: "mmcl_net", url = "https://x", "slow --accessToken abc");
            tracing::error!(target: "mmcl_auth", "bad");
            tracing::debug!(target: "mmcl_core", "dropped before ready");
            tracing::info!(target: "reqwest::connect", "noise");
            tracing::warn!(target: "hyper", "dependency warning");
        });
        assert_eq!(*cap.out.lock(), vec!["[Test App/STD] hello 42"]);
        assert_eq!(
            *cap.err.lock(),
            vec![
                "[Test App/WARN] slow --accessToken [REDACTED] url=https://x",
                "[Test App/ERROR] bad",
                "[Test App/WARN] dependency warning",
            ]
        );
    }

    #[test]
    fn debug_events_follow_the_runtime_flag() {
        let (l, cap) = logger(false);
        let l = Arc::new(l);
        let subscriber = tracing_subscriber::registry().with(LauncherLayer::new(l.clone()));
        tracing::subscriber::with_default(subscriber, || {
            let emit = || tracing::debug!(target: "mmcl_modpack", "d");
            emit();
            l.enable_config_backed_debug(true);
            emit();
            l.set_debug_enabled(false);
            emit();
            tracing::trace!(target: "reqwest", "never");
        });
        assert_eq!(*cap.out.lock(), vec!["[Test App/DEBUG] d"]);
    }

    #[test]
    fn field_only_events_print_their_fields() {
        let mut v = MessageVisitor::default();
        v.push_field("a", format_args!("1"));
        v.push_field("log.target", format_args!("x"));
        assert_eq!(v.finish(), "a=1");
    }
}
