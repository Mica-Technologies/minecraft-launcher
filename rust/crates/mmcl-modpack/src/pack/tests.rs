// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

use super::*;
use mmcl_core::GameMode;
use std::sync::atomic::{AtomicUsize, Ordering};

fn paths(root: &Path) -> Arc<DataPaths> {
    Arc::new(DataPaths::at_root(root, GameMode::Client))
}

fn manifest_pack(root: &Path, name: &str, url: Option<&str>) -> Arc<GameModPack> {
    GameModPack::from_manifest(
        paths(root),
        PackManifest {
            pack_name: Some(name.into()),
            pack_version: Some("2.0".into()),
            pack_mods: Some(Vec::new()),
            ..Default::default()
        },
        url.map(str::to_string),
        Some("ab".into()),
    )
}

// ---- LaunchIdentityTest (settings-key part) ----

#[test]
fn settings_keys() {
    let d = tempfile::tempdir().unwrap();
    assert_eq!(
        manifest_pack(d.path(), "Alto", Some("https://x/alto.mmcjson"))
            .settings_key()
            .as_deref(),
        Some("https://x/alto.mmcjson")
    );
    let v = GameModPack::vanilla(paths(d.path()), "1.20.4");
    assert_eq!(v.settings_key().as_deref(), Some("vanilla:1.20.4"));
    assert_eq!(v.sanitized_name(), "Minecraft1204");
    assert!(v.mod_loader_type().is_none());
    assert!(!v.uses_post_install_steps());
    assert!(manifest_pack(d.path(), "X", None).settings_key().is_none());
    assert!(
        manifest_pack(d.path(), "X", Some("  "))
            .settings_key()
            .is_none()
    );
}

// ---- IncompleteManifestLaunchGuardTest (pack part) ----

#[test]
fn failed_and_stub_are_named_but_unusable() {
    let d = tempfile::tempdir().unwrap();
    let long = format!("https://example.com/{}/manifest.mmcjson", "a".repeat(50));
    let f = GameModPack::failed(paths(d.path()), &long, "boom");
    assert!(f.is_failed_load() && !f.is_stub() && !f.is_launchable());
    assert!(f.friendly_name().is_some());
    assert!(f.name().contains("..."));
    assert!(f.manifest().pack_mods.is_none());
    assert_eq!(f.manifest_url(), Some(long.as_str()));
    assert_eq!(f.failed_error(), Some("boom"));
    let e = crate::index::IndexEntry {
        pack_name: Some("Alto".into()),
        ..Default::default()
    };
    let s = GameModPack::stub_from_index(paths(d.path()), "https://x/a", &e);
    assert!(s.is_stub() && !s.is_failed_load() && !s.is_launchable());
    assert_eq!(s.name(), "Alto");
}

#[test]
fn short_url_rule() {
    assert_eq!(short_url("https://a/b"), "https://a/b");
    let u = "x".repeat(41);
    assert_eq!(short_url(&u), format!("...{}", "x".repeat(37)));
}

#[test]
fn empty_sanitized_name_is_refused() {
    let d = tempfile::tempdir().unwrap();
    let p = manifest_pack(d.path(), "日本語", None);
    assert_eq!(p.sanitized_name(), "");
    assert!(matches!(
        p.checked_pack_root(),
        Err(ModpackError::EmptyFolderName(_))
    ));
    // Sidecar writers do nothing rather than write into installs/.
    p.save_installed_version();
    p.record_launch_start();
    assert!(
        !d.path()
            .join("installs")
            .join(INSTALLED_VERSION_FILE)
            .exists()
    );
}

#[test]
fn installed_version_and_update_check() {
    let d = tempfile::tempdir().unwrap();
    let p = manifest_pack(d.path(), "Up", None);
    assert!(!p.is_update_available());
    p.save_installed_version();
    assert_eq!(p.installed_version().as_deref(), Some("2.0"));
    assert!(!p.is_update_available());
    PackDotFile::write(&p.pack_root(), INSTALLED_VERSION_FILE, " 1.9 \n").unwrap();
    // Cached until save_installed_version resets it.
    assert!(!p.is_update_available());
    let fresh = manifest_pack(d.path(), "Up", None);
    assert!(fresh.is_update_available());
}

#[test]
fn launch_history() {
    let d = tempfile::tempdir().unwrap();
    let p = manifest_pack(d.path(), "Hist", None);
    assert!(p.is_never_played());
    assert_eq!(p.launch_count(), 0);
    p.record_launch_start();
    p.record_launch_start();
    p.record_session_end(5_000);
    assert_eq!(p.launch_count(), 2);
    assert_eq!(p.total_play_time_ms(), 5_000);
    assert!(!p.is_never_played());
    let text = PackDotFile::read(&p.pack_root(), LAUNCH_HISTORY_FILE).unwrap();
    let lines: Vec<&str> = text.split('\n').collect();
    assert_eq!(lines.len(), 4);
    assert_eq!(lines[1], "5000");
    assert_eq!(lines[2], "2");
    assert_eq!(lines[3], "");
    // A fresh object reads the file.
    let q = manifest_pack(d.path(), "Hist", None);
    assert_eq!(q.launch_count(), 2);
    // Garbage reads as zeros.
    PackDotFile::write(&q.pack_root(), LAUNCH_HISTORY_FILE, "x\ny").unwrap();
    assert_eq!(manifest_pack(d.path(), "Hist", None).launch_count(), 0);
    assert_eq!(
        LaunchHistory::parse("1\n2\n3\n"),
        LaunchHistory {
            last_played_ms: 1,
            total_play_time_ms: 2,
            launch_count: 3
        }
    );
}

#[test]
fn news_tracking() {
    let d = tempfile::tempdir().unwrap();
    let news: Vec<NewsItem> = serde_json::from_str(
        r#"[{"id":"a","title":"A","date":"2026-01-01"},{"id":"b","title":"B","date":"2026-02-01"},{"title":"no id"},{"id":"x","title":"Expired","expires":"2026-01-02"}]"#,
    )
    .unwrap();
    let p = GameModPack::from_manifest(
        paths(d.path()),
        PackManifest {
            pack_name: Some("News".into()),
            pack_news: Some(news),
            ..Default::default()
        },
        None,
        None,
    );
    let today = NaiveDate::from_ymd_opt(2026, 3, 1).unwrap();
    assert_eq!(p.unread_news_count(today), 2);
    PackDotFile::write(&p.pack_root(), SEEN_NEWS_FILE, "old\n").unwrap();
    p.mark_all_news_seen(today);
    assert_eq!(p.unread_news_count(today), 0);
    assert_eq!(
        PackDotFile::read(&p.pack_root(), SEEN_NEWS_FILE).unwrap(),
        "b\na\nold\n"
    );
}

#[test]
fn quick_join_funnel() {
    let d = tempfile::tempdir().unwrap();
    let p = GameModPack::from_manifest(
        paths(d.path()),
        PackManifest {
            pack_name: Some("Srv".into()),
            pack_default_server_host: Some(" alto.example ".into()),
            pack_default_server_port: Some(8585),
            ..Default::default()
        },
        None,
        None,
    );
    let explicit = ServerFavorite::new("Mine", "mine.example", 25565);
    p.set_quick_join_server(Some(explicit.clone()));
    assert_eq!(p.consume_quick_join_server(), Some(explicit));
    let def = p.consume_quick_join_server().unwrap();
    assert_eq!((def.host.as_str(), def.port), ("alto.example", 8585));
    servers::set_default_server_disabled(&p.pack_root(), true).unwrap();
    assert!(p.consume_quick_join_server().is_none());
}

#[test]
fn crash_report_only_when_recent() {
    let d = tempfile::tempdir().unwrap();
    let dir = d.path().join("crash-reports");
    fs::create_dir_all(&dir).unwrap();
    fs::write(dir.join("old.txt"), "old").unwrap();
    fs::write(dir.join("new.txt"), "new").unwrap();
    let f = fs::File::options()
        .write(true)
        .open(dir.join("old.txt"))
        .unwrap();
    f.set_modified(SystemTime::now() - Duration::from_secs(30))
        .unwrap();
    assert_eq!(
        latest_crash_report_in(&dir, SystemTime::now()).as_deref(),
        Some("new")
    );
    assert!(latest_crash_report_in(&dir, SystemTime::now() + Duration::from_secs(120)).is_none());
    assert!(latest_crash_report_in(&d.path().join("none"), SystemTime::now()).is_none());
}

#[test]
fn prepare_context_swap() {
    let d = tempfile::tempdir().unwrap();
    let a = manifest_pack(d.path(), "A", None);
    let b = manifest_pack(d.path(), "B", None);
    let fast = Arc::new(PrepareContext::for_launch(
        crate::VerifyMode::FastPath,
        None,
        3,
    ));
    let prev = a.set_prepare_context(fast.clone());
    assert_eq!(*prev, PrepareContext::NONE);
    // Each pack carries its own context.
    assert_eq!(a.prepare_context().verify_mode, crate::VerifyMode::FastPath);
    assert_eq!(b.prepare_context().verify_mode, crate::VerifyMode::Full);
    a.set_prepare_context(prev);
    assert_eq!(*a.prepare_context(), PrepareContext::NONE);
}

struct TestLoader;
impl PackLoader for TestLoader {
    fn loader_name(&self) -> String {
        "Forge".into()
    }
    fn loader_version(&self) -> String {
        "47.3.0".into()
    }
    fn minecraft_version(&self) -> String {
        "1.20.1".into()
    }
    fn as_any(&self) -> &dyn Any {
        self
    }
}

struct CountingFactory(AtomicUsize);
impl ModLoaderFactory for CountingFactory {
    fn create<'a>(
        &'a self,
        _pack: &'a Arc<GameModPack>,
    ) -> BoxFuture<'a, Result<Arc<dyn PackLoader>>> {
        self.0.fetch_add(1, Ordering::SeqCst);
        Box::pin(async { Ok(Arc::new(TestLoader) as Arc<dyn PackLoader>) })
    }
}

#[tokio::test]
async fn loader_is_memoised_per_instance() {
    let d = tempfile::tempdir().unwrap();
    let f = CountingFactory(AtomicUsize::new(0));
    let p = manifest_pack(d.path(), "L", Some("https://x/l"));
    assert!(p.cached_mod_loader().is_none());
    let l = p.mod_loader_with(&f).await.unwrap().unwrap();
    assert_eq!(l.minecraft_version(), "1.20.1");
    p.mod_loader_with(&f).await.unwrap();
    assert_eq!(f.0.load(Ordering::SeqCst), 1);
    assert_eq!(p.minecraft_version_hint().as_deref(), Some("1.20.1"));
    assert!(l.as_any().downcast_ref::<TestLoader>().is_some());
    // A re-fetched pack is a new object and builds its own.
    manifest_pack(d.path(), "L", Some("https://x/l"))
        .mod_loader_with(&f)
        .await
        .unwrap();
    assert_eq!(f.0.load(Ordering::SeqCst), 2);
    // Vanilla has none; failed / stub refuse.
    assert!(
        GameModPack::vanilla(paths(d.path()), "1.21")
            .mod_loader_with(&f)
            .await
            .unwrap()
            .is_none()
    );
    assert!(
        GameModPack::failed(paths(d.path()), "u", "e")
            .mod_loader_with(&f)
            .await
            .is_err()
    );
}
