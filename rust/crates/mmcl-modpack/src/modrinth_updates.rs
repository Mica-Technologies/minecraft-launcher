// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Per-jar update check against Modrinth (Java `ModrinthModUpdateChecker`). Serial and off the
//! UI thread; every failure reads as "not on Modrinth".

use std::collections::HashMap;
use std::fs;
use std::path::Path;

use mmcl_i18n::tf;
use serde_json::{Map, Value};

use crate::http::PackHttp;

/// The Modrinth API base.
pub const API_BASE: &str = "https://api.modrinth.com/v2";
/// Cap of one API response.
pub const MAX_RESPONSE_BYTES: u64 = 5 * 1024 * 1024;

/// What the check found for one jar.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Status {
    NotOnModrinth,
    UpToDate,
    UpdateAvailable,
}

/// One jar's result.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ModUpdate {
    pub status: Status,
    pub current_version: Option<String>,
    pub latest_version: Option<String>,
    pub latest_download_url: Option<String>,
    pub project_name: Option<String>,
}

impl ModUpdate {
    pub fn not_on_modrinth() -> Self {
        ModUpdate {
            status: Status::NotOnModrinth,
            current_version: None,
            latest_version: None,
            latest_download_url: None,
            project_name: None,
        }
    }
    fn up_to_date(current: Option<String>, project: Option<String>) -> Self {
        ModUpdate {
            status: Status::UpToDate,
            current_version: current,
            project_name: project,
            ..Self::not_on_modrinth()
        }
    }
}

pub(crate) fn opt_string(o: &Map<String, Value>, k: &str) -> Option<String> {
    match o.get(k) {
        Some(v @ (Value::String(_) | Value::Number(_) | Value::Bool(_))) => {
            mmcl_manifest::lenient::coerce_string(v)
        }
        _ => None,
    }
}

pub(crate) async fn fetch_object(http: &dyn PackHttp, url: &str) -> Option<Map<String, Value>> {
    let body = http.get_bounded(url, MAX_RESPONSE_BYTES).await.ok()?;
    if body.trim().is_empty() {
        return None;
    }
    match serde_json::from_str::<Value>(&body) {
        Ok(Value::Object(o)) => Some(o),
        _ => None,
    }
}

/// The primary file's URL of a version (else the first file with a URL).
pub(crate) fn primary_file_url(version: &Map<String, Value>) -> Option<String> {
    let files = version.get("files")?.as_array()?;
    let mut fallback = None;
    for f in files.iter().filter_map(Value::as_object) {
        let Some(url) = opt_string(f, "url") else {
            continue;
        };
        if f.get("primary").and_then(Value::as_bool) == Some(true) {
            return Some(url);
        }
        fallback.get_or_insert(url);
    }
    fallback
}

async fn check_one(http: &dyn PackHttp, jar: &Path) -> ModUpdate {
    let j = jar.to_path_buf();
    let sha = crate::managed::blocking(move || {
        crate::managed::hash_file(&j, mmcl_manifest::HashAlgo::Sha1).ok()
    })
    .await
    .ok()
    .flatten();
    let Some(sha) = sha else {
        return ModUpdate::not_on_modrinth();
    };
    let Some(current) = fetch_object(http, &format!("{API_BASE}/version_file/{sha}")).await else {
        return ModUpdate::not_on_modrinth();
    };
    let current_number = opt_string(&current, "version_number");
    let Some(project_id) = opt_string(&current, "project_id") else {
        return ModUpdate::not_on_modrinth();
    };
    let Some(project) = fetch_object(http, &format!("{API_BASE}/project/{project_id}")).await
    else {
        return ModUpdate::not_on_modrinth();
    };
    let name = opt_string(&project, "title");
    let Some(latest_id) = project
        .get("versions")
        .and_then(Value::as_array)
        .and_then(|a| a.last())
        .and_then(mmcl_manifest::lenient::coerce_string)
    else {
        return ModUpdate::up_to_date(current_number, name);
    };
    if Some(&latest_id) == opt_string(&current, "id").as_ref() {
        return ModUpdate::up_to_date(current_number, name);
    }
    let Some(latest) = fetch_object(http, &format!("{API_BASE}/version/{latest_id}")).await else {
        return ModUpdate::up_to_date(current_number, name);
    };
    ModUpdate {
        status: Status::UpdateAvailable,
        current_version: current_number,
        latest_version: opt_string(&latest, "version_number"),
        latest_download_url: primary_file_url(&latest),
        project_name: name,
    }
}

/// Checks every `*.jar` in `mods_dir`; the map is keyed by jar file name.
pub async fn scan(mods_dir: &Path, http: &dyn PackHttp) -> HashMap<String, ModUpdate> {
    let Ok(rd) = fs::read_dir(mods_dir) else {
        return HashMap::new();
    };
    let mut jars: Vec<_> = rd
        .flatten()
        .filter(|e| {
            e.file_type().is_ok_and(|t| t.is_file())
                && e.file_name()
                    .to_string_lossy()
                    .to_lowercase()
                    .ends_with(".jar")
        })
        .map(|e| e.path())
        .collect();
    jars.sort();
    let mut out = HashMap::new();
    for jar in jars {
        let name = jar
            .file_name()
            .map(|n| n.to_string_lossy().into_owned())
            .unwrap_or_default();
        let r = check_one(http, &jar).await;
        if r.status == Status::NotOnModrinth {
            mmcl_log::debug(&tf!(
                "log.modrinthUpdateChecker.checkFailed",
                &name,
                "not found"
            ));
        }
        out.insert(name, r);
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::http::fake::FakeHttp;

    #[tokio::test]
    async fn statuses() {
        let d = tempfile::tempdir().unwrap();
        fs::write(d.path().join("a.jar"), "a").unwrap();
        fs::write(d.path().join("b.jar"), "b").unwrap();
        fs::write(d.path().join("c.jar"), "c").unwrap();
        fs::write(d.path().join("notes.txt"), "x").unwrap();
        let sha = |s: &[u8]| mmcl_util::hash::sha1_hex(s);
        let http = FakeHttp::new();
        http.body(
            &format!("{API_BASE}/version_file/{}", sha(b"a")),
            r#"{"id":"v1","project_id":"p1","version_number":"1.0"}"#,
        );
        http.body(
            &format!("{API_BASE}/project/p1"),
            r#"{"title":"Alpha","versions":["v0","v2"]}"#,
        );
        http.body(
            &format!("{API_BASE}/version/v2"),
            r#"{"version_number":"2.0","files":[{"url":"https://cdn/x.jar"},{"url":"https://cdn/a2.jar","primary":true}]}"#,
        );
        http.body(
            &format!("{API_BASE}/version_file/{}", sha(b"b")),
            r#"{"id":"v9","project_id":"p2","version_number":"9"}"#,
        );
        http.body(
            &format!("{API_BASE}/project/p2"),
            r#"{"title":"Beta","versions":["v9"]}"#,
        );
        let r = scan(d.path(), http.as_ref()).await;
        assert_eq!(r.len(), 3);
        let a = &r["a.jar"];
        assert_eq!(a.status, Status::UpdateAvailable);
        assert_eq!(a.latest_version.as_deref(), Some("2.0"));
        assert_eq!(a.latest_download_url.as_deref(), Some("https://cdn/a2.jar"));
        assert_eq!(a.project_name.as_deref(), Some("Alpha"));
        assert_eq!(r["b.jar"].status, Status::UpToDate);
        assert_eq!(r["c.jar"].status, Status::NotOnModrinth);
    }
}
