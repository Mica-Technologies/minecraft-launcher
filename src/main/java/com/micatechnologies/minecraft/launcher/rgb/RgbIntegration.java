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
import com.micatechnologies.minecraft.launcher.consts.ConfigConstants;
import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.game.modpack.GameModPack;
import com.micatechnologies.minecraft.launcher.gui.MCLauncherMainGui;
import com.micatechnologies.minecraft.launcher.rgb.effects.CycleEffect;
import com.micatechnologies.minecraft.launcher.rgb.effects.InGameEffect;
import com.micatechnologies.minecraft.launcher.rgb.effects.PulseEffect;
import com.micatechnologies.minecraft.launcher.rgb.effects.RainbowEffect;
import com.micatechnologies.minecraft.launcher.rgb.effects.SolidEffect;
import com.micatechnologies.minecraft.launcher.utilities.SystemUtilities;

import javafx.scene.paint.Color;

import java.util.ArrayList;
import java.util.List;

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
     * enabled in config, starts the {@link RgbController} on every
     * backend the user has opted into that probes available (Auto
     * mode multi-backend), or on the single explicit pick (Manual
     * mode). Off the FX thread — backend start() may probe sockets /
     * load DLLs and we don't want to block the splash screen on
     * either.
     */
    public static void bootstrap()
    {
        if ( !ConfigManager.getRgbEnable() ) return;
        SystemUtilities.spawnNewTask( () -> {
            try {
                RgbController.getInstance().start(
                        RgbBackendRegistry.resolveBackendsFromConfig() );
                // Now that backends are up, paint the idle "menu" effect
                // so devices don't sit dark on the main screen. No-op when
                // the user has the menu effect toggle off in Settings —
                // they keep darkness until a game launches.
                applyMenuEffect( null );
            }
            catch ( Throwable t ) {
                Logger.logWarningSilent( LocalizationManager.get( "log.rgb.bootstrapThrew" ), t );
            }
        } );
    }

    /**
     * Switch to the idle "menu" effect, with colors chosen based on
     * context. Callers pass {@code null} when the user is in a generic
     * launcher menu (use the active theme's accent), or a non-null
     * {@link GameModPack} when the user is looking at that pack's
     * detail modal (use the pack's sampled colors for an immersive
     * "this is the modpack you're hovering" feel).
     *
     * <p>No-op when RGB is master-disabled or when the menu-effect
     * toggle is off in Settings; in the latter case devices stay dark
     * between game launches.</p>
     */
    public static void onMenu( GameModPack contextPack )
    {
        if ( !ConfigManager.getRgbEnable() ) return;
        try {
            applyMenuEffect( contextPack );
        }
        catch ( Throwable t ) {
            Logger.logWarningSilent( LocalizationManager.get( "log.rgb.onMenuThrew" ), t );
        }
    }

    /** Convenience overload for the generic-menu case (no specific
     *  modpack in focus). */
    public static void onMenu()
    {
        onMenu( null );
    }

    /** Compute the menu effect and push it to the controller. Internal
     *  helper shared by {@link #bootstrap}, {@link #onMenu}, and
     *  {@link #onPlayEnded} so they all produce the same effect when
     *  the user has the menu-effect toggle on. */
    private static void applyMenuEffect( GameModPack contextPack )
    {
        if ( !ConfigManager.getRgbMenuEffectEnable() ) {
            // User opted out of the menu effect — keep devices dark
            // between launches. Match the prior "stop effect" behaviour
            // so the transition out of an in-game effect still paints
            // a final black frame.
            RgbController.getInstance().stopEffect();
            return;
        }

        String style = ConfigManager.getRgbEffectStyle();
        String labelContext = contextPack != null
                ? contextPack.getFriendlyName()
                : "theme accent";

        // The rainbow style is intentionally palette-agnostic — sweeping
        // the entire hue circle regardless of pack or theme. Build it
        // first so we can short-circuit the palette computation below.
        if ( ConfigConstants.RGB_EFFECT_STYLE_RAINBOW.equals( style ) ) {
            RgbController.getInstance().setEffect(
                    new RainbowEffect( "Menu (rainbow)", 8_000L ) );
            return;
        }

        // Build the palette this effect should drive from. For pack
        // context: sampled dominant colors. For generic menu: theme
        // accent +/- analogous neighbours when the cycle effect needs
        // more than one hue.
        List< RgbColor > palette = resolvePalette( contextPack, style );
        if ( palette.isEmpty() ) {
            // Defensive — every path above should have produced at
            // least one color. Bail to a safe fallback rather than
            // crash the bootstrap.
            palette = List.of( ThemeAccentColors.accentForCurrentTheme() );
        }
        RgbColor primary = palette.get( 0 );

        RgbEffect effect = switch ( style ) {
            case ConfigConstants.RGB_EFFECT_STYLE_SOLID ->
                    new SolidEffect( "Menu (solid · " + labelContext + ")", primary );
            case ConfigConstants.RGB_EFFECT_STYLE_PULSE ->
                    // Fast, energetic — accent ↔ its complement, 1.5 s
                    // per cycle. Reads as "alive and waiting" vs.
                    // breathe's softer "idle and listening".
                    new PulseEffect( "Menu (pulse · " + labelContext + ")",
                                     primary, primary.complement(), 1_500L );
            case ConfigConstants.RGB_EFFECT_STYLE_CYCLE ->
                    new CycleEffect( "Menu (cycle · " + labelContext + ")",
                                     palette, perCycleMs( palette.size() ) );
            // Breathe is the default — slow accent pulse, the same
            // behaviour the menu effect had before the style dropdown
            // shipped. ConfigManager already filters unknown style
            // values back to default before they reach this dispatcher,
            // so falling through to the default arm covers both the
            // breathe case and any future-migration unknown.
            default ->
                    new PulseEffect( "Menu (breathe · " + labelContext + ")",
                                     primary,
                                     RgbColor.blend( primary, RgbColor.BLACK, 0.65 ),
                                     4_000L );
        };
        RgbController.getInstance().setEffect( effect );
    }

    /**
     * Build the palette feeding the active effect style. The number of
     * colors returned depends on the style — cycle wants 3-4, breathe /
     * pulse / solid only need the primary. We always over-provide
     * rather than risk a too-short list at the dispatcher's switch.
     */
    private static List< RgbColor > resolvePalette( GameModPack contextPack, String style )
    {
        int wanted = ConfigConstants.RGB_EFFECT_STYLE_CYCLE.equals( style ) ? 4 : 1;

        if ( contextPack != null ) {
            // Use the n-color path for cycle, the cached 2-color path
            // for everything else (cheaper, already populated).
            Color[] sampled = wanted > 2
                    ? MCLauncherMainGui.sampleDominantPackPalette( contextPack, wanted )
                    : MCLauncherMainGui.sampleDominantPackColors( contextPack );
            if ( sampled != null && sampled.length >= 1 && sampled[ 0 ] != null ) {
                List< RgbColor > out = new ArrayList<>( sampled.length );
                for ( Color c : sampled ) {
                    if ( c != null ) out.add( fromFxColor( c ) );
                }
                if ( !out.isEmpty() ) {
                    // If cycle asked for 4 and the logo only had 2,
                    // analogous-pad up to 3 around the primary so the
                    // cycle still has visible motion. Better than
                    // crashing the user back to breathe behaviour just
                    // because the pack art is monochrome.
                    if ( ConfigConstants.RGB_EFFECT_STYLE_CYCLE.equals( style )
                            && out.size() < 3 ) {
                        return ThemeAccentColors.derivePalette( out.get( 0 ), 3 );
                    }
                    return out;
                }
            }
        }

        // No pack (or sampling failed) — derive the palette from the
        // theme accent. Cycle gets 3 analogous hues; everything else
        // gets just the accent.
        RgbColor themePrimary = ThemeAccentColors.accentForCurrentTheme();
        if ( ConfigConstants.RGB_EFFECT_STYLE_CYCLE.equals( style ) ) {
            return ThemeAccentColors.derivePalette( themePrimary, 3 );
        }
        return List.of( themePrimary );
    }

    /**
     * Per-cycle duration for the cycle effect, scaled by color count
     * so each color gets roughly the same dwell time regardless of
     * palette size. Tuned to ~2.5 s per color which is slow enough
     * to feel intentional but fast enough that a 4-color palette
     * loops every ~10 s.
     */
    private static long perCycleMs( int colorCount )
    {
        return Math.max( 1L, colorCount ) * 2_500L;
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
            Logger.logWarningSilent( LocalizationManager.get( "log.rgb.shutdownThrew" ), t );
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
            Logger.logWarningSilent( LocalizationManager.get( "log.rgb.onPlayStartedThrew" ), t );
        }
    }

    /**
     * Called when the game process exits (or the launch is cancelled).
     * Drops the in-game effect and returns to the idle menu effect
     * (or stops entirely when the user has the menu-effect toggle off).
     * The controller stays running so a subsequent launch is a cheap
     * setEffect call rather than a full start.
     */
    public static void onPlayEnded()
    {
        if ( !ConfigManager.getRgbEnable() ) return;
        try {
            applyMenuEffect( null );
        }
        catch ( Throwable t ) {
            Logger.logWarningSilent( LocalizationManager.get( "log.rgb.onPlayEndedThrew" ), t );
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
