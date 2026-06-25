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

import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Detects whether a modpack logo has transparent edges (i.e. it's a logo whose
 * visible content doesn't fill its bounding box — a circular badge, a wordmark,
 * an irregular silhouette over a transparent PNG). Used to decide whether to
 * show the launcher's standard rounded-rect border around the logo container:
 * for square logos the border looks intentional; for transparent-edge logos
 * the border reads as a "floating" rectangle around nothing.
 *
 * <p>Detection is a cheap corner-sample heuristic — read alpha at the four
 * corners of the image and a few mid-edge points, count how many are
 * effectively transparent (alpha &lt; {@link #ALPHA_THRESHOLD}). If the
 * majority of perimeter samples are transparent the logo qualifies as
 * "transparent-edges" and the caller should hide the container border.
 *
 * <p>Results are cached process-wide keyed by image URL so the FX thread
 * doesn't repeatedly sample pixels for the same logo across navigation.
 * Cache is unbounded but the entry size is one boolean per pack — trivial.
 *
 * <p>Async-safe: if the image hasn't finished loading yet, the detector
 * attaches a one-shot listener to {@code progressProperty()} and fires the
 * callback once the image is fully loaded. The callback always runs on the
 * FX thread because the listener fires there.
 *
 * @since 3.4
 */
public final class LogoTransparencyDetector
{
    /** Alpha threshold below which a pixel counts as "transparent" for the
     *  edge-detection heuristic. 0.05 = essentially fully transparent —
     *  anti-aliased edges with subtle softness (~0.7 alpha) don't qualify,
     *  but a transparent backdrop will. */
    private static final double ALPHA_THRESHOLD = 0.05;

    /** Fraction of perimeter samples that must be transparent for the logo to
     *  count as transparent-edges. 0.6 = strict majority — a logo that fills
     *  most but not all of its bounding box (one transparent corner) doesn't
     *  qualify, but one that fills less than half does. */
    private static final double TRANSPARENT_RATIO = 0.60;

    /** Process-wide cache keyed by image URL. Holds the detector's verdict
     *  so a logo that's been classified once doesn't get re-sampled when
     *  the user navigates back to a screen. */
    private static final ConcurrentHashMap< String, Boolean > CACHE = new ConcurrentHashMap<>();

    /**
     * Private constructor to prevent instantiation of this utility class.
     */
    private LogoTransparencyDetector() { /* static-only */ }

    /**
     * Asynchronously determines whether the given image has transparent edges
     * and invokes {@code callback} with the result on the FX thread.
     *
     * <p>If the image is already loaded, the callback fires synchronously. If
     * not, a listener on {@code progressProperty} fires the callback once
     * loading completes. If the image errors out, the callback fires with
     * {@code false} so the caller renders the default (bordered) style.
     *
     * <p>The callback is guaranteed to fire at most once per call, even if the
     * image already has a progress listener that the JavaFX runtime decides to
     * invoke multiple times.
     *
     * @param image    the image to inspect; null/error → callback fires with false
     * @param callback the consumer to receive the verdict (runs on FX thread)
     */
    public static void detectAsync( Image image, Consumer< Boolean > callback )
    {
        if ( image == null || callback == null ) {
            if ( callback != null ) callback.accept( false );
            return;
        }

        // Cache hit — fastest path. Skips the pixel sampling entirely on
        // subsequent navigations to a screen showing the same pack.
        String url = image.getUrl();
        if ( url != null ) {
            Boolean cached = CACHE.get( url );
            if ( cached != null ) {
                callback.accept( cached );
                return;
            }
        }

        if ( image.isError() ) {
            callback.accept( false );
            return;
        }

        if ( image.getProgress() >= 1.0 ) {
            boolean result = sample( image );
            if ( url != null ) CACHE.put( url, result );
            callback.accept( result );
            return;
        }

        // Image still loading — defer the sample until load completes. The
        // wrapping array + boolean prevents the listener from firing the
        // callback more than once (JavaFX has been observed to fire progress
        // listeners multiple times around the 1.0 boundary on some platforms).
        final boolean[] fired = { false };
        image.progressProperty().addListener( ( obs, oldV, newV ) -> {
            if ( fired[ 0 ] ) return;
            if ( newV.doubleValue() < 1.0 ) return;
            fired[ 0 ] = true;
            if ( image.isError() ) {
                callback.accept( false );
                return;
            }
            boolean result = sample( image );
            if ( url != null ) CACHE.put( url, result );
            callback.accept( result );
        } );
    }

    /**
     * Performs the actual corner-and-edge alpha sample. Reads 12 perimeter
     * points: 4 corners + 8 edge midpoints (top/bottom × 3 each, left/right
     * × 1 each). If 60%+ are below the alpha threshold, returns true.
     *
     * <p>Inset the sample points by a few pixels from the literal corner so
     * a 1-pixel anti-aliased halo on an otherwise-square logo doesn't trip
     * the detector. Larger logos get a bigger inset.
     *
     * @param image the image to sample
     * @return true if the majority of perimeter samples are transparent, false otherwise
     */
    private static boolean sample( Image image )
    {
        PixelReader reader = image.getPixelReader();
        if ( reader == null ) return false;
        int w = ( int ) image.getWidth();
        int h = ( int ) image.getHeight();
        if ( w <= 0 || h <= 0 ) return false;

        // Inset 5% from each edge so smooth anti-aliasing doesn't get
        // sampled as "transparent." For a 256px logo that's ~13 px in;
        // for a 64px logo ~3 px in.
        int insetX = Math.max( 2, w / 20 );
        int insetY = Math.max( 2, h / 20 );
        int left   = insetX;
        int right  = w - 1 - insetX;
        int top    = insetY;
        int bottom = h - 1 - insetY;
        int midX   = w / 2;
        int midY   = h / 2;
        int qX1    = w / 4;
        int qX3    = ( 3 * w ) / 4;

        // 12 perimeter samples: 4 corners, 3 along top, 3 along bottom, plus
        // mids on left/right edges. Tilted logos / wordmarks have transparent
        // corners but opaque mid-top/mid-bottom; testing the full perimeter
        // avoids classifying those as transparent-edges.
        int[][] samples = {
                { left,  top },     { right, top },
                { left,  bottom },  { right, bottom },
                { qX1,   top },     { midX, top },     { qX3, top },
                { qX1,   bottom },  { midX, bottom },  { qX3, bottom },
                { left,  midY },    { right, midY }
        };

        int transparent = 0;
        for ( int[] xy : samples ) {
            double alpha = reader.getColor( xy[ 0 ], xy[ 1 ] ).getOpacity();
            if ( alpha < ALPHA_THRESHOLD ) transparent++;
        }
        return ( ( double ) transparent / samples.length ) >= TRANSPARENT_RATIO;
    }
}
