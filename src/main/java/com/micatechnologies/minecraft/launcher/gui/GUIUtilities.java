/*
 * Copyright (c) 2021 Mica Technologies
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
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.utilities.SensitiveDataRedactor;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Class containing utilities for the GUI subsystem.
 *
 * @author Mica Technologies
 * @version 2.0
 * @since 1.0
 */
public class GUIUtilities
{
    /**
     * Show a question message prompt to user as a dialog using specified information.
     *
     * @param title       dialog title
     * @param headerText  dialog header
     * @param contentText dialog content/question text
     * @param button1     button 1 text
     * @param button2     button 2 text
     *
     * @return 0 for cancel, 1 for button 1, 2 for button 2
     *
     * @since 1.0
     */
    public static int showQuestionMessage( String title,
                                           String headerText,
                                           String contentText,
                                           String button1,
                                           String button2,
                                           Stage owner )
    {
        // Create a question dialog with the specified and created information/messages
        CountDownLatch waitForResponse = new CountDownLatch( 1 );
        AtomicInteger index = new AtomicInteger( 0 );
        JFXPlatformRun( () -> {
            Alert questionAlert = new Alert( Alert.AlertType.CONFIRMATION );
            questionAlert.setTitle( title );
            questionAlert.setHeaderText( headerText );
            questionAlert.setContentText( sanitizeDialogText( contentText ) );
            questionAlert.initStyle( StageStyle.UTILITY );
            questionAlert.initModality( Modality.WINDOW_MODAL );
            questionAlert.initOwner( owner );

            // If the caller's button2 already IS the cancel label, fold it into the
            // dialog's CANCEL_CLOSE button instead of adding a separate one — otherwise
            // dialogs like the mmcl:// untrusted-host prompt end up with TWO Cancel
            // buttons (the explicit "Cancel" label + the auto-added one). When button2
            // is genuinely a second non-cancel choice ("Close Without Saving",
            // "Uninstall (Keep Files)", etc.), we still want the separate Cancel as
            // the escape hatch.
            ButtonType btn1 = new ButtonType( button1 );
            boolean button2IsCancel = button2 != null
                    && button2.trim().equalsIgnoreCase( "Cancel" );
            ButtonType btn2 = button2IsCancel
                    ? new ButtonType( button2, ButtonBar.ButtonData.CANCEL_CLOSE )
                    : new ButtonType( button2 );

            if ( button2IsCancel ) {
                questionAlert.getButtonTypes().setAll( btn1, btn2 );
            }
            else {
                ButtonType btnC = new ButtonType( "Cancel", ButtonBar.ButtonData.CANCEL_CLOSE );
                questionAlert.getButtonTypes().setAll( btn1, btn2, btnC );
            }

            // Show the created question dialog
            themeAlertChrome( questionAlert );
            Optional< ButtonType > opt = questionAlert.showAndWait();
            if ( opt.isPresent() && opt.get() == btn1 ) {
                index.set( 1 );
            }
            else if ( opt.isPresent() && opt.get() == btn2 ) {
                index.set( 2 );
            }

            // Release code from waiting
            waitForResponse.countDown();
        } );

        // Wait for question to be acknowledged
        try {
            waitForResponse.await();
        }
        catch ( InterruptedException e ) {
            // Show error for unable to wait for error acknowledge
            JFXPlatformRun( () -> {
                Alert errorAlert = new Alert( Alert.AlertType.ERROR );
                errorAlert.setTitle( "Something's Wrong" );
                errorAlert.setHeaderText( "Application Error" );
                errorAlert.setContentText( "A question message latch was interrupted before handling completed." );
                errorAlert.initStyle( StageStyle.UTILITY );
                errorAlert.initModality( Modality.WINDOW_MODAL );
                errorAlert.initOwner( owner );

                // Show the created error
                themeAlertChrome( errorAlert );
            errorAlert.showAndWait();
            } );
        }

        return index.get();
    }

    /**
     * Multi-line counterpart of {@link #showQuestionMessage}. Used for
     * deliberately-structured prompts (CurseForge / Modrinth import preview
     * with multi-line summary, future "review changes before applying"
     * confirmations) where the single-line {@link #sanitizeDialogText} would
     * flatten the layout into an unreadable run-on. Dialog pane widens and
     * the content label is reconfigured to size itself off the wrapped text,
     * mirroring {@link #showErrorMessageMultiline}.
     *
     * @return 0 for cancel, 1 for button 1, 2 for button 2
     *
     * @since 2026.3
     */
    public static int showQuestionMessageMultiline( String title,
                                                     String headerText,
                                                     String contentText,
                                                     String button1,
                                                     String button2,
                                                     Stage owner )
    {
        CountDownLatch waitForResponse = new CountDownLatch( 1 );
        AtomicInteger index = new AtomicInteger( 0 );
        JFXPlatformRun( () -> {
            Alert questionAlert = new Alert( Alert.AlertType.CONFIRMATION );
            questionAlert.setTitle( title );
            questionAlert.setHeaderText( headerText );
            questionAlert.setContentText( sanitizeDialogTextMultiline( contentText ) );
            questionAlert.initStyle( StageStyle.UTILITY );
            questionAlert.initModality( Modality.WINDOW_MODAL );
            questionAlert.initOwner( owner );

            ButtonType btn1 = new ButtonType( button1 );
            boolean button2IsCancel = button2 != null
                    && button2.trim().equalsIgnoreCase( "Cancel" );
            ButtonType btn2 = button2IsCancel
                    ? new ButtonType( button2, ButtonBar.ButtonData.CANCEL_CLOSE )
                    : new ButtonType( button2 );

            if ( button2IsCancel ) {
                questionAlert.getButtonTypes().setAll( btn1, btn2 );
            }
            else {
                ButtonType btnC = new ButtonType( "Cancel", ButtonBar.ButtonData.CANCEL_CLOSE );
                questionAlert.getButtonTypes().setAll( btn1, btn2, btnC );
            }

            // Widen the dialog so summary lines don't wrap aggressively, and
            // unbind the content label's preferred width so multi-line wrapped
            // text actually grows the dialog vertically. Same recipe as
            // showErrorMessageMultiline — kept inline rather than factored out
            // because the dialog-pane lookup is a JavaFX runtime quirk that
            // would otherwise leak into a "helper" with no other consumer.
            questionAlert.getDialogPane().setMinWidth( 560 );
            questionAlert.getDialogPane().setPrefWidth( 640 );
            javafx.scene.Node contentLabel = questionAlert.getDialogPane().lookup( ".content.label" );
            if ( contentLabel instanceof javafx.scene.control.Label l ) {
                l.setWrapText( true );
                l.setMinHeight( javafx.scene.layout.Region.USE_PREF_SIZE );
                l.setPrefWidth( 600 );
            }

            themeAlertChrome( questionAlert );
            Optional< ButtonType > opt = questionAlert.showAndWait();
            if ( opt.isPresent() && opt.get() == btn1 ) {
                index.set( 1 );
            }
            else if ( opt.isPresent() && opt.get() == btn2 ) {
                index.set( 2 );
            }
            waitForResponse.countDown();
        } );

        try {
            waitForResponse.await();
        }
        catch ( InterruptedException e ) {
            Thread.currentThread().interrupt();
        }
        return index.get();
    }

    /**
     * Show an error message dialog to user with specified information.
     *
     * @param contentText dialog content/error text
     *
     * @since 1.0
     */
    public static void showErrorMessage( String contentText, Stage owner ) {
        // Create an error with the specified and created information/messages
        CountDownLatch waitForError = new CountDownLatch( 1 );
        JFXPlatformRun( () -> {
            Alert errorAlert = new Alert( Alert.AlertType.ERROR );
            errorAlert.setTitle( "Oops" );
            errorAlert.setHeaderText( "Error" );
            errorAlert.setContentText( sanitizeDialogText( contentText ) );
            errorAlert.initModality( Modality.WINDOW_MODAL );
            errorAlert.initStyle( StageStyle.UTILITY );
            errorAlert.initOwner( owner );

            // Show the created error
            themeAlertChrome( errorAlert );
            errorAlert.showAndWait();

            // Release code from waiting
            waitForError.countDown();
        } );

        // Wait for error to be acknowledged
        try {
            waitForError.await();
        }
        catch ( InterruptedException e ) {
            // Show error for unable to wait for error acknowledge
            JFXPlatformRun( () -> {
                Alert errorAlert = new Alert( Alert.AlertType.ERROR );
                errorAlert.setTitle( "Something's Wrong" );
                errorAlert.setHeaderText( "Application Error" );
                errorAlert.setContentText( "An error message latch was interrupted before handling completed." );
                errorAlert.initModality( Modality.WINDOW_MODAL );
                errorAlert.initStyle( StageStyle.UTILITY );
                errorAlert.initOwner( owner );

                // Show the created error
                themeAlertChrome( errorAlert );
            errorAlert.showAndWait();
            } );
        }
    }

    /**
     * Variant of {@link #showErrorMessage} that preserves newlines so the
     * caller's structure (bullet lists, paragraph breaks, etc.) survives all
     * the way to the rendered Alert. Use this when the message text was
     * built deliberately — the scan-blocked popup with its per-finding
     * bullets is the canonical case.
     *
     * <p>Implementation notes: the JavaFX {@link Alert} content area is a
     * {@code Label} with wrapping on; setting a {@code \n}-bearing string
     * preserves the lines, but the dialog pane defaults to a width too
     * narrow for long file-path bullets, so this variant also widens the
     * pane and unbinds the label's preferred width to let JavaFX size it
     * to the text. Bypassing the single-line {@link #sanitizeDialogText}
     * is the whole point — newlines stay, the cap is larger, redaction
     * still runs.</p>
     */
    public static void showErrorMessageMultiline( String contentText, Stage owner ) {
        CountDownLatch waitForError = new CountDownLatch( 1 );
        JFXPlatformRun( () -> {
            Alert errorAlert = new Alert( Alert.AlertType.ERROR );
            errorAlert.setTitle( "Oops" );
            errorAlert.setHeaderText( "Error" );
            errorAlert.setContentText( sanitizeDialogTextMultiline( contentText ) );
            errorAlert.initModality( Modality.WINDOW_MODAL );
            errorAlert.initStyle( StageStyle.UTILITY );
            errorAlert.initOwner( owner );

            // Give the dialog room. The default Alert width is sized for one-liners;
            // a bulleted list of paths needs more horizontal space before wrapping
            // makes the lines look like an unstructured run-on.
            errorAlert.getDialogPane().setMinWidth( 560 );
            errorAlert.getDialogPane().setPrefWidth( 640 );
            // Let the content label compute its own preferred size from the wrapped
            // multi-line text, otherwise JavaFX clips below the first 2-3 lines.
            javafx.scene.Node contentLabel = errorAlert.getDialogPane().lookup( ".content.label" );
            if ( contentLabel instanceof javafx.scene.control.Label l ) {
                l.setWrapText( true );
                l.setMinHeight( javafx.scene.layout.Region.USE_PREF_SIZE );
                l.setPrefWidth( 600 );
            }

            themeAlertChrome( errorAlert );
            errorAlert.showAndWait();
            waitForError.countDown();
        } );

        try {
            waitForError.await();
        }
        catch ( InterruptedException e ) {
            Thread.currentThread().interrupt();
        }
    }

    /** Maximum length of caller-supplied text that the launcher will display in an
     *  Alert content area. Anything longer is truncated with an ellipsis — overlong
     *  text typically means a stack-trace dump was passed as contentText, which is
     *  also where information disclosure would happen (internal paths, type names). */
    private static final int MAX_DIALOG_CONTENT_CHARS = 600;

    /** Larger length cap for the multi-line variant. Structured error text the
     *  launcher constructs (scan-blocked listings, multi-finding repair reports)
     *  legitimately needs more room than a one-liner. Still bounded to keep a
     *  rogue caller from pasting a 100K stack trace into the dialog. */
    private static final int MAX_DIALOG_CONTENT_CHARS_MULTILINE = 4000;

    /**
     * Returns a defanged copy of {@code contentText} suitable for display in an
     * {@link Alert}. Strips embedded access tokens via {@link SensitiveDataRedactor},
     * collapses any sequence of newlines into a single space so multi-line stack
     * traces compress to one readable line, and truncates the result so a leaked
     * stack-trace dump can't push past the dialog's visible bounds. Empty/null
     * input passes through unchanged.
     */
    private static String sanitizeDialogText( String contentText )
    {
        if ( contentText == null || contentText.isEmpty() ) {
            return contentText;
        }
        String redacted = SensitiveDataRedactor.redact( contentText );
        // Collapse newlines + carriage returns to spaces, then squeeze runs of
        // whitespace so a wrapped stack frame reads as one continuous line.
        String oneLine = redacted.replace( '\n', ' ' )
                                  .replace( '\r', ' ' )
                                  .replaceAll( "\\s+", " " )
                                  .trim();
        if ( oneLine.length() > MAX_DIALOG_CONTENT_CHARS ) {
            return oneLine.substring( 0, MAX_DIALOG_CONTENT_CHARS - 1 ) + "…";
        }
        return oneLine;
    }

    /**
     * Multi-line counterpart of {@link #sanitizeDialogText}. Keeps newlines
     * intact (callers that go through this entrypoint constructed their
     * message deliberately — e.g. the scan-blocked popup's bulleted list)
     * while still running redaction and a sensible character cap.
     *
     * <p>{@code \r} is normalized to nothing and trailing whitespace on each
     * line is trimmed so editor-style CRLF input doesn't leak double-spacing.
     * Runs of three or more blank lines collapse to two — a hard ceiling on
     * vertical sprawl without flattening intentional paragraph breaks.</p>
     */
    private static String sanitizeDialogTextMultiline( String contentText )
    {
        if ( contentText == null || contentText.isEmpty() ) {
            return contentText;
        }
        String redacted = SensitiveDataRedactor.redact( contentText );
        // Normalize line endings, strip per-line trailing whitespace, cap runs of blank lines.
        String normalized = redacted.replace( "\r\n", "\n" ).replace( '\r', '\n' );
        // Per-line right-trim using regex; multiline mode so $ anchors line ends.
        normalized = normalized.replaceAll( "(?m)[ \\t]+$", "" );
        // Cap any run of 3+ newlines down to 2 (a single blank line between paragraphs).
        normalized = normalized.replaceAll( "\n{3,}", "\n\n" ).trim();
        if ( normalized.length() > MAX_DIALOG_CONTENT_CHARS_MULTILINE ) {
            return normalized.substring( 0, MAX_DIALOG_CONTENT_CHARS_MULTILINE - 1 ) + "…";
        }
        return normalized;
    }

    /**
     * Show an error message dialog to user with specified information and retry option.
     *
     * @param contentText dialog content/error text
     *
     * @return true if retry, false if not
     *
     * @since 1.0
     */
    public static boolean showErrorMessageRetry( String contentText, Stage owner, String retryText ) {
        // Create an error with the specified and created information/messages
        CountDownLatch waitForError = new CountDownLatch( 1 );
        AtomicBoolean retry = new AtomicBoolean( false );
        JFXPlatformRun( () -> {
            Alert errorAlert = new Alert( Alert.AlertType.ERROR );
            errorAlert.setTitle( "Oops" );
            errorAlert.setHeaderText( "Error" );
            errorAlert.setContentText( sanitizeDialogText( contentText ) );
            // Use application-modal if the owner stage isn't visible to avoid force-showing hidden stages
            if ( owner != null && owner.isShowing() ) {
                errorAlert.initModality( Modality.WINDOW_MODAL );
                errorAlert.initOwner( owner );
            }
            else {
                errorAlert.initModality( Modality.APPLICATION_MODAL );
            }
            errorAlert.initStyle( StageStyle.UTILITY );

            ButtonType btn1 = new ButtonType( retryText, ButtonBar.ButtonData.BACK_PREVIOUS );
            ButtonType btnC = new ButtonType( "Cancel", ButtonBar.ButtonData.CANCEL_CLOSE );

            errorAlert.getButtonTypes().setAll( btn1, btnC );

            // Show the created error dialog
            themeAlertChrome( errorAlert );
            Optional< ButtonType > opt = errorAlert.showAndWait();
            if ( opt.isPresent() && opt.get() == btn1 ) {
                retry.set( true );
            }

            // Release code from waiting
            waitForError.countDown();
        } );

        // Wait for error to be acknowledged
        try {
            waitForError.await();
        }
        catch ( InterruptedException e ) {
            // Show error for unable to wait for error acknowledge
            JFXPlatformRun( () -> {
                Alert errorAlert = new Alert( Alert.AlertType.ERROR );
                errorAlert.setTitle( "Something's Wrong" );
                errorAlert.setHeaderText( "Application Error" );
                errorAlert.setContentText( "An error message latch was interrupted before handling completed." );
                errorAlert.initModality( Modality.WINDOW_MODAL );
                errorAlert.initStyle( StageStyle.UTILITY );
                errorAlert.initOwner( owner );

                // Show the created error
                themeAlertChrome( errorAlert );
            errorAlert.showAndWait();
            } );
        }
        return retry.get();
    }

    /**
     * Show a warning message dialog to user with specified information.
     *
     * @param contentText dialog content/warning text
     *
     * @since 1.0
     */
    public static void showWarningMessage( String contentText, Stage owner ) {
        // Create a warning with the specified and created information/messages
        CountDownLatch waitForWarning = new CountDownLatch( 1 );
        JFXPlatformRun( () -> {
            Alert warningAlert = new Alert( Alert.AlertType.WARNING );
            warningAlert.setTitle( "Warning" );
            warningAlert.setHeaderText( "Warning" );
            warningAlert.setContentText( sanitizeDialogText( contentText ) );
            warningAlert.initModality( Modality.WINDOW_MODAL );
            warningAlert.initStyle( StageStyle.UTILITY );
            warningAlert.initOwner( owner );

            // Show the created error
            themeAlertChrome( warningAlert );
            warningAlert.showAndWait();

            // Release code from waiting
            waitForWarning.countDown();
        } );

        // Wait for error to be acknowledged
        try {
            waitForWarning.await();
        }
        catch ( InterruptedException e ) {
            // Show error for unable to wait for error acknowledge
            JFXPlatformRun( () -> {
                Alert warningAlert = new Alert( Alert.AlertType.ERROR );
                warningAlert.setTitle( "Something's Wrong" );
                warningAlert.setHeaderText( "Application Error" );
                warningAlert.setContentText( "A warning message latch was interrupted before handling completed." );
                warningAlert.initModality( Modality.WINDOW_MODAL );
                warningAlert.initStyle( StageStyle.UTILITY );
                warningAlert.initOwner( owner );

                // Show the created error
                themeAlertChrome( warningAlert );
            warningAlert.showAndWait();
            } );
        }
    }

    /**
     * Executes the specified runnable on the JavaFX thread and waits for its completion.
     *
     * @param r runnable task
     *
     * @since 1.1
     */
    public static void JFXPlatformRun( Runnable r ) {
        // If currently on JavaFX thread, run the runnable
        if ( Platform.isFxApplicationThread() ) {
            r.run();
        }
        else {
            // Create latch for identifying completion of runnable
            final CountDownLatch doneLatch = new CountDownLatch( 1 );

            // Schedule runnable for execution on JavaFX thread
            try {
                Platform.runLater( () -> {
                    try {
                        r.run();
                    }
                    finally {
                        doneLatch.countDown();
                    }
                } );
            }
            catch ( Exception e ) {
                Platform.startup( () -> {
                    try {
                        r.run();
                    }
                    finally {
                        doneLatch.countDown();
                    }
                } );
            }

            // Wait for completion of runnable on JavaFX thread
            try {
                doneLatch.await();
            }
            catch ( InterruptedException e ) {
                // Some background threads (jthemedetecor's watcher, especially during a
                // Windows dark/light flip that fires multiple registry events) get
                // interrupted by their own framework mid-await. Surfacing that as a
                // modal error dialog is wrong — the FX work either completed already
                // or will complete shortly, and the calling thread just lost its right
                // to wait. Restore the interrupt flag for any caller that wants to act
                // on it, and log silently.
                Thread.currentThread().interrupt();
                Logger.logWarningSilent(
                        "Interrupted while awaiting FX task; continuing without blocking." );
            }
        }
    }

    /**
     * Applies the active launcher theme's title-bar chrome (DWM immersive-dark
     * on Windows, NSWindow setAppearance on macOS, no-op on Linux) to the
     * {@link Alert}'s Stage so the alert window's frame matches the rest of
     * the app instead of falling back to the OS default. Idempotent. Call
     * after the alert has been initStyle / initOwner-configured, before
     * {@code showAndWait()}.
     *
     * <p>The chrome flip is attached via {@code setOnShowing} rather than
     * applied directly because an Alert's Stage isn't fully realised until
     * the dialog is about to be shown — querying
     * {@code alert.getDialogPane().getScene().getWindow()} too early returns
     * null. setOnShowing fires after the Stage exists but before the user
     * sees the first frame, so the chrome lands at the right moment.
     *
     * @param alert the JavaFX Alert to theme; null is a no-op
     *
     * @since 3.5
     */
    public static void themeAlertChrome( Alert alert )
    {
        if ( alert == null ) return;
        alert.setOnShowing( ev -> {
            try {
                // Install the launcher theme stylesheets on the dialog's own scene so the
                // alert buttons / text / background paint in the active theme instead
                // of JavaFX's stock light gray. The DialogPane lives in a separate Scene
                // from the parent launcher window, so the main stage's stylesheets do
                // NOT propagate automatically — we have to install them on the dialog
                // root explicitly.
                javafx.scene.Parent dialogRoot = alert.getDialogPane();
                MCLauncherGuiWindow.installCurrentThemeStylesheets( dialogRoot );

                javafx.stage.Window w = dialogRoot.getScene().getWindow();
                if ( w instanceof Stage st ) {
                    String theme = ConfigManager.getTheme();
                    boolean lightChrome = ConfigConstants.THEME_LIGHT.equals( theme )
                            || ( ConfigConstants.THEME_NATIVE.equals( theme )
                                 && !isOsDarkSafe() );
                    com.micatechnologies.minecraft.launcher.utilities.WindowChromeManager
                            .applyTitleBarDarkMode( st, !lightChrome );
                }
            }
            catch ( Throwable t ) {
                Logger.logWarningSilent( "Alert chrome theming", t );
            }
        } );
    }

    /** Cheap wrapper around OsThemeDetector for the alert-chrome decision; same
     *  shape as the helper in MCLauncherHelpWindow / MCLauncherQuickStartWizard
     *  so a detector failure on a weird platform never blocks an alert from
     *  showing. */
    private static boolean isOsDarkSafe()
    {
        try {
            return com.jthemedetecor.OsThemeDetector.getDetector().isDark();
        }
        catch ( Throwable ignored ) {
            return true;
        }
    }
}
