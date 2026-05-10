/*
 * Copyright (c) 2026 Mica Technologies
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License,
 * or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.micatechnologies.minecraft.launcher.game.crash;

import com.micatechnologies.minecraft.launcher.config.ConfigManager;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.game.modpack.GameModPack;
import com.micatechnologies.minecraft.launcher.gui.MCLauncherGuiController;
import com.micatechnologies.minecraft.launcher.utilities.SystemUtilities;
import oshi.SystemInfo;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pattern-based diagnosis for Minecraft crash reports. Takes raw crash-report text +
 * the originating {@link GameModPack}, returns a {@link CrashDiagnosis} explaining the
 * cause in plain English with zero or more actionable suggestions.
 *
 * <p>Each detector is a self-contained method that pattern-matches the raw text and
 * returns either a {@link CrashDiagnosis} or {@code null} (no match). {@link #analyze}
 * runs detectors in priority order — earlier entries take precedence so the most
 * specific patterns win over generic ones (e.g. OOM is checked before generic
 * "Caused by" because OOMs often *also* surface as exceptions in higher frames).</p>
 *
 * <p>Adding a new detector is intentionally just "write a method that returns
 * {@code CrashDiagnosis} or {@code null}, slot it into {@link #DETECTORS}". The
 * detectors are pure functions of (crash text, pack) so they're trivial to test
 * in isolation if we ever stand up a unit test suite.</p>
 */
public final class CrashReportAnalyzer
{
    /** Functional interface: a single detector. Returns {@code null} if its pattern
     *  doesn't match the crash text. */
    @FunctionalInterface
    private interface Detector
    {
        CrashDiagnosis tryAnalyze( String crashText, GameModPack pack );
    }

    /** Detectors in priority order. Earlier wins. Most-specific patterns first; the
     *  fall-through "Caused by" detector would otherwise swallow specific failures. */
    private static final List< Detector > DETECTORS = List.of(
            CrashReportAnalyzer::detectOutOfMemory,
            CrashReportAnalyzer::detectJavaVersion,
            CrashReportAnalyzer::detectDiskFull,
            CrashReportAnalyzer::detectNativeLibrary,
            CrashReportAnalyzer::detectMixinConflict,
            // Forge-specific dependency string must run BEFORE the generic mod-loading
            // detector — it extracts mod IDs + versions from structured Forge messages,
            // and the generic catch-all would otherwise win on the same crash with a
            // less-specific diagnosis.
            CrashReportAnalyzer::detectForgeDependencyMismatch,
            CrashReportAnalyzer::detectModLoading,
            CrashReportAnalyzer::detectGpuOpenGL,
            CrashReportAnalyzer::detectWorldCorruption,
            CrashReportAnalyzer::detectAuth
    );

    private CrashReportAnalyzer() { /* static-only */ }

    /**
     * Runs the crash-report text through every detector in priority order and returns
     * the first match. Returns {@link CrashDiagnosis#unknown(int)} if nothing matched
     * — the GUI uses that as a generic "we couldn't pinpoint the cause" panel pointing
     * the user at the raw report below.
     *
     * @param crashText raw crash-report text (may be {@code null} or empty — handled
     *                  gracefully as UNKNOWN)
     * @param pack      the modpack that crashed; used to populate pack-specific suggestions
     *                  (e.g. "Open SkyFactory 4's mods folder"). May be {@code null}.
     * @param exitCode  the OS exit code from the game process — surfaced in the fallback
     *                  message when no detector matches
     */
    public static CrashDiagnosis analyze( String crashText, GameModPack pack, int exitCode )
    {
        if ( crashText == null || crashText.isBlank() ) {
            return CrashDiagnosis.unknown( exitCode );
        }
        for ( Detector detector : DETECTORS ) {
            try {
                CrashDiagnosis diagnosis = detector.tryAnalyze( crashText, pack );
                if ( diagnosis != null ) {
                    return diagnosis;
                }
            }
            catch ( Exception | Error e ) {
                // A buggy detector shouldn't take down the crash-display path itself —
                // log and move on to the next.
                Logger.logWarningSilent( "Crash-report detector threw: " + e.getMessage() );
            }
        }
        return CrashDiagnosis.unknown( exitCode );
    }

    // =========================================================================================
    //  Detectors — ordered from most-specific to most-generic
    // =========================================================================================

    private static final Pattern OOM_PATTERN = Pattern.compile(
            "java\\.lang\\.OutOfMemoryError(?::\\s*([^\\r\\n]+))?" );

    private static CrashDiagnosis detectOutOfMemory( String text, GameModPack pack )
    {
        Matcher m = OOM_PATTERN.matcher( text );
        if ( !m.find() ) {
            return null;
        }
        String detail = m.group( 1 );  // e.g. "Java heap space", "GC overhead limit exceeded"
        long currentMaxMb = ConfigManager.getMaxRam();
        long currentMaxGb = Math.max( 1, currentMaxMb / 1024 );
        long systemGb     = detectSystemRamGb();

        // Suggest +2 GB, capped at ~75% of total system RAM (leave headroom for the OS).
        long cap = systemGb > 0 ? Math.max( 4, ( systemGb * 3 ) / 4 ) : Long.MAX_VALUE;
        long suggestedGb = Math.min( currentMaxGb + 2, cap );

        List< Suggestion > suggestions = new ArrayList<>();
        if ( suggestedGb > currentMaxGb ) {
            final long suggested = suggestedGb;
            suggestions.add( Suggestion.primary(
                    "Increase max RAM to " + suggestedGb + " GB",
                    () -> {
                        ConfigManager.setMaxRam( suggested * 1024L );
                        Logger.logStd( "Crash-diagnosis: bumped max RAM to " + suggested + " GB on user request." );
                    } ) );
        }
        suggestions.add( Suggestion.of( "Open RAM settings", () -> openSettings() ) );

        String summary = "Minecraft ran out of memory"
                + ( detail != null && !detail.isBlank() ? " (" + detail.trim() + ")" : "" )
                + ". Your launcher's max RAM is currently " + currentMaxGb + " GB; this pack needs more.";
        if ( systemGb > 0 ) {
            summary += " Your system has about " + systemGb + " GB total.";
        }

        return new CrashDiagnosis(
                CrashDiagnosis.Category.OUT_OF_MEMORY,
                CrashDiagnosis.Severity.CRITICAL,
                "Out of memory",
                summary,
                suggestions );
    }

    private static final Pattern JAVA_VERSION_PATTERN = Pattern.compile(
            "(java\\.lang\\.UnsupportedClassVersionError"
                    + "|has been compiled by a more recent version of the Java Runtime"
                    + "|class file version (\\d+)\\.\\d+)" );

    private static CrashDiagnosis detectJavaVersion( String text, GameModPack pack )
    {
        if ( !JAVA_VERSION_PATTERN.matcher( text ).find() ) {
            return null;
        }
        // Try to extract the required class-file major version → translate to Java release.
        Matcher m = Pattern.compile( "class file version (\\d+)\\.\\d+" ).matcher( text );
        String requiredJava = m.find() ? javaReleaseForClassFileVersion( m.group( 1 ) ) : null;

        String summary = "A mod or library in this pack was compiled for a newer Java version "
                + "than the runtime currently being used"
                + ( requiredJava != null ? " (this mod targets Java " + requiredJava + ")" : "" )
                + ". The launcher's runtime management screen can install or pick a different Java."
                + ( pack != null ? " The required Java version is normally declared by the modpack manifest." : "" );

        return new CrashDiagnosis(
                CrashDiagnosis.Category.JAVA_VERSION,
                CrashDiagnosis.Severity.CRITICAL,
                "Java version mismatch",
                summary,
                List.of(
                        Suggestion.primary( "Open Runtime Management", () -> openRuntime() ),
                        Suggestion.of( "Open Settings", () -> openSettings() )
                ) );
    }

    private static final Pattern DISK_FULL_PATTERN = Pattern.compile(
            "(No space left on device|There is not enough space on the disk|Disk full)" );

    private static CrashDiagnosis detectDiskFull( String text, GameModPack pack )
    {
        if ( !DISK_FULL_PATTERN.matcher( text ).find() ) {
            return null;
        }
        return new CrashDiagnosis(
                CrashDiagnosis.Category.DISK_IO,
                CrashDiagnosis.Severity.CRITICAL,
                "Disk full",
                "The system ran out of disk space mid-launch. Free some space — modpack worlds, "
                        + "old screenshots, and crash reports under the pack's install folder are usually "
                        + "the biggest offenders — and try again.",
                openFolderSuggestions( pack ) );
    }

    private static final Pattern NATIVE_LIB_PATTERN = Pattern.compile(
            "java\\.lang\\.UnsatisfiedLinkError|Could not (?:locate|load) the native library" );

    private static CrashDiagnosis detectNativeLibrary( String text, GameModPack pack )
    {
        if ( !NATIVE_LIB_PATTERN.matcher( text ).find() ) {
            return null;
        }
        return new CrashDiagnosis(
                CrashDiagnosis.Category.NATIVE_LIBRARY,
                CrashDiagnosis.Severity.CRITICAL,
                "Couldn't load a native library",
                "The JVM couldn't load one of Minecraft's native libraries (typically LWJGL or a "
                        + "JNI binding). The usual causes are an antivirus quarantining the file, a "
                        + "corrupted install, or — on Linux — a missing system library. Reinstalling "
                        + "the modpack usually fixes it.",
                openFolderSuggestions( pack ) );
    }

    private static final Pattern MIXIN_PATTERN = Pattern.compile(
            "org\\.spongepowered\\.asm\\.mixin\\.transformer\\.throwables\\.Mixin\\w+"
                    + "|MixinApplyError|MixinTransformerError" );

    /** Try to pull "mods.<modid>" out of common mixin error messages so we can name the offender. */
    private static final Pattern MIXIN_OWNER_PATTERN = Pattern.compile(
            "mixins?\\.([a-z0-9_\\-]+)\\.json|@Mixin\\(([^\\)]+)\\)" );

    private static CrashDiagnosis detectMixinConflict( String text, GameModPack pack )
    {
        if ( !MIXIN_PATTERN.matcher( text ).find() ) {
            return null;
        }
        Matcher owner = MIXIN_OWNER_PATTERN.matcher( text );
        String offendingMod = owner.find() ? ( owner.group( 1 ) != null ? owner.group( 1 ) : owner.group( 2 ) ) : null;

        String summary = "A mod's mixin patch failed to apply"
                + ( offendingMod != null ? " — mixin from \"" + offendingMod + "\" appears to be the culprit" : "" )
                + ". This usually means two mods are trying to patch the same class, or one of them "
                + "doesn't support this Minecraft version yet.";

        return new CrashDiagnosis(
                CrashDiagnosis.Category.MIXIN_CONFLICT,
                CrashDiagnosis.Severity.CRITICAL,
                "Mod mixin conflict",
                summary,
                openFolderSuggestions( pack ) );
    }

    /** Forge dependency-mismatch messages take a few shapes across Forge versions but always
     *  carry the requiring mod's display name, the required dependency's ID, and a version
     *  range. Capture groups (in order): requiring mod display name, required mod ID,
     *  required version range. The pattern is lenient on whitespace + intermediate punctuation
     *  to absorb minor Forge format drift. */
    private static final Pattern FORGE_DEPENDENCY_PATTERN = Pattern.compile(
            "Mod\\s+'([^']+)'\\s*\\([^)]+\\)\\s*requires\\s+([\\w\\-]+)\\s+([\\d\\.\\[\\]\\(\\),~^]+)"
                    + "|Mod\\s+([\\w\\-]+)\\s+requires\\s+([\\w\\-]+)\\s+version\\s+([\\d\\.\\[\\]\\(\\),~^]+)" );

    /** Forge-specific dependency-mismatch detector. Runs before the generic MOD_LOADING
     *  detector so we can surface concrete mod IDs + version ranges instead of the
     *  catch-all "a mod loaded wrong" message. */
    private static CrashDiagnosis detectForgeDependencyMismatch( String text, GameModPack pack )
    {
        Matcher m = FORGE_DEPENDENCY_PATTERN.matcher( text );
        if ( !m.find() ) {
            return null;
        }
        // Either alternation branch fires; pick whichever groups matched.
        String requiringMod = m.group( 1 ) != null ? m.group( 1 ) : m.group( 4 );
        String requiredMod  = m.group( 2 ) != null ? m.group( 2 ) : m.group( 5 );
        String requiredVer  = m.group( 3 ) != null ? m.group( 3 ) : m.group( 6 );

        String summary = "Mod \"" + requiringMod + "\" needs " + requiredMod + " "
                + requiredVer + " installed, but a compatible version isn't loaded. "
                + "Either add the missing dependency, update the existing one to a matching "
                + "version, or remove the mod that requires it.";

        return new CrashDiagnosis(
                CrashDiagnosis.Category.MOD_LOADING,
                CrashDiagnosis.Severity.CRITICAL,
                "Missing or mismatched mod dependency",
                summary,
                openFolderSuggestions( pack ) );
    }

    private static final Pattern MOD_LOADING_PATTERN = Pattern.compile(
            "net\\.minecraftforge\\.fml\\.(?:loading\\.moddiscovery\\.[\\w$]+|ModLoadingException)"
                    + "|requires Minecraft Forge"
                    + "|missing dependencies"
                    + "|java\\.lang\\.NoClassDefFoundError"
                    + "|java\\.lang\\.ClassNotFoundException" );

    private static CrashDiagnosis detectModLoading( String text, GameModPack pack )
    {
        if ( !MOD_LOADING_PATTERN.matcher( text ).find() ) {
            return null;
        }
        // Try to pull the missing class name out of NoClassDefFoundError / ClassNotFoundException.
        Matcher missing = Pattern.compile(
                "(?:NoClassDefFoundError|ClassNotFoundException):\\s*([\\w/.$]+)" ).matcher( text );
        String missingClass = missing.find() ? missing.group( 1 ).replace( '/', '.' ) : null;

        String summary;
        if ( missingClass != null ) {
            summary = "A mod tried to use class \"" + missingClass + "\" but it wasn't found. "
                    + "That usually means a required dependency mod is missing or a mod from a different "
                    + "Minecraft / Forge version snuck in.";
        }
        else {
            summary = "Forge couldn't load this pack's mods cleanly. The most common causes are a "
                    + "missing dependency, a mod that targets a different Minecraft / Forge version, "
                    + "or two mods that don't get along.";
        }

        return new CrashDiagnosis(
                CrashDiagnosis.Category.MOD_LOADING,
                CrashDiagnosis.Severity.CRITICAL,
                "Mod loading failed",
                summary,
                openFolderSuggestions( pack ) );
    }

    private static final Pattern GPU_PATTERN = Pattern.compile(
            "(?:Pixel format not accelerated"
                    + "|Failed to create OpenGL"
                    + "|GLFW.*(?:error|fail)"
                    + "|org\\.lwjgl\\.opengl\\.GLException"
                    + "|Could not (?:create|initialize) display"
                    + "|GL_OUT_OF_MEMORY)" );

    private static CrashDiagnosis detectGpuOpenGL( String text, GameModPack pack )
    {
        if ( !GPU_PATTERN.matcher( text ).find() ) {
            return null;
        }
        String summary = "Minecraft couldn't initialize the graphics layer. The usual causes are "
                + "out-of-date GPU drivers, the wrong GPU being picked on a laptop with both integrated "
                + "and discrete graphics, or remote-desktop sessions where OpenGL isn't accelerated.";

        return new CrashDiagnosis(
                CrashDiagnosis.Category.GPU,
                CrashDiagnosis.Severity.CRITICAL,
                "Graphics initialization failed",
                summary,
                List.of() );
    }

    private static final Pattern WORLD_PATTERN = Pattern.compile(
            "RegionFileException"
                    + "|ChunkLoadException"
                    + "|Failed to load chunk"
                    + "|net\\.minecraft\\.nbt\\.NbtException"
                    + "|Invalid level format"
                    + "|Could not load level" );

    private static CrashDiagnosis detectWorldCorruption( String text, GameModPack pack )
    {
        if ( !WORLD_PATTERN.matcher( text ).find() ) {
            return null;
        }
        return new CrashDiagnosis(
                CrashDiagnosis.Category.WORLD_CORRUPTION,
                CrashDiagnosis.Severity.CRITICAL,
                "World data couldn't be read",
                "Minecraft choked on a save file — a chunk, region, or level.dat is unreadable. "
                        + "Common causes: an unexpected reboot during a previous save, two mods writing "
                        + "conflicting NBT, or a mod removed between sessions whose data is still on disk. "
                        + "Try loading a different world, or restore from a recent backup.",
                openFolderSuggestions( pack ) );
    }

    private static final Pattern AUTH_PATTERN = Pattern.compile(
            "Could not authenticate against|Bad login|Invalid session ID|Authentication error" );

    private static CrashDiagnosis detectAuth( String text, GameModPack pack )
    {
        if ( !AUTH_PATTERN.matcher( text ).find() ) {
            return null;
        }
        return new CrashDiagnosis(
                CrashDiagnosis.Category.AUTH,
                CrashDiagnosis.Severity.CRITICAL,
                "Minecraft couldn't authenticate",
                "The game rejected the session token the launcher provided. Usually the saved login "
                        + "has expired; signing out of the launcher and signing back in clears it up.",
                List.of(
                        Suggestion.primary( "Open Settings", () -> openSettings() )
                ) );
    }

    // =========================================================================================
    //  Helpers
    // =========================================================================================

    /** Translates a Java class-file major version (e.g. "61") into a human-readable Java
     *  release ("17"). Returns the raw number as a string if the mapping is unknown. */
    private static String javaReleaseForClassFileVersion( String major )
    {
        try {
            int v = Integer.parseInt( major );
            // Class-file major version = Java release + 44. (52=Java 8, 61=Java 17, etc.)
            if ( v >= 49 ) {
                return String.valueOf( v - 44 );
            }
        }
        catch ( NumberFormatException ignored ) { /* fall through */ }
        return major;
    }

    /** System RAM in GiB via oshi-core (already on classpath). Returns 0 on failure so callers
     *  can decide whether to surface the figure in the suggestion text. */
    private static long detectSystemRamGb()
    {
        try {
            long bytes = new SystemInfo().getHardware().getMemory().getTotal();
            return bytes / ( 1024L * 1024L * 1024L );
        }
        catch ( Exception | Error e ) {
            return 0;
        }
    }

    /** Suggestions common to "the user should poke around in the pack's mod folder" diagnoses
     *  (missing dependency, mixin conflict, native library, world corruption, disk full). */
    private static List< Suggestion > openFolderSuggestions( GameModPack pack )
    {
        if ( pack == null ) {
            return List.of();
        }
        List< Suggestion > out = new ArrayList<>();
        out.add( Suggestion.primary( "Open Mods Folder", () -> openPackSubfolder( pack, "mods" ) ) );
        out.add( Suggestion.of( "Open Install Folder", () -> openPackSubfolder( pack, "" ) ) );
        return out;
    }

    /** Opens the given subfolder of the pack's install dir in the OS file manager. Creates the
     *  folder if it doesn't exist (consistent with the in-window context-menu equivalent). */
    private static void openPackSubfolder( GameModPack pack, String subfolder )
    {
        SystemUtilities.spawnNewTask( () -> {
            try {
                String path = pack.getPackRootFolder();
                if ( !subfolder.isEmpty() ) {
                    path += File.separator + subfolder;
                }
                File folder = new File( path );
                if ( !folder.exists() ) {
                    folder.mkdirs();
                }
                Desktop.getDesktop().open( folder );
            }
            catch ( Exception e ) {
                Logger.logWarningSilent( "Unable to open " + subfolder + " folder: " + e.getMessage() );
            }
        } );
    }

    private static void openSettings()
    {
        SystemUtilities.spawnNewTask( () -> {
            try {
                MCLauncherGuiController.goToSettingsGui();
            }
            catch ( IOException e ) {
                Logger.logWarningSilent( "Couldn't open settings from crash diagnosis: " + e.getMessage() );
            }
        } );
    }

    private static void openRuntime()
    {
        SystemUtilities.spawnNewTask( () -> {
            try {
                MCLauncherGuiController.goToRuntimeGui();
            }
            catch ( IOException e ) {
                Logger.logWarningSilent( "Couldn't open runtime management from crash diagnosis: " + e.getMessage() );
            }
        } );
    }
}
