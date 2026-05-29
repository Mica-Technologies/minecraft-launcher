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
import com.micatechnologies.minecraft.launcher.game.modpack.import_.ModrinthIndex;
import io.github.palexdev.materialfx.controls.MFXButton;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.util.Locale;

/**
 * Step-2 confirmation dialog for the modpack-import flow. The user has already
 * seen the project-level preview (name, summary, MC version, mod loader);
 * this dialog shows the actual mod list pulled from the {@code .mrpack}'s
 * {@code modrinth.index.json}, surfaces a few totals (mod count, total
 * download size), and asks for one final Import / Cancel before the
 * launcher commits to writing the manifest and installing.
 *
 * <p>The mod list lives in a scrollable VBox so packs with 100+ mods don't
 * overflow the screen. Each row shows the mod's filename and human-readable
 * size; the rendering is intentionally cheap (Label per row, no fancy
 * styling) because we may be drawing 200+ rows.</p>
 *
 * @since 2026.3
 */
public final class MCLauncherImportConfirmDialog
{
    private MCLauncherImportConfirmDialog() { /* static-only */ }

    /**
     * Modal-blocks the current FX thread on a new top-level Stage owned by
     * {@code owner}. Returns {@code true} when the user clicked Import,
     * {@code false} on Cancel / window close. Must be called from the FX
     * thread; the caller is expected to have just produced the mrpack
     * download URL + parsed index on a worker thread.
     */
    public static boolean showAndAwait( Stage owner, String packName, String packVersion, ModrinthIndex index )
    {
        Stage stage = new Stage( StageStyle.UTILITY );
        stage.initModality( Modality.WINDOW_MODAL );
        if ( owner != null ) stage.initOwner( owner );
        stage.setTitle( LocalizationManager.get( "dialog.importConfirm.title" ) );

        // ----- Header: pack name + summary line + totals -----
        Label title = new Label( ( packName != null && !packName.isBlank() )
                ? packName
                : LocalizationManager.get( "dialog.importConfirm.fallbackName" ) );
        title.getStyleClass().add( "heading-h2" );

        StringBuilder subBuilder = new StringBuilder();
        if ( packVersion != null && !packVersion.isBlank() ) subBuilder.append( "Version " ).append( packVersion );
        String mc = ( index.dependencies != null ) ? index.dependencies.get( "minecraft" ) : null;
        if ( mc != null && !mc.isBlank() ) {
            if ( subBuilder.length() > 0 ) subBuilder.append( " · " );
            subBuilder.append( "Minecraft " ).append( mc );
        }
        String forge = ( index.dependencies != null ) ? index.dependencies.get( "forge" ) : null;
        if ( forge != null && !forge.isBlank() ) {
            if ( subBuilder.length() > 0 ) subBuilder.append( " · " );
            subBuilder.append( "Forge " ).append( forge );
        }
        Label subtitle = new Label( subBuilder.toString() );
        subtitle.getStyleClass().add( "subtle" );

        // Count entries by category. The mod list is the headline; configs /
        // resourcepacks / shaderpacks get summarized as a trailing "+N other
        // files" line so the dialog doesn't overflow with low-signal rows.
        int modCount = 0;
        int configCount = 0;
        int rpCount = 0;
        int spCount = 0;
        int otherCount = 0;
        long totalBytes = 0;
        if ( index.files != null ) {
            for ( ModrinthIndex.File f : index.files ) {
                if ( f == null || f.path == null ) continue;
                totalBytes += f.fileSize;
                String lower = f.path.toLowerCase( Locale.ROOT ).replace( '\\', '/' );
                if ( lower.startsWith( "mods/" ) ) modCount++;
                else if ( lower.startsWith( "config/" ) ) configCount++;
                else if ( lower.startsWith( "resourcepacks/" ) ) rpCount++;
                else if ( lower.startsWith( "shaderpacks/" ) ) spCount++;
                else otherCount++;
            }
        }

        Label totals = new Label( buildTotalsText( modCount, configCount, rpCount, spCount, otherCount, totalBytes ) );
        totals.setStyle( "-fx-font-size: 12px;" );

        // ----- Mod list (scrollable) -----
        VBox modListContent = new VBox( 2 );
        modListContent.setPadding( new Insets( 4, 8, 4, 8 ) );
        boolean anyShown = false;
        if ( index.files != null ) {
            for ( ModrinthIndex.File f : index.files ) {
                if ( f == null || f.path == null ) continue;
                String lower = f.path.toLowerCase( Locale.ROOT ).replace( '\\', '/' );
                if ( !lower.startsWith( "mods/" ) ) continue;
                String filename = fileBasename( f.path );
                String size = formatBytes( f.fileSize );
                Label row = new Label( ( size == null ? "" : "[" + size + "]  " ) + filename );
                row.setStyle( "-fx-font-size: 11px; -fx-font-family: monospace;" );
                modListContent.getChildren().add( row );
                anyShown = true;
            }
        }
        if ( !anyShown ) {
            Label empty = new Label( "(No mod files listed — pack may be configs/resources only.)" );
            empty.getStyleClass().add( "subtle" );
            empty.setStyle( "-fx-font-size: 11px;" );
            modListContent.getChildren().add( empty );
        }

        ScrollPane modListScroll = new ScrollPane( modListContent );
        modListScroll.setFitToWidth( true );
        modListScroll.setPrefHeight( 280 );
        modListScroll.setMaxHeight( 360 );
        modListScroll.setHbarPolicy( ScrollPane.ScrollBarPolicy.NEVER );

        // ----- Footer: Import / Cancel -----
        final boolean[] confirmed = { false };
        MFXButton importBtn = new MFXButton( "Import" );
        importBtn.getStyleClass().add( "primary" );
        importBtn.setOnAction( e -> {
            confirmed[ 0 ] = true;
            stage.close();
        } );
        MFXButton cancelBtn = new MFXButton( "Cancel" );
        cancelBtn.setOnAction( e -> {
            confirmed[ 0 ] = false;
            stage.close();
        } );

        Region spacer = new Region();
        HBox.setHgrow( spacer, Priority.ALWAYS );
        HBox footer = new HBox( 8, spacer, cancelBtn, importBtn );
        footer.setAlignment( Pos.CENTER_RIGHT );
        footer.setPadding( new Insets( 12, 0, 0, 0 ) );

        Label note = new Label(
                "After confirming, the launcher will download every mod listed above to a freshly-created "
                        + "pack folder. You can cancel a stuck download from the launch progress screen if it "
                        + "doesn't finish in a reasonable time." );
        note.setWrapText( true );
        note.getStyleClass().add( "subtle" );
        note.setStyle( "-fx-font-size: 11px;" );

        VBox root = new VBox( 8, title, subtitle, totals, modListScroll, note, footer );
        root.setPadding( new Insets( 16 ) );
        root.getStyleClass().add( "rootPane" );

        Scene scene = new Scene( root, 600, 520 );
        stage.setScene( scene );

        // Install the active theme on the dialog's own Parent root rather than
        // copy stylesheets off the owner Scene. The launcher attaches theme
        // CSS to the owner's Parent (via MCLauncherGuiWindow), not to its
        // Scene, so inheriting from owner.getScene().getStylesheets() picks
        // up nothing and the dialog renders in JavaFX's stock light gray
        // regardless of the active theme. installCurrentThemeStylesheets
        // also sets a solid root background + scene fill so native-themed
        // dialogs (which use transparent -color-bg for DWM Mica) don't
        // composite to white over the desktop.
        com.micatechnologies.minecraft.launcher.gui.MCLauncherGuiWindow
                .installCurrentThemeStylesheets( scene.getRoot() );

        // Match the OS title-bar appearance (dark vs light chrome) to the
        // active launcher theme. Mirrors the recipe used by the quick-start
        // wizard + GUIUtilities.themeAlertChrome — WindowChromeManager
        // handles per-platform routing (DWM on Windows, NSWindow appearance
        // on macOS, no-op on Linux) and defers via WINDOW_SHOWN if the
        // stage isn't realized yet, so calling pre-showAndWait is safe.
        try {
            String themeName = com.micatechnologies.minecraft.launcher.config.ConfigManager.getTheme();
            boolean lightChrome = com.micatechnologies.minecraft.launcher.consts.ConfigConstants.THEME_LIGHT.equals( themeName )
                    || ( com.micatechnologies.minecraft.launcher.consts.ConfigConstants.THEME_NATIVE.equals( themeName )
                         && !isOsDarkSafe() );
            com.micatechnologies.minecraft.launcher.utilities.WindowChromeManager
                    .applyTitleBarDarkMode( stage, !lightChrome );
        }
        catch ( Throwable ignored ) { /* chrome theming is best-effort */ }

        stage.showAndWait();
        return confirmed[ 0 ];
    }

    /** Defensive wrapper around OsThemeDetector for the native-theme chrome
     *  decision; mirrors the helper pattern used elsewhere in the GUI so a
     *  detector failure on an unusual platform never blocks the dialog. */
    private static boolean isOsDarkSafe()
    {
        return com.micatechnologies.minecraft.launcher.utilities.OsThemeUtilities.isOsDark();
    }

    // ===== helpers =====

    private static String buildTotalsText( int mods, int configs, int rps, int sps, int other, long totalBytes )
    {
        StringBuilder sb = new StringBuilder();
        sb.append( mods ).append( mods == 1 ? " mod" : " mods" );
        if ( configs > 0 ) sb.append( ", " ).append( configs ).append( configs == 1 ? " config" : " configs" );
        if ( rps > 0 )     sb.append( ", " ).append( rps ).append( rps == 1 ? " resource pack" : " resource packs" );
        if ( sps > 0 )     sb.append( ", " ).append( sps ).append( sps == 1 ? " shader pack" : " shader packs" );
        if ( other > 0 )   sb.append( ", " ).append( other ).append( " other file" ).append( other == 1 ? "" : "s" );
        String size = formatBytes( totalBytes );
        if ( size != null ) sb.append( "  ·  " ).append( size ).append( " total" );
        return sb.toString();
    }

    private static String fileBasename( String path )
    {
        if ( path == null ) return "";
        String norm = path.replace( '\\', '/' );
        int slash = norm.lastIndexOf( '/' );
        return slash < 0 ? norm : norm.substring( slash + 1 );
    }

    /** Compact byte size formatter — "12.4 MB", "847 KB", "1.2 GB". Returns
     *  {@code null} for non-positive sizes so the caller skips the "[size]"
     *  prefix entirely rather than show "[0 B]". */
    private static String formatBytes( long bytes )
    {
        if ( bytes <= 0 ) return null;
        if ( bytes < 1024 ) return bytes + " B";
        double kb = bytes / 1024.0;
        if ( kb < 1024 ) return String.format( "%.0f KB", kb );
        double mb = kb / 1024.0;
        if ( mb < 1024 ) return String.format( "%.1f MB", mb );
        double gb = mb / 1024.0;
        return String.format( "%.2f GB", gb );
    }
}
