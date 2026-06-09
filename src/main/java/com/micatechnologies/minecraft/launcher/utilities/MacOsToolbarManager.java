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

package com.micatechnologies.minecraft.launcher.utilities;

import com.micatechnologies.minecraft.launcher.consts.GUIConstants;
import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.game.auth.MCLauncherAuthManager;
import com.micatechnologies.minecraft.launcher.rgb.RgbColor;
import com.micatechnologies.minecraft.launcher.rgb.ThemeAccentColors;
import com.pixelduke.window.WindowUtils;
import com.sun.jna.Callback;
import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;
import de.jangassen.jfa.foundation.Foundation;
import de.jangassen.jfa.foundation.ID;
import javafx.application.Platform;
import javafx.event.EventHandler;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import org.apache.commons.lang3.SystemUtils;

import java.util.Set;

/**
 * macOS-only native title-bar toolbar. Attaching an {@code NSToolbar} does two things at once:
 * it grows the title bar to the unified height (which re-centers the traffic lights and title
 * vertically), and it lets us host the launcher's primary navigation — Browse / Settings / Help
 * — as real {@code NSToolbarItem} buttons sitting natively in the title bar next to the traffic
 * lights, the way a Mac user expects.
 *
 * <p><b>Why native items, not the JavaFX navbar?</b> {@code NSWindowStyleMaskFullSizeContentView}
 * puts the JavaFX scene under the title bar, but a toolbar lays its own views over that band and
 * consumes the clicks there — so JavaFX buttons in the navbar's top row become unclickable. The
 * fix is to put the interactive controls in the toolbar (native, clickable) and hide the now
 * redundant JavaFX ones via {@link #hideReplacedControls}.</p>
 *
 * <p><b>Theming.</b> The item icons are SF Symbols. We attempt to tint them with the launcher's
 * current theme accent (via {@code NSImageSymbolConfiguration}); if that fails or the OS is too
 * old, the plain symbol is used, which already adapts to the system light/dark appearance — so
 * it still "fits" either way.</p>
 *
 * <p><b>Elegant fallback.</b> Every step is guarded and {@link #install} returns whether it fully
 * succeeded. The JavaFX navbar buttons are hidden ONLY when {@link #isInstalled()} is true, so if
 * any native step fails the launcher transparently keeps its normal in-window navbar (and, with no
 * toolbar attached, the title bar stays short and those buttons stay clickable). Nothing here ever
 * propagates a failure to the caller.</p>
 *
 * <p>The delegate is a small Obj-C class built once at runtime via JFA
 * ({@code objc_allocateClassPair} + {@code class_addMethod}), the same technique
 * {@code jSystemThemeDetector} uses for its notification observer. The JNA {@link Callback}
 * instances are retained for the JVM's lifetime so their native trampolines aren't collected, and
 * the delegate object is never released (NSToolbar holds its delegate weakly).</p>
 *
 * @author Mica Technologies
 * @since 2026.2
 */
public final class MacOsToolbarManager
{
    /** {@code NSWindowToolbarStyleUnified} (macOS 11+) — merges the toolbar into the title-bar row. */
    private static final long NS_WINDOW_TOOLBAR_STYLE_UNIFIED = 3L;

    private static final String ID_FLEX     = "NSToolbarFlexibleSpaceItem";
    private static final String ID_SPACE    = "NSToolbarSpaceItem";
    private static final String ID_BROWSE   = "MicaBrowse";
    private static final String ID_SETTINGS = "MicaSettings";
    private static final String ID_HELP     = "MicaHelp";
    private static final String ID_UPDATE   = "MicaUpdate";
    private static final String ID_ACCOUNT  = "MicaAccount";

    private static volatile boolean installed   = false;
    private static volatile boolean classBuilt  = false;
    private static volatile ID      delegateRef  = null;
    /** The live account item, kept so the async avatar load can swap its image in. */
    private static volatile ID      accountItemRef = null;

    // "Update available" state. The native update item is only vended (and only included in the
    // toolbar's identifier list) while updateAvailable is true — the macOS counterpart of the
    // JavaFX navbar's update glyph, set by UpdateCheckManager when a newer release is found. The
    // state is stored statically so it survives toolbar re-installs (every WINDOW_SHOWN / game
    // return rebuilds the toolbar and re-reads it).
    private static volatile boolean updateAvailable = false;
    private static volatile String  updateUrl       = null;
    private static volatile Stage   toolbarStage    = null;

    // Retained for the JVM lifetime — if these Callback instances are GC'd, the native method
    // trampolines they back are freed and the next delegate call crashes the process.
    private static Callback identifiersCallback;
    private static Callback itemForIdentifierCallback;
    private static Callback actionCallback;

    private MacOsToolbarManager() { /* static-only */ }

    /** @return true once the native toolbar has been attached successfully. */
    public static boolean isInstalled()
    {
        return installed;
    }

    /**
     * Marks (or clears) the "update available" state. When set true while the toolbar is installed,
     * a native download item appears in the title bar — the macOS counterpart of the JavaFX navbar's
     * update glyph; clicking it opens the same download prompt. No-op off macOS / when the toolbar
     * never installed (the caller then falls back to the JavaFX glyph).
     *
     * @param available     whether a newer launcher release is available
     * @param latestUrl     the release download URL to open on click (may be null when clearing)
     * @param stage         the owning stage (for the toolbar window + the click dialog)
     *
     * @since 2026.6
     */
    public static void setUpdateAvailable( boolean available, String latestUrl, Stage stage )
    {
        if ( !SystemUtils.IS_OS_MAC ) {
            return;
        }
        updateAvailable = available;
        updateUrl = latestUrl;
        if ( stage != null ) {
            toolbarStage = stage;
        }
        // Rebuild the toolbar so the item list picks up (or drops) the update item. install() is
        // idempotent and re-reads updateAvailable when it vends identifiers.
        if ( installed && toolbarStage != null ) {
            final Stage s = toolbarStage;
            Platform.runLater( () -> install( s ) );
        }
    }

    /**
     * Attaches the native toolbar to {@code stage}'s window. No-op off macOS / null stage.
     * Deferred until the NSWindow is realized. Fully guarded: on any failure the toolbar simply
     * isn't attached and {@link #isInstalled()} stays false, leaving the JavaFX navbar in charge.
     *
     * <p><b>Re-installable (this is the blank-button fix).</b> Each call builds a fresh
     * {@code NSToolbar} and {@code setToolbar:}s it, replacing any prior one — which makes AppKit
     * re-vend the items, so their SF Symbol images are rebuilt fresh. The launcher calls this on
     * <em>every</em> {@code WINDOW_SHOWN} (via {@code MCLauncherGuiWindow}'s persistent handler),
     * so returning from a game — which hides then re-shows the stage and can rebuild the NSWindow
     * peer, the path that previously left the items blank — now rebuilds the toolbar instead of
     * leaving a stale one behind.</p>
     *
     * @since 2026.2
     */
    public static void install( Stage stage )
    {
        if ( !SystemUtils.IS_OS_MAC || stage == null ) {
            return;
        }
        // Remember the stage so setUpdateAvailable can rebuild the toolbar when update state flips.
        toolbarStage = stage;
        if ( !stage.isShowing() ) {
            EventHandler< WindowEvent > onShown = new EventHandler< WindowEvent >()
            {
                @Override
                public void handle( WindowEvent event )
                {
                    stage.removeEventHandler( WindowEvent.WINDOW_SHOWN, this );
                    Platform.runLater( () -> install( stage ) );
                }
            };
            stage.addEventHandler( WindowEvent.WINDOW_SHOWN, onShown );
            return;
        }
        try {
            NativeLong handle = WindowUtils.getNativeHandleOfStageAsNativeLong( stage );
            if ( handle == null || handle.longValue() == 0 ) {
                return;
            }
            ID nsWindow = new ID( handle.longValue() );

            ensureDelegateClass();
            if ( delegateRef == null || ID.NIL.equals( delegateRef ) ) {
                return;
            }

            ID toolbar = Foundation.invoke( Foundation.invoke( "NSToolbar", "alloc" ),
                                            "initWithIdentifier:",
                                            Foundation.nsString( "MicaTitleBarToolbar" ) );
            if ( Foundation.isNil( toolbar ) ) {
                return;
            }
            Foundation.invoke( toolbar, "setDelegate:", delegateRef );
            Foundation.invoke( toolbar, "setAllowsUserCustomization:", false );
            Foundation.invoke( toolbar, "setShowsBaselineSeparator:", false );
            // Small size mode shrinks the items a touch so the icon+label pair centers with more
            // breathing room above and below — keeps the labels off the title-bar/content seam.
            // (NSToolbarSizeModeSmall = 2; the toolbar exposes no finer per-item vertical offset.)
            Foundation.invoke( toolbar, "setSizeMode:", 2L );

            Foundation.invoke( nsWindow, "setToolbar:", toolbar );
            Foundation.invoke( nsWindow, "setToolbarStyle:", NS_WINDOW_TOOLBAR_STYLE_UNIFIED );

            installed = true;
            Logger.logDebug( "MacOsToolbar: native toolbar attached" );

            // The first scene was set before the toolbar existed, so re-run the hide on it now
            // that isInstalled() is true (subsequent scene swaps hit hideReplacedControls directly).
            if ( stage.getScene() != null ) {
                hideReplacedControls( stage.getScene().getRoot() );
            }
        }
        catch ( Throwable t ) {
            installed = false;
            Logger.logWarningSilent( "MacOsToolbar: install failed — "
                                             + t.getClass().getSimpleName() + ": " + t.getMessage() );
        }
    }

    /** Builds + registers the Obj-C delegate class once and instantiates the singleton delegate. */
    private static synchronized void ensureDelegateClass()
    {
        if ( classBuilt ) {
            return;
        }
        classBuilt = true;
        try {
            ID cls = Foundation.allocateObjcClassPair( Foundation.getObjcClass( "NSObject" ),
                                                       "MicaToolbarDelegate" );
            if ( ID.NIL.equals( cls ) ) {
                return;
            }

            // toolbarDefaultItemIdentifiers: / toolbarAllowedItemIdentifiers: → NSArray.
            identifiersCallback = new IdentifiersCallback();
            // toolbar:itemForItemIdentifier:willBeInsertedIntoToolbar: → NSToolbarItem.
            itemForIdentifierCallback = new ItemForIdentifierCallback();
            // onMicaToolbarItem: → void (the item action).
            actionCallback = new ActionCallback();

            Foundation.addMethod( cls, Foundation.createSelector( "toolbarDefaultItemIdentifiers:" ),
                                  identifiersCallback, "@@:@" );
            Foundation.addMethod( cls, Foundation.createSelector( "toolbarAllowedItemIdentifiers:" ),
                                  identifiersCallback, "@@:@" );
            Foundation.addMethod( cls,
                                  Foundation.createSelector( "toolbar:itemForItemIdentifier:willBeInsertedIntoToolbar:" ),
                                  itemForIdentifierCallback, "@@:@@c" );
            Foundation.addMethod( cls, Foundation.createSelector( "onMicaToolbarItem:" ),
                                  actionCallback, "v@:@" );

            Foundation.registerObjcClassPair( cls );

            // +1 retained and never released — NSToolbar keeps only a weak reference to its delegate.
            delegateRef = Foundation.invoke( "MicaToolbarDelegate", "new" );
        }
        catch ( Throwable t ) {
            delegateRef = null;
            Logger.logWarningSilent( "MacOsToolbar: delegate class build failed — " + t.getMessage() );
        }
    }

    // =========================================================================================
    //  Delegate callbacks. Signatures mirror the Obj-C IMP (self, _cmd, ...declared args). Each
    //  body is fully guarded — a thrown exception must never escape into the Obj-C runtime.
    // =========================================================================================

    /** {@code -(NSArray*)toolbar(Default|Allowed)ItemIdentifiers:(NSToolbar*)tb}. */
    private static final class IdentifiersCallback implements Callback
    {
        @SuppressWarnings( "unused" )
        public Pointer callback( Pointer self, Pointer cmd, Pointer toolbar )
        {
            try {
                ID arr = Foundation.invoke( "NSMutableArray", "array" );
                Foundation.invoke( arr, "addObject:", Foundation.nsString( ID_FLEX ) );
                Foundation.invoke( arr, "addObject:", Foundation.nsString( ID_BROWSE ) );
                Foundation.invoke( arr, "addObject:", Foundation.nsString( ID_SETTINGS ) );
                Foundation.invoke( arr, "addObject:", Foundation.nsString( ID_HELP ) );
                // Update item only when a newer release is available (mirrors the JavaFX glyph,
                // which is hidden until then). Sits just before the account, like the navbar.
                if ( updateAvailable ) {
                    Foundation.invoke( arr, "addObject:", Foundation.nsString( ID_UPDATE ) );
                }
                Foundation.invoke( arr, "addObject:", Foundation.nsString( ID_SPACE ) );
                Foundation.invoke( arr, "addObject:", Foundation.nsString( ID_ACCOUNT ) );
                return arr.toPointer();
            }
            catch ( Throwable t ) {
                Logger.logWarningSilent( "MacOsToolbar: identifiers callback failed — " + t.getMessage() );
                return ID.NIL.toPointer();
            }
        }
    }

    /** {@code -(NSToolbarItem*)toolbar:itemForItemIdentifier:willBeInsertedIntoToolbar:}. */
    private static final class ItemForIdentifierCallback implements Callback
    {
        @SuppressWarnings( "unused" )
        public Pointer callback( Pointer self, Pointer cmd, Pointer toolbar, Pointer identifier, byte flag )
        {
            try {
                ID identifierId = new ID( identifier );
                String id = Foundation.toStringViaUTF8( identifierId );
                if ( id == null ) {
                    return ID.NIL.toPointer();
                }
                String symbol;
                String label;
                switch ( id ) {
                    case ID_BROWSE   -> { symbol = "square.grid.2x2";    label = LocalizationManager.get( "main.navbar.browse" ); }
                    case ID_SETTINGS -> { symbol = "gearshape";          label = LocalizationManager.get( "main.navbar.settings" ); }
                    case ID_HELP     -> { symbol = "questionmark.circle"; label = LocalizationManager.get( "menu.help.title" ); }
                    case ID_UPDATE   -> { symbol = "arrow.down.circle";  label = LocalizationManager.get( "notification.update.available.title" ); }
                    case ID_ACCOUNT  -> { symbol = "person.crop.circle"; label = currentUserName(); }
                    default          -> { return ID.NIL.toPointer(); }
                }

                ID item = Foundation.invoke( Foundation.invoke( "NSToolbarItem", "alloc" ),
                                             "initWithItemIdentifier:", identifierId );
                Foundation.invoke( item, "setLabel:", Foundation.nsString( label ) );
                Foundation.invoke( item, "setToolTip:", Foundation.nsString( label ) );
                Foundation.invoke( item, "setBordered:", true );
                ID image = symbolImage( symbol );
                if ( !Foundation.isNil( image ) ) {
                    Foundation.invoke( item, "setImage:", image );
                }
                Foundation.invoke( item, "setTarget:", delegateRef );
                Foundation.invoke( item, "setAction:", Foundation.createSelector( "onMicaToolbarItem:" ) );
                Foundation.invoke( item, "autorelease" );

                if ( ID_ACCOUNT.equals( id ) ) {
                    // Hold the item so the async avatar load can swap the real player head in for
                    // the person glyph; if the load fails the glyph just stays.
                    accountItemRef = item;
                    loadAccountAvatarAsync();
                }
                return item.toPointer();
            }
            catch ( Throwable t ) {
                Logger.logWarningSilent( "MacOsToolbar: itemForIdentifier callback failed — " + t.getMessage() );
                return ID.NIL.toPointer();
            }
        }
    }

    /** {@code -(void)onMicaToolbarItem:(NSToolbarItem*)sender}. Dispatches by item identifier. */
    private static final class ActionCallback implements Callback
    {
        @SuppressWarnings( "unused" )
        public void callback( Pointer self, Pointer cmd, Pointer sender )
        {
            try {
                String id = Foundation.toStringViaUTF8( Foundation.invoke( new ID( sender ), "itemIdentifier" ) );
                if ( ID_BROWSE.equals( id ) ) {
                    LauncherActions.openBrowse();
                }
                else if ( ID_SETTINGS.equals( id ) ) {
                    LauncherActions.openSettings();
                }
                else if ( ID_HELP.equals( id ) ) {
                    LauncherActions.openHelp();
                }
                else if ( ID_UPDATE.equals( id ) ) {
                    // Same prompt the JavaFX update glyph opens: confirm, then open the release URL.
                    com.micatechnologies.minecraft.launcher.utilities.UpdateCheckManager
                            .promptAndOpenUpdate( updateUrl, toolbarStage );
                }
                else if ( ID_ACCOUNT.equals( id ) ) {
                    // Matches the in-window avatar's click: open account settings.
                    LauncherActions.openSettings();
                }
            }
            catch ( Throwable t ) {
                Logger.logWarningSilent( "MacOsToolbar: action dispatch failed — " + t.getMessage() );
            }
        }
    }

    /** Builds an SF Symbol NSImage, tinted to the current theme accent when the OS supports it,
     *  else the plain (appearance-adaptive) symbol. Returns {@code ID.NIL} if the symbol is
     *  unavailable. */
    private static ID symbolImage( String symbolName )
    {
        ID image = Foundation.invoke( "NSImage", "imageWithSystemSymbolName:accessibilityDescription:",
                                      Foundation.nsString( symbolName ), ID.NIL );
        if ( Foundation.isNil( image ) ) {
            return ID.NIL;
        }
        try {
            ID accent = accentNsColor();
            if ( accent != null && !Foundation.isNil( accent ) ) {
                ID config = Foundation.invoke( "NSImageSymbolConfiguration",
                                               "configurationWithHierarchicalColor:", accent );
                if ( !Foundation.isNil( config ) ) {
                    ID tinted = Foundation.invoke( image, "imageWithSymbolConfiguration:", config );
                    if ( !Foundation.isNil( tinted ) ) {
                        return tinted;
                    }
                }
            }
        }
        catch ( Throwable ignored ) { /* fall through to the plain adaptive symbol */ }
        return image;
    }

    /** The logged-in player's name for the account item label, or the generic "Player" string. */
    private static String currentUserName()
    {
        try {
            var user = MCLauncherAuthManager.getLoggedInUser();
            if ( user != null ) {
                String name = user.name();
                if ( name != null && !name.isBlank() ) {
                    return name;
                }
            }
        }
        catch ( Throwable ignored ) { /* fall through to the generic label */ }
        return LocalizationManager.get( "main.navbar.player" );
    }

    /**
     * Loads the player-head avatar off the FX/main thread and swaps it into the account item once
     * ready. The item already shows a person glyph, so this is a best-effort upgrade — any failure
     * (offline, no user, bad image) just leaves the glyph. The {@code NSImage} is retained across
     * the thread hop and released after it's been handed to the item.
     */
    private static void loadAccountAvatarAsync()
    {
        final String url;
        try {
            var user = MCLauncherAuthManager.getLoggedInUser();
            if ( user == null || user.uuid() == null ) {
                return;
            }
            url = GUIConstants.URL_MINECRAFT_USER_ICONS.replace(
                    GUIConstants.URL_MINECRAFT_USER_ICONS_USER_REPLACE_KEY, user.uuid() );
        }
        catch ( Throwable t ) {
            return;
        }

        SystemUtilities.spawnNewTask( () -> {
            ID retained = null;
            // Off-main NSImage creation needs its own autorelease pool for the temporaries.
            Foundation.NSAutoreleasePool pool = new Foundation.NSAutoreleasePool();
            try {
                ID nsUrl = Foundation.invoke( "NSURL", "URLWithString:", Foundation.nsString( url ) );
                if ( !Foundation.isNil( nsUrl ) ) {
                    ID img = Foundation.invoke( Foundation.invoke( "NSImage", "alloc" ),
                                                "initWithContentsOfURL:", nsUrl );
                    if ( !Foundation.isNil( img ) ) {
                        Foundation.invoke( img, "retain" );  // survive pool drain + thread hop
                        retained = img;
                    }
                }
            }
            catch ( Throwable t ) {
                Logger.logWarningSilent( "MacOsToolbar: account avatar load failed — " + t.getMessage() );
            }
            finally {
                pool.drain();
            }
            if ( retained == null ) {
                return;
            }
            final ID avatar = retained;
            Platform.runLater( () -> {
                try {
                    ID item = accountItemRef;
                    if ( item != null && !Foundation.isNil( item ) ) {
                        Foundation.invoke( item, "setImage:", avatar );
                    }
                }
                catch ( Throwable ignored ) { /* keep the glyph */ }
                finally {
                    try { Foundation.invoke( avatar, "release" ); }
                    catch ( Throwable ignored ) { }
                }
            } );
        } );
    }

    /** Current theme accent as an {@code NSColor}, or null on failure. */
    private static ID accentNsColor()
    {
        try {
            RgbColor c = ThemeAccentColors.accentForCurrentTheme();
            if ( c == null ) {
                return null;
            }
            return Foundation.invoke( "NSColor", "colorWithSRGBRed:green:blue:alpha:",
                                      c.r() / 255.0, c.g() / 255.0, c.b() / 255.0, 1.0 );
        }
        catch ( Throwable t ) {
            return null;
        }
    }

    /**
     * On macOS, once the native toolbar is installed, hides the in-window navbar controls it now
     * replaces (Browse / Settings / Help) so they don't sit dead under the toolbar band. No-op
     * when the toolbar didn't install — the elegant fallback keeps the JavaFX navbar intact.
     *
     * @since 2026.2
     */
    public static void hideReplacedControls( Parent root )
    {
        if ( !SystemUtils.IS_OS_MAC || !installed || root == null ) {
            return;
        }
        hide( root.lookup( "#libraryBtn" ) );
        hide( root.lookup( "#settingsBtn" ) );
        hideAll( root.lookupAll( ".helpButton" ) );
        hideAll( root.lookupAll( ".navRefreshIcon" ) );
        hideAll( root.lookupAll( ".navUpdateIcon" ) );
        // The account display (avatar + player name + its divider) also lives in the navbar's
        // dead band and would overlap the native toolbar items. Its only click action opens
        // Settings, which the native Settings item already provides — so hiding it loses no
        // functionality, just the in-window identity label.
        hide( root.lookup( "#userImage" ) );
        hide( root.lookup( "#playerLabel" ) );
        hideAll( root.lookupAll( ".navDivider" ) );
    }

    private static void hide( Node node )
    {
        if ( node != null ) {
            node.setVisible( false );
            node.setManaged( false );
        }
    }

    private static void hideAll( Set< Node > nodes )
    {
        for ( Node node : nodes ) {
            hide( node );
        }
    }
}
