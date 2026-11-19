// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Ports of `OptionalModResolverTest` and the model half of `OptionalModSyncTest`.

use super::*;
use crate::PackManifest;

fn m(local: &str, default_on: bool, requires: &[&str], conflicts: &[&str]) -> OptionalMod {
    let list = |v: &[&str]| (!v.is_empty()).then(|| v.iter().map(|s| s.to_string()).collect());
    OptionalMod {
        name: Some(local.replace(".jar", "")),
        remote: Some(format!("https://example.invalid/{local}")),
        local: Some(local.into()),
        description: Some(format!("desc {local}")),
        default_enabled: default_on,
        requires: list(requires),
        conflicts_with: list(conflicts),
        ..Default::default()
    }
}

fn plain(local: &str, default_on: bool) -> OptionalMod {
    m(local, default_on, &[], &[])
}

fn sided(local: &str, client: bool, server: bool, default_on: bool) -> OptionalMod {
    OptionalMod {
        client_req: client,
        server_req: server,
        ..plain(local, default_on)
    }
}

fn choices(pairs: &[(&str, bool)]) -> Choices {
    pairs.iter().map(|(k, v)| (k.to_string(), *v)).collect()
}

fn set(keys: &[&str]) -> IndexSet<String> {
    keys.iter().map(|s| s.to_string()).collect()
}

#[test]
fn absent_choices_follow_defaults() {
    let mods = [plain("a.jar", false), plain("b.jar", true)];
    assert_eq!(resolve_enabled(&mods, &Choices::new()), set(&["b.jar"]));
}

#[test]
fn stored_choices_override_defaults_in_any_spelling() {
    let mods = [plain("AUSM.jar", false), plain("b.jar", true)];
    let c = choices(&[("mods/ausm.JAR", true), ("B.jar", false)]);
    assert_eq!(resolve_enabled(&mods, &c), set(&["ausm.jar"]));
}

#[test]
fn requires_are_turned_on_transitively() {
    let mods = [
        m("a.jar", true, &["b.jar"], &[]),
        m("b.jar", false, &["c.jar"], &[]),
        plain("c.jar", false),
    ];
    assert_eq!(
        resolve_enabled(&mods, &Choices::new()),
        set(&["a.jar", "b.jar", "c.jar"])
    );
}

#[test]
fn unknown_requires_and_conflicts_are_ignored() {
    let mods = [m("a.jar", true, &["ghost.jar"], &["phantom.jar"])];
    assert_eq!(resolve_enabled(&mods, &Choices::new()), set(&["a.jar"]));
}

#[test]
fn conflicting_defaults_let_the_earlier_entry_win_in_either_direction() {
    let mods = [plain("a.jar", true), m("b.jar", true, &[], &["a.jar"])];
    assert_eq!(resolve_enabled(&mods, &Choices::new()), set(&["a.jar"]));
}

#[test]
fn a_group_that_conflicts_with_itself_stays_off() {
    let mods = [
        m("a.jar", true, &["b.jar"], &["b.jar"]),
        plain("b.jar", false),
    ];
    assert!(resolve_enabled(&mods, &Choices::new()).is_empty());
}

#[test]
fn cyclic_requires_terminate() {
    let mods = [
        m("a.jar", true, &["b.jar"], &[]),
        m("b.jar", false, &["a.jar"], &[]),
    ];
    assert_eq!(
        resolve_enabled(&mods, &Choices::new()),
        set(&["a.jar", "b.jar"])
    );
}

#[test]
fn toggling_on_turns_requires_on_and_conflicts_off_with_reasons() {
    let mods = [
        plain("shaders.jar", false),
        m("ausm.jar", false, &["lib.jar"], &["fast.jar"]),
        plain("lib.jar", false),
        plain("fast.jar", true),
        m("fastaddon.jar", true, &["fast.jar"], &[]),
    ];
    let r = toggle(&mods, &Choices::new(), "ausm.jar", true);
    assert!(r.applied);
    assert_eq!(r.refusal, None);
    assert_eq!(r.enabled, set(&["ausm.jar", "lib.jar"]));
    assert_eq!(r.choices.get("lib.jar"), Some(&true));
    assert_eq!(r.choices.get("fast.jar"), Some(&false));
    assert_eq!(r.choices.get("fastaddon.jar"), Some(&false));
    assert_eq!(r.changes.len(), 3);
    let lib = &r.changes[0];
    assert_eq!(lib.key, "lib.jar");
    assert!(lib.enabled);
    assert_eq!(lib.reason, Reason::RequiredBy);
    assert_eq!(lib.cause, "ausm");
    assert_eq!(r.changes[1].reason, Reason::ConflictsWith);
    assert_eq!(r.changes[1].cause, "ausm");
    let addon = &r.changes[2];
    assert!(!addon.enabled);
    assert_eq!(addon.reason, Reason::RequiresOff);
    assert_eq!(addon.cause, "fast");
}

#[test]
fn toggle_choices_keep_the_order_the_java_resolver_writes() {
    // Stored order is the config file's key order, so it must match Java's: the existing
    // choices, then the requires closure (breadth-first), then the turned-off mods.
    let mods = [
        m("a.jar", false, &["b.jar"], &["x.jar"]),
        m("b.jar", false, &["c.jar"], &[]),
        plain("c.jar", false),
        plain("x.jar", true),
    ];
    let r = toggle(&mods, &choices(&[("Z.jar", true)]), "a.jar", true);
    let keys: Vec<&str> = r.choices.keys().map(String::as_str).collect();
    assert_eq!(keys, ["z.jar", "a.jar", "b.jar", "c.jar", "x.jar"]);
}

#[test]
fn conflicts_are_honoured_when_only_the_other_side_names_them() {
    let mods = [plain("a.jar", true), m("b.jar", false, &[], &["a.jar"])];
    let r = toggle(&mods, &Choices::new(), "b.jar", true);
    assert!(r.applied);
    assert_eq!(r.enabled, set(&["b.jar"]));
    assert_eq!(r.changes[0].reason, Reason::ConflictsWith);
}

#[test]
fn toggling_off_turns_dependents_off_but_leaves_requirements_on() {
    let mods = [
        m("a.jar", false, &["b.jar"], &[]),
        plain("b.jar", false),
        m("c.jar", false, &["a.jar"], &[]),
    ];
    let on = toggle(&mods, &Choices::new(), "c.jar", true);
    assert_eq!(on.enabled, set(&["a.jar", "b.jar", "c.jar"]));
    let off = toggle(&mods, &on.choices, "a.jar", false);
    assert!(off.applied);
    assert_eq!(off.enabled, set(&["b.jar"]));
    assert_eq!(off.changes.len(), 1);
    assert_eq!(off.changes[0].key, "c.jar");
    assert_eq!(off.changes[0].reason, Reason::RequiresOff);
}

#[test]
fn an_unsatisfiable_toggle_is_refused_and_changes_nothing() {
    let mods = [
        m("a.jar", false, &["b.jar"], &["b.jar"]),
        plain("b.jar", true),
    ];
    let c = choices(&[("b.jar", true)]);
    let r = toggle(&mods, &c, "a.jar", true);
    assert!(!r.applied);
    assert_eq!(r.refusal, Some(Reason::Unsatisfiable));
    assert_eq!(r.enabled, set(&["b.jar"]));
    assert_eq!(r.choices, c);
    assert!(r.changes.is_empty());
}

#[test]
fn a_group_needing_what_it_conflicts_with_is_refused() {
    // a needs b; b needs c; a conflicts with d, and c needs d: turning d off would take c
    // (and so a's own group) off with it.
    let mods = [
        m("a.jar", false, &["b.jar"], &["d.jar"]),
        m("b.jar", false, &["c.jar"], &[]),
        m("c.jar", false, &["d.jar"], &[]),
        plain("d.jar", false),
    ];
    let r = toggle(&mods, &Choices::new(), "a.jar", true);
    assert!(!r.applied);
    assert_eq!(r.refusal, Some(Reason::Unsatisfiable));
}

#[test]
fn an_unknown_mod_is_refused() {
    let r = toggle(&[plain("a.jar", false)], &Choices::new(), "zzz.jar", true);
    assert!(!r.applied);
    assert_eq!(r.refusal, Some(Reason::Unknown));
}

#[test]
fn a_client_installs_only_client_side_mods_that_are_on() {
    let mods = [
        plain("a.jar", true),
        plain("b.jar", false),
        sided("s.jar", false, true, true),
    ];
    let picked = select_for_side(&mods, Side::Client, &Choices::new());
    assert_eq!(
        picked.iter().map(|m| m.key()).collect::<Vec<_>>(),
        ["a.jar"]
    );
}

#[test]
fn a_server_ignores_choices_and_installs_only_server_side_default_on_mods() {
    let mods = [
        plain("client.jar", true),
        sided("s1.jar", true, true, true),
        sided("s2.jar", true, true, false),
    ];
    let c = choices(&[("s2.jar", true), ("s1.jar", false)]);
    let picked = select_for_side(&mods, Side::Server, &c);
    assert_eq!(
        picked.iter().map(|m| m.key()).collect::<Vec<_>>(),
        ["s1.jar"]
    );
}

#[test]
fn a_server_does_not_follow_requires() {
    let mods = [
        OptionalMod {
            requires: Some(vec!["lib.jar".into()]),
            ..sided("s.jar", true, true, true)
        },
        sided("lib.jar", true, true, false),
    ];
    let picked = select_for_side(&mods, Side::Server, &Choices::new());
    assert_eq!(
        picked.iter().map(|m| m.key()).collect::<Vec<_>>(),
        ["s.jar"]
    );
}

#[test]
fn stored_choices_use_the_manifest_spelling() {
    let mods = [plain("AUSM.jar", false)];
    let r = toggle(&mods, &Choices::new(), "ausm.jar", true);
    assert_eq!(
        to_stored_choices(&mods, &r.choices),
        choices(&[("AUSM.jar", true)])
    );
}

const MANIFEST: &str = r#"{
  "packName": "Alto",
  "packVersion": "1.0.0",
  "packMods": [
    { "name": "JEI", "remote": "https://example.invalid/jei.jar", "local": "jei.jar",
      "sha1": "-1", "md5": "-1", "sha256": "-1", "clientReq": true, "serverReq": true }
  ],
  "packOptionalMods": [
    { "name": "AUSM", "remote": "https://example.invalid/ausm.jar", "local": "ausm.jar",
      "sha256": "abc", "description": "Shader support", "requires": ["lib.jar"],
      "conflictsWith": ["celeritas-extra.jar"] },
    { "name": "Lib", "remote": "https://example.invalid/lib.jar", "local": "lib.jar",
      "serverReq": true, "defaultEnabled": true },
    { "name": "Extra", "remote": "https://example.invalid/x.jar", "local": "celeritas-extra.jar",
      "clientReq": false, "serverReq": true },
    { "name": "Dup", "remote": "https://example.invalid/jei2.jar", "local": "JEI.jar",
      "defaultEnabled": true },
    { "name": "Nested", "remote": "https://example.invalid/n.jar", "local": "mods/nested.jar",
      "defaultEnabled": true, "serverReq": true }
  ]
}"#;

#[test]
fn the_model_applies_optional_side_defaults_and_reads_the_extra_fields() {
    let p = PackManifest::parse(MANIFEST).unwrap();
    let optional = p.optional_mods();
    assert_eq!(optional.len(), 5);

    let ausm = &optional[0];
    assert!(ausm.is_client_side(), "absent clientReq means client-side");
    assert!(
        !ausm.is_server_side(),
        "absent serverReq means not on servers"
    );
    assert!(!ausm.default_enabled, "absent defaultEnabled means off");
    assert_eq!(ausm.description(), "Shader support");
    assert_eq!(ausm.requires(), ["lib.jar"]);
    assert_eq!(ausm.conflicts_with(), ["celeritas-extra.jar"]);
    assert_eq!(ausm.display_name(), "AUSM");

    let lib = &optional[1];
    assert_eq!(lib.local_name(), "lib.jar");
    assert!(lib.is_plain_file_name());
    assert!(lib.is_server_side() && lib.default_enabled);
    assert!(!optional[2].is_client_side());

    let nested = &optional[4];
    assert_eq!(
        nested.local_name(),
        "nested.jar",
        "a leading mods/ is not part of the name"
    );
    assert!(!nested.is_plain_file_name());
    assert_eq!(
        resolve_enabled(optional, &Choices::new()),
        set(&["lib.jar", "jei.jar", "nested.jar"])
    );
}

#[test]
fn a_null_client_req_keeps_the_optional_default_and_a_null_list_is_empty() {
    let p = PackManifest::parse(
        r#"{"packOptionalMods": [{"local": "a.jar", "clientReq": null, "requires": null}]}"#,
    )
    .unwrap();
    let a = &p.optional_mods()[0];
    assert!(a.is_client_side());
    assert!(a.requires().is_empty());
    assert!(
        PackManifest::parse("{}")
            .unwrap()
            .optional_mods()
            .is_empty()
    );
}

#[test]
fn the_list_survives_the_runtime_model_round_trip() {
    let p = PackManifest::parse(MANIFEST).unwrap();
    let again = PackManifest::from_value(&p.to_value()).unwrap();
    assert_eq!(again.optional_mods(), p.optional_mods());
}

#[test]
fn display_name_falls_back_to_the_file_name() {
    let mut a = plain("mods/A.jar", false);
    a.name = Some("  ".into());
    assert_eq!(a.display_name(), "A.jar");
    assert_eq!(a.key(), "a.jar");
    assert_eq!(key_of(" MODS\\Ausm.JAR "), "ausm.jar");
    assert_eq!(OptionalMod::default().key(), "");
    assert!(!OptionalMod::default().is_plain_file_name());
}
