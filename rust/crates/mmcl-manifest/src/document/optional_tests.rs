// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Port of `ModPackDocumentOptionalModsTest`: `packOptionalMods` in the editor document, its
//! side defaults, proof that every path that rewrites a manifest (fork, editor save, the MCP
//! add/remove mod edits) keeps the list intact, and the editor's validation rules.

use super::*;
use crate::optional;

const MANIFEST: &str = r#"{
  "packName": "Alto",
  "packVersion": "1.2.0",
  "packMods": [
    { "name": "JEI", "remote": "https://example.invalid/jei.jar", "local": "jei.jar",
      "sha1": "1111111111111111111111111111111111111111", "md5": "-1", "sha256": "-1",
      "clientReq": true, "serverReq": true }
  ],
  "packOptionalMods": [
    { "name": "AUSM", "remote": "https://example.invalid/ausm.jar", "local": "ausm.jar",
      "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      "description": "Shader pack support. Slower terrain rendering.",
      "defaultEnabled": false, "requires": ["lib.jar"], "conflictsWith": ["fast.jar"] },
    { "name": "Lib", "remote": "https://example.invalid/lib.jar", "local": "lib.jar",
      "defaultEnabled": true, "serverReq": true },
    { "name": "Fast", "remote": "https://example.invalid/fast.jar", "local": "fast.jar" }
  ]
}"#;

fn echo(key: &str, args: &[String]) -> String {
    std::iter::once(key.to_string())
        .chain(args.iter().cloned())
        .collect::<Vec<_>>()
        .join("|")
}

fn doc() -> ModPackDocument {
    ModPackDocument::from_json(MANIFEST).unwrap()
}

fn optional_array(d: &ModPackDocument) -> Value {
    d.json()["packOptionalMods"].clone()
}

fn reread(d: &ModPackDocument) -> ModPackDocument {
    ModPackDocument::from_json(&d.to_pretty_json()).unwrap()
}

#[test]
fn reading_keeps_the_extra_fields_and_optional_side_defaults() {
    let optional = doc().optional_mods();
    assert_eq!(optional.len(), 3);
    let ausm = &optional[0];
    assert_eq!(ausm.name(), "AUSM");
    assert_eq!(
        ausm.description(),
        "Shader pack support. Slower terrain rendering."
    );
    assert!(!ausm.default_enabled());
    assert_eq!(ausm.requires(), ["lib.jar"]);
    assert_eq!(ausm.conflicts_with(), ["fast.jar"]);
    assert!(ausm.client_req(), "absent clientReq means client-side");
    assert!(
        !ausm.server_req(),
        "absent serverReq means client-only for optional mods"
    );
    assert_eq!(ausm.hash_type(), "sha256");
    assert!(optional[1].default_enabled());
    assert!(optional[1].server_req());
}

#[test]
fn writing_round_trips_every_field() {
    let mut d = doc();
    let before = optional_array(&d);
    let entries = d.optional_mods();
    d.write_file_list(FileList::OptionalMods, &entries);

    let again = reread(&d);
    let optional = again.optional_mods();
    assert_eq!(optional.len(), 3);
    assert_eq!(
        optional[0].description(),
        "Shader pack support. Slower terrain rendering."
    );
    assert_eq!(optional[0].requires(), ["lib.jar"]);
    assert_eq!(optional[0].conflicts_with(), ["fast.jar"]);
    assert!(!optional[0].server_req());
    assert!(optional[1].default_enabled());

    let ausm = &optional_array(&again)[0];
    assert_eq!(ausm["sha256"], before[0]["sha256"]);
    assert_eq!(
        ausm["serverReq"],
        Value::Bool(false),
        "a save writes the explicit false, so a server never reads it as true"
    );
}

#[test]
fn entries_are_written_in_the_java_field_order() {
    let mut d = doc();
    let entries = d.optional_mods();
    d.write_file_list(FileList::OptionalMods, &entries);
    let keys = |i: usize| -> Vec<String> {
        optional_array(&d)[i]
            .as_object()
            .unwrap()
            .keys()
            .cloned()
            .collect()
    };
    assert_eq!(
        keys(0),
        [
            "name",
            "remote",
            "local",
            "sha1",
            "md5",
            "sha256",
            "clientReq",
            "serverReq",
            "description",
            "defaultEnabled",
            "requires",
            "conflictsWith"
        ]
    );
    // Empty requires / conflictsWith are left out; description and defaultEnabled never are.
    assert_eq!(keys(2).last().map(String::as_str), Some("defaultEnabled"));
    assert_eq!(optional_array(&d)[2]["description"], Value::from(""));
}

#[test]
fn an_empty_list_leaves_the_key_out() {
    let mut d = ModPackDocument::from_json(
        r#"{"packName":"P","packVersion":"1","packMods":[],"packConfigs":[]}"#,
    )
    .unwrap();
    d.write_file_list(FileList::OptionalMods, &[]);
    assert!(!d.json().contains_key("packOptionalMods"));
    assert!(d.optional_mods().is_empty());

    // Removing the last optional mod drops the key but keeps the other keys' order.
    let mut d = doc();
    d.write_file_list(FileList::OptionalMods, &[]);
    assert!(!d.json().contains_key("packOptionalMods"));
    assert_eq!(
        d.json().keys().collect::<Vec<_>>(),
        ["packName", "packVersion", "packMods"]
    );
}

#[test]
fn a_blank_document_has_no_optional_key() {
    let d = ModPackDocument::blank();
    assert!(!d.json().contains_key("packOptionalMods"));
    for list in FileList::ALL.into_iter().filter(|l| !l.is_optional()) {
        assert!(d.json()[list.key()].is_array(), "{}", list.key());
    }
}

#[test]
fn the_runtime_model_reads_what_the_document_writes() {
    let mut d = doc();
    let entries = d.optional_mods();
    d.write_file_list(FileList::OptionalMods, &entries);
    let parsed = crate::PackManifest::parse(&d.to_pretty_json()).unwrap();
    let optional = parsed.optional_mods();
    assert_eq!(optional.len(), 3);
    assert!(!optional[0].is_server_side());
    assert_eq!(optional[0].requires(), ["lib.jar"]);
    assert!(optional[1].default_enabled);
    assert_eq!(
        optional::resolve_enabled(optional, &optional::Choices::new()).len(),
        1
    );
}

// ---- every rewrite path keeps the list (issue acceptance item 5) ----

#[test]
fn forking_keeps_the_optional_list() {
    let source = doc();
    let fork = source.fork_of("Alto Fork").unwrap();
    assert_eq!(optional_array(&source), optional_array(&fork));
}

#[test]
fn an_editor_save_keeps_the_optional_list() {
    // The editor reads every list into rows and writes every list back on save.
    let mut d = doc();
    for list in FileList::ALL {
        let entries = d.read_file_list(list);
        d.write_file_list(list, &entries);
    }
    let optional = reread(&d).optional_mods();
    assert_eq!(
        optional.iter().map(FileEntry::local).collect::<Vec<_>>(),
        ["ausm.jar", "lib.jar", "fast.jar"]
    );
    assert_eq!(optional[0].conflicts_with(), ["fast.jar"]);
}

#[test]
fn adding_a_mod_keeps_the_optional_list() {
    let mut d = doc();
    let before = optional_array(&d);
    assert!(d.add_mod(FileEntry::new(
        "Other",
        "https://example.invalid/o.jar",
        "o.jar",
        "",
        Some("sha1"),
        true,
        true
    )));
    assert_eq!(before, optional_array(&reread(&d)));
}

#[test]
fn removing_a_mod_keeps_the_optional_list() {
    let mut d = doc();
    let before = optional_array(&d);
    assert!(d.remove_mod("jei.jar"));
    assert_eq!(before, optional_array(&reread(&d)));
}

#[test]
fn remove_mod_does_not_touch_optional_mods() {
    let mut d = doc();
    assert!(!d.remove_mod("ausm.jar"));
    assert_eq!(d.optional_mods().len(), 3);
}

#[test]
fn adding_a_mod_whose_file_is_an_optional_mod_is_refused() {
    let mut d = doc();
    assert!(!d.add_mod(FileEntry::new(
        "AUSM",
        "https://example.invalid/a.jar",
        "mods/AUSM.jar",
        "",
        Some("sha1"),
        true,
        true
    )));
    assert_eq!(d.mods().len(), 1);
}

// ---- validation ----

#[test]
fn a_valid_pack_has_no_optional_problems() {
    assert!(doc().validate_optional_mods(&echo).is_empty());
    assert!(doc().validate(&echo).is_empty());
}

#[test]
fn a_file_in_both_lists_is_refused() {
    let mut d = doc();
    let mut optional = d.optional_mods();
    optional.push(FileEntry::new(
        "JEI",
        "https://example.invalid/jei.jar",
        "JEI.jar",
        "",
        Some("sha1"),
        true,
        false,
    ));
    d.write_file_list(FileList::OptionalMods, &optional);
    assert_eq!(
        d.validate_optional_mods(&echo),
        ["editor.validate.optional.inBothLists|JEI.jar"]
    );
}

#[test]
fn an_optional_mod_must_be_a_plain_file_name() {
    let mut d = doc();
    let mut optional = d.optional_mods();
    optional[1].set_local("mods/lib.jar");
    optional[2].set_local("extras\\fast.jar");
    d.write_file_list(FileList::OptionalMods, &optional);
    assert_eq!(
        d.validate_optional_mods(&echo),
        [
            "editor.validate.optional.folder|mods/lib.jar|lib.jar",
            "editor.validate.optional.folder|extras\\fast.jar|fast.jar",
            "editor.validate.optional.unknownConflict|ausm.jar|fast.jar"
        ]
    );
}

#[test]
fn requires_and_conflicts_must_name_optional_mods_of_this_pack() {
    let mut d = doc();
    let mut optional = d.optional_mods();
    optional[0].set_requires(&["lib.jar", "jei.jar"]);
    optional[0].set_conflicts_with(&["nothing.jar"]);
    optional[2].set_requires(&["mods/FAST.jar"]);
    d.write_file_list(FileList::OptionalMods, &optional);
    assert_eq!(
        d.validate_optional_mods(&echo),
        [
            "editor.validate.optional.unknownRequires|ausm.jar|jei.jar",
            "editor.validate.optional.unknownConflict|ausm.jar|nothing.jar",
            "editor.validate.optional.selfReference|fast.jar"
        ]
    );
}

#[test]
fn a_repeated_optional_mod_is_reported_once() {
    let mut d = doc();
    let mut optional = d.optional_mods();
    optional.push(optional[2].clone());
    optional.push(optional[2].clone());
    d.write_file_list(FileList::OptionalMods, &optional);
    assert_eq!(
        d.validate_optional_mods(&echo),
        ["editor.validate.optional.duplicate|fast.jar"]
    );
}

#[test]
fn validate_includes_the_optional_rules() {
    let mut d = doc();
    let mut optional = d.optional_mods();
    optional[0].set_requires(&["ghost.jar"]);
    d.write_file_list(FileList::OptionalMods, &optional);
    assert!(
        d.validate(&echo)
            .contains(&"editor.validate.optional.unknownRequires|ausm.jar|ghost.jar".to_string())
    );
}

#[test]
fn name_lists_are_trimmed_and_blanks_dropped() {
    let d = ModPackDocument::from_json(
        r#"{"packOptionalMods":[{"local":"a.jar","requires":[" b.jar ","",null,{"x":1},3]}]}"#,
    )
    .unwrap();
    assert_eq!(d.optional_mods()[0].requires(), ["b.jar", "3"]);
}
