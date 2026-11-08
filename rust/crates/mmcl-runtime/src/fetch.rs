// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The download seam. [`NetFetcher`] goes through `mmcl-net` (retries, stall watchdog, atomic
//! publish, generic User-Agent); tests pass a fake.

use std::path::Path;

use futures::future::BoxFuture;
use mmcl_net::{CancelToken, DownloadError, DownloadOpts};

/// Downloads one URL to one file.
pub trait Fetcher: Send + Sync {
    /// Downloads `url` to `dest` (replacing it atomically), with an optional `Accept` header.
    /// Must stop with [`DownloadError::Cancelled`] once `cancel` fires.
    fn fetch<'a>(
        &'a self,
        url: &'a str,
        dest: &'a Path,
        accept: Option<&'a str>,
        cancel: &'a CancelToken,
    ) -> BoxFuture<'a, Result<(), DownloadError>>;
}

/// The production fetcher: [`mmcl_net::download_file_url`].
#[derive(Debug, Default, Clone, Copy)]
pub struct NetFetcher;

impl Fetcher for NetFetcher {
    fn fetch<'a>(
        &'a self,
        url: &'a str,
        dest: &'a Path,
        accept: Option<&'a str>,
        cancel: &'a CancelToken,
    ) -> BoxFuture<'a, Result<(), DownloadError>> {
        Box::pin(async move {
            let mut opts = DownloadOpts::new().cancel(cancel.clone());
            if let Some(a) = accept {
                opts = opts.accept(a);
            }
            mmcl_net::download_file_url(url, dest, &opts).await
        })
    }
}
