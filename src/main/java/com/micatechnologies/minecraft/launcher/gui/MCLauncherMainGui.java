/*
 * Copyright (c) 2021-2026 Mica Technologies
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

import com.micatechnologies.minecraft.launcher.LauncherCore;
import com.micatechnologies.minecraft.launcher.config.ConfigManager;
import com.micatechnologies.minecraft.launcher.consts.GUIConstants;
import com.micatechnologies.minecraft.launcher.consts.LauncherConstants;
import com.micatechnologies.minecraft.launcher.consts.ModPackConstants;
import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.game.auth.MCLauncherAuthManager;
import com.micatechnologies.minecraft.launcher.game.modpack.GameModPack;
import com.micatechnologies.minecraft.launcher.game.modpack.GameModPackManager;
import com.micatechnologies.minecraft.launcher.utilities.*;
import com.micatechnologies.minecraft.launcher.system.DesktopShortcutManager;
import io.github.palexdev.materialfx.controls.MFXButton;
import io.github.palexdev.materialfx.controls.MFXCheckbox;
import io.github.palexdev.materialfx.controls.MFXComboBox;
import io.github.palexdev.materialfx.controls.MFXTextField;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.event.EventHandler;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.CacheHint;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelReader;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.RowConstraints;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Main launcher screen — renders an installed-modpack library as a vertical
 * scrolling list of website-style hero cards (one card per pack/version).
 * Each card carries its own Play and Visit Website actions.
 *
 * @since 1.0
 */
public class MCLauncherMainGui extends MCLauncherAbstractGui
{
    // ===== Top navigation bar =====
    @SuppressWarnings( "unused" ) @FXML javafx.scene.shape.SVGPath refreshIcon;
    @SuppressWarnings( "unused" ) @FXML MFXButton libraryBtn;
    @SuppressWarnings( "unused" ) @FXML MFXButton settingsBtn;
    @SuppressWarnings( "unused" ) @FXML Label helpBtn;
    @SuppressWarnings( "unused" ) @FXML ImageView userImage;
    @SuppressWarnings( "unused" ) @FXML javafx.scene.shape.SVGPath updateImgView;
    @SuppressWarnings( "unused" ) @FXML Label playerLabel;
    @SuppressWarnings( "unused" ) @FXML HBox announcementBar;
    @SuppressWarnings( "unused" ) @FXML Label announcement;
    @SuppressWarnings( "unused" ) @FXML Label announcementClose;
    @SuppressWarnings( "unused" ) @FXML RowConstraints announcementRow;

    // ===== Filter row =====
    @SuppressWarnings( "unused" ) @FXML MFXTextField searchField;
    @SuppressWarnings( "unused" ) @FXML MFXComboBox< String > typeFilter;
    @SuppressWarnings( "unused" ) @FXML MFXComboBox< String > sortFilter;
    @SuppressWarnings( "unused" ) @FXML MFXCheckbox recentlyUpdatedOnlyCheck;

    // ===== Pagination row =====
    @SuppressWarnings( "unused" ) @FXML Label paginationRangeLabel;
    @SuppressWarnings( "unused" ) @FXML Label paginationPageLabel;
    @SuppressWarnings( "unused" ) @FXML MFXButton prevPageBtn;
    @SuppressWarnings( "unused" ) @FXML MFXButton nextPageBtn;
    @SuppressWarnings( "unused" ) @FXML MFXComboBox< Integer > pageSizeFilter;

    // ===== Center: scrolling card list =====
    @SuppressWarnings( "unused" ) @FXML ScrollPane modpackScrollPane;
    @SuppressWarnings( "unused" ) @FXML FlowPane modpackCardList;

    // ===== Bottom status bar =====
    @SuppressWarnings( "unused" ) @FXML Label versionLabel;
    @SuppressWarnings( "unused" ) @FXML Label offlineLabel;
    @SuppressWarnings( "unused" ) @FXML Label backgroundFetchLabel;
    @SuppressWarnings( "unused" ) @FXML MFXButton exitBtn;

    /** Expanded modpack-detail modal — overlays the main GUI when the user
     *  single-clicks a hero card. Constructed lazily in {@link #setup()} so the
     *  rootPane is real and the GridPane attachment works. */
    private MCLauncherModpackDetailModal detailModal;

    // ===== Filter / sort / pagination state =====

    private static final String TYPE_ALL      = "All";
    private static final String TYPE_MODPACKS = "Modpacks";
    private static final String TYPE_VANILLA  = "Vanilla";

    private static final String SORT_LAST_PLAYED  = "Last Played";
    private static final String SORT_NAME_AZ      = "Name (A–Z)";
    private static final String SORT_NAME_ZA      = "Name (Z–A)";
    private static final String SORT_RECENT_UPDATE = "Recently Updated";
    /** Most-played sort — total play time descending, packs that have never
     *  been launched fall to the bottom (getTotalPlayTimeMs returns 0 for them
     *  so they're indistinguishable from each other under this sort and order
     *  amongst themselves doesn't matter). Surfaces "what do you actually
     *  spend your time playing" near the top, useful once users accumulate
     *  installs from the future CurseForge / Modrinth import flow but only
     *  actually return to a handful. */
    private static final String SORT_MOST_PLAYED  = "Most Played";

    /** Page-size options exposed in the per-page dropdown. 12 is the default since
     *  the hero cards are 360px wide and wrap to 3-4 per row on typical window
     *  widths — a 12-item page fills 3-4 rows without needing to scroll past the
     *  pagination controls. */
    /** Page-size options. Bumped past the original 12/24/48 because card pooling
     *  ({@link #cardPool}, {@link ModpackHeroCard#bind}) absorbs the per-card
     *  scene-graph-creation cost that used to bite at 48-pack pages — the new
     *  upper end of 96 is still smooth on a warm pool. Pages above ~120 start
     *  to feel sluggish on the FlowPane layout pass even with pooling, so the
     *  ladder is capped there. */
    private static final List< Integer > PAGE_SIZES = List.of( 12, 24, 48, 72, 96 );
    /** Default page size, bumped from 12 → 48 alongside the card-pool rollout
     *  so the typical 30–50-pack library fits on one page without paging. */
    private static final int DEFAULT_PAGE_SIZE = 48;

    /** Filter / sort / search / pagination state for this screen. The VM owns
     *  pageSize, currentPage, the search-text debounce, and the named filter
     *  dimensions ({@code "type"} / {@code "updatesOnly"}); listeners below
     *  drive it from the FXML inputs and {@link #rebuildCards} reads from it.
     *  Shared between this screen and {@link MCLauncherGameLibraryGui} so a
     *  single bug fix lands in both. */
    private final LibraryViewModel vm = new LibraryViewModel( DEFAULT_PAGE_SIZE, 120 );

    /** Pool of constructed-but-not-currently-displayed {@link ModpackHeroCard}
     *  instances. {@link #rebuildCards()} pulls from here on each rebuild and
     *  returns any unused cards back to it, so the per-card scene-graph
     *  allocation + CSS-apply cost is paid once per slot for the lifetime of
     *  the main GUI instead of once per rebuild. Critical now that the default
     *  page size is 48 (up from 12) and on the future CurseForge / Modrinth
     *  import flow where libraries may grow into the hundreds.
     *
     *  <p>ArrayDeque is fine here: rebuildCards always runs on the FX thread,
     *  so no synchronization is needed.</p> */
    private final CardPool< ModpackHeroCard > cardPool = new CardPool<>();

    /** Filter key used in the VM for the "recently updated only" boolean.
     *  String constants for filter keys live with the controller that owns
     *  the dimension — the VM is intentionally generic. */
    private static final String FILTER_UPDATES_ONLY = "updatesOnly";
    private static final String FILTER_TYPE         = "type";

    /** True once the main menu has been shown at least once in this session. The
     *  cold-start fade-in only runs on the first show — subsequent returns from
     *  Settings / Library / etc. should snap back to the grid instantly the way
     *  the rest of the launcher's screen transitions do. */
    private static volatile boolean mainMenuShownOnce = false;

    public MCLauncherMainGui( Stage stage ) throws IOException {
        super( stage );
    }

    @SuppressWarnings( "unused" )
    public MCLauncherMainGui( Stage stage, double width, double height ) throws IOException {
        super( stage, width, height );
    }

    @Override
    String getSceneFxmlPath() {
        return "gui/mainGUI.fxml";
    }

    @Override
    String getSceneName() {
        return "Home";
    }

    @Override
    void setup() {
        // Window close → exit confirmation flow.
        stage.setOnCloseRequest( windowEvent -> {
            windowEvent.consume();
            exitBtn.fire();
        } );

        SystemUtilities.spawnNewTask( () -> DiscordRpcUtility.setMenuPresence( "Selecting a Mod Pack" ) );

        exitBtn.setOnAction( event -> LauncherCore.closeApp() );

        // Update-available indicator. The async update check pushes its
        // "update ready" red overlay through TaskbarProgressManager, which owns
        // a single shared wrapper for the app's lifetime — no per-screen
        // instances to manage or clean up here.
        UpdateCheckManager.checkAndConfigureUI( updateImgView, stage );

        // Announcements — dev banner if applicable, otherwise whatever the announcement service returns.
        if ( LauncherConstants.LAUNCHER_IS_DEV ) {
            setAnnouncementRow( "[DEVELOPMENT MODE: Bugs may be present and not all features may function as intended]" );
        }
        else {
            setAnnouncementRow( null );
        }

        // The announcement fetch is no longer a blocking startup step — it runs as a
        // pure background CompletableFuture spawned in LauncherSession. If it hadn't
        // settled by the time the menu painted, re-run setAnnouncementRow once it
        // does so the banner pops up the moment the JSON arrives. The whenComplete
        // is a one-shot — once the future is done, JavaFX never schedules again.
        java.util.concurrent.CompletableFuture< Void > announcementsFuture =
                AnnouncementManager.getCheckFuture();
        if ( announcementsFuture != null && !announcementsFuture.isDone()
                && !LauncherConstants.LAUNCHER_IS_DEV ) {
            announcementsFuture.whenComplete( ( v, t ) ->
                    GUIUtilities.JFXPlatformRun( () -> setAnnouncementRow( null ) ) );
        }

        // Announcement bar ✕ — dismiss for this session (resets on next launch).
        // Captures the currently-displayed text so AnnouncementManager keys on what
        // the user actually saw rather than what's currently in the manifest, which
        // could differ if a refresh races with the click.
        if ( announcementClose != null ) {
            announcementClose.setCursor( Cursor.HAND );
            TooltipManager.install( announcementClose, "Dismiss this announcement." );
            announcementClose.setOnMouseClicked( e -> {
                String shown = announcement.getText();
                if ( shown != null && !shown.isEmpty() ) {
                    AnnouncementManager.dismissAnnouncementForSession( shown );
                }
                // Re-run setAnnouncementRow with the same "extra" dev banner so the
                // dismissal logic collapses the row in a single code path.
                if ( LauncherConstants.LAUNCHER_IS_DEV ) {
                    setAnnouncementRow( "[DEVELOPMENT MODE: Bugs may be present and not all features may function as intended]" );
                }
                else {
                    setAnnouncementRow( null );
                }
            } );
        }

        settingsBtn.setOnAction( actionEvent -> SystemUtilities.spawnNewTask( () -> {
            try {
                MCLauncherGuiController.goToSettingsGui();
                SystemUtilities.spawnNewTask( () -> DiscordRpcUtility.setMenuPresence( "Settings" ) );
            }
            catch ( IOException e ) {
                Logger.logError( "Unable to load settings GUI due to an incomplete response from the GUI subsystem." );
                Logger.logThrowable( e );
            }
        } ) );

        libraryBtn.setDisable( AnnouncementManager.getDisableModpacksEdit() );
        libraryBtn.setOnAction( actionEvent -> SystemUtilities.spawnNewTask( () -> {
            try {
                MCLauncherGuiController.goToGameLibraryGui();
                SystemUtilities.spawnNewTask( () -> DiscordRpcUtility.setMenuPresence( "Browsing modpacks" ) );
            }
            catch ( IOException e ) {
                Logger.logError( "Unable to load library GUI due to an incomplete response from the GUI subsystem." );
                Logger.logThrowable( e );
            }
        } ) );

        helpBtn.setOnMouseClicked( e -> MCLauncherHelpWindow.show( getHelpTopic() ) );
        helpBtn.setCursor( Cursor.HAND );

        // Player avatar + name act as a clickable shortcut into the Settings → Account
        // category, since that's where users manage their identity. Both nodes share the
        // same handler so a click anywhere on the lockup works.
        EventHandler< MouseEvent > openAccountSettings = e -> SystemUtilities.spawnNewTask( () -> {
            try {
                MCLauncherSettingsGui settingsGui = MCLauncherGuiController.goToSettingsGui();
                if ( settingsGui != null ) {
                    GUIUtilities.JFXPlatformRun( () -> settingsGui.showCategory( 0 ) );
                }
                SystemUtilities.spawnNewTask( () -> DiscordRpcUtility.setMenuPresence( "Settings" ) );
            }
            catch ( IOException ex ) {
                Logger.logError( "Unable to load settings GUI from account label click." );
                Logger.logThrowable( ex );
            }
        } );
        userImage.setOnMouseClicked( openAccountSettings );
        userImage.setCursor( Cursor.HAND );
        playerLabel.setOnMouseClicked( openAccountSettings );
        playerLabel.setCursor( Cursor.HAND );

        playerLabel.setText( MCLauncherAuthManager.getLoggedInUser().name() );
        versionLabel.setText( "Mica Launcher v" + LauncherConstants.LAUNCHER_APPLICATION_VERSION );

        OfflineIndicator.applyTo( offlineLabel );

        // Auth refresh indicator. The cold-start path now paints this screen with
        // cached user info while the token renewal runs in the background; surface
        // that pending work on the bottom bar so the user sees a "Signing in…" hint
        // (same Label, same pattern as the pack-refresh indicator below). Cleared
        // when the future completes; the Play-click path awaits the same future
        // before launching the game, so a user who fires Play before refresh
        // settles will just see a brief progress modal there.
        java.util.concurrent.CompletableFuture< com.micatechnologies.minecraft.launcher.game.auth.MCLauncherAuthResult >
                pendingAuthRefresh = MCLauncherAuthManager.getPendingRefreshFuture();
        if ( pendingAuthRefresh != null && !pendingAuthRefresh.isDone() ) {
            backgroundFetchLabel.setText( LocalizationManager.get( "main.fetchLabel.signingIn" ) );
            backgroundFetchLabel.setVisible( true );
            backgroundFetchLabel.setManaged( true );
            pendingAuthRefresh.whenComplete( ( r, t ) -> GUIUtilities.JFXPlatformRun( () -> {
                backgroundFetchLabel.setVisible( false );
                backgroundFetchLabel.setManaged( false );
                // Restore the default text so any subsequent showBackgroundStatus
                // callers (pack-list refresh, etc.) don't inherit "Signing in…".
                backgroundFetchLabel.setText( LocalizationManager.get( "main.fetchLabel.loading" ) );
            } ) );
        }

        // "Loading available packs…" indicator. Show only while the background available-
        // modpacks fetch (kicked off at startup, see
        // GameModPackManager.startAvailableModPacksFetchAsync) is still running; hide as
        // soon as the future completes. Attaches the hide-on-complete callback via
        // whenComplete so we don't need a polling timer — the FX thread is woken
        // exactly once when the fetch finishes.
        java.util.concurrent.CompletableFuture< Void > availableFuture =
                com.micatechnologies.minecraft.launcher.game.modpack.GameModPackManager
                        .getAvailableFetchFuture();
        if ( availableFuture != null && !availableFuture.isDone() ) {
            backgroundFetchLabel.setVisible( true );
            backgroundFetchLabel.setManaged( true );
            availableFuture.whenComplete( ( v, t ) -> GUIUtilities.JFXPlatformRun( () -> {
                backgroundFetchLabel.setVisible( false );
                backgroundFetchLabel.setManaged( false );
            } ) );
        }

        // Installed-modpack revalidate hook. fetchInstalledModPacks now paints the menu
        // from on-disk cache and kicks off a background network revalidate for every
        // installed pack — when that future settles, any pack whose manifest has
        // changed since the cache was written has already been swapped into the
        // live list, so a single rebuildCards() picks up the new state (version,
        // updated-since-launch flag, refreshed images) without the user touching
        // anything. Cheap when no packs changed; the rebuild is what already runs
        // on filter/sort interactions.
        java.util.concurrent.CompletableFuture< Void > revalidateFuture =
                com.micatechnologies.minecraft.launcher.game.modpack.GameModPackManager
                        .getInstalledRevalidateFuture();
        if ( revalidateFuture != null && !revalidateFuture.isDone() ) {
            revalidateFuture.whenComplete( ( v, t ) -> GUIUtilities.JFXPlatformRun( this::rebuildCards ) );
        }

        // Background-load the avatar so the FX thread doesn't sit on a network
        // round-trip to minotar.net during first paint. With backgroundLoading=true
        // the ImageView shows nothing until the bytes land, then JavaFX updates the
        // node from its own image-loader thread. On a slow link this used to add
        // hundreds of ms to main-menu-painted because new Image(String) defaults
        // to synchronous loading.
        userImage.setImage( new Image(
                GUIConstants.URL_MINECRAFT_USER_ICONS.replace( GUIConstants.URL_MINECRAFT_USER_ICONS_USER_REPLACE_KEY,
                                                               MCLauncherAuthManager.getLoggedInUser().uuid() ),
                true ) );

        // Keyboard shortcuts: ENTER plays the last-played pack; F5 refreshes pack metadata.
        scene.setOnKeyPressed( keyEvent -> {
            if ( keyEvent.getCode() == KeyCode.ENTER ) {
                keyEvent.consume();
                playLastSelectedModpack();
            }
            else if ( keyEvent.getCode() == KeyCode.F5 ) {
                keyEvent.consume();
                refreshAvailablePacks();
            }
        } );

        // Cross-platform global shortcuts (Settings / Browse / Editor / Home / Help).
        // setOnKeyPressed above only handles ENTER + F5; the global manager uses
        // an event filter so the two coexist.
        KeyboardShortcutManager.installGlobalShortcuts( scene, this::getHelpTopic );

        // Cmd/Ctrl+F focuses the search field. Layered as an event filter so the
        // ENTER handler above doesn't swallow it.
        scene.addEventFilter( javafx.scene.input.KeyEvent.KEY_PRESSED, ev -> {
            if ( ev.isShortcutDown() && !ev.isAltDown()
                    && !ev.isShiftDown() && ev.getCode() == KeyCode.F ) {
                ev.consume();
                if ( searchField != null ) searchField.requestFocus();
            }
        } );

        // Click handler for the navbar refresh icon. Same behavior as F5 — fires
        // an asynchronous announcement + modpack-manifest fetch, then reloads
        // the main GUI so the freshly-fetched data is rendered.
        if ( refreshIcon != null ) {
            refreshIcon.setOnMouseClicked( e -> refreshAvailablePacks() );
            refreshIcon.setCursor( Cursor.HAND );
        }

        setupFilterAndPaginationControls();
        rebuildCards();

        // Smooth animated wheel-scroll for the card grid. Replaces JavaFX's default
        // snappy step-scroll with an eased glide that matches what native apps feel
        // like. See SmoothScroll for tuning.
        SmoothScroll.install( modpackScrollPane );

        // Build and attach the expanded modpack-detail modal overlay. The modal is a
        // StackPane that spans the entire GridPane (all rows + columns) and is hidden
        // until a hero card is clicked. Attaching here — after the populate call —
        // means it z-orders above every card in the scroll list as well as above the
        // navbar / bottom bar / announcement banner.
        if ( rootPane instanceof GridPane gridRoot ) {
            detailModal = new MCLauncherModpackDetailModal( gridRoot );
            detailModal.attachToGridPane( gridRoot );
        }
    }

    /**
     * Wires up the filter row (search / type / sort / Updates-Only) and the
     * pagination row (prev / next / per-page) so each input triggers a card
     * rebuild on change. Defaults are picked to match the screen's pre-filter
     * behavior: "All / Last Played" surfaces every installed pack with the
     * last-played one floated to the top, exactly like the original layout.
     */
    private void setupFilterAndPaginationControls()
    {
        // Type filter — Modpacks vs Vanilla. The main menu only shows INSTALLED
        // entries, so there's no need for a status filter (that's the Library
        // screen's job). "All" matches both kinds.
        typeFilter.setItems( FXCollections.observableArrayList(
                TYPE_ALL, TYPE_MODPACKS, TYPE_VANILLA ) );
        typeFilter.selectItem( TYPE_ALL );
        vm.setFilter( FILTER_TYPE, TYPE_ALL );
        typeFilter.setOnAction( e -> vm.setFilter( FILTER_TYPE, typeFilter.getValue() ) );

        // Sort filter — Last Played mirrors the pre-filter "float last-played to
        // top" behavior. Name A→Z / Z→A and Recently Updated round out the most
        // common needs.
        sortFilter.setItems( FXCollections.observableArrayList(
                SORT_LAST_PLAYED, SORT_MOST_PLAYED, SORT_NAME_AZ, SORT_NAME_ZA, SORT_RECENT_UPDATE ) );
        sortFilter.selectItem( SORT_LAST_PLAYED );
        vm.setSortKey( SORT_LAST_PLAYED );
        sortFilter.setOnAction( e -> vm.setSortKey( sortFilter.getValue() ) );

        // Search — VM debounces internally so a rapid burst of keystrokes
        // coalesces into one rebuild rather than firing per character.
        searchField.textProperty().addListener( ( obs, oldVal, newVal ) -> vm.setSearchQuery( newVal ) );
        // Same float-mode kill switch the Library screen uses — no floating label
        // wanted, just promptText.
        searchField.setFloatMode( io.github.palexdev.materialfx.enums.FloatMode.DISABLED );

        // "Recently updated only" checkbox — flips the boolean filter on selection
        // change. A checkbox communicates the dual on/off state more clearly than
        // the previous toggle button did (the button needed a styled `.selected`
        // class to indicate active state, which was easy to miss).
        recentlyUpdatedOnlyCheck.setSelected( false );
        recentlyUpdatedOnlyCheck.selectedProperty().addListener(
                ( obs, oldVal, newVal ) -> vm.setFilter( FILTER_UPDATES_ONLY, Boolean.TRUE.equals( newVal ) ) );

        // Pagination controls
        pageSizeFilter.setItems( FXCollections.observableArrayList( PAGE_SIZES ) );
        pageSizeFilter.selectItem( DEFAULT_PAGE_SIZE );
        pageSizeFilter.setOnAction( e -> {
            Integer selected = pageSizeFilter.getValue();
            if ( selected != null ) vm.setPageSize( selected );
        } );
        prevPageBtn.setOnAction( e -> vm.prevPage() );
        nextPageBtn.setOnAction( e -> vm.nextPage() );

        // Wire VM rebuild callback last so the initial setFilter / setSortKey
        // calls above don't cause a rebuild before setup finishes — the
        // explicit rebuildCards() at the end of setup() does the first paint.
        vm.setOnStateChanged( this::rebuildCards );

        // Tooltips so the controls are discoverable. Help text matches the
        // Library screen's hints where applicable so users get a consistent
        // mental model across the two screens.
        TooltipManager.install( searchField,
                "Filter packs. Each whitespace-separated word must match somewhere in "
                        + "the pack's name, version, Minecraft version, or Forge version. "
                        + "Try \"1.12\", \"forge\", or \"biomes 1.12\"." );
        TooltipManager.install( typeFilter,     "Filter by Modpack or Vanilla version." );
        TooltipManager.install( sortFilter,     "Choose how packs are ordered in the grid." );
        TooltipManager.install( recentlyUpdatedOnlyCheck,
                                "Show only packs whose manifest has changed since last launch." );
        TooltipManager.install( pageSizeFilter, "How many cards to render per page." );
    }

    @Override
    void afterShow() {
        ColdStartProfiler.mark( "main_gui_onShown" );

        // Defer two event-loop ticks past the stage's onShown so the first full
        // layout + paint pass has actually flushed before the profiler stamps its
        // final mark. Idempotent — only the first call writes anything (a session-
        // wide flag in the profiler) so subsequent main-menu re-shows are silent.
        Platform.runLater( () -> Platform.runLater( () -> {
            ColdStartProfiler.mark( "main_menu_painted" );
            ColdStartProfiler.writeAndMaybeExit();
        } ) );

        TooltipManager.install( settingsBtn, "Open launcher settings (RAM, theme, JVM flags, proxy)." );
        TooltipManager.install( libraryBtn, "Browse, install, and manage modpacks + vanilla Minecraft versions." );
        // Tooltip text intentionally still uses "Browse, install, manage" — matches
        // the button's new "Browse" label.
        TooltipManager.install( helpBtn, "Open the help window for this screen." );
        if ( refreshIcon != null ) {
            TooltipManager.install( refreshIcon,
                                    "Refresh modpack data (same as pressing F5)." );
        }

        // First-launch quick-start wizard. Fires once per install (the config flag
        // flips true on completion or skip), so existing users get walked through
        // the same setup the first time they open this version of the launcher.
        // Deferred a frame via Platform.runLater so the main GUI has finished
        // painting before the modal wizard pops up — otherwise the wizard's
        // showAndWait() races against the main stage's first show.
        if ( !ConfigManager.getQuickStartCompleted() ) {
            Platform.runLater( () -> MCLauncherQuickStartWizard.show( stage ) );
        }

        // Push focus onto the rootPane so JavaFX's default focus traversal doesn't
        // land on the first focus-traversable child (the Library button), which
        // gives it a visible focus-ring treatment as soon as the screen opens.
        // runLater defers past the layout pass where JavaFX assigns initial focus.
        Platform.runLater( () -> {
            if ( rootPane != null ) {
                rootPane.requestFocus();
            }
        } );

        // First-paint fade on cold start. The progress GUI cleared and the main
        // menu scene attached in a single FX-thread frame, so without this the
        // user sees a hard jump from one fully-rendered screen to another.
        // Subsequent returns to this screen (from Settings, Library, etc.) snap
        // instantly the way the rest of the launcher's transitions do — the
        // fade is reserved for the once-per-session "we just started up" moment.
        if ( !mainMenuShownOnce && rootPane != null ) {
            mainMenuShownOnce = true;
            javafx.animation.FadeTransition fade = new javafx.animation.FadeTransition(
                    javafx.util.Duration.millis( 180 ), rootPane );
            fade.setFromValue( 0.0 );
            fade.setToValue( 1.0 );
            rootPane.setOpacity( 0.0 );
            fade.play();
        }

        // Idle-window prefetch: warm GameVersionManifest's clientJsonCache for the
        // most-recently-played pack. The first Play click on that pack would
        // otherwise pay a 1-2s download of client.json (Mojang piston-meta) on
        // the critical path between "user clicks Play" and "progress GUI shows
        // first stage." Doing it during the main-menu idle window — where the
        // user is reading the grid and deciding what to launch — hides the cost
        // entirely. Best-effort: any failure (offline, pack with no MC version,
        // server error) is swallowed silently; the eventual Play click does its
        // own fetch normally.
        SystemUtilities.spawnNewTask( () -> {
            try {
                if ( com.micatechnologies.minecraft.launcher.utilities.NetworkUtilities.isOffline() ) {
                    return;
                }
                GameModPack mostRecent = null;
                long maxTs = 0;
                for ( GameModPack pack : GameModPackManager.getInstalledModPacks() ) {
                    if ( pack == null ) continue;
                    long ts = pack.getLastPlayedMs();
                    if ( ts > maxTs ) {
                        maxTs = ts;
                        mostRecent = pack;
                    }
                }
                if ( mostRecent == null || maxTs == 0 ) {
                    return;  // no play history yet — nothing to warm
                }
                String mcVersion = mostRecent.getMinecraftVersion();
                if ( mcVersion != null && !mcVersion.isBlank() ) {
                    com.micatechnologies.minecraft.launcher.game.modpack.manifests.GameVersionManifest
                            .getClientJson( mcVersion );
                }
            }
            catch ( Throwable ignored ) {
                // Best-effort — never let the prefetch surface noise on the main menu.
            }
        } );
    }

    @Override
    void cleanup() {
        // Defensive: if the update-check fired showFullError() to flag an
        // available update, clear it on transition out so the next screen
        // (e.g. progressGUI for a game launch) isn't competing with a stale
        // red overlay. Stop only — the wrapper itself is owned by
        // TaskbarProgressManager and lives until app exit.
        GUIUtilities.JFXPlatformRun( TaskbarProgressManager::stop );
    }

    @Override
    HelpTopic getHelpTopic() { return HelpTopic.MAIN_SCREEN; }

    public void setAnnouncementRow( String extra ) {
        String homeAnnounce = AnnouncementManager.getAnnouncementHome();
        if ( homeAnnounce == null ) homeAnnounce = "";

        String announcementText;
        if ( extra != null && !extra.isEmpty() ) {
            announcementText = homeAnnounce.isEmpty() ? extra : extra + "\n" + homeAnnounce;
        }
        else {
            announcementText = homeAnnounce;
        }

        // Session-scoped dismissal: if the user clicked the banner's ✕ for the
        // exact same text earlier in this launcher session, collapse the row.
        // Treated as "no announcement" for sizing purposes — the user will see
        // the banner again on next launch since the dismissal set is in-memory.
        if ( !announcementText.isEmpty() && AnnouncementManager.isAnnouncementDismissed( announcementText ) ) {
            announcementText = "";
        }

        if ( !announcementText.isEmpty() ) {
            announcement.setText( announcementText );
            announcementBar.setVisible( true );
            announcementBar.setManaged( true );
            // Use explicit fixed sizes — USE_COMPUTED_SIZE was not consistently resolving across
            // navigation transitions (banner shows on first load, vanishes after settings → main).
            // A multi-line message wraps within the Label; the row caps at 80 to keep the navbar
            // layout from collapsing.
            boolean multiLine = announcementText.contains( "\n" );
            double targetHeight = multiLine ? 60 : 40;
            announcement.setMinHeight( targetHeight );
            announcement.setPrefHeight( targetHeight );
            announcement.setMaxHeight( targetHeight );
            announcementBar.setMinHeight( targetHeight );
            announcementBar.setPrefHeight( targetHeight );
            announcementBar.setMaxHeight( targetHeight );
            announcementRow.setMinHeight( targetHeight );
            announcementRow.setPrefHeight( targetHeight );
            announcementRow.setMaxHeight( targetHeight );
        }
        else {
            announcement.setText( "" );
            announcementBar.setVisible( false );
            announcementBar.setManaged( false );
            announcement.setMinHeight( 0 );
            announcement.setPrefHeight( 0 );
            announcement.setMaxHeight( 0 );
            announcementBar.setMinHeight( 0 );
            announcementBar.setPrefHeight( 0 );
            announcementBar.setMaxHeight( 0 );
            announcementRow.setMinHeight( 0 );
            announcementRow.setPrefHeight( 0 );
            announcementRow.setMaxHeight( 0 );
        }
    }

    /**
     * Builds the visible card grid from the current filter / search / sort /
     * pagination state. Replaces the previous flat populateModpackCards() so
     * the main menu can now scale up to tens of packs without becoming an
     * unscannable wall.
     *
     * <p>Pipeline:
     * <ol>
     *   <li>Collect installed Forge modpacks + installed vanilla versions into a
     *       single {@code GameModPack} list.</li>
     *   <li>Apply the type filter (All / Modpacks / Vanilla).</li>
     *   <li>Apply the Updates-Only toggle.</li>
     *   <li>Apply the free-text search against display name.</li>
     *   <li>Sort by the selected sort key.</li>
     *   <li>Slice to the current page and instantiate hero cards for the slice.</li>
     * </ol>
     *
     * <p>Must run on the FX thread — touches FXML-injected controls and mutates
     * the FlowPane's child list.
     */
    private void rebuildCards()
    {
        // Step 1 — collect the union of installed modpacks + vanilla versions.
        List< GameModPack > all = new ArrayList<>( GameModPackManager.getInstalledModPacks() );
        for ( String versionId :
                com.micatechnologies.minecraft.launcher.game.modpack.VanillaVersionManager.getInstalledVersionIds() ) {
            all.add( GameModPack.createVanillaModPack( versionId ) );
        }

        // Step 2 — type filter (read from VM; UI listener keeps it in sync)
        String typeSel = vm.getStringFilter( FILTER_TYPE, TYPE_ALL );
        if ( TYPE_MODPACKS.equals( typeSel ) ) {
            all.removeIf( GameModPack::isVanillaVersion );
        }
        else if ( TYPE_VANILLA.equals( typeSel ) ) {
            all.removeIf( p -> !p.isVanillaVersion() );
        }

        // Step 3 — Updates-Only
        if ( vm.getBooleanFilter( FILTER_UPDATES_ONLY, false ) ) {
            all.removeIf( p -> !p.isUpdateAvailable() );
        }

        // Step 4 — search. Multi-token: whitespace splits the query into
        // tokens that must EACH appear somewhere in a per-pack haystack
        // (name + pack/MC/Forge versions). Lets "alto 1.12" or "1.12 alto"
        // (in either order) find Alto-1.12.2 even though neither matches
        // the display name as a single substring. VM.searchTokens()
        // returns an empty array for blank queries so the loop body is
        // skipped without a separate isEmpty() check.
        String[] tokens = vm.searchTokens();
        if ( tokens.length > 0 ) {
            all.removeIf( p -> !matchesAllTokens( p, tokens ) );
        }

        // Step 5 — sort
        String sortSel = vm.getSortKey().isEmpty() ? SORT_LAST_PLAYED : vm.getSortKey();
        sortPacks( all, sortSel );

        // Step 6 — paginate + render
        LibraryViewModel.PageBounds bounds = vm.clampAndSlice( all.size() );

        updatePaginationControls( bounds );

        // Recycle currently-displayed cards back to the pool before clearing the
        // FlowPane, so the next pass can pull them out and rebind to the new
        // pack set instead of constructing fresh ones. The "empty state" child
        // isn't a card, so the cast guard in CardPool.recycleAll skips it.
        cardPool.recycleAll( modpackCardList.getChildren(), ModpackHeroCard.class );
        modpackCardList.getChildren().clear();

        if ( bounds.totalItems() == 0 ) {
            String searchForEmptyState = vm.getSearchQuery().trim().toLowerCase( Locale.ROOT );
            modpackCardList.getChildren().add( buildEmptyState( typeSel, searchForEmptyState ) );
            return;
        }
        for ( int i = bounds.startIdx(); i < bounds.endIdx(); i++ ) {
            ModpackHeroCard card = cardPool.acquireOrNull();
            if ( card == null ) {
                card = new ModpackHeroCard( all.get( i ) );
            }
            else {
                card.bind( all.get( i ) );
            }
            modpackCardList.getChildren().add( card );
        }
    }

    /**
     * Reorders the pack list per the selected sort key. Mutates in place to
     * avoid an extra list allocation — the caller is the one-shot
     * {@link #rebuildCards()} pipeline that doesn't reuse the list.
     */
    private void sortPacks( List< GameModPack > packs, String sortKey )
    {
        switch ( sortKey ) {
            case SORT_NAME_AZ -> packs.sort( Comparator.comparing(
                    p -> displayNameOf( p ).toLowerCase( Locale.ROOT ) ) );
            case SORT_NAME_ZA -> packs.sort( Comparator.comparing(
                    ( GameModPack p ) -> displayNameOf( p ).toLowerCase( Locale.ROOT ) ).reversed() );
            case SORT_RECENT_UPDATE -> {
                // Sort by the timestamp of the newest update-log entry for the pack
                // (proxy for "this manifest changed recently"). Packs with no update
                // history fall to the bottom via Long.MIN_VALUE sentinel returned
                // by LibrarySortKeys.lastUpdate.
                packs.sort( Comparator.comparingLong( LibrarySortKeys::lastUpdate ).reversed() );
            }
            case SORT_MOST_PLAYED -> {
                // Total play time descending. Packs that have never been launched
                // have a total of 0 and bunch at the bottom; the relative order
                // amongst those doesn't matter since the user can't tell them
                // apart by play-count anyway.
                packs.sort( Comparator.comparingLong( LibrarySortKeys::totalPlayed ).reversed() );
            }
            default -> {
                // SORT_LAST_PLAYED — reuse the pre-filter behavior: float the
                // last-played pack to position 0, then sort the rest by lastPlayedMs
                // descending (most recently played near the top, never-played at
                // the bottom).
                packs.sort( Comparator.comparingLong( LibrarySortKeys::lastPlayed ).reversed() );
                String lastSelected = ConfigManager.getLastModPackSelected();
                if ( lastSelected != null && !lastSelected.isBlank() ) {
                    for ( int i = 0; i < packs.size(); i++ ) {
                        GameModPack p = packs.get( i );
                        if ( lastSelected.equals( p.getPackName() )
                                || lastSelected.equals( p.getFriendlyName() ) ) {
                            packs.add( 0, packs.remove( i ) );
                            break;
                        }
                    }
                }
            }
        }
    }

    /** Updates the prev/next/page-label state to match the new pagination math. */
    private void updatePaginationControls( LibraryViewModel.PageBounds bounds )
    {
        int currentPage = vm.getCurrentPage();
        if ( bounds.totalItems() == 0 ) {
            paginationRangeLabel.setText( LocalizationManager.get( "main.pagination.empty" ) );
            paginationPageLabel.setText( LocalizationManager.format( "main.pagination.pageOfPages", 1, 1 ) );
        }
        else {
            paginationRangeLabel.setText( LocalizationManager.format(
                    "main.pagination.range", bounds.startIdx() + 1, bounds.endIdx(), bounds.totalItems() ) );
            paginationPageLabel.setText( LocalizationManager.format(
                    "main.pagination.pageOfPages", currentPage, bounds.totalPages() ) );
        }
        prevPageBtn.setDisable( currentPage <= 1 );
        nextPageBtn.setDisable( currentPage >= bounds.totalPages() );
    }

    /**
     * Returns true when every {@code token} appears as a substring of the
     * pack's search haystack (display name + pack version + MC version +
     * Forge version, all lowercased). All-tokens semantics is AND — typing
     * two words narrows the result, doesn't widen it.
     *
     * <p>The haystack is rebuilt on every call rather than cached on the pack
     * because pack metadata (version, Forge version) can change underneath
     * us when the background revalidate replaces a stub with a fully-loaded
     * pack. Caching it would mean stale matches on the first few seconds
     * after cold start.</p>
     */
    private static boolean matchesAllTokens( GameModPack pack, String[] tokens )
    {
        if ( tokens == null || tokens.length == 0 ) return true;
        String haystack = buildSearchHaystack( pack );
        for ( String t : tokens ) {
            if ( t == null || t.isEmpty() ) continue;
            if ( !haystack.contains( t ) ) return false;
        }
        return true;
    }

    /**
     * Concatenates every user-meaningful identifying string for a pack into
     * one lowercase haystack. Used by {@link #matchesAllTokens} so a search
     * for "1.12" or "forge" or "biomes 1.12" hits even when those tokens
     * aren't in the display name.
     */
    private static String buildSearchHaystack( GameModPack pack )
    {
        StringBuilder sb = new StringBuilder( 128 );
        String name = displayNameOf( pack );
        if ( name != null ) sb.append( name ).append( ' ' );
        String packVersion = pack.getPackVersion();
        if ( packVersion != null ) sb.append( packVersion ).append( ' ' );
        String mc = safeMinecraftVersion( pack );
        if ( mc != null ) sb.append( mc ).append( ' ' );
        String loaderName = safeLoaderName( pack );
        String loaderVersion = safeLoaderVersion( pack );
        if ( loaderName != null ) sb.append( loaderName ).append( ' ' );
        if ( loaderVersion != null ) sb.append( loaderVersion ).append( ' ' );
        // Include "forge" / "vanilla" type tags so a user can filter by pack
        // shape via the search box ("forge 1.12", "fabric") in addition to
        // the type selector dropdown.
        if ( pack.isVanillaVersion() ) sb.append( "vanilla " );
        else sb.append( "modpack " );
        return sb.toString().toLowerCase( Locale.ROOT );
    }

    /** Display name resolver — mirrors {@link #resolveDisplayName(GameModPack)} but
     *  exposed as an instance method for filtering / sorting in this class. */
    private static String displayNameOf( GameModPack p )
    {
        String name = resolveDisplayName( p );
        return name == null ? "" : name;
    }

    /**
     * Empty-state placeholder. Two flavors:
     * <ul>
     *   <li>"No mod packs installed yet" — when there's literally nothing in
     *       the installed list (fresh launcher, no filters applied).</li>
     *   <li>"No packs match those filters" — when filtering produced an empty
     *       slice from a non-empty source.</li>
     * </ul>
     */
    private Node buildEmptyState( String typeSel, String search )
    {
        VBox box = new VBox( 12 );
        box.setAlignment( Pos.CENTER );
        box.setPrefHeight( 320 );

        boolean hasAnyInstalled = !GameModPackManager.getInstalledModPacks().isEmpty()
                || !com.micatechnologies.minecraft.launcher.game.modpack.VanillaVersionManager
                       .getInstalledVersionIds().isEmpty();
        boolean filtersActive = vm.getBooleanFilter( FILTER_UPDATES_ONLY, false )
                || ( typeSel != null && !TYPE_ALL.equals( typeSel ) )
                || ( search != null && !search.isEmpty() );

        Label heading;
        Label sub;
        if ( hasAnyInstalled && filtersActive ) {
            heading = new Label( "No packs match those filters" );
            sub = new Label( search != null && !search.isEmpty()
                    ? "Clear the search box or switch filters to see more."
                    : "Switch filters to see more." );
        }
        else {
            heading = new Label( "No mod packs installed yet" );
            sub = new Label( "Click \"Library\" in the top bar to add a modpack from a URL." );
        }
        heading.getStyleClass().add( "heading-h1" );
        sub.getStyleClass().add( "muted" );

        box.getChildren().addAll( heading, sub );
        return box;
    }

    /**
     * Scrolls the matching pack card into view (if found). Used by the CLI launch path
     * ({@link com.micatechnologies.minecraft.launcher.LauncherCore#main} when invoked with a modpack argument).
     *
     * <p>Now also clears active filters and jumps to the page containing the
     * requested pack — without this, a pack that's on page 3 of a filtered view
     * would silently fail the "scroll into view" because its card wouldn't be in
     * the FlowPane at all. Clearing filters guarantees the pack is reachable
     * regardless of UI state.
     *
     * <p>No-op if the requested pack isn't installed.
     */
    public void selectModpack( GameModPack modPack ) {
        if ( modPack == null ) return;
        GUIUtilities.JFXPlatformRun( () -> {
            // Reset filters so the requested pack is guaranteed to be in the result set.
            if ( searchField != null ) searchField.clear();
            if ( typeFilter != null ) typeFilter.selectItem( TYPE_ALL );
            if ( recentlyUpdatedOnlyCheck != null && recentlyUpdatedOnlyCheck.isSelected() ) {
                // Unticking the checkbox fires the listener which already calls
                // rebuildCards() (via vm.setFilter → onStateChanged), so the
                // explicit rebuild below is redundant in that branch but harmless.
                recentlyUpdatedOnlyCheck.setSelected( false );
            }
            // Programmatically reset the type filter in the VM since
            // typeFilter.selectItem(...) above doesn't fire setOnAction.
            vm.setFilter( FILTER_TYPE, TYPE_ALL );
            rebuildCards();

            String wanted = modPack.getFriendlyName();
            String wantedName = modPack.getPackName();
            for ( Node n : modpackCardList.getChildren() ) {
                if ( n instanceof ModpackHeroCard card &&
                        ( ( wanted     != null && wanted.equals( card.pack.getFriendlyName() ) )
                       || ( wantedName != null && wantedName.equals( card.pack.getPackName() ) ) ) ) {
                    card.requestFocus();
                    // Best-effort scroll-into-view via the parent ScrollPane.
                    if ( modpackScrollPane != null ) {
                        double cardY = card.getBoundsInParent().getMinY();
                        double total = modpackCardList.getBoundsInLocal().getHeight();
                        if ( total > 0 ) {
                            modpackScrollPane.setVvalue( Math.min( 1.0, cardY / total ) );
                        }
                    }
                    return;
                }
            }
        } );
    }

    /**
     * Refreshes announcements + modpack manifest data WITHOUT taking over the screen
     * with the full-page progress GUI. Fired by both the F5 shortcut and the navbar
     * refresh icon.
     *
     * <p>The previous version called {@link GameModPackManager#fetchModPackInfo()}
     * which opens the progress GUI as a side effect, then re-navigated to the main
     * GUI — a heavy, screen-takeover refresh that interrupted whatever the user was
     * doing on the main menu. The main menu is already where the user lives; the
     * refresh should feel like background work.
     *
     * <p>New flow: surface the existing "Loading available packs…" status indicator
     * at the bottom of the screen (repurposed text), fire the same underlying
     * fetches with a {@code null} progress window so they don't try to manage their
     * own UI, then rebuild the card grid in place once the network calls complete.
     * The main menu stays visible the entire time.
     */
    private void refreshAvailablePacks()
    {
        // Quick visual feedback so the user knows their click registered. Repurpose
        // the existing bottom-bar indicator (same one used during the deferred
        // startup fetch) — re-text it to "Refreshing modpacks…" so the verb matches
        // a manual refresh rather than the passive startup language.
        GUIUtilities.JFXPlatformRun( () -> {
            if ( backgroundFetchLabel != null ) {
                backgroundFetchLabel.setText( LocalizationManager.get( "main.fetchLabel.refreshing" ) );
                backgroundFetchLabel.setVisible( true );
                backgroundFetchLabel.setManaged( true );
            }
            if ( refreshIcon != null ) {
                // Disable the icon to prevent spam-clicks while a refresh is in flight.
                refreshIcon.setDisable( true );
            }
        } );

        SystemUtilities.spawnNewTask( () -> {
            try {
                AnnouncementManager.checkAnnouncements();
                // Direct calls with a null progress window skip the screen-takeover
                // progress GUI that fetchModPackInfo() opens — these methods do their
                // own logging when the window is null, which is what we want for a
                // background refresh on the main menu.
                GameModPackManager.fetchInstalledModPacks( null );
                if ( !NetworkUtilities.isOffline() ) {
                    GameModPackManager.fetchAvailableModPacks( null );
                }
            }
            finally {
                GUIUtilities.JFXPlatformRun( () -> {
                    if ( backgroundFetchLabel != null ) {
                        backgroundFetchLabel.setVisible( false );
                        backgroundFetchLabel.setManaged( false );
                        // Reset to the canonical startup-fetch text in case the
                        // deferred startup fetch hasn't fired yet on this session.
                        backgroundFetchLabel.setText( LocalizationManager.get( "main.fetchLabel.loading" ) );
                    }
                    if ( refreshIcon != null ) {
                        refreshIcon.setDisable( false );
                    }
                    // Rebuild the card grid in place using the freshly-fetched data —
                    // no goToMainGui navigation, no scene flicker, no scroll-position
                    // reset.
                    rebuildCards();
                } );
            }
        } );
    }

    /**
     * Opens the expanded pack-detail modal for the given pack. No-op if the modal
     * hasn't been initialized yet (which shouldn't happen in practice — setup()
     * always builds it before any card is clickable).
     *
     * @param pack the pack to display in the modal
     */
    private void openDetailModal( GameModPack pack )
    {
        if ( detailModal != null ) {
            detailModal.show( pack );
        }
    }

    /**
     * Plays whichever pack is currently first in the card list (which, after {@link #populateModpackCards()}, is the
     * last-played pack — or the first installed pack if there's no history). Used by the ENTER key shortcut.
     */
    private void playLastSelectedModpack()
    {
        for ( Node n : modpackCardList.getChildren() ) {
            if ( n instanceof ModpackHeroCard card && !card.playBtn.isDisabled() ) {
                card.playBtn.fire();
                return;
            }
        }
    }

    // =========================================================================================
    //  Hero card view — one per modpack
    // =========================================================================================

    /**
     * Tile-shaped modpack hero card — image-on-top design (closer to micatechnologies.com/projects):
     * the upper portion shows the modpack's background image; the lower portion is a solid surface
     * with logo, name, chips, last-played hint, and Play / Website actions. Fixed width so the
     * parent FlowPane can lay them out as a responsive 1-4 column grid.
     */
    private final class ModpackHeroCard extends VBox
    {
        private static final double CARD_WIDTH  = 360;
        private static final double IMAGE_HEIGHT = 150;
        private static final double BTN_H       = 38;

        /** Current pack — mutated by {@link #bind}. All event handlers read this
         *  through {@code this.pack} (never a closure-captured local) so a rebind
         *  cleanly redirects clicks to the new pack without leaking the previous. */
        private GameModPack pack;

        // === Static node tree fields. Built once by the constructor; bind()
        //     mutates their content in place rather than re-creating them. ===
        private final Region bgLayer;
        private final HBox badgeRow;
        private final StackPane logoContainer;
        private final ImageView logo;
        private final Label name;
        private final HBox chips;
        private final Label played;
        private final MFXButton playBtn;
        private final MFXButton websiteBtn;
        private final PauseTransition singleClickTimer;

        ModpackHeroCard( GameModPack initialPack )
        {
            getStyleClass().add( "heroCardShell" );
            // Width is fixed so FlowPane can wrap on a clean grid, but height is content-driven —
            // we tried capping at 320 and the buttons overflowed into the next row.
            setPrefWidth( CARD_WIDTH );
            setMinWidth( CARD_WIDTH );
            setMaxWidth( CARD_WIDTH );
            setSpacing( 0 );

            // Bitmap-cache the whole card so the ScrollPane reuses the rasterized
            // bitmap during vertical scrolling instead of re-rendering each card's
            // gaussian dropshadow, rounded image clip, and CSS bg-image every
            // frame. CacheHint.DEFAULT (NOT SPEED) is deliberate — SPEED treats
            // the bitmap as static and won't refresh it when the bgLayer's CSS
            // bg-image completes its async load, which manifested as packs
            // randomly rendering with the procedural gradient even though the
            // bg image was on disk and the URL had been applied. DEFAULT lets
            // JavaFX invalidate + re-render the cache when descendant content
            // changes (image loads, logo swap, etc.). Cost vs SPEED is bilinear
            // interpolation during scroll-translate, which is barely
            // perceptible at our card sizes.
            setCache( true );
            setCacheHint( CacheHint.DEFAULT );

            // ----- Top half: modpack background image -----
            StackPane imageBox = new StackPane();
            imageBox.getStyleClass().add( "heroCardImage" );
            imageBox.setPrefHeight( IMAGE_HEIGHT );
            imageBox.setMinHeight( IMAGE_HEIGHT );
            imageBox.setMaxHeight( IMAGE_HEIGHT );
            // Clip to rounded top corners. JavaFX background-radius alone doesn't clip
            // child ImageView/Region overflows — an explicit Rectangle clip does.
            Rectangle imageClip = new Rectangle( CARD_WIDTH, IMAGE_HEIGHT );
            imageClip.setArcWidth( 28 );    // 14 px radius * 2
            imageClip.setArcHeight( 28 );
            imageClip.heightProperty().bind( imageBox.heightProperty() );
            imageClip.widthProperty().bind( imageBox.widthProperty() );
            imageBox.setClip( imageClip );

            bgLayer = new Region();
            bgLayer.getStyleClass().add( "heroBackground" );

            // Subtle veil along the bottom of the image so the logo reads against it.
            Region imageVeil = new Region();
            imageVeil.getStyleClass().add( "heroCardImageVeil" );

            // Optional badge row floats over the top-right of the image; contents
            // are rebuilt per-bind since badges depend on pack state.
            badgeRow = new HBox( 6 );
            badgeRow.setAlignment( Pos.TOP_RIGHT );
            badgeRow.setPadding( new javafx.geometry.Insets( 10, 12, 0, 0 ) );

            imageBox.getChildren().addAll( bgLayer, imageVeil, badgeRow );

            // Pack logo overlaps the image/content boundary on the left. The container has a
            // rounded border in CSS; clip its inner ImageView so the bitmap respects the
            // rounded corners instead of overflowing as a square.
            logoContainer = new StackPane();
            logoContainer.getStyleClass().add( "heroPackLogoContainer" );
            logoContainer.setMinSize( 72, 72 );
            logoContainer.setMaxSize( 72, 72 );
            logo = new ImageView();
            logo.setFitWidth( 68 );
            logo.setFitHeight( 68 );
            logo.setPreserveRatio( true );
            Rectangle logoClip = new Rectangle( 68, 68 );
            logoClip.setArcWidth( 16 );
            logoClip.setArcHeight( 16 );
            logo.setClip( logoClip );
            logoContainer.getChildren().add( logo );
            logoContainer.setTranslateY( -36 );  // overlap the image

            // ----- Bottom half: content surface -----
            VBox info = new VBox( 6 );
            info.getStyleClass().add( "heroCardBody" );
            info.setAlignment( Pos.TOP_LEFT );
            info.setPadding( new javafx.geometry.Insets( 0, 18, 16, 18 ) );

            name = new Label();
            name.getStyleClass().addAll( "heading-h2", "heroCardTitle" );
            name.setWrapText( true );

            chips = new HBox( 6 );
            chips.setAlignment( Pos.CENTER_LEFT );

            played = new Label();
            played.getStyleClass().add( "heroLastPlayedSurface" );

            info.getChildren().addAll( logoContainer, name, chips, played );
            VBox.setVgrow( info, Priority.ALWAYS );

            // ----- Action row at the bottom -----
            HBox actions = new HBox( 8 );
            actions.setAlignment( Pos.CENTER_LEFT );
            actions.setPadding( new javafx.geometry.Insets( 0, 18, 16, 18 ) );

            // Both buttons pin to exactly 38 px tall via min + pref + max so they
            // visually align in the row. The CSS rules layered on top of MFXButton
            // ( .heroPlayBtn uses a bolder/larger font + heavier padding than the
            // default .mfx-button skin .heroCardSecondaryBtn inherits ) would
            // otherwise size the two buttons by their intrinsic content, giving
            // the play button a slightly taller box than its neighbour.
            playBtn = new MFXButton( "Play" );
            playBtn.getStyleClass().addAll( "primary", "heroPlayBtn" );
            playBtn.setMinHeight( BTN_H );
            playBtn.setPrefHeight( BTN_H );
            playBtn.setMaxHeight( BTN_H );
            playBtn.setMaxWidth( Double.MAX_VALUE );
            HBox.setHgrow( playBtn, Priority.ALWAYS );
            // Action handler reads this.pack each fire so a rebind re-targets it to
            // the current pack rather than the one captured at card construction.
            playBtn.setOnAction( e -> startPlay( this.pack ) );

            websiteBtn = new MFXButton( "Website" );
            websiteBtn.getStyleClass().add( "heroCardSecondaryBtn" );
            websiteBtn.setMinHeight( BTN_H );
            websiteBtn.setPrefHeight( BTN_H );
            websiteBtn.setMaxHeight( BTN_H );
            websiteBtn.setPrefWidth( 96 );
            websiteBtn.setOnAction( e -> openModpackWebsite( this.pack ) );

            actions.getChildren().addAll( playBtn, websiteBtn );

            // Assemble
            getChildren().addAll( imageBox, info, actions );

            // Card-level interactions. Cursor + click handler are tree-wide; only
            // the context menu's contents are per-pack and get rebuilt in bind().
            setCursor( Cursor.HAND );

            // Click behavior:
            //   • single primary click → open the expanded modpack detail modal
            //   • double primary click  → quick-launch the pack (preserved from the
            //     pre-modal flow so power users don't lose their muscle memory)
            //
            // JavaFX fires click-count=1 immediately and click-count=2 after the
            // platform's double-click interval. Without a delay, a real double-click
            // would briefly flash the modal open between the two events. The
            // PauseTransition defers the single-click action by ~220ms — long enough
            // for the second click to arrive and cancel the timer, short enough that
            // a single click still feels responsive. Buttons inside the card
            // (Play / Website) consume their own events, so the card-level handler
            // never fires for those.
            singleClickTimer = new PauseTransition( Duration.millis( 220 ) );
            singleClickTimer.setOnFinished( ev -> openDetailModal( this.pack ) );
            setOnMouseClicked( ev -> {
                if ( ev.getButton() != MouseButton.PRIMARY ) return;
                int count = ev.getClickCount();
                if ( count == 1 ) {
                    singleClickTimer.playFromStart();
                }
                else if ( count == 2 ) {
                    singleClickTimer.stop();
                    if ( !playBtn.isDisabled() ) {
                        playBtn.fire();
                    }
                }
            } );

            // Initial bind so the card has real content by the time the constructor returns.
            bind( initialPack );
        }

        /**
         * Switches this card to display {@code newPack}. Updates every per-pack
         * visual element (badge row, background gradient + image, logo, name, chip
         * list, last-played label, button disable state, context menu) but leaves
         * the underlying scene-graph nodes intact for reuse.
         *
         * <p>Called by the constructor with the initial pack and again by
         * {@link MCLauncherMainGui#rebuildCards()} whenever a pooled card is
         * reassigned during page / filter / sort changes — keeping the same VBox
         * instance alive saves the per-card scene-graph allocation + CSS-apply
         * cost on every rebuild, which is what makes larger page sizes (and
         * eventual CurseForge / Modrinth import libraries) responsive.</p>
         */
        void bind( GameModPack newPack )
        {
            this.pack = newPack;

            // Logo image — drives both the logo ImageView and the bgLayer's
            // derived-color gradient fallback. Re-resolved every bind in case the
            // pack swapped (e.g. page-nav) or the same pack's image URL changed
            // (e.g. revalidate-against-network upgraded a stub).
            Image packLogoImage = resolveLogoImage( newPack );
            logo.setImage( packLogoImage );
            // Fade the logo in once the off-thread image-loader delivers the bytes.
            // The rounded logoContainer's own styling (border + bg-color from
            // .heroPackLogoContainer) acts as the placeholder during the fade.
            ImageFadeIn.apply( logo );

            // Background layer — always paint the procedural gradient first so it
            // acts as the placeholder behind a remote -fx-background-image while
            // the latter is still loading off the network. Clear any prior bind's
            // inline -fx-background-image AND the dynamic styleClasses
            // applyDynamicBackground adds (heroBackgroundDefaultVanilla /
            // heroBackgroundDefaultForge) so they don't accumulate across rebinds
            // and bleed previous-pack styling into the new state:
            //   • vanilla versions get a sky → grass gradient
            //   • modded packs with a logo get a gradient derived from the logo's
            //     dominant color, so each pack feels visually individuated
            //   • modded packs without a logo fall back to a Forge-themed gradient
            //     (dark anvil + warm forge-fire glow)
            bgLayer.setStyle( null );
            bgLayer.getStyleClass().removeAll( "heroBackgroundDefaultVanilla",
                                                 "heroBackgroundDefaultForge" );
            applyDynamicBackground( bgLayer, newPack, packLogoImage );
            // User opt-out: gradient-only mode skips the bg-image overlay entirely.
            // The procedural gradient + the dynamic logo-color derivation still
            // run so cards still feel individuated, just without the imagery.
            boolean showBackgrounds = ConfigManager.getShowPackBackgrounds();
            String bgUrl = showBackgrounds ? resolveBackgroundUrl( newPack ) : null;
            if ( bgUrl != null ) {
                // Append rather than replace so the gradient bg-color from
                // applyDynamicBackground stays visible through any transparent
                // pixels of the bg-image and through the entire fetch window
                // until the bytes arrive.
                String existing = bgLayer.getStyle() == null ? "" : bgLayer.getStyle();
                bgLayer.setStyle( existing + " -fx-background-image: url('" + bgUrl + "');" );
            }
            else if ( showBackgrounds && newPack.hasCustomBackground() ) {
                // Pack declares a custom background but the cache file isn't
                // on disk yet — gradient is showing as a temporary fallback.
                // Spawn a background task to fetch it via cacheImages(),
                // then re-apply the CSS background-image when the file
                // lands. Without this, packs whose bg cache was deleted /
                // never warmed up (fresh install, hash bumped in a
                // recent manifest revalidate, OS-level cache wipe) would
                // permanently render with the gradient until something
                // else triggered a rebind. The pack-identity guard inside
                // the FX continuation makes sure a slow-completing fetch
                // for the previously-bound pack doesn't paint over a
                // newer pack's bg.
                final GameModPack capturedPack = newPack;
                SystemUtilities.spawnNewTask( () -> {
                    try {
                        capturedPack.cacheImages();
                    }
                    catch ( Throwable ignored ) {
                        return;
                    }
                    String path = capturedPack.getPackBackgroundFilepathRaw();
                    if ( path == null ) return;
                    File f = new File( path );
                    if ( !f.exists() || f.length() == 0 ) return;
                    String fetchedUrl = f.toURI().toString();
                    GUIUtilities.JFXPlatformRun( () -> {
                        if ( this.pack != capturedPack ) return;
                        String currentStyle = bgLayer.getStyle() == null ? "" : bgLayer.getStyle();
                        bgLayer.setStyle( currentStyle + " -fx-background-image: url('" + fetchedUrl + "');" );
                    } );
                } );
            }

            // Logo equivalent: if the pack declares a custom logo but the
            // cache file wasn't on disk at sync-resolve time (so we fell
            // back to the bundled default URL above), kick off the same
            // async warm-up so the real logo pops in shortly after.
            if ( newPack.hasCustomLogo() ) {
                String logoPath = newPack.getPackLogoFilepathRaw();
                File logoFile = logoPath == null ? null : new File( logoPath );
                if ( logoFile == null || !logoFile.exists() ) {
                    final GameModPack capturedPack = newPack;
                    SystemUtilities.spawnNewTask( () -> {
                        try {
                            capturedPack.cacheImages();
                        }
                        catch ( Throwable ignored ) {
                            return;
                        }
                        String p = capturedPack.getPackLogoFilepathRaw();
                        if ( p == null ) return;
                        File f = new File( p );
                        if ( !f.exists() ) return;
                        Image fresh = new Image( f.toURI().toString(), true );
                        GUIUtilities.JFXPlatformRun( () -> {
                            if ( this.pack != capturedPack ) return;
                            logo.setImage( fresh );
                            ImageFadeIn.apply( logo );
                        } );
                    } );
                }
            }

            // Transparent-edge detection for the logo container. Clear the
            // .logoTransparent class first so a previous pack's transparent-edged
            // logo doesn't keep the styling stripped on a new pack with an opaque one.
            logoContainer.getStyleClass().remove( "logoTransparent" );
            LogoTransparencyDetector.detectAsync( packLogoImage, isTransparent -> {
                // Guard against rebind-during-async: only flip the class if the
                // card still represents the pack whose logo we asked about.
                if ( isTransparent && this.pack == newPack ) {
                    logoContainer.getStyleClass().add( "logoTransparent" );
                }
            } );

            // Badge row — rebuilt from scratch on every bind. Only 0–2 badges in practice.
            badgeRow.getChildren().clear();
            if ( newPack.getPackUnstable() ) badgeRow.getChildren().add( buildChip( "Beta", "stat-chip-warn" ) );
            // "Recently updated" matches the terminology the Library screen uses on
            // installed-pack cards — keeping the two screens phrased the same way so
            // users learn one vocabulary, not two. Avoids the ambiguous imperative
            // ("Update!") that "Update" alone would imply.
            if ( newPack.isUpdateAvailable() ) badgeRow.getChildren().add( buildChip( "Recently updated", "stat-chip-success" ) );

            // Name + chips
            name.setText( resolveDisplayName( newPack ) );
            chips.getChildren().clear();
            String mc = safeMinecraftVersion( newPack );
            String loaderName = safeLoaderName( newPack );
            String loaderVersion = safeLoaderVersion( newPack );
            String packVersion = newPack.getPackVersion();
            // "Vanilla" chip — placed first so it's the first visual cue that this card
            // is a stock Minecraft version, not a modded pack.
            if ( newPack.isVanillaVersion() ) {
                chips.getChildren().add( buildChip( "Vanilla" ) );
            }
            // "Minecraft 1.20.4" (not "MC 1.20.4") — full name reads clearer and the chip
            // has room. Minecraft is a trademark of Mojang Synergies AB / Microsoft;
            // attribution is surfaced in the Settings → About / Attributions section.
            if ( mc != null && !mc.isBlank() ) chips.getChildren().add( buildChip( "Minecraft " + mc ) );
            if ( loaderName != null && loaderVersion != null && !loaderVersion.isBlank() ) {
                String shortLoader = loaderVersion.contains( "-" )
                        ? loaderVersion.substring( loaderVersion.lastIndexOf( '-' ) + 1 )
                        : loaderVersion;
                chips.getChildren().add( buildChip( loaderName + " " + shortLoader ) );
            }
            // Pack-version chip: skip when it would just duplicate the MC version chip.
            // Vanilla packs by construction have packVersion == mcVersion, so this drops
            // the redundant "v1.21.11" alongside "Minecraft 1.21.11". Modded packs with
            // a distinct pack version (e.g. "v26.5.2" vs "Minecraft 1.12.2") keep theirs.
            if ( packVersion != null && !packVersion.isBlank() && !packVersion.equals( mc ) ) {
                chips.getChildren().add( buildChip( "v" + packVersion ) );
            }

            // Last-played hint. Use isNeverPlayed() rather than string-comparing
            // the formatted output — the formatted string is now localized, so
            // a string-compare would only work for English-locale users.
            if ( newPack.isNeverPlayed() ) {
                played.setText( LocalizationManager.get( "main.card.neverPlayed" ) );
            }
            else {
                played.setText( LocalizationManager.format(
                        "main.card.lastPlayed", newPack.getLastPlayedFormatted().toLowerCase() ) );
            }

            // Button enable / disable state
            playBtn.setDisable( AnnouncementManager.getDisableGameplay() );
            websiteBtn.setDisable( newPack.getPackURL() == null || newPack.getPackURL().isBlank() );

            // Context menu — rebuilt per pack since it embeds pack-specific actions.
            ContextMenu menu = buildPackContextMenu( newPack, stage,
                                                      MCLauncherMainGui.this::showBackgroundStatus,
                                                      MCLauncherMainGui.this::hideBackgroundStatus,
                                                      MCLauncherMainGui.this::rebuildCards );
            setOnContextMenuRequested( e -> menu.show( this, e.getScreenX(), e.getScreenY() ) );
        }

        private void startPlay( GameModPack pack ) {
            ConfigManager.setLastModPackSelected( pack.getPackName() );
            SystemUtilities.spawnNewTask( () -> {
                Platform.setImplicitExit( false );
                SystemUtilities.spawnNewTask( () -> DiscordRpcUtility.setGamePresence( pack ) );
                LauncherCore.play( pack, () -> GUIUtilities.JFXPlatformRun( () -> {
                    try {
                        Objects.requireNonNull( MCLauncherGuiController.getTopStageOrNull() ).show();
                        MCLauncherGuiController.goToMainGui();
                        MCLauncherGuiController.requestFocus();
                    }
                    catch ( Exception e ) {
                        Logger.logError( "Unable to load main GUI due to an incomplete response from the GUI subsystem." );
                        Logger.logThrowable( e );
                        LauncherCore.closeApp();
                    }
                } ) );
            } );
        }

        private Label buildChip( String text, String... extraClasses ) {
            Label chip = new Label( text );
            chip.getStyleClass().add( "stat-chip" );
            for ( String c : extraClasses ) chip.getStyleClass().add( c );
            return chip;
        }

        private Label buildChip( String text ) {
            return buildChip( text, new String[ 0 ] );
        }
    }

    // ---------- helpers used by the card ----------

    private static String resolveDisplayName( GameModPack pack ) {
        // Vanilla packs use the standard friendly-name template "%s: %s" which expands to
        // e.g. "Minecraft 1.21.11: 1.21.11" — the pack name already contains the version,
        // so the duplicated suffix is just noise. Use the bare pack name in that case.
        if ( pack.isVanillaVersion() ) {
            String name = pack.getPackName();
            if ( name != null && !name.isBlank() ) return name;
        }
        String name = pack.getFriendlyName();
        if ( name == null || name.isBlank() ) name = pack.getPackName();
        return name != null ? name : "Unnamed Pack";
    }

    /** Resolves the pack's own background image to a file URL <em>only if it's
     *  already on disk</em>. Critically, this does NOT call
     *  {@link GameModPack#getPackBackgroundFilepath} — that path triggers a
     *  synchronous network-backed cacheImages call which, when invoked from
     *  the FX thread by {@link ModpackHeroCard#bind}, blocks the renderer for
     *  the duration of the download and (worse) leaves the bg file
     *  half-written for the brief moment between style-set and CSS image
     *  load. The result was random cards rendering with the gradient
     *  fallback when a fresh manifest had bumped the bg SHA-1 between
     *  launches. Now we use the side-effect-free raw path and let the card
     *  async-warm the cache on a background thread, then re-apply the URL
     *  once the file lands.
     *
     *  <p>{@code pack.hasCustomBackground()} is the canonical "does this pack
     *  ship its own image" signal. The local-cache file at
     *  {@code getPackBackgroundFilepath()} exists for default-image packs too
     *  (the environment downloads {@code MODPACK_DEFAULT_BG_URL} into a local
     *  cache), so checking just file existence would wrongly point at the
     *  cached-bundled-default and skip the procedural-background path. We
     *  gate on {@code hasCustomBackground} first.</p>
     */
    /** Package-private so {@link MCLauncherGameLibraryGui}'s LibraryCard can
     *  reuse the same "is the bg file already on disk?" check before overlaying
     *  it on top of the procedural gradient. Browse-view doesn't trigger an
     *  async download for missing files (would be heavy on cold cache); it just
     *  uses what's already cached, falling through to the gradient otherwise. */
    static String resolveBackgroundUrl( GameModPack pack ) {
        return ModpackImageResolver.resolveBackgroundUrlFromDisk( pack );
    }

    // =========================================================================================
    //  Dynamic background derivation (no-image fallback)
    // =========================================================================================

    /**
     * Sets up the bg-layer's visual when the pack doesn't ship its own background image.
     * Three branches:
     * <ul>
     *     <li><b>Vanilla version:</b> apply a static sky → grass CSS class so the card
     *         visually signals "this is a vanilla MC version" at a glance.</li>
     *     <li><b>Modded pack with a logo:</b> sample the logo's dominant color and build
     *         a tinted linear gradient from it. Each pack ends up with a bg that matches
     *         its branding (red logo → reddish bg, green logo → greenish bg, etc).</li>
     *     <li><b>Modded pack without a logo (rare):</b> apply a static Forge-themed CSS
     *         class — dark anvil tones with a warm forge-fire glow at the bottom.</li>
     * </ul>
     *
     * <p>Logo sampling is asynchronous-safe: if the logo {@link Image} hasn't finished
     * loading yet (the launcher requests background-loading), we install the Forge default
     * up front and replace it with the sampled gradient once {@code progressProperty} hits 1.0.</p>
     */
    /** Package-private so {@link MCLauncherGameLibraryGui}'s LibraryCard can reuse the same
     *  procedural-background logic (vanilla sky-grass, modded logo-derived gradient, default
     *  Forge) without duplicating the histogram code.
     *
     *  <p>All setStyle calls here go through {@link #setBgLayerGradient} so any existing
     *  {@code -fx-background-image} declaration (set by {@link ModpackHeroCard#bind} for
     *  packs that ship a custom bg image) is preserved. The fully-qualified inline style
     *  is then "gradient color base + image overlay" — replacing it with just the gradient
     *  (as a previous version did via {@code bgLayer.setStyle(buildGradientStyle(...))})
     *  wiped the bg-image and made packs flash the image briefly on first load then revert
     *  to the gradient once the logo's progress listener fired.</p> */
    static void applyDynamicBackground( Region bgLayer, GameModPack pack, Image logoImage )
    {
        if ( pack.isVanillaVersion() ) {
            // Vanilla cards adopt the launcher's own logo palette so the
            // gradient stays on-brand instead of stuck on the hardcoded
            // "Minecraft landscape" gradient. The launcher logo never
            // changes per session so this samples once + memoises.
            Color[] launcherPalette = getLauncherLogoPalette();
            if ( launcherPalette != null && launcherPalette.length > 0 ) {
                setBgLayerGradientFromPalette( bgLayer, launcherPalette );
            }
            else {
                // Resource missing or fully transparent — keep the
                // legacy hardcoded gradient as a graceful fallback.
                bgLayer.getStyleClass().add( "heroBackgroundDefaultVanilla" );
            }
            return;
        }
        if ( logoImage == null ) {
            bgLayer.getStyleClass().add( "heroBackgroundDefaultForge" );
            return;
        }

        // Up to 4-color palette — produces a richer multi-stop gradient
        // when the logo carries that many distinct hues. Falls back to
        // however many were sampled (1-3) for monochromatic logos.
        final int PALETTE_TARGET = 4;

        // Cache hit path — when the user navigates away and back, the logo file path is
        // stable per pack, so we skip the histogram work entirely.
        String cacheKey = packLogoCacheKey( pack );
        Color[] cachedPalette = ( cacheKey != null ) ? DOMINANT_PALETTE_CACHE.get( cacheKey ) : null;
        if ( cachedPalette != null && cachedPalette.length > 0 ) {
            setBgLayerGradientFromPalette( bgLayer, cachedPalette );
            return;
        }
        // Legacy 2-color cache fallback — the prefetch pre-populated this
        // before the palette cache existed; let it serve a 2-color gradient
        // immediately while a richer sample, if available, takes over on
        // the next visit.
        DominantColors cached2 = ( cacheKey != null ) ? DOMINANT_COLOR_CACHE.get( cacheKey ) : null;
        if ( cached2 != null ) {
            setBgLayerGradient( bgLayer, cached2 );
            // Don't return yet — also kick off an n-color sample so
            // the next render can use the richer palette.
        }

        // Logo loaded: sample immediately. May still return null if the logo is fully
        // transparent / monochrome — fall through to Forge default in that case.
        if ( logoImage.getProgress() >= 1.0 && !logoImage.isError() ) {
            Color[] fresh = computeDominantColorPalette( logoImage, PALETTE_TARGET );
            if ( fresh != null && fresh.length > 0 ) {
                if ( cacheKey != null ) {
                    DOMINANT_PALETTE_CACHE.put( cacheKey, fresh );
                    Color sec = fresh.length >= 2 ? fresh[ 1 ] : null;
                    DOMINANT_COLOR_CACHE.putIfAbsent( cacheKey,
                                                      new DominantColors( fresh[ 0 ], sec ) );
                }
                setBgLayerGradientFromPalette( bgLayer, fresh );
                return;
            }
            if ( cached2 == null ) {
                bgLayer.getStyleClass().add( "heroBackgroundDefaultForge" );
            }
            return;
        }

        // Logo still loading: show the Forge default while we wait, swap in the derived
        // gradient as soon as the load completes. Listener fires on the FX thread so it's
        // safe to mutate styleClass / setStyle directly. The result is cached so a future
        // re-construction of this card doesn't repeat the sample.
        if ( cached2 == null ) {
            bgLayer.getStyleClass().add( "heroBackgroundDefaultForge" );
        }
        logoImage.progressProperty().addListener( ( obs, oldVal, newVal ) -> {
            if ( newVal.doubleValue() < 1.0 || logoImage.isError() ) {
                return;
            }
            Color[] fresh = computeDominantColorPalette( logoImage, PALETTE_TARGET );
            if ( fresh != null && fresh.length > 0 ) {
                if ( cacheKey != null ) {
                    DOMINANT_PALETTE_CACHE.put( cacheKey, fresh );
                    Color sec = fresh.length >= 2 ? fresh[ 1 ] : null;
                    DOMINANT_COLOR_CACHE.putIfAbsent( cacheKey,
                                                      new DominantColors( fresh[ 0 ], sec ) );
                }
                bgLayer.getStyleClass().remove( "heroBackgroundDefaultForge" );
                setBgLayerGradientFromPalette( bgLayer, fresh );
            }
        } );
    }

    /** Applies a {@link #buildGradientStyle gradient declaration} to {@code bgLayer}
     *  while preserving any existing {@code -fx-background-image} declaration in its
     *  inline style. Critical because the logo-progress listener and the cached-color
     *  fast-path both fire LATER than {@link ModpackHeroCard#bind}'s bg-image overlay,
     *  and a naive {@code setStyle(gradient)} call would wipe the image. */
    private static void setBgLayerGradient( Region bgLayer, DominantColors colors )
    {
        String gradientPart = buildGradientStyle( colors );
        String existingBgImage = extractBackgroundImageDeclaration( bgLayer.getStyle() );
        if ( existingBgImage != null ) {
            bgLayer.setStyle( gradientPart + " " + existingBgImage );
        }
        else {
            bgLayer.setStyle( gradientPart );
        }
    }

    /** Extracts the {@code -fx-background-image: url('...');} fragment (if any) out of
     *  a JavaFX inline-style string. Returns the full declaration including the trailing
     *  semicolon, or {@code null} when no bg-image declaration is present. Used by
     *  {@link #setBgLayerGradient} to preserve the image overlay across gradient
     *  rewrites. */
    private static String extractBackgroundImageDeclaration( String inlineStyle )
    {
        if ( inlineStyle == null || inlineStyle.isEmpty() ) return null;
        int idx = inlineStyle.indexOf( "-fx-background-image" );
        if ( idx < 0 ) return null;
        int end = inlineStyle.indexOf( ';', idx );
        if ( end < 0 ) {
            // No trailing semicolon — take the rest of the string and add one.
            return inlineStyle.substring( idx ).trim() + ";";
        }
        return inlineStyle.substring( idx, end + 1 );
    }

    // -----------------------------------------------------------------------------------------
    //  Dominant-color extraction + cache
    // -----------------------------------------------------------------------------------------

    /** Process-wide cache of dominant-color results, keyed by logo file path. Survives FXML
     *  reloads (main GUI re-creation on navigation) so the user-perceived "every card has its
     *  bg right away" is real after the first visit to the main menu. The cache is bounded by
     *  the number of distinct logo files the user has installed (typically 5–50) and entry
     *  size is ~64 bytes — memory cost is trivial. Logo file paths only change on pack
     *  reinstall, so stale entries are extremely rare and never incorrect (they're just
     *  recomputed once the file path differs). */
    private static final java.util.concurrent.ConcurrentHashMap< String, DominantColors >
            DOMINANT_COLOR_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    /** N-color palette cache, parallel to {@link #DOMINANT_COLOR_CACHE}.
     *  Holds up to 4 colors per logo for the richer gradient renderer +
     *  the RGB cycle effect; the 2-color sibling cache stays as-is for
     *  callers that only need primary + secondary (in-game RGB effect,
     *  bg-image gradient fast path before n-color landed). Same string
     *  key — typically the pack's logo URL. */
    private static final java.util.concurrent.ConcurrentHashMap< String, Color[] >
            DOMINANT_PALETTE_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    /** Cache-key stand-in for the bundled launcher logo, used by the
     *  vanilla-version dynamic gradient. The logo is a single bundled
     *  resource so there's no per-pack URL to key on — this sentinel
     *  lets one cache entry serve every vanilla card. */
    private static final String LAUNCHER_LOGO_PALETTE_KEY = "__mica_launcher_logo__";

    /** Launcher logo resource path. The vanilla-version card samples
     *  dominant colors from this image at startup so vanilla version
     *  hero cards adopt the launcher's own brand palette instead of
     *  the hardcoded "Minecraft landscape" gradient. */
    private static final String LAUNCHER_LOGO_RESOURCE_PATH = "/micaminecraftlauncher.png";

    /** Cache key for a pack's logo. Prefers the URL (stable per-manifest, doesn't trigger
     *  the environment's image-cache download) before falling back to file path / pack name.
     *  Using the URL-first ordering means the library prefetch — which only has URLs — and
     *  the main-menu sampling — which has files — write the SAME cache entry per pack. */
    private static String packLogoCacheKey( GameModPack pack )
    {
        if ( pack == null ) {
            return null;
        }
        String url = pack.getPackLogoURL();
        if ( url != null && !url.isBlank() ) {
            return url;
        }
        try {
            String path = pack.getPackLogoFilepath();
            if ( path != null && !path.isBlank() ) {
                return path;
            }
        }
        catch ( Exception ignored ) { /* fall through */ }
        return pack.getPackName();
    }

    /**
     * Pre-warms the dominant-color cache for every modpack the manifest knows about, so by
     * the time the user opens the Game Library the gradients render instantly instead of
     * histogram-sampling 30+ logos one-after-another on the FX thread.
     *
     * <p>Runs on a background worker thread. For each available pack:</p>
     * <ol>
     *     <li>Skip if the URL-keyed cache already has an entry.</li>
     *     <li>Construct a {@link Image} synchronously (we're off the FX thread, so blocking
     *         is fine) from the pack's logo URL.</li>
     *     <li>Sample dominant colors via the existing histogram path.</li>
     *     <li>Stash the result in {@link #DOMINANT_COLOR_CACHE} keyed by URL.</li>
     * </ol>
     *
     * <p>The Library GUI's per-card resolution reads from the same cache via
     * {@link #packLogoCacheKey} (URL-preferred), so the prefetch and the main-menu
     * single-card sampling both hit the same cache entries.</p>
     *
     * <p>Call this from launcher session startup, after {@code GameModPackManager.fetchModPackInfo()}
     * has populated the available-modpacks list. Idempotent — re-running on an already-warm
     * cache is cheap.</p>
     */
    public static void prefetchAvailableModpackBackgrounds()
    {
        com.micatechnologies.minecraft.launcher.utilities.SystemUtilities.spawnNewTask( () -> {
            java.util.List< GameModPack > available;
            try {
                available = com.micatechnologies.minecraft.launcher.game.modpack.GameModPackManager.getAvailableModPacks();
            }
            catch ( Exception | Error e ) {
                return;
            }
            if ( available == null ) {
                return;
            }
            for ( GameModPack pack : available ) {
                if ( pack == null ) continue;
                String url = pack.getPackLogoURL();
                if ( url == null || url.isBlank() ) continue;
                if ( DOMINANT_COLOR_CACHE.containsKey( url ) ) continue;

                try {
                    // Synchronous Image construction off the FX thread — the loader runs on
                    // *this* worker thread rather than spawning a JavaFX image-loader task,
                    // which is what we want because we're prefetching specifically to avoid
                    // FX-thread work later.
                    Image img = new Image( url );
                    if ( img.isError() ) continue;
                    // Compute the n-color palette directly; the 2-color
                    // cache entry derives from its first two slots so
                    // legacy callers (in-game RGB effect, single-color
                    // fallback) still hit the cache too.
                    Color[] palette = computeDominantColorPalette( img, 4 );
                    if ( palette != null && palette.length > 0 ) {
                        DOMINANT_PALETTE_CACHE.put( url, palette );
                        Color sec = palette.length >= 2 ? palette[ 1 ] : null;
                        DOMINANT_COLOR_CACHE.put( url, new DominantColors( palette[ 0 ], sec ) );
                    }
                }
                catch ( Exception | Error ignored ) { /* skip on any failure */ }
            }
        } );
    }

    /** Two-color result of the histogram sample. {@code secondary} is null when the logo is
     *  too monochrome to identify a second distinct hue — the gradient renderer then derives
     *  the second stop algorithmically. */
    private record DominantColors( Color primary, Color secondary )
    {
        boolean hasSecondary() { return secondary != null; }
    }

    /**
     * Public-API counterpart to {@link #computeDominantColors} — extracts
     * the same two-color result for {@code pack}'s logo, using the
     * process-wide dominant-color cache so a pack already shown on the
     * main menu hero card is a cache hit. Returns {@code null} when the
     * pack is vanilla, has no logo on disk, or the histogram couldn't
     * find any saturated hues (e.g. fully transparent logo).
     *
     * <p>Used by {@code RgbIntegration} to derive the in-game RGB
     * effect's color palette from the selected modpack.</p>
     *
     * @since 2026.5
     */
    public static Color[] sampleDominantPackColors( GameModPack pack )
    {
        // Backwards-compatible 2-color wrapper. Most callers want exactly
        // the two-color gradient inputs the hero card and in-game effect
        // were originally designed for; the n-color path is for the RGB
        // cycle effect.
        Color[] palette = sampleDominantPackPalette( pack, 2 );
        if ( palette == null ) return null;
        // Always return a 2-element array — null in slot 1 when the
        // logo is too monochrome to find a second hue. Mirrors the
        // original {primary, secondary} contract.
        Color primary = palette.length > 0 ? palette[ 0 ] : null;
        Color secondary = palette.length > 1 ? palette[ 1 ] : null;
        return new Color[]{ primary, secondary };
    }

    /**
     * Multi-color variant of {@link #sampleDominantPackColors}. Returns
     * up to {@code count} distinct dominant colors from the modpack's
     * logo. Same saturation-weighted histogram, same minimum-distance
     * filter between successive picks. Returns null when the logo
     * can't be loaded; returns a possibly-shorter array when the logo
     * doesn't have {@code count} sufficiently-distinct hues.
     *
     * <p>Used by the {@code CycleEffect} in the RGB subsystem — looping
     * through 3-4 dominant pack colors produces a per-modpack signature
     * RGB animation that the user can recognise at a glance.</p>
     *
     * @param pack  the modpack whose logo to sample.
     * @param count maximum colors to return. Must be ≥ 1.
     */
    public static Color[] sampleDominantPackPalette( GameModPack pack, int count )
    {
        if ( pack == null || pack.isVanillaVersion() ) return null;
        if ( count < 1 ) return null;

        // Cache hit path. Same key as the 2-color cache so prefetched
        // entries and on-demand entries share storage. If the cached
        // palette already has >= count entries we slice; if fewer, we
        // fall through to a re-sample with the larger ask (logo could
        // have been re-sampled with a smaller count previously).
        String cacheKey = packLogoCacheKey( pack );
        Color[] cachedPalette = ( cacheKey != null ) ? DOMINANT_PALETTE_CACHE.get( cacheKey ) : null;
        if ( cachedPalette != null && cachedPalette.length >= count ) {
            return java.util.Arrays.copyOf( cachedPalette, count );
        }

        String logoPath = null;
        try { logoPath = pack.getPackLogoFilepath(); }
        catch ( Throwable ignored ) { /* fall through to null below */ }
        if ( logoPath == null || logoPath.isBlank() ) return null;
        java.io.File f = new java.io.File( logoPath );
        if ( !f.exists() || !f.isFile() ) return null;
        Image img = new Image( f.toURI().toString(), false );
        if ( img.isError() ) return null;

        Color[] palette = computeDominantColorPalette( img, count );
        if ( palette == null || palette.length == 0 ) return null;

        // Cache the freshly-sampled palette. Also opportunistically
        // populate the legacy 2-color cache from palette[0..1] so the
        // hero-card render path (which still calls sampleDominantPackColors
        // when n > 2 isn't needed) doesn't repeat the histogram.
        if ( cacheKey != null ) {
            DOMINANT_PALETTE_CACHE.put( cacheKey, palette );
            Color sec = palette.length >= 2 ? palette[ 1 ] : null;
            DOMINANT_COLOR_CACHE.putIfAbsent( cacheKey,
                                               new DominantColors( palette[ 0 ], sec ) );
        }
        return palette;
    }

    /**
     * N-color sibling of {@link #computeDominantColors}. Walks the same
     * saturation-weighted histogram, picks the heaviest bucket as the
     * first color, then repeatedly picks the next heaviest bucket whose
     * distance from every already-picked color exceeds the threshold —
     * stopping when {@code count} colors are picked or no more distinct
     * buckets remain.
     */
    private static Color[] computeDominantColorPalette( Image image, int count )
    {
        if ( image == null || image.isError() || count < 1 ) {
            return null;
        }
        PixelReader reader = image.getPixelReader();
        if ( reader == null ) {
            return null;
        }
        int w = ( int ) image.getWidth();
        int h = ( int ) image.getHeight();
        if ( w <= 0 || h <= 0 ) {
            return null;
        }

        final int LEVELS  = 16;
        final int BUCKETS = LEVELS * LEVELS * LEVELS;
        int[]    counts = new int[ BUCKETS ];
        double[] rSum   = new double[ BUCKETS ];
        double[] gSum   = new double[ BUCKETS ];
        double[] bSum   = new double[ BUCKETS ];

        int step = Math.max( 1, Math.min( w, h ) / 32 );
        for ( int y = 0; y < h; y += step ) {
            for ( int x = 0; x < w; x += step ) {
                Color c = reader.getColor( x, y );
                if ( c.getOpacity() < 0.5 ) continue;
                double sat = Math.max( c.getRed(), Math.max( c.getGreen(), c.getBlue() ) )
                           - Math.min( c.getRed(), Math.min( c.getGreen(), c.getBlue() ) );
                int weight = 1 + ( int ) ( sat * 20 );
                int rIdx = Math.min( LEVELS - 1, ( int ) ( c.getRed()   * LEVELS ) );
                int gIdx = Math.min( LEVELS - 1, ( int ) ( c.getGreen() * LEVELS ) );
                int bIdx = Math.min( LEVELS - 1, ( int ) ( c.getBlue()  * LEVELS ) );
                int idx  = ( rIdx * LEVELS + gIdx ) * LEVELS + bIdx;
                counts[ idx ] += weight;
                rSum  [ idx ] += c.getRed()   * weight;
                gSum  [ idx ] += c.getGreen() * weight;
                bSum  [ idx ] += c.getBlue()  * weight;
            }
        }

        final double DIST_THRESHOLD = 0.25;
        java.util.List< Color > picked = new java.util.ArrayList<>( count );
        // Greedy pass: repeatedly find the heaviest unpicked bucket that's
        // far enough away from every already-picked color. O(count * BUCKETS)
        // which is fine for count ≤ ~8.
        for ( int slot = 0; slot < count; slot++ ) {
            int bestIdx = -1;
            int bestCount = 0;
            outer:
            for ( int i = 0; i < BUCKETS; i++ ) {
                if ( counts[ i ] == 0 || counts[ i ] <= bestCount ) continue;
                Color candidate = bucketAverage( i, counts, rSum, gSum, bSum );
                for ( Color already : picked ) {
                    if ( colorDistance( candidate, already ) <= DIST_THRESHOLD ) {
                        continue outer;
                    }
                }
                bestCount = counts[ i ];
                bestIdx = i;
            }
            if ( bestIdx < 0 ) break;
            picked.add( bucketAverage( bestIdx, counts, rSum, gSum, bSum ) );
        }

        if ( picked.isEmpty() ) return null;
        return picked.toArray( new Color[ 0 ] );
    }

    /** Builds the CSS {@code -fx-background-color: linear-gradient(...)} declaration from
     *  a sampled {@link DominantColors}. Thin wrapper over the n-color builder so existing
     *  callers don't have to migrate to {@code Color[]}; the n-color path is preferred when
     *  the caller has a multi-color palette (richer gradients for logo-rich packs). */
    private static String buildGradientStyle( DominantColors colors )
    {
        Color[] palette = colors.hasSecondary()
                ? new Color[]{ colors.primary(), colors.secondary() }
                : new Color[]{ colors.primary() };
        return buildGradientStyleFromPalette( palette );
    }

    /**
     * N-color sibling of {@link #buildGradientStyle}. Generates a
     * diagonal {@code linear-gradient(to bottom right, ...)} with
     * {@code 2N-1} stops: each palette color anchored at its evenly
     * spaced position, plus a 50/50 blend between every adjacent pair
     * inserted at the midpoint between them. The extra midpoint stops
     * smooth out what would otherwise be hard transitions between
     * adjacent palette colors when the colors are visually distant.
     *
     * <p>Layouts:</p>
     * <ul>
     *   <li>1 color — primary + two derived darker stops (same fallback
     *       as the original 2-color builder's single-color branch).</li>
     *   <li>2 colors — primary, 50% midpoint, secondary (3 stops).</li>
     *   <li>3 colors — primary, mid12, secondary, mid23, tertiary
     *       (5 stops).</li>
     *   <li>4+ colors — same pattern extended; 7+ stops.</li>
     * </ul>
     */
    private static String buildGradientStyleFromPalette( Color[] palette )
    {
        if ( palette == null || palette.length == 0 ) {
            return null;
        }
        if ( palette.length == 1 ) {
            // Single-color fallback — pair primary with two derived darker
            // stops for depth. Same shape as the original buildGradientStyle
            // single-color branch.
            Color primary = palette[ 0 ];
            Color dim    = primary.deriveColor( 0.0, 1.0, 0.60, 1.0 );
            Color shadow = primary.deriveColor( 0.0, 1.0, 0.35, 1.0 );
            return String.format(
                    "-fx-background-color: linear-gradient(to bottom right, %s 0%%, %s 55%%, %s 100%%);",
                    toHexRgb( primary ), toHexRgb( dim ), toHexRgb( shadow ) );
        }

        // Multi-color: anchor each color at an evenly-spaced position
        // from 0% to 100%, and slot a 50/50 blend halfway between every
        // adjacent pair. With N colors the result has 2N-1 stops; the
        // anchor positions are i/(N-1) and the midpoint positions are
        // (i + 0.5)/(N-1).
        int n = palette.length;
        StringBuilder sb = new StringBuilder( "-fx-background-color: linear-gradient(to bottom right" );
        for ( int i = 0; i < n; i++ ) {
            double anchorPct = ( 100.0 * i ) / ( n - 1 );
            sb.append( ", " ).append( toHexRgb( palette[ i ] ) )
              .append( String.format( " %.2f%%", anchorPct ) );
            if ( i < n - 1 ) {
                Color mid = blend( palette[ i ], palette[ i + 1 ], 0.5 );
                double midPct = ( 100.0 * ( i + 0.5 ) ) / ( n - 1 );
                sb.append( ", " ).append( toHexRgb( mid ) )
                  .append( String.format( " %.2f%%", midPct ) );
            }
        }
        sb.append( ");" );
        return sb.toString();
    }

    /** {@link #setBgLayerGradient} sibling that takes the {@code Color[]}
     *  palette directly. Same bg-image preservation logic — the inline
     *  style is rebuilt as "gradient + image overlay" if an image was
     *  already declared. */
    private static void setBgLayerGradientFromPalette( Region bgLayer, Color[] palette )
    {
        String gradientPart = buildGradientStyleFromPalette( palette );
        if ( gradientPart == null ) return;
        String existingBgImage = extractBackgroundImageDeclaration( bgLayer.getStyle() );
        if ( existingBgImage != null ) {
            bgLayer.setStyle( gradientPart + " " + existingBgImage );
        }
        else {
            bgLayer.setStyle( gradientPart );
        }
    }

    /**
     * Loads + samples the bundled launcher logo and returns its
     * dominant-color palette. Result is memoised in
     * {@link #DOMINANT_PALETTE_CACHE} under
     * {@link #LAUNCHER_LOGO_PALETTE_KEY} — the logo never changes
     * per session, so this is effectively a one-shot computation.
     * Returns {@code null} when the image can't be loaded or no
     * dominant hues are extractable (fully transparent / monochrome
     * grey logo — neither applies to the current Mica logo, but we
     * keep the fallback so callers don't NPE on a future rebrand).
     */
    private static Color[] getLauncherLogoPalette()
    {
        Color[] cached = DOMINANT_PALETTE_CACHE.get( LAUNCHER_LOGO_PALETTE_KEY );
        if ( cached != null ) {
            return cached;
        }
        try {
            java.net.URL url = MCLauncherMainGui.class.getResource( LAUNCHER_LOGO_RESOURCE_PATH );
            if ( url == null ) return null;
            Image img = new Image( url.toExternalForm(), false );
            if ( img.isError() ) return null;
            Color[] palette = computeDominantColorPalette( img, 4 );
            if ( palette == null || palette.length == 0 ) return null;
            DOMINANT_PALETTE_CACHE.put( LAUNCHER_LOGO_PALETTE_KEY, palette );
            return palette;
        }
        catch ( Throwable t ) {
            return null;
        }
    }

    /** Linear blend between two colors at parameter t ∈ [0, 1]. t=0 returns a; t=1 returns b. */
    private static Color blend( Color a, Color b, double t )
    {
        return new Color(
                a.getRed()   * ( 1.0 - t ) + b.getRed()   * t,
                a.getGreen() * ( 1.0 - t ) + b.getGreen() * t,
                a.getBlue()  * ( 1.0 - t ) + b.getBlue()  * t,
                1.0 );
    }

    /** Histogram-based two-color extraction. Bins pixels into a coarse 16-per-channel RGB
     *  histogram (4096 buckets), weights each entry by the pixel's saturation so vivid
     *  accents dominate, then picks the heaviest bucket as primary and the heaviest
     *  *sufficiently-distinct* bucket as secondary. "Sufficiently distinct" = RGB Euclidean
     *  distance ≥ 0.25 in normalized space (~64 / 255 per channel on average), which keeps
     *  the gradient from collapsing to two near-identical shades.
     *
     *  <p>Returns null when no usable opaque pixels exist (fully transparent images);
     *  returns a {@code DominantColors} with {@code secondary == null} when the logo is
     *  too monochrome to find a second distinct hue (e.g. one-color logos on transparent
     *  backgrounds). Callers handle both null and no-secondary cases.</p> */
    private static DominantColors computeDominantColors( Image image )
    {
        if ( image == null || image.isError() ) {
            return null;
        }
        PixelReader reader = image.getPixelReader();
        if ( reader == null ) {
            return null;
        }
        int w = ( int ) image.getWidth();
        int h = ( int ) image.getHeight();
        if ( w <= 0 || h <= 0 ) {
            return null;
        }

        final int LEVELS  = 16;
        final int BUCKETS = LEVELS * LEVELS * LEVELS;
        int[]    counts = new int[ BUCKETS ];
        double[] rSum   = new double[ BUCKETS ];
        double[] gSum   = new double[ BUCKETS ];
        double[] bSum   = new double[ BUCKETS ];

        int step = Math.max( 1, Math.min( w, h ) / 32 );
        for ( int y = 0; y < h; y += step ) {
            for ( int x = 0; x < w; x += step ) {
                Color c = reader.getColor( x, y );
                if ( c.getOpacity() < 0.5 ) {
                    continue;
                }
                double sat = Math.max( c.getRed(), Math.max( c.getGreen(), c.getBlue() ) )
                           - Math.min( c.getRed(), Math.min( c.getGreen(), c.getBlue() ) );
                // Saturated pixels weigh up to ~21× more than gray pixels, so a logo's
                // accent hues dominate the histogram even when they cover fewer pixels
                // than the (often gray / white / dark) backdrop.
                int weight = 1 + ( int ) ( sat * 20 );

                int rIdx = Math.min( LEVELS - 1, ( int ) ( c.getRed()   * LEVELS ) );
                int gIdx = Math.min( LEVELS - 1, ( int ) ( c.getGreen() * LEVELS ) );
                int bIdx = Math.min( LEVELS - 1, ( int ) ( c.getBlue()  * LEVELS ) );
                int idx  = ( rIdx * LEVELS + gIdx ) * LEVELS + bIdx;

                counts[ idx ] += weight;
                rSum  [ idx ] += c.getRed()   * weight;
                gSum  [ idx ] += c.getGreen() * weight;
                bSum  [ idx ] += c.getBlue()  * weight;
            }
        }

        int topIdx = -1, topCount = 0;
        for ( int i = 0; i < BUCKETS; i++ ) {
            if ( counts[ i ] > topCount ) {
                topCount = counts[ i ];
                topIdx = i;
            }
        }
        if ( topIdx < 0 ) {
            return null;
        }
        Color primary = bucketAverage( topIdx, counts, rSum, gSum, bSum );

        final double DIST_THRESHOLD = 0.25;
        int secIdx = -1, secCount = 0;
        for ( int i = 0; i < BUCKETS; i++ ) {
            if ( i == topIdx || counts[ i ] == 0 ) {
                continue;
            }
            Color candidate = bucketAverage( i, counts, rSum, gSum, bSum );
            if ( colorDistance( candidate, primary ) > DIST_THRESHOLD && counts[ i ] > secCount ) {
                secCount = counts[ i ];
                secIdx = i;
            }
        }
        Color secondary = ( secIdx >= 0 ) ? bucketAverage( secIdx, counts, rSum, gSum, bSum ) : null;
        return new DominantColors( primary, secondary );
    }

    private static Color bucketAverage( int idx, int[] counts, double[] rSum, double[] gSum, double[] bSum )
    {
        int c = counts[ idx ];
        return new Color( rSum[ idx ] / c, gSum[ idx ] / c, bSum[ idx ] / c, 1.0 );
    }

    private static double colorDistance( Color a, Color b )
    {
        double dr = a.getRed()   - b.getRed();
        double dg = a.getGreen() - b.getGreen();
        double db = a.getBlue()  - b.getBlue();
        return Math.sqrt( dr * dr + dg * dg + db * db );
    }

    /** Formats a JavaFX {@link Color} as a six-digit hex literal usable in JavaFX CSS
     *  ({@code #RRGGBB}). Alpha is dropped — the bg-layer always renders fully opaque. */
    private static String toHexRgb( Color c )
    {
        return String.format( "#%02X%02X%02X",
                ( int ) Math.round( c.getRed()   * 255 ),
                ( int ) Math.round( c.getGreen() * 255 ),
                ( int ) Math.round( c.getBlue()  * 255 ) );
    }

    /** Delegates to {@link ModpackImageResolver#resolveLogoOrDefault} — kept as
     *  a wrapper so the call sites in this file (and {@link ModpackHeroCard#bind})
     *  read consistently with the rest of the screen-local helpers. */
    private static Image resolveLogoImage( GameModPack pack ) {
        return ModpackImageResolver.resolveLogoOrDefault( pack );
    }

    private static String safeMinecraftVersion( GameModPack pack ) {
        try { return pack.getMinecraftVersion(); }
        catch ( Exception ignored ) { return null; }
    }

    private static String safeLoaderName( GameModPack pack ) {
        try { return pack.getLoaderName(); }
        catch ( Exception ignored ) { return null; }
    }

    private static String safeLoaderVersion( GameModPack pack ) {
        try { return pack.getLoaderVersion(); }
        catch ( Exception ignored ) { return null; }
    }

    private static void openModpackWebsite( GameModPack pack ) {
        SystemUtilities.spawnNewTask( () -> {
            String url = pack.getPackURL();
            // Pack website URL comes from the manifest JSON. Reject anything other
            // than http/https so a malicious manifest can't have the "View Website"
            // button open arbitrary local files via file:// or other schemes.
            if ( url == null
                    || !( url.startsWith( "https://" ) || url.startsWith( "http://" ) ) ) {
                Logger.logWarning( "Refusing to open non-http(s) modpack URL: " + url );
                return;
            }
            try {
                Desktop.getDesktop().browse( URI.create( url ) );
            }
            catch ( IOException e ) {
                Logger.logError( "Unable to open your browser. Please visit " + url +
                                         " to view the mod pack's website!" );
                Logger.logThrowable( e );
            }
        } );
    }

    /** Bottom-bar status helper — counterpart to {@link MCLauncherGameLibraryGui}'s
     *  helper of the same name. Flips {@code backgroundFetchLabel} on with the
     *  given text. Safe from any thread; FX work is dispatched via JFXPlatformRun. */
    private void showBackgroundStatus( String text )
    {
        GUIUtilities.JFXPlatformRun( () -> {
            if ( backgroundFetchLabel == null ) return;
            backgroundFetchLabel.setText( text != null ? text : "" );
            backgroundFetchLabel.setVisible( true );
            backgroundFetchLabel.setManaged( true );
        } );
    }

    /** Hides the bottom-bar status label and restores the canonical startup-fetch
     *  text in case the deferred startup fetch hasn't fired yet this session
     *  (matches what the manual-refresh handler does on its tear-down path). */
    private void hideBackgroundStatus()
    {
        GUIUtilities.JFXPlatformRun( () -> {
            if ( backgroundFetchLabel == null ) return;
            backgroundFetchLabel.setVisible( false );
            backgroundFetchLabel.setManaged( false );
            backgroundFetchLabel.setText( LocalizationManager.get( "main.fetchLabel.loading" ) );
        } );
    }

    /**
     * Builds the right-click context menu for a pack card. Shared between the
     * main-menu hero carousel and the Library grid so a user who right-clicks
     * a pack in either view gets the same set of actions (open folders, copy
     * invite link, uninstall, …).
     *
     * <p>The uninstall action defers to
     * {@link MCLauncherGameLibraryGui#confirmAndUninstallModpack} — same
     * confirmation dialog as the Library's manage UI — and routes its
     * progress + completion callbacks back to the caller so the caller's
     * bottom-bar status label flips and its card grid rebuilds without this
     * helper needing to know which screen it was invoked from.
     */
    static ContextMenu buildPackContextMenu( GameModPack pack,
                                              Stage owner,
                                              java.util.function.Consumer< String > showProgress,
                                              Runnable hideProgress,
                                              Runnable afterUninstall )
    {
        ContextMenu menu = new ContextMenu();

        MenuItem playStats = new MenuItem( "Played " + pack.getTotalPlayTimeFormatted() +
                                                   " (" + pack.getLaunchCount() + " launches)" );
        playStats.setDisable( true );

        MenuItem openFolder       = new MenuItem( "Open Install Folder" );
        MenuItem openScreenshots  = new MenuItem( "Open Screenshots" );
        MenuItem openResourcePks  = new MenuItem( "Open Resource Packs" );
        MenuItem openShaderPacks  = new MenuItem( "Open Shader Packs" );
        MenuItem openMods         = new MenuItem( "Open Mods Folder" );
        MenuItem openConfig       = new MenuItem( "Open Config Folder" );
        MenuItem createShortcut   = new MenuItem( "Create Desktop Shortcut" );
        MenuItem copyInviteLink   = new MenuItem( "Copy Discord Invite Link" );
        MenuItem editPack         = new MenuItem( "Edit Pack…" );
        MenuItem exportPack       = new MenuItem( "Export as ZIP…" );
        MenuItem addToOfficial    = new MenuItem(
                LocalizationManager.get( "officialExport.menuItem" ) );
        MenuItem removeOfficial   = new MenuItem(
                LocalizationManager.get( "officialExport.remove.menuItem" ) );
        MenuItem uninstall        = new MenuItem( "Uninstall…" );

        openFolder.setOnAction(       e -> openPackSubfolder( pack, "" ) );
        openScreenshots.setOnAction(  e -> openPackSubfolder( pack, "screenshots" ) );
        openResourcePks.setOnAction(  e -> openPackSubfolder( pack, "resourcepacks" ) );
        openShaderPacks.setOnAction(  e -> openPackSubfolder( pack, "shaderpacks" ) );
        openMods.setOnAction(         e -> openPackSubfolder( pack, "mods" ) );
        openConfig.setOnAction(       e -> openPackSubfolder( pack, "config" ) );
        createShortcut.setOnAction(   e -> createDesktopShortcut( pack ) );
        copyInviteLink.setOnAction(   e -> copyInviteLinkToClipboard( pack ) );
        editPack.setOnAction( e -> SystemUtilities.spawnNewTask( () -> {
            try { MCLauncherGuiController.goToModPackEditorGui( pack ); }
            catch ( IOException ex ) {
                Logger.logError( "Unable to open modpack editor." );
                Logger.logThrowable( ex );
            }
        } ) );
        exportPack.setOnAction( e -> {
            javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
            chooser.setTitle( LocalizationManager.get( "dialog.fileChooser.exportModpack.title" ) );
            String safeName = pack.getPackName() == null ? "modpack" : pack.getPackName();
            chooser.setInitialFileName( safeName + "-" + System.currentTimeMillis() + ".zip" );
            chooser.getExtensionFilters().add(
                    new javafx.stage.FileChooser.ExtensionFilter(
                            LocalizationManager.get( "dialog.fileChooser.zipArchive.filter" ), "*.zip" ) );
            File dest = chooser.showSaveDialog( owner );
            if ( dest == null ) return;
            // Verify the pack first so a never-launched pack (mods/configs
            // not yet downloaded) doesn't produce a sparse ZIP. Vanilla
            // packs skip the verify (nothing to ship in the ZIP anyway —
            // ModpackExporter handles that as a no-op).
            Runnable doZip = () -> com.micatechnologies.minecraft.launcher.utilities.FxAsyncTask.run(
                    () -> com.micatechnologies.minecraft.launcher.game.modpack.ModpackExporter
                            .exportToZip( pack, dest ),
                    () -> NotificationManager.success(
                            LocalizationManager.get( "detailModal.export.success.title" ),
                            LocalizationManager.format( "detailModal.export.success.body",
                                                        dest.getAbsolutePath() ) ),
                    err -> NotificationManager.error(
                            LocalizationManager.get( "detailModal.exportPack.failed" ),
                            err.getMessage() ) );
            if ( pack.isVanillaVersion() ) {
                doZip.run();
            }
            else {
                NotificationManager.info(
                        LocalizationManager.get( "detailModal.exportPack.verifying" ),
                        pack.getPackName() == null ? "" : pack.getPackName() );
                com.micatechnologies.minecraft.launcher.utilities.VerifyAction.runForPacks(
                        java.util.List.of( pack ), doZip );
            }
        } );
        // Disable Export when there's no install folder (e.g. failed-load placeholder packs).
        if ( pack.getPackRootFolder() == null ) {
            exportPack.setDisable( true );
            addToOfficial.setDisable( true );
        }
        addToOfficial.setOnAction( e -> handleAddToOfficialLauncher( pack, owner ) );
        removeOfficial.setOnAction( e -> handleRemoveFromOfficialLauncher( pack, owner ) );
        // Remove appears only when a Mica-owned profile actually exists.
        // hasExportedProfile reads launcher_profiles.json on disk — a few
        // ms, fine to do inline during menu construction. Falls back to
        // false on any error so a permissions / IO hiccup hides the
        // option instead of surfacing a broken action.
        boolean exportedProfileExists = com.micatechnologies.minecraft.launcher.game.modpack
                .OfficialLauncherExporter.hasExportedProfile( pack );
        uninstall.setOnAction( e -> MCLauncherGameLibraryGui.confirmAndUninstallModpack(
                pack, pack.getFriendlyName(), owner,
                showProgress, hideProgress, afterUninstall ) );

        // Disable only when there's truly nothing to invite friends to — i.e. no manifest
        // URL AND no vanilla version ID. The customDiscordRpc gate that used to live here
        // was overzealous: it applies to the LIVE "Join Game" button on the running
        // presence (handled in DiscordRpcUtility.setGamePresence) but not to this
        // copy-link action, which produces a static URL the user manually shares. A
        // custom-RPC pack is still installable + playable from the join URL on the
        // receiver's launcher.
        if ( com.micatechnologies.minecraft.launcher.utilities.DiscordRpcUtility
                .buildInviteLinkFromPack( pack ) == null ) {
            copyInviteLink.setDisable( true );
        }

        menu.getItems().addAll( playStats, new SeparatorMenuItem(),
                                openFolder, new SeparatorMenuItem(),
                                openScreenshots, openResourcePks, openShaderPacks,
                                new SeparatorMenuItem(), openMods, openConfig,
                                new SeparatorMenuItem(), createShortcut, copyInviteLink,
                                new SeparatorMenuItem(), editPack, exportPack, addToOfficial );
        if ( exportedProfileExists ) {
            menu.getItems().add( removeOfficial );
        }
        menu.getItems().addAll( new SeparatorMenuItem(), uninstall );
        return menu;
    }

    /** Copies an {@code mmcl://join?url=...} or {@code mmcl://join?vanilla=...} invite link
     *  for the given pack to the system clipboard. The user can paste it into Discord, a
     *  chat message, etc.; clicking the link on another machine with the launcher installed
     *  installs the pack/version (if needed) and launches it via LauncherUriHandler. */
    private static void copyInviteLinkToClipboard( GameModPack pack )
    {
        String invite = com.micatechnologies.minecraft.launcher.utilities.DiscordRpcUtility
                .buildInviteLinkFromPack( pack );
        if ( invite == null ) {
            NotificationManager.warn(
                    LocalizationManager.get( "detailModal.invite.unavailable.title" ),
                    LocalizationManager.get( "detailModal.invite.unavailable.body" ) );
            return;
        }
        GUIUtilities.JFXPlatformRun( () -> {
            javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
            content.putString( invite );
            javafx.scene.input.Clipboard.getSystemClipboard().setContent( content );
            NotificationManager.success(
                    LocalizationManager.get( "notification.invite.copied.title" ),
                    LocalizationManager.get( "notification.invite.copied.body" ) );
        } );
    }

    /** Shared confirmation + export flow for "Add to Minecraft Launcher."
     *  Surfaced from both the main-menu context menu and (later) the
     *  modpack detail modal's Advanced section. Runs the export on a
     *  background thread; routes results to NotificationManager toasts.
     *
     *  <p>Vanilla packs skip the loader-installer warning since the
     *  Mojang launcher handles every vanilla version natively. Modded
     *  packs check whether the matching version directory exists under
     *  the Mojang launcher's {@code versions/} folder and surface the
     *  warning when it doesn't.</p> */
    static void handleAddToOfficialLauncher( GameModPack pack, Stage owner )
    {
        SystemUtilities.spawnNewTask( () -> {
            if ( !com.micatechnologies.minecraft.launcher.game.modpack.OfficialLauncherExporter
                    .isOfficialLauncherAvailable() ) {
                NotificationManager.error(
                        LocalizationManager.get( "officialExport.notInstalled.title" ),
                        LocalizationManager.format( "officialExport.notInstalled.body",
                                com.micatechnologies.minecraft.launcher.game.modpack
                                        .OfficialLauncherExporter.resolveDotMinecraft().toString() ) );
                return;
            }
            // Derive the version ID + loader-install state up front so the
            // confirmation dialog can show both. Wrapping in try/catch
            // here keeps a manifest-parse failure from blocking the dialog
            // — the user can still hit Cancel; we just won't have the
            // pre-flight info to show them.
            String versionId;
            boolean loaderInstalled;
            try {
                versionId = com.micatechnologies.minecraft.launcher.game.modpack
                        .OfficialLauncherExporter.computeVersionId( pack );
                loaderInstalled = com.micatechnologies.minecraft.launcher.game.modpack
                        .OfficialLauncherExporter.isVersionInstalled(
                                com.micatechnologies.minecraft.launcher.game.modpack
                                        .OfficialLauncherExporter.resolveDotMinecraft(),
                                versionId );
            }
            catch ( Exception e ) {
                Logger.logThrowable( e );
                NotificationManager.error(
                        LocalizationManager.get( "officialExport.failed.title" ),
                        LocalizationManager.format( "officialExport.failed.body", e.getMessage() ) );
                return;
            }
            String packName = pack.getPackName();
            String loaderName;
            try {
                loaderName = pack.isVanillaVersion() ? "Minecraft" : pack.getLoaderName();
            }
            catch ( Exception e ) {
                loaderName = "the modloader";
            }
            String middleBlurb;
            if ( pack.isVanillaVersion() ) {
                middleBlurb = LocalizationManager.format(
                        "officialExport.confirm.body.vanilla", packName, versionId );
            }
            else if ( loaderInstalled ) {
                middleBlurb = LocalizationManager.get(
                        "officialExport.confirm.body.modded.loaderInstalled" );
            }
            else {
                middleBlurb = LocalizationManager.format(
                        "officialExport.confirm.body.modded.loaderMissing", loaderName, versionId );
            }
            int answer = GUIUtilities.showQuestionMessageMultiline(
                    LocalizationManager.get( "officialExport.confirm.title" ),
                    LocalizationManager.format( "officialExport.confirm.header", packName ),
                    LocalizationManager.format( "officialExport.confirm.body",
                                                packName, versionId, middleBlurb ),
                    LocalizationManager.get( "officialExport.confirm.button.export" ),
                    LocalizationManager.get( "dialog.button.cancel" ),
                    owner );
            if ( answer != 1 ) return;

            // Phase 4 — if a modded pack's loader version isn't already
            // installed in .minecraft/versions/, offer to run the
            // installer for the user. They can decline and add the
            // profile anyway (it'll show "Version not found" in Mojang's
            // UI until they install the loader manually) or accept and
            // we'll spawn the installer headlessly.
            boolean autoInstallLoader = false;
            if ( !loaderInstalled && !pack.isVanillaVersion() ) {
                int installAnswer = GUIUtilities.showQuestionMessageMultiline(
                        LocalizationManager.get( "officialExport.installLoader.title" ),
                        LocalizationManager.format( "officialExport.installLoader.header",
                                                    loaderName ),
                        LocalizationManager.format( "officialExport.installLoader.body",
                                                    loaderName, versionId ),
                        LocalizationManager.get( "officialExport.installLoader.button.installAndAdd" ),
                        LocalizationManager.get( "officialExport.installLoader.button.addOnly" ),
                        owner );
                if ( installAnswer == 0 ) return;  // user hit Cancel — bail out entirely
                autoInstallLoader = ( installAnswer == 1 );
            }

            // Wrap the loader install + export in a continuation so we
            // can gate them on a successful pack verify. Vanilla packs
            // skip the verify step — there are no manifest-driven content
            // files to revalidate (the Mojang launcher pulls vanilla
            // assets on its own).
            final boolean finalAutoInstallLoader = autoInstallLoader;
            final String finalLoaderName = loaderName;
            Runnable doExport = () -> runOfficialExportAfterVerify(
                    pack, packName, finalLoaderName, finalAutoInstallLoader );

            if ( pack.isVanillaVersion() ) {
                doExport.run();
            }
            else {
                // Force-FULL verify of pack files so a never-launched pack
                // (mods/ + configs/ + resourcepacks/ not yet downloaded)
                // gets fully populated before the file copy runs against
                // an empty install folder. The verify GUI is the same
                // launch-progress surface used by the "Verify this pack
                // now" button, so the user gets familiar visual feedback.
                NotificationManager.info(
                        LocalizationManager.get( "officialExport.verifying" ),
                        packName );
                com.micatechnologies.minecraft.launcher.utilities.VerifyAction.runForPacks(
                        java.util.List.of( pack ), doExport );
            }
        } );
    }

    /**
     * Loader-install + Mojang-profile-write phase of the official-launcher
     * export, factored out so the verify-then-export chain in
     * {@link #handleAddToOfficialLauncher} can hand it off as a continuation
     * to {@link com.micatechnologies.minecraft.launcher.utilities.VerifyAction}.
     * Vanilla packs call this directly (no verify step); modded packs only
     * reach it when the pre-export verify completed cleanly.
     */
    private static void runOfficialExportAfterVerify( GameModPack pack,
                                                       String packName,
                                                       String loaderName,
                                                       boolean autoInstallLoader )
    {
        // Optional: run the loader installer before the regular export so
        // the version directory exists by the time the Mojang profile
        // points at it.
        if ( autoInstallLoader ) {
            NotificationManager.info(
                    LocalizationManager.format( "officialExport.installLoader.inProgress",
                                                loaderName ),
                    packName );
            com.micatechnologies.minecraft.launcher.game.modpack
                    .LoaderInstallerRunner.Result installResult =
                    com.micatechnologies.minecraft.launcher.game.modpack
                            .LoaderInstallerRunner.install( pack,
                                    com.micatechnologies.minecraft.launcher.game.modpack
                                            .OfficialLauncherExporter.resolveDotMinecraft() );
            if ( installResult.success() ) {
                NotificationManager.success(
                        LocalizationManager.get( "officialExport.installLoader.success.title" ),
                        LocalizationManager.format(
                                "officialExport.installLoader.success.body",
                                installResult.message() ) );
            }
            else {
                // Don't bail out — fall through to the regular export so
                // the user at least gets the profile entry. The success
                // notification at the end will flip to the "loader
                // missing" warning variant since the version dir still
                // doesn't exist.
                NotificationManager.warn(
                        LocalizationManager.get( "officialExport.installLoader.failed.title" ),
                        LocalizationManager.format(
                                "officialExport.installLoader.failed.body",
                                installResult.message(), loaderName ) );
                if ( installResult.installerStderr() != null
                        && !installResult.installerStderr().isBlank() ) {
                    Logger.logStd( "[loader-installer stderr]\n" + installResult.installerStderr() );
                }
            }
        }

        // Run the actual export. Status notification fires immediately so
        // the user sees progress even on slow filesystems (big modpacks
        // copy a few hundred MB).
        NotificationManager.info(
                LocalizationManager.get( "officialExport.inProgress" ),
                packName );
        com.micatechnologies.minecraft.launcher.game.modpack.OfficialLauncherExporter.Result result
                = com.micatechnologies.minecraft.launcher.game.modpack
                        .OfficialLauncherExporter.exportPack( pack );
        if ( !result.success() ) {
            NotificationManager.error(
                    LocalizationManager.get( "officialExport.failed.title" ),
                    LocalizationManager.format( "officialExport.failed.body",
                                                result.errorMessage() ) );
            return;
        }
        if ( !result.loaderInstalled() && !pack.isVanillaVersion() ) {
            NotificationManager.warn(
                    LocalizationManager.get( "officialExport.successButLoaderMissing.title" ),
                    LocalizationManager.format(
                            "officialExport.successButLoaderMissing.body",
                            result.versionId() ) );
            return;
        }
        NotificationManager.success(
                LocalizationManager.get( "officialExport.success.title" ),
                LocalizationManager.format( "officialExport.success.body",
                                            result.profileName() ) );
    }

    /** Phase 5: removes a previously-exported Mica profile from the
     *  Mojang launcher's launcher_profiles.json and deletes the export
     *  gameDir. Surfaced from the right-click menu / detail modal only
     *  when {@code OfficialLauncherExporter.hasExportedProfile()} is
     *  true. */
    static void handleRemoveFromOfficialLauncher( GameModPack pack, Stage owner )
    {
        SystemUtilities.spawnNewTask( () -> {
            String packName = pack.getPackName();
            String gameDir = com.micatechnologies.minecraft.launcher.game.modpack
                    .OfficialLauncherExporter.computeExportGameDir( pack ).toString();
            int answer = GUIUtilities.showQuestionMessageMultiline(
                    LocalizationManager.get( "officialExport.remove.confirm.title" ),
                    LocalizationManager.format( "officialExport.remove.confirm.header", packName ),
                    LocalizationManager.format( "officialExport.remove.confirm.body",
                                                packName, gameDir ),
                    LocalizationManager.get( "officialExport.remove.confirm.button.remove" ),
                    LocalizationManager.get( "dialog.button.cancel" ),
                    owner );
            if ( answer != 1 ) return;

            com.micatechnologies.minecraft.launcher.game.modpack.OfficialLauncherExporter.Result result
                    = com.micatechnologies.minecraft.launcher.game.modpack
                            .OfficialLauncherExporter.removeExport( pack );
            if ( !result.success() ) {
                NotificationManager.error(
                        LocalizationManager.get( "officialExport.remove.failed.title" ),
                        LocalizationManager.format( "officialExport.remove.failed.body",
                                                    result.errorMessage() ) );
                return;
            }
            NotificationManager.success(
                    LocalizationManager.get( "officialExport.remove.success.title" ),
                    LocalizationManager.format( "officialExport.remove.success.body",
                                                result.profileName() ) );
        } );
    }

    private static void createDesktopShortcut( GameModPack pack ) {
        SystemUtilities.spawnNewTask( () -> {
            try {
                DesktopShortcutManager.createShortcut( pack );
                Stage ownerStage = MCLauncherGuiController.getTopStageOrNull();
                if ( ownerStage != null ) {
                    GUIUtilities.JFXPlatformRun( () -> {
                        javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
                                javafx.scene.control.Alert.AlertType.INFORMATION );
                        alert.setTitle( LocalizationManager.get( "dialog.shortcut.created.title" ) );
                        alert.setHeaderText( null );
                        alert.setContentText(
                                LocalizationManager.format( "dialog.shortcut.created.body",
                                                            pack.getPackName() ) );
                        alert.initOwner( ownerStage );
                        alert.initStyle( javafx.stage.StageStyle.UTILITY );
                        alert.showAndWait();
                    } );
                }
            }
            catch ( Exception ex ) {
                Logger.logError( "Failed to create desktop shortcut: " + ex.getMessage() );
                Logger.logThrowable( ex );
                Stage ownerStage = MCLauncherGuiController.getTopStageOrNull();
                if ( ownerStage != null ) {
                    GUIUtilities.showErrorMessage(
                            LocalizationManager.format( "dialog.shortcut.failed.body", ex.getMessage() ),
                            ownerStage );
                }
            }
        } );
    }

    private static void openPackSubfolder( GameModPack pack, String subfolder ) {
        SystemUtilities.spawnNewTask( () -> {
            try {
                String path = pack.getPackRootFolder();
                if ( !subfolder.isEmpty() ) {
                    path += File.separator + subfolder;
                }
                File folder = new File( path );
                if ( !folder.exists() ) {
                    folder.mkdirs();
                }
                Desktop.getDesktop().open( folder );
            }
            catch ( Exception ex ) {
                Logger.logWarningSilent( "Unable to open folder: " + ex.getMessage() );
            }
        } );
    }
}
