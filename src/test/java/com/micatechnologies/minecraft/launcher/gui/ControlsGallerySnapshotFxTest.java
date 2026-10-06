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

import io.github.palexdev.materialfx.controls.MFXButton;
import io.github.palexdev.materialfx.controls.MFXPasswordField;
import io.github.palexdev.materialfx.controls.MFXProgressBar;
import io.github.palexdev.materialfx.controls.MFXTextField;
import javafx.collections.FXCollections;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.PopupWindow;
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
 * Renders every kind of control the launcher uses, outside the switches and dropdowns that
 * {@link ControlsSnapshotFxTest} covers, under every theme: fields, spinner, checkbox, link, slider,
 * progress bar, log area, lists and tables, buttons, and a dialog pane. Then opens the popups that
 * live in their own windows, where theme rules most easily miss (the dialog's dropdown menu, a
 * tooltip, a context menu), and renders each. PNGs go to {@code build/target/snapshots/}. Opt-in,
 * like the other TestFX tests.
 *
 * @since 2026.10
 */
@ExtendWith( ApplicationExtension.class )
@EnabledIfEnvironmentVariable( named = "MMCL_RUN_TESTFX", matches = "true" )
class ControlsGallerySnapshotFxTest
{
    private static final String[] THEMES = { "dark", "light", "bluegray", "orangepurple", "creeper", "native",
                                             "native-light" };

    private Stage stage;

    /** A row for the table. Public so PropertyValueFactory can read it. */
    public static final class Row
    {
        private final String name;
        private final String version;

        Row( String name, String version )
        {
            this.name = name;
            this.version = version;
        }

        public String getName()
        {
            return name;
        }

        public String getVersion()
        {
            return version;
        }
    }

    @Start
    private void start( Stage stage )
    {
        BundledFonts.ensureLoaded();
        this.stage = stage;
    }

    @Test
    void rendersEveryControlAndPopup( FxRobot robot ) throws Exception
    {
        for ( String theme : THEMES ) {
            AtomicReference< ComboBox< String > > dialogCombo = new AtomicReference<>();
            AtomicReference< Label > tipped = new AtomicReference<>();
            robot.interact( () -> {
                MFXTextField field = new MFXTextField( "Alto 26.10" );
                field.setFloatingText( "Pack name" );
                field.setPrefWidth( 220 );
                MFXPasswordField password = new MFXPasswordField( "secret" );
                password.setFloatingText( "Proxy password" );
                password.setPrefWidth( 220 );
                Spinner< Integer > spinner = new Spinner<>( 1, 64, 8 );
                spinner.setEditable( true );
                CheckBox checkOn = new CheckBox( "Checked" );
                checkOn.setSelected( true );
                CheckBox checkOff = new CheckBox( "Unchecked" );
                Hyperlink link = new Hyperlink( "A hyperlink" );
                Slider slider = new Slider( 0, 100, 40 );
                MFXProgressBar progress = new MFXProgressBar( 0.6 );
                progress.setPrefWidth( 220 );
                ToggleButton toggle = new ToggleButton( "Toggle button" );
                toggle.setSelected( true );
                MFXButton filled = new MFXButton( "Filled" );
                MFXButton tonal = new MFXButton( "Tonal" );
                tonal.getStyleClass().add( "tonalBtn" );
                MFXButton disabled = new MFXButton( "Disabled" );
                disabled.setDisable( true );
                Label tip = new Label( "Hover me (tooltip)" );
                tip.setTooltip( new Tooltip( "A tooltip in this theme" ) );
                tipped.set( tip );

                TextArea log = new TextArea( "[12:00:00] [main/INFO]: Loading 312 mods\n"
                                             + "[12:00:01] [main/WARN]: Missing texture\n" );
                log.setPrefRowCount( 3 );
                ListView< String > list = new ListView<>( FXCollections.observableArrayList(
                        "All the Mods 9", "Vanilla 1.21.4", "Create: Above and Beyond" ) );
                list.getSelectionModel().select( 1 );
                list.setPrefHeight( 96 );
                TableView< Row > table = new TableView<>( FXCollections.observableArrayList(
                        new Row( "jei", "19.21.0" ), new Row( "create", "6.0.4" ) ) );
                TableColumn< Row, String > nameCol = new TableColumn<>( "Mod" );
                nameCol.setCellValueFactory( new PropertyValueFactory<>( "name" ) );
                TableColumn< Row, String > versionCol = new TableColumn<>( "Version" );
                versionCol.setCellValueFactory( new PropertyValueFactory<>( "version" ) );
                table.getColumns().setAll( java.util.List.of( nameCol, versionCol ) );
                table.getSelectionModel().select( 0 );
                table.setPrefHeight( 96 );

                // A dialog pane as GUIUtilities builds them, with a plain JavaFX dropdown in it
                // (the Modpack Editor's ChoiceDialogs use one).
                DialogPane dialog = new DialogPane();
                dialog.setHeaderText( "Choose a loader" );
                ComboBox< String > combo = new ComboBox<>( FXCollections.observableArrayList(
                        "Forge", "NeoForge", "Fabric" ) );
                combo.getSelectionModel().select( 1 );
                dialogCombo.set( combo );
                dialog.setContent( new VBox( 8, new Label( "Pick the mod loader for this pack." ), combo ) );
                dialog.getButtonTypes().setAll( ButtonType.OK, ButtonType.CANCEL );

                VBox left = new VBox( 12, field, password, new HBox( 8, new Label( "RAM (GB)" ), spinner ),
                                      new HBox( 12, checkOn, checkOff ), link, slider, progress,
                                      new HBox( 8, filled, tonal, disabled ), toggle, tip );
                VBox right = new VBox( 12, log, list, table, dialog );
                right.setPrefWidth( 420 );
                HBox root = new HBox( 24, left, right );
                root.setStyle( "-fx-padding: 20;" );
                root.getStyleClass().add( "rootPane" );
                for ( String sheet : new String[]{ "ui/ui-base.css", "ui/ui-tokens-" + theme + ".css" } ) {
                    root.getStylesheets().add( getClass().getClassLoader().getResource( sheet ).toExternalForm() );
                }
                stage.setScene( new Scene( root, 760, 720 ) );
                stage.show();
            } );
            settle();
            write( robot, stage.getScene(), "gallery-" + theme + ".png" );

            robot.interact( () -> dialogCombo.get().show() );
            settle();
            writePopup( robot, "gallery-" + theme + "-dialog-combo.png" );
            robot.interact( () -> dialogCombo.get().hide() );

            robot.interact( () -> {
                Label tip = tipped.get();
                javafx.geometry.Bounds b = tip.localToScreen( tip.getBoundsInLocal() );
                tip.getTooltip().show( tip, b.getMinX(), b.getMaxY() + 4 );
            } );
            settle();
            writePopup( robot, "gallery-" + theme + "-tooltip.png" );
            robot.interact( () -> tipped.get().getTooltip().hide() );

            robot.interact( () -> {
                ContextMenu menu = new ContextMenu( new MenuItem( "Open folder" ), new MenuItem( "Copy log" ),
                                                    new SeparatorMenuItem(), new MenuItem( "Delete" ) );
                menu.getItems().get( 3 ).setDisable( true );
                tipped.get().setContextMenu( menu );
                menu.show( tipped.get(), Side.BOTTOM, 0, 0 );
            } );
            settle();
            writePopup( robot, "gallery-" + theme + "-context-menu.png" );
            robot.interact( () -> tipped.get().getContextMenu().hide() );
        }
    }

    private static void settle()
    {
        WaitForAsyncUtils.waitForFxEvents();
        WaitForAsyncUtils.sleep( 300, TimeUnit.MILLISECONDS );
        WaitForAsyncUtils.waitForFxEvents();
    }

    /** Writes the one showing popup window. */
    private void writePopup( FxRobot robot, String name ) throws Exception
    {
        AtomicReference< Scene > popup = new AtomicReference<>();
        robot.interact( () -> {
            for ( Window window : Window.getWindows() ) {
                if ( window instanceof PopupWindow && window.isShowing() && window.getScene() != null ) {
                    popup.set( window.getScene() );
                }
            }
        } );
        if ( popup.get() != null ) {
            write( robot, popup.get(), name );
        }
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
