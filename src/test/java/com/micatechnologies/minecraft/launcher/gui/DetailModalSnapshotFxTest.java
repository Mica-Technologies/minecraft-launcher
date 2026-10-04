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
import com.micatechnologies.minecraft.launcher.game.modpack.GameModPack;
import com.micatechnologies.minecraft.launcher.utilities.JSONUtilities;
import javafx.scene.Node;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Renders the modpack detail window for a sample pack, one image per tab, in a few themes:
 * {@code build/target/snapshots/detail/<theme>-<tab>.png}. Visual review only, like
 * {@link ThemeSnapshotFxTest}.
 *
 * @since 2026.10
 */
@ExtendWith( ApplicationExtension.class )
@EnabledIfEnvironmentVariable( named = "MMCL_RUN_TESTFX", matches = "true" )
class DetailModalSnapshotFxTest
{
    private static final String PACK_JSON = """
            {
              "packName": "Skyward Isles",
              "packVersion": "2.4.1",
              "packURL": "https://example.com/skyward",
              "packMinRAMGB": "6",
              "packModLoader": "fabric",
              "packNews": [
                { "id": "n1", "title": "Version 2.4 is out", "body": "New islands, a reworked quest book and faster world loading.", "date": "2026-09-28" },
                { "id": "n2", "title": "Server maintenance", "body": "The community server restarts on Saturday.", "date": "2026-09-20" }
              ],
              "packLinks": [
                { "title": "Website", "url": "https://example.com" },
                { "title": "Discord", "url": "https://example.com/discord" }
              ]
            }
            """;

    private Stage stage;

    @Start
    private void start( Stage stage )
    {
        BundledFonts.ensureLoaded();
        this.stage = stage;
    }

    @Test
    void rendersEachTab( FxRobot robot ) throws Exception
    {
        GameModPack pack = JSONUtilities.getGson().fromJson( PACK_JSON, GameModPack.class );
        // Many problems, as a heavily-modded pack that rewrites its configs each launch can have.
        java.util.List< com.micatechnologies.minecraft.launcher.game.modpack.ModPackAuditLog.Problem > problems =
                new ArrayList<>();
        for ( int i = 0; i < 14; i++ ) {
            problems.add( new com.micatechnologies.minecraft.launcher.game.modpack.ModPackAuditLog.Problem(
                    "config/mod-" + i + "/settings.toml", 3 + i % 4, i % 3 == 0 ) );
        }
        MCLauncherModpackDetailModal.problemSource = ( root, launches ) -> problems;
        for ( String[] theme : new String[][]{ { "dark", ConfigConstants.THEME_DARK },
                                                { "light", ConfigConstants.THEME_LIGHT },
                                                { "orangepurple", ConfigConstants.THEME_ORANGE_PURPLE } } ) {
            AtomicReference< MCLauncherModpackDetailModal > modal = new AtomicReference<>();
            AtomicReference< StackPane > root = new AtomicReference<>();
            robot.interact( () -> {
                StackPane r = new StackPane();
                r.getStyleClass().add( "rootPane" );
                for ( String sheet : MCLauncherGuiWindow.themeStylesheetPaths( theme[ 1 ], !"light".equals( theme[ 0 ] ) ) ) {
                    r.getStylesheets().add( getClass().getClassLoader().getResource( sheet ).toExternalForm() );
                }
                MCLauncherModpackDetailModal m = new MCLauncherModpackDetailModal( r );
                r.getChildren().add( m );
                modal.set( m );
                root.set( r );
                stage.setScene( new Scene( r, 1100, 820 ) );
                stage.sizeToScene();
                stage.show();
                m.show( pack );
            } );
            WaitForAsyncUtils.sleep( 1500, TimeUnit.MILLISECONDS );
            WaitForAsyncUtils.waitForFxEvents();
            List< Node > tabs = new ArrayList<>( root.get().lookupAll( ".modpackDetailTab" ) );
            for ( int i = 0; i < Math.max( 1, tabs.size() ); i++ ) {
                if ( !tabs.isEmpty() ) {
                    Node tab = tabs.get( i );
                    robot.interact( () -> {
                        if ( tab instanceof javafx.scene.control.ButtonBase b ) {
                            b.fire();
                        }
                        else {
                            tab.fireEvent( new javafx.scene.input.MouseEvent( javafx.scene.input.MouseEvent.MOUSE_CLICKED,
                                    0, 0, 0, 0, javafx.scene.input.MouseButton.PRIMARY, 1, false, false, false, false,
                                    true, false, false, true, false, false, null ) );
                        }
                    } );
                    WaitForAsyncUtils.sleep( 900, TimeUnit.MILLISECONDS );
                    WaitForAsyncUtils.waitForFxEvents();
                }
                AtomicReference< WritableImage > shot = new AtomicReference<>();
                robot.interact( () -> shot.set( stage.getScene().snapshot( null ) ) );
                File out = new File( "build/target/snapshots/detail/" + theme[ 0 ] + "-" + i + ".png" );
                out.getParentFile().mkdirs();
                ImageIO.write( toBuffered( shot.get() ), "png", out );
            }
            // Colour from pack art: an orange seed, as a pack with an orange logo would give.
            robot.interact( () -> {
                Node card = root.get().lookup( ".modpackDetailCard" );
                boolean dark = !"light".equals( theme[ 0 ] );
                PackColorScheme.apply( card, PackColorScheme.roles( 0xE87A1E, dark, dark ? 0x222732 : 0xEDEEF2 ) );
                if ( !tabs.isEmpty() && tabs.get( 0 ) instanceof javafx.scene.control.Label first ) {
                    first.fireEvent( new javafx.scene.input.MouseEvent( javafx.scene.input.MouseEvent.MOUSE_CLICKED,
                            0, 0, 0, 0, javafx.scene.input.MouseButton.PRIMARY, 1, false, false, false, false,
                            true, false, false, true, false, false, null ) );
                }
            } );
            WaitForAsyncUtils.sleep( 600, TimeUnit.MILLISECONDS );
            WaitForAsyncUtils.waitForFxEvents();
            AtomicReference< WritableImage > tinted = new AtomicReference<>();
            robot.interact( () -> tinted.set( stage.getScene().snapshot( null ) ) );
            ImageIO.write( toBuffered( tinted.get() ), "png", new File( "build/target/snapshots/detail/" + theme[ 0 ] + "-packcolors.png" ) );
            robot.interact( () -> modal.get().dispose() );
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
