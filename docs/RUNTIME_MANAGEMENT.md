# Runtime Management System

Technical documentation for the multi-version Java runtime management system.

## Overview

The runtime management system handles downloading, verifying, and caching multiple Java
runtime versions required by different Minecraft versions. Runtimes are sourced from
Mojang's official distribution for Java 16+ and Bell-SW Liberica for legacy Java 8.

All runtime code lives in:
- `src/main/java/com/micatechnologies/minecraft/launcher/files/RuntimeManager.java`
- `src/main/java/com/micatechnologies/minecraft/launcher/consts/RuntimeConstants.java`

## Architecture

```
                         ┌─────────────────────────┐
                         │     GameModPack          │
                         │  startGame() determines  │
                         │  required Java version   │
                         └────────────┬─────────────┘
                                      │
                         ┌────────────v─────────────┐
                         │    RuntimeManager         │
                         │                           │
                         │  verifyRuntime(component) │
                         │  getJavaPath(component)   │
                         │  getJavaVersion(component)│
                         └─────┬────────────┬────────┘
                               │            │
              ┌────────────────v──┐   ┌─────v──────────────────┐
              │  Mojang Runtimes  │   │  Bell-SW Liberica      │
              │  (Java 16+)      │   │  (Java 8 / jre-legacy) │
              │                  │   │                         │
              │  launchermeta    │   │  api.bell-sw.com        │
              │  .mojang.com    │   │  /v1/liberica/releases  │
              └──────────────────┘   └─────────────────────────┘
```

## Runtime Component Mapping

Each Minecraft version declares a required Java runtime component in its client.json
manifest. The system maps these components to download sources:

| Component | Java Version | Source | Notes |
|---|---|---|---|
| `jre-legacy` | 8 | Bell-SW Liberica 8u392 | Special handling; Mojang's 8u51 is deprecated |
| `java-runtime-alpha` | 16 | Mojang | MC 1.17 |
| `java-runtime-gamma` | 17 | Mojang | MC 1.18-1.20.4 |
| `java-runtime-delta` | 21 | Mojang | MC 1.20.5+ |
| `java-runtime-epsilon` | 25+ | Mojang | Future versions |

Backward-compatible version-based methods (`verifyJre(int majorVersion)`) map major
version numbers to components automatically.

## Directory Layout

```
launcher_runtime_folder/
├── mojang-runtime-index.json          ← Cached Mojang runtime index
├── jre-legacy/
│   ├── .version                       ← Version marker for change detection
│   ├── jre-legacy.api.json            ← Cached Bell-SW API response
│   └── [extracted JRE files]
├── java-runtime-alpha/
│   ├── .version
│   ├── runtime-manifest.json          ← Cached Mojang manifest
│   └── [extracted JRE files]
├── java-runtime-gamma/
│   └── ...
├── java-runtime-delta/
│   └── ...
└── java-runtime-epsilon/
    └── ...
```

## Download & Verification Flow

### Standard Mojang Runtimes (Java 16+)

1. Check `.version` file against remote manifest version
2. Fetch Mojang runtime index from `MOJANG_RUNTIME_INDEX_URL`
3. Extract platform-specific component using `RuntimeConstants.getMojangPlatformKey()`
4. Download component manifest (lists every file with SHA1 hashes)
5. Process manifest entries:
   - `"directory"` type: create directory
   - `"file"` type: download with SHA1 verification
   - `"link"` type: skip on Windows (symlinks require elevation)
6. Set executable bit on files flagged `executable: true`
7. Write version marker to `.version` file
8. Resolve and cache Java executable path

### Legacy JRE 8 (Bell-SW Liberica)

1. Query Bell-SW API for Liberica JRE 8u392 archive
2. API parameters: `os` (windows/macos/linux), `arch` (x86/arm), `bitness=64`
3. Download archive (.tar.gz on Unix, .zip on Windows)
4. Extract to `jre-legacy/` subfolder
5. Write version marker and cache Java executable path

## Platform Detection

`RuntimeConstants.getMojangPlatformKey()` returns the platform identifier used to index
into Mojang's runtime manifest:

| Platform | Key |
|---|---|
| Windows x64 | `windows-x64` |
| Windows ARM64 | `windows-arm64` |
| macOS x64 | `mac-os` |
| macOS ARM64 (Apple Silicon) | `mac-os-arm64` |
| Linux x64 | `linux` |
| Linux x86 | `linux-i386` |

## Java Executable Resolution

`RuntimeConstants.getJavaExecPathForOs()` returns the relative path to the `java`
executable within each runtime's directory:

| OS | Path |
|---|---|
| Windows | `bin\java.exe` |
| macOS | `jre.bundle/Contents/Home/bin/java` |
| Linux | `bin/java` |

## Key Classes

| Class | Purpose |
|---|---|
| `RuntimeManager` | Downloads, verifies, and caches Java runtimes; resolves executable paths |
| `RuntimeConstants` | Platform detection, URL templates, path constants |
| `MCLauncherRuntimeGui` | UI for viewing installed runtimes with size info and delete functionality |
| `runtimeManagementGUI.fxml` | FXML layout for the runtime management screen |

## Key Method Signatures

### RuntimeManager

```java
// Primary (component-based)
static void verifyRuntime( String component, boolean showProgress )
static void verifyRuntime( String component, boolean showProgress, RuntimeProgressCallback cb )
static String getJavaPath( String component )
static String getJavaVersion( String component )
static void clearRuntime( String component ) throws IOException
static List< Map< String, String > > getInstalledRuntimes()

// Version-based (backward compatible)
static void verifyJre( int majorVersion, boolean showProgress )
static String getJavaPath( int majorVersion )

// Legacy Java 8 shortcuts
static void verifyJre8()
static String getJre8Path()
```

## Caching & Fallback

- Verified paths are cached in a `ConcurrentHashMap< String, String >` to avoid
  redundant filesystem checks within a session
- Runtime index JSON is cached in memory after first fetch
- API responses are cached to disk for offline operation
- If a runtime installation fails, the system falls back to an existing installation
- If no local runtime is available, falls back to system `java` on PATH

## Error Handling

- Failed downloads do not remove a working existing installation
- Version file (`.version`) acts as an integrity marker -- if it doesn't match the
  remote manifest, the runtime is re-downloaded
- Progress is reported via three channels: GUI progress window, callback interface,
  or console log
