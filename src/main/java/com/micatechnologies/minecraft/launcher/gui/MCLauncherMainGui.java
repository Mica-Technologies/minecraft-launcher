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
import javafx.application.Platform;
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
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.RowConstraints;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import javafx.stage.Stage;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
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
    @SuppressWarnings( "unused" ) @FXML MFXButton editButton;
    @SuppressWarnings( "unused" ) @FXML MFXButton vanillaBtn;
    @SuppressWarnings( "unused" ) @FXML MFXButton settingsBtn;
    @SuppressWarnings( "unused" ) @FXML Label helpBtn;
    @SuppressWarnings( "unused" ) @FXML ImageView userImage;
    @SuppressWarnings( "unused" ) @FXML ImageView updateImgView;
    @SuppressWarnings( "unused" ) @FXML Label playerLabel;
    @SuppressWarnings( "unused" ) @FXML Label announcement;
    @SuppressWarnings( "unused" ) @FXML RowConstraints announcementRow;

    // ===== Center: scrolling card list =====
    @SuppressWarnings( "unused" ) @FXML ScrollPane modpackScrollPane;
    @SuppressWarnings( "unused" ) @FXML FlowPane modpackCardList;

    // ===== Bottom status bar =====
    @SuppressWarnings( "unused" ) @FXML Label versionLabel;
    @SuppressWarnings( "unused" ) @FXML Label offlineLabel;
    @SuppressWarnings( "unused" ) @FXML MFXButton exitBtn;

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

        editButton.setDisable( AnnouncementManager.getDisableModpacksEdit() );
        editButton.setOnAction( actionEvent -> SystemUtilities.spawnNewTask( () -> {
            try {
                MCLauncherGuiController.goToEditModpacksGui();
                SystemUtilities.spawnNewTask( () -> DiscordRpcUtility.setMenuPresence( "Editing Mod Packs" ) );
            }
            catch ( IOException e ) {
                Logger.logError( "Unable to load edit mod-packs GUI due to an incomplete response from the GUI subsystem." );
                Logger.logThrowable( e );
            }
        } ) );

        vanillaBtn.setOnAction( actionEvent -> SystemUtilities.spawnNewTask( () -> {
            try {
                MCLauncherGuiController.goToVanillaVersionsGui();
            }
            catch ( IOException e ) {
                Logger.logError( "Unable to open vanilla versions GUI." );
                Logger.logThrowable( e );
            }
        } ) );

        helpBtn.setOnMouseClicked( e -> MCLauncherHelpWindow.show( getHelpTopic() ) );
        helpBtn.setCursor( Cursor.HAND );

        playerLabel.setText( MCLauncherAuthManager.getLoggedInUser().name() );
        versionLabel.setText( "Mica Launcher v" + LauncherConstants.LAUNCHER_APPLICATION_VERSION );

        if ( NetworkUtilities.isOffline() ) {
            offlineLabel.setVisible( true );
            offlineLabel.setManaged( true );
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
                SystemUtilities.spawnNewTask( () -> {
                    AnnouncementManager.checkAnnouncements();
                    GameModPackManager.fetchModPackInfo();
                    try {
                        MCLauncherGuiController.goToMainGui();
                    }
                    catch ( Exception e ) {
                        Logger.logError( "Oops! Unable to refresh." );
                        Logger.logThrowable( e );
                    }
                } );
            }
        } );

        populateModpackCards();
    }

    @Override
    void afterShow() {
        TooltipManager.install( settingsBtn, "Open launcher settings (RAM, theme, JVM flags, proxy)." );
        TooltipManager.install( vanillaBtn, "Browse and play vanilla (unmodded) Minecraft versions." );
        TooltipManager.install( editButton, "Add, remove, or edit installed mod packs." );
        TooltipManager.install( helpBtn, "Open the help window for this screen." );
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

        if ( !announcementText.isEmpty() ) {
            announcement.setText( announcementText );
            // Use explicit fixed sizes — USE_COMPUTED_SIZE was not consistently resolving across
            // navigation transitions (banner shows on first load, vanishes after settings → main).
            // A multi-line message wraps within the Label; the row caps at 80 to keep the navbar
            // layout from collapsing.
            boolean multiLine = announcementText.contains( "\n" );
            double targetHeight = multiLine ? 60 : 40;
            announcement.setMinHeight( targetHeight );
            announcement.setPrefHeight( targetHeight );
            announcement.setMaxHeight( targetHeight );
            announcementRow.setMinHeight( targetHeight );
            announcementRow.setPrefHeight( targetHeight );
            announcementRow.setMaxHeight( targetHeight );
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
    }

    /**
     * Builds the combined list (Forge modpacks + installed vanilla versions) and instantiates a
     * {@link ModpackHeroCard} for each, placing them in the scrolling card list. Last-played pack is
     * floated to the top so the user lands on what they most likely want next.
     */
    private void populateModpackCards()
    {
        modpackCardList.getChildren().clear();

        List< GameModPack > allPacks = new ArrayList<>( GameModPackManager.getInstalledModPacks() );
        for ( String versionId :
                com.micatechnologies.minecraft.launcher.game.modpack.VanillaVersionManager.getInstalledVersionIds() ) {
            allPacks.add( GameModPack.createVanillaModPack( versionId ) );
        }

        if ( allPacks.isEmpty() ) {
            modpackCardList.getChildren().add( buildEmptyState() );
            return;
        }

        // Floa the last-played pack to the top.
        String last = ConfigManager.getLastModPackSelected();
        if ( last != null && !last.isBlank() ) {
            for ( int i = 0; i < allPacks.size(); i++ ) {
                GameModPack p = allPacks.get( i );
                if ( last.equals( p.getPackName() ) || last.equals( p.getFriendlyName() ) ) {
                    allPacks.add( 0, allPacks.remove( i ) );
                    break;
                }
            }
        }

        for ( GameModPack pack : allPacks ) {
            modpackCardList.getChildren().add( new ModpackHeroCard( pack ) );
        }
    }

    /** Empty-state placeholder shown when there are no packs installed yet. */
    private Node buildEmptyState()
    {
        VBox box = new VBox( 12 );
        box.setAlignment( Pos.CENTER );
        box.setPrefHeight( 320 );

        Label heading = new Label( "No mod packs installed yet" );
        heading.getStyleClass().add( "heading-h1" );

        Label sub = new Label( "Click \"Edit Packs\" in the top bar to add a modpack from a URL." );
        sub.getStyleClass().add( "muted" );

        box.getChildren().addAll( heading, sub );
        return box;
    }

    /**
     * Scrolls the matching pack card into view (if found). Used by the CLI launch path
     * ({@link com.micatechnologies.minecraft.launcher.LauncherCore#main} when invoked with a modpack argument).
     * No-op if the requested pack isn't present in the list.
     */
    public void selectModpack( GameModPack modPack ) {
        if ( modPack == null ) return;
        GUIUtilities.JFXPlatformRun( () -> {
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

            Region bgLayer = new Region();
            bgLayer.getStyleClass().add( "heroBackground" );
            String bgUrl = resolveBackgroundUrl( pack );
            if ( bgUrl != null ) {
                bgLayer.setStyle( "-fx-background-image: url('" + bgUrl + "');" );
            }

            // Subtle veil along the bottom of the image so the logo reads against it.
            Region imageVeil = new Region();
            imageVeil.getStyleClass().add( "heroCardImageVeil" );

            // Optional badge row floats over the top-right of the image.
            HBox badgeRow = new HBox( 6 );
            badgeRow.setAlignment( Pos.TOP_RIGHT );
            badgeRow.setPadding( new javafx.geometry.Insets( 10, 12, 0, 0 ) );
            if ( pack.getPackUnstable() ) badgeRow.getChildren().add( buildChip( "Beta", "stat-chip-warn" ) );
            if ( pack.isUpdateAvailable() ) badgeRow.getChildren().add( buildChip( "Update", "stat-chip-success" ) );

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
            logo.setImage( resolveLogoImage( pack ) );
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

            Label name = new Label( resolveDisplayName( pack ) );
            name.getStyleClass().addAll( "heading-h2", "heroCardTitle" );
            name.setWrapText( true );

            HBox chips = new HBox( 6 );
            chips.setAlignment( Pos.CENTER_LEFT );
            String mc = safeMinecraftVersion( pack );
            String forge = safeForgeVersion( pack );
            if ( mc != null && !mc.isBlank() ) chips.getChildren().add( buildChip( "MC " + mc ) );
            if ( forge != null && !forge.isBlank() ) {
                String shortForge = forge.contains( "-" ) ? forge.substring( forge.lastIndexOf( '-' ) + 1 ) : forge;
                chips.getChildren().add( buildChip( "Forge " + shortForge ) );
            }
            if ( pack.getPackVersion() != null && !pack.getPackVersion().isBlank() )
                chips.getChildren().add( buildChip( "v" + pack.getPackVersion() ) );

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

            playBtn = new MFXButton( "Play" );
            playBtn.getStyleClass().addAll( "primary", "heroPlayBtn" );
            playBtn.setPrefHeight( 38 );
            playBtn.setMaxWidth( Double.MAX_VALUE );
            HBox.setHgrow( playBtn, Priority.ALWAYS );
            playBtn.setDisable( AnnouncementManager.getDisableGameplay() );
            playBtn.setOnAction( e -> startPlay( pack ) );

            MFXButton websiteBtn = new MFXButton( "Website" );
            websiteBtn.getStyleClass().add( "heroCardSecondaryBtn" );
            websiteBtn.setPrefHeight( 38 );
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
            setOnMouseClicked( ev -> {
                // Double-click anywhere on the card (other than buttons) plays the pack — quick-launch UX.
                if ( ev.getButton() == MouseButton.PRIMARY && ev.getClickCount() == 2 && !playBtn.isDisabled() ) {
                    playBtn.fire();
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
                SystemUtilities.spawnNewTask( () ->
                    DiscordRpcUtility.setGamePresence( pack.getPackName(), pack.getCustomDiscordRpc() ) );
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
        String name = pack.getFriendlyName();
        if ( name == null || name.isBlank() ) name = pack.getPackName();
        return name != null ? name : "Unnamed Pack";
    }

    private static String resolveBackgroundUrl( GameModPack pack ) {
        try {
            String path = pack.getPackBackgroundFilepath();
            if ( path != null ) {
                File f = new File( path );
                if ( f.exists() ) return f.toURI().toString();
            }
        }
        catch ( Exception ignored ) { /* fall through */ }
        return ModPackConstants.MODPACK_DEFAULT_BG_URL;
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

        openFolder.setOnAction(       e -> openPackSubfolder( pack, "" ) );
        openScreenshots.setOnAction(  e -> openPackSubfolder( pack, "screenshots" ) );
        openResourcePks.setOnAction(  e -> openPackSubfolder( pack, "resourcepacks" ) );
        openShaderPacks.setOnAction(  e -> openPackSubfolder( pack, "shaderpacks" ) );
        openMods.setOnAction(         e -> openPackSubfolder( pack, "mods" ) );
        openConfig.setOnAction(       e -> openPackSubfolder( pack, "config" ) );
        createShortcut.setOnAction(   e -> createDesktopShortcut( pack ) );

        menu.getItems().addAll( playStats, new SeparatorMenuItem(),
                                openFolder, new SeparatorMenuItem(),
                                openScreenshots, openResourcePks, openShaderPacks,
                                new SeparatorMenuItem(), openMods, openConfig,
                                new SeparatorMenuItem(), createShortcut );
        return menu;
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
