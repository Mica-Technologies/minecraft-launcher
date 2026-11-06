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

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstallKindTest
{
    private static final String WINDOWS = "Windows 11";
    private static final String MAC = "Mac OS X";
    private static final String LINUX = "Linux";
    private static final String LOCAL_APP_DATA = "C:\\Users\\player\\AppData\\Local";

    /** Facts with nothing on disk and nothing writable unless listed. */
    private static InstallKind detect( String os, Map< String, String > env, String appPath, String codeSource,
                                       Set< String > existing, Set< String > writable )
    {
        return InstallKind.detect( new InstallKind.Facts( os, env, appPath, codeSource, false,
                                                          p -> existing.contains( p.toString() ),
                                                          p -> writable.contains( p.toString() ) ) );
    }

    private static InstallKind detect( String os, Map< String, String > env, String appPath )
    {
        return detect( os, env, appPath, null, Set.of(), Set.of() );
    }

    @Test
    void windowsUserInstallLivesUnderLocalAppData()
    {
        Map< String, String > env = Map.of( "LOCALAPPDATA", LOCAL_APP_DATA );

        assertEquals( InstallKind.WINDOWS_MSI_USER,
                      detect( WINDOWS, env, LOCAL_APP_DATA + "\\Programs\\Mica Minecraft Launcher\\Mica Minecraft "
                                            + "Launcher Core.exe" ) );
        assertEquals( InstallKind.WINDOWS_MSI_USER,
                      detect( WINDOWS, env, "c:\\users\\PLAYER\\appdata\\local\\Programs\\x\\Core.exe" ),
                      "paths compare case-insensitively" );
    }

    @Test
    void windowsEverythingElseIsTheSystemInstall()
    {
        Map< String, String > env = Map.of( "LOCALAPPDATA", LOCAL_APP_DATA );

        assertEquals( InstallKind.WINDOWS_MSI_SYSTEM,
                      detect( WINDOWS, env, "C:\\Program Files\\Mica Minecraft Launcher\\Mica Minecraft Launcher Core.exe" ) );
        assertEquals( InstallKind.WINDOWS_MSI_SYSTEM,
                      detect( WINDOWS, env, LOCAL_APP_DATA + "Other\\x.exe" ), "a sibling folder is not inside" );
        assertEquals( InstallKind.WINDOWS_MSI_SYSTEM, detect( WINDOWS, Map.of(), "C:\\x\\Core.exe" ) );
    }

    @Test
    void macBundleTheUserCanReplaceUpdatesFromTheDmg()
    {
        String appPath = "/Applications/Mica Minecraft Launcher.app/Contents/MacOS/Mica Minecraft Launcher Core";
        String bundle = "/Applications/Mica Minecraft Launcher.app";

        assertEquals( InstallKind.MAC_APP_DMG,
                      detect( MAC, Map.of(), appPath, null, Set.of(), Set.of( bundle, "/Applications" ) ) );
        assertEquals( InstallKind.MAC_APP_PKG, detect( MAC, Map.of(), appPath, null, Set.of(), Set.of( bundle ) ),
                      "the folder holding the bundle must be writable too" );
        assertEquals( InstallKind.MAC_APP_PKG, detect( MAC, Map.of(), appPath ) );
        assertEquals( InstallKind.UNSUPPORTED, detect( MAC, Map.of(), "/opt/odd/launcher" ) );
    }

    @Test
    void linuxRuntimesAreToldApartByTheirEnvironment()
    {
        String mounted = "/tmp/.mount_MicaXyz/bin/Mica Minecraft Launcher Core";

        assertEquals( InstallKind.LINUX_APPIMAGE,
                      detect( LINUX, Map.of( "APPIMAGE", "/home/player/Applications/Mica.AppImage" ), mounted ) );
        assertEquals( InstallKind.FLATPAK,
                      detect( LINUX, Map.of( "FLATPAK_ID", "com.example.Launcher", "APPIMAGE", "/x" ), "/app/bin/x" ),
                      "Flatpak wins" );
        assertEquals( InstallKind.UNSUPPORTED, detect( LINUX, Map.of(), mounted ),
                      "an app image outside /opt is not a package" );
    }

    @Test
    void linuxPackagesAreToldApartByDpkg()
    {
        String appPath = "/opt/mica-minecraft-launcher/bin/Mica Minecraft Launcher Core";

        assertEquals( InstallKind.LINUX_DEB,
                      detect( LINUX, Map.of(), appPath, null, Set.of( "/var/lib/dpkg/info/mica-minecraft-launcher.list" ),
                              Set.of() ) );
        assertEquals( InstallKind.LINUX_RPM, detect( LINUX, Map.of(), appPath ) );
    }

    @Test
    void withoutAJpackageLauncherItIsTheJarOrNothing()
    {
        assertEquals( InstallKind.JAR,
                      detect( LINUX, Map.of(), null, "/home/player/launcher-2026.10.10-jar-with-dependencies.jar",
                              Set.of(), Set.of() ) );
        assertEquals( InstallKind.UNSUPPORTED,
                      detect( WINDOWS, Map.of(), null, "C:\\dev\\launcher\\build\\target\\classes", Set.of(), Set.of() ),
                      "an IDE run" );
        assertEquals( InstallKind.UNSUPPORTED, detect( MAC, Map.of(), null, null, Set.of(), Set.of() ) );
    }

    @Test
    void devBuildsNeverUpdate()
    {
        InstallKind kind = InstallKind.detect( new InstallKind.Facts( WINDOWS, Map.of( "LOCALAPPDATA", LOCAL_APP_DATA ),
                                                                      LOCAL_APP_DATA + "\\Programs\\x\\Core.exe", null,
                                                                      true, p -> true, p -> true ) );

        assertEquals( InstallKind.UNSUPPORTED, kind );
    }

    @Test
    void onlyFlatpakAndUnsupportedDoNotUpdateThemselves()
    {
        for ( InstallKind kind : InstallKind.values() ) {
            boolean expected = kind != InstallKind.FLATPAK && kind != InstallKind.UNSUPPORTED;
            assertEquals( expected, kind.selfUpdating(), kind.name() );
        }
        assertFalse( InstallKind.FLATPAK.selfUpdating() );
        assertTrue( InstallKind.LINUX_APPIMAGE.selfUpdating() );
    }

    @Test
    void currentNeverThrows()
    {
        // Whatever this test JVM is (an IDE or Maven run, not an install), detection must not fail.
        InstallKind kind = InstallKind.current( false );
        assertTrue( kind == InstallKind.UNSUPPORTED || kind == InstallKind.JAR || kind == InstallKind.FLATPAK
                    || kind == InstallKind.LINUX_APPIMAGE, kind.name() );
        assertEquals( InstallKind.UNSUPPORTED, InstallKind.current( true ) );
    }
}
