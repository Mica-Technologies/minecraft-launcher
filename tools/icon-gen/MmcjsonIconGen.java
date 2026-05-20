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

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.GradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.GeneralPath;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Build-tool one-shot for the .mmcjson document icon. Composes the launcher
 * logo (smaller, top-left) with a document badge in the lower-right corner
 * so Finder / Explorer / Linux file managers visually distinguish a
 * modpack file from a generic launcher-association entry.
 *
 * <p>Run from the repo root with:</p>
 * <pre>
 *   java --source 26 tools/icon-gen/MmcjsonIconGen.java
 * </pre>
 *
 * <p>Outputs:</p>
 * <ul>
 *   <li>{@code deploy/linux/Mica Minecraft Modpack.png} — 1024×1024 RGBA PNG.</li>
 *   <li>{@code deploy/windows/Mica Minecraft Modpack.ico} — multi-size ICO
 *       (16/32/48/64/128/256) with PNG-embedded images.</li>
 *   <li>{@code deploy/mac/Mica Minecraft Modpack.icns} — multi-size ICNS
 *       (128/256/512/1024 + 256/512/1024 retina) with PNG-embedded images.</li>
 * </ul>
 *
 * <p>No external dependencies — pure JDK ImageIO + manual ICO / ICNS
 * encoding using the PNG-embedded variants (universally supported on
 * Windows 10+ and macOS 10.7+).</p>
 */
public class MmcjsonIconGen
{
    private static final int CANVAS = 1024;

    /** Sizes to emit per-platform.
     *  ICO traditionally caps at 256; jpackage's --icon on Windows accepts both.
     *  ICNS pairs each retina size with its non-retina sibling (256@2x = 512px). */
    private static final int[] ICO_SIZES = { 16, 32, 48, 64, 128, 256 };

    /** ICNS OSType codes paired with their pixel dimensions. ic07-ic10 are
     *  the non-retina codes; ic11-ic14 are the retina @2x variants of
     *  16/32/128/256. macOS picks the appropriate variant from a single
     *  .icns at runtime. */
    private static final IcnsEntry[] ICNS_ENTRIES = {
            new IcnsEntry( "ic07", 128 ),
            new IcnsEntry( "ic08", 256 ),
            new IcnsEntry( "ic09", 512 ),
            new IcnsEntry( "ic10", 1024 ),
            new IcnsEntry( "ic11", 32 ),
            new IcnsEntry( "ic12", 64 ),
            new IcnsEntry( "ic13", 256 ),
            new IcnsEntry( "ic14", 512 ),
    };

    public static void main( String[] args ) throws Exception
    {
        Path repoRoot = Paths.get( "" ).toAbsolutePath();
        Path launcherPng = repoRoot.resolve( "src/main/resources/micaminecraftlauncher.png" );
        if ( !Files.exists( launcherPng ) ) {
            // Fallback: caller may have run from tools/icon-gen/ — walk up two dirs.
            launcherPng = repoRoot.resolveSibling( "src/main/resources/micaminecraftlauncher.png" );
        }
        if ( !Files.exists( launcherPng ) ) {
            System.err.println( "Could not find launcher PNG at " + launcherPng );
            System.err.println( "Run this from the repo root: java --source 26 tools/icon-gen/MmcjsonIconGen.java" );
            System.exit( 1 );
        }

        System.out.println( "Loading launcher logo from " + launcherPng );
        BufferedImage launcherLogo = ImageIO.read( launcherPng.toFile() );

        System.out.println( "Composing " + CANVAS + "x" + CANVAS + " modpack-file icon..." );
        BufferedImage iconLarge = composeIcon( launcherLogo, CANVAS );

        // Linux: just the largest PNG.
        Path linuxOut = repoRoot.resolve( "deploy/linux/Mica Minecraft Modpack.png" );
        Files.createDirectories( linuxOut.getParent() );
        ImageIO.write( iconLarge, "PNG", linuxOut.toFile() );
        System.out.println( "  wrote " + linuxOut );

        // Windows: ICO with multi-size PNG-embedded entries.
        Path winOut = repoRoot.resolve( "deploy/windows/Mica Minecraft Modpack.ico" );
        Files.createDirectories( winOut.getParent() );
        writeIco( iconLarge, ICO_SIZES, winOut );
        System.out.println( "  wrote " + winOut );

        // macOS: ICNS with the standard PNG-embedded element set.
        Path macOut = repoRoot.resolve( "deploy/mac/Mica Minecraft Modpack.icns" );
        Files.createDirectories( macOut.getParent() );
        writeIcns( iconLarge, ICNS_ENTRIES, macOut );
        System.out.println( "  wrote " + macOut );

        System.out.println( "Done." );
    }

    // ====================================================================
    // Composition
    // ====================================================================

    /** Renders the modpack-file icon at {@code size}×{@code size}.
     *
     *  <p>Layout: launcher logo scaled to ~68% in the upper-left, then a
     *  document badge in the lower-right corner with a folded-corner motif
     *  + three text-suggestion lines. The badge is drawn with a soft drop
     *  shadow so it lifts off the logo on light backgrounds without
     *  blowing out the alpha channel on dark ones.</p> */
    private static BufferedImage composeIcon( BufferedImage launcherLogo, int size )
    {
        BufferedImage out = new BufferedImage( size, size, BufferedImage.TYPE_INT_ARGB );
        Graphics2D g = out.createGraphics();
        g.setRenderingHint( RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON );
        g.setRenderingHint( RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC );
        g.setRenderingHint( RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY );

        // ---- Launcher logo: 68% scale anchored top-left ----
        double logoScale = 0.68;
        int logoSize = (int) Math.round( size * logoScale );
        int logoOffset = (int) Math.round( size * 0.02 );
        g.drawImage( launcherLogo, logoOffset, logoOffset, logoSize, logoSize, null );

        // ---- Document badge: lower-right corner, ~46% of canvas ----
        double badgeScale = 0.46;
        int badgeW = (int) Math.round( size * badgeScale );
        int badgeH = (int) Math.round( size * badgeScale * 1.15 );  // taller than wide → document feel
        int badgeX = size - badgeW - (int) Math.round( size * 0.03 );
        int badgeY = size - badgeH - (int) Math.round( size * 0.03 );

        drawDocumentBadge( g, badgeX, badgeY, badgeW, badgeH, size );

        g.dispose();
        return out;
    }

    /** Draws the document badge with folded corner + text-suggestion lines.
     *
     *  <p>Drawn in screen-space units that scale linearly with {@code canvasSize}
     *  so the badge looks correct at every output resolution.</p> */
    private static void drawDocumentBadge( Graphics2D g, int x, int y, int w, int h, int canvasSize )
    {
        double scale = canvasSize / 1024.0;
        int foldSize = (int) Math.round( w * 0.30 );
        int cornerR = (int) Math.round( 28 * scale );
        int borderW = (int) Math.round( 14 * scale );

        // ---- Drop shadow under the badge (4 stacked offset blurs for cheap soft edge) ----
        Color shadowBase = new Color( 0, 0, 0, 70 );
        g.setComposite( AlphaComposite.SrcOver );
        for ( int i = 0; i < 4; i++ ) {
            int offset = (int) Math.round( ( 6 + i * 4 ) * scale );
            g.setColor( new Color( 0, 0, 0, Math.max( 8, 50 / ( i + 1 ) ) ) );
            g.fillRoundRect( x + offset, y + offset, w, h, cornerR + i * 2, cornerR + i * 2 );
        }

        // ---- Document body: clipped rounded rect with the upper-right corner taken out ----
        Path2D docShape = buildDocumentShape( x, y, w, h, foldSize, cornerR );

        // Paper fill: a vertical gradient from off-white at top → very pale gray at bottom so the
        // badge reads as a 3D card rather than a flat sticker.
        GradientPaint paperPaint = new GradientPaint(
                x, y, new Color( 0xFD, 0xFD, 0xFB ),
                x, y + h, new Color( 0xEC, 0xEC, 0xE7 ) );
        g.setPaint( paperPaint );
        g.fill( docShape );

        // ---- Folded corner triangle (lighter than the document body) ----
        Path2D fold = new Path2D.Double();
        fold.moveTo( x + w - foldSize, y );
        fold.lineTo( x + w, y + foldSize );
        fold.lineTo( x + w - foldSize, y + foldSize );
        fold.closePath();
        g.setColor( new Color( 0xC8, 0xC8, 0xC2 ) );
        g.fill( fold );

        // Subtle highlight along the fold's diagonal edge
        g.setColor( new Color( 0x9A, 0x9A, 0x90 ) );
        g.setStroke( new BasicStroke( Math.max( 2, borderW * 0.4f ), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND ) );
        g.drawLine( x + w - foldSize, y, x + w, y + foldSize );

        // ---- Outer border in the launcher's signature dark-gray ----
        g.setColor( new Color( 0x2D, 0x2A, 0x26 ) );
        g.setStroke( new BasicStroke( borderW, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND ) );
        g.draw( docShape );

        // ---- Text-suggestion lines (3 horizontal lines on the paper) ----
        // Lines are in the launcher's green/brown palette so the badge ties back to the parent logo
        // rather than looking like a generic system icon.
        Color line1 = new Color( 0x55, 0x3F, 0x29 );   // brown
        Color line2 = new Color( 0x2E, 0x7A, 0x2A );   // dark green
        Color line3 = new Color( 0x55, 0x3F, 0x29 );   // brown
        int lineStroke = (int) Math.round( Math.max( 4, borderW * 0.7 ) );
        g.setStroke( new BasicStroke( lineStroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND ) );

        // Padded inset, accounting for the corner fold on the topmost line
        int padX = (int) Math.round( w * 0.16 );
        int textBlockTop = y + foldSize + (int) Math.round( h * 0.10 );
        int textBlockBottom = y + h - (int) Math.round( h * 0.20 );
        int lineSpacing = ( textBlockBottom - textBlockTop ) / 2;
        int lineFullWidth = w - 2 * padX;

        g.setColor( line1 );
        g.drawLine( x + padX, textBlockTop, x + padX + lineFullWidth, textBlockTop );
        g.setColor( line2 );
        // Middle line slightly shorter → suggests the modpack name/title row
        g.drawLine( x + padX, textBlockTop + lineSpacing,
                    x + padX + (int) ( lineFullWidth * 0.85 ), textBlockTop + lineSpacing );
        g.setColor( line3 );
        // Bottom line shortest → metadata row
        g.drawLine( x + padX, textBlockBottom,
                    x + padX + (int) ( lineFullWidth * 0.55 ), textBlockBottom );
    }

    /** Builds a document outline: rounded rectangle with the upper-right corner
     *  cut diagonally so the badge looks like a folded sheet. The corner cut
     *  is sized by {@code foldSize}; {@code cornerR} controls the radius of
     *  the three non-folded corners. */
    private static Path2D buildDocumentShape( int x, int y, int w, int h, int foldSize, int cornerR )
    {
        Path2D p = new Path2D.Double();
        // Top edge: start cornerR in from the left, walk right until the fold start.
        p.moveTo( x + cornerR, y );
        p.lineTo( x + w - foldSize, y );
        // Fold diagonal: down-right to where the right edge resumes
        p.lineTo( x + w, y + foldSize );
        // Right edge: down to the bottom-right corner
        p.lineTo( x + w, y + h - cornerR );
        // Bottom-right rounded corner
        p.quadTo( x + w, y + h, x + w - cornerR, y + h );
        // Bottom edge
        p.lineTo( x + cornerR, y + h );
        // Bottom-left rounded corner
        p.quadTo( x, y + h, x, y + h - cornerR );
        // Left edge
        p.lineTo( x, y + cornerR );
        // Top-left rounded corner
        p.quadTo( x, y, x + cornerR, y );
        p.closePath();
        return p;
    }

    // ====================================================================
    // ICO encoder
    // ====================================================================

    /** Writes a multi-size ICO file with PNG-embedded images. Each emitted size
     *  is a fresh re-render (we re-call composeIcon at the target size rather
     *  than blindly downscaling the 1024 version), which keeps the badge's
     *  text-suggestion lines crisp at 16px / 32px where straight downscaling
     *  would alias them into mush. */
    private static void writeIco( BufferedImage referenceLogo, int[] sizes, Path out ) throws IOException
    {
        // referenceLogo isn't actually used — we re-load the launcher PNG so each
        // size renders from the source rather than the (potentially downscaled)
        // composed 1024 version. Kept in the signature for API symmetry with
        // writeIcns and to leave room for a fast-path that reuses the largest
        // composed image if the source-load cost ever becomes a concern.
        BufferedImage launcherLogo = ImageIO.read( Paths.get( "src/main/resources/micaminecraftlauncher.png" ).toFile() );

        byte[][] pngs = new byte[ sizes.length ][];
        for ( int i = 0; i < sizes.length; i++ ) {
            BufferedImage rendered = composeIcon( launcherLogo, sizes[ i ] );
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write( rendered, "PNG", bos );
            pngs[ i ] = bos.toByteArray();
        }

        try ( OutputStream fos = Files.newOutputStream( out );
              DataOutputStream dos = new DataOutputStream( fos ) ) {
            // ICONDIR header
            writeUInt16LE( dos, 0 );                  // reserved
            writeUInt16LE( dos, 1 );                  // type = ICO
            writeUInt16LE( dos, sizes.length );       // count

            int offset = 6 + sizes.length * 16;       // header + directory entries
            for ( int i = 0; i < sizes.length; i++ ) {
                int dim = sizes[ i ];
                // ICONDIRENTRY: width / height are uint8 with 0 meaning 256+
                dos.writeByte( dim == 256 ? 0 : dim );
                dos.writeByte( dim == 256 ? 0 : dim );
                dos.writeByte( 0 );                   // color count
                dos.writeByte( 0 );                   // reserved
                writeUInt16LE( dos, 1 );              // color planes
                writeUInt16LE( dos, 32 );             // bpp (PNG is RGBA → tag as 32)
                writeUInt32LE( dos, pngs[ i ].length );
                writeUInt32LE( dos, offset );
                offset += pngs[ i ].length;
            }

            for ( byte[] png : pngs ) {
                dos.write( png );
            }
        }
    }

    private static void writeUInt16LE( DataOutputStream dos, int v ) throws IOException
    {
        dos.writeByte( v & 0xFF );
        dos.writeByte( ( v >> 8 ) & 0xFF );
    }

    private static void writeUInt32LE( DataOutputStream dos, int v ) throws IOException
    {
        dos.writeByte( v & 0xFF );
        dos.writeByte( ( v >> 8 ) & 0xFF );
        dos.writeByte( ( v >> 16 ) & 0xFF );
        dos.writeByte( ( v >> 24 ) & 0xFF );
    }

    // ====================================================================
    // ICNS encoder (PNG-embedded variants — supported since macOS 10.7)
    // ====================================================================

    private record IcnsEntry( String osType, int pixelSize ) {}

    /** Writes an ICNS file containing PNG-embedded images for each requested
     *  size. Each per-element block is a 4-byte OSType + uint32 big-endian
     *  block length (including the 8-byte header) + PNG bytes. The top-level
     *  file header is {@code "icns"} + total file length. */
    private static void writeIcns( BufferedImage referenceLogo, IcnsEntry[] entries, Path out ) throws IOException
    {
        BufferedImage launcherLogo = ImageIO.read( Paths.get( "src/main/resources/micaminecraftlauncher.png" ).toFile() );

        // Render + PNG-encode each entry.
        byte[][] pngs = new byte[ entries.length ][];
        for ( int i = 0; i < entries.length; i++ ) {
            BufferedImage rendered = composeIcon( launcherLogo, entries[ i ].pixelSize );
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write( rendered, "PNG", bos );
            pngs[ i ] = bos.toByteArray();
        }

        // Total length = 8-byte top header + (8-byte element header + PNG payload) per entry
        int totalLength = 8;
        for ( byte[] png : pngs ) {
            totalLength += 8 + png.length;
        }

        try ( OutputStream fos = Files.newOutputStream( out );
              DataOutputStream dos = new DataOutputStream( fos ) ) {
            dos.writeBytes( "icns" );
            dos.writeInt( totalLength );  // DataOutputStream.writeInt is big-endian — exactly what ICNS wants

            for ( int i = 0; i < entries.length; i++ ) {
                byte[] osType = entries[ i ].osType.getBytes( java.nio.charset.StandardCharsets.US_ASCII );
                if ( osType.length != 4 ) {
                    throw new IOException( "OSType must be 4 bytes: " + entries[ i ].osType );
                }
                dos.write( osType );
                dos.writeInt( 8 + pngs[ i ].length );
                dos.write( pngs[ i ] );
            }
        }
    }
}
