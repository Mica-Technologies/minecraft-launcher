// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! End-to-end tests of the download core and the bounded fetches against a loopback HTTP/1.1
//! server (no network). The HTTPS-only rule is relaxed through the internal `Scheme` seam.

use std::net::SocketAddr;
use std::path::Path;
use std::sync::Arc;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::time::Duration;

use parking_lot::Mutex;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpListener;

use crate::bounded::{Scheme, bounded_string_with, conditional_with, file_bounded_with};
use crate::cancel::CancelToken;
use crate::client::Clients;
use crate::download::{
    DownloadOpts, Tuning, download_file_with, download_string_with, path_lock, unique_temp_file,
};
use crate::error::DownloadError;
use crate::listeners::{DownloadScope, add_retry_listener};
use crate::source::Source;
use crate::tracker::DownloadTracker;
use crate::verify::ExpectedHash;

#[derive(Clone, Debug)]
struct Req {
    path: String,
    headers: Vec<(String, String)>,
}

impl Req {
    fn header(&self, name: &str) -> Option<&str> {
        self.headers
            .iter()
            .find(|(k, _)| k == name)
            .map(|(_, v)| v.as_str())
    }
}

#[derive(Clone, Default)]
struct Reply {
    status: u16,
    headers: Vec<(String, String)>,
    body: Vec<u8>,
    /// Declared length when it differs from the body (a truncated response).
    declared_len: Option<usize>,
    omit_len: bool,
    /// Send the body one byte at a time with this gap.
    trickle: Option<Duration>,
}

impl Reply {
    fn ok(body: &[u8]) -> Reply {
        Reply {
            status: 200,
            body: body.to_vec(),
            ..Default::default()
        }
    }

    fn status(status: u16) -> Reply {
        Reply {
            status,
            ..Default::default()
        }
    }

    fn header(mut self, k: &str, v: &str) -> Reply {
        self.headers.push((k.to_string(), v.to_string()));
        self
    }
}

type Handler = Arc<dyn Fn(usize, &Req) -> Reply + Send + Sync>;

struct Server {
    addr: SocketAddr,
    requests: Arc<Mutex<Vec<Req>>>,
}

impl Server {
    fn url(&self, path: &str) -> String {
        format!("http://{}{}", self.addr, path)
    }

    fn count(&self) -> usize {
        self.requests.lock().len()
    }
}

async fn serve(handler: impl Fn(usize, &Req) -> Reply + Send + Sync + 'static) -> Server {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    let requests: Arc<Mutex<Vec<Req>>> = Arc::default();
    let handler: Handler = Arc::new(handler);
    let seen = requests.clone();
    let n = Arc::new(AtomicUsize::new(0));
    tokio::spawn(async move {
        loop {
            let Ok((mut sock, _)) = listener.accept().await else {
                return;
            };
            let (handler, seen, n) = (handler.clone(), seen.clone(), n.clone());
            tokio::spawn(async move {
                let mut buf = Vec::new();
                let mut tmp = [0u8; 4096];
                while !buf.windows(4).any(|w| w == b"\r\n\r\n") {
                    match sock.read(&mut tmp).await {
                        Ok(0) | Err(_) => return,
                        Ok(k) => buf.extend_from_slice(&tmp[..k]),
                    }
                }
                let text = String::from_utf8_lossy(&buf).into_owned();
                let mut lines = text.split("\r\n");
                let path = lines
                    .next()
                    .unwrap_or("")
                    .split(' ')
                    .nth(1)
                    .unwrap_or("/")
                    .to_string();
                let headers = lines
                    .take_while(|l| !l.is_empty())
                    .filter_map(|l| l.split_once(':'))
                    .map(|(k, v)| (k.trim().to_ascii_lowercase(), v.trim().to_string()))
                    .collect();
                let req = Req { path, headers };
                let idx = n.fetch_add(1, Ordering::SeqCst);
                seen.lock().push(req.clone());
                let r = handler(idx, &req);
                let mut head = format!("HTTP/1.1 {} X\r\n", r.status);
                for (k, v) in &r.headers {
                    head.push_str(&format!("{k}: {v}\r\n"));
                }
                if !r.omit_len {
                    head.push_str(&format!(
                        "Content-Length: {}\r\n",
                        r.declared_len.unwrap_or(r.body.len())
                    ));
                }
                head.push_str("Connection: close\r\n\r\n");
                if sock.write_all(head.as_bytes()).await.is_err() {
                    return;
                }
                match r.trickle {
                    Some(gap) => {
                        for b in &r.body {
                            if sock.write_all(&[*b]).await.is_err() {
                                return;
                            }
                            let _ = sock.flush().await;
                            tokio::time::sleep(gap).await;
                        }
                    }
                    None => {
                        let _ = sock.write_all(&r.body).await;
                    }
                }
                let _ = sock.shutdown().await;
            });
        }
    });
    Server { addr, requests }
}

fn clients() -> Clients {
    Clients::build("MicaMinecraftLauncher/test", None).unwrap()
}

fn fast() -> Tuning {
    Tuning {
        retry_base: Duration::from_millis(10),
        ..Tuning::DEFAULT
    }
}

fn src(s: &str) -> Source {
    Source::parse(s).unwrap()
}

fn tmp_files_in(dir: &Path) -> Vec<String> {
    std::fs::read_dir(dir)
        .unwrap()
        .flatten()
        .map(|e| e.file_name().to_string_lossy().into_owned())
        .filter(|n| n.ends_with(".tmp"))
        .collect()
}

/// Serialises the tests that set the process-wide offline latch.
static OFFLINE_TEST_LOCK: tokio::sync::Mutex<()> = tokio::sync::Mutex::const_new(());

fn opts() -> DownloadOpts {
    // A scope of its own keeps this download's notices away from other tests' listeners.
    DownloadOpts::new().scope(DownloadScope::new())
}

#[tokio::test]
async fn a_truncated_attempt_is_retried_registered_once_and_rolled_back() {
    let body: Vec<u8> = (0..100u8).collect();
    let b2 = body.clone();
    let server = serve(move |i, _| {
        if i == 0 {
            Reply {
                declared_len: Some(100),
                ..Reply::ok(&b2[..10])
            }
        } else {
            Reply::ok(&b2)
        }
    })
    .await;
    let dir = tempfile::tempdir().unwrap();
    let dest = dir.path().join("file.bin");
    let tracker = Arc::new(DownloadTracker::new());
    let scope = DownloadScope::new();
    let heard = Arc::new(Mutex::new(Vec::<String>::new()));
    let h = heard.clone();
    let _g = add_retry_listener(Some(scope), move |s: &str| h.lock().push(s.to_string()));
    mmcl_i18n::set_locale(mmcl_i18n::Locale::en_us());
    let o = DownloadOpts::new().tracker(tracker.clone()).scope(scope);
    download_file_with(
        Some(&clients().download),
        &src(&server.url("/x/file.bin")),
        &dest,
        &o,
        &fast(),
    )
    .await
    .unwrap();
    assert_eq!(std::fs::read(&dest).unwrap(), body);
    assert_eq!(server.count(), 2);
    assert_eq!(
        tracker.total_bytes_expected(),
        100,
        "registered once, not per attempt"
    );
    assert_eq!(
        tracker.total_bytes_downloaded(),
        100,
        "the failed attempt's bytes were rolled back"
    );
    assert_eq!(tracker.active_downloads(), 0);
    assert_eq!(heard.lock().as_slice(), ["Retrying (2/3) file.bin"]);
    assert!(tmp_files_in(dir.path()).is_empty());
}

#[tokio::test]
async fn an_http_error_fails_after_three_attempts_and_leaves_nothing_behind() {
    let server = serve(|_, _| Reply::status(404)).await;
    let dir = tempfile::tempdir().unwrap();
    let dest = dir.path().join("missing.jar");
    let err = download_file_with(
        Some(&clients().download),
        &src(&server.url("/missing.jar")),
        &dest,
        &opts(),
        &fast(),
    )
    .await
    .unwrap_err();
    assert!(
        matches!(err, DownloadError::Http { status: 404, .. }),
        "{err}"
    );
    assert_eq!(server.count(), 3);
    assert!(!dest.exists());
    assert!(tmp_files_in(dir.path()).is_empty());
}

#[tokio::test]
async fn a_digest_mismatch_never_publishes_and_a_match_does() {
    let server = serve(|_, _| Reply::ok(b"hello world")).await;
    let dir = tempfile::tempdir().unwrap();
    let dest = dir.path().join("mod.jar");
    let bad = opts().expected(ExpectedHash::sha1(
        "0000000000000000000000000000000000000000",
    ));
    let err = download_file_with(
        Some(&clients().download),
        &src(&server.url("/mod.jar")),
        &dest,
        &bad,
        &fast(),
    )
    .await
    .unwrap_err();
    assert!(matches!(err, DownloadError::HashMismatch { .. }), "{err}");
    assert!(!dest.exists());
    let good = opts().expected(ExpectedHash::sha256(
        mmcl_util::hash::sha256_hex(b"hello world").to_uppercase(),
    ));
    download_file_with(
        Some(&clients().download),
        &src(&server.url("/mod.jar")),
        &dest,
        &good,
        &fast(),
    )
    .await
    .unwrap();
    assert_eq!(std::fs::read(&dest).unwrap(), b"hello world");
}

#[tokio::test]
async fn the_stall_watchdog_aborts_a_trickling_transfer() {
    let server = serve(|_, _| Reply {
        trickle: Some(Duration::from_millis(40)),
        ..Reply::ok(&[7u8; 100])
    })
    .await;
    let dir = tempfile::tempdir().unwrap();
    let dest = dir.path().join("slow.bin");
    let tuning = Tuning {
        max_attempts: 1,
        stall_window: Duration::from_millis(150),
        stall_min_bytes: 1000,
        ..fast()
    };
    let err = download_file_with(
        Some(&clients().download),
        &src(&server.url("/slow.bin")),
        &dest,
        &opts(),
        &tuning,
    )
    .await
    .unwrap_err();
    assert!(matches!(err, DownloadError::Stalled { .. }), "{err}");
    assert!(
        err.to_string().starts_with("Download stalled (received "),
        "{err}"
    );
    assert!(!dest.exists());
}

#[tokio::test]
async fn a_cancel_stops_the_transfer_without_retrying() {
    let server = serve(|_, _| Reply {
        trickle: Some(Duration::from_millis(20)),
        ..Reply::ok(&[1u8; 500])
    })
    .await;
    let dir = tempfile::tempdir().unwrap();
    let dest = dir.path().join("big.bin");
    let token = CancelToken::new();
    let t2 = token.clone();
    tokio::spawn(async move {
        tokio::time::sleep(Duration::from_millis(100)).await;
        t2.cancel();
    });
    let started = std::time::Instant::now();
    let err = download_file_with(
        Some(&clients().download),
        &src(&server.url("/big.bin")),
        &dest,
        &opts().cancel(token),
        &Tuning::DEFAULT,
    )
    .await
    .unwrap_err();
    assert!(err.is_cancelled(), "{err}");
    assert!(started.elapsed() < Duration::from_secs(5));
    assert_eq!(server.count(), 1);
    assert!(!dest.exists());
    assert!(tmp_files_in(dir.path()).is_empty());
}

#[tokio::test]
async fn an_aborted_download_removes_its_temp_file_and_rolls_back_its_bytes() {
    // DownloadHandle::cancel aborts the task, so the download future is dropped mid-transfer
    // and its error path never runs: the attempt guard must clean up instead.
    let server = serve(|_, _| Reply {
        trickle: Some(Duration::from_millis(10)),
        ..Reply::ok(&[3u8; 2000])
    })
    .await;
    let dir = tempfile::tempdir().unwrap();
    let dest = dir.path().join("aborted.bin");
    let tracker = Arc::new(DownloadTracker::new());
    let o = opts().tracker(tracker.clone());
    let url = server.url("/aborted.bin");
    let d2 = dest.clone();
    let task = tokio::spawn(async move {
        download_file_with(Some(&clients().download), &src(&url), &d2, &o, &fast()).await
    });
    tokio::time::timeout(Duration::from_secs(10), async {
        while tracker.total_bytes_downloaded() == 0 || tmp_files_in(dir.path()).is_empty() {
            tokio::time::sleep(Duration::from_millis(5)).await;
        }
    })
    .await
    .expect("the transfer should start");
    task.abort();
    assert!(task.await.unwrap_err().is_cancelled());
    assert!(tmp_files_in(dir.path()).is_empty(), "temp file left behind");
    assert!(!dest.exists());
    assert_eq!(tracker.total_bytes_downloaded(), 0);
}

#[tokio::test]
async fn an_aborted_bounded_file_download_removes_the_destination() {
    let server = serve(|_, _| Reply {
        trickle: Some(Duration::from_millis(10)),
        ..Reply::ok(&[4u8; 2000])
    })
    .await;
    let dir = tempfile::tempdir().unwrap();
    let dest = dir.path().join("archive.zip");
    let url = server.url("/archive.zip");
    let d2 = dest.clone();
    let task = tokio::spawn(async move {
        file_bounded_with(&clients().bounded, &url, &d2, 1 << 20, Scheme::AllowHttp).await
    });
    tokio::time::timeout(Duration::from_secs(10), async {
        while !dest.exists() {
            tokio::time::sleep(Duration::from_millis(5)).await;
        }
    })
    .await
    .expect("the transfer should start writing");
    task.abort();
    assert!(task.await.unwrap_err().is_cancelled());
    assert!(!dest.exists(), "truncated archive left behind");
}

#[tokio::test]
async fn a_successful_request_clears_the_offline_latch() {
    let server = serve(|_, _| Reply::ok(b"{}")).await;
    let _offline = OFFLINE_TEST_LOCK.lock().await;
    crate::offline::set_offline(true);
    let body = download_string_with(
        &clients().download,
        &src(&server.url("/a.json")),
        Some("application/json"),
    )
    .await
    .unwrap();
    assert_eq!(body, "{}");
    assert!(!crate::offline::is_offline());
    assert_eq!(
        server.requests.lock()[0].header("accept"),
        Some("application/json")
    );
    let ua = server.requests.lock()[0]
        .header("user-agent")
        .map(str::to_string);
    assert_eq!(ua.as_deref(), Some("MicaMinecraftLauncher/test"));
    assert!(
        server.requests.lock()[0]
            .header("accept-encoding")
            .is_none(),
        "no compression is negotiated"
    );
}

#[tokio::test]
async fn the_download_client_follows_plain_redirects() {
    let server = serve(|_, r| {
        if r.path == "/old" {
            Reply::status(302).header("Location", "/new")
        } else {
            Reply::ok(b"moved")
        }
    })
    .await;
    let dir = tempfile::tempdir().unwrap();
    let dest = dir.path().join("f");
    download_file_with(
        Some(&clients().download),
        &src(&server.url("/old")),
        &dest,
        &opts(),
        &fast(),
    )
    .await
    .unwrap();
    assert_eq!(std::fs::read(&dest).unwrap(), b"moved");
}

#[tokio::test]
async fn local_files_and_jar_entries_are_copied_through_the_same_core() {
    let dir = tempfile::tempdir().unwrap();
    let plain = dir.path().join("in.txt");
    std::fs::write(&plain, b"local bytes").unwrap();
    let jar = dir.path().join("modpack.jar");
    {
        let f = std::fs::File::create(&jar).unwrap();
        let mut z = zip::ZipWriter::new(f);
        z.start_file("maven/a/b.jar", zip::write::SimpleFileOptions::default())
            .unwrap();
        std::io::Write::write_all(&mut z, b"embedded").unwrap();
        z.finish().unwrap();
    }
    let tracker = Arc::new(DownloadTracker::new());
    let out1 = dir.path().join("out1");
    download_file_with(
        None,
        &Source::file(&plain),
        &out1,
        &opts().tracker(tracker.clone()),
        &fast(),
    )
    .await
    .unwrap();
    assert_eq!(std::fs::read(&out1).unwrap(), b"local bytes");
    let entry = Source::JarEntry {
        jar: jar.clone(),
        entry: "maven/a/b.jar".into(),
    };
    let out2 = dir.path().join("sub").join("out2");
    download_file_with(
        None,
        &entry,
        &out2,
        &opts().tracker(tracker.clone()),
        &fast(),
    )
    .await
    .unwrap();
    assert_eq!(std::fs::read(&out2).unwrap(), b"embedded");
    assert_eq!(tracker.total_bytes_downloaded(), 19);
    assert_eq!(tracker.total_bytes_expected(), 19);
    let missing = Source::JarEntry {
        jar,
        entry: "nope".into(),
    };
    let one = Tuning {
        max_attempts: 1,
        ..fast()
    };
    let err = download_file_with(None, &missing, &dir.path().join("x"), &opts(), &one)
        .await
        .unwrap_err();
    assert!(
        matches!(err, DownloadError::JarEntryMissing { .. }),
        "{err}"
    );
}

// ---- bounded fetches ----

#[tokio::test]
async fn bounded_fetch_walks_relative_redirects() {
    let server = serve(|_, r| match r.path.as_str() {
        "/a" => Reply::status(301).header("Location", "b"),
        "/b" => Reply::status(307).header("Location", "/final.json"),
        _ => Reply::ok(br#"{"ok":true}"#).header("Content-Type", "application/json"),
    })
    .await;
    let body = bounded_string_with(
        &clients().bounded,
        &server.url("/a"),
        1024,
        None,
        Scheme::AllowHttp,
    )
    .await
    .unwrap();
    assert_eq!(body, r#"{"ok":true}"#);
    assert_eq!(server.count(), 3);
}

#[tokio::test]
async fn bounded_fetch_stops_after_five_redirects() {
    let server = serve(|i, _| Reply::status(302).header("Location", &format!("/hop{i}"))).await;
    let err = bounded_string_with(
        &clients().bounded,
        &server.url("/start"),
        1024,
        None,
        Scheme::AllowHttp,
    )
    .await
    .unwrap_err();
    assert!(matches!(err, DownloadError::TooManyRedirects(_)), "{err}");
    assert_eq!(server.count(), 6);
}

#[tokio::test]
async fn bounded_fetch_rejects_a_redirect_without_location() {
    let server = serve(|_, _| Reply::status(302).header("Location", "  ")).await;
    let err = bounded_string_with(
        &clients().bounded,
        &server.url("/x"),
        1024,
        None,
        Scheme::AllowHttp,
    )
    .await
    .unwrap_err();
    assert!(
        matches!(err, DownloadError::RedirectWithoutLocation(_)),
        "{err}"
    );
}

#[tokio::test]
async fn bounded_fetch_refuses_a_hop_to_another_scheme() {
    let server = serve(|_, _| Reply::status(302).header("Location", "ftp://example.com/x")).await;
    let err = bounded_string_with(
        &clients().bounded,
        &server.url("/x"),
        1024,
        None,
        Scheme::AllowHttp,
    )
    .await
    .unwrap_err();
    assert!(matches!(err, DownloadError::NonHttps(_)), "{err}");
}

#[tokio::test]
async fn bounded_fetch_gates_content_type_size_and_status() {
    let server = serve(|_, r| match r.path.as_str() {
        "/html" => Reply::ok(b"<html>").header("Content-Type", "text/html"),
        "/big" => Reply {
            omit_len: true,
            ..Reply::ok(&[b'x'; 2048])
        }
        .header("Content-Type", "text/plain"),
        "/gone" => Reply::status(404),
        _ => Reply::ok(b"[]").header("Content-Type", "application/octet-stream"),
    })
    .await;
    let c = clients();
    let get = |p: &str| {
        let (url, client) = (server.url(p), c.bounded.clone());
        async move { bounded_string_with(&client, &url, 1024, None, Scheme::AllowHttp).await }
    };
    assert!(matches!(
        get("/html").await.unwrap_err(),
        DownloadError::ContentType { .. }
    ));
    assert!(matches!(
        get("/big").await.unwrap_err(),
        DownloadError::TooLarge { max: 1024, .. }
    ));
    assert!(matches!(
        get("/gone").await.unwrap_err(),
        DownloadError::Http { status: 404, .. }
    ));
    assert_eq!(get("/ok").await.unwrap(), "[]");
}

#[tokio::test]
async fn conditional_fetch_sends_validators_and_handles_304() {
    let server = serve(|_, r| {
        if r.header("if-none-match") == Some("\"abc\"") {
            Reply::status(304).header("Last-Modified", "Tue, 01 Jan 2030 00:00:00 GMT")
        } else {
            Reply::ok(b"{}")
                .header("Content-Type", "application/json")
                .header("ETag", "\"abc\"")
                .header("Last-Modified", "Mon, 01 Jan 2029 00:00:00 GMT")
        }
    })
    .await;
    let _offline = OFFLINE_TEST_LOCK.lock().await;
    let c = clients();
    let fresh = conditional_with(
        &c.bounded,
        &server.url("/m.json"),
        1024,
        Some(" "),
        None,
        Scheme::AllowHttp,
    )
    .await
    .unwrap();
    assert!(!fresh.not_modified);
    assert_eq!(fresh.body.as_deref(), Some("{}"));
    assert_eq!(fresh.etag.as_deref(), Some("\"abc\""));
    assert!(
        server.requests.lock()[0].header("if-none-match").is_none(),
        "blank validators are not sent"
    );
    crate::offline::set_offline(true);
    let again = conditional_with(
        &c.bounded,
        &server.url("/m.json"),
        1024,
        fresh.etag.as_deref(),
        fresh.last_modified.as_deref(),
        Scheme::AllowHttp,
    )
    .await
    .unwrap();
    assert!(again.not_modified && again.is_not_modified());
    assert_eq!(again.body, None);
    assert_eq!(
        again.etag.as_deref(),
        Some("\"abc\""),
        "falls back to the validator sent"
    );
    assert_eq!(
        again.last_modified.as_deref(),
        Some("Tue, 01 Jan 2030 00:00:00 GMT")
    );
    assert!(
        !crate::offline::is_offline(),
        "a 304 is a successful request"
    );
    let sent = server.requests.lock()[1].clone();
    assert_eq!(
        sent.header("if-modified-since"),
        Some("Mon, 01 Jan 2029 00:00:00 GMT")
    );
}

#[tokio::test]
async fn bounded_file_download_caps_declared_and_actual_size() {
    let server = serve(|_, r| match r.path.as_str() {
        "/declared" => Reply::ok(&[0u8; 4096]),
        "/undeclared" => Reply {
            omit_len: true,
            ..Reply::ok(&[0u8; 4096])
        },
        _ => Reply::ok(b"archive"),
    })
    .await;
    let c = clients();
    let dir = tempfile::tempdir().unwrap();
    let dest = dir.path().join("a.tar.gz");
    std::fs::write(&dest, b"old").unwrap();
    let e = file_bounded_with(
        &c.bounded,
        &server.url("/declared"),
        &dest,
        1024,
        Scheme::AllowHttp,
    )
    .await
    .unwrap_err();
    assert!(
        matches!(e, DownloadError::DeclaredTooLarge { declared: 4096, .. }),
        "{e}"
    );
    assert!(!dest.exists());
    let e = file_bounded_with(
        &c.bounded,
        &server.url("/undeclared"),
        &dest,
        1024,
        Scheme::AllowHttp,
    )
    .await
    .unwrap_err();
    assert!(matches!(e, DownloadError::TooLarge { .. }), "{e}");
    assert!(!dest.exists());
    file_bounded_with(
        &c.bounded,
        &server.url("/ok"),
        &dest,
        1024,
        Scheme::AllowHttp,
    )
    .await
    .unwrap();
    assert_eq!(std::fs::read(&dest).unwrap(), b"archive");
}

// ---- DownloadTempFileTest and NetworkUtilitiesTest getPathLock_* ----

#[test]
fn each_attempt_gets_its_own_sibling_temp_file() {
    let dir = tempfile::tempdir().unwrap();
    let dest = dir.path().join("abcdef");
    let first = unique_temp_file(&dest);
    let second = unique_temp_file(&dest);
    assert_ne!(first, second);
    assert_eq!(first.parent(), Some(dir.path()));
    let name = first.file_name().unwrap().to_string_lossy().into_owned();
    assert!(name.starts_with("abcdef."), "{name}");
    assert!(name.ends_with(".tmp"), "{name}");
}

#[test]
fn path_lock_is_shared_by_every_spelling_of_a_path() {
    let dir = tempfile::tempdir().unwrap();
    let a = dir.path().join("x").join("f.bin");
    let b = dir.path().join("x").join(".").join("f.bin");
    let c = dir.path().join("x").join("y").join("..").join("f.bin");
    assert!(Arc::ptr_eq(&path_lock(&a), &path_lock(&b)));
    assert!(Arc::ptr_eq(&path_lock(&a), &path_lock(&c)));
    #[cfg(windows)]
    {
        let upper = std::path::PathBuf::from(a.to_string_lossy().to_uppercase());
        assert!(Arc::ptr_eq(&path_lock(&a), &path_lock(&upper)));
    }
}

#[test]
fn path_lock_differs_for_different_paths() {
    let dir = tempfile::tempdir().unwrap();
    assert!(!Arc::ptr_eq(
        &path_lock(&dir.path().join("a")),
        &path_lock(&dir.path().join("b"))
    ));
}

#[test]
fn relative_and_absolute_forms_share_a_lock() {
    let cwd = std::env::current_dir().unwrap();
    let rel = Path::new("mmcl-net-lock-test.bin");
    assert!(Arc::ptr_eq(&path_lock(rel), &path_lock(&cwd.join(rel))));
}
