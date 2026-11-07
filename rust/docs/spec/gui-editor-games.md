# Porting spec: modpack editor, Running Games, dialogs and shared UI components

Slice: `src/main/java/com/micatechnologies/minecraft/launcher/gui/` — the modpack editor, the
Running Games window and its per-game pane / log view, every dialog and reusable component, and
the screens not owned by the main shell, main screen, library/browse, login, settings, runtime
management, progress or help slices.

Target: Cargo workspace under `rust/`, GUI on GPUI (`gpui = "0.2.2"`, already the workspace
dependency in `rust/Cargo.toml`). Same platforms as the Java launcher (Windows, macOS, Linux).
The Rust launcher uses its own data folder for now, laid out exactly like the Java one.

The spec is written so a Rust engineer can rebuild each piece without reading the Java. Java
class and method names are kept in backticks so the two code bases can be cross-referenced.

---

## 0. Scope, ownership and conventions

### 0.1 What this slice owns

| Area | Java sources |
|---|---|
| Modpack editor screen | `MCLauncherModPackEditorGui`, `ModPackEditorFileEntry`, `gui/modpackEditorGUI.fxml` |
| Manifest document model used by the editor (validation, version bump, file-list hash model) | `game/modpack/ModPackDocument` (+ headless `ModPackFileEntry`). Lives in `game/modpack`, but its rules are the editor's; this spec defines them fully. If the modpack slice also specs it, the two must agree; this one is the source of truth for the editor-facing behaviour. |
| Modrinth search inside the editor | inline in `MCLauncherModPackEditorGui.searchModrinth` |
| "Add a mod from Modrinth" window (detail modal → Mods) | `ModrinthAddModDialog` (+ consumes `game/modpack/import_/ModrinthClient`) |
| Running Games window / dock | `RunningGamesWindow` |
| One game's pane (steps, log, crash card, Stop/Kill) | `GameSessionPane` |
| The virtualized log view | `SessionLogView` |
| Log buffer arithmetic (shared with `GameLog`) | `LogTrimPolicy` |
| Blocking dialogs, dialog text sanitizing, open-URL/open-folder helpers | `GUIUtilities` |
| OS toast notifications | `utilities/NotificationManager` (the platform slice owns the tray; this slice owns the notification API that every screen calls) |
| First-run wizard | `MCLauncherQuickStartWizard` |
| Modrinth `.mrpack` import confirmation | `MCLauncherImportConfirmDialog` |
| Add-account window | `AddAccountDialog` (embeds the login slice's `SignInPanel`) |
| Quit / restart confirmations while games run | `MCLauncherGuiController.confirmQuitWhileGamesRun` / `confirmRestartWhileGamesRun` (the dialogs only; the controller is the shell slice's) |
| Reusable components | `LoadingIndicator`, `WavyProgressBar`, `StepBadge`, `FilterChip`, `IconButtons`, `SearchFields`, `TooltipManager`, `EmptyStates`, `AvatarImages`, `AnnouncementBanners`, `OfflineIndicator`, `WindowIcons`, `Motion`, `AnimationGate`, `BundledFonts`, `LauncherIcons`, `MaterialSwitchSkin`, `ImageFadeIn`, `ShapeScale` |
| Modpack detail modal + content browser (§9) | `MCLauncherModpackDetailModal`, `ModpackContentBrowser`. **Ownership is ambiguous**: it is opened from the main screen's cards and may also be claimed by the main-screen or library slice. §9 gives a structural spec so nothing is lost; the owning slice should reconcile. |

Not in this slice (consumed only): `MCLauncherAbstractGui` and screen navigation (shell),
`UiScale` / `ScaledRoot` / `ScaledMinSize` (shell), `KeyboardShortcutManager` /
`SystemMenuBarManager` (shell/platform), `SignInPanel` / `MicrosoftSignIn` (login),
`GameSession` / `GameSessionRegistry` / `GameLog` / `LaunchProgressTracker` (session),
`CrashReportAnalyzer` (crash), `ModrinthModUpdateChecker`, `ModpackExporter`,
`VerifyAction`, `OfficialLauncherExporter`, `DesktopShortcutManager` (modpack/platform),
`ConfigManager` (config), `LocalizationManager` (i18n), `NetworkUtilities`, `CacheManager`,
`HashUtilities`, `SensitiveDataRedactor`, `UrlValidation`, `UnitFormatter`, `VersionUtilities`
(utilities).

### 0.2 Threading model: Java → GPUI

The Java code uses two primitives everywhere:

* `SystemUtilities.spawnNewTask(r)` — run `r` on a new worker thread.
* `GUIUtilities.JFXPlatformRun(r)` — run `r` on the FX thread **and block the caller until it
  finishes** (runs inline if already on the FX thread).
* `Platform.runLater(r)` — post to the FX thread without waiting.
* `FxAsyncTask.run(background, onSuccessFx, onErrorFx)` — worker + FX continuation.

GPUI has one foreground (main) thread per app and a background executor. Mapping:

| Java | GPUI 0.2.2 |
|---|---|
| `spawnNewTask(work)` then `JFXPlatformRun(update)` | `cx.spawn(async move \|this, cx\| { let r = cx.background_spawn(async move { work() }).await; this.update(cx, \|view, cx\| { view.apply(r); cx.notify(); }) })` |
| `Platform.runLater` from a foreign thread (a reader thread, a tracker callback) | send on an `async_channel`/`flume` channel; a foreground task spawned with `cx.spawn` drains it and calls `cx.notify()` |
| A worker **blocking** on a dialog answer (`GUIUtilities.showQuestionMessage` from a worker) | `async fn` returning the answer through a `oneshot`/`futures::channel::oneshot`; the caller `.await`s it inside its `cx.spawn` task. Never block the main thread. |
| `AtomicBoolean` refresh-coalescing (`GameSessionPane.queueStepsRefresh`) | not needed: GPUI coalesces `cx.notify()` into one render per frame. Keep a "dirty" flag only if draining a channel. |
| `Timeline` 1 s ticker | `cx.spawn` loop with `cx.background_executor().timer(Duration::from_secs(1)).await`, stopped by dropping its `Task` |
| `AnimationTimer` per frame | `window.request_animation_frame()` from `render`, only while the element is visible (see §10.13 AnimationGate) |

Rule carried over from the Java code (it fixed real freezes): **no screen or helper may block
the UI thread on network, disk or a child process.** Everything listed below as "worker" runs on
the background executor.

### 0.3 Localization

Every user-visible string goes through the i18n slice (`mmcl-i18n`, `t!(key)` /
`t!(key, args…)` in this spec). Keys are listed per section. MessageFormat semantics matter:

* `{0}` slots, and `{0,choice,0#…|1#…|1<…}` choice patterns (used by `importConfirm.totals.*`).
  The i18n slice must support `choice` (or translate those keys to an equivalent plural form).
* A key with **no** arguments must be looked up with `get`, not formatted, because MessageFormat
  treats `'` as an escape and would eat apostrophes (`MCLauncherModPackEditorGui.localize`).
  The Rust i18n API should make "no args → no MessageFormat pass" automatic.
* `LocalizationManager.getOr(key, fallback)` is used for `launchStep.<STEP_ID>`.

### 0.4 Theming

The Java UI is styled by `ui-base.css` + per-theme token sheets (`ui-tokens-*.css`). The theme
slice ports those tokens to a Rust `Theme` struct. Tokens this slice needs (names are the CSS
lookup names; the theme slice defines the Rust field names):

* Material roles: `-md-primary`, `-md-on-primary`, `-md-active-indicator`,
  `-md-on-active-indicator`, `-md-error`, `-md-on-error`, `-md-outline`, `-md-outline-variant`,
  `-md-on-surface`, `-md-on-surface-variant`, `-md-surface-container-high`,
  `-md-surface-container-highest`.
* Launcher roles: `-color-primary`, `-color-warning`, `-color-danger`, `-color-text-subtle`,
  `muted` / `subtle` text colours.
* Theme ids (config values, exact strings): `"Native (Mica)"` (default), `"Dark"`, `"Light"`,
  `"Automatic"`, `"Blue+gray"`, `"Orange+purple"`, `"Creeper"`.
* `is_light_chrome(theme) = theme == "Light" || (theme == "Native (Mica)" && !os_is_dark())`.
  Used to pick dark/light title-bar chrome for every secondary window and dialog
  (`GUIUtilities.isLightChrome`).

Every secondary window in this slice (Running Games, wizard, import confirm, add account, add
mod, every dialog) must: carry the launcher window icon (§10.12), apply the current theme with a
**solid** background (the Native theme's transparent background renders white-on-white in a
non-Mica popup), and set dark/light title-bar chrome via the platform slice's
`apply_title_bar_dark_mode(window, dark)`.

### 0.5 Rust crates proposed by this spec

| Crate | Contents |
|---|---|
| `mmcl-ui-kit` | Components (§10), icons, fonts, motion, dialogs service (§7), notification facade (§8), theme accessors used by components. Depends on `gpui`, `mmcl-i18n`, `mmcl-theme`. |
| `mmcl-editor` | Modpack editor screen (§1–§3): view, file-list tables, pickers, Modrinth search, URL check, hash calc, diff. Depends on `mmcl-modpack` (document model), `mmcl-net`, `mmcl-ui-kit`. |
| `mmcl-running-games` | Running Games window/dock (§4), `GameSessionPane` (§5), `SessionLogView` (§6). Depends on `mmcl-session`, `mmcl-crash`, `mmcl-ui-kit`. |
| `mmcl-modpack` (module `document`) | `ModPackDocument`, `FileList`, `ModPackFileEntry` (§2). Pure, no GPUI. |
| `mmcl-session` (module `trim`) | `LogTrimPolicy` (§6.4). Pure. Lives with `GameLog`, which also uses it. |
| app crate `mmcl-app` (module `dialogs`) | Wizard, import confirm, add-account, quit/restart confirmations (§11). |

---

## 1. Modpack editor: screen

### 1.1 Purpose

Create, open, edit, validate, diff and save a modpack manifest (`.mmcjson`, legacy `.json`). The
screen is a view over a `ModPackDocument` (§2). It is reached from the Game Library ("Edit"),
optionally pre-loaded with a pack, and returns to the Game Library.

### 1.2 Layout (from `modpackEditorGUI.fxml`)

Root: a 1-column, 5-row grid, minimum 750×475, preferred 1000×800, style `rootPane`.

| Row | Height | Content |
|---|---|---|
| 0 | fixed 52 | Navbar (`navBar`): 36×36 brand logo (shell asset `micaBrandLogo-small`), title `t!("editor.navbar.title")` (`navBrand`), flexible spacer, offline chip (`offlineLabel`, hidden unless offline, §10.11), help button (`helpBtn`, glyph `HELP`, tooltip `tooltip.common.help`, opens help topic `MODPACK_EDITOR`). Padding left 14, right 10, spacing 12. |
| 1 | 0 or 40 | Announcement banner (§10.10), text from `AnnouncementManager.getAnnouncementModpacksEdit()`. Glyph `ANNOUNCEMENT`. |
| 2 | fixed 44 | Toolbar (`stripPane`, padding 6/16, spacing 8): `New` (`editor.toolbar.new`), `Open File` (`editor.toolbar.openFile`), `Open URL` (`editor.toolbar.openUrl`), spacer (min 16), `Validate` (`editor.toolbar.validate`), `Diff` (`editor.toolbar.diff`). Buttons 28 px high. |
| 3 | grow | Tab bar, tabs not closable: **Metadata** (`editor.tab.metadata`), **Mods**, **Configs**, **Resource Packs**, **Shader Packs**, **Initial Files** (`editor.tab.mods/configs/resources/shaders/initialFiles`). |
| 4 | fixed 44 | Bottom bar (`bottomBar`, padding 0/14, spacing 10): status label (`muted`, initial `editor.status.noFile`), spacer, `Save` (`editor.saveBtn`), `Return` (`editor.returnBtn`). |

**Metadata tab** — a vertical scroll (smooth wheel scroll, §10.17), padding 16/24, spacing 8.
Sections are titled tiles (`settingsSectionTitle` heading + `settingsRow tileOnly` tile):

1. **Pack identity** (`editor.section.packIdentity`), spacing 12:
   * `packNameField` — floating label `editor.field.packName`, width 500, height 48.
   * Row: `packVersionField` (`editor.field.packVersion`, width 300) + a connected 3-button group
     `Major` / `Minor` / `Patch` (`editor.bumpBtn.major/minor/patch`; first/middle/last corner
     shapes).
   * `packURLField` (`editor.field.packUrl`, 500).
   * `packMinRAMField` (`editor.field.minRam`, 500).
2. **Flags** (`editor.section.flags`), spacing 8: switch `editor.toggle.unstable`
   (`packUnstable`), switch `editor.toggle.customDiscordRpc` (`packCustomDiscordRpc`).
3. **Modloader** (`editor.section.modloader`), spacing 12:
   * Row: loader combo (width 160, height 32, items `Forge`, `NeoForge`, `Fabric` — brand
     names, not localized) + picker button (`t!("editor.pickerBtn.label", <loader label>)`,
     e.g. "Pick Forge Version"; initial text `editor.pickerBtn.forge`).
   * Hint label (`settingsHint`, wraps): `editor.loaderHint.forge|neoforge|fabric` for the
     current combo value (initial `editor.modloaderHint`).
   * `packForgeURLField` — `editor.field.modloaderUrl` (500).
   * `packForgeHashField` — `editor.field.modloaderSha1` (500).
   * `packMinecraftVersionField` — `editor.field.minecraftVersion` (500). Informational only.
4. **Images** (`editor.section.images`), spacing 16:
   * Row: [multi-line logo URL area, 400×58, no wrap, placeholder `editor.logoUrls.prompt`;
     below it `packLogoSha1Field` `editor.field.logoSha1`] + logo preview 64×64 (keep aspect,
     a11y `editor.preview.logo`).
   * Row: [multi-line background URL area, placeholder `editor.backgroundUrls.prompt`;
     `packBgSha1Field` `editor.field.backgroundSha1`] + background preview fit 128×64
     (a11y `editor.preview.background`).
5. **Scan exclusions** (`editor.section.scanExclusions`): multi-line area 500×80, placeholder
   `editor.filePath.prompt`, one glob per line.

**File-list tabs** (§1.5) are built in code, one per `FileList`.

### 1.3 State

```text
document:        ModPackDocument          // the working manifest
rows:            HashMap<FileList, Vec<EditorRow>>   // table models, one per tab
saved_snapshot:  String                   // pretty JSON at last load/save, for dirty check
current_file:    Option<PathBuf>          // file backing the document, if any
initial_pack:    Option<PackRef>          // consumed once by setup()
status:          SharedString             // bottom status line
logo_preview / bg_preview: Option<ImageHandle>
```

`EditorRow` (Java `ModPackEditorFileEntry`) fields: `name`, `remote`, `local`, `hash`,
`hash_type` (default `"sha1"`), `client_req` (default `true`), `server_req` (default `true`),
`modrinth_slug` (default `""`), `extra_hashes: BTreeMap<String,String>` (keys lower-case
`sha1`/`md5`/`sha256`, never the primary type; blank value removes the key), and the transient
`url_status: Option<UrlCheck { ok: bool, label: SharedString }>` which is **never** written to
the document. Rows need a stable identity (`RowId(u64)`) because selection, sorting, URL-check
results and async hash results refer to rows, not indexes.

Conversions `EditorRow ⇄ ModPackFileEntry` copy every field including all extra hashes and the
Modrinth slug; `url_status` is not carried.

### 1.4 Lifecycle and document operations

**setup** (when the screen becomes active):

1. Show the announcement banner, wire the offline chip and help button.
2. Install window-close interception: on close request, if dirty, ask
   `dialog.editor.discardChanges.{title,header,body}` with buttons
   `dialog.editor.discardChanges.button.discard` / `dialog.button.cancel`; only answer 1
   proceeds, which calls the app's close. *Known gap:* Java calls `LauncherCore.closeApp()`
   directly here, skipping the "games still running" confirmation (§11.4). The Rust port should
   route through the shell's `request_quit()` after the discard confirmation.
3. Build the five file-list tabs; add a `Search Modrinth` button (`editor.searchModrinth`) to the
   Mods tab toolbar, inserted just before the entry-count label.
4. `new_document()`; then, if `initial_pack` was set, take it (clear it) and `load_from_pack`.

**Keyboard shortcuts** (GPUI actions in a `ModpackEditor` key context; `secondary-` = Cmd on
macOS, Ctrl elsewhere; must not fire with Alt or Shift held):

* `secondary-s` → Save (only if the Save button is enabled).
* `secondary-n` → New (only if enabled).
* Plus the shell's global shortcuts (help, navigation).

**new_document()**: `document = ModPackDocument::blank()`, `current_file = None`,
`saved_snapshot = document.to_pretty_json()` (taken *before* populating — valid because a blank
document round-trips through the form unchanged, see §2.10), populate the form, status
`editor.status.newCreated`. *Known gap:* New discards unsaved edits without asking (button and
Ctrl/Cmd+N). Recommend: ask the discard question first when dirty.

**open_from_file()**: native open dialog titled `dialog.fileChooser.openModpack.title` with
filters, in this order (first is default): `"Mica Minecraft Modpack"` → `*.mmcjson`, then
`t!("dialog.fileChooser.legacyJsonModpack.filter")` → `*.json`. On pick (worker): read UTF-8,
`ModPackDocument::from_json`, then on the UI thread set `current_file`, populate, **collect**
(normalizes: adds every key the form owns), and snapshot. Status `editor.status.loaded {file
name}`. On error: log `log.editor.openFileFailed` + error, status
`editor.status.errorLoadingFile {msg}`.

> GPUI's `cx.prompt_for_paths(PathPromptOptions{files,directories,multiple,prompt})` has no
> file-type filters. Use `rfd` (`AsyncFileDialog::new().add_filter(...)`) for open/save so the
> `.mmcjson` filter and initial file name survive. See §12.

**open_from_url()**: Java shows a confirmation (`dialog.editor.openFromUrl.{title,header,body}`,
buttons `dialog.button.ok` / `dialog.button.cancel`; only answer 1 continues) and then a text
input dialog (`dialog.editor.urlInput.{title,header,contentLabel}`). Rust: one themed modal with
a URL text field, title/header from the same keys, OK/Cancel. On OK (worker): status
`editor.status.downloading`; GET the URL as text (§12 net facade); parse; on the UI thread
`current_file = None`, populate, collect, snapshot; status `editor.status.loadedFromUrl`. Error:
log `log.editor.downloadUrlFailed`, status `editor.status.errorLoadingUrl {msg}`.

**load_from_pack(pack)** (pre-load from Library):

* `manifest_url = pack.manifest_url()`; if blank → status
  `editor.status.noManifestSource {pack friendly name}`.
* Worker: `text = GameModPackFetcher::load_manifest_text(manifest_url)` (modpack slice: a
  `file:` URL reads that file; anything else reads the cached manifest). Blank → status
  `editor.status.manifestLoadFailed {name}`. Parse failure → `editor.status.manifestUnreadable
  {name}`.
* `current_file = Some(path)` **only** when the URL starts with `file:` (imported/created packs:
  Save then round-trips to the file the launcher reads at start-up); otherwise `None`.
* UI thread: set document, populate, collect, snapshot, status `editor.status.loaded {name}`.
* Any other error: log `log.editor.manifestLoadFailed {name}` + trace, status
  `editor.status.errorLoading {name} {msg}`.

**save_to_file()**: collect the form into the document first. Native save dialog titled
`dialog.fileChooser.saveModpack.title`, same two filters (`.mmcjson` default). Initial location:
if `current_file` → its parent dir and file name; else if pack name non-empty →
`sanitized_file_base_name() + ".mmcjson"`. On pick (worker): write
`document.to_pretty_json()` as UTF-8 (overwrite), set `current_file`, `saved_snapshot =` the
written text, status `editor.status.saved {file name}`. Error: log `log.editor.saveFailed`,
status `editor.status.errorSaving {msg}`. Note: Save always shows the dialog (it is "Save As"
pre-filled); keep that unless the user asks otherwise.

**is_dirty()** = `collect(); document.to_pretty_json() != saved_snapshot` (false if there is no
document). **confirm_navigate_away()** (shell hook, called before menu/shortcut/dock navigation):
`true` if clean, else ask the discard question → `answer == 1`.

**Return**: if dirty and the answer isn't 1, stay; else navigate to the Game Library (on failure
log `log.editor.returnLibraryFailed`).

**Version bump**: `packVersionField = ModPackDocument::bump_version(field, 0|1|2)`, status
`editor.status.versionBumped {new}`.

**Image previews**: refresh when the logo/background URL area loses focus, and on every
populate. Uses `ModPackDocument::first_line(text)`; blank → clear; otherwise load
asynchronously via the library slice's `ModpackImageResolver::load_logo(url)` (decoded to a
256 px box). Any failure clears the preview. All preview changes on the UI thread.

**Status line**: `update_status(msg)` may be called from any task; it marshals to the UI thread.

### 1.5 Form ⇄ document mapping

**populate (document → form)**:

| Control | Source |
|---|---|
| name, version, URL, min RAM | `get_string("packName" / "packVersion" / "packURL" / "packMinRAMGB")` |
| unstable, custom RPC | `get_bool("packUnstable" / "packCustomDiscordRpc")` |
| loader combo | `display_label(get_string("packModLoader"))`: trimmed, lower-case (ROOT) `"neoforge"`→`NeoForge`, `"fabric"`→`Fabric`, anything else/blank→`Forge` |
| loader URL | `packModLoaderURL`, or `packForgeURL` when the former is blank |
| loader hash | `packModLoaderHash`, or `packForgeHash` when blank |
| MC version | `packMinecraftVersion` |
| logo URLs / bg URLs | `get_string_or_array_lines("packLogoURL" / "packBackgroundURL")` |
| logo sha1 / bg sha1 | `packLogoSha1` / `packBackgroundSha1` |
| scan exclusions | `get_array_lines("packScanExclusions")` |
| file tables | `read_file_list(list)` for each of the 5 lists |

**collect (form → document)**, in this order (order matters for new keys' position in output):

1. `put_string` packName, packVersion, packURL, packMinRAMGB; `put_bool` packUnstable,
   packCustomDiscordRpc.
2. `loader = config_type(combo)`: `NeoForge`→`"neoforge"`, `Fabric`→`"fabric"`, else `"forge"`.
   `put_string("packModLoader", loader)`, `packModLoaderURL` = URL field,
   `packModLoaderHash` = hash field.
3. Legacy mirror: `packForgeURL` / `packForgeHash` = the URL/hash fields **if loader is forge**,
   else `""` (kept so older launcher builds still read Forge packs; blank for others because the
   values would mislead).
4. `packMinecraftVersion` = MC field.
5. `put_string_or_array("packLogoURL", area)`, `packLogoSha1`,
   `put_string_or_array("packBackgroundURL", area)`, `packBackgroundSha1`.
6. `put_array_lines("packScanExclusions", area)`.
7. `write_file_list(list, rows→entries)` for each list, iterating the **unsorted, unfiltered**
   row order (table sorting/filtering is view-only; see §1.6).

### 1.6 File-list tab

One per `FileList` (§2.3). Layout: toolbar on top (padding 8, spacing 8, centred vertically),
table filling the rest.

**Toolbar**: filter field (floating label `editor.filter.prompt`, width 200, height 32), `Add`
(`common.button.add`), `Remove` (`common.button.remove`, danger style), `Check URLs`
(`editor.checkUrls`), [Mods only: `Search Modrinth`], entry count label
`t!("editor.entryCount", n)` — counts all rows, not just filtered ones; updates on any
add/remove.

**Filter**: case-insensitive substring over `name`, `local`, `remote`. Java uses default-locale
`toLowerCase()` (Turkish dotless-i bug); Rust should use Unicode `to_lowercase()` on both sides.
Empty filter shows everything.

**Table**: multi-row selection, editable cells, sortable columns (click header). Sorting and
filtering produce a *view* order (a `Vec<RowId>`); the model order is unchanged, so saving keeps
manifest order. Column policy: the last column absorbs extra width; Remote URL and Local Path
grow.

| Column | Shown when | Key | Editor | Pref / min / max width |
|---|---|---|---|---|
| Name | `has_name` (Mods) | `editor.column.name` | text | 120 / 80 / 200 |
| Remote URL | always | `editor.column.remoteUrl` | text | 300 / 150 / — (absorbs space) |
| Local Path | always | `editor.column.localPath` | text | 150 / 100 / — |
| Hash | always | `editor.column.hash` | text | 140 / 80 / — (fits 40-char SHA-1) |
| Type | always | `editor.column.type` | free text (`sha1`/`md5`/`sha256`) | 55 / 45 / 65 |
| Client | `has_client_server_req` | `editor.column.client` | checkbox | 55 / 50 / 60 |
| Server | `has_client_server_req` | `editor.column.server` | checkbox | 55 / 50 / 60 |
| URL status | always | `editor.column.urlStatus` | read-only; blank until checked; label in green (`urlStatusOk`) when `ok`, red (`urlStatusFailed`) otherwise — decide by `ok`, never by comparing the localized label | 70 / 55 / 90 |
| (calc) | always | header `""`, button `editor.calc` (small inline button) | not sortable | 50 / 45 / 55 |

Cell editing: Java's `TextFieldTableCell` commits on Enter and **cancels on focus loss** (edits
silently lost when clicking away — a JavaFX quirk). Rust: commit on Enter *and* on blur; Escape
cancels.

**Add**: append `EditorRow::default()` (all blank, `sha1`, client/server true). **Remove**:
delete every selected row from the model.

**Calc (per row)**: if `remote` is blank, do nothing. Worker: status
`editor.status.computingHashFor {local}`; download `remote` to a temp file (prefix
`mclauncher_hash_`, suffix `.tmp`, deleted afterwards); SHA-1 (lower-case hex) of the file; on
the UI thread set `hash = sha1`, `hash_type = "sha1"`; status `editor.status.hashComputed
{first 8 chars}`. `None` hash → `editor.status.hashReturnedNull`. Error → log
`log.editor.hashCalcFailed {msg}` (silent) + status `editor.status.hashCalcFailed {msg}`.
*Known gap:* if the row's primary was `sha256`, switching it to `sha1` drops the sha256 (it is
not moved to `extra_hashes`, so it saves as `"-1"`). Since a recompute usually means the file
changed, the old hashes are stale anyway; recommended fix: compute SHA-1, SHA-256 and MD5 in one
streaming pass and write all three (primary = sha256, matching the read precedence in §2.6).
Open question.

**Check URLs** (whole tab):

1. Snapshot the current rows (the check runs against the snapshot; rows added meanwhile are not
   checked). Clear every snapshot row's `url_status` on the UI thread.
2. Worker, sequential: status `editor.status.checkingUrls {tab name}`. For each row with a
   non-blank remote: `checked += 1`; send `HEAD remote` with connect and read timeouts of 5 s and
   `User-Agent: MicaMinecraftLauncher`; **do not follow redirects** (Java's
   `HttpURLConnection` does not follow http→https, and any 3xx counts as reachable — use
   `redirect::Policy::none()` or equivalent). `ok = 200 <= code < 400`; label = `t!("editor.urlStatus.ok")`
   when ok, else the numeric code. Any error (including non-http schemes such as `modrinth:` or
   `file:`, which in Java fail the cast to `HttpURLConnection`) → `ok = false`, label
   `t!("editor.urlStatus.error")`. Post each result to the row as it lands.
3. When done: clear the table selection, select every broken row, scroll to the first broken
   row; status `editor.status.urlCheck {checked} {broken}`.

Servers that reject `HEAD` (405) report as broken — same as Java.

### 1.7 Modloader pickers

The picker button dispatches on the combo value (null → Forge). All three show a modal list
picker; Java uses a `ChoiceDialog` drop-down that defaults to the first entry. Rust: a themed
modal with a filterable list (hundreds of entries), first entry pre-selected, OK/Cancel; map the
choice back by **index**, not by label (Java maps by localized label, which would collide if two
entries rendered the same).

**Forge** (`pickForgeVersion`):

1. Read the MC-version field on the UI thread first (`prefilter`).
2. Worker: status `editor.status.fetchingForge`; GET
   `https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json`; take object
   `promos` (map `"<mc>-recommended"|"<mc>-latest"` → forge version string).
3. Split into `recommended[mc]` and `latest[mc]` (strip the suffix).
4. `mc_versions = keys(latest)` sorted **descending** with `VersionUtilities::compare_version_numbers`
   (utilities slice). MC versions that have only a `-recommended` key are therefore not listed
   (Java behaviour).
5. If `prefilter.trim()` is non-empty **and** is in `mc_versions`, narrow to just it; otherwise
   keep the full list (a typo or untracked version must still leave something pickable).
6. For each mc: if recommended exists, entry `t!("editor.versionPick.forgeRecommended", mc,
   ver)`; if latest exists and differs from recommended, entry
   `t!("editor.versionPick.forgeLatest", mc, ver)`. URL for both:
   `https://maven.minecraftforge.net/net/minecraftforge/forge/{mc}-{ver}/forge-{mc}-{ver}-installer.jar`.
   (Very old Forge builds used a different artifact naming; the generated URL is wrong for them.
   Java has the same limitation.)
7. Dialog `dialog.editor.pickForge.{title,header,contentLabel}`. On choice: URL field = url,
   hash field = `""`, status `editor.status.forgeUrlSet`, then **compute the installer hash**
   (below) with label `Forge`.
8. Error: log `log.editor.fetchForgeFailed` + trace, status `editor.status.fetchForgeFailed {msg}`.

**NeoForge** (`pickNeoForgeVersion`):

1. Worker: status `editor.status.fetchingNeoForge`; GET
   `https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml`.
2. Extract versions with regex `<version>([^<]+)</version>` (no XML parser needed); sort
   descending with `compare_version_numbers`. (Note: the feed does contain `-beta` suffixed
   versions despite the Java comment; the comparator's handling of suffixes decides their order.)
3. For each version: `mc = neoforge_mc_version(v)`; label
   `t!("editor.versionPick.neoForgeWithMc", mc, v)` or `t!("editor.versionPick.neoForge", v)`
   when `mc` is `None`. URL
   `https://maven.neoforged.net/releases/net/neoforged/neoforge/{v}/neoforge-{v}-installer.jar`.
4. `neoforge_mc_version(v)`: split on `.`; fewer than 2 parts → `None`; parse parts 0 and 1 as
   `i32` (failure → `None`); `minor == 0 ? "1.{major}" : "1.{major}.{minor}"` (e.g. `21.1.95` →
   `1.21.1`, `20.4.190` → `1.20.4`, `21.0.x` → `1.21`). **Open question:** for the MC 26.x
   era this formula yields `1.26.1`; confirm NeoForge's scheme for MC 26.1+ and adjust.
5. Dialog `dialog.editor.pickNeoForge.*`. On choice: URL, clear hash, status
   `editor.status.neoForgeUrlSet`, compute hash with label `NeoForge`.
6. Error: `log.editor.fetchNeoForgeFailed`, `editor.status.fetchNeoForgeFailed {msg}`.

**Fabric** (`pickFabricVersion`):

1. Worker: status `editor.status.fetchingFabric`; GET `https://meta.fabricmc.net/v2/versions/game`
   and `https://meta.fabricmc.net/v2/versions/loader` (both JSON arrays, newest first).
2. Loader = first loader object with `"stable": true`; else the first loader; none →
   status `editor.status.fabricNoLoaders` and stop.
3. Entries for every game object **except** those with `"stable": false` (objects without the
   field are included): label `t!("editor.versionPick.fabric", mc, loader)`, URL
   `https://meta.fabricmc.net/v2/versions/loader/{mc}/{loader}/profile/json`.
4. Dialog `dialog.editor.pickFabric.*`. On choice: URL, hash `""` (Fabric profile JSON is
   generated per request; the launcher treats a blank hash as "skip verification"), status
   `editor.status.fabricUrlSet`. No hash computation.
5. Error: `log.editor.fetchFabricFailed`, `editor.status.fetchFabricFailed {msg}`.

**Installer hash** (`computeAndPopulateHash(url, label)`), worker: temp file
`{label lower}_installer_*.jar`; download; SHA-1; delete temp; on success set the hash field and
status `editor.status.hashComputedLoader {label} {first 8 chars}`; on error status
`editor.status.hashComputeLoaderFailed {label} {msg}`. A `None` hash is silent.

### 1.8 Modrinth search (editor, Mods tab)

Modal dialog, title `dialog.editor.searchModrinth.title`, header
`dialog.editor.searchModrinth.header`, content width 600, buttons **Add selected**
(`dialog.editor.searchModrinth.addSelected`) and Cancel.

Content (vertical, spacing 8):

1. Search bar: text field (floating `dialog.editor.searchModrinth.modNamePlaceholder`, width
   400, height 36) + primary button `dialog.editor.searchModrinth.searchBtn`. Enter in the field
   runs the search and **must not** close the dialog.
2. Version filter: checkbox `dialog.editor.searchModrinth.versionFilter` + field (width 100,
   enabled only while checked; Enter runs the search). Pre-filled by parsing the loader URL: if it
   contains `/forge/`, take the text after it up to the first `-`; if that is non-empty, fill the
   field and tick the box (`.../forge/1.20.1-47.3.0/...` → `1.20.1`). Only Forge URLs are parsed;
   the MC-version field is not consulted (possible improvement: fall back to it).
3. Loader filter: checkbox `dialog.editor.searchModrinth.loaderFilter` + combo
   (`forge`, `neoforge`, `fabric`, `quilt`; width 150; enabled only while checked). If the pack's
   loader combo value lower-cased is in the list, pre-select it and tick the box.
4. Info label: initial `editor.search.prompt`.
5. Results list: height 350, **multi-select**, rich rows (below).

**Search** (blank query → nothing): info `editor.modSearch.searching`, clear results. Read the
filters on the UI thread (`game_version` = trimmed field if ticked; `loader` = lower-cased combo
value if ticked and non-null). Worker:

```text
facets = [["project_type:mod"]] (+ ["versions:{game_version}"]) (+ ["categories:{loader}"])
GET https://api.modrinth.com/v2/search?query={form-urlencode(query)}&facets={form-urlencode(facets_json)}&limit=25
```

Facets JSON is built by hand exactly as shown (outer AND, inner OR). Response: `hits` array.
UI thread: append each hit; info `t!("editor.modSearch.resultsFound", count)`. Error → info
`t!("editor.modSearch.failed", msg)`.

**Result row**: 40×40 icon (keep aspect), title line `"{title}  by {author}"` (author omitted
when empty; `" by "` is hard-coded English in Java — add a key, e.g.
`editor.modSearch.byAuthor`), description (wrap, max 2 lines / 32 px): if longer than 120
**chars**, the first 117 chars + `"..."` (count `char`s, not bytes, so non-ASCII text cannot
panic). Missing fields: title `"?"`, author/description/icon `""`. Padding 4, spacing 10.
Right-click menu: `editor.modSearch.openPage` → open `https://modrinth.com/mod/{slug}` in the
browser (only when the hit has a slug).

**Icon loading** (`loadModrinthIcon` + caches):

* Decoded-icon LRU cache, capacity **128**, keyed by slug, process-wide, thread-safe. A hit
  paints immediately with no I/O.
* In-flight map keyed by slug (or by icon URL when there is no slug) so concurrent rows asking
  for the same icon share one fetch; the entry is removed when the fetch completes.
* Fetch (worker): `url = hit.icon_url`; if empty and a slug exists, GET
  `https://api.modrinth.com/v2/project/{slug}` and use its non-null `icon_url`. Still empty →
  no icon. Download through the shared disk cache (`CacheManager::download_and_cache(url)` →
  local file), decode, resize to fit 40×40 preserving aspect, smooth filtering. Decode error →
  no icon. Cache under the slug when the slug is non-empty. All failures are silent.
* Java guards against a recycled list cell painting the wrong icon. In GPUI rows are rendered
  from state each frame, so store icons in a `HashMap<slug, Arc<RenderImage>>` and `cx.notify()`
  when one lands; no stale-cell issue exists.

**Add selected** → for every selected hit build a row: `name = title`, `remote =
"modrinth:{slug}"`, `local = "{slug}.jar"`, `hash_type = "sha1"`, `modrinth_slug = slug`.
Capture the filters as they were **when the dialog opened** (Java reads them at dialog build
time, not at Add time — a bug: changing the filters after opening does not affect version
resolution; Rust should read them at Add time). Then, worker, sequentially per entry:

```text
GET https://api.modrinth.com/v2/project/{slug}/version?limit=1[&game_versions=["{gv}"]][&loaders=["{loader}"]]
```

(Java concatenates the JSON arrays unencoded; Rust must percent-encode the values.) If the array
is non-empty: take `versions[0].files[0]` (Java takes the *first* file, unlike
`ModrinthClient`, which prefers the `primary` one — recommend switching to primary-file
selection); set `remote = file.url` (or `""`), `local = file.filename` (or `{slug}.jar`),
`hash = file.hashes.sha1` (or `""`). Failure for one entry → log
`log.editor.modrinthVersionFailed {slug}` (silent) and keep the placeholder (the entry is left
with `remote = "modrinth:{slug}"`, which validation does not flag but the launcher cannot
download — consider flagging it). After all entries: UI thread appends them to the Mods rows and
sets status `editor.status.modsAdded {count}`. No duplicate check is made (the document's
`add_mod` has one, §2.9; consider using it).

### 1.9 Diff

`collect()`; `current = to_pretty_json()`; `original = saved_snapshot`.

* Equal → info dialog `dialog.editor.diff.title` / `dialog.editor.diff.noChangesHeader` /
  `dialog.editor.diff.noChangesBody`.
* Otherwise a **positional** line diff (not LCS): split both on `\n` the Java way (trailing empty
  strings dropped); for `i in 0..max(len_o, len_c)`: `o = orig[i]` or `""`, `c = curr[i]` or
  `""`; if `o != c`: if `o` non-empty push `"- " + o.trim() + "\n"`; if `c` non-empty push
  `"+ " + c.trim() + "\n"`. Show in an info dialog (header `dialog.editor.diff.changesHeader`)
  with a read-only, non-wrapping monospace text area, 600×400, expanded.

A single inserted line shifts every following line, so the output can be long. Using a real
diff (`similar` crate) is an obvious improvement; open question whether to keep exact parity.

### 1.10 Validate

`collect()` on the UI thread, then (worker) `issues = document.validate(&localize)` (§2.11).
Dialog: title `dialog.editor.validate.title`; when there are no issues, level info, header
`dialog.editor.validate.headerValid`, body `dialog.editor.validate.bodyValid`; otherwise level
warning, header `dialog.editor.validate.headerIssues`, body
`t!("dialog.editor.validate.bodyIssuesPrefix") + "\n" + issues.join("\n") + "\n"`.

### 1.11 Editor localization keys

`editor.*` (114 keys): `navbar.title`, `toolbar.{new,openFile,openUrl,validate,diff}`,
`tab.{metadata,mods,configs,resources,shaders,initialFiles}`, `section.{packIdentity,flags,modloader,images,scanExclusions}`,
`bumpBtn.{major,minor,patch}`, `toggle.{unstable,customDiscordRpc}`, `pickerBtn.{forge,label}`,
`modloaderHint`, `loaderHint.{forge,neoforge,fabric}`,
`field.{packName,packVersion,packUrl,minRam,modloaderUrl,modloaderSha1,minecraftVersion,logoSha1,backgroundSha1}`,
`preview.{logo,background}`, `logoUrls.prompt`, `backgroundUrls.prompt`, `filePath.prompt`,
`saveBtn`, `returnBtn`, `searchModrinth`, `search.prompt`, `calc`, `filter.prompt`, `checkUrls`,
`column.{name,remoteUrl,localPath,hash,type,client,server,urlStatus}`, `entryCount`,
`urlStatus.{ok,error}`, `modSearch.{searching,failed,resultsFound,openPage}`,
`versionPick.{forgeRecommended,forgeLatest,neoForge,neoForgeWithMc,fabric}`,
`status.*` (`noFile, newCreated, loaded, loadedFromUrl, downloading, saved, errorLoading,
errorLoadingFile, errorLoadingUrl, errorSaving, noManifestSource, manifestLoadFailed,
manifestUnreadable, fetchingForge, fetchingNeoForge, fetchingFabric, fetchForgeFailed,
fetchNeoForgeFailed, fetchFabricFailed, fabricNoLoaders, forgeUrlSet, neoForgeUrlSet,
fabricUrlSet, hashComputed, hashComputedLoader, hashComputeLoaderFailed, hashCalcFailed,
hashReturnedNull, computingHashFor, checkingUrls, urlCheck, modsAdded, versionBumped`),
`validate.{required,invalidMinRam,remoteEmpty,localEmpty,roundTripFailed}`,
`validate.label.{packName,packVersion,mods,configs,resourcePacks,shaderPacks,initialFiles}`.

`dialog.editor.*` (35): `discardChanges.{title,header,body,button.discard}`,
`openFromUrl.{title,header,body}`, `urlInput.{title,header,contentLabel}`,
`pickForge|pickNeoForge|pickFabric.{title,header,contentLabel}`,
`searchModrinth.{title,header,addSelected,searchBtn,modNamePlaceholder,versionFilter,loaderFilter}`,
`diff.{title,noChangesHeader,noChangesBody,changesHeader}`,
`validate.{title,headerValid,headerIssues,bodyValid,bodyIssuesPrefix}`.

Other: `dialog.fileChooser.{openModpack.title,saveModpack.title,legacyJsonModpack.filter}`,
`dialog.button.{ok,cancel}`, `common.button.{add,remove}`, `tooltip.common.help`,
`main.fetchLabel.offline`, `menu.help.title`.
Logs: `log.editor.{openFileFailed,downloadUrlFailed,saveFailed,returnLibraryFailed,manifestLoadFailed,fetchForgeFailed,fetchNeoForgeFailed,fetchFabricFailed,hashCalcFailed,modrinthVersionFailed}`.

English shapes worth knowing: `editor.validate.required = "- {0} is required"`,
`remoteEmpty = "- {0}: Remote URL is empty"`, `localEmpty = "- {0}: Local path is empty"`,
`roundTripFailed = "- Round-trip deserialization failed: {0}"`,
`status.urlCheck = "URL check: {0} checked, {1} broken"`, `entryCount = "{0} entries"`.

---

## 2. `ModPackDocument` (pure model, crate `mmcl-modpack::document`)

### 2.1 JSON representation requirements

The Java class wraps a Gson `JsonObject`. To round-trip manifests exactly:

* Use `serde_json` with **`preserve_order`** (insertion order kept; replacing an existing key
  keeps its position — Gson's `LinkedTreeMap` behaves the same, and the dirty check relies on
  it).
* Use **`arbitrary_precision`** so numbers keep their text (Gson's `LazilyParsedNumber` does:
  `2.0` stays `2.0`, `get_string` of a number returns its original text).
* Pretty output must match Gson `setPrettyPrinting()`: 2-space indent, `"key": value`, empty
  array `[]`, empty object `{}`, `\n` line breaks, no trailing newline. `serde_json`'s
  `PrettyFormatter` matches this layout.
* **HTML escaping:** Gson escapes `<`, `>`, `&`, `=`, `'` as `<`, `>`, `&`,
  `=`, `'` (and U+2028/2029 as ` `/` `) by default, and the launcher never
  disables it. URLs with `?a=b&c=d` therefore save as `?a=b&c=d`. For
  byte-identical output with Java-written files, implement a custom `serde_json::ser::Formatter`
  whose `write_string_fragment` escapes those characters with lower-case hex. Both forms parse
  identically, so this matters only for diffs against Java output. Recommend matching Gson.
* Parsing: `Gson.fromJson(String, JsonObject.class)` is **lenient** (comments, single quotes,
  unquoted names, trailing commas are tolerated). Rust: try `serde_json`; on failure, optionally
  retry with `json5`. Empty / whitespace input → `None` → error
  "Modpack manifest JSON did not parse to an object" (test `fromJsonRejectsEmptyInput`). A
  non-object top level (array, string) is also an error.

### 2.2 Constants and keys

```rust
pub const CURRENT_MANIFEST_FORMAT: i64 = 2; // format 2 added per-entry "modrinthSlug"
pub const KEY_PACK_NAME: &str = "packName";
pub const KEY_PACK_VERSION: &str = "packVersion";
pub const KEY_PACK_MIN_RAM_GB: &str = "packMinRAMGB";   // stored as a string
pub const KEY_SCAN_EXCLUSIONS: &str = "packScanExclusions";
pub const LOCAL_MANIFEST_PREFIX: &str = "created-";
pub const MODPACK_FILE_EXTENSION: &str = ".mmcjson";
pub const MODPACK_FILE_DESCRIPTION: &str = "Mica Minecraft Modpack";
pub const MOD_LOADER_FORGE: &str = "forge";
pub const MOD_LOADER_NEOFORGE: &str = "neoforge";
pub const MOD_LOADER_FABRIC: &str = "fabric";
```

Other keys the editor touches: `manifestFormat`, `packURL`, `packUnstable`,
`packCustomDiscordRpc`, `packLogoURL`, `packLogoSha1`, `packBackgroundURL`,
`packBackgroundSha1`, `packForgeURL`, `packForgeHash`, `packModLoader`, `packModLoaderURL`,
`packModLoaderHash`, `packMinecraftVersion`. Keys the editor does not model (news, links,
servers, scan acknowledgements…) must survive untouched.

### 2.3 `FileList`

| Variant | Key | has_name | has_client_server_req | label key |
|---|---|---|---|---|
| `Mods` | `packMods` | true | true | `editor.validate.label.mods` |
| `Configs` | `packConfigs` | false | true | `editor.validate.label.configs` |
| `ResourcePacks` | `packResourcePacks` | false | false | `editor.validate.label.resourcePacks` |
| `ShaderPacks` | `packShaderPacks` | false | false | `editor.validate.label.shaderPacks` |
| `InitialFiles` | `packInitialFiles` | false | true | `editor.validate.label.initialFiles` |

`FileList::ALL` in that order (iteration order is used by populate/collect/validate).

### 2.4 `blank()`

Insertion order (it is the key order of a new file):
`manifestFormat: 2`, `packName: ""`, `packVersion: "1.0.0"`, `packURL: ""`,
`packUnstable: false`, `packCustomDiscordRpc: false`, `packMinRAMGB: "2"`, `packLogoURL: ""`,
`packLogoSha1: ""`, `packBackgroundURL: ""`, `packBackgroundSha1: ""`, `packForgeURL: ""`,
`packForgeHash: ""`, `packModLoader: "forge"`, `packModLoaderURL: ""`, `packModLoaderHash: ""`,
`packMinecraftVersion: ""`, `packScanExclusions: []`, then `packMods`, `packConfigs`,
`packResourcePacks`, `packShaderPacks`, `packInitialFiles` all `[]`.

`wrapping(obj)` adopts an object without copying (tests check mutations are shared; in Rust,
`wrapping(&mut Map)` or take ownership and expose `json_mut()`). `None` → error.

### 2.5 Scalar accessors (Gson coercion rules)

* `get_string(key) -> String`: absent or JSON null → `""`; string → itself; number → original
  text; bool → `"true"`/`"false"`; array/object → Java throws `UnsupportedOperationException`.
  Rust: return `Result<String, DocError::NotScalar(key)>` from a `try_get_string`, and have
  `get_string` map that error to `""` for UI use; `validate` uses the strict form (§2.11).
* `put_string(key, v)`, `put_bool(key, v)`: insert or replace in place.
* `get_bool(key)`: absent/null → false; bool → itself; string → `eq_ignore_ascii_case("true")`;
  number → its text compared to `"true"` → false; array/object → Java throws.

### 2.6 Multi-line helpers

* `split_non_blank_lines(text)`: `None` → `[]`; split on `\n`; trim each (`\r` is removed by the
  trim); keep non-empty.
* `get_string_or_array_lines(key)`: absent/null → `""`; array → non-null elements'
  `get_as_string` joined by `\n` (null elements skipped); otherwise the scalar as a string.
* `put_string_or_array(key, text)`: lines = split_non_blank_lines; 0 → `""`; 1 → bare string;
  more → array in order. (So a one-element array collapses to a string after an edit cycle — a
  tested behaviour.)
* `get_array_lines(key)`: not an array (absent, string, …) → `""`; else elements joined by `\n`.
* `put_array_lines(key, text)`: always an array (empty for blank input).
* `first_line(text)`: first non-blank line, trimmed; `""` if none / `None`.

### 2.7 `read_file_list(list) -> Vec<ModPackFileEntry>`

Absent key or non-array → `[]`. For each element: skip non-objects. Then:

* `name = has_name && obj.has("name") ? as_string(name) : ""`; `remote`/`local` likewise
  (absent → `""`).
* `read_hash(obj, k)`: absent → none; `as_string` failing → none; blank or `"-1"` → none
  (`"-1"` is `ManagedGameFile`'s "no hash" sentinel).
* Primary hash precedence: **sha256, then sha1, then md5**; none → hash `""`, type `"sha1"`.
* Every other populated slot goes to `extra_hashes` (`sha1`/`md5`/`sha256` keyed).
* `client_req = !has_client_server_req || !obj.has("clientReq") || as_bool(clientReq)`;
  `server_req` the same. Lists without flags always read as required.
* `modrinth_slug = obj.modrinthSlug` as string when present.

### 2.8 `write_file_list(list, entries)`

Replaces the array (a `None` list writes `[]`). Per entry, keys in this order:

1. `name` (only if `has_name`), `remote`, `local`.
2. With `ht = hash_type`, `hv = hash`:
   * `sha1_out = (ht ≈ "md5" || ht ≈ "sha256") ? extra["sha1"] : (hv.is_empty() ? None : hv)`
     (≈ is ASCII case-insensitive; any unknown type therefore lands in the sha1 slot)
   * `md5_out = ht ≈ "md5" ? (hv.is_empty() ? None : hv) : extra["md5"]`
   * `sha256_out = ht ≈ "sha256" ? (hv.is_empty() ? None : hv) : extra["sha256"]`
   * emit `sha1`, `md5`, `sha256` in that order; a `None` or blank value becomes `"-1"`.
3. `clientReq`, `serverReq` (only if `has_client_server_req`).
4. `modrinthSlug` (only if non-empty).

A read→write→read→write cycle must change nothing after the first write (tests
`aReadWriteCycleDoesNotDropAnyHash`, `aSecondReadWriteCycleChangesNothingFurther`).

### 2.9 Authoring helpers (used by MCP tools and the editor)

* `fork_of(new_name)`: blank/None name → error "A fork needs a non-blank name"; deep copy via
  pretty JSON round trip; `packName = new_name.trim()`; `packVersion =
  bump_version(packVersion, 1)`; nothing else changes; independent of the source.
* `mods()` = `read_file_list(Mods)`.
* `add_mod(entry) -> bool`: false if `local` is blank or any existing mod's `local` equals it
  **ignoring case** (Windows/macOS file systems are case-insensitive); else append and rewrite.
* `remove_mod(identifier) -> bool`: blank → false; `wanted = identifier.trim()`; remove the
  **first** mod whose `local`, `name` or `modrinth_slug` equals `wanted` ignoring case; at most
  one removal per call.
* `local_manifest_key_of(name) = name` with every char outside `[a-zA-Z0-9]` removed,
  lower-cased; `None` → `""`.
* `local_manifest_path_in(dir)`: `None` dir → error; empty key → error "The pack name has no
  characters usable in a filename"; `dir/created-{key}.json`. ASCII-only filter means a hostile
  name (`../../x`) cannot escape `dir`.
* `write_local_manifest(dir, overwrite=true) -> String`: create dirs; write pretty JSON UTF-8;
  `overwrite = false` uses create-new and fails with `AlreadyExists` if present (so "My Pack" and
  "my-pack", which share `created-mypack.json`, cannot overwrite each other when creating). Return
  the file's `file:` URI (`url::Url::from_file_path`).
* `sanitized_file_base_name()` = `packName` with `[^a-zA-Z0-9]` removed (case kept).

### 2.10 `bump_version(current, position) -> String`

* `None`/blank → `"0.0.0"`.
* Split on `.` **the Java way**: trailing empty segments are dropped (`"1.2."` → `["1","2"]`),
  inner empties stay (`"1..2"` → `["1","","2"]`).
* `segments = vec![0; max(3, parts.len())]`; each part parsed like `Integer.parseInt` (optional
  sign, decimal, `i32`; failure or overflow → 0).
* If `0 <= position < segments.len()`: `segments[position] += 1` (Java `int` wraps on
  overflow; use `wrapping_add`), zero every later segment.
* Return `"{s0}.{s1}.{s2}"` — always exactly three segments, even for 4-segment input or an
  out-of-range position (`"1.2.3.4"`, pos 3 → `"1.2.3"`; pos 5 on `"1.2.3"` → `"1.2.3"`;
  negative → unchanged-but-normalized).

Round-trip guarantee used by the editor: populating the form from a document and collecting it
back yields the same JSON for documents created by `blank()`; for loaded documents, the editor
snapshots *after* one collect so missing keys added by collect don't make it dirty.

### 2.11 `validate(resolver) -> Vec<String>`

`resolver(key, args) -> String` (production: the localizer with the "no args → no
MessageFormat" rule; tests: an echo resolver). Checks, in order:

1. `check_required("packName", "editor.validate.label.packName")`, then `packVersion`: if
   absent or `as_string().trim().is_empty()` → `resolver("editor.validate.required",
   [resolver(label_key)])`. A present-but-null / array / object value is an **error**, not an
   issue (Java throws; test `aJsonNullNameThrowsRatherThanValidating`). Rust: return
   `Err(ValidateError::NotScalar(key))`; the editor never hits it because collect writes strings
   first.
2. If `packMinRAMGB` is present: parse its string form like `Double.parseDouble` — trim
   whitespace, allow an optional trailing `d`/`D`/`f`/`F`, accept `NaN`/`Infinity`, decimals,
   exponents (`"2"`, `"2.5"` valid; `"two"` invalid). Invalid →
   `resolver("editor.validate.invalidMinRam")`. Absent → no issue.
3. For each `FileList` in order: `label = resolver(label_key)`; per entry (1-based `idx`):
   `entry_label = "{label} #{idx}"`, or `"{label} \"{name}\""` when the list has names and the
   name is non-blank; blank remote → `resolver("editor.validate.remoteEmpty", [entry_label])`;
   blank local → `resolver("editor.validate.localEmpty", [entry_label])`.
4. Round trip: deserialize `to_pretty_json()` into the modpack slice's manifest struct
   (`GameModPack` equivalent). Failure → `resolver("editor.validate.roundTripFailed",
   [err.to_string()])`.

Each issue is one message without embedded newlines. A blank document fails only on the empty
name (test `blankDocumentOnlyFailsValidationOnTheEmptyRequiredName`).

### 2.12 Rust API sketch

```rust
pub struct ModPackDocument { json: serde_json::Map<String, Value> }

impl ModPackDocument {
    pub fn blank() -> Self;
    pub fn from_json(text: &str) -> Result<Self, DocError>;
    pub fn wrapping(obj: serde_json::Map<String, Value>) -> Self;
    pub fn json(&self) -> &serde_json::Map<String, Value>;
    pub fn json_mut(&mut self) -> &mut serde_json::Map<String, Value>;
    pub fn get_string(&self, key: &str) -> String;
    pub fn try_get_string(&self, key: &str) -> Result<String, DocError>;
    pub fn put_string(&mut self, key: &str, v: impl Into<String>);
    pub fn get_bool(&self, key: &str) -> bool;
    pub fn put_bool(&mut self, key: &str, v: bool);
    pub fn get_string_or_array_lines(&self, key: &str) -> String;
    pub fn put_string_or_array(&mut self, key: &str, text: &str);
    pub fn get_array_lines(&self, key: &str) -> String;
    pub fn put_array_lines(&mut self, key: &str, text: &str);
    pub fn read_file_list(&self, list: FileList) -> Vec<ModPackFileEntry>;
    pub fn write_file_list(&mut self, list: FileList, entries: &[ModPackFileEntry]);
    pub fn fork_of(&self, new_name: &str) -> Result<Self, DocError>;
    pub fn mods(&self) -> Vec<ModPackFileEntry>;
    pub fn add_mod(&mut self, e: ModPackFileEntry) -> bool;
    pub fn remove_mod(&mut self, identifier: &str) -> bool;
    pub fn local_manifest_path_in(&self, dir: &Path) -> Result<PathBuf, DocError>;
    pub fn write_local_manifest(&self, dir: &Path, overwrite: bool) -> io::Result<String>;
    pub fn to_pretty_json(&self) -> String;          // Gson-compatible
    pub fn sanitized_file_base_name(&self) -> String;
    pub fn validate(&self, r: &dyn Fn(&str, &[&dyn Display]) -> String)
        -> Result<Vec<String>, ValidateError>;
    pub fn bump_version(current: Option<&str>, position: i32) -> String;
    pub fn first_line(text: Option<&str>) -> String;
    pub fn local_manifest_key_of(name: Option<&str>) -> String;
}

#[derive(Clone, Debug, Default, PartialEq)]
pub struct ModPackFileEntry {
    pub name: String, pub remote: String, pub local: String,
    pub hash: String, pub hash_type: String,   // default "sha1"
    pub client_req: bool, pub server_req: bool, // default true
    pub modrinth_slug: String,
    extra_hashes: BTreeMap<String, String>,     // lower-case keys
}
impl ModPackFileEntry {
    pub fn put_extra_hash(&mut self, algo: &str, value: Option<&str>); // blank removes
    pub fn extra_hash(&self, algo: &str) -> Option<&str>;
    pub fn extra_hashes(&self) -> &BTreeMap<String, String>;
}
```

---

## 3. "Add a mod from Modrinth" window (`ModrinthAddModDialog`)

Opened from the detail modal's Mods section (§9.6) for a modded pack. Downloads a loose jar into
the pack's `mods/` folder; it does **not** edit the manifest.

* `show(pack, mods_dir, owner)`: `None` pack or dir → return. `loader = pack.mod_loader_type()`;
  `None` (vanilla) → warning notification `addMod.vanilla.{title,body}` and return.
* Window: **non-modal**, owned by `owner`, title `addMod.title`, 560×520 (× UI scale), padding
  16, spacing 10, launcher icon, current theme with solid background. Focus the query field on
  open.
* Content: search bar [query field (floating `addMod.search.prompt`, width 320, grows) +
  primary button `addMod.search.button` (height 32)]; compatibility note
  `t!("addMod.compatNote", loader)` (muted, wraps); status label (muted); scrolling results list
  (viewport height 360, grows).
* On open, a worker resolves `mc_version = pack.minecraft_version()` once (may read/parse the
  installer); failure → log `log.addMod.mcVersionUnresolved {msg}`, `mc_version = None`
  ("any version").
* Search (Enter or button; blank query ignored): disable the button, clear results, status
  `addMod.searching`; worker `ModrinthClient::search(q, mc_version, loader, 25)`; UI: re-enable
  button; no hits → `addMod.noResults`; else `t!("addMod.resultCount", n)` and one row per hit.
  (If the search runs before the MC version resolves, it searches any version — same as Java.)
* `ModrinthClient::search` (modpack import slice; behaviour needed here): returns `[]` for a
  blank query; facets `[["project_type:mod"](,["versions:{mc}"])(,["categories:{loader lower}"])]`;
  GET `https://api.modrinth.com/v2/search?limit={clamp(limit,1,100)}&index=relevance&query={enc}&facets={enc}`
  through the bounded HTTPS-only fetch with a **5 MB** cap; hits map to
  `SearchHit{project_id, slug, title, description, icon_url, author, downloads}`; any failure →
  log `log.modrinthClient.searchFailed {query} {error type}` and `[]`.
* Row: title (`title` or slug; style `modpackDetailContentName`), meta
  `t!("addMod.result.meta", author or "?", format_downloads(downloads))`, description (wraps, max
  width 380), spacer, `Add` button (`addMod.add`, height 28). Padding 6/4.
* `format_downloads(n)`: `>= 1_000_000` → `format!("{:.1}M", n/1e6)`; `>= 1_000` →
  `"{:.1}K"`; else the integer. Java uses the default locale (`1,2M` in German); use the i18n
  slice's number formatting to match.
* Add: disable the button, text `addMod.adding`; worker
  `ModrinthClient::resolve_latest_compatible_file(project_id, mc_version, loader)`:
  GET `/v2/project/{id}/version?loaders={enc(["loader"])}&game_versions={enc(["mc"])}` (each
  only when set), take the first version (newest first) that has a usable file, preferring its
  `primary: true` file else the first file with a URL. `None` → re-enable button (`addMod.add`)
  and warn `addMod.noCompatible.title` / `t!("addMod.noCompatible.body", title or slug)`.
  Otherwise create `mods_dir`, file name = `file.filename` if non-blank else the URL's last path
  segment, download to `mods_dir/name`, button text `addMod.added`, success notification
  `addMod.success.title` / `t!("addMod.success.body", name)`. Error → log
  `log.addMod.downloadFailed {slug} {msg}`, re-enable, error notification
  `addMod.fail.{title,body}`. No hash verification is done on the downloaded jar (Modrinth
  returns sha1/sha512 — recommend verifying sha512).

Keys: `addMod.{title,search.prompt,search.button,searching,noResults,resultCount,compatNote,result.meta,add,adding,added,success.title,success.body,fail.title,fail.body,noCompatible.title,noCompatible.body,vanilla.title,vanilla.body}`,
`log.addMod.{mcVersionUnresolved,downloadFailed}`.

---

## 4. Running Games window / dock (`RunningGamesWindow`)

### 4.1 Purpose

One tab per game session: launch steps while preparing, then its live log, uptime, Stop/Kill,
and how it ended. Launching no longer takes over the main window, so the library stays usable.
The view lives in its **own window** (default) or **docked** along the bottom of the main
window. Closing the window or collapsing the dock only hides it: games keep running and keep
logging. A tab can be closed only after its game has ended.

### 4.2 Constants and config

```rust
pub const DOCK_SHARE_MIN: f32 = 0.10;
pub const DOCK_SHARE_MAX: f32 = 0.90;
const DOCK_MIN_BODY: f32 = 240.0;          // unscaled px below the header when expanded
const WINDOW_SIZE: (f32, f32) = (1000.0, 720.0);  // × UI scale
const WINDOW_MIN: (f32, f32) = (640.0, 420.0);    // via ScaledMinSize (shell)
```

Config (config slice): `runningGamesDocked: bool` (default `false`),
`runningGamesDockHeight: i32` — the dock's share of the main window height in **per-mille**
(default `420` = 42 %). `consoleLogMaxLines: i32` (default `10_000`; presets
`1000, 5000, 10000, 25000, 50000, 0` where `0` = unlimited).

### 4.3 Structure

Singleton created on first use (UI thread only). State:

```text
tabs:        Vec<SessionId> + selected: Option<SessionId>
panes:       HashMap<SessionId, Entity<GameSessionPane>>
docked:      bool          // from config
expanded:    bool = true   // dock only
dock_share:  f32           // clamp_share(config per-mille / 1000)
applied_theme_key: String  // "{theme}:{os_dark}"
main_screen_root: weak handle to the main window's current screen host
```

Visual tree: `body = [header layered with resize_grip] + content`, `content = empty-label |
tab view`. `body` moves between the window host and the dock host.

**Header** (`runningGamesHeader`, spacing 8, centred): title `session.window.title`
(`runningGamesTitle`, 14 px; shown only docked — the window's own title says it otherwise), count
chip = number of tabs (`runningGamesCount`; shown only docked and when there are tabs), spacer,
`Dock in` labelled tonal button (`session.dock.dockIn`; shown only in window mode — there is no
icon for "dock into another window"), collapse/expand icon button (`ARROW_DOWN` with tooltip
`session.dock.collapse` when expanded, `ARROW_UP` + `session.dock.expand` when collapsed; dock
only), pop-out icon button (`OPEN_EXTERNAL`, `session.dock.popOut`; dock only). Pseudo-state
`docked` changes the header's styling. Docked, a primary click anywhere on the header that is
not on a button toggles expanded.

**Resize grip** (dock only, shown when docked and expanded): an 8 px strip laid **over** the top
edge of the header (so its clicks never toggle the header), centred handle 36×4 rounded, colour
`-md-outline-variant` (pressed: `-md-on-surface-variant`), vertical-resize cursor, tooltip
`session.dock.resize`.

* Press (primary): remember `drag_start_screen_y` and `drag_start_height = dock height`.
* Drag: `room = main screen host's dock_room()` (most height the screen above can give; shell's
  `ScaledRoot`); `set_dock_share(drag_share(start_height, (start_y - y) / ui_scale,
  window_height_unscaled, room))`.
* Release: save `round(dock_share * 1000)` to config.
* Double-click: share = default (0.42), save default 420.

**Pure helpers** (tested):

```rust
pub fn drag_share(start_height: f32, dragged_up: f32, window_height: f32, room: f32) -> f32 {
    if window_height <= 0.0 { return clamp_share(f32::NAN); }   // not shown yet: default
    let height = (start_height + dragged_up).min(room);  // clamp to room FIRST, else the grip
    clamp_share(height / window_height)                  // goes dead on the way back down
}
pub fn clamp_share(share: f32) -> f32 {
    if share.is_nan() { return 420.0 / 1000.0; }
    share.clamp(DOCK_SHARE_MIN, DOCK_SHARE_MAX)
}
```

**Dock sizing** (`DockHolder`): collapsed → header height only. Expanded → minimum =
header + `max(DOCK_MIN_BODY, tab_strip_height + selected_pane.min_height)` (so Stop and Kill
always show); preferred = `max(min, window_height_unscaled * dock_share)`. The shell's screen
host always grants the minimum and grants the preferred height when the content above can spare
it (content is clipped otherwise). Docked with no sessions, the dock takes **no room at all**.

### 4.4 Behaviour

* Construction: subscribe to `GameSessionRegistry` change events (registry slice; any thread) →
  re-run `sync` on the UI thread; then `sync()` and `place_body()`.
* `sync()`: for each registry session (registry order): create a tab + pane if new; refresh the
  tab label. For each known id no longer in the registry: remove the tab and **dispose** its
  pane. Show the empty label (`session.window.empty`, muted) iff there are no tabs; refresh the
  header.
* Tab label (`refreshTab`): 8 px dot (style `sessionDot sessionStatus-{phase}`: preparing =
  warning colour, running = primary, crashed/failed = danger, others = subtle text) + (if the
  session has an account UUID) a 16 px circular avatar (§10.9), then the pack name. Closable iff
  the phase is not active (`Preparing`/`Running`).
* Tab close request on an active game is refused. Closing an ended tab: forget it, dispose its
  pane, `registry.dismiss(id)`.
* Switching tabs fades the new pane in (Motion fade-through, §10.14).
* Window close request → hide the window only.
* `followScreen(screen_host)` (shell calls on every main-window screen change, after the new
  screen is set): remember it; if docked, attach the dock host to it.
* `place_body()`: docked → hide the window if showing, move body into the dock host, attach to
  the current screen host; window → detach the dock, move body into the window host. Then
  refresh the header.
* `set_docked(dock)`: no-op if unchanged; save config; `expanded = true`; place body; docked →
  focus the main window; window → reveal.
* `reveal()`: docked → expand and focus the main window; window → de-minimize, show, raise,
  focus.
* `refresh_header()`: as described in §4.3, then request relayout of the dock and its parent.

**Public API** (any thread; each hops to the UI thread):

```rust
pub fn show_session(cx: &mut App, id: SessionId);   // sync, select its tab, reveal
pub fn show_window(cx: &mut App);                   // reveal
pub fn hide_unless_preparing(cx: &mut App);         // no-op if any active session is Preparing;
                                                    // else collapse the dock / hide the window.
                                                    // Called by the launch flow when
                                                    // "Show console on launch" is off.
pub fn refresh_theme(cx: &mut App);                 // only if "{theme}:{os_dark}" changed:
                                                    // reapply theme + title-bar chrome
pub fn follow_screen(cx: &mut App, host: WeakEntity<ScreenHost>);
pub fn shutdown(cx: &mut App);                      // at exit: unsubscribe, dispose panes,
                                                    // detach dock, close window, drop singleton
```

`refresh_theme` is called on every screen switch, so it must early-out when the key is
unchanged.

### 4.5 GPUI mapping notes

* Keep the state in an `Entity<RunningGamesModel>` (global via `cx.set_global` handle). Render
  it through a `RunningGamesView` that the dock host and the separate window can each embed;
  only one host renders it at a time (`docked` decides). Pane entities are window-independent
  and can move between hosts; focus handles come from `cx.focus_handle()` and work in either
  window.
* The separate window: `cx.open_window(WindowOptions { window_bounds, titlebar, app_id, .. }, …)`
  with min size from the shell's `ScaledMinSize`. Closing (`window.on_window_should_close`)
  returns `false` and hides instead (GPUI 0.2.2 has no hide; minimize or close-and-recreate on
  reveal — keep the model alive so nothing is lost either way).
* Tabs: a simple custom tab strip (closable tabs with dot + avatar + text) is straightforward in
  plain GPUI; gpui-component's `TabBar` is not usable with gpui 0.2.2 (see §12).

---

## 5. One game's pane (`GameSessionPane`)

### 5.1 Layout

Padding 16, style `gameSessionPane`. Top / centre / bottom:

**Header** (spacing 12, bottom margin 12): optional 36 px circular avatar (account UUID present),
titles [pack name (`heading-h3`); subtitle (muted): `t!("session.subtitle.playingAs",
account_name)` or `session.subtitle.server` when there's no account], spacer, uptime label
(muted), status chip (`stat-chip sessionStatusChip sessionStatus-{phase}`, text
`session.status.{preparing|running|exited|crashed|cancelled|failed}`; preparing = warning colour,
running = primary, crashed/failed = danger).

**Centre** is a stack of two views; exactly one is visible:

* *Preparing view* (top-left aligned, height = content): heading `session.preparing.heading`,
  the step rows (spacing 10), `Cancel` button (`session.button.cancel`; disabled once the session
  is cancelled). Spacing 14.
* *Log view* (spacing 8): diagnosis card (hidden until a crash is analyzed), toolbar, the log
  (`SessionLogView`, grows), notices row.
  * Toolbar (spacing 8): search pill (§10.6; placeholder `console.search.placeholder`, no
    floating label, height 40, grows; Enter = find next), match status (muted), `Prev` icon
    button (`ARROW_UP`, tooltip `session.search.previousMatch`, text `console.search.prev`),
    `Next` icon button (`ARROW_DOWN`, `session.search.nextMatch`, `console.search.next`),
    auto-scroll filter chip (`console.autoPin.label`, selected by default), crash-report toggle
    (tonal; hidden until there is a crash report).
  * Notices: "truncated" label (subtle; `t!("console.truncationLabel", max_lines)`) + link
    `console.openLogLink.text` (opens the log file); both hidden by default.

**Footer** (spacing 8, top padding 12): `Copy` (icon `COPY`, text `console.copyBtn.label`;
copies the whole shown log), `Open log file` (text button `session.button.openLogFile`), spacer,
`Stop` (`session.button.stop` → `session.stop(force=false)`), `Kill` (error-tonal,
`session.button.kill` → `session.stop(force=true)`).

**Step row** (`StepRow`, built once per tracker, updated in place): `StepBadge` (§10.3) +
[label `step.display_label()` (i18n `launchStep.<ID>` with English fallback), detail line (muted,
wraps), wavy progress bar (max width)]. Render: detail = `error_message` if state is `Failed`,
else `sub_text`; shown only when non-blank. Bar shown only while `Running`; progress =
`step.progress` if `> 0`, else indeterminate (`-1`).

Step ids and English fallbacks (session slice owns the tracker): `MODPACK_CONTENT` "Modpack
content", `FORGE_LIBS` "Modloader libraries", `MC_LIBS_ASSETS` "Minecraft libraries & assets",
`JRE_INSTALL` "Java runtime", `FORGE_PROCESSORS` "Game patching", `SECURITY_SCAN` "Security
scan". States: `Pending`, `Running`, `Done`, `Failed`, `Skipped`.

### 5.2 Behaviour

`refresh_from_session()` (on construction and every session event; no-op after dispose):

1. Status chip text/class from the phase.
2. `bind_tracker(session.tracker())` and `bind_log(session.log())` (each a no-op if unchanged).
3. Preparing view visible iff phase = `Preparing`; log view otherwise.
4. Cancel disabled iff cancelled. Stop and Kill shown iff `Running`. Copy shown iff not
   preparing. Open-log-file shown iff the bound log has a file.
5. Uptime ticker runs (1 s) iff `Running`; refresh uptime now.
6. Phase `Crashed` and not yet diagnosed → `show_diagnosis()`.
7. Phase `Failed`/`Cancelled` with no log bound → log text `session.log.failed` /
   `session.log.cancelled`.

**Uptime**: `start = started_ms`; `<= 0` → empty. `end = now` while active, else `ended_ms`.
Text = `format_duration(max(0, end - start))`:

```rust
pub fn format_duration(ms: i64) -> String {
    let s = (ms / 1000) % 60; let m = (ms / 60_000) % 60; let h = ms / 3_600_000;
    let clock = if h > 0 { format!("{h}:{m:02}:{s:02}") } else { format!("{m}:{s:02}") };
    t!("session.uptime", clock)   // "Uptime {0}"
}
```

(The old console's MessageFormat didn't zero-pad — "5:3" — which this fixes; tests pin it.)

**Tracker binding**: on a new tracker, remove the listener from the old one, rebuild the step
rows once from `tracker.steps()` (fixed list), add a listener. Tracker events arrive off the UI
thread → forward through a channel; one UI update per frame renders every row from the tracker's
current step snapshots.

**Log binding** (`bind_log`): ignore `None` or the same log. Subscribe with
`log.subscribe(listener, max_lines)` (session slice): returns `{snapshot, cancel, clipped}`
where `snapshot` is already cut to the last `max_lines` lines (cutting a multi-megabyte capture
on the UI thread stalled it). `listener.on_lines(batch)` is called once per `GameLog` flush,
from the reader thread → channel → `append(batch)` on the UI thread. Show the snapshot
(`show_log_text(snapshot, clipped)`). If the log is already **closed and truncated** (an ended
game's in-memory buffer keeps only the tail), `load_full_log(log)`.

* `show_log_text(text, clipped)`: set the view's text; if clipped show the truncated notice; if
  auto-scroll is on, scroll to the end.
* `load_full_log(log)` (worker): `full = log.full_text()` (from the file); `drop =
  LogTrimPolicy::tail_start(full, max_lines)`; UI thread: if not disposed, still bound to the same
  log and not showing the crash report → `show_log_text(&full[drop..], drop > 0)`.
* `append(lines)`: ignored if disposed or showing the crash report;
  `dropped = log_view.append(lines, max_lines, auto_scroll)`; `dropped > 0` → show the truncated
  notice.
* Truncated notice (shown once): label `t!("console.truncationLabel", max_lines)`; link shown iff
  the log has a file.
* Turning auto-scroll back on jumps to the newest line (unless showing the crash report).

**Find**: empty needle or empty log → clear match status. `log_view.find(needle, forward)`;
`None` → `session.search.noMatches`; else `t!("session.search.matchCount", ordinal, total)` and
**turn auto-scroll off** (otherwise the next batch would yank the view away from the match).

**Open log file**: worker `open_with_system(path)` (GPUI `cx.open_with_system`); failure → log
`log.console.openLogFileFailed {msg}`.

**Crash diagnosis** (`show_diagnosis`, once): `report = session.crash_report()`; worker:
`text = report or log.full_text() or ""`; `diagnosis = CrashReportAnalyzer::analyze(text,
pack, exit_code)` (panics/errors → `None`); `log_blank = log is None or log.text().trim()
empty`. UI thread (if not disposed):

* With a diagnosis: card (`card crashDiagnosisCard` + severity class
  `crashSeverityCritical|Warning|Info`, padding 12/14, spacing 6): title (`heading-h3`), summary
  (wraps), actions row (spacing 6): each `Suggestion{label, action, primary}` → a muted label when
  `action` is `None`, else a button (primary style when `primary`) whose action runs **on a
  worker**.
* With a report: crash toggle text `console.crashReportBtn.crashReport`, shown. If the log is
  blank (the game died before logging), open on the crash report immediately.

**Crash toggle**: no report → nothing. Flip `showing_crash_report`. On → set the view to the
report, scroll to the top, button text `console.crashReportBtn.gameLog`. Off → restore
`log.tail(max_lines)` (`{text, clipped}`) or empty, then `load_full_log` again if closed and
truncated; button text back to `crashReport`.

**Dispose**: stop the ticker, unsubscribe from session, tracker and log. The game is
unaffected. In Rust, dropping the subscriptions/`Task`s does this; make subscription handles
RAII.

Keys: `session.{button.cancel,button.stop,button.kill,button.openLogFile,log.failed,log.cancelled,preparing.heading,search.noMatches,search.matchCount,search.previousMatch,search.nextMatch,status.*,subtitle.playingAs,subtitle.server,uptime,window.empty,window.title,dock.*}`,
`console.{autoPin.label,openLogLink.text,search.placeholder,search.prev,search.next,copyBtn.label,truncationLabel,crashReportBtn.crashReport,crashReportBtn.gameLog}`,
`launchStep.*`, `log.console.openLogFileFailed`. (Other `console.*` keys belong to the retired
console screen; keep them in the bundle for the MCP/TUI users if they reference them.)

---

## 6. The log view (`SessionLogView`) and `LogTrimPolicy`

### 6.1 Why it exists

It replaced a wrapping text area that re-laid out every paragraph on each appended batch (10 000+
lines, several times a second, for the whole game). Now lines live in a list; only visible rows
are laid out; rows wrap to the view width; there is **no horizontal scrolling**.

### 6.2 Behaviour

* Monospace text (bundled JetBrains Mono, §10.15), style `sessionLog` / `sessionLogText`.
* Preferred height small (120) so it takes only the space its parent leaves and never squeezes
  the crash card above it; it grows to fill.
* Row selection replaces free text selection: click, shift-click (range), ctrl/cmd-click
  (toggle). `secondary-c` copies the selected rows (sorted by index) joined with a trailing `\n`
  each; right-click menu item `console.copyBtn.label` does the same.
* Empty placeholder: nothing.
* `set_text(text)`: clear the match, replace lines with `split_lines(text)`.
* `append(added, max_lines, follow) -> dropped`: remember the first visible row if not
  following; push lines; `dropped = LogTrimPolicy::display_lines_to_drop(len, max_lines)`; drop
  that many from the front; shift the current match line by `-dropped` (clear it if it falls
  off); if following scroll to the end, else if something was dropped scroll so the reader stays
  on the same *line* (`first_visible - dropped`, floored at 0).
* `scroll_to_end()` / `scroll_to_start()`.
* `text()` = `join_lines(lines)`.
* `find(needle, forward) -> Option<(ordinal, total)>`: empty needle or no lines → clear match,
  `None`. Start point: from the current match (forward: its end column; backward: its start − 1)
  or from the start/end of the log. `find_match`; none → clear, `None`. Otherwise store the
  match `(line, start, start + needle.len())`, **clear the row selection** (a selected row's
  colour would hide the highlight), scroll to `max(0, line - 3)` (context above), return
  `match_position`.
* Rows: the current match is drawn with a highlight background (`sessionLogHighlight`) and match
  text style (`sessionLogMatch`) behind the matched range; other rows plain. Empty lines keep
  one line of height.

### 6.3 Pure helpers (exact semantics; tested)

```rust
/// Split on '\n'; a final '\n' ends the last line (no trailing empty line);
/// a '\r' immediately before '\n' (or at the very end) is dropped. None/"" → [].
pub fn split_lines(text: Option<&str>) -> Vec<String>;
// "a\nb\n"→["a","b"]; "a\r\nb"→["a","b"]; "a\n\nb\n"→["a","","b"]

/// Each line followed by '\n'. [] → "".
pub fn join_lines(lines: &[String]) -> String;

/// Next/previous case-insensitive match, wrapping. Returns (line, col).
/// from_line is taken modulo n (floor-mod). Visits n+1 steps: every line once, then the
/// starting line again for the part skipped. Forward: on step 0 search from from_col, later
/// steps from 0. Backward: on step 0 the last start ≤ from_col, later steps ≤ len(line).
pub fn find_match(lines: &[String], needle: &str, from_line: isize, from_col: isize,
                  forward: bool) -> Option<(usize, usize)>;

/// (ordinal 1-based, total). Non-overlapping matches per line (after a hit, continue at
/// hit + needle.len()). ordinal = count of matches at or before (line, col); at least 1.
pub fn match_position(lines: &[String], needle: &str, line: usize, col: usize) -> (usize, usize);
```

Case-insensitive comparison: Java's `regionMatches(ignoreCase=true)` compares per UTF-16 unit
with upper- and lower-case folding. Rust: compare per `char` using simple case folding
(`unicase`/`caseless`, or `c.to_lowercase().eq(d.to_lowercase())` for single-char mappings);
store **byte** offsets that are always on char boundaries and use `needle`'s byte length in the
matched text (compute end by walking the same number of chars, since case-folded chars can
differ in byte length). Tests use ASCII; non-ASCII logs (CJK modpacks) must not panic.

Test vectors (from `GameSessionPaneSearchTest`, with
`LOG = ["warn a", "info b", "warn c", "error d", "warn e"]`): `match_position(LOG,"warn",0,0) = (1,3)`, `(…,2,0) = (2,3)`,
`(…,4,0) = (3,3)`; `match_position(["aaaa"],"aa",0,2) = (2,2)`; `find_match(LOG,"ERROR",0,0,fwd) =
(3,0)`; forward from (0,4) → (2,0); from (4,4) wraps → (0,0); `find_match(["ab ab"],"ab",0,2,fwd)
= (0,3)`; backward from (4,-1) → (2,0); from (0,-1) wraps → (4,0); `(["ab ab"],"ab",0,2,back) =
(0,0)`; no match → `None`; `match_position` with no matches → `(1,0)`.

### 6.4 `LogTrimPolicy` (crate `mmcl-session::trim`; used by `GameLog` too)

Two independent caps: the visible view (lines, user setting) and the in-memory full-log capture
(characters). Both exist to stop long sessions from exhausting memory; there is an open report
of the launcher dying after 45+ minute sessions, and the "unlimited" setting is the branch that
can grow without bound.

```rust
pub const MIN_SLACK: i32 = 50;
pub fn is_unlimited(max_lines: i32) -> bool { max_lines <= 0 }
pub fn slack_for(max_lines: i32) -> i32 { MIN_SLACK.max(max_lines / 5) }
pub fn should_trim_display(count: i32, max_lines: i32) -> bool {
    !is_unlimited(max_lines) && count > max_lines + slack_for(max_lines)
}
pub fn display_lines_to_drop(count: i32, max_lines: i32) -> i32 {
    if should_trim_display(count, max_lines) { count - max_lines } else { 0 }
}
/// Offset just after the first `n` '\n's (stops early at the last one); 0 for None/""/n<=0;
/// never > len. "single line" (no '\n') → 0.
pub fn display_drop_offset(text: Option<&str>, n: i32) -> usize;
/// Start of the last `max_lines` lines, scanning backward (cost = tail length).
/// None/""/unlimited → 0. A final '\n' ends the last line; an unterminated last line counts.
pub fn tail_start(text: &str, max_lines: i32) -> usize;
pub fn tail_lines(text: Option<&str>, max_lines: i32) -> String; // None → ""
/// Full-log capture trim: 0 while len <= trigger; drop_to = len - retain (<=0 → 0);
/// advance to just after the next '\n' at or after drop_to; none → drop_to.
pub fn full_log_drop_offset(buf: Option<&str>, trigger: usize, retain: usize) -> usize;
```

Implement on bytes (`'\n'` is ASCII so byte scanning is safe and offsets land on char
boundaries; `full_log_drop_offset`'s fallback `drop_to` may not — round it forward to the next
char boundary).

### 6.5 GPUI implementation notes

* Use `gpui::list` with a `ListState` (variable-height, virtualized; `uniform_list` cannot wrap).
  `ListState::splice(range, count)` for append (`splice(len..len, added)`) and front trim
  (`splice(0..dropped, 0)`); `scroll_to_reveal_item(ix)`; "follow" = scroll to the last item
  after each splice (or `ListAlignment::Bottom` while following).
* Render each row with `StyledText::new(line).with_highlights(...)` (gpui 0.2.2
  `HighlightStyle { background_color, color, .. }`) for the match.
* Row heights are measured by the list from wrapped text at the current width; the list
  re-measures on width change automatically.
* Keep the lines in a `VecDeque<SharedString>`; `SharedString` avoids copies while rendering.

---

## 7. Dialogs service (`GUIUtilities`)

### 7.1 Semantics to keep

| Java | Result | Notes |
|---|---|---|
| `showQuestionMessage(title, header, content, b1, b2, owner)` | `0` cancel/closed, `1` b1, `2` b2 | Buttons: `b1`, `b2`, plus a separate Cancel (`dialog.button.cancel`, the escape/close button) — **unless** `b2.trim()` equals `"Cancel"` or the localized cancel (case-insensitive), in which case `b2` itself becomes the cancel-role button and no extra Cancel is added (prevents two Cancel buttons). Content sanitized single-line. Window-modal to `owner`. |
| `showQuestionMessageMultiline(...)` | same | Content sanitized multi-line; dialog min width 560, pref 640; content wraps at 600. |
| `showErrorMessage(content, owner)` | — | Title `dialog.alert.error.title`, header `dialog.alert.error.header`, single-line sanitize. |
| `showErrorMessageMultiline(content, owner)` | — | Multi-line sanitize, widened like above. |
| `showErrorMessageRetry(content, owner, retry_label)` | `true` iff retry | Buttons retry + Cancel. Window-modal when `owner` is showing, else **application-modal and unowned** (never force-show a hidden owner). |
| `showWarningMessage(content, owner)` | — | `dialog.alert.warning.title/header`. |

All of them are callable from **worker** threads and block that worker until answered. In Rust:
`async` methods on a `Dialogs` service that return through a oneshot; callers `.await` inside a
`cx.spawn` task. If there is no GUI this session (server mode, TUI mode) they must return the
"no answer" default **immediately** (`0` / `false` / `()`), never hang: the Java bug was a latch
that was never released when the FX body never ran, and a headless `Platform.startup()` that
blocked forever in GTK init at shutdown.

**Sanitizing** (apply to caller-supplied content):

* single-line: `None`/`""` pass through; `SensitiveDataRedactor::redact` (utilities slice:
  access/client tokens, legacy session ids, UUIDs); replace `\n` and `\r` with spaces; collapse
  whitespace runs to one space; trim; longer than **600** chars → first 599 chars + `"…"`.
* multi-line: redact; `\r\n`→`\n`, lone `\r`→`\n`; strip trailing spaces/tabs per line; collapse
  3+ consecutive `\n` to 2; trim; longer than **4000** chars → first 3999 + `"…"`.

**Chrome** (`themeAlertChrome`, applied to every dialog): enter animation (§10.14 `enter`) when
shown; current theme with solid background; UI-scale; launcher window icon; dark/light title
bar per `is_light_chrome(theme)`. Theming failures are logged silently
(`log.guiUtilities.alertChromeTheming`) and never stop the dialog.

**Interrupt fallbacks**: if the waiting thread was interrupted, Java shows an "application
error" dialog (`dialog.alert.appError.{title,header}` + `appError.question.body` /
`error.body` / `warning.body`). With async/oneshot this situation becomes "the oneshot was
dropped" → treat as cancel (0/false) and log; keep the keys.

**Helpers**:

* `open_external_url(url)`: `UrlValidation::sanitized_http_url(url)` (utilities: only
  `http`/`https`, else `None`); `None` → `log.modpack.refuseNonHttpUrl {url}` (warning) and
  return; else open in the browser off the UI thread (`cx.open_url`); failure →
  `log.modpack.openBrowserFailed {url}` + trace.
* `open_modpack_website(pack)` = `open_external_url(pack.pack_url())`.
* `open_folder(dir)`: no-op if `None` or missing; else `cx.open_with_system(dir)`.
* `is_os_dark()` (platform theme detector), `is_light_chrome(theme)` (§0.4).

### 7.2 GPUI options

* `window.prompt(PromptLevel::{Info,Warning,Critical}, message, detail, &answers) ->
  oneshot::Receiver<usize>` exists in gpui 0.2.2: native NSAlert on macOS, TaskDialog on
  Windows, and a GPUI-rendered fallback on Linux. It is **not re-entrant** ("Re-entrant window
  prompting is not supported by GPUI" panics) and is not themed.
* Recommended: a themed in-app modal (`DialogHost` overlay in each window, rendering a Material
  card with title/header/body/buttons, Esc = cancel role, Enter = first button), queued so a
  second dialog waits for the first instead of re-entering. Use `window.prompt` only as a
  fallback when no launcher window exists. **Open question** (themed vs native).

```rust
pub enum DialogLevel { Info, Question, Warning, Error }
pub struct DialogSpec { level: DialogLevel, title: SharedString, header: Option<SharedString>,
                        body: SharedString, multiline: bool, buttons: Vec<DialogButton>,
                        expandable: Option<AnyView /* e.g. diff text */> }
pub struct DialogButton { label: SharedString, role: ButtonRole /* Primary|Secondary|Cancel */ }

impl Dialogs {
    pub async fn ask(&self, title, header, body, b1, b2, owner: Option<AnyWindowHandle>) -> u8;
    pub async fn ask_multiline(&self, …) -> u8;
    pub async fn error(&self, body, owner);       pub async fn error_multiline(&self, body, owner);
    pub async fn error_retry(&self, body, owner, retry_label) -> bool;
    pub async fn warning(&self, body, owner);
    pub async fn show(&self, spec: DialogSpec, owner) -> Option<usize>;
    pub async fn input(&self, title, header, label, initial: &str, owner) -> Option<String>;
    pub async fn choose(&self, title, header, label, items: Vec<SharedString>, owner)
        -> Option<usize>;   // filterable list picker (§1.7)
}
pub fn sanitize_single_line(s: &str) -> String;   // pure, tested
pub fn sanitize_multiline(s: &str) -> String;     // pure, tested
```

Keys: `dialog.alert.{error,warning,appError}.{title,header}`,
`dialog.alert.appError.{question,error,warning}.body`, `dialog.button.{ok,cancel}`,
`log.guiUtilities.{fxTaskSkippedNoToolkit,fxToolkitBootstrap,fxTaskWaitInterrupted,alertChromeTheming}`.

---

## 8. Notifications (`NotificationManager`)

The launcher's "toasts" are **OS notifications**, not in-app snackbars. `Logger.logWarning`
shows a warning notification (title `dialog.alert.warning.title`, body = the message) whenever a
GUI window exists, then logs it; it replaced a modal alert that blocked workers and stacked
dialogs into a frozen UI. Warnings must stay fire-and-forget.

API (any thread, never blocks the caller for long):

```rust
pub fn info(title: &str, body: &str);
pub fn success(title: &str, body: &str);   // same visuals as info
pub fn warn(title: &str, body: &str);
pub fn error(title: &str, body: &str);
pub fn shutdown();                          // once at exit; idempotent
```

Behaviour:

* Windows 10/11: toast grouped under the launcher's AppUserModelID (set by the platform slice at
  start-up).
* macOS: Notification Center.
* Linux: Java's tray balloon works only on KDE/legacy trays; the fallback is
  `notify-send --app-name=<LAUNCHER_APPLICATION_NAME> --icon=mica-minecraft-launcher
  --urgency=<critical for error, else normal> <title> [<body>]` (body only when non-blank),
  output discarded, waited at most **2 s**; availability probed once via `sh -c "command -v
  notify-send"` (2 s timeout) and cached. Any failure is swallowed.
* **Always** also log `t!("log.notification.fallback", title, body.is_blank() ? "" : " — " + body)`
  as the paper trail.
* Display failures → log `log.notification.displayFailed {msg}` and use the fallback.
* The Java tray icon (left-click shows the launcher, right-click opens the shared launcher menu)
  is created lazily on the first notification and kept for the session (removing it right after
  a balloon drops the balloon on Windows). The tray itself belongs to the platform slice; this
  facade must not create a second one.

Rust: `notify-rust` covers Linux (D-Bus, no `notify-send` process needed — keep `notify-send` as
a last resort), macOS (`mac-notification-sys`) and Windows (WinRT; pass the app id). Run the
send on a background thread; serialize calls with a mutex (Java's `display` is `synchronized`).

Keys: `log.notification.{displayFailed,fallback,trayUnsupported,trayImageMissing,trayInitFailed}`.

---

## 9. Modpack detail modal and content browser (ownership to reconcile, §0.1)

### 9.1 Overlay lifecycle (`MCLauncherModpackDetailModal`)

In-window modal overlay over the main screen: full-window dim backdrop
(`modpackDetailBackdrop`) + centred card (`modpackDetailCard`). Card size = window ×
**0.90** in each axis, clamped to min **600×480** and max **1400×1000**. Starts hidden,
unmanaged, opacity 0 (avoids a one-frame full-opacity flash on the first show).

Dismiss by: click on the backdrop itself (clicks inside the card are consumed), the ✕ close
button, Esc (only while shown; installed on show, removed on hide), or Play.

`show(pack)`: rebuild the cheap skeleton synchronously (hero, header meta, tab bar, empty body
scroll, action row); mark shown; kick `RgbIntegration::on_menu(pack)` on a worker (SDK calls are
slow); if not already shown, fade the overlay 0→1 over `Motion::SHORT` (1 ms when reduced) and
play `Motion::enter(card)`, else set opacity 1; focus the card; after a **32 ms** pause (so the
empty skeleton paints first) select the Overview tab. `hide()`: unshown; drop the hero cycle
subscription; `RgbIntegration::on_menu()` (no pack); remove Esc; `Motion::exit(card)` + fade to 0,
then hide and clear the card. `dispose()` (owner teardown): unshown + drop the cycle
subscription, no animation. A `pack_generation` counter bumps on every show; deferred work for an
older generation abandons itself; every async section checks "still shown".

### 9.2 Structure

1. **Hero** (height = card height × 0.36 clamped to **172..320**): pack background (or
   procedural gradient), darkening veil, badges (Beta / Recently updated), logo (96 px box) +
   name + last-played subtitle, optional ◀ ▶ navigation (bottom-anchored, 28 px above the hero's
   bottom) when the pack has several showcase logos/backgrounds, close button added last
   (z-order). Multi-image cycling uses the shared `ModpackImageCycleClock` (library slice) with
   one index shared by logo and background, wrapping modulo the longer list; manual steps via
   arrows or clicking the logo; uncached showcase images are prefetched once per show. Colours are
   derived from the art (`PackColorScheme`, library slice) and re-applied when the theme surface
   changes.
2. **Header meta** (padding 14/24/0/24): chips row — "plays as" (`modal.chip.playsAs {name}` or
   `modal.chip.playsAsMissing` when the per-pack account override no longer exists), Vanilla
   (`modal.chip.vanilla`), `modal.chip.minecraft {mc}`, `"{loader} {short}"` where short = text
   after the last `-` of the loader version, `modal.chip.packVersion {v}` (omitted when equal to
   the MC version), `modal.chip.minimumRam {n}` (`%.0f` if integral else `%.1f`; skipped when
   unset/≤0), `modal.chip.modCount {n}` (>0). Then, if the audit found problems, a one-line
   problems banner that opens the Problems tab.
3. **Tab bar** (segmented, connected group): Overview (always), Problems (only with problems;
   count badge), Content and Activity (only with an install folder), Advanced (only with a
   settings key). Labels `detailModal.tab.{overview,content,activity,advanced}`,
   `detailModal.section.problems`. Each tab body is built lazily on first activation, cached,
   streamed in **3 sections per frame**, scrolled to top on switch. Empty tab →
   `detailModal.tab.empty`.
   * Overview: News (pack's visible news), Links, Quick actions, Stats.
   * Content: Worlds, Servers, Mods, Screenshots, Shader packs, Resource packs (§9.6).
   * Activity: Update log (last **12** entries + "+N more"), Crash history.
   * Advanced: §9.4. Problems: the audit problem list.
4. **Body** scroll (smooth scroll, vertical only).
5. **Action row** (padding 14/24/18/24, spacing 10, buttons 42 high): `Play`
   (`common.button.play`, primary, `PLAY` glyph, grows; disabled when announcements disable
   gameplay) → hide, then if the pack already has an active session show it in Running Games,
   else remember it as last selected and launch on a worker; `Website` (`common.button.website`;
   disabled when the pack URL is blank).

### 9.3 Quick actions and export

Reflowing grid (gap 8): Open folder; (modded only) Mods, Config; Screenshots, Resource packs,
Shaders — each opens `<packRoot>/<sub>` in the file browser, creating it if missing (worker;
failure logs `log.modpack.openFolderFailed`); Create desktop shortcut (platform slice; success
info dialog `dialog.shortcut.created.{title,body}`, failure error dialog
`dialog.shortcut.failed.body`); Export pack (below); Copy invite link (disabled with tooltip
`tooltip.modal.invite` when `DiscordRpcUtility::build_invite_link_from_pack` is `None`; copies
the `mmcl://` link, notification `notification.invite.copied.*`).

**Smart export**: `ModpackExporter::classify_export(pack)` → `ShareUrl | ShareManifestJson |
ExportZip`. Dialog (title `detailModal.exportPack.label`, header = friendly name) with body
`modal.share.prompt` + `\n\n` + `modal.share.recommend.{url|manifest|zip}`; buttons: Share URL
(only for ShareUrl), Share manifest JSON (ShareUrl or ShareManifestJson), Export ZIP (always),
Cancel.

* Share URL: copy `pack.manifest_url()`; blank → error notification
  `notification.detail.shareUrlFailed.*`; else success `notification.detail.urlCopied.*`.
* Share manifest JSON: `ModpackExporter::load_manifest_text(pack)`; blank → error
  `detailModal.manifest.couldNotRead.*`; save dialog `dialog.fileChooser.saveManifest.title`,
  default `<packName or "modpack">-manifest.json`, filter `dialog.fileChooser.json.filter` →
  `*.json`; write UTF-8; success `detailModal.manifest.saved.title` + path; failure error
  `detailModal.manifest.couldNotSave.title` + message.
* Export ZIP: confirm `detailModal.exportPack.{dialogTitle,dialogPrompt}` with an
  "include worlds" checkbox (`detailModal.exportPack.includeWorlds`); save dialog default
  `<packName or "modpack">-<epoch ms>.zip`, filter `dialog.fileChooser.zipArchive.filter`;
  notification `detailModal.exportPack.starting`; modded packs are **verified first** (so a
  never-launched pack isn't exported sparse; info `detailModal.exportPack.verifying`) then
  zipped; vanilla zips directly; success/failure notifications
  `detailModal.exportPack.{success,successBody,failed,failedBody}`.

**Stats** (spinner placeholder `detailModal.section.loading`, data gathered on a worker): last
played, total play time, launches, installed version (annotated
`detailModal.stats.installedDisplay.withUpdate {installed} {remote}` when an update is
available), manifest URL. Keys `detailModal.stats.*`.

### 9.4 Advanced tab

* Hint `modal.advanced.hint`.
* **Launch as**: combo of `""` (= default account, label `modal.advanced.account.default {default
  name}`) + every signed-in account (label = name, or `account.switcher.needsSignIn {name}`) +
  the stored override if it no longer exists (`modal.advanced.account.missing`). Selecting writes
  `ConfigManager::set_account_override_for_pack(settings_key, None for "" else uuid)`. Label
  `modal.advanced.account.label`, hint `modal.advanced.account.hint`.
* Only with a manifest URL:
  * Switch "always verify on launch" (`modal.advanced.alwaysVerify` + hint), stored per manifest
    URL, default off.
  * Scan frequency combo: `modal.advanced.scanFreq.useGlobal` (= no override) + every
    `ScanFrequency` display label; stored per manifest URL. Label/hint keys
    `modal.advanced.scanFreq.{label,hint}`.
  * Read-only list of the manifest's scan acknowledgements (`modal.ack.heading`; rows: rule kind,
    locator, reason; file SHA-256 in a tooltip only).
  * `Verify now` (`modal.advanced.verifyNow` + hint): hide, run a forced full verify.
  * With an install folder: `Add to official launcher` (`officialExport.menuItem`, hint
    `modal.advanced.addOfficial.hint`) or, when a Mica-owned profile already exists,
    `Remove from official launcher` (`officialExport.remove.menuItem`,
    `modal.advanced.removeOfficial.hint`) — exactly one is shown; both hide the modal first.

### 9.5 Section box

Each section is a collapsible box with a heading; some register a "populate on first expand"
action (`register_on_first_expand`) so their contents (and any disk scan) are built only when the
user opens them.

### 9.6 Content browser (`ModpackContentBrowser`)

Sections are built quickly (directory listing) and populate asynchronously behind a loading
placeholder; file actions (open, delete) run on workers.

* **Mods** (collapsed, populated on first expand): files in `<packRoot>/mods` that are files
  ending (case-insensitive) in `.jar` or `.jar.disabled`, sorted by lower-cased name (disabled
  sorts next to its jar). Header: `Check for updates` (`detailModal.mods.checkUpdates`) + status;
  for modded packs a spacer and `Add mod` (`detailModal.mods.addMod`, opens §3).
  * Row: name (for `.jar.disabled` the name without `.disabled`; dimmed when disabled), meta
    (`detailModal.mods.disabled` or human size), [update label for enabled jars], toggle button
    `detailModal.mods.enable|disable`.
  * Toggle (button disabled during the operation): rename `x.jar` ⇄ `x.jar.disabled` with an
    **atomic** move; if the target exists → warn `notification.content.toggleModConflict.*
    {name}` and do nothing; failure (file locked by a running game) → warn
    `notification.content.toggleModFailed.*`, log `log.contentBrowser.modToggleFailed`.
  * Check updates (worker): `ModrinthModUpdateChecker::scan(mods_dir)` (modpack slice: hashes
    each enabled jar and queries Modrinth's `version_file` endpoint) → per file
    `{status: UpdateAvailable|UpToDate|NotOnModrinth, current_version, latest_version,
    latest_download_url}`. Per row: `detailModal.mods.updateAvailable {cur or ?} {latest or
    ?}` + splice an `Update` button (`detailModal.mods.update`, inserted before the toggle,
    idempotent), `detailModal.mods.upToDate`, `detailModal.mods.notOnModrinth`. Summary
    `detailModal.mods.checkSummary {onModrinth} {total} {updates}`; failure
    `detailModal.mods.checkFailed`.
  * Apply update (worker; label `detailModal.mods.updating`): new name = URL-decoded last path
    segment of the download URL, or the old name when blank / not `.jar`; download to
    `mods/.{new}.update.tmp`; move over `mods/{new}` (replace); delete the old jar if the name
    changed; UI: `detailModal.mods.updated {v}`, remove the Update button, **disable the
    toggle** (it still points at the old file name). Failure: `detailModal.mods.updateFailed`,
    re-enable, warn `notification.content.modUpdateFailed.* {old}`, old jar kept.
* **Worlds** (`saves/`), **Servers** (the pack's `servers.dat` favourites plus the manifest's
  default server; connect action), **Screenshots** (thumbnail grid, 100 px tiles, gap 8; click
  opens an in-window image viewer overlay), **Shader packs** / **Resource packs** (entries with
  size, open/delete), **Crash history** (`crash-reports/`; row opens an in-window crash viewer
  that reads the report text). Delete asks for confirmation and rebuilds the section. Directory
  sizes are computed on workers.

---

## 10. Reusable components (crate `mmcl-ui-kit`)

### 10.1 `LoadingIndicator` (Material 3 Expressive loading indicator)

A filled shape that turns while morphing through 7 shapes, each change landing with an
overshoot. Default 32×32 (fixed). Fill `-md-primary`.

```rust
pub const SHAPES: [(f32, f32); 7] = [(9.,0.09),(5.,0.13),(4.,0.14),(12.,0.05),(2.,0.16),(6.,0.10),(8.,0.07)]; // (lobes, depth)
const MORPH_S: f64 = 0.65; const TURN_S: f64 = 1.6; const SAMPLES: usize = 96;

fn shape_radius((lobes, depth): (f32,f32), t: f64) -> f64 { (1.0 + depth*(lobes*t).cos()) / (1.0 + depth) }
fn ease_out_back(p: f64) -> f64 { let c1 = 1.4; let c3 = c1 + 1.0; 1.0 + c3*(p-1.0).powi(3) + c1*(p-1.0).powi(2) }

/// Outline at time `s` seconds, centre (cx,cy), outer radius r.
fn outline(s: f64, cx: f64, cy: f64, r: f64) -> [(f64,f64); SAMPLES] {
    let cycle = s / MORPH_S; let i = cycle.floor() as i64; let p = cycle - i as f64;
    let from = SHAPES[i.rem_euclid(7) as usize]; let to = SHAPES[(i+1).rem_euclid(7) as usize];
    let e = ease_out_back(p);
    let rot = s / TURN_S * TAU + (i as f64 + e) * FRAC_PI_2;  // steady turn + quarter turn per morph
    // point k: t = k*TAU/SAMPLES; rr = r*(shape_radius(from,t)*(1-e) + shape_radius(to,t)*e);
    //          (cx + cos(t+rot)*rr, cy + sin(t+rot)*rr); closed polygon
}
```

Size = `min(width, height)`, radius = size/2, centre = bounds centre. **Reduce motion**: clock
fixed at 0 (one shape, no morph) and the whole shape rotates `(s * 90) % 360` degrees — still
visibly busy. Animates only while visible (§10.13). GPUI: `canvas(prepaint, paint)` +
`PathBuilder::fill()` polygon + `window.paint_path(path, color)`; `window.request_animation_frame()`
each frame while drawn. Test `loadingShapesStayInsideTheirRadiusAndMorphSmoothly`.

### 10.2 `WavyProgressBar` (Material 3 Expressive wavy linear indicator)

Constants: `STROKE = 4`, `AMPLITUDE = 3`, `WAVELENGTH = 28`, `GAP = 4`, wave period `1.2 s`,
indeterminate sweep period `1.8 s` covering `0.38` of the width. Height fixed at
`2*AMPLITUDE + STROKE + 2 = 12`; preferred width 240. Round line caps. Colours: wave
`-md-primary`, track `-md-active-indicator`, stop dot `-md-primary`.

Global switch `set_wavy_enabled(bool)` (Settings → Appearance; off draws flat, keeping gap and
stop dot) — a shared observable so bars on screen restyle immediately. Progress: `0..1`, or
negative = indeterminate.

Per frame (time `s`, inner width `w`, x0 = left inset, `cy` = vertical centre):

* `phase = reduced ? 0 : (s % 1.2) / 1.2 * TAU`; `amp = wavy ? 3 : 0`.
* Wave polyline from `from` to `to`: step 2 px, `x` from `from` to `to + 0.001`,
  `xx = min(x, to)`, `ramp = min(1, (xx-from)/WAVELENGTH)` (a short wave grows from flat),
  `y = cy + sin(xx/WAVELENGTH*TAU - phase) * amp * ramp`.
* Indeterminate: `t = (s % 1.8)/1.8; len = w*0.38; start = -len + (w+len)*t; a = max(0,start);
  b = min(w, start+len)`; wave from `x0+a` to `x0+b` if `b > a`; left track `x0+STROKE/2 →
  x0+a-GAP` if `a-GAP > STROKE`; right track `x0+b+GAP → x0+w-STROKE/2` if `w-(b+GAP) >
  STROKE`; no stop dot. The sweep keeps moving under reduce motion (it is the "working" signal).
* Determinate: ease the drawn value: `shown += (min(1,target) - shown) * 0.2`, snap when within
  0.001; `end = x0 + STROKE/2 + (w-STROKE)*shown`; wave `x0+STROKE/2 → end` if `shown > 0`;
  track from `shown > 0 ? end + GAP + STROKE : x0 + STROKE/2` to `x0 + w - STROKE/2` when
  positive length; stop dot (radius STROKE/2) at the track end, visible while `shown < 0.999`.
* The wave phase comes from the clock, not the bar, so a rebuilt bar keeps moving smoothly.
* Initial `shown = max(0, initial)`.

GPUI: `canvas` + `PathBuilder::stroke(px(4.))` (round caps) for wave/track, circle for the dot.
Test `waveStartsFlatStaysInBoundsAndEndsAtTheEnd`, `zeroAmplitudeIsFlat`.

### 10.3 `StepBadge`

22×22 circle with a glyph drawn at 14 px (24 px artwork scaled 14/24). By state: Pending =
outlined ring (`-md-outline`, 1.5 px), no glyph; Running = `-md-active-indicator` disc with
`DOT` (`-md-on-active-indicator`); Done = `-md-primary` with `CHECK` (`-md-on-primary`); Failed =
`-md-error` with `CLOSE` (`-md-on-error`); Skipped = `-md-surface-container-highest` with
`REMOVE` (`-md-on-surface-variant`). `glyph_for(state)` is pure (test: every state but Pending
has a glyph).

### 10.4 `FilterChip`

Toggle chip: outlined when off; filled with the active-indicator tone and a leading 16 px
`CHECK` (24→16 scale, gap 2) when on. Exposes `selected` + on-change.

### 10.5 `IconButtons::decorate(button, icon, label)`

Turns a button into a 40 px round icon button (`iconBtn`: no container, hover/press state
layer) showing one glyph; `label` (or the button's existing text) becomes the tooltip and the
accessible label; the visible text is cleared. Rust: `IconButton::new(id, Icon::X).tooltip(label)`
with an accessibility label (GPUI 0.2.2 accessibility is limited; set `.id()` and keep the label
for the future).

### 10.6 `SearchFields::decorate(field)`

Full-pill search field (`searchPill`) with a leading 18 px `SEARCH` glyph, darker than the
surrounding surface.

### 10.7 `TooltipManager`

Every tooltip: style `mcl-tooltip`, show delay **400 ms**, visible **10 s**, wraps, max width
**300**. GPUI: `.tooltip(|window, cx| Tooltip::new(text))` on elements with an id; implement a
`LauncherTooltip` view honouring the delay/width.

### 10.8 `EmptyStates::badge(icon)`

72×72 tonal badge (`-md-surface-container-high`, radius 28) with a glyph, above an empty
screen's heading (Library, Browse, Runtime management).

### 10.9 `AvatarImages`

`url(uuid) = "https://minotar.net/armor/bust/{uuid}/100.png"`. `get(uuid)`: blank → `None`;
process-wide cache keyed by uuid; returns the cached image unless it is in an error state, in
which case a new load starts (failed loads are retried on the next request, never cached).
Loads in the background. Rust: `HashMap<String, AvatarState{Loading, Ready(Arc<RenderImage>),
Failed}>` in a global; `Failed` triggers a refetch on the next `get`; HTTP through the net
facade (generic User-Agent, no personal identifiers). Drawn circular-clipped (16 px in tabs, 36
px in the pane header; `rounded_full()` + `overflow_hidden()` in GPUI).

### 10.10 `AnnouncementBanners::show(banner, row, text)`

Text non-blank → banner visible with the text, its row exactly **40 px**; else text cleared,
banner hidden, row **0 px**.

### 10.11 `OfflineIndicator::apply_to(label)`

Visible iff `NetworkUtilities::is_offline()` (net slice), tooltip `tooltip.offline`. Java
checks once per screen show; Rust should observe the net slice's offline state and update live.

### 10.12 `WindowIcons`

Every launcher window and dialog gets the launcher icon (`micaminecraftlauncher.png`), decoded
once and shared; load failure logged once (`log.guiWindow.setIconFailed`) and not retried. GPUI
0.2.2 has no per-window icon API on all platforms: Windows takes it from the executable's
resource (`winres`/`embed-resource` in `build.rs`), macOS from the bundle, Linux from the
`.desktop` file + `app_id` (`WindowOptions.app_id`). The platform/packaging slice owns this;
this slice only requires that secondary windows set the same `app_id`.

### 10.13 `AnimationGate`

Runs an animation only while its element can be seen: the element and every ancestor visible,
in a window that is showing. Java needed weak listeners on the whole ancestor chain because a
`AnimationTimer` kept running (and leaked whole screens) after a scene swap. In GPUI, an element
that isn't rendered isn't painted, so the rule becomes: **call `window.request_animation_frame()`
only from inside `paint`/`render` of a visible animated element**, never from a free-running
task. Additionally skip requesting frames when the window is minimized/inactive if the platform
slice exposes that. Port `AnimationGateFxTest` as a test that an unrendered indicator schedules
no frames.

### 10.14 `Motion`

Easing tokens (cubic Béziers): `STANDARD (0.2, 0, 0, 1)`, `EMPHASIZED_DECELERATE (0.05, 0.7,
0.1, 1)`, `EMPHASIZED_ACCELERATE (0.3, 0, 0.8, 0.15)`. Durations: `SHORT 150 ms`, `MEDIUM 300 ms`,
`LONG 450 ms`. Global `set_reduce_motion(bool)` / `is_reduced()` (Settings → Appearance).

* `enter(el)`: opacity 0→1 and scale 0.96→1 over MEDIUM, emphasized-decelerate. Reduced → snap
  to rest.
* `exit(el, done)`: opacity →0 and scale →0.96 over SHORT, emphasized-accelerate, then reset and
  call `done`. Reduced → call `done` immediately.
* `fade_through(el)`: opacity 0→1 and translate-y 8→0 px over **220 ms**,
  emphasized-decelerate. Reduced → snap.

GPUI: `element.with_animation(id, Animation::new(dur).with_easing(cubic_bezier(..)), |el, t|
el.opacity(t)…)`. GPUI 0.2.2 has no element scale transform; approximate `enter`'s scale with a
small size/inset change or opacity-only, and note it. Write a `cubic_bezier(x1,y1,x2,y2) -> impl
Fn(f32)->f32` (Newton–Raphson solve), since GPUI ships only `ease_in_out`, `ease_out_quint`,
`bounce`, etc.

### 10.15 `BundledFonts`

Register once at start-up, before any text renders: `fonts/inter/Inter-Regular.ttf`,
`Inter-Italic.ttf`, `Inter-SemiBold.ttf`, `Inter-Bold.ttf`, `fonts/jetbrains-mono/JetBrainsMono-Regular.ttf`
(both SIL OFL; ship the `OFL.txt` files with the app). Failures per face are logged
(`log.bundledFonts.notLoaded {face}`, `log.bundledFonts.loading {face}`) and skipped. GPUI:
`cx.text_system().add_fonts(vec![Cow::Borrowed(include_bytes!(…)), …])`; then use family
`"Inter"` with `FontWeight::NORMAL / SEMIBOLD / BOLD` (GPUI resolves weights within the family,
unlike JavaFX which needed the separate "Inter SemiBold" family) and `"JetBrains Mono"` for logs.
Only weights 400/600/700 are used (TypeScaleTest).

### 10.16 `LauncherIcons`

38 icons, SVG path data in a 24×24 box, generated by `tools/launcher-icons/build_icons.py`
(Material Symbols Rounded filled, Apache-2.0, plus custom Minecraft glyphs): `ACCOUNT`, `GAME`,
`APPEARANCE`, `ADVANCED`, `NETWORK`, `SECURITY`, `SYSTEM`, `DISCORD`, `RGB`, `ABOUT`, `REFRESH`,
`DOWNLOAD`, `CHECK`, `CLOSE`, `REMOVE`, `DOT`, `LOCK`, `PEOPLE`, `SEARCH`, `WARNING`, `PLAY`,
`PREVIOUS`, `NEXT`, `ARROW_UP`, `ARROW_DOWN`, `COPY`, `STOP`, `EXPAND`, `ANNOUNCEMENT`, `DELETE`,
`HELP`, `BROWSE`, `OPEN_EXTERNAL`, `EMPTY_BOX`, `RUNTIME`. GPUI's `svg()` element loads SVG
**files** through the app's `AssetSource` and tints them with `text_color`. Port: a `build.rs`
(or a one-off script checked in) that wraps each path string from `LauncherIcons.java` into
`<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><path d="…"/></svg>` and embeds
them (`rust-embed` or `include_bytes!`) under `icons/<snake_name>.svg`; expose
`enum Icon { … }` with `fn path(self) -> &'static str`. Keep `build_icons.py` as the single
source; add a Rust output target to it rather than hand-editing.

### 10.17 Others

* `MaterialSwitchSkin`: Material 3 switch at 3/4 of mobile size (**39×24** vs 52×32): off =
  outlined track, handle with an X; on = filled primary track, handle with a check; handle same
  size in both states, slides with emphasized easing, icon cross-fades, handle grows while
  pressed; label on the left, switch at the right edge; click, Space and Enter toggle. Build as a
  `Switch` component in `mmcl-ui-kit` (gpui-component's `Switch` is not usable, §12).
* `ImageFadeIn`: images that load in the background fade from opacity 0 to 1 when the bytes
  arrive (immediately if already loaded). GPUI: `img(source).with_loading(..)` plus a short
  opacity animation keyed on the image's ready state.
* `SmoothScroll`: wheel events accumulate into a target offset (clamped), and each frame moves
  the actual offset a fraction of the remaining distance (exponential approach) until it is
  within epsilon, then stops. GPUI scroll handles trackpads precisely; for line-step mouse
  wheels add the same eased approach on top of `ScrollHandle` (optional; parity nicety).
* `ShapeScale` radii: `EXTRA_SMALL 4`, `SMALL 8`, `MEDIUM 12`, `LARGE 16`, `EXTRA_LARGE 28`,
  full = 999 (pill). Use only these.

---

## 11. Other windows

### 11.1 Quick-start wizard (`MCLauncherQuickStartWizard`)

Shown once on first launch (config `quickStartCompleted`, default false). Window-modal to the
main window, title `window.welcome.title`, unified/borderless-title style, **620×540**, not
resizable, launcher icon, current theme, title-bar chrome.

Navigation bar (padding 12/18/14/18, spacing 8): progress `t!("quickStart.progressIndicator",
step+1, 5)` ("Step {0} of {1}"), spacer, `Skip` (`quickStart.skipBtn`, link style), `Back`
(`quickStart.backBtn`, disabled on step 0), `Next` (`quickStart.nextBtn.next`; on the last step
`quickStart.nextBtn.getStarted`, which finishes). All steps are built up front and toggled, so
Back keeps each step's state. Step panel: padding 28/36/24/36, spacing 12.

`finish()` (Get Started or Skip) and closing the window both set `quickStartCompleted = true`.
Settings are written as the user changes them, so closing mid-way keeps partial choices.

1. **Welcome**: `quickStart.welcome.{heading,subtitle}`, 18 px gap, `quickStart.welcome.hint`.
2. **Theme**: heading/subtitle `quickStart.theme.*`; a wrapping grid (gap 10) of mutually
   exclusive toggle buttons (170 px wide), label `SettingsOptionLabels::theme(id)` (settings
   slice), with a 14×14 rounded swatch: Native `#6FCF3D`, Dark `#6FCF3D`, Light `#3C8527`,
   Blue+gray `#0668E1`, Orange+purple `#D257DB`, Creeper `#43D22D`, Automatic `#888888` (in that
   order). The current theme starts selected; deselecting is refused (one is always selected).
   Picking one saves `theme`, refreshes the main window's theme and re-themes the wizard live.
3. **RAM**: heading/subtitle `quickStart.ram.*`. `system_gb = max(1, total_bytes / 2^30)`
   (floor; detection failure → 8, log `log.quickStart.ramDetectFailed {msg}`);
   `recommended = max(2, min(16, system_gb / 2))`. Line `t!("quickStart.ram.recommendation",
   system_gb, recommended, 16)`. Slider 2..64 GB, tick labels every 4, 3 minor ticks (= every
   1 GB), snap to ticks, keyboard step 1, width 460. Initial = `recommended` if the stored
   `maxRam` equals the default **2048 MB**, else `clamp(maxRam_mb / 1024, 2, 64)`. Value label
   `t!("quickStart.ram.value", gb)`. Save `maxRam = round(value) * 1024` MB **only when the user
   moves the slider** (Java once wrote it at build time, overwriting the user's RAM just by
   opening the wizard).
4. **Preferences**: `quickStart.preferences.{heading,subtitle}`; switch
   `quickStart.preferences.discord` (config `discordRpcEnable`) + hint `discordHint`; switch
   `quickStart.preferences.updates` (`launcherUpdateCheckEnabled`) + hint `updatesHint`; each
   saved on change only.
5. **Done**: `quickStart.done.{heading,subtitle,recap}`.

System RAM in Rust: `sysinfo::System::new_with_specifics(RefreshKind::nothing().with_memory(..))
.total_memory()` (bytes).

### 11.2 Import confirmation (`MCLauncherImportConfirmDialog`)

`async fn show(owner, pack_name, pack_version, index: &ModrinthIndex) -> bool` — window-modal,
utility style, title `dialog.importConfirm.title`, **600×520**, padding 16, spacing 8. True only
on Import.

* Title (`heading-h2`): pack name, or `dialog.importConfirm.fallbackName` when blank.
* Subtitle (subtle), parts joined by `" · "`: `importConfirm.meta.version {v}` (if non-blank),
  `importConfirm.meta.minecraft {deps.minecraft}`, `importConfirm.meta.forge {deps.forge}`
  (each if present).
* Counting over `index.files` (skip entries with no path): path lower-cased, `\` → `/`; prefix
  `mods/` → mods, `config/` → configs, `resourcepacks/`, `shaderpacks/`, else other; sum
  `file_size` over all.
* Totals line: `t!("importConfirm.totals.mods", mods)`, then for each non-zero of configs /
  resourcePacks / shaderPacks / otherFiles `t!("importConfirm.totals.join", list,
  t!(key, n))`; if total bytes > 0 → `t!("importConfirm.totals.withSize", list,
  t!("importConfirm.totals.size", UnitFormatter::bytes(total)))`. Count keys are `choice`
  patterns (`{0,choice,0#0 mods|1#1 mod|1<{0} mods}`); join and size separators are keys too
  because they differ by script.
* Mod list (scrolls; height 280, max 360, no horizontal scroll; padding 4/8, spacing 2): one
  monospace row per `mods/` entry: `"[{size}]  {basename}"` (size omitted when ≤ 0) — basename
  = text after the last `/` (after `\`→`/`). None → `importConfirm.empty`.
* Note `importConfirm.note` (wraps). Footer right-aligned: `Cancel` (`common.button.cancel`),
  `Import` (`common.button.import`, primary).

### 11.3 Add account (`AddAccountDialog`)

Window-modal over the current window, title `account.add.title`, **1000×800**, min 860×680,
padding 20, `hero-surface` background. Embeds the login slice's `SignInPanel` with
(`account.add.title`, `account.add.hint`, dismiss = `dialog.button.cancel`); dismiss closes.
When hidden, load `about:blank` into the web view (nothing keeps running). Sign-in callback:
user cancelled → close; no code → log `log.login.msLoginError {err}` and, if still showing,
`fail_and_retry("signIn.failed")`; code → `show_redeeming()`, `remember = panel.remember()`,
worker `MCLauncherAuthManager::add_account_with_microsoft(code, remember)` →
`ok = AuthUtilities::check_auth_response(result)`; UI: ok → close + success notification
`account.add.done.title` / `t!("account.add.done.body", name)`; failure and still showing →
retry with `signIn.notOwned` when the result is `ErrorNotOwned`, else `signIn.failed` (if the
window was closed mid-redeem, do **not** retry: it would reload the sign-in page into a hidden
web view). Adds the account alongside others; the default account changes only if there was
none; also used for "Sign in again" on an account whose credentials Microsoft rejected.

**GPUI has no web view.** The login slice must decide between an embedded `wry` web view in a
child window (wry is Tauri's web view crate but usable standalone — the user ruled out Tauri,
not necessarily wry), the system browser with a loopback redirect, or the device-code flow. Open
question; this dialog adapts to whatever `SignInPanel` becomes.

### 11.4 Quit / restart confirmations

* `confirm_quit_while_games_run() -> bool` (async; every quit path: close button, Exit, tray,
  dock menu, Cmd+Q): no active sessions → true. Else ask `session.quit.title`,
  `t!("session.quit.header", active)` ("Games still running: {0}"), body `session.quit.body`,
  buttons `session.quit.leaveRunning` (1) / `session.quit.stopGames` (2) + Cancel. 0 → false
  (keep the launcher open). 2 → for each active session `cancel()` then `stop(false)`. Return
  true. (Leaving them running means their logs stop being saved.)
* `confirm_restart_while_games_run() -> bool` (signing out of the last account restarts the
  launcher in-process): no active sessions → true; else `session.restart.{title,header(count),
  body}`, `session.restart.confirm` / `dialog.button.cancel`; true iff answer 1.
* `request_quit()`: on a task, `if confirm_quit…() { close_app() }`.

---

## 12. Class-by-class map

| Java | Rust (crate::module::Type) | Key methods |
|---|---|---|
| `MCLauncherModPackEditorGui` | `mmcl_editor::screen::ModpackEditor` (GPUI view) | `new(initial: Option<PackRef>)`, `new_document`, `open_from_file`, `open_from_url`, `load_from_pack`, `save_to_file`, `is_dirty`, `confirm_navigate_away`, `bump_version`, `validate`, `show_diff`, `pick_loader_version`, `search_modrinth`, `update_status` |
| (inner) file-list tab | `mmcl_editor::file_table::FileListTable` | `rows`, `view_order` (filter+sort), `add_row`, `remove_selected`, `check_urls`, `calc_hash(row)` |
| `ModPackEditorFileEntry` | `mmcl_editor::row::EditorRow` + `UrlCheck` | `from_entry`, `to_entry` |
| Forge/NeoForge/Fabric pickers | `mmcl_editor::loaders` | `fetch_forge_entries(prefilter)`, `fetch_neoforge_entries()`, `fetch_fabric_entries()`, `neoforge_mc_version`, `installer_sha1(url)` |
| Modrinth search (inline) | `mmcl_editor::modrinth_search::ModrinthSearchDialog` + `IconCache` | `search`, `resolve_selected` |
| diff (inline) | `mmcl_editor::diff::positional_diff(orig, curr) -> String` | pure |
| `ModPackDocument`, `FileList` | `mmcl_modpack::document::{ModPackDocument, FileList}` | §2.12 |
| `ModPackFileEntry` | `mmcl_modpack::document::ModPackFileEntry` | §2.12 |
| `ModrinthAddModDialog` | `mmcl_app::dialogs::add_mod::AddModWindow` (or `mmcl_library` if the detail modal lives there) | `open(pack, mods_dir)`, `format_downloads` |
| `RunningGamesWindow` | `mmcl_running_games::{RunningGamesModel, RunningGamesView, DockHost}` | `show_session`, `show_window`, `hide_unless_preparing`, `refresh_theme`, `follow_screen`, `shutdown`, `drag_share`, `clamp_share` |
| `GameSessionPane` | `mmcl_running_games::pane::GameSessionPane` | `new(session)`, `refresh_from_session`, `find`, `toggle_crash_report`, `format_duration` |
| `GameSessionPane.StepRow` | `mmcl_running_games::pane::StepRow` (render fn) | `render(step)` |
| `SessionLogView` | `mmcl_running_games::log_view::SessionLogView` | `set_text`, `append`, `find`, `text`, `scroll_to_end/start`; pure `split_lines`, `join_lines`, `find_match`, `match_position` |
| `LogTrimPolicy` | `mmcl_session::trim` (free fns) | §6.4 |
| `GUIUtilities` | `mmcl_ui_kit::dialogs::Dialogs` + `mmcl_ui_kit::sys::{open_external_url, open_folder, is_light_chrome}` | §7 |
| `NotificationManager` | `mmcl_ui_kit::notify` (or `mmcl_platform::notify`) | `info/success/warn/error/shutdown` |
| `MCLauncherQuickStartWizard` | `mmcl_app::dialogs::quick_start::QuickStartWizard` | `show`, `recommended_ram_gb`, `initial_ram_gb` |
| `MCLauncherImportConfirmDialog` | `mmcl_app::dialogs::import_confirm` | `show(...) -> bool`, `totals_text`, `basename` |
| `AddAccountDialog` | `mmcl_app::dialogs::add_account` | `show(owner)` |
| `MCLauncherGuiController.confirmQuit…/confirmRestart…` | `mmcl_app::dialogs::quit` | §11.4 |
| `MCLauncherModpackDetailModal` | `mmcl_library::detail_modal::DetailModal` (owner TBD) | `show(pack)`, `hide`, `dispose`, `is_shown` |
| `ModpackContentBrowser` | `mmcl_library::content_browser` (owner TBD) | section builders, `toggle_mod_enabled`, `apply_mod_update`, `directory_size` |
| `LoadingIndicator` | `mmcl_ui_kit::loading::LoadingIndicator` | `outline`, `shape_radius`, `ease_out_back` |
| `WavyProgressBar` | `mmcl_ui_kit::wavy::WavyProgressBar` | `set_progress`, `set_wavy_enabled`, `wave_points` |
| `StepBadge` | `mmcl_ui_kit::step_badge` | `glyph_for` |
| `FilterChip`, `IconButtons`, `SearchFields`, `TooltipManager`, `EmptyStates` | `mmcl_ui_kit::{chip, icon_button, search_field, tooltip, empty_state}` | — |
| `AvatarImages` | `mmcl_ui_kit::avatar::Avatars` | `url`, `get` |
| `AnnouncementBanners`, `OfflineIndicator` | `mmcl_ui_kit::{banner, offline_chip}` | — |
| `WindowIcons` | `mmcl_platform` (packaging) | — |
| `Motion`, `AnimationGate` | `mmcl_ui_kit::motion` | `enter`, `exit`, `fade_through`, `cubic_bezier`, `set_reduce_motion` |
| `BundledFonts` | `mmcl_ui_kit::fonts::register(cx)` | — |
| `LauncherIcons` | `mmcl_ui_kit::icons::Icon` + embedded assets | `path()` |
| `MaterialSwitchSkin` | `mmcl_ui_kit::switch::Switch` | — |
| `ImageFadeIn`, `SmoothScroll`, `ShapeScale` | `mmcl_ui_kit::{fade_image, smooth_scroll, shape}` | — |

---

## 13. Public API other subsystems call

```rust
// mmcl-running-games
pub fn init(cx: &mut App, registry: Entity<GameSessionRegistry>, config: ConfigHandle);
pub fn show_session(cx: &mut App, id: SessionId);
pub fn show_window(cx: &mut App);
pub fn hide_unless_preparing(cx: &mut App);
pub fn refresh_theme(cx: &mut App);
pub fn follow_screen(cx: &mut App, host: WeakEntity<ScreenHost>);
pub fn shutdown(cx: &mut App);

// mmcl-editor
pub fn open_editor(window: &mut Window, cx: &mut App, initial: Option<PackRef>) -> Entity<ModpackEditor>;
impl Screen for ModpackEditor {            // shell trait
    fn confirm_navigate_away(&mut self, cx) -> Task<bool>;
    fn help_topic(&self) -> HelpTopic { HelpTopic::ModpackEditor }
}

// mmcl-ui-kit
pub fn register_fonts(cx: &mut App) -> anyhow::Result<()>;
pub struct Dialogs;                        // global; §7.2
pub mod notify { pub fn info(..); pub fn success(..); pub fn warn(..); pub fn error(..); pub fn shutdown(); }
pub fn open_external_url(cx: &App, url: &str);
pub fn open_folder(cx: &App, dir: &Path);
pub fn is_light_chrome(theme: &str, os_dark: bool) -> bool;
pub mod motion { pub fn set_reduce_motion(bool); pub fn is_reduced() -> bool; … }
pub mod wavy { pub fn set_wavy_enabled(bool); }
pub enum Icon { … }
pub struct LoadingIndicator; pub struct WavyProgressBar; pub struct StepBadge; pub struct Switch;
pub struct FilterChip; pub struct IconButton; pub struct SearchField; pub struct Avatars;

// mmcl-app::dialogs
pub async fn quick_start_if_needed(cx: &mut AsyncApp, owner: AnyWindowHandle);
pub async fn confirm_import(cx, owner, name, version, index: &ModrinthIndex) -> bool;
pub fn add_account(cx: &mut App, owner: Option<AnyWindowHandle>);
pub async fn confirm_quit_while_games_run(cx) -> bool;
pub async fn confirm_restart_while_games_run(cx) -> bool;
pub fn request_quit(cx: &mut App);
```

---

## 14. Needs from other subsystems

* **Session**: `GameSessionRegistry` (`sessions()`, `active()`, `dismiss(id)`, `active_for_pack(key)`,
  change events from any thread), `GameSession` (`id, pack, pack_name, account_uuid,
  account_name, phase, started_ms, ended_ms, exit_code, tracker, log, crash_report, is_cancelled,
  cancel(), stop(force), add/remove listener`), `Phase::is_active`, `GameLog` (`subscribe(listener,
  max_lines) -> {snapshot, cancel, clipped}`, `tail(max_lines) -> {text, clipped}`, `full_text()`,
  `text()`, `file()`, `is_closed()`, `is_truncated()`), `LaunchProgressTracker` (`steps()` →
  `{id, state, display_label, sub_text, error_message, progress}`, listeners). `GameSession::key_for(pack)`.
* **Crash**: `CrashReportAnalyzer::analyze(text, pack, exit_code) -> CrashDiagnosis{title,
  summary, severity: Critical|Warning|Info, suggestions: Vec<Suggestion{label, action:
  Option<Box<dyn FnOnce + Send>>, primary}>}`.
* **Modpack**: `GameModPack` (manifest_url, friendly_name, pack_name, mod_loader_type,
  minecraft_version, pack_root_folder, settings_key, is_vanilla_version, pack_url,
  pack_version, …), `GameModPackFetcher::load_manifest_text(url)`, the manifest deserialization
  struct for validation, `ModrinthClient::{search, resolve_latest_compatible_file}`,
  `ModrinthIndex`, `ModrinthModUpdateChecker::scan`, `ModpackExporter`, `VerifyAction`,
  `OfficialLauncherExporter`, `ModPackAuditLog::analyze_problems`, `ModPackUpdateLog`.
* **Net**: text GET, file download, bounded HTTPS-only GET (5 MB), HEAD with timeouts and no
  redirects, `is_offline()` observable, `CacheManager::download_and_cache(url) -> PathBuf`.
  Generic User-Agent, never personal identifiers.
* **Utilities**: `HashUtilities` SHA-1 hex (lower-case), `VersionUtilities::compare_version_numbers`,
  `SensitiveDataRedactor::redact`, `UrlValidation::sanitized_http_url`, `UnitFormatter::bytes`.
* **Config**: getters/setters listed in §4.2, §11.1, §9.4 plus `theme`, `consoleLogMaxLines`.
* **i18n**: `t!` with MessageFormat + `choice`; `get_or`.
* **Theme**: tokens in §0.4, `apply_title_bar_dark_mode(window, dark)` (platform), `os_is_dark()`.
* **Shell**: screen host with a bottom dock slot and `dock_room()`, `ScaledMinSize`, `UiScale`,
  navigation (`go_to_game_library`), help window (`HelpTopic::ModpackEditor`), `close_app()`,
  top window handle, announcement text (`AnnouncementManager`).
* **Library**: `ModpackImageResolver::load_logo(url)`, `PackColorScheme`, `ModpackImageCycleClock`.
* **Login**: `SignInPanel` (callback with code/error/cancelled, `show_redeeming`,
  `fail_and_retry(key)`, `remember()`, `load_sign_in()`), `MCLauncherAuthManager::add_account_with_microsoft`.
* **Platform**: notifications backend + tray, `DesktopShortcutManager`, window icons/app id,
  `RgbIntegration::on_menu`.

---

## 15. External Rust crates

| Crate | Use |
|---|---|
| `gpui = "0.2.2"` | UI |
| `serde`, `serde_json` (`preserve_order`, `arbitrary_precision`) | manifest model, Modrinth/Fabric/Forge JSON |
| `json5` (optional) | lenient manifest parsing fallback (Gson leniency) |
| `sha1`, `sha2`, `md-5` (RustCrypto) | installer / row hashes |
| `regex` | NeoForge `maven-metadata.xml` version extraction |
| `rfd` (async) | open/save dialogs **with file-type filters and initial file name** (GPUI's path prompts have no filters) |
| `lru` | 128-entry Modrinth icon cache |
| `image` | decode + resize icons (40×40) and previews |
| `url`, `form_urlencoded` / `percent-encoding` | Modrinth query/facet encoding, `file:` URIs |
| `tempfile` | temp downloads for hashing |
| `notify-rust` (+ `mac-notification-sys` / WinRT via it) | OS notifications |
| `sysinfo` | total RAM for the wizard |
| `unicase` or `caseless` | case-insensitive log search on `char`s |
| `similar` (optional) | real line diff if parity is relaxed |
| `async-channel` or `flume`, `futures` (oneshot) | foreign-thread → UI-thread events, dialog answers |
| `rust-embed` | embed icon SVGs and fonts as GPUI assets |
| `anyhow` / `thiserror` | errors |
| HTTP client: whatever the net slice picks (`ureq` blocking on the background executor is simplest; `reqwest` needs a Tokio runtime alongside GPUI's executor) | — |

**No good equivalent / caveats**

* **gpui-component 0.9 is not compatible with gpui 0.2.2**: its `Cargo.toml` depends on
  `gpui-pre = "=0.3.11"` (package rename), so its `Table`, `Input`, `Select`, `Switch`, `Tabs`,
  `Dialog`, `Notification` types would belong to a different gpui crate. Either move the whole
  workspace to `gpui-pre 0.3.11` (and accept a pre-release GPUI), or stay on 0.2.2 and build the
  needed widgets in `mmcl-ui-kit` (text input with selection/IME, editable virtualized table,
  combo/select, switch, slider, tabs, modal host). This is the single biggest decision for this
  slice. Open question.
* **Web view** for Microsoft sign-in (Add account): none in GPUI. `wry` standalone, system browser
  + loopback, or device code. Open question (login slice).
* **Editable data table** (sortable, multi-select, inline editing, checkbox cells, button
  cells): nothing ships with gpui 0.2.2; build on `uniform_list` (rows are uniform height there).
* **Rich text input with IME**: gpui 0.2.2 exposes `EntityInputHandler`/`ElementInputHandler`
  (Zed's examples/input.rs shows a minimal input); multi-line areas (logo URLs, scan exclusions)
  need a small multi-line editor. Substantial work without gpui-component.
* **Element scale transforms** (Motion enter/exit scale 0.96): not in gpui 0.2.2; approximate.
* **Per-window icons**: platform/packaging, not GPUI.
* **MessageFormat `choice`**: needs the i18n slice's own formatter (or ICU4X plural rules).

---

## 16. Tests to port

Pure (port as Rust unit tests, same vectors):

* `ModPackDocumentTest` (73 tests) → `mmcl-modpack/src/document/tests.rs`: blank defaults (format
  2, version `1.0.0`, RAM `"2"`, loader forge, empty arrays, only the name fails validation);
  `from_json` (parses, rejects empty, rejects non-object); `wrapping` (rejects null, shares
  without copy); scalar defaults for absent/null; string-or-array shapes (single → string,
  several → ordered array, none → `""`, blank lines dropped and trimmed, null input, null array
  elements skipped, stable across edit cycles, 1-element array collapses); array lines (always an
  array, empty for blank, non-array reads empty); `read_file_list` (absent, non-array, skips
  non-objects, `"-1"` and blank are not hashes, requirement defaults/honoured, flagless lists
  always required, names ignored where not carried, slug read); `write_file_list` (all three hash
  slots always, name/flags omitted where not carried, empty slug omitted, replaces, null entries
  → empty); round trips (no hash dropped; second cycle changes nothing); `bump_version` (major/
  minor/patch resets, null/blank → 0.0.0, non-numeric → 0, short padded, 4th segment lost,
  out-of-range and negative positions unchanged); `first_line`; `sanitized_file_base_name`;
  validation (missing name+version both reported, blank name, unparseable/fractional/absent RAM,
  entry with neither remote nor local reported twice, unnamed entries labelled by position,
  fully-populated clean, one message per issue without newlines, JSON-null name is an error);
  `FileList` shapes and label keys.
* `ModPackDocumentAuthoringTest` (23 tests): fork (name + minor bump, carries everything,
  independent, needs a name, trims), `add_mod` (adds, keeps order, refuses duplicate local path
  regardless of case, refuses blank local, survives serialization), `remove_mod` (by local / name /
  slug, case-insensitive + trimmed, absent → false and unchanged, one per call),
  `write_local_manifest` (writes and returns a `file:` URL, creates dirs, hostile name cannot
  escape, name sanitizing to nothing refused, null dir refused, near-duplicate refused when not
  overwriting, path derived from sanitized lower-cased name, rewriting reuses the file).
* `LogTrimPolicyTest` (25 tests) → `mmcl-session/src/trim.rs`: unlimited rules, slack (2 000 for
  10 000; floor 50 for 100 and 0), trim thresholds (10 000/10 000 no; 12 000 no; 12 001 yes;
  150/100 no; 151/100 yes), `display_drop_offset` vectors, `full_log_drop_offset` (below/at
  trigger 0, advances to line boundary e.g. 20, raw-offset fallback e.g. 15, null, forward
  progress), `tail_lines` (`"a\nb\nc\nd\n"`,2 → `"c\nd\n"`; unterminated `"a\nb\nc\nd"`,2 →
  `"c\nd"`; short/unlimited unchanged).
* `GameSessionPaneSearchTest` (10 tests) → `log_view::tests` (§6.3 vectors), including
  `split_lines`/`join_lines`.
* `GameSessionPaneUptimeTest` (3 tests): `63_000` → `1:03`, `7_000` → `0:07`, hours → `1:05:09`,
  `12:34`, `0` → `0:00` (assert through the same `t!("session.uptime", …)`).
* `RunningGamesDockResizeTest` (4 tests) with `WINDOW = 1000`, `ROOM = 600`:
  `drag_share(420, 100) = 0.52`, `(420, -100) = 0.32`, `(420, 400) = 0.60` (room clamp),
  `(420, -900) = MIN`, large up = `MAX`, `clamp_share(5) = MAX`, `clamp_share(-1) = MIN`,
  `clamp_share(NaN) = 0.42`, `drag_share(…, window 0) = 0.42`.
* `ProgressVisualsTest`: wave starts flat, stays within `cy ± AMPLITUDE`, ends exactly at `to`;
  zero amplitude is flat; every badge state but Pending has a glyph; loading shapes stay inside
  their radius and consecutive frames morph smoothly (bounded point movement).
  (`horizonColumns…` belongs to `BlockyHorizon`, progress slice.)
* New pure tests worth adding: `sanitize_single_line` / `sanitize_multiline` (600/4000 caps,
  newline collapsing, redaction hook), `question_buttons(b2)` cancel folding,
  `neoforge_mc_version`, Forge promos → entries (recommended/latest dedupe, prefilter fallback),
  Fabric stable filtering, Modrinth facet string building, description truncation on non-ASCII,
  `positional_diff`, `recommended_ram_gb` / `initial_ram_gb`, import totals/basename, Gson-style
  HTML escaping in `to_pretty_json`, `format_downloads`, mod toggle rename target.

GUI (TestFX-gated in Java; port as GPUI `#[gpui::test]` with `TestAppContext`/`VisualTestContext`
behind a feature or env gate, mirroring `MMCL_RUN_TESTFX`):

* `SessionLogViewFxTest`: a big log is virtualized (few rows built), rows wrap to width, and the
  reader keeps their place when lines drop off the front.
* `AnimationGateFxTest`: animation runs only while seen.
* `RunningGamesSnapshotFxTest` (each phase renders), `RunningGamesDockSnapshotFxTest` (dock,
  collapse, pop out, dock back, drag-resize): port as behaviour tests on the model plus optional
  screenshot output.
* `MaterialSwitchSkinFxTest`, `DetailModalSnapshotFxTest`, `ControlsSnapshotFxTest` (component
  visuals) — screenshot-only; lower priority.
