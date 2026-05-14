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
    private transient GameModPackProgressProvider progressProvider  = null;
    private transient GameModPackEnvironment      environment       = null;
    private transient GameModPackLauncher         launcher          = null;

    /**
     * Returns the lazily-initialized environment handler for this modpack.
     */
    private GameModPackEnvironment getEnvironment()
    {
        if ( environment == null ) {
            environment = new GameModPackEnvironment( this );
        }
        return environment;
    }

    /**
     * Returns the lazily-initialized launcher for this modpack.
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
     * The modloader type identifier for this pack — one of
     * {@link com.micatechnologies.minecraft.launcher.consts.ModPackConstants#MOD_LOADER_FORGE},
     * {@code MOD_LOADER_NEOFORGE}, or {@code MOD_LOADER_FABRIC}. Vanilla
     * packs return {@code null}. Absent / blank in the manifest defaults
     * to Forge for back-compat with every modpack created before the
     * multi-loader work.
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
     *  the new field isn't present in the manifest. */
    String getModLoaderURL() {
        if ( packModLoaderURL != null && !packModLoaderURL.isBlank() ) {
            return packModLoaderURL;
        }
        return packForgeURL;
    }

    /** Loader installer / profile SHA-1 — generalised replacement for
     *  {@code packForgeHash}. Falls back to {@code packForgeHash}. May
     *  be null/empty for loaders that don't hash-verify their meta
     *  artifact. */
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

    private GameModLoader createModLoader() throws ModpackException {
        String type = getModLoaderType();
        String url = getModLoaderURL();
        String hash = getModLoaderHash();
        return switch ( type ) {
            case com.micatechnologies.minecraft.launcher.consts.ModPackConstants.MOD_LOADER_FORGE ->
                    new GameModLoaderForge( url, hash, this );
            case com.micatechnologies.minecraft.launcher.consts.ModPackConstants.MOD_LOADER_FABRIC ->
                    new GameModLoaderFabric( url, hash, this );
            // NeoForge lands in a follow-up commit. Manifests declaring
            // unknown types surface a clear error rather than silently
            // falling back to Forge against the wrong installer.
            default -> throw new ModpackException(
                    "Unsupported modloader type \"" + type + "\" for pack "
                            + getPackName() + " — known types: forge, fabric." );
        };
    }

    /**
     * Get a Forge application object referencing the Forge application jar in this modpack.
     * Backwards-compat alias for callers that haven't migrated to
     * {@link #getModLoader()}. Throws if the pack isn't a Forge pack.
     *
     * @return mod pack MCForgeApp
     *
     * @throws ModpackException if unable to get Forge app object or if the
     *                          pack uses a non-Forge modloader
     */
    GameModLoaderForge getForgeApp() throws ModpackException {
        GameModLoader loader = getModLoader();
        if ( loader instanceof GameModLoaderForge forge ) {
            return forge;
        }
        throw new ModpackException( "Pack " + getPackName()
                + " is not a Forge pack — modloader type is " + getModLoaderType() );
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
        return getForgeApp().getMinecraftVersion();
    }

    /**
     * Get the Forge version of this modpack. Vanilla packs have no Forge loader,
     * so this returns {@code null} for them instead of attempting to construct a
     * {@link GameModLoaderForge} against null URL/hash fields (which previously
     * triggered a misleading "FILE FAILED VERIFICATION" message every time the
     * main-menu card builder asked vanilla packs for a Forge version).
     *
     * @return modpack Forge version, or {@code null} for vanilla packs
     *
     * @throws ModpackException if unable to get Forge version
     */
    @SuppressWarnings( "WeakerAccess" )
    public String getForgeVersion() throws ModpackException {
        if ( vanillaVersion ) {
            return null;
        }
        return getForgeApp().getForgeVersion();
    }

    /**
     * Get the Minecraft library manifest for this modpack.
     *
     * @return Minecraft library manifest
     *
     * @throws ModpackException if unable to get manifest
     */
    public GameLibraryManifest getMinecraftLibraryManifest() throws ModpackException {
        return GameVersionManifest.getMinecraftLibraryManifest( getMinecraftVersion(), this );
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

        Results scanResults = Main.run( scanCoreCount, Path.of( getPackRootFolder() ), emitWalkErrors,
                                        getPackScanExclusions(), logOutput, null );

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
                                                                  getPackScanExclusions(), scanCoreCount ) );
        }
        catch ( IOException e ) {
            // I/O failure during the supplemental walk shouldn't abort the
            // launch on its own — the upstream Nekodetector already completed.
            Logger.logWarningSilent( "Supplemental scan I/O error: "
                                             + e.getClass().getSimpleName() );
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
                        : " (reason: " + matched.reason + ")";
                String line = "Acknowledged scan finding: " + f + reasonSuffix;
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
                Logger.logWarning( "Modpack scan flagged: " + f );
                Logger.logStd( nekoCannotAckHint() );
            }
        }
        if ( stage2 != null ) {
            for ( String f : stage2 ) {
                Logger.logWarning( "Modpack scan flagged: " + f );
                Logger.logStd( nekoCannotAckHint() );
            }
        }
        for ( SupplementalScanner.Finding f : remainingSupp ) {
            if ( f.severity() == SupplementalScanner.Severity.HIGH ) {
                logOutput.apply( "Supplemental infection signal: " + f );
            }
            else {
                logOutput.apply( "Supplemental warning: " + f );
            }
            Logger.logWarning( "Supplemental modpack scan flagged: " + f );
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
            logOutput.apply( "Security scan blocked launch: " + total
                                     + ( total == 1 ? " issue detected." : " issues detected." ) );
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
     *     "fileSha256": "ab12cd34...",
     *     "kind":       "LAUNCHER_CREDENTIAL_FILE_REF",
     *     "locator":    "optifine/Installer.updateLauncherJson",
     *     "reason":     ""
     *   }
     * </pre>
     *
     * <p>The three identity fields come straight from the structured Finding,
     * so they're stable across launcher versions (renaming the user-facing
     * message wording doesn't shift them) and tight enough that a different
     * finding can't accidentally match. Bound to the JAR's SHA-256 so a mod
     * update invalidates the ack cleanly — the new content gets reviewed
     * fresh rather than silently inheriting the old trust.</p>
     */
    private static void emitAcknowledgementHint( SupplementalScanner.Finding f )
    {
        if ( f == null ) return;
        String sha = f.fileSha256();
        if ( sha == null || sha.isBlank() ) {
            // Without a hash we can't produce a stable signature. Tell the user
            // why no hint appears rather than emit an unmatchable snippet.
            Logger.logStd( "No acknowledgement signature available for this finding — "
                                   + "JAR hash could not be computed. The finding will continue "
                                   + "to block launches until the file is removed or repaired." );
            return;
        }
        String kindName = f.kind() == null ? "" : f.kind().name();
        String locator = f.locator() == null ? "" : f.locator();
        Logger.logStd( "To silence this finding, append to the manifest's packScanAcknowledgements:" );
        Logger.logStd( "  {" );
        Logger.logStd( "    \"fileSha256\": \"" + jsonEscape( sha ) + "\"," );
        Logger.logStd( "    \"kind\":       \"" + jsonEscape( kindName ) + "\"," );
        Logger.logStd( "    \"locator\":    \"" + jsonEscape( locator ) + "\"," );
        Logger.logStd( "    \"reason\":     \"\"" );
        Logger.logStd( "  }" );
    }

    /** Shared explanation line for Nekodetector findings — they can't be
     *  silenced via {@code packScanAcknowledgements} because they're
     *  signature-based malware detection, not heuristics. Surfaced after
     *  each Nekodetector finding so the maintainer isn't left wondering
     *  why no JSON snippet appears. */
    private static String nekoCannotAckHint()
    {
        return "This finding is from the malware-specific (Nekodetector) scanner and cannot be "
                + "silenced via packScanAcknowledgements. If you trust this content, change "
                + "the pack's Security Scan Frequency to Disabled in its Advanced settings.";
    }

    /** Minimal JSON string-content escaper for the ack-hint log line.
     *  Handles the two characters that would actually break the literal
     *  ({@code \} and {@code "}); the launcher's finding strings don't
     *  contain control chars worth escaping further. */
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
            Logger.logError( "Failed to read crash report: " + latest.getName() );
            return null;
        }
    }



    public synchronized String getPackLogoFilepath()
    {
        return getEnvironment().getPackLogoFilepath();
    }

    public synchronized String getPackBackgroundFilepath()
    {
        return getEnvironment().getPackBackgroundFilepath();
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
                && !packBackgroundURL.equals( ModPackConstants.MODPACK_DEFAULT_BG_URL );
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
                && !packLogoURL.equals( ModPackConstants.MODPACK_DEFAULT_LOGO_URL );
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
        return packLogoURL;
    }

    /** Source URL of the pack's background image as declared in the manifest. Same rationale
     *  as {@link #getPackLogoURL()} — use directly with {@code Image(url, true)} to avoid the
     *  file-cache trigger that {@link #getPackBackgroundFilepath()} would cause.
     *
     *  @since 3.2 */
    public String getPackBackgroundURL()
    {
        return packBackgroundURL;
    }

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
     */
    public void swapProgressProviderTransiently( GameModPackProgressProvider progressProvider )
    {
        this.progressProvider = progressProvider;
    }

    public synchronized void cacheImages()
    {
        getEnvironment().cacheImages();
    }

    public void prepareEnvironment()
    {
        getEnvironment().prepareEnvironment();
    }

    String manifestUrl;

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

    public String getManifestContentSha256() { return manifestContentSha256; }

    /** Package-private setter — only {@link GameModPackFetcher} populates this. */
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
        pack.packName = "Minecraft " + versionId;
        pack.packVersion = versionId;
        pack.packURL = "https://minecraft.net";
        pack.packLogoURL = ModPackConstants.MODPACK_DEFAULT_LOGO_URL;
        pack.packBackgroundURL = ModPackConstants.MODPACK_DEFAULT_BG_URL;
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
        pack.packName = "[Failed to load] " + shortUrl;
        pack.packVersion = "Error";
        pack.packMinRAMGB = "2";
        pack.packLogoURL = ModPackConstants.MODPACK_DEFAULT_LOGO_URL;
        pack.packBackgroundURL = ModPackConstants.MODPACK_DEFAULT_BG_URL;
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

    void markAsStub() { this.stub = true; }

    /**
     * Builds a stub GameModPack from a persisted {@link InstallIndex.Entry}.
     * Used by the cold-start fast-path so the main menu can paint cards from
     * one index-file read instead of N per-manifest cache reads. The returned
     * pack carries only the card-rendering subset; {@link #isStub} returns
     * true until a real manifest fetch upgrades it.
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

    public static GameModPack NULL_MODPACK() {
        GameModPack nullModPack = new GameModPack();
        nullModPack.packName = "No mod packs installed!";
        nullModPack.packVersion = "N/A";
        nullModPack.packLogoURL = GUIConstants.URL_MINECRAFT_NO_MOD_PACK_IMAGE;
        return nullModPack;
    }
}
