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
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
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
import java.util.Comparator;
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
    @SuppressWarnings( "unused" ) @FXML MFXComboBox< String > sortFilter;

    // ===== FXML — pagination bar =====
    @SuppressWarnings( "unused" ) @FXML Label paginationRangeLabel;
    @SuppressWarnings( "unused" ) @FXML Label backgroundFetchLabel;
    @SuppressWarnings( "unused" ) @FXML Label paginationPageLabel;
    @SuppressWarnings( "unused" ) @FXML MFXButton prevPageBtn;
    @SuppressWarnings( "unused" ) @FXML MFXButton nextPageBtn;
    @SuppressWarnings( "unused" ) @FXML MFXComboBox< Integer > pageSizeFilter;

    // ===== FXML — card grid =====
    @SuppressWarnings( "unused" ) @FXML ScrollPane libraryScrollPane;
    @SuppressWarnings( "unused" ) @FXML FlowPane cardList;

    /** Active import-in-progress card, prepended to the FlowPane during any
     *  Modrinth / future CurseForge import so the user can see at a glance
     *  that work is happening. Null when no import is in flight. Lives
     *  outside the filter/sort pipeline — always shown at position 0 of
     *  the FlowPane regardless of which page or filter the user is on, so
     *  the user can't accidentally hide an active import by changing the
     *  view. */
    private ImportProgressCard activeImportCard;

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
    // Loader-version filters — show all available Forge / NeoForge /
    // Fabric versions as installable "empty modpacks". Picking one
    // and clicking Install writes a real local manifest (under
    // imported-manifests/) so the result is a normal editable modpack.
    private static final String TYPE_FORGE_VERSIONS    = "Forge — Versions";
    private static final String TYPE_NEOFORGE_VERSIONS = "NeoForge — Versions";
    private static final String TYPE_FABRIC_VERSIONS   = "Fabric — Versions";

    private static final String STATUS_ALL         = "All";
    private static final String STATUS_INSTALLED   = "Installed";
    private static final String STATUS_AVAILABLE   = "Available";

    // Sort options. "Default" preserves the collectEntries grouping
    // (installed kinds first, then available, with vanilla/loader catalogs
    // in their upstream-fetched order). The play-stat / update-log sorts
    // are only meaningful for MODPACK_INSTALLED entries; other kinds are
    // treated as "never played" / "no update history" and sort to the
    // bottom while preserving their relative order via stable sort.
    private static final String SORT_DEFAULT      = "Default";
    private static final String SORT_NAME_AZ      = "Name (A–Z)";
    private static final String SORT_NAME_ZA      = "Name (Z–A)";
    private static final String SORT_RELEASE_DATE = "Release Date";
    private static final String SORT_LAST_PLAYED  = "Last Played";
    private static final String SORT_MOST_PLAYED  = "Most Played";
    private static final String SORT_RECENT_UPDATE = "Recently Updated";

    /** Page-size ladder. Bumped past the original 20/40/60 once {@link LibraryCard}
     *  picked up the same pool + bind pattern as the main menu's hero card — the
     *  per-card scene-graph cost is paid once per slot for the lifetime of the
     *  GUI instance, so even the 96-pack page is responsive. The 1.21 NeoForge
     *  release alone has ~30 versions, so the Forge/NeoForge/Fabric loader-version
     *  filters routinely produce pages that benefit from the larger sizes. */
    private static final List< Integer > PAGE_SIZES = List.of( 24, 48, 72, 96 );
    /** Default page size, bumped from 20 → 48 alongside the card-pool rollout
     *  so the typical installed library (and the new loader-version filters)
     *  fits on one page without paging. */
    private static final int DEFAULT_PAGE_SIZE = 48;

    private int pageSize    = DEFAULT_PAGE_SIZE;
    private int currentPage = 1;  // 1-based

    /** Pool of constructed-but-not-currently-displayed {@link LibraryCard}
     *  instances. {@link #rebuildCards()} pulls from here on each rebuild and
     *  returns any unused cards back to it, so the per-card scene-graph
     *  allocation + CSS-apply cost is paid once per slot for the lifetime of
     *  the library GUI instead of once per rebuild. Matches the pattern used
     *  by {@link MCLauncherMainGui#cardPool}, sized for the much larger pages
     *  the loader-version filters can produce.
     *
     *  <p>ArrayDeque is fine here: rebuildCards always runs on the FX thread,
     *  so no synchronization is needed.</p> */
    private final java.util.Deque< LibraryCard > cardPool = new java.util.ArrayDeque<>();

    /** Coalesces a burst of keystrokes in the search box into a single rebuild after
     *  the user pauses typing — see the listener in {@link #setup()} for rationale. */
    private javafx.animation.PauseTransition searchDebounce;
    private static final int SEARCH_DEBOUNCE_MS = 120;

    public MCLauncherGameLibraryGui( Stage stage ) throws IOException {
        super( stage );
    }

    @Override
    String getSceneFxmlPath() { return "gui/gameLibraryGUI.fxml"; }

    @Override
    String getSceneName() { return "Browse"; }

    @Override
    HelpTopic getHelpTopic() { return HelpTopic.BROWSE_LIBRARY; }

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
                TYPE_ALL, TYPE_MODPACKS,
                TYPE_VANILLA_RELEASE, TYPE_VANILLA_SNAPSHOT,
                TYPE_VANILLA_BETA, TYPE_VANILLA_ALPHA,
                TYPE_FORGE_VERSIONS, TYPE_NEOFORGE_VERSIONS, TYPE_FABRIC_VERSIONS ) );
        typeFilter.selectItem( TYPE_ALL );
        typeFilter.setOnAction( e -> { currentPage = 1; rebuildCards(); } );

        statusFilter.setItems( FXCollections.observableArrayList(
                STATUS_ALL, STATUS_INSTALLED, STATUS_AVAILABLE ) );
        statusFilter.selectItem( STATUS_INSTALLED );
        statusFilter.setOnAction( e -> { currentPage = 1; rebuildCards(); } );

        // Sort filter — labels mirror the main menu's so users learn one
        // vocabulary across screens. Last/Most-Played and Recently-Updated
        // are only meaningful for MODPACK_INSTALLED entries; vanilla / loader
        // catalogs degrade to "no play history" / "no update timestamp" and
        // sink to the bottom while keeping their natural intra-kind order.
        sortFilter.setItems( FXCollections.observableArrayList(
                SORT_DEFAULT, SORT_NAME_AZ, SORT_NAME_ZA, SORT_RELEASE_DATE,
                SORT_LAST_PLAYED, SORT_MOST_PLAYED, SORT_RECENT_UPDATE ) );
        sortFilter.selectItem( SORT_DEFAULT );
        sortFilter.setOnAction( e -> { currentPage = 1; rebuildCards(); } );

        // Search — debounce keystrokes so a rapid burst coalesces into one rebuild.
        // Without this, FlowPane.getChildren().clear() + re-instantiate fires per
        // keystroke, which makes the field feel laggy once the library has grown
        // past a few dozen entries (and the Library screen also folds vanilla
        // versions into the entry list, easily 100+ rows).
        searchDebounce = new javafx.animation.PauseTransition(
                javafx.util.Duration.millis( SEARCH_DEBOUNCE_MS ) );
        searchDebounce.setOnFinished( e -> rebuildCards() );
        searchField.textProperty().addListener( ( obs, oldVal, newVal ) -> {
            currentPage = 1;
            searchDebounce.playFromStart();
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
            TooltipManager.install( helpBtn, "Open the help window for this screen." );
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

        // URL "Add" → classify the URL, then either install (Mica manifest) or
        // route to the platform-specific import preview dialog (Modrinth /
        // CurseForge). Flips the button into an "Adding…" / "Checking…" state
        // while the work is in flight — without this affordance the click looks
        // like nothing happened, since the work can take 5-30 seconds for a
        // direct manifest install and an extra round-trip for the API-backed
        // preview.
        final String urlAddDefaultText = urlAddBtn.getText();
        urlAddBtn.setDisable( AnnouncementManager.getDisableModpacksEdit() );
        urlAddBtn.setOnAction( e -> {
            String url = urlAddField.getText();
            if ( url == null || url.isBlank() ) {
                return;
            }
            urlAddField.clear();
            urlAddBtn.setDisable( true );
            urlAddField.setDisable( true );

            com.micatechnologies.minecraft.launcher.game.modpack.import_.ModpackImportClassifier.Classification c =
                    com.micatechnologies.minecraft.launcher.game.modpack.import_.ModpackImportClassifier.classify( url );

            switch ( c.source() ) {
                case MODRINTH -> {
                    urlAddBtn.setText( "Checking…" );
                    handleModrinthImport( url, c.slug(), c.versionId(),
                                           urlAddDefaultText );
                }
                case CURSEFORGE -> {
                    urlAddBtn.setText( "Checking…" );
                    handleCurseForgeImport( url, c.slug(), c.versionId(),
                                             urlAddDefaultText );
                }
                default -> {
                    // MICA / UNKNOWN — existing path. UNKNOWN falls through to
                    // installModPackByURL which surfaces the usual "couldn't fetch
                    // manifest" error if the URL really wasn't anything we recognize.
                    urlAddBtn.setText( "Adding…" );
                    SystemUtilities.spawnNewTask( () -> {
                        try {
                            GameModPackManager.installModPackByURL( url );
                        }
                        finally {
                            GUIUtilities.JFXPlatformRun( () -> {
                                urlAddBtn.setText( urlAddDefaultText );
                                urlAddBtn.setDisable( AnnouncementManager.getDisableModpacksEdit() );
                                urlAddField.setDisable( false );
                                rebuildCards();
                            } );
                        }
                    } );
                }
            }
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

        // Apply smooth-scroll behavior to the card grid. Matches the main menu's
        // feel so users get a consistent scroll experience across the two
        // hero-card screens.
        SmoothScroll.install( libraryScrollPane );

        // If the startup-time background available-modpacks fetch is still in flight,
        // surface the same "Loading available packs…" affordance the main menu uses and
        // re-render the card grid the moment the future completes — that's when any
        // available-modpack cards that weren't ready on first paint can merge into the
        // list. Before this gate, collectEntries blocked the FX thread waiting for the
        // future, which stalled the Library screen for seconds on a cold network.
        java.util.concurrent.CompletableFuture< Void > availableFuture =
                GameModPackManager.getAvailableFetchFuture();
        if ( availableFuture != null && !availableFuture.isDone() ) {
            backgroundFetchLabel.setVisible( true );
            backgroundFetchLabel.setManaged( true );
            availableFuture.whenComplete( ( v, t ) -> GUIUtilities.JFXPlatformRun( () -> {
                backgroundFetchLabel.setVisible( false );
                backgroundFetchLabel.setManaged( false );
                rebuildCards();
            } ) );
        }

        // Cross-platform shortcuts: Settings / Browse / Editor / Home / Help.
        KeyboardShortcutManager.installGlobalShortcuts( scene, this::getHelpTopic );

        // Cmd/Ctrl+F focuses the search field.
        scene.addEventFilter( javafx.scene.input.KeyEvent.KEY_PRESSED, ev -> {
            if ( ev.isShortcutDown() && !ev.isAltDown() && !ev.isShiftDown()
                    && ev.getCode() == javafx.scene.input.KeyCode.F ) {
                ev.consume();
                if ( searchField != null ) searchField.requestFocus();
            }
        } );
    }

    @Override
    void cleanup() { /* nothing to tear down — filter listeners die with the scene */ }

    // =========================================================================================
    //  Add-by-URL platform-import helpers (Modrinth / CurseForge)
    // =========================================================================================

    /**
     * Fetches the Modrinth project metadata via their (free) v2 API, shows a
     * preview confirmation dialog, and — on confirm — kicks off the actual
     * import. v1 stubs the import action; the dialog itself, the API fetch,
     * and the URL classification scaffolding ship now so users get
     * recognizable feedback instead of a generic "not a manifest" error.
     */
    private void handleModrinthImport( String originalUrl,
                                        String slug,
                                        String versionId,
                                        String urlAddDefaultText )
    {
        SystemUtilities.spawnNewTask( () -> {
            com.micatechnologies.minecraft.launcher.game.modpack.import_.ModrinthClient.ProjectSummary summary
                    = com.micatechnologies.minecraft.launcher.game.modpack.import_.ModrinthClient
                            .fetchProject( slug, versionId );
            GUIUtilities.JFXPlatformRun( () -> resetUrlControls( urlAddDefaultText ) );

            if ( summary == null ) {
                NotificationManager.error(
                        "Couldn't reach Modrinth",
                        "The launcher recognized your URL as a Modrinth modpack but "
                                + "couldn't load details for it. Check your connection and try again, "
                                + "or paste a Mica manifest URL instead." );
                return;
            }

            String preview = buildModrinthPreview( originalUrl, summary );
            int choice = GUIUtilities.showQuestionMessageMultiline(
                    "Import from Modrinth?",
                    "Modrinth modpack detected",
                    preview,
                    "Continue",
                    "Cancel",
                    stage );
            if ( choice != 1 ) return;

            // Show the in-flight placeholder card in the Library FlowPane so
            // the user sees activity happening even before the second
            // confirmation dialog comes up. Status is updated as the flow
            // moves through download → translate → install.
            String packTitle = summary.title() != null ? summary.title() : "Modrinth modpack";
            Logger.logStd( "Modrinth import: user confirmed preview for slug=" + slug
                                   + " title=\"" + packTitle + "\"" );
            beginImport( packTitle );
            try {
                runModrinthImportFlow( summary, slug );
            }
            finally {
                Logger.logStd( "Modrinth import: flow ended for slug=" + slug );
                endImport();
            }
        } );
    }

    /**
     * Step-2 of the Modrinth import flow. The user confirmed they want to
     * proceed from the project-level preview — now we download the
     * {@code .mrpack} archive, parse its {@code modrinth.index.json}, show
     * a second confirmation dialog with the actual mod list, and only on
     * THAT confirmation do we write the translated Mica manifest and call
     * into the regular {@code installModPackByURL} pipeline.
     *
     * <p>The two-step confirmation is deliberate: the project page can
     * misrepresent what the pack actually contains (or change after the
     * user looked), so we make the second confirmation show the
     * authoritative file list from the archive itself. After that point
     * the user has explicitly approved every mod we're about to download.</p>
     */
    private void runModrinthImportFlow(
            com.micatechnologies.minecraft.launcher.game.modpack.import_.ModrinthClient.ProjectSummary summary,
            String slug )
    {
        var version = summary.latestVersion();
        if ( version == null || version.files() == null || version.files().isEmpty() ) {
            NotificationManager.error(
                    "Pack download URL unavailable",
                    "Modrinth didn't return a file URL for the pack's latest version. "
                            + "Try again, or check the pack on modrinth.com — it may have been "
                            + "unpublished or the version may have no published files yet." );
            return;
        }
        // Pick the primary file (the .mrpack); skip non-primary entries
        // (server-pack variants, sometimes shipped alongside).
        String mrpackUrl = null;
        for ( var fileRef : version.files() ) {
            if ( fileRef.primary() && fileRef.url() != null && !fileRef.url().isBlank() ) {
                mrpackUrl = fileRef.url();
                break;
            }
        }
        // Fallback: take the first file if no primary flag is set.
        if ( mrpackUrl == null ) {
            mrpackUrl = version.files().get( 0 ).url();
        }
        if ( mrpackUrl == null || !mrpackUrl.toLowerCase( java.util.Locale.ROOT ).endsWith( ".mrpack" ) ) {
            NotificationManager.error(
                    "Pack archive missing",
                    "Modrinth's primary download URL for this pack doesn't look like a .mrpack file. "
                            + "This launcher can only import .mrpack-formatted modpacks." );
            return;
        }

        // Notification for the long-running download phase. The .mrpack itself
        // is usually 1–20 MB but the Forge installer fetch + SHA happen in the
        // same call so the user sees one "Preparing import…" until both finish.
        NotificationManager.info(
                "Preparing import",
                "Downloading the pack archive and translating it. This usually takes a few seconds." );
        updateImportStatus( "Downloading pack archive + Forge installer…" );

        final String finalMrpackUrl = mrpackUrl;
        com.micatechnologies.minecraft.launcher.game.modpack.import_.MrpackImporter.Result result;
        try {
            // Hand the Modrinth project icon URL through so the imported
            // manifest's packLogoURL points at real CDN imagery rather than
            // a bundled-default placeholder. Falls through to default when
            // Modrinth's API didn't return an icon for the project.
            result = com.micatechnologies.minecraft.launcher.game.modpack.import_.MrpackImporter
                    .importMrpack( finalMrpackUrl, slug, summary.iconUrl() );
        }
        catch ( com.micatechnologies.minecraft.launcher.game.modpack.import_.MrpackImporter.ImportException ie ) {
            NotificationManager.error( "Import failed", ie.getMessage() );
            return;
        }

        updateImportStatus( "Waiting for confirmation…" );

        // Step-2 confirmation on the FX thread — shows the actual mod list
        // pulled from the .mrpack so the user confirms against authoritative
        // content rather than the Modrinth project page (which may have
        // moved on / had a different version). The dialog's showAndWait
        // blocks the FX thread; we use a CompletableFuture so this worker
        // thread blocks on the result without busy-spinning.
        com.micatechnologies.minecraft.launcher.game.modpack.import_.MrpackImporter.Result finalResult = result;
        java.util.concurrent.CompletableFuture< Boolean > dialogResult = new java.util.concurrent.CompletableFuture<>();
        GUIUtilities.JFXPlatformRun( () -> {
            try {
                boolean answer = MCLauncherImportConfirmDialog.showAndAwait(
                        stage,
                        finalResult.index().name,
                        finalResult.index().versionId,
                        finalResult.index() );
                dialogResult.complete( answer );
            }
            catch ( Throwable t ) {
                dialogResult.completeExceptionally( t );
            }
        } );
        boolean confirmed;
        try {
            confirmed = dialogResult.get();
        }
        catch ( Exception ex ) {
            Logger.logWarningSilent( "Import confirm dialog failed: " + ex.getMessage() );
            return;
        }
        if ( !confirmed ) {
            // User cancelled — translated manifest stays on disk so a
            // retry is fast, but we don't add the pack to the installed
            // list. The cached file is small (a few KB JSON) and a future
            // cleanup pass can reap untouched imported-manifests if the
            // directory ever gets noisy.
            return;
        }

        // Confirmed — hand the file:// URL to the standard installer.
        Logger.logStd( "Modrinth import: user confirmed mod list — installing "
                               + finalResult.localManifestUrl() );
        NotificationManager.info(
                "Import starting",
                "Adding the pack to your library and queueing the mod downloads." );
        updateImportStatus( "Adding to your library…" );
        try {
            com.micatechnologies.minecraft.launcher.game.modpack.GameModPackManager
                    .installModPackByURL( finalResult.localManifestUrl() );
            Logger.logStd( "Modrinth import: installModPackByURL returned for " + finalResult.localManifestUrl() );
            GUIUtilities.JFXPlatformRun( () -> {
                // rebuildCards happens automatically in endImport() — no need
                // to call it here, since endImport will also clear the
                // in-progress placeholder and the freshly-installed pack
                // appears in its sorted slot.
                NotificationManager.success(
                        "Import complete",
                        "The pack is now in your library. Open it from the main menu to launch." );
            } );
        }
        catch ( Throwable t ) {
            Logger.logErrorSilent( "Modrinth import install step failed: " + t.getMessage() );
            Logger.logThrowable( t );
            NotificationManager.error(
                    "Install failed",
                    "The pack was translated successfully but installing it into your library "
                            + "didn't complete. Check the launcher log for details." );
        }
    }

    /**
     * CurseForge counterpart. Their Core API requires a key (not free /
     * publicly distributable), so v1 can't fetch project metadata for the
     * preview — the dialog shows what we can derive from the URL alone and
     * tells the user the supported workaround.
     */
    private void handleCurseForgeImport( String originalUrl,
                                          String slug,
                                          String fileId,
                                          String urlAddDefaultText )
    {
        // Quick FX-thread continuation since there's no network round-trip to
        // wait on. spawnNewTask anyway for symmetry with the Modrinth path.
        SystemUtilities.spawnNewTask( () -> {
            GUIUtilities.JFXPlatformRun( () -> resetUrlControls( urlAddDefaultText ) );

            String preview = buildCurseForgePreview( originalUrl, slug, fileId );
            int choice = GUIUtilities.showQuestionMessageMultiline(
                    "CurseForge modpack detected",
                    "Direct import isn't available yet",
                    preview,
                    "Open CurseForge page",
                    "Cancel",
                    stage );
            if ( choice == 1 ) {
                try {
                    java.awt.Desktop.getDesktop().browse( java.net.URI.create( originalUrl ) );
                }
                catch ( Throwable t ) {
                    Logger.logWarningSilent( "Could not open CurseForge URL: " + t.getMessage() );
                }
            }
        } );
    }

    /** Restores the urlAdd field + button to their idle state. Called from
     *  both platform-import paths since both need this cleanup regardless of
     *  whether the user clicked Import or Cancel. */
    private void resetUrlControls( String urlAddDefaultText )
    {
        urlAddBtn.setText( urlAddDefaultText );
        urlAddBtn.setDisable( AnnouncementManager.getDisableModpacksEdit() );
        urlAddField.setDisable( false );
    }

    /** Renders the Modrinth project summary as the preview-dialog body text.
     *  Multi-line on purpose — uses {@link GUIUtilities#showQuestionMessageMultiline}. */
    private String buildModrinthPreview( String originalUrl,
            com.micatechnologies.minecraft.launcher.game.modpack.import_.ModrinthClient.ProjectSummary s )
    {
        StringBuilder sb = new StringBuilder();
        sb.append( s.title() != null ? s.title() : "(unnamed project)" ).append( '\n' );
        if ( s.description() != null && !s.description().isBlank() ) {
            sb.append( '\n' ).append( s.description().trim() ).append( '\n' );
        }
        sb.append( "\nSource: Modrinth" );
        if ( s.slug() != null ) sb.append( " (" ).append( s.slug() ).append( ")" );
        sb.append( '\n' );
        if ( s.latestVersion() != null ) {
            var v = s.latestVersion();
            if ( v.versionNumber() != null ) sb.append( "Version: " ).append( v.versionNumber() ).append( '\n' );
            if ( v.minecraftVersions() != null && !v.minecraftVersions().isEmpty() ) {
                sb.append( "Minecraft: " ).append( String.join( ", ", v.minecraftVersions() ) ).append( '\n' );
            }
            if ( v.loaders() != null && !v.loaders().isEmpty() ) {
                sb.append( "Mod loader: " ).append( String.join( ", ", v.loaders() ) ).append( '\n' );
            }
            // Deliberately don't surface v.files().size() here: a Modrinth version's
            // "files" array typically has ONE primary entry (the .mrpack archive),
            // plus sometimes a server-pack variant. The actual mod list lives
            // INSIDE the .mrpack archive's modrinth.index.json, which we don't
            // fetch until import time. Showing "1 file" mid-preview reads as
            // "this pack contains 1 mod", which is wrong — better to leave the
            // mod count off the preview entirely than to mislead.
        }
        sb.append( '\n' ).append( "URL: " ).append( originalUrl );
        return sb.toString();
    }

    /** CurseForge preview text. We can't fetch project metadata without an API
     *  key, so the dialog is purely advisory + a one-click out to the
     *  project page on cfwidget / curseforge.com itself. */
    private String buildCurseForgePreview( String originalUrl, String slug, String fileId )
    {
        StringBuilder sb = new StringBuilder();
        sb.append( "Detected a CurseForge modpack URL." ).append( '\n' );
        sb.append( '\n' );
        sb.append( "Source: CurseForge" );
        if ( slug != null ) sb.append( " (" ).append( slug ).append( ")" );
        sb.append( '\n' );
        if ( fileId != null && !fileId.isBlank() ) {
            sb.append( "File ID: " ).append( fileId ).append( '\n' );
        }
        sb.append( '\n' );
        sb.append( "CurseForge requires an API key for programmatic downloads, so the "
                          + "launcher can't import their packs directly yet. For now, you can "
                          + "open the project page below, download the modpack ZIP from "
                          + "CurseForge, and either repackage it as a Mica manifest or wait for "
                          + "the next launcher update that adds CurseForge import support." );
        sb.append( '\n' ).append( '\n' ).append( "URL: " ).append( originalUrl );
        return sb.toString();
    }

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

        String sortSel = sortFilter == null ? SORT_DEFAULT
                : Objects.requireNonNullElse( sortFilter.getValue(), SORT_DEFAULT );
        sortEntries( entries, sortSel );

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

        // Recycle currently-displayed cards back to the pool before clearing
        // the FlowPane, so the next pass can pull them out and rebind to the
        // new entry set instead of constructing fresh ones. Non-card children
        // (ImportProgressCard, empty-state Label) are skipped by the cast
        // guard so they don't pollute the pool.
        for ( javafx.scene.Node child : cardList.getChildren() ) {
            if ( child instanceof LibraryCard card ) {
                cardPool.push( card );
            }
        }
        cardList.getChildren().clear();
        // Active import card always shows first regardless of filter / page —
        // see field doc on activeImportCard. Persists across rebuildCards
        // calls triggered by the user fiddling with filters during an
        // import so the work-in-flight indicator stays visible.
        if ( activeImportCard != null ) {
            cardList.getChildren().add( activeImportCard );
        }
        if ( entries.isEmpty() ) {
            if ( activeImportCard == null ) {
                cardList.getChildren().add( buildEmptyState( type, status, search ) );
            }
            return;
        }
        for ( int i = startIdx; i < endIdx; i++ ) {
            LibraryCard card = cardPool.isEmpty() ? null : cardPool.pop();
            if ( card == null ) {
                card = new LibraryCard( entries.get( i ) );
            }
            else {
                card.bind( entries.get( i ) );
            }
            cardList.getChildren().add( card );
        }
    }

    // =========================================================================================
    //  Import-in-progress card (placeholder shown in the FlowPane during a modpack import)
    // =========================================================================================

    /**
     * Placeholder card prepended to the Library FlowPane while a modpack
     * import is in flight. Same dimensions and visual chrome as
     * {@link LibraryCard} so it slots into the grid cleanly — pulsing
     * spinner in the image-area position, pack name as the heading, and
     * a status line below that the import flow updates as it progresses
     * through download → translate → install.
     *
     * <p>Lives in the GUI layer only — the importer is unaware of it and
     * doesn't need a cancellation token (status updates only). Adding a
     * real cancel button would need cancellation plumbing through the
     * importer's network calls, which is a follow-up rather than a v1
     * scope item.</p>
     */
    private final class ImportProgressCard extends VBox
    {
        // Match LibraryCard's footprint so the placeholder slots cleanly into
        // the FlowPane next to the real cards — initial draft used the
        // main-menu hero card's 360x150 which looked oversized next to the
        // browse-view's 300x110 layout.
        private static final double CARD_WIDTH  = 300;
        private static final double IMAGE_HEIGHT = 110;

        private final Label statusLabel;

        ImportProgressCard( String packName )
        {
            getStyleClass().add( "heroCardShell" );
            setPrefWidth( CARD_WIDTH );
            setMinWidth( CARD_WIDTH );
            setMaxWidth( CARD_WIDTH );
            setSpacing( 0 );

            // Top half: themed gradient + indeterminate spinner overlay.
            StackPane imageBox = new StackPane();
            imageBox.getStyleClass().add( "heroCardImage" );
            imageBox.setPrefHeight( IMAGE_HEIGHT );
            imageBox.setMinHeight( IMAGE_HEIGHT );
            imageBox.setMaxHeight( IMAGE_HEIGHT );
            Rectangle clip = new Rectangle( CARD_WIDTH, IMAGE_HEIGHT );
            clip.setArcWidth( 28 );
            clip.setArcHeight( 28 );
            clip.heightProperty().bind( imageBox.heightProperty() );
            clip.widthProperty().bind( imageBox.widthProperty() );
            imageBox.setClip( clip );

            Region bgLayer = new Region();
            bgLayer.getStyleClass().add( "heroBackgroundDefaultForge" );

            ProgressIndicator spinner = new ProgressIndicator();
            spinner.setMaxSize( 72, 72 );
            spinner.setPrefSize( 72, 72 );

            imageBox.getChildren().addAll( bgLayer, spinner );

            // Bottom: pack name + live status line.
            VBox info = new VBox( 6 );
            info.getStyleClass().add( "heroCardBody" );
            info.setAlignment( Pos.CENTER );
            info.setPadding( new Insets( 18, 16, 16, 16 ) );
            VBox.setVgrow( info, Priority.ALWAYS );

            Label nameLabel = new Label( packName == null || packName.isBlank()
                    ? "Importing modpack…" : packName );
            nameLabel.getStyleClass().addAll( "heading-h2", "heroCardTitle" );
            nameLabel.setWrapText( true );
            nameLabel.setAlignment( Pos.CENTER );
            nameLabel.setMaxWidth( Double.MAX_VALUE );

            statusLabel = new Label( "Preparing import…" );
            statusLabel.getStyleClass().add( "subtle" );
            statusLabel.setWrapText( true );
            statusLabel.setAlignment( Pos.CENTER );
            statusLabel.setMaxWidth( Double.MAX_VALUE );
            statusLabel.setStyle( "-fx-font-size: 11px;" );

            info.getChildren().addAll( nameLabel, statusLabel );

            getChildren().addAll( imageBox, info );
        }

        /** Updates the status line. Must be called from the FX thread; the
         *  three {@code beginImport}/{@code updateImportStatus}/{@code endImport}
         *  helpers below take care of that for the worker-thread callers. */
        void setStatus( String text )
        {
            if ( text != null && !text.isBlank() ) {
                statusLabel.setText( text );
            }
        }
    }

    /** Creates + inserts the import-in-progress card. Safe to call from
     *  any thread; FX work is dispatched via JFXPlatformRun. */
    private void beginImport( String packName )
    {
        GUIUtilities.JFXPlatformRun( () -> {
            activeImportCard = new ImportProgressCard( packName );
            rebuildCards();
        } );
    }

    /** Updates the active import card's status line. No-op if no import is
     *  currently in flight. */
    private void updateImportStatus( String status )
    {
        GUIUtilities.JFXPlatformRun( () -> {
            if ( activeImportCard != null ) activeImportCard.setStatus( status );
        } );
    }

    /** Tears down the import-in-progress card and re-renders the FlowPane
     *  so the freshly-installed pack appears in its sorted slot. */
    private void endImport()
    {
        GUIUtilities.JFXPlatformRun( () -> {
            activeImportCard = null;
            rebuildCards();
        } );
    }

    /** Shows the bottom-bar background-fetch status with {@code text}, replacing
     *  whatever the FXML preset says. Safe from any thread. Pass {@code null}
     *  (or call {@link #hideBackgroundStatus()}) to dismiss. Used by
     *  install / uninstall handlers so refresh work happens silently in the
     *  background instead of the launcher swapping to a full-screen
     *  progress GUI. */
    private void showBackgroundStatus( String text )
    {
        GUIUtilities.JFXPlatformRun( () -> {
            if ( backgroundFetchLabel == null ) return;
            backgroundFetchLabel.setText( text != null ? text : "" );
            backgroundFetchLabel.setVisible( true );
            backgroundFetchLabel.setManaged( true );
        } );
    }

    /** Hides the bottom-bar background-fetch status. Safe from any thread. */
    private void hideBackgroundStatus()
    {
        GUIUtilities.JFXPlatformRun( () -> {
            if ( backgroundFetchLabel == null ) return;
            backgroundFetchLabel.setVisible( false );
            backgroundFetchLabel.setManaged( false );
        } );
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
        // Vanilla rows are shown for TYPE_ALL and any TYPE_VANILLA_*
        // filter — NOT for the loader-version filters, which want a
        // focused list of just that loader's versions.
        boolean wantVanilla   = TYPE_ALL.equals( type )
                || type != null && type.startsWith( "Vanilla" );
        // Loader-version rows fire only when the user explicitly
        // picks that filter. They're not included in TYPE_ALL because
        // the combined list would be enormous (~50 vanilla releases
        // + ~150 Forge promotions + ~100 NeoForge + ~50 Fabric) and
        // not what a "show me everything" search wants.
        String wantLoaderType = loaderTypeFor( type );

        // Installed modpacks
        if ( wantModpacks && wantInstalled ) {
            for ( GameModPack pack : GameModPackManager.getInstalledModPacks() ) {
                out.add( LibraryEntry.installedModpack( pack ) );
            }
        }
        // Available manifest modpacks (those not yet installed). Pull the rich GameModPack
        // objects so we have access to packLogoURL for the card's logo image. Use the
        // non-blocking accessor — on a cold launch the background available-packs fetch
        // may still be in flight, and the FX thread must not stall on it. The controller
        // wires a re-render to that future's completion, so any cards missing on first
        // paint show up automatically once the fetch settles.
        if ( wantModpacks && wantAvailable ) {
            List< GameModPack > available = GameModPackManager.getAvailableModPacksIfReady();
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

        // Loader versions — Forge / NeoForge / Fabric installable
        // "empty packs". Each entry's install action writes a real
        // manifest under imported-manifests/ and registers it with
        // GameModPackManager, so installed loader packs show up as
        // normal modpacks (under the Modpacks filter) — we never
        // emit a LOADER_INSTALLED entry here, only LOADER_AVAILABLE.
        if ( wantLoaderType != null && wantAvailable ) {
            List< com.micatechnologies.minecraft.launcher.game.modpack.LoaderVersionManager.LoaderVersion > versions =
                    switch ( wantLoaderType ) {
                        case com.micatechnologies.minecraft.launcher.consts.ModPackConstants.MOD_LOADER_FORGE ->
                                com.micatechnologies.minecraft.launcher.game.modpack.LoaderVersionManager.getForgeVersions();
                        case com.micatechnologies.minecraft.launcher.consts.ModPackConstants.MOD_LOADER_NEOFORGE ->
                                com.micatechnologies.minecraft.launcher.game.modpack.LoaderVersionManager.getNeoForgeVersions();
                        case com.micatechnologies.minecraft.launcher.consts.ModPackConstants.MOD_LOADER_FABRIC ->
                                com.micatechnologies.minecraft.launcher.game.modpack.LoaderVersionManager.getFabricVersions();
                        default -> java.util.Collections.emptyList();
                    };
            int catalogIndex = 0;
            for ( var v : versions ) {
                if ( com.micatechnologies.minecraft.launcher.game.modpack.LoaderVersionManager.isInstalled( v ) ) {
                    // The on-disk manifest already exists. It'll
                    // surface under the Modpacks filter through the
                    // normal installed-packs path; don't duplicate it
                    // here as an "available" entry. Still bumps
                    // catalogIndex so the release-date rank reflects
                    // real upstream position, not post-filter position.
                    catalogIndex++;
                    continue;
                }
                out.add( LibraryEntry.availableLoader( v, catalogIndex++ ) );
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

    /** Re-orders {@code entries} per the user's selected sort. Stable
     *  sort — entries that tie on the sort key keep their {@link #collectEntries}
     *  order, which already groups by kind (installed, then available) so a
     *  tie-broken "Most Played" list still reads naturally with the modpacks
     *  ahead of the vanilla / loader catalog rows.
     *
     *  <p>{@code SORT_LAST_PLAYED} / {@code SORT_MOST_PLAYED} / {@code SORT_RECENT_UPDATE}
     *  are well-defined only for {@link LibraryEntry.Kind#MODPACK_INSTALLED}; non-modpack
     *  kinds get a sentinel ({@code Long.MIN_VALUE}) so they sink to the bottom
     *  rather than disrupting the intent.</p> */
    private static void sortEntries( List< LibraryEntry > entries, String sortKey )
    {
        switch ( sortKey ) {
            case SORT_NAME_AZ -> entries.sort( Comparator.comparing(
                    e -> e.displayName.toLowerCase( Locale.ROOT ) ) );
            case SORT_NAME_ZA -> entries.sort( Comparator.comparing(
                    ( LibraryEntry e ) -> e.displayName.toLowerCase( Locale.ROOT ) ).reversed() );
            case SORT_RELEASE_DATE -> entries.sort( Comparator.comparingLong(
                    MCLauncherGameLibraryGui::releaseDateKey ).reversed() );
            case SORT_LAST_PLAYED -> entries.sort( Comparator.comparingLong(
                    MCLauncherGameLibraryGui::lastPlayedKey ).reversed() );
            case SORT_MOST_PLAYED -> entries.sort( Comparator.comparingLong(
                    MCLauncherGameLibraryGui::totalPlayedKey ).reversed() );
            case SORT_RECENT_UPDATE -> entries.sort( Comparator.comparingLong(
                    MCLauncherGameLibraryGui::lastUpdateKey ).reversed() );
            default -> { /* SORT_DEFAULT — keep collectEntries order */ }
        }
    }

    private static long lastPlayedKey( LibraryEntry e )
    {
        if ( e.pack == null ) return Long.MIN_VALUE;
        return e.pack.getLastPlayedMs();
    }

    private static long totalPlayedKey( LibraryEntry e )
    {
        if ( e.pack == null ) return Long.MIN_VALUE;
        return e.pack.getTotalPlayTimeMs();
    }

    private static long lastUpdateKey( LibraryEntry e )
    {
        if ( e.pack == null ) return Long.MIN_VALUE;
        try {
            java.util.List< com.micatechnologies.minecraft.launcher.game.modpack.ModPackUpdateLog.Entry > log
                    = com.micatechnologies.minecraft.launcher.game.modpack.ModPackUpdateLog.readEntries( e.pack );
            if ( log.isEmpty() ) return Long.MIN_VALUE;
            return log.get( 0 ).timestampMs();   // readEntries returns newest-first
        }
        catch ( Exception ignored ) {
            return Long.MIN_VALUE;
        }
    }

    /** Sort key for {@code SORT_RELEASE_DATE}. Uses the eagerly-populated
     *  {@code releaseTimeMs} when available (vanilla + loader entries); for
     *  modpacks the field is {@link Long#MIN_VALUE}, so we fall back to the
     *  installed pack's newest update-log timestamp as the user's release-date
     *  proxy ("if the pack doesn't have a real release date, treat last-updated
     *  as the release date"). Available manifest modpacks fall through both
     *  paths and sink to the bottom. */
    private static long releaseDateKey( LibraryEntry e )
    {
        if ( e.releaseTimeMs != Long.MIN_VALUE ) return e.releaseTimeMs;
        return lastUpdateKey( e );
    }

    /** Maps a top-level type filter to the modloader identifier
     *  ({@code ModPackConstants.MOD_LOADER_*}) when one of the
     *  loader-version filters is selected; null otherwise. */
    private static String loaderTypeFor( String type )
    {
        return switch ( type == null ? "" : type ) {
            case TYPE_FORGE_VERSIONS    -> com.micatechnologies.minecraft.launcher.consts.ModPackConstants.MOD_LOADER_FORGE;
            case TYPE_NEOFORGE_VERSIONS -> com.micatechnologies.minecraft.launcher.consts.ModPackConstants.MOD_LOADER_NEOFORGE;
            case TYPE_FABRIC_VERSIONS   -> com.micatechnologies.minecraft.launcher.consts.ModPackConstants.MOD_LOADER_FABRIC;
            default                      -> null;
        };
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
        enum Kind { MODPACK_INSTALLED, MODPACK_AVAILABLE,
                     VANILLA_INSTALLED, VANILLA_AVAILABLE,
                     /** Forge / NeoForge / Fabric version that's available to install as an
                      *  empty modpack. There's no LOADER_INSTALLED counterpart — installed
                      *  loader packs surface through the existing MODPACK_INSTALLED path since
                      *  installation writes a real manifest. */
                     LOADER_AVAILABLE }

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
        /** Non-null for LOADER_AVAILABLE — carries the (loaderType,
         *  mcVersion, loaderVersion, installerUrl) tuple the install
         *  action needs to write the manifest. */
        final com.micatechnologies.minecraft.launcher.game.modpack.LoaderVersionManager.LoaderVersion loaderVersion;
        /** Release date in epoch millis when known, {@link Long#MIN_VALUE} when
         *  not. Populated eagerly for vanilla (from the Mojang manifest's
         *  {@code releaseTime}) and loader entries (synthesised from catalog
         *  index since the upstream version services return newest-first
         *  order but no explicit per-version dates). Installed modpacks fall
         *  back to their newest update-log entry timestamp at sort time
         *  ({@link #lastUpdateKey}); available modpacks have no per-pack date
         *  source so they sink to the bottom of release-date sorts. */
        final long releaseTimeMs;

        private LibraryEntry( Kind kind, String displayName, List< String > chips, String statusText,
                              GameModPack pack, String vanillaVersionId, JsonObject vanillaInfo,
                              com.micatechnologies.minecraft.launcher.game.modpack.LoaderVersionManager.LoaderVersion loaderVersion,
                              long releaseTimeMs )
        {
            this.kind = kind;
            this.displayName = displayName;
            this.chips = chips;
            this.statusText = statusText;
            this.pack = pack;
            this.vanillaVersionId = vanillaVersionId;
            this.vanillaInfo = vanillaInfo;
            this.loaderVersion = loaderVersion;
            this.releaseTimeMs = releaseTimeMs;
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
            return new LibraryEntry( Kind.MODPACK_INSTALLED, name, chips, status, pack, null, null, null,
                                     Long.MIN_VALUE );
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
                                     pack, null, null, null, Long.MIN_VALUE );
        }

        static LibraryEntry installedVanilla( String versionId, JsonObject info )
        {
            String typeStr = info.has( "type" ) ? info.get( "type" ).getAsString() : "release";
            List< String > chips = new ArrayList<>();
            chips.add( "Vanilla" );
            chips.add( prettyVanillaType( typeStr ) );
            return new LibraryEntry( Kind.VANILLA_INSTALLED, "Minecraft " + versionId,
                                     chips, "Installed", null, versionId, info, null,
                                     parseReleaseTime( info ) );
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
                                     chips, status, null, versionId, info, null,
                                     parseReleaseTime( info ) );
        }

        /** Loader-version entry. {@code catalogIndex} is the position in the
         *  upstream version-service response (0 = newest); it's converted to a
         *  synthetic release timestamp so {@code SORT_RELEASE_DATE} can put
         *  newer loader builds first without per-version date data, which the
         *  Forge / NeoForge / Fabric services don't expose in their bulk
         *  version listings. */
        static LibraryEntry availableLoader(
                com.micatechnologies.minecraft.launcher.game.modpack.LoaderVersionManager.LoaderVersion v,
                int catalogIndex )
        {
            String loaderPretty = switch ( v.loaderType() ) {
                case com.micatechnologies.minecraft.launcher.consts.ModPackConstants.MOD_LOADER_FORGE    -> "Forge";
                case com.micatechnologies.minecraft.launcher.consts.ModPackConstants.MOD_LOADER_NEOFORGE -> "NeoForge";
                case com.micatechnologies.minecraft.launcher.consts.ModPackConstants.MOD_LOADER_FABRIC   -> "Fabric";
                default                                                                                  -> v.loaderType();
            };
            List< String > chips = new ArrayList<>();
            chips.add( loaderPretty );
            chips.add( "Minecraft " + v.mcVersion() );
            // Synthesise a release timestamp from the catalog position. Step by
            // ~1 day per slot so the synthetic dates spread over a reasonable
            // range; anchored at "now" so newest entries sort alongside truly
            // recent real-dated entries (vanilla / modpack updates) when the
            // result set mixes them. Loader-only filters are the common case
            // and the relative ordering within them is what matters most.
            long synthetic = System.currentTimeMillis() - ( catalogIndex * 86_400_000L );
            return new LibraryEntry( Kind.LOADER_AVAILABLE,
                                     loaderPretty + " " + v.loaderVersion(),
                                     chips, "Installs as empty modpack",
                                     null, null, null, v, synthetic );
        }

        /** Parses Mojang's ISO-8601 {@code releaseTime} field into epoch millis.
         *  Returns {@link Long#MIN_VALUE} on missing / unparseable values so
         *  entries without a date sort to the bottom rather than being treated
         *  as 1970-01-01 (which would float them near the top of an ascending
         *  sort or to the bottom of a descending sort by accident). */
        private static long parseReleaseTime( JsonObject info )
        {
            if ( info == null || !info.has( "releaseTime" ) ) return Long.MIN_VALUE;
            try {
                return java.time.OffsetDateTime.parse( info.get( "releaseTime" ).getAsString() )
                        .toInstant().toEpochMilli();
            }
            catch ( Exception ignored ) {
                return Long.MIN_VALUE;
            }
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

        /** Current entry — mutated by {@link #bind}. The action-row buttons and
         *  context menu are rebuilt against the new entry on each bind, so
         *  there's no closure-captured reference to leak from a previous bind. */
        private LibraryEntry entry;

        // === Static node tree fields. Built once by the constructor; bind()
        //     mutates their content in place rather than re-creating them. ===
        private final Region bgLayer;
        private final HBox badgeRow;
        private final ImageView logo;
        private final Label name;
        private final HBox chips;
        private final Label status;
        private final HBox actions;

        LibraryCard( LibraryEntry initialEntry )
        {
            getStyleClass().add( "heroCardShell" );
            setPrefWidth( CARD_WIDTH );
            setMinWidth( CARD_WIDTH );
            setMaxWidth( CARD_WIDTH );
            setSpacing( 0 );
            // Same bitmap-cache trick as the main-menu hero cards — keeps scroll lag minimal
            // even with a few hundred cards in the FlowPane. CacheHint.DEFAULT (not SPEED)
            // for the same reason that bit the main menu: SPEED treats the cached bitmap
            // as static and won't refresh when the bgLayer's CSS bg-image completes its
            // async load, making packs render with just the gradient even when the image
            // is available. DEFAULT lets JavaFX invalidate + re-render on descendant
            // content changes.
            setCache( true );
            setCacheHint( CacheHint.DEFAULT );

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

            bgLayer = new Region();
            bgLayer.getStyleClass().add( "heroBackground" );

            Region imageVeil = new Region();
            imageVeil.getStyleClass().add( "heroCardImageVeil" );

            // Badge row (top-right): a contextual status badge — "Installed" / "Update" / etc.
            // Children are rebuilt per-bind since the badge text/style is entry-specific.
            badgeRow = new HBox( 6 );
            badgeRow.setAlignment( Pos.TOP_RIGHT );
            badgeRow.setPadding( new Insets( 8, 10, 0, 0 ) );

            imageBox.getChildren().addAll( bgLayer, imageVeil, badgeRow );

            // ----- Logo overlay (matches main-menu visual) -----
            StackPane logoContainer = new StackPane();
            logoContainer.getStyleClass().add( "heroPackLogoContainer" );
            logoContainer.setMinSize( 56, 56 );
            logoContainer.setMaxSize( 56, 56 );
            logo = new ImageView();
            logo.setFitWidth( 52 );
            logo.setFitHeight( 52 );
            logo.setPreserveRatio( true );
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

            name = new Label();
            name.getStyleClass().addAll( "heading-h3", "heroCardTitle" );
            name.setWrapText( true );
            // setWrapText alone is not enough: a Label without a maxWidth defaults
            // to USE_PREF_SIZE, which is its single-line preferred width, and the
            // text gets ellipsised instead of wrapping. Pin maxWidth to the card
            // body's content width (card width minus the body's horizontal
            // padding of 14 px each side) so long pack / loader-version titles
            // like "NeoForge 21.6.0-beta" wrap to two lines instead of being
            // truncated.
            name.setMaxWidth( CARD_WIDTH - 28 );

            chips = new HBox( 6 );
            chips.setAlignment( Pos.CENTER_LEFT );

            status = new Label();
            status.getStyleClass().add( "heroLastPlayedSurface" );

            info.getChildren().addAll( logoContainer, name, chips, status );
            VBox.setVgrow( info, Priority.ALWAYS );

            // ----- Action row -----
            // Children are rebuilt per-bind because buttons + handlers vary by
            // entry kind (Install / Uninstall / Edit, each closing over the
            // active entry). The HBox container itself is reused.
            actions = new HBox( 8 );
            actions.setAlignment( Pos.CENTER_LEFT );
            actions.setPadding( new Insets( 0, 14, 12, 14 ) );

            getChildren().addAll( imageBox, info, actions );
            setCursor( Cursor.HAND );

            // Initial bind so the card has real content by the time the constructor returns.
            bind( initialEntry );
        }

        /**
         * Switches this card to display {@code newEntry}. Updates every per-entry
         * visual element (background gradient + image, logo, badge, name, chips,
         * status text, action row, context menu) but leaves the underlying scene
         * graph intact for reuse across rebuilds.
         *
         * <p>Called by the constructor with the initial entry and again by
         * {@link MCLauncherGameLibraryGui#rebuildCards()} whenever a pooled card
         * is reassigned during page / filter / search changes — keeping the same
         * VBox instance alive saves the per-card scene-graph allocation +
         * CSS-apply cost on every rebuild, which is what makes the larger page
         * sizes (and the new loader-version filters' big result sets) feel
         * responsive.</p>
         */
        void bind( LibraryEntry newEntry )
        {
            this.entry = newEntry;

            // ----- Background -----
            // Clear any prior bind's inline -fx-background-image AND the dynamic
            // style classes applyEntryBackground / applyDynamicBackground add
            // (heroBackgroundDefaultVanilla / heroBackgroundDefaultForge) so they
            // don't accumulate across rebinds and bleed previous-entry styling
            // into the new state.
            bgLayer.setStyle( null );
            bgLayer.getStyleClass().removeAll( "heroBackgroundDefaultVanilla",
                                                "heroBackgroundDefaultForge" );
            applyEntryBackground( bgLayer, newEntry );

            // Overlay the pack's real background image on top of the gradient when the
            // image is already on disk (installed packs whose previous launch downloaded
            // the bg, or available packs that happen to have been image-cached for any
            // reason). Browse view doesn't trigger an async download for missing files —
            // it'd be heavy when scrolling through dozens of available packs — so packs
            // without a cached image stay on the gradient. The main-menu card handles the
            // download for installed packs anyway, so the next visit to the library
            // typically has them all on disk. Respects the user-facing
            // Settings → Appearance toggle: when off, gradient-only across the board.
            if ( newEntry.pack != null
                    && com.micatechnologies.minecraft.launcher.config.ConfigManager.getShowPackBackgrounds() ) {
                String bgUrl = MCLauncherMainGui.resolveBackgroundUrl( newEntry.pack );
                if ( bgUrl != null ) {
                    String existing = bgLayer.getStyle() == null ? "" : bgLayer.getStyle();
                    bgLayer.setStyle( existing + " -fx-background-image: url('" + bgUrl + "');" );
                }
            }

            // ----- Badge row -----
            badgeRow.getChildren().clear();
            String badge = topBadgeFor( newEntry );
            String badgeStyle = topBadgeStyleFor( newEntry );
            if ( badge != null ) {
                badgeRow.getChildren().add( buildChip( badge, badgeStyle ) );
            }

            // ----- Logo -----
            // Always clear first so a previous bind's image doesn't briefly flash
            // through during the new resolveLogoForEntry's async fetch.
            logo.setImage( null );
            final LibraryEntry capturedEntry = newEntry;
            Image logoImage = resolveLogoForEntry( newEntry, officialImage -> {
                // Pack-identity guard: the card may have been rebound to a
                // different entry while the official-logo download was in
                // flight; ignore the late update in that case so we don't
                // paint over a now-newer entry's logo.
                if ( this.entry == capturedEntry ) {
                    logo.setImage( officialImage );
                    ImageFadeIn.apply( logo );
                }
            } );
            if ( logoImage != null ) {
                logo.setImage( logoImage );
                // Fade the logo in once the bytes arrive — see ImageFadeIn for rationale.
                ImageFadeIn.apply( logo );
            }

            // ----- Body text + chips -----
            name.setText( newEntry.displayName );
            chips.getChildren().clear();
            for ( String chip : newEntry.chips ) {
                chips.getChildren().add( buildChip( chip ) );
            }
            status.setText( newEntry.statusText );

            // ----- Action row -----
            actions.getChildren().clear();
            for ( javafx.scene.Node node : buildActionsFor( newEntry ) ) {
                actions.getChildren().add( node );
            }

            // ----- Context menu -----
            // Only installed modpacks get a right-click menu (open folder, copy
            // invite link, uninstall). Clear any handler from a prior bind first
            // so a recycled card whose previous entry was MODPACK_INSTALLED but
            // is now (say) LOADER_AVAILABLE doesn't keep the old menu wired up.
            setOnContextMenuRequested( null );
            if ( newEntry.kind == LibraryEntry.Kind.MODPACK_INSTALLED && newEntry.pack != null ) {
                final GameModPack ctxPack = newEntry.pack;
                ContextMenu menu = MCLauncherMainGui.buildPackContextMenu(
                        ctxPack, stage,
                        MCLauncherGameLibraryGui.this::showBackgroundStatus,
                        MCLauncherGameLibraryGui.this::hideBackgroundStatus,
                        MCLauncherGameLibraryGui.this::rebuildCards );
                setOnContextMenuRequested( e ->
                        menu.show( this, e.getScreenX(), e.getScreenY() ) );
            }
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
                case LOADER_AVAILABLE   -> "Available";
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
                case LOADER_AVAILABLE   -> "stat-chip-info";
            };
        }

        /** Per-kind action buttons. Returns the action HBox children (buttons). */
        private List< javafx.scene.Node > buildActionsFor( LibraryEntry entry )
        {
            List< javafx.scene.Node > out = new ArrayList<>();
            switch ( entry.kind ) {
                case MODPACK_INSTALLED -> {
                    MFXButton uninstall = primaryAction( "Uninstall", "dangerZone", () -> uninstallInstalledModpack( entry ) );
                    MFXButton edit = secondaryAction( "Edit", () -> openModpackEditor( entry.pack ) );
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
                case LOADER_AVAILABLE -> {
                    MFXButton install = primaryAction( "Install", "primary", () -> installAvailableLoader( entry ) );
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
        confirmAndUninstallModpack( entry.pack, entry.displayName, stage,
                                    this::showBackgroundStatus,
                                    this::hideBackgroundStatus,
                                    this::rebuildCards );
    }

    /**
     * Reusable uninstall flow: confirmation dialog → background worker that
     * optionally deletes the install folder and removes the pack from the
     * installed list. Used by the Library cards' Uninstall button and the
     * right-click context menus on both the Library cards and the main menu's
     * pack carousel — same prompt, same options, same cleanup, just different
     * progress affordances per caller.
     *
     * @param pack          the installed modpack to remove (no-op when null)
     * @param displayName   user-facing name; appears in the dialog title +
     *                      progress text
     * @param owner         dialog owner Stage (focus / theming)
     * @param showProgress  optional status updater (e.g. bottom-bar label
     *                      flip). Receives the localized progress string;
     *                      ignored when null
     * @param hideProgress  optional callback to dismiss the progress UI
     *                      after the worker finishes (success or failure);
     *                      runs on the FX thread; ignored when null
     * @param onComplete    optional FX-thread callback fired after the
     *                      uninstall succeeds, before progress is hidden;
     *                      typical use is rebuilding the caller's pack
     *                      grid so the removed pack disappears
     */
    public static void confirmAndUninstallModpack( GameModPack pack,
                                                    String displayName,
                                                    Stage owner,
                                                    java.util.function.Consumer< String > showProgress,
                                                    Runnable hideProgress,
                                                    Runnable onComplete )
    {
        if ( pack == null ) return;
        int response = GUIUtilities.showQuestionMessage(
                "Uninstall Modpack",
                "Uninstall " + displayName + "?",
                "Would you also like to delete the installed game files?",
                "Uninstall & Delete Files", "Uninstall (Keep Files)", owner );
        if ( response == 0 ) return;
        boolean deleteFiles = ( response == 1 );

        if ( showProgress != null ) showProgress.accept( "Removing " + displayName + "…" );
        SystemUtilities.spawnNewTask( () -> {
            try {
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
                if ( onComplete != null ) GUIUtilities.JFXPlatformRun( onComplete );
            }
            finally {
                if ( hideProgress != null ) GUIUtilities.JFXPlatformRun( hideProgress );
            }
        } );
    }

    private void installAvailableModpack( LibraryEntry entry )
    {
        if ( entry.pack == null ) return;
        // installModPackByFriendlyName matches the public manifest's friendly-name list, so
        // the available pack's getFriendlyName() is the right key.
        final String friendly = entry.pack.getFriendlyName();
        if ( friendly == null || friendly.isBlank() ) return;
        final String displayName = entry.displayName;
        showBackgroundStatus( "Installing " + displayName + "…" );
        SystemUtilities.spawnNewTask( () -> {
            try {
                GameModPackManager.installModPackByFriendlyName( friendly );
                GUIUtilities.JFXPlatformRun( this::rebuildCards );
                NotificationManager.success( "Modpack installed", displayName + " is now available to play." );
            }
            finally {
                hideBackgroundStatus();
            }
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

        showBackgroundStatus( "Removing Minecraft " + id + "…" );
        SystemUtilities.spawnNewTask( () -> {
            try {
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
            }
            finally {
                hideBackgroundStatus();
            }
        } );
    }

    private void installAvailableVanilla( LibraryEntry entry )
    {
        if ( entry.vanillaVersionId == null ) return;
        String id = entry.vanillaVersionId;
        showBackgroundStatus( "Installing Minecraft " + id + "…" );
        SystemUtilities.spawnNewTask( () -> {
            try {
                if ( VanillaVersionManager.isInstalled( id ) ) {
                    return;
                }
                VanillaVersionManager.installVersion( id );
                GUIUtilities.JFXPlatformRun( this::rebuildCards );
                NotificationManager.success( "Minecraft installed", "Minecraft " + id + " is now available to play." );
            }
            finally {
                hideBackgroundStatus();
            }
        } );
    }

    /** Install handler for a {@link LibraryEntry.Kind#LOADER_AVAILABLE}
     *  entry. Writes the synthetic loader manifest via
     *  {@link com.micatechnologies.minecraft.launcher.game.modpack.LoaderVersionManager}
     *  and rebuilds cards so the new pack shows up under
     *  Installed Modpacks immediately. */
    private void installAvailableLoader( LibraryEntry entry )
    {
        var v = entry.loaderVersion;
        if ( v == null ) return;
        String label = v.displayName();
        showBackgroundStatus( "Installing " + label + "…" );
        SystemUtilities.spawnNewTask( () -> {
            try {
                if ( com.micatechnologies.minecraft.launcher.game.modpack.LoaderVersionManager.isInstalled( v ) ) {
                    return;
                }
                com.micatechnologies.minecraft.launcher.game.modpack.LoaderVersionManager.installVersion( v );
                GUIUtilities.JFXPlatformRun( this::rebuildCards );
                NotificationManager.success( "Loader installed",
                                             label + " is now available as an empty modpack." );
            }
            catch ( IOException ex ) {
                Logger.logError( "Failed to install loader version " + label );
                Logger.logThrowable( ex );
                NotificationManager.error( "Install failed", "Could not install " + label + ": " + ex.getMessage() );
            }
            finally {
                hideBackgroundStatus();
            }
        } );
    }

    private void openModpackEditor( GameModPack initialPack )
    {
        SystemUtilities.spawnNewTask( () -> {
            try { MCLauncherGuiController.goToModPackEditorGui( initialPack ); }
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
        // Available loader versions (Forge/NeoForge/Fabric) — these install
        // as empty packs, so they share the modded-defaults look with the
        // catch-all branch below. Explicit case for exhaustive readability.
        if ( entry.kind == LibraryEntry.Kind.LOADER_AVAILABLE ) {
            bgLayer.getStyleClass().add( "heroBackgroundDefaultForge" );
            return;
        }
        bgLayer.getStyleClass().add( "heroBackgroundDefaultForge" );
    }

    /** No-callback overload for callers that don't care about the
     *  official-logo async upgrade (e.g. dominant-colour sampling for the
     *  card background — the placeholder's brand colour is fine there). */
    private static Image resolveLogoForEntry( LibraryEntry entry )
    {
        return resolveLogoForEntry( entry, img -> { /* no async upgrade needed */ } );
    }

    /** Resolves the logo for a card entry. Critical perf note: for available modpacks we use
     *  {@code pack.getPackLogoURL()} (the source URL from the manifest) directly with
     *  {@code new Image(url, true)} rather than {@code getPackLogoFilepath()} — the latter
     *  triggers an environment cache download synchronously, which on a fresh launcher with
     *  dozens of available packs would block the FX thread for seconds. JavaFX Image with
     *  {@code backgroundLoading=true} fetches the URL on its own worker thread.
     *
     *  <p>For {@code LOADER_AVAILABLE} entries the call returns the canvas placeholder
     *  immediately and (if the official project logo isn't cached on disk yet) fires
     *  {@code onOfficialReady} from the FX thread once the download finishes. The caller
     *  is responsible for guarding against rebind-during-async by checking that the card
     *  still represents the entry whose logo was requested before applying the late
     *  upgrade.</p> */
    private static Image resolveLogoForEntry( LibraryEntry entry,
                                               PlaceholderLogoFactory.ImageReadyCallback onOfficialReady )
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
            if ( entry.kind == LibraryEntry.Kind.VANILLA_INSTALLED
                    || entry.kind == LibraryEntry.Kind.VANILLA_AVAILABLE ) {
                // Vanilla cards have no per-version artwork. Use the green-grass
                // "MC" placeholder so the card visually reads as Minecraft at
                // a glance instead of generic launcher branding.
                return PlaceholderLogoFactory.getVanillaLogo();
            }
            if ( entry.kind == LibraryEntry.Kind.LOADER_AVAILABLE
                    && entry.loaderVersion != null ) {
                // Forge / NeoForge / Fabric — return the project's official
                // logo if it's been cached locally, otherwise a brand-coloured
                // placeholder and a background fetch that will upgrade the
                // card once the official PNG lands on disk.
                return PlaceholderLogoFactory.resolveLogo(
                        entry.loaderVersion.loaderType(), onOfficialReady );
            }
        }
        catch ( Exception ignored ) { /* fall through */ }
        return null;
    }
}
