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

import com.micatechnologies.minecraft.launcher.config.ConfigManager;
import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
import com.micatechnologies.minecraft.launcher.game.session.GameSession;
import com.micatechnologies.minecraft.launcher.game.session.GameSessionRegistry;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Circle;
import javafx.stage.Stage;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Running Games window: one tab per game, showing its launch steps while it prepares, then
 * its live log, uptime and Stop/Kill, and how it ended.
 *
 * <p>Launching no longer takes over the launcher's main window, so the library stays usable
 * for launching more games. Closing this window only hides it; the games keep running and keep
 * being logged. A tab can be closed once its game has ended.</p>
 *
 * <p>All methods may be called from any thread.</p>
 *
 * @since 2026.10
 */
public final class RunningGamesWindow
{
    private static RunningGamesWindow instance;

    private final Stage                stage   = new Stage();
    private final TabPane              tabs    = new TabPane();
    private final Label                empty   = new Label( LocalizationManager.get( "session.window.empty" ) );
    private final Map< Long, Tab >     byId    = new HashMap<>();
    private final Map< Long, GameSessionPane > panes = new HashMap<>();
    private final Runnable             registryListener = () -> Platform.runLater( this::sync );
    private       String               appliedThemeKey = ConfigManager.getTheme() + ":" + GUIUtilities.isOsDark();

    private RunningGamesWindow()
    {
        empty.getStyleClass().add( "muted" );
        StackPane root = new StackPane( empty, tabs );
        root.getStyleClass().add( "rootPane" );
        tabs.setTabClosingPolicy( TabPane.TabClosingPolicy.ALL_TABS );
        // Switching games fades the selected game's pane in (Material's fade-through).
        tabs.getSelectionModel().selectedItemProperty().addListener( ( o, was, now ) -> {
            if ( was != null && now != null ) {
                Motion.fadeThrough( now.getContent() );
            }
        } );
        stage.setTitle( LocalizationManager.get( "session.window.title" ) );
        stage.setScene( new Scene( root, 1000, 720 ) );
        stage.setMinWidth( 640 );
        stage.setMinHeight( 420 );
        MCLauncherGuiWindow.installCurrentThemeStylesheets( root );
        stage.setOnShown( e -> com.micatechnologies.minecraft.launcher.utilities.WindowChromeManager
                .applyTitleBarDarkMode( stage, !GUIUtilities.isLightChrome( ConfigManager.getTheme() ) ) );
        // Closing hides; the games, and their logs, carry on.
        stage.setOnCloseRequest( e -> {
            e.consume();
            stage.hide();
        } );
        GameSessionRegistry.get().addListener( registryListener );
        sync();
    }

    /** @return the window, created on first use. FX thread only. */
    private static RunningGamesWindow get()
    {
        if ( instance == null ) {
            instance = new RunningGamesWindow();
        }
        return instance;
    }

    /**
     * Shows the window with a game's tab selected.
     *
     * @param session the game to show
     *
     * @since 2026.10
     */
    public static void showSession( GameSession session )
    {
        Platform.runLater( () -> {
            RunningGamesWindow w = get();
            w.sync();
            Tab tab = w.byId.get( session.id() );
            if ( tab != null ) {
                w.tabs.getSelectionModel().select( tab );
            }
            w.reveal();
        } );
    }

    /**
     * Shows the window.
     *
     * @since 2026.10
     */
    public static void showWindow()
    {
        Platform.runLater( () -> get().reveal() );
    }

    /**
     * Hides the window unless a game is still preparing (whose progress the user is watching).
     * Used when "Show console on launch" is off: the window shows each launch's progress, then
     * gets out of the way once the game is up.
     *
     * @since 2026.10
     */
    public static void hideUnlessPreparing()
    {
        Platform.runLater( () -> {
            if ( instance == null ) {
                return;
            }
            for ( GameSession s : GameSessionRegistry.get().active() ) {
                if ( s.phase() == GameSession.Phase.PREPARING ) {
                    return;
                }
            }
            instance.stage.hide();
        } );
    }

    /**
     * Re-applies the launcher theme after the user changes it. The window is long-lived, so
     * without this it kept the theme it was opened with.
     *
     * @since 2026.10
     */
    public static void refreshTheme()
    {
        Platform.runLater( () -> {
            if ( instance == null ) {
                return;
            }
            // Called on every screen switch; only a real theme change is worth a full CSS pass.
            String key = ConfigManager.getTheme() + ":" + GUIUtilities.isOsDark();
            if ( key.equals( instance.appliedThemeKey ) ) {
                return;
            }
            instance.appliedThemeKey = key;
            javafx.scene.Parent root = instance.stage.getScene().getRoot();
            root.getStylesheets().clear();
            MCLauncherGuiWindow.installCurrentThemeStylesheets( root );
            com.micatechnologies.minecraft.launcher.utilities.WindowChromeManager.applyTitleBarDarkMode(
                    instance.stage, !GUIUtilities.isLightChrome( ConfigManager.getTheme() ) );
        } );
    }

    /**
     * Closes the window for good, at launcher exit.
     *
     * @since 2026.10
     */
    public static void shutdown()
    {
        Platform.runLater( () -> {
            if ( instance == null ) {
                return;
            }
            GameSessionRegistry.get().removeListener( instance.registryListener );
            instance.panes.values().forEach( GameSessionPane::dispose );
            instance.stage.close();
            instance = null;
        } );
    }

    private void reveal()
    {
        if ( stage.isIconified() ) {
            stage.setIconified( false );
        }
        stage.show();
        stage.toFront();
        stage.requestFocus();
    }

    /** Adds tabs for new sessions, drops tabs for dismissed ones, refreshes labels. */
    private void sync()
    {
        List< GameSession > sessions = GameSessionRegistry.get().sessions();
        java.util.Set< Long > live = new java.util.HashSet<>();
        for ( GameSession s : sessions ) {
            live.add( s.id() );
            Tab tab = byId.get( s.id() );
            if ( tab == null ) {
                tab = newTab( s );
                byId.put( s.id(), tab );
                tabs.getTabs().add( tab );
            }
            refreshTab( tab, s );
        }
        for ( Long id : List.copyOf( byId.keySet() ) ) {
            if ( !live.contains( id ) ) {
                Tab gone = byId.remove( id );
                tabs.getTabs().remove( gone );
                GameSessionPane pane = panes.remove( id );
                if ( pane != null ) {
                    pane.dispose();
                }
            }
        }
        boolean none = tabs.getTabs().isEmpty();
        empty.setVisible( none );
        tabs.setVisible( !none );
    }

    private Tab newTab( GameSession session )
    {
        GameSessionPane pane = new GameSessionPane( session );
        panes.put( session.id(), pane );
        Tab tab = new Tab();
        tab.setContent( pane.root() );
        tab.setOnCloseRequest( e -> {
            if ( session.phase().isActive() ) {
                e.consume();  // a live game's tab stays; stop the game first
            }
        } );
        tab.setOnClosed( e -> {
            byId.remove( session.id() );
            GameSessionPane removed = panes.remove( session.id() );
            if ( removed != null ) {
                removed.dispose();
            }
            GameSessionRegistry.get().dismiss( session.id() );
        } );
        return tab;
    }

    static void refreshTab( Tab tab, GameSession session )
    {
        Circle dot = new Circle( 4 );
        dot.getStyleClass().addAll( "sessionDot", "sessionStatus-" + session.phase().name().toLowerCase( java.util.Locale.ROOT ) );
        HBox graphic = new HBox( 6, dot );
        graphic.setAlignment( Pos.CENTER_LEFT );
        if ( session.accountUuid() != null ) {
            ImageView avatar = new ImageView( AvatarImages.get( session.accountUuid() ) );
            avatar.setFitWidth( 16 );
            avatar.setFitHeight( 16 );
            avatar.setClip( new Circle( 8, 8, 8 ) );
            graphic.getChildren().add( avatar );
        }
        tab.setGraphic( graphic );
        tab.setText( session.packName() );
        tab.setClosable( !session.phase().isActive() );
    }
}
