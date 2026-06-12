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

import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
import com.micatechnologies.minecraft.launcher.files.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.util.Collections;
import java.util.EnumSet;

/**
 * Best-effort restriction of a file's permissions to the owning OS user. Used
 * for the launcher's auth files, IPC tokens, and log files — anywhere that
 * sibling users on a shared workstation should not be able to read the
 * launcher's per-user data.
 *
 * <p>POSIX is tried first; on filesystems that don't support POSIX attributes
 * (NTFS being the common case), {@link AclFileAttributeView} replaces the file
 * ACL with a single ALLOW entry for the current owner. Any failure logs a
 * silent warning — these are defense-in-depth restrictions, not the primary
 * control, and the calling code stays responsible for not assuming the tighten
 * succeeded.
 *
 * @since 2026.2
 */
public final class FilePermissions
{
    private FilePermissions() { /* static-only */ }

    /**
     * Restricts the given file's permissions to owner-only. POSIX 0600 if
     * supported, else a single owner-FULL_CONTROL ACL entry. Idempotent —
     * calling on a file whose ACL is already owner-only is harmless.
     */
    public static void applyOwnerOnly( Path path )
    {
        boolean applied = false;
        try {
            Files.setPosixFilePermissions( path, EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE ) );
            applied = true;
        }
        catch ( UnsupportedOperationException ignored ) {
            // Non-POSIX FS — try ACL path below.
        }
        catch ( IOException e ) {
            Logger.logWarningSilent( LocalizationManager.format( "log.filePermissions.posixTightenFailed",
                                             path.getFileName(), e.getClass().getSimpleName() ) );
        }
        if ( !applied ) {
            try {
                AclFileAttributeView view = Files.getFileAttributeView(
                        path, AclFileAttributeView.class );
                if ( view == null ) {
                    return;
                }
                UserPrincipal owner = Files.getOwner( path );
                AclEntry entry = AclEntry.newBuilder()
                        .setType( AclEntryType.ALLOW )
                        .setPrincipal( owner )
                        .setPermissions( EnumSet.allOf( AclEntryPermission.class ) )
                        .build();
                view.setAcl( Collections.singletonList( entry ) );
            }
            catch ( Exception e ) {
                Logger.logWarningSilent( LocalizationManager.format( "log.filePermissions.aclTightenFailed",
                                                 path.getFileName(), e.getClass().getSimpleName() ) );
            }
        }
    }
}
