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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Renders the shared sign-in card ({@link SignInPanel}) in the dark and light themes and writes
 * PNGs to {@code build/target/snapshots/} for visual review. It also asserts the card lays out
 * at the login screen's minimum size without collapsing.
 *
 * <p>Opt-in like the other TestFX tests ({@code MMCL_RUN_TESTFX=true}). It loads Microsoft's
 * real sign-in page, so it needs network access; offline, the snapshot shows the retry state
 * instead, which is worth reviewing too. The theme sheets are installed directly rather than
 * through {@code MCLauncherGuiWindow}, which would read the user's real launcher config.</p>
 */
@ExtendWith( ApplicationExtension.class )
@EnabledIfEnvironmentVariable( named = "MMCL_RUN_TESTFX", matches = "true" )
class SignInPanelSnapshotFxTest
{
    private Stage stage;

    @Start
    private void start( Stage stage )
    {
        this.stage = stage;
    }

    @Test
    void rendersInDarkAndLightThemes( FxRobot robot ) throws Exception
    {
        render( robot, "dark", "guiStyle-dark.css", "ui/ui-tokens-dark.css" );
        render( robot, "light", "guiStyle-light.css", "ui/ui-tokens-light.css" );
    }

    private void render( FxRobot robot, String name, String legacySheet, String tokenSheet ) throws Exception
    {
        AtomicReference< SignInPanel > panel = new AtomicReference<>();
        robot.interact( () -> {
            SignInPanel signIn = new SignInPanel( "signIn.login.heading", "signIn.login.subtitle", "login.exitBtn" );
            panel.set( signIn );
            StackPane root = new StackPane( signIn.root() );
            root.getStyleClass().addAll( "rootPane", "hero-surface" );
            root.setStyle( "-fx-padding: 28 32 32 32;" );
            for ( String sheet : new String[]{ legacySheet, "ui/ui-base.css", tokenSheet } ) {
                root.getStylesheets().add( getClass().getClassLoader().getResource( sheet ).toExternalForm() );
            }
            stage.setScene( new Scene( root, 1120, 860 ) );
            stage.show();
            signIn.loadSignIn();
        } );
        // Give Microsoft's page time to load (or fail) so the overlay has settled.
        WaitForAsyncUtils.sleep( 8, java.util.concurrent.TimeUnit.SECONDS );
        WaitForAsyncUtils.waitForFxEvents();

        AtomicReference< WritableImage > shot = new AtomicReference<>();
        robot.interact( () -> shot.set( stage.getScene().snapshot( null ) ) );
        File out = new File( "build/target/snapshots/signin-" + name + ".png" );
        out.getParentFile().mkdirs();
        ImageIO.write( toBuffered( shot.get() ), "png", out );

        assertTrue( panel.get().root().getWidth() > 800, "card should use the window's width" );
        assertTrue( panel.get().webView().getWidth() >= 440, "Microsoft's page needs at least 440 px" );
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
