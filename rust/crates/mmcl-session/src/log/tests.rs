// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

use super::*;
use std::io::Cursor;
use std::sync::mpsc;

const WAIT: Duration = Duration::from_secs(5);

fn lines(lines: &[&str]) -> Cursor<Vec<u8>> {
    let text = if lines.is_empty() {
        String::new()
    } else {
        lines.join("\n") + "\n"
    };
    Cursor::new(text.into_bytes())
}

fn numbered(count: usize) -> Cursor<Vec<u8>> {
    let mut text = String::new();
    for i in 0..count {
        text.push_str(&format!("line number {i}\n"));
    }
    Cursor::new(text.into_bytes())
}

/// The game's end of an in-memory pipe.
struct GameOut(mpsc::Sender<Vec<u8>>);

impl GameOut {
    fn write(&self, s: &str) {
        self.0.send(s.as_bytes().to_vec()).unwrap();
    }
}

/// The launcher's end: reads what was sent, EOF once the sender is dropped.
struct PipeIn {
    rx: mpsc::Receiver<Vec<u8>>,
    left: Vec<u8>,
}

impl Read for PipeIn {
    fn read(&mut self, buf: &mut [u8]) -> std::io::Result<usize> {
        if self.left.is_empty() {
            match self.rx.recv() {
                Ok(chunk) => self.left = chunk,
                Err(_) => return Ok(0),
            }
        }
        let n = buf.len().min(self.left.len());
        buf[..n].copy_from_slice(&self.left[..n]);
        self.left.drain(..n);
        Ok(n)
    }
}

fn pipe() -> (GameOut, PipeIn) {
    let (tx, rx) = mpsc::channel();
    (
        GameOut(tx),
        PipeIn {
            rx,
            left: Vec::new(),
        },
    )
}

type Seen = Arc<Mutex<Vec<String>>>;

fn collector() -> (Arc<dyn LogListener>, Seen, mpsc::Receiver<()>) {
    let seen: Arc<Mutex<Vec<String>>> = Arc::default();
    let (tx, rx) = mpsc::channel();
    let tx = Mutex::new(tx);
    let s = seen.clone();
    let l = log_listener(
        move |batch| s.lock().extend_from_slice(batch),
        move || {
            let _ = tx.lock().send(());
        },
    );
    (l, seen, rx)
}

fn wait_for(cond: impl Fn() -> bool) {
    let deadline = Instant::now() + WAIT;
    while !cond() && Instant::now() < deadline {
        std::thread::sleep(Duration::from_millis(5));
    }
}

#[test]
fn captures_both_streams_into_memory_and_the_file() {
    let dir = tempfile::tempdir().unwrap();
    let file = dir.path().join("logs").join("game.log");
    let log = GameLog::new(Some(file.clone()));
    log.attach(lines(&["out one", "out two"]), lines(&["err one"]));
    assert!(log.await_closed(WAIT));
    let text = log.text();
    assert!(text.contains("out one\n") && text.contains("out two\n") && text.contains("err one\n"));
    let written = std::fs::read_to_string(&file).unwrap();
    let written: Vec<&str> = written.lines().collect();
    assert_eq!(written.len(), 3);
    for l in ["out one", "out two", "err one"] {
        assert!(written.contains(&l));
    }
    assert_eq!(log.line_count(), 3);
    assert!(log.is_file_complete());
}

#[test]
fn tokens_are_redacted_before_they_are_stored_or_shown() {
    let dir = tempfile::tempdir().unwrap();
    let file = dir.path().join("game.log");
    let log = GameLog::new(Some(file.clone()));
    let token = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ0ZXN0In0.c2lnbmF0dXJlLXZhbHVl";
    let (l, seen, _closed) = collector();
    log.add_listener(l);
    log.attach(
        lines(&[&format!(
            "launching with --accessToken {token} --version 1.20.1"
        )]),
        lines(&[]),
    );
    assert!(log.await_closed(WAIT));
    assert!(!log.text().contains(token));
    assert!(log.text().contains("[REDACTED]"));
    assert!(!std::fs::read_to_string(&file).unwrap().contains(token));
    assert!(seen.lock().iter().all(|l| !l.contains(token)));
}

#[test]
fn listeners_get_batches_and_hear_the_end() {
    let log = GameLog::new(None);
    let (l, seen, closed) = collector();
    log.add_listener(l);
    log.attach(lines(&["a", "b", "c"]), lines(&[]));
    closed.recv_timeout(WAIT).unwrap();
    assert_eq!(*seen.lock(), vec!["a", "b", "c"]);
    assert!(closed.try_recv().is_err(), "on_closed runs once");
}

#[test]
fn a_late_subscriber_misses_nothing_and_sees_nothing_twice() {
    let (game, out) = pipe();
    let log = GameLog::new(None);
    log.attach(out, lines(&[]));
    for i in 0..200 {
        game.write(&format!("early {i}\n"));
    }
    std::thread::sleep(Duration::from_millis(50));

    let (l, later, closed) = collector();
    let sub = log.subscribe(l, 0);
    for i in 0..200 {
        game.write(&format!("late {i}\n"));
    }
    drop(game);
    assert!(log.await_closed(WAIT));
    closed.recv_timeout(WAIT).unwrap();
    let mut combined = sub.snapshot.clone();
    for l in later.lock().iter() {
        combined.push_str(l);
        combined.push('\n');
    }
    assert_eq!(log.text(), combined);
    assert!(!sub.clipped);
}

#[test]
fn a_capped_subscriber_gets_only_the_tail_then_every_later_line() {
    let (game, out) = pipe();
    let log = GameLog::new(None);
    log.attach(out, lines(&[]));
    for i in 0..10 {
        game.write(&format!("early {i}\n"));
    }
    wait_for(|| log.text().contains("early 9\n"));

    let (l, later, _closed) = collector();
    let sub = log.subscribe(l, 3);
    assert_eq!(sub.snapshot, "early 7\nearly 8\nearly 9\n");
    assert!(sub.clipped);
    assert_eq!(sub.next_line, 10);
    assert_eq!(
        log.tail(2),
        Tail {
            text: "early 8\nearly 9\n".into(),
            clipped: true
        }
    );
    assert!(!log.tail(50).clipped);

    game.write("late\n");
    drop(game);
    assert!(log.await_closed(WAIT));
    assert_eq!(*later.lock(), vec!["late"]);
}

#[test]
fn a_cancelled_subscription_hears_nothing_more() {
    let (game, out) = pipe();
    let log = GameLog::new(None);
    log.attach(out, lines(&[]));
    let (l, seen, _closed) = collector();
    let sub = log.subscribe(l, 0);
    game.write("one\n");
    wait_for(|| !seen.lock().is_empty());
    sub.cancel();
    game.write("two\n");
    drop(game);
    assert!(log.await_closed(WAIT));
    assert_eq!(*seen.lock(), vec!["one"]);
}

#[test]
fn subscribing_to_a_closed_log_hears_the_end_at_once() {
    let log = GameLog::new(None);
    log.attach(lines(&["x"]), lines(&[]));
    assert!(log.await_closed(WAIT));
    let (l, _seen, closed) = collector();
    let sub = log.subscribe(l, 0);
    assert_eq!(sub.snapshot, "x\n");
    closed.try_recv().unwrap();
    assert!(closed.try_recv().is_err());
}

#[test]
fn the_in_memory_buffer_is_bounded() {
    let log = GameLog::with_limits(None, 1_000, 500, CLOSED_TAIL_BYTES);
    log.attach(numbered(300), lines(&[]));
    assert!(log.await_closed(WAIT));
    assert!(log.text().len() <= 1_000);
    assert!(log.text().ends_with("line number 299\n"));
    assert!(log.is_truncated());
}

#[test]
fn line_numbers_keep_counting_once_the_buffer_is_full() {
    // The Java TUI compared snapshot sizes, which stop changing once the buffer is full, so
    // its view froze. The line count keeps moving.
    let (game, out) = pipe();
    let log = GameLog::with_limits(None, 200, 100, CLOSED_TAIL_BYTES);
    log.attach(out, lines(&[]));
    for i in 0..100 {
        game.write(&format!("line {i:03}\n"));
    }
    wait_for(|| log.line_count() == 100);
    let first = log.lines_since(0, 0);
    assert_eq!(first.next, 100);
    assert!(
        first.missed > 0,
        "the bounded buffer dropped the oldest lines"
    );
    assert_eq!(first.lines.last().map(String::as_str), Some("line 099"));
    let size_before = log.text().len();

    game.write("line 100\nline 101\n");
    wait_for(|| log.line_count() == 102);
    assert!(log.text().len() <= size_before + 18);
    let next = log.lines_since(first.next, 0);
    assert_eq!(next.lines, vec!["line 100", "line 101"]);
    assert_eq!(next.missed, 0);
    assert_eq!(next.next, 102);
    assert!(log.lines_since(next.next, 0).lines.is_empty());

    let capped = log.lines_since(0, 3);
    assert_eq!(capped.lines, vec!["line 099", "line 100", "line 101"]);
    assert_eq!(capped.missed, 99);
    drop(game);
    assert!(log.await_closed(WAIT));
}

#[test]
fn an_ended_log_keeps_only_its_tail_but_serves_the_full_text_from_the_file() {
    let dir = tempfile::tempdir().unwrap();
    let file = dir.path().join("game.log");
    let log = GameLog::with_limits(Some(file.clone()), 1_000_000, 900_000, 200);
    log.attach(numbered(300), lines(&[]));
    assert!(log.await_closed(WAIT));

    assert!(log.is_truncated());
    assert!(log.text().len() <= 200);
    assert!(log.text().starts_with("line number "));
    assert!(log.text().ends_with("line number 299\n"));
    let full = log.full_text();
    assert!(full.starts_with(&format!("line number 0{}", mmcl_log::LINE_SEP)));
    assert_eq!(std::fs::read_to_string(&file).unwrap(), full);
}

#[test]
fn full_text_of_a_very_long_session_is_capped_like_the_live_buffer() {
    let dir = tempfile::tempdir().unwrap();
    let file = dir.path().join("game.log");
    let log = GameLog::with_limits(Some(file), 1_000, 500, 100);
    log.attach(numbered(300), lines(&[]));
    assert!(log.await_closed(WAIT));
    let full = log.full_text();
    assert!(full.len() <= 1_000 && full.len() > 100);
    assert!(full.starts_with("line number "));
    assert!(full.ends_with(&format!("line number 299{}", mmcl_log::LINE_SEP)));
}

#[test]
fn without_a_file_an_ended_log_keeps_everything() {
    let log = GameLog::with_limits(None, 1_000_000, 900_000, 200);
    log.attach(numbered(300), lines(&[]));
    assert!(log.await_closed(WAIT));
    assert!(!log.is_truncated());
    assert!(log.text().starts_with("line number 0\n"));
    assert_eq!(log.text(), log.full_text());
}

#[test]
fn an_unwritable_file_keeps_the_whole_ended_log_in_memory() {
    let dir = tempfile::tempdir().unwrap();
    let blocker = dir.path().join("not-a-dir");
    std::fs::write(&blocker, "x").unwrap();
    let log = GameLog::with_limits(Some(blocker.join("game.log")), 1_000_000, 900_000, 200);
    log.attach(numbered(300), lines(&[]));
    assert!(log.await_closed(WAIT));
    assert!(!log.is_truncated());
    assert!(!log.is_file_complete());
    assert!(log.full_text().starts_with("line number 0\n"));
}

#[test]
fn log_file_names_are_safe() {
    let dir = tempfile::tempdir().unwrap();
    let f = GameLog::file_for(
        dir.path(),
        Some("My Pack: Remastered/2"),
        "2026-10-03--10-00-00",
    );
    assert_eq!(
        f.file_name().unwrap().to_str().unwrap(),
        "game-My_Pack__Remastered_2-2026-10-03--10-00-00.log"
    );
    assert_eq!(f.parent().unwrap(), dir.path());
    let g = GameLog::file_for(dir.path(), None, "t");
    assert_eq!(g.file_name().unwrap().to_str().unwrap(), "game-game-t.log");
    assert_eq!(GameLog::timestamp_now().len(), "2026-10-03--10-00-00".len());
}

#[test]
fn an_unwritable_file_still_captures_in_memory() {
    let dir = tempfile::tempdir().unwrap();
    let blocker = dir.path().join("not-a-dir");
    std::fs::write(&blocker, "x").unwrap();
    let log = GameLog::new(Some(blocker.join("game.log")));
    log.attach(lines(&["still captured"]), lines(&[]));
    assert!(log.await_closed(WAIT));
    assert!(log.text().contains("still captured"));
}

#[test]
fn cr_lf_and_lone_cr_split_lines() {
    let log = GameLog::new(None);
    log.attach(
        Cursor::new(b"one\r\ntwo\rthree\n\nfour\rfive".to_vec()),
        lines(&[]),
    );
    assert!(log.await_closed(WAIT));
    assert_eq!(log.text(), "one\ntwo\nthree\n\nfour\nfive\n");
}

#[test]
fn a_cr_lf_split_across_reads_is_one_terminator() {
    let mut s = LineSplitter::default();
    let mut out: Vec<String> = Vec::new();
    s.feed(b"a\r", |l| out.push(String::from_utf8_lossy(l).into()));
    s.feed(b"\nb\r", |l| out.push(String::from_utf8_lossy(l).into()));
    s.feed(b"c", |l| out.push(String::from_utf8_lossy(l).into()));
    s.finish(|l| out.push(String::from_utf8_lossy(l).into()));
    assert_eq!(out, vec!["a", "b", "c"]);
}

#[test]
fn an_endless_line_is_split_on_a_char_boundary() {
    let mut s = LineSplitter::default();
    let mut out: Vec<Vec<u8>> = Vec::new();
    // One ASCII byte, then two-byte chars: the cut at MAX_LINE_BYTES lands inside one.
    let mut big = vec![b'x'];
    while big.len() < MAX_LINE_BYTES + 10 {
        big.extend_from_slice("é".as_bytes());
    }
    s.feed(&big, |l| out.push(l.to_vec()));
    s.finish(|l| out.push(l.to_vec()));
    assert_eq!(out.len(), 2);
    for part in &out {
        assert!(std::str::from_utf8(part).is_ok());
    }
    assert_eq!(out.iter().map(Vec::len).sum::<usize>(), big.len());
}

#[test]
fn invalid_utf8_is_replaced() {
    let log = GameLog::new(None);
    log.attach(Cursor::new(b"bad \xff byte\n".to_vec()), lines(&[]));
    assert!(log.await_closed(WAIT));
    assert_eq!(log.text(), "bad \u{FFFD} byte\n");
}

#[test]
fn a_panicking_listener_does_not_stop_the_others() {
    let log = GameLog::new(None);
    log.add_listener(log_listener(|_| panic!("listener failure"), || {}));
    let (l, seen, closed) = collector();
    log.add_listener(l);
    log.attach(lines(&["a"]), lines(&[]));
    closed.recv_timeout(WAIT).unwrap();
    assert_eq!(*seen.lock(), vec!["a"]);
}

#[test]
fn only_the_first_attach_counts() {
    let log = GameLog::new(None);
    log.attach(lines(&["first"]), lines(&[]));
    log.attach(lines(&["second"]), lines(&[]));
    assert!(log.await_closed(WAIT));
    assert_eq!(log.text(), "first\n");
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_child_process_is_drained_through_the_runtime() {
    let args: &[&str] = if cfg!(windows) {
        &["cmd", "/C", "echo out & echo err 1>&2"]
    } else {
        &["sh", "-c", "echo out; echo err 1>&2"]
    };
    let mut cmd = tokio::process::Command::new(args[0]);
    cmd.args(&args[1..])
        .stdin(std::process::Stdio::null())
        .stdout(std::process::Stdio::piped())
        .stderr(std::process::Stdio::piped());
    let mut child = cmd.spawn().unwrap();
    let log = GameLog::new(None);
    log.attach_child(&mut child).unwrap();
    let process = crate::ChildProcess::watch(child).unwrap();
    let l2 = log.clone();
    let closed = tokio::task::spawn_blocking(move || l2.await_closed(Duration::from_secs(20)))
        .await
        .unwrap();
    assert!(closed);
    let text = log.text();
    assert!(text.contains("out"), "{text:?}");
    assert!(text.contains("err"), "{text:?}");
    let (tx, rx) = tokio::sync::oneshot::channel();
    crate::ProcessHandle::on_exit(
        process.as_ref(),
        Box::new(move |c| {
            let _ = tx.send(c);
        }),
    );
    assert_eq!(
        tokio::time::timeout(Duration::from_secs(20), rx)
            .await
            .unwrap()
            .unwrap(),
        0
    );
}
