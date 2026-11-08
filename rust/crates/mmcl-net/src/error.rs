// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The crate's error type. Messages match the Java exception texts (they stay English: they are
//! exception messages, not UI strings); [`DownloadError::message_key`] gives the localization key
//! a front end shows instead.

use std::io;

/// Everything a fetch or download can fail with.
#[derive(Debug, thiserror::Error)]
pub enum DownloadError {
    /// The source string is not an `http(s):`, `file:` or `jar:file:` URL.
    #[error("Invalid download source: {0}")]
    InvalidSource(String),
    /// A status of 400 or more.
    #[error("HTTP {status} from {url}")]
    Http { status: u16, url: String },
    /// The connection, TLS handshake, read timeout or protocol failed.
    #[error("Request to {url} failed: {message}")]
    Request { url: String, message: String },
    /// The HTTP client could not be built.
    #[error("The HTTP client could not be created: {0}")]
    Client(String),
    /// A local read or write failed.
    #[error("{0}")]
    Io(#[from] io::Error),
    /// The stall watchdog fired: too few bytes in one window.
    #[error("Download stalled (received {bytes} bytes in {seconds}s): {url}")]
    Stalled {
        bytes: u64,
        seconds: u64,
        url: String,
    },
    /// The caller cancelled. Never retried.
    #[error("Download interrupted: {0}")]
    Cancelled(String),
    /// A bounded fetch (or a hop of its redirect chain) was not HTTPS.
    #[error("Refusing non-HTTPS URL on bounded fetch: {0}")]
    NonHttps(String),
    /// A redirect without a usable `Location`.
    #[error("Redirect without Location header from {0}")]
    RedirectWithoutLocation(String),
    /// More than `MAX_REDIRECTS` hops.
    #[error("Too many redirects following {0}")]
    TooManyRedirects(String),
    /// The bounded JSON fetch got a content type that contradicts JSON.
    #[error("Unexpected Content-Type for bounded JSON fetch from {url}: {content_type}")]
    ContentType { url: String, content_type: String },
    /// The body passed the cap while reading.
    #[error("Response from {url} exceeded max-bytes cap ({max})")]
    TooLarge { url: String, max: u64 },
    /// The declared `Content-Length` is over the cap (bounded file download).
    #[error("Response from {url} declares {declared} bytes, over the max-bytes cap ({max})")]
    DeclaredTooLarge {
        url: String,
        declared: u64,
        max: u64,
    },
    /// The downloaded bytes do not match the expected digest.
    #[error(
        "Downloaded file from {url} failed its {algorithm} check (expected {expected}, got {actual})"
    )]
    HashMismatch {
        url: String,
        algorithm: &'static str,
        expected: String,
        actual: String,
    },
    /// `jar:` entry not present in the jar.
    #[error("Entry {entry} not found in {jar}")]
    JarEntryMissing { jar: String, entry: String },
    /// The cache refused a non-network URL.
    #[error("Refusing to cache non-http(s) URL: {0}")]
    CacheRefused(String),
    /// The cache download produced no bytes.
    #[error("Cached download was empty: {0}")]
    CacheEmpty(String),
    /// JSON from a fetch could not be parsed.
    #[error("Invalid JSON from {url}: {message}")]
    Json { url: String, message: String },
}

impl DownloadError {
    /// `true` for a cancel (callers stop instead of reporting a failure).
    pub fn is_cancelled(&self) -> bool {
        matches!(self, DownloadError::Cancelled(_))
    }

    /// The localization key of the message a user sees for this error.
    pub fn message_key(&self) -> &'static str {
        match self {
            DownloadError::Cancelled(_) => "network.error.cancelled",
            DownloadError::Stalled { .. } => "network.error.stalled",
            DownloadError::HashMismatch { .. } => "network.error.hashMismatch",
            DownloadError::Http { .. } => "network.error.http",
            DownloadError::NonHttps(_) | DownloadError::CacheRefused(_) => "network.error.insecure",
            DownloadError::TooLarge { .. } | DownloadError::DeclaredTooLarge { .. } => {
                "network.error.tooLarge"
            }
            DownloadError::Io(_) => "network.error.io",
            _ => "network.error.generic",
        }
    }

    pub(crate) fn request(url: &str, e: reqwest::Error) -> DownloadError {
        // `without_url` keeps reqwest's copy of the URL out of the text: the caller's URL is
        // already in the message, once. The cause chain carries the useful part (DNS, TLS,
        // refused, timed out).
        let timed_out = e.is_timeout();
        let e = e.without_url();
        let mut message = e.to_string();
        let mut source = std::error::Error::source(&e);
        while let Some(cause) = source {
            message.push_str(": ");
            message.push_str(&cause.to_string());
            source = cause.source();
        }
        if timed_out && !message.contains("timed out") {
            message.push_str(" (timed out)");
        }
        DownloadError::Request {
            url: url.to_string(),
            message,
        }
    }
}

/// Failures of [`crate::pool::await_all`].
#[derive(Debug, thiserror::Error)]
pub enum AwaitError<E> {
    /// The deadline passed before every task finished.
    #[error("Download tasks did not complete within the allotted time.")]
    Timeout,
    /// A task returned an error (the first one seen wins).
    #[error("{0}")]
    Failed(E),
    /// A task panicked.
    #[error("A download task panicked: {0}")]
    Panicked(String),
    /// The caller's own token was cancelled, or a task was aborted from outside.
    #[error("Download tasks were cancelled.")]
    Cancelled,
}
