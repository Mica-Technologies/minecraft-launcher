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
import io.github.palexdev.materialfx.controls.MFXButton;
import io.github.palexdev.materialfx.controls.MFXToggleButton;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import oshi.SystemInfo;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * First-launch quick-start wizard. A small multi-step modal Stage that walks
 * a new user through picking a theme, allocating RAM, and toggling a few
 * common preferences before they land on the main menu for the first time.
 *
 * <p>Tracked via {@link ConfigConstants#QUICK_START_COMPLETED_KEY} in the
 * launcher config — defaults to false, so existing installs see the wizard
 * exactly once when they upgrade to a version that ships it. The flag flips
 * true when the user either Finishes or Skips, so subsequent launches go
 * straight to the main menu without re-prompting.
 *
 * <p>Steps (in order):
 * <ol>
 *   <li><b>Welcome</b> — branding + intro copy. No interaction beyond Next.</li>
 *   <li><b>Theme</b> — toggle-button grid of every available theme, each
 *       with a small color-swatch preview. Selecting a theme immediately
 *       applies it via {@link MCLauncherGuiController#forceThemeRefresh()}
 *       so the user sees the change live in the wizard itself.</li>
 *   <li><b>RAM</b> — slider with the launcher's MAX RAM range, defaulting
 *       to a recommended value computed from the host's total memory:
 *       50% of system RAM, capped at 16 GB. Recommendation is shown
 *       inline so the user understands the heuristic.</li>
 *   <li><b>Preferences</b> — opt-in toggles for Discord rich presence and
 *       launcher update checks. Both default ON because those are the
 *       intended behaviors for a fresh install.</li>
 *   <li><b>Done</b> — brief recap of what was set + the "Get Started"
 *       button that closes the wizard.</li>
 * </ol>
 *
 * <p>Navigation: persistent Back / Next buttons at the bottom (Next becomes
 * "Get Started" on the final step), plus a Skip link in the top-right that
 * dismisses the wizard with whatever defaults were in place. Settings are
 * written to {@link ConfigManager} as the user advances — closing the
 * launcher mid-wizard keeps the partial selections.
 *
 * @since 3.4
 */
public final class MCLauncherQuickStartWizard
{
    /** Theme catalog rendered on the Theme step. Insertion-order map so the
     *  buttons appear in a deliberate order (Native first since that's the
     *  Mica-aligned default), with the swatch color used as a visual preview
     *  next to each theme label. */
    private static final Map< String, Color > THEME_SWATCHES;
    static {
        THEME_SWATCHES = new LinkedHashMap<>();
        THEME_SWATCHES.put( ConfigConstants.THEME_NATIVE,        Color.web( "#6FCF3D" ) );
        THEME_SWATCHES.put( ConfigConstants.THEME_DARK,          Color.web( "#6FCF3D" ) );
        THEME_SWATCHES.put( ConfigConstants.THEME_LIGHT,         Color.web( "#3C8527" ) );
        THEME_SWATCHES.put( ConfigConstants.THEME_BLUE_GRAY,     Color.web( "#0668E1" ) );
        THEME_SWATCHES.put( ConfigConstants.THEME_ORANGE_PURPLE, Color.web( "#D257DB" ) );
        THEME_SWATCHES.put( ConfigConstants.THEME_CREEPER,       Color.web( "#43D22D" ) );
        THEME_SWATCHES.put( ConfigConstants.THEME_AUTOMATIC,     Color.web( "#888888" ) );
    }

    /** Hard ceiling on the RAM recommendation. Even on a 128 GB workstation,
     *  Minecraft Java doesn't benefit from more than ~16 GB heap — beyond that
     *  the GC pause time outweighs the headroom. Users who really want more
     *  can drag the slider past it; this only caps the recommendation. */
    private static final int RAM_RECOMMENDATION_MAX_GB = 16;

    /** Minimum slider value (matches the Settings page floor). The launcher
     *  needs at least 2 GB for modern Forge packs to even start. */
    private static final int RAM_SLIDER_MIN_GB = 2;

    /** Slider ceiling — high enough that no user hits it in practice but
     *  capped so an overzealous drag doesn't allocate gigabytes the host
     *  doesn't have. */
    private static final int RAM_SLIDER_MAX_GB = 64;

    /** The modal stage hosting the wizard. Created in {@link #buildStage(Stage)}.
     *
     * @since 3.4 */
    private Stage stage;

    /** Stacked host for the step panels — all steps are layered here and toggled
     *  visible/managed one at a time as the user navigates.
     *
     * @since 3.4 */
    private StackPane stepContainer;

    /** The Back navigation button, disabled on the first step.
     *
     * @since 3.4 */
    private MFXButton backBtn;

    /** The Next navigation button, relabeled "Get Started" on the final step.
     *
     * @since 3.4 */
    private MFXButton nextBtn;

    /** The "step X of N" progress label in the navigation bar.
     *
     * @since 3.4 */
    private Label progressIndicator;

    /** Zero-based index of the currently visible step.
     *
     * @since 3.4 */
    private int currentStep = 0;

    /** The pre-built step panels, in display order (Welcome, Theme, RAM,
     *  Preferences, Done). Built once up front so navigating Back retains each
     *  step's intermediate state.
     *
     * @since 3.4 */
    private VBox[] steps;

    /**
     * Opens the quick-start wizard as a modal stage owned by {@code owner}.
     * Blocks the owner until the user closes the wizard. On close — whether
     * via Finish, Skip, or the OS close button — the quick-start-completed
     * flag is persisted to config.
     *
     * @param owner the parent stage (typically the main GUI's stage)
     */
    public static void show( Stage owner )
    {
        new MCLauncherQuickStartWizard().display( owner );
    }

    /**
     * Builds the wizard stage on the JavaFX application thread and shows it
     * modally, blocking the calling flow until the wizard is dismissed.
     *
     * @param owner the parent stage, or {@code null} for an unowned modal
     *
     * @since 3.4
     */
    private void display( Stage owner )
    {
        GUIUtilities.JFXPlatformRun( () -> {
            buildStage( owner );
            stage.showAndWait();
        } );
    }

    // =============================================================================
    //  Stage assembly
    // =============================================================================

    /**
     * Assembles the entire wizard window: the modal stage and its icon/close
     * handling, all five step panels, the stacked step host, and the bottom
     * navigation bar (Skip / Back / Next plus the progress indicator). Installs
     * the active launcher theme stylesheets and matches the OS title-bar
     * appearance to that theme, then shows the first step. Must run on the
     * JavaFX application thread.
     *
     * @param owner the parent stage to own this modal, or {@code null} for none
     *
     * @since 3.4
     */
    private void buildStage( Stage owner )
    {
        stage = new Stage();
        stage.setTitle( LocalizationManager.get( "window.welcome.title" ) );
        stage.initStyle( StageStyle.UNIFIED );
        stage.initModality( Modality.WINDOW_MODAL );
        if ( owner != null ) {
            stage.initOwner( owner );
        }

        // Reuse the launcher's app icon so the wizard's taskbar entry matches
        // the rest of the launcher chrome.
        try ( InputStream iconStream = getClass().getClassLoader()
                                                 .getResourceAsStream( "micaminecraftlauncher.png" ) ) {
            if ( iconStream != null ) {
                stage.getIcons().add( new javafx.scene.image.Image( iconStream ) );
            }
        }
        catch ( Exception ignored ) { /* non-fatal — chrome falls back to default icon */ }

        // Treat the OS close button (X) the same as Skip — mark the wizard
        // completed so the user doesn't get re-prompted on next launch.
        stage.setOnCloseRequest( e -> {
            ConfigManager.setQuickStartCompleted( true );
        } );

        // Build the step nodes once up front, then swap their visibility as the
        // user navigates. Cheaper than rebuilding on every step change and lets
        // each step retain its own intermediate state if the user clicks Back.
        steps = new VBox[] {
                buildWelcomeStep(),
                buildThemeStep(),
                buildRamStep(),
                buildPreferencesStep(),
                buildDoneStep()
        };

        stepContainer = new StackPane();
        stepContainer.getStyleClass().add( "wizardStepHost" );
        for ( VBox step : steps ) {
            step.setVisible( false );
            step.setManaged( false );
            stepContainer.getChildren().add( step );
        }
        VBox.setVgrow( stepContainer, javafx.scene.layout.Priority.ALWAYS );

        // Navigation row pinned at the bottom of the wizard window.
        progressIndicator = new Label();
        progressIndicator.getStyleClass().add( "wizardProgress" );

        backBtn = new MFXButton( LocalizationManager.get( "quickStart.backBtn" ) );
        backBtn.setOnAction( e -> goToStep( currentStep - 1 ) );
        backBtn.setPrefWidth( 90 );

        nextBtn = new MFXButton( LocalizationManager.get( "quickStart.nextBtn.next" ) );
        nextBtn.getStyleClass().add( "primary" );
        nextBtn.setPrefWidth( 140 );
        nextBtn.setOnAction( e -> {
            if ( currentStep == steps.length - 1 ) {
                finish();
            }
            else {
                goToStep( currentStep + 1 );
            }
        } );

        MFXButton skipBtn = new MFXButton( LocalizationManager.get( "quickStart.skipBtn" ) );
        skipBtn.getStyleClass().add( "wizardSkipBtn" );
        skipBtn.setOnAction( e -> finish() );

        Pane navSpacer = new Pane();
        HBox.setHgrow( navSpacer, javafx.scene.layout.Priority.ALWAYS );

        HBox navBar = new HBox( 8, progressIndicator, navSpacer, skipBtn, backBtn, nextBtn );
        navBar.setAlignment( Pos.CENTER_LEFT );
        navBar.setPadding( new Insets( 12, 18, 14, 18 ) );
        navBar.getStyleClass().add( "wizardNavBar" );

        VBox root = new VBox( stepContainer, navBar );
        root.getStyleClass().add( "wizardRoot" );

        Scene scene = new Scene( root, 620, 540 );
        // Reuse the same theme stylesheet stack the main launcher uses so the
        // wizard inherits whichever theme the user picks live. Pulling from
        // MCLauncherGuiWindow keeps the resolution logic in one place.
        com.micatechnologies.minecraft.launcher.gui.MCLauncherGuiWindow
                .installCurrentThemeStylesheets( scene.getRoot() );
        stage.setScene( scene );
        stage.setResizable( false );

        // Match the OS title-bar appearance to the active launcher theme — same
        // path the main launcher uses post-setScene. WindowChromeManager handles
        // the per-platform routing (DWM on Windows, NSWindow setAppearance on
        // macOS, no-op on Linux); applyTitleBarAppearance defers via
        // WINDOW_SHOWN if the stage isn't realized yet, so calling here
        // pre-showAndWait is safe.
        boolean lightChrome = GUIUtilities.isLightChrome( ConfigManager.getTheme() );
        com.micatechnologies.minecraft.launcher.utilities.WindowChromeManager
                .applyTitleBarDarkMode( stage, !lightChrome );

        goToStep( 0 );
    }

    /**
     * Navigates to the step at the given index, toggling panel visibility,
     * syncing the Back button's enabled state, relabeling Next ("Get Started"
     * on the last step), and updating the "step X of N" progress indicator.
     * Out-of-range indices are ignored, which makes Back on step 0 and Next on
     * the final step safe no-ops at the bounds.
     *
     * @param idx the zero-based index of the step to show
     *
     * @since 3.4
     */
    private void goToStep( int idx )
    {
        if ( idx < 0 || idx >= steps.length ) return;
        for ( int i = 0; i < steps.length; i++ ) {
            boolean show = ( i == idx );
            steps[ i ].setVisible( show );
            steps[ i ].setManaged( show );
        }
        currentStep = idx;
        backBtn.setDisable( idx == 0 );
        nextBtn.setText( com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager.get(
                idx == steps.length - 1 ? "quickStart.nextBtn.getStarted" : "quickStart.nextBtn.next" ) );
        progressIndicator.setText( com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager.format(
                "quickStart.progressIndicator", idx + 1, steps.length ) );
    }

    /**
     * Marks the quick-start wizard as completed in config and closes the stage.
     * Invoked by both the Skip link and the final-step "Get Started" button so
     * either path persists the completed flag and the wizard never re-prompts.
     *
     * @since 3.4
     */
    private void finish()
    {
        ConfigManager.setQuickStartCompleted( true );
        if ( stage != null ) {
            stage.close();
        }
    }

    // =============================================================================
    //  Step builders
    // =============================================================================

    /**
     * Builds the Welcome step: branding heading, subtitle, and an intro hint.
     * Purely informational — the user just clicks Next.
     *
     * @return the assembled Welcome step panel
     *
     * @since 3.4
     */
    private VBox buildWelcomeStep()
    {
        VBox step = baseStep();

        Label title = new Label( LocalizationManager.get( "quickStart.welcome.heading" ) );
        title.getStyleClass().add( "wizardHeading" );
        title.setWrapText( true );

        Label subtitle = new Label( LocalizationManager.get( "quickStart.welcome.subtitle" ) );
        subtitle.getStyleClass().add( "wizardSubtitle" );
        subtitle.setWrapText( true );

        Label hint = new Label( LocalizationManager.get( "quickStart.welcome.hint" ) );
        hint.getStyleClass().add( "wizardBody" );
        hint.setWrapText( true );

        step.getChildren().addAll( title, subtitle, spacer( 18 ), hint );
        return step;
    }

    /**
     * Builds the Theme step: a flow-pane grid of mutually-exclusive toggle
     * buttons, one per entry in {@link #THEME_SWATCHES}, each showing a color
     * swatch preview. The button matching the current config theme starts
     * selected; selecting one persists it via {@link ConfigManager#setTheme}
     * and applies it live (refreshing both the main GUI and the wizard's own
     * stylesheets) so the change is visible immediately. Deselect is disallowed
     * so exactly one theme is always chosen.
     *
     * @return the assembled Theme step panel
     *
     * @since 3.4
     */
    private VBox buildThemeStep()
    {
        VBox step = baseStep();

        Label title = new Label( LocalizationManager.get( "quickStart.theme.heading" ) );
        title.getStyleClass().add( "wizardHeading" );

        Label subtitle = new Label( LocalizationManager.get( "quickStart.theme.subtitle" ) );
        subtitle.getStyleClass().add( "wizardSubtitle" );
        subtitle.setWrapText( true );

        ToggleGroup group = new ToggleGroup();
        String currentTheme = ConfigManager.getTheme();
        javafx.scene.layout.FlowPane themes = new javafx.scene.layout.FlowPane( 10, 10 );
        themes.setAlignment( Pos.TOP_LEFT );
        for ( Map.Entry< String, Color > entry : THEME_SWATCHES.entrySet() ) {
            ToggleButton tb = new ToggleButton( entry.getKey() );
            tb.getStyleClass().add( "wizardThemeOption" );
            tb.setToggleGroup( group );
            tb.setUserData( entry.getKey() );
            tb.setMinWidth( 170 );
            tb.setPrefWidth( 170 );

            // Color swatch as the toggle's graphic — small rounded square in the
            // theme's primary color so the user can scan options visually.
            Region swatch = new Region();
            swatch.setMinSize( 14, 14 );
            swatch.setMaxSize( 14, 14 );
            swatch.setStyle( "-fx-background-color: " + toCssColor( entry.getValue() )
                                     + "; -fx-background-radius: 4;" );
            tb.setGraphic( swatch );
            tb.setContentDisplay( javafx.scene.control.ContentDisplay.LEFT );

            if ( entry.getKey().equals( currentTheme ) ) {
                tb.setSelected( true );
            }
            tb.setOnAction( e -> {
                if ( !tb.isSelected() ) {
                    // Don't allow deselect to "none" — re-select to keep at least
                    // one theme chosen at all times.
                    tb.setSelected( true );
                    return;
                }
                ConfigManager.setTheme( entry.getKey() );
                MCLauncherGuiController.forceThemeRefresh();
                // Re-install the theme stylesheets on the wizard's own root so
                // the wizard reflects the live theme change immediately.
                com.micatechnologies.minecraft.launcher.gui.MCLauncherGuiWindow
                        .installCurrentThemeStylesheets( stage.getScene().getRoot() );
            } );
            themes.getChildren().add( tb );
        }

        step.getChildren().addAll( title, subtitle, spacer( 14 ), themes );
        return step;
    }

    /**
     * Builds the RAM step: a snap-to-tick slider over the launcher's allowed
     * heap range, seeded with the recommended allocation (see
     * {@link #computeRecommendedRamGb(long)}) when the config still holds the
     * default, or the user's existing allocation otherwise. An inline
     * recommendation line explains the heuristic. The slider persists its value
     * to {@link ConfigManager#setMaxRam} only on actual user interaction, never
     * at build time, so merely opening (or skipping) the wizard does not
     * overwrite the user's existing RAM setting.
     *
     * @return the assembled RAM step panel
     *
     * @since 3.4
     */
    private VBox buildRamStep()
    {
        VBox step = baseStep();

        Label title = new Label( LocalizationManager.get( "quickStart.ram.heading" ) );
        title.getStyleClass().add( "wizardHeading" );

        long systemRamGb = detectSystemRamGb();
        int recommendedGb = computeRecommendedRamGb( systemRamGb );
        long currentRamMb = ConfigManager.getMaxRam();
        int currentGb = Math.max( RAM_SLIDER_MIN_GB,
                                  Math.min( RAM_SLIDER_MAX_GB, ( int ) ( currentRamMb / 1024 ) ) );
        int initialGb = ( currentRamMb == ConfigConstants.MAX_RAM_MEGABYTES_DEFAULT )
                ? recommendedGb : currentGb;

        Label subtitle = new Label( LocalizationManager.get( "quickStart.ram.subtitle" ) );
        subtitle.getStyleClass().add( "wizardSubtitle" );
        subtitle.setWrapText( true );

        Label recommendation = new Label( LocalizationManager.format(
                "quickStart.ram.recommendation",
                systemRamGb, recommendedGb, RAM_RECOMMENDATION_MAX_GB ) );
        recommendation.getStyleClass().add( "wizardBody" );
        recommendation.setWrapText( true );

        Slider slider = new Slider( RAM_SLIDER_MIN_GB, RAM_SLIDER_MAX_GB, initialGb );
        slider.setMajorTickUnit( 4 );
        slider.setMinorTickCount( 3 );
        slider.setShowTickLabels( true );
        slider.setShowTickMarks( true );
        slider.setSnapToTicks( true );
        slider.setBlockIncrement( 1 );
        slider.setPrefWidth( 460 );

        Label currentValue = new Label();
        currentValue.getStyleClass().add( "wizardRamValue" );

        Runnable updateLabel = () -> {
            int gb = ( int ) Math.round( slider.getValue() );
            currentValue.setText( LocalizationManager.format( "quickStart.ram.value", gb ) );
        };
        // Persist only on actual user interaction, not at build time. Every wizard step is
        // built eagerly in buildStage(), so writing config from the initial updateLabel.run()
        // overwrote the user's existing RAM allocation just by opening the wizard -- even if
        // they Skip without ever visiting this step. This mirrors the other steps (e.g. the
        // Discord toggle), which persist only when the control actually changes.
        slider.valueProperty().addListener( ( obs, oldV, newV ) -> {
            updateLabel.run();
            ConfigManager.setMaxRam( ( int ) Math.round( slider.getValue() ) * 1024L );
        } );
        updateLabel.run();

        step.getChildren().addAll(
                title, subtitle, spacer( 8 ), recommendation, spacer( 8 ), slider, currentValue );
        return step;
    }

    /**
     * Builds the Preferences step: two opt-in toggles — Discord rich presence
     * and launcher update checks — each seeded from current config and writing
     * back via {@link ConfigManager} only when toggled. Both default ON for a
     * fresh install.
     *
     * @return the assembled Preferences step panel
     *
     * @since 3.4
     */
    private VBox buildPreferencesStep()
    {
        VBox step = baseStep();

        Label title = new Label( LocalizationManager.get( "quickStart.preferences.heading" ) );
        title.getStyleClass().add( "wizardHeading" );

        Label subtitle = new Label( LocalizationManager.get( "quickStart.preferences.subtitle" ) );
        subtitle.getStyleClass().add( "wizardSubtitle" );
        subtitle.setWrapText( true );

        MFXToggleButton discord = new MFXToggleButton(
                LocalizationManager.get( "quickStart.preferences.discord" ) );
        discord.setSelected( ConfigManager.getDiscordRpcEnable() );
        discord.selectedProperty().addListener( ( obs, oldV, newV ) ->
                ConfigManager.setDiscordRpcEnable( newV ) );
        Label discordHint = new Label( LocalizationManager.get( "quickStart.preferences.discordHint" ) );
        discordHint.getStyleClass().add( "wizardHint" );
        discordHint.setWrapText( true );

        MFXToggleButton updates = new MFXToggleButton(
                LocalizationManager.get( "quickStart.preferences.updates" ) );
        updates.setSelected( ConfigManager.getLauncherUpdateCheckEnabled() );
        updates.selectedProperty().addListener( ( obs, oldV, newV ) ->
                ConfigManager.setLauncherUpdateCheckEnabled( newV ) );
        Label updatesHint = new Label( LocalizationManager.get( "quickStart.preferences.updatesHint" ) );
        updatesHint.getStyleClass().add( "wizardHint" );
        updatesHint.setWrapText( true );

        step.getChildren().addAll(
                title, subtitle, spacer( 10 ),
                discord, discordHint, spacer( 6 ),
                updates, updatesHint );
        return step;
    }

    /**
     * Builds the Done step: a closing heading, subtitle, and a recap of what was
     * configured. The navigation bar's Next button reads "Get Started" here and
     * closes the wizard.
     *
     * @return the assembled Done step panel
     *
     * @since 3.4
     */
    private VBox buildDoneStep()
    {
        VBox step = baseStep();

        Label title = new Label( LocalizationManager.get( "quickStart.done.heading" ) );
        title.getStyleClass().add( "wizardHeading" );

        Label subtitle = new Label( LocalizationManager.get( "quickStart.done.subtitle" ) );
        subtitle.getStyleClass().add( "wizardSubtitle" );
        subtitle.setWrapText( true );

        Label recap = new Label( LocalizationManager.get( "quickStart.done.recap" ) );
        recap.getStyleClass().add( "wizardBody" );
        recap.setWrapText( true );

        step.getChildren().addAll( title, subtitle, spacer( 18 ), recap );
        return step;
    }

    // =============================================================================
    //  Small builders + helpers
    // =============================================================================

    /**
     * Creates an empty step panel with the wizard's shared spacing, padding, and
     * style class. Each step builder starts from one of these and appends its
     * own content.
     *
     * @return a styled, empty {@link VBox} ready to receive step content
     *
     * @since 3.4
     */
    private VBox baseStep()
    {
        VBox step = new VBox( 12 );
        step.setPadding( new Insets( 28, 36, 24, 36 ) );
        step.getStyleClass().add( "wizardStep" );
        return step;
    }

    /**
     * Creates a fixed-height vertical spacer region for wizard step layouts.
     *
     * @param h the exact height in pixels (min, pref, and max are all pinned to it)
     *
     * @return a {@link Region} fixed to the given height
     */
    private static Region spacer( double h )
    {
        Region r = new Region();
        r.setMinHeight( h );
        r.setMaxHeight( h );
        r.setPrefHeight( h );
        return r;
    }

    /**
     * Formats a JavaFX {@link Color} as a {@code #RRGGBB} hex string for use in
     * inline CSS.
     *
     * @param c the color to format (alpha is ignored)
     *
     * @return the {@code #RRGGBB} hex representation
     */
    private static String toCssColor( Color c )
    {
        return String.format( "#%02X%02X%02X",
                              ( int ) Math.round( c.getRed()   * 255 ),
                              ( int ) Math.round( c.getGreen() * 255 ),
                              ( int ) Math.round( c.getBlue()  * 255 ) );
    }

    /** System RAM in GB via OSHI. Falls back to 8 if detection fails so the
     *  recommendation logic always produces a sane number. */
    private static long detectSystemRamGb()
    {
        try {
            long bytes = new SystemInfo().getHardware().getMemory().getTotal();
            return Math.max( 1L, bytes / ( 1024L * 1024L * 1024L ) );
        }
        catch ( Throwable t ) {
            Logger.logWarningSilent(
                    "Couldn't detect system RAM for quick-start wizard: " + t.getMessage() );
            return 8L;
        }
    }

    /** Recommended Minecraft RAM allocation: 50% of system RAM, capped at the
     *  {@link #RAM_RECOMMENDATION_MAX_GB} ceiling and floored at the slider
     *  minimum. Half of system RAM is a common heuristic that leaves room for
     *  the OS + browser; the 16 GB cap reflects diminishing returns on the
     *  JVM heap beyond that point. */
    private static int computeRecommendedRamGb( long systemRamGb )
    {
        int half = ( int ) ( systemRamGb / 2 );
        int capped = Math.min( RAM_RECOMMENDATION_MAX_GB, half );
        return Math.max( RAM_SLIDER_MIN_GB, capped );
    }
}
