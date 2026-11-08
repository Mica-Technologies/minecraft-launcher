// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The protocol client.

use std::fmt;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};

use base64::Engine;
use base64::engine::{DecodePaddingMode, GeneralPurpose, GeneralPurposeConfig};
use serde_json::{Value, json};
use uuid::Uuid;

use super::{
    DEVICE_CODE_GRANT, DEVICE_CODE_URL, MC_ENTITLEMENTS_URL, MC_LOGIN_URL, MC_PROFILE_URL,
    MsaError, MsaTokens, OFFICIAL_CLIENT_ID, REDIRECT_URL, SCOPE_XBOX_SIGNIN, SignInKind,
    TOKEN_URL, XBL_URL, XSTS_URL, encode_client_id, rps_ticket,
};
use crate::authfile::AuthFile;
use crate::http::{HttpRequest, HttpResponse, HttpTransport, ReqwestTransport, form_body};
use crate::user::{USER_TYPE_MSA, User};

/// A token endpoint success.
#[derive(Clone, PartialEq, Eq)]
pub struct OAuthTokens {
    pub access_token: String,
    pub refresh_token: Option<String>,
    pub expires_in: Option<i64>,
}

impl fmt::Debug for OAuthTokens {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("OAuthTokens")
            .field("expires_in", &self.expires_in)
            .finish_non_exhaustive()
    }
}

/// A device authorization answer.
#[derive(Clone, PartialEq, Eq)]
pub struct DeviceCode {
    /// The code the user types.
    pub user_code: String,
    /// The secret code the launcher polls with.
    pub device_code: String,
    /// Where the user types it (`https://www.microsoft.com/link`).
    pub verification_uri: String,
    /// Seconds until the code expires.
    pub expires_in: u64,
    /// Seconds between polls.
    pub interval: u64,
}

impl fmt::Debug for DeviceCode {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("DeviceCode")
            .field("user_code", &self.user_code)
            .field("verification_uri", &self.verification_uri)
            .field("expires_in", &self.expires_in)
            .field("interval", &self.interval)
            .finish_non_exhaustive()
    }
}

/// One device code poll.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum DevicePoll {
    /// The user has not finished yet.
    Pending,
    /// Poll less often (add 5 s to the interval).
    SlowDown,
    /// Signed in.
    Done(OAuthTokens),
    /// The code expired.
    Expired,
    /// The user declined.
    Declined,
}

/// A failed renewal; `rotated` holds the new refresh token when the OAuth step succeeded before
/// a later step failed, so it can be kept.
#[derive(Clone, Debug)]
pub struct RenewFailure {
    pub error: MsaError,
    pub rotated: Option<AuthFile>,
}

/// The Xbox / XSTS token and user hash.
struct XboxToken {
    token: String,
    uhs: String,
}

/// The pre-RFC name of the device code grant.
const DEVICE_CODE_GRANT_SHORT: &str = "device_code";

/// The protocol client.
#[derive(Clone)]
pub struct MsaClient {
    http: Arc<dyn HttpTransport>,
    /// Set once the token endpoint refused the RFC grant name (shared by clones).
    short_device_grant: Arc<AtomicBool>,
}

impl fmt::Debug for MsaClient {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("MsaClient").finish_non_exhaustive()
    }
}

/// Base64url for JWT payloads: padding optional.
const JWT_BASE64: GeneralPurpose = GeneralPurpose::new(
    &base64::alphabet::URL_SAFE,
    GeneralPurposeConfig::new()
        .with_decode_padding_mode(DecodePaddingMode::Indifferent)
        .with_decode_allow_trailing_bits(true),
);

impl MsaClient {
    /// A client over `http`.
    pub fn new(http: Arc<dyn HttpTransport>) -> MsaClient {
        MsaClient {
            http,
            short_device_grant: Arc::new(AtomicBool::new(false)),
        }
    }

    /// A client over the production reqwest transport.
    pub fn production() -> Result<MsaClient, MsaError> {
        Ok(MsaClient::new(Arc::new(ReqwestTransport::new()?)))
    }

    /// The transport.
    pub fn transport(&self) -> &Arc<dyn HttpTransport> {
        &self.http
    }

    async fn send(&self, req: HttpRequest) -> Result<HttpResponse, MsaError> {
        Ok(self.http.send(req).await?)
    }

    // ------------------------------------------------------------------ OAuth

    /// Exchanges an authorization code (step 1a).
    pub async fn exchange_code(
        &self,
        client_id: &str,
        code: &str,
        redirect_uri: &str,
    ) -> Result<OAuthTokens, MsaError> {
        let body = form_body(&[
            ("client_id", client_id),
            ("code", code),
            ("grant_type", "authorization_code"),
            ("redirect_uri", redirect_uri),
        ]);
        let resp = self.send(HttpRequest::post_form(TOKEN_URL, body)).await?;
        parse_token_response(&resp)
    }

    /// Redeems a refresh token (step 1b). Files from the web view (the Java format) refresh as
    /// the Java library did: no scope, the desktop redirect. Other files name their scope.
    pub async fn refresh_token(&self, file: &AuthFile) -> Result<OAuthTokens, MsaError> {
        let client_id = file.msa_client_id();
        let scope = file.scope();
        let body = if scope == SCOPE_XBOX_SIGNIN {
            form_body(&[
                ("client_id", client_id),
                ("refresh_token", &file.refresh_token),
                ("grant_type", "refresh_token"),
                ("redirect_uri", REDIRECT_URL),
            ])
        } else {
            form_body(&[
                ("client_id", client_id),
                ("refresh_token", &file.refresh_token),
                ("grant_type", "refresh_token"),
                ("scope", scope),
            ])
        };
        let resp = self.send(HttpRequest::post_form(TOKEN_URL, body)).await?;
        parse_token_response(&resp)
    }

    /// Starts a device code sign-in.
    pub async fn request_device_code(
        &self,
        client_id: &str,
        scope: &str,
    ) -> Result<DeviceCode, MsaError> {
        let body = form_body(&[
            ("client_id", client_id),
            ("scope", scope),
            ("response_type", "device_code"),
        ]);
        let resp = self
            .send(HttpRequest::post_form(DEVICE_CODE_URL, body))
            .await?;
        let v = parse_json(&resp, "device code")?;
        if let Some(err) = oauth_error(&v) {
            return Err(err);
        }
        let s = |k: &str| v.get(k).and_then(Value::as_str).map(str::to_string);
        let n = |k: &str| {
            v.get(k).and_then(|x| {
                x.as_u64()
                    .or_else(|| x.as_str().and_then(|s| s.parse().ok()))
            })
        };
        Ok(DeviceCode {
            user_code: s("user_code").ok_or(MsaError::Parse("device code"))?,
            device_code: s("device_code").ok_or(MsaError::Parse("device code"))?,
            verification_uri: s("verification_uri")
                .unwrap_or_else(|| "https://www.microsoft.com/link".to_string()),
            expires_in: n("expires_in").unwrap_or(900),
            interval: n("interval").unwrap_or(5),
        })
    }

    /// Polls a device code once. The grant is sent in its RFC 8628 form; should the endpoint
    /// answer `unsupported_grant_type`, the poll is repeated with the short `device_code` form
    /// older Microsoft endpoints know, and that form is kept for later polls.
    pub async fn poll_device_code(
        &self,
        client_id: &str,
        device_code: &str,
    ) -> Result<DevicePoll, MsaError> {
        let mut resp;
        loop {
            let short = self.short_device_grant.load(Ordering::Relaxed);
            let grant = if short {
                DEVICE_CODE_GRANT_SHORT
            } else {
                DEVICE_CODE_GRANT
            };
            let body = form_body(&[
                ("grant_type", grant),
                ("client_id", client_id),
                ("device_code", device_code),
            ]);
            resp = self.send(HttpRequest::post_form(TOKEN_URL, body)).await?;
            let unsupported = !short
                && serde_json::from_slice::<Value>(&resp.body)
                    .ok()
                    .and_then(|v| v.get("error").and_then(Value::as_str).map(str::to_string))
                    .is_some_and(|e| e == "unsupported_grant_type");
            if !unsupported {
                break;
            }
            self.short_device_grant.store(true, Ordering::Relaxed);
        }
        match parse_token_response(&resp) {
            Ok(t) => Ok(DevicePoll::Done(t)),
            Err(MsaError::OAuth { error, description }) => Ok(match error.as_str() {
                "authorization_pending" => DevicePoll::Pending,
                "slow_down" => DevicePoll::SlowDown,
                "expired_token" | "bad_verification_code" | "invalid_grant" => DevicePoll::Expired,
                "authorization_declined" | "access_denied" => DevicePoll::Declined,
                _ => return Err(MsaError::OAuth { error, description }),
            }),
            Err(e) => Err(e),
        }
    }

    // ------------------------------------------------------------------ Xbox chain

    async fn xbl(&self, rps_ticket: &str) -> Result<XboxToken, MsaError> {
        let body = json!({
            "Properties": {
                "AuthMethod": "RPS",
                "SiteName": "user.auth.xboxlive.com",
                "RpsTicket": rps_ticket,
            },
            "RelyingParty": "http://auth.xboxlive.com",
            "TokenType": "JWT",
        });
        let resp = self
            .send(HttpRequest::post_json(XBL_URL, &body).header("x-xbl-contract-version", "1"))
            .await?;
        if resp.status >= 400 {
            return Err(MsaError::XblHttp(resp.status));
        }
        let v = parse_json(&resp, "xbl")?;
        let token = v
            .get("Token")
            .and_then(Value::as_str)
            .ok_or(MsaError::Parse("xbl"))?;
        let uhs = first_uhs(&v).ok_or(MsaError::XblNoXui)?;
        Ok(XboxToken {
            token: token.to_string(),
            uhs,
        })
    }

    async fn xsts(&self, xbl_token: &str) -> Result<XboxToken, MsaError> {
        let body = json!({
            "Properties": { "SandboxId": "RETAIL", "UserTokens": [xbl_token] },
            "RelyingParty": "rp://api.minecraftservices.com/",
            "TokenType": "JWT",
        });
        let resp = self
            .send(HttpRequest::post_json(XSTS_URL, &body).header("x-xbl-contract-version", "1"))
            .await?;
        // The status is not checked: an error body carries XErr.
        let v = match parse_json(&resp, "xsts") {
            Ok(v) => v,
            Err(_) if resp.status >= 400 => {
                return Err(MsaError::Xsts {
                    xerr: 0,
                    message: None,
                });
            }
            Err(e) => return Err(e),
        };
        if let Some(x) = v.get("XErr") {
            let xerr = x
                .as_i64()
                .or_else(|| x.as_u64().map(|u| u as i64))
                .or_else(|| x.as_str().and_then(|s| s.parse().ok()))
                .unwrap_or(0);
            let message = v.get("Message").and_then(Value::as_str).map(str::to_string);
            return Err(MsaError::Xsts { xerr, message });
        }
        let token = v
            .get("Token")
            .and_then(Value::as_str)
            .ok_or(MsaError::Parse("xsts"))?;
        let uhs = first_uhs(&v).ok_or(MsaError::XstsNoXui)?;
        Ok(XboxToken {
            token: token.to_string(),
            uhs,
        })
    }

    async fn launcher_login(&self, xsts: &XboxToken) -> Result<String, MsaError> {
        let body = json!({
            "xtoken": format!("XBL3.0 x={};{}", xsts.uhs, xsts.token),
            "platform": "PC_LAUNCHER",
        });
        let resp = self
            .send(HttpRequest::post_json(MC_LOGIN_URL, &body))
            .await?;
        if resp.status >= 400 {
            return Err(MsaError::McLoginHttp(resp.status));
        }
        let v = parse_json(&resp, "minecraft login")?;
        v.get("access_token")
            .and_then(Value::as_str)
            .map(str::to_string)
            .ok_or(MsaError::Parse("minecraft login"))
    }

    async fn entitlements(&self, mc_token: &str, request_id: &Uuid) -> Result<(), MsaError> {
        let url = format!("{MC_ENTITLEMENTS_URL}?requestId={request_id}");
        let resp = self
            .send(HttpRequest::get(url).header("Authorization", format!("Bearer {mc_token}")))
            .await?;
        if resp.status >= 400 {
            return Err(MsaError::EntitlementsHttp(resp.status));
        }
        let v = parse_json(&resp, "entitlements")?;
        let owned = v
            .get("items")
            .and_then(Value::as_array)
            .is_some_and(|a| !a.is_empty());
        if owned {
            Ok(())
        } else {
            Err(MsaError::NotOwned)
        }
    }

    async fn profile(&self, mc_token: &str) -> Result<(String, String), MsaError> {
        let resp = self
            .send(
                HttpRequest::get(MC_PROFILE_URL)
                    .header("Authorization", format!("Bearer {mc_token}")),
            )
            .await?;
        if resp.status >= 400 {
            return Err(MsaError::ProfileHttp(resp.status));
        }
        let v = parse_json(&resp, "profile")?;
        let id = v.get("id").and_then(Value::as_str);
        let name = v.get("name").and_then(Value::as_str);
        match (id, name) {
            (Some(id), Some(name)) => Ok((id.to_string(), name.to_string())),
            _ => Err(MsaError::Parse("profile")),
        }
    }

    /// Steps 2-7: Microsoft access token -> Minecraft user.
    pub async fn minecraft_user(
        &self,
        rps_ticket: &str,
        launcher_client_id: &Uuid,
    ) -> Result<User, MsaError> {
        let xbl = self.xbl(rps_ticket).await?;
        let xsts = self.xsts(&xbl.token).await?;
        let mc_token = self.launcher_login(&xsts).await?;
        self.entitlements(&mc_token, launcher_client_id).await?;
        let (id, name) = self.profile(&mc_token).await?;
        let xuid = xuid_from_jwt(&mc_token).ok_or(MsaError::BadAccessToken)?;
        Ok(User {
            uuid: id,
            name,
            access_token: Some(mc_token),
            user_type: Some(USER_TYPE_MSA.to_string()),
            xuid: Some(xuid),
            client_id: Some(encode_client_id(launcher_client_id)),
        })
    }

    /// An interactive sign-in from any flow's tokens: a new random launcher client id, the
    /// shared chain, and the authentication file to store.
    pub async fn sign_in(&self, tokens: &MsaTokens) -> Result<(User, AuthFile), MsaError> {
        if tokens.kind != SignInKind::Wam && tokens.refresh_token.is_none() {
            // Without a refresh token the account could never renew.
            return Err(MsaError::Parse("token"));
        }
        let launcher_client_id = Uuid::new_v4();
        let user = self
            .minecraft_user(&tokens.rps_ticket(), &launcher_client_id)
            .await?;
        Ok((user, tokens.to_auth_file(launcher_client_id)))
    }

    /// The web view sign-in with an authorization code and the official client ID, in one
    /// token call (the Java library made two; the result is the same).
    pub async fn sign_in_with_code(&self, code: &str) -> Result<(User, AuthFile), MsaError> {
        let t = self
            .exchange_code(OFFICIAL_CLIENT_ID, code, REDIRECT_URL)
            .await?;
        let tokens = MsaTokens {
            access_token: t.access_token,
            refresh_token: t.refresh_token,
            client_id: OFFICIAL_CLIENT_ID.to_string(),
            scope: SCOPE_XBOX_SIGNIN.to_string(),
            kind: SignInKind::WebView,
            wam_account_id: None,
        };
        self.sign_in(&tokens).await
    }

    /// A renewal: refresh grant, then the chain. Keeps the launcher client id and extras. When a
    /// step after the refresh grant fails, the failure carries the rotated file.
    pub async fn renew(&self, file: &AuthFile) -> Result<(User, AuthFile), RenewFailure> {
        let t = self
            .refresh_token(file)
            .await
            .map_err(|error| RenewFailure {
                error,
                rotated: None,
            })?;
        let rotated = match &t.refresh_token {
            Some(rt) => file.with_refresh_token(rt.clone()),
            None => file.clone(),
        };
        let ticket = rps_ticket(&t.access_token, file.scope());
        match self.minecraft_user(&ticket, &file.client_id).await {
            Ok(user) => Ok((user, rotated)),
            Err(error) => Err(RenewFailure {
                error,
                rotated: (rotated.refresh_token != file.refresh_token).then_some(rotated),
            }),
        }
    }

    /// The chain for an access token obtained elsewhere (a WAM silent renewal), keeping `file`.
    pub async fn renew_with_access_token(
        &self,
        file: &AuthFile,
        access_token: &str,
    ) -> Result<(User, AuthFile), MsaError> {
        let ticket = rps_ticket(access_token, file.scope());
        let user = self.minecraft_user(&ticket, &file.client_id).await?;
        Ok((user, file.clone()))
    }
}

fn parse_json(resp: &HttpResponse, step: &'static str) -> Result<Value, MsaError> {
    serde_json::from_slice(&resp.body).map_err(|_| MsaError::Parse(step))
}

fn oauth_error(v: &Value) -> Option<MsaError> {
    let error = v.get("error")?;
    Some(MsaError::OAuth {
        error: error.as_str().unwrap_or("unknown").to_string(),
        description: v
            .get("error_description")
            .and_then(Value::as_str)
            .map(str::to_string),
    })
}

fn parse_token_response(resp: &HttpResponse) -> Result<OAuthTokens, MsaError> {
    let v = parse_json(resp, "token")?;
    if let Some(e) = oauth_error(&v) {
        return Err(e);
    }
    let access_token = v
        .get("access_token")
        .and_then(Value::as_str)
        .filter(|s| !s.is_empty())
        .ok_or(MsaError::Parse("token"))?;
    Ok(OAuthTokens {
        access_token: access_token.to_string(),
        refresh_token: v
            .get("refresh_token")
            .and_then(Value::as_str)
            .filter(|s| !s.is_empty())
            .map(str::to_string),
        expires_in: v.get("expires_in").and_then(Value::as_i64),
    })
}

fn first_uhs(v: &Value) -> Option<String> {
    v.get("DisplayClaims")?
        .get("xui")?
        .as_array()?
        .first()?
        .get("uhs")?
        .as_str()
        .map(str::to_string)
}

/// The `xuid` member of a JWT's payload (no signature check, padding optional).
pub fn xuid_from_jwt(jwt: &str) -> Option<String> {
    let payload = jwt.split('.').nth(1)?;
    let bytes = JWT_BASE64.decode(payload).ok()?;
    let v: Value = serde_json::from_slice(&bytes).ok()?;
    match v.get("xuid")? {
        Value::String(s) => Some(s.clone()),
        Value::Number(n) => Some(n.to_string()),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::http::Method;
    use crate::msa::SCOPE_MBI_SSL;
    use crate::testutil::ScriptedTransport;

    /// A JWT whose payload is `{"xuid":"2535400000000000","exp":1}` without padding.
    fn jwt() -> String {
        let payload = base64::engine::general_purpose::URL_SAFE_NO_PAD
            .encode(br#"{"xuid":"2535400000000000","exp":1}"#);
        format!("eyJhbGciOiJIUzI1NiJ9.{payload}.sig")
    }

    fn happy(t: &ScriptedTransport) {
        t.on(
            Method::Post,
            TOKEN_URL,
            200,
            r#"{"token_type":"bearer","expires_in":86400,"scope":"x","access_token":"MSA-AT","refresh_token":"MSA-RT2","user_id":"u"}"#,
        );
        t.on(
            Method::Post,
            XBL_URL,
            200,
            r#"{"IssueInstant":"x","NotAfter":"y","Token":"XBL-T","DisplayClaims":{"xui":[{"uhs":"UHS1"}]}}"#,
        );
        t.on(
            Method::Post,
            XSTS_URL,
            200,
            r#"{"Token":"XSTS-T","DisplayClaims":{"xui":[{"uhs":"UHS2"}]}}"#,
        );
        t.on(
            Method::Post,
            MC_LOGIN_URL,
            200,
            &format!(r#"{{"username":"x","roles":[],"access_token":"{}","token_type":"Bearer","expires_in":86400}}"#, jwt()),
        );
        t.on(
            Method::Get,
            MC_ENTITLEMENTS_URL,
            200,
            r#"{"items":[{"name":"game_minecraft","source":"PURCHASE"}],"signature":"s","keyId":"1"}"#,
        );
        t.on(
            Method::Get,
            MC_PROFILE_URL,
            200,
            r#"{"id":"0123456789abcdef0123456789abcdef","name":"Steve","skins":[],"capes":[]}"#,
        );
    }

    fn client(t: &Arc<ScriptedTransport>) -> MsaClient {
        MsaClient::new(t.clone())
    }

    fn block<F: std::future::Future>(f: F) -> F::Output {
        futures::executor::block_on(f)
    }

    #[test]
    fn a_code_sign_in_runs_the_whole_chain() {
        let t = Arc::new(ScriptedTransport::default());
        happy(&t);
        let (user, file) = block(client(&t).sign_in_with_code("M.C1_code")).unwrap();
        assert_eq!(user.uuid, "0123456789abcdef0123456789abcdef");
        assert_eq!(user.name, "Steve");
        assert_eq!(user.xuid.as_deref(), Some("2535400000000000"));
        assert_eq!(user.user_type.as_deref(), Some("msa"));
        assert_eq!(
            user.client_id.as_deref(),
            Some(encode_client_id(&file.client_id).as_str())
        );
        assert_eq!(file.refresh_token, "MSA-RT2");
        assert!(file.extra.is_empty());

        let token = &t.requests_to(TOKEN_URL)[0];
        assert_eq!(
            token.body_text(),
            "client_id=00000000402b5328&code=M.C1_code&grant_type=authorization_code&redirect_uri=https%3A%2F%2Flogin.live.com%2Foauth20_desktop.srf"
        );
        let xbl: Value = serde_json::from_str(&t.requests_to(XBL_URL)[0].body_text()).unwrap();
        assert_eq!(xbl["Properties"]["RpsTicket"], "d=MSA-AT");
        assert_eq!(xbl["RelyingParty"], "http://auth.xboxlive.com");
        let xsts: Value = serde_json::from_str(&t.requests_to(XSTS_URL)[0].body_text()).unwrap();
        assert_eq!(xsts["Properties"]["UserTokens"][0], "XBL-T");
        assert_eq!(xsts["RelyingParty"], "rp://api.minecraftservices.com/");
        let login: Value =
            serde_json::from_str(&t.requests_to(MC_LOGIN_URL)[0].body_text()).unwrap();
        assert_eq!(login["xtoken"], "XBL3.0 x=UHS2;XSTS-T");
        assert_eq!(login["platform"], "PC_LAUNCHER");
        let ent = &t.requests_to(MC_ENTITLEMENTS_URL)[0];
        assert_eq!(
            ent.url,
            format!("{MC_ENTITLEMENTS_URL}?requestId={}", file.client_id)
        );
        assert!(
            ent.header_value("Authorization")
                .unwrap()
                .starts_with("Bearer eyJ")
        );
        // No Xbox profile-settings call.
        assert!(t.requests_to("https://profile.xboxlive.com").is_empty());
    }

    #[test]
    fn a_renewal_keeps_the_client_id_and_rotates_the_refresh_token() {
        let t = Arc::new(ScriptedTransport::default());
        happy(&t);
        let file = AuthFile::new(Uuid::new_v4(), "MSA-RT1");
        let (_user, renewed) = block(client(&t).renew(&file)).unwrap();
        assert_eq!(renewed.client_id, file.client_id);
        assert_eq!(renewed.refresh_token, "MSA-RT2");
        assert_eq!(
            t.requests_to(TOKEN_URL)[0].body_text(),
            "client_id=00000000402b5328&refresh_token=MSA-RT1&grant_type=refresh_token&redirect_uri=https%3A%2F%2Flogin.live.com%2Foauth20_desktop.srf"
        );
    }

    #[test]
    fn device_code_files_refresh_with_their_scope_and_a_t_ticket() {
        let t = Arc::new(ScriptedTransport::default());
        happy(&t);
        let mut file = AuthFile::new(Uuid::new_v4(), "RT");
        file.extra
            .insert(crate::authfile::EXTRA_SCOPE.into(), SCOPE_MBI_SSL.into());
        block(client(&t).renew(&file)).unwrap();
        let body = t.requests_to(TOKEN_URL)[0].body_text();
        assert!(
            body.contains("scope=service%3A%3Auser.auth.xboxlive.com%3A%3AMBI_SSL"),
            "{body}"
        );
        assert!(!body.contains("redirect_uri"));
        let xbl: Value = serde_json::from_str(&t.requests_to(XBL_URL)[0].body_text()).unwrap();
        assert_eq!(xbl["Properties"]["RpsTicket"], "t=MSA-AT");
    }

    #[test]
    fn invalid_grant_is_a_rejection_without_a_rotated_file() {
        let t = Arc::new(ScriptedTransport::default());
        t.on(
            Method::Post,
            TOKEN_URL,
            400,
            r#"{"error":"invalid_grant","error_description":"The token was revoked.","correlation_id":"c"}"#,
        );
        let f = block(client(&t).renew(&AuthFile::new(Uuid::nil(), "RT"))).unwrap_err();
        assert!(f.error.credentials_rejected());
        assert!(f.rotated.is_none());
    }

    #[test]
    fn a_failure_after_the_refresh_carries_the_rotated_token() {
        let t = Arc::new(ScriptedTransport::default());
        happy(&t);
        t.routes.lock().retain(|(_, u, _)| u != XBL_URL);
        t.on(Method::Post, XBL_URL, 503, "");
        let f = block(client(&t).renew(&AuthFile::new(Uuid::nil(), "RT1"))).unwrap_err();
        assert_eq!(f.error, MsaError::XblHttp(503));
        assert!(!f.error.credentials_rejected());
        assert_eq!(f.rotated.unwrap().refresh_token, "MSA-RT2");
    }

    fn with_route(status: u16, url: &str, method: Method, body: &str) -> Arc<ScriptedTransport> {
        let t = Arc::new(ScriptedTransport::default());
        happy(&t);
        t.routes.lock().retain(|(_, u, _)| u != url);
        t.on(method, url, status, body);
        t
    }

    #[test]
    fn xbl_without_user_hashes_fails() {
        let t = with_route(
            200,
            XBL_URL,
            Method::Post,
            r#"{"Token":"x","DisplayClaims":{"xui":[]}}"#,
        );
        assert_eq!(
            block(client(&t).sign_in_with_code("c")).unwrap_err(),
            MsaError::XblNoXui
        );
    }

    #[test]
    fn xsts_xerr_is_reported() {
        let t = with_route(
            401,
            XSTS_URL,
            Method::Post,
            r#"{"Identity":"0","XErr":2148916233,"Message":"","Redirect":"https://start.ui.xboxlive.com/CreateAccount"}"#,
        );
        assert_eq!(
            block(client(&t).sign_in_with_code("c")).unwrap_err(),
            MsaError::Xsts {
                xerr: 2148916233,
                message: Some(String::new())
            }
        );
        let t = with_route(
            200,
            XSTS_URL,
            Method::Post,
            r#"{"Token":"x","DisplayClaims":{"xui":[]}}"#,
        );
        assert_eq!(
            block(client(&t).sign_in_with_code("c")).unwrap_err(),
            MsaError::XstsNoXui
        );
    }

    #[test]
    fn no_entitlements_means_not_owned() {
        let t = with_route(200, MC_ENTITLEMENTS_URL, Method::Get, r#"{"items":[]}"#);
        let e = block(client(&t).sign_in_with_code("c")).unwrap_err();
        assert_eq!(e, MsaError::NotOwned);
        assert!(e.to_string().contains("not have bought"));
    }

    #[test]
    fn a_missing_profile_is_a_404() {
        let t = with_route(404, MC_PROFILE_URL, Method::Get, r#"{"error":"NOT_FOUND"}"#);
        assert_eq!(
            block(client(&t).sign_in_with_code("c")).unwrap_err(),
            MsaError::ProfileHttp(404)
        );
    }

    #[test]
    fn a_transport_failure_is_reported_as_such() {
        let t = Arc::new(ScriptedTransport::default());
        let e = block(client(&t).sign_in_with_code("c")).unwrap_err();
        assert!(matches!(e, MsaError::Transport(_)));
    }

    #[test]
    fn jwt_xuid_extraction_accepts_unpadded_payloads() {
        assert_eq!(xuid_from_jwt(&jwt()).as_deref(), Some("2535400000000000"));
        assert_eq!(xuid_from_jwt("not-a-jwt"), None);
        assert_eq!(xuid_from_jwt("a.!!!.c"), None);
    }

    #[test]
    fn an_unsupported_device_grant_name_falls_back_to_the_short_form() {
        let t = Arc::new(ScriptedTransport::default());
        t.on(
            Method::Post,
            TOKEN_URL,
            400,
            r#"{"error":"unsupported_grant_type"}"#,
        );
        t.on(
            Method::Post,
            TOKEN_URL,
            400,
            r#"{"error":"authorization_pending"}"#,
        );
        let c = client(&t);
        let poll = || block(c.poll_device_code(OFFICIAL_CLIENT_ID, "DC")).unwrap();
        assert_eq!(poll(), DevicePoll::Pending);
        assert_eq!(poll(), DevicePoll::Pending);
        let grants: Vec<String> = t
            .requests_to(TOKEN_URL)
            .iter()
            .map(|r| r.body_text().split('&').next().unwrap().to_string())
            .collect();
        assert_eq!(
            grants,
            vec![
                "grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Adevice_code",
                "grant_type=device_code",
                "grant_type=device_code",
            ]
        );
    }

    #[test]
    fn device_code_requests_and_polls() {
        let t = Arc::new(ScriptedTransport::default());
        t.on(
            Method::Post,
            DEVICE_CODE_URL,
            200,
            r#"{"user_code":"ABCD1234","device_code":"DC","verification_uri":"https://www.microsoft.com/link","expires_in":900,"interval":5}"#,
        );
        let c = client(&t);
        let dc = block(c.request_device_code(OFFICIAL_CLIENT_ID, SCOPE_MBI_SSL)).unwrap();
        assert_eq!(dc.user_code, "ABCD1234");
        assert_eq!(dc.interval, 5);
        assert_eq!(
            t.requests_to(DEVICE_CODE_URL)[0].body_text(),
            "client_id=00000000402b5328&scope=service%3A%3Auser.auth.xboxlive.com%3A%3AMBI_SSL&response_type=device_code"
        );
        t.on(
            Method::Post,
            TOKEN_URL,
            400,
            r#"{"error":"authorization_pending"}"#,
        );
        t.on(Method::Post, TOKEN_URL, 400, r#"{"error":"slow_down"}"#);
        t.on(Method::Post, TOKEN_URL, 400, r#"{"error":"expired_token"}"#);
        t.on(
            Method::Post,
            TOKEN_URL,
            400,
            r#"{"error":"authorization_declined"}"#,
        );
        t.on(
            Method::Post,
            TOKEN_URL,
            200,
            r#"{"access_token":"A","refresh_token":"R"}"#,
        );
        let poll = || block(c.poll_device_code(OFFICIAL_CLIENT_ID, "DC")).unwrap();
        assert_eq!(poll(), DevicePoll::Pending);
        assert_eq!(poll(), DevicePoll::SlowDown);
        assert_eq!(poll(), DevicePoll::Expired);
        assert_eq!(poll(), DevicePoll::Declined);
        assert!(matches!(poll(), DevicePoll::Done(t) if t.refresh_token.as_deref() == Some("R")));
        assert!(
            t.requests_to(TOKEN_URL)[0]
                .body_text()
                .starts_with("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Adevice_code&")
        );
    }
}
