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
import com.micatechnologies.minecraft.launcher.consts.ConfigConstants;
import com.micatechnologies.minecraft.launcher.files.Logger;
import javafx.collections.FXCollections;
import javafx.concurrent.Worker;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import netscape.javascript.JSObject;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Singleton help window that displays context-sensitive HTML help content. The window contains a topic sidebar
 * (ListView) on the left and a WebView content area on the right. The content is themed to match the launcher's
 * current theme.
 *
 * @author Mica Technologies
 * @version 1.0
 * @since 2.0
 */
public class MCLauncherHelpWindow
{
    private static Stage helpStage = null;
    private static WebView webView = null;
    private static WebEngine webEngine = null;
    private static ListView< HelpTopic > topicList = null;
    private static BorderPane root = null;
    private static HelpTopic currentTopic = null;

    /**
     * Shows the help window and navigates to the specified topic. If the window is already open, it is brought to
     * front and the topic is loaded.
     *
     * @param topic the help topic to display
     */
    public static void show( HelpTopic topic )
    {
        GUIUtilities.JFXPlatformRun( () -> {
            if ( helpStage == null ) {
                buildStage();
            }
            loadTopic( topic );
            helpStage.show();
            helpStage.toFront();
        } );
    }

    /**
     * Hides the help window if it is showing.
     */
    public static void hide()
    {
        if ( helpStage != null ) {
            GUIUtilities.JFXPlatformRun( () -> helpStage.hide() );
        }
    }

    /**
     * Refreshes the help window theme to match the current launcher theme. Call when the launcher theme changes.
     */
    public static void refreshTheme()
    {
        if ( helpStage == null || root == null ) {
            return;
        }
        GUIUtilities.JFXPlatformRun( () -> {
            applyTheme();
            if ( currentTopic != null ) {
                loadTopic( currentTopic );
            }
        } );
    }

    /**
     * Builds the help Stage, WebView, and sidebar layout. Called lazily on first show.
     */
    private static void buildStage()
    {
        helpStage = new Stage();
        helpStage.setTitle( "Mica Minecraft Launcher Help" );
        helpStage.initStyle( StageStyle.DECORATED );

        // Set owner so help stays above launcher
        Stage owner = MCLauncherGuiController.getTopStageOrNull();
        if ( owner != null ) {
            helpStage.initOwner( owner );
        }

        // Topic sidebar
        topicList = new ListView<>( FXCollections.observableArrayList( HelpTopic.values() ) );
        topicList.setPrefWidth( 180 );
        topicList.setMinWidth( 160 );
        topicList.setCellFactory( lv -> new ListCell< HelpTopic >()
        {
            @Override
            protected void updateItem( HelpTopic item, boolean empty )
            {
                super.updateItem( item, empty );
                setText( empty || item == null ? null : item.getDisplayName() );
            }
        } );
        topicList.getSelectionModel().selectedItemProperty().addListener( ( obs, old, selected ) -> {
            if ( selected != null ) {
                loadTopic( selected );
            }
        } );

        // Sidebar header
        Label sidebarHeader = new Label( "Help Topics" );
        sidebarHeader.getStyleClass().add( "helpSidebarHeader" );
        sidebarHeader.setStyle( "-fx-font-size: 14; -fx-font-weight: bold; -fx-padding: 10 10 6 10;" );

        VBox sidebar = new VBox( sidebarHeader, topicList );
        VBox.setVgrow( topicList, javafx.scene.layout.Priority.ALWAYS );

        // Content area
        webView = new WebView();
        webEngine = webView.getEngine();
        webView.setContextMenuEnabled( false );

        // Intercept internal help links via JavaScript bridge.
        // The locationProperty approach doesn't work with loadContent() since WebView can't
        // navigate to help:// URLs (not a real protocol). Instead, we inject JS after each
        // page load that catches link clicks and calls back into Java.
        webEngine.getLoadWorker().stateProperty().addListener( ( obs, oldState, newState ) -> {
            if ( newState == Worker.State.SUCCEEDED ) {
                // Expose Java callback to JavaScript (use static field to prevent GC)
                JSObject window = ( JSObject ) webEngine.executeScript( "window" );
                window.setMember( "helpBridge", helpBridge );

                // Intercept all help:// link clicks. Use getAttribute('href') instead of
                // the .href DOM property because the browser resolves .href relative to the
                // base URL (about:blank when using loadContent), which mangles the help:// scheme.
                webEngine.executeScript(
                        "document.addEventListener('click', function(e) {" +
                                "  var target = e.target;" +
                                "  while (target && target.tagName !== 'A') target = target.parentElement;" +
                                "  if (target) {" +
                                "    var raw = target.getAttribute('href');" +
                                "    if (raw && raw.indexOf('help://topic/') === 0) {" +
                                "      e.preventDefault();" +
                                "      e.stopPropagation();" +
                                "      var topic = raw.substring(13);" +
                                "      window.helpBridge.navigateTo(topic);" +
                                "    }" +
                                "  }" +
                                "});"
                );
            }
        } );

        // Layout
        root = new BorderPane();
        root.setLeft( sidebar );
        root.setCenter( webView );
        root.getStyleClass().add( "helpRoot" );

        Scene scene = new Scene( root, 800, 600 );
        helpStage.setScene( scene );
        helpStage.setMinWidth( 600 );
        helpStage.setMinHeight( 400 );

        applyTheme();
    }

    /**
     * Loads the specified help topic into the WebView.
     */
    private static void loadTopic( HelpTopic topic )
    {
        currentTopic = topic;

        // Select in sidebar without firing listener loop
        if ( topicList.getSelectionModel().getSelectedItem() != topic ) {
            topicList.getSelectionModel().select( topic );
        }

        try {
            URL contentUrl = MCLauncherHelpWindow.class.getClassLoader().getResource( topic.getResourcePath() );
            if ( contentUrl != null ) {
                String html = new String(
                        Objects.requireNonNull( MCLauncherHelpWindow.class.getClassLoader()
                                                                          .getResourceAsStream( topic.getResourcePath() )
                        ).readAllBytes(), StandardCharsets.UTF_8 );

                // Resolve CSS URLs
                String baseCssUrl = resolveResourceUrl( "help/help-style.css" );
                String themeCssUrl = resolveResourceUrl( resolveThemeCssPath() );

                // Wrap content with CSS links
                String fullHtml = "<!DOCTYPE html><html><head><meta charset=\"UTF-8\">" +
                        "<link rel=\"stylesheet\" href=\"" + baseCssUrl + "\">" +
                        "<link rel=\"stylesheet\" href=\"" + themeCssUrl + "\">" +
                        "</head><body>" + html + "</body></html>";

                webEngine.loadContent( fullHtml );
            }
            else {
                webEngine.loadContent(
                        "<html><body style='font-family:sans-serif;padding:20px;color:#E6E1E5;background:#1C1B1F;'>" +
                                "<h2>Help topic not available</h2>" +
                                "<p>The help content for \"" + topic.getDisplayName() +
                                "\" has not been created yet.</p></body></html>" );
            }
        }
        catch ( Exception e ) {
            Logger.logWarningSilent( "Failed to load help topic: " + topic.getDisplayName() );
            webEngine.loadContent( "<html><body><p>Error loading help content.</p></body></html>" );
        }
    }

    /**
     * Applies the current launcher theme CSS to the help window's JavaFX sidebar.
     */
    private static void applyTheme()
    {
        if ( root == null ) return;
        String themeCss = resolveActiveThemeCss();
        root.getStylesheets().clear();
        if ( themeCss != null ) {
            root.getStylesheets().add( themeCss );
        }
    }

    /**
     * Returns the help-specific theme CSS resource path based on the current launcher theme.
     */
    private static String resolveThemeCssPath()
    {
        String theme = ConfigManager.getTheme();
        return switch ( theme ) {
            case ConfigConstants.THEME_LIGHT -> "help/help-light.css";
            case ConfigConstants.THEME_BLUE_GRAY -> "help/help-bluegray.css";
            case ConfigConstants.THEME_ORANGE_PURPLE -> "help/help-orangepurple.css";
            default -> "help/help-dark.css";
        };
    }

    /**
     * Returns the launcher's main theme CSS URL for the JavaFX sidebar styling.
     */
    private static String resolveActiveThemeCss()
    {
        String theme = ConfigManager.getTheme();
        String cssName = switch ( theme ) {
            case ConfigConstants.THEME_LIGHT -> "guiStyle-light.css";
            case ConfigConstants.THEME_BLUE_GRAY -> "guiStyle-bluegray.css";
            case ConfigConstants.THEME_ORANGE_PURPLE -> "guiStyle-orangepurple.css";
            default -> "guiStyle-dark.css";
        };
        URL url = MCLauncherHelpWindow.class.getClassLoader().getResource( cssName );
        return url != null ? url.toExternalForm() : null;
    }

    /**
     * Resolves a classpath resource to a URL string.
     */
    private static String resolveResourceUrl( String path )
    {
        URL url = MCLauncherHelpWindow.class.getClassLoader().getResource( path );
        return url != null ? url.toExternalForm() : "";
    }

    /**
     * JavaScript-to-Java bridge for intercepting help topic link clicks in the WebView. Must be public for the
     * JavaScript engine to access it. The instance is stored as a field to prevent garbage collection.
     */
    @SuppressWarnings( "unused" ) // Called from JavaScript
    public static class HelpBridge
    {
        /**
         * Called from JavaScript when a help://topic/ link is clicked.
         *
         * @param topicName the topic name from the URL (e.g. "SETTINGS" or "MAIN_SCREEN")
         */
        public void navigateTo( String topicName )
        {
            String normalized = topicName.toUpperCase().replace( "-", "_" );
            try {
                HelpTopic linked = HelpTopic.valueOf( normalized );
                GUIUtilities.JFXPlatformRun( () -> {
                    topicList.getSelectionModel().select( linked );
                    loadTopic( linked );
                } );
            }
            catch ( IllegalArgumentException ignored ) {
                Logger.logWarningSilent( "Unknown help topic link: " + topicName );
            }
        }
    }

    /**
     * Prevent the HelpBridge from being garbage collected (JavaScript weak references).
     */
    private static HelpBridge helpBridge = new HelpBridge();
}
