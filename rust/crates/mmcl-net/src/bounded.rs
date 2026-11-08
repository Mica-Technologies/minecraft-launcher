// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Bounded fetches: JSON that goes straight into a parser, and capped binary archives.
//!
//! Every hop must be HTTPS. Redirects are walked by hand (at most [`MAX_REDIRECTS`], so six
//! requests) so an HTTPS-to-HTTP downgrade cannot slip a plaintext body into a flow the caller
//! believes is end-to-end HTTPS. The body is capped while reading; `Content-Length` is not
//! trusted. JSON fetches also gate the `Content-Type`.

use std::path::Path;

use reqwest::StatusCode;
use reqwest::header::{self, HeaderMap, HeaderValue};
use tokio::io::AsyncWriteExt;
use url::Url;

use crate::client::clients;
use crate::download::DOWNLOAD_BUFFER_SIZE;
use crate::error::DownloadError;
use crate::offline::note_network_success;

/// Redirect hops a bounded fetch follows.
pub const MAX_REDIRECTS: usize = 5;
/// Cap for announcements and GitHub release JSON.
pub const SMALL_JSON_MAX_BYTES: u64 = 256 * 1024;
/// Cap for modpack manifests.
pub const MANIFEST_MAX_BYTES: u64 = 50 * 1024 * 1024;

/// What a conditional bounded fetch returned.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct BoundedFetchResult {
    /// The body; `None` on a 304.
    pub body: Option<String>,
    /// `ETag` of the response (on a 304, falling back to the one sent).
    pub etag: Option<String>,
    /// `Last-Modified` of the response (on a 304, falling back to the one sent).
    pub last_modified: Option<String>,
    /// `true` for 304 Not Modified: reuse the cached body.
    pub not_modified: bool,
}

impl BoundedFetchResult {
    pub fn is_not_modified(&self) -> bool {
        self.not_modified
    }
}

/// The scheme policy of the redirect walker. Always HTTPS outside tests.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Scheme {
    HttpsOnly,
    #[cfg(test)]
    AllowHttp,
}

impl Scheme {
    fn allows(self, u: &Url) -> bool {
        match self {
            Scheme::HttpsOnly => u.scheme() == "https",
            #[cfg(test)]
            Scheme::AllowHttp => matches!(u.scheme(), "https" | "http"),
        }
    }
}

/// The content-type gate. Missing or blank passes; otherwise the lowercased value must start
/// with a JSON type, or with `text/plain` / `application/octet-stream` (what static blob hosts
/// serve `.mmcjson` as).
pub fn assert_acceptable_json_content_type(
    content_type: Option<&str>,
    from: &str,
) -> Result<(), DownloadError> {
    let Some(ct) = content_type.filter(|c| !c.trim().is_empty()) else {
        return Ok(());
    };
    let lower = ct.to_ascii_lowercase();
    const OK: [&str; 5] = [
        "application/json",
        "text/json",
        "application/javascript",
        "text/plain",
        "application/octet-stream",
    ];
    if OK.iter().any(|p| lower.starts_with(p)) {
        return Ok(());
    }
    Err(DownloadError::ContentType {
        url: from.to_string(),
        content_type: ct.to_string(),
    })
}

enum Hop {
    Final(reqwest::Response, Url),
    NotModified(reqwest::Response),
}

fn parse_url(s: &str) -> Result<Url, DownloadError> {
    Url::parse(s.trim()).map_err(|_| DownloadError::InvalidSource(s.to_string()))
}

fn is_redirect(s: StatusCode) -> bool {
    matches!(s.as_u16(), 301 | 302 | 303 | 307 | 308)
}

async fn walk(
    client: &reqwest::Client,
    source: &str,
    scheme: Scheme,
    headers: &HeaderMap,
    conditional: bool,
) -> Result<Hop, DownloadError> {
    let mut current = parse_url(source)?;
    for _hop in 0..=MAX_REDIRECTS {
        if !scheme.allows(&current) {
            return Err(DownloadError::NonHttps(current.to_string()));
        }
        let resp = client
            .get(current.clone())
            .headers(headers.clone())
            .send()
            .await
            .map_err(|e| DownloadError::request(current.as_str(), e))?;
        let status = resp.status();
        if conditional && status == StatusCode::NOT_MODIFIED {
            return Ok(Hop::NotModified(resp));
        }
        if is_redirect(status) {
            let loc = resp
                .headers()
                .get(header::LOCATION)
                .and_then(|v| v.to_str().ok())
                .map(str::trim)
                .filter(|s| !s.is_empty())
                .map(str::to_string);
            let Some(loc) = loc else {
                return Err(DownloadError::RedirectWithoutLocation(current.to_string()));
            };
            current = current
                .join(&loc)
                .map_err(|_| DownloadError::InvalidSource(loc.clone()))?;
            continue;
        }
        return Ok(Hop::Final(resp, current));
    }
    Err(DownloadError::TooManyRedirects(source.to_string()))
}

/// The response of a non-conditional walk (which only reports 304 when the server sends one
/// unprompted: an error here).
fn final_hop(h: Hop, url: &str) -> Result<(reqwest::Response, Url), DownloadError> {
    match h {
        Hop::Final(r, u) => Ok((r, u)),
        Hop::NotModified(r) => Err(DownloadError::Http {
            status: r.status().as_u16(),
            url: url.to_string(),
        }),
    }
}

fn header_string(h: &HeaderMap, name: header::HeaderName) -> Option<String> {
    h.get(name)
        .and_then(|v| v.to_str().ok())
        .map(str::to_string)
}

async fn read_capped(
    mut resp: reqwest::Response,
    source: &str,
    max: u64,
) -> Result<Vec<u8>, DownloadError> {
    let mut out = Vec::new();
    let mut total: u64 = 0;
    while let Some(chunk) = resp
        .chunk()
        .await
        .map_err(|e| DownloadError::request(source, e))?
    {
        total += chunk.len() as u64;
        if total > max {
            return Err(DownloadError::TooLarge {
                url: source.to_string(),
                max,
            });
        }
        out.extend_from_slice(&chunk);
    }
    Ok(out)
}

fn check_status(resp: &reqwest::Response, url: &Url) -> Result<(), DownloadError> {
    let status = resp.status().as_u16();
    if status >= 400 {
        return Err(DownloadError::Http {
            status,
            url: url.to_string(),
        });
    }
    Ok(())
}

/// HTTPS-only bounded string fetch for JSON.
pub async fn download_string_bounded(url: &str, max_bytes: u64) -> Result<String, DownloadError> {
    bounded_string_with(&clients()?.bounded, url, max_bytes, None, Scheme::HttpsOnly).await
}

/// [`download_string_bounded`] with an `Accept` header (GitHub's API wants its media type).
pub async fn download_string_bounded_accept(
    url: &str,
    max_bytes: u64,
    accept: &str,
) -> Result<String, DownloadError> {
    bounded_string_with(
        &clients()?.bounded,
        url,
        max_bytes,
        Some(accept),
        Scheme::HttpsOnly,
    )
    .await
}

pub(crate) async fn bounded_string_with(
    client: &reqwest::Client,
    url: &str,
    max_bytes: u64,
    accept: Option<&str>,
    scheme: Scheme,
) -> Result<String, DownloadError> {
    let mut headers = HeaderMap::new();
    if let Some(a) = accept.and_then(|a| HeaderValue::from_str(a).ok()) {
        headers.insert(header::ACCEPT, a);
    }
    let (resp, final_url) = final_hop(walk(client, url, scheme, &headers, false).await?, url)?;
    check_status(&resp, &final_url)?;
    let ct = header_string(resp.headers(), header::CONTENT_TYPE);
    assert_acceptable_json_content_type(ct.as_deref(), final_url.as_str())?;
    let body = read_capped(resp, url, max_bytes).await?;
    note_network_success();
    Ok(String::from_utf8_lossy(&body).into_owned())
}

/// HTTPS-only bounded fetch with `If-None-Match` / `If-Modified-Since` from the stored
/// validators (sent when non-blank). A 304 gives no body and the response's validators,
/// falling back to the ones sent.
pub async fn download_string_bounded_conditional(
    url: &str,
    max_bytes: u64,
    prev_etag: Option<&str>,
    prev_last_modified: Option<&str>,
) -> Result<BoundedFetchResult, DownloadError> {
    conditional_with(
        &clients()?.bounded,
        url,
        max_bytes,
        prev_etag,
        prev_last_modified,
        Scheme::HttpsOnly,
    )
    .await
}

pub(crate) async fn conditional_with(
    client: &reqwest::Client,
    url: &str,
    max_bytes: u64,
    prev_etag: Option<&str>,
    prev_last_modified: Option<&str>,
    scheme: Scheme,
) -> Result<BoundedFetchResult, DownloadError> {
    let prev_etag = prev_etag.filter(|s| !s.trim().is_empty());
    let prev_lm = prev_last_modified.filter(|s| !s.trim().is_empty());
    let mut headers = HeaderMap::new();
    if let Some(v) = prev_etag.and_then(|v| HeaderValue::from_str(v).ok()) {
        headers.insert(header::IF_NONE_MATCH, v);
    }
    if let Some(v) = prev_lm.and_then(|v| HeaderValue::from_str(v).ok()) {
        headers.insert(header::IF_MODIFIED_SINCE, v);
    }
    match walk(client, url, scheme, &headers, true).await? {
        Hop::NotModified(resp) => {
            let etag =
                header_string(resp.headers(), header::ETAG).or(prev_etag.map(str::to_string));
            let last_modified = header_string(resp.headers(), header::LAST_MODIFIED)
                .or(prev_lm.map(str::to_string));
            note_network_success();
            Ok(BoundedFetchResult {
                body: None,
                etag,
                last_modified,
                not_modified: true,
            })
        }
        Hop::Final(resp, final_url) => {
            check_status(&resp, &final_url)?;
            let ct = header_string(resp.headers(), header::CONTENT_TYPE);
            assert_acceptable_json_content_type(ct.as_deref(), final_url.as_str())?;
            let etag = header_string(resp.headers(), header::ETAG);
            let last_modified = header_string(resp.headers(), header::LAST_MODIFIED);
            let body = read_capped(resp, url, max_bytes).await?;
            note_network_success();
            Ok(BoundedFetchResult {
                body: Some(String::from_utf8_lossy(&body).into_owned()),
                etag,
                last_modified,
                not_modified: false,
            })
        }
    }
}

/// HTTPS-only capped download of a binary archive straight into `dest` (not a temp file). A
/// declared `Content-Length` over the cap fails before reading; the copy aborts past the cap.
/// **On any failure `dest` is deleted.**
pub async fn download_file_bounded(
    url: &str,
    dest: &Path,
    max_bytes: u64,
) -> Result<(), DownloadError> {
    let client = match clients() {
        Ok(c) => c,
        Err(e) => {
            let _ = tokio::fs::remove_file(dest).await;
            return Err(e);
        }
    };
    file_bounded_with(&client.bounded, url, dest, max_bytes, Scheme::HttpsOnly).await
}

pub(crate) async fn file_bounded_with(
    client: &reqwest::Client,
    url: &str,
    dest: &Path,
    max_bytes: u64,
    scheme: Scheme,
) -> Result<(), DownloadError> {
    // Deletes `dest` on failure, and also when this future is dropped mid-transfer, so an
    // aborted fetch never leaves a truncated archive behind.
    struct RemoveUnlessDone<'a> {
        dest: &'a Path,
        done: bool,
    }
    impl Drop for RemoveUnlessDone<'_> {
        fn drop(&mut self) {
            if !self.done {
                let _ = std::fs::remove_file(self.dest);
            }
        }
    }
    let mut guard = RemoveUnlessDone { dest, done: false };
    let result = file_bounded_inner(client, url, dest, max_bytes, scheme).await;
    guard.done = result.is_ok();
    result
}

async fn file_bounded_inner(
    client: &reqwest::Client,
    url: &str,
    dest: &Path,
    max_bytes: u64,
    scheme: Scheme,
) -> Result<(), DownloadError> {
    let (mut resp, _) = final_hop(
        walk(client, url, scheme, &HeaderMap::new(), false).await?,
        url,
    )?;
    let status = resp.status().as_u16();
    if status >= 400 {
        return Err(DownloadError::Http {
            status,
            url: url.to_string(),
        });
    }
    if let Some(declared) = resp.content_length()
        && declared > max_bytes
    {
        return Err(DownloadError::DeclaredTooLarge {
            url: url.to_string(),
            declared,
            max: max_bytes,
        });
    }
    let file = tokio::fs::File::create(dest).await?;
    let mut out = tokio::io::BufWriter::with_capacity(DOWNLOAD_BUFFER_SIZE, file);
    let mut total: u64 = 0;
    while let Some(chunk) = resp
        .chunk()
        .await
        .map_err(|e| DownloadError::request(url, e))?
    {
        total += chunk.len() as u64;
        if total > max_bytes {
            return Err(DownloadError::TooLarge {
                url: url.to_string(),
                max: max_bytes,
            });
        }
        out.write_all(&chunk).await?;
    }
    out.flush().await?;
    drop(out);
    note_network_success();
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    // NetworkUtilitiesTest: assertAcceptableJsonContentType_*
    const U: &str = "https://example.com/x.json";

    #[test]
    fn allows_explicit_json_declarations() {
        for ct in [
            "application/json",
            "application/json; charset=utf-8",
            "text/json",
            "application/javascript",
        ] {
            assert!(
                assert_acceptable_json_content_type(Some(ct), U).is_ok(),
                "{ct}"
            );
        }
    }

    #[test]
    fn allows_generic_static_host_types() {
        for ct in [
            "text/plain",
            "application/octet-stream",
            "text/plain; charset=utf-8",
        ] {
            assert!(
                assert_acceptable_json_content_type(Some(ct), U).is_ok(),
                "{ct}"
            );
        }
    }

    #[test]
    fn allows_missing_or_blank_content_type() {
        for ct in [None, Some(""), Some("   ")] {
            assert!(assert_acceptable_json_content_type(ct, U).is_ok());
        }
    }

    #[test]
    fn rejects_html_content_type() {
        let e = assert_acceptable_json_content_type(Some("text/html"), U).unwrap_err();
        assert!(e.to_string().contains("text/html"));
    }

    #[test]
    fn content_type_gate_is_case_insensitive() {
        assert!(assert_acceptable_json_content_type(Some("APPLICATION/JSON"), U).is_ok());
        assert!(assert_acceptable_json_content_type(Some("TEXT/HTML"), U).is_err());
    }

    // NetworkUtilitiesTest: boundedFileDownload_refusesPlainHttpAndRemovesTheDestination
    #[tokio::test]
    async fn bounded_file_download_refuses_plain_http_and_removes_the_destination() {
        let dir = tempfile::tempdir().unwrap();
        let dest = dir.path().join("archive.zip");
        std::fs::write(&dest, b"stale").unwrap();
        let err = download_file_bounded("http://example.com/archive.zip", &dest, 1024)
            .await
            .unwrap_err();
        assert!(matches!(err, DownloadError::NonHttps(_)), "{err}");
        assert!(
            !dest.exists(),
            "partial destination should be deleted on failure"
        );
    }

    #[tokio::test]
    async fn bounded_string_fetch_refuses_plain_http_before_any_request() {
        let err = download_string_bounded("http://example.com/x.json", 1024)
            .await
            .unwrap_err();
        assert_eq!(
            err.to_string(),
            "Refusing non-HTTPS URL on bounded fetch: http://example.com/x.json"
        );
    }
}
