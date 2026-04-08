# Settings

The settings screen lets you customize memory allocation, appearance, JVM behavior, network configuration, and more. All changes are saved automatically -- there is no save button.

Access settings from the [Main Screen](main-screen.md) by clicking the **Settings** button.

---

## Game Memory

Control how much system RAM is allocated to the Minecraft JVM process.

| Setting | Description |
|---|---|
| **Minimum RAM** | The initial heap size (`-Xms`) allocated to the game JVM. This is the amount of memory reserved immediately when the game starts. Typically leave at the default (512 MB) unless a modpack specifies otherwise. |
| **Maximum RAM** | The upper memory limit (`-Xmx`) for the game. The JVM will not use more than this amount. Most modpacks perform best with **4 - 8 GB**. |

> **Tip:** A good rule of thumb: set maximum RAM to no more than half your total system memory. For example, on a 16 GB machine, 6 - 8 GB is a reasonable upper limit. Allocating too much can starve your operating system and other applications, causing the game to perform *worse*, not better.

### Memory Recommendations by Modpack Size

| Modpack Type | Recommended Max RAM |
|---|---|
| Small (< 50 mods) | 3 - 4 GB |
| Medium (50 - 150 mods) | 4 - 6 GB |
| Large (150+ mods) | 6 - 8 GB |
| Vanilla | 2 - 4 GB |

---

## Theme

Choose the visual appearance of the launcher. The selected theme affects all launcher windows, including dialogs and the modpack editor.

| Theme | Description |
|---|---|
| **Automatic** | Follows your operating system's light/dark mode preference. On Windows, this reads the system color scheme; on macOS, it follows the Appearance setting; on Linux, it uses the desktop environment's theme preference where available. |
| **Dark** | Dark background with light text. Easy on the eyes, especially in low-light environments. |
| **Light** | Traditional light background with dark text. |
| **Blue + Gray** | A cool-toned accent theme with blue highlights on a gray background. |
| **Orange + Purple** | A warm-toned accent theme with orange and purple highlights. |

---

## Advanced Options

Toggle features that affect launcher behavior and diagnostics.

| Setting | Description |
|---|---|
| **Debug Mode** | Enables verbose logging output in the launcher console. When enabled, the launcher prints detailed information about every operation -- file downloads, hash checks, Forge processing steps, and more. Useful for diagnosing issues but produces a large volume of output. |
| **Window Resizing** | Allows the launcher window to be freely resized beyond its default dimensions. By default, the launcher window has a fixed size. Enable this if you want to make it larger or work with a non-standard display. |
| **Discord Integration** | Shows your current modpack as a Discord Rich Presence status. When enabled and Discord is running, your Discord profile will display the name of the modpack you are playing. |
| **Enhanced Logging** | Writes detailed log files to disk for post-session analysis. When enabled, the launcher saves comprehensive logs that persist after the launcher closes. Useful for sharing with support or modpack authors when diagnosing issues. |
| **In-Game Console** | Opens a [Game Console](game-console.md) window alongside Minecraft to view real-time output. The console shows all game log messages, warnings, errors, and crash reports as they happen. |

---

## JVM Presets

Pre-configured sets of JVM flags that are appended to the game launch command. These flags control garbage collection behavior and other low-level JVM settings that significantly affect performance.

| Preset | Description |
|---|---|
| **Performance (Aikar's Flags)** | **Recommended for most users.** A set of optimized garbage collection flags developed by Aikar and widely used by the Minecraft community. These flags tune the G1 garbage collector for Minecraft's specific memory usage patterns, reducing lag spikes caused by GC pauses. Best overall performance for the vast majority of modpacks. |
| **Low Memory** | Conservative flags designed for systems with limited RAM (8 GB or less of total system memory). Reduces GC overhead and memory footprint at the cost of some throughput. Use this if you cannot allocate more than 3-4 GB to the game. |
| **Debug** | Enables JVM debug output including detailed GC logs and diagnostic information. Useful for diagnosing crashes, memory leaks, or performance problems. Not recommended for normal play as the extra logging adds overhead. |
| **None** | No extra flags -- uses the JVM's built-in defaults. Use this only if you know what you are doing, if a modpack author specifically recommends it, or if you are experiencing compatibility issues with the other presets. |

> **Tip:** If you are unsure which preset to use, start with **Performance (Aikar's Flags)**. It is safe, well-tested, and provides the best experience for most modpacks.

---

## Network / Proxy

Configure a network proxy if your internet connection requires one. This is most common on corporate, school, or other restricted networks.

| Setting | Description |
|---|---|
| **HTTP Proxy** | Standard web proxy used for HTTP and HTTPS connections. Enter the hostname (or IP address) and port number provided by your network administrator. Example: `proxy.example.com:8080`. |
| **SOCKS Proxy** | A lower-level proxy protocol that tunnels all network traffic. Use this if your network specifically requires SOCKS (version 4 or 5). Enter the hostname and port as with HTTP proxy. |

> **Note:** Most users can leave proxy settings blank. Only configure these if downloads or authentication fail on a restricted network. If you are unsure whether your network uses a proxy, ask your network administrator.

When a proxy is configured, the launcher routes all of its network traffic -- file downloads, manifest fetches, and authentication requests -- through the specified proxy server.

---

## Malware Scanning

The launcher includes a built-in scanner that checks downloaded JAR files for signatures of **fractureiser** -- a Minecraft-specific malware that spread through compromised mods and plugins in mid-2023.

Key details:

- The scan runs **automatically before each game launch**. You do not need to trigger it manually.
- It checks all JAR files in the modpack's `mods/` directory and other relevant locations.
- If malware is detected, the launcher will **block the launch** and display a warning identifying the affected file(s).
- The scanner uses signature-based detection derived from the community's fractureiser analysis.

This provides an additional layer of security on top of the SHA-1 file integrity checks that the launcher already performs on every download.

---

## Reset and Runtime Options

| Setting | Description |
|---|---|
| **Reset Launcher** | Restores all settings to their factory defaults and clears all cached data, including authentication tokens, modpack state, and configuration. **This cannot be undone.** |
| **Runtime Management** | Opens the [Runtime Management](runtime-management.md) screen where you can view, verify, or delete installed Java runtimes. |

> **Warning:** Resetting the launcher will remove all settings, cached authentication tokens, and modpack state. Your modpack files on disk are preserved, but you will need to re-add modpacks to the launcher and sign in again. Use this as a last resort when other troubleshooting steps have failed.

### When to Reset

Consider resetting the launcher if:

- Settings appear corrupted or the launcher behaves unexpectedly after a configuration change.
- You want to start with a completely clean state.
- Other troubleshooting steps (documented in [Troubleshooting](troubleshooting.md)) have not resolved your issue.

---

## Import / Export Settings

Transfer your launcher configuration between machines or create a backup.

- **Export** -- saves your current configuration (all settings, modpack list, and preferences) to a file that you can store or share.
- **Import** -- loads a previously exported configuration file, replacing your current settings with the imported values.

### Use Cases

- **New machine setup** -- export from your old machine, import on the new one to replicate your configuration instantly.
- **Sharing with friends** -- export your settings and share the file so others can use the same JVM flags, memory allocation, and theme.
- **Backup before reset** -- export your settings before using Reset Launcher, so you can restore them afterwards.
- **Consistent team configuration** -- for modpack communities or servers, distribute a standard settings file to ensure all players use recommended JVM flags and memory settings.

> **Note:** The exported file contains launcher settings only -- it does not include modpack files, game saves, or authentication tokens.
