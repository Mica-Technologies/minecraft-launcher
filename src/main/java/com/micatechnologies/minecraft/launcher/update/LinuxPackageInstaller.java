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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;

/**
 * Installs a {@code .deb} or {@code .rpm} update through the system package manager, asking for
 * the administrator password with {@code pkexec} (polkit shows the desktop's own prompt).
 *
 * <p>The launcher keeps running while the package is installed: package managers replace files
 * by renaming new ones into place, so the running copy keeps the files it has open, and the new
 * version runs from the restart that follows.
 *
 * <p>The package manager is the friendliest one present: {@code apt-get} and {@code dnf} resolve
 * any new dependency, {@code dpkg} and {@code rpm} are the fallbacks every system of that family
 * has.
 *
 * @since 2026.11
 */
public final class LinuxPackageInstaller
{
    /** pkexec's exit code when the user dismissed the password prompt. */
    static final int PKEXEC_DISMISSED = 126;
    /** pkexec's exit code when the user could not be authorized. */
    static final int PKEXEC_NOT_AUTHORIZED = 127;

    private LinuxPackageInstaller() { /* static-only */ }

    /**
     * The command that installs a package file as root.
     *
     * @param kind   {@link InstallKind#LINUX_DEB} or {@link InstallKind#LINUX_RPM}
     * @param file   the package file; absolute, as the package managers require for local files
     * @param exists whether a program path exists
     *
     * @return the command, starting with {@code pkexec}
     *
     * @throws IllegalArgumentException for any other kind
     * @since 2026.11
     */
    static List< String > command( InstallKind kind, Path file, Predicate< Path > exists )
    {
        String path = file.toAbsolutePath().toString();
        return switch ( kind ) {
            case LINUX_DEB -> exists.test( Path.of( "/usr/bin/apt-get" ) )
                              ? List.of( "pkexec", "/usr/bin/apt-get", "install", "-y", path )
                              : List.of( "pkexec", "/usr/bin/dpkg", "-i", path );
            case LINUX_RPM -> exists.test( Path.of( "/usr/bin/dnf" ) )
                              ? List.of( "pkexec", "/usr/bin/dnf", "install", "-y", path )
                              : List.of( "pkexec", "/usr/bin/rpm", "-U", path );
            default -> throw new IllegalArgumentException( "Not a Linux package install: " + kind );
        };
    }

    /**
     * Installs the staged package, waiting for the password prompt and the package manager.
     *
     * @param staged the staged update; a {@code .deb} or {@code .rpm}
     * @param runner runs {@code pkexec}
     *
     * @throws UpdateCancelledException when the password prompt was dismissed
     * @throws IOException              when the install failed
     * @since 2026.11
     */
    static void install( StagedUpdate staged, ProcessRunner runner ) throws IOException
    {
        int code = runner.run( command( staged.kind(), staged.file(), Files::exists ) );
        if ( code == PKEXEC_DISMISSED || code == PKEXEC_NOT_AUTHORIZED ) {
            throw new UpdateCancelledException( "The administrator password prompt was dismissed" );
        }
        if ( code != 0 ) {
            throw new IOException( "The package manager stopped with exit code " + code );
        }
    }
}
