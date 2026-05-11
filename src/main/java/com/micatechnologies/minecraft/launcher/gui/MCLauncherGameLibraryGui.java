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

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.launcher.LauncherCore;
import com.micatechnologies.minecraft.launcher.consts.ModPackConstants;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.game.modpack.GameModPack;
import com.micatechnologies.minecraft.launcher.game.modpack.GameModPackManager;
import com.micatechnologies.minecraft.launcher.game.modpack.VanillaVersionManager;
import com.micatechnologies.minecraft.launcher.utilities.AnnouncementManager;
import com.micatechnologies.minecraft.launcher.utilities.NotificationManager;
import com.micatechnologies.minecraft.launcher.utilities.SystemUtilities;
import io.github.palexdev.materialfx.controls.MFXButton;
import io.github.palexdev.materialfx.controls.MFXComboBox;
import io.github.palexdev.materialfx.controls.MFXTextField;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.CacheHint;
import javafx.scene.Cursor;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.RowConstraints;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import javafx.stage.Stage;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Unified "Game Library" screen — combines what used to be the separate Edit Modpacks and
 * Vanilla Versions GUIs into a single browse-and-manage surface with hero-card layout,
 * type + status filtering, and free-text search.
 *
 * <p>Cards represent four kinds of entries:</p>
 * <ul>
 *     <li><b>Installed modpacks</b> — Uninstall + Edit actions.</li>
 *     <li><b>Available modpacks</b> (from the public manifest) — Install action.</li>
 *     <li><b>Installed vanilla versions</b> — Uninstall.</li>
 *     <li><b>Available vanilla versions</b> (from the Mojang manifest, filtered by type)
 *         — Install.</li>
 * </ul>
 *
 * <p>Cards reuse the main-menu hero-card visual: image area on top with the same procedural
 * gradient logic ({@link MCLauncherMainGui#applyDynamicBackground}), then logo + name + chips
 * + status + action buttons below. Cards are noticeably smaller than the main-menu version
 * (300×content vs 360×content) so 3-4 fit per row on a typical window width.</p>
 */
public class MCLauncherGameLibraryGui extends MCLauncherAbstractGui
{
    // ===== FXML — top bar =====
    @SuppressWarnings( "unused" ) @FXML MFXTextField searchField;
    @SuppressWarnings( "unused" ) @FXML MFXComboBox< String > typeFilter;
    @SuppressWarnings( "unused" ) @FXML MFXComboBox< String > statusFilter;

    // ===== FXML — pagination bar =====
    @SuppressWarnings( "unused" ) @FXML Label paginationRangeLabel;
    @SuppressWarnings( "unused" ) @FXML Label paginationPageLabel;
    @SuppressWarnings( "unused" ) @FXML MFXButton prevPageBtn;
    @SuppressWarnings( "unused" ) @FXML MFXButton nextPageBtn;
    @SuppressWarnings( "unused" ) @FXML MFXComboBox< Integer > pageSizeFilter;

    // ===== FXML — card grid =====
    @SuppressWarnings( "unused" ) @FXML ScrollPane libraryScrollPane;
    @SuppressWarnings( "unused" ) @FXML FlowPane cardList;

    // ===== FXML — bottom bar =====
    @SuppressWarnings( "unused" ) @FXML MFXTextField urlAddField;
    @SuppressWarnings( "unused" ) @FXML MFXButton urlAddBtn;
    @SuppressWarnings( "unused" ) @FXML MFXButton editorBtn;
    @SuppressWarnings( "unused" ) @FXML MFXButton hostingManifestBtn;
    @SuppressWarnings( "unused" ) @FXML MFXButton returnBtn;

    // ===== FXML — announcement banner =====
    @SuppressWarnings( "unused" ) @FXML Label announcement;
    @SuppressWarnings( "unused" ) @FXML RowConstraints announcementRow;
    @SuppressWarnings( "unused" ) @FXML Label helpBtn;

    // Filter options exposed in the dropdowns. Strings rather than enum so FXML/MFX can
    // bind them directly.
    private static final String TYPE_ALL              = "All";
    private static final String TYPE_MODPACKS         = "Modpacks";
    private static final String TYPE_VANILLA_RELEASE  = "Vanilla — Releases";
    private static final String TYPE_VANILLA_SNAPSHOT = "Vanilla — Snapshots";
    private static final String TYPE_VANILLA_BETA     = "Vanilla — Old Beta";
    private static final String TYPE_VANILLA_ALPHA    = "Vanilla — Old Alpha";

    private static final String STATUS_ALL         = "All";
    private static final String STATUS_INSTALLED   = "Installed";
    private static final String STATUS_AVAILABLE   = "Available";

    private static final List< Integer > PAGE_SIZES = List.of( 20, 40, 60 );
    private static final int DEFAULT_PAGE_SIZE = 20;

    private int pageSize    = DEFAULT_PAGE_SIZE;
    private int currentPage = 1;  // 1-based

    public MCLauncherGameLibraryGui( Stage stage ) throws IOException {
        super( stage );
    }

    @Override
    String getSceneFxmlPath() { return "gui/gameLibraryGUI.fxml"; }

    @Override
    String getSceneName() { return "Library"; }

    @Override
    HelpTopic getHelpTopic() { return HelpTopic.EDIT_MODPACKS; }

    @Override
    void setup()
    {
        stage.setOnCloseRequest( windowEvent -> {
            windowEvent.consume();
            LauncherCore.closeApp();
        } );

        // Announcement banner — reuse the edit-modpacks announcement text since this screen
        // takes over its role.
        String announcementText = AnnouncementManager.getAnnouncementModpacksEdit();
        if ( announcementText != null && !announcementText.isEmpty() ) {
            announcement.setText( announcementText );
            announcement.setMinHeight( 40 );
            announcement.setPrefHeight( 40 );
            announcement.setMaxHeight( 40 );
            announcementRow.setMinHeight( 40 );
            announcementRow.setPrefHeight( 40 );
            announcementRow.setMaxHeight( 40 );
        }
        else {
            announcement.setText( "" );
            announcement.setMinHeight( 0 );
            announcement.setPrefHeight( 0 );
            announcement.setMaxHeight( 0 );
            announcementRow.setMinHeight( 0 );
            announcementRow.setPrefHeight( 0 );
            announcementRow.setMaxHeight( 0 );
        }

        // Filters
        typeFilter.setItems( FXCollections.observableArrayList(
                TYPE_ALL, TYPE_MODPACKS, TYPE_VANILLA_RELEASE, TYPE_VANILLA_SNAPSHOT,
                TYPE_VANILLA_BETA, TYPE_VANILLA_ALPHA ) );
        typeFilter.selectItem( TYPE_ALL );
        typeFilter.setOnAction( e -> { currentPage = 1; rebuildCards(); } );

        statusFilter.setItems( FXCollections.observableArrayList(
                STATUS_ALL, STATUS_INSTALLED, STATUS_AVAILABLE ) );
        statusFilter.selectItem( STATUS_INSTALLED );
        statusFilter.setOnAction( e -> { currentPage = 1; rebuildCards(); } );

        // Search — rebuild on every change. FlowPane rebuild is cheap enough that we don't
        // need to debounce; if a real responsiveness issue surfaces, wrap in a PauseTransition.
        searchField.textProperty().addListener( ( obs, oldVal, newVal ) -> {
            currentPage = 1;
            rebuildCards();
        } );

        // These two fields use promptText only — they have no floatingText label,
        // so we disable MaterialFX's floating-text feature entirely. Without this,
        // the skin still allocates and paints an empty plate in the top-left corner
        // (driven by the `:floating` pseudo-class flipping on focus / content state)
        // and the UI shows a ghost label that doesn't go anywhere.
        searchField.setFloatMode( io.github.palexdev.materialfx.enums.FloatMode.DISABLED );
        urlAddField.setFloatMode( io.github.palexdev.materialfx.enums.FloatMode.DISABLED );

        // Wire the navbar help button — same pattern as the other screens with a
        // declared helpBtn in FXML. MCLauncherGuiWindow.injectHelpButton sees this
        // exists and skips the corner-overlay fallback.
        if ( helpBtn != null ) {
            helpBtn.setOnMouseClicked( e -> MCLauncherHelpWindow.show( getHelpTopic() ) );
            helpBtn.setCursor( javafx.scene.Cursor.HAND );
        }

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

        // URL "Add" → install + refresh
        urlAddBtn.setDisable( AnnouncementManager.getDisableModpacksEdit() );
        urlAddBtn.setOnAction( e -> {
            String url = urlAddField.getText();
            if ( url == null || url.isBlank() ) {
                return;
            }
            urlAddField.clear();
            SystemUtilities.spawnNewTask( () -> {
                GameModPackManager.installModPackByURL( url );
                GUIUtilities.JFXPlatformRun( this::rebuildCards );
            } );
        } );

        // Modpack Editor
        editorBtn.setOnAction( e -> SystemUtilities.spawnNewTask( () -> {
            try { MCLauncherGuiController.goToModPackEditorGui(); }
            catch ( IOException ex ) {
                Logger.logError( "Unable to open modpack editor." );
                Logger.logThrowable( ex );
            }
        } ) );

        // Hosting manifest export — same flow as the old Edit Packs screen
        hostingManifestBtn.setOnAction( e -> {
            javafx.stage.FileChooser fc = new javafx.stage.FileChooser();
            fc.setTitle( "Save Hosting Manifest" );
            fc.setInitialFileName( "installable.json" );
            fc.getExtensionFilters().add(
                    new javafx.stage.FileChooser.ExtensionFilter( "JSON Files", "*.json" ) );
            File file = fc.showSaveDialog( stage );
            if ( file == null ) {
                return;
            }
            SystemUtilities.spawnNewTask( () -> {
                try {
                    List< String > urls = GameModPackManager.getInstalledModPackURLs();
                    JsonObject manifest = new JsonObject();
                    JsonArray available = new JsonArray();
                    for ( String u : urls ) {
                        available.add( u );
                    }
                    manifest.add( ModPackConstants.AVAILABLE_PACKS_MANIFEST_LIST_KEY, available );
                    String json = new GsonBuilder().setPrettyPrinting().create().toJson( manifest );
                    java.nio.file.Files.writeString( file.toPath(), json,
                                                     java.nio.charset.StandardCharsets.UTF_8 );
                    GUIUtilities.JFXPlatformRun( () -> hostingManifestBtn.setText( "Saved!" ) );
                    SystemUtilities.spawnNewTask( () -> {
                        try { Thread.sleep( 2000 ); }
                        catch ( InterruptedException ignored ) {}
                        GUIUtilities.JFXPlatformRun(
                                () -> hostingManifestBtn.setText( "Generate Hosting Manifest" ) );
                    } );
                }
                catch ( Exception ex ) {
                    Logger.logError( "Failed to generate hosting manifest: " + ex.getMessage() );
                }
            } );
        } );

        // Return
        returnBtn.setOnAction( e -> SystemUtilities.spawnNewTask( () -> {
            try { MCLauncherGuiController.goToMainGui(); }
            catch ( IOException ex ) {
                Logger.logError( "Unable to return to main GUI." );
                Logger.logThrowable( ex );
            }
        } ) );
    }

    @Override
    void afterShow()
    {
        // Build the initial card list off the FX thread — vanilla-version fetch can take a
        // moment if the Mojang manifest hasn't been cached yet.
        SystemUtilities.spawnNewTask( () -> {
            GUIUtilities.JFXPlatformRun( this::rebuildCards );
        } );
    }

    @Override
    void cleanup() { /* nothing to tear down — filter listeners die with the scene */ }

    // =========================================================================================
    //  Card list assembly
    // =========================================================================================

    /** Rebuilds the FlowPane's card list from current filter + search + pagination state.
     *  Called on filter changes, search-text changes, page changes, and after install /
     *  uninstall actions complete. Must run on the FX thread. */
    private void rebuildCards()
    {
        String type   = Objects.requireNonNullElse( typeFilter.getValue(),   TYPE_ALL );
        String status = Objects.requireNonNullElse( statusFilter.getValue(), STATUS_INSTALLED );
        String search = searchField.getText() == null ? "" : searchField.getText().trim().toLowerCase( Locale.ROOT );

        List< LibraryEntry > entries = collectEntries( type, status );
        if ( !search.isEmpty() ) {
            entries.removeIf( e -> !e.displayName.toLowerCase( Locale.ROOT ).contains( search ) );
        }

        // Pagination math. Clamp currentPage to [1, totalPages] so out-of-range states from
        // prev/next clicks or filter changes that shrink the list quietly snap back.
        int totalItems = entries.size();
        int totalPages = Math.max( 1, ( totalItems + pageSize - 1 ) / pageSize );
        if ( currentPage < 1 ) currentPage = 1;
        if ( currentPage > totalPages ) currentPage = totalPages;
        int startIdx = ( currentPage - 1 ) * pageSize;
        int endIdx   = Math.min( totalItems, startIdx + pageSize );

        // Update pagination UI labels + enable state
        updatePaginationControls( totalItems, totalPages, startIdx, endIdx );

        cardList.getChildren().clear();
        if ( entries.isEmpty() ) {
            cardList.getChildren().add( buildEmptyState( type, status, search ) );
            return;
        }
        for ( int i = startIdx; i < endIdx; i++ ) {
            cardList.getChildren().add( new LibraryCard( entries.get( i ) ) );
        }
    }

    /** Updates the pagination bar's text labels and button enable state for the current page
     *  state. Called from {@link #rebuildCards} after the visible slice is computed. */
    private void updatePaginationControls( int totalItems, int totalPages, int startIdx, int endIdx )
    {
        if ( totalItems == 0 ) {
            paginationRangeLabel.setText( "No items" );
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

    /** Collects entries from the GameModPackManager + VanillaVersionManager according to the
     *  current type filter. Status filtering happens in here too so we don't allocate cards
     *  we're just going to discard. */
    private List< LibraryEntry > collectEntries( String type, String status )
    {
        List< LibraryEntry > out = new ArrayList<>();
        boolean wantInstalled = !STATUS_AVAILABLE.equals( status );
        boolean wantAvailable = !STATUS_INSTALLED.equals( status );
        boolean wantModpacks  = TYPE_ALL.equals( type ) || TYPE_MODPACKS.equals( type );
        boolean wantVanilla   = !TYPE_MODPACKS.equals( type );

        // Installed modpacks
        if ( wantModpacks && wantInstalled ) {
            for ( GameModPack pack : GameModPackManager.getInstalledModPacks() ) {
                out.add( LibraryEntry.installedModpack( pack ) );
            }
        }
        // Available manifest modpacks (those not yet installed). Pull the rich GameModPack
        // objects via getAvailableModPacks() instead of just friendly names — that way we
        // have access to packLogoURL for the card's logo image.
        if ( wantModpacks && wantAvailable ) {
            List< GameModPack > available = GameModPackManager.getAvailableModPacks();
            List< String > installedFriendly = new ArrayList<>();
            for ( GameModPack pack : GameModPackManager.getInstalledModPacks() ) {
                if ( pack.getFriendlyName() != null ) {
                    installedFriendly.add( pack.getFriendlyName() );
                }
            }
            if ( available != null ) {
                for ( GameModPack availPack : available ) {
                    String friendly = availPack.getFriendlyName();
                    if ( friendly == null || !installedFriendly.contains( friendly ) ) {
                        out.add( LibraryEntry.availableModpack( availPack ) );
                    }
                }
            }
        }

        // Vanilla versions — installed always come first within their type bucket; available
        // are capped at VANILLA_VISIBLE_CAP to keep the FlowPane snappy.
        if ( wantVanilla ) {
            List< String > installedIds = VanillaVersionManager.getInstalledVersionIds();
            for ( String vanillaType : vanillaTypesFor( type ) ) {
                List< JsonObject > versions = VanillaVersionManager.getVersionsByType( vanillaType );
                if ( versions == null ) {
                    continue;
                }
                // No visible cap any more — pagination handles the slice cost. LibraryEntry
                // construction is cheap (record + small list); the FlowPane only renders the
                // current page's worth of cards.
                for ( JsonObject version : versions ) {
                    String id = version.get( "id" ).getAsString();
                    boolean installed = installedIds.contains( id );
                    if ( installed && wantInstalled ) {
                        out.add( LibraryEntry.installedVanilla( id, version ) );
                    }
                    else if ( !installed && wantAvailable ) {
                        out.add( LibraryEntry.availableVanilla( id, version ) );
                    }
                }
            }
        }
        return out;
    }

    /** Maps a top-level type filter to the vanilla-type strings VanillaVersionManager
     *  recognizes. TYPE_ALL gets every type so "Installed" status pulls every installed
     *  vanilla regardless of release / snapshot / beta / alpha. */
    private static List< String > vanillaTypesFor( String type )
    {
        return switch ( type ) {
            case TYPE_VANILLA_RELEASE  -> List.of( "release" );
            case TYPE_VANILLA_SNAPSHOT -> List.of( "snapshot" );
            case TYPE_VANILLA_BETA     -> List.of( "old_beta" );
            case TYPE_VANILLA_ALPHA    -> List.of( "old_alpha" );
            case TYPE_ALL              -> List.of( "release", "snapshot", "old_beta", "old_alpha" );
            default                    -> Collections.emptyList();
        };
    }

    /** Friendly empty-state shown when filter + search produced no matches. */
    private static javafx.scene.Node buildEmptyState( String type, String status, String search )
    {
        VBox box = new VBox( 8 );
        box.setAlignment( Pos.CENTER );
        box.setPrefHeight( 280 );
        Label heading = new Label( "Nothing matches those filters" );
        heading.getStyleClass().add( "heading-h1" );
        Label sub;
        if ( !search.isEmpty() ) {
            sub = new Label( "No items in “" + type + " · " + status + "” match \"" + search + "\"." );
        }
        else {
            sub = new Label( "Switch the Type or Status filters to see more." );
        }
        sub.getStyleClass().add( "muted" );
        box.getChildren().addAll( heading, sub );
        return box;
    }

    // =========================================================================================
    //  LibraryEntry — unified data model for one card
    // =========================================================================================

    private static final class LibraryEntry
    {
        enum Kind { MODPACK_INSTALLED, MODPACK_AVAILABLE, VANILLA_INSTALLED, VANILLA_AVAILABLE }

        final Kind kind;
        final String displayName;
        final List< String > chips;
        final String statusText;
        /** Carries the pack reference for BOTH installed and available modpacks now —
         *  available manifest packs are pulled via getAvailableModPacks() returning the rich
         *  GameModPack objects with logo / background URLs, so the card can render a logo
         *  without the friendly-name → URL → install detour. */
        final GameModPack pack;
        final String vanillaVersionId;   // non-null for VANILLA_*
        final JsonObject vanillaInfo;    // non-null for VANILLA_AVAILABLE

        private LibraryEntry( Kind kind, String displayName, List< String > chips, String statusText,
                              GameModPack pack, String vanillaVersionId, JsonObject vanillaInfo )
        {
            this.kind = kind;
            this.displayName = displayName;
            this.chips = chips;
            this.statusText = statusText;
            this.pack = pack;
            this.vanillaVersionId = vanillaVersionId;
            this.vanillaInfo = vanillaInfo;
        }

        static LibraryEntry installedModpack( GameModPack pack )
        {
            String name = pack.getFriendlyName() != null ? pack.getFriendlyName() : pack.getPackName();
            List< String > chips = new ArrayList<>();
            chips.add( "Modpack" );
            try {
                String mc = pack.getMinecraftVersion();
                if ( mc != null && !mc.isBlank() ) chips.add( "Minecraft " + mc );
            }
            catch ( Exception ignored ) { /* leave off if not resolvable */ }
            // Packs auto-update on launch and there's no manual update path, so "Update
             // available" was misleading. When the local on-disk pack differs from the
             // hosted manifest we surface that as "Recently updated" to indicate the
             // launcher has fresh metadata cached for the next launch.
            String status = pack.isUpdateAvailable() ? "Recently updated" : "Installed";
            return new LibraryEntry( Kind.MODPACK_INSTALLED, name, chips, status, pack, null, null );
        }

        static LibraryEntry availableModpack( GameModPack pack )
        {
            String name = pack.getFriendlyName() != null ? pack.getFriendlyName() : pack.getPackName();
            List< String > chips = new ArrayList<>();
            chips.add( "Modpack" );
            try {
                String mc = pack.getMinecraftVersion();
                if ( mc != null && !mc.isBlank() ) chips.add( "Minecraft " + mc );
            }
            catch ( Exception ignored ) { /* leave off if not resolvable */ }
            return new LibraryEntry( Kind.MODPACK_AVAILABLE, name, chips, "From manifest",
                                     pack, null, null );
        }

        static LibraryEntry installedVanilla( String versionId, JsonObject info )
        {
            String typeStr = info.has( "type" ) ? info.get( "type" ).getAsString() : "release";
            List< String > chips = new ArrayList<>();
            chips.add( "Vanilla" );
            chips.add( prettyVanillaType( typeStr ) );
            return new LibraryEntry( Kind.VANILLA_INSTALLED, "Minecraft " + versionId,
                                     chips, "Installed", null, versionId, info );
        }

        static LibraryEntry availableVanilla( String versionId, JsonObject info )
        {
            String typeStr = info.has( "type" ) ? info.get( "type" ).getAsString() : "release";
            List< String > chips = new ArrayList<>();
            chips.add( "Vanilla" );
            chips.add( prettyVanillaType( typeStr ) );
            String date = info.has( "releaseTime" ) && info.get( "releaseTime" ).getAsString().length() >= 10
                          ? info.get( "releaseTime" ).getAsString().substring( 0, 10 )
                          : "";
            String status = date.isEmpty() ? "Available" : "Released " + date;
            return new LibraryEntry( Kind.VANILLA_AVAILABLE, "Minecraft " + versionId,
                                     chips, status, null, versionId, info );
        }

        private static String prettyVanillaType( String t )
        {
            return switch ( t ) {
                case "release"   -> "Release";
                case "snapshot"  -> "Snapshot";
                case "old_beta"  -> "Old Beta";
                case "old_alpha" -> "Old Alpha";
                default          -> t;
            };
        }
    }

    // =========================================================================================
    //  LibraryCard — visual representation of one LibraryEntry
    // =========================================================================================

    private final class LibraryCard extends VBox
    {
        private static final double CARD_WIDTH   = 300;
        private static final double IMAGE_HEIGHT = 110;

        LibraryCard( LibraryEntry entry )
        {
            getStyleClass().add( "heroCardShell" );
            setPrefWidth( CARD_WIDTH );
            setMinWidth( CARD_WIDTH );
            setMaxWidth( CARD_WIDTH );
            setSpacing( 0 );
            // Same bitmap-cache trick as the main-menu hero cards — keeps scroll lag minimal
            // even with a few hundred cards in the FlowPane.
            setCache( true );
            setCacheHint( CacheHint.SPEED );

            // ----- Image area (procedural gradient via shared main-menu logic) -----
            StackPane imageBox = new StackPane();
            imageBox.getStyleClass().add( "heroCardImage" );
            imageBox.setPrefHeight( IMAGE_HEIGHT );
            imageBox.setMinHeight( IMAGE_HEIGHT );
            imageBox.setMaxHeight( IMAGE_HEIGHT );
            Rectangle imageClip = new Rectangle( CARD_WIDTH, IMAGE_HEIGHT );
            imageClip.setArcWidth( 28 );
            imageClip.setArcHeight( 28 );
            imageClip.heightProperty().bind( imageBox.heightProperty() );
            imageClip.widthProperty().bind( imageBox.widthProperty() );
            imageBox.setClip( imageClip );

            Region bgLayer = new Region();
            bgLayer.getStyleClass().add( "heroBackground" );

            Region imageVeil = new Region();
            imageVeil.getStyleClass().add( "heroCardImageVeil" );

            // Badge row (top-right): a contextual status badge — "Installed" / "Update" / etc.
            HBox badgeRow = new HBox( 6 );
            badgeRow.setAlignment( Pos.TOP_RIGHT );
            badgeRow.setPadding( new Insets( 8, 10, 0, 0 ) );
            String badge = topBadgeFor( entry );
            String badgeStyle = topBadgeStyleFor( entry );
            if ( badge != null ) {
                badgeRow.getChildren().add( buildChip( badge, badgeStyle ) );
            }

            imageBox.getChildren().addAll( bgLayer, imageVeil, badgeRow );

            // For installed entries (we have a GameModPack), reuse the main-menu's procedural-
            // background logic — gets logo-derived gradients for modded, sky→grass for vanilla.
            // For available-but-not-installed entries, fall back to the Forge default class
            // (modpack) or the vanilla class (vanilla) so we still get something distinct
            // rather than a flat surface.
            applyEntryBackground( bgLayer, entry );

            // ----- Logo overlay (matches main-menu visual) -----
            StackPane logoContainer = new StackPane();
            logoContainer.getStyleClass().add( "heroPackLogoContainer" );
            logoContainer.setMinSize( 56, 56 );
            logoContainer.setMaxSize( 56, 56 );
            ImageView logo = new ImageView();
            logo.setFitWidth( 52 );
            logo.setFitHeight( 52 );
            logo.setPreserveRatio( true );
            Image logoImage = resolveLogoForEntry( entry );
            if ( logoImage != null ) {
                logo.setImage( logoImage );
            }
            Rectangle logoClip = new Rectangle( 52, 52 );
            logoClip.setArcWidth( 12 );
            logoClip.setArcHeight( 12 );
            logo.setClip( logoClip );
            logoContainer.getChildren().add( logo );
            logoContainer.setTranslateY( -28 );

            // ----- Body (name, chips, status) -----
            VBox info = new VBox( 4 );
            info.getStyleClass().add( "heroCardBody" );
            info.setAlignment( Pos.TOP_LEFT );
            info.setPadding( new Insets( 0, 14, 10, 14 ) );

            Label name = new Label( entry.displayName );
            name.getStyleClass().addAll( "heading-h3", "heroCardTitle" );
            name.setWrapText( true );

            HBox chips = new HBox( 6 );
            chips.setAlignment( Pos.CENTER_LEFT );
            for ( String chip : entry.chips ) {
                chips.getChildren().add( buildChip( chip ) );
            }

            Label status = new Label( entry.statusText );
            status.getStyleClass().add( "heroLastPlayedSurface" );

            info.getChildren().addAll( logoContainer, name, chips, status );
            VBox.setVgrow( info, Priority.ALWAYS );

            // ----- Action row -----
            HBox actions = new HBox( 8 );
            actions.setAlignment( Pos.CENTER_LEFT );
            actions.setPadding( new Insets( 0, 14, 12, 14 ) );
            for ( javafx.scene.Node node : buildActionsFor( entry ) ) {
                actions.getChildren().add( node );
            }

            getChildren().addAll( imageBox, info, actions );
            setCursor( Cursor.HAND );
        }

        /** Top-right badge text — "Installed" / "Available" / "Recently updated". */
        private String topBadgeFor( LibraryEntry entry )
        {
            return switch ( entry.kind ) {
                case MODPACK_INSTALLED  -> ( entry.pack != null && entry.pack.isUpdateAvailable() )
                                           ? "Recently updated" : "Installed";
                case VANILLA_INSTALLED  -> "Installed";
                case MODPACK_AVAILABLE  -> "Available";
                case VANILLA_AVAILABLE  -> null;  // Status row text already says "Available" / "Released ..."
            };
        }

        private String topBadgeStyleFor( LibraryEntry entry )
        {
            return switch ( entry.kind ) {
                case MODPACK_INSTALLED  -> "stat-chip-success";
                case VANILLA_INSTALLED  -> "stat-chip-success";
                // Brand-blue "info" rather than yellow "warn" for Available — yellow + white
                // was a contrast trainwreck in the prior pass.
                case MODPACK_AVAILABLE  -> "stat-chip-info";
                case VANILLA_AVAILABLE  -> "stat-chip-info";
            };
        }

        /** Per-kind action buttons. Returns the action HBox children (buttons). */
        private List< javafx.scene.Node > buildActionsFor( LibraryEntry entry )
        {
            List< javafx.scene.Node > out = new ArrayList<>();
            switch ( entry.kind ) {
                case MODPACK_INSTALLED -> {
                    MFXButton uninstall = primaryAction( "Uninstall", "dangerZone", () -> uninstallInstalledModpack( entry ) );
                    MFXButton edit = secondaryAction( "Edit", () -> openModpackEditor() );
                    out.add( uninstall );
                    out.add( edit );
                }
                case MODPACK_AVAILABLE -> {
                    MFXButton install = primaryAction( "Install", "primary", () -> installAvailableModpack( entry ) );
                    out.add( install );
                }
                case VANILLA_INSTALLED -> {
                    MFXButton uninstall = primaryAction( "Uninstall", "dangerZone", () -> uninstallInstalledVanilla( entry ) );
                    out.add( uninstall );
                }
                case VANILLA_AVAILABLE -> {
                    MFXButton install = primaryAction( "Install", "primary", () -> installAvailableVanilla( entry ) );
                    out.add( install );
                }
            }
            return out;
        }

        private MFXButton primaryAction( String label, String styleClass, Runnable onClick )
        {
            MFXButton btn = new MFXButton( label );
            btn.getStyleClass().add( styleClass );
            btn.setPrefHeight( 32 );
            btn.setMaxWidth( Double.MAX_VALUE );
            HBox.setHgrow( btn, Priority.ALWAYS );
            btn.setOnAction( e -> onClick.run() );
            return btn;
        }

        private MFXButton secondaryAction( String label, Runnable onClick )
        {
            MFXButton btn = new MFXButton( label );
            btn.getStyleClass().add( "heroCardSecondaryBtn" );
            btn.setPrefHeight( 32 );
            btn.setPrefWidth( 80 );
            btn.setOnAction( e -> onClick.run() );
            return btn;
        }

        private Label buildChip( String text )
        {
            Label chip = new Label( text );
            chip.getStyleClass().add( "stat-chip" );
            return chip;
        }

        private Label buildChip( String text, String extraClass )
        {
            Label chip = new Label( text );
            chip.getStyleClass().add( "stat-chip" );
            if ( extraClass != null ) chip.getStyleClass().add( extraClass );
            return chip;
        }
    }

    // =========================================================================================
    //  Action handlers
    // =========================================================================================

    private void uninstallInstalledModpack( LibraryEntry entry )
    {
        if ( entry.pack == null ) return;
        int response = GUIUtilities.showQuestionMessage(
                "Uninstall Modpack",
                "Uninstall " + entry.displayName + "?",
                "Would you also like to delete the installed game files?",
                "Uninstall & Delete Files", "Uninstall (Keep Files)", stage );
        if ( response == 0 ) return;
        boolean deleteFiles = ( response == 1 );

        GameModPack pack = entry.pack;
        SystemUtilities.spawnNewTask( () -> {
            if ( deleteFiles ) {
                try {
                    File installDir = new File( pack.getPackRootFolder() );
                    if ( installDir.exists() ) {
                        org.codehaus.plexus.util.FileUtils.deleteDirectory( installDir );
                    }
                }
                catch ( Exception e ) {
                    Logger.logWarningSilent( "Could not fully delete install folder: " + e.getMessage() );
                }
            }
            GameModPackManager.uninstallModPack( pack );
            GUIUtilities.JFXPlatformRun( this::rebuildCards );
        } );
    }

    private void installAvailableModpack( LibraryEntry entry )
    {
        if ( entry.pack == null ) return;
        // installModPackByFriendlyName matches the public manifest's friendly-name list, so
        // the available pack's getFriendlyName() is the right key.
        final String friendly = entry.pack.getFriendlyName();
        if ( friendly == null || friendly.isBlank() ) return;
        SystemUtilities.spawnNewTask( () -> {
            GameModPackManager.installModPackByFriendlyName( friendly );
            GUIUtilities.JFXPlatformRun( this::rebuildCards );
            NotificationManager.success( "Modpack installed", entry.displayName + " is now available to play." );
        } );
    }

    private void uninstallInstalledVanilla( LibraryEntry entry )
    {
        if ( entry.vanillaVersionId == null ) return;
        String id = entry.vanillaVersionId;
        int response = GUIUtilities.showQuestionMessage(
                "Uninstall Version",
                "Uninstall Minecraft " + id + "?",
                "Would you also like to delete the installed game files?",
                "Uninstall & Delete Files", "Uninstall (Keep Files)", stage );
        if ( response == 0 ) return;

        SystemUtilities.spawnNewTask( () -> {
            if ( response == 1 ) {
                GameModPack vanilla = GameModPack.createVanillaModPack( id );
                try {
                    File installDir = new File( vanilla.getPackRootFolder() );
                    if ( installDir.exists() ) {
                        org.codehaus.plexus.util.FileUtils.deleteDirectory( installDir );
                    }
                }
                catch ( Exception e ) {
                    Logger.logWarningSilent( "Could not fully delete install folder: " + e.getMessage() );
                }
            }
            VanillaVersionManager.uninstallVersion( id );
            GUIUtilities.JFXPlatformRun( this::rebuildCards );
        } );
    }

    private void installAvailableVanilla( LibraryEntry entry )
    {
        if ( entry.vanillaVersionId == null ) return;
        String id = entry.vanillaVersionId;
        SystemUtilities.spawnNewTask( () -> {
            if ( VanillaVersionManager.isInstalled( id ) ) {
                return;
            }
            VanillaVersionManager.installVersion( id );
            GUIUtilities.JFXPlatformRun( this::rebuildCards );
            NotificationManager.success( "Minecraft installed", "Minecraft " + id + " is now available to play." );
        } );
    }

    private void openModpackEditor()
    {
        SystemUtilities.spawnNewTask( () -> {
            try { MCLauncherGuiController.goToModPackEditorGui(); }
            catch ( IOException ex ) {
                Logger.logError( "Unable to open modpack editor." );
                Logger.logThrowable( ex );
            }
        } );
    }

    // =========================================================================================
    //  Background + logo resolution for cards
    // =========================================================================================

    private static void applyEntryBackground( Region bgLayer, LibraryEntry entry )
    {
        // Installed modpacks use the full dynamic-background pipeline (samples logo for a
        // two-color gradient). The dominant-color cache in MCLauncherMainGui keeps subsequent
        // visits fast.
        if ( entry.kind == LibraryEntry.Kind.MODPACK_INSTALLED && entry.pack != null ) {
            MCLauncherMainGui.applyDynamicBackground( bgLayer, entry.pack, resolveLogoForEntry( entry ) );
            return;
        }
        if ( entry.kind == LibraryEntry.Kind.VANILLA_INSTALLED && entry.vanillaVersionId != null ) {
            GameModPack synthetic = GameModPack.createVanillaModPack( entry.vanillaVersionId );
            MCLauncherMainGui.applyDynamicBackground( bgLayer, synthetic, resolveLogoForEntry( entry ) );
            return;
        }
        // Available modpacks — also run through the dynamic-background pipeline so cards
        // visually match their installed siblings. The cache means we only pay the histogram
        // cost on the first card visit per pack; the prefetcher in MCLauncherGuiController
        // populates the cache at app startup so by the time the user opens the library most
        // packs hit it instantly.
        if ( entry.kind == LibraryEntry.Kind.MODPACK_AVAILABLE && entry.pack != null ) {
            MCLauncherMainGui.applyDynamicBackground( bgLayer, entry.pack, resolveLogoForEntry( entry ) );
            return;
        }
        // Available vanilla versions — share the static vanilla CSS class with installed
        // vanilla; no logo means nothing to sample anyway.
        if ( entry.kind == LibraryEntry.Kind.VANILLA_AVAILABLE ) {
            bgLayer.getStyleClass().add( "heroBackgroundDefaultVanilla" );
            return;
        }
        bgLayer.getStyleClass().add( "heroBackgroundDefaultForge" );
    }

    /** Resolves the logo for a card entry. Critical perf note: for available modpacks we use
     *  {@code pack.getPackLogoURL()} (the source URL from the manifest) directly with
     *  {@code new Image(url, true)} rather than {@code getPackLogoFilepath()} — the latter
     *  triggers an environment cache download synchronously, which on a fresh launcher with
     *  dozens of available packs would block the FX thread for seconds. JavaFX Image with
     *  {@code backgroundLoading=true} fetches the URL on its own worker thread. */
    private static Image resolveLogoForEntry( LibraryEntry entry )
    {
        try {
            if ( entry.kind == LibraryEntry.Kind.MODPACK_INSTALLED && entry.pack != null ) {
                String path = entry.pack.getPackLogoFilepath();
                if ( path != null ) {
                    File f = new File( path );
                    if ( f.exists() ) return new Image( f.toURI().toString(), true );
                }
            }
            if ( entry.kind == LibraryEntry.Kind.MODPACK_AVAILABLE && entry.pack != null ) {
                String url = entry.pack.getPackLogoURL();
                if ( url != null && !url.isBlank() ) {
                    return new Image( url, true );
                }
            }
            if ( entry.kind == LibraryEntry.Kind.VANILLA_INSTALLED ) {
                return new Image( ModPackConstants.MODPACK_DEFAULT_LOGO_URL, true );
            }
        }
        catch ( Exception ignored ) { /* fall through */ }
        return null;
    }
}
