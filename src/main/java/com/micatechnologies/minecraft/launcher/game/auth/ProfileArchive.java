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

package com.micatechnologies.minecraft.launcher.game.auth;

import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.launcher.consts.LocalPathConstants;
import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
import com.micatechnologies.minecraft.launcher.files.LocalPathManager;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.utilities.FilePermissions;
import com.micatechnologies.minecraft.launcher.utilities.JSONUtilities;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Local archive for previously-signed-in Microsoft accounts. Saves the
 * three auth files ({@code player.mica}, {@code cached_user.json},
 * {@code renewal.timestamp}) under {@code <config>/profiles/<uuid>/}
 * so a "Switch Account" UI can resurface them without re-running the
 * Microsoft OAuth flow.
 *
 * <h3>Status</h3>
 * <p>This is the storage primitive for the F4 profile/account-switcher
 * feature in the 2026-05-14 review plan. The utility is intentionally
 * not yet wired into {@link MCLauncherAuthManager#logout()} or the
 * Settings UI — those changes touch the encrypted-at-rest auth flow
 * and the multi-account switching semantics need a small design pass
 * (what happens when the active token expires while another profile
 * is archived? does archiving include the renewal timestamp?). The
 * file-move primitives below are correct on their own and can be
 * wired up incrementally once the design is settled.</p>
 *
 * <h3>Encryption</h3>
 * <p>{@code player.mica} is encrypted at rest with the machine-bound
 * key from {@link com.micatechnologies.minecraft.launcher.utilities.MachineSecretCipher}.
 * Archive and activate operations are just file moves — no
 * re-encryption is needed, the same machine key still unlocks the
 * archived copy. This means profiles can't be moved between
 * machines, which is by design (same property the active file has
 * today).</p>
 *
 * @since 2026.5
 */
public final class ProfileArchive
{
    /**
     * Prevents instantiation; all members are static.
     *
     * @since 2026.5
     */
    private ProfileArchive() { /* static-only */ }

    /**
     * Folder under {@code <config>/} that holds archived profiles.
     *
     * @since 2026.5
     */
    public static final String PROFILES_DIR = "profiles";

    /** Filename inside each profile folder that mirrors the in-place
     *  encrypted credentials. */
    private static final String ARCHIVED_LOGIN_FILE = LocalPathConstants.AUTH_ACCOUNT_REMEMBERED_FILE_NAME;

    /** Sidecar metadata file storing the display name + UUID so the
     *  switcher UI can render a profile list without decrypting the
     *  credentials. */
    private static final String ARCHIVED_META_FILE = "profile.json";

    /** Names of the sibling files in the active auth folder that get
     *  swept across along with the credentials. Mirrors the paths
     *  {@link MCLauncherAuthManager#logout()} touches today. */
    private static final String[] SIBLING_FILES = {
            "cached_user.json",
            "renewal.timestamp"
    };

    /**
     * A summary of one archived profile, suitable for rendering a switcher menu.
     *
     * @param uuid        the account UUID, which also names the profile folder
     * @param displayName the account's display name for the switcher UI, or an
     *                    empty string if unknown
     * @param lastUsedMs  epoch-millis timestamp of the last archive/activate of
     *                    this profile; drives the most-recently-used sort order
     * @since 2026.5
     */
    public record ProfileEntry( String uuid, String displayName, long lastUsedMs ) { }

    /**
     * Archives the currently-active auth files into
     * {@code <config>/profiles/<uuid>/}. Copies (not moves) so the
     * active session keeps working until the caller explicitly tears
     * it down via the existing logout path. The archived copy
     * shadows the active one — re-archiving the same UUID overwrites.
     *
     * @param uuid        the account UUID; names the profile folder and is
     *                    written into the sidecar metadata
     * @param displayName the account's display name for the switcher UI; may be
     *                    {@code null} (stored as an empty string)
     * @return the archived profile entry, or {@code null} if archival failed
     *         (blank UUID, no active account, or IO error)
     * @since 2026.5
     */
    public static ProfileEntry archiveActive( String uuid, String displayName )
    {
        if ( uuid == null || uuid.isBlank() ) return null;
        Path activeLogin = Path.of( LocalPathManager.getRememberedAccountFilePath() );
        if ( !Files.exists( activeLogin ) ) {
            Logger.logWarningSilent( LocalizationManager.get( "log.profileArchive.archiveNoActiveLogin" ) );
            return null;
        }
        Path activeFolder = activeLogin.getParent();
        if ( activeFolder == null ) {
            Logger.logWarningSilent( LocalizationManager.get( "log.profileArchive.archiveNoParent" ) );
            return null;
        }
        try {
            Path target = profilesRoot().resolve( uuid );
            Files.createDirectories( target );
            // Tighten the profile directory itself — on Windows the copied
            // credential files below inherit the directory ACL, so the directory
            // must be owner-only before they land.
            FilePermissions.applyOwnerOnly( target );

            copyOwnerOnly( activeLogin, target.resolve( ARCHIVED_LOGIN_FILE ) );
            for ( String sibling : SIBLING_FILES ) {
                Path src = activeFolder.resolve( sibling );
                if ( Files.exists( src ) ) {
                    copyOwnerOnly( src, target.resolve( sibling ) );
                }
            }

            long now = System.currentTimeMillis();
            JsonObject meta = new JsonObject();
            meta.addProperty( "uuid", uuid );
            meta.addProperty( "displayName", displayName == null ? "" : displayName );
            meta.addProperty( "lastUsedMs", now );
            Path metaPath = target.resolve( ARCHIVED_META_FILE );
            Files.writeString( metaPath, JSONUtilities.getGson().toJson( meta ) );
            FilePermissions.applyOwnerOnly( metaPath );

            return new ProfileEntry( uuid, displayName, now );
        }
        catch ( IOException e ) {
            Logger.logError( LocalizationManager.format( "log.profileArchive.archiveFailed", uuid, e.getMessage() ) );
            Logger.logThrowable( e );
            return null;
        }
    }

    /**
     * Lists archived profiles sorted by last-used descending so a
     * switcher dropdown surfaces the most-recently-used account at
     * the top. Empty list when the profiles directory doesn't exist
     * or no archives have been written yet.
     *
     * @return a mutable, last-used-descending list of archived profiles; empty
     *         (never {@code null}) when there are none or the directory is absent
     * @since 2026.5
     */
    public static List< ProfileEntry > list()
    {
        List< ProfileEntry > out = new ArrayList<>();
        Path root = profilesRoot();
        if ( !Files.isDirectory( root ) ) return out;
        try ( java.util.stream.Stream< Path > stream = Files.list( root ) ) {
            stream.filter( Files::isDirectory ).forEach( dir -> {
                Path meta = dir.resolve( ARCHIVED_META_FILE );
                if ( !Files.exists( meta ) ) return;
                try {
                    String raw = Files.readString( meta );
                    JsonObject obj = JSONUtilities.getGson().fromJson( raw, JsonObject.class );
                    String uuid = obj.has( "uuid" ) ? obj.get( "uuid" ).getAsString()
                                                     : dir.getFileName().toString();
                    String displayName = obj.has( "displayName" )
                            ? obj.get( "displayName" ).getAsString() : "";
                    long lastUsed = obj.has( "lastUsedMs" )
                            ? obj.get( "lastUsedMs" ).getAsLong() : 0L;
                    out.add( new ProfileEntry( uuid, displayName, lastUsed ) );
                }
                catch ( Exception ex ) {
                    Logger.logWarningSilent( LocalizationManager.format( "log.profileArchive.skipUnreadable",
                                                     dir.getFileName(), ex.getMessage() ) );
                }
            } );
        }
        catch ( IOException e ) {
            Logger.logWarningSilent( LocalizationManager.format( "log.profileArchive.listFailed", e.getMessage() ) );
        }
        out.sort( Comparator.comparingLong( ProfileEntry::lastUsedMs ).reversed() );
        return out;
    }

    /**
     * Activates the named archived profile by copying its files back
     * over the active auth-folder slots. Idempotent — re-activating
     * the same profile is a no-op file refresh.
     *
     * <p>Caller is responsible for triggering whatever in-memory
     * state refresh {@link MCLauncherAuthManager} needs (token
     * renewal, GUI repaint). This utility only handles the file
     * shuffle.</p>
     *
     * @param uuid the UUID of the archived profile to activate
     * @return {@code true} on success, {@code false} when the UUID is blank, the
     *         profile doesn't exist, or an IO error occurred
     * @since 2026.5
     */
    public static boolean activate( String uuid )
    {
        if ( uuid == null || uuid.isBlank() ) return false;
        Path source = profilesRoot().resolve( uuid );
        Path archivedLogin = source.resolve( ARCHIVED_LOGIN_FILE );
        if ( !Files.isRegularFile( archivedLogin ) ) {
            Logger.logWarningSilent( LocalizationManager.format( "log.profileArchive.activateNoLoginFile", uuid ) );
            return false;
        }
        Path activeLogin = Path.of( LocalPathManager.getRememberedAccountFilePath() );
        Path activeFolder = activeLogin.getParent();
        try {
            copyOwnerOnly( archivedLogin, activeLogin );
            for ( String sibling : SIBLING_FILES ) {
                Path src = source.resolve( sibling );
                if ( Files.exists( src ) && activeFolder != null ) {
                    copyOwnerOnly( src, activeFolder.resolve( sibling ) );
                }
            }
            // Touch the last-used timestamp so the switcher list
            // resorts with this profile on top after activation.
            Path meta = source.resolve( ARCHIVED_META_FILE );
            if ( Files.exists( meta ) ) {
                JsonObject obj = JSONUtilities.getGson().fromJson( Files.readString( meta ),
                                                                    JsonObject.class );
                obj.addProperty( "lastUsedMs", System.currentTimeMillis() );
                Files.writeString( meta, JSONUtilities.getGson().toJson( obj ) );
            }
            return true;
        }
        catch ( IOException e ) {
            Logger.logError( LocalizationManager.format( "log.profileArchive.activateFailed", uuid, e.getMessage() ) );
            Logger.logThrowable( e );
            return false;
        }
    }

    /**
     * Permanently removes an archived profile, deleting its folder and all
     * contained files.
     *
     * @param uuid the UUID of the archived profile to delete
     * @return {@code true} if the profile was removed, {@code false} when the UUID
     *         is blank, no such profile exists, or an IO error occurred
     * @since 2026.5
     */
    public static boolean forget( String uuid )
    {
        if ( uuid == null || uuid.isBlank() ) return false;
        Path folder = profilesRoot().resolve( uuid );
        if ( !Files.isDirectory( folder ) ) return false;
        try {
            try ( java.util.stream.Stream< Path > stream = Files.list( folder ) ) {
                stream.forEach( f -> {
                    try { Files.deleteIfExists( f ); } catch ( IOException ignored ) {}
                } );
            }
            Files.deleteIfExists( folder );
            return true;
        }
        catch ( IOException e ) {
            Logger.logError( LocalizationManager.format( "log.profileArchive.forgetFailed", uuid, e.getMessage() ) );
            return false;
        }
    }

    /**
     * Resolves the root directory that holds all archived profile folders
     * ({@code <config>/profiles}).
     *
     * @return the profiles root path (not guaranteed to exist on disk)
     * @since 2026.5
     */
    private static Path profilesRoot()
    {
        return Path.of( LocalPathManager.getLauncherConfigFolderPath(), PROFILES_DIR );
    }

    /**
     * Copies a (potentially credential-bearing) file and restricts the
     * destination to owner-only, matching how {@link MCLauncherAuthManager}
     * protects the in-place auth files. Without this, an archived
     * {@code player.mica} could land group/world-readable — on Windows it
     * inherits the parent directory ACL rather than the source file's
     * restrictive ACL. Replaces the destination if it already exists.
     *
     * @param src the source file to copy
     * @param dst the destination path, replaced if present and then locked to
     *            owner-only permissions
     * @throws IOException if the copy or permission tightening fails
     * @since 2026.5
     */
    private static void copyOwnerOnly( Path src, Path dst ) throws IOException
    {
        Files.copy( src, dst, StandardCopyOption.REPLACE_EXISTING );
        FilePermissions.applyOwnerOnly( dst );
    }
}
