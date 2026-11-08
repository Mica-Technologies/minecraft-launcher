// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

use std::io::Write;

use super::*;
use crate::http::fake::{FakeHttp, Reply};

fn sha1_of(b: &[u8]) -> String {
    mmcl_util::hash::sha1_hex(b)
}
fn sha256_of(b: &[u8]) -> String {
    mmcl_util::hash::sha256_hex(b)
}

fn spec(local: &str, remote: &str) -> ManifestFile {
    ManifestFile {
        local: Some(local.into()),
        remote: Some(remote.into()),
        client_req: true,
        server_req: true,
        ..Default::default()
    }
}

/// Writes a jar holding `entries` and returns its `jar:` URL prefix.
fn make_jar(path: &Path, entries: &[(&str, &[u8])]) -> String {
    fs::create_dir_all(path.parent().unwrap()).unwrap();
    let f = fs::File::create(path).unwrap();
    let mut z = zip::ZipWriter::new(f);
    for (name, bytes) in entries {
        z.start_file(*name, zip::write::SimpleFileOptions::default())
            .unwrap();
        z.write_all(bytes).unwrap();
    }
    z.finish().unwrap();
    let u = url::Url::from_file_path(std::path::absolute(path).unwrap()).unwrap();
    format!("jar:{u}!/")
}

// ---- ManagedGameFileMultiHashTest -----------------------------------------------------------

#[test]
fn multi_hash_prefers_sha256_and_skips_sentinels() {
    let mut s = spec("a.jar", "https://x/a.jar");
    assert!(s.strongest_hash().is_none());
    s.md5 = Some("m".into());
    assert_eq!(s.strongest_hash().unwrap().0, HashAlgo::Md5);
    s.sha1 = Some("s1".into());
    assert_eq!(s.strongest_hash().unwrap().0, HashAlgo::Sha1);
    s.sha256 = Some("-1".into());
    assert_eq!(s.strongest_hash().unwrap(), (HashAlgo::Sha1, "s1"));
    s.sha256 = Some("  ".into());
    assert_eq!(s.strongest_hash().unwrap().0, HashAlgo::Sha1);
    s.sha256 = Some("s256".into());
    assert_eq!(s.strongest_hash().unwrap(), (HashAlgo::Sha256, "s256"));
}

// ---- ManagedGameFilePostDownloadGateTest ----------------------------------------------------

#[test]
fn post_download_gate() {
    let d = tempfile::tempdir().unwrap();
    let f = d.path().join("a.jar");
    fs::write(&f, b"hello").unwrap();
    let mut s = spec("a.jar", "https://x/a.jar");
    let mf = |s: &ManifestFile| ManagedFile::new(s.clone(), d.path(), d.path());
    // No hash: passes.
    assert!(mf(&s).matches_declared_hash(&f).unwrap());
    // Match (case-insensitive).
    s.sha1 = Some(sha1_of(b"hello").to_uppercase());
    assert!(mf(&s).matches_declared_hash(&f).unwrap());
    // Mismatch.
    s.sha1 = Some(sha1_of(b"other"));
    assert!(!mf(&s).matches_declared_hash(&f).unwrap());
    // Strongest first, no fallback: a wrong SHA-256 fails although SHA-1 matches.
    s.sha1 = Some(sha1_of(b"hello"));
    s.sha256 = Some(sha256_of(b"other"));
    assert!(!mf(&s).matches_declared_hash(&f).unwrap());
    // The gate ignores FAST_PATH: it never takes a context, and FAST_PATH verify passes.
    let fast = PrepareContext::for_launch(VerifyMode::FastPath, None, 1);
    assert!(mf(&s).verify(&fast));
    assert!(!mf(&s).verify(&PrepareContext::NONE));
}

// ---- ManagedGameFileUrlGateTest -------------------------------------------------------------

#[test]
fn jar_url_gate() {
    let d = tempfile::tempdir().unwrap();
    let root = d.path().join("root");
    let inside = make_jar(
        &root.join("installs/P/bin/modpack.jar"),
        &[("maven/x.jar", b"x")],
    );
    assert!(is_jar_url_contained_in(
        &root,
        &format!("{inside}maven/x.jar")
    ));
    let outside = make_jar(&d.path().join("other/evil.jar"), &[("x", b"x")]);
    assert!(!is_jar_url_contained_in(&root, &format!("{outside}x")));
    assert!(!is_jar_url_contained_in(
        &root,
        "jar:http://example.com/a.jar!/x"
    ));
    assert!(!is_jar_url_contained_in(&root, "jar:file:no-bang"));
    assert!(!is_jar_url_contained_in(&root, "https://example.com/a.jar"));
    assert!(!is_jar_url_contained_in(&root, ""));
    assert!(!is_jar_url_contained_in(&root, "jar:"));
    // `..` traversal out of the root.
    let trav = inside.replace(
        "installs/P/bin/modpack.jar",
        "installs/../../other/evil.jar",
    );
    assert!(!is_jar_url_contained_in(&root, &format!("{trav}x")));
    // Scheme classification.
    assert_eq!(
        classify_remote("https://a.example/x.jar", &root),
        RemoteKind::Https
    );
    assert_eq!(
        classify_remote("HTTPS://a.example/x.jar", &root),
        RemoteKind::Https
    );
    assert_eq!(
        classify_remote("http://a.example/x.jar", &root),
        RemoteKind::Refused
    );
    assert_eq!(
        classify_remote("file:///etc/passwd", &root),
        RemoteKind::Refused
    );
    assert_eq!(classify_remote("ftp://a/x", &root), RemoteKind::Refused);
}

// ---- containment ---------------------------------------------------------------------------

#[test]
fn containment_gate() {
    let d = tempfile::tempdir().unwrap();
    let mods = d.path().join("mods");
    let c =
        |local: &str| ManagedFile::new(spec(local, "https://x/y"), &mods, d.path()).is_contained();
    assert!(c("a.jar"));
    assert!(c("sub/dir/a.jar"));
    assert!(c("sub\\a.jar"));
    assert!(c("./a.jar"));
    assert!(!c("../a.jar"));
    assert!(!c("sub/../../a.jar"));
    assert!(!c(".."));
    assert!(!c(""));
    assert!(!c("."));
    if cfg!(windows) {
        assert!(!c("C:\\Windows\\x.dll"));
        assert!(!c("a.jar:stream"));
        assert!(!c("..\\a.jar"));
    } else {
        // An absolute local is joined under the prefix, so it stays inside.
        assert!(c("/etc/passwd"));
    }
    // Legacy full-path files skip the check.
    let lp = ManagedFile::at_path(spec("", "https://x/y"), Path::new("/anywhere/x"), d.path());
    assert!(lp.is_contained());
    assert_eq!(lp.full_path(), PathBuf::from("/anywhere/x"));
}

#[test]
fn outside_file_never_verifies_or_downloads() {
    let rt = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .unwrap();
    rt.block_on(async {
        let d = tempfile::tempdir().unwrap();
        fs::write(d.path().join("secret"), b"x").unwrap();
        let f = ManagedFile::new(
            spec("../secret", "https://x/y"),
            d.path().join("mods"),
            d.path(),
        );
        assert!(!f.verify(&PrepareContext::NONE));
        let http = FakeHttp::new();
        let e = f.download(http.as_ref()).await.unwrap_err();
        assert!(matches!(e, ModpackError::OutsidePack(_)));
        assert_eq!(http.count("DL"), 0);
    });
}

// ---- ManagedGameFileSessionVerifiedTest -----------------------------------------------------

#[tokio::test]
async fn session_flag_rechecks_a_deleted_file() {
    let d = tempfile::tempdir().unwrap();
    let mut s = spec("a.jar", "https://cdn.example/a.jar");
    s.sha1 = Some(sha1_of(b"good"));
    let f = ManagedFile::new(s, d.path(), d.path());
    fs::write(f.full_path(), b"good").unwrap();
    let http = FakeHttp::new();
    assert!(
        !f.update(&PrepareContext::NONE, http.as_ref())
            .await
            .unwrap()
    );
    assert!(f.is_session_verified());
    // A present file stays skipped (even rewritten: the hash is not re-checked).
    fs::write(f.full_path(), b"tampered").unwrap();
    assert!(
        !f.update(&PrepareContext::NONE, http.as_ref())
            .await
            .unwrap()
    );
    // Deleted: re-checked and re-downloaded.
    fs::remove_file(f.full_path()).unwrap();
    http.body("https://cdn.example/a.jar", b"good".to_vec());
    assert!(
        f.update(&PrepareContext::NONE, http.as_ref())
            .await
            .unwrap()
    );
    assert_eq!(fs::read(f.full_path()).unwrap(), b"good");
    // Reverify forgets the flag and finds the tampered bytes.
    fs::write(f.full_path(), b"tampered").unwrap();
    assert!(
        f.reverify(&PrepareContext::NONE, http.as_ref())
            .await
            .unwrap()
    );
    assert_eq!(fs::read(f.full_path()).unwrap(), b"good");
}

#[tokio::test]
async fn local_only_file_missing_is_an_error_and_not_marked() {
    let d = tempfile::tempdir().unwrap();
    let f = ManagedFile::new(spec("a.jar", ""), d.path(), d.path());
    let http = FakeHttp::new();
    let e = f
        .update(&PrepareContext::NONE, http.as_ref())
        .await
        .unwrap_err();
    assert!(matches!(e, ModpackError::MissingNoUrl(_)));
    assert!(!f.is_session_verified());
    fs::write(f.full_path(), b"x").unwrap();
    assert!(
        !f.update(&PrepareContext::NONE, http.as_ref())
            .await
            .unwrap()
    );
    assert!(f.is_session_verified());
    assert_eq!(http.log.lock().len(), 0);
}

#[tokio::test]
async fn offline_refuses_unverified_bytes() {
    let d = tempfile::tempdir().unwrap();
    let mut s = spec("a.jar", "https://cdn.example/a.jar");
    s.sha256 = Some(sha256_of(b"good"));
    let f = ManagedFile::new(s, d.path(), d.path());
    let http = FakeHttp::new();
    http.set_offline(true);
    let e = f
        .update(&PrepareContext::NONE, http.as_ref())
        .await
        .unwrap_err();
    assert!(matches!(e, ModpackError::OfflineMissing(_)));
    fs::write(f.full_path(), b"bad").unwrap();
    let e = f
        .update(&PrepareContext::NONE, http.as_ref())
        .await
        .unwrap_err();
    assert!(matches!(e, ModpackError::OfflineHashMismatch(_)));
    fs::write(f.full_path(), b"good").unwrap();
    assert!(
        !f.update(&PrepareContext::NONE, http.as_ref())
            .await
            .unwrap()
    );
}

#[tokio::test]
async fn hash_mismatch_retries_three_times_then_fails() {
    let d = tempfile::tempdir().unwrap();
    let mut s = spec("a.jar", "https://cdn.example/a.jar");
    s.sha1 = Some(sha1_of(b"good"));
    let f = ManagedFile::new(s, d.path(), d.path());
    let http = FakeHttp::new();
    http.body("https://cdn.example/a.jar", b"evil".to_vec());
    let e = f
        .update(&PrepareContext::NONE, http.as_ref())
        .await
        .unwrap_err();
    assert!(matches!(e, ModpackError::HashFailed { attempts: 3, .. }));
    assert_eq!(http.count("DL"), 3);
    assert!(!f.full_path().exists(), "rejected bytes are deleted");
}

#[tokio::test]
async fn non_https_is_refused() {
    let d = tempfile::tempdir().unwrap();
    let f = ManagedFile::new(
        spec("a.jar", "http://cdn.example/a.jar"),
        d.path(),
        d.path(),
    );
    let http = FakeHttp::new();
    let e = f
        .update(&PrepareContext::NONE, http.as_ref())
        .await
        .unwrap_err();
    assert!(matches!(e, ModpackError::NonHttps(_)));
    assert_eq!(http.count("DL"), 0);
}

#[tokio::test]
async fn jar_entry_source_downloads_and_is_hash_checked() {
    let d = tempfile::tempdir().unwrap();
    let root = d.path();
    let prefix = make_jar(
        &root.join("installs/P/bin/modpack.jar"),
        &[("maven/lib.jar", b"lib")],
    );
    let mut s = spec("lib.jar", &format!("{prefix}maven/lib.jar"));
    s.sha1 = Some(sha1_of(b"lib"));
    let f = ManagedFile::new(s.clone(), root.join("libraries"), root);
    let http = FakeHttp::new();
    assert!(
        f.update(&PrepareContext::NONE, http.as_ref())
            .await
            .unwrap()
    );
    assert_eq!(fs::read(f.full_path()).unwrap(), b"lib");
    s.sha1 = Some(sha1_of(b"nope"));
    let g = ManagedFile::new(s, root.join("libraries2"), root);
    let e = g
        .update(&PrepareContext::NONE, http.as_ref())
        .await
        .unwrap_err();
    assert!(matches!(e, ModpackError::HashFailed { .. }));
}

#[tokio::test]
async fn audited_redownload_records_old_and_new_hash() {
    let d = tempfile::tempdir().unwrap();
    let mut s = spec("a.jar", "https://cdn.example/a.jar");
    s.sha1 = Some(sha1_of(b"good"));
    let f = ManagedFile::new(s, d.path().join("mods"), d.path());
    fs::create_dir_all(d.path().join("mods")).unwrap();
    fs::write(f.full_path(), b"stale").unwrap();
    let http = FakeHttp::new();
    http.body("https://cdn.example/a.jar", b"good".to_vec());
    let ctx = PrepareContext::for_launch(VerifyMode::Full, Some(d.path().into()), 5);
    assert!(f.update(&ctx, http.as_ref()).await.unwrap());
    let log = fs::read_to_string(d.path().join(crate::audit::AUDIT_FILE)).unwrap();
    assert!(log.contains(&format!("\"oldHash\":\"{}\"", sha1_of(b"stale"))));
    assert!(log.contains(&format!("\"newHash\":\"{}\"", sha1_of(b"good"))));
    assert!(log.contains("\"changed\":true"));
    assert!(log.contains("\"file\":\"mods/a.jar\""));
    // A cold install (file absent) writes nothing.
    let g = ManagedFile::new(
        {
            let mut s = spec("b.jar", "https://cdn.example/a.jar");
            s.sha1 = Some(sha1_of(b"good"));
            s
        },
        d.path().join("mods"),
        d.path(),
    );
    g.update(&ctx, http.as_ref()).await.unwrap();
    let lines = fs::read_to_string(d.path().join(crate::audit::AUDIT_FILE))
        .unwrap()
        .lines()
        .count();
    assert_eq!(lines, 1);
}

// ---- LaunchPrepareContextTest ---------------------------------------------------------------

#[test]
fn each_check_follows_its_own_context() {
    let d = tempfile::tempdir().unwrap();
    let mut s = spec("a.jar", "https://x/a.jar");
    s.sha1 = Some(sha1_of(b"expected"));
    let a = ManagedFile::new(s.clone(), d.path().join("A"), d.path());
    let b = ManagedFile::new(s, d.path().join("B"), d.path());
    for f in [&a, &b] {
        fs::create_dir_all(f.full_path().parent().unwrap()).unwrap();
        fs::write(f.full_path(), b"wrong bytes").unwrap();
    }
    let fast = PrepareContext::for_launch(VerifyMode::FastPath, None, 1);
    let full = PrepareContext::for_launch(VerifyMode::Full, None, 2);
    // One launch's FAST_PATH never reaches another launch's FULL verify.
    std::thread::scope(|sc| {
        let ha = sc.spawn(|| (0..50).all(|_| a.verify(&fast)));
        let hb = sc.spawn(|| (0..50).all(|_| !b.verify(&full)));
        assert!(ha.join().unwrap());
        assert!(hb.join().unwrap());
    });
}

#[tokio::test]
async fn read_json_memoises_by_mtime_and_size() {
    let d = tempfile::tempdir().unwrap();
    let f = ManagedFile::new(spec("x.json", ""), d.path(), d.path());
    fs::write(f.full_path(), br#"{"a":1}"#).unwrap();
    let http = FakeHttp::new();
    let v1 = f
        .read_json(&PrepareContext::NONE, http.as_ref())
        .await
        .unwrap();
    let v2 = f
        .read_json(&PrepareContext::NONE, http.as_ref())
        .await
        .unwrap();
    assert!(Arc::ptr_eq(&v1, &v2));
    assert_eq!(v1["a"], 1);
    let _ = Reply::NotModified;
}
