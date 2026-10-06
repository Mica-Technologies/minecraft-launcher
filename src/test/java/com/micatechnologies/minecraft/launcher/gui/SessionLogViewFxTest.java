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

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.IndexedCell;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.skin.VirtualFlow;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.input.Clipboard;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The virtualized game log with a big log: thousands of lines, some far wider than the view.
 * Checks that only the visible rows get nodes, long lines wrap instead of scrolling sideways,
 * a reader scrolled up stays on their line while lines arrive and old ones are trimmed, and the
 * search highlights its match; writes PNGs to {@code build/target/snapshots/} for review.
 *
 * <p>Opt-in like the other TestFX tests ({@code MMCL_RUN_TESTFX=true}).</p>
 */
@ExtendWith( ApplicationExtension.class )
@EnabledIfEnvironmentVariable( named = "MMCL_RUN_TESTFX", matches = "true" )
class SessionLogViewFxTest
{
    private Stage stage;

    @Start
    private void start( Stage stage )
    {
        BundledFonts.ensureLoaded();
        this.stage = stage;
    }

    @Test
    void bigLogIsVirtualizedWrapsAndKeepsTheReadersPlace( FxRobot robot ) throws Exception
    {
        SessionLogView view = new SessionLogView();
        String longLine = "[12:00:00] [main/ERROR]: java.lang.IllegalStateException: "
                          + "x".repeat( 40 ) + " at net.minecraft.world.level.chunk.LevelChunk.tick".repeat( 30 );
        StringBuilder text = new StringBuilder();
        for ( int i = 0; i < 6000; i++ ) {
            String line = i % 500 == 7 ? longLine : "[12:00:00] [Render thread/INFO]: Loaded chunk batch " + i;
            if ( i == 5509 ) {
                line = "";
            }
            else if ( i == 5511 ) {
                line = "y".repeat( 600 );
            }
            text.append( line ).append( '\n' );
        }
        robot.interact( () -> {
            view.setText( text.toString() );
            StackPane root = new StackPane( view.node() );
            root.getStyleClass().add( "rootPane" );
            for ( String sheet : new String[]{ "ui/ui-base.css", "ui/ui-tokens-dark.css" } ) {
                root.getStylesheets().add( getClass().getClassLoader().getResource( sheet ).toExternalForm() );
            }
            stage.setScene( new Scene( root, 900, 500 ) );
            stage.show();
            view.scrollToEnd();
        } );
        settle();
        assertEquals( 6000, view.lineCount() );
        shoot( robot, "session-log-bottom" );

        // Only the rows on screen exist as cells.
        VirtualFlow< ? > flow = (VirtualFlow< ? >) view.node().lookup( ".virtual-flow" );
        assertNotNull( flow );
        assertTrue( flow.getCellCount() == 6000 );
        int visible = flow.getLastVisibleCell().getIndex() - flow.getFirstVisibleCell().getIndex() + 1;
        assertTrue( visible < 60, "rows on screen: " + visible );
        assertEquals( 5999, flow.getLastVisibleCell().getIndex() );

        // A long line wraps: no sideways scrolling, and its row is several lines tall.
        robot.interact( () -> view.node().scrollTo( 5507 ) );
        settle();
        for ( Node n : view.node().lookupAll( ".scroll-bar" ) ) {
            if ( n instanceof ScrollBar bar && bar.getOrientation() == javafx.geometry.Orientation.HORIZONTAL ) {
                assertFalse( bar.isVisible(), "no horizontal scrollbar" );
            }
        }
        IndexedCell< ? > wrapped = flow.getCell( 5507 );
        IndexedCell< ? > plain = flow.getCell( 5508 );
        assertTrue( wrapped.getHeight() > plain.getHeight() * 5,
                    "long line wraps: " + wrapped.getHeight() + " vs " + plain.getHeight() );
        assertTrue( plain.getHeight() < 22, "one line is about one line tall: " + plain.getHeight() );
        assertEquals( plain.getHeight(), flow.getCell( 5509 ).getHeight(), 0.5, "a blank line keeps its height" );
        assertTrue( flow.getCell( 5511 ).getHeight() > plain.getHeight() * 2,
                    "a long word with no spaces wraps too: " + flow.getCell( 5511 ).getHeight() );
        shoot( robot, "session-log-wrapped" );

        // Scrolled up, the reader keeps their place while lines arrive, and while old lines are
        // trimmed from the front.
        robot.interact( () -> view.node().scrollTo( 3000 ) );
        settle();
        AtomicReference< String > firstLine = new AtomicReference<>();
        robot.interact( () -> firstLine.set( (String) flow.getFirstVisibleCell().getItem() ) );
        List< String > more = new ArrayList<>();
        for ( int i = 0; i < 1500; i++ ) {
            more.add( "[12:01:00] [Server thread/INFO]: new line " + i );
        }
        AtomicReference< Integer > dropped = new AtomicReference<>();
        robot.interact( () -> dropped.set( view.append( more, 5000, false ) ) );
        settle();
        assertEquals( 2500, dropped.get(), "trimmed back to the cap once past its slack" );
        assertEquals( 5000, view.lineCount() );
        robot.interact( () -> assertEquals( firstLine.get(), flow.getFirstVisibleCell().getItem(),
                                            "the reader's line stays at the top" ) );

        // Following, new lines bring the view to the end.
        robot.interact( () -> view.append( List.of( "last line" ), 5000, true ) );
        settle();
        robot.interact( () -> assertEquals( view.lineCount() - 1, flow.getLastVisibleCell().getIndex() ) );

        // Search: next and previous move between matches across lines and wrap.
        AtomicReference< int[] > hit = new AtomicReference<>();
        robot.interact( () -> hit.set( view.find( "illegalstate", true ) ) );
        settle();
        assertArrayEquals( new int[]{ 1, 7 }, hit.get() );
        robot.interact( () -> hit.set( view.find( "illegalstate", false ) ) );
        assertArrayEquals( new int[]{ 7, 7 }, hit.get(), "previous from the first wraps to the last" );
        settle();
        shoot( robot, "session-log-search-wrapped" );
        robot.interact( () -> hit.set( view.find( "batch 4321", true ) ) );
        settle();
        assertArrayEquals( new int[]{ 1, 1 }, hit.get() );
        shoot( robot, "session-log-search" );

        // Copy: all lines, and the selected rows in log order whatever order they were picked in.
        robot.interact( () -> {
            assertTrue( view.text().endsWith( "last line\n" ) );
            int match = view.lines().indexOf( "[12:00:00] [Render thread/INFO]: Loaded chunk batch 4321" );
            view.node().getSelectionModel().select( match + 1 );
            view.node().getSelectionModel().select( match );
            view.node().fireEvent( new javafx.scene.input.KeyEvent(
                    javafx.scene.input.KeyEvent.KEY_PRESSED, "", "", javafx.scene.input.KeyCode.C,
                    false, !isMac(), false, isMac() ) );
            assertEquals( "[12:00:00] [Render thread/INFO]: Loaded chunk batch 4321\n"
                          + "[12:00:00] [Render thread/INFO]: Loaded chunk batch 4322\n",
                          Clipboard.getSystemClipboard().getString() );
        } );
        settle();
        shoot( robot, "session-log-selected" );

        // The same view (a selected row, the search's match on the row above) in every theme.
        robot.interact( () -> {
            view.find( "batch 4321", true );
            view.node().getSelectionModel().clearAndSelect(
                    view.lines().indexOf( "[12:00:00] [Render thread/INFO]: Loaded chunk batch 4323" ) );
        } );
        for ( String theme : new String[]{ "dark", "light", "native", "native-light", "bluegray", "creeper",
                                           "orangepurple" } ) {
            robot.interact( () -> {
                var sheets = stage.getScene().getRoot().getStylesheets();
                sheets.set( 1, getClass().getClassLoader().getResource( "ui/ui-tokens-" + theme + ".css" )
                                         .toExternalForm() );
            } );
            settle();
            shoot( robot, "session-log-theme-" + theme );
        }
    }

    private static boolean isMac()
    {
        return System.getProperty( "os.name", "" ).toLowerCase().contains( "mac" );
    }

    private static void settle()
    {
        WaitForAsyncUtils.waitForFxEvents();
        WaitForAsyncUtils.sleep( 300, TimeUnit.MILLISECONDS );
        WaitForAsyncUtils.waitForFxEvents();
    }

    private void shoot( FxRobot robot, String name ) throws Exception
    {
        AtomicReference< WritableImage > shot = new AtomicReference<>();
        robot.interact( () -> shot.set( stage.getScene().snapshot( null ) ) );
        WritableImage image = shot.get();
        int w = (int) image.getWidth();
        int h = (int) image.getHeight();
        BufferedImage out = new BufferedImage( w, h, BufferedImage.TYPE_INT_ARGB );
        PixelReader reader = image.getPixelReader();
        for ( int y = 0; y < h; y++ ) {
            for ( int x = 0; x < w; x++ ) {
                out.setRGB( x, y, reader.getArgb( x, y ) );
            }
        }
        File file = new File( "build/target/snapshots/" + name + ".png" );
        file.getParentFile().mkdirs();
        ImageIO.write( out, "png", file );
    }
}
