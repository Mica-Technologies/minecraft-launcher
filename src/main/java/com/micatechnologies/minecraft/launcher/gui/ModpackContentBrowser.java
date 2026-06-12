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

import com.micatechnologies.minecraft.launcher.LauncherCore;
import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.game.modpack.GameModPack;
import com.micatechnologies.minecraft.launcher.game.modpack.ServerFavorite;
import com.micatechnologies.minecraft.launcher.game.modpack.ServerFavoritesStore;
import com.micatechnologies.minecraft.launcher.utilities.DiscordRpcUtility;
import com.micatechnologies.minecraft.launcher.utilities.FxAsyncTask;
import com.micatechnologies.minecraft.launcher.utilities.NetworkUtilities;
import com.micatechnologies.minecraft.launcher.utilities.NotificationManager;
import com.micatechnologies.minecraft.launcher.utilities.SystemUtilities;
import io.github.palexdev.materialfx.controls.MFXButton;
import io.github.palexdev.materialfx.controls.MFXTextField;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.stage.Stage;

import java.awt.Desktop;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;

/**
 * Builds the per-pack content-browser sections (Worlds, Screenshots,
 * Shader Packs, Resource Packs) for the modpack detail modal, plus a
 * lightweight in-window image viewer overlay used when the user clicks
 * a screenshot thumbnail.
 *
 * <p>Each section is a {@link VBox} returned by one of the
 * {@code buildXxxSection} methods. Construction is fast — directory
 * listing only — so the call can happen on the FX thread inside the
 * modal's body assembly. Filesystem actions (Open Folder, Delete) run
 * via {@link FxAsyncTask} so they don't block the renderer.</p>
 *
 * <p>This lives outside {@code MCLauncherModpackDetailModal} to keep the
 * already-1200-line modal class focused on layout / lifecycle. The
 * sections call back into the modal's existing helpers
 * ({@code buildSectionBox}, {@code buildStatRow} equivalents) by
 * receiving them as method parameters / interfaces — see
 * {@link SectionBuilder}.</p>
 *
 * @since 2026.5
 */
public final class ModpackContentBrowser
{
    private ModpackContentBrowser() { /* static-only */ }

    /** Functional interface so the caller (the detail modal) can hand
     *  us its private {@code buildSectionBox} implementation without
     *  this class needing visibility into the modal's internals.
     *  The {@code defaultExpanded} parameter lets each section pick
     *  its initial state — sections that typically carry lots of rows
     *  (mods, screenshots, crash history) default to collapsed to
     *  keep the modal's scroll manageable for large packs. */
    @FunctionalInterface
    public interface SectionBuilder
    {
        VBox build( String heading, boolean defaultExpanded );
    }

    private static final double THUMB_SIZE = 100;
    private static final double THUMB_GAP  = 8;
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat( "yyyy-MM-dd HH:mm" );

    // ====================================================================
    // Public section builders
    // ====================================================================

    /** Lists each subfolder of {@code <packRoot>/saves/} as a row with
     *  size + last-modified metadata and per-row Open Folder + Delete
     *  actions. */
    public static Node buildWorldsSection( GameModPack pack, SectionBuilder sectionBox, Stage owner )
    {
        // Worlds list is typically 0-5 entries — expanded by default.
        VBox section = sectionBox.build( LocalizationManager.get( "detailModal.section.worlds" ), true );
        populateAsync( section,
                       () -> scanSortedFiles( pack, "saves", File::isDirectory,
                                              Comparator.comparingLong( File::lastModified ).reversed() ),
                       ( sec, worlds ) -> {
                           if ( worlds == null || worlds.length == 0 ) {
                               sec.getChildren().add( emptyLabel() );
                               return;
                           }
                           for ( File world : worlds ) {
                               sec.getChildren().add( buildFileRow( world, true, true, owner, sec ) );
                           }
                       } );
        return section;
    }

    /** Renders a thumbnail grid of {@code <packRoot>/screenshots/*.png}.
     *  Each thumbnail is clickable → opens the image viewer overlay
     *  attached to {@code overlayHost}. */
    public static Node buildScreenshotsSection( GameModPack pack, SectionBuilder sectionBox,
                                                  StackPane overlayHost )
    {
        // Screenshots can grow large — pre-collapse so a pack with
        // hundreds of screenshot files doesn't bloat the modal scroll.
        // Population is gated on first-expand: a user who never opens
        // this section never pays the FS scan + tile construction cost.
        VBox section = sectionBox.build( LocalizationManager.get( "detailModal.section.screenshots" ), false );
        MCLauncherModpackDetailModal.registerOnFirstExpand( section, () -> populateAsync( section,
                       () -> scanSortedFiles( pack, "screenshots",
                                              f -> {
                                                  if ( !f.isFile() ) return false;
                                                  String n = f.getName().toLowerCase();
                                                  return n.endsWith( ".png" )
                                                          || n.endsWith( ".jpg" )
                                                          || n.endsWith( ".jpeg" );
                                              },
                                              Comparator.comparingLong( File::lastModified ).reversed() ),
                       ( sec, shots ) -> {
                           if ( shots == null || shots.length == 0 ) {
                               sec.getChildren().add( emptyLabel() );
                               return;
                           }
                           FlowPane grid = new FlowPane( THUMB_GAP, THUMB_GAP );
                           for ( File shot : shots ) {
                               grid.getChildren().add( buildScreenshotTile( shot, overlayHost ) );
                           }
                           sec.getChildren().add( grid );
                       } ) );
        return section;
    }

    /** Lists each {@code <packRoot>/mods/*.jar} + {@code *.jar.disabled}
     *  entry with a Disable / Enable toggle. The toggle renames the file
     *  between {@code foo.jar} and {@code foo.jar.disabled} — Minecraft
     *  + Forge / Fabric only load {@code .jar} files from the mods
     *  folder, so the disabled state is honored on the next launch.
     *  Useful for "is this mod the one crashing?" diagnosis without
     *  leaving the launcher. */
    public static Node buildModsSection( GameModPack pack, SectionBuilder sectionBox, VBox bodyToRebuild,
                                         Stage owner )
    {
        // Mods is the biggest section by far — a typical Forge pack
        // carries 100+ jars. Pre-collapse + lazy-populate: 100+ HBoxes
        // (each with label + toggle button) constructed at modal open
        // would dominate the FX-thread render storm right when the
        // user wants to see the modal. Gated on first-expand instead.
        VBox section = sectionBox.build( LocalizationManager.get( "detailModal.section.mods" ), false );
        MCLauncherModpackDetailModal.registerOnFirstExpand( section, () -> populateAsync( section,
                       () -> scanSortedFiles( pack, "mods",
                                              f -> {
                                                  if ( !f.isFile() ) return false;
                                                  String n = f.getName().toLowerCase();
                                                  return n.endsWith( ".jar" ) || n.endsWith( ".jar.disabled" );
                                              },
                                              // Group enabled + disabled together by sorting by
                                              // case-insensitive filename — the .disabled suffix
                                              // naturally sorts after the bare .jar.
                                              Comparator.comparing( ( File f ) -> f.getName().toLowerCase() ) ),
                       ( sec, mods ) -> {
                           if ( mods == null || mods.length == 0 ) {
                               sec.getChildren().add( emptyLabel() );
                               return;
                           }
                           File modsDirRef = subDir( pack, "mods" );
                           renderModsSection( sec, modsDirRef, mods, bodyToRebuild, pack, owner );
                       } ) );
        return section;
    }

    private static void renderModsSection( VBox section, File modsDir, File[] mods, VBox bodyToRebuild,
                                           GameModPack pack, Stage owner )
    {
        // "Check for updates" affordance — runs a background scan that
        // hashes each enabled jar + queries Modrinth's /version_file
        // endpoint. Result is one ModUpdate per jar; the row's meta label
        // is updated in place with "Update available v X → vY" or "Up to
        // date" or "Not on Modrinth" when the scan finishes. Per-row
        // labels are captured in the modUpdateLabels map below so the
        // background task can write back to them on the FX thread.
        java.util.Map< String, Label > modUpdateLabels = new java.util.HashMap<>();
        // Row per enabled jar so the scan can splice a one-click "Update" button into the ones with
        // a newer Modrinth version available.
        java.util.Map< String, HBox > modRows = new java.util.HashMap<>();
        HBox header = new HBox( 10 );
        header.setAlignment( Pos.CENTER_LEFT );
        MFXButton checkUpdatesBtn = new MFXButton( LocalizationManager.get( "detailModal.mods.checkUpdates" ) );
        checkUpdatesBtn.getStyleClass().add( "heroCardSecondaryBtn" );
        checkUpdatesBtn.setPrefHeight( 28 );
        Label checkUpdatesStatus = new Label();
        checkUpdatesStatus.getStyleClass().add( "muted" );
        checkUpdatesBtn.setOnAction( e -> {
            checkUpdatesBtn.setDisable( true );
            checkUpdatesStatus.setText( LocalizationManager.get( "detailModal.mods.checking" ) );
            final File modsDirRef = modsDir;
            FxAsyncTask.run( () -> {
                try {
                    java.util.Map< String,
                            com.micatechnologies.minecraft.launcher.game.modpack
                                    .ModrinthModUpdateChecker.ModUpdate > results =
                            com.micatechnologies.minecraft.launcher.game.modpack
                                    .ModrinthModUpdateChecker.scan( modsDirRef );
                    int onModrinth = 0;
                    int updateAvailable = 0;
                    for ( var entry : results.entrySet() ) {
                        if ( entry.getValue().status()
                                != com.micatechnologies.minecraft.launcher.game.modpack
                                .ModrinthModUpdateChecker.Status.NOT_ON_MODRINTH ) onModrinth++;
                        if ( entry.getValue().status()
                                == com.micatechnologies.minecraft.launcher.game.modpack
                                .ModrinthModUpdateChecker.Status.UPDATE_AVAILABLE ) updateAvailable++;
                    }
                    final int onModrinthFinal = onModrinth;
                    final int updateAvailableFinal = updateAvailable;
                    javafx.application.Platform.runLater( () -> {
                        for ( var entry : results.entrySet() ) {
                            Label lbl = modUpdateLabels.get( entry.getKey() );
                            if ( lbl == null ) continue;
                            var v = entry.getValue();
                            switch ( v.status() ) {
                                case UPDATE_AVAILABLE -> {
                                    lbl.setText( LocalizationManager.format(
                                            "detailModal.mods.updateAvailable",
                                            v.currentVersion() == null ? "?" : v.currentVersion(),
                                            v.latestVersion() == null ? "?" : v.latestVersion() ) );
                                    // Splice in a one-click "Update" button for this row, wired to the
                                    // latest version's primary download URL the scan resolved.
                                    HBox row = modRows.get( entry.getKey() );
                                    if ( row != null && v.latestDownloadUrl() != null ) {
                                        addUpdateButton( row, modsDirRef, entry.getKey(),
                                                         v.latestDownloadUrl(), v.latestVersion(), lbl );
                                    }
                                }
                                case UP_TO_DATE -> lbl.setText( LocalizationManager.get(
                                        "detailModal.mods.upToDate" ) );
                                case NOT_ON_MODRINTH -> lbl.setText( LocalizationManager.get(
                                        "detailModal.mods.notOnModrinth" ) );
                            }
                        }
                        checkUpdatesStatus.setText( LocalizationManager.format(
                                "detailModal.mods.checkSummary",
                                onModrinthFinal, results.size(), updateAvailableFinal ) );
                        checkUpdatesBtn.setDisable( false );
                    } );
                }
                catch ( Throwable t ) {
                    Logger.logWarningSilent( LocalizationManager.format( "log.contentBrowser.modUpdateScanThrew", t.getClass().getSimpleName() ) );
                    javafx.application.Platform.runLater( () -> {
                        checkUpdatesStatus.setText( LocalizationManager.get( "detailModal.mods.checkFailed" ) );
                        checkUpdatesBtn.setDisable( false );
                    } );
                }
            } );
        } );
        header.getChildren().addAll( checkUpdatesBtn, checkUpdatesStatus );
        // "Add Mod" — opens the Modrinth search dialog (modded packs only; vanilla has no loader).
        if ( pack != null && pack.getModLoaderType() != null ) {
            Region headerSpacer = new Region();
            HBox.setHgrow( headerSpacer, Priority.ALWAYS );
            MFXButton addModBtn = new MFXButton( LocalizationManager.get( "detailModal.mods.addMod" ) );
            addModBtn.getStyleClass().add( "heroCardSecondaryBtn" );
            addModBtn.setPrefHeight( 28 );
            addModBtn.setOnAction( e -> ModrinthAddModDialog.show( pack, modsDir, owner ) );
            header.getChildren().addAll( headerSpacer, addModBtn );
        }
        section.getChildren().add( header );

        for ( File mod : mods ) {
            HBox row = buildModRow( mod, bodyToRebuild );
            // Track the row's update-label so the background scan can
            // populate it. Only attach for enabled .jar entries — disabled
            // mods aren't on the loader path so update status doesn't apply.
            if ( !mod.getName().toLowerCase().endsWith( ".jar.disabled" ) ) {
                Label updateLabel = new Label();
                updateLabel.getStyleClass().add( "muted" );
                row.getChildren().add( row.getChildren().size() - 1, updateLabel );
                modUpdateLabels.put( mod.getName(), updateLabel );
                modRows.put( mod.getName(), row );
            }
            section.getChildren().add( row );
        }
    }

    /** One row for a mod jar — enabled or disabled. The toggle button
     *  renames the file between {@code foo.jar} and {@code foo.jar.disabled};
     *  after the rename, the row's controls (label / button / meta) are
     *  updated in place so the user sees the new state without a modal
     *  re-render. */
    private static HBox buildModRow( File initialFile, VBox unused )
    {
        HBox row = new HBox( 10 );
        row.setAlignment( Pos.CENTER_LEFT );
        row.getStyleClass().add( "modpackDetailContentRow" );
        row.setPadding( new Insets( 4, 0, 4, 0 ) );

        // Mutable holders so the toggle handler can swap the row's
        // backing file + UI controls in place without rebuilding the row.
        final File[] currentFile = { initialFile };

        Label name = new Label();
        name.getStyleClass().add( "modpackDetailContentName" );
        HBox.setHgrow( name, Priority.ALWAYS );
        name.setMaxWidth( Double.MAX_VALUE );

        Label meta = new Label();
        meta.getStyleClass().add( "muted" );

        MFXButton toggleBtn = new MFXButton();
        toggleBtn.getStyleClass().add( "heroCardSecondaryBtn" );
        toggleBtn.setPrefHeight( 28 );

        // Render once for the initial state, then again after each rename.
        Runnable renderRowState = () -> {
            File f = currentFile[ 0 ];
            boolean disabled = f.getName().toLowerCase().endsWith( ".jar.disabled" );
            String displayName = disabled
                    ? f.getName().substring( 0, f.getName().length() - ".disabled".length() )
                    : f.getName();
            name.setText( displayName );
            // Dim disabled mods so the user can tell at a glance which
            // jars are active. The CSS class "muted" already lives in
            // ui-base.css.
            name.getStyleClass().remove( "muted" );
            if ( disabled ) name.getStyleClass().add( "muted" );

            meta.setText( disabled
                    ? LocalizationManager.get( "detailModal.mods.disabled" )
                    : humanSize( f.length() ) );

            toggleBtn.setText( disabled
                    ? LocalizationManager.get( "detailModal.mods.enable" )
                    : LocalizationManager.get( "detailModal.mods.disable" ) );
        };
        renderRowState.run();

        toggleBtn.setOnAction( e -> toggleModEnabled( currentFile, renderRowState, toggleBtn ) );

        row.getChildren().addAll( name, meta, toggleBtn );
        return row;
    }

    /** Atomic rename between {@code foo.jar} and {@code foo.jar.disabled}.
     *  Updates the {@code currentFile} reference + re-renders the row on
     *  success. Failure (locked file, permission denied) surfaces as a
     *  warning toast — non-fatal, the user just tries again after closing
     *  the game. */
    private static void toggleModEnabled( File[] currentFile, Runnable rerender, MFXButton btn )
    {
        // Disable the button during the rename so a frantic double-click
        // doesn't fire two toggles in a row.
        btn.setDisable( true );
        FxAsyncTask.run( () -> {
            File mod = currentFile[ 0 ];
            try {
                boolean disabled = mod.getName().toLowerCase().endsWith( ".jar.disabled" );
                File renamed = disabled
                        ? new File( mod.getParentFile(),
                                    mod.getName().substring( 0, mod.getName().length() - ".disabled".length() ) )
                        : new File( mod.getParentFile(), mod.getName() + ".disabled" );
                if ( renamed.exists() ) {
                    NotificationManager.warn( LocalizationManager.get( "notification.content.toggleModConflict.title" ),
                                              LocalizationManager.format( "notification.content.toggleModConflict.body", renamed.getName() ) );
                    return;
                }
                java.nio.file.Files.move( mod.toPath(), renamed.toPath(),
                                          java.nio.file.StandardCopyOption.ATOMIC_MOVE );
                currentFile[ 0 ] = renamed;
                javafx.application.Platform.runLater( rerender );
            }
            catch ( Exception ex ) {
                Logger.logWarningSilent( LocalizationManager.format( "log.contentBrowser.modToggleFailed", mod.getName(), ex.getMessage() ) );
                NotificationManager.warn( LocalizationManager.get( "notification.content.toggleModFailed.title" ),
                                          LocalizationManager.get( "notification.content.toggleModFailed.body" ) );
            }
            finally {
                javafx.application.Platform.runLater( () -> btn.setDisable( false ) );
            }
        } );
    }

    /** Splices a one-click "Update" button into a mod row whose Modrinth scan found a newer
     *  version. Inserted just before the enable/disable toggle (the row's last child). */
    private static void addUpdateButton( HBox row, File modsDir, String oldJarName,
                                         String downloadUrl, String newVersion, Label updateLabel )
    {
        // Idempotent — re-running the scan shouldn't stack multiple Update buttons on a row.
        if ( row.lookup( ".modUpdateApplyBtn" ) != null ) {
            return;
        }
        MFXButton updateBtn = new MFXButton( LocalizationManager.get( "detailModal.mods.update" ) );
        updateBtn.getStyleClass().addAll( "heroCardSecondaryBtn", "modUpdateApplyBtn" );
        updateBtn.setPrefHeight( 28 );
        updateBtn.setOnAction( e -> {
            updateBtn.setDisable( true );
            updateLabel.setText( LocalizationManager.get( "detailModal.mods.updating" ) );
            FxAsyncTask.run( () -> applyModUpdate( modsDir, oldJarName, downloadUrl, newVersion,
                                                   row, updateBtn, updateLabel ) );
        } );
        // Toggle button is last; insert the Update button immediately before it.
        int insertAt = Math.max( 0, row.getChildren().size() - 1 );
        row.getChildren().add( insertAt, updateBtn );
    }

    /** Downloads the latest mod jar, replaces the old one, and finalizes the row's UI. Runs off the
     *  FX thread (called from {@link FxAsyncTask}); all scene-graph writes are marshaled back. */
    private static void applyModUpdate( File modsDir, String oldJarName, String downloadUrl,
                                        String newVersion, HBox row, MFXButton updateBtn,
                                        Label updateLabel )
    {
        try {
            // Modrinth download URLs end in the filename — use it so a renamed jar lands correctly.
            String newName = downloadUrl.substring( downloadUrl.lastIndexOf( '/' ) + 1 );
            newName = java.net.URLDecoder.decode( newName, java.nio.charset.StandardCharsets.UTF_8 );
            if ( newName.isBlank() || !newName.toLowerCase().endsWith( ".jar" ) ) {
                newName = oldJarName;
            }
            File tempFile = new File( modsDir, "." + newName + ".update.tmp" );
            NetworkUtilities.downloadFileFromURL( new java.net.URL( downloadUrl ), tempFile );

            File newFile = new File( modsDir, newName );
            java.nio.file.Files.move( tempFile.toPath(), newFile.toPath(),
                                      java.nio.file.StandardCopyOption.REPLACE_EXISTING );
            // Remove the superseded jar if the version bump renamed it.
            File oldFile = new File( modsDir, oldJarName );
            if ( !oldFile.getName().equals( newFile.getName() ) && oldFile.exists() ) {
                //noinspection ResultOfMethodCallIgnored
                oldFile.delete();
            }

            javafx.application.Platform.runLater( () -> {
                updateLabel.setText( LocalizationManager.format( "detailModal.mods.updated",
                                                                 newVersion == null ? "?" : newVersion ) );
                row.getChildren().remove( updateBtn );
                // The row's enable/disable toggle still references the old filename; disable it so a
                // stale rename can't fail. Re-opening the modal re-scans against the new jar.
                Node last = row.getChildren().isEmpty()
                        ? null : row.getChildren().get( row.getChildren().size() - 1 );
                if ( last instanceof MFXButton toggle ) {
                    toggle.setDisable( true );
                }
            } );
        }
        catch ( Exception ex ) {
            Logger.logWarningSilent( LocalizationManager.format( "log.contentBrowser.modUpdateFailed", oldJarName, ex.getMessage() ) );
            javafx.application.Platform.runLater( () -> {
                updateLabel.setText( LocalizationManager.get( "detailModal.mods.updateFailed" ) );
                updateBtn.setDisable( false );
            } );
            NotificationManager.warn(
                    LocalizationManager.get( "notification.content.modUpdateFailed.title" ),
                    LocalizationManager.format( "notification.content.modUpdateFailed.body", oldJarName ) );
        }
    }

    /** Lists each {@code <packRoot>/shaderpacks/*} entry (zip or
     *  directory) with size + Open Folder action. */
    public static Node buildShaderPacksSection( GameModPack pack, SectionBuilder sectionBox )
    {
        return buildSimplePackList( pack, "shaderpacks", "detailModal.section.shaderPacks", sectionBox );
    }

    /** Lists each {@code <packRoot>/resourcepacks/*} entry with the
     *  same shape as the shader packs section. */
    public static Node buildResourcePacksSection( GameModPack pack, SectionBuilder sectionBox )
    {
        return buildSimplePackList( pack, "resourcepacks", "detailModal.section.resourcePacks", sectionBox );
    }

    /**
     * Per-pack server-favorites list, persisted to
     * {@code <packRoot>/server-favorites.json}. Renders the existing
     * favorites with Connect / Copy IP / Remove actions and an Add row
     * with Name + Address fields at the top. The Connect button sets
     * the pack's transient quick-join target and routes through the
     * same launch flow as the modal's Play button, so the user lands on
     * the server's loading screen instead of the main menu.
     *
     * <p>Section is empty-stated when no favorites exist; the Add row
     * is still present so the user can add their first one.</p>
     */
    /** Snapshot of {@code server-favorites.json} for one pack, read
     *  off the FX thread. Bundles the favorites list with the
     *  disableDefaultServer flag so a single bg trip covers both. */
    private record ServerStoreSnapshot( java.util.List< ServerFavorite > favorites,
                                        boolean defaultServerDisabled ) {}

    public static Node buildServersSection( GameModPack pack, SectionBuilder sectionBox )
    {
        // Servers list is typically tiny — expanded by default.
        VBox section = sectionBox.build( LocalizationManager.get( "detailModal.section.servers" ), true );

        // Manifest-declared default server is read from the pack's in-
        // memory metadata (no I/O), but the auto-join-disabled flag
        // lives in the sidecar — so the row needs FS data before it
        // can render its checkbox correctly. Slot in async.
        ServerFavorite manifestDefault = pack.getDefaultServer();

        // Add row stays synchronous — text fields are cheap, and the
        // user can start typing a new server while the favorites list
        // loads. The handler closes over a mutable favorites list that
        // we'll populate from the background read.
        final java.util.List< ServerFavorite > favorites = new java.util.ArrayList<>();
        HBox addRow = new HBox( 8 );
        addRow.setAlignment( Pos.CENTER_LEFT );
        MFXTextField nameField = new MFXTextField();
        nameField.setPromptText( LocalizationManager.get( "detailModal.servers.namePlaceholder" ) );
        nameField.setPrefWidth( 160 );
        MFXTextField addressField = new MFXTextField();
        addressField.setPromptText( LocalizationManager.get( "detailModal.servers.addressPlaceholder" ) );
        HBox.setHgrow( addressField, Priority.ALWAYS );
        MFXButton addBtn = new MFXButton( LocalizationManager.get( "detailModal.servers.addBtn" ) );
        addBtn.getStyleClass().add( "heroCardPrimaryBtn" );
        addBtn.setPrefHeight( 28 );
        addRow.getChildren().addAll( nameField, addressField, addBtn );

        VBox rowsBox = new VBox( 4 );
        Runnable rerender = () -> renderServerRows( pack, favorites, rowsBox );

        addBtn.setOnAction( e -> {
            ServerFavorite parsed = ServerFavorite.parse( nameField.getText(), addressField.getText() );
            if ( parsed == null ) {
                NotificationManager.warn(
                        LocalizationManager.get( "detailModal.servers.invalidTitle" ),
                        LocalizationManager.get( "detailModal.servers.invalidBody" ) );
                return;
            }
            favorites.add( parsed );
            try {
                ServerFavoritesStore.save( pack, favorites );
            }
            catch ( Exception ex ) {
                Logger.logWarningSilent( LocalizationManager.format( "log.contentBrowser.saveFavoritesFailed", ex.getMessage() ) );
                NotificationManager.warn(
                        LocalizationManager.get( "detailModal.servers.saveFailedTitle" ),
                        LocalizationManager.get( "detailModal.servers.saveFailedBody" ) );
                favorites.remove( favorites.size() - 1 );
                return;
            }
            nameField.clear();
            addressField.clear();
            rerender.run();
        } );

        populateAsync( section,
                       () -> new ServerStoreSnapshot( ServerFavoritesStore.load( pack ),
                                                     ServerFavoritesStore.isDefaultServerDisabled( pack ) ),
                       ( sec, snapshot ) -> {
                           if ( manifestDefault != null ) {
                               sec.getChildren().add( buildDefaultServerRow( pack, manifestDefault,
                                                                              snapshot != null
                                                                                      && snapshot.defaultServerDisabled() ) );
                           }
                           sec.getChildren().add( addRow );
                           sec.getChildren().add( rowsBox );
                           if ( snapshot != null && snapshot.favorites() != null ) {
                               favorites.addAll( snapshot.favorites() );
                           }
                           rerender.run();
                       } );
        return section;
    }

    private static void renderServerRows( GameModPack pack,
                                          java.util.List< ServerFavorite > favorites,
                                          VBox rowsBox )
    {
        rowsBox.getChildren().clear();
        if ( favorites.isEmpty() ) {
            rowsBox.getChildren().add( emptyLabel() );
            return;
        }
        for ( int i = 0; i < favorites.size(); i++ ) {
            rowsBox.getChildren().add( buildServerRow( pack, favorites, i, rowsBox ) );
        }
    }

    private static HBox buildServerRow( GameModPack pack,
                                        java.util.List< ServerFavorite > favorites,
                                        int index,
                                        VBox rowsBox )
    {
        ServerFavorite fav = favorites.get( index );

        HBox row = new HBox( 10 );
        row.setAlignment( Pos.CENTER_LEFT );
        row.getStyleClass().add( "modpackDetailContentRow" );
        row.setPadding( new Insets( 4, 0, 4, 0 ) );

        Label name = new Label( fav.name() );
        name.getStyleClass().add( "modpackDetailContentName" );
        HBox.setHgrow( name, Priority.ALWAYS );
        name.setMaxWidth( Double.MAX_VALUE );

        Label addr = new Label( fav.displayAddress() );
        addr.getStyleClass().add( "muted" );

        MFXButton connectBtn = new MFXButton( LocalizationManager.get( "detailModal.servers.connectBtn" ) );
        connectBtn.getStyleClass().add( "heroCardPrimaryBtn" );
        connectBtn.setPrefHeight( 28 );
        connectBtn.setOnAction( e -> connectToServer( pack, fav ) );

        MFXButton copyBtn = new MFXButton( LocalizationManager.get( "detailModal.servers.copyBtn" ) );
        copyBtn.getStyleClass().add( "heroCardSecondaryBtn" );
        copyBtn.setPrefHeight( 28 );
        copyBtn.setOnAction( e -> {
            ClipboardContent content = new ClipboardContent();
            content.putString( fav.displayAddress() );
            Clipboard.getSystemClipboard().setContent( content );
            NotificationManager.success(
                    LocalizationManager.get( "detailModal.servers.copiedTitle" ),
                    fav.displayAddress() );
        } );

        MFXButton removeBtn = new MFXButton( LocalizationManager.get( "detailModal.servers.removeBtn" ) );
        removeBtn.getStyleClass().add( "heroCardSecondaryBtn" );
        removeBtn.setPrefHeight( 28 );
        removeBtn.setOnAction( e -> {
            favorites.remove( index );
            try {
                ServerFavoritesStore.save( pack, favorites );
            }
            catch ( Exception ex ) {
                Logger.logWarningSilent( LocalizationManager.format( "log.contentBrowser.saveFavoritesFailed", ex.getMessage() ) );
                NotificationManager.warn(
                        LocalizationManager.get( "detailModal.servers.saveFailedTitle" ),
                        LocalizationManager.get( "detailModal.servers.saveFailedBody" ) );
                favorites.add( index, fav );
                return;
            }
            renderServerRows( pack, favorites, rowsBox );
        } );

        row.getChildren().addAll( name, addr, connectBtn, copyBtn, removeBtn );
        return row;
    }

    private static HBox buildDefaultServerRow( GameModPack pack, ServerFavorite manifestDefault, boolean disabled )
    {
        HBox row = new HBox( 10 );
        row.setAlignment( Pos.CENTER_LEFT );
        row.getStyleClass().add( "modpackDetailContentRow" );
        row.setPadding( new Insets( 4, 0, 4, 0 ) );

        Label tag = new Label( LocalizationManager.get( "detailModal.servers.packDefaultLabel" ) );
        tag.getStyleClass().add( "modpackDetailContentName" );

        Label nameLabel = new Label( manifestDefault.name() );
        nameLabel.getStyleClass().add( "modpackDetailContentName" );
        HBox.setHgrow( nameLabel, Priority.ALWAYS );
        nameLabel.setMaxWidth( Double.MAX_VALUE );

        Label addr = new Label( manifestDefault.displayAddress() );
        addr.getStyleClass().add( "muted" );

        javafx.scene.control.CheckBox autoJoin = new javafx.scene.control.CheckBox(
                LocalizationManager.get( "detailModal.servers.autoJoinToggle" ) );
        autoJoin.setSelected( !disabled );
        autoJoin.selectedProperty().addListener( ( obs, was, isNow ) -> {
            try {
                ServerFavoritesStore.setDefaultServerDisabled( pack, !isNow );
            }
            catch ( Exception ex ) {
                Logger.logWarningSilent( LocalizationManager.format( "log.contentBrowser.saveDefaultToggleFailed", ex.getMessage() ) );
                NotificationManager.warn(
                        LocalizationManager.get( "detailModal.servers.saveFailedTitle" ),
                        LocalizationManager.get( "detailModal.servers.saveFailedBody" ) );
                // Revert the visual checkbox state to match what's on disk.
                autoJoin.setSelected( was );
            }
        } );

        row.getChildren().addAll( tag, nameLabel, addr, autoJoin );
        return row;
    }

    /** Sets the pack's quick-join transient field and routes through
     *  the standard launch pipeline. Mirrors the modal's startPlay
     *  pattern (spawn task, set Discord presence, call LauncherCore.play
     *  with the back-to-main-GUI callback). */
    private static void connectToServer( GameModPack pack, ServerFavorite fav )
    {
        pack.setQuickJoinServer( fav );
        SystemUtilities.spawnNewTask( () -> {
            javafx.application.Platform.setImplicitExit( false );
            SystemUtilities.spawnNewTask( () -> DiscordRpcUtility.setGamePresence( pack ) );
            LauncherCore.play( pack, () -> javafx.application.Platform.runLater( () -> {
                try {
                    var top = MCLauncherGuiController.getTopStageOrNull();
                    if ( top != null ) top.show();
                    MCLauncherGuiController.goToMainGui();
                    MCLauncherGuiController.requestFocus();
                }
                catch ( Exception e ) {
                    Logger.logErrorSilent( LocalizationManager.format( "log.contentBrowser.quickJoinReturnFailed", e.getMessage() ) );
                }
            } ) );
        } );
    }

    /** Lists {@code <packRoot>/crash-reports/*.txt} sorted newest-first
     *  with each row showing the filename + mtime + a View action
     *  that opens the crash text in an overlay. Empty when the
     *  folder is missing or empty — typical state for a pack that's
     *  never crashed. */
    public static Node buildCrashHistorySection( GameModPack pack, SectionBuilder sectionBox,
                                                   StackPane overlayHost )
    {
        // Crash history is the kind of thing a user looks at AFTER
        // something went wrong, not on every modal open — pre-collapse
        // + lazy-populate so a pack with a long crash log doesn't pay
        // the FS scan + row construction cost until the user asks for
        // it.
        VBox section = sectionBox.build( LocalizationManager.get( "detailModal.section.crashHistory" ), false );
        MCLauncherModpackDetailModal.registerOnFirstExpand( section, () -> populateAsync( section,
                       () -> scanSortedFiles( pack, "crash-reports",
                                              f -> f.isFile() && f.getName().endsWith( ".txt" ),
                                              Comparator.comparingLong( File::lastModified ).reversed() ),
                       ( sec, reports ) -> {
                           if ( reports == null || reports.length == 0 ) {
                               sec.getChildren().add( emptyLabel() );
                               return;
                           }
                           for ( File r : reports ) {
                               sec.getChildren().add( buildCrashRow( r, overlayHost, pack ) );
                           }
                       } ) );
        return section;
    }

    private static HBox buildCrashRow( File report, StackPane overlayHost, GameModPack pack )
    {
        HBox row = new HBox( 10 );
        row.setAlignment( Pos.CENTER_LEFT );
        row.getStyleClass().add( "modpackDetailContentRow" );
        row.setPadding( new Insets( 4, 0, 4, 0 ) );

        Label name = new Label( report.getName() );
        name.getStyleClass().add( "modpackDetailContentName" );
        HBox.setHgrow( name, Priority.ALWAYS );
        name.setMaxWidth( Double.MAX_VALUE );

        Label meta = new Label( LocalizationManager.format( "detailModal.content.lastModified",
                DATE_FORMAT.format( new Date( report.lastModified() ) ) ) );
        meta.getStyleClass().add( "muted" );

        MFXButton viewBtn = new MFXButton( LocalizationManager.get( "detailModal.crash.viewBtn" ) );
        viewBtn.getStyleClass().add( "heroCardSecondaryBtn" );
        viewBtn.setPrefHeight( 28 );
        viewBtn.setOnAction( e -> showCrashViewer( report, overlayHost, pack ) );

        MFXButton openBtn = new MFXButton( LocalizationManager.get( "detailModal.content.openFolder" ) );
        openBtn.getStyleClass().add( "heroCardSecondaryBtn" );
        openBtn.setPrefHeight( 28 );
        openBtn.setOnAction( e -> openInFileBrowser( report ) );

        row.getChildren().addAll( name, meta, viewBtn, openBtn );
        return row;
    }

    /** Opens a centered scrollable overlay with the crash report's
     *  raw text + Copy / Close actions, plus a diagnosis card at the
     *  top that runs the {@link com.micatechnologies.minecraft.launcher.game.crash.CrashReportAnalyzer}
     *  against the report and surfaces the matched detector's title +
     *  summary + suggested actions. Analyzer runs on a worker so the
     *  overlay opens immediately; the diagnosis card fills in once
     *  the analysis returns. */
    private static void showCrashViewer( File report, StackPane host, GameModPack pack )
    {
        if ( host == null || report == null || !report.isFile() ) return;
        String text;
        try {
            text = java.nio.file.Files.readString( report.toPath() );
        }
        catch ( Exception ex ) {
            text = LocalizationManager.format( "detailModal.crash.viewer.readFailed", report.getName(), ex.getMessage() );
        }
        final String crashText = text;

        StackPane overlay = new StackPane();
        overlay.setStyle( "-fx-background-color: rgba(0,0,0,0.8);" );
        overlay.setPickOnBounds( true );

        VBox card = new VBox( 12 );
        card.setAlignment( Pos.TOP_LEFT );
        card.setPadding( new Insets( 16 ) );
        card.setStyle( "-fx-background-color: -color-surface; -fx-background-radius: 12;" );
        card.setMaxWidth( host.getWidth() * 0.85 );
        card.setMaxHeight( host.getHeight() * 0.85 );

        Label title = new Label( report.getName() );
        title.getStyleClass().add( "heading-h3" );

        // Diagnosis card placeholder — filled in once the analyzer
        // returns on the FX thread. Starts blank so the overlay
        // renders immediately; the analyzer runs on a worker.
        VBox diagnosisBox = new VBox( 6 );
        diagnosisBox.getStyleClass().add( "modpackDetailContentRow" );
        diagnosisBox.setPadding( new Insets( 10, 12, 10, 12 ) );
        diagnosisBox.setStyle( "-fx-background-color: -color-bg-soft; -fx-background-radius: 8;" );
        Label diagnosisLoading = new Label(
                LocalizationManager.get( "detailModal.crash.viewer.analyzing" ) );
        diagnosisLoading.getStyleClass().add( "muted" );
        diagnosisBox.getChildren().add( diagnosisLoading );

        javafx.scene.control.TextArea area = new javafx.scene.control.TextArea( crashText );
        area.setEditable( false );
        area.setWrapText( false );
        area.getStyleClass().add( "text-mono" );
        area.setStyle( "-fx-font-size: 12px;" );
        area.setPrefWidth( host.getWidth() * 0.8 );
        area.setPrefHeight( host.getHeight() * 0.55 );
        VBox.setVgrow( area, Priority.ALWAYS );

        HBox actions = new HBox( 8 );
        actions.setAlignment( Pos.CENTER );

        MFXButton copyBtn = new MFXButton( LocalizationManager.get( "detailModal.crash.viewer.copy" ) );
        copyBtn.getStyleClass().add( "primary" );
        copyBtn.setPrefHeight( 32 );
        copyBtn.setOnAction( e -> {
            ClipboardContent content = new ClipboardContent();
            content.putString( crashText );
            Clipboard.getSystemClipboard().setContent( content );
            copyBtn.setText( LocalizationManager.get( "detailModal.crash.viewer.copied" ) );
            FxAsyncTask.run( () -> {
                Thread.sleep( 1500 );
                javafx.application.Platform.runLater( () -> copyBtn.setText(
                        LocalizationManager.get( "detailModal.crash.viewer.copy" ) ) );
            } );
        } );

        MFXButton openFolderBtn = new MFXButton(
                LocalizationManager.get( "detailModal.crash.viewer.openFolder" ) );
        openFolderBtn.getStyleClass().add( "heroCardSecondaryBtn" );
        openFolderBtn.setPrefHeight( 32 );
        openFolderBtn.setOnAction( e -> openInFileBrowser( report ) );

        MFXButton closeBtn = new MFXButton( LocalizationManager.get( "detailModal.crash.viewer.close" ) );
        closeBtn.getStyleClass().add( "heroCardSecondaryBtn" );
        closeBtn.setPrefHeight( 32 );
        closeBtn.setOnAction( e -> host.getChildren().remove( overlay ) );

        actions.getChildren().addAll( copyBtn, openFolderBtn, closeBtn );
        card.getChildren().addAll( title, diagnosisBox, area, actions );
        overlay.getChildren().add( card );
        overlay.setOnMouseClicked( e -> {
            if ( e.getTarget() == overlay ) host.getChildren().remove( overlay );
        } );
        host.getChildren().add( overlay );

        // Run the diagnosis on a worker — the analyzer's pattern matching
        // is fast but pessimistically off-thread keeps the overlay open
        // immediately even if a future detector grows expensive. Result
        // lands on the FX thread to swap the placeholder for the real
        // diagnosis card.
        SystemUtilities.spawnNewTask( () -> {
            com.micatechnologies.minecraft.launcher.game.crash.CrashDiagnosis diagnosis;
            try {
                diagnosis = com.micatechnologies.minecraft.launcher.game.crash
                        .CrashReportAnalyzer.analyze( crashText, pack, 0 );
            }
            catch ( Throwable t ) {
                Logger.logWarningSilent( LocalizationManager.format( "log.contentBrowser.crashAnalyzerThrew", t.getClass().getSimpleName() ) );
                diagnosis = null;
            }
            final com.micatechnologies.minecraft.launcher.game.crash.CrashDiagnosis finalDiag = diagnosis;
            javafx.application.Platform.runLater( () -> {
                diagnosisBox.getChildren().clear();
                if ( finalDiag == null ) {
                    Label none = new Label( LocalizationManager.get( "detailModal.crash.viewer.analyzeFailed" ) );
                    none.getStyleClass().add( "muted" );
                    diagnosisBox.getChildren().add( none );
                    return;
                }
                Label diagTitle = new Label( finalDiag.title() );
                diagTitle.getStyleClass().add( "modpackDetailContentName" );
                diagTitle.setStyle( "-fx-font-weight: 700;" );

                Label diagSummary = new Label( finalDiag.summary() );
                diagSummary.setWrapText( true );

                diagnosisBox.getChildren().addAll( diagTitle, diagSummary );

                if ( finalDiag.suggestions() != null && !finalDiag.suggestions().isEmpty() ) {
                    HBox suggestionRow = new HBox( 8 );
                    suggestionRow.setAlignment( Pos.CENTER_LEFT );
                    suggestionRow.setPadding( new Insets( 4, 0, 0, 0 ) );
                    for ( com.micatechnologies.minecraft.launcher.game.crash.Suggestion s : finalDiag.suggestions() ) {
                        if ( s == null ) continue;
                        if ( s.action() == null ) {
                            Label hint = new Label( "• " + s.label() );
                            hint.getStyleClass().add( "muted" );
                            hint.setWrapText( true );
                            diagnosisBox.getChildren().add( hint );
                        }
                        else {
                            MFXButton suggBtn = new MFXButton( s.label() );
                            suggBtn.getStyleClass().add( s.primary() ? "primary" : "heroCardSecondaryBtn" );
                            suggBtn.setPrefHeight( 28 );
                            suggBtn.setOnAction( e -> {
                                try { s.action().run(); }
                                catch ( Throwable t ) {
                                    Logger.logWarningSilent( LocalizationManager.format( "log.contentBrowser.crashSuggestionFailed", t.getMessage() ) );
                                }
                            } );
                            suggestionRow.getChildren().add( suggBtn );
                        }
                    }
                    if ( !suggestionRow.getChildren().isEmpty() ) {
                        diagnosisBox.getChildren().add( suggestionRow );
                    }
                }
            } );
        } );
    }

    // ====================================================================
    // Section helpers
    // ====================================================================

    /** Single-row placeholder shown while a section populates
     *  asynchronously. Small indeterminate spinner + "Loading…" label,
     *  styled muted so it doesn't draw attention away from the rest of
     *  the modal. Reused by {@link #populateAsync}. */
    private static HBox buildLoadingPlaceholder()
    {
        HBox row = new HBox( 8 );
        row.setAlignment( Pos.CENTER_LEFT );
        row.setPadding( new Insets( 4, 0, 4, 0 ) );
        javafx.scene.control.ProgressIndicator spinner = new javafx.scene.control.ProgressIndicator();
        spinner.setPrefSize( 16, 16 );
        spinner.setMaxSize( 16, 16 );
        Label label = new Label( LocalizationManager.get( "detailModal.section.loading" ) );
        label.getStyleClass().add( "muted" );
        row.getChildren().addAll( spinner, label );
        return row;
    }

    /**
     * Lazy-populate a section. Drops a {@link #buildLoadingPlaceholder
     * loading spinner} into the section immediately so the modal can
     * fade in with the section header visible, then runs {@code bgWork}
     * on a worker thread. When that returns, the result is handed to
     * {@code fxRender} on the FX thread, which clears the placeholder
     * and appends the real rows.
     *
     * <p>Centralising this pattern keeps every section builder a single
     * synchronous call (returns a Node immediately) while pushing the
     * filesystem scan + heavy-traffic node construction off the FX
     * thread. The user sees the modal open in ~milliseconds with
     * spinners that fill in as data arrives, rather than waiting 1-2 s
     * for everything to load before the modal even appears.</p>
     *
     * @param <T>      result type the bg work produces
     * @param section  section VBox the placeholder + rows go into
     * @param bgWork   off-thread scan / parse / compute work; result
     *                 is handed to {@code fxRender}; may return null
     * @param fxRender on-FX-thread render step that receives the
     *                 section + the bg result; the placeholder is
     *                 already removed by the time this fires
     */
    private static < T > void populateAsync( VBox section,
                                              java.util.function.Supplier< T > bgWork,
                                              java.util.function.BiConsumer< VBox, T > fxRender )
    {
        HBox placeholder = buildLoadingPlaceholder();
        section.getChildren().add( placeholder );
        SystemUtilities.spawnNewTask( () -> {
            T result;
            try {
                result = bgWork.get();
            }
            catch ( Throwable t ) {
                Logger.logWarningSilent( LocalizationManager.format( "log.contentBrowser.sectionBgWorkFailed",
                                                 t.getClass().getSimpleName(), t.getMessage() ) );
                result = null;
            }
            final T finalResult = result;
            javafx.application.Platform.runLater( () -> {
                section.getChildren().remove( placeholder );
                try {
                    fxRender.accept( section, finalResult );
                }
                catch ( Throwable t ) {
                    Logger.logWarningSilent( LocalizationManager.format( "log.contentBrowser.sectionFxRenderFailed",
                                                     t.getClass().getSimpleName() ) );
                    section.getChildren().add( emptyLabel() );
                }
            } );
        } );
    }

    private static Node buildSimplePackList( GameModPack pack, String folderName,
                                              String headingKey, SectionBuilder sectionBox )
    {
        // Shaderpacks / resourcepacks typically have 0-3 entries each
        // — expanded by default.
        VBox section = sectionBox.build( LocalizationManager.get( headingKey ), true );
        populateAsync( section,
                       () -> scanSortedFiles( pack, folderName, f -> !f.getName().startsWith( "." )
                               && ( f.isFile() || f.isDirectory() ),
                                              Comparator.comparing( ( File f ) -> f.getName().toLowerCase() ) ),
                       ( sec, entries ) -> {
                           if ( entries == null || entries.length == 0 ) {
                               sec.getChildren().add( emptyLabel() );
                               return;
                           }
                           for ( File entry : entries ) {
                               sec.getChildren().add( buildFileRow( entry, false, false, null, sec ) );
                           }
                       } );
        return section;
    }

    /** Off-thread directory listing + sort. Returns null when the
     *  directory doesn't exist or no entries match the filter. */
    private static File[] scanSortedFiles( GameModPack pack, String folderName,
                                            java.io.FileFilter filter,
                                            java.util.Comparator< File > sorter )
    {
        File dir = subDir( pack, folderName );
        if ( dir == null ) return null;
        File[] entries = dir.listFiles( filter );
        if ( entries == null || entries.length == 0 ) return null;
        if ( sorter != null ) Arrays.sort( entries, sorter );
        return entries;
    }

    /** Returns the named subfolder of the pack's install dir if it
     *  exists; null otherwise. */
    private static File subDir( GameModPack pack, String name )
    {
        if ( pack == null ) return null;
        String root = pack.getPackRootFolder();
        if ( root == null ) return null;
        File f = new File( root, name );
        return f.isDirectory() ? f : null;
    }

    /** One-row entry for the worlds / shaderpacks / resourcepacks
     *  sections. Showing-folder-size for a deep tree (a world can be
     *  hundreds of MB) is too slow for the FX thread, so the size
     *  column is filled asynchronously after the row renders. */
    private static HBox buildFileRow( File f, boolean computeSizeAsync, boolean includeDelete,
                                       Stage deleteOwner, VBox sectionToRebuild )
    {
        HBox row = new HBox( 10 );
        row.setAlignment( Pos.CENTER_LEFT );
        row.getStyleClass().add( "modpackDetailContentRow" );
        row.setPadding( new Insets( 4, 0, 4, 0 ) );

        Label name = new Label( f.getName() );
        name.getStyleClass().add( "modpackDetailContentName" );
        HBox.setHgrow( name, Priority.ALWAYS );
        name.setMaxWidth( Double.MAX_VALUE );

        Label meta = new Label();
        meta.getStyleClass().add( "muted" );
        // For files: size is cheap (single stat call). For directories:
        // compute off-thread because recursive size could touch
        // thousands of files.
        if ( f.isFile() ) {
            meta.setText( formatMeta( f, f.length() ) );
        }
        else if ( computeSizeAsync ) {
            meta.setText( LocalizationManager.format( "detailModal.content.lastModified",
                                                       DATE_FORMAT.format( new Date( f.lastModified() ) ) ) );
            FxAsyncTask.run( () -> {
                long size = directorySize( f );
                javafx.application.Platform.runLater( () -> meta.setText( formatMeta( f, size ) ) );
            } );
        }
        else {
            meta.setText( LocalizationManager.format( "detailModal.content.lastModified",
                                                       DATE_FORMAT.format( new Date( f.lastModified() ) ) ) );
        }

        MFXButton openBtn = new MFXButton( LocalizationManager.get( "detailModal.content.openFolder" ) );
        openBtn.getStyleClass().add( "heroCardSecondaryBtn" );
        openBtn.setPrefHeight( 28 );
        openBtn.setOnAction( e -> openInFileBrowser( f ) );

        row.getChildren().addAll( name, meta, openBtn );

        if ( includeDelete ) {
            MFXButton delBtn = new MFXButton( LocalizationManager.get( "detailModal.content.delete" ) );
            delBtn.getStyleClass().addAll( "heroCardSecondaryBtn", "dangerZone" );
            delBtn.setPrefHeight( 28 );
            delBtn.setOnAction( e -> confirmDelete( f, deleteOwner, sectionToRebuild ) );
            row.getChildren().add( delBtn );
        }

        return row;
    }

    private static String formatMeta( File f, long sizeBytes )
    {
        String size = humanSize( sizeBytes );
        String mtime = DATE_FORMAT.format( new Date( f.lastModified() ) );
        return size + " · " + LocalizationManager.format( "detailModal.content.lastModified", mtime );
    }

    private static String humanSize( long bytes )
    {
        if ( bytes < 1024 ) return bytes + " B";
        if ( bytes < 1024 * 1024 ) return String.format( "%.1f KB", bytes / 1024.0 );
        if ( bytes < 1024L * 1024 * 1024 ) return String.format( "%.1f MB", bytes / 1024.0 / 1024.0 );
        return String.format( "%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0 );
    }

    /** Best-effort recursive byte count. Errors mid-walk (permission
     *  denied, symlink loops) are silently skipped — the meta column
     *  is informational, not load-bearing. */
    private static long directorySize( File dir )
    {
        if ( dir == null || !dir.isDirectory() ) return 0;
        long total = 0;
        File[] children = dir.listFiles();
        if ( children == null ) return 0;
        for ( File child : children ) {
            try {
                total += child.isDirectory() ? directorySize( child ) : child.length();
            }
            catch ( Exception ignored ) { /* skip unreadable nodes */ }
        }
        return total;
    }

    // ====================================================================
    // Screenshot thumbnails + image viewer overlay
    // ====================================================================

    private static Node buildScreenshotTile( File shot, StackPane overlayHost )
    {
        StackPane tile = new StackPane();
        tile.setPrefSize( THUMB_SIZE, THUMB_SIZE );
        tile.setMinSize( THUMB_SIZE, THUMB_SIZE );
        tile.setMaxSize( THUMB_SIZE, THUMB_SIZE );
        tile.getStyleClass().add( "modpackDetailScreenshotTile" );

        ImageView iv = new ImageView();
        iv.setFitWidth( THUMB_SIZE );
        iv.setFitHeight( THUMB_SIZE );
        iv.setPreserveRatio( true );
        iv.setSmooth( true );
        // backgroundLoading=true so the FX thread doesn't decode the
        // PNG bytes for every thumbnail synchronously during section
        // build; the ImageView fades the image in once the bytes land.
        iv.setImage( new Image( shot.toURI().toString(), THUMB_SIZE, THUMB_SIZE, true, true, true ) );

        Rectangle clip = new Rectangle( THUMB_SIZE, THUMB_SIZE );
        clip.setArcWidth( 8 );
        clip.setArcHeight( 8 );
        iv.setClip( clip );

        tile.getChildren().add( iv );
        tile.setCursor( Cursor.HAND );
        tile.setOnMouseClicked( e -> {
            if ( e.getButton() == MouseButton.PRIMARY && overlayHost != null ) {
                showImageViewer( shot, overlayHost );
            }
        } );
        return tile;
    }

    /** Adds a full-screen image overlay to {@code host} with Copy
     *  Image + Show in File Explorer + Close actions. Click outside
     *  the image (or Close button) dismisses the overlay. */
    public static void showImageViewer( File imageFile, StackPane host )
    {
        StackPane overlay = new StackPane();
        overlay.getStyleClass().add( "imageViewerOverlay" );
        overlay.setStyle( "-fx-background-color: rgba(0,0,0,0.8);" );
        overlay.setPickOnBounds( true );

        VBox card = new VBox( 12 );
        card.setAlignment( Pos.CENTER );
        card.setMaxWidth( Region.USE_PREF_SIZE );
        card.setMaxHeight( Region.USE_PREF_SIZE );
        card.setPadding( new Insets( 16 ) );
        card.getStyleClass().add( "imageViewerCard" );
        card.setStyle( "-fx-background-color: -color-surface; -fx-background-radius: 12;" );

        Image fullImage = new Image( imageFile.toURI().toString() );
        ImageView fullView = new ImageView( fullImage );
        fullView.setPreserveRatio( true );
        // Cap displayed size at the viewport so big screenshots fit.
        double maxWidth  = host.getWidth() * 0.85;
        double maxHeight = host.getHeight() * 0.75;
        if ( maxWidth  > 0 ) fullView.setFitWidth( maxWidth );
        if ( maxHeight > 0 ) fullView.setFitHeight( maxHeight );

        HBox actions = new HBox( 8 );
        actions.setAlignment( Pos.CENTER );

        MFXButton copyBtn = new MFXButton( LocalizationManager.get( "detailModal.imageViewer.copyImage" ) );
        copyBtn.getStyleClass().add( "primary" );
        copyBtn.setPrefHeight( 32 );
        copyBtn.setOnAction( e -> {
            ClipboardContent content = new ClipboardContent();
            content.putImage( fullImage );
            Clipboard.getSystemClipboard().setContent( content );
            copyBtn.setText( LocalizationManager.get( "detailModal.imageViewer.copied" ) );
            FxAsyncTask.run( () -> {
                Thread.sleep( 1500 );
                javafx.application.Platform.runLater( () -> copyBtn.setText(
                        LocalizationManager.get( "detailModal.imageViewer.copyImage" ) ) );
            } );
        } );

        MFXButton showBtn = new MFXButton( LocalizationManager.get( "detailModal.imageViewer.showInFolder" ) );
        showBtn.getStyleClass().add( "heroCardSecondaryBtn" );
        showBtn.setPrefHeight( 32 );
        showBtn.setOnAction( e -> showFileInBrowser( imageFile ) );

        MFXButton closeBtn = new MFXButton( LocalizationManager.get( "detailModal.imageViewer.close" ) );
        closeBtn.getStyleClass().add( "heroCardSecondaryBtn" );
        closeBtn.setPrefHeight( 32 );
        closeBtn.setOnAction( e -> host.getChildren().remove( overlay ) );

        actions.getChildren().addAll( copyBtn, showBtn, closeBtn );

        card.getChildren().addAll( fullView, actions );
        overlay.getChildren().add( card );

        // Click on the dim backdrop (outside the card) dismisses too.
        overlay.setOnMouseClicked( e -> {
            if ( e.getTarget() == overlay ) {
                host.getChildren().remove( overlay );
            }
        } );

        host.getChildren().add( overlay );
    }

    // ====================================================================
    // Filesystem actions
    // ====================================================================

    private static void openInFileBrowser( File f )
    {
        if ( f == null || !f.exists() ) return;
        FxAsyncTask.run( () -> {
            try {
                Desktop.getDesktop().open( f.isDirectory() ? f : f.getParentFile() );
            }
            catch ( Exception ex ) {
                Logger.logWarningSilent( LocalizationManager.format( "log.contentBrowser.openInBrowserFailed", ex.getMessage() ) );
            }
        } );
    }

    /** "Show in File Explorer" — opens the parent folder with the
     *  file selected when the OS supports it. Falls back to opening
     *  the parent folder unselected. */
    private static void showFileInBrowser( File f )
    {
        if ( f == null || !f.exists() ) return;
        FxAsyncTask.run( () -> {
            try {
                if ( org.apache.commons.lang3.SystemUtils.IS_OS_WINDOWS ) {
                    new ProcessBuilder( "explorer.exe", "/select,", f.getAbsolutePath() ).start();
                }
                else if ( org.apache.commons.lang3.SystemUtils.IS_OS_MAC ) {
                    new ProcessBuilder( "open", "-R", f.getAbsolutePath() ).start();
                }
                else {
                    Desktop.getDesktop().open( f.getParentFile() );
                }
            }
            catch ( Exception ex ) {
                Logger.logWarningSilent( LocalizationManager.format( "log.contentBrowser.showInBrowserFailed", ex.getMessage() ) );
            }
        } );
    }

    private static void confirmDelete( File f, Stage owner, VBox sectionToRebuild )
    {
        SystemUtilities.spawnNewTask( () -> {
            int response = GUIUtilities.showQuestionMessage(
                    LocalizationManager.get( "detailModal.content.deleteConfirm.title" ),
                    LocalizationManager.format( "detailModal.content.deleteConfirm.body", f.getName() ),
                    f.getAbsolutePath(),
                    LocalizationManager.get( "detailModal.content.delete" ),
                    LocalizationManager.get( "dialog.button.cancel" ), owner );
            if ( response != 1 ) return;
            FxAsyncTask.run( () -> {
                if ( f.isDirectory() ) {
                    org.codehaus.plexus.util.FileUtils.deleteDirectory( f );
                }
                else {
                    java.nio.file.Files.deleteIfExists( f.toPath() );
                }
                NotificationManager.success( f.getName(), LocalizationManager.get( "notification.content.fileDeleted.body" ) );
                // Rebuild the section's rows: easiest is to remove the
                // row whose name matches. Doing a full section rebuild
                // would need a callback into the detail modal which
                // we don't have a clean hook for here.
                javafx.application.Platform.runLater( () -> {
                    sectionToRebuild.getChildren().removeIf( child -> {
                        if ( child instanceof HBox h ) {
                            for ( javafx.scene.Node n : h.getChildren() ) {
                                if ( n instanceof Label l && f.getName().equals( l.getText() ) ) {
                                    return true;
                                }
                            }
                        }
                        return false;
                    } );
                } );
            } );
        } );
    }

    private static Label emptyLabel()
    {
        Label l = new Label( LocalizationManager.get( "detailModal.content.empty" ) );
        l.getStyleClass().add( "muted" );
        return l;
    }
}
