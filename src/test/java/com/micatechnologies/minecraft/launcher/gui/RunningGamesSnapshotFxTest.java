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
import com.micatechnologies.minecraft.launcher.game.modpack.LaunchProgressTracker;
import com.micatechnologies.minecraft.launcher.game.session.FakeProcess;
import com.micatechnologies.minecraft.launcher.game.session.GameLog;
import com.micatechnologies.minecraft.launcher.game.session.GameSession;
import javafx.scene.Scene;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
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
import java.util.concurrent.atomic.AtomicReference;

/**
 * Renders the Running Games window's tabs for three fake games (preparing, running with a live
 * log, crashed) in the dark theme and writes PNGs to {@code build/target/snapshots/} for
 * review. No real game, network or launcher config is involved: the config document is
 * injected with every key the panes read, so nothing is written to disk.
 *
 * <p>Opt-in like the other TestFX tests ({@code MMCL_RUN_TESTFX=true}).</p>
 */
@ExtendWith( ApplicationExtension.class )
@EnabledIfEnvironmentVariable( named = "MMCL_RUN_TESTFX", matches = "true" )
class RunningGamesSnapshotFxTest
{
    private static JsonObject originalConfig;
    private Stage stage;

    @BeforeAll
    static void injectConfig()
    {
        originalConfig = ConfigStore.peek();
        JsonObject doc = new JsonObject();
        doc.addProperty( ConfigConstants.CONSOLE_LOG_MAX_LINES_KEY, 5000 );
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
        this.stage = stage;
    }

    @Test
    void rendersEachPhase( FxRobot robot ) throws Exception
    {
        GameSession preparing = new GameSession( null, "p1", "All the Mods 9", null, "Alex", System::currentTimeMillis );
        LaunchProgressTracker tracker = LaunchProgressTracker.forSteps(
                LaunchProgressTracker.StepId.MODPACK_CONTENT, LaunchProgressTracker.StepId.FORGE_LIBS,
                LaunchProgressTracker.StepId.MC_LIBS_ASSETS, LaunchProgressTracker.StepId.JRE_INSTALL,
                LaunchProgressTracker.StepId.SECURITY_SCAN );
        tracker.markDone( LaunchProgressTracker.StepId.MODPACK_CONTENT );
        tracker.markDone( LaunchProgressTracker.StepId.FORGE_LIBS );
        tracker.markRunning( LaunchProgressTracker.StepId.MC_LIBS_ASSETS );
        tracker.submitProgress( LaunchProgressTracker.StepId.MC_LIBS_ASSETS, 0.42,
                                "assets/objects/3f/3f2a… — 42% · 12.3 / 29.1 MB · 4.1 MB/s" );
        preparing.setTracker( tracker );

        GameSession running = new GameSession( null, "p2", "Vanilla 1.21.4", null, "Blake", System::currentTimeMillis );
        GameLog log = new GameLog( null );
        StringBuilder sample = new StringBuilder();
        for ( int i = 0; i < 40; i++ ) {
            sample.append( "[12:00:" ).append( String.format( "%02d", i ) )
                  .append( "] [Render thread/INFO]: Loaded chunk batch " ).append( i ).append( '\n' );
        }
        log.attach( new ByteArrayInputStream( sample.toString().getBytes( StandardCharsets.UTF_8 ) ),
                    new ByteArrayInputStream( new byte[ 0 ] ) );
        running.setLog( log );
        running.attachProcess( new FakeProcess() );

        GameSession crashed = new GameSession( null, "p3", "Create: Above and Beyond", null, "Casey",
                                               System::currentTimeMillis );
        FakeProcess crashedProcess = new FakeProcess();
        crashed.attachProcess( crashedProcess );
        crashed.setCrashReport( "---- Minecraft Crash Report ----\nDescription: Mod loading error\n"
                                + "java.lang.OutOfMemoryError: Java heap space\n" );
        crashedProcess.finish( 1 );
        log.awaitClosed( 2_000 );

        TabPane tabs = new TabPane();
        robot.interact( () -> {
            for ( GameSession s : new GameSession[]{ preparing, running, crashed } ) {
                Tab tab = new Tab();
                tab.setContent( new GameSessionPane( s ).root() );
                RunningGamesWindow.refreshTab( tab, s );
                tabs.getTabs().add( tab );
            }
            StackPane root = new StackPane( tabs );
            root.getStyleClass().add( "rootPane" );
            for ( String sheet : new String[]{ "guiStyle-dark.css", "ui/ui-base.css", "ui/ui-tokens-dark.css" } ) {
                root.getStylesheets().add( getClass().getClassLoader().getResource( sheet ).toExternalForm() );
            }
            stage.setScene( new Scene( root, 1000, 680 ) );
            stage.show();
        } );

        for ( int i = 0; i < 3; i++ ) {
            final int index = i;
            robot.interact( () -> tabs.getSelectionModel().select( index ) );
            WaitForAsyncUtils.sleep( 600, java.util.concurrent.TimeUnit.MILLISECONDS );
            WaitForAsyncUtils.waitForFxEvents();
            AtomicReference< WritableImage > shot = new AtomicReference<>();
            robot.interact( () -> shot.set( stage.getScene().snapshot( null ) ) );
            File out = new File( "build/target/snapshots/running-games-" + index + ".png" );
            out.getParentFile().mkdirs();
            ImageIO.write( toBuffered( shot.get() ), "png", out );
        }
    }

    private static BufferedImage toBuffered( WritableImage image )
    {
        int w = (int) image.getWidth();
        int h = (int) image.getHeight();
        BufferedImage out = new BufferedImage( w, h, BufferedImage.TYPE_INT_ARGB );
        PixelReader reader = image.getPixelReader();
        for ( int y = 0; y < h; y++ ) {
            for ( int x = 0; x < w; x++ ) {
                out.setRGB( x, y, reader.getArgb( x, y ) );
            }
        }
        return out;
    }
}
