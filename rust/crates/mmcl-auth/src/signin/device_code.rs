// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The device code sign-in: `login.live.com/oauth20_connect.srf` hands out a short code the user
//! enters at microsoft.com/link on a phone or PC; the launcher polls the token endpoint until
//! they finish. Nothing is typed on the launcher itself, which is what handhelds such as the
//! Steam Deck need.
//!
//! The code, address and expiry reach the UI through the prompt callback, once per code.

use std::sync::Arc;
use std::time::{Duration, SystemTime};

use futures::FutureExt;
use futures::future::BoxFuture;
use mmcl_i18n::tf;
use mmcl_net::CancelToken;
use tokio::time::Instant;

use super::{SignInError, SignInFlow};
use crate::msa::{
    DevicePoll, MsaClient, MsaError, MsaTokens, OFFICIAL_CLIENT_ID, SCOPE_MBI_SSL, SignInKind,
};

/// Consecutive transport failures tolerated while polling.
const MAX_POLL_TRANSPORT_FAILURES: u32 = 3;
/// Added to the interval on `slow_down`.
const SLOW_DOWN_STEP: Duration = Duration::from_secs(5);

/// What the UI shows while waiting.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct DeviceCodePrompt {
    /// The code to type, e.g. `ABCD1234`.
    pub user_code: String,
    /// Where to type it (`https://www.microsoft.com/link`).
    pub verification_uri: String,
    /// How long the code is valid from `issued_at`.
    pub expires_in: Duration,
    /// When the code was issued.
    pub issued_at: SystemTime,
}

impl DeviceCodePrompt {
    /// When the code stops working.
    pub fn expires_at(&self) -> SystemTime {
        self.issued_at + self.expires_in
    }
}

/// Receives the prompt.
pub type PromptCallback = Arc<dyn Fn(&DeviceCodePrompt) + Send + Sync>;

/// The device code flow.
#[derive(Clone)]
pub struct DeviceCodeFlow {
    msa: Arc<MsaClient>,
    client_id: String,
    scope: String,
    on_prompt: PromptCallback,
    min_interval: Duration,
}

impl std::fmt::Debug for DeviceCodeFlow {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("DeviceCodeFlow")
            .field("client_id", &self.client_id)
            .field("scope", &self.scope)
            .finish_non_exhaustive()
    }
}

impl DeviceCodeFlow {
    /// A flow with the official client ID; `on_prompt` gets the code to show.
    pub fn new(msa: Arc<MsaClient>, on_prompt: PromptCallback) -> DeviceCodeFlow {
        DeviceCodeFlow {
            msa,
            client_id: OFFICIAL_CLIENT_ID.to_string(),
            scope: SCOPE_MBI_SSL.to_string(),
            on_prompt,
            min_interval: Duration::from_secs(1),
        }
    }

    /// Uses the configured client ID (`auth.clientId`).
    pub fn with_client_id(mut self, client_id: impl Into<String>) -> Self {
        self.client_id = client_id.into();
        self
    }

    /// The shortest poll interval (tests use zero).
    pub fn with_min_interval(mut self, d: Duration) -> Self {
        self.min_interval = d;
        self
    }

    async fn poll_until_done(&self, cancel: &CancelToken) -> Result<MsaTokens, SignInError> {
        if cancel.is_cancelled() {
            return Err(SignInError::Cancelled);
        }
        // Every request races the cancel token, so Cancel never waits out a slow request.
        let dc = tokio::select! {
            r = self.msa.request_device_code(&self.client_id, &self.scope) => r?,
            _ = cancel.cancelled() => return Err(SignInError::Cancelled),
        };
        let expires_in = Duration::from_secs(dc.expires_in.max(1));
        let deadline = Instant::now() + expires_in;
        let prompt = DeviceCodePrompt {
            user_code: dc.user_code.clone(),
            verification_uri: dc.verification_uri.clone(),
            expires_in,
            issued_at: SystemTime::now(),
        };
        mmcl_log::std(&tf!(
            "log.authManager.deviceCodeRequested",
            prompt.verification_uri.as_str(),
            dc.expires_in.to_string()
        ));
        (self.on_prompt)(&prompt);

        let mut interval = Duration::from_secs(dc.interval).max(self.min_interval);
        let mut transport_failures = 0u32;
        loop {
            let now = Instant::now();
            if now >= deadline {
                return Err(SignInError::DeviceCodeExpired);
            }
            let nap = interval.min(deadline - now);
            if cancel.sleep(nap).await {
                return Err(SignInError::Cancelled);
            }
            let polled = tokio::select! {
                r = self.msa.poll_device_code(&self.client_id, &dc.device_code) => r,
                _ = cancel.cancelled() => return Err(SignInError::Cancelled),
            };
            match polled {
                Ok(DevicePoll::Pending) => transport_failures = 0,
                Ok(DevicePoll::SlowDown) => {
                    transport_failures = 0;
                    interval += SLOW_DOWN_STEP;
                }
                Ok(DevicePoll::Expired) => return Err(SignInError::DeviceCodeExpired),
                Ok(DevicePoll::Declined) => return Err(SignInError::DeviceCodeDeclined),
                Ok(DevicePoll::Done(t)) => {
                    return Ok(MsaTokens {
                        access_token: t.access_token,
                        refresh_token: t.refresh_token,
                        client_id: self.client_id.clone(),
                        scope: self.scope.clone(),
                        kind: SignInKind::DeviceCode,
                        wam_account_id: None,
                    });
                }
                Err(MsaError::Transport(e)) if transport_failures < MAX_POLL_TRANSPORT_FAILURES => {
                    transport_failures += 1;
                    mmcl_log::debug(&tf!("log.authManager.deviceCodePollFailed", e.kind_name()));
                }
                Err(e) => return Err(SignInError::Msa(e)),
            }
        }
    }
}

impl SignInFlow for DeviceCodeFlow {
    fn kind(&self) -> SignInKind {
        SignInKind::DeviceCode
    }

    fn run<'a>(&'a self, cancel: &'a CancelToken) -> BoxFuture<'a, Result<MsaTokens, SignInError>> {
        self.poll_until_done(cancel).boxed()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::http::Method;
    use crate::msa::{DEVICE_CODE_URL, TOKEN_URL};
    use crate::testutil::ScriptedTransport;

    fn transport(interval: u64, expires: u64) -> Arc<ScriptedTransport> {
        let t = Arc::new(ScriptedTransport::default());
        t.on(
            Method::Post,
            DEVICE_CODE_URL,
            200,
            &format!(
                r#"{{"user_code":"WXYZ9876","device_code":"DEV","verification_uri":"https://www.microsoft.com/link","expires_in":{expires},"interval":{interval}}}"#
            ),
        );
        t
    }

    fn flow(
        t: &Arc<ScriptedTransport>,
        seen: Arc<parking_lot::Mutex<Vec<DeviceCodePrompt>>>,
    ) -> DeviceCodeFlow {
        DeviceCodeFlow::new(
            Arc::new(MsaClient::new(t.clone())),
            Arc::new(move |p: &DeviceCodePrompt| seen.lock().push(p.clone())),
        )
        .with_min_interval(Duration::ZERO)
    }

    #[tokio::test]
    async fn the_prompt_is_shown_then_polling_ends_with_tokens() {
        let t = transport(0, 900);
        t.on(
            Method::Post,
            TOKEN_URL,
            400,
            r#"{"error":"authorization_pending"}"#,
        );
        t.on(
            Method::Post,
            TOKEN_URL,
            400,
            r#"{"error":"authorization_pending"}"#,
        );
        t.on(
            Method::Post,
            TOKEN_URL,
            200,
            r#"{"access_token":"AT","refresh_token":"RT","expires_in":3600}"#,
        );
        let seen = Arc::new(parking_lot::Mutex::new(Vec::new()));
        let tokens = flow(&t, seen.clone())
            .run(&CancelToken::new())
            .await
            .unwrap();
        assert_eq!(tokens.access_token, "AT");
        assert_eq!(tokens.refresh_token.as_deref(), Some("RT"));
        assert_eq!(tokens.kind, SignInKind::DeviceCode);
        assert_eq!(tokens.scope, SCOPE_MBI_SSL);
        assert_eq!(tokens.rps_ticket(), "t=AT");
        let prompts = seen.lock();
        assert_eq!(prompts.len(), 1);
        assert_eq!(prompts[0].user_code, "WXYZ9876");
        assert_eq!(
            prompts[0].verification_uri,
            "https://www.microsoft.com/link"
        );
        assert_eq!(prompts[0].expires_in, Duration::from_secs(900));
        assert_eq!(t.requests_to(TOKEN_URL).len(), 3);
    }

    #[tokio::test]
    async fn an_expired_or_declined_code_ends_the_flow() {
        let t = transport(0, 900);
        t.on(Method::Post, TOKEN_URL, 400, r#"{"error":"expired_token"}"#);
        let seen = Arc::new(parking_lot::Mutex::new(Vec::new()));
        assert_eq!(
            flow(&t, seen.clone()).run(&CancelToken::new()).await,
            Err(SignInError::DeviceCodeExpired)
        );
        let t = transport(0, 900);
        t.on(
            Method::Post,
            TOKEN_URL,
            400,
            r#"{"error":"authorization_declined"}"#,
        );
        assert_eq!(
            flow(&t, seen).run(&CancelToken::new()).await,
            Err(SignInError::DeviceCodeDeclined)
        );
    }

    #[tokio::test]
    async fn the_deadline_expires_the_code() {
        let t = transport(0, 1);
        t.on(
            Method::Post,
            TOKEN_URL,
            400,
            r#"{"error":"authorization_pending"}"#,
        );
        let seen = Arc::new(parking_lot::Mutex::new(Vec::new()));
        let f = flow(&t, seen).with_min_interval(Duration::from_millis(200));
        assert_eq!(
            f.run(&CancelToken::new()).await,
            Err(SignInError::DeviceCodeExpired)
        );
    }

    #[tokio::test]
    async fn cancelling_stops_polling() {
        let t = transport(30, 900);
        t.on(
            Method::Post,
            TOKEN_URL,
            400,
            r#"{"error":"authorization_pending"}"#,
        );
        let seen = Arc::new(parking_lot::Mutex::new(Vec::new()));
        let f = flow(&t, seen.clone());
        let cancel = CancelToken::new();
        let c2 = cancel.clone();
        tokio::spawn(async move {
            tokio::time::sleep(Duration::from_millis(30)).await;
            c2.cancel();
        });
        let started = std::time::Instant::now();
        assert_eq!(f.run(&cancel).await, Err(SignInError::Cancelled));
        assert!(started.elapsed() < Duration::from_secs(10));
        assert_eq!(seen.lock().len(), 1);
    }

    /// A transport whose token endpoint never answers.
    struct Hanging(Arc<ScriptedTransport>);

    impl crate::http::HttpTransport for Hanging {
        fn send(
            &self,
            req: crate::http::HttpRequest,
        ) -> BoxFuture<'_, Result<crate::http::HttpResponse, crate::http::TransportError>> {
            if req.url.starts_with(TOKEN_URL) {
                futures::future::pending().boxed()
            } else {
                self.0.send(req)
            }
        }
    }

    #[tokio::test]
    async fn cancelling_does_not_wait_for_a_request_in_flight() {
        let t = transport(0, 900);
        let flow = DeviceCodeFlow::new(
            Arc::new(MsaClient::new(Arc::new(Hanging(t)))),
            Arc::new(|_: &DeviceCodePrompt| {}),
        )
        .with_min_interval(Duration::ZERO);
        let cancel = CancelToken::new();
        let c2 = cancel.clone();
        tokio::spawn(async move {
            tokio::time::sleep(Duration::from_millis(30)).await;
            c2.cancel();
        });
        let r = tokio::time::timeout(Duration::from_secs(5), flow.run(&cancel)).await;
        assert_eq!(r.expect("cancel returned"), Err(SignInError::Cancelled));
    }

    #[tokio::test]
    async fn a_few_network_blips_are_tolerated_while_polling() {
        let t = transport(0, 900);
        t.push(
            Method::Post,
            TOKEN_URL,
            Err(crate::http::TransportError::Timeout),
        );
        t.on(
            Method::Post,
            TOKEN_URL,
            200,
            r#"{"access_token":"AT","refresh_token":"RT"}"#,
        );
        let seen = Arc::new(parking_lot::Mutex::new(Vec::new()));
        assert!(flow(&t, seen).run(&CancelToken::new()).await.is_ok());
    }

    #[tokio::test]
    async fn a_refused_device_code_request_is_an_oauth_error() {
        let t = Arc::new(ScriptedTransport::default());
        t.on(
            Method::Post,
            DEVICE_CODE_URL,
            400,
            r#"{"error":"invalid_client"}"#,
        );
        let seen = Arc::new(parking_lot::Mutex::new(Vec::new()));
        let e = flow(&t, seen.clone())
            .run(&CancelToken::new())
            .await
            .unwrap_err();
        assert!(matches!(e, SignInError::Msa(MsaError::OAuth { .. })));
        assert!(seen.lock().is_empty());
    }
}
