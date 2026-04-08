# Game Console

The game console displays real-time output from the Minecraft process, including log messages, warnings, errors, and crash reports. It is an invaluable tool for diagnosing problems and monitoring the game's behavior.

## Enabling the Console

The console window appears alongside the game when **In-Game Console** is enabled in [Settings](settings.md) under Advanced Options. When disabled, the game runs without a visible console window.

To enable or disable the console:

1. Open [Settings](settings.md) from the main screen.
2. Find **In-Game Console** in the Advanced Options section.
3. Toggle it on or off.
4. The change takes effect on the next game launch.

> **Tip:** It is recommended to keep the console enabled, at least initially. It provides immediate visibility into what the game is doing and is essential for diagnosing crashes or mod conflicts.

## Console Features

### Real-Time Output

Game log lines appear in the console as they are produced by the Minecraft process. This includes:

- **Mod loading messages** -- shows which mods are being loaded and their versions.
- **Warnings** -- non-fatal issues that may indicate problems (e.g., deprecated mod features, missing optional dependencies).
- **Errors** -- problems that may affect gameplay or cause crashes.
- **General log output** -- server connections, world loading, resource pack loading, and other game events.

### Kill Game

The **Kill Game** button forcefully terminates the Minecraft process. Use this as a **last resort** when the game becomes completely unresponsive (frozen, not responding to Alt+F4 or the window close button).

Note that killing the game does not allow it to save -- any unsaved progress in the current session will be lost. Always try closing the game normally first.

### Copy

The **Copy** button copies the entire contents of the console to your system clipboard. This is useful for:

- Sharing log output with modpack authors or support channels.
- Pasting into a text editor for searching or analysis.
- Including in bug reports.

## Crash Reports

When the game crashes, the console automatically detects the crash and highlights the relevant crash report. The console makes it easy to:

- **View the full crash report** directly in the console window without navigating to files on disk.
- **Copy the crash report** using the Copy button for sharing with modpack authors or posting in support channels.

### Understanding Crash Reports

A Minecraft crash report typically contains:

- **Description** -- a one-line summary of the crash cause.
- **Stack trace** -- the technical call stack showing where the crash occurred. Look for mod names in the stack trace to identify which mod caused the problem.
- **System details** -- your operating system, Java version, allocated memory, and loaded mods.

When sharing a crash report, always include the complete text -- partial reports make diagnosis much harder.

## Log File Location

The console display is **truncated** to keep memory usage reasonable during long play sessions. Only the most recent output is retained in the console window.

For the **complete, untruncated log**, check the log files on disk:

- Game logs are written to the modpack's `logs/` directory.
- The most recent log is typically named `latest.log`.
- Older logs may be compressed as `.log.gz` files.

To quickly access the log directory:

1. Right-click the modpack on the [Main Screen](main-screen.md).
2. Select **Open Modpack Folder**.
3. Navigate to the `logs/` subdirectory.

## Tips for Effective Debugging

- **Enable Enhanced Logging** in [Settings](settings.md) for even more detailed output.
- **Enable Debug Mode** in [Settings](settings.md) to add verbose launcher-level logging on top of the game's own output.
- When reporting a crash, include **both** the crash report and the `latest.log` file -- the log often contains context leading up to the crash that the crash report alone does not capture.
- If the game crashes immediately on startup, the issue is often a mod incompatibility. Check which mods appear in the stack trace.
- If the game crashes during gameplay, it may be a memory issue. Try increasing maximum RAM in [Settings](settings.md).
