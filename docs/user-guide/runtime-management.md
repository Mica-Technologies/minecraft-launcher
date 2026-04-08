# Runtime Management

The Mica Minecraft Launcher automatically manages Java runtimes so you never need to install or configure Java yourself. This page explains how runtimes work, how to view them, and how to troubleshoot runtime-related issues.

## Why Runtimes Matter

Minecraft and Forge require specific Java versions to run correctly. Using the wrong Java version causes crashes, class loading errors, or other failures. Different modpacks and Minecraft versions may need different runtimes:

| Minecraft Version | Required Java Runtime |
|---|---|
| 1.16 and earlier | Java 8 |
| 1.17 - 1.20 | Java 17 |
| 1.21+ | Java 21 or later |

The launcher detects which runtime each modpack or vanilla version requires (based on the Minecraft version it targets) and manages the correct runtime automatically.

## Automatic Downloads

When you launch a modpack or vanilla version for the first time, the launcher checks whether the required Java runtime is already installed in its managed runtime directory. If not, it downloads the correct version automatically.

### Download Sources

The launcher downloads runtimes from two sources:

- **Mojang's runtime distribution** -- the same runtimes used by the official Minecraft launcher.
- **Liberica JDK (BellSoft)** -- a well-tested OpenJDK distribution used as a fallback or for versions not provided by Mojang.

### Download Details

- Runtime downloads are typically **40 - 80 MB** per version.
- Each runtime version is downloaded only once. Subsequent launches that need the same runtime reuse the existing installation.
- Runtimes are stored in the launcher's data directory, **completely separate** from any Java installation on your system. The launcher's runtimes do not interfere with system-installed Java and vice versa.

> **Note:** The first launch of a modpack may take longer than usual due to the runtime download. Subsequent launches skip this step.

## Viewing Installed Runtimes

To see all installed Java runtimes:

1. Open [Settings](settings.md) from the main screen.
2. Click **Runtime Management**.

The runtime management screen displays a list of all managed Java runtimes, including:

- **Java version** -- the major version number (e.g., Java 8, Java 17, Java 21).
- **Path** -- the filesystem location of the runtime installation.
- **Associated modpacks** -- which installed modpacks use this runtime.

## Deleting Unused Runtimes

If you have removed modpacks that used a particular Java version, you can delete the corresponding runtime to free disk space:

1. Open the Runtime Management screen (via Settings).
2. Select the runtime you want to delete.
3. Click **Delete**.
4. Confirm the deletion.

> **Warning:** Deleting a runtime that is still needed by an installed modpack will cause the launcher to re-download it on the next launch of that modpack. This is not harmful -- it just adds a download step -- but it wastes bandwidth. Check which modpacks use a runtime before deleting it.

## How the Launcher Chooses a Runtime

The selection process is automatic and based on the Minecraft version targeted by the modpack:

1. The launcher reads the modpack's Minecraft version from its manifest.
2. It determines the required Java major version based on Mojang's runtime requirements for that Minecraft version.
3. It checks whether that Java version is already installed in the managed runtime directory.
4. If found, it uses the existing runtime. If not, it downloads the correct version.
5. The runtime's `java` (or `javaw` on Windows) executable is used to launch the game.

You do not need to configure any of this manually.

## Troubleshooting Runtimes

If a modpack fails to launch with Java-related errors (e.g., `UnsupportedClassVersionError`, `java.lang.NoClassDefFoundError`, or the game simply fails to start):

### Step 1: Re-download the Runtime

1. Open the Runtime Management screen.
2. Find and select the runtime used by the affected modpack.
3. Click **Delete** to remove it.
4. Launch the modpack again -- the launcher will download a fresh copy of the runtime.

### Step 2: Check Memory Settings

Some Java-related crashes are actually caused by insufficient memory. Ensure your maximum RAM setting in [Settings](settings.md) is appropriate for the modpack (typically 4-8 GB for modded Minecraft).

### Step 3: Check for Conflicting System Java

The launcher's managed runtimes are isolated from your system Java installation, but in rare cases, environment variables like `JAVA_HOME` or entries in your system `PATH` can interfere. If you suspect a conflict:

- The launcher should ignore system Java entirely -- but if issues persist, try temporarily renaming or uninstalling any system-wide Java installation to test.
- Check that no environment variables are forcing a specific Java version.

### Step 4: Reset and Retry

If all else fails:

1. Use **Reset Launcher** in [Settings](settings.md) to clear all cached data.
2. Re-add your modpacks.
3. Launch again -- the launcher will re-download all runtimes from scratch.

For additional help, see [Troubleshooting](troubleshooting.md).
