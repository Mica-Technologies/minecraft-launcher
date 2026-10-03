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

import com.micatechnologies.minecraft.launcher.config.ConfigManager;
import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
import com.micatechnologies.minecraft.launcher.files.LocalPathManager;
import com.micatechnologies.minecraft.launcher.files.Logger;
import net.hycrafthd.minecraft_authenticator.login.AuthenticationFile;
import net.hycrafthd.minecraft_authenticator.login.Authenticator;
import net.hycrafthd.minecraft_authenticator.login.User;

import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Static entry point for the launcher's Microsoft/Minecraft authentication.
 *
 * <p>Accounts themselves live in {@link AccountManager}: every remembered account stays
 * signed in, each with its own session and refresh, and one of them is the default. This
 * class keeps the launcher-wide concerns around that:</p>
 * <ul>
 *     <li><strong>The Microsoft calls</strong>: interactive sign-in
 *         ({@link #loginWithMicrosoftAccount(String, boolean)}) and token renewal, wrapping
 *         the {@code minecraft_authenticator} library with a timeout.</li>
 *     <li><strong>Rate limiting and backoff</strong>: {@link #enforceRateLimit()} spaces out
 *         API calls for every account together and backs off exponentially after
 *         consecutive failures, to avoid HTTP 429 responses.</li>
 *     <li><strong>Token-refresh throttling</strong>: a hard refresh interval
 *         ({@link #TOKEN_REFRESH_INTERVAL_MS}) plus a softer preemptive window
 *         ({@link #TOKEN_SOFT_REFRESH_INTERVAL_MS}) keep cold starts fast.</li>
 *     <li><strong>The default-account facade</strong>: {@link #getLoggedInUser()},
 *         {@link #renewExistingLogin()} and friends act on the default account, so screens
 *         that only show "who is signed in" didn't have to change.</li>
 * </ul>
 *
 * <p>All members are static. It is not intended to be instantiated.</p>
 *
 * @author Mica Technologies
 */
public class MCLauncherAuthManager
{
    /**
     * Maximum time in seconds to wait for an authentication operation to complete before giving up.
     */
    private static final int AUTH_TIMEOUT_SECONDS = 60;

    /**
     * Tracks the timestamp of the last auth API call to enforce rate limiting.
     */
    private static final AtomicLong lastAuthAttemptTimeMs = new AtomicLong( 0 );

    /**
     * Minimum interval between auth API calls (in milliseconds). Prevents rapid-fire requests that could
     * trigger HTTP 429 responses from Microsoft's servers.
     */
    private static final long MIN_AUTH_INTERVAL_MS = 5_000;

    /**
     * Number of consecutive auth failures. Used for exponential backoff.
     */
    private static final AtomicInteger consecutiveFailures = new AtomicInteger( 0 );

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

    /** Background executor for token refreshes. Single-thread, daemon: refreshes are
     *  spaced out by the shared rate limit anyway, and we don't want to keep the JVM alive
     *  if the user quits before one completes. */
    private static final ExecutorService REFRESH_EXECUTOR = Executors.newSingleThreadExecutor( r -> {
        Thread t = new Thread( r, "mmcl-auth-refresh" );
        t.setDaemon( true );
        return t;
    } );

    /** Lazily-built production account manager. */
    private static final class Holder
    {
        static final AccountManager INSTANCE;

        static
        {
            Path configFolder = Path.of( LocalPathManager.getLauncherConfigFolderPath() );
            AccountCipher cipher = AccountCipher.machineBound();
            INSTANCE = new AccountManager(
                    new AccountStore( configFolder.resolve( AccountStore.PROFILES_DIR ), cipher ),
                    configFolder, cipher,
                    new AccountManager.DefaultAccountSetting()
                    {
                        @Override
                        public String get()
                        {
                            return ConfigManager.getDefaultAccountUuid();
                        }

                        @Override
                        public void set( String uuid )
                        {
                            ConfigManager.setDefaultAccountUuid( uuid );
                        }
                    },
                    MCLauncherAuthManager::renewWithMicrosoft,
                    System::currentTimeMillis,
                    REFRESH_EXECUTOR,
                    TOKEN_REFRESH_INTERVAL_MS );
        }
    }

    /**
     * Every signed-in account and the default. Loads (and migrates the old single-account
     * files) on first use.
     *
     * @return the launcher's account manager
     *
     * @since 2026.10
     */
    public static AccountManager accounts()
    {
        return Holder.INSTANCE;
    }

    /**
     * Decides whether a token renewal is due. Pure: no clock, no disk, no logging.
     *
     * <p>A {@code lastRenewalMs} of {@code 0} means "never renewed, or the timestamp could
     * not be read", and always reports due — failing toward re-authentication rather than
     * trusting a token of unknown age. A clock that has moved backwards (NTP correction, a
     * timestamp written on another machine) yields a negative elapsed time and reports not
     * due; that is deliberate, since the alternative is forcing a re-login on every launch
     * until the clock catches up.</p>
     *
     * @param lastRenewalMs epoch millis of the last successful renewal, or {@code 0} if unknown
     * @param nowMs         current epoch millis
     * @param intervalMs    renewal interval
     *
     * @return {@code true} when a renewal should be attempted
     *
     * @since 2026.9
     */
    static boolean isRenewalDue( long lastRenewalMs, long nowMs, long intervalMs )
    {
        if ( lastRenewalMs <= 0 ) {
            return true;
        }
        return ( nowMs - lastRenewalMs ) >= intervalMs;
    }

    /**
     * Parses the on-disk renewal timestamp string, accepting either the encrypted
     * Base64 form or the legacy plain-decimal form left over from pre-encryption installs.
     * Returns {@code 0} on any parse failure.
     */
    // Package-private for test reach — see MCLauncherAuthManagerRenewalTimestampTest, which
    // runs the real-cipher cases in a subprocess with cwd pinned to a @TempDir.
    static long readRenewalTimestamp( String raw ) {
        return AccountStore.parseRenewalTimestamp( raw, AccountCipher.machineBound() );
    }

    /**
     * Optional callback for reporting auth status to a progress UI.
     */
    @FunctionalInterface
    public interface AuthStatusCallback
    {
        /**
         * Invoked to report incremental authentication progress to a UI surface.
         *
         * @param sectionText the high-level phase label (e.g. "Signing in")
         * @param detailText  the finer-grained detail for the current phase
         *                    (e.g. "Contacting servers")
         */
        void onStatus( String sectionText, String detailText );
    }

    /**
     * Current auth status callback (set before calling auth methods).
     */
    private static volatile AuthStatusCallback statusCallback = null;

    /**
     * Sets the auth status callback for progress UI updates during auth operations.
     *
     * @param callback the callback, or null to disable
     */
    public static void setStatusCallback( AuthStatusCallback callback ) {
        statusCallback = callback;
    }

    /**
     * Reports status to the registered {@link #statusCallback} if one is set;
     * otherwise a no-op.
     *
     * @param sectionText the high-level phase label
     * @param detailText  the finer-grained detail for the current phase
     */
    private static void reportStatus( String sectionText, String detailText ) {
        AuthStatusCallback callback = statusCallback;
        if ( callback != null ) {
            callback.onStatus( sectionText, detailText );
        }
    }

    /**
     * Enforces rate limiting by waiting if the last auth attempt was too recent.
     * Applies exponential backoff if there have been consecutive failures.
     *
     * <p>Synchronized because every account's refresh and every interactive sign-in share
     * this one budget: two callers racing it must not both see the slot free.</p>
     */
    private static synchronized void enforceRateLimit() {
        // Snapshot the failure count once so the backoff math + status text are
        // computed against a single consistent value even if another auth path
        // mutates it concurrently.
        int failures = consecutiveFailures.get();
        long backoffMs = 0;
        if ( failures > 0 ) {
            backoffMs = Math.min( MIN_AUTH_INTERVAL_MS * ( 1L << Math.min( failures, 10 ) ), MAX_BACKOFF_MS );
            Logger.logStd( LocalizationManager.format( "log.authManager.authBackoff", ( backoffMs / 1000 ), failures ) );
        }

        long elapsed = System.currentTimeMillis() - lastAuthAttemptTimeMs.get();
        long waitMs = Math.max( MIN_AUTH_INTERVAL_MS - elapsed, backoffMs - elapsed );
        if ( waitMs > 0 ) {
            if ( failures > 0 ) {
                reportStatus( LocalizationManager.get( "authManager.status.signingIn" ),
                              LocalizationManager.format( "authManager.status.waitingRetry", ( waitMs / 1000 ),
                                      ( failures + 1 ) ) );
            }
            try {
                Thread.sleep( waitMs );
            }
            catch ( InterruptedException ignored ) {
                Thread.currentThread().interrupt();
            }
        }
        lastAuthAttemptTimeMs.set( System.currentTimeMillis() );
    }

    /**
     * Records a failed auth attempt (increments failure counter).
     */
    private static void recordAuthFailure() {
        consecutiveFailures.incrementAndGet();
    }

    /**
     * Runs an authenticator under {@link #AUTH_TIMEOUT_SECONDS}.
     *
     * @throws TimeoutException   when it doesn't finish in time
     * @throws ExecutionException when it throws; the cause is the library's exception
     */
    private static void runWithTimeout( Authenticator authenticator )
            throws TimeoutException, ExecutionException, InterruptedException
    {
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
            throw e;
        }
        finally {
            authExecutor.shutdownNow();
        }
    }

    /**
     * The production {@link AccountManager.Renewer}: exchanges a saved authentication file
     * for fresh tokens with Microsoft, under the shared rate limit and the auth timeout.
     *
     * @param gzippedAuthFile the gzip'd {@code AuthenticationFile}
     *
     * @return the renewed user and updated authentication file
     *
     * @throws AccountManager.RenewalFailedException on timeout, a rejected login, or any other failure
     */
    private static AccountManager.Renewal renewWithMicrosoft( byte[] gzippedAuthFile )
            throws AccountManager.RenewalFailedException
    {
        reportStatus( LocalizationManager.get( "authManager.status.signingIn" ),
                      LocalizationManager.get( "authManager.status.preparingContact" ) );
        enforceRateLimit();
        try {
            AuthenticationFile previous = AuthenticationFile.readCompressed( gzippedAuthFile );
            Logger.logDebug( LocalizationManager.REMEMBERED_USER_LOADED_TEXT );
            Authenticator authenticator =
                    Authenticator.of( previous ).serviceConnectTimeout( 5000 ).serviceReadTimeout( 10000 )
                                 .shouldAuthenticate().shouldRetrieveXBoxProfile().build();
            reportStatus( LocalizationManager.get( "authManager.status.signingIn" ),
                          LocalizationManager.get( "authManager.status.contactingServers" ) );
            Logger.logStd( LocalizationManager.format( "log.authManager.renewingToken", AUTH_TIMEOUT_SECONDS ) );
            runWithTimeout( authenticator );

            User user = authenticator.getUser().orElse( null );
            AuthenticationFile result = authenticator.getResultFile();
            if ( user == null || result == null ) {
                Logger.logStd( LocalizationManager.get( "log.authManager.renewalNoUser" ) );
                recordAuthFailure();
                throw new AccountManager.RenewalFailedException( "renewal returned no user", true );
            }
            consecutiveFailures.set( 0 );
            Logger.logStd( LocalizationManager.get( "log.authManager.tokenRenewedSuccess" ) );
            reportStatus( LocalizationManager.get( "authManager.status.signingIn" ),
                          LocalizationManager.get( "authManager.status.signedIn" ) );
            return new AccountManager.Renewal( user, result.writeCompressed() );
        }
        catch ( TimeoutException e ) {
            Logger.logError( LocalizationManager.format( "log.authManager.authTimedOut", AUTH_TIMEOUT_SECONDS ) );
            recordAuthFailure();
            throw new AccountManager.RenewalFailedException( "timed out", false );
        }
        catch ( ExecutionException e ) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            Logger.logWarningSilent( LocalizationManager.get( "log.authManager.tokenRenewalFailed" ) );
            // Sanitized: log only the exception class. The library wraps OAuth response
            // bodies in exception messages, which can contain token fragments.
            logAuthErrorType( cause );
            recordAuthFailure();
            Exception asException = cause instanceof Exception ex ? ex : new Exception( cause );
            boolean rejected = checkIfExceptionIsInvalidCredentials( asException )
                               || checkIfExceptionIsNotBought( asException );
            throw new AccountManager.RenewalFailedException( cause.getClass().getSimpleName(), rejected );
        }
        catch ( InterruptedException e ) {
            Thread.currentThread().interrupt();
            throw new AccountManager.RenewalFailedException( "interrupted", false );
        }
        catch ( AccountManager.RenewalFailedException e ) {
            throw e;
        }
        catch ( Exception e ) {
            Logger.logWarningSilent( LocalizationManager.PROBLEM_READING_ACCOUNT_FROM_DISK_TEXT );
            logAuthErrorType( e );
            recordAuthFailure();
            throw new AccountManager.RenewalFailedException( e.getClass().getSimpleName(), false );
        }
    }

    // ------------------------------------------------------------ default-account facade

    /**
     * Indicates whether there is a default account to start the launcher with.
     *
     * @return {@code true} if a remembered default account exists (or an old single-account
     *         login that still has to be identified), {@code false} when the login screen
     *         should be shown
     */
    public static boolean hasExistingLogin() {
        return accounts().hasDefault();
    }

    /**
     * Returns the default account's user.
     *
     * @return the default {@link User}, or {@code null} when there is no default account
     */
    public static User getLoggedInUser() {
        return accounts().defaultUser();
    }

    /**
     * Returns the default account's in-flight refresh, or {@code null} when none is
     * pending. Used by UI surfaces that want to wait for the refresh to settle (e.g. the
     * Play-click handler) or display its progress (the main GUI's bottom-bar
     * background-status label).
     */
    public static CompletableFuture< MCLauncherAuthResult > getPendingRefreshFuture() {
        CompletableFuture< User > pending = accounts().pendingRefresh( accounts().defaultUuid() );
        return pending == null ? null : toAuthResult( pending );
    }

    /**
     * Returns the default account's cached user without touching the network, so the main
     * GUI can paint immediately; pair with {@link #renewExistingLoginAsync()} to refresh
     * the token in the background.
     *
     * @return the cached {@link User}, or {@code null} when it is missing or lacks the uuid
     *         and name the main GUI needs, in which case callers should fall through to the
     *         synchronous {@link #renewExistingLogin()} path
     *
     * @since 3.6
     */
    public static User loadCachedUserNow() {
        User cached = accounts().defaultUser();
        if ( cached == null || cached.name() == null || cached.name().isBlank()
                || cached.uuid() == null || cached.uuid().isBlank() ) {
            return null;
        }
        return cached;
    }

    /**
     * Refreshes the default account's token in the background when due, returning a future
     * the caller can await. A refresh already in flight is reused. On the fast path (token
     * still fresh) the future is already complete and nothing touches the network.
     *
     * @since 3.6
     */
    public static CompletableFuture< MCLauncherAuthResult > renewExistingLoginAsync() {
        return toAuthResult( accounts().refreshDefault() );
    }

    /**
     * Signs the default account out and forgets it, deleting its stored credentials. The
     * most recently used remaining account, if any, becomes the default.
     */
    public static void logout() {
        accounts().remove( accounts().defaultUuid() );
    }

    /**
     * Clears the default account while keeping every account signed in, so the next start
     * lands on the login screen to add another account. Signing in there adds the new
     * account and makes it the default.
     *
     * @since 2026.5
     */
    public static void archiveAndLogout() {
        accounts().setDefault( null );
    }

    /**
     * Makes another signed-in account the default.
     *
     * @param targetUuid uuid of the account to make the default
     * @return true if the account exists and is now the default
     * @since 2026.5
     */
    public static boolean switchToArchivedProfile( String targetUuid ) {
        return accounts().setDefault( targetUuid );
    }

    /**
     * If the default account's token is in the {@link #TOKEN_SOFT_REFRESH_INTERVAL_MS}..
     * {@link #TOKEN_REFRESH_INTERVAL_MS} window, kicks off a fire-and-forget background
     * renewal so the next cold start lands inside the "no renewal needed" window again.
     * Every other account whose token is past the soft window is refreshed the same way, so
     * a launch on a non-default account rarely has to wait. Returns immediately.
     *
     * <p>No-op when offline. Accounts past the hard threshold are left to the synchronous
     * paths that handle them.</p>
     *
     * @since 3.5
     */
    public static void tryPreemptiveBackgroundRenewal() {
        try {
            if ( com.micatechnologies.minecraft.launcher.utilities.NetworkUtilities.isOffline() ) {
                return;
            }
            AccountManager manager = accounts();
            String defaultUuid = manager.defaultUuid();
            long now = System.currentTimeMillis();
            for ( AccountManager.AccountInfo account : manager.accounts() ) {
                if ( account.status() == AccountManager.Status.NEEDS_SIGN_IN ) {
                    continue;
                }
                long renewedAt = manager.renewedAtMs( account.uuid() );
                if ( renewedAt == 0 ) {
                    continue;
                }
                long elapsed = now - renewedAt;
                if ( elapsed < TOKEN_SOFT_REFRESH_INTERVAL_MS ) {
                    continue;
                }
                if ( elapsed >= TOKEN_REFRESH_INTERVAL_MS && account.uuid().equals( defaultUuid ) ) {
                    continue;  // past the hard threshold: the startup path renews it
                }
                Logger.logStd( LocalizationManager.get( "log.authManager.softRefreshKickoff" ) );
                manager.refresh( account.uuid(), true ).exceptionally( t -> {
                    Logger.logWarningSilent( LocalizationManager.format( "log.authManager.preemptiveRenewalFailed",
                                                     t.getClass().getSimpleName() ) );
                    return null;
                } );
            }
        }
        catch ( Throwable t ) {
            // Nothing in this opportunistic path should throw onto the startup critical path.
            Logger.logWarningSilent( LocalizationManager.format( "log.authManager.preemptiveRenewalAborted",
                                             t.getClass().getSimpleName() ) );
        }
    }

    /**
     * Synchronously refreshes the default account's session when due.
     *
     * @return a successful {@link MCLauncherAuthResult} wrapping the default {@link User},
     *         {@link MCLauncherAuthResult#ERROR_LOGIN_EXPIRED} when there is no default
     *         account or Microsoft rejected its credentials, or
     *         {@link MCLauncherAuthResult#ERROR_OTHER} on timeout or any other failure
     */
    public static MCLauncherAuthResult renewExistingLogin() {
        reportStatus( LocalizationManager.get( "authManager.status.signingIn" ),
                      LocalizationManager.get( "authManager.status.checkingSession" ) );
        try {
            MCLauncherAuthResult result = renewExistingLoginAsync().get( AUTH_TIMEOUT_SECONDS * 2L, TimeUnit.SECONDS );
            if ( result.getMinecraftUser() != null ) {
                Logger.logStd( LocalizationManager.get( "log.authManager.signInComplete" ) );
            }
            return result;
        }
        catch ( TimeoutException e ) {
            return MCLauncherAuthResult.ERROR_OTHER;
        }
        catch ( InterruptedException e ) {
            Thread.currentThread().interrupt();
            return MCLauncherAuthResult.ERROR_OTHER;
        }
        catch ( ExecutionException e ) {
            return MCLauncherAuthResult.ERROR_OTHER;
        }
    }

    /**
     * Maps an account-manager refresh to the launcher's result type. A rejected login maps
     * to {@link MCLauncherAuthResult#ERROR_LOGIN_EXPIRED}, anything else to
     * {@link MCLauncherAuthResult#ERROR_OTHER}.
     */
    private static CompletableFuture< MCLauncherAuthResult > toAuthResult( CompletableFuture< User > refresh ) {
        return refresh.handle( ( user, failure ) -> {
            if ( failure == null && user != null ) {
                return new MCLauncherAuthResult( user );
            }
            Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                              ? failure.getCause() : failure;
            return cause instanceof AccountManager.RenewalFailedException rf && rf.credentialsRejected()
                   ? MCLauncherAuthResult.ERROR_LOGIN_EXPIRED
                   : MCLauncherAuthResult.ERROR_OTHER;
        } );
    }

    /**
     * Performs a fresh sign-in from a Microsoft OAuth authorization code and adds the account
     * to the signed-in accounts as the new default.
     *
     * <p>Runs the {@code minecraft_authenticator} Microsoft flow under an
     * {@link #AUTH_TIMEOUT_SECONDS} timeout (after {@link #enforceRateLimit() rate
     * limiting}). When {@code save} is {@code false} the account is kept in memory only and
     * any previously remembered copy of it is deleted.</p>
     *
     * @param authCode the Microsoft OAuth authorization code obtained from the
     *                 interactive sign-in flow
     * @param save     {@code true} to remember the account (persist encrypted
     *                 session state); {@code false} for a one-shot sign-in
     * @return a successful {@link MCLauncherAuthResult} wrapping the new
     *         {@link User}, or one of the {@code ERROR_*} sentinels — see
     *         {@link #processAuthException(Exception)} for the failure mapping
     *         ({@link MCLauncherAuthResult#ERROR_NOT_OWNED},
     *         {@link MCLauncherAuthResult#ERROR_BAD_USERNAME_PASSWORD},
     *         {@link MCLauncherAuthResult#ERROR_NO_VAL},
     *         {@link MCLauncherAuthResult#ERROR_OTHER})
     */
    public static MCLauncherAuthResult loginWithMicrosoftAccount( String authCode, boolean save ) {
        enforceRateLimit();
        User user;
        byte[] authFile;
        try {
            final Authenticator authenticator =
                    Authenticator.ofMicrosoft( authCode ).serviceConnectTimeout( 5000 ).serviceReadTimeout( 10000 ).shouldAuthenticate().shouldRetrieveXBoxProfile().build();

            Logger.logStd( LocalizationManager.format( "log.authManager.authenticatingMicrosoft", AUTH_TIMEOUT_SECONDS ) );
            try {
                runWithTimeout( authenticator );
            }
            catch ( TimeoutException e ) {
                Logger.logError( LocalizationManager.format( "log.authManager.microsoftAuthTimedOut", AUTH_TIMEOUT_SECONDS ) );
                recordAuthFailure();
                return MCLauncherAuthResult.ERROR_OTHER;
            }
            catch ( ExecutionException e ) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                recordAuthFailure();
                return processAuthException( cause instanceof Exception ? ( Exception ) cause : new Exception( cause ) );
            }

            Logger.logStd( LocalizationManager.get( "log.authManager.microsoftAuthResponse" ) );
            user = authenticator.getUser().orElse( null );
            AuthenticationFile result = authenticator.getResultFile();
            authFile = result == null ? null : result.writeCompressed();
        }
        catch ( InterruptedException e ) {
            Thread.currentThread().interrupt();
            recordAuthFailure();
            return MCLauncherAuthResult.ERROR_OTHER;
        }
        catch ( Exception e ) {
            recordAuthFailure();
            return processAuthException( e );
        }

        if ( user == null || authFile == null || !accounts().addSignedIn( user, authFile, save ) ) {
            recordAuthFailure();
            return MCLauncherAuthResult.ERROR_BAD_USERNAME_PASSWORD;
        }
        consecutiveFailures.set( 0 );
        return new MCLauncherAuthResult( user );
    }

    /**
     * Maps an authentication exception to the appropriate {@link MCLauncherAuthResult}
     * error sentinel by inspecting its message for known failure signatures.
     *
     * @param e the exception thrown by the authentication flow
     * @return {@link MCLauncherAuthResult#ERROR_NOT_OWNED},
     *         {@link MCLauncherAuthResult#ERROR_BAD_USERNAME_PASSWORD},
     *         {@link MCLauncherAuthResult#ERROR_NO_VAL}, or
     *         {@link MCLauncherAuthResult#ERROR_OTHER} for unrecognized causes
     */
    // Widened from private to package-private for test reach — see
    // MCLauncherAuthManagerExceptionClassificationTest. Pure string-message
    // inspection; no reflection needed.
    static MCLauncherAuthResult processAuthException( Exception e ) {
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
            Logger.logWarningSilent( LocalizationManager.get( "log.authManager.loginException" ) );
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
        Logger.logWarningSilent( LocalizationManager.format( "log.authManager.authErrorType", t.getClass().getName() ) );
    }

    /**
     * Tests whether an exception signals a missing-value failure (an
     * {@code Optional} with "no value present").
     *
     * @param e the exception to inspect
     * @return {@code true} if the message contains "no value present"
     */
    // Widened from private to package-private for test reach — see
    // MCLauncherAuthManagerExceptionClassificationTest.
    static boolean checkIfExceptionIsNoValuePresent( Exception e ) {
        String msg = e.getMessage();
        return msg != null && msg.toLowerCase().contains( "no value present" );
    }

    /**
     * Tests whether an exception signals that the account does not own Minecraft.
     *
     * @param e the exception to inspect
     * @return {@code true} if the message indicates the game has not been bought
     */
    // Widened from private to package-private for test reach — see
    // MCLauncherAuthManagerExceptionClassificationTest.
    static boolean checkIfExceptionIsNotBought( Exception e ) {
        String msg = e.getMessage();
        return msg != null && msg.toLowerCase().contains( "not have bought" );
    }

    /**
     * Tests whether an exception signals invalid credentials.
     *
     * @param e the exception to inspect
     * @return {@code true} if the message contains "invalid credentials"
     */
    // Widened from private to package-private for test reach — see
    // MCLauncherAuthManagerExceptionClassificationTest.
    static boolean checkIfExceptionIsInvalidCredentials( Exception e ) {
        String msg = e.getMessage();
        return msg != null && msg.toLowerCase().contains( "invalid credentials" );
    }
}
