# Getting Started

Welcome to the **Mica Minecraft Launcher** -- a cross-platform Minecraft Forge modpack launcher that makes it easy to install, update, and play modpacks with a single click.

## Quick Start

The basic workflow is three steps:

1. **Sign in** with your Microsoft account. The launcher uses official Microsoft authentication to verify your Minecraft ownership. See [Login](login.md) for details on the authentication process.

2. **Select a modpack** from the list on the left side of the [Main Screen](main-screen.md). If no modpacks are installed yet, use [Edit Mod Packs](edit-modpacks.md) to add one.

3. **Click Play** and the launcher will automatically download all required files, install the correct Forge version, verify file integrity, and launch the game. No manual setup is needed -- the launcher handles everything.

## What the Launcher Handles for You

You do not need to manually install Java, Forge, or individual mods. The launcher manages:

- **Modpack file management** -- downloading and updating mods, configs, resource packs, and shader packs to match the modpack definition.
- **Forge installation** -- installing the correct Forge version for each modpack automatically.
- **Java runtime management** -- downloading and managing the correct Java version for each modpack. No manual JDK installation is needed. See [Runtime Management](runtime-management.md) for details.
- **File integrity verification** -- checking every downloaded file against SHA-1 hashes to ensure nothing is corrupted or tampered with.
- **Malware scanning** -- scanning downloaded JAR files for known Minecraft-specific malware signatures (such as fractureiser) before each launch.

## Feature Overview

Beyond the core launch workflow, the launcher includes:

- **Multiple modpack support** -- install and switch between any number of modpacks, each with its own isolated file set and Forge version.
- **Automatic updates** -- when a modpack author publishes a new version, the launcher detects it and applies the update on your next launch.
- **Vanilla Minecraft** -- play unmodded Minecraft releases and snapshots directly from the launcher. See [Vanilla Versions](vanilla-versions.md).
- **Modpack Editor** -- a built-in editor for creating and editing modpack JSON definitions with validation, Modrinth search integration, and diff tracking. See [Modpack Editor](modpack-editor.md).
- **Customizable settings** -- memory allocation, themes, JVM presets, proxy configuration, and more. See [Settings](settings.md).
- **In-game console** -- view real-time game output and crash reports alongside Minecraft. See [Game Console](game-console.md).
- **Discord Rich Presence** -- show your current modpack as a Discord status.
- **Desktop shortcuts** -- create shortcuts to launch specific modpacks directly from your desktop.
- **Play statistics** -- track total play time and session history per modpack.
- **Import/Export settings** -- share your configuration between machines or back it up before a reset.

> **Tip:** Look for the **?** button on every screen -- it opens context-sensitive help for the screen you are currently viewing.

## System Requirements

The launcher itself is lightweight, but Minecraft (especially modded) can be demanding:

- **Operating system:** Windows 10+, macOS 10.14+, or Linux (most modern distributions)
- **Internet connection:** Required for authentication and initial file downloads. After the first launch, cached files allow limited offline play.
- **RAM:** At least 8 GB of system memory recommended. Most modpacks need 4-8 GB allocated to the game.
- **Disk space:** Varies by modpack. A typical modpack requires 1-3 GB; the launcher and runtimes add another 200-400 MB.

## Next Steps

- [Main Screen](main-screen.md) -- learn how to navigate the launcher interface
- [Settings](settings.md) -- customize memory, themes, JVM flags, and more
- [Edit Mod Packs](edit-modpacks.md) -- add and remove modpacks
- [Vanilla Versions](vanilla-versions.md) -- play unmodded Minecraft
- [Troubleshooting](troubleshooting.md) -- common issues and solutions
