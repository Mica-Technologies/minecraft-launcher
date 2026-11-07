# GUI shell, screens and theming: porting spec

Slice: `src/main/java/com/micatechnologies/minecraft/launcher/gui/` (window shell and navigation,
Home, Browse, sign-in and Add-account, Settings, Runtime Management, the two progress screens,
the help window), their FXML in `src/main/resources/gui/`, the theme sheets in
`src/main/resources/ui/`, and `docs/GUI_SYSTEM.md`.

Out of this slice but referenced (owned by other specs): the Running Games window and
`GameSessionPane`, the modpack detail modal, the modpack editor, the quick-start wizard internals,
the macOS/Windows native chrome managers (`utilities/WindowChromeManager`,
`WindowsCustomChromeManager`, `MacOs*Manager`), config storage, auth, modpack management,
runtime management and localization. Where this slice calls them, the call and its contract are
listed in section 6.

Target: a Cargo workspace under `rust/` with GPUI `0.2.2` (optionally `gpui-component` 0.9).
The Rust app uses its own data folder for now, laid out exactly like the Java one
(`<launcherRoot>/config/configuration.json`, `<launcherRoot>/modpacks/...`, `runtime/`, `logs/`
etc.), so it can later be pointed at the Java launcher's folder.

---

## 1. Overview and architecture

One main window shows one screen at a time. A static controller swaps screens. Secondary
windows: Running Games (other spec), Add-account (modal), Help (singleton), quick-start wizard
(modal, other spec), plus native message dialogs and file pickers.

Launching a game never replaces the main window's screen: it opens a tab in the Running Games
window. The library stays usable.

```
GuiController (global, any thread)        RunningGamesWindow (other spec)
  start_gui / prestart / prebuild_main      show_session / show_window / follow_screen
  go_to(Screen)                             shutdown
  may_navigate_away / may_leave_for_launch
  confirm_quit_while_games_run / request_quit
        |
        v
MainWindow (the one OS window)            AddAccountDialog (window-modal)
  set_screen(screen):                        SignInPanel -- MicrosoftSignIn
    previous.cleanup()                     LoginScreen
    screen.setup()                           SignInPanel -- MicrosoftSignIn
    guard_close_while_games_run()          HelpWindow (singleton)
    apply min size / help button / menu
    title / theme / native title bar
    show + fade-through
    screen.after_show()
```

### 1.1 Screen list

| Java controller | FXML | Rust view (proposed) | Purpose | `allowsToolbarNavigation` | Help topic |
|---|---|---|---|---|---|
| `MCLauncherMainGui` | `mainGUI.fxml` | `screens::home::HomeScreen` | installed packs as hero cards, account header, running-games button | true | `MAIN_SCREEN` |
| `MCLauncherGameLibraryGui` | `gameLibraryGUI.fxml` | `screens::browse::BrowseScreen` | install/uninstall packs, vanilla versions, loader versions; add by URL; import zip/Prism; hosting manifest | true | `BROWSE_LIBRARY` |
| `MCLauncherSettingsGui` | `settingsGUI.fxml` | `screens::settings::SettingsScreen` | 10 categories | true (but `confirmNavigateAway` prompts) | `SETTINGS` |
| `MCLauncherRuntimeGui` | `runtimeManagementGUI.fxml` | `screens::runtime::RuntimeScreen` | list / refresh / delete Java runtimes | **false** | `RUNTIME_MANAGEMENT` |
| `MCLauncherLoginGui` | `loginGUI.fxml` | `screens::login::LoginScreen` | first-run / signed-out sign-in | **false** | `LOGIN` |
| `MCLauncherProgressGui` | `progressGUI.fxml` | `screens::progress::ProgressScreen` | three-label progress (startup sign-in, runtime work, legacy flows) | **false** | `GETTING_STARTED` |
| `MCLauncherLaunchProgressGui` | `launchProgressGUI.fxml` | `screens::launch_progress::LaunchProgressScreen` | step list from a `LaunchProgressTracker` (used by "Verify now") | **false** | `GETTING_STARTED` |
| `MCLauncherModPackEditorGui` | `modpackEditorGUI.fxml` | other spec | editor | true (prompts when dirty) | `MODPACK_EDITOR` |

Window title of every screen: `LAUNCHER_APPLICATION_NAME + " | " + screen.title()`
(`GUIConstants.TITLE_SPLIT_CHAR = " | "`). Screen titles: Home `menu.modpacks.home`, Browse
`browse.navbar.title`, Settings (`getSceneName`, a settings key), Runtime `runtime.navbar.title`,
Login `window.title.login` ("Login"), Progress `window.title.loading` ("Loading"),
LaunchProgress `window.title.launching` ("Launching").

### 1.2 Application lifecycle (GUI client)

`LauncherCore.main()` loops on `restartFlag`: the launcher can restart in-process (sign-out of the
last account, settings import, etc.) without a new process. Each pass:

1. `LauncherSession` calls `prestartGui()` on a background thread (window creation overlaps auth
   and pack loading) and then `prebuildMainGui()` (builds the Home view tree in advance).
2. `performClientLogin()` shows the Progress screen while tokens renew, or the Login screen when
   sign-in is needed; it blocks on `LoginScreen::wait_for_login_success()`.
3. `doModpackSelection()` calls `goToMainGui()`.
4. `LauncherCore.restartApp()` / `closeApp()` end the pass; `GuiController::exit()` tears down
   the window, the current screen (`cleanup()`), and the Running Games window; the help window's
   `cleanup()` is called from `LauncherCore.cleanupApp()`.

Rust: GPUI's `Application::run` owns the main thread for the life of the process and cannot be
re-entered. Model the in-process restart as "close all windows, drop all per-session state,
re-run the session bootstrap inside the same `App`" rather than a new `Application`. Every piece
of static state the Java code resets in `exit()` / `cleanup()` (pre-built Home, help window
statics, close guard, theme listener, scale listener) must live in a per-session struct that is
dropped on restart. This is exactly the class of leak the Java comments describe ("every
in-process restart leaked the previous window").

---

## 2. Window shell and navigation

### 2.1 `MCLauncherGuiController` (static) -> `shell::GuiController` (GPUI `Global`)

State:
- `start_success: AtomicBool` gates every accessor so off-thread callers never see a partly
  built GUI.
- `gui_window: volatile MainWindow?` (read once into a local before use: `exit()` can null it
  between a check and a second read).
- `prebuilt_main: Option<HomeScreen>` guarded by `PREBUILD_LOCK` (a session thread arriving
  mid-prebuild must block until it finishes instead of building a second copy).

Methods (all safe from any thread unless stated):

| Method | Behaviour |
|---|---|
| `getTopStageOrNull()` | stage if `startSuccess && window != null`, else null |
| `isLauncherFocused()` | stage showing && !iconified && focused. Gates native toasts. |
| `getCurrentGuiOrNull()` | current screen controller |
| `mayNavigateAway()` | **off the UI thread** (may block on a dialog). `current == null || (current.allowsToolbarNavigation() && current.confirmNavigateAway())` |
| `mayLeaveForLaunch()` | `current == null || current.allowsToolbarNavigation()` (a launch keeps the screen, so no unsaved-work prompt) |
| `confirmQuitWhileGamesRun()` | **off UI thread**. If `GameSessionRegistry.active()` empty -> true. Else question dialog: title `session.quit.title`, header `session.quit.header` (arg: count), body `session.quit.body`, buttons `session.quit.leaveRunning` (1), `session.quit.stopGames` (2), implicit Cancel. 0 (closed/cancel) -> false. 2 -> for each active session `cancel()` then `stop(false)`. Returns true for 1 or 2. |
| `confirmRestartWhileGamesRun()` | same pattern, keys `session.restart.{title,header,body,confirm}` + `dialog.button.cancel`; true only for 1 |
| `requestQuit()` | spawn task: if `confirmQuitWhileGamesRun()` then `LauncherCore.closeApp()`. Used by Home Exit, tray/dock Quit, Cmd+Q (macOS quit handler cancels the system quit and calls this). |
| `requestFocus()` | on UI thread: de-iconify if iconified, show if hidden, `toFront`, `requestFocus`. Used by tray click and `mmcl://` deep links. Without de-iconify the user saw no response. |
| `startGui()` | `synchronized`, idempotent. Creates the window (Java: `StageStyle.UNIFIED` so DWM can composite Mica; on Windows also the base for the custom title-bar inset). `start()` already shows the window. Failure -> log `log.controller.startGuiFailed`, `startSuccess=false`, window null. |
| `prestartGui()` | `startGui()` |
| `prebuildMainGui()` | under lock: return if already built or GUI not started; build Home (FXML only; `setup()` is NOT run here, it runs at `setScene` time after auth has populated the logged-in user). Failure -> silent warn `log.controller.prebuildMainFailed`. |
| `goToMainGui()` | `startGui()`; take the pre-built Home (or build one); `setScene`; `show`; then spawn `prebuildMainGui()` again so every later return to Home skips the ~250 ms build |
| `goToSettingsGui()`, `goToGameLibraryGui()`, `goToLoginGui()`, `goToProgressGui()`, `goToLaunchProgressGui()`, `goToRuntimeGui()` | build + `setScene` + `show`; return the controller (callers use it, e.g. `settings.showCategory(0)`, `progress.setLabelTexts`) or null + log `log.controller.<x>NotDisplayed` |
| `goToModPackEditorGui(initialPack?)` | stash the initial pack on the editor **before** `setScene` (setup reads and clears it) |
| `forceThemeRefresh()` | `window.forceThemeChange()` |
| `exit()` | drop pre-built Home under lock (it holds this session's stage; a restarted session would otherwise bind a controller to a dead window); `RunningGamesWindow.shutdown()`; `window.cleanup()`; `Platform.setImplicitExit(false)`; close the stage; null the window; `startSuccess=false` in lockstep |
| `shouldCreateGui()` | `!GraphicsEnvironment.isHeadless()` |

Rust shape: a `GuiController` global holding `Option<WindowHandle<MainWindow>>` plus an
`Arc<GuiState>` mirror (atomics + `RwLock`) for reads from background threads. Off-thread
callers send commands through an `async_channel` / `AsyncApp` handle; the "blocking" methods
become `async fn` that await a prompt answer. `may_navigate_away()` becomes
`async fn may_navigate_away(cx: &mut AsyncApp) -> bool`.

Pre-building Home is an optimisation for FXML parse cost. GPUI views are cheap to construct; keep
the API (`prebuild_main()` no-op is acceptable) but do not port the lock dance unless profiling
shows a need. Keep the semantics that Home's data binding (account header) runs at show time,
not construction.

### 2.2 `MCLauncherAbstractGui` -> `shell::Screen` trait

Constructor loads FXML on the UI thread, controller = `this`, resources = active localization
bundle (so `%key` attributes resolve). New scene takes the size of the stage's existing scene.
Scene root is wrapped in `ScaledRoot` (`UiScale.wrap`), `UiScale.install(scene)`, scene fill
`#0C1017` so an unstyled frame never flashes white. FXML failure -> log
`log.abstractGui.fxmlLoaderFailed`, throw `IOException`.

Hooks:

| Hook | When |
|---|---|
| `setup()` | in `setScene`, before the scene is attached: wire controls and listeners |
| `afterShow()` | after `stage.setScene`: focus, web loads, quick-start wizard, measurements |
| `cleanup()` | when replaced by the next screen and in `MainWindow.cleanup()` at exit/restart; remove every listener added in setup |
| `confirmNavigateAway()` | default true; off UI thread; must use blocking dialog |
| `allowsToolbarNavigation()` | default true |
| `getHelpTopic()` | default `GETTING_STARTED` |
| `getSceneName()` | window-title suffix |
| `hideStage()` | hide window (any thread) |

```rust
pub trait Screen: Render + 'static {
    fn id(&self) -> ScreenId;
    fn title(&self, cx: &App) -> SharedString;          // getSceneName
    fn help_topic(&self) -> HelpTopic { HelpTopic::GettingStarted }
    fn allows_toolbar_navigation(&self) -> bool { true }
    /// Runs on the foreground; may show a prompt. Return false to stay.
    fn confirm_navigate_away(&mut self, window: &mut Window, cx: &mut Context<Self>)
        -> Task<bool> { Task::ready(true) }
    fn setup(&mut self, window: &mut Window, cx: &mut Context<Self>) {}
    fn after_show(&mut self, window: &mut Window, cx: &mut Context<Self>) {}
    fn cleanup(&mut self, cx: &mut Context<Self>) {}    // drop Subscriptions/Tasks here
    fn min_size(&self) -> Size<Pixels>;                  // FXML rootPane minWidth/minHeight
    fn pref_size(&self) -> Option<Size<Pixels>> { None } // login grows the window to this
    fn has_own_help_button(&self) -> bool { true }      // false -> shell injects corner button
    fn on_close_requested(&mut self, window: &mut Window, cx: &mut Context<Self>)
        -> CloseDecision;                                 // the screen's own close handler
}
```

In GPUI most "remove every listener" work is automatic: store `Subscription`s and `Task`s in the
view; dropping the view drops them. Keep an explicit `disposed: bool` anyway for background
tasks that hold a `WeakEntity` and complete after navigation (see 3.6).

### 2.3 `MCLauncherGuiWindow` -> `shell::MainWindow`

Constants: `PREF_WIDTH=1000`, `PREF_HEIGHT=800`, `MIN_WIDTH=750`, `MIN_HEIGHT=600` (layout units,
multiplied by the interface scale).

#### 2.3.1 `start(stage)`
1. `BundledFonts.ensureLoaded()` (register Inter Regular/Italic/SemiBold/Bold and JetBrains Mono
   from `resources/fonts/` before any style resolves a family).
2. `WavyProgressBar.setWavyEnabled(config.wavyProgress)`, `Motion.setReduceMotion(config.reduceMotion)`,
   `UiScale.setPercent(config.uiScalePercent)`.
3. Base min size 750x600, `applyScaledMinSize()`; register a scale listener (kept in a field,
   removed in `cleanup()`: the scale property outlives the window and the listener pinned the
   whole previous window on every in-process restart).
4. `stage.setResizable(config.resizableWindows)`.
5. Window icon from `micaminecraftlauncher.png` (failure logs `log.guiWindow.setIconFailed`).
6. Create the OS theme detector (failure: silent warn).
7. `restoreSavedBounds()` **before** show so the window appears in place.
8. Install an empty placeholder scene (1000x800, fill and bg `#0C1017`) and show it. The
   placeholder has no nodes on purpose: even 12 nodes cost ~400 ms of first paint. After one
   idle pulse, if no real screen has arrived yet and the placeholder is still in this window,
   add three bouncing voxel cubes (see 2.3.9).
9. If bounds were not restored, centre on screen.
10. `installBoundsPersistence()`, `installMonitorChangeNudge()` (Windows only), macOS dock menu
    (`MacOsDockManager.installDockMenu(LauncherActions.buildDockMenu())`).

#### 2.3.2 Window bounds persistence
Config keys (all in `configuration.json`): `windowX`, `windowY`, `windowWidth`, `windowHeight`
(double, NaN when unset), `windowMaximized` (default false). Set together by
`ConfigManager.setWindowBounds(x, y, w, h, maximized)`.

Restore:
- Usable only if all four are non-NaN and `w >= 750 && h >= 600`.
- If `!WindowPlacement.titleBarOnScreen(saved, allScreensVisualBounds)`: clamp into the primary
  screen's visual bounds (`clampInto`); if the clamped size is below 750x600 give up (log debug
  `log.guiWindow.boundsOffScreen`), else use it (`log.guiWindow.boundsMovedOnScreen`).
- Apply x/y/w/h; seed `lastNormal*` with them and `normalBoundsSeen=true`; then
  `setMaximized(true)` if saved maximized.

Persist:
- 500 ms debounce (restart on every change).
- On x/y/width/height change: if not maximized, not iconified and w,h > 0, record
  `lastNormal{X,Y,W,H}` and `normalBoundsSeen=true`. Then restart the debounce.
- On maximized change: restart the debounce.
- Flush writes `lastNormal*` + current maximized flag (never the maximized geometry, so a
  restored maximized window un-maximizes to a sensible size). Skip if `!normalBoundsSeen`.
- `cleanup()` stops the debounce and flushes synchronously. Listener bodies null-check the
  debouncer: JavaFX fires a final location change during `close()` after cleanup nulled it.

Rust: `window.observe_window_bounds(cx, ...)` / `Window::window_bounds()` returns
`WindowBounds::{Windowed(b), Maximized(b), Fullscreen(b)}` where `b` is the restore size, which
already gives "last normal bounds". Debounce with a replaced `Task` that sleeps 500 ms.
Use `cx.displays()` (`PlatformDisplay::visible_bounds()` if available, else `bounds()`) for the
screen list.

`WindowPlacement` (pure, port exactly):
```
TITLE_STRIP_HEIGHT = 40; MIN_GRAB_WIDTH = 100
title_bar_on_screen(b, screens):
  strip = min(40, b.h); left = b.x + 0.25*b.w; right = b.x + 0.75*b.w
  needed = min(100, right - left)
  any screen s: b.y >= s.minY && b.y + strip <= s.maxY
                && (min(right, s.maxX) - max(left, s.minX)) >= needed
clamp_into(b, s):
  w = min(b.w, s.w); h = min(b.h, s.h)
  x = min(max(b.x, s.minX), s.maxX - w); y = min(max(b.y, s.minY), s.maxY - h)
```

#### 2.3.3 Minimum size (`ScaledMinSize`, pure core)
```
SCREEN_MARGIN = 24
clamp(base, scale, available) =
  wanted = max(0, base*scale)
  if !(available > 0): wanted else min(wanted, max(0, available - 24))
apply(window, baseW, baseH): bounds = visual bounds of the screen under the window centre
  (else any screen it overlaps, else primary); set min = clamp(base, scale, bounds.w/h)
follow(window, baseW, baseH): apply now, on every scale change (weak listener), and on every show
```
Reason: the login screen's 760 px min is 950 px at 125 %, taller than a 768 px laptop; the
bottom (with the buttons) went off screen. The main window re-applies on screen change (see the
monitor nudge) and on scale change. Each screen's FXML root may raise the base min:
`setScene` takes `rootPane.minWidth/minHeight` when > 0 and finite.

FXML minimums: Home/Browse/Settings/Progress/LaunchProgress/Runtime 750x475 (pref 1000x800;
Runtime pref 900x600); Login 900x760 (pref 1120x860).

GPUI: `WindowOptions { window_min_size: Some(size) }` at open; for later changes use
`window.set_window_min_size(...)` if 0.2.2 exposes it, otherwise this is a gap (see open
questions).

#### 2.3.4 Windows monitor-change nudge (Windows only)
Problem: with "show taskbar buttons on the taskbar where the window is open", the taskbar icon
often fails to follow a window dragged to another monitor. After x/y changes (only while
showing and not iconified), a 200 ms debounce runs `nudgeIfMonitorChanged`:
- current = screen under a 1x1 probe at the window centre (full bounds is ambiguous when
  straddling), else primary.
- If changed from last known: re-apply min size (new display may be smaller); `setX(x+1)`; on the
  next pulse `setX(x)` (two setX in one pulse coalesce into one `WM_WINDOWPOSCHANGED`) and call
  `WindowsShellRefresh.forceFrameRefresh` (SetWindowPos with SWP_FRAMECHANGED); retry the frame
  refresh 200 ms later (first attempt is sometimes dropped by the Win11 shell mid-debounce;
  together ~70% -> near 100%).
Rust: `windows` crate, `SetWindowPos(hwnd, 0, x, y, w, h, SWP_NOZORDER|SWP_NOACTIVATE|SWP_FRAMECHANGED)`.
Whether GPUI's own window proc already triggers this must be tested; keep behind `cfg(windows)`.

#### 2.3.5 `setScene(gui)` (all on the UI thread, serialized)
Two racing navigations (double click, shortcut + callback) run one after another; each cleans up
exactly the screen it replaces.
1. If previous != new: `previous.cleanup()`; a panic/exception is logged
   (`log.guiWindow.cleanupFailed`, arg: exception class) and does not stop the swap.
2. Stop placeholder animations (first swap only; also clears the placeholder ref so a late voxel
   attach no-ops).
3. `gui.setup()`.
4. `guardCloseWhileGamesRun()` (2.3.7).
5. Min size from the root's FXML min (only if > 0 and finite), `applyScaledMinSize()`. Setting a
   min above the current size grows the window (progress -> login).
6. `injectHelpButton(gui)` unless the screen has a node with style class `helpButton`: a "?"
   button (glyph `LauncherIcons.HELP`, a11y `menu.help.title`) in the top-right cell of the
   root grid, margin 8,8,0,0; click opens `HelpWindow.show(gui.helpTopic)`.
7. macOS: reparent the single system menu bar into this scene (`SystemMenuBarManager`).
   GPUI: `cx.set_menus(...)` once per session (no reparenting needed).
8. Title `"<app name> | <screen title>"`; `forceThemeChange()`.
9. Windows custom chrome pre-paint setup on the unwrapped root (other spec).
10. `stage.setScene(scene)`; `Motion.fadeThrough(root)` (2.8); `RunningGamesWindow.followScreen(scene)`
    (moves a docked Running Games view into the new screen's wrapper).
11. macOS: hide redundant in-window brand lockup, make the navbar a drag region, hide navbar
    controls replaced by the native toolbar, `MacOsToolbarManager.setNavigationEnabled(gui.allowsToolbarNavigation())`
    (toolbar items stay visible but disabled on login/progress/runtime so sign-in cannot be
    bypassed).
12. Windows title-bar controls: caption cluster + help button in the title bar
    (`WindowsTitleBarControls.apply(root, () -> HelpWindow.show(topic))`).
13. `gui.afterShow()`.
After the swap (outside the UI block): (re)register the OS theme listener (remove the old one
first to avoid accumulation). The listener only acts when the configured theme is Automatic or
Native, and dispatches `forceThemeChange` fire-and-forget (the detector's watcher thread restarts
itself on multi-event registry changes and interrupted blocking waits surfaced as "Unable to wait
for a user interface task to complete").

GPUI port: `MainWindow` is the window's root view and holds `current: AnyView` +
`Box<dyn ScreenHandle>` (type-erased lifecycle). `set_screen` is a foreground method; background
callers use `cx.update(|cx| ...)` / `window_handle.update(...)`. The OS theme listener becomes
`cx.observe_window_appearance(window, |this, window, cx| ...)` (GPUI reports
`WindowAppearance::{Light, VibrantLight, Dark, VibrantDark}`), which is per window and dropped
with the view: no accumulation possible.

#### 2.3.6 `show()`
On the UI thread; `firstShow = !showing`; show. First show only:
- macOS: hidden-inset title bar + native toolbar, re-applied on every later `WINDOW_SHOWN`
  (a hide/show can rebuild the NSWindow and drop the toolbar; returning from a game used a raw
  show path). Also re-apply NSAppearance dark/light each time (title text went black in dark mode
  after a game return).
- Windows: install the custom-chrome window-proc subclass; re-apply the title-bar controls to the
  current scene (the first `setScene` ran before the HWND existed).
- DWM attributes once (re-firing on every navigation stuttered on focus regain): immersive dark
  mode = !light theme; backdrop Mica if native theme else none; caption + border colour = theme bg
  (native: clear to default so the title strip composites with the Mica body); native: recompute
  scene fill (2.9.4) now the HWND exists; `forceFrameRefresh`; `RedrawWindow(RDW_INVALIDATE|RDW_ERASE|RDW_FRAME)`
  (SWP_FRAMECHANGED alone left a white client rectangle on native theme until the next repaint).

GPUI: `WindowOptions { titlebar: Some(TitlebarOptions { appears_transparent: true,
traffic_light_position: Some(point(px(12.), px(18.))), title }), window_background:
WindowBackgroundAppearance::{Opaque|Transparent|Blurred|MicaBackdrop?}, kind: WindowKind::Normal,
is_resizable, window_min_size, app_id: Some("mica-minecraft-launcher"), window_decorations }`.
GPUI already draws client-side decorations on Linux and supports a transparent title bar on
macOS and Windows; most of the JavaFX chrome hacks (hidden inset, custom caption buttons, navbar
drag region via `WindowControlArea::Drag`) map to GPUI built-ins. The DWM caption-colour and
Mica calls that GPUI does not expose go through `raw-window-handle` -> HWND -> `windows` crate.

#### 2.3.7 Close guard
`guardCloseWhileGamesRun()` wraps the screen's own close handler in a `CloseGuard(screenHandler,
body)`. If the screen set none, unwrap the previous guard's handler (so the question is not
asked twice). On close:
- no active sessions: run the screen handler (if any) and return (JavaFX default close if none);
- else consume the event; spawn a task: `confirmQuitWhileGamesRun()`; false -> keep open; true ->
  on the UI thread run the screen handler with a fresh close event, or `LauncherCore.closeApp()`
  when there is none.
Rust: `window.on_window_should_close(cx, |window, cx| -> bool)` returns false always and drives an
async flow: `if registry.active().is_empty() { screen.on_close_requested() } else { spawn(confirm
then screen.on_close_requested or close_app) }`.

Screen close handlers in this slice:
- Home: consume, `exitBtn.fire()` -> `requestQuit()`.
- Browse, Runtime, Progress, LaunchProgress: consume, `LauncherCore.closeApp()`.
- Login: consume, fire the sign-in panel's dismiss button ("Exit") -> `closeApp()`.
- Settings: consume; spawn: if `hasUnsavedChanges()`: question `dialog.settings.unsavedOnClose.{title,header,body}`,
  buttons `...button.saveAndClose` (1: `persistSettings()` on this thread, then `closeApp`),
  `...button.closeWithoutSaving` (2: `closeApp`), cancel (0: stay); else `closeApp`.

#### 2.3.8 `cleanup()`
Remove scale listener; take and null the current screen and `cleanup()` it (logged on failure;
without this every in-process restart leaked the last screen); stop + null the bounds debouncer
and flush bounds; stop + null the monitor debouncer; remove the theme listener (silent warn on
failure).

#### 2.3.9 Cold-start placeholder
Three 36x36 isometric cubes in an HBox (spacing 10, centred), SVG faces:
top `M 18 0 L 36 9 L 18 18 L 0 9 Z`, left `M 0 9 L 18 18 L 18 36 L 0 27 Z`,
right `M 36 9 L 18 18 L 18 36 L 36 27 Z`. Colours (top, left, right):
dirt `#8B6A3F #5C4322 #3F2E14`, grass `#6FCF3D #3C8527 #2A6B1E`, stone `#A8AEB8 #6B7280 #4B5563`.
Animation: translateY 0 -> -8, 500 ms, auto-reverse, infinite, ease-both, delays 0/150/300 ms.
Rust: a `Placeholder` view shown until the first real screen; paint the faces with
`gpui::PathBuilder` (or a small SVG asset) and animate with `with_animation(... .repeat())`.

### 2.4 Navigation gate and keyboard shortcuts

Callers outside a screen (macOS menu bar, keyboard shortcuts, dock / tray / jump-list menus,
macOS toolbar, crash-suggestion buttons) call `mayNavigateAway()` off the UI thread before
`goTo*`. Launch entry points (`LauncherActions.readyToLaunch`, `LauncherUriHandler`) call
`mayLeaveForLaunch()`.

`KeyboardShortcutManager.installGlobalShortcuts(scene, helpTopicSupplier)` (event filter, so it
coexists with per-screen handlers). Installed by Home, Browse, Settings, Runtime (not Login /
progress):
- `F1` or `Shortcut+Shift+/` -> consume; `HelpWindow.show(topic ?? GETTING_STARTED)`.
- Only with Shortcut (Cmd on macOS, Ctrl elsewhere) and **without Alt** (Alt+Cmd+letter is a text
  accelerator):
  - `Shortcut+,` -> Settings; `Shortcut+L` -> Browse; `Shortcut+E` -> Editor (blank);
  - `Shortcut+Shift+M` -> Home (bare Cmd+M is macOS Minimize; never override it).
- `navigate(target)`: spawn task; if `!mayNavigateAway()` return; run target; IOException logs
  `log.shortcuts.navigationFailed`.

Per-screen (Home): `Enter` plays the first card whose Play is enabled (after the default
last-played sort that is the last-played pack); `F5` -> refresh; `Shortcut+F` (no Alt, no Shift)
focuses the search field. Browse: `Shortcut+F` focuses search.

macOS menu bar (`SystemMenuBarManager`, macOS only, one bar per session): Modpacks (Home
`Shortcut+Shift+H`, Browse `Shortcut+L`, Refresh `Shortcut+R`, separator, Play Recent submenu with
`Shortcut+<digit>` items or `menu.modpacks.playRecent.empty`), Game (Runtimes, Editor), View
(Theme radio submenu labelled via `SettingsOptionLabels.theme`, Discord RPC check, Keep games
window open check, Pack backgrounds check), Window (Minimize `Shortcut+M`, Zoom), Help (Help
`Shortcut+Shift+/`, Website). Keys `menu.*`.

GPUI port: define actions (`actions!(mmcl, [OpenSettings, OpenBrowse, OpenEditor, OpenHome,
OpenHelp, Refresh, FocusSearch, PlayLast, Quit])`) and bind with `cx.bind_keys([
KeyBinding::new("secondary-,", OpenSettings, None), KeyBinding::new("secondary-l", ...),
KeyBinding::new("secondary-e", ...), KeyBinding::new("secondary-shift-m", ...),
KeyBinding::new("f1", OpenHelp, None), KeyBinding::new("secondary-shift-/", OpenHelp, None),
KeyBinding::new("f5", Refresh, Some("Home")), KeyBinding::new("enter", PlayLast, Some("Home")),
KeyBinding::new("secondary-f", FocusSearch, Some("Home || Browse"))])`. `secondary` = Cmd on
macOS, Ctrl elsewhere, which matches JavaFX "shortcut". Screens that do not install global
shortcuts (Login, progress screens) must not get them: put the global bindings in a key context
(`"Navigable"`) that only those four screens set on their root. Text inputs must not swallow
these chords: GPUI dispatches from the focused element up, so a `TextInput` that binds
`secondary-l` would win; avoid conflicts. The menu bar is `cx.set_menus(vec![Menu { name,
items: vec![MenuItem::action(...)...] }])`, built once; the Play Recent submenu is rebuilt by
calling `set_menus` again after each launch.

### 2.5 Interface scale (`UiScale`, `ScaledRoot`)

- Offered percents `[50, 75, 100, 125, 150]`, default 100. `setPercent(p)` snaps to the nearest
  offered value. `nearest()` starts from 100 and walks the list in order, replacing the best only
  on a strictly smaller distance, so a tie keeps 100 if it is one side of the tie, else the lower
  value (e.g. 62 -> 50, 63 -> 75, 87 -> 75, 88 -> 100, 112 -> 100, 113 -> 125; out-of-range values
  clamp to 50 / 150). Config key `uiScalePercent`.
- `ScaledRoot` lays the content out at `window size / scale` and draws it scaled, so text and
  vectors stay sharp and hit-testing follows. It can also host a bottom dock (Running Games).
- Popups (tooltips, menus, combo lists, dialogs) are separate windows outside the zoom, so a
  generated stylesheet (data URI, null at 100 %) scales them: tooltip font 12px*s, padding
  4*s/8*s; context menu font 14*s, padding 6*s/4*s, items 8*s/12*s; combo cells 10*s/16*s, font 14*s;
  dialog pane font 14*s, button-bar buttons 6*s/16*s. Format `%.1fpx` with root locale.
- Native chrome alignment: stage min = screen min x scale; Windows caption hit regions multiply by
  it; macOS navbar inset/band divide by it so the traffic lights and the 52 pt band keep native size.

GPUI port: GPUI has no layout transform, but every `rems()` length scales with
`window.set_rem_size(...)`. Author all layout sizes in rems against a 16 px base
(`fn u(px: f32) -> Rems { rems(px / 16.0) }`) and set `rem_size = 16px * scale` on every window
(main, help, add-account, running games, wizard). Popups drawn by GPUI (tooltips, context menus,
gpui-component dropdowns) live in the same window and scale automatically, so the popup
stylesheet hack is not needed. Native message dialogs (`window.prompt`) are OS-drawn and ignore
the scale (accepted). Re-apply min size on scale change. Hard pixel sizes that must stay native
(traffic-light inset, caption buttons) stay in `px`.

### 2.6 Shared dialogs (`GUIUtilities`)

| Java | Contract | Rust |
|---|---|---|
| `JFXPlatformRun(r)` | run now if on UI thread, else post and **block** until done. If the toolkit was never started (server / TUI mode) and no GUI is intended this session, skip with a silent warn (calling `Platform.startup` there hung headless Linux shutdown forever). Interrupted waits restore the interrupt flag and log silently. | Never block the foreground. Background code posts with `AsyncApp::update` / a channel and awaits a oneshot if it needs the result. In server/TUI mode the GUI crate is not initialised and the post is a no-op. |
| `showQuestionMessage(title, header, content, b1, b2, owner) -> int` | blocking, window-modal confirmation. Buttons `[b1, b2, Cancel]`; if `b2` equals "Cancel" or the localized `dialog.button.cancel` (trimmed, case-insensitive) it **is** the cancel button and no extra Cancel is added (avoids two Cancel buttons). Returns 1 for b1, 2 for b2, 0 for closed/cancel. Content is sanitized: `SensitiveDataRedactor.redact`, newlines collapsed to spaces, whitespace squeezed, trimmed, capped at 600 chars with `…`. Themed (theme sheets on the dialog, window icon, title-bar dark mode, grow-in animation, scale). | `async fn ask(window, Question { title, header, body, primary, secondary }) -> Answer { Closed, Primary, Secondary }` via `window.prompt(PromptLevel::Info, header, Some(body), &[primary, secondary, cancel])`. GPUI prompts return the index of the clicked answer; map the cancel index and dismissal to `Closed`. |
| `showQuestionMessageMultiline` | same but keeps line breaks: normalize CRLF, right-trim lines, collapse 3+ newlines to 2, trim, cap 4000 chars; wider pane (min 560, pref 640, label pref 600, wrap) | same, `body` multi-line. If native prompts truncate long text, use an in-window modal (gpui-component `Dialog`) instead. |
| `showErrorMessage`, `showErrorMessageMultiline`, `showWarningMessage`, `showErrorMessageRetry(text, owner, retryText) -> bool` | blocking alerts | `PromptLevel::Critical/Warning` |
| `isLightChrome(theme)` | `theme == Light || (theme == Native && !osDark)` | `theme::is_light_chrome` |
| `openExternalUrl(url)` | only http(s) after `UrlValidation.sanitizedHttpUrl`; refuse others with `log.modpack.refuseNonHttpUrl`; open on a worker | `cx.open_url(&url)` after the same validation |
| `openModpackWebsite(pack)` | `openExternalUrl(pack.packURL)` | |
| `openFolder(file)` | open if exists | `cx.open_with_system(path)` / `open` crate |

The "do the work off the UI thread, then ask with a blocking dialog" pattern appears everywhere
in this slice. In Rust write it as one `cx.spawn(async move |this, cx| { let answer =
ask(...).await; ... cx.background_spawn(work).await; this.update(cx, ...) })`.

### 2.7 Tooltips, icon buttons, misc shared controls

- `TooltipManager.install(node, text)`: style class `mcl-tooltip`, show delay 400 ms, show
  duration 10 s. GPUI: `.tooltip(Tooltip::text(...))` (gpui-component) or a custom tooltip view;
  GPUI's default delay is about 500 ms (accept).
- `IconButtons.decorate(btn, glyph, label)`: 40 px round button, glyph only, label moved into the
  tooltip and the accessible text. Used for pagination prev/next.
- `EmptyStates.badge(glyph)`: large tonal circle with a glyph above an empty-state heading.
- `SearchFields.decorate(field)`: full pill with a leading magnifier, darker than the surface.
- `FilterChip`: toggle; unselected outlined, selected filled with `-md-active-indicator` and a
  leading check.
- `OfflineIndicator.applyTo(label)`: visible+managed iff `NetworkUtilities.isOffline()`, tooltip
  `tooltip.offline`. Evaluated once at setup (not live).
- `AnnouncementBanners.show(label, row, text)`: shown iff text non-blank; fixed height 40 (min =
  pref = max), else 0 (explicit sizes because computed sizes did not resolve across navigations).
- `LauncherIcons`: SVG path strings (24x24 viewbox, generated by `tools/launcher-icons/build_icons.py`):
  HELP, REFRESH, BROWSE, SYSTEM, DOWNLOAD, CLOSE, ANNOUNCEMENT, PREVIOUS, NEXT, SEARCH, GAME,
  EMPTY_BOX, RUNTIME, SECURITY, LOCK, PEOPLE, ACCOUNT, APPEARANCE, ADVANCED, NETWORK, DISCORD, RGB,
  ABOUT, ... Port by emitting `assets/icons/*.svg` from the same script and rendering with
  `svg().path("icons/help.svg")` through an `AssetSource`.
- `WindowIcons.apply(stage)`: every window and dialog gets the app icon (decoded once).
- Brand logo: `gui/components/micaBrandLogo.fxml` (80x84 vector group, raw path coords ~800 px)
  and `micaBrandLogo-small.fxml` (pre-scaled for a 36x36 navbar slot). Export both as SVG assets.

### 2.8 Motion (`Motion`)

Tokens: `STANDARD = cubic-bezier(0.2, 0, 0, 1)`, `EMPHASIZED_DECELERATE = (0.05, 0.7, 0.1, 1)`,
`EMPHASIZED_ACCELERATE = (0.3, 0, 0.8, 0.15)`; durations SHORT 150, MEDIUM 300, LONG 450 ms.
- `enter(node)`: 300 ms fade 0->1 + scale 0.96->1, emphasized decelerate (dialogs, detail modal).
- `exit(node, done)`: 150 ms fade -> 0 + scale -> 0.96, emphasized accelerate, then reset and done.
- `fadeThrough(node)`: 220 ms fade 0->1 + translateY 8->0, emphasized decelerate (every screen
  swap, every Settings category switch, Running Games tabs).
- Reduce motion (`reduceMotion`, default false): transitions become instant (reset node: opacity
  1, scale 1, translate 0); ambient animation stops (progress voxels, wavy wave, loading shape).
Home's first show per process also fades the root 0->1 over 180 ms (only once:
`mainMenuShownOnce`).

GPUI: `div().with_animation(ElementId, Animation::new(Duration::from_millis(220)).with_easing(cubic_bezier(0.05, 0.7, 0.1, 1.0)), |el, t| el.opacity(t).top(px(8.0 * (1.0 - t))))`.
Write `fn cubic_bezier(x1, y1, x2, y2) -> impl Fn(f32) -> f32` (Newton-Raphson on x). Keying the
animation id on a per-show counter restarts it on every screen swap.

### 2.9 Theming

#### 2.9.1 Model
Every screen root gets two sheets, lowest to highest precedence:
1. `ui/ui-base.css`: theme-agnostic component styling; only `-color-*` / `-md-*` lookups.
2. A token sheet `ui/ui-tokens-<theme>.css` defining the palette on `.root`.
Every token sheet defines the same token set (`ThemeTokensTest`), text colours meet WCAG AA on
what they sit on, and `-color-popup` / `-color-popup-border` are opaque in every theme (popups
are separate windows). The `-md-*` Material 3 roles are generated from each theme's `-color-*`
values by `tools/theme-roles` (`npm run generate`); never hand-edit them.

Config `theme` (string, exact values, default `"Native (Mica)"`):
`"Automatic"`, `"Dark"`, `"Light"`, `"Blue+gray"`, `"Orange+purple"`, `"Creeper"`, `"Native (Mica)"`.
Labels: `settings.theme.option.{automatic,dark,light,blueGray,orangePurple,creeper,native}`.

Resolution (`themeStylesheetPaths(theme, osDark)`):

| Theme | Token sheet |
|---|---|
| Light | light |
| Blue+gray | bluegray |
| Orange+purple | orangepurple |
| Creeper | creeper |
| Native (Mica) | macOS / Windows: osDark ? native : native-light. **Linux: osDark ? dark : light** (no backdrop; translucent tokens would be near-invisible) |
| Automatic | osDark ? dark : light (if the OS detector failed to start, Automatic applies nothing and keeps the previous sheets) |
| anything else | dark |

Note `themeStylesheetPaths` (used by secondary windows and tests) does not apply the Linux
native fallback; the main window does (`switchToNativeTheme`). Port the main window's rule
everywhere.

`light chrome` (title bar light mode) = token sheet is `light` or `native-light`.

#### 2.9.2 Solid background per token sheet (`themeBgHex`, main window)
dark `#0C1017`, light `#FFFFFF`, bluegray `#121721`, orangepurple `#201221`, creeper `#0C130C`,
native `#14181F`, native-light `#F5F6FA`, unknown/null `#0C1017`.
Secondary windows use `themeBgHexStatic`: light `#FFFFFF`, bluegray `#0E141D`, orangepurple
`#201221`, creeper `#0C130C`, native `#1C1B1F`, native-light `#F5F6FA`, default `#0C1017`.
(The two tables disagree for bluegray and native; the token sheet's own `-color-bg` for bluegray
is `#0E141D`. Use one table in Rust: `bg = token(-color-bg)` for opaque themes, and for native use
`#14181F` (main window, under Mica) / `#1C1B1F` (secondary windows); record the discrepancy as
fixed.)

#### 2.9.3 `applyTheme(tokenSheet)` (main window)
- No-op until a real screen is installed (placeholder has no controller; an OS theme toggle
  racing startup is picked up by the next `setScene`).
- Swap sheets only if the last two root sheets are not already `[base, tokens]` (re-adding
  identical sheets re-styles the whole scene).
- Always paint the fallback: native -> `applyNativeThemeFill(bg)`; else root inline bg + scene fill
  = bg, and clear macOS vibrancy.
- If the token sheet equals the one already applied and the window is showing, stop here: plain
  navigations must not re-run the native chrome pipeline (it toggled Mica and forced a synchronous
  RedrawWindow, causing a visible flicker on every screen change).
- On a real change: backdrop NONE then (native ? MICA : NONE) (DWM caches Mica and ignores a
  duplicate request, so a dark->light flip kept the old tint); title-bar dark mode = !light;
  caption + border colour = bg (native: clear both); full repaint.

`applyNativeThemeFill(bg)`:
- macOS: root bg `rgba(0,0,0,0.001)` and transparent scene (fully transparent made JavaFX skip
  rendering and show black; 0.1 % alpha is invisible).
- Windows with Mica support (Win11 22H2+, needs a realised HWND): transparent root and scene.
- otherwise (Win10, pre-22H2, before first show): solid bg (a transparent scene with no backdrop
  paints black).
`switchToNativeTheme` on macOS applies the native tokens then `MacOsVibrancyManager.apply(stage,
osDark)` (NSVisualEffectView).

GPUI: Native maps to `WindowBackgroundAppearance::Blurred` on macOS (GPUI uses
NSVisualEffectView-style blur) and to Mica on Windows 11 22H2+ (use GPUI's Mica variant if 0.2.2
has one, else `Transparent` + `DwmSetWindowAttribute(DWMWA_SYSTEMBACKDROP_TYPE=38, DWMSBT_MAINWINDOW=2)`).
Detect Mica support by Windows build >= 22621 (do not rely on a probe that needs a realised
window). When not supported, render the native theme with its solid bg. Call
`window.set_background_appearance(...)` only on an actual theme change (same anti-flicker rule).
DWM attributes: `DWMWA_USE_IMMERSIVE_DARK_MODE=20`, `DWMWA_BORDER_COLOR=34`, `DWMWA_CAPTION_COLOR=35`
(COLORREF `0x00BBGGRR`; `DWMWA_COLOR_DEFAULT=0xFFFFFFFF` clears; `DWMWA_COLOR_NONE=0xFFFFFFFE`),
`DWMWA_SYSTEMBACKDROP_TYPE=38` (`DWMSBT_NONE=1`, `DWMSBT_MAINWINDOW=2` Mica, `DWMSBT_TABBEDWINDOW=4`).

`forceThemeChange()` also calls `HelpWindow.refreshTheme()` and `RunningGamesWindow.refreshTheme()`.
In GPUI, make the theme a `Global` (`ThemeRegistry` + `ActiveTheme`), and have every window
`cx.observe_global::<ActiveTheme>()` and `cx.notify()`; no per-window sheet juggling.

Secondary windows (`installCurrentThemeStylesheets(root)`) get the same tokens but a **solid**
background (no Mica: auxiliary windows are not set up for composition; transparency there showed
white-on-white). Their title bar mode follows `isLightChrome`. Only the help window is refreshed on
theme change; Add-account and dialogs pick the theme at creation.

#### 2.9.4 Component styling reference (from `ui-base.css`, 3139 lines)
Port as Rust style helpers, not CSS. Key rules:
- Root font: Inter 14 px (fallbacks Segoe UI Variable, Segoe UI, SF Pro Text, Roboto,
  system-ui); text `-color-text`. JavaFX picks only the first available family, so Inter and
  JetBrains Mono are bundled; "Inter SemiBold" is its own family for weight 600.
- Text roles: `.muted` -> `-color-text-muted`; `.subtle` -> `-color-text-subtle`.
- Type scale (only these sizes are allowed, `TypeScaleTest`): `heading-display` JetBrains Mono 700
  24; `heading-h1` 600 28; `heading-h2` 600 22; `heading-h3` 600 16; `type-headline-medium` 28/600;
  `type-headline-small` 24/600; `type-title-large` 22/600; `type-title-medium` 16/600;
  `type-title-small` 14/600; `type-body-large` 16; `type-body-medium` 14; `type-label-large` 14/600;
  `type-label-medium` 12/600; `type-label-small` 11; `type-body-small` 12; `type-weight-bold`
  bold; `settingsSectionTitle`
  14/600 `-md-text-primary`; `settingsHint` 12 `-md-on-surface-variant`; chips 11/600.
- Shape scale (`ShapeScale`, only these radii, `ShapeScaleTest`): EXTRA_SMALL 4, SMALL 8,
  MEDIUM 12, LARGE 16, EXTRA_LARGE 28, full pill 999. `inner(outer, inset) = max(0, outer - inset)`
  for concentric inner clips (logo clip = inner(MEDIUM, 2) = 10).
- Surfaces: `.rootPane` bg `-color-bg`; `.stripPane` (help sidebar) `-color-bg-soft` + right
  border `-color-border`; `.hero-surface` (login / progress / add-account) = `-color-bg` plus two
  radial gradients: centre (25%, 0%) radius 80% `-color-hero-stop-1` -> transparent, centre (110%,
  100%) radius 70% `-color-hero-stop-2` -> transparent (but `.rootPane.hero-surface` resets to flat
  `-color-bg`; the glow on progress screens comes from `.progressGlow`). `.card` bg
  `-md-surface-container`, radius 12, padding 16; `.card.elevated` `-md-surface-container-high`.
- Navbar / bottom bar: bg `-color-bg-soft`, padding 6 12, border `-color-border` (navbar bottom
  1 px, bottom bar top 1 px); navbar row height 52, bottom bars 40/44/52 per screen. `.navBrand`
  700 14 `-color-text`, padding 0 4. `.navDivider` 1 px `-color-border`. `.navUpdateIcon` fill
  `-color-primary` (hover `-color-primary-hover`). `.navRefreshIcon` fill `-color-text-muted`
  (hover `-color-text`).
- Buttons (all pills, radius 999, padding 6 16, 1 px border, size to their label, never a fixed
  width): default tonal `-md-surface-container-highest` / text `-md-on-surface`, hover adds layer
  `-md-state-hover-on-surface`; keyboard focus (`:focus-visible` only) border `-md-primary`;
  disabled opacity 0.38. `.primary`: `-md-primary` / `-md-on-primary`, hover layer
  `-md-state-hover-on-primary`. `.secondary`: `-md-secondary-container` /
  `-md-on-secondary-container`. `.outlinedBtn`: transparent, border `-md-outline`. `.textBtn`:
  transparent, padding 6 12, text `-md-text-primary`, hover `-md-state-hover-primary`. `.selected`:
  `-md-active-indicator` / `-md-on-active-indicator`. `.danger` / `.dangerZone`: `-md-error` /
  `-md-on-error`. `.errorTonalBtn`, `.errorTextBtn`, `.tonalBtn`, `.heroCardSecondaryBtn` variants
  likewise. `.connectedGroup` = M3 connected button group (the UI-scale selector).
- `.helpButton` 30x30 round, glyph `.helpButtonGlyph`.
- Chips: `.stat-chip` transparent bg, 1 px `-md-outline-variant` border, radius 8, text
  `-md-on-surface-variant` 11/600, padding 3 10; variants `-success`, `-warn`, `-danger`, `-info`
  colour the border/text with the status role. `.chip-offline` pill bg `-color-danger`, text
  `-color-text-on-primary`, padding 2 10, 11/600.
- `.banner-announcement`: bg `-color-warning`, text `-color-text-inverse` 600, padding 4 12; icon
  `LauncherIcons.ANNOUNCEMENT`.
- Hero card: `.heroCardShell` bg `-md-surface-container-high`, radius 16 (hover lifts);
  `.heroBackgroundDefaultVanilla` linear gradient to bottom right `#4A8FD0 0%, #8FC8E8 22%, #E0CC7E
  42%, #5BA84E 62%, #7A5C36 100%`; `.heroBackgroundDefaultForge` radial (50% 95%, r 65%)
  `rgba(255,107,53,0.50) 0%, rgba(255,107,53,0.20) 35%, transparent 60%` over linear to bottom
  `#2B2D31 0%, #1F2125 60%, #3D2D1F 100%`; `.heroCardImageVeil` bottom veil; `.heroPackLogoContainer`
  rounded frame (removed by `.logoTransparent`).
- Progress: `.progressCard` bg `-md-surface-container-high`, radius 28, drop shadow (gaussian,
  `-color-shadow`, radius 32, spread 0.18, offset y 12). `.progressGlow`, `.voxelShadow`,
  `.progressTitle`, `.progressStep`, `.progressDetail`, `.progressChip`, `.blockyHorizon*`,
  `.stepBadge-*`, `.wavy-progress .wavy-*`, `.loadingIndicator*`.
- Sign-in: `.signInCard`, `.signInAside`, `.signInEyebrow`, `.signInFrame`, `.signInOverlay`,
  `.signInPointIcon` (36 px disc), `.signInPointIconGlyph`, `.signInPointTitle`, `.signInStatus`.
- Settings: `.settingsNavGroup`, `.settingsNavIcon.icon-<hue>` (hues blue, cyan, green, yellow,
  orange, pink, purple, grey -> `-md-icon-<hue>` bg, `-md-on-icon-<hue>` glyph),
  `.settingsNavTitle`, `.settingsNavSummary`, `.settingsRow` + position classes `tileOnly`,
  `tileFirst`, `tileMiddle`, `tileLast` (outer corners rounded, inner tight), `.settingsGroup`,
  `.tileList` / `.tileListCell` / `.tileListBadge` / `.tileListGlyph` (runtime list).
- Accounts: `.accountChip`, `.accountRow`, `.accountStatusWarning`.
- Switch (`MaterialSwitchSkin` on every toggle): 3/4 of M3 mobile 52x32 (39x24), outlined track +
  handle with X when off, filled primary track + handle with check when on, same handle size, label
  left / switch right edge, emphasized easing slide, icon cross-fade, handle swells while pressed;
  Space/Enter toggle. Native themes paint the off-X with `-color-popup` (the translucent role
  vanished).
- Scrollbars, tooltips (`mcl-tooltip`), context menus, combo popups, tables, tab panes restyled at
  the end of the sheet using `-color-popup`, `-color-popup-border`.
- Native-only overrides (in the native token sheets): `.rootPane` and `.rootPane.hero-surface`
  transparent; the detail modal card and its sections forced opaque (`#1C2230`, `#252B3A`,
  `#161A23`, quick actions `#2B3144` hover `#353C52`, borders `rgba(220,230,245,0.18)`; light
  variant equivalents in `ui-tokens-native-light.css`).

`InlineStyleGuardTest` forbids inline font/text-colour/padding/radius/literal-colour styles and
FXML `style=` attributes. Rust equivalent: all colours come from `Theme` fields; add a test that
greps `crates/mmcl-ui/src` for `rgb(0x` / `hsla(` literals outside `theme/` (allow-list the voxel
cube colours and the default gradients, which are brand constants).

#### 2.9.5 Token values per theme
Generated from the seven token sheets (`.root` block, comments stripped). Where `-color-popup`
names `-md-surface-container-high`, it resolves to that theme's value in the same table. `white`
is `#FFFFFF`. The native sheets add the opaque overrides listed at the end of 2.9.4. Embed these
as `const` tables in `mmcl-theme` (or generate them at build time from the CSS with a tiny
parser, so a `tools/theme-roles` regeneration flows into Rust).

| token | dark | light | bluegray | orangepurple | creeper | native | native-light |
|---|---|---|---|---|---|---|---|
| `-color-bg` | #0C1017 | white | #0E141D | #201221 | #0C130C | rgba(0, 0, 0, 0.0) | rgba(255, 255, 255, 0.0) |
| `-color-bg-soft` | #10151E | #F8F9FC | #141B26 | #29192A | #121C12 | rgba(0, 0, 0, 0.0) | rgba(255, 255, 255, 0.0) |
| `-color-surface` | #141924 | #F5F6FA | #1A2230 | #342136 | #192419 | rgba(255, 255, 255, 0.05) | rgba(0, 0, 0, 0.05) |
| `-color-surface-hover` | #1A202E | #EBEEF4 | #232C3D | #402A41 | #222F22 | rgba(255, 255, 255, 0.10) | rgba(0, 0, 0, 0.10) |
| `-color-surface-elevated` | #1E2533 | white | #232C3D | #49304B | #2A372A | rgba(255, 255, 255, 0.08) | rgba(0, 0, 0, 0.07) |
| `-color-overlay` | rgba(0, 0, 0, 0.55) | rgba(15, 23, 42, 0.32) | rgba(0, 0, 0, 0.55) | rgba(0, 0, 0, 0.55) | rgba(0, 0, 0, 0.65) | rgba(0, 0, 0, 0.55) | rgba(0, 0, 0, 0.40) |
| `-color-border` | rgba(220, 224, 240, 0.10) | rgba(15, 23, 42, 0.10) | rgba(180, 200, 235, 0.12) | rgba(240, 222, 240, 0.12) | rgba(140, 220, 140, 0.10) | rgba(220, 230, 245, 0.26) | rgba(15, 23, 42, 0.18) |
| `-color-border-strong` | rgba(220, 224, 240, 0.18) | rgba(15, 23, 42, 0.18) | rgba(180, 200, 235, 0.22) | rgba(240, 222, 240, 0.20) | rgba(140, 220, 140, 0.18) | rgba(220, 230, 245, 0.40) | rgba(15, 23, 42, 0.30) |
| `-color-divider` | rgba(220, 224, 240, 0.06) | rgba(15, 23, 42, 0.06) | rgba(180, 200, 235, 0.06) | rgba(240, 222, 240, 0.06) | rgba(140, 220, 140, 0.06) | rgba(220, 230, 245, 0.14) | rgba(15, 23, 42, 0.10) |
| `-color-text` | #EDEFF2 | #151C28 | #E7E9EE | #F4EBF3 | #EEF0EA | #F2F4F8 | #151C28 |
| `-color-text-muted` | #A4AAB7 | #4B566C | #A8AFBD | #C4ABC0 | #ABBDA8 | #B8BFCC | #4B566C |
| `-color-text-subtle` | #737B8C | #737F96 | #7C8598 | #9F7F99 | #7B9376 | #828B9C | #737F96 |
| `-color-text-inverse` | #0C1017 | white | #070809 | #201221 | #0C130C | #14181F | #FFFFFF |
| `-color-text-on-primary` | #0C1017 | white | white | #36003C | #0C130C | #14181F | white |
| `-color-primary` | #6FCF3D | #3C8527 | #0668E1 | #D257DB | #43D22D | #6FCF3D | #3C8527 |
| `-color-primary-hover` | #82DA52 | #347319 | #0059B3 | #D970E1 | #61DA4E | #82DA52 | #347319 |
| `-color-primary-pressed` | #9BE236 | #2C621C | #99CCFF | #E682ED | #74E363 | #9BE236 | #2C621C |
| `-color-primary-soft` | rgba(111, 207, 61, 0.18) | rgba(60, 133, 39, 0.10) | rgba(6, 104, 225, 0.22) | rgba(190, 95, 200, 0.20) | rgba(95, 210, 70, 0.20) | rgba(111, 207, 61, 0.22) | rgba(60, 133, 39, 0.14) |
| `-color-primary-soft-hov` | rgba(111, 207, 61, 0.28) | rgba(60, 133, 39, 0.18) | rgba(6, 104, 225, 0.32) | rgba(190, 95, 200, 0.28) | rgba(95, 210, 70, 0.30) | rgba(111, 207, 61, 0.32) | rgba(60, 133, 39, 0.22) |
| `-color-focus-ring` | rgba(111, 207, 61, 0.50) | rgba(60, 133, 39, 0.45) | rgba(6, 104, 225, 0.55) | rgba(190, 95, 200, 0.50) | rgba(95, 210, 70, 0.50) | rgba(111, 207, 61, 0.55) | rgba(60, 133, 39, 0.45) |
| `-color-secondary` | #7A5C36 | #7A5C36 | #99CCFF | #F68D31 | #9BE236 | #7A5C36 | #7A5C36 |
| `-color-secondary-hover` | #8E6E44 | #66492A | #B3D9FF | #FA9D4C | #ABEC51 | #8E6E44 | #66492A |
| `-color-secondary-soft` | rgba(122, 92, 54, 0.20) | rgba(122, 92, 54, 0.14) | rgba(153, 204, 255, 0.18) | rgba(245, 145, 60, 0.20) | rgba(180, 230, 80, 0.20) | rgba(122, 92, 54, 0.22) | rgba(122, 92, 54, 0.14) |
| `-color-success` | #3C8527 | #3C8527 | #3BCE85 | #51CD7A | #43D22D | #3C8527 | #3C8527 |
| `-color-warning` | #F4C025 | #C2940A | #F7B23B | #F7B23B | #F9C31F | #F4C025 | #C2940A |
| `-color-warning-soft` | rgba(255, 196, 38, 0.18) | rgba(217, 158, 0, 0.14) | rgba(255, 178, 38, 0.18) | rgba(255, 178, 38, 0.18) | rgba(255, 196, 38, 0.18) | rgba(255, 196, 38, 0.22) | rgba(217, 158, 0, 0.14) |
| `-color-danger` | #F53D3D | #DA0B0B | #F04C4C | #F34949 | #F42A2A | #F53D3D | #DA0B0B |
| `-color-danger-hover` | #F65A5A | #BD0A0A | #F36868 | #F56666 | #F65151 | #F65A5A | #BD0A0A |
| `-color-danger-soft` | rgba(255, 86, 86, 0.16) | rgba(220, 38, 38, 0.10) | rgba(238, 90, 90, 0.16) | rgba(238, 86, 86, 0.16) | rgba(240, 80, 80, 0.16) | rgba(255, 86, 86, 0.20) | rgba(220, 38, 38, 0.10) |
| `-color-shadow` | rgba(0, 0, 0, 0.55) | rgba(15, 23, 42, 0.18) | rgba(0, 0, 0, 0.55) | rgba(0, 0, 0, 0.55) | rgba(0, 0, 0, 0.65) | rgba(0, 0, 0, 0.22) | rgba(15, 23, 42, 0.18) |
| `-color-shadow-soft` | rgba(0, 0, 0, 0.32) | rgba(15, 23, 42, 0.08) | rgba(0, 0, 0, 0.32) | rgba(0, 0, 0, 0.32) | rgba(0, 0, 0, 0.40) | rgba(0, 0, 0, 0.12) | rgba(15, 23, 42, 0.10) |
| `-color-hero-stop-1` | rgba(111, 207, 61, 0.16) | rgba(60, 133, 39, 0.10) | rgba(6, 104, 225, 0.18) | rgba(190, 95, 200, 0.18) | rgba(95, 210, 70, 0.16) | rgba(111, 207, 61, 0.18) | rgba(60, 133, 39, 0.10) |
| `-color-hero-stop-2` | rgba(122, 92, 54, 0.10) | rgba(122, 92, 54, 0.06) | rgba(153, 204, 255, 0.10) | rgba(245, 145, 60, 0.10) | rgba(180, 230, 80, 0.08) | rgba(122, 92, 54, 0.10) | rgba(122, 92, 54, 0.06) |
| `-color-hero-stop-3` | rgba(0, 0, 0, 0) | rgba(255, 255, 255, 0) | rgba(0, 0, 0, 0) | rgba(0, 0, 0, 0) | rgba(0, 0, 0, 0) | rgba(0, 0, 0, 0) | rgba(255, 255, 255, 0) |
| `-color-popup` | -md-surface-container-high | white | -md-surface-container-high | -md-surface-container-high | -md-surface-container-high | #1C2230 | white |
| `-color-popup-border` | rgba(220, 224, 240, 0.12) | rgba(15, 23, 42, 0.15) | rgba(220, 224, 240, 0.12) | rgba(220, 224, 240, 0.12) | rgba(220, 224, 240, 0.12) | rgba(220, 224, 240, 0.12) | rgba(15, 23, 42, 0.15) |
| `-md-primary` | #6FCF3D | #3C8527 | #0668E1 | #D257DB | #43D22D | #6FCF3D | #3C8527 |
| `-md-on-primary` | #0C1017 | #FFFFFF | #FFFFFF | #36003C | #0C130C | #14181F | #FFFFFF |
| `-md-primary-container` | #1E5200 | #A7F78A | #004397 | #7E008A | #065300 | #1E5200 | #A7F78A |
| `-md-on-primary-container` | #97FB63 | #032100 | #D8E2FF | #FFD6FA | #78FF5D | #97FB63 | #032100 |
| `-md-secondary` | #7A5C36 | #7A5C36 | #99CCFF | #F68D31 | #9BE236 | #7A5C36 | #7A5C36 |
| `-md-on-secondary` | #FFFFFF | #FFFFFF | #001D33 | #301400 | #112000 | #FFFFFF | #FFFFFF |
| `-md-secondary-container` | #5C421E | #FFDDB7 | #034A76 | #703800 | #304F00 | #5C421E | #FFDDB7 |
| `-md-on-secondary-container` | #FFDDB7 | #2A1700 | #CEE5FF | #FFDCC5 | #AFF74B | #FFDDB7 | #2A1700 |
| `-md-error` | #F53D3D | #DA0B0B | #F04C4C | #F34949 | #F42A2A | #F53D3D | #DA0B0B |
| `-md-on-error` | #410003 | #FFFFFF | #410004 | #410004 | #000000 | #410003 | #FFFFFF |
| `-md-error-container` | #930011 | #FFDAD5 | #930015 | #930014 | #93000B | #930011 | #FFDAD5 |
| `-md-on-error-container` | #FFDAD6 | #410000 | #FFDAD7 | #FFDAD7 | #FFDAD6 | #FFDAD6 | #410000 |
| `-md-success` | #3C8527 | #3C8527 | #3BCE85 | #51CD7A | #43D22D | #3C8527 | #3C8527 |
| `-md-on-success` | #FFFFFF | #FFFFFF | #002110 | #00210C | #012200 | #FFFFFF | #FFFFFF |
| `-md-success-container` | #105300 | #A7F78A | #00522F | #005227 | #065300 | #105300 | #A7F78A |
| `-md-on-success-container` | #A7F78A | #032100 | #70FCAE | #80FBA3 | #78FF5D | #A7F78A | #032100 |
| `-md-warning` | #F4C025 | #C2940A | #F7B23B | #F7B23B | #F9C31F | #F4C025 | #C2940A |
| `-md-on-warning` | #251A00 | #251A00 | #281800 | #281800 | #251A00 | #251A00 | #251A00 |
| `-md-warning-container` | #594400 | #FFDF9B | #614000 | #614000 | #594400 | #594400 | #FFDF9B |
| `-md-on-warning-container` | #FFDF95 | #251A00 | #FFDDB0 | #FFDDB0 | #FFDF96 | #FFDF95 | #251A00 |
| `-md-tertiary` | #A0CFCF | #386668 | #DFBBDE | #F6B8AB | #A0CFD2 | #A0CFCF | #386668 |
| `-md-on-tertiary` | #003737 | #FFFFFF | #402843 | #4C261D | #003739 | #003737 | #FFFFFF |
| `-md-tertiary-container` | #1E4E4E | #BCEBED | #583E5A | #673B32 | #1E4D50 | #1E4E4E | #BCEBED |
| `-md-on-tertiary-container` | #BBEBEB | #002021 | #FCD7FB | #FFDAD3 | #BCEBEE | #BBEBEB | #002021 |
| `-md-surface` | #0C1017 | white | #0E141D | #201221 | #0C130C | rgba(0, 0, 0, 0.0) | rgba(255, 255, 255, 0.0) |
| `-md-surface-container-lowest` | #050914 | #FFFFFF | #070F1C | #1D0C20 | #050E05 | rgba(255, 255, 255, 0.02) | rgba(0, 0, 0, 0.02) |
| `-md-surface-container-low` | #141924 | #F8F9FD | #141C2A | #2B182D | #121C12 | rgba(255, 255, 255, 0.05) | rgba(0, 0, 0, 0.05) |
| `-md-surface-container` | #181D28 | #F2F3F7 | #18202E | #2F1C31 | #162016 | rgba(255, 255, 255, 0.08) | rgba(0, 0, 0, 0.08) |
| `-md-surface-container-high` | #222732 | #EDEEF2 | #232B39 | #3A273C | #202B20 | rgba(255, 255, 255, 0.11) | rgba(0, 0, 0, 0.11) |
| `-md-surface-container-highest` | #2D323E | #E7E8EC | #2D3544 | #463247 | #2A362A | rgba(255, 255, 255, 0.14) | rgba(0, 0, 0, 0.14) |
| `-md-text-primary` | #6FCF3D | #2E771A | #6A9CFF | #EE71F6 | #43D22D | #6FCF3D | #266F12 |
| `-md-on-surface` | #EDEFF2 | #151C28 | #E7E9EE | #F4EBF3 | #EEF0EA | #F2F4F8 | #151C28 |
| `-md-on-surface-variant` | #A4AAB7 | #4B566C | #A8AFBD | #C4ABC0 | #ABBDA8 | #B8BFCC | #4B566C |
| `-md-outline` | #8C909F | #75777B | #8991A2 | #A289A2 | #879485 | #8B90A2 | #767777 |
| `-md-outline-variant` | #424753 | #C5C6CA | #3F4756 | #554056 | #3E4A3D | #414756 | #C6C6C7 |
| `-md-inverse-surface` | #DEE2F2 | #2E3134 | #DBE2F6 | #F7D9F6 | #D9E6D5 | #DDE2F5 | #2F3131 |
| `-md-inverse-on-surface` | #2B303C | #EFF1F5 | #29313F | #3D2A3F | #283327 | #2A303F | #F0F1F1 |
| `-md-inverse-primary` | #2A6C00 | #8CDA70 | #0059C5 | #9E22AA | #0B6E00 | #2A6C00 | #8CDA70 |
| `-md-scrim` | #000000 | #000000 | #000000 | #000000 | #000000 | #000000 | #000000 |
| `-md-active-indicator` | #384C2C | #CFEBC0 | #394664 | #5A3D59 | #364D2E | #384C2C | #CFEBC0 |
| `-md-on-active-indicator` | #D1EABE | #0C2006 | #D8E2FF | #FED7F9 | #CFEBC1 | #D1EABE | #0C2006 |
| `-md-state-hover-on-surface` | rgba(237, 239, 242, 0.08) | rgba(21, 28, 40, 0.08) | rgba(231, 233, 238, 0.08) | rgba(244, 235, 243, 0.08) | rgba(238, 240, 234, 0.08) | rgba(242, 244, 248, 0.08) | rgba(21, 28, 40, 0.08) |
| `-md-state-pressed-on-surface` | rgba(237, 239, 242, 0.1) | rgba(21, 28, 40, 0.1) | rgba(231, 233, 238, 0.1) | rgba(244, 235, 243, 0.1) | rgba(238, 240, 234, 0.1) | rgba(242, 244, 248, 0.1) | rgba(21, 28, 40, 0.1) |
| `-md-state-hover-primary` | rgba(111, 207, 61, 0.08) | rgba(60, 133, 39, 0.08) | rgba(6, 104, 225, 0.08) | rgba(210, 87, 219, 0.08) | rgba(67, 210, 45, 0.08) | rgba(111, 207, 61, 0.08) | rgba(60, 133, 39, 0.08) |
| `-md-state-pressed-primary` | rgba(111, 207, 61, 0.1) | rgba(60, 133, 39, 0.1) | rgba(6, 104, 225, 0.1) | rgba(210, 87, 219, 0.1) | rgba(67, 210, 45, 0.1) | rgba(111, 207, 61, 0.1) | rgba(60, 133, 39, 0.1) |
| `-md-state-hover-on-primary` | rgba(12, 16, 23, 0.08) | rgba(255, 255, 255, 0.08) | rgba(255, 255, 255, 0.08) | rgba(54, 0, 60, 0.08) | rgba(12, 19, 12, 0.08) | rgba(20, 24, 31, 0.08) | rgba(255, 255, 255, 0.08) |
| `-md-state-pressed-on-primary` | rgba(12, 16, 23, 0.1) | rgba(255, 255, 255, 0.1) | rgba(255, 255, 255, 0.1) | rgba(54, 0, 60, 0.1) | rgba(12, 19, 12, 0.1) | rgba(20, 24, 31, 0.1) | rgba(255, 255, 255, 0.1) |
| `-md-icon-blue` | #9ACBFF | #B6D8FF | #AEC6FF | #BDC2FF | #9ACBFF | #9ACBFF | #B6D8FF |
| `-md-on-icon-blue` | #003355 | #003E67 | #002E6A | #03169C | #003355 | #003355 | #003E67 |
| `-md-icon-cyan` | #4CDADB | #5EE8E9 | #60D4FE | #60D4FE | #4CDADB | #4CDADB | #5EE8E9 |
| `-md-on-icon-cyan` | #003737 | #004343 | #003545 | #003545 | #003737 | #003737 | #004343 |
| `-md-icon-green` | #7EDC74 | #88EA83 | #59DDA6 | #96D861 | #79DC78 | #7EDC74 | #88EA83 |
| `-md-on-icon-green` | #003A05 | #00460D | #003825 | #193800 | #00390A | #003A05 | #00460D |
| `-md-icon-yellow` | #E1C618 | #F0D42B | #FFB961 | #FFB961 | #E1C618 | #E1C618 | #F0D42B |
| `-md-on-icon-yellow` | #383000 | #453B00 | #472A00 | #472A00 | #383000 | #383000 | #453B00 |
| `-md-icon-orange` | #FFB86F | #FFCB98 | #FFB5A0 | #FFB5A0 | #FFB86F | #FFB86F | #FFCB98 |
| `-md-on-icon-orange` | #4A2900 | #593200 | #5F1500 | #5F1500 | #4A2900 | #4A2900 | #593200 |
| `-md-icon-pink` | #FFB2BC | #FFC6CD | #FFACE9 | #FFADE3 | #FFB2BC | #FFB2BC | #FFC6CD |
| `-md-on-icon-pink` | #670023 | #7B002C | #5E0054 | #5F004F | #670023 | #670023 | #7B002C |
| `-md-icon-purple` | #CCBDFF | #DACEFF | #CCBDFF | #ECB1FF | #CCBDFF | #CCBDFF | #DACEFF |
| `-md-on-icon-purple` | #350097 | #4100B4 | #350097 | #520070 | #350097 | #350097 | #4100B4 |
| `-md-icon-grey` | #C2C6D5 | #D3D4D8 | #BFC7D9 | #DABED9 | #BDCAB9 | #C1C6D9 | #D4D4D4 |
| `-md-on-icon-grey` | #2B303C | #393C3F | #29313F | #3D2A3F | #283327 | #2A303F | #3A3C3C |


---

## 3. Home screen (`MCLauncherMainGui`, `mainGUI.fxml`)

### 3.1 Layout (grid, one column, rows top to bottom)

| Row | Height | Content |
|---|---|---|
| 0 navbar | 52 | brand logo (36x36 slot, `micaBrandLogo-small`), label `%main.navbar.library` ("Library", the screen name, not the app name), spacer, refresh glyph (`REFRESH`, a11y `main.navbar.refresh.a11y`, tooltip `tooltip.main.refresh`), button Browse (`%main.navbar.browse`, glyph `BROWSE`, tooltip `tooltip.main.library`), button Settings (`%main.navbar.settings`, glyph `SYSTEM`, tooltip `tooltip.main.settings`), update glyph (`DOWNLOAD`, a11y `main.navbar.update.a11y`; shown by `UpdateCheckManager.checkAndConfigureUI(node, stage)`), help (`HELP`, a11y `menu.help.title`, tooltip `tooltip.common.help`), divider, account chip: avatar 28x28 round-clipped (placeholder `no_user.png`, a11y `main.navbar.avatar.a11y`) + name label (`%main.navbar.player`) |
| 1 announcement | 0 / 40 / 60 | HBox `banner-announcement`: icon + text (wraps, centred) + close glyph (`CLOSE`, a11y `banner.dismiss`, tooltip `tooltip.main.announcementClose`) |
| 2 filters | 48 | search pill (prompt `%filter.search.prompt`, min 180 pref 220, h 32, tooltip `tooltip.main.search`), label `%main.filter.typeLabel`, type combo (min 120 pref 130, tooltip `tooltip.main.typeFilter`), label `%main.filter.sortLabel`, sort combo (min 150 pref 170, tooltip `tooltip.main.sortFilter`), spacer, filter chip `%main.filter.recentlyUpdatedOnly` (tooltip `tooltip.main.recentlyUpdated`) |
| 3 pagination | 48 (bottomBar style) | range label (muted), spacer, prev icon button (tooltip `main.pagination.prevPage`), page label, next icon button (`main.pagination.nextPage`), spacer min 20, `%main.pagination.perPageLabel`, page-size combo (80 wide, h 28, tooltip `tooltip.main.pageSize`) |
| 4 content | grow | vertical scroll (no horizontal), smooth scrolling; flow-wrap grid, hgap 20 vgap 20, centred, padding 24 32 32 32 |
| 5 status | 40 | version label (`main.versionLabel` with app version), offline chip, background-status label (muted, label-small; hidden), spacer, Running-games button (`primary`, hidden, text `main.runningGames` {count}), Exit button (`%main.navbar.exit`) |

The detail modal (other spec) is an overlay spanning the whole root grid, attached after the
first card build so it is above everything.

### 3.2 `setup()`
1. Close request -> fire Exit -> `GuiController::request_quit()`.
2. Discord presence `discordRpc.screen.selectingPack` (background).
3. `UpdateCheckManager.checkAndConfigureUI(updateIcon, window)`.
4. Announcement: dev build -> `setAnnouncementRow(main.announcement.devMode)`, else
   `setAnnouncementRow(null)`. If `AnnouncementManager.getCheckFuture()` is not done (and not dev),
   re-run `setAnnouncementRow(null)` on the UI thread when it completes (one-shot).
5. Close glyph: on click, if the shown text is non-empty
   `AnnouncementManager.dismissAnnouncementForSession(shownText)` (keyed on what the user saw, in
   case a refresh changed the manifest), then re-run `setAnnouncementRow(devExtra or null)`.
6. Settings button -> spawn: `go_to(Settings)`; presence `discordRpc.screen.settings`.
7. Browse button disabled if `AnnouncementManager.getDisableModpacksEdit()`; -> `go_to(Browse)`;
   presence `discordRpc.screen.browsing`.
8. Help -> `HelpWindow.show(MAIN_SCREEN)`.
9. Avatar and name click -> `AccountSwitcherMenu.show(anchor, openAccountSettings)` where
   `openAccountSettings` = `go_to(Settings)` then `showCategory(0)` and presence settings.
10. `bindAccountHeader()`; subscribe to `accounts()` changes -> post `bindAccountHeader` (repaint
    in place, no restart, when the default changes).
11. Running games button -> `RunningGamesWindow.showWindow()`; subscribe to
    `GameSessionRegistry` -> post `refreshRunningGames`; call it once.
12. Version label; `OfflineIndicator.applyTo(offlineLabel)`.
13. Pending auth refresh future (`MCLauncherAuthManager.getPendingRefreshFuture()`) not done ->
    show status `main.fetchLabel.signingIn` until it completes, then hide and restore the label
    text to `main.fetchLabel.loading` (so later users of the label do not inherit "Signing in…").
14. Available-packs fetch future (`GameModPackManager.getAvailableFetchFuture()`) not done -> show
    status (`main.fetchLabel.loading`) until done.
15. Installed-pack revalidate future (`getInstalledRevalidateFuture()`) not done -> `rebuildCards()`
    when it completes (packs whose manifests changed were swapped in).
16. Keys (2.4), filters + pagination (3.3), first `rebuildCards()`, smooth scroll, attach modal.

`afterShow()`: after two frames mark `ColdStartProfiler.mark("main_menu_painted")` and
`writeAndMaybeExit()`; install navbar tooltips; if `!config.quickStartCompleted` open the
quick-start wizard one frame later (avoids racing the first show); focus the root (no default
focus ring on Browse); first show of the process fades the root in over 180 ms; background
prefetch: if online, find the installed pack with the max `lastPlayedMs` (> 0) and call
`GameVersionManifest.getClientJson(mcVersion)` to warm the cache (all errors swallowed).

`cleanup()`: unsubscribe account + session listeners; `disposed = true` **first**; stop taskbar
progress (update check may have set a red overlay); unsubscribe every card (shown and pooled)
from the image-cycle clock; dispose the detail modal; dispose the VM (stop debounce, drop
callback).

### 3.3 Filter, sort, pagination (`LibraryViewModel`, pure)

```
DEFAULT_SEARCH_DEBOUNCE_MS = 120
new(page_size > 0 else panic/Err, debounce_ms clamped >= 0)
state: page_size, current_page = 1, search_query = "", sort_key = "", filters: Map<String, Value>
set_page_size(sz): if sz <= 0 || sz == page_size return; page_size = sz; page = 1; fire
set_sort_key(k): k = k ?? ""; if equal return; sort_key = k; page = 1; fire
set_search_query(q): q = q ?? ""; if equal return; query = q; page = 1; debounce_fire (restart timer)
set_filter(key, value): if key null return; prev = put; if prev == value return; page = 1; fire
set_current_page(p): page = max(1, p)   // no fire
prev_page(): if page > 1 { page -= 1; fire }
next_page(): page += 1; fire            // no clamp; clamp_and_slice fixes overshoot
clamp_and_slice(total) -> PageBounds { total_items, total_pages = max(1, ceil(total / size)),
    start = (page-1)*size, end = min(total, start + size) }   // clamps page into [1, total_pages]
search_tokens(): query.trim().to_lowercase() split on whitespace; [] when blank
get_string_filter(key, fallback) / get_bool_filter(key, fallback): fallback on missing or wrong type
dispose(): stop debounce, callback = no-op
```
In Rust: `LibraryViewModel` is plain data plus `on_state_changed: Box<dyn Fn()>`; the debounce is
driven by the owning view (store a `Task<()>` that sleeps 120 ms then calls `rebuild`; replacing
the task cancels the previous one). Filter values as `enum FilterValue { Str(String), Bool(bool) }`.

Home options (stable ids; labels via `FilterOptionLabels`: `filter.type.<id>`,
`filter.status.<id>`, `filter.sort.<id>`):
- type: `all` (default), `modpacks`, `vanilla`
- sort: `lastPlayed` (default), `mostPlayed`, `nameAz`, `nameZa`, `recentUpdate`
- `updatesOnly` bool (filter chip, default off)
- page sizes `[12, 24, 48, 72, 96]`, default **48**
Setting initial filter values happens before `on_state_changed` is wired, so setup does not
rebuild twice; `rebuildCards()` is called explicitly at the end of setup.

`buildFilteredSortedPacks()` (shared by rebuild and `selectModpack` so they never disagree):
1. installed modpacks (`GameModPackManager.getInstalledModPacks()`) + one synthetic
   `GameModPack.createVanillaModPack(id)` per `VanillaVersionManager.getInstalledVersionIds()`.
2. type filter: modpacks -> drop vanilla; vanilla -> keep only vanilla.
3. updatesOnly -> keep `isUpdateAvailable()`.
4. search: every token must be a substring of the haystack `lower(displayName + " " + packVersion +
   " " + mcVersion + " " + loaderName + " " + loaderVersion + " " + ("vanilla" | "modpack"))`
   (each part only if non-null; mc/loader getters may throw: treat as null). Rebuilt per call (pack
   metadata changes under revalidation).
5. sort (stable): `nameAz` / `nameZa` by `lower(displayName)`; `recentUpdate` by newest
   `ModPackUpdateLog.readEntries(pack)[0].timestampMs` desc (empty log or error = `i64::MIN`, sinks);
   `mostPlayed` by `totalPlayTimeMs` desc; default/`lastPlayed` by `lastPlayedMs` desc, then move the
   pack whose `packName` or `friendlyName` equals config `lastModPack` to index 0.
`displayName`: vanilla -> `packName` if non-blank; else `friendlyName`, else `packName`, else
`main.unnamedPack`.

`rebuildCards()` (UI thread; return if disposed): bounds = `clamp_and_slice(all.len)`; update
pagination (empty: range `main.pagination.empty`, page `main.pagination.pageOfPages(1,1)`; else
range `main.pagination.range(start+1, end, total)`, page `pageOfPages(current, totalPages)`; prev
disabled if page <= 1, next disabled if page >= totalPages); recycle cards into the pool; clear;
empty -> empty state; else acquire-or-construct one card per slice item and `bind`.

Empty state (VBox spacing 12, pref height 320, centred): `hasAnyInstalled` = any installed pack or
vanilla id; `filtersActive` = updatesOnly || type != all || search non-empty. If both: badge
`SEARCH`, heading `main.empty.filtered.heading`, sub `main.empty.filtered.sub.search` (search
non-empty) or `.noSearch`. Otherwise badge `GAME`, heading `main.empty.none.heading`, sub
`main.empty.none.sub`, plus a primary Browse button (glyph `BROWSE`) that fires the navbar Browse.
Sub text wraps, max width 480, centred.

`CardPool<T>`: a stack (push/poll). `recycle_all(children)` keeps only card instances (skips the
empty state / import placeholder). In GPUI a pool is unnecessary for performance (elements are
rebuilt every frame from data); what matters is the per-card *state* (image-cycle subscription,
pending single-click timer, decoded images). Model each card as an `Entity<HeroCard>` keyed by pack
id in a `HashMap` and reuse entities across rebuilds, dropping ones no longer shown (drop =
unsubscribe). Render the grid with `div().flex().flex_wrap().gap(...)` or gpui-component's
virtual list if libraries grow into the hundreds (page size caps at 96 anyway).

`selectModpack(pack)` (CLI `-c <pack>` path): clear search, type = all, untick updatesOnly, set VM
type all, find the pack index in `buildFilteredSortedPacks()` by friendly or pack name, set page =
`index / pageSize + 1`, rebuild, focus that card and scroll it into view on the next frame (bounds
are stale in the same pass): `vvalue = min(1, cardY / gridHeight)`. GPUI:
`ScrollHandle::scroll_to_item(ix)`.

`refreshAvailablePacks()` (F5 / refresh glyph; never takes over the screen): status
`main.fetchLabel.refreshing`, disable the refresh glyph; background:
`NetworkUtilities.checkNetworkAvailability()` (re-probe: the startup offline flag is sticky and a
single miss pinned the session offline), `AnnouncementManager.checkAnnouncements()`,
`GameModPackManager.fetchInstalledModPacks(null)`, and if online
`fetchAvailableModPacks(null)` (null progress window = no progress screen); finally on UI: hide the
status, restore its text to `main.fetchLabel.loading`, enable the glyph, `rebuildCards()` (no
navigation, scroll position kept).

`setAnnouncementRow(extra)`: text = `homeAnnouncement` (`AnnouncementManager.getAnnouncementHome()`,
null -> ""), prefixed by `extra + "\n"` when extra is non-empty. If the text was dismissed this
session, treat as empty. Non-empty: show; height 60 if it contains `\n` else 40 (fixed min=pref=max
on label, bar and row). Empty: hide, unmanage, all heights 0.

`refreshRunningGames()` (skip if disposed): `n = registry.active().len()`; button text
`main.runningGames(n)`, visible iff n > 0; every card `refreshPlayState()`.
`bindAccountHeader()` (skip if disposed): `user = MCLauncherAuthManager.getLoggedInUser()` (the
default account); name label = `user.name` or ""; avatar = `AvatarImages.get(user.uuid)` or none.

### 3.4 Hero card (`ModpackHeroCard`)
Fixed width 360 (height from content); image area 150 high, top corners clipped at LARGE (16).
Layers in the image area: procedural gradient (`bgLayer`), background image
(`PackBackgroundLayer`, cover-fit, fades in 220 ms over the gradient), bottom veil, badge row
(top-right, spacing 6, padding 10 12 0 0). Logo container 72x72 overlapping the image by 36 px
(translateY -36), logo 68x68 clipped at radius 10, fades in over 220 ms when loaded. Body padding
0 18 16 18, spacing 6: logo, name (`heading-h2 heroCardTitle`, wraps), chips row (spacing 6),
last-played line. Action row padding 0 18 16 18, spacing 8: Play (`primary heroPlayBtn`, grows,
height exactly 38) and Website (`heroCardSecondaryBtn`, height 38, pref width). Card cursor =
hand. Java caches the card bitmap (`CacheHint.DEFAULT`, not SPEED, which froze the cache before the
async background arrived); not needed in GPUI.

Clicks: primary single click starts a 130 ms timer, then opens the detail modal for the card's
*current* pack; a double click stops the timer and fires Play if enabled. Play / Website consume
their own clicks. `bind()` stops a pending timer first (a rebuild within 130 ms would otherwise
open the modal for the newly bound pack). Right click -> pack context menu (3.5). All handlers read
`self.pack` at fire time, never a captured pack.

`bind(pack)`:
- logo = `ModpackImageResolver.resolveLogoOrDefault(pack)` (disk cache file if it exists, decoded
  at 256x256 preserving ratio, smooth, background; else the bundled `micaminecraftlauncher.png`).
- gradient: clear previous inline gradient and the two default classes, then
  `applyDynamicBackground` (3.4.1).
- background: `bgUrl = config.showPackBackgrounds ? resolveBackgroundUrlFromDisk(pack) : null`
  (only if `hasCustomBackground()` and the cache file exists with length > 0). Show it (null = clear).
  If showBackgrounds, the pack declares a custom background and the file is missing: background
  task `pack.cacheImages()`, then if the file now exists and the card still shows *this* pack, show
  it and `refreshImageCycle()`.
- same for a declared custom logo whose cache file is missing: `cacheImages()`, reload the logo,
  identity check, fade in, refresh cycle.
- logo transparency: remove `logoTransparent`, run `LogoTransparencyDetector.detectAsync(logo)`;
  if transparent and still the same pack, add `logoTransparent` (drops the frame bg/border).
- badges (rebuilt): `main.badge.beta` (`stat-chip-warn`) if `getPackUnstable()`;
  `main.badge.recentlyUpdated` (`stat-chip-success`) if `isUpdateAvailable()`; `main.badge.news`
  (`stat-chip`) if `getUnreadNewsCount() > 0` (wrapped: a garbled manifest must not break the card).
- name = displayName. Chips: `main.chip.vanilla` (vanilla only, first); `main.chip.minecraft(mc)`
  if mc non-blank; `"<loaderName> <shortLoader>"` if loader name and version present, where
  shortLoader = text after the last `-` of loaderVersion (e.g. `1.12.2-14.23.5.2860` ->
  `14.23.5.2860`); `main.chip.packVersion(v)` if packVersion non-blank and != mc (vanilla packs have
  packVersion == mc).
- played: `isNeverPlayed()` (boolean, never string-compare localized text) ->
  `main.card.neverPlayed`; else `main.card.lastPlayed(lower(getLastPlayedFormatted()))`.
- Play disabled iff `AnnouncementManager.getDisableGameplay()`; `refreshPlayState()`; Play tooltip
  set **every** bind (pooled cards would carry a stale one): if
  `config.getAccountOverrideForPack(pack.settingsKey)` is set: account found ->
  `modal.chip.playsAs(displayName)`, missing -> `modal.chip.playsAsMissing`; else no tooltip.
- Website disabled iff `packURL` blank.
- context menu rebuilt per bind.
- image cycle (3.4.2).

`refreshPlayState()`: Play text = `main.card.running` ("Running · Show") if
`GameSessionRegistry.isPackActive(pack)` else `common.button.play`.

`startPlay(pack)`: if `registry.activeForPack(GameSession.keyFor(pack))` exists ->
`RunningGamesWindow.showSession(it)`; else `config.setLastModPackSelected(pack.packName)` and spawn
`LauncherCore.play(pack)`.

#### 3.4.1 Dynamic gradient (`applyDynamicBackground(bgLayer, pack, logoImage)`, shared with Browse)
- Tag the layer with the logo image (identity token) so a late async result for an older bind is
  dropped.
- Vanilla: palette of the bundled launcher logo (`/micaminecraftlauncher.png`, cached under key
  `__mica_launcher_logo__`, 4 colours) -> gradient; if unavailable, class
  `heroBackgroundDefaultVanilla`.
- No logo: class `heroBackgroundDefaultForge`.
- Cache key: `packLogoURL` if non-blank, else logo file path, else packName. Palette cache hit
  (4 colours) -> gradient. Else 2-colour cache hit -> interim gradient. If the logo is fully loaded
  -> compute palette(4), cache, gradient (or Forge default if none and no interim). If still loading:
  Forge default (unless interim), and on load completion (not error, token unchanged) compute,
  cache, remove the Forge class, gradient.

`computeDominantColorPalette(image, count)` (port exactly; image decoded small, Java decodes
palette sources at 64x64):
```
LEVELS = 16 (4096 buckets RGB); step = max(1, min(w, h) / 32)
for y in (0..h).step_by(step), x in (0..w).step_by(step):
  c = pixel; if c.alpha < 0.5 continue
  sat = max(r,g,b) - min(r,g,b); weight = 1 + floor(sat * 20)
  idx = (min(15, floor(r*16)) * 16 + min(15, floor(g*16))) * 16 + min(15, floor(b*16))
  counts[idx] += weight; sums[idx] += rgb * weight
for slot in 0..count:
  best = argmax over buckets with counts > bestCount of counts, skipping any bucket whose average
         colour is within Euclidean RGB distance <= 0.25 of an already picked colour
  if none: break; picked.push(average(best))
return picked (None if empty)
```
Gradient (`to bottom right`): one colour p -> `p 0%, derive(p, brightness*0.60) 55%,
derive(p, brightness*0.35) 100%` (HSB brightness scale). n >= 2 colours -> stops at
`100*i/(n-1)%` with an extra 50/50 RGB blend stop at `100*(i+0.5)/(n-1)%` between each pair.
Hex `#RRGGBB` with rounding. GPUI's `linear_gradient` takes two stops; draw multi-stop gradients
by painting n-1 adjacent quads with 2-stop gradients in a `canvas`, or precompute a small RGBA
bitmap (e.g. 64x64) per pack and draw it as a stretched image (simplest, cache by key).

`prefetchAvailableModpackBackgrounds()` (called at startup by the session): background; for every
available pack with a logo URL not yet cached, decode (cached file if present, else the URL) at
64x64 and store palette(4) + dominant pair. `sampleDominantPackColors(pack)` /
`sampleDominantPackPalette(pack, count)` (public, used by RGB and the detail modal): null for
vanilla / count < 1; cached palette if it has >= count colours; else decode the logo file at
64x64, compute, cache.

`LogoTransparencyDetector` (pure core, cache by image URL): inset `max(2, w/20)`,
`max(2, h/20)`; 12 samples: 4 corners (inset), top at x = w/4, w/2, 3w/4, bottom same, left and
right at y = h/2; transparent if alpha < 0.05; logo is "transparent-edged" when >= 60 % of samples
are transparent. Error or null -> false. Fires the callback once.

#### 3.4.2 Multi-image cycling (issue #43)
Packs may declare several logo and background URLs (distinct showcase images, not mirrors).
`canCycle(pack, showBg)` = (custom logo && logo URL count > 1) || (showBg && custom background &&
background URL count > 1); errors -> false. If not cyclable: no subscription, lists = [primary].
Else resolve every cached logo file (decode 256) and background file (length > 0); if fewer files
than declared and no prefetch started this bind, start one `cacheImages()` and refresh on completion
(same pack only). If shuffle (`imageCycleShuffle`) shuffle copies once. Subscribe to the shared
clock if either list has > 1 entry; tick: skip if not on screen; `index += 1`; logo =
`logos[index % n]` (fade), background `cycleTo(bgs[index % m])` (cross-fade). Unsubscribe when the
card leaves the scene, on rebind, and in screen cleanup; resubscribe on scene attach only if
cyclable. Never subscribe once the screen is disposed (pins the screen and keeps the timer alive).

`ModpackImageCycleClock` (one per process): listeners list (copy-on-write); `register(onTick) ->
unsubscribe` (idempotent; stops the timer when the last listener leaves); `reconfigure()` (stop +
start; called when Settings changes the interval); a listener panic must not kill the clock.
Interval from config `imageCycleInterval` (default `30s`):
`5s, 15s, 30s, 1m, 5m, 15m, 30m, 1h, 6h, 12h, 1d (24h), 7d (168h)`; `never`, unknown and null -> no
timer. GPUI: a `Global` holding `Vec<(id, WeakEntity)>` + one `Task` loop that sleeps the interval
and notifies; tick in lockstep for all surfaces.

`PackBackgroundLayer`: decodes off the UI thread at the shown size x display scale, width rounded
up to a multiple of 64 (`decodeWidth(boxW, scale) = max(64, ceil(boxW*scale) rounded up to 64)`),
LRU cache capped at 96 MB of decoded pixels, 220 ms fade, cover viewport:
```
cover_viewport(iw, ih, bw, bh): s = max(bw/iw, bh/ih); vw = min(iw, bw/s); vh = min(ih, bh/s)
  -> rect((iw-vw)/2, (ih-vh)/2, vw, vh)
```
(Loading 1080p-4K backgrounds via inline CSS cost 8-30 MB per bind on the UI thread; that is why.)
GPUI: decode with `image` + resize with `fast_image_resize` on the background executor into a
`RenderImage`; `img(Arc<RenderImage>).object_fit(ObjectFit::Cover)` does the cover crop.

`ImageFadeIn`: start opacity 0, 220 ms fade when the image finishes (or immediately when already
loaded).

### 3.5 Pack context menu (`buildPackContextMenu`, shared with Browse installed cards)
Items, in order:
1. disabled `main.contextMenu.playStats(totalPlayTimeFormatted, launchCount)`; separator
2. `main.contextMenu.openInstall` -> open pack root folder; separator
3. `openScreenshots` (`screenshots`), `openResourcePacks` (`resourcepacks`), `openShaderPacks`
   (`shaderpacks`); separator; `openMods` (`mods`), `openConfig` (`config`); each creates the folder
   if missing, then opens it with the OS (failure: silent warn `log.modpack.openFolderFailed`)
4. separator; `createShortcut` -> `DesktopShortcutManager.createShortcut(pack)` then an info alert
   `dialog.shortcut.created.{title,body(packName)}`; failure -> error `dialog.shortcut.failed.body(msg)`
5. `copyInvite` -> `DiscordRpcUtility.buildInviteLinkFromPack(pack)` to the clipboard +
   success toast `notification.invite.copied.{title,body}`; null -> warn
   `detailModal.invite.unavailable.{title,body}`; item disabled when the link is null
6. separator; `editPack` -> `go_to_editor(pack)`; `exportZip` -> save dialog (title
   `dialog.fileChooser.exportModpack.title`, initial name `<packName or modpack>-<epochMillis>.zip`,
   filter `dialog.fileChooser.zipArchive.filter` `*.zip`); vanilla -> zip now; else toast
   `detailModal.exportPack.verifying`, `VerifyAction.runForPacks([pack], doZip)`; zip via
   `ModpackExporter.exportToZip(pack, dest)` with success `detailModal.export.success.{title,body(path)}`
   / error `detailModal.exportPack.failed`
7. `officialExport.menuItem` -> Add to official launcher flow (below); both 6-zip and 7 disabled
   when `packRootFolder` is null
8. `officialExport.remove.menuItem` only when `OfficialLauncherExporter.hasExportedProfile(pack)`
9. separator; `main.contextMenu.uninstall` -> `confirmAndUninstallModpack` (4.6)

Add to official launcher (background): if `!isOfficialLauncherAvailable()` error
`officialExport.notInstalled.{title, body(dotMinecraft path)}`. Compute `versionId =
computeVersionId(pack)` and `loaderInstalled = isVersionInstalled(dotMinecraft, versionId)` (error
-> `officialExport.failed.{title, body(msg)}`). loaderName = vanilla ? "Minecraft" :
`pack.getLoaderName()` (error -> `main.officialExport.loaderFallback`). Middle blurb: vanilla
`confirm.body.vanilla(packName, versionId)`, loader installed `confirm.body.modded.loaderInstalled`,
else `confirm.body.modded.loaderMissing(loaderName, versionId)`. Multiline question
`officialExport.confirm.{title, header(packName), body(packName, versionId, blurb)}`, buttons
`confirm.button.export` / Cancel; != 1 stop. If modded and loader missing: second question
`installLoader.{title, header(loader), body(loader, versionId)}`, buttons `installAndAdd` (1, auto
install) / `addOnly` (2); 0 stop. Then (modded: after a forced full verify with toast
`officialExport.verifying`) run: optional `LoaderInstallerRunner.install(pack, dotMinecraft)` with
toasts `installLoader.inProgress`, `.success`, or `.failed` (continue anyway; log stderr), then toast
`officialExport.inProgress`, `OfficialLauncherExporter.exportPack(pack)` -> error
`officialExport.failed`, or warn `successButLoaderMissing(versionId)` when modded and loader still
missing, or success `officialExport.success(profileName)`. Remove: multiline question
`remove.confirm.{title, header(pack), body(pack, gameDir)}` -> `removeExport(pack)` -> toast
success/failed.

---

## 4. Browse screen (`MCLauncherGameLibraryGui`, `gameLibraryGUI.fxml`)

### 4.1 Layout
| Row | Height | Content |
|---|---|---|
| 0 navbar | 52 | brand logo, `%browse.navbar.title` ("Browse"), spacer, offline chip, help |
| 1 announcement | 0/40 | `AnnouncementManager.getAnnouncementModpacksEdit()` (fixed 40 when non-empty) |
| 2 filters | 48 | search pill (min 180 pref 200), `%main.filter.typeLabel` type combo (min 150 pref 160), `%browse.filter.statusLabel` status combo (min 120 pref 130), `%main.filter.sortLabel` sort combo (min 150 pref 160) |
| 3 pagination | 48 | range label, loading indicator (spinner 20 px + `%browse.loading`, hidden), background status label (hidden), spacer, prev, page label, next, spacer, per-page combo |
| 4 grid | grow | scroll; flow grid hgap 16 vgap 16, padding 20 28 28 28 |
| 5 actions | 52 | `%browse.bottomBar.urlLabel`, URL field (prompt `%browse.addByUrl.prompt`, pref 320, grows), Add from URL, Import ZIP, Import MultiMC / Prism, spacer, Modpack Editor, Generate Hosting Manifest, Return |

Options: type `all, modpacks, vanilla.release, vanilla.snapshot, vanilla.beta, vanilla.alpha,
forge, neoforge, fabric` (default all); status `all, installed, available` (default **installed**);
sort `default, nameAz, nameZa, releaseDate, lastPlayed, mostPlayed, recentUpdate` (default
`default` = keep collection order); page sizes `[24, 48, 72, 96]`, default 48. Debounce 120 ms.

### 4.2 `setup()` / `afterShow()` / `cleanup()`
- Close -> `closeApp()`.
- Prewarm the mod-filename cache for every installed pack on a worker (first search keystroke
  otherwise lists every pack's `mods/` dir on the UI thread).
- Add from URL (disabled if `getDisableModpacksEdit()`): ignore blank; clear and disable the field
  and button; `ModpackImportClassifier.classify(url)` -> `{source, slug, versionId}`:
  - MODRINTH: button `browse.urlAdd.checking`; Modrinth flow (4.4).
  - CURSEFORGE: `checking`; reset controls; multiline question `dialog.import.curseforge.{title,header}`
    with a preview (detected, source + slug, file id, note, URL) and buttons `button.openPage` /
    Cancel; 1 -> open the original URL in the browser.
  - TECHNIC: `checking`; reset; question `dialog.import.technic.*` with preview (detected, source +
    slug, note, steps 1-3, URL); 1 -> open `https://www.technicpack.net/modpack/<slug>` (never the
    API host: that dumps JSON).
  - MICA / UNKNOWN: button `browse.urlAdd.adding`; background
    `GameModPackManager.installModPackByURL(url)`; finally restore the button text and enable
    states, `rebuildCards()`.
- Modpack Editor -> blank editor. Return -> Home.
- Import ZIP (disabled if edit disabled): open dialog `dialog.fileChooser.importModpackZip.title`,
  filter `dialog.fileChooser.micaModpackZip.filter` `*.zip`; disable + `checking`;
  `beginImport(zipName)`; background `ModpackZipImporter.importZip(file)` -> URL; if the URL contains
  `/technic-server-` toast `notification.import.technicServer.*` else
  `notification.import.complete.*`; `ImportException` -> error `notification.import.failed.title`
  + message; other -> error `notification.import.unexpected.*`; finally `endImport()` and restore.
- Import Prism (disabled if edit disabled): directory dialog `dialog.dirChooser.importPrism.title`;
  `beginImport(dirName)`; `PrismInstanceImporter.importInstance(dir)` -> success
  `notification.import.prism.success.{title, body(name)}`, ImportException -> warn
  `notification.import.prism.failed.title` + message, other -> warn with
  `notification.import.unexpected.body`; finally `endImport()`.
- Hosting manifest: save dialog `dialog.fileChooser.saveHostingManifest.title`, initial
  `installable.json`, filter `*.json`; background: `{ "<AVAILABLE_PACKS_MANIFEST_LIST_KEY>": [
  installed pack URLs ] }` pretty-printed UTF-8; button `browse.hostingManifest.saved` for 2 s then
  back to `browse.hostingManifest.label`; failure logs only.
- `afterShow()`: first `rebuildCards()` (gathers off the UI thread so the chrome paints at once;
  a cold Mojang manifest fetch used to freeze a blank grey screen), smooth scroll; if the
  available-packs future is pending show the background status and rebuild when it completes; global
  shortcuts; `Shortcut+F` focuses search.
- `cleanup()`: `disposed = true`; dispose the VM.

### 4.3 `rebuildCards()` (generation-guarded, async)
```
if disposed return
gen = ++rebuildGeneration
capture type, status (default installed), search = lower(trim(query)), sort (default "default")
start a 150 ms timer: if gen still current, show the loading indicator (warm rebuilds never flash)
background:
  entries = collectEntries(type, status)
  if search non-empty: keep e if lower(e.displayName) contains search, or (e.pack != null and
    packModsMatch(e.pack, search))
  sortEntries(entries, sort)
UI: stop timer; if gen != current or disposed: drop; renderEntries; hide loading indicator
```
`collectEntries(type, status)`:
- wantInstalled = status != available; wantAvailable = status != installed;
  wantModpacks = type in {all, modpacks}; wantVanilla = type == all || type starts with `vanilla.`;
  loaderType = forge/neoforge/fabric -> `ModPackConstants.MOD_LOADER_*` else none.
- modpacks + installed: `installedModpack(p)` for each installed pack.
- modpacks + available: `GameModPackManager.getAvailableModPacksIfReady()` (non-blocking; null while
  loading) minus any whose `friendlyName` matches an installed pack's friendly name.
- loader + available: `LoaderVersionManager.get{Forge,NeoForge,Fabric}Versions()`; skip installed
  (`isInstalled(v)`) but keep counting `catalogIndex`; `availableLoader(v, catalogIndex)`.
- vanilla: installed ids; for each vanilla type in `vanillaTypesFor(type)` (`release`,
  `snapshot`, `old_beta`, `old_alpha`; all four for `all`), `VanillaVersionManager.getVersionsByType`
  (null -> skip); per version `id`: installed && wantInstalled -> `installedVanilla`, not installed &&
  wantAvailable -> `availableVanilla`.

`LibraryEntry { kind: ModpackInstalled | ModpackAvailable | VanillaInstalled | VanillaAvailable |
LoaderAvailable, display_name, chips, status_text, pack?, vanilla_id?, vanilla_info?, loader?,
release_time_ms (i64, MIN = none) }`:
- installed modpack: name friendly ?? pack; chips `library.chip.modpack`, `library.chip.minecraft(mc)`
  if resolvable; status `library.status.recentlyUpdated` if update available else
  `library.status.installed`.
- available modpack: same chips; status `library.status.fromManifest`.
- installed vanilla: name `library.entry.minecraftVersion(id)`; chips `library.chip.vanilla`,
  pretty type (`Release`, `Snapshot`, `Old Beta`, `Old Alpha`, raw otherwise; note: these four are
  hard-coded English, a localization gap to fix); status installed; release time parsed from
  `releaseTime` (ISO offset date-time) else MIN.
- available vanilla: status `library.status.released(first 10 chars of releaseTime)` if >= 10 chars
  else `library.status.available`.
- available loader: name `"<Forge|NeoForge|Fabric|raw> <loaderVersion>"`; chips loader pretty name,
  `library.chip.minecraft(mcVersion)`; status `library.status.installsAsEmpty`; synthetic release
  time `now - catalogIndex * 86_400_000` so "release date" keeps upstream order.

Sort (stable; descending ones `.reversed()`): nameAz / nameZa by lower(name); releaseDate by
release time, falling back to `lastUpdateKey`; lastPlayed / mostPlayed / recentUpdate by the pack
values (no pack -> MIN, sinks); default keeps order.

Mod-name search cache: per pack root, `{ mods_dir_mtime, lowercase file names ending .jar or
.disabled }`, refreshed when the `mods` directory's mtime changes.

`renderEntries`: bounds, pagination labels (empty: `browse.pagination.empty`), recycle, clear;
if an import is in flight its placeholder card goes first; empty -> (no placeholder) empty state:
badge `BROWSE` (no search) / `SEARCH`, heading `library.empty.heading`, sub
`library.empty.sub.search(type, status, search)` (note: passes raw ids, not labels; fix in Rust by
passing the localized labels) or `library.empty.sub.noSearch`; else one card per slice item.

Import placeholder card (300 wide, image 110 with the Forge default gradient and a 56 px loading
indicator, name `heading-h2` (default `library.import.defaultName`), status label starting at
`library.import.preparing`): `beginImport(name)` sets it and rebuilds; `updateImportStatus(text)`
updates the label (ignores blank); `endImport()` clears it and rebuilds.

### 4.4 Modrinth import flow
Background: `ModrinthClient.fetchProject(slug, versionId)` -> summary or null; reset URL
controls; null -> error `notification.import.modrinth.failed.*`. Preview text: title (or
`preview.modrinth.unnamedProject`), blank line + description, `preview.modrinth.sourceLabel` +
` (slug)`, `versionLabel(versionNumber)`, `minecraftLabel(join ", ")`, `loaderLabel(join ", ")`,
`preview.urlLabel(url)`. Multiline question `dialog.import.modrinth.{title,header}`,
`button.continue` / Cancel; != 1 stop. `beginImport(title)`; then:
- latest version with files required (else error `fileUnavailable`); pick the first `primary` file
  with a URL, else the first file; URL must end `.mrpack` (else error `archiveMissing`).
- toast `notification.import.preparing.*`; status `library.import.modrinth.downloading`;
  `MrpackImporter.importMrpack(url, slug, iconUrl)` (ImportException -> error toast);
- status `library.import.waitingConfirmation`; show `MCLauncherImportConfirmDialog` (other spec)
  with name, version and the parsed index; await the answer (Java: CompletableFuture between the
  worker and the UI-thread `showAndWait`); cancel -> stop (the translated manifest stays on disk);
- toast `notification.browse.importStarting.*`; status `library.import.addingToLibrary`;
  `installModPackByURL(localManifestUrl)`; success toast `notification.browse.importComplete.*`,
  failure `notification.browse.installFailed.*`.
- finally `endImport()`.

### 4.5 Library card (`LibraryCard`)
300 wide, image 110 (LARGE clip), same layers as the hero card; badge row padding 8 10 0 0;
logo container 56x56 at translateY -28, logo 52x52 clipped radius 10; body padding 0 14 10 14,
spacing 4: logo, name (`heading-h3`, max width 272), chips, status line; actions padding 0 14 12 14.
`bind(entry)`: gradient via `applyEntryBackground` (installed/available modpack and installed
vanilla -> `applyDynamicBackground`; available vanilla -> vanilla default; loader -> Forge default);
background image only for entries with a pack and `showPackBackgrounds`; top badge + style:
installed modpack `recentlyUpdated` or `installed` (`stat-chip-success`), installed vanilla
`installed` (success), available modpack / loader `library.status.available` (`stat-chip-info`),
available vanilla none; logo: installed modpack -> disk logo; available modpack -> its logo URL
(decoded 256); vanilla -> `PlaceholderLogoFactory.getVanillaLogo()`; loader ->
`PlaceholderLogoFactory.resolveLogo(loaderType, onOfficialReady)` (canvas placeholder now, official
logo later from `<config>/loader-logos/`; apply only if the card still shows that entry).
Actions: installed modpack -> Uninstall (`dangerZone`, grows) + Edit (secondary); available modpack /
vanilla / loader -> Install (`primary`); installed vanilla -> Uninstall. Context menu only for
installed modpacks (3.5).

### 4.6 Install / uninstall actions
- `confirmAndUninstallModpack(pack, displayName, owner, showProgress, hideProgress, onComplete)`
  (public static, also used by Home's context menu and the detail modal): if the pack is running ->
  warn toast `notification.pack.running.{title, uninstall(name)}` and stop. Question
  `dialog.uninstall.modpack.{title, header(name), body}`, buttons
  `dialog.uninstall.button.deleteFiles` (1) / `keepFiles` (2) / Cancel (0 stop). showProgress
  `library.status.removing(name)`; background `GameModPackManager.uninstallModPack(pack, deleteFiles)`;
  if removed run onComplete on UI; finally hideProgress.
- install available modpack: friendly name required; status `library.status.installing(name)`;
  `installModPackByFriendlyName(friendly)`, rebuild, success toast
  `notification.install.modpackSuccess.{title, body(name)}`; finally hide (FxAsyncTask reports errors).
- uninstall vanilla: question `dialog.uninstall.vanilla.{title, header(id)}` + modpack body, same
  buttons; status `library.status.removingMc(id)`; if delete files remove the vanilla pack root dir
  recursively (warn on failure); `VanillaVersionManager.uninstallVersion(id)`; rebuild.
- install vanilla: status `library.status.installingMc(id)`; skip if installed;
  `installVersion(id)`; rebuild; toast `notification.install.vanillaSuccess`.
- install loader: status `library.status.installingLabel(displayName)`; skip if installed;
  `LoaderVersionManager.installVersion(v)` (writes a local manifest under `imported-manifests/` and
  installs it as an empty modpack); rebuild; toast `loaderSuccess`; IOException -> error toast
  `notification.install.failed.{title, body(label, msg)}`; finally hide.

---

## 5. Sign-in: Login screen, SignInPanel, Add-account, account switcher

### 5.1 `MicrosoftSignIn` (pure parts port exactly)
- `USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:147.0) Gecko/20100101 Firefox/147.0"`
  (Microsoft's pages degrade for unknown agents). This is a desktop browser UA for the embedded page
  only, not a personal identifier.
- `loginUrl() = MicrosoftService.oAuthLoginUrl() + "&prompt=select_account"` (forces the account
  picker even with a live Microsoft session in the web view; without it a second account cannot be
  added because Microsoft silently signs the cached one back in).
- Redirect prefix `Constants.MICROSOFT_OAUTH_REDIRECT_URL` = `https://login.live.com/oauth20_desktop.srf`.
- `parseCallback(location)`: take the query after the first `?`, cut at `#`; split `&`; URL-decode
  name and value (no `=` -> value ""); `code` keeps the first occurrence; `error` sets a flag;
  `error_description` is the message; others ignored. If error flag set or code null/empty ->
  `Callback { code: None, error: error_description or "" }`; else `Callback { code, error: None }`.
- `Callback::user_cancelled() = error contains "user has denied access"`.
- `attach(view, waiting, onCallback)`: on every load-state change while `waiting`: location must be
  non-null and start with the redirect prefix; then `waiting = false`, load `about:blank`, call
  `onCallback(parseCallback(location))` on the UI thread. Attach once per web view (attaching per
  page load stacked listeners and redeemed a single-use code several times).

### 5.2 `SignInPanel` (shared card)
Root: HBox `card elevated signInCard`, max width 1040, two parts:
- Aside (VBox spacing 14, padding 32 28 28 32, min 300 pref 330 max 360, `signInAside`): brand row
  (logo 40x42 = the 80x84 brand component scaled 0.5 about the origin, eyebrow `signIn.eyebrow`),
  heading (`heading-h2`, key from the host), subtitle (muted, host key), three points (VBox spacing
  16, top padding 8): each a 36 px disc with a 24 px glyph + title + muted body, keys
  `signIn.point.password.*` (glyph SECURITY), `signIn.point.device.*` (LOCK),
  `signIn.point.accounts.*` (PEOPLE); grow spacer; "Stay signed in" switch (`signIn.remember`,
  default on) + hint `signIn.remember.hint` (subtle); status label (`signInStatus`, hidden until a
  failure); dismiss button (`heroCardSecondaryBtn`, h 32, min w 96, host key).
- Frame (StackPane `signInFrame`, clipped at MEDIUM 12, margin 12 12 12 0, grows): web view (JS on,
  UA above, min 440 pref 520, smooth scroll) under an overlay (VBox spacing 14, centred,
  `signInOverlay`): loading indicator 36 px, text (muted, wraps, max 320), retry button (`primary`,
  h 32, `signIn.retry`, hidden).
Behaviour:
- `loadSignIn()`: `redeeming=false`, enable the switch, `waiting=true`, overlay `signIn.loading`
  (spinner), load `loginUrl()`.
- page loads (ignored while redeeming): SUCCEEDED on a non-blank location (not null, "",
  `about:blank`) hides the overlay; FAILED -> silent warn `log.login.pageLoadFailed`, overlay
  `signIn.loadFailed` with Retry (no spinner).
- Retry: clear status, `loadSignIn()`.
- `showRedeeming()`: `redeeming=true`, disable the switch, clear status, overlay `signIn.redeeming`.
- `failAndRetry(key)`: show the status label with the key's text, then `loadSignIn()`.
- `remember()` = switch state.

### 5.3 `MCLauncherLoginGui` (`loginGUI.fxml`)
Root grid min 900x760, pref 1120x860, classes `rootPane hero-surface`; row 0 announcement
(`AnnouncementManager.getAnnouncementLogin()`, min height 40 when non-empty else max 0), row 1 the
sign-in host (padding 28 32 32 32) centred. `setup()`: close -> fire dismiss; grow (never shrink)
the window to pref size if smaller (the progress screen opened the window at 800 high and the web
view clipped); build `SignInPanel("signIn.login.heading", "signIn.login.subtitle",
"login.exitBtn")`; attach the OAuth callback once; dismiss -> `closeApp()`.
`afterShow()`: reset the process cookie handler (`CookieHandler.setDefault(new CookieManager())`,
java.net only; the web view keeps its own jar, which is why `prompt=select_account` exists),
`loadSignIn()`, presence `login.discord.loggingIn`. `cleanup()`: load `about:blank`.
`allowsToolbarNavigation = false`. Callback:
- no code: cancelled -> debug `log.login.userCancelled` and `loadSignIn()` (not an error); else
  error log `log.login.msLoginError(error or login.error.unknown)` and `failAndRetry("signIn.failed")`.
- code: `showRedeeming()`; background `MCLauncherAuthManager.loginWithMicrosoftAccount(code,
  remember)` (this one sets the default account); `AuthUtilities.checkAuthResponse(result)` true ->
  release the latch; false -> log `log.login.failed`, UI `failAndRetry(result == ERROR_NOT_OWNED ?
  "signIn.notOwned" : "signIn.failed")`.
`waitForLoginSuccess()`: block until the latch (one-shot `CountDownLatch(1)`). Rust:
`pub fn wait_for_login_success(&self) -> impl Future<Output = ()>` backed by a
`futures::channel::oneshot` or `event_listener::Event`.

### 5.4 `AddAccountDialog.show(owner)`
Window-modal window over `owner` (title `account.add.title`, app icon), root StackPane padding 20,
`rootPane hero-surface`, scene 1000x800 x scale, min 860x680 (`ScaledMinSize.follow`), current theme
sheets (solid bg), title-bar dark mode on show (`!isLightChrome`), on hide load `about:blank`.
Panel `SignInPanel("account.add.title", "account.add.hint", "dialog.button.cancel")`; dismiss closes.
Callback: cancelled -> close; no code -> log `log.login.msLoginError(error)`, if still showing
`failAndRetry("signIn.failed")`; code -> `showRedeeming()`, background
`MCLauncherAuthManager.addAccountWithMicrosoft(code, remember)` (adds alongside; never changes the
default unless there is none; for an account whose credentials were rejected it replaces them) ->
ok: close + success toast `account.add.done.{title, body(name)}`; else if still showing (closed
while redeeming would reload into a hidden view) `failAndRetry(notOwned ? "signIn.notOwned" :
"signIn.failed")`. Show, then `loadSignIn()`.

### 5.5 `AccountListModel` (pure)
`Row { uuid, name, status_key, is_default, can_make_default, needs_sign_in, session_only }`.
`rows(accounts)`: needs_sign_in = status == NEEDS_SIGN_IN; status key: needsSignIn ->
`account.status.needsSignIn`, REFRESHING -> `account.status.refreshing`, default ->
`account.status.default`, else `account.status.signedIn`; name = displayName if non-blank else uuid;
can_make_default = !default && !needs_sign_in; session_only = !remembered. Order: defaults first,
then the rest in input order (most recently used first, as `AccountManager.accounts()` returns).

### 5.6 `AccountSwitcherMenu.show(anchor, openAccountSettings)`
Context menu below the anchor: disabled header `account.switcher.header`; one item per row (rows
from `AccountListModel`; any error -> no rows): graphic = tick label ("✓" for default, min width 14)
+ 20 px round avatar; text = name, or `account.switcher.needsSignIn(name)`; default item has no
action; needs-sign-in -> `AddAccountDialog.show(window)`; else background `accounts().setDefault(uuid)`
(writes config + account metadata; listeners repaint). Separator; `account.switcher.addAccount` ->
Add-account dialog; `account.switcher.manage` -> openAccountSettings. GPUI: gpui-component
`PopupMenu` / `ContextMenu` anchored to the chip, or a custom `deferred(anchored(...))` overlay.

### 5.7 `AvatarImages`
URL `https://minotar.net/armor/bust/<uuid>/100.png`. Per-session cache uuid -> image loaded in the
background; a failed load is replaced on the next request (not cached); blank uuid -> none. GPUI:
`img(SharedUri)` with the app's HTTP client already caches; add an explicit
`HashMap<Uuid, Shared<Task<Result<Arc<RenderImage>>>>>` with failure eviction so failures retry.
HTTP requests must use the launcher's generic User-Agent, never anything personal.

---

## 6. Settings screen (`MCLauncherSettingsGui`, `settingsGUI.fxml`)

### 6.1 Layout
Rows: navbar 52 (brand, `%settings.fxml.settings`, spacer, offline chip, help), announcement 0/40
(`AnnouncementManager.getAnnouncementConfig()`), body (sidebar + content), bottom bar 44 (version
label, ..., Save `%settings.fxml.save`, Save and Restart `%settings.fxml.saveAndRestart` (hidden
unless a language change is pending), Return `%settings.fxml.return`).
Sidebar: scrollable list of 10 nav tiles (`SettingsNavTile.decorateSidebar`), each a 36 px coloured
disc with glyph (24 px art drawn at 20 px) + title + one-line ellipsized summary
(`settings.nav.<id>.summary`); a11y "title, summary"; grouped (`.settingsNavGroup` VBoxes) with
position classes. Tiles (icon, hue, id): Account (ACCOUNT, blue), Game (GAME, green), Appearance
(APPEARANCE, orange), Advanced (ADVANCED, yellow), Network (NETWORK, blue), Security (SECURITY, cyan),
System (SYSTEM, grey), Discord (DISCORD, purple), RGB (RGB, pink), About (ABOUT, grey).
Content: a stack of 10 scroll panes in the same order; `showCategory(i)` shows/manages only pane i
(fade-through when it was hidden) and marks tile i `selected`. Default category 0. All inner scroll
panes get smooth scrolling. `SettingsGroups.install(root)`: a `.settingsRow` tile is shown only
while at least one child is visible, and each `.settingsGroup`'s visible rows are re-tagged
first/middle/last whenever visibility changes.

### 6.2 Every setting: control, storage, save semantics
All values live in `<launcherRoot>/config/configuration.json` (key names below; the config
subsystem owns parsing, versioning (`configVersion` = 7), atomic writes and the per-pack maps)
except the CurseForge key (encrypted envelope in the same file via `AuthTokenStore`, AES-256-GCM
machine-bound; getter returns null on decrypt failure) and MCP grants/policies (MCP subsystem).

"Save" = written by `persistSettings()` on Save / Save-and-Restart / the unsaved-changes prompts.
"Live" = written immediately by a change listener.

| Pane | Control (fx:id) | Config key | Type / default | Range / options | When written | Side effect |
|---|---|---|---|---|---|---|
| Account | `accountAvatar`, `accountNameLabel`, `accountUuidLabel` | (auth) | | | | default account display; uuid label `settings.accountUuidLabel(uuid)` |
| Account | `minecraftNetBtn` | | | | | open `https://www.minecraft.net/en-us/profile` |
| Account | `msAccountBtn` | | | | | open `https://account.microsoft.com/` |
| Account | `savedAccountsList` | (auth `AccountManager`) | | | live | rows (6.3) |
| Account | `addAccountBtn` (`settings.accounts.add`) | | | | | `AddAccountDialog.show(window)` |
| Account | `logoutBtn` (`settings.accounts.signOutDefault`) | | | | | `confirmSignOut(default)` |
| Game | `minRamGb` spinner (double, step 0.1, editable) | `minRAM` (MB, long) | 512 | 0.2 .. min(32, max) GB | Save: `(long)(gb*1024)` | max spinner's min tracks `max(1.0, min)` |
| Game | `maxRamGb` spinner | `maxRAM` (MB) | 2048 | max(1.0, min) .. 64 GB | Save | min spinner's max tracks `min(32, max)`; on first crossing above 31 GB (31744 MB) warn toast `notification.settings.maxRamAboveCompressedOops.*` (`crossesCompressedOopsLimit(prev, new) = new > 31744 && prev <= 31744`) |
| Game | `sysRamLabel` | | | | | background OSHI total/available -> `settings.sysRamLabel(total GB 2dp, used GB 2dp)` (first OSHI query can stall seconds on macOS; never on the UI thread). Rust: `sysinfo` |
| Game | `jvmPresetSelection` | `jvmArgs` | `JVM_ARGS_VALUE_DEFAULT` (Aikar) | 4 presets + "Custom" (`settings.jvmPreset.custom`) | Save: `jvmArgsToPersist` | labels `settings.jvmPreset.option.{performance,lowMemory,debug,none}` |
| Game | `generateJvmArgsBtn`, `generateJvmArgsHint` | `jvmArgs` | | | live (on click) | hint `HardwareTunedJvmArgs.summary(maxRamGb)` (updates with max RAM spinner); click: `generate(maxRamGb)` -> save, select matching preset (normally Custom), success toast `notification.settings.jvmArgsGenerated.*`, tooltip `tooltip.settings.generateJvmArgs` |
| Appearance | `themeSelection` | `theme` | `Native (Mica)` | 7 themes | Save: `setTheme` + `forceThemeRefresh()` + `RgbIntegration.onMenu()` | on show, the saved value is matched case-insensitively to the canonical list; no match -> set `Automatic` (an old capitalisation bug reset "Native (Mica)" to Automatic every launch) |
| Appearance | `uiScaleGroup` (connected buttons 50..150 %, locale percent format) | `uiScalePercent` | 100 | 50/75/100/125/150 | live | `UiScale.setPercent` applies immediately; mark selected |
| Appearance | `wavyProgressToggle` | `wavyProgress` | true | | live | `WavyProgressBar.setWavyEnabled` |
| Appearance | `reduceMotionToggle` | `reduceMotion` | false | | live | `Motion.setReduceMotion` |
| Appearance | `languageSelection` | `localeOverride` (BCP-47 tag, "" = OS) | "" | item 0 `settings.language.osDefault(detected OS locale display name in the current UI locale)`, then `SupportedLocales.ENTRIES[i].displayName` (endonyms) | Save | index -> tag only (`overrideTagForIndex`), never by label; takes effect after restart (Save & Restart relaunches) |
| Appearance | `showPackBackgroundsToggle` | `showPackBackgrounds` | true | | live | next card rebuild picks it up |
| Appearance | `packColorsToggle` | `packColors` | true | | live | read when a detail window opens |
| Appearance | `imageCycleIntervalSelection` | `imageCycleInterval` | `30s` | 13 tokens (3.4.2), labels `settings.cycle.{seconds(n),minute,minutes(n),hour,hours(n),daily,weekly,never}` | live | `ModpackImageCycleClock.reconfigure()`; unknown saved token shows the default |
| Appearance | `imageCycleShuffleToggle` | `imageCycleShuffle` | false | | live | |
| Advanced | `debugCheckBox` | `debug` | false | disabled in dev builds | Save | |
| Advanced | `windowResizeCheckBox` | `resizableWindows` | true | | Save | `stage.setResizable(...)` |
| Advanced | `enhancedLoggingCheckBox` | `enhancedLogging` | (config) | | Save | |
| Advanced | `inGameConsoleCheckBox` (`settings.runningGames.keepOpen`) | `inGameConsole` | false | | Save | Running Games "show console on launch" |
| Advanced | `consoleLogMaxLinesSelection` | `consoleLogMaxLines` | 10000 | presets 1000, 5000, 10000, 25000, 50000, 0 (= `settings.consoleLogMaxLines.unlimited`); labels `settings.consoleLogMaxLines.lines(NumberFormat(n))`; unmatched saved value selects index 0 | Save (by index) | |
| Advanced | `batteryThrottleCheckBox` | `batteryThrottleEnable` | true | | Save | downloads throttle to 512 KiB/s below 30 % battery (other subsystem) |
| Advanced | `lwjglArmPatchCheckBox` | `lwjglArmPatch` | true | | Save | |
| Advanced | `defaultScanFrequencyCombo` | `defaultScanFrequency` (enum name) | (ModPackConfig) | `ScanFrequency.values()` labels `displayLabel()` | live | |
| Advanced | `autoBackupToggle` | `backupAutoBeforeUpdate` | true | | live | |
| Advanced | `backupIncludeSavesToggle` | `backupIncludeSaves` | false | | live | |
| Advanced | `backupMaxCountSpinner` (int, editable) | `backupMaxCountPerPack` | 3 | 0..100 (0 = no cap) | live | |
| Advanced | `backupMaxAgeDaysSpinner` | `backupMaxAgeDays` | 14 | 0..365 | live | |
| Advanced | `verifyAllGameFilesBtn` | | | | | no installed packs -> info toast `notification.settings.nothingToVerify.*`; else `VerifyAction.runForPacks(installed)` (shows LaunchProgress screen) |
| Advanced | `curseForgeApiKeyField` (password), Save / Clear, status label | `curseForgeApiKey` (encrypted) | none | | live | field never shows the stored key (shoulder-surf / screen recording); Save ignores blank, stores trimmed, clears field, toast `notification.settings.curseforgeKeySaved.*`; Clear stores null; status `settings.curseforge.keyConfigured` / `keyNotConfigured` from `hasCurseForgeApiKey()` |
| Network | `proxyEnableCheckBox` | `proxyEnable` | false | | Save | if enabled with a blank host: save as **off**, untick, warn `settings.proxy.hostRequired` |
| Network | `proxyTypeSelection` | `proxyType` | `HTTP` | `HTTP`, `SOCKS` | Save | |
| Network | `proxyHostField` (prompt `settings.proxyHost.prompt`) | `proxyHost` | "" | | Save | |
| Network | `proxyPortSpinner` (int, editable) | `proxyPort` | 8080 | 1..65535 | Save | then `NetworkUtilities.reloadProxy()` |
| Security | `scanFolderLabel`, `scanFolderBtn`, `scanBtn`, `scanProgressBar`, `scanOutputLabel` | (not stored) | launcher root | | | 6.4 |
| Security | `uriHandlerToggle` | `uriHandlerEnabled` | true | | live | `mmcl://` deep links |
| Security | `mcpServerToggle` | `mcpServerEnabled` | false | | live | `McpBootstrap.refresh()` starts/stops now (port closed, endpoint file removed); refresh status |
| Security | `mcpAutoApproveToggle` | `mcpAutoApproveReadOnly` | true | | live | |
| Security | `mcpAllowStateChangesToggle` | `mcpAllowStateChanges` | false | | live | rebuild tool registry via `McpBootstrap.refresh()` |
| Security | `mcpToolPolicyList` (one combo per tool) | `mcpToolPolicies` (MCP-owned encoding) | "" | Default / Always allow / Ask / Disabled (`settings.mcp.policy.*`) | live | `policies.setPolicy(tool, null|ALWAYS_ALLOW|ASK|DISABLED)`; row label `settings.mcp.tool.label(title, risk display name)`, min width 240 |
| Security | `mcpEndpointLabel`, `mcpTokenLabel`, reveal / copy / regenerate | `mcpPort`, `mcpToken` | | | | endpoint `McpClientSetup.endpointUrl(port)`, port = bound port if > 0 else config; token masked by `McpAccessToken.mask` unless revealed (`settings.mcp.connect.token.reveal`/`hide`); copy `ensureToken()`; regenerate asks `dialog.mcp.regenToken.*` then `regenerateToken()`, re-render snippet, refresh status |
| Security | `mcpClientSelection` + `mcpSetupSnippet` (text area) + `mcpCopySetupBtn` | | | Claude Code, Cursor, Codex, HTTP, stdio (`settings.mcp.connect.client.*`) | | snippet `McpClientSetup.snippet(client, launcherPath, nativeExe = !isJar(path), port, endpointFile, token)`; failure -> empty + silent warn; copy -> clipboard, label `settings.mcp.connect.copied` (reset on re-render) |
| Security | `mcpStatusLabel`, `mcpGrantsLabel`, `mcpSessionList`, `mcpActivityList`, Refresh, Clear activity, Revoke grants | (MCP runtime state) | | | | status `settings.mcp.status.running(port) + "  •  " + sessions(n)` or `.stopped`; grants `settings.mcp.grants.count(n)`; sessions `settings.mcp.sessions.entry(name or unnamed, elapsed, calls)`; activity rows (max `MCP_ACTIVITY_ROWS_SHOWN`) `settings.mcp.activity.entry(elapsed, tool, decision.<lower>)`; elapsed: < 1 min `justNow`, < 60 `minutesAgo(m)`, else `hoursAgo(h)` |
| System | `resetLauncherBtn` | | | | | refuse with warning `dialog.settings.resetLauncher.gamesRunning` if any session active; question `dialog.settings.resetLauncher.*`; `LauncherCore.resetLauncherAndRelaunch()` (deletes the folder and starts a fresh process so nothing in memory writes back) |
| System | `resetRuntimeBtn` | | | | | unsaved changes -> question `dialog.settings.unsavedOnResetRuntime.*` (1 save + continue, 2 continue, 0 stop); question `dialog.settings.resetRuntime.*`; `RuntimeManager.clearRuntime(c)` for each installed; log; reload Settings (on failure closeApp) |
| System | `manageRuntimeBtn` | | | | | `go_to(Runtime)` |
| System | `exportSettingsBtn` | | | | | save dialog (`dialog.fileChooser.exportSettings.title`, name `dialog.settings.exportSettings.defaultFilename`, `*.json`); `ConfigManager.exportConfig(file)` -> label `settings.exportBtn.success`/`failed` for 2 s then `label` |
| System | `importSettingsBtn` | | | | | open dialog (`settings.fileChooser.importTitle`); `importConfig(file)` -> success: label + reload Settings; failure: label `failed` for 2 s |
| System | `openFolderBtn` | | | | | open launcher root (create if missing) |
| System | `versionLabel` | | | | | dev `settings.versionLabel.devMode`, else `settings.versionLabel.normal(version)` |
| Discord | `discordCheckBox` | `discordRpc` | true | | Save | off -> `DiscordRpcUtility.exit()` |
| Discord | `discordInvitesCheckBox` | `discordInvites` | true | | Save | |
| RGB | `rgbEnableToggle` | `rgbEnable` | false | | live (ignores no-op fires) | refresh auto-toggle visibility, restart controller |
| RGB | `rgbBackendCombo` | `rgbBackend` | `auto` | `auto, openrgb, chroma-native, chroma, windows-dl, corsair, asus-aura, none`; labels Auto (`settings.rgb.backend.auto`), OpenRGB, Razer Chroma (Native), Razer Chroma (REST), Windows Dynamic Lighting, Corsair iCUE, ASUS Aura, None (`settings.rgb.backend.none`); index <-> token via `rgbBackendIndex` / `backendForIndex` (unknown -> 0 / auto) | live (skip unchanged) | refresh visibility, restart controller |
| RGB | `rgbAutoToggleHeader` + `rgbAutoToggleBox`: OpenRGB, Chroma Native, Windows DL, Corsair, ASUS Aura, Chroma REST toggles | `rgbEnableOpenRGB` (true), `rgbEnableChromaNative` (true), `rgbEnableWindowsDL` (true), `rgbEnableCorsair` (true), `rgbEnableAsusAura` (true), `rgbEnableChromaRest` (false) | | shown only when backend = auto and RGB enabled | live (skip unchanged) | restart controller |
| RGB | `rgbMenuEffectToggle` | `rgbMenuEffectEnable` | true | | live | `RgbIntegration.onMenu()` |
| RGB | `rgbEffectStyleSelection` | `rgbEffectStyle` | `breathe` | Solid/Breathe/Pulse/Cycle/Rainbow -> `solid/breathe/pulse/cycle/rainbow` (labels hard-coded English: localize in Rust) | live | `onMenu()` |
| RGB | `rgbUsePackColorsToggle` | `rgbUsePackColors` | true | | live | |
| RGB | `rgbHighlightKeysToggle` | `rgbHighlightKeys` | true | | live | |
| RGB | `rgbStatusChip` | | | | | disabled -> `settings.rgb.status.disabled` (base chip); not running / no backends -> `notDetected` (warn); else primary name (+ " +N") with worst health HEALTHY `connected` (success) / DEGRADED `degraded` (warn) / DEAD `unavailable` (danger) |
| RGB | `rgbTestBtn` | | | | | RGB off -> warn toast `notification.settings.rgbDisabled.*`; else set a solid magenta (255,0,255) effect named "Connection Test" for 2 s, restore the prior effect (null = stop); refresh chip |
| About | `aboutAppNameLabel`, `aboutVersionLabel` (`settings.aboutVersionLabel(version)`) | | | | | |
| About | `launcherUpdateCheckBox` | `launcherUpdateCheck` | (config) | | live | |
| About | `aboutWebsiteBtn` / `aboutSourceBtn` | | | | | `https://micatechnologies.com` / `https://github.com/MicaTechnologies/Mica-Minecraft-Launcher` |

`restartRgbController()` (background): `RgbController.getInstance().stop()`; if enabled
`start(RgbBackendRegistry.resolveBackendsFromConfig())`; errors silent-warned; refresh chip.

JVM preset logic (pure, port exactly; preset names are stored ids, not labels):
```
PRESET_NAMES = ["Performance (Aikar's Flags)", "Low Memory", "Debug", "None"]
PRESET_ARGS  = [JVM_ARGS_VALUE_DEFAULT,
                "-XX:+UseG1GC -XX:MaxGCPauseMillis=50 -XX:G1HeapRegionSize=1M",
                "-XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200 -ea -verbose:gc -Xlog:gc*:file=gc.log:time,uptime,level,tags",
                ""]
JVM_ARGS_VALUE_DEFAULT = "-XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200 -XX:+UnlockExperimentalVMOptions -XX:+ExplicitGCInvokesConcurrent -XX:+AlwaysPreTouch -XX:G1NewSizePercent=30 -XX:G1MaxNewSizePercent=40 -XX:G1HeapRegionSize=16M -XX:G1ReservePercent=20 -XX:G1HeapWastePercent=5 -XX:G1MixedGCCountTarget=4 -XX:InitiatingHeapOccupancyPercent=15 -XX:G1MixedGCLiveThresholdPercent=90 -XX:G1RSetUpdatingPauseTimePercent=5 -XX:SurvivorRatio=32 -XX:+PerfDisableSharedMem -XX:MaxTenuringThreshold=1 -Dusing.aikars.flags=https://mcflags.emc.gs -Daikars.new.flags=true"
(the pre-2026.10 default used -XX:+DisableExplicitGC; config migration maps it to the new default,
 so the migrated default selects Performance)
preset_index_for_args(args) = first i with PRESET_ARGS[i] == args, else -1 -> "Custom"
preset_index_of(name) = index in PRESET_NAMES, else -1
jvm_args_to_persist(i, saved) = if i out of range -> None (Custom never writes)
                                else if PRESET_ARGS[i] == saved -> None else Some(PRESET_ARGS[i])
```
Language logic: `override_tag_for_index(i) = if 1 <= i <= len(ENTRIES) { ENTRIES[i-1].tag } else ""`;
`is_language_change_pending(i, saved) = !override_tag_for_index(i).eq_ignore_ascii_case(saved ?? "")`.
Save-and-Restart visible iff pending; after a plain Save it hides (the saved override now matches).

`hasUnsavedChanges()` (only the Save-written fields): min/max RAM (MB as above) vs config; debug,
discord, invites, resizable, enhanced logging, in-game console, battery throttle, LWJGL patch;
theme (case-insensitive); proxy enable/host/port/type; console max lines (by index); JVM preset
(dirty iff `jvm_args_to_persist(...)` is Some); language (tag vs override, case-insensitive).
Fields written live are never dirty.

`persistSettings()` order: min RAM, max RAM (+ oops warning), debug, discord (+ exit RPC when off),
invites, resizable (+ apply), enhanced logging, in-game console, console lines (by index), battery,
LWJGL, theme (only if a canonical theme is selected; + theme refresh + RGB menu repaint), locale
override, proxy (host-missing rule) + `reloadProxy()`, JVM args (if Some). Safe to call off the UI
thread (Java reads FX controls from a worker; in Rust snapshot the form state on the foreground
first, then write config in the background).

Buttons:
- Save (background): `persistSettings()`, refresh Save-and-Restart, label
  `settings.saveBtn.saved` for 5 s then `settings.saveBtn.label`.
- Save and Restart: `confirmQuitWhileGamesRun()` (a relaunch ends the process); persist;
  `LauncherCore.relaunchApp()` (new process; dev fallback in-process). In-process restart cannot
  fully re-localize: Java binds 89 static-final strings at class load. In Rust, if the localization
  layer re-reads strings on each `get`, a live language switch becomes possible; keep the
  restart button for parity anyway.
- Return (background): unsaved -> question `dialog.settings.unsavedOnReturn.{title,header,body}`,
  `button.save` (1: persist then Home) / `button.return` (2: Home) / Cancel (stay); else Home.
- `confirmNavigateAway()` (menu bar, shortcuts, dock): same prompt; 1 -> persist **synchronously**
  then true; 2 -> true; 0 -> false.

`afterShow()`: smooth scroll every scroll pane, theme selection (above), tooltips
(`tooltip.settings.{minRam,maxRam,debug,windowResize,discord,discordInvites,enhancedLogging,
inGameConsole,batteryThrottle,theme,jvmPreset,proxyEnable,proxyHost,proxyPort,proxyType,
resetLauncher,resetRuntime,exportSettings,importSettings}`), help button, offline chip, global
shortcuts. `cleanup()`: remove the account listener and the RAM spinner listeners.
`showCategory(int)` is public (Home's account chip and the switcher open category 0).

### 6.3 Saved accounts list
Repainted from an account-manager listener (posted to the UI thread). Per row (HBox spacing 10,
`accountRow`): 32 px round avatar; name (bold) over status (`statusKey` text; session-only ->
`account.status.withSessionOnly(status)`; style `accountStatusWarning` when needs sign-in, else
muted); then buttons (h 28): `settings.accounts.signInAgain` (`primary`, needs sign-in ->
Add-account dialog), `settings.accounts.makeDefault` (can make default -> background
`setDefault(uuid)`), `settings.accounts.signOut` (`dangerZone` -> `confirmSignOut`). Header labels
show the default account (name, uuid, avatar).
`confirmSignOut(uuid, name)` (background): question `settings.accounts.confirmSignOut.{title,
header(name), body}`, `settings.accounts.signOut` / Cancel; != 1 stop; if it is the last account and
`!confirmRestartWhileGamesRun()` stop; `accounts().remove(uuid)`; if none remain
`LauncherCore.restartApp()` (back to the login screen).

### 6.4 Malware scan
`scanFolder` defaults to the launcher root (`settings.scanFolderLabel(path)`). Change Folder ->
directory dialog (`settings.scanFolderDialog.title`, initial = current). Scan toggles: while
scanning, the button cancels (`Main.cancelScanIfRunning()`, `scanningCanceled = true`). Start
(background): button `settings.scanBtn.cancel`, hide/disable Change Folder, show progress + output,
disable Save and Return; progress 0; run; progress 1; error -> output `settings.scanOutputLabel.failed`
+ log; restore UI (button `settings.scanBtn.label`). Scan: threads `max(1, cores/2)`; jarscanner
`Main.run(threads, path, emitWalkErrors=true, [], logOutput, progressOutput)` where log output
strips ANSI red/green/white/reset codes and shows the latest line; progress = processed/total
(indeterminate while total <= 0). Then `SupplementalScanner.scanFolder(path, [], threads)` counting
HIGH vs others, logging `settings.scan.supplemental(finding)` (IO error ->
`settings.scan.supplementalIoError(msg)`). Summary: total > 0 ->
`settings.scan.infectionsFound(stage1, stage2, high, medium)`; else cancelled ->
`settings.scan.canceled` (reset flag); else `settings.scan.noInfections`. The scanner itself is the
security subsystem's (Rust has no jarscanner; see open questions).

---

## 7. Runtime Management (`MCLauncherRuntimeGui`, `runtimeManagementGUI.fxml`)
Layout: navbar 52 (brand, `%runtime.navbar.title`, spacer, offline chip, help), announcement 0/40
(`getAnnouncementConfig()`), content (padding 16 24, spacing 10): heading `%runtime.section.installed`
(`heading-h3`), list (grows, `tileList`), row: status label (muted, initial
`%runtime.status.selectAPrompt`), spacer, Refresh (`%runtime.refreshBtn`), Delete (`errorTonalBtn`,
`%runtime.deleteBtn`), Delete All (`textBtn errorTextBtn`, `%runtime.deleteAllBtn`); bottom bar 44:
info `%runtime.infoLabel` (muted), spacer, Return (`%runtime.returnBtn` -> Settings).
`allowsToolbarNavigation = false` (install/verify/delete must not be abandoned). Close -> `closeApp()`.

Data: `RuntimeManager.getInstalledRuntimes()` -> list of maps with `component`, `version`
(optional), `sizeMB`. Listing sizes every runtime tree (thousands of files): always off the UI
thread, including the initial load.
- `refreshRuntimeList()` (background): store the list (volatile); UI: set items, install the empty
  placeholder once (badge EMPTY_BOX, heading `runtime.empty.heading` (`heading-h3`), body
  `runtime.empty.body` (muted, wraps, max 420, centred), padding 24); status
  `runtime.status.countInstalled(n)` or `runtime.status.noneInstalled`.
- Cell: tile with a RUNTIME glyph badge, title = version known ? `runtime.list.title(version)`
  ("Java 21.0.3") : component; detail = known ? `runtime.list.detail(component, sizeMB)` :
  `runtime.list.size(sizeMB)` (`settingsHint`); classes `tileListCell` + position class (first,
  middle, last, only).
- Delete: resolve the selected index on the UI thread against **one snapshot** of the list (a
  concurrent refresh could otherwise delete the wrong runtime); none -> status
  `runtime.status.noneSelected`. Background question `dialog.runtime.delete.{title, header(component),
  body}`, `button.delete` / Cancel; != 1 stop. `clearRuntime(component)` -> status
  `runtime.status.deleted(component)`; IOException -> log `log.runtime.deleteFailed(component)`,
  status `runtime.error.inUse(component)` if `RuntimeManager.isInUse(component)` else
  `runtime.status.deleteFailed`. Then refresh.
- Delete All (background): snapshot; empty -> `runtime.status.noneInstalled`; question
  `dialog.runtime.deleteAll.*`, `button.deleteAll`; clear each (log failures); status
  `runtime.status.allDeleted`; refresh.
GPUI: gpui-component `List` or a plain `uniform_list` with selection state; selection index +
snapshot `Arc<Vec<RuntimeInfo>>`.

---

## 8. Progress screens

### 8.1 `MCLauncherProgressGui` (`progressGUI.fxml`)
Root `rootPane hero-surface` (min 750x475). Backdrop: `progressGlow` region filling the window
(mouse-transparent) and `BlockyHorizon` along the bottom. Centred card `progressCard` (VBox spacing
10, centred, pref 460, min 400, max 480, padding 32 32 28 32):
- voxel row (HBox bottom-centre, spacing 14, bottom padding 8): three cubes (same faces/colours as
  2.3.9) each above an ellipse shadow (rx 15, ry 3, `voxelShadow`, spacing 6);
- `upperLabel` (`progressTitle`, centred, wraps, max 400, default `%progress.title.justAMoment`);
- `sectionLabel` (`progressStep`); wavy progress bar (pref/max 360, margins 6/2);
- `detailLabel` (`progressDetail`); `speedLabel` chip (`progressChip`, hidden while empty);
- cancel row (hidden, top padding 10) with a `tonalBtn` `%progress.cancelBtn.label`.
Voxel hop (skipped with reduce motion): per cube a 1000 ms infinite timeline, delays 0/150/300 ms:
t=0 translateY 0, scaleY 0.9, scaleX 1.06; t=90 scale -> 1 (rise easing); t=380 translateY -14
(rise = cubic-bezier(0.2,0,0,1)); t=660 translateY 0 (fall = cubic-bezier(0.4,0,1,1)); rest until
1000. Shadow: t=0 scaleX 1 opacity 1; t=380 scaleX 0.6 opacity 0.45 (rise); t=660 back (fall).
API (any thread; marshalled to UI):
- `setUpperLabelText`, `setSectionText`, `setDetailText`; `setSpeedText` (hide + unmanage when
  empty); `setLowerLabelText` = section; `setLabelTexts(upper, lower)`.
- `setProgress(p)`: p on 0..100 (`GameModPackProgressProvider.PROGRESS_PERCENT_BASE` = 100) or
  `INDETERMINATE` (-1); bar gets p/100 (or -1), also mirrored to the taskbar
  (`TaskbarProgressManager.setProgress`).
- `setCancelHandler(handler?)`: null hides the whole row (unmanaged, so no reserved padding),
  clears the action, resets the label; non-null shows it; click -> label
  `progress.cancelBtn.cancelling`, disabled (optimistic: the worker may be mid-read), run the
  handler; if it throws restore label + enable.
`setup()`: close -> `closeApp()`. `afterShow()`: start voxels; upper = `progress.justAMoment`, others
empty; attach the taskbar progress (only after the window is realised: earlier native access
crashed); progress 0. `cleanup()`: `TaskbarProgressManager.stop()` (the wrapper lives for the whole
app; never close it per screen), stop voxels. `allowsToolbarNavigation = false`.

### 8.2 `MCLauncherLaunchProgressGui` (`launchProgressGUI.fxml`)
Same backdrop; card `progressCard` (pref 600, min 520, max 640, inner VBox spacing 14, padding 28
28 24 28): `titleLabel` (`progressTitle`, default `%progress.title.preparingLaunch`, max 560),
`subtitleLabel` (`progressStep`, hidden + unmanaged when empty), `rowsBox` (VBox spacing 10, padding
6 0, max 560), cancel row (hidden; top padding 8). Used by `VerifyAction` (verify-only runs); game
launches show their steps in the Running Games window instead.
- `setTitle(text?)` (null -> ""), `setSubtitle(text?)` (empty hides).
- `attachToTracker(tracker)`: detach any previous; clear rows; one row per `tracker.steps()` (skipped
  steps are simply absent from the list; that is how vanilla hides Forge stages); render each;
  register a listener that **coalesces**: `if flush_scheduled.compare_and_set(false, true)` post one
  UI task that clears the flag, re-renders every row from the tracker's current state and refreshes
  the taskbar once (a warm launch fires ~3500-4500 per-file events; one post per event froze the
  window).
- Row (HBox spacing 12, `launchStepRow` padding 4 0): `StepBadge(state)`, text column (title
  `type-title-small`, sub-text `subtle type-label-small`), spacer, wavy bar (pref 200). Sub-text and
  bar are always *managed* (only visibility toggles) so row height never changes (rows used to
  "breathe").
- `renderRow`: badge state; sub-text = `errorMessage` when FAILED and non-empty, else `subText`;
  visible iff non-empty. RUNNING: cancel pending hide, show bar, value = progress. DONE: if the bar
  was visible snap to 1.0 and hide after 160 ms (restartable); else keep hidden. PENDING / FAILED /
  SKIPPED: cancel pending hide, hide.
- Taskbar roll-up: sum over steps (DONE/FAILED/SKIPPED = 1, RUNNING = progress, PENDING = 0) / count
  -> `TaskbarProgressManager.setLaunchProgress(tracker, fraction)` (0..1).
- `setCancelHandler`: as 8.1 but a throwing handler is ignored (button stays disabled).
- `setup()`: close -> `closeApp()`. `afterShow()`: attach taskbar. `cleanup()`: detach the listener;
  `TaskbarProgressManager.endLaunchProgress(tracker)` on UI. `allowsToolbarNavigation = false`.
`StepBadge` states: PENDING outlined ring (no glyph), RUNNING tonal disc with dot, DONE filled primary
with check, FAILED error colour with cross, SKIPPED muted disc with dash (`ProgressVisualsTest`:
every state but pending has a glyph).

GPUI: the tracker listener runs on worker threads; implement the coalescing with an `AtomicBool`
and `AsyncApp::update` (or a `smol::channel` with `try_send` capacity 1 that the view drains each
frame). Throttling to frame rate comes for free if the view only calls `cx.notify()`.

### 8.3 Progress visuals (shared components)
- `WavyProgressBar`: stroke 4, amplitude 3, wavelength 28, gap 4 before the flat track, stop dot at
  the end, wave period 1.2 s (phase from the wall clock so rebuilt bars keep moving), indeterminate
  sweep period 1.8 s with segment length 0.38; animates only while visible (`AnimationGate`);
  global flat mode when `wavyProgress` is off. GPUI: a `canvas` element drawing a `Path` (sampled
  sine, `PathBuilder::stroke`) and requesting animation frames only while progress is visible.
- `LoadingIndicator`: M3 Expressive morphing shape; shapes as polar radius `1 + a*cos(k*theta)` at
  fixed angles (9-lobed cookie, rounded pentagon, 4-lobed cookie, sunburst, oval, hexagon, 8-lobed
  cookie), rotates while morphing point by point with overshoot. Port the shape table and blend.
- `BlockyHorizon`: two layers of block-sized columns, heights from a fixed function of the column
  index (deterministic skyline; does not jump on resize), front layer with a grass top.
- `AnimationGate`: stops ambient animation when not visible / window hidden / reduce motion.

---

## 9. Help window (`MCLauncherHelpWindow`, `HelpTopic`)

Topics (enum order = sidebar order; key, page):
| Topic | Name key | Page |
|---|---|---|
| GETTING_STARTED | `help.topic.name.gettingStarted` | `help/getting-started.html` |
| MAIN_SCREEN | `help.topic.name.mainScreen` | `help/main-screen.html` |
| LOGIN | `help.topic.name.login` | `help/login.html` |
| SETTINGS | `help.topic.name.settings` | `help/settings.html` |
| BROWSE_LIBRARY | `help.topic.name.browse` | `help/browse-library.html` |
| MODPACK_EDITOR | `help.topic.name.modpackEditor` | `help/modpack-editor.html` |
| GAME_CONSOLE | `help.topic.name.gameConsole` | `help/game-console.html` |
| VANILLA_VERSIONS | `help.topic.name.vanillaVersions` | `help/vanilla-versions.html` |
| RUNTIME_MANAGEMENT | `help.topic.name.runtimeManagement` | `help/runtime-management.html` |
| KEYBOARD_SHORTCUTS | `help.topic.name.keyboardShortcuts` | `help/keyboard-shortcuts.html` |
| JVM_PRESETS | `help.topic.name.jvmPresets` | `help/jvm-presets.html` |
| ACTION_REFERENCE | `help.topic.name.actionReference` | `help/action-reference.html` |
| LANGUAGE | `help.topic.name.language` | `help/language.html` |
| TROUBLESHOOTING | `help.topic.name.troubleshooting` | `help/troubleshooting.html` |

Translated pages: `help/<lang>_<COUNTRY>/<file>` then `help/<lang>/<file>`, else English
(`localizedPagePath(englishPath, locale, exists)`; empty language -> English). Shipped folders:
ar, de, es, fr, hi, it, ja, ko, nl, pl, pt_BR, ru, sv, tr, zh_CN, zh_TW. RTL languages:
`ar, he, iw, fa, ur`; `dir="rtl"` only when a translated page was actually found.

Window: singleton, built lazily; title `window.help.title`; app icon; owner = main window (stays
above it); scene 800x600 x scale, min 600x400 (`ScaledMinSize.follow`). Layout: left sidebar
(`stripPane`) = header `help.sidebar.header` (`heading-h3 helpSidebarHeader`, padding 14 14 8 14)
over the topic list (pref 180, min 160, grows); centre = content. Selecting a topic loads it;
`loadTopic` selects it in the list without re-firing.

`show(topic)` (UI thread): read OS dark once for the whole pass (the detector can flip between
back-to-back calls, which split the window into dark root + light content); build if needed; load
the topic; show; to front; title-bar dark mode (`!lightChrome`, light = Light or Native over OS
light); backdrop Mica iff native; first show -> full repaint.
`hide()`, `cleanup()` (on session end / restart: hide + close, clear the content, null all statics,
so a restarted session never reuses a stage owned by a dead window), `refreshTheme()` (called from
every main-window theme pass: no-op unless `theme + ":" + osDark` changed; reloading re-read the
page and reset scroll on every screen switch).

`loadTopic(topic)`: resolve the page; read it (UTF-8); inline (not link) `help/help-style.css` +
the theme sheet (`help-native.css` (native, OS dark) / `help-light.css` (native light, Automatic
light, Light) / `help-dark.css` (Automatic dark, Dark, default) / `help-bluegray.css` /
`help-orangepurple.css` / `help-creeper.css`) into
`<!DOCTYPE html><html[ dir="rtl"]><head><meta charset="UTF-8"><style>BASE\nTHEME</style></head><body>PAGE</body></html>`
(linked `file:` sheets are blocked for `loadContent` documents in JavaFX 26's WebKit: unstyled
black-on-dark text). Missing page -> inline page with `help.topic.unavailable.heading` and
`help.topic.unavailable.body(name)`, HTML-escaped (`& < > "`; names like "Language & Localization").
Exception -> `help.topic.loadError`.
Links: `help://topic/<NAME>` anchors navigate in-window: name upper-cased, `-` -> `_`, matched to
the enum (unknown -> silent warn). Java injects a JS click handler reading `getAttribute('href')`
(the resolved `.href` mangles the custom scheme under about:blank) and exposes a Java bridge; any
main-frame navigation away from about:blank is cancelled so remote content can never reach the
bridge. The web view is transparent (reflection hack) so the theme bg shows through; body bg in
`help-style.css` is transparent.

Native theme: macOS transparent root + vibrancy; Windows/Linux solid `#0C1017` (OS dark) /
`#FFFFFF` (OS light) so the help sheet's text colour always matches its surface.

GPUI port options (decision needed, see open questions): (a) render the help pages natively with
gpui-component's `TextView` (markdown / simple HTML) after converting the 14 x 17 pages to Markdown
at build time; `help://topic/X` links handled by the link callback; theme comes from the GPUI
theme, no CSS; or (b) embed a `wry` web view (keeps the HTML and help CSS as-is, needs the same
navigation lock-down via `with_navigation_handler` returning false for everything but the
initial document, and `with_ipc_handler` for `help://` clicks). Recommendation: (a), because it
removes the web engine dependency from the help window; the pages are static prose with headings,
lists, tables, `kbd` and links.

---

## 10. Class-by-class map

| Java class | Rust module / type | Key items |
|---|---|---|
| `MCLauncherGuiController` | `mmcl_ui::shell::controller::GuiController` (Global) + `GuiHandle` (Send+Sync) | `go_to(Screen)`, `may_navigate_away`, `may_leave_for_launch`, `confirm_quit_while_games_run`, `confirm_restart_while_games_run`, `request_quit`, `request_focus`, `is_launcher_focused`, `current_screen`, `force_theme_refresh`, `exit` |
| `MCLauncherGuiWindow` | `mmcl_ui::shell::main_window::MainWindow` | `set_screen`, `apply_theme`, bounds persistence, close guard, placeholder, monitor nudge (`cfg(windows)`) |
| `MCLauncherAbstractGui` | `mmcl_ui::shell::screen::Screen` trait + `ScreenId` enum | lifecycle hooks |
| `WindowPlacement` | `mmcl_ui_model::placement` | `title_bar_on_screen`, `clamp_into` |
| `ScaledMinSize` | `mmcl_ui_model::min_size::clamp` + `mmcl_ui::shell::min_size::apply/follow` | |
| `UiScale`, `ScaledRoot` | `mmcl_ui::scale::{UiScale (Global), nearest, apply_rem_size}` | dock hosting moves to the Running Games spec |
| `GUIUtilities` | `mmcl_ui::dialogs` (`ask`, `ask_multiline`, `error`, `warn`, `error_retry`, `sanitize_one_line`, `sanitize_multiline`), `mmcl_ui::open::{open_url, open_folder}` | |
| `KeyboardShortcutManager` | `mmcl_ui::actions` (actions + `bind_global_keys`) | |
| `SystemMenuBarManager` | `mmcl_ui::menus::build_app_menus` (`cx.set_menus`) | |
| `HelpTopic` | `mmcl_ui_model::help::HelpTopic` | `name_key`, `page_path`, `from_link_name` |
| `MCLauncherHelpWindow` | `mmcl_ui::help::HelpWindow` (singleton per session) | `show`, `hide`, `refresh_theme`, `cleanup`; pure `localized_page_path`, `is_right_to_left`, `escape_html` in `mmcl_ui_model::help` |
| `MCLauncherMainGui` | `mmcl_ui::screens::home::{HomeScreen, HeroCard}` | `rebuild_cards`, `select_modpack`, `refresh_available_packs`, `set_announcement_row` |
| `MCLauncherMainGui` statics (colours, context menu, exports) | `mmcl_ui::pack_visuals::{dominant_palette, gradient_stops, palette_cache}`, `mmcl_ui::pack_menu::build_pack_context_menu`, `mmcl_ui::official_export_flow` | `prefetch_available_modpack_backgrounds`, `sample_dominant_pack_palette` (public for RGB/modal) |
| `MCLauncherGameLibraryGui` | `mmcl_ui::screens::browse::{BrowseScreen, LibraryCard, ImportProgressCard}` + `mmcl_ui_model::library::{LibraryEntry, collect_entries, sort_entries}` | `confirm_and_uninstall_modpack` (public) |
| `LibraryViewModel` | `mmcl_ui_model::library_vm::LibraryViewModel` | |
| `LibrarySortKeys` | `mmcl_ui_model::sort_keys` | `last_played`, `total_played`, `last_update` (i64::MIN sentinel) |
| `CardPool` | `mmcl_ui::cards::CardCache<K, V>` (entities keyed by pack id) | |
| `FilterOptionLabels`, `SettingsOptionLabels` | `mmcl_ui_model::labels` | `key(prefix, id)`, `theme_label`, `jvm_preset_label` |
| `ModpackImageResolver` | `mmcl_ui::images::resolver` | disk-first logo/background resolution |
| `ModpackImageCycleClock` | `mmcl_ui::images::cycle_clock::CycleClock` (Global) | `register -> Subscription`, `reconfigure`, `token_to_duration` (pure) |
| `PackBackgroundLayer` | `mmcl_ui::images::background::PackBackground` | `cover_viewport`, `decode_width` (pure), LRU 96 MB |
| `ImageFadeIn` | animation helper | |
| `LogoTransparencyDetector` | `mmcl_ui_model::logo_transparency::is_transparent_edged(rgba, w, h)` + cache | |
| `PlaceholderLogoFactory` | `mmcl_ui::images::placeholder_logos` | vanilla / loader placeholders, official logo disk cache `<config>/loader-logos/` |
| `AvatarImages` | `mmcl_ui::images::avatars::Avatars` (Global) | |
| `AccountListModel` | `mmcl_ui_model::accounts::{AccountRow, rows}` | |
| `AccountSwitcherMenu` | `mmcl_ui::accounts::switcher` | |
| `AddAccountDialog` | `mmcl_ui::accounts::add_account_window` | |
| `SignInPanel` | `mmcl_ui::signin::SignInPanel` | `load_sign_in`, `show_redeeming`, `fail_and_retry`, `remember` |
| `MicrosoftSignIn` | `mmcl_ui_model::signin::{login_url, parse_callback, Callback, USER_AGENT}` + `mmcl_ui::signin::webview` | |
| `MCLauncherLoginGui` | `mmcl_ui::screens::login::LoginScreen` | `wait_for_login_success` |
| `MCLauncherSettingsGui` | `mmcl_ui::screens::settings::{SettingsScreen, panes::*}` + `mmcl_ui_model::settings_logic` | `show_category`, `persist`, `has_unsaved_changes`, preset/language/proxy/oops logic |
| `SettingsNavTile`, `SettingsGroups` | `mmcl_ui::screens::settings::tiles` + `mmcl_ui_model::tiles::position_class` | |
| `MCLauncherRuntimeGui` | `mmcl_ui::screens::runtime::RuntimeScreen` | |
| `MCLauncherProgressGui` | `mmcl_ui::screens::progress::ProgressScreen` + `ProgressHandle` (Send) | |
| `MCLauncherLaunchProgressGui` | `mmcl_ui::screens::launch_progress::LaunchProgressScreen` + `LaunchProgressHandle` | |
| `StepBadge`, `WavyProgressBar`, `LoadingIndicator`, `BlockyHorizon`, `AnimationGate` | `mmcl_ui::widgets::{step_badge, wavy_progress, loading_indicator, blocky_horizon}`; pure geometry in `mmcl_ui_model::visuals` | |
| `MaterialSwitchSkin`, `FilterChip`, `SearchFields`, `IconButtons`, `EmptyStates`, `TooltipManager`, `OfflineIndicator`, `AnnouncementBanners` | `mmcl_ui::widgets::{switch, filter_chip, search_field, icon_button, empty_state, tooltip, offline_chip, banner}` | |
| `Motion` | `mmcl_ui::motion` (`cubic_bezier`, `enter`, `exit`, `fade_through`, `ReduceMotion` Global) | |
| `ShapeScale` | `mmcl_theme::shape` consts | |
| `BundledFonts` | `mmcl_ui::fonts::register(cx)` (`include_bytes!`) | |
| `WindowIcons`, `LauncherIcons` | `mmcl_ui::assets` (`AssetSource` + icon names) | |
| theme sheets + `themeStylesheetPaths`, `themeBgHex*`, `installCurrentThemeStylesheets` | `mmcl_theme::{ThemeId, TokenSheet, Theme, resolve, ActiveTheme (Global)}` | |
| `MCLauncherQuickStartWizard` | other spec (opened from Home `after_show`) | |

---

## 11. Proposed crates and public API

### 11.1 Crates
- `mmcl-theme` (no GPUI dependency in its core; an optional `gpui` feature adds `Hsla`
  conversions): token tables for the seven sheets, theme id parsing (exact config strings),
  resolution `(ThemeId, os_dark, platform) -> TokenSheet`, `bg_hex`, `is_light_chrome`, shape and
  type scales, motion tokens, contrast checker for tests.
- `mmcl-ui-model` (pure, no GPUI): view models and algorithms that are unit-tested: library VM,
  entry collection/sorting (over traits for pack/vanilla/loader sources), account rows, sign-in URL
  and callback parsing, window placement, min-size clamp, UI scale snapping, labels, settings logic,
  help paths, dominant colour palette, logo transparency, cover viewport, decode width, cycle tokens,
  progress-row rendering rules, launch-progress roll-up.
- `mmcl-ui` (GPUI): shell, screens, widgets, dialogs, images, help window, sign-in panel, menus,
  actions. Depends on `mmcl-theme`, `mmcl-ui-model` and the service crates of other subsystems.
- `mmcl-signin-webview` (optional, platform-gated): the embedded Microsoft page (wry), isolated so
  the rest of the UI builds without a web engine.
- `mmcl-app` (exists): `main.rs` bootstraps `Application`, registers globals, runs the session.

### 11.2 API sketch (what other subsystems call)
```rust
// mmcl_ui::shell
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum ScreenId { Home, Browse, Settings, Runtime, Login, Progress, LaunchProgress, Editor }

pub enum ScreenRequest { Home, Browse, Settings { category: Option<usize> }, Runtime, Login,
                         Progress, LaunchProgress, Editor { initial_pack: Option<PackRef> } }

/// Cloneable, Send + Sync handle for background threads (auth, launch, MCP, tray, deep links).
#[derive(Clone)]
pub struct GuiHandle { /* AsyncApp-backed sender + Arc<GuiState> */ }
impl GuiHandle {
    pub fn is_started(&self) -> bool;
    pub fn is_launcher_focused(&self) -> bool;
    pub fn current_screen(&self) -> Option<ScreenId>;
    pub async fn go_to(&self, req: ScreenRequest) -> anyhow::Result<()>;
    pub async fn go_to_progress(&self) -> anyhow::Result<ProgressHandle>;
    pub async fn go_to_launch_progress(&self) -> anyhow::Result<LaunchProgressHandle>;
    pub async fn go_to_login(&self) -> anyhow::Result<LoginHandle>;
    pub async fn may_navigate_away(&self) -> bool;
    pub fn may_leave_for_launch(&self) -> bool;
    pub async fn confirm_quit_while_games_run(&self) -> bool;
    pub async fn confirm_restart_while_games_run(&self) -> bool;
    pub fn request_quit(&self);
    pub fn request_focus(&self);
    pub fn force_theme_refresh(&self);
    pub fn select_modpack(&self, pack: PackRef);            // CLI -c <pack>
    pub fn rebuild_home_if_shown(&self);                     // deep-link installs
    pub async fn exit(&self);
    // dialogs usable from any subsystem
    pub async fn ask(&self, q: Question) -> Answer;
    pub async fn ask_multiline(&self, q: Question) -> Answer;
    pub async fn show_error(&self, text: String);
    pub async fn show_warning(&self, text: String);
    pub async fn show_error_retry(&self, text: String, retry_label: String) -> bool;
}
pub struct Question { pub title: String, pub header: String, pub body: String,
                      pub primary: String, pub secondary: String }
#[derive(Clone, Copy, PartialEq, Eq)] pub enum Answer { Closed = 0, Primary = 1, Secondary = 2 }

#[derive(Clone)] pub struct ProgressHandle { /* weak view + sender */ }
impl ProgressHandle {
    pub fn set_upper(&self, s: impl Into<SharedString>);
    pub fn set_section(&self, s: impl Into<SharedString>);
    pub fn set_detail(&self, s: impl Into<SharedString>);
    pub fn set_speed(&self, s: impl Into<SharedString>);
    pub fn set_progress(&self, p: Progress);              // Progress::Percent(f64 0..=100) | Indeterminate
    pub fn set_cancel_handler(&self, h: Option<Arc<dyn Fn() + Send + Sync>>);
}
#[derive(Clone)] pub struct LaunchProgressHandle { /* ... */ }
impl LaunchProgressHandle {
    pub fn set_title(&self, s: Option<String>);
    pub fn set_subtitle(&self, s: Option<String>);
    pub fn attach_tracker(&self, t: Arc<LaunchProgressTracker>);
    pub fn set_cancel_handler(&self, h: Option<Arc<dyn Fn() + Send + Sync>>);
}
#[derive(Clone)] pub struct LoginHandle { /* ... */ }
impl LoginHandle { pub async fn wait_for_login_success(&self); }

// mmcl_ui::help
pub fn show_help(cx: &mut App, topic: HelpTopic);

// mmcl_ui::accounts
pub fn show_add_account(owner: Option<AnyWindowHandle>, cx: &mut App);

// mmcl_ui::pack_visuals (used by RGB integration and the detail modal)
pub fn sample_dominant_pack_palette(pack: &dyn PackImages, count: usize) -> Option<Vec<Rgb>>;
pub fn prefetch_available_modpack_backgrounds(packs: Vec<PackRef>, exec: &BackgroundExecutor);

// mmcl_ui::screens::browse
pub async fn confirm_and_uninstall_modpack(gui: &GuiHandle, pack: PackRef, display_name: String,
    status: Option<StatusSink>) -> bool;

// mmcl_theme
pub enum ThemeId { Automatic, Dark, Light, BlueGray, OrangePurple, Creeper, Native }
impl ThemeId { pub fn from_config(s: &str) -> Option<Self>; pub fn config_value(self) -> &'static str; }
pub enum TokenSheet { Dark, Light, BlueGray, OrangePurple, Creeper, Native, NativeLight }
pub fn resolve(theme: ThemeId, os_dark: bool, platform: Platform) -> TokenSheet;
pub fn is_light_chrome(theme: ThemeId, os_dark: bool) -> bool;
pub struct Tokens { pub color_bg: Rgba, /* every -color-* and -md-* token as a field */ }
pub fn tokens(sheet: TokenSheet) -> &'static Tokens;
pub fn solid_bg(sheet: TokenSheet, secondary_window: bool) -> Rgba;

// mmcl_ui_model (pure; examples)
pub struct LibraryViewModel { /* ... */ }
pub struct PageBounds { pub total_items: usize, pub total_pages: usize, pub start: usize, pub end: usize }
pub fn title_bar_on_screen(b: Rect, screens: &[Rect]) -> bool;
pub fn clamp_into(b: Rect, screen: Rect) -> Rect;
pub fn clamp_min(base: f64, scale: f64, available: f64) -> f64;
pub fn nearest_scale(percent: i32) -> i32;
pub fn parse_callback(location: &str) -> Callback;
pub fn account_rows(accounts: &[AccountInfo]) -> Vec<AccountRow>;
pub fn dominant_palette(rgba: &[u8], w: u32, h: u32, count: usize) -> Option<Vec<[f32; 3]>>;
pub fn gradient_stops(palette: &[[f32; 3]]) -> Vec<([f32; 3], f32)>;
pub fn cover_viewport(iw: f64, ih: f64, bw: f64, bh: f64) -> Rect;
pub fn decode_width(box_w: f64, scale: f64) -> u32;
pub fn cycle_interval(token: &str) -> Option<Duration>;
pub fn jvm_preset_index_for_args(args: &str) -> Option<usize>;
pub fn jvm_args_to_persist(selected: Option<usize>, saved: &str) -> Option<&'static str>;
pub fn override_tag_for_index(i: usize, entries: &[LocaleEntry]) -> &str;
pub fn crosses_compressed_oops_limit(prev_mb: u64, new_mb: u64) -> bool;  // 31 * 1024
pub fn proxy_host_missing(enabled: bool, host: &str) -> bool;
pub fn localized_page_path(english: &str, lang: &str, country: &str, exists: impl Fn(&str) -> bool) -> String;
```

### 11.3 Threading model
Java runs controller logic on whatever thread, marshals with a blocking `JFXPlatformRun`, and
shows dialogs by blocking a worker on a latch. In GPUI:
- All view state lives on the foreground executor (main thread). Background work uses
  `cx.background_spawn` (CPU / blocking IO) or a Tokio runtime owned by the service crates
  (network); results come back with `this.update(cx, ...)` on a `WeakEntity` (fails silently once
  the view is gone, which replaces most `disposed` checks; keep `disposed` where a task must not even
  start work).
- Background threads outside GPUI (auth renewal, the launch pipeline, MCP, tray, tracker
  listeners) talk to the UI through `GuiHandle`, which wraps an `AsyncApp` clone (not `Send`) behind
  a dedicated `async_channel::Sender<UiCommand>` drained by a foreground task, so it is `Send +
  Sync`.
- Never block the foreground. The Java "worker blocks on a dialog" becomes an `async` flow awaiting
  `window.prompt(...)`.
- Debounces and delays: replace-a-`Task` pattern with `cx.background_executor().timer(d).await`.

---

## 12. External crates

| Need | Crate | Notes |
|---|---|---|
| UI | `gpui` 0.2.2 | windows, layout, text, actions, menus, prompts, file pickers (`prompt_for_paths`, `prompt_for_new_path`), clipboard, `open_url`, window appearance observation, rem scaling |
| Widgets | `gpui-component` 0.9 (optional) | Button, Input (search, URL, password), Dropdown/Select, Switch, Checkbox, Tooltip, PopupMenu/ContextMenu, Dialog/Modal, List, Scrollbar, Notification, `TextView` (markdown/HTML for help), Spinner. Its theme system can be fed from `mmcl-theme`. Version-pin to the GPUI it targets. |
| Embedded Microsoft sign-in page | `wry` (+ `raw-window-handle`) | child web view over a GPUI window region (needs the window's raw handle; verify gpui 0.2.2 implements `HasWindowHandle`), or a separate tao/wry window. WebView2 on Windows, WKWebView on macOS, WebKitGTK on Linux (adds a system dependency on Linux packages). |
| Windows chrome | `windows` (Win32_Graphics_Dwm, Win32_UI_WindowsAndMessaging, Win32_UI_Shell for ITaskbarList3) | Mica, caption/border colour, frame refresh, taskbar progress |
| macOS chrome | `objc2`, `objc2-app-kit` | NSAppearance, NSVisualEffectView, dock menu if GPUI's is insufficient |
| OS dark mode | GPUI `window.appearance()` / `observe_window_appearance`; fallback `dark-light` | replaces jSystemThemeDetector |
| Images | `image`, `fast_image_resize` | decode at target size, palette sampling, transparency probe |
| HTTP for avatars / logos | the shared HTTP client (`reqwest`) set as GPUI's `HttpClient` | generic User-Agent only |
| System RAM | `sysinfo` | replaces OSHI |
| File dialogs with filters | `rfd` | GPUI's path prompts have no extension filters |
| Open folders / URLs | GPUI `open_url`, or `open` / `opener` | |
| Async utilities | `futures`, `async-channel`, `smol` (timers if outside GPUI) | |
| LRU | `lru` | 96 MB decoded background cache (weigh by bytes manually) |
| Desktop notifications | owned by the notifications spec (`notify-rust` / `winrt-notification` / `mac-notification-sys`) | |
| Number/percent formatting per locale | `icu_decimal` (or `num-format`) | `NumberFormat.getInstance()` / `getPercentInstance()` |
| Markdown conversion of help pages (build time) | `html2md` or a small handwritten converter in `build.rs` | if option (a) for help |

No good Rust equivalent:
- **JavaFX WebView + JS bridge**: wry is the closest; there is no in-process web engine that renders
  inside a GPUI element tree. The sign-in page needs a real browser engine (Microsoft's login is a
  heavy JS app), so either wry or a different OAuth flow (system browser + loopback / device code).
- **jarscanner** (Java malware scanner used by the Settings scan): no Rust crate; the security spec
  must reimplement signature checks over jar (zip) entries or call out to a bundled JVM.
- **MaterialFX controls / CSS cascade**: no CSS in GPUI; every style rule becomes Rust code reading
  theme tokens.
- **JavaFX node transforms** (`ScaledRoot`): replaced by rem scaling; anything drawn in absolute
  `px` will not scale.

---

## 13. Tests to port

Pure (port to `mmcl-ui-model` / `mmcl-theme` unit tests, same cases):
- `LibraryViewModelTest`: rejects non-positive page size; page-size change resets to page 1 and fires
  once; sort change resets page and fires only on a difference; filter map distinguishes types and
  falls back on mismatch; prev page bounded at 1 without firing; `clamp_and_slice` corrects
  out-of-range pages; search tokens split on whitespace and lowercase.
- `AccountListModelTest`: default first, rest keep order; default cannot be made default; another
  ready account can; rejected credentials offer sign-in instead; refreshing status; session-only
  marked; unknown name falls back to uuid; no accounts -> no rows.
- `MicrosoftSignInTest`: code returned; code URL-decoded; error wins over code; a server error is not
  a cancellation; neither -> unknown error (""); fragment ignored (redirect
  `https://login.live.com/oauth20_desktop.srf`).
- `FilterOptionLabelsTest`: every option id (all three prefixes, both screens) has a bundle key; a
  null selection renders "".
- `ModpackImageCycleClockTest`: every token maps; `never`, unknown and null -> None; every canonical
  option resolves.
- `MCLauncherSettingsJvmPresetLogicTest`: preset args select their preset; unmatched args -> Custom;
  migrated default selects Performance; Custom never overwrites saved args; unchanged preset writes
  nothing; picking a preset writes its args.
- `MCLauncherSettingsLanguageLogicTest`: tag for every index; OS default / out of range -> ""; no
  change when selection matches; change when it differs; comparison case-insensitive and null-safe.
- `MCLauncherSettingsValidationLogicTest`: enabled proxy needs a host; oops warning only on first
  crossing.
- `WindowPlacementTest`: well inside kept; 1 px overlap rejected; title bar above visible area
  rejected; behind taskbar rejected; disconnected screen rejected; hanging partly off the side kept
  while grabbable; clamp keeps size and moves inside; clamp shrinks oversize.
- `ScaledMinSizeTest`: scales when it fits; caps to display minus 24; unknown or tiny displays degrade
  safely.
- `UiScaleTest`: snaps to nearest offered scale (drop the popup-stylesheet cases or replace them with
  a rem-size test).
- `HelpPageLocalizationTest`: prefers lang_COUNTRY folder; falls back to lang then English; only RTL
  languages flip; every translated page has an English original (walk `help/<lang>/`); inserted text
  HTML-escaped.
- `HelpTopicNamesTest`: every topic name key resolves in the bundle.
- `ThemeTokensTest`: every theme defines the same tokens; text meets WCAG AA (4.5:1) on every role it
  is drawn on; contrast function matches known values. Port as `mmcl-theme` tests over the const
  tables (and over the CSS if tables are generated).
- `SettingsNavTileTest`: lone tile -> `tileOnly`; groups first/middle/last.
- `SettingsOptionLabelsTest`: every theme and JVM preset has a label; unknown ids show as-is.
- `ShapeScaleTest`: radii on the scale; inner stays concentric. Rust: assert every radius constant
  used in `mmcl-ui` comes from `mmcl_theme::shape` (grep test).
- `TypeScaleTest`: only scale sizes; semibold names its face. Rust: font size constants only from the
  type scale.
- `PackBackgroundLayerTest`: wide image cropped equally left/right; tall equally top/bottom; same
  aspect shows whole; decode width covers the box in device pixels rounded up to 64.
- `ProgressVisualsTest`: wave starts flat, stays in bounds, ends at the end; zero amplitude flat;
  horizon columns within height and repeat exactly; every state but pending has a glyph; loading
  shapes stay inside their radius and morph smoothly.
- `PackColorSchemeTest`, `LogTrimPolicyTest`, `GameSessionPane*Test`, `RunningGamesDockResizeTest`
  belong to the detail-modal / running-games specs.
- New Rust tests worth adding: dominant palette on synthetic images (distance threshold 0.25,
  saturation weighting, alpha < 0.5 skipped); logo transparency on synthetic RGBA; Browse
  `collect_entries` status/type matrix with fake sources; Home haystack multi-token AND search and
  last-played pinning; `sanitize_one_line` / `sanitize_multiline` caps (600 / 4000) and newline
  rules; Settings `has_unsaved_changes` over a form snapshot.

TestFX snapshot tests (`ThemeSnapshotFxTest`, `SignInPanelSnapshotFxTest`,
`SecondaryScreensSnapshotFxTest`, `UiScaleSnapshotFxTest`, `ControlsGallerySnapshotFxTest`,
`SettingsLanguageButtonFxTest`, `SettingsGroupsFxTest`, `MaterialSwitchSkinFxTest`,
`AnimationGateFxTest`, `TestFxSmokeTest`): port as opt-in GPUI tests (`#[gpui::test]` with
`TestAppContext` / `VisualTestContext`) behind an env var `MMCL_RUN_UI_TESTS=true`, rendering each
screen in every theme and scale; GPUI's test platform does not rasterize, so true PNG snapshots need
a real-window harness (open question).
`InlineStyleGuardTest` -> a grep test forbidding colour literals outside `mmcl-theme` (allow-list
brand constants: voxel cubes, default gradients, sign-in UA string is not a colour).

---

## 14. Behaviour checklist of guarded bugs (do not regress)
1. Screen swaps are serialized and each cleans up exactly the screen it replaces.
2. A failing `cleanup()` never blocks the next screen.
3. Late async callbacks never touch a disposed screen (Home/Browse `disposed` set first in cleanup).
4. Card rebind cancels a pending single-click (130 ms) so the modal never opens for the wrong pack.
5. Async image/logo/transparency results apply only if the card still shows the same pack/entry.
6. Pooled/reused cards reset tooltip, badges, classes and context menu on every bind.
7. Image-cycle subscriptions are dropped on scene detach, rebind and screen cleanup; never created
   for a disposed screen; the clock stops with no listeners; a panicking listener does not kill it.
8. Theme re-apply on plain navigation must not touch native chrome (flicker).
9. Native theme falls back to an opaque bg where no backdrop exists (Win10, pre-22H2, Linux), else a
   black window.
10. DWM Mica re-request toggles NONE -> MICA on a theme change (DWM caches).
11. OS theme events only act for Automatic / Native and are fire-and-forget.
12. Window bounds persist the un-maximized geometry and validate the title bar on restore.
13. Min size is capped to the display (large scale on small laptops).
14. The OAuth listener is attached once per web view; the code is single-use.
15. `prompt=select_account` so a second account can be chosen.
16. A sign-in dialog closed while redeeming does not reload the page into a hidden web view.
17. Signing out the last account confirms when games run, then restarts into login.
18. Settings "Custom" JVM preset never overwrites generated or hand-edited args.
19. Proxy enabled without host is saved as off with a warning.
20. Theme saved value matched case-insensitively ("Native (Mica)" survived round trips).
21. Runtime delete resolves the selection against one snapshot.
22. Runtime listing, OSHI/RAM queries, Browse entry gathering never run on the UI thread.
23. Launch-progress listener coalesces bursts into one UI flush; rows never change height.
24. Taskbar progress wrapper lives for the app; screens only clear/stop it.
25. Help window: one OS-dark read per pass; reload only on real theme change; navigation locked to
    bundled content; inserted text HTML-escaped; statics cleared on session restart.
26. Quit / restart while games run always asks (window close, Exit, tray/dock Quit, Cmd+Q,
    Save-and-Restart).
27. Global shortcuts ignore Alt chords and never override bare Cmd+M.
28. Refresh (F5) re-probes connectivity before trusting the sticky offline flag.
29. The cold-start placeholder is empty at first paint; decorations attach after one idle frame.
30. Every in-process restart drops all per-session GUI state (no leaked windows, listeners, statics).

---

## 15. Needs from other subsystems (contracts consumed)
- Config (`ConfigManager` slices): every key in 6.2, plus `windowX/Y/Width/Height/Maximized`,
  `lastModPack`, `quickStartCompleted`, `accountOverrideByPack` (`getAccountOverrideForPack(settingsKey)`),
  `runningGamesDocked`, `exportConfig(file) -> bool`, `importConfig(file) -> bool`, CurseForge key
  get/set/has.
- Localization: `get(key)`, `format(key, args...)` (MessageFormat semantics), current locale,
  `SupportedLocales::ENTRIES { tag, display_name }`, `LocaleBootstrap::detect_os_locale()`, locale
  display names in another locale.
- Paths: `LocalPathManager` launcher root, config folder, `<config>/loader-logos/`.
- Auth: `MCLauncherAuthManager::{logged_in_user() -> Option<User{name, uuid}>, accounts() ->
  AccountManager { accounts(), account(uuid), set_default(uuid), remove(uuid), add_listener },
  login_with_microsoft_account(code, remember), add_account_with_microsoft(code, remember),
  pending_refresh_future()}`, `AuthResult` incl. `ERROR_NOT_OWNED`, `check_auth_response`,
  `MicrosoftService::oauth_login_url()`, redirect constant.
- Modpacks: `GameModPackManager::{installed, available_if_ready, available, available_fetch_future,
  installed_revalidate_future, fetch_installed(None), fetch_available(None), install_by_url,
  install_by_friendly_name, uninstall(pack, delete_files) -> bool, installed_urls}`; `GameModPack`
  accessors used in 3.4 (names, versions, loader, URLs, logo/background file paths and counts,
  `cache_images`, play stats, `is_never_played`, `last_played_formatted`, `unread_news_count`,
  `pack_unstable`, `is_update_available`, `root_folder`, `settings_key`, `create_vanilla(id)`);
  `ModPackUpdateLog::read_entries` (newest first); `VanillaVersionManager`; `LoaderVersionManager`;
  `ModpackImportClassifier`, `ModrinthClient`, `MrpackImporter`, `ModpackZipImporter`,
  `PrismInstanceImporter`; `ModpackExporter`; `OfficialLauncherExporter`; `LoaderInstallerRunner`;
  `VerifyAction::run_for_packs(packs, then)`; `GameVersionManifest::get_client_json(mc)`;
  `ScanFrequency`; `AVAILABLE_PACKS_MANIFEST_LIST_KEY`.
- Sessions: `GameSessionRegistry::{active(), has_active(), is_pack_active(pack), active_for_pack(key),
  add_listener/remove_listener, dismiss}`, `GameSession::{key_for, cancel, stop(force)}`,
  `RunningGamesWindow::{show_session, show_window, hide_unless_preparing, follow_screen, shutdown,
  refresh_theme}`; `LaunchProgressTracker` (steps with id, state, label, sub_text, progress,
  error_message; listeners).
- Runtimes: `RuntimeManager::{installed_runtimes() -> Vec<{component, version?, size_mb}>,
  clear_runtime(c), is_in_use(c)}`.
- Core: `LauncherCore::{play(pack), close_app, restart_app, relaunch_app,
  reset_launcher_and_relaunch}`, `ColdStartProfiler`.
- Network: `NetworkUtilities::{is_offline, check_network_availability, reload_proxy}`,
  `UrlValidation::sanitized_http_url`, `SensitiveDataRedactor::redact`.
- Announcements: `AnnouncementManager::{home, login, config, modpacks_edit, check_future,
  check_announcements, dismiss_for_session(text), is_dismissed(text), disable_modpacks_edit,
  disable_gameplay}`.
- Platform: notifications (`success/info/warn/error(title, body)`), Discord RPC (`set_menu_presence`,
  `exit`, `build_invite_link_from_pack`), taskbar/dock progress (`attach`, `set_progress`, `stop`,
  `set_launch_progress`, `end_launch_progress`), window chrome (Windows DWM / custom title bar,
  macOS vibrancy / toolbar / title bar / dock menu), `DesktopShortcutManager::{create_shortcut,
  resolve_launcher_path}`, `LauncherActions::build_dock_menu`, `UpdateCheckManager`,
  `HardwareTunedJvmArgs::{summary, generate}`.
- MCP: `McpBootstrap::{refresh, is_running, port, sessions, grants, activity, policies,
  describe_tools, ensure_token, regenerate_token}`, `McpClientSetup`, `McpEndpointFile`,
  `McpAccessToken::mask`.
- RGB: `RgbController::{instance, start, stop, status, active_effect, set_effect}`,
  `RgbBackendRegistry::resolve_backends_from_config`, `RgbIntegration::on_menu`, `SolidEffect`,
  `RgbColor`.
- Security: jarscanner equivalent (`run(threads, path, emit_walk_errors, excludes, log_fn,
  progress_fn) -> Results { stage1, stage2 }`, `cancel_if_running`), `SupplementalScanner`.
- Quick-start wizard, detail modal, editor, import-confirm dialog, Running Games window: their specs.

## 16. Provided to other subsystems
- `GuiHandle` (navigation, gates, quit/restart confirmations, focus, dialogs).
- `ProgressHandle`, `LaunchProgressHandle`, `LoginHandle`.
- `show_help(topic)`, `HelpTopic`.
- `show_add_account(owner)`, `AccountSwitcher`.
- Theme: `ActiveTheme` global, `Tokens`, `is_light_chrome`, `solid_bg`, shape/type/motion tokens,
  `install_theme_on(window)` for secondary windows.
- Shared widgets: wavy progress, loading indicator, step badge, chips, switch, search field, empty
  state, banner, icon button, tooltip, pack card visuals (`PackBackground`, dynamic gradient,
  palette sampling, image cycle clock, avatar cache, placeholder logos).
- `confirm_and_uninstall_modpack`, `build_pack_context_menu`.
- `UiScale` global and rem helpers.

## 17. Open questions
1. Microsoft sign-in in Rust: embed the existing `oauth20_desktop.srf` flow in a wry web view
   (closest parity, adds WebKitGTK as a Linux package dependency and needs a raw window handle from
   GPUI), or switch to the system browser with auth-code + PKCE on a loopback redirect, or the
   device-code flow (both need the Azure app registration to allow it, and change the UX)?
2. Help window: native rendering of converted Markdown (gpui-component `TextView`) or a wry web view
   reusing the HTML and help CSS?
3. Does gpui 0.2.2 expose: a Mica window background on Windows, runtime `set_window_min_size`,
   `HasWindowHandle` on `Window`, `visible_bounds` per display, and a dock menu API on macOS? Each
   "no" needs a platform shim (DWM / objc2) or an upstream patch.
4. Interface scale via rem size covers GPUI-drawn content but not OS-native prompts; acceptable, or
   should every dialog be an in-window gpui-component modal (also gives themed dialogs everywhere,
   which Java achieves by styling JavaFX alerts)?
5. Live language switching: if the Rust localization layer looks strings up at render time, the
   Save-and-Restart flow could apply a language instantly. Keep the restart for parity or switch live?
6. The Settings malware scan depends on jarscanner (Java). Port a Rust scanner, ship a JVM-backed
   helper, or drop the manual scan from Settings until the security subsystem has one?
7. Main window and secondary windows disagree on the solid bg for Blue+gray (`#121721` vs
   `#0E141D`) and Native (`#14181F` vs `#1C1B1F`); adopt the token sheet's `-color-bg` for opaque
   themes everywhere?
8. Several user-visible strings are hard-coded English in Java (vanilla type chips "Release",
   "Snapshot", "Old Beta", "Old Alpha"; RGB effect names; RGB backend brand labels;
   `library.empty.sub.search` receives raw filter ids). Add localization keys in the Rust port?
9. Snapshot tests: GPUI's test platform does not rasterize. Is a real-window screenshot harness
   (opt-in, per OS) wanted for theme/scale visual review, as the Java TestFX snapshots provide?
10. Card pooling: drop it in favour of per-pack entities (recommended) or keep a strict pool for very
    large libraries?
