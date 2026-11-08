// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Ports of `ModPackDocumentTest`, `ModPackDocumentAuthoringTest` and `ModPackFileEntryTest`.

use super::*;
use serde_json::json;

fn echo(key: &str, args: &[String]) -> String {
    if args.is_empty() {
        key.to_string()
    } else {
        format!("{key}({})", args.join(", "))
    }
}

fn validate(doc: &ModPackDocument) -> Vec<String> {
    doc.validate(&echo)
}

fn doc(text: &str) -> ModPackDocument {
    ModPackDocument::from_json(text).unwrap()
}

fn first_mod(text: &str, list: FileList) -> FileEntry {
    doc(text).read_file_list(list).remove(0)
}

fn written(d: &ModPackDocument, list: FileList) -> Map<String, Value> {
    d.json()[list.key()][0].as_object().unwrap().clone()
}

fn pack_named(name: &str, version: &str) -> ModPackDocument {
    let mut d = ModPackDocument::blank();
    d.put_string(KEY_PACK_NAME, name);
    d.put_string(KEY_PACK_VERSION, version);
    d
}

fn mod_entry(name: &str, local: &str) -> FileEntry {
    FileEntry::new(
        name,
        format!("https://example.test/{name}.jar"),
        local,
        "",
        Some("sha1"),
        true,
        true,
    )
}

fn pack_with_two_mods() -> ModPackDocument {
    let mut d = pack_named("Pack", "1.0.0");
    d.add_mod(mod_entry("JEI", "mods/jei.jar"));
    d.add_mod(mod_entry("REI", "mods/rei.jar"));
    d
}

// --- blank -----------------------------------------------------------------------------------

#[test]
fn blank_document_defaults() {
    let d = ModPackDocument::blank();
    assert_eq!(d.json()["manifestFormat"], json!(2));
    assert_eq!(d.get_string(KEY_PACK_VERSION), "1.0.0");
    assert_eq!(d.get_string(KEY_PACK_MIN_RAM_GB), "2");
    assert_eq!(d.get_string("packModLoader"), "forge");
    assert!(d.json()[KEY_SCAN_EXCLUSIONS].is_array());
    for list in FileList::ALL {
        assert!(
            d.json()[list.key()].is_array(),
            "{} should be an array",
            list.key()
        );
        assert!(d.read_file_list(list).is_empty());
    }
}

#[test]
fn blank_document_key_order_matches_java() {
    let keys: Vec<_> = ModPackDocument::blank().json().keys().cloned().collect();
    assert_eq!(
        keys,
        [
            "manifestFormat",
            "packName",
            "packVersion",
            "packURL",
            "packUnstable",
            "packCustomDiscordRpc",
            "packMinRAMGB",
            "packLogoURL",
            "packLogoSha1",
            "packBackgroundURL",
            "packBackgroundSha1",
            "packForgeURL",
            "packForgeHash",
            "packModLoader",
            "packModLoaderURL",
            "packModLoaderHash",
            "packMinecraftVersion",
            "packScanExclusions",
            "packMods",
            "packConfigs",
            "packResourcePacks",
            "packShaderPacks",
            "packInitialFiles"
        ]
    );
}

#[test]
fn blank_document_pretty_json_matches_gson() {
    let expected = "{\n  \"manifestFormat\": 2,\n  \"packName\": \"\",\n  \"packVersion\": \"1.0.0\",\n  \"packURL\": \"\",\n  \"packUnstable\": false,\n  \"packCustomDiscordRpc\": false,\n  \"packMinRAMGB\": \"2\",\n  \"packLogoURL\": \"\",\n  \"packLogoSha1\": \"\",\n  \"packBackgroundURL\": \"\",\n  \"packBackgroundSha1\": \"\",\n  \"packForgeURL\": \"\",\n  \"packForgeHash\": \"\",\n  \"packModLoader\": \"forge\",\n  \"packModLoaderURL\": \"\",\n  \"packModLoaderHash\": \"\",\n  \"packMinecraftVersion\": \"\",\n  \"packScanExclusions\": [],\n  \"packMods\": [],\n  \"packConfigs\": [],\n  \"packResourcePacks\": [],\n  \"packShaderPacks\": [],\n  \"packInitialFiles\": []\n}";
    assert_eq!(ModPackDocument::blank().to_pretty_json(), expected);
}

#[test]
fn blank_document_only_fails_validation_on_the_empty_required_name() {
    let issues = validate(&ModPackDocument::blank());
    assert_eq!(issues.len(), 1, "unexpected issues: {issues:?}");
    assert!(issues[0].starts_with("editor.validate.required"));
}

// --- parsing ---------------------------------------------------------------------------------

#[test]
fn from_json_parses_a_manifest() {
    assert_eq!(
        doc("{\"packName\":\"Test Pack\"}").get_string(KEY_PACK_NAME),
        "Test Pack"
    );
}

#[test]
fn from_json_rejects_empty_and_non_objects() {
    assert!(ModPackDocument::from_json("").is_err());
    assert!(ModPackDocument::from_json("null").is_err());
    assert!(ModPackDocument::from_json("[1,2]").is_err());
    assert!(ModPackDocument::from_json("not json").is_err());
}

#[test]
fn from_json_keeps_unknown_keys_and_order() {
    let text = "{\n  \"zeta\": 1,\n  \"packName\": \"P\",\n  \"future\": {\n    \"x\": [\n      1\n    ]\n  }\n}";
    let d = doc(text);
    assert_eq!(d.to_pretty_json(), text);
}

#[test]
fn wrapping_adopts_the_object() {
    let mut d = ModPackDocument::wrapping(Map::new());
    d.put_string(KEY_PACK_NAME, "Live");
    assert_eq!(d.into_json()[KEY_PACK_NAME], json!("Live"));
}

// --- scalars ---------------------------------------------------------------------------------

#[test]
fn get_string_and_get_bool_defaults() {
    let empty = ModPackDocument::wrapping(Map::new());
    assert_eq!(empty.get_string("nope"), "");
    assert_eq!(doc("{\"packURL\":null}").get_string("packURL"), "");
    assert!(!empty.get_bool("packUnstable"));
    assert!(!doc("{\"packUnstable\":null}").get_bool("packUnstable"));
}

#[test]
fn scalar_coercions() {
    let d = doc("{\"a\":5,\"b\":true,\"c\":[\"one\"],\"d\":{\"x\":1},\"e\":\"TRUE\",\"f\":[true]}");
    assert_eq!(d.get_string("a"), "5");
    assert_eq!(d.get_string("b"), "true");
    assert_eq!(d.get_string("c"), "one");
    assert_eq!(d.get_string("d"), "");
    assert!(d.get_bool("e"));
    assert!(d.get_bool("f"));
    assert!(!d.get_bool("a"));
}

#[test]
fn put_bool_and_get_bool_round_trip() {
    let mut d = ModPackDocument::blank();
    d.put_bool("packUnstable", true);
    assert!(d.get_bool("packUnstable"));
}

// --- string | string[] ------------------------------------------------------------------------

#[test]
fn a_single_line_is_stored_as_a_bare_string() {
    let mut d = ModPackDocument::blank();
    d.put_string_or_array("packLogoURL", "https://example.test/logo.png");
    assert!(d.json()["packLogoURL"].is_string());
    assert_eq!(
        d.get_string_or_array_lines("packLogoURL"),
        "https://example.test/logo.png"
    );
}

#[test]
fn several_lines_are_stored_as_an_array_in_order() {
    let mut d = ModPackDocument::blank();
    d.put_string_or_array("packLogoURL", "one\ntwo\nthree");
    assert_eq!(d.json()["packLogoURL"].as_array().unwrap().len(), 3);
    assert_eq!(
        d.get_string_or_array_lines("packLogoURL"),
        "one\ntwo\nthree"
    );
}

#[test]
fn no_non_blank_lines_stores_an_empty_string() {
    let mut d = ModPackDocument::blank();
    d.put_string_or_array("packLogoURL", "   \n\n  ");
    assert_eq!(d.json()["packLogoURL"], json!(""));
    assert_eq!(d.get_string_or_array_lines("packLogoURL"), "");
}

#[test]
fn blank_lines_are_dropped_and_surviving_lines_trimmed() {
    let mut d = ModPackDocument::blank();
    d.put_string_or_array("packLogoURL", "  a  \n\n   \n  b  \r");
    assert_eq!(d.get_string_or_array_lines("packLogoURL"), "a\nb");
}

#[test]
fn string_or_array_read_rules() {
    assert_eq!(
        doc("{\"packLogoURL\":[\"a\",null,\"b\"]}").get_string_or_array_lines("packLogoURL"),
        "a\nb"
    );
    assert_eq!(
        ModPackDocument::wrapping(Map::new()).get_string_or_array_lines("packLogoURL"),
        ""
    );
}

#[test]
fn string_or_array_shape_is_stable_across_an_edit_cycle() {
    let mut d = doc("{\"packLogoURL\":[\"a\",\"b\"]}");
    let lines = d.get_string_or_array_lines("packLogoURL");
    d.put_string_or_array("packLogoURL", &lines);
    assert!(d.json()["packLogoURL"].is_array());
    assert_eq!(d.get_string_or_array_lines("packLogoURL"), "a\nb");

    let mut single = doc("{\"packLogoURL\":[\"only\"]}");
    let lines = single.get_string_or_array_lines("packLogoURL");
    single.put_string_or_array("packLogoURL", &lines);
    assert_eq!(single.json()["packLogoURL"], json!("only"));
}

#[test]
fn array_lines() {
    let mut d = ModPackDocument::blank();
    d.put_array_lines(KEY_SCAN_EXCLUSIONS, "mods/onlyone.jar");
    assert!(d.json()[KEY_SCAN_EXCLUSIONS].is_array());
    assert_eq!(d.get_array_lines(KEY_SCAN_EXCLUSIONS), "mods/onlyone.jar");
    d.put_array_lines(KEY_SCAN_EXCLUSIONS, "  \n  ");
    assert_eq!(d.json()[KEY_SCAN_EXCLUSIONS], json!([]));
    assert_eq!(
        doc("{\"packScanExclusions\":\"mods/\"}").get_array_lines(KEY_SCAN_EXCLUSIONS),
        ""
    );
    assert_eq!(
        doc("{\"packScanExclusions\":[\"a\",null,\"b\"]}").get_array_lines(KEY_SCAN_EXCLUSIONS),
        "a\nb"
    );
}

// --- file lists ------------------------------------------------------------------------------

#[test]
fn read_file_list_absent_or_non_array_is_empty() {
    assert!(
        ModPackDocument::wrapping(Map::new())
            .read_file_list(FileList::Mods)
            .is_empty()
    );
    assert!(
        doc("{\"packMods\":\"not an array\"}")
            .read_file_list(FileList::Mods)
            .is_empty()
    );
}

#[test]
fn read_file_list_skips_non_object_elements() {
    let entries = doc("{\"packMods\":[\"junk\",{\"remote\":\"r\",\"local\":\"l\"},7,null]}")
        .read_file_list(FileList::Mods);
    assert_eq!(entries.len(), 1);
    assert_eq!(entries[0].remote(), "r");
}

#[test]
fn sha256_wins_as_primary_and_the_others_are_preserved() {
    let e = first_mod(
        "{\"packMods\":[{\"remote\":\"r\",\"local\":\"l\",\"sha1\":\"aaa\",\"md5\":\"bbb\",\"sha256\":\"ccc\"}]}",
        FileList::Mods,
    );
    assert_eq!(e.hash_type(), "sha256");
    assert_eq!(e.hash(), "ccc");
    assert_eq!(e.extra_hash("sha1"), Some("aaa"));
    assert_eq!(e.extra_hash("md5"), Some("bbb"));
    assert_eq!(
        e.extra_hash("sha256"),
        None,
        "the primary hash is not also an extra"
    );
}

#[test]
fn sha1_wins_over_md5_without_sha256() {
    let e = first_mod(
        "{\"packMods\":[{\"remote\":\"r\",\"local\":\"l\",\"sha1\":\"aaa\",\"md5\":\"bbb\"}]}",
        FileList::Mods,
    );
    assert_eq!((e.hash_type(), e.hash()), ("sha1", "aaa"));
    assert_eq!(e.extra_hash("md5"), Some("bbb"));
}

#[test]
fn md5_is_used_when_it_is_the_only_hash() {
    let e = first_mod(
        "{\"packMods\":[{\"remote\":\"r\",\"local\":\"l\",\"md5\":\"bbb\"}]}",
        FileList::Mods,
    );
    assert_eq!((e.hash_type(), e.hash()), ("md5", "bbb"));
}

#[test]
fn the_minus_one_sentinel_does_not_count_as_a_hash() {
    let e = first_mod(
        "{\"packMods\":[{\"remote\":\"r\",\"local\":\"l\",\"sha1\":\"-1\",\"md5\":\"-1\",\"sha256\":\"-1\"}]}",
        FileList::Mods,
    );
    assert_eq!(e.hash(), "");
    assert_eq!(
        e.hash_type(),
        "sha1",
        "the default type is kept when there is no hash"
    );
    assert!(e.extra_hashes().is_empty());
}

#[test]
fn a_blank_hash_slot_does_not_count_as_a_hash() {
    let e = first_mod(
        "{\"packMods\":[{\"remote\":\"r\",\"local\":\"l\",\"sha1\":\"   \",\"md5\":\"bbb\"}]}",
        FileList::Mods,
    );
    assert_eq!(e.hash_type(), "md5");
    assert_eq!(e.extra_hash("sha1"), None);
}

#[test]
fn absent_requirement_flags_default_to_required() {
    let e = first_mod(
        "{\"packMods\":[{\"remote\":\"r\",\"local\":\"l\"}]}",
        FileList::Mods,
    );
    assert!(e.client_req() && e.server_req());
}

#[test]
fn null_fields_read_as_defaults_instead_of_failing() {
    let e = first_mod(
        "{\"packMods\":[{\"name\":null,\"remote\":\"r\",\"local\":\"l\",\"clientReq\":null,\"serverReq\":false}]}",
        FileList::Mods,
    );
    assert_eq!(e.name(), "");
    // A present-but-null flag reads as the runtime reads it (false), so saving the entry
    // does not turn it into a client download.
    assert!(!e.client_req());
    assert!(!e.server_req());
}

#[test]
fn present_flags_read_the_same_as_the_runtime_model() {
    let text = "{\"packMods\":[{\"remote\":\"r\",\"local\":\"l\",\"clientReq\":null,\"serverReq\":\"TRUE\"},\
                {\"remote\":\"r\",\"local\":\"m\",\"clientReq\":{},\"serverReq\":\"yes\"}]}";
    let mut d = doc(text);
    let runtime = d.to_manifest();
    let entries = d.read_file_list(FileList::Mods);
    for (entry, file) in entries.iter().zip(runtime.mods()) {
        assert_eq!(entry.client_req(), file.client_req);
        assert_eq!(entry.server_req(), file.server_req);
    }
    d.write_file_list(FileList::Mods, &entries);
    let saved = d.to_manifest();
    let flags = |m: &PackManifest| -> Vec<(bool, bool)> {
        m.mods()
            .iter()
            .map(|f| (f.client_req, f.server_req))
            .collect()
    };
    assert_eq!(flags(&saved), flags(&runtime));
    assert_eq!(flags(&saved), [(false, true), (false, false)]);
}

#[test]
fn present_requirement_flags_are_honoured() {
    let e = first_mod(
        "{\"packMods\":[{\"remote\":\"r\",\"local\":\"l\",\"clientReq\":true,\"serverReq\":false}]}",
        FileList::Mods,
    );
    assert!(e.client_req());
    assert!(!e.server_req());
}

#[test]
fn lists_without_requirement_flags_always_read_as_required() {
    let e = first_mod(
        "{\"packResourcePacks\":[{\"remote\":\"r\",\"local\":\"l\",\"serverReq\":false}]}",
        FileList::ResourcePacks,
    );
    assert!(e.server_req());
}

#[test]
fn names_are_ignored_on_lists_that_do_not_carry_them() {
    let e = first_mod(
        "{\"packConfigs\":[{\"name\":\"ignored\",\"remote\":\"r\",\"local\":\"l\"}]}",
        FileList::Configs,
    );
    assert_eq!(e.name(), "");
}

#[test]
fn the_modrinth_slug_is_read_when_present() {
    let e = first_mod(
        "{\"packMods\":[{\"remote\":\"r\",\"local\":\"l\",\"modrinthSlug\":\"jei\"}]}",
        FileList::Mods,
    );
    assert_eq!(e.modrinth_slug(), "jei");
}

#[test]
fn write_file_list_always_emits_all_three_hash_slots_in_order() {
    let mut d = ModPackDocument::blank();
    d.write_file_list(
        FileList::Mods,
        &[FileEntry::new(
            "n",
            "r",
            "l",
            "abc",
            Some("sha1"),
            true,
            true,
        )],
    );
    let w = written(&d, FileList::Mods);
    assert_eq!(w["sha1"], json!("abc"));
    assert_eq!(w["md5"], json!("-1"));
    assert_eq!(w["sha256"], json!("-1"));
    let keys: Vec<_> = w.keys().cloned().collect();
    assert_eq!(
        keys,
        [
            "name",
            "remote",
            "local",
            "sha1",
            "md5",
            "sha256",
            "clientReq",
            "serverReq"
        ]
    );
}

#[test]
fn write_file_list_shape_per_list() {
    let mut d = ModPackDocument::blank();
    d.write_file_list(
        FileList::Configs,
        &[FileEntry::new("n", "r", "l", "", Some("sha1"), true, true)],
    );
    assert!(!written(&d, FileList::Configs).contains_key("name"));
    d.write_file_list(
        FileList::ShaderPacks,
        &[FileEntry::new("", "r", "l", "", Some("sha1"), true, false)],
    );
    let w = written(&d, FileList::ShaderPacks);
    assert!(!w.contains_key("clientReq") && !w.contains_key("serverReq"));
    d.write_file_list(
        FileList::Mods,
        &[FileEntry::new("n", "r", "l", "", Some("sha1"), true, true)],
    );
    assert!(!written(&d, FileList::Mods).contains_key("modrinthSlug"));
}

#[test]
fn write_file_list_replaces_whatever_was_there() {
    let mut d = doc("{\"packMods\":[{\"remote\":\"old\",\"local\":\"old\"}]}");
    d.write_file_list(FileList::Mods, &[]);
    assert_eq!(d.json()["packMods"], json!([]));
}

#[test]
fn a_read_write_cycle_does_not_drop_any_hash() {
    let mut d = doc(
        "{\"packMods\":[{\"name\":\"JEI\",\"remote\":\"r\",\"local\":\"l\",\"sha1\":\"aaa\",\"md5\":\"bbb\",\"sha256\":\"ccc\",\"clientReq\":true,\"serverReq\":false,\"modrinthSlug\":\"jei\"}]}",
    );
    let mods = d.read_file_list(FileList::Mods);
    d.write_file_list(FileList::Mods, &mods);
    let w = written(&d, FileList::Mods);
    assert_eq!(w["sha1"], json!("aaa"));
    assert_eq!(w["md5"], json!("bbb"));
    assert_eq!(w["sha256"], json!("ccc"));
    assert_eq!(w["name"], json!("JEI"));
    assert_eq!(w["modrinthSlug"], json!("jei"));
    assert_eq!(w["serverReq"], json!(false));
}

#[test]
fn a_second_read_write_cycle_changes_nothing_further() {
    let mut d = doc(
        "{\"packMods\":[{\"name\":\"JEI\",\"remote\":\"r\",\"local\":\"l\",\"sha1\":\"aaa\",\"sha256\":\"ccc\"}]}",
    );
    let mods = d.read_file_list(FileList::Mods);
    d.write_file_list(FileList::Mods, &mods);
    let after_first = d.to_pretty_json();
    let mods = d.read_file_list(FileList::Mods);
    d.write_file_list(FileList::Mods, &mods);
    assert_eq!(after_first, d.to_pretty_json());
}

// --- versions --------------------------------------------------------------------------------

#[test]
fn bump_version_rules() {
    assert_eq!(bump_version(Some("1.4.7"), 0), "2.0.0");
    assert_eq!(bump_version(Some("1.4.7"), 1), "1.5.0");
    assert_eq!(bump_version(Some("1.4.7"), 2), "1.4.8");
    assert_eq!(bump_version(None, 1), "0.1.0");
    assert_eq!(bump_version(Some("   "), 0), "1.0.0");
    assert_eq!(bump_version(Some("0.x.9"), 0), "1.0.0");
    assert_eq!(bump_version(Some("1"), 0), "2.0.0");
    assert_eq!(bump_version(Some("1.4.7.3"), 1), "1.5.0");
    assert_eq!(bump_version(Some("1.4.7"), 9), "1.4.7");
    assert_eq!(bump_version(Some("1.2."), 2), "1.2.1");
    assert_eq!(bump_version(Some("1.4.7.3"), 3), "1.4.7");
}

#[test]
fn first_line_rules() {
    assert_eq!(first_line(Some("   \n  second  \nthird")), "second");
    assert_eq!(first_line(None), "");
    assert_eq!(first_line(Some("  \n \n ")), "");
}

#[test]
fn sanitized_file_base_name() {
    let mut d = ModPackDocument::blank();
    d.put_string(KEY_PACK_NAME, "My Pack v2.1 (beta)!");
    assert_eq!(d.sanitized_file_base_name(), "MyPackv21beta");
    d.put_string(KEY_PACK_NAME, "!!! ---");
    assert_eq!(d.sanitized_file_base_name(), "");
}

// --- validation ------------------------------------------------------------------------------

#[test]
fn a_missing_name_and_version_are_both_reported() {
    let issues = validate(&ModPackDocument::wrapping(Map::new()));
    assert!(
        issues.contains(&"editor.validate.required(editor.validate.label.packName)".to_string())
    );
    assert!(
        issues.contains(&"editor.validate.required(editor.validate.label.packVersion)".to_string())
    );
}

#[test]
fn a_blank_or_null_name_is_reported_as_missing() {
    let required = "editor.validate.required(editor.validate.label.packName)".to_string();
    assert!(validate(&doc("{\"packName\":\"   \",\"packVersion\":\"1.0.0\"}")).contains(&required));
    // Java threw UnsupportedOperationException here; a null name is simply missing.
    assert!(validate(&doc("{\"packName\":null,\"packVersion\":\"1.0.0\"}")).contains(&required));
}

#[test]
fn minimum_ram_validation() {
    let mut d = pack_named("Pack", "1");
    d.put_string(KEY_PACK_MIN_RAM_GB, "lots");
    assert!(validate(&d).contains(&"editor.validate.invalidMinRam".to_string()));
    d.put_string(KEY_PACK_MIN_RAM_GB, "1.5");
    assert!(!validate(&d).contains(&"editor.validate.invalidMinRam".to_string()));
    let absent = doc("{\"packName\":\"P\",\"packVersion\":\"1\"}");
    assert!(!validate(&absent).contains(&"editor.validate.invalidMinRam".to_string()));
    let numeric = doc("{\"packName\":\"P\",\"packVersion\":\"1\",\"packMinRAMGB\":4}");
    assert!(validate(&numeric).is_empty());
}

#[test]
fn an_entry_with_no_remote_or_local_is_reported_twice() {
    let issues = validate(&doc(
        "{\"packName\":\"P\",\"packVersion\":\"1\",\"packMods\":[{\"name\":\"JEI\",\"remote\":\"\",\"local\":\"\"}]}",
    ));
    assert!(
        issues.contains(
            &"editor.validate.remoteEmpty(editor.validate.label.mods \"JEI\")".to_string()
        ),
        "{issues:?}"
    );
    assert!(
        issues.contains(
            &"editor.validate.localEmpty(editor.validate.label.mods \"JEI\")".to_string()
        ),
        "{issues:?}"
    );
}

#[test]
fn an_unnamed_entry_is_labelled_by_its_position() {
    let issues = validate(&doc(
        "{\"packName\":\"P\",\"packVersion\":\"1\",\"packConfigs\":[{\"remote\":\"r\",\"local\":\"l\"},{\"remote\":\"\",\"local\":\"l\"}]}",
    ));
    assert!(
        issues
            .contains(&"editor.validate.remoteEmpty(editor.validate.label.configs #2)".to_string())
    );
}

#[test]
fn a_fully_populated_manifest_validates_clean() {
    let mut d = ModPackDocument::blank();
    d.put_string(KEY_PACK_NAME, "Complete Pack");
    d.write_file_list(
        FileList::Mods,
        &[FileEntry::new(
            "JEI",
            "https://example.test/jei.jar",
            "mods/jei.jar",
            "abc",
            Some("sha1"),
            true,
            true,
        )],
    );
    assert_eq!(validate(&d), Vec::<String>::new());
}

#[test]
fn each_issue_is_a_separate_message_with_no_embedded_newlines() {
    let mut d = ModPackDocument::wrapping(Map::new());
    d.put_string(KEY_PACK_MIN_RAM_GB, "nope");
    d.write_file_list(
        FileList::Mods,
        &[FileEntry::new("", "", "", "", Some("sha1"), true, true)],
    );
    let issues = validate(&d);
    assert!(issues.len() >= 4, "{issues:?}");
    assert!(issues.iter().all(|i| !i.contains('\n')));
}

#[test]
fn a_manifest_java_cannot_bind_fails_the_round_trip_check() {
    let issues = validate(&doc(
        "{\"packName\":\"P\",\"packVersion\":\"1\",\"packMods\":[\"junk\"]}",
    ));
    assert_eq!(issues.len(), 1, "{issues:?}");
    assert!(
        issues[0].starts_with("editor.validate.roundTripFailed("),
        "{issues:?}"
    );
}

#[test]
fn file_list_shapes_match_the_manifest_format() {
    assert_eq!(FileList::Mods.key(), "packMods");
    assert!(FileList::Mods.has_name() && FileList::Mods.has_side_flags());
    assert!(!FileList::Configs.has_name() && FileList::Configs.has_side_flags());
    assert!(!FileList::ResourcePacks.has_side_flags());
    assert!(!FileList::ShaderPacks.has_side_flags());
    assert!(!FileList::InitialFiles.has_name() && FileList::InitialFiles.has_side_flags());
    for list in FileList::ALL {
        assert!(list.label_key().starts_with("editor.validate.label."));
    }
}

// --- authoring -------------------------------------------------------------------------------

#[test]
fn a_fork_takes_the_new_name_and_bumps_the_minor_version() {
    let fork = pack_named("Original", "1.4.7").fork_of("My Fork").unwrap();
    assert_eq!(fork.get_string(KEY_PACK_NAME), "My Fork");
    assert_eq!(fork.get_string(KEY_PACK_VERSION), "1.5.0");
}

#[test]
fn a_fork_carries_everything_else_over_and_is_independent() {
    let mut source = pack_named("Original", "1.0.0");
    source.put_string("packModLoader", "fabric");
    source.put_bool("packUnstable", true);
    source.add_mod(mod_entry("JEI", "mods/jei.jar"));
    let mut fork = source.fork_of("My Fork").unwrap();
    assert_eq!(fork.get_string("packModLoader"), "fabric");
    assert!(fork.get_bool("packUnstable"));
    assert_eq!(fork.mods()[0].name(), "JEI");
    fork.add_mod(mod_entry("REI", "mods/rei.jar"));
    fork.put_string("packModLoader", "forge");
    assert_eq!(source.mods().len(), 1);
    assert_eq!(source.get_string(KEY_PACK_NAME), "Original");
    assert_eq!(source.get_string("packModLoader"), "fabric");
    assert_eq!(fork.mods().len(), 2);
}

#[test]
fn a_fork_needs_a_name_and_trims_it() {
    let source = pack_named("Original", "1.0.0");
    assert!(matches!(
        source.fork_of("   "),
        Err(DocError::ForkNameBlank)
    ));
    assert_eq!(
        source
            .fork_of("  My Fork  ")
            .unwrap()
            .get_string(KEY_PACK_NAME),
        "My Fork"
    );
}

#[test]
fn mods_are_added_in_order() {
    let mut d = pack_named("Pack", "1.0.0");
    assert!(d.add_mod(mod_entry("First", "mods/a.jar")));
    assert!(d.add_mod(mod_entry("Second", "mods/b.jar")));
    assert_eq!(d.mods()[0].name(), "First");
    assert_eq!(d.mods()[1].name(), "Second");
    assert_eq!(d.mods()[0].local(), "mods/a.jar");
}

#[test]
fn duplicate_or_blank_local_paths_are_refused() {
    let mut d = pack_named("Pack", "1.0.0");
    assert!(d.add_mod(mod_entry("JEI", "mods/JEI.jar")));
    assert!(!d.add_mod(mod_entry("JEI (again)", "mods/JEI.jar")));
    assert!(!d.add_mod(mod_entry("jei", "mods/jei.jar")));
    assert!(!d.add_mod(mod_entry("Nameless", "")));
    assert!(!d.add_mod(mod_entry("Nameless", "   ")));
    assert_eq!(d.mods().len(), 1);
}

#[test]
fn an_added_mod_survives_a_serialization_round_trip() {
    let mut d = pack_named("Pack", "1.0.0");
    let mut e = mod_entry("JEI", "mods/jei.jar");
    e.set_hash("abc");
    e.set_modrinth_slug("jei");
    d.add_mod(e);
    let read = ModPackDocument::from_json(&d.to_pretty_json())
        .unwrap()
        .mods()
        .remove(0);
    assert_eq!(read.hash(), "abc");
    assert_eq!(read.modrinth_slug(), "jei");
}

#[test]
fn mods_are_removed_by_local_name_or_slug() {
    let mut d = pack_with_two_mods();
    assert!(d.remove_mod("mods/jei.jar"));
    assert_eq!(d.mods().len(), 1);
    assert_eq!(d.mods()[0].name(), "REI");

    let mut d = pack_with_two_mods();
    assert!(d.remove_mod("JEI"));
    assert_eq!(d.mods().len(), 1);

    let mut d = pack_named("Pack", "1.0.0");
    let mut e = mod_entry("Just Enough Items", "mods/jei.jar");
    e.set_modrinth_slug("jei");
    d.add_mod(e);
    assert!(d.remove_mod("jei"));
    assert!(d.mods().is_empty());
}

#[test]
fn removal_is_case_insensitive_trimmed_and_single() {
    let mut d = pack_with_two_mods();
    assert!(d.remove_mod("  jei  "));
    assert_eq!(d.mods().len(), 1);
    let mut d = pack_with_two_mods();
    assert!(!d.remove_mod("not-installed"));
    assert!(!d.remove_mod("  "));
    assert_eq!(d.mods().len(), 2);
    let mut d = pack_named("Pack", "1.0.0");
    d.add_mod(mod_entry("Shared", "mods/a.jar"));
    d.add_mod(mod_entry("Shared", "mods/b.jar"));
    assert!(d.remove_mod("Shared"));
    assert_eq!(d.mods().len(), 1);
    assert_eq!(d.mods()[0].local(), "mods/b.jar");
}

fn path_of(url: &str) -> PathBuf {
    url::Url::parse(url).unwrap().to_file_path().unwrap()
}

#[test]
fn a_manifest_is_written_and_its_url_points_at_it() {
    let tmp = tempfile::tempdir().unwrap();
    let url = pack_named("My Pack", "1.0.0")
        .write_local_manifest(tmp.path(), true)
        .unwrap();
    assert!(url.starts_with("file:"), "{url}");
    let path = path_of(&url);
    assert!(path.is_file());
    let text = fs::read_to_string(&path).unwrap();
    assert_eq!(
        ModPackDocument::from_json(&text)
            .unwrap()
            .get_string(KEY_PACK_NAME),
        "My Pack"
    );
}

#[test]
fn a_missing_directory_is_created() {
    let tmp = tempfile::tempdir().unwrap();
    let nested = tmp.path().join("imported-manifests");
    pack_named("Pack", "1.0.0")
        .write_local_manifest(&nested, true)
        .unwrap();
    assert!(nested.is_dir());
}

#[test]
fn a_hostile_pack_name_cannot_escape_the_target_directory() {
    let tmp = tempfile::tempdir().unwrap();
    let root = fs::canonicalize(tmp.path()).unwrap();
    for hostile in [
        "../escaped",
        "../../etc/passwd",
        "a/b/c",
        "..\\\\windows",
        "with\nnewline",
        "C:\\x",
    ] {
        let url = pack_named(hostile, "1.0.0")
            .write_local_manifest(tmp.path(), true)
            .unwrap();
        let parent = fs::canonicalize(path_of(&url).parent().unwrap()).unwrap();
        assert_eq!(parent, root, "escaped with name {hostile:?}");
    }
}

#[test]
fn a_name_that_sanitizes_to_nothing_is_refused() {
    let tmp = tempfile::tempdir().unwrap();
    assert!(matches!(
        pack_named("!!! ---", "1.0.0").write_local_manifest(tmp.path(), true),
        Err(DocError::NameUnusable)
    ));
}

#[test]
fn a_near_duplicate_name_cannot_overwrite_when_overwriting_is_refused() {
    let tmp = tempfile::tempdir().unwrap();
    let first = pack_named("My Pack", "1.0.0")
        .write_local_manifest(tmp.path(), false)
        .unwrap();
    let second = pack_named("my-pack", "9.9.9").write_local_manifest(tmp.path(), false);
    assert!(matches!(second, Err(DocError::AlreadyExists(_))));
    let text = fs::read_to_string(path_of(&first)).unwrap();
    assert_eq!(
        ModPackDocument::from_json(&text)
            .unwrap()
            .get_string(KEY_PACK_NAME),
        "My Pack"
    );
}

#[test]
fn the_local_manifest_path_is_derived_from_the_sanitized_lower_cased_name() {
    let tmp = tempfile::tempdir().unwrap();
    assert_eq!(
        pack_named("My Pack!", "1.0.0")
            .local_manifest_path_in(tmp.path())
            .unwrap(),
        tmp.path().join("created-mypack.json")
    );
    assert_eq!(
        ModPackDocument::local_manifest_key_of(Some("my-PACK")),
        "mypack"
    );
    assert_eq!(ModPackDocument::local_manifest_key_of(Some("!!! ---")), "");
    assert_eq!(ModPackDocument::local_manifest_key_of(None), "");
}

#[test]
fn rewriting_the_same_pack_reuses_the_same_file() {
    let tmp = tempfile::tempdir().unwrap();
    let mut d = pack_named("Pack", "1.0.0");
    let first = d.write_local_manifest(tmp.path(), true).unwrap();
    d.put_string(KEY_PACK_VERSION, "2.0.0");
    let second = d.write_local_manifest(tmp.path(), true).unwrap();
    assert_eq!(first, second);
    assert_eq!(fs::read_dir(tmp.path()).unwrap().count(), 1);
    let text = fs::read_to_string(path_of(&second)).unwrap();
    assert_eq!(
        ModPackDocument::from_json(&text)
            .unwrap()
            .get_string(KEY_PACK_VERSION),
        "2.0.0"
    );
}

#[test]
fn doc_errors_map_to_keys() {
    assert_eq!(
        DocError::ForkNameBlank.key(),
        "editor.document.error.forkNameBlank"
    );
    assert_eq!(
        DocError::from(ManifestParseError::NotAnObject).key(),
        "error.manifest.notObject"
    );
}

// --- FileEntry -------------------------------------------------------------------------------

#[test]
fn a_new_entry_defaults_to_sha1_and_required_on_both_sides() {
    let e = FileEntry::default();
    assert_eq!(e.hash_type(), "sha1");
    assert!(e.client_req() && e.server_req());
    assert_eq!(e.name(), "");
    assert_eq!(e.hash(), "");
    assert_eq!(
        FileEntry::new("", "", "", "", None, false, false).hash_type(),
        "sha1"
    );
}

#[test]
fn setting_no_hash_type_restores_the_default() {
    let mut e = FileEntry::default();
    e.set_hash_type(Some("sha256"));
    e.set_hash_type(None);
    assert_eq!(e.hash_type(), "sha1");
}

#[test]
fn extra_hash_lookup_is_case_insensitive() {
    let mut e = FileEntry::default();
    e.put_extra_hash("SHA256", Some("abc"));
    assert_eq!(e.extra_hash("sha256"), Some("abc"));
    assert_eq!(e.extra_hash("Sha256"), Some("abc"));
}

#[test]
fn storing_a_blank_or_missing_value_clears_the_slot() {
    let mut e = FileEntry::default();
    e.put_extra_hash("sha1", Some("abc"));
    e.put_extra_hash("sha1", Some("   "));
    assert_eq!(e.extra_hash("sha1"), None);
    e.put_extra_hash("md5", Some("def"));
    e.put_extra_hash("md5", None);
    assert_eq!(e.extra_hash("md5"), None);
    assert!(e.extra_hashes().is_empty());
}

#[test]
fn write_honours_a_md5_primary_with_extras() {
    let mut d = ModPackDocument::blank();
    let mut e = FileEntry::new("n", "r", "l", "m5", Some("MD5"), true, true);
    e.put_extra_hash("sha1", Some("s1"));
    e.put_extra_hash("sha256", Some("s256"));
    d.write_file_list(FileList::Mods, &[e]);
    let w = written(&d, FileList::Mods);
    assert_eq!(
        (w["sha1"].clone(), w["md5"].clone(), w["sha256"].clone()),
        (json!("s1"), json!("m5"), json!("s256"))
    );
}
