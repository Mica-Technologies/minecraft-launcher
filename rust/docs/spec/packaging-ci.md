# Porting spec: packaging, installers, versioning and CI

Slice: `pom.xml` (versioning, packaging profiles, jpackage / WiX / Burn / DMG / PKG / DEB / RPM
steps), `deploy/**`, `.github/workflows/*.yml` (plus `.github/dependabot.yml`,
`.github/auto_assign.yml`, `.mvn/ci-settings.xml`, `tools/scripts/compute_bundle_revision.ps1`),
and the runtime code that the installers depend on: `SchemeRegistrar` (URL scheme and file
association registration), `VersionUtilities`, `LauncherConstants` (version / flavour),
`UpdateCheckConstants` / `UpdateCheckManager` (release feed). The native starters, the MSI
"launcher is running" gate and the AppCDS move live only on the unmerged branch
`tmp/starter-ci` (and `fix/inuse-upgrade-wipe`); they are documented in section 1.9 because they
fix real incidents that the Rust packaging must not reintroduce.

Target: a Cargo workspace under `rust/` (today: one crate, `crates/mmcl-app`, binary
`mica-minecraft-launcher`, GPUI 0.2.2), building the same installer set on Windows, macOS and
Linux, with no Tauri.

---

## 1. Current behaviour (what the Java build produces and why)

### 1.1 Pipeline overview

One `mvn -B package` per OS does everything. Phases, in order:

| Phase | Step | Notes |
|---|---|---|
| `initialize` | `derive-revision-from-git` (gmavenplus Groovy script) | Sets `revision` and `revision.numeric` (section 1.2). |
| `initialize` | `maven-clean-plugin` auto-clean | Wipes `build/` except `build/jdk/**` on EVERY build (even `compile`). |
| `initialize` | Windows only: `compute-bundle-revision` (antrun + PowerShell) | Sets `bundle.build.revision` (section 1.2.4). |
| `process-resources` | `flatten-maven-plugin` (`resolveCiFriendliesOnly`) | Resolves `${revision}` in the POM. |
| `compile` / `test` | javac 26, Surefire + JaCoCo agent | Not this slice. |
| `package` | `maven-assembly-plugin` | Fat JAR `launcher-<version>-jar-with-dependencies.jar`, manifest has `Main-Class`, `Implementation-Title/Version` (from POM name/version), and custom `Launcher-Environment: RELEASE|DEV`. |
| `package` | `mvn-jlink-wrapper` `cache-jdk` | Downloads Amazon Corretto JDK `featureVersion=26` (major only; a full version silently falls back to 25), arch `x64` or `aarch64` (profile `jdk-arch-mac-arm` on Apple Silicon), cached at `build/jdk`. Its `bin/jpackage` is the packager. |
| `package` | `stage-jar-for-jpackage` (antrun) | Copies the fat JAR to `build/target/jpackage-input/`; copies `deploy/windows/installer-resources` to `build/target/installer-resources/` and replaces `@@BANNER_BMP_PATH@@` / `@@DIALOG_BMP_PATH@@` in `main.wxs` with absolute paths; stages file-association `.properties` with absolute icon paths (`build/target/jpackage-file-assoc/{mac,linux}.properties` + `mmcjson-document.{icns,png}`); stages `build/target/jpackage-mac-resources/Info.plist` (alone, see 1.5). |
| `package` | per-OS profile executions (exec-maven-plugin) | jpackage, candle, light (sections 1.4 to 1.6). |

Profiles: `windows-packaging` (os family windows), `mac-packaging` (os family mac),
`linux-packaging` (unix + name Linux), `jdk-arch-mac-arm` (mac + aarch64), `dev` (manual `-Pdev`),
`linux-dev-packaging-safe-name` (Linux + property `launcher.environment=DEV`), `sbom` (CycloneDX
`makeAggregateBom`, writes `build/target/bom.xml` + `bom.json`).

Common jpackage inputs (all OSes): `--name "Mica Minecraft Launcher"` (DEV: `"Mica Minecraft
Launcher DEV"`), `--vendor "Mica Technologies"`, `--description "Mica Minecraft Launcher — a
cross-platform Minecraft Forge modpack launcher."`, `--license-file LICENSE` (GPLv3),
`--main-class com.micatechnologies.minecraft.launcher.LauncherCore`, `--jlink-options
"--compress=zip-9 --no-header-files --no-man-pages --generate-cds-archive"` (no `strip-debug`, on
purpose: stack traces keep JDK line numbers), and the JVM options listed in 1.10. None of the JVM
material survives in Rust; it is listed only so nobody looks for an equivalent.

### 1.2 Versioning

There are four version strings, and a Rust port needs all of them.

#### 1.2.1 Local / unstamped builds (`revision`)

Resolution order (Groovy script at `initialize`):

1. `-Drevision=...` from the CLI wins (CI always passes it).
2. Else `git describe --tags --dirty=.dirty --always` in the project dir (5 s timeout). Output
   examples: `2026.5.12` (on a tag), `2026.5.12-15-gabc1234` (past a tag),
   `2026.5.12-15-gabc1234.dirty` (uncommitted changes), `abc1234` (no tags, shallow clone).
3. Else the sentinel `0.0.0-no-git-tag`.

Caveat recorded in `test-build-pr.yml`: Maven freezes `<version>${revision}</version>` during
model building, before `initialize`, so the git-derived value never reaches the JAR manifest;
only `-Drevision` works. That is why the PR workflow computes `git describe` in a separate job
and passes it in. (Rust: compute in `build.rs`, which does not have this problem.)

#### 1.2.2 `revision.numeric` (macOS / Linux app version)

`-Drevision.numeric=...` wins; otherwise `revision` cut at the first `-`, then at the first
`+`; empty becomes `0.0.0`. A warning is logged when the result differs. macOS jpackage rejects
anything but `N.N.N`-style numbers in `--app-version`, so mac and Linux packages use this value.

#### 1.2.3 Release workflow versions (`prepare` job of `build-release.yml`)

- `BASE_VERSION = date -u +%Y.%m.%d` (zero-padded: `2026.05.12`).
- Full release (`workflow_dispatch` with `release=true`): fetch tags; if tag `BASE_VERSION`
  exists, `maxN=0`; for every tag `BASE_VERSION.*` whose suffix is all digits, `maxN=max`. If
  `maxN >= 0` the version is `BASE_VERSION.(maxN+1)`, else `BASE_VERSION`. Tag = version. A 4th
  numeric component is used instead of SemVer `+N` because jpackage on macOS rejects `+`, and the
  in-app comparator ignores build metadata, so `+1` would never be offered as an update.
- Pre-release (every push to `main`/`master`, or dispatch without `release=true`):
  `BASE_VERSION-pre.HHMM.UTC+0000+<short sha>` (`date -u +'pre.%H%M.%Z%z'` then `+$SHORT_SHA`).
  The doubled `+` is deliberate, for parity with the mod projects' tag history.
- `win_version` = version with the first two characters removed (`2026.05.12.1` →
  `26.05.12.1`), then cut at the first `-` and at the first `+`. Reason: MSI ProductVersion
  major must be <= 255.
- The job then `git tag <tag> <sha>` and `git push origin <tag>` (job has `contents: write`).

Values passed to the builds: `-Drevision=<version>` everywhere and, on Windows,
`-Djpackage.app.version=<win_version>`. Without that override (local builds and every PR build)
the Windows MSI version is the POM default `25.1.2`.

#### 1.2.4 Burn bundle revision (Windows)

`tools/scripts/compute_bundle_revision.ps1`: `floor((UtcNow - 2024-01-01T00:00Z).TotalMinutes) mod
65536`, printed to stdout. Bundle version = `<AppVersion>.<revision>`. Burn compares all four
parts and only upgrades strictly-older related bundles, so a strictly increasing 4th part stops
same-version reinstalls from stacking duplicate Add/Remove Programs (ARP) entries. Wraps every
~45 days. It is a separate `.ps1` file because Ant `<exec>` splits an inline `-Command` at the
first space and silently yields 0.

#### 1.2.5 Runtime version, flavour and name (`LauncherConstants`)

- Version: JAR manifest `Implementation-Version`; else `git describe --tags --dirty=.dirty
  --always` against the repo holding the running classes (2 s timeout; finds `.git` dir or file
  for worktrees by walking up from the code source); else `0.0.0-dev`.
- `LAUNCHER_IS_DEV`: true when there is no manifest version (IDE run) or the manifest has
  `Launcher-Environment: DEV` (case-insensitive).
- Name: manifest `Implementation-Title` (= POM `<name>`, always "Mica Minecraft Launcher"),
  plus `" DEV"` when dev mode and the title does not already contain `DEV`; IDE runs get
  `"Mica Minecraft Launcher DEV"`. `LAUNCHER_APPLICATION_NAME_TRIMMED` removes spaces and names the
  data folder: `~/.MicaMinecraftLauncher` or `~/.MicaMinecraftLauncherDEV` (client mode; server
  mode uses the CWD).
- Windows AppUserModelID set at startup with `SetCurrentProcessExplicitAppUserModelID`:
  `com.micatechnologies.minecraft.launcher.LauncherCore`, plus `DEV` suffix (no separator) in dev.

#### 1.2.6 Update check (consumer of the release feed)

`GET https://api.github.com/repos/Mica-Technologies/minecraft-launcher/releases/latest`; reads
`tag_name` (version) and `html_url` (page to open). `/releases/latest` excludes pre-releases and
drafts. Skipped when the user setting is off, or the running version is null, starts with
`0.0.0`, contains `no-git-tag`, or contains `-dev`. Update when
`compareVersionNumbers(installed, latest) == -1`. `html_url` must start with `https://` or
`http://` before it is opened.

`VersionUtilities.compareVersionNumbers(a, b)` (returns -1/0/1):

1. `strip(v)`: cut at first `-`, then at first `+`, then at first `_`; empty → `"0"`.
2. Split both on `.`; iterate to the longer length; a missing segment is 0.
3. `parseSegment(s)`: the leading ASCII-digit run parsed as int; empty / no leading digit /
   overflow → 0. Never throws (a throw here once broke main-menu card rebuilds).

Consequences a port must keep: `2026.05.12-pre...` equals `2026.05.12`; `2026.05.12.1 >
2026.05.12`; dev strings `2026.1.0-15-gabc1234.dirty` compare as `2026.1.0`.

### 1.3 Flavours (RELEASE vs DEV)

`-Pdev` sets `launcher.environment=DEV`, `packaging.displayName = packaging.name = "Mica
Minecraft Launcher DEV"`, and on Linux `packaging.linux.packageName=mica-minecraft-launcher-dev`.
Effects: different app name, install dir, Start-menu group text stays "Mica Minecraft Launcher"
(hard-coded `--win-menu-group`), data folder `~/.MicaMinecraftLauncherDEV`, AUMID suffix, and
runtime scheme registration is skipped in dev mode.

Known wart: the Windows MSI UpgradeCode (`3699f9a3-…`) and the Burn UpgradeCode (`b3a4f7c2-…`)
are the SAME for both flavours, so installing a DEV MSI/bundle removes a RELEASE install and
vice versa (the starter tells the two apart by install folder). The Rust port should use
distinct codes per flavour (section 3.4).

### 1.4 Windows

#### 1.4.1 Artifacts

| Built file | Released as (release workflow) | PR artifact name |
|---|---|---|
| `build/packaging/user/Mica Minecraft Launcher-<winver>.msi` | `Mica Minecraft Launcher-<winver>-user.msi` | `Mica Minecraft Launcher-user.msi` |
| `build/packaging/machine/Mica Minecraft Launcher-<winver>.msi` | `Mica Minecraft Launcher-<winver>-system.msi` | `Mica Minecraft Launcher-system.msi` |
| `build/packaging/Mica Minecraft Launcher Setup.exe` | `Mica Minecraft Launcher Setup.exe` | same |

x64 only (`Platform="x64"`, candle `-arch x64`). No code signing (no Authenticode step).
Toolchain: WiX 3.14 at `C:/Program Files (x86)/WiX Toolset v3.14/bin` (`-Dwix.toolset.bin`
override); CI adds it to `PATH`.

#### 1.4.2 The two MSIs (jpackage `--type msi`)

Both: `--win-dir-chooser`, `--win-menu`, `--win-menu-group "Mica Minecraft Launcher"`,
`--win-shortcut`, `--win-shortcut-prompt`, `--win-upgrade-uuid
3699f9a3-0d10-4228-ab2f-b86ca9a500ac`, `--icon "deploy/windows/Mica Minecraft Launcher.ico"`,
`--resource-dir build/target/installer-resources`, `--verbose` (without it WiX errors are
swallowed into a bare "exited with NNN"). Per-user adds `--win-per-user-install`. Windows gets
NO `--file-associations`: on Windows the `.mmcjson` association and `mmcl://` exist only through
runtime registration (1.7).

Install location: per-machine `%ProgramFiles%\Mica Minecraft Launcher\`; per-user: the repo's
comments and the Burn display text say `%LOCALAPPDATA%\Programs\Mica Minecraft Launcher\`
(jpackage roots per-user installs in `LocalAppDataFolder`; verify the exact folder on a real
install before relying on it). jpackage image layout under it: `<name>.exe` (native launcher),
`app\<name>.cfg` + `app\*.jar`, `runtime\` (jlink image, including `runtime\lib\modules`).

Customised jpackage resources (`deploy/windows/installer-resources/`), picked up by filename:

- `main.wxs` replaces jpackage's template. Differences from stock:
  - `Product Id="*"`: a random ProductCode per build. jpackage derives it from name+version,
    so two builds of one version shared a code and Windows Installer refused the second as
    "already installed" before the upgrade logic ran.
  - `Upgrade` with two `UpgradeVersion`s, both `OnlyDetect="no"`, `MigrateFeatures="yes"`:
    `Maximum=<ver> IncludeMaximum="yes"` (upgrade AND same-version reinstall) and
    `Minimum=<ver> IncludeMinimum="no"` (downgrade). Plus `<RemoveExistingProducts
    Before="CostInitialize"/>` (early REP). Net effect: any installed version is removed and
    the new one laid down: upgrades, downgrades and reinstalls all work without uninstalling.
  - Branding defines: `JpHelpURL=https://github.com/Mica-Technologies/minecraft-launcher/issues`,
    `JpAboutURL=https://minecraft.micatechnologies.com/`,
    `JpUpdateURL=https://github.com/Mica-Technologies/minecraft-launcher/releases`, set into
    `ARPHELPLINK` / `ARPURLINFOABOUT` / `ARPURLUPDATEINFO`; also `ARPINSTALLLOCATION`,
    `ARPCOMMENTS` (description), `ARPCONTACT` (vendor), `ARPSIZE`, `ARPPRODUCTICON`.
  - `WixUIBannerBmp` = `installer-banner.bmp` (493x58, 24 bpp), `WixUIDialogBmp` =
    `installer-background.bmp` (493x312, 24 bpp), absolute paths injected at build time
    (jpackage does not copy `.bmp` files from the resource dir to its temp dir).
  - `Binary JpCaDll SourceFile="msica.dll"` + `JpFindRelatedProducts` custom action. The DLL was
    `wixhelper.dll` before JDK 26; a mismatch makes light.exe exit 103. (No Rust equivalent
    needed.)
  - `Package InstallScope` = `perMachine` if `JpIsSystemWide` else `perUser`; `Compressed=yes`,
    `InstallerVersion=200`, `Language=1033`, one embedded cab `Data.cab`.
- `overrides.wxi`: empty stub include.
- `ShortcutPromptDlg.wxs`: jpackage's shortcut dialog with the two check boxes moved to Y=100
  (desktop) and Y=140 (Start menu) for optical centring; properties
  `JP_INSTALL_DESKTOP_SHORTCUT`, `JP_INSTALL_STARTMENU_SHORTCUT`.
- `CustomUIStrings_en.wxl` (en-us, codepage 1252): overrides Welcome, License ("[ProductName]
  is free software, licensed under the GNU GPL v3."), InstallDir, ShortcutPrompt, VerifyReady
  (install / remove / update texts, including "Your modpacks, Java runtimes, and saved games
  stored under your user profile are not affected"), Progress, Exit ("On first run, sign in with
  your Microsoft account…", kept under ~140 chars because ExitDialog body is 40 IU tall) and
  InstallScope strings. Port these strings verbatim (adjust "Java runtimes" only if still true).
- `iss.vtl`: a leftover Inno Setup Velocity template from the old fvarrui javapackager
  pipeline. Unused by the current build; do not port.

Dialog flow (jpackage JpUI): Welcome → License (LICENSE) → InstallDir (dir chooser) →
ShortcutPrompt (desktop + Start menu) → VerifyReady → Progress → Exit. The per-user MSI also
contains an InstallScope string set but jpackage builds a fixed-scope MSI.

#### 1.4.3 The Burn bootstrapper (`deploy/windows/bootstrapper/`)

Why it exists: jpackage cannot build one MSI that installs in either scope (it hard-codes the
install root), so two MSIs are built and Burn chooses.

`bundle.wxs` (candle `-ext WixBalExtension -ext WixUtilExtension -arch x64` with `-d`
`ProjectDir, MsiUserPath, MsiMachinePath, AppName, AppVersion, BundleBuildRevision,
AppDescription="Mica Minecraft Launcher — Forge modpacks, made simple."`; light `-b
deploy/windows/bootstrapper`):

- `Bundle Name=$(AppName) Version=$(AppVersion).$(BundleBuildRevision) Manufacturer="Mica
  Technologies"`, About/Help/Update URLs as above, `UpgradeCode=b3a4f7c2-1d9e-4f88-9b06-3a2c1c7e9501`,
  `IconSourceFile` = the launcher `.ico`, `DisableModify="yes"`, `Compressed="yes"`.
- `RelatedBundle Id=<same UpgradeCode> Action="Upgrade"`: every sibling bundle is an upgrade
  target regardless of version (cleans up legacy same-version duplicates).
- BA: `WixStandardBootstrapperApplication.HyperlinkLargeLicense` with
  `LicenseUrl=https://www.gnu.org/licenses/gpl-3.0.en.html`, `ThemeFile=theme.xml`,
  `LocalizationFile=theme.wxl`, `LogoFile` = launcher `.ico`, `ShowVersion=yes`.
- Variable `MMCL_INSTALL_FOR_ALL_USERS` (numeric, default 0, not persisted, overridable from the
  command line: `Setup.exe MMCL_INSTALL_FOR_ALL_USERS=1`). The theme check box of the same name
  binds to it.
- Variable `InstallFolder = [LocalAppDataFolder]Programs\[WixBundleName]` — display only; the
  MSIs ignore it (known: editing the Options-page path does nothing; with "all users" the real
  path is Program Files).
- Two `util:RegistrySearch`es read `DisplayVersion` from
  `HKCU|HKLM\Software\Microsoft\Windows\CurrentVersion\Uninstall\{3699f9a3-…}` into
  `MMCL_EXISTING_USER_VERSION` / `MMCL_EXISTING_MACHINE_VERSION`. Bug: ARP keys are named by
  ProductCode (random per build), not UpgradeCode, so these never match; nothing uses the
  variables. Do not port as is.
- Chain: `MsiPackage UserMsi` (`InstallCondition="MMCL_INSTALL_FOR_ALL_USERS = 0"`,
  `ForcePerMachine="no"`) and `MachineMsi` (`= 1`, `ForcePerMachine="yes"` → UAC); both
  `Compressed=yes Vital=yes Visible=no DisplayInternalUI=no`.

`theme.xml` (thmutil v4 namespace, required for `DpiContent`): window 500x420 (stock is 500x390)
with `HexStyle=100a0000` and `ExtendedHexStyle=00040000` (WS_EX_APPWINDOW so the window keeps a
taskbar button; WixStdBA minimises itself during the elevated phase and never restores — a
known, accepted limitation). No logo `<Image>` (WiX 3 BA is not DPI-aware; images render huge
on HiDPI). Install page: scope check box at Y=-71, EULA check box at Y=-41, both X=11
Width=-11. Pages: Help, Install, Options, FilesInUse, Progress, Modify, Success, Failure.
`theme.wxl` strings: scope check box "Install for &all users on this PC (requires
administrator)", help text documents `/install /repair /uninstall /layout /passive /quiet
/norestart /log` and `MMCL_INSTALL_FOR_ALL_USERS=1`, Options "Cancel" relabelled "Back".

#### 1.4.4 Windows runtime integration that the installer does not do

- `mmcl://` and `.mmcjson`: written to HKCU on every startup (1.7). Never removed on
  uninstall (orphaned keys pointing at a deleted exe). The Rust port should register them in the
  MSI too, so uninstall cleans up (section 3.5.2).
- Shortcuts carry no AppUserModelID property; the process sets its AUMID at startup.

### 1.5 macOS

#### 1.5.1 Artifacts

Two runners: `macos-latest` (arm64; Corretto `aarch64` via the `jdk-arch-mac-arm` profile) and
`macos-15-intel` (x64; `macos-13` was retired 2025-12-08 and hangs queued). Each produces
`build/packaging/Mica Minecraft Launcher-<numeric>.dmg` and `.pkg` (jpackage `--type dmg` and
`--type pkg`, same args otherwise). The workflow renames them to
`Mica Minecraft Launcher-<numeric>-arm64.{dmg,pkg}` and `…-intel.{dmg,pkg}` so the two arches do
not collide on the release page.

#### 1.5.2 Bundle contents

- `--icon deploy/mac/Mica Minecraft Launcher.icns`.
- `--resource-dir build/target/jpackage-mac-resources` holding ONLY `Info.plist`. Pointing at
  `deploy/mac/` makes jpackage copy `<name>.icns` from the dir AND from `--icon` to the same
  destination → `FileAlreadyExistsException`.
- `deploy/mac/Info.plist` = jpackage's template (placeholders `DEPLOY_LAUNCHER_NAME`,
  `DEPLOY_BUNDLE_IDENTIFIER`, `DEPLOY_BUNDLE_NAME`, `DEPLOY_BUNDLE_SHORT_VERSION`,
  `DEPLOY_BUNDLE_CATEGORY`, `DEPLOY_BUNDLE_CFBUNDLE_VERSION`, `DEPLOY_BUNDLE_COPYRIGHT`,
  `DEPLOY_ICON_FILE`, `DEPLOY_FILE_ASSOCIATIONS`) plus:
  - `LSMinimumSystemVersion 10.9`, `CFBundleDevelopmentRegion English`,
    `CFBundleAllowMixedLocalizations true`, `CFBundlePackageType APPL`,
    `CFBundleInfoDictionaryVersion 6.0`, `NSHighResolutionCapable true`,
    `NSSupportsAutomaticGraphicsSwitching true`.
  - `CFBundleURLTypes`: one dict, `CFBundleURLName "Mica Minecraft Launcher Protocol"`,
    `CFBundleURLSchemes [mmcl]`, `CFBundleTypeRole Viewer`. Without it, website "Open in
    Desktop Launcher" links cannot cold-start the app (only reach a running one).
  - (starter branch) The XML comment must not contain `--`: jpackage parses the generated plist
    as XML when packaging an app image.
- `--file-associations` (`deploy/mac/file-associations.properties`): `extension=mmcjson`,
  `mime-type=application/x-mmcjson`, `description=Mica Minecraft Modpack`, icon = staged
  absolute path to `Mica Minecraft Modpack.icns` renamed `mmcjson-document.icns` (jpackage 25
  opens a relative `icon=` against the build CWD and fails with `NoSuchFileException`; the
  rename avoids a basename clash with the app icon in `Contents/Resources`). jpackage turns this
  into `CFBundleDocumentTypes` + a UTI declaration in `DEPLOY_FILE_ASSOCIATIONS`.
- Bundle identifier, category, version keys: jpackage defaults (no `--mac-package-identifier`,
  so it derives the id from the main class; category is jpackage's default). Pin these
  explicitly in Rust.
- macOS runtime registration is a deliberate no-op (Launch Services reads `Info.plist`;
  runtime registration would race its cache).

#### 1.5.3 Signing and notarization

None. The jpackage `--mac-sign --mac-signing-key-user-name ${env.MAC_SIGNING_IDENTITY}` lines
are commented out ("uncomment when an Apple Developer ID certificate is available", cert via
`apple-actions/import-codesign-certs`). `deploy/mac/micaforgelauncher.entitlements`
(`com.apple.security.app-sandbox=false`, `com.apple.security.device.audio-input=true`) is NOT
referenced by the build. The starter branch ad-hoc signs the whole app after inserting the
starter: `codesign --force --deep --sign - <app>` then `codesign --verify --deep --strict`.
Users currently get Gatekeeper's "unidentified developer" flow.

### 1.6 Linux

#### 1.6.1 Artifacts

`ubuntu-latest`, x64 only; build host needs `dpkg fakeroot rpm` (`rpmbuild`). jpackage
`--type deb` and `--type rpm` into `build/packaging/`, uploaded as-is (`*.deb`, `*.rpm`).
jpackage names them `<pkg>_<numeric>-1_amd64.deb` and `<pkg>-<numeric>-1.x86_64.rpm` (release
"1" is jpackage's default `--linux-app-release`). The fat JAR (platform-independent) and the
SBOM (`bom.xml`, `bom.json`) are also uploaded from the Linux job.

#### 1.6.2 Package contents

- `--name "Mica Minecraft Launcher"` (the `.desktop` `Name=`), `--linux-package-name
  mica-minecraft-launcher` (DEV: `mica-minecraft-launcher-dev`) — the dpkg/rpm name and the
  `/opt/<pkg>/` install path. Earlier builds used the lowercase id as `--name` and the menu
  showed "mica-minecraft-launcher"; keep the display name and package id separate.
- `--icon "deploy/linux/Mica Minecraft Launcher.png"` (1024x1024), `--linux-shortcut` (a
  `.desktop` menu entry, installed through `xdg-desktop-menu` in the maintainer scripts).
- `--file-associations` (`deploy/linux/file-associations.properties`): `extension=mmcjson`,
  `mime-type=application/x-mmcjson`, `description=Mica Minecraft Modpack`, icon = staged
  `mmcjson-document.png` (1024x1024, launcher logo with a document badge, generated by
  `tools/icon-gen/MmcjsonIconGen.java`). jpackage writes a MIME XML (`xdg-mime install`), the
  document icon (`xdg-icon-resource`) and `MimeType=application/x-mmcjson;` in the packaged
  `.desktop`. The packaged `.desktop` does NOT declare `x-scheme-handler/mmcl`: on Linux the URL
  scheme exists only through the runtime `.desktop` in 1.7.
- Layout: `/opt/mica-minecraft-launcher/bin/Mica Minecraft Launcher`, `lib/app/*.jar` +
  `lib/app/<name>.cfg`, `lib/runtime/`. Dependencies are auto-derived by jpackage.
- `deploy/linux/Mica Minecraft Launcher.xpm` (2 MB) is not referenced by the build.

### 1.7 Runtime OS registration (`SchemeRegistrar`)

Runs at startup (off the UI thread) and again after each successful game launch
(`refreshRecentPacks`, which just re-runs the whole thing so Linux recents update). Skipped when
dev mode, or when `jpackage.app-path` is unset (raw JAR / IDE: no stable exe). All errors are
caught and logged silently; nothing here ever fails startup. Exe path = `jpackage.app-path` (on
the starter branch: `StarterHandshake.launcherExecutable()`, i.e. the starter, so registrations
go through it).

**Windows** (HKCU only, no admin):

```
HKCU\Software\Classes\mmcl
    (default)      = "URL:Mica Minecraft Launcher Protocol"
    "URL Protocol" = ""
HKCU\Software\Classes\mmcl\shell\open\command
    (default)      = "\"<exe>\" \"%1\""
HKCU\Software\Classes\.mmcjson
    (default)      = "MicaMinecraftLauncher.Modpack"
HKCU\Software\Classes\MicaMinecraftLauncher.Modpack
    (default)      = "Mica Minecraft Modpack"
HKCU\Software\Classes\MicaMinecraftLauncher.Modpack\shell\open\command
    (default)      = "\"<exe>\" \"%1\""
HKCU\Software\Classes\MicaMinecraftLauncher.Modpack\DefaultIcon      (only if the icon staged)
    (default)      = "\"<config>\\icons\\mmcjson-file.ico\",0"
```

The `.ico` is the bundled resource `/mmcjson-file.ico`, copied (overwritten each run) to
`<launcher config folder>/icons/mmcjson-file.ico`. Keys are created step by step
(`shell`, `shell\open`, `shell\open\command`). No `SHChangeNotify` call is made.

**Linux** (user scope, `~/.local/share`):

1. Write `~/.local/share/applications/mica-minecraft-launcher.desktop` (UTF-8, `\n`):

   ```
   [Desktop Entry]
   Type=Application
   Name=<LAUNCHER_APPLICATION_NAME>
   Exec="<exe>" %u
   Terminal=false
   Icon=mica-minecraft-launcher
   MimeType=x-scheme-handler/mmcl;application/x-mmcjson;
   Categories=Game;
   Keywords=minecraft;modpack;forge;launcher;mica;
   NoDisplay=false
   Actions=play-pack-0;play-pack-1;…           (only if there are recent packs)

   [Desktop Action play-pack-0]
   Name=<friendly name, or pack name; CR/LF and chars < 0x20 removed>
   Exec="<exe>" "mmcl://play?name=<percent-encoded pack name>"
   ```

   Up to 5 recent packs (`RecentPacks.getRecent(5)`); packs with blank names are skipped
   without consuming an index; a lookup error drops the whole `Actions=` block. Percent
   encoding keeps `[A-Za-z0-9-_.~]`, encodes every other UTF-8 byte as `%XX` uppercase (spaces
   become `%20`, never `+`). Note: Java encodes per UTF-16 `char`, so a surrogate pair is
   encoded as two `?` bytes — a latent bug; Rust should encode the UTF-8 of the whole string.
2. `installLinuxAppIcon`: unless `~/.local/share/icons/hicolor/256x256/apps/mica-minecraft-launcher.png`
   exists, scale the bundled `micaminecraftlauncher.png` (bilinear) to 256, 128, 64, 48, 32 and
   write `hicolor/<n>x<n>/apps/mica-minecraft-launcher.png`.
3. `registerLinuxMimeType`: unless `~/.local/share/mime/packages/mica-minecraft-launcher.xml`
   exists, write

   ```xml
   <?xml version="1.0" encoding="UTF-8"?>
   <mime-info xmlns="http://www.freedesktop.org/standards/shared-mime-info">
     <mime-type type="application/x-mmcjson">
       <comment>Mica Minecraft Modpack</comment>
       <glob pattern="*.mmcjson"/>
     </mime-type>
   </mime-info>
   ```

   (comment XML-escaped for `& < >`), then run `update-mime-database ~/.local/share/mime`.
4. Run `update-desktop-database ~/.local/share/applications`.

Helper commands run with stdout+stderr merged and drained, 2 s wait, all failures ignored.

Edge cases: the runtime `.desktop` and the package's `.desktop` both exist on deb/rpm installs
(two menu entries are possible; the user-level one wins for `mmcl`). Both flavours write the same
`.desktop` filename, but dev mode skips registration, so only RELEASE writes it.

### 1.8 Paths the installers must respect (shared with other subsystems)

- Data folder `~/.MicaMinecraftLauncher[DEV]/` (`config/`, `logs/startup.log`, `installs/`,
  runtimes, assets). Installers never touch it; uninstall leaves it (the MSI strings promise
  this).
- `startup.log` is the pre-startup log the starters append to.

### 1.9 Unmerged but load-bearing: `tmp/starter-ci` / `fix/inuse-upgrade-wipe`

Incidents and their fixes, which the Rust packaging must not reintroduce:

1. **In-use upgrade wipe (2026-10-13/14).** Running a new bundle while the launcher was open:
   early `RemoveExistingProducts` could not delete the memory-mapped `runtime\lib\modules`,
   Windows Installer queued a path-keyed delete in `PendingFileRenameOperations` (MSI log
   `Info 1903`), the new product wrote a fresh file at the same path, Burn returned 3010
   (reboot required, suppressed), and the next boot deleted the NEW file. The launcher then
   exited silently. Fixes:
   - MSI gate: `util:CloseApplication` for `$(JpAppName).exe` and `$(JpAppName) Core.exe`, both
     `CloseMessage="no" RebootPrompt="no" Property="MMCL_LAUNCHER_RUNNING"`, with
     `WixCloseApplications` sequenced before an error custom action `MmclLauncherRunning`
     (`Before="RemoveExistingProducts"`, condition `MMCL_LAUNCHER_RUNNING AND (NOT Installed OR
     REMOVE~="ALL")`; message `MmclLauncherRunningError`: "[ProductName] is still running.
     Close it, then run the installer again. Nothing has been changed."). A repair is never
     refused (a starter-driven `msiexec /fpums` must work while the starter is open).
   - `PendingRebootDeleteGuard` (runtime, Windows): reads `HKLM\SYSTEM\CurrentControlSet\Control\Session
     Manager` values `PendingFileRenameOperations` and `...2`, replays them, and offers an
     elevated fix that removes exactly the delete entries that would hit install files (after a
     backup). Belongs to the platform subsystem in Rust, but packaging must keep the MSI gate.
   A Rust exe is also locked while running, so the same failure mode applies (smaller blast
   radius: one exe instead of a JVM image, but a deleted exe is still a dead install).
2. **AppCDS exit crash.** HotSpot fast-failed (0xC0000409) on every exit when the CDS archive
   path under Program Files was unwritable; archive moved to `$LOCALAPPDATA\MicaMinecraftLauncher[DEV]\appcds\`.
   Not applicable to Rust (no JVM) — but the lesson "never write runtime files under the install
   dir" applies to any cache the Rust app keeps.
3. **Silent startup failure.** Native starters (`deploy/windows/starter/starter.c`,
   `deploy/unix/starter/starter_unix.c`) become `<name>.exe` / `<name>`; jpackage's launcher
   moves to `<name> Core[.exe]` (its `.cfg` renamed to match). The starter: checks the files the
   JVM needs (and the `.cfg` classpath jars); passes stdin/stdout through (the `--mcp` relay
   speaks JSON-RPC on stdio), keeps an 8 KiB tail of stderr (and of stdout when it has none);
   sets `MMCL_STARTER_STATE` (state file path) and `MMCL_STARTER_PATH` (its own path); the
   launcher writes stage words `main` / `ready` / `handled` (monotonic). After exit: code 0 →
   done; NTSTATUS (`code & 0xC0000000 == 0xC0000000`) → crash dialog; stage < `ready` → failure
   dialog; stage `launching` → "exited before Java started", offers Repair. Windows Repair =
   find the product by `MsiEnumRelatedProducts(UpgradeCode)` whose `InstallLocation` equals the
   starter's folder, then `msiexec /fpums {ProductCode} /passive /norestart` (once per run).
   mac/Linux offer "download again" (`https://github.com/Mica-Technologies/minecraft-launcher/releases/latest`).
   macOS: the starter must BE the launcher process (Launch Services delivers `mmcl://` and Dock
   events to it), so it forks a kqueue watcher and `exec`s Core. Dialogs: TaskDialog (Windows),
   `CFUserNotification` (macOS), GTK 3 via `dlopen` with zenity fallback (Linux). Strings come
   from `starter.*` keys in every `DisplayStrings*.properties`, generated into a C header by
   `deploy/starter/GenerateStarterStrings.java`. Windows starter is built with MSVC (`/O1 /W4
   /WX /utf-8 /MT /GS /guard:cf /sdl`, `/SUBSYSTEM:WINDOWS /DYNAMICBASE /NXCOMPAT`) and carries
   a manifest + `.rc` with version info from `win_version` padded to 4 parts.
4. **JVM output on stdout** corrupted the `--mcp` stdio relay: `-Xlog:disable
   -Xlog:all=warning:stderr:uptime,level,tags -XX:+DisplayVMOutputToStderr`. Rust analogue:
   never print to stdout except the protocol in `--mcp` mode (logging goes to stderr/file).
5. jpackage splits each `--java-options` value on whitespace; `$APPDIR`/`$LOCALAPPDATA` expand at
   run time. Irrelevant for Rust.

### 1.10 JVM options baked into every launcher (for completeness; no Rust equivalent)

`--add-exports=javafx.graphics/com.sun.glass.ui=ALL-UNNAMED`, `--enable-native-access=ALL-UNNAMED`,
`--enable-final-field-mutation=ALL-UNNAMED`, `--add-opens=javafx.web/javafx.scene.web=ALL-UNNAMED`,
`-XX:SharedArchiveFile=$APPDIR\launcher.jsa`, `-XX:+AutoCreateSharedArchive`.

### 1.11 CI workflows

#### `test-build-pr.yml` (on `pull_request`, `permissions: contents: read`)

1. `prepare` (ubuntu): checkout with `fetch-depth: 0`, `fetch-tags: true`; output
   `revision=$(git describe --tags --dirty=.dirty --always)`.
2. `unit-tests` (ubuntu, needs prepare): Zulu 26 `jdk+fx` with Maven cache; `mvn -B -s
   .mvn/ci-settings.xml test -Drevision=…`; always upload `surefire-reports-pr<N>` (14 days,
   warn if none); `jacoco:report`; step summary table of instruction/line coverage from
   `jacoco.csv` (awk); upload `jacoco-coverage-pr<N>`. No coverage threshold, on purpose.
3. `ui-tests` (ubuntu, `continue-on-error: true`, nothing needs it): `xvfb-run -a mvn test`
   with `MMCL_RUN_TESTFX=true`; upload `testfx-reports-pr<N>`.
4. `build-windows` / `build-macos-arm` (`macos-latest`) / `build-macos-intel` (`macos-15-intel`)
   / `build-linux`, each `needs: [prepare, unit-tests]`: `mvn package`; stage + rename as in
   1.4.1 / 1.5.1; upload `windows-x64-pr<N>`, `macos-arm64-pr<N>`, `macos-x64-pr<N>`,
   `linux-x64-pr<N>`, `fat-jar-pr<N>` (14 days, `if-no-files-found: error`). Windows steps use
   `shell: bash` because PowerShell 7 splits `-Drevision=2026.05.13` at the first dot.

#### `build-release.yml` (push to `main`/`master`, or `workflow_dispatch` with input `release`)

`prepare` (contents: write; computes and pushes the tag, 1.2.3) → `build-windows`,
`build-macos-arm`, `build-macos-intel`, `build-linux` (each uploads `windows-x64`,
`macos-arm64`, `macos-x64`, `linux-x64`, `fat-jar`, `sbom`) → `release` (contents: write):
download all artifacts to `artifacts/`; build `checksums.txt` = "## Checksums (SHA-256)" + a
fenced block of `sha256sum` over `*.msi *.exe *.dmg *.pkg *.deb *.rpm *-jar-with-dependencies.jar`
(sorted) and pass it to the body via a heredoc output; then
`softprops/action-gh-release@b4309332981a82ec1c5618f44dd2e27cc8bfbfda` (v3, SHA-pinned) with
`tag_name`, `name: "Release <v>"` or `"Pre-Release <v>"`, body "## Mica Minecraft Launcher <v>"
(+ " (Pre-Release)" and a note that pre-releases may be removed after 90 days), the checksums,
`generate_release_notes: true`, `prerelease` accordingly, files = the same globs. The SBOM is
uploaded as a workflow artifact but not attached to the release.

#### `cleanup-pre-releases.yml`

On `workflow_run` completion of the release workflow: `dev-drprasad/delete-older-releases@dfbe6be2…`
(v0.3.4) with `delete_expired_data: 90`, `delete_prerelease_only: true`, `keep_latest: 3`,
`keep_min_download_counts: 5`.

#### Other

- `.mvn/ci-settings.xml`: mirror `*,!jitpack.io,!repo.u-team.info` → Maven Central
  (dependency-confusion hardening).
- `dependabot.yml`: `github-actions` and `maven`, monthly, max 3 PRs, ignore patch+minor
  (security updates bypass), pin `oshi-core` majors.
- `auto_assign.yml`: reviewers `AngelWingsPanda`, `mica-alex`, assignee author, skip `wip`.
- Manual post-release step (team convention, not in the workflow): patch releases are retitled
  with the last codename + ", Patch N" via `gh release edit`.

### 1.12 Bugs and edge cases (summary)

| # | What | Rust action |
|---|---|---|
| 1 | Same UpgradeCode for DEV and RELEASE: one flavour's installer removes the other. | Distinct codes per flavour. |
| 2 | Burn `RegistrySearch` keyed by UpgradeCode instead of ProductCode: never matches. | Drop, or use `util:ProductSearch UpgradeCode=…`. |
| 3 | Burn Options-page install path is display-only. | Remove the Options button, or pass `InstallFolder` to the MSIs as `INSTALLDIR`. |
| 4 | Same-day bump `26.05.12.1` + bundle revision → 5-part Burn version (invalid). | Bundle version = first 3 parts of win_version + revision (section 3.3). |
| 5 | MSI ignores the 4th version field: `26.05.12.1` equals `26.05.12` for upgrade logic. | Keep `IncludeMaximum`/same-version upgrades allowed. |
| 6 | Pre-release installers carry the base date version (`2026.05.12`) — two pre-releases on a day are indistinguishable by version. | Accepted; put the full version in file names / CFBundleVersion. |
| 7 | PR Windows builds are always version `25.1.2`. | Pass the PR revision-derived win version. |
| 8 | macOS 4-part versions (`2026.05.12.1`) may be refused by jpackage / are invalid for `CFBundleShortVersionString` (3 parts). | Short version = 3 parts; CFBundleVersion = full numeric. |
| 9 | Runtime HKCU registration is never removed on uninstall. | Install-time registration in the MSI as well. |
| 10 | Linux `mmcl://` only via runtime `.desktop`; packaged `.desktop` lacks `x-scheme-handler/mmcl`. | Put it in the packaged `.desktop`. |
| 11 | Percent-encoding per UTF-16 char breaks non-BMP pack names. | Encode UTF-8 bytes. |
| 12 | Entitlements file unused; no `NSMicrophoneUsageDescription`. Voice-chat mods in the child game process are attributed to the launcher by TCC. | Add the usage string; use entitlements when signing with hardened runtime. |
| 13 | `iss.vtl`, `.xpm` are dead files. | Do not port. |

---

## 2. File / class map

| Java side | Rust side | Key items |
|---|---|---|
| `pom.xml` `derive-revision-from-git` | `crates/mmcl-buildinfo/build.rs` | `MMCL_VERSION` env > `git describe` > sentinel; `numeric()`. |
| `pom.xml` `launcher.environment`, `-Pdev` | `MMCL_ENVIRONMENT=DEV` env at build time (or cargo feature `dev-flavor`) read by `mmcl-buildinfo/build.rs` | `Flavor::{Release, Dev}`. |
| `tools/scripts/compute_bundle_revision.ps1` | `xtask/src/version.rs::bundle_revision(now)` | Minutes since 2024-01-01 UTC mod 65536. |
| `build-release.yml` version shell | `xtask/src/version.rs::release_version(...)`, `pre_release_version(...)`, `win_version(...)` (also callable as `cargo xtask version`) | Same algorithms, unit-tested. |
| `maven-assembly-plugin` fat JAR | portable archives (`.zip` Windows, `.tar.gz` Linux/macOS) from `xtask dist` | No platform-independent artifact exists for a native app. |
| jpackage app image | `xtask/src/stage.rs` | Builds the install tree per OS. |
| jpackage MSI + `main.wxs`, `ShortcutPromptDlg.wxs`, `CustomUIStrings_en.wxl`, `overrides.wxi` | `rust/packaging/windows/{product.wxs, ui.wxs, ShortcutPromptDlg.wxs, strings_en-us.wxl}` + `xtask/src/windows.rs` | Hand-authored (no jpackage to generate Files/Shortcuts/UI). |
| `bundle.wxs`, `theme.xml`, `theme.wxl` | `rust/packaging/windows/bundle/{bundle.wxs, theme.xml, theme.wxl}` | Ported to the chosen WiX version's schema. |
| `installer-banner.bmp`, `installer-background.bmp`, `.ico` files | copied to `rust/packaging/windows/` (or referenced from `deploy/`) | Same assets. |
| `deploy/mac/Info.plist` + `file-associations.properties` | `rust/packaging/macos/Info.plist.tmpl` + `xtask/src/macos.rs` | Rendered with explicit keys (no jpackage placeholders). |
| `micaforgelauncher.entitlements` | `rust/packaging/macos/entitlements.plist` | Used when signing. |
| `deploy/linux/*` | `[package.metadata.deb]` / `[package.metadata.generate-rpm]` in `crates/mmcl-app/Cargo.toml`, plus `rust/packaging/linux/{mica-minecraft-launcher.desktop, mica-minecraft-launcher.xml, postinst, postrm}` | Packaged `.desktop` and MIME. |
| `SchemeRegistrar` | `mmcl-platform::registration` (owned by the platform subsystem; spec here) | `register_if_needed`, `refresh_recent_packs`. |
| `SchemeRegistrar.resolveLauncherExePath`, `DesktopShortcutManager.resolveLauncherPath`, `StarterHandshake.launcherExecutable` | `mmcl-buildinfo::launcher_executable()` | `std::env::current_exe()` canonicalised; `None` for `cargo run`. |
| `LauncherConstants` (version / name / dev) | `mmcl-buildinfo` consts | `APP_NAME`, `VERSION`, `FLAVOR`, `DATA_FOLDER_NAME`, `AUMID`. |
| `VersionUtilities` | `mmcl-buildinfo::version::compare` (or `mmcl-core::version`) | Exact port. |
| `UpdateCheckConstants` | `mmcl-buildinfo::release` consts | API URL, keys. |
| `StarterHandshake`, `starter.c`, `starter_unix.c` | not ported as separate binaries (section 3.7); in-process `mmcl-app::startup_guard` | Panic hook, startup log, native error dialog. |
| `PendingRebootDeleteGuard` | `mmcl-platform::windows::reboot_guard` (platform subsystem) | Packaging keeps the MSI running-gate. |
| `.github/workflows/*.yml` | `.github/workflows/rust-ci.yml`, `rust-release.yml` | Section 5. |
| `.mvn/ci-settings.xml` | `cargo --locked`, `cargo-deny` (`deny.toml`), optional `[source]` config | Supply chain. |
| `dependabot.yml` | add `package-ecosystem: cargo`, `directory: /rust` | Same noise budget. |

---

## 3. Rust design

### 3.1 Workspace layout (packaging-related parts)

```
rust/
  Cargo.toml                    workspace; add members xtask, mmcl-buildinfo
  .cargo/config.toml            [alias] xtask = "run -p xtask --"; windows crt-static
  crates/
    mmcl-app/                   the GUI binary (bin name stays mica-minecraft-launcher)
      build.rs                  Windows resources (icon, VERSIONINFO, manifest)
      Cargo.toml                [package.metadata.deb] / [package.metadata.generate-rpm]
    mmcl-buildinfo/             version, flavour, ids, names, launcher_executable()
      build.rs
  xtask/                        `cargo xtask dist <os>` orchestration (not shipped)
  packaging/
    windows/  product.wxs ui.wxs ShortcutPromptDlg.wxs strings_en-us.wxl
              bundle/bundle.wxs bundle/theme.xml bundle/theme.wxl
              app.manifest  *.ico  installer-banner.bmp installer-background.bmp
    macos/    Info.plist.tmpl entitlements.plist AppIcon.icns Modpack.icns dmg/ (background, optional)
    linux/    mica-minecraft-launcher.desktop.tmpl mica-minecraft-launcher.xml icons/ postinst postrm
  docs/spec/packaging-ci.md
```

Outputs mirror Java's `build/packaging/`: `rust/target/dist/packaging/` with `user/` and
`machine/` sub-folders on Windows, then a flat `rust/target/dist/release/` with final names
(section 3.8).

### 3.2 `mmcl-buildinfo` public API

```rust
//! Compile-time identity of this build. Everything other subsystems need to know about
//! "which launcher am I" lives here; nothing here does I/O except launcher_executable().

#[derive(Copy, Clone, Debug, PartialEq, Eq)]
pub enum Flavor { Release, Dev }

/// Full version string: "2026.05.12", "2026.05.12.1", "2026.05.12-pre.1432.UTC+0000+abc1234",
/// "2026.5.12-15-gabc1234.dirty", or "0.0.0-no-git-tag".
pub const VERSION: &str = env!("MMCL_VERSION");
/// VERSION cut at the first '-' then '+'; "0.0.0" when empty.
pub const VERSION_NUMERIC: &str = env!("MMCL_VERSION_NUMERIC");
pub const FLAVOR: Flavor = /* from MMCL_ENVIRONMENT */;
pub const fn is_dev() -> bool;

/// "Mica Minecraft Launcher" or "Mica Minecraft Launcher DEV".
pub const APP_NAME: &str;
/// APP_NAME without spaces: "MicaMinecraftLauncher[DEV]". The paths subsystem builds the data
/// folder from this (the Rust build may add its own suffix until it takes over the Java folder).
pub const APP_NAME_TRIMMED: &str;
pub const VENDOR: &str = "Mica Technologies";
pub const DESCRIPTION: &str = "Mica Minecraft Launcher — a cross-platform Minecraft Forge modpack launcher.";

pub mod ids {
    pub const URL_SCHEME: &str = "mmcl";
    pub const MODPACK_EXTENSION: &str = "mmcjson";          // without the dot
    pub const MODPACK_MIME: &str = "application/x-mmcjson";
    pub const MODPACK_DESCRIPTION: &str = "Mica Minecraft Modpack";
    pub const WIN_URL_DESCRIPTION: &str = "URL:Mica Minecraft Launcher Protocol";
    pub const WIN_MODPACK_PROGID: &str;   // "MicaMinecraftLauncher.Modpack" (+ "DEV" in dev)
    pub const WIN_AUMID: &str;            // section 3.4
    pub const MAC_BUNDLE_ID: &str;        // section 3.4
    pub const LINUX_PACKAGE_NAME: &str;   // "mica-minecraft-launcher" / "-dev"
    pub const LINUX_DESKTOP_ID: &str;     // "<LINUX_PACKAGE_NAME>.desktop"
    pub const WIN_UPGRADE_CODE: &str;     // MSI, per flavour
    pub const WIN_BUNDLE_UPGRADE_CODE: &str;
}

pub mod release {
    pub const LATEST_RELEASE_API: &str =
        "https://api.github.com/repos/Mica-Technologies/minecraft-launcher/releases/latest";
    pub const RELEASES_PAGE: &str = "https://github.com/Mica-Technologies/minecraft-launcher/releases/latest";
    pub const KEY_TAG: &str = "tag_name";
    pub const KEY_URL: &str = "html_url";
    pub const HELP_URL: &str = "https://github.com/Mica-Technologies/minecraft-launcher/issues";
    pub const ABOUT_URL: &str = "https://minecraft.micatechnologies.com/";
}

pub mod version {
    use std::cmp::Ordering;
    /// Exact port of VersionUtilities.compareVersionNumbers.
    pub fn compare(a: &str, b: &str) -> Ordering;
    /// "0.0.0*", "*no-git-tag*", "*-dev*": skip the update check.
    pub fn is_unstamped(v: &str) -> bool;
    pub fn numeric(v: &str) -> String;  // shared with build.rs via include!
}

#[derive(Copy, Clone, Debug, PartialEq, Eq)]
pub enum InstallKind { WindowsPerUser, WindowsPerMachine, MacAppBundle, LinuxSystemPackage, Portable, DevRun }

/// The installed executable to register with the OS and to relaunch. None for `cargo run`
/// (exe under a `target/` dir) — mirrors "jpackage.app-path unset".
pub fn launcher_executable() -> Option<std::path::PathBuf>;
pub fn install_kind() -> InstallKind;
```

`build.rs` algorithm:

```text
version = env MMCL_VERSION (non-empty)
       ?: `git describe --tags --dirty=.dirty --always` run in CARGO_MANIFEST_DIR (5 s cap)
       ?: "0.0.0-no-git-tag"
numeric = env MMCL_VERSION_NUMERIC ?: numeric(version)
flavor  = env MMCL_ENVIRONMENT == "DEV" (case-insensitive) → Dev, else Release;
          a `cargo run` without MMCL_ENVIRONMENT is Release-flavoured but install_kind() == DevRun,
          and the Java "IDE run is dev" rule maps to: is_dev() || install_kind() == DevRun
          for anything that must not touch the OS (scheme registration).
emit cargo:rustc-env=MMCL_VERSION / MMCL_VERSION_NUMERIC / MMCL_FLAVOR
emit cargo:rerun-if-env-changed=MMCL_VERSION, MMCL_VERSION_NUMERIC, MMCL_ENVIRONMENT
emit cargo:rerun-if-changed=<git dir>/HEAD and <git dir>/refs/tags (resolve worktree .git files)
```

Do not put the date version in `Cargo.toml`: `2026.05.12` is not valid SemVer (leading zeros).
Packagers that default to the Cargo version must be given the version explicitly.

### 3.3 `xtask` (dist orchestration)

```rust
// xtask/src/main.rs
// cargo xtask version  --mode {local|pr|release|pre} [--sha S] [--existing-tags FILE]
// cargo xtask dist windows --version V [--win-version W] [--flavor release|dev] [--skip-installers]
// cargo xtask dist macos   --version V --target {aarch64|x86_64}-apple-darwin [--sign IDENTITY] [--notarize]
// cargo xtask dist linux   --version V --target x86_64-unknown-linux-gnu [--formats deb,rpm,tar,appimage]
// cargo xtask checksums <dir>   → checksums.md in the release-body format

pub mod version {
    pub fn base_date(now_utc: OffsetDateTime) -> String;                 // "2026.05.12"
    pub fn release_version(base: &str, existing_tags: &[String]) -> String;
    pub fn pre_release_version(base: &str, now_utc: OffsetDateTime, short_sha: &str) -> String;
    pub fn win_version(version: &str) -> String;                          // drop 2 chars, cut -,+
    pub fn numeric(version: &str) -> String;
    pub fn bundle_revision(now_utc: OffsetDateTime) -> u16;              // minutes since 2024-01-01 % 65536
    /// Burn needs exactly a.b.c.d: first three parts of win_version + revision.
    pub fn bundle_version(win_version: &str, revision: u16) -> String;
    /// MSI ProductVersion: major<=255, minor<=255, build<=65535; at most 4 parts.
    pub fn validate_msi_version(v: &str) -> Result<(), String>;
    /// CFBundleShortVersionString: first 3 numeric parts of numeric().
    pub fn mac_short_version(numeric: &str) -> String;
}
```

Rules ported exactly from 1.2.3; plus the fixes from 1.12 (#4, #7, #8). The release workflow
may keep its shell or call `cargo xtask version`; either way the unit tests in section 6 pin
the behaviour.

`dist` steps are plain `std::process::Command` calls to the external tools in section 4, each
logged with its full command line, failing on non-zero exit (the Java build relied on
`--verbose` to surface WiX errors; always pipe tool output through).

### 3.4 Identifiers (new, so Rust installs side by side with Java)

The Rust launcher keeps its own data folder for now, so its installers must not upgrade or
remove the Java launcher. Re-using Java's UpgradeCodes would make `RemoveExistingProducts`
uninstall the Java launcher. Proposed (freshly generated; record them once and never change):

| Id | RELEASE | DEV |
|---|---|---|
| MSI UpgradeCode (both scopes share it, like Java) | `71cbbacc-9bc5-4759-a454-1cb7c7ff2296` | `f23786db-dbfd-414d-8e15-a690df0d4f9c` |
| Burn bundle UpgradeCode | `4667401f-350d-42ef-ba29-974794f9c279` | `4854aa25-3b2f-462c-bf7c-4bc145b891ac` |
| Windows AUMID | `com.micatechnologies.minecraft.launcher.rs` | `com.micatechnologies.minecraft.launcher.rsDEV` |
| macOS bundle id | `com.micatechnologies.minecraft.launcher.rs` | `com.micatechnologies.minecraft.launcher.rs.dev` |
| Linux package | `mica-minecraft-launcher` (conflicts with Java's deb/rpm!) → while side by side use `mica-minecraft-launcher-rs` | `mica-minecraft-launcher-rs-dev` |
| ProgID | `MicaMinecraftLauncher.Modpack` (shared on purpose: the last launcher to run owns `.mmcjson`) | not registered in dev |

At switch-over (Rust replaces Java) swap to Java's codes so the Rust MSI upgrades the Java
install in place: MSI `3699f9a3-0d10-4228-ab2f-b86ca9a500ac`, bundle
`b3a4f7c2-1d9e-4f88-9b06-3a2c1c7e9501`, AUMID `com.micatechnologies.minecraft.launcher.LauncherCore`,
Linux `mica-minecraft-launcher` (and `Replaces`/`Obsoletes` the Java package). Keep all of these
in one table in `mmcl-buildinfo` so the swap is a one-line change per id.

Display names: "Mica Minecraft Launcher" / "Mica Minecraft Launcher DEV" (same as Java). While
side by side both launchers would show the same name in ARP and menus; see open questions.

### 3.5 Windows packaging

#### 3.5.1 The executable

- Build `--release --locked --target x86_64-pc-windows-msvc`. Set `-C target-feature=+crt-static`
  for this target in `rust/.cargo/config.toml` so the exe does not need the VC++ redistributable
  (the jpackage runtime shipped its own CRT).
- `#![cfg_attr(windows, windows_subsystem = "windows")]` in `main.rs`. For `-s` (server), `--cli`
  (TUI) and `--mcp`: call `AttachConsole(ATTACH_PARENT_PROCESS)` early when started from a
  console; pipes (MCP clients) already work because inherited handles are valid. Never write
  anything but protocol bytes to stdout in `--mcp` mode (lesson 1.9 #4).
- `crates/mmcl-app/build.rs` (Windows only) embeds: the icon `Mica Minecraft Launcher.ico` (id 1,
  so `<exe>,0` works as a DefaultIcon and the `.mmcjson` icon can be resource id 2:
  `"<exe>",1` replaces the Java "extract ico to the config folder" step); `VERSIONINFO` with
  `FileVersion`/`ProductVersion` = win_version padded to 4 parts, `ProductName`, `CompanyName`
  "Mica Technologies", `FileDescription`, `LegalCopyright`, `OriginalFilename`; and an app
  manifest: `dpiAwareness PerMonitorV2`, `longPathAware true`, `activeCodePage UTF-8`,
  supportedOS Windows 10/11, Common Controls v6 (needed by TaskDialog), `asInvoker`.
- Installed file name: `Mica Minecraft Launcher.exe` (DEV: `Mica Minecraft Launcher DEV.exe`),
  renamed by `xtask` from the cargo bin name. Keeps parity with shortcuts, pins, the MSI running
  gate, and Java's process name.

#### 3.5.2 MSI (`product.wxs`, one source, built twice)

Preprocessor variables from `xtask`: `Scope` (`perUser`|`perMachine`), `AppName`, `ExeName`,
`Version` (win_version), `UpgradeCode`, `Description`, `SourceDir` (staged image),
`BannerBmp`, `DialogBmp`, `LicenseRtf`, `Aumid`, `ProgId`.

Must contain (all of this was generated by jpackage before; now hand-authored):

- `Package` (WiX v4+ merges Product/Package): `Scope`, `Manufacturer`, `UpgradeCode`,
  `Version`, `Compressed=yes`, x64, `Language=1033`, one embedded cab. `ProductCode` auto per
  build (WiX v4+ default when omitted; matches Java's `Id="*"`).
- Upgrades: `<MajorUpgrade AllowDowngrades="yes" AllowSameVersionUpgrades="yes"
  Schedule="afterInstallInitialize" />` (or keep Java's explicit `Upgrade` +
  early `RemoveExistingProducts Before="CostInitialize"`; either gives upgrade + downgrade +
  same-version reinstall without uninstalling). If `afterInstallInitialize` is chosen, re-check
  that the running gate below still fires before any removal.
- Running gate (from 1.9 #1, mandatory): `util:CloseApplication Target="[ExeName]"
  CloseMessage="no" RebootPrompt="no" Property="MMCL_LAUNCHER_RUNNING"`, `WixCloseApplications`
  scheduled before the error action `MmclLauncherRunning` (string `MmclLauncherRunningError`),
  which is scheduled before `RemoveExistingProducts`, condition `MMCL_LAUNCHER_RUNNING AND (NOT
  Installed OR REMOVE~="ALL")` (never block a repair). With a single Rust exe there is only
  one target (no "Core").
- Directories: perUser `LocalAppDataFolder\Programs\[AppName]`, perMachine
  `ProgramFiles64Folder\[AppName]`; `INSTALLDIR` editable via the dir chooser. Per-user
  components need an HKCU registry `KeyPath` and `RemoveFolder` for every created folder
  (ICE38/ICE64).
- Files: the exe (and any non-embedded assets the app needs: fonts, locale files, if GPUI assets
  are not compiled in; LICENSE). Prefer `include_bytes!`/`rust-embed` for assets so the MSI is
  one file plus LICENSE.
- Shortcuts: Start menu folder "Mica Minecraft Launcher" (Java's fixed `--win-menu-group`),
  Start-menu shortcut (condition `JP_INSTALL_STARTMENU_SHORTCUT`), Desktop shortcut (condition
  `JP_INSTALL_DESKTOP_SHORTCUT`), each with `<ShortcutProperty Key="System.AppUserModel.ID"
  Value="[Aumid]"/>` so toasts and taskbar grouping match the AUMID the process sets.
- Install-time registration (fixes 1.12 #9; the runtime registration in 3.6 stays for portable
  installs and to self-heal): per-user under `HKCU\Software\Classes`, per-machine under
  `HKLM\Software\Classes`, the same keys as section 1.7, with the exe path `[INSTALLDIR][ExeName]`
  and `DefaultIcon "[INSTALLDIR][ExeName]",1`. WiX `<ProgId>`/`<Extension>` can generate the
  file association; the URL protocol needs explicit `RegistryValue`s.
- ARP: `ARPHELPLINK`, `ARPURLINFOABOUT`, `ARPURLUPDATEINFO`, `ARPCOMMENTS`, `ARPCONTACT`,
  `ARPPRODUCTICON`, `ARPINSTALLLOCATION` (WiX sets `ARPINSTALLLOCATION` via a SetProperty).
- UI: `WixUI_InstallDir` customised: Welcome → License (`LICENSE` converted to RTF by
  `xtask`) → InstallDir → `ShortcutPromptDlg` (port the dialog with check boxes at Y=100/140,
  defaulting both properties to 1) → VerifyReady → Progress → Exit. Bitmaps
  `WixUIBannerBmp`/`WixUIDialogBmp` = the two `.bmp` files (493x58, 493x312). Strings:
  `strings_en-us.wxl` = `CustomUIStrings_en.wxl` ported verbatim plus `MmclLauncherRunningError`.

Output: `target/dist/packaging/user/<AppName>-<winver>.msi` and `…/machine/…msi`.

#### 3.5.3 Burn bundle

Port `bundle.wxs` with: `Version = bundle_version(win_version, bundle_revision)`, the new
bundle UpgradeCode, `RelatedBundle Action="Upgrade"` (WiX v4+: `<RelatedBundle Code=…
Action="upgrade"/>`), variable `MMCL_INSTALL_FOR_ALL_USERS` (numeric 0, not persisted,
overridable), the two `MsiPackage`s with the same `InstallCondition`s and `ForcePerMachine`,
`DisplayInternalUI=no`, `Visible=no`. Fix 1.12 #2 (drop or replace the searches) and #3
(either remove the Options page or pass `INSTALLDIR="[InstallFolder]"` via `<MsiProperty>` and
set `InstallFolder` from the scope when the check box changes). The theme must be rewritten for
the WiX version chosen (thmutil schema changed in v4); keep the layout decisions: 500x420,
`WS_EX_APPWINDOW`, no logo image, scope check box above the EULA check box, the theme strings
verbatim.

Output: `target/dist/packaging/<AppName> Setup.exe`.

#### 3.5.4 Signing (optional, off by default like Java)

If a certificate is provided (`WINDOWS_CERT_PFX`, `WINDOWS_CERT_PASSWORD` secrets, or Azure
Trusted Signing): `signtool sign /fd sha256 /tr <timestamp> /td sha256` the exe before it goes
into the MSIs, then the MSIs, then the bundle using the Burn engine detach/reattach dance
(`wix burn detach` → sign engine → `wix burn reattach` → sign bundle). Without a cert, skip
silently.

### 3.6 Runtime registration in Rust (`mmcl-platform::registration`)

```rust
pub struct RecentPackAction { pub pack_name: String, pub display_name: Option<String> }

/// Idempotent; call off the UI thread at startup. No-op when buildinfo::is_dev(),
/// when launcher_executable() is None, and on macOS. Never returns an error to the caller:
/// failures are logged (warning, silent) and swallowed.
pub fn register_if_needed(recent: &[RecentPackAction]);
/// Called after a successful game launch; same as register_if_needed (Linux rewrites Actions=).
pub fn refresh_recent_packs(recent: &[RecentPackAction]);

// pure helpers (unit-tested)
pub fn linux_desktop_entry(app_name: &str, exe: &Path, recent: &[RecentPackAction]) -> String;
pub fn desktop_entry_escape(s: &str) -> String;           // drop chars < 0x20 (incl. CR/LF)
pub fn percent_encode_pack_name(s: &str) -> String;      // keep A-Za-z0-9-_.~ ; UTF-8 bytes %XX upper
pub fn linux_mime_xml(description: &str) -> String;
pub fn windows_registry_plan(exe: &Path) -> Vec<RegWrite>; // the key/value list of section 1.7
```

Behaviour = section 1.7 with these changes: Windows `DefaultIcon = "\"<exe>\",1"` (icon is a
resource in the exe; no `.ico` extraction); after writing, call `SHChangeNotify(SHCNE_ASSOCCHANGED,
SHCNF_IDLIST, null, null)`; Linux icon scaling via the `image` crate (bilinear =
`FilterType::Triangle`); helper commands with a 2 s timeout via `wait-timeout` (kill on timeout,
drain output); percent-encode UTF-8 properly (1.12 #11). Exe quoting in `Exec=` follows the
Desktop Entry spec (inside double quotes escape `"`, `` ` ``, `$`, `\`), which Java did not do —
paths with those characters were broken. Windows: use the `windows` or `winreg` crate under
`HKCU\Software\Classes`.

### 3.7 Startup failure reporting (replacing the native starters)

The starters existed because a JVM that cannot start prints only to an invisible console. A
Rust binary has no runtime to go missing, so a separate starter executable is not needed for
parity. Keep the guarantees in-process (`mmcl-app::startup_guard`):

- First thing in `main`: append "starting <version>" to `<data>/logs/startup.log` (same format
  as Java's pre-startup log).
- `std::panic::set_hook`: write the panic and backtrace to `startup.log` and the main log, then
  show a native error dialog (`rfd::MessageDialog` or the `windows` crate's `TaskDialogIndirect`
  on Windows) with "Open logs" (`opener`) and, on Windows packaged installs, "Repair" =
  `msiexec /fpums {ProductCode} /passive /norestart` where the ProductCode is found with
  `MsiEnumRelatedProductsW(UpgradeCode)` + `MsiGetProductInfoW(INSTALLLOCATION)` equal to the
  exe's folder (port of `starter.c` `findProduct`/`repairInstall`); macOS/Linux: "Download
  again" opens `release::RELEASES_PAGE`.
- GPU / window-creation failure (GPUI cannot open a window: no Vulkan on Linux, no Metal,
  D3D11 failure): catch the error from `Application::run`/`open_window` and show the same
  dialog instead of exiting silently.
- Native crashes (access violation in a driver) cannot be caught in-process. If that turns out
  to matter, add an optional supervisor mode (`--supervised`: the exe re-execs itself as a child
  and watches the exit code, NTSTATUS check `code & 0xC0000000 == 0xC0000000`); on macOS it must
  not re-exec (Launch Services delivers URLs to the first process). Not required for v1.
- Strings via the localization subsystem (the `starter.*` keys already exist in every bundle on
  the starter branch).

### 3.8 macOS packaging

1. Build `aarch64-apple-darwin` on `macos-latest`, and `x86_64-apple-darwin` either on
   `macos-15-intel` or cross-compiled from the arm64 runner (`rustup target add
   x86_64-apple-darwin`; GPUI's Metal shaders compile with `xcrun metal`, which needs full
   Xcode; GitHub runners have it). Keep separate arm64 and intel artifacts (parity); a
   universal `lipo` build is an optional extra.
2. Assemble `Mica Minecraft Launcher.app`:
   ```
   Contents/Info.plist
   Contents/PkgInfo                     "APPL????"
   Contents/MacOS/Mica Minecraft Launcher   (the binary, renamed)
   Contents/Resources/AppIcon.icns          (deploy/mac/Mica Minecraft Launcher.icns)
   Contents/Resources/Modpack.icns          (deploy/mac/Mica Minecraft Modpack.icns)
   Contents/Resources/LICENSE
   ```
3. `Info.plist` (rendered by `xtask`, validated with `plutil -lint`): `CFBundleExecutable`,
   `CFBundleIdentifier` (3.4), `CFBundleName` / `CFBundleDisplayName` = AppName,
   `CFBundleShortVersionString = mac_short_version(numeric)`, `CFBundleVersion = numeric`,
   `CFBundleIconFile AppIcon`, `CFBundlePackageType APPL`, `CFBundleInfoDictionaryVersion 6.0`,
   `CFBundleDevelopmentRegion en`, `CFBundleAllowMixedLocalizations true`,
   `LSApplicationCategoryType public.app-category.games`, `LSMinimumSystemVersion` = the GPUI
   floor (Zed targets macOS 10.15.7; confirm for gpui 0.2.2), `NSHighResolutionCapable true`,
   `NSSupportsAutomaticGraphicsSwitching true`, `NSHumanReadableCopyright`,
   `NSMicrophoneUsageDescription` (1.12 #12), `CFBundleURLTypes` exactly as in 1.5.2,
   `CFBundleDocumentTypes` = one entry `CFBundleTypeName "Mica Minecraft Modpack"`,
   `CFBundleTypeRole Viewer`, `LSHandlerRank Owner`, `CFBundleTypeIconFile Modpack`,
   `LSItemContentTypes [<bundle id>.mmcjson]`, and `UTExportedTypeDeclarations` =
   `UTTypeIdentifier <bundle id>.mmcjson`, `UTTypeDescription "Mica Minecraft Modpack"`,
   `UTTypeConformsTo [public.json, public.data]`, `UTTypeIconFile Modpack`,
   `UTTypeTagSpecification { public.filename-extension [mmcjson], public.mime-type
   application/x-mmcjson }`.
   The app receives URLs and files through GPUI's `on_open_urls` (Apple Events); the URI
   subsystem handles them.
4. Sign: ad-hoc by default (`codesign --force --deep --sign - <app>`; arm64 binaries must be
   signed at least ad hoc). With `MAC_SIGNING_IDENTITY` (Developer ID Application): `codesign
   --force --options runtime --timestamp --entitlements packaging/macos/entitlements.plist
   --sign "$IDENTITY" <app>`; entitlements: `com.apple.security.device.audio-input`
   (app-sandbox false is the default and can be omitted). Verify with `codesign --verify --deep
   --strict`.
5. DMG: staging dir with the `.app` and an `Applications -> /Applications` symlink; `hdiutil
   create -volname "<AppName>" -srcfolder <staging> -ov -format UDZO
   "<AppName>-<numeric>.dmg"` (optional: `create-dmg` for a background and icon layout like
   jpackage's). Sign the DMG when a Developer ID is present.
6. PKG: `pkgbuild --component <app> --install-location /Applications --identifier <bundle id>
   --version <numeric> component.pkg`, then `productbuild --distribution distribution.xml
   --resources <dir with LICENSE> --package-path . "<AppName>-<numeric>.pkg"` (distribution
   shows the license; jpackage's PKG had one). With a Developer ID Installer identity add
   `--sign`.
7. Notarize (only when Apple credentials exist): `xcrun notarytool submit <dmg|pkg>
   --apple-id … --team-id … --password … --wait` (or an API key), then `xcrun stapler staple`.
8. Rename to `…-arm64.{dmg,pkg}` / `…-intel.{dmg,pkg}` (parity with 1.5.1).

### 3.9 Linux packaging

1. Build `x86_64-unknown-linux-gnu`. glibc floor: a binary built on `ubuntu-latest` (24.04,
   glibc 2.39) does not start on Debian 12 (2.36) or RHEL 9 (2.34). Build on `ubuntu-22.04`
   (2.35) or with `cargo zigbuild --target x86_64-unknown-linux-gnu.2.31` to cover current
   LTS distros. Build deps for GPUI (verify against gpui 0.2.2's docs): `libxkbcommon-dev
   libxkbcommon-x11-dev libwayland-dev libvulkan-dev libx11-xcb-dev libxcb1-dev
   libfontconfig-dev libfreetype-dev libasound2-dev pkg-config` (TLS: use rustls to avoid
   `libssl` runtime deps).
2. Install layout (both formats):
   ```
   /opt/<pkg>/bin/<pkg>                         the binary (lowercase, no spaces)
   /usr/bin/<pkg> -> /opt/<pkg>/bin/<pkg>       symlink
   /usr/share/applications/<pkg>.desktop
   /usr/share/icons/hicolor/<n>x<n>/apps/<pkg>.png          n = 16 32 48 64 128 256 512
   /usr/share/icons/hicolor/<n>x<n>/mimetypes/application-x-mmcjson.png
   /usr/share/mime/packages/<pkg>.xml           the MIME XML of section 1.7
   /usr/share/licenses/<pkg>/LICENSE (rpm) or /usr/share/doc/<pkg>/copyright (deb)
   ```
   `/opt/<pkg>/` keeps parity with jpackage's install root (anything that looks for the Java
   install path keeps working); the `/usr/bin` symlink is new and makes `-s`/`--cli` usable
   from a shell.
3. Packaged `.desktop`: `Name=<AppName>`, `Exec=/opt/<pkg>/bin/<pkg> %u`, `Icon=<pkg>`,
   `Type=Application`, `Terminal=false`, `Categories=Game;`, `Keywords=…` (as 1.7),
   `MimeType=x-scheme-handler/mmcl;application/x-mmcjson;` (fixes 1.12 #10),
   `StartupWMClass=` = the app id GPUI sets on its windows (must match, or the dock shows a
   generic icon; coordinate with the GUI subsystem). Runtime registration (3.6) still writes the
   user-level file with the recents `Actions=`; it should use the same desktop id
   `<pkg>.desktop` so it overrides the system one instead of adding a second menu entry
   (Java used `mica-minecraft-launcher.desktop` for both, but jpackage named its system file
   differently, so two entries could appear).
4. deb via `cargo-deb`: `cargo deb -p mmcl-app --no-build --target … --deb-version
   <numeric>-1 --output target/dist/packaging/` with `[package.metadata.deb]`: `name`,
   `maintainer`, `copyright`, `license-file`, `section = "games"`, `priority = "optional"`,
   `depends = "$auto"` (dpkg-shlibdeps), `assets` per the layout, `maintainer-scripts =
   "packaging/linux/"` (postinst/postrm run `update-desktop-database -q`, `update-mime-database
   /usr/share/mime`, `gtk-update-icon-cache -q /usr/share/icons/hicolor`, all `|| true`; deb
   triggers usually cover these but the scripts are harmless), `conflicts`/`replaces` for the
   dev/release pair only if they install the same files (they should not).
5. rpm via `cargo-generate-rpm`: `cargo generate-rpm -p crates/mmcl-app --target … -s
   'version = "<numeric>"' -s 'release = "1"' -o target/dist/packaging/`, metadata
   `[package.metadata.generate-rpm]` with the same assets, `auto-req = "auto"`, `post_install_script`
   / `post_uninstall_script` as above, `license = "GPL-3.0-only"`.
6. Names: `<pkg>_<numeric>-1_amd64.deb`, `<pkg>-<numeric>-1.x86_64.rpm` (parity).
7. Optional extras (not in the Java set): `<pkg>-<numeric>-x86_64.AppImage` via `appimagetool`
   (or `linuxdeploy`), and `<pkg>-<numeric>-linux-x86_64.tar.gz` (portable; runtime registration
   makes it self-integrate, like the Java portable JAR). Add `ubuntu-24.04-arm` / `aarch64` later
   if wanted; GPUI supports it.

### 3.10 Portable archives (replacement for the fat JAR)

The release lists one platform-independent JAR. Rust has none; publish instead
`Mica Minecraft Launcher-<winver>-portable-x64.zip` (the exe + LICENSE), the Linux `.tar.gz`,
and no macOS portable (the DMG is already portable). Portable runs are `InstallKind::Portable`:
runtime registration on, no MSI repair button.

---

## 4. External tools and crates

### 4.1 Packaging toolchain (recommendation)

| Format | Tool | Why |
|---|---|---|
| MSI (x2) + Burn `Setup.exe` | **WiX Toolset v5** CLI (`dotnet tool install --global wix --version 5.0.x`, extensions `WixToolset.UI.wixext`, `WixToolset.Util.wixext`, `WixToolset.Bal.wixext` pinned to the same version), driven by `xtask` | Burn + a custom WixStdBA theme + two scopes + CloseApplication gate need hand-written WiX. `cargo-wix` targets WiX 3, generates a single MSI, and has no Burn support; `cargo-packager`'s WiX output (WiX 3 template) has no Burn bundle or dual scope. WiX 3.14 would let the existing `.wxs` port nearly verbatim but is end-of-life and not guaranteed on new runner images. Note: WiX v6+ ships under the Open Source Maintenance Fee terms — check before moving past v5. |
| `.app` | `xtask` (plain file layout + plist rendering) | Trivial; `cargo-bundle` exists but is lightly maintained and does not cover UTI exports well. |
| DMG | `hdiutil` (built in) or `create-dmg` (brew) | Same as jpackage underneath. |
| PKG | `pkgbuild` + `productbuild` (built in) | No Rust crate does PKG; `cargo-packager` lacks it. |
| Signing / notarization (mac) | `codesign`, `xcrun notarytool`, `xcrun stapler`; optionally `apple-codesign` (`rcodesign`, pure Rust, can sign/notarize from Linux) | |
| DEB | **`cargo-deb`** | Mature, `$auto` dependencies, maintainer scripts, assets. |
| RPM | **`cargo-generate-rpm`** | Pure Rust, no `rpmbuild` needed, auto-req. |
| AppImage (optional) | `appimagetool` / `linuxdeploy` | |
| glibc floor | `cargo-zigbuild` (optional) | |
| SBOM | **`cargo-cyclonedx`** (CycloneDX JSON/XML, parity with `bom.json`/`bom.xml`); optionally `cargo-auditable` to embed the dependency list in the binary | |
| Supply chain | `cargo --locked`, **`cargo-deny`** (advisories, licenses: GPL-3.0 compatible only, sources: crates.io only + allow-listed git), `cargo-audit` optional | Replaces `.mvn/ci-settings.xml` mirror hardening. |
| Coverage | `cargo-llvm-cov` (report only, no threshold) | Parity with JaCoCo policy. |

`cargo-packager` (not Tauri, though from the same ecosystem) was considered as a one-stop tool:
it covers `.app`, DMG, NSIS, WiX-3 MSI, deb, AppImage, pacman, but not RPM, PKG, Burn bundles or
dual-scope MSIs, so it would cover only part of the set and still need the custom pieces. Not
recommended as the backbone; acceptable as a quick local `.app`/DMG path.

### 4.2 Rust crates

| Crate | Use |
|---|---|
| `winresource` (or `embed-resource`) | Embed icon, VERSIONINFO, manifest into the Windows exe (`build.rs`). |
| `windows` (windows-rs) | `SetCurrentProcessExplicitAppUserModelID`, `AttachConsole`, `SHChangeNotify`, `TaskDialogIndirect`, `MsiEnumRelatedProductsW` / `MsiGetProductInfoW` (feature `Win32_System_ApplicationInstallationAndServicing`), registry. |
| `winreg` | Simpler HKCU writes (alternative to `windows` registry APIs). |
| `image` | Linux icon scaling (runtime registration). |
| `rfd` | Cross-platform native message dialogs for startup failures (or `windows` TaskDialog + GTK). |
| `opener` / `open` | Open the logs folder and the releases page. |
| `wait-timeout` | 2 s cap on `update-desktop-database` / `update-mime-database`. |
| `time` (or `chrono`) | Version dates, bundle revision (xtask only). |
| `plist` | Render / validate `Info.plist` in xtask. |
| `quick-xml` | Optional: generate WiX fragments (file lists) or validate `.wxs` in xtask. |
| `xshell` or `duct` | Command running in xtask. |
| `clap` | xtask CLI. |
| `sha2` + `hex` | `cargo xtask checksums` (or keep `sha256sum` in the workflow). |
| `dirs` | Home / local-data paths (shared with the paths subsystem). |

### 4.3 No good Rust equivalent

- Burn bootstrapper and MSI authoring: no Rust tool; WiX stays an external .NET tool.
- macOS PKG and DMG: Apple CLI tools only (or third-party `create-dmg`); notarization needs
  Apple's service.
- jpackage's automatic per-file component generation: replaced by hand-written WiX (simpler
  for one exe; if assets are not embedded, generate the file list with `wix heat`-like logic in
  xtask — v4+ has no `heat` for arbitrary dirs beyond `<Files>` globbing, which is fine).
- Windows Installer custom actions: none needed; avoid writing any.

---

## 5. GitHub Actions

Constraints: the Java workflows stay untouched on this branch (they trigger on `main`); Rust
workflows must not publish anything the Java launcher's update check would see
(`/releases/latest` returns the newest non-prerelease release of the repo). Rust releases are
therefore pre-releases (or drafts) with a tag namespace, e.g. `rs-2026.05.12[.N]`, until the
switch-over (open question). Keep `actions/checkout@v7`, `actions/upload-artifact@v7`,
`actions/download-artifact@v8` and the SHA-pinned third-party actions used today. Never more
than the four platform jobs in parallel (same as today).

### 5.1 `rust-ci.yml` (PRs and pushes touching `rust/**`)

```yaml
name: Rust CI
on:
  pull_request:
    paths: ['rust/**', '.github/workflows/rust-*.yml']
  push:
    branches: [rewrite/rust-gpui]
    paths: ['rust/**', '.github/workflows/rust-*.yml']
permissions:
  contents: read
defaults:
  run:
    shell: bash
    working-directory: rust
env:
  CARGO_TERM_COLOR: always
  CARGO_INCREMENTAL: 0
jobs:
  prepare:
    runs-on: ubuntu-latest
    outputs:
      version: ${{ steps.v.outputs.version }}
      win_version: ${{ steps.v.outputs.win_version }}
    steps:
      - uses: actions/checkout@v7
        with: { fetch-depth: 0, fetch-tags: true }
      - id: v
        working-directory: .
        run: |
          V=$(git describe --tags --dirty=.dirty --always)
          echo "version=$V" >> "$GITHUB_OUTPUT"
          # PR MSIs get today's date-derived version instead of a fixed 25.1.2 (fix 1.12 #7)
          echo "win_version=$(date -u +'%y.%m.%d')" >> "$GITHUB_OUTPUT"

  checks:                      # fast gate, like the Java unit-tests job
    needs: prepare
    strategy:
      fail-fast: false
      matrix: { os: [ubuntu-22.04, windows-latest, macos-latest] }
    runs-on: ${{ matrix.os }}
    steps:
      - uses: actions/checkout@v7
      - uses: dtolnay/rust-toolchain@<sha> # stable, components rustfmt, clippy
      - uses: Swatinem/rust-cache@<sha>
        with: { workspaces: rust }
      - if: runner.os == 'Linux'
        run: sudo apt-get update && sudo apt-get install -y libxkbcommon-dev libxkbcommon-x11-dev libwayland-dev libvulkan-dev libx11-xcb-dev libxcb1-dev libfontconfig-dev libfreetype-dev libasound2-dev pkg-config
      - run: cargo fmt --all --check
        if: runner.os == 'Linux'
      - run: cargo clippy --workspace --all-targets --locked -- -D warnings
      - run: cargo test --workspace --locked
        env: { MMCL_VERSION: '${{ needs.prepare.outputs.version }}' }
      - if: runner.os == 'Linux'
        run: cargo install cargo-deny --locked && cargo deny check

  coverage:                    # report only, never fails the build (JaCoCo policy)
    needs: prepare
    runs-on: ubuntu-22.04
    continue-on-error: true
    steps:
      - uses: actions/checkout@v7
      - uses: dtolnay/rust-toolchain@<sha>
        with: { components: llvm-tools-preview }
      - uses: taiki-e/install-action@<sha>
        with: { tool: cargo-llvm-cov }
      - run: sudo apt-get update && sudo apt-get install -y <gpui deps as above>
      - run: cargo llvm-cov --workspace --locked --summary-only | tee coverage.txt
      - run: { echo '### Coverage'; echo '```'; cat coverage.txt; echo '```'; } >> "$GITHUB_STEP_SUMMARY"

  ui-tests:                    # opt-in GPUI visual/snapshot tests, non-blocking (TestFX parity)
    needs: prepare
    runs-on: ubuntu-22.04
    continue-on-error: true
    env: { MMCL_RUN_UI_TESTS: 'true' }
    steps: [checkout, toolchain, deps + xvfb + mesa-vulkan-drivers, 'xvfb-run -a cargo test --workspace --locked', upload snapshots]

  package:
    needs: [prepare, checks]
    strategy:
      fail-fast: false
      matrix:
        include:
          - { name: windows-x64,  os: windows-latest, target: x86_64-pc-windows-msvc }
          - { name: macos-arm64,  os: macos-latest,   target: aarch64-apple-darwin }
          - { name: macos-x64,    os: macos-15-intel, target: x86_64-apple-darwin }   # or cross from macos-latest
          - { name: linux-x64,    os: ubuntu-22.04,   target: x86_64-unknown-linux-gnu }
    runs-on: ${{ matrix.os }}
    env:
      MMCL_VERSION: ${{ needs.prepare.outputs.version }}
    steps:
      - uses: actions/checkout@v7
      - uses: dtolnay/rust-toolchain@<sha>
        with: { targets: '${{ matrix.target }}' }
      - uses: Swatinem/rust-cache@<sha>
        with: { workspaces: rust, key: '${{ matrix.target }}' }
      - if: runner.os == 'Windows'
        run: |
          dotnet tool install --global wix --version 5.0.2
          wix extension add -g WixToolset.UI.wixext/5.0.2 WixToolset.Util.wixext/5.0.2 WixToolset.Bal.wixext/5.0.2
      - if: runner.os == 'Linux'
        run: |
          sudo apt-get update && sudo apt-get install -y <gpui deps> dpkg fakeroot
          cargo install cargo-deb cargo-generate-rpm --locked
      - run: cargo build --release --locked -p mmcl-app --target ${{ matrix.target }}
      - run: >
          cargo xtask dist ${{ runner.os == 'Windows' && 'windows' || runner.os == 'macOS' && 'macos' || 'linux' }}
          --version "$MMCL_VERSION" --win-version "${{ needs.prepare.outputs.win_version }}"
          --target ${{ matrix.target }} --out target/dist/release
      - uses: actions/upload-artifact@v7
        with:
          name: rust-${{ matrix.name }}-pr${{ github.event.pull_request.number || 'push' }}
          path: rust/target/dist/release/*
          retention-days: 14
          if-no-files-found: error
```

(`<sha>` = pin each third-party action to a commit SHA, as the existing workflows do.)

### 5.2 `rust-release.yml`

Same shape as `build-release.yml`:

1. `prepare` (`contents: write`): the same date / `.N` / pre-release / `win_version` algorithm
   (or `cargo xtask version`), with the tag namespace prefix while side by side; `git tag` +
   `git push origin <tag>`.
2. `package` matrix as above with `MMCL_VERSION=<version>` and `--win-version <win_version>`;
   Linux job also runs `cargo cyclonedx --format json` and `--format xml` and uploads `sbom`.
   Optional signing steps gated on secrets (`if: env.WINDOWS_CERT_PFX != ''`,
   `if: env.MAC_SIGNING_IDENTITY != ''`), with `apple-actions/import-codesign-certs@<sha>` and
   `notarytool` when Apple secrets exist.
3. `release` (`contents: write`): download artifacts, checksums over `*.msi *.exe *.dmg *.pkg
   *.deb *.rpm *.zip *.tar.gz *.AppImage`, `softprops/action-gh-release@b4309332…` with the same
   body templates ("## Mica Minecraft Launcher <v>" + checksums; pre-release note about 90-day
   removal), `prerelease: true` while side by side.

`cleanup-pre-releases.yml` already deletes old pre-releases of the whole repo; Rust
pre-releases would fall under the same 90-day / keep-3 policy (note: `keep_latest: 3` counts
across both launchers' pre-releases). Either accept that or give Rust its own cleanup with
`delete_tag_pattern: rs-`.

Dependabot: add

```yaml
  - package-ecosystem: cargo
    directory: /rust
    schedule: { interval: monthly }
    open-pull-requests-limit: 3
    ignore:
      - dependency-name: "*"
        update-types: ["version-update:semver-patch", "version-update:semver-minor"]
      - dependency-name: "gpui"        # pin; GPUI breaks APIs between minors
        update-types: ["version-update:semver-minor"]
```

### 5.3 Final artifact set (Rust)

| OS / arch | Files on the release page |
|---|---|
| Windows x64 | `Mica Minecraft Launcher Setup.exe`, `Mica Minecraft Launcher-<winver>-user.msi`, `Mica Minecraft Launcher-<winver>-system.msi`, `Mica Minecraft Launcher-<winver>-portable-x64.zip` |
| macOS arm64 | `Mica Minecraft Launcher-<numeric>-arm64.dmg`, `…-arm64.pkg` |
| macOS x64 | `Mica Minecraft Launcher-<numeric>-intel.dmg`, `…-intel.pkg` |
| Linux x64 | `<pkg>_<numeric>-1_amd64.deb`, `<pkg>-<numeric>-1.x86_64.rpm`, optional `<pkg>-<numeric>-x86_64.AppImage`, `<pkg>-<numeric>-linux-x86_64.tar.gz` |
| any | `bom.json`, `bom.xml` (workflow artifact; optionally attached) |

---

## 6. Tests worth porting / adding

Port (exact cases):

- `VersionUtilitiesTest` → `mmcl-buildinfo/src/version.rs` tests:
  - `("1.2.3","1.2.3+abc")=0`, `("1.2.3","1.2.3-rc1")=0`, `("2026.1.0-15-gabc1234.dirty","2026.1.0")=0`
  - `("14.23.5.2855_230729ER","14.23.5.2855_230729ER")=0`, `("14.23.5.2855_230729ER","14.23.5.2855_231005AB")=0`, `("14.23.5.2854","14.23.5.2855_230729ER")=-1`
  - `("1.2rc1.3","1.2.3")=0`, `("1.alpha.3","1.0.3")=0`
  - `("1.2.3","1.2.3")=0`, `("1.2.3","1.2.4")=-1`, `("1.2.4","1.2.3")=1`, `("1.9.0","1.10.0")=-1`, `("2.0","1.99.99")=1`
  - `("1.0","1.0.0")=0`, `("1.0","1.0.1")=-1`, `("1.0.1","1.0")=1`
  - `("","0")=0`, `("","")=0`, `("_230729ER","0")=0`
  - add: overflowing segment (`"99999999999"`) parses as 0, never panics; `2026.05.12.1 > 2026.05.12`; pre-release equals its base date.
- `StarterHandshakeTest` (only if the supervisor mode is built): stage words are `main`/`ready`/`handled`, monotonic, no file created when the variable is unset; executable resolution prefers the starter, falls back to Core, finds the starter beside a `"<name> Core[.exe]"`.
- `LauncherUriHandlerQueryAndSchemeTest` belongs to the URI subsystem but should include a
  round trip: `percent_encode_pack_name(x)` → `mmcl://play?name=…` → parsed name == x, for ASCII,
  spaces, `&`, `=`, `%`, CJK and emoji names.
- `DesktopShortcutManager*Test` (quoting, sanitising, command building) belong to the platform
  subsystem; the quoting rules must agree with 3.6's `Exec=` quoting.

Add (pure, fast, no tools needed):

- `xtask::version`: `release_version` with no tags → base; with base tag → `.1`; with `.1`,
  `.3`, `.x`, `.2a` → `.4`; `pre_release_version` format exactly
  `2026.05.12-pre.1432.UTC+0000+abc1234`; `win_version("2026.05.12.1") = "26.05.12.1"`,
  `win_version("2026.05.12-pre.1432.UTC+0000+abc") = "26.05.12"`; `numeric` of each describe
  form; `bundle_revision` at 2024-01-01T00:00Z = 0, +1 min = 1, +65536 min = 0;
  `bundle_version("26.05.12.1", 7) = "26.05.12.7"` (3 parts + revision);
  `validate_msi_version` rejects major > 255 and 5 parts; `mac_short_version`.
- `registration`: golden `.desktop` text with 0, 1 and 6 recent packs (only 5 listed, blank
  names skipped without gaps in ids), control-character stripping, MIME XML escaping, the
  Windows registry plan (exact keys and values for an exe path with spaces).
- `macos` plist rendering: parse the rendered plist with the `plist` crate and assert
  `CFBundleURLSchemes == ["mmcl"]`, the UTI tag spec, versions.
- WiX: a CI-only smoke test that builds both MSIs and the bundle, then `msiexec /a` (administrative
  extract) the per-user MSI into a temp dir and asserts the exe is there; run the `wix`
  validation (ICE) step. Optionally install/uninstall the per-user MSI silently
  (`msiexec /i … /qn`, then `/x`) on the runner and assert the HKCU keys appear and vanish.
- Linux: `dpkg-deb -c` / `rpm -qlp` listings contain the layout of 3.9; `desktop-file-validate`
  on the packaged `.desktop`; `xmllint` on the MIME XML.

---

## 7. Open questions

1. Release channel while side by side: Rust builds as pre-releases with an `rs-` tag prefix in
   the same repo (keeps Java's `/releases/latest` update check clean), or a separate repo /
   draft releases only? This also decides whether the Rust app's own update check should look
   at `rs-` pre-releases.
2. Side-by-side identity: the proposed new UpgradeCodes / bundle id / AUMID / `-rs` Linux
   package (3.4) keep the Java install untouched. Should the display name also differ (for
   example "Mica Minecraft Launcher (Rust)") so the two are distinguishable in ARP, Start menu
   and Dock?
3. WiX version: v5 (rewrite the theme to the v4+ schema, maintained) vs staying on 3.14 (reuse
   files almost verbatim, end-of-life)?
4. Keep the two-MSI + Burn design, or, now that the WiX source is ours, ship one dual-purpose
   MSI (`MSIINSTALLPERUSER` / `ALLUSERS=2`) and drop Burn? Parity says keep three files.
5. Code signing: are a Windows certificate (or Azure Trusted Signing) and an Apple Developer ID
   (with notarization) available, or stay unsigned + ad-hoc like Java?
6. Starter: is the in-process guard (3.7) enough, or is the supervisor mode required from day
   one to catch native crashes?
7. Linux: build on `ubuntu-22.04` or with `cargo-zigbuild` for a lower glibc floor; ship the
   optional AppImage, tarball and an aarch64 build?
8. Per-user install folder: confirm where the Java per-user MSI really installs
   (`%LOCALAPPDATA%\Programs\…` per the docs vs jpackage's `LocalAppDataFolder\…`); the Rust MSI
   proposes `%LOCALAPPDATA%\Programs\<AppName>`.
