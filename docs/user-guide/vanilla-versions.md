# Vanilla Versions

In addition to Forge modpacks, the launcher can install and play **vanilla (unmodded) Minecraft** versions directly. This is useful for playing the base game, testing new Minecraft releases, or verifying that your system meets the basic requirements before installing larger modpacks.

## Browsing Versions

The vanilla versions screen displays a list of available Minecraft releases and snapshots fetched from Mojang's version manifest. Each entry shows:

- **Version number** -- the Minecraft version identifier (e.g., `1.21.4`, `24w14a`).
- **Release date** -- when Mojang published this version.

Both full releases and snapshots (pre-release/experimental versions) are listed.

## Installing and Launching

1. **Select a Minecraft version** from the list.
2. **Click Install** to download the game client, libraries, and assets for that version. The launcher handles all file downloads and verification automatically.
3. **Once installed, click Play** to launch. The launcher verifies all files, ensures the correct Java runtime is available, and starts the game.

The installation process downloads:

- The Minecraft client JAR for the selected version.
- All required libraries (LWJGL, logging libraries, etc.).
- Game assets (textures, sounds, language files) from Mojang's asset servers.
- The correct Java runtime if it is not already installed (see below).

All files are verified with SHA-1 hashes to ensure integrity.

## Runtime Management

Different Minecraft versions require different Java runtimes:

| Minecraft Version | Required Java |
|---|---|
| 1.16 and earlier | Java 8 |
| 1.17 - 1.20 | Java 17 |
| 1.21+ | Java 21 or later |

The launcher **automatically downloads and manages** the correct runtime for each version. You do not need to install Java manually. When you launch a version that requires a runtime you do not have yet, the launcher downloads it before starting the game.

See [Runtime Management](runtime-management.md) for more details on how runtimes are handled, including how to view and delete installed runtimes.

## Settings and Vanilla Versions

Vanilla versions use the same settings configured in [Settings](settings.md):

- **Memory allocation** -- the same minimum and maximum RAM settings apply. Vanilla Minecraft typically needs less RAM than modded (2-4 GB is usually sufficient).
- **JVM presets** -- the same JVM flags are applied. Aikar's flags work well for vanilla too.
- **Theme and other preferences** -- all launcher settings apply regardless of whether you are playing vanilla or a modpack.

> **Note:** If a vanilla version runs poorly, try adjusting your RAM allocation or switching to the Performance JVM preset in [Settings](settings.md).

## Use Cases for Vanilla Versions

- **Testing your system** -- launch a vanilla version to verify that Minecraft runs correctly on your hardware before installing modpacks.
- **Playing new releases** -- try new Minecraft versions on release day without waiting for Forge or modpack updates.
- **Snapshots and previews** -- experiment with upcoming Minecraft features via snapshot versions.
- **Troubleshooting** -- if a modpack crashes, launching the same Minecraft version in vanilla helps determine whether the issue is with Minecraft itself or with the mods.
