// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The Modrinth API calls behind the import preview and the editor's mod search (Java
//! `ModrinthClient`). Every failure is logged and reads as "nothing found".

use serde_json::{Map, Value};

use mmcl_i18n::tf;

use crate::http::PackHttp;
use crate::modrinth_updates::{API_BASE, fetch_object, opt_string};

/// One file of a version.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct FileRef {
    pub filename: Option<String>,
    pub url: String,
    pub sha1: Option<String>,
    pub sha512: Option<String>,
    pub size: i64,
    pub primary: bool,
}

/// One version.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct VersionSummary {
    pub version_id: Option<String>,
    pub name: Option<String>,
    pub version_number: Option<String>,
    pub minecraft_versions: Vec<String>,
    pub loaders: Vec<String>,
    pub files: Vec<FileRef>,
}

/// A project and its pinned or latest version.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct ProjectSummary {
    pub project_id: Option<String>,
    pub slug: Option<String>,
    pub title: Option<String>,
    pub description: Option<String>,
    pub icon_url: Option<String>,
    pub minecraft_versions: Vec<String>,
    pub loaders: Vec<String>,
    pub latest_version: Option<VersionSummary>,
}

/// One search hit.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct SearchHit {
    pub project_id: Option<String>,
    pub slug: Option<String>,
    pub title: Option<String>,
    pub description: Option<String>,
    pub icon_url: Option<String>,
    pub author: Option<String>,
    pub downloads: i64,
}

fn enc(s: &str) -> String {
    url::form_urlencoded::byte_serialize(s.as_bytes()).collect()
}

fn str_array(o: &Map<String, Value>, k: &str) -> Vec<String> {
    o.get(k)
        .and_then(Value::as_array)
        .map(|a| {
            a.iter()
                .filter(|v| !v.is_object() && !v.is_array() && !v.is_null())
                .filter_map(mmcl_manifest::lenient::coerce_string)
                .collect()
        })
        .unwrap_or_default()
}

fn file_ref(fo: &Map<String, Value>) -> Option<FileRef> {
    let url = opt_string(fo, "url")?;
    let hashes = fo.get("hashes").and_then(Value::as_object);
    Some(FileRef {
        filename: opt_string(fo, "filename"),
        url,
        sha1: hashes.and_then(|h| opt_string(h, "sha1")),
        sha512: hashes.and_then(|h| opt_string(h, "sha512")),
        size: fo
            .get("size")
            .and_then(mmcl_util::json::as_gson_i64)
            .unwrap_or(0),
        primary: fo.get("primary").and_then(Value::as_bool) == Some(true),
    })
}

/// The primary file of a version JSON, else its first file with a URL.
pub fn primary_file(version: &Map<String, Value>) -> Option<FileRef> {
    let files = version.get("files")?.as_array()?;
    let refs: Vec<FileRef> = files
        .iter()
        .filter_map(Value::as_object)
        .filter_map(file_ref)
        .collect();
    refs.iter()
        .find(|f| f.primary)
        .cloned()
        .or_else(|| refs.into_iter().next())
}

/// Parses a version JSON.
pub fn parse_version(v: &Map<String, Value>) -> VersionSummary {
    VersionSummary {
        version_id: opt_string(v, "id"),
        name: opt_string(v, "name"),
        version_number: opt_string(v, "version_number"),
        minecraft_versions: str_array(v, "game_versions"),
        loaders: str_array(v, "loaders"),
        files: v
            .get("files")
            .and_then(Value::as_array)
            .map(|a| {
                a.iter()
                    .filter_map(Value::as_object)
                    .filter_map(file_ref)
                    .collect()
            })
            .unwrap_or_default(),
    }
}

/// The search URL (mods only, optionally filtered by MC version and loader).
pub fn search_url(
    query: &str,
    mc_version: Option<&str>,
    loader: Option<&str>,
    limit: usize,
) -> String {
    let mut facets = vec!["[\"project_type:mod\"]".to_string()];
    if let Some(v) = mc_version.filter(|v| !v.trim().is_empty()) {
        facets.push(format!("[\"versions:{v}\"]"));
    }
    if let Some(l) = loader.filter(|l| !l.trim().is_empty()) {
        facets.push(format!("[\"categories:{}\"]", l.to_lowercase()));
    }
    format!(
        "{API_BASE}/search?limit={}&index=relevance&query={}&facets={}",
        limit.clamp(1, 100),
        enc(query),
        enc(&format!("[{}]", facets.join(",")))
    )
}

/// Searches mods.
pub async fn search(
    http: &dyn PackHttp,
    query: &str,
    mc_version: Option<&str>,
    loader: Option<&str>,
    limit: usize,
) -> Vec<SearchHit> {
    if query.trim().is_empty() {
        return Vec::new();
    }
    let Some(root) = fetch_object(http, &search_url(query, mc_version, loader, limit)).await else {
        mmcl_log::warn_silent(&tf!("log.modrinthClient.searchFailed", query, "no result"));
        return Vec::new();
    };
    root.get("hits")
        .and_then(Value::as_array)
        .map(|hits| {
            hits.iter()
                .filter_map(Value::as_object)
                .map(|h| SearchHit {
                    project_id: opt_string(h, "project_id"),
                    slug: opt_string(h, "slug"),
                    title: opt_string(h, "title"),
                    description: opt_string(h, "description"),
                    icon_url: opt_string(h, "icon_url"),
                    author: opt_string(h, "author"),
                    downloads: h
                        .get("downloads")
                        .and_then(mmcl_util::json::as_gson_i64)
                        .unwrap_or(0),
                })
                .collect()
        })
        .unwrap_or_default()
}

/// The newest version's primary file compatible with `mc_version` and `loader`.
pub async fn resolve_latest_compatible_file(
    http: &dyn PackHttp,
    project_id: &str,
    mc_version: Option<&str>,
    loader: Option<&str>,
) -> Option<FileRef> {
    if project_id.trim().is_empty() {
        return None;
    }
    let mut url = format!("{API_BASE}/project/{project_id}/version?");
    if let Some(l) = loader.filter(|l| !l.trim().is_empty()) {
        url.push_str(&format!(
            "loaders={}&",
            enc(&format!("[\"{}\"]", l.to_lowercase()))
        ));
    }
    if let Some(v) = mc_version.filter(|v| !v.trim().is_empty()) {
        url.push_str(&format!("game_versions={}", enc(&format!("[\"{v}\"]"))));
    }
    let body = match http
        .get_bounded(&url, crate::modrinth_updates::MAX_RESPONSE_BYTES)
        .await
    {
        Ok(b) => b,
        Err(e) => {
            mmcl_log::warn_silent(&tf!(
                "log.modrinthClient.versionResolveFailed",
                project_id,
                e.to_string()
            ));
            return None;
        }
    };
    let Ok(Value::Array(versions)) = serde_json::from_str::<Value>(&body) else {
        return None;
    };
    versions
        .iter()
        .filter_map(Value::as_object)
        .find_map(primary_file)
}

async fn fetch_version(http: &dyn PackHttp, id: &str) -> Option<VersionSummary> {
    if id.trim().is_empty() {
        return None;
    }
    match fetch_object(http, &format!("{API_BASE}/version/{id}")).await {
        Some(v) => Some(parse_version(&v)),
        None => {
            mmcl_log::warn_silent(&tf!(
                "log.modrinthClient.versionFetchFailed",
                id,
                "no result"
            ));
            None
        }
    }
}

/// A project plus its pinned version (else its newest).
pub async fn fetch_project(
    http: &dyn PackHttp,
    slug_or_id: &str,
    version_pin: Option<&str>,
) -> Option<ProjectSummary> {
    if slug_or_id.trim().is_empty() {
        return None;
    }
    let Some(p) = fetch_object(http, &format!("{API_BASE}/project/{slug_or_id}")).await else {
        mmcl_log::warn_silent(&tf!(
            "log.modrinthClient.projectFetchFailed",
            slug_or_id,
            "no result"
        ));
        return None;
    };
    let mut version = match version_pin.filter(|v| !v.trim().is_empty()) {
        Some(pin) => fetch_version(http, pin).await,
        None => None,
    };
    if version.is_none()
        && let Some(last) = str_array(&p, "versions").last()
    {
        version = fetch_version(http, last).await;
    }
    Some(ProjectSummary {
        project_id: opt_string(&p, "id"),
        slug: opt_string(&p, "slug"),
        title: opt_string(&p, "title"),
        description: opt_string(&p, "description"),
        icon_url: opt_string(&p, "icon_url"),
        minecraft_versions: str_array(&p, "game_versions"),
        loaders: str_array(&p, "loaders"),
        latest_version: version,
    })
}

/// The `.mrpack` download URL of a project's version (primary file).
pub fn mrpack_url(v: &VersionSummary) -> Option<&str> {
    v.files
        .iter()
        .find(|f| f.primary)
        .or_else(|| v.files.first())
        .map(|f| f.url.as_str())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::http::fake::FakeHttp;

    #[test]
    fn search_url_shape() {
        let u = search_url("just enough", Some("1.20.1"), Some("Forge"), 500);
        assert!(u.starts_with(
            "https://api.modrinth.com/v2/search?limit=100&index=relevance&query=just+enough&facets="
        ));
        assert!(u.contains("categories%3Aforge"));
    }

    #[tokio::test]
    async fn project_with_latest_version() {
        let http = FakeHttp::new();
        http.body(
            &format!("{API_BASE}/project/fab"),
            r#"{"id":"P","slug":"fab","title":"Fab","versions":["a","b"],"loaders":["fabric"]}"#,
        );
        http.body(
            &format!("{API_BASE}/version/b"),
            r#"{"id":"b","version_number":"2","files":[{"url":"https://cdn/x.mrpack","filename":"x.mrpack","primary":true,"hashes":{"sha1":"s"}}]}"#,
        );
        let p = fetch_project(http.as_ref(), "fab", None).await.unwrap();
        assert_eq!(p.title.as_deref(), Some("Fab"));
        let v = p.latest_version.unwrap();
        assert_eq!(mrpack_url(&v), Some("https://cdn/x.mrpack"));
        assert_eq!(v.files[0].sha1.as_deref(), Some("s"));
        assert!(
            fetch_project(http.as_ref(), "missing", None)
                .await
                .is_none()
        );
        http.body(&format!("{API_BASE}/search?limit=10&index=relevance&query=jei&facets=%5B%5B%22project_type%3Amod%22%5D%5D"), r#"{"hits":[{"slug":"jei","downloads":5}]}"#);
        let hits = search(http.as_ref(), "jei", None, None, 10).await;
        assert_eq!(hits.len(), 1);
        assert_eq!(hits[0].downloads, 5);
    }
}
