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
import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
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
            // One OS dark/light read for the whole pass so the root, sidebar, content sheet, and
            // chrome all agree (the detector can otherwise flip between back-to-back calls).
            refreshOsDarkCache();
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
     * Tears down the help window's static state so the next launcher session
     * (after a restartApp) builds a fresh Stage / WebView / sidebar rather
     * than reusing the leftover ones from the previous session. Called from
     * {@link com.micatechnologies.minecraft.launcher.LauncherCore#cleanupApp()}
     * during shutdown / restart. Idempotent — calling on a never-shown
     * window or after a previous teardown is a no-op.
     *
     * <p>Without this, the static fields ({@link #helpStage}, {@link #webView},
     * {@link #webEngine}, {@link #topicList}, {@link #root}) survive the
     * {@code while(restartFlag)} loop in {@code LauncherCore.main}, so a
     * post-restart {@link #show} hits the {@code helpStage != null} fast
     * path in {@link #buildStage} and renders the previous session's
     * Stage — potentially with a stale theme, an Owner stage that no
     * longer exists, and a WebView whose internal state was set up against
     * a now-closed launcher window.
     */
    public static void cleanup()
    {
        if ( helpStage == null ) {
            return;
        }
        GUIUtilities.JFXPlatformRun( () -> {
            try {
                if ( helpStage != null ) {
                    if ( helpStage.isShowing() ) {
                        helpStage.hide();
                    }
                    helpStage.close();
                }
            }
            catch ( Throwable t ) {
                Logger.logWarningSilent( "Help window cleanup: stage close", t );
            }
            // Also clear the WebView page so any in-flight JS / pending
            // navigations stop trying to call back into the about-to-die
            // bridge.
            try {
                if ( webEngine != null ) {
                    webEngine.loadContent( "" );
                }
            }
            catch ( Throwable t ) {
                Logger.logWarningSilent( "Help window cleanup: webEngine clear", t );
            }
            helpStage = null;
            webView = null;
            webEngine = null;
            topicList = null;
            root = null;
            currentTopic = null;
        } );
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
            // One OS dark/light read for the whole pass (see refreshOsDarkCache).
            refreshOsDarkCache();
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
        helpStage.setTitle( LocalizationManager.get( "window.help.title" ) );
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

        // Make the WebView's webkit canvas transparent so the HTML content composites
        // over the BorderPane's `.helpRoot` background (`-color-bg`) instead of WebKit's
        // default opaque-white fill. With this in place, every theme's help content
        // area paints the theme bg from a single source (the JFX root), and on the
        // Native macOS theme the content area composites over the NSVisualEffectView
        // backdrop just like the sidebar does — no more solid dark rectangle in the
        // middle of an otherwise-vibrant window. Body bg in help-style.css must also
        // be set to `transparent` for the transparency to propagate through the HTML.
        // Reflective because JavaFX exposes no public WebView transparency API and
        // WebPage lives in the non-exported com.sun.webkit package; the launcher's
        // VM args pass --add-opens javafx.web/javafx.scene.web=ALL-UNNAMED so the
        // setAccessible(true) on the private `page` field works on JDK 17+.
        makeWebViewTransparent( webView );

        // Wheel scrolling on the WebView matches the rest of the app's smooth-scroll
        // surfaces (modpack list, library) — exponential chase toward a target
        // scrollY rather than JavaFX's default discrete-tick behavior. See
        // SmoothScroll.install(WebView) for the per-frame math and per-event
        // re-anchor logic.
        SmoothScroll.install( webView );

        // Intercept internal help links via JavaScript bridge.
        // The locationProperty approach doesn't work with loadContent() since WebView can't
        // navigate to help:// URLs (not a real protocol). Instead, we inject JS after each
        // page load that catches link clicks and calls back into Java.
        //
        // Capture the engine in a final local so the listener doesn't read the static
        // `webEngine` field at fire time. cleanup() nulls the static, but in-flight load
        // workers (including the empty-content clear that cleanup itself triggers) still
        // fire SUCCEEDED on the FX thread afterwards — if the listener reads `webEngine`
        // then, it NPEs. Also guard against the static having been replaced by a later
        // buildStage() so an old engine's listener doesn't script the new engine.
        final WebEngine engineRef = webEngine;
        engineRef.getLoadWorker().stateProperty().addListener( ( obs, oldState, newState ) -> {
            if ( newState != Worker.State.SUCCEEDED ) {
                return;
            }
            if ( webEngine != engineRef ) {
                return;
            }
            // Expose Java callback to JavaScript (use static field to prevent GC)
            JSObject window = ( JSObject ) engineRef.executeScript( "window" );
            window.setMember( "helpBridge", helpBridge );

            // Intercept all help:// link clicks. Use getAttribute('href') instead of
            // the .href DOM property because the browser resolves .href relative to the
            // base URL (about:blank when using loadContent), which mangles the help:// scheme.
            engineRef.executeScript(
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

                // Inline the CSS text rather than <link href="file:..."> them. A page loaded via
                // loadContent() has a null / about:blank origin, and WebKit (JavaFX 26) blocks that
                // origin from pulling in file: stylesheet resources — so the linked sheets silently
                // never applied and the content fell back to unstyled black-on-(dark root) default
                // rendering (the help-window readability bug). Embedding the sheet text makes the
                // content fully self-contained and theme-correct on every theme.
                String baseCss = readResourceText( "help/help-style.css" );
                String themeCss = readResourceText( resolveThemeCssPath() );
                String fullHtml = "<!DOCTYPE html><html><head><meta charset=\"UTF-8\">" +
                        "<style>" + baseCss + "\n" + themeCss + "</style>" +
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

        // Native theme handling. On macOS the help root + scene stay transparent so the
        // NSVisualEffectView vibrancy (installed below) frosts through. On Windows / Linux the
        // help WebView can't composite over DWM Mica, so a transparent root leaves the content on
        // an unreliable backdrop — e.g. the native-LIGHT help sheet's near-black text landing on a
        // dark surface (the "black text on dark" the user hit). Give it a SOLID surface matching
        // the native light/dark state (and the help content CSS's own --color-bg) so the text reads
        // correctly, exactly like every non-native theme. Other themes get their solid bg via the
        // theme stylesheets (no inline override needed — the legacy / token rules win their cascade).
        String activeTheme = ConfigManager.getTheme();
        boolean isNative = ConfigConstants.THEME_NATIVE.equals( activeTheme );
        if ( isNative ) {
            if ( org.apache.commons.lang3.SystemUtils.IS_OS_MAC ) {
                root.setStyle( "-fx-background-color: transparent;" );
                if ( helpStage != null && helpStage.getScene() != null ) {
                    helpStage.getScene().setFill( javafx.scene.paint.Color.TRANSPARENT );
                }
            }
            else {
                // #0C1017 mirrors help-native.css --color-bg (dark, light text); #FFFFFF mirrors
                // help-light.css --color-bg (light, dark text) used for native over OS light.
                String nativeBg = isOsDark() ? "#0C1017" : "#FFFFFF";
                root.setStyle( "-fx-background-color: " + nativeBg + ";" );
                if ( helpStage != null && helpStage.getScene() != null ) {
                    helpStage.getScene().setFill( javafx.scene.paint.Color.web( nativeBg ) );
                }
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

        // macOS Native: install the NSVisualEffectView vibrancy on the help stage so
        // the transparent JavaFX scene + sidebar actually composite over a frosted
        // backdrop instead of the NSWindow's default opaque black contentView. Without
        // this, Native theme made the help sidebar render as a solid black strip —
        // the "pitch black sidebar" the user reported. On non-Native themes the
        // contentView is opaque (theme bg via CSS), so we clear any prior vibrancy.
        // No-op on non-macOS.
        if ( helpStage != null ) {
            if ( isNative ) {
                com.micatechnologies.minecraft.launcher.utilities.MacOsVibrancyManager
                        .apply( helpStage, isOsDark() );
            }
            else {
                com.micatechnologies.minecraft.launcher.utilities.MacOsVibrancyManager
                        .clear( helpStage );
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

    /** Reads a classpath text resource (e.g. a help CSS sheet) into a String for inlining, or "" if
     *  it can't be read. Used to embed the help stylesheets directly into the WebView document — see
     *  {@link #loadTopic} for why linking them as file: resources doesn't work under loadContent(). */
    private static String readResourceText( String resourcePath )
    {
        try ( InputStream in = MCLauncherHelpWindow.class.getClassLoader()
                                                          .getResourceAsStream( resourcePath ) ) {
            if ( in == null ) {
                Logger.logWarningSilent( "Help: CSS resource not found: " + resourcePath );
                return "";
            }
            return new String( in.readAllBytes(), StandardCharsets.UTF_8 );
        }
        catch ( Exception e ) {
            Logger.logWarningSilent( "Help: could not read CSS " + resourcePath + ": " + e.getMessage() );
            return "";
        }
    }

    /** Cached OS dark/light state for the current show / refresh pass. The jSystemThemeDetector can
     *  return inconsistent values across rapid back-to-back calls; reading it once per pass keeps
     *  every theming decision (root bg, sidebar tokens, content CSS, title-bar chrome) in agreement.
     *  Otherwise the window splits — e.g. a dark root paired with the light content sheet, giving
     *  the near-black-on-dark help text the user reported. */
    private static Boolean osDarkCache = null;

    /** Re-reads + caches the OS dark/light state. Called once at the start of each show / refresh so
     *  the whole pass agrees on one value. */
    private static void refreshOsDarkCache()
    {
        osDarkCache = com.micatechnologies.minecraft.launcher.utilities.OsThemeUtilities.isOsDark();
    }

    /** OS dark/light for this pass — the value cached by {@link #refreshOsDarkCache()} (or a fresh
     *  read if nothing's cached yet). Use this everywhere instead of calling the detector directly,
     *  so a single pass never disagrees with itself. */
    private static boolean isOsDark()
    {
        if ( osDarkCache == null ) {
            refreshOsDarkCache();
        }
        return osDarkCache;
    }

    /** Returns the ui-tokens-*.css resource path for the active launcher theme.
     *  Native theme picks its dark or light variant based on OS dark/light state. */
    private static String resolveUiTokensPath()
    {
        String theme = ConfigManager.getTheme();
        if ( ConfigConstants.THEME_NATIVE.equals( theme ) ) {
            return isOsDark() ? "ui/ui-tokens-native.css" : "ui/ui-tokens-native-light.css";
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
            cssName = isOsDark() ? "guiStyle-dark.css" : "guiStyle-light.css";
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

    /**
     * Switches a WebView's underlying WebKit canvas to a fully-transparent fill so its
     * HTML content composites over the JavaFX parent's background instead of WebKit's
     * default opaque-white. See the explanatory comment at the call site in
     * {@link #buildStage()} for rationale.
     *
     * <p>Implementation: reach into {@code WebEngine.page} (private field) and invoke
     * {@code com.sun.webkit.WebPage#setBackgroundColor(int)} with ARGB=0x00000000.
     * Both accesses are gated by --add-opens javafx.web/javafx.scene.web=ALL-UNNAMED.
     * Best-effort: failure (missing flag, JFX internal rename, future API change)
     * logs silently and leaves the WebView opaque — the user just sees the old solid
     * fill, not a crash.
     */
    private static void makeWebViewTransparent( WebView wv )
    {
        try {
            java.lang.reflect.Field pageField = wv.getEngine().getClass()
                                                   .getDeclaredField( "page" );
            pageField.setAccessible( true );
            Object page = pageField.get( wv.getEngine() );
            if ( page != null ) {
                page.getClass()
                    .getMethod( "setBackgroundColor", int.class )
                    .invoke( page, 0 );
            }
        }
        catch ( Throwable t ) {
            Logger.logWarningSilent(
                    "Help WebView transparency setup failed (" + t.getClass().getSimpleName()
                            + "): " + t.getMessage() );
        }
    }
}
