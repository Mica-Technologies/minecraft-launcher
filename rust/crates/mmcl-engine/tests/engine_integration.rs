// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! End-to-end engine tests over a temporary data folder, with no network: authored packs,
//! manifest edits, backups, refusals, server-mode guard, settings helpers and reset.

use std::path::Path;
use std::time::Duration;

use mmcl_core::{DataPaths, GameMode};
use mmcl_engine::{
    Engine, EngineError, EngineEvent, EngineOptions, LaunchOptions, PackId, Setting,
};

struct Harness {
    _rt: tokio::runtime::Runtime,
    engine: Engine,
    _dir: tempfile::TempDir,
}

fn harness(mode: GameMode) -> Harness {
    let dir = tempfile::tempdir().unwrap();
    let rt = tokio::runtime::Builder::new_multi_thread()
        .worker_threads(2)
        .enable_all()
        .build()
        .unwrap();
    // Network-free: OfflineHttp answers every request (and the connectivity probe) at once.
    let o = EngineOptions::offline_for_tests(
        DataPaths::at_root(dir.path().join("data"), mode),
        rt.handle().clone(),
    );
    let engine = Engine::new(o).unwrap();
    Harness {
        _rt: rt,
        engine,
        _dir: dir,
    }
}

impl Harness {
    fn run<T: Send + 'static>(
        &self,
        f: impl FnOnce(Engine) -> futures::future::BoxFuture<'static, T>,
    ) -> T {
        let fut = f(self.engine.clone());
        futures::executor::block_on(self.engine.spawn(fut)).unwrap()
    }
}

fn create(h: &Harness, name: &str) -> PackId {
    let n = name.to_string();
    h.run(move |e| Box::pin(async move { e.create_pack(&n, Some("fabric"), None).await }))
        .unwrap()
}

#[test]
fn an_authored_pack_can_be_created_edited_and_forked() {
    let h = harness(GameMode::Client);
    let id = create(&h, "Test Pack");
    let e = &h.engine;
    let summary = e.pack(&id).expect("installed");
    assert_eq!(summary.name, "Test Pack");
    assert!(e.is_authored_pack(&id));
    let authored = e.paths().mcp_manifests_dir().join("created-testpack.json");
    assert!(authored.is_file());

    // Add a mod; a second one at the same path is refused without writing.
    let id2 = id.clone();
    h.run(move |e| {
        Box::pin(async move {
            e.add_mod(&id2, "JEI", "https://example.com/jei.jar", "mods/jei.jar")
                .await
        })
    })
    .unwrap();
    let text = e.pack_manifest_text(&id).unwrap_or_default();
    let on_disk = std::fs::read_to_string(&authored).unwrap();
    assert!(on_disk.contains("mods/jei.jar"), "{on_disk}");
    let _ = text;
    let id2 = id.clone();
    let dup = h.run(move |e| {
        Box::pin(async move {
            e.add_mod(
                &id2,
                "JEI 2",
                "https://example.com/jei2.jar",
                "MODS/JEI.JAR",
            )
            .await
        })
    });
    assert!(matches!(dup, Err(EngineError::Other(_))));
    let id2 = id.clone();
    let unsafe_path = h.run(move |e| {
        Box::pin(async move {
            e.add_mod(&id2, "x", "https://example.com/x.jar", "../x.jar")
                .await
        })
    });
    assert!(unsafe_path.is_err());

    // Remove it by name; removing again finds nothing.
    let id2 = id.clone();
    h.run(move |e| Box::pin(async move { e.remove_mod(&id2, "jei").await }))
        .unwrap();
    assert!(
        !std::fs::read_to_string(&authored)
            .unwrap()
            .contains("jei.jar")
    );
    let id2 = id.clone();
    assert!(
        h.run(move |e| Box::pin(async move { e.remove_mod(&id2, "jei").await }))
            .is_err()
    );

    // Fork: a second authored pack with the minor version bumped.
    let id2 = id.clone();
    let fork = h
        .run(move |e| Box::pin(async move { e.fork_pack(&id2, "Test Pack Copy").await }))
        .unwrap();
    assert_ne!(fork, id);
    assert!(e.is_authored_pack(&fork));
    assert_eq!(e.installed_packs().len(), 2);

    // A name whose file key is taken by an installed pack is refused.
    let clash = h.run(|e| Box::pin(async move { e.create_pack("test-pack", None, None).await }));
    assert!(clash.is_err());
    // Unknown loaders and blank names are refused.
    assert!(
        h.run(|e| Box::pin(async move { e.create_pack("Other", Some("quilt"), None).await }))
            .is_err()
    );
    assert!(
        h.run(|e| Box::pin(async move { e.create_pack("  ", None, None).await }))
            .is_err()
    );
}

#[test]
fn an_edited_manifest_saves_back_to_its_file() {
    let h = harness(GameMode::Client);
    let id = create(&h, "Saver");
    let file = h
        .engine
        .paths()
        .mcp_manifests_dir()
        .join("created-saver.json");
    let mut doc: serde_json::Value =
        serde_json::from_str(&std::fs::read_to_string(&file).unwrap()).unwrap();
    doc["packVersion"] = serde_json::Value::from("2.0.0");
    let json = doc.to_string();
    let id2 = id.clone();
    h.run(move |e| Box::pin(async move { e.save_manifest(&id2, &json).await }))
        .unwrap();
    assert!(std::fs::read_to_string(&file).unwrap().contains("2.0.0"));
    let id2 = id.clone();
    assert!(
        h.run(move |e| Box::pin(async move { e.save_manifest(&id2, "not json").await }))
            .is_err()
    );
}

#[test]
fn launching_without_an_account_is_refused() {
    let h = harness(GameMode::Client);
    let id = create(&h, "No Account");
    let id2 = id.clone();
    let err = h
        .run(move |e| Box::pin(async move { e.launch(&id2, LaunchOptions::default()).await }))
        .unwrap_err();
    assert!(
        matches!(err, EngineError::Account(mmcl_auth::Problem::NoAccount)),
        "{err:?}"
    );
    assert!(h.engine.sessions().is_empty());
    assert!(h.engine.why_launch_blocked(&id, None).is_some());
}

#[test]
fn run_server_needs_server_mode() {
    let h = harness(GameMode::Client);
    let id = create(&h, "Not A Server");
    let err = h
        .run(move |e| Box::pin(async move { e.run_server(&id).await }))
        .unwrap_err();
    assert!(matches!(err, EngineError::Other(_)));
    let err = h
        .run(|e| Box::pin(async move { e.run_server(&PackId::new("nope")).await }))
        .unwrap_err();
    assert!(matches!(
        err,
        EngineError::Other(_) | EngineError::PackNotInstalled(_)
    ));
}

#[test]
fn a_backup_can_be_taken_and_restored() {
    let h = harness(GameMode::Client);
    let id = create(&h, "Backed Up");
    let root = h
        .engine
        .pack_folder(&id, mmcl_engine::PackFolder::Root)
        .unwrap();
    std::fs::create_dir_all(root.join("config")).unwrap();
    std::fs::write(root.join("config/a.cfg"), "original").unwrap();
    let id2 = id.clone();
    let zip = h
        .run(move |e| Box::pin(async move { e.backup_now(&id2).await }))
        .unwrap()
        .expect("a backup");
    std::fs::write(root.join("config/a.cfg"), "changed").unwrap();
    let id2 = id.clone();
    let listed = h
        .run(move |e| Box::pin(async move { e.backups(&id2).await }))
        .unwrap();
    assert_eq!(listed.len(), 1);
    let id2 = id.clone();
    let z = zip.clone();
    h.run(move |e| Box::pin(async move { e.restore_backup(&id2, &z).await }))
        .unwrap();
    assert_eq!(
        std::fs::read_to_string(root.join("config/a.cfg")).unwrap(),
        "original"
    );
    // A zip outside the pack's backups folder is refused.
    let outside = root.join("elsewhere.zip");
    std::fs::copy(&zip, &outside).unwrap();
    let id2 = id.clone();
    assert!(
        h.run(move |e| Box::pin(async move { e.restore_backup(&id2, &outside).await }))
            .is_err()
    );
}

#[test]
fn verifying_and_updating_unknown_packs_report_not_installed() {
    let h = harness(GameMode::Client);
    let bad = PackId::new("https://example.invalid/none.json");
    let b = bad.clone();
    assert!(matches!(
        h.run(move |e| Box::pin(async move { e.verify_pack(&b).await })),
        Err(EngineError::PackNotInstalled(_))
    ));
    let b = bad.clone();
    assert!(matches!(
        h.run(move |e| Box::pin(async move { e.update_pack(&b).await })),
        Err(EngineError::PackNotInstalled(_))
    ));
    // Nothing installed: verify-all is a no-op that says so.
    let mut rx = h.engine.subscribe();
    h.run(|e| Box::pin(async move { e.verify_all_packs().await }))
        .unwrap();
    let mut saw_info = false;
    while let Ok(ev) = rx.try_recv() {
        if matches!(ev, EngineEvent::Notification(_)) {
            saw_info = true;
        }
    }
    assert!(saw_info);
}

#[test]
fn settings_helpers_work_offline() {
    let h = harness(GameMode::Client);
    let args = h.engine.hardware_tuned_jvm_args(6.0).unwrap();
    assert!(args.contains("-XX:+UseG1GC"));
    assert!(h.engine.hardware_tuned_jvm_args(0.0).is_err());
    assert!(!h.engine.hardware_tuning_summary(6.0).is_empty());
    let mem = h
        .run(|e| Box::pin(async move { e.system_memory().await }))
        .unwrap();
    assert!(mem.total_gb > 0.0);
    assert!(mem.used_gb <= mem.total_gb);
    h.engine.set(Setting::UiScalePercent(125)).unwrap();
    assert_eq!(h.engine.settings().ui_scale_percent, 125);
}

#[test]
fn a_clean_folder_scans_clean() {
    let h = harness(GameMode::Client);
    let folder = tempfile::tempdir().unwrap();
    std::fs::write(folder.path().join("readme.txt"), "hello").unwrap();
    let p = folder.path().to_path_buf();
    let summary = h
        .run(move |e| Box::pin(async move { e.scan_folder(&p).await }))
        .unwrap();
    assert_eq!(summary.stage1, 0);
    assert_eq!(summary.high, 0);
    assert!(!summary.cancelled);
}

#[test]
fn curseforge_urls_are_not_imported_directly() {
    let h = harness(GameMode::Client);
    let err = h
        .run(|e| {
            Box::pin(async move {
                e.import(mmcl_engine::ImportRequest::Url(
                    "https://www.curseforge.com/minecraft/modpacks/some-pack".into(),
                ))
                .await
            })
        })
        .unwrap_err();
    assert!(matches!(err, EngineError::Other(_)));
}

#[test]
fn reset_deletes_the_data_folder_and_asks_for_a_restart() {
    let h = harness(GameMode::Client);
    let _ = create(&h, "Doomed");
    let root = h.engine.paths().root().to_path_buf();
    assert!(root.is_dir());
    let mut rx = h.engine.subscribe();
    h.run(|e| Box::pin(async move { e.reset_launcher().await }))
        .unwrap();
    assert!(!root.exists());
    assert!(h.engine.is_shut_down());
    let mut restart = false;
    let deadline = std::time::Instant::now() + Duration::from_secs(2);
    while std::time::Instant::now() < deadline {
        match rx.try_recv() {
            Ok(EngineEvent::RestartRequested) => {
                restart = true;
                break;
            }
            Ok(_) => {}
            Err(_) => break,
        }
    }
    assert!(restart);
    let parent = root.parent().unwrap();
    let leftovers: Vec<_> = std::fs::read_dir(parent)
        .unwrap()
        .flatten()
        .filter(|e| e.file_name().to_string_lossy().contains(".reset-"))
        .collect();
    assert!(leftovers.is_empty());
    let _ = Path::new("");
}

/// Seeds the manifest cache with `body` for `url`, so an offline engine can load it.
fn seed_cache(h: &Harness, url: &str, body: &str) {
    let dir = h.engine.paths().manifest_cache_dir();
    std::fs::create_dir_all(&dir).unwrap();
    let name = format!("{}.json", mmcl_util::hash::sha256_hex(url.as_bytes()));
    std::fs::write(dir.join(name), body).unwrap();
}

const SITE_URL: &str = "https://packs.example/site/manifest.json";
const SITE_MANIFEST: &str = r#"{"packName":"Site Pack","packVersion":"1.0","packURL":"https://site.example/pack","packCustomDiscordRpc":true,"packModLoader":"fabric","packMods":[]}"#;

#[test]
fn summaries_carry_the_website_and_the_discord_flag() {
    let h = harness(GameMode::Client);
    seed_cache(&h, SITE_URL, SITE_MANIFEST);
    let id = h
        .run(|e| Box::pin(async move { e.install_pack_by_url(SITE_URL).await }))
        .unwrap();
    let s = h.engine.pack(&id).expect("installed");
    assert_eq!(s.website.as_deref(), Some("https://site.example/pack"));
    assert!(s.custom_discord_rpc);
    // No image was cached offline, so there is no logo file and no image to cycle.
    assert_eq!(s.logo_path, None);
    let images = h.engine.pack_images(&id);
    assert!(images.logos.is_empty() && !images.cached);
    let id2 = id.clone();
    let cached = h
        .run(move |e| Box::pin(async move { e.cache_pack_images(&id2).await }))
        .unwrap();
    assert!(!cached.cached);
    assert!(
        h.engine
            .pack_images(&PackId::new("https://nope.example/x.json"))
            .logos
            .is_empty()
    );
}

#[test]
fn manifest_text_reads_installed_and_cached_remote_manifests_only() {
    let h = harness(GameMode::Client);
    // A remote manifest that is only in the cache (an available pack's fetch leaves this).
    seed_cache(&h, SITE_URL, SITE_MANIFEST);
    assert_eq!(
        h.engine
            .pack_manifest_text(&PackId::new(SITE_URL))
            .as_deref(),
        Some(SITE_MANIFEST)
    );
    let async_text =
        h.run(|e| Box::pin(async move { e.manifest_text(&PackId::new(SITE_URL)).await }));
    assert_eq!(async_text.as_deref(), Some(SITE_MANIFEST));
    // A raw local file id is never read (MCP passes ids through).
    let secret = h.engine.paths().root().join("secret.json");
    std::fs::create_dir_all(secret.parent().unwrap()).unwrap();
    std::fs::write(&secret, "{}").unwrap();
    let file_id = PackId::new(mmcl_modpack::fetch::path_to_file_url(&secret));
    assert_eq!(h.engine.pack_manifest_text(&file_id), None);
    assert_eq!(
        h.engine.pack_manifest_text(&PackId::vanilla("1.20.1")),
        None
    );
    // An installed authored pack reads its own file.
    let id = create(&h, "Texty");
    assert!(h.engine.pack_manifest_text(&id).unwrap().contains("Texty"));
}

#[test]
fn vanilla_versions_are_packs() {
    let h = harness(GameMode::Client);
    let id = h
        .run(|e| Box::pin(async move { e.install_vanilla("1.20.1").await }))
        .unwrap();
    assert_eq!(id, PackId::vanilla("1.20.1"));
    let all = h.engine.installed_packs();
    let v = all.iter().find(|p| p.id == id).expect("vanilla listed");
    assert!(v.is_vanilla && v.installed);
    assert_eq!(v.minecraft_version.as_deref(), Some("1.20.1"));
    assert!(h.engine.installed_modpacks().iter().all(|p| !p.is_vanilla));
    assert!(h.engine.pack(&id).is_some());
    let id2 = id.clone();
    let details = h
        .run(move |e| Box::pin(async move { e.pack_details(&id2).await }))
        .unwrap();
    assert!(details.summary.is_vanilla);
    // Launching now reaches the account check instead of "not installed".
    let id2 = id.clone();
    let err = h
        .run(move |e| Box::pin(async move { e.launch(&id2, LaunchOptions::default()).await }))
        .unwrap_err();
    assert!(
        matches!(err, EngineError::Account(mmcl_auth::Problem::NoAccount)),
        "{err:?}"
    );
    // The official export status of a vanilla pack needs no loader.
    let id2 = id.clone();
    let status = h
        .run(move |e| Box::pin(async move { e.official_export_status(&id2).await }))
        .unwrap();
    assert!(status.vanilla && status.loader_installed);
    assert_eq!(status.version_id, "1.20.1");
    // Uninstall removes it from the library and the config, and deletes its folder.
    let root = h
        .engine
        .pack_folder(&id, mmcl_engine::PackFolder::Root)
        .unwrap();
    std::fs::create_dir_all(root.join("saves")).unwrap();
    let id2 = id.clone();
    h.run(move |e| Box::pin(async move { e.uninstall_pack(&id2, true).await }))
        .unwrap();
    assert!(h.engine.pack(&id).is_none());
    assert!(!root.exists());
    assert!(h.engine.config().installed_vanilla_versions().is_empty());
}

#[test]
fn a_loader_version_installs_as_an_empty_pack() {
    let h = harness(GameMode::Client);
    let v = mmcl_engine::LoaderVersionView {
        loader_type: "fabric".into(),
        minecraft_version: "1.21.5".into(),
        loader_version: "0.16.10".into(),
        display_name: "Fabric 1.21.5 (0.16.10)".into(),
        installed: false,
    };
    let v2 = v.clone();
    let id = h
        .run(move |e| Box::pin(async move { e.install_loader_version(&v2).await }))
        .unwrap();
    let s = h.engine.pack(&id).expect("installed");
    assert_eq!(s.name, "Fabric 1.21.5 (0.16.10)");
    assert_eq!(s.mod_loader.as_deref(), Some("fabric"));
    assert!(
        h.engine
            .paths()
            .imported_manifests_dir()
            .join("loader-fabric-1.21.5-0.16.10.json")
            .is_file()
    );
    let mut bad = v;
    bad.loader_type = "quilt".into();
    assert!(
        h.run(move |e| Box::pin(async move { e.install_loader_version(&bad).await }))
            .is_err()
    );
}

#[test]
fn a_modrinth_preview_needs_a_modrinth_url_and_finds_nothing_offline() {
    let h = harness(GameMode::Client);
    assert!(
        h.run(|e| Box::pin(async move { e.preview_modrinth("https://example.com/x").await }))
            .is_err()
    );
    let found = h
        .run(|e| {
            Box::pin(async move {
                e.preview_modrinth("https://modrinth.com/modpack/fabulously-optimized")
                    .await
            })
        })
        .unwrap();
    assert_eq!(found, None);
    let no_file = mmcl_engine::ModrinthProjectView {
        slug: "x".into(),
        ..Default::default()
    };
    assert!(
        h.run(move |e| Box::pin(async move { e.install_modrinth(&no_file).await }))
            .is_err()
    );
}

#[test]
fn operations_report_under_a_caller_supplied_id() {
    let h = harness(GameMode::Client);
    let op = h.engine.next_operation_id();
    let mut rx = h.engine.subscribe();
    let bad = PackId::new("https://example.invalid/none.json");
    let res = h.run(move |e| Box::pin(async move { e.verify_pack_with(&bad, op).await }));
    assert!(matches!(res, Err(EngineError::PackNotInstalled(_))));
    let mut finished = false;
    while let Ok(ev) = rx.try_recv() {
        if matches!(ev, EngineEvent::OperationFinished(o) if o == op) {
            finished = true;
        }
    }
    assert!(finished, "a refused operation still ends its id");

    // The malware scan reports progress under the id it was given.
    let folder = tempfile::tempdir().unwrap();
    std::fs::write(folder.path().join("a.txt"), "x").unwrap();
    let p = folder.path().to_path_buf();
    let op = h.engine.next_operation_id();
    let mut rx = h.engine.subscribe();
    let summary = h
        .run(move |e| Box::pin(async move { e.scan_folder_with(&p, op).await }))
        .unwrap();
    assert!(!summary.cancelled);
    let (mut progress, mut done) = (false, false);
    while let Ok(ev) = rx.try_recv() {
        match ev {
            EngineEvent::Progress(p) if p.operation == op => progress = true,
            EngineEvent::OperationFinished(o) if o == op => done = true,
            _ => {}
        }
    }
    assert!(progress && done);
}
