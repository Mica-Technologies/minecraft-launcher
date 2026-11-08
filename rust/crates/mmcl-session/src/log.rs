// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! A game's output, captured for its whole life (Java `GameLog`).
//!
//! Both pipes are drained from the moment the game starts until it exits, whether or not any
//! window is looking: an unread pipe fills within moments and the game stalls on its next log
//! line. Every line is redacted before it is stored, shown or written. The in-memory buffer is
//! bounded (cut back to `retain` bytes on a line boundary once it passes `trigger`); the session
//! log file keeps everything. Lines reach listeners in ordered batches every
//! [`FLUSH_INTERVAL`].
//!
//! Every line gets a number ([`line_count`](GameLog::line_count) counts them all, including
//! those the bounded buffer has dropped), so a poller can tell new output from old even once
//! the buffer has stopped growing (the Java TUI compared snapshot sizes and froze once its
//! 4000-line ring was full). See [`lines_since`](GameLog::lines_since).

use std::collections::VecDeque;
use std::fs::File;
use std::io::{BufWriter, Read, Write};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::{Arc, Weak};
use std::time::{Duration, Instant};

use parking_lot::{Condvar, Mutex};
use tokio::io::{AsyncRead, AsyncReadExt};

use crate::error::SessionError;
use crate::listeners::{ListenerId, run_isolated};
use crate::trim::{full_log_drop_offset, tail_start};

/// How often queued lines are delivered to listeners and the file is flushed.
pub const FLUSH_INTERVAL: Duration = Duration::from_millis(150);
/// The buffer is cut once it passes this many bytes...
pub const TRIGGER_BYTES: usize = 5_000_000;
/// ...back to about this many.
pub const RETAIN_BYTES: usize = 4_000_000;
/// What an ended log keeps in memory when its file is complete.
pub const CLOSED_TAIL_BYTES: usize = 256 * 1024;
/// A line longer than this is split, so a game that never prints a newline cannot grow memory
/// without bound.
pub const MAX_LINE_BYTES: usize = 1024 * 1024;

const READ_CHUNK: usize = 16 * 1024;

/// Hears a game's output.
pub trait LogListener: Send + Sync {
    /// A batch of new lines (redacted, without terminators), in order.
    fn on_lines(&self, lines: &[String]);
    /// The game's output has ended (both pipes closed). Called once.
    fn on_closed(&self) {}
}

struct FnListener<F, C>(F, C);

impl<F, C> LogListener for FnListener<F, C>
where
    F: Fn(&[String]) + Send + Sync,
    C: Fn() + Send + Sync,
{
    fn on_lines(&self, lines: &[String]) {
        (self.0)(lines)
    }
    fn on_closed(&self) {
        (self.1)()
    }
}

/// A listener from two closures.
pub fn log_listener(
    on_lines: impl Fn(&[String]) + Send + Sync + 'static,
    on_closed: impl Fn() + Send + Sync + 'static,
) -> Arc<dyn LogListener> {
    Arc::new(FnListener(on_lines, on_closed))
}

/// What [`GameLog::subscribe`] hands back: the text so far and a way to stop listening.
/// Dropping it does **not** unsubscribe; call [`cancel`](Self::cancel).
pub struct Subscription {
    /// The buffered text at the moment of subscribing (each line ends in `'\n'`).
    pub snapshot: String,
    /// Whether the snapshot was cut to the requested number of lines.
    pub clipped: bool,
    /// The number of the first line the listener will receive.
    pub next_line: u64,
    id: ListenerId,
    log: Weak<GameLog>,
}

impl Subscription {
    /// Stops delivery to the listener.
    pub fn cancel(self) {
        if let Some(log) = self.log.upgrade() {
            log.remove_listener(self.id);
        }
    }

    pub fn id(&self) -> ListenerId {
        self.id
    }
}

/// The last lines of the buffer.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Tail {
    pub text: String,
    /// Whether older buffered lines were left out.
    pub clipped: bool,
}

/// What [`GameLog::lines_since`] returns.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct LinesSince {
    /// The lines numbered `from..next` that are still available (oldest first).
    pub lines: Vec<String>,
    /// The number to ask from next time ([`GameLog::line_count`] at the time of the call).
    pub next: u64,
    /// Lines between `from` and the first returned one that are no longer in memory (the
    /// buffer dropped them, or `max_lines` cut them).
    pub missed: u64,
}

struct Buffer {
    text: String,
    truncated: bool,
    line_count: u64,
    pending: VecDeque<(u64, String)>,
}

#[derive(Clone)]
enum Entry {
    Plain(Arc<dyn LogListener>),
    /// Delivers only lines numbered `>= start` (a subscriber already has the earlier ones in
    /// its snapshot).
    FromIndex(u64, Arc<dyn LogListener>),
}

impl Entry {
    fn target(&self) -> &Arc<dyn LogListener> {
        match self {
            Entry::Plain(l) | Entry::FromIndex(_, l) => l,
        }
    }
}

/// A game's captured output. Shared as `Arc<GameLog>`.
pub struct GameLog {
    file: Option<PathBuf>,
    trigger: usize,
    retain: usize,
    closed_tail: usize,
    buffer: Mutex<Buffer>,
    listeners: Mutex<Arc<Vec<(ListenerId, Entry)>>>,
    writer: Mutex<Option<BufWriter<File>>>,
    file_complete: AtomicBool,
    attached: AtomicBool,
    open_readers: AtomicUsize,
    wake: (Mutex<()>, Condvar),
    closed: (Mutex<bool>, Condvar),
}

impl GameLog {
    /// A log writing to `file` (none: memory only) with the default limits.
    pub fn new(file: Option<PathBuf>) -> Arc<Self> {
        Self::with_limits(file, TRIGGER_BYTES, RETAIN_BYTES, CLOSED_TAIL_BYTES)
    }

    /// A log with explicit buffer limits (tests).
    pub fn with_limits(
        file: Option<PathBuf>,
        trigger: usize,
        retain: usize,
        closed_tail: usize,
    ) -> Arc<Self> {
        Arc::new(GameLog {
            file,
            trigger,
            retain,
            closed_tail,
            buffer: Mutex::new(Buffer {
                text: String::new(),
                truncated: false,
                line_count: 0,
                pending: VecDeque::new(),
            }),
            listeners: Mutex::new(Arc::new(Vec::new())),
            writer: Mutex::new(None),
            file_complete: AtomicBool::new(false),
            attached: AtomicBool::new(false),
            open_readers: AtomicUsize::new(0),
            wake: (Mutex::new(()), Condvar::new()),
            closed: (Mutex::new(false), Condvar::new()),
        })
    }

    /// `dir/game-<pack name, unsafe chars as '_'>-<timestamp>.log`.
    pub fn file_for(dir: &Path, pack_name: Option<&str>, timestamp: &str) -> PathBuf {
        let safe: String = pack_name
            .unwrap_or("game")
            .chars()
            .map(|c| {
                if c.is_ascii_alphanumeric() || matches!(c, '.' | '_' | '-') {
                    c
                } else {
                    '_'
                }
            })
            .collect();
        dir.join(format!("game-{safe}-{timestamp}.log"))
    }

    /// The timestamp [`file_for`](Self::file_for) is usually given (`2026-10-03--10-00-00`,
    /// local time).
    pub fn timestamp_now() -> String {
        chrono::Local::now()
            .format("%Y-%m-%d--%H-%M-%S")
            .to_string()
    }

    /// Starts draining `stdout` and `stderr` on two threads (plus a delivery thread). Returns
    /// at once. Only the first attach of a log takes effect.
    pub fn attach(
        self: &Arc<Self>,
        stdout: impl Read + Send + 'static,
        stderr: impl Read + Send + 'static,
    ) {
        if !self.begin_attach() {
            return;
        }
        let out = Arc::clone(self);
        let err = Arc::clone(self);
        self.spawn_thread("game-log-out", move || out.read_blocking(stdout));
        self.spawn_thread("game-log-err", move || err.read_blocking(stderr));
        self.start_flush_thread();
    }

    /// Starts draining two async streams on `runtime` (plus a delivery thread).
    pub fn attach_async(
        self: &Arc<Self>,
        stdout: impl AsyncRead + Unpin + Send + 'static,
        stderr: impl AsyncRead + Unpin + Send + 'static,
        runtime: &tokio::runtime::Handle,
    ) {
        if !self.begin_attach() {
            return;
        }
        let out = Arc::clone(self);
        let err = Arc::clone(self);
        runtime.spawn(async move { out.read_async(stdout).await });
        runtime.spawn(async move { err.read_async(stderr).await });
        self.start_flush_thread();
    }

    /// Takes the piped stdout and stderr of `child` and drains them on the current tokio
    /// runtime. A pipe that is not there (not piped, or already taken) counts as empty.
    pub fn attach_child(
        self: &Arc<Self>,
        child: &mut tokio::process::Child,
    ) -> Result<(), SessionError> {
        let runtime =
            tokio::runtime::Handle::try_current().map_err(|_| SessionError::NoAsyncRuntime)?;
        let out = child.stdout.take();
        let err = child.stderr.take();
        self.attach_async(OptionalReader(out), OptionalReader(err), &runtime);
        Ok(())
    }

    /// The session log file, if any.
    pub fn file(&self) -> Option<&Path> {
        self.file.as_deref()
    }

    /// The buffered text (each line ends in `'\n'`).
    pub fn text(&self) -> String {
        self.buffer.lock().text.clone()
    }

    /// Whether the buffer no longer holds the first lines.
    pub fn is_truncated(&self) -> bool {
        self.buffer.lock().truncated
    }

    /// How many lines have been captured so far (never decreases).
    pub fn line_count(&self) -> u64 {
        self.buffer.lock().line_count
    }

    /// Whether the file holds every line (it was opened and no write failed).
    pub fn is_file_complete(&self) -> bool {
        self.file_complete.load(Ordering::SeqCst)
    }

    /// The whole log: the buffer, or once it was cut, the session file (closed and complete)
    /// capped like the live buffer. Reads the file, so call it off the UI thread.
    pub fn full_text(&self) -> String {
        {
            let b = self.buffer.lock();
            if !b.truncated {
                return b.text.clone();
            }
        }
        if let Some(file) = &self.file
            && self.is_closed()
            && self.is_file_complete()
            && let Ok(bytes) = std::fs::read(file)
        {
            let all = String::from_utf8_lossy(&bytes);
            let drop = full_log_drop_offset(&all, self.trigger, self.retain);
            return all[drop..].to_string();
        }
        self.text()
    }

    /// Whether both pipes have closed and the last lines were delivered.
    pub fn is_closed(&self) -> bool {
        *self.closed.0.lock()
    }

    /// Waits up to `timeout` for [`is_closed`](Self::is_closed).
    pub fn await_closed(&self, timeout: Duration) -> bool {
        let deadline = Instant::now() + timeout;
        let mut closed = self.closed.0.lock();
        while !*closed {
            if self.closed.1.wait_until(&mut closed, deadline).timed_out() {
                return *closed;
            }
        }
        true
    }

    /// Adds a listener that hears every line delivered from now on (no snapshot).
    pub fn add_listener(&self, listener: Arc<dyn LogListener>) -> ListenerId {
        let id = ListenerId::next();
        self.push_listener(id, Entry::Plain(listener));
        id
    }

    pub fn remove_listener(&self, id: ListenerId) -> bool {
        let mut list = self.listeners.lock();
        if !list.iter().any(|(i, _)| *i == id) {
            return false;
        }
        let next: Vec<_> = list.iter().filter(|(i, _)| *i != id).cloned().collect();
        *list = Arc::new(next);
        true
    }

    /// The buffered text (its last `max_lines` lines; 0 = all) plus every later line, with
    /// nothing missed and nothing repeated. If the log has already closed, the listener's
    /// `on_closed` runs before this returns.
    pub fn subscribe(
        self: &Arc<Self>,
        listener: Arc<dyn LogListener>,
        max_lines: usize,
    ) -> Subscription {
        let id = ListenerId::next();
        let (snapshot, drop, next_line, already_closed) = {
            let b = self.buffer.lock();
            let drop = tail_start(&b.text, max_lines);
            // Registered under the buffer lock, so the numbering and the snapshot agree, and
            // under the `closed` lock, so `on_closed` arrives exactly once.
            let closed = self.closed.0.lock();
            self.push_listener(id, Entry::FromIndex(b.line_count, Arc::clone(&listener)));
            (b.text[drop..].to_string(), drop, b.line_count, *closed)
        };
        if already_closed {
            run_isolated(|| listener.on_closed());
        }
        Subscription {
            snapshot,
            clipped: drop > 0,
            next_line,
            id,
            log: Arc::downgrade(self),
        }
    }

    /// The last `max_lines` buffered lines (0 = all).
    pub fn tail(&self, max_lines: usize) -> Tail {
        let b = self.buffer.lock();
        let drop = tail_start(&b.text, max_lines);
        Tail {
            text: b.text[drop..].to_string(),
            clipped: drop > 0,
        }
    }

    /// The lines numbered `from` and later that are still buffered, at most the newest
    /// `max_lines` of them (0 = no cap). For pollers (the TUI): pass `next` back each time.
    pub fn lines_since(&self, from: u64, max_lines: usize) -> LinesSince {
        let b = self.buffer.lock();
        let next = b.line_count;
        let wanted = next.saturating_sub(from);
        if wanted == 0 {
            return LinesSince {
                lines: Vec::new(),
                next,
                missed: 0,
            };
        }
        let cap = if max_lines == 0 {
            wanted
        } else {
            wanted.min(max_lines as u64)
        };
        // The buffer ends with line `next - 1`; walk back over at most `cap` lines.
        let take = usize::try_from(cap).unwrap_or(usize::MAX);
        let start = tail_start(&b.text, take);
        let mut lines: Vec<String> = b.text[start..].lines().map(str::to_string).collect();
        if lines.len() > take {
            let extra = lines.len() - take;
            lines.drain(..extra);
        }
        let missed = wanted - lines.len() as u64;
        LinesSince {
            lines,
            next,
            missed,
        }
    }

    // ---------------------------------------------------------------------------------------
    //  Capture
    // ---------------------------------------------------------------------------------------

    fn begin_attach(&self) -> bool {
        if self.attached.swap(true, Ordering::SeqCst) {
            return false;
        }
        self.open_writer();
        self.open_readers.store(2, Ordering::SeqCst);
        true
    }

    fn spawn_thread(self: &Arc<Self>, name: &str, body: impl FnOnce() + Send + 'static) {
        if let Err(e) = std::thread::Builder::new()
            .name(name.to_string())
            .spawn(body)
        {
            // Without the reader the pipe is never drained; nothing more we can do than say so
            // and count the reader as finished, so the log still closes.
            tracing::error!(
                "{}",
                mmcl_i18n::tf!("log.session.logThreadFailed", name, e.to_string())
            );
            self.reader_done();
        }
    }

    fn start_flush_thread(self: &Arc<Self>) {
        let me = Arc::clone(self);
        let spawned = std::thread::Builder::new()
            .name("game-log-flush".to_string())
            .spawn(move || me.flush_loop());
        if let Err(e) = spawned {
            tracing::error!(
                "{}",
                mmcl_i18n::tf!(
                    "log.session.logThreadFailed",
                    "game-log-flush",
                    e.to_string()
                )
            );
        }
    }

    fn open_writer(&self) {
        let Some(file) = &self.file else {
            return;
        };
        let opened = (|| -> std::io::Result<File> {
            if let Some(parent) = file.parent() {
                std::fs::create_dir_all(parent)?;
            }
            File::create(file)
        })();
        match opened {
            Ok(f) => {
                *self.writer.lock() = Some(BufWriter::new(f));
                self.file_complete.store(true, Ordering::SeqCst);
                // Game logs can carry user names and mod debug output; owner-only, best-effort.
                let _ = mmcl_core::fsx::owner_only(file, false);
            }
            Err(e) => {
                tracing::warn!(
                    "{}",
                    mmcl_i18n::tf!("log.session.logOpenFailed", file, e.to_string())
                );
            }
        }
    }

    fn read_blocking(&self, mut stream: impl Read) {
        let mut splitter = LineSplitter::default();
        let mut chunk = vec![0u8; READ_CHUNK];
        loop {
            match stream.read(&mut chunk) {
                Ok(0) => break,
                Ok(n) => splitter.feed(&chunk[..n], |line| self.capture(line)),
                Err(e) if e.kind() == std::io::ErrorKind::Interrupted => continue,
                // The stream closes (or breaks) when the game exits.
                Err(_) => break,
            }
        }
        splitter.finish(|line| self.capture(line));
        self.reader_done();
    }

    async fn read_async(&self, mut stream: impl AsyncRead + Unpin) {
        let mut splitter = LineSplitter::default();
        let mut chunk = vec![0u8; READ_CHUNK];
        loop {
            match stream.read(&mut chunk).await {
                Ok(0) => break,
                Ok(n) => splitter.feed(&chunk[..n], |line| self.capture(line)),
                Err(e) if e.kind() == std::io::ErrorKind::Interrupted => continue,
                Err(_) => break,
            }
        }
        splitter.finish(|line| self.capture(line));
        self.reader_done();
    }

    fn reader_done(&self) {
        self.open_readers.fetch_sub(1, Ordering::SeqCst);
        let _g = self.wake.0.lock();
        self.wake.1.notify_all();
    }

    fn capture(&self, raw: &[u8]) {
        let decoded = String::from_utf8_lossy(raw);
        let safe = mmcl_log::redact(&decoded).into_owned();
        {
            let mut b = self.buffer.lock();
            b.text.push_str(&safe);
            b.text.push('\n');
            let drop = full_log_drop_offset(&b.text, self.trigger, self.retain);
            if drop > 0 {
                b.text.drain(..drop);
                b.truncated = true;
            }
            // Numbered under the same lock as the append, so a subscriber's snapshot and the
            // numbering agree on exactly which lines it already has.
            let index = b.line_count;
            b.line_count += 1;
            b.pending.push_back((index, safe.clone()));
        }
        self.write(&safe);
    }

    fn write(&self, line: &str) {
        let mut w = self.writer.lock();
        if let Some(writer) = w.as_mut()
            && (writer.write_all(line.as_bytes()).is_err()
                || writer.write_all(mmcl_log::LINE_SEP.as_bytes()).is_err())
        {
            // A failed write must never back up into the game's pipe. The file now has a hole,
            // so the buffer must keep its text: stop writing and stop trusting the file.
            self.writer_failed(&mut w);
        }
    }

    fn writer_failed(&self, w: &mut Option<BufWriter<File>>) {
        if self.file_complete.swap(false, Ordering::SeqCst)
            && let Some(file) = &self.file
        {
            tracing::warn!("{}", mmcl_i18n::tf!("log.session.logWriteFailed", file));
        }
        *w = None;
    }

    fn flush_loop(&self) {
        while self.open_readers.load(Ordering::SeqCst) > 0 {
            {
                let mut g = self.wake.0.lock();
                if self.open_readers.load(Ordering::SeqCst) > 0 {
                    self.wake.1.wait_for(&mut g, FLUSH_INTERVAL);
                }
            }
            self.deliver();
            let mut w = self.writer.lock();
            if let Some(writer) = w.as_mut()
                && writer.flush().is_err()
            {
                self.writer_failed(&mut w);
            }
        }
        self.deliver();
        {
            let mut w = self.writer.lock();
            if let Some(mut writer) = w.take()
                && writer.flush().is_err()
            {
                self.writer_failed(&mut w);
            }
        }
        self.trim_to_tail();
        // Closing and taking the listener snapshot is one step under the `closed` lock, and
        // `subscribe` registers under that lock too: each subscriber hears `on_closed` once,
        // either from here or from `subscribe` itself.
        let listeners = {
            let mut closed = self.closed.0.lock();
            *closed = true;
            self.closed.1.notify_all();
            self.listeners.lock().clone()
        };
        for (_, entry) in listeners.iter() {
            run_isolated(|| entry.target().on_closed());
        }
    }

    /// An ended log with a complete file keeps only its tail in memory (the registry keeps up
    /// to 20 ended sessions; millions of bytes each added up). Without a complete file the
    /// buffer is all there is, so it stays.
    fn trim_to_tail(&self) {
        if !self.is_file_complete() {
            return;
        }
        let mut b = self.buffer.lock();
        let drop = full_log_drop_offset(&b.text, self.closed_tail, self.closed_tail);
        if drop > 0 {
            b.text.drain(..drop);
            b.text.shrink_to_fit();
            b.truncated = true;
        }
    }

    fn deliver(&self) {
        let batch: Vec<(u64, String)> = {
            let mut b = self.buffer.lock();
            b.pending.drain(..).collect()
        };
        if batch.is_empty() {
            return;
        }
        let mut all: Option<Vec<String>> = None;
        let listeners = self.listeners.lock().clone();
        for (_, entry) in listeners.iter() {
            match entry {
                Entry::Plain(l) => {
                    let lines =
                        all.get_or_insert_with(|| batch.iter().map(|(_, s)| s.clone()).collect());
                    run_isolated(|| l.on_lines(lines));
                }
                Entry::FromIndex(start, l) => {
                    let fresh: Vec<String> = batch
                        .iter()
                        .filter(|(i, _)| i >= start)
                        .map(|(_, s)| s.clone())
                        .collect();
                    if !fresh.is_empty() {
                        run_isolated(|| l.on_lines(&fresh));
                    }
                }
            }
        }
    }

    fn push_listener(&self, id: ListenerId, entry: Entry) {
        let mut list = self.listeners.lock();
        let mut next = Vec::clone(&list);
        next.push((id, entry));
        *list = Arc::new(next);
    }
}

/// An `AsyncRead` that reads as empty when there is no stream.
struct OptionalReader<R>(Option<R>);

impl<R: AsyncRead + Unpin> AsyncRead for OptionalReader<R> {
    fn poll_read(
        mut self: std::pin::Pin<&mut Self>,
        cx: &mut std::task::Context<'_>,
        buf: &mut tokio::io::ReadBuf<'_>,
    ) -> std::task::Poll<std::io::Result<()>> {
        match self.0.as_mut() {
            Some(r) => std::pin::Pin::new(r).poll_read(cx, buf),
            None => std::task::Poll::Ready(Ok(())),
        }
    }
}

/// Splits a byte stream into lines like Java's `BufferedReader.readLine`: on `\n`, `\r\n` or a
/// lone `\r`, terminators stripped, a final unterminated line kept. Lines longer than
/// [`MAX_LINE_BYTES`] are split on a UTF-8 boundary.
#[derive(Default)]
pub(crate) struct LineSplitter {
    partial: Vec<u8>,
    /// The previous chunk ended in `\r`: a leading `\n` belongs to it.
    after_cr: bool,
}

impl LineSplitter {
    pub(crate) fn feed(&mut self, mut bytes: &[u8], mut emit: impl FnMut(&[u8])) {
        if self.after_cr {
            self.after_cr = false;
            if let Some(rest) = bytes.strip_prefix(b"\n") {
                bytes = rest;
            }
        }
        while let Some(pos) = bytes.iter().position(|b| *b == b'\n' || *b == b'\r') {
            self.partial.extend_from_slice(&bytes[..pos]);
            self.emit_partial(&mut emit);
            let cr = bytes[pos] == b'\r';
            bytes = &bytes[pos + 1..];
            if cr {
                match bytes.first() {
                    Some(b'\n') => bytes = &bytes[1..],
                    Some(_) => {}
                    None => self.after_cr = true,
                }
            }
        }
        self.partial.extend_from_slice(bytes);
        while self.partial.len() > MAX_LINE_BYTES {
            let mut cut = MAX_LINE_BYTES;
            // Back up to the start of a UTF-8 sequence (at most 3 continuation bytes).
            while cut > MAX_LINE_BYTES - 3 && (self.partial[cut] & 0xC0) == 0x80 {
                cut -= 1;
            }
            let head: Vec<u8> = self.partial.drain(..cut).collect();
            emit(&head);
        }
    }

    pub(crate) fn finish(&mut self, mut emit: impl FnMut(&[u8])) {
        if !self.partial.is_empty() {
            self.emit_partial(&mut emit);
        }
    }

    fn emit_partial(&mut self, emit: &mut impl FnMut(&[u8])) {
        emit(&self.partial);
        self.partial.clear();
    }
}

#[cfg(test)]
mod tests;
