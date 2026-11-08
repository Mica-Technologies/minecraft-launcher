// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The tao window and wry web view (desktop platforms only).
//!
//! WebView2 on Windows, WKWebView on macOS, WebKitGTK 4.1 on Linux. The web view starts from a
//! fresh, non-persistent profile every run (incognito plus a throw-away data folder), so a
//! Microsoft session from an earlier sign-in never signs that account back in silently and a
//! second account can be added. The data folder also keeps WebView2 from trying to write next
//! to the executable, which is read-only under Program Files.

use std::io::{self, Read};
use std::rc::Rc;

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

/// Events posted into the tao loop from web-view callbacks and the stdin watcher.
#[derive(Debug)]
enum HelperEvent {
    /// The redirect URL was captured.
    Redirect(String),
    /// stdin closed (`--watch-stdin`).
    ParentGone,
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

    let code = event_loop.run_return(|event, _, control_flow| {
        *control_flow = ControlFlow::Wait;
        match event {
            Event::UserEvent(HelperEvent::Redirect(url)) => {
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
    let on_page_load = move |_event: PageLoadEvent, url: String| {
        if let Some(captured) = load_watcher.observe(&url) {
            let _ = load_proxy.send_event(HelperEvent::Redirect(captured));
        }
    };

    let builder = WebViewBuilder::new_with_web_context(context)
        .with_url(cfg.login_url.as_str())
        .with_user_agent(cfg.user_agent.as_str())
        .with_incognito(true)
        .with_devtools(false)
        .with_navigation_handler(on_navigation)
        .with_on_page_load_handler(on_page_load)
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

/// Reads stdin to end-of-file on a background thread, then tells the loop the parent is gone.
fn spawn_stdin_watcher(proxy: EventLoopProxy<HelperEvent>) {
    let spawned = std::thread::Builder::new()
        .name("mmcl-signin-stdin".into())
        .spawn(move || {
            let mut buf = [0u8; 256];
            let mut stdin = io::stdin().lock();
            loop {
                match stdin.read(&mut buf) {
                    Ok(0) => break,
                    Ok(_) => continue,
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
