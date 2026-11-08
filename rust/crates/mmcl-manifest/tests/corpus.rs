// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Parses real manifests: the vendored copies in `tests/corpus/`, and, when it is checked out
//! next to this repository (or named by `MMCL_MODPACKS_DIR`), every `.mmcjson` in the
//! `minecraft-launcher-modpacks` repository. Each is checked field by field against an
//! independent `serde_json::Value` reading of the same text.

use std::path::{Path, PathBuf};

use mmcl_manifest::document::{FileList, ModPackDocument};
use mmcl_manifest::gson;
use mmcl_manifest::{PackManifest, Syntax, usable_hash};
use serde_json::Value;

fn modpacks_repo() -> Option<PathBuf> {
    if let Some(dir) = std::env::var_os("MMCL_MODPACKS_DIR") {
        let dir = PathBuf::from(dir);
        return dir.is_dir().then_some(dir);
    }
    Path::new(env!("CARGO_MANIFEST_DIR"))
        .ancestors()
        .map(|a| a.join("minecraft-launcher-modpacks"))
        .find(|p| p.is_dir())
}

fn manifests_in(dir: &Path) -> Vec<PathBuf> {
    let mut files: Vec<PathBuf> = walkdir::WalkDir::new(dir)
        .into_iter()
        .filter_map(Result::ok)
        .filter(|e| e.file_type().is_file())
        .map(|e| e.into_path())
        .filter(|p| p.extension().is_some_and(|x| x == "mmcjson"))
        .collect();
    files.sort();
    files
}

fn list_len(raw: &Value, key: &str) -> Option<usize> {
    raw.get(key)?
        .as_array()
        .map(|a| a.iter().filter(|v| v.is_object()).count())
}

fn check(path: &Path) {
    let text = std::fs::read_to_string(path).unwrap();
    let name = path.display();
    let parsed = PackManifest::parse_with_syntax(&text).unwrap_or_else(|e| panic!("{name}: {e}"));
    assert_eq!(parsed.syntax, Syntax::Standard, "{name}");
    let m = parsed.manifest;
    let raw: Value = serde_json::from_str(&text).unwrap();

    assert!(
        gson::compat_problems(&raw).is_empty(),
        "{name}: {:?}",
        gson::compat_problems(&raw)
    );
    assert_eq!(m.pack_name.as_deref(), raw["packName"].as_str(), "{name}");
    assert_eq!(
        m.pack_version.as_deref(),
        raw["packVersion"].as_str(),
        "{name}"
    );
    assert!(!m.sanitized_name().is_empty(), "{name}");
    assert!(m.min_ram_gb() > 0.0, "{name}");

    let lists = [
        ("packMods", m.pack_mods.as_deref()),
        ("packConfigs", m.pack_configs.as_deref()),
        ("packResourcePacks", m.pack_resource_packs.as_deref()),
        ("packShaderPacks", m.pack_shader_packs.as_deref()),
        ("packInitialFiles", m.pack_initial_files.as_deref()),
    ];
    for (key, list) in lists {
        assert_eq!(list.map(<[_]>::len), list_len(&raw, key), "{name}: {key}");
        for (i, f) in list.unwrap_or(&[]).iter().enumerate() {
            let r = &raw[key][i];
            assert_eq!(
                f.remote.as_deref(),
                r["remote"].as_str(),
                "{name}: {key}[{i}]"
            );
            assert_eq!(
                f.local.as_deref(),
                r["local"].as_str(),
                "{name}: {key}[{i}]"
            );
            assert_eq!(
                f.sha256.as_deref(),
                r["sha256"].as_str(),
                "{name}: {key}[{i}]"
            );
            assert_eq!(
                f.client_req,
                r["clientReq"].as_bool().unwrap_or(false),
                "{name}: {key}[{i}]"
            );
            assert_eq!(
                f.server_req,
                r["serverReq"].as_bool().unwrap_or(false),
                "{name}: {key}[{i}]"
            );
            assert!(f.file_name().is_some(), "{name}: {key}[{i}]");
        }
    }
    assert_eq!(
        m.news().len(),
        list_len(&raw, "packNews").unwrap_or(0),
        "{name}"
    );
    assert_eq!(
        m.links().len(),
        list_len(&raw, "packLinks").unwrap_or(0),
        "{name}"
    );
    assert_eq!(
        m.scan_acknowledgements().len(),
        list_len(&raw, "packScanAcknowledgements").unwrap_or(0),
        "{name}"
    );
    let logo_count = match &raw["packLogoURL"] {
        Value::Array(a) => a.len(),
        Value::String(s) if !s.trim().is_empty() => 1,
        _ => 0,
    };
    assert_eq!(m.logo_urls().len(), logo_count, "{name}");

    // Runtime model round trip.
    let again = PackManifest::parse(&m.to_pretty_json()).unwrap();
    assert_eq!(again, m, "{name}: runtime round trip");

    // Editor round trip: the document keeps every key, and a read/write of every list keeps
    // every usable hash and side flag.
    let mut doc = ModPackDocument::from_json(&text).unwrap();
    assert_eq!(
        Value::Object(doc.json().clone()),
        raw,
        "{name}: document keeps everything"
    );
    let reparsed = ModPackDocument::from_json(&doc.to_pretty_json()).unwrap();
    assert_eq!(reparsed, doc, "{name}: pretty JSON reparses identically");
    for list in FileList::ALL {
        let entries = doc.read_file_list(list);
        doc.write_file_list(list, &entries);
    }
    let edited = doc.to_manifest();
    for (before, after) in m.mods().iter().zip(edited.mods()) {
        for algo in [
            mmcl_manifest::HashAlgo::Sha1,
            mmcl_manifest::HashAlgo::Md5,
            mmcl_manifest::HashAlgo::Sha256,
        ] {
            assert_eq!(
                before.hash(algo),
                after.hash(algo),
                "{name}: {:?}",
                before.local
            );
        }
        assert_eq!(
            usable_hash(before.sha256.as_deref()),
            usable_hash(after.sha256.as_deref())
        );
        assert_eq!(before.client_req, after.client_req, "{name}");
        assert_eq!(before.server_req, after.server_req, "{name}");
    }
    assert!(
        doc.validate(&|k, a| format!("{k}{a:?}")).is_empty(),
        "{name}: {:?}",
        doc.validate(&|k, a| format!("{k}{a:?}"))
    );
}

#[test]
fn vendored_corpus_parses() {
    let dir = Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("tests")
        .join("corpus");
    let files = manifests_in(&dir);
    assert!(files.len() >= 3, "vendored corpus missing");
    for f in files {
        check(&f);
    }
}

#[test]
fn modpacks_repository_parses() {
    let Some(repo) = modpacks_repo() else {
        eprintln!("minecraft-launcher-modpacks not found; skipping");
        return;
    };
    let files = manifests_in(&repo);
    assert!(!files.is_empty(), "no manifests under {}", repo.display());
    for f in &files {
        check(f);
    }
    eprintln!("checked {} manifests under {}", files.len(), repo.display());
}

#[test]
fn vendored_fabric_manifest_details() {
    let text = include_str!("corpus/fabric-1.21.5.mmcjson");
    let m = PackManifest::parse(text).unwrap();
    assert_eq!(m.mod_loader_kind(), mmcl_manifest::ModLoaderKind::Fabric);
    assert!(!m.uses_post_install_steps());
    assert_eq!(m.mod_loader_hash(), Some(""));
    assert_eq!(m.mods(), &[]);
}

#[test]
fn a_gson_formatted_manifest_prints_back_byte_for_byte() {
    // The vendored Forge 1.12.2 manifest is in Gson's pretty layout, so an editor load and
    // save must reproduce it exactly (line endings aside).
    let text = include_str!("corpus/forge-1.12.2.mmcjson").replace("\r\n", "\n");
    let doc = ModPackDocument::from_json(&text).unwrap();
    assert_eq!(doc.to_pretty_json(), text.trim_end_matches('\n'));
}
