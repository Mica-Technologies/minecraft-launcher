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
        }
    }
}

impl EditorRow {
    /// Copies every field of a document entry (all extra hashes and the slug).
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
        }
    }

    /// The document entry for this row (`url_status` is not carried).
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
