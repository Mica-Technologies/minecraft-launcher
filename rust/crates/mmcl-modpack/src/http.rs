// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The network seam of the modpack runtime.
//!
//! Everything this crate fetches goes through [`PackHttp`], so tests run against a scripted fake
//! and never touch the network. [`NetHttp`] is the real implementation over `mmcl-net` (HTTPS
//! only for bounded fetches, the generic launcher User-Agent, proxies, retries and the stall
//! watchdog).

use std::path::Path;
use std::sync::Arc;

use futures::future::BoxFuture;
use mmcl_net::{BoundedFetchResult, DownloadError, DownloadOpts, DownloadTracker};

/// What the modpack runtime needs from the network.
pub trait PackHttp: Send + Sync {
    /// `true` while the launcher is in offline mode.
    fn is_offline(&self) -> bool;

    /// Probes connectivity and updates the offline flag.
    fn refresh_network_availability(&self) -> BoxFuture<'_, bool>;

    /// HTTPS-only, size-capped conditional GET for JSON (`If-None-Match` / `If-Modified-Since`).
    fn get_conditional<'a>(
        &'a self,
        url: &'a str,
        max_bytes: u64,
        etag: Option<&'a str>,
        last_modified: Option<&'a str>,
    ) -> BoxFuture<'a, Result<BoundedFetchResult, DownloadError>>;

    /// HTTPS-only, size-capped GET for JSON.
    fn get_bounded<'a>(
        &'a self,
        url: &'a str,
        max_bytes: u64,
    ) -> BoxFuture<'a, Result<String, DownloadError>>;

    /// GET of any text (no content-type gate, no cap), e.g. Maven metadata XML.
    fn get_string<'a>(&'a self, url: &'a str) -> BoxFuture<'a, Result<String, DownloadError>>;

    /// Downloads `url` to `dest` with retries and an atomic publish.
    fn download<'a>(
        &'a self,
        url: &'a str,
        dest: &'a Path,
        tracker: Option<Arc<DownloadTracker>>,
    ) -> BoxFuture<'a, Result<(), DownloadError>>;

    /// HTTPS-only capped download straight into `dest` (deleted on failure).
    fn download_bounded<'a>(
        &'a self,
        url: &'a str,
        dest: &'a Path,
        max_bytes: u64,
    ) -> BoxFuture<'a, Result<(), DownloadError>>;
}

/// Refuses anything but an `https://` URL. `mmcl-net` also accepts `file:` and `jar:` sources;
/// nothing this crate fetches over the network may name one (contained `jar:` entries are
/// copied locally by `ManagedFile`), so a manifest or catalogue URL can never read local files.
fn require_https(url: &str) -> Result<(), DownloadError> {
    if url.trim().to_ascii_lowercase().starts_with("https://") {
        Ok(())
    } else {
        Err(DownloadError::NonHttps(url.to_string()))
    }
}

/// [`PackHttp`] over `mmcl-net`.
#[derive(Clone, Copy, Debug, Default)]
pub struct NetHttp;

impl NetHttp {
    /// The shared instance as a trait object.
    pub fn shared() -> Arc<dyn PackHttp> {
        Arc::new(NetHttp)
    }
}

impl PackHttp for NetHttp {
    fn is_offline(&self) -> bool {
        mmcl_net::is_offline()
    }

    fn refresh_network_availability(&self) -> BoxFuture<'_, bool> {
        Box::pin(mmcl_net::check_network_availability())
    }

    fn get_conditional<'a>(
        &'a self,
        url: &'a str,
        max_bytes: u64,
        etag: Option<&'a str>,
        last_modified: Option<&'a str>,
    ) -> BoxFuture<'a, Result<BoundedFetchResult, DownloadError>> {
        Box::pin(mmcl_net::download_string_bounded_conditional(
            url,
            max_bytes,
            etag,
            last_modified,
        ))
    }

    fn get_bounded<'a>(
        &'a self,
        url: &'a str,
        max_bytes: u64,
    ) -> BoxFuture<'a, Result<String, DownloadError>> {
        Box::pin(mmcl_net::download_string_bounded(url, max_bytes))
    }

    fn get_string<'a>(&'a self, url: &'a str) -> BoxFuture<'a, Result<String, DownloadError>> {
        Box::pin(async move {
            require_https(url)?;
            mmcl_net::download_string_url(url, None).await
        })
    }

    fn download<'a>(
        &'a self,
        url: &'a str,
        dest: &'a Path,
        tracker: Option<Arc<DownloadTracker>>,
    ) -> BoxFuture<'a, Result<(), DownloadError>> {
        Box::pin(async move {
            require_https(url)?;
            let mut opts = DownloadOpts::new();
            if let Some(t) = tracker {
                opts = opts.tracker(t);
            }
            mmcl_net::download_file_url(url, dest, &opts).await
        })
    }

    fn download_bounded<'a>(
        &'a self,
        url: &'a str,
        dest: &'a Path,
        max_bytes: u64,
    ) -> BoxFuture<'a, Result<(), DownloadError>> {
        Box::pin(mmcl_net::download_file_bounded(url, dest, max_bytes))
    }
}

#[cfg(test)]
pub(crate) mod fake {
    //! A scripted [`PackHttp`] for tests: URL → body / status, with a request log.

    use super::*;
    use parking_lot::Mutex;
    use std::collections::HashMap;
    use std::sync::atomic::{AtomicBool, Ordering};

    /// One scripted response.
    #[test]
    fn net_http_refuses_non_https_before_any_io() {
        let rt = tokio::runtime::Builder::new_current_thread()
            .build()
            .unwrap();
        let d = tempfile::tempdir().unwrap();
        let dest = d.path().join("x");
        for url in ["http://a/b", "file:///etc/passwd", "jar:file:/x.jar!/a"] {
            let err = rt.block_on(NetHttp.download(url, &dest, None)).unwrap_err();
            assert!(matches!(err, DownloadError::NonHttps(_)), "{url}: {err}");
            let err = rt.block_on(NetHttp.get_string(url)).unwrap_err();
            assert!(matches!(err, DownloadError::NonHttps(_)), "{url}: {err}");
        }
        assert!(!dest.exists());
    }

    #[derive(Clone, Debug)]
    pub enum Reply {
        Body(Vec<u8>),
        /// 200 with validators.
        BodyWithEtag(Vec<u8>, String),
        NotModified,
        Status(u16),
    }

    #[derive(Default)]
    pub struct FakeHttp {
        pub offline: AtomicBool,
        pub replies: Mutex<HashMap<String, Vec<Reply>>>,
        pub log: Mutex<Vec<String>>,
    }

    impl FakeHttp {
        pub fn new() -> Arc<Self> {
            Arc::new(FakeHttp::default())
        }
        /// Every request to `url` gets `reply` (the last scripted reply repeats).
        pub fn on(&self, url: &str, reply: Reply) {
            self.replies
                .lock()
                .entry(url.to_string())
                .or_default()
                .push(reply);
        }
        pub fn body(&self, url: &str, body: impl Into<Vec<u8>>) {
            self.on(url, Reply::Body(body.into()));
        }
        pub fn set_offline(&self, v: bool) {
            self.offline.store(v, Ordering::SeqCst);
        }
        pub fn count(&self, prefix: &str) -> usize {
            self.log
                .lock()
                .iter()
                .filter(|l| l.contains(prefix))
                .count()
        }
        fn next(&self, url: &str) -> Option<Reply> {
            let mut map = self.replies.lock();
            let list = map.get_mut(url)?;
            if list.len() > 1 {
                Some(list.remove(0))
            } else {
                list.first().cloned()
            }
        }
        fn reply(&self, kind: &str, url: &str) -> Result<Reply, DownloadError> {
            self.log.lock().push(format!("{kind} {url}"));
            match self.next(url) {
                Some(Reply::Status(s)) => Err(DownloadError::Http {
                    status: s,
                    url: url.into(),
                }),
                Some(r) => Ok(r),
                None => Err(DownloadError::Http {
                    status: 404,
                    url: url.into(),
                }),
            }
        }
    }

    fn text(b: Vec<u8>) -> String {
        String::from_utf8_lossy(&b).into_owned()
    }

    impl PackHttp for FakeHttp {
        fn is_offline(&self) -> bool {
            self.offline.load(Ordering::SeqCst)
        }
        fn refresh_network_availability(&self) -> BoxFuture<'_, bool> {
            Box::pin(async move { !self.is_offline() })
        }
        fn get_conditional<'a>(
            &'a self,
            url: &'a str,
            _max: u64,
            etag: Option<&'a str>,
            lm: Option<&'a str>,
        ) -> BoxFuture<'a, Result<BoundedFetchResult, DownloadError>> {
            Box::pin(async move {
                let r = self.reply(&format!("COND etag={etag:?} lm={lm:?}"), url)?;
                Ok(match r {
                    Reply::NotModified => BoundedFetchResult {
                        body: None,
                        etag: etag.map(str::to_string),
                        last_modified: lm.map(str::to_string),
                        not_modified: true,
                    },
                    Reply::BodyWithEtag(b, e) => BoundedFetchResult {
                        body: Some(text(b)),
                        etag: Some(e),
                        last_modified: None,
                        not_modified: false,
                    },
                    Reply::Body(b) => BoundedFetchResult {
                        body: Some(text(b)),
                        etag: None,
                        last_modified: None,
                        not_modified: false,
                    },
                    Reply::Status(_) => unreachable!("handled in reply()"),
                })
            })
        }
        fn get_bounded<'a>(
            &'a self,
            url: &'a str,
            _max: u64,
        ) -> BoxFuture<'a, Result<String, DownloadError>> {
            Box::pin(async move {
                match self.reply("GET", url)? {
                    Reply::Body(b) | Reply::BodyWithEtag(b, _) => Ok(text(b)),
                    _ => Err(DownloadError::Http {
                        status: 304,
                        url: url.into(),
                    }),
                }
            })
        }
        fn get_string<'a>(&'a self, url: &'a str) -> BoxFuture<'a, Result<String, DownloadError>> {
            self.get_bounded(url, u64::MAX)
        }
        fn download<'a>(
            &'a self,
            url: &'a str,
            dest: &'a Path,
            _tracker: Option<Arc<DownloadTracker>>,
        ) -> BoxFuture<'a, Result<(), DownloadError>> {
            Box::pin(async move {
                match self.reply("DL", url)? {
                    Reply::Body(b) | Reply::BodyWithEtag(b, _) => {
                        if let Some(p) = dest.parent() {
                            std::fs::create_dir_all(p)?;
                        }
                        std::fs::write(dest, b)?;
                        Ok(())
                    }
                    _ => Err(DownloadError::Http {
                        status: 304,
                        url: url.into(),
                    }),
                }
            })
        }
        fn download_bounded<'a>(
            &'a self,
            url: &'a str,
            dest: &'a Path,
            _max: u64,
        ) -> BoxFuture<'a, Result<(), DownloadError>> {
            self.download(url, dest, None)
        }
    }
}
