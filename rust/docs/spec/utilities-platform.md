# Porting spec: utilities and native platform integration

Scope: `src/main/java/com/micatechnologies/minecraft/launcher/utilities/` (all 58 classes,
~16k lines) and `.../launcher/system/DesktopShortcutManager.java`. Background reading:
`docs/PLATFORM_INTEGRATION.md`.

This spec is written so a Rust engineer can rebuild the slice without reading the Java. Where
the Java code carries a fix for a past bug, the fix is called out as **Guard:**. Keep every
guard. Most of them cost one line and each was learned the hard way.

Target: Cargo workspace under `rust/`, GPUI `0.2.2` (optionally `gpui-component 0.9`), Windows /
macOS / Linux. The Rust build uses its own data folder for now, laid out exactly like the Java
one (see section 0.3), so it can later be pointed at the Java folder.

---

## 0. Cross-cutting decisions

### 0.1 Fallback philosophy (keep it)

Every native integration point:

1. is gated on the platform (`cfg(target_os)` at compile time, plus runtime gates such as the
   Windows build number);
2. catches every failure and logs it at "warning, silent" level (in the log, not a toast);
3. never panics across an FFI boundary. Every `extern "system"` / Obj-C callback body is wrapped
   in `std::panic::catch_unwind` and returns a safe default;
4. when it *replaces* in-window UI, does the replacement only after the native step reports
   success.

Provide one helper in `mmcl-platform`:

```rust
/// Runs a best-effort native step. Logs `key` with the error on failure. Never panics.
pub fn best_effort<T>(what: &'static str, f: impl FnOnce() -> anyhow::Result<T>) -> Option<T>;
```

### 0.2 Threading model

Java uses three kinds of thread: the JavaFX thread, a cached daemon pool
(`SystemUtilities.spawnNewTask`, threads `mica-bg-N`) and a fixed download pool
(`DownloadExecutor`, threads `mmcl-download`). GPUI's `App` is `!Send` and lives on the main
thread.

The Rust mapping:

| Java | Rust |
|---|---|
| `GUIUtilities.JFXPlatformRun(r)` | `UiDispatcher::run(Box<dyn FnOnce(&mut App) + Send>)`. The app crate implements it with an `async_channel` drained by a `cx.spawn` loop on the foreground executor. |
| `SystemUtilities.spawnNewTask(r)` | `mmcl_util::tasks::spawn(name, f)`: a lazily created, re-creatable cached thread pool (see 2.13). Do not use `cx.background_spawn` for this. Callers include IPC accept threads and Discord callbacks that have no `App`. |
| `DownloadExecutor` | `mmcl_net::DownloadPool` (fixed size, see 3.4). |
| Thread interrupt as cancel | An explicit `CancelToken` (`Arc<AtomicBool>` plus a `Condvar` for interruptible sleeps). Rust has no thread interrupt. |
| "blocks the worker until the user answers a dialog" | The worker sends a request to the UI thread with a `oneshot`, then blocks on it. **Never call it on the UI thread** (it would deadlock). Debug-assert this. |

### 0.3 Paths and constants this slice reads

Owned by the paths/config subsystem. Listed here because the slice hard-codes their names.

| Name | Java value | Used by |
|---|---|---|
| client root | `~/.MicaMinecraftLauncher` (release), `~/.MicaMinecraftLauncherDEV` (dev). That is `"." + appName.replace(" ", "")` | startup log, IPC token |
| server root | current working directory | everything mode-dependent in server mode |
| config folder | `<root>/config` | `machine-key.bin`, `client.mica`, `update.mica`, `icons/mmcjson-file.ico` |
| client config folder | `<client root>/config`, **whatever the game mode** | `single-instance.token` |
| logs | `<root>/logs`, and `startup.log` always under the client root | `StartupDiagnostics` |
| metadata | `<root>/metadata` | desktop-shortcut icons `icon_<sanitizedPack>.ico/.icns` |
| cache | `<root>/cache` | `CacheManager` |
| single-instance port | `47821`, dev `47822` | `SingleInstanceLock` |
| Discord client id | `841860482029846528` | Discord RPC |
| URL scheme | `mmcl` | URI handler, registrars |
| pack file extension / description | `.mmcjson` / `Mica Minecraft Modpack` | registrars |
| client CLI flag | `-c` | shortcuts |
| user agent | `MicaMinecraftLauncher/<version>` (`dev` when unknown) | all HTTP |

**Guard (early-main paths):** before the CLI args are parsed the game mode is unknown, and a
mode-dependent path resolves to the current directory. The startup log and the IPC token must
always use the *client* root. Two processes started from different working directories (the
running launcher and the OS-spawned forwarder) must agree on the token location.

**Side-by-side with the Java launcher** (open question Q1): with the Rust build on its own data
folder, it must not share the IPC port, the `mmcl://` registration, the `.desktop` file name,
the Windows ProgID or the AppUserModelID with an installed Java launcher. Otherwise each
launcher steals the other's deep links. Proposed: an `AppIdentity` struct (name, folder name,
port pair, desktop id, ProgID, AUMID) chosen at build time, defaulting to a distinct "RS"
identity until the switch-over.

### 0.4 Localization

All user-visible text and almost every log line go through `LocalizationManager`. The Rust
slice takes an `&dyn Localizer` (or a global) with `get(key)` and `format(key, args)`. The Java
keys are kept verbatim so the bundle files carry over. Key names appear inline below as
`key:...`. Exceptions that stay English on purpose: `StartupDiagnostics` lines (written before
the locale is set up), `TimeUtilities` greetings (legacy), exception messages.

---

## 1. Proposed crates

| Crate | Contents | Depends on |
|---|---|---|
| `mmcl-util` | Pure logic plus small fs helpers: versions, JVM-args validation and generation, URL validation, redaction, `StringOrArray`, JSON helpers, unit formatting, hashing, command-line splitting, env filtering, shell quoting, `mmcl://` parsing and classification, throttle math, progress combining, background task pool, process launch, startup diagnostics, cold-start profiler, client token | serde, serde_json, sha1, sha2, md-5, regex, uuid |
| `mmcl-net` | HTTP downloads (retry, stall watchdog, atomic publish, path locks), proxy, offline latch, scoped listeners, `DownloadTracker`, `DownloadPool`, bounded and conditional JSON fetches, `CacheManager`, connectivity probe, update check, announcements | reqwest (blocking), mmcl-util, mmcl-power (trait) |
| `mmcl-archive` | Contained tar.gz and zip extraction, jar extraction for natives, jar signature stripping, image transcode to PNG, ICO/ICNS writers | zip, tar, flate2, image, icns |
| `mmcl-secure` | `MachineSecretCipher`, hardware UUID, Java-compatible `os.name`, `FilePermissions` | aes-gcm, pbkdf2, hmac, sha2, base64, getrandom, windows, wmi (Windows), io-kit-sys or core-foundation (macOS) |
| `mmcl-platform` | All OS-shell integration: theme probe, DWM chrome and Mica, Windows chrome helpers for GPUI, taskbar progress, jump list, scheme and file registration, desktop shortcuts, single-instance IPC, foreground hand-off, DLL hardening, DPI, notifications, tray, dock, macOS appearance and dock, Linux Unity launcher entry, power state | windows, objc2 + objc2-app-kit + objc2-foundation, zbus, notify-rust, tray-icon, ksni, starship-battery, raw-window-handle, socket2, gpui |
| `mmcl-discord` | Discord IPC client (handshake, SET_ACTIVITY, ACTIVITY_JOIN subscription), presence state machine | interprocess (Windows named pipe, Unix socket), serde_json |

`LauncherUriHandler` dispatch, `LauncherActions`, `VerifyAction`, `UpdateCheckManager` and
`AnnouncementManager` reach into the GUI, modpack and session subsystems. Their *logic* lives in
these crates behind traits (`DeepLinkHost`, `ShellActionsHost`). The app crate implements the
traits.

---

## 2. Pure and general utilities (`mmcl-util`)

### 2.1 `VersionUtilities.compareVersionNumbers(a, b) -> -1|0|1`

1. `strip(v)`: cut at the first `-`, then at the first `+`, then at the first `_`. Empty
   becomes `"0"`.
2. Split on `.`. Walk `max(len)` segments. A missing segment counts as 0.
3. `parseSegment`: the leading ASCII-digit run parsed as `i32`. No leading digit gives 0. An
   overflow gives 0. **Never throws.**
4. The first unequal segment decides.

**Guard:** git-describe versions like `2026.1.0-15-gabc1234.dirty`, Forge `14.23.5.2855_230729ER`
and segments like `rc3` must not throw. A throw used to disable update detection and break
main-menu card rebuilds. Rust: `pub fn compare_versions(a: &str, b: &str) -> std::cmp::Ordering`.
Test cases are in `VersionUtilitiesTest`.

### 2.2 `JvmArgsValidator`

- `PLACEHOLDER_OPEN = "${"`.
- `require_clean(s) -> Result<String, JvmArgsError>`: `None` becomes `""`. Reject the first
  char `< 0x20` or `== 0x7F`, reporting its index (message
  `"Custom JVM args contain a control character at position N"`). Reject any occurrence of
  `${` (`"Custom JVM args may not contain '${...}' placeholder syntax."`).
- `is_clean(s) -> bool`: `None` and empty are clean. The read path uses this and falls back
  silently.
- Why: the args are split on whitespace (so a newline smuggles arguments), and templating runs
  *after* the custom args are appended (so `${auth_access_token}` would leak the token into
  argv).
- Index is a char index in Java (UTF-16). Rust should report a char index, not a byte index.

### 2.3 `HardwareTunedJvmArgs`

`generate(max_ram_gb: i32) -> String`. `cores = max(1, available_parallelism)`. Exact order:

```
-XX:+UseG1GC -XX:+UnlockExperimentalVMOptions
  if maxRam <= 4:  -XX:MaxGCPauseMillis=50 -XX:G1HeapRegionSize=2M -XX:G1NewSizePercent=20
  elif <= 12:      -XX:MaxGCPauseMillis=200 -XX:G1HeapRegionSize=4M -XX:G1NewSizePercent=30
                   -XX:G1MaxNewSizePercent=40 -XX:G1ReservePercent=20
  else:            -XX:MaxGCPauseMillis=200 -XX:G1HeapRegionSize=16M -XX:G1NewSizePercent=30
                   -XX:G1MaxNewSizePercent=40 -XX:G1ReservePercent=15
  if cores >= 4:                 -XX:+ParallelRefProcEnabled
  if cores >= 8 || maxRam > 12:  -XX:+UseStringDeduplication
-XX:+AlwaysPreTouch -XX:+ExplicitGCInvokesConcurrent
```

Single spaces, no trailing space. **Guard:** `UnlockExperimentalVMOptions` must come before the
G1 percent flags. `ExplicitGCInvokesConcurrent` replaces Aikar's `DisableExplicitGC` (disabling
explicit GC led to "OutOfMemoryError: Direct buffer memory").

`summary(max_ram_gb)` gives `"{cores} core{s?} / {totalGB} GB system RAM / {max} GB max heap"`
(English, unlocalized in Java; consider localizing). Total RAM comes from `sysinfo` and is cached
for the session. If the probe fails, assume 4 GiB (Java: `maxMemory * 4`).

Tests: every branch contains `ExplicitGCInvokesConcurrent`, and the output passes
`JvmArgsValidator::is_clean`.

### 2.4 `UrlValidation`

`sanitized_http_url(raw) -> Option<String>`: trim, parse strictly as an RFC 3986 URI, accept
only scheme `http`/`https` (any case), and **return the trimmed input as typed**. Do not return
a normalized form. `"https://example.com"` must come back without a trailing `/`, so do **not**
use `url::Url::to_string()`. Reject what Java's `URI.create` rejects: spaces, `"<>\^`{|}`,
malformed `%`. Use `fluent-uri` (strict and non-normalizing) or a hand-written check.
`is_http_url(raw)` is `sanitized_http_url(raw).is_some()`. Tests in `UrlValidationTest` (for
example `"https://exa mple.com/page with space"` is rejected and `"//example.com/page"` is
rejected).

### 2.5 `SensitiveDataRedactor`

Applied in order. Regex syntax is the same in the `regex` crate.

| Pattern | Replacement |
|---|---|
| `(--accessToken)([=\s]+)\S+` | `$1$2[REDACTED]` |
| `(--clientToken)([=\s]+)\S+` | `$1$2[REDACTED]` |
| `token:[A-Za-z0-9._-]+:([0-9a-fA-F-]{32,36})` | `token:[REDACTED]:$1` (the UUID tail is kept on purpose) |

`redact_strict` = `redact`, then `\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b`
then `\b[0-9a-fA-F]{32}\b`, both replaced by `[REDACTED]`. A 40-char SHA-1 is not matched (no
word boundary after 32 chars). A bare MD5 *is* redacted, which is an accepted false positive.
`None` and empty pass through. Note: in Rust's `regex`, `\b` is Unicode-aware by default; use
`(?-u:\b)` to match Java's ASCII semantics. Tests in `SensitiveDataRedactorTest` (18 cases)
port 1:1.

### 2.6 `StringOrArray` (serde)

A `string | string[]` JSON union, used for modpack logo/background URL lists.

- Deserialize: `null` or absent gives empty. A string gives one element. An array gives its
  non-null elements. A number or bool is coerced to its string form (Gson `getAsString`). Every
  value is trimmed and blanks are dropped. Order is kept.
- Serialize: empty gives JSON `null`, one value gives a bare string, two or more give an array.
- `all()`, `first() -> Option<&str>`, `is_empty()`. Equality is by value list, so `"a"` equals
  `["a"]`.
- Struct fields use `#[serde(default)]` so an absent field gives empty. (Java leaves the field
  `null`; the test `absentFieldLeavesNullAndEmptyStringIsEmpty` maps to `Option<StringOrArray>` =
  `None`.)

### 2.7 JSON helpers (`JSONUtilities`, `JsonHelper`, `FileUtilities`)

- Use `serde_json`. `FileUtilities.persistenceCharset` is UTF-8. Read and write whole files.
- `JsonHelper` gets null-safe getters on `serde_json::Value`: `get_string(obj, key, default)`,
  `get_required_string`, `get_int`, `get_long`, `get_bool`, `get_double`, `get_object`,
  `get_array` and the required variants. **Gson coercion semantics**: `getAsString` on a number
  or bool returns its text, `getAsInt` on a numeric string parses it, and a JSON `null` value
  counts as absent. Required getters fail with
  `"Required JSON key '<k>' is missing or null"` / `"... is missing or not a JSON object"` /
  `"... not a JSON array"`.
- **Compatibility note:** Gson `fromJson` is lenient (comments, single quotes, unquoted names).
  `serde_json` is strict. See open question Q6. Gson's `toJson` HTML-escapes `<>&='`
  (`\u003c`); output text differs but parses the same.
- Pretty print: 2-space indent, the same as `serde_json::to_string_pretty`.

### 2.8 `UnitFormatter`

Binary multiples. Negative values clamp to 0.

- `bytes(b)`: `< 1024` gives `units.bytes` with 0 decimals; `< 1 MiB` gives `units.kilobytes`
  with 0 decimals; `< 1 GiB` gives `units.megabytes` with 1 decimal; else `units.gigabytes`
  with 2 decimals.
- `bytes_per_second(r)`: `< 1024` gives `units.bytesPerSecond` with 0; `< 1 MiB` gives
  `units.kilobytesPerSecond` with 1; else `units.megabytesPerSecond` with 1.
- The number uses the UI locale's decimal separator, exact fraction digits and **no grouping**.
  It is passed into the message as a pre-formatted string. Rust: format with `{:.N}`, then swap
  `.` for the locale's decimal symbol (from a small table, or `icu_decimal`).
- Not for machine-facing text (MCP output, parsed logs).

### 2.9 Hashing (`HashUtilities`)

- `file_sha1/sha256/md5(path) -> Option<String>`: lowercase, zero-padded hex (40/64/32 chars).
  256 KiB read buffer (**Guard:** hashing dominates a full verify; 8 KB was 2-4x slower). On I/O
  error: log `log.hashUtil.sha1CalcFailed` (and the sha256/md5 variants) and return `None`.
- `bytes_to_hex`, `sha1_hex(&[u8])`, `sha256_hex(&[u8])`, `sha256_hex_str(&str)` (UTF-8).
- `verify_sha1/sha256/md5(path, expected) -> bool`: false if the path is not a regular file.
  Compare with `constant_time_hex_eq`: equal length, then lowercase each char and OR the XORs.
  Mixed-case expected hashes are accepted. Crates: `sha1`, `sha2`, `md-5` (RustCrypto).

### 2.10 `ProcessUtilities`

`split_command_line(s) -> Vec<String>`. A quote toggles `in_quotes` and *starts a token*. Outside
quotes, whitespace ends a started token. Everything else is appended. **Backslashes are
literal** (Windows paths like `"C:\dir\"`). An explicit `""` produces an empty argument. An
unclosed trailing quote still emits the last token. Adjacent quoted segments join (`"a""b"` gives
`ab`). Whitespace means Java `Character.isWhitespace` (ASCII whitespace plus Unicode space
separators, but not NBSP). Tests: `ProcessUtilitiesSplitCommandLineTest` (9 cases).

`ChildIoMode { Pipe, Discard, Inherit }`:

- `Pipe`: stdout/stderr piped. **The caller must drain both** or the game blocks when the pipe
  buffer fills ("JVM in Task Manager but no window").
- `Discard`: `Stdio::null()` for stdout and stderr.
- `Inherit`: stdin, stdout and stderr all inherited. **Guard:** stdin matters for headless
  server mode, where systemd `StandardInput=socket` feeds console commands (`stop`, `say`) into
  the launcher's stdin.

`launch(argv: &[String], cwd: &Path, mode) -> io::Result<Child>`:

- Strip sensitive env vars from the child's environment (never the launcher's own). A name is
  sensitive if, uppercased, it **contains** any of `TOKEN SECRET PASSWORD PASSWD PASSPHRASE
  API_KEY APIKEY PRIVATE_KEY ACCESS_KEY CREDENTIAL COOKIE SESSION_KEY AUTH_KEY`, or **starts
  with** any of `AWS_ AZURE_ GCP_ GCLOUD_ GOOGLE_APPLICATION_ GITHUB_TOKEN GH_TOKEN NPM_TOKEN
  PYPI_TOKEN OPENAI_ ANTHROPIC_ STRIPE_ TWILIO_ SENDGRID_`. Implementation: iterate
  `std::env::vars_os()` and call `cmd.env_remove(name)` (case-insensitive on Windows).
- Log `log.processUtil.launchingCommand` with `redact(argv.join(" "))`.
- **Windows:** set `CREATE_NO_WINDOW` (`0x0800_0000`) through `CommandExt::creation_flags` when
  the launcher has no console of its own (GUI mode). Otherwise every helper (`powershell`,
  `notify` and the like) flashes a console window. In server/console mode leave it off so
  `Inherit` shares the console.
- The string overload `launch_str(cmd, cwd, mode)` uses `split_command_line`. Prefer argv.

`execute_string_command(cmd, cwd)` is a legacy blocking variant with a retry prompt. Enhanced
logging on: pipe stdout and stderr and tee them to the launcher's stdout/stderr with two reader
threads. Off: inherit. **Guard:** inheriting while gobbling made the gobblers read EOF, so
choose one or the other. A non-zero exit triggers `Logger.logErrorConfirmRetry(
processUtil.gameCrashed.message, processUtil.gameCrashed.retry)`. A retry re-runs it and logs
`log.processUtil.executingCommandRetry`. `wait_for_process(child)` shows the same prompt on
non-zero exit, without retrying.

Tests: `ProcessUtilitiesSensitiveEnvTest` (7 cases, including "an unrelated substring is not
stripped").

### 2.11 Shell and desktop quoting helpers (from `DesktopShortcutManager`)

Pure and security-relevant. Port exactly.

- `sanitize_file_name(name)`: replace each of `< > : " / \ | ? *` with `_`, then trim. It does
  **not** strip control chars, CR/LF or `..`; the tests pin this. A null input panics in Java
  (NPE), so take `&str` in Rust.
- `shell_single_quote(s)`: `None` gives `''`. Otherwise `'` + `s.replace("'", "'\\''")` + `'`.
- `desktop_exec_quote(s)`: `None` gives `""`. Otherwise wrap in `"` and backslash-escape each of
  `"`, `` ` ``, `$`, `\`.
- `windows_cmd_quote(s)`: `None` gives `""`. Otherwise wrap in `"`. Count the run of `\`. Before
  an embedded `"`, double the run and emit `\"`. At the end, double a trailing run. Matches
  `CommandLineToArgvW`.
- `strip_line_terminators(s)`: drop `\r`, `\n`, `\0`. `None` gives `""`.

Tests: `DesktopShortcutManagerQuotingTest` and `DesktopShortcutManagerSanitizeTest` port 1:1.

### 2.12 Small leftovers

- `TimeUtilities.friendly_greeting()`: local time in `[00:00,12:00)` gives "Good Morning",
  `[12:00,17:00)` gives "Good Afternoon", else "Good Evening". English, legacy. Use `chrono`.
- `GameMode { Client, Server }` with string names `"CLIENT"` / `"SERVER"`.
- `AuthUtilities.check_auth_response(result) -> bool`: false (logging an error) for
  `BadUsernamePassword` (`log.authUtil.badUsernamePassword`), `LoginExpired`, `NoVal`, `Other`,
  `NotOwned`; true otherwise. Belongs with the auth subsystem's result enum. Note from the test
  `freshInstanceWithNullUserIsTreatedAsSuccessDespiteMatchingAnErrorConstantsData`: in Java,
  identity comparison against singletons makes a structurally-equal fresh instance read as
  success. Model the result as an enum and drop this quirk.
- `RecentPacks.get_recent(max)`: installed packs with `last_played_ms > 0`, sorted by it
  descending, truncated to `max`. A pack whose metadata read fails is skipped. `max <= 0` gives
  empty.
- `SystemUtilities.build_file_path(parts)`: join with the OS separator. In Rust use `PathBuf`.
- `client_token()`: memoized. Read `<config>/client.mica` (UTF-8, whole file, used as-is). If it
  is missing or unreadable, generate a UUID v4 string (lowercase, dashed) and write it with no
  newline. On write failure, log and still use it. Log keys are the legacy
  `CLIENT_TOKEN_*_TEXT` constants.

### 2.13 Background task pool (`SystemUtilities.spawnNewTask`, `runBounded`, `FxAsyncTask`)

- `tasks::spawn(f)`: a cached pool. Grow without bound, reap idle threads after 60 s, name
  threads `mica-bg-{n}` with a global monotonic counter (names stay unique across pool
  generations). Mark worker threads (thread-local flag) so `is_background_worker()` can answer.
- **Guard:** the pool is **re-creatable**. The in-process restart loop (`while restartFlag`)
  shuts it down in cleanup. A pool that stayed shut made every later `spawn` fail and silently
  disabled image caching, auth renewal and Play. If submission fails anyway, run the task inline
  and log `log.systemUtil.bgExecutorRejected`.
- `shutdown(await_ms)`: stop accepting, wait up to `await_ms`, then abandon. Log
  `log.systemUtil.bgExecutorNoDrain`. Work that shuts the pool down must not run on a pool
  worker (check `is_background_worker()`).
- Daemon semantics: Rust has no daemon threads, but `std::process::exit` ends them. The app's
  exit path must call `process::exit` rather than rely on `main` returning while workers are
  blocked.
- `run_bounded(name, step, timeout_ms, on_failure) -> bool`: run on a fresh detached thread
  `mmcl-bounded-{name}`. Join with a timeout (`mpsc::recv_timeout` on a completion channel,
  timeout at least 1 ms). Return whether it finished. A panic counts as finished and is passed
  to `on_failure`. Used at shutdown for steps that can hang (RGB SDKs, Discord, MCP). Tests:
  `SystemUtilitiesShutdownHelpersTest`.
- `FxAsyncTask.run(background, on_success_ui, on_error_ui)` and `run_with_finally(background,
  finally_ui)`: run on the pool. On `Err` or panic, log `log.fxAsyncTask.taskFailed` with the
  error and its backtrace. Then dispatch exactly one of success or error to the UI thread, or
  the finally callback. In GPUI code prefer `cx.spawn` plus `background_executor().spawn` and
  keep this helper for parity call sites.

### 2.14 `StartupDiagnostics`

- File: `<client root>/logs/startup.log`, whatever the mode.
- `append(file, msg)`: synchronized. Create the parent dirs. If the file is over
  `MAX_BYTES = 256 * 1024`, move it to `startup.log.1` (replacing). Then append
  `yyyy-MM-dd HH:mm:ss.SSS [pid N] <msg><platform newline>`. **Never fails**: swallow every
  error.
- `record_launch(args)`: `"Start <version>[ (dev)] on <os name> <os version>, <runtime>,
  <n> argument(s)"`. Log the argument **count only** (args can carry deep links). Rust: replace
  "Java <ver>" with the Rust build info.
- `install_panic_hook()`: the counterpart of the uncaught-exception handler. Record
  `Uncaught exception on thread "<name>"` with the backtrace, then call the previous hook.
- English only. Tests: `StartupDiagnosticsTest` (timestamp and pid, rollover, never panics,
  client folder whatever the mode).

### 2.15 `ColdStartProfiler`

- Enabled when env `MMCL_PROFILE_OUTPUT` (or a CLI or system-property equivalent) is non-blank.
  `MMCL_EXIT_AFTER_PAINT` set to `true` (any case) or `1` exits after the write.
- The anchor is captured as early as possible (a `static` `Instant` in `main`). `mark(phase)`
  records the first or last ns offset per phase, in insertion order (`indexmap`).
  `is_enabled()`.
- `write_and_maybe_exit()`: runs once (`AtomicBool` CAS). Appends CSV rows to the output file,
  creating parents and writing a header when the file is new or empty:
  `ts_iso,run_id,jvm_start_epoch_ms,jdk_version,os,arch,cds_enabled,phase,offset_ms`. `run_id`
  is the process start epoch ms. `offset_ms = (ns + 500_000) / 1_000_000`. Fields are
  CSV-escaped (quote when they contain `,`, `"` or `\n`; double inner quotes). Keep the column
  names so existing analysis scripts still work. Fill `jdk_version` with the Rust version or
  `"rust"` and `cds_enabled` with `false`.
- Exit: after about 100 ms (let the frame finish), call `cx.quit()` and then
  `std::process::exit(0)` from a helper thread.
- Phase names used across the app (`locale_ready`, `main_menu_painted` and others) belong to the
  callers.

---

## 3. Networking (`mmcl-net`)

### 3.1 HTTP client choice

Use `reqwest` with the `blocking`, `rustls-tls`, `socks` features and **no** `gzip`/`brotli`/
`deflate`. Java's `HttpURLConnection` sends no `Accept-Encoding`, so `Content-Length` is the real
byte count that progress and caps rely on. `reqwest::blocking` is chosen over `ureq` because it
has a real per-read idle timeout (`read_timeout`). Build two clients per proxy setting:

- **download client**: `connect_timeout(10s)`, `read_timeout(30s)` (per read, like Java's
  `setReadTimeout`), no response caching, `User-Agent` set, redirect policy "follow up to 20,
  allow same scheme and http to https, refuse https to http". (Java's `HttpURLConnection`
  follows same-protocol redirects only. The http-to-https upgrade is a deliberate, harmless
  widening.)
- **bounded client**: the same timeouts, `redirect::Policy::none()`. The bounded fetches walk
  redirects by hand (see 3.6).

Rebuild both clients when proxy settings change (`reload_proxy()`, called by Settings).

### 3.2 Proxy

From config: `proxyEnable`, `proxyHost`, `proxyPort`, `proxyType`. When enabled with a
non-empty host: `"SOCKS"` (any case) gives `socks5://host:port`, anything else gives
`http://host:port`, applied to both http and https. Log `log.network.proxyConfigured`
(type, host, port). On config failure log `log.network.proxyConfigFailed` and use no proxy.
Read the settings lazily on first use.

**Guard:** only `http`/`https` sources use the proxy. Local sources (`file:`, and `jar:`
entries inside the Forge installer) are read straight from disk. In Java the `jar:` handler threw
`UnsupportedOperationException` with a proxy set, which broke every embedded Forge library for
proxy users.

### 3.3 Source types

The Java `URL` overloads accept `http(s)`, `file:` and `jar:file:/...!/entry`. Rust:

```rust
pub enum Source { Http(url::Url), File(PathBuf), JarEntry { jar: PathBuf, entry: String } }
impl Source { pub fn parse(s: &str) -> Result<Self>; pub fn file_name(&self) -> &str; }
```

The `jar:` containment gate (which jars may be read) belongs to the modpack subsystem. This
crate only provides the reader. Local sources skip the stall watchdog, the proxy and the offline
latch, but still go through temp file and atomic publish.

### 3.4 Offline mode latch and connectivity probe

- `offline: AtomicBool`. `is_offline()`, `set_offline(bool)`.
- `note_network_success()`: called after **every** successful real request (file download,
  string fetch, bounded fetch, and a 304). If the flag was set, clear it and log
  `log.network.connectivityRestored`. **Guard:** a startup probe that failed briefly (Wi-Fi still
  associating) used to strand the session in cache-only mode while downloads worked fine.
- `check_network_availability() -> bool`: try a TCP connect (3 s timeout each), in order, to
  `launchermeta.mojang.com:443`, `1.1.1.1:443`, `8.8.8.8:443`, `9.9.9.9:443`. Stop at the first
  success (clear offline, return true). If all fail, log `log.network.connectivityCheckFailed`,
  set offline and return false. **Guard:** one flaky host must not latch offline mode. Note:
  like Java's raw `Socket`, the probe ignores the HTTP proxy. A proxy-only network reads as
  offline until the first real request succeeds and clears it. Keep that, or try the proxy host
  first (open question Q7).

### 3.5 File download core (`download_file`)

```rust
pub fn download_file(src: &Source, dest: &Path, opts: &DownloadOpts) -> Result<(), DownloadError>;
pub struct DownloadOpts<'a> {
    pub tracker: Option<&'a DownloadTracker>,
    pub accept: Option<&'a str>,      // Accept header
    pub cancel: Option<&'a CancelToken>,
}
```

Every file-download variant in Java (tracked, untracked, with Accept, String or URL source)
funnels through this one core. **Guard:** the old untracked path had no stall watchdog, and a
trickling connection hung a launch forever.

Algorithm:

1. Take a **per-path lock**. The key is `dest` made absolute and normalized lexically (no
   symlink resolution, no `canonicalize` syscall), lowercased on Windows. Store it in a
   `DashMap<String, Arc<Mutex<()>>>` that is never pruned. Relative and absolute forms of the
   same path must share a lock (tested).
2. `registered = false`. For `attempt in 1..=3`:
   1. `tmp = format!("{dest}.{uuid_v4}.tmp")`, a sibling and unique per attempt. **Guard:**
      shared folders (assets) are downloaded into by two launches at once. A fixed `.tmp` let
      one launch publish the other's half-written bytes. Tested in `DownloadTempFileTest`.
   2. Send the GET with UA, optional `Accept` and no cache. A status `>= 400` is an error (Java's
      `getInputStream` throws), which is retried.
   3. `content_length = header or -1`. If there is a tracker and `!registered`, call
      `tracker.register_download(content_length)` and set `registered = true`. **Guard:**
      registering on every attempt double-counted expected bytes and leaked `active_downloads`,
      so the progress bar and ETA went backwards.
   4. Copy with a **64 KiB** buffer into a `BufWriter<File>` on `tmp`. Per chunk:
      - if `cancel` is set, return `Cancelled` (the counterpart of `InterruptedIOException`);
      - write, `attempt_bytes += n`, `window_bytes += n`, `tracker.add_bytes(n)`;
      - **stall watchdog**: when `now - window_start >= 60 s`, if `window_bytes < 30_720` log
        `log.network.downloadStalled` (file name, bytes, seconds) and fail with
        `"Download stalled (received N bytes in Ss): <src>"`; otherwise reset the window;
      - `notify_download_progress(dest, attempt_bytes, content_length, tracker)` (throttled, see
        3.7);
      - `power::maybe_throttle(n)` (battery saver, see 6.17).
   5. Flush, then publish atomically: `std::fs::rename(tmp, dest)` (atomic replace on POSIX; on
      Windows `MoveFileExW(MOVEFILE_REPLACE_EXISTING)`, which std's `rename` uses). If that
      fails with a cross-device error, fall back to copy and remove.
   6. `tracker.complete_download()`, `note_network_success()`, return Ok.
   7. On error: delete `tmp`. If `attempt_bytes > 0`, `tracker.add_bytes(-attempt_bytes)`
      (**Guard:** this rollback stops double counting on retry). If cancelled, return
      immediately, with **no further retries**. If `attempt < 3`: log
      `log.network.downloadAttemptFailed` (attempt, url, delay ms),
      `notify_retry(src, attempt + 1, 3)`, then sleep `1000 * attempt` ms (1 s, then 2 s) in an
      **interruptible** way (`cancel.wait_timeout`). A cancel during the sleep gives
      `Cancelled`.
3. After three failures, return the last error.

`active_downloads` is not decremented on final failure. That leak matches Java and is harmless.
Optionally fix it.

### 3.6 String and bounded fetches

- `download_string(src, accept: Option<&str>) -> Result<String>`: a single attempt, no retry,
  UTF-8 decode (lossy is fine), `note_network_success()`.
- `download_string_bounded(url, max_bytes) -> Result<String>`: used for JSON that goes straight
  into the parser.
  1. `open_https_no_downgrade(url)`: for up to 5 redirects (`MAX_REDIRECTS`, so 6 requests at
     most), refuse non-https (`"Refusing non-HTTPS URL on bounded fetch: <u>"`). On
     301/302/303/307/308 read `Location` (`"Redirect without Location header from <u>"` when it
     is missing or blank), resolve it against the current URL and loop. Otherwise return the
     response. Exhaustion gives `"Too many redirects following <src>"`.
  2. Content-Type gate `assert_acceptable_json_content_type(ct, url)`: missing or blank passes.
     Otherwise the lowercased value must start with `application/json`, `text/json`,
     `application/javascript`, `text/plain` or `application/octet-stream` (static blob hosts
     serve `.mmcjson` as one of the last two). Anything else fails with
     `"Unexpected Content-Type for bounded JSON fetch from <u>: <ct>"`.
  3. Read with an 8 KiB buffer. A running total over `max_bytes` fails with
     `"Response from <src> exceeded max-bytes cap (<max>)"`. `Content-Length` is not trusted.
  4. A status `>= 400` is an error.
- `download_string_bounded_conditional(url, max, prev_etag, prev_last_modified) ->
  BoundedFetchResult { body: Option<String>, etag, last_modified, not_modified }`: the same,
  plus `If-None-Match` / `If-Modified-Since` when the stored value is non-blank. A **304** gives
  `body: None`, validators from the response falling back to the previous ones,
  `not_modified: true`, and `note_network_success()`. The content-type gate runs only on non-304,
  non-redirect responses.
- `download_file_bounded(url, dest, max)`: for binary archives. HTTPS without downgrade, status
  `>= 400` fails (`"HTTP <code> from <src>"`), declared `Content-Length > max` fails before
  reading, and the copy aborts past `max`. **On any failure delete `dest`.** It writes straight
  to `dest` (not a temp file); keep that. Test `boundedFileDownload_refusesPlainHttpAndRemovesTheDestination`.
- Caps used by callers: 256 KiB for announcements and GitHub release JSON, 50 MiB for modpack
  manifests.

### 3.7 Scoped retry and progress listeners

Several launches run at once. Each launch owns a **scope token**.

```rust
#[derive(Clone, Copy, PartialEq, Eq, Hash)] pub struct DownloadScope(u64);
pub fn current_scope() -> Option<DownloadScope>;          // thread-local
pub fn set_scope(s: Option<DownloadScope>);
pub fn in_current_scope<F: FnOnce() -> R + Send, R>(f: F) -> impl FnOnce() -> R + Send; // captures and restores
pub fn add_retry_listener(scope: Option<DownloadScope>, f: impl Fn(&str) + Send + Sync + 'static) -> ListenerGuard;
pub fn add_progress_listener(scope: Option<DownloadScope>, f: impl Fn(&str) + Send + Sync + 'static) -> ListenerGuard;
```

- Store listeners in a copy-on-write list (`arc-swap` or `RwLock<Vec<Arc<Entry>>>`). Each entry
  holds `{scope, callback, last_fire_ms: AtomicU64}`. Dropping the guard removes it. (A list,
  not a slot. **Guard:** with one slot, a second launch replaced the first launch's listener and
  the first launch's cleanup then cleared the second's.)
- `hears(entry, download_scope) = entry.scope.is_none() || download_scope.is_none() ||
  entry.scope == download_scope`.
- Retry notice text: `format("network.download.retrying", attempt, max, url_file_name(src))`.
  Every listener that hears it is called, and a panic inside a listener is caught and ignored.
- Progress: throttled **per listener** to one call per 120 ms. Only the thread that wins the CAS
  on `last_fire_ms` fires. The line is built lazily, once per notify, and only if some listener
  fires.
- `format_download_progress(dest, so_far, len, tracker)`: `"<name> — "` when the name is
  non-empty. If `len > 0`: `"<min(100, so_far*100/len)>% · <bytes(so_far)> / <bytes(len)>"`,
  else `bytes(so_far)`. Then `" · <speed>"` when the tracker's formatted speed is non-empty. The
  separators are U+2014 and U+00B7.
- `url_file_name(src)`: the last path segment. A path ending in `/` gives `""`. A path with no
  `/` gives the path. An empty path gives the host. `None` gives `""`. The query is not part of
  the path.
- `DownloadPool::submit` captures the submitter's scope (so pool threads report to the right
  launch). Tests: `NetworkUtilitiesDownloadScopeTest`.

### 3.8 `DownloadTracker`

All fields atomic: `total_downloaded`, `total_expected`, `active`, `completed_downloads`,
`completed_files`, `total_files`, `start_ms`, `speed` (f64 bits in an `AtomicU64`),
`last_speed_update_ms`, `last_speed_bytes`.

- `reset()`, `set_total_file_count(n)`.
- `register_download(expected)`: add when `> 0`. On the first call set `start_ms` and
  `last_speed_update_ms`. `active += 1`.
- `add_bytes(i64)` (can be negative): add, then recalculate the speed. If `now - last >= 500 ms`
  **and this thread wins the CAS** on `last_speed_update_ms`:
  `instant = delta * 1000 / elapsed`, `speed = speed * 0.3 + instant * 0.7`,
  `last_speed_bytes = current`.
- `complete_download()`: `active -= 1`, `completed_downloads += 1`.
- `complete_file()`: `completed_files += 1`. Called whether or not a download happened.
- `eta_seconds()`: `-1` when `completed < 2`, `total <= 0`, `completed >= total` or
  `start == 0`. Otherwise `(elapsed_ms / completed * remaining) / 1000`. File-count based,
  because many servers omit `Content-Length`.
- `formatted_speed()`: `""` when speed `< 1.0`, else `UnitFormatter::bytes_per_second`.
- `formatted_eta()`: `""` when `< 0`. Under 60 s: `download.eta.seconds`. Under an hour:
  `download.eta.remaining` with `m:ss`. Otherwise `h:mm:ss`.
- `formatted_status()`: when `total > 0`, `file_count = format("download.fileCount", completed,
  total)`. With no speed, return `file_count` (possibly empty). Otherwise
  `speed[ — eta][ — file_count]` (U+2014 with spaces).

### 3.9 `DownloadPool` (`DownloadExecutor`)

- One process-wide fixed pool: `max(16, 2 * cores)` threads named `mmcl-download`, never shut
  down. It is separate from the launch orchestration pool (whose branches block on this pool;
  sharing one pool could starve it). Tasks must never submit back into it.
- `submit<T>(f) -> DownloadHandle<T>`: wraps `f` in `in_current_scope`. Each handle carries a
  `CancelToken` passed into the task.
- `await_all(handles, timeout_ms) -> Result<(), AwaitError>`: one deadline over the whole set.
  For each handle, wait for the remaining time. Zero or less remaining gives
  `Timeout("Download tasks did not complete within the allotted time.")`. On timeout, a task
  failure (first error wins) or the caller's own cancel: **cancel every handle first**, then
  return. Tests: `DownloadExecutorTest` (all complete, failure cancels siblings, timeout cancels
  pending).
- Implementation: `crossbeam-channel` work queue plus N workers. Each handle is a
  `oneshot::Receiver<thread::Result<Result<T>>>` plus its token.

### 3.10 `CacheManager`

- Dir: `<root>/cache`, created on demand. Every public method holds one global mutex.
- Key: `sha256_hex(url)` plus the extension. The extension is taken from the URL with the query
  removed: the text after the last `.` that comes after the last `/`, at most 6 chars including
  the dot, else none.
- `get_cached(url, ttl = 24h) -> Option<PathBuf>`: returns the file if it exists and
  `now - mtime < ttl`. An expired file is deleted.
- `download_and_cache(url, ttl)`: return the cached file if there is one. **Refuse** anything
  that does not start with `https://` or `http://` (case-insensitive), with
  `"Refusing to cache non-http(s) URL: <u>"`. Download with `download_file`. **Guard:** a 0-byte
  result is deleted and fails with `"Cached download was empty: <u>"` (a broken icon otherwise
  stuck around for 24 h).
- `cleanup()`: delete files older than 24 h. If the rest exceeds 100 MiB, delete the oldest by
  mtime until it is under the cap. Log `log.cacheManager.cleanupComplete` (KB remaining).

### 3.11 Update check (`UpdateCheckUtilities`, `UpdateCheckManager`)

- API: `GET https://api.github.com/repos/Mica-Technologies/minecraft-launcher/releases/latest`
  with `Accept: application/vnd.github.v3+json`. Java downloads it to `<config>/update.mica`
  and parses that file. Keep the file (layout parity). Read `tag_name` (version) and `html_url`
  (page). The result is memoized for the process once both are present. A response missing
  either key logs `log.updateCheck.emptyApiResponse` and leaves both empty.
- Note: Rust releases should publish tags the Java comparator accepts (for example
  `2026.11.0`). Q8 covers whether the Rust build checks the same repo.
- `check_and_configure_ui(host)`:
  1. Hide the update glyph.
  2. If the `launcherUpdateCheckEnabled` setting is off, return.
  3. **Guard:** skip unstamped builds (version missing, starts with `0.0.0`, or contains
     `no-git-tag` or `-dev`), logging `log.updateCheck.skipUnstampedBuild`.
  4. On a worker: if `compare(installed, latest) == Less`, then on the UI thread:
     - when the macOS native toolbar item is installed (see 6.9), mark the update on it;
       otherwise show the glyph, with tooltip `tooltip.update.available` and a click that opens
       the prompt;
     - `TaskbarProgress::show_full_error()` (the red taskbar overlay is the attention cue);
     - toast `notification.update.available.title/body` **once per session** (`AtomicBool`).
  5. Errors log `log.updateCheck.checkError` with details.
- `prompt_and_open_update(url, window)`: on a worker, show a two-button question
  (`dialog.update.available.*`, buttons "now" and "later"). On "now", open the URL only if it
  starts with `https://` or `http://`; otherwise log `log.updateCheck.refuseNonHttpUrl`. Open
  with the `open` crate; failure logs `log.updateCheck.browserOpenFailed`.

### 3.12 Announcements (`AnnouncementManager`)

- URL: `https://micauseaststorage.blob.core.windows.net/mc-launcher-api/launcher-remote-config/announce.json`,
  bounded to 256 KiB.
- JSON keys: `login`, `home`, `config`, `modpacksedit` (strings) and `disableGameplay`,
  `disableModpacksEdit` (bools). Each key present overwrites its field. Missing keys keep the
  previous value. The defaults are `""` and `false`. Parse failure logs
  `log.announcement.loadFailed`.
- `start_check_async() -> Shared<Future>`: idempotent while in flight; a new fetch starts after
  completion. `check_future()` returns the last one, so the main menu can re-render when it
  settles. Use `futures::future::Shared`, or a `watch` channel holding a generation counter.
- Session-only dismissal set keyed by the exact announcement text:
  `dismiss_for_session(text)` and `is_dismissed(text)`. Changed text shows again.

---

## 4. Archives and images (`mmcl-archive`)

### 4.1 `ArchiveExtractor` (runtime tarballs and zips)

`extract_tar_gz(archive, target)` and `extract_zip(archive, target)`.

- `base = absolute(target)` normalized lexically, then created.
- Shared `validate_and_resolve(name, kind)` (**Guard:** shared so tar and zip cannot drift
  apart. The zip path once had a weaker symlink check by accident):
  - an empty name is skipped;
  - a NUL in the name is an **error** (`"Refusing archive entry with NUL byte: <n>"`);
  - an absolute path is an **error**: starts with `/` or `\`, or has `:` as its second char;
  - tar entry types Symlink, Link (hardlink), Char, Block and Fifo are **skipped** and logged
    (`log.archiveExtractor.skippingNonRegularTar`, type name `symlink/hardlink/chardev/blockdev/fifo`);
  - a zip entry whose Unix mode is a symlink (`(mode & 0o170000) == 0o120000`) is **skipped**
    (`log.archiveExtractor.skippingZipSymlink`);
  - `target = base.join(name)` normalized lexically. Not under `base` is an **error**
    (`"Refusing archive entry that escapes base dir: <n>"`);
  - a leaf whose stem (text before the last `.`, lowercased) is a Windows reserved name (`con
    prn aux nul com1-9 lpt1-9`) is **skipped** on every OS.
- A directory entry is created. A file goes through `copy_entry` with an 8 KiB buffer, a
  **per-entry cap of 512 MiB** and a **total cap of 2 GiB**, counting bytes actually read and
  **never trusting the declared size**. Breaches are errors.
- **Zip must read the central directory** for the mode bits. **Guard:** a streaming reader only
  sees local headers, so its symlink check is silently a no-op. The `zip` crate's `ZipArchive`
  reads the central directory, so use `by_index` / `unix_mode()`. Visit entries in physical
  order (sort indices by `header_start()`). An entry with an unsupported compression method is
  skipped with `log.archiveExtractor.skippingUnreadableZipEntry`.
- Tar: `tar::Archive::entries()` over `flate2::read::GzDecoder`. **Do not use
  `Entry::unpack_in`**; write files yourself so the checks above are the only path to disk.
- Permissions and timestamps are not kept. The runtime subsystem restores `+x` on `bin/*`.
- Tests: `ArchiveExtractorTest` (10 cases, including the `..` escape, absolute paths, NUL,
  symlinks in both formats, reserved names).

### 4.2 `SystemUtilities.extractJarFile` (natives)

`extract_jar(jar: &Path, dest: &Path) -> Result<(), ModpackError>`:

- Skip any entry whose name **contains** `META-INF`.
- NUL, absolute path or escape is an error. A reserved stem is skipped
  (`log.systemUtil.skippingReservedNameEntry`). Directories are created.
- Caps: **64 MiB per entry, 256 MiB total**, streamed.
- Write to `<target>.tmp-<uuid>`, **fsync** (`File::sync_all`), then atomic rename over the
  target. If the atomic move is unsupported, plain replace. On any failure delete the temp file
  and return `"Unable to read file from jar during extraction."` or `"Unable to publish
  extracted JAR entry: <n>"`. **Guard:** natives (`lwjgl64.dll`) are re-extracted every launch.
  A partial write produced "DLL initialization routine failed" / 0xC0000005.
- Tests: `SystemUtilitiesExtractTest`, `SystemUtilitiesExtractSecurityTest` (reserved-name check
  is case-insensitive and ignores the extension).

### 4.3 `JarSigningStripper`

- `is_strip_required_for(mc_version)`: regex `^1\.(\d+)` matches and the minor is `< 6`.
  `None`, empty, snapshots (`20w28a`) and anything not starting `1.` give false. **Guard: never
  for 1.6+.** FML's sanity checker aborts with "CRITICAL TAMPERING WITH MINECRAFT".
- `strip_signing(jar) -> Result<bool>`: false when it is not a regular file or has no signature
  file (an entry starting `META-INF/` (case-insensitive) and ending `.SF`, `.DSA`, `.RSA` or
  `.EC`). Otherwise rewrite to `<jar>.unsigned.tmp`:
  - drop the signature files;
  - rewrite `META-INF/MANIFEST.MF` (case-insensitive match): parse the manifest, remove the
    `Signature-Version` main attribute, **drop every per-entry section**, write it back;
  - copy every other entry with its modified time, using Deflate (the `JarOutputStream`
    default);
  - atomic rename over the original. Log `log.jarStripper.strippedSigning`.
- Manifest writer must follow Java's `Manifest.write` format: `Manifest-Version` first, then the
  other main attributes in their original order, `Name: Value` lines, **CRLF**, lines wrapped at
  72 bytes with a single-space continuation, a blank line after the main section. Write a small
  parser and writer (about 100 lines). No crate does this faithfully.
- Tests: `JarSigningStripperTest` (11 cases, including no temp file left behind).

### 4.4 Image format (`ImageFormatUtilities`)

- `is_natively_decodable(path)`: a regular file at least 8 bytes long whose first 12 bytes match
  PNG `89 50 4E 47`, JPEG `FF D8 FF`, GIF `47 49 46 38` or BMP `42 4D`.
- `ensure_decodable(path) -> bool`: already natively decodable gives true. Otherwise decode with
  `image` (features `webp`, `avif` if desired, `tiff`). Undecodable gives false and leaves the
  file alone. Otherwise encode PNG to `<file>.transcode.tmp`, then rename over the file.
  Failures log `log.imageFormat.readFailed/writeFailed/transcodeFailed`. Always clean up the
  temp file.
- GPUI decodes WebP itself. **Still transcode WebP to PNG** so the content-addressed image cache
  (keyed by SHA-1 of the bytes *after* transcode) stays layout-compatible with the Java folder.
  The PNG bytes differ from Java ImageIO's, so a shared folder re-keys once, which is harmless.

### 4.5 Icon writers (for desktop shortcuts)

- **ICO** (`png_to_ico`): sizes `[256, 48, 32, 16]`, each a PNG payload. `ICONDIR` (reserved 0,
  type 1, count, all LE u16), then 16-byte `ICONDIRENTRY` each (width/height byte, `0` meaning
  256; palette 0; reserved 0; planes 1; bpp 32; size u32; offset u32, starting at
  `6 + 16 * count`), then the payloads. Use the `ico` crate or hand-roll it.
- **ICNS** (`png_to_icns`): Java runs `iconutil` on an `.iconset` with
  `icon_{16,32,128,256,512}x{same}[@2x].png`. Rust writes it in-process with the `icns` crate
  (no external tool). If that fails, fall back to the PNG.
- Scaling: `image::imageops::resize` with `Lanczos3` (Java uses area-averaging
  `SCALE_SMOOTH`) into RGBA.

---

## 5. Security primitives (`mmcl-secure`)

### 5.1 `MachineSecretCipher` (must be byte-compatible with Java)

Protects cached auth tokens and the CurseForge key at rest. The format has to match exactly so
the Rust build can later read the Java folder.

- **Envelope**: `base64_std_padded(salt[16] || iv[12] || ciphertext || tag[16])`. The minimum
  combined length is 28 bytes. Shorter input to `decrypt` gives `Ok(None)`. `None`, empty or
  blank input gives `Ok(None)`. Bad base64 is a clean `Err`.
- **Cipher**: AES-256-GCM, 128-bit tag appended (the `aes-gcm` crate's default layout matches
  Java's), fresh random salt and IV per encryption.
- **Key**: PBKDF2-HMAC-SHA256, **65536** iterations, 32-byte output. The password is the UTF-8
  bytes of the fingerprint (SunJCE encodes the `char[]` as UTF-8). The salt is the per-blob
  salt. One derivation per call. No fallback to old fingerprints. A tag mismatch is an `Err`, so
  callers can tell corrupt or wrong-machine data from missing data.
- **Fingerprint**: `"{user}|{os_name}|{hw_uuid}|install:{secret_hex}"`, where a missing
  component is `""`. (`assemble_fingerprint` is pure and tested.)
  - `user`: env `USER`, then `USERNAME` (non-empty), then the OS account name (Java
    `user.name`; `whoami::username()`).
  - `os_name`: **Java's `os.name` string exactly**: `"Windows 10"`, `"Windows 11"`,
    `"Windows Server 2022"` and so on (derived from `RtlGetVersion` major, minor, build and
    product type, with build `>= 22000` meaning 11), `"Mac OS X"` on macOS, `"Linux"` on Linux.
    Put this in a `java_os_name()` helper with a table.
  - `hw_uuid`: must match what oshi returns. Windows: WMI `SELECT UUID FROM
    Win32_ComputerSystemProduct` (crate `wmi`). macOS: `IOPlatformUUID` of
    `IOPlatformExpertDevice` (IOKit). Linux: `/sys/devices/virtual/dmi/id/product_uuid`, which
    is usually root-only; oshi then tries `dmidecode` and others. **Open question Q4**: oshi
    returns `"unknown"` rather than `""` in some failure cases. Confirm with
    `MachineSecretCipherSubprocessHarness` before relying on cross-reading.
  - `secret_hex`: `<config>/machine-key.bin`. If it is a regular file of at least 32 bytes,
    lowercase hex of **all** its bytes. Otherwise (missing, truncated) generate 32 random bytes,
    create dirs, write, apply `FilePermissions::owner_only`. If persisting fails, log
    `log.machineCipher.persistSecretFailed` and use an ephemeral secret for this process.
    Cached once (double-checked `OnceLock`).
- **Guard:** the network MAC address used to be part of the fingerprint. macOS randomizes MACs,
  which broke decryption on about 90% of launches. Never add volatile inputs.
- API:

```rust
pub fn encrypt(plain: &str) -> Result<String>;
pub fn decrypt(envelope: &str) -> Result<Option<String>>;
pub fn encrypt_bytes(plain: &[u8]) -> Result<Vec<u8>>;
pub fn decrypt_bytes(combined: &[u8]) -> Result<Option<Vec<u8>>>;
pub(crate) fn assemble_fingerprint(user: Option<&str>, os: Option<&str>, uuid: Option<&str>, secret: &str) -> String;
```

Allow an injectable key source (trait `MachineKeySource`) so tests can simulate "wrong
machine" without hardware. Tests: `MachineSecretCipherSecurityTest` (null and short handling,
tamper detected at every byte position, wrong key fails cleanly) and
`MachineSecretCipherFingerprintTest`.

### 5.2 `FilePermissions`

- `owner_only(path)` for files and `owner_only_dir(path)` for directories (separate on purpose).
  **Guard:** a directory at 0600 locks out its own owner.
- Unix: `set_permissions(0o600 / 0o700)`.
- Windows: Java replaces the DACL with a single ALLOW ACE (all permissions) for the file's
  **owner**. Rust: `GetNamedSecurityInfoW(OWNER_SECURITY_INFORMATION)` to get the owner SID,
  build an ACL with one `ACCESS_ALLOWED_ACE` (`FILE_ALL_ACCESS`, plus inheritance flags for
  directories), then `SetNamedSecurityInfoW(DACL_SECURITY_INFORMATION |
  PROTECTED_DACL_SECURITY_INFORMATION)`.
- Every failure logs (`log.filePermissions.posixTightenFailed` / `aclTightenFailed`) and returns.
  Callers must not assume it worked. Idempotent.
- Tests: `FilePermissionsTest` (Unix modes; make the Windows path a separate test that reads the
  DACL back).

### 5.3 Install-URL classification (with the URI handler, see 6.4)

`classify_install_url(url) -> Accept | RequireConfirmation | Reject`, used by both the deep-link
handler and the MCP install tool:

- `None`, empty or blank gives Reject. Any char `< 0x20` or `0x7F` gives Reject.
- Strict parse fails gives Reject. The scheme must be `https` (any case). A missing or blank
  host gives Reject.
- Host lowercased is in `{"micauseaststorage.blob.core.windows.net"}`: Accept. Otherwise
  RequireConfirmation. Exact match only (`evil.<trusted>.attacker.com` requires confirmation).
- Java's `URI.getHost()` is null for registry-style authorities (for example hosts containing
  `_`). Treat a host that is not a valid DNS name, IPv4 or `[IPv6]` literal as missing, which
  gives Reject.
- Tests: `LauncherUriHandlerClassifyInstallUrlTest` (17 cases).

---

## 6. Native platform integration (`mmcl-platform`)

### 6.0 What GPUI 0.2.2 already gives us (checked against the crate source)

| Need | GPUI 0.2.2 | Notes |
|---|---|---|
| Native window handle | `impl raw_window_handle::HasWindowHandle for Window`: Windows gives `Win32WindowHandle { hwnd }`, macOS gives `AppKitWindowHandle { ns_view }` (the window is `[ns_view window]`), Linux gives X11 or Wayland handles | **Do not call `display_handle()` on Windows**: it is `unimplemented!()` and panics. |
| Custom title bar | `TitlebarOptions { appears_transparent: true, traffic_light_position }` | Windows: GPUI handles `WM_NCCALCSIZE` (with maximized insets and the auto-hide taskbar fix) and `WM_NCHITTEST`. macOS: sets `NSFullSizeContentViewWindowMask`, `titleVisibility = Hidden`, transparent title bar and the traffic-light position. |
| Caption hit testing | `div().window_control_area(WindowControlArea::{Drag, Min, Max, Close})` | Windows maps these to `HTCAPTION`/`HTMINBUTTON`/**`HTMAXBUTTON`** (so Windows 11 snap layouts work)/`HTCLOSE`, and GPUI handles the NC button press and release itself. |
| Window drag | `window.start_window_move()`, `window.titlebar_double_click()` | macOS uses AppKit's native drag. Double-click follows the user's "double-click title bar" preference. |
| Dark/light | `cx.window_appearance()`, `window.appearance()`, `window.observe_window_appearance()` | macOS reads `effectiveAppearance` (no null bug). Linux reads xdg-desktop-portal `color-scheme`. Windows reads `UISettings` foreground. |
| Translucent backdrop | `WindowBackgroundAppearance::{Opaque, Transparent, Blurred}` | macOS `Blurred` inserts an `NSVisualEffectView` (macOS 12+). Windows uses `SetWindowCompositionAttribute` accents, **not** Mica. |
| macOS menu bar | `cx.set_menus(Vec<Menu>)` | |
| Dock menu | `cx.set_dock_menu(Vec<MenuItem>)` | macOS: dock menu. **Windows: it rewrites the jump list** with Zed-specific `--dock-action N` tasks plus a "Recent Folders" category. **Never call it on Windows** or it overwrites our jump list. |
| Open-URL events | `cx.on_open_urls(cb)`, `cx.on_reopen(cb)` | macOS only in practice: `mmcl://` links and `.mmcjson` file URLs. |
| URL scheme registration | `cx.register_url_scheme` | macOS 12+ only. Windows and Linux are unimplemented, so we do our own. |
| DPI awareness | `gpui.manifest.xml` declares PerMonitorV2 | Only when the manifest is embedded (gpui feature `windows-manifest`, or our own manifest). |
| Tray, toasts, taskbar progress, Mica, AUMID, jump list, badge, single instance | **none** | Built here. |

### 6.1 Theme probe (`OsThemeUtilities`)

- `is_os_dark()`: in GUI code use `cx.window_appearance()` (Dark or VibrantDark means dark).
  Outside a GPUI context (TUI mode, early startup) use the `dark-light` crate.
- On a detector error, return **dark**, the launcher's "when in doubt" default, except macOS
  where a missing `AppleInterfaceStyle` means Light. GPUI and dark-light both handle that
  correctly, so the Java NPE workaround is not needed.
- Live changes: `window.observe_window_appearance` drives the Automatic and Native themes.

### 6.2 Single-instance lock and IPC (`SingleInstanceLock`, `WindowsForeground`)

**Lock**: bind a TCP listener on `127.0.0.1:<port>` (47821, dev 47822; see Q1) with backlog 8,
using `socket2`. **On Windows set `SO_EXCLUSIVEADDRUSE`**, as Java's `ServerSocket` does there,
so no other process can co-bind with `SO_REUSEADDR`. The OS frees the port on any exit, so there
are no stale lock files.

- `try_acquire() -> bool` is idempotent: true if this process already holds the lock. This
  matters because the restart loop calls it again. On success: write the token, start the
  accept thread, log `log.singleInstance.lockAcquired`. On failure keep the error
  (`last_acquire_failure()`); on Windows an access error usually means a Hyper-V/WSL reserved
  port range.
- **Token**: 32 random bytes as lowercase hex, written to `<client config>/single-instance.token`
  (client path whatever the mode; see 0.3), then `owner_only`. If writing fails, log
  `log.singleInstance.tokenPersistFailed` and keep the in-memory token (forwarding is then off
  for the session).
- **Accept loop** (thread `single-instance-ipc`): `accept()` until the listener closes. Each
  connection goes to `tasks::spawn(handle_incoming)`.
- **`handle_incoming`**: 2 s read timeout. Line 1 is the token. A missing line, no expected
  token, or a mismatch under constant-time `slow_equals` (UTF-8 bytes, equal length, XOR
  accumulate) logs `log.singleInstance.badToken` and replies `REJECTED\n`. Line 2 is the
  payload; empty or blank gives a silent close. Otherwise reply `OK WINDOW\n` or
  `OK NOWINDOW\n` (whether the main window exists yet), **then** bring the window forward, then
  if the payload is an `mmcl://` URI log `log.singleInstance.dispatchDeepLink` and call
  `deeplink::handle(payload)`; otherwise log `log.singleInstance.unrecognizedPayload`. Reply
  write errors are ignored: an older sender hangs up early, and its request must still run.
- **`forward_to_running_instance(payload) -> ForwardResult`**:
  - no payload gives `Unreachable`; no or blank token file gives `NoToken`;
  - replace `\n` and `\r` in the payload with spaces;
  - **Windows: call `AllowSetForegroundWindow(ASFW_ANY = u32::MAX)` before sending.** The
    holder runs in the background and could only flash its taskbar button; this process was
    started by the user's click and may pass on the right to take the foreground;
  - connect, send `token\npayload\n`, 2 s read timeout, read one line:
    - timeout gives `Unreachable` (something that stays silent is not a launcher);
    - a read error is treated like a hang-up, giving `None`;
  - `parse_reply(None) = Unconfirmed` (older launchers hang up without replying);
    `"REJECTED" = Rejected`; two words with the first `"OK"` gives `DeliveredNoWindow` when the
    second is `NOWINDOW`, else `Delivered`; anything else is `Unconfirmed`. Trim first;
  - a connect error gives `Unreachable`;
  - `ForwardResult::delivered()` is true for `Delivered` and `Unconfirmed`. For the other
    results the second launch must **tell the user** why it stopped (and write it to
    `startup.log`) rather than exit silently. **Guard:** issue #92, a silent second launch.
- **`release()`**: close the listener (the accept loop ends), clear the token, delete the token
  file.
- **Bringing the window forward** on the holder: `window.activate_window()` plus, on Windows,
  `ShowWindow(SW_RESTORE)` when minimized and `SetForegroundWindow(hwnd)`.
- The payload for a plain second launch is `mmcl://open`.
- Tests: `SingleInstanceLockReplyTest` (7) and `SingleInstanceLockSlowEqualsTest` (7) port 1:1.
  Add an integration test: bind on an ephemeral port, forward, check the reply.

### 6.3 Startup hardening (Windows), first lines of `main`

1. `WindowsDllSearchHardening`: `SetDllDirectoryW(w!(""))` takes the current directory out of
   the DLL search order. Leave `PATH` in, so installed RGB vendor SDKs (`RzChromaSDK64`,
   `iCUESDK`, `AURA_SDK`) still resolve. **Do not** call `SetDefaultDllDirectories` (it removes
   `PATH`). Log `log.dllHardening.returnedFalse/failed`. Must run before any `libloading` call.
2. `WindowsDpiAwareness`: embed a manifest declaring PerMonitorV2, and at runtime try
   `SetProcessDpiAwarenessContext(-4)`, then `(-3)`, then `shcore!SetProcessDpiAwareness(2)`,
   ignoring every failure (log `log.dpiAwareness.setupFailed` on the last). It must happen before
   GPUI creates a window. (Java needed it to keep the non-client area scaled; GPUI already
   assumes PerMonitorV2.)
3. **AppUserModelID**: `SetCurrentProcessExplicitAppUserModelID`. Java uses
   `"com.micatechnologies.minecraft.launcher.LauncherCore"` (with `DEV` appended in dev). The
   Rust build should use its own id while side by side (Q1). It groups taskbar buttons, toasts
   and the jump list. The Start-menu shortcut the installer creates must carry the same AUMID
   (`System.AppUserModel.ID`) for WinRT toasts to work (packaging subsystem).

### 6.4 Deep links (`LauncherUriHandler`)

```
mmcl://add?url=<urlenc manifest>     install a modpack
mmcl://play?name=<pack or friendly name>
mmcl://join?url=<urlenc manifest>    install if needed, then play (Discord "Join")
mmcl://join?vanilla=<version id>     install the vanilla version if needed, then play
mmcl://open                          bring the window forward
```

- `is_launcher_uri(s)`: case-insensitive prefix `mmcl://`. (`mmcl:play` does not match.)
- `handle(s)`:
  1. not a launcher URI: return;
  2. the `uriHandlerEnabled` setting is off: log `log.uriHandler.handlerDisabled`, return;
  3. strict parse; on failure log `log.uriHandler.malformedUri`;
  4. `action` = the authority host; if there is none, the text after the prefix up to the first
     `?`. Lowercase it;
  5. `params = parse_query(raw_query)`: split on `&`, skip empty segments, split at the first
     `=` (no `=` means value `""`), form-decode both sides (`+` is a space, `%XX` UTF-8). **Skip
     a pair with a malformed `%`** (Java `URLDecoder` throws). A later duplicate wins;
  6. log `log.uriHandler.dispatch` and dispatch; an unknown action logs
     `log.uriHandler.unknownAction`.
- Each action runs on `tasks::spawn`, because they block on dialogs:
  - **add**: missing url logs `log.uriHandler.addMissingUrl`. Then `confirm_install_url(url,
    header = dialog.uri.confirmInstall.headerAdd)`, then `install_modpack_by_url`, then toast
    `notification.uri.modpackAdded.*`, then refresh the main screen **only if it is the current
    screen** (never pull the user out of the editor). Failure gives
    `notification.uri.modpackAddFailed.*`.
  - **play**: find the pack by pack name, then by friendly name (case-insensitive). Not found
    gives toast `notification.uri.modpackNotInstalled` (with the name). Then
    `ready_to_launch_via_deep_link()`, then `confirm_deep_link_launch(friendly)`, then
    `play(pack)`.
  - **join (url)**: `ready_to_launch_via_deep_link()` **first** (no point installing if it
    cannot play). Already installed at that URL: confirm the launch, then play. Otherwise
    `confirm_install_url(header = ...headerJoin)`, install, toast
    `notification.uri.discordJoin.title` / `discordJoinModpack.body`, look it up again, play;
    not found logs `log.uriHandler.joinPackNotFound`.
  - **join (vanilla)**: ready check, confirm (`dialog.uri.confirmLaunch.vanillaName`), install
    the vanilla version if it is missing (toast `discordJoinVanilla.body`), create the vanilla
    pack, play.
  - **open**: bring the window forward.
- `ready_to_launch_via_deep_link()`: if a game is running, log
  `log.uriHandler.refuseGameRunning`, toast `notification.uri.gameRunning.*`, bring the window
  forward and return false. Then `may_leave_for_launch()` (refused on the login screen; offers
  to keep unsaved Settings).
- `confirm_deep_link_launch(name)`: always asks, because web pages and same-user processes can
  send these links: `dialog.uri.confirmLaunch.*` with buttons launch and cancel. Anything other
  than button 1 gives false (log `log.uriHandler.userDeclinedLaunch`). In-app Play never goes
  through this.
- `confirm_install_url`: Accept gives true. Reject logs `log.uriHandler.refuseInstallUrl`, toasts
  `notification.uri.unsafeLink.*` and gives false. RequireConfirmation asks
  (`dialog.uri.confirmInstall.body` with `host_of(url)`); anything but button 1 gives false.
- `host_of(url)`: the parsed host, falling back to the raw string on a parse failure or a
  missing host.
- Delivery paths: argv at cold start (the app crate parses it and queues the URI until the main
  window is up), the IPC payload (6.2), GPUI `on_open_urls` (macOS), and the Discord join event.
- Tests: `LauncherUriHandlerQueryAndSchemeTest` (15 cases) and the classifier tests.

### 6.5 Scheme and file-type registration (`SchemeRegistrar`)

Runs on every startup off the UI thread. Idempotent. **Skipped in dev builds** (never register an
IDE or `target/debug` binary). Skipped when the executable path is unknown
(`log.schemeRegistrar.exePathUnknown`). Rust's executable path: `std::env::current_exe()`, or
`$APPIMAGE` when set (AppImage). Java used `jpackage.app-path`, which pointed at a native C
starter in front of the jpackage executable; the Rust build is a single binary. Errors log
`log.schemeRegistrar.registrationFailed`.

**Windows** (HKCU only, no admin; crate `windows-registry` or `winreg`):

```
HKCU\Software\Classes\mmcl                      (default)="URL:Mica Minecraft Launcher Protocol"
                                                "URL Protocol"=""
HKCU\Software\Classes\mmcl\shell\open\command   (default)="\"<exe>\" \"%1\""
HKCU\Software\Classes\.mmcjson                  (default)="MicaMinecraftLauncher.Modpack"
HKCU\Software\Classes\MicaMinecraftLauncher.Modpack            (default)="Mica Minecraft Modpack"
HKCU\Software\Classes\MicaMinecraftLauncher.Modpack\shell\open\command (default)="\"<exe>\" \"%1\""
HKCU\Software\Classes\MicaMinecraftLauncher.Modpack\DefaultIcon (default)="\"<config>\icons\mmcjson-file.ico\",0"
```

The `.ico` is copied from the bundled resource `mmcjson-file.ico` to `<config>/icons/` every run,
so an updated icon lands. A missing resource logs `log.schemeRegistrar.icoMissing`. Improvement
over Java: call `SHChangeNotify(SHCNE_ASSOCCHANGED, SHCNF_IDLIST, null, null)` afterwards so
Explorer picks up the change.

**Linux** writes `~/.local/share/applications/mica-minecraft-launcher.desktop` (name is Q1):

```
[Desktop Entry]
Type=Application
Name=<app name>
Exec="<exe>" %u
Terminal=false
Icon=mica-minecraft-launcher
MimeType=x-scheme-handler/mmcl;application/x-mmcjson;
Categories=Game;
Keywords=minecraft;modpack;forge;launcher;mica;
NoDisplay=false
Actions=play-pack-0;play-pack-1;...      (only when there are recent packs)

[Desktop Action play-pack-0]
Name=<friendly name, CR/LF/<0x20 removed>
Exec="<exe>" "mmcl://play?name=<pct-encoded pack name>"
```

- Recent packs: `RecentPacks::get_recent(5)`. Packs with a blank name are skipped. Action ids
  are `play-pack-{index}` (the spec allows only `[A-Za-z0-9-]`). A lookup failure logs
  `log.schemeRegistrar.recentPacksLookupFailed` and leaves out the block.
- Percent-encoding for the `Exec` URL: keep `A-Za-z0-9-_.~`, encode everything else as UTF-8
  `%XX` (uppercase hex), so a space becomes `%20`, never `+`. The same encoder is used for the
  Windows jump list.
- Icon staging (once, guarded by `256x256/apps/mica-minecraft-launcher.png` existing): scale the
  bundled `micaminecraftlauncher.png` to 256, 128, 64, 48 and 32 into
  `~/.local/share/icons/hicolor/NxN/apps/`.
- MIME (once, guarded by the file existing): `~/.local/share/mime/packages/mica-minecraft-launcher.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<mime-info xmlns="http://www.freedesktop.org/standards/shared-mime-info">
  <mime-type type="application/x-mmcjson">
    <comment>Mica Minecraft Modpack</comment>
    <glob pattern="*.mmcjson"/>
  </mime-type>
</mime-info>
```

  then run `update-mime-database ~/.local/share/mime`.
- Then run `update-desktop-database ~/.local/share/applications`.
- `run_quietly(cmd)`: spawn, drain output, wait at most 2 s, ignore every error.
- `refresh_recent_packs()` re-runs the whole registration (on Linux it rewrites `Actions=`).

**macOS**: nothing at runtime. `CFBundleURLTypes` (`mmcl`) and `CFBundleDocumentTypes` /
`UTExportedTypeDeclarations` (`.mmcjson`) live in `Info.plist` (packaging subsystem).
Registering at runtime would race the Launch Services cache.

### 6.6 Recent-modpacks surfaces (`JumpListManager`, `WindowsJumpList`, `LauncherActions`)

`jump_list::refresh()` runs at startup (after the scheme registration) and after every
successful launch, on a worker. Errors log `log.jumpListManager.refreshFailed`.

- **Windows**: `windows_jump_list::refresh()` with the `windows` crate COM bindings (not
  hand-built vtables). Skipped in dev builds and when the executable path is unknown
  (`log.jumpList.noExePath`).
  1. `CoInitializeEx(COINIT_APARTMENTTHREADED)`: `S_OK` means we uninitialize later, `S_FALSE`
     is fine, `RPC_E_CHANGED_MODE` (`0x80010106`) is fine, anything else logs
     `log.jumpList.coInitFailed` and stops. Better: run it on a dedicated short-lived thread
     that owns its apartment.
  2. `ICustomDestinationList` (`CLSID_DestinationList`), `BeginList(&mut min_slots)`, which
     returns the removed `IObjectArray` (ignored).
  3. `IObjectCollection` (`CLSID_EnumerableObjectCollection`), `Clear()`.
  4. For each of `RecentPacks::get_recent(5)` with a non-blank name: create `IShellLinkW`,
     `SetPath(exe)`, `SetArguments("mmcl://play?name=<pct>")`,
     `SetDescription(format("jumpList.tooltip.play", display))`, `SetIconLocation(exe, 0)`.
     Through `IPropertyStore`, `SetValue(PKEY_Title, display)` and `Commit()`. Then
     `AddObject`. One bad entry does not stop the rest.
  5. Cast to `IObjectArray`, `AppendCategory(LocalizationManager.get(
     "jumpList.category.recentModpacks"), arr)`, `CommitList()`. On any failure, `AbortList()`.
     The previous list survives because only `CommitList` applies it.
  6. Log HRESULT failures as `log.jumpList.hresultFailure` (where, hex).
  - `PKEY_Title` is `{F29F85E0-4FF9-1068-AB91-08002B27B3D9}, 2`. Use `InitPropVariantFromString`
    or `PROPVARIANT::from(&str)` from `windows` instead of laying out memory by hand.
  - The jump list depends on the process AUMID (6.3). **Never call GPUI's `set_dock_menu` on
    Windows** (6.0).
- **Linux**: `scheme_registrar::refresh_recent_packs()` (the `.desktop` `Actions=`). There must
  be one writer for that file.
- **macOS**: rebuild the dock menu with `cx.set_dock_menu(build_dock_menu())`. It has no on-show
  hook, so rebuild after each launch.

**Menu contents** (`LauncherActions`):

- Shared tray menu (Windows and Linux): `menu.dock.show` (with the app name), separator,
  `menu.dock.playLast`, `menu.dock.openMods`, separator, `menu.dock.quit` (with the app name).
- macOS dock menu: Show, separator, submenu `menu.modpacks.playRecent` (up to **8** recent packs
  with `last_played > 0`, newest first; when empty, one disabled `menu.modpacks.playRecent.empty`
  item), `menu.dock.openMods`, separator, `menu.modpacks.browse`, `main.navbar.settings`,
  separator, Quit.
- Actions (callable from any thread):
  - `show_launcher` brings the window forward;
  - `play_last` resolves `ConfigManager.lastModPackSelected` by pack name, then by friendly
    name; none gives toast `notification.shell.noRecentModpackPlay.*`;
  - `play_modpack(pack)` on a worker: `ready_to_launch()` (`may_leave_for_launch`; several games
    may run, and the session registry refuses conflicts itself), save it as the last pack, play;
  - `open_browse` and `open_settings`: check `may_navigate_away()` first, log
    `log.launcherActions.openBrowseFailed/openSettingsFailed`;
  - `open_help` opens the getting-started topic;
  - `open_last_mods_folder` creates `<pack root>/mods` if needed and opens it with the `open`
    crate; no last pack gives toast `notification.shell.noRecentModpackMods.*`;
  - `quit` calls `request_quit`.
- In GPUI these are `Action`s (for `set_menus` and `set_dock_menu`) plus plain functions (tray
  callbacks run on other threads and post to the UI thread).

### 6.7 Notifications and tray (`NotificationManager`)

API: `info(title, body)`, `success`, `warn`, `error`. Success looks the same as info.

Java behaviour to keep:

- The tray icon is created **lazily on the first notification** and kept for the session.
  **Guard:** adding and removing it around each toast dropped balloons on Windows (`NIM_DELETE`
  right after `NIM_MODIFY`). Left-click or activate shows the launcher. Right-click shows the
  shared menu. The tooltip is the app name. The icon is `micaminecraftlauncher.png`. `shutdown()`
  removes it (idempotent).
- **Every notification is also logged** (`log.notification.fallback`: title plus
  `" — body"` when there is a body), as a paper trail.
- When native toasts are unavailable, fall back to the log line only.

Rust:

| OS | Toast | Tray |
|---|---|---|
| Windows | `notify-rust` (WinRT toast through `tauri-winrt-notification`) with `app_id = AUMID`. WinRT toasts from an unpackaged app need a Start-menu shortcut with that AUMID. Without one, fall back to a **`Shell_NotifyIconW` `NIF_INFO` balloon** on our tray icon (what Java's `TrayIcon.displayMessage` did; Windows 10+ shows it as a toast). Icon flags `NIIF_INFO`/`NIIF_WARNING`/`NIIF_ERROR`. | `tray-icon` (its message window is pumped by GPUI's Win32 loop on the main thread), or hand-rolled `Shell_NotifyIconW` if we need the balloon. Q5. |
| macOS | `notify-rust` (mac-notification-sys), or `UNUserNotificationCenter` through objc2 (needs the app bundle) | `tray-icon` gives an `NSStatusItem`. Java's AWT tray did create a menu-bar extra on macOS, but only after the first toast. Keep or drop (Q5). |
| Linux | `notify-rust` over D-Bus `org.freedesktop.Notifications` (zbus). This replaces Java's `notify-send` fallback, the common path on GNOME/Wayland. Hints: app name, icon `mica-minecraft-launcher`, **urgency Critical for errors** (so they do not auto-expire), Normal otherwise. | `ksni` (StatusNotifierItem over D-Bus): KDE, and GNOME with the AppIndicator extension. Avoid `tray-icon` on Linux; it needs GTK and libappindicator plus a GTK main loop that GPUI does not run. |

Errors log `log.notification.displayFailed`, `trayInitFailed`, `trayImageMissing`,
`trayUnsupported`.

### 6.8 Taskbar and dock progress (`TaskbarProgressManager`, `MacOsDockManager`)

One process-wide owner. Every call is safe from any thread (it posts to the UI thread).

```rust
pub fn attach(window: &Window);                       // once; deferred until shown
pub fn set_progress(p: Progress);                      // Progress::Fraction(f64) | Indeterminate
pub fn stop();
pub fn show_full_error();                             // "update available" attention cue
pub fn set_launch_progress(owner: LaunchId, p: Progress);
pub fn end_launch_progress(owner: LaunchId);
pub fn shutdown();
pub(crate) fn combine(values: &[Progress]) -> Progress; // pure
```

- `combine`: empty gives `Fraction(0.0)`. Any indeterminate (or a negative value) gives
  `Indeterminate`. Otherwise the mean of `min(1, f)`. `set_launch_progress` stores the value per
  owner in a map and pushes the combination. `end_launch_progress` removes it, then `stop()` if
  none are left, else pushes the combination. **Guard:** with several launches, each one
  overwrote the bar and the first to finish cleared it for all. Tests:
  `TaskbarProgressCombineTest` (5 cases).
- **Guard:** keep **one** shared native wrapper per session. Several wrappers on the same HWND
  interleaved and left the bar stuck.
- **Windows** (`ITaskbarList3` from the `windows` crate, `CLSID_TaskbarList`, `HrInit()`, created
  on the UI thread after the window is shown):
  - fraction: `SetProgressState(TBPF_NORMAL)`, `SetProgressValue(round(f * 100), 100)`;
  - indeterminate: `TBPF_INDETERMINATE`;
  - stop: `TBPF_NOPROGRESS`;
  - full error: `TBPF_ERROR` plus `SetProgressValue(100, 100)`;
  - shutdown: stop, release.
  - Calls fail until the taskbar button exists. Create lazily and retry on the next call when
    `HrInit` or the first call fails. GPUI owns the window procedure, so we cannot catch
    `TaskbarButtonCreated`.
  - Log keys: `log.taskbar.initFailed/updateFailed/stopFailed/errorOverlayFailed`.
- **macOS** (dock, objc2-app-kit, main thread):
  - progress `0..=100` (indeterminate is shown as a **static 50%**, since the dock cannot draw an
    indeterminate arc cleanly): set an `NSDockTile` `contentView`, an `NSImageView` with the app
    icon plus an `NSProgressIndicator` (bar style) along the bottom, then `dockTile.display()`.
    This mirrors AWT. Clearing (`-1`) removes the `contentView` and calls `display()`;
  - `set_error` = 100%;
  - `request_attention(critical)` = `[NSApp requestUserAttention: critical ? NSCriticalRequest
    (0) : NSInformationalRequest (10)]`;
  - `set_badge(Some("1"))` / `clear_badge()` = `dockTile.setBadgeLabel:`;
  - `show_full_error` on macOS = set error, attention (not critical), badge `"1"`. The badge
    stays until the next session (the update stays available).
  - `shutdown` = clear progress, clear badge, clear the dock menu.
  - Log keys: `log.macDock.*`.
- **Linux** (improvement; AWT only did this on Unity and KDE): emit the D-Bus signal
  `com.canonical.Unity.LauncherEntry.Update("application://<desktop id>.desktop",
  {"progress": f64, "progress-visible": bool, "urgent": bool, "count": i64, "count-visible":
  bool})` with `zbus`. KDE Plasma's task manager and Ubuntu's Dash-to-Dock honour it.

### 6.9 Windows chrome (`WindowChromeManager`, `WindowsCustomChromeManager`, `WindowsTitleBarControls`, `WindowsShellRefresh`)

**HWND**: `window.window_handle()` gives `RawWindowHandle::Win32(h)`, and `HWND(h.hwnd.get() as
_)`. This replaces Java's Glass title-matching lookup.

**Custom caption (replaces `WindowsCustomChromeManager` entirely)**: open the main window with
`TitlebarOptions { appears_transparent: true, .. }`. GPUI then reclaims the frame, insets it when
maximized (per-DPI), keeps the resize borders, and hit-tests the areas we mark:

- the title strip is **52 logical px** tall (`TITLE_BAR_HEIGHT`), multiplied by the launcher's
  UI scale, since it sits inside the zoomed content;
- caption buttons are **46 px** wide (`BUTTON_WIDTH`). From right to left: close, maximize,
  minimize, then the help "?" button (a launcher button, not a window control);
- mark empty title-strip space with `WindowControlArea::Drag`; mark the maximize button with
  `WindowControlArea::Max` (so **Windows 11 snap layouts work**), minimize with `Min`, close with
  `Close`. GPUI's NC handling then performs min, maximize/restore and close natively.
  Interactive elements inside the strip (navbar controls, the help button) must *not* carry
  `Drag`, which matches Java's "interactive control means HTCLIENT" rule;
- close must go through the window's should-close path (GPUI `on_window_should_close`) so each
  screen's close handler runs (the main menu's Exit means app close).

What Java needed and GPUI either does or must be checked in a spike (Q2):

| Java fix | GPUI 0.2.2 |
|---|---|
| Drop `WS_CAPTION`, keep `WS_THICKFRAME`, maximize, minimize, sysmenu | handled by GPUI's hide-title-bar mode |
| `DwmExtendFrameIntoClientArea(-1, -1, -1, -1)` to re-arm composition (**Guard**, issue #80: white top line and classic buttons) and to let Mica show | **we call it** after creation (it is harmless if GPUI also does) |
| `DwmDefWindowProc` first | not needed with GPUI's own hit testing |
| `HTMAXBUTTON` click needs `WM_SYSCOMMAND SC_MAXIMIZE/SC_RESTORE` on button-up (issue #80: "max did nothing") | GPUI handles `(HTMAXBUTTON, HTMAXBUTTON)` press/release; verify |
| `WM_NCACTIVATE` with `lParam = -1` to stop the default title bar flashing on focus change | **verify**; if it flashes, GPUI needs a patch or a `SetWindowSubclass` subclass of our own that rewrites `lParam` |
| Simulated hover on the OS-owned maximize button (NC mouse tracking, `TrackMouseEvent(TME_NONCLIENT)`) | GPUI turns NC mouse moves into hover; verify the max button shows hover |
| Maximized inset with `GetSystemMetricsForDpi(SM_CXFRAME) + SM_CXPADDEDBORDER` at the window DPI | GPUI `get_client_area_insets` |
| Re-binding on in-process restart (new window, stale static state) | not applicable; state is per window |

**Caption cluster view** (port of `WindowsTitleBarControls` as a GPUI component, Windows only):

- 1 px stroked SVG glyphs in a 10x10 box:
  - minimize `M0,5.5 H10`;
  - maximize `M0,0 H10 V10 H0 Z`;
  - restore `M0,2 H8 V10 H0 Z M2,2 V0 H10 V8 H8` (shown while maximized; follows
    `window.is_maximized()`);
  - close `M0,0 L10,10 M10,0 L0,10`.
- Close has a red hover; help has a blue hover and the `?` text.
- Accessible names: `titleBarControls.help/minimize/maximize/close`.
- Title-strip blend: the top navbar is transparent so the root background (or Mica) shows
  through. The secondary filter bar is left alone.
- Reserve `4 * 46` px trailing clearance in the top navbar so right-aligned items (player
  identity, "Running" status) never slide under the caption buttons.
- Main menu: put the refresh icon right before the update icon.
- Hide any other in-window help affordance (the cluster's help replaces it).

**DWM attributes** (`DwmSetWindowAttribute`, 4-byte values; all no-ops off Windows):

| Function | Attribute | Value |
|---|---|---|
| `apply_title_bar_dark_mode(dark)` | `DWMWA_USE_IMMERSIVE_DARK_MODE` = 20; if the HRESULT is non-zero retry with 19 (Windows 10 19H1/19H2) | `BOOL` |
| `apply_caption_color(Option<Rgb>)` | `DWMWA_CAPTION_COLOR` = 35 | COLORREF `0x00BBGGRR`, `None` gives `0xFFFFFFFF` (`DWMWA_COLOR_DEFAULT`) |
| `apply_border_color` | 34 | same |
| `apply_caption_text_color` | 36 | same |
| `clear_caption_color` / `clear_border_color` | 35 / 34 | `0xFFFFFFFE` (`DWMWA_COLOR_NONE`: no fill, so Mica shows through) |
| `apply_backdrop(kind) -> bool` | `DWMWA_SYSTEMBACKDROP_TYPE` = 38 | 1 none/auto, 2 Mica, 3 Acrylic, 4 Mica Alt. Returns `HRESULT == S_OK`; Windows 10 and Windows 11 before 22H2 return `E_INVALIDARG`. |

- GPUI re-applies immersive dark mode from the **system** appearance on `WM_SETTINGCHANGE
  ImmersiveColorSet`. Our value follows the **app theme**, so re-apply it from
  `observe_window_appearance` and after every theme change.
- `supports_mica_backdrop(window) -> bool`:
  1. **Transparency effects** must be on. Read
     `HKCU\Software\Microsoft\Windows\CurrentVersion\Themes\Personalize\EnableTransparency`
     (DWORD). A missing or unreadable value counts as **on**. Read it on **every call** (the
     user, the accessibility setting and battery saver all flip this one value at runtime). Log
     a failure as `log.windowChrome.transparencyReadFailed`.
  2. **OS support**: probed once by `apply_backdrop(Mica)` and cached for the process, but only
     once the HWND exists (with no HWND, return false without caching).
  - **Guard:** a transparent scene over a rejected or disabled backdrop renders **pure black**.
    The Native theme falls back to an opaque palette when this returns false.
- Mica with GPUI: GPUI's Windows renderer uses a DirectComposition swap chain with premultiplied
  alpha. Plan: `window_background: Opaque` (no accent policy), `DwmExtendFrameIntoClientArea(-1)`,
  backdrop type 2, and draw the root with a transparent background in the Native theme. **Spike
  required (Q2):** check whether GPUI's `Transparent` accent policy (`SetWindowCompositionAttribute`
  `ACCENT_ENABLE_TRANSPARENTGRADIENT`) conflicts with Mica, and whether the swap chain's alpha
  reaches DWM.
- `force_full_repaint(window)`: `RedrawWindow(hwnd, null, null, RDW_INVALIDATE | RDW_ERASE |
  RDW_FRAME | RDW_ALLCHILDREN | RDW_UPDATENOW)` (`0x0585`), plus `window.refresh()`.
- `force_frame_refresh(window)` (`WindowsShellRefresh`): `GetWindowRect`, then `SetWindowPos(
  hwnd, null, x, y, w, h, SWP_FRAMECHANGED | SWP_NOZORDER | SWP_NOACTIVATE)`. That re-asserts
  the *real* position, which Windows 11's per-monitor taskbar notices; a no-move version gets
  debounced. If `GetWindowRect` fails, use `0, 0, 0, 0` with
  `SWP_FRAMECHANGED | SWP_NOMOVE | SWP_NOSIZE | SWP_NOZORDER`. Called when the window moves
  between monitors (`window.on_moved` / display change).
- Log keys: `log.windowChrome.*`, `log.customChrome.*`, `log.shellRefresh.*`.

### 6.10 macOS chrome (`MacOsTitleBarManager`, `MacOsToolbarManager`, `MacOsVibrancyManager`)

**NSWindow from GPUI**: `RawWindowHandle::AppKit(h)` gives `h.ns_view`; with objc2,
`let view: &NSView = h.ns_view.cast().as_ref(); let window = view.window()`. Main thread only.

**Hidden-inset title bar** (Java set three NSWindow flags by hand): GPUI does it with
`TitlebarOptions { appears_transparent: true, traffic_light_position: Some(point(px(x), px(y))) }`.

- Pick `y` so the traffic lights sit **vertically centred in the 52 pt band**. That was the
  whole reason for the NSToolbar (JavaFX Glass ignored every other way of growing the title
  bar). In GPUI you just position the lights, which is what Zed does.
- In-window brand: hide the `navBrandLogo` and **keep** the screen-name label (the native title
  is hidden, so the label is the only thing naming the screen). Inset the top navbar's leading
  edge by **86 pt** (past the lights) and give it a minimum height of **52 pt**. Both are
  divided by the launcher UI scale: the lights and the native band do not zoom, the content
  does.
- **Why the title stays hidden** (keep in mind for any native toolbar): with a visible title,
  AppKit puts an `NSToolbarPrimaryTitleContainerView` across about 77% of the band, it wins hit
  tests, and dragging breaks. GPUI hides the title; do not re-enable it.
- **Window drag**: on mouse-down (primary button) on empty top-navbar space, call
  `window.start_window_move()` (AppKit `performWindowDragWithEvent`: follows the pointer 1:1 and
  snaps). Do not hand-roll a move loop; it lags behind the pointer. Clicks on buttons, text
  inputs and anything with its own click handler must not start a drag (in GPUI, interactive
  children stop propagation). Double-click: `window.titlebar_double_click()`.
- **Screens without a navbar** (login, launch progress) use the **whole root** as the drag
  region, minus interactive elements.

**Native toolbar** (`MacOsToolbarManager`). Java attached a real `NSToolbar` (unified style 3,
size mode small, no customization, no separator) with a runtime-built Obj-C delegate class
`MicaToolbarDelegate`. Item ids:

```
flexible space, MicaBrowse, MicaSettings, MicaHelp, [MicaUpdate if update pending], space, MicaAccount
```

- SF Symbols: `square.grid.2x2`, `gearshape`, `questionmark.circle`, `arrow.down.circle`,
  `person.crop.circle`, tinted with the theme accent through
  `NSImageSymbolConfiguration.configurationWithHierarchicalColor`, with the plain symbol as
  fallback.
- Labels: `main.navbar.browse`, `main.navbar.settings`, `menu.help.title`,
  `notification.update.available.title`, and the player name (falling back to
  `main.navbar.player`).
- The account item swaps in the player-head avatar loaded off-thread from
  `https://minotar.net/armor/bust/<uuid>/100.png`, and follows changes of the default account.
- Navigation gating: Browse, Settings, Update and Account are disabled (greyed, with
  `setAutovalidates:NO` so AppKit does not re-enable them) on screens that must not be bypassed
  (login, progress). The action handler re-checks the gate. Help is always enabled.
- The JavaFX controls the toolbar replaced (`#libraryBtn`, `#settingsBtn`, `.helpButton`,
  `.navRefreshIcon`, `.navUpdateIcon`, `#userImage`, `#playerLabel`, `.navDivider`) were hidden
  **only once the toolbar installed**.
- **Guard (blank buttons):** reinstall the toolbar on every show, because a hide/show can
  rebuild the NSWindow peer.

**Recommendation:** do not port the NSToolbar. In GPUI the band is ours. Draw Browse, Settings,
Help, Update and Account as GPUI elements in the title bar, centre the traffic lights with
`traffic_light_position`, and keep the gating, update-item and avatar behaviour. This gives the
same look and behaviour with no Obj-C runtime classes and none of the blank-button bug. If
strict native-item parity is wanted (Q3), the port is `objc2::define_class!` for the delegate
(retain it for the app's lifetime; `NSToolbar` holds its delegate weakly), `NSToolbar
initWithIdentifier:"MicaTitleBarToolbar"`, then `window.setToolbar`, `setToolbarStyle(3)`. A
spike must confirm GPUI's custom `NSWindow` subclass and hit testing get along with a toolbar.

**Vibrancy** (`MacOsVibrancyManager`): use `window.set_background_appearance(Blurred)`
(`NSVisualEffectView` behind the window) for the Native theme and `Opaque` otherwise. Also set
the **NSWindow's** appearance so the title-bar chrome follows the app theme:
`window.setAppearance(NSAppearance::appearanceNamed(NSAppearanceNameDarkAqua or
NSAppearanceNameAqua))`.

- **Guard:** use DarkAqua/Aqua, not VibrantDark/VibrantLight, which do not theme a window frame.
- **Guard:** set it on the NSWindow, not the content view (the title bar reads the window's
  appearance).
- `clear()` sets the appearance to match the OS dark/light.
- Java re-applied on every scene change because the first apply sometimes did not stick under
  Glass. With GPUI, apply once when the window opens and on each theme change; verify in the
  spike.
- `apply_title_bar_appearance(window, dark)` is the version without vibrancy, for secondary
  windows.
- The cross-platform `apply_title_bar_dark_mode(window, dark)` sends Windows to DWM, macOS to
  this, and does nothing on Linux.

**Menu bar** (`SystemMenuBarManager`, GUI slice): `cx.set_menus`. The app menu has About,
Preferences (cmd+,) and Quit. Then Modpacks (Home, Browse cmd+L, Refresh cmd+R, Play Recent
submenu), Game (Runtime, Modpack Editor), View (theme radio group and toggles), Window
(Minimize cmd+M, Zoom), Help (cmd+?). Rebuild it on navigation so recents and toggle state stay
current.

### 6.11 Linux window chrome

New for the Rust build: JavaFX ran with server-side decorations. On Wayland (GNOME), GPUI gets
client-side decorations (`window.window_decorations() == Decorations::Client{..}`). Draw the
same 52 px title band with caption buttons. Drag with `start_window_move`, resize edges with
`window.start_window_resize(edge)`, and handle the window-menu right-click with
`show_window_menu`. Under X11 or server-side decorations, keep the native frame. Request
`WindowDecorations::Client` only when the theme wants it (Q2).

### 6.12 Desktop shortcuts (`DesktopShortcutManager`, `system` package)

`create_shortcut(pack) -> Result<()>`:

1. `shortcut_name = sanitize_file_name(pack.pack_name)`.
2. Desktop dir: on Linux, `xdg-user-dir DESKTOP` (or `dirs::desktop_dir()`, which reads
   `user-dirs.dirs`); everywhere else `~/Desktop`. Missing gives `shortcut.error.noDesktop`.
3. Launcher path: `current_exe()`, or `$APPIMAGE`, or for a macOS app bundle the bundle's
   `Contents/MacOS/<exe>`. Java's JAR, `java -jar` and `pickBestExecutable` branches do not
   apply to a native Rust binary. Keep `pick_best_executable(candidates)` only if a lookup
   inside a folder survives (it prefers a name that contains or is contained in the trimmed app
   name, case-insensitive with the extension removed, else the alphabetically first). Its tests
   are in `DesktopShortcutManagerCommandBuildingTest`.
4. Icon: the pack's cached PNG logo (`pack.logo_path()`). Windows: write
   `<metadata>/icon_<sanitizedName>.ico` (4.5). macOS: `.icns`, falling back to the PNG. Linux:
   the PNG. Failure logs `log.desktopShortcut.iconConversionFailed` and continues without an
   icon.
5. Arguments are always `-c <pack>`.

Per platform:

- **Windows** `.lnk`: Java ran PowerShell `WScript.Shell`. Rust uses COM directly:
  `IShellLinkW` with `SetPath(exe)`, `SetArguments("-c " + windows_cmd_quote(
  strip_line_terminators(pack)))`, `SetIconLocation(ico, 0)`, then `IPersistFile::Save(
  "<desktop>\<name>.lnk", TRUE)`. No 30 s PowerShell timeout, no output drain, no
  `powershell` dependency. Keep `shortcut.error.*` messages for failures. **Guard:** a pack
  name comes from server JSON. `windows_cmd_quote` stops `pack" --flag "` from splitting into
  several args.
- **macOS** `<desktop>/<name>.app`:
  - `Contents/Info.plist` with `CFBundleExecutable=launch.sh`, `CFBundleIconFile=icon.icns`,
    `CFBundleName` = the name with `&` and `<` escaped, `CFBundlePackageType=APPL`,
    `CFBundleVersion` = the app version;
  - `Contents/Resources/icon.icns`;
  - `Contents/MacOS/launch.sh` = `#!/bin/bash\nexec <shell_single_quote(exe)> -c
    <shell_single_quote(strip_line_terminators(pack))>\n`, mode 0755.
  - **Guard:** single-quoting. A double-quoted bash string still expands `$` and backticks, so a
    pack name `"; rm -rf $HOME; #"` would have run.
- **Linux** `<desktop>/<name>.desktop`:

  ```
  [Desktop Entry]
  Type=Application
  Name=<stripped pack name>
  Exec=<desktop_exec_quote(exe)> -c <desktop_exec_quote(stripped pack)>
  Icon=<stripped icon path>        (if present)
  Terminal=false
  Categories=Game;
  ```

  Mode 0755, then `gio set <file> metadata::trusted true` (wait at most 2 s, ignore errors).
  **Guard:** strip CR/LF/NUL so a pack name cannot forge an `Exec=` line.
- Log `log.desktopShortcut.created`.

### 6.13 Discord Rich Presence (`DiscordRpcUtility`, crate `mmcl-discord`)

Java used `DiscordIPC` (jagrosh, CDAGaming fork). Rust: write a small IPC client. The crates on
crates.io (`discord-rich-presence`, `discord-presence`) do not do the `ACTIVITY_JOIN` event
subscription reliably.

**Transport**:

- Windows named pipe `\\.\pipe\discord-ipc-{0..9}`. **Open it overlapped** (crate
  `interprocess`, or tokio named pipes). A synchronous pipe handle serializes a blocking
  `ReadFile` on the reader thread against `WriteFile`, which deadlocks presence updates.
- Unix socket `{dir}/discord-ipc-{0..9}` where `dir` is the first set of `$XDG_RUNTIME_DIR`,
  `$TMPDIR`, `$TMP`, `$TEMP`, else `/tmp`. Also try the `app/com.discordapp.Discord/` (Flatpak)
  and `snap.discord/` subdirectories.

**Framing**: `u32 LE opcode`, `u32 LE length`, UTF-8 JSON. Opcodes: 0 handshake, 1 frame,
2 close, 3 ping, 4 pong (answer pings).

1. Handshake `{"v":1,"client_id":"841860482029846528"}`, then wait for the `READY` dispatch.
2. Subscribe: `{"cmd":"SUBSCRIBE","evt":"ACTIVITY_JOIN","nonce":<uuid>}`.
3. Presence: `{"cmd":"SET_ACTIVITY","args":{"pid":<pid>,"activity":{...}},"nonce":<uuid>}`.

Activity JSON:

```json
{"type":0,"state":S,"details":D,"timestamps":{"start":<epoch s>},
 "assets":{"large_image":K,"large_text":T,"small_image":K2,"small_text":T2},
 "party":{"id":<PARTY_ID>,"size":[1,4],"privacy":1},
 "secrets":{"join":<secret>}}
```

`party` and `secrets` appear only while a join secret is staged. **Guard:** `type` (Playing = 0)
is required; the Java library NPE'd without it.

State machine:

- `PARTY_ID`: a random UUID per process, so the party stays the same across screens.
- Staged join data `{secret, size = 1, max = 4}` is re-sent on **every** presence update so the
  Join button survives screen changes. (**Guard:** Discord shows Join only when `max > size`.)
- `set_rich_presence(state, details, start, large_key, large_text, small_key, small_text)`:
  - only when the `discordRpcEnable` setting is on;
  - connect lazily. Java tried once and then kept failing on every update. **Rust: retry the
    connection at most once per 30 s** (improvement) and stay silent when Discord is not
    running;
  - errors log `log.discordRpc.updateFailed` / `setupFailed`.
- `on_ready`: send the "in menus" presence: state `discordRpc.state.inMenus`, details
  `discordRpc.details.loading`, now, image key `mica_minecraft_launcher` for both large and
  small, text `discordRpc.image.launcher`.
- `set_menu_presence(screen)`: **return without change if any game session is RUNNING**
  (presence follows the running game). Otherwise clear join data, then state
  `discordRpc.state.inMenus`, details = the screen name, large `mica_minecraft_launcher` /
  `discordRpc.image.launcher`, small `clipboard` / the screen name.
- `set_game_presence(pack_name, custom_discord_rpc, manifest_url)`:
  - `custom_discord_rpc` means `exit()` and return (the game's own RPC takes over);
  - when the `discordInvitesEnable` setting is on and the URL is non-blank, stage
    `secret = build_join_secret(url)`. If it is over 128 UTF-8 bytes, log
    `log.discordRpc.joinSecretTooLong` and clear the join data instead. If invites are off,
    clear it;
  - state `discordRpc.state.inGame`, details `format("discordRpc.details.modPack",
    sanitize(pack))`, large `mica_minecraft_launcher`, small `game` /
    `discordRpc.image.inGame`.
- `sanitize(v)`: take the first 96 chars, drop chars `< 0x20` and `0x7F`. (**Guard:** newlines in
  a server-supplied pack name gave a multi-line social-engineering card.)
- `build_invite_link(url)` = `mmcl://join?url=` + `application/x-www-form-urlencoded(url)`. This
  is Java `URLEncoder`: keep `A-Za-z0-9.-*_`, space becomes `+`, everything else `%XX`
  (`form_urlencoded::byte_serialize` matches). `build_invite_link_from_pack(pack)`: a vanilla
  pack with a non-blank version gives `mmcl://join?vanilla=<enc>`, else the manifest URL form.
  `build_join_secret` = the invite link if it is at most 128 bytes, else `None`.
- **ACTIVITY_JOIN event**: `data.secret`. Log `log.discordRpc.joinReceived`. If it is an
  `mmcl://` URI, call `deeplink::handle` (which still confirms); otherwise log
  `log.discordRpc.joinNotRecognized`. A panic logs `log.discordRpc.joinHandleFailed`.
  `ACTIVITY_JOIN_REQUEST` ("ask to join") is not implemented.
- `exit()`:
  1. `SET_ACTIVITY` **without** `activity`;
  2. `SET_ACTIVITY` with `"activity": {}`;
  3. **sleep 500 ms** (**Guard:** 150 ms raced the close and the presence stuck as "In Game");
  4. close.
  Then clear the client and the join data. Use `run_bounded` at shutdown.

### 6.14 Power state and battery throttle (`PowerStateManager`)

- Probe with `starship-battery` (the maintained fork of `battery`). Cache it for **5 s**
  (`Instant` expiry) so the per-chunk hook stays cheap.
- Only **internal batteries** count. Skip a source whose name, model or vendor (uppercased)
  contains `UPS`, `APC ` (with the space), `CYBERPOWER`, `TRIPP LITE`, `TRIPPLITE`, `EATON` or
  `MINUTEMAN`. If it cannot be classified, it is not a battery. (**Guard:** Windows reports a
  plugged-in UPS at 100% as "not on AC line".)
- `on_battery = saw_battery && no battery is (on AC or charging or not discharging)`. That is,
  every internal battery must be **actively discharging**. (**Guard:** a full battery briefly
  reports "not on line" while plugged in.) Percentage: the first battery's `state_of_charge`,
  rounded to 0..100, else -1. A probe failure means "not on battery" and -1, logged as
  `log.powerState.probeFailed`.
- Optional Windows cross-check: `GetSystemPowerStatus().ACLineStatus == 0`.
- `should_throttle_downloads()`: **check `on_battery` first** and return false otherwise,
  without touching config. (**Guard:** reading the synchronized config on every chunk from every
  download thread convoyed the pool and once wedged it during a headless init deadlock.) Then
  the `batteryThrottleEnable` setting (default true; true if config is not ready), then
  `pct < 0`, which means throttle, or `pct < 30` (`BATTERY_THROTTLE_PCT_THRESHOLD`).
- `maybe_throttle(chunk)`: chunk at most 0, or not throttling: return. On the first engagement
  log `log.powerState.throttleEngaged` and latch `throttled_this_session`. The rate is
  `BATTERY_THROTTLE_BYTES_PER_SEC = 512 KiB/s` **per stream**. Per-thread state
  `[window_start_ns, bytes]` (thread-local). Sleep for the computed duration.
- **Pure core** `next_throttle_sleep_nanos(state: &mut [u64; 2], now, chunk, bps) -> u64`:
  - `bps <= 0` or `chunk <= 0` gives 0;
  - when `state[0] == 0` or `now - state[0] > 2 s`, reset to `[now, 0]`;
  - `state[1] += chunk`;
  - `target = state[1] * 1e9 / bps`, `deficit = target - (now - state[0])`;
  - `sleep = deficit >= 12 ms ? deficit : 0`;
  - when `state[1] >= bps`, set `state = [now + sleep, 0]`;
  - return `sleep`.
  - This deadline (closed-loop) design self-corrects sleep overshoot on battery, where a fixed
    per-chunk sleep collapses the rate.
- Tests: `PowerStateThrottleTest` (6 cases with a synthetic clock) port 1:1.
- `was_throttled_this_session()` is for UI.

### 6.15 macOS and Windows "open folder/URL"

`Desktop.open(folder)` and `Desktop.browse(uri)` map to the `open` crate (`open::that_detached`).
Only open URLs that passed `UrlValidation` (http/https).

---

## 7. App-level flows in this slice (implemented in the app crate on top of the traits)

### 7.1 `VerifyAction.run_for_packs(packs, on_all_succeeded)`

1. Packs active in a game session are removed, each with toast
   `notification.pack.running.title` / `notification.pack.running.verify` (name). If none are
   left, return **without** running the continuation.
2. An empty or missing list with a continuation runs the continuation on a worker (panic logs
   `log.verifyAction.continuationThrew`).
3. Worker: go to the launch-progress screen (failure logs `log.verifyAction.noProgressGui` and
   returns). For each pack with a non-blank manifest URL, build a tracker with these steps:
   - not vanilla: `MODPACK_CONTENT`, `FORGE_LIBS`;
   - always `MC_LIBS_ASSETS`, `JRE_INSTALL`;
   - when it uses post-install steps (Forge/NeoForge, not Fabric): `FORGE_PROCESSORS`;
   - always `SECURITY_SCAN`.
   Title: `verify.progress.titleCounted` (name, i, total) when there are several packs, else
   `verify.progress.title`. Attach the tracker and run `pack.verify_all_files_now()`, counting
   successes and failures (log `log.verifyAction.packFailed`). Pack name fallback: friendly
   name, then pack name, then `verify.packName.fallback`.
4. No failures and a continuation: go to the main screen, then run the continuation **instead
   of** the "verify complete" toast. Otherwise toast `notification.verify.complete.bodyOne` or
   `bodyMany`, or on failure `notification.verify.completedWithErrors` (success, failure), then
   go to the main screen.

### 7.2 Update and announcement UI hooks

See 3.11 and 3.12.

---

## 8. Class-by-class map

| Java class | Rust home | Key items |
|---|---|---|
| `AnnouncementManager` | `mmcl_net::announcements` | `start_check_async`, getters, `dismiss_for_session`, `is_dismissed` |
| `ArchiveExtractor` | `mmcl_archive::extract` | `extract_tar_gz`, `extract_zip` |
| `AuthUtilities` | auth crate (`AuthResult::is_success`) | |
| `CacheManager` | `mmcl_net::cache::CacheManager` | `get_cached`, `download_and_cache`, `cleanup` |
| `ColdStartProfiler` | `mmcl_util::profiler` | `mark`, `is_enabled`, `write_and_maybe_exit` |
| `DiscordRpcUtility` | `mmcl_discord::Presence` | `set_menu_presence`, `set_game_presence`, `set_rich_presence`, `build_invite_link[_from_pack]`, `exit` |
| `DownloadExecutor` | `mmcl_net::pool::DownloadPool` | `submit`, `await_all` |
| `DownloadTracker` | `mmcl_net::tracker::DownloadTracker` | see 3.8 |
| `FileUtilities` | `mmcl_util::json::{read_json, write_json}` | |
| `FilePermissions` | `mmcl_secure::perms` | `owner_only`, `owner_only_dir` |
| `FxAsyncTask` | `mmcl_util::tasks::{run_async, run_with_finally}` plus `UiDispatcher` | |
| `HardwareTunedJvmArgs` | `mmcl_util::jvm_args::{generate, summary}` | |
| `HashUtilities` | `mmcl_util::hash` | `file_sha1/256/md5`, `verify_*`, `*_hex` |
| `ImageFormatUtilities` | `mmcl_archive::image::{is_natively_decodable, ensure_decodable}` | |
| `JarSigningStripper` | `mmcl_archive::jarsign` | `is_strip_required_for`, `strip_signing` |
| `JSONUtilities`, `JsonHelper` | `mmcl_util::json` | serde, lenient getters |
| `JumpListManager` | `mmcl_platform::recents::refresh` | |
| `JvmArgsValidator` | `mmcl_util::jvm_args::{require_clean, is_clean}` | |
| `LauncherActions` | app crate (`shell_actions`) plus `mmcl_platform::menus` (definitions) | |
| `LauncherUriHandler` | `mmcl_util::deeplink` (parse, classify) plus app `DeepLinkHost` (dispatch) | |
| `MachineSecretCipher` | `mmcl_secure::cipher` | |
| `MacOsDockManager` | `mmcl_platform::mac::dock` | `set_progress`, `stop`, `set_error`, `request_attention`, `set_badge`, `shutdown` (the dock menu goes through `cx.set_dock_menu`) |
| `MacOsTitleBarManager` | GPUI `TitlebarOptions` plus app title-bar component | `traffic_light_position`, `start_window_move` |
| `MacOsToolbarManager` | app title-bar component (recommended) or `mmcl_platform::mac::toolbar` | Q3 |
| `MacOsVibrancyManager` | `mmcl_platform::mac::appearance` plus GPUI `Blurred` | `apply_window_appearance` |
| `NetworkUtilities` | `mmcl_net::{http, offline, listeners, proxy}` | `download_file`, `download_string[_bounded[_conditional]]`, `download_file_bounded`, `check_network_availability`, scopes |
| `NotificationManager` | `mmcl_platform::notify` | `info`, `success`, `warn`, `error`, `shutdown` |
| `OsThemeUtilities` | `mmcl_platform::theme::is_os_dark` | |
| `PowerStateManager` | `mmcl_platform::power` (pure core in `mmcl_util`) | |
| `ProcessUtilities` | `mmcl_util::process` | `launch`, `split_command_line`, `strip_sensitive_env`, `execute_string_command` |
| `RecentPacks` | modpack crate or app (`recent_packs(max)`) | |
| `SchemeRegistrar` | `mmcl_platform::register` | `register_if_needed`, `refresh_recent_packs`, `launcher_exe_path` |
| `SensitiveDataRedactor` | `mmcl_util::redact` | |
| `SingleInstanceLock` | `mmcl_platform::single_instance` | `try_acquire`, `forward`, `release`, `parse_reply`, `slow_equals` |
| `StartupDiagnostics` | `mmcl_util::startup_log` | |
| `StringOrArray` | `mmcl_util::json::StringOrArray` | |
| `SystemUtilities` | `mmcl_util::tasks` (`spawn`, `run_bounded`, `shutdown`), `mmcl_archive::extract_jar`, `mmcl_util::client_token` | |
| `TaskbarProgressManager` | `mmcl_platform::progress` | see 6.8 |
| `TimeUtilities` | `mmcl_util::time::friendly_greeting` | |
| `UnitFormatter` | `mmcl_util::units` | |
| `UpdateCheckManager`, `UpdateCheckUtilities` | `mmcl_net::update` plus app hook | |
| `UrlValidation` | `mmcl_util::url_validation` | |
| `VerifyAction` | app crate | |
| `VersionUtilities` | `mmcl_util::version::compare_versions` | |
| `WindowChromeManager` | `mmcl_platform::win::dwm` | |
| `WindowsCustomChromeManager` | GPUI built-in plus `mmcl_platform::win::dwm::extend_frame` | |
| `WindowsDllSearchHardening` | `mmcl_platform::win::harden_dll_search` | |
| `WindowsDpiAwareness` | manifest plus `mmcl_platform::win::enable_per_monitor_v2` | |
| `WindowsForeground` | `mmcl_platform::win::allow_any_foreground` | |
| `WindowsJumpList` | `mmcl_platform::win::jump_list` | |
| `WindowsShellRefresh` | `mmcl_platform::win::force_frame_refresh` | |
| `WindowsTitleBarControls` | app GPUI component `CaptionButtons` | |
| `objects.GameMode` | `mmcl_util::GameMode` | |
| `system.DesktopShortcutManager` | `mmcl_platform::shortcut` (quoting in `mmcl_util::quote`) | |

---

## 9. Public API sketch (what other subsystems call)

```rust
// ---- mmcl-util ----
pub mod version { pub fn compare_versions(a: &str, b: &str) -> std::cmp::Ordering; }
pub mod jvm_args {
    pub fn require_clean(s: Option<&str>) -> Result<String, JvmArgsError>;
    pub fn is_clean(s: Option<&str>) -> bool;
    pub fn generate(max_ram_gb: i32) -> String;
    pub fn summary(max_ram_gb: i32) -> String;
}
pub mod redact { pub fn redact(s: &str) -> Cow<'_, str>; pub fn redact_strict(s: &str) -> Cow<'_, str>; }
pub mod url_validation { pub fn sanitized_http_url(raw: &str) -> Option<String>; pub fn is_http_url(raw: &str) -> bool; }
pub mod deeplink {
    pub const SCHEME: &str = "mmcl";
    pub enum DeepLink { Add { url: String }, Play { name: String }, JoinUrl { url: String }, JoinVanilla { version: String }, Open }
    pub fn is_launcher_uri(s: &str) -> bool;
    pub fn parse(s: &str) -> Result<DeepLink, DeepLinkError>;      // action + params
    pub fn parse_query(q: Option<&str>) -> HashMap<String, String>;
    pub enum InstallUrlVerdict { AcceptTrusted, RequireConfirmation, Reject }
    pub fn classify_install_url(url: Option<&str>) -> InstallUrlVerdict;
    pub fn host_of(url: &str) -> String;
    pub fn pct_encode_unreserved(s: &str) -> String;               // .desktop and jump list
}
pub mod process {
    pub enum ChildIoMode { Pipe, Discard, Inherit }
    pub fn launch(argv: &[String], cwd: &Path, mode: ChildIoMode) -> io::Result<std::process::Child>;
    pub fn split_command_line(s: &str) -> Vec<String>;
    pub fn is_sensitive_env_name(name: &str) -> bool;
}
pub mod tasks {
    pub fn spawn(f: impl FnOnce() + Send + 'static);
    pub fn run_bounded(name: &str, f: impl FnOnce() + Send + 'static, timeout: Duration,
                       on_failure: Option<Box<dyn FnOnce(String) + Send>>) -> bool;
    pub fn shutdown(wait: Duration);
    pub fn is_background_worker() -> bool;
    pub trait UiDispatcher: Send + Sync { fn run(&self, f: Box<dyn FnOnce(&mut gpui::App) + Send>); }
}
pub mod hash { pub fn file_sha1(p: &Path) -> Option<String>; pub fn verify_sha1(p: &Path, want: &str) -> bool; /* sha256, md5, hex */ }
pub mod startup_log { pub fn record(msg: &str); pub fn record_launch(argc: usize); pub fn install_panic_hook(); }
pub mod profiler { pub fn mark(phase: &'static str); pub fn write_and_maybe_exit(); }
pub mod json { pub struct StringOrArray(Vec<String>); /* helpers */ }
pub mod units { pub fn bytes(n: i64) -> String; pub fn bytes_per_second(r: f64) -> String; }

// ---- mmcl-net ----
pub fn download_file(src: &Source, dest: &Path, opts: &DownloadOpts) -> Result<(), DownloadError>;
pub fn download_string(src: &Source, accept: Option<&str>) -> Result<String, DownloadError>;
pub fn download_string_bounded(url: &str, max: u64) -> Result<String, DownloadError>;
pub fn download_string_bounded_conditional(url: &str, max: u64, etag: Option<&str>, last_mod: Option<&str>)
    -> Result<BoundedFetchResult, DownloadError>;
pub fn download_file_bounded(url: &str, dest: &Path, max: u64) -> Result<(), DownloadError>;
pub fn is_offline() -> bool; pub fn set_offline(b: bool); pub fn check_network_availability() -> bool;
pub fn reload_proxy();
pub struct DownloadTracker; pub struct DownloadPool; pub struct CancelToken; pub struct DownloadScope;
pub fn add_retry_listener(..) -> ListenerGuard; pub fn add_progress_listener(..) -> ListenerGuard;
pub mod cache { pub fn get_cached(url: &str, ttl: Duration) -> Option<PathBuf>; pub fn download_and_cache(url: &str, ttl: Duration) -> Result<PathBuf>; pub fn cleanup(); }
pub mod update { pub fn latest_release() -> Result<Option<ReleaseInfo>>; }
pub mod announcements { /* 3.12 */ }

// ---- mmcl-archive ----
pub fn extract_tar_gz(a: &Path, to: &Path) -> io::Result<()>;
pub fn extract_zip(a: &Path, to: &Path) -> io::Result<()>;
pub fn extract_jar(jar: &Path, to: &Path) -> Result<(), ExtractError>;
pub mod jarsign { pub fn is_strip_required_for(v: Option<&str>) -> bool; pub fn strip_signing(jar: &Path) -> io::Result<bool>; }
pub mod image { pub fn ensure_decodable(p: &Path) -> bool; pub fn png_to_ico(src: &Path, dst: &Path) -> Result<()>; pub fn png_to_icns(src: &Path, dst: &Path) -> Result<()>; }

// ---- mmcl-secure ----
pub mod cipher { pub fn encrypt(s: &str) -> Result<String>; pub fn decrypt(s: &str) -> Result<Option<String>>; }
pub mod perms { pub fn owner_only(p: &Path); pub fn owner_only_dir(p: &Path); }
pub fn java_os_name() -> String;

// ---- mmcl-platform ----
pub mod single_instance { pub fn try_acquire() -> bool; pub fn forward(payload: &str) -> ForwardResult; pub fn release(); pub fn set_handler(h: Arc<dyn IpcHandler>); }
pub mod win { pub fn early_init(aumid: &str); /* dll hardening + DPI + AUMID */ pub mod dwm; pub mod jump_list; }
pub mod theme { pub fn is_os_dark() -> bool; }
pub mod notify { pub fn info(t: &str, b: &str); pub fn success(..); pub fn warn(..); pub fn error(..); pub fn shutdown(); }
pub mod progress { /* 6.8 */ }
pub mod register { pub fn register_if_needed(); pub fn refresh_recent_packs(); pub fn launcher_exe_path() -> Option<PathBuf>; }
pub mod recents { pub fn refresh(); }
pub mod shortcut { pub fn create_shortcut(pack: &dyn ShortcutPack) -> Result<()>; }
pub mod power { pub fn is_on_battery() -> bool; pub fn battery_percentage() -> i32; pub fn maybe_throttle(chunk: usize); }
pub mod chrome { pub fn apply_title_bar_dark_mode(w: &gpui::Window, dark: bool); pub fn supports_mica(w: &gpui::Window) -> bool;
                 pub fn apply_backdrop(w: &gpui::Window, kind: Backdrop) -> bool; pub fn apply_window_appearance(w: &gpui::Window, dark: bool); }

// ---- mmcl-discord ----
pub struct Presence; impl Presence {
    pub fn global() -> &'static Presence;
    pub fn set_menu_presence(&self, screen: &str);
    pub fn set_game_presence(&self, pack: &str, custom_rpc: bool, manifest_url: Option<&str>);
    pub fn exit(&self);
}
pub fn build_invite_link(url: &str) -> Option<String>;
```

Traits the app crate implements:

```rust
pub trait DeepLinkHost: Send + Sync {
    fn uri_handler_enabled(&self) -> bool;
    fn find_installed_by_name(&self, name: &str) -> Option<PackRef>;
    fn find_installed_by_url(&self, url: &str) -> Option<PackRef>;
    fn install_by_url(&self, url: &str) -> anyhow::Result<()>;
    fn vanilla_installed(&self, id: &str) -> bool; fn install_vanilla(&self, id: &str) -> anyhow::Result<()>;
    fn is_game_running(&self) -> bool;
    fn may_leave_for_launch(&self) -> bool;          // blocks on dialogs; worker only
    fn ask(&self, q: Question) -> usize;             // blocking two-button question; 1 = first button
    fn play(&self, pack: PackRef);
    fn focus_window(&self);
    fn refresh_main_if_current(&self);
    fn has_main_window(&self) -> bool;
}
```

---

## 10. External crates

| Crate | Use |
|---|---|
| `windows` (0.61/0.62, matching GPUI's) | DWM, COM (`ITaskbarList3`, `ICustomDestinationList`, `IShellLinkW`, `IPersistFile`, `IPropertyStore`), `SetDllDirectoryW`, DPI, AUMID, `AllowSetForegroundWindow`, `SetWindowPos`, `RedrawWindow`, `Shell_NotifyIconW`, `SHChangeNotify`, security APIs, `RtlGetVersion` |
| `windows-registry` or `winreg` | HKCU class registration, `EnableTransparency` read |
| `wmi` | hardware UUID on Windows (oshi parity) |
| `objc2`, `objc2-foundation`, `objc2-app-kit` | NSWindow appearance, NSDockTile badge and progress, `requestUserAttention`, optional NSToolbar |
| `core-foundation`, `io-kit-sys` | `IOPlatformUUID` |
| `zbus` | Unity LauncherEntry progress, notifications (through notify-rust) |
| `notify-rust` | cross-platform toasts |
| `tray-icon` (Windows, macOS), `ksni` (Linux) | tray |
| `raw-window-handle` 0.6 | HWND and NSView from GPUI |
| `socket2` | single-instance listener (backlog, `SO_EXCLUSIVEADDRUSE`) |
| `interprocess` | Discord IPC (overlapped named pipe, Unix socket) |
| `reqwest` (blocking, rustls, socks, no compression) | HTTP |
| `url`, `fluent-uri`, `form_urlencoded`, `percent-encoding` | URL parsing (strict and lenient), query codec |
| `zip`, `tar`, `flate2` | archives |
| `image`, `ico`, `icns` | transcode, icons |
| `aes-gcm`, `pbkdf2`, `hmac`, `sha1`, `sha2`, `md-5`, `base64`, `getrandom` | crypto and hashing |
| `starship-battery` | power source |
| `sysinfo` | total RAM |
| `dark-light` | theme probe outside GPUI |
| `whoami`, `dirs` | user name, desktop and home dirs |
| `uuid`, `chrono`, `regex`, `indexmap`, `dashmap`, `arc-swap`, `crossbeam-channel`, `parking_lot`, `once_cell`/`std::sync::OnceLock` | general |
| `open` | open folder or URL |
| `serde`, `serde_json` | JSON |
| `embed-resource` or `winresource` | Windows manifest and icon in the exe |

No good Rust equivalent, so write it here:

- **Java `Manifest` read and write** (72-byte wrapping, CRLF, attribute order) for the jar
  signing stripper.
- **Discord IPC with join events.** Write our own (about 300 lines).
- **oshi-identical hardware UUID strings.** Per-OS code, checked against the Java harness.
- **Java `os.name` mapping.** A table.
- **Mica on GPUI.** Our own DWM calls, behaviour to confirm in a spike.
- **macOS dock progress arc.** A custom `NSDockTile` content view.
- **Gson-lenient JSON.** `json5` covers most of it if wanted (Q6).

---

## 11. Tests to port

All under `src/test/java/.../utilities/` and `.../system/`. Port them as Rust unit tests in the
crate that owns the code. Seams stay as plain functions or constructor-injected parts, with no
mocking framework.

| Java test | Count | Rust target |
|---|---|---|
| `VersionUtilitiesTest` | 6 | `mmcl_util::version` |
| `JvmArgsValidatorTest` | 4 | `jvm_args` |
| `HardwareTunedJvmArgsTest` | 2 | `jvm_args` |
| `UrlValidationTest` | 10 | `url_validation` |
| `SensitiveDataRedactorTest` | 18 | `redact` |
| `StringOrArrayTest` | 7 | `json` |
| `JSONUtilitiesTest` | 8 | `json` (round-trips; the shared-instance tests can be dropped) |
| `HashUtilitiesHexTest`, `HashUtilitiesFileTest` | 4 + 11 | `hash` |
| `ProcessUtilitiesSplitCommandLineTest` | 9 | `process` |
| `ProcessUtilitiesSensitiveEnvTest` | 7 | `process` |
| `SystemUtilitiesShutdownHelpersTest` | 4 | `tasks` |
| `StartupDiagnosticsTest` | 4 | `startup_log` |
| `NetworkUtilitiesTest` | 25 | `mmcl_net` (`url_file_name`, `format_bytes`, `format_download_progress`, path lock, content-type gate, bounded file refuses http and deletes dest) |
| `NetworkUtilitiesDownloadScopeTest` | 3 | `mmcl_net::listeners` |
| `DownloadTempFileTest` | 1 | `mmcl_net` |
| `DownloadExecutorTest` | 3 | `mmcl_net::pool` |
| `ArchiveExtractorTest` | 10 | `mmcl_archive` |
| `SystemUtilitiesExtractTest`, `SystemUtilitiesExtractSecurityTest` | 4 + 6 | `mmcl_archive::extract_jar` |
| `JarSigningStripperTest` | 11 | `mmcl_archive::jarsign` |
| `MachineSecretCipherSecurityTest`, `MachineSecretCipherFingerprintTest` | 11 + 3 | `mmcl_secure::cipher` |
| `MachineSecretCipherSubprocessHarness` | harness | turn it into a **cross-language test**: Java encrypts, Rust decrypts, and the reverse, on the same machine (proves envelope, KDF and fingerprint parity) |
| `FilePermissionsTest` | 4 | `mmcl_secure::perms` (Unix; a Windows DACL read-back variant) |
| `PowerStateThrottleTest` | 6 | throttle core (synthetic clock) |
| `TaskbarProgressCombineTest` | 5 | `progress::combine` |
| `SingleInstanceLockReplyTest`, `SingleInstanceLockSlowEqualsTest` | 7 + 7 | `single_instance` |
| `LauncherUriHandlerClassifyInstallUrlTest`, `LauncherUriHandlerQueryAndSchemeTest` | 17 + 15 | `deeplink` |
| `AuthUtilitiesTest` | 7 | auth crate (enum based) |
| `DesktopShortcutManagerQuotingTest`, `...SanitizeTest`, `...CommandBuildingTest` | 19 + 13 + 13 | `mmcl_util::quote` (the command-building tests shrink to the native-exe case) |

New tests worth adding:

- an IPC loopback round trip (ephemeral port);
- a `.desktop` content golden file;
- registry key layout (Windows CI, under a temporary `HKCU\Software\Classes\mmcl-test` root);
- Discord frame encode and decode;
- the bounded-fetch redirect walker against a local `tiny_http` server (https-only rule:
  inject the scheme check);
- ICO header bytes;
- the stall watchdog with an injectable clock and a stub reader.

---

## 12. Order of work (suggested)

1. `mmcl-util` (pure, test-heavy, no OS).
2. `mmcl-secure` cipher with the cross-language harness. **Do this early**: format mistakes are
   the costliest.
3. `mmcl-net` and `mmcl-archive`.
4. `mmcl-platform` Windows early init, single instance and deep links (needed for the first
   runnable build).
5. GPUI title-bar spike on all three OSes (Q2): custom caption, snap layouts, Mica, NCACTIVATE
   flash, macOS traffic lights and vibrancy, Linux CSD.
6. Taskbar and dock progress, notifications and tray, jump list and `.desktop` actions.
7. Discord, shortcuts, power throttle.

---

## 13. Open questions

- **Q1** Side-by-side identity. While the Rust build uses its own data folder, should it also
  use its own IPC port, AUMID, `mmcl://` registration, Windows ProgID and `.desktop` id
  (proposed: yes, through an `AppIdentity` switch), or take over `mmcl://` from an installed
  Java launcher?
- **Q2** GPUI window-chrome spike results needed before committing: Mica through GPUI's
  DirectComposition swap chain (`Opaque` vs `Transparent` background), the `WM_NCACTIVATE`
  title-bar flash, maximize-button hover and snap layouts, and Linux client-side decorations
  (always draw our own title band on Wayland?).
- **Q3** macOS native `NSToolbar` items. Port them (objc2 runtime delegate class on GPUI's
  NSWindow), or draw the same Browse/Settings/Help/Update/Account items in the GPUI title bar
  with centred traffic lights (recommended)?
- **Q4** Hardware-UUID parity with oshi (Linux without root, VMs, oshi's `"unknown"` sentinel).
  This decides whether a Rust build can decrypt Java-written auth caches after the folder
  switch, or users must sign in again once.
- **Q5** Tray behaviour. Keep Java's "tray icon appears on first notification" (including a
  macOS menu-bar extra), or create it at startup on Windows and Linux only? And WinRT toasts
  (need the installer's AUMID shortcut) versus `Shell_NotifyIcon` balloons?
- **Q6** JSON leniency. Gson accepts comments and other non-standard JSON in hand-edited
  manifests and config. Accept it in Rust (a `json5` fallback) or require strict JSON?
- **Q7** Connectivity probe behind an HTTP proxy. Keep Java's direct-socket probe (reads
  offline until the first real request), or probe through the configured proxy?
- **Q8** Update check for the Rust build. Same GitHub repo and `releases/latest` as the Java
  launcher (the tags would interleave), or a separate channel until the switch?
- **Q9** Deep-link concurrency. Java's deep-link `play`/`join` refuse while *any* game runs, but
  tray, dock and menu launches allow several games. Keep that inconsistency, or let deep links
  allow concurrent launches too (the session registry still refuses same-pack and same-account)?
