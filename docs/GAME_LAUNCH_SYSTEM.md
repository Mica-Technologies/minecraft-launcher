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
  startGame() builds command line & launches ProcessBuilder
```

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

- **OS matching** -- name (`windows`/`osx`/`linux`), version (regex), arch (regex)
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
| `${auth_player_name}` | Logged-in username |
| `${version_name}` | MC version (vanilla) or Forge version |
| `${game_directory}` | Modpack root folder (quoted on Windows) |
| `${assets_root}` | Assets folder (quoted on Windows) |
| `${assets_index_name}` | Asset index version from manifest |
| `${auth_uuid}` | Player UUID |
| `${auth_access_token}` | OAuth access token |
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
| `GameVersionManifest` | `game/modpack/manifests/GameVersionManifest.java` | Mojang v2 version manifest, client.json caching |
| `GameLibraryManifest` | `game/modpack/manifests/GameLibraryManifest.java` | Library filtering, downloads, classpath building |
| `GameAssetManifest` | `game/modpack/manifests/GameAssetManifest.java` | Game resource (texture/sound) downloads |
| `ManifestRuleUtilities` | `game/modpack/manifests/ManifestRuleUtilities.java` | Rule evaluation, argument flattening |
| `ManagedGameFile` | `game/modpack/ManagedGameFile.java` | Base class for downloadable game files with SHA-1 |
| `GameModPackProgressProvider` | `game/modpack/GameModPackProgressProvider.java` | Progress callback for multi-step operations |
