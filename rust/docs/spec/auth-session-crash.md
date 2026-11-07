# Porting spec: authentication, game sessions, crash analysis

Slice: `game/auth/`, `game/session/`, `game/crash/` (Java), plus `docs/AUTHENTICATION_SYSTEM.md`, plus the
parts of `minecraft_authenticator` 3.0.6 (net.hycrafthd), `MachineSecretCipher`, `FilePermissions`,
`SensitiveDataRedactor`, `LogTrimPolicy` and `MicrosoftSignIn` this slice depends on. Those helpers sit in other
Java packages, but the Rust port needs them to reproduce this slice exactly. They are specified here and owned by
the crates named in section 3.

Source facts were read from:

- `src/main/java/com/micatechnologies/minecraft/launcher/game/auth/*.java` (whole files)
- `src/main/java/com/micatechnologies/minecraft/launcher/game/session/*.java` (whole files)
- `src/main/java/com/micatechnologies/minecraft/launcher/game/crash/*.java` (whole files)
- `utilities/MachineSecretCipher.java`, `utilities/FilePermissions.java`, `utilities/SensitiveDataRedactor.java`,
  `gui/LogTrimPolicy.java` (the two methods used), `gui/MicrosoftSignIn.java`, call sites in `LauncherCore.java`,
  `LauncherSession.java`, `config/AppConfig.java`, `config/ModPackConfig.java`, `gui/GameSessionPane.java`,
  `game/modpack/GameModPack.getLatestCrashReport`
- `~/.m2/.../minecraft_authenticator-3.0.6-sources.jar` (pom pins 3.0.6) — every class in it
- `~/.m2/.../oshi-core-6.6.5-sources.jar` (pom pins 6.6.5) — the hardware-UUID code paths
- the matching tests under `src/test/java/.../game/{auth,session,crash}/`

Conventions: "the Java" means the current launcher. **Compat** marks a detail that must match byte for byte so
the Rust launcher can later read the Java launcher's data folder. **Bug-guard** marks behaviour that exists
because a real bug shipped; do not "simplify" it away.

---

## 1. Purpose and behaviour

### 1.1 Overview

Three cooperating pieces:

1. **Auth**: Microsoft account sign-in (OAuth authorization code → Xbox Live → XSTS → Minecraft services),
   several accounts signed in at once, one *default*, per-account encrypted store on disk, per-account token
   refresh with single-flight semantics, a launcher-wide rate limit / backoff, and a pure resolver that picks
   which account a launch uses.
2. **Sessions**: one `GameSession` per launch with a phase state machine, a registry that admits launches
   atomically (one active game per pack, one per account), output capture for the whole life of the game
   (`GameLog`), and a follower that keeps "what are you playing" surfaces (RGB keyboard, Discord presence) on the
   newest running game.
3. **Crash analysis**: an ordered list of regex detectors that turn a crash report (or the game log) into a
   localized diagnosis with actionable suggestions.

### 1.2 Data folder layout (Compat)

All paths are relative to the launcher **config folder** (`LocalPathManager.getLauncherConfigFolderPath()`,
supplied by the paths subsystem; in the Rust port it is under the Rust launcher's own data root for now, with the
same sub-layout).

```
<config>/
  configuration.json          # owned by config subsystem; keys used here:
                              #   "defaultAccountUuid": "<uuid>" | ""
                              #   "accountOverrideByPack": { "<packSettingsKey>": "<uuid>", ... }
  machine-key.bin             # 32 random bytes (raw), owner-only. Per-install secret for the cipher.
  profiles/                   # owner-only dir
    <uuid>/                   # owner-only dir; <uuid> = Minecraft profile id, normally 32 hex, no dashes
      player.mica             # encrypted gzip'd AuthenticationFile, RAW envelope bytes (not Base64)
      cached_user.json        # Base64( encrypted( UTF-8 JSON of User ) )
      renewal.timestamp       # Base64( encrypted( ASCII decimal epoch millis ) )
      profile.json            # plaintext JSON {"uuid","displayName","lastUsedMs"}
  # Legacy single-account layout (migrated on first load, then deleted):
  player.mica, cached_user.json, renewal.timestamp
```

Game logs go to the **launcher log folder** (`LocalPathManager.getLauncherLogFolderPath()`):
`game-<safePackName>-<yyyy-MM-dd--HH-mm-ss>.log` (local time, `SimpleDateFormat("yyyy-MM-dd--HH-mm-ss")`).

Crash reports are read from `<packRoot>/crash-reports/*.txt` (owned by the modpack subsystem, see 1.8.1).

### 1.3 Encryption: `MachineSecretCipher` (Compat, exact)

Used by `AccountStore` through the `AccountCipher` seam (`encrypt(bytes) -> bytes`, `decrypt(bytes) -> Option<bytes>`).
Also used elsewhere in the launcher (CurseForge API key), so it belongs in a shared crate.

**Envelope** = `salt[16] || iv[12] || ciphertext || tag[16]` (GCM tag appended by the cipher, as JCE does).

**encrypt(plaintext)**:
1. `salt = 16 random bytes` (CSPRNG), `iv = 12 random bytes`.
2. `key = PBKDF2-HMAC-SHA256(password = UTF-8(fingerprint), salt, iterations = 65536, dkLen = 32)`.
   JCE `PBEKeySpec(char[])` + `PBKDF2WithHmacSHA256` encodes the password chars as UTF-8, so use the UTF-8 bytes.
3. `AES-256-GCM(key, iv, no AAD, tag 128 bits)`.
4. Return `salt || iv || ct||tag`.

Cost: one 65,536-iteration PBKDF2 per encrypt **and** per decrypt (fresh salt each time; there is no key
cache). A renewal writes three encrypted files, so about 3 PBKDF2 runs. Keep this off the UI thread. The Rust
port may cache derived keys per salt in memory, but the format must not change.

**decrypt(envelope)**:
- `envelope.len() < 28` → return `None` ("too short"; *not* an error). Note: 28, not 44; a 28..43-byte input
  reaches GCM and fails the tag.
- Split salt/iv/ct, derive key as above, AES-GCM decrypt. A tag failure is an **error** (wrong machine or
  tampered). Callers treat error and `None` alike: as "unreadable".
- **No legacy fallback.** `docs/AUTHENTICATION_SYSTEM.md` says decrypt retries a legacy UUID-only fingerprint;
  the current code explicitly does **not** (comment: "We intentionally do NOT fall back to the pre-2026.6
  UUID-only fingerprint"). The doc is stale. The code wins: do not implement the fallback.

**Fingerprint** (`assembleFingerprint`), exact format:

```
<user> "|" <osName> "|" <hwUuid> "|" "install:" <installSecretHex>
```

null components become `""`. The separator is a literal `|`.

- `user` = env `USER` if non-empty, else env `USERNAME` if non-empty, else Java `System.getProperty("user.name")`
  (Rust: `whoami::username()` / `GetUserNameW` on Windows, `getpwuid_r(geteuid())` name on Unix).
- `osName` = Java `System.getProperty("os.name")`. **Compat hazard**: Rust has no equivalent string, so
  reproduce Java's values:
  - Windows: `"Windows 10"` / `"Windows 11"` for workstations (JDK 26 reports `"Windows 11"` for build ≥ 22000),
    servers `"Windows Server 2016"`, `"Windows Server 2019"` (build ≥ 17763), `"Windows Server 2022"`
    (≥ 20348), `"Windows Server 2025"` (≥ 26100). Get the build and product type from `RtlGetVersion` /
    registry `CurrentBuildNumber` + `InstallationType`.
  - macOS: `"Mac OS X"` (always; Java never changed it).
  - Linux: `"Linux"`. FreeBSD: `"FreeBSD"`.
  Only matters for reading Java-written files. Note it in a `java_os_name()` helper with tests per platform.
  An OS upgrade (Windows 10 → 11) changes the key, which means re-sign-in. The Java launcher behaves the same.
- `hwUuid` = oshi 6.6.5 `ComputerSystem.getHardwareUUID()`, or `""` if the oshi call throws, or `""` if the
  result is null/blank. **Important**: oshi itself returns the literal string `"unknown"` when it can't find a
  UUID, and that is *not* blank, so `"unknown"` goes into the fingerprint. Exact oshi behaviour:
  - Windows: WMI `SELECT IdentifyingNumber, UUID FROM Win32_ComputerSystemProduct`, first row's `UUID` as WMI
    returns it (normally upper-case dashed, e.g. `4C4C4544-0042-...`). If blank → `"unknown"`.
  - macOS: IOKit `IOPlatformExpertDevice` property `IOPlatformUUID` (string, upper-case dashed). Blank →
    `"unknown"`.
  - Linux, first non-null of:
    1. first line of `/sys/devices/virtual/dmi/id/product_uuid` (no trim; `""` if unreadable, which is the
       usual case for non-root because the file is mode 0400), accepted when non-empty and `!= "None"`;
    2. if the process is elevated (euid 0): `dmidecode -t system`, first line containing `UUID:`, text after it,
       trimmed;
    3. `lshal`, line containing `system.hardware.uuid =`, single-quoted value;
    4. if elevated: `lshw -C system`, line containing `uuid:`, text after it trimmed;
    5. else `"unknown"`.
    So for a normal non-root desktop Linux user the value is almost always `"unknown"`.
- `installSecretHex` = lower-case hex (`%02x` per byte) of **all** bytes of `<config>/machine-key.bin`.
  - Read lazily once per process and cached (double-checked lock).
  - If the file exists and has ≥ 32 bytes: use it (all bytes, even if > 32).
  - Else (missing or < 32 bytes): generate 32 CSPRNG bytes, `create_dir_all(parent)`, write the file
    (plain write, not atomic), apply owner-only permissions, use it.
  - If any IO fails: log a warning and use a fresh **in-memory** 32-byte secret for the rest of the process.
    Every value encrypted in that process then can't be decrypted after a restart. This is accepted.
  - Note: the secret path comes from the config folder of the current game mode. In the Java this is resolved
    after mode detection; see memory "Early main() path trap". The Rust port must not touch the cipher before
    the data root is known.

Bug-guard (doc + comments): a network **MAC address** used to be in the fingerprint. macOS randomizes MACs, so
the key rotated between launches and ~90% of macOS launches failed with "token renewal failed". Never add
volatile inputs to the fingerprint.

### 1.4 File permissions (`FilePermissions.applyOwnerOnly[Directory]`)

Best effort; failures are logged at warning level and never fail the write.

- POSIX: files `0600`, directories `0700`.
- Windows (no POSIX view): replace the DACL with a single ALLOW ACE for the file owner granting all
  permissions (Java `AclEntryPermission` all = FILE_ALL_ACCESS equivalent), no inheritance flags. Rust: use
  `windows` crate `SetNamedSecurityInfoW(path, SE_FILE_OBJECT, DACL_SECURITY_INFORMATION |
  PROTECTED_DACL_SECURITY_INFORMATION, ...)` with an ACL holding one ACE for the owner SID (get it with
  `GetNamedSecurityInfoW(OWNER_SECURITY_INFORMATION)`). Adding `PROTECTED_` is a small hardening over the Java
  (it stops inherited ACEs coming back), and it is safe.
- Directories are tightened **before** credentials are written into them, because new files on Windows inherit
  the directory ACL (bug-guard comment in `AccountStore.ensureFolder`).

### 1.5 `AccountStore` (per-account files)

Not thread-safe; `AccountManager` serializes all access (holds its lock while calling the store).

Constants: `PROFILES_DIR="profiles"`, `AUTH_FILE="player.mica"`, `CACHED_USER_FILE="cached_user.json"`,
`RENEWAL_FILE="renewal.timestamp"`, `META_FILE="profile.json"`.

**UUID safety**: `is_safe_uuid(s) = s matches ^[0-9a-fA-F-]{1,64}$` (whole string). The uuid becomes a path
segment, so it is *refused*, not sanitized. That keeps `..` or separators from escaping the store. Read paths
with an unsafe uuid resolve to `root/invalid-uuid/never` (a path that never exists), so they read as absent and
don't error. Write paths with an unsafe uuid fail with IO error "Refusing to store an account under an unsafe
uuid". `remove` with an unsafe uuid returns `false`.

**list() -> Vec<Entry{uuid, display_name, last_used_ms}>**
- If `root` isn't a directory: empty.
- For each **directory** child whose name passes `is_safe_uuid` **and** that contains a regular file
  `player.mica`: read `profile.json` if it is a regular file; take `displayName` (string) and `lastUsedMs`
  (i64) when present. Any parse error only logs a warning and keeps the entry with `""`/`0` (bug-guard: unreadable
  metadata costs only the name and sort position).
- Folders without `player.mica`, and unsafe names, are skipped.
- Listing IO error: warn, return what was collected.
- Sort by `last_used_ms` **descending** (stable sort; ties keep directory-listing order, which is unspecified).

**read_auth_file(uuid) / read_auth_file_at(path, cipher) -> Option<Vec<u8>>** (gzip'd AuthenticationFile bytes)
- Missing / not a regular file / empty → `None`.
- If the first two bytes are the gzip magic `1F 8B` → return the bytes as-is (legacy pre-encryption file; it is
  re-written encrypted on the next write).
- Else `cipher.decrypt(bytes)`; error or `None` → `None` (warn with the error type name only).

**write_auth_file(uuid, gzipped)** → `write_secret(uuid, AUTH_FILE, gzipped, base64 = false)`.

**read_cached_user(uuid) / read_cached_user_at(path, cipher) -> Option<User>**
- Missing → `None`. Read the file as a string and **trim** it. Base64-decode (standard alphabet, padded,
  strict: Java `Base64.getDecoder()`), decrypt, UTF-8 decode, parse JSON object.
- Build `User{uuid, name, access_token, type, xuid, client_id}` from members `uuid`, `name`, `accessToken`,
  `type`, `xuid`, `clientId`. Absent or JSON-null → `None` field.
- `uuid` or `name` null/blank → `None` (test `aCachedUserWithoutANameIsTreatedAsMissing`).
- Any error → `None` (warn with type only).

**write_cached_user(user)**: JSON object with exactly these members in this order: `uuid`, `name`,
`accessToken`, `type`, `xuid`, `clientId`. A null field is stored as `JsonNull`, and the default Gson
(`JSONUtilities.getGson()`: no `serializeNulls`, no pretty printing) **omits** it, so a `None` field is left out
(serde: `skip_serializing_if = "Option::is_none"`). Compact output. Then `write_secret(user.uuid, CACHED_USER_FILE,
utf8(json), base64 = true)`. The folder is the user's own uuid.

**read_renewal_ms(uuid) / read_renewal_ms_at(path, cipher) -> i64** (0 = unknown)
- Missing / IO error → 0. Else `parse_renewal_timestamp(trimmed contents, cipher)`.

**parse_renewal_timestamp(raw, cipher) -> i64**:
1. `raw` null/blank → 0.
2. Try: Base64-decode `raw` (strict), decrypt. If decrypt returns `Some(plain)`: return `i64::parse(plain.trim())`;
   if that parse fails the *exception* is swallowed and we fall to step 3. If decrypt returns `None` (too short),
   fall to step 3. Any exception (bad Base64, GCM failure) → fall to step 3.
3. Return `i64::parse(raw)` or 0 on failure. This accepts the legacy plain-decimal form **including negative
   numbers** (test `legacyNegativeNumberParsesDirectly...`). A negative value is later treated as "unknown/due" by
   `is_renewal_due`.
   Edge (tested): a Base64-shaped decimal like `"1234"` decodes to 3 bytes → too short → `None` → parsed as the
   decimal 1234 (`base64ShapedNumberThatDecodesTooShortFallsBackToParsingTheOriginalString`).

**write_renewal_ms(uuid, ms)** → `write_secret(uuid, RENEWAL_FILE, ascii(ms.to_string()), base64 = true)`.

**write_meta(uuid, display_name, last_used_ms)**: plaintext compact JSON `{"uuid":..,"displayName":..,"lastUsedMs":..}`
(`displayName` null → `""`), written atomically into `ensure_folder(uuid)`.

**remove(uuid) -> bool**: unsafe → false. If the folder doesn't exist → true. Else walk it, delete children
before parents (reverse-sorted path order), **deleting symlinks as links and never following them** (Rust:
`symlink_metadata`; never `remove_dir_all` across a link). Ignore individual failures. Return
`!folder.exists()`.

**ensure_folder(uuid)**: refuse unsafe; `create_dir_all(root/uuid)`; then `applyOwnerOnlyDirectory(root)` and
`applyOwnerOnlyDirectory(folder)`.

**write_secret(uuid, name, plaintext, base64)**: `ensure_folder`; `cipher.encrypt` (error → IO error "Could not
encrypt <name>"); optionally standard-Base64 encode (padded, no line breaks); `write_atomically`.

**write_atomically(target, bytes)**:
1. `tmp = target + ".tmp"` (sibling, e.g. `player.mica.tmp`).
2. Write `tmp`, apply owner-only to `tmp` **before** the move (bug-guard: the credential is never briefly
   world-readable).
3. Rename `tmp → target` with replace (atomic). If atomic move is unsupported, fall back to a plain
   replace-move. In Rust, `std::fs::rename` replaces atomically on POSIX and uses `MoveFileExW(REPLACE_EXISTING)`
   on Windows. Treat a cross-device error as "unsupported" and copy + remove.
4. Apply owner-only to `target` again.
Test `writesLeaveNoTempFilesBehind`: no `.tmp` file is left after a successful write.

### 1.6 The `User` and `AuthenticationFile` formats (Compat)

**User** (`net.hycrafthd.minecraft_authenticator.login.User` record):
`uuid` (Minecraft profile id, 32 hex no dashes, from `/minecraft/profile` `id`), `name`, `accessToken`
(Minecraft services JWT), `type` (always `"msa"`), `xuid` (from the access-token JWT payload), `clientId`
(Base64url-with-padding of the launcher client UUID string, see 1.7). The game-launch subsystem builds
`--uuid`, `--username`, `--accessToken`, `--userType`, `--xuid`, `--clientId` from these.

**AuthenticationFile** (`player.mica` after decryption is **gzip** of UTF-8 JSON). Gson compact serialization,
member order:

```json
{"warning":"DO NOT SHARE THIS FILE. IT CONTAINS LOGIN INFORMATION FOR YOUR MINECRAFT ACCOUNT!",
 "file":{"clientId":{"most":-1234567890123456789,"least":987654321},
         "refreshToken":"M.C5xx_BAY.0.U.-...",
         "extraProperties":{},
         "type":"microsoft"}}
```

- `clientId.most` / `.least` = the signed 64-bit most/least significant bits of a random v4 UUID created at the
  first interactive sign-in (`UUID.randomUUID()`). Rust: `Uuid::from_u64_pair(most as u64, least as u64)`, and
  `let (m, l) = uuid.as_u64_pair(); (m as i64, l as i64)` to write. JSON numbers must be written as integers
  without exponent.
- On read: `file.type` must be `"microsoft"` (anything else → error). `extraProperties` is optional; every value
  read with `getAsString`. Parse failures → IO error "Cannot parse authentication file".
- Gzip: standard single-member gzip (Java `GZIPOutputStream` default header: no name, mtime 0, OS byte 0xFF on
  some JDKs). Readers must accept any valid gzip; byte-identical output is not needed.
- `clientId` is kept across renewals: a renewal writes `MicrosoftAuthenticationFile(sameClientId, newRefreshToken)`.

### 1.7 Microsoft / Xbox / Minecraft protocol (what `minecraft_authenticator` 3.0.6 does)

The Java calls the library like this:
- Sign-in: `Authenticator.ofMicrosoft(authCode).serviceConnectTimeout(5000).serviceReadTimeout(10000)
  .shouldAuthenticate().shouldRetrieveXBoxProfile().build().run()`
- Renewal: `Authenticator.of(AuthenticationFile.readCompressed(gzipped))` with the same options.
No custom Azure application is set, so the library defaults apply.

**Constants**

| Name | Value |
|---|---|
| Client ID | `00000000402b5328` (the official Minecraft launcher's MSA client; no secret) |
| OAuth base | `https://login.live.com` |
| Authorize endpoint | `https://login.live.com/oauth20_authorize.srf` |
| Token endpoint | `https://login.live.com/oauth20_token.srf` |
| Redirect URI | `https://login.live.com/oauth20_desktop.srf` |
| Scope | `XboxLive.signin offline_access` |
| XBL authenticate | `https://user.auth.xboxlive.com/user/authenticate` |
| XSTS authorize | `https://xsts.auth.xboxlive.com/xsts/authorize` |
| MC launcher login | `https://api.minecraftservices.com/launcher/login` |
| MC entitlements | `https://api.minecraftservices.com/entitlements/license?requestId=<launcherClientId>` |
| MC profile | `https://api.minecraftservices.com/minecraft/profile` |
| Xbox profile settings | `https://profile.xboxlive.com/users/me/profile/settings?settings=...` |
| Library User-Agent | `Minecraft-Authenticator` (for API calls; generic, no personal data) |
| Connect / read timeout | 5000 ms / 10000 ms per HTTP request |

Common request headers (every call): `Accept-Charset: UTF-8`, `Accept-Encoding: gzip, deflate`,
`Accept: application/json`, `User-Agent: Minecraft-Authenticator`, follow redirects, no caches. POSTs also send
`Charset: UTF-8`, `Content-Type: <type>` and a fixed `Content-Length`. Response bodies are read from the error
stream when the status is ≥ 400 (so JSON error bodies are parsed). `deflate` here means *raw* deflate
(`Inflater(nowrap = true)`). reqwest's `deflate` feature expects zlib-wrapped data, so send only
`Accept-Encoding: gzip`, or handle both.

**Interactive sign-in URL** (shown in the embedded browser, `MicrosoftSignIn.loginUrl()`):

```
https://login.live.com/oauth20_authorize.srf?client_id=00000000402b5328&response_type=code&scope=XboxLive.signin%20offline_access&redirect_uri=https%3A%2F%2Flogin.live.com%2Foauth20_desktop.srf&prompt=select_account
```

(Query values URL-encoded with `+` → `%20`, parameter order as shown.) `prompt=select_account` is appended by the
launcher. Bug-guard: without it, a WebView that still holds a Microsoft session silently signs the cached
account back in, so a second, different account can never be added.

The browser uses UA `Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:147.0) Gecko/20100101 Firefox/147.0`, because
Microsoft's pages degrade for unknown agents.

**Redirect capture** (`MicrosoftSignIn.attach` / `parseCallback`): while waiting, the first page load whose
location **starts with** `https://login.live.com/oauth20_desktop.srf` stops waiting, navigates the view to
`about:blank`, and parses the location:
- Take the substring after the first `?`, cut at the first `#`, split on `&`; for each pair split on the first
  `=` (no `=` → value `""`), URL-decode name and value (form decoding: `+` → space).
- `code`: first occurrence wins. `error`: sets `saw_error`. `error_description`: becomes the error text. Others
  (`state`, `session_state`, `lc`) are ignored.
- Result: if `saw_error` or the code is missing or empty → `Callback{code: None, error: Some(error_description or "")}`;
  else `Callback{code: Some(code), error: None}`. An `error` wins over a `code`.
- `user_cancelled()` = `error.contains("user has denied access")` (that substring is inside Microsoft's
  `error_description` for `access_denied`). A cancel is shown differently from a failure.

**Step 1a: code → tokens** (sign-in). `POST https://login.live.com/oauth20_token.srf`,
`Content-Type: application/x-www-form-urlencoded`, `Accept: application/json`, body (form-encoded with `+` for
spaces, in this order): `client_id=00000000402b5328&code=<code>&grant_type=authorization_code&redirect_uri=https%3A%2F%2Flogin.live.com%2Foauth20_desktop.srf`.
No `scope` on the token request.

**Step 1b: refresh → tokens** (renewal). Same endpoint, body:
`client_id=...&refresh_token=<rt>&grant_type=refresh_token&redirect_uri=...`.

Response JSON: if it has member `error` → error `{error, error_description, correlation_id}`. Else
`{token_type, expires_in, scope, access_token, refresh_token, user_id, foci}`. Transport or parse failure →
exception.

At sign-in, step 1a runs **twice in effect**: `createAuthenticationFile` exchanges the code for a refresh token
and stores it in a new `MicrosoftAuthenticationFile(randomUUID, refresh_token)`; then `authenticate` runs the
full login chain starting with a **refresh-token grant** (step 1b) on that file. So a sign-in makes two token
calls. A port may collapse these into one (use the first response's `access_token` directly). The observable
result is the same, apart from one fewer refresh-token rotation.

**Step 2: XBL.** `POST https://user.auth.xboxlive.com/user/authenticate`, JSON:
```json
{"Properties":{"AuthMethod":"RPS","SiteName":"user.auth.xboxlive.com","RpsTicket":"d=<msa access_token>"},
 "RelyingParty":"http://auth.xboxlive.com","TokenType":"JWT"}
```
HTTP ≥ 400 → error carrying the status code. Response `{IssueInstant, NotAfter, Token, DisplayClaims:{xui:[{uhs}]}}`;
an empty `xui` → exception "Xui (user hashes) cannot be missing".

**Step 3: XSTS for Minecraft.** `POST https://xsts.auth.xboxlive.com/xsts/authorize`, JSON:
```json
{"Properties":{"SandboxId":"RETAIL","UserTokens":["<xbl Token>"]},
 "RelyingParty":"rp://api.minecraftservices.com/","TokenType":"JWT"}
```
The HTTP status is **not** checked; the body is parsed. A body with member `XErr` → error
`{Identity, XErr (i64), Message, Redirect}`. Well-known `XErr` values (not mapped by the Java, but useful for
better messages in Rust): `2148916233` no Xbox profile, `2148916235` country not allowed, `2148916236` /
`2148916237` adult verification (South Korea), `2148916238` child account needs a family group. Library quirk:
the "validation" `xblXui.containsAll(xstsXui)` compares by identity, so in practice it only throws when the
XSTS `xui` list is **empty** ("Xui (user hashes) do match match"). Port it as "XSTS xui must be non-empty".

**Step 4: Minecraft launcher login.** `POST https://api.minecraftservices.com/launcher/login`, JSON
`{"xtoken":"XBL3.0 x=<uhs>;<xsts Token>","platform":"PC_LAUNCHER"}`, where `uhs` = the **XSTS** response's
`DisplayClaims.xui[0].uhs`. HTTP ≥ 400 → error code. Response `{username, roles, access_token, token_type, expires_in}`.
(This is the newer `launcher/login` endpoint, not `authentication/login_with_xbox`.)

**Step 5: entitlements.** `GET https://api.minecraftservices.com/entitlements/license?requestId=<launcherClientId UUID, lower-case dashed>`,
`Authorization: Bearer <mc access_token>`. HTTP ≥ 400 → error code. Response `{items:[{name,source}], signature, keyId, requestId}`.
`items` empty → error message **"This account does not have bought minecraft"** (the launcher matches the
substring `not have bought`, so keep the wording in the Rust error, or better, use a typed error variant).

**Step 6: profile.** `GET https://api.minecraftservices.com/minecraft/profile`, Bearer. HTTP ≥ 400 (404 = owns
the game but never created a profile) → error code. Response `{id, name, skins[{id,state,url,variant,alias}], capes[{id,state,url,alias}]}`.

**Step 7: derive.** `xuid` = JSON member `xuid` of the Base64url-decoded **payload** (2nd dot-separated
segment) of the Minecraft `access_token` JWT (no signature check; the decoder accepts missing padding). Failure →
"Could not parse minecraft access token". `clientId` = Base64url (**with** padding, Java `getUrlEncoder()`) of
the UTF-8 launcher-client-UUID string (36 chars → 48 Base64 chars, so in practice no `=`).
`User{uuid: profile.id, name: profile.name, accessToken: mc access_token, type: "msa", xuid, clientId}`.

**Step 8: Xbox profile** (because `shouldRetrieveXBoxProfile()` is set). XSTS again with
`RelyingParty: "http://xboxlive.com"`, then `GET https://profile.xboxlive.com/users/me/profile/settings?settings=GameDisplayName,AppDisplayName,AppDisplayPicRaw,GameDisplayPicRaw,PublicGamerpic,Gamerscore,Gamertag,ModernGamertag,ModernGamertagSuffix,UniqueModernGamertag,AccountTier,XboxOneRep,Location,Bio,Watermarks,RealName,RealNameOverride,IsQuarantined`
(commas URL-encoded as `%2C`), headers `Authorization: XBL3.0 x=<uhs>;<token>`, `x-xbl-contract-version: 3`.
Response `{profileUsers:[{id, hostId, settings:[{id,value}], isSponsoredUser}]}`. **The launcher never uses the
result**, but a failure here fails the whole sign-in or renewal. See the open questions: recommend dropping step
8, or making its failure non-fatal.

**Error propagation and the refresh token**: once step 1 succeeded, every later failure still carries the new
refresh token, and the library swaps its result file to `(clientId, newRefreshToken)` *before* throwing. The
Java launcher **discards** the result file on any failure (it only persists after success), so a rotated
refresh token from a failed renewal is lost. MSA consumer refresh tokens usually stay valid after rotation, so
this has not been a problem. The Rust port should keep this behaviour unless it deliberately persists rotated
tokens on partial failure (open question).

**Error messages** produced by the chain (wrapped as `MicrosoftAuthenticationException`), which the launcher
classifies by substring:

| Message (prefix) | Launcher classification |
|---|---|
| `Cannot get oAuth token` / `... because: OAuthErrorResponse [error=invalid_grant, ...]` | interactive: `ERROR_OTHER`; renewal: transient (`credentialsRejected = false`) |
| `Cannot authenticate with xbl ... http code N` | other / transient |
| `Cannot authorize with xsts for minecraft services because: XSTSAuthorizeErrorResponse [...]` | other / transient |
| `Cannot login into minecraft with xbox ... http code N` | other / transient |
| `This account does not have bought minecraft` | interactive: `ERROR_NOT_OWNED`; renewal: **rejected** |
| `Cannot get minecraft profile data ... 404` | other / transient |
| `After login there should be a user` | other |

**Finding**: the classifier `checkIfExceptionIsInvalidCredentials` looks for `"invalid credentials"`, a string
the library **never produces** (verified: no occurrence in the 3.0.6 sources). So in practice a revoked or expired
refresh token (`invalid_grant`) is classified as **transient**: the account stays `READY` on a dead token, and
launches go ahead and fail inside Minecraft. The Rust port should classify an OAuth `error == "invalid_grant"`
(and perhaps `interaction_required`) as credentials-rejected. That is a behaviour change, so it is listed as an
open question; the recommended answer is yes.

### 1.8 `MCLauncherAuthManager` (launcher-wide auth concerns, static facade)

**Constants**

| Constant | Value | Meaning |
|---|---|---|
| `AUTH_TIMEOUT_SECONDS` | 60 | timeout for one whole authenticator run; also `userForLaunch`'s wait |
| `MIN_AUTH_INTERVAL_MS` | 5000 | minimum gap between Microsoft calls (all accounts together) |
| `MAX_BACKOFF_MS` | 120000 | backoff cap |
| `TOKEN_REFRESH_INTERVAL_MS` | 4 h (14,400,000) | hard: a token older than this is refreshed before use |
| `TOKEN_SOFT_REFRESH_INTERVAL_MS` | 3 h (10,800,000) | soft: background refresh at startup past this |
| renewExistingLogin wait | `AUTH_TIMEOUT_SECONDS * 2` = 120 s | |
| refresh executor | single thread, daemon, name `mmcl-auth-refresh` | all refreshes run here |

**is_renewal_due(last_ms, now_ms, interval_ms)** (pure): `last_ms <= 0 → true`; else `(now - last) >= interval`.
A clock that went backwards (`now < last`) → not due, deliberately (otherwise every launch forces a re-login
until the clock catches up). Tests: `MCLauncherAuthManagerRenewalTest` (boundary at exactly the interval is
due; one ms short is not due; negative is due; future is not due; the given interval is honoured).

**Rate limit** `enforce_rate_limit()`: one global mutex (Java `static synchronized`), **held while sleeping**,
so all callers queue behind it:
```
failures = consecutive_failures (snapshot)
backoff  = failures > 0 ? min(5000 * 2^min(failures, 10), 120000) : 0      // 10 s, 20 s, 40 s, 80 s, 120 s...
elapsed  = now - last_attempt_ms        // last_attempt starts at 0 → huge → no wait on the first call
wait     = max(5000 - elapsed, backoff - elapsed)
if wait > 0: if failures > 0 report status ("Signing In", "Waiting {wait/1000}s before retrying (attempt {failures+1})...")
             sleep(wait)
last_attempt_ms = now
```
`record_auth_failure()` increments `consecutive_failures`; any success sets it to 0. Interactive sign-ins and
every account's renewals share the counter and the clock.

**run_with_timeout(authenticator)**: runs it on a fresh thread and waits up to 60 s; on timeout cancels it (Java
interrupts the thread; the HTTP calls have their own 5 s/10 s timeouts, so they unwind soon). Rust: run the
chain as an async future under `tokio::time::timeout(60s)`, which drops the future and cancels the in-flight
request.

**renew_with_microsoft(gzipped) -> Result<Renewal, RenewalFailed>** (the production `Renewer`):
1. Status ("Signing In", "Preparing to contact authentication servers..."); `enforce_rate_limit()`.
2. Parse the AuthenticationFile (parse failure → the final catch-all: transient, `record_auth_failure`).
3. Status ("Signing In", "Contacting authentication servers..."). Run the chain (refresh grant) with the timeout.
4. No user or no result file → `record_auth_failure`, `RenewalFailed("renewal returned no user", rejected = true)`.
5. Success → `consecutive_failures = 0`, status "Signed in successfully", return
   `Renewal{user, gzipped: result.write_compressed()}`.
6. Timeout → log error, `record_auth_failure`, `RenewalFailed("timed out", false)`.
7. Chain error → log **only the error type name** (bug-guard: the library puts OAuth response bodies in
   messages, and those can contain token fragments, so never log messages from this layer), `record_auth_failure`,
   `rejected = message contains "invalid credentials" || contains "not have bought"` (case-insensitive,
   `Locale.ROOT`), `RenewalFailed(<type name>, rejected)`.
8. Interrupted → `RenewalFailed("interrupted", false)` (no failure recorded).

**sign_in_with_microsoft(code, save, make_default) -> AuthResult**:
1. `enforce_rate_limit()` (no status text).
2. Run the chain (code grant) with the timeout. Timeout → `record_auth_failure`, `ERROR_OTHER`. Chain error →
   `record_auth_failure`, `process_auth_exception(e)`. Interrupted → failure recorded, `ERROR_OTHER`.
3. `user` or result file missing, or `accounts().add_signed_in(user, file, save, make_default)` returns false
   (unsafe uuid) → `record_auth_failure`, `ERROR_BAD_USERNAME_PASSWORD`.
4. Success → `consecutive_failures = 0`, `Ok(user)`.

`login_with_microsoft_account(code, save)` = `sign_in(code, save, make_default = true)` (login screen);
`add_account_with_microsoft(code, save)` = `make_default = false` (Add-account window, and "Sign in again").

**process_auth_exception(e)** (pure, tested): check, in this order, case-insensitive substrings of the message
(null message → no match):
1. `"not have bought"` → `ERROR_NOT_OWNED`
2. `"invalid credentials"` → `ERROR_BAD_USERNAME_PASSWORD`
3. `"no value present"` → `ERROR_NO_VAL` (a Java `Optional.get()` failure)
4. else log a warning and the type name only → `ERROR_OTHER`.
"not bought" wins over "invalid credentials" when both match.

**AuthResult**: Java `MCLauncherAuthResult(User)` with five distinct sentinel instances, all with `user = null`
(`ERROR_BAD_USERNAME_PASSWORD`, `ERROR_LOGIN_EXPIRED`, `ERROR_NOT_OWNED`, `ERROR_NO_VAL`, `ERROR_OTHER`),
compared by identity. Rust: `enum AuthOutcome { Ok(User), BadCredentials, LoginExpired, NotOwned, NoValue, Other }`.

**Default-account facade** (what screens call):
- `has_existing_login()` = `accounts.has_default()`.
- `get_logged_in_user()` = default user or None.
- `get_pending_refresh_future()` = the default account's in-flight refresh mapped to AuthResult, or None.
- `load_cached_user_now()` = default user if its name and uuid are non-blank, else None. Never touches the
  network.
- `renew_existing_login_async()` = `accounts.refresh_default()` mapped by `to_auth_result`.
- `renew_existing_login()` (blocking): status ("Signing In", "Checking session..."), wait up to 120 s for the
  async one; timeout, interrupt or exceptional completion → `ERROR_OTHER`.
- `to_auth_result(fut)`: success with a user → `Ok(user)`; failure whose cause (unwrap `CompletionException`) is
  `RenewalFailed{rejected: true}` → `ERROR_LOGIN_EXPIRED`; anything else → `ERROR_OTHER`.
- `logout()` = `accounts.remove(default_uuid)`.
- `archive_and_logout()` = `accounts.set_default(None)` (keeps every account; the next start shows the login
  screen).
- `switch_to_archived_profile(uuid)` = `accounts.set_default(uuid)`.
- `set_status_callback(cb)`: one global, swappable callback `(section, detail)`, used by the startup progress
  screen. Reported from the refresh thread.

**try_preemptive_background_renewal()** (called once at startup, fire-and-forget, must never panic onto the
startup path; Java wraps the whole body in a catch-all that logs):
- Offline (`NetworkUtilities.isOffline()`) → return.
- For each account: skip `NEEDS_SIGN_IN`; skip `renewed_at == 0`; `elapsed = now - renewed_at`; skip
  `elapsed < 3 h`; skip if `elapsed >= 4 h` **and** it is the default (the startup path renews that one); else
  `accounts.refresh(uuid, force = true)` and log a warning (type only) if it fails.
- Note: non-default accounts past 4 h *are* refreshed here.

**user_for_launch(override_uuid) -> Result<User, Blocked>** (blocks; call off the UI thread):
1. `res = LaunchAccountResolver::resolve(override, accounts.accounts())`; not ok → `Blocked(res)`.
2. Wait for `accounts.refresh(res.uuid, force = false)` up to 60 s.
   - Success with a user → return it.
   - Failure with `RenewalFailed{rejected: true}` → `Blocked(NEEDS_SIGN_IN, account_name, from_override)`.
   - Any other failure, or a timeout (the refresh keeps running in the background) → fall through.
3. `current = accounts.user(uuid)`; None (never identified) → `Blocked(NEEDS_SIGN_IN)`; else return the cached
   user. Its token is stale, but it outlives the refresh interval by hours.

**Startup flow** (`LauncherCore.performClientLogin`, for the app/GUI crate to port):
- `has_existing_login()`:
  - `load_cached_user_now()` is Some → paint the GUI right away; `renew_existing_login_async()` in the
    background; done.
  - else (cache unreadable) → progress screen, status callback attached, `renew_existing_login()` (blocking).
    Success → done. **Any failure (even transient `ERROR_OTHER`) → `logout()`**, which deletes the default
    account's stored credentials, then the login screen with "unable to refresh". This is harsh for a
    transient failure; see the open questions.
- else → login screen.
- Later in startup (`LauncherSession`): `try_preemptive_background_renewal()`.
- TUI mode: no login screen; if `!has_existing_login()` print a message and exit; else `load_cached_user_now()`
  + `renew_existing_login_async()`.

**Launch-time handling of `Blocked`** (`LauncherCore.resolveLaunchUser`; app crate):
- `OVERRIDE_MISSING`: if headless, no default, or the override was forced for this launch only (MCP
  `launch_modpack` naming an account) → refuse (return None). Else ask "Play as <default> instead?"; yes →
  `set_account_override_for_pack(key, None)` (the override can never apply again) and retry
  `user_for_launch(None)`.
- `NEEDS_SIGN_IN`: ask "<name> needs to sign in again"; the "Sign in again" answer opens the Add-account window.
  The launch does not proceed.
- `NO_ACCOUNT`: error "No account is signed in. Sign in to play."

**Per-pack override config** (config subsystem; documented here because auth consumes it):
`get_account_override_for_pack(pack_key)`: blank key → None; missing map, missing entry, JSON null, blank string,
or **any** malformed value → None (falls back to the default). `set_account_override_for_pack(key, uuid)`: blank
key ignored; None/blank uuid removes the entry; a non-object map value is replaced with a new object; debounced
write. `defaultAccountUuid` get defaults to `""`; set with None → `""`.

### 1.9 `AccountManager` (accounts, sessions, default, refresh)

**Types**
- `Status { Ready, Refreshing, NeedsSignIn }`.
- `AccountInfo { uuid, display_name ("" if unknown), status, is_default, remembered, last_used_ms }` (snapshot).
- `Renewer: Fn(&[u8] gzipped) -> Result<Renewal{user, gzipped}, RenewalFailed{message, credentials_rejected}>`.
- `DefaultAccountSetting { get() -> String ("" = none), set(String) }` (backed by `configuration.json`).
- Internal `Session { uuid, display_name, user: Option<User>, renewed_at_ms, last_used_ms, remembered, status
  (initially Ready), memory_auth_file: Option<Vec<u8>>, pending: Option<SharedFuture<User>> }`.

**Constructor inputs** (seams; all injected): store, `legacy_folder` (= `<config>`), cipher, default setting,
renewer, clock (epoch ms), executor, `refresh_interval_ms` (4 h in production).

**Concurrency**: one mutex guards all state (Java `synchronized(this)`, reentrant). Listeners
(`Vec<Box<dyn Fn()>>`, copy-on-write) are called **outside** the lock, on whichever thread made the change, except
`fire_changed_later`, which posts the notification to the executor because the caller holds the lock. A listener
that panics is caught and logged (type only); the others still run. The UI must hop to its own thread.

**Lazy load** `ensure_loaded()` (first call of any public method; idempotent; sets `loaded = true` *first*, so a
failure part-way never retries):
1. `migrated = migrate_legacy_layout()`.
2. For each `store.list()` entry (most recent first): Session with `remembered = true`,
   `user = store.read_cached_user(uuid)` (may be None), `renewed_at = store.read_renewal_ms(uuid)`,
   `last_used = entry.last_used_ms`, `display_name = user.name if user else entry.display_name`.
   Insert into an insertion-ordered map.
3. If `migrated` is Some → `default_setting.set(migrated)` (the flat files were the active account).
4. `default_uuid = resolve_default(default_setting.get(), session keys)`, where
   `resolve_default(configured, available) = configured non-blank && available.contains(configured) ? Some : None`.
   A configured default that is gone resolves to None **without** rewriting the config.

**Legacy migration** `migrate_legacy_layout() -> Option<uuid>`:
- No `<config>/player.mica` regular file → None.
- `auth = read_auth_file_at(<config>/player.mica, cipher)`; None (another machine, corrupt) → warn, **delete**
  the three legacy files, return None. The user signs in again, as before.
- `user = read_cached_user_at(<config>/cached_user.json)`; None or unsafe uuid → keep `auth` in memory as
  `unidentified_legacy_auth_file` (the first renewal identifies it; see `refresh_default`), return None. The
  files are **not** deleted yet.
- `renewed = read_renewal_ms_at(<config>/renewal.timestamp)`.
- `persist(user, auth, renewed, now)`, then verify `store.read_auth_file(uuid)` is Some (else IO error
  "migrated authentication file did not read back"). On any IO error → warn, return None (flat files kept; the
  next start repeats the migration).
- Delete the legacy files, log "migrated", return `Some(uuid)`.
- The flat copy overwrites a stale archived copy of the same uuid (it is the newest). Archived
  `profiles/<uuid>/` folders from the old `ProfileArchive` need no migration (same layout).
- Order bug-guard: copy, then verify, then delete. A crash part-way just repeats the migration.

**Queries** (all lock, `ensure_loaded`): `accounts()` (snapshots sorted by `last_used_ms` desc, stable over map
insertion order), `account(uuid)`, `user(uuid)`, `default_uuid()`, `default_user()`,
`has_default() = default_uuid.is_some() || unidentified_legacy_auth_file.is_some()`,
`is_refresh_due(uuid) = session exists && (user.is_none() || is_renewal_due(renewed_at, now, interval))`
(unknown account → false), `renewed_at_ms(uuid)` (0 if unknown), `pending_refresh(uuid)` (None if no session, no
pending, or pending already done).

**set_default(uuid: Option) -> bool**: under lock: `Some(u)` not signed in → false (no change). Equal to the
current → true (no event). Else `default_uuid = uuid`; if Some, `touch(session)` (`last_used = now`; if
remembered, `store.write_meta(uuid, display_name, last_used)`, warn on IO error); `persist_default()`. Then fire
listeners (outside the lock); return true. `set_default(None)` keeps every account signed in.

**add_signed_in(user, gzipped, remember, make_default = true) -> bool**:
- `user` None or `!is_safe_uuid(user.uuid)` → false (test `aUserWithoutAUsableUuidIsRefused`).
- Under lock: get or create the Session; set `user`, `display_name = user.name`, `renewed_at = last_used = now`,
  `status = Ready`, `remembered = remember`.
  - remember: `memory_auth_file = None`; `persist(user, gzipped, now, now)`. On IO error: log an error, then
    downgrade to session-only (`remembered = false`, `memory_auth_file = gzipped`) so the sign-in still works
    for this run.
  - not remember: `memory_auth_file = gzipped`; `store.remove(uuid)`. Bug-guard: an older stored copy would
    otherwise be silently brought back on the next start.
  - `if make_default || default_uuid.is_none() { default_uuid = user.uuid }`; `persist_default()`.
- Fire listeners; return true.
- Edge: an `add_signed_in` while a refresh of the same uuid is pending leaves `pending` alone. When that refresh
  lands it overwrites `user` with the renewal's user (same identity). Acceptable.

**remove(uuid) -> bool**: under lock: remove the session (None → false). If remembered, `store.remove(uuid)`. If it
was the default: the new default is the most recently used remaining account (any, remembered or not), or None;
`persist_default()`. Fire; true.

**refresh(uuid, force) -> SharedFuture<Result<Option<User>, RenewalFailed>>**:
Under lock:
1. Unknown → completed failure `RenewalFailed("unknown account", false)`.
2. A pending refresh not yet done → return **the same** future (single-flight per account; test
   `oneRefreshPerAccountIsInFlightAtATime`).
3. `!force && !is_refresh_due(uuid)` → completed success with the current `user` (no network).
4. `auth = remembered ? store.read_auth_file(uuid) : memory_auth_file`. None → `status = NeedsSignIn`,
   `fire_changed_later()`, return a completed failure `RenewalFailed("no saved credentials", true)`.
5. New future; `session.pending = fut`; `status = Refreshing`.
Outside the lock: fire listeners; `executor.execute(complete_refresh(uuid, auth, fut))`; return fut.
Different accounts' refreshes are independent futures, but all run on the one refresh thread, spaced by the
rate limit.

**complete_refresh(uuid, auth, fut)** (on the executor):
- `r = renewer(auth)`. If it is Ok but the user is missing or `renewal.user.uuid != uuid` → failure
  `RenewalFailed("renewal returned a different account", true)` (bug-guard: the identity behind an entry never
  silently changes). Renewer `Err(e)` → `e`. A panic or unexpected error → `RenewalFailed(<type name>, false)`.
- Under lock: `session = sessions.get(uuid)`; only if it exists **and** `session.pending` is this very future
  (pointer identity):
  `pending = None`; on success `user`, `display_name`, `renewed_at = now`, `status = Ready`, then if remembered
  `persist(user, gzipped, now, session.last_used_ms)` (IO error logged), else `memory_auth_file = gzipped`.
  On failure: `status = rejected ? NeedsSignIn : Ready` (a transient failure leaves the account usable on its
  current token). A session that was removed (or removed and re-added) while the refresh ran gets nothing
  persisted (test `aRemovedAccountStaysRemovedWhenItsRefreshLands`).
- Fire listeners, then complete the future (success with the renewed user even if the session is gone; or
  failure).

**refresh_default() -> future**:
- Under lock read `legacy = unidentified_legacy_auth_file`, `current = default_uuid`.
- `current` Some → `refresh(current, false)`. Both None → completed failure `RenewalFailed("no default account",
  true)` (maps to `ERROR_LOGIN_EXPIRED`; test `refreshDefaultWithNoAccountFailsAsLoginExpired`).
- Only legacy → on the executor: `renewer(legacy)`. Success: clear the legacy bytes, delete the legacy flat
  files, `add_signed_in(renewal.user, renewal.gzipped, remember = true)` (so it becomes the default), complete
  with the user. Rejected failure: clear the legacy bytes and delete the flat files, complete exceptionally.
  Transient: keep everything (retried next start), complete exceptionally. Note that this path skips the
  single-flight `pending` map; two concurrent calls could renew twice. It is a cold-start one-off; acceptable,
  but a Rust port can guard it with a `OnceCell`/pending slot.

**persist(user, gzipped, renewed_at, last_used)** (lock held): `write_auth_file`, `write_cached_user`,
`write_renewal_ms`, `write_meta(uuid, user.name, last_used)`, in that order; the first IO error aborts.

**persist_default()** (lock held): if the default is None or remembered → `set(default or "")`. Else (the default
is session-only, so it can't be the default after a restart) persist the most recently used **remembered**
account, or `""` if there is none.

### 1.10 `LaunchAccountResolver` (pure)

```
resolve(override: Option<&str>, accounts: &[AccountInfo]) -> Resolution{uuid, problem, account_name, from_override}
has_override = override is Some and non-blank
chosen = first account where (has_override ? a.uuid == override : a.is_default)
chosen None  -> has_override ? (None, OVERRIDE_MISSING, None, true) : (None, NO_ACCOUNT, None, false)
chosen.status == NeedsSignIn -> (None, NEEDS_SIGN_IN, chosen.display_name, has_override)
else -> (chosen.uuid, None, chosen.display_name, has_override)
```
A `Refreshing` account can still launch (test `aRefreshingAccountCanStillLaunch`). An override never quietly
falls back to the default (playing on the wrong account is worse than one extra click).
`Blocked` error message: `"launch blocked: <problem>"`.

### 1.11 `GameSession`

Fields: `id` (process-unique, from a global counter starting at 1), `pack` (handle, may be None in tests),
`pack_key`, `pack_name`, `account_uuid` (None for server mode), `account_name`, `clock`, `cancelled` (atomic
bool), listeners (`Fn(&GameSession)`, copy-on-write), `worker` (the preparing thread handle / cancellation token),
`phase` (initially `Preparing`), `process`, `started_ms`, `ended_ms`, `exit_code`, `tracker`
(LaunchProgressTracker), `log` (GameLog), `crash_report` (String). All volatile in the Java; in Rust use a
`Mutex<Inner>` plus atomics, and hand out `Arc<GameSession>`.

`key_for(pack) = pack.settings_key().unwrap_or("name:" + pack.pack_name)`.

**Phase machine**

```
            attach_process(p)           exit code 0
Preparing ───────────────────▶ Running ──────────────▶ Exited
   │                                    └─ non-zero / unknown (-1) ─▶ Crashed
   └─ end_without_game(): cancelled ? Cancelled : Failed
is_active() = Preparing | Running
```

- `cancel()`: only when `phase == Preparing` **and** the flag flips false→true: set the flag, interrupt the bound
  worker (Rust: trigger a `CancellationToken` that the prep pipeline and downloads observe). Idempotent; no
  effect once running (test `cancelDoesNothingOnceTheGameIsRunning`). The phase stays `Preparing` until
  `end_without_game()`.
- `stop(force)`: if the process is alive: force → kill; else ask it to exit (Java `destroy()`: SIGTERM on Unix;
  **on Windows `destroy()` is also TerminateProcess**). Rust: Unix `kill(pid, SIGTERM)` for graceful,
  `Child::kill` (SIGKILL / TerminateProcess) for force; Windows graceful may post `WM_CLOSE` to the process's
  top-level windows, falling back to kill.
- `attach_process(p)`: ignored if p is None or `phase != Preparing`. Store it, `started_ms = now`, phase
  `Running`. Then on exit (a waiter thread or `tokio::process` wait): `exit_code` = code, or -1 if unavailable
  (on Unix, a signal death has no code: Java reports 128+signal, so map `ExitStatusExt::signal()` to `128 + sig`
  for parity), `ended_ms = now`, phase `Exited` if 0 else `Crashed`. Note: a game the user stopped exits
  non-zero and so lands in `Crashed`, and `LauncherCore.onGameExited` then shows a crash notification. The Java
  does this; recording a `stop_requested` flag is suggested in the open questions.
- `end_without_game()`: if `Preparing` → `ended_ms = now`, phase `Cancelled` if cancelled else `Failed`. No
  effect otherwise (test `endWithoutGameLeavesARunningGameAlone`). The launch worker calls it in a `finally`
  on every path.
- `set_tracker`, `set_log`, `set_crash_report` and every phase change notify all listeners, each guarded so a
  panicking listener doesn't stop the others.

Launch-flow responsibilities around a session (app crate, from `LauncherCore.play`):
1. Resolve the user (1.8) in client mode; server mode has no account.
2. Run the mod-conflict prompt *before* registering (nothing to clean up if cancelled).
3. Create the session, bind the worker, `ensure_running_game_follower()`, `registry.try_register(session)`;
   refused → `report_launch_refused` (PACK_ALREADY_RUNNING with GUI: bring that game's tab forward instead of a
   message; otherwise a warning toast `launch.refused.*`, or a log error when headless).
4. Prepare and spawn (`GameModPack.startGame(user, cancelled)`); if cancelled after the JVM spawned, kill it
   (bug-guard: no orphaned game window).
5. GUI mode: create `GameLog(file_for(log_dir, pack_name, timestamp))`, `log.attach(process)`,
   `session.set_log(log)`. Server mode inherits the terminal instead; the TUI reads the output itself.
6. `session.attach_process(p)`; on exit `on_game_exited`: record play time; if the code != 0, log an error,
   show a notification, `session.set_crash_report(pack.latest_crash_report())`, and bring the tab forward.
7. `finally`: clear any pending interrupt, then `session.end_without_game()`.

### 1.12 `LaunchAdmission` (pure) and `GameSessionRegistry`

`check(candidate, others, one_at_a_time) -> Decision{outcome, conflicting}`:
- For each other `s` (skip `s` itself and inactive ones), in order:
  - `s.pack_key` is Some and equals `candidate.pack_key` → `PackAlreadyRunning(s)`
  - `s.account_uuid` is Some and equals `candidate.account_uuid` → `AccountBusy(s)`
- Then if `one_at_a_time` and any other active session exists → `AnotherGameRunning(s)` (the specific conflict
  is reported first even in one-at-a-time mode).
- Else `Ok`.
Rationale (decision D1): one pack has one install folder (saves, options, mods, natives), and a running game
locks its jars on Windows. One account: Minecraft signs the older session out when an account logs in twice.
Server launches have `account_uuid = None` and never collide on accounts.

**Registry** (process singleton, `MAX_ENDED = 20`, `one_at_a_time = false` by default; nothing sets it true now):
- `try_register(s)`: under the lock, `check`; not ok → return the decision (not added). Else push, `prune_ended`.
  Outside the lock: add a session listener that (under the lock) prunes and then fires registry listeners; fire
  registry listeners; return Ok. Check-and-add is atomic (test `theRegistryAdmitsAtomically`).
- `preview(candidate)`: `check` without registering (for greying out Play).
- `sessions()` (copy, oldest first), `active()`, `has_active()`, `newest_running()` =
  `RunningGameFollower::pick(sessions)`, `active_for_pack(key)`,
  `is_pack_active(pack) = active_for_pack(key_for(pack)).is_some()`. Bug-guard: anything that rewrites a pack's
  files (verify, uninstall, reinstall from the crash suggestion) must check this first.
- `dismiss(id)`: remove only if the session is **not** active; fire if removed.
- `prune_ended()`: count the ended sessions; while that count > 20, remove the oldest ended one (active ones are
  skipped and never removed).
- Listeners: `Fn()`, panics isolated.

### 1.13 `RunningGameFollower` and `LatestUpdateRunner`

`pick(sessions)`: among sessions with `phase == Running`, the one with the greatest `started_ms`; ties go to the
**later** one in the list (`>=`). Preparing sessions don't count.

`update(sessions)` (internally synchronized): `next = pick`; if `next` is the same session as `shown`
(identity) → nothing. Else `shown = next`; if next is Some → `sink.show_game(next)`; else if the previous was
Some → `sink.show_no_game()`. So nothing resets before any game has run (test
`preparingGamesAreNotShownAndNothingResetsBeforeAnyGameRan`), and the first game to exit doesn't clear the others.
`resync(sessions)`: `shown = None; update(sessions)`. Used after an in-process launcher restart, which resets
Discord and RGB while games keep running. Does nothing when no game runs.

Wiring (`LauncherCore.ensureRunningGameFollower`, installed once on the first launch): a `LatestUpdateRunner` on
its own daemon thread `mmcl-running-game-follower`. The sink's `show_game(s)` submits
`{ RgbIntegration.on_play_started(s.pack); Discord.set_game_presence(s.pack) }`; `show_no_game` submits
`{ RgbIntegration.on_play_ended(); Discord.set_menu_presence(localized "discordRpc.screen.selectingPack") }`.
Registry listener: `lock(follower) { follower.update(registry.sessions()) }`. Bug-guard: the snapshot is taken
**inside** the follower's lock, because two changes on different threads could otherwise apply their snapshots
out of order and leave Discord and RGB showing no game while one runs.
`restore_running_games_after_restart()`: if the registry has active sessions, show the Running Games window (GUI)
and `follower.resync(...)` under its lock.

`LatestUpdateRunner`: `submit(update)`: `mine = generation.fetch_add(1) + 1`; enqueue on a **single ordered**
executor a task that runs `update` only if `generation == mine` at its turn. Bug-guard: on an unordered pool a
slow "show the game" (RGB samples the pack logo) landed after a quick "no game" and left the keyboard and
Discord on a game that had already exited.

### 1.14 `GameLog` (output capture)

Purpose: drain the game's stdout and stderr for the **whole life of the game**, whether or not a window is
looking (bug-guard: an unread pipe fills within moments and the game stalls on its next log line; this used to
live in the console screen, so leaving that screen stopped the capture). Redact every line, keep a bounded
in-memory buffer, write a session log file, and batch lines to listeners.

Constants: `FLUSH_INTERVAL_MS = 150`, `TRIGGER_CHARS = 5_000_000`, `RETAIN_CHARS = 4_000_000`,
`CLOSED_TAIL_CHARS = 256 * 1024`. All three sizes are constructor parameters for tests. Java counts UTF-16
units; Rust may count bytes (or chars), as long as every cut lands on a char boundary, which the
newline-aligned cut gives for free.

`file_for(dir, pack_name, timestamp) = dir / ("game-" + replace(pack_name or "game", /[^a-zA-Z0-9._-]/ → "_") + "-" + timestamp + ".log")`.
Each non-matching char becomes one `_` (Java regex works per UTF-16 unit; for non-BMP chars Java emits two `_`.
Rust per-char gives one `_`; this doesn't matter).

State: `buffer: String`, `truncated: bool`, `line_count: u64` (all under one buffer lock); `pending: queue of
(index, line)`; `file_complete: AtomicBool`; writer (under its own lock); `closed` latch; `open_readers` counter;
listeners (copy-on-write).

`attach(stdout, stderr)`: `open_writer()`; `open_readers = 2`; spawn daemon threads `game-log-out`,
`game-log-err` (each runs `read`) and `game-log-flush` (`flush_loop`). Returns immediately.

`open_writer()`: no file → none. Else `create_dir_all(parent)`, create/truncate the file as UTF-8,
`file_complete = true`, apply owner-only (game logs can hold user names). On error: no writer, and
`file_complete` stays false.

`read(stream)`: read lines as UTF-8 (Java `BufferedReader.readLine` splits on `\n`, `\r` or `\r\n` and strips the
terminator; malformed bytes become U+FFFD). Rust: a byte reader that splits on `\n`, strips a trailing `\r`, and
**also splits on a lone `\r`** for parity; decode with `from_utf8_lossy`. For each line:
`safe = redact(line)`; under the buffer lock: `buffer += safe + "\n"`; `drop = full_log_drop_offset(buffer,
trigger, retain)`; if > 0, remove `buffer[..drop]`, `truncated = true`; push `(line_count++, safe)` to `pending`
**under the same lock** (bug-guard: a subscriber's snapshot and the numbering then agree on exactly which lines it
already has). Then `write(safe)` outside that lock. An IO error ends the loop (the stream closes when the game
exits). Always decrement `open_readers` at the end.

`write(line)`: under the writer lock, if there is a writer, write `line` + platform newline (Java `newLine()` =
`System.lineSeparator()`, so `\r\n` on Windows). On error `file_complete = false`. Bug-guard: a failed write must
never back up into the game's pipe, and the file now has a hole, so the buffer must keep its text.

`flush_loop()`: while `open_readers > 0`: sleep 150 ms (an interrupt breaks out), `deliver()`, flush the writer
(error → `file_complete = false`). Then: `deliver()`, close the writer (error → `file_complete = false`),
`trim_to_tail()`, release the `closed` latch, call `on_closed()` on every listener (panics isolated).

`deliver()`: drain `pending` into a batch; if it is non-empty, for each listener: a `FromIndex(start, target)`
wrapper delivers only lines with `index >= start` (and only if any are left); a plain listener gets all lines.
Panics isolated. Called on the flush thread only, so batches are ordered.

`trim_to_tail()`: only if `file_complete`. `drop = full_log_drop_offset(buffer, closed_tail, closed_tail)`; if > 0,
cut, shrink capacity, `truncated = true`. Purpose: the registry keeps up to 20 ended sessions, and each one
holding millions of characters added up. Without a complete file the buffer is all there is, so it stays (tests
`withoutAFileAnEndedLogKeepsEverything`, `anUnwritableFileKeepsTheWholeEndedLogInMemory`).

Read API:
- `text()` = the buffer copy. `is_truncated()`. `file()`. `is_closed()`. `await_closed(timeout_ms) -> bool`.
- `full_text()`: if not truncated → buffer. Else if there is a file, the log is closed and `file_complete`: read
  the whole file as UTF-8, apply `full_log_drop_offset(all, trigger, retain)` and return from that offset (so a
  very long session is capped like the live buffer). Any error → `text()`. Reads the file, so call it off the
  UI thread.
- `subscribe(listener, max_lines = 0) -> Subscription{snapshot, cancel, clipped}`: under the buffer lock:
  `drop = tail_start(buffer, max_lines)`, `snapshot = buffer[drop..]`, register `FromIndex(line_count, listener)`.
  After releasing the lock: if already closed → `listener.on_closed()` immediately. `clipped = drop > 0`.
  Guarantee: no line missed, none repeated (test `aLateSubscriberMissesNothingAndSeesNothingTwice`). The pending
  queue may still hold lines numbered below `start` that are in the snapshot; `FromIndex` filters them out.
  `cancel` removes the wrapper.
- `tail(max_lines) -> Tail{text, clipped}`: the same `tail_start` cut, copying only the tail.
- `add_listener` / `remove_listener` (plain, unfiltered).

`LogTrimPolicy` helpers (pure; port into the session crate or a shared util):
- `full_log_drop_offset(buf, trigger, retain)`: `len <= trigger → 0`; `drop_to = len - retain`; `drop_to <= 0 → 0`;
  else the index just after the first `\n` at or after `drop_to`; if there is no newline after it, return
  `drop_to` (raw cut, matching the old behaviour).
- `tail_start(text, max_lines)`: empty or `max_lines` unlimited (`<= 0`) → 0. `end = len`, minus one if the last
  char is `\n` (a final newline ends the last line rather than starting another). Scan backwards from `end-1`
  counting `\n`; when the count reaches `max_lines`, return `i + 1`. Not enough lines → 0.
  (`isUnlimited(n)` is `n <= 0` in that class; the Settings dropdown's "unlimited" choice maps to it.)

**Redaction** (`SensitiveDataRedactor.redact`, applied to every captured line; owned by utilities). Java regexes,
applied in order:
1. `(--accessToken)([=\s]+)\S+` → `$1$2[REDACTED]`
2. `(--clientToken)([=\s]+)\S+` → `$1$2[REDACTED]`
3. `token:[A-Za-z0-9._-]+:([0-9a-fA-F-]{32,36})` → `token:[REDACTED]:$1`
`redact_strict` (MCP and log export) additionally replaces dashed UUIDs
`\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b` and undashed
`\b[0-9a-fA-F]{32}\b` with `[REDACTED]`. These port directly to the `regex` crate (`\s` and `\b` work the same for
ASCII input; use `(?-u)` or the default Unicode classes, either is fine for redaction).
Accepted residual risk (doc): the live access token is on the game JVM's argv (`--accessToken`), readable by
same-user processes. That is inherent to the Minecraft launch protocol; logs are redacted, argv is not.

### 1.15 Crash analysis

**Inputs**: `crash_text` (the session's `crash_report` if any, else `log.full_text()`, else `""`, computed off the
UI thread in `GameSessionPane.showDiagnosis`), the pack (may be None), and `exit_code`. Other callers: the MCP
`LiveMcpLauncherView` (latest crash report) and `ModpackContentBrowser` (exit code 0).

`GameModPack.latest_crash_report()` (modpack subsystem): newest `*.txt` by mtime in `<packRoot>/crash-reports/`;
None if the folder is missing or empty, or the newest is **older than 60 s** (so only a report from this crash
counts); read as UTF-8; a read error logs and returns None.

**Output**: `CrashDiagnosis{category, severity, title, summary, suggestions: Vec<Suggestion>}`;
`Suggestion{label, action: Option<Action>, primary: bool}` (`hint` = no action). Categories: `OutOfMemory,
JavaVersion, ModLoading, MixinConflict, LoaderConflict, Gpu, Audio, NativeLibrary, WorldCorruption, DiskIo,
FileLock, Auth, Unknown`. Severities: `Critical, Warning, Info` (only Audio is Warning; Info is unused).

In Rust, make the analyzer **pure**: suggestions carry an `Action` enum (data), and the GUI crate executes them.
That fixes the Java's coupling to `Desktop`, `ConfigManager` and `MCLauncherGuiController`:

```rust
enum CrashAction { SetMaxRamMb(u64), OpenSettings, OpenRuntime, OpenPackFolder(PackFolder /*Root|Mods|CrashReports*/),
                   ReinstallPack, DisableMod(PathBuf), OpenUrl(String) }
```

**analyze(text, pack, exit_code)**:
1. `text` None or blank → `unknown(exit_code)`.
2. Run the detectors **in this order**; the first `Some` wins. A detector that panics or errors is logged and
   skipped:
   1. out of memory, 2. Java version, 3. disk full, 4. file lock, 5. native library, 6. OptiFine + Sodium,
   7. mixin conflict, 8. mod built for another MC version, 9. Forge dependency mismatch, 10. JEI/REI,
   11. generic mod loading, 12. audio, 13. GPU/OpenGL, 14. world corruption, 15. auth.
   (The ordering is the point: specific beats generic. OOM beats the downstream NoClassDef noise;
   OptiFine+Sodium beats mixin; MC-version beats generic mod-loading. All three are tested.)
3. Nothing matched: `phrase = extract_crash_search_phrase(text)`; if Some →
   `unknown_with_suggestions(exit_code, [search_suggestion(pack, phrase)])`, else `unknown(exit_code)`.

`unknown(code)`: Unknown, Critical, title `crash.unknown.title`, summary `crash.unknown.summary` with `{0} = code`.

All regexes below are Java syntax; they port unchanged to the Rust `regex` crate (no look-around or
backreferences are used). `find` = search anywhere. Inline flags as noted.

| # | Detector | Trigger regex(es) | Category / severity | Title / summary keys | Suggestions |
|---|---|---|---|---|---|
| 1 | OOM | `java\.lang\.OutOfMemoryError(?::\s*([^\r\n]+))?` | OUT_OF_MEMORY / Critical | `crash.oom.title`; summary `crash.oom.summary({detailPart},{currentMaxGb})` + optional `crash.oom.systemTotal({systemGb})`; `detailPart = crash.oom.detailSuffix(detail.trim())` if group 1 non-blank else `""` | see below |
| 2 | Java version | `(java\.lang\.UnsupportedClassVersionError\|has been compiled by a more recent version of the Java Runtime\|class file version (\d+)\.\d+)` | JAVA_VERSION / Critical | `crash.javaVersion.title`; `crash.javaVersion.summary({targetPart},{manifestPart})` | primary `crash.action.openRuntime` → OpenRuntime; `crash.action.openSettings` → OpenSettings |
| 3 | Disk full | `(No space left on device\|There is not enough space on the disk\|Disk full)` | DISK_IO / Critical | `crash.diskFull.title/summary` | open-folder list |
| 4 | File lock | `The process cannot access the file because it is being used by another process\|FileSystemException:.*Access is denied\|java\.nio\.file\.AccessDeniedException\|Sharing violation` | FILE_LOCK / Critical | `crash.fileLock.title/summary` | primary `crash.action.openInstallFolder` (pack root; built even when pack is None, which the Java would then NPE on inside its async task; Rust: omit when pack is None) + crash-reports suggestion |
| 5 | Native library | `java\.lang\.UnsatisfiedLinkError\|Could not (?:locate\|load) the native library` | NATIVE_LIBRARY / Critical | `crash.nativeLib.title/summary` | if the pack has a non-blank pack URL: primary `crash.action.reinstallModpack`; then the open-folder list; then crash-reports |
| 6 | OptiFine+Sodium | both `(?i)optifine\|optifabric` **and** `(?i)\bsodium\b\|me\.jellysquid\.mods\.sodium` | LOADER_CONFLICT / Critical | `crash.optifineSodium.title/summary` | disable-mod("optifine"), disable-mod("sodium") when found; if none: primary `openModsFolder`; else the first becomes primary + non-primary `openModsFolder` |
| 7 | Mixin | `org\.spongepowered\.asm\.mixin\.transformer\.throwables\.Mixin\w+\|MixinApplyError\|MixinTransformerError` | MIXIN_CONFLICT / Critical | `crash.mixin.title`; `crash.mixin.summary({culpritPart})`, `culpritPart = crash.mixin.culpritSuffix(mod)` | if a culprit is found and a jar matches: primary disable-mod(culprit lower-case); then the open-folder list; then crash-reports |
| 8 | MC-version mismatch | `(?:requires\|requires loaded\|compiled for\|compiled against)\s+(?:Minecraft\s+)?(?:version\s+)?([\d]+\.[\d]+(?:\.[\d]+)?)\s+(?:but(?: you have\| we have\| this version is)?\|but is being loaded by)\s+(?:Minecraft\s+)?(?:version\s+)?([\d]+\.[\d]+(?:\.[\d]+)?)` | MOD_LOADING / Critical | title `crash.modMcMismatch.title({req},{cur})`; summary `crash.modMcMismatch.summary({req},{cur})` | open-folder list |
| 9 | Forge dependency | `Mod\s+'([^']+)'\s*\([^)]+\)\s*requires\s+([\w\-]+)\s+([\d\.\[\]\(\),~^]+)\|Mod\s+([\w\-]+)\s+requires\s+([\w\-]+)\s+version\s+([\d\.\[\]\(\),~^]+)` | MOD_LOADING / Critical | `crash.forgeDep.title`; `crash.forgeDep.summary({requiring},{required},{range})` from groups 1-3 or 4-6 | open-folder list |
| 10 | JEI/REI | dup: `Duplicate recipe(?: id\| identifier)\|mezz\.jei.*RecipeRegistry\|me\.shedaniel\.rei.*Recipe(?:Display\|Registry)Exception`; dual (flags DOTALL + CASE_INSENSITIVE, i.e. `(?si)`): `(?:mezz\.jei\|jei-\d\|just enough items).*?(?:me\.shedaniel\.rei\|roughly enough items\|rei-\d)\|(?:me\.shedaniel\.rei\|roughly enough items\|rei-\d).*?(?:mezz\.jei\|jei-\d\|just enough items)` | MOD_LOADING / Critical | dual: `crash.jeiRei.titleDual` / `summaryDual`; else `titleCollision` / `summaryCollision` | dual: disable-mod("jei"), disable-mod("rei") (first one primary); always + non-primary `openModsFolder` |
| 11 | Mod loading | `net\.minecraftforge\.fml\.(?:loading\.moddiscovery\.[\w$]+\|ModLoadingException)\|requires Minecraft Forge\|missing dependencies\|java\.lang\.NoClassDefFoundError\|java\.lang\.ClassNotFoundException` | MOD_LOADING / Critical | `crash.modLoading.title`; with class: `summaryWithClass({cls})` where cls = group 1 of `(?:NoClassDefFoundError\|ClassNotFoundException):\s*([\w/.$]+)` with `/`→`.`; else `summaryGeneric` | open-folder list |
| 12 | Audio | `Could not (?:open\|create\|initialize) (?:audio\|sound\|OpenAL)\|OpenAL [Ee]rror\|AL_INVALID_(?:DEVICE\|OPERATION)\|com\.mojang\.blaze3d\.audio\.\w+Exception\|paInternalError\|ALSA lib\|No default audio device` | AUDIO / **Warning** | `crash.audio.title/summary` | crash-reports |
| 13 | GPU | `(?:Pixel format not accelerated\|Failed to create OpenGL\|GLFW.*(?:error\|fail)\|org\.lwjgl\.opengl\.GLException\|Could not (?:create\|initialize) display\|GL_OUT_OF_MEMORY)` | GPU / Critical | `crash.gpu.title/summary` | none |
| 14 | World | `RegionFileException\|ChunkLoadException\|Failed to load chunk\|net\.minecraft\.nbt\.NbtException\|Invalid level format\|Could not load level` | WORLD_CORRUPTION / Critical | `crash.world.title/summary` | open-folder list |
| 15 | Auth | `Could not authenticate against\|Bad login\|Invalid session ID\|Authentication error` | AUTH / Critical | `crash.auth.title/summary` | primary `crash.action.openSettings` |

(In the table, `\|` is a literal `|` alternation escaped for Markdown.)

Note on regex semantics: Java `.` excludes line terminators unless DOTALL, the same as Rust's default. Java
`\w` is ASCII-only by default; Rust `\w` is Unicode. Use `(?-u:\w)` or accept the difference (crash text is
mostly ASCII). `GLFW.*(?:error|fail)` is case-sensitive.

**OOM suggestions**: `current_max_mb = config.max_ram_mb`; `current_gb = max(1, current_max_mb / 1024)`;
`system_gb = total RAM / 2^30` (0 if unknown; Rust `sysinfo`); `cap = system_gb > 0 ? max(4, system_gb*3/4) :
∞`; `suggested = min(current_gb + 2, cap)`. If `suggested > current_gb`: primary
`crash.oom.increaseRam({suggested})` → `SetMaxRamMb(suggested * 1024)` (log `log.crashAnalyzer.bumpedRam`).
Always: `crash.oom.openRamSettings` → OpenSettings. The summary appends `crash.oom.systemTotal({system_gb})`
when `system_gb > 0`.

**Java-version helper**: the required release comes from `class file version (\d+)\.\d+`: major ≥ 49 → `major
- 44` (61 → "17"), else the raw number. `targetPart = crash.javaVersion.targetSuffix({release})` when found;
`manifestPart = crash.javaVersion.manifestSuffix` when the pack is Some.

**Mixin culprit**: the first match of `mixins?\.([a-z0-9_\-]+)\.json|@Mixin\(([^\)]+)\)`; group 1 if set, else
group 2. Note that `mixins.foo.json` and `foo.mixins.json` both appear in the wild, and only the former is
captured. Keep the regex as-is for parity.

**open-folder list** (pack None → empty): primary `crash.action.openModsFolder` → OpenPackFolder(Mods);
`crash.action.openInstallFolder` → OpenPackFolder(Root).
**crash-reports suggestion** (pack None → empty): `crash.action.openCrashReports` → OpenPackFolder(CrashReports).
Opening a folder creates it if missing, then opens it in the OS file manager (Rust: `open` crate).

**disable-mod(pack, substring)**: None if the pack or its root is None, or `<root>/mods` isn't a directory, or
listing fails. Else the **first** entry (directory order, unspecified) whose lower-cased name ends with `.jar`
and contains `substring`; suggestion `crash.action.disableMod({file name})` → rename `x.jar` → `x.jar.disabled`
with replace (Forge, NeoForge and Fabric all skip `.jar.disabled`; rename, not delete, so it can be re-enabled).
Rust: sort the candidates by name so the choice is deterministic.

**reinstall**: on a background task: `if uninstall(pack) { install_by_url(pack.url) }`. Uninstall refuses (and
reports) while the pack is running.

**OpenSettings / OpenRuntime**: these run from the Running Games window, so first ask the main window
`may_navigate_away()` (it protects unsaved work), then go to the Settings / Runtime screen.

**extract_crash_search_phrase(text)** (pure, tested): first match of
`((?:[a-zA-Z_$][\w$]*\.)+[A-Z][\w$]*(?:Exception|Error))(?::\s*([^\r\n]+))?`; no match → None; message missing
or blank → the type; else `type + ": " + message.trim()` with the message cut to 120 chars and trimmed again.
**search_suggestion(pack, phrase)**: blank → None; `query = (pack name + " " if any) + phrase`; URL
`https://www.google.com/search?q=` + form-URL-encode(query) (space → `+`); label `crash.action.searchWeb` →
OpenUrl.

---

## 2. Class-by-class map

| Java | Rust (crate::module::Type) | Key items |
|---|---|---|
| `auth/AccountCipher` (interface) | `mmcl-secrets::Cipher` trait | `encrypt(&[u8]) -> Result<Vec<u8>>`, `decrypt(&[u8]) -> Result<Option<Vec<u8>>>`, `MachineBound::new(paths)` |
| `utilities/MachineSecretCipher` | `mmcl-secrets::machine::MachineCipher` + `fingerprint` | `assemble_fingerprint(user, os, hw, secret_hex)`, `java_os_name()`, `hardware_uuid()`, `install_secret()`; also `encrypt_str`/`decrypt_str` (Base64) for other callers |
| `utilities/FilePermissions` | `mmcl-fsutil::perms` (or a module of `mmcl-secrets`) | `apply_owner_only(path)`, `apply_owner_only_dir(path)`, `write_atomically(path, bytes)` |
| `auth/AccountStore` | `mmcl-auth::store::AccountStore` | `list`, `read_auth_file[_at]`, `write_auth_file`, `read_cached_user[_at]`, `write_cached_user`, `read_renewal_ms[_at]`, `parse_renewal_timestamp`, `write_renewal_ms`, `write_meta`, `remove`, `is_safe_uuid` |
| `auth/AccountStore.Entry` | `mmcl-auth::store::Entry` | `uuid, display_name, last_used_ms` |
| `auth/AccountManager` | `mmcl-auth::accounts::AccountManager` | see the API in section 3 |
| `AccountManager.Status/AccountInfo/Renewal/RenewalFailedException/Renewer/DefaultAccountSetting` | `mmcl-auth::accounts::{Status, AccountInfo, Renewal, RenewalError, Renewer (trait), DefaultAccountSetting (trait)}` | |
| `auth/LaunchAccountResolver` (+ `Problem`, `Resolution`, `BlockedException`) | `mmcl-auth::resolve::{resolve, Problem, Resolution, Blocked}` | pure fn |
| `auth/MCLauncherAuthManager` | split: `mmcl-auth::msa` (protocol client), `mmcl-auth::limiter::RateLimiter`, `mmcl-auth::service::AuthService` (facade, owns the manager, executor, status callback) | `sign_in`, `add_account`, `renew`, `user_for_launch`, `try_preemptive_background_renewal`, `is_renewal_due`, `classify_error` |
| `auth/MCLauncherAuthResult` | `mmcl-auth::AuthOutcome` enum | |
| `minecraft_authenticator` (external lib) | `mmcl-auth::msa::{oauth, xbox, minecraft, authfile}` | `authorize_url()`, `exchange_code`, `refresh`, `xbl`, `xsts`, `launcher_login`, `entitlements`, `profile`, `xbox_profile` (optional), `AuthFile{client_id: Uuid, refresh_token, extra}`, `read_compressed/write_compressed` |
| `minecraft_authenticator.login.User` | `mmcl-auth::User` (serde, camelCase) | `uuid, name, access_token, user_type, xuid, client_id` |
| `gui/MicrosoftSignIn` | `mmcl-auth::signin::{login_url, parse_callback, Callback}` (pure) + webview host in the app crate | `user_cancelled()` |
| `session/GameSession` (+ `Phase`) | `mmcl-session::session::{GameSession, Phase}` | `Arc`, `key_for`, `cancel`, `stop`, `attach_process`, `end_without_game`, listeners |
| `session/LaunchAdmission` | `mmcl-session::admission::{check, Outcome, Decision}` | pure |
| `session/GameSessionRegistry` | `mmcl-session::registry::GameSessionRegistry` | `global()`, `try_register`, `preview`, `sessions`, `active`, `newest_running`, `active_for_pack`, `is_pack_active`, `dismiss`, listeners |
| `session/RunningGameFollower` (+ `Sink`) | `mmcl-session::follower::{RunningGameFollower, Sink}` | `pick`, `update`, `resync` |
| `session/LatestUpdateRunner` | `mmcl-session::latest::LatestUpdateRunner` | `submit`, `on_daemon_thread(name)` |
| `session/GameLog` (+ `Listener`, `Subscription`, `Tail`) | `mmcl-session::log::{GameLog, LogListener, Subscription, Tail}` | `file_for`, `attach(Child)` / `attach_streams`, `text`, `full_text`, `subscribe`, `tail`, `await_closed` |
| `gui/LogTrimPolicy` (two fns) | `mmcl-session::trim::{full_log_drop_offset, tail_start}` | pure |
| `utilities/SensitiveDataRedactor` | `mmcl-util::redact::{redact, redact_strict}` | `once_cell` regexes |
| `crash/CrashReportAnalyzer` | `mmcl-crash::analyze` + `detectors` module | `analyze(text, &PackInfo, exit_code, &Env) -> CrashDiagnosis`, `extract_search_phrase` |
| `crash/CrashDiagnosis` (+ enums) | `mmcl-crash::{CrashDiagnosis, Category, Severity}` | |
| `crash/Suggestion` | `mmcl-crash::{Suggestion, CrashAction}` | action is data, not a closure |

---

## 3. Proposed crates and public API

Workspace naming follows the existing `crates/mmcl-app`. Suggested new crates:

- **`mmcl-secrets`**: `Cipher` trait, `MachineCipher`, fingerprint, `machine-key.bin`, owner-only perms,
  atomic write. Small; shared by auth and the CurseForge-key config.
- **`mmcl-auth`**: protocol client, AuthFile, store, manager, resolver, rate limiter, facade, sign-in URL and
  callback parsing. No GUI.
- **`mmcl-session`**: GameSession, admission, registry, GameLog, follower, latest-update runner, trim policy.
- **`mmcl-crash`**: pure analyzer (it could be a module of `mmcl-session`; a separate crate keeps regex-heavy
  compile units apart and testable).
- Redaction lives in a shared `mmcl-util` (also used by the launcher logger and MCP).

Async model: use **tokio** (the HTTP and download subsystems will need it anyway). The auth refresh "single
daemon thread" becomes one dedicated tokio task fed by an mpsc queue (strictly serial, like the Java
single-thread executor). Futures handed to callers are `futures::future::Shared<BoxFuture<Result<User,
RenewalError>>>` (cloneable, so single-flight callers share one). GPUI code awaits them with
`cx.background_executor().spawn(...)` or bridges through a `tokio::runtime::Handle`. Listeners are plain
`Arc<dyn Fn() + Send + Sync>`; the GUI side forwards them into a GPUI `Model`/`Entity` with `cx.notify()` on the
foreground.

```rust
// ---------- mmcl-secrets ----------
pub trait Cipher: Send + Sync {
    fn encrypt(&self, plaintext: &[u8]) -> anyhow::Result<Vec<u8>>;
    /// Ok(None) when the envelope is too short (< 28 bytes); Err on GCM tag failure.
    fn decrypt(&self, envelope: &[u8]) -> anyhow::Result<Option<Vec<u8>>>;
}
pub struct MachineCipher { /* config_dir, OnceLock<String> secret_hex, OnceLock<Fingerprint parts> */ }
impl MachineCipher { pub fn new(config_dir: PathBuf) -> Self; }
pub fn assemble_fingerprint(user: &str, os_name: &str, hw_uuid: &str, install_secret_hex: &str) -> String;
pub fn java_os_name() -> String;
pub fn hardware_uuid() -> String;            // oshi-compatible: "unknown" when not found, "" on error
pub fn apply_owner_only(path: &Path);        // best effort, logs
pub fn apply_owner_only_dir(path: &Path);
pub fn write_atomically(target: &Path, bytes: &[u8]) -> std::io::Result<()>;

// ---------- mmcl-auth ----------
#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct User { pub uuid: String, pub name: String, pub access_token: Option<String>,
                  #[serde(rename = "type")] pub user_type: Option<String>,
                  pub xuid: Option<String>, pub client_id: Option<String> }
impl fmt::Debug for User /* redacts access_token */;

pub enum Status { Ready, Refreshing, NeedsSignIn }
pub struct AccountInfo { pub uuid: String, pub display_name: String, pub status: Status,
                         pub is_default: bool, pub remembered: bool, pub last_used_ms: i64 }
#[derive(thiserror::Error)] #[error("{message}")]
pub struct RenewalError { pub message: String, pub credentials_rejected: bool }
pub struct Renewal { pub user: User, pub auth_file_gz: Vec<u8> }
#[async_trait] pub trait Renewer: Send + Sync { async fn renew(&self, auth_file_gz: &[u8]) -> Result<Renewal, RenewalError>; }
pub trait DefaultAccountSetting: Send + Sync { fn get(&self) -> String; fn set(&self, uuid: &str); }
pub type RefreshFuture = Shared<BoxFuture<'static, Result<Option<User>, Arc<RenewalError>>>>;

pub struct AccountManager { /* Mutex<State>, store, cipher, setting, renewer, clock, spawner, interval */ }
impl AccountManager {
    pub fn new(store: AccountStore, legacy_dir: PathBuf, cipher: Arc<dyn Cipher>,
               default_setting: Arc<dyn DefaultAccountSetting>, renewer: Arc<dyn Renewer>,
               clock: Arc<dyn Fn() -> i64 + Send + Sync>, spawner: Arc<dyn Spawn>, refresh_interval_ms: i64) -> Self;
    pub fn accounts(&self) -> Vec<AccountInfo>;            // most recently used first
    pub fn account(&self, uuid: &str) -> Option<AccountInfo>;
    pub fn user(&self, uuid: &str) -> Option<User>;
    pub fn default_uuid(&self) -> Option<String>;
    pub fn default_user(&self) -> Option<User>;
    pub fn has_default(&self) -> bool;
    pub fn is_refresh_due(&self, uuid: &str) -> bool;
    pub fn renewed_at_ms(&self, uuid: &str) -> i64;
    pub fn pending_refresh(&self, uuid: &str) -> Option<RefreshFuture>;
    pub fn set_default(&self, uuid: Option<&str>) -> bool;
    pub fn add_signed_in(&self, user: User, auth_file_gz: Vec<u8>, remember: bool, make_default: bool) -> bool;
    pub fn remove(&self, uuid: &str) -> bool;
    pub fn refresh(&self, uuid: &str, force: bool) -> RefreshFuture;
    pub fn refresh_default(&self) -> RefreshFuture;
    pub fn add_listener(&self, f: Arc<dyn Fn() + Send + Sync>) -> ListenerId;
    pub fn remove_listener(&self, id: ListenerId);
}

pub enum Problem { NoAccount, OverrideMissing, NeedsSignIn }
pub struct Resolution { pub uuid: Option<String>, pub problem: Option<Problem>,
                        pub account_name: Option<String>, pub from_override: bool }
pub fn resolve(override_uuid: Option<&str>, accounts: &[AccountInfo]) -> Resolution;
#[derive(thiserror::Error)] #[error("launch blocked: {:?}", .0.problem)] pub struct Blocked(pub Resolution);

pub enum AuthOutcome { Ok(User), BadCredentials, LoginExpired, NotOwned, NoValue, Other }

pub mod signin {
    pub const REDIRECT_URL: &str = "https://login.live.com/oauth20_desktop.srf";
    pub const BROWSER_USER_AGENT: &str = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:147.0) Gecko/20100101 Firefox/147.0";
    pub fn login_url() -> String;                       // includes &prompt=select_account
    pub struct Callback { pub code: Option<String>, pub error: Option<String> }
    impl Callback { pub fn user_cancelled(&self) -> bool; }
    pub fn parse_callback(location: &str) -> Callback;
}

pub mod msa {   // the minecraft_authenticator replacement
    pub struct AuthFile { pub client_id: Uuid, pub refresh_token: String, pub extra: BTreeMap<String, String> }
    impl AuthFile { pub fn read_compressed(gz: &[u8]) -> Result<Self>; pub fn write_compressed(&self) -> Result<Vec<u8>>; }
    pub enum MsaError { OAuth{error: String, description: Option<String>}, XblHttp(u16), Xsts{xerr: i64, message: Option<String>},
                        McLoginHttp(u16), EntitlementsHttp(u16), NotOwned, ProfileHttp(u16), BadAccessToken, XboxProfile(String),
                        Transport(reqwest::Error), Parse(String) }
    impl MsaError { pub fn credentials_rejected(&self) -> bool; }    // NotOwned (+ OAuth invalid_grant if adopted)
    pub struct MsaClient { http: reqwest::Client /* 5 s connect, 10 s read, UA "Minecraft-Authenticator" */ }
    impl MsaClient {
        pub async fn sign_in_with_code(&self, code: &str) -> Result<(User, AuthFile), MsaError>;   // new random client_id
        pub async fn renew(&self, file: &AuthFile) -> Result<(User, AuthFile), MsaError>;         // keeps client_id
    }
}

pub struct RateLimiter { /* Mutex<(last_attempt_ms, )>, AtomicU32 failures */ }
impl RateLimiter { pub async fn acquire(&self, status: &StatusSink); pub fn record_failure(&self); pub fn record_success(&self);
                   pub fn backoff_ms(failures: u32) -> u64; }

pub struct AuthService { /* Arc<AccountManager>, MsaClient, RateLimiter, status callback */ }
impl AuthService {
    pub fn new(paths: &LauncherPaths, config: Arc<dyn ConfigAccess>, network: Arc<dyn NetworkState>) -> Arc<Self>;
    pub fn accounts(&self) -> &Arc<AccountManager>;
    pub async fn login_with_microsoft(&self, code: &str, remember: bool) -> AuthOutcome;   // becomes default
    pub async fn add_account_with_microsoft(&self, code: &str, remember: bool) -> AuthOutcome;
    pub async fn user_for_launch(&self, override_uuid: Option<&str>) -> Result<User, Blocked>;
    pub fn has_existing_login(&self) -> bool;
    pub fn logged_in_user(&self) -> Option<User>;
    pub fn load_cached_user_now(&self) -> Option<User>;
    pub fn renew_existing_login_async(&self) -> BoxFuture<'static, AuthOutcome>;
    pub async fn renew_existing_login(&self) -> AuthOutcome;                 // 120 s cap
    pub fn pending_refresh(&self) -> Option<BoxFuture<'static, AuthOutcome>>;
    pub fn try_preemptive_background_renewal(&self);
    pub fn logout(&self); pub fn archive_and_logout(&self); pub fn switch_to(&self, uuid: &str) -> bool;
    pub fn set_status_callback(&self, cb: Option<Arc<dyn Fn(&str, &str) + Send + Sync>>);
}
pub fn is_renewal_due(last_ms: i64, now_ms: i64, interval_ms: i64) -> bool;
pub fn classify(message: &str) -> AuthOutcome;   // process_auth_exception port, for parity tests

// ---------- mmcl-session ----------
pub enum Phase { Preparing, Running, Exited, Crashed, Cancelled, Failed }
impl Phase { pub fn is_active(self) -> bool; }
pub struct GameSession { /* id, pack: Option<PackHandle>, pack_key, pack_name, account_uuid, account_name, Mutex<Inner>, CancellationToken */ }
impl GameSession {
    pub fn new(pack: Option<PackHandle>, pack_key: String, pack_name: String, account_uuid: Option<String>,
               account_name: Option<String>, clock: Clock) -> Arc<Self>;
    pub fn key_for(pack: &dyn PackIdentity) -> String;
    pub fn id(&self) -> u64; pub fn phase(&self) -> Phase; pub fn exit_code(&self) -> i32;
    pub fn started_ms(&self) -> i64; pub fn ended_ms(&self) -> i64;
    pub fn cancellation(&self) -> CancellationToken;     // replaces bindWorker + interrupt
    pub fn is_cancelled(&self) -> bool; pub fn cancel(&self);
    pub fn stop(&self, force: bool);
    pub fn attach_process(self: &Arc<Self>, child: std::process::Child /* or a ProcessHandle trait for tests */);
    pub fn end_without_game(&self);
    pub fn set_tracker(&self, t: Arc<LaunchProgressTracker>); pub fn tracker(&self) -> Option<Arc<LaunchProgressTracker>>;
    pub fn set_log(&self, log: Arc<GameLog>); pub fn log(&self) -> Option<Arc<GameLog>>;
    pub fn set_crash_report(&self, r: Option<String>); pub fn crash_report(&self) -> Option<String>;
    pub fn add_listener(&self, f: Arc<dyn Fn(&GameSession) + Send + Sync>) -> ListenerId; pub fn remove_listener(&self, id: ListenerId);
}
pub trait ProcessHandle: Send + Sync { fn is_alive(&self) -> bool; fn terminate(&self); fn kill(&self);
                                       fn wait(&self) -> Option<i32>; }  // FakeProcess in tests
pub enum Outcome { Ok, PackAlreadyRunning, AccountBusy, AnotherGameRunning }
pub struct Decision { pub outcome: Outcome, pub conflicting: Option<Arc<GameSession>> }
pub fn check(candidate: &GameSession, others: &[Arc<GameSession>], one_at_a_time: bool) -> Decision;
pub struct GameSessionRegistry { /* Mutex<Vec<Arc<GameSession>>>, listeners, AtomicBool */ }
impl GameSessionRegistry {
    pub fn global() -> &'static Arc<GameSessionRegistry>;
    pub fn set_one_at_a_time(&self, v: bool);
    pub fn try_register(self: &Arc<Self>, s: Arc<GameSession>) -> Decision;
    pub fn preview(&self, candidate: &GameSession) -> Decision;
    pub fn sessions(&self) -> Vec<Arc<GameSession>>; pub fn active(&self) -> Vec<Arc<GameSession>>;
    pub fn has_active(&self) -> bool; pub fn newest_running(&self) -> Option<Arc<GameSession>>;
    pub fn active_for_pack(&self, key: &str) -> Option<Arc<GameSession>>; pub fn is_pack_active(&self, key: &str) -> bool;
    pub fn dismiss(&self, id: u64) -> bool;
    pub fn add_listener(&self, f: Arc<dyn Fn() + Send + Sync>) -> ListenerId; pub fn remove_listener(&self, id: ListenerId);
}
pub trait Sink: Send + Sync { fn show_game(&self, s: &Arc<GameSession>); fn show_no_game(&self); }
pub struct RunningGameFollower { /* Mutex<Option<Weak/Arc<GameSession>>>, sink */ }
impl RunningGameFollower { pub fn pick(s: &[Arc<GameSession>]) -> Option<Arc<GameSession>>;
                           pub fn update(&self, s: &[Arc<GameSession>]); pub fn resync(&self, s: &[Arc<GameSession>]); }
pub struct LatestUpdateRunner { /* mpsc Sender<(u64, Box<dyn FnOnce()+Send>)>, AtomicU64 */ }
impl LatestUpdateRunner { pub fn on_daemon_thread(name: &str) -> Self; pub fn submit(&self, f: impl FnOnce() + Send + 'static); }

pub trait LogListener: Send + Sync { fn on_lines(&self, lines: &[String]); fn on_closed(&self) {} }
pub struct Subscription { pub snapshot: String, pub clipped: bool, cancel: Box<dyn FnOnce() + Send> }
pub struct Tail { pub text: String, pub clipped: bool }
pub struct GameLog { /* ... */ }
impl GameLog {
    pub fn new(file: Option<PathBuf>) -> Arc<Self>;
    pub fn with_limits(file: Option<PathBuf>, trigger: usize, retain: usize, closed_tail: usize) -> Arc<Self>;
    pub fn file_for(dir: &Path, pack_name: Option<&str>, timestamp: &str) -> PathBuf;
    pub fn attach(self: &Arc<Self>, stdout: impl Read + Send + 'static, stderr: impl Read + Send + 'static);
    pub fn file(&self) -> Option<&Path>; pub fn text(&self) -> String; pub fn is_truncated(&self) -> bool;
    pub fn full_text(&self) -> String; pub fn is_closed(&self) -> bool; pub fn await_closed(&self, timeout: Duration) -> bool;
    pub fn subscribe(&self, l: Arc<dyn LogListener>, max_lines: usize /* 0 = all */) -> Subscription;
    pub fn tail(&self, max_lines: usize) -> Tail;
    pub fn add_listener(&self, l: Arc<dyn LogListener>) -> ListenerId; pub fn remove_listener(&self, id: ListenerId);
}
pub fn full_log_drop_offset(buf: &str, trigger: usize, retain: usize) -> usize;
pub fn tail_start(text: &str, max_lines: usize) -> usize;

// ---------- mmcl-crash ----------
pub enum Category { OutOfMemory, JavaVersion, ModLoading, MixinConflict, LoaderConflict, Gpu, Audio,
                    NativeLibrary, WorldCorruption, DiskIo, FileLock, Auth, Unknown }
pub enum Severity { Critical, Warning, Info }
pub enum PackFolder { Root, Mods, CrashReports }
pub enum CrashAction { SetMaxRamMb(u64), OpenSettings, OpenRuntime, OpenPackFolder(PackFolder),
                       ReinstallPack, DisableMod(PathBuf), OpenUrl(String) }
pub struct Suggestion { pub label: String, pub action: Option<CrashAction>, pub primary: bool }
pub struct CrashDiagnosis { pub category: Category, pub severity: Severity, pub title: String,
                            pub summary: String, pub suggestions: Vec<Suggestion> }
pub struct PackInfo<'a> { pub name: Option<&'a str>, pub root: Option<&'a Path>, pub url: Option<&'a str> }
pub struct Env { pub max_ram_mb: u64, pub system_ram_gb: u64 /* 0 = unknown */ }
pub fn analyze(text: Option<&str>, pack: Option<&PackInfo>, exit_code: i32, env: &Env, l10n: &dyn Localizer) -> CrashDiagnosis;
pub fn extract_crash_search_phrase(text: &str) -> Option<String>;
```

---

## 4. External crates

| Need | Crate | Notes |
|---|---|---|
| AES-256-GCM | `aes-gcm` 0.10 | 12-byte nonce, 16-byte tag appended (same layout as JCE) |
| PBKDF2-HMAC-SHA256 | `pbkdf2` 0.12 + `hmac` + `sha2` | `pbkdf2_hmac::<Sha256>(pw, salt, 65536, &mut [0;32])` |
| CSPRNG | `rand` 0.8 (`OsRng`) or `getrandom` | salt, IV, install secret, client UUID |
| Base64 | `base64` 0.22 | `STANDARD` (strict, padded) for files; `URL_SAFE` (padded) for clientId; `URL_SAFE_NO_PAD` + an indifferent-padding config for the JWT payload |
| gzip | `flate2` | AuthenticationFile; also raw-deflate responses if `Accept-Encoding: deflate` is kept |
| JSON | `serde`, `serde_json` | `preserve_order` feature or hand-built `serde_json::Map` to keep member order |
| HTTP | `reqwest` (rustls, gzip, json) | per-request `connect_timeout(5s)`, `timeout(10s)`-ish read; whole chain under `tokio::time::timeout(60s)` |
| Async | `tokio`, `futures` (`Shared`), `tokio-util` (`CancellationToken`) | |
| UUID | `uuid` (v4, `from_u64_pair`) | |
| Hardware UUID | `wmi` (Windows, `Win32_ComputerSystemProduct`), `core-foundation` + `io-kit-sys` (macOS `IOPlatformUUID`), std fs + `std::process::Command` (Linux) | `machine-uid` and `sysinfo` return *different* identifiers (MachineGuid, /etc/machine-id), **not** oshi's; use them only if Java-folder compat is dropped |
| Username | `whoami` | env `USER` / `USERNAME` first, as the Java does |
| OS version for `java_os_name` | `windows` (`RtlGetVersion`) or `os_info` | Windows build to 10/11/Server mapping |
| Windows ACLs | `windows` crate (`Win32_Security_Authorization`) | owner-only DACL |
| Unix perms | std `PermissionsExt` | |
| Regex | `regex` + `once_cell`/`std::sync::LazyLock` | every pattern here is regex-crate compatible |
| URL encode / parse | `url`, `form_urlencoded`, `percent-encoding` | sign-in URL, callback parsing, Google search URL |
| Errors | `thiserror`, `anyhow` | |
| Process | std `process` + `nix` (SIGTERM) on Unix; `windows` for WM_CLOSE on Windows | |
| System RAM | `sysinfo` | OOM suggestion |
| Open folder / URL | `open` | crash suggestions |
| Embedded browser for sign-in | `wry` (+ `tao`) in a helper process | **no GPUI equivalent**, see below |
| Time format | `chrono` (`%Y-%m-%d--%H-%M-%S`, local) | log file name |
| Tests | `tempfile`, `tokio::test`, `proptest` (optional) | |

**No good Rust equivalent:**
- **Embedded WebView for Microsoft sign-in.** GPUI has no web view. The client ID `00000000402b5328` only allows
  the redirect `https://login.live.com/oauth20_desktop.srf`, which a system browser can't hand back to the app
  (no loopback redirect is registered for that client). Options: (a, recommended) a small helper mode of the same
  binary (`mmcl --msa-signin`) that opens a `tao` + `wry` window (WebView2 on Windows, WKWebView on macOS,
  WebKitGTK on Linux), loads `login_url()` with the Firefox UA, watches navigations (`with_navigation_handler`)
  for the redirect prefix, prints the redirect URL on stdout, and exits. A separate process avoids fighting GPUI
  for the main-thread event loop (tao and GPUI both want to own it on macOS, and wry needs GTK on Linux). Each
  sign-in must start with a **fresh, non-persistent web context** (or rely on `prompt=select_account`) so a second
  account can be added. (b) A device-code flow, which needs either a Mojang-approved Azure client ID of our own,
  or the undocumented live.com device flow with the same client ID (`oauth20_connect.srf`, scope
  `service::user.auth.xboxlive.com::MBI_SSL`, a different XBL RpsTicket form). Both are unverified; see the open
  questions. Linux packaging must then depend on `libwebkit2gtk-4.1`.
- **oshi hardware UUID** byte-for-byte: no crate does it; hand-roll the three platform paths in 1.3.
- **Java `os.name`**: hand-rolled mapping.

---

## 5. Tests worth porting

All listed Java tests are pure or use temp dirs and fakes; port them 1:1 as Rust unit tests. The Java uses
hand-rolled fakes, not mocks. Do the same: a `FakeCipher` (prefix `FAKE:` + plaintext; decrypt errors when the
prefix is missing), an `OtherMachineCipher` (encrypt = identity, decrypt always errors), a scripted `Renewer`,
a manually drained executor/spawner, a manual clock, and a `FakeProcess`.

**`AccountStoreTest`** (temp dir + fake cipher): everyFileRoundTrips; credentialsAreEncryptedOnDisk (no plaintext
token in any file); writesLeaveNoTempFilesBehind; listIsMostRecentlyUsedFirst; readsAFolderInTheOldProfileArchiveLayout;
acceptsALegacyUnencryptedGzipAuthFile (bytes starting `1F 8B` returned as-is); acceptsALegacyPlainDecimalRenewalTimestamp;
metadataIsOptionalForListing; corruptMetadataKeepsTheAccount; listSkipsFoldersWithoutCredentialsAndUnsafeNames;
unsafeUuidsCannotEscapeTheStore (`..`, `a/b`, `a\b` refused on write, absent on read, remove false);
credentialsFromAnotherMachineAreRefused; aCachedUserWithoutANameIsTreatedAsMissing; missingFilesReadAsAbsent;
removeDeletesTheWholeFolder. Add for Rust: removeDoesNotFollowSymlinks (Unix).

**`AccountManagerTest`** (fake cipher, scripted renewer, manual executor, manual clock): noAccountsMeansNoDefault;
aRememberedSignInSurvivesARestartAsTheDefault; severalAccountsStaySignedInTogether;
aSessionOnlySignInIsNeverWrittenAndIsGoneAfterARestart; signingInAgainWithoutRememberMeDeletesTheStoredCopy;
aUserWithoutAUsableUuidIsRefused; switchingTheDefaultIsImmediateAndNotifiesListeners;
anUnknownAccountCannotBecomeTheDefault; clearingTheDefaultKeepsEveryAccount;
removingTheDefaultPromotesTheMostRecentlyUsedAccount; resolveDefaultOnlyAcceptsASignedInAccount;
aFreshTokenIsNotRefreshed; aStaleTokenIsRefreshedAndPersisted; oneRefreshPerAccountIsInFlightAtATime;
rejectedCredentialsMeanSignInAgain; aTransientFailureKeepsTheAccountUsable;
aRenewalThatComesBackAsAnotherAccountIsRejected; aSessionOnlyAccountRefreshesFromMemory;
refreshingAnUnknownAccountFails; aRemovedAccountStaysRemovedWhenItsRefreshLands;
migration: theOldActiveAccountMovesIntoTheStoreAsTheDefault; theOldActiveAccountOverwritesAStaleArchivedCopy;
archivedAccountsSitAlongsideTheMigratedDefault; migrationRunsOnce; anUnidentifiedOldLoginIsIdentifiedByItsFirstRenewal;
anOldLoginFromAnotherMachineIsDropped; refreshDefaultWithNoAccountFailsAsLoginExpired.
Add for Rust: aSessionOnlyDefaultPersistsTheMostRecentRememberedAccount (`persist_default`);
missingAuthFileMarksNeedsSignIn.

**`LaunchAccountResolverTest`**: noOverrideUsesTheDefault; aBlankOverrideIsNoOverride; anOverrideWinsOverTheDefault;
anOverrideForAnAccountThatIsGoneBlocksInsteadOfFallingBack; anOverrideAccountThatNeedsSigningInBlocks;
aDefaultThatNeedsSigningInBlocks; aRefreshingAccountCanStillLaunch; noAccountsAtAll.

**`MCLauncherAuthManagerLaunchUserTest`** → `AuthService::user_for_launch` with a scripted renewer:
successfulRefreshLaunchesWithTheNewToken; transientFailureLaunchesWithTheCachedToken;
rejectedCredentialsBlockTheLaunchWithNeedsSignIn. Add: timeoutLaunchesWithTheCachedToken (paused tokio clock).

**`MCLauncherAuthManagerRenewalTest`** → `is_renewal_due`: all nine boundary cases.

**`MCLauncherAuthManagerRenewalTimestampTest`** → `parse_renewal_timestamp`: null/blank → 0; non-Base64 garbage → 0;
legacy decimal; legacy negative decimal; Base64-shaped short number falls back to decimal; encrypted round trip.
The Java runs the real-cipher case in a subprocess with cwd pinned to a temp dir (to isolate `machine-key.bin`).
In Rust, `MachineCipher::new(tempdir)` takes the config dir explicitly, so no subprocess is needed.

**`MCLauncherAuthManagerExceptionClassificationTest`** → `classify(message)`: all 17 cases (substring, case
insensitivity, unrelated message, null/empty message, precedence of not-bought over invalid-credentials).

**`MicrosoftSignInTest`** (gui package, but the logic is here) → `signin::parse_callback`: code; error wins over
code; error_description decoded; `#fragment` stripped; empty code → error; no query → unknown error;
`user_cancelled`.

**New Rust-only protocol tests** (no network; use `wiremock` or `httpmock` with the base URLs injectable):
AuthFile JSON/gzip round trip including negative `most`/`least`; reading a gzip produced by the Java library
(check a fixture into `tests/fixtures/`, using a dummy refresh token); XBL empty-xui error; XSTS `XErr` error;
entitlements-empty → NotOwned; profile 404; JWT xuid extraction with unpadded Base64url; clientId encoding;
authorize URL string equals the exact expected URL in 1.7.

**Cipher compat test** (Rust-only, important if the Java folder will ever be shared): a fixture envelope produced
by the Java `MachineSecretCipher` for a known fingerprint (generated once with a tiny Java harness, using
`assembleFingerprint("u","Linux","unknown",hex(32 zero bytes))`), decrypted by Rust's PBKDF2 + GCM using that
fingerprint string. Plus `assemble_fingerprint` format tests (the Java has `assembleFingerprint`
package-private; a matching `MachineSecretCipherTest` may exist under `utilities/`).

**Sessions**:
- `GameSessionTest` (uses `FakeProcess`): differentPacksOnDifferentAccountsRunSideBySide; aPackCanOnlyRunOnce;
  anAccountCanOnlyPlayOneGame; serverLaunchesHaveNoAccountToCollideOn; endedSessionsDoNotBlock;
  oneAtATimeBlocksAnyOtherActiveGame; theSpecificConflictIsReportedEvenInOneAtATimeMode; aSessionRunsThenExits;
  aNonZeroExitIsACrash; aLaunchThatNeverStartsFailsOrIsCancelled; endWithoutGameLeavesARunningGameAlone;
  cancellingOneLaunchLeavesAnotherAlone; cancelDoesNothingOnceTheGameIsRunning; stopAsksAndKillForces;
  listenersHearEveryPhaseChange; theRegistryAdmitsAtomically; anEndedGameFreesItsPackAndAccount;
  onlyEndedSessionsCanBeDismissed; oldEndedSessionsArePruned; listenersHearRegistrationsAndPhaseChanges.
  `FakeProcess`: alive until `finish(code)`; records destroy vs destroyForcibly; `onExit` completes on finish.
  In Rust that is the `ProcessHandle` trait.
- `GameLogTest` (in-memory pipes, temp files, small limits): capturesBothStreamsIntoMemoryAndTheFile;
  tokensAreRedactedBeforeTheyAreStoredOrShown; listenersGetBatchesAndHearTheEnd;
  aLateSubscriberMissesNothingAndSeesNothingTwice; aCappedSubscriberGetsOnlyTheTailThenEveryLaterLine;
  theInMemoryBufferIsBounded; anEndedLogKeepsOnlyItsTailButServesTheFullTextFromTheFile;
  fullTextOfAVeryLongSessionIsCappedLikeTheLiveBuffer; withoutAFileAnEndedLogKeepsEverything;
  anUnwritableFileKeepsTheWholeEndedLogInMemory; logFileNamesAreSafe; anUnwritableFileStillCapturesInMemory.
  Add: crLfAndLoneCrSplitLines; invalidUtf8IsReplaced.
- `LatestUpdateRunnerTest`: appliesUpdatesInOrderWhenEachRunsBeforeTheNext; skipsAnUpdateAlreadyReplaced;
  newestUpdateLandsLastEvenWhenAnEarlierOneIsSlow.
- `RunningGameFollowerTest`: followsTheMostRecentlyStartedGame; theFirstGameExitingDoesNotClearTheOthers;
  whenTheShownGameExitsTheOtherTakesOver; repeatedUpdatesDoNothing; resyncShowsTheFollowedGameAgain;
  resyncWithNoGameRunningDoesNothing; preparingGamesAreNotShownAndNothingResetsBeforeAnyGameRan.
- `LogTrimPolicyTest` (gui package; port the `tail_start` / `full_log_drop_offset` cases with the session crate).

**Crash** (`CrashReportAnalyzerTest`, 30 cases): one per detector (OOM with "Java heap space"; class file 61 →
"Java 17"; disk full; UnsatisfiedLinkError; mixin with `mixins.<mod>.json` named in the summary; Forge dep
`Mod 'JEI' (jei) requires patchouli [1.20.1,) loaded`; NoClassDefFoundError `com/example/SomeApi` →
`com.example.SomeApi`; GLFW 65540; RegionFileException; "Could not authenticate against ... Invalid session ID";
audio is Warning; file lock; OptiFine + Sodium, and OptiFine alone does not trigger; MC-version
`compiled for Minecraft 1.20.1 but is being loaded by Minecraft 1.20.4`; JEI+REI dual; duplicate recipe id),
the precedence cases (OOM over mod-loading; MC-version over mod-loading; OptiFine+Sodium over mixin), the
unknown cases (empty text; exit code 42 in the summary; unrelated NPE → Unknown), null-text safety, the
search-phrase cases (FQN + message; type only; 120-char truncation; None without an exception), and
unmatchedCrashGetsWebSearchSuggestion. The Java asserts on English text through `LocalizationManager`. In Rust,
assert on `Category`, `Severity`, the localization keys and their arguments (have `analyze` take a `Localizer`;
a test localizer returns `key(args)`), plus a few English-text checks through the real bundle.

---

## 6. Edge cases and bug-guards (checklist)

1. MAC address must never be in the cipher fingerprint (macOS randomization; ~90% of macOS launches failed).
2. The install secret is always mixed in (2026.6); there is no legacy-fingerprint fallback, despite the doc.
3. `prompt=select_account` on the sign-in URL, or a second account can't be added.
4. Owner-only on the directory before writing credentials into it (Windows ACL inheritance); owner-only on the
   temp file before the rename.
5. A uuid that isn't `[0-9a-fA-F-]{1,64}` is refused as a path segment.
6. A session-only sign-in deletes any stored copy, so it can't be brought back on restart.
7. A renewal returning a different uuid counts as credentials rejected.
8. A refresh landing for a removed (or removed and re-added) session persists nothing (pointer-identity check on
   `pending`).
9. Transient refresh failures keep the account `Ready`; only rejected credentials flip it to `NeedsSignIn`.
10. A pack override never silently falls back to the default.
11. Log only exception **type names** from the auth library layer, never messages (token fragments).
12. Game output is drained for the game's whole life, independent of any window; a failed log write never blocks
    the pipe; an ended log trims to a 256 KiB tail only when the file is complete.
13. The subscriber snapshot and the line numbering are taken under one lock: nothing missed, nothing repeated.
14. Follower updates go on one ordered thread with "latest wins"; the snapshot is taken inside the follower lock.
15. A launch cancelled after the JVM spawned kills the JVM.
16. Reinstall, verify and uninstall check `is_pack_active` first.
17. Admission check-and-add is one atomic step.
18. A backwards clock means "not due" (no re-login loop).
19. The crash report must be younger than 60 s to be attached to a session.
20. Startup: a failed *synchronous* renewal (cache unreadable) currently signs the default account out even on a
    transient failure. Faithful port, or soften it (open question).
