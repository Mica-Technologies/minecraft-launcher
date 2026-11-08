// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Localized launcher-log lines for the MCP subsystem. Every message is a `log.mcp*` key;
//! the logger redacts each line again on the way out.

use mmcl_i18n::FmtArg;

fn render(key: &str, args: &[&str]) -> String {
    let args: Vec<FmtArg> = args.iter().map(|a| FmtArg::from(*a)).collect();
    mmcl_i18n::format(key, &args)
}

/// Java `logStd`.
pub(crate) fn std(key: &str, args: &[&str]) {
    mmcl_log::std(&render(key, args));
}

/// Java `logDebug`.
pub(crate) fn debug(key: &str, args: &[&str]) {
    mmcl_log::debug(&render(key, args));
}

/// Java `logWarningSilent`.
pub(crate) fn warn(key: &str, args: &[&str]) {
    mmcl_log::warn_silent(&render(key, args));
}

/// Java `logErrorSilent`.
pub(crate) fn error(key: &str, args: &[&str]) {
    mmcl_log::error_silent(&render(key, args));
}

/// Java `logErrorSilent` followed by `logThrowable`.
pub(crate) fn error_with(key: &str, args: &[&str], err: &dyn std::fmt::Display) {
    mmcl_log::error_silent(&format!("{}: {}", render(key, args), err));
}

/// Java `logWarningSilent(prefix, throwable)`.
pub(crate) fn warn_with(key: &str, args: &[&str], err: &dyn std::fmt::Display) {
    mmcl_log::warn_silent(&format!("{}: {}", render(key, args), err));
}

/// A panic payload as text.
pub(crate) fn panic_text(payload: &(dyn std::any::Any + Send)) -> String {
    if let Some(s) = payload.downcast_ref::<&str>() {
        (*s).to_string()
    } else if let Some(s) = payload.downcast_ref::<String>() {
        s.clone()
    } else {
        "panic".to_string()
    }
}
