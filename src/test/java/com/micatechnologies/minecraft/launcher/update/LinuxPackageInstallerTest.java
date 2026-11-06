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

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LinuxPackageInstallerTest
{
    private static final Path DEB = Path.of( "/home/player/.MicaMinecraftLauncher/updates/mica_2026.11.20_amd64.deb" );
    private static final Path RPM = Path.of( "/home/player/.MicaMinecraftLauncher/updates/mica-2026.11.20-1.x86_64.rpm" );

    /** Records commands and answers with a fixed exit code. */
    private static final class FakeRunner implements ProcessRunner
    {
        final List< List< String > > commands = new ArrayList<>();
        final int exitCode;

        FakeRunner( int exitCode )
        {
            this.exitCode = exitCode;
        }

        @Override
        public int run( List< String > command )
        {
            commands.add( command );
            return exitCode;
        }

        @Override
        public void startDetached( List< String > command )
        {
            throw new AssertionError( "A package install never runs anything detached" );
        }
    }

    @Test
    void debianPackagesGoThroughAptWhereItExists()
    {
        assertEquals( List.of( "pkexec", "/usr/bin/apt-get", "install", "-y", DEB.toString() ),
                      LinuxPackageInstaller.command( InstallKind.LINUX_DEB, DEB,
                                                     p -> Set.of( Path.of( "/usr/bin/apt-get" ) ).contains( p ) ) );
        assertEquals( List.of( "pkexec", "/usr/bin/dpkg", "-i", DEB.toString() ),
                      LinuxPackageInstaller.command( InstallKind.LINUX_DEB, DEB, p -> false ) );
    }

    @Test
    void rpmPackagesGoThroughDnfWhereItExists()
    {
        assertEquals( List.of( "pkexec", "/usr/bin/dnf", "install", "-y", RPM.toString() ),
                      LinuxPackageInstaller.command( InstallKind.LINUX_RPM, RPM,
                                                     p -> Set.of( Path.of( "/usr/bin/dnf" ) ).contains( p ) ) );
        assertEquals( List.of( "pkexec", "/usr/bin/rpm", "-U", RPM.toString() ),
                      LinuxPackageInstaller.command( InstallKind.LINUX_RPM, RPM, p -> false ) );
    }

    @Test
    void theFileIsPassedAsOneAbsoluteArgumentEvenWithSpaces()
    {
        Path spaced = Path.of( "/home/player/My Files/mica_2026.11.20_amd64.deb" );

        List< String > command = LinuxPackageInstaller.command( InstallKind.LINUX_DEB, spaced, p -> false );

        assertEquals( spaced.toString(), command.get( command.size() - 1 ) );
        assertTrue( Path.of( command.get( command.size() - 1 ) ).isAbsolute() );
    }

    @Test
    void otherKindsHaveNoPackageCommand()
    {
        assertThrows( IllegalArgumentException.class,
                      () -> LinuxPackageInstaller.command( InstallKind.LINUX_APPIMAGE, DEB, p -> true ) );
    }

    @Test
    void aDismissedPasswordPromptIsACancelNotAFailure()
    {
        StagedUpdate staged = new StagedUpdate( "2026.11.20", InstallKind.LINUX_DEB, DEB, "0" );

        assertThrows( UpdateCancelledException.class,
                      () -> LinuxPackageInstaller.install( staged, new FakeRunner(
                              LinuxPackageInstaller.PKEXEC_DISMISSED ) ) );
        assertThrows( UpdateCancelledException.class,
                      () -> LinuxPackageInstaller.install( staged, new FakeRunner(
                              LinuxPackageInstaller.PKEXEC_NOT_AUTHORIZED ) ) );
    }

    @Test
    void aPackageManagerErrorFailsTheInstall()
    {
        StagedUpdate staged = new StagedUpdate( "2026.11.20", InstallKind.LINUX_RPM, RPM, "0" );

        IOException failure = assertThrows( IOException.class,
                                            () -> LinuxPackageInstaller.install( staged, new FakeRunner( 1 ) ) );

        assertTrue( !( failure instanceof UpdateCancelledException ) );
    }

    @Test
    void aSuccessfulInstallRunsPkexecOnce() throws IOException
    {
        FakeRunner runner = new FakeRunner( 0 );

        LinuxPackageInstaller.install( new StagedUpdate( "2026.11.20", InstallKind.LINUX_DEB, DEB, "0" ), runner );

        assertEquals( 1, runner.commands.size() );
        assertEquals( "pkexec", runner.commands.get( 0 ).get( 0 ) );
    }
}
