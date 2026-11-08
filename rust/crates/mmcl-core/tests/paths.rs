// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Ports of `LocalPathManagerClientConfigTest` and `LocalPathManagerClientServerPathsTest`.
//! No subprocess harness is needed: `DataPaths` takes the mode (or the root) explicitly.

use std::path::{Path, PathBuf};

use mmcl_core::identity::AppIdentity;
use mmcl_core::mode::{self, GameMode};
use mmcl_core::paths::{self, DataPaths};

fn assert_composed(p: &DataPaths, root: &Path) {
    let config = root.join("config");
    assert_eq!(p.root(), root);
    assert_eq!(p.config_dir(), config);
    assert_eq!(p.config_file(), config.join("configuration.json"));
    assert_eq!(p.config_tmp_file(), config.join("configuration.json.tmp"));
    assert_eq!(p.metadata_dir(), root.join("metadata"));
    assert_eq!(p.installs_dir(), root.join("installs"));
    assert_eq!(p.logs_dir(), root.join("logs"));
    assert_eq!(p.runtime_dir(), root.join("runtime"));
    assert_eq!(p.shared_assets_dir(), root.join("shared").join("assets"));
    assert_eq!(p.client_token_file(), config.join("client.mica"));
    assert_eq!(p.remembered_account_file(), config.join("player.mica"));
    assert_eq!(p.update_info_file(), config.join("update.mica"));
    assert_eq!(
        p.mc_version_manifest_file(),
        config.join("version.manifest")
    );
    assert_eq!(p.machine_key_file(), config.join("machine-key.bin"));
    assert_eq!(p.profiles_dir(), config.join("profiles"));
    assert_eq!(
        p.imported_manifests_dir(),
        config.join("imported-manifests")
    );
    assert_eq!(p.mcp_manifests_dir(), config.join("mcp-manifests"));
    assert_eq!(p.loader_logos_dir(), config.join("loader-logos"));
    assert_eq!(p.icons_dir(), config.join("icons"));
    assert_eq!(p.cache_dir(), root.join("cache"));
    assert_eq!(
        p.official_launcher_exports_dir(),
        root.join("official-launcher-exports")
    );
    assert_eq!(
        p.install_index_file(),
        root.join("installs").join("install_index.json")
    );
    assert_eq!(
        p.manifest_cache_dir(),
        root.join("installs").join("manifest_cache")
    );
    assert_eq!(
        p.pack_install_dir("MyPack"),
        root.join("installs").join("MyPack")
    );
    assert_eq!(
        p.metadata_image_file("abc"),
        root.join("metadata").join("abc.png")
    );
    assert_eq!(
        p.runtime_component_dir("jre-legacy"),
        root.join("runtime").join("jre-legacy")
    );
}

#[test]
fn client_mode_paths_are_composed_correctly() {
    let dir = tempfile::tempdir().unwrap();
    let root = dir.path().join(".MicaMinecraftLauncherRS");
    assert_composed(&DataPaths::at_root(&root, GameMode::Client), &root);
}

#[test]
fn server_mode_paths_are_composed_correctly() {
    let dir = tempfile::tempdir().unwrap();
    assert_composed(
        &DataPaths::at_root(dir.path(), GameMode::Server),
        dir.path(),
    );
}

#[test]
fn session_log_file_name() {
    let p = DataPaths::at_root("root", GameMode::Client);
    let at = chrono::NaiveDate::from_ymd_opt(2026, 11, 7)
        .unwrap()
        .and_hms_opt(14, 3, 22)
        .unwrap();
    let trimmed = AppIdentity::current().app_name_trimmed;
    assert_eq!(
        p.session_log_file(&at),
        PathBuf::from("root")
            .join("logs")
            .join(format!("{trimmed}_CLIENT_2026-11-07--14-03-22.log"))
    );
    assert_eq!(
        p.session_log_file_for(GameMode::Server, &at)
            .file_name()
            .unwrap()
            .to_string_lossy(),
        format!("{trimmed}_SERVER_2026-11-07--14-03-22.log")
    );
    assert_eq!(
        p.config_corrupt_file(&at),
        PathBuf::from("root")
            .join("config")
            .join("configuration.json.corrupt-20261107-140322")
    );
}

/// One test owns the process-wide mode and environment, so nothing races it.
#[test]
fn mode_dependent_resolution_and_the_client_anchor() {
    // Mode unset: no mode-dependent paths at all (the early-path trap is unrepresentable).
    mode::clear();
    assert_eq!(mode::current(), None);
    assert!(!mode::is_client());
    assert!(!mode::is_server());
    assert!(paths::current().is_none());

    // The client config folder is home-anchored (or the override) whatever the mode.
    let expected_client_root = paths::resolve_client_root(
        AppIdentity::current(),
        std::env::var_os(paths::DATA_DIR_ENV),
        dirs::home_dir(),
        paths::snapshot_startup_cwd(),
    );
    assert_eq!(DataPaths::client().root(), expected_client_root);
    assert_eq!(
        DataPaths::client_config_dir(),
        expected_client_root.join("config")
    );
    assert_eq!(
        DataPaths::single_instance_token_file(),
        expected_client_root
            .join("config")
            .join("single-instance.token")
    );
    assert_eq!(
        DataPaths::mcp_endpoint_file(),
        expected_client_root
            .join("config")
            .join("mcp-endpoint.json")
    );
    assert_eq!(
        DataPaths::startup_log_file(),
        expected_client_root.join("logs").join("startup.log")
    );
    if std::env::var_os(paths::DATA_DIR_ENV).is_none() {
        let home = dirs::home_dir().unwrap();
        assert_eq!(
            expected_client_root,
            home.join(AppIdentity::current().data_dir_name)
        );
    }

    // Client and server roots differ; the server root is the startup working directory.
    let server = DataPaths::for_mode(GameMode::Server);
    assert_eq!(server.root(), paths::snapshot_startup_cwd());
    assert_ne!(server.root(), DataPaths::client().root());
    assert_ne!(server.config_dir(), DataPaths::client_config_dir());

    mode::set(GameMode::Server);
    assert!(mode::is_server());
    assert_eq!(paths::expect_current(), server);
    // Client-anchored paths do not follow the mode.
    assert_eq!(
        DataPaths::client_config_dir(),
        expected_client_root.join("config")
    );

    mode::set(GameMode::Client);
    assert!(mode::is_client());
    assert_eq!(paths::current().unwrap().root(), expected_client_root);

    // MMCL_DATA_DIR overrides the client root.
    let dir = tempfile::tempdir().unwrap();
    let saved = std::env::var_os(paths::DATA_DIR_ENV);
    // SAFETY: this is the only test in this binary that reads or writes the environment.
    unsafe { std::env::set_var(paths::DATA_DIR_ENV, dir.path()) };
    assert_eq!(DataPaths::client().root(), dir.path());
    assert_eq!(DataPaths::client_config_dir(), dir.path().join("config"));
    match saved {
        // SAFETY: as above.
        Some(v) => unsafe { std::env::set_var(paths::DATA_DIR_ENV, v) },
        // SAFETY: as above.
        None => unsafe { std::env::remove_var(paths::DATA_DIR_ENV) },
    }
    mode::clear();
}
