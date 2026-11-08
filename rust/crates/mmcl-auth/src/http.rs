// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The HTTP seam of the protocol client. Production uses [`ReqwestTransport`]; tests answer
//! from a script, so no test touches the network.
//!
//! Response bodies are returned whatever the status (Microsoft's error bodies are JSON the
//! protocol layer parses). Requests carry only the generic launcher User-Agent.

use std::fmt;
use std::time::Duration;

use futures::FutureExt;
use futures::future::BoxFuture;

/// Connect timeout of each request (the Java library's `serviceConnectTimeout`).
pub const CONNECT_TIMEOUT: Duration = Duration::from_secs(5);
/// Idle read timeout of each request (`serviceReadTimeout`).
pub const READ_TIMEOUT: Duration = Duration::from_secs(10);
/// Upper bound for one whole request.
pub const REQUEST_TIMEOUT: Duration = Duration::from_secs(30);

/// The two methods the protocol needs.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Method {
    Get,
    Post,
}

/// One request.
#[derive(Clone)]
pub struct HttpRequest {
    pub method: Method,
    pub url: String,
    pub headers: Vec<(String, String)>,
    pub body: Option<Vec<u8>>,
}

impl fmt::Debug for HttpRequest {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        // Bodies and authorization headers carry tokens.
        f.debug_struct("HttpRequest")
            .field("method", &self.method)
            .field("url", &self.url)
            .field(
                "headers",
                &self
                    .headers
                    .iter()
                    .map(|(k, _)| k.as_str())
                    .collect::<Vec<_>>(),
            )
            .field("body_len", &self.body.as_ref().map(Vec::len))
            .finish()
    }
}

impl HttpRequest {
    /// A GET with `Accept: application/json`.
    pub fn get(url: impl Into<String>) -> HttpRequest {
        HttpRequest {
            method: Method::Get,
            url: url.into(),
            headers: vec![("Accept".into(), "application/json".into())],
            body: None,
        }
    }

    /// A POST of a form body.
    pub fn post_form(url: impl Into<String>, body: String) -> HttpRequest {
        HttpRequest {
            method: Method::Post,
            url: url.into(),
            headers: vec![
                ("Accept".into(), "application/json".into()),
                (
                    "Content-Type".into(),
                    "application/x-www-form-urlencoded".into(),
                ),
            ],
            body: Some(body.into_bytes()),
        }
    }

    /// A POST of a JSON body.
    pub fn post_json(url: impl Into<String>, body: &serde_json::Value) -> HttpRequest {
        HttpRequest {
            method: Method::Post,
            url: url.into(),
            headers: vec![
                ("Accept".into(), "application/json".into()),
                ("Content-Type".into(), "application/json".into()),
            ],
            body: Some(body.to_string().into_bytes()),
        }
    }

    /// Adds a header.
    pub fn header(mut self, name: &str, value: impl Into<String>) -> HttpRequest {
        self.headers.push((name.to_string(), value.into()));
        self
    }

    /// The header `name` (case-insensitive), for tests and logs.
    pub fn header_value(&self, name: &str) -> Option<&str> {
        self.headers
            .iter()
            .find(|(k, _)| k.eq_ignore_ascii_case(name))
            .map(|(_, v)| v.as_str())
    }

    /// The body as text.
    pub fn body_text(&self) -> String {
        self.body
            .as_deref()
            .map(|b| String::from_utf8_lossy(b).into_owned())
            .unwrap_or_default()
    }
}

/// One response.
#[derive(Clone, Debug)]
pub struct HttpResponse {
    pub status: u16,
    pub body: Vec<u8>,
}

/// Why a request got no response. Carries no URL or data.
#[derive(Clone, Debug, PartialEq, Eq, thiserror::Error)]
pub enum TransportError {
    #[error("the request timed out")]
    Timeout,
    #[error("the connection failed")]
    Connect,
    #[error("the request failed")]
    Request,
    #[error("the response body could not be read")]
    Body,
    #[error("the HTTP client could not be created")]
    Client,
}

impl TransportError {
    /// A data-free name for logs.
    pub fn kind_name(&self) -> &'static str {
        match self {
            TransportError::Timeout => "Timeout",
            TransportError::Connect => "Connect",
            TransportError::Request => "Request",
            TransportError::Body => "Body",
            TransportError::Client => "Client",
        }
    }
}

/// Sends requests.
pub trait HttpTransport: Send + Sync {
    fn send(&self, req: HttpRequest) -> BoxFuture<'_, Result<HttpResponse, TransportError>>;
}

/// The reqwest transport: rustls, gzip, 5 s connect / 10 s read timeouts, the launcher's proxy
/// setting, the generic `MicaMinecraftLauncher/<version>` User-Agent.
#[derive(Clone, Debug)]
pub struct ReqwestTransport {
    client: reqwest::Client,
}

impl ReqwestTransport {
    /// Builds the client with the current proxy setting.
    pub fn new() -> Result<ReqwestTransport, TransportError> {
        let mut b = reqwest::Client::builder()
            .user_agent(mmcl_net::user_agent())
            .connect_timeout(CONNECT_TIMEOUT)
            .read_timeout(READ_TIMEOUT)
            .timeout(REQUEST_TIMEOUT);
        match mmcl_net::proxy::current_proxy() {
            Some(p) => {
                let px = reqwest::Proxy::all(p.url()).map_err(|_| TransportError::Client)?;
                b = b.proxy(px);
            }
            None => b = b.no_proxy(),
        }
        let client = b.build().map_err(|_| TransportError::Client)?;
        Ok(ReqwestTransport { client })
    }
}

impl HttpTransport for ReqwestTransport {
    fn send(&self, req: HttpRequest) -> BoxFuture<'_, Result<HttpResponse, TransportError>> {
        async move {
            let mut rb = match req.method {
                Method::Get => self.client.get(&req.url),
                Method::Post => self.client.post(&req.url),
            };
            for (k, v) in &req.headers {
                rb = rb.header(k.as_str(), v.as_str());
            }
            if let Some(body) = req.body {
                rb = rb.body(body);
            }
            let resp = rb.send().await.map_err(map_reqwest)?;
            let status = resp.status().as_u16();
            let body = resp.bytes().await.map_err(|e| {
                if e.is_timeout() {
                    TransportError::Timeout
                } else {
                    TransportError::Body
                }
            })?;
            mmcl_net::note_network_success();
            Ok(HttpResponse {
                status,
                body: body.to_vec(),
            })
        }
        .boxed()
    }
}

fn map_reqwest(e: reqwest::Error) -> TransportError {
    if e.is_timeout() {
        TransportError::Timeout
    } else if e.is_connect() {
        TransportError::Connect
    } else {
        TransportError::Request
    }
}

/// Form-encodes pairs in order, spaces as `+` (Java `URLEncoder`).
pub fn form_body(pairs: &[(&str, &str)]) -> String {
    let mut s = url::form_urlencoded::Serializer::new(String::new());
    for (k, v) in pairs {
        s.append_pair(k, v);
    }
    s.finish()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn form_bodies_encode_like_java() {
        assert_eq!(
            form_body(&[
                ("client_id", "00000000402b5328"),
                ("redirect_uri", "https://login.live.com/oauth20_desktop.srf"),
                ("scope", "a b"),
            ]),
            "client_id=00000000402b5328&redirect_uri=https%3A%2F%2Flogin.live.com%2Foauth20_desktop.srf&scope=a+b"
        );
    }

    #[test]
    fn debug_hides_bodies_and_header_values() {
        let r = HttpRequest::post_form("https://x.test/", "refresh_token=secret".into())
            .header("Authorization", "Bearer secret");
        let s = format!("{r:?}");
        assert!(!s.contains("secret"), "{s}");
        assert_eq!(r.header_value("authorization"), Some("Bearer secret"));
    }

    #[test]
    fn the_real_transport_builds() {
        assert!(ReqwestTransport::new().is_ok());
    }
}
