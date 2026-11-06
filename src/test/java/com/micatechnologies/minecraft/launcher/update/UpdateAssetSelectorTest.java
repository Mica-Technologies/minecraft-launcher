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

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateAssetSelectorTest
{
    private static final String SHA = "0".repeat( 64 );

    /** The file names of release 2026.10.10 as GitHub serves them, plus the AppImage that follows. */
    private static final List< String > RELEASE_FILES = List.of(
            "launcher-2026.10.10-jar-with-dependencies.jar",
            "mica-minecraft-launcher-2026.10.10-1.x86_64.rpm",
            "mica-minecraft-launcher_2026.10.10_amd64.deb",
            "Mica.Minecraft.Launcher-2026.10.10-arm64.dmg",
            "Mica.Minecraft.Launcher-2026.10.10-arm64.pkg",
            "Mica.Minecraft.Launcher-2026.10.10-intel.dmg",
            "Mica.Minecraft.Launcher-2026.10.10-intel.pkg",
            "Mica.Minecraft.Launcher-26.10.10-system.msi",
            "Mica.Minecraft.Launcher-26.10.10-user.msi",
            "Mica.Minecraft.Launcher.Setup.exe",
            "Mica-Minecraft-Launcher-2026.10.10-x86_64.AppImage" );

    private static List< ReleaseAsset > assets()
    {
        return RELEASE_FILES.stream()
                            .map( name -> new ReleaseAsset( name, "https://example.invalid/" + name, 1, SHA ) )
                            .toList();
    }

    private static String pick( InstallKind kind, String osArch )
    {
        return UpdateAssetSelector.select( kind, osArch, assets() ).map( ReleaseAsset::name ).orElse( null );
    }

    @Test
    void everyInstallKindFindsItsOwnFile()
    {
        assertEquals( "Mica.Minecraft.Launcher-26.10.10-user.msi", pick( InstallKind.WINDOWS_MSI_USER, "amd64" ) );
        assertEquals( "Mica.Minecraft.Launcher-26.10.10-system.msi", pick( InstallKind.WINDOWS_MSI_SYSTEM, "amd64" ) );
        assertEquals( "Mica.Minecraft.Launcher-2026.10.10-arm64.dmg", pick( InstallKind.MAC_APP_DMG, "aarch64" ) );
        assertEquals( "Mica.Minecraft.Launcher-2026.10.10-intel.dmg", pick( InstallKind.MAC_APP_DMG, "x86_64" ) );
        assertEquals( "Mica.Minecraft.Launcher-2026.10.10-arm64.pkg", pick( InstallKind.MAC_APP_PKG, "aarch64" ) );
        assertEquals( "Mica.Minecraft.Launcher-2026.10.10-intel.pkg", pick( InstallKind.MAC_APP_PKG, "x86_64" ) );
        assertEquals( "Mica-Minecraft-Launcher-2026.10.10-x86_64.AppImage", pick( InstallKind.LINUX_APPIMAGE, "amd64" ) );
        assertEquals( "mica-minecraft-launcher_2026.10.10_amd64.deb", pick( InstallKind.LINUX_DEB, "amd64" ) );
        assertEquals( "mica-minecraft-launcher-2026.10.10-1.x86_64.rpm", pick( InstallKind.LINUX_RPM, "amd64" ) );
        assertEquals( "launcher-2026.10.10-jar-with-dependencies.jar", pick( InstallKind.JAR, "aarch64" ) );
    }

    @Test
    void windowsOnArmGetsTheX64Msi()
    {
        assertEquals( "Mica.Minecraft.Launcher-26.10.10-user.msi", pick( InstallKind.WINDOWS_MSI_USER, "aarch64" ) );
    }

    @Test
    void architecturesWithoutABuildGetNothing()
    {
        assertEquals( null, pick( InstallKind.LINUX_APPIMAGE, "aarch64" ) );
        assertEquals( null, pick( InstallKind.LINUX_DEB, "aarch64" ) );
        assertEquals( null, pick( InstallKind.MAC_APP_DMG, "ppc64" ) );
    }

    @Test
    void flatpakAndUnsupportedInstallsGetNothing()
    {
        assertEquals( null, pick( InstallKind.FLATPAK, "amd64" ) );
        assertEquals( null, pick( InstallKind.UNSUPPORTED, "amd64" ) );
    }

    @Test
    void aFileWithoutAChecksumIsNeverOffered()
    {
        List< ReleaseAsset > noDigest = List.of(
                new ReleaseAsset( "Mica.Minecraft.Launcher-26.10.10-user.msi", "https://example.invalid/u", 1, null ) );

        Optional< ReleaseAsset > picked = UpdateAssetSelector.select( InstallKind.WINDOWS_MSI_USER, "amd64", noDigest );

        assertTrue( picked.isEmpty() );
    }

    @Test
    void suffixesMatchIgnoringCase()
    {
        List< ReleaseAsset > lower = List.of(
                new ReleaseAsset( "mica-minecraft-launcher-2026.10.10-x86_64.appimage", "https://example.invalid/a", 1,
                                  SHA ) );

        assertTrue( UpdateAssetSelector.select( InstallKind.LINUX_APPIMAGE, "x86_64", lower ).isPresent() );
    }

    @Test
    void architectureNamesFromEveryJvm()
    {
        assertEquals( UpdateAssetSelector.Arch.X64, UpdateAssetSelector.Arch.of( "amd64" ) );
        assertEquals( UpdateAssetSelector.Arch.X64, UpdateAssetSelector.Arch.of( "X86_64" ) );
        assertEquals( UpdateAssetSelector.Arch.ARM64, UpdateAssetSelector.Arch.of( "aarch64" ) );
        assertEquals( UpdateAssetSelector.Arch.ARM64, UpdateAssetSelector.Arch.of( "arm64" ) );
        assertEquals( UpdateAssetSelector.Arch.OTHER, UpdateAssetSelector.Arch.of( null ) );
        assertEquals( UpdateAssetSelector.Arch.OTHER, UpdateAssetSelector.Arch.of( "riscv64" ) );
    }
}
