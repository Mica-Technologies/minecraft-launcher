// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! "Check URLs": a HEAD per row, no redirects followed; 2xx and 3xx count as reachable.

use mmcl_i18n::t;

use crate::backend::{EditorBackend, NetError};
use crate::row::UrlCheck;

/// `http://` or `https://` (any case).
pub fn is_http_url(url: &str) -> bool {
    let l = url.trim().to_ascii_lowercase();
    l.starts_with("http://") || l.starts_with("https://")
}

/// The status cell for a HEAD outcome: `OK` for 200..400, the numeric code otherwise, `ERR` for
/// any failure (non-http schemes such as `modrinth:` and `file:` included).
pub fn status_for(result: Result<u16, NetError>) -> UrlCheck {
    match result {
        Ok(code) if (200..400).contains(&code) => UrlCheck {
            ok: true,
            label: t!("editor.urlStatus.ok").into_owned(),
        },
        Ok(code) => UrlCheck {
            ok: false,
            label: code.to_string(),
        },
        Err(_) => UrlCheck {
            ok: false,
            label: t!("editor.urlStatus.error").into_owned(),
        },
    }
}

/// Checks one URL.
pub async fn check_url(backend: &dyn EditorBackend, url: &str) -> UrlCheck {
    if !is_http_url(url) {
        return status_for(Err(NetError::new(url)));
    }
    status_for(backend.head_status(url).await)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::backend::fake::FakeBackend;

    #[test]
    fn status_mapping() {
        assert!(status_for(Ok(200)).ok);
        assert!(status_for(Ok(302)).ok);
        let nf = status_for(Ok(404));
        assert!(!nf.ok);
        assert_eq!(nf.label, "404");
        assert!(!status_for(Ok(405)).ok);
        let e = status_for(Err(NetError::new("x")));
        assert!(!e.ok);
        assert_eq!(e.label, t!("editor.urlStatus.error"));
    }

    #[test]
    fn non_http_schemes_fail_without_a_request() {
        let b = FakeBackend::default().head("https://ok/x", Ok(200));
        let r = futures::executor::block_on(check_url(&b, "modrinth:sodium"));
        assert!(!r.ok);
        let r = futures::executor::block_on(check_url(&b, "https://ok/x"));
        assert!(r.ok);
        assert_eq!(b.requested(), ["HEAD https://ok/x"]);
        assert!(is_http_url("HTTPS://x"));
        assert!(!is_http_url("file:/x"));
    }
}
