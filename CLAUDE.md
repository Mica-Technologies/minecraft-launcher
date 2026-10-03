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

### Maven & JDK Location (macOS / IntelliJ)

Same idea as Windows -- Maven isn't on PATH and there's no `mvnw` wrapper; use IntelliJ's bundled Maven with the project's configured JDK. macOS equivalents of the Windows paths above:

- **Maven:** `~/Applications/IntelliJ IDEA.app/Contents/plugins/maven-plugin/lib/maven3/bin/mvn` (older IntelliJ releases used `plugins/maven/` instead; the space in the app path **must** be quoted). IntelliJ may instead live under `/Applications/` or the JetBrains Toolbox (`~/Library/Application Support/JetBrains/Toolbox/apps/`).
- **JDKs (IntelliJ-managed):** `~/Library/Java/JavaVirtualMachines/` -- check `.idea/misc.xml` `project-jdk-name` (e.g. `azul-26`) and find the matching folder (e.g. `azul-26.0.1`).
- **JAVA_HOME** points at the JDK's `Contents/Home` (macOS bundle layout), not the JDK root.

```bash
# Set JAVA_HOME and compile
export JAVA_HOME=~/Library/Java/JavaVirtualMachines/azul-26.0.1/Contents/Home
"$HOME/Applications/IntelliJ IDEA.app/Contents/plugins/maven-plugin/lib/maven3/bin/mvn" -B compile
```

Note: an IntelliJ-managed `azul-26.0.1` is typically **not** the `jdk+fx` variant (no JavaFX modules in the JDK), but `compile` and the `test` phase still succeed because JavaFX arrives via Maven dependencies. Full native packaging or launching the GUI may need a `jdk+fx` JDK.

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

Unit tests live under `src/test/java/` using JUnit Jupiter (6.1.0) — 32 test classes across 8 packages (12 in `game/modpack`, 8 in `utilities`, 5 in `gui`, 4 across `rgb`, and one each in `security`, `game/crash`, and `files`). They target the launcher's pure-logic seams — RGB backend resolver / circuit breaker, the `jar:` URL containment gate, machine-secret cipher fingerprinting, crash-report analysis, scan-exclusion policy — and avoid vendor SDKs and network I/O so they run in well under a second total.

JavaFX tests DO exist: TestFX (`testfx-core` + `testfx-junit5` 4.0.18) backs `TestFxSmokeTest` and `SettingsLanguageButtonFxTest`. Both are gated behind `@EnabledIfEnvironmentVariable( named = "MMCL_RUN_TESTFX", matches = "true" )`, so they are opt-in and do not run in the default build. Keep new GUI tests behind that same gate.

Coverage is measured by JaCoCo 0.8.15 (`mvn test jacoco:report` → `build/target/site/jacoco/index.html`). Baseline at introduction: 7.03% instruction / 6.22% line coverage. **JaCoCo must stay at 0.8.15 or newer** — earlier releases abort report generation with `Unsupported class file major version 70` on this project's Java 26 bytecode; the agent still attaches and writes `jacoco.exec`, so the failure only appears at the report step. There is deliberately **no** coverage threshold: a gate set before a real baseline exists gets gamed or bypassed. The JaCoCo agent's JVM args land in `${jacocoArgLine}`, not the default `${argLine}`, because Surefire already carries an explicit `argLine` for the FXTaskbarProgressBar module export — using the default would silently overwrite it and report empty coverage. Surefire references that property with **late evaluation** (`@{jacocoArgLine}`, not `${jacocoArgLine}`): a `${}` reference is interpolated from the POM model before `prepare-agent` runs, resolves to the empty default, and leaves the agent silently unattached — tests still pass and no `jacoco.exec` is ever written.

CI runs tests via `.github/workflows/test-build-pr.yml`, which executes a dedicated `mvn -B test` job first and then a three-platform (Windows, macOS, Linux) `mvn -B package` matrix; `.github/workflows/build-release.yml` handles releases. A failing test already blocks a PR.

When adding new tests, prefer extracting a package-private seam in the production class over reflection / Mockito. The current tests use plain JUnit assertions plus small hand-rolled stubs (e.g. `RgbBackendRegistryTest`'s `StubBackend`); we have not added a mocking framework and don't currently need one.

Build outputs (note: the POM sets `<directory>${project.basedir}/build/target</directory>`, so **there is no top-level `target/` directory** — everything lands under `build/target/`):
- `build/target/*-jar-with-dependencies.jar` -- runnable fat JAR
- `build/target/surefire-reports/` -- per-test-class results
- `build/target/site/jacoco/index.html` -- coverage report
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
| `mcp/` | Model Context Protocol server, off by default: `McpBootstrap` (lifecycle), `McpServer` + `transport/` (loopback HTTP, admission checks, `--mcp` stdio relay), `tools/` + `resources/` (what is exposed, and the redaction boundary), `approval/` (risk classes, per-tool policy, consent). See `docs/MCP_SERVER_GUIDE.pdf` |

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
- `docs/PLATFORM_INTEGRATION.md` -- Native OS integration (macOS title-bar toolbar / hidden-inset / dock / menu bar / vibrancy, Windows DWM Mica / taskbar / jump list, Linux), shared shell menus + notifications, and the platform-gated fallback pattern
- `docs/MCP_SERVER_GUIDE.pdf` -- MCP server: setup for each client, architecture, the layered security/consent model, and a reference for every tool and resource. Generated from `docs/pdf-generation-assets/mcp-server-guide/` (`npm install && npm run build`); edit the HTML there, never the PDF

Agent progress/tracking docs are in `docs/agent-progress-plans/`.

## Localization (i18n)

All user-visible strings should route through `LocalizationManager` so
the launcher renders in the user's chosen language. The infrastructure
supports OS-locale autodetect at startup with a manual override in
Settings → Appearance → Language.

**Source-of-truth bundle:** `src/main/resources/lang/DisplayStrings.properties`
(English). Per-locale translations live alongside as
`DisplayStrings_<java-locale>.properties` and are auto-generated by
`tools/i18n/translate-locales.js`. `SupportedLocales.ENTRIES` is the
canonical list.

**Tags are BCP-47; filenames are not.** Locale *tags* use BCP-47
throughout (`SupportedLocales.ENTRIES`, the config override, the
translator's `TARGET_LOCALES`) — e.g. `pt-BR`, `zh-CN`. Locale
*filenames* must use Java's resource-bundle form, which separates
language and region with an **underscore**: `DisplayStrings_pt_BR.properties`.
`ResourceBundle.getBundle` resolves by `Locale.toString()`, which emits
`pt_BR`, so a hyphenated filename is never found and `getBundle`
silently falls back to the English root bundle — no error, no warning,
just an English UI. That shipped for `pt-BR`, `zh-CN` and `zh-TW` until
it was fixed. Convert only at the filename (`tag.replace('-', '_')`);
`DisplayStringsBundleParityTest.everySupportedLocaleResolvesItsOwnBundleAtRuntime`
guards it.

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
