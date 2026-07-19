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
import com.micatechnologies.minecraft.launcher.consts.ModPackConstants;
import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.game.modpack.GameModPack;
import com.micatechnologies.minecraft.launcher.game.modpack.ModPackAuditLog;
import com.micatechnologies.minecraft.launcher.game.modpack.ModPackUpdateLog;
import com.micatechnologies.minecraft.launcher.system.DesktopShortcutManager;
import com.micatechnologies.minecraft.launcher.utilities.AnnouncementManager;
import com.micatechnologies.minecraft.launcher.utilities.DiscordRpcUtility;
import com.micatechnologies.minecraft.launcher.utilities.NotificationManager;
import com.micatechnologies.minecraft.launcher.utilities.SystemUtilities;
import io.github.palexdev.materialfx.controls.MFXButton;
import javafx.animation.AnimationTimer;
import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * In-scene modal overlay that presents an expanded view of a single mod pack. Triggered
 * by a single-click on a {@code ModpackHeroCard} in the main menu — the card list is
 * dense by design (~360×320 tiles), so this modal gives the user a roomier surface to
 * see the pack's background art, glance at usage stats, and reach the same set of
 * actions that the right-click context menu exposes without having to right-click.
 *
 * <p>The overlay is a {@link StackPane} the caller drops onto an existing root pane
 * (typically the main GUI's GridPane), sized to span the full window. The overlay
 * itself is transparent until {@link #show(GameModPack)} is called, at which point a
 * dim backdrop fades in and the centered modal card slides into view.
 *
 * <p>Dismissal channels (any of these closes the modal):
 * <ul>
 *   <li>Clicking the backdrop region outside the modal card</li>
 *   <li>Clicking the explicit close button (the ✕ in the modal's top-right corner)</li>
 *   <li>Pressing ESC while the modal has focus</li>
 *   <li>Clicking Play (which transitions to the progress screen anyway)</li>
 * </ul>
 *
 * <p>Layout (top to bottom). A persistent header + footer frame a tabbed body so
 * the modal's many sections are grouped instead of stacked into one long scroll:
 * <ol>
 *   <li><b>Hero image</b> (persistent) — large pack background (or procedural
 *       gradient when none), overlaid with the pack logo, friendly name, badges
 *       (Beta / Updated), and the close button.</li>
 *   <li><b>Meta header</b> (persistent) — the stat-chip row (Minecraft / loader /
 *       pack version, RAM, mod count) and, when the audit flags issues, the
 *       Problems banner — so identity and a broken-pack warning are always visible.</li>
 *   <li><b>Tab bar</b> — a segmented control switching the scrollable body between:
 *     <ul>
 *       <li><b>Overview</b> (default) — News, Links, Quick Actions, Stats.</li>
 *       <li><b>Content</b> — Worlds / Servers / Mods / Screenshots / Shader Packs /
 *           Resource Packs file browsers (only when the pack has an install folder).</li>
 *       <li><b>Activity</b> — Update Log and Crash History.</li>
 *       <li><b>Advanced</b> — per-pack verify controls (only when there's a manifest).</li>
 *     </ul>
 *     Each tab's body is built lazily on first activation and cached. See
 *     {@code packNews}/{@code packLinks} in
 *     {@link com.micatechnologies.minecraft.launcher.game.modpack.NewsItem} /
 *     {@link com.micatechnologies.minecraft.launcher.game.modpack.LinkItem}.</li>
 * </ol>
 *
 * <p>The Play and Website buttons live in a sticky action row pinned below the
 * tabbed body so the primary actions remain reachable from any tab.
 *
 * @since 3.4
 */
public class MCLauncherModpackDetailModal extends StackPane
{
    /** Minimum width of the centered modal card. The launcher's own minimum window width
     *  is 750 (see mainGUI.fxml), so 600 leaves comfortable margin around the modal
     *  even at the smallest supported window. */
    private static final double MODAL_MIN_WIDTH  = 600;
    /** Minimum height of the centered modal card. Picked so the hero image + chip row
     *  + sticky action row all fit without the scroll pane being collapsed to zero. */
    private static final double MODAL_MIN_HEIGHT = 480;

    /** Hard upper cap on the modal card's width. The tabbed layout splits the
     *  former single scroll across Overview / Content / Activity / Advanced, so a
     *  wider modal now buys genuinely-more usable width (the file-browser grids,
     *  side-by-side content) rather than just longer text lines — hence the raise
     *  from the pre-tab 1100. */
    private static final double MODAL_MAX_WIDTH  = 1400;
    /** Hard upper cap on the modal card's height. Raised alongside the width so a
     *  large window gets a roomy modal; each tab's body is still scrollable. */
    private static final double MODAL_MAX_HEIGHT = 1000;

    /** Fraction of the parent layout's width/height the modal card targets. ~90%
     *  leaves a thin dim backdrop frame so the user still sees there's content
     *  behind it, while letting the modal grow with the window. */
    private static final double MODAL_WIDTH_FRACTION  = 0.90;
    private static final double MODAL_HEIGHT_FRACTION = 0.90;

    /** Preferred (and maximum) height of the hero image section at the top of the
     *  modal. ~2× the hero-card image height (150 → 320) so background art is
     *  genuinely "viewed bigger" rather than just "viewed slightly bigger." The hero
     *  only reaches this on a tall modal — it scales down toward
     *  {@link #HERO_MIN_HEIGHT} on short modals so the sticky hero can't starve the
     *  scrollable body (see the binding in {@link #buildHeroSection}). */
    private static final double HERO_HEIGHT      = 320;

    /** Floor for the responsive hero height. Below this the pack art reads as a thin
     *  strip and the logo/title crowd it, so we stop shrinking here and let the body
     *  scroll instead. Keeps the 96px logo box + its 18px top/bottom padding fitting
     *  comfortably. */
    private static final double HERO_MIN_HEIGHT  = 172;

    /** Fraction of the modal card's height the hero targets before clamping to
     *  [{@link #HERO_MIN_HEIGHT}, {@link #HERO_HEIGHT}]. At the ~480px minimum modal
     *  height this yields ~173px (near the floor), leaving the header, tab bar,
     *  scroll body, and action row real room; a modal taller than ~890px pins the
     *  hero at its 320 max and the extra height flows to the scroll body. */
    private static final double HERO_HEIGHT_FRACTION = 0.36;

    /** How many update-log entries to render. The full log is bounded at 200 entries
     *  (see {@link ModPackUpdateLog}) but the modal only shows the most recent slice —
     *  anything older is reachable by opening the .update_log.txt file directly from
     *  the Open Folder action. */
    private static final int MAX_LOG_ENTRIES_SHOWN = 12;

    /** Dim, full-overlay region that sits behind the modal card. Captures clicks
     *  on the area outside the card and routes them to {@link #hide()} (the
     *  click-outside-to-dismiss channel); the card itself consumes its own
     *  clicks so they never reach this handler. */
    private final Region backdrop;
    /** The centered modal card. Its content is torn down and rebuilt on every
     *  {@link #show(GameModPack)} (so the overlay can be reused across packs),
     *  and its pref/max size is bound to a clamped fraction of the overlay's
     *  bounds — see the size constants and the binding wired in the constructor. */
    private final VBox modalCard;
    /** The host layout this overlay was added to (the value passed to the
     *  constructor). Retained for reference; the overlay pins itself to fill
     *  this parent so the backdrop covers the whole window. */
    private final Pane parentRoot;

    /** Whether the modal is currently shown. Used to gate ESC handling so we don't
     *  steal escape from other modals/screens when this one isn't active. */
    private boolean visibleState = false;

    // === Hero multi-image cycle state (issue #43). Rebuilt by buildHeroSection on
    //     every show; the modal auto-cycles via the shared clock AND lets the user
    //     step manually with the ◀ ▶ buttons / by clicking the logo. ===
    /** Unsubscribe callback returned by {@link ModpackImageCycleClock#register}
     *  for this modal's auto-advance tick, or {@code null} when not subscribed.
     *  Run-and-cleared on hide / dispose / re-wire so the modal never leaks a
     *  registration into the app-wide clock. */
    private Runnable heroCycleUnsub;
    /** The pack's resolved set of showcase logos for the current show, in cycle
     *  order (post-shuffle when shuffle is enabled). Rebuilt by
     *  {@link #setupHeroCycle}; a size of &gt;1 enables cycling/manual stepping. */
    private java.util.List< Image > heroLogos = java.util.List.of();
    /** The pack's resolved set of showcase background URLs for the current show,
     *  in cycle order. Empty when backgrounds are disabled in settings; a size of
     *  &gt;1 enables cycling and surfaces the ◀ ▶ navigation overlay. */
    private java.util.List< String > heroBgUrls = java.util.List.of();
    /** Current position within the hero cycle, shared by both the logo and the
     *  background so the two advance in lockstep. Wrapped modulo the larger of the
     *  two list sizes by {@link #advanceHero(int)}. */
    private int heroCycleIndex = 0;
    /** The hero's background {@link Region} for the current show, retained so the
     *  cycle / prefetch-rewire paths can repaint it via
     *  {@link MCLauncherMainGui#setBackgroundImageInline}. */
    private Region heroBgLayer;
    /** The hero's logo {@link ImageView} for the current show, retained so the
     *  cycle path can swap its image without rebuilding the hero. */
    private ImageView heroLogoView;
    /** Guards the one-shot background prefetch of not-yet-cached hero images per show. */
    private boolean heroPrefetchStarted = false;

    /** Captured key handler — installed on show, removed on hide so we don't leak
     *  filters when the modal goes back to invisible. */
    private final javafx.event.EventHandler< KeyEvent > escHandler = e -> {
        if ( e.getCode() == KeyCode.ESCAPE && visibleState ) {
            e.consume();
            hide();
        }
    };

    /**
     * Constructs the overlay and registers it as a child of the supplied parent. The
     * overlay is initially invisible/unmanaged so it doesn't intercept layout or
     * mouse events until {@link #show(GameModPack)} is called.
     *
     * <p>The parent should be a layout that allows arbitrary positioning of children
     * (StackPane is ideal; GridPane works if the overlay is configured to span all
     * cells — see {@link #attachToGridPane(GridPane)}). We pin the overlay to fill
     * the parent so the dim backdrop covers the entire window.
     *
     * @param parentRoot the layout that should host this overlay
     */
    public MCLauncherModpackDetailModal( Pane parentRoot )
    {
        this.parentRoot = parentRoot;

        // Dim backdrop — sits behind the modal card, captures clicks for dismissal.
        backdrop = new Region();
        backdrop.getStyleClass().add( "modpackDetailBackdrop" );
        backdrop.setOnMouseClicked( e -> {
            // Only close when the click target is the backdrop itself — clicks on the
            // modal card or its children bubble up but the card's own onMouseClicked
            // handler consumes them, so they won't reach here.
            if ( e.getTarget() == backdrop ) {
                hide();
            }
        } );

        // Modal card placeholder — content is rebuilt on every show(pack) so the
        // overlay can be reused across packs without dragging stale views around.
        modalCard = new VBox();
        modalCard.getStyleClass().add( "modpackDetailCard" );
        modalCard.setMinWidth( MODAL_MIN_WIDTH );
        modalCard.setMinHeight( MODAL_MIN_HEIGHT );
        modalCard.setPickOnBounds( true );
        // Consume clicks on the card so they don't fall through to the backdrop and
        // close the modal when the user is just clicking around inside.
        modalCard.setOnMouseClicked( javafx.event.Event::consume );

        // Bind the card's pref + max size to a clamped fraction of the overlay's own
        // bounds so a maximized 1920×1080 launcher gets a comfortably-large modal
        // (~1100×950 capped) while a 750×475 minimum-size launcher gets a sensible
        // 645×419-ish modal that still respects the min floors above. The overlay
        // itself spans the entire window (GridPane.REMAINING on attach), so its
        // width/height properties track the launcher window minus any chrome.
        javafx.beans.binding.DoubleBinding cardPrefWidth = javafx.beans.binding.Bindings
                .createDoubleBinding( () -> {
                    double w = getWidth() * MODAL_WIDTH_FRACTION;
                    return Math.max( MODAL_MIN_WIDTH, Math.min( MODAL_MAX_WIDTH, w ) );
                }, widthProperty() );
        javafx.beans.binding.DoubleBinding cardPrefHeight = javafx.beans.binding.Bindings
                .createDoubleBinding( () -> {
                    double h = getHeight() * MODAL_HEIGHT_FRACTION;
                    return Math.max( MODAL_MIN_HEIGHT, Math.min( MODAL_MAX_HEIGHT, h ) );
                }, heightProperty() );
        modalCard.prefWidthProperty().bind( cardPrefWidth );
        modalCard.maxWidthProperty().bind( cardPrefWidth );
        modalCard.prefHeightProperty().bind( cardPrefHeight );
        modalCard.maxHeightProperty().bind( cardPrefHeight );

        getChildren().addAll( backdrop, modalCard );
        StackPane.setAlignment( modalCard, Pos.CENTER );

        setPickOnBounds( true );
        setVisible( false );
        setManaged( false );

        // Start at opacity 0 so the first show()'s setVisible(true) +
        // fade-in plays cleanly. Without this, the modal's default
        // opacity is 1.0 — JavaFX paints one frame at full opacity
        // BEFORE the FadeTransition's first pulse snaps opacity to 0,
        // producing a visible "appear → flash gone → fade back in"
        // glitch on the first open after app start. (Subsequent shows
        // don't glitch because hide()'s fade-out leaves opacity at 0.)
        setOpacity( 0.0 );
    }

    /**
     * Convenience wrapper that adds this overlay to a {@link GridPane} root such that
     * it spans every row and column — the common case for the main menu, whose
     * rootPane is a 1-column / 4-row GridPane.
     *
     * <p>Adds the overlay as the last child so it z-orders above all existing content.
     *
     * @param gridPane the GridPane to attach to (must be the same instance passed to
     *                 the constructor as {@code parentRoot})
     */
    public void attachToGridPane( GridPane gridPane )
    {
        GridPane.setRowIndex( this, 0 );
        GridPane.setColumnIndex( this, 0 );
        GridPane.setRowSpan( this, GridPane.REMAINING );
        GridPane.setColumnSpan( this, GridPane.REMAINING );
        if ( !gridPane.getChildren().contains( this ) ) {
            gridPane.getChildren().add( this );
        }
    }

    /**
     * Renders the modal for the given pack and fades it in. Idempotent: calling
     * {@code show} while already visible swaps content to the new pack without an
     * extra fade animation, which is the right behavior when the user closes one
     * pack's modal via dismiss-then-click but a rare edge case in practice.
     *
     * @param pack the pack to display (must be non-null)
     */
    public void show( GameModPack pack )
    {
        if ( pack == null ) return;

        // Build only the cheap modal chrome (hero + action row + an
        // empty body scroll-pane) synchronously, so the modal card has
        // enough structure to lay out + fade in on this same FX pulse.
        // The body's section builders (12+ sections, each with its own
        // FS scan) come in on the next pulse via populateBodyContent —
        // letting the fade-in animation start immediately instead of
        // blocking 1-2 s on layout + CSS while every section materialises.
        rebuildModalSkeleton( pack );

        boolean alreadyShown = visibleState;
        visibleState = true;
        setVisible( true );
        setManaged( true );

        // RGB effect dispatch goes through SDK calls (Razer Chroma, OpenRGB,
        // Windows-DL) that can be tens of ms per device — not catastrophic
        // but enough to be visible on a frame budget. The effect is purely
        // a side-effect (keyboard color), not load-bearing for the modal
        // display, so kick it off-thread.
        SystemUtilities.spawnNewTask(
                () -> com.micatechnologies.minecraft.launcher.rgb.RgbIntegration.onMenu( pack ) );

        // Install the ESC handler on the scene only after the overlay enters the scene
        // graph. We use addEventFilter (not setOnKeyPressed) so we don't clobber
        // existing scene-level key handlers (e.g. the F5 refresh on the main GUI).
        Scene s = getScene();
        if ( s != null ) {
            s.removeEventFilter( KeyEvent.KEY_PRESSED, escHandler );
            s.addEventFilter( KeyEvent.KEY_PRESSED, escHandler );
        }

        if ( !alreadyShown ) {
            FadeTransition fade = new FadeTransition( Duration.millis( 140 ), this );
            fade.setFromValue( 0.0 );
            fade.setToValue( 1.0 );
            fade.play();
        }
        else {
            setOpacity( 1.0 );
        }

        // Push focus onto the modal card so ESC reaches the scene filter without
        // having to click through some focus-traversable child first.
        Platform.runLater( modalCard::requestFocus );

        // Defer body content past the next render pulse. A plain
        // Platform.runLater isn't enough — the FX event loop drains
        // its runLater queue before each render, so a queued
        // populateBodyContent runs BEFORE the renderer ever paints
        // the empty skeleton. The user then sees the modal "pop in"
        // with content already loaded (no spinner ever visible),
        // because the layout + CSS + paint of the populated card is
        // what's actually on screen by frame 1.
        //
        // A 32 ms PauseTransition (~2 frames at 60 Hz) guarantees the
        // renderer has had a chance to paint the empty skeleton first.
        // The fade-in animation is 140 ms, so 32 ms still leaves plenty
        // of fade time for the section placeholders to populate
        // visibly. If a section's bg scan takes longer than ~100 ms
        // the spinner shows; if it's faster, the rows fade in along
        // with the modal — either way the modal itself appears in
        // ~150 ms instead of waiting for every section to render.
        PauseTransition bodyDelay = new PauseTransition( Duration.millis( 32 ) );
        bodyDelay.setOnFinished( e -> populateBodyContent( pack ) );
        bodyDelay.play();
    }

    /** Tear-down hook for the owning GUI's cleanup. Unconditionally drops the
     *  hero-image cycle-clock subscription (idempotent), so a main GUI torn down
     *  while the modal is open doesn't leak the subscription into the app-wide
     *  {@link ModpackImageCycleClock}. Unlike {@link #hide()} it does no
     *  animation and ignores {@code visibleState}. */
    public void dispose()
    {
        if ( heroCycleUnsub != null ) {
            heroCycleUnsub.run();
            heroCycleUnsub = null;
        }
    }

    /** Fades the modal out and returns it to its hidden, unmanaged state so no input
     *  events leak through. Safe to call when already hidden. */
    public void hide()
    {
        if ( !visibleState ) return;
        visibleState = false;

        // Stop auto-cycling the hero images — the modal is closing.
        if ( heroCycleUnsub != null ) {
            heroCycleUnsub.run();
            heroCycleUnsub = null;
        }

        // Revert the idle RGB effect to the theme accent now that we're
        // leaving the pack-specific context. Same null-pack overload as
        // a generic launcher menu.
        com.micatechnologies.minecraft.launcher.rgb.RgbIntegration.onMenu();

        Scene s = getScene();
        if ( s != null ) {
            s.removeEventFilter( KeyEvent.KEY_PRESSED, escHandler );
        }

        FadeTransition fade = new FadeTransition( Duration.millis( 120 ), this );
        fade.setFromValue( getOpacity() );
        fade.setToValue( 0.0 );
        fade.setOnFinished( e -> {
            setVisible( false );
            setManaged( false );
            modalCard.getChildren().clear();
        } );
        fade.play();
    }

    /**
     * Reports whether the modal is currently in its shown (visible, managed)
     * state. Tracks {@link #visibleState}, which flips to {@code true} the
     * instant {@link #show(GameModPack)} begins and back to {@code false} at
     * the start of {@link #hide()} (i.e. before the fade-out animation
     * finishes), so a caller polling this gets the logical state, not the
     * mid-animation opacity.
     *
     * @return {@code true} while the modal is logically open, {@code false} otherwise
     *
     * @since 3.4
     */
    public boolean isShown()
    {
        return visibleState;
    }

    // =============================================================================
    //  Content construction
    // =============================================================================

    /** Tab identifiers for the detail modal's segmented tab bar. */
    private static final String TAB_OVERVIEW = "overview";
    private static final String TAB_CONTENT  = "content";
    private static final String TAB_ACTIVITY = "activity";
    private static final String TAB_ADVANCED = "advanced";

    /** Scroll pane hosting the active tab's body; its content is swapped on tab
     *  switch (the body itself never moves, so the sticky header/footer stay put). */
    private ScrollPane bodyScroll;
    /** Lazily-built body VBox for each tab, keyed by tab id. Built on first
     *  activation (rendered incrementally) and cached so re-selecting is instant.
     *  Cleared on every {@link #show(GameModPack)}. */
    private final java.util.Map< String, VBox > tabBodies = new java.util.HashMap<>();
    /** Tab-bar buttons keyed by tab id, for toggling the {@code active} style. */
    private final java.util.Map< String, Label > tabButtons = new java.util.LinkedHashMap<>();
    /** Bumped on every {@link #show(GameModPack)} so a deferred per-tab render
     *  started for an earlier pack abandons itself once the modal is re-shown. */
    private int packGeneration = 0;

    /**
     * Builds the cheap modal chrome — hero, an empty body scroll-pane,
     * and the action row — so the modal card has enough structure to
     * lay out and start fading in immediately. Heavy section builders
     * (chips, quick actions, stats, content-browser sections, advanced)
     * are deferred to {@link #populateBodyContent} via Platform.runLater.
     */
    private void rebuildModalSkeleton( GameModPack pack )
    {
        modalCard.getChildren().clear();
        tabBodies.clear();
        tabButtons.clear();
        final int gen = ++packGeneration;

        Node hero = buildHeroSection( pack );

        // Persistent meta header under the hero: the chip row plus, when the audit
        // flags issues, the Problems banner — so pack identity and a broken-pack
        // warning stay visible regardless of which tab is active.
        VBox headerMeta = new VBox( 10 );
        headerMeta.setPadding( new Insets( 14, 24, 0, 24 ) );
        headerMeta.getStyleClass().add( "modpackDetailHeaderMeta" );
        headerMeta.getChildren().add( buildChipsRow( pack ) );
        if ( pack.getPackRootFolder() != null ) {
            java.util.List< ModPackAuditLog.Problem > problems =
                    ModPackAuditLog.analyzeProblems( pack.getPackRootFolder(), pack.getLaunchCount() );
            if ( !problems.isEmpty() ) {
                headerMeta.getChildren().add( buildProblemsSection( problems ) );
            }
        }

        // Which tabs apply: Overview always; Content/Activity need an install folder
        // to browse / report on; Advanced needs a manifest to verify against.
        java.util.List< String > tabs = new java.util.ArrayList<>();
        tabs.add( TAB_OVERVIEW );
        if ( pack.getPackRootFolder() != null ) {
            tabs.add( TAB_CONTENT );
            tabs.add( TAB_ACTIVITY );
        }
        if ( pack.getManifestUrl() != null && !pack.getManifestUrl().isBlank() ) {
            tabs.add( TAB_ADVANCED );
        }
        HBox tabBar = buildTabBar( pack, tabs, gen );

        bodyScroll = new ScrollPane();
        bodyScroll.setFitToWidth( true );
        bodyScroll.setHbarPolicy( ScrollPane.ScrollBarPolicy.NEVER );
        bodyScroll.setVbarPolicy( ScrollPane.ScrollBarPolicy.AS_NEEDED );
        bodyScroll.getStyleClass().add( "modpackDetailScroll" );
        SmoothScroll.install( bodyScroll );
        VBox.setVgrow( bodyScroll, Priority.ALWAYS );

        HBox actions = buildActionRow( pack );

        modalCard.getChildren().addAll( hero, headerMeta, tabBar, bodyScroll, actions );
    }

    /**
     * Builds the segmented tab bar. Each tab is a clickable {@link Label} styled
     * as a segmented-control button; clicking activates that tab via
     * {@link #selectTab}.
     *
     * @param pack the pack being shown (passed to the section builders on activation)
     * @param tabs the ordered tab ids that apply to this pack
     * @param gen  the show generation, so stale clicks abandon themselves
     *
     * @return the populated tab-bar row
     */
    private HBox buildTabBar( GameModPack pack, java.util.List< String > tabs, int gen )
    {
        HBox bar = new HBox( 6 );
        bar.getStyleClass().add( "modpackDetailTabBar" );
        bar.setPadding( new Insets( 12, 24, 12, 24 ) );
        bar.setAlignment( Pos.CENTER_LEFT );
        for ( String tab : tabs ) {
            Label btn = new Label( tabLabel( tab ) );
            btn.getStyleClass().add( "modpackDetailTab" );
            btn.setCursor( Cursor.HAND );
            btn.setOnMouseClicked( e -> selectTab( tab, pack, gen ) );
            tabButtons.put( tab, btn );
            bar.getChildren().add( btn );
        }
        return bar;
    }

    /** Returns the localized label for a tab id. */
    private static String tabLabel( String tabId )
    {
        return switch ( tabId ) {
            case TAB_CONTENT  -> LocalizationManager.get( "detailModal.tab.content" );
            case TAB_ACTIVITY -> LocalizationManager.get( "detailModal.tab.activity" );
            case TAB_ADVANCED -> LocalizationManager.get( "detailModal.tab.advanced" );
            default           -> LocalizationManager.get( "detailModal.tab.overview" );
        };
    }

    /**
     * Activates a tab: toggles the tab-bar {@code active} style, lazily builds the
     * tab's body on first activation (rendered incrementally), and swaps it into
     * the scroll area scrolled to the top.
     *
     * @param tabId the tab to activate
     * @param pack  the pack being shown
     * @param gen   the show generation; a mismatch (modal re-shown) is a no-op
     */
    private void selectTab( String tabId, GameModPack pack, int gen )
    {
        if ( gen != packGeneration || bodyScroll == null ) return;
        for ( var entry : tabButtons.entrySet() ) {
            Label btn = entry.getValue();
            if ( entry.getKey().equals( tabId ) ) {
                if ( !btn.getStyleClass().contains( "active" ) ) {
                    btn.getStyleClass().add( "active" );
                }
            }
            else {
                btn.getStyleClass().remove( "active" );
            }
        }
        VBox tabBody = tabBodies.get( tabId );
        if ( tabBody == null ) {
            tabBody = new VBox( 16 );
            tabBody.setPadding( new Insets( 16, 24, 20, 24 ) );
            tabBody.getStyleClass().add( "modpackDetailBody" );
            tabBodies.put( tabId, tabBody );
            renderSectionsIncrementally( tabBody, tabSectionBuilders( tabId, pack, tabBody ), gen );
        }
        bodyScroll.setContent( tabBody );
        bodyScroll.setVvalue( 0.0 );
    }

    /**
     * Returns the ordered section builders for a tab, reusing the existing
     * {@code build*Section} methods. None are removed from the modal — they are
     * regrouped here: Overview = News / Links / Quick Actions / Stats; Content =
     * the file browsers; Activity = Update Log / Crash History; Advanced = the
     * verify controls. An empty tab gets a muted empty-state.
     *
     * @param tabId   the tab whose sections to assemble
     * @param pack    the pack being shown
     * @param tabBody the tab's body VBox (passed to section builders that need it)
     *
     * @return the ordered, non-empty list of section suppliers
     */
    private java.util.List< java.util.function.Supplier< Node > > tabSectionBuilders(
            String tabId, GameModPack pack, VBox tabBody )
    {
        java.util.List< java.util.function.Supplier< Node > > b = new java.util.ArrayList<>();
        switch ( tabId ) {
            case TAB_OVERVIEW -> {
                // News + Links lead (manifest-authored, the freshest "why I'm here"
                // info), then quick actions and the at-a-glance stats.
                java.util.List< com.micatechnologies.minecraft.launcher.game.modpack.NewsItem > news;
                try { news = pack.getVisibleNews(); }
                catch ( Throwable t ) {
                    Logger.logWarningSilent( LocalizationManager.format(
                            "log.modpackDetail.newsReadFailed", t.getClass().getSimpleName() ) );
                    news = java.util.Collections.emptyList();
                }
                if ( !news.isEmpty() ) {
                    final java.util.List< com.micatechnologies.minecraft.launcher.game.modpack.NewsItem > fn = news;
                    b.add( () -> buildNewsSection( pack, fn ) );
                }
                java.util.List< com.micatechnologies.minecraft.launcher.game.modpack.LinkItem > links;
                try { links = pack.getVisibleLinks(); }
                catch ( Throwable t ) {
                    Logger.logWarningSilent( LocalizationManager.format(
                            "log.modpackDetail.linksReadFailed", t.getClass().getSimpleName() ) );
                    links = java.util.Collections.emptyList();
                }
                if ( !links.isEmpty() ) {
                    final java.util.List< com.micatechnologies.minecraft.launcher.game.modpack.LinkItem > fl = links;
                    b.add( () -> buildLinksSection( fl ) );
                }
                b.add( () -> buildQuickActionsSection( pack ) );
                b.add( () -> buildStatsSection( pack ) );
            }
            case TAB_CONTENT -> {
                final Stage ownerStage = MCLauncherGuiController.getTopStageOrNull();
                b.add( () -> ModpackContentBrowser.buildWorldsSection( pack, this::buildSectionBox, ownerStage ) );
                b.add( () -> ModpackContentBrowser.buildServersSection( pack, this::buildSectionBox ) );
                b.add( () -> ModpackContentBrowser.buildModsSection( pack, this::buildSectionBox, tabBody, ownerStage ) );
                b.add( () -> ModpackContentBrowser.buildScreenshotsSection( pack, this::buildSectionBox, this ) );
                b.add( () -> ModpackContentBrowser.buildShaderPacksSection( pack, this::buildSectionBox ) );
                b.add( () -> ModpackContentBrowser.buildResourcePacksSection( pack, this::buildSectionBox ) );
            }
            case TAB_ACTIVITY -> {
                b.add( () -> buildUpdateLogSection( pack ) );
                b.add( () -> ModpackContentBrowser.buildCrashHistorySection( pack, this::buildSectionBox, this ) );
            }
            case TAB_ADVANCED -> b.add( () -> buildAdvancedSection( pack ) );
            default -> { }
        }
        if ( b.isEmpty() ) {
            b.add( () -> {
                Label empty = new Label( LocalizationManager.get( "detailModal.tab.empty" ) );
                empty.getStyleClass().add( "muted" );
                empty.setWrapText( true );
                return empty;
            } );
        }
        return b;
    }

    /**
     * Appends a tab's section builders into its body a few per frame (so a heavy
     * tab can't jank the open/switch), abandoning if the modal closed or was
     * re-shown for a different pack.
     *
     * @param tabBody  the tab body to append into
     * @param builders the ordered section suppliers
     * @param gen      the show generation guarding against a stale render
     */
    private void renderSectionsIncrementally(
            VBox tabBody, java.util.List< java.util.function.Supplier< Node > > builders, int gen )
    {
        final int[] next = { 0 };
        new AnimationTimer()
        {
            @Override
            public void handle( long now )
            {
                if ( gen != packGeneration || !visibleState ) {
                    stop();
                    return;
                }
                int end = Math.min( next[ 0 ] + SECTIONS_PER_FRAME, builders.size() );
                while ( next[ 0 ] < end ) {
                    tabBody.getChildren().add( builders.get( next[ 0 ] ).get() );
                    next[ 0 ]++;
                }
                if ( next[ 0 ] >= builders.size() ) {
                    stop();
                }
            }
        }.start();
    }

    /**
     * Appends body sections into the modal's already-rendered scroll
     * area. Called via Platform.runLater from {@link #show} so the
     * fade-in animation gets to start on this pulse and the heavy
     * section construction lands on the next. Each content-browser
     * section returns a placeholder + populates itself off the FX
     * thread, so this call returns quickly even when there's a lot
     * of data to scan.
     *
     * <p>If the modal was closed (or re-shown for a different pack)
     * between {@link #show} scheduling this call and it actually
     * running, bail — {@code currentBody} would point at a different
     * pack's body or be null.</p>
     */
    /** How many body sections to construct + mount per animation frame in
     *  {@link #populateBodyContent}. Small enough that one frame's work stays
     *  inside the ~16 ms budget so the open fade can't stutter; large enough
     *  that the whole body still streams in within the fade's duration. */
    private static final int SECTIONS_PER_FRAME = 3;

    /**
     * Deferred entry point (fired ~32 ms after {@link #show} so the empty
     * skeleton paints first): activates the default Overview tab, which lazily
     * builds and streams in its sections. The other tabs build on first click.
     *
     * @param pack the pack whose detail content is rendered
     */
    private void populateBodyContent( GameModPack pack )
    {
        if ( !visibleState || bodyScroll == null ) return;
        selectTab( TAB_OVERVIEW, pack, packGeneration );
    }

    /**
     * Builds the hero section at the top of the modal: the background image
     * (or procedural gradient fallback), a darkening veil, the Beta /
     * Recently-updated badge row, the pack logo + name + last-played subtitle,
     * the optional ◀ ▶ multi-image navigation overlay, and the close button.
     *
     * <p>Resets {@link #heroPrefetchStarted} so this show gets a fresh chance to
     * prefetch declared-but-uncached showcase images, and wires the multi-image
     * cycle via {@link #setupHeroCycle}. Children are added in deliberate z-order
     * (close button last) so the close button's hit region is never overlapped by
     * a wide title row — see the inline notes for the defensive measures.
     *
     * @param pack the pack whose art and metadata the hero displays
     * @return the assembled hero {@link StackPane}
     */
    private Node buildHeroSection( GameModPack pack )
    {
        // Fresh hero per show — allow its cycle images to be prefetched once.
        heroPrefetchStarted = false;

        StackPane hero = new StackPane();
        hero.getStyleClass().add( "modpackDetailHero" );
        // Responsive height: the hero is sticky (never scrolls), so a fixed 320 would
        // eat most of a short modal and crush the scrollable body below the tab bar to
        // near-zero. Scale it with the modal card's height, clamped to
        // [HERO_MIN_HEIGHT, HERO_HEIGHT], so a small modal keeps a usable body while a
        // tall one still gets the full-size hero art.
        javafx.beans.binding.DoubleBinding heroHeight = javafx.beans.binding.Bindings
                .createDoubleBinding( () -> {
                    double h = modalCard.getHeight() * HERO_HEIGHT_FRACTION;
                    return Math.max( HERO_MIN_HEIGHT, Math.min( HERO_HEIGHT, h ) );
                }, modalCard.heightProperty() );
        hero.minHeightProperty().bind( heroHeight );
        hero.prefHeightProperty().bind( heroHeight );
        hero.maxHeightProperty().bind( heroHeight );

        // Rounded-top clip so the hero image respects the modal card's corner radius.
        Rectangle clip = new Rectangle();
        clip.setArcWidth( 32 );
        clip.setArcHeight( 32 );
        clip.widthProperty().bind( hero.widthProperty() );
        clip.heightProperty().bind( hero.heightProperty() );
        hero.setClip( clip );

        // Background image / dynamic gradient — same resolution rules as the hero card
        // (custom bg → file URL; vanilla → sky gradient; modded w/ logo → derived
        // gradient; modded w/o logo → Forge default).
        Image logoImage = resolveLogoImage( pack );
        Region bgLayer = new Region();
        bgLayer.getStyleClass().add( "heroBackground" );
        // Always paint the dynamic gradient as the placeholder behind the bg-image so
        // the hero area never renders empty during a cold image fetch. The remote
        // -fx-background-image, when supplied, layers on top once its bytes arrive
        // and through any transparent regions.
        MCLauncherMainGui.applyDynamicBackground( bgLayer, pack, logoImage );
        String bgUrl = resolveBackgroundUrl( pack );
        if ( bgUrl != null ) {
            String existing = bgLayer.getStyle() == null ? "" : bgLayer.getStyle();
            bgLayer.setStyle( existing + " -fx-background-image: url('" + bgUrl + "');" );
        }
        heroBgLayer = bgLayer;

        // Veil — heavier at the bottom-left where the logo and title sit, so they read
        // cleanly over arbitrary bright pack imagery.
        Region veil = new Region();
        veil.getStyleClass().add( "modpackDetailHeroVeil" );

        // Close button — top-right, sits above the veil.
        //
        // Multiple defensive measures together because previous single-fix attempts
        // (pickOnBounds on the Label alone) didn't make the button respond:
        //   1. pickOnBounds(true) so the entire 32×32 styled circle is hit-testable
        //      (Label nodes otherwise only pick on the painted glyph).
        //   2. Added as the LAST hero child so it z-orders above the titleRow.
        //      An earlier ordering put titleRow on top, and although titleRow is
        //      Pos.BOTTOM_LEFT-anchored, with `wrapText` on the title label the HBox
        //      can grow to a width that overlaps the close button's TOP_RIGHT cell
        //      depending on the modal's pref size — closeBtn-on-top sidesteps that.
        //   3. Both MOUSE_PRESSED + MOUSE_CLICKED handlers are wired. MOUSE_PRESSED
        //      fires first and survives even if some intermediate node consumes the
        //      MOUSE_CLICKED — belt and suspenders.
        Label closeBtn = new Label( "✕" );
        closeBtn.getStyleClass().add( "modpackDetailClose" );
        closeBtn.setCursor( Cursor.HAND );
        closeBtn.setPickOnBounds( true );
        closeBtn.setOnMousePressed( e -> { e.consume(); hide(); } );
        closeBtn.setOnMouseClicked( e -> { e.consume(); hide(); } );
        StackPane.setAlignment( closeBtn, Pos.TOP_RIGHT );
        StackPane.setMargin( closeBtn, new Insets( 14, 18, 0, 0 ) );

        // Badge row — top-left so it doesn't collide with the close button.
        HBox badgeRow = new HBox( 6 );
        badgeRow.setAlignment( Pos.TOP_LEFT );
        if ( pack.getPackUnstable() ) badgeRow.getChildren().add( buildChip( LocalizationManager.get( "modal.badge.beta" ), "stat-chip-warn" ) );
        // "Recently updated" matches the Library screen's badge text — keeping
        // the same vocabulary across surfaces so the user learns one term.
        if ( pack.isUpdateAvailable() ) badgeRow.getChildren().add( buildChip( LocalizationManager.get( "modal.badge.recentlyUpdated" ), "stat-chip-success" ) );
        StackPane.setAlignment( badgeRow, Pos.TOP_LEFT );
        StackPane.setMargin( badgeRow, new Insets( 14, 0, 0, 18 ) );

        // Logo + title — bottom-left of the hero image, mirroring the layout language
        // of the hero card but scaled up so it reads at modal size.
        // pickOnBounds(false) so clicks only register on the actually-painted logo +
        // text, not the empty space between the title text and the top-right corner
        // of the hero where the close button lives. The titleRow's HBox bounds can
        // grow wider than its visible content (long title text + wrapText), and
        // without this, the empty parts of the row could intercept clicks meant
        // for the ✕ button.
        HBox titleRow = new HBox( 14 );
        titleRow.setAlignment( Pos.CENTER_LEFT );
        titleRow.setPadding( new Insets( 0, 18, 18, 18 ) );
        titleRow.setPickOnBounds( false );
        StackPane.setAlignment( titleRow, Pos.BOTTOM_LEFT );

        StackPane logoBox = new StackPane();
        logoBox.getStyleClass().add( "modpackDetailLogo" );
        logoBox.setMinSize( 96, 96 );
        logoBox.setMaxSize( 96, 96 );
        ImageView logoView = new ImageView();
        logoView.setFitWidth( 88 );
        logoView.setFitHeight( 88 );
        logoView.setPreserveRatio( true );
        logoView.setImage( logoImage );
        heroLogoView = logoView;
        // Clicking the logo advances to the next image immediately (issue #43). The
        // cursor only turns into a hand when there's actually more than one logo —
        // wired below once the logo list is resolved.
        logoView.setOnMouseClicked( e -> {
            if ( heroLogos.size() > 1 || heroBgUrls.size() > 1 ) {
                e.consume();
                advanceHero( 1 );
            }
        } );
        // Fade the logo in once its bytes arrive — keeps the modal from flickering
        // a blank logo square on cold-network opens.
        ImageFadeIn.apply( logoView );
        Rectangle logoClip = new Rectangle( 88, 88 );
        logoClip.setArcWidth( 18 );
        logoClip.setArcHeight( 18 );
        logoView.setClip( logoClip );
        logoBox.getChildren().add( logoView );

        // Strip the glass-frame container styling when the logo has transparent
        // edges, same rationale as the hero card — see ModpackHeroCard.
        LogoTransparencyDetector.detectAsync( logoImage, isTransparent -> {
            if ( isTransparent ) {
                logoBox.getStyleClass().add( "logoTransparent" );
            }
        } );

        VBox titleBox = new VBox( 4 );
        titleBox.setAlignment( Pos.CENTER_LEFT );

        Label nameLabel = new Label( resolveDisplayName( pack ) );
        nameLabel.getStyleClass().add( "modpackDetailTitle" );
        nameLabel.setWrapText( true );

        Label playedLabel = new Label();
        playedLabel.getStyleClass().add( "modpackDetailSubtitle" );
        if ( pack.isNeverPlayed() ) {
            playedLabel.setText(
                    LocalizationManager.get("main.card.neverPlayed" ) );
        }
        else {
            playedLabel.setText(
                    com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager.format(
                            "main.card.lastPlayed",
                            pack.getLastPlayedFormatted().toLowerCase() ) );
        }

        titleBox.getChildren().addAll( nameLabel, playedLabel );
        titleRow.getChildren().addAll( logoBox, titleBox );

        // Resolve the pack's full set of cached logos / backgrounds and wire the cycle.
        // Returns the ◀ ▶ navigation control (only when there are multiple backgrounds
        // to showcase) to overlay on the hero.
        Node bgNav = setupHeroCycle( pack, logoImage );

        // closeBtn is added LAST so it z-orders on top of every other hero element.
        // If it weren't, the titleRow (added next in the bottom-anchored slot) could
        // overlap the close button's clickable region for very wide title rows.
        hero.getChildren().addAll( bgLayer, veil, badgeRow, titleRow );
        if ( bgNav != null ) {
            hero.getChildren().add( bgNav );
        }
        hero.getChildren().add( closeBtn );
        return hero;
    }

    /**
     * (Re)builds the hero's multi-image cycle for {@code pack} (issue #43): resolves every
     * cached logo + background, applies the Settings shuffle, subscribes to the shared
     * {@link ModpackImageCycleClock} for auto-advance, and prefetches any not-yet-cached
     * showcase images. Returns a ◀ ▶ navigation overlay when the pack ships more than one
     * background (so the user can step through them — backgrounds double as showcase art),
     * or {@code null} otherwise.
     *
     * @param pack        the pack whose showcase images drive the cycle
     * @param primaryLogo the already-resolved primary logo, used as the sole cycle
     *                    entry when no per-disk logos are found
     * @return a ◀ ▶ navigation overlay {@link Node} when the pack has more than one
     *         background, or {@code null} when no navigation control is warranted
     */
    private Node setupHeroCycle( GameModPack pack, Image primaryLogo )
    {
        // Drop any prior subscription (modal reused across packs / re-shows).
        if ( heroCycleUnsub != null ) {
            heroCycleUnsub.run();
            heroCycleUnsub = null;
        }
        heroCycleIndex = 0;

        boolean showBg = ConfigManager.getShowPackBackgrounds();
        java.util.List< Image > rawLogos = ModpackImageResolver.resolveLogosFromDisk( pack );
        java.util.List< String > rawBgs = showBg
                ? ModpackImageResolver.resolveBackgroundUrlsFromDisk( pack )
                : java.util.List.of();

        // One-shot prefetch of declared-but-uncached showcase images, then re-wire.
        int declaredLogos = pack.hasCustomLogo() ? pack.getPackLogoUrlCount() : 0;
        int declaredBgs = ( showBg && pack.hasCustomBackground() ) ? pack.getPackBackgroundUrlCount() : 0;
        if ( !heroPrefetchStarted
                && ( rawLogos.size() < declaredLogos || rawBgs.size() < declaredBgs ) ) {
            heroPrefetchStarted = true;
            final GameModPack captured = pack;
            SystemUtilities.spawnNewTask( () -> {
                try {
                    captured.cacheImages();
                }
                catch ( Throwable ignored ) {
                    return;
                }
                GUIUtilities.JFXPlatformRun( () -> {
                    // Only re-wire if the modal still shows this pack and the hero nodes
                    // are still the ones we built.
                    if ( visibleState && heroBgLayer != null && heroLogoView != null ) {
                        rewireHeroAfterPrefetch( captured, primaryLogo );
                    }
                } );
            } );
        }

        java.util.List< Image > logos = rawLogos.isEmpty() && primaryLogo != null
                ? new java.util.ArrayList<>( java.util.List.of( primaryLogo ) )
                : new java.util.ArrayList<>( rawLogos );
        java.util.List< String > bgs = new java.util.ArrayList<>( rawBgs );

        if ( ConfigManager.getImageCycleShuffle() ) {
            if ( logos.size() > 1 ) java.util.Collections.shuffle( logos );
            if ( bgs.size() > 1 ) java.util.Collections.shuffle( bgs );
        }

        heroLogos = logos;
        heroBgUrls = bgs;

        // Hand cursor on the logo only when clicking it would actually do something.
        if ( heroLogoView != null ) {
            heroLogoView.setCursor( ( heroLogos.size() > 1 || heroBgUrls.size() > 1 )
                                    ? Cursor.HAND : Cursor.DEFAULT );
        }

        // Auto-advance via the shared clock when there's more than one of either.
        if ( heroLogos.size() > 1 || heroBgUrls.size() > 1 ) {
            heroCycleUnsub = ModpackImageCycleClock.getInstance().register( () -> {
                if ( visibleState ) advanceHero( 1 );
            } );
        }

        return heroBgUrls.size() > 1 ? buildHeroNav() : null;
    }

    /** Re-resolves the hero cycle after a prefetch lands and refreshes the displayed
     *  image. Re-adds the ◀ ▶ nav overlay if backgrounds newly became multiple.
     *
     *  @param pack        the pack whose now-cached images should be re-resolved
     *  @param primaryLogo the primary logo to fall back on, forwarded to
     *                     {@link #setupHeroCycle} */
    private void rewireHeroAfterPrefetch( GameModPack pack, Image primaryLogo )
    {
        Node bgNav = setupHeroCycle( pack, primaryLogo );
        // The hero StackPane is the bgLayer's parent; re-insert the nav just under the
        // close button if one is now warranted and not already present.
        if ( bgNav != null && heroBgLayer != null
                && heroBgLayer.getParent() instanceof StackPane heroPane ) {
            boolean hasNav = heroPane.getChildren().stream()
                    .anyMatch( n -> "heroNav".equals( n.getId() ) );
            if ( !hasNav ) {
                int insertAt = Math.max( 0, heroPane.getChildren().size() - 1 );
                heroPane.getChildren().add( insertAt, bgNav );
            }
        }
        applyHeroImages();
    }

    /** Builds the ◀ ▶ overlay that steps through the pack's backgrounds.
     *
     *  @return an {@link HBox} spanning the hero with left / right chevron labels
     *          wired to {@link #advanceHero(int)} */
    private Node buildHeroNav()
    {
        Label prev = new Label( "‹" ); // ‹
        Label next = new Label( "›" ); // ›
        prev.getStyleClass().add( "modpackDetailHeroNav" );
        next.getStyleClass().add( "modpackDetailHeroNav" );
        prev.setCursor( Cursor.HAND );
        next.setCursor( Cursor.HAND );
        prev.setPickOnBounds( true );
        next.setPickOnBounds( true );
        prev.setOnMouseClicked( e -> { e.consume(); advanceHero( -1 ); } );
        next.setOnMouseClicked( e -> { e.consume(); advanceHero( 1 ); } );

        Region spacer = new Region();
        HBox.setHgrow( spacer, Priority.ALWAYS );
        HBox nav = new HBox( prev, spacer, next );
        nav.setId( "heroNav" );
        nav.setAlignment( Pos.CENTER );
        nav.setPickOnBounds( false );
        nav.setPadding( new Insets( 0, 12, 0, 12 ) );
        // Fill the hero so the chevrons are pushed to its left / right edges by the
        // spacer (a pref-sized HBox would bunch them in the middle).
        nav.setMaxWidth( Double.MAX_VALUE );
        nav.setMaxHeight( Double.MAX_VALUE );
        StackPane.setAlignment( nav, Pos.CENTER );
        return nav;
    }

    /** Steps the hero cycle by {@code delta} (wrapping) and repaints. Drives both the
     *  logo and background in sync (issue #43), independent of the auto-cycle clock so
     *  manual stepping works even when the interval is set to "Never".
     *
     *  @param delta number of steps to move (positive forward, negative back); the
     *               result wraps modulo the larger of the logo / background counts.
     *               A no-op when there's nothing to cycle */
    private void advanceHero( int delta )
    {
        int span = Math.max( heroLogos.size(), heroBgUrls.size() );
        if ( span <= 1 ) return;
        heroCycleIndex = ( ( heroCycleIndex + delta ) % span + span ) % span;
        applyHeroImages();
    }

    /** Applies the current cycle index to the hero logo + background nodes. */
    private void applyHeroImages()
    {
        if ( heroLogoView != null && heroLogos.size() > 1 ) {
            heroLogoView.setImage( heroLogos.get( heroCycleIndex % heroLogos.size() ) );
            ImageFadeIn.apply( heroLogoView );
        }
        if ( heroBgLayer != null && heroBgUrls.size() > 1 ) {
            MCLauncherMainGui.setBackgroundImageInline(
                    heroBgLayer, heroBgUrls.get( heroCycleIndex % heroBgUrls.size() ) );
        }
    }

    /**
     * Builds the at-a-glance metadata chip row: a Vanilla marker (vanilla packs
     * only), Minecraft version, mod-loader name + short version, pack version
     * (when it differs from the MC version), minimum RAM, and mod count. Each
     * chip is added only when its underlying value is present and meaningful;
     * resolution failures are swallowed so one bad field can't sink the row.
     *
     * @param pack the pack whose metadata populates the chips
     * @return an {@link HBox} of {@code stat-chip} labels
     */
    private Node buildChipsRow( GameModPack pack )
    {
        HBox chips = new HBox( 6 );
        chips.setAlignment( Pos.CENTER_LEFT );

        if ( pack.isVanillaVersion() ) {
            chips.getChildren().add( buildChip( LocalizationManager.get( "modal.chip.vanilla" ), "stat-chip" ) );
        }
        String mc = safeMinecraftVersion( pack );
        if ( mc != null && !mc.isBlank() ) {
            chips.getChildren().add( buildChip( LocalizationManager.format( "modal.chip.minecraft", mc ), "stat-chip" ) );
        }
        String loaderName = safeLoaderName( pack );
        String loaderVersion = safeLoaderVersion( pack );
        if ( loaderName != null && loaderVersion != null && !loaderVersion.isBlank() ) {
            // Same short-version formatting as the hero card so the two surfaces match.
            String shortLoader = loaderVersion.contains( "-" )
                    ? loaderVersion.substring( loaderVersion.lastIndexOf( '-' ) + 1 )
                    : loaderVersion;
            chips.getChildren().add( buildChip( loaderName + " " + shortLoader, "stat-chip" ) );
        }
        String packVersion = pack.getPackVersion();
        if ( packVersion != null && !packVersion.isBlank() && !packVersion.equals( mc ) ) {
            chips.getChildren().add( buildChip( LocalizationManager.format( "modal.chip.packVersion", packVersion ), "stat-chip" ) );
        }

        // RAM chip — the manifest declares a minimum, which is genuinely useful for
        // "should I bump my allocation?" decisions. Parse failures (unset / malformed)
        // just skip the chip.
        Double ramGB = safeCall( () -> {
            try { return pack.getPackMinRAMGB(); }
            catch ( Exception ignored ) { return null; }
        } );
        if ( ramGB != null && ramGB > 0 ) {
            String ramAmount = ( ramGB == Math.floor( ramGB ) )
                    ? String.format( "%.0f", ramGB )
                    : String.format( "%.1f", ramGB );
            chips.getChildren().add( buildChip(
                    LocalizationManager.format( "modal.chip.minimumRam", ramAmount ), "stat-chip" ) );
        }

        int modCount = safePackModCount( pack );
        if ( modCount > 0 ) {
            chips.getChildren().add( buildChip(
                    LocalizationManager.format( "modal.chip.modCount", modCount ), "stat-chip" ) );
        }

        return chips;
    }

    /**
     * Builds the Quick Actions section — the right-click context menu surfaced as
     * a reflowing button grid: Open Folder plus (modded packs only) Mods / Config,
     * then Screenshots / Resource Packs / Shaders, Create Desktop Shortcut, Export
     * Pack, and Copy Invite Link. The invite button is disabled with an explanatory
     * tooltip when the pack has no invitable target.
     *
     * @param pack the pack the actions operate on
     * @return the assembled section {@link VBox} (header + button grid)
     */
    private Node buildQuickActionsSection( GameModPack pack )
    {
        VBox section = buildSectionBox( com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager.get( "detailModal.section.quickActions" ) );
        // Tile-style grid of action buttons — uses a FlowPane so it reflows on
        // narrower modal widths (e.g. when the user has a small window) instead of
        // overflowing horizontally.
        javafx.scene.layout.FlowPane grid = new javafx.scene.layout.FlowPane( 8, 8 );
        grid.setAlignment( Pos.CENTER_LEFT );

        grid.getChildren().add( buildQuickActionBtn( LocalizationManager.get( "modal.quickAction.openFolder" ),
                () -> openPackSubfolder( pack, "" ) ) );
        // Mods/config/resourcepacks/shaderpacks only make sense for modded packs —
        // vanilla versions don't have a separate Forge config dir, no mods, etc.
        if ( !pack.isVanillaVersion() ) {
            grid.getChildren().add( buildQuickActionBtn( LocalizationManager.get( "modal.quickAction.mods" ),
                    () -> openPackSubfolder( pack, "mods" ) ) );
            grid.getChildren().add( buildQuickActionBtn( LocalizationManager.get( "modal.quickAction.config" ),
                    () -> openPackSubfolder( pack, "config" ) ) );
        }
        grid.getChildren().add( buildQuickActionBtn( LocalizationManager.get( "modal.quickAction.screenshots" ),
                () -> openPackSubfolder( pack, "screenshots" ) ) );
        grid.getChildren().add( buildQuickActionBtn( LocalizationManager.get( "modal.quickAction.resourcePacks" ),
                () -> openPackSubfolder( pack, "resourcepacks" ) ) );
        grid.getChildren().add( buildQuickActionBtn( LocalizationManager.get( "modal.quickAction.shaders" ),
                () -> openPackSubfolder( pack, "shaderpacks" ) ) );
        grid.getChildren().add( buildQuickActionBtn( LocalizationManager.get( "modal.quickAction.desktopShortcut" ),
                () -> createDesktopShortcut( pack ) ) );
        grid.getChildren().add( buildQuickActionBtn(
                com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager.get( "detailModal.exportPack.label" ),
                () -> showSmartExportDialog( pack ) ) );

        // Copy Invite Link — only enabled when the pack has something to invite to.
        MFXButton inviteBtn = buildQuickActionBtn( LocalizationManager.get( "modal.quickAction.copyInvite" ),
                () -> copyInviteLinkToClipboard( pack ) );
        if ( DiscordRpcUtility.buildInviteLinkFromPack( pack ) == null ) {
            inviteBtn.setDisable( true );
            TooltipManager.install( inviteBtn,
                    LocalizationManager.get( "tooltip.modal.invite" ) );
        }
        grid.getChildren().add( inviteBtn );

        section.getChildren().add( grid );
        return section;
    }

    /**
     * Smart-export entry point. Classifies the pack and lets the user
     * pick between the three sharing modes:
     * <ul>
     *   <li><b>Share URL</b> — copy the manifest URL to clipboard. Cheapest
     *       option for packs installed from a remote manifest.</li>
     *   <li><b>Share Manifest JSON</b> — save the manifest body to a file
     *       so a friend can drop it on any HTTPS host. Available when every
     *       mod in the pack has an HTTP/S download URL.</li>
     *   <li><b>Export as ZIP</b> — full archive including mods, configs,
     *       and the embedded manifest. Always available; required when
     *       the pack has any local-file-reference mods.</li>
     * </ul>
     *
     * @param pack the pack to share / export; a {@code null} pack is a no-op
     */
    private void showSmartExportDialog( com.micatechnologies.minecraft.launcher.game.modpack.GameModPack pack )
    {
        if ( pack == null ) return;
        com.micatechnologies.minecraft.launcher.game.modpack.ModpackExporter.ExportMode mode
                = com.micatechnologies.minecraft.launcher.game.modpack.ModpackExporter.classifyExport( pack );

        javafx.scene.control.Alert chooser = new javafx.scene.control.Alert(
                javafx.scene.control.Alert.AlertType.CONFIRMATION );
        chooser.setTitle( com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager.get(
                "detailModal.exportPack.label" ) );
        chooser.setHeaderText( pack.getFriendlyName() != null
                                        ? pack.getFriendlyName() : pack.getPackName() );

        // Build the body text dynamically — explain what each option does
        // and which one is "recommended" for this pack so the user picks
        // the lightest mode that actually works.
        StringBuilder body = new StringBuilder();
        body.append( LocalizationManager.get( "modal.share.prompt" ) ).append( "\n\n" );
        switch ( mode ) {
            case SHARE_URL -> body.append( LocalizationManager.get( "modal.share.recommend.url" ) );
            case SHARE_MANIFEST_JSON -> body.append( LocalizationManager.get( "modal.share.recommend.manifest" ) );
            case EXPORT_ZIP -> body.append( LocalizationManager.get( "modal.share.recommend.zip" ) );
        }
        chooser.setContentText( body.toString() );

        // Button types per available mode. ButtonType is mutable here so we
        // can also offer ZIP as a fallback regardless of recommended mode.
        javafx.scene.control.ButtonType shareUrlBtn = new javafx.scene.control.ButtonType(
                LocalizationManager.get( "modal.share.button.shareUrl" ) );
        javafx.scene.control.ButtonType shareJsonBtn = new javafx.scene.control.ButtonType(
                LocalizationManager.get( "modal.share.button.shareManifest" ) );
        javafx.scene.control.ButtonType zipBtn = new javafx.scene.control.ButtonType(
                LocalizationManager.get( "modal.share.button.exportZip" ) );
        javafx.scene.control.ButtonType cancel = new javafx.scene.control.ButtonType(
                LocalizationManager.get( "dialog.button.cancel" ),
                javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE );

        java.util.List< javafx.scene.control.ButtonType > buttons = new java.util.ArrayList<>();
        if ( mode == com.micatechnologies.minecraft.launcher.game.modpack.ModpackExporter.ExportMode.SHARE_URL ) {
            buttons.add( shareUrlBtn );
        }
        if ( mode == com.micatechnologies.minecraft.launcher.game.modpack.ModpackExporter.ExportMode.SHARE_URL
                || mode == com.micatechnologies.minecraft.launcher.game.modpack.ModpackExporter.ExportMode.SHARE_MANIFEST_JSON ) {
            buttons.add( shareJsonBtn );
        }
        buttons.add( zipBtn );
        buttons.add( cancel );
        chooser.getButtonTypes().setAll( buttons );

        java.util.Optional< javafx.scene.control.ButtonType > picked = chooser.showAndWait();
        if ( picked.isEmpty() || picked.get() == cancel ) return;

        if ( picked.get() == shareUrlBtn ) {
            shareManifestUrlToClipboard( pack );
        }
        else if ( picked.get() == shareJsonBtn ) {
            saveManifestJsonToFile( pack );
        }
        else if ( picked.get() == zipBtn ) {
            exportPackAsZip( pack );
        }
    }

    /** Copies the pack's manifest URL to the system clipboard and
     *  surfaces a success notification. No file IO. Shows an error
     *  notification instead when the pack has no manifest URL.
     *
     *  @param pack the pack whose manifest URL is copied */
    private void shareManifestUrlToClipboard( com.micatechnologies.minecraft.launcher.game.modpack.GameModPack pack )
    {
        String url = pack.getManifestUrl();
        if ( url == null || url.isBlank() ) {
            NotificationManager.error( LocalizationManager.get( "notification.detail.shareUrlFailed.title" ),
                                        LocalizationManager.get( "notification.detail.shareUrlFailed.body" ) );
            return;
        }
        javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
        content.putString( url );
        javafx.scene.input.Clipboard.getSystemClipboard().setContent( content );
        NotificationManager.success( LocalizationManager.get( "notification.detail.urlCopied.title" ),
                                      LocalizationManager.format( "notification.detail.urlCopied.body", url ) );
    }

    /** Saves the pack's manifest JSON body to a user-chosen file via a
     *  FileChooser. Default filename is {@code <packName>-manifest.json}.
     *  The body comes from the on-disk manifest cache via
     *  {@link com.micatechnologies.minecraft.launcher.game.modpack.ModpackExporter#loadManifestText}.
     *
     *  @param pack the pack whose cached manifest JSON is saved */
    private void saveManifestJsonToFile( com.micatechnologies.minecraft.launcher.game.modpack.GameModPack pack )
    {
        String body = com.micatechnologies.minecraft.launcher.game.modpack.ModpackExporter.loadManifestText( pack );
        if ( body == null || body.isBlank() ) {
            NotificationManager.error(
                    LocalizationManager.get("detailModal.manifest.couldNotRead.title" ),
                    LocalizationManager.get("detailModal.manifest.couldNotRead.body" ) );
            return;
        }
        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
        chooser.setTitle( LocalizationManager.get("dialog.fileChooser.saveManifest.title" ) );
        String defaultName = ( pack.getPackName() == null ? "modpack" : pack.getPackName() )
                + "-manifest.json";
        chooser.setInitialFileName( defaultName );
        chooser.getExtensionFilters().add(
                new javafx.stage.FileChooser.ExtensionFilter(
                        LocalizationManager.get( "dialog.fileChooser.json.filter" ),
                        "*.json" ) );
        javafx.stage.Stage owner = MCLauncherGuiController.getTopStageOrNull();
        java.io.File dest = chooser.showSaveDialog( owner );
        if ( dest == null ) return;
        try {
            java.nio.file.Files.writeString( dest.toPath(), body,
                                              java.nio.charset.StandardCharsets.UTF_8 );
            NotificationManager.success(
                    LocalizationManager.get("detailModal.manifest.saved.title" ),
                    dest.getAbsolutePath() );
        }
        catch ( java.io.IOException ex ) {
            Logger.logErrorSilent( LocalizationManager.format( "log.modpackDetail.saveManifestJsonFailed", ex.getMessage() ) );
            NotificationManager.error(
                    LocalizationManager.get("detailModal.manifest.couldNotSave.title" ),
                    ex.getMessage() );
        }
    }

    /** Pops a file chooser for the destination ZIP, then runs the
     *  export off the FX thread via FxAsyncTask. Shows a notification
     *  on completion (success path links the saved file; failure
     *  path surfaces the exception message). Modded packs are verified
     *  first (force-download of any not-yet-present content) so the ZIP
     *  isn't sparse; vanilla packs skip verify and archive directly.
     *
     *  @param pack the pack to archive; a {@code null} pack or one with no
     *              root folder is a no-op */
    private void exportPackAsZip( com.micatechnologies.minecraft.launcher.game.modpack.GameModPack pack )
    {
        if ( pack == null || pack.getPackRootFolder() == null ) return;
        // Confirmation dialog with the "include worlds" toggle.
        javafx.scene.control.Alert prompt = new javafx.scene.control.Alert(
                javafx.scene.control.Alert.AlertType.CONFIRMATION );
        prompt.setTitle( com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager.get(
                "detailModal.exportPack.dialogTitle" ) );
        prompt.setHeaderText( pack.getFriendlyName() != null
                                       ? pack.getFriendlyName() : pack.getPackName() );
        prompt.setContentText( com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager.get(
                "detailModal.exportPack.dialogPrompt" ) );
        javafx.scene.control.CheckBox worldsToggle = new javafx.scene.control.CheckBox(
                com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager.get(
                        "detailModal.exportPack.includeWorlds" ) );
        prompt.getDialogPane().setExpandableContent( worldsToggle );
        prompt.getDialogPane().setExpanded( true );

        java.util.Optional< javafx.scene.control.ButtonType > ok = prompt.showAndWait();
        if ( ok.isEmpty() || ok.get() != javafx.scene.control.ButtonType.OK ) return;
        final boolean includeWorlds = worldsToggle.isSelected();

        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
        chooser.setTitle( com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager.get(
                "detailModal.exportPack.dialogTitle" ) );
        String defaultName = ( pack.getPackName() == null ? "modpack" : pack.getPackName() )
                + "-" + System.currentTimeMillis() + ".zip";
        chooser.setInitialFileName( defaultName );
        chooser.getExtensionFilters().add(
                new javafx.stage.FileChooser.ExtensionFilter(
                        LocalizationManager.get( "dialog.fileChooser.zipArchive.filter" ), "*.zip" ) );
        javafx.stage.Stage owner = MCLauncherGuiController.getTopStageOrNull();
        java.io.File dest = chooser.showSaveDialog( owner );
        if ( dest == null ) return;

        final String displayName = pack.getFriendlyName() != null
                ? pack.getFriendlyName() : pack.getPackName();
        NotificationManager.success(
                com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager.format(
                        "detailModal.exportPack.starting", displayName ),
                dest.getAbsolutePath() );

        // Build the ZIP step as a continuation we can chain after a
        // pack verify. A never-launched pack has empty mods/configs/
        // resourcepacks/ directories on disk, which would produce a
        // sparse ZIP — verifying first force-downloads everything.
        // Vanilla packs skip verify (no manifest-driven content to
        // re-verify) and go straight to the file walk.
        Runnable doZip = () -> com.micatechnologies.minecraft.launcher.utilities.FxAsyncTask.run(
                () -> com.micatechnologies.minecraft.launcher.game.modpack.ModpackExporter.exportToZip(
                        pack, dest, includeWorlds, null ),
                () -> NotificationManager.success(
                        com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager.format(
                                "detailModal.exportPack.success", displayName ),
                        com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager.format(
                                "detailModal.exportPack.successBody", dest.getAbsolutePath() ) ),
                err -> NotificationManager.error(
                        com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager.get(
                                "detailModal.exportPack.failed" ),
                        com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager.format(
                                "detailModal.exportPack.failedBody", err.getMessage() ) ) );
        if ( pack.isVanillaVersion() ) {
            doZip.run();
        }
        else {
            NotificationManager.info(
                    com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager.get(
                            "detailModal.exportPack.verifying" ),
                    displayName );
            com.micatechnologies.minecraft.launcher.utilities.VerifyAction.runForPacks(
                    java.util.List.of( pack ), doZip );
        }
    }

    /**
     * Builds the Stats section: last played, total play time, launch count, the
     * installed pack version (annotated with the available remote version when an
     * update is pending), and the manifest URL. All of these involve disk / manifest
     * reads, so the section mounts a spinner placeholder synchronously and replaces
     * it with the real rows once the data is gathered off the FX thread.
     *
     * @param pack the pack whose play stats and version info are shown
     * @return the section {@link VBox}, initially showing a loading placeholder
     */
    private Node buildStatsSection( GameModPack pack )
    {
        VBox section = buildSectionBox(
                com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager.get( "detailModal.section.stats" ) );

        // Stats reads pack.getLastPlayedFormatted (ensureHistoryLoaded
        // reads play-history from disk), getInstalledVersion (file
        // read), isUpdateAvailable (manifest comparison). Defer the
        // whole bundle off the FX thread, render rows once the data
        // lands.
        HBox placeholder = new HBox( 8 );
        placeholder.setAlignment( Pos.CENTER_LEFT );
        placeholder.setPadding( new Insets( 4, 0, 4, 0 ) );
        javafx.scene.control.ProgressIndicator spinner = new javafx.scene.control.ProgressIndicator();
        spinner.setPrefSize( 16, 16 );
        spinner.setMaxSize( 16, 16 );
        Label loadingLabel = new Label(
                com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager.get( "detailModal.section.loading" ) );
        loadingLabel.getStyleClass().add( "muted" );
        placeholder.getChildren().addAll( spinner, loadingLabel );
        section.getChildren().add( placeholder );

        SystemUtilities.spawnNewTask( () -> {
            String lastPlayed = pack.getLastPlayedFormatted();
            String totalPlayTime = pack.getTotalPlayTimeFormatted();
            long launchCount = pack.getLaunchCount();
            String installed = pack.getInstalledVersion();
            String remote = pack.getPackVersion();
            boolean updateAvailable = pack.isUpdateAvailable();
            String manifestUrl = pack.getManifestUrl();

            javafx.application.Platform.runLater( () -> {
                section.getChildren().remove( placeholder );
                VBox rows = new VBox( 4 );
                rows.getChildren().add( buildStatRow(
                        com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager.get( "detailModal.stats.lastPlayed" ),
                        lastPlayed ) );
                rows.getChildren().add( buildStatRow(
                        com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager.get( "detailModal.stats.totalPlayTime" ),
                        totalPlayTime ) );
                rows.getChildren().add( buildStatRow(
                        com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager.get( "detailModal.stats.launches" ),
                        String.valueOf( launchCount ) ) );
                if ( installed != null && !installed.isBlank() ) {
                    String installedDisplay = updateAvailable && remote != null && !remote.equals( installed )
                            ? com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager.format(
                                    "detailModal.stats.installedDisplay.withUpdate", installed, remote )
                            : installed;
                    rows.getChildren().add( buildStatRow(
                            com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager.get( "detailModal.stats.installedVersion" ),
                            installedDisplay ) );
                }
                if ( manifestUrl != null && !manifestUrl.isBlank() ) {
                    rows.getChildren().add( buildStatRow(
                            com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager.get( "detailModal.stats.manifest" ),
                            manifestUrl ) );
                }
                section.getChildren().add( rows );
            } );
        } );
        return section;
    }

    /**
     * Builds the Update Log section: a "what's new" pending-changes card (when an
     * update is available) atop a chronological list of the most recent
     * {@link #MAX_LOG_ENTRIES_SHOWN} packVersion-change entries, with a "+N more"
     * footer when the log is longer. Both the log read and the pending-update diff
     * are file/manifest I/O, so the section mounts a spinner placeholder and fills
     * in the rows off the FX thread. Empty logs show a vanilla- or modded-specific
     * empty-state message.
     *
     * @param pack the pack whose update log is rendered
     * @return the section {@link VBox}, initially showing a loading placeholder
     */
    private Node buildUpdateLogSection( GameModPack pack )
    {
        VBox section = buildSectionBox( com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager.get( "detailModal.section.updateLog" ) );

        // ModPackUpdateLog.readEntries does file I/O (reads the per-pack
        // update-log file). Defer it off the FX thread the same way the
        // content-browser sections do — keeps the modal opening time
        // bounded by the cheap header construction, not the filesystem.
        HBox placeholder = new HBox( 8 );
        placeholder.setAlignment( Pos.CENTER_LEFT );
        placeholder.setPadding( new Insets( 4, 0, 4, 0 ) );
        javafx.scene.control.ProgressIndicator spinner = new javafx.scene.control.ProgressIndicator();
        spinner.setPrefSize( 16, 16 );
        spinner.setMaxSize( 16, 16 );
        Label loadingLabel = new Label(
                com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager.get( "detailModal.section.loading" ) );
        loadingLabel.getStyleClass().add( "muted" );
        placeholder.getChildren().addAll( spinner, loadingLabel );
        section.getChildren().add( placeholder );

        SystemUtilities.spawnNewTask( () -> {
            List< ModPackUpdateLog.Entry > entries;
            try {
                entries = ModPackUpdateLog.readEntries( pack );
            }
            catch ( Throwable t ) {
                Logger.logWarningSilent( LocalizationManager.format( "log.modpackDetail.updateLogReadFailed", t.getClass().getSimpleName() ) );
                entries = java.util.Collections.emptyList();
            }
            // When an update is pending, diff the manifest's mods against what's installed so we can
            // show an at-a-glance "what's new" summary. Best-effort; null when not applicable.
            com.micatechnologies.minecraft.launcher.game.modpack.PendingUpdateDiff.Result diff = null;
            try {
                if ( pack.isUpdateAvailable() ) {
                    diff = com.micatechnologies.minecraft.launcher.game.modpack.PendingUpdateDiff.compute( pack );
                }
            }
            catch ( Throwable t ) {
                Logger.logWarningSilent( LocalizationManager.format( "log.modpackDetail.pendingDiffFailed", t.getClass().getSimpleName() ) );
            }
            final List< ModPackUpdateLog.Entry > finalEntries = entries;
            final com.micatechnologies.minecraft.launcher.game.modpack.PendingUpdateDiff.Result finalDiff = diff;
            javafx.application.Platform.runLater( () -> {
                section.getChildren().remove( placeholder );
                if ( finalDiff != null && !finalDiff.isEmpty() ) {
                    section.getChildren().add( buildPendingChangesNode( pack, finalDiff ) );
                }
                if ( finalEntries.isEmpty() ) {
                    Label empty = new Label(
                            pack.isVanillaVersion()
                                    ? LocalizationManager.get( "modal.updateLog.empty.vanilla" )
                                    : LocalizationManager.get( "modal.updateLog.empty.modded" ) );
                    empty.setWrapText( true );
                    empty.getStyleClass().add( "muted" );
                    section.getChildren().add( empty );
                    return;
                }
                VBox list = new VBox( 4 );
                int shown = Math.min( finalEntries.size(), MAX_LOG_ENTRIES_SHOWN );
                for ( int i = 0; i < shown; i++ ) {
                    list.getChildren().add( buildUpdateLogRow( finalEntries.get( i ) ) );
                }
                if ( finalEntries.size() > MAX_LOG_ENTRIES_SHOWN ) {
                    Label more = new Label( LocalizationManager.format( "modal.updateLog.more",
                                                    finalEntries.size() - MAX_LOG_ENTRIES_SHOWN ) );
                    more.getStyleClass().add( "muted" );
                    list.getChildren().add( more );
                }
                section.getChildren().add( list );
            } );
        } );
        return section;
    }

    /** "What's new" summary card shown atop the update log when a manifest update is pending —
     *  a one-line add/update/remove count derived from {@link com.micatechnologies.minecraft.launcher.game.modpack.PendingUpdateDiff},
     *  with a few example mod names per category.
     *
     *  @param pack the pack whose pending update is being summarized (its
     *              {@code packVersion} appears in the header)
     *  @param diff the precomputed, non-empty add/update/remove diff to render
     *  @return the summary card {@link Node} */
    private Node buildPendingChangesNode(
            GameModPack pack,
            com.micatechnologies.minecraft.launcher.game.modpack.PendingUpdateDiff.Result diff )
    {
        VBox box = new VBox( 4 );
        box.getStyleClass().add( "modpackDetailSection" );
        box.setPadding( new Insets( 10 ) );

        Label header = new Label(
                com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager.format(
                        "detailModal.whatsNew.header", pack.getPackVersion() == null ? "?" : pack.getPackVersion() ) );
        header.getStyleClass().add( "modpackDetailContentName" );
        box.getChildren().add( header );

        java.util.List< String > parts = new java.util.ArrayList<>();
        if ( diff.added() > 0 ) {
            parts.add( com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager
                               .format( "detailModal.whatsNew.added", diff.added() ) );
        }
        if ( diff.updated() > 0 ) {
            parts.add( com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager
                               .format( "detailModal.whatsNew.updated", diff.updated() ) );
        }
        if ( diff.removed() > 0 ) {
            parts.add( com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager
                               .format( "detailModal.whatsNew.removed", diff.removed() ) );
        }
        Label summary = new Label( String.join( "  ·  ", parts ) );
        summary.setWrapText( true );
        box.getChildren().add( summary );

        addExampleNames( box, "detailModal.whatsNew.addedNames", diff.addedNames() );
        addExampleNames( box, "detailModal.whatsNew.updatedNames", diff.updatedNames() );
        addExampleNames( box, "detailModal.whatsNew.removedNames", diff.removedNames() );

        Label note = new Label( com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager
                                        .get( "detailModal.whatsNew.note" ) );
        note.getStyleClass().add( "muted" );
        note.setWrapText( true );
        box.getChildren().add( note );
        return box;
    }

    /** Appends a muted "Added: a, b, c (+N more)" line for one diff category, capped to keep the
     *  card compact. No-op for an empty list.
     *
     *  @param box      the container the line is appended to
     *  @param labelKey the localization key for the line template (takes the joined names)
     *  @param names    the category's mod names; capped at 6 with a "+N more" suffix when longer */
    private void addExampleNames( VBox box, String labelKey, java.util.List< String > names )
    {
        if ( names == null || names.isEmpty() ) {
            return;
        }
        final int cap = 6;
        java.util.List< String > shown = names.size() > cap ? names.subList( 0, cap ) : names;
        String joined = String.join( ", ", shown );
        if ( names.size() > cap ) {
            joined += com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager
                    .format( "detailModal.whatsNew.more", names.size() - cap );
        }
        Label lbl = new Label(
                com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager
                        .format( labelKey, joined ) );
        lbl.getStyleClass().add( "muted" );
        lbl.setWrapText( true );
        box.getChildren().add( lbl );
    }

    /**
     * Advanced section — currently houses the per-pack "Always verify game
     * files on launch" toggle and the "Verify this pack now" button. Designed
     * to grow over time as other per-pack power-user knobs (custom RAM,
     * custom JVM args, etc.) land.
     *
     * <p>Starts expanded (it's the only section on the Advanced tab, so gating it
     * behind a click is pointless), but still lazy-populates via
     * {@link #registerOnFirstExpand} — which fires immediately for an
     * already-expanded section, so the controls construct when the Advanced tab
     * is first opened rather than at modal-open.
     *
     * @param pack the pack the advanced controls operate on
     * @return the section {@link VBox}
     */
    private Node buildAdvancedSection( GameModPack pack )
    {
        // Expanded by default — Advanced is its own tab now, so there's no extra
        // scroll to protect. The MFXComboBox / MFXToggleButton / MFXButton
        // construction + ConfigManager reads still defer to the lazy-populate
        // (fires on first expand, i.e. when the tab is first shown).
        VBox section = buildSectionBox( com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager.get( "detailModal.section.advanced" ), true );

        registerOnFirstExpand( section, () -> buildAdvancedSectionBody( section, pack ) );
        return section;
    }

    /** Body of the Advanced section. Extracted so it can be deferred
     *  via {@link #registerOnFirstExpand} — the section header alone
     *  is what renders at modal open, content only loads if the user
     *  clicks to expand.
     *
     *  @param section the already-constructed Advanced section the controls are
     *                 appended to
     *  @param pack    the pack the controls read from and write back to via
     *                 {@link ConfigManager} */
    private void buildAdvancedSectionBody( VBox section, GameModPack pack )
    {
        Label hint = new Label( LocalizationManager.get( "modal.advanced.hint" ) );
        hint.setWrapText( true );
        hint.getStyleClass().add( "muted" );
        hint.setStyle( "-fx-font-size: 11px;" );
        section.getChildren().add( hint );

        // Toggle: always verify on launch.
        // Stored per-pack-URL via ConfigManager. Default OFF — fast-path
        // eligible — matches the design choice from the 3.3 question pass.
        io.github.palexdev.materialfx.controls.MFXToggleButton alwaysVerifyToggle =
                new io.github.palexdev.materialfx.controls.MFXToggleButton(
                        LocalizationManager.get( "modal.advanced.alwaysVerify" ) );
        alwaysVerifyToggle.setSelected(
                ConfigManager.getAlwaysVerifyOnLaunch( pack.getManifestUrl() ) );
        alwaysVerifyToggle.selectedProperty().addListener( ( obs, oldVal, newVal ) ->
                ConfigManager.setAlwaysVerifyOnLaunch( pack.getManifestUrl(),
                                                       Boolean.TRUE.equals( newVal ) ) );
        Label toggleHint = new Label( LocalizationManager.get( "modal.advanced.alwaysVerify.hint" ) );
        toggleHint.setWrapText( true );
        toggleHint.getStyleClass().add( "subtle" );
        toggleHint.setStyle( "-fx-font-size: 11px;" );
        section.getChildren().add( alwaysVerifyToggle );
        section.getChildren().add( toggleHint );

        // Per-pack scan-frequency override. First entry maps to "null override"
        // (== use global default); the rest map 1:1 to the enum values. Keyed
        // by display label so user-facing copy can be edited without shifting
        // indices in stored configs.
        final String USE_GLOBAL = "Use global default";
        io.github.palexdev.materialfx.controls.MFXComboBox< String > scanFreqCombo =
                new io.github.palexdev.materialfx.controls.MFXComboBox<>();
        com.micatechnologies.minecraft.launcher.game.modpack.ScanFrequency[] freqValues =
                com.micatechnologies.minecraft.launcher.game.modpack.ScanFrequency.values();
        java.util.List< String > scanFreqLabels = new java.util.ArrayList<>();
        scanFreqLabels.add( USE_GLOBAL );
        for ( var f : freqValues ) scanFreqLabels.add( f.displayLabel() );
        scanFreqCombo.setItems( javafx.collections.FXCollections.observableArrayList( scanFreqLabels ) );
        com.micatechnologies.minecraft.launcher.game.modpack.ScanFrequency override =
                ConfigManager.getScanFrequencyForPack( pack.getManifestUrl() );
        scanFreqCombo.selectItem( override == null ? USE_GLOBAL : override.displayLabel() );
        scanFreqCombo.setMinHeight( 36 );
        scanFreqCombo.setPrefHeight( 36 );
        scanFreqCombo.setPrefWidth( 280 );
        scanFreqCombo.setOnAction( e -> {
            String selected = scanFreqCombo.getSelectedItem();
            if ( selected == null ) return;
            if ( USE_GLOBAL.equals( selected ) ) {
                ConfigManager.setScanFrequencyForPack( pack.getManifestUrl(), null );
                return;
            }
            for ( var f : freqValues ) {
                if ( f.displayLabel().equals( selected ) ) {
                    ConfigManager.setScanFrequencyForPack( pack.getManifestUrl(), f );
                    break;
                }
            }
        } );
        Label scanFreqLabel = new Label( LocalizationManager.get( "modal.advanced.scanFreq.label" ) );
        scanFreqLabel.setStyle( "-fx-font-size: 12px;" );
        Label scanFreqHint = new Label( LocalizationManager.get( "modal.advanced.scanFreq.hint" ) );
        scanFreqHint.setWrapText( true );
        scanFreqHint.getStyleClass().add( "subtle" );
        scanFreqHint.setStyle( "-fx-font-size: 11px;" );
        section.getChildren().add( scanFreqLabel );
        section.getChildren().add( scanFreqCombo );
        section.getChildren().add( scanFreqHint );

        // Acknowledged scan findings — read-only view of what the pack's
        // manifest silences. Surfaces in the UI so a user can see what the
        // pack maintainer has chosen not to block on (and why) without
        // having to read the raw JSON manifest. Skipped entirely when the
        // pack has no acknowledgements — most packs won't.
        java.util.List< com.micatechnologies.minecraft.launcher.game.modpack.ScanAcknowledgement > acks =
                pack.getPackScanAcknowledgements();
        if ( acks != null && !acks.isEmpty() ) {
            section.getChildren().add( buildAcknowledgementsList( acks ) );
        }

        // Button: verify this pack now.
        // Runs a force-FULL verify in the background using the new launch
        // progress GUI for the per-step display.
        MFXButton verifyNowBtn = new MFXButton( LocalizationManager.get( "modal.advanced.verifyNow" ) );
        verifyNowBtn.getStyleClass().add( "modpackDetailSecondaryBtn" );
        verifyNowBtn.setMinHeight( 32 );
        verifyNowBtn.setPrefHeight( 32 );
        verifyNowBtn.setOnAction( e -> {
            hide();
            com.micatechnologies.minecraft.launcher.utilities.VerifyAction.runForPacks(
                    java.util.List.of( pack ) );
        } );
        Label verifyHint = new Label( LocalizationManager.get( "modal.advanced.verifyNow.hint" ) );
        verifyHint.setWrapText( true );
        verifyHint.getStyleClass().add( "subtle" );
        verifyHint.setStyle( "-fx-font-size: 11px;" );
        HBox verifyRow = new HBox( 8, verifyNowBtn );
        verifyRow.setAlignment( Pos.CENTER_LEFT );
        section.getChildren().add( verifyRow );
        section.getChildren().add( verifyHint );

        // Button: Add to Official Minecraft Launcher.
        // Creates / refreshes a Mojang-launcher profile for this pack so
        // users can play from Minecraft's own UI. Detail-modal placement
        // pairs with the main-GUI right-click menu item — both flows call
        // through to the same handleAddToOfficialLauncher helper.
        //
        // Phase 5 partner: when a Mica-owned profile already exists for
        // this pack, swap the Add button for a Remove button + helper text
        // so the user can back out cleanly. We pick exactly one of the two
        // to surface so the section doesn't grow visually busy.
        if ( pack.getPackRootFolder() != null ) {
            boolean exported = com.micatechnologies.minecraft.launcher.game.modpack
                    .OfficialLauncherExporter.hasExportedProfile( pack );

            if ( exported ) {
                MFXButton removeBtn = new MFXButton(
                        LocalizationManager.get( "officialExport.remove.menuItem" ) );
                removeBtn.getStyleClass().add( "modpackDetailSecondaryBtn" );
                removeBtn.setMinHeight( 32 );
                removeBtn.setPrefHeight( 32 );
                removeBtn.setOnAction( e -> {
                    Stage owner = MCLauncherGuiController.getTopStageOrNull();
                    hide();
                    MCLauncherMainGui.handleRemoveFromOfficialLauncher( pack, owner );
                } );
                Label removeHint = new Label(
                        LocalizationManager.get( "modal.advanced.removeOfficial.hint" ) );
                removeHint.setWrapText( true );
                removeHint.getStyleClass().add( "subtle" );
                removeHint.setStyle( "-fx-font-size: 11px;" );
                HBox removeRow = new HBox( 8, removeBtn );
                removeRow.setAlignment( Pos.CENTER_LEFT );
                section.getChildren().add( removeRow );
                section.getChildren().add( removeHint );
            }
            else {
                MFXButton addToOfficialBtn = new MFXButton(
                        LocalizationManager.get( "officialExport.menuItem" ) );
                addToOfficialBtn.getStyleClass().add( "modpackDetailSecondaryBtn" );
                addToOfficialBtn.setMinHeight( 32 );
                addToOfficialBtn.setPrefHeight( 32 );
                addToOfficialBtn.setOnAction( e -> {
                    Stage owner = MCLauncherGuiController.getTopStageOrNull();
                    hide();
                    MCLauncherMainGui.handleAddToOfficialLauncher( pack, owner );
                } );
                Label addToOfficialHint = new Label(
                        LocalizationManager.get( "modal.advanced.addOfficial.hint" ) );
                addToOfficialHint.setWrapText( true );
                addToOfficialHint.getStyleClass().add( "subtle" );
                addToOfficialHint.setStyle( "-fx-font-size: 11px;" );
                HBox addToOfficialRow = new HBox( 8, addToOfficialBtn );
                addToOfficialRow.setAlignment( Pos.CENTER_LEFT );
                section.getChildren().add( addToOfficialRow );
                section.getChildren().add( addToOfficialHint );
            }
        }
    }

    /**
     * Builds a read-only list of the pack manifest's
     * {@code packScanAcknowledgements} entries. Each row shows the rule kind,
     * the locator (class.method or inner-JAR entry path), and the maintainer's
     * stated reason. A short hint above the list calls out that these are
     * defined by the pack maintainer and can't be edited from the launcher —
     * the manifest is the source of truth.
     *
     * <p>The fileSha256 is intentionally not surfaced inline: it's a 64-char
     * hex string that adds visual noise without informing the typical user.
     * It's exposed as a tooltip on each row for the curious / auditing case.</p>
     */
    private Node buildAcknowledgementsList(
            java.util.List< com.micatechnologies.minecraft.launcher.game.modpack.ScanAcknowledgement > acks )
    {
        VBox container = new VBox( 6 );

        Label heading = new Label( LocalizationManager.get( "modal.ack.heading" ) );
        heading.setStyle( "-fx-font-size: 12px; -fx-font-weight: bold;" );
        container.getChildren().add( heading );

        Label intro = new Label( LocalizationManager.get( "modal.ack.intro" ) );
        intro.setWrapText( true );
        intro.getStyleClass().add( "subtle" );
        intro.setStyle( "-fx-font-size: 11px;" );
        container.getChildren().add( intro );

        VBox list = new VBox( 4 );
        list.setStyle( "-fx-padding: 6 0 0 0;" );
        for ( com.micatechnologies.minecraft.launcher.game.modpack.ScanAcknowledgement a : acks ) {
            if ( a == null ) continue;
            list.getChildren().add( buildAcknowledgementRow( a ) );
        }
        container.getChildren().add( list );

        return container;
    }

    /**
     * One row of the acknowledgements list. Layout:
     *
     * <pre>
     *   ⚠  KIND_NAME · class.method
     *      Maintainer's reason text wraps here on long entries…
     * </pre>
     *
     * <p>The fileSha256 is wired up as a hover tooltip — useful for someone
     * auditing the manifest against an out-of-band file hash, but not worth
     * spending a row's worth of vertical space on by default.</p>
     */
    private Node buildAcknowledgementRow(
            com.micatechnologies.minecraft.launcher.game.modpack.ScanAcknowledgement a )
    {
        VBox row = new VBox( 2 );
        row.setStyle( "-fx-background-color: -color-surface-hover;"
                              + " -fx-background-radius: 6;"
                              + " -fx-padding: 6 10 6 10;" );

        // Header: kind + locator. Kind in bold so the reader can scan the
        // column at a glance; locator dimmed since it's a debugger-style
        // detail (class.method or inner JAR path).
        HBox header = new HBox( 8 );
        header.setAlignment( Pos.CENTER_LEFT );
        Label kindLabel = new Label( a.kind != null ? a.kind : LocalizationManager.get( "modal.ack.unknownRule" ) );
        kindLabel.setStyle( "-fx-font-weight: bold; -fx-font-size: 11px;" );
        header.getChildren().add( kindLabel );
        if ( a.locator != null && !a.locator.isBlank() ) {
            Label sep = new Label( "·" );
            sep.getStyleClass().add( "subtle" );
            sep.setStyle( "-fx-font-size: 11px;" );
            Label locatorLabel = new Label( a.locator );
            locatorLabel.getStyleClass().add( "subtle" );
            locatorLabel.setStyle( "-fx-font-size: 11px;" );
            header.getChildren().addAll( sep, locatorLabel );
        }
        row.getChildren().add( header );

        // Reason — bold-not-bold contrast keeps the most important text
        // (why this is OK) at full opacity, with the technical identity
        // recessed above it.
        if ( a.reason != null && !a.reason.isBlank() ) {
            Label reasonLabel = new Label( a.reason );
            reasonLabel.setWrapText( true );
            reasonLabel.setStyle( "-fx-font-size: 11px;" );
            row.getChildren().add( reasonLabel );
        }
        else {
            Label noReason = new Label( LocalizationManager.get( "modal.ack.noReason" ) );
            noReason.getStyleClass().add( "subtle" );
            noReason.setStyle( "-fx-font-size: 11px; -fx-font-style: italic;" );
            row.getChildren().add( noReason );
        }

        // SHA-256 as a hover tooltip. The full 64-char hex is too noisy to
        // bake into the visual row but useful for auditors comparing the
        // manifest's claim against a freshly computed hash of the JAR.
        if ( a.fileSha256 != null && !a.fileSha256.isBlank() ) {
            TooltipManager.install( row,
                    LocalizationManager.format( "tooltip.modal.fileSha", a.fileSha256 ) );
        }

        return row;
    }

    /**
     * News section — renders the pack's manifest-authored {@code packNews}
     * entries (see {@link com.micatechnologies.minecraft.launcher.game.modpack.NewsItem}).
     * Only built when the pack ships visible news (the caller gates on
     * {@code getVisibleNews()}). Opening the modal marks the visible news as
     * seen, clearing the hero-card unread badge.
     *
     * @param news the precomputed, non-empty list of visible news items
     */
    private Node buildNewsSection( GameModPack pack,
            java.util.List< com.micatechnologies.minecraft.launcher.game.modpack.NewsItem > news )
    {
        VBox section = buildSectionBox( LocalizationManager.get( "detailModal.section.news" ) );
        VBox list = new VBox( 10 );
        for ( com.micatechnologies.minecraft.launcher.game.modpack.NewsItem item : news ) {
            list.getChildren().add( buildNewsCard( item ) );
        }
        section.getChildren().add( list );

        // Mark seen off the FX thread — opening the modal counts as having seen
        // the pack's news, so the unread badge clears on next main-menu render.
        SystemUtilities.spawnNewTask( () -> {
            try {
                pack.markAllNewsSeen();
            }
            catch ( Throwable t ) {
                Logger.logWarningSilent( LocalizationManager.format(
                        "log.modpackDetail.newsMarkSeenFailed", t.getClass().getSimpleName() ) );
            }
        } );
        return section;
    }

    /** One news entry rendered as a compact card: a type glyph + title header,
     *  an optional date, the plain-text body, and an optional validated
     *  "Read more" link. */
    private Node buildNewsCard( com.micatechnologies.minecraft.launcher.game.modpack.NewsItem item )
    {
        VBox card = new VBox( 4 );
        card.getStyleClass().add( "modpackDetailSection" );
        card.setPadding( new Insets( 10 ) );

        // Header — type chip (glyph only, colour by type) + bold title.
        HBox header = new HBox( 8 );
        header.setAlignment( Pos.CENTER_LEFT );
        Label typeChip = buildChip( item.getType().glyph(), item.getType().chipStyleClass() );
        Label title = new Label( item.getTitle() );
        title.getStyleClass().add( "modpackDetailContentName" );
        title.setWrapText( true );
        HBox.setHgrow( title, Priority.ALWAYS );
        header.getChildren().addAll( typeChip, title );
        card.getChildren().add( header );

        // Date — muted, secondary.
        String displayDate = item.getDisplayDate();
        if ( displayDate != null ) {
            Label date = new Label( displayDate );
            date.getStyleClass().add( "muted" );
            date.setStyle( "-fx-font-size: 11px;" );
            card.getChildren().add( date );
        }

        // Body — plain text, never interpreted as markup.
        String body = item.getBody();
        if ( body != null ) {
            Label bodyLabel = new Label( body );
            bodyLabel.setWrapText( true );
            card.getChildren().add( bodyLabel );
        }

        // Read more — only when the manifest supplied a valid http(s) URL.
        String url = item.getUrl();
        if ( url != null ) {
            javafx.scene.control.Hyperlink link =
                    new javafx.scene.control.Hyperlink( LocalizationManager.get( "modal.news.readMore" ) );
            link.setOnAction( e -> openExternalUrl( url ) );
            card.getChildren().add( link );
        }

        return card;
    }

    /**
     * Links section — renders the pack's manifest-authored {@code packLinks}
     * (see {@link com.micatechnologies.minecraft.launcher.game.modpack.LinkItem}).
     * Only built when the pack ships visible links (the caller gates on
     * {@code getVisibleLinks()}).
     *
     * @param links the precomputed, non-empty list of renderable links
     */
    private Node buildLinksSection(
            java.util.List< com.micatechnologies.minecraft.launcher.game.modpack.LinkItem > links )
    {
        VBox section = buildSectionBox( LocalizationManager.get( "detailModal.section.links" ) );
        VBox list = new VBox( 10 );
        for ( com.micatechnologies.minecraft.launcher.game.modpack.LinkItem link : links ) {
            list.getChildren().add( buildLinkCard( link ) );
        }
        section.getChildren().add( list );
        return section;
    }

    /** One link rendered as a compact card: a clickable title (opens the URL)
     *  with an optional plain-text description beneath. The URL is already
     *  http(s)-validated by {@link com.micatechnologies.minecraft.launcher.game.modpack.LinkItem#getUrl()}. */
    private Node buildLinkCard( com.micatechnologies.minecraft.launcher.game.modpack.LinkItem link )
    {
        VBox card = new VBox( 2 );
        card.getStyleClass().add( "modpackDetailSection" );
        card.setPadding( new Insets( 10 ) );

        final String url = link.getUrl();
        javafx.scene.control.Hyperlink title = new javafx.scene.control.Hyperlink( link.getTitle() );
        title.getStyleClass().add( "modpackDetailContentName" );
        title.setWrapText( true );
        title.setOnAction( e -> openExternalUrl( url ) );
        card.getChildren().add( title );

        String description = link.getDescription();
        if ( description != null ) {
            Label desc = new Label( description );
            desc.setWrapText( true );
            desc.getStyleClass().add( "muted" );
            desc.setStyle( "-fx-font-size: 11px;" );
            card.getChildren().add( desc );
        }

        return card;
    }

    /** Opens an http(s) URL in the user's browser via the shared
     *  {@link GUIUtilities#openExternalUrl(String)} helper (validates scheme,
     *  runs off the FX thread). */
    private static void openExternalUrl( String url )
    {
        GUIUtilities.openExternalUrl( url );
    }

    /**
     * Builds the sticky action row pinned below the scrollable body: a flex-growing
     * Play button (the primary action; disabled when {@link AnnouncementManager}
     * reports gameplay is globally disabled) and a fixed-width Website button
     * (disabled when the pack declares no {@code packURL}). Both buttons are pinned
     * to an identical height so the Play button's heavier styling doesn't make it
     * taller than its neighbour.
     *
     * @param pack the pack the Play / Website actions operate on
     * @return the assembled action-row {@link HBox}
     */
    private HBox buildActionRow( GameModPack pack )
    {
        HBox actions = new HBox( 10 );
        actions.setAlignment( Pos.CENTER_LEFT );
        actions.setPadding( new Insets( 14, 24, 18, 24 ) );
        actions.getStyleClass().add( "modpackDetailActions" );

        // Pin both buttons to identical heights via min + pref + max so the
        // .modpackDetailPlayBtn rule's bigger font + heavier padding doesn't make
        // Play taller than its Website neighbour. Same fix the hero card buttons
        // received — see the BTN_H comment in MCLauncherMainGui.ModpackHeroCard.
        final double BTN_H = 42;

        MFXButton playBtn = new MFXButton( LocalizationManager.get( "common.button.play" ) );
        playBtn.getStyleClass().addAll( "primary", "modpackDetailPlayBtn" );
        playBtn.setMinHeight( BTN_H );
        playBtn.setPrefHeight( BTN_H );
        playBtn.setMaxHeight( BTN_H );
        HBox.setHgrow( playBtn, Priority.ALWAYS );
        playBtn.setMaxWidth( Double.MAX_VALUE );
        playBtn.setDisable( AnnouncementManager.getDisableGameplay() );
        playBtn.setOnAction( e -> {
            hide();
            startPlay( pack );
        } );

        MFXButton websiteBtn = new MFXButton( LocalizationManager.get( "common.button.website" ) );
        websiteBtn.getStyleClass().add( "modpackDetailSecondaryBtn" );
        websiteBtn.setMinHeight( BTN_H );
        websiteBtn.setPrefHeight( BTN_H );
        websiteBtn.setMaxHeight( BTN_H );
        websiteBtn.setPrefWidth( 110 );
        websiteBtn.setOnAction( e -> openModpackWebsite( pack ) );
        if ( pack.getPackURL() == null || pack.getPackURL().isBlank() ) {
            websiteBtn.setDisable( true );
        }

        actions.getChildren().addAll( playBtn, websiteBtn );
        return actions;
    }

    // =============================================================================
    //  Small builders / helpers
    // =============================================================================

    /**
     * Convenience overload that builds a section box which starts expanded. Equivalent
     * to {@link #buildSectionBox(String, boolean)} with {@code defaultExpanded == true}.
     *
     * @param heading section title shown in the header
     * @return the expanded section {@link VBox} (header plus room for content rows)
     */
    private VBox buildSectionBox( String heading )
    {
        return buildSectionBox( heading, true );
    }

    /**
     * Section VBox with a click-to-toggle header. The returned VBox is
     * an outer container whose first child is the {@code header} HBox
     * (chevron + title); subsequent children added by section builders
     * are the "content" rows.
     *
     * <p>When the section is collapsed, its content children have
     * {@code visible=false, managed=false} so they take zero layout
     * space — the section shrinks to just the header row. A
     * {@link javafx.collections.ListChangeListener} on the section's
     * child list keeps this state consistent for children added
     * AFTER the user has toggled: the modal's content-browser
     * sections populate themselves asynchronously, so the rows
     * arrive minutes (or seconds) after construction; the listener
     * makes sure they respect the current expand state.</p>
     *
     * @param heading         section title shown in the header
     * @param defaultExpanded {@code true} to start expanded;
     *                        {@code false} to start collapsed so the
     *                        user has to click to load. Sections
     *                        likely to contain a lot of rows (mods,
     *                        screenshots, crash history) default to
     *                        collapsed to keep the modal scroll
     *                        manageable for large packs.
     */
    /** Section property key holding a {@code List<Runnable>} of
     *  callbacks fired the FIRST time the user expands a collapsed
     *  section. Pre-collapsed sections (Mods, Screenshots, Crash
     *  History, Advanced) skip their expensive populate work at
     *  construction time and stash it here instead, so the user only
     *  pays the cost of sections they actually open. Single-fire:
     *  collapsing + re-expanding doesn't run the callbacks again
     *  (the property is removed after fire). */
    public static final String LAZY_POPULATE_KEY = "mmcl.firstExpandRunnables";

    /** Registers {@code action} to fire the first time the user
     *  expands {@code section}. If the section is already expanded
     *  (or it's not a section built by {@link #buildSectionBox(String, boolean)}),
     *  fires immediately. Multiple registrations stack — all
     *  registered runnables fire in registration order on the same
     *  expand. */
    public static void registerOnFirstExpand( VBox section, Runnable action )
    {
        if ( section == null || action == null ) return;
        @SuppressWarnings( "unchecked" )
        java.util.List< Runnable > list =
                ( java.util.List< Runnable > ) section.getProperties().get( LAZY_POPULATE_KEY );
        if ( list == null ) {
            // Section is already expanded (or pre-expanded sections
            // don't get a stash list — see buildSectionBox). Fire now.
            action.run();
            return;
        }
        list.add( action );
    }

    /**
     * Builds the "Problems" section listing files the audit log flags as re-downloading on
     * every recent launch. Only constructed when {@code problems} is non-empty (see
     * {@link #populateBodyContent}). Each row names the file and explains the likely cause —
     * an identical re-fetch points at a manifest hash that doesn't match the served bytes.
     */
    private VBox buildProblemsSection( java.util.List< ModPackAuditLog.Problem > problems )
    {
        VBox section = buildSectionBox( LocalizationManager.get( "detailModal.section.problems" ), true );
        for ( ModPackAuditLog.Problem p : problems ) {
            VBox row = new VBox( 2 );
            row.getStyleClass().add( "modpackDetailProblemRow" );

            Label head = new Label( LocalizationManager.format( "detailModal.problems.redownload",
                                                                p.file(), p.consecutiveLaunches() ) );
            head.getStyleClass().add( "modpackDetailProblemTitle" );
            head.setStyle( "-fx-font-weight: bold;" );
            head.setWrapText( true );

            Label detail = new Label( LocalizationManager.get(
                    p.contentUnchanged() ? "detailModal.problems.unchanged" : "detailModal.problems.changed" ) );
            detail.getStyleClass().add( "modpackDetailProblemDetail" );
            detail.setWrapText( true );
            detail.setOpacity( 0.85 );

            row.getChildren().addAll( head, detail );
            section.getChildren().add( row );
        }
        return section;
    }

    /**
     * Builds a section VBox with a click-to-toggle header. The returned VBox is an
     * outer container whose first child is the header HBox (chevron + title);
     * subsequent children added by section builders are the "content" rows.
     *
     * <p>When collapsed, the content children are set {@code visible=false,
     * managed=false} so they take zero layout space and the section shrinks to just
     * the header. A {@link javafx.collections.ListChangeListener} on the section's
     * child list keeps this state consistent for rows added AFTER a toggle: the
     * content-browser sections populate themselves asynchronously, so their rows
     * arrive seconds after construction, and the listener ensures they respect the
     * current expand state.
     *
     * <p>Pre-collapsed sections also receive an empty {@link #LAZY_POPULATE_KEY}
     * stash so {@link #registerOnFirstExpand} can queue deferred populate work that
     * fires once, the first time the user expands the section.
     *
     * @param heading         section title shown in the header
     * @param defaultExpanded {@code true} to start expanded; {@code false} to start
     *                        collapsed so the user has to click to load. Sections
     *                        likely to contain many rows (mods, screenshots, crash
     *                        history) default to collapsed to keep the modal scroll
     *                        manageable for large packs.
     * @return the assembled section {@link VBox} (header at index 0, content rows
     *         appended by the caller)
     */
    private VBox buildSectionBox( String heading, boolean defaultExpanded )
    {
        VBox section = new VBox( 8 );
        section.getStyleClass().add( "modpackDetailSection" );

        final boolean[] expanded = { defaultExpanded };

        Label chevron = new Label( defaultExpanded ? "▾" : "▸" );
        chevron.getStyleClass().add( "modpackDetailSectionChevron" );
        Label title = new Label( heading );
        title.getStyleClass().add( "modpackDetailSectionHeading" );
        HBox header = new HBox( 8, chevron, title );
        header.setAlignment( Pos.CENTER_LEFT );
        header.setCursor( javafx.scene.Cursor.HAND );

        // Pre-collapsed sections get an empty lazy-populate stash so
        // registerOnFirstExpand has somewhere to queue runnables. The
        // pre-expanded path leaves the property absent so
        // registerOnFirstExpand fires immediately (matches its docs).
        if ( !defaultExpanded ) {
            section.getProperties().put( LAZY_POPULATE_KEY, new java.util.ArrayList< Runnable >() );
        }

        Runnable applyState = () -> {
            chevron.setText( expanded[ 0 ] ? "▾" : "▸" );
            // Skip index 0 (the header itself).
            for ( int i = 1; i < section.getChildren().size(); i++ ) {
                javafx.scene.Node child = section.getChildren().get( i );
                child.setVisible( expanded[ 0 ] );
                child.setManaged( expanded[ 0 ] );
            }
        };
        header.setOnMouseClicked( e -> {
            expanded[ 0 ] = !expanded[ 0 ];
            // First-expand fire: drain any registered populate hooks.
            // Removed atomically so a re-expand later doesn't re-fire
            // them (and so registerOnFirstExpand called after this
            // point falls into the "already expanded → fire now" path).
            if ( expanded[ 0 ] ) {
                @SuppressWarnings( "unchecked" )
                java.util.List< Runnable > pending =
                        ( java.util.List< Runnable > ) section.getProperties().remove( LAZY_POPULATE_KEY );
                if ( pending != null ) {
                    for ( Runnable r : pending ) {
                        try { r.run(); }
                        catch ( Throwable t ) {
                            Logger.logWarningSilent( LocalizationManager.format( "log.modpackDetail.lazyPopulateFailed",
                                                             t.getClass().getSimpleName() ) );
                        }
                    }
                }
            }
            applyState.run();
        } );
        section.getChildren().add( header );

        // Auto-hide newly-appended children while collapsed — the
        // content-browser sections populate themselves via async
        // background scans + Platform.runLater, so rows arrive AFTER
        // construction. Without this listener a collapsed Mods
        // section would show its rows the moment the scan finished
        // because the original setVisible(false) only applied to
        // children present at toggle time.
        section.getChildren().addListener( ( javafx.collections.ListChangeListener< javafx.scene.Node > ) c -> {
            while ( c.next() ) {
                if ( c.wasAdded() && !expanded[ 0 ] ) {
                    for ( javafx.scene.Node n : c.getAddedSubList() ) {
                        if ( n != header ) {
                            n.setVisible( false );
                            n.setManaged( false );
                        }
                    }
                }
            }
        } );
        return section;
    }

    /**
     * Builds a small pill-style {@link Label} ("chip") carrying the given text and
     * style classes — used for the hero badges and the stat-chip metadata row.
     *
     * @param text    the chip's display text
     * @param classes zero or more CSS style classes to add to the chip (e.g.
     *                {@code "stat-chip"}, {@code "stat-chip-warn"})
     * @return the styled chip {@link Label}
     */
    private Label buildChip( String text, String... classes )
    {
        Label chip = new Label( text );
        for ( String c : classes ) chip.getStyleClass().add( c );
        return chip;
    }

    /**
     * Builds one Quick Actions tile button. The supplied action is wrapped in a
     * try/catch so a failing action (e.g. an IO error opening a folder) logs and is
     * swallowed rather than propagating out of the FX event handler.
     *
     * @param text   the button's display text
     * @param action the work to run when the button is clicked; any thrown
     *               {@link Throwable} is logged and suppressed
     * @return the configured {@link MFXButton}
     */
    private MFXButton buildQuickActionBtn( String text, Runnable action )
    {
        MFXButton btn = new MFXButton( text );
        btn.getStyleClass().add( "modpackDetailQuickAction" );
        btn.setPrefHeight( 34 );
        btn.setOnAction( e -> {
            try { action.run(); }
            catch ( Throwable t ) {
                Logger.logErrorSilent( LocalizationManager.format( "log.modpackDetail.quickActionFailed", t.getMessage() ) );
                Logger.logThrowable( t );
            }
        } );
        return btn;
    }

    /**
     * Builds a single key/value row for the Stats section: a fixed-width label
     * column on the left and a flex-growing, wrapping value on the right. A null or
     * blank value renders as an em-dash placeholder.
     *
     * @param label the row's key text (left column)
     * @param value the row's value text (right column); rendered as {@code "—"} when
     *              null or blank
     * @return the assembled stat row {@link HBox}
     */
    private HBox buildStatRow( String label, String value )
    {
        HBox row = new HBox( 8 );
        row.setAlignment( Pos.CENTER_LEFT );
        Label k = new Label( label );
        k.getStyleClass().add( "modpackDetailStatKey" );
        k.setMinWidth( 140 );
        Label v = new Label( value != null && !value.isBlank() ? value : "—" );
        v.getStyleClass().add( "modpackDetailStatValue" );
        v.setWrapText( true );
        HBox.setHgrow( v, Priority.ALWAYS );
        row.getChildren().addAll( k, v );
        return row;
    }

    /**
     * Builds a single Update Log row: the entry's formatted timestamp on the left and
     * an {@code v<old> → v<new>} version-change arrow on the right.
     *
     * @param e the update-log entry to render
     * @return the assembled log row {@link HBox}
     */
    private HBox buildUpdateLogRow( ModPackUpdateLog.Entry e )
    {
        HBox row = new HBox( 10 );
        row.setAlignment( Pos.CENTER_LEFT );
        row.getStyleClass().add( "modpackDetailLogRow" );

        Label date = new Label( ModPackUpdateLog.formatTimestamp( e ) );
        date.getStyleClass().add( "modpackDetailLogDate" );
        date.setMinWidth( 96 );

        Label arrow = new Label( "v" + e.oldVersion() + "  →  v" + e.newVersion() );
        arrow.getStyleClass().add( "modpackDetailLogArrow" );

        row.getChildren().addAll( date, arrow );
        return row;
    }

    // =============================================================================
    //  Pack helpers (mirror the ones in MCLauncherMainGui so the modal stays
    //  self-contained — the small duplication is preferable to making private
    //  helpers package-visible across the gui package).
    // =============================================================================

    /**
     * Resolves the pack's logo image for the hero, mirroring the hero card's
     * resolution rules: the on-disk cached logo file when present, otherwise the
     * bundled default-logo URL. Loaded in background mode so the modal doesn't block
     * on a cold image fetch. Returns {@code null} only if even the default fails to
     * construct.
     *
     * @param pack the pack whose logo is resolved
     * @return the resolved logo {@link Image}, or {@code null} when none could be
     *         constructed
     */
    private static Image resolveLogoImage( GameModPack pack )
    {
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

    /**
     * Resolves the hero background image URL for the pack, or {@code null} when the
     * pack has no custom background.
     *
     * @param pack the pack
     *
     * @return a usable background URL, or {@code null}
     */
    private static String resolveBackgroundUrl( GameModPack pack )
    {
        try {
            if ( !pack.hasCustomBackground() ) return null;
            String path = pack.getPackBackgroundFilepath();
            if ( path != null ) {
                File f = new File( path );
                if ( f.exists() && f.length() > 0 ) return f.toURI().toString();
            }
        }
        catch ( Exception ignored ) { /* fall through */ }
        return null;
    }

    /**
     * Returns the pack's display name for the modal header.
     *
     * @param pack the pack
     *
     * @return the display name
     */
    private static String resolveDisplayName( GameModPack pack )
    {
        if ( pack.isVanillaVersion() ) {
            String name = pack.getPackName();
            if ( name != null && !name.isBlank() ) return name;
        }
        String name = pack.getFriendlyName();
        if ( name == null || name.isBlank() ) name = pack.getPackName();
        return name != null ? name : LocalizationManager.get( "modal.unnamedPack" );
    }

    /** Counts mods on the pack metadata, swallowing exceptions for vanilla / null cases. */
    private static int safePackModCount( GameModPack pack )
    {
        try {
            // Reflectively access the mod list via the metadata accessor — vanilla packs
            // get an empty list assigned at createVanillaModPack time, modded packs get
            // whatever the manifest declared. A null packMods (older manifests without
            // the field at all) is treated as zero.
            java.lang.reflect.Field f = pack.getClass().getSuperclass().getDeclaredField( "packMods" );
            f.setAccessible( true );
            Object mods = f.get( pack );
            if ( mods instanceof java.util.Collection< ? > c ) {
                return c.size();
            }
        }
        catch ( Exception ignored ) { /* fall through */ }
        return 0;
    }

    /** Wraps a value supplier in a try/catch so resolution exceptions don't break the
     *  modal build. Returns null on failure. */
    private static < T > T safeCall( Supplier< T > supplier )
    {
        try { return supplier.get(); }
        catch ( Exception ignored ) { return null; }
    }

    /**
     * Returns the pack's Minecraft version, or {@code null} if it can't be resolved.
     *
     * @param pack the pack
     *
     * @return the Minecraft version, or {@code null}
     */
    private static String safeMinecraftVersion( GameModPack pack )
    {
        try { return pack.getMinecraftVersion(); }
        catch ( Exception ignored ) { return null; }
    }

    /**
     * Returns the pack's modloader name, or {@code null} if it can't be resolved.
     *
     * @param pack the pack
     *
     * @return the loader name, or {@code null}
     */
    private static String safeLoaderName( GameModPack pack )
    {
        try { return pack.getLoaderName(); }
        catch ( Exception ignored ) { return null; }
    }

    /**
     * Returns the pack's modloader version, or {@code null} if it can't be resolved.
     *
     * @param pack the pack
     *
     * @return the loader version, or {@code null}
     */
    private static String safeLoaderVersion( GameModPack pack )
    {
        try { return pack.getLoaderVersion(); }
        catch ( Exception ignored ) { return null; }
    }

    // =============================================================================
    //  Action wiring (these mirror what's in MCLauncherMainGui's context-menu
    //  helpers; consolidating them in a shared utility is a future refactor).
    // =============================================================================

    private static void startPlay( GameModPack pack )
    {
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
                    Logger.logError(
                            LocalizationManager.get( "log.modpackDetail.loadMainGuiFailed" ) );
                    Logger.logThrowable( e );
                    LauncherCore.closeApp();
                }
            } ) );
        } );
    }

    /**
     * Opens the pack's website in the browser via
     * {@link GUIUtilities#openModpackWebsite(GameModPack)}.
     *
     * @param pack the pack whose website to open
     */
    private static void openModpackWebsite( GameModPack pack )
    {
        GUIUtilities.openModpackWebsite( pack );
    }

    /**
     * Opens the given subfolder of the pack's install directory in the OS file
     * browser.
     *
     * @param pack      the pack
     * @param subfolder the subfolder name relative to the pack root
     */
    private static void openPackSubfolder( GameModPack pack, String subfolder )
    {
        SystemUtilities.spawnNewTask( () -> {
            try {
                String path = pack.getPackRootFolder();
                if ( !subfolder.isEmpty() ) {
                    path += File.separator + subfolder;
                }
                File folder = new File( path );
                if ( !folder.exists() ) {
                    //noinspection ResultOfMethodCallIgnored
                    folder.mkdirs();
                }
                Desktop.getDesktop().open( folder );
            }
            catch ( Exception ex ) {
                Logger.logWarningSilent( LocalizationManager.format( "log.modpack.openFolderFailed", ex.getMessage() ) );
            }
        } );
    }

    /**
     * Creates a desktop shortcut that launches the given pack directly.
     *
     * @param pack the pack to create a shortcut for
     */
    private static void createDesktopShortcut( GameModPack pack )
    {
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
                        GUIUtilities.themeAlertChrome( alert );
                        alert.showAndWait();
                    } );
                }
            }
            catch ( Exception ex ) {
                Logger.logError( LocalizationManager.format( "log.modpack.createShortcutFailed", ex.getMessage() ) );
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

    /**
     * Copies an {@code mmcl://} invite/deep-link for the pack to the system
     * clipboard.
     *
     * @param pack the pack to build an invite link for
     */
    private static void copyInviteLinkToClipboard( GameModPack pack )
    {
        String invite = DiscordRpcUtility.buildInviteLinkFromPack( pack );
        if ( invite == null ) {
            NotificationManager.warn( LocalizationManager.get( "notification.detail.noInviteLink.title" ),
                                      LocalizationManager.get( "notification.detail.noInviteLink.body" ) );
            return;
        }
        GUIUtilities.JFXPlatformRun( () -> {
            javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
            content.putString( invite );
            javafx.scene.input.Clipboard.getSystemClipboard().setContent( content );
            NotificationManager.success(
                    com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager.get( "notification.invite.copied.title" ),
                    com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager.get( "notification.invite.copied.body" ) );
        } );
    }

}
