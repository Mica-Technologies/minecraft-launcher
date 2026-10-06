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
import javafx.geometry.Rectangle2D;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Region;
import javafx.stage.Window;
import javafx.util.Duration;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A pack's background image on a card or the detail modal's hero, drawn over the procedural
 * gradient that stays on the layer beneath it as the placeholder.
 *
 * <p>The image used to be set as an inline {@code -fx-background-image}. CSS loads such an image
 * synchronously, at full resolution, on the FX thread during the CSS pass: a 1080p to 4K
 * background is 8 to 30 MB decoded, paid on every card bind, page change and image-cycle tick.
 * This layer decodes the image in the background at the size it is shown (times the screen's
 * scale) and keeps recent decodes in a small cache, so revisiting a page or cycling back to an
 * image is free.</p>
 *
 * <p>It draws the image like the CSS it replaced: scaled to cover the layer from its top-left
 * corner (see {@link #coverViewport}), with the gradient showing until it arrives and through
 * any transparent pixels. Build and use on the FX thread.</p>
 *
 * @since 2026.10
 */
final class PackBackgroundLayer extends Region
{
    /** Decoded images kept for reuse, by URL and decode size, least recently used first. */
    private static final Map< String, Image > CACHE = new LinkedHashMap<>( 32, 0.75f, true );

    /** Budget for the cache's decoded pixels, in bytes. */
    private static final long CACHE_BYTES = 96L * 1024 * 1024;

    /** Decode widths are rounded up to a multiple of this, so near-equal sizes share a decode. */
    private static final int WIDTH_STEP = 64;

    /** Fade for an image arriving where none was shown; matches {@link ImageFadeIn}. */
    private static final Duration FADE = Duration.millis( 220 );

    private final ImageView view = new ImageView();

    /** The URL to show, or {@code null} for none. */
    private String url;
    /** The image being waited for, and the listener waiting on it. */
    private Image                     pending;
    private ChangeListener< Number >  pendingListener;
    /** The decode size the shown or pending image was requested at, in device pixels. */
    private int                       requestedWidth;
    private int                       requestedHeight;

    /** Creates an empty layer. */
    PackBackgroundLayer()
    {
        getStyleClass().add( "packBackgroundLayer" );
        setMouseTransparent( true );
        setFocusTraversable( false );
        view.setSmooth( true );
        view.setManaged( false );
        getChildren().add( view );
    }

    /**
     * Shows the image at {@code imageUrl}, or nothing for {@code null}. A different image showing
     * now is cleared at once, so the gradient beneath shows until the new one arrives.
     *
     * @param imageUrl the image's URL, usually a {@code file:} URL into the pack's image cache
     */
    void show( String imageUrl )
    {
        show( imageUrl, false );
    }

    /**
     * Changes the image, keeping the current one on screen until the new one is decoded. For the
     * image cycle, where falling back to the gradient between two images would flash.
     *
     * @param imageUrl the next image's URL, or {@code null} for none
     */
    void cycleTo( String imageUrl )
    {
        show( imageUrl, true );
    }

    /** @return the URL shown or being loaded, or {@code null} */
    String url()
    {
        return url;
    }

    private void show( String imageUrl, boolean keepCurrent )
    {
        if ( imageUrl != null && imageUrl.equals( url ) ) {
            return;
        }
        url = imageUrl;
        cancelPending();
        requestedWidth = 0;
        requestedHeight = 0;
        if ( imageUrl == null || !keepCurrent ) {
            view.setImage( null );
        }
        requestLayout();
    }

    @Override
    protected double computePrefWidth( double height )
    {
        return 0;
    }

    @Override
    protected double computePrefHeight( double width )
    {
        return 0;
    }

    @Override
    protected double computeMinWidth( double height )
    {
        return 0;
    }

    @Override
    protected double computeMinHeight( double width )
    {
        return 0;
    }

    @Override
    protected void layoutChildren()
    {
        double w = getWidth();
        double h = getHeight();
        if ( url != null && w > 0 && h > 0 && getScene() != null ) {
            requestIfNeeded( w, h );
        }
        fit( w, h );
    }

    /** Sizes the view to cover the layer, cropping the image's overflow at the right and bottom. */
    private void fit( double w, double h )
    {
        Image image = view.getImage();
        view.relocate( 0, 0 );
        view.setFitWidth( w );
        view.setFitHeight( h );
        if ( image == null || image.getWidth() <= 0 || image.getHeight() <= 0 || w <= 0 || h <= 0 ) {
            view.setViewport( null );
            return;
        }
        view.setViewport( coverViewport( image.getWidth(), image.getHeight(), w, h ) );
    }

    /**
     * The part of an image that covers a box when scaled to fill it, anchored at the image's
     * top-left corner. That is how the CSS this layer replaced actually drew it: its rule said
     * {@code background-size: cover; background-position: center}, but JavaFX rendered the
     * image from the top-left, cropping only the right and bottom, and cards have always looked
     * that way. Pure, for testing.
     *
     * @param imageW the image's width
     * @param imageH the image's height
     * @param boxW   the box's width
     * @param boxH   the box's height
     *
     * @return the image region to show
     */
    static Rectangle2D coverViewport( double imageW, double imageH, double boxW, double boxH )
    {
        double scale = Math.max( boxW / imageW, boxH / imageH );
        double vw = Math.min( imageW, boxW / scale );
        double vh = Math.min( imageH, boxH / scale );
        return new Rectangle2D( 0, 0, vw, vh );
    }

    /**
     * The width to decode an image at so that, scaled to its width, it covers a box. Rounded up
     * so near-equal sizes share one decode. Pure, for testing.
     *
     * @param boxW  the box's width, in layout pixels
     * @param scale device pixels per layout pixel
     *
     * @return the decode width, in device pixels
     */
    static int decodeWidth( double boxW, double scale )
    {
        int px = (int) Math.ceil( boxW * scale );
        return Math.max( WIDTH_STEP, ( px + WIDTH_STEP - 1 ) / WIDTH_STEP * WIDTH_STEP );
    }

    private void requestIfNeeded( double w, double h )
    {
        double scale = deviceScale();
        int needW = decodeWidth( w, scale );
        int needH = (int) Math.ceil( h * scale );
        if ( requestedWidth >= needW && requestedHeight >= needH ) {
            return;
        }
        requestedWidth = needW;
        requestedHeight = needH;
        request( url, needW, 0 );
    }

    /**
     * Shows the cached decode or starts one. A decode is by width (keeping the aspect ratio);
     * an image wider than the box needs decodes by height instead, which {@link #arrived} handles.
     */
    private void request( String forUrl, int decodeW, int decodeH )
    {
        String key = forUrl + '|' + decodeW + 'x' + decodeH;
        Image image = CACHE.get( key );
        if ( image == null || image.isError() ) {
            image = new Image( forUrl, decodeW, decodeH, true, true, true );
            CACHE.put( key, image );
            trimCache();
        }
        if ( image.getProgress() >= 1.0 ) {
            arrived( image, forUrl, decodeH > 0, false );
            return;
        }
        cancelPending();
        Image waiting = image;
        pending = image;
        pendingListener = ( obs, was, now ) -> {
            if ( now.doubleValue() >= 1.0 ) {
                cancelPending();
                arrived( waiting, forUrl, decodeH > 0, true );
            }
        };
        image.progressProperty().addListener( pendingListener );
    }

    private void arrived( Image image, String forUrl, boolean byHeight, boolean late )
    {
        if ( !forUrl.equals( url ) || image.isError() ) {
            return;
        }
        // A panorama decoded by width falls short of the box's height: decode it by height.
        if ( !byHeight && image.getHeight() > 0 && image.getHeight() + 1 < requestedHeight ) {
            request( forUrl, 0, requestedHeight );
            return;
        }
        boolean wasEmpty = view.getImage() == null;
        view.setImage( image );
        fit( getWidth(), getHeight() );
        if ( late && wasEmpty ) {
            view.setOpacity( 0 );
            FadeTransition fade = new FadeTransition( FADE, view );
            fade.setToValue( 1 );
            fade.play();
        }
        else {
            view.setOpacity( 1 );
        }
    }

    private void cancelPending()
    {
        if ( pending != null && pendingListener != null ) {
            pending.progressProperty().removeListener( pendingListener );
        }
        pending = null;
        pendingListener = null;
    }

    /** Device pixels per layout pixel here: the window's output scale times any scene scaling. */
    private double deviceScale()
    {
        double scale = 1;
        Window window = getScene() == null ? null : getScene().getWindow();
        if ( window != null && window.getOutputScaleX() > 0 ) {
            scale = window.getOutputScaleX();
        }
        double nodeScale = Math.abs( getLocalToSceneTransform().getMxx() );
        return scale * ( nodeScale > 0 ? nodeScale : 1 );
    }

    /** Evicts the least recently used decodes until the cache fits its budget. */
    private static void trimCache()
    {
        long total = 0;
        for ( Image image : CACHE.values() ) {
            total += bytes( image );
        }
        Iterator< Image > it = CACHE.values().iterator();
        // Keep the newest entry even if it alone is over budget.
        while ( total > CACHE_BYTES && CACHE.size() > 1 && it.hasNext() ) {
            total -= bytes( it.next() );
            it.remove();
        }
    }

    /** Decoded size of an image; for one still loading, its requested size at 16:9. */
    private static long bytes( Image image )
    {
        double w = image.getWidth() > 0 ? image.getWidth() : image.getRequestedWidth();
        double h = image.getHeight() > 0 ? image.getHeight()
                                         : ( image.getRequestedHeight() > 0 ? image.getRequestedHeight() : w * 9 / 16 );
        if ( w <= 0 ) {
            w = h * 16 / 9;
        }
        return (long) ( w * h * 4 );
    }
}
