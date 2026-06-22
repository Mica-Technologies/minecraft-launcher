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
import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.game.modpack.GameModPack;
import com.micatechnologies.minecraft.launcher.game.modpack.GameModPackManager;
import com.micatechnologies.minecraft.launcher.gui.MCLauncherGuiController;
import com.micatechnologies.minecraft.launcher.utilities.SystemUtilities;
import oshi.SystemInfo;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
            // File-lock runs right after disk-full so the two filesystem-class
            // failures sit next to each other and the file-lock test sees the
            // crash before the generic native-library catch.
            CrashReportAnalyzer::detectFileLock,
            CrashReportAnalyzer::detectNativeLibrary,
            // OptiFine + Sodium runs BEFORE both mixin and mod-loading because
            // the structural "you have both" diagnosis is more actionable than
            // the downstream mixin / load-failure noise either of them produces.
            CrashReportAnalyzer::detectOptiFineSodiumConflict,
            CrashReportAnalyzer::detectMixinConflict,
            // Mod-MC-version-mismatch runs before the generic Forge dependency
            // matcher — both look at Forge load messages, but the MC-version one
            // is a more specific subcase that should win when it fires.
            CrashReportAnalyzer::detectModMinecraftVersionMismatch,
            // Forge-specific dependency string must run BEFORE the generic mod-loading
            // detector — it extracts mod IDs + versions from structured Forge messages,
            // and the generic catch-all would otherwise win on the same crash with a
            // less-specific diagnosis.
            CrashReportAnalyzer::detectForgeDependencyMismatch,
            // JEI/REI recipe collisions surface at game-content init time and look
            // like a generic mod-loading failure to the catch-all detector. Run
            // before that so users get the targeted "you have JEI and REI both
            // installed" diagnosis instead of a confused dependency message.
            CrashReportAnalyzer::detectJeiReiRecipeConflict,
            CrashReportAnalyzer::detectModLoading,
            CrashReportAnalyzer::detectAudioInit,
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
                Logger.logWarningSilent( LocalizationManager.format( "log.crashAnalyzer.detectorThrew", e.getMessage() ) );
            }
        }
        // No detector matched, but we have crash text — offer a web search on the most
        // salient exception as a last-resort hint, so an un-diagnosed crash still has an
        // actionable next step instead of a dead end.
        Suggestion search = searchDocumentationSuggestion( pack, extractCrashSearchPhrase( crashText ) );
        if ( search != null ) {
            return CrashDiagnosis.unknownWithSuggestions( exitCode, List.of( search ) );
        }
        return CrashDiagnosis.unknown( exitCode );
    }

    /** Matches a fully-qualified Java exception/error type and its (optional) message. */
    private static final Pattern EXCEPTION_PHRASE_PATTERN = Pattern.compile(
            "((?:[a-zA-Z_$][\\w$]*\\.)+[A-Z][\\w$]*(?:Exception|Error))(?::\\s*([^\\r\\n]+))?" );

    /**
     * Extracts a concise, searchable phrase from a crash report — the first
     * fully-qualified exception, with its message truncated — for use as a web-search
     * query when no detector could diagnose the crash. Returns {@code null} when nothing
     * usable is found (the caller then shows the plain unknown diagnosis with no search).
     */
    static String extractCrashSearchPhrase( String crashText )
    {
        if ( crashText == null || crashText.isBlank() ) {
            return null;
        }
        Matcher m = EXCEPTION_PHRASE_PATTERN.matcher( crashText );
        if ( !m.find() ) {
            return null;
        }
        String type = m.group( 1 );
        String message = m.group( 2 );
        if ( message == null || message.isBlank() ) {
            return type;
        }
        String trimmed = message.trim();
        // Cap the message so the query stays a focused search term (and a long message
        // carrying a user-specific path can't bloat the URL).
        if ( trimmed.length() > 120 ) {
            trimmed = trimmed.substring( 0, 120 ).trim();
        }
        return type + ": " + trimmed;
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
                    LocalizationManager.format( "crash.oom.increaseRam", suggestedGb ),
                    () -> {
                        ConfigManager.setMaxRam( suggested * 1024L );
                        Logger.logStd( LocalizationManager.format( "log.crashAnalyzer.bumpedRam", suggested ) );
                    } ) );
        }
        suggestions.add( Suggestion.of( LocalizationManager.get( "crash.oom.openRamSettings" ), () -> openSettings() ) );

        String detailPart = ( detail != null && !detail.isBlank() )
                ? LocalizationManager.format( "crash.oom.detailSuffix", detail.trim() ) : "";
        String summary = LocalizationManager.format( "crash.oom.summary", detailPart, currentMaxGb );
        if ( systemGb > 0 ) {
            summary += LocalizationManager.format( "crash.oom.systemTotal", systemGb );
        }

        return new CrashDiagnosis(
                CrashDiagnosis.Category.OUT_OF_MEMORY,
                CrashDiagnosis.Severity.CRITICAL,
                LocalizationManager.get( "crash.oom.title" ),
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

        String targetPart = ( requiredJava != null )
                ? LocalizationManager.format( "crash.javaVersion.targetSuffix", requiredJava ) : "";
        String manifestPart = ( pack != null )
                ? LocalizationManager.get( "crash.javaVersion.manifestSuffix" ) : "";
        String summary = LocalizationManager.format( "crash.javaVersion.summary", targetPart, manifestPart );

        return new CrashDiagnosis(
                CrashDiagnosis.Category.JAVA_VERSION,
                CrashDiagnosis.Severity.CRITICAL,
                LocalizationManager.get( "crash.javaVersion.title" ),
                summary,
                List.of(
                        Suggestion.primary( LocalizationManager.get( "crash.action.openRuntime" ), () -> openRuntime() ),
                        Suggestion.of( LocalizationManager.get( "crash.action.openSettings" ), () -> openSettings() )
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
                LocalizationManager.get( "crash.diskFull.title" ),
                LocalizationManager.get( "crash.diskFull.summary" ),
                openFolderSuggestions( pack ) );
    }

    private static final Pattern NATIVE_LIB_PATTERN = Pattern.compile(
            "java\\.lang\\.UnsatisfiedLinkError|Could not (?:locate|load) the native library" );

    private static CrashDiagnosis detectNativeLibrary( String text, GameModPack pack )
    {
        if ( !NATIVE_LIB_PATTERN.matcher( text ).find() ) {
            return null;
        }
        List< Suggestion > suggestions = new ArrayList<>();
        // Reinstall is the canonical fix for native-library failures, so it
        // leads. Only attach when we have a manifest URL to install from
        // (no manifest = nothing to reinstall against).
        if ( pack != null && pack.getPackURL() != null && !pack.getPackURL().isBlank() ) {
            suggestions.add( reinstallPackSuggestion( pack ) );
        }
        suggestions.addAll( openFolderSuggestions( pack ) );
        suggestions.addAll( openCrashReportFolderSuggestion( pack ) );
        return new CrashDiagnosis(
                CrashDiagnosis.Category.NATIVE_LIBRARY,
                CrashDiagnosis.Severity.CRITICAL,
                LocalizationManager.get( "crash.nativeLib.title" ),
                LocalizationManager.get( "crash.nativeLib.summary" ),
                suggestions );
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

        String culpritPart = ( offendingMod != null )
                ? LocalizationManager.format( "crash.mixin.culpritSuffix", offendingMod ) : "";
        String summary = LocalizationManager.format( "crash.mixin.summary", culpritPart );

        List< Suggestion > suggestions = new ArrayList<>();
        // If we identified the offending mod and a matching jar lives in mods/, surface a
        // one-click disable as the primary CTA. Falls back to the generic open-folder list
        // when the name didn't yield a file match.
        if ( offendingMod != null ) {
            Suggestion disable = maybeDisableModSuggestion( pack, offendingMod.toLowerCase( java.util.Locale.ROOT ) );
            if ( disable != null ) {
                suggestions.add( new Suggestion( disable.label(), disable.action(), true ) );
            }
        }
        suggestions.addAll( openFolderSuggestions( pack ) );
        suggestions.addAll( openCrashReportFolderSuggestion( pack ) );

        return new CrashDiagnosis(
                CrashDiagnosis.Category.MIXIN_CONFLICT,
                CrashDiagnosis.Severity.CRITICAL,
                LocalizationManager.get( "crash.mixin.title" ),
                summary,
                suggestions );
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

        String summary = LocalizationManager.format( "crash.forgeDep.summary", requiringMod, requiredMod, requiredVer );

        return new CrashDiagnosis(
                CrashDiagnosis.Category.MOD_LOADING,
                CrashDiagnosis.Severity.CRITICAL,
                LocalizationManager.get( "crash.forgeDep.title" ),
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
            summary = LocalizationManager.format( "crash.modLoading.summaryWithClass", missingClass );
        }
        else {
            summary = LocalizationManager.get( "crash.modLoading.summaryGeneric" );
        }

        return new CrashDiagnosis(
                CrashDiagnosis.Category.MOD_LOADING,
                CrashDiagnosis.Severity.CRITICAL,
                LocalizationManager.get( "crash.modLoading.title" ),
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
        String summary = LocalizationManager.get( "crash.gpu.summary" );

        return new CrashDiagnosis(
                CrashDiagnosis.Category.GPU,
                CrashDiagnosis.Severity.CRITICAL,
                LocalizationManager.get( "crash.gpu.title" ),
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
                LocalizationManager.get( "crash.world.title" ),
                LocalizationManager.get( "crash.world.summary" ),
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
                LocalizationManager.get( "crash.auth.title" ),
                LocalizationManager.get( "crash.auth.summary" ),
                List.of(
                        Suggestion.primary( LocalizationManager.get( "crash.action.openSettings" ), () -> openSettings() )
                ) );
    }

    // -----------------------------------------------------------------------------------------
    //  Audio subsystem init failure — Linux PulseAudio / ALSA + Windows missing device
    // -----------------------------------------------------------------------------------------

    private static final Pattern AUDIO_PATTERN = Pattern.compile(
            "Could not (?:open|create|initialize) (?:audio|sound|OpenAL)"
                    + "|OpenAL [Ee]rror"
                    + "|AL_INVALID_(?:DEVICE|OPERATION)"
                    + "|com\\.mojang\\.blaze3d\\.audio\\.\\w+Exception"
                    + "|paInternalError|ALSA lib"
                    + "|No default audio device" );

    private static CrashDiagnosis detectAudioInit( String text, GameModPack pack )
    {
        if ( !AUDIO_PATTERN.matcher( text ).find() ) {
            return null;
        }
        String summary = LocalizationManager.get( "crash.audio.summary" );
        return new CrashDiagnosis(
                CrashDiagnosis.Category.AUDIO,
                CrashDiagnosis.Severity.WARNING,
                LocalizationManager.get( "crash.audio.title" ),
                summary,
                openCrashReportFolderSuggestion( pack ) );
    }

    // -----------------------------------------------------------------------------------------
    //  File lock — Windows antivirus / handle-still-open
    // -----------------------------------------------------------------------------------------

    private static final Pattern FILE_LOCK_PATTERN = Pattern.compile(
            "The process cannot access the file because it is being used by another process"
                    + "|FileSystemException:.*Access is denied"
                    + "|java\\.nio\\.file\\.AccessDeniedException"
                    + "|Sharing violation" );

    private static CrashDiagnosis detectFileLock( String text, GameModPack pack )
    {
        if ( !FILE_LOCK_PATTERN.matcher( text ).find() ) {
            return null;
        }
        String summary = LocalizationManager.get( "crash.fileLock.summary" );
        List< Suggestion > suggestions = new ArrayList<>();
        suggestions.add( Suggestion.primary( LocalizationManager.get( "crash.action.openInstallFolder" ), () -> openPackSubfolder( pack, "" ) ) );
        suggestions.addAll( openCrashReportFolderSuggestion( pack ) );
        return new CrashDiagnosis(
                CrashDiagnosis.Category.FILE_LOCK,
                CrashDiagnosis.Severity.CRITICAL,
                LocalizationManager.get( "crash.fileLock.title" ),
                summary,
                suggestions );
    }

    // -----------------------------------------------------------------------------------------
    //  OptiFine + Sodium combo — known-incompatible pair
    // -----------------------------------------------------------------------------------------

    /** True when both OptiFine and Sodium are mentioned in the crash text — either as class
     *  names, mod IDs, or filenames. Different from MIXIN_CONFLICT because the failure isn't
     *  always a mixin throw; sometimes it's just a downstream LoaderException with two mods
     *  fighting over the same Minecraft internals. */
    private static final Pattern OPTIFINE_PATTERN = Pattern.compile(
            "(?i)optifine|optifabric" );
    private static final Pattern SODIUM_PATTERN = Pattern.compile(
            "(?i)\\bsodium\\b|me\\.jellysquid\\.mods\\.sodium" );

    private static CrashDiagnosis detectOptiFineSodiumConflict( String text, GameModPack pack )
    {
        if ( !OPTIFINE_PATTERN.matcher( text ).find()
                || !SODIUM_PATTERN.matcher( text ).find() ) {
            return null;
        }
        String summary = LocalizationManager.get( "crash.optifineSodium.summary" );

        List< Suggestion > suggestions = new ArrayList<>();
        Suggestion disableOptifine = maybeDisableModSuggestion( pack, "optifine" );
        if ( disableOptifine != null ) suggestions.add( disableOptifine );
        Suggestion disableSodium = maybeDisableModSuggestion( pack, "sodium" );
        if ( disableSodium != null ) suggestions.add( disableSodium );
        if ( suggestions.isEmpty() ) {
            // Mods folder doesn't surface the jar names — fall back to a manual prompt.
            suggestions.add( Suggestion.primary( LocalizationManager.get( "crash.action.openModsFolder" ),
                    () -> openPackSubfolder( pack, "mods" ) ) );
        }
        else {
            // Tag the first auto-disable button as primary for visual emphasis.
            Suggestion first = suggestions.get( 0 );
            suggestions.set( 0, new Suggestion( first.label(), first.action(), true ) );
            suggestions.add( Suggestion.of( LocalizationManager.get( "crash.action.openModsFolder" ),
                    () -> openPackSubfolder( pack, "mods" ) ) );
        }
        return new CrashDiagnosis(
                CrashDiagnosis.Category.LOADER_CONFLICT,
                CrashDiagnosis.Severity.CRITICAL,
                LocalizationManager.get( "crash.optifineSodium.title" ),
                summary,
                suggestions );
    }

    // -----------------------------------------------------------------------------------------
    //  Mod compiled for a different Minecraft version
    // -----------------------------------------------------------------------------------------

    /** Forge prints messages like
     *  {@code Mod 'X' (xid) requires minecraft 1.20.1 but you have 1.20.4 loaded},
     *  or the looser {@code The mod X was compiled for MC 1.20.1, but is being loaded
     *  by MC 1.20.4}. Capture both shapes. Groups (in order across alternations):
     *  required-MC, observed-MC. */
    private static final Pattern MOD_MC_VERSION_PATTERN = Pattern.compile(
            "(?:requires|requires loaded|compiled for|compiled against)\\s+(?:Minecraft\\s+)?(?:version\\s+)?"
                    + "([\\d]+\\.[\\d]+(?:\\.[\\d]+)?)"
                    + "\\s+(?:but(?: you have| we have| this version is)?|but is being loaded by)\\s+"
                    + "(?:Minecraft\\s+)?(?:version\\s+)?([\\d]+\\.[\\d]+(?:\\.[\\d]+)?)" );

    private static CrashDiagnosis detectModMinecraftVersionMismatch( String text, GameModPack pack )
    {
        Matcher m = MOD_MC_VERSION_PATTERN.matcher( text );
        if ( !m.find() ) {
            return null;
        }
        String requiredMc = m.group( 1 );
        String currentMc  = m.group( 2 );
        String summary = LocalizationManager.format( "crash.modMcMismatch.summary", requiredMc, currentMc );
        return new CrashDiagnosis(
                CrashDiagnosis.Category.MOD_LOADING,
                CrashDiagnosis.Severity.CRITICAL,
                LocalizationManager.format( "crash.modMcMismatch.title", requiredMc, currentMc ),
                summary,
                openFolderSuggestions( pack ) );
    }

    // -----------------------------------------------------------------------------------------
    //  JEI / REI recipe collision
    // -----------------------------------------------------------------------------------------

    /** Most JEI/REI conflicts surface as "Duplicate recipe id" errors from either mod's
     *  recipe registry, or as both projects being present in a NoClassDefFoundError tail
     *  (the rare case where the user installed both and the bridge mod is missing).
     *  We require either an explicit duplicate-recipe message OR the dual-presence pattern. */
    private static final Pattern JEI_REI_DUP_PATTERN = Pattern.compile(
            "Duplicate recipe(?: id| identifier)"
                    + "|mezz\\.jei.*RecipeRegistry"
                    + "|me\\.shedaniel\\.rei.*Recipe(?:Display|Registry)Exception" );
    private static final Pattern JEI_AND_REI_DUAL_PATTERN = Pattern.compile(
            "(?:mezz\\.jei|jei-\\d|just enough items).*?(?:me\\.shedaniel\\.rei|roughly enough items|rei-\\d)"
                    + "|(?:me\\.shedaniel\\.rei|roughly enough items|rei-\\d).*?(?:mezz\\.jei|jei-\\d|just enough items)",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE );

    private static CrashDiagnosis detectJeiReiRecipeConflict( String text, GameModPack pack )
    {
        boolean dup = JEI_REI_DUP_PATTERN.matcher( text ).find();
        boolean dual = JEI_AND_REI_DUAL_PATTERN.matcher( text ).find();
        if ( !dup && !dual ) {
            return null;
        }

        String summary;
        List< Suggestion > suggestions = new ArrayList<>();
        if ( dual ) {
            summary = LocalizationManager.get( "crash.jeiRei.summaryDual" );
            Suggestion disableJei = maybeDisableModSuggestion( pack, "jei" );
            if ( disableJei != null ) suggestions.add( disableJei );
            Suggestion disableRei = maybeDisableModSuggestion( pack, "rei" );
            if ( disableRei != null ) suggestions.add( disableRei );
            if ( !suggestions.isEmpty() ) {
                Suggestion first = suggestions.get( 0 );
                suggestions.set( 0, new Suggestion( first.label(), first.action(), true ) );
            }
        }
        else {
            summary = LocalizationManager.get( "crash.jeiRei.summaryCollision" );
        }
        suggestions.add( Suggestion.of( LocalizationManager.get( "crash.action.openModsFolder" ), () -> openPackSubfolder( pack, "mods" ) ) );
        return new CrashDiagnosis(
                CrashDiagnosis.Category.MOD_LOADING,
                CrashDiagnosis.Severity.CRITICAL,
                dual ? LocalizationManager.get( "crash.jeiRei.titleDual" ) : LocalizationManager.get( "crash.jeiRei.titleCollision" ),
                summary,
                suggestions );
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
        out.add( Suggestion.primary( LocalizationManager.get( "crash.action.openModsFolder" ), () -> openPackSubfolder( pack, "mods" ) ) );
        out.add( Suggestion.of( LocalizationManager.get( "crash.action.openInstallFolder" ), () -> openPackSubfolder( pack, "" ) ) );
        return out;
    }

    /** Single-entry suggestion list for "open the pack's crash-reports/ folder."
     *  Returned as a list so callers can {@code addAll} it without a null check. */
    private static List< Suggestion > openCrashReportFolderSuggestion( GameModPack pack )
    {
        if ( pack == null ) {
            return List.of();
        }
        return List.of( Suggestion.of( LocalizationManager.get( "crash.action.openCrashReports" ),
                () -> openPackSubfolder( pack, "crash-reports" ) ) );
    }

    /** Builds a "Reinstall Modpack" primary call-to-action that wipes the install + re-runs
     *  the install-by-URL pipeline. Used by NATIVE_LIBRARY where reinstall is the canonical
     *  fix. Caller is expected to gate on {@code pack.getPackURL() != null} before invoking. */
    private static Suggestion reinstallPackSuggestion( GameModPack pack )
    {
        return Suggestion.primary( LocalizationManager.get( "crash.action.reinstallModpack" ), () -> SystemUtilities.spawnNewTask( () -> {
            try {
                String url = pack.getPackURL();
                Logger.logStd( LocalizationManager.format( "log.crashAnalyzer.reinstallRequested",
                                       pack.getPackName(), url ) );
                GameModPackManager.uninstallModPack( pack );
                GameModPackManager.installModPackByURL( url );
            }
            catch ( Exception | Error e ) {
                Logger.logWarningSilent( LocalizationManager.format( "log.crashAnalyzer.reinstallFailed", e.getMessage() ) );
            }
        } ) );
    }

    /** Walks the pack's {@code mods/} directory and returns a "Disable {name}" suggestion for
     *  the first jar whose lowercased filename contains {@code substring}. Returns
     *  {@code null} when the mods folder doesn't exist, can't be listed, or no jar matches —
     *  callers (OptiFine/Sodium detector, JEI/REI detector) gracefully degrade to a manual
     *  "Open Mods Folder" prompt in that case.
     *
     *  <p>Renaming to {@code .jar.disabled} is the universal "skip this mod" convention
     *  recognised by Forge, NeoForge, and Fabric loaders. The file is renamed not deleted
     *  so the user can re-enable it later by stripping the suffix.</p> */
    private static Suggestion maybeDisableModSuggestion( GameModPack pack, String substring )
    {
        if ( pack == null || pack.getPackRootFolder() == null ) {
            return null;
        }
        File modsDir = new File( pack.getPackRootFolder(), "mods" );
        if ( !modsDir.isDirectory() ) {
            return null;
        }
        File[] jars = modsDir.listFiles( ( dir, name ) ->
                name.toLowerCase( java.util.Locale.ROOT ).endsWith( ".jar" )
                        && name.toLowerCase( java.util.Locale.ROOT ).contains( substring ) );
        if ( jars == null || jars.length == 0 ) {
            return null;
        }
        // Pick the first match. There's rarely more than one optifine/sodium/jei/rei jar in
        // a single pack — and if there is, disabling the first is still progress.
        File target = jars[ 0 ];
        return Suggestion.of( LocalizationManager.format( "crash.action.disableMod", target.getName() ), () -> SystemUtilities.spawnNewTask( () -> {
            try {
                Path src = target.toPath();
                Path dst = src.resolveSibling( target.getName() + ".disabled" );
                Files.move( src, dst, StandardCopyOption.REPLACE_EXISTING );
                Logger.logStd( LocalizationManager.format( "log.crashAnalyzer.renamedMod", src.toString(), dst.toString() ) );
            }
            catch ( Exception | Error e ) {
                Logger.logWarningSilent( LocalizationManager.format( "log.crashAnalyzer.disableModFailed",
                                                 target.getName(), e.getMessage() ) );
            }
        } ) );
    }

    /** Opens a Google search pre-filled with the pack name + a short snippet from the crash
     *  text. Useful as a last-resort hint on diagnoses where the launcher can't suggest a
     *  concrete fix; surfaced from the GUI's fallback panel rather than wired into specific
     *  detectors to keep the per-detector suggestion lists tight. */
    static Suggestion searchDocumentationSuggestion( GameModPack pack, String crashPhrase )
    {
        if ( crashPhrase == null || crashPhrase.isBlank() ) {
            return null;
        }
        String query = ( pack != null && pack.getPackName() != null ? pack.getPackName() + " " : "" )
                + crashPhrase;
        String encoded = URLEncoder.encode( query, StandardCharsets.UTF_8 );
        String url = "https://www.google.com/search?q=" + encoded;
        return Suggestion.of( LocalizationManager.get( "crash.action.searchWeb" ), () -> SystemUtilities.spawnNewTask( () -> {
            try {
                if ( Desktop.isDesktopSupported() ) {
                    Desktop.getDesktop().browse( new java.net.URI( url ) );
                }
            }
            catch ( Exception | Error e ) {
                Logger.logWarningSilent( LocalizationManager.format( "log.crashAnalyzer.openSearchFailed", e.getMessage() ) );
            }
        } ) );
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
                Logger.logWarningSilent( LocalizationManager.format( "log.crashAnalyzer.openFolderFailed", subfolder, e.getMessage() ) );
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
                Logger.logWarningSilent( LocalizationManager.format( "log.crashAnalyzer.openSettingsFailed", e.getMessage() ) );
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
                Logger.logWarningSilent( LocalizationManager.format( "log.crashAnalyzer.openRuntimeFailed", e.getMessage() ) );
            }
        } );
    }
}
