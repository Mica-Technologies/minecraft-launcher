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
import com.micatechnologies.minecraft.launcher.files.LocalPathManager;
import com.micatechnologies.minecraft.launcher.files.Logger;
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
    private ProfileArchive() { /* static-only */ }

    /** Folder under {@code <config>/} that holds archived profiles. */
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

    /** A summary of one archived profile, suitable for rendering a
     *  switcher menu. */
    public record ProfileEntry( String uuid, String displayName, long lastUsedMs ) { }

    /**
     * Archives the currently-active auth files into
     * {@code <config>/profiles/<uuid>/}. Copies (not moves) so the
     * active session keeps working until the caller explicitly tears
     * it down via the existing logout path. The archived copy
     * shadows the active one — re-archiving the same UUID overwrites.
     *
     * @return the archived profile entry, or null if archival failed
     *         (no active account, IO error)
     */
    public static ProfileEntry archiveActive( String uuid, String displayName )
    {
        if ( uuid == null || uuid.isBlank() ) return null;
        Path activeLogin = Path.of( LocalPathManager.getRememberedAccountFilePath() );
        if ( !Files.exists( activeLogin ) ) {
            Logger.logWarningSilent( "ProfileArchive.archiveActive called with no active login file." );
            return null;
        }
        Path activeFolder = activeLogin.getParent();
        if ( activeFolder == null ) {
            Logger.logWarningSilent( "Active login path has no parent — bailing out of archive." );
            return null;
        }
        try {
            Path target = profilesRoot().resolve( uuid );
            Files.createDirectories( target );

            Files.copy( activeLogin, target.resolve( ARCHIVED_LOGIN_FILE ),
                         StandardCopyOption.REPLACE_EXISTING );
            for ( String sibling : SIBLING_FILES ) {
                Path src = activeFolder.resolve( sibling );
                if ( Files.exists( src ) ) {
                    Files.copy( src, target.resolve( sibling ),
                                 StandardCopyOption.REPLACE_EXISTING );
                }
            }

            long now = System.currentTimeMillis();
            JsonObject meta = new JsonObject();
            meta.addProperty( "uuid", uuid );
            meta.addProperty( "displayName", displayName == null ? "" : displayName );
            meta.addProperty( "lastUsedMs", now );
            Files.writeString( target.resolve( ARCHIVED_META_FILE ),
                                JSONUtilities.getGson().toJson( meta ) );

            return new ProfileEntry( uuid, displayName, now );
        }
        catch ( IOException e ) {
            Logger.logError( "Failed to archive profile " + uuid + ": " + e.getMessage() );
            Logger.logThrowable( e );
            return null;
        }
    }

    /**
     * Lists archived profiles sorted by last-used descending so a
     * switcher dropdown surfaces the most-recently-used account at
     * the top. Empty list when the profiles directory doesn't exist
     * or no archives have been written yet.
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
                    Logger.logWarningSilent( "Skipping unreadable archived profile at "
                                                     + dir.getFileName() + ": " + ex.getMessage() );
                }
            } );
        }
        catch ( IOException e ) {
            Logger.logWarningSilent( "Failed to list profiles: " + e.getMessage() );
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
     * @return true on success, false when the profile doesn't exist
     *         or an IO error occurred
     */
    public static boolean activate( String uuid )
    {
        if ( uuid == null || uuid.isBlank() ) return false;
        Path source = profilesRoot().resolve( uuid );
        Path archivedLogin = source.resolve( ARCHIVED_LOGIN_FILE );
        if ( !Files.isRegularFile( archivedLogin ) ) {
            Logger.logWarningSilent( "ProfileArchive.activate: no login file for " + uuid );
            return false;
        }
        Path activeLogin = Path.of( LocalPathManager.getRememberedAccountFilePath() );
        Path activeFolder = activeLogin.getParent();
        try {
            Files.copy( archivedLogin, activeLogin, StandardCopyOption.REPLACE_EXISTING );
            for ( String sibling : SIBLING_FILES ) {
                Path src = source.resolve( sibling );
                if ( Files.exists( src ) && activeFolder != null ) {
                    Files.copy( src, activeFolder.resolve( sibling ),
                                 StandardCopyOption.REPLACE_EXISTING );
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
            Logger.logError( "Failed to activate profile " + uuid + ": " + e.getMessage() );
            Logger.logThrowable( e );
            return false;
        }
    }

    /** Permanently removes an archived profile. */
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
            Logger.logError( "Failed to forget profile " + uuid + ": " + e.getMessage() );
            return false;
        }
    }

    private static Path profilesRoot()
    {
        return Path.of( LocalPathManager.getLauncherConfigFolderPath(), PROFILES_DIR );
    }
}
