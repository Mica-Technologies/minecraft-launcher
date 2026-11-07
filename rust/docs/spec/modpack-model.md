# Porting spec: modpack model, list management, fetch/cache and file sync

Slice: `src/main/java/com/micatechnologies/minecraft/launcher/game/modpack/` except
`manifests/`, `GameModLoader*.java`, `GameModPackLauncher.java` and `LoaderInstallerRunner.java`.

Covers the hosted modpack JSON format (`.mmcjson`), the in-memory pack model, the installed /
available pack lists, manifest fetch with offline cache and conditional GET, the install index,
file sync (mods, configs, resource packs, shader packs, initial files), hash verification and
the download gate, the per-pack sidecar files, pending-update diffs, the re-download audit log,
the update log, launch progress tracking, and the smaller helpers in the same package (news,
links, scan acknowledgements, scan frequency, server favourites, play-time labels, mod conflict
rules, backups, export, editor document, Modrinth update check, loader / vanilla version
catalogues). The `import_/` sub-package is summarised in section 1.18, since it only produces
manifests that this slice then consumes.

Read alongside: the loader spec (Forge / NeoForge / Fabric, `GameModPackLauncher`), the
`manifests/` spec (Mojang version / library / asset manifests), the files / paths spec
(`LocalPathManager`, `SynchronizedFileManager`, logger), the network spec (`NetworkUtilities`,
`DownloadExecutor`, `DownloadTracker`), the config spec (`ConfigManager`), and the security spec
(`SupplementalScanner`, `ScanExclusionPolicy`, jarscanner).

---

## 0. Ground rules for the Rust port

* **Folder layout must be byte-compatible with Java.** The Rust launcher uses its own data root
  for now, but every path below is relative to a data root that can later point at the Java
  one. Sidecar file names, JSON key names and line formats must match exactly, so a pack folder
  written by either launcher reads in the other.
* **Every manifest that loads in Java must load in Rust.** Gson is lenient in ways serde is not
  (section 1.2.3). The Rust model must reproduce that leniency.
* **Untrusted input.** Manifests come from a remote host (or a user-supplied file). Every field
  is attacker-controllable: `local` paths, URLs, RAM value, news text, scan exclusions. The
  guards listed in this spec are security fixes, not polish. Port all of them.
* **No global "current launch" state.** Several packs can prepare at once and share one download
  pool, so verify mode and audit target travel with the pack (`PrepareContext`), never in a
  global or thread-local.

### Data root layout (all relative to `<root>`)

`<root>` in Java is `~/.<LauncherNameWithoutSpaces>` in client mode and the current working
directory in server mode (`LocalPathManager.getLauncherLocalPath()`). That belongs to the paths
subsystem; this slice only needs the sub-paths:

| Path | Owner | Contents |
|---|---|---|
| `<root>/installs/` | this slice | one folder per pack, plus the two shared stores below |
| `<root>/installs/<SanitizedPackName>/` | this slice | the pack root ("game dir") |
| `<root>/installs/manifest_cache/<sha256hex(url)>.json` | fetcher | cached manifest body (UTF-8, verbatim) |
| `<root>/installs/manifest_cache/<sha256hex(url)>.meta.json` | fetcher | `{"etag":..., "lastModified":...}` |
| `<root>/installs/manifest_cache/<javaHashCodeHex(url)>.json` | fetcher (read only) | legacy cache name, read as a fallback |
| `<root>/installs/install_index.json` | install index | card subset of every installed pack |
| `<root>/installs/install_index.json.tmp.<N>` | install index | staging files; reaped when older than 5 min |
| `<root>/metadata/<sha1>.png` | environment | content-addressed logo / background images, shared across packs |
| `<root>/config/imported-manifests/*.json` | importers / editor / loader catalogue | local manifests addressed by `file:` URL |

Notes:

* The Java doc comment on `InstallIndex` says `modpacks/`; the real folder is `installs/`
  (`LocalPathConstants.MODPACK_FOLDER = "/installs"`). Use `installs`.
* `manifest_cache` cannot collide with a pack folder: sanitized names are `[A-Za-z0-9]` only, so
  they never contain `_`.

### Per-pack folder layout (`<packRoot>` = `<root>/installs/<SanitizedPackName>`)

| Path | Meaning |
|---|---|
| `bin/` | loader jar (`bin/modpack.jar`), `bin/minecraft.jar`, `bin/fabric-profile.json`, legacy `bin/logo.png` / `bin/background.png` constants (unused by the current cache) |
| `bin/natives/` | extracted natives |
| `mods/` | synced mods (`packMods`) |
| `config/` | synced configs (`packConfigs`) |
| `resourcepacks/` | client only (`packResourcePacks`) |
| `shaderpacks/` | client only (`packShaderPacks`) |
| `<local>` at pack root | `packInitialFiles` (e.g. `options.txt`) |
| `crash-reports/*.txt` | written by the game; read by `latest_crash_report` |
| `saves/` | worlds; backed up only when the include-saves setting is on |
| `.installed_version` | text: the `packVersion` of the last successful launch |
| `.launch_history` | text, 3 lines: `lastPlayedMs\ntotalPlayTimeMs\nlaunchCount\n` |
| `.seen_news` | text: one news id per line, trailing `\n` |
| `.verify_state.json` | JSON `VerifyState` (section 1.11) |
| `.image_cache` | JSON image sidecar (section 1.13) |
| `.audit_log` | JSON lines, one per re-download (section 1.12) |
| `.last_seen_remote_version` | text: last observed `packVersion` (no newline) |
| `.update_log.txt` | lines `epochMs|old|new` (section 1.14) |
| `server-favorites.json` | JSON favourites (section 1.16) |
| `.backups/<yyyy-MM-dd--HH-mm-ss>.zip` | pre-update backups (section 1.17) |
| `.logo_download.tmp`, `.background_download.tmp` | transient image download staging |

---

## 1. Purpose and behaviour

### 1.1 The available-packs list (`installable.json`)

* URL: `https://micauseaststorage.blob.core.windows.net/mc-launcher-api/installable.json`
  (`ModPackConstants.AVAILABLE_PACKS_MANIFEST_URL`).
* Shape: `{ "available": [ "<manifest url>", ... ] }` (key `AVAILABLE_PACKS_MANIFEST_LIST_KEY`).
  The Java reads it with `getAsJsonArray("available")` and `getAsString()` per element, so a
  missing key or a non-string element throws and aborts the whole available fetch (logged). The
  Rust port should skip bad elements instead.
* Fetched with the bounded fetch, cap 50 MiB, HTTPS only.
* The live list today contains 17 URLs, all under the same blob container, all ending in
  `.mmcjson` (e.g. `.../alto/manifest.mmcjson`, `.../alto/manifest-test.mmcjson`,
  `.../fabric-1.21.5/manifest.mmcjson`).

### 1.2 The modpack manifest format (`.mmcjson`)

File extension `.mmcjson` (`MODPACK_FILE_EXTENSION`), file-chooser description
"Mica Minecraft Modpack". Content is a single JSON object, UTF-8. Gson maps keys to Java fields
by exact name (no naming policy), ignores unknown keys, and leaves absent keys at the Java
default (`null` for objects and strings, `false` for `boolean`, `0` for numbers).

#### 1.2.1 Top-level fields

All fields are optional at the parser level. "Default" is what Gson leaves when the key is
absent.

| Key | JSON type | Default | Meaning / rules |
|---|---|---|---|
| `packName` | string | null | Display name. Also the pack folder name after sanitizing (`[^A-Za-z0-9]` removed). Required by the editor's validator. Two packs whose names sanitize alike share one folder. |
| `packVersion` | string | null | Version string. Compared with `.installed_version` for "update available" (section 1.9). Required by the validator. |
| `packURL` | string | null | Website link (not the manifest URL). |
| `packUnstable` | bool | false | Shows a beta badge. |
| `packCustomDiscordRpc` | bool | false | Pack supplies its own Discord RPC config (consumed by the Discord subsystem). |
| `packLogoURL` | string \| string[] | null | Logo URL(s). An array means several distinct showcase images that the UI cycles through (issue #43), not mirrors. See `StringOrArray`. |
| `packLogoSha1` | string | null | SHA-1 of the first logo image's bytes. Names the cache file `<sha1>.png`. |
| `packBackgroundURL` | string \| string[] | null | Same as logo, for the background. |
| `packBackgroundSha1` | string | null | SHA-1 of the first background. |
| `packMinRAMGB` | string (number accepted) | null | Minimum RAM in GB as a string, e.g. `"6"`. Parsed defensively (section 1.9). |
| `packForgeURL` | string | null | Legacy loader installer URL; fallback for `packModLoaderURL`. Empty string in non-Forge packs. |
| `packForgeHash` | string | null | Legacy SHA-1 of the installer; fallback for `packModLoaderHash`. |
| `packModLoader` | string | null | `forge` \| `neoforge` \| `fabric`, trimmed, matched case-insensitively. Absent / blank means `forge`. Unknown value fails at loader construction ("Unsupported modloader type"). |
| `packModLoaderURL` | string | null | Loader artifact URL: installer jar (Forge, NeoForge) or Fabric meta profile JSON, e.g. `https://meta.fabricmc.net/v2/versions/loader/1.21.5/0.16.10/profile/json`. Blank falls back to `packForgeURL`. |
| `packModLoaderHash` | string | null | SHA-1 of the above. Blank falls back to `packForgeHash`. May be empty for Fabric (no verification). |
| `packDefaultServerHost` | string | null | Default server for quick-join. Blank = none. Trimmed when used. |
| `packDefaultServerPort` | int | null | Port; null or outside 1..65535 means 25565. Live packs use a JSON number (`8585`). |
| `packDefaultServerName` | string | null | Display name; blank falls back to the host. |
| `packImportedSkipSync` | bool | false | Imported, user-owned content: file sync skips the floating-mod sweep and the mod download loop entirely. Set by the Prism/MultiMC importer. |
| `packScanExclusions` | string[] | null → `[]` | Names (relative to pack root) the security scan skips. Attacker-controllable: always pass through `ScanExclusionPolicy.filterUntrusted` before use (security spec). |
| `packScanAcknowledgements` | object[] | null → `[]` | Silences specific heuristic scan findings (section 1.15). |
| `packNews` | object[] | null → `[]` | News items (section 1.7). |
| `packLinks` | object[] | null → `[]` | Links (section 1.8). |
| `packMods` | object[] | null | Mods (`GameMod`, section 1.2.2). `null` vs `[]` matters (section 1.6). |
| `packConfigs` | object[] | null | Configs (`GameAsset`). |
| `packResourcePacks` | object[] | null | Resource packs (`ManagedGameFile`). Client only. |
| `packShaderPacks` | object[] | null | Shader packs (`ManagedGameFile`). Client only. |
| `packInitialFiles` | object[] | null | Files placed at the pack root (`GameAsset`), e.g. `options.txt`. Note: in practice synced every launch like configs, despite the editor calling them "written once". |

Keys that appear in manifests but that the launcher model ignores at runtime (they must survive
an editor round trip, so the editor works on a raw JSON document):

| Key | Written by | Meaning |
|---|---|---|
| `manifestFormat` | editor (`blank()` → `2`), Technic importer | Format version. 2 added per-entry `modrinthSlug`. Not read at runtime. |
| `packMinecraftVersion` | editor, Prism / Technic importers | Informational MC version for humans and for the editor's version filter. Not read at launch (the loader decides the MC version). |
| `modrinthSlug` (per entry) | editor | Modrinth project slug. Not read at runtime. |

#### 1.2.2 File-list entry fields

All five lists share the `ManagedGameFile` base shape. `packMods` adds `name`; `packMods`,
`packConfigs` and `packInitialFiles` add `clientReq` / `serverReq`. Resource and shader packs have
no side flags (they are client-only by list).

| Key | Type | Lists | Meaning |
|---|---|---|---|
| `name` | string | mods | Display name only. |
| `remote` | string | all | Download URL. Must be `https://` (or the trusted `jar:file:` form, section 1.4.4). Blank means "local-only file": accepted if it exists, error if missing. |
| `local` | string | all | Path relative to the list's folder (`mods/`, `config/`, `resourcepacks/`, `shaderpacks/`, or the pack root for initial files). May contain `/` sub-folders (e.g. `ldic/menu.json`). Must stay inside that folder (containment check). |
| `sha1` | string | all | Hex SHA-1. `"-1"`, `""`, whitespace or absent = no hash. |
| `md5` | string | all | Hex MD5. Same "no hash" rule. |
| `sha256` | string | all | Hex SHA-256. Same "no hash" rule. |
| `clientReq` | bool | mods, configs, initial files | Download / keep on the client. **Absent = false** in the runtime model (Gson default). |
| `serverReq` | bool | mods, configs, initial files | Download / keep on the server. **Absent = false** at runtime. |
| `modrinthSlug` | string | any | Editor-only. |

Hash precedence: strongest declared wins, SHA-256 then SHA-1 then MD5. Exactly one algorithm is
checked per file (no fallback to a weaker one when the strong one mismatches). Hex comparison is
case-insensitive and constant-time.

**Two different defaults for `clientReq` / `serverReq`.** The runtime model (Gson) reads an
absent flag as `false`, so a mod without `clientReq` is never downloaded on a client. The editor
model (`ModPackDocument.readFileList`) reads an absent flag as `true`. Every live manifest
(17 surveyed, 667 mod entries) writes both flags explicitly, so the difference has not bitten,
but the Rust port must keep both behaviours where they are (runtime: false; editor: true) or
deliberately unify them as a recorded decision (see open questions).

Survey of the live modpacks repo (all `.mmcjson` files under `minecraft-launcher-modpacks`): every
top-level key above appears except `packImportedSkipSync` and `packShaderPacks`; `packMods`
entries carry `name, remote, local, clientReq, serverReq, sha256` always, `sha1` in 473 of 667,
`md5` in 194; `packNews` entries carry `id, title, body, date, type` always, `url` in 279 of 313,
`pinned` in 5. `packBackgroundURL` is an array in the Alto pack; `packLogoURL` is always a bare
string today.

Example (trimmed from the live Alto manifest):

```json
{
  "packName": "Alto",
  "packVersion": "26.11.4.3",
  "packCustomDiscordRpc": true,
  "packURL": "https://minecraft.micatechnologies.com",
  "packLogoURL": "https://micauseaststorage.blob.core.windows.net/mc-launcher-api/alto/pack.png",
  "packLogoSha1": "54787d3bf0957c79da8b9e097be07616d8244b0e",
  "packBackgroundURL": [".../backgroundC.png", ".../backgroundB.png", ".../backgroundA.png"],
  "packBackgroundSha1": "29dd5a74221ff67df5894492b374498d7df31af6",
  "packMinRAMGB": "6",
  "packDefaultServerHost": "alto.tacomano.micatechnologies.com",
  "packDefaultServerPort": 8585,
  "packDefaultServerName": "Alto Minecraft Server",
  "packForgeURL": "https://maven.minecraftforge.net/net/minecraftforge/forge/1.12.2-14.23.5.2860/forge-1.12.2-14.23.5.2860-installer.jar",
  "packForgeHash": "5e8a91f71ef3d1f77de3f8d3261aedcc2f551c9d",
  "packScanExclusions": ["dynmap"],
  "packScanAcknowledgements": [{ "innerSha256": "7a43...", "kind": "LAUNCHER_CREDENTIAL_FILE_REF",
                                 "locator": "optifine/Installer.updateLauncherJson", "reason": "..." }],
  "packNews": [{ "id": "2026-11-04-v26.11.4.3", "title": "...", "body": "...", "date": "2026-11-04",
                 "type": "update", "url": "https://github.com/..." }],
  "packLinks": [{ "title": "Website", "url": "https://minecraft.micatechnologies.com", "description": "..." }],
  "packMods": [{ "name": "3D Skin Layers", "remote": "https://cdn.modrinth.com/...jar", "local": "3d-skin-layers.jar",
                 "sha1": "332d...", "clientReq": true, "serverReq": false, "sha256": "d1b3..." }],
  "packConfigs": [{ "remote": ".../csm_pack.cfg", "local": "csm_pack.cfg", "sha1": "8d50...",
                    "clientReq": true, "serverReq": false, "sha256": "3386..." }],
  "packResourcePacks": [{ "remote": ".../alto-resource-pack.zip", "local": "alto-resource-pack.zip",
                          "sha1": "9ea3...", "sha256": "4a4b..." }],
  "packInitialFiles": [{ "remote": ".../options.txt", "local": "options.txt", "sha1": "-1",
                         "clientReq": true, "serverReq": false, "sha256": "-1" }]
}
```

A minimal Fabric manifest (live):

```json
{ "packName": "Fabric 1.21.5", "packVersion": "0.16.10", "packURL": "https://fabricmc.net",
  "packLogoURL": "https://fabricmc.net/assets/logo.png", "packMinRAMGB": "2",
  "packModLoader": "fabric",
  "packModLoaderURL": "https://meta.fabricmc.net/v2/versions/loader/1.21.5/0.16.10/profile/json",
  "packModLoaderHash": "", "packForgeURL": "", "packForgeHash": "",
  "packMods": [], "packConfigs": [], "packResourcePacks": [], "packInitialFiles": [] }
```

#### 1.2.3 Gson leniency the Rust parser must reproduce

The shared Gson instance (`JSONUtilities.getGson()`) is a plain `GsonBuilder().create()` with one
type adapter (`StringOrArray`). Gson 2.14 behaviours that matter:

1. **Unknown keys are ignored.** Use `#[serde(default)]` everywhere and never
   `deny_unknown_fields`.
2. **JSON `null` = absent** for every field.
3. **Type coercion on primitives.** Gson's `String` adapter accepts a JSON number or boolean and
   stores its text (`"packMinRAMGB": 6` reads as `"6"`). Its `boolean` adapter accepts the
   strings `"true"` / `"false"` (via `Boolean.parseBoolean`, so any other string is `false`).
   Its `Integer` adapter accepts numeric strings (`"8585"`). A JSON number with a fraction into
   an `Integer` field fails. Provide `lenient_string`, `lenient_bool`, `lenient_opt_i32`
   deserializers.
4. **Duplicate keys: last one wins** for reflective objects. `serde_json` derived structs reject
   duplicates. Parse to `serde_json::Value` first (whose map keeps the last value), then convert,
   or write a visitor that overwrites.
5. **`null` array elements** become `null` list entries in Java, which later throw NPEs in sync.
   Rust: drop null elements when deserializing lists.
6. **Lenient syntax.** `Gson.fromJson(String, Class)` parses with the reader in lenient mode
   unless a strictness was set (it was not). Lenient mode accepts `//`, `/* */` and `#` comments,
   unquoted keys and values, single-quoted strings, `;` and `=>` separators, and a top-level
   value followed by nothing else. No live manifest relies on this. Recommended: try
   `serde_json` first; if it fails, retry with `json5` and log a "non-standard manifest" warning.
   Edge cases (lenient arrays treat `[1,]` as `[1, null]`) are not worth matching.
7. **Objects created without a constructor.** Gson instantiates `GameMod`, `GameAsset` and
   `ManagedGameFile` via `Unsafe` (none has a no-arg constructor), so their constructor logic
   never runs for manifest-loaded entries: `local` keeps its `/` separators (the constructor's
   `/`→`File.separator` replacement only runs for code-built entries), hash fields stay `null`
   instead of `"-1"`, `localPathPrefix` is `null` (not `""`) until sync sets it, and
   `sessionVerified` is `false`. Rust: model hashes as `Option<String>` normalised by
   `usable_hash()`, and treat `local` as a `/`-separated relative path that is joined component by
   component.

`StringOrArray` (shared utility, `utilities/StringOrArray.java`) rules:

* Deserialize: JSON null → empty; array → each non-null element's string form; any primitive →
  its string form (number / bool coerced). Then trim each value and drop blanks, keep order.
* Serialize: empty → JSON `null`; one value → bare string; two or more → array.
* Equality is list equality (used by `InstallIndex.Entry::same_content`).
* `first()` = first value or none; `all()` = the list.

### 1.3 The pack model (`GameModPackMetadata` + `GameModPack`)

`GameModPackMetadata` holds the Gson fields and pure accessors; `GameModPack` extends it with
runtime behaviour and transient state. In Rust split this into:

* `PackManifest`: the immutable, deserialized manifest (pure data, `mmcl-manifest` crate).
* `GameModPack`: `Arc`-shared runtime object = `PackManifest` + identity + transient caches,
  in `mmcl-modpack`.

#### 1.3.1 Identity and transient state

| Java field | Meaning | Rust |
|---|---|---|
| `manifestUrl` (package field) | The URL the pack was loaded from (`https://`, or `file:` for imported/local). Set by every fetch path, including failures. | `manifest_url: Option<String>` |
| `manifestContentSha256` | Lowercase hex SHA-256 of the manifest body's UTF-8 bytes as loaded (cache or network). Null for factory packs. Drives fast-path verify and scan-frequency decisions. | `manifest_sha256: Option<String>` |
| `vanillaVersion`, `vanillaMinecraftVersion` | Vanilla "pack" built by `createVanillaModPack`. | `kind: PackKind::Vanilla { mc_version }` |
| `failedLoad` | Placeholder from `createFailedModPack`; must never be synced or launched. | `PackKind::Failed { error }` |
| `stub` | Built from an install-index entry; only card fields are set. Must not be synced or launched until upgraded. | `PackKind::Stub` |
| `quickJoinServer` | One-shot explicit server for the next launch. | `Mutex<Option<ServerFavorite>>` |
| `progressProvider` | Progress sink for this pack's operations. | owned by the launch session, passed explicitly, not stored on the pack |
| `environment` | Lazy dirs + image cache helper. | `OnceLock<PackEnvironment>` |
| `launcher` | Lazy `GameModPackLauncher` (other slice). | not stored; launch slice constructs per launch |
| `cachedModLoader` | Memoized loader object (its construction verifies the installer on disk, so it is cached to avoid re-hashing and log spam on every card render). | `OnceLock<Arc<dyn ModLoader>>` (fallible init: use `Mutex<Option<..>>`) |
| `cachedLibraryManifest` | Memoized Mojang library manifest (2-4 MB `client.json` parse). | same pattern |
| `prepareContext` | Current prepare run (volatile; null reads as NONE). | `ArcSwap<PrepareContext>` or `RwLock<PrepareContext>` |
| `updateAvailable` (metadata) | Cached result of the update check; reset by `saveInstalledVersion`. | `Mutex<Option<bool>>` |
| `cachedLastPlayedMs/TotalPlayTimeMs/LaunchCount` | Cached `.launch_history`; `-1` = not loaded. | `Mutex<Option<LaunchHistory>>` |

The Java `setProgressProvider` resets the cached launcher so the next launch rebinds to the new
sink; `swapProgressProviderTransiently` swaps the sink without resetting it (used mid-launch for
the security-scan row; resetting there orphaned `getLastLaunchedProcess()` and left the UI stuck
on the progress screen). In Rust, pass the progress sink as a parameter to each operation and
the whole class of bug disappears.

A pack re-fetched from the network is a **new object**: the caches above are per instance, so a
refresh naturally re-verifies the loader and re-parses manifests.

#### 1.3.2 Factory packs

* `create_vanilla(version_id)`: name = localized `modpack.vanilla.name` = `"Minecraft {0}"`
  (all locales seen use the same text, but it is a localized string), version = `version_id`,
  `packURL` = `https://minecraft.net`, logo/background = bundled defaults, `packMinRAMGB = "2"`,
  `packMods = []`, kind Vanilla. Its folder is the sanitized localized name
  (`Minecraft1204` for 1.20.4); if a translation ever changes that string the folder moves.
  Rust: derive the vanilla folder name from a fixed, non-localized `"Minecraft {version}"`.
* `create_failed(manifest_url, error)`: name = `modpack.failed.name` = `"[Failed to load] {0}"`
  where `{0}` is the URL, shortened to `"..." + last 37 chars` when longer than 40; version =
  localized `modpack.failed.version` (`"Error"` in English); `packMinRAMGB = "2"`; default
  images; `packMods` stays null; kind Failed. Its friendly name is non-null, so failed packs do
  appear in lists (installed and available).
* `create_stub_from_index(url, entry)`: copies `packName, packVersion, packURL, packLogoURL,
  packLogoSha1, packBackgroundURL, packBackgroundSha1, packMinRAMGB, packUnstable,
  packCustomDiscordRpc`; kind Stub.
* `NULL_MODPACK()`: "no selection" placeholder: name `modpack.none.name`, version
  `modpack.none.version`, logo = `GUIConstants.URL_MINECRAFT_NO_MOD_PACK_IMAGE`. GUI only.

#### 1.3.3 Accessors and derived values

* `sanitized_name()` = `packName` with every char outside `[A-Za-z0-9]` removed (ASCII only:
  Java's `[^a-zA-Z0-9]` regex removes accented letters, CJK, etc.). A name that sanitizes to
  `""` gives pack root `<root>/installs/` itself. Guard against that in Rust (refuse to sync a
  pack whose sanitized name is empty).
* `pack_root()` = `<root>/installs/<sanitized_name>`; `bin_folder()` = `<packRoot>/bin`.
* `friendly_name()` = `format!("{}: {}", name, version)` (`MODPACK_FRIENDLY_NAME_TEMPLATE =
  "%s: %s"`); None when name is None. `toString()` returns this.
* `settings_key()`: `manifestUrl` if non-blank; else `"vanilla:" + mc_version` for vanilla; else
  None. Used as the key for per-pack settings because vanilla names are localized.
* `mod_loader_type()`: None for vanilla; `packModLoader` trimmed + lowercased, or `"forge"` when
  absent / blank.
* `mod_loader_url()` / `mod_loader_hash()`: the new field when non-blank, else the legacy
  `packForge*` field.
* `uses_post_install_steps()`: false for vanilla and Fabric; true for Forge / NeoForge (drives
  whether the "Game patching" progress row exists). Must not construct the loader.
* `minecraft_version()`: vanilla version, else delegated to the loader (other slice).
* `loader_name()` / `loader_version()`: None for vanilla, else from the loader.
* `min_ram_gb() -> f64`: blank / null → 0.0; trimmed, parsed as f64; NaN or negative → 0.0;
  clamped to at most 1024.0; parse error → 0.0. Never panics.
* `scan_exclusions()`, `scan_acknowledgements()`, `news()`, `links()`: never null (empty list).
* `visible_news(today)`: items that are renderable and not expired, sorted pinned first, then by
  `sort_epoch` descending (undated last). Stable sort.
* `visible_links()`: renderable links in manifest order.
* `default_server()`: None when host blank; port defaulted as above; name falls back to host
  (untrimmed host as the name fallback; the host itself is trimmed).
* `consume_quick_join_server()`: take-and-clear the explicit target; if none, the default
  server unless `ServerFavoritesStore::is_default_server_disabled(pack)`. Single funnel for
  both Play and Connect.
* `has_custom_logo()` / `has_custom_background()`: true when any declared URL differs from the
  bundled default URL.
* `logo_url()` / `background_url()`: first declared URL (no caching side effect);
  `logo_url_count()` / `background_url_count()`: number declared.
* `latest_crash_report()`: newest `*.txt` by mtime in `<packRoot>/crash-reports/`, only if
  modified within the last 60 s; content as UTF-8 string; None otherwise or on read error.

The bundled default image URLs (`MODPACK_DEFAULT_LOGO_URL`, `MODPACK_DEFAULT_BG_URL`) are, in
Java, `Class.getResource(...)` URLs (`file:` or `jar:file:` paths that depend on the install
location). They get written into generated manifests (loader catalogue, factory packs). After a
launcher reinstall in a different folder those stored URLs no longer match the constants, are
treated as custom, and fail the HTTPS check. Rust: use stable sentinels (for example
`mmcl-bundled:logo.png`, `mmcl-bundled:background.png`), and also recognise any `file:`/`jar:`
URL ending in `/micaminecraftlauncher.png` or `/default_background.png` as the bundled default
when reading old manifests.

#### 1.3.4 Security scan entry point (`scanModPackRootFolder`)

Owned by the security subsystem in Rust, but the pack-side orchestration lives here:

1. Threads = `max(1, cores / 2)`.
2. `safe_exclusions = ScanExclusionPolicy::filter_untrusted(pack.scan_exclusions())`.
   Both scan passes use only the filtered list (a manifest must not be able to exclude `mods/`
   or the root).
3. Run the Nekodetector (Fractureiser) scan over the pack root; stdout lines are stripped of ANSI
   colour codes and sent to the progress sink's detail text.
4. Run the supplemental heuristic scanner; an I/O error there is logged and ignored.
5. Drop supplemental findings matched by any `ScanAcknowledgement` (section 1.15); log each
   acknowledged finding with its reason.
6. For each Nekodetector stage-1/stage-2 hit: warning + "cannot be acknowledged" hint. For each
   remaining supplemental finding: progress line (HIGH = infection, MEDIUM = warning), warning
   log, and a paste-ready acknowledgement JSON snippet (prefer `innerSha256`, show `fileSha256`
   commented out as the alternative; if neither hash is known, log that no snippet can be made).
   Snippet strings are escaped for `\` and `"` only.
7. Blocking set = stage-1 + supplemental HIGH (+ stage-2). If non-empty, throw
   `ModpackScanDetectionException(Results(blockingHigh, stage2), packName, packRoot)`.
   Supplemental MEDIUM never blocks.

### 1.4 `ManagedGameFile`: verify, download, and the gates

Every downloadable file (mods, configs, resource packs, initial files, and in other slices the
loader installer, libraries, assets and Mojang manifests) is a `ManagedGameFile`.

#### 1.4.1 State

* `remote: Option<String>`, `local: String` (relative), `sha1/md5/sha256: Option<String>`.
* `local_path_prefix` (folder the `local` path is resolved against; empty = `local` is already a
  full path).
* `session_verified: bool` (per instance).
* `md5_only_warned: bool` (log the "MD5 only" debug line once per instance).
* `download_tracker: Option<Arc<DownloadTracker>>` for byte progress.
* JSON parse memo for `read_to_json` keyed by `(mtime, len)` (manifests slice uses it).

`full_local_path()` = prefix + separator + local (no double separator if the prefix already
ends with one), or `local` when the prefix is empty. `file_name()` = last component.

#### 1.4.2 `verify(context) -> bool`

1. **Containment gate**: if a prefix is set, `absolute(normalize(full_path))` must start with
   `absolute(normalize(prefix))` (component-wise, lexical normalisation: `.` and `..` removed
   without touching the filesystem, like Java `Path.normalize()`). On failure log
   `log.managedGameFile.outsidePackFolder` and return false. Any exception → false. No prefix →
   no check (legacy callers that pass full paths).
2. **FAST_PATH**: when `context.verify_mode == FastPath`, return
   `exists && is_file && len > 0`. No hashing.
3. **FULL**: strongest usable hash first. Usable = non-null, non-blank, not `"-1"`.
   Hash through the **process-wide verify cache** keyed by
   `(absolute path string, mtime ms, size, expected hash, algo)`; a miss computes and stores the
   result. A file that is touched or rewritten gets a new key, so no eviction is needed; the cache
   is unbounded and that is fine (one entry per file per manifest). Concurrent duplicate work is
   benign. If only MD5 is declared, log a one-time debug note.
4. No usable hash → `exists && is_file`.

Hash comparison: lowercase hex of the digest vs the declared value, case-insensitive,
constant-time. A missing file never verifies.

#### 1.4.3 `update(context) -> Result<bool /*changed*/>`

Order matters; each step is a fix for a past bug:

1. `context` null → `PrepareContext::NONE`.
2. **Session flag skips the hash, never the existence check.** If `session_verified` and the
   file `is_file && len > 0`, return `Ok(false)`. Otherwise clear the flag and continue. (A file
   verified earlier in the session can be deleted by the user or a content-browser action; the
   old code trusted the flag forever and the post-sync check then aborted with the file
   missing.)
3. **No remote URL** (blank): if the file exists and is a file, mark verified and return
   `Ok(false)`; else error `managedFile.error.missingNoUrl` and do **not** mark verified (so the
   next launch fails the same clear way). Avoids futile downloads on an empty URL.
4. `verify(context)`. If it passes: mark verified, `Ok(false)`.
5. Verify failed and **offline**: error `managedFile.error.offlineHashMismatch` when the file
   exists (refuse to launch unverified bytes), else `managedFile.error.offlineMissing`. Never
   accept mismatched content offline.
6. Online: warn `verifyFailedRedownload`. **Audit capture**: if `context.is_auditing()` and the
   file already exists, hash it with the declared algorithm before the download.
7. `download()`.
8. If audited: hash again after, then `context.record_redownload(full_path, old, new, expected,
   algo)`.
9. Mark verified, `Ok(true)`.

`reverify()` = clear the session flag, then `update(self.prepare_context())`. For callers about
to execute the file (loader installer), which must not trust an earlier check.

`prepare_context()` defaults to NONE; files owned by a pack (loader, manifests) return the
pack's current context. In Rust pass the context explicitly everywhere.

#### 1.4.4 `download()`

1. Containment gate again (this is the write side): error `managedFile.error.outsidePack`.
2. Parse `remote` as a URL. Accept only:
   * `https` (case-insensitive scheme); or
   * `jar:` where `is_jar_url_contained_in_launcher(remote)`: the URL starts with `jar:`, has a
     `!/`, the inner part (between `jar:` and `!/`) is a `file:` URL, and its path, made
     absolute and normalised, starts with the launcher data root. Any parse failure → reject.
     These URLs are built by the Forge loader code to pull embedded Maven artifacts out of the
     already-verified installer jar (`jar:file:/.../installs/<pack>/bin/modpack.jar!/maven/...`);
     a manifest cannot make the launcher read arbitrary local files.
   Anything else (`http`, `file`, `ftp`, `jar:http`) → error `managedFile.error.nonHttps`.
3. Create parent directories.
4. **Post-download integrity gate**, up to 3 attempts: download (with the byte tracker when
   set), then if there is no usable hash or `matches_declared_hash(file)` succeeds, clear the
   JSON memo and return. Otherwise delete the file, log `hashRetry attempt/3`, retry. After 3
   failures error `managedFile.error.hashFailed`. `matches_declared_hash` always hashes for real
   (no fast path, no cache), strongest declared algorithm only, returns true when nothing is
   declared. (Without this a compromised mirror's bytes were placed on the classpath, detected
   next launch, re-downloaded from the same source and accepted again, forever.)
5. I/O errors → `ModpackException(UNABLE_DOWNLOAD_FILE_LOCALLY_TO_TEXT + path, cause)`.

`jar:` download in Rust: open the outer jar with `zip`, read the entry, write to the target.
The network layer's plain HTTPS downloader cannot do this; the file layer needs a small
`fetch_to_file(url, dest, tracker)` that dispatches on scheme.

#### 1.4.5 Subclasses

* `GameMod` adds `name`, `client_req`, `server_req`; `is_required_for(mode)` =
  `(client && client_req) || (server && server_req)`; `update(mode, ctx)` returns `Ok(true)`
  without doing anything when not required.
* `GameAsset` (configs, initial files; also Forge library/asset entries in the loader slice):
  same side flags, `update(mode, ctx)` is a no-op when not required.
* `GameLibrary` (loader slice): adds OS applicability and native flag.

### 1.5 Prepare context and verify mode

`PrepareContext` (immutable): `verify_mode: VerifyMode { Full, FastPath }`,
`audit_pack_root: Option<PathBuf>`, `audit_launch_id: i32` (-1 when none).

* `NONE` = Full, no audit. Used outside any prepare run and for "Verify this pack now"
  (`full_verify()` returns NONE: hash everything, record nothing, because there is no launch
  number).
* `for_launch(mode, pack_root, launch_id)`.
* `is_auditing()` = `audit_pack_root.is_some()`.
* `record_redownload(...)` forwards to `ModPackAuditLog::record_redownload`.

The launcher (other slice) sets the pack's context for the length of a run and restores the
previous one after. Two packs preparing at once each verify under their own context even though
their downloads share one pool (test `oneLaunchesFastPathNeverReachesAnotherLaunchesFullVerify`).

### 1.6 File sync (`GameModPackFileSync`)

Constructed per sync with the pack and an optional progress sink. On construction, when a sink
is present, sets the shared download tracker's total file count to
`len(mods) + len(configs) + len(initial files)` plus resource packs and shader packs when not in
server mode.

Game mode (client / server) comes from the global `GameModeManager`; in Rust pass it in.

#### 1.6.1 `fetch_latest_mods()`

1. If `packImportedSkipSync`: progress "imported skip" 100, return.
2. **Incomplete-manifest guard**: if the pack is a Stub or Failed, error
   (`fileSync.error.manifestFetchFailed` / `fileSync.error.manifestStub`). Must come before the
   sweep. (A headless server once booted with an empty mods folder because a stub's null mod
   list read as "no mods".)
3. `clear_floating_mods()` then progress "floating removed", +30.
4. `packMods` null → progress "no mods" 100, return (legitimate: fully-loaded manifest without
   the key).
5. Prefix = `<packRoot>/mods`. If the list is **non-empty** (a past bug used `size() > 1` and
   skipped single-mod packs), submit one task per mod to the shared bounded download pool:
   set prefix + byte tracker, `mod.update(mode, ctx)`, increment an atomic "processed" counter,
   progress text `downloadingMods done/total` with `+70/total` section progress, tracker
   `complete_file()`. The detail text is an aggregate count on purpose: per-mod names from ~16
   parallel threads were last-writer-wins and showed the wrong mod.
   Wait for all with a 30-minute bound; on timeout / interrupt / first failure, cancel the
   remaining tasks and error (`modsTimedOut`, `modsInterrupted`, `modsFailed: <cause message>`).
6. **Post-sync existence assertion**: every mod required for this side must exist as a
   non-empty file; otherwise error `fileSync.error.modsMissing(pack, missingCount,
   requiredCount, comma-joined file names)`. Side-only mods for the other side are excluded.
   Debug log on success with the count.

#### 1.6.2 `clear_floating_mods()`

* **Data-loss guard**: `packMods` null → warn and return (never sweep without an authoritative
  list). An empty non-null list is valid and deletes every file in `mods/`.
* Valid set = full paths of mods required for the current side.
* For each direct child of `mods/` that is a file and whose path string is not in the set:
  delete; log an error if delete fails. Directories are left alone.
* Java compares path **strings** (`File.getPath()` vs the built full path). Consequences the
  Rust port should fix deliberately: a `local` with different case from the file on disk
  (Windows / macOS) gets deleted and re-downloaded every launch; disabled jars
  (`foo.jar.disabled`, created by the content browser and the conflict prompt) are deleted at
  the next launch of a managed pack, and the mod comes back enabled. Compare normalised paths,
  case-insensitively on Windows and macOS. Whether to keep `.disabled` copies of managed mods
  is an open question.

#### 1.6.3 Configs, resource packs, shader packs, initial files

Sequential (not pooled), same pattern each:

| List | Prefix | Side handling | Null list |
|---|---|---|---|
| `packConfigs` | `<packRoot>/config` | `GameAsset::update(mode)` skips other-side entries | progress "no configs" 100 |
| `packResourcePacks` | `<packRoot>/resourcepacks` | whole list skipped in server mode | "no resource packs" 100 |
| `packShaderPacks` | `<packRoot>/shaderpacks` | whole list skipped in server mode | "no shader packs" 100 |
| `packInitialFiles` | `<packRoot>` | `GameAsset::update(mode)` | "no initial files" 100 |

Per entry: set prefix and tracker, detail text `downloading <file name>`, update, progress
`verified <file name>` with `+100/len`, tracker `complete_file()`. Errors propagate and abort.

(The log4j hardening config is applied by the launcher slice during launch, not here.)

#### 1.6.4 Environment preparation

`prepare_environment()` creates (if missing) `<packRoot>/bin`, `mods`, `config`, `bin/natives`,
and in client mode `resourcepacks` and `shaderpacks`. Parent of `bin` (the pack root) created
with `create_dir_all`; the rest with single `mkdir`.

### 1.7 News items (`packNews`)

Fields: `id, title, body, date, type, url, pinned (bool), expires`. All author-supplied,
rendered as plain text only.

* `title()`: trimmed, clamped to 200 chars (Java `String.length` counts UTF-16 units; Rust should
  clamp on char boundaries, 200 chars), None if blank.
* `body()`: same, 2000.
* `id()`: trimmed, None if blank. Items without an id are never counted as unread.
* `kind()`: `info` / `update` / `warning` / `event`, case-insensitive; anything else or absent →
  `Info`. Glyph and chip class: Info `ℹ` `stat-chip`; Update `⬆` `stat-chip-success`; Warning
  `⚠` `stat-chip-warn`; Event `★` `stat-chip`. (Chip classes are JavaFX CSS names; the Rust UI
  maps them to its own tokens.)
* `url()`: `UrlValidation.sanitizedHttpUrl`: trim, parse as URI, accept only `http`/`https`
  scheme (case-insensitive), return the parsed URI string; else None.
* Date parse (`date`, `expires`): `yyyy-MM-dd`, else an ISO offset date-time converted to UTC and
  truncated to the date; else None.
* `is_expired(today)`: expiry parses and `today >= expiry`.
* `sort_epoch()`: `epochDay * 86400`, or `i64::MIN` when undated.
* `display_date()`: locale medium date format; None if unparseable (use ICU4X or a small table).
* `is_renderable()`: title present.

Read tracking (`.seen_news`, one id per line):

* `seen_news_ids()`: trimmed non-empty lines; empty set if missing/unreadable.
* `unread_news_count()`: visible items with an id not in the set.
* `mark_all_news_seen()`: union of currently visible ids with the existing file contents
  (read-modify-write under the per-path lock, so rotated-out ids stay seen), written as
  `ids.join("\n") + "\n"`, insertion order (visible first, then existing). No-op when there are
  no trackable ids. Errors logged.

### 1.8 Links (`packLinks`)

Fields `title, url, description`. `title()` clamp 120, `description()` clamp 500, `url()`
sanitized http(s), `is_renderable()` = title and url both present. Order preserved.

### 1.9 Installed version, update check, launch history

All via `PackDotFile` (section 1.10).

* `installed_version()`: `.installed_version` trimmed, or None.
* `save_installed_version()`: writes `packVersion` (no newline), resets the cached update flag.
  Call after a successful launch.
* `is_update_available()`: cached; false when no installed version or no remote version; else
  `compare_versions(installed, remote) == -1`. Any panic → log and false (it runs during card
  rendering).

`compare_versions(a, b)` (utilities, needed here): strip from the first `-`, then from the first
`+`, then from the first `_`; empty → `"0"`; split on `.`; compare segment by segment, missing
segments = 0; each segment's value = its leading ASCII-digit run parsed as i32 (empty or overflow
→ 0). Returns -1 / 0 / 1.

Launch history `.launch_history` = `"<lastPlayedMs>\n<totalPlayTimeMs>\n<launchCount>\n"`:

* `record_launch_start()`: atomic modify: parse existing total (line 2) and count (line 3),
  ignore parse errors (warn); write `now, total, count+1`; update cache inside the lock.
* `record_session_end(duration_ms)`: atomic modify: lastPlayed = line 1 (fallback now), total =
  line 2 + duration (fallback duration), count = line 3 (fallback 1); write; update cache.
* Lazy load: lines parsed with `trim().split('\n')`; any error → all zeros; missing file → zeros.
* `last_played_ms()`, `total_play_time_ms()`, `launch_count()`, `is_never_played()` (=
  last played 0).

Labels (`PlayTimeFormatting`, pure, returns key + amount; the UI localizes):

* `last_played(last, now)`: `last == 0` → `metadata.lastPlayed.never`; `elapsed < 60_000`
  (including negative, i.e. a future timestamp) → `metadata.lastPlayed.justNow`;
  `< 3_600_000` → minutes (`minuteAgo` if 1 else `minutesAgo`); `< 86_400_000` → hours
  (`hourAgo` / `hoursAgo`); else days (`dayAgo` / `daysAgo`). Integer division.
* `total_play_time(ms)`: `0` → `gameModPackMetadata.totalPlayTime.zero`; minutes =
  `ms / 60_000`; `< 60` → `minute` (1) / `minutes` (whole number; note sub-minute non-zero gives
  `minutes` with 0, not the zero key); hours = `minutes / 60.0`; `< 24` → `hours` (fraction);
  else `days` = `hours / 24.0` (fraction). Minute keys format as integers, hour/day keys keep the
  fraction.

### 1.10 `PackDotFile` (sidecar helper)

Every per-pack text sidecar goes through it, holding the canonical per-path lock from the files
subsystem (`SynchronizedFileManager.getSynchronizedFile(path)` monitor):

* `read(packRoot, name) -> Option<String>`: None when absent or unreadable.
* `read_lines(...) -> Vec<String>`: empty when absent or unreadable.
* `write(packRoot, name, content)`: create the pack root, write UTF-8.
* `modify(packRoot, name, f: FnOnce(Option<String>) -> String)`: read, transform, write, all
  under the lock. The transform may update in-memory caches.

Rust: a global `PathLocks` map (`DashMap<PathBuf, Arc<Mutex<()>>>`) from the files crate, keyed
by the normalised absolute path. This only serializes within one process; that matches Java.

### 1.11 Verify state, fast path, scan frequency

`<packRoot>/.verify_state.json`, Gson default (compact) output:

```json
{"manifestSha256":"ab12...","verifiedAt":1736812800000,"launcherVersion":"2026.3",
 "lastScannedAt":1736812800000,"lastScannedManifestSha256":"ab12..."}
```

(Gson omits null fields; zero numbers are written.)

* `load(pack)`: None when missing / unreadable / unparseable (forces a full verify).
* `save(pack, state)`: create parent, write. Java writes in place (comment says atomic, code is
  not); Rust should write temp + rename.
* `compute_manifest_sha256(body)`: lowercase hex SHA-256 of the UTF-8 bytes.
* `decide_mode(state, current_sha, ttl_ms, always_verify, global_force)`: FULL if global force,
  always-verify, no current hash, no state, state hash missing or not equal ignoring case, or
  `age < 0 || age > ttl`; else FAST_PATH. `DEFAULT_TTL_MS = 24 h`.
* `successful_verify(existing, sha)`: set `manifestSha256`, `verifiedAt = now`,
  `launcherVersion`; keep scan fields.
* `successful_scan(existing, sha)`: set `lastScannedAt = now`, `lastScannedManifestSha256`,
  `launcherVersion`; keep verify fields.

`ScanFrequency` = `EVERY_TIME | DAILY | ON_CHANGES_ONLY | DISABLED`, persisted by enum name,
default `DAILY`, `from_name_safe` falls back to the default. `should_scan(freq, state, sha)`:

* DISABLED → false; EVERY_TIME → true.
* ON_CHANGES_ONLY → true if no state, no last-scanned hash, or no current hash; else
  `current != last` (ignoring case).
* DAILY → true if no state; true if a current hash exists and the last-scanned hash is missing
  or different; else `age < 0 || age > 24 h`.

Display labels via `scanFrequency.<NAME>` keys with English fallbacks "Every time",
"Daily (and on changes)", "On changes only", "Disabled".

### 1.12 Re-download audit log (`.audit_log`)

One JSON object per line (Gson `JsonObject.toString()`, compact, key order as below), line
separator = platform line separator (`\r\n` on Windows; readers must accept both):

```json
{"ts":1730000000000,"launch":42,"file":"mods/jei.jar","oldHash":"..","newHash":"..","expected":"..","algo":"sha256","changed":false}
```

* `record_redownload(pack_root, launch_id, full_path, old, new, expected, algo)`: no-op when
  root or path is None; never fails the caller. `file` = path relative to the pack root with
  `/` separators (raw path with `/` if not under the root). Null hashes / algo written as `""`.
  `changed = old && new && !old.eq_ignore_ascii_case(new)`.
* Append under the audit file's per-path lock (writes from one launch run concurrently). Before
  appending, if the file is larger than 512 KiB, keep only the last 2000 lines.
* `analyze_problems(pack_root, most_recent_launch, threshold = 3) -> Vec<Problem>`: empty when
  threshold <= 0, `most_recent < threshold`, or no file. Parse each non-blank line (skip
  malformed ones); per file collect the launch ids it appears in and those with
  `changed == false`. A file is a problem when it appears in every launch
  `most_recent, most_recent-1, ..., most_recent-threshold+1`; `content_unchanged` = every one of
  those launches was unchanged. Sort by file name ignoring case. `Problem { file,
  consecutive_launches: threshold, content_unchanged }`.

Meaning: a file re-downloaded with identical bytes every launch has a manifest hash that does
not match what the server serves. Only re-downloads of existing files are recorded, so cold
installs and healthy launches write nothing.

### 1.13 Images: environment and content-addressed cache

Images are cached once, shared by all packs, at `<root>/metadata/<sha1>.png`. The per-pack
sidecar `<packRoot>/.image_cache` maps URLs to hashes:

```json
{"logos":[{"url":"https://...","sha1":"..."}],"backgrounds":[{"url":"https://...","sha1":"..."}]}
```

Legacy (pre-3.6) sidecars use scalar `logoUrl, logoSha1, backgroundUrl, backgroundSha1`; on
load, when a list is absent, fold the scalar pair into a one-element list, then null the
scalars so the next save uses the list form.

Raw (no network) path resolution, `resolve_slot_paths(urls, declared_sha1, default_url,
sidecar)`: URL list = declared URLs, or `[default_url]` when none; for each URL at index `i`,
sha1 = sidecar entry for that URL, or (only for `i == 0`) the declared sha1; include
`metadata/<sha1>.png` when known. Missing images are simply absent. Primary path when the list
is empty: `metadata/<declaredSha1>.png`, or `metadata/logo_<SanitizedName>.png` /
`background_<SanitizedName>.png` when no sha1 is declared (may not exist; callers check).

`cache_images()` (network; call off the UI thread; serialized per pack environment, **not** on
the pack itself: holding the pack's lock across downloads froze the UI and an error dialog
raised inside froze the launcher for good):

For each slot (logo, then background):

1. Declared URLs, de-duplicated, order kept.
2. For each URL `i`: require HTTPS (exact match with the bundled default URLs is allowed);
   * `i == 0` and a declared sha1: `resolve_declared_image(metadata/<declared>.png, declared,
     url)`: if the file matches the declared hash, keep it (transcoding in place if the bytes are
     not a renderable format); else if the file exists and is renderable, trust it (it was
     transcoded earlier, so its hash no longer matches the WebP source; without this branch the
     launcher re-downloaded every time); else download then transcode. sha1 = declared.
   * otherwise: reuse the sidecar's sha1 for this URL if `metadata/<sha1>.png` exists; else
     download into `<packRoot>/<.logo_download.tmp | .background_download.tmp>`, transcode to a
     renderable format, SHA-1 the result, move to `metadata/<sha1>.png` (delete the temp if that
     file already exists).
   * an I/O failure logs `logoSourceFailed` / `backgroundSourceFailed` and skips that one URL.
3. If nothing resolved, try the bundled default the same way.
4. Still nothing → error "Unable to download/fetch <label> (all sources failed)".
5. Replace the slot's list in the sidecar and save it.

The whole call catches any error, logs (`imageDownloadFailed`), and leaves
`did_cache_images = false`; the `*_filepath(s)` accessors run `cache_images` once lazily when
it has not succeeded yet.

**Java bug to not port:** `requireHttps` throws `ModpackException`, which is not an
`IOException`, so a single non-HTTPS URL aborts the whole slot instead of skipping that URL as
the comment intends. Rust: treat it as a per-URL failure.

Transcoding: Java's `ImageFormatUtilities.ensureJavaFxDecodable` turns WebP and other ImageIO
formats into PNG because JavaFX only renders PNG / JPEG / GIF / BMP. GPUI decodes through the
`image` crate, which reads WebP natively, so the Rust port could skip transcoding. It must not,
if the cache must be shared with the Java launcher: Java hashes the transcoded PNG, so the file
names would differ. Recommended: keep the transcode-to-PNG step (with `image`) so cache keys
match. `.png` is the extension regardless of content (JPEG/GIF bytes are kept as is).

### 1.14 Update log (`.last_seen_remote_version`, `.update_log.txt`)

`record_remote_version_seen(pack)`, called after every successful network fetch of an installed
pack:

* Skip when the pack is None, vanilla, has a blank version, or its version equals `"Error"`.
  The Java checks the English literal of `modpack.failed.version`, which is localized, so in
  other languages a failed pack's version gets recorded. Rust: skip `PackKind::Failed`
  (and stubs) by kind, not by string.
* Under a per-pack lock (`"modpack-update-log:" + packRoot`): create the root; read the tracker
  (trimmed, empty = none). None → seed it with the current version, no log entry (first
  sighting). Equal → nothing. Different → append
  `<epochMs>|<sanitize(old)>|<sanitize(new)><line separator>` (sanitize: `|` → `/`, `\n` and `\r`
  → space), write the tracker, then trim the log to the newest 200 lines.
* Errors logged, never thrown.

`read_entries(pack)`: parse each line with `split('|', 3)`, needing 3 parts and an i64 first
part; skip others; return newest first. `format_timestamp(entry)` = local date `yyyy-MM-dd`.

### 1.15 Scan acknowledgements (`packScanAcknowledgements`)

Entry fields: `fileSha256`, `innerSha256`, `kind`, `locator`, `reason`.

`matches(finding)`:

1. finding None → false; `kind` blank → false; finding kind None → false; `kind.trim()` must
   equal the finding kind's enum name ignoring case.
2. At least one of `fileSha256` / `innerSha256` must be non-blank, else false.
3. Each non-blank ack hash must equal the finding's corresponding hash (trim, ignore case); a
   blank finding hash never matches. Both set = both must match.
4. Non-blank `locator` must equal the finding locator (trim, ignore case); finding locator None
   → false. Blank locator matches any locator.

Only supplemental (heuristic) findings can be acknowledged; Nekodetector hits cannot.

### 1.16 Server favourites (`server-favorites.json`)

`ServerFavorite { name, host, port }`, `DEFAULT_PORT = 25565`.

* `display_address()` = host, or `host:port` when not default.
* `parse(name, host_port)`: None when blank; trim; split at the **last** `:`; port must parse as
  an integer in 1..65535 else None; host must be non-blank; name = trimmed name or host. (IPv6
  literals without brackets misparse; matches Java.)

Store, at `<packRoot>/server-favorites.json`:

```json
{"favorites":[{"name":"Alto","host":"alto.example.com","port":8585}],"disableDefaultServer":true}
```

* `load`: missing file / bad JSON / not an object / no `favorites` array → empty. Per element:
  skip non-objects and blank hosts; `port` primitive else default; out-of-range port → default;
  blank name → host.
* `save(favorites)`: rewrite preserving the current `disableDefaultServer` flag; host-less
  entries dropped; flag key emitted only when true.
* `is_default_server_disabled` / `set_default_server_disabled`.

### 1.17 Backups, export, conflicts, Modrinth update check, catalogues

**Backups** (`ModpackBackupManager`), before an update over an existing install
(`install_by_url` when the URL is already installed):

* No-op when the config switch "auto-backup before update" is off, the pack root is missing, or
  none of the backed-up folders exist. Folders: `config` always; `saves` when "include saves" is
  on.
* Zip to `<packRoot>/.backups/<yyyy-MM-dd--HH-mm-ss>.zip` (local time). Entries `config/...`,
  `saves/...`; directories get `name/` entries; file entry time = file mtime; symlinks skipped.
  On error delete the partial zip, warn, return None.
* `prune_old_backups`: zips sorted by mtime newest first; delete index `>= maxCount` (when
  `maxCount > 0`) or older than `maxAgeDays` (when > 0). Defaults 3 and 14 (config slice).

**Export** (`ModpackExporter`):

* `classify_export(pack)`: `http(s)` manifest URL → `ShareUrl`; `file:` URL whose manifest text
  has no `packMods` or only object entries whose `remote` starts with `http://` / `https://`
  (case-insensitive) → `ShareManifestJson`; else `ExportZip`.
* ZIP export: whole pack root minus `libraries, bin, assets, runtime, logs, crash-reports,
  natives, versions` (and `saves` unless asked), plus `mica-export.json` marker
  (`format: "mica-export-v2"`) and `manifest.json` (the manifest body). Progress callback in
  bytes. (Read the exporter source when implementing the exact marker fields.)

**Mod conflicts** (`ModConflictDetector`), filename heuristics on `mods/*.jar` (case-insensitive
`.jar` suffix; `.jar.disabled` ignored), rules in order:

1. OptiFine vs Sodium: first `(?i)\boptifine\b|\boptifabric\b`, second `(?i)\bsodium(?:fabric)?\b`.
2. JEI vs REI: first `(?i)\bjei\b|just[- ]?enough[- ]?items|mezz\.jei`, second
   `(?i)\brei\b|roughly[- ]?enough[- ]?items|shedaniel\.rei`.

A rule fires when both patterns match (first matching name each, in directory order) and the
names differ. Titles / descriptions are English literals in Java (should be localized in Rust).
`disable_jar(pack, name)`: rename `mods/<name>` to `mods/<name>.disabled` with an atomic move;
false if missing, target exists, or the move fails. Note `\b` semantics: Java `\b` is
Unicode-aware with `(?i)`; Rust `regex` `\b` is Unicode by default too.

**Modrinth update check** (`ModrinthModUpdateChecker`), serial, off the UI thread, every failure
→ `NotOnModrinth`:

1. For each `mods/*.jar`: SHA-1 hex.
2. `GET https://api.modrinth.com/v2/version_file/<sha1>` (bounded 5 MiB): needs `project_id`;
   take `version_number`, `id`.
3. `GET /v2/project/<project_id>`: `title`; `versions` array (oldest first). Missing / empty →
   `UpToDate`. Last element equals current id → `UpToDate`.
4. `GET /v2/version/<latestId>`: `version_number`, primary file URL (`files[]` entry with
   `primary: true`, else the first with a `url`). Missing → `UpToDate`; else `UpdateAvailable`.

Result map keyed by jar file name: `ModUpdate { status, current_version, latest_version,
latest_download_url, project_name }`.

**Loader version catalogue** (`LoaderVersionManager`, catalogue for Browse and the editor):

* Forge: `https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json`
  (recommended + latest per MC version, newest MC first); installer URL
  `https://maven.minecraftforge.net/net/minecraftforge/forge/<mc>-<ver>/forge-<mc>-<ver>-installer.jar`
  (check the source for the exact pattern).
* NeoForge: `https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml`.
* Fabric: `https://meta.fabricmc.net/v2/versions/game` and `/v2/versions/loader`; profile URL
  `https://meta.fabricmc.net/v2/versions/loader/<mc>/<loader>/profile/json`.
* Results cached per process.
* `install_version(v)`: write (if absent) `<root>/config/imported-manifests/loader-<type>-<mc>-<ver>.json`
  where mc and ver have `[^A-Za-z0-9._-]` replaced by `_` (`unknown` for null). Manifest:
  `packName = "<Forge|NeoForge|Fabric> <mc> (<ver>)"`, `packVersion = ver`, `packURL` =
  `https://files.minecraftforge.net` / `https://neoforged.net` / `https://fabricmc.net` /
  `https://minecraft.net`, logo/background = bundled defaults, `packMinRAMGB "2"`, flags false,
  `packModLoader`, `packModLoaderURL`, `packModLoaderHash ""`, `packForgeURL` = installer for
  Forge else `""`, `packForgeHash ""`, empty arrays for exclusions and all five lists. Then
  `install_by_url(file_url)`. `is_installed` = file exists.

**Vanilla versions** (`VanillaVersionManager`): list from the cached Mojang
`version_manifest_v2.json` (`versions[]`, filter by `type` or `"all"`); installed vanilla ids live
in config (`installedVanillaVersions`); display `"Minecraft <id>"`, friendly
`"Minecraft <id> (Vanilla)"`.

### 1.18 Importers (`import_/`, summary)

They all end by writing a Mica manifest into `<root>/config/imported-manifests/` and calling
`install_by_url("file:...")`, so the rest of this slice treats imported packs like hosted ones
(`file:` URLs are read straight from disk, never cached, never revalidated):

* `MmcjsonImporter`: copy a `.mmcjson` (validated as Mica JSON) into `imported-manifests/`.
* `MrpackImporter`: Modrinth `.mrpack` (bounded download, `modrinth.index.json` → lists by path
  prefix `mods/`, `config/`, `resourcepacks/`, `shaderpacks/`; Forge installer URL derived and
  hashed; output `<slug>-<versionId>.json`; `overrides/` not extracted yet).
* `ModpackZipImporter`: Mica export ZIP (requires `mica-export.json` with format starting
  `mica-export-v`, v2+ for the embedded manifest), extracts into the pack root, manifest to
  `zip-<slug>.json`.
* `PrismInstanceImporter`: MultiMC / Prism instance (`instance.cfg`, `mmc-pack.json`), sets
  `packImportedSkipSync = true`, copies `.minecraft/` into the pack folder.
* `TechnicServerZipImporter`: Technic server ZIP; mods become entries with empty `remote`
  (local-only files, section 1.4.3 step 3); writes `manifestFormat: 2`.
* `ModpackImportClassifier` / `ModpackImportSource`: regex classification of pasted URLs
  (Mica manifest vs Modrinth vs CurseForge ...). `ModrinthClient`: preview API calls.
* `BoundedZipExtraction`: decompression-bomb caps on real streamed bytes, plus Zip-Slip checks.

These deserve their own spec; listed here for the dependency picture.

### 1.19 Manifest fetch (`GameModPackFetcher`)

Constants: cache dir `<root>/installs/manifest_cache`; `MANIFEST_MAX_BYTES = 50 MiB`;
`MANIFEST_STALE_THRESHOLD_MS = 7 days`.

Cache file name: `sha256hex(url UTF-8) + ".json"`; meta sidecar `+ ".meta.json"`. Legacy read
fallback: `javaHashCodeHex(url) + ".json"` where `javaHashCodeHex` = Java `String.hashCode()`
(over UTF-16 code units: `h = 31*h + unit`, wrapping i32) formatted like
`Integer.toHexString` (lowercase hex of the value reinterpreted as u32, no leading zeros). Never
written any more; read so old caches still work.

#### `get(url, create_environment) -> GameModPack` (never fails; failure → Failed pack)

1. `file:` URL: read the file (URI → path; must be a regular file); missing → error "Local
   manifest not found". No network, no cache.
2. Offline (`NetworkUtilities.isOffline()`): `load_cached(url, warn_stale = true)`; none → error
   "No cached manifest available for offline mode".
3. Online: load the meta sidecar (unreadable → none); conditional GET with `If-None-Match`
   (etag) / `If-Modified-Since` (lastModified), HTTPS-only, bounded 50 MiB.
   * 304: read the cached body (no stale warning). If the cache file is gone, do an
     unconditional bounded GET and cache it. Otherwise touch the cache file's mtime to now (the
     server just confirmed it fresh; without this, users whose manifest had not changed in a
     week got "stale cache" warnings while online). Then save the validators from the 304.
   * 200: cache the body (write UTF-8; failure only logged), save validators.
4. Parse into the model (section 1.2.3); set `manifest_sha256 = sha256(body)`.
5. `create_environment`: `prepare_environment()`; if online, `cache_images()` with every error
   swallowed and logged (a broken logo URL must not put a pack into a permanent retry loop).
6. Set `manifest_url`; `InstallIndex::upsert_and_save(url, pack)` (errors logged).
7. Any error in 1-6: log error + throwable; return `create_failed(url, message)` with
   `manifest_url` set.

#### `get_from_cache(url, create_environment) -> Option<GameModPack>`

No network. `file:` → read it; else `load_cached(url, warn_stale = false)`. Parse, set URL and
hash, optional `prepare_environment()` (no image download), upsert the install index. Any error
→ log, None.

#### `get_stub_from_index(url) -> Option<GameModPack>`

From `InstallIndex::load().get(url)`; None for blank URL or no entry.

#### `load_manifest_text(url) -> Option<String>`

Raw body for the editor (unknown keys must survive): `file:` → read; else cached body. No
network.

#### Stale warning

Only on the offline path. If the cache file's mtime is older than 7 days: a silent warning per
URL once per process, and a single non-modal notification toast per process
(`notification.manifest.stale.*`, body with the age in days). Never a modal: parallel loads
used to raise a barrage of modal dialogs and once froze the UI mid-transition.

### 1.20 Install index (`install_index.json`)

```json
{"version":1,"packs":{"<manifest url>":{"packName":"Alto","packVersion":"26.11.4.3",
 "packURL":"https://...","packLogoURL":"https://.../pack.png","packLogoSha1":"...",
 "packBackgroundURL":["...","..."],"packBackgroundSha1":"...","packMinRAMGB":"6",
 "packUnstable":false,"packCustomDiscordRpc":true,"updatedAt":1730000000000}}}
```

(Gson compact output, null fields omitted, insertion-ordered map: `LinkedHashMap`.)

* `CURRENT_SCHEMA_VERSION = 1`. `load()`: reap stale temp files first; missing / unparseable /
  `version > 1` → empty index (logged); `packs` null → empty map.
* `save()`: create parent (failure → log and return); write to
  `install_index.json.tmp.<process-wide counter>` then rename over the target (atomic where
  possible, plain replace otherwise); if the temp path fails, delete the temp and write the
  target directly. A unique temp name per write: a shared `.tmp` let two writers interleave and
  publish garbled JSON.
* `reap_stale_temp_files()`: delete `install_index.json.tmp.*` older than 5 minutes.
* `upsert_and_save(url, pack)` and `remove_and_save(url)`: whole load-modify-save under one
  process-wide lock (the same lock `load` takes). Upsert copies the card subset with
  `updatedAt = now`; it skips the write when the entry existed with the same content and the
  previous `updatedAt` is less than 24 h old (`UPDATED_AT_REFRESH_MS`).
* `Entry::same_content` compares every field except `updatedAt` (`StringOrArray` by value).

### 1.21 The pack lists (`GameModPackManager`)

Process-wide state (Java statics): `installed: Option<Vec<pack>>`, `available: Option<Vec<pack>>`
(copy-on-write lists behind volatile references), `available_fetch: Option<Future>`,
`installed_revalidate: Option<Future>`, `background_error_listener`, a dedicated manifest fetch
pool of `max(4, 2 * cores)` threads. Mutating methods are `synchronized` on the class; read-only
getters are lock-free (they used to stall the UI thread for whole network fetches).

In Rust: a `ModPackManager` service object (not statics), holding
`ArcSwap<Vec<Arc<GameModPack>>>` (or `RwLock<Vec<..>>`) per list, a `tokio::sync::Mutex<()>` for
the mutating operations, and `Shared` futures / `watch` channels for the two background tasks.

#### `fetch_installed(block_until_revalidated: bool)`

1. URLs from config (`ConfigManager.getInstalledModPacks()`, JSON key `modpacks`, a list of
   manifest URL strings; may contain `file:` URLs and, through a Java bug, duplicates).
2. Reset the installed list to empty.
3. **Phase 1a** (one file read): for each URL with an install-index entry, push a stub. Every URL
   goes on the "needs full load" list.
4. **Phase 1b** (parallel disk): `get_from_cache(url, true)` for each; hit → replace the entry
   with that URL in place (`replace_or_append_by_url`: replace the first match, else append); miss
   → "needs network".
5. Phase 1b network (parallel on the fetch pool): `get(url, true)`, replace-or-append (never
   plain append: a past bug appended next to the stub, by-name lookups found the stub first, its
   null mod list read as "no mods" and the game launched modless); then
   `record_remote_version_seen` (errors logged).
6. **Phase 2**: if online and the list is non-empty, start the background revalidate: in
   parallel, `get(url, true)` for every URL; if the result is Failed, keep the cached entry
   (a network blip must not replace a good pack with an unlaunchable one); else replace the entry
   with the same URL in place (no append) and `record_remote_version_seen`. Per-pack failures are
   logged; a failure of the whole pass fires the background-error listener
   (`gameModPackManager.installedRevalidateFailed`).
7. `block_until_revalidated` (headless server and any auto-launch): wait for Phase 2 up to
   120 s (`REVALIDATE_BLOCK_TIMEOUT_SECONDS`); timeout / interrupt / failure logged as errors.
   If Phase 2 did not run, log an error saying why (offline, or no packs). Without this, a server
   always launched the manifest from the previous start.

#### `fetch_available()`

Bounded GET of `installable.json` (failure → log, return). Reset the available list. Skip URLs
already installed (exact string compare with the installed URLs). Parallel `get(url, false)`
(no environment, no images) on the fetch pool; add packs whose friendly name is non-null (this
includes Failed packs).

`start_available_fetch_async()`: idempotent, double-checked; offline → an already-completed
future; else run `fetch_available()` in the background, errors fire the listener
(`gameModPackManager.availableFetchFailed`). `is_available_fetch_pending()`.

`fetch_mod_pack_info()`: refresh the network-availability flag, `fetch_installed(false)`, then
`fetch_available()` if online. Called after install / uninstall and lazily on first access.

#### Getters

* `installed()`: lock-free snapshot; triggers `fetch_mod_pack_info()` on first access.
* `available()`: waits for the background fetch (outside the lock: the background task takes the
  same lock, so waiting while holding it deadlocks), then returns the list (lazy fetch if never
  populated). `available_if_ready()`: non-blocking snapshot or empty.
* `installed_urls()`, `installed_friendly_names()` (non-null names), `available_friendly_names()`.
* `installed_by_name(name)`: first pack whose name equals ignoring case.
* `installed_by_url(url)`: first pack whose manifest URL equals **ignoring case** (URL paths are
  case-sensitive in general; keep for compatibility but prefer exact match first).
* `installed_by_friendly_name(name)`: **last** match ignoring case (Java loop has no `break`).

#### Mutations

* `install(pack)`: append, save URLs to config, `fetch_mod_pack_info()`. No de-duplication in
  Java: re-installing an installed URL (the update path) appends a duplicate URL to config.
  Rust: replace in place by URL.
* `install_by_url(url)`: `get(url, true)`; if the friendly name is non-null (always true, even
  for Failed packs), back up the existing install when the URL is already installed, then
  `install`. Rust: refuse to install a Failed pack as new (surface the error), but keep an
  existing entry when an update fetch fails.
* `install_by_friendly_name(name)`: find in `available()` by exact friendly name; not found →
  error log "not available".
* `uninstall(pack, delete_files) -> bool`: `uninstall_guarded(pack, delete_files, is_active,
  delete_folder, unregister)`: if the pack is launching or running
  (`GameSessionRegistry.isPackActive`) refuse before touching anything (warn + toast
  `notification.pack.running.*`) and return false; else delete the pack root recursively when
  asked (failure logged, uninstall continues), then unregister: remove from the list, remove the
  install-index entry, save config, `fetch_mod_pack_info()`.
* `uninstall_by_url(url)` (exact compare), `uninstall_by_friendly_name(name)` (exact compare):
  first match, keep files.
* `save_to_config()`: write the installed URLs.
* `set_background_error_listener(Fn(String, Error))`: listener panics are caught and logged.

### 1.22 Pending update diff (`PendingUpdateDiff`)

`compute(pack) -> Result { added, removed, updated, added_names, removed_names, updated_names }`,
empty for None / vanilla / null `packMods`. Pure apart from listing `mods/` and hashing.

* `base_name(file)`: strip a trailing `.jar.disabled` or `.jar` (case-insensitive), then remove
  the regex `[-_]v?\d.*$` (leftmost match to the end); if that leaves a blank string, use the
  name without extension. `mod_key = base_name.to_lowercase()`.
* Installed side: files in `<packRoot>/mods` whose lowercase name ends in `.jar` or
  `.jar.disabled`; first file per key wins (directory order), with its display base name.
* For each manifest mod (file name of `local`, skip on error): add its key to the "new keys"
  set; skip display when `!clientReq` (server-only mods never install on a client); no installed
  file with that key → added; else if the installed bytes do not match the declared hash
  (`matches_declared_hash`, any error = match) → updated.
* Removed = installed keys not in "new keys", using installed display names.

It is a hint shown only when an update is pending; hand-added mods show as removed.

### 1.23 Launch progress

Two layers, both in this package:

**`LaunchProgressTracker`** (new multi-row model). Steps, in display-order contract:

| `StepId` | English label (key `launchStep.<NAME>`) |
|---|---|
| `MODPACK_CONTENT` | Modpack content |
| `FORGE_LIBS` | Modloader libraries |
| `MC_LIBS_ASSETS` | Minecraft libraries & assets |
| `JRE_INSTALL` | Java runtime |
| `FORGE_PROCESSORS` | Game patching (omitted when `!uses_post_install_steps()`) |
| `SECURITY_SCAN` | Security scan |

Row state: `Pending → Running → Done | Failed(msg) | Skipped`; `progress` in [0, 1] (clamped);
`sub_text` (null → `""`); `error_message`. Built with `for_steps(ids...)`: only listed steps exist;
operations on an absent step are an error (`IllegalArgumentException`). Mutations:
`mark_running` (clears error), `mark_done` (progress = 1), `mark_failed(msg)`, `mark_skipped`,
`set_progress`, `set_sub_text`, `submit_progress(p, text)` (one notification).
`overall_fraction()` = (Done/Failed/Skipped count 1, Running counts its progress, Pending 0) /
step count; 0 when no steps. `running_steps()` snapshot (used to push retry / 429 / hash-retry
messages onto every in-flight row).

Threading: mutations hold the tracker lock; **listeners are called after the lock is released**
and each listener's panic is swallowed (a listener that reads another step must not deadlock).
Listeners run on the mutating thread; the UI marshals to its own thread. Exactly one
notification per mutation.

**`GameModPackProgressProvider`** (legacy sink, still what every sub-call uses):

* Weighted sections on a 0-100 scale: `start_section(title, size)` folds the in-flight section
  progress into the completed percent (note: adds `currSectionProgress` unweighted, a quirk),
  sets title / size, clears detail and section progress, fires immediately;
  `submit_progress(detail, delta)` adds `delta` to the section progress (cap 100) and fires
  at most every 50 ms unless the section reached 100; `set_curr_text(detail)` fires
  immediately; `end_section(text)` adds the full section size to the completed percent, resets,
  fires; `signal_complete(title)` forces 100.
* Overall percent = `completed + section_progress * size / 100`.
* Fires `update_progress_handler(percent, section_title, detail, download_status)` where
  `download_status` is the shared `DownloadTracker`'s formatted speed / ETA.
* Step hooks with no-op defaults: `enter_step`, `complete_step`, `fail_step`, `on_error(msg,
  cause)`.
* Owns one `DownloadTracker` shared by all files of the sync.

**`LaunchTrackerProgressBridge`** (legacy sink → tracker, serial pipeline): `enter_step(id)` sets
the active step, resets the section accumulator, `mark_running`. `complete_step` /
`fail_step` clear the active step only when it matches, then mark. `submit_progress` (no
throttle) adds to the accumulator (cap 100) and pushes `acc / 100` + text to the active step;
no-op without an active step. `set_curr_text` → sub-text. `start_section` → progress 0 and the
title as sub-text (step stays Running). `end_section` → sub-text. `update_progress_handler` is a
no-op. `handle_for(step)` returns a `StepProgressHandle`.

**`StepProgressHandle`** (one per parallel branch, bound to one step): same mapping, plus a
50 ms throttle on `submit_progress` (bypassed at 100), and `mark_running` / `mark_done` /
`mark_failed(msg)` / `mark_skipped(reason)` (reason becomes the sub-text first).

The `on_error` override in the bridge (a warning toast) is referenced in docs; check the launcher
slice for where it is implemented.

### 1.24 Editor document (`ModPackDocument`, `ModPackFileEntry`)

Headless editor model over a raw JSON object (so unknown and future keys survive). Rust: wrap a
`serde_json::Map<String, Value>` with `preserve_order`.

* `blank()`: keys in this order: `manifestFormat: 2, packName: "", packVersion: "1.0.0",
  packURL: "", packUnstable: false, packCustomDiscordRpc: false, packMinRAMGB: "2",
  packLogoURL: "", packLogoSha1: "", packBackgroundURL: "", packBackgroundSha1: "",
  packForgeURL: "", packForgeHash: "", packModLoader: "forge", packModLoaderURL: "",
  packModLoaderHash: "", packMinecraftVersion: "", packScanExclusions: [], packMods: [],
  packConfigs: [], packResourcePacks: [], packShaderPacks: [], packInitialFiles: []`.
* `from_json(text)` (error if not an object), `wrapping(obj)`, `json()`, `to_pretty_json()` (Gson
  pretty printing: 2-space indent, `"key": value`; match it so diffs stay small).
* `get_string(key)` ("" for absent / null; non-strings via their string form; arrays / objects
  throw in Java), `put_string`, `get_bool` (false for absent / null), `put_bool`.
* `get_string_or_array_lines(key)` / `put_string_or_array(key, text)`: lines ↔ string | array
  (one line → bare string, several → array, none → `""`; lines trimmed, blanks dropped; null array
  elements skipped on read).
* `get_array_lines` / `put_array_lines` (always an array; non-array value reads as "").
* `FileList` = `MODS(packMods, name, side flags)`, `CONFIGS(packConfigs, side flags)`,
  `RESOURCE_PACKS(packResourcePacks)`, `SHADER_PACKS(packShaderPacks)`,
  `INITIAL_FILES(packInitialFiles, side flags)`; label keys `editor.validate.label.*`.
* `read_file_list(list)`: absent / non-array → empty; skip non-objects; name only for MODS;
  primary hash = sha256 > sha1 > md5 among usable values (non-blank, not `-1`, readable as
  string); the other usable values go to `extra_hashes` (lowercase algo keys); side flags
  default **true** when absent, always true for lists without flags; `modrinthSlug` when present.
* `write_file_list(list, entries)`: replace the array; per entry `name` (MODS only), `remote`,
  `local`, then **all three** `sha1`, `md5`, `sha256` (primary into its slot, others from extras,
  empty → `"-1"`), side flags only for lists that have them, `modrinthSlug` only when non-empty.
  Key order: name, remote, local, sha1, md5, sha256, clientReq, serverReq, modrinthSlug.
* `fork_of(new_name)`: deep copy, trimmed new name (blank → error), minor version bumped.
* `add_mod(entry)`: refuse blank local or a local equal (ignore case) to an existing one; append.
* `remove_mod(identifier)`: trimmed, first entry whose local, name or modrinth slug equals it
  ignoring case; at most one.
* `local_manifest_key_of(name)` = `[^A-Za-z0-9]` removed, lowercased.
  `local_manifest_path_in(dir)` = `dir/created-<key>.json` (error for no dir or empty key).
  `write_local_manifest(dir, overwrite)`: create dir, write pretty JSON (create-new when not
  overwriting → "already exists" error), return the `file:` URI.
* `sanitized_file_base_name()` (no lowercasing).
* `bump_version(current, position)`: blank → `0.0.0`; split on `.`; non-numeric segments → 0;
  at least 3 segments; increment `position` (ignored when out of range) and zero everything to
  its right; output **exactly three** segments.
* `first_line(text)`: first trimmed non-blank line or "".
* `validate(resolver) -> Vec<String>`: required `packName`, `packVersion` (absent or blank →
  `editor.validate.required(label)`); `packMinRAMGB` present but not parseable as f64 →
  `invalidMinRam`; per list entry blank remote → `remoteEmpty(label)`, blank local →
  `localEmpty(label)`, label `"<list> \"<name>\""` for named mods else `"<list> #<1-based>"`;
  finally the document must deserialize into the runtime model, else
  `roundTripFailed(message)`. The resolver maps `(key, args)` to text so tests stay
  locale-independent.

`ModPackFileEntry`: `name, remote, local, hash, hash_type ("sha1" default), client_req (true),
server_req (true), modrinth_slug, extra_hashes`; setters coalesce null to "" (hash type to
"sha1"); `put_extra_hash(algo, value)` lowercases the algo, blank value removes, null algo is
ignored; `extra_hashes()` read-only view.

---

## 2. Class-by-class map

| Java class | Rust home | Rust type / key functions |
|---|---|---|
| `GameModPackMetadata` | `mmcl-manifest::pack` + `mmcl-modpack::pack` | `PackManifest` (serde fields, pure accessors: `min_ram_gb`, `default_server`, `visible_news`, `visible_links`, `sanitized_name`, `friendly_name`, `mod_loader_type/url/hash`); sidecar methods move to `mmcl-modpack::sidecar` |
| `GameModPack` | `mmcl-modpack::pack` | `GameModPack` (`Arc`), `PackKind`, factory fns, `settings_key`, `consume_quick_join_server`, `has_custom_logo`, `latest_crash_report`; `scan_root` lives in the security crate taking `&GameModPack` |
| `GameMod`, `GameAsset`, `ManagedGameFile` | `mmcl-manifest::files` (data) + `mmcl-modpack::managed` (I/O) | `ManifestFile { remote, local, sha1, md5, sha256, name, client_req, server_req }`; `ManagedFile` runtime wrapper with `verify`, `update`, `reverify`, `download`, `matches_declared_hash`; `SideFilter` |
| `GameLibrary` | loader crate | out of slice |
| `LaunchPrepareContext`, `LaunchVerifyMode` | `mmcl-modpack::context` | `PrepareContext`, `VerifyMode` |
| `GameModPackFileSync` | `mmcl-modpack::sync` | `FileSync::{fetch_latest_mods, fetch_latest_configs, fetch_latest_resource_packs, fetch_latest_shader_packs, fetch_latest_initial_files}`, `clear_floating_mods` |
| `GameModPackEnvironment` | `mmcl-modpack::environment` | `PackEnvironment::{prepare, cache_images, logo_paths_raw, background_paths_raw, ...}`, `ImageCacheSidecar` |
| `GameModPackFetcher` | `mmcl-modpack::fetch` | `ManifestFetcher::{get, get_from_cache, get_stub_from_index, load_manifest_text}`, `ManifestCache`, `CacheMeta` |
| `InstallIndex` | `mmcl-modpack::index` | `InstallIndex`, `IndexEntry`, `upsert_and_save`, `remove_and_save` |
| `GameModPackManager` | `mmcl-modpack::manager` | `ModPackManager` service |
| `PackDotFile` | `mmcl-modpack::sidecar` | `PackDotFile::{read, read_lines, write, modify}` |
| `VerifyState` | `mmcl-modpack::verify_state` | `VerifyState`, `decide_mode`, `compute_manifest_sha256` |
| `ScanFrequency` | `mmcl-modpack::verify_state` | `ScanFrequency`, `should_scan` |
| `ScanAcknowledgement` | `mmcl-manifest::scan` | `ScanAcknowledgement::matches(&Finding)` (`Finding` trait / struct from security crate, or a small `FindingKey` struct here to avoid the dependency) |
| `NewsItem`, `LinkItem` | `mmcl-manifest::news` | `NewsItem`, `NewsKind`, `LinkItem` |
| `ServerFavorite` | `mmcl-manifest::server` | `ServerFavorite::{parse, display_address}` |
| `ServerFavoritesStore` | `mmcl-modpack::sidecar::servers` | `ServerFavoritesStore` |
| `PlayTimeFormatting` | `mmcl-manifest::play_time` | `last_played`, `total_play_time` → `Label { key, amount }` |
| `ModPackAuditLog` | `mmcl-modpack::audit` | `AuditLog::{record_redownload, analyze_problems}`, `Problem` |
| `ModPackUpdateLog` | `mmcl-modpack::update_log` | `UpdateLog::{record_remote_version_seen, read_entries}`, `UpdateLogEntry` |
| `PendingUpdateDiff` | `mmcl-modpack::diff` | `pending_update_diff(&GameModPack) -> UpdateDiff`; pure core `diff_mods(manifest, installed, hash_check)` in `mmcl-manifest` |
| `ModConflictDetector` | `mmcl-manifest::conflicts` (pure) + `mmcl-modpack` (scan/disable) | `detect_from_names`, `scan`, `disable_jar` |
| `ModpackBackupManager` | `mmcl-modpack::backup` | `backup_before_update`, `prune_old_backups` |
| `ModpackExporter` | `mmcl-modpack::export` | `classify_export`, `export_to_zip` |
| `OfficialLauncherExporter` | separate crate / module `mmcl-official-export` | out of scope here beyond noting the dependency |
| `ModrinthModUpdateChecker` | `mmcl-modpack::modrinth_updates` | `scan(mods_dir) -> HashMap<String, ModUpdate>` |
| `LoaderVersionManager` | `mmcl-modpack::catalog::loaders` | `forge_versions`, `neoforge_versions`, `fabric_versions`, `install_version` |
| `VanillaVersionManager` | `mmcl-modpack::catalog::vanilla` | `all_versions`, `versions_by_type`, install / uninstall ids |
| `ModPackDocument`, `ModPackFileEntry` | `mmcl-manifest::document` | `ModPackDocument`, `FileList`, `FileEntry`, `bump_version` |
| `LaunchProgressTracker` | `mmcl-progress` | `LaunchProgressTracker`, `StepId`, `StepState`, `Step` |
| `GameModPackProgressProvider` | `mmcl-progress` | `trait ProgressSink` + `SectionProgress` (weighted legacy model) |
| `LaunchTrackerProgressBridge` | `mmcl-progress` | `TrackerBridge: ProgressSink` |
| `StepProgressHandle` | `mmcl-progress` | `StepHandle: ProgressSink` |
| `MavenArtifactPath`, `ForgeLibraryPlanner`, `Lwjgl2ArmPatcher` | loader crate | out of slice (pure helpers the loader spec covers) |
| `import_/*` | `mmcl-import` | out of slice (section 1.18) |

---

## 3. Crates and public API sketch

### 3.1 Crate layout

* **`mmcl-manifest`** (pure, no I/O except optional file listing helpers, no async): manifest
  schema and lenient serde, `StringOrArray`, news / links / acks / servers, editor document,
  play-time labels, version compare (or re-export from `mmcl-util`), conflict rules, diff core.
  Used by: modpack runtime, editor UI, MCP tools, importers, loader crate.
* **`mmcl-progress`** (pure, sync): tracker, sink trait, handle, bridge. Used by the modpack,
  loader, runtime (JRE), security and launch crates, so it must not depend on any of them.
* **`mmcl-modpack`** (I/O): managed files, sync, fetcher, index, manager, sidecars, audit,
  update log, images, backups, export, Modrinth check, catalogues.

Dependency direction: the Java `GameModPack.getModLoader()` constructs loaders, and loaders read
pack paths, so a naive port has a cycle (modpack → loader → modpack). Break it: the loader crate
depends on `mmcl-manifest` and on a small `PackPaths`/`PackContext` trait (defined in
`mmcl-manifest` or `mmcl-modpack-api`); `mmcl-modpack` holds the loader as
`Arc<dyn ModLoader>` created through a factory the launch crate registers, or the launch crate
does the loader construction and `GameModPack` only caches it.

### 3.2 `mmcl-manifest`

```rust
pub mod string_or_array {
    #[derive(Clone, Debug, Default, PartialEq, Eq)]
    pub struct StringOrArray(Vec<String>);              // trimmed, non-blank, ordered
    impl StringOrArray {
        pub fn of<I: IntoIterator<Item = S>, S: AsRef<str>>(v: I) -> Self;
        pub fn all(&self) -> &[String];
        pub fn first(&self) -> Option<&str>;
        pub fn is_empty(&self) -> bool;
    }
    // Deserialize: null | string | number | bool | array; Serialize: null | string | array.
}

pub mod lenient {                                       // Gson-compatible field adapters
    pub fn string<'de, D>(d: D) -> Result<Option<String>, D::Error>;   // accepts number/bool
    pub fn bool<'de, D>(d: D) -> Result<bool, D::Error>;               // accepts "true"/"false"
    pub fn opt_i32<'de, D>(d: D) -> Result<Option<i32>, D::Error>;     // accepts "8585"
    pub fn vec_skip_null<'de, D, T>(d: D) -> Result<Option<Vec<T>>, D::Error>;
}

#[derive(Clone, Debug, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ManifestFile {
    pub name: Option<String>,
    pub remote: Option<String>,
    pub local: Option<String>,
    pub sha1: Option<String>,
    pub md5: Option<String>,
    pub sha256: Option<String>,
    #[serde(default)] pub client_req: bool,             // Gson default: false
    #[serde(default)] pub server_req: bool,
}
impl ManifestFile {
    pub fn strongest_hash(&self) -> Option<(HashAlgo, &str)>;          // sha256 > sha1 > md5
    pub fn is_required_for(&self, side: Side) -> bool;
}
pub fn usable_hash(h: Option<&str>) -> Option<&str>;   // None for null/blank/"-1"

#[derive(Clone, Debug, Default, Deserialize, Serialize)]
pub struct PackManifest {
    #[serde(rename = "packName")] pub pack_name: Option<String>,
    // ... every field from section 1.2.1, Gson names via #[serde(rename = "...")]
    #[serde(rename = "packMods")] pub pack_mods: Option<Vec<ManifestFile>>,
    // configs, resource packs, shader packs, initial files likewise
}
impl PackManifest {
    pub fn parse(body: &str) -> Result<Self, ManifestParseError>; // serde_json → Value → struct, json5 fallback
    pub fn sanitized_name(&self) -> String;
    pub fn friendly_name(&self) -> Option<String>;
    pub fn min_ram_gb(&self) -> f64;
    pub fn mod_loader_kind(&self) -> ModLoaderKind;     // Forge | NeoForge | Fabric | Unknown(String)
    pub fn mod_loader_url(&self) -> Option<&str>;
    pub fn mod_loader_hash(&self) -> Option<&str>;
    pub fn default_server(&self) -> Option<ServerFavorite>;
    pub fn visible_news(&self, today: time::Date) -> Vec<&NewsItem>;
    pub fn visible_links(&self) -> Vec<&LinkItem>;
    pub fn has_custom_logo(&self) -> bool;
    pub fn has_custom_background(&self) -> bool;
}

pub struct NewsItem { /* raw fields */ }
impl NewsItem {
    pub fn title(&self) -> Option<String>; pub fn body(&self) -> Option<String>;
    pub fn id(&self) -> Option<&str>; pub fn kind(&self) -> NewsKind; pub fn url(&self) -> Option<String>;
    pub fn is_expired(&self, today: time::Date) -> bool; pub fn sort_epoch(&self) -> i64;
    pub fn date(&self) -> Option<time::Date>; pub fn is_renderable(&self) -> bool;
}
pub struct LinkItem { /* title, url, description */ }
pub struct ScanAcknowledgement { pub file_sha256: Option<String>, pub inner_sha256: Option<String>,
                                 pub kind: Option<String>, pub locator: Option<String>, pub reason: Option<String> }
pub struct FindingKey<'a> { pub kind: Option<&'a str>, pub file_sha256: Option<&'a str>,
                            pub inner_sha256: Option<&'a str>, pub locator: Option<&'a str> }
impl ScanAcknowledgement { pub fn matches(&self, f: &FindingKey) -> bool; }

pub struct ServerFavorite { pub name: String, pub host: String, pub port: u16 }
impl ServerFavorite { pub const DEFAULT_PORT: u16 = 25565;
    pub fn parse(name: Option<&str>, host_port: &str) -> Option<Self>; pub fn display_address(&self) -> String; }

pub mod play_time { pub struct Label { pub key: &'static str, pub amount: f64 }
    pub fn last_played(last_ms: i64, now_ms: i64) -> Label; pub fn total_play_time(total_ms: i64) -> Label; }

pub mod version { pub fn compare_versions(a: &str, b: &str) -> std::cmp::Ordering; }

pub mod conflicts { pub struct Conflict { pub title_key: &'static str, pub description_key: &'static str,
                                          pub first_jar: String, pub second_jar: String }
    pub fn detect_from_names(names: &[String]) -> Vec<Conflict>; }

pub mod diff { pub struct UpdateDiff { pub added: Vec<String>, pub removed: Vec<String>, pub updated: Vec<String> }
    pub fn base_name(file: &str) -> String; pub fn mod_key(file: &str) -> String;
    pub fn diff_mods(manifest: &[ManifestFile], installed: &[String],
                     installed_differs: impl Fn(&ManifestFile, &str) -> bool) -> UpdateDiff; }

pub mod document {
    pub struct ModPackDocument { /* serde_json::Map with preserve_order */ }
    pub enum FileList { Mods, Configs, ResourcePacks, ShaderPacks, InitialFiles }
    pub struct FileEntry { /* section 1.24 */ }
    impl ModPackDocument {
        pub fn blank() -> Self; pub fn from_json(s: &str) -> Result<Self, DocError>;
        pub fn to_pretty_json(&self) -> String;
        pub fn read_file_list(&self, l: FileList) -> Vec<FileEntry>;
        pub fn write_file_list(&mut self, l: FileList, e: &[FileEntry]);
        pub fn fork_of(&self, new_name: &str) -> Result<Self, DocError>;
        pub fn add_mod(&mut self, e: FileEntry) -> bool; pub fn remove_mod(&mut self, id: &str) -> bool;
        pub fn write_local_manifest(&self, dir: &Path, overwrite: bool) -> io::Result<String>;
        pub fn validate(&self, r: &dyn Fn(&str, &[String]) -> String) -> Vec<String>;
    }
    pub fn bump_version(current: Option<&str>, position: usize) -> String;
}
```

### 3.3 `mmcl-progress`

```rust
#[derive(Copy, Clone, Debug, PartialEq, Eq, Hash)]
pub enum StepId { ModpackContent, ForgeLibs, McLibsAssets, JreInstall, ForgeProcessors, SecurityScan }
#[derive(Clone, Debug, PartialEq)] pub enum StepState { Pending, Running, Done, Failed, Skipped }
#[derive(Clone, Debug)] pub struct StepSnapshot { pub id: StepId, pub state: StepState,
    pub progress: f64, pub sub_text: String, pub error: Option<String> }

pub struct LaunchProgressTracker { /* Mutex<Vec<StepSnapshot>>, listeners: RwLock<Vec<Arc<dyn Fn(&StepSnapshot) + Send + Sync>>> */ }
impl LaunchProgressTracker {
    pub fn for_steps(ids: &[StepId]) -> Arc<Self>;
    pub fn steps(&self) -> Vec<StepSnapshot>;
    pub fn step(&self, id: StepId) -> Option<StepSnapshot>;
    pub fn overall_fraction(&self) -> f64;
    pub fn running_steps(&self) -> Vec<StepId>;
    pub fn add_listener(&self, l: Arc<dyn Fn(&StepSnapshot) + Send + Sync>) -> ListenerId;
    pub fn remove_listener(&self, id: ListenerId);
    pub fn mark_running(&self, id: StepId) -> Result<(), UnknownStep>;
    pub fn mark_done(&self, id: StepId) -> Result<(), UnknownStep>;
    pub fn mark_failed(&self, id: StepId, msg: impl Into<String>) -> Result<(), UnknownStep>;
    pub fn mark_skipped(&self, id: StepId) -> Result<(), UnknownStep>;
    pub fn set_progress(&self, id: StepId, p: f64) -> Result<(), UnknownStep>;
    pub fn set_sub_text(&self, id: StepId, t: impl Into<String>) -> Result<(), UnknownStep>;
    pub fn submit_progress(&self, id: StepId, p: f64, t: impl Into<String>) -> Result<(), UnknownStep>;
}

/// Legacy sink every sub-call uses (file sync, loader, runtime, scanner).
pub trait ProgressSink: Send + Sync {
    fn submit_progress(&self, detail: &str, section_delta: f64);
    fn set_curr_text(&self, detail: &str);
    fn start_section(&self, title: &str, size: f64);
    fn end_section(&self, text: &str);
    fn download_tracker(&self) -> Option<Arc<DownloadTracker>> { None }
    fn enter_step(&self, _id: StepId) {}
    fn complete_step(&self, _id: StepId) {}
    fn fail_step(&self, _id: StepId, _msg: &str) {}
    fn on_error(&self, _msg: &str, _cause: Option<&(dyn std::error::Error + Send + Sync)>) {}
}
pub struct SectionProgress<F: Fn(f64, &str, &str, &str) + Send + Sync> { /* weighted model, 50 ms throttle */ }
pub struct TrackerBridge { /* tracker, active step, accumulator */ }
impl TrackerBridge { pub fn new(t: Arc<LaunchProgressTracker>) -> Self; pub fn handle_for(&self, id: StepId) -> StepHandle; }
pub struct StepHandle { /* tracker, step, accumulator, last_fire */ }
impl StepHandle { pub fn mark_running(&self); pub fn mark_done(&self); pub fn mark_failed(&self, m: &str); pub fn mark_skipped(&self, reason: &str); }
pub struct NullSink;                                     // replaces "progressProvider == null"
```

`DownloadTracker` belongs to the network crate; `mmcl-progress` can depend on it or define a tiny
trait. Throttle clocks should be injectable (`Instant` source) for tests.

### 3.4 `mmcl-modpack`

```rust
pub enum PackKind { Manifest, Vanilla { mc_version: String }, Failed { error: String }, Stub }

pub struct GameModPack {
    pub manifest: PackManifest,
    pub kind: PackKind,
    manifest_url: Option<String>,
    manifest_sha256: Option<String>,
    paths: Arc<DataPaths>,                               // from the paths crate
    prepare_context: ArcSwap<PrepareContext>,
    quick_join: Mutex<Option<ServerFavorite>>,
    env: OnceLock<PackEnvironment>,
    loader: Mutex<Option<Arc<dyn ModLoader>>>,           // see 3.1 on the cycle
    update_available: Mutex<Option<bool>>,
    history: Mutex<Option<LaunchHistory>>,
}
impl GameModPack {
    pub fn vanilla(paths: Arc<DataPaths>, version: &str) -> Arc<Self>;
    pub fn failed(paths: Arc<DataPaths>, url: &str, error: &str) -> Arc<Self>;
    pub fn stub_from_index(paths: Arc<DataPaths>, url: &str, e: &IndexEntry) -> Arc<Self>;
    pub fn manifest_url(&self) -> Option<&str>;
    pub fn manifest_sha256(&self) -> Option<&str>;
    pub fn settings_key(&self) -> Option<String>;
    pub fn pack_root(&self) -> PathBuf;
    pub fn is_launchable(&self) -> bool;                 // !Failed && !Stub
    pub fn prepare_context(&self) -> Arc<PrepareContext>;
    pub fn set_prepare_context(&self, c: Arc<PrepareContext>) -> Arc<PrepareContext>; // returns previous
    pub fn set_quick_join_server(&self, s: Option<ServerFavorite>);
    pub fn consume_quick_join_server(&self) -> Option<ServerFavorite>;
    pub fn uses_post_install_steps(&self) -> bool;
    pub fn latest_crash_report(&self) -> Option<String>;
    // sidecars
    pub fn installed_version(&self) -> Option<String>;
    pub fn save_installed_version(&self);
    pub fn is_update_available(&self) -> bool;
    pub fn record_launch_start(&self); pub fn record_session_end(&self, duration_ms: i64);
    pub fn last_played_ms(&self) -> i64; pub fn total_play_time_ms(&self) -> i64; pub fn launch_count(&self) -> i32;
    pub fn seen_news_ids(&self) -> HashSet<String>; pub fn unread_news_count(&self) -> usize; pub fn mark_all_news_seen(&self);
    // environment
    pub fn prepare_environment(&self, side: Side);
    pub async fn cache_images(&self, http: &HttpClient);
    pub fn logo_paths_raw(&self) -> Vec<PathBuf>; pub fn background_paths_raw(&self) -> Vec<PathBuf>;
    pub fn logo_path_raw(&self) -> PathBuf; pub fn background_path_raw(&self) -> PathBuf;
}

pub struct PrepareContext { pub verify_mode: VerifyMode, audit_root: Option<PathBuf>, launch_id: i32 }
impl PrepareContext { pub const NONE: Self; pub fn for_launch(m: VerifyMode, root: Option<PathBuf>, id: i32) -> Self;
    pub fn is_auditing(&self) -> bool; pub fn record_redownload(&self, path: &Path, old: Option<&str>, new: Option<&str>, expected: Option<&str>, algo: Option<HashAlgo>); }

pub struct ManagedFile<'a> { pub spec: &'a ManifestFile, pub prefix: Option<PathBuf>, /* session flag lives in a SessionVerified set keyed by full path */ }
impl ManagedFile<'_> {
    pub fn full_path(&self) -> PathBuf;
    pub fn is_contained(&self) -> bool;
    pub fn verify(&self, ctx: &PrepareContext) -> bool;
    pub async fn update(&self, ctx: &PrepareContext, env: &SyncEnv) -> Result<bool, ModpackError>;
    pub async fn reverify(&self, ctx: &PrepareContext, env: &SyncEnv) -> Result<(), ModpackError>;
    pub fn matches_declared_hash(&self, file: &Path) -> io::Result<bool>;
}
pub fn is_jar_url_contained_in(root: &Path, url: &str) -> bool;
pub struct VerifyCache;                                  // process-wide, DashMap<Key, bool>

pub struct SyncEnv { pub http: HttpClient, pub pool: Arc<DownloadPool>, pub side: Side,
                     pub offline: Arc<OfflineFlag>, pub data_root: PathBuf, pub sink: Arc<dyn ProgressSink> }
pub struct FileSync<'a> { pack: &'a GameModPack, env: &'a SyncEnv }
impl FileSync<'_> {
    pub fn new(pack: &GameModPack, env: &SyncEnv) -> Self;   // sets the tracker's total count
    pub async fn fetch_latest_mods(&self) -> Result<(), ModpackError>;
    pub async fn fetch_latest_configs(&self) -> Result<(), ModpackError>;
    pub async fn fetch_latest_resource_packs(&self) -> Result<(), ModpackError>;
    pub async fn fetch_latest_shader_packs(&self) -> Result<(), ModpackError>;
    pub async fn fetch_latest_initial_files(&self) -> Result<(), ModpackError>;
}

pub struct ManifestFetcher { /* paths, http, offline flag, notifier */ }
impl ManifestFetcher {
    pub async fn get(&self, url: &str, create_env: bool) -> Arc<GameModPack>;   // never fails
    pub fn get_from_cache(&self, url: &str, create_env: bool) -> Option<Arc<GameModPack>>;
    pub fn get_stub_from_index(&self, url: &str) -> Option<Arc<GameModPack>>;
    pub fn load_manifest_text(&self, url: &str) -> Option<String>;
}
pub fn java_string_hash_code_hex(s: &str) -> String;     // legacy cache name

pub struct InstallIndex { pub version: i32, pub packs: IndexMap<String, IndexEntry> }
impl InstallIndex { pub fn load(path: &Path) -> Self; pub fn upsert_and_save(path: &Path, url: &str, pack: &GameModPack);
                    pub fn remove_and_save(path: &Path, url: &str); pub fn get(&self, url: &str) -> Option<&IndexEntry>; }

pub struct ModPackManager { /* lists, locks, futures, fetcher, config handle, session registry handle */ }
impl ModPackManager {
    pub async fn fetch_installed(&self, block_until_revalidated: bool);
    pub async fn fetch_available(&self);
    pub fn start_available_fetch(&self) -> Shared<BoxFuture<'static, ()>>;
    pub fn is_available_fetch_pending(&self) -> bool;
    pub fn installed_revalidate(&self) -> Option<Shared<BoxFuture<'static, ()>>>;
    pub fn is_installed_revalidate_pending(&self) -> bool;
    pub fn installed(&self) -> Arc<Vec<Arc<GameModPack>>>;
    pub async fn available(&self) -> Arc<Vec<Arc<GameModPack>>>;
    pub fn available_if_ready(&self) -> Arc<Vec<Arc<GameModPack>>>;
    pub fn installed_by_name(&self, n: &str) -> Option<Arc<GameModPack>>;
    pub fn installed_by_url(&self, u: &str) -> Option<Arc<GameModPack>>;
    pub fn installed_by_friendly_name(&self, n: &str) -> Option<Arc<GameModPack>>;
    pub async fn install_by_url(&self, url: &str) -> Result<Arc<GameModPack>, ModpackError>;
    pub async fn install_by_friendly_name(&self, n: &str) -> Result<(), ModpackError>;
    pub async fn uninstall(&self, pack: &GameModPack, delete_files: bool) -> bool;
    pub fn subscribe(&self) -> watch::Receiver<ListsVersion>;   // UI re-render signal
    pub fn set_background_error_listener(&self, l: Option<Arc<dyn Fn(&str, &anyhow::Error) + Send + Sync>>);
}
pub(crate) fn uninstall_guarded(pack: &GameModPack, delete: bool, is_active: impl Fn(&GameModPack) -> bool,
                                delete_folder: impl FnOnce(&GameModPack), unregister: impl FnOnce(&GameModPack)) -> bool;
pub(crate) fn replace_or_append_by_url(list: &mut Vec<Arc<GameModPack>>, url: &str, fresh: Arc<GameModPack>);

pub mod verify_state { pub struct VerifyState { /* section 1.11 */ }
    pub fn decide_mode(s: Option<&VerifyState>, sha: Option<&str>, ttl_ms: i64, always: bool, force: bool, now_ms: i64) -> VerifyMode;
    pub enum ScanFrequency { EveryTime, Daily, OnChangesOnly, Disabled }
    pub fn should_scan(f: ScanFrequency, s: Option<&VerifyState>, sha: Option<&str>, now_ms: i64) -> bool; }
pub mod audit { pub fn record_redownload(root: &Path, launch: i32, path: &Path, old: Option<&str>, new: Option<&str>, expected: Option<&str>, algo: Option<&str>);
    pub struct Problem { pub file: String, pub consecutive_launches: i32, pub content_unchanged: bool }
    pub fn analyze_problems(root: &Path, most_recent: i32, threshold: i32) -> Vec<Problem>; }
pub mod update_log { pub fn record_remote_version_seen(p: &GameModPack); pub struct Entry { pub ts_ms: i64, pub old: String, pub new: String };
    pub fn read_entries(p: &GameModPack) -> Vec<Entry>; }
pub mod servers { pub fn load(p: &GameModPack) -> Vec<ServerFavorite>; pub fn save(p: &GameModPack, f: &[ServerFavorite]) -> io::Result<()>;
    pub fn is_default_server_disabled(p: &GameModPack) -> bool; pub fn set_default_server_disabled(p: &GameModPack, d: bool) -> io::Result<()>; }
pub mod backup { pub fn backup_before_update(p: &GameModPack, policy: &BackupPolicy) -> Option<PathBuf>; pub fn prune_old_backups(p: &GameModPack, policy: &BackupPolicy); }
pub mod export { pub enum ExportMode { ShareUrl, ShareManifestJson, ExportZip }
    pub fn classify_export(p: &GameModPack, f: &ManifestFetcher) -> ExportMode;
    pub fn export_to_zip(p: &GameModPack, dest: &Path, include_saves: bool, progress: impl FnMut(u64)) -> io::Result<()>; }
pub mod diff { pub fn pending_update_diff(p: &GameModPack) -> mmcl_manifest::diff::UpdateDiff; }
pub mod modrinth_updates { pub async fn scan(mods_dir: &Path, http: &HttpClient) -> HashMap<String, ModUpdate>; }
pub mod catalog { /* loaders + vanilla, section 1.17 */ }
```

Error type: `ModpackError` (`thiserror`) with variants mirroring the localized messages
(`OutsidePack(PathBuf)`, `NonHttps(String)`, `HashFailed{attempts, path, url}`,
`MissingNoUrl(PathBuf)`, `OfflineHashMismatch(PathBuf)`, `OfflineMissing(PathBuf)`,
`ManifestFetchFailed(String)`, `ManifestStub(String)`, `ModsMissing{pack, missing, required, names}`,
`ModsTimedOut`, `ModsInterrupted`, `ModsFailed(String)`, `UnsupportedLoader(String)`, `Io(io::Error)`,
`ScanDetection{..}`). The UI localizes from the variant; log lines localize at the call site.

### 3.5 Concurrency model

* Network: `tokio` + `reqwest` (the network crate). GPUI's executors run UI tasks; run modpack
  work on a tokio runtime and post results to the UI via channels.
* Download pool: a `tokio::sync::Semaphore` sized `max(16, 2 * cores)` (`DownloadExecutor`
  `POOL_SIZE`), shared by all packs. Mods use `JoinSet` + semaphore; the 30-minute bound is
  `tokio::time::timeout` around the join; on error `abort_all()`.
* Manifest fetch fan-out: semaphore of `max(4, 2 * cores)`.
* Hashing is CPU / disk bound: run in `spawn_blocking` (or a `rayon` pool), stream with 64 KiB
  buffers.
* Per-path locks for sidecars: the files crate's `PathLocks` (`parking_lot::Mutex`), never held
  across `.await`.
* Install index: one process-wide `Mutex` around load-modify-save.
* Lists: `ArcSwap` snapshots for lock-free readers; a `tokio::sync::Mutex` serializes the mutating
  operations (equivalent to Java's class monitor). Do not hold it while awaiting the background
  available fetch (the Java deadlock note).
* Progress listeners are called after the tracker lock is released.

---

## 4. External crates

| Need | Crate | Notes |
|---|---|---|
| JSON | `serde`, `serde_json` (`preserve_order` feature) | `preserve_order` keeps editor key order and `install_index.json` order |
| Lenient JSON fallback | `json5` | for Gson-lenient manifests (comments, single quotes, unquoted keys) |
| Ordered maps | `indexmap` | install index `packs`, de-dup of URL lists |
| Hashing | `sha1`, `sha2`, `md-5` (RustCrypto), `hex` | streaming file hashes |
| Constant-time compare | `subtle` | hex digest comparison |
| HTTP | `reqwest` (rustls) via the network crate | conditional GET, bounded body, HTTPS-only redirects |
| Async | `tokio`, `futures` (`Shared`) | pools, timeouts, background tasks |
| Lock-free snapshots | `arc-swap` | installed / available lists, prepare context |
| Concurrent maps | `dashmap` | verify cache, per-path locks |
| Locks | `parking_lot` | short critical sections |
| Regex | `regex` | `base_name`, conflict rules |
| Dates | `time` (or `chrono`) | news dates, update-log date, backup timestamp; `icu` / `icu_datetime` for the localized medium date |
| Paths | `path-clean` (or a hand-written lexical normaliser), `std::path::absolute` | containment gate |
| URLs | `url` | `jar:` / `file:` parsing, `file:` URI ↔ path (`Url::to_file_path`, `Url::from_file_path`) |
| ZIP | `zip` | `jar:` entry extraction, backups, export, importers |
| Images | `image` (png, jpeg, gif, bmp, webp) | transcode to PNG so cache names match Java |
| Errors | `thiserror`, `anyhow` (app edges) | |
| Temp files | `tempfile` | atomic writes (`NamedTempFile::persist`) |
| Directory walk | `walkdir` | export, backup |
| Recursive delete | `remove_dir_all` | Windows-safe uninstall (read-only files, long paths) |
| Tests | `tempfile`, `proptest` (version compare, StringOrArray), `insta` (pretty JSON snapshots) | |

No good Rust equivalent / needs care:

* **Gson lenient parsing**: no crate reproduces it exactly; `serde_json` + `json5` fallback +
  custom field adapters covers all real cases.
* **Gson pretty printer output** (`ModPackDocument.toPrettyJson`): `serde_json`'s
  `PrettyFormatter` uses 2 spaces and `": "` like Gson; Gson escapes `<`, `>`, `&`, `=`, `'` as
  `<` etc. by default (HTML-safe) where serde_json does not. Both parse identically; diffs
  of edited manifests will show those characters changing. Decide whether to emulate (custom
  `Formatter` escaping those five characters) or accept the churn.
* **Java `String.hashCode`** for the legacy cache name: 10 lines of code, iterate
  `s.encode_utf16()`.
* **jarscanner (Nekodetector)**: Java-only library; owned by the security spec.
* **Localized medium date** (`FormatStyle.MEDIUM` for the UI locale): `icu_datetime` or a small
  per-locale pattern table.
* **Windows path comparison**: Java's `WindowsPath.startsWith` compares case-insensitively;
  Rust `Path::starts_with` is case-sensitive on every OS. The containment gate should lowercase
  both sides on Windows (and accept that it is lexical, symlinks not resolved, as in Java).

---

## 5. Tests worth porting

From `src/test/java/com/micatechnologies/minecraft/launcher/game/modpack/`, all pure or
temp-dir based:

| Java test | What to port |
|---|---|
| `GameModSideTest` | `is_required_for` matrix; skipped side reports success without downloading |
| `IncompleteManifestLaunchGuardTest` | failed and stub packs abort mod sync before any sweep; failed sentinel is non-null but unusable; stub and failed are independent; a fresh fetch replaces the index stub in place; unknown URL is appended |
| `InstallIndexConcurrencyTest` | many concurrent `upsert_and_save` neither corrupt the file nor lose entries; unchanged upsert leaves the file untouched (mtime) but a change is saved |
| `LaunchPrepareContextTest` | NONE hashes and records nothing; each check follows the context it is given; one pack's FAST_PATH never leaks into another pack's FULL; each pack carries its own context |
| `LaunchProgressOverallTest` | overall fraction counts finished / running / pending correctly; no steps = 0 |
| `LaunchProgressTrackerNotifyTest` | listeners called outside the tracker lock (a listener that calls back into the tracker from another thread does not deadlock); one notification per mutation |
| `ManagedGameFileMultiHashTest` | multi-hash slots; null / blank → none; single-hash constructor routes to the right slot; SHA-256 preferred; falls through to SHA-1 when SHA-256 is the sentinel |
| `ManagedGameFilePostDownloadGateTest` | gate accepts match, rejects mismatch; strongest first with no fallback; ignores FAST_PATH; passes with no hash |
| `ManagedGameFileSessionVerifiedTest` | a deleted file is re-checked after a session verify; a present file stays skipped |
| `ManagedGameFileUrlGateTest` | `jar:file:` under the root accepted; outside rejected; `jar:http` rejected; malformed rejected; non-jar scheme rejected; null / empty rejected; `..` traversal out of the root rejected |
| `ModConflictDetectorTest` | each rule, long forms, case-insensitivity, single half = nothing, multiple conflicts, unrelated mods |
| `ModPackAuditLogTest` | flags a 3-launch streak; too short / stale streak not flagged; content-changed flag; no-op without a launch context; two packs' concurrent launches each write their own log; no log = no problems |
| `ModPackDocumentTest`, `ModPackDocumentAuthoringTest` | the full editor contract (about 90 cases): blank defaults, string-or-array round trips, hash precedence and preservation, side-flag defaults, write shape, version bumping edge cases, fork, add / remove mod, local manifest path safety (hostile names cannot escape the directory, near-duplicate names refuse to overwrite), validation messages |
| `ModPackFileEntryTest` | defaults, null coalescing, extra-hash case-insensitivity, clearing, read-only view |
| `PackDotFileTest` | read / read_lines absent; write-read round trip; write creates the root; modify sees existing / None |
| `PlayTimeFormattingTest` | every bucket boundary, singular / plural, future timestamp → just now, sub-minute total is not the zero key |
| `ScanAcknowledgementTest` | inner-only, outer-only, both-hash, neither, kind rules, case-insensitive hashes, locator rules, null finding, blank finding hash |
| `ServerFavoriteTest`, `ServerFavoritesStoreTest` | parse rules and limits; store round trips, flag persistence across saves, malformed JSON, host-less entries, out-of-range port clamp |
| `UninstallRunningPackGuardTest` | running pack refused before anything is deleted; idle + delete deletes then unregisters; idle keep-files only unregisters |
| `LaunchIdentityTest` (settings-key part) | manifest pack keyed by URL; vanilla keyed by version, not localized name; nothing stable → None. (The launch-argument cases belong to the launcher slice.) |

Also port from `utilities/`: `StringOrArrayTest`, `VersionUtilitiesTest`, `HashUtilitiesHexTest`,
`HashUtilitiesFileTest`.

New Rust tests worth adding (no Java equivalent):

* **Corpus test**: parse every `.mmcjson` in the `minecraft-launcher-modpacks` repo (vendor a
  copy under `rust/crates/mmcl-manifest/tests/corpus/`) and assert field counts match a
  Gson-produced reference dump.
* Lenient parsing: number-as-string, string-as-bool, duplicate keys (last wins), null list
  elements dropped, comment-bearing JSON via the json5 fallback.
* `java_string_hash_code_hex` against known Java values (`"" → "0"`, `"a" → "61"`, a URL with a
  negative hash, a non-BMP character).
* Containment gate with `..`, absolute `local`, Windows drive letters and mixed separators.
* Fetcher with a mock HTTP server (`wiremock`): 200 caches + saves validators; 304 touches mtime
  and reuses the body; 304 with a missing cache does an unconditional re-fetch; offline uses the
  cache and warns only when older than 7 days; failure returns a Failed pack with the URL set.
* File sync with a mock server: single-mod pack downloads (the old `> 1` bug); hash mismatch
  retries 3 times then errors; floating sweep keeps required mods and deletes the rest; null mod
  list never sweeps; server mode skips resource / shader packs.
* Manager: revalidate keeps the cached pack when the fresh fetch fails; duplicate install of the
  same URL does not duplicate the config entry.
* Image cache: legacy sidecar migration; declared-SHA "trust local transcoded file" branch does not
  re-download; one bad URL in a multi-image slot skips only that image.
