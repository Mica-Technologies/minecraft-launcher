// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! One editable file-list row (`ModPackEditorFileEntry`).

use std::collections::BTreeMap;

use mmcl_manifest::document::FileEntry;

/// A row's stable identity: selection, sorting, URL-check and hash results refer to rows by id,
/// never by index.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub struct RowId(pub u64);

/// A URL check result. Transient: never written to the document.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct UrlCheck {
    /// Reachable (2xx/3xx).
    pub ok: bool,
    /// What the status cell shows (`OK`, the numeric code, or `ERR`).
    pub label: String,
}

/// One row of a file-list table.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct EditorRow {
    pub name: String,
    pub remote: String,
    pub local: String,
    pub hash: String,
    /// `sha1` (default), `md5` or `sha256`; free text.
    pub hash_type: String,
    pub client_req: bool,
    pub server_req: bool,
    pub modrinth_slug: String,
    /// Non-primary hashes keyed by lower-case algorithm.
    pub extra_hashes: BTreeMap<String, String>,
    /// The last URL check, if any.
    pub url_status: Option<UrlCheck>,
    /// Optional mods only: the text shown to the player next to the toggle.
    pub description: String,
    /// Optional mods only: on for a player who has not chosen.
    pub default_enabled: bool,
    /// Optional mods only: the `local` names this mod requires, comma-separated as typed.
    pub requires: String,
    /// Optional mods only: the `local` names that must be off while this mod is on,
    /// comma-separated as typed.
    pub conflicts_with: String,
}

impl Default for EditorRow {
    fn default() -> Self {
        EditorRow {
            name: String::new(),
            remote: String::new(),
            local: String::new(),
            hash: String::new(),
            hash_type: "sha1".into(),
            client_req: true,
            server_req: true,
            modrinth_slug: String::new(),
            extra_hashes: BTreeMap::new(),
            url_status: None,
            description: String::new(),
            default_enabled: false,
            requires: String::new(),
            conflicts_with: String::new(),
        }
    }
}

/// Splits a comma- or semicolon-separated list of names as typed into a cell: trimmed, blanks
/// dropped, in order (Java `ModPackEditorFileEntry.splitNames`).
pub fn split_names(text: &str) -> Vec<String> {
    text.split([',', ';'])
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .map(str::to_string)
        .collect()
}

impl EditorRow {
    /// Copies every field of a document entry (all extra hashes, the slug and the optional-mod
    /// fields).
    pub fn from_entry(e: &FileEntry) -> EditorRow {
        EditorRow {
            name: e.name().to_string(),
            remote: e.remote().to_string(),
            local: e.local().to_string(),
            hash: e.hash().to_string(),
            hash_type: e.hash_type().to_string(),
            client_req: e.client_req(),
            server_req: e.server_req(),
            modrinth_slug: e.modrinth_slug().to_string(),
            extra_hashes: e.extra_hashes().clone(),
            url_status: None,
            description: e.description().to_string(),
            default_enabled: e.default_enabled(),
            requires: e.requires().join(", "),
            conflicts_with: e.conflicts_with().join(", "),
        }
    }

    /// The document entry for this row (`url_status` is not carried). Which fields reach the
    /// manifest is decided by the list it is written to.
    pub fn to_entry(&self) -> FileEntry {
        let mut e = FileEntry::new(
            self.name.clone(),
            self.remote.clone(),
            self.local.clone(),
            self.hash.clone(),
            Some(self.hash_type.as_str()),
            self.client_req,
            self.server_req,
        );
        e.set_modrinth_slug(self.modrinth_slug.clone());
        for (algo, value) in &self.extra_hashes {
            e.put_extra_hash(algo, Some(value));
        }
        e.set_description(self.description.clone());
        e.set_default_enabled(self.default_enabled);
        e.set_requires(&split_names(&self.requires));
        e.set_conflicts_with(&split_names(&self.conflicts_with));
        e
    }

    /// Stores a non-primary hash; a blank value removes it.
    pub fn put_extra_hash(&mut self, algo: &str, value: Option<&str>) {
        let key = algo.to_lowercase();
        match value.filter(|v| !v.trim().is_empty()) {
            Some(v) => {
                self.extra_hashes.insert(key, v.to_string());
            }
            None => {
                self.extra_hashes.remove(&key);
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn conversions_carry_every_field() {
        let mut e = FileEntry::new(
            "Mod",
            "https://x/m.jar",
            "m.jar",
            "abc",
            Some("sha256"),
            false,
            true,
        );
        e.set_modrinth_slug("mod-slug");
        e.put_extra_hash("sha1", Some("s1"));
        e.put_extra_hash("md5", Some("m5"));
        let mut row = EditorRow::from_entry(&e);
        assert_eq!(row.hash_type, "sha256");
        assert!(!row.client_req && row.server_req);
        assert_eq!(row.extra_hashes.len(), 2);
        row.url_status = Some(UrlCheck {
            ok: true,
            label: "OK".into(),
        });
        assert_eq!(row.to_entry(), e);
    }

    #[test]
    fn optional_mod_fields_survive_the_editor_row() {
        let mut e = FileEntry::new(
            "AUSM",
            "https://example.invalid/ausm.jar",
            "ausm.jar",
            "abc",
            Some("sha256"),
            true,
            false,
        );
        e.put_extra_hash("sha1", Some("def"));
        e.set_modrinth_slug("ausm");
        e.set_description("Shader support");
        e.set_default_enabled(true);
        e.set_requires(&["lib.jar", "core.jar"]);
        e.set_conflicts_with(&["fast.jar"]);
        let row = EditorRow::from_entry(&e);
        assert_eq!(row.requires, "lib.jar, core.jar");
        let back = row.to_entry();
        assert_eq!(back, e);
        assert_eq!(back.description(), "Shader support");
        assert!(back.default_enabled() && back.client_req() && !back.server_req());
        assert_eq!(back.requires(), ["lib.jar", "core.jar"]);
        assert_eq!(back.conflicts_with(), ["fast.jar"]);
    }

    #[test]
    fn typed_name_lists_are_split_on_commas_and_semicolons() {
        assert_eq!(
            split_names(" a.jar ,b.jar;; c.jar, "),
            ["a.jar", "b.jar", "c.jar"]
        );
        assert!(split_names("").is_empty());
        assert!(split_names(" , ").is_empty());
    }

    #[test]
    fn defaults_match_java() {
        let r = EditorRow::default();
        assert_eq!(r.hash_type, "sha1");
        assert!(r.client_req && r.server_req);
        assert!(r.modrinth_slug.is_empty() && r.url_status.is_none());
    }

    #[test]
    fn blank_extra_hash_removes_the_key() {
        let mut r = EditorRow::default();
        r.put_extra_hash("SHA1", Some("x"));
        assert_eq!(r.extra_hashes.get("sha1").map(String::as_str), Some("x"));
        r.put_extra_hash("sha1", Some("  "));
        assert!(r.extra_hashes.is_empty());
    }
}
