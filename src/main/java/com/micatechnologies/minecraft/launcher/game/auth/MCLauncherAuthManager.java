/*
 * Copyright (c) 2021 Mica Technologies
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

import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
import com.micatechnologies.minecraft.launcher.files.LocalPathManager;
import com.micatechnologies.minecraft.launcher.files.Logger;
import net.hycrafthd.minecraft_authenticator.login.AuthenticationFile;
import net.hycrafthd.minecraft_authenticator.login.Authenticator;
import net.hycrafthd.minecraft_authenticator.login.User;

import com.micatechnologies.minecraft.launcher.utilities.JSONUtilities;
import com.google.gson.JsonObject;
import oshi.SystemInfo;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Base64;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.concurrent.*;

public class MCLauncherAuthManager
{
    private static final Path SAVED_LOGIN_FILE_PATH = Path.of( LocalPathManager.getRememberedAccountFilePath() );
    private static       User loggedIn              = null;

    /**
     * Maximum time in seconds to wait for an authentication operation to complete before giving up.
     */
    private static final int AUTH_TIMEOUT_SECONDS = 60;

    /**
     * Tracks the timestamp of the last auth API call to enforce rate limiting.
     */
    private static long lastAuthAttemptTimeMs = 0;

    /**
     * Minimum interval between auth API calls (in milliseconds). Prevents rapid-fire requests that could
     * trigger HTTP 429 responses from Microsoft's servers.
     */
    private static final long MIN_AUTH_INTERVAL_MS = 5_000;

    /**
     * Number of consecutive auth failures. Used for exponential backoff.
     */
    private static int consecutiveFailures = 0;

    /**
     * Maximum backoff delay in milliseconds (2 minutes).
     */
    private static final long MAX_BACKOFF_MS = 120_000;

    /**
     * Minimum interval between token renewals in milliseconds. Microsoft/Xbox Live tokens are valid for 24 hours,
     * so there's no need to refresh more than once every few hours. This prevents unnecessary API calls and
     * reduces the chance of hitting HTTP 429 rate limits.
     */
    private static final long TOKEN_REFRESH_INTERVAL_MS = 4 * 60 * 60 * 1000L; // 4 hours

    /**
     * Timestamp of the last successful token renewal. Stored on disk alongside the auth file so it persists
     * across launcher restarts.
     */
    private static long lastSuccessfulRenewalMs = 0;

    /**
     * File name for storing the last successful renewal timestamp.
     */
    private static final String RENEWAL_TIMESTAMP_FILE = "renewal.timestamp";

    /**
     * File name for storing the cached user data (so we can restore sessions without server contact).
     */
    private static final String CACHED_USER_FILE = "cached_user.json";

    /**
     * Returns true if the saved token should be renewed (enough time has passed since last renewal).
     */
    private static boolean shouldRenewToken() {
        // Load persisted timestamp if we haven't yet
        if ( lastSuccessfulRenewalMs == 0 ) {
            try {
                Path timestampPath = resolveSiblingPath( RENEWAL_TIMESTAMP_FILE );
                if ( Files.exists( timestampPath ) ) {
                    String content = Files.readString( timestampPath ).trim();
                    lastSuccessfulRenewalMs = Long.parseLong( content );
                    Logger.logStd( "Loaded renewal timestamp from disk: " +
                                           new java.text.SimpleDateFormat( "yyyy-MM-dd HH:mm:ss" ).format(
                                                   new java.util.Date( lastSuccessfulRenewalMs ) ) );
                }
                else {
                    Logger.logStd( "No renewal timestamp file found -- token renewal will be required." );
                }
            }
            catch ( Exception e ) {
                Logger.logWarningSilent( "Failed to read renewal timestamp: " + e.getMessage() );
                lastSuccessfulRenewalMs = 0;
            }
        }

        long elapsed = System.currentTimeMillis() - lastSuccessfulRenewalMs;
        long elapsedMinutes = elapsed / 60000;
        long thresholdMinutes = TOKEN_REFRESH_INTERVAL_MS / 60000;
        boolean shouldRenew = elapsed >= TOKEN_REFRESH_INTERVAL_MS;
        Logger.logStd( "Token age check: " + elapsedMinutes + "m since last server renewal (threshold: " +
                               thresholdMinutes + "m) -> " + ( shouldRenew ? "RENEWAL NEEDED" : "still valid" ) );
        return shouldRenew;
    }

    /**
     * Resolves the path for a sibling file next to the saved login file.
     */
    private static Path resolveSiblingPath( String filename ) {
        return SAVED_LOGIN_FILE_PATH.getParent() != null ?
               SAVED_LOGIN_FILE_PATH.getParent().resolve( filename ) :
               Path.of( LocalPathManager.getLauncherConfigFolderPath(), filename );
    }

    /**
     * Restricts the given file's permissions to owner-only (POSIX 0600 / Windows
     * owner-FULL_CONTROL with no other ACEs). Used on every auth-file write so other
     * local user accounts can't read tokens off a shared workstation.
     *
     * <p>POSIX path is tried first; if the underlying file store doesn't support
     * POSIX attributes (Windows NTFS is the common case), the {@code AclFileAttributeView}
     * path runs instead. Failures are logged silently — these are defense-in-depth
     * tightenings on top of the at-rest encryption, not a primary control.
     */
    private static void applyOwnerOnlyPermissions( Path path ) {
        // POSIX attempt — succeeds on Linux/macOS, throws UnsupportedOperationException
        // on NTFS. The flag tracks whether either path actually applied.
        boolean applied = false;
        try {
            Files.setPosixFilePermissions( path, EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE ) );
            applied = true;
        }
        catch ( UnsupportedOperationException ignored ) {
            // Not a POSIX FS — Windows path below.
        }
        catch ( IOException e ) {
            Logger.logWarningSilent( "POSIX perms tighten failed on "
                                             + path.getFileName() + ": "
                                             + e.getClass().getSimpleName() );
        }

        if ( !applied ) {
            try {
                AclFileAttributeView view = Files.getFileAttributeView(
                        path, AclFileAttributeView.class );
                if ( view == null ) {
                    return; // No ACL support either; leave default perms.
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
                Logger.logWarningSilent( "ACL tighten failed on "
                                                 + path.getFileName() + ": "
                                                 + e.getClass().getSimpleName() );
            }
        }
    }

    /** Filename of the per-install random fallback secret. Sits alongside the auth files
     *  in the launcher config dir and is generated lazily only when hardware fingerprint
     *  pieces are unavailable. Users with working oshi + NIC enumeration never create or
     *  read it, so their derived key is unchanged. */
    private static final String INSTALL_SECRET_FILE = "machine-key.bin";

    /** Length of the per-install secret in bytes. 32 bytes = 256 bits, well above the
     *  64-bit minimum for an unguessable HMAC input. */
    private static final int INSTALL_SECRET_BYTES = 32;

    /**
     * Returns a per-install random secret as a hex string, lazily creating and persisting
     * it on first use. The file is written then tightened to owner-only permissions via
     * {@link #applyOwnerOnlyPermissions(Path)} so other local users cannot read it. If
     * the file cannot be created (read-only FS / permission denied), falls back to an
     * in-memory ephemeral secret for this process — the consequence is that the cached
     * auth files become unreadable after restart, which forces a clean re-login, which
     * is the right failure mode (it's no worse than losing the cache outright).
     */
    private static volatile String cachedInstallSecret = null;

    private static String getOrCreateInstallSecret() {
        String cached = cachedInstallSecret;
        if ( cached != null ) {
            return cached;
        }
        synchronized ( MCLauncherAuthManager.class ) {
            if ( cachedInstallSecret != null ) {
                return cachedInstallSecret;
            }
            Path secretPath = resolveSiblingPath( INSTALL_SECRET_FILE );
            try {
                if ( Files.isRegularFile( secretPath ) ) {
                    byte[] existing = Files.readAllBytes( secretPath );
                    if ( existing.length >= INSTALL_SECRET_BYTES ) {
                        cachedInstallSecret = bytesToHex( existing );
                        return cachedInstallSecret;
                    }
                    // Corrupt / truncated — regenerate.
                }
                byte[] fresh = new byte[INSTALL_SECRET_BYTES];
                new SecureRandom().nextBytes( fresh );
                Files.createDirectories( secretPath.getParent() );
                Files.write( secretPath, fresh );
                applyOwnerOnlyPermissions( secretPath );
                cachedInstallSecret = bytesToHex( fresh );
                return cachedInstallSecret;
            }
            catch ( IOException e ) {
                // Best-effort: a non-persisted in-memory secret is still better than a
                // constant string. The auth cache won't survive a restart, but encryption
                // within this session is still machine-process-bound.
                Logger.logWarningSilent( "Unable to persist install secret ("
                                                 + e.getClass().getSimpleName()
                                                 + "); using ephemeral fallback." );
                byte[] fresh = new byte[INSTALL_SECRET_BYTES];
                new SecureRandom().nextBytes( fresh );
                cachedInstallSecret = bytesToHex( fresh );
                return cachedInstallSecret;
            }
        }
    }

    private static String bytesToHex( byte[] bytes ) {
        StringBuilder sb = new StringBuilder( bytes.length * 2 );
        for ( byte b : bytes ) {
            sb.append( String.format( "%02x", b ) );
        }
        return sb.toString();
    }

    /**
     * Derives a machine-specific AES-256 key using PBKDF2 with hardware/OS identifiers as
     * the passphrase. The key is tied to this specific machine — the encrypted file
     * cannot be decrypted elsewhere.
     *
     * <p>Hardware UUID (motherboard/BIOS) and MAC address are the primary entropy sources.
     * When either lookup fails (cloud VM with sandboxed hardware identifiers, Docker
     * container, restricted-permissions process), we previously appended the literal
     * strings {@code "no-hw-uuid"} / {@code "no-mac"} — which collapsed every install on a
     * given username + OS pair to the same derivation. We now substitute a per-install
     * random secret read from {@link #INSTALL_SECRET_FILE}, lazily generated on first
     * need and never sent off the machine. This preserves the behavior on hardware where
     * detection works (so existing encrypted caches still decrypt) while closing the
     * "all cloud VMs share a key" hole on hardware where detection doesn't.
     */
    private static SecretKey deriveMachineKey( byte[] salt ) throws Exception {
        StringBuilder fingerprint = new StringBuilder();
        fingerprint.append( System.getProperty( "user.name", "" ) );
        fingerprint.append( "|" );
        fingerprint.append( System.getProperty( "os.name", "" ) );
        fingerprint.append( "|" );

        // Hardware UUID from oshi (motherboard/BIOS identifier). On failure, substitute
        // the per-install random secret instead of a constant string.
        try {
            SystemInfo si = new SystemInfo();
            fingerprint.append( si.getHardware().getComputerSystem().getHardwareUUID() );
        }
        catch ( Exception e ) {
            fingerprint.append( "install:" ).append( getOrCreateInstallSecret() );
        }
        fingerprint.append( "|" );

        // First available MAC address for additional machine binding. Same fallback
        // treatment — random per-install secret rather than the predictable "no-mac".
        boolean macFound = false;
        try {
            Enumeration< NetworkInterface > nets = NetworkInterface.getNetworkInterfaces();
            while ( nets.hasMoreElements() ) {
                byte[] mac = nets.nextElement().getHardwareAddress();
                if ( mac != null && mac.length > 0 ) {
                    for ( byte b : mac ) {
                        fingerprint.append( String.format( "%02x", b ) );
                    }
                    macFound = true;
                    break;
                }
            }
        }
        catch ( Exception e ) {
            // fall through to install-secret fallback below
        }
        if ( !macFound ) {
            fingerprint.append( "install:" ).append( getOrCreateInstallSecret() );
        }

        KeySpec spec = new PBEKeySpec( fingerprint.toString().toCharArray(), salt, 65536, 256 );
        SecretKeyFactory factory = SecretKeyFactory.getInstance( "PBKDF2WithHmacSHA256" );
        byte[] keyBytes = factory.generateSecret( spec ).getEncoded();
        return new SecretKeySpec( keyBytes, "AES" );
    }

    /**
     * Encrypts raw bytes with AES-256-GCM using a machine-derived key.
     * Output layout: salt[16] | iv[12] | ciphertext+tag. The salt is fresh per
     * encryption so each call also derives a fresh key, eliminating the IV-reuse
     * footgun that would exist if the key were cached across encryptions.
     */
    private static byte[] encryptBytesForMachine( byte[] plaintext ) throws Exception {
        SecureRandom random = new SecureRandom();
        byte[] salt = new byte[16];
        random.nextBytes( salt );
        byte[] iv = new byte[12];
        random.nextBytes( iv );

        SecretKey key = deriveMachineKey( salt );
        Cipher cipher = Cipher.getInstance( "AES/GCM/NoPadding" );
        cipher.init( Cipher.ENCRYPT_MODE, key, new GCMParameterSpec( 128, iv ) );
        byte[] ciphertext = cipher.doFinal( plaintext );

        byte[] combined = new byte[salt.length + iv.length + ciphertext.length];
        System.arraycopy( salt, 0, combined, 0, salt.length );
        System.arraycopy( iv, 0, combined, salt.length, iv.length );
        System.arraycopy( ciphertext, 0, combined, salt.length + iv.length, ciphertext.length );
        return combined;
    }

    /**
     * Decrypts bytes produced by {@link #encryptBytesForMachine(byte[])}. Returns
     * {@code null} when the layout is too short to contain salt+iv+tag; throws on
     * GCM tag mismatch (wrong machine / tampered data) — callers decide whether
     * to log and fall through.
     */
    private static byte[] decryptBytesForMachine( byte[] combined ) throws Exception {
        if ( combined == null || combined.length < 28 ) {
            return null;
        }
        byte[] salt = new byte[16];
        byte[] iv = new byte[12];
        byte[] ciphertext = new byte[combined.length - 28];
        System.arraycopy( combined, 0, salt, 0, 16 );
        System.arraycopy( combined, 16, iv, 0, 12 );
        System.arraycopy( combined, 28, ciphertext, 0, ciphertext.length );

        SecretKey key = deriveMachineKey( salt );
        Cipher cipher = Cipher.getInstance( "AES/GCM/NoPadding" );
        cipher.init( Cipher.DECRYPT_MODE, key, new GCMParameterSpec( 128, iv ) );
        return cipher.doFinal( ciphertext );
    }

    /**
     * Encrypts a UTF-8 string with AES-256-GCM and Base64-encodes the result.
     * Output format: Base64( salt[16] + iv[12] + ciphertext+tag ). Thin wrapper
     * around {@link #encryptBytesForMachine(byte[])}.
     */
    private static String encryptForMachine( String plaintext ) throws Exception {
        return Base64.getEncoder().encodeToString(
                encryptBytesForMachine( plaintext.getBytes( StandardCharsets.UTF_8 ) ) );
    }

    /**
     * Decrypts a Base64 string produced by {@link #encryptForMachine(String)}.
     * Returns {@code null} if decryption fails (wrong machine, tampered data, etc.).
     */
    private static String decryptForMachine( String encoded ) throws Exception {
        byte[] plaintext = decryptBytesForMachine( Base64.getDecoder().decode( encoded ) );
        return plaintext == null ? null : new String( plaintext, StandardCharsets.UTF_8 );
    }

    /** GZIP magic bytes — used to detect legacy unencrypted {@code player.mica} files
     *  from before the at-rest encryption rollout so they can be migrated on next load. */
    private static final byte GZIP_MAGIC_0 = (byte) 0x1F;
    private static final byte GZIP_MAGIC_1 = (byte) 0x8B;

    /**
     * Persists an {@link AuthenticationFile} to {@link #SAVED_LOGIN_FILE_PATH},
     * encrypted with the machine-bound key. The library-provided
     * {@link AuthenticationFile#writeCompressed} output is the gzip'd JSON of the
     * refresh / access / Xbox tokens; left on disk in that form it's just
     * compression and any process that can read the user's home directory can
     * impersonate the account indefinitely. Wrapping the gzip bytes in AES-256-GCM
     * binds the file to this machine (per {@link #deriveMachineKey}).
     */
    private static void saveAuthFileEncrypted( AuthenticationFile authFile ) throws Exception {
        byte[] gzipped = authFile.writeCompressed();
        byte[] encrypted = encryptBytesForMachine( gzipped );
        try ( FileOutputStream out = new FileOutputStream( SAVED_LOGIN_FILE_PATH.toFile() ) ) {
            out.write( encrypted );
        }
        applyOwnerOnlyPermissions( SAVED_LOGIN_FILE_PATH );
    }

    /**
     * Reads and decrypts the saved {@link AuthenticationFile}. Falls back to the
     * legacy plain-gzip layout (gzip magic bytes 1F 8B at the start of file) for
     * one-shot migration from pre-encryption installs — on a successful legacy
     * read the file is immediately re-saved in the encrypted form so subsequent
     * loads take the fast path. Returns {@code null} if the file is missing,
     * corrupt, or bound to a different machine.
     */
    private static AuthenticationFile loadAuthFileDecrypted() {
        try {
            byte[] fileBytes = Files.readAllBytes( SAVED_LOGIN_FILE_PATH );
            if ( fileBytes.length == 0 ) {
                return null;
            }

            // Legacy plaintext path: gzip stream starting with 1F 8B. Migrate on read.
            if ( fileBytes.length >= 2
                    && fileBytes[0] == GZIP_MAGIC_0
                    && fileBytes[1] == GZIP_MAGIC_1 ) {
                AuthenticationFile legacy = AuthenticationFile.readCompressed( fileBytes );
                Logger.logStd( "Migrating legacy plaintext player.mica to encrypted form." );
                try {
                    saveAuthFileEncrypted( legacy );
                }
                catch ( Exception migrateFailure ) {
                    // Migration failure shouldn't block login — leave the legacy file alone
                    // and try again next time. Log a sanitized warning (no token data).
                    Logger.logWarningSilent( "Auth file migration failed: "
                                                     + migrateFailure.getClass().getSimpleName() );
                }
                return legacy;
            }

            // Encrypted path
            byte[] gzipped = decryptBytesForMachine( fileBytes );
            if ( gzipped == null ) {
                return null;
            }
            return AuthenticationFile.readCompressed( gzipped );
        }
        catch ( Exception e ) {
            Logger.logWarningSilent( "Failed to load saved auth file: "
                                             + e.getClass().getSimpleName() );
            return null;
        }
    }

    /**
     * Saves the User object to disk, encrypted with a machine-specific key.
     */
    private static void saveCachedUser( User user ) {
        try {
            JsonObject json = new JsonObject();
            json.addProperty( "uuid", user.uuid() );
            json.addProperty( "name", user.name() );
            json.addProperty( "accessToken", user.accessToken() );
            json.addProperty( "type", user.type() );
            json.addProperty( "xuid", user.xuid() );
            json.addProperty( "clientId", user.clientId() );
            String encrypted = encryptForMachine( JSONUtilities.getGson().toJson( json ) );
            Path cachedPath = resolveSiblingPath( CACHED_USER_FILE );
            Files.writeString( cachedPath, encrypted );
            applyOwnerOnlyPermissions( cachedPath );
        }
        catch ( Exception e ) {
            Logger.logWarningSilent( "Failed to save cached user data: " + e.getMessage() );
        }
    }

    /**
     * Loads the cached User object from disk by decrypting with the machine-specific key.
     * Returns null if the file doesn't exist, can't be decrypted (wrong machine), or is invalid.
     */
    private static User loadCachedUser() {
        try {
            Path cachedPath = resolveSiblingPath( CACHED_USER_FILE );
            if ( !Files.exists( cachedPath ) ) {
                return null;
            }
            String encrypted = Files.readString( cachedPath ).trim();
            String decrypted = decryptForMachine( encrypted );
            if ( decrypted == null ) {
                return null;
            }
            JsonObject json = JSONUtilities.getGson().fromJson( decrypted, JsonObject.class );
            return new User(
                    json.has( "uuid" ) ? json.get( "uuid" ).getAsString() : null,
                    json.has( "name" ) ? json.get( "name" ).getAsString() : null,
                    json.has( "accessToken" ) ? json.get( "accessToken" ).getAsString() : null,
                    json.has( "type" ) ? json.get( "type" ).getAsString() : null,
                    json.has( "xuid" ) ? json.get( "xuid" ).getAsString() : null,
                    json.has( "clientId" ) ? json.get( "clientId" ).getAsString() : null
            );
        }
        catch ( Exception e ) {
            Logger.logWarningSilent( "Failed to load cached user data: " + e.getMessage() );
            return null;
        }
    }

    /**
     * Records the timestamp of a successful token renewal to disk.
     */
    private static void recordSuccessfulRenewal() {
        lastSuccessfulRenewalMs = System.currentTimeMillis();
        try {
            Path renewalPath = resolveSiblingPath( RENEWAL_TIMESTAMP_FILE );
            Files.writeString( renewalPath, String.valueOf( lastSuccessfulRenewalMs ) );
            applyOwnerOnlyPermissions( renewalPath );
        }
        catch ( Exception e ) {
            Logger.logWarningSilent( "Failed to save renewal timestamp: " + e.getMessage() );
        }
    }

    /**
     * Optional callback for reporting auth status to a progress UI.
     */
    @FunctionalInterface
    public interface AuthStatusCallback
    {
        void onStatus( String sectionText, String detailText );
    }

    /**
     * Current auth status callback (set before calling auth methods).
     */
    private static AuthStatusCallback statusCallback = null;

    /**
     * Sets the auth status callback for progress UI updates during auth operations.
     *
     * @param callback the callback, or null to disable
     */
    public static void setStatusCallback( AuthStatusCallback callback ) {
        statusCallback = callback;
    }

    /**
     * Reports status to the callback if set.
     */
    private static void reportStatus( String sectionText, String detailText ) {
        if ( statusCallback != null ) {
            statusCallback.onStatus( sectionText, detailText );
        }
    }

    /**
     * Enforces rate limiting by waiting if the last auth attempt was too recent.
     * Applies exponential backoff if there have been consecutive failures.
     */
    private static void enforceRateLimit() {
        long backoffMs = 0;
        if ( consecutiveFailures > 0 ) {
            backoffMs = Math.min( MIN_AUTH_INTERVAL_MS * ( 1L << Math.min( consecutiveFailures, 10 ) ), MAX_BACKOFF_MS );
            Logger.logStd( "Auth backoff: waiting " + ( backoffMs / 1000 ) + "s after " + consecutiveFailures +
                                   " consecutive failure(s)." );
        }

        long elapsed = System.currentTimeMillis() - lastAuthAttemptTimeMs;
        long waitMs = Math.max( MIN_AUTH_INTERVAL_MS - elapsed, backoffMs - elapsed );
        if ( waitMs > 0 ) {
            if ( consecutiveFailures > 0 ) {
                reportStatus( "Signing In",
                              "Waiting " + ( waitMs / 1000 ) + "s before retrying (attempt " +
                                      ( consecutiveFailures + 1 ) + ")..." );
            }
            try {
                Thread.sleep( waitMs );
            }
            catch ( InterruptedException ignored ) {
            }
        }
        lastAuthAttemptTimeMs = System.currentTimeMillis();
    }

    /**
     * Records a successful auth attempt (resets failure counter).
     */
    private static void recordAuthSuccess() {
        consecutiveFailures = 0;
        recordSuccessfulRenewal();
        if ( loggedIn != null ) {
            saveCachedUser( loggedIn );
        }
    }

    /**
     * Records a failed auth attempt (increments failure counter).
     */
    private static void recordAuthFailure() {
        consecutiveFailures++;
    }

    public static boolean hasExistingLogin() {
        return Files.isRegularFile( SAVED_LOGIN_FILE_PATH );
    }

    public static User getLoggedInUser() {
        return loggedIn;
    }

    public static void logout() {
        // Clear local logged in file copy
        loggedIn = null;
        lastSuccessfulRenewalMs = 0;

        // Delete saved user file, cached user, and renewal timestamp
        try {
            Files.deleteIfExists( SAVED_LOGIN_FILE_PATH );
            Files.deleteIfExists( resolveSiblingPath( RENEWAL_TIMESTAMP_FILE ) );
            Files.deleteIfExists( resolveSiblingPath( CACHED_USER_FILE ) );
        }
        catch ( IOException e ) {
            Logger.logWarningSilent( "Unable to delete saved user account file!" );
            Logger.logThrowable( e );
        }
    }

    public static MCLauncherAuthResult renewExistingLogin() {
        reportStatus( "Signing In", "Checking session..." );

        // Check if we can skip renewal -- token was refreshed recently and is still valid
        if ( !shouldRenewToken() && loggedIn != null ) {
            long hoursAgo = ( System.currentTimeMillis() - lastSuccessfulRenewalMs ) / 3600000;
            long minutesAgo = ( ( System.currentTimeMillis() - lastSuccessfulRenewalMs ) / 60000 ) % 60;
            Logger.logStd( "Using existing session (token renewed " + hoursAgo + "h " + minutesAgo +
                                   "m ago, no server contact needed)." );
            reportStatus( "Signing In", "Using existing session (no server contact needed)" );
            return new MCLauncherAuthResult( loggedIn );
        }

        // If we have a cached user file on disk but no in-memory user, load it directly.
        // This handles the case where the launcher was restarted but the token is still fresh.
        if ( !shouldRenewToken() ) {
            User cachedUser = loadCachedUser();
            if ( cachedUser != null ) {
                loggedIn = cachedUser;
                long hoursAgo = ( System.currentTimeMillis() - lastSuccessfulRenewalMs ) / 3600000;
                long minutesAgo = ( ( System.currentTimeMillis() - lastSuccessfulRenewalMs ) / 60000 ) % 60;
                Logger.logStd( "Loaded session from disk (token renewed " + hoursAgo + "h " + minutesAgo +
                                       "m ago, no server contact needed)." );
                reportStatus( "Signing In", "Restored session from disk (no server contact needed)" );
                consecutiveFailures = 0;
                return new MCLauncherAuthResult( loggedIn );
            }
            else {
                Logger.logStd( "Cached user file not found or invalid, will contact authentication servers." );
            }
        }

        // Token is stale or could not be loaded -- need to contact authentication servers
        Logger.logStd( "Session needs renewal, will contact authentication servers." );
        reportStatus( "Signing In", "Preparing to contact authentication servers..." );

        // Enforce rate limiting before making API calls
        enforceRateLimit();

        // Try to read and renew login for saved account
        try {
            // Get previous authentication file. loadAuthFileDecrypted handles both the
            // current AES-256-GCM machine-bound layout and one-shot migration from the
            // legacy plain-gzip layout left over from pre-encryption installs.
            final AuthenticationFile previousAuthFile = loadAuthFileDecrypted();
            if ( previousAuthFile == null ) {
                throw new IOException( "Saved auth file is missing or unreadable on this machine." );
            }
            Logger.logDebug( LocalizationManager.REMEMBERED_USER_LOADED_TEXT );

            // Update authentication with timeout protection
            final Authenticator authenticator =
                    Authenticator.of( previousAuthFile ).serviceConnectTimeout( 5000 ).serviceReadTimeout( 10000 ).shouldAuthenticate().shouldRetrieveXBoxProfile().build();

            reportStatus( "Signing In", "Contacting authentication servers..." );
            Logger.logStd( "Renewing token with authentication servers (timeout: " + AUTH_TIMEOUT_SECONDS + "s)..." );
            ExecutorService authExecutor = Executors.newSingleThreadExecutor();
            Future< Void > authFuture = authExecutor.submit( () -> {
                authenticator.run();
                return null;
            } );

            try {
                authFuture.get( AUTH_TIMEOUT_SECONDS, TimeUnit.SECONDS );
            }
            catch ( TimeoutException e ) {
                authFuture.cancel( true );
                Logger.logError( "Authentication timed out after " + AUTH_TIMEOUT_SECONDS +
                                         " seconds. The authentication servers may be unreachable." );
                recordAuthFailure();
                return MCLauncherAuthResult.ERROR_OTHER;
            }
            catch ( ExecutionException e ) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                Logger.logWarningSilent( "Token renewal failed with an error (server was contacted)." );
                // Sanitized: log only the exception class, not the message or stack. The
                // minecraft_authenticator library wraps OAuth response bodies in exception
                // messages, which can contain token fragments and detailed account state.
                // Persistent logs are not the place for that (security finding 2.5).
                logAuthErrorType( cause );
                recordAuthFailure();
                return MCLauncherAuthResult.ERROR_OTHER;
            }
            finally {
                authExecutor.shutdownNow();
            }

            Logger.logStd( "Token renewed successfully (server responded)." );

            if ( authenticator.getResultFile() != null ) {
                // Get new authentication file and write to disk
                handleAuthFile( authenticator.getResultFile(), true );

                // Store logged in user (fallback to null if no user)
                loggedIn = authenticator.getUser().orElse( null );
            }
        }
        catch ( Exception e ) {
            Logger.logWarningSilent( LocalizationManager.PROBLEM_READING_ACCOUNT_FROM_DISK_TEXT );
            // Sanitized: the catch is broad and frequently catches auth-lib exceptions
            // (not just I/O), so we log type-only rather than the full stack. See finding 2.5.
            logAuthErrorType( e );
            recordAuthFailure();
            return MCLauncherAuthResult.ERROR_OTHER;
        }

        // Ensure loggedIn was populated as expected and return result
        MCLauncherAuthResult result;
        if ( loggedIn == null ) {
            Logger.logStd( "Token renewal completed but no user was returned (login may have expired)." );
            recordAuthFailure();
            result = MCLauncherAuthResult.ERROR_LOGIN_EXPIRED;
        }
        else {
            Logger.logStd( "Sign-in complete (token renewed with server)." );
            reportStatus( "Signing In", "Signed in successfully" );
            recordAuthSuccess();
            result = new MCLauncherAuthResult( loggedIn );
        }

        return result;
    }

    public static MCLauncherAuthResult loginWithMicrosoftAccount( String authCode, boolean save ) {
        // Enforce rate limiting to prevent API spam
        enforceRateLimit();
        // Try to login with Microsoft
        try {
            // Perform authentication with timeout protection
            final Authenticator authenticator =
                    Authenticator.ofMicrosoft( authCode ).serviceConnectTimeout( 5000 ).serviceReadTimeout( 10000 ).shouldAuthenticate().shouldRetrieveXBoxProfile().build();

            Logger.logStd( "Authenticating with Microsoft (timeout: " + AUTH_TIMEOUT_SECONDS + "s)..." );
            ExecutorService authExecutor = Executors.newSingleThreadExecutor();
            Future< Void > authFuture = authExecutor.submit( () -> {
                authenticator.run();
                return null;
            } );

            try {
                authFuture.get( AUTH_TIMEOUT_SECONDS, TimeUnit.SECONDS );
            }
            catch ( TimeoutException e ) {
                authFuture.cancel( true );
                Logger.logError( "Microsoft authentication timed out after " + AUTH_TIMEOUT_SECONDS + " seconds." );
                recordAuthFailure();
                return MCLauncherAuthResult.ERROR_OTHER;
            }
            catch ( ExecutionException e ) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                recordAuthFailure();
                return processAuthException( cause instanceof Exception ? ( Exception ) cause : new Exception( cause ) );
            }
            finally {
                authExecutor.shutdownNow();
            }

            Logger.logStd( "Microsoft authentication response received." );

            if ( authenticator.getResultFile() != null ) {
                handleAuthFile( authenticator.getResultFile(), save );
                loggedIn = authenticator.getUser().orElse( null );
            }
        }
        catch ( Exception e ) {
            recordAuthFailure();
            return processAuthException( e );
        }

        // Ensure loggedIn was populated as expected and return result
        MCLauncherAuthResult result;
        if ( loggedIn == null ) {
            recordAuthFailure();
            result = MCLauncherAuthResult.ERROR_BAD_USERNAME_PASSWORD;
        }
        else {
            recordAuthSuccess();
            result = new MCLauncherAuthResult( loggedIn );
        }

        return result;
    }

    private static void handleAuthFile( AuthenticationFile authFile, boolean save ) {
        if ( save ) {
            try {
                Logger.logDebug( LocalizationManager.REMEMBERED_USER_WRITING_TEXT );
                saveAuthFileEncrypted( authFile );
                Logger.logDebug( LocalizationManager.REMEMBERED_USER_WRITE_FINISHED_TEXT );
            }
            catch ( Exception e ) {
                Logger.logError( LocalizationManager.PROBLEM_WRITING_ACCOUNT_TO_DISK_TEXT );
                Logger.logThrowable( e );
            }
        }
        else {
            try {
                Files.deleteIfExists( SAVED_LOGIN_FILE_PATH );
            }
            catch ( Exception e ) {
                Logger.logWarningSilent( LocalizationManager.UNABLE_REMOVE_USER_FROM_DISK_TEXT );
                Logger.logThrowable( e );
            }
        }
    }

    private static MCLauncherAuthResult processAuthException( Exception e ) {
        MCLauncherAuthResult result;
        if ( checkIfExceptionIsNotBought( e ) ) {
            result = MCLauncherAuthResult.ERROR_NOT_OWNED;
        }
        else if ( checkIfExceptionIsInvalidCredentials( e ) ) {
            result = MCLauncherAuthResult.ERROR_BAD_USERNAME_PASSWORD;
        }
        else if ( checkIfExceptionIsNoValuePresent( e ) ) {
            result = MCLauncherAuthResult.ERROR_NO_VAL;
        }
        else {
            Logger.logWarningSilent( "Failed to login due to an exception while contacting the login service!" );
            // Sanitized — these are direct auth-library exceptions, so the message
            // can carry token fragments. Log type only (security finding 2.5).
            logAuthErrorType( e );
            result = MCLauncherAuthResult.ERROR_OTHER;
        }
        return result;
    }

    /**
     * Logs the bare exception type at WARNING level, without the message body or stack
     * trace. Used by auth-flow error sites where the exception comes from the
     * {@code minecraft_authenticator} library — those messages often quote OAuth
     * response bodies, which can include refresh-token fragments, access tokens, or
     * detailed account state that doesn't belong in a persistent log file.
     *
     * <p>If a deeper diagnostic is needed during development, set a breakpoint here or
     * temporarily route to {@link Logger#logThrowable(Throwable)} locally rather than
     * persisting full traces by default.
     */
    private static void logAuthErrorType( Throwable t ) {
        if ( t == null ) {
            return;
        }
        Logger.logWarningSilent( "Auth error type: " + t.getClass().getName() );
    }

    private static boolean checkIfExceptionIsNoValuePresent( Exception e ) {
        String msg = e.getMessage();
        return msg != null && msg.toLowerCase().contains( "no value present" );
    }

    private static boolean checkIfExceptionIsNotBought( Exception e ) {
        String msg = e.getMessage();
        return msg != null && msg.toLowerCase().contains( "not have bought" );
    }

    private static boolean checkIfExceptionIsInvalidCredentials( Exception e ) {
        String msg = e.getMessage();
        return msg != null && msg.toLowerCase().contains( "invalid credentials" );
    }
}
