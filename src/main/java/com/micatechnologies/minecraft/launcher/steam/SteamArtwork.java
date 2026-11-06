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

package com.micatechnologies.minecraft.launcher.steam;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * The library artwork for a Steam entry, composed from a modpack's logo and background and
 * written to {@code userdata/<account>/config/grid} under the entry's grid ID:
 * {@code <id>p.png} (portrait capsule, 600×900), {@code <id>.png} (wide capsule, 920×430),
 * {@code <id>_hero.png} (banner, 1920×620), {@code <id>_logo.png} with its {@code <id>.json}
 * placement, and {@code <id>_icon.png} (256×256, which the entry's {@code icon} field points at).
 *
 * <p>Without a background the art uses a plain dark panel; without a logo no logo file is written,
 * so Steam shows the entry's title as text. Uses AWT only, so it works headless.
 *
 * @since 2026.11
 */
public final class SteamArtwork
{
    /** Steam's default logo placement; current clients may not show a logo without it. */
    static final String LOGO_POSITION_JSON = "{\"nVersion\":1,\"logoPosition\":{\"pinnedPosition\":\"BottomLeft\","
                                             + "\"nWidthPct\":50,\"nHeightPct\":50,\"nVersion\":1}}";

    private static final Color PANEL_TOP = new Color( 0x24, 0x3B, 0x2A );
    private static final Color PANEL_BOTTOM = new Color( 0x10, 0x14, 0x12 );

    private SteamArtwork() { /* static-only */ }

    /**
     * Writes every artwork file for an entry.
     *
     * @param gridFolder the account's {@code config/grid} folder (created when missing)
     * @param gridId     the entry's grid ID ({@link SteamShortcuts#gridId(int)})
     * @param logo       the logo, or {@code null}
     * @param background the background, or {@code null}
     *
     * @return the icon file, for the entry's {@code icon} field
     *
     * @throws IOException when a file can't be written
     * @since 2026.11
     */
    public static Path write( Path gridFolder, long gridId, BufferedImage logo, BufferedImage background )
            throws IOException
    {
        Files.createDirectories( gridFolder );
        String id = Long.toString( gridId );
        writePng( capsule( logo, background, 600, 900, 0.8 ), gridFolder.resolve( id + "p.png" ) );
        writePng( capsule( logo, background, 920, 430, 0.55 ), gridFolder.resolve( id + ".png" ) );
        writePng( capsule( null, background, 1920, 620, 0 ), gridFolder.resolve( id + "_hero.png" ) );
        if ( logo != null ) {
            writePng( fit( logo, 960, 540 ), gridFolder.resolve( id + "_logo.png" ) );
            Files.writeString( gridFolder.resolve( id + ".json" ), LOGO_POSITION_JSON, StandardCharsets.UTF_8 );
        }
        Path icon = gridFolder.resolve( id + "_icon.png" );
        writePng( square( logo != null ? logo : background, 256 ), icon );
        return icon;
    }

    /**
     * The artwork files an entry has, for removing them with the entry.
     *
     * @param gridFolder the account's {@code config/grid} folder
     * @param gridId     the entry's grid ID
     *
     * @return the files that may exist
     *
     * @since 2026.11
     */
    public static List< Path > files( Path gridFolder, long gridId )
    {
        String id = Long.toString( gridId );
        List< Path > files = new ArrayList<>();
        for ( String suffix : List.of( "p.png", ".png", "_hero.png", "_logo.png", ".json", "_icon.png" ) ) {
            files.add( gridFolder.resolve( id + suffix ) );
        }
        return files;
    }

    /**
     * Reads an image file, or {@code null} when it is missing or not an image ImageIO knows.
     *
     * @param file the file, or {@code null}
     *
     * @return the image, or {@code null}
     *
     * @since 2026.11
     */
    public static BufferedImage read( String file )
    {
        if ( file == null || file.isBlank() ) {
            return null;
        }
        try {
            return ImageIO.read( new File( file ) );
        }
        catch ( IOException | RuntimeException e ) {
            return null;
        }
    }

    /**
     * A capsule: the background cropped to fill, darkened at the bottom for contrast, with the
     * logo centred at the given share of the width.
     */
    static BufferedImage capsule( BufferedImage logo, BufferedImage background, int width, int height,
                                  double logoWidthShare )
    {
        BufferedImage out = new BufferedImage( width, height, BufferedImage.TYPE_INT_ARGB );
        Graphics2D g = smooth( out );
        if ( background != null ) {
            double scale = Math.max( ( double ) width / background.getWidth(),
                                     ( double ) height / background.getHeight() );
            int w = ( int ) Math.ceil( background.getWidth() * scale );
            int h = ( int ) Math.ceil( background.getHeight() * scale );
            g.drawImage( background, ( width - w ) / 2, ( height - h ) / 2, w, h, null );
            g.setPaint( new GradientPaint( 0, height * 0.4f, new Color( 0, 0, 0, 0 ), 0, height,
                                           new Color( 0, 0, 0, 140 ) ) );
        }
        else {
            g.setPaint( new GradientPaint( 0, 0, PANEL_TOP, 0, height, PANEL_BOTTOM ) );
        }
        g.fillRect( 0, 0, width, height );
        if ( logo != null && logoWidthShare > 0 ) {
            BufferedImage fitted = fit( logo, ( int ) ( width * logoWidthShare ), ( int ) ( height * 0.6 ) );
            g.drawImage( fitted, ( width - fitted.getWidth() ) / 2, ( height - fitted.getHeight() ) / 2, null );
        }
        g.dispose();
        return out;
    }

    /** The image scaled to fit inside the box, keeping its shape and transparency. */
    static BufferedImage fit( BufferedImage image, int maxWidth, int maxHeight )
    {
        double scale = Math.min( ( double ) maxWidth / image.getWidth(), ( double ) maxHeight / image.getHeight() );
        int w = Math.max( 1, ( int ) Math.round( image.getWidth() * scale ) );
        int h = Math.max( 1, ( int ) Math.round( image.getHeight() * scale ) );
        BufferedImage out = new BufferedImage( w, h, BufferedImage.TYPE_INT_ARGB );
        Graphics2D g = smooth( out );
        g.drawImage( image, 0, 0, w, h, null );
        g.dispose();
        return out;
    }

    /** The image fitted and centred in a transparent square; a plain panel when there is none. */
    static BufferedImage square( BufferedImage image, int size )
    {
        if ( image == null ) {
            return capsule( null, null, size, size, 0 );
        }
        BufferedImage out = new BufferedImage( size, size, BufferedImage.TYPE_INT_ARGB );
        BufferedImage fitted = fit( image, size, size );
        Graphics2D g = smooth( out );
        g.drawImage( fitted, ( size - fitted.getWidth() ) / 2, ( size - fitted.getHeight() ) / 2, null );
        g.dispose();
        return out;
    }

    private static Graphics2D smooth( BufferedImage image )
    {
        Graphics2D g = image.createGraphics();
        g.setRenderingHint( RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC );
        g.setRenderingHint( RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY );
        g.setRenderingHint( RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON );
        return g;
    }

    private static void writePng( BufferedImage image, Path file ) throws IOException
    {
        Path temp = file.resolveSibling( file.getFileName() + ".tmp" );
        if ( !ImageIO.write( image, "png", temp.toFile() ) ) {
            throw new IOException( "No PNG writer available" );
        }
        Files.move( temp, file, StandardCopyOption.REPLACE_EXISTING );
    }
}
