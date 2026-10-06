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

import io.github.palexdev.materialfx.controls.MFXComboBox;
import io.github.palexdev.materialfx.controls.MFXToggleButton;
import javafx.scene.Scene;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;
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

/**
 * Renders the shared controls (the Material switch off and on, and a dropdown with its menu open) under
 * every theme, writing one PNG per theme to {@code build/target/snapshots/} for visual review. Opt-in,
 * like the other TestFX tests.
 *
 * @since 2026.10
 */
@ExtendWith( ApplicationExtension.class )
@EnabledIfEnvironmentVariable( named = "MMCL_RUN_TESTFX", matches = "true" )
class ControlsSnapshotFxTest
{
    private static final String[] THEMES = { "dark", "light", "bluegray", "orangepurple", "creeper", "native",
                                             "native-light" };

    private Stage stage;

    @Start
    private void start( Stage stage )
    {
        BundledFonts.ensureLoaded();
        this.stage = stage;
    }

    @Test
    void rendersSwitchesAndAnOpenDropdown( FxRobot robot ) throws Exception
    {
        for ( String theme : THEMES ) {
            MFXComboBox< String > combo = new MFXComboBox<>();
            robot.interact( () -> {
                MFXToggleButton off = new MFXToggleButton( "Switch off" );
                MFXToggleButton on = new MFXToggleButton( "Switch on" );
                on.setSelected( true );
                MFXToggleButton disabled = new MFXToggleButton( "Switch off, disabled" );
                disabled.setDisable( true );
                combo.getItems().setAll( "All modpacks", "Installed modpacks", "Not installed" );
                combo.selectItem( "Installed modpacks" );
                // Sized as the Settings screen sizes its dropdowns, and as the main screen sizes its filters.
                combo.setPrefWidth( 250 );
                combo.setMinHeight( 36 );
                combo.setPrefHeight( 36 );
                combo.setMaxHeight( 36 );
                MFXComboBox< String > filter = new MFXComboBox<>();
                filter.getItems().setAll( "Newest first", "Name" );
                filter.selectItem( "Newest first" );
                filter.setPrefWidth( 170 );
                filter.setMinHeight( 32 );
                filter.setPrefHeight( 32 );
                filter.setMaxHeight( 32 );
                filter.setMaxWidth( 170 );
                VBox root = new VBox( 12, off, on, disabled, combo, filter );
                root.setStyle( "-fx-padding: 24;" );
                root.getStyleClass().addAll( "rootPane", "settingsRow" );
                for ( String sheet : new String[]{ "ui/ui-base.css", "ui/ui-tokens-" + theme + ".css" } ) {
                    root.getStylesheets().add( getClass().getClassLoader().getResource( sheet ).toExternalForm() );
                }
                stage.setScene( new Scene( root, 360, 420 ) );
                stage.show();
                combo.show();
            } );
            WaitForAsyncUtils.sleep( 500, TimeUnit.MILLISECONDS );
            WaitForAsyncUtils.waitForFxEvents();

            AtomicReference< WritableImage > main = new AtomicReference<>();
            AtomicReference< WritableImage > popup = new AtomicReference<>();
            robot.interact( () -> {
                main.set( stage.getScene().snapshot( null ) );
                // The open combo's menu: the showing popup whose content is MaterialFX's .combo-popup.
                for ( Window window : Window.getWindows() ) {
                    if ( window != stage && window.isShowing() && window.getScene() != null
                         && window.getScene().getRoot().lookup( ".combo-popup" ) != null ) {
                        popup.set( window.getScene().snapshot( null ) );
                    }
                }
            } );
            write( main.get(), "controls-" + theme + ".png" );
            if ( popup.get() != null ) {
                write( popup.get(), "controls-" + theme + "-dropdown.png" );
            }
            robot.interact( combo::hide );
        }
    }

    private static void write( WritableImage image, String name ) throws Exception
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
        File file = new File( "build/target/snapshots/" + name );
        file.getParentFile().mkdirs();
        ImageIO.write( out, "png", file );
    }
}
