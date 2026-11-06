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

import com.micatechnologies.minecraft.launcher.update.InstallKind;
import com.micatechnologies.minecraft.launcher.update.LauncherUpdater;
import org.junit.jupiter.api.Test;

import java.util.ResourceBundle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MCLauncherUpdateDialogTest
{
    @Test
    void theReadyTextMatchesHowEachKindInstalls()
    {
        assertEquals( "update.dialog.ready.body", MCLauncherUpdateDialog.readyBodyKey( InstallKind.LINUX_APPIMAGE ) );
        assertEquals( "update.dialog.ready.bodyPermission",
                      MCLauncherUpdateDialog.readyBodyKey( InstallKind.LINUX_DEB ) );
        assertEquals( "update.dialog.ready.bodyPkg", MCLauncherUpdateDialog.readyBodyKey( InstallKind.MAC_APP_PKG ) );
        assertEquals( "update.dialog.button.closeAndInstall",
                      MCLauncherUpdateDialog.restartButtonKey( InstallKind.MAC_APP_PKG ) );
        assertEquals( "update.dialog.button.restart", MCLauncherUpdateDialog.restartButtonKey( InstallKind.LINUX_RPM ) );
    }

    @Test
    void everyKeyTheDialogPicksExistsInTheEnglishBundle()
    {
        ResourceBundle english = ResourceBundle.getBundle( "lang.DisplayStrings", java.util.Locale.ROOT );
        for ( InstallKind kind : InstallKind.values() ) {
            if ( LauncherUpdater.installsInApp( kind ) || kind == InstallKind.MAC_APP_PKG ) {
                assertTrue( english.containsKey( MCLauncherUpdateDialog.readyBodyKey( kind ) ), kind.name() );
                assertTrue( english.containsKey( MCLauncherUpdateDialog.restartButtonKey( kind ) ), kind.name() );
            }
        }
        assertTrue( english.containsKey( "update.dialog.failed.cancelled" ) );
    }
}
