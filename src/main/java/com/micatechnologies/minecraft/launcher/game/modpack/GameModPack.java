/*
 * Copyright (c) 2021 Mica Technologies
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

package com.micatechnologies.minecraft.launcher.game.modpack;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import com.micatechnologies.minecraft.launcher.consts.GUIConstants;
import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
import com.micatechnologies.minecraft.launcher.utilities.StringOrArray;
import com.micatechnologies.minecraft.launcher.consts.ModPackConstants;
import com.micatechnologies.minecraft.launcher.exceptions.ModpackException;
import com.micatechnologies.minecraft.launcher.exceptions.ModpackScanDetectionException;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.game.modpack.manifests.GameLibraryManifest;
import com.micatechnologies.minecraft.launcher.game.modpack.manifests.GameVersionManifest;
import com.micatechnologies.minecraft.launcher.security.SupplementalScanner;
import me.cortex.jarscanner.Constants;
import me.cortex.jarscanner.Main;
import me.cortex.jarscanner.Results;

import java.util.List;

/**
 * Class representation of a Forge mod pack with functionality to update mods, game libraries and start the game.
 *
 * @version 1.3
 */
public class GameModPack extends GameModPackMetadata
{
    /** Progress sink for verify / download / launch operations on this pack;
     *  {@code null} until {@link #setProgressProvider} installs one. */
    private transient GameModPackProgressProvider progressProvider  = null;
    /** Lazily-constructed environment handler (directories + image cache);
     *  see {@link #getEnvironment()}. */
    private transient GameModPackEnvironment      environment       = null;
    /** Lazily-constructed launcher (classpath build + game spawn), rebound
     *  whenever {@link #setProgressProvider} changes the progress sink; see
     *  {@link #getLauncher()}. */
    private transient GameModPackLauncher         launcher          = null;

    /**
     * Optional quick-join server target for the next {@link #startGame()}
     * call. When non-null, the launcher appends {@code --server <host>}
     * (plus {@code --port <port>} when not the default 25565) to the
     * Minecraft game argv, so the user lands directly on that server's
     * loading screen instead of the main menu. Cleared by the launcher
     * after consumption — set it again per launch.
     *
     * @since 2026.5
     */
    private transient ServerFavorite quickJoinServer = null;

    /**
     * Sets the explicit quick-join server target for the next
     * {@link #startGame()} call. Pass the server the user clicked "Connect"
     * on; the value is consumed (read-and-cleared) by
     * {@link #consumeQuickJoinServer()} during launch, so it must be set again
     * for each launch that should auto-join. Passing {@code null} clears any
     * previously-set explicit target, falling back to the pack's
     * manifest-declared default server.
     *
     * @param quickJoinServer the server to auto-join on next launch, or
     *                         {@code null} to clear the explicit target
     *
     * @since 2026.5
     */
    public void setQuickJoinServer( ServerFavorite quickJoinServer )
    {
        this.quickJoinServer = quickJoinServer;
    }

    /**
     * Returns and clears the explicit quick-join server set by a "Connect"
     * action; when no explicit target is set, falls back to the pack's
     * manifest-declared default server (when present and not disabled by
     * the user via the per-pack toggle stored in
     * {@link ServerFavoritesStore}). This is the single place that
     * resolves "what server, if any, should the next launch auto-join?" —
     * both the Play button and the Connect button funnel through here.
     *
     * @return the server the next launch should auto-join, or {@code null}
     *         when no explicit target is set and no (enabled) default server
     *         is declared
     *
     * @since 2026.5
     */
    public ServerFavorite consumeQuickJoinServer()
    {
        ServerFavorite v = this.quickJoinServer;
        this.quickJoinServer = null;
        if ( v != null ) return v;
        ServerFavorite dflt = getDefaultServer();
        if ( dflt == null ) return null;
        if ( ServerFavoritesStore.isDefaultServerDisabled( this ) ) return null;
        return dflt;
    }

    /**
     * Returns the lazily-initialized environment handler for this modpack,
     * constructing it on first access.
     *
     * @return the per-pack {@link GameModPackEnvironment}; never {@code null}
     */
    private GameModPackEnvironment getEnvironment()
    {
        if ( environment == null ) {
            environment = new GameModPackEnvironment( this );
        }
        return environment;
    }

    /**
     * Returns the lazily-initialized launcher for this modpack, constructing
     * it (bound to the current {@link #progressProvider}) on first access.
     *
     * @return the per-pack {@link GameModPackLauncher}; never {@code null}
     */
    private GameModPackLauncher getLauncher()
    {
        if ( launcher == null ) {
            launcher = new GameModPackLauncher( this, progressProvider );
        }
        return launcher;
    }

    /**
     * Cached modloader instance for this pack. Constructing a
     * {@link GameModLoader} eagerly verifies the on-disk installer / profile
     * artifact (and re-downloads on hash mismatch), so we cache the instance to
     * avoid paying that verification cost — and emitting "FILE FAILED
     * VERIFICATION" console spam — on every UI card render that asks for the
     * pack's MC or loader version. The cache is {@code transient} so a re-fetch
     * of the modpack list still produces a fresh verification on the new
     * instance.
     *
     * @since 3.3
     */
    private transient GameModLoader cachedModLoader;

    /**
     * Cached Minecraft library manifest for this pack's MC version. Building a
     * {@link GameLibraryManifest} downloads + GSON-parses the version's
     * client.json (2-4 MB), and the launch path asks for it more than once (the
     * MC-libs stage and again in the main {@code launch()}), so without this the
     * big manifest was read + parsed twice per launch. Memoized so the parse
     * happens once per pack instance. {@code transient}, so a re-fetch of the
     * modpack list re-parses against the fresh instance.
     *
     * @since 2026.6
     */
    private transient GameLibraryManifest cachedLibraryManifest;

    /**
     * The modloader type identifier for this pack — one of
     * {@link com.micatechnologies.minecraft.launcher.consts.ModPackConstants#MOD_LOADER_FORGE},
     * {@code MOD_LOADER_NEOFORGE}, or {@code MOD_LOADER_FABRIC}. Vanilla
     * packs return {@code null}. Absent / blank in the manifest defaults
     * to Forge for back-compat with every modpack created before the
     * multi-loader work.
     *
     * @return the lower-cased loader type identifier (one of
     *         {@code forge} / {@code neoforge} / {@code fabric}), or
     *         {@code null} for vanilla packs
     */
    public String getModLoaderType() {
        if ( vanillaVersion ) return null;
        if ( packModLoader == null || packModLoader.isBlank() ) {
            return com.micatechnologies.minecraft.launcher.consts.ModPackConstants.MOD_LOADER_DEFAULT;
        }
        return packModLoader.trim().toLowerCase( java.util.Locale.ROOT );
    }

    /** Loader installer / profile URL — generalised replacement for
     *  {@code packForgeURL}. Falls back to {@code packForgeURL} when
     *  the new field isn't present in the manifest.
     *
     *  @return the loader meta-artifact URL, or the legacy
     *          {@code packForgeURL} fallback */
    String getModLoaderURL() {
        if ( packModLoaderURL != null && !packModLoaderURL.isBlank() ) {
            return packModLoaderURL;
        }
        return packForgeURL;
    }

    /** Loader installer / profile SHA-1 — generalised replacement for
     *  {@code packForgeHash}. Falls back to {@code packForgeHash}. May
     *  be null/empty for loaders that don't hash-verify their meta
     *  artifact.
     *
     *  @return the loader meta-artifact SHA-1, the legacy
     *          {@code packForgeHash} fallback, or {@code null}/empty when
     *          the loader doesn't hash-verify */
    String getModLoaderHash() {
        if ( packModLoaderHash != null && !packModLoaderHash.isBlank() ) {
            return packModLoaderHash;
        }
        return packForgeHash;
    }

    /**
     * Get the polymorphic modloader for this pack. Returns {@code null}
     * for vanilla packs. Cached per pack instance.
     *
     * <p>The concrete class is chosen from {@link #getModLoaderType()};
     * each loader interprets its own {@code packModLoaderURL} /
     * {@code packModLoaderHash} according to its own conventions
     * (installer JAR for Forge / NeoForge, profile-JSON URL for
     * Fabric).</p>
     *
     * @return the cached or freshly-constructed {@link GameModLoader} for
     *         this pack, or {@code null} for vanilla packs
     *
     * @throws ModpackException if the loader type is unknown or
     *                          construction (which validates the
     *                          installer artifact) fails.
     */
    public GameModLoader getModLoader() throws ModpackException {
        if ( vanillaVersion ) return null;
        if ( cachedModLoader == null ) {
            cachedModLoader = createModLoader();
        }
        return cachedModLoader;
    }

    /**
     * Constructs the concrete {@link GameModLoader} implementation for this
     * pack's {@link #getModLoaderType() loader type}, passing it the loader
     * URL and hash. Construction eagerly validates the on-disk meta artifact,
     * so callers should prefer the cached {@link #getModLoader()}.
     *
     * @return a newly-constructed loader matching this pack's loader type
     *
     * @throws ModpackException if the loader type is unrecognized or the
     *                          loader's own construction / artifact
     *                          validation fails
     */
    private GameModLoader createModLoader() throws ModpackException {
        String type = getModLoaderType();
        String url = getModLoaderURL();
        String hash = getModLoaderHash();
        return switch ( type ) {
            case com.micatechnologies.minecraft.launcher.consts.ModPackConstants.MOD_LOADER_FORGE ->
                    new GameModLoaderForge( url, hash, this );
            case com.micatechnologies.minecraft.launcher.consts.ModPackConstants.MOD_LOADER_NEOFORGE ->
                    new GameModLoaderNeoForge( url, hash, this );
            case com.micatechnologies.minecraft.launcher.consts.ModPackConstants.MOD_LOADER_FABRIC ->
                    new GameModLoaderFabric( url, hash, this );
            default -> throw new ModpackException(
                    "Unsupported modloader type \"" + type + "\" for pack "
                            + getPackName() + " — known types: forge, neoforge, fabric." );
        };
    }

    /**
     * Get the Minecraft version of this modpack.
     *
     * @return modpack Minecraft version
     *
     * @throws ModpackException if unable to get Minecraft version
     */
    @SuppressWarnings( "WeakerAccess" )
    public String getMinecraftVersion() throws ModpackException {
        if ( vanillaVersion && vanillaMinecraftVersion != null ) {
            return vanillaMinecraftVersion;
        }
        // Dispatch through the polymorphic modloader so Fabric / NeoForge
        // packs get their own MC version reads.
        return getModLoader().getMinecraftVersion();
    }

    /** Display name of this pack's modloader — {@code "Forge"},
     *  {@code "NeoForge"}, {@code "Fabric"}, or {@code null} for
     *  vanilla packs. Used by the hero / detail UI chips.
     *
     *  @return the loader's human-readable name, or {@code null} for
     *          vanilla packs
     *
     *  @throws ModpackException if the loader can't be resolved /
     *                          constructed */
    public String getLoaderName() throws ModpackException {
        if ( vanillaVersion ) return null;
        return getModLoader().getName();
    }

    /**
     * Whether this pack needs the post-install step
     * ({@link LaunchProgressTracker.StepId#FORGE_PROCESSORS} row in the
     * launch UI; the "Game patching" stage). True for Forge and
     * NeoForge (modern installers run install processors here; legacy
     * Forge's runForgeProcessors short-circuits but still uses the
     * row). False for Fabric (runtime loader — no patching pipeline)
     * and vanilla. Doesn't construct the loader so this is safe to
     * call before launch / verify starts.
     *
     * @return {@code true} if the launch UI should show the post-install
     *         (game-patching) step for this pack; {@code false} for Fabric
     *         and vanilla
     */
    public boolean usesPostInstallSteps() {
        if ( vanillaVersion ) return false;
        return !com.micatechnologies.minecraft.launcher.consts.ModPackConstants.MOD_LOADER_FABRIC
                .equals( getModLoaderType() );
    }

    /** Loader-agnostic version string — Forge's
     *  {@code "14.23.5.2855"}, Fabric loader's {@code "0.16.10"},
     *  NeoForge's {@code "21.1.95"}. {@code null} for vanilla.
     *
     *  @return the loader version string, or {@code null} for vanilla packs
     *
     *  @throws ModpackException if the loader can't be resolved /
     *                          constructed */
    public String getLoaderVersion() throws ModpackException {
        if ( vanillaVersion ) return null;
        return getModLoader().getLoaderVersion();
    }

    /**
     * Get the Minecraft library manifest for this modpack.
     *
     * @return Minecraft library manifest
     *
     * @throws ModpackException if unable to get manifest
     */
    public GameLibraryManifest getMinecraftLibraryManifest() throws ModpackException {
        // Memoize: both the MC-libs launch stage and the main launch() ask for
        // this, as do repeated UI card renders. The instance parses the version's
        // 2-4 MB client.json once; sharing it avoids a redundant re-parse.
        if ( cachedLibraryManifest == null ) {
            cachedLibraryManifest = GameVersionManifest.getMinecraftLibraryManifest( getMinecraftVersion(), this );
        }
        return cachedLibraryManifest;
    }

    /**
     * Verify all local modpack files and downloads files if necessary, then compiles the classpath String for running
     * this modpack. Delegates to {@link GameModPackLauncher#buildClasspath()}.
     *
     * @return modpack classpath String
     *
     * @throws ModpackException if unable to update modpack or build classpath
     * @since 1.0
     */
    public String buildModpackClasspath() throws ModpackException {
        return getLauncher().buildClasspath();
    }

    /**
     * Scans the modpack root folder for any infections identified by the {@link me.cortex.jarscanner.Detector} class.
     *
     * @throws ModpackException     if any infections are found
     * @throws IOException          if any I/O errors occur while scanning
     * @throws InterruptedException if the current thread is interrupted while scanning
     */
    public void scanModPackRootFolder() throws ModpackException, IOException, InterruptedException {
        int halfCoreCount = Runtime.getRuntime().availableProcessors() / 2;
        int scanCoreCount = Math.max( 1, halfCoreCount );
        boolean emitWalkErrors = true;
        Function< String, String > logOutput = ( out ) -> {
            if ( progressProvider != null ) {
                String processedOut = out.replace( Constants.ANSI_RED, "" )
                                         .replace( Constants.ANSI_GREEN, "" )
                                         .replace( Constants.ANSI_WHITE, "" )
                                         .replace( Constants.ANSI_RESET, "" );
                progressProvider.setCurrText( processedOut );
            }
            return out;
        };

        // The manifest's scan-exclusion list is attacker-controllable, so it
        // passes through ScanExclusionPolicy before EITHER scan pass honors
        // it — otherwise a malicious pack could exclude mods/ (or the pack
        // root) and disarm the malware scan entirely. Rejected entries are
        // logged by the policy.
        List< String > safeScanExclusions =
                com.micatechnologies.minecraft.launcher.security.ScanExclusionPolicy.filterUntrusted(
                        getPackScanExclusions() );

        Results scanResults = Main.run( scanCoreCount, Path.of( getPackRootFolder() ), emitWalkErrors,
                                        safeScanExclusions, logOutput, null );

        // Supplemental, non-Fractureiser-specific heuristics. The Nekodetector
        // checks above are narrowly targeted at one malware family; this layer
        // adds high-signal checks for embedded executables, Discord-webhook
        // exfil endpoints, paste-host stage-2 fetchers, and a couple of
        // adjacent IoCs. HIGH-severity findings are treated as Stage 1 hits;
        // MEDIUM findings are logged but don't block launch (false-positive
        // floor is too uncertain to fail-closed on them).
        List< SupplementalScanner.Finding > supplemental = new ArrayList<>();
        try {
            supplemental.addAll( SupplementalScanner.scanFolder( Path.of( getPackRootFolder() ),
                                                                  safeScanExclusions, scanCoreCount ) );
        }
        catch ( IOException e ) {
            // I/O failure during the supplemental walk shouldn't abort the
            // launch on its own — the upstream Nekodetector already completed.
            Logger.logWarningSilent( LocalizationManager.format( "log.gameModPack.supplementalScanIoError",
                                             e.getClass().getSimpleName() ) );
        }

        // Apply per-finding acknowledgements declared in the manifest. Each
        // ack carries (fileSha256, kind, locator); findings whose structured
        // signature matches are dropped from the blocking list and logged so
        // the user can see the maintainer's stated rationale. The scan still
        // RAN against the file — this only silences specific known-OK
        // patterns, so a JAR update that changes the SHA-256 invalidates the
        // ack (the new content gets reviewed fresh) and a different kind /
        // locator firing on the same JAR still flags.
        //
        // Acknowledgements only apply to supplemental (heuristic) findings.
        // Nekodetector stage 1 / stage 2 hits are signature-based malware
        // detection and stay unsilenceable — if a real Fractureiser FP ever
        // occurred, the right escape hatch is per-pack ScanFrequency.DISABLED.
        List< ScanAcknowledgement > acks = getPackScanAcknowledgements();
        List< SupplementalScanner.Finding > remainingSupp = new ArrayList<>();
        for ( SupplementalScanner.Finding f : supplemental ) {
            ScanAcknowledgement matched = null;
            for ( ScanAcknowledgement a : acks ) {
                if ( a != null && a.matches( f ) ) {
                    matched = a;
                    break;
                }
            }
            if ( matched != null ) {
                String reasonSuffix = ( matched.reason == null || matched.reason.isBlank() )
                        ? ""
                        : LocalizationManager.format( "log.gameModPack.ackReasonSuffix", matched.reason );
                String line = LocalizationManager.format( "log.gameModPack.acknowledgedFinding", f, reasonSuffix );
                logOutput.apply( line );
                Logger.logStd( line );
            }
            else {
                remainingSupp.add( f );
            }
        }

        // Per-finding logging + (where applicable) acknowledgement hint for
        // everything that remains after acks. Done in a single pass so each
        // un-silenced finding shows up next to a paste-ready snippet.
        List< String > stage1 = scanResults.getStage1Detections();
        List< String > stage2 = scanResults.getStage2Detections();
        if ( stage1 != null ) {
            for ( String f : stage1 ) {
                Logger.logWarning( LocalizationManager.format( "log.gameModPack.scanFlagged", f ) );
                Logger.logStd( nekoCannotAckHint() );
            }
        }
        if ( stage2 != null ) {
            for ( String f : stage2 ) {
                Logger.logWarning( LocalizationManager.format( "log.gameModPack.scanFlagged", f ) );
                Logger.logStd( nekoCannotAckHint() );
            }
        }
        for ( SupplementalScanner.Finding f : remainingSupp ) {
            if ( f.severity() == SupplementalScanner.Severity.HIGH ) {
                logOutput.apply( LocalizationManager.format( "log.gameModPack.supplementalInfection", f ) );
            }
            else {
                logOutput.apply( LocalizationManager.format( "log.gameModPack.supplementalWarning", f ) );
            }
            Logger.logWarning( LocalizationManager.format( "log.gameModPack.supplementalScanFlagged", f ) );
            emitAcknowledgementHint( f );
        }

        // Block launch if anything's still un-acknowledged. Stage 1 and the
        // supplemental HIGHs share the same blocking severity — merge them
        // into one list for the exception so the popup renders them together.
        // Supplemental MEDIUMs never block.
        List< String > blockingHigh = new ArrayList<>();
        if ( stage1 != null ) blockingHigh.addAll( stage1 );
        for ( SupplementalScanner.Finding f : remainingSupp ) {
            if ( f.severity() == SupplementalScanner.Severity.HIGH ) {
                blockingHigh.add( f.toString() );
            }
        }
        boolean anyBlocking = !blockingHigh.isEmpty()
                || ( stage2 != null && !stage2.isEmpty() );
        if ( anyBlocking ) {
            int total = blockingHigh.size() + ( stage2 == null ? 0 : stage2.size() );
            logOutput.apply( total == 1
                                     ? LocalizationManager.format( "log.gameModPack.scanBlockedLaunchOne", total )
                                     : LocalizationManager.format( "log.gameModPack.scanBlockedLaunchMany", total ) );
            throw new ModpackScanDetectionException(
                    new Results( blockingHigh, stage2 ),
                    getPackName(), getPackRootFolder() );
        }
    }

    /**
     * Emit a paste-ready hint to the log so a pack maintainer can silence
     * the given supplemental-scanner finding by appending an entry to the
     * manifest's {@code packScanAcknowledgements} list:
     *
     * <pre>
     * To silence this finding, append to the manifest's packScanAcknowledgements:
     *   {
     *     // Recommended: keyed to the inner element's bytes so the ack
     *     // survives outer-mod repackages. Pick this one unless you have
     *     // a specific reason to want strict outer-JAR pinning.
     *     "innerSha256": "ab12cd34...",
     *     "kind":        "LAUNCHER_CREDENTIAL_FILE_REF",
     *     "locator":     "optifine/Installer.updateLauncherJson",
     *     "reason":      ""
     *
     *     // Alternative: keyed to the outer JAR's bytes — invalidates on
     *     // any mod update, even when the offending inner element is
     *     // unchanged. Useful for defense-in-depth when paired with
     *     // innerSha256 (both must match).
     *     // "fileSha256": "ff00ee11..."
     *   }
     * </pre>
     *
     * <p>The identity fields come straight from the structured Finding so
     * they're stable across launcher versions — renaming the user-facing
     * message wording doesn't shift them, and the (kind, locator) pair is
     * tight enough that a different finding can't accidentally match.</p>
     *
     * @param f the supplemental-scanner finding to emit a silence hint for;
     *          {@code null} and hash-less findings are handled gracefully
     *          (no unmatchable snippet is produced)
     */
    private static void emitAcknowledgementHint( SupplementalScanner.Finding f )
    {
        if ( f == null ) return;
        String fileSha  = f.fileSha256();
        String innerSha = f.innerSha256();
        if ( ( fileSha == null || fileSha.isBlank() )
                && ( innerSha == null || innerSha.isBlank() ) ) {
            // Without any hash we can't produce a stable signature. Tell the user
            // why no hint appears rather than emit an unmatchable snippet.
            Logger.logStd( LocalizationManager.get( "log.gameModPack.noAckSignature" ) );
            return;
        }
        String kindName = f.kind() == null ? "" : f.kind().name();
        String locator = f.locator() == null ? "" : f.locator();
        Logger.logStd( LocalizationManager.get( "log.gameModPack.silenceHintHeader" ) );
        Logger.logStd( "  {" );
        // Lead with innerSha256 — survives outer-mod updates as long as the
        // bundled artifact / class itself doesn't change. Falls back to a
        // commented placeholder line when the inner hash couldn't be computed.
        if ( innerSha != null && !innerSha.isBlank() ) {
            Logger.logStd( "    // Recommended — survives outer-mod updates." );
            Logger.logStd( "    \"innerSha256\": \"" + jsonEscape( innerSha ) + "\"," );
        }
        else {
            Logger.logStd( "    // Inner-element hash unavailable for this finding." );
        }
        if ( fileSha != null && !fileSha.isBlank() ) {
            Logger.logStd( "    // Alternative — strict outer-JAR pinning, breaks on mod updates." );
            Logger.logStd( "    // \"fileSha256\": \"" + jsonEscape( fileSha ) + "\"," );
        }
        Logger.logStd( "    \"kind\":        \"" + jsonEscape( kindName ) + "\"," );
        Logger.logStd( "    \"locator\":     \"" + jsonEscape( locator ) + "\"," );
        Logger.logStd( "    \"reason\":      \"\"" );
        Logger.logStd( "  }" );
    }

    /** Shared explanation line for Nekodetector findings — they can't be
     *  silenced via {@code packScanAcknowledgements} because they're
     *  signature-based malware detection, not heuristics. Surfaced after
     *  each Nekodetector finding so the maintainer isn't left wondering
     *  why no JSON snippet appears.
     *
     *  @return the localized "Nekodetector findings can't be acknowledged"
     *          explanation line */
    private static String nekoCannotAckHint()
    {
        return LocalizationManager.get( "log.gameModPack.nekoCannotAckHint" );
    }

    /** Minimal JSON string-content escaper for the ack-hint log line.
     *  Handles the two characters that would actually break the literal
     *  ({@code \} and {@code "}); the launcher's finding strings don't
     *  contain control chars worth escaping further.
     *
     *  @param s the raw string to escape; {@code null} yields {@code ""}
     *
     *  @return the input with backslashes and double-quotes escaped for
     *          embedding in a JSON string literal */
    private static String jsonEscape( String s )
    {
        if ( s == null ) return "";
        return s.replace( "\\", "\\\\" ).replace( "\"", "\\\"" );
    }

    /**
     * Builds the full launch command, replaces all placeholders, and starts the game process. Delegates to
     * {@link GameModPackLauncher#launch()}.
     *
     * @throws ModpackException if unable to launch the game
     *
     * @since 1.0
     */
    public void startGame() throws ModpackException
    {
        getLauncher().launch();
    }

    /**
     * Runs a force-FULL verify across every mod, library, asset, and runtime
     * file this pack ships, then re-writes the verify-state sidecar so the
     * next launch is fast-path eligible again. Does <em>not</em> spawn the
     * game JVM — purely the integrity sweep half of the pre-launch pipeline.
     *
     * <p>Used by the per-pack "Verify this pack now" button in the modpack
     * detail modal and by the launcher-wide "Verify all game files" action
     * in Settings → Security (which iterates installed packs and calls this
     * on each).</p>
     *
     * @throws ModpackException if any verify-or-repair step fails
     * @since 2026.3
     */
    public void verifyAllFilesNow() throws ModpackException
    {
        getLauncher().verifyAllFilesNow();
    }

    /**
     * Returns the last launched game process, if available and the in-game console is enabled.
     *
     * @return the game Process, or null
     *
     * @since 3.0
     */
    public Process getLastLaunchedProcess() {
        return launcher != null ? launcher.getLastLaunchedProcess() : null;
    }

    /**
     * Finds and returns the content of the most recent Minecraft crash report in this modpack's crash-reports folder.
     *
     * @return the crash report content, or null if no crash report found
     *
     * @since 3.0
     */
    public String getLatestCrashReport() {
        File crashReportsDir = new File( getPackRootFolder(), "crash-reports" );
        if ( !crashReportsDir.exists() || !crashReportsDir.isDirectory() ) {
            return null;
        }

        File[] reports = crashReportsDir.listFiles( ( dir, name ) -> name.endsWith( ".txt" ) );
        if ( reports == null || reports.length == 0 ) {
            return null;
        }

        // Find the most recent crash report (by last modified time)
        File latest = reports[ 0 ];
        for ( File report : reports ) {
            if ( report.lastModified() > latest.lastModified() ) {
                latest = report;
            }
        }

        // Only return if the crash report was created recently (within last 60 seconds)
        if ( System.currentTimeMillis() - latest.lastModified() > 60_000 ) {
            return null;
        }

        try {
            return org.apache.commons.io.FileUtils.readFileToString( latest, "UTF-8" );
        }
        catch ( IOException e ) {
            Logger.logError( LocalizationManager.format( "log.gameModPack.crashReportReadFailed", latest.getName() ) );
            return null;
        }
    }



    /**
     * Returns the on-disk path of this pack's primary logo image, triggering
     * a synchronous cache download if it isn't already cached. Because this
     * can hit the network, call it off the FX thread; use
     * {@link #getPackLogoFilepathRaw()} for a side-effect-free path on the FX
     * thread.
     *
     * @return absolute path to the cached primary logo image file
     */
    public synchronized String getPackLogoFilepath()
    {
        return getEnvironment().getPackLogoFilepath();
    }

    /**
     * Background-image counterpart of {@link #getPackLogoFilepath()}.
     *
     * @return absolute path to the cached primary background image file
     */
    public synchronized String getPackBackgroundFilepath()
    {
        return getEnvironment().getPackBackgroundFilepath();
    }

    /**
     * Returns the on-disk paths of every cached logo this pack declares (the manifest's
     * {@code packLogoURL} may list multiple distinct showcase images). Triggers a
     * synchronous cache download for any missing images — call off the FX thread. Use
     * {@link #getPackLogoFilepathsRaw()} on the FX thread.
     *
     * @return ordered list of cached logo paths; never {@code null}, possibly empty
     * @since 3.6
     */
    public synchronized java.util.List< String > getPackLogoFilepaths()
    {
        return getEnvironment().getPackLogoFilepaths();
    }

    /**
     * Background-image counterpart of {@link #getPackLogoFilepaths()}.
     *
     * @return ordered list of cached background paths; never {@code null}, possibly empty
     * @since 3.6
     */
    public synchronized java.util.List< String > getPackBackgroundFilepaths()
    {
        return getEnvironment().getPackBackgroundFilepaths();
    }

    /**
     * Returns the expected on-disk path for the pack logo image WITHOUT
     * triggering the image-download cache step. Use this when running on the
     * FX thread to avoid a synchronous network fetch blocking the rendering
     * loop — caller checks {@link File#exists()} and falls back to a
     * placeholder if the file isn't on disk yet. The async warm-up (which
     * actually fetches missing images) should run on a background thread
     * via {@link #cacheImages}.
     *
     * @since 2026.3
     */
    public synchronized String getPackLogoFilepathRaw()
    {
        return getEnvironment().getRawLogoFilePath();
    }

    /**
     * Background-image counterpart of {@link #getPackLogoFilepathRaw}. Same
     * "don't trigger cacheImages from the FX thread" rationale — see that
     * method's docs.
     *
     * @since 2026.3
     */
    public synchronized String getPackBackgroundFilepathRaw()
    {
        return getEnvironment().getRawBackgroundFilePath();
    }

    /**
     * Side-effect-free counterpart of {@link #getPackLogoFilepaths()} — returns the paths of
     * every declared logo already cached on disk WITHOUT triggering a network fetch. Safe to
     * call on the FX thread; a not-yet-cached image is simply absent from the list.
     *
     * @return ordered list of cached logo paths; never {@code null}, possibly empty
     * @since 3.6
     */
    public synchronized java.util.List< String > getPackLogoFilepathsRaw()
    {
        return getEnvironment().getRawLogoFilePaths();
    }

    /**
     * Side-effect-free counterpart of {@link #getPackBackgroundFilepaths()}.
     *
     * @return ordered list of cached background paths; never {@code null}, possibly empty
     * @since 3.6
     */
    public synchronized java.util.List< String > getPackBackgroundFilepathsRaw()
    {
        return getEnvironment().getRawBackgroundFilePaths();
    }

    /**
     * Returns true if this pack ships its own background image (i.e. the manifest specified a
     * {@code packBackgroundURL} other than the bundled default). Vanilla versions and any
     * modded pack whose manifest left the field blank both return false here — both end up
     * with {@link ModPackConstants#MODPACK_DEFAULT_BG_URL} as their backing URL, which the
     * environment dutifully downloads into a local cached file. That cache file isn't useful
     * for hero-card rendering (the bundled-default PNG path doesn't render cleanly through
     * the {@code -fx-background-image} pipeline), so the UI layer asks this method first and
     * switches to a procedural background when it returns false.
     *
     * @return true iff the pack has its own custom background image
     *
     * @since 3.2
     */
    public boolean hasCustomBackground()
    {
        return packBackgroundURL != null
                && packBackgroundURL.all().stream()
                                    .anyMatch( u -> !u.equals( ModPackConstants.MODPACK_DEFAULT_BG_URL ) );
    }

    /**
     * Returns true if this pack ships its own logo image (i.e. the manifest specified a
     * {@code packLogoURL} other than the bundled default). Symmetric to {@link #hasCustomBackground()}.
     *
     * @return true iff the pack has its own custom logo image
     *
     * @since 3.2
     */
    public boolean hasCustomLogo()
    {
        return packLogoURL != null
                && packLogoURL.all().stream()
                              .anyMatch( u -> !u.equals( ModPackConstants.MODPACK_DEFAULT_LOGO_URL ) );
    }

    /** Source URL of the pack's logo image as declared in the manifest. Distinct from
     *  {@link #getPackLogoFilepath()} — the latter triggers a download into a local cache the
     *  first time it's called, which is fine for installed packs but expensive when we have
     *  dozens of available-but-not-installed packs to render. Use this URL with JavaFX's
     *  {@code Image(url, true)} to fetch asynchronously without touching the cache machinery.
     *
     *  @since 3.2 */
    public String getPackLogoURL()
    {
        return packLogoURL != null ? packLogoURL.first() : null;
    }

    /** Number of logo URLs the manifest declares (1 for a bare string, N for an array,
     *  0 if unset). Lets the UI know how many distinct showcase logos to expect / prefetch
     *  for the multi-image cycle.
     *
     *  @since 3.6 */
    public int getPackLogoUrlCount()
    {
        return packLogoURL != null ? packLogoURL.all().size() : 0;
    }

    /** Number of background URLs the manifest declares. See {@link #getPackLogoUrlCount()}.
     *
     *  @since 3.6 */
    public int getPackBackgroundUrlCount()
    {
        return packBackgroundURL != null ? packBackgroundURL.all().size() : 0;
    }

    /** Source URL of the pack's background image as declared in the manifest. Same rationale
     *  as {@link #getPackLogoURL()} — use directly with {@code Image(url, true)} to avoid the
     *  file-cache trigger that {@link #getPackBackgroundFilepath()} would cause.
     *
     *  @since 3.2 */
    public String getPackBackgroundURL()
    {
        return packBackgroundURL != null ? packBackgroundURL.first() : null;
    }

    /**
     * Sets the progress provider used to surface verify / download / launch
     * progress for this pack, and invalidates the cached launcher so the next
     * {@link #getLauncher()} call rebinds to the new provider. Call this once
     * per launch session with a fresh tracker bridge; see
     * {@link #swapProgressProviderTransiently} for the in-flight,
     * cache-preserving variant.
     *
     * @param progressProvider the progress sink for this pack's operations
     *                         (may be {@code null} to detach)
     */
    public void setProgressProvider( GameModPackProgressProvider progressProvider )
    {
        this.progressProvider = progressProvider;
        // Reset the launcher so it picks up the new progress provider on its
        // next getLauncher() call. This matters between launch sessions —
        // LauncherCore.play and VerifyAction both pass in a fresh tracker
        // bridge and need the next-spawned launcher to capture that bridge
        // in its constructor.
        this.launcher = null;
    }

    /**
     * Like {@link #setProgressProvider} but does <em>not</em> invalidate the
     * cached launcher. Used by {@code GameModPackLauncher#doSecurityScan} to
     * temporarily swap the pack's progress provider so the scanner's stdout
     * routes to the SECURITY_SCAN tracker row, then swap back. The active
     * launcher is the one that spawned the security-scan call and will, in
     * a few statements, spawn the game JVM — nullifying its cached
     * reference here mid-flight orphans {@link #getLastLaunchedProcess()}
     * (it reads the cached launcher's lastLaunchedProcess field) and leaves
     * {@code LauncherCore.play}'s post-spawn flow looking at a null process,
     * so the console-GUI swap is skipped and the launcher sits stuck on the
     * progress screen even though the game is running.
     *
     * @param progressProvider the progress sink to install for the duration
     *                         of the in-flight operation
     */
    public void swapProgressProviderTransiently( GameModPackProgressProvider progressProvider )
    {
        this.progressProvider = progressProvider;
    }

    /**
     * Downloads and caches this pack's logo and background images. Performs
     * network I/O, so call from a background thread (e.g. the async warm-up
     * for the modpack cards). Delegates to
     * {@link GameModPackEnvironment#cacheImages()}.
     */
    public synchronized void cacheImages()
    {
        getEnvironment().cacheImages();
    }

    /**
     * Creates all on-disk directories this pack needs (bin, mods, config,
     * natives, and — in client mode — resourcepacks and shaderpacks).
     * Delegates to {@link GameModPackEnvironment#prepareEnvironment()}.
     */
    public void prepareEnvironment()
    {
        getEnvironment().prepareEnvironment();
    }

    /** URL of the manifest JSON this pack was loaded from. Package-visible
     *  field populated by the fetch / factory paths. */
    String manifestUrl;

    /**
     * Returns the URL of the manifest JSON this pack was loaded from.
     *
     * @return the source manifest URL, or {@code null} for packs built via a
     *         factory path that doesn't carry one
     */
    public String getManifestUrl() {
        return manifestUrl;
    }

    /** Hex SHA-256 of the manifest JSON body as it existed when this pack
     *  was loaded. Used by {@link VerifyState} to detect manifest changes
     *  between launches. Set by {@link GameModPackFetcher} after parsing;
     *  transient because it's a runtime fingerprint of the source bytes,
     *  not part of the pack content itself. May be {@code null} on packs
     *  built via the createVanillaModPack / createFailedModPack factory
     *  paths since those don't have a real manifest body. */
    private transient String manifestContentSha256;

    /**
     * Returns the hex SHA-256 fingerprint of the manifest JSON body captured
     * when this pack was loaded.
     *
     * @return the manifest-body SHA-256, or {@code null} for factory-built
     *         packs that have no real manifest body
     */
    public String getManifestContentSha256() { return manifestContentSha256; }

    /**
     * Package-private setter — only {@link GameModPackFetcher} populates this.
     *
     * @param sha256 the hex SHA-256 of the manifest body to record
     */
    void setManifestContentSha256( String sha256 ) {
        this.manifestContentSha256 = sha256;
    }

    /**
     * Flag indicating this is a vanilla (non-Forge) Minecraft version.
     */
    private transient boolean vanillaVersion = false;

    /**
     * For vanilla versions, the Minecraft version ID (e.g. "1.20.4").
     */
    private transient String vanillaMinecraftVersion = null;

    /**
     * Returns true if this is a vanilla (non-Forge) Minecraft version.
     *
     * @return {@code true} when this pack is a plain Minecraft version with
     *         no modloader
     */
    public boolean isVanillaVersion() {
        return vanillaVersion;
    }

    /**
     * Creates a GameModPack representing a vanilla (non-Forge) Minecraft version.
     *
     * @param versionId the Minecraft version ID (e.g. "1.20.4")
     *
     * @return a GameModPack configured for vanilla launch
     *
     * @since 3.0
     */
    public static GameModPack createVanillaModPack( String versionId ) {
        GameModPack pack = new GameModPack();
        pack.packName = LocalizationManager.format( "modpack.vanilla.name", versionId );
        pack.packVersion = versionId;
        pack.packURL = "https://minecraft.net";
        pack.packLogoURL = StringOrArray.of( ModPackConstants.MODPACK_DEFAULT_LOGO_URL );
        pack.packBackgroundURL = StringOrArray.of( ModPackConstants.MODPACK_DEFAULT_BG_URL );
        pack.packMinRAMGB = "2";
        pack.packMods = new ArrayList<>();
        pack.vanillaVersion = true;
        pack.vanillaMinecraftVersion = versionId;
        return pack;
    }

    /**
     * Creates a GameModPack representing a failed load attempt. The pack name indicates the failure so the user can
     * see that something went wrong in the pack list.
     *
     * @param manifestUrl  the URL that failed to load
     * @param errorMessage the error message, or null
     *
     * @return a GameModPack with a descriptive error name
     *
     * @since 3.0
     */
    public static GameModPack createFailedModPack( String manifestUrl, String errorMessage )
    {
        GameModPack pack = new GameModPack();
        // Extract a short identifier from the URL for display
        String shortUrl = manifestUrl;
        if ( shortUrl.length() > 40 ) {
            shortUrl = "..." + shortUrl.substring( shortUrl.length() - 37 );
        }
        pack.packName = LocalizationManager.format( "modpack.failed.name", shortUrl );
        pack.packVersion = LocalizationManager.get( "modpack.failed.version" );
        pack.packMinRAMGB = "2";
        pack.packLogoURL = StringOrArray.of( ModPackConstants.MODPACK_DEFAULT_LOGO_URL );
        pack.packBackgroundURL = StringOrArray.of( ModPackConstants.MODPACK_DEFAULT_BG_URL );
        return pack;
    }

    /** True when this pack was built from an {@link InstallIndex.Entry} stub and
     *  hasn't yet had its full manifest loaded. Stub instances carry just the
     *  card-rendering subset (name, version, image URLs, RAM, flags); fields
     *  like {@code packMods}, {@code packForgeURL}, scan exclusions, etc. are
     *  null until {@link InstallIndex#upgradeStubToFull} or any of the manifest
     *  fetch paths populate them.
     *
     *  <p>Marked {@code transient} so it doesn't round-trip through Gson when
     *  the full manifest body is parsed into a real pack — a freshly-loaded
     *  pack always reads back with {@code stub == false}.</p> */
    private transient boolean stub;

    /** Returns true when this pack was painted from an {@link InstallIndex}
     *  entry and the full manifest hasn't been loaded yet. Callers that need
     *  fields beyond the card-rendering subset (mods, forge URL, scan
     *  exclusions, etc.) should treat a stub as "data may be null — load
     *  the full manifest first." See {@link GameModPackFetcher#get} / the
     *  background revalidate in {@link GameModPackManager}. */
    public boolean isStub() { return stub; }

    /** Marks this pack as a stub (card-rendering subset only, full manifest
     *  not yet loaded). See {@link #isStub()}. */
    void markAsStub() { this.stub = true; }

    /**
     * Builds a stub GameModPack from a persisted {@link InstallIndex.Entry}.
     * Used by the cold-start fast-path so the main menu can paint cards from
     * one index-file read instead of N per-manifest cache reads. The returned
     * pack carries only the card-rendering subset; {@link #isStub} returns
     * true until a real manifest fetch upgrades it.
     *
     * @param manifestUrl the manifest URL this stub stands in for
     * @param entry       the persisted index entry supplying the
     *                    card-rendering field subset
     *
     * @return a stub {@link GameModPack} populated from {@code entry}
     *
     * @since 2026.3
     */
    public static GameModPack createStubFromIndex( String manifestUrl, InstallIndex.Entry entry )
    {
        GameModPack pack = new GameModPack();
        pack.manifestUrl         = manifestUrl;
        pack.packName            = entry.packName;
        pack.packVersion         = entry.packVersion;
        pack.packURL             = entry.packURL;
        pack.packLogoURL         = entry.packLogoURL;
        pack.packLogoSha1        = entry.packLogoSha1;
        pack.packBackgroundURL   = entry.packBackgroundURL;
        pack.packBackgroundSha1  = entry.packBackgroundSha1;
        pack.packMinRAMGB        = entry.packMinRAMGB;
        pack.packUnstable        = entry.packUnstable;
        pack.packCustomDiscordRpc = entry.packCustomDiscordRpc;
        pack.stub                = true;
        return pack;
    }

    /**
     * Returns a placeholder "no mod pack" {@link GameModPack} used as the
     * empty / none selection in the UI. It carries a localized name and
     * version plus the bundled "no mod pack" image, and is not backed by any
     * real manifest.
     *
     * @return a sentinel placeholder pack representing "no selection"
     */
    public static GameModPack NULL_MODPACK() {
        GameModPack nullModPack = new GameModPack();
        nullModPack.packName = LocalizationManager.get( "modpack.none.name" );
        nullModPack.packVersion = LocalizationManager.get( "modpack.none.version" );
        nullModPack.packLogoURL = StringOrArray.of( GUIConstants.URL_MINECRAFT_NO_MOD_PACK_IMAGE );
        return nullModPack;
    }
}
