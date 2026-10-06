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

import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.launcher.config.ConfigStore;
import com.micatechnologies.minecraft.launcher.consts.ConfigConstants;
import com.micatechnologies.minecraft.launcher.game.session.FakeProcess;
import com.micatechnologies.minecraft.launcher.game.session.GameLog;
import com.micatechnologies.minecraft.launcher.game.session.GameSession;
import com.micatechnologies.minecraft.launcher.game.session.GameSessionRegistry;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.GridPane;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Renders the Running Games view docked along the bottom of a stand-in main-window screen,
 * expanded and collapsed, then popped out into its own window and docked back, writing PNGs to
 * {@code build/target/snapshots/} for visual review. Opt-in, like the other TestFX tests.
 *
 * @since 2026.10
 */
@ExtendWith( ApplicationExtension.class )
@EnabledIfEnvironmentVariable( named = "MMCL_RUN_TESTFX", matches = "true" )
class RunningGamesDockSnapshotFxTest
{
    private static JsonObject originalConfig;
    private Stage stage;

    @BeforeAll
    static void injectConfig()
    {
        originalConfig = ConfigStore.peek();
        JsonObject doc = new JsonObject();
        doc.addProperty( ConfigConstants.CONSOLE_LOG_MAX_LINES_KEY, 5000 );
        doc.addProperty( ConfigConstants.RUNNING_GAMES_DOCKED_KEY, true );
        ConfigStore.setJson( doc );
    }

    @AfterAll
    static void restoreConfig()
    {
        ConfigStore.setJson( originalConfig );
    }

    @Start
    private void start( Stage stage )
    {
        BundledFonts.ensureLoaded();
        this.stage = stage;
    }

    @Test
    void docksCollapsesPopsOutAndDocksBack( FxRobot robot ) throws Exception
    {
        GameSession first = running( "p1", "All the Mods 9", "Alex" );
        GameSession second = running( "p2", "Vanilla 1.21.4", "Blake" );

        GridPane screen = new GridPane();
        robot.interact( () -> {
            Label library = new Label( "Library: the screen above the dock" );
            library.getStyleClass().add( "muted" );
            screen.add( library, 0, 0 );
            screen.setMinHeight( 475 );  // as the launcher's screens declare
            screen.setStyle( "-fx-padding: 24;" );
            screen.getStyleClass().add( "rootPane" );
            MCLauncherGuiWindow.installCurrentThemeStylesheets( screen );
            Scene scene = new Scene( UiScale.wrap( screen ), 1000, 720 );
            stage.setScene( scene );
            stage.show();
            RunningGamesWindow.followScreen( scene );
        } );
        RunningGamesWindow.showSession( second );
        settle();
        ScaledRoot root = (ScaledRoot) stage.getScene().getRoot();
        assertNotNull( root.dock(), "the view docks into the screen's wrapper" );
        write( robot, stage.getScene(), "running-games-docked.png" );

        RunningGamesWindow.hideUnlessPreparing();
        settle();
        assertTrue( root.dockHeight( 720 ) < 80, "collapsed, the dock is only its header" );
        write( robot, stage.getScene(), "running-games-docked-collapsed.png" );

        fire( robot, root, "session.dock.popOut" );
        settle();
        assertNull( root.dock(), "popped out, the dock leaves the screen" );
        Window popped = otherShowingWindow();
        assertNotNull( popped, "popped out, the view has its own window" );
        assertFalse( ( (Stage) popped ).getIcons().isEmpty(), "the window carries the launcher's icon" );
        write( robot, popped.getScene(), "running-games-window.png" );

        fire( robot, popped.getScene().getRoot(), "session.dock.dockIn" );
        settle();
        assertSame( root, ( (ScaledRoot) stage.getScene().getRoot() ) );
        assertNotNull( root.dock(), "docked again" );
        assertFalse( popped.isShowing(), "docking hides the view's own window" );

        robot.interact( () -> {
            RunningGamesWindow.shutdown();
        } );
        for ( GameSession s : new GameSession[]{ first, second } ) {
            ( (FakeProcess) s.process() ).finish( 0 );
        }
        settle();
        for ( GameSession s : new GameSession[]{ first, second } ) {
            GameSessionRegistry.get().dismiss( s.id() );
        }
    }

    private static GameSession running( String key, String name, String player )
    {
        GameSession session = new GameSession( null, key, name, null, player, System::currentTimeMillis );
        GameLog log = new GameLog( null );
        log.attach( new ByteArrayInputStream( ( "[12:00:00] [Render thread/INFO]: Started " + name + "\n" )
                                                      .getBytes( StandardCharsets.UTF_8 ) ),
                    new ByteArrayInputStream( new byte[ 0 ] ) );
        session.setLog( log );
        session.attachProcess( new FakeProcess() );
        assertTrue( GameSessionRegistry.get().tryRegister( session ).ok() );
        return session;
    }

    /** Fires the header button whose accessible text is the given string's value. */
    private static void fire( FxRobot robot, Node within, String key )
    {
        String text = com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager.get( key );
        robot.interact( () -> {
            for ( Node node : within.lookupAll( ".mfx-button" ) ) {
                boolean named = text.equals( node.getAccessibleText() )
                                || node instanceof Labeled labeled && text.equals( labeled.getText() );
                if ( named && node.isVisible() ) {
                    ( (javafx.scene.control.ButtonBase) node ).fire();
                    return;
                }
            }
            throw new AssertionError( "No visible button: " + text );
        } );
    }

    private Window otherShowingWindow()
    {
        for ( Window window : Window.getWindows() ) {
            if ( window != stage && window.isShowing() && window instanceof Stage ) {
                return window;
            }
        }
        return null;
    }

    private static void settle()
    {
        WaitForAsyncUtils.waitForFxEvents();
        WaitForAsyncUtils.sleep( 400, TimeUnit.MILLISECONDS );
        WaitForAsyncUtils.waitForFxEvents();
    }

    private static void write( FxRobot robot, Scene scene, String name ) throws Exception
    {
        AtomicReference< WritableImage > shot = new AtomicReference<>();
        robot.interact( () -> shot.set( scene.snapshot( null ) ) );
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
        File file = new File( "build/target/snapshots/" + name );
        file.getParentFile().mkdirs();
        ImageIO.write( out, "png", file );
    }
}
