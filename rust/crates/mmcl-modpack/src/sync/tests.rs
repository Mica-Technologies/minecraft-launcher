// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

use super::*;
use crate::http::fake::FakeHttp;
use mmcl_core::{DataPaths, GameMode};
use mmcl_manifest::PackManifest;
use mmcl_progress::NullSink;

fn modf(local: &str, body: &[u8], client: bool, server: bool) -> ManifestFile {
    ManifestFile {
        name: Some(local.into()),
        local: Some(local.into()),
        remote: Some(format!("https://cdn.example/{local}")),
        sha256: Some(mmcl_util::hash::sha256_hex(body)),
        client_req: client,
        server_req: server,
        ..Default::default()
    }
}

fn pack(root: &Path, mode: GameMode, m: PackManifest) -> Arc<GameModPack> {
    let p = GameModPack::from_manifest(
        Arc::new(DataPaths::at_root(root, mode)),
        m,
        Some("https://x/m".into()),
        None,
    );
    p.prepare_environment().unwrap();
    p
}

fn env(side: Side, http: Arc<FakeHttp>) -> SyncEnv {
    let mut e = SyncEnv::new(side, http, Arc::new(NullSink), Default::default());
    e.pool = DownloadPool::with_size(4);
    e
}

fn named(name: &str) -> PackManifest {
    PackManifest {
        pack_name: Some(name.into()),
        pack_version: Some("1".into()),
        ..Default::default()
    }
}

// ---- GameModSideTest ----

#[tokio::test]
async fn side_matrix_and_skip() {
    let d = tempfile::tempdir().unwrap();
    let both = modf("b.jar", b"b", true, true);
    let client = modf("c.jar", b"c", true, false);
    let server = modf("s.jar", b"s", false, true);
    let none = modf("n.jar", b"n", false, false);
    assert!(both.is_required_for(Side::Client) && both.is_required_for(Side::Server));
    assert!(client.is_required_for(Side::Client) && !client.is_required_for(Side::Server));
    assert!(!server.is_required_for(Side::Client) && server.is_required_for(Side::Server));
    assert!(!none.is_required_for(Side::Client) && !none.is_required_for(Side::Server));
    // A skipped side reports success without downloading.
    let http = FakeHttp::new();
    let f = ManagedFile::new(server, d.path(), d.path());
    assert!(
        f.update_for(Side::Client, &PrepareContext::NONE, http.as_ref())
            .await
            .unwrap()
    );
    assert_eq!(http.log.lock().len(), 0);
}

// ---- IncompleteManifestLaunchGuardTest (sync part) ----

#[tokio::test]
async fn failed_and_stub_never_sweep() {
    let d = tempfile::tempdir().unwrap();
    let paths = Arc::new(DataPaths::at_root(d.path(), GameMode::Client));
    let e = crate::index::IndexEntry {
        pack_name: Some("Alto".into()),
        ..Default::default()
    };
    let stub = GameModPack::stub_from_index(paths.clone(), "https://x/a", &e);
    let mods = stub.mods_dir();
    fs::create_dir_all(&mods).unwrap();
    fs::write(mods.join("keep.jar"), "x").unwrap();
    let http = FakeHttp::new();
    let s = FileSync::new(stub, env(Side::Client, http.clone()));
    assert!(matches!(
        s.fetch_latest_mods().await,
        Err(ModpackError::ManifestStub(_))
    ));
    assert!(matches!(
        s.clear_floating_mods(),
        Err(ModpackError::ManifestStub(_))
    ));
    let failed = GameModPack::failed(paths, "https://x/a", "down");
    let s = FileSync::new(failed, env(Side::Client, http));
    let r = s.fetch_latest_mods().await;
    assert!(matches!(r, Err(ref e) if e.is_incomplete_manifest()));
    assert!(mods.join("keep.jar").exists());
}

#[tokio::test]
async fn single_mod_pack_downloads_and_sweep_keeps_required() {
    let d = tempfile::tempdir().unwrap();
    let m = PackManifest {
        pack_mods: Some(vec![modf("only.jar", b"only", true, true)]),
        ..named("One")
    };
    let p = pack(d.path(), GameMode::Client, m);
    let mods = p.mods_dir();
    fs::write(mods.join("floating.jar"), "x").unwrap();
    fs::create_dir_all(mods.join("subdir")).unwrap();
    let http = FakeHttp::new();
    http.body("https://cdn.example/only.jar", b"only".to_vec());
    FileSync::new(p.clone(), env(Side::Client, http.clone()))
        .fetch_latest_mods()
        .await
        .unwrap();
    assert_eq!(fs::read(mods.join("only.jar")).unwrap(), b"only");
    assert!(!mods.join("floating.jar").exists());
    assert!(mods.join("subdir").is_dir(), "directories are left alone");
    assert_eq!(http.count("DL"), 1);
}

#[tokio::test]
async fn null_mod_list_never_sweeps_empty_list_does() {
    let d = tempfile::tempdir().unwrap();
    let p = pack(d.path(), GameMode::Client, named("Null"));
    fs::write(p.mods_dir().join("mine.jar"), "x").unwrap();
    FileSync::new(p.clone(), env(Side::Client, FakeHttp::new()))
        .fetch_latest_mods()
        .await
        .unwrap();
    assert!(p.mods_dir().join("mine.jar").exists());
    let e = pack(
        d.path(),
        GameMode::Client,
        PackManifest {
            pack_mods: Some(vec![]),
            ..named("Null")
        },
    );
    FileSync::new(e.clone(), env(Side::Client, FakeHttp::new()))
        .fetch_latest_mods()
        .await
        .unwrap();
    assert!(!e.mods_dir().join("mine.jar").exists());
}

#[tokio::test]
async fn disabled_managed_mod_stays_disabled() {
    let d = tempfile::tempdir().unwrap();
    let m = PackManifest {
        pack_mods: Some(vec![modf("opt.jar", b"opt", true, true)]),
        ..named("Dis")
    };
    let p = pack(d.path(), GameMode::Client, m);
    fs::write(p.mods_dir().join("opt.jar.disabled"), "opt").unwrap();
    fs::write(p.mods_dir().join("other.jar.disabled"), "x").unwrap();
    let http = FakeHttp::new();
    FileSync::new(p.clone(), env(Side::Client, http.clone()))
        .fetch_latest_mods()
        .await
        .unwrap();
    assert!(p.mods_dir().join("opt.jar.disabled").exists());
    assert!(!p.mods_dir().join("opt.jar").exists(), "not re-enabled");
    assert!(
        !p.mods_dir().join("other.jar.disabled").exists(),
        "unmanaged disabled jars are swept"
    );
    assert_eq!(http.count("DL"), 0);
}

#[tokio::test]
async fn an_enabled_copy_beside_a_disabled_one_is_still_verified() {
    let d = tempfile::tempdir().unwrap();
    let m = PackManifest {
        pack_mods: Some(vec![modf("opt.jar", b"opt", true, true)]),
        ..named("Both")
    };
    let p = pack(d.path(), GameMode::Client, m);
    fs::write(p.mods_dir().join("opt.jar.disabled"), "opt").unwrap();
    // A tampered enabled copy is what the game would load, so it must not ride on the
    // disabled copy's exemption.
    fs::write(p.mods_dir().join("opt.jar"), "tampered").unwrap();
    let http = FakeHttp::new();
    http.body("https://cdn.example/opt.jar", b"opt".to_vec());
    FileSync::new(p.clone(), env(Side::Client, http.clone()))
        .fetch_latest_mods()
        .await
        .unwrap();
    assert_eq!(http.count("DL"), 1, "the bad enabled copy is re-fetched");
    assert_eq!(fs::read(p.mods_dir().join("opt.jar")).unwrap(), b"opt");
    assert!(p.mods_dir().join("opt.jar.disabled").exists());
}

#[cfg(any(windows, target_os = "macos"))]
#[tokio::test]
async fn case_difference_is_not_swept() {
    let d = tempfile::tempdir().unwrap();
    let m = PackManifest {
        pack_mods: Some(vec![modf("JEI.jar", b"j", true, true)]),
        ..named("Case")
    };
    let p = pack(d.path(), GameMode::Client, m);
    fs::write(p.mods_dir().join("jei.jar"), "j").unwrap();
    let http = FakeHttp::new();
    FileSync::new(p.clone(), env(Side::Client, http.clone()))
        .fetch_latest_mods()
        .await
        .unwrap();
    assert_eq!(http.count("DL"), 0);
}

#[tokio::test]
async fn other_side_mods_are_swept_and_not_required() {
    let d = tempfile::tempdir().unwrap();
    let m = PackManifest {
        pack_mods: Some(vec![
            modf("client.jar", b"c", true, false),
            modf("server.jar", b"s", false, true),
        ]),
        ..named("Sides")
    };
    let p = pack(d.path(), GameMode::Server, m);
    fs::write(p.mods_dir().join("client.jar"), "c").unwrap();
    let http = FakeHttp::new();
    http.body("https://cdn.example/server.jar", b"s".to_vec());
    FileSync::new(p.clone(), env(Side::Server, http))
        .fetch_latest_mods()
        .await
        .unwrap();
    assert!(!p.mods_dir().join("client.jar").exists());
    assert!(p.mods_dir().join("server.jar").exists());
}

#[tokio::test]
async fn hash_mismatch_fails_the_mod_sync() {
    let d = tempfile::tempdir().unwrap();
    let m = PackManifest {
        pack_mods: Some(vec![modf("bad.jar", b"good", true, true)]),
        ..named("Bad")
    };
    let p = pack(d.path(), GameMode::Client, m);
    let http = FakeHttp::new();
    http.body("https://cdn.example/bad.jar", b"evil".to_vec());
    let r = FileSync::new(p, env(Side::Client, http.clone()))
        .fetch_latest_mods()
        .await;
    assert!(matches!(r, Err(ModpackError::ModsFailed(_))), "{r:?}");
    assert_eq!(http.count("DL"), 3);
}

#[tokio::test]
async fn imported_packs_skip_mod_sync() {
    let d = tempfile::tempdir().unwrap();
    let m = PackManifest {
        pack_imported_skip_sync: true,
        pack_mods: Some(vec![]),
        ..named("Imp")
    };
    let p = pack(d.path(), GameMode::Client, m);
    fs::write(p.mods_dir().join("mine.jar"), "x").unwrap();
    FileSync::new(p.clone(), env(Side::Client, FakeHttp::new()))
        .fetch_latest_mods()
        .await
        .unwrap();
    assert!(p.mods_dir().join("mine.jar").exists());
}

#[tokio::test]
async fn server_mode_skips_resource_and_shader_packs() {
    let d = tempfile::tempdir().unwrap();
    let rp = ManifestFile {
        local: Some("rp.zip".into()),
        remote: Some("https://cdn.example/rp.zip".into()),
        ..Default::default()
    };
    let m = PackManifest {
        pack_resource_packs: Some(vec![rp.clone()]),
        pack_shader_packs: Some(vec![rp]),
        pack_configs: Some(vec![modf("a.cfg", b"cfg", true, false)]),
        pack_initial_files: Some(vec![modf("options.txt", b"o", true, true)]),
        pack_mods: Some(vec![]),
        ..named("Res")
    };
    let http = FakeHttp::new();
    http.body("https://cdn.example/rp.zip", b"rp".to_vec());
    http.body("https://cdn.example/a.cfg", b"cfg".to_vec());
    http.body("https://cdn.example/options.txt", b"o".to_vec());
    let srv = pack(d.path(), GameMode::Server, m.clone());
    FileSync::new(srv.clone(), env(Side::Server, http.clone()))
        .sync_all()
        .await
        .unwrap();
    assert_eq!(http.count("rp.zip"), 0);
    assert!(
        !srv.pack_root().join("config/a.cfg").exists(),
        "client-only config skipped on a server"
    );
    assert!(srv.pack_root().join("options.txt").exists());
    let d2 = tempfile::tempdir().unwrap();
    let cli = pack(d2.path(), GameMode::Client, m);
    FileSync::new(cli.clone(), env(Side::Client, http.clone()))
        .sync_all()
        .await
        .unwrap();
    assert!(cli.pack_root().join("resourcepacks/rp.zip").exists());
    assert!(cli.pack_root().join("shaderpacks/rp.zip").exists());
    assert!(cli.pack_root().join("config/a.cfg").exists());
}

#[tokio::test]
async fn missing_required_mod_after_sync_is_an_error() {
    let d = tempfile::tempdir().unwrap();
    // A local-only mod that is missing fails inside the pool; a present one passes the check.
    let mut lo = modf("local.jar", b"l", true, true);
    lo.remote = Some(String::new());
    lo.sha256 = None;
    let m = PackManifest {
        pack_mods: Some(vec![lo]),
        ..named("Miss")
    };
    let p = pack(d.path(), GameMode::Client, m);
    let r = FileSync::new(p.clone(), env(Side::Client, FakeHttp::new()))
        .fetch_latest_mods()
        .await;
    assert!(matches!(r, Err(ModpackError::ModsFailed(_))));
    fs::write(p.mods_dir().join("local.jar"), "l").unwrap();
    FileSync::new(p.clone(), env(Side::Client, FakeHttp::new()))
        .fetch_latest_mods()
        .await
        .unwrap();
}

// ---- optional mods (issue #103 acceptance 2-4, minus the game) ----

fn optional_pack() -> PackManifest {
    let sha = mmcl_util::hash::sha256_hex(b"ausm");
    let text = format!(
        r#"{{"packName":"Alto","packVersion":"1","packMods":[{{"name":"JEI","local":"jei.jar",
            "remote":"https://cdn.example/jei.jar","sha256":"{}","clientReq":true,"serverReq":true}}],
            "packOptionalMods":[{{"name":"AUSM","local":"ausm.jar",
            "remote":"https://cdn.example/ausm.jar","sha256":"{sha}"}}]}}"#,
        mmcl_util::hash::sha256_hex(b"jei")
    );
    PackManifest::parse(&text).unwrap()
}

fn optional_env(side: Side, http: Arc<FakeHttp>, on: Option<bool>) -> SyncEnv {
    let mut e = env(side, http);
    if let Some(on) = on {
        e.optional_choices.insert("ausm.jar".into(), on);
    }
    e
}

#[tokio::test]
async fn an_optional_mod_installs_when_on_and_is_swept_when_off() {
    let d = tempfile::tempdir().unwrap();
    let p = pack(d.path(), GameMode::Client, optional_pack());
    let mods = p.mods_dir();
    let http = FakeHttp::new();
    http.body("https://cdn.example/jei.jar", b"jei".to_vec());
    http.body("https://cdn.example/ausm.jar", b"ausm".to_vec());

    // Off by default: not downloaded, and the sidecar records it.
    let sync = FileSync::new(p.clone(), optional_env(Side::Client, http.clone(), None));
    assert_eq!(sync.synced_mods().len(), 1);
    sync.fetch_latest_mods().await.unwrap();
    assert!(!mods.join("ausm.jar").exists());
    assert_eq!(
        fs::read_to_string(
            p.pack_root()
                .join(crate::optional::KNOWN_OPTIONAL_MODS_FILE)
        )
        .unwrap(),
        "ausm.jar"
    );

    // On: downloaded with the right hash and kept.
    FileSync::new(
        p.clone(),
        optional_env(Side::Client, http.clone(), Some(true)),
    )
    .fetch_latest_mods()
    .await
    .unwrap();
    assert_eq!(fs::read(mods.join("ausm.jar")).unwrap(), b"ausm");

    // Off again: the sweep removes it; the required mod stays.
    FileSync::new(
        p.clone(),
        optional_env(Side::Client, http.clone(), Some(false)),
    )
    .fetch_latest_mods()
    .await
    .unwrap();
    assert!(!mods.join("ausm.jar").exists());
    assert!(mods.join("jei.jar").exists());
}

#[tokio::test]
async fn a_server_ignores_the_players_optional_choices() {
    let d = tempfile::tempdir().unwrap();
    let p = pack(d.path(), GameMode::Server, optional_pack());
    let http = FakeHttp::new();
    http.body("https://cdn.example/jei.jar", b"jei".to_vec());
    let sync = FileSync::new(
        p.clone(),
        optional_env(Side::Server, http.clone(), Some(true)),
    );
    assert_eq!(
        sync.synced_mods().len(),
        1,
        "client-only and off by default"
    );
    sync.fetch_latest_mods().await.unwrap();
    assert!(!p.mods_dir().join("ausm.jar").exists());
}

#[tokio::test]
async fn a_pack_without_optional_mods_gets_no_sidecar() {
    let d = tempfile::tempdir().unwrap();
    let m = PackManifest {
        pack_mods: Some(vec![modf("only.jar", b"only", true, true)]),
        ..named("One")
    };
    let p = pack(d.path(), GameMode::Client, m);
    let http = FakeHttp::new();
    http.body("https://cdn.example/only.jar", b"only".to_vec());
    FileSync::new(p.clone(), env(Side::Client, http))
        .fetch_latest_mods()
        .await
        .unwrap();
    assert!(
        !p.pack_root()
            .join(crate::optional::KNOWN_OPTIONAL_MODS_FILE)
            .exists()
    );
}
