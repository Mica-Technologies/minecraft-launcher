// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

use std::sync::atomic::{AtomicBool, Ordering};

use mmcl_core::GameMode;
use mmcl_manifest::PackManifest;
use mmcl_mojang::host::presets;
use mmcl_progress::NullSink;

use super::*;
use crate::test_support::*;

// ---- GameModPackLauncherBranchesTest ---------------------------------------------------------

#[tokio::test]
async fn abort_cancels_a_running_branch_and_waits_for_it() {
    let launch = CancelToken::new();
    let mut b: Branches<u32> = Branches::new(&launch);
    let stopped = Arc::new(AtomicBool::new(false));
    let (tok, s) = (b.token(), stopped.clone());
    b.spawn(async move {
        tok.cancelled().await;
        tokio::time::sleep(Duration::from_millis(30)).await;
        s.store(true, Ordering::SeqCst);
        Err(LaunchError::Cancelled)
    });
    b.spawn(async { Err(lerr!("launch.error.contentSyncFailed", "boom")) });
    let e = b.join().await.unwrap_err();
    assert_eq!(e.key(), "launch.error.contentSyncFailed");
    assert!(
        stopped.load(Ordering::SeqCst),
        "the sibling stopped before join returned"
    );
    assert!(!launch.is_cancelled(), "the caller's token is untouched");
}

#[tokio::test]
async fn an_uncancelled_branch_returns_its_result_in_order() {
    let launch = CancelToken::new();
    let mut b: Branches<u32> = Branches::new(&launch);
    b.spawn(async {
        tokio::time::sleep(Duration::from_millis(20)).await;
        Ok(1)
    });
    b.spawn(async { Ok(2) });
    assert_eq!(b.join().await.unwrap(), vec![1, 2]);
}

#[tokio::test]
async fn a_launch_cancel_reaches_the_branches_and_reads_as_pre_launch_cancelled() {
    let launch = CancelToken::new();
    let mut b: Branches<u32> = Branches::new(&launch);
    let tok = b.token();
    b.spawn(async move {
        tok.cancelled().await;
        Err(LaunchError::Cancelled)
    });
    let l = launch.clone();
    tokio::spawn(async move {
        tokio::time::sleep(Duration::from_millis(20)).await;
        l.cancel();
    });
    let e = b.join().await.unwrap_err();
    assert!(matches!(e, LaunchError::PreLaunchCancelled));
    assert!(e.is_cancelled());
}

#[tokio::test]
async fn a_branch_that_ignores_cancel_is_abandoned_after_the_wait() {
    let launch = CancelToken::new();
    let mut b: Branches<u32> = Branches::new(&launch);
    b.spawn(async {
        tokio::time::sleep(Duration::from_secs(60)).await;
        Ok(1)
    });
    b.spawn(async { Err(lerr!("launch.error.stepFailed")) });
    let started = std::time::Instant::now();
    let e = b
        .join_with_wait(Duration::from_millis(50))
        .await
        .unwrap_err();
    assert_eq!(e.key(), "launch.error.stepFailed");
    assert!(started.elapsed() < Duration::from_secs(10));
}

// ---- LaunchCancellationScopeTest -------------------------------------------------------------

#[tokio::test]
async fn a_cancelled_launch_does_not_leave_later_work_cancelled() {
    let first = CancelToken::new();
    first.cancel();
    let b1: Branches<u32> = Branches::new(&first);
    tokio::time::sleep(Duration::from_millis(10)).await;
    assert!(b1.token().is_cancelled());
    drop(b1);
    let second = CancelToken::new();
    let mut b2: Branches<u32> = Branches::new(&second);
    let t = b2.token();
    b2.spawn(async move { Ok(u32::from(t.is_cancelled())) });
    assert_eq!(b2.join().await.unwrap(), vec![0]);
}

#[tokio::test]
async fn a_fresh_token_never_cancels() {
    let launch = CancelToken::new();
    let b: Branches<u32> = Branches::new(&launch);
    tokio::time::sleep(Duration::from_millis(10)).await;
    assert!(!b.token().is_cancelled());
}

// ---- IncompleteManifestLaunchGuardTest -------------------------------------------------------

#[tokio::test]
async fn failed_and_stub_packs_abort_before_any_sync() {
    let fx = Fixture::new(GameMode::Client);
    let failed = GameModPack::failed(fx.paths.clone(), "https://x/m.json", "boom");
    let stub = GameModPack::stub_from_index(
        fx.paths.clone(),
        "https://x/m.json",
        &mmcl_modpack::IndexEntry {
            pack_name: Some("Stubby".into()),
            ..Default::default()
        },
    );
    let c = CancelToken::new();
    let e = prepare(&fx.env, &failed, Arc::new(NullSink), None, &c)
        .await
        .unwrap_err();
    assert_eq!(e.key(), "launch.error.manifestNotLoaded");
    let e = prepare(&fx.env, &stub, Arc::new(NullSink), None, &c)
        .await
        .unwrap_err();
    assert_eq!(e.key(), "launch.error.manifestNotFullyLoaded");
    let e = verify_all_files_now(&fx.env, &stub, Arc::new(NullSink), None, &c)
        .await
        .unwrap_err();
    assert_eq!(e.key(), "launch.error.manifestNotFullyLoaded");
    assert_eq!(fx.http.count(""), 0, "nothing was fetched");
    assert!(
        !stub.pack_root().join("mods").exists(),
        "nothing was created"
    );
    let req = LaunchRequest {
        pack: stub,
        account: None,
        sink: Arc::new(NullSink),
        bridge: None,
        cancel: c,
    };
    let e = launch(&fx.env, req).await.unwrap_err();
    assert_eq!(e.key(), "gameModPackLauncher.error.noAccount");
}

// ---- end-to-end prepare ----------------------------------------------------------------------

#[tokio::test]
async fn vanilla_prepare_end_to_end_and_fast_path_afterwards() {
    let fx = Fixture::new(GameMode::Client);
    serve_vanilla(&fx, "1.21.5");
    fake_runtime(&fx, "java-runtime-delta");
    let pack = fx.vanilla_pack("1.21.5");
    let c = CancelToken::new();
    let p = prepare(&fx.env, &pack, Arc::new(NullSink), None, &c)
        .await
        .unwrap();
    assert_eq!(p.mc_version, "1.21.5");
    assert!(p.loader.is_none());
    assert_eq!(p.verify_mode, VerifyMode::Full);
    assert!(p.classpath.last().unwrap().ends_with("minecraft.jar"));
    assert_eq!(p.classpath.len(), 4);
    assert!(
        pack.pack_root()
            .join("bin/natives")
            .read_dir()
            .unwrap()
            .count()
            >= 1
    );
    // The context is restored after the run.
    assert_eq!(*pack.prepare_context(), PrepareContext::NONE);
    // Vanilla packs have no manifest hash, so no verify state is written.
    assert!(pack.load_verify_state().is_none());
}

#[tokio::test]
async fn fabric_prepare_merges_classpaths_with_loader_priority() {
    let fx = Fixture::new(GameMode::Client);
    serve_vanilla(&fx, "1.21.5");
    fake_runtime(&fx, "java-runtime-delta");
    let profile_url = "https://meta.fabricmc.net/v2/versions/loader/1.21.5/0.16.10/profile/json";
    fx.http.body(
        profile_url,
        r#"{"id":"fabric-loader-0.16.10-1.21.5","inheritsFrom":"1.21.5",
            "mainClass":"net.fabricmc.loader.impl.launch.knot.KnotClient",
            "arguments":{"game":[],"jvm":["-DFabricMcEmu= net.minecraft.client.main.Main "]},
            "libraries":[
              {"name":"net.fabricmc:fabric-loader:0.16.10","url":"https://maven.fabricmc.net/"},
              {"name":"org.ow2.asm:asm:9.7.1","url":"https://maven.fabricmc.net/"}]}"#,
    );
    fx.http.body(
        "https://maven.fabricmc.net/net/fabricmc/fabric-loader/0.16.10/fabric-loader-0.16.10.jar",
        b"loader".to_vec(),
    );
    fx.http.body(
        "https://maven.fabricmc.net/org/ow2/asm/asm/9.7.1/asm-9.7.1.jar",
        b"asm-9.7.1".to_vec(),
    );
    let pack = fx.pack(PackManifest {
        pack_name: Some("Fab Pack".into()),
        pack_version: Some("1".into()),
        pack_mod_loader: Some("fabric".into()),
        pack_mod_loader_url: Some(profile_url.into()),
        pack_mods: Some(Vec::new()),
        ..Default::default()
    });
    let c = CancelToken::new();
    let p = prepare(&fx.env, &pack, Arc::new(NullSink), None, &c)
        .await
        .unwrap();
    let l = p.loader.as_ref().unwrap();
    assert_eq!(l.name(), "Fabric");
    assert_eq!(l.loader_version(), "0.16.10");
    let norm: Vec<String> = p.classpath.iter().map(|e| e.replace('\\', "/")).collect();
    assert!(norm[0].ends_with("fabric-loader-0.16.10.jar"));
    assert!(norm[1].ends_with("asm-9.7.1.jar"));
    assert!(
        !norm.iter().any(|e| e.ends_with("asm-9.6.jar")),
        "vanilla ASM lost to Fabric's"
    );
    assert!(norm.last().unwrap().ends_with("bin/minecraft.jar"));
    // A FULL run with a manifest hash writes the verify state.
    let st = pack.load_verify_state().unwrap();
    assert_eq!(st.manifest_sha256.as_deref(), pack.manifest_sha256());

    // Same manifest, fresh state: the next launch uses the fast path.
    assert_eq!(
        decide_launch_verify_mode(&fx.env, &pack),
        VerifyMode::FastPath
    );
    let (main, args) =
        main_class_and_game_args(true, &p.library_manifest, Some(l.as_ref()), &fx.env.host);
    assert_eq!(main, "net.fabricmc.loader.impl.launch.knot.KnotClient");
    assert_eq!(args[0], "--username");
    let (main, args) =
        main_class_and_game_args(false, &p.library_manifest, Some(l.as_ref()), &fx.env.host);
    assert_eq!(main, mmcl_mojang::fabric_profile::FABRIC_SERVER_MAIN_CLASS);
    assert!(args.is_empty());
}

#[tokio::test]
async fn a_failing_branch_fails_the_launch_with_its_step_error() {
    let fx = Fixture::new(GameMode::Client);
    serve_vanilla(&fx, "1.21.5");
    fake_runtime(&fx, "java-runtime-delta");
    // The Fabric profile is missing (404).
    let pack = fx.pack(PackManifest {
        pack_name: Some("Broken".into()),
        pack_version: Some("1".into()),
        pack_mod_loader: Some("fabric".into()),
        pack_mod_loader_url: Some("https://meta.fabricmc.net/missing".into()),
        pack_mods: Some(Vec::new()),
        ..Default::default()
    });
    let c = CancelToken::new();
    let e = prepare(&fx.env, &pack, Arc::new(NullSink), None, &c)
        .await
        .unwrap_err();
    assert!(!e.is_cancelled());
    assert_eq!(*pack.prepare_context(), PrepareContext::NONE);
    assert!(pack.load_verify_state().is_none());
}

// ---- argv ------------------------------------------------------------------------------------

fn base_inputs() -> ArgvInputs {
    let mut placeholders = IndexMap::new();
    placeholders.insert("${classpath}", "CP".to_string());
    placeholders.insert("${natives_directory}", "/p/bin/natives".to_string());
    placeholders.insert("${auth_player_name}", "Steve".to_string());
    placeholders.insert("${user_type}", "msa".to_string());
    ArgvInputs {
        client: true,
        mac: false,
        java: "/rt/bin/java".into(),
        custom_jvm_args: "-XX:+UseG1GC -XX:G1NewSizePercent=30 -Dbad=${auth_access_token}".into(),
        min_ram_mb: 512,
        max_ram_mb: 4096,
        log4j_config: None,
        natives: "/p/bin/natives".into(),
        arm64_flags: false,
        logo: "/m/logo.png".into(),
        title: "My \"Pack\"".into(),
        manifest_jvm: vec![
            "-Djava.library.path=${natives_directory}".into(),
            "-cp".into(),
            "${classpath}".into(),
        ],
        loader_jvm: vec![],
        classpath: "CP".into(),
        main_class: "net.minecraft.client.main.Main".into(),
        game_args: vec![
            "--username".into(),
            "${auth_player_name}".into(),
            "--userType".into(),
            "${user_type}".into(),
        ],
        placeholders,
        quick_join: None,
    }
}

fn v(a: &[&str]) -> Vec<String> {
    a.iter().map(|s| s.to_string()).collect()
}

#[test]
fn modern_client_argv_golden() {
    let argv = build_argv(&base_inputs());
    assert_eq!(
        argv,
        v(&[
            "/rt/bin/java",
            "-XX:+UnlockExperimentalVMOptions",
            "-XX:+UseG1GC",
            "-XX:G1NewSizePercent=30",
            "-Xms512m",
            "-Xmx4096m",
            "-Dlog4j2.formatMsgNoLookups=true",
            "-Djava.library.path=/p/bin/natives",
            "-cp",
            "CP",
            "net.minecraft.client.main.Main",
            "--username",
            "Steve",
            "--userType",
            "msa",
            "--title",
            "My Pack",
            "--icon",
            "/m/logo.png",
        ])
    );
}

#[test]
fn legacy_client_argv_adds_library_path_and_classpath() {
    let mut i = base_inputs();
    i.manifest_jvm.clear();
    i.custom_jvm_args.clear();
    i.log4j_config = Some("/l/metadata/log4j-configs/4bb8.xml".into());
    i.main_class = "net.minecraft.launchwrapper.Launch".into();
    i.quick_join = Some(ServerFavorite::new("S", "mc.example.org", 25570));
    let argv = build_argv(&i);
    assert_eq!(
        argv,
        v(&[
            "/rt/bin/java",
            "-Xms512m",
            "-Xmx4096m",
            "-Dlog4j2.formatMsgNoLookups=true",
            "-Dlog4j.configurationFile=/l/metadata/log4j-configs/4bb8.xml",
            "-Djava.library.path=/p/bin/natives",
            "-cp",
            "CP",
            "net.minecraft.launchwrapper.Launch",
            "--username",
            "Steve",
            "--userType",
            "msa",
            "--server",
            "mc.example.org",
            "--port",
            "25570",
            "--title",
            "My Pack",
            "--icon",
            "/m/logo.png",
        ])
    );
}

#[test]
fn mac_dock_flags_sit_in_the_jvm_section() {
    let mut i = base_inputs();
    i.mac = true;
    i.arm64_flags = true;
    i.loader_jvm = v(&["--add-opens", "java.base/java.lang=ALL-UNNAMED"]);
    let argv = build_argv(&i);
    let main = argv
        .iter()
        .position(|a| a == "net.minecraft.client.main.Main")
        .unwrap();
    let dock = argv
        .iter()
        .position(|a| a.starts_with("-Xdock:name="))
        .unwrap();
    let arm = argv
        .iter()
        .position(|a| a.starts_with("-Dorg.lwjgl.librarypath="))
        .unwrap();
    let mjvm = argv
        .iter()
        .position(|a| a.starts_with("-Djava.library.path="))
        .unwrap();
    let ljvm = argv.iter().position(|a| a == "--add-opens").unwrap();
    assert!(
        arm < dock && dock < mjvm && mjvm < ljvm && ljvm < main,
        "{argv:?}"
    );
    assert_eq!(argv[dock], "-Xdock:name=My Pack");
    assert!(argv.contains(&"-Djdk.lang.Process.launchMechanism=vfork".to_string()));

    // A pack name that looks like a placeholder is never substituted into the dock name.
    let mut i = base_inputs();
    i.mac = true;
    i.title = "${auth_player_name}".into();
    let argv = build_argv(&i);
    assert!(
        argv.contains(&"-Xdock:name=${auth_player_name}".to_string()),
        "{argv:?}"
    );
    let t = argv.iter().position(|a| a == "--title").unwrap();
    assert_eq!(argv[t + 1], "${auth_player_name}");
}

#[test]
fn server_argv_has_no_title_and_ignores_quick_join() {
    let mut i = base_inputs();
    i.client = false;
    i.mac = true;
    i.quick_join = Some(ServerFavorite::new("S", "h", 25565));
    i.placeholders.shift_remove("${auth_player_name}");
    i.game_args.clear();
    let argv = build_argv(&i);
    assert!(
        !argv
            .iter()
            .any(|a| a == "--title" || a == "--server" || a.starts_with("-Xdock"))
    );
    assert_eq!(argv.last().unwrap(), "net.minecraft.client.main.Main");
}

#[test]
fn string_and_vector_argument_paths_agree_on_every_host() {
    let cj = mmcl_mojang::ClientJson::parse(
        br#"{"arguments":{"jvm":[
            {"rules":[{"action":"allow","os":{"name":"windows","version":"^10\\."}}],"value":["-Dos.name=Windows 10","-Dos.version=10.0"]},
            {"rules":[{"action":"allow","os":{"name":"osx"}}],"value":["-XstartOnFirstThread"]},
            {"rules":[{"action":"allow","os":{"arch":"x86"}}],"value":"-Xss1M"},
            "-cp","${classpath}"],
           "game":["--gameDir","${game_directory}"]}}"#,
    )
    .unwrap();
    for h in presets::all() {
        assert_eq!(
            cj.jvm_arguments_vec(&h),
            command::split_command_line(&cj.jvm_arguments(&h)),
            "{h:?}"
        );
        assert_eq!(
            cj.game_arguments_vec(&h),
            command::split_command_line(&cj.game_arguments(&h))
        );
    }
}

#[test]
fn server_heap_override_wins_only_in_server_mode() {
    let mut fx = Fixture::new(GameMode::Server);
    fx.env.heap_override = crate::env::HeapOverride {
        xms_mb: Some(1024),
        xmx_mb: Some(8192),
    };
    assert_eq!(ram_mb(&fx.env), (1024, 8192));
    let mut fx = Fixture::new(GameMode::Client);
    fx.env.heap_override.xmx_mb = Some(8192);
    assert_eq!(ram_mb(&fx.env), (512, 2048));
}

#[test]
fn scan_skip_reasons() {
    assert_eq!(
        scan_skip_reason(ScanFrequency::Disabled),
        t!("gameModPackLauncher.scanSkip.disabled")
    );
    assert_eq!(
        scan_skip_reason(ScanFrequency::Daily),
        t!("gameModPackLauncher.scanSkip.recent")
    );
}
