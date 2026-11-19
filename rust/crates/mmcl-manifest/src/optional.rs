// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Optional mods (Java `GameOptionalMod` and `OptionalModResolver`): the manifest's top-level
//! `packOptionalMods` list and the rules that decide which of them are on for a player.
//!
//! The list is a top-level key of its own rather than a flag on a `packMods` entry so that a
//! launcher that does not know the feature never installs these jars: it ignores the unknown
//! key, and its mods-folder sweep removes any jar a newer launcher left behind.
//!
//! An entry has every `packMods` field plus `description`, `defaultEnabled` (false when
//! absent), `requires` and `conflictsWith` (the `local` names of other optional mods). Unlike
//! `packMods`, an absent `clientReq` means **true** and an absent `serverReq` means false: an
//! optional mod is client-only unless the entry says otherwise.
//!
//! # Resolution rules
//!
//! 1. Each mod's *wish* is the player's stored choice, or the entry's `defaultEnabled` when the
//!    player never chose.
//! 2. Mods are visited in manifest order. A mod that is wished on is turned on together with
//!    everything it `requires`, transitively, even a required mod whose own wish is off.
//! 3. If that group would conflict with a mod already on (in either direction: `conflictsWith`
//!    is symmetric), or within itself, the whole group stays off, so the mod listed earlier
//!    wins.
//! 4. Names in `requires` or `conflictsWith` that are not optional mods of the same pack are
//!    ignored here (the editor refuses to save them).
//!
//! A dedicated server has no player to choose: it ignores stored choices and installs exactly
//! the mods that are both `serverReq` and `defaultEnabled` (and does not follow `requires`).
//!
//! Mods are identified by [`key_of`] their `local` name: normalised with
//! [`normalize_mod_local_path`] and lower-cased, so `mods/AUSM.jar` and `ausm.jar` are one mod.

use indexmap::{IndexMap, IndexSet};
use serde::{Deserialize, Deserializer, Serialize};
use serde_json::Value;

use crate::document::normalize_mod_local_path;
use crate::files::{ManifestFile, Side};
use crate::lenient;

/// A player's stored choices: an optional mod's `local` name (any spelling) to on/off.
pub type Choices = IndexMap<String, bool>;

/// Gson leaves a primitive at its constructor value when the JSON holds `null`: `clientReq`
/// is seeded `true`, so an explicit `null` keeps it on.
fn boolean_default_true<'de, D: Deserializer<'de>>(d: D) -> Result<bool, D::Error> {
    Ok(match Value::deserialize(d)? {
        Value::Null => true,
        other => lenient::coerce_bool(&other),
    })
}

/// One entry of a manifest's `packOptionalMods` list.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct OptionalMod {
    #[serde(
        deserialize_with = "lenient::string",
        skip_serializing_if = "Option::is_none"
    )]
    pub name: Option<String>,
    #[serde(
        deserialize_with = "lenient::string",
        skip_serializing_if = "Option::is_none"
    )]
    pub remote: Option<String>,
    /// The file name in the pack's `mods` folder, exactly as written.
    #[serde(
        deserialize_with = "lenient::string",
        skip_serializing_if = "Option::is_none"
    )]
    pub local: Option<String>,
    #[serde(
        deserialize_with = "lenient::string",
        skip_serializing_if = "Option::is_none"
    )]
    pub sha1: Option<String>,
    #[serde(
        deserialize_with = "lenient::string",
        skip_serializing_if = "Option::is_none"
    )]
    pub md5: Option<String>,
    #[serde(
        deserialize_with = "lenient::string",
        skip_serializing_if = "Option::is_none"
    )]
    pub sha256: Option<String>,
    /// Installed on clients when on; `true` when absent.
    #[serde(rename = "clientReq", deserialize_with = "boolean_default_true")]
    pub client_req: bool,
    /// A dedicated server may install it (only when also `defaultEnabled`); `false` when
    /// absent.
    #[serde(rename = "serverReq", deserialize_with = "lenient::boolean")]
    pub server_req: bool,
    #[serde(
        rename = "modrinthSlug",
        deserialize_with = "lenient::string",
        skip_serializing_if = "Option::is_none"
    )]
    pub modrinth_slug: Option<String>,
    /// Short text shown to the player next to the toggle.
    #[serde(
        deserialize_with = "lenient::string",
        skip_serializing_if = "Option::is_none"
    )]
    pub description: Option<String>,
    /// On for a player who has not chosen; `false` when absent.
    #[serde(rename = "defaultEnabled", deserialize_with = "lenient::boolean")]
    pub default_enabled: bool,
    /// The `local` names of other optional mods that must be on with this one.
    #[serde(
        deserialize_with = "lenient::string_list",
        skip_serializing_if = "Option::is_none"
    )]
    pub requires: Option<Vec<String>>,
    /// The `local` names of other optional mods that must be off while this one is on.
    #[serde(
        rename = "conflictsWith",
        deserialize_with = "lenient::string_list",
        skip_serializing_if = "Option::is_none"
    )]
    pub conflicts_with: Option<Vec<String>>,
}

impl Default for OptionalMod {
    fn default() -> Self {
        OptionalMod {
            name: None,
            remote: None,
            local: None,
            sha1: None,
            md5: None,
            sha256: None,
            client_req: true,
            server_req: false,
            modrinth_slug: None,
            description: None,
            default_enabled: false,
            requires: None,
            conflicts_with: None,
        }
    }
}

impl OptionalMod {
    /// The entry's `local` with backslashes turned into slashes (Java `rawLocal`).
    fn raw_local(&self) -> Option<String> {
        self.local.as_deref().map(|l| l.replace('\\', "/"))
    }

    /// The `local` name normalised ([`normalize_mod_local_path`]); `""` when absent.
    pub fn local_name(&self) -> String {
        normalize_mod_local_path(self.raw_local().as_deref().unwrap_or(""))
    }

    /// The identity key choices and `requires` / `conflictsWith` are matched by; `""` when the
    /// entry has no local name.
    pub fn key(&self) -> String {
        key_of(self.raw_local().as_deref().unwrap_or(""))
    }

    /// Whether `local` is a plain file name, which an optional mod's must be: the sync joins it
    /// to the `mods` folder as written, so `mods/x.jar` would land in `mods/mods/`.
    pub fn is_plain_file_name(&self) -> bool {
        self.raw_local()
            .is_some_and(|l| !l.trim().is_empty() && !l.contains('/'))
    }

    /// The name to show the player: `name`, else the local name.
    pub fn display_name(&self) -> String {
        match self.name.as_deref() {
            Some(n) if !n.trim().is_empty() => n.to_string(),
            _ => self.local_name(),
        }
    }

    /// The description, `""` when absent.
    pub fn description(&self) -> &str {
        self.description.as_deref().unwrap_or("")
    }

    /// The `requires` names as written.
    pub fn requires(&self) -> &[String] {
        self.requires.as_deref().unwrap_or(&[])
    }

    /// The `conflictsWith` names as written.
    pub fn conflicts_with(&self) -> &[String] {
        self.conflicts_with.as_deref().unwrap_or(&[])
    }

    /// Installed on clients when on.
    pub fn is_client_side(&self) -> bool {
        self.client_req
    }

    /// A dedicated server installs it when it is also on by default.
    pub fn is_server_side(&self) -> bool {
        self.server_req
    }

    /// The entry as a plain file-list entry, with its side flags, for the sync and the hash
    /// check.
    pub fn to_manifest_file(&self) -> ManifestFile {
        ManifestFile {
            name: self.name.clone(),
            remote: self.remote.clone(),
            local: self.local.clone(),
            sha1: self.sha1.clone(),
            md5: self.md5.clone(),
            sha256: self.sha256.clone(),
            client_req: self.client_req,
            server_req: self.server_req,
            modrinth_slug: self.modrinth_slug.clone(),
        }
    }
}

/// Any spelling of an optional mod's local name as its identity key: normalised and
/// lower-cased; `""` for an empty name.
pub fn key_of(local: &str) -> String {
    normalize_mod_local_path(local).to_lowercase()
}

/// Why a toggle changed a mod the player did not touch, or why a toggle was refused.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum Reason {
    /// Turned on because a mod that is on requires it.
    RequiredBy,
    /// Turned off because it conflicts with a mod that is on.
    ConflictsWith,
    /// Turned off because it requires a mod that is now off.
    RequiresOff,
    /// Refused: the mod needs a group of mods that conflict among themselves.
    Unsatisfiable,
    /// Refused: no optional mod has that name.
    Unknown,
}

/// A mod a toggle switched as a side effect.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Change {
    /// The switched mod's identity key.
    pub key: String,
    /// Its display name.
    pub name: String,
    /// Its new state.
    pub enabled: bool,
    pub reason: Reason,
    /// The display name of the mod that caused it, or `""`.
    pub cause: String,
}

/// What a toggle does.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ToggleResult {
    /// Whether the toggle can be applied; `false` means nothing changes.
    pub applied: bool,
    /// Why it was refused; `None` when applied.
    pub refusal: Option<Reason>,
    /// The choices after the toggle, by identity key; the input choices when refused.
    pub choices: Choices,
    /// The identity keys that are on after the toggle, in manifest order.
    pub enabled: IndexSet<String>,
    /// The other mods that switched, in manifest order.
    pub changes: Vec<Change>,
}

/// Indexes mods by identity key in manifest order, keeping the first entry for a repeated
/// key and skipping entries without a name.
pub fn index(mods: &[OptionalMod]) -> IndexMap<String, &OptionalMod> {
    let mut by_key = IndexMap::new();
    for m in mods {
        let key = m.key();
        if !key.is_empty() {
            by_key.entry(key).or_insert(m);
        }
    }
    by_key
}

/// Re-keys stored choices by identity key, so any spelling of a local name matches.
pub fn normalize_choices(choices: &Choices) -> Choices {
    let mut out = Choices::new();
    for (name, on) in choices {
        let key = key_of(name);
        if !key.is_empty() {
            out.insert(key, *on);
        }
    }
    out
}

fn keys_of(names: &[String]) -> IndexSet<String> {
    names
        .iter()
        .map(|n| key_of(n))
        .filter(|k| !k.is_empty())
        .collect()
}

/// A mod and everything it requires, transitively, limited to optional mods of the pack;
/// breadth-first from `start`.
fn requires_closure(by_key: &IndexMap<String, &OptionalMod>, start: &str) -> IndexSet<String> {
    let mut group = IndexSet::new();
    let mut pending = std::collections::VecDeque::from([start.to_string()]);
    while let Some(key) = pending.pop_front() {
        let Some(m) = by_key.get(&key) else {
            continue;
        };
        if !group.insert(key) {
            continue;
        }
        pending.extend(keys_of(m.requires()));
    }
    group
}

/// The mods that need any of `keys`, transitively, not including `keys` themselves.
fn dependents_of(
    by_key: &IndexMap<String, &OptionalMod>,
    keys: &IndexSet<String>,
) -> IndexSet<String> {
    let mut off = keys.clone();
    let mut dependents = IndexSet::new();
    let mut grew = true;
    while grew {
        grew = false;
        for (key, m) in by_key {
            if off.contains(key) {
                continue;
            }
            if keys_of(m.requires()).iter().any(|r| off.contains(r)) {
                off.insert(key.clone());
                dependents.insert(key.clone());
                grew = true;
            }
        }
    }
    dependents
}

/// Whether two mods conflict, in either direction.
fn conflicts(by_key: &IndexMap<String, &OptionalMod>, a: &str, b: &str) -> bool {
    if a == b {
        return false;
    }
    let names = |k: &str, other: &str| {
        by_key
            .get(k)
            .is_some_and(|m| keys_of(m.conflicts_with()).contains(other))
    };
    names(a, b) || names(b, a)
}

fn conflicts_between<'a>(
    by_key: &IndexMap<String, &OptionalMod>,
    first: impl IntoIterator<Item = &'a String> + Clone,
    second: impl IntoIterator<Item = &'a String> + Clone,
) -> bool {
    first
        .into_iter()
        .any(|a| second.clone().into_iter().any(|b| conflicts(by_key, a, b)))
}

/// Which optional mods are on for a player: identity keys in manifest order.
pub fn resolve_enabled(mods: &[OptionalMod], choices: &Choices) -> IndexSet<String> {
    let by_key = index(mods);
    let wishes = normalize_choices(choices);
    let mut enabled: IndexSet<String> = IndexSet::new();
    for (key, m) in &by_key {
        if enabled.contains(key) {
            continue;
        }
        if !wishes.get(key).copied().unwrap_or(m.default_enabled) {
            continue;
        }
        let group = requires_closure(&by_key, key);
        if conflicts_between(&by_key, &group, &group)
            || conflicts_between(&by_key, &group, &enabled)
        {
            continue;
        }
        enabled.extend(group);
    }
    by_key
        .keys()
        .filter(|k| enabled.contains(*k))
        .cloned()
        .collect()
}

/// The optional mods a sync installs on `side`, in manifest order. A client installs the
/// resolved mods that are client-side; a server ignores `choices` and installs the mods that
/// are both server-side and on by default.
pub fn select_for_side<'a>(
    mods: &'a [OptionalMod],
    side: Side,
    choices: &Choices,
) -> Vec<&'a OptionalMod> {
    if mods.is_empty() {
        return Vec::new();
    }
    let by_key = index(mods);
    match side {
        Side::Server => by_key
            .values()
            .filter(|m| m.is_server_side() && m.default_enabled)
            .copied()
            .collect(),
        Side::Client => resolve_enabled(mods, choices)
            .iter()
            .filter_map(|k| by_key.get(k).copied())
            .filter(|m| m.is_client_side())
            .collect(),
    }
}

fn refused(reason: Reason, choices: Choices, enabled: IndexSet<String>) -> ToggleResult {
    ToggleResult {
        applied: false,
        refusal: Some(reason),
        choices,
        enabled,
        changes: Vec::new(),
    }
}

/// What turning one optional mod on or off does.
///
/// Turning a mod on also turns on everything it requires (transitively) and turns off
/// everything that conflicts with any of those, plus anything that needs a mod being turned
/// off. Turning a mod off also turns off everything that needs it. Every switched mod is
/// written into the returned choices explicitly, so a later toggle elsewhere does not quietly
/// undo it. A group that conflicts with itself is refused and nothing changes.
pub fn toggle(mods: &[OptionalMod], choices: &Choices, local: &str, enable: bool) -> ToggleResult {
    let by_key = index(mods);
    let before = normalize_choices(choices);
    let enabled_before = resolve_enabled(mods, &before);
    let target = key_of(local);
    if !by_key.contains_key(&target) {
        return refused(Reason::Unknown, before, enabled_before);
    }

    let mut turn_on: IndexSet<String> = IndexSet::new();
    let mut turn_off: IndexSet<String> = IndexSet::new();
    if enable {
        let group = requires_closure(&by_key, &target);
        if conflicts_between(&by_key, &group, &group) {
            return refused(Reason::Unsatisfiable, before, enabled_before);
        }
        for other in by_key.keys() {
            if !group.contains(other) && conflicts_between(&by_key, &group, [other]) {
                turn_off.insert(other.clone());
            }
        }
        turn_on.extend(group);
    } else {
        turn_off.insert(target.clone());
    }
    let dependents = dependents_of(&by_key, &turn_off);
    turn_off.extend(dependents);
    let before_len = turn_off.len();
    turn_off.retain(|k| !turn_on.contains(k));
    if turn_off.len() != before_len && enable {
        // A mod in the group needs something the group conflicts with.
        return refused(Reason::Unsatisfiable, before, enabled_before);
    }

    let mut after = before.clone();
    for k in &turn_on {
        after.insert(k.clone(), true);
    }
    for k in &turn_off {
        after.insert(k.clone(), false);
    }
    let enabled_after = resolve_enabled(mods, &after);
    if enabled_after.contains(&target) != enable {
        return refused(Reason::Unsatisfiable, before, enabled_before);
    }

    let changes = by_key
        .iter()
        .filter(|(k, _)| **k != target)
        .filter(|(k, _)| enabled_before.contains(*k) != enabled_after.contains(*k))
        .map(|(k, m)| explain(&by_key, k, m, enabled_after.contains(k), &enabled_after))
        .collect();
    ToggleResult {
        applied: true,
        refusal: None,
        choices: after,
        enabled: enabled_after,
        changes,
    }
}

/// Why a mod switched.
fn explain(
    by_key: &IndexMap<String, &OptionalMod>,
    key: &str,
    m: &OptionalMod,
    is_on: bool,
    enabled: &IndexSet<String>,
) -> Change {
    let change = |reason, cause: String| Change {
        key: key.to_string(),
        name: m.display_name(),
        enabled: is_on,
        reason,
        cause,
    };
    if is_on {
        let cause = enabled
            .iter()
            .filter_map(|other| by_key.get(other))
            .find(|c| keys_of(c.requires()).contains(key))
            .map(|c| c.display_name())
            .unwrap_or_default();
        return change(Reason::RequiredBy, cause);
    }
    if let Some(other) = enabled.iter().find(|other| conflicts(by_key, key, other)) {
        return change(Reason::ConflictsWith, by_key[other].display_name());
    }
    let cause = keys_of(m.requires())
        .iter()
        .filter(|r| !enabled.contains(*r))
        .find_map(|r| by_key.get(r))
        .map(|r| r.display_name())
        .unwrap_or_default();
    change(Reason::RequiresOff, cause)
}

/// Resolver choices (by identity key) in their stored form, keyed by each mod's local name as
/// the manifest spells it (normalised). Choices for names the manifest no longer lists keep
/// their key, so a mod that comes back keeps the player's choice.
pub fn to_stored_choices(mods: &[OptionalMod], choices: &Choices) -> Choices {
    let by_key = index(mods);
    choices
        .iter()
        .map(|(k, on)| {
            let name = by_key.get(k).map_or_else(|| k.clone(), |m| m.local_name());
            (name, *on)
        })
        .collect()
}

#[cfg(test)]
mod tests;
