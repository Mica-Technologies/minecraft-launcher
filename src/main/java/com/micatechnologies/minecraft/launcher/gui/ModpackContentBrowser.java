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

import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.game.modpack.GameModPack;
import com.micatechnologies.minecraft.launcher.utilities.FxAsyncTask;
import com.micatechnologies.minecraft.launcher.utilities.NotificationManager;
import com.micatechnologies.minecraft.launcher.utilities.SystemUtilities;
import io.github.palexdev.materialfx.controls.MFXButton;
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
     *  this class needing visibility into the modal's internals. */
    @FunctionalInterface
    public interface SectionBuilder
    {
        VBox build( String heading );
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
        VBox section = sectionBox.build( LocalizationManager.get( "detailModal.section.worlds" ) );
        File savesDir = subDir( pack, "saves" );
        File[] worlds = savesDir == null ? null : savesDir.listFiles( File::isDirectory );
        if ( worlds == null || worlds.length == 0 ) {
            section.getChildren().add( emptyLabel() );
            return section;
        }
        Arrays.sort( worlds, Comparator.comparingLong( File::lastModified ).reversed() );
        for ( File world : worlds ) {
            section.getChildren().add( buildFileRow( world, true, true, owner, section ) );
        }
        return section;
    }

    /** Renders a thumbnail grid of {@code <packRoot>/screenshots/*.png}.
     *  Each thumbnail is clickable → opens the image viewer overlay
     *  attached to {@code overlayHost}. */
    public static Node buildScreenshotsSection( GameModPack pack, SectionBuilder sectionBox,
                                                  StackPane overlayHost )
    {
        VBox section = sectionBox.build( LocalizationManager.get( "detailModal.section.screenshots" ) );
        File shotsDir = subDir( pack, "screenshots" );
        File[] shots = shotsDir == null ? null : shotsDir.listFiles( ( dir, name ) -> {
            String lower = name.toLowerCase();
            return lower.endsWith( ".png" ) || lower.endsWith( ".jpg" ) || lower.endsWith( ".jpeg" );
        } );
        if ( shots == null || shots.length == 0 ) {
            section.getChildren().add( emptyLabel() );
            return section;
        }
        Arrays.sort( shots, Comparator.comparingLong( File::lastModified ).reversed() );
        FlowPane grid = new FlowPane( THUMB_GAP, THUMB_GAP );
        for ( File shot : shots ) {
            grid.getChildren().add( buildScreenshotTile( shot, overlayHost ) );
        }
        section.getChildren().add( grid );
        return section;
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

    /** Lists {@code <packRoot>/crash-reports/*.txt} sorted newest-first
     *  with each row showing the filename + mtime + a View action
     *  that opens the crash text in an overlay. Empty when the
     *  folder is missing or empty — typical state for a pack that's
     *  never crashed. */
    public static Node buildCrashHistorySection( GameModPack pack, SectionBuilder sectionBox,
                                                   StackPane overlayHost )
    {
        VBox section = sectionBox.build( LocalizationManager.get( "detailModal.section.crashHistory" ) );
        File crashDir = subDir( pack, "crash-reports" );
        File[] reports = crashDir == null ? null
                : crashDir.listFiles( ( dir, name ) -> name.endsWith( ".txt" ) );
        if ( reports == null || reports.length == 0 ) {
            section.getChildren().add( emptyLabel() );
            return section;
        }
        Arrays.sort( reports, Comparator.comparingLong( File::lastModified ).reversed() );
        for ( File r : reports ) {
            section.getChildren().add( buildCrashRow( r, overlayHost ) );
        }
        return section;
    }

    private static HBox buildCrashRow( File report, StackPane overlayHost )
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
        viewBtn.setOnAction( e -> showCrashViewer( report, overlayHost ) );

        MFXButton openBtn = new MFXButton( LocalizationManager.get( "detailModal.content.openFolder" ) );
        openBtn.getStyleClass().add( "heroCardSecondaryBtn" );
        openBtn.setPrefHeight( 28 );
        openBtn.setOnAction( e -> openInFileBrowser( report ) );

        row.getChildren().addAll( name, meta, viewBtn, openBtn );
        return row;
    }

    /** Opens a centered scrollable overlay with the crash report's
     *  raw text + Copy / Close actions. Mirrors the image viewer
     *  overlay pattern from buildScreenshotsSection. */
    private static void showCrashViewer( File report, StackPane host )
    {
        if ( host == null || report == null || !report.isFile() ) return;
        String text;
        try {
            text = java.nio.file.Files.readString( report.toPath() );
        }
        catch ( Exception ex ) {
            text = "Failed to read " + report.getName() + ":\n" + ex.getMessage();
        }
        final String crashText = text;

        StackPane overlay = new StackPane();
        overlay.setStyle( "-fx-background-color: rgba(0,0,0,0.8);" );
        overlay.setPickOnBounds( true );

        VBox card = new VBox( 12 );
        card.setAlignment( Pos.CENTER );
        card.setPadding( new Insets( 16 ) );
        card.setStyle( "-fx-background-color: -color-surface; -fx-background-radius: 12;" );
        card.setMaxWidth( host.getWidth() * 0.85 );
        card.setMaxHeight( host.getHeight() * 0.85 );

        Label title = new Label( report.getName() );
        title.getStyleClass().add( "heading-h3" );

        javafx.scene.control.TextArea area = new javafx.scene.control.TextArea( crashText );
        area.setEditable( false );
        area.setWrapText( false );
        area.getStyleClass().add( "text-mono" );
        area.setStyle( "-fx-font-size: 12px;" );
        area.setPrefWidth( host.getWidth() * 0.8 );
        area.setPrefHeight( host.getHeight() * 0.7 );
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

        MFXButton closeBtn = new MFXButton( LocalizationManager.get( "detailModal.crash.viewer.close" ) );
        closeBtn.getStyleClass().add( "heroCardSecondaryBtn" );
        closeBtn.setPrefHeight( 32 );
        closeBtn.setOnAction( e -> host.getChildren().remove( overlay ) );

        actions.getChildren().addAll( copyBtn, closeBtn );
        card.getChildren().addAll( title, area, actions );
        overlay.getChildren().add( card );
        overlay.setOnMouseClicked( e -> {
            if ( e.getTarget() == overlay ) host.getChildren().remove( overlay );
        } );
        host.getChildren().add( overlay );
    }

    // ====================================================================
    // Section helpers
    // ====================================================================

    private static Node buildSimplePackList( GameModPack pack, String folderName,
                                              String headingKey, SectionBuilder sectionBox )
    {
        VBox section = sectionBox.build( LocalizationManager.get( headingKey ) );
        File dir = subDir( pack, folderName );
        File[] entries = dir == null ? null : dir.listFiles( f ->
                !f.getName().startsWith( "." ) && ( f.isFile() || f.isDirectory() ) );
        if ( entries == null || entries.length == 0 ) {
            section.getChildren().add( emptyLabel() );
            return section;
        }
        Arrays.sort( entries, Comparator.comparing( ( File f ) -> f.getName().toLowerCase() ) );
        for ( File entry : entries ) {
            section.getChildren().add( buildFileRow( entry, false, false, null, section ) );
        }
        return section;
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
                Logger.logWarningSilent( "Could not open in file browser: " + ex.getMessage() );
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
                Logger.logWarningSilent( "Could not show in file browser: " + ex.getMessage() );
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
                NotificationManager.success( f.getName(), "Deleted." );
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
