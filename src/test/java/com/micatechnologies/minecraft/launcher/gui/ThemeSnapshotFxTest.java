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
import io.github.palexdev.materialfx.controls.MFXButton;
import io.github.palexdev.materialfx.controls.MFXCheckbox;
import io.github.palexdev.materialfx.controls.MFXComboBox;
import io.github.palexdev.materialfx.controls.MFXPasswordField;
import io.github.palexdev.materialfx.controls.MFXProgressBar;
import io.github.palexdev.materialfx.controls.MFXTextField;
import io.github.palexdev.materialfx.controls.MFXToggleButton;
import javafx.collections.FXCollections;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Renders every FXML screen and a gallery of styled controls in every theme, writing PNGs to
 * {@code build/target/snapshots/themes/<theme>/<view>.png}. It asserts nothing: it is the visual
 * baseline for styling work, compared before and after a change. Screens load without their
 * controllers, so they show static chrome rather than live data; the gallery covers the controls
 * the controllers add at runtime.
 *
 * @since 2026.10
 */
@ExtendWith( ApplicationExtension.class )
@EnabledIfEnvironmentVariable( named = "MMCL_RUN_TESTFX", matches = "true" )
class ThemeSnapshotFxTest
{
    /** Directory name, configured theme, and OS dark mode (for Native). */
    private record ThemeCase( String dir, String theme, boolean osDark ) {}

    private static final List< ThemeCase > THEMES = List.of(
            new ThemeCase( "dark", ConfigConstants.THEME_DARK, true ),
            new ThemeCase( "light", ConfigConstants.THEME_LIGHT, false ),
            new ThemeCase( "bluegray", ConfigConstants.THEME_BLUE_GRAY, true ),
            new ThemeCase( "orangepurple", ConfigConstants.THEME_ORANGE_PURPLE, true ),
            new ThemeCase( "creeper", ConfigConstants.THEME_CREEPER, true ),
            new ThemeCase( "native-dark", ConfigConstants.THEME_NATIVE, true ),
            new ThemeCase( "native-light", ConfigConstants.THEME_NATIVE, false ) );

    private static final String[] SCREENS = {
            "mainGUI", "gameLibraryGUI", "settingsGUI", "modpackEditorGUI", "runtimeManagementGUI",
            "launchProgressGUI", "progressGUI", "loginGUI" };

    private Stage stage;

    @Start
    private void start( Stage stage )
    {
        this.stage = stage;
    }

    @Test
    void rendersEveryScreenInEveryTheme( FxRobot robot ) throws Exception
    {
        for ( ThemeCase theme : THEMES ) {
            for ( String screen : SCREENS ) {
                FXMLLoader loader = new FXMLLoader( resource( "gui/" + screen + ".fxml" ) );
                loader.setResources( LocalizationManager.currentBundle() );
                AtomicReference< Parent > root = new AtomicReference<>();
                robot.interact( () -> {
                    try {
                        root.set( loader.load() );
                    }
                    catch ( Exception e ) {
                        throw new IllegalStateException( screen, e );
                    }
                } );
                if ( "settingsGUI".equals( screen ) ) {
                    // One pane per settings category; the controller shows one at a time.
                    List< Map.Entry< String, Object > > panes = new ArrayList<>();
                    for ( Map.Entry< String, Object > e : loader.getNamespace().entrySet() ) {
                        if ( e.getValue() instanceof ScrollPane && e.getKey().endsWith( "Pane" ) ) {
                            panes.add( e );
                        }
                    }
                    for ( Map.Entry< String, Object > shown : panes ) {
                        robot.interact( () -> panes.forEach( p -> {
                            Node n = (Node) p.getValue();
                            n.setVisible( p == shown );
                            n.setManaged( p == shown );
                        } ) );
                        render( robot, theme, root.get(), "settings-" + shown.getKey().replace( "Pane", "" ),
                                1000, 800 );
                    }
                }
                else {
                    render( robot, theme, root.get(), screen, 1000, 800 );
                }
            }
            AtomicReference< Parent > gallery = new AtomicReference<>();
            robot.interact( () -> gallery.set( gallery() ) );
            render( robot, theme, gallery.get(), "gallery", 1000, 980 );
        }
    }

    /** One of every control and text style the screens use, in a scrollable column. */
    private static Parent gallery()
    {
        VBox column = new VBox( 14 );
        column.setPadding( new Insets( 20 ) );

        VBox text = new VBox( 4 );
        for ( String cls : new String[]{ "heading-display", "heading-h1", "heading-h2", "heading-h3" } ) {
            Label l = new Label( cls );
            l.getStyleClass().add( cls );
            text.getChildren().add( l );
        }
        for ( String cls : new String[]{ "", "muted", "subtle", "text-mono" } ) {
            Label l = new Label( "Body text " + cls );
            if ( !cls.isEmpty() ) {
                l.getStyleClass().add( cls );
            }
            text.getChildren().add( l );
        }
        text.getChildren().add( new Hyperlink( "A hyperlink" ) );
        column.getChildren().add( text );

        FlowPane buttons = new FlowPane( 10, 10 );
        buttons.getChildren().add( new MFXButton( "Default" ) );
        for ( String cls : new String[]{ "playBtn", "heroPlayBtn", "modpackDetailPlayBtn",
                                         "modpackDetailSecondaryBtn", "heroCardSecondaryBtn", "modpackDetailQuickAction",
                                         "settingsNavBtn", "wizardSkipBtn" } ) {
            MFXButton b = new MFXButton( cls );
            b.getStyleClass().add( cls );
            buttons.getChildren().add( b );
        }
        MFXButton disabled = new MFXButton( "Disabled" );
        disabled.setDisable( true );
        buttons.getChildren().add( disabled );
        column.getChildren().add( buttons );

        MFXToggleButton on = new MFXToggleButton( "Toggle on" );
        on.setSelected( true );
        MFXCheckbox check = new MFXCheckbox( "Checkbox" );
        check.setSelected( true );
        HBox toggles = new HBox( 16, on, new MFXToggleButton( "Toggle off" ), check );
        column.getChildren().add( toggles );

        MFXTextField field = new MFXTextField( "Text field" );
        field.setFloatingText( "Floating label" );
        MFXPasswordField password = new MFXPasswordField( "secret" );
        MFXComboBox< String > combo = new MFXComboBox<>( FXCollections.observableArrayList( "One", "Two" ) );
        combo.selectFirst();
        HBox fields = new HBox( 12, field, password, combo );
        column.getChildren().add( fields );

        FlowPane chips = new FlowPane( 8, 8 );
        for ( String cls : new String[]{ "stat-chip", "stat-chip-info", "stat-chip-success", "stat-chip-warn",
                                         "chip-offline" } ) {
            Label chip = new Label( cls );
            chip.getStyleClass().addAll( "stat-chip", cls );
            chips.getChildren().add( chip );
        }
        column.getChildren().add( chips );

        HBox banners = new HBox( 10 );
        for ( String cls : new String[]{ "banner-announcement", "banner-warning", "banner-danger" } ) {
            HBox banner = new HBox( new Label( cls ) );
            banner.getStyleClass().add( cls );
            banner.setPadding( new Insets( 8 ) );
            banners.getChildren().add( banner );
        }
        column.getChildren().add( banners );

        VBox card = new VBox( 6, new Label( "Card" ), new Label( "Card body text" ) );
        card.getStyleClass().add( "card" );
        card.setPadding( new Insets( 12 ) );
        VBox detailCard = new VBox( 6, new Label( "modpackDetailCard" ) );
        detailCard.getStyleClass().add( "modpackDetailCard" );
        detailCard.setPadding( new Insets( 12 ) );
        VBox crashCard = new VBox( 6, new Label( "crashDiagnosisCard" ) );
        crashCard.getStyleClass().add( "crashDiagnosisCard" );
        crashCard.setPadding( new Insets( 12 ) );
        column.getChildren().add( new HBox( 12, card, detailCard, crashCard ) );

        ProgressBar progress = new ProgressBar( 0.6 );
        MFXProgressBar mfxProgress = new MFXProgressBar( 0.35 );
        column.getChildren().add( new HBox( 12, progress, mfxProgress ) );

        ListView< String > list = new ListView<>( FXCollections.observableArrayList( "List item one",
                                                                                     "List item two (selected)",
                                                                                     "List item three" ) );
        list.getSelectionModel().select( 1 );
        list.setPrefHeight( 90 );
        TabPane tabs = new TabPane( new Tab( "First tab", new Label( "Tab content" ) ), new Tab( "Second tab" ) );
        tabs.setPrefHeight( 90 );
        TextArea area = new TextArea( "[12:00:01] [Render thread/INFO]: Monospace log text" );
        area.getStyleClass().add( "text-mono" );
        area.setPrefHeight( 90 );
        HBox lists = new HBox( 12, list, tabs, area );
        column.getChildren().add( lists );

        DialogPane dialog = new DialogPane();
        dialog.setHeaderText( "Dialog header" );
        dialog.setContentText( "Dialog content text." );
        dialog.getButtonTypes().addAll( ButtonType.OK, ButtonType.CANCEL );
        dialog.setMaxWidth( 480 );
        column.getChildren().add( dialog );

        StackPane root = new StackPane( column );
        root.getStyleClass().add( "rootPane" );
        return root;
    }

    private void render( FxRobot robot, ThemeCase theme, Parent root, String name, double width, double height )
            throws Exception
    {
        List< String > sheets = MCLauncherGuiWindow.themeStylesheetPaths( theme.theme(), theme.osDark() );
        String bg = MCLauncherGuiWindow.themeBgHexStatic( sheets.get( sheets.size() - 1 ) );
        robot.interact( () -> {
            root.getStylesheets().clear();
            for ( String sheet : sheets ) {
                root.getStylesheets().add( resource( sheet ).toExternalForm() );
            }
            if ( root instanceof Region region ) {
                region.setStyle( "-fx-background-color: " + bg + ";" );
            }
            Scene scene = root.getScene() != null ? root.getScene() : new Scene( root, width, height );
            stage.setScene( scene );
            stage.setWidth( width );
            stage.setHeight( height );
            stage.show();
        } );
        WaitForAsyncUtils.sleep( 300, TimeUnit.MILLISECONDS );
        WaitForAsyncUtils.waitForFxEvents();
        AtomicReference< WritableImage > shot = new AtomicReference<>();
        robot.interact( () -> shot.set( stage.getScene().snapshot( null ) ) );
        File out = new File( "build/target/snapshots/themes/" + theme.dir() + "/" + name + ".png" );
        out.getParentFile().mkdirs();
        ImageIO.write( toBuffered( shot.get() ), "png", out );
    }

    private static java.net.URL resource( String path )
    {
        return java.util.Objects.requireNonNull( ThemeSnapshotFxTest.class.getClassLoader().getResource( path ),
                                                 path );
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
