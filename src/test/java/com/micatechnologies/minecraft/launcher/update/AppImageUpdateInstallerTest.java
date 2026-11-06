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

import com.micatechnologies.minecraft.launcher.utilities.HashUtilities;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppImageUpdateInstallerTest
{
    private static final byte[] NEW_IMAGE = "new AppImage".getBytes( StandardCharsets.UTF_8 );
    private static final String NEW_SHA = HashUtilities.sha256Hex( NEW_IMAGE );

    @TempDir
    Path home;

    private Path updates;
    private Path appImage;
    private UpdateStager stager;
    private AppImageUpdateInstaller installer;

    @BeforeEach
    void setUp() throws IOException
    {
        updates = home.resolve( ".MicaMinecraftLauncher" ).resolve( "updates" );
        Path apps = Files.createDirectories( home.resolve( "Applications" ) );
        appImage = Files.writeString( apps.resolve( "Mica-Minecraft-Launcher.AppImage" ), "old AppImage" );
        stager = new UpdateStager( updates, ( url, destination, tracker ) -> Files.write( destination.toPath(),
                                                                                          NEW_IMAGE ) );
        installer = new AppImageUpdateInstaller( updates, stager );
    }

    private StagedUpdate stage( InstallKind kind ) throws IOException
    {
        return stager.stage( "2026.11.20", kind,
                             new ReleaseAsset( "Mica-Minecraft-Launcher-2026.11.20-x86_64.AppImage",
                                               "https://example.invalid/new", NEW_IMAGE.length, NEW_SHA ), null );
    }

    private Path previous()
    {
        return appImage.resolveSibling( appImage.getFileName() + ".previous" );
    }

    @Test
    void replacesTheAppImageAndKeepsThePreviousOne() throws IOException
    {
        installer.install( stage( InstallKind.LINUX_APPIMAGE ), appImage, "2026.11.13" );

        assertEquals( "new AppImage", Files.readString( appImage ) );
        assertEquals( "old AppImage", Files.readString( previous() ) );
        assertTrue( Files.isExecutable( appImage ) );
        assertFalse( Files.exists( appImage.resolveSibling( appImage.getFileName() + ".new" ) ) );
        assertTrue( stager.staged().isEmpty(), "the staged copy is gone once installed" );
    }

    @Test
    void recordsTheSwapForTheStarter() throws IOException
    {
        installer.install( stage( InstallKind.LINUX_APPIMAGE ), appImage, "2026.11.13" );

        assertEquals( "version=2026.11.20\nfrom=2026.11.13\nappimage=" + appImage.toAbsolutePath()
                      + "\nprevious=" + previous().toAbsolutePath() + "\n",
                      Files.readString( updates.resolve( AppImageUpdateInstaller.APPLIED_MARKER ) ),
                      "starter_unix.c reads these keys" );
        AppImageUpdateInstaller.AppliedUpdate applied = installer.applied().orElseThrow();
        assertEquals( "2026.11.20", applied.version() );
        assertEquals( previous().toAbsolutePath(), applied.previous() );
    }

    @Test
    void theNewVersionStartingConfirmsTheUpdate() throws IOException
    {
        installer.install( stage( InstallKind.LINUX_APPIMAGE ), appImage, "2026.11.13" );

        assertFalse( installer.confirmStarted( "2026.11.13" ), "the previous version is not the update" );
        assertTrue( Files.exists( previous() ) );
        assertTrue( installer.confirmStarted( "2026.11.20" ) );
        assertFalse( Files.exists( previous() ) );
        assertTrue( installer.applied().isEmpty() );
        assertFalse( installer.confirmStarted( "2026.11.20" ), "nothing left to confirm" );
    }

    @Test
    void stagingAnotherUpdateKeepsTheRecordOfTheInstalledOne() throws IOException
    {
        installer.install( stage( InstallKind.LINUX_APPIMAGE ), appImage, "2026.11.13" );

        stage( InstallKind.LINUX_APPIMAGE );

        assertTrue( installer.applied().isPresent() );
    }

    @Test
    void refusesAnUpdateForAnotherKindOfInstall() throws IOException
    {
        StagedUpdate msi = stage( InstallKind.WINDOWS_MSI_USER );

        assertThrows( IOException.class, () -> installer.install( msi, appImage, "2026.11.13" ) );
        assertEquals( "old AppImage", Files.readString( appImage ) );
    }

    @Test
    void aStagedFileDamagedSinceDownloadIsNotInstalled() throws IOException
    {
        StagedUpdate staged = stage( InstallKind.LINUX_APPIMAGE );
        Files.writeString( staged.file(), "damaged" );

        assertThrows( UpdateStager.ChecksumMismatchException.class,
                      () -> installer.install( staged, appImage, "2026.11.13" ) );
        assertEquals( "old AppImage", Files.readString( appImage ) );
        assertFalse( Files.exists( previous() ) );
        assertTrue( installer.applied().isEmpty() );
    }

    @Test
    void anIncompleteRecordIsIgnored() throws IOException
    {
        Files.createDirectories( updates );
        Files.writeString( updates.resolve( AppImageUpdateInstaller.APPLIED_MARKER ), "version=2026.11.20\n" );

        assertTrue( installer.applied().isEmpty() );
        assertFalse( installer.confirmStarted( "2026.11.20" ) );
    }
}
