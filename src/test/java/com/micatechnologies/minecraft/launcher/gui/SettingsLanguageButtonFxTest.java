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

import com.micatechnologies.minecraft.launcher.consts.localization.SupportedLocales;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E2E (TestFX) coverage for the Settings "Save &amp; Restart" interaction:
 * the button stays hidden until the language dropdown moves to a value
 * different from the saved override, then appears, and a robot click fires its
 * action. It drives a minimal stand-in scene (a {@link ComboBox} + the button)
 * wired to the <em>real</em> decision seam
 * {@link MCLauncherSettingsGui#isLanguageChangePending(String, String)} and the
 * same visible+managed toggling the live controller uses — exercising the FX
 * selection-listener → decision → visibility path through real JavaFX events,
 * without standing up the heavyweight Settings controller (ConfigManager /
 * Platform lifecycle), matching {@code TestFxSmokeTest}'s philosophy.
 *
 * <p>Gated behind {@code MMCL_RUN_TESTFX=true} like the rest of the TestFX
 * suite: it needs a display server, so CI (which doesn't set the var) skips it
 * cleanly while a developer on a workstation opts in.</p>
 */
@ExtendWith( ApplicationExtension.class )
@EnabledIfEnvironmentVariable( named = "MMCL_RUN_TESTFX", matches = "true" )
class SettingsLanguageButtonFxTest
{
    /** The OS-default dropdown entry's composite label (never a SupportedLocales
     *  display name), so it resolves to the empty override tag. */
    private static final String OS_DEFAULT_LABEL =
            SupportedLocales.OS_DEFAULT_LABEL_PREFIX + " (detected: English)";

    private ComboBox< String > languageCombo;
    private Button saveRestartBtn;
    private boolean restartRequested;

    /** Simulated persisted override — empty means "Use OS Language" is saved,
     *  matching a fresh install. The button is pending whenever the selection
     *  resolves to a different tag than this. */
    private final String savedOverrideTag = "";

    @Start
    private void start( Stage stage )
    {
        restartRequested = false;

        languageCombo = new ComboBox<>();
        languageCombo.setId( "language-combo" );
        languageCombo.getItems().add( OS_DEFAULT_LABEL );
        for ( SupportedLocales.Entry entry : SupportedLocales.ENTRIES ) {
            languageCombo.getItems().add( entry.displayName() );
        }
        // Start on the saved (OS-default) selection, mirroring the live dropdown.
        languageCombo.getSelectionModel().select( OS_DEFAULT_LABEL );

        saveRestartBtn = new Button( "Save & Restart" );
        saveRestartBtn.setId( "save-restart-btn" );
        // Hidden + unmanaged until a language change is pending — exactly the
        // controller's initial FXML state.
        saveRestartBtn.setVisible( false );
        saveRestartBtn.setManaged( false );
        saveRestartBtn.setOnAction( e -> restartRequested = true );

        // Same wiring the live controller installs: recompute visibility from the
        // real decision seam on every selection change.
        languageCombo.getSelectionModel().selectedItemProperty().addListener(
                ( obs, oldV, newV ) -> {
                    boolean pending = MCLauncherSettingsGui.isLanguageChangePending( newV, savedOverrideTag );
                    saveRestartBtn.setVisible( pending );
                    saveRestartBtn.setManaged( pending );
                } );

        VBox root = new VBox( 8, languageCombo, saveRestartBtn );
        stage.setScene( new Scene( root, 360, 140 ) );
        stage.show();
    }

    @Test
    void saveRestartButtonAppearsOnLanguageChangeAndFires( FxRobot robot )
    {
        // Initial state: saved == selected (OS default) → button hidden.
        assertFalse( saveRestartBtn.isVisible(), "button should start hidden with no pending change" );
        assertFalse( saveRestartBtn.isManaged(), "hidden button should take no layout space" );

        // Pick a different language. Driving selection on the FX thread fires the
        // real selectedItemProperty listener synchronously — same event the live
        // dropdown raises.
        robot.interact( () -> languageCombo.getSelectionModel().select( "Français" ) );
        assertTrue( saveRestartBtn.isVisible(), "button should appear once a language change is pending" );
        assertTrue( saveRestartBtn.isManaged(), "shown button should participate in layout" );

        // Robot click on the now-visible button fires its action (stands in for
        // the relaunch trigger).
        robot.clickOn( saveRestartBtn );
        assertTrue( restartRequested, "clicking Save & Restart should fire its action" );

        // Revert to the saved selection → button hides again.
        robot.interact( () -> languageCombo.getSelectionModel().select( OS_DEFAULT_LABEL ) );
        assertFalse( saveRestartBtn.isVisible(), "button should hide when selection returns to the saved value" );
        assertFalse( saveRestartBtn.isManaged() );
    }
}
