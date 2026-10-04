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
import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Region;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The interface scale on real screens: each is laid out at the window size divided by the scale,
 * and drawn to fill the window. Writes {@code build/target/snapshots/scale/<screen>-<percent>.png}.
 *
 * @since 2026.10
 */
@ExtendWith( ApplicationExtension.class )
@EnabledIfEnvironmentVariable( named = "MMCL_RUN_TESTFX", matches = "true" )
class UiScaleSnapshotFxTest
{
    private Stage stage;

    @Start
    private void start( Stage stage )
    {
        BundledFonts.ensureLoaded();
        this.stage = stage;
    }

    @AfterEach
    void reset( FxRobot robot )
    {
        robot.interact( () -> UiScale.setPercent( 100 ) );
    }

    @Test
    void rendersScreensAtEachScale( FxRobot robot ) throws Exception
    {
        for ( String screen : new String[]{ "mainGUI", "settingsGUI" } ) {
            for ( int percent : UiScale.PERCENTS ) {
                double s = percent / 100.0;
                AtomicReference< Parent > content = new AtomicReference<>();
                robot.interact( () -> {
                    try {
                        UiScale.setPercent( percent );
                        FXMLLoader loader = new FXMLLoader( getClass().getResource( "/gui/" + screen + ".fxml" ),
                                                            LocalizationManager.currentBundle() );
                        Parent root = loader.load();
                        for ( String sheet : MCLauncherGuiWindow.themeStylesheetPaths( ConfigConstants.THEME_DARK, true ) ) {
                            root.getStylesheets().add( getClass().getClassLoader().getResource( sheet ).toExternalForm() );
                        }
                        content.set( root );
                        Scene scene = new Scene( UiScale.wrap( root ), 1000 * s, 800 * s );
                        UiScale.install( scene );
                        stage.setScene( scene );
                        stage.sizeToScene();
                        stage.show();
                    }
                    catch ( Exception e ) {
                        throw new IllegalStateException( screen, e );
                    }
                } );
                WaitForAsyncUtils.sleep( 300, TimeUnit.MILLISECONDS );
                WaitForAsyncUtils.waitForFxEvents();
                AtomicReference< WritableImage > shot = new AtomicReference<>();
                robot.interact( () -> shot.set( stage.getScene().snapshot( null ) ) );
                // The content is laid out at the designed 1000 x 800 whatever the scale.
                Region r = (Region) content.get();
                assertEquals( 1000, r.getWidth(), 1.0, screen + " @" + percent + " layout width" );
                assertEquals( 800, r.getHeight(), 1.0, screen + " @" + percent + " layout height" );
                File out = new File( "build/target/snapshots/scale/" + screen + "-" + percent + ".png" );
                out.getParentFile().mkdirs();
                ImageIO.write( toBuffered( shot.get() ), "png", out );
            }
        }
    }

    @Test
    void popupsFollowTheScale( FxRobot robot ) throws Exception
    {
        double[] menuHeights = new double[ 2 ];
        double[] tipHeights = new double[ 2 ];
        int[] percents = { 100, 150 };
        for ( int i = 0; i < percents.length; i++ ) {
            int percent = percents[ i ];
            javafx.scene.control.Label anchor = new javafx.scene.control.Label( "Popup anchor" );
            robot.interact( () -> {
                UiScale.setPercent( percent );
                javafx.scene.layout.StackPane root = new javafx.scene.layout.StackPane( anchor );
                for ( String sheet : MCLauncherGuiWindow.themeStylesheetPaths( ConfigConstants.THEME_DARK, true ) ) {
                    root.getStylesheets().add( getClass().getClassLoader().getResource( sheet ).toExternalForm() );
                }
                Scene scene = new Scene( UiScale.wrap( root ), 400, 300 );
                UiScale.install( scene );
                stage.setScene( scene );
                stage.show();
            } );
            WaitForAsyncUtils.waitForFxEvents();
            javafx.scene.control.ContextMenu menu = new javafx.scene.control.ContextMenu(
                    new javafx.scene.control.MenuItem( "Open folder" ), new javafx.scene.control.MenuItem( "Delete" ) );
            javafx.scene.control.Tooltip tip = new javafx.scene.control.Tooltip( "A launcher tooltip" );
            javafx.stage.PopupWindow[] popups = { menu, tip };
            for ( javafx.stage.PopupWindow popup : popups ) {
                robot.interact( () -> popup.show( anchor, stage.getX() + 40, stage.getY() + 40 ) );
                WaitForAsyncUtils.sleep( 300, TimeUnit.MILLISECONDS );
                WaitForAsyncUtils.waitForFxEvents();
                final int idx = i;
                robot.interact( () -> {
                    ( popup == menu ? menuHeights : tipHeights )[ idx ] = popup.getHeight();
                    popup.hide();
                } );
            }
        }
        // The popups' text and padding scale; their window chrome (shadow insets) doesn't, so
        // allow a margin below a full 1.5x.
        org.junit.jupiter.api.Assertions.assertTrue( menuHeights[ 1 ] > menuHeights[ 0 ] * 1.3,
                "menu " + menuHeights[ 0 ] + " -> " + menuHeights[ 1 ] );
        org.junit.jupiter.api.Assertions.assertTrue( tipHeights[ 1 ] > tipHeights[ 0 ] * 1.3,
                "tooltip " + tipHeights[ 0 ] + " -> " + tipHeights[ 1 ] );
    }

    @Test
    void dialogsFollowTheScale( FxRobot robot ) throws Exception
    {
        double[] heights = new double[ 2 ];
        int[] percents = { 100, 150 };
        for ( int i = 0; i < percents.length; i++ ) {
            int percent = percents[ i ];
            AtomicReference< javafx.scene.control.Dialog< javafx.scene.control.ButtonType > > ref = new AtomicReference<>();
            robot.interact( () -> {
                UiScale.setPercent( percent );
                stage.setScene( new Scene( UiScale.wrap( new javafx.scene.layout.StackPane() ), 400, 300 ) );
                stage.show();
                javafx.scene.control.Dialog< javafx.scene.control.ButtonType > dlg = new javafx.scene.control.Dialog<>();
                dlg.getDialogPane().setHeaderText( "A dialog" );
                dlg.getDialogPane().setContentText( "Dialog content text." );
                dlg.getDialogPane().getButtonTypes().addAll( javafx.scene.control.ButtonType.OK,
                                                             javafx.scene.control.ButtonType.CANCEL );
                GUIUtilities.themeAlertChrome( dlg );
                dlg.initOwner( stage );
                dlg.show();
                ref.set( dlg );
            } );
            WaitForAsyncUtils.sleep( 400, TimeUnit.MILLISECONDS );
            WaitForAsyncUtils.waitForFxEvents();
            final int idx = i;
            AtomicReference< WritableImage > shot = new AtomicReference<>();
            robot.interact( () -> {
                javafx.scene.Scene scene = ref.get().getDialogPane().getScene();
                heights[ idx ] = scene.getHeight();
                shot.set( scene.snapshot( null ) );
                scene.getWindow().hide();
            } );
            File out = new File( "build/target/snapshots/scale/dialog-" + percent + ".png" );
            out.getParentFile().mkdirs();
            ImageIO.write( toBuffered( shot.get() ), "png", out );
        }
        org.junit.jupiter.api.Assertions.assertTrue( heights[ 1 ] > heights[ 0 ] * 1.3,
                "dialog " + heights[ 0 ] + " -> " + heights[ 1 ] );
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
