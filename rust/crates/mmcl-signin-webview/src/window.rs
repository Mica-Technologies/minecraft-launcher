// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The tao window and wry web view (desktop platforms only).
//!
//! WebView2 on Windows, WKWebView on macOS, WebKitGTK 4.1 on Linux. The web view starts from a
//! fresh, non-persistent profile every run (incognito plus a throw-away data folder), so a
//! Microsoft session from an earlier sign-in never signs that account back in silently and a
//! second account can be added. The data folder also keeps WebView2 from trying to write next
//! to the executable, which is read-only under Program Files.

use std::io::{self, BufRead, Read};
use std::rc::Rc;
use std::time::Instant;

use tao::dpi::{LogicalSize, PhysicalPosition, PhysicalSize};
use tao::event::{Event, WindowEvent};
use tao::event_loop::{ControlFlow, EventLoopBuilder, EventLoopProxy};
use tao::platform::run_return::EventLoopExtRunReturn;
use tao::window::{Window, WindowBuilder};
use wry::{NewWindowResponse, PageLoadEvent, WebContext, WebView, WebViewBuilder};

use crate::capture::{NavDecision, RedirectWatcher};
use crate::config::HelperConfig;
use crate::layout;
use crate::output::{EXIT_CANCELLED, EXIT_FAILED, EXIT_PARENT_GONE, EXIT_REDIRECT, emit_redirect};
use crate::page::{self, PageEvent, PageLoadTracker};

/// Events posted into the tao loop from web-view callbacks and the stdin watcher.
#[derive(Debug)]
enum HelperEvent {
    /// The redirect URL was captured.
    Redirect(String),
    /// stdin closed (`--watch-stdin`).
    ParentGone,
    /// A main-frame page load started.
    PageStarted,
    /// A main-frame page load finished (possibly on the engine's error page).
    PageFinished,
    /// The page posted its ready message.
    PageReady,
    /// The reachability probe could not connect to the sign-in host.
    Unreachable,
    /// The parent asked for the page to load again ("Try again").
    Reload,
}

/// Prints a page line for the parent; a closed stdout only loses the hint.
fn report(event: Option<PageEvent>) {
    if let Some(e) = event
        && let Err(err) = page::emit_page_event(&mut io::stdout().lock(), e)
    {
        tracing::debug!(
            "{}",
            mmcl_i18n::tf!("signInHelper.log.outputFailed", err.to_string())
        );
    }
}

/// Runs the window until the redirect, a close, or the parent going away. Returns the exit code.
pub(crate) fn run(cfg: &HelperConfig) -> i32 {
    let data_dir = match tempfile::Builder::new().prefix("mmcl-signin-").tempdir() {
        Ok(d) => d,
        Err(e) => {
            tracing::error!(
                "{}",
                mmcl_i18n::tf!("signInHelper.log.dataDirFailed", e.to_string())
            );
            return EXIT_FAILED;
        }
    };

    let mut event_loop = EventLoopBuilder::<HelperEvent>::with_user_event().build();
    let proxy = event_loop.create_proxy();

    let window = match WindowBuilder::new()
        .with_title(cfg.window_title())
        .with_inner_size(LogicalSize::new(
            layout::PREFERRED_SIZE.0,
            layout::PREFERRED_SIZE.1,
        ))
        .with_min_inner_size(LogicalSize::new(layout::MIN_SIZE.0, layout::MIN_SIZE.1))
        .with_visible(false)
        .with_focused(true)
        .build(&event_loop)
    {
        Ok(w) => w,
        Err(e) => {
            tracing::error!(
                "{}",
                mmcl_i18n::tf!("signInHelper.log.windowFailed", e.to_string())
            );
            return EXIT_FAILED;
        }
    };
    place_on_monitor(&window);

    let mut context = WebContext::new(Some(data_dir.path().to_path_buf()));
    let webview = match build_webview(cfg, &window, &mut context, &proxy) {
        Ok(v) => v,
        Err(e) => {
            tracing::error!(
                "{}",
                mmcl_i18n::tf!("signInHelper.log.webviewFailed", e.to_string())
            );
            return EXIT_FAILED;
        }
    };

    window.set_visible(true);
    window.set_focus();
    let _ = webview.focus();

    if cfg.watch_stdin {
        spawn_stdin_watcher(proxy.clone());
    }
    spawn_reachability_probe(cfg.login_url.clone(), proxy.clone());

    let mut tracker = PageLoadTracker::new();
    report(tracker.on_started(Instant::now()));

    let code = event_loop.run_return(|event, _, control_flow| {
        let now = Instant::now();
        match event {
            Event::UserEvent(HelperEvent::PageStarted) => report(tracker.on_started(now)),
            Event::UserEvent(HelperEvent::PageFinished) => report(tracker.on_finished(now)),
            Event::UserEvent(HelperEvent::PageReady) => report(tracker.on_ready()),
            Event::UserEvent(HelperEvent::Unreachable) => {
                tracing::warn!("{}", mmcl_i18n::t!("signInHelper.log.unreachable"));
                report(tracker.on_unreachable(now));
            }
            Event::UserEvent(HelperEvent::Reload) => {
                tracing::info!("{}", mmcl_i18n::t!("signInHelper.log.reload"));
                tracker.on_reload();
                report(tracker.on_started(now));
                if let Err(e) = webview.load_url(cfg.login_url.as_str()) {
                    tracing::warn!(
                        "{}",
                        mmcl_i18n::tf!("signInHelper.log.webviewFailed", e.to_string())
                    );
                }
                spawn_reachability_probe(cfg.login_url.clone(), proxy.clone());
            }
            Event::UserEvent(HelperEvent::Redirect(url)) => {
                tracker.on_redirect();
                window.set_visible(false);
                let code = match emit_redirect(&mut io::stdout().lock(), &url) {
                    Ok(()) => {
                        tracing::info!("{}", mmcl_i18n::t!("signInHelper.log.redirectCaptured"));
                        EXIT_REDIRECT
                    }
                    Err(e) => {
                        tracing::error!(
                            "{}",
                            mmcl_i18n::tf!("signInHelper.log.outputFailed", e.to_string())
                        );
                        EXIT_FAILED
                    }
                };
                *control_flow = ControlFlow::ExitWithCode(code);
            }
            Event::UserEvent(HelperEvent::ParentGone) => {
                tracing::info!("{}", mmcl_i18n::t!("signInHelper.log.parentGone"));
                *control_flow = ControlFlow::ExitWithCode(EXIT_PARENT_GONE);
            }
            Event::WindowEvent {
                event: WindowEvent::CloseRequested,
                ..
            } => {
                tracing::info!("{}", mmcl_i18n::t!("signInHelper.log.closed"));
                *control_flow = ControlFlow::ExitWithCode(EXIT_CANCELLED);
            }
            _ => {}
        }
        if matches!(*control_flow, ControlFlow::ExitWithCode(_)) {
            return;
        }
        report(tracker.poll(now));
        *control_flow = match tracker.next_deadline() {
            Some(at) => ControlFlow::WaitUntil(at.max(now)),
            None => ControlFlow::Wait,
        };
    });

    // Tear down the web view before its data folder goes away.
    drop(webview);
    drop(window);
    drop(context);
    drop(data_dir);
    code
}

/// Builds the web view with the redirect hooks attached once, for the life of the view.
fn build_webview(
    cfg: &HelperConfig,
    window: &Window,
    context: &mut WebContext,
    proxy: &EventLoopProxy<HelperEvent>,
) -> wry::Result<WebView> {
    let watcher = Rc::new(RedirectWatcher::new(cfg.redirect_prefix.clone()));

    let nav_watcher = watcher.clone();
    let nav_proxy = proxy.clone();
    let on_navigation = move |url: String| -> bool {
        match nav_watcher.decide(&url) {
            NavDecision::Allow => true,
            NavDecision::Block => false,
            NavDecision::Capture(captured) => {
                let _ = nav_proxy.send_event(HelperEvent::Redirect(captured));
                false
            }
        }
    };

    // Backstop: some engines report a server redirect only as a page load.
    let load_watcher = watcher;
    let load_proxy = proxy.clone();
    let on_page_load = move |event: PageLoadEvent, url: String| {
        if let Some(captured) = load_watcher.observe(&url) {
            let _ = load_proxy.send_event(HelperEvent::Redirect(captured));
            return;
        }
        if load_watcher.is_done() {
            return;
        }
        let _ = load_proxy.send_event(match event {
            PageLoadEvent::Started => HelperEvent::PageStarted,
            PageLoadEvent::Finished => HelperEvent::PageFinished,
        });
    };

    // The page says when a real document is up (engine error pages never run this script).
    let ipc_proxy = proxy.clone();
    let on_ipc = move |req: wry::http::Request<String>| {
        if req.body() == page::READY_MESSAGE {
            let _ = ipc_proxy.send_event(HelperEvent::PageReady);
        }
    };

    let builder = WebViewBuilder::new_with_web_context(context)
        .with_url(cfg.login_url.as_str())
        .with_user_agent(cfg.user_agent.as_str())
        .with_incognito(true)
        .with_devtools(false)
        .with_navigation_handler(on_navigation)
        .with_on_page_load_handler(on_page_load)
        .with_initialization_script(page::READY_SCRIPT)
        .with_ipc_handler(on_ipc)
        .with_new_window_req_handler(|_url, _features| {
            // Help and legal links would open a second, unmanaged window; the flow stays in one.
            tracing::debug!("{}", mmcl_i18n::t!("signInHelper.log.popupBlocked"));
            NewWindowResponse::Deny
        });

    #[cfg(target_os = "linux")]
    {
        use tao::platform::unix::WindowExtUnix;
        use wry::WebViewBuilderExtUnix;
        match window.default_vbox() {
            Some(vbox) => builder.build_gtk(vbox),
            None => builder.build_gtk(window.gtk_window()),
        }
    }
    #[cfg(not(target_os = "linux"))]
    {
        builder.build(window)
    }
}

/// Fits the window to its monitor and centres it there.
fn place_on_monitor(window: &Window) {
    let Some(monitor) = window
        .current_monitor()
        .or_else(|| window.primary_monitor())
    else {
        return;
    };
    let scale = monitor.scale_factor();
    let mon = monitor.size();
    let origin = monitor.position();
    let to_physical = |v: f64| (v * scale).round() as u32;
    let (w, h) = layout::fit_size(
        (
            to_physical(layout::PREFERRED_SIZE.0),
            to_physical(layout::PREFERRED_SIZE.1),
        ),
        (
            to_physical(layout::MIN_SIZE.0),
            to_physical(layout::MIN_SIZE.1),
        ),
        (mon.width, mon.height),
    );
    window.set_inner_size(PhysicalSize::new(w, h));
    let outer = window.outer_size();
    let (x, y) = layout::centered(
        (origin.x, origin.y),
        (mon.width, mon.height),
        (outer.width, outer.height),
    );
    window.set_outer_position(PhysicalPosition::new(x, y));
}

/// Checks on a background thread whether the sign-in host takes a connection at all, and tells
/// the loop when it does not. Skipped when the environment configures a proxy (a direct
/// connection would prove nothing).
fn spawn_reachability_probe(login_url: String, proxy: EventLoopProxy<HelperEvent>) {
    if page::proxy_in_environment(|k| std::env::var(k).ok()) {
        return;
    }
    let Some((host, port)) = page::probe_target(&login_url) else {
        return;
    };
    let _ = std::thread::Builder::new()
        .name("mmcl-signin-probe".into())
        .spawn(move || {
            if !page::host_reachable(&host, port, page::PROBE_TIMEOUT) {
                let _ = proxy.send_event(HelperEvent::Unreachable);
            }
        });
}

/// Reads stdin lines on a background thread: [`page::RELOAD_COMMAND`] reloads the page, and
/// end-of-file tells the loop the parent is gone.
fn spawn_stdin_watcher(proxy: EventLoopProxy<HelperEvent>) {
    let spawned = std::thread::Builder::new()
        .name("mmcl-signin-stdin".into())
        .spawn(move || {
            let mut stdin = io::stdin().lock();
            let mut line = Vec::new();
            loop {
                line.clear();
                // Bounded: a line longer than this is not a command.
                match (&mut stdin).take(256).read_until(b'\n', &mut line) {
                    Ok(0) => break,
                    Ok(_) => {
                        if page::is_reload_command(&String::from_utf8_lossy(&line)) {
                            let _ = proxy.send_event(HelperEvent::Reload);
                        }
                    }
                    Err(e) if e.kind() == io::ErrorKind::Interrupted => continue,
                    Err(_) => break,
                }
            }
            let _ = proxy.send_event(HelperEvent::ParentGone);
        });
    if let Err(e) = spawned {
        tracing::warn!(
            "{}",
            mmcl_i18n::tf!("signInHelper.log.stdinWatchFailed", e.to_string())
        );
    }
}
