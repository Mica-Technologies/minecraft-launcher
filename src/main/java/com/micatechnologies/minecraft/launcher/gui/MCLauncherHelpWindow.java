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
import javafx.scene.image.Image;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import netscape.javascript.JSObject;

import java.io.InputStream;
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
            boolean firstShow = !helpStage.isShowing();
            helpStage.show();
            helpStage.toFront();

            // Re-apply DWM chrome attributes once the HWND is real. The applyTheme()
            // call inside buildStage() runs before the stage has a window handle, so
            // any DWM call there silently no-ops. On first show, the very first paint
            // happens with no Mica enabled — same white-flash problem the main stage
            // had — so we follow up with a full RedrawWindow to invalidate the client
            // area and force DWM to repaint with Mica active.
            String theme = ConfigManager.getTheme();
            boolean isNative = ConfigConstants.THEME_NATIVE.equals( theme );
            boolean lightChrome = ConfigConstants.THEME_LIGHT.equals( theme )
                                 || ( isNative && !isOsDark() );
            boolean dark = !lightChrome;
            com.micatechnologies.minecraft.launcher.utilities.WindowChromeManager
                    .applyTitleBarDarkMode( helpStage, dark );
            com.micatechnologies.minecraft.launcher.utilities.WindowChromeManager
                    .applyBackdrop( helpStage,
                            isNative
                              ? com.micatechnologies.minecraft.launcher.utilities.WindowChromeManager.BACKDROP_MICA
                              : com.micatechnologies.minecraft.launcher.utilities.WindowChromeManager.BACKDROP_NONE );
            if ( firstShow ) {
                com.micatechnologies.minecraft.launcher.utilities.WindowChromeManager
                        .forceFullRepaint( helpStage );
            }
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
        // StageStyle.UNIFIED is required for Mica to composite through the JavaFX scene
        // on Windows. Same reason the main stage uses it — DECORATED keeps an opaque
        // redirection bitmap so DwmSetWindowAttribute(SYSTEMBACKDROP_TYPE) is silently
        // dropped. UNIFIED falls back gracefully on macOS / Linux.
        helpStage.initStyle( StageStyle.UNIFIED );

        // Reuse the launcher app icon on the help window so the taskbar / window
        // chrome don't fall back to the generic JavaFX placeholder. Same resource
        // and same loading pattern MCLauncherGuiWindow uses for the main stage —
        // best-effort: if the resource is missing or unreadable we just log and
        // proceed with the default chrome.
        try ( InputStream iconStream = MCLauncherHelpWindow.class.getClassLoader()
                                                .getResourceAsStream( "micaminecraftlauncher.png" ) ) {
            if ( iconStream != null ) {
                helpStage.getIcons().add( new Image( iconStream ) );
            }
        }
        catch ( Exception e ) {
            Logger.logWarningSilent( "Unable to load help window icon: " + e.getMessage() );
        }

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

        // Sidebar header — uses the same heading scale + muted caption pattern as the
        // app's navbar so the help window reads as a part of the same shell.
        Label sidebarHeader = new Label( "Help Topics" );
        sidebarHeader.getStyleClass().addAll( "heading-h3", "helpSidebarHeader" );
        sidebarHeader.setPadding( new Insets( 14, 14, 8, 14 ) );

        VBox sidebar = new VBox( sidebarHeader, topicList );
        sidebar.getStyleClass().add( "stripPane" );
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
     * Applies the current launcher theme CSS to the help window's JavaFX sidebar. Loads
     * the legacy theme sheet first (for compatibility selectors), then the modern UI
     * base + tokens sheets so the sidebar styling reads as part of the same app shell
     * as the main launcher window.
     */
    private static void applyTheme()
    {
        if ( root == null ) return;
        root.getStylesheets().clear();

        // Legacy sheet (still defines some baseline selectors not yet ported)
        String legacy = resolveActiveThemeCss();
        if ( legacy != null ) {
            root.getStylesheets().add( legacy );
        }
        // Modern base sheet
        String base = resolveResourceUrl( "ui/ui-base.css" );
        if ( base != null && !base.isEmpty() ) {
            root.getStylesheets().add( base );
        }
        // Per-theme token sheet — defines the -color-* lookups used by ui-base.css.
        String tokens = resolveResourceUrl( resolveUiTokensPath() );
        if ( tokens != null && !tokens.isEmpty() ) {
            root.getStylesheets().add( tokens );
        }

        // Native theme: paint the help root + scene transparent so DWM Mica composites
        // through, the way the main stage does. Other themes get a solid bg via the
        // theme stylesheets (no inline override needed — the legacy / token rules win
        // their cascade since root has no inline style here).
        String activeTheme = ConfigManager.getTheme();
        boolean isNative = ConfigConstants.THEME_NATIVE.equals( activeTheme );
        if ( isNative ) {
            root.setStyle( "-fx-background-color: transparent;" );
            if ( helpStage != null && helpStage.getScene() != null ) {
                helpStage.getScene().setFill( javafx.scene.paint.Color.TRANSPARENT );
            }
        }
        else {
            root.setStyle( "" );
            if ( helpStage != null && helpStage.getScene() != null ) {
                // Default scene fill — the theme stylesheets paint the rootPane bg over
                // it; this is just the safety floor so an unstyled scene never flashes
                // white.
                helpStage.getScene().setFill( javafx.scene.paint.Color.web( "#0C1017" ) );
            }
        }

        // Match the title bar of the help stage to the app theme. Light themes (Light
        // and Native running over OS light mode) need the bright Windows chrome;
        // everything else uses the immersive-dark chrome.
        if ( helpStage != null ) {
            boolean lightChrome = ConfigConstants.THEME_LIGHT.equals( activeTheme )
                                 || ( isNative && !isOsDark() );
            com.micatechnologies.minecraft.launcher.utilities.WindowChromeManager
                    .applyTitleBarDarkMode( helpStage, !lightChrome );
            // Request / clear the Mica backdrop to match the active theme. The call
            // is harmless on non-Windows / older Win11 builds and silently no-ops
            // before show() if the HWND doesn't exist yet — show() re-applies it
            // after the window is real.
            com.micatechnologies.minecraft.launcher.utilities.WindowChromeManager
                    .applyBackdrop( helpStage,
                            isNative
                              ? com.micatechnologies.minecraft.launcher.utilities.WindowChromeManager.BACKDROP_MICA
                              : com.micatechnologies.minecraft.launcher.utilities.WindowChromeManager.BACKDROP_NONE );
        }
    }

    /** Cheap wrapper around OsThemeDetector for places that need the OS state but
     *  shouldn't blow up if the detector can't init on a weird platform. */
    private static boolean isOsDark()
    {
        try {
            return com.jthemedetecor.OsThemeDetector.getDetector().isDark();
        }
        catch ( Throwable ignored ) {
            return true;
        }
    }

    /** Returns the ui-tokens-*.css resource path for the active launcher theme.
     *  Native theme picks its dark or light variant based on OS dark/light state. */
    private static String resolveUiTokensPath()
    {
        String theme = ConfigManager.getTheme();
        if ( ConfigConstants.THEME_NATIVE.equals( theme ) ) {
            boolean osDark = true;
            try {
                osDark = com.jthemedetecor.OsThemeDetector.getDetector().isDark();
            }
            catch ( Throwable ignored ) { /* fall through with dark default */ }
            return osDark ? "ui/ui-tokens-native.css" : "ui/ui-tokens-native-light.css";
        }
        return switch ( theme ) {
            case ConfigConstants.THEME_LIGHT         -> "ui/ui-tokens-light.css";
            case ConfigConstants.THEME_BLUE_GRAY     -> "ui/ui-tokens-bluegray.css";
            case ConfigConstants.THEME_ORANGE_PURPLE -> "ui/ui-tokens-orangepurple.css";
            case ConfigConstants.THEME_CREEPER       -> "ui/ui-tokens-creeper.css";
            default                                  -> "ui/ui-tokens-dark.css";
        };
    }

    /**
     * Returns the help-specific theme CSS resource path based on the current launcher
     * theme. Themes that follow the OS (Automatic, Native) pick their light or dark
     * companion based on the OS dark/light state — otherwise the help WebView content
     * stayed dark even when the rest of the app was light.
     */
    private static String resolveThemeCssPath()
    {
        String theme = ConfigManager.getTheme();
        if ( ConfigConstants.THEME_NATIVE.equals( theme ) ) {
            return isOsDark() ? "help/help-native.css" : "help/help-light.css";
        }
        if ( ConfigConstants.THEME_AUTOMATIC.equals( theme ) ) {
            return isOsDark() ? "help/help-dark.css" : "help/help-light.css";
        }
        return switch ( theme ) {
            case ConfigConstants.THEME_LIGHT         -> "help/help-light.css";
            case ConfigConstants.THEME_BLUE_GRAY     -> "help/help-bluegray.css";
            case ConfigConstants.THEME_ORANGE_PURPLE -> "help/help-orangepurple.css";
            case ConfigConstants.THEME_CREEPER       -> "help/help-creeper.css";
            default                                  -> "help/help-dark.css";
        };
    }

    /**
     * Returns the launcher's main theme CSS URL for the JavaFX sidebar styling.
     */
    private static String resolveActiveThemeCss()
    {
        String theme = ConfigManager.getTheme();
        String cssName;
        if ( ConfigConstants.THEME_NATIVE.equals( theme ) ) {
            // Native follows OS dark/light: pair the matching legacy sheet so list-cell /
            // scroll-bar / etc. baseline rules carry the right palette.
            boolean osDark = true;
            try {
                osDark = com.jthemedetecor.OsThemeDetector.getDetector().isDark();
            }
            catch ( Throwable ignored ) { /* fall through with dark default */ }
            cssName = osDark ? "guiStyle-dark.css" : "guiStyle-light.css";
        }
        else {
            cssName = switch ( theme ) {
                case ConfigConstants.THEME_LIGHT         -> "guiStyle-light.css";
                case ConfigConstants.THEME_BLUE_GRAY     -> "guiStyle-bluegray.css";
                case ConfigConstants.THEME_ORANGE_PURPLE -> "guiStyle-orangepurple.css";
                // Creeper has no legacy companion; fall back to dark.
                case ConfigConstants.THEME_CREEPER       -> "guiStyle-dark.css";
                default                                  -> "guiStyle-dark.css";
            };
        }
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
