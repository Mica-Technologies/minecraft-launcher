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
import javafx.scene.image.Image;
import javafx.stage.Stage;

import java.io.InputStream;

/**
 * Gives every launcher window the launcher's icon. A {@link Stage} shows the platform's generic
 * window icon in its title bar and taskbar button unless it is given one, and only the main
 * window used to be: the Running Games window, the dialogs and every alert showed the generic
 * icon. The image is decoded once and shared.
 *
 * @since 2026.10
 */
public final class WindowIcons
{
    private static final String ICON_RESOURCE = "micaminecraftlauncher.png";

    private static Image icon;
    private static boolean loadFailed;

    private WindowIcons()
    {
    }

    /**
     * Sets the launcher's icon on a window, unless it already has one. FX thread.
     *
     * @param stage the window; {@code null} is ignored
     *
     * @since 2026.10
     */
    public static void apply( Stage stage )
    {
        if ( stage == null || !stage.getIcons().isEmpty() ) {
            return;
        }
        Image image = icon();
        if ( image != null ) {
            stage.getIcons().add( image );
        }
    }

    /** @return the launcher's icon, decoded on first use, or {@code null} if it can't be read */
    static synchronized Image icon()
    {
        if ( icon == null && !loadFailed ) {
            try ( InputStream stream = WindowIcons.class.getClassLoader().getResourceAsStream( ICON_RESOURCE ) ) {
                if ( stream != null ) {
                    icon = new Image( stream );
                }
                else {
                    loadFailed = true;
                }
            }
            catch ( Exception e ) {
                loadFailed = true;
                Logger.logWarningSilent( LocalizationManager.get( "log.guiWindow.setIconFailed" ) );
                Logger.logThrowable( e );
            }
        }
        return icon;
    }
}
