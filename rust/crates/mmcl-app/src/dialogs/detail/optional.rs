// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The text of the detail dialog's Optional mods section (Java `OptionalModMessages`): what a
//! toggle did and why other mods switched with it, and each mod's requirement and conflict
//! notes. Pure, with a pluggable resolver, so the wording is testable without GPUI or a
//! locale.

use mmcl_engine::optional::{OptionalMod, Reason, ToggleResult, key_of};

/// Resolves a localization key and its arguments to display text.
pub type Resolver<'a> = &'a dyn Fn(&str, &[String]) -> String;

/// The production resolver.
pub fn localize(key: &str, args: &[String]) -> String {
    let a: Vec<mmcl_i18n::FmtArg<'_>> = args
        .iter()
        .map(|s| mmcl_i18n::FmtArg::from(s.as_str()))
        .collect();
    mmcl_i18n::format(key, &a)
}

/// A toggle's outcome, one sentence per line: the toggled mod's new state (or why it could
/// not change), then every other mod that switched with it and why. Never empty.
pub fn describe_toggle(
    result: &ToggleResult,
    name: &str,
    enable: bool,
    resolve: Resolver<'_>,
) -> Vec<String> {
    let name = name.to_string();
    if !result.applied {
        let key = if result.refusal == Some(Reason::Unknown) {
            "modal.optionalMods.refused.unknown"
        } else {
            "modal.optionalMods.refused.unsatisfiable"
        };
        return vec![resolve(key, &[name])];
    }
    let status = if enable {
        "modal.optionalMods.status.on"
    } else {
        "modal.optionalMods.status.off"
    };
    let mut lines = vec![resolve(status, &[name])];
    for change in &result.changes {
        let has_cause = !change.cause.is_empty();
        let key = match change.reason {
            Reason::RequiredBy if has_cause => "modal.optionalMods.reason.requiredBy",
            Reason::RequiredBy => "modal.optionalMods.reason.turnedOn",
            Reason::ConflictsWith => "modal.optionalMods.reason.conflictsWith",
            Reason::NoLongerNeeded if has_cause => "modal.optionalMods.reason.noLongerNeeded",
            _ if has_cause => "modal.optionalMods.reason.requiresOff",
            _ => "modal.optionalMods.reason.turnedOff",
        };
        lines.push(if has_cause {
            resolve(key, &[change.name.clone(), change.cause.clone()])
        } else {
            resolve(key, std::slice::from_ref(&change.name))
        });
    }
    lines
}

/// What `m` needs and what it cannot be used with, naming the other optional mods by display
/// name. Conflicts are shown from both sides; names that are not optional mods of the pack are
/// left out. Zero, one or two lines.
pub fn describe_relations(
    m: &OptionalMod,
    all: &[OptionalMod],
    resolve: Resolver<'_>,
) -> Vec<String> {
    let display = |local: &str| {
        let key = key_of(local);
        all.iter()
            .find(|o| o.key() == key)
            .map(|o| o.display_name())
    };
    let names_of = |locals: &[String]| {
        let mut out: Vec<String> = Vec::new();
        for n in locals.iter().filter_map(|l| display(l)) {
            if !out.contains(&n) {
                out.push(n);
            }
        }
        out
    };
    let mut lines = Vec::new();
    let needs = names_of(m.requires());
    if !needs.is_empty() {
        lines.push(resolve("modal.optionalMods.requires", &[needs.join(", ")]));
    }
    let own = m.key();
    let mut conflicts = names_of(m.conflicts_with());
    for other in all.iter().filter(|o| o.key() != own) {
        let names_this = other.conflicts_with().iter().any(|n| key_of(n) == own);
        if names_this && !conflicts.contains(&other.display_name()) {
            conflicts.push(other.display_name());
        }
    }
    if !conflicts.is_empty() {
        lines.push(resolve(
            "modal.optionalMods.conflicts",
            &[conflicts.join(", ")],
        ));
    }
    lines
}

#[cfg(test)]
mod tests {
    //! Port of `OptionalModMessagesTest`.
    use super::*;
    use mmcl_engine::optional::{Choices, toggle};

    fn echo(key: &str, args: &[String]) -> String {
        std::iter::once(key.to_string())
            .chain(args.iter().cloned())
            .collect::<Vec<_>>()
            .join("|")
    }

    fn m(name: &str, local: &str, on: bool, req: &[&str], conf: &[&str]) -> OptionalMod {
        let list = |v: &[&str]| (!v.is_empty()).then(|| v.iter().map(|s| s.to_string()).collect());
        OptionalMod {
            name: Some(name.into()),
            remote: Some(format!("https://example.invalid/{local}")),
            local: Some(local.into()),
            default_enabled: on,
            requires: list(req),
            conflicts_with: list(conf),
            ..Default::default()
        }
    }

    fn mods() -> Vec<OptionalMod> {
        vec![
            m("AUSM", "ausm.jar", false, &["lib.jar"], &["fast.jar"]),
            m("Lib", "lib.jar", false, &[], &[]),
            m("Fast", "fast.jar", true, &[], &[]),
            m("Fast Addon", "addon.jar", true, &["fast.jar"], &[]),
        ]
    }

    #[test]
    fn a_toggle_explains_every_other_mod_that_switched() {
        let r = toggle(&mods(), &Choices::new(), "ausm.jar", true);
        assert_eq!(
            describe_toggle(&r, "AUSM", true, &echo),
            [
                "modal.optionalMods.status.on|AUSM",
                "modal.optionalMods.reason.requiredBy|Lib|AUSM",
                "modal.optionalMods.reason.conflictsWith|Fast|AUSM",
                "modal.optionalMods.reason.requiresOff|Fast Addon|Fast"
            ]
        );
    }

    #[test]
    fn a_plain_toggle_off_says_when_it_applies() {
        let r = toggle(&mods(), &Choices::new(), "addon.jar", false);
        assert_eq!(
            describe_toggle(&r, "Fast Addon", false, &echo),
            ["modal.optionalMods.status.off|Fast Addon"]
        );
    }

    #[test]
    fn turning_a_mod_off_explains_the_requirements_that_went_off_with_it() {
        let on = toggle(&mods(), &Choices::new(), "ausm.jar", true);
        let off = toggle(&mods(), &on.choices, "ausm.jar", false);
        assert_eq!(
            describe_toggle(&off, "AUSM", false, &echo),
            [
                "modal.optionalMods.status.off|AUSM",
                "modal.optionalMods.reason.noLongerNeeded|Lib|AUSM"
            ]
        );
    }

    #[test]
    fn a_refused_toggle_says_nothing_changed() {
        let bad = [
            m("Bad", "bad.jar", false, &["lib.jar"], &["lib.jar"]),
            m("Lib", "lib.jar", false, &[], &[]),
        ];
        let r = toggle(&bad, &Choices::new(), "bad.jar", true);
        assert_eq!(
            describe_toggle(&r, "Bad", true, &echo),
            ["modal.optionalMods.refused.unsatisfiable|Bad"]
        );
        let r = toggle(&bad, &Choices::new(), "gone.jar", true);
        assert_eq!(
            describe_toggle(&r, "Gone", true, &echo),
            ["modal.optionalMods.refused.unknown|Gone"]
        );
    }

    #[test]
    fn relations_name_other_mods_by_display_name_and_show_conflicts_both_ways() {
        let all = mods();
        assert_eq!(
            describe_relations(&all[0], &all, &echo),
            [
                "modal.optionalMods.requires|Lib",
                "modal.optionalMods.conflicts|Fast"
            ]
        );
        // Fast names nothing, but AUSM names it.
        assert_eq!(
            describe_relations(&all[2], &all, &echo),
            ["modal.optionalMods.conflicts|AUSM"]
        );
        assert!(describe_relations(&all[1], &all, &echo).is_empty());
    }
}
