// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Hand-rolled test doubles (the Java `AccountTestSupport` plus the manager's fakes).

use std::collections::VecDeque;
use std::sync::Arc;
use std::sync::atomic::{AtomicI64, AtomicUsize, Ordering};

use futures::FutureExt;
use futures::future::BoxFuture;
use mmcl_secure::{Cipher, CipherError};
use parking_lot::Mutex;

use crate::accounts::{DefaultAccountSetting, Renewal, RenewalError, Renewer};
use crate::http::{HttpRequest, HttpResponse, HttpTransport, Method, TransportError};
use crate::spawn::Spawn;
use crate::user::User;

/// Prefix the fake cipher adds, so tests can tell encrypted bytes from plaintext.
pub const FAKE_PREFIX: &[u8] = b"FAKE:";

/// Reversible, deterministic stand-in for the machine cipher.
pub struct FakeCipher;

impl Cipher for FakeCipher {
    fn encrypt(&self, plaintext: &[u8]) -> Result<Vec<u8>, CipherError> {
        let mut out = FAKE_PREFIX.to_vec();
        out.extend_from_slice(plaintext);
        Ok(out)
    }

    fn decrypt(&self, envelope: &[u8]) -> Result<Option<Vec<u8>>, CipherError> {
        if !envelope.starts_with(FAKE_PREFIX) {
            return Err(CipherError::Authentication);
        }
        Ok(Some(envelope[FAKE_PREFIX.len()..].to_vec()))
    }
}

/// A cipher bound to "another machine": it can't decrypt anything.
pub struct OtherMachine;

impl Cipher for OtherMachine {
    fn encrypt(&self, plaintext: &[u8]) -> Result<Vec<u8>, CipherError> {
        Ok(plaintext.to_vec())
    }

    fn decrypt(&self, _envelope: &[u8]) -> Result<Option<Vec<u8>>, CipherError> {
        Err(CipherError::Authentication)
    }
}

/// A 32-digit profile id from one digit.
pub fn uuid(d: char) -> String {
    d.to_string().repeat(32)
}

/// A user derived from the uuid's digit.
pub fn user(d: char, token: &str) -> User {
    User {
        uuid: uuid(d),
        name: format!("Player{d}"),
        access_token: Some(token.to_string()),
        user_type: Some("msa".into()),
        xuid: Some(format!("xuid{d}")),
        client_id: Some("client".into()),
    }
}

/// Opaque stand-in authentication-file bytes.
pub fn auth_file(label: &str) -> Vec<u8> {
    format!("auth:{label}").into_bytes()
}

/// An in-memory default-account setting.
#[derive(Default)]
pub struct InMemoryDefault(pub Mutex<String>);

impl DefaultAccountSetting for InMemoryDefault {
    fn get(&self) -> String {
        self.0.lock().clone()
    }
    fn set(&self, uuid: &str) {
        *self.0.lock() = uuid.to_string();
    }
}

/// A spawner the test drains by hand.
#[derive(Default)]
pub struct ManualSpawner(pub Mutex<VecDeque<BoxFuture<'static, ()>>>);

impl Spawn for ManualSpawner {
    fn spawn(&self, task: BoxFuture<'static, ()>) {
        self.0.lock().push_back(task);
    }
}

impl ManualSpawner {
    /// Runs every queued task (and any they queue) to completion.
    pub fn run_queued(&self) {
        loop {
            let next = self.0.lock().pop_front();
            match next {
                Some(t) => futures::executor::block_on(t),
                None => break,
            }
        }
    }
}

type Step = Box<dyn Fn(&[u8]) -> Result<Renewal, RenewalError> + Send + Sync>;

/// Renews by echoing the input's account unless a test scripts `next`.
#[derive(Default)]
pub struct ScriptedRenewer {
    pub calls: AtomicUsize,
    pub next: Mutex<Option<Step>>,
    pub last_input: Mutex<Option<Vec<u8>>>,
}

impl ScriptedRenewer {
    pub fn script(
        &self,
        f: impl Fn(&[u8]) -> Result<Renewal, RenewalError> + Send + Sync + 'static,
    ) {
        *self.next.lock() = Some(Box::new(f));
    }
}

impl Renewer for ScriptedRenewer {
    fn renew(&self, auth_file_gz: Vec<u8>) -> BoxFuture<'static, Result<Renewal, RenewalError>> {
        self.calls.fetch_add(1, Ordering::SeqCst);
        *self.last_input.lock() = Some(auth_file_gz.clone());
        let r = match &*self.next.lock() {
            Some(step) => step(&auth_file_gz),
            None => {
                // "auth:<digit>" renews to that account with a new token.
                let label = String::from_utf8_lossy(&auth_file_gz[5..]).to_string();
                let d = label.chars().next().unwrap_or('0');
                Ok(Renewal {
                    user: user(d, "renewed"),
                    auth_file_gz: auth_file_gz.clone(),
                })
            }
        };
        async move { r }.boxed()
    }
}

/// A settable clock.
#[derive(Clone)]
pub struct ManualClock(pub Arc<AtomicI64>);

impl ManualClock {
    pub fn new(t: i64) -> Self {
        ManualClock(Arc::new(AtomicI64::new(t)))
    }
    pub fn get(&self) -> i64 {
        self.0.load(Ordering::SeqCst)
    }
    pub fn advance(&self, ms: i64) {
        self.0.fetch_add(ms, Ordering::SeqCst);
    }
    pub fn as_fn(&self) -> Arc<dyn Fn() -> i64 + Send + Sync> {
        let c = self.clone();
        Arc::new(move || c.get())
    }
}

/// A transport that answers from a script of `(method, url prefix) -> (status, body)` and
/// records every request.
type Route = (
    Method,
    String,
    VecDeque<Result<HttpResponse, TransportError>>,
);

#[derive(Default)]
pub struct ScriptedTransport {
    pub routes: Mutex<Vec<Route>>,
    pub requests: Mutex<Vec<HttpRequest>>,
}

impl ScriptedTransport {
    pub fn on(&self, method: Method, url_prefix: &str, status: u16, body: &str) -> &Self {
        self.push(
            method,
            url_prefix,
            Ok(HttpResponse {
                status,
                body: body.as_bytes().to_vec(),
            }),
        )
    }

    pub fn push(
        &self,
        method: Method,
        url_prefix: &str,
        r: Result<HttpResponse, TransportError>,
    ) -> &Self {
        let mut routes = self.routes.lock();
        if let Some(route) = routes
            .iter_mut()
            .find(|(m, u, _)| *m == method && u == url_prefix)
        {
            route.2.push_back(r);
        } else {
            routes.push((method, url_prefix.to_string(), VecDeque::from([r])));
        }
        self
    }

    pub fn requests_to(&self, url_prefix: &str) -> Vec<HttpRequest> {
        self.requests
            .lock()
            .iter()
            .filter(|r| r.url.starts_with(url_prefix))
            .cloned()
            .collect()
    }
}

impl HttpTransport for ScriptedTransport {
    fn send(&self, req: HttpRequest) -> BoxFuture<'_, Result<HttpResponse, TransportError>> {
        self.requests.lock().push(req.clone());
        let mut routes = self.routes.lock();
        let answer = routes
            .iter_mut()
            .find(|(m, u, _)| *m == req.method && req.url.starts_with(u.as_str()))
            .map(|route| {
                // The last answer repeats once the script runs out.
                if route.2.len() > 1 {
                    route.2.pop_front().unwrap()
                } else {
                    route.2.front().cloned().unwrap()
                }
            })
            .unwrap_or(Err(TransportError::Connect));
        async move { answer }.boxed()
    }
}
