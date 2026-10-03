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
import com.micatechnologies.minecraft.launcher.game.auth.MCLauncherAuthManager;
import com.micatechnologies.minecraft.launcher.utilities.SystemUtilities;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.shape.Circle;

import java.util.List;

/**
 * Quick account switcher surfaced from the navbar / title-bar account lockup. Clicking the avatar or
 * player name pops this menu: every signed-in account with its avatar, the default one ticked.
 *
 * <p>Picking another account makes it the default straight away: every account stays signed
 * in, so there is nothing to swap and no restart. An account whose saved sign-in Microsoft
 * rejected opens the "Add account" window to sign it in again. "Add account…" opens that
 * window too, and "Manage accounts…" goes to Settings → Account.</p>
 *
 * @since 2026.6
 */
public final class AccountSwitcherMenu
{
    /**
     * Private constructor to prevent instantiation of this utility class.
     */
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

        MenuItem header = new MenuItem( LocalizationManager.get( "account.switcher.header" ) );
        header.setDisable( true );
        menu.getItems().add( header );

        List< AccountListModel.Row > rows;
        try {
            rows = AccountListModel.rows( MCLauncherAuthManager.accounts().accounts() );
        }
        catch ( Throwable t ) {
            rows = List.of();
        }
        for ( AccountListModel.Row row : rows ) {
            menu.getItems().add( accountItem( row, anchor ) );
        }

        menu.getItems().add( new SeparatorMenuItem() );

        MenuItem addItem = new MenuItem( LocalizationManager.get( "account.switcher.addAccount" ) );
        addItem.setOnAction( e -> AddAccountDialog.show( anchor.getScene() == null ? null : anchor.getScene().getWindow() ) );
        menu.getItems().add( addItem );

        MenuItem manageItem = new MenuItem( LocalizationManager.get( "account.switcher.manage" ) );
        manageItem.setOnAction( e -> {
            if ( openAccountSettings != null ) {
                openAccountSettings.run();
            }
        } );
        menu.getItems().add( manageItem );

        menu.show( anchor, Side.BOTTOM, 0, 0 );
    }

    /** One account: avatar, name, a tick on the default, and a hint when it needs signing in. */
    private static MenuItem accountItem( AccountListModel.Row row, Node anchor )
    {
        ImageView avatar = new ImageView( AvatarImages.get( row.uuid() ) );
        avatar.setFitWidth( 20 );
        avatar.setFitHeight( 20 );
        avatar.setPreserveRatio( true );
        avatar.setClip( new Circle( 10, 10, 10 ) );
        Label tick = new Label( row.isDefault() ? "✓" : "" );
        tick.setMinWidth( 14 );
        HBox graphic = new HBox( 6, tick, avatar );
        graphic.setAlignment( Pos.CENTER_LEFT );

        String text = row.needsSignIn()
                      ? LocalizationManager.format( "account.switcher.needsSignIn", row.name() )
                      : row.name();
        MenuItem item = new MenuItem( text, graphic );
        if ( row.isDefault() ) {
            return item;  // already the default: nothing to do
        }
        item.setOnAction( e -> {
            if ( row.needsSignIn() ) {
                AddAccountDialog.show( anchor.getScene() == null ? null : anchor.getScene().getWindow() );
                return;
            }
            // Writes the config and the account's metadata, so off the FX thread. Listeners
            // repaint the header and toolbar when it lands.
            SystemUtilities.spawnNewTask( () -> MCLauncherAuthManager.accounts().setDefault( row.uuid() ) );
        } );
        return item;
    }
}
