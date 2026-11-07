# Porting spec: MCP server (`mcp/` subsystem)

Source slice: `src/main/java/com/micatechnologies/minecraft/launcher/mcp/` (41 classes, ~10k lines incl. Javadoc), its tests under `src/test/java/.../mcp/` (21 classes, ~400 test methods), and the human spec `docs/pdf-generation-assets/mcp-server-guide/guide.html`.

Target: a Rust crate `mmcl-mcp` inside the `rust/` Cargo workspace (alongside `mmcl-app`), with the launcher-facing adapters (live view, live actions, GPUI consent dialog, Settings pane) implemented in the app crate against traits this crate defines.

Where the Java code and the guide disagree, **the Java code is authoritative**; the disagreements are listed in section 18.

---

## 0. One-paragraph summary

The launcher hosts an optional (off by default) **Model Context Protocol server**: JSON-RPC 2.0 over MCP's "Streamable HTTP" transport (POST-only, no SSE stream), bound to **127.0.0.1 only**, protected by a persisted 256-bit bearer token, an Origin check against browser/DNS-rebinding attacks, and body-size/media-type checks. Clients handshake with `initialize`, get an `Mcp-Session-Id`, then list and call **tools** (7 read-only, 8 state-changing behind a second opt-in) and read three **resources** under a `mica://` URI scheme. Every tool call and resource read passes: content gate (`validate_before_approval`) → approval engine (pure decision table over risk class, per-tool policy, session grants, master toggles, GUI availability) → optional consent dialog (Allow once / Allow for this session / deny, 120 s deadline, fail-closed) → invocation on a **single serial dispatch thread** → **strict redaction** of every outbound text block → in-memory activity log (redacted on write). A `--mcp` CLI mode is a dumb stdio↔HTTP relay for clients that can only spawn subprocesses; it reads the port/token from an owner-only endpoint file and transparently re-initializes expired sessions.

---

## 1. Feature gates, lifecycle and configuration

### 1.1 Config keys (launcher `configuration.json`, client-mode config folder)

All live in the same `configuration.json` every other launcher setting uses (owned by the config subsystem; debounced writes).

| Key | Type | Default | Meaning |
|---|---|---|---|
| `mcpServerEnabled` | bool | `false` | Master switch. Off → nothing binds, no token generated, no endpoint file. |
| `mcpAllowStateChanges` | bool | `false` | Registers the 8 state-changing tools. Off → they do not exist on the wire (`-32602 Unknown tool`). |
| `mcpAutoApproveReadOnly` | bool | `true` | READ_ONLY tools with no explicit policy run without a prompt. Read live on each call (no restart). |
| `mcpToolPolicies` | **string** containing a JSON object | `""` | `{"<tool name lower-case>":"ALWAYS_ALLOW"\|"ASK"\|"DISABLED"}`. Note: stored as a JSON *string value* inside configuration.json, not a nested object. |
| `mcpPort` | int | `47823` release, `47824` dev build | Loopback port. Setter clamps: values outside 1..=65535 store the default. No UI. |
| `mcpToken` | string | `""` | Persisted bearer token. Generated on first use; a malformed value is replaced. |

Port constants: `MCP_SERVER_PORT = 47823`, `MCP_SERVER_PORT_DEV = 47824` (dev and release launchers can run side by side). `LAUNCHER_IS_DEV` selects.

### 1.2 Server modes (`McpServerMode`)

Enum `OFF | WITH_LAUNCHER | TRAY_BACKGROUND`. Only `OFF`/`WITH_LAUNCHER` are implemented; tray/background mode is unbuilt (phase 5). Port the enum but only implement the first two.

### 1.3 Bootstrap (`McpBootstrap`) — process-global lifecycle holder

Process-wide singletons (Rust: one `McpBootstrap` struct held in a `OnceLock`/app global, all methods behind one `Mutex`):

- `server: Option<McpServer>` — the running server.
- `GRANTS: McpGrantStore` — **outlives server restarts within one run**, but `stop()` calls `revoke_all()`.
- `ACTIVITY: McpActivityLog` — outlives server restarts within one run (toggling off/on must not erase history). Not persisted across launcher restarts.
- `POLICIES: McpToolPolicyStore` backed by config key `mcpToolPolicies` (read/write string).

`start_if_enabled()` (synchronized):
1. If already running → return.
2. Read `mcpServerEnabled`; if the read errors → log warning `log.mcpBootstrap.settingUnreadable`, return (feature stays off). If false → return.
3. Build `LiveMcpLauncherView`; new `McpToolRegistry`; register read-only tools.
4. If `mcpAllowStateChanges` → register mutating tools with `LiveMcpLauncherActions`; log `log.mcpBootstrap.writeToolsEnabled`.
5. New `McpResourceRegistry`; register the 3 launcher resources.
6. `LauncherMcpAuthorizer(settings = config-backed, grants = GRANTS, prompt = GUI consent prompt, clock = now_ms)`.
7. `McpServer::new(tools, resources, authorizer, ACTIVITY).start(config.mcpPort, endpoint_default_path(), ensure_token())`.
8. Any error → log error `log.mcpBootstrap.startFailed` + the error; `server = None`. **Never fails launcher startup.**

`stop()`: stop server if any (error → warning `log.mcpBootstrap.stopUnclean`), set None, then **always** `GRANTS.revoke_all()`. Must be harmless when never started (called unconditionally from launcher cleanup, inside a bounded cleanup step).

`refresh()` = `stop(); start_if_enabled()`. Called when the Settings toggles for `mcpServerEnabled` or `mcpAllowStateChanges` change (the tool registry is built at start, so a toggle needs a restart). `mcpAutoApproveReadOnly` and per-tool policies do **not** refresh; they are read on each call.

`is_running()`, `port()` (0 when stopped), `sessions()` (empty when stopped).

`describe_tools() -> Vec<ToolDescriptor{name, title(localized), risk_class}>`: builds a **throwaway** registry with *both* read-only and mutating tools regardless of config, so Settings can show/pre-configure policies while the server is off or state changes disabled. Errors → warning `log.mcpBootstrap.listToolsFailed`, return what was collected.

`ensure_token()` → `McpAccessToken::ensure(read mcpToken, write mcpToken)`.

`regenerate_token()` (synchronized): generate, store to config, log `log.mcpBootstrap.tokenRegenerated`, `refresh()` (restart so the transport uses it), return new token.

Accessors `activity()`, `policies()`, `grants()` for the Settings pane.

Where it is called from:
- **Start**: GUI session startup (`LauncherSession.run`), after the installed-pack list is populated and the async available-pack fetch has been kicked off, before the main window shows. **GUI mode only** — not from TUI (`--cli`) or headless server mode (the consent prompt needs a window; headless would fail closed on anything not auto-approved).
- **Stop**: launcher cleanup (`cleanupStepBounded("mcp", McpBootstrap::stop)`).

### 1.4 The bearer token (`McpAccessToken`)

- `generate()`: 32 bytes from a CSPRNG, lower-case hex → 64 chars (`TOKEN_LENGTH = 64`).
- `is_well_formed(s)`: exactly 64 chars, each `[0-9a-fA-F]` (upper case accepted).
- `ensure(read, write)`: read (an error counts as absent); if well-formed return it; else generate, try to write (write error ignored — this run still has a usable token; next launch generates another), return it. Both accessors required.
- `mask(token)`: `""` for empty; else first `min(8, len)` chars + `"… (" + len + " characters)"` (the ellipsis is U+2026). E.g. `3f9a21c8… (64 characters)`.
- Why persisted not per-launch: client configs are hand-edited; a rotating token would make the feature unusable. Mitigations: loopback-only, owner-only storage, Regenerate button.

### 1.5 Endpoint file (`McpEndpointFile`)

- Path: `<client-mode config folder>/mcp-endpoint.json` (Java: `~/.MicaMinecraftLauncher/config/mcp-endpoint.json`, dev `~/.MicaMinecraftLauncherDEV/config/`). **Must** use the game-mode-independent client config path (same reason as the single-instance IPC token): the relay process runs with a different CWD and before game mode is known; a mode-dependent path would resolve to CWD in server mode / early main and never agree.
- Content (compact JSON, key order as shown): `{"port":47823,"token":"<64 hex>","pid":12345,"launcherVersion":"<version>"}`.
- `write(path, endpoint)`: create parent dirs; write UTF-8; then apply owner-only permissions (POSIX 0600; Windows: replace DACL with a single ALLOW FULL_CONTROL ACE for the file owner). Java applies perms *after* writing (shared helper); in Rust prefer creating with mode 0600 atomically on Unix (`OpenOptionsExt::mode(0o600)`) and then the ACL on Windows. Write failure → `start()` stops the server and returns the error.
- `read(path) -> Option<Endpoint>`: None if not a regular file, unparsable, missing `port` or `token`, port ∉ 1..=65535, token blank. `pid` absent → -1; `launcherVersion` absent → `""`. Any exception → None (a half-written file during startup is a normal race).
- `read_live(path)`: `read`, then if `pid > 0 && !is_process_alive(pid)` → None (stale file from a crashed launcher would send a client to a port the launcher no longer owns). A file with no/≤0 pid is treated as live (back-compat).
- `delete(path)`: delete-if-exists; error → warning `log.mcpEndpointFile.removeFailed`.
- `is_process_alive(pid)`: pid ≤ 0 → false; otherwise OS check. Rust: Unix `kill(pid, 0)` (0 or EPERM → alive; ESRCH → dead); Windows `OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION)` + `GetExitCodeProcess == STILL_ACTIVE`. (Or `sysinfo`, heavier.) PID reuse is an accepted residual risk.

---

## 2. Protocol layer

### 2.1 Constants

- JSON-RPC version `"2.0"`. MCP protocol revision **`"2025-06-18"`** (`PROTOCOL_VERSION`). The server does **not** negotiate: it always answers with this version regardless of what the client sent.
- `serverInfo = {"name":"Mica Minecraft Launcher","version":<launcher version>}`.
- Capabilities: `{"tools":{"listChanged":false},"resources":{"subscribe":false,"listChanged":false}}` (no `prompts` capability).

Methods (`McpMethods`): `initialize`, `notifications/initialized`, `ping`, `tools/list`, `tools/call`, `resources/list`, `resources/read`, `resources/templates/list`, plus constants `prompts/list`, `prompts/get` which are **not implemented** (answer -32601).

Error codes (`McpErrors`):

| Code | Name | Used for |
|---|---|---|
| -32700 | PARSE_ERROR | empty/blank body, malformed JSON, bare `null` root. id always null. |
| -32600 | INVALID_REQUEST | batch, non-object root, bad id, bad/missing `jsonrpc`/`method`, bad `params` type; non-ping before handshake (`Session is not initialized`); missing session header (HTTP 400). |
| -32601 | METHOD_NOT_FOUND | `Unknown method: <m>` |
| -32602 | INVALID_PARAMS | missing tool name, unknown tool, content-gate refusal, missing resource uri |
| -32603 | INTERNAL_ERROR | tool threw, resource read threw, dispatch failure, server stopping, interrupted; also relay-synthesized errors |
| -32001 | SESSION_NOT_FOUND | unknown/expired session id (HTTP 404) |
| -32002 | RESOURCE_NOT_FOUND | `No such resource: <uri>` (includes segment-gate rejections) |
| -32003 | REQUEST_DENIED | approval said no |

### 2.2 `JsonRpcCodec::parse(raw) -> Result<JsonRpcMessage, ErrorResponse>` — exact order

1. `raw` null or blank → `-32700 "Empty request"`, id null.
2. JSON parse failure → `-32700 "Malformed JSON"`, id null. Root is JSON `null` → same.
3. Root is array → `-32600 "Batch requests are not supported"` (MCP removed batching; reject even `[]`), id null.
4. Root not an object → `-32600 "Request must be a JSON object"`, id null.
5. If `"id"` present: JSON null → `-32600 "Request id must not be null"` (id null); not a string or number (object/array/bool) → `-32600 "Request id must be a string or number"` (id null — **never echo invalid ids**, it would reflect caller-controlled structure). Otherwise remember id.
6. `"jsonrpc"` missing, not a primitive, or its string form ≠ `"2.0"` → `-32600 "Missing or unsupported \"jsonrpc\" version"` (echo valid id). Note: Gson `getAsString` on a number primitive `2.0` gives `"2.0"` → accepted; Rust may be strict (require JSON string) — harmless divergence, but see test `aWrongJsonrpcVersionIsRejected`.
7. `"method"` missing or not a JSON string → `-32600 "Missing or non-string \"method\""`; blank (whitespace) → `-32600 "Method name must not be blank"`.
8. `"params"` present and not null: must be object or array, else `-32600 "\"params\" must be an object or array"`. Null params = absent.
9. Unknown top-level fields are ignored.

`JsonRpcMessage { id: Option<Value /*string|number*/>, method: String, params: Option<Value> }`:
- `is_notification()` = `id.is_none()`.
- `params_object()` → params if object; else (absent or array) an empty object.

Builders:
- `result(id, result)` → `{"jsonrpc":"2.0","id":<id>,"result":<result or {}>}` (null result becomes `{}`, which is what `ping` returns).
- `error(id, code, message, data?)` → `{"jsonrpc":"2.0","id":<id or null>,"error":{"code":..,"message":<msg or "">,"data":<only if supplied>}}`. **The `id` field is always present** (JSON `null` when unknowable).
- `encode()` → compact JSON.

Rust notes:
- Use `serde_json` with the **`preserve_order`** feature (Gson `JsonObject` keeps insertion order; tool listings, schemas and result field order should match) and consider **`arbitrary_precision`** so numeric ids echo byte-for-byte (Gson's `LazilyParsedNumber` preserves the literal; e.g. id `1.0` or a 20-digit id). If `arbitrary_precision` is unwanted workspace-wide, keep ids as `serde_json::value::RawValue`/`Box<RawValue>`.
- Gson's parser is lenient (single quotes, unquoted keys, comments) but must consume the whole document. serde_json is strict; that is an acceptable (safer) divergence.
- Gson escapes HTML-sensitive chars (`<`, `>`, `&`, `=`, `'` → `\u003c` etc.) when serializing; serde_json does not. Semantically identical JSON; only byte-level tests differ. Do not replicate.

### 2.3 `McpRequestHandler::handle(message, session, now_ms) -> Option<JsonObject>`

Pure of transport and GUI. Order:

1. `message` or `session` missing → `-32603 "No message to handle"` (id null).
2. `session.record_activity(now_ms, method == "tools/call")`.
3. If notification: if `notifications/initialized` → debug log `log.mcpRequestHandler.clientInitialized` (client name). **Return None for every notification**, known or unknown (answering one is a protocol violation; an unknown notification is not an error).
4. `initialize` → handshake (below).
5. If `!session.initialized && method != "ping"` → `-32600 "Session is not initialized"`. (Client name is half of every grant key; calls must not precede identification.)
6. Dispatch:
   - `ping` → `result {}`
   - `tools/list` → `tools.list_result()`
   - `tools/call` → section 5.3
   - `resources/list` → `resources.list_result()`
   - `resources/templates/list` → `resources.templates_list_result()`
   - `resources/read` → section 6.4
   - else → `-32601 "Unknown method: <method>"`.

Handshake: `params.clientInfo` (if object, else `{}`); `session.initialize(read_string(clientInfo,"name"), read_string(clientInfo,"version"))` — **only the first handshake sets identity** (a re-`initialize` on a live session is answered normally but cannot rename the client, else it could pick up another client's grants). Result: `{"protocolVersion":"2025-06-18","capabilities":{...},"serverInfo":{...}}`.

`read_string(obj, key)`: absent, JSON null, or non-primitive → `""`; primitive → its string form (numbers/bools stringify; Gson `getAsString`). Not trimmed here.

Constructor requires all four collaborators (tools, resources, authorizer, activity log); `None`/null → error.

---

## 3. Transport: loopback HTTP (`LoopbackHttpTransport`, `McpHttpGuard`, `McpServer::dispatch`)

### 3.1 Listener

- Bind address: **loopback only, always** (`127.0.0.1`; Java uses `InetAddress.getLoopbackAddress()`). There is deliberately no way to configure another address.
- Single path `/mcp`. (JDK `HttpServer.createContext` is a *prefix* match, so Java also serves `/mcp/anything` and `/mcpx`; other paths get the JDK's 404. Rust: match path `== "/mcp"` exactly, ignore query string; everything else 404 with empty body. Divergence is harmless.)
- Concurrency: Java uses a fixed pool of **8** daemon threads named `mcp-http-N`; headroom so pings/listings stay answerable while a few tool calls block on consent.
- `start(port)`: error if already running; returns the bound port (port 0 → OS-assigned).
- `stop()`: immediate (`server.stop(0)`), idempotent.
- Windows: do **not** set `SO_REUSEADDR` on the listener (on Windows it allows port hijacking); consider `SO_EXCLUSIVEADDRUSE`. Tokio's `TcpListener::bind` sets `SO_REUSEADDR` only on Unix, which is fine.
- No CORS headers are ever sent; preflights (`OPTIONS` with an Origin) fall out as 403/401/405.

### 3.2 Admission (`McpHttpGuard::admit(request_meta, expected_token) -> Verdict`) — pure, before any body byte is read

Inputs: HTTP method, `Authorization`, `Origin`, `Content-Type` (first values), declared `Content-Length` (−1 if absent or unparsable after trim), `remote_is_loopback`.

Order (deliberate: absolute checks first, then auth, so nothing past auth can probe an unauthenticated server):

| # | Check | Verdict / status |
|---|---|---|
| 1 | request present and peer is loopback (unresolvable peer = not loopback) | FORBIDDEN 403 |
| 2 | `is_allowed_origin(Origin)` | FORBIDDEN 403 |
| 3 | `tokens_match(expected, bearer_token_of(Authorization))` | UNAUTHORIZED 401 |
| 4 | method `DELETE` (case-insensitive) → **ALLOW** immediately (no body checks) | — |
| 5 | method ≠ `POST` (case-insensitive) | METHOD_NOT_ALLOWED 405 |
| 6 | declared length > `MAX_BODY_BYTES` (1 MiB = 1,048,576; exactly 1 MiB is OK; unknown length passes) | PAYLOAD_TOO_LARGE 413 |
| 7 | `is_json_content_type` | UNSUPPORTED_MEDIA_TYPE 415 |
| — | otherwise | ALLOW |

Rejections send the **status only, no body** ("a rejected caller learns the status and nothing more").

`is_allowed_origin(origin)`:
- null/blank → allowed (non-browser clients send none).
- lower-case+trim; `"null"` → refused (sandboxed iframe / `file:` page).
- scheme must be `http://` or `https://`, else refused (`file://localhost`, `chrome-extension://…`, schemeless `localhost:3000` all refused).
- host-of-authority: if it starts with `[` take up to and including the first `]` (IPv6 literal; naive split on `:` would truncate `[::1]` to `[`); else up to the first `:`; else whole remainder.
- allowed iff host ∈ {`localhost`, `127.0.0.1`, `[::1]`, `::1`}.
- Test vectors: allowed `http://localhost:3000`, `http://127.0.0.1:8080`, `http://localhost`, `https://127.0.0.1:443`, `HTTP://LOCALHOST:3000`, `http://[::1]:3000`, `http://[::1]`; refused `http://evil.example`, `https://rebind.attacker.test:8080`, `null`, `NULL`, `http://localhost.evil.example`, `http://127.0.0.1.evil.example`, `http://notlocalhost`, `http://evil.example/?x=localhost`, `file://localhost`, `chrome-extension://abcdef`, `localhost:3000`.

`bearer_token_of(header)`: trim; must be ≥7 chars with case-insensitive prefix `"Bearer "`; token = rest trimmed; empty → None. Vectors: `"Bearer X"`, `"bearer X"`, `"  Bearer   X  "` → X; `null`, `""`, `"Basic …"`, `"Bearer"`, `"Bearer "`, `"X"` → None.

`tokens_match(expected, presented)`: false if either missing or expected empty; else constant-time compare of UTF-8 bytes (Java `MessageDigest.isEqual`: constant-time in content, length difference distinguishable). Rust: `subtle::ConstantTimeEq` on byte slices (it returns false on length mismatch without leaking content) or `constant_time_eq`.

`is_json_content_type(ct)`: trim+lower; strip from first `;`, trim; equals `application/json`. Vectors: accepted `application/json`, `application/json; charset=utf-8`, `  APPLICATION/JSON  `; refused null, `text/plain`, `application/x-www-form-urlencoded`, `application/jsonx`, `multipart/form-data` (the CORS "simple request" types are refused so a page cannot send without a preflight).

`is_loopback(addr)`: null → false. Rust: `ip.to_canonical().is_loopback()` (so IPv4-mapped `::ffff:127.0.0.1` counts, matching Java which canonicalizes mapped addresses).

### 3.3 Serving one exchange

1. Build guard request; verdict ≠ ALLOW → if PAYLOAD_TOO_LARGE, **drain** (read and discard) up to `DRAIN_LIMIT = 4 MiB` of body first (only reachable by authenticated callers since size is checked after auth) so the client receives the 413 instead of a TCP reset; send status with no body; done.
2. `DELETE`: header `Mcp-Session-Id` missing/blank → 400; else `session_closer(id)` → 204 if a live session was removed, else 404. Empty bodies.
3. Read body capped: read up to `MAX_BODY_BYTES + 1`; if more than the cap (chunked bodies declare no length, so re-check while reading) → drain up to 4 MiB, respond 413. Decode as UTF-8 (Java `new String(bytes, UTF_8)` replaces invalid sequences with U+FFFD; use `String::from_utf8_lossy`).
4. `response = body_handler(body, header("Mcp-Session-Id"))`.
5. If `response` is None or `response.json` is None (a notification): send **202** with no body when status is 200 (or no response), otherwise send the refusal status (400/404) with no body. No session header in this case.
6. Else add `Mcp-Session-Id: <id>` header if `response.session_id` is set; send `response.status` with `Content-Type: application/json; charset=utf-8` and the compact JSON body.
7. Any unexpected error → log error `log.mcpTransport.serveFailed` + error, send 500 empty. Always close the exchange.

`Response { json: Option<Value>, session_id: Option<String>, status: u16 /*default 200*/ }`.

### 3.4 Session resolution and serialization (`McpServer::dispatch(body, session_id_header)`)

1. `parse(body)`; on error → `Response(error_json, None, 200)` (parse errors are HTTP 200 with a JSON-RPC error).
2. `now = current ms`; `has_sid = header present and not blank`; `is_init = method == "initialize"`.
3. `session = if has_sid { sessions.find(sid, now) /*expires idle on lookup*/ } else { None }`.
4. If no session:
   - `is_init` → `sessions.create(now)` (also covers re-initializing after a 404, which the transport spec mandates).
   - else if `has_sid` → **404**, body `-32001 "Unknown or expired MCP session; send initialize to start a new one"` (None body if the message is a notification).
   - else if method is `ping` → a throwaway, **unregistered** `McpSession("", now)` (liveness needs no handshake and creates nothing).
   - else → **400**, body `-32600 "Missing Mcp-Session-Id header; send initialize first"` (None body for notifications).
5. If `requires_serial_dispatch(method)` (exactly `tools/call` and `resources/read`): submit `handler.handle(msg, session, now)` to the single-threaded dispatcher named `mcp-dispatch` and **block** for the result. If the dispatcher is gone (stopping) → `-32603 "The MCP server is stopping"`. Every other method (initialize, ping, listings, notifications) runs **inline on the HTTP thread**, so a call waiting up to 120 s on a consent dialog never stalls pings, listings, or other clients' handshakes (pinned by `aPendingConsentDoesNotBlockUnrelatedRequests`).
6. Interrupted wait → `-32603 "Request was interrupted"`; handler panic/exception → log `log.mcpServer.dispatchFailed` + error, `-32603 "Request failed"`.
7. Echo the session id **only on `initialize`** (`Response(json, is_init ? session.id : None)`).

Why serialized: the Java `GameModPackManager` is `static synchronized` and several pack operations are unsafe to interleave; serialization makes concurrent calls well-defined. Throughput is irrelevant here.

Edge case (Java behaviour, flag for the port): an `initialize` sent as a **notification** (no id) still creates and registers a session (step 4) whose id is never returned (step 5 of 3.3 sends 202 without the header). It is orphaned until idle expiry and consumes one of the 8 slots. Recommended Rust fix: only create a session for an `initialize` that has an id; answer an id-less `initialize` with 202 and register nothing.

### 3.5 `McpServer` lifecycle

- Fields: `McpSessionRegistry`, `McpRequestHandler`, transport, dispatcher, token, port, endpoint path. All lifecycle methods mutually exclusive.
- `start(requested_port, endpoint_file: Option<Path>, bearer_token)`:
  - already running → error.
  - token blank → generate one (test path); else use the persisted one.
  - create transport (token, `dispatch`, `sessions.remove` as the DELETE closer) and the serial dispatcher.
  - bind `requested_port`; on failure log warning `log.mcpServer.portUnavailable` (port) and **bind port 0** instead (a busy port degrades to "reconfigure your HTTP client"; the relay picks the new port up from the endpoint file). If that also fails → tear everything down (no half-started server, no token/executor left alive) and return the error.
  - write the endpoint file (if a path was given; tests pass None) with `{port, token, pid: current pid, launcherVersion}`; failure → `stop()` and return error.
  - log `log.mcpServer.listening` (port). Return bound port.
- `stop()`: delete endpoint file (if written), stop transport, shut down dispatcher (`shutdownNow`: interrupts a running call; in Rust drop the job channel and let the thread finish/abandon), clear token, `sessions.clear()`, port = 0, log `log.mcpServer.stopped`. Idempotent.
- `is_running()`, `port()`, `token()` (in-process only: endpoint file and tests; must never reach a tool result/resource/error/log), `sessions()` (runs `evict_idle(now)` first so departed clients disappear without waiting for a newcomer), `activity_log()`.
- `requires_serial_dispatch(method)` is a public/testable pure fn.

### 3.6 Sessions (`McpSession`, `McpSessionRegistry`)

`McpSession`:
- `id: String` (launcher-assigned), `connected_at_ms`, `call_count: AtomicU64`, `last_activity_ms: AtomicU64` (initialized to connected time), `client_name`, `client_version` (both `""` before handshake; untrusted display text — but `client_name` is half of every grant key), `initialized: bool`.
- `initialize(name, version) -> bool` (mutex): if already initialized → false, unchanged; else set (null → `""`), mark initialized, true.
- `record_activity(now, was_tool_call)`: set last activity; increment count if tool call.
- `to_call_context()` → `McpCallContext{client_name, session_id}`.
- Holds **no credential** (it is passed into tool code).

`McpSessionRegistry`:
- `MAX_SESSIONS = 8`; `IDLE_TIMEOUT_MS = 30 min`.
- ids: 16 random bytes (CSPRNG) → 32 lower-case hex chars (unguessable, so a client cannot adopt another's handshake). (The guide's example shows a UUID-like id; it is wrong.)
- `create(now)` (mutex): `evict_idle(now)`; then while `len >= 8` remove the session with the smallest `last_activity_ms` (**evict least-recently-active rather than refuse the newcomer**: an evicted client gets 404 and re-initializes per spec; refusal would lock every new client out once relay restarts filled the slots). Insert and return.
- `find(id)` (no idleness check); `find(id, now)`: if idle (`now - last_activity >= IDLE_TIMEOUT_MS`) remove (only if still the same instance) and return None.
- `remove(id) -> bool`, `evict_idle(now) -> count`, `clear()`, `all()` snapshot, `len()`.

---

## 4. Approval system (`approval/`)

### 4.1 Types

`McpRiskClass` (each tool declares exactly one):

| Class | Default policy | Meaning | Localized label key |
|---|---|---|---|
| `READ_ONLY` | `ALWAYS_ALLOW` (subject to auto-approve toggle) | lists, reads, diagnostics | `mcp.risk.readOnly` ("read-only") |
| `MUTATING` | `ASK` | recoverable state change: install, create, fork, add/remove mod | `mcp.risk.mutating` ("makes changes") |
| `DESTRUCTIVE` | `ASK` | deletes/overwrites user data: uninstall | `mcp.risk.destructive` ("destructive") |
| `EXECUTE` | `ASK` | runs third-party code / processes: launch, stop | `mcp.risk.execute` ("runs code") |

`mutates_state()` = not READ_ONLY. `default_policy()` as above (the engine does not actually consult `default_policy`; it encodes the same table — test `theDeclaredDefaultsMatchWhatTheEngineDoes` keeps them in sync).

`McpApprovalPolicy`: `ALWAYS_ALLOW | ASK | DISABLED` (user-set per tool; "Default" in the UI = no stored policy).

`McpApprovalDecision`: `ALLOW | PROMPT | DENY`.

`Answer` (consent result): `DENY | ALLOW_ONCE | ALLOW_FOR_SESSION`.

### 4.2 `McpApprovalEngine::resolve(Request) -> Decision` (pure)

`Request { risk_class, explicit_policy: Option<Policy>, server_enabled, auto_approve_read_only, gui_available, grant_expires_at_ms /*0 = none*/, now_ms }`. Missing request or risk class → programming error (Java throws IllegalArgumentException).

1. `!server_enabled` → DENY (defence in depth; unreachable in practice).
2. explicit `DISABLED` → DENY.
3. explicit `ALWAYS_ALLOW` → ALLOW.
4. explicit `ASK` → `gui ? PROMPT : DENY` (**a session grant does not satisfy ASK**).
5. unexpired grant (`grant > 0 && grant > now`; expiring exactly now = expired) → ALLOW.
6. `READ_ONLY && auto_approve_read_only` → ALLOW.
7. else `gui ? PROMPT : DENY` (no GUI never self-approves).

Consequences pinned by tests: a grant cannot override DISABLED or ASK; turning auto-approve off does not undo an explicit ALWAYS_ALLOW on a read-only tool; a read-only tool still runs headless with auto-approve on; a live grant still allows with no GUI; mutating/destructive/execute are never allowed without an explicit ALWAYS_ALLOW or a grant.

`DEFAULT_GRANT_TTL_MS = 8 h`.

### 4.3 `LauncherMcpAuthorizer::authorize(tool, ctx, args) -> bool` (production `McpAuthorizer`)

Collaborators (all required, injected for tests): `Settings { server_enabled(), auto_approve_read_only(), policy_for(tool_name) -> Option<Policy> }`, `McpGrantStore`, `ConsentPrompt { is_available(), ask(tool, ctx, args, offer_session_grant) -> Answer }`, clock.

1. Missing tool/ctx → false.
2. Gather inputs (now, explicit policy, settings, `prompt.is_available()`, `grants.expiry_for(ctx.client_name, tool.name)`). **Any error → log error `log.mcpAuthorizer.resolveFailed` + error, false.**
3. `resolve`. ALLOW → true. DENY → log `log.mcpAuthorizer.denied` (client, tool), false.
4. PROMPT: `offer_session_grant = explicit_policy != Some(ASK)`. `prompt.ask(...)`; error → log error `log.mcpConsent.promptFailed`, false; None → false.
5. If `ALLOW_FOR_SESSION && offer_session_grant` → `grants.grant(client, tool, now + 8h)` (an ALLOW_FOR_SESSION that was not offered is treated as allow-once).
6. `allowed = answer != DENY`; log `log.mcpAuthorizer.allowedAnswer` / `deniedAnswer` (client, tool, answer). Return.

### 4.4 `McpGrantStore` (in-memory, per run)

- Map `key -> expires_at_ms` (concurrent). Key = `normalize(client) + '\0' + normalize(tool)`; normalize = trim + lower-case (ROOT); blank → unusable (grant ignored, expiry 0). NUL separator because a printable one is ambiguous (`"a b"`+`c` vs `"a"`+`"b c"`); tool names cannot contain NUL.
- `grant`, `expiry_for -> 0 if none`, `purge_expired(now)` (remove `<= now`), `revoke_all()`, `revoke_client(name)` (prefix `name\0`), `live_grants(now) -> Vec<Grant{client(normalized), tool(normalized), expires_at}>` (skip `<= now`), `len()` (incl. expired).
- Keyed to client name, not connection: MCP clients reconnect often; connection-scoped grants would re-prompt constantly and train click-through. Note `McpCallContext` coalesces a blank client name to `"Unknown client"`, so all nameless clients share grants — an accepted consequence of untrusted names.

### 4.5 `McpToolPolicyStore` (durable per-tool policy)

- Backing `{ read() -> String, write(String) }` → config `mcpToolPolicies`.
- `policy_for(tool)`: normalize (trim+lower; blank → None); `load()[key]`.
- `set_policy(tool, Option<Policy>)`: None clears; save.
- `all()` → sorted map. `clear()` → save `{}`.
- `load()`: **every failure is "unset", never a policy** — backing read throws, blank, malformed JSON, non-object root, non-primitive value, unknown policy name (e.g. from a newer build) → that entry (or everything) is absent. Policy parse = trim + upper-case + enum match. One bad entry does not discard good ones. Keys normalized on load.
- `save()`: JSON object sorted by key, values = enum names (`"ALWAYS_ALLOW"`, …), compact; a backing write error is swallowed.
- Rationale: a corrupted config must degrade to "ask", never silently grant standing permission to delete modpacks.

### 4.6 Consent prompt (`FxConsentPrompt` → GPUI `ConsentDialog`)

Contract (implement in the app crate against `ConsentPrompt` trait):
- `is_available()`: true iff a top-level launcher window exists (Java `MCLauncherGuiController.getTopStageOrNull() != null`); any error → false (→ caller denies).
- `ask(tool, ctx, args, offer_session_grant)`: tool/ctx missing or unavailable → DENY. Show the dialog **from the GUI thread**; the dispatch thread blocks on the answer with a **120 s** deadline (`CONSENT_TIMEOUT_SECONDS`). Timeout → log `log.mcpConsent.promptTimedOut` (tool), DENY. Interrupted → DENY. Any error → log error `log.mcpConsent.promptFailed`, DENY.
- Java limitation: the timed-out dialog stays on screen (the helper cannot be dismissed). **Rust should close/invalidate the dialog on timeout** (answer is already DENY).
- Buttons: button 1 `dialog.mcp.consent.button.allowOnce` ("Allow once") → ALLOW_ONCE; button 2 `dialog.mcp.consent.button.allowSession` ("Allow for this session") → ALLOW_FOR_SESSION — **only when `offer_session_grant`**; otherwise the second button is the plain Cancel. Cancel, close, Escape → DENY (the answer that needs no decision is the safe one). No "Always allow permanently" button by design (that is a Settings policy).
- Text (localized):
  - title `dialog.mcp.consent.title` = "MCP tool approval"
  - header `dialog.mcp.consent.header` = `{0} wants to use "{1}"` (client name, `tool.display_title()`)
  - body `dialog.mcp.consent.body` = `The connected MCP client "{0}" is asking to run the tool {1}.\n\n{2}\n\nArguments: {3}` (client, tool wire name, `tool.display_description()`, detailed arguments)
  - `dialog.mcp.consent.noArguments` = "(none)"
- Detailed arguments = `summarize_arguments(args)` + (if `tool.consent_detail(args)` is non-blank) `"\n\n" + detail`. A `consent_detail` error → warning `log.mcpConsent.detailFailed`, no detail (the user is still asked).
- `summarize_arguments(args)`: null/empty object → "(none)"; else iterate keys in insertion order, at most **6** rendered (`MAX_SUMMARIZED_ARGUMENTS`); after 6 append `", …"` and stop; separator `", "`; each `key=value` where value renders: JSON null → `null`; array → `[N items]`; object → `{object}`; primitive → string form truncated to **120** chars + `…` if longer.
- Java then passes the body through the dialog helper's single-line sanitizer: base `redact()` (not strict), all `\r`/`\n` → space, whitespace runs squeezed, trimmed, capped at **600** chars (`599 chars + "…"`). The Rust GPUI dialog may lay out client/tool/description/arguments/detail as separate blocks (better), but must keep: redaction of the displayed text, the 6×120 argument bound, and a total length bound. Model-chosen strings are untrusted display text: render as plain text, never markup.
- Dialog is window-modal to the top launcher window.

---

## 5. Tools (`tools/`)

### 5.1 Tool trait (`McpTool`)

- `name()` — wire name; must match `^[a-z0-9](?:[a-z0-9_-]{0,62}[a-z0-9])?$` (1–64 chars, lower-case alnum, `_`/`-` inside only).
- `title()` — English protocol title (sent to clients).
- `display_title()` — localized `mcp.tool.<name>.title`, fallback `title()` (consent dialog + Settings).
- `description()` — English, for the model.
- `display_description()` — localized `mcp.tool.<name>.description`, fallback `description()`.
- `input_schema() -> Value` (JSON Schema object).
- `risk_class()`.
- `invoke(ctx, args) -> Result<McpToolResult, Error>`: an ordinary failure returns `McpToolResult::error(msg)`; `Err` only for unexpected failures (converted to `-32603` without leaking the message).
- `validate_before_approval(args) -> Option<String>` (default None): content gate run **before** approval/consent; a refusal never becomes a prompt.
- `consent_detail(args) -> Option<String>` (default None): extra dialog line (e.g. footprint); must be bounded/non-blocking.
- `describe()` → `{"name","title","description","inputSchema"}` in that order.
- Must be safe off the GUI thread and never block indefinitely (dispatch is serialized).

`McpToolRegistry`: insertion-ordered map. `register` errors: null tool, invalid name, missing risk class (all "illegal argument"); duplicate name ("illegal state"). `find(name)` (exact, case-sensitive), `all()`, `len()`, `list_result()` → `{"tools":[describe()…]}` (empty array, never omitted).

`McpCallContext { client_name, session_id }`: blank/null client name → `"Unknown client"`; null session id → `""`. Holds no credential.

### 5.2 `McpToolResult` — **the redaction boundary for tools**

- `text(s)` (null → ""), `json(value)` (null → `{}`; serialized **pretty** — 2-space indent — into a single text block), `error(msg)` (`isError: true`), `texts(blocks)` (null list → zero blocks; null entries → "").
- `is_error()`, `raw_text_blocks()` (unredacted; in-process/tests only).
- `to_json()` → `{"content":[{"type":"text","text": redact_strict(block)}…],"isError":bool}`. **Every block is redacted here**, so no tool — including one written later — can emit unredacted text. A tool error is a *successful* JSON-RPC result with `isError: true`, not a protocol error.

### 5.3 `tools/call` handling (in `McpRequestHandler`)

1. `name = read_string(params,"name")`; empty → `-32602 "Missing tool name"`.
2. `tool = registry.find(name)`; None → `-32602 "Unknown tool: <name>"` (this is what a disabled-by-second-opt-in mutating tool returns).
3. `args = params.arguments` if object else `{}`. `ctx = session.to_call_context()`.
4. Content gate: `tool.validate_before_approval(args)`; if it errors → log error `log.mcpRequestHandler.preApprovalFailed` + error, rejection = `"The request could not be validated."`. If rejection → log `log.mcpRequestHandler.refusedBeforeApproval` (tool, reason), activity `REFUSED` with the reason, return `-32602 <rejection>`.
5. Approval: `authorizer.authorize(tool, ctx, args)`; error → log `log.mcpRequestHandler.toolApprovalFailed`, false. Denied → activity `DENIED` detail `"not approved"`, return `-32003 "Tool call was not approved: <name>"`.
6. Invoke: result None → treated as failed; activity `FAILED` (detail = first raw text block if any, else "") or `ALLOWED` (detail ""); return `result(to_json())` (None → `McpToolResult::error("Tool returned no result").to_json()`).
7. Invoke error → log error `log.mcpRequestHandler.toolFailed` + stack trace; activity `FAILED` detail `"the tool threw"` (exception text never recorded: it carries paths/command lines); return `-32603 "Tool failed: <name>"`.

### 5.4 The launcher seams (traits other subsystems implement)

`McpLauncherView` — the *only* read surface tools/resources get. Its records have **no field** for a token, account UUID or filesystem path; that is the primary credential control.

```text
PackSummary  { friendly_name, version, latest_version, update_available, mod_loader, installed, unstable }
LauncherStatus { launcher_version, signed_in, username, installed_pack_count }   // username only
CrashInfo    { report_text, title, summary, category, suggestions: Vec<String> }
PackFootprint{ size_bytes: u64, world_count: u32, approximate: bool }
RunningGame  { friendly_name, account /*username or "" for server*/, phase /*"preparing"|"running"*/, uptime_seconds }

fn packs() -> Vec<PackSummary>
fn pack(friendly_name) -> Option<PackSummary>
fn manifest_of(friendly_name) -> Option<String>
fn latest_crash_of(friendly_name) -> Option<CrashInfo>
fn footprint_of(friendly_name) -> Option<PackFootprint>   // bounded work
fn status() -> LauncherStatus
fn running_games() -> Vec<RunningGame>                    // oldest first
```

`McpLauncherActions` — the only write surface; implementations must not throw for ordinary failures (return `Outcome{ok:false,message}`) and must not block indefinitely:

```text
Outcome { ok: bool, message: String }
install_from_url(url) -> Outcome
uninstall(friendly_name) -> Outcome
launch(friendly_name, account_username: Option) -> Outcome      // "started", not "finished loading"
why_launch_blocked(friendly_name, account: Option) -> Option<String>
stop_game(friendly_name) -> Outcome
create_pack(name, mod_loader: Option, mod_loader_url: Option) -> Outcome
fork_pack(source_friendly_name, new_name) -> Outcome
add_mod(friendly_name, mod_name, remote_url, local_path) -> Outcome
remove_mod(friendly_name, identifier) -> Outcome
```

A tool holding only the view cannot change anything — "is this tool read-only?" is a question about its dependencies.

### 5.5 Read-only tools (always registered; all `READ_ONLY`)

Conventions: string args are read with "absent/non-primitive → empty", then **trimmed**. `unknownPack(n)` = `No modpack named "<n>". Call list_modpacks to see the available friendly names.` Schemas:
- no-args schema: `{"type":"object","properties":{}}`
- pack-name schema: `{"type":"object","properties":{"friendlyName":{"type":"string","description":"The modpack's friendly (display) name."}},"required":["friendlyName"]}`

Pack JSON entry (used by list, info, and the `mica://packs` resource), field order: `friendlyName, version, latestVersion, updateAvailable, modLoader, installed, unstable`.

| # | name | title | args | output (pretty JSON text unless noted) | errors (`isError`) |
|---|---|---|---|---|---|
| 1 | `list_modpacks` | List modpacks | none | `{"modpacks":[entry…],"count":N}` (empty library is success) | — |
| 2 | `get_modpack_info` | Get modpack info | friendlyName | one entry object | `A friendlyName argument is required.`; unknownPack |
| 3 | `get_modpack_manifest` | Get modpack manifest | friendlyName | **raw manifest text** (verbatim, not re-serialized) | required msg; `No manifest is available for modpack "<n>". It may not exist, or its manifest may be unreachable.` |
| 4 | `get_crash_report` | Get crash report | friendlyName | **raw report text** | required msg; `No crash report is available for modpack "<n>".` (also when blank) |
| 5 | `diagnose_launch_failure` | Diagnose launch failure | friendlyName | `{friendlyName, version, modLoader, unstable, crashReportAvailable:false, note:"No crash report is on record for this modpack."}` **as success** when no crash; else `{friendlyName, version, modLoader, unstable, crashReportAvailable:true, category, title, summary, suggestions:[..] (null list → []), crashReport}` | required msg; unknownPack |
| 6 | `get_launcher_status` | Get launcher status | none | `{launcherVersion, signedIn, username, installedModpackCount}` | — |
| 7 | `list_running_games` | List running games | none | `{"games":[{friendlyName, account, phase, uptimeSeconds}…]}` | — |

Descriptions (English, for the model) — port verbatim:
1. "Lists every modpack the launcher knows about, both installed and available to install. Returns each pack's friendly name, installed version, latest published version, whether an update is available, mod loader, install state, and whether its author flagged it unstable. Use the friendly name to address a pack in other tools."
2. "Reports the installed version, the latest published version, whether an update is available, the mod loader, install state and stability flag of one modpack, addressed by its friendly name."
3. "Returns the raw modpack manifest JSON for one pack: its mod, config, resource pack and shader lists, mod loader URLs, and metadata."
4. "Returns the most recent crash report for one modpack, as raw text. Credentials are stripped from the output."
5. "Explains why a modpack failed to launch or crashed. Returns the launcher's own diagnosis (category, summary, suggested fixes) together with the raw crash report and the pack's version and mod loader, so the report can be read in context. Credentials are stripped from the output. Start here for any 'why did my game crash' question."
6. "Reports the launcher version, whether an account is signed in and its username, and how many modpacks are installed."
7. "Lists the games that are launching or running: the modpack, the account playing it (username), whether it is still preparing or running, and how long it has run in seconds."

`diagnose_launch_failure` reports "not crashed" as success on purpose: an error would push the model to retry pointlessly.

### 5.6 State-changing tools (registered only when `mcpAllowStateChanges`)

Shared helpers: `string_arg` (absent/non-primitive → "", trimmed); `schema_of(name, desc, …)` → all-string properties, all required; `result_of(outcome)` → None → error `The operation reported no outcome.`; ok → `text(msg)`; else `error(msg)`.

`new_pack_name_rejection(name)`: `key = local_manifest_key_of(name)` (letters+digits only, lower-cased: `name.replace(/[^a-zA-Z0-9]/,"").to_lower()`). Empty → `The name "<n>" has no letters or digits. Pick a name with at least one.` For each known pack (installed and available) whose key equals: exact same name → `A modpack named "<n>" already exists. Pick a different name.`; else `"<n>" is too close to the existing modpack "<existing>": names that differ only in case, spacing or punctuation would share a manifest file. Pick a more distinct name.` (Because the authored manifest filename is `created-<key>.json`, "My Pack" and "my-pack" would clobber each other.)

`classify_install_url(url)` (owned by the URI-handler subsystem; spec here for completeness): blank → REJECT; any char `< 0x20` or `== 0x7F` → REJECT; not a parseable URI → REJECT; scheme ≠ `https` (case-insensitive) → REJECT; no/blank host → REJECT; host (lower-case) in trusted allowlist `{ "micauseaststorage.blob.core.windows.net" }` → ACCEPT_TRUSTED; else REQUIRE_CONFIRMATION. The MCP tools refuse only on **REJECT**; REQUIRE_CONFIRMATION proceeds to consent (an untrusted but well-formed host is exactly what the consent dialog is for).

| name | title | risk | args (schema) | content gate (in order) | consent detail | invoke |
|---|---|---|---|---|---|---|
| `install_modpack` | Install modpack | MUTATING | `url` (req) "The https URL of the modpack manifest to install." | empty → `A url argument is required.`; REJECT → `That install URL was refused by the launcher's install-source rules. Modpack URLs must be well-formed https links.` | — | `install_from_url(url)` (synchronous on dispatch thread; reply means finished) |
| `create_modpack` | Create modpack | MUTATING | `name` (req) "The new modpack's name."; `modLoader` (opt) "Mod loader: forge, neoforge, or fabric. Defaults to forge."; `modLoaderUrl` (opt) "URL of the mod loader installer." (`required: ["name"]`) | empty name → `A name argument is required.`; non-empty `modLoaderUrl` with REJECT → `That mod loader URL was refused by the launcher's install-source rules. Mod loader URLs must be well-formed https links.`; then `new_pack_name_rejection(name)` | — | `create_pack(name, empty→None, empty→None)` |
| `fork_modpack` | Fork modpack | MUTATING | `friendlyName` "The modpack to copy.", `newName` "The name for the copy." | either empty → `Both friendlyName and newName are required.`; source unknown → unknownPack; `new_pack_name_rejection(newName)` | — | `fork_pack` |
| `add_mod_to_modpack` | Add mod to modpack | MUTATING | `friendlyName` "The modpack to edit.", `modName` "The mod's display name.", `remoteUrl` "The https URL to download the mod from.", `localPath` "Where it installs, relative to the pack root, e.g. mods/jei.jar" | any empty → `friendlyName, modName, remoteUrl and localPath are all required.`; pack unknown → unknownPack; remoteUrl REJECT → `That mod URL was refused by the launcher's install-source rules. Mod URLs must be well-formed https links.`; `reject_unsafe_local_path(localPath)` | — | `add_mod` |
| `remove_mod_from_modpack` | Remove mod from modpack | MUTATING | `friendlyName` "The modpack to edit.", `mod` "The mod's local path, display name, or Modrinth slug." | either empty → `Both friendlyName and mod are required.`; pack unknown → unknownPack | — | `remove_mod` |
| `uninstall_modpack` | Uninstall modpack | DESTRUCTIVE | `friendlyName` "The modpack to uninstall." | empty → `A friendlyName argument is required.`; unknown → unknownPack; running (case-insensitive match in `running_games`) → `"<n>" is running. Stop it with stop_game before uninstalling it.` | footprint: None → `This permanently deletes the modpack's files.`; else `This permanently deletes ` + (`at least ` if approximate) + `describe_size(bytes)` + (if worlds>0: ` including N saved world`/`saved worlds`) + `. This cannot be undone.` | `uninstall` |
| `launch_modpack` | Launch modpack | EXECUTE | `friendlyName` (req) "The modpack to launch."; `account` (opt) "Optional username of a signed-in account to play as." | empty → required msg; unknown → unknownPack; not installed → `"<n>" is not installed. Install it before launching.`; `!status().signed_in` → `No account is signed in. Sign in through the launcher before launching a modpack.`; then `actions.why_launch_blocked(pack, account empty→None)` | — | `launch(pack, account empty→None)` |
| `stop_game` | Stop game | EXECUTE | `friendlyName` (opt) "The modpack whose game to stop. Optional when only one game is running." (no `required`) | `stop_problem(running, named)` | target found → `This stops "<target>".` | resolve target again; None → `error(stop_problem(...))`; else `stop_game(target)` |

Descriptions — port verbatim:
- install: "Installs a modpack from a manifest URL. The URL must be https and is checked against the launcher's install-source rules before the user is asked to approve. Downloads mods and configuration from third-party hosts."
- create: "Creates a new, empty modpack with the given name and installs it locally, ready to have mods added. Optionally sets the mod loader and its installer URL."
- fork: "Copies an existing modpack under a new name and installs the copy, so it can be edited without touching the original. The copy keeps every mod, config and setting, with its minor version bumped."
- add mod: "Adds a mod to a modpack's manifest, to be downloaded on the next sync. The remote URL must be https."
- remove mod: "Removes a mod from a modpack's manifest, identified by its local path, display name, or Modrinth slug. The file is removed on the next sync."
- uninstall: "Permanently removes a modpack and deletes its files, including any worlds saved inside it. This cannot be undone."
- launch: "Launches a modpack, starting Minecraft. It plays as the modpack's usual account (its own setting, else the default account) unless an account is named. Several games can run at once, one per modpack and one per account. This runs third-party mod code."
- stop: "Stops a running game (or cancels one still preparing). Name the modpack when several games are running; list_running_games shows them. Unsaved progress since the last autosave is lost."

`stop_game` helpers (pure, tested):
- `target_of(running, named)`: named non-empty → first game whose name equals ignoring case → its canonical name, else None; unnamed → the single game if exactly one, else None.
- `stop_problem(running, named)`: none running → `No game is currently running.`; target found → None; else names = `"a", "b"`; named → `"<named>" is not running. Running now: <names>.`; unnamed → `Several games are running (<names>). Name the one to stop with friendlyName.`

`reject_unsafe_local_path(p)`: blank → `A localPath argument is required.`; trimmed starts with `/` or `\` or `p[1] == ':'` (drive letter) → `localPath must be relative to the modpack folder, not an absolute path.`; any `/`- or `\`-separated segment equal to `..` → `localPath must stay inside the modpack folder.`; any NUL or ISO control char (U+0000–U+001F, U+007F–U+009F) → `localPath contains an illegal character.`; else None. (Optional Rust hardening, not in Java: refuse `:` anywhere — NTFS alternate data streams — and Windows reserved device names. Contained either way, so low priority.)

`describe_size(bytes)`: `< 1024` → `"<n> bytes"`; else divide by 1024 into KB, and keep dividing while `>= 1024` up to TB; format `"%.1f <unit>"` if value < 10 else `"%.0f <unit>"`. Vectors: 512→`512 bytes`, 0→`0 bytes`, 1024→`1.0 KB`, 1 MiB→`1.0 MB`, 4,509,715,660→`4.2 GB`, 512 MiB→`512 MB`. Rounding: Java `Formatter` is HALF_UP on the exact decimal; Rust `format!("{:.1}")` rounds ties to even on the exact binary value — differs only on exact ties (e.g. 1280 bytes = 1.25 KB: Java `1.3 KB`, Rust `1.2 KB`). Implement HALF_UP manually if byte-exact parity matters. Known quirk: 10239 bytes ≈ 9.999 KB → `10.0 KB` (one decimal past 10); keep or fix, cosmetic.

`MutatingTools::tool_names()` = `[install_modpack, create_modpack, fork_modpack, add_mod_to_modpack, remove_mod_from_modpack, uninstall_modpack, launch_modpack, stop_game]` — registration order for read-only is `list_modpacks, get_modpack_info, get_modpack_manifest, get_crash_report, diagnose_launch_failure, get_launcher_status, list_running_games`; mutating are appended after in the order above. `tools/list` order = registration order.

### 5.7 Live view (`LiveMcpLauncherView`) — app-crate adapter, behaviour to preserve

- `packs()`: installed list first, then available; key by friendly name, **first wins** (installed shadows available). Each pack summarized; any pack whose metadata throws or has a blank name is skipped (one bad pack never fails a listing); a list fetch error → warning (`log.mcpView.listInstalledFailed` / `listAvailableFailed`) and empty list.
  - installed: `version = first_non_blank(installedVersion, packVersion)`; `latest = packVersion`; `update_available = pack.isUpdateAvailable()` (errors → false).
  - available: `version = packVersion`, `latest = packVersion`, `update_available = false`.
  - `mod_loader = modLoaderType or ""`, `unstable = packUnstable`.
  - Both versions are reported because a friendly name like `Alto: 26.9.3` embeds the *published* version while `26.6.12` is installed (found in a live run).
- `pack(name)`: exact (case-sensitive) match over `packs()`.
- `manifest_of(name)`: find pack (installed, then available, exact name); `ModpackExporter.loadManifestText(pack)`; error → warning `log.mcpView.manifestLoadFailed`, None.
- `latest_crash_of(name)`: `pack.getLatestCrashReport()` (error → warning `crashReportReadFailed`, None; blank → None); diagnose with `CrashReportAnalyzer.analyze(report, pack, exitCode = 1)` (exit code is not recorded with the stored report; 1 is an honest generic failure); suggestions → **labels only** (a Suggestion also carries an action closure that is meaningless across MCP); category → enum name or ""; diagnosis error → warning `crashDiagnoseFailed`, return the report with empty diagnosis.
- `footprint_of(name)`: pack root folder; not a dir → `{0,0,false}`; else bounded walk: DFS with an explicit stack, **max 40,000 entries visited** and **1,500 ms wall-clock budget** (checked before each directory pop; exceeding → approximate=true, stop); **symlinks are not followed** (skipped entirely); directories are pushed; a directory whose parent is `<root>/saves` counts as one world; file sizes summed; an unreadable directory → approximate=true and continue. Error → warning `footprintFailed`, None.
- `running_games()`: from the game-session registry's active sessions: `{packName or "", accountName or "", phase RUNNING→"running" else "preparing", uptime = running && startedMs>0 ? (now-startedMs)/1000 : 0}`.
- `status()`: `{launcher version, signed_in = logged-in user exists, username = user.name() ONLY (never uuid/accessToken), installed_count = installed list size}`; account read error → warning `accountReadFailed`, signed out.

### 5.8 Live actions (`LiveMcpLauncherActions`) — app-crate adapter, behaviour to preserve

Messages are model-facing and must not contain exception text.

- `find_pack` here searches **installed packs only**, exact name.
- `install_from_url(url)`: `GameModPackManager.installModPackByURL(url)` → ok `Installed the modpack from <url>.`; error → log `log.mcpActions.installFailed`, failed `The install failed. The URL may not point at a valid modpack manifest, or the download may have been interrupted.`
- `uninstall(n)`: not installed → `No modpack named "<n>" is installed.`; `uninstallModPackByFriendlyName(n)` → `Uninstalled "<n>" and deleted its files.`; error → log, `The uninstall failed. Some files may still be on disk.`
- `launch(n, account)`: not installed → `No modpack named "<n>" is installed.`; account given → resolve uuid by **case-insensitive, trimmed** match on signed-in accounts' display names; none → `No signed-in account is named "<account>".`; then **spawn a background task** running `LauncherCore.playAs(pack, uuid_or_None)` (play blocks for the whole prepare; must not hold the single dispatch thread; a failure inside logs `log.mcpActions.launchFailed`); return ok `Started launching "<n>". Its progress shows in the launcher's Running Games window; call list_running_games to follow it.`; spawn error → `The launch could not be started.`
- `why_launch_blocked(n, account)`: pack not installed → None (tool already reported); pack already active in the session registry → `"<n>" is already running. Stop it with stop_game first.`; named account not found → `No signed-in account is named "<a>". get_launcher_status names the default account.`; unnamed → `LaunchAccountResolver.resolve(config.accountOverrideForPack(pack.settingsKey), accounts)`; not ok → `NO_ACCOUNT`: `No account is signed in. Sign in through the launcher first.`; `OVERRIDE_MISSING`: `This modpack is set to launch as an account that is no longer signed in. Change it in the launcher, or pass an account.`; `NEEDS_SIGN_IN`: `The account this modpack launches as has to sign in again in the launcher.`; then if any active session has that account uuid → `That account is already playing "<pack>". An account can play one game at a time; pass a different account or stop that game.`; else None.
- `stop_game(n)`: first active session with pack name equal ignoring case: PREPARING → `cancel()`, ok `Cancelled the launch of "<pack>".`; else `stop(false)` (process destroy), ok `Stopped "<pack>".`; none → `"<n>" is not running.`; error → log `stopGameFailed`, `The game could not be stopped.`
- `create_pack(name, loader, loader_url)`: `ModPackDocument::blank()`; `packName = name`; loader given → trim+lower; must be `forge|neoforge|fabric` else `Unknown mod loader "<loader>". Use forge, neoforge, or fabric.`; set `packModLoader`; loader URL given → set `packModLoaderURL` (trimmed); `install_authored(doc, "Created")`; error → log `createFailed`, `The modpack could not be created.`
- `fork_pack(src, new)`: read source manifest document (via `loadManifestText` → `ModPackDocument.fromJson`; None → `Could not read the manifest for "<src>", so it cannot be forked.`); `doc.forkOf(new)` (deep copy, `packName = new.trim()`, `packVersion` minor bumped); `install_authored(fork, "Forked \"<src>\" into")`; error → `The fork failed.`
- `install_authored(doc, verb)`: dir = `<launcher config folder>/mcp-manifests` (`AUTHORED_MANIFESTS_DIR`; mode-dependent launcher config folder, which is the client config folder in GUI mode); target = `dir/created-<key>.json`; if target exists and **no installed pack's manifest URL resolves to it** → delete it as stale (so uninstall-then-recreate works; if any installed pack's metadata throws, assume it is the owner and keep the file); write with `CREATE_NEW` (`overwrite=false`) → file-already-exists → failed `Another installed modpack already uses the manifest file that "<packName>" would be written to; its name differs only in case, spacing or punctuation. Pick a more distinct name.` (guards the race past the tool's name check); install from the returned `file:` URL; refresh the pack list (`fetchModPackInfo`; error → warning `refreshAfterEditFailed`); ok `<verb> "<packName>" and installed it.`
- `add_mod` / `remove_mod` via `edit_manifest(n, edit)`: pack not installed → `No modpack named "<n>" is installed.`; `local_manifest_path_of(pack)` None → `"<n>" was installed from a remote manifest, which this launcher does not own and cannot edit. Use fork_modpack to make an editable copy first.`; read file → document → apply edit; edit failure → return it **without writing**; else write pretty JSON back, refresh list; error → log `editManifestFailed`, `The manifest could not be updated.`
  - add: `ModPackFileEntry(name=modName, remote=remoteUrl, local=localPath, hash="", hashType="sha1", clientReq=true, serverReq=true)`; `doc.addMod` false (same local path, case-insensitive, already present) → `"<n>" already installs something to <localPath>.`; ok `Added "<mod>" to "<n>". It downloads on the next sync.`
  - remove: `doc.removeMod(id)` matches first mod whose local path, name, or Modrinth slug equals id ignoring case (removes at most one); none → `No mod matching "<id>" is in "<n>".`; ok `Removed "<id>" from "<n>". The file goes on the next sync.`
- `resolve_authored_manifest(manifest_url, authored_dir) -> Option<Path>` (pure, tested): None unless url starts with `file:`; convert `file:` URI to path, absolutize + normalize (lexically resolve `.`/`..`); authored dir likewise; return the path iff it is **component-wise** inside the dir (`Path::starts_with`, so `mcp-manifests-evil/` sibling does not match) **and not equal to** the dir itself; any parse error → None. `local_manifest_path_of` additionally requires the path to be an existing regular file. A `file:` URL alone is not enough: an imported `.mmcjson` is local but the user's, not ours.

---

## 6. Resources (`resources/`)

### 6.1 `McpResource` trait

`uri_template()`, `name()`, `description()`, `mime_type()`, `read(params: Map<String,String>) -> Result<String>`, `governing_tool_name()` (default `"read_resource"`), `is_template()` (= template contains `{` and `}`), `concrete_uris()` (default: template → `[]`; concrete → `[uri]`), `describe_template()` → `{uriTemplate,name,description,mimeType}`, `describe_concrete(uri)` → `{uri,name,description,mimeType}`.

### 6.2 `McpResourceUri` — the `mica://` containment gate

- `SCHEME = "mica://"`.
- `match(template, uri) -> Option<Map>`: split both on `/` keeping empty parts (Java `split("/", -1)` = Rust `str::split('/')`); lengths differ → None; per part: a template part of length > 2 that starts with `{` and ends with `}` is a placeholder: **decode first**, then require `is_safe_segment(decoded)`, else None; non-placeholder parts must be equal (case-sensitive). Returns an insertion-ordered immutable map.
- `decode(segment)`: Java `URLDecoder.decode(s, UTF-8)`: `+` → space, `%XX` → bytes → UTF-8 (malformed UTF-8 → U+FFFD); an invalid `%` sequence makes decode throw → **return the raw segment unchanged** (so the checks still see the raw text; a malformed encoding cannot slip a separator through). Rust: if the segment contains a `%` not followed by two hex digits → raw; else replace `+` with space and `percent_decode(..).decode_utf8_lossy()`.
- `is_safe_segment(s)`: not null/blank (all-whitespace = blank); not `.` or `..`; no `/`, `\`, NUL, or ISO control char (`char::is_control`, i.e. Cc). A leading dot (`.hidden`) is safe.
- `build(template, value)`: value must be safe (else error); replace every `\{[^/{}]+}` with `encode(value)`.
- `encode(value)`: Java `URLEncoder` (form encoding) with `+` → `%20`: unreserved = `A-Z a-z 0-9 . - * _`; everything else percent-encoded as UTF-8 bytes with **upper-case hex**; space → `%20`. Example: `Fabric 1.21.5: 0.16.10` → `Fabric%201.21.5%3A%200.16.10`; `~` → `%7E`. Rust: `percent_encoding::utf8_percent_encode` with `NON_ALPHANUMERIC.remove(b'.').remove(b'-').remove(b'*').remove(b'_')`.
- `is_template(t)` = contains `{` and `}`.
- Rejection vectors (all must fail to match): `..`, `.`, empty, whitespace-only, `%2e%2e`, `%2E%2E`, `%2f`, `%5c`, `%00`, `%0a`, malformed `%zz/..`-style sequences, literal separators.

### 6.3 `McpResourceRegistry`

Insertion-ordered map by template. `register`: null → error; template not starting with `mica://` → error; duplicate → error. `resolve(uri)`: first registration (in order) whose template matches → `Match{resource, params}`; else None (a traversal attempt reads as "no such resource"). `list_result()` → `{"resources":[describe_concrete(u) for each resource for each concrete uri]}`; `templates_list_result()` → `{"resourceTemplates":[describe_template() for template resources only]}`. Empty arrays are always present.

### 6.4 `resources/read` handling (in `McpRequestHandler`)

1. `uri = read_string(params,"uri")`; empty → `-32602 "Missing resource uri"`.
2. `resolve(uri)`; None → `-32002 "No such resource: <uri>"`.
3. **Gate**: `tool = registry.find(resource.governing_tool_name() or "read_resource" if blank)`; if not registered, a stand-in READ_ONLY pseudo-tool with that name (title = resource name, description = resource description, empty schema; never invoked). Arguments for the authorizer = all match params + `"uri": uri`. Authorize as for a tool call (so a tool the user DISABLED cannot be read around via its resource, and ASK prompts). Error → log `resourceApprovalFailed`, deny. Denied → activity `DENIED` (tool = gate name, detail `"resource <uri>"`), `-32003 "Resource read was not approved: <uri>"`.
4. **No content gate** runs for resources (`validate_before_approval` is not called).
5. `read(params)`; error → log `resourceReadFailed` + error, activity `FAILED`, `-32603 "Could not read resource: <uri>"` (exception text never on the wire).
6. Activity `ALLOWED` (detail `"resource <uri>"`).
7. Result: `{"contents":[{"uri":<uri as requested>,"mimeType":<mime>,"text": redact_strict(text)}]}` — resource text goes through **the same redaction** as tool output (Java literally routes it through `McpToolResult::text(t).to_json()`).

Note: `resources/read` counts as activity but not as a tool call on the session counter (only `tools/call` increments).

### 6.5 The three resources (`LauncherResources`)

| URI / template | name | MIME | governing tool | content | enumerated (`resources/list`) |
|---|---|---|---|---|---|
| `mica://packs` | Modpack index | application/json | `list_modpacks` | **pretty** JSON `{"modpacks":[entry…]}` (same 7 fields as list_modpacks; no `count`) | itself |
| `mica://modpack/{friendlyName}/manifest` | Modpack manifest | application/json | `get_modpack_manifest` | raw manifest text; None → error `No manifest for modpack: <n>` (→ -32603) | every pack (installed and available) whose name is a safe segment |
| `mica://modpack/{friendlyName}/crash-report` | Modpack crash report | text/plain | `get_crash_report` | raw report text; no crash → error (→ -32603) | **installed packs only**, safe names only |

Descriptions: "Every modpack the launcher knows about, installed and available." / "The raw manifest JSON for one modpack." / "The most recent crash report for one modpack, with credentials stripped."

A pack whose name is not a safe segment is skipped in listings, so a listing never advertises a URI the reader would refuse. Every advertised URI must resolve (test `everyAdvertisedUriResolves`).

---

## 7. The redaction boundary (credential invariant)

**Absolute rule:** no tool result, resource text, error message, or activity-log entry may contain an access token, client token, refresh token, the account UUID, anything from the encrypted token cache, the MCP bearer token, the IPC token, the endpoint file contents, or raw `${auth_access_token}`/`${auth_session}` expansions. The username is allowed (`get_launcher_status`, `list_running_games`).

Enforcement, in order of reliance:
1. **Structural**: tools only see `McpLauncherView`/`McpLauncherActions`, whose types have no field for tokens/UUIDs/paths. `McpSession`/`McpCallContext` hold no credentials. The live view reads `user.name()` only.
2. **Outbound filter**: `McpToolResult::to_json` and resource reads apply `redact_strict` to every text block.
3. **Activity log**: every text field passes `redact_strict` **before storage** (`McpActivityLog` never holds a credential in memory; the Settings page is a screenshot away from a support thread).
4. Error messages never carry exception text (`"Tool failed: <name>"`, `"Could not read resource: <uri>"`); detail goes to the launcher log only.

`redact_strict` (shared utility, from `SensitiveDataRedactor`; MCP consumes it — exact semantics):
1. `redact` (base), applied in this order, each a global replace:
   - `(--accessToken)([=\s]+)\S+` → `$1$2[REDACTED]`
   - `(--clientToken)([=\s]+)\S+` → `$1$2[REDACTED]`
   - `token:[A-Za-z0-9._-]+:([0-9a-fA-F-]{32,36})` → `token:[REDACTED]:$1` (base keeps the UUID tail)
2. then `\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b` → `[REDACTED]`
3. then `\b[0-9a-fA-F]{32}\b` → `[REDACTED]` (a 40-hex SHA-1 has no boundary at 32 so survives — mod hashes stay readable; a bare MD5 is an accepted false positive).
- null/empty input passes through unchanged.
- Rust regex fidelity: Java `\s`, `\S`, `\b` here are ASCII-semantics (`\s` = `[ \t\n\x0B\f\r]`, `\b` against `[A-Za-z0-9_]`). Rust `regex` defaults to Unicode; write `(?-u:\s)`, `(?-u:\S)`, `(?-u:\b)` or explicit classes. Note `(?-u:\S)` matches arbitrary bytes only in `regex::bytes`; for `&str` regexes use `[^ \t\n\x0B\x0C\r]` instead of `\S`.
- **Observation for the port:** the 64-hex MCP bearer token itself is *not* matched by any pattern (`\b[0-9a-f]{32}\b` needs a boundary at char 32). The token is kept off the wire structurally only. Recommended defence-in-depth for Rust (no behaviour change for legitimate output): the MCP outbound filter additionally replaces the literal current bearer token and IPC token with `[REDACTED]`.

The consent dialog's displayed text uses the **base** `redact` (Java dialog sanitizer), not strict.

### 7.1 Activity log (`McpActivityLog`)

- In-memory, bounded `MAX_ENTRIES = 200`, newest dropped last (deque; oldest evicted). `recent()` returns newest first. `len()`, `clear()`.
- `record(ts, client, tool, decision, detail)`: ignored if tool or decision missing; each text field: null → `""`, else `redact_strict`.
- `Decision`: `REFUSED` (content gate, before asking) | `DENIED` (policy/user/timeout/no GUI) | `ALLOWED` (ran, succeeded) | `FAILED` (ran, failed — tool error or threw; exception text never recorded).
- Thread-safe (mutex).
- Persisting record: the launcher log records start/stop, every approval outcome, every pre-approval refusal, every tool failure with stack trace; pack mutations additionally reach the existing `ModPackAuditLog` via the normal code paths.

---

## 8. The `--mcp` stdio relay (`StdioProxy`)

Entry: `launcher --mcp` — `args[0]` equals `--mcp` **case-insensitively**. Dispatched in `main` **before** the single-instance lock (must run alongside an open launcher), **before the logger is configured**, and also skipped by startup diagnostics' launch recorder (clients spawn it often). Exits the process with the relay's return code. **stdout carries protocol only**; all diagnostics go to stderr; never use the launcher logger here.

`run(endpoint_path, stdin, stdout, stderr) -> exit code`:
1. `endpoint = read_live(default endpoint path)`; None → stderr `No running Mica Minecraft Launcher with the MCP server enabled was found. Start the launcher and turn on Settings → Security → Enable MCP server, then reconnect.`; return **2** (`EXIT_NO_LAUNCHER`). (No cold start of the launcher; planned, not built.)
2. HTTP client: connect timeout 10 s; target `http://127.0.0.1:<port>/mcp`. **Rust: disable proxy resolution** (env `HTTP_PROXY` etc.) — Java's `HttpClient` default uses no proxy unless system properties are set; `ureq`/`reqwest` read env proxies by default and 127.0.0.1 is not always in `NO_PROXY`.
3. State: `session_id: Option`, `initialize_line: Option`.
4. For each stdin line (UTF-8; line terminators `\n`, `\r\n`, `\r` stripped); skip blank lines:
   - `is_init` = line parses via `JsonRpcCodec::parse` and method is `initialize`; if so remember it as `initialize_line`.
   - `relayed = relay(line, session = if is_init { None } else { session_id })` — a handshake always starts a new session.
   - If `relayed.session_unknown && initialize_line.is_some()`: `renewed = reinitialize()`; if Some → `session_id = renewed`, `relayed = relay(line, renewed)` (retry **once**).
   - If `relayed.session_id` is Some → adopt it.
   - If `relayed.body` is Some → write it plus newline to stdout, **flush immediately** (a buffered reply looks like a hung server).
5. stdin EOF → `end_session()`; return 0. stdin read error → stderr `MCP relay stopped: <ErrorKindName>`, `end_session()`, return 1.

`relay(line, session)`: POST with `Authorization: Bearer <token>`, `Content-Type: application/json`, `Mcp-Session-Id` if any, request timeout **180 s** (must exceed the 120 s consent deadline). Response handling (Java order):
1. status 404 **and** a session was sent → `Relayed{body: non-blank body or None, session: None, unknown: true}`.
2. status 202 **or** blank body → `Relayed{None, assigned header}` (notification accepted).
3. status 400 with a body → pass body through (launcher's JSON-RPC explanation).
4. other non-200 → stderr `MCP relay: launcher returned HTTP <code>`; synthesize `-32603 "The launcher refused the request (HTTP <code>). The MCP server may have been turned off."` with the original id.
5. 200 → pass body through, adopt `Mcp-Session-Id` header if present.
- Interrupted → `-32603 "Interrupted"`. Any other error (connection refused, timeout) → stderr `MCP relay: <ErrorTypeName>`; `-32603 "Could not reach the launcher. It may have been closed."` with the original id.
- `transport_error(line, msg)` re-parses the line to recover the id (stateless about content; a client waiting on that id would otherwise hang); unparsable → id null.

`reinitialize()`: relay `initialize_line` with no session; no session id in reply → stderr `MCP relay: could not re-establish the session`, None. Else stderr `MCP relay: session expired; re-initialized`, then relay `{"jsonrpc":"2.0","method":"notifications/initialized"}` with the new id (reply discarded); return the new id. Neither reply reaches the client (it already holds its original handshake answer).

`end_session()`: if a session exists, `DELETE /mcp` with token and session header, 5 s timeout, discard response, ignore all errors (the launcher's idle expiry reclaims it).

**Java bugs to fix (or consciously keep) in Rust:**
- Rule 2 precedes rule 4, and every guard rejection (401/403/405/413/415) and 500 has an **empty body** — so the "synthesize an error for a non-200" branch is unreachable for them and a *request* rejected with e.g. 401 (token regenerated while the relay runs) produces **no output at all**; the client waits for its id forever (until its own timeout). Rust: treat only `202` (or 2xx with empty body *and* the line was a notification) as silent; for any non-2xx where the line had an id, synthesize the -32603 error.
- A failing **notification** (transport error) currently writes an error with `id: null` to stdout. Rust: write nothing for notifications.
- The endpoint is read once at relay start; if the launcher restarts (new token or fallback port) the relay is stuck. Optional Rust improvement: on connection failure or 401, re-read `read_live()` once and retry.
- Java `println` writes the platform line separator (`\r\n` on Windows) through `System.out`'s platform charset. Rust: write raw UTF-8 and `\n` only.

---

## 9. Client setup snippets (`McpClientSetup`)

Pure string generation for the Settings "Connecting a client" card.

- `SERVER_NAME = "mica-launcher"`; `endpoint_url(port) = "http://127.0.0.1:<port>/mcp"`.
- `Client` enum, in UI order: `CLAUDE_CODE, CURSOR, CODEX, HTTP, STDIO` (labels `settings.mcp.connect.client.claudeCode|cursor|codex|http|stdio`).
- `is_jar(path)` = lower-case ends with `.jar`. `is_usable_launcher_path(path)`: blank → false; jar → true; else basename (after normalizing `\`→`/`) lower-cased must not be `java`, `java.exe`, `javaw.exe` (a dev run resolves the launcher path to the JVM; `.../bin/java --mcp` silently does nothing). Rust equivalent: the packaged executable path is `std::env::current_exe()`; a `cargo run` dev build is usable too (it is the real binary) — but if the app ever runs under a wrapper, keep the "is this really the launcher?" check (e.g. reject paths inside `target/debug` only if desired). The JAR branch disappears in Rust; keep the API shape so `relay_command(path, native=true)` → `[path, "--mcp"]`.
- `relay_command(path, native)`: native → `[path, "--mcp"]`; else `["java","-jar",path,"--mcp"]`.
- `snippet(client, launcher_path, native, port, endpoint_file_path, token)`: token blank → `"<token>"` placeholder.
  - CLAUDE_CODE: `claude mcp add --transport http mica-launcher <url> --header ` + `shell_quote("Authorization: Bearer <tok>")`.
  - CURSOR: `"// ~/.cursor/mcp.json\n"` + pretty JSON `{"mcpServers":{"mica-launcher":{"url":<url>,"headers":{"Authorization":"Bearer <tok>"}}}}`.
  - CODEX: `"# ~/.codex/config.toml\n[mcp_servers.mica-launcher]\nurl = <toml(url)>\n\n[mcp_servers.mica-launcher.http_headers]\nAuthorization = <toml("Bearer <tok>")>\n"`.
  - HTTP: `"URL     <url>\nHeader  Authorization: Bearer <tok>\n\nTransport is Streamable HTTP (POST JSON-RPC). The launcher must be open."`.
  - STDIO: unusable path → `UNPACKAGED_NOTICE` ("This launcher is running from source rather than an installed build, so there is no\nlauncher command to give a client. Use one of the HTTP options instead --\nthey do not need a launcher path."); else `"For a client that can only spawn a subprocess, run this as the server command:\n" + shell-quoted command joined by spaces + "\n\nIt relays stdio to the launcher and reads the port and token from\n" + endpoint_file_path + " itself, so no token goes in the client's config."` — **embeds no token**.
- `shell_quote(v)`: empty → `''`; matches `^[A-Za-z0-9_./:=+-]+$` → bare; else `'` + v with each `'` replaced by `'\''` + `'`.
- `toml_string(v)`: `"` + v with `\` → `\\` then `"` → `\"` + `"`.
- The Settings pane uses the **bound** port when running (else the configured port) and the real token.

---

## 10. Settings pane (Settings → Security → MCP Server) — app crate, uses this crate's API

Three cards:
1. **Server controls**: toggle *Enable MCP server* (writes `mcpServerEnabled`, `refresh()`), *Auto-approve read-only tools* (writes `mcpAutoApproveReadOnly`; no refresh), *Allow tools that change things* (writes `mcpAllowStateChanges`, `refresh()`). Status line: `settings.mcp.status.running` (port) + `"  •  "` + `settings.mcp.status.sessions` (count) or `settings.mcp.status.stopped`. Never shows the token.
2. **Connecting a client**: endpoint URL (bound port); token masked via `mask()` with Show/Hide, *Copy token* (`ensure_token()` to clipboard), *Regenerate* (confirm dialog `dialog.mcp.regenToken.*` → `regenerate_token()`, re-render); client dropdown + snippet text area + Copy (label flips to `settings.mcp.connect.copied`).
3. **Tool permissions / clients / activity**: one row per `describe_tools()` entry: `settings.mcp.tool.label` (localized title, risk label) + dropdown *Default / Always allow / Always ask / Disabled* (index 0 → clear policy); *Connected clients* snapshot (`settings.mcp.sessions.entry`: name or `settings.mcp.sessions.unnamed`, elapsed since connect, call count; none → `settings.mcp.sessions.none`); *Recent activity* (first N entries: elapsed, tool, `settings.mcp.activity.decision.<refused|denied|allowed|failed>`; none → `settings.mcp.activity.none`); buttons *Revoke session approvals* (`grants().revoke_all()`; label shows `settings.mcp.grants.count` with live grant count), *Refresh*, *Clear activity*. Elapsed: `<1 min` → `settings.mcp.sessions.justNow`; `<60` → `minutesAgo`; else `hoursAgo`.
The Java lists are snapshots refreshed on build and on Refresh. In GPUI, a periodic refresh or an observable registry is a cheap improvement.

---

## 11. Threading & concurrency model (Rust)

- **HTTP**: async server (hyper 1.x on a small tokio runtime owned by `McpServer`, e.g. 2 worker threads; or one shared app runtime). Per connection: guard → body read (capped) → `dispatch`. Inline methods run on the async task (they are cheap and non-blocking). Serial methods: send a job `(message, session, now, oneshot::Sender<Value>)` over a channel to **one dedicated OS thread** `mcp-dispatch` that runs `handler.handle` (which may block up to 120 s inside `ConsentPrompt::ask`); the HTTP task awaits the oneshot (no tokio worker is blocked).
- **Consent from GPUI**: `ConsentPrompt::ask` (called on `mcp-dispatch`) posts a request to the GPUI foreground (via an `async_channel`/`flume` receiver polled by a `cx.spawn` task the app installs at startup, or `AsyncApp::update`), and blocks on `std::sync::mpsc::Receiver::recv_timeout(120 s)`. On timeout the app must close the dialog. `is_available()` asks a shared `AtomicBool`/handle maintained by the app ("main window exists").
- **Launch**: `launch` spawns its own thread/task; never runs the prepare pipeline on `mcp-dispatch`.
- **Shared state**: `McpSessionRegistry` behind a `Mutex<HashMap>` (create/evict under the lock), session counters atomic, `McpGrantStore` `Mutex<HashMap>` or `DashMap`, activity log `Mutex<VecDeque>`, policy store reads config each call.
- **Stop**: stop accepting, drop the job sender (dispatcher thread exits after the current job), abort in-flight HTTP tasks, delete endpoint file. A blocked consent wait is abandoned (answer → deny).
- **Relay process**: fully blocking, no tokio needed (`ureq`).

---

## 12. Class-by-class map (Java → Rust)

Crate `mmcl-mcp` (no GPUI dependency):

| Java | Rust module / item | Key items |
|---|---|---|
| `McpServerMode` | `mmcl_mcp::McpServerMode` | `Off, WithLauncher, TrayBackground` |
| `McpBootstrap` | `mmcl_mcp::bootstrap::McpBootstrap` | `start_if_enabled`, `stop`, `refresh`, `is_running`, `port`, `sessions`, `describe_tools`, `ensure_token`, `regenerate_token`, `activity`, `policies`, `grants`; takes a `McpHost` (factory for view/actions/consent/config) at construction |
| `McpServer` | `server::McpServer` | `new`, `start`, `stop`, `is_running`, `port`, `token`, `sessions`, `activity_log`, `dispatch`, `requires_serial_dispatch` |
| `McpRequestHandler` | `handler::RequestHandler` | `PROTOCOL_VERSION`, `handle` |
| `McpAccessToken` | `token` | `generate`, `ensure`, `is_well_formed`, `mask`, `TOKEN_LENGTH` |
| `McpEndpointFile` | `endpoint` | `Endpoint`, `FILENAME`, `default_path(paths)`, `write`, `read`, `read_live`, `delete`, `is_process_alive` |
| `McpActivityLog` | `activity::ActivityLog` | `Decision`, `Entry`, `record`, `recent`, `len`, `clear`, `MAX_ENTRIES` |
| `McpClientSetup` | `client_setup` | `Client`, `SERVER_NAME`, `endpoint_url`, `relay_command`, `snippet`, `shell_quote`, `toml_string`, `is_usable_launcher_path` |
| `FxConsentPrompt` | trait `approval::ConsentPrompt` here + `summarize_arguments`/`consent_body` helpers; GPUI impl in `mmcl-app` | `CONSENT_TIMEOUT`, `MAX_SUMMARIZED_ARGUMENTS = 6`, `MAX_SUMMARIZED_VALUE_LEN = 120` |
| `protocol.JsonRpcCodec` | `protocol::codec` | `parse`, `result`, `error`, `encode` |
| `protocol.JsonRpcMessage` | `protocol::JsonRpcMessage` | `is_notification`, `params_object` |
| `protocol.McpErrors` | `protocol::codes` | consts |
| `protocol.McpMethods` | `protocol::methods` | consts |
| `session.McpSession` | `session::Session` | `initialize`, `record_activity`, `call_context` |
| `session.McpSessionRegistry` | `session::SessionRegistry` | `create`, `find`, `find_live`, `remove`, `evict_idle`, `clear`, `all`, `MAX_SESSIONS`, `IDLE_TIMEOUT` |
| `transport.LoopbackHttpTransport` | `transport::http::LoopbackTransport` | `PATH`, `SESSION_HEADER`, `Response`, `start`, `stop` |
| `transport.McpHttpGuard` | `transport::guard` | `Verdict`, `RequestMeta`, `admit`, `is_allowed_origin`, `bearer_token_of`, `tokens_match`, `is_json_content_type`, `is_loopback`, `MAX_BODY_BYTES` |
| `transport.StdioProxy` | `relay::run` | `EXIT_NO_LAUNCHER = 2` |
| `approval.McpRiskClass` | `approval::RiskClass` | `default_policy`, `mutates_state`, `label_key` |
| `approval.McpApprovalPolicy` | `approval::Policy` | |
| `approval.McpApprovalDecision` | `approval::Decision` | |
| `approval.McpApprovalEngine` | `approval::engine` | `ApprovalRequest`, `resolve`, `has_unexpired_grant`, `DEFAULT_GRANT_TTL` |
| `approval.McpAuthorizer` | trait `approval::Authorizer` | `authorize` |
| `approval.LauncherMcpAuthorizer` | `approval::LauncherAuthorizer` | `Settings` trait, `Answer` |
| `approval.McpGrantStore` | `approval::GrantStore` | |
| `approval.McpToolPolicyStore` | `approval::PolicyStore` | `Backing` trait |
| `tools.McpTool` | trait `tools::Tool` | |
| `tools.McpToolRegistry` | `tools::ToolRegistry` | `VALID_TOOL_NAME` |
| `tools.McpToolResult` | `tools::ToolResult` | `to_json` = redaction boundary |
| `tools.McpCallContext` | `tools::CallContext` | |
| `tools.McpLauncherView` | trait `tools::LauncherView` + records | |
| `tools.McpLauncherActions` | trait `tools::LauncherActions` + `Outcome` | |
| `tools.ReadOnlyTools` | `tools::read_only::register_all` | 7 structs |
| `tools.MutatingTools` | `tools::mutating::register_all` | 8 structs, `reject_unsafe_local_path`, `describe_size`, `target_of`, `stop_problem`, `TOOL_NAMES` |
| `tools.LiveMcpLauncherView` | **`mmcl-app`** `mcp_live::LiveView` (implements `LauncherView` over modpack/auth/crash/session crates) | footprint walk constants |
| `tools.LiveMcpLauncherActions` | **`mmcl-app`** `mcp_live::LiveActions`; pure `resolve_authored_manifest` stays in `mmcl-mcp::tools::authored` | `AUTHORED_MANIFESTS_DIR = "mcp-manifests"` |
| `resources.McpResource` | trait `resources::Resource` | |
| `resources.McpResourceRegistry` | `resources::ResourceRegistry` | |
| `resources.McpResourceUri` | `resources::uri` | `SCHEME`, `match_template`, `build`, `is_safe_segment`, `is_template` |
| `resources.LauncherResources` | `resources::launcher::register_all` | `PACKS_URI`, `MANIFEST_TEMPLATE`, `CRASH_TEMPLATE` |

---

## 13. Public API sketch (Rust)

```rust
// ---- crate mmcl-mcp ----------------------------------------------------------
pub mod approval {
    #[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
    pub enum RiskClass { ReadOnly, Mutating, Destructive, Execute }
    #[derive(Clone, Copy, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
    #[serde(rename_all = "SCREAMING_SNAKE_CASE")]
    pub enum Policy { AlwaysAllow, Ask, Disabled }
    #[derive(Clone, Copy, Debug, PartialEq, Eq)]
    pub enum Decision { Allow, Prompt, Deny }
    #[derive(Clone, Copy, Debug, PartialEq, Eq)]
    pub enum Answer { Deny, AllowOnce, AllowForSession }

    pub struct ApprovalRequest {
        pub risk_class: RiskClass, pub explicit_policy: Option<Policy>,
        pub server_enabled: bool, pub auto_approve_read_only: bool,
        pub gui_available: bool, pub grant_expires_at_ms: u64, pub now_ms: u64,
    }
    pub fn resolve(req: &ApprovalRequest) -> Decision;
    pub const DEFAULT_GRANT_TTL: std::time::Duration;

    pub trait Settings: Send + Sync {
        fn server_enabled(&self) -> anyhow::Result<bool>;
        fn auto_approve_read_only(&self) -> anyhow::Result<bool>;
        fn policy_for(&self, tool: &str) -> anyhow::Result<Option<Policy>>;
    }
    /// Called on the mcp-dispatch thread; must return within CONSENT_TIMEOUT.
    pub trait ConsentPrompt: Send + Sync {
        fn is_available(&self) -> bool;
        fn ask(&self, req: ConsentRequest) -> Answer;
    }
    pub struct ConsentRequest {
        pub client_name: String, pub tool_name: String,
        pub tool_title: String /*localized*/, pub tool_description: String /*localized*/,
        pub arguments_summary: String, pub consent_detail: Option<String>,
        pub offer_session_grant: bool,
    }
    pub trait Authorizer: Send + Sync {
        fn authorize(&self, tool: &dyn crate::tools::Tool, ctx: &crate::tools::CallContext,
                     args: &serde_json::Map<String, serde_json::Value>) -> bool;
    }
    pub struct LauncherAuthorizer { /* settings, grants, prompt, clock */ }
    pub struct GrantStore { /* Mutex<HashMap<String,u64>> */ }
    pub struct Grant { pub client_name: String, pub tool_name: String, pub expires_at_ms: u64 }
    pub trait PolicyBacking: Send + Sync { fn read(&self) -> anyhow::Result<String>; fn write(&self, json: &str) -> anyhow::Result<()>; }
    pub struct PolicyStore { /* Box<dyn PolicyBacking> */ }
    impl PolicyStore {
        pub fn policy_for(&self, tool: &str) -> Option<Policy>;
        pub fn set_policy(&self, tool: &str, policy: Option<Policy>);
        pub fn all(&self) -> std::collections::BTreeMap<String, Policy>;
        pub fn clear(&self);
    }
}

pub mod tools {
    pub struct CallContext { pub client_name: String, pub session_id: String }
    pub trait Tool: Send + Sync {
        fn name(&self) -> &'static str;
        fn title(&self) -> &'static str;
        fn description(&self) -> &'static str;
        fn input_schema(&self) -> serde_json::Value;
        fn risk_class(&self) -> crate::approval::RiskClass;
        fn invoke(&self, ctx: &CallContext, args: &Args) -> anyhow::Result<ToolResult>;
        fn validate_before_approval(&self, _args: &Args) -> Option<String> { None }
        fn consent_detail(&self, _args: &Args) -> Option<String> { None }
    }
    pub type Args = serde_json::Map<String, serde_json::Value>;
    pub struct ToolResult { /* blocks, is_error */ }
    impl ToolResult {
        pub fn text(s: impl Into<String>) -> Self; pub fn json(v: &serde_json::Value) -> Self;
        pub fn error(msg: impl Into<String>) -> Self; pub fn texts(blocks: Vec<String>) -> Self;
        pub fn is_error(&self) -> bool; pub fn raw_text_blocks(&self) -> &[String];
        pub fn to_json(&self) -> serde_json::Value; // redacts every block
    }
    pub struct ToolRegistry { /* IndexMap<String, Arc<dyn Tool>> */ }

    // Seams implemented by mmcl-app:
    pub trait LauncherView: Send + Sync { /* packs, pack, manifest_of, latest_crash_of, footprint_of, status, running_games */ }
    pub trait LauncherActions: Send + Sync { /* install_from_url ... remove_mod */ }
    pub mod read_only { pub fn register_all(reg: &mut super::ToolRegistry, view: std::sync::Arc<dyn super::LauncherView>); }
    pub mod mutating  { pub fn register_all(reg: &mut super::ToolRegistry, view: std::sync::Arc<dyn super::LauncherView>, actions: std::sync::Arc<dyn super::LauncherActions>);
                        pub fn reject_unsafe_local_path(p: &str) -> Option<String>; pub fn describe_size(bytes: u64) -> String; }
    pub mod authored  { pub fn resolve_authored_manifest(manifest_url: &str, authored_dir: &std::path::Path) -> Option<std::path::PathBuf>; }
}

pub mod bootstrap {
    /// What the app supplies so the MCP crate can build a server without knowing GPUI or launcher internals.
    pub trait McpHost: Send + Sync {
        fn config(&self) -> &dyn McpConfig;                 // the six keys
        fn client_config_dir(&self) -> std::path::PathBuf;  // for mcp-endpoint.json
        fn launcher_version(&self) -> String;
        fn view(&self) -> std::sync::Arc<dyn crate::tools::LauncherView>;
        fn actions(&self) -> std::sync::Arc<dyn crate::tools::LauncherActions>;
        fn consent(&self) -> std::sync::Arc<dyn crate::approval::ConsentPrompt>;
        fn localize(&self, key: &str) -> Option<String>;    // mcp.tool.<name>.title etc.
        fn log(&self) -> &dyn McpLog;                        // std/warn/error/debug with localized keys
    }
    pub trait McpConfig: Send + Sync {
        fn server_enabled(&self) -> anyhow::Result<bool>;
        fn allow_state_changes(&self) -> bool;
        fn auto_approve_read_only(&self) -> bool;
        fn tool_policies(&self) -> String;  fn set_tool_policies(&self, json: &str);
        fn port(&self) -> u16;
        fn token(&self) -> String;          fn set_token(&self, t: &str);
    }
    pub struct ToolDescriptor { pub name: String, pub title: String, pub risk_class: crate::approval::RiskClass }
    pub struct McpBootstrap { /* host, Mutex<Option<McpServer>>, grants, activity, policies */ }
    impl McpBootstrap {
        pub fn new(host: std::sync::Arc<dyn McpHost>) -> Self;
        pub fn start_if_enabled(&self);
        pub fn stop(&self);
        pub fn refresh(&self);
        pub fn is_running(&self) -> bool;
        pub fn port(&self) -> u16;
        pub fn sessions(&self) -> Vec<crate::session::SessionSnapshot>;
        pub fn describe_tools(&self) -> Vec<ToolDescriptor>;
        pub fn ensure_token(&self) -> String;
        pub fn regenerate_token(&self) -> String;
        pub fn activity(&self) -> &crate::activity::ActivityLog;
        pub fn policies(&self) -> &crate::approval::PolicyStore;
        pub fn grants(&self) -> &crate::approval::GrantStore;
    }
}

pub mod endpoint { pub struct Endpoint { pub port: u16, pub token: String, pub pid: i64, pub launcher_version: String }
                   pub const FILENAME: &str = "mcp-endpoint.json";
                   pub fn write(path: &std::path::Path, e: &Endpoint) -> std::io::Result<()>;
                   pub fn read(path: &std::path::Path) -> Option<Endpoint>;
                   pub fn read_live(path: &std::path::Path) -> Option<Endpoint>;
                   pub fn delete(path: &std::path::Path); }

pub mod relay { pub const EXIT_NO_LAUNCHER: i32 = 2;
                pub fn run(endpoint_path: &std::path::Path, stdin: impl std::io::BufRead,
                           stdout: impl std::io::Write, stderr: impl std::io::Write) -> i32; }

pub mod client_setup { pub enum Client { ClaudeCode, Cursor, Codex, Http, Stdio }
                       pub fn snippet(client: Client, launcher_path: &str, native: bool, port: u16,
                                      endpoint_file: &str, token: Option<&str>) -> String;
                       pub fn endpoint_url(port: u16) -> String; }
```

`main.rs` (mmcl-app): `if args.get(1).is_some_and(|a| a.eq_ignore_ascii_case("--mcp")) { std::process::exit(mmcl_mcp::relay::run(&paths::client_config_dir().join(FILENAME), stdin.lock(), stdout.lock(), stderr.lock())) }` — before the single-instance lock and before any logger/GPUI init; stdout untouched by anything else.

---

## 14. External crates

| Need | Crate | Notes |
|---|---|---|
| JSON | `serde`, `serde_json` (features `preserve_order`, optionally `arbitrary_precision` or `raw_value`) | insertion order like Gson; exact id echo |
| HTTP server | `hyper` 1.x + `hyper-util` + `http-body-util` (`Limited` for the 1 MiB cap) on `tokio` (`rt-multi-thread`/`net`/`sync`) | Alternative: `tiny_http` (blocking, thread-per-request like the JDK server; simpler but less maintained) |
| Relay HTTP client | `ureq` (blocking, HTTP/1.1, proxies disabled) | no tokio in the relay process |
| CSPRNG | `rand` (`OsRng`/`ThreadRng`) or `getrandom` | token (32 B) and session ids (16 B) |
| Hex | `hex` (or hand-rolled) | lower-case |
| Constant-time compare | `subtle` (or `constant_time_eq`) | |
| Regex | `regex` (with `(?-u:...)` ASCII classes) | redactor, tool-name pattern (or hand-written check) |
| Percent encoding | `percent-encoding` | custom `AsciiSet` matching `URLEncoder` |
| Ordered maps | `indexmap` | registries |
| URL/file-URI | `url` (`Url::to_file_path`, `Url::from_file_path`) | authored manifest containment |
| Path normalization | `path-clean` or hand-written lexical normalize (don't `canonicalize`: the Java check is lexical, and canonicalize fails on missing files) | |
| Process liveness | `libc` (Unix `kill(pid,0)`), `windows`/`windows-sys` (`OpenProcess`, `GetExitCodeProcess`) — or `sysinfo` | |
| Owner-only file perms | Unix `std::os::unix::fs::OpenOptionsExt::mode(0o600)`; Windows `windows`/`windows-sys` `SetNamedSecurityInfoW` + `SetEntriesInAclW` (owner SID from `GetSecurityInfo`) | shared helper with the single-instance IPC token — belongs in a common utility crate |
| Channels | `crossbeam-channel` or `std::sync::mpsc` (dispatch), `tokio::sync::oneshot`, `async-channel`/`flume` (to GPUI) | |
| Errors / logging | `anyhow`/`thiserror`, the workspace logger | |

No good Rust equivalent / needs hand work:
- **No MCP SDK is wanted** (Java hand-rolled it on purpose). The official `rmcp` crate exists but imposes its own transport/session model, SSE support and tool macros, and would not reproduce the exact admission order, session/404 semantics, serialized dispatch and approval gate; hand-roll as in Java.
- **Windows ACL owner-only**: no ergonomic, maintained high-level crate; write ~60 lines against `windows-sys` (shared with the IPC token).
- **GPUI modal consent dialog** with a deadline and external dismissal: build in the app (gpui-component `Dialog`/`Modal` if available); there is no blocking "show question and wait" helper, so bridge via channels.
- Gson-lenient JSON parsing and Java `Formatter` HALF_UP rounding have no drop-in; both are intentionally not replicated (see 2.2, 5.6).

---

## 15. Needs from other subsystems

- **Paths**: client-mode config dir (endpoint file; Java `~/.MicaMinecraftLauncher/config`, dev `…DEV`), launcher config dir (mode-dependent; `mcp-manifests/`). Rust uses its own data root for now but must mirror `<root>/config/mcp-endpoint.json`, `<root>/config/configuration.json`, `<root>/config/mcp-manifests/created-<key>.json`.
- **Config**: the six keys (1.1) with debounced writes; `mcpToolPolicies` stored as a JSON *string*; port default depends on dev flag; `getAccountOverrideForPack(settingsKey)`.
- **Constants**: launcher version string, `IS_DEV`, `MCP_SERVER_PORT(_DEV)`, `--mcp` arg.
- **Redaction**: `redact` / `redact_strict` (SensitiveDataRedactor) — shared with game console, crash reporter, launch-command log.
- **File permissions**: owner-only file (and dir) helper (shared with single-instance IPC token).
- **Localization**: `get`, `format`, `get_or(key, fallback)`; keys `mcp.tool.<name>.title|description`, `mcp.risk.*`, `dialog.mcp.consent.*`, `dialog.mcp.regenToken.*`, `dialog.button.cancel`, `settings.mcp.*`, `log.mcp*.*` (all `Logger` messages are localized in this project).
- **Logging**: std / warning-silent / error / throwable / debug.
- **Modpack manager**: installed + available pack lists; per pack: friendly name, installed version, pack version, `is_update_available`, mod loader type, unstable flag, pack root folder, latest crash report text, manifest URL, settings key; `install_by_url(url)` (supports `file:` URLs), `uninstall_by_friendly_name`, `refresh/fetch pack info`; `ModpackExporter::load_manifest_text(pack)`.
- **Modpack document model** (`ModPackDocument`/`ModPackFileEntry`): `blank`, `from_json`, `to_pretty_json`, `get/put_string`, `KEY_PACK_NAME` (`packName`), `KEY_PACK_VERSION`, keys `packModLoader`, `packModLoaderURL`, `fork_of(new_name)` (minor bump), `add_mod(entry) -> bool`, `remove_mod(identifier) -> bool`, `local_manifest_key_of`, `local_manifest_path_in(dir)`, `write_local_manifest(dir, overwrite=false) -> file URL` (create-new semantics), mod-loader constants `forge|neoforge|fabric`.
- **Crash analysis**: `analyze(report, pack, exit_code) -> {title, summary, category(enum name), suggestions[label]}`.
- **Auth**: logged-in user's username; accounts list `{display_name, uuid}`; `LaunchAccountResolver::resolve(override, accounts) -> Ok(uuid) | NoAccount | OverrideMissing | NeedsSignIn`.
- **Game sessions**: active sessions `{pack_name, account_name, account_uuid, phase Preparing|Running, started_ms, cancel(), stop(force=false)}`, `is_pack_active(pack)`.
- **Launch**: `play_as(pack, account_uuid: Option)` runnable on a background task; `spawn_task`.
- **URI handler**: `classify_install_url(url) -> AcceptTrusted | RequireConfirmation | Reject`.
- **GUI shell**: "top window exists" signal, a modal dialog facility on the foreground thread with external dismissal, clipboard, the Settings → Security page host.
- **Desktop integration**: resolved launcher executable path (`DesktopShortcutManager.resolveLauncherPath` equivalent) for the stdio snippet.
- **Lifecycle**: call `start_if_enabled` in GUI startup after the pack list loads; `stop` in cleanup; `--mcp` dispatch in `main` before single-instance lock and logger init.

## 16. Provides to other subsystems

- `McpBootstrap` lifecycle + Settings data (status, port, sessions, activity, policies, grants, tool descriptors, token mask/copy/regenerate).
- `relay::run` for `main`'s `--mcp` mode (exit codes 0/1/2).
- `client_setup::snippet` for the Settings "Connecting a client" card.
- `endpoint::{read_live, write, delete}` (any future tool/diagnostic wanting to find a running launcher's MCP server).
- Traits `LauncherView`, `LauncherActions`, `ConsentPrompt`, `McpHost`, `McpConfig` that the app implements.
- Pure helpers reusable elsewhere: `guard::is_allowed_origin`/`tokens_match` (could also harden the single-instance IPC listener), `resources::uri::is_safe_segment`, `mutating::reject_unsafe_local_path`, `describe_size`.

---

## 17. Tests worth porting (from `src/test/java/.../mcp/`)

All are pure and should port almost 1:1 as Rust unit tests (stubs instead of mocks), except the loopback and relay tests which become integration tests using an ephemeral port and a temp dir.

- `JsonRpcCodecTest` (34): every parse branch and message in 2.2; id echo rules (valid id echoed on later failure, invalid never), batch/empty batch, scalar root, bare null, array params read as `{}`, null params = absent, unknown fields ignored, result/error shapes, `data` only when supplied, null message → `""`, id field present when null, compact encode round-trip.
- `McpHttpGuardTest` (27): full admission table and order (unauthenticated rejected before any other check; foreign origin forbidden even with valid token; authenticated DELETE admitted; 1 MiB exactly accepted; unknown length passes); the origin, bearer and content-type vectors in 3.2; loopback/unknown peer.
- `McpServerLoopbackTest` (27): binds ephemeral loopback port; endpoint file published and removed; start twice refused; stop twice harmless; 401 no/wrong token; 403 foreign origin; loopback origin OK; GET 405; text/plain 415; oversized 413 (arrives intact, not a reset); ping answered sessionless; malformed JSON → -32700; notification → 202 no body; handshake then tool call; call without session id → 400; unknown session → 404; sessionless requests create no sessions; handshakes past the cap evict oldest; DELETE ends session (204), DELETE without id 400, DELETE without token 401; re-initialize cannot change client name; denied call → -32003 and never runs; handshake registers a live session; **pending consent does not block ping/tools/list/another initialize**; `requires_serial_dispatch` only for `tools/call` and `resources/read`.
- `McpRequestHandlerTest` (37): collaborators required; initialize shape/identity/missing clientInfo; pre-handshake refusal for every non-ping method; ping pre-handshake; notifications never answered (incl. unknown); unknown method; tools/list; null message → internal error; approved call; missing/unknown tool name; denied call never reaches tool; throwing authorizer denies; content gate before approval (authorizer never consulted); throwing gate refuses; throwing tool doesn't leak its message (wire or activity); null result → tool error; call counting; every outcome recorded; resource read content, redaction, missing/unknown/traversal URIs, failing read not leaking, authorized as governing tool, recorded, unregistered governing tool gated as read-only, **disabled tool cannot be read through its resource**, listings served.
- `McpApprovalEngineTest` (24) and `LauncherMcpAuthorizerTest` (19): the whole decision table (4.2), grant TTL/exact-expiry/zero-expiry, headless behaviour, grant scope (per tool, per client), disabling/ASK overriding a grant, ASK never offered a session grant, every throw path denies, allow-once not carried over.
- `McpToolPolicyStoreTest` (18): round trip, case-insensitive trimmed keys, blank names ignored, sorted storage, malformed/empty/unknown/wrong-type/throwing reads → unset, one bad entry keeps others, throwing write swallowed, **no hostile input ever yields a permissive policy** (fuzz-style; good candidate for `proptest`).
- `McpSessionRegistryTest` (11): ids unguessable/distinct (32 hex), idle expiry on lookup, activity keeps alive, evict-idle only idle, LRU eviction at cap, idle swept before active evicted, repeated restarts never exceed cap, first handshake sets identity.
- `McpResourceUriTest` (27), `McpResourceRegistryTest` (15), `LauncherResourcesTest` (14): match/decode/encode vectors, every encoded rejected character, malformed percent still checked, leading dot safe, `%20` not `+`, reserved-char round trip, build refuses unsafe; registry first-match, duplicate/scheme refusal, listings shape; every resource governed by a registered read-only tool; crash reports enumerated for installed only; names with spaces encoded and resolve back; unsafe names skipped; every advertised URI resolves; empty library advertises only `mica://packs`.
- `McpToolRegistryTest` (16): name pattern accept/reject vectors (whitespace, control chars, shell/path metachars, upper case, blank, leading/trailing separator, >64 chars), duplicate refusal, order preserved, listing fields, empty array.
- `McpToolResultTest` (17): access/client token, dashed and undashed UUID, legacy session string redacted; error results and every block of multi-block results and JSON payloads redacted; raw accessor not redacted; SHA-1 survives; shapes and null handling.
- `ReadOnlyToolsTest` (28) and `MutatingToolsTest` (43): every message and shape in 5.5/5.6 against stub view/actions, including localized title/description present for every tool (port as a localization-bundle parity test), risk classes, gates before consent, name-sanitization collisions, loader URL gate, optional args forwarded as None, uninstall-while-running refused (other game running is fine), footprint wording (plural/singular/none/approximate/unmeasurable), `describe_size` vectors, launch gates incl. `why_launch_blocked` passthrough and named account passthrough, stop targeting (single/several/named/not running), outcome → result mapping.
- `LiveMcpLauncherActionsTest` (8): `resolve_authored_manifest` — inside, nested, outside, sibling prefix, `..` escape, the dir itself, remote URL, malformed input. Run on both Windows and Unix path forms.
- `McpEndpointFileTest` (16): round trip, parent creation, owner-only perms (POSIX check; ACL check on Windows), absent/dir/malformed/missing fields/out-of-range → None, dead pid → absent, no pid → live, current process alive, invalid pids not alive, delete idempotent.
- `McpAccessTokenTest` (12), `McpActivityLogTest` (11), `McpClientSetupTest` (24), `FxConsentPromptTest` (8, now tests of `summarize_arguments`): straightforward vectors listed in sections 1.4, 7.1, 9, 4.6.
- `StdioProxyTest` (11): absent/stale endpoint → exit 2 and nothing on stdout; ping relayed; session id carried; notification → no output; stdin close → DELETE; dropped session transparently re-established; blank lines skipped; malformed JSON relayed and answered -32700 by the launcher; launcher gone → error carrying the request id; diagnostics never on stdout. **Add** tests for the fixes in section 8 (401 mid-run yields an error line; failing notification yields no line).

---

## 18. Discrepancies between guide and code (code wins)

- Guide says "six" read-only tools in several places and its worked example lists six in `tools/list`; code registers **seven** (`list_running_games` added). Fifteen tools total is correct.
- Guide's example session id is UUID-like (`6f1c0f0a-…`); actual ids are 32 lower-case hex chars.
- Guide's example `launch_modpack` reply text is outdated; actual: `Started launching "<n>". Its progress shows in the launcher's Running Games window; call list_running_games to follow it.`
- Guide says "Starting generates a fresh bearer token … per-launch" in the `McpServer` Javadoc; superseded — the token is persisted (`McpAccessToken.ensure`), only test paths generate a fresh one.
- `McpBootstrap` Javadoc says "only the read-only tools are registered"; superseded by the second opt-in.
- Guide's §10 test counts are stale (code has ~400 methods across 21 classes).
