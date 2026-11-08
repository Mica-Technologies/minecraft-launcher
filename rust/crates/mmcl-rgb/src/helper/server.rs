// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The helper-process side: runs one vendor-DLL backend and answers the launcher's requests.

use std::fmt::Write as _;
use std::io::{self, BufRead, Write};
use std::sync::{Arc, OnceLock};

use parking_lot::Mutex;
use tracing::field::{Field, Visit};
use tracing::{Event, Subscriber};
use tracing_subscriber::layer::{Context, Layer};

use super::protocol::{self, PROTOCOL_VERSION, Reply, Request};
use crate::backend::{BackendKind, RgbBackend, guarded};

/// A shared line writer (the helper's stdout).
pub type SharedWriter = Arc<Mutex<Box<dyn Write + Send>>>;

/// Writes one message as a line; write errors (the launcher went away) are ignored here and
/// noticed by the read loop.
pub fn send(w: &SharedWriter, reply: &Reply) {
    let mut w = w.lock();
    let _ = writeln!(w, "{}", protocol::encode(reply));
    let _ = w.flush();
}

/// Serves requests from `input` until `exit` or end of input, then shuts the backend down.
pub fn serve(mut backend: Box<dyn RgbBackend>, input: impl BufRead, out: SharedWriter) {
    let kind = backend.kind().helper_arg().unwrap_or("unknown").to_string();
    send(
        &out,
        &Reply::Ready {
            kind,
            version: PROTOCOL_VERSION,
        },
    );
    let mut started = false;
    for line in input.lines() {
        let Ok(line) = line else { break };
        if line.trim().is_empty() {
            continue;
        }
        let req: Request = match protocol::decode(&line) {
            Ok(r) => r,
            Err(e) => {
                send(&out, &Reply::Err { msg: e.to_string() });
                continue;
            }
        };
        let reply = match req {
            Request::Available => match guarded(|| Ok(backend.is_available())) {
                Ok(v) => Reply::Ok { value: Some(v) },
                Err(e) => Reply::Err { msg: e.to_string() },
            },
            Request::Start => match guarded(|| backend.start()) {
                Ok(()) => {
                    started = true;
                    Reply::Ok { value: None }
                }
                Err(e) => Reply::Err { msg: e.to_string() },
            },
            Request::Render { bg, keys } => {
                let frame = protocol::frame_of(bg, &keys);
                match guarded(|| backend.render_frame(&frame)) {
                    Ok(()) => Reply::Ok { value: None },
                    Err(e) => Reply::Err { msg: e.to_string() },
                }
            }
            Request::Shutdown => {
                let _ = guarded(|| {
                    backend.shutdown();
                    Ok(())
                });
                started = false;
                Reply::Ok { value: None }
            }
            Request::Exit => {
                send(&out, &Reply::Ok { value: None });
                break;
            }
        };
        send(&out, &reply);
    }
    if started {
        let _ = guarded(|| {
            backend.shutdown();
            Ok(())
        });
    }
}

/// A `tracing` layer that forwards events to the launcher as [`Reply::Log`] lines.
struct ForwardLayer {
    out: SharedWriter,
}

#[derive(Default)]
struct MessageVisitor {
    msg: String,
}

impl Visit for MessageVisitor {
    fn record_debug(&mut self, field: &Field, value: &dyn std::fmt::Debug) {
        if field.name() == "message" {
            let _ = write!(self.msg, "{value:?}");
        } else {
            let _ = write!(self.msg, " {}={value:?}", field.name());
        }
    }

    fn record_str(&mut self, field: &Field, value: &str) {
        if field.name() == "message" {
            self.msg.push_str(value);
        } else {
            let _ = write!(self.msg, " {}={value}", field.name());
        }
    }
}

impl<S: Subscriber> Layer<S> for ForwardLayer {
    fn on_event(&self, event: &Event<'_>, _ctx: Context<'_, S>) {
        let level = match *event.metadata().level() {
            tracing::Level::ERROR => "error",
            tracing::Level::WARN => "warn",
            tracing::Level::INFO => "info",
            _ => "debug",
        };
        let mut v = MessageVisitor::default();
        event.record(&mut v);
        send(
            &self.out,
            &Reply::Log {
                level: level.into(),
                msg: v.msg,
            },
        );
    }
}

/// The helper's stdout, shared by replies and forwarded log lines.
fn stdout_writer() -> SharedWriter {
    static OUT: OnceLock<SharedWriter> = OnceLock::new();
    OUT.get_or_init(|| Arc::new(Mutex::new(Box::new(io::stdout()) as Box<dyn Write + Send>)))
        .clone()
}

/// Entry point of the helper process: `args` are the arguments after `--rgb-helper`
/// (`<kind> [--locale=<tag>]`). Returns the process exit code.
pub fn run(args: &[String]) -> i32 {
    let Some(kind) = args.first().and_then(|a| BackendKind::from_helper_arg(a)) else {
        eprintln!("rgb helper: expected one of chroma-native, corsair, asus-aura");
        return 2;
    };
    if let Some(tag) = args.iter().find_map(|a| a.strip_prefix("--locale=")) {
        mmcl_i18n::init(mmcl_i18n::Locale::parse(tag));
    }
    let out = stdout_writer();
    {
        use tracing_subscriber::layer::SubscriberExt;
        use tracing_subscriber::util::SubscriberInitExt;
        let _ = tracing_subscriber::registry()
            .with(ForwardLayer { out: out.clone() })
            .try_init();
    }
    let backend = crate::backends::make_in_process(kind);
    let stdin = io::stdin();
    serve(backend, stdin.lock(), out);
    0
}
