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

package com.micatechnologies.minecraft.launcher.utilities;

import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.game.modpack.GameModPack;
import com.micatechnologies.minecraft.launcher.game.modpack.LaunchProgressTracker;
import com.micatechnologies.minecraft.launcher.game.modpack.LaunchTrackerProgressBridge;
import com.micatechnologies.minecraft.launcher.gui.GUIUtilities;
import com.micatechnologies.minecraft.launcher.gui.MCLauncherGuiController;
import com.micatechnologies.minecraft.launcher.gui.MCLauncherLaunchProgressGui;

import java.io.IOException;
import java.util.List;

/**
 * Drives the user-initiated "Verify this pack now" / "Verify all game files"
 * flows. Both surfaces — the per-pack button in the modpack-detail-modal
 * Advanced section and the launcher-wide button in Settings → Security —
 * reach for {@link #runForPacks(List)}.
 *
 * <p>The flow:</p>
 * <ol>
 *   <li>Take over the stage with a {@link MCLauncherLaunchProgressGui}
 *       attached to a fresh {@link LaunchProgressTracker}. The tracker's
 *       step set is the same six-row layout the Play path uses (Modpack
 *       content / Forge libs / MC libs+assets / JRE / Forge processors /
 *       Scan) — the user gets visual continuity between launching and
 *       verifying.</li>
 *   <li>For each pack in the supplied list, set the bridge as the pack's
 *       progress provider and call {@code pack.verifyAllFilesNow()},
 *       which force-FULL-verifies every file and writes a fresh
 *       verify-state sidecar.</li>
 *   <li>When all packs settle, toast the outcome and return to the main
 *       GUI.</li>
 * </ol>
 *
 * <p>Vanilla packs trim the Forge rows from the tracker so the GUI doesn't
 * render skip placeholders for stages that don't apply — same convention
 * as the Play path's launch progress.</p>
 *
 * @since 2026.3
 */
public final class VerifyAction
{
    private VerifyAction() { /* static-only */ }

    /**
     * Spawns the verify flow on a background thread so the FX thread stays
     * responsive while files are being re-hashed. Safe to call from any
     * thread; the GUI transition routes through the FX thread internally.
     *
     * @param packs the packs to verify. Failed-load placeholders are
     *              silently skipped.
     */
    public static void runForPacks( List< GameModPack > packs )
    {
        if ( packs == null || packs.isEmpty() ) return;
        SystemUtilities.spawnNewTask( () -> runForPacksOnWorker( packs ) );
    }

    private static void runForPacksOnWorker( List< GameModPack > packs )
    {
        MCLauncherLaunchProgressGui progressGui;
        try {
            progressGui = MCLauncherGuiController.goToLaunchProgressGui();
        }
        catch ( IOException e ) {
            Logger.logError( "Could not show launch progress GUI for verify action." );
            Logger.logThrowable( e );
            return;
        }
        if ( progressGui == null ) {
            Logger.logErrorSilent( "Verify action: GUI subsystem returned null progress window." );
            return;
        }

        int successCount = 0;
        int failureCount = 0;
        final int total = packs.size();

        for ( int i = 0; i < total; i++ ) {
            GameModPack pack = packs.get( i );
            if ( pack == null || pack.getManifestUrl() == null || pack.getManifestUrl().isBlank() ) {
                continue;
            }

            // Build a per-pack tracker with only the steps that apply. Same
            // step-set rule the Play path uses: vanilla skips the modded
            // rows; Fabric drops FORGE_PROCESSORS (it has no post-install
            // patching pipeline).
            java.util.List< LaunchProgressTracker.StepId > stepList = new java.util.ArrayList<>();
            if ( !pack.isVanillaVersion() ) {
                stepList.add( LaunchProgressTracker.StepId.MODPACK_CONTENT );
                stepList.add( LaunchProgressTracker.StepId.FORGE_LIBS );
            }
            stepList.add( LaunchProgressTracker.StepId.MC_LIBS_ASSETS );
            stepList.add( LaunchProgressTracker.StepId.JRE_INSTALL );
            if ( pack.usesPostInstallSteps() ) {
                stepList.add( LaunchProgressTracker.StepId.FORGE_PROCESSORS );
            }
            stepList.add( LaunchProgressTracker.StepId.SECURITY_SCAN );
            LaunchProgressTracker tracker = LaunchProgressTracker.forSteps(
                    stepList.toArray( new LaunchProgressTracker.StepId[ 0 ] ) );
            LaunchTrackerProgressBridge bridge = new LaunchTrackerProgressBridge( tracker );

            final String title = ( total > 1 )
                    ? "Verifying " + safePackName( pack ) + " (" + ( i + 1 ) + " of " + total + ")"
                    : "Verifying " + safePackName( pack );
            final MCLauncherLaunchProgressGui finalProgressGui = progressGui;
            GUIUtilities.JFXPlatformRun( () -> {
                finalProgressGui.setTitle( title );
                finalProgressGui.attachToTracker( tracker );
            } );

            pack.setProgressProvider( bridge );
            try {
                pack.verifyAllFilesNow();
                successCount++;
            }
            catch ( Throwable t ) {
                failureCount++;
                Logger.logError( "Verify failed for pack " + safePackName( pack )
                                         + ": " + t.getClass().getSimpleName() );
                Logger.logThrowable( t );
            }
        }

        final int finalSuccess = successCount;
        final int finalFailure = failureCount;
        GUIUtilities.JFXPlatformRun( () -> {
            if ( finalFailure == 0 ) {
                NotificationManager.success( "Verify complete",
                        finalSuccess + ( finalSuccess == 1 ? " pack" : " packs" )
                                + " verified successfully." );
            }
            else {
                NotificationManager.error( "Verify finished with errors",
                        finalSuccess + " ok, " + finalFailure + " failed. See log for details." );
            }
            try {
                MCLauncherGuiController.goToMainGui();
            }
            catch ( IOException e ) {
                Logger.logErrorSilent( "Verify action: could not return to main GUI." );
            }
        } );
    }

    private static String safePackName( GameModPack pack )
    {
        if ( pack == null ) return "modpack";
        String name = pack.getFriendlyName();
        if ( name != null && !name.isBlank() ) return name;
        name = pack.getPackName();
        return ( name != null && !name.isBlank() ) ? name : "modpack";
    }
}
