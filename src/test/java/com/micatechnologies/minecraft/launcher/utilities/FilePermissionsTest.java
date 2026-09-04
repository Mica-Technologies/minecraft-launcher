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

package com.micatechnologies.minecraft.launcher.utilities;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Covers {@link FilePermissions} on POSIX filesystems (macOS / Linux CI
 * runners) — the defense-in-depth control that keeps the launcher's auth
 * cache, IPC token, and log files unreadable to other OS users on a shared
 * workstation. A regression that left these world- or group-readable would
 * quietly expose a Microsoft auth token or the single-instance IPC secret to
 * any other local account, with no user-visible symptom.
 *
 * <p>Also pins the file-vs-directory distinction called out in
 * {@link FilePermissions}'s own javadoc: a directory tightened to the
 * file-only 0600 mask (no execute bit) becomes untraversable by its own
 * owner, which is why {@link FilePermissions#applyOwnerOnlyDirectory} adds
 * {@code OWNER_EXECUTE} and {@link FilePermissions#applyOwnerOnly} does
 * not.</p>
 *
 * <p>Skipped outright on non-POSIX filesystems (Windows CI) via
 * {@link org.junit.jupiter.api.Assumptions#assumeTrue}; the ACL branch isn't
 * practical to assert on from a portable unit test.</p>
 *
 * @since 3.0
 */
class FilePermissionsTest
{
    private static boolean isPosix()
    {
        return FileSystems.getDefault().supportedFileAttributeViews().contains( "posix" );
    }

    @Test
    void applyOwnerOnly_restrictsFileToOwnerReadWrite( @TempDir Path tempDir ) throws Exception
    {
        assumeTrue( isPosix(), "POSIX permissions not supported on this filesystem" );

        Path file = tempDir.resolve( "secret.txt" );
        Files.writeString( file, "token" );

        FilePermissions.applyOwnerOnly( file );

        Set< PosixFilePermission > perms = Files.getPosixFilePermissions( file );
        assertEquals( Set.of( PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE ), perms );
    }

    @Test
    void applyOwnerOnlyDirectory_restrictsDirectoryToOwnerReadWriteExecute( @TempDir Path tempDir ) throws Exception
    {
        assumeTrue( isPosix(), "POSIX permissions not supported on this filesystem" );

        Path dir = tempDir.resolve( "profile-dir" );
        Files.createDirectory( dir );

        FilePermissions.applyOwnerOnlyDirectory( dir );

        Set< PosixFilePermission > perms = Files.getPosixFilePermissions( dir );
        assertEquals( Set.of( PosixFilePermission.OWNER_READ,
                               PosixFilePermission.OWNER_WRITE,
                               PosixFilePermission.OWNER_EXECUTE ),
                      perms );
    }

    @Test
    void applyOwnerOnly_isIdempotent( @TempDir Path tempDir ) throws Exception
    {
        assumeTrue( isPosix(), "POSIX permissions not supported on this filesystem" );

        Path file = tempDir.resolve( "secret.txt" );
        Files.writeString( file, "token" );

        FilePermissions.applyOwnerOnly( file );
        FilePermissions.applyOwnerOnly( file );

        Set< PosixFilePermission > perms = Files.getPosixFilePermissions( file );
        assertEquals( Set.of( PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE ), perms );
    }

    @Test
    void applyOwnerOnly_tightensAlreadyPermissiveFile( @TempDir Path tempDir ) throws Exception
    {
        assumeTrue( isPosix(), "POSIX permissions not supported on this filesystem" );

        Path file = tempDir.resolve( "secret.txt" );
        Files.writeString( file, "token" );
        Files.setPosixFilePermissions( file, Set.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_READ, PosixFilePermission.OTHERS_READ ) );

        FilePermissions.applyOwnerOnly( file );

        Set< PosixFilePermission > perms = Files.getPosixFilePermissions( file );
        assertEquals( Set.of( PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE ), perms );
    }
}
