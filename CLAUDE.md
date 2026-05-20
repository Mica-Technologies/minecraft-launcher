plendi# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Mica Minecraft Launcher -- a cross-platform Minecraft Forge modpack launcher built with Java 26 and JavaFX. Supports client (GUI) and server (headless) modes on Windows, macOS, and Linux. Modpacks are defined as JSON files hosted at URLs.

## Build & Run

Requires JDK 26 with JavaFX (e.g. Azul Zulu `jdk+fx` or AWS Corretto). Maven handles JDK download for packaging via `mvn-jlink-wrapper`.

### Maven & JDK Location (Windows / IntelliJ)

Maven is not typically on the system PATH. Use IntelliJ's bundled Maven with the project's configured JDK:

- **Maven:** `C:\Users\[username]\AppData\Local\Programs\IntelliJ IDEA\plugins\maven\lib\maven3\bin\mvn.cmd`
- **JDKs (IntelliJ-managed):** `C:\Users\[username]\.jdks\` -- check `.idea/misc.xml` `project-jdk-name` attribute for the configured SDK name (e.g. `azul-26`), then find the matching folder under `.jdks/`
- **JAVA_HOME** must be set when invoking Maven from the command line:

```bash
# Set JAVA_HOME and compile (replace [username] with your Windows user)
JAVA_HOME="C:/Users/[username]/.jdks/azul-26.0.1" "C:/Users/[username]/AppData/Local/Programs/IntelliJ IDEA/plugins/maven/lib/maven3/bin/mvn.cmd" compile
```

### Standard Maven Commands

```bash
# Compile and package (fat JAR + native installer)
mvn -B package --file pom.xml

# Compile only (skip native packaging)
mvn compile

# Run just the unit tests
mvn test

# Clean build artifacts (target/ and packaging/ directories)
mvn clean
```

Unit tests live under `src/test/java/` using JUnit Jupiter (6.x). They cover the launcher's pure-logic seams — RGB backend resolver / circuit breaker, the `jar:` URL containment gate, etc. — and intentionally avoid JavaFX runtime, vendor SDKs, and network I/O so they run in well under a second total. CI runs `mvn -B package` on all three platforms (Windows, macOS, Linux) via `.github/workflows/build-packages.yml`, which exercises the test phase as a side effect.

When adding new tests, prefer extracting a package-private seam in the production class over reflection / Mockito. The current tests use plain JUnit assertions plus small hand-rolled stubs (e.g. `RgbBackendRegistryTest`'s `StubBackend`); we have not added a mocking framework and don't currently need one.

Build outputs:
- `target/*-jar-with-dependencies.jar` -- runnable fat JAR
- `packaging/` -- native installers (EXE/MSI, DMG/PKG, DEB/RPM)

## Architecture

**Entry point:** `LauncherCore.main()` -- parses CLI args (`-c` client, `-s` server, optional modpack name), detects game mode, authenticates, then enters the GUI loop or launches the server directly. The main method runs in a `while(restartFlag)` loop, allowing the application to restart without JVM restart.

### Key Packages (under `com.micatechnologies.minecraft.launcher`)

| Package | Purpose |
|---|---|
| `game/modpack/` | Core game logic: `GameModPack` (modpack lifecycle, game launch command assembly), `GameModPackManager` (modpack list CRUD), `GameModLoaderForge` (Forge installer extraction, library resolution, classpath building) |
| `game/modpack/manifests/` | Mojang/Forge manifest parsing: `GameVersionManifest` (resolves MC version to library manifest URL), `GameLibraryManifest` (native + Java libraries, platform rules), `GameAssetManifest` (game assets), `ManifestRuleUtilities` (shared rule evaluation) |
| `game/auth/` | Microsoft/Minecraft authentication via `minecraft_authenticator` library; AES-256-GCM encrypted token caching with machine-derived key |
| `gui/` | JavaFX controllers for each screen. `MCLauncherGuiController` is the singleton that manages screen transitions. FXML files in `src/main/resources/gui/` |
| `files/` | `SynchronizedFileManager` (thread-safe file access), `Logger`, `LocalPathManager`, `RuntimeManager` (multi-version Java runtime management) |
| `config/` | JSON-based config persistence via GSON (`ConfigManager`) |
| `consts/` | Constants classes (`RuntimeConstants`, `LauncherConstants`, `ModPackConstants`, `ConfigConstants`) and `localization/LocalizationManager` |
| `utilities/` | HTTP downloads, hashing (SHA-1 verification), Discord RPC, system theme detection, process execution |

### Game Launch Flow

1. User selects modpack in GUI (or specifies via CLI)
2. `GameModPack` downloads and verifies the modpack JSON definition
3. `GameModLoaderForge` extracts the Forge installer JAR, reads its embedded `version.json` and `install_profile.json`
4. `GameVersionManifest` resolves the Minecraft version to its client.json URL (piston-meta v2)
5. `GameLibraryManifest` and `GameAssetManifest` download/verify all libraries and assets (threaded, SHA-1 checked)
6. `RuntimeManager` verifies the required Java runtime (on-demand per modpack, multi-version)
7. `GameModLoaderForge.runForgeProcessors()` executes the Forge patching pipeline (modern Forge 1.13+)
8. Security scan via jarscanner
9. `GameModPack.startGame()` assembles the full JVM command line (classpath, game args, auth tokens) and launches via `ProcessBuilder`

### Forge Library Resolution

`GameModLoaderForge.getForgeAssets()` is the most complex method. It handles:
- Libraries with `downloads.artifact` (direct URL) vs Maven coordinate-only entries
- Embedded Maven artifacts inside the Forge installer JAR (`jar:` URLs)
- Platform-specific native classifier resolution (e.g. `natives-windows`, `natives-linux-${arch}`)
- Modern vs legacy Forge detection (base + universal JAR vs universal only)
- Classpath deduplication via `LinkedHashSet`

### Manifest Rule Evaluation

`ManifestRuleUtilities` centralizes Mojang/Forge rule evaluation (OS name, version, arch matching with regex) and argument flattening. Supports `versionRange` (min/max) for MC 26.1+ rule format. Used by both `GameLibraryManifest` and `GameModLoaderForge`.

## In-Depth System Documentation

See `docs/` for detailed technical documentation on major subsystems:
- `docs/GAME_LAUNCH_SYSTEM.md` -- Full launch pipeline: manifest chain, Forge integration, classpath assembly, argument construction
- `docs/RUNTIME_MANAGEMENT.md` -- Multi-version Java runtime: Mojang/Liberica sources, platform detection, download flow
- `docs/AUTHENTICATION_SYSTEM.md` -- Microsoft OAuth, AES-256-GCM token cache, machine key derivation
- `docs/GUI_SYSTEM.md` -- JavaFX architecture, screen navigation, theming, game console

Agent progress/tracking docs are in `docs/agent-progress-plans/`.

## Localization (i18n)

All user-visible strings should route through `LocalizationManager` so
the launcher renders in the user's chosen language. The infrastructure
supports OS-locale autodetect at startup with a manual override in
Settings → Appearance → Language.

**Source-of-truth bundle:** `src/main/resources/lang/DisplayStrings.properties`
(English). Per-locale translations live alongside as
`DisplayStrings_<bcp47-tag>.properties` and are auto-generated by
`tools/i18n/translate-locales.js`. Both runtime + tooling use BCP-47
tags (e.g. `fr-FR`, `pt-BR`) — see `SupportedLocales.ENTRIES` for the
canonical list.

**Key naming convention:**
- New keys use dot-namespaced form: `<screen>.<element>.<purpose>`
  (e.g. `browse.filter.type.label`, `console.title.crashed`,
  `notification.install.failed.body`).
- The existing 89 legacy `ALL_CAPS` keys are preserved for backwards
  compatibility with `LocalizationManager`'s static-final fields —
  don't add new ones in that style.
- Parameterised messages use MessageFormat slots (`{0}`, `{1}`, ...) —
  never string-concatenate user-visible data into a template.

**How to use:**

```java
// Plain lookup
LocalizationManager.get( "main.pagination.empty" )

// Parameterised
LocalizationManager.format( "main.pagination.range", start, end, total )

// FXML — use %key syntax; FXMLLoader resolves via the active bundle
text="%console.title.console"
```

**Adding new strings:**
1. Add the new key + English value to `DisplayStrings.properties` in the
   dot-namespaced section.
2. Reference it from Java via `LocalizationManager.get/format` or from
   FXML via `text="%key"`.
3. Run `cd tools/i18n && npm run translate` to auto-translate the new
   key into every supported locale (incremental — won't re-translate
   existing values).
4. Commit the updated `.properties` files together with the code change.

**Sentinel strings:** when domain-layer code (e.g. `getLastPlayedFormatted`)
returns user-visible text AND callers compare against it, add a
boolean helper (e.g. `isNeverPlayed()`) rather than string-matching
against the localized output. The string-compare only works in
English; the boolean works in every locale.

**`LocaleBootstrap`:** runs at startup via `LauncherSession.run` to
resolve the effective locale (config override → OS detect → `en-US`
fallback) and call `Locale.setDefault` BEFORE `LocalizationManager`
first loads. The class is deliberately standalone so calling
`apply()` doesn't trigger `LocalizationManager`'s class init, which
would otherwise lock the 89 legacy `static final` fields to the
launch-time locale.

## Code Style

- Allman-style braces (opening brace on its own line for classes/methods)
- Spaces inside parentheses: `if ( condition )`, `method( arg1, arg2 )`
- Spaces inside angle brackets for generics: `List< String >`, `ArrayList< GameLibrary >`
- Verbose Javadoc on public methods with `@since` tags
- File headers: GNU GPLv3 copyright block with `Mica Technologies` attribution

## Branch & PR Policy

- `main` is protected; all work goes on feature/dev branches
- PRs to `main` require at least one review and must pass CI build on all three platforms
- CI automatically builds on push/PR to `main`

## Commit Conventions

- **Commit as you go:** Create logical, well-scoped commits after completing each meaningful unit of work. Do not accumulate large batches of unrelated changes into a single commit.
- **Descriptive messages:** Lead with a short imperative summary (e.g., "Add multi-version runtime management", not "wip" or "progress"). Use the commit body for details when the summary alone isn't sufficient.
- **Scope commits logically:** Group related changes together. For example, a new feature's model, controller, and view files belong in one commit, but an unrelated bug fix should be a separate commit.
- **Compile before committing:** Verify that the project compiles successfully (`mvn compile`) before creating a commit. Do not commit code that breaks the build.
