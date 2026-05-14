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

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
     * Soft threshold (lower than the hard refresh interval) past which a cold-start
     * caller may opt to kick off a non-blocking background renewal piggybacked on
     * other startup work. The user proceeds with the cached token immediately;
     * the async renewal just ensures the next cold-start lands inside the
     * "no renewal needed" window again, smoothing out the every-N-launches stall.
     *
     * <p>3 hours = 75% of the hard interval. A user who launches the app more
     * frequently than once every 3 hours never hits the synchronous renewal path.</p>
     */
    private static final long TOKEN_SOFT_REFRESH_INTERVAL_MS = 3 * 60 * 60 * 1000L; // 3 hours

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
        // Load persisted timestamp if we haven't yet. Encrypted-at-rest like the other
        // auth files so a stolen disk image doesn't trivially reveal when the user last
        // signed in. Legacy plaintext files are migrated transparently on first read.
        if ( lastSuccessfulRenewalMs == 0 ) {
            try {
                Path timestampPath = resolveSiblingPath( RENEWAL_TIMESTAMP_FILE );
                if ( Files.exists( timestampPath ) ) {
                    String raw = Files.readString( timestampPath ).trim();
                    long parsed = readRenewalTimestamp( raw );
                    if ( parsed > 0 ) {
                        lastSuccessfulRenewalMs = parsed;
                        Logger.logStd( "Loaded renewal timestamp from disk: " +
                                               new java.text.SimpleDateFormat( "yyyy-MM-dd HH:mm:ss" ).format(
                                                       new java.util.Date( lastSuccessfulRenewalMs ) ) );
                    }
                    else {
                        Logger.logWarningSilent( "Renewal timestamp file present but unreadable; ignoring." );
                        lastSuccessfulRenewalMs = 0;
                    }
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

    /** Tightens the given file to owner-only perms via the shared utility. Kept as a
     *  thin local alias so existing call sites in this file read naturally. */
    private static void applyOwnerOnlyPermissions( Path path ) {
        com.micatechnologies.minecraft.launcher.utilities.FilePermissions.applyOwnerOnly( path );
    }

    // Machine-bound encryption / decryption was extracted into the shared
    // {@link com.micatechnologies.minecraft.launcher.utilities.MachineSecretCipher}
    // utility so the same primitive can protect the CurseForge API key (and
    // future user-supplied secrets) without two copies of the cipher drifting
    // apart. The auth manager now delegates encrypt / decrypt calls into the
    // utility; the install-secret file (machine-key.bin) location is unchanged,
    // so existing encrypted-at-rest auth files keep decrypting after the refactor.

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
        byte[] encrypted = com.micatechnologies.minecraft.launcher.utilities.MachineSecretCipher.encryptBytes( gzipped );
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
            byte[] gzipped = com.micatechnologies.minecraft.launcher.utilities.MachineSecretCipher.decryptBytes( fileBytes );
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
            String encrypted = com.micatechnologies.minecraft.launcher.utilities.MachineSecretCipher.encrypt( JSONUtilities.getGson().toJson( json ) );
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
            String decrypted = com.micatechnologies.minecraft.launcher.utilities.MachineSecretCipher.decrypt( encrypted );
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
            // Encrypt the timestamp at rest with the same machine key the other auth
            // files use. The value itself isn't a credential, but disclosing it tells
            // an attacker who reads the disk when the user last signed in and
            // contributes to activity-pattern profiling.
            String encoded = com.micatechnologies.minecraft.launcher.utilities.MachineSecretCipher.encrypt( String.valueOf( lastSuccessfulRenewalMs ) );
            Files.writeString( renewalPath, encoded );
            applyOwnerOnlyPermissions( renewalPath );
        }
        catch ( Exception e ) {
            Logger.logWarningSilent( "Failed to save renewal timestamp: " + e.getMessage() );
        }
    }

    /**
     * Parses the on-disk renewal timestamp string, accepting either the encrypted
     * Base64 form produced by {@link #recordSuccessfulRenewal()} or the legacy
     * plain-decimal form left over from pre-encryption installs. Legacy values are
     * upgraded transparently on the next {@link #recordSuccessfulRenewal()} call.
     * Returns {@code 0} on any parse failure.
     */
    private static long readRenewalTimestamp( String raw ) {
        if ( raw == null || raw.isBlank() ) {
            return 0L;
        }
        // Try decrypt first. If the body is valid Base64 ciphertext bound to this
        // machine, this succeeds and returns the decrypted decimal string.
        try {
            String decrypted = com.micatechnologies.minecraft.launcher.utilities.MachineSecretCipher.decrypt( raw );
            if ( decrypted != null ) {
                return Long.parseLong( decrypted.trim() );
            }
        }
        catch ( Exception ignored ) {
            // Fall through to legacy plaintext parse.
        }
        // Legacy plain-decimal path. Migration: the next recordSuccessfulRenewal
        // call rewrites the file in the encrypted form.
        try {
            return Long.parseLong( raw );
        }
        catch ( NumberFormatException e ) {
            return 0L;
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

    /**
     * Like {@link #logout()} but copies the active credentials to
     * {@code <config>/profiles/<uuid>/} first via {@link ProfileArchive}
     * so the account can be re-activated later from the Settings →
     * Account "Saved Accounts" list without re-running the Microsoft
     * OAuth flow.
     *
     * <p>The active session files are still deleted after the archive
     * copy, so the immediate-effect behaviour is identical to a plain
     * logout — login GUI lands on the next restart. The difference
     * only matters on the NEXT logged-out state, when the user opens
     * Settings and sees their previous accounts ready to switch to.</p>
     *
     * <p>Called by the Settings → Account "Save & Sign Out" /
     * "Add Another Account" UI; plain {@link #logout()} is left for
     * cases where the user explicitly wants to forget the account
     * (the existing red "Log Out" button is wired to it).</p>
     *
     * @since 2026.5
     */
    public static void archiveAndLogout() {
        User user = loggedIn;
        if ( user != null && user.uuid() != null && !user.uuid().isBlank() ) {
            ProfileArchive.archiveActive( user.uuid(), user.name() );
        }
        logout();
    }

    /**
     * Switches to a previously-archived profile. Archives the active
     * profile first (so we don't lose the current session), then
     * copies the archived files back into the active slots. The
     * in-memory {@link #loggedIn} state is cleared so the next caller
     * picks up the swapped-in token via the existing renewal /
     * restore path.
     *
     * <p>Caller should restart the launcher (or trigger a session
     * refresh) so the new identity is visible across every screen.
     * The Settings UI does this via {@code LauncherCore.restartApp()}
     * after a successful switch.</p>
     *
     * @param targetUuid UUID of the archived profile to activate
     * @return true if the swap succeeded, false otherwise
     * @since 2026.5
     */
    public static boolean switchToArchivedProfile( String targetUuid ) {
        if ( targetUuid == null || targetUuid.isBlank() ) return false;
        // Archive current — non-destructive so the existing session
        // stays valid until ProfileArchive.activate overwrites the
        // active files on the next line.
        User active = loggedIn;
        if ( active != null && active.uuid() != null && !active.uuid().isBlank() ) {
            if ( !active.uuid().equals( targetUuid ) ) {
                ProfileArchive.archiveActive( active.uuid(), active.name() );
            }
            else {
                // Switching to the already-active profile is a no-op.
                return true;
            }
        }
        boolean ok = ProfileArchive.activate( targetUuid );
        if ( ok ) {
            // Clear in-memory cache so the next renewal / launch
            // reads the swapped-in token from disk.
            loggedIn = null;
            lastSuccessfulRenewalMs = 0;
        }
        return ok;
    }

    /**
     * If the cached token's age is in the {@link #TOKEN_SOFT_REFRESH_INTERVAL_MS}..
     * {@link #TOKEN_REFRESH_INTERVAL_MS} window — too fresh to require synchronous
     * renewal, but old enough that the next launch will hit the sync renewal path —
     * kicks off a fire-and-forget background renewal piggybacked on other cold-start
     * idle time. Returns immediately. The next cold start (assuming the renewal
     * succeeds) lands inside the "no renewal needed" window again.
     *
     * <p>No-op when offline, when no cached login exists, when the token is younger
     * than the soft threshold, or when the token is already past the hard threshold
     * (since the caller's regular renewExistingLogin call will handle that case
     * synchronously).</p>
     *
     * @since 3.5
     */
    public static void tryPreemptiveBackgroundRenewal() {
        try {
            if ( !hasExistingLogin() ) return;
            // Force the timestamp file load if it hasn't been read yet — same lazy
            // load shouldRenewToken does. Cheap enough to fold inline.
            if ( lastSuccessfulRenewalMs == 0 ) {
                shouldRenewToken();
            }
            if ( lastSuccessfulRenewalMs == 0 ) {
                // No timestamp on disk → renewal is "required," which the synchronous
                // path will handle. Bail out instead of double-renewing.
                return;
            }
            long elapsed = System.currentTimeMillis() - lastSuccessfulRenewalMs;
            if ( elapsed < TOKEN_SOFT_REFRESH_INTERVAL_MS ) {
                return;  // still fresh — nothing to do
            }
            if ( elapsed >= TOKEN_REFRESH_INTERVAL_MS ) {
                return;  // past hard threshold — let the sync caller handle it
            }
            if ( com.micatechnologies.minecraft.launcher.utilities.NetworkUtilities.isOffline() ) {
                return;
            }
            Logger.logStd( "Token age in soft-refresh window — kicking off background renewal." );
            java.util.concurrent.CompletableFuture.runAsync( () -> {
                try {
                    renewExistingLogin();
                }
                catch ( Throwable t ) {
                    // Best-effort: any failure here is invisible to the user — they'll
                    // hit the sync renewal next launch if this one didn't take.
                    Logger.logWarningSilent( "Preemptive background renewal failed: "
                                                     + t.getClass().getSimpleName() );
                }
            } );
        }
        catch ( Throwable t ) {
            // Wrapper guard — nothing in this opportunistic path should throw onto the
            // launcher's startup critical path.
            Logger.logWarningSilent( "tryPreemptiveBackgroundRenewal aborted: "
                                             + t.getClass().getSimpleName() );
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
                // Persistent logs are not the place for that.
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
            // (not just I/O), so we log type-only rather than the full stack.
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
            // can carry token fragments. Log type only.
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
