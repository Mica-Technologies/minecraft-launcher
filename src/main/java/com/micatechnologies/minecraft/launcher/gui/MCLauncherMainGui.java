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

    /** Page-size options exposed in the per-page dropdown. 12 is the default since
     *  the hero cards are 360px wide and wrap to 3-4 per row on typical window
     *  widths — a 12-item page fills 3-4 rows without needing to scroll past the
     *  pagination controls. */
    private static final List< Integer > PAGE_SIZES = List.of( 12, 24, 48 );
    private static final int DEFAULT_PAGE_SIZE = 12;

    private int pageSize    = DEFAULT_PAGE_SIZE;
    private int currentPage = 1;

    /** When true, the rebuild pass keeps only packs with isUpdateAvailable() == true.
     *  Mirror of {@link #recentlyUpdatedOnlyCheck}'s selected state, cached here so
     *  the rebuild pipeline doesn't have to null-check the FXML field on every
     *  invocation. */
    private boolean updatesOnly = false;

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

        // Announcement bar ✕ — dismiss for this session (resets on next launch).
        // Captures the currently-displayed text so AnnouncementManager keys on what
        // the user actually saw rather than what's currently in the manifest, which
        // could differ if a refresh races with the click.
        if ( announcementClose != null ) {
            announcementClose.setCursor( Cursor.HAND );
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

        if ( NetworkUtilities.isOffline() ) {
            offlineLabel.setVisible( true );
            offlineLabel.setManaged( true );
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

        userImage.setImage( new Image(
                GUIConstants.URL_MINECRAFT_USER_ICONS.replace( GUIConstants.URL_MINECRAFT_USER_ICONS_USER_REPLACE_KEY,
                                                               MCLauncherAuthManager.getLoggedInUser().uuid() ) ) );

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
        typeFilter.setOnAction( e -> { currentPage = 1; rebuildCards(); } );

        // Sort filter — Last Played mirrors the pre-filter "float last-played to
        // top" behavior. Name A→Z / Z→A and Recently Updated round out the most
        // common needs.
        sortFilter.setItems( FXCollections.observableArrayList(
                SORT_LAST_PLAYED, SORT_NAME_AZ, SORT_NAME_ZA, SORT_RECENT_UPDATE ) );
        sortFilter.selectItem( SORT_LAST_PLAYED );
        sortFilter.setOnAction( e -> rebuildCards() );

        // Search — rebuild on each character. FlowPane rebuild is cheap and a
        // PauseTransition-style debounce only matters once a user has 100+ packs.
        searchField.textProperty().addListener( ( obs, oldVal, newVal ) -> {
            currentPage = 1;
            rebuildCards();
        } );
        // Same float-mode kill switch the Library screen uses — no floating label
        // wanted, just promptText.
        searchField.setFloatMode( io.github.palexdev.materialfx.enums.FloatMode.DISABLED );

        // "Recently updated only" checkbox — flips the boolean filter on selection
        // change. A checkbox communicates the dual on/off state more clearly than
        // the previous toggle button did (the button needed a styled `.selected`
        // class to indicate active state, which was easy to miss).
        recentlyUpdatedOnlyCheck.setSelected( false );
        recentlyUpdatedOnlyCheck.selectedProperty().addListener( ( obs, oldVal, newVal ) -> {
            updatesOnly = Boolean.TRUE.equals( newVal );
            currentPage = 1;
            rebuildCards();
        } );

        // Pagination controls
        pageSizeFilter.setItems( FXCollections.observableArrayList( PAGE_SIZES ) );
        pageSizeFilter.selectItem( DEFAULT_PAGE_SIZE );
        pageSizeFilter.setOnAction( e -> {
            Integer selected = pageSizeFilter.getValue();
            if ( selected != null ) {
                pageSize = selected;
                currentPage = 1;
                rebuildCards();
            }
        } );
        prevPageBtn.setOnAction( e -> {
            if ( currentPage > 1 ) {
                currentPage--;
                rebuildCards();
            }
        } );
        nextPageBtn.setOnAction( e -> {
            currentPage++;  // rebuildCards clamps + redraws
            rebuildCards();
        } );

        // Tooltips so the controls are discoverable. Help text matches the
        // Library screen's hints where applicable so users get a consistent
        // mental model across the two screens.
        TooltipManager.install( searchField,    "Filter packs by name." );
        TooltipManager.install( typeFilter,     "Filter by Modpack or Vanilla version." );
        TooltipManager.install( sortFilter,     "Choose how packs are ordered in the grid." );
        TooltipManager.install( recentlyUpdatedOnlyCheck,
                                "Show only packs whose manifest has changed since last launch." );
        TooltipManager.install( pageSizeFilter, "How many cards to render per page." );
    }

    @Override
    void afterShow() {
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

        // Step 2 — type filter
        String typeSel = typeFilter == null ? TYPE_ALL :
                Objects.requireNonNullElse( typeFilter.getValue(), TYPE_ALL );
        if ( TYPE_MODPACKS.equals( typeSel ) ) {
            all.removeIf( GameModPack::isVanillaVersion );
        }
        else if ( TYPE_VANILLA.equals( typeSel ) ) {
            all.removeIf( p -> !p.isVanillaVersion() );
        }

        // Step 3 — Updates-Only
        if ( updatesOnly ) {
            all.removeIf( p -> !p.isUpdateAvailable() );
        }

        // Step 4 — search
        String search = ( searchField == null || searchField.getText() == null )
                ? "" : searchField.getText().trim().toLowerCase( Locale.ROOT );
        if ( !search.isEmpty() ) {
            all.removeIf( p -> !displayNameOf( p ).toLowerCase( Locale.ROOT ).contains( search ) );
        }

        // Step 5 — sort
        String sortSel = sortFilter == null ? SORT_LAST_PLAYED :
                Objects.requireNonNullElse( sortFilter.getValue(), SORT_LAST_PLAYED );
        sortPacks( all, sortSel );

        // Step 6 — paginate + render
        int totalItems = all.size();
        int totalPages = Math.max( 1, ( totalItems + pageSize - 1 ) / pageSize );
        if ( currentPage < 1 ) currentPage = 1;
        if ( currentPage > totalPages ) currentPage = totalPages;
        int startIdx = ( currentPage - 1 ) * pageSize;
        int endIdx   = Math.min( totalItems, startIdx + pageSize );

        updatePaginationControls( totalItems, totalPages, startIdx, endIdx );

        modpackCardList.getChildren().clear();
        if ( totalItems == 0 ) {
            modpackCardList.getChildren().add( buildEmptyState( typeSel, search ) );
            return;
        }
        for ( int i = startIdx; i < endIdx; i++ ) {
            modpackCardList.getChildren().add( new ModpackHeroCard( all.get( i ) ) );
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
                // history fall to the bottom — getUpdateRank returns Long.MIN_VALUE
                // for those, which sorts last under reversed-natural ordering.
                packs.sort( Comparator.comparingLong( MCLauncherMainGui::lastUpdateTimestamp ).reversed() );
            }
            default -> {
                // SORT_LAST_PLAYED — reuse the pre-filter behavior: float the
                // last-played pack to position 0, then sort the rest by lastPlayedMs
                // descending (most recently played near the top, never-played at
                // the bottom).
                packs.sort( Comparator.comparingLong( GameModPack::getLastPlayedMs ).reversed() );
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

    /** Returns the newest update-log timestamp for the pack, or {@code Long.MIN_VALUE}
     *  if the pack has no recorded updates. Used by the "Recently Updated" sort. */
    private static long lastUpdateTimestamp( GameModPack pack )
    {
        try {
            java.util.List< com.micatechnologies.minecraft.launcher.game.modpack.ModPackUpdateLog.Entry > entries
                    = com.micatechnologies.minecraft.launcher.game.modpack.ModPackUpdateLog.readEntries( pack );
            if ( entries.isEmpty() ) return Long.MIN_VALUE;
            return entries.get( 0 ).timestampMs();  // readEntries returns newest-first
        }
        catch ( Exception ignored ) {
            return Long.MIN_VALUE;
        }
    }

    /** Updates the prev/next/page-label state to match the new pagination math. */
    private void updatePaginationControls( int totalItems, int totalPages, int startIdx, int endIdx )
    {
        if ( totalItems == 0 ) {
            paginationRangeLabel.setText( "No packs" );
            paginationPageLabel.setText( "Page 1 of 1" );
        }
        else {
            paginationRangeLabel.setText( "Showing " + ( startIdx + 1 ) + "–" + endIdx
                                                  + " of " + totalItems );
            paginationPageLabel.setText( "Page " + currentPage + " of " + totalPages );
        }
        prevPageBtn.setDisable( currentPage <= 1 );
        nextPageBtn.setDisable( currentPage >= totalPages );
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
        boolean filtersActive = updatesOnly
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
                // rebuildCards(), so the explicit rebuild below is redundant in
                // that branch but harmless.
                recentlyUpdatedOnlyCheck.setSelected( false );
            }
            currentPage = 1;
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
                backgroundFetchLabel.setText( "Refreshing modpacks…" );
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
                        backgroundFetchLabel.setText( "Loading available packs…" );
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

        private final GameModPack pack;
        private final MFXButton playBtn;

        ModpackHeroCard( GameModPack pack )
        {
            this.pack = pack;
            getStyleClass().add( "heroCardShell" );
            // Width is fixed so FlowPane can wrap on a clean grid, but height is content-driven —
            // we tried capping at 320 and the buttons overflowed into the next row.
            setPrefWidth( CARD_WIDTH );
            setMinWidth( CARD_WIDTH );
            setMaxWidth( CARD_WIDTH );
            setSpacing( 0 );

            // Bitmap-cache the whole card. Each card stacks several expensive layers
            // (gaussian dropshadow, rounded Rectangle clip on the image, CSS background-image
            // on the bgLayer Region, secondary shadow on the logo container). Without
            // caching, the ScrollPane re-rasterizes those effects every frame during scroll,
            // which produced the severe lag the user reported. CacheHint.SPEED uses bilinear
            // filtering on the cached bitmap during scroll-translate, which is exactly what
            // we want for vertical scrolling.
            setCache( true );
            setCacheHint( CacheHint.SPEED );

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

            // Load the pack's logo image up front. The logoContainer in the lower half of
            // the card uses it directly; we also use it as the seed for the bgLayer's
            // derived-gradient fallback when the pack doesn't ship its own background image.
            Image packLogoImage = resolveLogoImage( pack );

            Region bgLayer = new Region();
            bgLayer.getStyleClass().add( "heroBackground" );
            String bgUrl = resolveBackgroundUrl( pack );
            if ( bgUrl != null ) {
                bgLayer.setStyle( "-fx-background-image: url('" + bgUrl + "');" );
            }
            else {
                // No per-pack background image — drive a procedural visual:
                //   • vanilla versions get a sky → grass gradient
                //   • modded packs with a logo get a gradient derived from the logo's
                //     dominant color, so each pack feels visually individuated
                //   • modded packs without a logo fall back to a Forge-themed gradient
                //     (dark anvil + warm forge-fire glow)
                applyDynamicBackground( bgLayer, pack, packLogoImage );
            }

            // Subtle veil along the bottom of the image so the logo reads against it.
            Region imageVeil = new Region();
            imageVeil.getStyleClass().add( "heroCardImageVeil" );

            // Optional badge row floats over the top-right of the image.
            HBox badgeRow = new HBox( 6 );
            badgeRow.setAlignment( Pos.TOP_RIGHT );
            badgeRow.setPadding( new javafx.geometry.Insets( 10, 12, 0, 0 ) );
            if ( pack.getPackUnstable() ) badgeRow.getChildren().add( buildChip( "Beta", "stat-chip-warn" ) );
            // "Recently updated" matches the terminology the Library screen uses on
            // installed-pack cards — keeping the two screens phrased the same way so
            // users learn one vocabulary, not two. Avoids the ambiguous imperative
            // ("Update!") that "Update" alone would imply.
            if ( pack.isUpdateAvailable() ) badgeRow.getChildren().add( buildChip( "Recently updated", "stat-chip-success" ) );

            imageBox.getChildren().addAll( bgLayer, imageVeil, badgeRow );

            // Pack logo overlaps the image/content boundary on the left. The container has a
            // rounded border in CSS; clip its inner ImageView so the bitmap respects the
            // rounded corners instead of overflowing as a square.
            StackPane logoContainer = new StackPane();
            logoContainer.getStyleClass().add( "heroPackLogoContainer" );
            logoContainer.setMinSize( 72, 72 );
            logoContainer.setMaxSize( 72, 72 );
            ImageView logo = new ImageView();
            logo.setFitWidth( 68 );
            logo.setFitHeight( 68 );
            logo.setPreserveRatio( true );
            logo.setImage( packLogoImage );
            Rectangle logoClip = new Rectangle( 68, 68 );
            logoClip.setArcWidth( 16 );
            logoClip.setArcHeight( 16 );
            logo.setClip( logoClip );
            logoContainer.getChildren().add( logo );
            logoContainer.setTranslateY( -36 );  // overlap the image

            // Transparent-edge detection: if the logo's perimeter is mostly transparent
            // (circular badge, wordmark, irregular silhouette), the bordered rounded-
            // rect container reads as a floating frame around nothing. Strip the
            // container styling via the .logoTransparent class when that's the case.
            LogoTransparencyDetector.detectAsync( packLogoImage, isTransparent -> {
                if ( isTransparent ) {
                    logoContainer.getStyleClass().add( "logoTransparent" );
                }
            } );

            // ----- Bottom half: content surface -----
            VBox info = new VBox( 6 );
            info.getStyleClass().add( "heroCardBody" );
            info.setAlignment( Pos.TOP_LEFT );
            info.setPadding( new javafx.geometry.Insets( 0, 18, 16, 18 ) );

            Label name = new Label( resolveDisplayName( pack ) );
            name.getStyleClass().addAll( "heading-h2", "heroCardTitle" );
            name.setWrapText( true );

            HBox chips = new HBox( 6 );
            chips.setAlignment( Pos.CENTER_LEFT );
            String mc = safeMinecraftVersion( pack );
            String forge = safeForgeVersion( pack );
            String packVersion = pack.getPackVersion();

            // "Vanilla" chip — placed first so it's the first visual cue that this card
            // is a stock Minecraft version, not a Forge modpack.
            if ( pack.isVanillaVersion() ) {
                chips.getChildren().add( buildChip( "Vanilla" ) );
            }
            // "Minecraft 1.20.4" (not "MC 1.20.4") — full name reads clearer and the chip
            // has room. Minecraft is a trademark of Mojang Synergies AB / Microsoft;
            // attribution is surfaced in the Settings → About / Attributions section.
            if ( mc != null && !mc.isBlank() ) chips.getChildren().add( buildChip( "Minecraft " + mc ) );
            if ( forge != null && !forge.isBlank() ) {
                String shortForge = forge.contains( "-" ) ? forge.substring( forge.lastIndexOf( '-' ) + 1 ) : forge;
                chips.getChildren().add( buildChip( "Forge " + shortForge ) );
            }
            // Pack-version chip: skip when it would just duplicate the MC version chip.
            // Vanilla packs by construction have packVersion == mcVersion, so this drops
            // the redundant "v1.21.11" alongside "Minecraft 1.21.11". Modded packs with
            // a distinct pack version (e.g. "v26.5.2" vs "Minecraft 1.12.2") keep theirs.
            if ( packVersion != null && !packVersion.isBlank() && !packVersion.equals( mc ) ) {
                chips.getChildren().add( buildChip( "v" + packVersion ) );
            }

            String lastPlayed = pack.getLastPlayedFormatted();
            Label played = new Label();
            played.getStyleClass().add( "heroLastPlayedSurface" );
            if ( lastPlayed != null && !"Never played".equals( lastPlayed ) ) {
                played.setText( "Last played " + lastPlayed.toLowerCase() );
            }
            else {
                played.setText( "Never played" );
            }

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
            final double BTN_H = 38;
            playBtn = new MFXButton( "Play" );
            playBtn.getStyleClass().addAll( "primary", "heroPlayBtn" );
            playBtn.setMinHeight( BTN_H );
            playBtn.setPrefHeight( BTN_H );
            playBtn.setMaxHeight( BTN_H );
            playBtn.setMaxWidth( Double.MAX_VALUE );
            HBox.setHgrow( playBtn, Priority.ALWAYS );
            playBtn.setDisable( AnnouncementManager.getDisableGameplay() );
            playBtn.setOnAction( e -> startPlay( pack ) );

            MFXButton websiteBtn = new MFXButton( "Website" );
            websiteBtn.getStyleClass().add( "heroCardSecondaryBtn" );
            websiteBtn.setMinHeight( BTN_H );
            websiteBtn.setPrefHeight( BTN_H );
            websiteBtn.setMaxHeight( BTN_H );
            websiteBtn.setPrefWidth( 96 );
            websiteBtn.setOnAction( e -> openModpackWebsite( pack ) );
            if ( pack.getPackURL() == null || pack.getPackURL().isBlank() ) {
                websiteBtn.setDisable( true );
            }

            actions.getChildren().addAll( playBtn, websiteBtn );

            // Assemble
            getChildren().addAll( imageBox, info, actions );

            // Card-level interactions
            setContextMenu( pack );
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
            PauseTransition singleClickTimer = new PauseTransition( Duration.millis( 220 ) );
            singleClickTimer.setOnFinished( ev -> openDetailModal( pack ) );
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
        }

        private void setContextMenu( GameModPack pack ) {
            ContextMenu menu = buildPackContextMenu( pack );
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

    /** Resolves the pack's own background image to a file URL, or null when no per-pack image
     *  exists. The caller (ModpackHeroCard.<init>) handles the null path with a procedural
     *  gradient via {@link #applyDynamicBackground}.
     *
     *  <p>Important: {@code pack.hasCustomBackground()} is the canonical "does this pack ship
     *  its own image" signal. The local-cache file at {@code getPackBackgroundFilepath()}
     *  exists for default-image packs too (the environment downloads MODPACK_DEFAULT_BG_URL
     *  into a local cache), so checking just file existence would wrongly point at the
     *  cached-bundled-default and skip the procedural-background path. We gate on
     *  hasCustomBackground first.</p>
     */
    private static String resolveBackgroundUrl( GameModPack pack ) {
        try {
            if ( !pack.hasCustomBackground() ) {
                return null;
            }
            String path = pack.getPackBackgroundFilepath();
            if ( path != null ) {
                File f = new File( path );
                if ( f.exists() && f.length() > 0 ) return f.toURI().toString();
            }
        }
        catch ( Exception ignored ) { /* fall through */ }
        return null;
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
     *  Forge) without duplicating the histogram code. */
    static void applyDynamicBackground( Region bgLayer, GameModPack pack, Image logoImage )
    {
        if ( pack.isVanillaVersion() ) {
            bgLayer.getStyleClass().add( "heroBackgroundDefaultVanilla" );
            return;
        }
        if ( logoImage == null ) {
            bgLayer.getStyleClass().add( "heroBackgroundDefaultForge" );
            return;
        }

        // Cache hit path — when the user navigates away and back, the logo file path is
        // stable per pack, so we skip the histogram work entirely.
        String cacheKey = packLogoCacheKey( pack );
        DominantColors cached = ( cacheKey != null ) ? DOMINANT_COLOR_CACHE.get( cacheKey ) : null;
        if ( cached != null ) {
            bgLayer.setStyle( buildGradientStyle( cached ) );
            return;
        }

        // Logo loaded: sample immediately. May still return null if the logo is fully
        // transparent / monochrome — fall through to Forge default in that case.
        if ( logoImage.getProgress() >= 1.0 && !logoImage.isError() ) {
            DominantColors fresh = computeDominantColors( logoImage );
            if ( fresh != null ) {
                if ( cacheKey != null ) {
                    DOMINANT_COLOR_CACHE.put( cacheKey, fresh );
                }
                bgLayer.setStyle( buildGradientStyle( fresh ) );
                return;
            }
            bgLayer.getStyleClass().add( "heroBackgroundDefaultForge" );
            return;
        }

        // Logo still loading: show the Forge default while we wait, swap in the derived
        // gradient as soon as the load completes. Listener fires on the FX thread so it's
        // safe to mutate styleClass / setStyle directly. The result is cached so a future
        // re-construction of this card doesn't repeat the sample.
        bgLayer.getStyleClass().add( "heroBackgroundDefaultForge" );
        logoImage.progressProperty().addListener( ( obs, oldVal, newVal ) -> {
            if ( newVal.doubleValue() < 1.0 || logoImage.isError() ) {
                return;
            }
            DominantColors fresh = computeDominantColors( logoImage );
            if ( fresh != null ) {
                if ( cacheKey != null ) {
                    DOMINANT_COLOR_CACHE.put( cacheKey, fresh );
                }
                bgLayer.getStyleClass().remove( "heroBackgroundDefaultForge" );
                bgLayer.setStyle( buildGradientStyle( fresh ) );
            }
        } );
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
                    DominantColors colors = computeDominantColors( img );
                    if ( colors != null ) {
                        DOMINANT_COLOR_CACHE.put( url, colors );
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

    /** Builds the CSS {@code -fx-background-color: linear-gradient(...)} declaration from
     *  a sampled {@link DominantColors}. When two distinct colors were found the gradient
     *  uses them directly with a 50%-blended midpoint for a smoother transition; when only
     *  one color was found the second stop is derived as a darker variant of the first. */
    private static String buildGradientStyle( DominantColors colors )
    {
        if ( colors.hasSecondary() ) {
            // Linear gradient diagonally across the card image, primary at the top-left
            // corner, secondary at the bottom-right, 50/50 blend in the middle. The
            // midpoint blend smooths out the transition so the gradient doesn't feel like
            // two-stripe poster art.
            Color mid = blend( colors.primary(), colors.secondary(), 0.5 );
            return String.format(
                    "-fx-background-color: linear-gradient(to bottom right, %s 0%%, %s 50%%, %s 100%%);",
                    toHexRgb( colors.primary() ), toHexRgb( mid ), toHexRgb( colors.secondary() ) );
        }
        // Single-color fallback — pair primary with a derived darker stop for depth.
        Color dim    = colors.primary().deriveColor( 0.0, 1.0, 0.60, 1.0 );
        Color shadow = colors.primary().deriveColor( 0.0, 1.0, 0.35, 1.0 );
        return String.format(
                "-fx-background-color: linear-gradient(to bottom right, %s 0%%, %s 55%%, %s 100%%);",
                toHexRgb( colors.primary() ), toHexRgb( dim ), toHexRgb( shadow ) );
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

    private static Image resolveLogoImage( GameModPack pack ) {
        try {
            String path = pack.getPackLogoFilepath();
            if ( path != null ) {
                File f = new File( path );
                if ( f.exists() ) return new Image( f.toURI().toString(), true );
            }
        }
        catch ( Exception ignored ) { /* fall through */ }
        try {
            return new Image( ModPackConstants.MODPACK_DEFAULT_LOGO_URL, true );
        }
        catch ( Exception ignored ) {
            return null;
        }
    }

    private static String safeMinecraftVersion( GameModPack pack ) {
        try { return pack.getMinecraftVersion(); }
        catch ( Exception ignored ) { return null; }
    }

    private static String safeForgeVersion( GameModPack pack ) {
        try { return pack.getForgeVersion(); }
        catch ( Exception ignored ) { return null; }
    }

    private static void openModpackWebsite( GameModPack pack ) {
        SystemUtilities.spawnNewTask( () -> {
            try {
                Desktop.getDesktop().browse( URI.create( pack.getPackURL() ) );
            }
            catch ( IOException e ) {
                Logger.logError( "Unable to open your browser. Please visit " + pack.getPackURL() +
                                         " to view the mod pack's website!" );
                Logger.logThrowable( e );
            }
        } );
    }

    private static ContextMenu buildPackContextMenu( GameModPack pack ) {
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

        openFolder.setOnAction(       e -> openPackSubfolder( pack, "" ) );
        openScreenshots.setOnAction(  e -> openPackSubfolder( pack, "screenshots" ) );
        openResourcePks.setOnAction(  e -> openPackSubfolder( pack, "resourcepacks" ) );
        openShaderPacks.setOnAction(  e -> openPackSubfolder( pack, "shaderpacks" ) );
        openMods.setOnAction(         e -> openPackSubfolder( pack, "mods" ) );
        openConfig.setOnAction(       e -> openPackSubfolder( pack, "config" ) );
        createShortcut.setOnAction(   e -> createDesktopShortcut( pack ) );
        copyInviteLink.setOnAction(   e -> copyInviteLinkToClipboard( pack ) );

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
                                new SeparatorMenuItem(), createShortcut, copyInviteLink );
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
            NotificationManager.warn( "No invite link",
                                      "This pack doesn't have anything to invite friends to (no manifest URL or vanilla version)." );
            return;
        }
        GUIUtilities.JFXPlatformRun( () -> {
            javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
            content.putString( invite );
            javafx.scene.input.Clipboard.getSystemClipboard().setContent( content );
            NotificationManager.success( "Invite link copied",
                                         "Paste it in Discord or anywhere else to invite friends." );
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
                        alert.setTitle( "Shortcut Created" );
                        alert.setHeaderText( null );
                        alert.setContentText( "Desktop shortcut created for " + pack.getPackName() + "." );
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
                    GUIUtilities.showErrorMessage( "Unable to create desktop shortcut: " + ex.getMessage(), ownerStage );
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
