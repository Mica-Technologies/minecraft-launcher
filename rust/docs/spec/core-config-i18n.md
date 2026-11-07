# Porting spec: core lifecycle, config, paths, logging, i18n, security scan

Slice owner: core / config / i18n. Target: Rust Cargo workspace under `rust/` (GPUI front end).

Java sources covered (read in full unless noted):

| Java file | Lines |
|---|---|
| `LauncherCore.java` | 2064 |
| `LauncherSession.java` | 362 |
| `LauncherArgs.java` | 153 |
| `config/` (`AppConfig`, `AuthTokenStore`, `ConfigManager`, `ConfigStore`, `GameModeManager`, `ModPackConfig`, `NetworkConfig`, `RgbConfig`, `RuntimeConfig`) | ~3,960 |
| `consts/` (`AuthConstants`, `ConfigConstants`, `ForgeConstants`, `GUIConstants`, `LauncherConstants`, `LocalPathConstants`, `ManifestConstants`, `ModPackConstants`, `RuntimeConstants`, `UpdateCheckConstants`) | ~2,650 |
| `consts/localization/` (`LocaleBootstrap`, `LocalizationManager`, `SupportedLocales`) | ~1,170 |
| `files/` (`LocalPathManager`, `Logger`, `SynchronizedFileManager`). `RuntimeManager` is out of scope. | ~850 |
| `exceptions/` (`LauncherException`, `ModpackException`, `ModpackScanDetectionException`) | ~290 |
| `security/` (`ScanExclusionPolicy`, `SupplementalScanner`) | ~1,280 |
| Vendored `me/cortex/jarscanner/` (`Detector`, `Main`, `Results`, `Constants`). This is the "Nekodetector" Fractureiser scanner that `security/` sits beside, so it is specified here. | ~870 |
| Helpers read for context, owned by the utilities spec: `SensitiveDataRedactor`, `FilePermissions`, `StartupDiagnostics`, `JvmArgsValidator`, `SingleInstanceLock` (enum and paths only), `JSONUtilities`/`FileUtilities` (Gson config), `ScanFrequency`, `GameModPack.scanModPackRootFolder` | — |
| Tests listed in §16 | ~3,300 |

The Rust launcher uses **its own data folder for now** (§4.4). The folder layout below must match the Java one exactly so that switching to the Java folder later only means changing one constant.

---

## 1. What this slice does

1. **Process entry and lifecycle.** `main()` handles the pre-session modes (`--mcp` stdio relay, `--diag-manifest`), the single-instance lock and the hand-off to an already-running copy. It then runs a **restart loop**: each iteration builds a `LauncherSession` that parses args, fixes the game mode, loads config, applies the locale, configures logging, signs in, loads packs, shows the main UI (or runs the server) and then blocks until exit or restart is signalled. It also covers close, in-process restart, out-of-process relaunch, and "reset launcher" (delete the data folder, then relaunch). All of these share a guarded cleanup sequence.
2. **Game mode.** `CLIENT` or `SERVER`. The mode selects the data root: client is `~/.<AppNameTrimmed>`, server is the current working directory. Reading config before the mode is set is the **early-path trap** (§3.2).
3. **Paths.** Every launcher folder and file is derived from that root.
4. **Config.** One JSON document, `config/configuration.json`. Reads use type-tolerant coercion. Writes are debounced (500 ms), atomic (tmp file, fsync, rename) and owner-only. Corrupt files are moved aside. A versioned migration runs on load (schema v7).
5. **Logging.** One log file per session. Console and file get the same lines (tee). Every line goes through the redactor. Files rotate (10 MB, 3 backups). A 5 s flush timer runs. Error-level calls can show GUI dialogs, and warnings show as toasts.
6. **i18n.** Java `.properties` bundles in `src/main/resources/lang/` (English source plus 16 locales). Lookup by key, a `MessageFormat` subset with `choice`, startup locale resolution (config override, then OS, then `en-US`).
7. **Security scan before launch.** Two passes over the pack folder. (a) The Nekodetector bytecode signature scan for Fractureiser stage 1, plus a stage 2 filesystem IoC check. (b) The supplemental heuristic scanner: embedded executables, natives in odd places, webhook and paste-host URLs, launcher credential file references, IPv4 literals, clipboard stealer patterns. Manifest-supplied exclusions go through a policy gate first. Findings render into a user-facing block message.

### The hardest parts to port

- **The `.properties` and `MessageFormat` semantics.** This includes Java's escape grammar (`\uXXXX`, `\ ` for a leading space, `\n`), the bundle fallback chain (the `zh` script and region mapping, `pt_BR` file naming), apostrophe auto-escaping, `{n,choice,...}`, locale-aware number formatting of numeric args, and the exact fallback strings for missing keys and malformed patterns. 41 `choice` patterns exist across the bundles, 5 of them in English.
- **A JVM class-file and bytecode reader** for the two scanners. The Detector's signature matching has odd but load-bearing semantics. Rust has no ASM equivalent, but a small purpose-built parser is enough.
- **Config concurrency and durability.** Debounced single-writer, write ordering, interrupt-safe flush before relaunch, flush-before-lock-release on exit. Two Java bugs to fix are covered in §7.9.
- **The lifecycle thread rules.** Never run cleanup on the UI thread (this deadlocks on macOS with AppKit). Never run it on a background-pool worker (cleanup shuts the pool down and interrupts itself). Bound the vendor-SDK cleanup steps with timeouts.

---

## 2. Constants

### 2.1 Build identity (`LauncherConstants`)

| Constant | Java value / derivation | Rust |
|---|---|---|
| `LAUNCHER_IS_DEV` | `true` if the jar manifest has no `Implementation-Version` (IDE run), or if the manifest has `Launcher-Environment: DEV` (set by Maven `-Pdev`, `launcher.environment=DEV`). | `cfg!(debug_assertions)` OR the build-time env `MMCL_ENV=DEV` (via `build.rs` / `option_env!`). |
| `LAUNCHER_APPLICATION_NAME` | The manifest `Implementation-Title`. Release: `"Mica Minecraft Launcher"` (the pom `<name>`). Dev: the title plus `" DEV"` if it does not already contain "DEV" (case-insensitive). With no title (IDE run): `"Mica Minecraft Launcher DEV"`. | Same strings. |
| `LAUNCHER_APPLICATION_NAME_TRIMMED` | The name with **spaces removed**: `MicaMinecraftLauncher` or `MicaMinecraftLauncherDEV`. Used for the data folder and log file names. | Same. |
| `LAUNCHER_APPLICATION_VERSION` | The manifest `Implementation-Version`. Otherwise `git describe --tags --dirty=.dirty --always`, run in the repo root found by walking up from the code location to a `.git` dir or file, with a 2 s timeout and the first line trimmed. Otherwise `"0.0.0-dev"`. | `build.rs` runs the same `git describe` at build time. Fall back to `CARGO_PKG_VERSION`, then `"0.0.0-dev"`. Never shell out at runtime. |
| `PROGRAM_ARG_CLIENT_MODE` | `"-c"` | |
| `PROGRAM_ARG_SERVER_MODE` | `"-s"` | |
| `PROGRAM_ARG_DIAG_MANIFEST` | `"--diag-manifest"` | |
| `PROGRAM_ARG_MCP` | `"--mcp"` | |
| `SETTINGS_MIN_RAM_MIN` / `SETTINGS_MIN_RAM_MAX` | `0.2` / `32.0` (GB, UI slider bounds) | |
| `SETTINGS_MAX_RAM_MIN` / `SETTINGS_MAX_RAM_MAX` | `1.0` / `64.0` (GB) | |
| `EXIT_STATUS_CODE_GOOD` | `0` | |
| `SINGLE_INSTANCE_PORT` / `SINGLE_INSTANCE_PORT_DEV` | `47821` / `47822` (loopback) | See §4.4: the Rust build needs its own ports while it has its own folder. |
| `MCP_SERVER_PORT` / `MCP_SERVER_PORT_DEV` | `47823` / `47824` | Same caveat. |
| `JVM_PROPERTIES` | `prism.lcdtext=false`, `prism.text=t2k`, `prism.forceUploadingPainter=true`, `sun.net.http.allowRestrictedHeaders=true`. All are JavaFX or JDK-HTTP specific. | **Drop.** Not applicable to GPUI. |

`applySystemProperties()` also does three things:
- **macOS:** sets `prism.order=mtl,es2,sw`. JavaFX-only, so drop it.
- **Windows:** sets the AppUserModelID to `com.micatechnologies.minecraft.launcher.LauncherCore`, plus the suffix `DEV` in dev builds. **Keep the exact string** so taskbar pins and jump lists stay compatible. Call `SetCurrentProcessExplicitAppUserModelID` (`windows` crate) before creating any window. On failure, log a silent error.
- Installs the macOS app-menu handlers (GUI spec) and spawns `SchemeRegistrar::register_if_needed` (registers `mmcl://` and `.mmcjson`) on a background task. That belongs to the platform-integration spec.

Startup also sets `http.maxConnections=32` (JDK HTTP keep-alive pool). In Rust, configure the shared `reqwest::Client` with `pool_max_idle_per_host(32)`.

Two more process-level steps run before any native DLL load:
- **Windows:** remove the CWD from the DLL search order with `SetDllDirectoryW("")` / `SetDefaultDllDirectories(LOAD_LIBRARY_SEARCH_DEFAULT_DIRS)`.
- **Windows:** opt into Per-Monitor-V2 DPI awareness before any window exists, with `SetProcessDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2)`. GPUI may already do this; verify. An application manifest is preferred.

### 2.2 URLs and other constants used across subsystems

| Constant | Value |
|---|---|
| `ModPackConstants.AVAILABLE_PACKS_MANIFEST_URL` | `https://micauseaststorage.blob.core.windows.net/mc-launcher-api/installable.json` (list key `"available"`) |
| `ModPackConstants.MINECRAFT_VERSION_MANIFEST_URL` | `https://piston-meta.mojang.com/mc/game/version_manifest_v2.json` |
| `ManifestConstants.MINECRAFT_ASSET_SERVER_URL_TEMPLATE` | `https://resources.download.minecraft.net/folder/hash`. The literal tokens `folder` and `hash` are replaced by the first 2 hex chars of the hash and the full hash. |
| `RuntimeConstants.MOJANG_RUNTIME_INDEX_URL` | `https://launchermeta.mojang.com/v1/products/java-runtime/2ec0cc96c44e5a76b9c8b7c39df7210883d12871/all.json` |
| `UpdateCheckConstants.UPDATE_CHECK_API_URL` | `https://api.github.com/repos/Mica-Technologies/minecraft-launcher/releases/latest` (keys `html_url`, `tag_name`) |
| `GUIConstants.URL_MINECRAFT_USER_ICONS` | `https://minotar.net/armor/bust/{user}/100.png` (replace `{user}`) |
| `GUIConstants.URL_MINECRAFT_NO_MOD_PACK_IMAGE` | `https://cdn.pixabay.com/photo/2016/11/11/14/49/minecraft-1816996_960_720.png` |
| `GUIConstants.TITLE_SPLIT_CHAR` | `" | "` |
| `ModPackConstants.MODPACK_FILE_EXTENSION` / `_GLOB` / `_DESCRIPTION` | `.mmcjson` / `*.mmcjson` / `Mica Minecraft Modpack` |
| `ModPackConstants.MOD_LOADER_*` | `forge` (default), `neoforge`, `fabric` (lowercase, matched case-insensitively) |
| `ModPackConstants.MODPACK_FRIENDLY_NAME_TEMPLATE` | `"%s: %s"` (name, version) |
| `ModPackConstants.PLATFORM_*` | `windows`, `osx`, `linux` (Mojang rule OS names) |
| Pack-relative paths | `bin/modpack.jar` (Forge jar), `bin/fabric-profile.json`, `bin/minecraft.jar`, `mods`, `libraries`, `config`, `bin/natives`, `resourcepacks`, `shaderpacks`, `assets`, `bin/logo.png`, `bin/background.png` |
| `MODPACK_DEFAULT_BG_URL` / `MODPACK_DEFAULT_LOGO_URL` | Bundled resources `/default_background.png` (falls back to `/micaminecraftlauncher.png`) and `/micaminecraftlauncher.png`. In Rust, embed them with `include_bytes!`. |
| `RuntimeConstants` | `DEFAULT_RUNTIME_COMPONENT="jre-legacy"`, `DEFAULT_JAVA_MAJOR_VERSION=8`, `MOJANG_RUNTIME_INDEX_FILE_NAME="mojang-runtime-index.json"`, `RUNTIME_MANIFEST_FILE_NAME="runtime-manifest.json"`, `RUNTIME_VERSION_FILE_NAME=".version"`, java exe at `bin/java.exe` (Windows), `jre.bundle/Contents/Home/bin/java` (macOS), `bin/java` (Linux). See the runtime spec. |
| `RuntimeConstants.getMojangPlatformKey()` | Windows: `windows-arm64` if `os.arch` contains `aarch64` or `arm`, else `windows-x64`. macOS: `mac-os-arm64` or `mac-os`. Linux: `linux-i386` if arch contains `"86"` or `"32"`, **else `linux`**. Quirk: `amd64` contains neither, but `x86_64` contains "86", so a JVM reporting `x86_64` would pick `linux-i386`. Linux JVMs report `amd64`. In Rust, map from `std::env::consts::ARCH` explicitly: `x86_64` → `linux`, `x86` → `linux-i386`. |
| `ForgeConstants` | JSON key names for the Forge `version.json`: `id`, `inheritsFrom`, `minecraftArguments`, `mainClass`, `libraries`, `name`, `url`, `downloads`, `artifact`, `path`, `sha1`, `serverreq`, `clientreq`. Forge jar version file: `version.json`. |
| `ManifestConstants` | `objects`, `hash`, `libraries`, `downloads`, `name`, `artifact`, `.json`. |
| `AuthConstants` | Empty class. Drop it. |

---

## 3. Game mode

### 3.1 `GameModeManager`

- State: `currentGameMode: GameMode?`, initially `null`. All methods are `synchronized`.
- `inferGameMode()`: `SERVER` if `GraphicsEnvironment.isHeadless()`, else `CLIENT`. Logs `"Game mode inferred and set to: <NAME>"` (hardcoded English, deliberately: localizing here would class-load the bundle too early).
- `setCurrentGameMode(m)`: sets and logs a debug line, `"Game mode being set to: <NAME>."`.
- `isClient()` / `isServer()`: compare against the enum. **When the mode is unset, both return false.**
- `GameMode.getStringName()` returns `"CLIENT"` or `"SERVER"`.

Rust: `enum GameMode { Client, Server }` with `as_str()` returning `"CLIENT"` / `"SERVER"`. Keep it in a `OnceLock`-style global cell plus an `RwLock` for the rare re-set. **Headless detection** in Rust:
- Linux: neither `DISPLAY` nor `WAYLAND_DISPLAY` is set.
- Windows: always graphical, unless running as a service or session 0, which can be ignored.
- macOS: always graphical. Optionally check `SSH_CONNECTION` without a window server, but parity says "not headless".

### 3.2 The early-path trap (must be designed out)

In Java, `LocalPathManager.getLauncherLocalPath()` returns the **client** root only when `isClient()` is true. Before `parseLauncherArgs` runs, the mode is `null`, `isClient()` is false, and every path resolves to the **server root, which is the CWD**. This has caused three shipped bugs:

1. Any `ConfigManager` getter called before the mode was set loaded `<cwd>/config/configuration.json`, or created an empty default there. That locked the in-memory JSON to a stale document. Later writes went to the right folder while reads kept the stale data, so every config change made in the previous session was lost. The fix was to call nothing config-related from `main()`, and to have `Logger.logDebug` consult config only after `enableConfigBackedDebugLogging()`.
2. The single-instance IPC token was written from `main()` before the mode was set, so it landed under CWD. The forwarding process (spawned by the OS scheme handler with a different CWD) never found it. The fix was `LocalPathManager.getClientConfigFolderPath()`, which is always the client root.
3. `RgbIntegration.bootstrap()` read config from `main()`. It was moved after the arg parse.

**Rust design rule:** there is no lazily-loaded global config. `ConfigStore::open(path)` takes an explicit path and is called only after the mode is fixed. `LauncherPaths` is a value built from an explicit `GameMode`. Code that must run before the mode is known (single-instance token, `startup.log`) uses `LauncherPaths::client()` explicitly. The global accessor `paths()` returns `Option` or panics with a clear message in debug builds if the mode is unset. Make the bug impossible to write rather than documenting it.

---

## 4. Paths (`LocalPathConstants`, `LocalPathManager`)

### 4.1 Roots

| Mode | Root |
|---|---|
| Client | `<user.home>/.<LAUNCHER_APPLICATION_NAME_TRIMMED>`. Release `~/.MicaMinecraftLauncher`, dev `~/.MicaMinecraftLauncherDEV`. **The same on every OS:** Windows `C:\Users\<u>\.MicaMinecraftLauncher`, macOS `/Users/<u>/.MicaMinecraftLauncher`, Linux `/home/<u>/.MicaMinecraftLauncher`. Not XDG and not `%APPDATA%`: Java uses `System.getProperty("user.home")`. |
| Server | The absolute CWD at **class-load time** (`Paths.get("").toAbsolutePath()`). |

For `user.home` in Rust, use `dirs::home_dir()`. Java uses `%USERPROFILE%` on Windows, which `dirs` also does through `SHGetKnownFolderPath(FOLDERID_Profile)`. Snapshot the server CWD once at startup.

### 4.2 Layout (all relative to the root)

Paths owned by this slice:

```
<root>/
  config/                         getLauncherConfigFolderPath()
    configuration.json            ConfigConstants.CONFIG_FILE_NAME  (+ .tmp during write, .corrupt-yyyyMMdd-HHmmss when quarantined)
    client.mica                   getClientTokenFilePath()          (legacy, auth spec)
    player.mica                   getRememberedAccountFilePath()    (legacy, auth spec)
    update.mica                   getUpdateInfoFilePath()           (update-check spec)
    version.manifest              getMinecraftVersionManifestFilePath()  (".manifest" extension)
    single-instance.token         SingleInstanceLock (always via client config path)
    machine-key.bin               MachineSecretCipher install secret (auth spec)
  metadata/                       getLauncherMetadataFolderPath()   (e.g. log4j-configs/, <sha1>.png image cache)
  installs/                       getLauncherModpackFolderPath()    (one folder per pack; see modpack spec)
  logs/                           getLauncherLogFolderPath()
    MicaMinecraftLauncher_CLIENT_2026-11-07--14-03-22.log   (+ .1 .2 .3 rotations)
    startup.log (+ startup.log.1) StartupDiagnostics (always the CLIENT root, 256 KiB roll)
    <per-game GameLog files>      game/session spec: GameLog.fileFor(logsDir, packName, "yyyy-MM-dd--HH-mm-ss")
  runtime/                        getLauncherRuntimeFolderPath()    (runtime spec)
  shared/assets/                  getLauncherSharedAssetsFolderPath()  (indexes/, objects/, virtual/)
```

Other subsystems add more under the same root: the auth `profiles/<uuid>/` and `auth/cached_user.json`, `modpacks/manifest_cache/` (keyed by `sha256(url)`), `mcp-endpoint.json`, crash-reports inside pack folders, and backups. Their specs own those paths. The Rust `LauncherPaths` should expose `root()` so they compose from it, and should not hard-code new top-level names without updating this table.

Pack-relative asset folders (`LocalPathConstants`): `assets`, `assets/indexes`, `assets/objects`, `assets/virtual` (legacy pre-1.7 flat tree per index id), `resources` (1.6 `map_to_resources`). Library manifest file name: `libraries.manifest`. Pack bin folder: `bin`. Jar extension `.jar`, log extension `.log`.

Log file name: `<APP_NAME_TRIMMED>_<GAME_MODE>_<yyyy-MM-dd--HH-mm-ss>.log`, in local time (`SimpleDateFormat`, default time zone). The Rust `chrono` format is `%Y-%m-%d--%H-%M-%S`.

### 4.3 Path string composition

Java concatenates strings with `File.separator`, and its constants start with a separator (for example `CONFIG_FOLDER = "\config"`). In Rust, use `PathBuf::join` with plain segment names. The results are the same.

### 4.4 Rust-specific data folder (decision needed; see open questions)

The task requires the Rust port to have **its own folder for now, with the identical layout**. Proposal:

```rust
pub const DATA_DIR_NAME_RELEASE: &str = ".MicaMinecraftLauncherRS";    // later: ".MicaMinecraftLauncher"
pub const DATA_DIR_NAME_DEV:     &str = ".MicaMinecraftLauncherRSDEV"; // later: ".MicaMinecraftLauncherDEV"
```

The log file prefix keeps using `APP_NAME_TRIMMED`. Also allow an env override, `MMCL_DATA_DIR`, for tests and side-by-side runs.

**Ports:** while the folders differ, the Rust build **must not** use 47821 to 47824. Otherwise a Rust launch would find the Java launcher holding the port, read a token from the wrong folder (`NO_TOKEN`), and refuse to start, or it would hand its deep link to the Java launcher. Proposal: release ports 47831 (single instance) and 47833 (MCP); dev ports 47832 and 47834. Switch back to the Java ports at the same time as the folder.

---

## 5. Command-line arguments

### 5.1 Pre-session forms, handled in `main()` before the lock

Order of checks in `main()`:

1. `StartupDiagnostics.installUncaughtExceptionHandler()`. Every uncaught exception goes to `startup.log` and stderr. In Rust, install a panic hook that does the same.
2. Unless `args[0]` equals `--mcp` (case-insensitive): `StartupDiagnostics.recordLaunch(args)` writes `Start <version>[ (dev)] on <os.name> <os.version>, Java <ver>, <n> argument(s)`. Rust replaces `Java <ver>` with `rustc <ver>` or drops it.
3. Set the HTTP pool, DLL search hardening and DPI awareness (§2.1).
4. **TUI detection:** any arg equal to `--cli` or `--tui` (case-**sensitive**) enables TUI mode. The real stdout and stdin are captured **before** the logger replaces the console streams. In Rust, keep the original `Stdout` handle for the TUI and make the logger file-only when the TUI is enabled.
5. `--diag-manifest <url>`: only when `args.length == 2` and `args[0]` equals `--diag-manifest` (case-insensitive). Sets CLIENT mode, fetches the pack with `GameModPackFetcher.get(url, false)`, resolves the library manifest twice, prints the `[manifest-diag] ...` lines, and exits with 0 (memoized), 1 (not memoized) or 2 (fetch failed or error). Developer diagnostic; port it as an optional `--diag-manifest` in the modpack crate.
6. `--mcp` as `args[0]` (case-insensitive, any trailing args): MCP stdio relay. `System.exit(StdioProxy.run(McpEndpointFile.defaultPath(), stdin, stdout, stderr))`. **stdout is protocol-only**, so the logger is never configured on this path. MCP spec.
7. **Single instance:** `SingleInstanceLock.tryAcquire()` binds a loopback `ServerSocket` on the port. If it fails, call `handleSecondLaunch(args)` and exit:
   - Pick the first arg that is a launcher URI (`mmcl://`, case-insensitive prefix match).
   - TUI with no URI: record to `startup.log`, print `tui.alreadyRunning` to the real stdout, exit 0.
   - Otherwise forward the URI, or `mmcl://open`, to the running instance. Result enum: `DELIVERED`, `DELIVERED_NO_WINDOW`, `UNCONFIRMED`, `NO_TOKEN`, `UNREACHABLE`, `REJECTED`. `delivered()` is true for `DELIVERED` and `UNCONFIRMED`. If delivered, exit 0.
   - Else show the message, chosen like this: `DELIVERED_NO_WINDOW` → `startup.alreadyRunning.noWindow`; `REJECTED` → `startup.alreadyRunning.unreachable`; anything else → `startup.portUnavailable` with `{0}` = port **as a String** (no digit grouping) and `{1}` = the bind failure message. Show it in an **always-on-top** native dialog, or on stderr if TUI or headless. Exit 1. In Rust, use `rfd::MessageDialog` with the `Warning` level. `rfd` cannot force always-on-top on every platform, so note that as a gap.

   Note: in this path `LocalizationManager` loads with the **OS default locale**. No config is read; that is acceptable.
8. Early GUI-toolkit prestart. "Likely client" means no arg equals `-s` or `--server` (case-sensitive here) and TUI is off. Java starts the JavaFX toolkit on a daemon thread to overlap its about 270 ms startup. Not needed for GPUI. In Rust, the GPUI `App` must own the main thread, so the session logic moves off it (§6.6).
9. The restart loop (§6).

### 5.2 Session argument grammar (`LauncherArgs.parse(args, tuiEnabled)`), a pure function

Output: `{ mode_action: Client|Server|Infer|None, modpack_selection: String ("" = none), pending_uri: Option<String>, invalid: bool }`.

Precedence:

1. If **any** arg is a launcher URI (`mmcl://`, case-insensitive prefix): `(Client, "", Some(arg), false)`. Only the first one counts.
2. If TUI is enabled: `Client`, and the selection is the **last** token that is not one of `--cli`, `--tui` (case-sensitive) or `-c`/`-s` (case-insensitive). Never invalid.
3. Otherwise, by argument count:

| argv | result |
|---|---|
| `[]` (or null) | `(Infer, "", None, false)` |
| `[-c]` (case-insensitive) | `(Client, "")` |
| `[-s]` | `(Server, "")` |
| `[name]` | `(None, name)`: **sets no mode at all** (historical, deliberate; see §17) |
| `[-c, name]` | `(Client, name)` |
| `[-s, name]` | `(Server, name)` |
| anything else (two bare names, three or more args) | `(None, "", None, true)` |

`parseLauncherArgs` applies the result:
- Stash the pending URI.
- Apply the mode (`Infer` → `inferGameMode()`, `None` → nothing).
- If `invalid`, log the error `INVALID_ARGS_SPECIFIED_TEXT + "\n" + USAGE_TEXT + ": launcher.jar [ -s [modpack_name] | -c [modpack_name] | modpack_name ]"` and call `closeApp()`. In Rust, print the binary name instead of `launcher.jar`.
- Return the selection.

Note that `--server` is **not** an accepted session flag; only `-s` is. Only the early "likely client" check knows about `--server`.

---

## 6. Startup sequence and lifecycle

### 6.1 The restart loop (`main`)

```
static restartFlag = true; restartError = null; currentSession; lifecycleTransition = AtomicBoolean(false)
while restartFlag:
    previousRestartError = restartError; restartFlag = false; restartError = null
    lifecycleTransition = false                 // accept close/restart requests again
    if !SingleInstanceLock.tryAcquire():         // idempotent; re-binds after cleanup released it
        logWarningSilent("Could not re-acquire single-instance lock after restart; ...")   // non-fatal
    currentSession = new LauncherSession(args, previousRestartError)
    currentSession.run()                         // blocks on exitLatch
```

`args` is the **original argv** on every iteration. That means a restarted session re-applies the original modpack auto-launch and re-dispatches nothing, because the pending URI was consumed. Note: after a restart, `doModpackSelection(initialName)` auto-launches the CLI-named pack **again**. That is probably unintended. See §17.

### 6.2 `LauncherSession.run()`: the exact order

1. `ColdStartProfiler.mark("session_start")`. The profiler records named timestamps; port it as an optional `tracing` span or event.
2. `initial = parseLauncherArgs(args)`. **The game mode is fixed here.**
3. `Logger.enableConfigBackedDebugLogging()`. This reads `debug` from config, which is the **first config load**, and caches it in a volatile.
4. `RgbIntegration.bootstrap()`. Reads config. RGB spec.
5. `localeOverride = ConfigManager.getLocaleOverride()`, then `LocaleBootstrap.apply(localeOverride)`, then `LocalizationManager.setLocale(LocaleBootstrap.resolve(localeOverride))`. See §10.5. In Rust, a single `i18n::init(resolve(override))`.
6. `applySystemProperties()` (§2.1).
7. `configureLogger()`: opens `logs/<APP>_<MODE>_<ts>.log` (§8). Until this point, log lines go only to the original console.
8. **TUI path:**
   - If no saved login: print `tui.auth.noAccount` and `closeApp()`.
   - Otherwise `loadCachedUserNow()`, `renewExistingLoginAsync()`, `fetchInstalledModPacks(null)`, `startAvailableModPacksFetchAsync()` and `TuiApp.run()`.
   - If the TUI throws, log it and print `tui.error.generic` with `{0}` = the throwable's string.
   - Then `closeApp()` and return.
9. Client mode: the Java FX prestart thread prebuilds the window and the main GUI. In GPUI, open the window early and show a loading state.
10. Debug log `log.launcherSession.loggingConfigured`, then each arg on its own debug line prefixed with two spaces. In a dev build, also log `log.launcherSession.devModeNotice`.
11. `restoreRunningGamesAfterRestart()`: if game sessions are still active (they survive an in-process restart), reopen the Running Games window and resync the RGB and Discord follower. Game-session spec.
12. **Client:** debug `LAUNCHER_CLIENT_MODE_STARTING_LOGIN_TEXT`, then `performClientLogin(previousRestartError)` (§6.3), then debug `LOGIN_PROCESS_FINISHED_TEXT`. **Server:** debug `LAUNCHER_NOT_CLIENT_MODE_SKIPPING_LOGIN_TEXT`.
13. `ColdStartProfiler.mark("auth_done")`.
14. Async `NetworkUtilities.checkNetworkAvailability()`. Never block on it. The old blocking 5 s TCP probe to `launchermeta.mojang.com:443` was removed.
15. `AnnouncementManager.startCheckAsync()` and `MCLauncherAuthManager.tryPreemptiveBackgroundRenewal()`, both fire-and-forget.
16. `GameModPackManager.fetchInstalledModPacks(null, isServer())`. **Server mode blocks on the revalidation**, so a headless start always launches the latest manifest. Client mode loads cache first and revalidates in the background.
17. Set the background-error listener so failures surface as a `notification.session.backgroundTaskFailed.title` toast.
18. `startAvailableModPacksFetchAsync()`.
19. `McpBootstrap.startIfEnabled()`. Binds only if `mcpServerEnabled`. Failures are logged and swallowed. GUI path only.
20. `prefetchAvailableModpackBackgrounds()` (GUI).
21. `doModpackSelection(initial, previousRestartError)` (§6.4).
22. Drain the pending `mmcl://` URI and hand it to `LauncherUriHandler.handle(uri)`. Drain the pending `.mmcjson` files (queued by the macOS open-file handler during cold start), then `spawnNewTask(dispatchMmcjsonImport(f))` for each.
23. `exitLatch.await()`. On interrupt, log `log.launcherSession.mainThreadInterrupted`.

Pending-URI slot: a single volatile string. Later URIs **overwrite** the slot (last one wins). Pending files: a synchronized list, drained atomically.

### 6.3 `performClientLogin(initialError)`

- If `hasExistingLogin()`:
  - `cached = loadCachedUserNow()`. If not null: log `log.launcherCore.cachedSessionLoaded` with `{0}` = name, call `renewExistingLoginAsync()` and return. This is the fast path: the UI paints with the cached user and the refresh runs in parallel. Play waits on it.
  - Otherwise log `log.launcherCore.cachedUserUnreadable`. Show the progress UI with `auth.progress.signingIn` / `auth.progress.checkingSession`. Install a status callback that updates the section and detail text, then call the blocking `renewExistingLogin()` and clear the callback. On success, log `"[<name>] " + WAS_LOGGED_IN_TO_LAUNCHER_TEXT` and return. On failure, log `log.launcherCore.savedAccountNotRenewed`, call `logout()`, and if `initialError` is empty set it to `AUTH_UNABLE_TO_REFRESH_TEXT`.
- Otherwise log `REMEMBERED_ACCOUNT_NOT_FOUND_SHOWING_LOGIN`.
- Show the login UI. If `initialError` is non-empty, `logError(initialError)`, which shows a dialog. Block until login succeeds (`waitForLoginSuccess`). If the wait is interrupted or the UI fails to load, log and `closeApp()`.

### 6.4 `doModpackSelection(name, initialError)`

- `pack = installedModPackByName(name)`. If `name` is non-empty and not installed, `logError(name + " " + PACK_NOT_INSTALLED_WILL_DEFAULT_TO_FIRST_TEXT)`.
- **Client:**
  - If `initialError` is non-empty, `logError` it.
  - If `pack` exists (desktop-shortcut auto-launch): `setLastModPackSelected(pack.packName)`, then on a background task set the Discord game presence and call `play(pack, after = { goToMainGui(); requestFocus() })`. If that fails, log `log.launcherCore.mainGuiAfterAutoLaunchFailed` and `closeApp()`. Return.
  - Otherwise `goToMainGui()`. On failure log `log.launcherCore.mainGuiLoadFailed`.
- **Server:**
  - If `initialError` is non-empty, `logErrorSilent` it.
  - If no pack: `logError(NO_MOD_PACKS_INSTALLED_CANT_LAUNCH_SERVER_TEXT)`.
  - Otherwise run the crash-restart loop: `maxRestarts = 3`. For each attempt:
    - If `restartCount > 0`, call `fetchInstalledModPacks(null, true)` (blocking re-check of the manifest; log `log.launcherCore.restartRefreshFailed` on error).
    - Re-resolve by name. Use the refreshed pack only if it is not `isFailedLoad()` and not `isStub()`. If it exists but is broken, log `log.launcherCore.restartPackNotLoaded` and keep the last good pack.
    - Log `startingServer` or `startingServerRestart {count,max}` and `launchingPackVersion {name,version}`, then `play(pack)`, then `proc = pack.getLastLaunchedProcess()`.
    - `exit = proc.waitFor()`. The child inherits the launcher's stdio (`ChildIoMode.INHERIT`), so there is no draining. Log `serverExited {exit}`.
    - Exit 0 stops. Non-zero with `restartCount < 3`: increment, log `serverCrashedRestarting`, sleep **5 s**, repeat. Otherwise log `serverCrashedGivingUp {3}` and stop.
    - If the process is null, stop.
  - Finally `closeApp()`.

### 6.5 `play(pack, after, forcedAccountUuid)`: launch orchestration (cross-ref to the game-session and launch specs)

This logic lives in LauncherCore today. The Rust port should move it into the session or launch crate and keep the behaviour:

1. Client mode: `resolveLaunchUser`. The override is the forced account (MCP) or the config `accountOverrideByPack[pack.settingsKey]`. `userForLaunch(override)` returns a blocked result in these cases:
   - `OVERRIDE_MISSING`: refuse if headless, if there is no default user, or if the account was forced. Otherwise ask the question `launch.account.overrideMissing.*` (button 1: `useDefault {defaultName}`). On yes, clear the override in config and retry with null.
   - `NEEDS_SIGN_IN`: ask `launch.account.needsSignIn.*`. On yes, open `AddAccountDialog`. Refuse either way.
   - Anything else: `launch.account.none` error.
2. Client mode with a GUI: `ModConflictDetector.scan(pack)`. On conflicts, show a three-button dialog:
   - Text: title "Mod conflict detected", header "These mods don't get along", body lines `• title\n  description` separated by blank lines.
   - Buttons: "Disable {firstJar}" → `disableJar` (rename to `.jar.disabled`); on failure warn `notification.launch.disableModFailed.*` and abort. "Launch anyway" continues. Cancel aborts.
   - **These English strings are hardcoded in Java. Localize them in Rust** (new keys).
3. Build a `GameSession(pack, keyFor(pack), friendlyName, uuid, name, clock)`, bind it to the worker thread (for cancellation), and call `ensureRunningGameFollower()`. Then `tryRegister`; if refused, `reportLaunchRefused`. That shows the running game for `PACK_ALREADY_RUNNING`, or warns `launch.refused.packRunning|accountBusy|anotherGame` and refocuses.
4. RAM gate: if `pack.minRamGb > maxRAM/1024.0`, log the error `"[<friendly>] " + REQUIRES_MIN_OF_TEXT + " " + minRam + " " + GB_OF_RAM_TEXT + ". " + MAX_RAM_SETTING_MUST_INCREASE_TEXT` and stop.
5. Build the step tracker:
   - Non-vanilla packs: `MODPACK_CONTENT`, `FORGE_LIBS`.
   - Always: `MC_LIBS_ASSETS`, `JRE_INSTALL`.
   - `FORGE_PROCESSORS` only if `usesPostInstallSteps()`.
   - Always: `SECURITY_SCAN`.

   With a GUI, show the session tab and push taskbar progress. Headless, attach the throttled log listener: state changes always log (FAILED via `logErrorSilent log.launch.headless.stepFailed {label,err}`, otherwise `stepState {label,state}`), and sub-text detail logs at most once per step per **3000 ms** (`stepDetail`, with CAS on the last timestamp).
6. "Ready" toast: when every step is terminal (DONE, FAILED or SKIPPED), fire once. Show it only if more than 10 s have elapsed or the launcher is not focused (`notification.launch.ready.*`, or `bodyNamed` when a friendly name exists). End the taskbar progress.
7. Set the download scope to the session, plus retry and progress listeners that write sub-text on running steps. Call `pack.startGame(user, session::isCancelled)`. In `finally`, remove the listeners, clear the scope and swap the progress provider to null.
8. If the session was cancelled after spawn, kill the process forcibly and log `launchCancelledAfterSpawn`.
9. Otherwise `saveInstalledVersion()`, `recordLaunchStart()`, refresh the jump list in the background. With a GUI, attach a `GameLog` at `logs/` (`fileFor(packName, yyyy-MM-dd--HH-mm-ss)`). Call `session.attachProcess(p)`. On exit, `onGameExited`: record the session duration; if the exit code is non-zero (or -1 when unknown), log the error `gameCrashedExitCode`, notify `notification.launch.gameCrashed.*`, attach the crash report and show the session. If `!inGameConsole`, hide the Running Games window unless something is preparing.
10. Errors:
    - `ModpackScanDetectionException`: `logErrorMultiline(msg)` (keeps bullets) plus the throwable.
    - Other exceptions: if cancelled, log `launchCancelled`; otherwise `logError(UNABLE_START_GAME_EXCEPTION_TEXT)` plus the throwable.
    - Either way, end the taskbar progress.
11. Call `after` only if the session was not cancelled. `finally`: clear the thread interrupt flag, then `session.endWithoutGame()`, which is a no-op if a process is attached.

### 6.6 Close, restart, relaunch, reset

All four are guarded by `lifecycleTransition.compareAndSet(false, true)`. A second request logs a debug line `log.launcherCore.lifecycleTransitionIgnored` and returns. The guard resets at the top of each loop iteration.

**Thread rule** (`runLifecycleTransition`): if the caller is the **UI thread** or a **background-pool worker**, the work runs on a new **non-daemon** thread (`Launcher-Close` / `Launcher-Restart`). Otherwise it runs inline.
- The UI-thread rule exists because, on macOS, the UI thread is the AppKit main thread, and cleanup's dispatch-sync calls to AppKit deadlock there.
- The pool-worker rule exists because cleanup calls `shutdownNow` on the pool, which interrupts the worker running it and aborts its interruptible config write.

`relaunchApp` and `resetLauncherAndRelaunch` **always** use a dedicated thread.

In Rust with GPUI, the main thread runs the GPUI event loop. Run lifecycle transitions on a dedicated `std::thread`. Dispatch UI teardown (closing windows) to the foreground executor and wait on a channel with a timeout. Exiting the process after cleanup: call `cx.quit()` from the foreground, then `std::process::exit(0)` once the run loop returns.

- `closeApp()` → `closeAppNow`: `cleanupApp()`, count down the latch, `logStd(SEE_YOU_SOON_TEXT)`, `exit(0)`.
- `restartApp()` / `restartAppWithError(err)`: `restartFlag = true`, `restartError = err`, then `cleanupApp()` and count down the latch. The loop iterates. An in-process restart keeps running game processes and their sessions alive.
- `relaunchApp()` (used for a language change in Java, because the static-final strings bind at class load; **Rust has no such constraint**, see §10.6):
  - On the dedicated thread: `ConfigManager.flushPendingWrite()` first, which must be durable because the child reads the locale from disk.
  - Find the installed exe: Java uses the `jpackage.app-path` system property; in Rust use `std::env::current_exe()` only for installed builds (§17).
  - With no exe: log `relaunchUnavailable` and do an in-process restart.
  - Otherwise log `relaunchingProcess {exe}`, run `cleanupApp()`, then spawn. Exit 0 on success. On failure, log the error `relaunchSpawnFailedFallback`, set `restartFlag = true` and count down the latch.
- Spawn (`spawnRelaunchProcess`):
  - Windows: first `cmd /c start "" <exe>`, with the CWD set to the exe's directory, so the child is a real top-level process. Spawning as a console child left the custom title bar half-applied (a double title bar). Second option: `Desktop.open(exe)` (ShellExecute).
  - Everywhere else, or if both fail: spawn the process directly, detached, with stdout and stderr discarded and the CWD set to the exe directory.

  In Rust on Windows, prefer `ShellExecuteW(NULL, "open", exe, NULL, dir, SW_SHOWNORMAL)` via the `windows` crate, with `cmd /c start` as the fallback. On Unix, `Command::new(exe).current_dir(dir).stdin(null).stdout(null).stderr(null)`, plus `setsid` through `pre_exec` (or `CommandExt::process_group(0)`) so the child is detached.
- `resetLauncherAndRelaunch()` (Settings refuses this while a game is running):
  1. `cleanupApp()`.
  2. `root = paths.root().absolute().normalize()`.
  3. `moveAsideForReset(root, now_ms)`: if the root does not exist, return null. Otherwise **rename** it to the sibling `<name>.reset-<stamp>`. This is atomic: on Windows a file still in use makes the rename fail with nothing touched.
  4. If the rename fails: `restartFlag = true`, `restartError = format("settings.resetLauncher.failed", e)`, count down the latch (in-process restart; safe because nothing was deleted).
  5. Delete the renamed folder recursively. On failure, `logWarningSilent resetLeftover {path}`; this is harmless.
  6. If an exe is found and the spawn succeeds, exit 0. Otherwise show the warning `settings.resetLauncher.startAgain` and exit 0. **Never** restart in-process after deleting: the in-memory config and accounts would be written back.

### 6.7 `cleanupApp()`: order is load-bearing

Each step runs isolated. Any panic or error is logged as `log.launcherCore.cleanupStepFailed {name, err}` and the next step still runs. Steps marked *bounded* run on a fresh daemon thread with a **3000 ms** wait; on timeout, log `cleanupStepTimedOut {name, 3000}` and move on, leaving that thread behind.

1. `logStd(PERFORMING_APP_CLEANUP_TEXT)`
2. *bounded* `rgb`: `RgbIntegration.shutdown()`. Backends paint their final black frame. An OpenRGB socket write can hang, which is why this step is bounded.
3. *bounded* `mcp`: `McpBootstrap.stop()`. Must delete `mcp-endpoint.json`. A stale file would point clients at a port this process no longer owns, carrying a bearer token.
4. *bounded* `discord`: `DiscordRpcUtility.exit()`
5. `taskbar`: release the taskbar progress COM wrapper **before** the GUI closes (a stale HWND lookup can block).
6. `notifications`: remove the tray icon (Windows keeps a ghost icon otherwise).
7. `gui`: close all windows.
8. `helpWindow`: tear down the singleton. Must run after `gui`.
9. `backgroundTasks`: `shutdownBackgroundExecutor(2000)`, a bounded drain of `spawnNewTask` work. The pool is **rebuilt lazily** on the next use, so restarted sessions work.
10. `config`: `ConfigManager.flushPendingWrite()`. **Must run before** the lock release.
11. `instanceLock`: `SingleInstanceLock.release()`. Only after the config is on disk, so a quick relaunch never reads a half-written config.
12. `logging`: `Logger.shutdownLogSys()`
13. `logStd(FINISHED_APP_CLEANUP_TEXT)`. This goes to the original console only.

---

## 7. Config

### 7.1 File format

- Path: `<root>/config/configuration.json`, UTF-8.
- **Written compact**, with no pretty printing, using Gson defaults. Gson escapes HTML characters by default (`<`, `>`, `&`, `=`, `'` are written as `\u003c` and so on). Java's reader accepts either form, so the Rust writer may write them unescaped. The JSON stays valid, and the Java launcher reads it back fine.
- Keep key insertion order with `serde_json` and `preserve_order`. Gson's `JsonObject` is a `LinkedTreeMap` that keeps insertion order.
- Numbers: `addProperty(long)` writes `2048`. A `double` writes `1234.0` or `2.5`. **NaN or Infinity makes Gson's writer throw**, which drops the whole write (it is logged as `CONFIG_SAVE_ERROR_TEXT`). `setWindowBounds` could in principle pass NaN. In Rust, serde_json writes non-finite floats as `null`. Treat `null` as absent on read, which matches the Java `isAbsent` rule. This is strictly better.
- **Reader leniency:** Gson's `JsonParser` is lenient. It accepts `//` comments, single quotes, unquoted keys and trailing commas in some cases. With strict `serde_json`, a hand-edited lenient file would be quarantined as corrupt. Recommendation: parse with `serde_json` first, then fall back to `json5`, and only after both fail treat the file as corrupt.

### 7.2 Load (`ConfigStore.loadFromDisk`), run once on first access

```
file = config_dir/configuration.json
if file is a regular file:
    parse → JsonObject
    zero bytes / not an object / parse error  ⇒ corrupt
    else log debug "log.configStore.loadFromDisk" {size, packCount (modpacks array length, or -1), path}
if corrupt and the file exists:
    rename to "configuration.json.corrupt-yyyyMMdd-HHmmss" (REPLACE_EXISTING); log std "log.configStore.preservedCorrupt" {dst}
      (on failure: warnSilent "log.configStore.preserveCorruptFailed" {msg})
    logErrorAsync(CONFIG_EXISTS_CORRUPT_RESET_ERROR_TEXT); logThrowable(parseError)
if not read (missing or corrupt):
    json = { "configVersion": 7 }
    populateFirstRunDefaults():  minRAM=512, maxRAM=2048, debug=false, discordRpc=true,
                                 resizableWindows=true, modpacks=[]   (each schedules a write)
    logStd(CONFIG_RESET_SUCCESS_TEXT)
migrateConfigIfNeeded()
```

### 7.3 Migration (`migrateConfigIfNeeded`)

- `stored = configVersion` (0 if absent). This uses `getAsInt` **without the coercion guard**, so a non-numeric `configVersion` throws. In Rust, use the coercing read with default 0.
- If `stored >= 7`, return.
- Log `log.configManager.migrating {stored, 7}`.
- `stored < 5`: if `resizableWindows` is present and false, set it to true (log `migrateV5ResizeWindows`).
- `stored < 6`: for `modpacks[]` string entries and `lastModPack`, if the value **starts with** `https://micauseaststorage.blob.core.windows.net/mc-launcher-api/` **and ends with** `.json`, replace that `.json` suffix with `.mmcjson`. Non-string elements are kept as-is. Third-party URLs are untouched. The manifest cache files keyed by `sha256(url)` become orphans, which is intentional. If anything changed, log `migrateV6Urls {count}`.
- `stored < 7`: `migrateDefaultJvmArgs`. If `jvmArgs` is a JSON **string** whose `trim()` equals `JVM_ARGS_VALUE_DEFAULT_PRE_2026_10` exactly, replace it with `JVM_ARGS_VALUE_DEFAULT` (log `migrateV7JvmArgs`). It is idempotent, and a non-string or absent value is left alone.
- Then "touch every key" by calling these getters, so that `getOrInit` writes missing defaults: `minRAM`, `maxRAM`, `debug`, `discordRpc`, `resizableWindows`, `enhancedLogging`, `jvmArgs`, `lastModPack`, `theme`, `modpacks`, `inGameConsole`, `proxyEnable`, `proxyHost`, `proxyPort`, `proxyType`, `vanillaVersions`, `lwjglArmPatch`, `discordInvites`.
- Set `configVersion = 7` and schedule a write.

**Rust migrations must produce byte-for-byte equivalent JSON values**, so the Java launcher can read a Rust-written config and the reverse. Bump `CONFIG_VERSION` in lockstep with Java if either side adds a migration. A Rust-only key is fine: Java ignores unknown keys, and so must Rust.

### 7.4 Typed accessors and coercion (`ConfigStore`)

Two read families, both under the store lock:

- `get_x(key, default)`: return `default` if the key is absent or `null`. Does **not** write.
- `get_or_init_x(key, default)`: if the key is **absent** (`!has(key)`; note that a JSON `null` counts as present here), store `default` and schedule a write, then return the coerced value.

Coercion has to mirror Gson's `getAs*`, because the tests pin it:

| Stored JSON | `getAsString` | `getAsBoolean` | `getAsInt` / `getAsLong` | `getAsDouble` |
|---|---|---|---|---|
| string `"abc"` | `"abc"` | `"abc".equalsIgnoreCase("true")`, so **never throws** (any other string is false) | parse; failure → **default plus warning** | parse; failure → default plus warning |
| string `"42"` | `"42"` | false | `42` | `42.0` |
| number `42` / `2.5` | `"42"` / `"2.5"` | `Boolean.parseBoolean("42")`, which is false | `42` / `2` (truncates) | `42.0` / `2.5` |
| bool `true` | `"true"` | `true` | throws → default plus warning | throws → default plus warning |
| `[x]` (exactly one element) | unwraps to `x` and coerces recursively (pinned by a test) | unwrap | unwrap | unwrap |
| `[]` or `[a, b]` | throws → default plus warning | same | same | same |
| `{...}` | throws → default plus warning | same | same | same |

Warning text (silent, hardcoded English): `Config key '<key>' does not hold a valid <type>; using default (<fallback>). Correct or delete the entry in configuration.json.`. **A failed read never rewrites the stored value.**

Large-number nuance: Gson's `getAsInt` on `9000000000` gives `Number.intValue()`, which truncates to a garbage int. Rust should range-check, then warn and use the default. That is a deliberate improvement.

`mutate(f)` applies `f` to the live document under the lock. `snapshot()` returns a deep copy. `set_json(obj)` replaces the document (import). `peek()` returns the document only if it is loaded.

Java threading history: the setters mutated the live object under **their own** class monitors, so the writer serialized a deep copy taken with retries (up to 8 on `ConcurrentModificationException`). **Rust: one `parking_lot::Mutex<Map>`; every access goes through it.** That removes this whole class of race.

### 7.5 Writes: debounce, atomic write, ordering, durability

- `schedule_write()`: cancel any pending timer and schedule `write_now` for **500 ms** later. A burst of writes coalesces into one. Runs on a dedicated daemon thread, `mica-config-write`.
- `flush_now()`: claim and cancel the pending write under the config lock, then (outside that lock) call `write_now` **only if a write was pending**. See bug §7.9(1).
- `write_now()` holds `WRITE_LOCK`. Lock order is **WRITE_LOCK, then the config lock**, never the reverse.
  1. Under the config lock, take a snapshot. If nothing is loaded, `logErrorAsync(CONFIG_NOT_LOADED_CANT_SAVE_ERROR_TEXT)`. Because the snapshot is taken inside WRITE_LOCK, writes land in snapshot order.
  2. Serialize to UTF-8 and log debug `log.configStore.writeNow {bytes, packCount, path}`.
  3. `create_dir_all(parent)`.
  4. Write `configuration.json.tmp` with create, truncate and write, then **fsync** (`sync_all`).
  5. Apply **owner-only permissions to the tmp file before the rename**, so the real path is never world-readable. This matters because the config holds the MCP bearer token and proxy settings.
  6. Rename the tmp file over the target atomically, replacing it. If atomic move is unsupported (FAT32, old SMB), log the warning `log.configStore.atomicMoveUnsupported` and do a plain replace.
  7. On any other error: `logErrorAsync(CONFIG_SAVE_ERROR_TEXT)` plus the throwable, and delete the tmp file.
- **Interrupt safety (Java-specific):** a `FileChannel` write fails if the thread's interrupt flag is set, which once lost the locale change on relaunch. Java clears the flag, retries once and restores the flag. Rust threads have no interrupts, so this concern disappears. Keep the rule that **cleanup and relaunch flush on a thread that is not being torn down**.
- **Shutdown hook:** the JVM hook calls `flushNow`. In Rust, there is no hook for `std::process::exit`, so every exit path must call `config.flush()` explicitly. `cleanupApp` does this. Also flush in the panic hook, best-effort with `try_lock`, and on SIGTERM/SIGINT (`ctrlc` or `signal-hook`) and Windows console-close events.
- `logErrorAsync` exists because `logError` blocks on a modal dialog that needs the UI thread, which might be waiting on the config lock. That froze the launcher for good. **In Rust: never show a blocking dialog while holding a config lock.** Error reporting from the config crate goes through a non-blocking callback or channel.

Atomic replace on Windows: `std::fs::rename` uses `MoveFileExW(MOVEFILE_REPLACE_EXISTING)`, which is atomic on NTFS for files on the same volume. Use the `tempfile` crate's `NamedTempFile::persist`, or a manual write-and-rename. The tmp name must be `configuration.json.tmp` for parity; this is not essential.

Owner-only permissions (`FilePermissions.applyOwnerOnly`):
- POSIX: `0600`, or `0700` for a directory.
- Elsewhere: a single ACL entry, ALLOW owner with all permissions, which **replaces** inherited ACEs.
- Failures are logged as `log.filePermissions.posixTightenFailed` / `aclTightenFailed` and ignored.
- In Rust on Windows: `SetNamedSecurityInfoW` with a DACL holding one ACE for the owner SID and `PROTECTED_DACL_SECURITY_INFORMATION` (`windows` crate). This is the utilities spec's job, but config and the logger depend on it.

### 7.6 Every config key

Getter kinds: **I** = get-or-init (persists the default on first read), **G** = plain get (no write), **S** = special. Setters write the value and schedule a write unless noted. *Facade* means the validation lives in `ConfigManager`.

**Schema / runtime**

| Key | Type | Default | Kind | Notes |
|---|---|---|---|---|
| `configVersion` | int | `7` | S | Schema version. |
| `minRAM` | long (MB) | `512` | I | `-Xms`. |
| `maxRAM` | long (MB) | `2048` | I | `-Xmx`. `getMaxRamInGb = maxRAM / 1024.0`. |
| `jvmArgs` | string | `JVM_ARGS_VALUE_DEFAULT` (below) | I | *Facade:* the read returns the default if `!JvmArgsValidator.isClean` (logs `log.configManager.jvmArgsValidationFailed`). The write calls `requireClean`, which errors on any char `< 0x20`, on `0x7F`, or on `${`. `null` is stored as `""`. The setter calls `flushNow` instead of scheduling (bug §7.9(1)). |

**Pack list and selection**

| Key | Type | Default | Kind | Notes |
|---|---|---|---|---|
| `lastModPack` | string | `""` | I | Despite the doc ("manifest URL"), the CLI auto-launch stores `packName`. Treat it as an opaque string. |
| `modpacks` | string[] | `[]` | S | When absent, the read inserts `[]` **without** scheduling a write. Installed manifest URLs, in order. |
| `vanillaVersions` | string[] | `[]` | S | Same as `modpacks`. Vanilla version ids. |

**Appearance and locale**

| Key | Type | Default | Kind | Notes |
|---|---|---|---|---|
| `theme` | string | `"Native (Mica)"` | I | Allowed values: `Automatic`, `Dark`, `Light`, `Blue+gray`, `Orange+purple`, `Creeper`, `Native (Mica)`. Not validated on read here; the GUI validates. |
| `localeOverride` | string | `""` | G | BCP-47 tag. Blank means OS detection. **Never default-written.** A `null` set is stored as `""`. |
| `wavyProgress` | bool | `true` | I | |
| `reduceMotion` | bool | `false` | I | |
| `packColors` | bool | `true` | I | |
| `uiScalePercent` | int | `100` | I | UI offers 50, 75, 100, 125 and 150. |
| `showPackBackgrounds` | bool | `true` | G | Wrong type → default. |
| `imageCycleInterval` | string | `"30s"` | G | Must be one of `5s 15s 30s 1m 5m 15m 30m 1h 6h 12h 1d 7d never`, else the default. The setter stores without validating. |
| `imageCycleShuffle` | bool | `false` | G | |

**Logging**

| Key | Type | Default | Kind | Notes |
|---|---|---|---|---|
| `debug` | bool | `false` | I | Read as `IS_DEV OR value`. The setter also updates the logger's cached flag. |
| `enhancedLogging` | bool | `true` | I | |

**Discord and windows**

| Key | Type | Default | Kind | Notes |
|---|---|---|---|---|
| `discordRpc` | bool | `true` | I | |
| `discordInvites` | bool | `true` | I | |
| `resizableWindows` | bool | `true` | I | v5 migration. |
| `inGameConsole` | bool | `false` | I | "Show console on launch". |
| `runningGamesDocked` | bool | `false` | I | |
| `runningGamesDockHeight` | int (permille) | `420` | I | |
| `consoleLogMaxLines` | int | `10000` | G | Set clamps to 0 or more; 0 means unlimited. Presets: 1000, 5000, 10000, 25000, 50000, 0. |
| `windowX`, `windowY`, `windowWidth`, `windowHeight` | double | NaN | G | Written together by `setWindowBounds`. |
| `windowMaximized` | bool | `false` | G | |

**App lifecycle and MCP**

| Key | Type | Default | Kind | Notes |
|---|---|---|---|---|
| `launcherUpdateCheck` | bool | `true` | I | |
| `uriHandlerEnabled` | bool | `true` | I | When false, neither argv nor IPC URIs are dispatched. |
| `mcpServerEnabled` | bool | `false` | I | |
| `mcpAutoApproveReadOnly` | bool | `true` | I | |
| `mcpToolPolicies` | string (JSON object as a string) | `""` | I | `{tool: ALWAYS_ALLOW\|ASK\|DISABLED}`. Unreadable means no policies, which **fails closed**. |
| `mcpAllowStateChanges` | bool | `false` | I | |
| `mcpPort` | int | `47823` (dev `47824`) | I | The setter replaces values outside `1..=65535` with the default. |
| `mcpToken` | string | `""` | I | Bearer token. This is why the config is owner-only. |
| `defaultAccountUuid` | string | `""` | I | |
| `quickStartCompleted` | bool | `false` | I | |
| `batteryThrottleEnable` | bool | `true` | I | Related constants: `BATTERY_THROTTLE_BYTES_PER_SEC = 524288`, `BATTERY_THROTTLE_PCT_THRESHOLD = 30`. |
| `lwjglArmPatch` | bool | `true` | I | |

**RGB**

| Key | Type | Default | Kind | Notes |
|---|---|---|---|---|
| `rgbEnable` | bool | `false` | I | |
| `rgbBackend` | string | `"auto"` | I | Read and write accept only `auto`, `openrgb`, `chroma`, `chroma-native`, `windows-dl` and `none`; anything else becomes `auto`. **Quirk:** `corsair` and `asus-aura` constants exist but are **not** in the accepted set (§17). |
| `rgbUsePackColors` | bool | `true` | I | |
| `rgbHighlightKeys` | bool | `true` | I | |
| `rgbEnableOpenRGB` | bool | `true` | I | |
| `rgbEnableChromaNative` | bool | `true` | I | |
| `rgbEnableChromaRest` | bool | `false` | I | |
| `rgbEnableWindowsDL` | bool | `true` | I | |
| `rgbEnableCorsair` | bool | `true` | I | |
| `rgbEnableAsusAura` | bool | `true` | I | |
| `rgbMenuEffectEnable` | bool | `true` | I | |
| `rgbEffectStyle` | string | `"breathe"` | I | One of `solid`, `breathe`, `pulse`, `cycle`, `rainbow`; otherwise the default. The setter **ignores** invalid values. |

**Proxy**

| Key | Type | Default | Kind | Notes |
|---|---|---|---|---|
| `proxyEnable` | bool | `false` | I | |
| `proxyHost` | string | `""` | I | `null` is stored as `""`. |
| `proxyPort` | int | `8080` | I | Not range-checked. |
| `proxyType` | string | `"HTTP"` | I | `HTTP` or `SOCKS`. `null` is stored as `HTTP`. Not validated otherwise. |

**Backups**

| Key | Type | Default | Kind | Notes |
|---|---|---|---|---|
| `backupAutoBeforeUpdate` | bool | `true` | G | |
| `backupMaxCountPerPack` | int | `3` | G | Set clamps to 0 or more; 0 means no count cap. |
| `backupMaxAgeDays` | int | `14` | G | Set clamps to 0 or more; 0 means no age cap. |
| `backupIncludeSaves` | bool | `false` | G | |

**Per-pack maps and secrets**

| Key | Type | Default | Kind | Notes |
|---|---|---|---|---|
| `alwaysVerifyOnLaunchByPack` | object `{packUrl: bool}` | per pack `false` | S | A blank URL returns `false`, and setting a blank URL is a no-op. A malformed value logs a silent warning `log.modPackConfig.alwaysVerifyReadFailed {url}` and returns false. **Java bug:** the setter does `getAsJsonObject()` on a non-object and throws. Rust: replace a malformed map. |
| `defaultScanFrequency` | string, an enum name | `DAILY` (`ScanFrequency.DEFAULT`) | S | Values: `EVERY_TIME`, `DAILY`, `ON_CHANGES_ONLY`, `DISABLED`. Unknown or absent gives `DAILY`. A `null` set stores `DAILY`. |
| `scanFrequencyByPack` | object `{packUrl: enumName}` | absent means use the default | S | Unknown name or blank gives `None`, which deliberately falls back to the global value. Malformed logs `log.modPackConfig.scanFrequencyReadFailed`. Setting `None` removes the entry. `effective(url) = override ?: default`. |
| `accountOverrideByPack` | object `{packSettingsKey: uuid}` | absent means use the default account | S | Blank key → None or no-op. Missing, `null`, blank or malformed → None. Setting None or blank removes the entry. Setting over a non-object section **replaces** it (this one is guarded). |
| `curseForgeApiKey` | string, a Base64 AES-256-GCM machine-bound envelope | absent | S | Read: absent, blank or non-string → None; decrypt failure → None plus the warning `log.authTokenStore.cfKeyDecryptFailed {ExcClass}`. Set: null or blank **removes** the key, otherwise stores the encrypted value; both call `flushNow` (bug §7.9(1)). Encrypt failure → warning `cfKeyEncryptFailed`, nothing stored. `has` = non-blank string present. Cipher format: `salt[16] \| iv[12] \| ct+tag`, PBKDF2-HMAC-SHA256 with 65536 iterations from a machine fingerprint (auth spec). |

`JVM_ARGS_VALUE_DEFAULT` (exact, single spaces):

```
-XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200 -XX:+UnlockExperimentalVMOptions -XX:+ExplicitGCInvokesConcurrent -XX:+AlwaysPreTouch -XX:G1NewSizePercent=30 -XX:G1MaxNewSizePercent=40 -XX:G1HeapRegionSize=16M -XX:G1ReservePercent=20 -XX:G1HeapWastePercent=5 -XX:G1MixedGCCountTarget=4 -XX:InitiatingHeapOccupancyPercent=15 -XX:G1MixedGCLiveThresholdPercent=90 -XX:G1RSetUpdatingPauseTimePercent=5 -XX:SurvivorRatio=32 -XX:+PerfDisableSharedMem -XX:MaxTenuringThreshold=1 -Dusing.aikars.flags=https://mcflags.emc.gs -Daikars.new.flags=true
```

`JVM_ARGS_VALUE_DEFAULT_PRE_2026_10` is the same string with `-XX:+DisableExplicitGC` in place of `-XX:+ExplicitGCInvokesConcurrent`. It is used only as the v7 migration target. **Never hand it to a JVM:** it leaks direct buffers and the game dies with `OutOfMemoryError: Direct buffer memory`.

JVM presets, as `(name, args)`, in order:

| Name | Args |
|---|---|
| `Performance (Aikar's Flags)` | the default above |
| `Low Memory` | `-XX:+UseG1GC -XX:MaxGCPauseMillis=50 -XX:G1HeapRegionSize=1M` |
| `Debug` | `-XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200 -ea -verbose:gc -Xlog:gc*:file=gc.log:time,uptime,level,tags` |
| `None` | `""` |

The preset names are user-visible; localize them in Rust and keep an internal id.

### 7.7 Import and export

- `exportConfig(dest)`: write the snapshot as **compact** JSON. The Javadoc says "pretty-printed", but the code uses `objectToString`, which is compact. On error, log the silent error `log.configManager.exportFailed {msg}` and return false. Rust may pretty-print; that is harmless.
- `importConfig(src)`: parse, replace the document, migrate, then **schedule** a write (not a flush). On error, log `importFailed` and return false. Rust: flush immediately after import, because a crash within 500 ms would lose the import.

### 7.8 Facade

`ConfigManager` is a thin static facade over the slices. In Rust, use a single `Config` struct with typed methods, generated by a macro from a key table so defaults live in one place:

```rust
config_keys! {
    min_ram:  i64  = "minRAM"   => 512,  init;
    debug:    bool = "debug"    => false, init;
    locale_override: String = "localeOverride" => "", plain;
    ...
}
```

### 7.9 Java bugs to fix, not replicate

1. **`flushNow()` without a pending write is a no-op.** `RuntimeConfig.setCustomJvmArgs` and `AuthTokenStore.setCurseForgeApiKey` mutate the document and then call `flushNow()` **without** `scheduleWrite()`. The change is only persisted if some other write happened to be pending; otherwise it waits for the next unrelated write. In Rust, `set_durable()` = mutate, mark dirty, then write synchronously.
2. `alwaysVerifyOnLaunchByPack` and `scanFrequencyByPack` setters throw on a malformed (non-object) section. Replace the section instead.
3. Import schedules rather than flushes (§7.7).
4. `configVersion` is read without coercion (§7.3).
5. `getAsInt` truncates out-of-range longs (§7.4).

---

## 8. Logger (`files/Logger`)

### 8.1 Line format

`[<LAUNCHER_APPLICATION_NAME>/<LEVEL>] <redacted message>`, where LEVEL is one of `ERROR`, `WARN`, `STD` or `DEBUG`. Example: `[Mica Minecraft Launcher/STD] Logging subsystem has been initialized.`. The prefixes are **hardcoded ASCII, never localized**. Java did this to avoid loading the bundle too early; keep it anyway, because log lines must be greppable. There is no timestamp in the line.

- **stdout:** `STD` and `DEBUG`.
- **stderr:** `ERROR`, `WARN` and `logThrowable` (the full stack trace, redacted).

Rust `logThrowable`: print the error and its `source()` chain, plus the backtrace if one was captured (`anyhow::Error` with `{:?}`).

### 8.2 Methods

| Java | Behaviour |
|---|---|
| `logStd(s)` | Write to stdout. |
| `logDebug(s)` | Dev build: always print. Otherwise print only once `configBackedDebugReady` is set **and** the cached `debugLoggingEnabled` is true. Before ready, debug lines are **dropped**. |
| `logErrorSilent(s)` / `logWarningSilent(s)` | Write to stderr only. |
| `logWarningSilent(prefix, throwable)` | Null throwable → `prefix`. Empty prefix → `Class: msg`. Otherwise `log.logger.operationFailed {prefix, Class}` or `operationFailedMessage {prefix, Class, msg}`. |
| `logError(s)` | If a GUI window is up, show a **blocking** modal error, then write to stderr. |
| `logErrorAsync(s)` | Write to stderr, then show a non-blocking error dialog posted to the UI thread. |
| `logErrorMultiline(s)` | Like `logError`, but keeps line breaks in the dialog. The normal dialog collapses text to one line, as a defence against dumped stack traces. |
| `logErrorConfirmRetry(s, retryText) -> bool` | Retry dialog; returns false if there is no GUI. |
| `logWarning(s)` | If a GUI is up, a **non-modal toast** (`dialog.alert.warning.title`), then write to stderr. It used to be a modal, which froze download workers and stacked dialogs. |
| `logThrowable(t)` | Redacted stack trace to stderr. |

`enableConfigBackedDebugLogging()` reads `ConfigManager.getDebugLogging()` once and sets ready. `setDebugLoggingEnabled(b)` is called by the config setter. The cache exists because reading config on every debug line inverted lock order (the config writer logs while holding its lock), which produced an intermittent deadlock. **Rust: the logger never touches the config.** It holds an `AtomicBool`, and the config crate pushes changes to it.

### 8.3 File sink (`initLogSys(file)`)

1. Idempotent: shut down any previous sink and flush timer **first**. This releases the handle before rotation; Windows cannot rename an open file.
2. Create the parent directories. If that fails and the directory does not exist, log debug `LOG_FILE_DIR_NOT_CREATED_TEXT`.
3. If the file exists and is larger than **10 MiB**, rotate (§8.4). With per-second file names this rarely triggers. It matters only within the same second or after a clock reset.
4. Create the file **owner-only from creation** on POSIX (`0600` via `OpenOptions::mode`). Otherwise create it and then tighten the ACL before the first write. The file is **truncated**, not appended.
5. Buffered writer. The 5 s flush timer runs on a daemon thread, `log-flush`. If scheduling fails, fall back to unbuffered and log the silent error `log.logger.unbufferedFallback`.
6. Replace `System.out` and `System.err` with tee streams to the console and the file. In **TUI mode, write to the file only**.
7. `logStd(LOG_SYSTEM_INITIALIZED_TEXT)`.

`shutdownLogSys()`: stop the timer, then flush and close the file.

**Rust design:** the Java tee also captures any third-party `System.out.println`. The Rust equivalent is a global `Logger` behind which every subsystem writes; there is no fd redirection. Recommendation: use `tracing` with a custom `Layer` that formats `[App/LEVEL] msg`, redacts, and writes to the console (stdout for info and debug, stderr for warn and error) plus a `Mutex<BufWriter<File>>` flushed every 5 s and on shutdown. Map `logStd` to `info!`, `logDebug` to `debug!` (gated by the atomic flag or a dev build), `logWarningSilent` to `warn!` and `logErrorSilent` to `error!`. GUI side effects (dialogs, toasts) go through a registered `UiSink` trait object set by the app crate, so the logger crate has no GUI dependency. Dependencies like `reqwest` log through `tracing`. Filter them to `warn` so they don't flood the launcher log.

### 8.4 Rotation (`rotateLogFiles`), package-private for tests

```
base = abs(file)
delete base.3 if it exists
for i in 2 down to 1: if base.i exists: move base.i → base.(i+1), replacing (then owner-only)
move base → base.1, replacing (then owner-only)
```

A failed move logs the silent warning `log.logger.rotateFailed {fileName, ExcClass}` and continues. Gaps in the sequence are fine; only existing files shift. `MAX_LOG_BACKUPS = 3`, `MAX_LOG_FILE_SIZE = 10*1024*1024`.

### 8.5 Redaction (`SensitiveDataRedactor.redact`), applied to every log line

Applied in order:
1. `(--accessToken)([=\s]+)\S+` → `$1$2[REDACTED]`
2. `(--clientToken)([=\s]+)\S+` → `$1$2[REDACTED]`
3. `token:[A-Za-z0-9._-]+:([0-9a-fA-F-]{32,36})` → `token:[REDACTED]:$1`. The UUID is preserved deliberately.

`redactStrict` also replaces dashed UUIDs (`\b[0-9a-fA-F]{8}-...-[0-9a-fA-F]{12}\b`) and undashed 32-hex runs (`\b[0-9a-fA-F]{32}\b`) with `[REDACTED]`. MD5 hashes are an accepted false positive. The Rust `regex` crate supports all of these patterns, including `\b`.

### 8.6 `StartupDiagnostics` (`logs/startup.log`, always under the client root)

- Appends `yyyy-MM-dd HH:mm:ss.SSS [pid N] message` plus the line separator, in UTF-8.
- If the file is larger than **256 KiB**, it is first moved to `startup.log.1`, replacing any existing one.
- Synchronized. Best-effort: errors are swallowed.
- Records: launch start, uncaught exceptions with stack traces, second-launch hand-off outcomes, early toolkit failures.

It works before the logger and before the game mode is known, which is why it always uses the client root.

---

## 9. `SynchronizedFileManager`

This provides one canonical `File` object per path, so callers can `synchronized(file)` for mutual exclusion across threads.

- Key: `abs(path).normalize()` as a string. On Windows (separator `\`), the key is **lowercased with `Locale.ROOT`**. Normalization is lexical only (`.` and `..`), with no symlink resolution.
- Map: `ConcurrentHashMap.computeIfAbsent`.
- Users in this slice: the config file and the log file. Download, verification and install code elsewhere uses it heavily.

**Rust:**

```rust
pub struct FileLocks { map: DashMap<String, Arc<parking_lot::Mutex<()>>> }  // or Mutex<HashMap<..>>
pub fn file_lock(path: &Path) -> Arc<Mutex<()>>;    // key = normalize_lexical(absolute(path)), lowercased on Windows
```

Return a lock handle rather than a path. Callers do `let _g = file_lock(p).lock();`. Use `std::path::absolute` (stable since 1.79) plus lexical `..` handling. Do not use `canonicalize`: it fails on missing files and resolves symlinks, which diverges from Java. On macOS (case-insensitive APFS by default), Java does not fold case either, so keep parity. The map grows without bound; Java does the same, with one entry per distinct file.

---

## 10. Localization

### 10.1 Files and naming

- Location: `src/main/resources/lang/DisplayStrings.properties` (English, the root bundle) plus `DisplayStrings_<java-locale>.properties` for `es fr de pt_BR it ru ja ko zh_CN zh_TW ar hi nl pl tr sv`.
- **Tags are BCP-47 (`pt-BR`); filenames use Java's underscore form (`pt_BR`).** Convert only when building a filename: `tag.replace('-', '_')`. A hyphenated filename is silently never found. That shipped for `pt-BR`, `zh-CN` and `zh-TW`.
- Encoding: UTF-8, **CRLF** line endings. Non-English files mostly use `\uXXXX` escapes (for example `ar` has about 2,490 escaped lines). English has 19. Each file has about 2,620 lines and about 2,540 key-value entries. All locales have identical key sets; the parity test enforces this.
- The Rust port reads **the same files**. Embed them at compile time with `include_str!("../../../../src/main/resources/lang/DisplayStrings.properties")`, or with `include_dir!` over the folder, so there is a single source of truth shared with Java.
- `SupportedLocales.ENTRIES` (order = dropdown order; the display name is in its own language):

| Tag | Display name |
|---|---|
| `es` | Español |
| `fr` | Français |
| `de` | Deutsch |
| `pt-BR` | Português (Brasil) |
| `it` | Italiano |
| `ru` | Русский |
| `ja` | 日本語 |
| `ko` | 한국어 |
| `zh-CN` | 简体中文 |
| `zh-TW` | 繁體中文 |
| `ar` | العربية |
| `hi` | हिन्दी |
| `nl` | Nederlands |
| `pl` | Polski |
| `tr` | Türkçe |
| `sv` | Svenska |

English is not in the list. The UI offers "Use OS Language" (empty override) plus these 16. Keep this list in sync with `TARGET_LOCALES` in `tools/i18n/translate-locales.js`.

### 10.2 `.properties` grammar the Rust parser must implement (Java `Properties.load`)

- Lines end at `\n`, `\r` or `\r\n`. A **logical line** may continue onto the next physical line when it ends in an **odd** number of backslashes. The continuation's leading whitespace is skipped. None of the current files use continuations, but support them.
- Blank lines are skipped. A line whose first non-whitespace char is `#` or `!` is a comment.
- Leading whitespace (space, `\t`, `\f`) on a line is skipped.
- Key: ends at the first unescaped `=`, `:` or whitespace. Then skip whitespace, then **one** optional `=` or `:`, then whitespace again. The rest is the value.
- Escapes in both key and value:
  - `\t`, `\n`, `\r`, `\f`
  - `\uXXXX` (exactly 4 hex digits; malformed is an error in Java, so be lenient and keep it literal)
  - `\` followed by any other char gives that char. So `\ ` is a space (used for 13 values with a leading space, such as `detailModal.whatsNew.more=\ (+{0} more)`), `\"` is `"`, `\\` is `\`, and `\:` is `:`.
- **An unescaped leading space in a value is dropped.** The parity test enforces escaping it. Trailing whitespace is **kept**.
- A duplicate key: the last one wins.
- After decoding `\uXXXX`, re-pair surrogates (Java strings are UTF-16). Handle a high surrogate followed by a low one, combining them into one `char`.

Crate option: `java-properties` (it parses this grammar). Recommended: a hand-written parser of about 150 lines, so continuation and surrogate handling are under our control and tested against Java's output with a golden test (§16).

### 10.3 Bundle resolution (Java `ResourceBundle.getBundle(base, locale)` default `Control`)

Java's candidate order for a locale `L` is the specific form, then the language only, then (if nothing specific was found) **the JVM default locale's chain**, then the root. `setLocale(t)` sets the default to `t` first, so in practice the chain is `t`'s candidates, then root. Child bundles **inherit missing keys from their parents** (`DisplayStrings_pt_BR`, then `DisplayStrings_pt` if it existed, then root).

Candidate generation for `lang[-Script][-REGION]`:
- `lang_REGION`, then `lang`, then root. With a script: `lang_Script_REGION`, `lang_Script`, `lang_REGION`, `lang`.
- **Chinese special case** (Java does this):
  - `zh` with no script but a region: TW, HK or MO imply `Hant`; CN or SG imply `Hans`.
  - `zh-Hans` with no region adds the `zh_CN` candidate. `zh-Hant` with no region adds `zh_TW`.
  - So macOS's `zh-Hans-CN` finds `zh_CN` and `zh-Hant-TW` finds `zh_TW`. **Plain `zh` finds nothing**, so English is shown.
- Norwegian `nb`/`no` special-casing is irrelevant (no file).
- `pt` alone (Portugal or OS `pt-PT`) finds no `DisplayStrings_pt`, so English. Parity: keep this. Optional improvement in §17.

Rust: `fn candidates(langid: &LanguageIdentifier) -> Vec<String>` produces file suffixes; then load the first hit plus its parents and **merge**: start from root, overlay `lang`, then `lang_REGION`. `bundle.locale()` = the locale of the most specific file found (Java's `bundle.getLocale()`); `LocalizationManager.currentLocale()` returns it, and the Settings UI uses it.

### 10.4 `LocalizationManager` API semantics (port exactly; the tests pin them)

- `get(key)`: `null` key → `""`. A missing key returns **the key itself**, so a missing entry shows as the raw key in the UI.
- `getOr(key, fallback)`: `null` key → fallback. Missing → fallback.
- `format(key, args...)`:
  1. `template = get(key)`. If the key is missing, the template is the key text.
  2. If there are no args, return the **raw template**: no apostrophe processing, no brace processing, `{0}` left as-is.
  3. Otherwise apply `MessageFormat.format(escapeApostrophes(template), args)`. **Every `'` is doubled**, which makes it literal. Bundle text never uses MessageFormat quoting; the parity test rejects `''` in bundles.
  4. If the pattern is malformed (`IllegalArgumentException`, for example an unmatched `{`, or a non-numeric `{foo}`): return `template + " [" + args.join(", ") + "]"`.
  5. A missing key whose text has no braces returns the key unchanged and **silently drops** the args (test: `format_missingKeyWithNoBraceSyntax...`).
- `currentBundle()` / `currentLocale()`. FXML `%key` resolves against `currentBundle()`; the GPUI equivalent is `t!("key")` at render time.
- `setLocale(locale)`: synchronized. `null` means the current default. Sets the default, loads the bundle, and **atomically** swaps a single `(bundle, locale)` holder, so a reader never sees a new bundle paired with the old locale.
- About 89 legacy `ALL_CAPS` keys back `public static final String` fields that bind at class load (`LOG_*_PREFIX`, `CONFIG_*`, `USAGE_TEXT`, ...). **In Rust, these are just keys.** Look them up dynamically like everything else (`t!("CONFIG_SAVE_ERROR_TEXT")`). Do not add new ALL_CAPS keys.

`MessageFormat` subset to implement (`mmcl-i18n::msgfmt`):
- Literal text. With apostrophe auto-escaping, every `'` in the input is literal, so the formatter can treat `'` as an ordinary char. **Inside a `choice` sub-message, Java re-applies MessageFormat to the chosen text** (only when it contains `{`), and the apostrophes there are already doubled. Treat them as literal there too.
- `{n}` with `n` from 0 to 9 and beyond (any digits):
  - Arg missing (index ≥ len): emit `{n}` literally (test: `format_tooFewArgs_leavesUnfilledSlotAsLiteralBraces`). Extra args are ignored.
  - Arg present: format by type, as in the table below.
- `{n,choice,LIMITS}`: limits separated by `|`; each item is `<number><#|<|≤><text>`. `#` (or `≤`, U+2264) means `>=`; `<` means strictly greater (the next double up). Pick the **last** item whose condition holds; if the value is below the first limit, pick the first item. If the chosen text contains `{`, re-format it recursively with the **same args**. Example: `{0,choice,0#0 mods|1#1 mod|1<{0} mods}`. The arg must be numeric; a string arg throws in Java, which leads to the debug fallback string. 41 choice patterns exist across all bundles.
- `{n,number}`, `{n,number,integer}`, `{n,date}` and `{n,time}` are **not used** by any bundle today (verified by grep). Parse them for robustness, formatting `number` as above and `integer` as a rounded integer. Do not invent other styles.
- Errors (treated as malformed, giving the debug string): unmatched `{` or `}` at the top level, a non-numeric argument index, an unknown format type, a malformed choice.

Argument formatting:

| Java arg type | Output |
|---|---|
| `String` | As-is. |
| `Number` (int, long) | `NumberFormat.getInstance(locale)`, with **locale digit grouping**: en `10,000`, de `10.000`, fr `10 000` (U+202F narrow no-break space), ru and sv also use spaces, hi `10,000` and `1,00,000` (Indian grouping). |
| `Number` (double) | Same, with at most 3 fraction digits, rounded half-even. |
| `Boolean` | `"true"` / `"false"`. |
| `null` | `"null"`. |
| Other (Path, enum, Throwable) | `toString()`. |

Callers that must not be grouped already pass `String.valueOf(n)`, for example the port in `startup.portUnavailable`. Exit codes, counts, byte sizes and timeouts are passed as numbers, so `cleanupStepTimedOut` renders `3,000` in English.

Rust arg type:

```rust
pub enum FmtArg<'a> { Str(Cow<'a, str>), Int(i64), Float(f64), Bool(bool) }
impl From<&str>/String/i32/i64/u32/u64/usize/f64/bool/&Path/... for FmtArg
```

Pass `.to_string()` explicitly where grouping must be avoided. For grouping, use **ICU4X `icu_decimal`** with `fixed_decimal`, and compiled data for the 17 locales: it gives correct CLDR separators, grouping sizes and native digits. If its binary size (around 1 MB of data) is unacceptable, use a fallback table of `(group_sep, decimal_sep, grouping)` per locale. Digit shaping for Arabic: Java's default for `ar` uses Latin digits in recent JDKs; check what JDK 26 emits for `ar` and match it (ICU4X with `-u-nu-latn` forces Latin digits).

### 10.5 `LocaleBootstrap` and startup resolution

- `OS_DEFAULT` is captured **once**, before any override is applied. "Use OS Language" must re-detect the real OS locale, not the last applied one. In Rust, call `sys_locale::get_locale()` once at startup and store it in a `OnceLock`.
- `resolve(override)`:
  - Blank override → `detectOsLocale()`.
  - Otherwise parse BCP-47 (`Locale.forLanguageTag`, which is lenient). If the result is empty or `und`, or parsing fails, use `detectOsLocale()`.
  - Else use the parsed locale.
- `detectOsLocale()`: a null, blank, `und`, `POSIX` or `C` tag gives `en-US`; otherwise the OS locale.
  - Rust specifics: `sys-locale` may return `C`, `POSIX`, `C.UTF-8` or `en_US.UTF-8` on Linux. Normalize by stripping `.codeset` and `@modifier` and replacing `_` with `-`. Treat `C` and `POSIX` (any codeset) as `en-US`.
- `apply(override)` sets the JVM default locale, which is only needed because of Java's class-load binding.
- **Rust: `i18n::init(resolve(override))`, once, after config load.** The UI language can switch at runtime with `i18n::set_locale`. Every string is looked up dynamically, so no relaunch is needed (§10.6).

### 10.6 Runtime language switching

Java needs a full process relaunch to re-localize the static-final strings. In Rust, call `i18n::set_locale(l)`, then refresh every open GPUI window (`cx.refresh_windows()`), rebuild cached strings in view state, and update the native menus and the tray. **Recommendation: drop the relaunch for language changes.** Keep `relaunch_app()` for other uses: reset, and anything else that needs it.

### 10.7 Key conventions (from the bundle header and CLAUDE.md)

- New keys are dot-namespaced, `<screen>.<element>.<purpose>`. Examples: `browse.filter.type.label`, `console.title.crashed`, `notification.install.failed.body`, `log.<class>.<event>` for log messages.
- Every user-visible string **and every log message** goes through the localizer (owner rule: full localization). Exceptions: the log level prefixes, the game-mode log lines and the config type-mismatch warnings, which are hardcoded English by design.
- Placeholders are `{0}`, `{1}`, and so on. Never concatenate user data into a template.
- Write apostrophes plainly (`it's`), never doubled.
- No XML entities (`&quot;`) in values.
- A leading space must be written as `\ `. ja, zh-CN and zh-TW may drop it.
- Translations must keep the English line-break count and must not add backslashes.
- `translate-locales.js` swaps `{n}` for `__MMCL_PH<n>__` sentinels around each API call. It is incremental, and `--force` re-translates. New Rust-only keys go into the same English file and get translated with the same tool (`cd tools/i18n && npm run translate`), or are translated by hand for a handful of keys. The Rust crate must **not** fork the bundle.
- Sentinel strings: when domain code returns localized text that callers compare against, add a boolean helper (`is_never_played()`) instead of comparing strings.

---

## 11. Exceptions → Rust errors

| Java | Rust |
|---|---|
| `LauncherException(msg[, cause])`, checked, with user-friendly wording | `mmcl_core::Error::Launcher { msg: String, #[source] source: Option<anyhow::Error> }` |
| `ModpackException extends LauncherException` | `ModpackError` (in the modpack crate), convertible into `Error` |
| `ModpackScanDetectionException extends ModpackException` | `ScanBlocked { report: ScanReport, pack_name: Option<String>, pack_root: Option<PathBuf> }`; `Display` = the message below |

Use `thiserror` for the enums. The launcher's top-level error handling matches on the variant to choose `logErrorMultiline` (scan blocked) versus `logError`.

`ModpackScanDetectionException` message (`getExceptionMsg(results, packName, packRoot)`):
- `all = stage1 ++ stage2`, de-duplicated in order, with nulls dropped.
- If empty: `The pre-launch security scan completed without any findings.`
- Else, with `label = "\"<name>\""` (or `this modpack` if the name is blank) and `n = count`:

```
The pre-launch security scan blocked <label> — <n> issue|issues found:\n\n
• <cleaned line 1>\n
• <cleaned line 2>\n
...
\nRemove or replace the flagged file(s) before launching again. If you trust this content, you can change this pack's Security Scan Frequency to "Disabled" in its Advanced settings to bypass the scan.
```

(`issue` when n = 1.) The separator is an em dash, U+2014.

`cleanFindingLine(raw, root)`:
- null → `Unknown detection`.
- trim, then strip `^\[[A-Za-z]+]\s+`.
- If a root is given: strip the `root + separator` prefix. Otherwise retry with `/`-normalized forms of both strings, and on a match cut `prefixSlash.length()` chars off the **original** string, which keeps its separators.

**This text is hardcoded English in Java. Rust should localize it** with new keys such as `scan.blocked.header`, `scan.blocked.footer` and `scan.blocked.none`, using `choice` for issue/issues, and with the `•` bullets kept in the template.

---

## 12. Security: pre-launch scan

### 12.1 Orchestration (in `GameModPack.scanModPackRootFolder`; this belongs to the modpack spec, summarized for context)

- `threads = max(1, cpus/2)`.
- `safe = ScanExclusionPolicy.filterUntrusted(manifest.packScanExclusions)`.
- Nekodetector `Main.run(threads, root, emitWalkErrors=true, safe, logOutput, null)` gives `Results{stage1: Vec<path>, stage2: Vec<path>}`. `logOutput` strips the ANSI codes `\x1b[31m`, `\x1b[32m`, `\x1b[37m` and `\x1b[0m` and pushes the text to the progress sub-text.
- `SupplementalScanner.scanFolder(root, safe, threads)` gives the findings. An I/O error here is a warning only.
- Apply the manifest `packScanAcknowledgements` (`fileSha256` / `innerSha256` / `kind` / `locator` / `reason`) **to supplemental findings only**. Neko hits cannot be acknowledged. Matched findings are logged with their reason.
- Log every remaining finding. HIGH and MEDIUM produce a `logWarning` toast. Print a paste-ready acknowledgement JSON hint (innerSha256 recommended).
- **Blocking:** Neko stage 1 plus supplemental HIGH (`f.toString()`) plus Neko stage 2. If anything is blocking, throw `ScanBlocked(Results(blockingHigh, stage2), packName, packRoot)`. MEDIUM never blocks.
- `ScanFrequency` (`EVERY_TIME`, `DAILY` (the default), `ON_CHANGES_ONLY`, `DISABLED`) gates whether the scan runs at all. `DAILY` scans if there is no state, if the manifest SHA-256 changed, or if `lastScannedAt` is older than `VerifyState.DEFAULT_TTL_MS` (or in the future).

### 12.2 `ScanExclusionPolicy` (pure; port exactly)

`rejection_reason(ex) -> Option<String>`:
- `norm = ex.unwrap_or("").trim().replace('\\', '/').to_lowercase()`. Use ASCII or Unicode lowercase; Java uses `Locale.ROOT`, so `str::to_lowercase` is fine.
- Split on `/` and drop empty segments and `.` segments.
- No segments → `log.scanExclusion.reasonPackRoot` (this covers the pack root, for example `""`, `"."`, `"/"`, `"./"`).
- Any segment equal to `..` → `log.scanExclusion.reasonTraversal`.
- First segment in `{mods, config, scripts, resourcepacks, shaderpacks, kubejs, defaultconfigs}` → `log.scanExclusion.reasonProtectedFolder {seg}`.
- Otherwise None (allowed).

`filter_untrusted(list)` keeps the allowed entries **in their original form**. Each rejected entry logs the silent warning `log.scanExclusion.ignored {entry, reason}`. A null or empty list gives an empty list. Both scanners re-apply this filter internally (defence in depth), so callers cannot bypass it.

### 12.3 `SupplementalScanner`

Walk from `root`:
- Exclusions = `normalize_exclusions(filter_untrusted(caller))`, then append the **built-ins** `libraries`, `bin`, `runtime`. A manifest cannot remove the built-ins.
- `normalize_exclusions`: trim, strip all leading and trailing `/` and `\`, drop blanks, lowercase, `\` → `/`.
- `is_excluded(root, target, ex)`: false if `ex` is empty or `target == root`. Otherwise `rel = relativize(root, normalize(abs(target)))`, lowercased with `\` → `/`. Excluded if `rel == e` or `rel.starts_with(e + "/")` for some `e`. Excluded directories are skipped as whole subtrees.
- Queue every non-excluded file whose name ends in `.jar` (case-insensitive). Then scan the queue on a fixed pool of `max(1, n)` threads. Use `rayon` with a scoped pool of `n` threads. Java waits at most **30 minutes**, then calls `shutdownNow`.
- A jar that fails to open logs the silent warning `log.supplementalScanner.openFailed {fileName, ExcClass}`.
- Walk errors (`walkFileTree` throwing) propagate as I/O errors. In Rust, with `walkdir`, log unreadable entries and continue, but return `Err` if the root itself is unreadable.

`scan_jar(zip, jar_path)` visits each non-directory entry once:
- `name` = the raw entry name; `lower` = lowercase; `norm` = `lower` with `\` → `/`.
- `fileSha256` is computed **lazily**, the first time a finding needs it. It is a streaming SHA-256 of the jar file, lowercase hex, or None on error.
- `isNativesJar` = the jar's file name matches `(?i).*-natives-.*\.jar$`.

Checks, in order:

1. **Embedded executable (HIGH, `EMBEDDED_EXECUTABLE`).** Applies when `lower` ends with any of `.exe .scr .com .bat .cmd .ps1 .psm1 .vbs .vbe .wsf .msi .lnk .reg`. `innerSha` is the SHA-256 of the entry bytes, or None on a read error or when the entry is over the ceiling. Locator = `name`. Message: `embedded executable / script entry: <name>`. Only the first matching suffix counts.
   - `.js` and `.jse` are deliberately **not** flagged: they are false positives in FAWE, MaryTTS and KubeJS.
2. **Native outside a known path (MEDIUM, `NATIVE_OUTSIDE_KNOWN_PATH`).** Skipped for natives jars. Applies when `lower` ends with `.dll`, `.so`, `.dylib` or `.jnilib`, the path is **not** a legitimate native path, **and** the filename is **not** a legitimate native filename (both tests below). Locator = `name`. Message: `native binary outside a known natives path: <name>`.
   - Legitimate path: `norm.contains(p)` for any of: `meta-inf/native`, `natives/`, `native/`, `jni/`, `lib/`, `libs/`, `assets/`, `org/lwjgl/`, `lwjgl/native`, `com/sun/jna/`, `linux/`, `windows/`, `macos/`, `mac/`, `osx/`, `freebsd/`, `netbsd/`, `openbsd/`, `solaris/`, `aix/`, `x86/`, `x86_64/`, `x64/`, `i686/`, `i386/`, `aarch64/`, `arm64/`, `arm/`, `armv7/`, `armv6/`, `amd64/`, `ppc/`, `ppc64/`, `ppc64le/`, `riscv64/`, `loongarch64/`, `mips/`, `mips64/`, `s390x/`, `sparc/`, `darwin-`, `linux-`, `windows-`, `macos-`, `-macosx-`, `-linux-`, `-windows-`.
   - Legitimate filename: the basename (after the last `/`) starts with `lib` or ends with `.dll`. A `.dll` therefore never fires; the check is effectively informational.
3. **Class content.** Applies when `lower` ends with `.class`. Read the bytes (subject to the ceiling); `innerSha` = SHA-256 of the class bytes, computed lazily. Parse the class while **skipping debug info and frames**. For each method and each instruction:
   - `LDC`, `LDC_W` with a **String** constant → `inspect_string`. ASM's `LdcInsnNode` with `cst instanceof String` corresponds to `CONSTANT_String`. (`LDC2_W` never loads a String.)
   - Invoke instructions (`INVOKEVIRTUAL`, `INVOKESPECIAL`, `INVOKESTATIC`, `INVOKEINTERFACE`; ASM `MethodInsnNode`; `INVOKEDYNAMIC` is **not** included): set `sawRobot` if the owner is `java/awt/Robot`, and `sawClipboard` if the owner is `java/awt/datatransfer/Clipboard`.
   - After all methods: if both flags are set, add MEDIUM `CLIPBOARD_STEALER_PATTERN`, locator = the class internal name (`node.name`), message `clipboard-stealer pattern (AWT Robot + Clipboard) in <name>`. Once per class.
   - Any parse failure skips that class silently.

`inspect_string(s)`, with locator = `<classInternalName>.<methodName>`:
- Return early if `s` is empty, or if it contains neither `://` nor `.`.
- **(A)** It contains `://` and `(?i)https?://(?:[a-z0-9.-]*\.)?discord(?:app)?\.com/api/webhooks/[\w-]+/[\w-]+` finds a match → HIGH `DISCORD_WEBHOOK_URL`, `Discord webhook URL in <loc>`. Return.
- **(B)** It contains `://` and any of these (case-insensitive) finds a match → HIGH `SUSPICIOUS_HOST_URL`, `suspicious host URL in <loc> (<pattern source text>)`. Return.
  - `https?://(?:www\.)?pastebin\.com/raw/[\w-]+`
  - `https?://(?:www\.)?(anonfiles\.com|transfer\.sh|tmpfiles\.org|bashupload\.com|file\.io|gofile\.io)/[\w./?=&-]+`. These are six separate patterns in Java; the message includes the matched pattern's source, so keep them separate with exactly the Java pattern strings, for example `https?://(?:www\.)?transfer\.sh/[\w./?=&-]+`.
- **(C)** `s.to_lowercase()` contains `launcher_profiles.json`, `launcher_accounts.json`, `launcher_msa_credentials.bin` or `credentials.json` → HIGH `LAUNCHER_CREDENTIAL_FILE_REF`, `reference to launcher credential file '<f>' in <loc>`. Return. (`usercache.json` is deliberately excluded: it is the vanilla server's cache.) Java iterates a `Set.of(...)`, so the order is unspecified; for strings with two matches the reported name could vary. Rust: use a fixed order.
- **(D)** If `s` has fewer than 3 dots, return.
- **(E)** `m = find(\b(?!127\.)(?!0\.)(?!22[4-9]\.)(?!23\d\.)(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})\b(?!\.\d))`, **first match only**. If `looks_like_real_ip(m)` (all four octets 0 to 255) and not `is_false_positive_ip(s, m)` → MEDIUM `IPV4_LITERAL`, `hard-coded IPv4 literal in <loc>: <match>`.
  - **The Rust `regex` crate has no look-around.** Use `fancy-regex`, or implement it by hand: scan for `\d{1,3}(\.\d{1,3}){3}` candidates at word boundaries, then apply the negative checks (first octet not `127`, `0`, `224` to `229`, or `230` to `239`, judged by text prefix as the Java regex does; and not followed by `.\d`). Careful: Java's `\b` and `\d` are ASCII by default. Hand-rolling is preferred and is easy to test against the existing test vectors. Note that the regex lookaheads test **text prefixes**: `(?!0\.)` rejects first octet `0`; `(?!22[4-9]\.)` rejects 224 to 229; `(?!23\d\.)` rejects 230 to 239. 240 and above are **not** excluded by the regex, despite the comment. Also `\d{1,3}` can match the middle of `1234.1.1.1` only at a word boundary, which never falls inside digits, so `234.1.1.1` is not found there. Replicate the regex exactly, and add the Java test vectors.
- `is_false_positive_ip(s, a.b.c.d, start)` returns true for:
  - `10/8`; `100.64/10`; `169.254/16`; `172.16/12`; `192.168/16`; `192.0.0/24`; `192.0.2/24`; `198.18/15`; `198.51.100/24`; `203.0.113/24`
  - the DNS resolvers `8.8.8.8`, `8.8.4.4`, `1.1.1.1`, `1.0.0.1`, `9.9.9.9`, `149.112.112.112`, `208.67.222.222`, `208.67.220.220`
  - `c == 0 && d == 0`
  - `a < 32 && !is_ip_url_host(s, start)`
- `is_ip_url_host(s, start)`: `s[start-3..start] == "://"`, or `s[start-1] == '@'`. Java indexes UTF-16 code units; in Rust use byte offsets from the regex match. Those offsets are equivalent because the context chars are ASCII.

Zip-bomb guard: an entry read aborts at more than **256 MiB** of inflated bytes. Reads of classes and entries go through it; on overflow the class is skipped or the hash is None. **The Neko pass in Java has no such guard. Add one in Rust** (same constant).

`Finding { severity: High|Medium, kind, file: PathBuf (jar), file_sha256: Option<String>, inner_sha256: Option<String>, locator: String, message: String }`. `Display` = `[HIGH] <jar path> — <message>`. The `Kind` names are **part of the public manifest schema** (`packScanAcknowledgements`): `EMBEDDED_EXECUTABLE`, `NATIVE_OUTSIDE_KNOWN_PATH`, `DISCORD_WEBHOOK_URL`, `SUSPICIOUS_HOST_URL`, `LAUNCHER_CREDENTIAL_FILE_REF`, `IPV4_LITERAL`, `CLIPBOARD_STEALER_PATTERN`. Serialize them as those exact strings and never rename them.

### 12.4 Nekodetector (vendored `me.cortex.jarscanner`; MIT, original at `github.com/MCRcortex/nekodetector`)

`Main.run(nThreads, dir, emitWalkErrors, excludes, logOutput, progress)`:
- `safe = filter_untrusted(excludes)`. Again, defence in depth.
- Log `Starting All Scans - ...` (the ANSI-green prefix is stripped by the caller).
- **Panics** (`IllegalArgumentException`) if `dir` does not exist, is not a directory, or `nThreads < 1`.
- `countValidFiles` makes a first walk to count non-excluded files (for progress). Then a second walk:
  - Directories excluded per `isPathExcludedFromScan` are skipped as subtrees.
  - For each non-excluded file, increment progress. If `path.lower().ends_with(".jar")`, submit `Detector.scan(jar)` to the pool and log `Scanning Jar file for infection: <name>`. On detection, add `file.toString()` (the absolute path as walked) to `stage1`. Open failures log `Failed to scan Jar file: <file>` when `emitWalkErrors` is set.
  - `visitFileFailed` and `postVisitDirectory` errors log `Failed to access file|directory: ...`.
- Wait for the pool with effectively no timeout.
- Then `stage2 = Detector.checkForStage2()`.
- Log the timings: `Scanner Prepared - Took N ms.`, `Stage 1 Scan Complete - Took N ms.`, `Stage 2 Scan Complete...`, `All Scans Complete - Total N ms.`.

`isPathExcludedFromScan(scan, check, ex)`:
- false if `check == scan`.
- `rel = relativize`; `relStr = rel.toString().lower()`.
- For each `e`: `ne = e.trim()` with **one** leading `/` and **one** trailing `/` stripped, then lowercased. Excluded if `ne == relStr`, or (when rel has a parent) `ne == relStr` again (a Java bug: the "parent" string is the full path, so this branch is a duplicate), or `relStr.starts_with(ne + File.separator)`.
- **Windows quirk:** an exclusion written `a/b` never matches `a\b`. Supplemental normalizes `\` to `/`; Neko does not. **Rust: use the supplemental `normalize_exclusions` and `is_excluded` for both passes** (consistent; see §17).

`Detector.scan(jar)`: for each entry whose name ends with `.class` (case-**sensitive** here), `scanClass(bytes)`. Short-circuit on the first hit (`anyMatch`). Per-class I/O or parse errors are logged and the class counts as clean. Then close the jar.

`scanClass(bytes)`:
- Parse with all attributes (flags 0). If the class version is unsupported or the class is malformed, return false. **ASM rejects class versions newer than it knows; that is a blind spot. The Rust parser should not gate on the major version.**
- Then, for each method, test three signatures. Any hit means the class is infected.

**Instruction model for matching.** ASM's tree gives one node per real instruction, plus pseudo-nodes (labels, line numbers, frames) with opcode −1, which the matcher skips. Decoding the Code attribute bytecode linearly gives the same real-instruction sequence. Normalizations needed to match ASM:
- `LDC_W` → `LDC`
- `wide` forms → the base opcode
- `GOTO_W` → `GOTO`, `JSR_W` → `JSR`
- ASM has no separate node for `wide`
- `ILOAD_0` and friends stay as they are (ASM keeps them as `VarInsnNode`? **No:** ASM expands `ILOAD_n` to `ILOAD` with var n). This does not matter: the signatures use only `NEW`, `INVOKE*`, `BIPUSH`, `BASTORE`, `DUP` and `ICONST_1` to `ICONST_5`, and none of those are rewritten.
- So: decode opcodes, keep `{opcode, operand}`, and resolve `NEW`'s class and `INVOKE*`'s `(owner, name, desc)` from the constant pool (Methodref and InterfaceMethodref).

Signature matching, where `insns` = the real instructions of the method:

- **SIG1** (the hard detect), the expected list E1 of 15 items:
  1. `NEW java/lang/String`
  2. `INVOKESPECIAL java/lang/String.<init>([B)V`
  3. `NEW java/lang/String`
  4. `INVOKESPECIAL String.<init>([B)V`
  5. `INVOKESTATIC java/lang/Class.forName(Ljava/lang/String;)Ljava/lang/Class;`
  6. `INVOKEVIRTUAL java/lang/Class.getConstructor([Ljava/lang/Class;)Ljava/lang/reflect/Constructor;`
  7. `INVOKESPECIAL String.<init>([B)V` ×3
  8. `INVOKESPECIAL java/net/URL.<init>(Ljava/lang/String;Ljava/lang/String;ILjava/lang/String;)V`
  9. `INVOKEVIRTUAL java/lang/reflect/Constructor.newInstance([Ljava/lang/Object;)Ljava/lang/Object;`
  10. `INVOKESTATIC java/lang/Class.forName(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;`
  11. `INVOKESPECIAL String.<init>([B)V`
  12. `INVOKEVIRTUAL java/lang/Class.getMethod(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;`
  13. `INVOKEVIRTUAL java/lang/reflect/Method.invoke(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;`

  Algorithm: `j=0; for insn in insns while j<15: if insn.op == E1[j].op { if !same(insn,E1[j]) { fail } ; j+=1 }`. Match iff `j == 15` with no fail. So it is an in-order subsequence where the **first** later instruction with the expected opcode must also match the operands; otherwise the method fails immediately.
- **SIG2** (the near-hard detect), E2 of 7 items:
  1. `INVOKESTATIC java/lang/Runtime.getRuntime()Ljava/lang/Runtime;`
  2. `INVOKESTATIC java/util/Base64.getDecoder()Ljava/util/Base64$Decoder;`
  3. `INVOKEVIRTUAL java/lang/String.concat(Ljava/lang/String;)Ljava/lang/String;`
  4. `INVOKEVIRTUAL java/util/Base64$Decoder.decode(Ljava/lang/String;)[B`
  5. `INVOKESPECIAL java/lang/String.<init>([B)V`
  6. `INVOKEVIRTUAL java/io/File.getPath()Ljava/lang/String;`
  7. `INVOKEVIRTUAL java/lang/Runtime.exec([Ljava/lang/String;)Ljava/lang/Process;`

  Algorithm: for each start `q` over the **raw node list including pseudo-nodes** (index semantics differ, but the result is the same for real nodes): `j=0; for i in q..: if op != E2[j].op continue; if !same → next q; j+=1; if j==7 → match`. Pseudo-nodes have opcode −1 and are never equal to an expected opcode, so iterating over real instructions only gives the same result.
- `same(a, b)`: TypeInsn compares the `desc` (internal class name). MethodInsn compares owner, name and desc. Plain InsnNode always matches.
- **SIG3** (a byte array spelling the IP `85.217.144.130`), E3 of 55 items: `BIPUSH 56, BASTORE, DUP, ICONST_1, BIPUSH 53, BASTORE, DUP, ICONST_2, BIPUSH 46, ..., DUP, BIPUSH 13, BIPUSH 48`. Copy the list verbatim from `Detector.java` (`SIG3`). The bytes are the ASCII codes of `"85.217.144.130"`, indices 0 to 13.
  - Algorithm, with its quirks: `pos=0; match=false; for insn in real_insns: if pos==55 break; if insn.op == E3[pos].op { if E3[pos] is BIPUSH(INT_INSN) { if operand == E3[pos].operand { match=true; pos+=1 } /* else: neither advance nor reset */ } else { match=true; pos+=1 } } else { match=false; pos=0 }`. The result is `match` at loop end.
  - **Quirk:** `match` becomes true after **any** single opcode-matching instruction, and is reset only by a non-matching opcode. So a method whose **last** real instruction happens to have `E3[pos]`'s opcode reports a hit even when `pos < 55`. In practice methods end with `*RETURN` or `ATHROW`, so this almost never fires. **Port it exactly** (for parity), and add a test documenting the quirk. Also, a mismatch reset does not re-test the current instruction as a new start.

`checkForStage2()` (Fractureiser stage 2 IoCs):
- `appdata = env APPDATA`, or `~/AppData/Roaming` if unset.
- `startup = appdata/Microsoft/Windows/Start Menu/Programs/Startup`.
- `windows = isDirectory(startup) && isWritable(startup)`. If `windows`:
  - Flag `File(env("APPDATA") + "\\Microsoft Edge")` if it exists. This is the IoC folder; the real Edge lives under `LOCALAPPDATA\Microsoft\Edge`. Java bug: when `APPDATA` is unset, this becomes the literal `"null\Microsoft Edge"`.
  - Then **Java bug:** it lists the **relative** path `Microsoft\Windows\Start Menu\Programs\Startup` (relative to the CWD, not to `startup`), so it effectively never inspects the real Startup folder. Intended: flag files there named exactly `.ref`, `client.jar`, `lib.dll`, `libWebGL64.jar` or `run.bat`.
- If `os.name` contains `linux`: check `File("~/.config/.data/lib.jar")`. **Java bug:** `~` is literal, so this resolves relative to the CWD and never matches the real home.
- Returns absolute path strings. **Any stage 2 hit blocks every launch**, because it means the machine is infected, not the pack.

**Decision for Rust (see open questions):** implement the intended checks (the real Startup folder and `$HOME/.config/.data/lib.jar`). This could newly block launches on machines that really are infected, which is the point of the check, but the behaviour changes. Recommendation: fix them.

### 12.5 Class-file parser (no ASM in Rust)

Options:
- **(a)** `cafebabe` (crates.io; parses the constant pool, attributes, and `Code` bytecode into an opcode enum with resolved member refs).
- **(b)** `noak`, `classfile-parser` or `ristretto_classfile`.
- **(c)** A minimal custom parser (about 400 lines): magic, version (**not** checked), the constant pool (all tags up to Java 26, including `Module`, `Package`, `Dynamic` and `InvokeDynamic`; `Long`/`Double` take two slots), access flags, this and super, interfaces, fields (skipped), methods (find `Code`, read the `code` bytes), and a bytecode decoder with per-opcode lengths (`tableswitch` and `lookupswitch` padding to 4-byte alignment relative to the code start; `wide`).

**Recommendation: (c).** Both scanners need only `(opcode, Class, Methodref, String)` resolution. It removes a dependency's version-gating risk, a malformed class must just return `Err`, and the parser is easy to fuzz (`cargo fuzz`). Never panic: all indexing is bounds-checked, and constant-pool index errors return `Err`, after which the class is skipped.

Zip: the `zip` crate (`ZipArchive<File>`). Entry names can contain `\`; treat them as raw strings, exactly as Java does. Encrypted or unsupported compression: skip the entry. Use `entry.take(MAX+1)` reads for the ceiling. Java's `JarFile` also verifies signatures lazily, which is irrelevant here.

---

## 13. Class-by-class map

| Java | Rust module / type | Key items |
|---|---|---|
| `LauncherCore` (main, restart loop, lifecycle) | `mmcl-app::lifecycle` (`Lifecycle` singleton, `run_restart_loop`) | `main()`, `handle_second_launch`, `close_app`, `restart_app(err)`, `relaunch_app`, `reset_and_relaunch`, `cleanup()`, `move_aside_for_reset(root, stamp)` (pure, tested), `spawn_relaunch(exe)`, `pending_uri` slot, `pending_mmcjson` queue |
| `LauncherCore.play/doModpackSelection/performClientLogin/onGameExited/...` | `mmcl-app::launch_flow` (or the game-session crate) | Same names in snake_case. These move out of the core. |
| `LauncherCore.attachHeadlessTrackerLogging` | `mmcl-session::headless_log` | 3000 ms per-step throttle |
| `LauncherSession` | `mmcl-app::session::Session { args, previous_error, exit: oneshot / (Mutex<bool>, Condvar) }` | `run()` |
| `LauncherArgs` | `mmcl-core::args::{LauncherArgs, ModeAction}` | `parse(&[String], tui: bool) -> LauncherArgs` (pure) |
| `config.GameModeManager` | `mmcl-core::mode` | `current()`, `set()`, `infer()`, `is_client()`, `is_server()` |
| `config.ConfigStore` | `mmcl-config::store::ConfigStore` | `open`, `get_*`, `get_or_init_*`, `mutate`, `snapshot`, `replace`, `schedule_write`, `flush`, `write_now` |
| `config.ConfigManager` + six slices | `mmcl-config::Config` (typed facade) plus `mmcl-config::migrate` | Every key in §7.6; `migrate_default_jvm_args(&mut Map) -> bool` (pure) |
| `config.AuthTokenStore` | `mmcl-config::secrets` | Uses the `MachineSecretCipher` trait from the auth crate (injected) |
| `consts.*` | `mmcl-core::consts::{build, ports, urls, paths, modpack, runtime, forge, manifest, gui}` | |
| `consts.localization.LocalizationManager` | `mmcl-i18n::{I18n, t!, tf!}` | `get`, `get_or`, `format`, `current_locale`, `set_locale` |
| `consts.localization.LocaleBootstrap` | `mmcl-i18n::locale::{os_locale, resolve, detect_os}` | |
| `consts.localization.SupportedLocales` | `mmcl-i18n::SUPPORTED: &[(tag, display)]` | |
| (Java's `ResourceBundle` / `Properties`) | `mmcl-i18n::properties::parse`, `mmcl-i18n::bundle` | |
| (Java's `MessageFormat`) | `mmcl-i18n::msgfmt` | |
| `files.LocalPathManager` + `LocalPathConstants` | `mmcl-core::paths::LauncherPaths` | §4 |
| `files.SynchronizedFileManager` | `mmcl-core::filelock::file_lock` | |
| `files.Logger` | `mmcl-log::{Logger, init_file_sink, shutdown, rotate, UiSink}` plus `tracing` macros | |
| `utilities.SensitiveDataRedactor` (consumed) | `mmcl-log::redact::{redact, redact_strict}` | |
| `utilities.StartupDiagnostics` (consumed) | `mmcl-log::startup_diag` | |
| `utilities.FilePermissions` (consumed) | `mmcl-core::fsx::owner_only(path, is_dir)` | |
| `exceptions.*` | `mmcl-core::error::Error`, `mmcl-scan::ScanBlocked` | |
| `security.ScanExclusionPolicy` | `mmcl-scan::exclusion` | `rejection_reason`, `filter_untrusted` |
| `security.SupplementalScanner` | `mmcl-scan::supplemental` | `scan_folder`, `scan_jar`, `Finding`, `Kind`, `Severity` plus the pure helpers |
| `me.cortex.jarscanner.*` | `mmcl-scan::neko::{run, scan_jar, scan_class, check_stage2, Results}` | |
| (ASM) | `mmcl-scan::classfile` | |

---

## 14. Proposed crates and public API sketch

```
rust/crates/
  mmcl-core      paths, mode, args, consts, build info, file locks, owner-only perms, atomic write, Error
  mmcl-i18n      properties parser, bundle loader (embedded), locale resolve, MessageFormat subset, t!/tf!
  mmcl-log       tracing layer, file sink + rotation, redactor, startup diagnostics, UiSink hook
  mmcl-config    ConfigStore + typed Config + migrations + secrets slot
  mmcl-scan      classfile parser, nekodetector, supplemental scanner, exclusion policy, report text
  mmcl-app       (exists) main(), lifecycle, session, wiring
```

Dependency order: `core` ← `i18n` ← `log` ← `config` ← `scan` ← everything else. `log` depends on `i18n` only for localized log messages; the format layer itself must not need it.

### `mmcl-core`

```rust
pub mod build {
    pub struct BuildInfo { pub is_dev: bool, pub app_name: &'static str, pub app_name_trimmed: &'static str,
                           pub version: &'static str }
    pub fn info() -> &'static BuildInfo;
}
pub mod mode {
    #[derive(Copy, Clone, Eq, PartialEq, Debug)] pub enum GameMode { Client, Server }
    impl GameMode { pub fn as_str(self) -> &'static str; }
    pub fn current() -> Option<GameMode>;
    pub fn set(m: GameMode);
    pub fn infer() -> GameMode;                    // sets + returns
    pub fn is_client() -> bool; pub fn is_server() -> bool;
}
pub mod args {
    pub enum ModeAction { Client, Server, Infer, None }
    pub struct LauncherArgs { pub mode_action: ModeAction, pub modpack_selection: String,
                              pub pending_uri: Option<String>, pub invalid: bool }
    impl LauncherArgs { pub fn parse(args: &[String], tui_enabled: bool) -> Self; }
    pub fn tui_requested(args: &[String]) -> bool;               // --cli / --tui
    pub fn is_launcher_uri(s: &str) -> bool;                      // case-insensitive "mmcl://"
    pub enum PreSession { Mcp, DiagManifest(String), Normal }
    pub fn pre_session(args: &[String]) -> PreSession;
}
pub mod paths {
    #[derive(Clone)] pub struct LauncherPaths { root: PathBuf }
    impl LauncherPaths {
        pub fn for_mode(mode: GameMode) -> Self;   // client: home/DATA_DIR_NAME, server: startup cwd
        pub fn client() -> Self;                   // always client root (pre-mode code)
        pub fn root(&self) -> &Path;
        pub fn config_dir(&self) -> PathBuf; pub fn config_file(&self) -> PathBuf;
        pub fn metadata_dir(&self) -> PathBuf; pub fn installs_dir(&self) -> PathBuf;
        pub fn logs_dir(&self) -> PathBuf; pub fn runtime_dir(&self) -> PathBuf;
        pub fn shared_assets_dir(&self) -> PathBuf;
        pub fn client_token_file(&self) -> PathBuf; pub fn remembered_account_file(&self) -> PathBuf;
        pub fn update_info_file(&self) -> PathBuf; pub fn mc_version_manifest_file(&self) -> PathBuf;
        pub fn session_log_file(&self, mode: GameMode, now: chrono::DateTime<Local>) -> PathBuf;
    }
    pub fn current() -> LauncherPaths;             // panics in debug if mode unset (early-path trap guard)
}
pub mod filelock { pub fn file_lock(path: &Path) -> Arc<parking_lot::Mutex<()>>; pub fn key_for(path:&Path)->String; }
pub mod fsx {
    pub fn owner_only(path: &Path, is_dir: bool) -> io::Result<()>;
    pub fn atomic_write(target: &Path, bytes: &[u8], owner_only: bool) -> io::Result<()>; // tmp+fsync+perm+rename
    pub fn move_aside_for_reset(root: &Path, stamp_ms: u128) -> io::Result<Option<PathBuf>>;
}
pub mod error { #[derive(thiserror::Error, Debug)] pub enum Error { ... } }
```

### `mmcl-i18n`

```rust
pub fn init(locale: LanguageIdentifier);                  // once, after config load
pub fn set_locale(locale: LanguageIdentifier);            // runtime switch (ArcSwap)
pub fn current_locale() -> LanguageIdentifier;            // locale of the most specific bundle found
pub fn get(key: &str) -> Cow<'static, str>;               // missing → key
pub fn get_or<'a>(key: &str, fallback: &'a str) -> Cow<'a, str>;
pub fn format(key: &str, args: &[FmtArg]) -> String;      // §10.4 semantics
#[macro_export] macro_rules! t  { ($k:literal) => { $crate::get($k) } }
#[macro_export] macro_rules! tf { ($k:literal, $($a:expr),+) => { $crate::format($k, &[$($crate::FmtArg::from($a)),+]) } }
pub enum FmtArg<'a> { Str(Cow<'a, str>), Int(i64), Float(f64), Bool(bool) }
pub mod locale {
    pub fn os_locale() -> &'static LanguageIdentifier;    // snapshot at first call
    pub fn detect_os() -> LanguageIdentifier;             // C/POSIX/und → en-US
    pub fn resolve(override_tag: &str) -> LanguageIdentifier;
}
pub const SUPPORTED: &[(&str, &str)];                     // (bcp47 tag, native display name)
pub mod properties { pub fn parse(src: &str) -> IndexMap<String, String>; }
pub mod msgfmt { pub fn format(pattern: &str, args: &[FmtArg], locale: &LanguageIdentifier) -> Result<String, PatternError>; }
pub mod bundle { pub fn candidates(l: &LanguageIdentifier) -> Vec<String>; /* "pt_BR","pt" */ }
```

Hold the bundle store in `arc_swap::ArcSwap<Active{ map: HashMap<&'static str, String>, locale }>`, so lookups take no lock. Parse the embedded bundles lazily on first use (about 2,500 entries per file, a few ms). Optionally pre-parse at build time with `build.rs` into `phf` maps for faster startup.

### `mmcl-log`

```rust
pub trait UiSink: Send + Sync {                      // set by mmcl-app once the GUI exists
    fn error_modal(&self, msg: &str);                // blocking (must never be called holding locks)
    fn error_async(&self, msg: &str);
    fn error_multiline(&self, msg: &str);
    fn error_retry(&self, msg: &str, retry: &str) -> bool;
    fn warn_toast(&self, title: &str, msg: &str);
    fn has_window(&self) -> bool;
}
pub fn install_console(app_name: &'static str, is_dev: bool, tui_file_only: bool);
pub fn init_file_sink(file: &Path) -> io::Result<()>;    // idempotent; rotate >10MiB; 0600; 5 s flush
pub fn shutdown();                                     // flush+close
pub fn set_ui_sink(s: Arc<dyn UiSink>); pub fn clear_ui_sink();
pub fn enable_config_backed_debug(enabled: bool); pub fn set_debug_enabled(enabled: bool);
pub fn std(msg: &str); pub fn debug(msg: &str); pub fn warn_silent(msg: &str); pub fn error_silent(msg: &str);
pub fn warn(msg: &str); pub fn error(msg: &str); pub fn error_async(msg: &str); pub fn error_multiline(msg: &str);
pub fn error_confirm_retry(msg: &str, retry: &str) -> bool;
pub fn throwable(e: &(dyn std::error::Error + 'static));
pub fn warn_silent_err(prefix: &str, e: &dyn std::error::Error);
pub fn rotate(file: &Path);                           // pub(crate) + test
pub mod redact { pub fn redact(s: &str) -> Cow<str>; pub fn redact_strict(s: &str) -> Cow<str>; }
pub mod startup_diag { pub fn record(msg: &str); pub fn record_err(msg: &str, e: &dyn Error);
                       pub fn record_launch(argc: usize); pub fn install_panic_hook(); pub fn log_path() -> PathBuf; }
```

### `mmcl-config`

```rust
pub struct ConfigStore { /* Arc<Inner{ path, doc: Mutex<Map<String,Value>>, write_lock: Mutex<()>, debounce: Debouncer }> */ }
impl ConfigStore {
    pub fn open(file: PathBuf, hooks: StoreHooks) -> Self;      // load / quarantine / defaults / migrate
    pub fn get_string(&self, k: &str, d: &str) -> String; pub fn get_bool(&self, k:&str, d:bool)->bool;
    pub fn get_i32(&self, k:&str, d:i32)->i32; pub fn get_i64(&self,k:&str,d:i64)->i64; pub fn get_f64(&self,k:&str,d:f64)->f64;
    pub fn get_or_init_string(..)->String; /* bool, i32, i64 */
    pub fn mutate<R>(&self, f: impl FnOnce(&mut Map<String, Value>) -> R) -> R;   // + schedule_write
    pub fn mutate_durable<R>(&self, f: impl FnOnce(&mut Map<String, Value>) -> R) -> R; // + write now
    pub fn snapshot(&self) -> Map<String, Value>;
    pub fn replace(&self, doc: Map<String, Value>);             // import (then migrate + flush)
    pub fn schedule_write(&self); pub fn flush(&self);          // flush writes if dirty (fixes bug 7.9.1)
}
pub struct StoreHooks { pub on_error_async: Box<dyn Fn(&str)+Send+Sync>, pub on_debug_changed: Box<dyn Fn(bool)+Send+Sync> }
pub struct Config { store: ConfigStore }        // typed facade, one method pair per key (§7.6), macro-generated
impl Config {
    pub fn min_ram_mb(&self) -> i64; pub fn set_min_ram_mb(&self, v: i64); /* … every key … */
    pub fn custom_jvm_args(&self) -> String;                     // validated on read
    pub fn set_custom_jvm_args(&self, v: &str) -> Result<(), JvmArgsError>;  // validated, durable
    pub fn scan_frequency_for_pack(&self, url: &str) -> Option<ScanFrequency>;
    pub fn effective_scan_frequency_for_pack(&self, url: &str) -> ScanFrequency;
    pub fn account_override_for_pack(&self, key: &str) -> Option<String>;
    pub fn curseforge_api_key(&self, cipher: &dyn SecretCipher) -> Option<String>;
    pub fn export_to(&self, dest: &Path) -> bool; pub fn import_from(&self, src: &Path) -> bool;
}
pub mod migrate { pub const CONFIG_VERSION: i64 = 7; pub fn migrate(doc: &mut Map<..>) -> bool;
                  pub fn migrate_default_jvm_args(doc: &mut Map<..>) -> bool; }
pub mod jvm_args { pub fn is_clean(s: &str) -> bool; pub fn require_clean(s: &str) -> Result<&str, JvmArgsError>; }
pub fn global() -> &'static Config;   // set once by mmcl-app after the mode is known (OnceLock); panics if unset
```

`ScanFrequency` lives in `mmcl-config`, or in a shared `mmcl-model` crate if the modpack spec defines it, with `serde` as `SCREAMING_SNAKE_CASE`.

### `mmcl-scan`

```rust
pub mod exclusion { pub fn rejection_reason(ex: &str) -> Option<String>; pub fn filter_untrusted(v: &[String]) -> Vec<String>; }
pub mod supplemental {
    pub enum Severity { High, Medium }
    #[derive(Serialize, Deserialize)] #[serde(rename_all = "SCREAMING_SNAKE_CASE")] pub enum Kind { ... }
    pub struct Finding { pub severity: Severity, pub kind: Kind, pub file: PathBuf, pub file_sha256: Option<String>,
                         pub inner_sha256: Option<String>, pub locator: String, pub message: String }
    pub fn scan_folder(root: &Path, excludes: &[String], threads: usize, cancel: &CancelToken) -> io::Result<Vec<Finding>>;
    pub fn scan_jar(path: &Path) -> io::Result<Vec<Finding>>;
    // pub(crate) + #[cfg(test)] pub: normalize_exclusions, is_excluded, looks_like_real_ip, is_false_positive_ip,
    // is_ip_url_host, is_in_legit_native_path, has_legit_native_filename, sha256_hex
}
pub mod neko {
    pub struct Results { pub stage1: Vec<String>, pub stage2: Vec<String> }
    pub fn run(threads: usize, dir: &Path, emit_walk_errors: bool, excludes: &[String],
               log: &(dyn Fn(&str) + Sync), progress: Option<&(dyn Fn(f64, f64) + Sync)>,
               cancel: &CancelToken) -> Result<Results, NekoError>;
    pub fn scan_class(bytes: &[u8]) -> bool;
    pub fn check_stage2(env: &Stage2Env) -> Vec<String>;   // env injected for tests (APPDATA, HOME, os)
}
pub mod classfile { pub struct ClassFile<'a>{..}; pub fn parse(b: &[u8]) -> Result<ClassFile, ClassError>;
                    pub struct Insn { pub op: u8, pub kind: InsnRef } /* resolved operands */ }
pub struct ScanBlocked { pub stage1_and_high: Vec<String>, pub stage2: Vec<String>, pub pack_name: Option<String>, pub pack_root: Option<PathBuf> }
impl ScanBlocked { pub fn message(&self) -> String; pub(crate) fn clean_finding_line(raw: &str, root: Option<&Path>) -> String; }
```

`cancelScanIfRunning` (`Main.executorService.shutdownNow()`) becomes a `CancelToken` that is checked between jars. Rust has no interrupts.

### `mmcl-app` lifecycle

```rust
pub struct Lifecycle { transition: AtomicBool, restart: Mutex<Option<RestartRequest>>, session_exit: Mutex<Option<Sender<()>>> }
pub enum RestartRequest { InProcess { error: Option<String> } }
impl Lifecycle {
    pub fn close(&self); pub fn restart(&self, error: Option<String>);
    pub fn relaunch(&self); pub fn reset_and_relaunch(&self);
    fn cleanup(&self);                 // §6.7 order, each step isolated; bounded steps with 3 s timeout
}
pub fn set_pending_uri(uri: String); pub fn take_pending_uri() -> Option<String>;
pub fn add_pending_mmcjson(p: PathBuf); pub fn take_pending_mmcjson() -> Vec<PathBuf>;
```

Threading model: GPUI owns the main thread (`Application::new().run(...)`). The restart loop runs on a dedicated `launcher-session` thread that talks to GPUI through `AsyncApp` handles and channels. An "in-process restart" closes and rebuilds windows but keeps the GPUI app alive. Server and TUI modes never start GPUI, and the loop runs on the main thread.

---

## 15. External crates

| Purpose | Crate |
|---|---|
| JSON config (insertion order) | `serde`, `serde_json` with `preserve_order`, optional `json5` fallback reader |
| Locks / atomics | `parking_lot`, `arc-swap`, `dashmap` (or `Mutex<HashMap>`) |
| Errors | `thiserror`, `anyhow` |
| Logging | `tracing`, `tracing-subscriber` (custom Layer), `regex` (redactor) |
| Time | `chrono` (local time stamps for log and backup names) |
| Home dir | `dirs` |
| OS locale | `sys-locale` |
| Language tags | `unic-langid` or `icu_locale_core` |
| Number formatting (`MessageFormat` numbers) | `icu_decimal` + `fixed_decimal` (ICU4X 2.x, compiled data); fallback: a hand table |
| Properties | hand-written (recommended) or `java-properties` |
| Hashing | `sha2`, `hex` |
| Zip | `zip` |
| Class files | hand-written (recommended); alternatives `cafebabe`, `noak` |
| Regex with look-around (IPv4 rule) | hand-rolled scanner (recommended) or `fancy-regex` |
| Walk / parallel | `walkdir`, `rayon` |
| Temp-file atomic replace | `tempfile` (optional) |
| Windows APIs (AppUserModelID, DLL dirs, DPI, ACLs, ShellExecute) | `windows` (or `windows-sys`) |
| Signals for flush on SIGTERM / Ctrl-C | `signal-hook` or `ctrlc` |
| Pre-UI native dialog (second launch) | `rfd` |
| Build-time git version | `build.rs` with `std::process::Command` (or `vergen-git2`) |
| Tests | `tempfile`, `insta` (snapshot of scan messages), `proptest` (properties / msgfmt), `cargo-fuzz` (classfile) |

What has no good Rust equivalent:
- **ASM:** replaced by a small custom class-file decoder.
- **Java `MessageFormat`, `ChoiceFormat` and `ResourceBundle`:** they must be reimplemented. Fluent and `rust-i18n` use different formats, and the bundles are shared with Java.
- **The JavaFX toolkit prestart and system properties:** not needed.
- **The JVM shutdown hook:** replaced by explicit flushes on every exit path plus signal handlers.
- **Thread interruption:** replaced by cancel tokens.
- **The tee of `System.out` capturing all library output:** replaced by a `tracing` subscriber; stray `println!` in dependencies stays on the console only.
- **An always-on-top owner-less dialog before any window** (`rfd` cannot guarantee always-on-top): on Windows, use `MessageBoxW` with `MB_TOPMOST | MB_SETFOREGROUND` directly.

---

## 16. Tests to port (from `src/test/java`)

| Java test | Rust test | What it pins |
|---|---|---|
| `LauncherArgsTest` (20 cases) | `mmcl-core/tests/args.rs` | Every row of the §5.2 table: case-insensitive `-c`/`-s`; null = empty; a bare name sets no mode; two bare names, or three or more args, are invalid; an invalid result has no mode and no pack; a deep link wins from any position and implies client; a non-`mmcl` URI is not a link; TUI implies client, strips the flags, accepts `--cli`, takes the last bare token, is never invalid; a deep link beats TUI. |
| `LauncherResetMoveAsideTest` (3) | `mmcl-core/tests/reset.rs` | Moves to `<name>.reset-<stamp>` with the contents intact; a missing folder returns None; a failed rename leaves the folder in place (simulate by making the target exist as a non-empty dir, or with a read-only parent on Unix). |
| `ConfigStoreAccessorTest` (12) | `mmcl-config/tests/accessors.rs` | Every row of the §7.4 coercion table; a plain get does not mutate; a fallback does not overwrite; get-or-init does not overwrite an existing value; a snapshot is independent; `mutate` is live. |
| `ConfigManagerJvmArgsMigrationTest` (8) | `mmcl-config/tests/migrate_jvm.rs` | The new default uses `ExplicitGCInvokesConcurrent`; the old default is replaced; whitespace is trimmed; customized values are untouched; the new default is untouched; idempotent; absent or non-string values are left alone; old and new differ only in that flag. |
| `AccountOverrideConfigTest` (4) | `mmcl-config/tests/account_override.rs` | Read by pack key; no entry → None; blank key or value → None; malformed section → None. |
| (new) | `mmcl-config/tests/load.rs` | Corrupt file shapes (truncated, zero bytes, array, primitive) are quarantined to `.corrupt-*` and replaced by first-run defaults; the v5 and v6 migrations (host gate, non-string elements kept); a write is atomic (no `.tmp` left, perms 0600 on Unix); debounce coalesces writes; `set_durable` persists without a pending write (regression for §7.9(1)); a Rust-written file round-trips through a captured Java-written sample. |
| `LocalizationManagerTest` (20) | `mmcl-i18n/tests/manager.rs` | Known legacy and dot keys; missing key → the key; null → `""`; `get_or` cases; format with 1, 2 and 3 args; no args → raw `{0}`; too few args → literal `{n}`; extra args ignored; missing key without braces drops the args; malformed → `template [a, b]`; apostrophes survive (`l'outil {0}`); templates without `'` are untouched. |
| `DisplayStringsBundleParityTest` (8) | `mmcl-i18n/tests/parity.rs` (reads the same files) | Every supported locale resolves its own file (underscore naming); no missing keys; no orphan keys; placeholder index sets match English; line-break counts equal, with no stray backslashes; no `''`; leading spaces kept (except ja and zh); no XML entities; every `choice` formats for n = 0, 1, 5 with no leftover `{` or `|` and containing `n`. |
| `LocalizationKeyReferencesTest` | `mmcl-i18n/tests/key_refs.rs` | Scan `rust/crates/**/*.rs` for `t!("…")`, `tf!("…", …)`, `get("…")` and `format("…", …)` literals and assert each key exists in English. Better: make `t!` a proc-macro that checks the key at compile time against the embedded English file. |
| (new) | `mmcl-i18n/tests/properties_golden.rs` | Parse each bundle and compare with a JSON dump generated **once by Java** (`Properties.load`), committed as a fixture. This catches escape and continuation divergence. |
| (new) | `mmcl-i18n/tests/locale.rs` | `resolve("")` gives the OS locale; `"und"` and garbage give the OS locale; `C`, `POSIX` and `C.UTF-8` give `en-US`; the candidate chains for `pt-BR`, `zh-Hans-CN` (→ `zh_CN`), `zh-TW`, `zh` (→ root), `pt-PT` (→ root), `fr-CA` (→ `fr`). |
| `LocalPathManagerClientConfigTest`, `LocalPathManagerClientServerPathsTest` (+ subprocess harness) | `mmcl-core/tests/paths.rs` | The client config path is home-anchored even with no mode set; client and server roots differ; every getter composes correctly. In Rust, no subprocess is needed: `LauncherPaths::for_mode` takes the mode explicitly, plus an override root for tests. |
| `LoggerRotationTest` (5) | `mmcl-log/tests/rotate.rs` | No backups → `.1`; shifts `.1` → `.2`; at capacity deletes `.3` and shifts; gaps shift only the files that exist; calling twice is safe. |
| `SynchronizedFileManagerTest` (5) | `mmcl-core/tests/filelock.rs` | Same path → same `Arc` (`Arc::ptr_eq`); relative and absolute collapse; `a/./b/../c` normalizes; case variants collapse only on Windows (`#[cfg]`). |
| `ScanExclusionPolicyTest` (7) | `mmcl-scan/tests/exclusion.rs` | Protected roots in all forms (`./mods/`, `MODS`, `mods\sub`, `/config`); pack-root forms; traversal; benign entries allowed; the filter keeps the original form; null and empty handled. |
| `SupplementalScannerTest` (~35) | `mmcl-scan/tests/supplemental.rs` | Every pure helper's vectors (IP octet range, FP ranges, DNS resolvers, trailing zeros, the version-string heuristic, the URL-host position, legit native paths and filenames, `normalize_exclusions`, `is_excluded`). `scan_jar` on synthetic jars: exe and script HIGH; `.js` not flagged; MEDIUM native outside a known path; a bare `.dll` not flagged; natives jar suppression still flags an exe; webhook, paste-host and credential-file HIGH; `usercache.json` not flagged; loopback and multicast not flagged; clipboard pattern only when both APIs are present. `scan_folder`: built-in exclusions skipped, a manifest cannot exempt `mods`, a legitimate exclusion is honored. The Java tests build class bytes with ASM; **for Rust, commit small `.class` fixtures** (generated once from Java, or hand-assembled with a tiny test-only class writer) under `mmcl-scan/tests/fixtures/`. |
| (new) | `mmcl-scan/tests/neko.rs` | `scan_class` positives for SIG1, SIG2 and SIG3 (fixture classes); negatives; the SIG3 "last instruction" quirk documented; a malformed or very new class version is not a panic; `check_stage2` with injected env and a temp dir (both fixed and parity behaviours). |
| (new) | `mmcl-scan/tests/blocked_message.rs` | `ScanBlocked::message`: empty, one issue (singular), many with de-duplication, root stripping with both separator styles. |
| (new) | `mmcl-scan` fuzz target | `classfile::parse` never panics. |

---

## 17. Edge cases, Java quirks, and decisions

1. **Bare `launcher <modpack>` (mode `None`):** the mode stays unset, so paths resolve to the CWD. `configureLogger` then calls `getCurrentGameMode().getStringName()` on null, which throws a NullPointerException, and the session dies. Even without that, `doModpackSelection` would match neither the client nor the server branch and wait forever. This is the documented-as-deliberate `NONE` behaviour, but in practice it is broken. **Recommendation for Rust:** treat `None` as `Infer` (open question).
2. **The restart loop re-uses the original argv**, so after any in-process restart (logout, account switch), a CLI-named pack auto-launches again. Recommendation: clear `modpack_selection` after the first session.
3. The pending deep link is consumed once and never re-dispatched after a restart (correct). A newer URI arriving before dispatch overwrites the older one (last wins).
4. **`rgbBackend` validation omits `corsair` and `asus-aura`**, so selecting them in a UI would silently revert to `auto`. Check with the RGB spec whether these are selectable. If they are, add them to the accepted set in **both** launchers.
5. **Stage 2 checks** never inspect the real Startup folder or the real `~/.config/.data/lib.jar` (§12.4). Fix this in Rust (open question).
6. **Neko exclusion matching** is separator-sensitive on Windows (§12.4). Unify it with supplemental.
7. `ModpackScanDetectionException`, the conflict dialog and the `--diag-manifest` output are hardcoded English. Localize them in Rust (except the diag output).
8. Writing NaN window bounds would kill the whole config write in Java (§7.1). Rust writes `null`.
9. `lastModPack` holds a pack *name* when set by the auto-launch path, but a URL elsewhere in the docs. Keep it opaque and coordinate with the modpack spec.
10. Language changes: Java relaunches the process (static finals). Rust switches live (§10.6).
11. `relaunch` needs "the installed exe". In Rust, `current_exe()` always exists. Consider an installed build to be one that is not running from `target/` and does not have `MMCL_DEV_NO_RELAUNCH` set. When it is not installed, use the in-process restart, matching Java's dev fallback.
12. Java logs the `GameMode` set and inferred lines through the logger **before** the file sink exists, so they reach only the console. Same in Rust, unless the logger buffers early lines; optionally buffer them and replay them into the file on `init_file_sink`.
13. `debug` lines before `enableConfigBackedDebugLogging` are dropped in release builds (by design).
14. Optional i18n improvement (not parity): map `pt-*` to `pt_BR` and plain `zh` to `zh_CN` instead of falling back to English.
15. Number grouping in `format` makes some log lines read `3,000` ms. Keep it for parity, or pass strings at those call sites.
16. `Results.stage1` entries are `Path.toString()` of the walked path (absolute, because the root is absolute). The block message strips the pack root to make them relative.
