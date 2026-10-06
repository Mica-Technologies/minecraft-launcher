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
import io.github.palexdev.materialfx.controls.MFXButton;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.stage.Stage;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Running Games view: one tab per game, showing its launch steps while it prepares, then
 * its live log, uptime and Stop/Kill, and how it ended.
 *
 * <p>Launching no longer takes over the launcher's main window, so the library stays usable
 * for launching more games. The view lives in its own window by default, or docked along the
 * bottom of the main window; a button in its header moves it between the two, and the choice is
 * saved. Docked, it collapses to its header and follows the main window from screen to screen
 * (each screen has its own scene, so {@link #followScreen(Scene)} moves it). Closing its window,
 * or collapsing the dock, only hides it; the games keep running and keep being logged. A tab can
 * be closed once its game has ended.</p>
 *
 * <p>All methods may be called from any thread unless noted.</p>
 *
 * @since 2026.10
 */
public final class RunningGamesWindow
{
    /** Share of the main window's height the expanded dock asks for. */
    private static final double DOCK_SHARE = 0.42;

    /** Least height the expanded dock takes below its header, in unscaled pixels; more if the
     *  selected game's pane needs it, so Stop and Kill are never cut off. */
    private static final double DOCK_MIN_BODY = 240;

    private static RunningGamesWindow instance;

    /** The main window's current screen root, which the dock follows. FX thread only. */
    private static Parent mainScreenRoot;

    private final Stage                stage   = new Stage();
    private final TabPane              tabs    = new TabPane();
    private final Label                empty   = new Label( LocalizationManager.get( "session.window.empty" ) );
    private final Label                title   = new Label( LocalizationManager.get( "session.window.title" ) );
    private final Label                count   = new Label();
    private final MFXButton            dockButton     = new MFXButton();
    private final MFXButton            collapseButton = new MFXButton();
    private final MFXButton            popOutButton   = new MFXButton();
    private final HBox                 header;
    private final StackPane            content;
    /** The header and the tabs: what moves between the window and the dock. */
    private final VBox                 body;
    /** The window's content; holds {@link #body} while the view is in its own window. */
    private final StackPane            windowHolder = new StackPane();
    /** Holds {@link #body} while docked, along the bottom of the main window. */
    private final DockHolder           dockHolder   = new DockHolder();
    private final Map< Long, Tab >     byId    = new HashMap<>();
    private final Map< Long, GameSessionPane > panes = new HashMap<>();
    private final Runnable             registryListener = () -> Platform.runLater( this::sync );
    private       String               appliedThemeKey = ConfigManager.getTheme() + ":" + GUIUtilities.isOsDark();
    private       boolean              docked   = ConfigManager.getRunningGamesDocked();
    private       boolean              expanded = true;

    private RunningGamesWindow()
    {
        empty.getStyleClass().add( "muted" );
        content = new StackPane( empty, tabs );
        VBox.setVgrow( content, Priority.ALWAYS );

        title.getStyleClass().add( "runningGamesTitle" );
        count.getStyleClass().add( "runningGamesCount" );
        Region spacer = new Region();
        HBox.setHgrow( spacer, Priority.ALWAYS );
        dockButton.setOnAction( e -> setDocked( true ) );
        // A labelled button: no icon in the set says "dock to the bottom of another window".
        dockButton.setText( LocalizationManager.get( "session.dock.dockIn" ) );
        dockButton.getStyleClass().add( "tonalBtn" );
        popOutButton.setOnAction( e -> setDocked( false ) );
        IconButtons.decorate( popOutButton, LauncherIcons.OPEN_EXTERNAL,
                              LocalizationManager.get( "session.dock.popOut" ) );
        collapseButton.setOnAction( e -> setExpanded( !expanded ) );
        header = new HBox( 8, title, count, spacer, dockButton, collapseButton, popOutButton );
        header.setAlignment( Pos.CENTER_LEFT );
        header.getStyleClass().add( "runningGamesHeader" );
        // Docked, a click anywhere on the header (but its buttons) collapses or expands the view.
        header.setOnMouseClicked( e -> {
            if ( docked && e.getButton() == MouseButton.PRIMARY && !isInButton( e.getPickResult().getIntersectedNode() ) ) {
                setExpanded( !expanded );
            }
        } );

        body = new VBox( header, content );
        // The theme sheets define their tokens on .root; the body carries it so they resolve both
        // in this window and docked inside the main window's screens.
        body.getStyleClass().addAll( "root", "rootPane", "runningGamesBody" );
        MCLauncherGuiWindow.installCurrentThemeStylesheets( body );

        tabs.setTabClosingPolicy( TabPane.TabClosingPolicy.ALL_TABS );
        // Switching games fades the selected game's pane in (Material's fade-through).
        tabs.getSelectionModel().selectedItemProperty().addListener( ( o, was, now ) -> {
            if ( was != null && now != null ) {
                Motion.fadeThrough( now.getContent() );
            }
        } );

        // The holder paints nothing, so the body's themed background shows, as the old root's did.
        windowHolder.setStyle( "-fx-background-color: transparent;" );
        dockHolder.getStyleClass().add( "runningGamesDock" );

        stage.setTitle( LocalizationManager.get( "session.window.title" ) );
        WindowIcons.apply( stage );
        stage.setScene( new Scene( UiScale.wrap( windowHolder ), 1000 * UiScale.get(), 720 * UiScale.get() ) );
        UiScale.install( stage.getScene() );
        ScaledMinSize.follow( stage, 640, 420 );
        stage.setOnShown( e -> com.micatechnologies.minecraft.launcher.utilities.WindowChromeManager
                .applyTitleBarDarkMode( stage, !GUIUtilities.isLightChrome( ConfigManager.getTheme() ) ) );
        // Closing hides; the games, and their logs, carry on.
        stage.setOnCloseRequest( e -> {
            e.consume();
            stage.hide();
        } );
        GameSessionRegistry.get().addListener( registryListener );
        sync();
        placeBody();
    }

    /** @return the view, created on first use. FX thread only. */
    private static RunningGamesWindow get()
    {
        if ( instance == null ) {
            instance = new RunningGamesWindow();
        }
        return instance;
    }

    /**
     * Moves the docked view into the main window's new screen. Called by the main window on
     * every screen change, after the new scene is set. FX thread only.
     *
     * @param scene the main window's new scene
     *
     * @since 2026.10
     */
    static void followScreen( Scene scene )
    {
        mainScreenRoot = scene == null ? null : scene.getRoot();
        if ( instance != null && instance.docked ) {
            instance.attachDock();
        }
    }

    /**
     * Shows the view with a game's tab selected: its window, or the dock expanded.
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
     * Shows the view: its window, or the dock expanded.
     *
     * @since 2026.10
     */
    public static void showWindow()
    {
        Platform.runLater( () -> get().reveal() );
    }

    /**
     * Hides the view unless a game is still preparing (whose progress the user is watching):
     * its window closes, or the dock collapses to its header. Used when "Show console on launch"
     * is off: the view shows each launch's progress, then gets out of the way once the game is up.
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
            if ( instance.docked ) {
                instance.setExpanded( false );
            }
            else {
                instance.stage.hide();
            }
        } );
    }

    /**
     * Re-applies the launcher theme after the user changes it. The view is long-lived, so
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
            // The sheets live on the body, which carries them into the window and the dock alike.
            instance.body.getStylesheets().clear();
            MCLauncherGuiWindow.installCurrentThemeStylesheets( instance.body );
            com.micatechnologies.minecraft.launcher.utilities.WindowChromeManager.applyTitleBarDarkMode(
                    instance.stage, !GUIUtilities.isLightChrome( ConfigManager.getTheme() ) );
        } );
    }

    /**
     * Closes the view for good, at launcher exit.
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
            instance.detachDock();
            instance.stage.close();
            instance = null;
        } );
    }

    private void reveal()
    {
        if ( docked ) {
            setExpanded( true );
            MCLauncherGuiController.requestFocus();
            return;
        }
        if ( stage.isIconified() ) {
            stage.setIconified( false );
        }
        stage.show();
        stage.toFront();
        stage.requestFocus();
    }

    /** Moves the view into the dock or into its own window, and saves the choice. */
    private void setDocked( boolean dock )
    {
        if ( docked == dock ) {
            return;
        }
        docked = dock;
        ConfigManager.setRunningGamesDocked( dock );
        expanded = true;
        placeBody();
        if ( dock ) {
            MCLauncherGuiController.requestFocus();
        }
        else {
            reveal();
        }
    }

    private void setExpanded( boolean expand )
    {
        expanded = expand;
        refreshHeader();
    }

    /** Puts the body in the window or the dock, per {@link #docked}. */
    private void placeBody()
    {
        if ( docked ) {
            windowHolder.getChildren().remove( body );
            if ( stage.isShowing() ) {
                stage.hide();
            }
            if ( body.getParent() != dockHolder ) {
                dockHolder.getChildren().setAll( body );
            }
            attachDock();
        }
        else {
            detachDock();
            dockHolder.getChildren().clear();
            if ( body.getParent() != windowHolder ) {
                windowHolder.getChildren().setAll( body );
            }
        }
        refreshHeader();
    }

    private void attachDock()
    {
        if ( mainScreenRoot instanceof ScaledRoot screen ) {
            screen.setDock( dockHolder );
        }
    }

    private void detachDock()
    {
        if ( dockHolder.getParent() instanceof ScaledRoot screen ) {
            screen.setDock( null );
        }
    }

    /** Shows the header controls for the current mode and sizes the dock. */
    private void refreshHeader()
    {
        boolean none = tabs.getTabs().isEmpty();
        // In its own window, the window's title already names the view.
        title.setVisible( docked );
        title.setManaged( docked );
        count.setText( String.valueOf( tabs.getTabs().size() ) );
        count.setVisible( docked && !none );
        count.setManaged( docked && !none );
        dockButton.setVisible( !docked );
        dockButton.setManaged( !docked );
        collapseButton.setVisible( docked );
        collapseButton.setManaged( docked );
        popOutButton.setVisible( docked );
        popOutButton.setManaged( docked );
        IconButtons.decorate( collapseButton, expanded ? LauncherIcons.ARROW_DOWN : LauncherIcons.ARROW_UP,
                              LocalizationManager.get( expanded ? "session.dock.collapse" : "session.dock.expand" ) );
        header.pseudoClassStateChanged( javafx.css.PseudoClass.getPseudoClass( "docked" ), docked );

        boolean showContent = !docked || expanded;
        content.setVisible( showContent );
        content.setManaged( showContent );
        // Docked with nothing running, there is nothing to dock: take no room at all.
        dockHolder.setVisible( docked && !none );
        dockHolder.requestLayout();
        if ( dockHolder.getParent() != null ) {
            dockHolder.getParent().requestLayout();
        }
    }

    private static boolean isInButton( Node node )
    {
        for ( Node n = node; n != null; n = n.getParent() ) {
            if ( n instanceof ButtonBase ) {
                return true;
            }
        }
        return false;
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
        refreshHeader();
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

    /**
     * The dock's container. Collapsed, it is as tall as its header. Expanded, it needs the header,
     * the tab strip and the selected game's pane at its minimum (so Stop and Kill always show),
     * and asks for a share of the main window's height; the main window's {@link ScaledRoot}
     * always grants the need, and the share when the screen above can spare it.
     */
    private final class DockHolder extends StackPane
    {
        @Override
        protected double computeMinHeight( double width )
        {
            double headerHeight = snappedTopInset() + header.prefHeight( width ) + snappedBottomInset();
            return expanded ? headerHeight + bodyNeed( width ) : headerHeight;
        }

        @Override
        protected double computePrefHeight( double width )
        {
            double need = computeMinHeight( width );
            if ( !expanded ) {
                return need;
            }
            Scene scene = getScene();
            double windowHeight = scene == null ? 0 : scene.getHeight() / UiScale.get();
            return Math.max( need, windowHeight * DOCK_SHARE );
        }

        /** The tab strip plus the selected pane's minimum. A TabPane reports a minimum of 0 and
         *  leaves its content out, so measure the parts. */
        /** The TabPane's tab strip, found once (a lookup walks the whole subtree, and this runs
         *  on every layout pass) and again only after the skin is replaced. */
        private Region cachedStrip;
        private Object cachedStripSkin;

        private Region tabStrip()
        {
            Object skin = tabs.getSkin();
            if ( skin == null ) {
                return null;
            }
            if ( cachedStrip == null || cachedStripSkin != skin ) {
                cachedStrip = tabs.lookup( ".tab-header-area" ) instanceof Region region ? region : null;
                cachedStripSkin = cachedStrip == null ? null : skin;
            }
            return cachedStrip;
        }

        private double bodyNeed( double width )
        {
            Tab selected = tabs.getSelectionModel().getSelectedItem();
            if ( selected == null || selected.getContent() == null ) {
                return DOCK_MIN_BODY;
            }
            Region strip = tabStrip();
            double stripHeight = strip != null ? strip.prefHeight( width ) : 0;
            return Math.max( DOCK_MIN_BODY, stripHeight + selected.getContent().minHeight( width ) );
        }
    }
}
