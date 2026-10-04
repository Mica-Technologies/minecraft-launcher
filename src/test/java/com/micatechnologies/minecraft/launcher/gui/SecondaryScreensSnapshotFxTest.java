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

import com.micatechnologies.minecraft.launcher.consts.ConfigConstants;
import javafx.collections.FXCollections;
import javafx.scene.Scene;
import javafx.scene.control.ListView;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Snapshots of the secondary screens' data-driven parts, which the FXML-only theme snapshots
 * can't show: the Runtime Management tile list (with a selection) next to its empty state.
 * Written to {@code build/target/snapshots/secondary/<theme>-runtimes.png}. Opt-in, like the
 * other TestFX tests.
 *
 * @since 2026.10
 */
@ExtendWith( ApplicationExtension.class )
@EnabledIfEnvironmentVariable( named = "MMCL_RUN_TESTFX", matches = "true" )
class SecondaryScreensSnapshotFxTest
{
    private Stage stage;

    @Start
    private void start( Stage stage )
    {
        BundledFonts.ensureLoaded();
        this.stage = stage;
    }

    @Test
    void rendersRuntimeTiles( FxRobot robot ) throws Exception
    {
        List< Map< String, String > > runtimes = List.of(
                Map.of( "component", "java-runtime-delta", "version", "21.0.3", "sizeMB", "196" ),
                Map.of( "component", "java-runtime-gamma", "version", "17.0.8", "sizeMB", "182" ),
                Map.of( "component", "jre-legacy", "version", "", "sizeMB", "118" ) );
        for ( String[] theme : new String[][]{ { "dark", ConfigConstants.THEME_DARK },
                                                { "light", ConfigConstants.THEME_LIGHT } } ) {
            robot.interact( () -> {
                ListView< Map< String, String > > list = new ListView<>( FXCollections.observableArrayList( runtimes ) );
                list.getStyleClass().add( "tileList" );
                list.setCellFactory( l -> new MCLauncherRuntimeGui.RuntimeCell() );
                list.getSelectionModel().select( 1 );
                ListView< Map< String, String > > empty = new ListView<>();
                empty.getStyleClass().add( "tileList" );
                empty.setPlaceholder( MCLauncherRuntimeGui.emptyPlaceholder() );
                HBox.setHgrow( list, Priority.ALWAYS );
                HBox.setHgrow( empty, Priority.ALWAYS );
                HBox row = new HBox( 24, list, empty );
                VBox.setVgrow( row, Priority.ALWAYS );
                StackPane r = new StackPane( new VBox( row ) );
                r.getStyleClass().add( "rootPane" );
                r.setPadding( new javafx.geometry.Insets( 24 ) );
                for ( String sheet : MCLauncherGuiWindow.themeStylesheetPaths( theme[ 1 ], "dark".equals( theme[ 0 ] ) ) ) {
                    r.getStylesheets().add( getClass().getClassLoader().getResource( sheet ).toExternalForm() );
                }
                stage.setScene( new Scene( r, 1000, 360 ) );
                stage.sizeToScene();
                stage.show();
            } );
            WaitForAsyncUtils.sleep( 500, TimeUnit.MILLISECONDS );
            WaitForAsyncUtils.waitForFxEvents();
            AtomicReference< WritableImage > shot = new AtomicReference<>();
            robot.interact( () -> shot.set( stage.getScene().snapshot( null ) ) );
            File out = new File( "build/target/snapshots/secondary/" + theme[ 0 ] + "-runtimes.png" );
            out.getParentFile().mkdirs();
            ImageIO.write( toBuffered( shot.get() ), "png", out );
        }
    }

    private static BufferedImage toBuffered( WritableImage img )
    {
        int w = (int) img.getWidth();
        int h = (int) img.getHeight();
        BufferedImage out = new BufferedImage( w, h, BufferedImage.TYPE_INT_ARGB );
        PixelReader reader = img.getPixelReader();
        for ( int y = 0; y < h; y++ ) {
            for ( int x = 0; x < w; x++ ) {
                out.setRGB( x, y, reader.getArgb( x, y ) );
            }
        }
        return out;
    }
}
