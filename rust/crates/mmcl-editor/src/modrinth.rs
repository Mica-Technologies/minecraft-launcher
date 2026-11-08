// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Modrinth search for the editor's Mods tab, and turning picked hits into verified rows.
//!
//! Changes from Java: the filters are read when "Add selected" is pressed (Java froze them at
//! dialog build time); query values are percent-encoded; the version's *primary* file is used
//! (Java took the first); every added jar is downloaded once and checked against Modrinth's
//! published SHA-512 (SHA-1 when no SHA-512 is published) before its hashes are written; and a
//! mod whose local path already exists in the tab is skipped instead of duplicated.

use percent_encoding::{AsciiSet, NON_ALPHANUMERIC, utf8_percent_encode};
use serde_json::Value;

use crate::backend::{EditorBackend, NetError};
use crate::hashing::{FileHashes, remote_hashes};
use crate::row::EditorRow;

/// Search endpoint.
pub const SEARCH_URL: &str = "https://api.modrinth.com/v2/search";
/// Results per search.
pub const SEARCH_LIMIT: usize = 25;
/// The loader filter's choices.
pub const LOADER_FILTERS: [&str; 4] = ["forge", "neoforge", "fabric", "quilt"];
/// Description cap (characters) in a result row.
pub const DESCRIPTION_MAX: usize = 120;

/// One search hit.
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct SearchHit {
    pub slug: String,
    pub title: String,
    pub author: String,
    pub description: String,
    pub icon_url: String,
}

impl SearchHit {
    /// The title line: `"{title}  by {author}"` (author part omitted when blank).
    pub fn title_line(&self) -> String {
        if self.author.trim().is_empty() {
            self.title.clone()
        } else {
            mmcl_i18n::tf!(
                "editor.modSearch.byAuthor",
                self.title.as_str(),
                self.author.as_str()
            )
        }
    }

    /// The description, capped at 120 characters (117 + `...`), counted in `char`s.
    pub fn short_description(&self) -> String {
        truncate_chars(&self.description, DESCRIPTION_MAX)
    }

    /// The project page.
    pub fn page_url(&self) -> Option<String> {
        (!self.slug.is_empty()).then(|| format!("https://modrinth.com/mod/{}", self.slug))
    }
}

/// `s` if it has at most `max` chars, else its first `max - 3` chars + `...`.
pub fn truncate_chars(s: &str, max: usize) -> String {
    if s.chars().count() > max {
        let mut out: String = s.chars().take(max.saturating_sub(3)).collect();
        out.push_str("...");
        out
    } else {
        s.to_string()
    }
}

/// The facets JSON: `[["project_type:mod"](,["versions:{gv}"])(,["categories:{loader}"])]`.
pub fn facets_json(game_version: Option<&str>, loader: Option<&str>) -> String {
    let mut groups = vec![serde_json::json!(["project_type:mod"])];
    if let Some(gv) = game_version.map(str::trim).filter(|s| !s.is_empty()) {
        groups.push(serde_json::json!([format!("versions:{gv}")]));
    }
    if let Some(l) = loader.map(str::trim).filter(|s| !s.is_empty()) {
        groups.push(serde_json::json!([format!(
            "categories:{}",
            l.to_lowercase()
        )]));
    }
    Value::Array(groups).to_string()
}

fn form_encode(s: &str) -> String {
    form_urlencoded::byte_serialize(s.as_bytes()).collect()
}

/// The search request URL.
pub fn search_url(query: &str, game_version: Option<&str>, loader: Option<&str>) -> String {
    format!(
        "{SEARCH_URL}?query={}&facets={}&limit={SEARCH_LIMIT}",
        form_encode(query.trim()),
        form_encode(&facets_json(game_version, loader))
    )
}

fn str_field(v: &Value, key: &str) -> String {
    v.get(key)
        .and_then(Value::as_str)
        .unwrap_or_default()
        .to_string()
}

/// The hits of a search response.
pub fn parse_hits(body: &str) -> Result<Vec<SearchHit>, NetError> {
    let root: Value = serde_json::from_str(body).map_err(|e| NetError::new(e.to_string()))?;
    let hits = root
        .get("hits")
        .and_then(Value::as_array)
        .ok_or_else(|| NetError::new("hits"))?;
    Ok(hits
        .iter()
        .map(|h| {
            let title = str_field(h, "title");
            SearchHit {
                slug: str_field(h, "slug"),
                title: if title.is_empty() { "?".into() } else { title },
                author: str_field(h, "author"),
                description: str_field(h, "description"),
                icon_url: str_field(h, "icon_url"),
            }
        })
        .collect())
}

/// Runs a search (blank query: no results, no request).
pub async fn search(
    backend: &dyn EditorBackend,
    query: &str,
    game_version: Option<&str>,
    loader: Option<&str>,
) -> Result<Vec<SearchHit>, NetError> {
    if query.trim().is_empty() {
        return Ok(Vec::new());
    }
    let body = backend
        .get_text(&search_url(query, game_version, loader))
        .await?;
    parse_hits(&body)
}

/// Characters escaped in a query value (everything but unreserved).
const QUERY_VALUE: &AsciiSet = &NON_ALPHANUMERIC
    .remove(b'-')
    .remove(b'.')
    .remove(b'_')
    .remove(b'~');

/// `/v2/project/{slug}/version` with the optional `game_versions` / `loaders` filters, values
/// percent-encoded.
pub fn versions_url(slug: &str, game_version: Option<&str>, loader: Option<&str>) -> String {
    let mut url = format!(
        "https://api.modrinth.com/v2/project/{}/version",
        utf8_percent_encode(slug, QUERY_VALUE)
    );
    let mut params = Vec::new();
    if let Some(gv) = game_version.map(str::trim).filter(|s| !s.is_empty()) {
        let arr = serde_json::json!([gv]).to_string();
        params.push(format!(
            "game_versions={}",
            utf8_percent_encode(&arr, QUERY_VALUE)
        ));
    }
    if let Some(l) = loader.map(str::trim).filter(|s| !s.is_empty()) {
        let arr = serde_json::json!([l.to_lowercase()]).to_string();
        params.push(format!(
            "loaders={}",
            utf8_percent_encode(&arr, QUERY_VALUE)
        ));
    }
    if !params.is_empty() {
        url.push('?');
        url.push_str(&params.join("&"));
    }
    url
}

/// The file of a version chosen for the manifest.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct VersionFile {
    pub url: String,
    pub filename: String,
    pub sha1: Option<String>,
    pub sha512: Option<String>,
}

/// The newest version's primary file (else its first file with a URL), from a version list
/// (newest first). Versions without a usable file are skipped.
pub fn pick_file(versions_body: &str) -> Result<Option<VersionFile>, NetError> {
    let root: Value =
        serde_json::from_str(versions_body).map_err(|e| NetError::new(e.to_string()))?;
    let versions = root
        .as_array()
        .ok_or_else(|| NetError::new("expected a version array"))?;
    for v in versions {
        let Some(files) = v.get("files").and_then(Value::as_array) else {
            continue;
        };
        let has_url = |f: &&Value| {
            f.get("url")
                .and_then(Value::as_str)
                .is_some_and(|u| !u.is_empty())
        };
        let file = files
            .iter()
            .filter(has_url)
            .find(|f| f.get("primary").and_then(Value::as_bool) == Some(true))
            .or_else(|| files.iter().find(has_url));
        if let Some(f) = file {
            let hash = |k: &str| {
                f.get("hashes")
                    .and_then(|h| h.get(k))
                    .and_then(Value::as_str)
                    .map(str::to_string)
            };
            return Ok(Some(VersionFile {
                url: str_field(f, "url"),
                filename: str_field(f, "filename"),
                sha1: hash("sha1"),
                sha512: hash("sha512"),
            }));
        }
    }
    Ok(None)
}

/// The placeholder row for a hit (what stays when the version cannot be resolved).
pub fn placeholder_row(hit: &SearchHit) -> EditorRow {
    EditorRow {
        name: hit.title.clone(),
        remote: format!("modrinth:{}", hit.slug),
        local: format!("{}.jar", hit.slug),
        hash_type: "sha1".into(),
        modrinth_slug: hit.slug.clone(),
        ..Default::default()
    }
}

/// What happened to each picked hit.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum AddOutcome {
    /// Resolved, downloaded and verified: the row has all three hashes.
    Verified(EditorRow),
    /// The version could not be resolved (or could not be downloaded to verify): the row keeps
    /// what Modrinth said (or the `modrinth:` placeholder) and its SHA-1 when published.
    Unverified(EditorRow),
    /// The download did not match Modrinth's published hash: not added.
    HashMismatch { slug: String },
    /// The tab already has a mod at this local path: not added.
    Duplicate { slug: String },
}

impl AddOutcome {
    /// The row to append, when the outcome adds one.
    pub fn row(&self) -> Option<&EditorRow> {
        match self {
            AddOutcome::Verified(r) | AddOutcome::Unverified(r) => Some(r),
            _ => None,
        }
    }
}

/// Resolves one picked hit into a row: newest compatible version, primary file, download,
/// SHA-512 check, all three hashes. `has_local` reports duplicates against the tab's rows (and
/// the rows added before this one).
pub async fn resolve_hit(
    backend: &dyn EditorBackend,
    hit: &SearchHit,
    game_version: Option<&str>,
    loader: Option<&str>,
    has_local: &(dyn Fn(&str) -> bool + Send + Sync),
) -> AddOutcome {
    let mut row = placeholder_row(hit);
    let resolved = match backend
        .get_text(&versions_url(&hit.slug, game_version, loader))
        .await
        .and_then(|b| pick_file(&b))
    {
        Ok(f) => f,
        Err(e) => {
            tracing::warn!(
                "{} {e}",
                mmcl_i18n::tf!("log.editor.modrinthVersionFailed", hit.slug.as_str())
            );
            None
        }
    };
    let Some(file) = resolved else {
        return if has_local(&row.local) {
            AddOutcome::Duplicate {
                slug: hit.slug.clone(),
            }
        } else {
            AddOutcome::Unverified(row)
        };
    };
    row.remote = file.url.clone();
    if !file.filename.trim().is_empty() {
        row.local = file.filename.clone();
    }
    if has_local(&row.local) {
        return AddOutcome::Duplicate {
            slug: hit.slug.clone(),
        };
    }
    match remote_hashes(backend, &file.url, "mclauncher_modrinth_", ".jar").await {
        Ok(hashes) => verify_into(row, &hashes, &file, &hit.slug),
        Err(e) => {
            tracing::warn!(
                "{} {e}",
                mmcl_i18n::tf!("log.editor.modrinthVerifyDownloadFailed", hit.slug.as_str())
            );
            row.hash = file.sha1.clone().unwrap_or_default();
            row.hash_type = "sha1".into();
            AddOutcome::Unverified(row)
        }
    }
}

fn verify_into(
    mut row: EditorRow,
    hashes: &FileHashes,
    file: &VersionFile,
    slug: &str,
) -> AddOutcome {
    match hashes.verify_published(file.sha512.as_deref(), file.sha1.as_deref()) {
        Some(false) => {
            tracing::warn!(
                "{}",
                mmcl_i18n::tf!("log.editor.modrinthHashMismatch", slug, file.url.as_str())
            );
            AddOutcome::HashMismatch {
                slug: slug.to_string(),
            }
        }
        Some(true) => {
            hashes.apply_to(&mut row);
            AddOutcome::Verified(row)
        }
        None => {
            // Nothing published to check against: record what was downloaded.
            hashes.apply_to(&mut row);
            AddOutcome::Unverified(row)
        }
    }
}

/// Totals for the status line after "Add selected".
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct AddTotals {
    pub added: usize,
    pub unverified: usize,
    pub mismatched: usize,
    pub duplicates: usize,
}

impl AddTotals {
    /// Tallies outcomes.
    pub fn of(outcomes: &[AddOutcome]) -> AddTotals {
        let mut t = AddTotals::default();
        for o in outcomes {
            match o {
                AddOutcome::Verified(_) => t.added += 1,
                AddOutcome::Unverified(_) => {
                    t.added += 1;
                    t.unverified += 1;
                }
                AddOutcome::HashMismatch { .. } => t.mismatched += 1,
                AddOutcome::Duplicate { .. } => t.duplicates += 1,
            }
        }
        t
    }

    /// The status line.
    pub fn status(&self) -> String {
        let mut s = mmcl_i18n::tf!("editor.status.modsAdded", self.added.to_string());
        if self.unverified > 0 {
            s.push(' ');
            s.push_str(&mmcl_i18n::tf!(
                "editor.status.modsUnverified",
                self.unverified.to_string()
            ));
        }
        if self.mismatched > 0 {
            s.push(' ');
            s.push_str(&mmcl_i18n::tf!(
                "editor.status.modsHashMismatch",
                self.mismatched.to_string()
            ));
        }
        if self.duplicates > 0 {
            s.push(' ');
            s.push_str(&mmcl_i18n::tf!(
                "editor.status.modsDuplicate",
                self.duplicates.to_string()
            ));
        }
        s
    }
}

/// The project's icon URL (`/v2/project/{slug}`), when the search hit had none.
pub async fn project_icon_url(backend: &dyn EditorBackend, slug: &str) -> Option<String> {
    let url = format!(
        "https://api.modrinth.com/v2/project/{}",
        utf8_percent_encode(slug, QUERY_VALUE)
    );
    let body = backend.get_text(&url).await.ok()?;
    let v: Value = serde_json::from_str(&body).ok()?;
    v.get("icon_url")
        .and_then(Value::as_str)
        .filter(|s| !s.is_empty())
        .map(str::to_string)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::backend::fake::FakeBackend;
    use futures::executor::block_on;

    #[test]
    fn facets_are_built_exactly() {
        assert_eq!(facets_json(None, None), r#"[["project_type:mod"]]"#);
        assert_eq!(
            facets_json(Some(" 1.20.1 "), Some("Forge")),
            r#"[["project_type:mod"],["versions:1.20.1"],["categories:forge"]]"#
        );
        assert_eq!(facets_json(Some(""), None), r#"[["project_type:mod"]]"#);
    }

    #[test]
    fn search_urls_are_form_encoded() {
        let u = search_url("jei & more", None, Some("fabric"));
        assert!(u.starts_with("https://api.modrinth.com/v2/search?query=jei+%26+more&facets="));
        assert!(
            u.contains("%5B%5B%22project_type%3Amod%22%5D%2C%5B%22categories%3Afabric%22%5D%5D")
        );
        assert!(u.ends_with("&limit=25"));
    }

    #[test]
    fn version_urls_percent_encode_filters() {
        assert_eq!(
            versions_url("jei", None, None),
            "https://api.modrinth.com/v2/project/jei/version"
        );
        assert_eq!(
            versions_url("jei", Some("1.20.1"), Some("Forge")),
            "https://api.modrinth.com/v2/project/jei/version?game_versions=%5B%221.20.1%22%5D&loaders=%5B%22forge%22%5D"
        );
    }

    #[test]
    fn hits_parse_with_defaults_and_truncation() {
        let long = "é".repeat(130);
        let body = format!(
            r#"{{"hits":[{{"slug":"jei","title":"JEI","author":"mezz","description":"{long}","icon_url":"https://i/x.png"}},{{"slug":"x"}}]}}"#
        );
        let hits = parse_hits(&body).unwrap();
        assert_eq!(hits.len(), 2);
        assert_eq!(hits[1].title, "?");
        assert_eq!(hits[1].author, "");
        let d = hits[0].short_description();
        assert_eq!(d.chars().count(), 120);
        assert!(d.ends_with("..."));
        assert!(hits[0].title_line().contains("mezz"));
        assert_eq!(hits[1].title_line(), "?");
        assert_eq!(
            hits[0].page_url().as_deref(),
            Some("https://modrinth.com/mod/jei")
        );
        assert_eq!(SearchHit::default().page_url(), None);
        assert!(parse_hits("{}").is_err());
        assert_eq!(truncate_chars("short", 120), "short");
    }

    #[test]
    fn blank_queries_send_nothing() {
        let b = FakeBackend::default();
        assert!(block_on(search(&b, "  ", None, None)).unwrap().is_empty());
        assert!(b.requested().is_empty());
    }

    #[test]
    fn primary_files_win() {
        let body = r#"[{"files":[]},{"files":[
            {"url":"https://cdn/a.jar","filename":"a.jar","primary":false,"hashes":{"sha1":"1"}},
            {"url":"https://cdn/b.jar","filename":"b.jar","primary":true,"hashes":{"sha1":"2","sha512":"5"}}]}]"#;
        let f = pick_file(body).unwrap().unwrap();
        assert_eq!(f.filename, "b.jar");
        assert_eq!(f.sha512.as_deref(), Some("5"));
        let first = r#"[{"files":[{"url":"https://cdn/a.jar","filename":"a.jar"}]}]"#;
        assert_eq!(pick_file(first).unwrap().unwrap().url, "https://cdn/a.jar");
        assert_eq!(pick_file("[]").unwrap(), None);
        assert!(pick_file("{}").is_err());
    }

    fn hit(slug: &str) -> SearchHit {
        SearchHit {
            slug: slug.into(),
            title: slug.to_uppercase(),
            ..Default::default()
        }
    }

    fn versions_body(url: &str, sha512: &str) -> String {
        format!(
            r#"[{{"files":[{{"url":"{url}","filename":"jei-1.0.jar","primary":true,"hashes":{{"sha512":"{sha512}","sha1":"x"}}}}]}}]"#
        )
    }

    #[test]
    fn verified_mods_get_all_three_hashes() {
        let good = FileHashes::of_reader(&b"jar-bytes"[..]).unwrap();
        let b = FakeBackend::default()
            .text(
                &versions_url("jei", Some("1.20.1"), None),
                &versions_body("https://cdn/jei.jar", &good.sha512),
            )
            .file("https://cdn/jei.jar", b"jar-bytes");
        let none = |_: &str| false;
        let out = block_on(resolve_hit(&b, &hit("jei"), Some("1.20.1"), None, &none));
        let AddOutcome::Verified(row) = out else {
            panic!("{out:?}")
        };
        assert_eq!(row.remote, "https://cdn/jei.jar");
        assert_eq!(row.local, "jei-1.0.jar");
        assert_eq!(row.hash, good.sha256);
        assert_eq!(row.hash_type, "sha256");
        assert_eq!(row.extra_hashes.get("sha1"), Some(&good.sha1));
        assert_eq!(row.modrinth_slug, "jei");
        assert_eq!(row.name, "JEI");
    }

    #[test]
    fn mismatched_downloads_are_rejected() {
        let b = FakeBackend::default()
            .text(
                &versions_url("jei", None, None),
                &versions_body("https://cdn/jei.jar", "00ff"),
            )
            .file("https://cdn/jei.jar", b"tampered");
        let none = |_: &str| false;
        let out = block_on(resolve_hit(&b, &hit("jei"), None, None, &none));
        assert_eq!(out, AddOutcome::HashMismatch { slug: "jei".into() });
        assert!(out.row().is_none());
    }

    #[test]
    fn unresolvable_versions_keep_the_placeholder() {
        let b = FakeBackend::default();
        let none = |_: &str| false;
        let out = block_on(resolve_hit(&b, &hit("gone"), None, None, &none));
        let AddOutcome::Unverified(row) = out else {
            panic!()
        };
        assert_eq!(row.remote, "modrinth:gone");
        assert_eq!(row.local, "gone.jar");
        let dup = |l: &str| l == "gone.jar";
        assert_eq!(
            block_on(resolve_hit(&b, &hit("gone"), None, None, &dup)),
            AddOutcome::Duplicate {
                slug: "gone".into()
            }
        );
    }

    #[test]
    fn download_failures_fall_back_to_the_published_sha1() {
        let b = FakeBackend::default().text(
            &versions_url("jei", None, None),
            &versions_body("https://cdn/missing.jar", "00"),
        );
        let none = |_: &str| false;
        let AddOutcome::Unverified(row) = block_on(resolve_hit(&b, &hit("jei"), None, None, &none))
        else {
            panic!()
        };
        assert_eq!(row.hash, "x");
        assert_eq!(row.hash_type, "sha1");
    }

    #[test]
    fn totals_and_status() {
        let r = EditorRow::default();
        let t = AddTotals::of(&[
            AddOutcome::Verified(r.clone()),
            AddOutcome::Unverified(r),
            AddOutcome::HashMismatch { slug: "a".into() },
            AddOutcome::Duplicate { slug: "b".into() },
        ]);
        assert_eq!(
            t,
            AddTotals {
                added: 2,
                unverified: 1,
                mismatched: 1,
                duplicates: 1
            }
        );
        let s = t.status();
        assert!(s.contains('2'));
        assert_eq!(
            AddTotals::default().status(),
            mmcl_i18n::tf!("editor.status.modsAdded", "0")
        );
    }

    #[test]
    fn icon_url_lookup() {
        let b = FakeBackend::default().text(
            "https://api.modrinth.com/v2/project/jei",
            r#"{"icon_url":"https://cdn/i.png"}"#,
        );
        assert_eq!(
            block_on(project_icon_url(&b, "jei")).as_deref(),
            Some("https://cdn/i.png")
        );
        assert_eq!(block_on(project_icon_url(&b, "nope")), None);
    }
}
