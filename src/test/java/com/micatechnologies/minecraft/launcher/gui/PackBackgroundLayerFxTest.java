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

import javafx.scene.Scene;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Draws pack background images with {@link PackBackgroundLayer} beside a reference, at the
 * main-menu card, library card and detail-modal hero sizes, for a 1080p image, a 4K image, a
 * panorama, a small image and one with transparent pixels, and checks the two look the same.
 * The reference draws the full-size image, scaled to cover the box and centred, onto a canvas
 * over the same gradient; the layer decodes a smaller copy in the background. Writes
 * {@code build/target/snapshots/pack-backgrounds.png} for review: reference on the left, layer
 * on the right.
 *
 * <p>Opt-in like the other TestFX tests ({@code MMCL_RUN_TESTFX=true}).</p>
 */
@ExtendWith( ApplicationExtension.class )
@EnabledIfEnvironmentVariable( named = "MMCL_RUN_TESTFX", matches = "true" )
class PackBackgroundLayerFxTest
{
    /** The procedural gradient the card paints beneath the image. */
    private static final String GRADIENT =
            "-fx-background-color: linear-gradient(to bottom right, #2E7D32 0%, #1B5E20 55%, #102A12 100%);";

    private static final double[][] SIZES = { { 360, 150 }, { 300, 110 }, { 520, 200 } };

    private Stage stage;

    @Start
    private void start( Stage stage )
    {
        BundledFonts.ensureLoaded();
        this.stage = stage;
    }

    @Test
    void layerLooksLikeACentredCoverBackground( FxRobot robot ) throws Exception
    {
        File dir = new File( "build/target/pack-background-images" );
        dir.mkdirs();
        List< String > urls = new ArrayList<>();
        urls.add( write( dir, "hd.png", 1920, 1080, false ) );
        urls.add( write( dir, "uhd.png", 3840, 2160, false ) );
        urls.add( write( dir, "panorama.png", 3000, 500, false ) );
        urls.add( write( dir, "small.png", 240, 135, false ) );
        urls.add( write( dir, "transparent.png", 1920, 1080, true ) );

        VBox rows = new VBox( 8 );
        List< StackPane[] > pairs = new ArrayList<>();
        List< PackBackgroundLayer > layers = new ArrayList<>();
        robot.interact( () -> {
            for ( String url : urls ) {
                HBox row = new HBox( 8 );
                for ( double[] size : SIZES ) {
                    StackPane old = box( size );
                    Region under = new Region();
                    under.setStyle( GRADIENT );
                    old.getChildren().addAll( under, centredCover( new javafx.scene.image.Image( url ), size ) );

                    StackPane now = box( size );
                    Region gradient = new Region();
                    gradient.setStyle( GRADIENT );
                    PackBackgroundLayer layer = new PackBackgroundLayer();
                    layer.show( url );
                    layers.add( layer );
                    now.getChildren().addAll( gradient, layer );

                    row.getChildren().addAll( old, now );
                    pairs.add( new StackPane[]{ old, now } );
                }
                rows.getChildren().add( row );
            }
            rows.setStyle( "-fx-background-color: #101010; -fx-padding: 8;" );
            stage.setScene( new Scene( rows ) );
            stage.sizeToScene();
            stage.show();
        } );
        settle();
        // Background decodes finish off the FX thread; give them a moment, then let the fades end.
        WaitForAsyncUtils.sleep( 1500, TimeUnit.MILLISECONDS );
        settle();

        AtomicReference< WritableImage > all = new AtomicReference<>();
        robot.interact( () -> all.set( stage.getScene().snapshot( null ) ) );
        write( all.get(), new File( "build/target/snapshots/pack-backgrounds.png" ) );

        for ( StackPane[] pair : pairs ) {
            AtomicReference< WritableImage > a = new AtomicReference<>();
            AtomicReference< WritableImage > b = new AtomicReference<>();
            robot.interact( () -> {
                a.set( pair[ 0 ].snapshot( null, null ) );
                b.set( pair[ 1 ].snapshot( null, null ) );
            } );
            double diff = meanDifference( a.get(), b.get() );
            assertTrue( diff < 4.0, "reference and layer differ by " + diff + " per channel at "
                                    + pair[ 0 ].getWidth() + "x" + pair[ 0 ].getHeight() );
        }

        // Nothing for show(null); the cycle keeps the current image until the next arrives.
        PackBackgroundLayer first = layers.get( 0 );
        robot.interact( () -> {
            first.cycleTo( urls.get( 1 ) );
            assertEquals( urls.get( 1 ), first.url() );
            assertTrue( ( (javafx.scene.image.ImageView) first.getChildrenUnmodifiable().get( 0 ) ).getImage() != null,
                        "the previous image stays while the next decodes" );
            first.show( null );
            assertNull( ( (javafx.scene.image.ImageView) first.getChildrenUnmodifiable().get( 0 ) ).getImage() );
        } );
    }

    /** The reference: the whole image scaled to cover the box, centred, worked out here
     *  independently of {@link PackBackgroundLayer#coverViewport}. */
    private static javafx.scene.canvas.Canvas centredCover( javafx.scene.image.Image image, double[] size )
    {
        double w = size[ 0 ];
        double h = size[ 1 ];
        double scale = Math.max( w / image.getWidth(), h / image.getHeight() );
        double drawnW = image.getWidth() * scale;
        double drawnH = image.getHeight() * scale;
        javafx.scene.canvas.Canvas canvas = new javafx.scene.canvas.Canvas( w, h );
        canvas.getGraphicsContext2D().setImageSmoothing( true );
        canvas.getGraphicsContext2D().drawImage( image, ( w - drawnW ) / 2, ( h - drawnH ) / 2, drawnW, drawnH );
        return canvas;
    }

    private static StackPane box( double[] size )
    {
        StackPane box = new StackPane();
        box.setMinSize( size[ 0 ], size[ 1 ] );
        box.setPrefSize( size[ 0 ], size[ 1 ] );
        box.setMaxSize( size[ 0 ], size[ 1 ] );
        Rectangle clip = ShapeScale.round( new Rectangle( size[ 0 ], size[ 1 ] ), ShapeScale.LARGE );
        box.setClip( clip );
        return box;
    }

    /** Writes a synthetic "screenshot": a sky gradient, hills, a sun and fine stripes. */
    private static String write( File dir, String name, int w, int h, boolean transparentHalf ) throws Exception
    {
        BufferedImage img = new BufferedImage( w, h, BufferedImage.TYPE_INT_ARGB );
        Graphics2D g = img.createGraphics();
        g.setRenderingHint( RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON );
        g.setPaint( new GradientPaint( 0, 0, new Color( 70, 140, 210 ), 0, h, new Color( 240, 200, 120 ) ) );
        g.fillRect( 0, 0, w, h );
        g.setColor( new Color( 255, 230, 90 ) );
        g.fillOval( w / 2 - h / 6, h / 5, h / 3, h / 3 );
        g.setColor( new Color( 60, 130, 60 ) );
        for ( int i = 0; i < 6; i++ ) {
            g.fillOval( i * w / 5 - w / 8, h * 2 / 3, w / 3, h / 2 );
        }
        g.setColor( new Color( 120, 80, 40 ) );
        g.setStroke( new BasicStroke( Math.max( 2, w / 400f ) ) );
        for ( int x = 0; x < w; x += Math.max( 8, w / 60 ) ) {
            g.drawLine( x, h - h / 8, x + w / 40, h );
        }
        g.dispose();
        if ( transparentHalf ) {
            for ( int y = 0; y < h; y++ ) {
                for ( int x = w / 2; x < w; x++ ) {
                    img.setRGB( x, y, img.getRGB( x, y ) & 0x00FFFFFF );
                }
            }
        }
        File file = new File( dir, name );
        ImageIO.write( img, "png", file );
        return file.toURI().toString();
    }

    private static double meanDifference( WritableImage a, WritableImage b )
    {
        int w = (int) Math.min( a.getWidth(), b.getWidth() );
        int h = (int) Math.min( a.getHeight(), b.getHeight() );
        PixelReader ra = a.getPixelReader();
        PixelReader rb = b.getPixelReader();
        long sum = 0;
        for ( int y = 0; y < h; y++ ) {
            for ( int x = 0; x < w; x++ ) {
                int pa = ra.getArgb( x, y );
                int pb = rb.getArgb( x, y );
                for ( int shift = 0; shift <= 16; shift += 8 ) {
                    sum += Math.abs( ( ( pa >> shift ) & 0xFF ) - ( ( pb >> shift ) & 0xFF ) );
                }
            }
        }
        return sum / ( 3.0 * w * h );
    }

    private static void settle()
    {
        WaitForAsyncUtils.waitForFxEvents();
        WaitForAsyncUtils.sleep( 300, TimeUnit.MILLISECONDS );
        WaitForAsyncUtils.waitForFxEvents();
    }

    private static void write( WritableImage image, File out ) throws Exception
    {
        int w = (int) image.getWidth();
        int h = (int) image.getHeight();
        BufferedImage buf = new BufferedImage( w, h, BufferedImage.TYPE_INT_ARGB );
        PixelReader reader = image.getPixelReader();
        for ( int y = 0; y < h; y++ ) {
            for ( int x = 0; x < w; x++ ) {
                buf.setRGB( x, y, reader.getArgb( x, y ) );
            }
        }
        out.getParentFile().mkdirs();
        ImageIO.write( buf, "png", out );
    }
}
