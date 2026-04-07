# GUI System

Technical documentation for the JavaFX GUI architecture, screen navigation, and theming.

## Overview

The launcher GUI is built with JavaFX and uses FXML layouts with CSS-based theming. A
singleton controller manages screen transitions, and each screen has a dedicated controller
class paired with an FXML file. Four color themes are available.

GUI code lives in:
- `src/main/java/com/micatechnologies/minecraft/launcher/gui/` -- controllers
- `src/main/resources/gui/` -- FXML layouts
- `src/main/resources/` -- CSS theme files

## Architecture

```
  ┌──────────────────────────────────────────────────────┐
  │              MCLauncherGuiController                  │
  │              (singleton, static methods)              │
  │                                                      │
  │  MCLauncherGuiWindow guiWindow ← single Stage        │
  │                                                      │
  │  goToMainGui()  ──────>  MCLauncherMainGui           │
  │  goToLoginGui() ──────>  MCLauncherLoginGui          │
  │  goToSettingsGui() ───>  MCLauncherSettingsGui       │
  │  goToProgressGui() ───>  MCLauncherProgressGui       │
  │  goToEditModpacksGui()>  MCLauncherEditModPacksGui   │
  │  goToRuntimeGui() ────>  MCLauncherRuntimeGui        │
  │  goToVanillaVersionsGui()> MCLauncherVanillaVersionsGui │
  │  goToGameConsoleGui() ─>  MCLauncherGameConsoleGui   │
  └──────────────────────────────────────────────────────┘
```

## Screen Navigation

`MCLauncherGuiController` is the central navigation hub. It holds a single static
`MCLauncherGuiWindow` (JavaFX `Stage`) and provides `goTo*()` methods that:

1. Create a new GUI controller instance for the target screen
2. Load the corresponding FXML layout
3. Set the scene on the shared window via `guiWindow.setScene()`
4. Show the window

Thread-safe initialization uses `AtomicBoolean startSuccess` to ensure the window is
created only once.

### Navigation Methods

```java
public static MCLauncherMainGui goToMainGui() throws IOException
public static MCLauncherLoginGui goToLoginGui() throws IOException
public static MCLauncherSettingsGui goToSettingsGui() throws IOException
public static MCLauncherProgressGui goToProgressGui() throws IOException
public static MCLauncherEditModPacksGui goToEditModpacksGui() throws IOException
public static MCLauncherRuntimeGui goToRuntimeGui() throws IOException
public static MCLauncherVanillaVersionsGui goToVanillaVersionsGui() throws IOException
public static MCLauncherGameConsoleGui goToGameConsoleGui() throws IOException
```

### Utility Methods

```java
public static Stage getTopStageOrNull()       // Access the Stage for dialogs
public static void requestFocus()             // Bring window to front
public static void forceThemeRefresh()        // Reload CSS on theme change
public static void exit()                     // Close window and cleanup
public static boolean shouldCreateGui()       // False in SERVER mode
```

## Screens

### Main Screen (`MCLauncherMainGui` / `mainGUI.fxml`)

The primary screen after login. Features:
- **Modpack list** -- `ListView<GameModPack>` with custom `ModPackCellFactory` (52px cells)
- **Play button** -- launches selected modpack, handles Discord RPC
- **Navigation buttons** -- settings, edit modpacks, vanilla versions, website, logout, exit
- **Player info** -- username label, avatar image
- **Announcements** -- banner for development mode or update notifications
- **Keyboard shortcuts** -- ENTER fires play, F5 refreshes modpack info
- **Context menu** -- right-click modpack to open game directories (mods, config, screenshots)

### Login Screen (`MCLauncherLoginGui` / `loginGUI.fxml`)

Microsoft OAuth webview for fresh authentication. Displays the Microsoft login page and
captures the auth code on successful login.

### Progress Screen (`MCLauncherProgressGui` / `progressGUI.fxml`)

Multi-purpose progress display used during:
- Startup token renewal
- Modpack loading
- Game launch (classpath building, downloads)

Shows upper label, section text, and percentage progress bar.

### Settings Screen (`MCLauncherSettingsGui` / `settingsGUI.fxml`)

User preferences with change detection (`hasUnsavedChanges()`):

| Setting | Control | Description |
|---|---|---|
| Min/Max RAM | Spinners | Memory allocation with system RAM constraints |
| Debug logging | Toggle | Enable verbose logging |
| Resizable windows | Toggle | Allow window resize |
| Discord RPC | Toggle | Discord Rich Presence integration |
| Enhanced logging | Toggle | Extended log output |
| In-game console | Toggle | Real-time game output monitoring |
| Theme | ComboBox | Select color theme |

Danger zone buttons: reset launcher, manage runtimes, open launcher folder, malware scan.

### Edit Modpacks Screen (`MCLauncherEditModPacksGui` / `editGUI.fxml`)

Add/remove modpack URLs from the launcher's modpack list. Can be disabled via
`AnnouncementManager` flag.

### Runtime Management Screen (`MCLauncherRuntimeGui` / `runtimeManagementGUI.fxml`)

Lists installed Java runtimes with version and disk size. Supports deleting individual
runtimes or all runtimes at once, with refresh capability.

### Vanilla Versions Screen (`MCLauncherVanillaVersionsGui` / `vanillaVersionsGUI.fxml`)

Browse and launch vanilla (non-Forge) Minecraft versions. Creates `GameModPack` instances
via `GameModPack.createVanillaModPack(versionId)`.

### Game Console Screen (`MCLauncherGameConsoleGui` / `gameConsoleGUI.fxml`)

Real-time game output monitoring with:

| Feature | Detail |
|---|---|
| Line buffer | `ConcurrentLinkedQueue<String>` for thread-safe buffering |
| UI limit | 10,000 lines displayed (full log preserved in memory) |
| Flush interval | 150ms batched UI updates to reduce JavaFX rendering load |
| Stream reading | Separate threads for stdout and stderr |
| Uptime counter | Updates every 1 second |
| Crash detection | Non-zero exit code triggers crash report display |
| Log persistence | Writes to `game-{name}-{timestamp}.log` |
| Kill button | Force-terminates game process |

## Theming

Four CSS themes are available, each as a standalone stylesheet:

| Theme | File | Description |
|---|---|---|
| Dark | `guiStyle-dark.css` | Dark background with light text |
| Light | `guiStyle-light.css` | Light background with dark text |
| Blue Gray | `guiStyle-bluegray.css` | Blue-gray color palette |
| Orange Purple | `guiStyle-orangepurple.css` | Orange and purple accent colors |

Themes are applied via `MCLauncherGuiController.forceThemeRefresh()` which reloads the
CSS on the active scene. Theme selection is persisted in `ConfigManager`.

All themes share consistent class selectors for buttons, labels, list views, scroll bars,
combo boxes, toggles, spinners, and custom components.

## FXML / Controller Pairing

| Controller | FXML |
|---|---|
| `MCLauncherMainGui` | `gui/mainGUI.fxml` |
| `MCLauncherLoginGui` | `gui/loginGUI.fxml` |
| `MCLauncherProgressGui` | `gui/progressGUI.fxml` |
| `MCLauncherSettingsGui` | `gui/settingsGUI.fxml` |
| `MCLauncherEditModPacksGui` | `gui/editGUI.fxml` |
| `MCLauncherRuntimeGui` | `gui/runtimeManagementGUI.fxml` |
| `MCLauncherVanillaVersionsGui` | `gui/vanillaVersionsGUI.fxml` |
| `MCLauncherGameConsoleGui` | `gui/gameConsoleGUI.fxml` |

## Application Lifecycle

`LauncherCore.main()` drives the GUI lifecycle:

1. Parse CLI args -- if SERVER mode, skip GUI entirely
2. `performClientLogin()` -- show progress or login screen
3. Show progress screen for announcement and modpack loading
4. `goToMainGui()` -- main screen displayed
5. User interactions trigger navigation to other screens
6. `LauncherCore.closeApp()` or `restartApp()` handles shutdown

The main method runs in a `while(restartFlag)` loop, allowing the application to restart
without JVM restart (e.g., after resetting the launcher or changing settings that require
reinitialization).
