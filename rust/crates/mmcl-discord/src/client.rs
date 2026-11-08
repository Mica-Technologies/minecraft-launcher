// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The IPC client: handshake, `READY`, the `ACTIVITY_JOIN` subscription, `SET_ACTIVITY`, and
//! a reader task that answers pings and reports join events.
//!
//! A [`Connection`] must live inside a tokio runtime: its reader is a spawned task.

use std::time::Duration;

use serde_json::{Value, json};
use tokio::io::{AsyncWriteExt, ReadHalf, WriteHalf};
use tokio::sync::mpsc;
use tokio::task::JoinHandle;

use crate::activity::Activity;
use crate::error::IpcError;
use crate::frame::{Opcode, read_frame, write_frame};
use crate::transport::BoxStream;

/// How long the handshake may take until `READY`.
pub const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(5);

/// How long one write may take before the connection is given up.
pub const WRITE_TIMEOUT: Duration = Duration::from_secs(5);

/// What the reader task saw.
#[derive(Debug, Clone, PartialEq)]
pub enum ConnEvent {
    /// Discord pinged; answer with a pong carrying the same payload.
    Ping(Value),
    /// A friend pressed "Join": the join secret.
    Join(String),
    /// Discord answered a command with an error (`data.message`).
    CommandError(String),
    /// The connection ended (Discord closed it, or reading failed).
    Closed(String),
}

/// The `{"v":1,"client_id":..}` handshake payload.
pub fn handshake_payload(client_id: &str) -> Value {
    json!({ "v": 1, "client_id": client_id })
}

/// A fresh command nonce.
pub fn nonce() -> String {
    uuid::Uuid::new_v4().to_string()
}

/// `SUBSCRIBE` to `evt`.
pub fn subscribe_payload(evt: &str, nonce: &str) -> Value {
    json!({ "cmd": "SUBSCRIBE", "evt": evt, "nonce": nonce })
}

/// The activity half of a `SET_ACTIVITY` command.
#[derive(Debug, Clone, Copy)]
pub enum SetActivity<'a> {
    /// Show this activity.
    Show(&'a Activity),
    /// `args` without `activity`: most clients clear the activity on this.
    ClearAbsent,
    /// `"activity": {}`: the clear some client versions need instead.
    ClearEmpty,
}

/// A `SET_ACTIVITY` command.
pub fn set_activity_payload(pid: u32, activity: SetActivity<'_>, nonce: &str) -> Value {
    let mut args = json!({ "pid": pid });
    match activity {
        SetActivity::Show(a) => args["activity"] = a.to_json(),
        SetActivity::ClearAbsent => {}
        SetActivity::ClearEmpty => args["activity"] = json!({}),
    }
    json!({ "cmd": "SET_ACTIVITY", "args": args, "nonce": nonce })
}

fn str_field<'a>(v: &'a Value, key: &str) -> Option<&'a str> {
    v.get(key).and_then(Value::as_str)
}

fn error_message(payload: &Value) -> String {
    let data = payload.get("data").unwrap_or(&Value::Null);
    match (data.get("code"), str_field(data, "message")) {
        (Some(code), Some(msg)) => format!("{code}: {msg}"),
        (None, Some(msg)) => msg.to_string(),
        _ => payload.to_string(),
    }
}

/// Classifies one received frame (other than during the handshake).
pub fn classify(op: Opcode, payload: Value) -> Option<ConnEvent> {
    match op {
        Opcode::Ping => Some(ConnEvent::Ping(payload)),
        Opcode::Close => Some(ConnEvent::Closed(close_reason(&payload))),
        Opcode::Frame => {
            let evt = str_field(&payload, "evt");
            match (str_field(&payload, "cmd"), evt) {
                (Some("DISPATCH"), Some("ACTIVITY_JOIN")) => payload
                    .get("data")
                    .and_then(|d| str_field(d, "secret"))
                    .map(|s| ConnEvent::Join(s.to_string())),
                (_, Some("ERROR")) => Some(ConnEvent::CommandError(error_message(&payload))),
                _ => None,
            }
        }
        Opcode::Handshake | Opcode::Pong => None,
    }
}

fn close_reason(payload: &Value) -> String {
    match (payload.get("code"), str_field(payload, "message")) {
        (Some(code), Some(msg)) => format!("{code}: {msg}"),
        (None, Some(msg)) => msg.to_string(),
        _ => payload.to_string(),
    }
}

/// An open connection that has passed the handshake.
pub struct Connection {
    writer: WriteHalf<BoxStream>,
    reader: JoinHandle<()>,
    endpoint: String,
}

impl Connection {
    /// Runs the handshake on `stream`, waits for `READY`, subscribes to `ACTIVITY_JOIN` and
    /// starts the reader task, which reports into `events`.
    pub async fn open(
        mut stream: BoxStream,
        endpoint: String,
        client_id: &str,
        events: mpsc::UnboundedSender<ConnEvent>,
    ) -> Result<Connection, IpcError> {
        tokio::time::timeout(HANDSHAKE_TIMEOUT, handshake(&mut stream, client_id))
            .await
            .map_err(|_| IpcError::Timeout("handshake"))??;
        let (read, writer) = tokio::io::split(stream);
        let reader = tokio::spawn(read_loop(read, events));
        let mut conn = Connection {
            writer,
            reader,
            endpoint,
        };
        conn.send(Opcode::Frame, &subscribe_payload("ACTIVITY_JOIN", &nonce()))
            .await?;
        Ok(conn)
    }

    /// The endpoint this connection uses.
    pub fn endpoint(&self) -> &str {
        &self.endpoint
    }

    /// Writes one frame, bounded by [`WRITE_TIMEOUT`].
    pub async fn send(&mut self, op: Opcode, payload: &Value) -> Result<(), IpcError> {
        tokio::time::timeout(WRITE_TIMEOUT, write_frame(&mut self.writer, op, payload))
            .await
            .map_err(|_| IpcError::Timeout("write"))?
    }

    /// Sends `SET_ACTIVITY`.
    pub async fn set_activity(&mut self, activity: SetActivity<'_>) -> Result<(), IpcError> {
        let payload = set_activity_payload(std::process::id(), activity, &nonce());
        self.send(Opcode::Frame, &payload).await
    }

    /// Answers a ping.
    pub async fn pong(&mut self, payload: &Value) -> Result<(), IpcError> {
        self.send(Opcode::Pong, payload).await
    }

    /// Clears the activity both ways, waits `grace` so Discord reads it before the pipe
    /// closes (150 ms raced the close and left "In Game" stuck), then closes.
    pub async fn clear_and_close(mut self, grace: Duration) -> Result<(), IpcError> {
        let a = self.set_activity(SetActivity::ClearAbsent).await;
        let b = self.set_activity(SetActivity::ClearEmpty).await;
        tokio::time::sleep(grace).await;
        let c = self.close().await;
        a.and(b).and(c)
    }

    /// Sends CLOSE and shuts the stream.
    pub async fn close(&mut self) -> Result<(), IpcError> {
        let sent = self.send(Opcode::Close, &json!({})).await;
        let _ = tokio::time::timeout(WRITE_TIMEOUT, self.writer.shutdown()).await;
        self.reader.abort();
        sent
    }
}

impl Drop for Connection {
    fn drop(&mut self) {
        self.reader.abort();
    }
}

async fn handshake(stream: &mut BoxStream, client_id: &str) -> Result<(), IpcError> {
    write_frame(stream, Opcode::Handshake, &handshake_payload(client_id)).await?;
    loop {
        let frame = read_frame(stream).await?;
        match frame.op {
            Opcode::Ping => write_frame(stream, Opcode::Pong, &frame.payload).await?,
            Opcode::Close => return Err(IpcError::Closed(close_reason(&frame.payload))),
            Opcode::Frame => {
                let p = &frame.payload;
                match (str_field(p, "cmd"), str_field(p, "evt")) {
                    (Some("DISPATCH"), Some("READY")) => return Ok(()),
                    (_, Some("ERROR")) => return Err(IpcError::Rejected(error_message(p))),
                    _ => {}
                }
            }
            Opcode::Handshake | Opcode::Pong => {}
        }
    }
}

async fn read_loop(mut read: ReadHalf<BoxStream>, events: mpsc::UnboundedSender<ConnEvent>) {
    loop {
        match read_frame(&mut read).await {
            Ok(frame) => {
                let closing = frame.op == Opcode::Close;
                if let Some(ev) = classify(frame.op, frame.payload)
                    && events.send(ev).is_err()
                {
                    return;
                }
                if closing {
                    return;
                }
            }
            Err(e) => {
                let _ = events.send(ConnEvent::Closed(e.to_string()));
                return;
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::activity::JoinParty;

    fn activity() -> Activity {
        Activity {
            state: "s".into(),
            details: "d".into(),
            start: 1,
            large_image: "l".into(),
            large_text: "lt".into(),
            small_image: "sm".into(),
            small_text: "st".into(),
            join: Some(JoinParty {
                party_id: "p".into(),
                secret: "mmcl://join?url=x".into(),
                size: 1,
                max: 4,
            }),
        }
    }

    #[test]
    fn handshake_payload_shape() {
        assert_eq!(
            handshake_payload("841860482029846528"),
            json!({"v":1,"client_id":"841860482029846528"})
        );
    }

    #[test]
    fn subscribe_payload_shape() {
        assert_eq!(
            subscribe_payload("ACTIVITY_JOIN", "n"),
            json!({"cmd":"SUBSCRIBE","evt":"ACTIVITY_JOIN","nonce":"n"})
        );
    }

    #[test]
    fn set_activity_payload_variants() {
        let a = activity();
        let show = set_activity_payload(42, SetActivity::Show(&a), "n");
        assert_eq!(show["cmd"], "SET_ACTIVITY");
        assert_eq!(show["nonce"], "n");
        assert_eq!(show["args"]["pid"], 42);
        assert_eq!(show["args"]["activity"], a.to_json());

        let absent = set_activity_payload(42, SetActivity::ClearAbsent, "n");
        assert_eq!(absent["args"], json!({"pid":42}));

        let empty = set_activity_payload(42, SetActivity::ClearEmpty, "n");
        assert_eq!(empty["args"], json!({"pid":42,"activity":{}}));
    }

    #[test]
    fn nonces_are_unique_uuids() {
        let a = nonce();
        assert_ne!(a, nonce());
        assert!(uuid::Uuid::parse_str(&a).is_ok());
    }

    #[test]
    fn classify_recognises_join_errors_pings_and_close() {
        assert_eq!(
            classify(
                Opcode::Frame,
                json!({"cmd":"DISPATCH","evt":"ACTIVITY_JOIN","data":{"secret":"mmcl://join?url=a"}})
            ),
            Some(ConnEvent::Join("mmcl://join?url=a".into()))
        );
        assert_eq!(
            classify(
                Opcode::Frame,
                json!({"cmd":"SET_ACTIVITY","evt":"ERROR","data":{"code":4000,"message":"bad"}})
            ),
            Some(ConnEvent::CommandError("4000: bad".into()))
        );
        assert_eq!(
            classify(Opcode::Ping, json!({"x":1})),
            Some(ConnEvent::Ping(json!({"x":1})))
        );
        assert_eq!(
            classify(
                Opcode::Close,
                json!({"code":4000,"message":"Invalid Client ID"})
            ),
            Some(ConnEvent::Closed("4000: Invalid Client ID".into()))
        );
        // Ordinary responses are ignored.
        assert_eq!(
            classify(
                Opcode::Frame,
                json!({"cmd":"SET_ACTIVITY","evt":null,"data":{}})
            ),
            None
        );
        // A join without a secret is ignored.
        assert_eq!(
            classify(
                Opcode::Frame,
                json!({"cmd":"DISPATCH","evt":"ACTIVITY_JOIN","data":{}})
            ),
            None
        );
    }

    async fn server_reads(server: &mut tokio::io::DuplexStream) -> crate::frame::Frame {
        read_frame(server).await.unwrap()
    }

    #[tokio::test]
    async fn open_runs_the_handshake_and_subscribes() {
        let (client, mut server) = tokio::io::duplex(4096);
        let (tx, mut rx) = mpsc::unbounded_channel();
        let open = tokio::spawn(Connection::open(
            Box::new(client),
            "test".into(),
            "841860482029846528",
            tx,
        ));

        let hs = server_reads(&mut server).await;
        assert_eq!(hs.op, Opcode::Handshake);
        assert_eq!(hs.payload["client_id"], "841860482029846528");
        // A ping during the handshake is answered.
        write_frame(&mut server, Opcode::Ping, &json!({"p":1}))
            .await
            .unwrap();
        let pong = server_reads(&mut server).await;
        assert_eq!(pong.op, Opcode::Pong);
        assert_eq!(pong.payload, json!({"p":1}));
        write_frame(
            &mut server,
            Opcode::Frame,
            &json!({"cmd":"DISPATCH","evt":"READY","data":{"v":1}}),
        )
        .await
        .unwrap();

        let conn = open.await.unwrap().unwrap();
        assert_eq!(conn.endpoint(), "test");
        let sub = server_reads(&mut server).await;
        assert_eq!(sub.payload["cmd"], "SUBSCRIBE");
        assert_eq!(sub.payload["evt"], "ACTIVITY_JOIN");

        // Join events arrive through the reader task.
        write_frame(
            &mut server,
            Opcode::Frame,
            &json!({"cmd":"DISPATCH","evt":"ACTIVITY_JOIN","data":{"secret":"mmcl://join?url=z"}}),
        )
        .await
        .unwrap();
        assert_eq!(
            rx.recv().await,
            Some(ConnEvent::Join("mmcl://join?url=z".into()))
        );

        // Exit sequence: two clears, then CLOSE.
        let close =
            tokio::spawn(async move { conn.clear_and_close(Duration::from_millis(1)).await });
        let c1 = server_reads(&mut server).await;
        assert_eq!(c1.payload["args"].get("activity"), None);
        let c2 = server_reads(&mut server).await;
        assert_eq!(c2.payload["args"]["activity"], json!({}));
        let c3 = server_reads(&mut server).await;
        assert_eq!(c3.op, Opcode::Close);
        close.await.unwrap().unwrap();
    }

    #[tokio::test]
    async fn open_fails_when_discord_closes_the_handshake() {
        let (client, mut server) = tokio::io::duplex(4096);
        let (tx, _rx) = mpsc::unbounded_channel();
        let open = tokio::spawn(Connection::open(Box::new(client), "t".into(), "1", tx));
        let _ = server_reads(&mut server).await;
        write_frame(
            &mut server,
            Opcode::Close,
            &json!({"code":4000,"message":"Invalid Client ID"}),
        )
        .await
        .unwrap();
        match open.await.unwrap() {
            Err(IpcError::Closed(msg)) => assert!(msg.contains("Invalid Client ID")),
            Err(other) => panic!("unexpected error {other}"),
            Ok(_) => panic!("handshake should fail"),
        }
    }

    #[tokio::test]
    async fn reader_reports_eof_as_closed() {
        let (client, mut server) = tokio::io::duplex(4096);
        let (tx, mut rx) = mpsc::unbounded_channel();
        let open = tokio::spawn(Connection::open(Box::new(client), "t".into(), "1", tx));
        let _ = server_reads(&mut server).await;
        write_frame(
            &mut server,
            Opcode::Frame,
            &json!({"cmd":"DISPATCH","evt":"READY"}),
        )
        .await
        .unwrap();
        let _conn = open.await.unwrap().unwrap();
        let _ = server_reads(&mut server).await;
        drop(server);
        assert!(matches!(rx.recv().await, Some(ConnEvent::Closed(_))));
    }
}
