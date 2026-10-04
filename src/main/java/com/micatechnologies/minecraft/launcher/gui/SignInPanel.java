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
import io.github.palexdev.materialfx.controls.MFXButton;
import io.github.palexdev.materialfx.controls.MFXToggleButton;
import javafx.concurrent.Worker;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;
import javafx.scene.web.WebView;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The sign-in card shared by the full-screen login and the "Add account" window: an
 * information column beside Microsoft's own sign-in page.
 *
 * <p>The column says what is about to happen and why it is safe (the password goes to
 * Microsoft, the saved sign-in stays encrypted on this computer, more accounts can be added
 * later), holds the "Stay signed in" choice, and reports progress. The page itself sits in a
 * rounded frame with an overlay that covers the blank moments: while Microsoft's page loads,
 * while the launcher redeems the sign-in, and when the page can't be reached, with a retry.</p>
 *
 * <p>Build and use on the FX thread.</p>
 *
 * @since 2026.10
 */
final class SignInPanel
{
    // Icon paths from Google's Material Icons (Apache License 2.0), 24x24 viewbox.

    private final HBox             root;
    private final WebView          webView;
    private final MFXToggleButton  rememberToggle;
    private final MFXButton        dismissButton;
    private final Label            statusLabel;
    private final VBox             overlay;
    private final LoadingIndicator overlaySpinner;
    private final Label            overlayText;
    private final MFXButton        retryButton;
    private final AtomicBoolean    waiting = new AtomicBoolean( false );
    private       boolean          redeeming;

    /**
     * Builds the card.
     *
     * @param headingKey  localization key of the heading
     * @param subtitleKey localization key of the line under the heading
     * @param dismissKey  localization key of the dismiss button ("Exit", "Cancel")
     *
     * @since 2026.10
     */
    SignInPanel( String headingKey, String subtitleKey, String dismissKey )
    {
        // ---- Information column ----
        Label eyebrow = new Label( LocalizationManager.get( "signIn.eyebrow" ) );
        eyebrow.getStyleClass().add( "signInEyebrow" );
        HBox brand = new HBox( 10, brandLogo(), eyebrow );
        brand.setAlignment( Pos.CENTER_LEFT );

        Label heading = new Label( LocalizationManager.get( headingKey ) );
        heading.getStyleClass().add( "heading-h2" );
        heading.setWrapText( true );
        Label subtitle = new Label( LocalizationManager.get( subtitleKey ) );
        subtitle.getStyleClass().add( "muted" );
        subtitle.setWrapText( true );

        VBox points = new VBox( 16,
                point( LauncherIcons.SECURITY, "signIn.point.password.title", "signIn.point.password.body" ),
                point( LauncherIcons.LOCK, "signIn.point.device.title", "signIn.point.device.body" ),
                point( LauncherIcons.PEOPLE, "signIn.point.accounts.title", "signIn.point.accounts.body" ) );
        points.setPadding( new Insets( 8, 0, 0, 0 ) );

        Region grow = new Region();
        VBox.setVgrow( grow, Priority.ALWAYS );

        rememberToggle = new MFXToggleButton( LocalizationManager.get( "signIn.remember" ) );
        rememberToggle.setSelected( true );
        Label rememberHint = new Label( LocalizationManager.get( "signIn.remember.hint" ) );
        rememberHint.getStyleClass().add( "subtle" );
        rememberHint.setWrapText( true );
        VBox remember = new VBox( 2, rememberToggle, rememberHint );

        statusLabel = new Label();
        statusLabel.getStyleClass().add( "signInStatus" );
        statusLabel.setWrapText( true );
        statusLabel.setManaged( false );
        statusLabel.setVisible( false );

        dismissButton = new MFXButton( LocalizationManager.get( dismissKey ) );
        dismissButton.getStyleClass().add( "heroCardSecondaryBtn" );
        dismissButton.setPrefHeight( 32 );
        dismissButton.setMinWidth( 96 );

        VBox aside = new VBox( 14, brand, heading, subtitle, points, grow, remember, statusLabel, dismissButton );
        aside.getStyleClass().add( "signInAside" );
        aside.setPadding( new Insets( 32, 28, 28, 32 ) );
        aside.setMinWidth( 300 );
        aside.setPrefWidth( 330 );
        aside.setMaxWidth( 360 );

        // ---- Microsoft's page, framed ----
        webView = new WebView();
        webView.getEngine().setJavaScriptEnabled( true );
        webView.getEngine().setUserAgent( MicrosoftSignIn.USER_AGENT );
        webView.setMinWidth( 440 );
        webView.setPrefWidth( 520 );

        overlaySpinner = new LoadingIndicator();
        overlaySpinner.setPrefSize( 36, 36 );
        overlayText = new Label();
        overlayText.getStyleClass().add( "muted" );
        overlayText.setWrapText( true );
        overlayText.setMaxWidth( 320 );
        overlayText.setAlignment( Pos.CENTER );
        retryButton = new MFXButton( LocalizationManager.get( "signIn.retry" ) );
        retryButton.getStyleClass().add( "primary" );
        retryButton.setPrefHeight( 32 );
        retryButton.setVisible( false );
        retryButton.setManaged( false );
        overlay = new VBox( 14, overlaySpinner, overlayText, retryButton );
        overlay.setAlignment( Pos.CENTER );
        overlay.getStyleClass().add( "signInOverlay" );

        StackPane frame = new StackPane( webView, overlay );
        frame.getStyleClass().add( "signInFrame" );
        Rectangle clip = ShapeScale.round( new Rectangle(), ShapeScale.MEDIUM );
        clip.widthProperty().bind( frame.widthProperty() );
        clip.heightProperty().bind( frame.heightProperty() );
        frame.setClip( clip );
        HBox.setHgrow( frame, Priority.ALWAYS );
        HBox.setMargin( frame, new Insets( 12, 12, 12, 0 ) );

        root = new HBox( aside, frame );
        root.getStyleClass().addAll( "card", "elevated", "signInCard" );
        root.setMaxWidth( 1040 );

        watchPageLoads();
        SmoothScroll.install( webView );
    }

    /** @return the card, to place in a scene */
    Region root()
    {
        return root;
    }

    /** @return the embedded Microsoft page */
    WebView webView()
    {
        return webView;
    }

    /** @return the "Exit" / "Cancel" button, for the host to wire */
    MFXButton dismissButton()
    {
        return dismissButton;
    }

    /** @return whether the user chose to stay signed in */
    boolean remember()
    {
        return rememberToggle.isSelected();
    }

    /**
     * Calls back with the OAuth result each time the page completes a sign-in. Attach once.
     *
     * @param onCallback receives the code or error on the FX thread
     */
    void onCallback( java.util.function.Consumer< MicrosoftSignIn.Callback > onCallback )
    {
        MicrosoftSignIn.attach( webView, waiting, onCallback );
    }

    /**
     * (Re)loads Microsoft's sign-in page and clears any busy state.
     */
    void loadSignIn()
    {
        redeeming = false;
        rememberToggle.setDisable( false );
        waiting.set( true );
        showOverlay( LocalizationManager.get( "signIn.loading" ), false );
        webView.getEngine().load( MicrosoftSignIn.loginUrl() );
    }

    /**
     * Covers the page while the launcher redeems the sign-in with Microsoft.
     */
    void showRedeeming()
    {
        redeeming = true;
        rememberToggle.setDisable( true );
        clearStatus();
        showOverlay( LocalizationManager.get( "signIn.redeeming" ), false );
    }

    /**
     * Shows a problem under the column and reloads the sign-in page so the user can retry.
     *
     * @param messageKey localization key of the message
     */
    void failAndRetry( String messageKey )
    {
        statusLabel.setText( LocalizationManager.get( messageKey ) );
        statusLabel.setManaged( true );
        statusLabel.setVisible( true );
        loadSignIn();
    }

    private void clearStatus()
    {
        statusLabel.setText( "" );
        statusLabel.setManaged( false );
        statusLabel.setVisible( false );
    }

    /** Shows the overlay while Microsoft's page loads, hides it once a real page is up, and
     *  turns it into a retry prompt when the page can't be reached. */
    private void watchPageLoads()
    {
        retryButton.setOnAction( e -> {
            clearStatus();
            loadSignIn();
        } );
        webView.getEngine().getLoadWorker().stateProperty().addListener( ( obs, oldState, state ) -> {
            if ( redeeming ) {
                return;  // the page is blanked while redeeming; keep the redeeming message up
            }
            String location = webView.getEngine().getLocation();
            boolean blank = location == null || location.isEmpty() || "about:blank".equals( location );
            if ( state == Worker.State.SUCCEEDED && !blank ) {
                overlay.setVisible( false );
            }
            else if ( state == Worker.State.FAILED ) {
                Logger.logWarningSilent( LocalizationManager.get( "log.login.pageLoadFailed" ) );
                showOverlay( LocalizationManager.get( "signIn.loadFailed" ), true );
            }
        } );
    }

    private void showOverlay( String text, boolean offerRetry )
    {
        overlayText.setText( text );
        overlaySpinner.setVisible( !offerRetry );
        overlaySpinner.setManaged( !offerRetry );
        retryButton.setVisible( offerRetry );
        retryButton.setManaged( offerRetry );
        overlay.setVisible( true );
    }

    /** One reassurance point: an icon in a soft disc, a title and a sentence. */
    private static Node point( String iconPath, String titleKey, String bodyKey )
    {
        SVGPath icon = new SVGPath();
        icon.setContent( iconPath );
        icon.getStyleClass().add( "signInPointIconGlyph" );
        StackPane disc = new StackPane( icon );
        disc.getStyleClass().add( "signInPointIcon" );
        disc.setMinSize( 36, 36 );
        disc.setMaxSize( 36, 36 );

        Label title = new Label( LocalizationManager.get( titleKey ) );
        title.getStyleClass().add( "signInPointTitle" );
        title.setWrapText( true );
        Label body = new Label( LocalizationManager.get( bodyKey ) );
        body.getStyleClass().add( "muted" );
        body.setWrapText( true );
        VBox text = new VBox( 2, title, body );
        HBox.setHgrow( text, Priority.ALWAYS );

        HBox row = new HBox( 12, disc, text );
        row.setAlignment( Pos.TOP_LEFT );
        return row;
    }

    /** The Mica logo at 40 px, loaded from the shared FXML component. */
    private static Node brandLogo()
    {
        try {
            FXMLLoader loader = new FXMLLoader(
                    SignInPanel.class.getClassLoader().getResource( "gui/components/micaBrandLogo.fxml" ) );
            Node logo = loader.load();
            // The component draws at 80x84 (see mainGUI.fxml for why it needs a fixed-size
            // wrapper); halve it for the column.
            // A transform pivoting at the origin, not setScaleX/Y: those pivot on the node's
            // center, and this Group's untransformed bounds are the raw ~800 px path coords.
            logo.getTransforms().add( new javafx.scene.transform.Scale( 0.5, 0.5, 0, 0 ) );
            Pane wrapper = new Pane( logo );
            wrapper.setMinSize( 40, 42 );
            wrapper.setPrefSize( 40, 42 );
            wrapper.setMaxSize( 40, 42 );
            return wrapper;
        }
        catch ( Exception e ) {
            return new Region();
        }
    }
}
