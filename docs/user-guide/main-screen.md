# Main Screen

The main screen is your home base after signing in. From here you can browse installed modpacks, launch the game, and navigate to settings or modpack management.

## Modpack List

The left panel displays all installed modpacks. Each entry shows:

- **Modpack name** -- the display name defined in the modpack's JSON manifest.
- **Last played** -- the date and time you last launched this modpack, displayed beneath the name.

Select a modpack by clicking on it to view its details in the center panel.

### Update Badges

When a modpack has a newer version available on the remote server, a green **UPDATE** badge appears next to its name. You do not need to do anything special -- the update is applied automatically the next time you click Play. The launcher will download any new or changed files, update Forge if needed, and launch the updated version.

### Right-Click Context Menu

Right-click any modpack in the list to access additional options:

| Option | Description |
|---|---|
| **Open Modpack Folder** | Opens the modpack's local directory in your system file manager. Useful for inspecting files, adding custom resource packs, or viewing logs. |
| **Open Mods Folder** | Jumps directly to the `mods/` subdirectory within the modpack folder. |
| **Create Desktop Shortcut** | Creates a shortcut on your desktop that launches this specific modpack directly, bypassing the launcher's main screen. |
| **Play Statistics** | Displays total play time and a history of individual sessions for this modpack. |

## Center Panel

Selecting a modpack displays its details in the center area:

- **Description** -- the modpack's description text as defined by the modpack author.
- **Background image** -- a background image specific to the modpack (if one is defined in the manifest).

Two primary buttons appear at the bottom of the center panel:

- **Play** -- downloads any missing or updated files, verifies integrity, installs the correct Forge and Java runtime, runs the malware scan, and then launches the game.
- **Website** -- opens the modpack's homepage in your default browser (if the modpack author has defined a website URL in the manifest).

## Navigation

The main screen provides access to the rest of the launcher:

- **Settings** -- opens the [Settings](settings.md) screen where you can configure memory allocation, themes, JVM flags, proxy settings, and more.
- **Edit Mod Packs** -- opens the [Edit Mod Packs](edit-modpacks.md) screen to add new modpacks or remove existing ones.

> **Note:** If you have no modpacks installed, the center panel will display a prompt directing you to [Edit Mod Packs](edit-modpacks.md) to add your first one.

## Workflow Summary

A typical session on the main screen:

1. The launcher shows your installed modpacks on the left. Any with available updates show a green badge.
2. Click a modpack to select it and view its description.
3. Click **Play** to launch. The launcher handles all downloads, verification, and setup automatically.
4. While the game is running, the launcher minimizes (or shows the [Game Console](game-console.md) if enabled).
5. When you close the game, the launcher returns to the main screen.
