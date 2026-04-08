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
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Base64;
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
     * Derives a machine-specific AES-256 key using PBKDF2 with hardware/OS identifiers as the passphrase.
     * The key is tied to this specific machine -- the encrypted file cannot be decrypted elsewhere.
     */
    private static SecretKey deriveMachineKey( byte[] salt ) throws Exception {
        // Build a machine fingerprint from multiple system identifiers
        StringBuilder fingerprint = new StringBuilder();
        fingerprint.append( System.getProperty( "user.name", "" ) );
        fingerprint.append( "|" );
        fingerprint.append( System.getProperty( "os.name", "" ) );
        fingerprint.append( "|" );

        // Hardware UUID from oshi (motherboard/BIOS identifier)
        try {
            SystemInfo si = new SystemInfo();
            fingerprint.append( si.getHardware().getComputerSystem().getHardwareUUID() );
        }
        catch ( Exception e ) {
            fingerprint.append( "no-hw-uuid" );
        }
        fingerprint.append( "|" );

        // First available MAC address for additional machine binding
        try {
            Enumeration< NetworkInterface > nets = NetworkInterface.getNetworkInterfaces();
            while ( nets.hasMoreElements() ) {
                byte[] mac = nets.nextElement().getHardwareAddress();
                if ( mac != null && mac.length > 0 ) {
                    for ( byte b : mac ) {
                        fingerprint.append( String.format( "%02x", b ) );
                    }
                    break;
                }
            }
        }
        catch ( Exception e ) {
            fingerprint.append( "no-mac" );
        }

        KeySpec spec = new PBEKeySpec( fingerprint.toString().toCharArray(), salt, 65536, 256 );
        SecretKeyFactory factory = SecretKeyFactory.getInstance( "PBKDF2WithHmacSHA256" );
        byte[] keyBytes = factory.generateSecret( spec ).getEncoded();
        return new SecretKeySpec( keyBytes, "AES" );
    }

    /**
     * Encrypts a string with AES-256-GCM using a machine-derived key.
     * Output format: Base64( salt[16] + iv[12] + ciphertext+tag )
     */
    private static String encryptForMachine( String plaintext ) throws Exception {
        SecureRandom random = new SecureRandom();
        byte[] salt = new byte[16];
        random.nextBytes( salt );
        byte[] iv = new byte[12];
        random.nextBytes( iv );

        SecretKey key = deriveMachineKey( salt );
        Cipher cipher = Cipher.getInstance( "AES/GCM/NoPadding" );
        cipher.init( Cipher.ENCRYPT_MODE, key, new GCMParameterSpec( 128, iv ) );
        byte[] ciphertext = cipher.doFinal( plaintext.getBytes( StandardCharsets.UTF_8 ) );

        // Combine: salt + iv + ciphertext
        byte[] combined = new byte[salt.length + iv.length + ciphertext.length];
        System.arraycopy( salt, 0, combined, 0, salt.length );
        System.arraycopy( iv, 0, combined, salt.length, iv.length );
        System.arraycopy( ciphertext, 0, combined, salt.length + iv.length, ciphertext.length );

        return Base64.getEncoder().encodeToString( combined );
    }

    /**
     * Decrypts a string that was encrypted with {@link #encryptForMachine(String)}.
     * Returns null if decryption fails (wrong machine, tampered data, etc.).
     */
    private static String decryptForMachine( String encoded ) throws Exception {
        byte[] combined = Base64.getDecoder().decode( encoded );
        if ( combined.length < 28 ) {
            return null; // Too short to contain salt + iv + any data
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
        byte[] plaintext = cipher.doFinal( ciphertext );
        return new String( plaintext, StandardCharsets.UTF_8 );
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
            Files.writeString( resolveSiblingPath( CACHED_USER_FILE ), encrypted );
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
            Files.writeString( resolveSiblingPath( RENEWAL_TIMESTAMP_FILE ),
                               String.valueOf( lastSuccessfulRenewalMs ) );
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
            // Get previous authentication file
            final AuthenticationFile previousAuthFile;
            try ( FileInputStream authFileInputStream = new FileInputStream( SAVED_LOGIN_FILE_PATH.toFile() ) ) {
                previousAuthFile = AuthenticationFile.readCompressed( authFileInputStream );
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
                Logger.logThrowable( ( Exception ) cause );
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
            Logger.logThrowable( e );
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
                FileOutputStream authFileOutputStream = new FileOutputStream( SAVED_LOGIN_FILE_PATH.toFile() );
                authFile.writeCompressed( authFileOutputStream );
                authFileOutputStream.close();
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
            Logger.logThrowable( e );
            result = MCLauncherAuthResult.ERROR_OTHER;
        }
        return result;
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
