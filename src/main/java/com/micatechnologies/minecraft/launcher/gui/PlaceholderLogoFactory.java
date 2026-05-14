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

package com.micatechnologies.minecraft.launcher.gui;

import com.micatechnologies.minecraft.launcher.consts.ModPackConstants;
import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;
import javafx.geometry.VPos;

/**
 * Builds JavaFX {@link Image}s used as placeholder card logos for vanilla
 * Minecraft and the supported modloaders (Forge, NeoForge, Fabric) when no
 * real artwork is available. Cards in the Browse GUI surface installable
 * loader / vanilla versions as empty packs — without a pack-specific logo
 * the cards all rendered with the launcher logo and were impossible to tell
 * apart at a glance.
 *
 * <p>Each image is rendered once per JVM via a JavaFX {@link Canvas} +
 * {@code snapshot} and cached as a {@link WritableImage}. The first call
 * primes the cache; subsequent calls are constant-time lookups. The render
 * is always done on the FX thread (via {@code GUIUtilities.JFXPlatformRun})
 * so callers don't need to worry about thread safety — Canvas drawing and
 * snapshotting both have FX-thread requirements that this class hides.</p>
 *
 * <p>Visual recipe per kind:</p>
 * <ul>
 *   <li>Vanilla — grass-green linear gradient, bold "MC" wordmark.</li>
 *   <li>Forge — dark steel gradient, orange "F" wordmark (forge fire).</li>
 *   <li>NeoForge — deep orange gradient, white "N" wordmark.</li>
 *   <li>Fabric — tan / khaki gradient, dark brown "FAB" wordmark.</li>
 * </ul>
 *
 * @since 2026.5
 */
public final class PlaceholderLogoFactory
{
    private PlaceholderLogoFactory() { /* static-only */ }

    private static final int LOGO_SIZE = 128;

    private static volatile Image vanillaImage;
    private static volatile Image forgeImage;
    private static volatile Image neoForgeImage;
    private static volatile Image fabricImage;

    /** Vanilla Minecraft placeholder — green gradient + "MC" wordmark. */
    public static Image getVanillaLogo()
    {
        if ( vanillaImage == null ) {
            vanillaImage = renderOnFxThread(
                    new Stop[]{ new Stop( 0, Color.web( "#7bb344" ) ),
                                 new Stop( 1, Color.web( "#3f7821" ) ) },
                    "MC", Color.WHITE, 56 );
        }
        return vanillaImage;
    }

    /** Forge placeholder — dark steel gradient + orange "F" wordmark. */
    public static Image getForgeLogo()
    {
        if ( forgeImage == null ) {
            forgeImage = renderOnFxThread(
                    new Stop[]{ new Stop( 0, Color.web( "#525252" ) ),
                                 new Stop( 1, Color.web( "#1f1f1f" ) ) },
                    "F", Color.web( "#ff8a3a" ), 78 );
        }
        return forgeImage;
    }

    /** NeoForge placeholder — deep orange gradient + white "N" wordmark. */
    public static Image getNeoForgeLogo()
    {
        if ( neoForgeImage == null ) {
            neoForgeImage = renderOnFxThread(
                    new Stop[]{ new Stop( 0, Color.web( "#f08646" ) ),
                                 new Stop( 1, Color.web( "#b54115" ) ) },
                    "N", Color.WHITE, 78 );
        }
        return neoForgeImage;
    }

    /** Fabric placeholder — tan / khaki gradient + dark brown "FAB" wordmark. */
    public static Image getFabricLogo()
    {
        if ( fabricImage == null ) {
            fabricImage = renderOnFxThread(
                    new Stop[]{ new Stop( 0, Color.web( "#dec596" ) ),
                                 new Stop( 1, Color.web( "#937547" ) ) },
                    "FAB", Color.web( "#3b2a13" ), 44 );
        }
        return fabricImage;
    }

    /** Resolves the placeholder for a loader-type string (matches
     *  {@code ModPackConstants.MOD_LOADER_*}). Returns the vanilla placeholder
     *  for unrecognised values so callers can use this as the catch-all
     *  "no real logo available" path. */
    public static Image forLoaderType( String loaderType )
    {
        if ( loaderType == null ) return getVanillaLogo();
        return switch ( loaderType ) {
            case ModPackConstants.MOD_LOADER_FORGE    -> getForgeLogo();
            case ModPackConstants.MOD_LOADER_NEOFORGE -> getNeoForgeLogo();
            case ModPackConstants.MOD_LOADER_FABRIC   -> getFabricLogo();
            default                                    -> getVanillaLogo();
        };
    }

    /** Render the logo image. Forces the work onto the FX thread because
     *  {@code Canvas.snapshot} requires it. Blocks the caller until the
     *  render completes — only ever runs once per kind so the latency is
     *  invisible in practice (the lazy {@code if (xxx == null)} above means
     *  the second and onwards calls skip this entirely). */
    private static Image renderOnFxThread( Stop[] gradientStops, String text, Color textColor, double fontSize )
    {
        // Fast path: already on FX thread, render synchronously.
        if ( javafx.application.Platform.isFxApplicationThread() ) {
            return doRender( gradientStops, text, textColor, fontSize );
        }
        // Background-thread call (rare — pre-FX or accidental off-thread
        // resolveLogoForEntry call). Bounce to FX and wait for the result.
        final Image[] result = { null };
        final Object lock = new Object();
        synchronized ( lock ) {
            javafx.application.Platform.runLater( () -> {
                Image img = doRender( gradientStops, text, textColor, fontSize );
                synchronized ( lock ) {
                    result[ 0 ] = img;
                    lock.notifyAll();
                }
            } );
            try { while ( result[ 0 ] == null ) lock.wait(); }
            catch ( InterruptedException ie ) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return result[ 0 ];
    }

    private static Image doRender( Stop[] gradientStops, String text, Color textColor, double fontSize )
    {
        Canvas canvas = new Canvas( LOGO_SIZE, LOGO_SIZE );
        GraphicsContext gc = canvas.getGraphicsContext2D();

        // Background gradient — diagonal top-left → bottom-right.
        LinearGradient grad = new LinearGradient(
                0, 0, 1, 1, true, CycleMethod.NO_CYCLE, gradientStops );
        gc.setFill( grad );
        gc.fillRect( 0, 0, LOGO_SIZE, LOGO_SIZE );

        // Subtle highlight stripe along the top edge to give it some depth.
        gc.setFill( Color.color( 1, 1, 1, 0.10 ) );
        gc.fillRect( 0, 0, LOGO_SIZE, LOGO_SIZE * 0.22 );

        // Bold wordmark text, centered.
        gc.setFill( textColor );
        gc.setFont( Font.font( "System", FontWeight.BOLD, fontSize ) );
        gc.setTextAlign( TextAlignment.CENTER );
        gc.setTextBaseline( VPos.CENTER );
        // Vertically biased a hair upward so the visual weight reads centered
        // (most fonts have a heavier cap height than descender).
        gc.fillText( text, LOGO_SIZE / 2.0, LOGO_SIZE / 2.0 + 2 );

        SnapshotParameters sp = new SnapshotParameters();
        sp.setFill( Color.TRANSPARENT );
        return canvas.snapshot( sp, new WritableImage( LOGO_SIZE, LOGO_SIZE ) );
    }
}
