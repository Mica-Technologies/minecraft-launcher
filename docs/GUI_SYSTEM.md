# GUI System

Technical documentation for the JavaFX GUI: screens and navigation, the navigation gate, the
Running Games window, the account and sign-in UI, the modpack detail modal, theming, and how the
GUI is tested.

## Overview

The launcher has one main window. A static controller swaps one screen at a time into it. Each
screen is a controller class paired with an FXML layout. Some windows sit outside that main
window: the **Running Games** window (one tab per game), the **Add account** window, the help
window and the quick-start wizard. These are built in code and use the same theme stylesheets.

Launching a game **does not take over the main window**. A launch opens a tab in the Running
Games window, and the library stays usable for launching more games.

Code (paths relative to `src/main/java/com/micatechnologies/minecraft/launcher/`):
- `gui/` -- screen controllers, windows, dialogs and the pure view models behind them
- `src/main/resources/gui/` -- FXML layouts (`components/` holds the brand-logo fragments)
- `src/main/resources/ui/` -- theme stylesheets
- `game/session/` -- `GameSession`, `GameSessionRegistry`, `GameLog` (what the Running Games window shows)

## Architecture

```
  MCLauncherGuiController (static)            RunningGamesWindow (singleton Stage)
   │  startGui / prestartGui / prebuildMainGui  │  one Tab per GameSession
   │  goTo*Gui()  ──┐                           │   └ GameSessionPane
   │  mayNavigateAway / mayLeaveForLaunch       │  listens to GameSessionRegistry
   v                v                           │
  MCLauncherGuiWindow (the main Stage)        AddAccountDialog (modal Stage)
   │  setScene(gui):  previous.cleanup()        │   └ SignInPanel ─ MicrosoftSignIn
   │                  gui.setup()               │
   │                  guardCloseWhileGamesRun() MCLauncherLoginGui (screen)
   │                  forceThemeChange()          └ SignInPanel ─ MicrosoftSignIn
   │                  stage.setScene(...)
   │                  gui.afterShow()
   v
  MCLauncherAbstractGui subclasses (one per screen, FXML-backed)
```

## Screens

| Controller | FXML | Purpose |
|---|---|---|
| `MCLauncherMainGui` | `gui/mainGUI.fxml` | Home: installed packs as hero cards, account header, running-games button |
| `MCLauncherGameLibraryGui` | `gui/gameLibraryGUI.fxml` | Browse: install / uninstall modpacks and vanilla versions, add by URL, import zip / Prism |
| `MCLauncherSettingsGui` | `gui/settingsGUI.fxml` | Settings, in ten categories (Account, Game, Appearance, Advanced, Network, Security, System, Discord, RGB, About) |
| `MCLauncherModPackEditorGui` | `gui/modpackEditorGUI.fxml` | Create, edit and export modpack JSON definitions |
| `MCLauncherRuntimeGui` | `gui/runtimeManagementGUI.fxml` | List, refresh and delete installed Java runtimes |
| `MCLauncherLoginGui` | `gui/loginGUI.fxml` | First-run / signed-out Microsoft sign-in |
| `MCLauncherProgressGui` | `gui/progressGUI.fxml` | Generic three-label progress screen (startup sign-in, runtime work) |
| `MCLauncherLaunchProgressGui` | `gui/launchProgressGUI.fxml` | Step-list progress driven by a `LaunchProgressTracker`; used by `VerifyAction` for "Verify now" |

The Edit Modpacks and Vanilla Versions screens no longer exist. `MCLauncherGameLibraryGui` replaces
both. The old in-game console screen (`MCLauncherGameConsoleGui`) is also gone; the Running Games
window replaces it.

Other classes in `gui/`:

| Class | Role |
|---|---|
| `MCLauncherModpackDetailModal` | In-scene modal overlay for one pack (see [Detail Modal](#modpack-detail-modal)) |
| `ModpackContentBrowser`, `ModrinthAddModDialog` | Content tab file browsers; add a mod from Modrinth |
| `MCLauncherImportConfirmDialog` | Second step of the modpack import flow |
| `MCLauncherHelpWindow`, `HelpTopic` | Singleton help window; each screen returns its topic from `getHelpTopic()` |
| `MCLauncherQuickStartWizard` | First-launch wizard (theme, RAM, toggles), opened from Home |
| `SystemMenuBarManager`, `KeyboardShortcutManager` | macOS system menu bar; global shortcuts on every platform |
| `CardPool`, `ModpackImageResolver`, `ModpackImageCycleClock`, `ImageFadeIn`, `PlaceholderLogoFactory`, `LogoTransparencyDetector` | Card-grid support for Home and Browse |
| `TooltipManager`, `OfflineIndicator`, `GUIUtilities` | Shared tooltips, the offline chip, FX-thread and dialog helpers |

## Screen Lifecycle and Navigation

### MCLauncherGuiController

`MCLauncherGuiController` is a static class. It holds the single `MCLauncherGuiWindow`.
`startGui()` creates the window once (`StageStyle.UNIFIED`, so Windows DWM can draw a Mica backdrop).
An `AtomicBoolean startSuccess` records whether that worked. Every `goTo*()` method does the same three things:
1. `startGui()`
2. Build the controller. The constructor loads the FXML.
3. `guiWindow.setScene( gui )`, then `guiWindow.show()`

```java
public static MCLauncherMainGui goToMainGui() throws IOException
public static MCLauncherSettingsGui goToSettingsGui() throws IOException
public static MCLauncherGameLibraryGui goToGameLibraryGui() throws IOException
public static MCLauncherLoginGui goToLoginGui() throws IOException
public static MCLauncherProgressGui goToProgressGui() throws IOException
public static MCLauncherLaunchProgressGui goToLaunchProgressGui() throws IOException
public static MCLauncherRuntimeGui goToRuntimeGui() throws IOException
public static MCLauncherModPackEditorGui goToModPackEditorGui( GameModPack initialPack ) throws IOException
```

Other entry points:
- `prestartGui()` and `prebuildMainGui()` are called from `LauncherSession`. They let the window and the Home scene graph build while sign-in and pack loading run. `goToMainGui()` uses the pre-built instance and then starts building the next one.
- `getTopStageOrNull()`, `getCurrentGuiOrNull()` and `isLauncherFocused()` give access to the window.
- `requestFocus()` de-iconifies the window and brings it to the front.
- `forceThemeRefresh()` re-applies the theme.
- `exit()` drops the pre-built Home, calls `RunningGamesWindow.shutdown()`, cleans up the window and resets `startSuccess`.
- `shouldCreateGui()` returns `!GraphicsEnvironment.isHeadless()`.

### MCLauncherAbstractGui

Every screen extends `MCLauncherAbstractGui`. The constructor loads `getSceneFxmlPath()` on the FX
thread with the controller set to `this` and the resources set to `LocalizationManager.currentBundle()`.
A new scene takes the size of the current scene. Its fill is a near-black (`#0C1017`), so an unstyled
frame never flashes white.

| Hook | When it runs |
|---|---|
| `setup()` | In `MCLauncherGuiWindow.setScene`, before the scene is attached. Wire controls and listeners here. |
| `afterShow()` | After `stage.setScene`, for work that needs the scene laid out (focus, WebView loads, the quick-start wizard). |
| `cleanup()` | When the next screen replaces this one, and in `MCLauncherGuiWindow.cleanup()` at exit or restart. Remove every listener added in `setup()`. |
| `confirmNavigateAway()` | Before a navigation that does not come from the screen's own controls (see below). Default `true`. |
| `allowsToolbarNavigation()` | Whether primary navigation (Browse / Settings / Account) is allowed. Default `true`. |
| `getHelpTopic()` | The topic for the help button, F1 and Cmd/Ctrl+Shift+/. |

### MCLauncherGuiWindow.setScene

`setScene( gui )` does the whole swap inside one `GUIUtilities.JFXPlatformRun`. Two racing
navigations (a double menu click, or a shortcut plus a callback) therefore run one after the other.
Each one cleans up exactly the screen it replaces. The steps, in order:

1. Call `cleanup()` on the previous screen. A `RuntimeException` there is logged and does not stop the swap.
2. Stop the cold-start placeholder animation (first swap only), then call `gui.setup()`.
3. `guardCloseWhileGamesRun()`, described below.
4. Set the stage minimum size from the FXML root's min width and height.
5. Add a corner help button (`injectHelpButton`) unless the FXML has a node with the `helpButton` style class.
6. `SystemMenuBarManager.attachTo( gui.rootPane )`. This only does something on macOS.
7. Set the window title and call `forceThemeChange()`.
8. `stage.setScene( gui.scene )`.
9. Platform title-bar hooks: `WindowsTitleBarControls`, `MacOsTitleBarManager.hideRedundantBranding` / `installWindowDrag`, and `MacOsToolbarManager.hideReplacedControls` / `setNavigationEnabled( gui.allowsToolbarNavigation(), stage )`.
10. `gui.afterShow()`.

`docs/PLATFORM_INTEGRATION.md` covers the platform hooks.

### Close guard

`guardCloseWhileGamesRun()` wraps the screen's own `setOnCloseRequest` handler in a `CloseGuard`
record. If the screen set no handler of its own, the guard unwraps the previous guard so the
question is never asked twice. When `GameSessionRegistry.get().active()` is empty, the screen's
handler runs as before. Otherwise the event is consumed and a question dialog opens:
- **Leave running**: the games keep going and lose their launcher-side log.
- **Stop games**: `cancel()` and `stop( false )` on each session.
- Closing the dialog keeps the launcher open.

After either choice, the screen's handler runs, or `LauncherCore.closeApp()` if there is none.

The prompt itself is `MCLauncherGuiController.confirmQuitWhileGamesRun()`. Every other way of
quitting reaches it through `requestQuit()`: the Home **Exit** button, the tray and dock **Quit**
items, and Cmd+Q (the macOS quit handler cancels the system quit and calls `requestQuit()`).

### Navigation gate

Navigation from outside the screen goes through `MCLauncherGuiController`. This covers the macOS
menu bar, keyboard shortcuts, the dock / tray / jump-list menu and the macOS toolbar. These callers
run off the FX thread:

| Method | Rule | Used by |
|---|---|---|
| `mayNavigateAway()` | `allowsToolbarNavigation() && confirmNavigateAway()` | `SystemMenuBarManager`, `KeyboardShortcutManager.navigate`, `LauncherActions.openBrowse` / `openSettings` (dock and toolbar), crash-suggestion buttons (`CrashReportAnalyzer`) |
| `mayLeaveForLaunch()` | `allowsToolbarNavigation()` only. A launch leaves the screen as it is. | `LauncherActions.readyToLaunch`, `LauncherUriHandler` |

`allowsToolbarNavigation()` returns `false` on `MCLauncherLoginGui` (so nothing can skip sign-in), on
`MCLauncherProgressGui` and `MCLauncherLaunchProgressGui`, and on `MCLauncherRuntimeGui`.
`confirmNavigateAway()` is overridden in two places:
- `MCLauncherSettingsGui`: Save, Return, or close the dialog to stay.
- `MCLauncherModPackEditorGui`: Discard or Cancel when the document is dirty.

Both show the dialog with the blocking `GUIUtilities.showQuestionMessage`. That method returns 0 when
the dialog is closed, otherwise 1 or 2 for the button chosen. A screen's own Return / Back buttons
keep their own prompts.

## Main Screen (Home)

`MCLauncherMainGui` shows installed packs as `ModpackHeroCard` tiles in a `FlowPane`
(`modpackCardList`) inside `modpackScrollPane`. The grid is responsive and paginated.

- **Filters**: `searchField`, `typeFilter`, `sortFilter`, `recentlyUpdatedOnlyCheck`, `pageSizeFilter` and prev/next paging. A `LibraryViewModel` holds the state and calls `rebuildCards()`. Cards are reused through `CardPool`. Sort keys come from `LibrarySortKeys`.
- **Account header**: `userImage` and `playerLabel` show the default account, through `bindAccountHeader()` and `AvatarImages`. Clicking either opens `AccountSwitcherMenu`. The header listens to `MCLauncherAuthManager.accounts()` and repaints in place when the default changes.
- **Running games**: `runningGamesBtn` shows while any session is active and calls `RunningGamesWindow.showWindow()`. Card Play buttons follow `GameSessionRegistry`. `startPlay` brings up the tab of a pack that is already running instead of launching it again. Otherwise it calls `LauncherCore.play( pack )` off the FX thread.
- **Shortcuts**: Enter plays the last-played pack, F5 refreshes, Cmd/Ctrl+F focuses search. `KeyboardShortcutManager.installGlobalShortcuts` adds Cmd/Ctrl+`,` (Settings), `L` (Browse), `E` (Editor), Shift+`M` (Home) and F1 (Help).
- **Bottom bar**: background-status labels ("Signing in…", "Loading available packs…"), the offline chip and the version label.

`MCLauncherGameLibraryGui` (Browse) uses the same grid design (`LibraryCard`, `CardPool`, its own
`LibraryViewModel`). It adds a `statusFilter` and controls to add a pack by URL, import a zip or a
Prism instance, open the editor and save a hosting manifest (`installable.json`).

### Disposed flag

Home and Browse both have a `private volatile boolean disposed`. `cleanup()` sets it **before**
teardown. Late async callbacks check it before touching cards: manifest revalidation, image
caching, registry and account listeners (`refreshRunningGames`, `bindAccountHeader`). After a
navigation the old `Scene` still exists, so `getScene() != null` does not tell you the screen is
gone. `cleanup()` also unsubscribes every card, both shown and pooled, from
`ModpackImageCycleClock`, and disposes the detail modal.

### Localized filters and scrolling

- `FilterOptionLabels`: the filter and sort combos hold stable ids (`"modpacks"`, `"nameAz"`). They display them through `FilterOptionLabels.converter( TYPE | STATUS | SORT )`, which looks up `filter.type.<id>`, `filter.status.<id>` or `filter.sort.<id>`. The filter logic compares ids, never the English labels.
- `SmoothScroll.install( ScrollPane )` (and an overload for `WebView`) replaces wheel stepping with an eased animation. Its capture-phase filter checks `isInsideNestedScrollable` and lets a nested scrollable under the pointer handle the wheel event itself. Without that, text areas and nested lists inside a smooth-scrolled page could not be scrolled. Horizontal-only gestures are also passed through.

## Running Games Window

`RunningGamesWindow` is a lazily created singleton `Stage` with a `TabPane` and one tab per
`GameSession`. When there are no tabs, an empty-state label shows instead. All its static methods
can be called from any thread:

| Method | Use |
|---|---|
| `showSession( session )` | Select that game's tab and show the window. `LauncherCore` calls it when a launch starts, on a crash, and when a launch is refused because the pack is already running. The Play buttons on Home and in the detail modal call it too. |
| `showWindow()` | The Home "N running" button |
| `hideUnlessPreparing()` | Called when "Show console on launch" (`ConfigManager.getInGameConsoleEnable()`) is off. The window hides once the game is up, unless another game is still preparing. |
| `shutdown()` | Called from `MCLauncherGuiController.exit()`. Detaches from the registry, disposes the panes and closes the window. |

The window is driven by `GameSessionRegistry` listeners. `sync()` adds tabs for new sessions and
removes tabs for dismissed ones. `refreshTab()` draws the tab graphic: a status dot styled
`sessionStatus-<phase>` and a 16 px avatar.

The window's behaviour:
- Closing the window only **hides** it. The games keep running and keep being logged.
- A tab cannot be closed while its session's `phase().isActive()`. Closing an ended tab calls `GameSessionRegistry.dismiss( id )`.

### GameSessionPane

Each tab holds a package-private `GameSessionPane`. It is built in code, has no FXML, and lives on
the FX thread only.

- **Header**: pack name, "Playing as <account>" with avatar, uptime (`formatDuration`, `m:ss` / `h:mm:ss`, ticked every second while running) and a status chip.
- **PREPARING**: the `LaunchProgressTracker` steps (one row each, refreshes coalesced) and a Cancel button (`session.cancel()`).
- **After launch**: the live log. The pane subscribes to the session's `GameLog` (`subscribe` returns the snapshot so far plus a cancel handle), so a pane opened mid-game starts with the full captured log. Also: case-insensitive find with Prev/Next, an auto-scroll toggle, and display trimming to `ConfigManager.getConsoleLogMaxLines()` through `LogTrimPolicy`. When the display is trimmed, a notice links to the full log file.
- **Footer**: Copy, Open log file, and Stop (`stop( false )`) / Kill (`stop( true )`) while RUNNING.
- **CRASHED**: `CrashReportAnalyzer.analyze` fills a diagnosis card. It has severity styling and suggestion buttons, which run off the FX thread. If a crash report exists, a toggle switches between it and the game log. If nothing was logged, the pane opens on the crash report.
- **FAILED / CANCELLED** with no log: a short message.

`GameLog` captures output whether or not a pane exists (multi-account plan D8). `dispose()` sets
`disposed` and detaches the session, tracker and log listeners. It does not affect the game.

## Accounts and Sign-In UI

These follow decisions D3, D10 and D11 in `docs/agent-progress-plans/MULTI_ACCOUNT_PLAN.md`:
- Every saved account stays signed in, and switching the default is instant (D3).
- Adding an account never changes the default unless there is none (D10).
- The login screen and the Add-account window share one `SignInPanel` (D11).

`docs/AUTHENTICATION_SYSTEM.md` covers the auth side.

| Class | Role |
|---|---|
| `AccountListModel` | Pure. `rows( accounts )` returns `Row( uuid, name, statusKey, isDefault, canMakeDefault, needsSignIn, sessionOnly )` with the default first. It decides which actions each row offers. |
| `AccountSwitcherMenu` | `show( anchor, openAccountSettings )`: a `ContextMenu` under the header avatar or name. It has a tick on the default and an item per account. Picking one calls `accounts().setDefault( uuid )` off the FX thread (no restart). A row that needs sign-in opens `AddAccountDialog` instead. Also has "Add account…" and "Manage accounts…" (Settings → Account, `showCategory( 0 )`). |
| Settings → Account | `setupAccountTab()` / `rebuildSavedAccountsList()`: one row per `AccountListModel.Row`. Each row has Sign in again, Make default, and Sign out (`confirmSignOut`; signing out the last account restarts into login). Repaints from an account-manager listener that `cleanup()` removes. |
| `AddAccountDialog` | `show( owner )`: a window-modal `Stage` holding a `SignInPanel`. Calls `MCLauncherAuthManager.addAccountWithMicrosoft( code, remember )` off the FX thread, then shows a success notification or `failAndRetry`. Blanks the WebView when hidden. |
| `SignInPanel` | Shared card: an information column (what happens, why it's safe, "Stay signed in" toggle, status) next to the Microsoft page in a rounded frame. An overlay covers loading, redeeming (`showRedeeming()`) and the unreachable-page retry. `loadSignIn()` and `failAndRetry( key )` reset it. |
| `MicrosoftSignIn` | `loginUrl()` (adds `prompt=select_account` so a second account can be chosen), `parseCallback( location )` (pure; an `error` wins over `code`), and `attach( webView, waiting, onCallback )`, which catches the OAuth redirect once per load. |
| `MCLauncherLoginGui` | Hosts `SignInPanel` in `signInHost` and calls `loginWithMicrosoftAccount` (which sets the default). `waitForLoginSuccess()` blocks the session thread until sign-in finishes. |
| `AvatarImages` | `get( uuid )`: a per-session cache of background-loading `Image`s. A failed load is retried on the next request. Used by the header, switcher, Settings, the detail modal and the Running Games tabs. |

## Modpack Detail Modal

`MCLauncherModpackDetailModal` is a `StackPane` overlay attached to Home's root
(`attachToGridPane`). It opens with `show( pack )` and closes on backdrop click, the close button
or Esc. Home calls `dispose()` from its `cleanup()`.

The modal has a persistent hero image, a meta header and a sticky Play / Website row. Play uses the
same running-pack check as Home. The meta header shows stat chips and, when the audit finds
problems, a Problems banner. A pack with a launch-account override gets a "Plays as <name>" chip
(or a "missing account" chip). The tabbed body builds each tab when it is first opened:

| Tab | Shown when | Contents |
|---|---|---|
| Overview | always | News, Links, Quick Actions, Stats |
| Content | the pack has an install folder | Worlds / Servers / Mods / Screenshots / Shader / Resource packs browsers (`ModpackContentBrowser`), Modrinth add |
| Activity | the pack has an install folder | Update Log, Crash History |
| Advanced | `pack.getSettingsKey() != null` (a manifest or a vanilla version) | **Launch as** account choice, plus manifest-only verify controls |

**Launch as** (`buildAccountOverride`) is an `MFXComboBox` of account uuids:
- The first entry is the default account (no override). Choosing it calls `ConfigManager.setAccountOverrideForPack( key, null )`.
- Accounts that need sign-in are labelled as such.
- An override whose account is gone stays in the list as "missing". At launch `LauncherCore.resolveLaunchUser` then blocks and asks what to do instead of falling back silently (D9).

Vanilla versions get only this control. Packs with a manifest also get the verify toggles and
"Verify now" (`VerifyAction`).

## Theming

Each screen root gets two stylesheets, from lowest to highest precedence:

1. **`ui/ui-base.css`**: theme-agnostic component styling (font stack, cards, chips, buttons, dialogs, tables, popups). It uses only `-color-*` lookup variables, never literal colours.
2. **Token sheet** (`ui/ui-tokens-{dark,light,bluegray,orangepurple,creeper,native,native-light}.css`): defines the `-color-*` palette. Every token sheet defines the same set of tokens. `-color-popup` and `-color-popup-border` stay opaque in every theme, because popups and dialogs are separate windows with nothing behind them.

Until 2026.10 a per-theme legacy sheet (`guiStyle-<theme>.css`) loaded underneath, with its own hard-coded palette. Its still-live rules now sit, mapped to tokens, in section 0 of `ui-base.css`.

`MCLauncherGuiWindow.forceThemeChange()` maps `ConfigManager.getTheme()` to a token sheet (`themeStylesheetPaths` gives the full list, and is what the snapshot test renders):

| Theme | Token sheet |
|---|---|
| Dark / Light / Blue Gray / Orange Purple / Creeper | the matching token sheet |
| Automatic | Dark or Light, following `OsThemeUtilities.isOsDark()` |
| Native | macOS and Windows: `ui-tokens-native` / `-native-light` (transparent surfaces over Mica or vibrancy). Linux: plain Dark/Light. |

**Inline styles.** `setStyle(...)` is only for values computed at runtime: background images, the
hero gradient, window transparency, title-bar insets, theme swatches. Fixed sizes, weights and
colours belong in a CSS class (`type-body-small`, `type-label-small`, `type-weight-bold` and so on),
because the theme can't reach inline styles. Popups resolve tokens through their owner node, so
attach tooltips and context menus to a node (`Tooltip.install`, `ContextMenu.show( anchor, ... )`).

**Snapshots.** `ThemeSnapshotFxTest` (`MMCL_RUN_TESTFX=true`) renders every screen, two control
galleries, a dialog and the popups in all seven theme variants to `build/target/snapshots/themes/`.
`tools/ui-snapshots/diff_snapshots.py BEFORE AFTER` reports changed pixels per image and writes
highlighted diffs. Every Maven run wipes `build/` (except `build/jdk`), so keep a baseline outside it.

`applyTheme( tokens )` swaps the sheets only when they differ. It always paints the root
background and scene fill (`themeBgHex`) as a fallback. On a real theme change it also updates the
native chrome: DWM backdrop, caption and border colour, dark title bar, full repaint. Plain
navigations skip that step to avoid flicker. `applyNativeThemeFill` goes transparent only where a
backdrop really exists. The OS theme listener re-applies the theme only for Automatic and Native.

Secondary windows (Running Games, Add account, help, wizard) call the static
`MCLauncherGuiWindow.installCurrentThemeStylesheets( root )`. That gives them the same sheets
with a solid background (no Mica). `GUIUtilities.isLightChrome( theme )` picks their title-bar mode.
`MCLauncherHelpWindow.refreshTheme()` is the only secondary window refreshed by `forceThemeChange()`.

## Localization in FXML

The `MCLauncherAbstractGui` constructor calls `fxmlLoader.setResources( LocalizationManager.currentBundle() )`,
so FXML attributes can use `%key` (for example `text="%main.navbar.settings"` in `mainGUI.fxml`). Screens built in code
(`GameSessionPane`, `SignInPanel`, `AccountSwitcherMenu`, the modal) call `LocalizationManager.get`
/ `format` directly. Combos show localized labels for stable ids through a `StringConverter`
(`FilterOptionLabels`, the Launch-as combo). Don't compare against displayed text. The key-naming
and translation workflow is in `CLAUDE.md` (Localization).

## Testing

GUI logic that can be pure is pulled out into package-private seams and tested with plain JUnit
under `src/test/java/.../gui/`:

| Test | Covers |
|---|---|
| `LibraryViewModelTest` | Paging, filter/sort resets, search tokens |
| `AccountListModelTest` | Row order, default/make-default, needs-sign-in, refreshing, session-only, name fallback |
| `MicrosoftSignInTest` | `parseCallback` code/error/decoding cases |
| `FilterOptionLabelsTest` | Every option id has a label; a null selection renders empty |
| `LogTrimPolicyTest` | Display and full-log trim arithmetic, including "unlimited" |
| `ModpackImageCycleClockTest`, `ModpackContentBrowserIoTest`, `MCLauncherSettingsLanguageLogicTest` | Cycle-interval mapping, content-browser file I/O, language-picker logic |

TestFX tests are opt-in. Each has
`@EnabledIfEnvironmentVariable( named = "MMCL_RUN_TESTFX", matches = "true" )` and does not run in
the default build:

| Test | What it does |
|---|---|
| `TestFxSmokeTest` | Checks the TestFX harness itself (a button click updates a label) |
| `SettingsLanguageButtonFxTest` | The Save & Restart button appears after a language change and fires |
| `SignInPanelSnapshotFxTest` | Renders `SignInPanel` in dark and light themes to `build/target/snapshots/signin-*.png` and checks the layout at the login minimum size. Loads the real Microsoft page, so it needs network access; offline it shows the retry state. |
| `RunningGamesSnapshotFxTest` | Renders `GameSessionPane` tabs for fake preparing / running / crashed sessions (`FakeProcess`, injected config) to `build/target/snapshots/running-games-*.png` |

Run them with `MMCL_RUN_TESTFX=true mvn test`. Put new GUI tests behind the same gate. Prefer a
pure model (like `AccountListModel`) or a package-private static helper (like
`GameSessionPane.formatDuration`, `RunningGamesWindow.refreshTab`) over driving a live scene.

## Application Lifecycle

`LauncherCore.main()` loops on `restartFlag`, so the launcher can restart without starting a new JVM.
For a GUI client, each pass works like this:
1. `LauncherSession` prestarts the window and pre-builds Home.
2. `performClientLogin()` shows `MCLauncherProgressGui` while renewing, or `MCLauncherLoginGui` when sign-in is needed.
3. `doModpackSelection()` calls `goToMainGui()`.

`LauncherCore.restartApp()` / `closeApp()` end the pass. `MCLauncherGuiController.exit()` tears
down the window, the current screen (`cleanup()`) and the Running Games window.
