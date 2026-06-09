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
import com.micatechnologies.minecraft.launcher.game.auth.MCLauncherAuthManager;
import com.micatechnologies.minecraft.launcher.game.auth.ProfileArchive;
import com.micatechnologies.minecraft.launcher.utilities.SystemUtilities;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.stage.Stage;

import java.util.List;

/**
 * Quick account switcher surfaced from the navbar / title-bar account lockup. Clicking the avatar or
 * player name pops this menu so the user can switch between previously-signed-in Microsoft accounts,
 * add another, or jump to the full account-management screen — without digging into Settings first.
 *
 * <p>Reuses the existing {@link ProfileArchive} storage and
 * {@link MCLauncherAuthManager#switchToArchivedProfile} / {@link MCLauncherAuthManager#archiveAndLogout}
 * flows (the same ones Settings → Account uses), including the launcher restart those require to
 * propagate the new identity across every screen.</p>
 *
 * @since 2026.6
 */
public final class AccountSwitcherMenu
{
    private AccountSwitcherMenu() { /* static-only */ }

    /**
     * Builds and shows the switcher menu anchored below {@code anchor}.
     *
     * @param anchor              the clicked node (avatar / name label) to anchor the popup to
     * @param openAccountSettings opens the full Settings → Account screen (the "Manage accounts" item)
     */
    public static void show( Node anchor, Runnable openAccountSettings )
    {
        if ( anchor == null ) {
            return;
        }
        ContextMenu menu = new ContextMenu();

        var active = MCLauncherAuthManager.getLoggedInUser();
        String activeName = active != null ? active.name() : null;
        String activeUuid = active != null ? active.uuid() : null;

        // Header — the currently-signed-in account (non-interactive).
        MenuItem header = new MenuItem( LocalizationManager.format( "account.switcher.signedInAs",
                                                                    activeName == null ? "?" : activeName ) );
        header.setDisable( true );
        menu.getItems().add( header );

        // One "Switch to X" item per archived profile (excluding the active one).
        List< ProfileArchive.ProfileEntry > profiles;
        try {
            profiles = ProfileArchive.list();
        }
        catch ( Throwable t ) {
            profiles = List.of();
        }
        boolean addedSeparator = false;
        for ( ProfileArchive.ProfileEntry p : profiles ) {
            if ( activeUuid != null && activeUuid.equals( p.uuid() ) ) {
                continue;
            }
            if ( !addedSeparator ) {
                menu.getItems().add( new SeparatorMenuItem() );
                addedSeparator = true;
            }
            String name = ( p.displayName() == null || p.displayName().isBlank() )
                    ? p.uuid() : p.displayName();
            MenuItem switchItem = new MenuItem(
                    LocalizationManager.format( "account.switcher.switchTo", name ) );
            switchItem.setOnAction( e -> SystemUtilities.spawnNewTask( () -> {
                boolean ok = MCLauncherAuthManager.switchToArchivedProfile( p.uuid() );
                if ( ok ) {
                    GUIUtilities.JFXPlatformRun( LauncherCore::restartApp );
                }
            } ) );
            menu.getItems().add( switchItem );
        }

        menu.getItems().add( new SeparatorMenuItem() );

        // Add another account — archives the current session (recoverable from the list) and
        // restarts to the login screen. Confirmed, since it interrupts the session.
        MenuItem addItem = new MenuItem( LocalizationManager.get( "account.switcher.addAccount" ) );
        addItem.setOnAction( e -> {
            Stage owner = anchor.getScene() != null && anchor.getScene().getWindow() instanceof Stage s
                    ? s : null;
            int response = GUIUtilities.showQuestionMessage(
                    LocalizationManager.get( "settings.savedAccounts.confirmAdd.title" ),
                    LocalizationManager.get( "settings.savedAccounts.confirmAdd.body" ),
                    "",
                    LocalizationManager.get( "settings.fxml.addAccount" ),
                    LocalizationManager.get( "dialog.button.cancel" ), owner );
            if ( response != 1 ) {
                return;
            }
            SystemUtilities.spawnNewTask( () -> {
                MCLauncherAuthManager.archiveAndLogout();
                GUIUtilities.JFXPlatformRun( LauncherCore::restartApp );
            } );
        } );
        menu.getItems().add( addItem );

        // Manage accounts — the existing full Settings → Account screen.
        MenuItem manageItem = new MenuItem( LocalizationManager.get( "account.switcher.manage" ) );
        manageItem.setOnAction( e -> {
            if ( openAccountSettings != null ) {
                openAccountSettings.run();
            }
        } );
        menu.getItems().add( manageItem );

        menu.show( anchor, Side.BOTTOM, 0, 0 );
    }
}
