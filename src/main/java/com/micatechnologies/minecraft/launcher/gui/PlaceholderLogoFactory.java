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
import com.micatechnologies.minecraft.launcher.files.LocalPathManager;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.utilities.NetworkUtilities;
import com.micatechnologies.minecraft.launcher.utilities.SystemUtilities;
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

import java.io.File;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Builds JavaFX {@link Image}s used as card logos for vanilla Minecraft and
 * the supported modloaders (Forge, NeoForge, Fabric) when no per-entry
 * artwork is available.
 *
 * <p>Two tiers of logo:</p>
 * <ol>
 *   <li><b>Canvas-rendered placeholders</b> — brand-coloured gradient +
 *       wordmark, drawn once per JVM via a JavaFX {@link Canvas} snapshot.
 *       Always available synchronously on the FX thread.</li>
 *   <li><b>Official project logos</b> — fetched from each project's
 *       publicly-hosted brand assets and cached on disk under
 *       {@code <config>/loader-logos/} so subsequent app launches load
 *       them from the local filesystem without a network hit. Vanilla
 *       Minecraft sticks with the canvas placeholder — Mojang brand
 *       assets have stricter usage terms than the project-hosted loader
 *       logos, which are explicitly meant to be embedded.</li>
 * </ol>
 *
 * <p>Callers use {@link #resolveLogo} to get an image right away (the
 * canvas placeholder, or a cached official logo if already on disk) and
 * supply an {@link ImageReadyCallback} that fires once a missing official
 * logo finishes downloading — the caller swaps the image into its
 * {@link javafx.scene.image.ImageView} from the callback. Once a logo is
 * cached, the call is constant-time and synchronous on subsequent visits.</p>
 *
 * @since 2026.5
 */
public final class PlaceholderLogoFactory
{
    private PlaceholderLogoFactory() { /* static-only */ }

    private static final int    LOGO_SIZE        = 128;
    private static final String CACHE_DIR_NAME   = "loader-logos";

    /** Official project logo URLs. Each project hosts these for embedding /
     *  attribution use — none of them require attribution headers but the
     *  cards do display them next to the loader name + version, which is
     *  itself nominative-fair-use attribution. */
    private static final String FORGE_LOGO_URL    = "https://files.minecraftforge.net/static/images/embed_logo.png";
    private static final String NEOFORGE_LOGO_URL = "https://neoforged.net/img/authors/neoforged.png";
    private static final String FABRIC_LOGO_URL   = "https://fabricmc.net/assets/logo.png";

    private static volatile Image vanillaPlaceholder;
    private static volatile Image forgePlaceholder;
    private static volatile Image neoForgePlaceholder;
    private static volatile Image fabricPlaceholder;

    /** Tracks loader-type keys with an in-flight download so concurrent card
     *  binds don't queue up duplicate fetches. */
    private static final Set< String > inFlightFetches = ConcurrentHashMap.newKeySet();

    /** Notified on the FX thread once a previously-missing official logo
     *  finishes downloading + is cached locally. The supplied {@link Image}
     *  is ready to drop into an {@link javafx.scene.image.ImageView}. */
    @FunctionalInterface
    public interface ImageReadyCallback
    {
        void onReady( Image image );
    }

    /**
     * Resolve the best-available logo for {@code loaderType}. Returns
     * immediately. When the official logo is already cached on disk it
     * comes back synchronously; otherwise the placeholder is returned and a
     * background fetch is kicked off that fires {@code onOfficialReady} on
     * the FX thread once the download completes. Callers should treat the
     * returned image as the initial render and update their {@code ImageView}
     * from the callback (after re-checking that the card still represents
     * the entry whose logo was requested, so a slow fetch doesn't paint over
     * a rebound card).
     *
     * @param loaderType        {@code ModPackConstants.MOD_LOADER_*}
     *                          identifier. {@code null} or unrecognised
     *                          falls back to the vanilla placeholder.
     * @param onOfficialReady   FX-thread callback invoked when an async
     *                          download completes. Never invoked when the
     *                          official logo is already cached (the call
     *                          returns it synchronously) or when the
     *                          fetch fails / errors out.
     */
    public static Image resolveLogo( String loaderType, ImageReadyCallback onOfficialReady )
    {
        // No official logo for vanilla — stay on the placeholder.
        if ( loaderType == null
                || !( ModPackConstants.MOD_LOADER_FORGE.equals( loaderType )
                       || ModPackConstants.MOD_LOADER_NEOFORGE.equals( loaderType )
                       || ModPackConstants.MOD_LOADER_FABRIC.equals( loaderType ) ) )
        {
            return getVanillaLogo();
        }
        File cached = cacheFileFor( loaderType );
        if ( cached.exists() && cached.length() > 0 ) {
            return new Image( cached.toURI().toString(), true );
        }
        // Not cached — return the placeholder synchronously and kick off
        // a one-shot background fetch. Guard against duplicate downloads
        // when many cards bind to the same loader-type at once.
        if ( inFlightFetches.add( loaderType ) ) {
            final String url = officialUrlFor( loaderType );
            final File destination = cached;
            SystemUtilities.spawnNewTask( () -> {
                try {
                    if ( !destination.getParentFile().exists() ) {
                        destination.getParentFile().mkdirs();
                    }
                    NetworkUtilities.downloadFileFromURL( url, destination );
                    if ( destination.exists() && destination.length() > 0 ) {
                        Image ready = new Image( destination.toURI().toString(), false );
                        javafx.application.Platform.runLater( () -> onOfficialReady.onReady( ready ) );
                    }
                }
                catch ( Exception ex ) {
                    Logger.logWarningSilent(
                            "PlaceholderLogoFactory: failed to fetch official "
                                    + loaderType + " logo (" + url + "): "
                                    + ex.getMessage() );
                }
                finally {
                    inFlightFetches.remove( loaderType );
                }
            } );
        }
        return placeholderFor( loaderType );
    }

    // ====================================================================
    // Synchronous placeholder accessors — kept public for callers that
    // explicitly want the canvas-drawn brand image (e.g. background-colour
    // sampling, attribution-free preview thumbnails).
    // ====================================================================

    /** Vanilla Minecraft placeholder — green gradient + "MC" wordmark. */
    public static Image getVanillaLogo()
    {
        if ( vanillaPlaceholder == null ) {
            vanillaPlaceholder = renderOnFxThread(
                    new Stop[]{ new Stop( 0, Color.web( "#7bb344" ) ),
                                 new Stop( 1, Color.web( "#3f7821" ) ) },
                    "MC", Color.WHITE, 56 );
        }
        return vanillaPlaceholder;
    }

    /** Forge placeholder — dark steel gradient + orange "F" wordmark. */
    public static Image getForgePlaceholder()
    {
        if ( forgePlaceholder == null ) {
            forgePlaceholder = renderOnFxThread(
                    new Stop[]{ new Stop( 0, Color.web( "#525252" ) ),
                                 new Stop( 1, Color.web( "#1f1f1f" ) ) },
                    "F", Color.web( "#ff8a3a" ), 78 );
        }
        return forgePlaceholder;
    }

    /** NeoForge placeholder — deep orange gradient + white "N" wordmark. */
    public static Image getNeoForgePlaceholder()
    {
        if ( neoForgePlaceholder == null ) {
            neoForgePlaceholder = renderOnFxThread(
                    new Stop[]{ new Stop( 0, Color.web( "#f08646" ) ),
                                 new Stop( 1, Color.web( "#b54115" ) ) },
                    "N", Color.WHITE, 78 );
        }
        return neoForgePlaceholder;
    }

    /** Fabric placeholder — tan / khaki gradient + dark brown "FAB" wordmark. */
    public static Image getFabricPlaceholder()
    {
        if ( fabricPlaceholder == null ) {
            fabricPlaceholder = renderOnFxThread(
                    new Stop[]{ new Stop( 0, Color.web( "#dec596" ) ),
                                 new Stop( 1, Color.web( "#937547" ) ) },
                    "FAB", Color.web( "#3b2a13" ), 44 );
        }
        return fabricPlaceholder;
    }

    /** Synchronous placeholder for {@code loaderType}. Falls back to the
     *  vanilla placeholder for null / unrecognised values. */
    public static Image placeholderFor( String loaderType )
    {
        if ( loaderType == null ) return getVanillaLogo();
        return switch ( loaderType ) {
            case ModPackConstants.MOD_LOADER_FORGE    -> getForgePlaceholder();
            case ModPackConstants.MOD_LOADER_NEOFORGE -> getNeoForgePlaceholder();
            case ModPackConstants.MOD_LOADER_FABRIC   -> getFabricPlaceholder();
            default                                    -> getVanillaLogo();
        };
    }

    // ====================================================================
    // Disk cache + URL helpers
    // ====================================================================

    private static File cacheFileFor( String loaderType )
    {
        return new File( LocalPathManager.getLauncherConfigFolderPath()
                                 + File.separator + CACHE_DIR_NAME,
                          loaderType + ".png" );
    }

    private static String officialUrlFor( String loaderType )
    {
        return switch ( loaderType ) {
            case ModPackConstants.MOD_LOADER_FORGE    -> FORGE_LOGO_URL;
            case ModPackConstants.MOD_LOADER_NEOFORGE -> NEOFORGE_LOGO_URL;
            case ModPackConstants.MOD_LOADER_FABRIC   -> FABRIC_LOGO_URL;
            default                                    -> null;
        };
    }

    // ====================================================================
    // Canvas rendering
    // ====================================================================

    /** Render the logo image. Forces the work onto the FX thread because
     *  {@code Canvas.snapshot} requires it. Only runs once per kind so the
     *  latency is invisible in practice. */
    private static Image renderOnFxThread( Stop[] gradientStops, String text, Color textColor, double fontSize )
    {
        if ( javafx.application.Platform.isFxApplicationThread() ) {
            return doRender( gradientStops, text, textColor, fontSize );
        }
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

        LinearGradient grad = new LinearGradient(
                0, 0, 1, 1, true, CycleMethod.NO_CYCLE, gradientStops );
        gc.setFill( grad );
        gc.fillRect( 0, 0, LOGO_SIZE, LOGO_SIZE );

        gc.setFill( Color.color( 1, 1, 1, 0.10 ) );
        gc.fillRect( 0, 0, LOGO_SIZE, LOGO_SIZE * 0.22 );

        gc.setFill( textColor );
        gc.setFont( Font.font( "System", FontWeight.BOLD, fontSize ) );
        gc.setTextAlign( TextAlignment.CENTER );
        gc.setTextBaseline( VPos.CENTER );
        gc.fillText( text, LOGO_SIZE / 2.0, LOGO_SIZE / 2.0 + 2 );

        SnapshotParameters sp = new SnapshotParameters();
        sp.setFill( Color.TRANSPARENT );
        return canvas.snapshot( sp, new WritableImage( LOGO_SIZE, LOGO_SIZE ) );
    }
}
