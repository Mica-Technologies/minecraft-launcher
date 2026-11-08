// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The editor's I/O seam.
//!
//! Everything the editor reads from the network or the pack store goes through
//! [`EditorBackend`], so the editor logic is testable with an in-memory fake and the GUI never
//! blocks on I/O. [`EngineBackend`] is the production implementation: it runs every request on
//! the engine's tokio runtime (`Engine::spawn`) through `mmcl-net`, and hands back a
//! runtime-agnostic future GPUI's executor can await.
//!
//! The engine facade has no generic "GET this text" / "HEAD this URL" / "download to here"
//! operations, so they live here, behind the trait, and the integrator can swap them for engine
//! operations later without touching the editor.

use std::future::Future;
use std::path::{Path, PathBuf};
use std::pin::Pin;
use std::sync::Arc;
use std::time::Duration;

use mmcl_engine::{Engine, PackId, PackSummary};

/// A boxed future the editor can await from any executor.
pub type BoxFut<T> = Pin<Box<dyn Future<Output = T> + Send + 'static>>;

/// A network or storage failure, already reduced to the message the status line shows.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
#[error("{0}")]
pub struct NetError(pub String);

impl NetError {
    /// A failure with a message.
    pub fn new(message: impl Into<String>) -> NetError {
        NetError(message.into())
    }
}

/// The editor's I/O operations. All return `'static + Send` futures.
pub trait EditorBackend: Send + Sync + 'static {
    /// GET `url` as text (UTF-8, lossy). `file:` URLs read the file.
    fn get_text(&self, url: &str) -> BoxFut<Result<String, NetError>>;

    /// `HEAD url` with 5 s connect and read timeouts and **no** redirects followed; the status
    /// code. Non-http schemes fail.
    fn head_status(&self, url: &str) -> BoxFut<Result<u16, NetError>>;

    /// Downloads `url` to `dest` (overwriting).
    fn download(&self, url: &str, dest: &Path) -> BoxFut<Result<(), NetError>>;

    /// The bytes of an image, through the shared on-disk cache when the URL is http(s).
    fn image_bytes(&self, url: &str) -> BoxFut<Result<Vec<u8>, NetError>>;

    /// The manifest text of a pack (a `file:` manifest is read from disk, anything else from the
    /// cached manifest). `None` when there is none.
    fn pack_manifest_text(&self, pack: &PackSummary) -> BoxFut<Option<String>>;

    /// Looks a pack up by id (for screen requests that carry only a [`PackId`]).
    fn pack(&self, id: &PackId) -> Option<PackSummary>;

    /// The editor's announcement text (`modpacks_edit`), blank when none.
    fn announcement(&self) -> String;
}

/// HEAD timeout (connect and read), as Java's `HttpURLConnection` used.
pub const HEAD_TIMEOUT: Duration = Duration::from_secs(5);

/// The production backend over [`Engine`] and `mmcl-net`.
#[derive(Clone)]
pub struct EngineBackend {
    engine: Engine,
    head_client: Option<reqwest::Client>,
}

impl EngineBackend {
    /// A backend running on `engine`'s runtime.
    pub fn new(engine: Engine) -> EngineBackend {
        let head_client = reqwest::Client::builder()
            .user_agent(mmcl_net::user_agent())
            .connect_timeout(HEAD_TIMEOUT)
            .read_timeout(HEAD_TIMEOUT)
            .timeout(HEAD_TIMEOUT * 2)
            .redirect(reqwest::redirect::Policy::none())
            .no_proxy()
            .build()
            .ok();
        EngineBackend {
            engine,
            head_client,
        }
    }

    /// Wraps the backend for the editor.
    pub fn shared(engine: Engine) -> Arc<dyn EditorBackend> {
        Arc::new(EngineBackend::new(engine))
    }

    fn run<T, F>(&self, fut: F) -> BoxFut<Result<T, NetError>>
    where
        T: Send + 'static,
        F: Future<Output = Result<T, NetError>> + Send + 'static,
    {
        let task = self.engine.spawn(fut);
        Box::pin(async move {
            match task.await {
                Ok(r) => r,
                Err(e) => Err(NetError::new(e.to_string())),
            }
        })
    }
}

impl EditorBackend for EngineBackend {
    fn get_text(&self, url: &str) -> BoxFut<Result<String, NetError>> {
        let url = url.trim().to_string();
        self.run(async move {
            mmcl_net::download_string_url(&url, None)
                .await
                .map_err(|e| NetError::new(e.to_string()))
        })
    }

    fn head_status(&self, url: &str) -> BoxFut<Result<u16, NetError>> {
        let url = url.trim().to_string();
        let client = self.head_client.clone();
        self.run(async move {
            if !crate::url_check::is_http_url(&url) {
                return Err(NetError::new(url));
            }
            let client = client.ok_or_else(|| NetError::new("no HTTP client"))?;
            let resp = client
                .head(&url)
                .send()
                .await
                .map_err(|e| NetError::new(e.to_string()))?;
            Ok(resp.status().as_u16())
        })
    }

    fn download(&self, url: &str, dest: &Path) -> BoxFut<Result<(), NetError>> {
        let url = url.trim().to_string();
        let dest = dest.to_path_buf();
        self.run(async move {
            mmcl_net::download_file_url(&url, &dest, &mmcl_net::DownloadOpts::default())
                .await
                .map_err(|e| NetError::new(e.to_string()))
        })
    }

    fn image_bytes(&self, url: &str) -> BoxFut<Result<Vec<u8>, NetError>> {
        let url = url.trim().to_string();
        let cache = mmcl_net::CacheManager::for_paths(self.engine.paths());
        self.run(async move {
            let path: PathBuf = if crate::url_check::is_http_url(&url) {
                cache
                    .download_and_cache(&url, mmcl_net::cache::DEFAULT_TTL)
                    .await
                    .map_err(|e| NetError::new(e.to_string()))?
            } else {
                match mmcl_net::Source::parse(&url).map_err(|e| NetError::new(e.to_string()))? {
                    mmcl_net::Source::File(p) => p,
                    _ => return Err(NetError::new(url)),
                }
            };
            read_file(path)
        })
    }

    fn pack_manifest_text(&self, pack: &PackSummary) -> BoxFut<Option<String>> {
        let engine = self.engine.clone();
        let id = pack.id.clone();
        let task = self
            .engine
            .spawn(async move { engine.pack_manifest_text(&id) });
        Box::pin(async move { task.await.ok().flatten() })
    }

    fn pack(&self, id: &PackId) -> Option<PackSummary> {
        self.engine.pack(id)
    }

    fn announcement(&self) -> String {
        self.engine.announcements().modpacks_edit
    }
}

fn read_file(path: PathBuf) -> Result<Vec<u8>, NetError> {
    std::fs::read(&path).map_err(|e| NetError::new(e.to_string()))
}

#[cfg(test)]
pub(crate) mod fake {
    //! An in-memory backend for tests.

    use std::collections::HashMap;

    use parking_lot::Mutex;

    use super::*;

    /// Canned responses keyed by URL.
    #[derive(Default)]
    pub struct FakeBackend {
        pub texts: Mutex<HashMap<String, Result<String, NetError>>>,
        pub heads: Mutex<HashMap<String, Result<u16, NetError>>>,
        pub files: Mutex<HashMap<String, Vec<u8>>>,
        pub requests: Mutex<Vec<String>>,
        pub manifest: Mutex<Option<String>>,
    }

    impl FakeBackend {
        pub fn text(self, url: &str, body: &str) -> Self {
            self.texts
                .lock()
                .insert(url.to_string(), Ok(body.to_string()));
            self
        }
        pub fn head(self, url: &str, status: Result<u16, NetError>) -> Self {
            self.heads.lock().insert(url.to_string(), status);
            self
        }
        pub fn file(self, url: &str, bytes: &[u8]) -> Self {
            self.files.lock().insert(url.to_string(), bytes.to_vec());
            self
        }
        pub fn requested(&self) -> Vec<String> {
            self.requests.lock().clone()
        }
    }

    impl EditorBackend for FakeBackend {
        fn get_text(&self, url: &str) -> BoxFut<Result<String, NetError>> {
            self.requests.lock().push(url.to_string());
            let r = self
                .texts
                .lock()
                .get(url)
                .cloned()
                .unwrap_or_else(|| Err(NetError::new(format!("HTTP 404 from {url}"))));
            Box::pin(async move { r })
        }
        fn head_status(&self, url: &str) -> BoxFut<Result<u16, NetError>> {
            self.requests.lock().push(format!("HEAD {url}"));
            let r = self
                .heads
                .lock()
                .get(url)
                .cloned()
                .unwrap_or_else(|| Err(NetError::new("unreachable")));
            Box::pin(async move { r })
        }
        fn download(&self, url: &str, dest: &Path) -> BoxFut<Result<(), NetError>> {
            self.requests.lock().push(format!("GET {url}"));
            let r = match self.files.lock().get(url) {
                Some(bytes) => {
                    std::fs::write(dest, bytes).map_err(|e| NetError::new(e.to_string()))
                }
                None => Err(NetError::new(format!("HTTP 404 from {url}"))),
            };
            Box::pin(async move { r })
        }
        fn image_bytes(&self, url: &str) -> BoxFut<Result<Vec<u8>, NetError>> {
            let r = self
                .files
                .lock()
                .get(url)
                .cloned()
                .ok_or_else(|| NetError::new("missing"));
            Box::pin(async move { r })
        }
        fn pack_manifest_text(&self, _pack: &PackSummary) -> BoxFut<Option<String>> {
            let r = self.manifest.lock().clone();
            Box::pin(async move { r })
        }
        fn pack(&self, _id: &PackId) -> Option<PackSummary> {
            None
        }
        fn announcement(&self) -> String {
            String::new()
        }
    }
}
