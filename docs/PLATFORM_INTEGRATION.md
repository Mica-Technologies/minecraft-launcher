# Platform / OS Integration

Technical documentation for the launcher's native operating-system integrations — title-bar
chrome, taskbar/dock surfaces, system menus, notifications, URL schemes, and theme detection
across macOS, Windows, and Linux.

## Overview

The launcher is a single JavaFX application, but it reaches into each host OS's native shell so
it feels like a first-class citizen rather than a ported Java app. The guiding principles:

- **Native where it matters, shared where it doesn't.** Cross-platform behavior (the shell action
  menu, notifications, the "recent modpacks" concept) lives in shared classes; each OS then has
  thin adapters that render those concepts the native way.
- **Elegant fallback everywhere.** Every native entry point is gated on the platform and wrapped
  so that an unsupported feature, a missing native library, or a runtime failure degrades to a
  no-op (or to the in-window equivalent) — never a crash and never a broken screen. A feature
  that can't light up simply doesn't, and the JavaFX UI carries on.
- **Runtime, not installer.** OS registrations (URL scheme, file associations, recent lists) are
  (re)written on every startup so portable-JAR and jpackage installs behave identically.

Integration code lives under
`src/main/java/com/micatechnologies/minecraft/launcher/utilities/` (most adapters),
`.../gui/SystemMenuBarManager.java` (macOS menu bar), and
`.../system/DesktopShortcutManager.java` (per-pack desktop shortcuts).

### Native bridge libraries

| Library | Platform | Used for |
|---|---|---|
| **JFA** (`de.jangassen.jfa`) | macOS | Pure-Java Obj-C bridge (`Foundation.invoke`, runtime class creation) — drives `NSWindow`, `NSToolbar`, `NSImage`, the dock, etc. |
| **JNA** (`com.sun.jna`) | macOS / Windows | Underpins JFA; on Windows loads `dwmapi` directly and backs the native callbacks |
| **FXThemes** (`com.pixelduke`) | macOS / Win | `NSVisualEffectView` vibrancy + DWM helpers; also the source of the live `NSWindow` pointer |
| **jSystemThemeDetector** (`com.jthemedetecor`) | all | OS dark/light detection + change listener |
| **FXTaskbarProgressBar** (`com.nativejavafx.taskbar`) | Windows | `ITaskbarList3` taskbar progress overlay |
| `java.awt.Taskbar` / `SystemTray` / `Desktop` | all | Dock badge/menu (macOS), tray + Notification Center / Action Center toasts, app-menu + open-URI/file handlers |

## Cross-platform layer

These classes own a concept once; the per-OS adapters render it.

- **`LauncherActions`** — the shared shell action surface. `buildSharedMenu()` (Win/Linux system
  tray) and `buildDockMenu()` (macOS dock) build AWT `PopupMenu`s from the same action handlers
  (Show, Play Last / Play Recent, Open Mods Folder, Browse, Settings, Quit). Each action is
  thread-safe — UI work hops to the FX thread, long work spawns a worker.
- **`NotificationManager`** — cross-platform native notifications via `java.awt.SystemTray` +
  `TrayIcon.displayMessage`: Action Center toasts on Windows, Notification Center on macOS, the
  legacy tray protocol on Linux DEs that expose it. Unsupported platforms transparently fall back
  to a log line. All user-visible strings route through `LocalizationManager`.
- **`JumpListManager`** — façade over the OS "recent modpacks" surface: Windows jump list, Linux
  `.desktop` `Actions=`, macOS dock menu. Called at startup and after every successful launch.
- **`OsThemeUtilities`** — single NULL-safe dark/light probe (see [Theme detection](#theme-detection)).
- **`SchemeRegistrar`** — runtime registration of the `mmcl://` URL scheme and `.mmcjson` file
  association (and, on Linux, the recent-packs `.desktop` `Actions=`).
- **`DesktopShortcutManager`** — per-pack desktop shortcuts: `.lnk` (Windows), `.app` bundle
  (macOS), `.desktop` (Linux), with PNG→ICO/ICNS icon conversion.

## macOS

macOS gets the deepest integration. Most of it is driven through JFA's `Foundation.invoke` against
the live `NSWindow` pointer, which FXThemes' `WindowUtils.getNativeHandleOfStageAsNativeLong(stage)`
reflects out of Glass (returns 0 before `stage.show()`, so calls defer to a one-shot `WINDOW_SHOWN`
handler).

### Hidden-inset title bar — `MacOsTitleBarManager`

The Electron-`hiddenInset` look: the native title bar goes transparent and the JavaFX content
fills the full window height, with the traffic lights floating over the content.

- `applyHiddenInset(stage)` — sets `titlebarAppearsTransparent = YES` and OR's
  `NSWindowStyleMaskFullSizeContentView` into the window's style mask (read-modify-write so
  `StageStyle.UNIFIED`'s bits survive).
- `hideRedundantBranding(root)` — collapses the in-window `.navBrandLogo` / `.navBrand` nodes; the
  OS title bar already shows the screen name.
- `installWindowDrag(root, stage)` — because the full-size content view swallows the native
  title-bar drag, the top navbar becomes a drag region: a primary press on empty navbar space
  hands off to AppKit's `[NSWindow performWindowDragWithEvent:]` (native tracking loop — follows
  the cursor 1:1, no lag, with window snapping). Presses on buttons / text fields / clickable
  glyphs are excluded; double-click zooms.

### Native title-bar toolbar — `MacOsToolbarManager`

Attaching an `NSToolbar` does two things: it grows the title bar to the unified height (which
re-centers the traffic lights and title vertically) and lets the launcher host its primary nav as
real, clickable native items next to the traffic lights.

- A small Obj-C **toolbar delegate class is built at runtime** via JFA
  (`objc_allocateClassPair` + `class_addMethod`, the same technique `jSystemThemeDetector` uses for
  its notification observer). It vends `NSToolbarItem`s for **Browse, Settings, Help, and the
  account** (right-aligned via a flexible space), actions routed to `LauncherActions`.
- **Theming:** item icons are SF Symbols tinted to the current theme accent
  (`ThemeAccentColors.accentForCurrentTheme()` → `NSColor` → `NSImageSymbolConfiguration`), falling
  back to the plain appearance-adaptive symbol. Small `sizeMode` gives the icon+label pairs
  breathing room off the content seam.
- **Account item:** shows the player name + a `person.crop.circle` glyph immediately, then an
  async off-main load swaps in the real minotar player head (`NSImage` retained across the
  thread hop, released after `setImage:`).
- **Why native items, not the JavaFX navbar:** a toolbar's views consume clicks over the full-size
  content band, so JavaFX buttons up there become unclickable. The fix is to put the controls in
  the toolbar and **hide the now-redundant JavaFX navbar controls** (`hideReplacedControls`,
  including the account display whose only action opened Settings).
- **Fallback:** `install()` reports success and the JavaFX controls are hidden **only when
  `isInstalled()` is true**. If any native step fails, nothing is hidden, no toolbar is attached
  → the title bar stays short and the in-window navbar stays fully clickable.

> ⚠️ The runtime delegate uses JNA callbacks as Obj-C method implementations. A wrong callback
> signature / type-encoding can crash the JVM when AppKit invokes the delegate (at display time),
> which the setup-time guards can't catch. The async avatar load is the one spot with manual
> native memory management (retain/release across a thread hop) — first suspect for any
> avatar-load crash.

### System menu bar — `SystemMenuBarManager`

A `MenuBar` with `useSystemMenuBar = true` renders in the macOS screen-top bar (no-op elsewhere).
A single instance lives for the session and is reparented into each scene on navigation.

- **App menu** (via `java.awt.Desktop` handlers): About, Preferences (⌘,), Quit. The
  `OPEN_URI` / `OPEN_FILE` handlers also receive cold-start `mmcl://` links and `.mmcjson`
  double-clicks.
- **Menus:** Modpacks (Home, Open Browse ⌘L, Refresh ⌘R, **Play Recent** submenu), Game (Runtime,
  Modpack Editor), View (Theme radio submenu + Discord RPC / In-Game Console / Show Pack
  Backgrounds toggles), Window (Minimize ⌘M, Zoom), Help (⌘?).
- **Dynamic refresh:** the native bar doesn't fire JavaFX's `Menu.onShowing` reliably, so recent
  packs + toggle/theme state are re-synced from `attachTo` (which runs on every navigation).

### Dock — `MacOsDockManager`

Wraps `java.awt.Taskbar` for dock parity with the Windows taskbar work:

- **Progress arc** reflecting install progress (driven through `TaskbarProgressManager`).
- **Icon badge** (`setIconBadge`) — a "1" cue when an update is available.
- **Attention bounce** (`requestUserAttention`) for significant async events.
- **Right-click dock menu** (`LauncherActions.buildDockMenu()`), rebuilt after each launch via
  `JumpListManager` so its Play Recent list stays current.

### Vibrancy — `MacOsVibrancyManager`

Installs an `NSVisualEffectView` frosted-glass backdrop (the macOS analogue of Win11 Mica) via
FXThemes, used by the Native theme. Also sets the `NSWindow`'s `NSAppearance` directly so the
title-bar chrome follows the chosen dark/light theme.

### Theme detection

`OsThemeUtilities.isOsDark()` is the single dark/light probe. On macOS it reads
`AppleInterfaceStyle` directly through JFA — sidestepping a `jSystemThemeDetector` 3.8 bug that
NPEs in Light mode (the key is absent, so the library feeds `null` to a regex matcher and logs an
ERROR + stack trace on every theme application). Windows/Linux delegate to the library, which works
there. The live change listener (for the Automatic / Native themes) is still registered through the
library.

## Windows

- **`WindowChromeManager`** — DWM chrome via `dwmapi.DwmSetWindowAttribute`: title-bar dark mode,
  caption / border color matched to the theme, and the Mica / Acrylic system backdrop
  (`DWMWA_SYSTEMBACKDROP_TYPE`, Win11 22H2+). `StageStyle.UNIFIED` is used so DWM can composite the
  backdrop through the JavaFX scene. No-op on non-Windows.
- **`TaskbarProgressManager`** — owns a single `FXTaskbarProgressBar` (`ITaskbarList3`) wrapper for
  the session and pushes progress / error overlays to the taskbar button. Also forwards to
  `MacOsDockManager` so one call drives the right surface on every OS.
- **`WindowsJumpList`** — populates a "Recent Modpacks" category in the taskbar / Start-menu jump
  list via `ICustomDestinationList` COM; each entry shells the launcher with a
  `mmcl://play?name=<pack>` URL that the single-instance lock forwards.
- **`WindowsShellRefresh`** — `SetWindowPos(SWP_FRAMECHANGED)` to nudge the shell into
  re-evaluating per-monitor taskbar membership after a chrome change.
- **Tray + toasts** — `NotificationManager` shows Action Center toasts grouped under the launcher's
  AppUserModelID; the tray icon's right-click menu is `LauncherActions.buildSharedMenu()`.
- **URL scheme / file association** — `SchemeRegistrar` registers `mmcl://` and `.mmcjson` in the
  registry at startup.

## Linux

- **Recent modpacks** — `JumpListManager` rewrites the installed `.desktop` file's `Actions=` block
  (owned by `SchemeRegistrar`, the single writer for that file) so the app-menu / dock right-click
  surfaces the same recents.
- **Notifications / tray** — `NotificationManager` works on KDE Plasma and any DE exposing the
  legacy `SystemTray` D-Bus protocol; modern GNOME/Wayland reports unsupported and falls back to a
  log line.
- **URL scheme / file association / shortcuts** — `SchemeRegistrar` + `DesktopShortcutManager` via
  `.desktop` files.
- No system-wide vibrancy equivalent — the Native theme routes to the opaque Dark/Light palette.

## Fallback philosophy (important)

Every adapter here is best-effort. The expected pattern, used consistently:

```java
if ( !SystemUtils.IS_OS_MAC || stage == null ) return;   // platform / arg gate
try {
    // ... native calls ...
}
catch ( Throwable t ) {                                  // never escapes
    Logger.logWarningSilent( "…: " + t.getMessage() );
}
```

For features that *replace* in-window UI (notably the native toolbar), the replacement is applied
**only after** the native install reports success, so a failure leaves the original JavaFX UI
intact and usable. When adding new native integration, follow the same shape: gate on the
platform, wrap in `Throwable` handling, log silently, and make any UI hand-off conditional on the
native side actually succeeding.

## File reference

| Concern | Class |
|---|---|
| Shared shell menu / actions | `utilities/LauncherActions` |
| Cross-platform notifications | `utilities/NotificationManager` |
| Recent-modpacks façade | `utilities/JumpListManager` |
| Dark/light probe | `utilities/OsThemeUtilities` |
| URL scheme / file association | `utilities/SchemeRegistrar` |
| Per-pack desktop shortcuts | `system/DesktopShortcutManager` |
| macOS hidden-inset title bar + drag | `utilities/MacOsTitleBarManager` |
| macOS native toolbar | `utilities/MacOsToolbarManager` |
| macOS dock badge / progress / menu | `utilities/MacOsDockManager` |
| macOS vibrancy backdrop | `utilities/MacOsVibrancyManager` |
| macOS system menu bar | `gui/SystemMenuBarManager` |
| Windows DWM chrome / Mica | `utilities/WindowChromeManager` |
| Windows taskbar progress | `utilities/TaskbarProgressManager` |
| Windows jump list | `utilities/WindowsJumpList` |
| Windows shell refresh | `utilities/WindowsShellRefresh` |
