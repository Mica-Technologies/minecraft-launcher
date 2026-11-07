# Launch pipeline: porting spec

Scope: everything between "the user pressed Play on a pack" and "a Java process is running
Minecraft". It covers Mojang version metadata (version manifest v2, client.json, asset index),
rule evaluation, library and native resolution, the Forge, NeoForge, Fabric and vanilla loaders,
Forge install processors, classpath assembly and dedup, JVM and game argument construction,
Java runtime selection and installation (Mojang and Bell-SW Liberica), and process start.

Java sources this spec replaces (all under `src/main/java/com/micatechnologies/minecraft/launcher/`):

| Java | Lines |
|---|---|
| `game/modpack/manifests/GameVersionManifest.java` | 317 |
| `game/modpack/manifests/GameLibraryManifest.java` | 909 |
| `game/modpack/manifests/GameAssetManifest.java` | 399 |
| `game/modpack/manifests/ManifestRuleUtilities.java` | 382 |
| `game/modpack/GameModLoader.java` (interface) | 137 |
| `game/modpack/GameModLoaderForge.java` | 1540 |
| `game/modpack/GameModLoaderNeoForge.java` | 100 |
| `game/modpack/GameModLoaderFabric.java` | 540 |
| `game/modpack/ForgeLibraryPlanner.java` | 202 |
| `game/modpack/MavenArtifactPath.java` | 227 |
| `game/modpack/GameModPackLauncher.java` | 1869 |
| `game/modpack/LoaderInstallerRunner.java` | 406 |
| `game/modpack/Lwjgl2ArmPatcher.java` | 571 |
| `game/modpack/GameLibrary.java`, `GameAsset.java` | ~200 |
| `files/RuntimeManager.java` | 1341 |
| `consts/RuntimeConstants.java` | 143 |
| `config/RuntimeConfig.java` (RAM and JVM-arg settings) | 131 |

It also leans on helpers owned by other subsystems; their contract is given here where the
pipeline depends on exact behaviour (`ManagedGameFile`, `SystemUtilities.extractJarFile`,
`ArchiveExtractor`, `ProcessUtilities`, `JarSigningStripper`, `VerifyState`, `DownloadExecutor`).

Conventions in this document:

- "Pack root" is `<launcher>/installs/<packSanitizedName>` (`GameModPackMetadata.getPackRootFolder`).
  `packSanitizedName` is the pack name with every char outside `[A-Za-z0-9]` removed. Several
  safety arguments below depend on that sanitising.
- `<launcher>` is the launcher data folder. Client mode: `~/.MicaMinecraftLauncher`. Server mode:
  the current working directory. The Rust build uses its own data folder for now, but the
  **layout under it must be identical** so the Rust launcher can later point at the Java one.
- `SEP` is the platform path separator (`\` or `/`) and `PATHSEP` the classpath separator
  (`;` on Windows, `:` elsewhere).
- "Java os.arch / os.version" means the values a JVM would report. Rule matching was written
  against them, so the Rust port must emulate them (section 4.3).

---

## 1. On-disk layout (must match the Java launcher byte-for-byte in names)

```
<launcher>/
├── config/
│   └── version.manifest                     ← Mojang version_manifest_v2.json (re-downloaded once per process)
├── metadata/
│   ├── client-<mcVersion>.json              ← client.json cache used for Java-version reads
│   └── log4j-configs/<sha1>.xml             ← shared Log4Shell configs, keyed by SHA-1
├── shared/
│   └── assets/
│       ├── indexes/<assetIndexId>.json      ← asset index (shared by every pack)
│       └── objects/<hh>/<sha1>              ← hashed asset objects (shared)
├── runtime/
│   ├── mojang-runtime-index.json            ← Mojang all.json cache
│   ├── jre-legacy/
│   │   ├── .version                         ← installed Liberica version string, e.g. "8u392+9"
│   │   ├── jre-legacy.api.json              ← cached Bell-SW API response
│   │   ├── <filename from API>.zip|.tar.gz  ← downloaded archive (kept, SHA-1 checked)
│   │   └── jre8u392/  or  jre8u392.jre/     ← extracted JRE (macOS gets the .jre suffix)
│   └── java-runtime-<x>/
│       ├── .version                         ← Mojang version name, e.g. "17.0.8"
│       ├── runtime-manifest.json            ← Mojang per-component file manifest
│       └── bin/java(.exe) | jre.bundle/Contents/Home/bin/java (macOS)
└── installs/<pack>/                         ← pack root, also the game's working dir and --gameDir
    ├── .verify_state.json                   ← FULL-verify / scan bookkeeping (VerifyState)
    ├── bin/
    │   ├── modpack.jar                      ← Forge/NeoForge installer JAR (MODPACK_FORGE_JAR_LOCAL_PATH)
    │   ├── fabric-profile.json              ← Fabric meta profile JSON
    │   ├── libraries.manifest               ← this pack's copy of client.json
    │   ├── minecraft.jar                    ← vanilla client (or server) jar
    │   ├── minecraft.jar.verified           ← "verified (and stripped if needed)" marker, empty file
    │   ├── natives/                         ← extracted native libs (re-extracted every launch)
    │   ├── logo.png, background.png
    ├── libraries/                           ← Maven layout; vanilla, loader and processor libs
    │   ├── arm64-natives/                   ← LWJGL2-on-ARM64 replacement jars (cache)
    │   └── forge-installer-data/<entry>     ← files extracted from the installer for processors
    ├── assets/virtual/<assetIndexId>/       ← flat legacy asset tree (virtual: true indexes)
    ├── resources/                           ← flat legacy asset tree (map_to_resources: true)
    ├── mods/ config/ resourcepacks/ shaderpacks/
    └── crash-reports/ logs/ saves/ options.txt … (written by the game)
```

Older builds kept `assets/objects` and `assets/indexes` under each pack. Each asset download
pass deletes those two folders in the background (section 6.4). Keep that one-shot migration.

---

## 2. Pipeline overview

```
LaunchRequest(pack, account, cancel token, progress tracker)
  │
  ├─ guard: pack.failed_load || pack.is_stub  → error (never launch a pack whose manifest wasn't loaded)
  ├─ decide verify mode (FULL | FAST_PATH) and install a PrepareContext on the pack
  │
  ├─ if modded: 3 parallel branches ──────────────────────────────────────────────┐
  │     A. modpack content sync (mods/config/resourcepacks/shaderpacks/initial files, images)
  │     B. loader libraries → loader classpath string
  │     C. vanilla libs + natives + client jar + assets → MC classpath; then Java runtime
  │   join (fail-fast: first error cancels siblings, abort+wait ≤10 s)                │
  │   D. if loader uses post-install steps (Forge/NeoForge): run install processors   │
  ├─ if vanilla: C only, sequential ───────────────────────────────────────────────┘
  │
  ├─ E. security scan (policy may skip)
  ├─ merge classpaths (loader wins by Maven coordinate; drop vanilla minecraft.jar when a
  │   Forge/NeoForge patched client is present)
  ├─ persist VerifyState on FULL success
  │
  ├─ build argv: [java] custom JVM args, -Xms/-Xmx, log4j, ARM flags, manifest JVM args,
  │   loader JVM args, legacy -Djava.library.path/-cp, main class, game args,
  │   placeholder substitution, --server/--port, --title/--icon, (macOS dock args)
  ├─ spawn (cwd = pack root, env scrubbed, CREATE_NO_WINDOW on Windows, stdout/stderr piped
  │   or inherited) → markInUse(runtime, child)
  └─ return Child
```

Progress weights the GUI expects (percent of each step's own bar): content 15 (mods) + 5
(configs); loader libs 15; MC libs 40 (vanilla) or 20 (modded); JRE 20 (vanilla) or 15
(modded); processors 10; scan 10. Inside steps: library list 5, each library 25/N, client jar 10,
each asset 50/N, each classpath add 10/N; Forge: asset list 20, each Forge lib 60/N, each
classpath add 20/N, each processor 10/N, each processor lib download 1.

---

## 3. Data formats

### 3.1 Version manifest v2

`GET https://piston-meta.mojang.com/mc/game/version_manifest_v2.json`

```json
{ "latest": {"release": "1.21.5", "snapshot": "…"},
  "versions": [ {"id": "1.21.5", "type": "release", "url": "https://piston-meta…/<sha1>/1.21.5.json",
                 "time": "…", "releaseTime": "…", "sha1": "<sha1 of client.json>", "complianceLevel": 1}, … ] }
```

- Saved to `<launcher>/config/version.manifest`. Downloaded **once per process** under a lock
  (double-checked locking over a static). Concurrent launches must not both download it.
- Lookup is an exact `id` match (not a prefix match; the first matching entry wins). Missing →
  `ModpackException("minecraft.error.versionNotFound", id)`.
- **Gap to fix:** the Java code ignores each entry's `sha1`. Rust should pass it to the
  client.json managed file (section 3.2), see Bugs B-3.

### 3.2 client.json (per-version "library manifest")

Two copies exist in Java, and the port should keep both paths for layout parity:

1. `GameVersionManifest.getClientJson(ver)` downloads to `<launcher>/metadata/client-<ver>.json`
   once per process per version (`computeIfAbsent` style: one download even under concurrent
   launches; errors must not poison the cache entry), used only for `javaVersion` reads.
2. `GameLibraryManifest` is a managed file at `<pack>/bin/libraries.manifest`, remote = the
   version's URL, **no hash**. So it is verified by existence only and never refreshed once
   present (Bugs B-3). All launch reads go through this copy. Its parse is memoised by
   `(mtime, len)`. `GameModPack.getMinecraftLibraryManifest()` memoises the object per pack, so
   the 2-4 MB parse happens once per launch.

Fields read:

| Field | Use | Default when absent |
|---|---|---|
| `libraries[]` | library and native list | required |
| `downloads.client` / `downloads.server` `{url, sha1}` | game jar | required for the mode |
| `assetIndex {id, url}` | asset index | required (error if missing) |
| `javaVersion.majorVersion` | Java major | `8` (`DEFAULT_JAVA_MAJOR_VERSION`) |
| `javaVersion.component` | runtime component | `"jre-legacy"` (`DEFAULT_RUNTIME_COMPONENT`) |
| `mainClass` | vanilla main class | `null` → caller uses `net.minecraft.client.main.Main` |
| `arguments.jvm[]`, `arguments.game[]` | modern (1.13+) args | `""` |
| `minecraftArguments` | legacy (≤1.12) game args string | used only when `arguments.game` missing |
| `logging.client {argument, file{id,url,sha1}}` | parsed, returned as `[argument, url, sha1, id]` only if `argument` and `file.url` both exist | `null`. **Not used at launch.** The launcher uses its own Log4Shell config logic instead (7.4) |

`hasModernArguments()` = presence of the `arguments` key.

### 3.3 Library entry (vanilla)

```json
{ "name": "org.lwjgl:lwjgl:3.3.3",
  "downloads": { "artifact": {"path": "org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3.jar", "sha1": "…", "size": 1, "url": "https://libraries.minecraft.net/…"},
                 "classifiers": { "natives-windows": {"path": "…", "sha1": "…", "url": "…"}, … } },
  "natives": { "windows": "natives-windows", "osx": "natives-osx", "linux": "natives-linux-${arch}" },
  "extract": { "exclude": ["META-INF/"] },
  "rules": [ {"action": "allow"}, {"action": "disallow", "os": {"name": "osx"}} ] }
```

### 3.4 Asset index

```json
{ "virtual": false, "map_to_resources": false,
  "objects": { "minecraft/sounds/ambient/cave/cave1.ogg": {"hash": "<sha1>", "size": 12345}, … } }
```

Object URL: `https://resources.download.minecraft.net/<hash[0..2]>/<hash>`, stored at
`<launcher>/shared/assets/objects/<hash[0..2]>/<hash>`, SHA-1 = `hash`. `size` is unused.

### 3.5 Forge installer JAR (`<pack>/bin/modpack.jar`)

- **Modern (1.13+, and some late 1.12.2):** top-level `version.json` (launcher-profile shape:
  `id`, `inheritsFrom`, `mainClass`, `arguments{game,jvm}`, `libraries[]`) plus
  `install_profile.json` (`data{}`, `processors[]`, `libraries[]`) and an embedded Maven tree
  under `maven/…`. Data files such as `/data/client.lzma` sit at the jar root under `data/`.
- **Legacy (1.7.10 to early 1.12.x):** no `version.json`. `install_profile.json` holds a
  `versionInfo` object with the same shape (`id`, `mainClass`, `minecraftArguments`,
  `libraries[]` with `name`, optional `url`, `clientreq`, `serverreq`), and the universal jar
  sits at the **top level** of the installer as `forge-<mc>-<ver>-universal.jar`.
- Version manifest selection: `version.json` if present, else `install_profile.versionInfo` if
  it is an object, else error `UNABLE_FIND_FORGE_VERSION_FILE`.
- Required: `id` and `mainClass`, otherwise `forge.error.manifestMissingField`.

install_profile `data` values (per side):

```json
"data": { "MAPPINGS": {"client": "[de.oceanlabs.mcp:mcp_config:1.20.1-…:mappings@txt]", "server": "…"},
          "BINPATCH": {"client": "/data/client.lzma", "server": "/data/server.lzma"},
          "PATCHED":  {"client": "[net.minecraftforge:forge:1.20.1-47.2.0:client]", "server": "…"},
          "PATCHED_SHA": {"client": "'<sha1>'", "server": "'<sha1>'"},
          "MC_EXTRA": {"client": "[net.minecraft:client:1.20.1-…:extra]", … } }
```

Processor:

```json
{ "sides": ["client"], "jar": "net.minecraftforge:installertools:1.3.0",
  "classpath": ["net.md-5:SpecialSource:1.11.0", …],
  "args": ["--task", "MCP_DATA", "--input", "[de.oceanlabs.mcp:mcp_config:…@zip]", "--output", "{MAPPINGS}", "--key", "mappings"],
  "outputs": { … } }
```

`outputs` is ignored by the Java code (parity: ignore; optional improvement in 10).

### 3.6 Fabric profile JSON (`<pack>/bin/fabric-profile.json`)

URL shape: `https://meta.fabricmc.net/v2/versions/loader/<mc>/<loader>/profile/json`.

```json
{ "id": "fabric-loader-0.16.10-1.21.5", "inheritsFrom": "1.21.5",
  "mainClass": "net.fabricmc.loader.impl.launch.knot.KnotClient",
  "arguments": {"game": [], "jvm": ["-DFabricMcEmu= net.minecraft.client.main.Main "]},
  "libraries": [ {"name": "net.fabricmc:fabric-loader:0.16.10", "url": "https://maven.fabricmc.net/", "sha1": "…"}, … ] }
```

Required: `mainClass`, `inheritsFrom`, `libraries` (must be an array). Hash is optional: the meta
service does not publish stable hashes, so a blank `packModLoaderHash` means existence-only.

### 3.7 Mojang Java runtime index

`GET https://launchermeta.mojang.com/v1/products/java-runtime/2ec0cc96c44e5a76b9c8b7c39df7210883d12871/all.json`

```json
{ "windows-x64": { "java-runtime-gamma": [ { "availability": {…},
      "manifest": {"sha1": "…", "size": 1, "url": "https://piston-meta…/manifest.json"},
      "version": {"name": "17.0.8", "released": "…"} } ], "jre-legacy": [ … ], … },
  "mac-os": {…}, "mac-os-arm64": {…}, "linux": {…}, "linux-i386": {…}, "windows-arm64": {…}, "windows-x86": {…}, "gamecore": {…} }
```

Only element `[0]` of each component array is used. `manifest.sha1` is ignored by Java.

Per-component manifest:

```json
{ "files": { "bin": {"type": "directory"},
             "bin/java": {"type": "file", "executable": true,
                          "downloads": {"raw": {"sha1": "…", "size": 1, "url": "…"}, "lzma": {…}}},
             "legal/java.base/LICENSE": {"type": "link", "target": "../…"} } }
```

### 3.8 Bell-SW Liberica API (JRE 8)

`GET https://api.bell-sw.com/v1/liberica/releases?version-feature=8&version-update=392&bitness=64&installation-type=archive&os={OS}&arch={ARCH}&bundle-type=jre`
(sent with `Accept: application/json`). The response is a JSON **array**, and element `[0]` is used.
Fields read: `version` (string, e.g. `"8u392+9"`), `bundleType` (`"jre"`), `featureVersion`
(int, default 8), `updateVersion` (int, default 0), `sha1`, `filename`, `downloadUrl`,
`packageType` (`"tar.gz"` or `"zip"`, default `"tar.gz"`).

OS/arch tokens: Windows → `("windows","x86")` always (64-bit x86 even on Windows ARM, which
then runs under emulation). macOS → `("macos", arm|x86)`. Linux → `("linux", arm|x86)`. Here `arm`
means Java os.arch contains `aarch64`.

---

## 4. Rule evaluation and argument flattening (`ManifestRuleUtilities`)

### 4.1 `evaluate_rules(rules) -> bool`

```
if rules is null or empty → true
allowed = false
for rule in rules:
    if rule is not an object → skip
    if !rule_matches(rule) → skip
    allowed = (rule.action ?? "allow").eq_ignore_ascii_case("allow")
return allowed
```

`rule_matches`: an `os` block (if present) must match, **and** a `features` block (if present)
must match.

### 4.2 `os` block

Every present constraint must hold:

- `name`: lowercased and trimmed. `windows|win|bindoj` → host is Windows; `osx|mac|macos` → host
  is macOS; `linux|unix` → host is Linux; anything else → **no match**. (A BSD host matches
  nothing. Java's platform name falls back to `linux` for non-Windows/non-mac hosts, but
  `IS_OS_LINUX` is false there. Keep the asymmetry.)
- `version`: regex **find** (not full match) against Java `os.version`.
- `arch`: regex find against Java `os.arch`.
- `versionRange {min?, max?}` (MC 26.1+ format): inclusive, numeric component compare against
  `os.version`. An empty `os.version` → match. Components: split on `.`, strip non-digits from
  each part, unparseable → 0. Missing trailing components count as 0.
- An invalid regex is a **non-match**, not an error. Compile each pattern once and cache it by
  source string, including the failure (`HashMap<String, Option<Regex>>` behind a lock).

Regex dialect: Mojang patterns are simple (`^10\\.`, `x86`, `^Microsoft Windows XP`). Use the
`regex` crate. If compilation fails, retry with `fancy-regex` before declaring a non-match, so
lookaround patterns that Java accepts still work.

### 4.3 Emulating Java `os.arch` / `os.version` (important)

Rust's `std::env::consts::ARCH` is **not** what the rules were written against. Provide
`HostInfo { os: Os, java_arch: String, java_os_version: String }`, computed once:

| Host | Java `os.arch` | Java `os.version` |
|---|---|---|
| Windows x64 | `amd64` | `10.0` on Windows 10 **and** 11 (JDK reports major.minor only) |
| Windows ARM64 | `aarch64` | `10.0` |
| Windows x86 | `x86` | `10.0` |
| macOS Intel | `x86_64` | product version, e.g. `14.5` |
| macOS Apple Silicon | `aarch64` | e.g. `14.5` |
| Linux x64 | `amd64` | kernel release (`uname -r`), e.g. `6.5.0-35-generic` |
| Linux ARM64 | `aarch64` | kernel release |
| Linux x86 | `i386` (JDK reports `i386` on 32-bit Linux) | kernel release |

Consequences the port must reproduce:

- The legacy Mojang rule `{"action":"allow","os":{"arch":"x86"}}` (adds `-Xss1M`) matches on
  **Intel macOS** (`x86_64` contains `x86`) and on 32-bit Windows, but not on `amd64`.
- `versionRange.min = "10.0.17134"` against Java's `"10.0"` compares `[10,0]` with
  `[10,0,17134]`, which is **less**, so such a rule never matches on Windows under Java. See open
  question Q-1 (recommendation: follow Java for parity and log it).

### 4.4 `features` block

The launcher implements no Mojang feature toggles. Every feature counts as `false`. The block
matches only when every listed feature expects `false`. So `is_demo_user`,
`has_custom_resolution`, `has_quick_plays_support`, `is_quick_play_*` arguments are always
dropped.

### 4.5 `flatten_arguments(array) -> String`

```
out = []
for el in array:
    null → skip
    primitive → out.push(quote_if_needed(el.as_string()))      // numbers/bools stringify
    object → if !evaluate_rules(el.rules) skip
             value = el.value; null → skip
             string → push quote_if_needed(value)
             array  → for each primitive v: push quote_if_needed(v)
    else → skip
return out.join(" ")
```

`quote_if_needed(a)`: empty → as-is. Already `"…"` → as-is. Contains a space **and** does not
contain `${` → `"` + a + `"`. Else as-is.

This string is later re-tokenised with `split_command_line` (section 7.2). The round trip
(quote, then split) is what keeps `-Dos.name=Windows 10` as one argv element. **Rust design
note:** keep the string API for parity and tests, but add `flatten_arguments_vec(array) ->
Vec<String>` and use it internally so tokens never round-trip through a string. Both must give
the same final argv for every manifest; a test should assert that.

### 4.6 Platform name

`current_platform_name()` → `"windows"` | `"osx"` | `"linux"` (the last for anything else).
These are the `ModPackConstants.PLATFORM_*` values.

---

## 5. Vanilla library resolution (`GameLibraryManifest.extractLibraries`)

Pure function over the parsed client.json. Port it exactly:

```
for lib in manifest.libraries:
    if !(lib has "downloads" && lib has "name"):
        warn("log.libraryManifest.skippedUnknownType", lib); continue     // not an error
    artifact = lib.downloads.artifact (optional object)
    if artifact: path, sha1, url = required strings (missing → ModpackException)
    allowed = evaluate_rules(lib.rules)
    rulesOS = allowed ? [current_platform] : []
    if lib.downloads has "classifiers":
        for (osKey, hostIs) in [("windows", IS_WINDOWS), ("osx", IS_MAC), ("linux", IS_LINUX)]:
            key = resolve_native_classifier_key(lib, osKey)
            if allowed && hostIs && classifiers has key:
                native = classifiers[key]   // required: path, url, sha1
                push GameLibrary{url, path, sha1(SHA1), native=true, applicable=[platform]}
    if artifact present:
        thisLib = GameLibrary{url, path, sha1, native=false, strict=true, applicable=rulesOS}
        if thisLib.applicable contains current_platform: push
```

Order in the output list is manifest order, with each native entry pushed **before** its own
artifact. Classpath order depends on this.

`resolve_native_classifier_key(lib, osKey)`: if `lib.natives[osKey]` exists, take that string and
replace `${arch}` with `"64"` if Java os.arch contains `"64"`, else `"32"`. Note `aarch64` →
`"64"`. Otherwise return `"natives-" + osKey`.

Edge cases covered by tests:

- A library whose rules exclude this OS contributes **neither** its artifact nor its native
  (commit "Skip native libraries when the parent library's rules exclude this OS").
- A classifiers block without this OS's key adds no native entry.
- An entry without `downloads` (very old manifests that use `name` + `url` only) is skipped with
  a warning, not an error.
- `extract.exclude` is ignored. Native extraction instead skips any entry name containing
  `META-INF`.
- Modern (1.19+) manifests express natives as separate libraries such as
  `org.lwjgl:lwjgl:3.3.1:natives-macos-arm64` with `os` rules, and no classifiers. These go
  through the artifact branch and land on the **classpath**, not in `bin/natives` (LWJGL3
  extracts them itself). Parity: same.

### 5.1 Download, verify and extract

`downloadVerifyLibraries`:

- Each library becomes a `ManagedGameFile` with local prefix `<pack>/libraries`, local path = the
  manifest `path` (forward slashes turned into `SEP`).
- All libraries are submitted to the **shared bounded download pool** (`DownloadExecutor`, size
  `max(16, cores*2)`, shared by every launch). The calling task waits with
  `await_all(futures, 30 min)`. On timeout, interrupt or failure it cancels pending siblings and
  re-raises. Timeout → `minecraft.error.libsTimedOut`. Execution failure →
  `minecraft.error.libsFailed(cause message)`.
- **Natives are extracted on every launch**, not only when re-downloaded. The old `didChange`
  gate left `bin/natives` permanently empty after a manual wipe or FAST_PATH skip, and LWJGL
  then failed with `UnsatisfiedLinkError`. Extraction target: `<pack>/bin/natives`. The
  extraction contract is in 5.2.
- Progress: `libraryManifest.verifiedLibrary(fileName)` with weight 25/N.

### 5.2 `extract_jar(source_zip, dest_dir)` contract (SystemUtilities.extractJarFile)

Used for natives and for the ARM64 replacement natives:

- Base = `absolute(dest).normalize()` (lexical normalise, **not** `canonicalize`: see 9.4).
- Skip any entry whose name contains `META-INF`.
- NUL in the name → error. Absolute names (`/…`, `\…`, `X:…`) → error. A resolved target outside
  base → error.
- Windows reserved device stems (`con prn aux nul com1-9 lpt1-9`, case-insensitive, extension
  stripped) → skip with a warning, **on every OS**.
- Directory entries → `create_dir_all`.
- File entries: stream into sibling `<target>.tmp-<uuid>`, counting real bytes. Caps are 64 MiB
  per entry and 256 MiB cumulative; the declared size is never trusted. Then `fsync` and an
  atomic rename over the target (fall back to a plain replace if the rename is unsupported). On
  error, delete the temp file. Reason: a half-written `lwjgl64.dll` makes Windows `LoadLibrary`
  fail with 0xC0000005. Atomic publish means the target holds either the old good DLL or the new
  complete one, never a mix.
- Several pool tasks extract different native jars into the **same** folder at once. The uuid
  temp names make that safe.

### 5.3 LWJGL2 on ARM64 (`Lwjgl2ArmPatcher`)

`is_needed(mc)` = config `lwjglArmPatchEnable` **and** host arch is ARM64 (Java os.arch contains
`aarch64` or `arm64`) **and** the OS is not Windows **and** `is_lwjgl2_version(mc)`.

`is_lwjgl2_version`: Java splits on `.` and returns `minor <= 12`, where minor = `parts[1]`.
**Bug B-5:** `"26.1"` (year-based versions) gives minor 1, so it is treated as LWJGL2. Rust:
return true only when `parts[0] == "1"` and `parts[1] <= 12`.

`patch_natives(nativesDir, librariesDir, cacheDir = libraries/arm64-natives)`:

1. Choose URLs by OS:
   - macOS: LWJGL
     `https://github.com/MinecraftMachina/lwjgl/releases/download/2.9.4-20150209-mmachina.2/lwjgl-platform-2.9.4-nightly-20150209-natives-osx.jar`;
     jinput `https://github.com/r58Playz/jinput-m1/raw/main/plugins/OSX/bin/jinput-platform-2.0.5.jar`;
     suffix `-natives-osx.jar`.
   - Linux: LWJGL
     `https://github.com/theofficialgman/lwjgl3-binaries-arm64/raw/lwjgl-2.9.4/lwjgl-platform-2.9.4-nightly-20150209-natives-linux.jar`;
     jinput `…/raw/lwjgl-2.9.4/jinput-platform-2.0.5-natives-linux.jar`; suffix `-natives-linux.jar`.
   - JNA (both): `https://repo1.maven.org/maven2/net/java/dev/jna/jna/5.15.0/jna-5.15.0.jar`.
2. Download each to `cacheDir/{lwjgl-arm64-natives.jar, jinput-arm64-natives.jar, jna-arm64.jar}`
   **only if absent**. These have no hash, existence only (Bugs B-6).
3. Walk `librariesDir` recursively, skipping the `arm64-natives` dir. A file ending in the suffix
   whose name contains `lwjgl-platform` is overwritten with the LWJGL jar; one containing
   `jinput-platform` with the jinput jar. A `jna-*.jar` whose name does not contain `platform` is
   overwritten with the JNA jar.
4. Replace every `text2speech-*.jar` under libraries (same skip) with a **generated no-op stub
   jar**. The stub holds `com/mojang/text2speech/Narrator.class` (public interface with abstract
   `say(Ljava/lang/String;Z)V`, `clear()V`, `destroy()V`, `close()V`, `active()Z`, and a static
   `getNarrator()` returning a `new NarratorNoop()`) and `com/mojang/text2speech/NarratorNoop.class`
   (implements Narrator, no-op methods, `active()` returns false). Classfile version 52 (V1_8).
   Java builds these with ASM at runtime. **Rust:** ship the stub jar as
   `include_bytes!("text2speech-noop.jar")`. Generate it once (reproducibly, with a small
   build-time script or by hand-assembling the two class files) and commit it. No ASM equivalent
   is needed at runtime.
5. Extract the LWJGL and jinput jars into `nativesDir` (5.2), then set `+rx` on `.dylib`, `.so`
   and `.jnilib`.
6. On I/O failure, log and delete all three cache files so they are fetched again next time. The
   failure is **non-fatal**: the launch goes on.

`disable_incompatible_mods(<pack>/mods)`: rename `controllable.jar` →
`controllable.jar.arm64disabled` when patching is needed. Called from the content branch after
the mod sync.

Launch JVM flags when `is_needed`: `-Dorg.lwjgl.librarypath=<natives>`,
`-Dnet.java.games.input.librarypath=<natives>`, `-Djna.nosys=false`, `-Djna.boot.library.path=`,
`-Dmojang.text2speech.enabled=false`.

### 5.4 The client (or server) jar

- `ManagedGameFile(url=downloads.client|server.url, local=<pack>/bin/minecraft.jar, sha1)`. Server
  mode downloads the **server** jar to the same path.
- Marker `<pack>/bin/minecraft.jar.verified`. If the marker exists, skip **all** verification
  (Bugs B-7). If not:
  1. Update the managed file (verify, download when needed).
  2. If `JarSigningStripper::is_strip_required_for(mc)` (regex `^1\.(\d+)` and that group `< 6`;
     snapshots and anything else → false), strip the signature. Remove `META-INF/*.SF|.DSA|.RSA|.EC`
     (case-insensitive) and rewrite `META-INF/MANIFEST.MF` keeping only the main section (drop
     per-entry digest sections). Write to `<jar>.unsigned.tmp`, then atomic replace. A strip
     failure only logs a warning. Why: before 1.6, launchwrapper's transformer trips JarVerifier
     and lang lookups return null. From 1.6 on, FML's `FMLSanityChecker` aborts with "CRITICAL
     TAMPERING" if the jar is stripped, so never strip there.
  3. Create the marker (empty file), warning on failure. The marker was renamed from `.unsigned`
     to `.verified` to force one re-download for installs corrupted by an old always-strip build.
- Progress `libraryManifest.verifiedMinecraftApp`, 10.

### 5.5 Vanilla classpath

`LinkedHashSet` (insertion-ordered, deduped) of `<pack>/libraries/<lib.path>` for each resolved
library, **natives included** (they sit on the classpath too). Then append
`<pack>/bin/minecraft.jar`. Join with `PATHSEP`. Steps in `buildMinecraftClasspath`, in order:
library list → download/verify/extract natives → ARM64 patch (if needed) → client jar → assets
(6) → classpath.

---

## 6. Assets (`GameAssetManifest`)

### 6.1 Index

Managed file remote = `assetIndex.url`, local = `<launcher>/shared/assets/indexes/<id>.json`,
**no hash** (`assetIndex.sha1` is ignored, Bugs B-3).

### 6.2 Objects

For each `objects` entry: `ManagedGameFile(url, shared/objects/hh/hash, sha1=hash)`. Submit all to
the shared pool (thousands of small files, so the job is latency-bound). Progress
`VERIFIED_ASSET_PROGRESS_TEXT + " " + fileName`, weight 50/N. Wait 30 min. A timeout gives
"Asset downloads did not complete within 30 minutes."

Concurrent launches of different packs share these files. The downloader must write each attempt
to a **unique** temp file and atomically rename it into place (see 9.2). Two launches fetching
the same object must not corrupt each other.

### 6.3 Legacy virtual tree

After downloading, `materializeVirtualTree()`:

- Only when the index has `virtual: true` (dest = `<pack>/assets/virtual/<id>`) or
  `map_to_resources: true` (dest = `<pack>/resources`; `map_to_resources` wins if both are set).
- For each object: source = the shared hashed object. If the source is missing, **skip silently**
  (one stale entry must not block the launch). If the destination exists with the same length,
  skip. Else `mkdir -p` the parent and copy with `REPLACE_EXISTING + COPY_ATTRIBUTES` (preserve
  mtime with the `filetime` crate). A copy failure is fatal: "Failed to materialize legacy asset
  <name>". Log copied and skipped counts.

### 6.4 Migration cleanup

At the start of `downloadAssets`, spawn a **background** task that recursively deletes
`<pack>/assets/objects` and `<pack>/assets/indexes`, best-effort, logging each failure. Never
delete `assets/virtual`. It runs in the background because the first run may delete tens of
thousands of files and would otherwise look like a hang.

### 6.5 Launch placeholders that depend on the index

- `${assets_root}` = `<launcher>/shared/assets` always.
- `${game_assets}` = the virtual path if virtual, else the resources path if
  `map_to_resources`, else the shared root. Any error falls back to the shared root.
- `${assets_index_name}` = `assetIndex.id`.

---

## 7. Command line construction (`GameModPackLauncher.launchAs`)

### 7.1 Inputs

- Mode: client or server (`GameModeManager`); also TUI-enabled.
- `user: Option<Account>`. A client launch without one → error `gameModPackLauncher.error.noAccount`.
- `AuthArguments`: `player_name`, `uuid`, `access_token` (nulls become `""`), and
  `session = "token:" + token + ":" + uuid`. Server mode: all `""`.

### 7.2 `split_command_line(s) -> Vec<String>` (port exactly; do NOT use `shlex`)

```
args=[]; cur=""; in_quotes=false; started=false
for c in s.chars():
    if c == '"': in_quotes = !in_quotes; started = true; continue
    if c.is_whitespace() && !in_quotes:          // Java Character.isWhitespace semantics
        if started: args.push(cur); cur.clear(); started=false
    else: cur.push(c); started = true
if started: args.push(cur)
```

Backslashes are literal (Windows paths). `""` yields an empty-string argument. Quotes in the
middle of a token toggle mode and are dropped (`a"b c"d` → `ab cd`). Java's
`Character.isWhitespace` is true for Unicode space separators except no-break spaces
(U+00A0, U+2007, U+202F) and for `\t \n \x0B \f \r \x1C-\x1F`. Implement `java_is_whitespace(c)`
to match.

### 7.3 Main class and game args

- Vanilla: `mainClass` from the manifest, else (or on error) `net.minecraft.client.main.Main`.
  Client: game args = `extract_game_arguments(manifest)` (modern flatten, else
  `minecraftArguments`). Server: no game args.
- Modded: client main = `loader.minecraft_main_class()`. Server main =
  `loader.server_main_class().unwrap_or(loader.minecraft_main_class())`.
- Modded client game args:
  ```
  v = vanilla game args; l = loader game args
  if !l.empty && !v.empty && !l.contains("${auth_player_name}") → v + " " + l    // modern: loader adds --launchTarget etc.
  elif !l.empty → l                                                              // legacy Forge: full replacement string
  else → v
  ```

### 7.4 argv assembly order (exact)

1. **Custom JVM args** (config `jvmArgs`; default is Aikar's flags, see below) if not blank:
   `split_command_line`, then **drop any token containing `${`** (stops a tampered config from
   smuggling `${auth_access_token}` into substitution), then
   `ensure_experimental_options_unlocked`: if `-XX:+UnlockExperimentalVMOptions` is absent and
   any token is `-XX:[+-]?Name[=…]` with Name in {`G1NewSizePercent`, `G1MaxNewSizePercent`,
   `G1MixedGCLiveThresholdPercent`}, **insert the unlock flag at index 0** (the JVM aborts
   otherwise).
2. `-Xms{min}m`, `-Xmx{max}m`. Config `minRAM`/`maxRAM` (MB, defaults 512/2048). **Server
   mode:** Java overrode these with the launcher JVM's own `-Xms`/`-Xmx` input arguments, parsed
   by `(?i)-Xm[sx](\d+)([gmk]?)` (g→×1024, m→×1, k→÷1024, none = bytes→÷1048576, failure → keep
   the config value). There is no JVM in Rust. See Q-4 (recommendation: server CLI flags
   `--xms/--xmx` and env vars `MMCL_XMS/MMCL_XMX` parsed with the same function).
3. Log4Shell (`applyLog4jSecurityConfig`): always `-Dlog4j2.formatMsgNoLookups=true`. Then, from
   `minor = parts[1]` of the MC version (parse failure → 0):
   - `minor >= 17` → nothing more.
   - `12 <= minor <= 16` → managed file
     `https://launcher.mojang.com/v1/objects/02937d122c86ce73319ef9975b58896fc1b491d1/log4j2_112-116.xml`
     (SHA-1 `02937d122c86ce73319ef9975b58896fc1b491d1`).
   - `7 <= minor <= 11` →
     `https://launcher.mojang.com/v1/objects/4bb89a97a66f350bc9f73b3ca8509632682aea2e/log4j2_17-111.xml`
     (SHA-1 `4bb89a97a66f350bc9f73b3ca8509632682aea2e`).
   - Stored at `<launcher>/metadata/log4j-configs/<sha1>.xml`, then add
     `-Dlog4j.configurationFile=<path>`. Any failure only warns (no flag added).
   - For year-based versions like `26.1`, minor 1 → no file, which is correct by accident. Rust:
     treat `major != 1` as modern (no file).
4. Natives folder `<pack>/bin/natives`: if it exists, set rwx on the folder and each direct child
   (Unix: `chmod u+rwx`. Windows: no-op).
5. ARM64 LWJGL2 flags (5.3) when needed.
6. **Manifest JVM args** (`arguments.jvm`, flattened, then split).
7. **Loader JVM args**, only when modded **and client** (Forge/NeoForge module flags, e.g. `-p
   ${library_directory}/…${classpath_separator}…`, `--add-opens`, `-DignoreList=…`).
8. If the manifest JVM args were empty (legacy ≤1.12): `-Djava.library.path=<natives>`, `-cp`,
   `<classpath>`.
9. Main class.
10. Game args (split).
11. **Placeholder substitution** over every argv element (7.5).
12. Quick join: `pack.consume_quick_join_server()` (consumed even in server mode, but only used in
    client mode) → `--server <host>`, plus `--port <port>` when port ≠ 25565.
13. Client: `--title <sanitized pack name>`, `--icon <pack logo path>`. Sanitize: first 200
    chars, drop `"`, `\`, chars < 0x20 and 0x7F.
14. Client on macOS: `-Xdock:icon=<logo>`, `-Xdock:name=<title>`,
    `-Dapple.laf.useScreenMenuBar=true`, `-Djdk.lang.Process.launchMechanism=vfork`. **Bug B-1:**
    in Java these come *after* the main class, so they reach Minecraft as ignored game args and
    have no effect. Recommendation: put them in the JVM section, right after step 5. See Q-6.
15. Insert the Java executable at index 0 (`RuntimeManager.java_path(component)`).

Default custom JVM args (`ConfigConstants.JVM_ARGS_VALUE_DEFAULT`):

```
-XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200 -XX:+UnlockExperimentalVMOptions
-XX:+ExplicitGCInvokesConcurrent -XX:+AlwaysPreTouch -XX:G1NewSizePercent=30 -XX:G1MaxNewSizePercent=40
-XX:G1HeapRegionSize=16M -XX:G1ReservePercent=20 -XX:G1HeapWastePercent=5 -XX:G1MixedGCCountTarget=4
-XX:InitiatingHeapOccupancyPercent=15 -XX:G1MixedGCLiveThresholdPercent=90
-XX:G1RSetUpdatingPauseTimePercent=5 -XX:SurvivorRatio=32 -XX:+PerfDisableSharedMem
-XX:MaxTenuringThreshold=1 -Dusing.aikars.flags=https://mcflags.emc.gs -Daikars.new.flags=true
```

(The config subsystem owns the pre-2026.10 default migration. Not covered here.)

### 7.5 Placeholders

Always:

| Key | Value |
|---|---|
| `${natives_directory}` | `<pack>/bin/natives` |
| `${classpath}` | merged classpath |
| `${launcher_name}` | `MicaMinecraftLauncher` |
| `${launcher_version}` | `2025.1` (hardcoded, keep) |
| `${version_type}` | `release` |
| `${classpath_separator}` | `PATHSEP` |
| `${library_directory}` | `<pack>/libraries` |

Client only:

| Key | Value |
|---|---|
| `${auth_player_name}` | account name |
| `${version_name}` | vanilla: MC version. Modded: **loader version** (e.g. `47.2.0`). Null → `""` |
| `${game_directory}` | pack root |
| `${assets_root}` | `<launcher>/shared/assets` |
| `${assets_index_name}` | `assetIndex.id` |
| `${auth_uuid}` | uuid |
| `${auth_access_token}` | access token |
| `${user_type}` | `mojang` (keep; see Q-7) |
| `${clientid}`, `${auth_xuid}` | `""` (produce empty argv elements, which is fine) |
| `${user_properties}` | `{}` |
| `${auth_session}` | `token:<token>:<uuid>` |
| `${game_assets}` | 6.5 |

In server mode, client-only placeholders stay literal.

**Substitution semantics. Bug B-2: deliberately change this.** Java applies `String.replace` for
each map entry in turn, in `HashMap` order. A value that contains another key gets expanded or
not depending on iteration order (`GameModPackLauncherPlaceholderTest
.substitutedValuesAreThemselvesRescannedWhichIsOrderDependent`). Today this is only unreachable
because pack names are sanitized to `[A-Za-z0-9]`. Rust: a **single left-to-right scan** per
argument. At each `${`, find the next `}`. If `${name}` is a known key, emit its value and **do not
rescan the value**. Otherwise emit the text verbatim. Unknown placeholders survive verbatim, the
same as Java. Skip arguments with no `$` (fast path). Port the other placeholder tests as-is. They
all still hold.

### 7.6 Spawning

- Working dir = pack root.
- Environment: inherit, then **remove** any variable whose uppercased name contains one of
  `TOKEN SECRET PASSWORD PASSWD PASSPHRASE API_KEY APIKEY PRIVATE_KEY ACCESS_KEY CREDENTIAL COOKIE
  SESSION_KEY AUTH_KEY` or starts with one of `AWS_ AZURE_ GCP_ GCLOUD_ GOOGLE_APPLICATION_
  GITHUB_TOKEN GH_TOKEN NPM_TOKEN PYPI_TOKEN OPENAI_ ANTHROPIC_ STRIPE_ TWILIO_ SENDGRID_`.
- I/O mode: server → **inherit** stdin/stdout/stderr (stdin matters: systemd
  `StandardInput=socket` feeds console commands). GUI and TUI → **piped** stdout and stderr.
  stdin: Java leaves it as a pipe; Rust should use `Stdio::null()` (Q-8). The caller (GameLog)
  **must drain both pipes for the game's whole life**, or the game stalls on its next println
  ("JVM in Task Manager, no window").
- **Windows:** set `CREATE_NO_WINDOW` (0x08000000) in `creation_flags`. The JDK's `ProcessImpl`
  always passes it. Without it, spawning `java.exe` from the GUI launcher (no console) opens a
  console window. Apply the same to Forge processors and installers.
- Log the command joined with spaces, after **redaction**: `(--accessToken)([=\s]+)\S+` →
  `$1$2<redacted>`, the same for `--clientToken`, and `token:[A-Za-z0-9._-]+:([0-9a-fA-F-]{32,36})`
  → `token:<redacted>:$1`. Redaction belongs to the logging subsystem; this crate calls it.
- After spawn: `runtimes.mark_in_use(component, child_pid/handle)`. The record is removed when the
  child exits.
- Errors: `launch.error.startFailed(msg)`.
- Before the final spawn the progress sink gets `signalComplete("Starting Minecraft")` (this hides
  the legacy progress window).
- Windows command-line limit: 32,767 UTF-16 units. Java shares it, so long classpaths that work
  today will work in Rust. If the joined argv exceeds about 32,000, log a clear error (open
  improvement: Java 9+ `@argfile`).

---

## 8. Loaders

### 8.1 Trait (`GameModLoader`)

| Method | Forge / NeoForge | Fabric |
|---|---|---|
| `name()` | `"Forge"` / `"NeoForge"` | `"Fabric"` |
| `minecraft_version()` | `inheritsFrom`, else the id before `-forge`, else the id | `inheritsFrom` |
| `loader_version()` | version manifest `id` (e.g. `1.20.1-forge-47.2.0`) | id with `fabric-loader-` prefix and `-<mc>` suffix stripped (else trimmed id, else `""`) |
| `minecraft_arguments()` | `minecraftArguments` if present, else flatten `arguments.game`, else `""` | flatten `arguments.game` |
| `jvm_arguments()` | flatten `arguments.jvm` (re-reads the cached manifest) | flatten `arguments.jvm` |
| `minecraft_main_class()` | `mainClass` | `mainClass` |
| `server_main_class()` | `None` if main starts with `cpw.mods.modlauncher` or `cpw.mods.bootstraplauncher`, else `net.minecraftforge.fml.relauncher.ServerLaunchWrapper` | `net.fabricmc.loader.impl.launch.server.FabricServerLauncher` |
| `build_classpath(mode, progress)` | 8.2 | 8.5 |
| `run_post_install_steps(mode, progress, component)` | 8.3 | no-op |

Construction is eager in Java: the constructor downloads and verifies the installer or profile
(network in a constructor). Rust: `async fn open(...) -> Result<Self>` that does the same, cached
per pack (`GameModPack::mod_loader()`). Type selection: `packModLoader` lowercased and trimmed.
Blank → `forge`. `forge|neoforge|fabric`, anything else → error "Unsupported modloader type". URL =
`packModLoaderURL` else `packForgeURL`. Hash = `packModLoaderHash` else `packForgeHash`. Vanilla
packs have no loader. `uses_post_install_steps()` = modded and not Fabric (does not construct the
loader).

Forge installer managed file: remote = the URL, local = `<pack>/bin/modpack.jar`, SHA-1 = hash.
**Cache invalidation:** when the installer is re-downloaded (`update` returns changed), clear the
cached entry-name set, the parsed version manifest and the library list.

### 8.2 Forge/NeoForge library list (`getForgeLibrariesList` + `ForgeLibraryPlanner`)

Inputs: the version manifest `libraries[]`. NeoForge differs from Forge **only** in
`loader_coord_prefix`: `net.neoforged:neoforge:` vs `net.minecraftforge:forge:`.

Entry-name probes run against a **once-built `HashSet` of every entry name in the installer**.
Java used to reopen the multi-MB jar 100-250 times per launch. A failed read returns an empty set
**without caching it**.

For each library (`name` required):

```
artifact = lib.downloads?.artifact
inferred = name[after first ':'].replace(':', '-')          // "net.minecraftforge:forge:1.7.10-10.13.4.1614" → "forge-1.7.10-10.13.4.1614"
if artifact?.path: repoPath = artifact.path; specified = true  else: repoPath = inferred; specified = false
sha1 = artifact?.sha1
plan = planner.plan(name, repoPath, inferred, specified, loaderPrefix, has_maven_entry, has_top_level_entry)
```

`plan`:

```
isLoader = name.starts_with(loaderPrefix)
if specified && isLoader && !repoPath.contains("-universal"):
    uni = repoPath.replace(".jar", "-universal.jar")          // replaces EVERY ".jar" occurrence (Java String.replace)
    if has_maven(uni) && has_maven(repoPath): addUniversalAsExtra = true            // modern Forge: both on the classpath
    elif has_maven(uni): repoPath = uni; suppressSha1 = true                       // hash described the base jar
if !specified && isLoader:
    top = inferred + "-universal.jar"
    if has_top(top): legacyTopLevel = true; legacyEntry = top; suppressSha1 = true
```

`has_maven(p)` = the installer has entry `maven/<p>`. `has_top(n)` = it has entry `<n>`.

URL resolution (priority order):

1. `artifact.url` non-blank after trim → use it.
2. `specified && has_maven(repoPath)` → `jar:file:<installer abs URI>!/maven/<repoPath>`.
3. `legacyTopLevel` → `jar:file:<installer>!/<legacyEntry>`.
4. Maven repo: `lib.url` if non-blank, else `https://repo1.maven.org/maven2/`. If `name` contains
   `net.minecraft:` use `https://libraries.minecraft.net/`. Else if it contains
   `net.minecraftforge:` use `https://maven.minecraftforge.net/`. Ensure a trailing `/`.
   - specified → `repo + repoPath`
   - else → `repo + group.replace('.','/') + "/" + rest.replace(':','/') + "/" + inferred + ".jar"`,
     where `rest` is everything after the first `:`. With a classifier, `a:b:c:d` → `…/b/c/d/b-c-d.jar`.
     That is not standard Maven, but it is what Java does, and the local path mirrors it (below).
5. If the URL contains `lzma/lzma/0.0.1` and not `spongepowered` →
   `https://repo.spongepowered.org/maven/lzma/lzma/0.0.1/lzma-0.0.1.jar` (needed by Forge 1.7-1.12
   and not hosted elsewhere). `docs/GAME_LAUNCH_SYSTEM.md` also mentions scala and vecmath
   overrides, but the **code has only lzma**. Port only lzma, and fix the doc later.

Local path (relative to `<pack>/libraries`):

- specified → `repoPath` with `/`→`SEP`.
- else → `group.replace('.',SEP) + SEP + rest.replace(':',SEP) + SEP + inferred + ".jar"`.

Requirements: `clientreq` and `serverreq` default to true. A `GameAsset` is only updated and only
put on the classpath when `required_for(mode)`.

Asset: if `sha1` is present and not suppressed → SHA-1 checked. Else existence-only. When
`addUniversalAsExtra`, add a second asset: url = `jar:` maven URL of `uni`, local = `uni`
(SEP-converted), no hash, same req flags, **immediately after** the base entry.

Download: all assets on the shared pool, prefix `<pack>/libraries`, wait 30 min (errors:
`forge.error.libsTimedOut`, `forge.error.libsInterrupted`, a cause that is already a
`ModpackException` is re-raised as is, else `forge.error.libDownloadFailed`). Progress
`forgeLoader.verifiedAsset(name)` 60/N.

**`jar:` URL sources:** the managed-file downloader accepts `https:` and `jar:file:…!/…` **only
when the inner file path, after normalising, is under `<launcher>`** (`isJarUrlContainedInLauncher`).
It rejects `jar:http:`, malformed URLs and traversal. Rust should model this as a typed source,
`FileSource::ZipEntry { archive: PathBuf, entry: String }`, made only by trusted launcher code,
plus `FileSource::Https(Url)`. The containment check on `archive` stays as defence in depth.
Keep accepting the `jar:` string form if the manifest-driven managed-file API takes strings (tests
port over).

Classpath (`buildForgeClasspath`): ordered set of `<pack>/libraries/<local>` for every asset that
is required for the side. Then reopen `install_profile.json`. If `data.PATCHED[side]` exists and
the file `<libs>/<maven path>` exists → add it. Same for `data.MC_EXTRA[side]`. I/O errors there
only warn. Join with `PATHSEP`.

### 8.3 Forge install processors (`runForgeProcessors`)

```
side = client|server; libs = <pack>/libraries
profile = install_profile.json in the installer; absent → return (legacy)
if !profile.processors || !profile.data → return
PATCHED check:
    coord = data.PATCHED[side]; f = libs/<maven path(coord)>
    if f exists && len > 0:
        sha = data.PATCHED_SHA[side], trimmed, single quotes stripped, blank → None
        if sha is None || sha1(f) == sha → log "patched client exists", return
        warn "patched client corrupt"; delete f (failure → error patchedClientUndeletable)
    patchedOutput = f   (remembered for failure cleanup)
Libraries (profile.libraries[] with downloads.artifact only):
    path = required; url = artifact.url ?? ""; sha1 = artifact.sha1 (blank → None, warn libraryNoHash)
    record declared[path] = sha1
    reject path containing NUL, starting with '/' or '\', or path[1]==':'  → forge.error.unsafeLibraryPath
    resolved = normalize(abs(libs) / path(SEP)); must start_with abs(libs) → forge.error.libraryEscapes
    if resolved exists:
        if no sha1 || sha1 ok: if sha1: verified.add(path); continue
        warn existingLibraryFailedHash; delete
    if url == "":   // embedded in installer
        if has_maven(path): extract maven/<path> → resolved (error embeddedExtractFailed)
                            if sha1 && mismatch: delete, error embeddedHashFailed
                            if sha1: verified.add(path)
        continue        // NOTE: if not embedded either, silently continue (processor will fail later)
    require scheme == https (case-insensitive) else forge.error.nonHttpsLibrary
    mkdir -p parent; up to 3 attempts: download; accept if no sha1 or sha1 ok; else delete + warn
    all failed → forge.error.processorLibHashFailed(3, path, url)
    progress "forgeLoader.downloadedProcessorLib", 1.0
javaExec = runtimes.java_path(component)    // the component from vanilla client.json
for (i, proc) in processors:
    if proc.sides present and does not contain side → skip
    jarCoord = proc.jar (required); jarPath = maven path (strict)
    verify_processor_artifact(jarPath)     // declared hash, skip when no hash or already verified
    cp = [abs(libs/jarPath)] + for each proc.classpath coord: verify_processor_artifact + abs(libs/path)
    mainClass = Main-Class from libs/jarPath's META-INF/MANIFEST.MF (unreadable / missing → error)
    args = proc.args (required) mapped by resolve_processor_arg
    cmd = [javaExec, "-cp", cp.join(PATHSEP), mainClass, args...]
    spawn cwd=<pack root>, stdio inherit; wait ≤ 10 min
        timeout → forge.error.processorTimedOut; exit != 0 → forge.error.processorExitCode(code, jar)
        on ANY failure (timeout, nonzero, interrupt/cancel, spawn error): kill the child if alive,
        delete patchedOutput if it exists (warn if it can't be deleted)
    progress "forgeLoader.completedProcessor", 10/N
```

`verify_processor_artifact` compares the relative path **with forward slashes** to the keys in
`declared`. The Maven path helper returns `/` paths, so keys match the profile's `artifact.path`
format.

`resolve_processor_arg(arg)`:

- `{KEY}` (whole argument): if `data[KEY][side]` exists → `resolve_data_value(v)`. Else magic tokens:
  `SIDE`→side, `MINECRAFT_JAR`→`<pack>/bin/minecraft.jar`, `MINECRAFT_VERSION`→the loader's MC
  version, `ROOT`→pack root, `LIBRARY_DIR`→libs, `INSTALLER`→installer path. An unknown key warns
  `unrecognizedProcessorToken` and passes through as-is. Data wins over magic. Forge 1.20.1
  `DOWNLOAD_MOJMAPS` needs `{SIDE}`: without it installertools fails with "Missing download info
  for {SIDE} mappings".
- `[coord]` → `abs(libs/<maven path>)`.
- Otherwise literal.

`resolve_data_value(v)`:

- `'literal'` → inner text.
- `[coord]` → `abs(libs/<maven path>)`.
- `/path/in/installer` → `libs/forge-installer-data/<path>`. Extracted from the installer **only if
  the file does not exist yet** (a missing entry → `forge.error.installerEntryMissing`).
  **Bug B-4:** if a pack changes Forge version, a stale `data/client.lzma` from the old installer
  is reused. Recommendation: re-extract whenever the installer changed (track the installer SHA-1
  in a sidecar `forge-installer-data/.installer-sha1`) or always re-extract (small files). Keep the
  same path.
- Otherwise literal.

MANIFEST.MF parsing (needed for `Main-Class`): sections are split by blank lines and the main
section comes first. Lines are `Name: value`. **Continuation lines start with a single space** and
join the previous value; lines wrap at 72 bytes, so long class names do wrap. Accept CRLF, LF and
CR. Names are case-insensitive. Write a ~40-line parser in `mmcl-launch::jar`.

Cancellation: Java's interrupt path kills the processor and keeps the interrupt flag. Rust: race
`child.wait()` against the cancel token and the 10-minute timeout, then `kill()` + `wait()`.

### 8.4 NeoForge

Same as Forge, with prefix `net.neoforged:neoforge:` and name `"NeoForge"`. Installers come from
`maven.neoforged.net`. NeoForge starts at MC 1.20.1, and the launcher does not validate the
pairing.

### 8.5 Fabric (`GameModLoaderFabric`)

- Profile managed file at `<pack>/bin/fabric-profile.json`. Hash = the given one if non-blank,
  else none. Parse: a read error, a non-object or malformed JSON each give its own error.
- `build_classpath`: for each `libraries[]` object with a non-blank `name`:
  - `mavenPath = MavenArtifactPath::to_relative_path_or_none(name)`. Malformed → skip. Traversal →
    **error** (never skip silently).
  - base = `lib.url` if non-blank, else by prefix: `net.fabricmc:` →
    `https://maven.fabricmc.net/`, `net.minecraft:` → `https://libraries.minecraft.net/`, else
    `https://repo1.maven.org/maven2/`. Ensure a trailing `/`. URL = base + mavenPath.
  - sha1 = `lib.sha1` if present.
  - local = `<pack>/libraries/<mavenPath SEP>`. `GameAsset(client=true, server=true)`. Update
    **sequentially** (Java does not parallelise here; Rust may run it on the shared pool, see 10).
  - Add to an ordered set. No `rules` evaluation (Fabric profiles have none).
- Log `log.gameModLoaderFabric.classpathCount(n)`.

### 8.6 Maven coordinates (`MavenArtifactPath`)

Grammar: `[`? `group:artifact:version[:classifier]` `[@ext]` `]`?

- Strip a surrounding `[...]`. `@` splits off `ext` (default `jar`). Split on `:`. Fewer than 3
  parts → error.
- **Strict**: reject `..`, `/`, `\` in group, artifact, version, classifier and ext → "Path
  traversal detected…".
- Path: `group.replace('.', '/') / artifact / version / artifact-version[-classifier].ext`.
- `parse_or_none`: structural errors → `None`. Traversal → **error** (Java throws a
  RuntimeException wrapping it).
- Rust: `enum CoordError { Malformed, Traversal }`. `parse_or_none` maps only `Malformed` to
  `Ok(None)`.

### 8.7 Classpath merge (`mergeClasspathsLoaderPriority`)

```
map = IndexMap<String, String>()
for e in loader_cp.split(PATHSEP) non-blank: map.insert(key(e), e)        // insert keeps the first position, replaces the value
for e in mc_cp.split(PATHSEP) non-blank: map.entry(key(e)).or_insert(e)
if any value (\\→/) ends_with "-client.jar" and contains "/net/minecraftforge/forge/" or "/net/neoforged/neoforge/":
    remove every value whose (\\→/) ends_with "/bin/minecraft.jar"
join values with PATHSEP
```

`IndexMap::insert` on an existing key keeps its position, which matches `LinkedHashMap.put`.

`key(e)`: `f = Path(e)`. If there is no parent or no grandparent, the key is `e`. `versionDir =
parent.file_name`, `filename = f.file_name`. `tok = "-" + versionDir`. If
`filename.find(tok) = idx > 0`: `suffix = filename[..idx] + filename[idx+tok.len..]` (the first
occurrence only). Else `suffix = filename`. The key is `abs(grandparent) + ":" + suffix`. So
`asm-9.7.1.jar` and `asm-9.6.jar` under `org/ow2/asm/asm/<ver>/` collide (Fabric needs ASM 9.7.1;
vanilla 1.21.5 ships 9.6; Fabric refuses to start with both). Different classifiers do not collide
(`lwjgl-3.3.3-natives-windows.jar` → `lwjgl-natives-windows.jar`).

The vanilla `minecraft.jar` is dropped because with modlauncher 10+ / bootstraplauncher 2+ both the
patched client and the vanilla jar become automatic module `minecraft`, which gives
`ResolutionException: Module X reads more than one module named minecraft`. Legacy Forge has no
patched client, so the vanilla jar stays.

The loader classpath already holds the Forge PATCHED and MC_EXTRA jars, because
`buildForgeClasspath` adds them, so they win.

### 8.8 Exporting a loader to the Mojang launcher (`LoaderInstallerRunner`)

`install(pack, dot_minecraft) -> InstallResult {success, message, installer_stderr}`:

- No pack → failure. `dot_minecraft` not a directory → failure. Vanilla → success
  (`officialExport.loader.vanilla`, nothing to do). Loader type unreadable or None → failure.
- Forge/NeoForge: get the installer through the pack's **managed loader file**, call
  `reverify_local_file()` (clear the session-verified flag and verify again, because the installer
  is about to be executed), and require it to be a non-empty file. Run
  `<java> -jar <installer> --installClient <dot_minecraft abs>`. Drain stderr into a buffer and
  stdout into debug logs, each on its own task (pipes must not fill). Time out at **180 s**
  (kill). Nonzero exit → failure carrying stderr. Then compute the version id
  (`OfficialLauncherExporter.computeVersionId`, owned by the export subsystem) and require
  `isVersionInstalled(dot_minecraft, id)`, else failure ("version dir missing") carrying stderr.
  On cancel, kill the installer.
- **Java executable:** Java uses the launcher's own JVM (`java.home`). Rust has none. See Q-3.
  Recommendation: `runtimes.java_path(component of the pack's MC version)` (a full Mojang JRE; the
  Forge installer runs headless on Java 8+). Fall back to `java-runtime-delta` when unresolved.
- Fabric: GET `pack.mod_loader_url()` as a string. Blank → failure. Parse; no `id` → failure.
  Write `<dotmc>/versions/<id>/<id>.json` via `<id>.json.tmp` + atomic rename (fall back to a plain
  replace). **Note:** this download goes through the generic GET, not the managed file, so it has
  no hash check, which matches Java (the profile is data, not code).
- Unknown type → failure `unsupportedType`.

---

## 9. Java runtimes (`RuntimeManager`)

### 9.1 Public behaviour

- `verify_runtime(component, show_progress, progress_cb)`: serialised **per component** (a lock per
  component name; different components install in parallel). `jre-legacy` → Liberica (9.3). Else
  Mojang (9.2). On success both caches are filled: `verified_paths[component]` and
  `verified_versions[component]`. Unrecoverable failure raises an error (the step fails); it never
  silently uses PATH `java` for a Mojang component.
- `java_path(component)`: if not cached, `verify_runtime(component, false)`. Return the cached path.
- `java_version(component)`: cached version or None.
- `mark_in_use(component, process)`: add to the in-use set, removed on exit.
  `is_in_use(component)`: prune dead processes, then return whether any remain. Null or unknown →
  false.
- `clear_runtime(component)`: refuse while in use (`runtime.error.inUse`). Recursively delete the
  folder and evict both caches.
- `installed_runtimes()`: for each child dir of `<launcher>/runtime` with a `.version` file →
  `{component: dir name, version: trimmed marker (read error → ""), path: abs, sizeMB: dir size /
  1 MiB (integer)}`. A missing root, or a root that is a file → empty list. Plain files under the
  root are ignored.
- Major-version helpers: `major_to_component`: 8→`jre-legacy`, 16→`java-runtime-alpha`,
  17→`java-runtime-gamma`, 21→`java-runtime-delta`, 25→`java-runtime-epsilon`. Otherwise ≤8 legacy,
  ≤16 alpha, ≤17 gamma, ≤21 delta, else epsilon (non-positive → legacy).
  `verify_jre(major)`, `java_path_for_major`, `clear_runtime_for_major`, and the JRE-8 shortcuts
  map through it. `verify_jre(major)` without the flag defaults to show_progress = true.
- Progress fan-out (`reportProgress`): window (if any): upper label, section text, percent. The
  callback gets the lower text. **Always** logs `"<label>: <text> (<pct>%)"` (percent omitted when
  negative, meaning indeterminate). In Rust, the "window" is the GUI's standalone progress view;
  model it as an optional `ProgressSink`, never a GUI dependency.

Platform key (`getMojangPlatformKey`), **re-specified for Rust** (Java substring logic depends on
Java os.arch names):

| Host | Key |
|---|---|
| Windows x86_64 | `windows-x64` |
| Windows aarch64 | `windows-arm64` |
| Windows x86 | Java: `windows-x64` (bug). Rust: `windows-x86` (exists in all.json) |
| macOS x86_64 | `mac-os` |
| macOS aarch64 | `mac-os-arm64` |
| Linux x86_64 | `linux` |
| Linux x86 | `linux-i386` |
| Linux aarch64 | Java: `linux` (x64 binaries, which can't run). Mojang publishes no linux-arm64. See Q-2 |

**Do not** port `osArch.contains("86")` literally against Rust's `"x86_64"`: it would send every
Linux x64 host to `linux-i386`.

Java executable relative path: Windows `bin\java.exe`, macOS
`jre.bundle/Contents/Home/bin/java`, Linux `bin/java`.

### 9.2 Mojang component flow (`verifyRuntimeImpl`)

```
label = "runtime.label.named"(component); report("checking", 5)
dir = <launcher>/runtime/<component>; mkdir -p
marker = dir/.version; exe = dir/<java exec rel path>
FAST PATH: if marker exists && exe exists:
    v = read(marker).trim() (error → "Unknown")
    cache (abs exe, v); report("alreadyInstalled", 100)
    spawn background_update_check(component, dir, v); return
IN-USE DEFER: if is_in_use(component) && exe exists:      // marker was dropped by an update check, but a game uses it
    cache (exe, ""); report alreadyInstalled; return
try:
    report("fetchingIndex", 10); index = mojang_index(); platform = key()
    index[platform] missing → "runtime.error.platformNotFound"; [component] missing → componentNotFound; empty → noEntries
    entry = [0]; manifestUrl = entry.manifest.url; versionName = entry.version.name
    if marker exists && trim(marker) == versionName && exe exists → cache, report, return
    report("downloadingManifest", 15); download manifestUrl → dir/runtime-manifest.json; files = .files
    log installing(component, versionName, count)
    DELETE marker (from here the folder is in flux)
    base = normalize(abs(dir))
    pass 1 (sequential, manifest order):
        for (rel, e) in files:
            type = e.type (required)
            unsafe(rel) [NUL, leading / or \, rel[1]==':'] → warn unsafeEntryName, skip
            resolved = normalize(base / rel); !starts_with(base) → warn entryEscapesBase, skip
            "directory" → mkdir -p
            "file" → queue task {sha1 = downloads.raw.sha1, url = downloads.raw.url, executable = e.executable ?? false}
            "link" → skip (ALL platforms; the Java comment says "Windows" but the code skips everywhere)
    pass 2 (parallel pool, size max(1, min(tasks, max(12, cores)))):
        task: if !exists || sha1 mismatch: mkdir parent; up to 3 × {download; ok = sha1 matches; if !ok delete + warn hashRetry}
              all fail → error runtime.error.fileHashFailed(3, rel, url)
              if executable: chmod +x
              every 20 files (only when a progress window exists): report("installingFiles", 15 + 80*done/total)
        wait for all; first failure → "runtime.error.fileFailed"(cause); interrupt → "runtime.error.interrupted"
    WRITE marker = versionName
    if exe exists → cache (exe, versionName)
    else: log javaExecNotFound; found = recursive search for java(.exe) under dir; none → ERROR javaExecMissing
catch e:
    log installFailed
    if cancelled/interrupted || marker missing || exe missing → raise installFailed (step fails)
    else use existing: cache (exe, trim(marker) or "Unknown"); log usingExisting
report("completed", 100); cache
```

The marker invariant is the integrity protocol: it is written **last** on success and **deleted
first** before any file changes. A crash mid-install leaves no marker, and the next launch does a
full install. Commit "Don't trust half-finished patches, processors or Java installs". Never
reorder these writes.

`background_update_check(component, dir, installed)`: fetch the index (cached, 10-minute TTL). If
`[platform][component][0].version.name != installed` → log newerAvailable and **delete the marker**
(the next launch then installs fully, unless it is in use). Any failure → silent warning. Note:
`verify_runtime` runs twice per launch (JRE step, and again in `launch()`), so two checks are
spawned. Harmless. Rust may dedupe with a per-component "checked this session" flag.

`mojang_index()`: process-wide cache with a 10-minute TTL (the launcher's in-process restart loop
can keep a process alive for hours). On expiry, download to `<launcher>/runtime/mojang-runtime-index.json`.
If that fails and the file exists, warn and use it, else raise. Parse, store, set the timestamp.
Guarded by one lock.

Warm-launch rationale: before 2026.6, every launch did the ~500 KB index fetch synchronously and
stalled for up to ~40 s on a flaky link. The fast path must not touch the network.

### 9.3 Liberica JRE 8 (`verifyLegacyJre`)

Mojang's `jre-legacy` is 8u51, too old for Forge, which needs 8u121+ for
`sun.misc.ObjectInputFilter`. So `jre-legacy` always means Bell-SW Liberica 8u392.

```
label = runtime.label.legacy; report checking 5; dir = runtime/jre-legacy; mkdir -p
(os, arch) = liberica_os_arch(); api = template.replace("{OS}", os).replace("{ARCH}", arch)
report fetchingJre8Info 15
try download api (Accept: application/json) → dir/jre-legacy.api.json; parse array
  on failure: if the file exists, parse the cached one (warn usingCachedJre8Info) else raise
empty → runtime.error.noJre8Info
info = [0]; version = info.version
folder = bundleType + featureVersion + "u" + updateVersion        // "jre8u392"
alt = folder + "." + bundleType                                    // "jre8u392.jre" (macOS)
eff = folder if it exists, else alt if it exists, else folder
if marker exists && eff exists && trim(marker) == version:
    exe = eff/<java exec rel>; if missing → search eff recursively for java(.exe)
    if exe exists: mark_java_binaries_executable(exe) (heals old installs); cache; report alreadyInstalled; return
report verifyingJre8 30; archive = dir/<info.filename>
if !sha1_ok(archive, info.sha1):
    report downloadingJre8 -1; download info.downloadUrl → archive
    if !sha1_ok → delete archive, error runtime.error.jre8HashFailed
report extractingJre8 75
DELETE marker; rm -rf folder and alt
extract (tar.gz if packageType == "tar.gz", else zip) into dir with the hardened extractor (9.5)
eff = resolve again; exe = eff/<rel> or the recursive search
if none: path = "java", version = "Unknown (System Java)"   (marker NOT written)
else: mark_java_binaries_executable(exe); WRITE marker = version
catch e:
    existing = marker exists ? recursive search for java under dir : None
    if existing is None || cancelled → raise jre8InstallFailed
    use existing (version = trim(marker) or "Unknown")
report completed; cache
```

Notes:

- On macOS the Liberica layout is `jre8u392.jre/Contents/Home/bin/java`, so `jre.bundle/...` never
  matches and the recursive search finds it. `search_for_file` is depth-first in directory-listing
  order and returns the first regular file with the exact name. Rust: sort children by name so the
  result is deterministic (Java's order is filesystem-dependent).
- There is **no network-free fast path** for jre-legacy. The API is hit on every launch, with
  fallback to the cached JSON. Recommendation (10): when the marker, folder and exe all exist,
  return at once and refresh the API JSON in the background, like the Mojang flow. Keep the
  folder layout.
- `mark_java_binaries_executable(exe)`: not on Windows. Set `u+x` on every regular file in
  `exe.parent` (bin/), and on `<bin>/../lib/jspawnhelper` if present. Needed because the hardened
  extractor drops POSIX modes.
- The "System Java" result (`"java"` on PATH) only happens when extraction succeeded but no java
  binary was found. It is kept for parity and logged loudly.

### 9.4 Path handling rules (whole pipeline)

- Use lexical normalisation (`..` and `.` collapsed without touching the filesystem) for every
  containment check: Java's `Path.normalize()` followed by `startsWith`, which compares whole
  components (so `/base-evil` does not start with `/base`; test
  `rejectsAnEscapeThatTunnelsThroughASiblingWithASharedPrefix`). Implement `lexical_normalize`
  over `Path::components()`. `std::path::absolute` is fine (stable). **Never put
  `std::fs::canonicalize` output on the command line on Windows**: it returns `\\?\C:\…` verbatim
  paths. Use `dunce` where canonicalisation is needed.
- Java `File.getAbsolutePath()` does not resolve symlinks. Neither should the Rust paths passed
  to the game.

### 9.5 Hardened archive extraction (`ArchiveExtractor`, also used by Liberica)

Same rules as 5.2, except: caps of 512 MiB per entry and 2 GiB total. Reject NUL and absolute
names, and escapes from base (error). Skip tar symlinks, hardlinks, char/block devices and FIFOs,
and zip entries whose external attrs mark a Unix symlink (warn). Skip reserved names. Skip zip
entries with an unsupported compression method (warn; never write an empty file). Directories →
`create_dir_all`. **POSIX modes are dropped**: the caller restores the execute bits. Zip entries
are walked in physical (local header) order, with metadata from the central directory.

---

## 10. Concurrency, cancellation, verify modes

### 10.1 Branch orchestration

- Modded packs: three branches on a dedicated, unbounded "launch I/O" pool (Java: cached daemon
  threads named `mica-launch-io`, kept off the CPU common pool because the branches block on HTTP
  for minutes). Rust: `tokio::spawn` (or `spawn_blocking` for sync hashing and zip work) inside one
  `JoinSet`/`try_join!` per launch.
- Fail-fast: the first branch error **cancels the others** (a child cancellation token per launch)
  and waits up to **10 s** for them to stop (log `branchesStillRunning` if they don't), then
  returns the first error unwrapped (a `ModpackException` cause as is, else wrapped with the
  cause message or `launch.error.stepFailed`). The user's cancel → `launch.error.preLaunchCancelled`.
  Why it waits: commit "Stop a cancelled launch's parallel branches instead of orphaning them". A
  merely-marked future kept downloading, and after the launch ended its checks read "not
  cancelled", so a relaunch of the same pack ran next to it in the same folder.
- Every branch polls `check_cancelled()` between major steps and raises `launch.error.cancelled`.
  Rust: pass a `CancellationToken` explicitly; **no thread-locals / globals**. A cancel flag
  scoped to one launch must not leak: Java cleared it in a `finally` because a cancelled flag left
  on the cached launcher made the next "Verify this pack" fail as cancelled (test
  `LaunchCancellationScopeTest`). A `null` check never cancels.
- Each branch carries the launch's **download scope** so retry and progress notices from the shared
  pool go only to that launch's listeners. Rust: pass a `DownloadScope` handle into every download
  call (or use `tokio::task_local!` around branch futures).
- The shared download pool is process-wide (bounded) so two concurrent launches cannot together
  open hundreds of connections. Rust: a global `Semaphore(max(16, 2*cores))` around each managed
  file download/verify, plus `await_all(30 min)` semantics: on timeout or failure, abort the
  remaining tasks for this batch.
- Step error wrapping (each branch marks its progress row failed with the message first):
  content `launch.error.contentSyncFailed`, loader libs `launch.error.loaderLibsFailed`, MC
  `launch.error.minecraftFilesFailed`, JRE `launch.error.runtimeFailed`, processors
  `launch.error.processorsFailed`, scan `launch.error.scanFailed` / `scanInterrupted`.

### 10.2 Verify mode and prepare context

`PrepareContext { verify_mode: Full|FastPath, audit_pack_root: Option<PathBuf>, audit_launch_id: i32 }`;
`NONE = {Full, None, -1}`.

- Chosen per launch and attached **to the pack** (not global: several packs prepare at once on a
  shared pool). Every managed file that the pack's loader, manifests and sync touch uses the
  pack's context. Rust: pass `&PrepareContext` explicitly through the loader, manifest and sync
  APIs.
- `decide_mode`: server mode → FULL always. Else FULL if `alwaysVerifyOnLaunch(manifestUrl)`, or no
  current manifest SHA-256, or no state, or the state's sha differs (case-insensitive), or
  age < 0 or > 24 h. Otherwise FAST_PATH.
- FAST_PATH verify = `is_file && len > 0` (no hashing). FULL = strongest declared hash (SHA-256 →
  SHA-1 → MD5, through the `(path, mtime, size, expected, algo)` verify cache), and with no hash,
  existence only.
- After a successful **FULL** run with a manifest sha: read, modify and write `.verify_state.json`
  (keep the scan fields). After a FAST_PATH run, don't touch it (otherwise the TTL never expires).
  Restore the previous context however the run ends.
- `verify_all_files_now()` = the same pipeline forced FULL (with the NONE context, so no audit).
  It still writes state.
- Audit: when an existing file is re-downloaded under an auditing context, hash it before and after
  with the declared algorithm and call `context.record_redownload(path, old, new, expected, algo)`
  (the per-pack audit log subsystem).

### 10.3 Managed file contract the pipeline relies on

(Owned by the files/download subsystem; listed so the two specs agree.)

- Hash slots: `"-1"`, `""` or blank means no hash.
- `update(ctx) -> changed`:
  1. If verified this session and the file is non-empty → `false`. If missing or empty, clear the
     flag and re-check.
  2. Blank remote: existing file → verified. Missing → error `managedFile.error.missingNoUrl` (do
     **not** mark it verified).
  3. If verify fails: offline → error (`offlineHashMismatch` when the file exists, else
     `offlineMissing`). Never use an unverified file offline. Else warn, optionally audit, download.
- Containment: the full path must sit under `local_prefix` when a prefix is set. Scheme gate:
  https, or a trusted `jar:file:` under the launcher root.
- Download: up to 3 attempts. Each attempt's bytes are hashed against the strongest declared hash
  (bypassing FAST_PATH and the cache). Mismatch → delete + retry. Exhausted →
  `managedFile.error.hashFailed`.
- `read_json()`: update first, then a memoised parse keyed by `(mtime, len)`.
- `reverify()`: clear the session flag, then update.

### 10.4 Guard: never launch an unloaded manifest

`pack.is_failed_load() || pack.is_stub()` → error (`launch.error.manifestNotLoaded` /
`manifestNotFullyLoaded`) **before any sync**. A null mod list would otherwise be read as "no mods"
and wipe `mods/`. Tests `IncompleteManifestLaunchGuardTest`.

### 10.5 Security scan step

Policy: `ConfigManager.effectiveScanFrequencyForPack(manifestUrl)` + `ScanFrequency::should_scan(freq,
state, manifestSha)`. Not due → mark the row skipped with a reason (`DISABLED` → disabled,
`ON_CHANGES_ONLY` → noChange, `DAILY` → recent, else notDue). Due → swap the pack's progress
provider **transiently** (do not drop the pack's cached launcher, which holds the process handle),
run `pack.scan_root_folder()` (jarscanner malware scan, owned by the security subsystem), then
read, modify and write the scan fields into VerifyState (a persist failure only warns), and restore
the provider.

---

## 11. Class-by-class map

| Java | Rust module / type | Key items |
|---|---|---|
| `ManifestRuleUtilities` | `mmcl_mojang::rules` | `evaluate_rules(&[Value], &HostInfo) -> bool`, `flatten_arguments(&[Value], &HostInfo) -> String`, `flatten_arguments_vec`, `quote_if_needed`, `current_platform_name`, `RulePatternCache` |
| `RuntimeConstants` (host parts) | `mmcl_mojang::host::HostInfo` | `detect()`, `java_os_arch`, `java_os_version`, `platform()`, `mojang_runtime_key()`, `java_exec_rel_path()` |
| `GameVersionManifest` | `mmcl_mojang::version_manifest::VersionManifestService` | `ensure_loaded()`, `find_version(id) -> VersionEntry{url, sha1}`, `client_json(id) -> Arc<ClientJson>` (single-flight per id), `required_java_major`, `required_runtime_component` |
| `GameLibraryManifest` (pure parts) | `mmcl_mojang::client_json::ClientJson` (serde model + accessors) | `extract_libraries(&HostInfo) -> Vec<ResolvedLibrary>`, `resolve_native_classifier_key`, `asset_index()`, `jvm_arguments()`, `game_arguments()`, `logging_config()`, `main_class()`, `java_version()` |
| `GameLibraryManifest` (I/O parts) | `mmcl_launch::vanilla::VanillaInstaller` | `build_minecraft_classpath(mode, ctx, progress, cancel)`, `download_verify_libraries`, `download_minecraft_app`, `download_minecraft_assets` |
| `GameAssetManifest` | `mmcl_mojang::asset_index::AssetIndex` (model) + `mmcl_launch::assets::AssetInstaller` | `download_assets`, `materialize_virtual_tree`, `virtual_assets_path`, `resources_path`, `shared_assets_root`, `cleanup_legacy_per_pack_tree` |
| `GameLibrary`, `GameAsset` | `mmcl_launch::files::{ResolvedLibrary, LoaderAsset}` | `applicable_os`, `is_native`, `required_for(mode)` |
| `GameModLoader` | `mmcl_launch::loader::ModLoader` (trait) + `LoaderKind` enum | see 12 |
| `GameModLoaderForge` | `mmcl_launch::loader::forge::ForgeLoader { flavor: Forge | NeoForge }` | `open`, `library_list` (cached), `build_classpath`, `run_processors`, `server_main_class` |
| `GameModLoaderNeoForge` | same type, `Flavor::NeoForge` (prefix + name only) | |
| `ForgeLibraryPlanner` | `mmcl_launch::loader::forge::planner` | `inferred_repo_path`, `plan(...) -> Plan`, `local_path(..., sep, ext)` (pure) |
| `GameModLoaderFabric` | `mmcl_launch::loader::fabric::FabricLoader` | `open`, `derive_loader_version`, `default_maven_for` |
| `MavenArtifactPath` | `mmcl_mojang::maven::{Coord, parse_strict, parse_or_none, to_relative_path_strict}` | |
| `Lwjgl2ArmPatcher` | `mmcl_launch::arm64` | `is_needed`, `is_lwjgl2_version`, `patch_natives`, `disable_incompatible_mods`, `TEXT2SPEECH_STUB: &[u8]` |
| `JarSigningStripper` | `mmcl_launch::jar::strip_signing`, `is_strip_required_for` | |
| `SystemUtilities.extractJarFile` | `mmcl_launch::jar::extract_jar` (or shared `mmcl-archive`) | |
| (MANIFEST.MF reading) | `mmcl_launch::jar::read_main_class` | |
| `GameModPackLauncher` | `mmcl_launch::pipeline::LaunchPipeline` + `mmcl_launch::command` | `prepare(...) -> Prepared`, `launch(...) -> GameProcess`, `merge_classpaths_loader_priority`, `maven_coord_dedup_key`, `substitute_placeholders`, `sanitize_title`, `ensure_experimental_options_unlocked`, `parse_heap_arg_mb`, `apply_log4j_security_config`, `AuthArguments` |
| `GameModPackLauncher.Branches` | `mmcl_launch::pipeline::Branches` (JoinSet + child token + 10 s drain) | |
| `ProcessUtilities.splitCommandLine` | `mmcl_launch::command::split_command_line` | |
| `ProcessUtilities.launchCommand` + env scrub | `mmcl_launch::spawn::{spawn_game, ChildIo, strip_sensitive_env}` | |
| `LoaderInstallerRunner` | `mmcl_launch::export_install::install_loader_for_official_launcher` | `InstallResult` |
| `RuntimeManager` | `mmcl_runtime::RuntimeManager` | see 12 |
| `RuntimeManager` Liberica helpers | `mmcl_runtime::liberica` | `os_arch()`, `api_url()`, `extracted_folder_name()`, `resolve_effective_folder()` |
| `RuntimeManager` containment helpers | `mmcl_runtime::install::{is_unsafe_entry_name, resolve_within}` | |
| `RuntimeManager.findJavaExecutable/searchForFile/markJavaBinariesExecutable` | `mmcl_runtime::fsutil` | |
| `RuntimeConfig` | `mmcl_config` (other subsystem): `min_ram_mb()`, `max_ram_mb()`, `custom_jvm_args()` | |
| `LaunchPrepareContext`, `LaunchVerifyMode` | `mmcl_launch::context::{PrepareContext, VerifyMode}` (or in the files crate if the managed-file owner prefers) | |

---

## 12. Proposed crates and public API

Three crates. The split keeps pure, heavily tested parsing free of I/O and async, and lets the
GUI, TUI, MCP and export code depend on just the parts they need.

### 12.1 `mmcl-mojang` (pure: no network, no async)

```rust
pub mod host {
    #[derive(Clone, Copy, PartialEq, Eq, Debug)] pub enum Os { Windows, MacOs, Linux, Other }
    #[derive(Clone, Copy, PartialEq, Eq, Debug)] pub enum Arch { X86_64, X86, Aarch64, Other }
    #[derive(Clone, Debug)]
    pub struct HostInfo { pub os: Os, pub arch: Arch, pub java_os_arch: String, pub java_os_version: String }
    impl HostInfo {
        pub fn detect() -> &'static HostInfo;               // OnceLock
        pub fn platform_name(&self) -> &'static str;        // "windows" | "osx" | "linux"
        pub fn mojang_runtime_key(&self) -> Option<&'static str>;
        pub fn java_exec_rel_path(&self) -> &'static Path;
    }
}
pub mod rules {
    pub fn evaluate_rules(rules: Option<&[serde_json::Value]>, host: &HostInfo) -> bool;
    pub fn flatten_arguments(args: Option<&[serde_json::Value]>, host: &HostInfo) -> String;
    pub fn flatten_arguments_vec(args: Option<&[serde_json::Value]>, host: &HostInfo) -> Vec<String>;
}
pub mod maven {
    pub struct Coord { pub group: String, pub artifact: String, pub version: String,
                       pub classifier: Option<String>, pub ext: String }
    pub enum CoordError { Null, Malformed(String), Traversal(String) }
    impl Coord { pub fn relative_path(&self) -> String; }   // forward slashes
    pub fn parse_strict(s: &str) -> Result<Coord, CoordError>;
    pub fn parse_or_none(s: &str) -> Result<Option<Coord>, CoordError>;   // Err only for Traversal
}
pub mod version_manifest { pub struct VersionManifest { pub versions: Vec<VersionEntry> }
    pub struct VersionEntry { pub id: String, pub kind: String, pub url: String, pub sha1: Option<String>, pub release_time: String }
    impl VersionManifest { pub fn find(&self, id: &str) -> Result<&VersionEntry, ManifestError>; } }
pub mod client_json {
    pub struct ClientJson { raw: serde_json::Value /* preserve_order */ }
    pub struct ResolvedLibrary { pub url: String, pub path: String, pub sha1: String, pub native: bool }
    impl ClientJson {
        pub fn parse(bytes: &[u8]) -> Result<Self, ManifestError>;
        pub fn libraries(&self, host: &HostInfo) -> Result<Vec<ResolvedLibrary>, ManifestError>;
        pub fn java_major(&self) -> u32;                 // default 8
        pub fn runtime_component(&self) -> &str;         // default "jre-legacy"
        pub fn main_class(&self) -> Option<&str>;
        pub fn has_modern_arguments(&self) -> bool;
        pub fn jvm_arguments(&self, host: &HostInfo) -> String;
        pub fn game_arguments(&self, host: &HostInfo) -> String;
        pub fn asset_index(&self) -> Result<AssetIndexRef, ManifestError>;   // {id, url, sha1}
        pub fn game_download(&self, mode: GameMode) -> Result<Download, ManifestError>;
        pub fn logging_config(&self) -> Option<LoggingConfig>;
    }
    pub fn resolve_native_classifier_key(lib: &serde_json::Value, os_key: &str, host: &HostInfo) -> String;
}
pub mod asset_index { pub struct AssetIndex { pub virtual_: bool, pub map_to_resources: bool,
                      pub objects: IndexMap<String, AssetObject> } pub fn object_url(hash: &str) -> String; }
pub mod forge_profile { /* serde models for version.json / install_profile.json / processors */ }
pub mod fabric_profile { /* serde model */ }
pub mod runtime_index { /* all.json + component manifest models */ }
```

### 12.2 `mmcl-runtime`

```rust
pub struct RuntimeManager { /* paths, http, per-component locks, caches, in-use registry, index cache */ }
pub trait RuntimeProgress: Send + Sync { fn report(&self, label: &str, text: &str, percent: f64); }
pub struct InstalledRuntime { pub component: String, pub version: String, pub path: PathBuf, pub size_mb: u64 }
impl RuntimeManager {
    pub fn new(paths: Arc<LauncherPaths>, http: Arc<dyn HttpClient>, host: &'static HostInfo) -> Arc<Self>;
    pub async fn verify(&self, component: &str, progress: Option<&dyn RuntimeProgress>,
                        cancel: &CancellationToken) -> Result<ResolvedRuntime, RuntimeError>;
    pub async fn java_path(&self, component: &str, cancel: &CancellationToken) -> Result<PathBuf, RuntimeError>;
    pub fn java_version(&self, component: &str) -> Option<String>;
    pub fn mark_in_use(&self, component: &str, pid: u32, exited: impl Future<Output = ()> + Send + 'static);
    pub fn is_in_use(&self, component: &str) -> bool;
    pub async fn clear(&self, component: &str) -> Result<(), RuntimeError>;
    pub fn installed(&self) -> Vec<InstalledRuntime>;
    pub fn component_for_major(major: i32) -> &'static str;
}
pub struct ResolvedRuntime { pub component: String, pub java: PathBuf, pub version: String }
```

`mark_in_use` takes an exit future (or `Arc<GameProcessHandle>` from the session crate) rather
than a `Process`, so the runtime crate need not depend on the session crate.

### 12.3 `mmcl-launch`

```rust
pub enum GameMode { Client, Server }
pub struct LaunchEnv { pub mode: GameMode, pub tui: bool, pub host: &'static HostInfo,
                       pub paths: Arc<LauncherPaths>, pub http: Arc<dyn HttpClient>,
                       pub runtimes: Arc<RuntimeManager>, pub config: Arc<dyn LaunchConfig>,
                       pub scanner: Arc<dyn SecurityScanner>, pub content_sync: Arc<dyn ContentSync> }
pub trait LaunchConfig: Send + Sync {
    fn min_ram_mb(&self) -> u64; fn max_ram_mb(&self) -> u64; fn custom_jvm_args(&self) -> String;
    fn lwjgl_arm_patch_enabled(&self) -> bool; fn always_verify_on_launch(&self, manifest_url: &str) -> bool;
    fn scan_frequency_for(&self, manifest_url: Option<&str>) -> ScanFrequency;
    fn server_heap_override(&self) -> (Option<u64>, Option<u64>);     // Q-4
}
pub struct Account { pub name: String, pub uuid: String, pub access_token: String }
pub struct LaunchRequest<'a> { pub pack: &'a PackHandle, pub account: Option<Account>,
                               pub progress: Arc<dyn LaunchProgress>, pub cancel: CancellationToken }
pub trait LaunchProgress: Send + Sync {
    fn step(&self, id: StepId) -> Arc<dyn StepProgress>;   // MODPACK_CONTENT, FORGE_LIBS, MC_LIBS_ASSETS, JRE_INSTALL, FORGE_PROCESSORS, SECURITY_SCAN
    fn set_text(&self, text: &str); fn signal_complete(&self, text: &str);
}
pub trait StepProgress: Send + Sync {
    fn running(&self); fn done(&self); fn failed(&self, msg: &str); fn skipped(&self, reason: &str);
    fn section_start(&self, text: &str, weight: f64); fn section_end(&self, text: &str);
    fn submit(&self, detail: &str, pct: f64); fn set_text(&self, text: &str);
}
pub enum ChildIo { Pipe, Inherit, Discard }
pub struct GameProcess { pub child: tokio::process::Child, pub runtime_component: String, pub argv_redacted: String }

pub async fn launch(env: &LaunchEnv, req: LaunchRequest<'_>) -> Result<GameProcess, LaunchError>;
pub async fn prepare(env: &LaunchEnv, pack: &PackHandle, ctx: PrepareMode, progress: Arc<dyn LaunchProgress>,
                     cancel: &CancellationToken) -> Result<Prepared, LaunchError>;   // the buildClasspath half
pub async fn verify_all_files_now(env: &LaunchEnv, pack: &PackHandle, cancel: &CancellationToken) -> Result<(), LaunchError>;

#[async_trait] pub trait ModLoader: Send + Sync {
    fn kind(&self) -> LoaderKind; fn name(&self) -> &'static str;
    fn minecraft_version(&self) -> &str; fn loader_version(&self) -> &str;
    fn minecraft_arguments(&self) -> &str; fn jvm_arguments(&self) -> Result<String, LaunchError>;
    fn main_class(&self) -> &str; fn server_main_class(&self) -> Option<&str>;
    async fn build_classpath(&self, mode: GameMode, ctx: &PrepareContext, p: &dyn StepProgress, c: &CancellationToken) -> Result<String, LaunchError>;
    async fn run_post_install(&self, mode: GameMode, p: &dyn StepProgress, component: &str, c: &CancellationToken) -> Result<(), LaunchError> { Ok(()) }
    fn installer_file(&self) -> Option<&ManagedFile>;     // for LoaderInstallerRunner
}
pub async fn open_loader(env: &LaunchEnv, pack: &PackHandle) -> Result<Option<Arc<dyn ModLoader>>, LaunchError>;

pub mod command {
    pub fn split_command_line(s: &str) -> Vec<String>;
    pub fn substitute_placeholders(argv: &mut [String], map: &HashMap<&str, String>);
    pub fn merge_classpaths_loader_priority(loader: &str, mc: &str, pathsep: char) -> String;
    pub fn maven_coord_dedup_key(entry: &str) -> String;
    pub fn sanitize_title(s: &str) -> String;
    pub fn ensure_experimental_options_unlocked(args: &mut Vec<String>);
    pub fn parse_heap_arg_mb(arg: &str) -> u64;
    pub fn combine_game_args(vanilla: &str, loader: &str) -> String;
    pub fn log4j_plan(mc_version: &str) -> Log4jPlan;   // None | Flag | File{url, sha1, name}
}
pub mod export_install { pub async fn install_loader(env: &LaunchEnv, pack: &PackHandle, dot_mc: &Path, c: &CancellationToken) -> InstallResult; }
```

`PackHandle` is the modpack subsystem's type, needing: `root()`, `sanitized_name()`,
`is_vanilla()`, `vanilla_mc_version()`, `loader_type()`, `loader_url()`, `loader_hash()`,
`is_failed_load()`, `is_stub()`, `manifest_url()`, `manifest_sha256()`, `launch_count()`,
`logo_path()`, `name()`, `consume_quick_join_server()`, `mod_loader()` (cached `Arc<dyn ModLoader>`),
`library_manifest()` (memoised), and `verify_state` load/save.

Errors: one `LaunchError` enum (`thiserror`) carrying a localisation key plus args
(`LocalizedError { key: &'static str, args: Vec<String> }`) and an optional source, so the GUI can
render it in any locale. Variants: `Cancelled`, `ManifestNotLoaded`, `Network`, `HashMismatch`,
`UnsafePath`, `Processor{..}`, `Runtime(RuntimeError)`, `Io`, `Json`.

---

## 13. External crates

| Need | Crate | Notes |
|---|---|---|
| Async runtime, process, fs | `tokio` (rt-multi-thread, process, fs, sync, time, macros) | Shared with the rest of the app. Bridge to GPUI with its executor or channels |
| Cancellation | `tokio-util` (`CancellationToken`, child tokens) | |
| HTTP | `reqwest` (rustls-tls, stream) | Owned by the net subsystem. Generic User-Agent `MicaMinecraftLauncher/<ver>` |
| JSON | `serde`, `serde_json` with **`preserve_order`** | Gson keeps insertion order. Object order matters for runtime manifest pass 1 (directories before files), asset materialisation logging and `data` iteration |
| Ordered map/set | `indexmap` | LinkedHashMap/LinkedHashSet semantics for classpaths |
| Regex | `regex` (+ `fancy-regex` fallback) | Rule patterns, heap-arg parsing, redaction |
| Hashing | `sha1`, `sha2`, `md-5`, `hex` (RustCrypto) | Streaming, 64 KiB buffers |
| Zip/jar | `zip` (deflate) | Installer entries, natives, signature stripping, stub jar |
| tar.gz | `tar` + `flate2` | Liberica on macOS/Linux |
| Temp/atomic | `tempfile` (or uuid-named siblings) + `std::fs::rename` | Rename is atomic on the same volume. On Windows `rename` replaces when the target exists (MoveFileEx with REPLACE_EXISTING) |
| fsync | std `File::sync_all` | |
| mtime preserve | `filetime` | COPY_ATTRIBUTES in the virtual tree |
| Paths | `dunce` | Avoid `\\?\` on Windows |
| Unix perms | std `PermissionsExt` | |
| Windows flags | std `CommandExt::creation_flags` | `CREATE_NO_WINDOW` |
| OS version | `os_info` or platform calls (`sw_vers`/`uname`) | For `java_os_version`. On Windows, emulate Java's `"10.0"` |
| Concurrency maps | `parking_lot`, `dashmap` (optional) | Per-component locks: `Mutex<HashMap<String, Arc<tokio::sync::Mutex<()>>>>` |
| Single-flight | `tokio::sync::OnceCell` per key | client.json per version |
| Errors | `thiserror` | |
| Logging | `tracing` | Redaction applied before logging argv |
| uuid temp names | `uuid` (v4) | |
| Test | `tempfile`, `assert_fs`, `wiremock` (HTTP fakes for runtime and loader flows) | |

No good Rust equivalent:

- **ASM bytecode generation** (text2speech stub): replace it with a committed, prebuilt jar via
  `include_bytes!`.
- **jarscanner** (Java malware scanner used by the scan step): no Rust port. Owned by the security
  subsystem; options there are to run it in a managed JRE as a subprocess or to port its
  signature set. Flagged in Q-5.
- **`java.home`** for running installers: no JVM in-process. Use a managed runtime (Q-3).
- **`RuntimeMXBean` input args** for server-mode heap inheritance: none. Replace with CLI and env
  (Q-4).
- Java's `JarFile` manifest API: write a small MANIFEST.MF parser (8.3).

---

## 14. Bugs and quirks register

Each entry says whether the Rust port **keeps** the behaviour (parity) or **fixes** it.

| # | Java behaviour | Port |
|---|---|---|
| B-1 | macOS `-Xdock:*`, `-Dapple.laf…`, `-Djdk.lang.Process.launchMechanism=vfork` go after the main class, so they are passed to Minecraft as game args and do nothing | **Fix**: put them in the JVM section (Q-6) |
| B-2 | Placeholder substitution re-scans substituted values; the result depends on HashMap order | **Fix**: single-pass, no rescan (7.5) |
| B-3 | client.json (`bin/libraries.manifest`) and the asset index are managed with **no hash**, so once present they are never refreshed even if Mojang republishes. The v2 `sha1` and `assetIndex.sha1` are ignored | **Fix**: pass the sha1s. Same paths |
| B-4 | `forge-installer-data/*` extracted only if absent, so a stale `client.lzma` survives a Forge version change in the same pack | **Fix**: re-extract when the installer SHA-1 changes |
| B-5 | `isLwjgl2Version("26.1")` is true (minor = 1). Log4j minor parse is also year-unaware (harmless) | **Fix**: require major == 1 |
| B-6 | ARM64 replacement jars and JNA are cached with existence-only checks | Keep (no upstream hashes). Optionally pin SHA-256s in code |
| B-7 | `minecraft.jar.verified` disables all later verification of the client jar, including under FULL verify and "Verify all files" | **Keep** for parity of observable behaviour, but under FULL mode also verify the hash for 1.6+ (not stripped) jars. Document it |
| B-8 | Mojang runtime `link` entries are skipped on every OS (the comment says Windows only) | Keep (Q-9 proposes creating symlinks on Unix) |
| B-9 | `getMojangPlatformKey`: Linux aarch64 → `linux` (x64 JRE); Windows x86 → `windows-x64` | **Fix** the Windows x86 key. Linux ARM64: Q-2 |
| B-10 | jre-legacy has no offline fast path (API every launch) | **Fix**: fast path + background refresh |
| B-11 | Two background runtime update checks per launch | Fix or keep. Harmless |
| B-12 | Processor library with an empty URL and no embedded entry is silently skipped | Keep, but log a warning |
| B-13 | `${user_type}` = `mojang` for Microsoft accounts | Keep (Q-7) |
| B-14 | `${launcher_version}` hardcoded `2025.1` | Keep (some mods read it). Q-7 |
| B-15 | Forge legacy URL/path for classified coordinates puts the classifier in a directory (`b/c/d/b-c-d.jar`) | **Keep**: on-disk layout parity |
| B-16 | `ForgeLibraryPlanner`: `repoPath.replace(".jar", "-universal.jar")` replaces every `.jar` | Keep (Java `String.replace`) |
| B-17 | Fabric libraries download sequentially | May parallelise on the shared pool. Output order must stay declaration order |
| B-18 | `versionRange` on Windows compares against `"10.0"` | Keep (Q-1) |
| B-19 | `logging.client` from client.json is parsed but unused; the launcher applies its own log4j config | Keep |
| B-20 | Natives extracted on every launch (deliberate, not a bug) | Keep |
| B-21 | `search_for_file` order is filesystem-dependent | Sort by name (deterministic) |
| B-22 | Server main class for legacy Forge is hardcoded `ServerLaunchWrapper` | Keep |
| B-23 | Fabric profile download in `LoaderInstallerRunner` has no hash check | Keep (data only) |

---

## 15. Tests to port

All are pure and table-driven. Port them as Rust unit tests next to each module. Host-dependent
tests take an explicit `HostInfo` so all three OSes can be tested on any machine (better than
Java, where tests detected the current OS and branched).

| Java test | Rust target | What to keep |
|---|---|---|
| `ManifestRuleUtilitiesTest` (35 cases) | `mmcl_mojang::rules` | null/empty allow, action default allow and case-insensitive, later rule overrides, non-object rules skipped, os name/version/arch regex, invalid regex → non-match, versionRange min/max/inclusive/only-min, features true never match, false matches, flatten (plain, conditional allow/deny, array values, missing value), quoting (whitespace quoted, `${}` unquoted, already-quoted kept). **Add:** Intel-mac `x86` match, Windows `10.0` vs versionRange, `bindoj`, fancy-regex fallback |
| `GameLibraryManifestExtractLibrariesTest` (10) | `client_json::libraries` | no libraries, no-rules include, disallow-all, another-OS exclude, explicit allow, missing downloads skipped without error, independence, native classifier included, native excluded when parent rules deny, no native when classifier key missing. **Add:** natives-before-artifact order |
| `GameLibraryManifestClassifierTest` (7) | `resolve_native_classifier_key` | fallback key, mapping without OS falls back, explicit mapping, `${arch}` expansion anywhere, no placeholder unchanged, only `64`/`32` (aarch64 → 64) |
| `GameLibraryManifestParsingTest` (21) | `ClientJson` accessors | java major and component defaults, main class null, jvm/game flatten, legacy fallback, modern takes precedence, logging config completeness, asset index id required |
| `GameVersionManifestTest` (11) | `VersionManifest::find`, java version reads | exact match, not prefix, picks the matching entry, empty array throws, defaults |
| `MavenArtifactPathTest` (19) | `mmcl_mojang::maven` | g:a:v, classifier, `@ext`, both, brackets, layout, dots → slashes, null, too few, traversal in each component, literal slashes, parse_or_none semantics |
| `ForgeLibraryPlannerTest` (16) | `forge::planner` | inferred stem with and without classifier, non-coordinate rejected, both bundled → extra universal, only universal → substitute + drop hash, neither → untouched, already universal untouched, top-level universal → legacy + drop hash, ordinary libs never rewritten, prefix respected (NeoForge), local path explicit/separator/reconstructed/classifier dirs |
| `GameModPackLauncherPlaceholderTest` (9) | `command::substitute_placeholders` | all except `substitutedValuesAreThemselvesRescanned…`, which **inverts**: assert no rescan in either order. Keep the pack-name sanitiser test |
| `GameModPackLauncherBranchesTest` (3) | `pipeline::Branches` | abort cancels a running branch and waits for it, caller's cancel state preserved, uncancelled branch returns its result |
| `LaunchCancellationScopeTest` (3) | pipeline | a cancelled launch does not leave later work cancelled, the check applies during and clears after, no token never cancels |
| `IncompleteManifestLaunchGuardTest` (6) | pipeline guard (+ modpack subsystem) | failed and stub packs abort before sync |
| `ManagedGameFileUrlGateTest` (7) | files subsystem `is_jar_url_contained_in_launcher` | accept under root, reject outside root, `jar:http`, malformed, non-jar, empty, traversal |
| `RuntimeComponentMappingTest` (9) | `RuntimeManager::component_for_major`, `HostInfo` | mapping table, clamping, non-positive → legacy, default agreement, platform key and exec path |
| `RuntimeInUseTest` (4) | runtime in-use registry | in use while running, two games, delete refused, unknown/null not in use (use a fake process/exit future) |
| `RuntimeManagerLibericaSeamsTest` (10) | `mmcl_runtime::liberica` | os/arch tokens per host (table over HostInfo), URL substitution, folder name, effective-folder fallbacks |
| `RuntimeManagerSeamsTest` (15) | `mmcl_runtime::fsutil` + progress | search (direct, nested, absent, exact), platform exe name, mark executable (bin siblings, jspawnhelper, no-op cases), progress fan-out |
| `RuntimeManifestContainmentTest` (18) | `mmcl_runtime::install` | unsafe names (NUL, `/`, `\`, drive), `..` not caught by the name check but by resolve, sibling-prefix escape, `.` resolves to base, component folder path, installed scan cases, marker trimmed |
| `ManagedGameFileMultiHashTest`, `…PostDownloadGateTest`, `…SessionVerifiedTest` | files subsystem | Port there. This pipeline relies on them |

New tests worth adding in Rust:

- `split_command_line` table: quotes, empty `""`, mid-token quotes, backslashes literal, tabs and
  newlines, Unicode whitespace per Java rules.
- `merge_classpaths_loader_priority` + `maven_coord_dedup_key`: ASM version collision, natives
  classifiers kept distinct, vanilla minecraft.jar dropped only with a Forge/NeoForge
  `-client.jar`, Windows backslash entries, a loader-internal duplicate keeping its first
  position.
- `combine_game_args` truth table. `ensure_experimental_options_unlocked`. `parse_heap_arg_mb`
  (`4g`, `512m`, `1048576k`, bare bytes, garbage).
- `log4j_plan` for `1.6.4`, `1.7.10`, `1.11.2`, `1.12.2`, `1.16.5`, `1.17`, `1.21.5`, `26.1`,
  `20w14a`.
- `is_lwjgl2_version` including `26.1`.
- Forge `resolve_processor_arg` / `resolve_data_value`: data beats magic, every magic token,
  unknown token passthrough, quoted literal, bracket coord, installer path extraction with a fake zip.
- `patched_sha` trimming and quote stripping. Processor-library path validation (NUL, absolute,
  drive, `..` escape, https-only).
- MANIFEST.MF parser: wrapped `Main-Class`, CRLF, missing attribute.
- `extract_jar`: zip-slip, absolute, NUL, reserved names, per-entry and total caps (with a crafted
  zip), META-INF skip, atomic publish leaves no `.tmp-*`.
- Full argv golden tests: a fixture client.json for 1.7.10 (legacy), 1.12.2 + Forge, 1.20.1 +
  Forge, 1.21.5 + Fabric, 1.21.5 vanilla, and a fake pack, on each HostInfo. Assert the exact
  argv (with a fake runtime path). This is the single most valuable regression net for the port.
- Runtime install flow with `wiremock`: fast path makes no request; the marker is deleted before
  files and written last; a hash-retry loop; fallback to existing only when the marker is present;
  cancellation never falls back.

---

## 16. What this subsystem needs from others

- **Paths** (`mmcl-paths`): launcher root (client vs server), `config/`, `metadata/`,
  `installs/`, `runtime/`, `shared/assets/`, pack root from the sanitized name.
- **HTTP/downloads** (`mmcl-net`): GET to file with retry (3), unique temp file + atomic rename,
  https gate, offline flag, conditional GET / cache fallback for the version manifest, a per-launch
  `DownloadScope` for retry and progress notices, byte-progress tracker, generic User-Agent,
  `Accept` header support, GET to string.
- **Managed files** (`mmcl-files` or wherever `ManagedGameFile` lands): the contract in 10.3, the
  verify cache, `jar:` / zip-entry sources, `PrepareContext` + audit hook, `read_json`, `reverify`.
- **Shared download pool**: a process-wide bounded concurrency limiter + `await_all(timeout)`.
- **Config** (`mmcl-config`): min and max RAM, custom JVM args (already filtered of `${`), LWJGL
  ARM patch flag, always-verify-on-launch per manifest URL, scan frequency per pack, server heap
  override.
- **Game mode** (client/server) and TUI flag.
- **Auth** (`mmcl-auth`): the `Account` for the launch (refreshed token).
- **Modpack** (`mmcl-modpack`): `PackHandle` (12.3), file sync (`ContentSync`: mods, configs,
  resource packs, shader packs, initial files, image cache), VerifyState load/save,
  `OfficialLauncherExporter::compute_version_id` / `is_version_installed`.
- **Session** (`mmcl-session`): owns the `Child` after spawn, drains pipes (GameLog), cancellation
  token per session, runtime in-use hookup.
- **Security** (`mmcl-security`): `SecurityScanner::scan_pack_root`, `ScanFrequency::should_scan`.
- **Progress** (`mmcl-progress` / GUI): `LaunchProgress` / `StepProgress` implementations, the
  optional standalone runtime progress view.
- **Logging and redaction**: `SensitiveDataRedactor::redact`, tracing setup.
- **i18n**: localisation keys for every error and progress text (all keys referenced above already
  exist in `DisplayStrings.properties`).

## 17. What this subsystem provides to others

- `launch()` → a running `GameProcess` (GUI play, TUI, MCP `playAs`, server mode).
- `prepare()` / `verify_all_files_now()` (Settings → Security "Verify all game files", pack detail
  "Verify this pack").
- `RuntimeManager`: install, list, delete and in-use for the runtime-management GUI and TUI;
  `java_path` for anything that must run Java.
- `ModLoader` metadata (name, loader version, MC version) for UI chips; `uses_post_install_steps`.
- `VersionManifestService` (version list for the vanilla-version picker / `VanillaVersionManager`).
- Pure parsing crate `mmcl-mojang` (rules, Maven coordinates, client.json models) for the export,
  import and editor subsystems.
- `install_loader()` for the official-launcher export.
- `split_command_line` and `sanitize_title` utilities.

## 18. Open questions (to be put to the user)

- **Q-1** `versionRange` on Windows: emulate Java's `os.version = "10.0"` (parity: build-range
  rules never match) or report the real build `10.0.26100` (likely the intent of Mojang's 26.1
  rules)? Recommend parity first, behind one constant.
- **Q-2** Linux ARM64 runtimes: Mojang publishes none, and Java silently installs the x64 JRE. Fetch
  Adoptium or Liberica builds per major version for `linux-aarch64` (and keep the folder name
  `java-runtime-<x>`)? Recommend yes, with Liberica, since its client already exists.
- **Q-3** Which Java runs the Forge/NeoForge installer for Mojang-launcher export, now that there's
  no launcher JVM? Recommend the pack's own MC-version runtime component.
- **Q-4** Server mode used to inherit `-Xms`/`-Xmx` from the launcher's JVM flags (systemd unit
  files rely on this). Replacement: `--xms/--xmx` CLI flags and `MMCL_XMS/MMCL_XMX` env vars?
- **Q-5** The security scan (jarscanner) has no Rust equivalent. Run the Java scanner in a
  managed JRE, port its signatures, or ship without it initially?
- **Q-6** Move the macOS `-Xdock` flags into the JVM section (behaviour change: the dock name and
  icon would start working)? Recommend yes.
- **Q-7** Keep `${user_type}=mojang` and `${launcher_version}=2025.1` verbatim, or switch to `msa`
  and the real version? Recommend parity for now.
- **Q-8** GUI-mode child stdin: Java leaves an unused pipe. Use `Stdio::null()`?
- **Q-9** Create symlinks for Mojang runtime `link` entries on macOS/Linux (Java skips them)?
- **Q-10** Should the managed-file type (`ManagedGameFile`) and `PrepareContext` live in
  `mmcl-launch` or in a shared `mmcl-files` crate? Recommend `mmcl-files`, since the modpack sync
  uses it far more than the launch pipeline does.
