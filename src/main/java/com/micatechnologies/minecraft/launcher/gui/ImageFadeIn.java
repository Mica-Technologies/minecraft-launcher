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

import javafx.animation.FadeTransition;
import javafx.beans.value.ChangeListener;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.util.Duration;

/**
 * Helper for cross-fading background-loaded images into view as their bytes arrive.
 *
 * <p>The launcher's hero-card surfaces all use {@code new Image(url, true)} so the
 * image-loader thread fetches and decodes off the FX thread. The ImageView the bitmap
 * eventually lands in renders <em>nothing</em> until that point — on a cold network
 * the visual result is a card with a blank logo slot, then a sudden pop-in when the
 * bytes arrive. Wrapping each ImageView in this helper makes the appearance smooth
 * instead: the view starts at opacity zero, and once the underlying {@link Image}
 * reports progress 1.0 (or already-loaded if the image was cache-hot) a short
 * {@link FadeTransition} ramps opacity to 1.</p>
 *
 * <p>Cheap: one {@code progressProperty} listener per ImageView, removed once the
 * fade fires. No timers, no polling.</p>
 *
 * @author Mica Technologies
 * @since 3.5
 */
final class ImageFadeIn
{
    /** Fade duration in milliseconds. Picked to feel responsive on a fast network
     *  (image already cache-hot — the user sees a near-instant cross-fade) without
     *  feeling like a deliberate animation on a slow one. */
    private static final int FADE_MS = 220;

    /**
     * Private constructor to prevent instantiation of this utility class.
     */
    private ImageFadeIn() { /* static-only */ }

    /**
     * Installs the fade-in behaviour on the given {@link ImageView}. The view's
     * opacity is set to zero immediately; once the bound Image finishes loading
     * (or if it was already loaded when the helper attached), a fade transition
     * ramps it back to one over {@link #FADE_MS} milliseconds.
     *
     * <p>If the ImageView has no image attached when this is called, it stays
     * fully opaque — no fade needed because there's nothing to wait for, and
     * the caller may set the image later via {@code setImage}.</p>
     *
     * @param view the image view to install the fade on; null-safe (no-op)
     */
    static void apply( ImageView view )
    {
        if ( view == null ) {
            return;
        }
        Image image = view.getImage();
        if ( image == null ) {
            return;
        }
        // Already loaded (cache hit, classpath URL, etc.) — no fade needed.
        if ( image.getProgress() >= 1.0 && !image.isError() ) {
            return;
        }
        view.setOpacity( 0.0 );
        // Both listeners self-remove (and remove each other) once the load reaches a
        // terminal state — fired or errored — so the fade can't double-fire on a second
        // progress==1.0 notification and neither lambda's captured ImageView is pinned in
        // a (potentially shared/cached) Image's listener list for longer than needed.
        // Boxed in single-element arrays so each listener can reference the pair.
        final ChangeListener< Number >[] progressListener = new ChangeListener[ 1 ];
        final ChangeListener< Boolean >[] errorListener = new ChangeListener[ 1 ];
        final Runnable detach = () -> {
            if ( progressListener[ 0 ] != null ) {
                image.progressProperty().removeListener( progressListener[ 0 ] );
            }
            if ( errorListener[ 0 ] != null ) {
                image.errorProperty().removeListener( errorListener[ 0 ] );
            }
        };
        progressListener[ 0 ] = ( obs, oldVal, newVal ) -> {
            if ( newVal.doubleValue() >= 1.0 ) {
                detach.run();
                FadeTransition fade = new FadeTransition( Duration.millis( FADE_MS ), view );
                fade.setFromValue( 0.0 );
                fade.setToValue( 1.0 );
                fade.play();
            }
        };
        // Failure path: image errored before reaching 1.0 progress. Snap back to
        // visible so the (now-broken) ImageView still shows whatever fallback the
        // caller had wired (placeholder pixels, null bitmap, etc.) rather than
        // staying invisible forever.
        errorListener[ 0 ] = ( obs, oldVal, errored ) -> {
            if ( Boolean.TRUE.equals( errored ) ) {
                detach.run();
                view.setOpacity( 1.0 );
            }
        };
        image.progressProperty().addListener( progressListener[ 0 ] );
        image.errorProperty().addListener( errorListener[ 0 ] );
    }
}
