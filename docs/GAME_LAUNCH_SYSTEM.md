# Game Launch System

Technical documentation for the Minecraft game launch pipeline, covering manifest
resolution, Forge library handling, classpath assembly, and argument construction.

## Overview

The game launch system orchestrates the full sequence from modpack selection to process
launch. It resolves Mojang version manifests, downloads and verifies all libraries and
assets, handles Forge mod loader integration, assembles the JVM classpath, constructs
launch arguments with placeholder replacement, and spawns the game process.

Key source files:
- `game/modpack/GameModPack.java` -- main orchestrator
- `game/modpack/GameModLoaderForge.java` -- Forge installer extraction and processor execution
- `game/modpack/manifests/GameVersionManifest.java` -- Mojang version manifest (v2)
- `game/modpack/manifests/GameLibraryManifest.java` -- library and asset resolution
- `game/modpack/manifests/GameAssetManifest.java` -- game asset downloads
- `game/modpack/manifests/ManifestRuleUtilities.java` -- rule evaluation and argument flattening

All paths are relative to `src/main/java/com/micatechnologies/minecraft/launcher/`.

## Architecture

```
  User selects modpack
         │
         v
  ┌──────────────────┐
  │   GameModPack     │  buildModpackClasspath() orchestrates:
  │                   │
  │  1. Download mods,│     ┌──────────────────────────┐
  │     configs, etc. │     │  GameModLoaderForge       │
  │                   │     │                           │
  │  2. Forge libs ───┼────>│  Extracts version.json &  │
  │                   │     │  install_profile.json from │
  │                   │     │  Forge installer JAR       │
  │                   │     └──────────┬────────────────┘
  │                   │                │
  │  3. MC libs ──────┼────>┌──────────v────────────────┐
  │     & assets      │     │  GameVersionManifest      │
  │                   │     │  (piston-meta v2)         │
  │                   │     │         │                  │
  │                   │     │  ┌──────v──────────────┐  │
  │                   │     │  │ GameLibraryManifest  │  │
  │                   │     │  │ (client.json)       │  │
  │                   │     │  │       │              │  │
  │                   │     │  │ ┌─────v────────────┐│  │
  │                   │     │  │ │GameAssetManifest ││  │
  │                   │     │  │ └──────────────────┘│  │
  │                   │     │  └─────────────────────┘  │
  │                   │     └───────────────────────────┘
  │  4. Verify Java   │
  │  5. Forge procs   │  ← runForgeProcessors() patches client JAR
  │  6. Security scan │
  └────────┬──────────┘
           │
           v
  startGame(user, cancelled) builds the command line, spawns the game and returns its Process
```

## Launch Sessions & Concurrent Games

`LauncherCore.play(pack)` drives one launch. Several can run at once.

1. **Account.** `MCLauncherAuthManager.userForLaunch(override)` picks the account: the pack's
   *Launch as* override, else the default (see `AUTHENTICATION_SYSTEM.md`). A missing or
   signed-out account blocks the launch with a prompt rather than playing on another account.
   `LauncherCore.playAs(pack, uuid)` forces an account for one launch (used by MCP).
2. **Session.** A `GameSession` (`game/session/`) is created for the pack and account and
   registered with `GameSessionRegistry.tryRegister`, which checks `LaunchAdmission` atomically:

   | Rule | Why |
   |---|---|
   | One active game per pack | A pack has one install folder (saves, `options.txt`, mods, natives), and Windows locks a running game's jars |
   | One active game per account | Minecraft signs the older session out when an account logs in twice |

   A refused launch explains why; asking for a pack that's already running just shows its tab.
3. **Preparation.** The session carries the `LaunchProgressTracker`, which the Running Games window
   (`GUI_SYSTEM.md`) shows as the launch's steps. Cancellation is per session: the launcher polls
   the `BooleanSupplier` it was given (`session::isCancelled`), so cancelling one launch can't
   cancel another. Download retry and progress notices go to per-launch listeners
   (`NetworkUtilities.addRetryNoticeListener` / `addDownloadProgressListener`), and the taskbar
   bar averages every launch still preparing (`TaskbarProgressManager.setLaunchProgress`).
4. **Running.** `startGame` returns the `Process`. In the GUI the game's output is always piped
   (`ChildIoMode.PIPE`) and captured by a `GameLog`: two reader threads drain stdout/stderr for the
   game's whole life (an unread pipe stalls the game), redact tokens, keep a bounded buffer, and
   write `logs/game-<pack>-<timestamp>.log`. `session.attachProcess` moves the session to
   `RUNNING`, then to `EXITED` or `CRASHED` when the process ends. On a crash, `LauncherCore`
   attaches the newest crash report and brings the game's tab forward. Server mode inherits the
   terminal instead, and the TUI reads output itself (but registers sessions the same way).

### Launcher-wide effects with several games

| Effect | Behaviour |
|---|---|
| Keyboard RGB, Discord presence | `RunningGameFollower` shows the most recently started running game, and returns to the menu state only when the last game exits |
| Shared downloads (assets) | each attempt writes its own temp file (`NetworkUtilities.uniqueTempFile`) and is moved into place atomically |
| Java runtimes | a spawned game registers its runtime (`RuntimeManager.markInUse`); an update that would reinstall it waits, and deleting it is refused while in use |
| Verify / uninstall | refused for a pack that is launching or running (`GameSessionRegistry.isPackActive`) |
| Quitting the launcher | asks first: leave the games running (they keep going, but their logs stop being saved) or stop them |

## Optional Mods

A pack can offer mods that each player switches on or off: shader support that costs frame time,
a minimap some players do not want. They live in their own top-level manifest key,
`packOptionalMods`, not as a flag on a `packMods` entry. A launcher that does not know the key
ignores it, so it never installs these jars, and its mods-folder sweep removes any that a newer
launcher left behind. A flag inside `packMods` would be installed as a required mod by every older
launcher. (Issue #103.)

### Manifest format

```json
"packOptionalMods": [
  { "name": "AUSM", "remote": "https://…/ausm.jar", "local": "ausm.jar",
    "sha1": "…", "md5": "-1", "sha256": "…",
    "clientReq": true, "serverReq": false,
    "description": "Shader pack support. Slower terrain rendering.",
    "defaultEnabled": false,
    "requires": ["ausm-core.jar"],
    "conflictsWith": ["celeritas-extras.jar"] }
]
```

Entries have every `packMods` field plus:

| Field | Meaning | Absent means |
|---|---|---|
| `description` | Short text shown next to the toggle | empty |
| `defaultEnabled` | On for a player who has not chosen | `false` |
| `requires` | `local` names of other optional mods that must be on with this one | none |
| `conflictsWith` | `local` names of other optional mods that must be off while this one is on | none |
| `clientReq` | Installed on clients when on | `true` |
| `serverReq` | A dedicated server may install it (see Sides) | `false` |

A file may be in `packMods` or `packOptionalMods`, never both. An optional mod's `local` must be a
plain file name (`ausm.jar`, not `mods/ausm.jar`): the sync joins it to the `mods` folder as
written, so a folder would land the jar in `mods/mods/`, where the game does not load it; the sync
skips such an entry instead of installing it. `requires` and `conflictsWith` may only name
optional mods of the same pack. The editor refuses to save a manifest that breaks either
rule (`ModPackDocument.validateOptionalMods`), and `ModPackDocument.addMod` refuses a file that is
already an optional mod. Optional mods are identified everywhere by their `local` name normalised
with `ModPackDocument.normalizeModLocalPath` and compared ignoring case
(`GameOptionalMod.keyOf`), so `mods/AUSM.jar` and `ausm.jar` are the same mod.

Every manifest rewrite keeps the list: fork (`ModPackDocument.forkOf` copies the whole JSON), the
editor (it reads and writes `packOptionalMods` as a file list of its own, `FileList.OPTIONAL_MODS`,
and leaves the key out while it is empty), and the MCP `add_mod_to_modpack` /
`remove_mod_from_modpack` edits (they only rewrite `packMods`).

### The player's choice

Stored per pack in `configuration.json` under `optionalModsByPack`, keyed like
`accountOverrideByPack` by `GameModPack.getSettingsKey()`:

```json
"optionalModsByPack": {
  "https://example.com/alto.json": { "ausm.jar": true, "celeritas-extras.jar": false }
}
```

A mod with no entry follows its `defaultEnabled`. It is a player setting and is never written into
the manifest. Accessors: `ConfigManager.getOptionalModChoices` / `setOptionalModChoices`
(`ModPackConfig`). The choice is set from the detail modal's Advanced tab or the MCP
`set_optional_mod` tool, both through `OptionalMods.toggle`.

### Resolution rules (`OptionalModResolver`)

1. Each mod's wish is the stored choice, else `defaultEnabled`.
2. Mods are visited in manifest order. A wished-on mod is turned on together with everything it
   `requires`, transitively; a required mod is turned on even if its own wish is off.
3. If that group conflicts with a mod already on (conflicts are symmetric: either side naming the
   other is enough), or within itself, the whole group stays off. So when stored choices or
   defaults disagree, the mod listed earlier wins.
4. Names that are not optional mods of the pack are ignored.

A toggle (`OptionalModResolver.toggle`) stores only what the player decided:

- the toggled mod's own new state (`true` or `false`);
- turning a mod on: `false` for every mod that conflicts with it or with anything it requires
  (transitively), and for everything that needs one of those. The player chose this mod over them,
  so turning it off again does not bring them back;
- turning a mod off: `false` for every mod that needs it (transitively).

Requirements a toggle turns on are **not** stored. Rule 2 keeps them on while a mod that needs them
is on; once none is, each falls back to its own wish. So turning a mod on and then off again turns
its requirements back off, unless the player turned one on themselves (stored `true`), it is
`defaultEnabled`, or another mod that is on still needs it. Choices stored by builds before this
rule hold cascaded `true`s for requirements; they read as the player's own picks and are not
migrated.

The result lists every other mod that switched, in manifest order, with a reason and a cause. Each
cause is the first matching mod in manifest order:

| Reason | When | Cause |
|---|---|---|
| `REQUIRED_BY` | turned on | first mod on afterwards that directly requires it |
| `CONFLICT_CLEARED` | turned on, and nothing on requires it | first mod that was on, is off now and conflicts with it |
| `CONFLICTS_WITH` | turned off | first mod on afterwards that conflicts with it |
| `REQUIRES_OFF` | turned off and stored off by the toggle, with no conflict | first mod it directly requires that is off afterwards |
| `NO_LONGER_REQUIRED` | turned off without being stored (it fell back to its wish) | first mod that was on, is off now and directly requires it |

A mod turned on with no cause found is `REQUIRED_BY` with an empty cause. A mod stored off by the
toggle is checked for `CONFLICTS_WITH`, then `REQUIRES_OFF`; one that fell back is checked for
`NO_LONGER_REQUIRED`, then `CONFLICTS_WITH`; with no cause found either way it is `REQUIRES_OFF`
with an empty cause. A toggle the rules cannot satisfy (the group it needs conflicts with itself,
or the toggled mod would not end up in the requested state) is refused (`UNSATISFIABLE`) and
stores nothing; an unknown name is refused as `UNKNOWN`. Rule 3 only bites when a manifest update
adds a conflict after the player chose, or when defaults disagree.

### Sides

A client installs the resolved mods that are client-side (`clientReq`). A dedicated server has no
player to choose and no toggle UI: it ignores `optionalModsByPack`. It starts from the optional
mods that are both `serverReq: true` and `defaultEnabled: true`, in manifest order, and turns each
on together with everything it `requires` (transitively, whatever their own `defaultEnabled`). The
whole group is skipped when any mod in it is not `serverReq`, when two of its mods conflict, or when
it conflicts (in either direction) with a mod already selected, so the earlier manifest entry wins,
as on a client.

### Sync

`GameModPackFileSync.modsForSide` builds the mod list for a sync: every `packMods` entry, then the
optional mods `OptionalModResolver.selectForSide` picks. That list drives the download loop (each
enabled optional mod is downloaded and hash-checked like any mod), the post-sync existence check,
and the sweep's expected set. An optional mod that is off is not in the set, so `clearFloatingMods`
removes its jar. An optional entry whose file is also a `packMods` entry is skipped (the required
entry wins). A toggle takes effect on the next launch or sync.

`GameModPack.getVerifyFingerprint()` is what the verify fast path and the scan schedule compare
between launches: the manifest hash, extended with the keys of the optional mods that are on when
there are any. A pack with no optional mods on keeps exactly its manifest hash. Switching an
optional mod changes the fingerprint, so the next launch hash-verifies every file and the security
scan treats the pack as changed.

After a successful mod sync the launcher writes `.known_optional_mods` in the pack root (the keys
of every optional mod the manifest declared, one per line; written only for packs that have, or
had, optional mods). `PendingUpdateDiff` uses it to tell an optional mod
that is new in an update from one the player left off.

### Pending-update summary

`PendingUpdateDiff` lists changes to optional mods the player has on like any other mod change,
with their names in `Result.optionalNames()` so the modal tags them "(optional)". An optional mod
that is off and not in `.known_optional_mods` is listed as a new optional mod available, off
(`Result.newOptionalNames()`); a server-only optional mod is never offered this way on a client.
Changes to optional mods that are off are hidden, and an optional
jar on disk is never reported as removed.

## Manifest Resolution Chain

### Step 1: Version Manifest v2

`GameVersionManifest` fetches the master version list from
`piston-meta.mojang.com/mc/game/version_manifest_v2.json` and caches it. For a given
Minecraft version (e.g., `1.20.4`), it resolves the URL to that version's `client.json`.

Key methods:
- `getClientJson(String mcVersion)` -- downloads and caches per-version client.json
- `getRequiredJavaMajorVersion(String mcVersion)` -- reads `javaVersion.majorVersion`
- `getRequiredRuntimeComponent(String mcVersion)` -- reads `javaVersion.component`
- `getMinecraftLibraryManifest(String mcVersion, GameModPack parent)` -- creates a
  `GameLibraryManifest` from the resolved client.json

### Step 2: Library Manifest (client.json)

`GameLibraryManifest` parses the per-version client.json and handles:

- **Library filtering** -- evaluates platform rules via `ManifestRuleUtilities` to
  include only libraries for the current OS/arch
- **Native library resolution** -- resolves platform-specific classifiers (e.g.,
  `natives-windows`, `natives-linux-${arch}`)
- **Multi-threaded downloads** -- thread pool sized to
  `min(libraryCount, availableProcessors)`; SHA-1 verified
- **Post-download integrity gate** -- the declared hash is enforced on the bytes
  *actually received*, not just used as a "should I re-download?" trigger.
  `ManagedGameFile.downloadLocalFile` re-hashes each download (strongest-first
  SHA-256 → SHA-1 → MD5, bypassing the verify cache / FAST_PATH) and fails the
  launch on mismatch after a bounded retry; `RuntimeManager` and the Forge
  processor-library loader do the same. This stops a compromised mirror / corrupted
  transfer from being placed on the classpath, extracted, or executed.
- **Classpath building** -- `LinkedHashSet` for deduplication
- **Game side** -- libraries are resolved for the client or the server: an entry marked
  `"side": "client"` or `"server"` (Cleanroom marks its natives client-only) is left out
  on the other side. Mojang's client.json has no `side`.
- **Derived paths** -- a download without `path` (Cleanroom's legacy natives classifiers
  carry only `url` and `sha1`) gets the Maven path of the library name plus classifier
- **Modern vs legacy detection:**
  - `hasModernArguments()` -- checks for `"arguments"` field (modern) vs
    `"minecraftArguments"` (legacy)
  - `getJvmArguments()` -- reads `arguments.jvm` array with rule evaluation
  - `getGameArguments()` -- reads `arguments.game` array, falls back to
    `minecraftArguments`
  - `getLoggingConfig()` -- extracts `logging.client` with argument template and
    log config file URL

### Step 3: Asset Manifest

`GameAssetManifest` downloads game resources (textures, sounds, etc.) using the asset
index from client.json. Assets are stored in `objects/{hash_prefix}/{full_hash}` layout.

### Step 4: Rule Evaluation

`ManifestRuleUtilities` centralizes Mojang/Forge rule evaluation:

- **OS matching** -- name (`windows`/`osx`/`linux`), version (regex), arch (regex,
  or the host's normalized name written out in full: `x64`, `arm64`, `x86`, which
  Cleanroom's profile uses and no Java `os.arch` contains)
- **Host seam** -- every check takes a `ManifestRuleUtilities.Host` (OS family,
  `os.arch`, `os.version`); the one-argument forms use the running machine, so a
  manifest can be resolved for any platform in tests
- **Version range matching** -- `versionRange` with min/max for MC 26.1+ rule format;
  uses numeric component comparison
- **Feature matching** -- all features expected `false` (no demo mode, etc.)
- **Argument flattening** -- handles mixed primitive and object arrays with rule
  filtering and smart quoting (avoids quoting `${placeholder}` strings)

## Forge Integration

### Forge JAR Extraction

`GameModLoaderForge` downloads the Forge installer JAR and extracts:
- `version.json` -- Forge version metadata, main class, arguments
- `install_profile.json` -- library list, install processors, data mappings

### Forge Library Resolution

`getForgeLibrariesList()` handles complex library resolution:

1. Parse each library entry from `install_profile.json`
2. Check for `downloads.artifact` (direct URL) vs Maven coordinate-only entries
3. Build inferred paths from Maven coordinates (`group:artifact:version:classifier`)
4. Detect modern vs legacy Forge:
   - Modern: both base JAR and `forge-<ver>-universal.jar` embedded
   - Legacy: only universal JAR present
5. Resolve repository URLs (Mojang, Minecraft Forge Maven, Maven Central)
6. Handle embedded Maven artifacts inside the Forge JAR (`jar:` URLs)
7. Special URL overrides for specific libraries (scala, lzma, vecmath)

### Forge Install Processors (Modern Forge 1.13+)

`runForgeProcessors()` executes Forge's post-download patching pipeline:

1. Check for `PATCHED` marker to skip if already processed
2. Download processor libraries (from URLs or embedded in Forge JAR)
3. For each processor:
   - Read `Main-Class` from processor JAR manifest
   - Build classpath from processor + its dependencies
   - Resolve arguments: `{VARIABLE}` from data section, `[maven:coord]` to file path,
     or literal strings
   - Extract data files from Forge JAR to `forge-installer-data/` as needed
   - Execute via `ProcessBuilder` with inherited I/O
   - Verify exit code (throws on non-zero)

### Maven Coordinate Conversion

`mavenCoordToPath(String coord)` converts Maven coordinates to file paths:
```
net.minecraftforge:forge:1.15.2-31.2.50
  → net/minecraftforge/forge/1.15.2-31.2.50/forge-1.15.2-31.2.50.jar

net.minecraftforge:forge:1.15.2-31.2.50:universal
  → net/minecraftforge/forge/1.15.2-31.2.50/forge-1.15.2-31.2.50-universal.jar
```

Supports `@ext` suffix for non-JAR artifacts (default extension is `jar`).

## Cleanroom Integration

Cleanroom is the Forge 1.12.2 fork on Java 25 and LWJGL 3. A pack names it with
`"packModLoader": "cleanroom"` (any case) and puts the Cleanroom installer JAR in
`packForgeURL` / `packForgeHash` (or `packModLoaderURL` / `packModLoaderHash`); one
installer serves both sides and is kept at `bin/modpack.jar`.

The installer carries a **standalone** `version.json` (no `inheritsFrom`; its own
`javaVersion`, `assetIndex`, client and server downloads, and every library including
LWJGL 3, its natives and LWJGLY) and an `install_profile.json` with zero processors.
`GameModLoaderCleanroom` reads both into the pure `CleanroomProfile`, and
`GameModLoader.getStandaloneVersionManifest()` hands the launch that profile **in place of**
Mojang's 1.12.2 client.json (`GameLibraryManifest.standalone`, chosen by
`GameModPack.getMinecraftLibraryManifest()`):

- **Runtime** -- from the profile's `javaVersion` (`java-runtime-epsilon`, Java 25), for
  the runtime check and the launch. Vanilla 1.12.2 would say `jre-legacy`.
- **Libraries** -- only the profile's. No vanilla library is merged in, so LWJGL 2 never
  reaches the classpath; Mojang's version manifest is not fetched.
- **Loader classpath** -- the libraries the installer embeds (the universal jar: blank
  `url`, under the installer's `maven/`), verified against the listed SHA-1 and kept
  first. An installer missing one it lists is refused.
- **Natives** -- `bin/natives` is emptied before extraction, so LWJGL 2 natives from an
  earlier Forge launch of the pack cannot load; LWJGL 3's natives keep their directory
  structure (`windows/x64/org/lwjgl/lwjgl.dll`).
- **Minecraft version** -- the install profile's `minecraft` (`1.12.2`), never the
  standalone id (`Cleanroom-0.7.14-alpha`), which is the loader version.
- **Skipped** -- the LWJGL 2 ARM64 patch (and its JVM flags and mod disabling), Mojang's
  1.12 log4j config file (the no-lookups flag is still set), the "Game patching" step, and
  the main menu's idle client.json prefetch (`GameModPack.usesStandaloneLoaderProfile()`).
- **macOS** -- the client gets `-XstartOnFirstThread`, as the installer's own launcher
  profile does.
- **Server** -- assembled natively, as the installer's `server` mode does it (the
  installer is never run): the profile's libraries without the client-only natives, the
  server jar from `downloads.server`, `serverMainClass`, and `--tweakClass` per
  `serverTweakers` (`GameModLoader.getServerArguments()`). The server also gets
  `-Dfml.queryResult=confirm` (`GameModLoader.getServerJvmArguments()`), unless the pack or
  config already sets `fml.queryResult`: a pack without Cleanroom's companion mods Fugue and
  Scalar otherwise stops at a console prompt that nobody answers, the server watchdog kills it,
  and the restart loop repeats. A warning is logged when it is added. Clients keep the prompt,
  which the player answers in game.
- **Official-launcher export** -- the installer runs as `client --dir <.minecraft> --no-gui
  --yes --no-profile --java <java> --no-provision-java` (it has no `--installClient`); the
  exported profile uses the Java 25 runtime and carries the profile's JVM arguments.

## Classpath Assembly

`GameModPack.buildModpackClasspath()` progress allocation:

| Step | Progress | Description |
|---|---|---|
| 1. Modpack content | 15% | Mods, configs, resource packs, shader packs |
| 2. Forge libraries | 15% | Forge classpath (skipped for vanilla) |
| 3. MC libraries & assets | 20-40% | Minecraft classpath + asset downloads |
| 4. Java runtime | 15-20% | RuntimeManager.verifyRuntime() |
| 5. Forge processors | 10% | Patching pipeline (Forge only) |
| 6. Security scan | 10% | Malware detection via jarscanner |

Final classpath = Forge classpath + Minecraft classpath, joined by `File.pathSeparator`,
deduplicated via `LinkedHashSet`.

## Argument Construction

`GameModPack.startGame()` builds the full JVM command line:

### JVM Arguments (in order)

1. Custom user JVM args (`ConfigManager.getCustomJvmArgs()`)
2. RAM allocation: `-Xms{min}m -Xmx{max}m`
3. Log4j security config (see below)
4. Manifest JVM args (`libraryManifest.getJvmArguments()`)
5. Forge JVM args (module system flags, modern Forge only)
6. Legacy fallback: `-Djava.library.path` and `-cp` if no manifest JVM args
7. Main class
8. Game arguments

### Log4j Security (CVE-2021-44228)

`applyLog4jSecurityConfig()` applies version-specific mitigations:

| MC Version | Mitigation |
|---|---|
| 1.7 - 1.11.2 | Download `log4j2_17-111.xml` + `-Dlog4j.configurationFile=` |
| 1.12 - 1.16.5 | Download `log4j2_112-116.xml` + `-Dlog4j.configurationFile=` |
| 1.17+ | `-Dlog4j2.formatMsgNoLookups=true` (flag alone sufficient) |

### JVM Placeholder Replacement

| Placeholder | Replacement |
|---|---|
| `${natives_directory}` | Natives folder path (quoted on Windows) |
| `${classpath}` | Full classpath string (quoted on Windows) |
| `${launcher_name}` | `"MicaMinecraftLauncher"` |
| `${launcher_version}` | `"2025.1"` |
| `${version_type}` | `"release"` |
| `${classpath_separator}` | `File.pathSeparator` |
| `${library_directory}` | Libraries folder path |

### Game Argument Placeholder Replacement (Client Only)

| Placeholder | Replacement |
|---|---|
| `${auth_player_name}` | The launching account's username (the `User` passed to `startGame`) |
| `${version_name}` | MC version (vanilla) or Forge version |
| `${game_directory}` | Modpack root folder (quoted on Windows) |
| `${assets_root}` | Assets folder (quoted on Windows) |
| `${assets_index_name}` | Asset index version from manifest |
| `${auth_uuid}` | The launching account's UUID |
| `${auth_access_token}` | The launching account's access token, refreshed before launch if due |
| `${user_type}` | `"mojang"` |
| `${clientid}` | Empty string |
| `${auth_xuid}` | Empty string |
| `${user_properties}` | `"{}"` |

### Client-Specific Additions

- Window title: `--title "{packName}"`
- Window icon: `--icon "{logoPath}"`
- macOS: `-Xdock:icon`, `-Xdock:name`, `apple.laf.useScreenMenuBar`

## Modern vs Legacy Detection Summary

| Feature | Modern (1.13+) | Legacy (1.7-1.12) |
|---|---|---|
| Game arguments | `arguments.game` array | `minecraftArguments` string |
| JVM arguments | `arguments.jvm` array with rules | Manual `-Djava.library.path` + `-cp` |
| Forge structure | base JAR + universal JAR | universal JAR only |
| Forge processors | `install_profile.json` pipeline | None |
| Forge JVM args | `arguments.jvm` in version.json | None |

Detection method: `hasModernArguments()` checks for `"arguments"` field in client.json.
Forge modernity detected by presence of both base and universal JARs.

## Key Classes

| Class | File | Purpose |
|---|---|---|
| `GameModPack` | `game/modpack/GameModPack.java` | Main orchestrator: classpath assembly, game launch |
| `GameModLoaderForge` | `game/modpack/GameModLoaderForge.java` | Forge JAR extraction, library resolution, processor execution |
| `GameModLoaderCleanroom` | `game/modpack/GameModLoaderCleanroom.java` | Cleanroom installer, embedded universal jar, standalone version profile |
| `CleanroomProfile` | `game/modpack/CleanroomProfile.java` | Pure: Cleanroom's two profiles and the launch decisions that follow from them |
| `GameVersionManifest` | `game/modpack/manifests/GameVersionManifest.java` | Mojang v2 version manifest, client.json caching |
| `GameLibraryManifest` | `game/modpack/manifests/GameLibraryManifest.java` | Library filtering, downloads, classpath building |
| `GameAssetManifest` | `game/modpack/manifests/GameAssetManifest.java` | Game resource (texture/sound) downloads |
| `ManifestRuleUtilities` | `game/modpack/manifests/ManifestRuleUtilities.java` | Rule evaluation, argument flattening |
| `ManagedGameFile` | `game/modpack/ManagedGameFile.java` | Base class for downloadable game files with SHA-1 |
| `GameModPackProgressProvider` | `game/modpack/GameModPackProgressProvider.java` | Progress callback for multi-step operations |
| `GameSession` | `game/session/GameSession.java` | One launch: pack, account, phase, process, tracker, log, crash report, cancellation |
| `GameSessionRegistry` | `game/session/GameSessionRegistry.java` | Active and recently ended sessions; atomic admission |
| `LaunchAdmission` | `game/session/LaunchAdmission.java` | Pure: one game per pack and per account |
| `GameLog` | `game/session/GameLog.java` | Captures a game's output for its whole life; gap-free `subscribe` for late viewers |
| `RunningGameFollower` | `game/session/RunningGameFollower.java` | Keeps RGB and Discord on the newest running game |
| `GameOptionalMod` | `game/modpack/GameOptionalMod.java` | One `packOptionalMods` entry: description, default, requires, conflicts; optional-mod side defaults |
| `OptionalModResolver` | `game/modpack/OptionalModResolver.java` | Pure: which optional mods are on, what a toggle changes and why, which a side installs |
| `OptionalMods` | `game/modpack/OptionalMods.java` | Loads a pack's optional mods (stub-aware), reads and stores the player's choices, applies a toggle |
