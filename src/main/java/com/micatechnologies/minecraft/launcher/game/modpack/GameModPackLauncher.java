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
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.util.List;

import com.micatechnologies.minecraft.launcher.config.ConfigManager;
import com.micatechnologies.minecraft.launcher.config.GameModeManager;
import com.micatechnologies.minecraft.launcher.consts.ModPackConstants;
import com.micatechnologies.minecraft.launcher.exceptions.ModpackException;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.files.RuntimeManager;
import com.micatechnologies.minecraft.launcher.files.SynchronizedFileManager;
import com.micatechnologies.minecraft.launcher.game.auth.MCLauncherAuthManager;
import com.micatechnologies.minecraft.launcher.game.modpack.manifests.GameAssetManifest;
import com.micatechnologies.minecraft.launcher.game.modpack.manifests.GameLibraryManifest;
import com.micatechnologies.minecraft.launcher.game.modpack.manifests.GameVersionManifest;
import com.micatechnologies.minecraft.launcher.utilities.ProcessUtilities;

/**
 * Encapsulates the game launching logic for a {@link GameModPack}, including classpath assembly, JVM argument
 * construction, log4j security configuration, and process creation.
 *
 * @author Mica Technologies
 * @version 1.0
 * @since 3.0
 */
class GameModPackLauncher
{
    /**
     * The modpack instance this launcher operates on.
     */
    private final GameModPack pack;

    /**
     * Optional progress provider for reporting launch progress to the UI.
     */
    private final GameModPackProgressProvider progressProvider;

    /**
     * The last launched game process, if any.
     */
    private Process lastLaunchedProcess = null;

    // region Log4j security config constants

    /**
     * Mojang security-patched log4j config for MC 1.12-1.16.5. Uses PatternLayout with {@code %msg{nolookups}} to
     * mitigate CVE-2021-44228 (Log4Shell).
     */
    private static final String LOG4J_CONFIG_112_116_URL
            = "https://launcher.mojang.com/v1/objects/02937d122c86ce73319ef9975b58896fc1b491d1/log4j2_112-116.xml";
    private static final String LOG4J_CONFIG_112_116_SHA1 = "02937d122c86ce73319ef9975b58896fc1b491d1";
    private static final String LOG4J_CONFIG_112_116_NAME = "log4j2_112-116.xml";

    /**
     * Mojang security-patched log4j config for MC 1.7-1.11.2. Uses RegexFilter to deny {@code ${...}} patterns
     * since older Log4j doesn't support {@code {nolookups}}.
     */
    private static final String LOG4J_CONFIG_17_111_URL
            = "https://launcher.mojang.com/v1/objects/4bb89a97a66f350bc9f73b3ca8509632682aea2e/log4j2_17-111.xml";
    private static final String LOG4J_CONFIG_17_111_SHA1 = "4bb89a97a66f350bc9f73b3ca8509632682aea2e";
    private static final String LOG4J_CONFIG_17_111_NAME = "log4j2_17-111.xml";

    // endregion

    /**
     * Constructs a new launcher for the given modpack.
     *
     * @param pack             the modpack to launch
     * @param progressProvider optional progress provider, may be null
     *
     * @since 3.0
     */
    GameModPackLauncher( GameModPack pack, GameModPackProgressProvider progressProvider )
    {
        this.pack = pack;
        this.progressProvider = progressProvider;
    }

    /**
     * Returns the last launched game process, if available.
     *
     * @return the game Process, or null
     *
     * @since 3.0
     */
    Process getLastLaunchedProcess()
    {
        return lastLaunchedProcess;
    }

    /**
     * Verify all local modpack files, download files if necessary, and compile the classpath String for running this
     * modpack.
     *
     * @return modpack classpath String
     *
     * @throws ModpackException if unable to update modpack or build classpath
     *
     * @since 3.0
     */
    String buildClasspath() throws ModpackException
    {
        if ( progressProvider != null ) {
            progressProvider.setCurrText( "Preparing modpack..." );
        }

        // Decide and install the verify mode for this launch. The mode is read
        // by ManagedGameFile.verifyLocalFile inside every fetchLatest* /
        // buildXClasspath call below. FULL is the historical behaviour (hash
        // every file); FAST_PATH accepts files on existence + non-zero size.
        //
        // Step 2 of 3.3 lands this plumbing but ManagedGameFile still always
        // does the FULL check regardless of the value — the decision result
        // is observable for verification but doesn't yet change behaviour.
        // Step 3 lights up the FAST_PATH branch and the sidecar write that
        // makes subsequent launches eligible.
        //
        // The user-facing controls (per-pack toggle + global force flag) land
        // in step 4; for now both inputs are hardcoded false so the decision
        // is driven entirely by manifest-hash + TTL + sidecar presence.
        final LaunchVerifyMode chosenMode = decideLaunchVerifyMode();
        Logger.logDebug( "Launch verify mode for " + pack.getPackName() + ": " + chosenMode );
        LaunchVerifyMode prevMode = ManagedGameFile.getCurrentVerifyMode();
        ManagedGameFile.setCurrentVerifyMode( chosenMode );
        try {
            String classpath = buildClasspathInner();
            // Persist the sidecar ONLY after a successful FULL verify. A FAST_PATH
            // run didn't validate anything new, so bumping verifiedAt would let
            // the TTL never bite — defeating the staleness safety net. We do
            // refresh the manifestSha256 in case it ever drifts (shouldn't,
            // since the decision required it to match) but the verifiedAt
            // stays anchored to the last actual hash sweep.
            if ( chosenMode == LaunchVerifyMode.FULL
                    && pack.getManifestContentSha256() != null ) {
                // Read-modify-write so the scan-tracking fields (lastScannedAt /
                // lastScannedManifestSha256) the scan step writes aren't clobbered
                // by a verify-only update.
                VerifyState existing = VerifyState.loadForPack( pack );
                VerifyState fresh = VerifyState.successfulVerify(
                        existing, pack.getManifestContentSha256() );
                VerifyState.saveForPack( pack, fresh );
                Logger.logDebug( "Wrote verify state for " + pack.getPackName()
                                         + " at " + fresh.verifiedAt );
            }
            return classpath;
        }
        finally {
            // Always restore so a subsequent launch starts from a known state
            // regardless of how this one ended.
            ManagedGameFile.setCurrentVerifyMode( prevMode );
        }
    }

    /**
     * Rolls up every fast-path eligibility check into a single decision for
     * this launch. See {@link VerifyState#decideMode} for the conditions.
     */
    private LaunchVerifyMode decideLaunchVerifyMode()
    {
        // Honor the per-pack "always verify" opt-out. The launcher-wide
        // "force-full-verify-next-launch" lever isn't a separate flag in
        // 3.3's design — the user-facing global action (Settings → Security
        // → "Verify all game files now") synchronously verifies every pack
        // by calling pack.verifyAllFilesNow() rather than scheduling a flag
        // for the next launch, so no flag plumbing here.
        boolean perPackOptOut = pack.getManifestUrl() != null
                && ConfigManager.getAlwaysVerifyOnLaunch( pack.getManifestUrl() );
        VerifyState existing = VerifyState.loadForPack( pack );
        return VerifyState.decideMode(
                existing,
                pack.getManifestContentSha256(),
                VerifyState.DEFAULT_TTL_MS,
                perPackOptOut,
                /* globalForceFullVerify */ false );
    }

    /**
     * Force-runs {@link #buildClasspath()} in {@link LaunchVerifyMode#FULL}
     * mode regardless of fast-path eligibility, then discards the resulting
     * classpath. Used by the "Verify this pack now" / "Verify all game files"
     * UI actions in step 4. The sidecar gets written automatically by
     * buildClasspath on success since this is a FULL-mode run.
     */
    void verifyAllFilesNow() throws ModpackException
    {
        LaunchVerifyMode prev = ManagedGameFile.getCurrentVerifyMode();
        ManagedGameFile.setCurrentVerifyMode( LaunchVerifyMode.FULL );
        try {
            buildClasspathForceFull();
        }
        finally {
            ManagedGameFile.setCurrentVerifyMode( prev );
        }
    }

    /**
     * Variant of {@link #buildClasspath()} that hardcodes FULL verify and
     * skips the per-pack opt-out / global-flag decision. Used by the
     * verify-now actions; not by the normal Play path.
     */
    private void buildClasspathForceFull() throws ModpackException
    {
        Logger.logDebug( "Force-full-verify run for " + pack.getPackName() );
        LaunchVerifyMode prevMode = ManagedGameFile.getCurrentVerifyMode();
        ManagedGameFile.setCurrentVerifyMode( LaunchVerifyMode.FULL );
        try {
            buildClasspathInner();
            if ( pack.getManifestContentSha256() != null ) {
                VerifyState existing = VerifyState.loadForPack( pack );
                VerifyState fresh = VerifyState.successfulVerify(
                        existing, pack.getManifestContentSha256() );
                VerifyState.saveForPack( pack, fresh );
            }
        }
        finally {
            ManagedGameFile.setCurrentVerifyMode( prevMode );
        }
    }

    /** The actual buildClasspath body. Extracted so {@link #buildClasspath()}
     *  can wrap it in the verify-mode install / restore lifecycle without
     *  the body needing to know that ceremony exists. */
    private String buildClasspathInner() throws ModpackException
    {
        // The four pre-launch download stages have no real data dependency on each
        // other within a single launch:
        //   - Modpack content (mods + configs + ...) writes under <packRoot>/{mods,
        //     config,resourcepacks,shaderpacks}
        //   - Forge libraries writes under <packRoot>/libraries/forge-side
        //   - MC libraries + assets writes under <packRoot>/libraries + the shared
        //     assets folder
        //   - JRE install writes under the launcher-wide runtime folder
        // None of these touch the same file paths. Run them as parallel CompletableFutures
        // when a tracker bridge is wired up (the Play path always provides one).
        //
        // The Forge processors step (step 5) genuinely depends on having both Forge libs
        // AND MC libs AND the JRE in place — it's an external java -jar invocation against
        // the installed Forge installer's processor scripts. So it joins after the parallel
        // group. Security scan (step 6) reads <packRoot>/mods and the libraries we just
        // downloaded, so it also joins after.
        //
        // For headless callers without a bridge (no GUI), the parallel orchestration still
        // works — the handles just don't drive any UI. Per-step error handling is wired
        // through each branch's try/catch so a failure in one branch marks its row failed
        // and surfaces the exception; sibling branches finish naturally for now (step 4
        // will add cancellation propagation).

        final LaunchTrackerProgressBridge bridge =
                ( progressProvider instanceof LaunchTrackerProgressBridge )
                        ? (LaunchTrackerProgressBridge) progressProvider
                        : null;

        String forgeAssetClasspath = "";
        final GameLibraryManifest libraryManifest;
        final String minecraftAssetClasspath;

        if ( !pack.isVanillaVersion() ) {
            // Three parallel branches for a Forge launch: modpack content / Forge libs /
            // (MC libs + assets → JRE install). The third branch is sequential internally
            // because the JRE install depends on the library manifest fetched at the top of
            // the same branch.
            final StepProgressHandle modpackContentH = handleFor( bridge,
                    LaunchProgressTracker.StepId.MODPACK_CONTENT );
            final StepProgressHandle forgeLibsH = handleFor( bridge,
                    LaunchProgressTracker.StepId.FORGE_LIBS );
            final StepProgressHandle mcLibsH = handleFor( bridge,
                    LaunchProgressTracker.StepId.MC_LIBS_ASSETS );
            final StepProgressHandle jreH = handleFor( bridge,
                    LaunchProgressTracker.StepId.JRE_INSTALL );

            java.util.concurrent.CompletableFuture< Void > branchModpackContent =
                    java.util.concurrent.CompletableFuture.runAsync( () -> {
                        try {
                            doModpackContent( modpackContentH );
                        }
                        catch ( Throwable t ) {
                            throw rethrowAsCompletion( t );
                        }
                    } );

            java.util.concurrent.CompletableFuture< String > branchForgeLibs =
                    java.util.concurrent.CompletableFuture.supplyAsync( () -> {
                        try {
                            return doForgeLibs( forgeLibsH );
                        }
                        catch ( Throwable t ) {
                            throw rethrowAsCompletion( t );
                        }
                    } );

            java.util.concurrent.CompletableFuture< McLibsAndJreResult > branchMcLibsJre =
                    java.util.concurrent.CompletableFuture.supplyAsync( () -> {
                        try {
                            return doMcLibsThenJre( mcLibsH, jreH );
                        }
                        catch ( Throwable t ) {
                            throw rethrowAsCompletion( t );
                        }
                    } );

            try {
                java.util.concurrent.CompletableFuture.allOf( branchModpackContent,
                                                               branchForgeLibs,
                                                               branchMcLibsJre ).get();
                forgeAssetClasspath = branchForgeLibs.get();
                McLibsAndJreResult mcResult = branchMcLibsJre.get();
                libraryManifest = mcResult.manifest;
                minecraftAssetClasspath = mcResult.classpath;
            }
            catch ( InterruptedException ie ) {
                // The launch was interrupted (cancel button, parent thread interrupted,
                // etc.). Cancel siblings best-effort, restore the interrupt flag, and
                // surface the cancellation as a ModpackException so the caller can
                // navigate back. CompletableFuture.cancel(true) is best-effort — most
                // blocking HTTP reads don't respond to Thread.interrupt, so siblings
                // may finish in the background, but the user has already moved on.
                Thread.currentThread().interrupt();
                cancelSiblings( branchModpackContent, branchForgeLibs, branchMcLibsJre );
                throw new ModpackException( "Pre-launch cancelled", ie );
            }
            catch ( java.util.concurrent.ExecutionException ee ) {
                // One branch failed. Cancel the rest so the user isn't watching a
                // half-cancelled progress card finish three more steps after the X
                // already appeared on the failed row.
                cancelSiblings( branchModpackContent, branchForgeLibs, branchMcLibsJre );
                throw unwrapModpackException( ee );
            }
            catch ( java.util.concurrent.CompletionException ce ) {
                cancelSiblings( branchModpackContent, branchForgeLibs, branchMcLibsJre );
                throw unwrapModpackException( ce );
            }

            // --- Step 5: post-install patching (Forge / NeoForge processors) ---
            // Sequential, depends on MC libs + loader libs + JRE all present.
            // Skipped entirely for loaders without a post-install step
            // (Fabric is a runtime loader — KnotClient patches in-memory at
            // launch rather than at install). Skipping the call avoids
            // logging a "running" + "done" pair for a no-op.
            if ( pack.usesPostInstallSteps() ) {
                final StepProgressHandle procH = handleFor( bridge,
                        LaunchProgressTracker.StepId.FORGE_PROCESSORS );
                doForgeProcessors( procH, libraryManifest.getRequiredRuntimeComponent() );
            }
        }
        else {
            // Vanilla packs have no modpack content or Forge stages. Just MC libs + JRE
            // sequentially, then scan. The "parallel" orchestration would be just one branch
            // — keep it serial to avoid the CompletableFuture overhead on the simpler path.
            final StepProgressHandle mcLibsH = handleFor( bridge,
                    LaunchProgressTracker.StepId.MC_LIBS_ASSETS );
            final StepProgressHandle jreH = handleFor( bridge,
                    LaunchProgressTracker.StepId.JRE_INSTALL );
            McLibsAndJreResult vanillaResult = doMcLibsThenJre( mcLibsH, jreH );
            libraryManifest = vanillaResult.manifest;
            minecraftAssetClasspath = vanillaResult.classpath;
        }

        // --- Step 6: Security scan ---
        // Sequential after everything else: scans the mods we just synced and the
        // libraries we just downloaded.
        final StepProgressHandle scanH = handleFor( bridge,
                LaunchProgressTracker.StepId.SECURITY_SCAN );
        doSecurityScan( scanH );

        // Combine classpaths
        if ( !forgeAssetClasspath.isEmpty() && !forgeAssetClasspath.endsWith( File.pathSeparator ) ) {
            forgeAssetClasspath += File.pathSeparator;
        }

        return forgeAssetClasspath + minecraftAssetClasspath;
    }

    /** Constructs a handle for the given step. Bridge-aware: returns a real
     *  {@link StepProgressHandle} when a bridge is present, or {@code null} for
     *  headless / non-tracker callers. */
    private static StepProgressHandle handleFor( LaunchTrackerProgressBridge bridge,
                                                  LaunchProgressTracker.StepId stepId )
    {
        return bridge != null ? bridge.handleFor( stepId ) : null;
    }

    /** Best-effort cancellation of every in-flight sibling future when one
     *  fails or the launch is cancelled. {@link java.util.concurrent.CompletableFuture#cancel(boolean)}
     *  doesn't actually interrupt the underlying worker thread on modern JDKs —
     *  it just marks the future cancelled — but the failed-launch unwind path
     *  has already navigated away, so siblings finishing in the background
     *  isn't user-visible. */
    private static void cancelSiblings( java.util.concurrent.CompletableFuture< ? >... futures )
    {
        for ( java.util.concurrent.CompletableFuture< ? > f : futures ) {
            try { f.cancel( true ); }
            catch ( Throwable ignored ) { /* best-effort */ }
        }
    }

    /** Wraps a non-{@link CompletionException} throwable so it can propagate out
     *  of a CompletableFuture without being mangled. Pure unwrapping helper. */
    private static java.util.concurrent.CompletionException rethrowAsCompletion( Throwable t )
    {
        if ( t instanceof java.util.concurrent.CompletionException ce ) {
            return ce;
        }
        return new java.util.concurrent.CompletionException( t );
    }

    /** Unwraps the actual root cause from CompletionException / ExecutionException
     *  layers and returns it as a {@link ModpackException}. Anything that isn't
     *  already a ModpackException gets wrapped. */
    private static ModpackException unwrapModpackException( Throwable t )
    {
        Throwable cause = t;
        while ( cause != null
                && ( cause instanceof java.util.concurrent.CompletionException
                        || cause instanceof java.util.concurrent.ExecutionException )
                && cause.getCause() != null ) {
            cause = cause.getCause();
        }
        if ( cause instanceof ModpackException me ) {
            return me;
        }
        return new ModpackException(
                cause != null && cause.getMessage() != null
                        ? cause.getMessage()
                        : "Pre-launch step failed",
                cause );
    }

    /** Holds the two return values from the MC libs + JRE sequential branch. */
    private record McLibsAndJreResult( GameLibraryManifest manifest, String classpath ) {}

    // =========================================================================
    //  Per-step bodies — extracted so they're callable both sequentially (vanilla
    //  scan path) and in parallel (Forge content/libs/MC branches). Each method
    //  drives its own step lifecycle on the handle it receives: markRunning at
    //  entry, markFailed in the catch path, markDone on success.
    // =========================================================================

    private void doModpackContent( StepProgressHandle handle ) throws ModpackException
    {
        if ( handle != null ) handle.markRunning();
        try {
            GameModPackFileSync fileSync = new GameModPackFileSync( pack,
                    handle != null ? handle : progressProvider );
            if ( handle != null ) {
                handle.startProgressSection( "Downloading mods...", 15.0 );
            }
            fileSync.fetchLatestMods();
            if ( Lwjgl2ArmPatcher.isNeeded( pack.getMinecraftVersion() ) ) {
                Lwjgl2ArmPatcher.disableIncompatibleMods(
                        pack.getPackRootFolder() + File.separator + "mods" );
            }
            if ( handle != null ) {
                handle.endProgressSection( "Mods ready" );
                handle.startProgressSection( "Downloading configs and resources...", 5.0 );
            }
            fileSync.fetchLatestConfigs();
            fileSync.fetchLatestResourcePacks();
            fileSync.fetchLatestShaderPacks();
            fileSync.fetchLatestInitialFiles();
            pack.cacheImages();
            if ( handle != null ) {
                handle.endProgressSection( "Configs and resources ready" );
                handle.markDone();
            }
        }
        catch ( Throwable t ) {
            if ( handle != null ) handle.markFailed( extractMessage( t ) );
            if ( t instanceof ModpackException me ) throw me;
            if ( t instanceof RuntimeException re ) throw re;
            throw new ModpackException( "Modpack content sync failed", t );
        }
    }

    private String doForgeLibs( StepProgressHandle handle ) throws ModpackException
    {
        if ( handle != null ) handle.markRunning();
        try {
            GameModLoader loader = pack.getModLoader();
            String loaderName = loader.getName();
            if ( handle != null ) {
                handle.startProgressSection( "Downloading " + loaderName + " libraries...", 15.0 );
            }
            String cp = loader.buildClasspath(
                    GameModeManager.getCurrentGameMode(),
                    handle != null ? handle : progressProvider );
            if ( handle != null ) {
                handle.endProgressSection( loaderName + " libraries ready" );
                handle.markDone();
            }
            return cp;
        }
        catch ( Throwable t ) {
            if ( handle != null ) handle.markFailed( extractMessage( t ) );
            if ( t instanceof ModpackException me ) throw me;
            if ( t instanceof RuntimeException re ) throw re;
            throw new ModpackException( "Modloader library sync failed", t );
        }
    }

    private McLibsAndJreResult doMcLibsThenJre( StepProgressHandle mcHandle,
                                                 StepProgressHandle jreHandle ) throws ModpackException
    {
        // MC libs + assets first
        if ( mcHandle != null ) mcHandle.markRunning();
        final GameLibraryManifest libraryManifest;
        final String classpath;
        try {
            if ( mcHandle != null ) {
                mcHandle.startProgressSection(
                        "Downloading Minecraft libraries and assets...",
                        pack.isVanillaVersion() ? 40.0 : 20.0 );
            }
            libraryManifest = GameVersionManifest.getMinecraftLibraryManifest(
                    pack.getMinecraftVersion(), pack );
            classpath = libraryManifest.buildMinecraftClasspath(
                    GameModeManager.getCurrentGameMode(),
                    mcHandle != null ? mcHandle : progressProvider );
            if ( mcHandle != null ) {
                mcHandle.endProgressSection( "Minecraft libraries and assets ready" );
                mcHandle.markDone();
            }
        }
        catch ( Throwable t ) {
            if ( mcHandle != null ) mcHandle.markFailed( extractMessage( t ) );
            if ( t instanceof ModpackException me ) throw me;
            if ( t instanceof RuntimeException re ) throw re;
            throw new ModpackException( "Minecraft libraries / assets sync failed", t );
        }

        // Then JRE install (depends on libraryManifest above)
        final String procRuntimeComponent = libraryManifest.getRequiredRuntimeComponent();
        if ( jreHandle != null ) jreHandle.markRunning();
        try {
            if ( jreHandle != null ) {
                jreHandle.startProgressSection(
                        "Installing Java runtime (" + procRuntimeComponent + ")...",
                        pack.isVanillaVersion() ? 20.0 : 15.0 );
            }
            RuntimeManager.verifyRuntime( procRuntimeComponent, false,
                    jreHandle != null ? jreHandle::setCurrText
                                       : ( progressProvider != null ? progressProvider::setCurrText : null ) );
            if ( jreHandle != null ) {
                jreHandle.submitProgress( "Java runtime ready", 100.0 );
                jreHandle.endProgressSection( "Java runtime ready" );
                jreHandle.markDone();
            }
        }
        catch ( Throwable t ) {
            if ( jreHandle != null ) jreHandle.markFailed( extractMessage( t ) );
            if ( t instanceof ModpackException me ) throw me;
            if ( t instanceof RuntimeException re ) throw re;
            throw new ModpackException( "Java runtime install failed", t );
        }

        return new McLibsAndJreResult( libraryManifest, classpath );
    }

    private void doForgeProcessors( StepProgressHandle handle, String procRuntimeComponent )
            throws ModpackException
    {
        if ( handle != null ) handle.markRunning();
        try {
            if ( handle != null ) {
                handle.startProgressSection( "Patching game files...", 10.0 );
            }
            pack.getModLoader().runPostInstallSteps( GameModeManager.getCurrentGameMode(),
                    handle != null ? handle : progressProvider,
                    procRuntimeComponent );
            if ( handle != null ) {
                handle.endProgressSection( "Game files patched" );
                handle.markDone();
            }
        }
        catch ( Throwable t ) {
            if ( handle != null ) handle.markFailed( extractMessage( t ) );
            if ( t instanceof ModpackException me ) throw me;
            if ( t instanceof RuntimeException re ) throw re;
            throw new ModpackException( "Forge processors run failed", t );
        }
    }

    private void doSecurityScan( StepProgressHandle handle ) throws ModpackException
    {
        // Resolve the effective frequency for this pack (per-pack override, then
        // global default) and ask the policy whether the scan is due. Skipping
        // here is a safety-tradeoff the user explicitly chose in Settings /
        // modpack-detail Advanced — surface that on the row rather than silently
        // running, otherwise users can't tell their setting is doing anything.
        final com.micatechnologies.minecraft.launcher.game.modpack.ScanFrequency effective =
                com.micatechnologies.minecraft.launcher.config.ConfigManager
                        .effectiveScanFrequencyForPack( pack.getManifestUrl() );
        final com.micatechnologies.minecraft.launcher.game.modpack.VerifyState lastState =
                com.micatechnologies.minecraft.launcher.game.modpack.VerifyState.loadForPack( pack );
        final String currentManifestSha256 = pack.getManifestContentSha256();
        if ( !com.micatechnologies.minecraft.launcher.game.modpack.ScanFrequency.shouldScan(
                effective, lastState, currentManifestSha256 ) ) {
            Logger.logDebug( "Skipping security scan for " + pack.getPackName()
                                     + " — policy=" + effective.name() );
            if ( handle != null ) {
                String reason;
                switch ( effective ) {
                    case DISABLED:        reason = "Scan disabled in settings"; break;
                    case ON_CHANGES_ONLY: reason = "No manifest change since last scan"; break;
                    case DAILY:           reason = "Scanned recently — next scan due tomorrow"; break;
                    default:              reason = "Scan not due this launch"; break;
                }
                handle.markSkipped( reason );
            }
            return;
        }

        if ( handle != null ) handle.markRunning();
        // The scanner reads pack.progressProvider internally for status output (via the
        // logOutput Function it constructs in scanModPackRootFolder). Temporarily redirect
        // that to our scan-step handle so the malware-scan log output lands on the right
        // row, then restore the bridge so anything downstream that reads the provider
        // still sees the canonical reference. progressProvider here is the launcher's
        // own field, which was the same reference pack.progressProvider held when the
        // launcher was constructed.
        final GameModPackProgressProvider previous = progressProvider;
        if ( handle != null ) {
            // swapProgressProviderTransiently (vs. setProgressProvider) so the
            // pack's cached launcher reference — i.e. THIS launcher, which is
            // about to spawn the game JVM and store the Process in its own
            // lastLaunchedProcess field — survives the swap. setProgressProvider
            // nullifies pack.launcher, which would orphan LauncherCore's
            // post-startGame getLastLaunchedProcess() call and leave the
            // launcher stuck on the progress screen even after the game starts.
            pack.swapProgressProviderTransiently( handle );
            handle.startProgressSection( "Scanning for malware...", 10.0 );
        }
        try {
            pack.scanModPackRootFolder();
            // Persist scan tracking so the next launch's shouldScan decision has
            // a real lastScannedAt / lastScannedManifestSha256 to look at.
            // Read-modify-write to preserve the verify-tracking fields.
            try {
                com.micatechnologies.minecraft.launcher.game.modpack.VerifyState existing =
                        com.micatechnologies.minecraft.launcher.game.modpack.VerifyState.loadForPack( pack );
                com.micatechnologies.minecraft.launcher.game.modpack.VerifyState fresh =
                        com.micatechnologies.minecraft.launcher.game.modpack.VerifyState.successfulScan(
                                existing, currentManifestSha256 );
                com.micatechnologies.minecraft.launcher.game.modpack.VerifyState.saveForPack( pack, fresh );
            }
            catch ( Throwable t ) {
                Logger.logWarningSilent( "Could not persist scan state for "
                                                 + pack.getPackName() + ": "
                                                 + t.getClass().getSimpleName() );
            }
            if ( handle != null ) {
                handle.endProgressSection( "Security scan complete" );
                handle.markDone();
            }
        }
        catch ( IOException e ) {
            if ( handle != null ) handle.markFailed( extractMessage( e ) );
            throw new ModpackException( "Unable to scan downloaded files due to an exception!", e );
        }
        catch ( InterruptedException e ) {
            if ( handle != null ) handle.markFailed( extractMessage( e ) );
            throw new ModpackException( "Unable to scan downloaded files due to an interruption!", e );
        }
        catch ( Throwable t ) {
            if ( handle != null ) handle.markFailed( extractMessage( t ) );
            if ( t instanceof ModpackException me ) throw me;
            if ( t instanceof RuntimeException re ) throw re;
            throw new ModpackException( "Security scan failed", t );
        }
        finally {
            // Always restore so the scan-step handle doesn't outlive its phase.
            // Same transient-swap rationale as the entry-side call above.
            pack.swapProgressProviderTransiently( previous );
        }
    }

    /** Pulls a short error message out of a throwable for use in the failed-row
     *  sub-text on the launch progress GUI. */
    private static String extractMessage( Throwable t )
    {
        if ( t == null ) return "Unknown failure";
        if ( t.getMessage() != null && !t.getMessage().isEmpty() ) return t.getMessage();
        return t.getClass().getSimpleName();
    }

    /**
     * Builds the full launch command, replaces all placeholders, and starts the game process.
     *
     * @throws ModpackException if unable to launch the game
     *
     * @since 3.0
     */
    void launch() throws ModpackException
    {
        // Get classpath, main class and Minecraft args
        String cp = buildClasspath();

        // Determine the required Java version from the Minecraft version manifest
        GameLibraryManifest libraryManifest = pack.getMinecraftLibraryManifest();

        String minecraftMainClass;
        String minecraftArgs = "";

        if ( pack.isVanillaVersion() ) {
            // Vanilla launch: use mainClass and args from the vanilla manifest
            try {
                minecraftMainClass = libraryManifest.getVanillaMainClass();
                if ( minecraftMainClass == null ) {
                    minecraftMainClass = "net.minecraft.client.main.Main";
                }
            }
            catch ( Exception e ) {
                minecraftMainClass = "net.minecraft.client.main.Main";
            }

            if ( GameModeManager.isClient() ) {
                minecraftArgs = libraryManifest.getGameArguments();
            }
        }
        else {
            // Modded launch — dispatch through the polymorphic modloader.
            GameModLoader loader = pack.getModLoader();
            if ( GameModeManager.isClient() ) {
                minecraftMainClass = loader.getMinecraftMainClass();
            }
            else {
                // Server-mode main class is still Forge-specific —
                // Fabric / NeoForge server entry points will be wired
                // through the modloader interface in a follow-up
                // commit (needs a getServerMainClass() addition).
                minecraftMainClass = "net.minecraftforge.fml.relauncher.ServerLaunchWrapper";
            }

            // Build game arguments: combine vanilla game args with the
            // loader's extra game args.
            if ( GameModeManager.isClient() ) {
                String vanillaGameArgs = libraryManifest.getGameArguments();
                String loaderGameArgs = loader.getMinecraftArguments();

                if ( !loaderGameArgs.isEmpty() && !vanillaGameArgs.isEmpty() &&
                        !loaderGameArgs.contains( "${auth_player_name}" ) ) {
                    minecraftArgs = vanillaGameArgs + " " + loaderGameArgs;
                }
                else if ( !loaderGameArgs.isEmpty() ) {
                    minecraftArgs = loaderGameArgs;
                }
                else {
                    minecraftArgs = vanillaGameArgs;
                }
            }
        }
        String runtimeComponent = libraryManifest.getRequiredRuntimeComponent();
        int requiredJavaMajorVersion = libraryManifest.getRequiredJavaMajorVersion();
        Logger.logStd( "Minecraft version " + pack.getMinecraftVersion() + " requires runtime " + runtimeComponent +
                               " (Java " + requiredJavaMajorVersion + ")" );

        // Ensure the required Java runtime is available (should already be verified by buildClasspath,
        // but this call is cheap if already cached)
        RuntimeManager.verifyRuntime( runtimeComponent, false );

        if ( progressProvider != null ) {
            progressProvider.setCurrText( "Preparing launch command..." );
        }

        // Build JVM arguments string
        StringBuilder jvmArgs = new StringBuilder();

        // Add custom user JVM args first
        String customJvmArgs = ConfigManager.getCustomJvmArgs();
        if ( customJvmArgs != null && !customJvmArgs.isBlank() ) {
            jvmArgs.append( customJvmArgs ).append( " " );
        }

        // Add min and max RAM
        long minRAMMB = ConfigManager.getMinRam();
        long maxRAMMB = ConfigManager.getMaxRam();
        if ( GameModeManager.isServer() ) {
            RuntimeMXBean bean = ManagementFactory.getRuntimeMXBean();
            List< String > aList = bean.getInputArguments();
            for ( String s : aList ) {
                if ( s.contains( "Xms" ) ) {
                    minRAMMB = Integer.parseInt( s.replaceAll( "\\D+", "" ) );
                    Logger.logDebug( "Configuring min RAM from provided " + s );
                }
                if ( s.contains( "Xmx" ) ) {
                    maxRAMMB = Integer.parseInt( s.replaceAll( "\\D+", "" ) );
                    Logger.logDebug( "Configuring max RAM from provided " + s );
                }
            }
        }
        jvmArgs.append( "-Xms" ).append( minRAMMB ).append( "m " );
        jvmArgs.append( "-Xmx" ).append( maxRAMMB ).append( "m " );

        // Handle logging configuration using Mojang's security-patched log4j configs
        // (CVE-2021-44228 / Log4Shell mitigation). These also use PatternLayout instead of XMLLayout
        // for the console appender, preventing XML clutter in stdout.
        // See: https://www.minecraft.net/en-us/article/important-message--security-vulnerability-java-edition
        applyLog4jSecurityConfig( jvmArgs, pack.getMinecraftVersion() );

        // Add natives path
        String nativesFolder = pack.getPackRootFolder() +
                File.separator +
                ModPackConstants.MODPACK_MINECRAFT_NATIVES_LOCAL_FOLDER;
        File nativesFolderFile = SynchronizedFileManager.getSynchronizedFile( nativesFolder );
        if ( nativesFolderFile.exists() ) {
            nativesFolderFile.setExecutable( true );
            nativesFolderFile.setReadable( true );
            nativesFolderFile.setWritable( true );
            File[] nativeFiles = nativesFolderFile.listFiles();
            if ( nativeFiles != null ) {
                for ( File f : nativeFiles ) {
                    f.setExecutable( true );
                    f.setReadable( true );
                    f.setWritable( true );
                }
            }
        }

        // On ARM64 with LWJGL2 patching, tell LWJGL and jinput to load natives from our patched
        // natives folder instead of extracting x86_64 binaries from the classpath JARs.
        // Also disable the narrator (text2speech) which depends on JNA 4.4 that lacks ARM64 natives,
        // and force JNA to use the system-installed library if available.
        if ( Lwjgl2ArmPatcher.isNeeded( pack.getMinecraftVersion() ) ) {
            jvmArgs.append( "-Dorg.lwjgl.librarypath=" ).append( nativesFolder ).append( " " );
            jvmArgs.append( "-Dnet.java.games.input.librarypath=" ).append( nativesFolder ).append( " " );
            jvmArgs.append( "-Djna.nosys=false " );
            jvmArgs.append( "-Djna.boot.library.path= " );
            jvmArgs.append( "-Dmojang.text2speech.enabled=false " );
        }

        // Add manifest JVM arguments (from modern arguments.jvm if available)
        String manifestJvmArgs = libraryManifest.getJvmArguments();
        if ( !manifestJvmArgs.isEmpty() ) {
            jvmArgs.append( manifestJvmArgs ).append( " " );
        }

        // Add loader-specific JVM arguments (e.g. module system flags
        // for modern Forge / NeoForge; Fabric typically returns empty).
        if ( !pack.isVanillaVersion() && GameModeManager.isClient() ) {
            String loaderJvmArgs = pack.getModLoader().getJvmArguments();
            if ( !loaderJvmArgs.isEmpty() ) {
                jvmArgs.append( loaderJvmArgs ).append( " " );
            }
        }

        if ( manifestJvmArgs.isEmpty() ) {
            // Legacy versions don't specify JVM args in the manifest; add essential ones manually
            if ( org.apache.commons.lang3.SystemUtils.IS_OS_WINDOWS ) {
                jvmArgs.append( "-Djava.library.path=\"" ).append( nativesFolder ).append( "\" " );
                jvmArgs.append( "-cp \"" ).append( cp ).append( "\" " );
            }
            else {
                jvmArgs.append( "-Djava.library.path=" ).append( nativesFolder ).append( " " );
                jvmArgs.append( "-cp " ).append( cp ).append( " " );
            }
        }

        // Add main class
        jvmArgs.append( minecraftMainClass ).append( " " );

        // Add game arguments
        jvmArgs.append( minecraftArgs );

        String fullArgs = jvmArgs.toString();

        // Replace JVM placeholders (from modern arguments.jvm)
        if ( org.apache.commons.lang3.SystemUtils.IS_OS_WINDOWS ) {
            fullArgs = fullArgs.replace( "${natives_directory}", "\"" + nativesFolder + "\"" );
            fullArgs = fullArgs.replace( "${classpath}", "\"" + cp + "\"" );
        }
        else {
            fullArgs = fullArgs.replace( "${natives_directory}", nativesFolder );
            fullArgs = fullArgs.replace( "${classpath}", cp );
        }
        fullArgs = fullArgs.replace( "${launcher_name}", "MicaMinecraftLauncher" );
        fullArgs = fullArgs.replace( "${launcher_version}", "2025.1" );
        fullArgs = fullArgs.replace( "${version_type}", "release" );
        fullArgs = fullArgs.replace( "${classpath_separator}", File.pathSeparator );
        fullArgs = fullArgs.replace( "${library_directory}", pack.getPackRootFolder() + File.separator +
                ModPackConstants.MODPACK_FORGE_LIBS_LOCAL_FOLDER );

        // Replace game argument placeholders
        if ( GameModeManager.isClient() ) {
            fullArgs = fullArgs.replace( "${auth_player_name}",
                                          MCLauncherAuthManager.getLoggedInUser().name() );
            // ${version_name} is the launcher's identifier for the
            // running profile — Forge: "1.16.5-forge-36.1.31",
            // Fabric: "0.16.10", NeoForge: "21.1.95". Reads come from
            // the polymorphic loader so Fabric / NeoForge land here
            // with a non-null value instead of tripping over Forge's
            // null-for-non-Forge alias.
            fullArgs = fullArgs.replace( "${version_name}",
                                          pack.isVanillaVersion() ? pack.getMinecraftVersion() :
                                          pack.getLoaderVersion() );
            // Modern MC reads assets through the shared launcher-wide tree by hash, so the
            // ${assets_root} placeholder points at the deduplicated location instead of a
            // per-pack copy. Legacy MC uses ${game_assets} which is handled below.
            String sharedAssetsRoot = GameAssetManifest.getSharedAssetsRoot();
            if ( org.apache.commons.lang3.SystemUtils.IS_OS_WINDOWS ) {
                fullArgs = fullArgs.replace( "${game_directory}", "\"" + pack.getPackRootFolder() + "\"" );
                fullArgs = fullArgs.replace( "${assets_root}", "\"" + sharedAssetsRoot + "\"" );
            }
            else {
                fullArgs = fullArgs.replace( "${game_directory}", pack.getPackRootFolder() );
                fullArgs = fullArgs.replace( "${assets_root}", sharedAssetsRoot );
            }

            fullArgs = fullArgs.replace( "${assets_index_name}", libraryManifest.getAssetIndexVersion() );
            fullArgs = fullArgs.replace( "${auth_uuid}", MCLauncherAuthManager.getLoggedInUser().uuid() );
            fullArgs = fullArgs.replace( "${auth_access_token}",
                                          MCLauncherAuthManager.getLoggedInUser().accessToken() );
            fullArgs = fullArgs.replace( "${user_type}", "mojang" );
            fullArgs = fullArgs.replace( "${clientid}", "" );
            fullArgs = fullArgs.replace( "${auth_xuid}", "" );
            fullArgs = fullArgs.replace( "${user_properties}", "{}" );

            // Legacy minecraftArguments (pre-1.6) placeholders. ${auth_session} carries the
            // session token in Mojang's old "token:<accessToken>:<uuid>" form; ${game_assets}
            // is the path the game treats as the legacy flat-assets directory. Without these
            // replacements, launchwrapper sees the literal "${...}" strings and the launch
            // either fails to auth (vanilla 1.0-1.5) or fails to find assets.
            String authSession = "token:" + MCLauncherAuthManager.getLoggedInUser().accessToken()
                    + ":" + MCLauncherAuthManager.getLoggedInUser().uuid();
            fullArgs = fullArgs.replace( "${auth_session}", authSession );

            // ${game_assets} needs to point to the directory layout the game version expects:
            //   - virtual: true (pre-1.6) → assets/virtual/<id>/ per-pack (flat tree, built
            //     by GameAssetManifest.materializeVirtualTree at download time)
            //   - map_to_resources: true (1.6.x) → <gameDir>/resources/ per-pack
            //   - everything else → shared assets root (modern hash-keyed layout)
            // Modern packs share a single tree; legacy needs the flat layout under gameDir.
            String gameAssetsPath;
            try {
                GameAssetManifest assetManifest = libraryManifest.getAssetManifest();
                if ( assetManifest.isVirtual() ) {
                    gameAssetsPath = assetManifest.getVirtualAssetsPath();
                }
                else if ( assetManifest.mapsToResources() ) {
                    gameAssetsPath = assetManifest.getResourcesPath();
                }
                else {
                    gameAssetsPath = sharedAssetsRoot;
                }
            }
            catch ( ModpackException e ) {
                gameAssetsPath = sharedAssetsRoot;
            }
            if ( org.apache.commons.lang3.SystemUtils.IS_OS_WINDOWS ) {
                fullArgs = fullArgs.replace( "${game_assets}", "\"" + gameAssetsPath + "\"" );
            }
            else {
                fullArgs = fullArgs.replace( "${game_assets}", gameAssetsPath );
            }

            // Add title and icon to arguments. The pack name is server-supplied
            // JSON, and ProcessUtilities.splitCommandLine has naive `"`-toggle
            // semantics with no escape character — so a manifest with a packName
            // containing an embedded `"` would close the quote early and inject
            // additional client args (e.g. `--gameDir` to redirect MC's writes,
            // or `--accessToken X` to swap the live token). The pack logo filepath
            // is launcher-constructed under getPackRootFolder() (the folder name
            // is the alphanum-only getPackSanitizedName) so it can't carry a `"`;
            // leave its backslashes untouched so Windows paths still resolve.
            String safeTitle = sanitizeForCommandLine( pack.getPackName() );
            fullArgs += " --title \"" + safeTitle + "\"";
            fullArgs += " --icon \"" + pack.getPackLogoFilepath() + "\"";

            // Set dock name and icon for macOS
            if ( org.apache.commons.lang3.SystemUtils.IS_OS_MAC ) {
                fullArgs += " -Xdock:icon=\"" + pack.getPackLogoFilepath() + "\"";
                fullArgs += " -Xdock:name=\"" + safeTitle + "\" ";
                fullArgs += "-Dapple.laf.useScreenMenuBar=true ";
                fullArgs += "-Djdk.lang.Process.launchMechanism=vfork ";
            }
        }

        // Add java executable path to front of args
        fullArgs = RuntimeManager.getJavaPath( runtimeComponent ) + " " + fullArgs;

        // Signal completion to trigger the progress window hide
        if ( progressProvider != null ) {
            progressProvider.signalComplete( "Starting Minecraft..." );
        }

        // Start game (always non-blocking -- LauncherCore.play() handles the process lifecycle).
        // When the in-game console is disabled the launcher won't be attaching to the JVM's
        // stdout/stderr at all, so let the kernel discard those streams instead of leaving
        // them as PIPE — otherwise the OS pipe buffer fills within a few hundred ms of Forge
        // logging and the child JVM stalls on its next println (visible as "JVM in Task
        // Manager, no Minecraft window"). When console is enabled, keep PIPE so the console
        // GUI's readStream threads can ingest the output for display.
        boolean discardOutput = !ConfigManager.getInGameConsoleEnable();
        try {
            // Redact --accessToken / --clientToken / legacy "token:<token>:<uuid>" before
            // logging — the launcher command line carries the live MS access token, and
            // this log line gets teed into the persistent launcher.log + the game console
            // window. Without redaction, a forum-pasted log = account takeover.
            Logger.logDebug( "Launching game with command: "
                                     + com.micatechnologies.minecraft.launcher.utilities.SensitiveDataRedactor
                                                .redact( fullArgs ) );
            lastLaunchedProcess = ProcessUtilities.launchCommand( fullArgs, pack.getPackRootFolder(),
                                                                   discardOutput );
        }
        catch ( IOException e ) {
            throw new ModpackException( "Unable to execute mod pack game.", e );
        }
    }

    /**
     * Strips characters that would break the naive {@code "}-toggle parser in
     * {@link ProcessUtilities#splitCommandLine} when an attacker-controlled string
     * (today: the modpack name) is spliced into a double-quoted segment of the
     * launch command. Removes ASCII control characters, embedded double quotes,
     * and backslashes (which have no escape semantics in our splitter and would
     * survive into argv literally), and truncates to 200 chars so a manifest
     * cannot inflate the command line beyond reason.
     *
     * <p>The realistic injection vector before this gate was a manifest with
     * {@code "packName": "MyPack\" --gameDir C:/Users/Public --"} closing the
     * {@code --title "..."} quote and injecting client args.
     */
    private static String sanitizeForCommandLine( String value )
    {
        if ( value == null || value.isEmpty() ) {
            return "";
        }
        int cap = Math.min( value.length(), 200 );
        StringBuilder out = new StringBuilder( cap );
        for ( int i = 0; i < cap; i++ ) {
            char c = value.charAt( i );
            if ( c == '"' || c == '\\' || c < 0x20 || c == 0x7F ) {
                continue;
            }
            out.append( c );
        }
        return out.toString();
    }

    /**
     * Applies the correct Mojang security-patched log4j configuration based on the Minecraft version.
     * <ul>
     *   <li>MC 1.7 - 1.11.2: Uses {@code log4j2_17-111.xml} with RegexFilter</li>
     *   <li>MC 1.12 - 1.16.5: Uses {@code log4j2_112-116.xml} with {@code %msg{nolookups}}</li>
     *   <li>MC 1.17+: Uses {@code -Dlog4j2.formatMsgNoLookups=true} JVM flag (built-in support)</li>
     * </ul>
     *
     * @param jvmArgs   the JVM arguments builder to append to
     * @param mcVersion the Minecraft version string
     *
     * @since 3.0
     */
    private void applyLog4jSecurityConfig( StringBuilder jvmArgs, String mcVersion )
    {
        // Parse the major and minor version numbers from the MC version string (e.g. "1.12.2" -> major=1, minor=12)
        int minor = 0;
        try {
            String[] parts = mcVersion.split( "\\." );
            if ( parts.length >= 2 ) {
                minor = Integer.parseInt( parts[ 1 ] );
            }
        }
        catch ( NumberFormatException e ) {
            Logger.logWarningSilent( "Could not parse Minecraft version for log4j config: " + mcVersion );
        }

        // Always add the safety flag as a baseline (no-op if the config file is also applied)
        jvmArgs.append( "-Dlog4j2.formatMsgNoLookups=true " );

        if ( minor >= 17 ) {
            // MC 1.17+: The JVM flag above is sufficient, no config file needed
            Logger.logDebug( "MC " + mcVersion +
                                     ": Using log4j2.formatMsgNoLookups=true (1.17+ built-in support)" );
        }
        else if ( minor >= 12 ) {
            // MC 1.12 - 1.16.5: Download and apply the security-patched config
            applyLog4jConfigFile( jvmArgs, LOG4J_CONFIG_112_116_URL, LOG4J_CONFIG_112_116_SHA1,
                                  LOG4J_CONFIG_112_116_NAME );
        }
        else if ( minor >= 7 ) {
            // MC 1.7 - 1.11.2: Download and apply the older security-patched config
            applyLog4jConfigFile( jvmArgs, LOG4J_CONFIG_17_111_URL, LOG4J_CONFIG_17_111_SHA1,
                                  LOG4J_CONFIG_17_111_NAME );
        }
        // MC < 1.7: Not affected by Log4Shell
    }

    /**
     * Downloads a log4j config file and adds the JVM argument to use it.
     *
     * <p>Storage layout: a single shared cache file under the launcher's metadata
     * folder, keyed by the config's expected SHA-1. Previously each modpack stored
     * its own copy under {@code <packRoot>/bin/<fileName>}, so a user with N packs
     * pinned to the same Minecraft major-version branch (e.g. five 1.16 packs)
     * downloaded the identical 2 KB config N times. Sharing the file across packs
     * means the second pack onwards is a hash-verify-only fast path with no
     * network at all.</p>
     *
     * @param jvmArgs  the JVM arguments builder to append to
     * @param url      the download URL for the config file
     * @param sha1     the expected SHA-1 hash
     * @param fileName the human-readable file name (used in debug log only — the
     *                 cached path uses the SHA-1 so different versions of the
     *                 same file don't collide)
     *
     * @since 3.0
     */
    private void applyLog4jConfigFile( StringBuilder jvmArgs, String url, String sha1, String fileName )
    {
        String cacheDir = com.micatechnologies.minecraft.launcher.files.LocalPathManager
                .getLauncherMetadataFolderPath() + File.separator + "log4j-configs";
        String logConfigPath = cacheDir + File.separator + sha1 + ".xml";
        try {
            //noinspection ResultOfMethodCallIgnored
            new File( cacheDir ).mkdirs();
            ManagedGameFile logConfigFile = new ManagedGameFile( url, logConfigPath, sha1,
                                                                 ManagedGameFile.ManagedGameFileHashType.SHA1 );
            logConfigFile.updateLocalFile();
            jvmArgs.append( "-Dlog4j.configurationFile=" ).append( logConfigPath ).append( " " );
            Logger.logDebug( "Applied Mojang security log4j config: " + fileName );
        }
        catch ( Exception e ) {
            Logger.logWarningSilent( "Failed to download Mojang log4j config " + fileName +
                                             ", relying on formatMsgNoLookups flag." );
        }
    }
}
