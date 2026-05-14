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

package com.micatechnologies.minecraft.launcher.rgb;

import com.micatechnologies.minecraft.launcher.config.ConfigManager;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.game.modpack.GameModPack;
import com.micatechnologies.minecraft.launcher.gui.MCLauncherMainGui;
import com.micatechnologies.minecraft.launcher.rgb.effects.InGameEffect;
import com.micatechnologies.minecraft.launcher.utilities.SystemUtilities;

import javafx.scene.paint.Color;

/**
 * Glue layer between the rest of the launcher and the RGB subsystem.
 *
 * <p>All public methods are static, null-safe, and bail fast when RGB
 * is disabled — callers ({@code LauncherCore.main}, {@code LauncherCore.play},
 * {@code LauncherCore.closeApp}) invoke them unconditionally without
 * needing to first check the master-enable toggle. Every internal call
 * is wrapped in catch-{@link Throwable} so an RGB-side failure can
 * never propagate up into the launch flow.</p>
 *
 * <p>This class is the only place the launcher's "business logic" code
 * paths reach into the RGB package; the {@code rgb/} sub-package is
 * otherwise hermetic. Adding a new RGB hook (e.g. "fire a celebration
 * effect on level-up" — if/when we ever parse the game log) means
 * adding a method here, not touching {@code rgb/} internals.</p>
 *
 * @since 2026.5
 */
public final class RgbIntegration
{
    private RgbIntegration() { /* static-only */ }

    /**
     * Startup hook. Called once during launcher bootstrap. If RGB is
     * enabled in config, starts the {@link RgbController} on whichever
     * backend the user picked (or auto-probes). Off the FX thread —
     * backend start() may probe sockets / load DLLs and we don't want
     * to block the splash screen on either.
     */
    public static void bootstrap()
    {
        if ( !ConfigManager.getRgbEnable() ) return;
        SystemUtilities.spawnNewTask( () -> {
            try {
                RgbController.getInstance().start( RgbBackendRegistry.resolveFromConfig() );
            }
            catch ( Throwable t ) {
                Logger.logWarningSilent( "RGB bootstrap threw — RGB will be disabled "
                                                 + "this session", t );
            }
        } );
    }

    /**
     * Shutdown hook. Called once from the launcher's cleanup path on
     * exit. Stops the controller, which paints a final black frame so
     * the user's devices return to whatever state they were in before
     * the launcher took over. Synchronous because shutdown ordering
     * matters — we want this to complete before the JVM exits.
     */
    public static void shutdown()
    {
        try {
            RgbController.getInstance().stop();
        }
        catch ( Throwable t ) {
            Logger.logWarningSilent( "RGB shutdown threw — ignoring", t );
        }
    }

    /**
     * Called when a game launch starts (after the play button press
     * + pre-launch verify, immediately before the JVM spawn). Switches
     * the active effect to the headline "in-game" effect, with colors
     * derived from the launching modpack's logo when the
     * "Use modpack colors" toggle is on. No-op when RGB is disabled.
     *
     * <p>The launching effect is intentionally NOT a pulse / spinner —
     * the in-game look is the steady scene the user spends the next
     * several hours in. The transient launch progress UI is fast enough
     * that a separate pre-launch effect would barely register before
     * being replaced by the in-game one.</p>
     */
    public static void onPlayStarted( GameModPack pack )
    {
        if ( !ConfigManager.getRgbEnable() ) return;
        try {
            RgbColor primary;
            RgbColor highlight;

            if ( ConfigManager.getRgbUsePackColors() && pack != null ) {
                Color[] sampled = MCLauncherMainGui.sampleDominantPackColors( pack );
                if ( sampled != null && sampled.length >= 1 && sampled[ 0 ] != null ) {
                    primary = fromFxColor( sampled[ 0 ] );
                    // Highlight: if the sample produced a strong secondary,
                    // use it; otherwise derive a complement of the primary.
                    if ( sampled.length >= 2 && sampled[ 1 ] != null
                            && colorDistance( sampled[ 0 ], sampled[ 1 ] ) > 0.25 ) {
                        highlight = fromFxColor( sampled[ 1 ] );
                    }
                    else {
                        highlight = primary.complement();
                    }
                }
                else {
                    // No usable pack color — fall back to the safe default.
                    primary = new RgbColor( 60, 90, 160 );          // Mica blue
                    highlight = new RgbColor( 240, 195, 60 );        // gold
                }
            }
            else {
                // Pack-colors off in Settings, or no pack provided.
                primary = new RgbColor( 60, 90, 160 );
                highlight = new RgbColor( 240, 195, 60 );
            }

            String displayName = pack != null ? pack.getFriendlyName() : "";
            java.util.Set< com.micatechnologies.minecraft.launcher.rgb.KeyboardKey > keys =
                    ConfigManager.getRgbHighlightKeys()
                            ? InGameEffect.DEFAULT_HIGHLIGHTED_KEYS
                            : java.util.Collections.emptySet();
            InGameEffect effect = new InGameEffect( displayName, primary, highlight, keys );
            RgbController.getInstance().setEffect( effect );
        }
        catch ( Throwable t ) {
            Logger.logWarningSilent( "RGB onPlayStarted threw — effect not applied", t );
        }
    }

    /**
     * Called when the game process exits (or the launch is cancelled).
     * Stops the active effect — engine pushes one final black frame,
     * scheduler idles. The controller stays running so a subsequent
     * launch is a cheap setEffect call rather than a full start.
     */
    public static void onPlayEnded()
    {
        if ( !ConfigManager.getRgbEnable() ) return;
        try {
            RgbController.getInstance().stopEffect();
        }
        catch ( Throwable t ) {
            Logger.logWarningSilent( "RGB onPlayEnded threw — ignoring", t );
        }
    }

    // =========================================================================
    //  Helpers
    // =========================================================================

    private static RgbColor fromFxColor( Color c )
    {
        return new RgbColor(
                clamp255( (int) Math.round( c.getRed() * 255.0 ) ),
                clamp255( (int) Math.round( c.getGreen() * 255.0 ) ),
                clamp255( (int) Math.round( c.getBlue() * 255.0 ) ) );
    }

    private static int clamp255( int v )
    {
        return v < 0 ? 0 : ( v > 255 ? 255 : v );
    }

    /** Euclidean distance in normalized RGB space — mirrors the
     *  {@code colorDistance} used by {@code MCLauncherMainGui}'s
     *  histogram so our "is the secondary distinct enough to use?"
     *  threshold matches what the main-menu hero card uses for the
     *  gradient's second stop. */
    private static double colorDistance( Color a, Color b )
    {
        double dr = a.getRed() - b.getRed();
        double dg = a.getGreen() - b.getGreen();
        double db = a.getBlue() - b.getBlue();
        return Math.sqrt( dr * dr + dg * dg + db * db );
    }
}
