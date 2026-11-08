// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Termination signals in GUI mode (Java's `Runtime.addShutdownHook`): Ctrl-C, SIGTERM and
//! SIGHUP on Unix; Ctrl-C, Ctrl-Break and the console close / logoff / shutdown events on
//! Windows. The first one flushes the configuration at once (Windows ends the process a few
//! seconds after a close event), then runs the normal back-end cleanup (MCP endpoint file,
//! RGB, Discord, tray, instance lock, logs) and exits.
//!
//! Server mode has its own Ctrl-C handling in the engine (stop the server, then exit).

use std::sync::Arc;

use mmcl_engine::Engine;
use mmcl_mcp::McpBootstrap;

/// The exit code after a signal-driven shutdown (the cleanup ran; nothing failed).
pub const EXIT_CODE: i32 = 0;

/// Watches for termination signals on the engine's runtime for the rest of the process.
pub fn install(engine: &Engine, mcp: Arc<McpBootstrap>) {
    let e = engine.clone();
    // The task is detached: dropping the handle leaves it running.
    drop(engine.spawn(async move {
        if !wait_for_termination().await {
            return;
        }
        tracing::info!("{}", mmcl_i18n::t!("log.app.terminationSignal"));
        e.config().flush();
        // The cleanup blocks on the engine's runtime, so it runs on a thread of its own.
        let spawned = std::thread::Builder::new()
            .name("Launcher-Signal".into())
            .spawn(move || {
                crate::lifecycle::cleanup(Some(&e), Some(&mcp));
                std::process::exit(EXIT_CODE);
            });
        if spawned.is_err() {
            std::process::exit(EXIT_CODE);
        }
    }));
}

/// Resolves `true` on the first termination signal, `false` when none could be registered.
#[cfg(unix)]
async fn wait_for_termination() -> bool {
    use tokio::signal::unix::{SignalKind, signal};
    let mut streams: Vec<_> = [
        SignalKind::terminate(),
        SignalKind::interrupt(),
        SignalKind::hangup(),
    ]
    .into_iter()
    .filter_map(|kind| signal(kind).ok())
    .collect();
    if streams.is_empty() {
        return false;
    }
    let waits = streams.iter_mut().map(|s| Box::pin(s.recv()));
    futures::future::select_all(waits).await;
    true
}

/// Resolves `true` on the first termination event, `false` when none could be registered.
#[cfg(windows)]
async fn wait_for_termination() -> bool {
    use tokio::signal::windows;
    let mut waits: Vec<std::pin::Pin<Box<dyn std::future::Future<Output = ()> + Send>>> =
        Vec::new();
    if let Ok(mut s) = windows::ctrl_c() {
        waits.push(Box::pin(async move {
            s.recv().await;
        }));
    }
    if let Ok(mut s) = windows::ctrl_break() {
        waits.push(Box::pin(async move {
            s.recv().await;
        }));
    }
    if let Ok(mut s) = windows::ctrl_close() {
        waits.push(Box::pin(async move {
            s.recv().await;
        }));
    }
    if let Ok(mut s) = windows::ctrl_logoff() {
        waits.push(Box::pin(async move {
            s.recv().await;
        }));
    }
    if let Ok(mut s) = windows::ctrl_shutdown() {
        waits.push(Box::pin(async move {
            s.recv().await;
        }));
    }
    if waits.is_empty() {
        return false;
    }
    futures::future::select_all(waits).await;
    true
}

/// No signals on other targets.
#[cfg(not(any(unix, windows)))]
async fn wait_for_termination() -> bool {
    false
}

#[cfg(test)]
mod tests {
    #[test]
    fn the_log_key_exists() {
        assert!(mmcl_i18n::english("log.app.terminationSignal").is_some());
    }
}
