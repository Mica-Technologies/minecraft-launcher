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
import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.utilities.FilePermissions;
import com.micatechnologies.minecraft.launcher.utilities.JSONUtilities;
import net.hycrafthd.minecraft_authenticator.login.User;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * On-disk store for every remembered account, one folder per account under
 * {@code <config>/profiles/<uuid>/}.
 *
 * <p>Each folder holds the files a signed-in account has always had, in the same
 * formats, so folders written by the old single-account {@code ProfileArchive} are read
 * unchanged:</p>
 * <ul>
 *   <li>{@code player.mica}: the gzip'd {@code AuthenticationFile} (refresh, access and
 *       Xbox tokens), encrypted with {@link AccountCipher}. A legacy plain-gzip file is
 *       still accepted on read.</li>
 *   <li>{@code cached_user.json}: the {@link User} fields as JSON, encrypted and Base64'd.
 *       Lets the launcher show the account without contacting Microsoft.</li>
 *   <li>{@code renewal.timestamp}: epoch millis of the last token renewal, encrypted and
 *       Base64'd. A legacy plain-decimal value is still accepted on read.</li>
 *   <li>{@code profile.json}: plaintext uuid, display name and last-used time, so the
 *       account list can be built without decrypting anything.</li>
 * </ul>
 *
 * <p>Only remembered accounts are ever written here. An account signed in without
 * "remember me" lives in memory only (see {@link AccountManager}).</p>
 *
 * <p>The store is not thread-safe on its own; {@link AccountManager} serializes access.</p>
 *
 * @since 2026.10
 */
final class AccountStore
{
    /**
     * Folder under {@code <config>/} holding one subfolder per account. Unchanged from the
     * old {@code ProfileArchive} so existing saved accounts are picked up as-is.
     *
     * @since 2026.10
     */
    static final String PROFILES_DIR = "profiles";

    /** Encrypted authentication file. Same name as the old flat active-account file. */
    static final String AUTH_FILE = "player.mica";

    /** Encrypted cached user. */
    static final String CACHED_USER_FILE = "cached_user.json";

    /** Encrypted renewal timestamp. */
    static final String RENEWAL_FILE = "renewal.timestamp";

    /** Plaintext metadata for listing. */
    static final String META_FILE = "profile.json";

    /**
     * What a folder name may look like. A Minecraft profile id is 32 hex digits, with or
     * without dashes. The uuid becomes a path segment, so anything else is refused outright
     * rather than sanitized: a uuid of {@code ..} or one containing a separator would
     * otherwise point the store outside its own folder.
     */
    private static final Pattern SAFE_UUID = Pattern.compile( "[0-9a-fA-F-]{1,64}" );

    /** GZIP magic bytes, for spotting a legacy unencrypted {@code player.mica}. */
    private static final byte GZIP_MAGIC_0 = (byte) 0x1F;
    private static final byte GZIP_MAGIC_1 = (byte) 0x8B;

    /**
     * One account as listed from its {@code profile.json}.
     *
     * @param uuid        the account uuid, which also names its folder
     * @param displayName the account's display name, or {@code ""} when unknown
     * @param lastUsedMs  epoch millis the account was last used or made the default
     *
     * @since 2026.10
     */
    record Entry( String uuid, String displayName, long lastUsedMs ) { }

    private final Path          root;
    private final AccountCipher cipher;

    /**
     * Creates a store rooted at the given folder.
     *
     * @param root   the {@code profiles} folder; created on first write
     * @param cipher encrypts and decrypts the credential files
     *
     * @since 2026.10
     */
    AccountStore( Path root, AccountCipher cipher )
    {
        this.root = root;
        this.cipher = cipher;
    }

    /**
     * Whether a uuid is safe to use as a folder name.
     *
     * @param uuid the candidate uuid
     *
     * @return {@code true} when it is hex digits and dashes only
     *
     * @since 2026.10
     */
    static boolean isSafeUuid( String uuid )
    {
        return uuid != null && SAFE_UUID.matcher( uuid ).matches();
    }

    /**
     * Lists stored accounts, most recently used first. A folder counts as an account only
     * when it holds an authentication file; a folder with metadata but no credentials can
     * never be signed in, so it is skipped.
     *
     * @return the stored accounts; never {@code null}
     *
     * @since 2026.10
     */
    List< Entry > list()
    {
        List< Entry > out = new ArrayList<>();
        if ( !Files.isDirectory( root ) ) {
            return out;
        }
        try ( var dirs = Files.list( root ) ) {
            for ( Path dir : dirs.filter( Files::isDirectory ).toList() ) {
                String folderUuid = dir.getFileName().toString();
                if ( !isSafeUuid( folderUuid ) || !Files.isRegularFile( dir.resolve( AUTH_FILE ) ) ) {
                    continue;
                }
                String displayName = "";
                long lastUsed = 0L;
                Path meta = dir.resolve( META_FILE );
                if ( Files.isRegularFile( meta ) ) {
                    try {
                        JsonObject obj = JSONUtilities.getGson().fromJson( Files.readString( meta ), JsonObject.class );
                        if ( obj != null && obj.has( "displayName" ) ) {
                            displayName = obj.get( "displayName" ).getAsString();
                        }
                        if ( obj != null && obj.has( "lastUsedMs" ) ) {
                            lastUsed = obj.get( "lastUsedMs" ).getAsLong();
                        }
                    }
                    catch ( Exception e ) {
                        // Unreadable metadata only costs the name and sort position; the
                        // credentials are still good, so keep the account.
                        Logger.logWarningSilent( LocalizationManager.format( "log.accountStore.metaUnreadable",
                                                                             folderUuid, e.getClass().getSimpleName() ) );
                    }
                }
                out.add( new Entry( folderUuid, displayName, lastUsed ) );
            }
        }
        catch ( IOException e ) {
            Logger.logWarningSilent( LocalizationManager.format( "log.accountStore.listFailed", e.getMessage() ) );
        }
        out.sort( Comparator.comparingLong( Entry::lastUsedMs ).reversed() );
        return out;
    }

    /**
     * Reads and decrypts an account's authentication file.
     *
     * @param uuid the account uuid
     *
     * @return the gzip'd {@code AuthenticationFile} bytes, or {@code null} when missing,
     *         unreadable, or bound to another machine
     *
     * @since 2026.10
     */
    byte[] readAuthFile( String uuid )
    {
        Path file = folder( uuid ).resolve( AUTH_FILE );
        return readAuthFileAt( file, cipher );
    }

    /**
     * Reads and decrypts an authentication file at an arbitrary path. Shared with the
     * one-shot migration of the old flat layout, which reads {@code <config>/player.mica}.
     *
     * @param file   the file to read
     * @param cipher the cipher it was written with
     *
     * @return the gzip'd bytes, or {@code null} when missing, unreadable, or from another machine
     *
     * @since 2026.10
     */
    static byte[] readAuthFileAt( Path file, AccountCipher cipher )
    {
        try {
            if ( !Files.isRegularFile( file ) ) {
                return null;
            }
            byte[] bytes = Files.readAllBytes( file );
            if ( bytes.length == 0 ) {
                return null;
            }
            // Pre-encryption installs wrote the gzip stream directly. Accept it; the next
            // write stores it encrypted.
            if ( bytes.length >= 2 && bytes[ 0 ] == GZIP_MAGIC_0 && bytes[ 1 ] == GZIP_MAGIC_1 ) {
                return bytes;
            }
            return cipher.decrypt( bytes );
        }
        catch ( Exception e ) {
            Logger.logWarningSilent( LocalizationManager.format( "log.authManager.loadAuthFileFailed",
                                                                 e.getClass().getSimpleName() ) );
            return null;
        }
    }

    /**
     * Encrypts and writes an account's authentication file.
     *
     * @param uuid    the account uuid
     * @param gzipped the gzip'd {@code AuthenticationFile} bytes
     *
     * @throws IOException if the uuid is unsafe or the write fails
     * @since 2026.10
     */
    void writeAuthFile( String uuid, byte[] gzipped ) throws IOException
    {
        writeSecret( uuid, AUTH_FILE, gzipped, false );
    }

    /**
     * Reads an account's cached {@link User}.
     *
     * @param uuid the account uuid
     *
     * @return the cached user, or {@code null} when missing, unreadable, or lacking a uuid and name
     *
     * @since 2026.10
     */
    User readCachedUser( String uuid )
    {
        return readCachedUserAt( folder( uuid ).resolve( CACHED_USER_FILE ), cipher );
    }

    /**
     * Reads a cached user at an arbitrary path; shared with the flat-layout migration.
     *
     * @param file   the file to read
     * @param cipher the cipher it was written with
     *
     * @return the user, or {@code null} when missing, unreadable, or lacking a uuid and name
     *
     * @since 2026.10
     */
    static User readCachedUserAt( Path file, AccountCipher cipher )
    {
        try {
            if ( !Files.isRegularFile( file ) ) {
                return null;
            }
            String json = decryptText( Files.readString( file ).trim(), cipher );
            if ( json == null ) {
                return null;
            }
            JsonObject obj = JSONUtilities.getGson().fromJson( json, JsonObject.class );
            User user = new User( stringOrNull( obj, "uuid" ), stringOrNull( obj, "name" ),
                                  stringOrNull( obj, "accessToken" ), stringOrNull( obj, "type" ),
                                  stringOrNull( obj, "xuid" ), stringOrNull( obj, "clientId" ) );
            if ( user.uuid() == null || user.uuid().isBlank() || user.name() == null || user.name().isBlank() ) {
                return null;
            }
            return user;
        }
        catch ( Exception e ) {
            Logger.logWarningSilent( LocalizationManager.format( "log.authManager.loadCachedUserFailed",
                                                                 e.getClass().getSimpleName() ) );
            return null;
        }
    }

    /**
     * Encrypts and writes an account's cached user. The folder is the user's own uuid.
     *
     * @param user the user to cache
     *
     * @throws IOException if the uuid is unsafe or the write fails
     * @since 2026.10
     */
    void writeCachedUser( User user ) throws IOException
    {
        JsonObject json = new JsonObject();
        json.addProperty( "uuid", user.uuid() );
        json.addProperty( "name", user.name() );
        json.addProperty( "accessToken", user.accessToken() );
        json.addProperty( "type", user.type() );
        json.addProperty( "xuid", user.xuid() );
        json.addProperty( "clientId", user.clientId() );
        writeSecret( user.uuid(), CACHED_USER_FILE,
                     JSONUtilities.getGson().toJson( json ).getBytes( StandardCharsets.UTF_8 ), true );
    }

    /**
     * Reads when an account's token was last renewed.
     *
     * @param uuid the account uuid
     *
     * @return epoch millis, or {@code 0} when unknown
     *
     * @since 2026.10
     */
    long readRenewalMs( String uuid )
    {
        return readRenewalMsAt( folder( uuid ).resolve( RENEWAL_FILE ), cipher );
    }

    /**
     * Reads a renewal timestamp at an arbitrary path; shared with the flat-layout migration.
     *
     * @param file   the file to read
     * @param cipher the cipher it was written with
     *
     * @return epoch millis, or {@code 0} when missing or unreadable
     *
     * @since 2026.10
     */
    static long readRenewalMsAt( Path file, AccountCipher cipher )
    {
        try {
            if ( !Files.isRegularFile( file ) ) {
                return 0L;
            }
            return parseRenewalTimestamp( Files.readString( file ).trim(), cipher );
        }
        catch ( IOException e ) {
            return 0L;
        }
    }

    /**
     * Parses a renewal timestamp in either the encrypted Base64 form or the legacy
     * plain-decimal form.
     *
     * @param raw    the file contents
     * @param cipher the cipher the encrypted form was written with
     *
     * @return epoch millis, or {@code 0} when it can't be parsed
     *
     * @since 2026.10
     */
    static long parseRenewalTimestamp( String raw, AccountCipher cipher )
    {
        if ( raw == null || raw.isBlank() ) {
            return 0L;
        }
        try {
            String decrypted = decryptText( raw, cipher );
            if ( decrypted != null ) {
                return Long.parseLong( decrypted.trim() );
            }
        }
        catch ( Exception ignored ) {
            // Not ciphertext from this machine; try the legacy decimal form.
        }
        try {
            return Long.parseLong( raw );
        }
        catch ( NumberFormatException e ) {
            return 0L;
        }
    }

    /**
     * Encrypts and writes when an account's token was last renewed.
     *
     * @param uuid      the account uuid
     * @param renewalMs epoch millis of the renewal
     *
     * @throws IOException if the uuid is unsafe or the write fails
     * @since 2026.10
     */
    void writeRenewalMs( String uuid, long renewalMs ) throws IOException
    {
        writeSecret( uuid, RENEWAL_FILE, String.valueOf( renewalMs ).getBytes( StandardCharsets.UTF_8 ), true );
    }

    /**
     * Writes an account's plaintext listing metadata.
     *
     * @param uuid        the account uuid
     * @param displayName the display name; {@code null} is stored as {@code ""}
     * @param lastUsedMs  epoch millis the account was last used
     *
     * @throws IOException if the uuid is unsafe or the write fails
     * @since 2026.10
     */
    void writeMeta( String uuid, String displayName, long lastUsedMs ) throws IOException
    {
        JsonObject meta = new JsonObject();
        meta.addProperty( "uuid", uuid );
        meta.addProperty( "displayName", displayName == null ? "" : displayName );
        meta.addProperty( "lastUsedMs", lastUsedMs );
        writeAtomically( ensureFolder( uuid ).resolve( META_FILE ),
                         JSONUtilities.getGson().toJson( meta ).getBytes( StandardCharsets.UTF_8 ) );
    }

    /**
     * Deletes an account's folder and everything in it.
     *
     * @param uuid the account uuid
     *
     * @return {@code true} when the folder is gone afterwards
     *
     * @since 2026.10
     */
    boolean remove( String uuid )
    {
        if ( !isSafeUuid( uuid ) ) {
            return false;
        }
        Path folder = root.resolve( uuid );
        if ( !Files.exists( folder ) ) {
            return true;
        }
        // Children before parents, and links deleted as links rather than followed, so this
        // can't wander out of the account's folder.
        try ( var walk = Files.walk( folder ) ) {
            for ( Path path : walk.sorted( Comparator.reverseOrder() ).toList() ) {
                try {
                    Files.deleteIfExists( path );
                }
                catch ( IOException ignored ) {
                    // Reported below by the existence check.
                }
            }
        }
        catch ( IOException e ) {
            Logger.logWarningSilent( LocalizationManager.format( "log.accountStore.removeFailed", uuid, e.getMessage() ) );
        }
        return !Files.exists( folder );
    }

    /**
     * Resolves an account's folder, refusing unsafe uuids.
     */
    private Path folder( String uuid )
    {
        if ( !isSafeUuid( uuid ) ) {
            // An unsafe uuid can't name a stored account, so resolve to a path that never
            // exists rather than throwing from read paths.
            return root.resolve( "invalid-uuid" ).resolve( "never" );
        }
        return root.resolve( uuid );
    }

    /**
     * Creates an account's folder (and the root) owner-only, refusing unsafe uuids.
     */
    private Path ensureFolder( String uuid ) throws IOException
    {
        if ( !isSafeUuid( uuid ) ) {
            throw new IOException( "Refusing to store an account under an unsafe uuid" );
        }
        Path folder = root.resolve( uuid );
        Files.createDirectories( folder );
        // The folder must be owner-only before credentials land in it: on Windows new files
        // inherit the directory ACL.
        FilePermissions.applyOwnerOnlyDirectory( root );
        FilePermissions.applyOwnerOnlyDirectory( folder );
        return folder;
    }

    /**
     * Encrypts and writes one credential file. Text files are Base64'd, matching the format
     * the launcher has always written for the cached user and renewal timestamp.
     */
    private void writeSecret( String uuid, String fileName, byte[] plaintext, boolean base64 ) throws IOException
    {
        Path folder = ensureFolder( uuid );
        byte[] encrypted;
        try {
            encrypted = cipher.encrypt( plaintext );
        }
        catch ( Exception e ) {
            throw new IOException( "Could not encrypt " + fileName, e );
        }
        byte[] payload = base64 ? Base64.getEncoder().encode( encrypted ) : encrypted;
        writeAtomically( folder.resolve( fileName ), payload );
    }

    /**
     * Writes a file through a temp sibling and a move, so a crash mid-write leaves either
     * the old file or the new one, never a truncated credential. The temp file is tightened
     * to owner-only before the move so the credential is never briefly world-readable.
     */
    private static void writeAtomically( Path target, byte[] bytes ) throws IOException
    {
        Path tmp = target.resolveSibling( target.getFileName() + ".tmp" );
        Files.write( tmp, bytes );
        FilePermissions.applyOwnerOnly( tmp );
        try {
            Files.move( tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE );
        }
        catch ( AtomicMoveNotSupportedException e ) {
            Files.move( tmp, target, StandardCopyOption.REPLACE_EXISTING );
        }
        FilePermissions.applyOwnerOnly( target );
    }

    /**
     * Decrypts a Base64 envelope to UTF-8 text.
     */
    private static String decryptText( String base64, AccountCipher cipher ) throws Exception
    {
        if ( base64 == null || base64.isBlank() ) {
            return null;
        }
        byte[] plain = cipher.decrypt( Base64.getDecoder().decode( base64 ) );
        return plain == null ? null : new String( plain, StandardCharsets.UTF_8 );
    }

    /**
     * Reads a string member, treating absent and JSON-null alike.
     */
    private static String stringOrNull( JsonObject obj, String member )
    {
        return obj != null && obj.has( member ) && !obj.get( member ).isJsonNull()
               ? obj.get( member ).getAsString() : null;
    }
}
