# Mica Minecraft Launcher

A cross-platform modpack launcher for Minecraft. Modpacks are defined as JSON files hosted on the internet; the launcher fetches the manifest, downloads the required modloader + mods + configs + assets, verifies them by SHA-1, and launches the game. Runs on Windows, macOS, and Linux as a client (GUI) or in headless server mode.

Supports **Forge**, **NeoForge**, **Fabric**, and vanilla Mojang versions. Imports Modrinth `.mrpack` files, MultiMC / Prism Launcher instance folders, and Mica-format ZIP exports.

## Installing & Running

Native installers (EXE/MSI on Windows, DMG/PKG on macOS, DEB/RPM on Linux) are produced by `mvn -B package` and live in `packaging/`. Installed builds open as a normal desktop app and default to client (GUI) mode.

You can also run the fat JAR from `target/*-jar-with-dependencies.jar` directly:

| Command | Effect |
|---|---|
| `java -jar launcher.jar` | Client mode (default; falls back to server in headless environments) |
| `java -jar launcher.jar -c` | Force client (GUI) mode |
| `java -jar launcher.jar -s` | Force server (headless) mode |
| `java -jar launcher.jar <modpack name>` | Open with the named installed modpack pre-selected |
| `java -jar launcher.jar -s <modpack name>` | Run the named modpack as a server |

Native app builds (DMG/EXE/etc.) only support client mode — the mode switches are ignored.

### `mmcl://` URL handler

The launcher registers an OS-level scheme so web pages can hand work back to the desktop app. Recognised actions:

- `mmcl://add?url=<manifest-url>` — register the pack URL (doesn't install)
- `mmcl://join?url=<manifest-url>` — install (if needed) and launch
- `mmcl://join?vanilla=<version-id>` — install the Mojang vanilla version and launch
- `mmcl://play?name=<pack-name>` — launch an already-installed pack
- `mmcl://open?name=<pack-name>` — focus / open the launcher on the pack's detail screen

Disabled per-launcher in Settings if you don't want the scheme handler active.

## Adding a Modpack

The Library screen's bottom bar has three add paths:

- **URL** — paste a Mica `manifest.json` / `manifest.mmcjson` URL (or a Modrinth, CurseForge, or TechnicPack project URL — the launcher detects the source and routes through the matching importer)
- **Import ZIP** — pick a `.mrpack` (Modrinth), Technic server pack, or Mica-format export `.zip`
- **Import MultiMC / Prism** — pick a Prism Launcher / MultiMC instance folder. The launcher reads `instance.cfg` + `mmc-pack.json`, generates a Mica manifest, and copies the `.minecraft/` contents (mods, configs, worlds, resourcepacks, shaderpacks) into the new pack folder. Quilt instances are imported as Fabric (largely compatible at runtime).

## Creating a Modpack

A modpack is a single JSON file hosted at a stable URL. Pack manifests can target Forge, NeoForge, Fabric, or vanilla Mojang versions; the launcher picks the right loader pipeline from the `packModLoader` field.

### Basic Forge example

```json
{
    "packName": "Example Forge Pack",
    "packVersion": "1.0.0",
    "packURL": "https://example.com/",
    "packLogoURL": "https://example.com/logo.png",
    "packMinRAMGB": "4",
    "packModLoader": "forge",
    "packModLoaderURL": "https://maven.minecraftforge.net/net/minecraftforge/forge/1.20.1-47.2.0/forge-1.20.1-47.2.0-installer.jar",
    "packModLoaderHash": "<sha-1 of the installer jar>",
    "packMods": [
        {
            "name": "Journey Map",
            "remote": "https://example.com/journeymap.jar",
            "local": "journeymap.jar",
            "sha1": "<sha-1>",
            "clientReq": true,
            "serverReq": false
        }
    ]
}
```

### Fabric / NeoForge

Swap `packModLoader` and point `packModLoaderURL` at the loader's installer / profile artifact:

- **Forge**: `"packModLoader": "forge"`, `packModLoaderURL` → Forge installer JAR (Maven Central `net.minecraftforge:forge:<mc>-<ver>:installer`)
- **NeoForge**: `"packModLoader": "neoforge"`, `packModLoaderURL` → NeoForge installer JAR (Maven `net.neoforged:neoforge:<ver>:installer`)
- **Fabric**: `"packModLoader": "fabric"`, `packModLoaderURL` → Fabric profile JSON (e.g. `https://meta.fabricmc.net/v2/versions/loader/<mc>/<loader>/profile/json`). `packModLoaderHash` may be left blank for Fabric — the profile JSON is content-versioned by Fabric.

Legacy Forge-only packs that still use `packForgeURL` + `packForgeHash` keep working — the launcher falls back to those when `packModLoader*` is absent.

### Adding an auto-join default server

If the pack is built for a specific multiplayer server, set the default-server fields and the launcher will append `--server` + `--port` to Minecraft's argv on every launch, so the user lands directly on the server's loading screen:

```json
{
    "packName": "MyServer Pack",
    "packVersion": "1.0.0",
    "packDefaultServerHost": "play.example.com",
    "packDefaultServerPort": 25565,
    "packDefaultServerName": "Example SMP"
}
```

(Add the three `packDefaultServer*` fields alongside your other top-level keys — they're optional and independent of the modloader / mods sections.)

| Field | Required | Notes |
|---|---|---|
| `packDefaultServerHost` | Yes (to enable) | Hostname or IP. Omit / leave blank to disable. |
| `packDefaultServerPort` | No | Defaults to `25565`. |
| `packDefaultServerName` | No | Display label shown in the pack's Server Favorites section. Defaults to the host. |

The launcher surfaces a "Pack default" row with an **Auto-join on play** checkbox in the modpack detail modal's Server Favorites section. Users can untick it to play singleplayer or join a different server without removing the field from your manifest — their choice persists in the pack's `server-favorites.json` sidecar.

### Manifest field reference

| Field | Required | Notes |
|---|---|---|
| `packName` | yes | Display name. |
| `packVersion` | yes | Pack version (compared against `installed.version` to surface updates). |
| `packURL` | yes | Website / Discord / GitHub URL — used by the "Pack Website" action. May be blank. |
| `packLogoURL` | yes | URL to a square logo PNG. |
| `packLogoSha1` | no | SHA-1 of the logo file. Logo is cached locally; hash mismatch triggers a re-download. |
| `packBackgroundURL` | no | Optional background image shown in the hero card / detail modal. |
| `packBackgroundSha1` | no | SHA-1 of the background. |
| `packMinRAMGB` | yes | Minimum RAM (GB). Launcher refuses to start the pack when max-RAM setting is below this. |
| `packUnstable` | no | `true` flags the pack as beta / pre-release. Surfaced as a "Beta" chip in the UI. |
| `packCustomDiscordRpc` | no | `true` suppresses the launcher's Discord rich-presence so the pack can drive its own. |
| `packModLoader` | yes¹ | One of `"forge"`, `"neoforge"`, `"fabric"`. Defaults to `forge` for back-compat with pre-multi-loader manifests. |
| `packModLoaderURL` | yes¹ | Loader installer / profile URL — see modloader notes above. Falls back to `packForgeURL` if absent. |
| `packModLoaderHash` | yes¹ | SHA-1 of the loader installer. Falls back to `packForgeHash`. May be blank for Fabric. |
| `packForgeURL` | legacy | Pre-multi-loader Forge installer URL. Still honoured as a fallback. |
| `packForgeHash` | legacy | Pre-multi-loader Forge installer SHA-1. Still honoured as a fallback. |
| `packDefaultServerHost` | no | Auto-join target for the pack — see the "Adding an auto-join default server" section. |
| `packDefaultServerPort` | no | Defaults to `25565`. |
| `packDefaultServerName` | no | Defaults to the host. |
| `packMods` | yes¹ | Mod list (see Mod / Config / File entries below). |
| `packConfigs` | no | Config-file list (managed by the launcher; deleted from pack folder if absent from manifest). |
| `packResourcePacks` | no | Resource pack list. |
| `packShaderPacks` | no | Shader pack list. |
| `packInitialFiles` | no | "Anything else" file list — placed at the pack root, useful for `options.txt`, custom JVM args files, etc. |
| `packScanExclusions` | no | List of file / folder names (relative to pack root) excluded from the security scan. |
| `packScanAcknowledgements` | no | Per-finding "I know about this, it's fine" silencers — see `ScanAcknowledgement.java` for the schema. |

¹ The launcher accepts vanilla / loaderless packs registered through `mmcl://join?vanilla=<id>`, in which case `packModLoader*` and `packMods` aren't applicable.

### Mod / Config / File entry shape

```json
{
    "name": "Mod Display Name",
    "remote": "https://example.com/the-mod.jar",
    "local": "mod-filename.jar",
    "sha1": "<sha-1 of the file>",
    "clientReq": true,
    "serverReq": true
}
```

| Field | Notes |
|---|---|
| `name` | Display label (mods only). |
| `remote` | Download URL. Must be HTTPS or `jar:` (jar: is supported for embedded artifacts). |
| `local` | Filename relative to the corresponding folder (`mods/`, `config/`, etc.). |
| `sha1` | SHA-1 hash. Set to `-1` to skip verification (use sparingly — defeats the integrity check). |
| `clientReq` | `true` to download in client mode. Mods / configs / initial-files only; resourcepacks + shaderpacks are always client-only. |
| `serverReq` | `true` to download in server mode. |

## Development

### Requirements

- **JDK 26** with JavaFX (e.g. Azul Zulu `jdk+fx` or AWS Corretto). Maven downloads its own JDK for the packaging step via `mvn-jlink-wrapper`, so the build doesn't strictly require JavaFX on your dev JDK if you're only running `mvn compile`.
- **Maven** (bundled with IntelliJ IDEA, or install separately).
- **Node.js** (only needed to re-run the i18n translation script under `tools/i18n/`).

### Building

```bash
# Compile only
mvn compile

# Run unit tests
mvn test

# Build fat JAR + native installer (EXE/MSI, DMG/PKG, DEB/RPM for the host OS)
mvn -B package

# DEV build (separate install dir + app name; runs side-by-side with prod)
mvn -Pdev package

# Clean target/ + packaging/
mvn clean
```

Build outputs:

- `target/*-jar-with-dependencies.jar` — runnable fat JAR
- `packaging/` — native installers (one per host OS)

### IntelliJ run configurations

| Configuration | Description |
|---|---|
| Core (Client) | Run the launcher in client (GUI) mode from the IDE |
| Core (Server) | Run the launcher in headless server mode |
| Core (Automatic) | Run with automatic mode detection |
| Package App | Build production fat JAR + native installer |
| Package App (DEV) | Build DEV fat JAR + native installer (side-by-side safe) |

### Localization

User-visible strings live in `src/main/resources/lang/DisplayStrings.properties` (English, source of truth). Per-locale files (`DisplayStrings_<bcp47>.properties`) are auto-generated by `tools/i18n/translate-locales.js`. After adding new keys to the English file:

```bash
cd tools/i18n
npm install        # first time only
npm run translate  # incremental — only translates new / changed keys
```

See `CLAUDE.md` for the full key-naming convention and `LocalizationManager` usage patterns.

### Contributing

Open a GitHub Issue for bugs / feature requests, or a pull request against `main` for code changes.

### Branch policy

`main` is protected. All work happens on a feature branch and merges through a pull request with at least one review. CI builds on Windows, macOS, and Linux for every PR — if any platform fails to build, the PR can't merge.
