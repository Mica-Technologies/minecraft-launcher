// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! End-to-end engine tests over a temporary data folder, with no network: authored packs,
//! manifest edits, backups, refusals, server-mode guard, settings helpers and reset.

use std::path::Path;
use std::time::Duration;

use mmcl_core::{DataPaths, GameMode};
use mmcl_engine::{
    Engine, EngineError, EngineEvent, EngineOptions, LaunchOptions, PackId, RuntimeChoice, Setting,
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
    let mut o = EngineOptions::new(DataPaths::at_root(dir.path().join("data"), mode));
    o.runtime = RuntimeChoice::Handle(rt.handle().clone());
    o.start_background_work = false;
    o.follow_running_games = false;
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
