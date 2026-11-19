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
import com.micatechnologies.minecraft.launcher.game.modpack.GameModPack;
import com.micatechnologies.minecraft.launcher.game.modpack.GameOptionalMod;
import com.micatechnologies.minecraft.launcher.game.modpack.ModPackAuditLog;
import com.micatechnologies.minecraft.launcher.game.modpack.PendingUpdateDiff;
import com.micatechnologies.minecraft.launcher.utilities.JSONUtilities;
import io.github.palexdev.materialfx.controls.MFXButton;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
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
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Renders the modpack detail window for a sample pack with optional mods, news and a pending
 * update, and checks the sticky tab bar: {@code build/target/snapshots/detail/<theme>-<shot>.png}.
 * Shots: the top of Overview, Overview scrolled so the tab bar is pinned, the Mods tab reached
 * through the Optional mods summary's Choose button, then Worlds &amp; files, Activity, Settings
 * and Problems, plus the pack-colour variant and the window at its minimum size. The assertions
 * cover the pinned bar sitting at the top of the scroll area, staying pinned across tab switches,
 * an unpinned position surviving a switch, and Choose landing pinned on Mods. Everything is
 * driven through {@code fire()} and the modal's test hooks, never the real mouse.
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
              "packVersion": "2.5.0",
              "packURL": "https://example.com/skyward",
              "packMinRAMGB": "6",
              "packModLoader": "fabric",
              "manifestUrl": "https://example.invalid/skyward-snapshot.json",
              "packNews": [
                { "id": "n1", "title": "Version 2.5 is out", "body": "New islands, a reworked quest book and faster world loading.", "date": "2026-09-28" },
                { "id": "n2", "title": "Server maintenance", "body": "The community server restarts on Saturday.", "date": "2026-09-20" }
              ],
              "packLinks": [
                { "title": "Website", "url": "https://example.com" },
                { "title": "Discord", "url": "https://example.com/discord" }
              ],
              "packOptionalMods": [
                { "name": "Iris Shaders", "remote": "https://example.invalid/iris.jar", "local": "iris.jar",
                  "clientReq": true, "description": "Shader pack support. Slower on older graphics cards.",
                  "defaultEnabled": true, "requires": ["sodium.jar"] },
                { "name": "Sodium", "remote": "https://example.invalid/sodium.jar", "local": "sodium.jar",
                  "clientReq": true, "description": "Faster rendering.", "defaultEnabled": true },
                { "name": "Distant Horizons", "remote": "https://example.invalid/dh.jar", "local": "dh.jar",
                  "clientReq": true, "description": "Renders far-away terrain at low detail.",
                  "conflictsWith": ["iris.jar"] },
                { "name": "Server Tools", "remote": "https://example.invalid/st.jar", "local": "st.jar",
                  "clientReq": false, "serverReq": true }
              ]
            }
            """;

    /** Folders the modal's background image caching creates. Tests run with no game mode set, so
     *  pack paths resolve to the working directory (the repository); the ones this test creates
     *  are removed afterwards so they don't litter the checkout. */
    private static final String[] WORKING_DIR_FOLDERS = { "installs", "metadata" };
    private static final List< File > createdFolders = new ArrayList<>();

    private Stage stage;

    @BeforeAll
    static void noteWorkingDirFolders()
    {
        for ( String name : WORKING_DIR_FOLDERS ) {
            File folder = new File( name );
            if ( !folder.exists() ) {
                createdFolders.add( folder );
            }
        }
    }

    @AfterAll
    static void removeCreatedWorkingDirFolders() throws Exception
    {
        // Give the modal's background downloads a moment to settle before deleting their folders.
        WaitForAsyncUtils.sleep( 500, TimeUnit.MILLISECONDS );
        for ( File folder : createdFolders ) {
            org.apache.commons.io.FileUtils.deleteDirectory( folder );
        }
    }

    @AfterEach
    void restoreSources()
    {
        MCLauncherModpackDetailModal.problemSource = ModPackAuditLog::analyzeProblems;
        MCLauncherModpackDetailModal.pendingDiffSource =
                pack -> pack.isUpdateAvailable() ? PendingUpdateDiff.compute( pack ) : null;
        MCLauncherModpackDetailModal.optionalEnabledSource =
                com.micatechnologies.minecraft.launcher.game.modpack.OptionalMods::enabledKeys;
    }

    @Start
    private void start( Stage stage )
    {
        BundledFonts.ensureLoaded();
        this.stage = stage;
    }

    /** Stubs the modal's data sources: problems, a pending update, and the optional mods' defaults
     *  (the player's stored choices are never read, and nothing is toggled, so no config is written). */
    private static void stubSources()
    {
        List< ModPackAuditLog.Problem > problems = new ArrayList<>();
        for ( int i = 0; i < 5; i++ ) {
            problems.add( new ModPackAuditLog.Problem( "config/mod-" + i + "/settings.toml", 3 + i % 4, i % 3 == 0 ) );
        }
        MCLauncherModpackDetailModal.problemSource = ( root, launches ) -> problems;
        MCLauncherModpackDetailModal.pendingDiffSource = pack -> new PendingUpdateDiff.Result(
                3, 1, 5,
                List.of( "Create", "Farmer's Delight", "Distant Horizons" ),
                List.of( "OptiFine" ),
                List.of( "Iris Shaders", "Sodium", "JEI", "Waystones", "Jade" ),
                Set.of( "Iris Shaders", "Sodium" ),
                List.of( "Distant Horizons" ) );
        MCLauncherModpackDetailModal.optionalEnabledSource = ( pack, mods ) -> {
            Set< String > on = new LinkedHashSet<>();
            for ( GameOptionalMod mod : mods ) {
                if ( mod.isDefaultEnabled() ) {
                    on.add( mod.key() );
                }
            }
            return on;
        };
    }

    @Test
    void rendersEachTabAndPinsTheTabBar( FxRobot robot ) throws Exception
    {
        stubSources();
        for ( String[] theme : new String[][]{ { "dark", ConfigConstants.THEME_DARK },
                                                { "light", ConfigConstants.THEME_LIGHT },
                                                { "orangepurple", ConfigConstants.THEME_ORANGE_PURPLE } } ) {
            GameModPack pack = JSONUtilities.getGson().fromJson( PACK_JSON, GameModPack.class );
            AtomicReference< MCLauncherModpackDetailModal > modalRef = new AtomicReference<>();
            AtomicReference< StackPane > rootRef = new AtomicReference<>();
            open( robot, theme, pack, 1100, 820, modalRef, rootRef );
            MCLauncherModpackDetailModal modal = modalRef.get();
            StackPane root = rootRef.get();

            shoot( robot, theme[ 0 ] + "-overview-top" );
            assertFalse( modal.isTabBarPinned(), "the tab bar starts in place under the hero" );

            // Scroll past the tab bar: it pins at the top of the scroll area.
            double pinAt = fx( robot, modal::tabBarPinOffsetForTest );
            robot.interact( () -> modal.scrollToForTest( pinAt + 180 ) );
            settle( 400 );
            assertTrue( modal.isTabBarPinned(), "scrolled past it, the tab bar pins" );
            assertBarAtViewportTop( robot, root );
            assertTrue( fx( robot, () -> root.lookup( ".modpackDetailPinnedClose" ).isVisible() ),
                        "the pinned row has a close button" );
            shoot( robot, theme[ 0 ] + "-overview-pinned" );

            // The Optional mods summary's Choose opens the Mods tab, pinned, at the optional mods.
            robot.interact( () -> modal.scrollToForTest( 0 ) );
            settle( 200 );
            MFXButton choose = fx( robot, () -> findButton( root,
                    LocalizationManager.get( "detailModal.optionalSummary.choose" ) ) );
            assertNotNull( choose, "the Overview shows the Optional mods summary" );
            robot.interact( choose::fire );
            settle( 900 );
            double chosenOffset = fx( robot, modal::scrollOffsetForTest );
            double chosenPinAt = fx( robot, modal::tabBarPinOffsetForTest );
            assertTrue( modal.isTabBarPinned(),
                        () -> "Choose lands with the tab bar pinned (offset " + chosenOffset + ", bar at " + chosenPinAt + ")" );
            assertEquals( fx( robot, modal::tabBarPinOffsetForTest ), fx( robot, modal::scrollOffsetForTest ), 1.0,
                          "Choose shows the Mods tab from its top" );
            assertTrue( fx( robot, () -> activeTabText( root ) )
                                .startsWith( LocalizationManager.get( "detailModal.tab.mods" ) ) );
            assertBarAtViewportTop( robot, root );
            shoot( robot, theme[ 0 ] + "-mods" );

            // Switching tabs while pinned keeps the bar pinned.
            for ( String tab : List.of( DetailModalLayout.TAB_FILES, DetailModalLayout.TAB_ACTIVITY,
                                        DetailModalLayout.TAB_SETTINGS, DetailModalLayout.TAB_PROBLEMS ) ) {
                robot.interact( () -> modal.selectTabForTest( tab ) );
                settle( 900 );
                double tabOffset = fx( robot, modal::scrollOffsetForTest );
                double tabPinAt = fx( robot, modal::tabBarPinOffsetForTest );
                assertTrue( modal.isTabBarPinned(), () -> tab + ": still pinned after the switch (offset "
                                                          + tabOffset + ", bar at " + tabPinAt + ")" );
                assertBarAtViewportTop( robot, root );
                shoot( robot, theme[ 0 ] + "-" + tab );
            }

            // An unpinned position survives a switch: no jump back over the hero.
            robot.interact( () -> modal.scrollToForTest( 60 ) );
            settle( 300 );
            robot.interact( () -> modal.selectTabForTest( DetailModalLayout.TAB_OVERVIEW ) );
            settle( 600 );
            assertFalse( modal.isTabBarPinned() );
            assertEquals( 60, fx( robot, modal::scrollOffsetForTest ), 1.0, "an unpinned position stays put" );

            // Colour from pack art: an orange seed, as a pack with an orange logo would give.
            robot.interact( () -> {
                Node card = root.lookup( ".modpackDetailCard" );
                boolean dark = !"light".equals( theme[ 0 ] );
                PackColorScheme.apply( card, PackColorScheme.roles( 0xE87A1E, dark, dark ? 0x222732 : 0xEDEEF2 ) );
                modal.scrollToForTest( 0 );
            } );
            settle( 600 );
            shoot( robot, theme[ 0 ] + "-packcolors" );
            robot.interact( modal::dispose );
        }

        // The smallest window the launcher allows.
        String[] dark = { "dark", ConfigConstants.THEME_DARK };
        GameModPack pack = JSONUtilities.getGson().fromJson( PACK_JSON, GameModPack.class );
        AtomicReference< MCLauncherModpackDetailModal > modalRef = new AtomicReference<>();
        AtomicReference< StackPane > rootRef = new AtomicReference<>();
        open( robot, dark, pack, 750, 475, modalRef, rootRef );
        MCLauncherModpackDetailModal modal = modalRef.get();
        shoot( robot, "dark-minsize-top" );
        double pinAt = fx( robot, modal::tabBarPinOffsetForTest );
        robot.interact( () -> modal.scrollToForTest( pinAt + 120 ) );
        settle( 400 );
        assertTrue( modal.isTabBarPinned() );
        assertBarAtViewportTop( robot, rootRef.get() );
        shoot( robot, "dark-minsize-pinned" );
        robot.interact( () -> modal.selectTabForTest( DetailModalLayout.TAB_SETTINGS ) );
        settle( 900 );
        double settingsOffset = fx( robot, modal::scrollOffsetForTest );
        double settingsPinAt = fx( robot, modal::tabBarPinOffsetForTest );
        assertTrue( modal.isTabBarPinned(), () -> "a tab switch at the minimum size stays pinned (offset "
                                                  + settingsOffset + ", bar at " + settingsPinAt + ")" );
        assertBarAtViewportTop( robot, rootRef.get() );
        shoot( robot, "dark-minsize-settings" );
        robot.interact( modal::dispose );
    }

    private void open( FxRobot robot, String[] theme, GameModPack pack, double w, double h,
                       AtomicReference< MCLauncherModpackDetailModal > modal, AtomicReference< StackPane > root )
    {
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
            stage.setScene( new Scene( r, w, h ) );
            stage.sizeToScene();
            stage.show();
            m.show( pack );
        } );
        settle( 1800 );
    }

    private static void settle( long ms )
    {
        WaitForAsyncUtils.sleep( ms, TimeUnit.MILLISECONDS );
        WaitForAsyncUtils.waitForFxEvents();
    }

    private static < T > T fx( FxRobot robot, java.util.function.Supplier< T > read )
    {
        AtomicReference< T > out = new AtomicReference<>();
        robot.interact( () -> out.set( read.get() ) );
        return out.get();
    }

    /** The pinned tab bar's top edge is the scroll area's viewport top edge. */
    private static void assertBarAtViewportTop( FxRobot robot, StackPane root )
    {
        double[] ys = fx( robot, () -> {
            ScrollPane scroll = (ScrollPane) root.lookup( ".modpackDetailScroll" );
            Node viewport = scroll.lookup( ".viewport" );
            Node bar = root.lookup( ".modpackDetailTabBar" );
            return new double[]{ viewport.localToScene( 0, 0 ).getY(), bar.localToScene( 0, 0 ).getY() };
        } );
        assertEquals( ys[ 0 ], ys[ 1 ], 1.0, "the pinned tab bar sits at the top of the scroll area" );
    }

    private static MFXButton findButton( StackPane root, String text )
    {
        for ( Node n : root.lookupAll( ".mfx-button" ) ) {
            if ( n instanceof MFXButton b && text.equals( b.getText() ) && b.isVisible() ) {
                return b;
            }
        }
        return null;
    }

    private static String activeTabText( StackPane root )
    {
        for ( Node n : root.lookupAll( ".modpackDetailTab" ) ) {
            if ( n.getStyleClass().contains( "active" ) && n instanceof Label l ) {
                return l.getText();
            }
        }
        return "";
    }

    private void shoot( FxRobot robot, String name ) throws Exception
    {
        AtomicReference< WritableImage > shot = new AtomicReference<>();
        robot.interact( () -> shot.set( stage.getScene().snapshot( null ) ) );
        File out = new File( "build/target/snapshots/detail/" + name + ".png" );
        out.getParentFile().mkdirs();
        ImageIO.write( toBuffered( shot.get() ), "png", out );
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
