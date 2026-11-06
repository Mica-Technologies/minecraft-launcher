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

package com.micatechnologies.minecraft.launcher.update;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LauncherUpdaterTest
{
    private static final Optional< ReleaseAsset > FILE = Optional.of(
            new ReleaseAsset( "x", "https://example.invalid/x", 1, "0".repeat( 64 ) ) );

    @Test
    void theAppImageUpdatesInApp()
    {
        assertEquals( LauncherUpdater.Route.IN_APP, LauncherUpdater.routeFor( InstallKind.LINUX_APPIMAGE, FILE ) );
    }

    @Test
    void withoutAFileForThisInstallItIsTheReleasePage()
    {
        assertEquals( LauncherUpdater.Route.RELEASE_PAGE,
                      LauncherUpdater.routeFor( InstallKind.LINUX_APPIMAGE, Optional.empty() ) );
    }

    @Test
    void kindsWithAnInstallerUpdateInAppAndTheRestUseTheReleasePage()
    {
        for ( InstallKind kind : InstallKind.values() ) {
            if ( kind == InstallKind.FLATPAK ) {
                continue;
            }
            LauncherUpdater.Route expected = LauncherUpdater.installsInApp( kind ) ? LauncherUpdater.Route.IN_APP
                                                                                   : LauncherUpdater.Route.RELEASE_PAGE;
            assertEquals( expected, LauncherUpdater.routeFor( kind, FILE ), kind.name() );
        }
        assertTrue( LauncherUpdater.installsInApp( InstallKind.LINUX_DEB ) );
        assertTrue( LauncherUpdater.installsInApp( InstallKind.LINUX_RPM ) );
        assertFalse( LauncherUpdater.installsInApp( InstallKind.JAR ) );
        assertFalse( LauncherUpdater.installsInApp( InstallKind.UNSUPPORTED ) );
    }

    @Test
    void updatesThatAskForPermissionAreNeverInstalledOnQuit()
    {
        for ( InstallKind kind : InstallKind.values() ) {
            if ( LauncherUpdater.needsPermission( kind ) ) {
                assertFalse( LauncherUpdater.installsOnQuit( kind ), kind.name() );
                assertTrue( LauncherUpdater.installsInApp( kind ), kind.name() );
            }
        }
        assertTrue( LauncherUpdater.needsPermission( InstallKind.LINUX_DEB ) );
        assertTrue( LauncherUpdater.installsOnQuit( InstallKind.LINUX_APPIMAGE ) );
        assertFalse( LauncherUpdater.installsOnQuit( InstallKind.FLATPAK ) );
    }

    @Test
    void aStagedUpdateNoNewerThanTheRunningVersionIsStale()
    {
        StagedUpdate staged = new StagedUpdate( "2026.11.20", InstallKind.LINUX_DEB, Path.of( "x" ), "0" );

        assertTrue( LauncherUpdater.isStale( staged, "2026.11.20" ), "already installed" );
        assertTrue( LauncherUpdater.isStale( staged, "2026.12.01" ), "left over from before" );
        assertFalse( LauncherUpdater.isStale( staged, "2026.11.13" ) );
    }

    @Test
    void aFlatpakIsAlwaysLeftToTheSoftwareStore()
    {
        assertEquals( LauncherUpdater.Route.SOFTWARE_STORE, LauncherUpdater.routeFor( InstallKind.FLATPAK, FILE ) );
        assertEquals( LauncherUpdater.Route.SOFTWARE_STORE,
                      LauncherUpdater.routeFor( InstallKind.FLATPAK, Optional.empty() ) );
    }

    @Test
    void onlyANewerUpdateForTheSameKindIsInstalledOnQuit()
    {
        StagedUpdate staged = new StagedUpdate( "2026.11.20", InstallKind.LINUX_APPIMAGE, Path.of( "x" ), "0" );

        assertTrue( LauncherUpdater.shouldInstall( staged, InstallKind.LINUX_APPIMAGE, "2026.11.13" ) );
        assertFalse( LauncherUpdater.shouldInstall( staged, InstallKind.LINUX_APPIMAGE, "2026.11.20" ), "same" );
        assertFalse( LauncherUpdater.shouldInstall( staged, InstallKind.LINUX_APPIMAGE, "2026.12.01" ), "older" );
        assertFalse( LauncherUpdater.shouldInstall( staged, InstallKind.LINUX_DEB, "2026.11.13" ), "other kind" );
        assertTrue( LauncherUpdater.shouldInstall(
                new StagedUpdate( "2026.11.20.1", InstallKind.LINUX_APPIMAGE, Path.of( "x" ), "0" ),
                InstallKind.LINUX_APPIMAGE, "2026.11.20" ), "a same-day .N release" );
    }
}
