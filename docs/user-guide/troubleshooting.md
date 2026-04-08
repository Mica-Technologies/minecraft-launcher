# Troubleshooting

This page covers common issues and their solutions. If your problem is not listed here, enable Enhanced Logging and Debug Mode in [Settings](settings.md), reproduce the issue, and check the launcher logs for error details.

---

## Download Failures

**Symptoms:** Files fail to download, verification errors appear during launch, or the launcher reports hash mismatches.

**Solutions:**

- **Check your internet connection** and try again. Transient network errors often resolve on a second attempt.
- **Configure a proxy** if you are on a corporate or school network. Go to [Settings](settings.md) > Network / Proxy and enter the proxy details provided by your network administrator.
- **Retry the launch** -- the launcher will attempt to download only the files that failed, not the entire modpack.
- **Check if the download source is down** -- if a specific file consistently fails, the download server may be temporarily unavailable. Wait and try again later.
- **Report persistent failures** -- if a specific file always fails across multiple attempts and days, the download URL may be broken. Report this to the modpack author so they can update the manifest.

---

## Game Crashes

**Symptoms:** The game starts but crashes shortly after (during mod loading, world loading, or during gameplay).

**Solutions:**

- **Enable the Game Console** in [Settings](settings.md) > Advanced Options > In-Game Console. This shows real-time output so you can see exactly what happens before the crash. See [Game Console](game-console.md).
- **Check crash reports** in the modpack's `crash-reports/` folder. Right-click the modpack on the [Main Screen](main-screen.md), select **Open Modpack Folder**, and look in the `crash-reports/` directory.
- **Increase maximum RAM** in [Settings](settings.md). Many modpacks need at least 4 - 6 GB. `OutOfMemoryError` in the crash report or logs is a clear sign of insufficient memory.
- **Force a fresh mod download** -- right-click the modpack on the main screen, select **Open Modpack Folder**, delete the `mods/` folder, and relaunch. The launcher will re-download all mods.
- **Try the Performance JVM preset** in [Settings](settings.md) > JVM Presets. Aikar's flags reduce garbage collection lag spikes that can cause crashes in memory-intensive modpacks.

> **Warning:** Allocating too much RAM (more than your system can spare) can cause crashes just as easily as allocating too little. Keep maximum RAM at or below half your total system memory. For example, on a 16 GB system, do not exceed 8 GB.

### Crash During Mod Loading

If the game crashes during the Forge mod loading phase (before you see the main menu):

- A mod may be incompatible with the Forge version or Minecraft version. Check the crash report for the specific mod named in the error.
- Two mods may conflict with each other. The crash report usually names both.
- A required dependency mod may be missing. Look for messages like "Missing required mod: ..." in the log.

### Crash During Gameplay

If the game crashes during gameplay (after reaching the main menu or while in a world):

- Memory exhaustion is the most common cause. Increase maximum RAM.
- A specific game action may trigger a mod bug. Note what you were doing when the crash occurred and include that in any bug report.

---

## Launcher Stuck on Loading

**Symptoms:** The launcher appears frozen, a progress bar stops moving, or the UI becomes unresponsive.

**Solutions:**

- **Wait a few minutes** -- large modpacks can take significant time to verify files, especially on first launch or after an update. The launcher may be downloading, extracting, or verifying hundreds of files.
- **Check the launcher logs** for errors. Enable Enhanced Logging in [Settings](settings.md) to get detailed output.
- **Check your internet connection** -- if the launcher is stuck downloading a file, a network interruption may be the cause.
- **Restart the launcher** -- close it and reopen. The launcher will resume where it left off, skipping files that were already verified.
- **Reset as a last resort** -- use **Reset Launcher** in [Settings](settings.md) to return to a clean state. This clears all cached data and settings. See [Settings](settings.md) for details on what reset does.

---

## Offline Mode

The launcher requires an internet connection for authentication and downloading files. However, there is limited offline functionality:

- **Cached game files** -- after a successful first download, modpack files (mods, configs, assets, libraries) are cached locally. If you lose connectivity, the game may still launch using these cached files, provided all files were successfully downloaded previously.
- **Cached authentication tokens** -- tokens are cached for approximately 4 hours. If your token is still valid when you lose connectivity, you can play within that window.
- **Limitations** -- without an internet connection, you cannot: authenticate for the first time, download new modpacks, receive modpack updates, or download missing files.

---

## Performance Issues

**Symptoms:** The game runs but with low FPS, frequent stuttering, or long loading times.

**Solutions:**

- **Switch to the Performance (Aikar's Flags) JVM preset** in [Settings](settings.md) > JVM Presets. This is the single most impactful change for most users.
- **Allocate enough RAM** -- at least 4 GB for most modpacks, 6 - 8 GB for large packs (150+ mods).
- **Do not over-allocate RAM** -- giving the game too much memory can cause long GC pauses. Stay at or below half your system memory.
- **Close other applications** -- browsers, video editors, and other memory-intensive applications compete for resources.
- **Update your graphics drivers** -- outdated GPU drivers are a common cause of poor Minecraft performance.
- **Check in-game settings** -- reduce render distance, disable shaders, or lower graphics quality in Minecraft's video settings.
- **Verify your system meets requirements** -- modded Minecraft is CPU and memory intensive. A system with less than 8 GB of total RAM will struggle with most modpacks.

---

## Missing Mods or Files

**Symptoms:** The game launches but mods are missing, configs are wrong, or the modpack behaves differently than expected.

**Solutions:**

- **Re-launch the modpack** -- the launcher verifies file hashes on each launch. If files were manually modified or deleted, they will be re-downloaded to match the modpack definition.
- **Check for manual modifications** -- if you added or modified files in the modpack folder manually, the launcher may overwrite them on the next launch to maintain consistency with the modpack manifest.
- **Report missing mods** -- if a mod is missing from the modpack definition itself (not just your local files), contact the modpack author.
- **Remove and re-add the modpack** -- as a more thorough fix, remove the modpack in [Edit Mod Packs](edit-modpacks.md) and add it again. This forces a complete re-download of all files.

---

## Authentication Issues

**Symptoms:** Cannot sign in, repeated sign-in prompts, or authentication errors.

**Solutions:**

- **Token expiration is normal** -- authentication tokens expire after approximately 4 hours. Being prompted to sign in again after this period is expected behavior.
- **Clear browser cookies** -- if sign-in fails repeatedly, try clearing your browser's cookies for `login.microsoftonline.com` and `login.live.com`.
- **Verify your Minecraft license** -- ensure your Microsoft account has an active Minecraft: Java Edition license. Check at [minecraft.net](https://www.minecraft.net/).
- **Check Xbox Live status** -- Microsoft authentication depends on Xbox Live services. If those services are experiencing an outage, authentication will fail. Check [Xbox Live Status](https://support.xbox.com/en-US/xbox-live-status).
- **Try a different browser** -- if the authentication page does not load, try setting a different default browser on your system.
- **Reset the launcher** -- if authentication is persistently broken, use **Reset Launcher** in [Settings](settings.md) to clear the corrupted token cache, then sign in again.

See [Login](login.md) for more details on the authentication process.

---

## Java / Runtime Errors

**Symptoms:** Errors mentioning Java versions, `UnsupportedClassVersionError`, or the game fails to start with a Java-related message.

**Solutions:**

- **Re-download the runtime** -- open [Settings](settings.md) > Runtime Management, delete the runtime used by the affected modpack, and relaunch. The launcher will download a fresh copy.
- **Check for system Java conflicts** -- the launcher manages its own Java installations, but system-wide `JAVA_HOME` or `PATH` variables can sometimes interfere.
- See [Runtime Management](runtime-management.md) for detailed troubleshooting steps.

---

## Reporting Issues

When reporting issues to modpack authors or support channels, always include:

- **Modpack name and version** -- visible on the main screen.
- **Operating system** -- Windows, macOS, or Linux, and the specific version.
- **Launcher log file** -- enable Enhanced Logging in Settings, reproduce the issue, and share the log file.
- **Crash report** -- if the game crashed, include the crash report from the modpack's `crash-reports/` folder.
- **What you were doing** -- describe the steps that led to the issue.

> **Tip:** The more information you provide upfront, the faster the issue can be diagnosed. A crash report without context is much harder to debug than one accompanied by reproduction steps and logs.
