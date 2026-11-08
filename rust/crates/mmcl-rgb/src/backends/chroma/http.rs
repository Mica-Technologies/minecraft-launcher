// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! A minimal blocking HTTP/1.1 client for Razer's localhost REST service (plain HTTP, JSON
//! bodies, one request per connection). Keeps tokio and TLS off the RGB threads.

use std::io::{BufRead, BufReader, Read, Write};
use std::net::{TcpStream, ToSocketAddrs};
use std::time::Duration;

use crate::error::{RgbError, RgbResult};

/// Generic User-Agent; never carries anything about the user.
pub const USER_AGENT: &str = "MicaMinecraftLauncher-RGB";

/// A parsed `http://host:port/path` URL.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct HttpUrl {
    pub host: String,
    pub port: u16,
    pub path: String,
}

impl HttpUrl {
    pub fn parse(url: &str) -> RgbResult<HttpUrl> {
        let rest = url
            .strip_prefix("http://")
            .ok_or_else(|| RgbError::protocol(format!("not an http:// URL: {url}")))?;
        let (authority, path) = match rest.find('/') {
            Some(i) => (&rest[..i], &rest[i..]),
            None => (rest, "/"),
        };
        let (host, port) = match authority.rsplit_once(':') {
            Some((h, p)) => (
                h,
                p.parse::<u16>()
                    .map_err(|_| RgbError::protocol(format!("bad port in {url}")))?,
            ),
            None => (authority, 80),
        };
        if host.is_empty() {
            return Err(RgbError::protocol(format!("no host in {url}")));
        }
        Ok(HttpUrl {
            host: host.to_string(),
            port,
            path: path.to_string(),
        })
    }
}

/// An HTTP response: status and body text.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct HttpResponse {
    pub status: u16,
    pub body: String,
}

/// The request bytes for `method url` with an optional JSON body.
pub fn build_request(method: &str, url: &HttpUrl, body: Option<&str>) -> Vec<u8> {
    let mut req = format!(
        "{method} {} HTTP/1.1\r\nHost: {}:{}\r\nUser-Agent: {USER_AGENT}\r\nAccept: application/json\r\nConnection: close\r\n",
        url.path, url.host, url.port
    );
    let body = body.unwrap_or("");
    if !body.is_empty() || method == "POST" || method == "PUT" {
        req.push_str("Content-Type: application/json\r\n");
        req.push_str(&format!("Content-Length: {}\r\n", body.len()));
    }
    req.push_str("\r\n");
    let mut bytes = req.into_bytes();
    bytes.extend_from_slice(body.as_bytes());
    bytes
}

/// Parses a full response (status line, headers, then a `Content-Length`, chunked, or
/// read-to-end body).
pub fn read_response(r: impl Read) -> RgbResult<HttpResponse> {
    let mut r = BufReader::new(r);
    let mut line = String::new();
    r.read_line(&mut line)?;
    let status = line
        .split_whitespace()
        .nth(1)
        .and_then(|s| s.parse::<u16>().ok())
        .ok_or_else(|| RgbError::protocol(format!("bad HTTP status line: {}", line.trim())))?;
    let mut content_length = None;
    let mut chunked = false;
    loop {
        line.clear();
        if r.read_line(&mut line)? == 0 {
            break;
        }
        let l = line.trim_end();
        if l.is_empty() {
            break;
        }
        if let Some((k, v)) = l.split_once(':') {
            let (k, v) = (k.trim().to_ascii_lowercase(), v.trim());
            if k == "content-length" {
                content_length = v.parse::<usize>().ok();
            } else if k == "transfer-encoding" && v.to_ascii_lowercase().contains("chunked") {
                chunked = true;
            }
        }
    }
    let mut body = Vec::new();
    if chunked {
        loop {
            line.clear();
            r.read_line(&mut line)?;
            let size_hex = line.trim().split(';').next().unwrap_or("");
            let size = usize::from_str_radix(size_hex, 16)
                .map_err(|_| RgbError::protocol("bad chunk size"))?;
            if size == 0 {
                break;
            }
            let start = body.len();
            body.resize(start + size, 0);
            r.read_exact(&mut body[start..])?;
            line.clear();
            r.read_line(&mut line)?;
        }
    } else if let Some(n) = content_length {
        body.resize(n, 0);
        r.read_exact(&mut body)?;
    } else {
        r.read_to_end(&mut body)?;
    }
    Ok(HttpResponse {
        status,
        body: String::from_utf8_lossy(&body).into_owned(),
    })
}

/// Sends one request with `timeout` for connect, write and each read.
pub fn request(
    method: &str,
    url: &str,
    body: Option<&str>,
    timeout: Duration,
) -> RgbResult<HttpResponse> {
    let url = HttpUrl::parse(url)?;
    let addrs: Vec<_> = (url.host.as_str(), url.port).to_socket_addrs()?.collect();
    let mut last = None;
    let mut stream = None;
    for a in addrs {
        match TcpStream::connect_timeout(&a, timeout) {
            Ok(s) => {
                stream = Some(s);
                break;
            }
            Err(e) => last = Some(e),
        }
    }
    let mut stream = match stream {
        Some(s) => s,
        None => {
            return Err(last.map(RgbError::Io).unwrap_or_else(|| {
                RgbError::Unavailable(format!("{}:{} did not resolve", url.host, url.port))
            }));
        }
    };
    stream.set_read_timeout(Some(timeout))?;
    stream.set_write_timeout(Some(timeout))?;
    stream.write_all(&build_request(method, &url, body))?;
    stream.flush()?;
    read_response(stream)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn url_parsing() {
        assert_eq!(
            HttpUrl::parse("http://localhost:54235/razer/chromasdk").unwrap(),
            HttpUrl {
                host: "localhost".into(),
                port: 54235,
                path: "/razer/chromasdk".into()
            }
        );
        assert_eq!(HttpUrl::parse("http://127.0.0.1").unwrap().path, "/");
        assert!(HttpUrl::parse("https://x").is_err());
        assert!(HttpUrl::parse("http://:80/").is_err());
    }

    #[test]
    fn request_bytes() {
        let u = HttpUrl::parse("http://localhost:1/a").unwrap();
        let r = String::from_utf8(build_request("PUT", &u, Some("{}"))).unwrap();
        assert!(r.starts_with("PUT /a HTTP/1.1\r\nHost: localhost:1\r\n"));
        assert!(r.contains("Content-Length: 2\r\n"));
        assert!(r.ends_with("\r\n\r\n{}"));
        let d = String::from_utf8(build_request("DELETE", &u, None)).unwrap();
        assert!(!d.contains("Content-Length"));
    }

    #[test]
    fn response_forms() {
        let r = read_response(
            &b"HTTP/1.1 200 OK\r\nContent-Length: 12\r\n\r\n{\"result\":0}trailing"[..],
        )
        .unwrap();
        assert_eq!((r.status, r.body.as_str()), (200, "{\"result\":0}"));
        let c = read_response(&b"HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n4\r\n{\"a\"\r\n3\r\n:1}\r\n0\r\n\r\n"[..]).unwrap();
        assert_eq!(c.body, "{\"a\":1}");
        let e = read_response(&b"HTTP/1.0 500 Oops\r\n\r\nboom"[..]).unwrap();
        assert_eq!((e.status, e.body.as_str()), (500, "boom"));
        assert!(read_response(&b"garbage\r\n\r\n"[..]).is_err());
    }
}
