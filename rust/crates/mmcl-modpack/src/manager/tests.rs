// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

use std::cell::RefCell;
use std::fs;

use super::*;
use crate::http::fake::{FakeHttp, Reply};
use mmcl_config::{ConfigStore, Document};
use mmcl_core::GameMode;
use mmcl_manifest::PackManifest;

fn body(name: &str, ver: &str) -> String {
    format!(r#"{{"packName":"{name}","packVersion":"{ver}","packMods":[]}}"#)
}

fn mgr(root: &std::path::Path) -> (Arc<ModPackManager>, Arc<FakeHttp>) {
    let http = FakeHttp::new();
    let paths = Arc::new(DataPaths::at_root(root, GameMode::Client));
    let cfg = Config::new(ConfigStore::in_memory(Document::new()));
    let fetcher = ManifestFetcher::new(paths, http.clone()).with_notifier(Arc::new(|_, _| {}));
    let m = ModPackManager::with_fetcher(fetcher, cfg);
    m.set_notifier(Arc::new(|_, _| {}));
    m.set_available_url("https://list.example/installable.json");
    (m, http)
}

fn pack_at(root: &std::path::Path, name: &str, url: &str) -> Arc<GameModPack> {
    GameModPack::from_manifest(
        Arc::new(DataPaths::at_root(root, GameMode::Client)),
        PackManifest {
            pack_name: Some(name.into()),
            pack_mods: Some(vec![]),
            ..Default::default()
        },
        Some(url.into()),
        None,
    )
}

// ---- IncompleteManifestLaunchGuardTest (list part) ----

#[test]
fn fresh_fetch_replaces_the_stub_in_place_unknown_url_appends() {
    let d = tempfile::tempdir().unwrap();
    let paths = Arc::new(DataPaths::at_root(d.path(), GameMode::Client));
    let e = crate::index::IndexEntry {
        pack_name: Some("A".into()),
        ..Default::default()
    };
    let mut list = vec![
        GameModPack::stub_from_index(paths.clone(), "u1", &e),
        GameModPack::stub_from_index(paths, "u2", &e),
    ];
    replace_or_append_by_url(&mut list, "u1", pack_at(d.path(), "A", "u1"));
    assert_eq!(list.len(), 2);
    assert!(!list[0].is_stub() && list[1].is_stub());
    replace_or_append_by_url(&mut list, "u3", pack_at(d.path(), "C", "u3"));
    assert_eq!(list.len(), 3);
    assert!(!replace_by_url(
        &mut list,
        "nope",
        pack_at(d.path(), "D", "nope")
    ));
}

// ---- UninstallRunningPackGuardTest ----

#[test]
fn uninstall_guard() {
    let d = tempfile::tempdir().unwrap();
    let p = pack_at(d.path(), "Run", "u");
    let log = RefCell::new(Vec::new());
    let ok = uninstall_guarded(
        &p,
        true,
        |_| true,
        |_| log.borrow_mut().push("delete"),
        |_| log.borrow_mut().push("unregister"),
    );
    assert!(!ok);
    assert!(log.borrow().is_empty(), "nothing touched");
    assert!(uninstall_guarded(
        &p,
        true,
        |_| false,
        |_| log.borrow_mut().push("delete"),
        |_| log.borrow_mut().push("unregister")
    ));
    assert_eq!(*log.borrow(), ["delete", "unregister"]);
    log.borrow_mut().clear();
    assert!(uninstall_guarded(
        &p,
        false,
        |_| false,
        |_| log.borrow_mut().push("delete"),
        |_| log.borrow_mut().push("unregister")
    ));
    assert_eq!(*log.borrow(), ["unregister"]);
}

#[test]
fn available_list_parsing_skips_bad_elements() {
    assert_eq!(
        parse_available_list(
            r#"{"available":["https://a/1.mmcjson", 5, null, " ", "https://a/2.mmcjson"]}"#
        ),
        ["https://a/1.mmcjson", "https://a/2.mmcjson"]
    );
    assert!(parse_available_list(r#"{"other":[]}"#).is_empty());
    assert!(parse_available_list("garbage").is_empty());
}

#[tokio::test]
async fn install_update_and_uninstall() {
    let d = tempfile::tempdir().unwrap();
    let (m, http) = mgr(d.path());
    http.body(
        "https://list.example/installable.json",
        r#"{"available":["https://h/a.mmcjson","https://h/b.mmcjson"]}"#,
    );
    http.body("https://h/a.mmcjson", body("A", "1"));
    http.body("https://h/b.mmcjson", body("B", "1"));
    let a = m.install_by_url("https://h/a.mmcjson").await.unwrap();
    assert_eq!(m.config().installed_mod_packs(), ["https://h/a.mmcjson"]);
    assert_eq!(m.installed_snapshot().len(), 1);
    // The available list skips installed packs.
    let avail = m.available().await;
    assert_eq!(avail.len(), 1);
    assert_eq!(avail[0].name(), "B");
    // Re-installing the same URL replaces in place; the config entry is not duplicated.
    fs::create_dir_all(a.config_dir()).unwrap();
    fs::write(a.config_dir().join("x.cfg"), "x").unwrap();
    m.install_by_url("https://h/a.mmcjson").await.unwrap();
    assert_eq!(m.config().installed_mod_packs(), ["https://h/a.mmcjson"]);
    assert_eq!(m.installed_snapshot().len(), 1);
    assert!(
        a.pack_root().join(crate::backup::BACKUPS_DIR).is_dir(),
        "update backed up the config"
    );
    // Lookups.
    assert!(m.installed_by_name("a").is_some());
    assert!(m.installed_by_url("HTTPS://H/A.MMCJSON").is_some());
    assert!(m.installed_by_friendly_name("a: 1").is_some());
    // Install by friendly name from the available list.
    m.install_by_friendly_name("B: 1").await.unwrap();
    assert_eq!(m.installed_snapshot().len(), 2);
    assert!(m.install_by_friendly_name("Z: 9").await.is_err());
    // A running pack is refused; an idle one goes, with its files.
    let b = m.installed_by_name("B").unwrap();
    m.set_active_check(Arc::new(|p| p.name() == "B"));
    assert!(!m.uninstall(&b, true).await);
    assert!(b.pack_root().exists());
    m.set_active_check(Arc::new(|_| false));
    assert!(m.uninstall(&b, true).await);
    assert!(!b.pack_root().exists());
    assert_eq!(m.config().installed_mod_packs(), ["https://h/a.mmcjson"]);
    assert!(
        InstallIndex::load(&m.paths().install_index_file())
            .get("https://h/b.mmcjson")
            .is_none()
    );
}

#[tokio::test]
async fn a_failed_fetch_is_not_installed_as_new() {
    let d = tempfile::tempdir().unwrap();
    let (m, http) = mgr(d.path());
    http.on("https://h/down.mmcjson", Reply::Status(503));
    assert!(m.install_by_url("https://h/down.mmcjson").await.is_err());
    assert!(m.config().installed_mod_packs().is_empty());
}

#[tokio::test]
async fn revalidate_keeps_the_cached_pack_when_the_fresh_fetch_fails() {
    let d = tempfile::tempdir().unwrap();
    let (m, http) = mgr(d.path());
    http.on("https://h/a.mmcjson", Reply::Body(body("A", "1").into()));
    http.on("https://h/a.mmcjson", Reply::Status(500));
    http.on(
        "https://list.example/installable.json",
        Reply::Body(br#"{"available":[]}"#.to_vec()),
    );
    m.install_by_url("https://h/a.mmcjson").await.unwrap();
    // A fresh manager: phase 1 loads from the cache, phase 2 revalidates and fails.
    let (m2, _) = {
        let paths = m.paths().clone();
        let f = ManifestFetcher::new(paths, http.clone()).with_notifier(Arc::new(|_, _| {}));
        (ModPackManager::with_fetcher(f, m.config().clone()), ())
    };
    m2.fetch_installed(true).await;
    let list = m2.installed_snapshot();
    assert_eq!(list.len(), 1);
    assert!(
        !list[0].is_failed_load() && !list[0].is_stub(),
        "kept the cached pack"
    );
    assert_eq!(list[0].name(), "A");
}

#[tokio::test]
async fn offline_load_uses_index_and_cache() {
    let d = tempfile::tempdir().unwrap();
    let (m, http) = mgr(d.path());
    http.body("https://h/a.mmcjson", body("A", "1"));
    http.body(
        "https://list.example/installable.json",
        r#"{"available":[]}"#,
    );
    m.install_by_url("https://h/a.mmcjson").await.unwrap();
    // The install may have started a background revalidate on the shared pool; let it finish,
    // or a slow runner still sees it pending below (that is not the offline load's doing).
    if let Some(f) = m.installed_revalidate() {
        f.await;
    }
    http.set_offline(true);
    let before = http.log.lock().len();
    m.fetch_installed(true).await;
    assert_eq!(http.log.lock().len(), before, "no network offline");
    assert_eq!(m.installed_snapshot()[0].name(), "A");
    assert!(!m.is_installed_revalidate_pending());
}

#[tokio::test]
async fn a_url_listed_twice_in_config_leaves_no_stub_behind() {
    let d = tempfile::tempdir().unwrap();
    let (m, http) = mgr(d.path());
    http.body("https://h/a.mmcjson", body("A", "1"));
    http.body(
        "https://list.example/installable.json",
        r#"{"available":[]}"#,
    );
    m.install_by_url("https://h/a.mmcjson").await.unwrap();
    // A Java-written config with the same URL twice.
    m.config()
        .set_installed_mod_packs(&["https://h/a.mmcjson", "https://h/a.mmcjson"]);
    http.set_offline(true);
    m.fetch_installed(false).await;
    let list = m.installed_snapshot();
    assert_eq!(list.len(), 1);
    assert!(!list[0].is_stub());
    let found = m.installed_by_friendly_name("A: 1").unwrap();
    assert!(!found.is_stub(), "the last match is the loaded pack");
}
