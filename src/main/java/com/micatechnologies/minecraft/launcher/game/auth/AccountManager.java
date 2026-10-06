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

import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
import com.micatechnologies.minecraft.launcher.files.Logger;
import net.hycrafthd.minecraft_authenticator.login.User;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;

/**
 * Every account the launcher is signed in to, and which one is the default.
 *
 * <p>Each account keeps its own session: its {@link User} (name, uuid, access token), when
 * its token was last renewed, and at most one in-flight refresh. All of them stay signed in
 * at once, so changing the default account takes effect immediately, with no restart, and a
 * game can be launched on any of them.</p>
 *
 * <p>Remembered accounts are persisted through {@link AccountStore}, one folder per account.
 * Accounts signed in without "remember me" are held in memory only and are gone after a
 * restart. Calls to Microsoft go through the {@link Renewer}, which in production also
 * applies the launcher-wide rate limit shared by every account.</p>
 *
 * <p>Thread-safe. Listeners run on whichever thread made the change and must hop to the FX
 * thread themselves.</p>
 *
 * @since 2026.10
 */
public final class AccountManager
{
    /**
     * Whether an account can be used to launch right now.
     *
     * @since 2026.10
     */
    public enum Status
    {
        /** Signed in. The token may be due for a refresh, which happens before a launch. */
        READY,
        /** A token refresh is in flight. */
        REFRESHING,
        /** Microsoft rejected the saved credentials; the user has to sign in again. */
        NEEDS_SIGN_IN
    }

    /**
     * A read-only snapshot of one account, for UI lists.
     *
     * @param uuid        the account's Minecraft profile id
     * @param displayName the account's Minecraft username
     * @param status      whether it can be used right now
     * @param isDefault   whether launches use it unless a pack overrides that
     * @param remembered  whether it survives a restart
     * @param lastUsedMs  epoch millis it was last signed in to or made the default
     *
     * @since 2026.10
     */
    public record AccountInfo( String uuid, String displayName, Status status, boolean isDefault,
                               boolean remembered, long lastUsedMs ) { }

    /**
     * Exchanges a saved authentication file for fresh tokens.
     *
     * @since 2026.10
     */
    @FunctionalInterface
    interface Renewer
    {
        /**
         * Renews the session the given authentication file belongs to.
         *
         * @param gzippedAuthFile the gzip'd {@code AuthenticationFile}
         *
         * @return the renewed user and the updated authentication file
         *
         * @throws RenewalFailedException when the renewal fails
         * @since 2026.10
         */
        Renewal renew( byte[] gzippedAuthFile ) throws RenewalFailedException;
    }

    /**
     * The result of a successful renewal.
     *
     * @param user            the renewed user, with a fresh access token
     * @param gzippedAuthFile the updated authentication file to persist
     *
     * @since 2026.10
     */
    record Renewal( User user, byte[] gzippedAuthFile ) { }

    /**
     * A failed renewal.
     *
     * @since 2026.10
     */
    static final class RenewalFailedException extends Exception
    {
        private final boolean credentialsRejected;

        /**
         * @param message             what failed, without any token material
         * @param credentialsRejected {@code true} when Microsoft refused the saved credentials,
         *                            so retrying can't help and the user must sign in again;
         *                            {@code false} for timeouts and other transient failures
         *
         * @since 2026.10
         */
        RenewalFailedException( String message, boolean credentialsRejected )
        {
            super( message );
            this.credentialsRejected = credentialsRejected;
        }

        /**
         * @return whether the saved credentials were rejected outright
         *
         * @since 2026.10
         */
        boolean credentialsRejected()
        {
            return credentialsRejected;
        }
    }

    /**
     * Where the default account's uuid is persisted.
     *
     * @since 2026.10
     */
    interface DefaultAccountSetting
    {
        /**
         * @return the persisted default uuid, or {@code ""} when there is none
         *
         * @since 2026.10
         */
        String get();

        /**
         * @param uuid the new default uuid, or {@code ""} for none
         *
         * @since 2026.10
         */
        void set( String uuid );
    }

    /** One account's in-memory state. Guarded by the manager's monitor. */
    private static final class Session
    {
        final String uuid;
        String  displayName;
        User    user;
        long    renewedAtMs;
        long    lastUsedMs;
        boolean remembered;
        Status  status = Status.READY;
        /** The authentication file of a session-only account, which is never written to disk. */
        byte[]  memoryAuthFile;
        CompletableFuture< User > pending;

        Session( String uuid )
        {
            this.uuid = uuid;
        }
    }

    private final AccountStore          store;
    private final Path                  legacyFolder;
    private final AccountCipher         cipher;
    private final DefaultAccountSetting defaultSetting;
    private final Renewer               renewer;
    private final LongSupplier          clock;
    private final Executor              executor;
    private final long                  refreshIntervalMs;

    private final Map< String, Session >     sessions  = new LinkedHashMap<>();
    private final List< Runnable >           listeners = new CopyOnWriteArrayList<>();
    private       String                     defaultUuid;
    private       boolean                    loaded;
    /**
     * An old flat-layout authentication file whose account couldn't be identified because its
     * cached user was unreadable. Renewing it reveals the account, which is then stored
     * normally; see {@link #refreshDefault()}.
     */
    private       byte[]                     unidentifiedLegacyAuthFile;

    /**
     * Creates a manager. Production code uses {@link MCLauncherAuthManager#accounts()}.
     *
     * @param store             persistence for remembered accounts
     * @param legacyFolder      the folder that held the old single-account files
     *                          ({@code <config>/}), migrated on first load
     * @param cipher            the cipher the legacy files were written with
     * @param defaultSetting    where the default account is persisted
     * @param renewer           exchanges saved credentials for fresh tokens
     * @param clock             epoch-millis clock
     * @param executor          runs refreshes off the caller's thread
     * @param refreshIntervalMs how old a token may get before a refresh is due
     *
     * @since 2026.10
     */
    AccountManager( AccountStore store, Path legacyFolder, AccountCipher cipher,
                    DefaultAccountSetting defaultSetting, Renewer renewer, LongSupplier clock,
                    Executor executor, long refreshIntervalMs )
    {
        this.store = store;
        this.legacyFolder = legacyFolder;
        this.cipher = cipher;
        this.defaultSetting = defaultSetting;
        this.renewer = renewer;
        this.clock = clock;
        this.executor = executor;
        this.refreshIntervalMs = refreshIntervalMs;
    }

    // ---------------------------------------------------------------- loading

    /**
     * Loads stored accounts on first use, migrating the old single-account files first.
     */
    private synchronized void ensureLoaded()
    {
        if ( loaded ) {
            return;
        }
        loaded = true;
        String migratedUuid = migrateLegacyLayout();
        for ( AccountStore.Entry entry : store.list() ) {
            Session session = new Session( entry.uuid() );
            session.remembered = true;
            session.user = store.readCachedUser( entry.uuid() );
            session.renewedAtMs = store.readRenewalMs( entry.uuid() );
            session.lastUsedMs = entry.lastUsedMs();
            session.displayName = session.user != null ? session.user.name() : entry.displayName();
            sessions.put( entry.uuid(), session );
        }
        if ( migratedUuid != null ) {
            // The flat files were the active account, so it was the default by definition.
            defaultSetting.set( migratedUuid );
        }
        defaultUuid = resolveDefault( defaultSetting.get(), sessions.keySet() );
    }

    /**
     * Moves the old single-account files ({@code <config>/player.mica}, {@code cached_user.json},
     * {@code renewal.timestamp}) into the per-account store. Copy, verify, then delete, so a
     * crash part-way leaves the flat files in place and the next start simply repeats the
     * migration. The flat copy is the newest one, so it overwrites any stale archived copy of
     * the same account.
     *
     * @return the migrated account's uuid, or {@code null} when nothing was migrated
     */
    private String migrateLegacyLayout()
    {
        Path legacyAuth = legacyFolder.resolve( AccountStore.AUTH_FILE );
        if ( !Files.isRegularFile( legacyAuth ) ) {
            return null;
        }
        byte[] authFile = AccountStore.readAuthFileAt( legacyAuth, cipher );
        if ( authFile == null ) {
            // Unreadable here (another machine, or corrupt): it can never be used, so it is
            // dropped rather than migrated. The user signs in again, exactly as before.
            Logger.logWarningSilent( LocalizationManager.get( "log.accountStore.legacyUnreadable" ) );
            deleteLegacyFiles();
            return null;
        }
        User user = AccountStore.readCachedUserAt( legacyFolder.resolve( AccountStore.CACHED_USER_FILE ), cipher );
        if ( user == null || !AccountStore.isSafeUuid( user.uuid() ) ) {
            // The credentials are fine but nothing says whose they are. Keep them in memory;
            // the first renewal identifies the account and stores it.
            unidentifiedLegacyAuthFile = authFile;
            return null;
        }
        long renewedAt = AccountStore.readRenewalMsAt( legacyFolder.resolve( AccountStore.RENEWAL_FILE ), cipher );
        try {
            persist( user, authFile, renewedAt, clock.getAsLong() );
            if ( store.readAuthFile( user.uuid() ) == null ) {
                throw new IOException( "migrated authentication file did not read back" );
            }
        }
        catch ( IOException e ) {
            Logger.logWarningSilent( LocalizationManager.format( "log.accountStore.migrationFailed", e.getMessage() ) );
            return null;
        }
        deleteLegacyFiles();
        Logger.logStd( LocalizationManager.get( "log.accountStore.migrated" ) );
        return user.uuid();
    }

    private void deleteLegacyFiles()
    {
        for ( String name : new String[]{ AccountStore.AUTH_FILE, AccountStore.CACHED_USER_FILE,
                                          AccountStore.RENEWAL_FILE } ) {
            try {
                Files.deleteIfExists( legacyFolder.resolve( name ) );
            }
            catch ( IOException e ) {
                Logger.logWarningSilent( LocalizationManager.format( "log.accountStore.legacyDeleteFailed", name ) );
            }
        }
    }

    /**
     * Picks the default account: the configured one if it is still signed in, otherwise none.
     * Pure, for testing.
     *
     * @param configured the persisted default uuid, possibly blank
     * @param available  the uuids of signed-in accounts
     *
     * @return the default uuid, or {@code null}
     *
     * @since 2026.10
     */
    static String resolveDefault( String configured, Collection< String > available )
    {
        if ( configured == null || configured.isBlank() ) {
            return null;
        }
        return available.contains( configured ) ? configured : null;
    }

    // ---------------------------------------------------------------- queries

    /**
     * Every signed-in account, most recently used first.
     *
     * @return snapshots of each account; never {@code null}
     *
     * @since 2026.10
     */
    public synchronized List< AccountInfo > accounts()
    {
        ensureLoaded();
        List< AccountInfo > out = new ArrayList<>();
        for ( Session s : byRecentUse() ) {
            out.add( snapshot( s ) );
        }
        return out;
    }

    /**
     * One account's snapshot.
     *
     * @param uuid the account uuid
     *
     * @return the snapshot, or {@code null} when no such account is signed in
     *
     * @since 2026.10
     */
    public synchronized AccountInfo account( String uuid )
    {
        ensureLoaded();
        Session s = uuid == null ? null : sessions.get( uuid );
        return s == null ? null : snapshot( s );
    }

    /**
     * The user for an account, carrying its current access token.
     *
     * @param uuid the account uuid
     *
     * @return the user, or {@code null} when the account is unknown or not yet identified
     *
     * @since 2026.10
     */
    public synchronized User user( String uuid )
    {
        ensureLoaded();
        Session s = uuid == null ? null : sessions.get( uuid );
        return s == null ? null : s.user;
    }

    /**
     * @return the default account's uuid, or {@code null} when there is none
     *
     * @since 2026.10
     */
    public synchronized String defaultUuid()
    {
        ensureLoaded();
        return defaultUuid;
    }

    /**
     * @return the default account's user, or {@code null} when there is none
     *
     * @since 2026.10
     */
    public synchronized User defaultUser()
    {
        return user( defaultUuid() );
    }

    /**
     * Whether there is a default account to start the launcher with, including an old
     * single-account login that still has to be identified by its first renewal.
     *
     * @return {@code true} when the login screen can be skipped
     *
     * @since 2026.10
     */
    public synchronized boolean hasDefault()
    {
        ensureLoaded();
        return defaultUuid != null || unidentifiedLegacyAuthFile != null;
    }

    /**
     * Whether an account's token is old enough, or its user unknown, that a refresh is due.
     *
     * @param uuid the account uuid
     *
     * @return {@code true} when a refresh is due; {@code false} for unknown accounts
     *
     * @since 2026.10
     */
    public synchronized boolean isRefreshDue( String uuid )
    {
        ensureLoaded();
        Session s = uuid == null ? null : sessions.get( uuid );
        return s != null && ( s.user == null
                              || MCLauncherAuthManager.isRenewalDue( s.renewedAtMs, clock.getAsLong(),
                                                                     refreshIntervalMs ) );
    }

    /**
     * When an account's token was last renewed.
     *
     * @param uuid the account uuid
     *
     * @return epoch millis, or {@code 0} when unknown
     *
     * @since 2026.10
     */
    public synchronized long renewedAtMs( String uuid )
    {
        ensureLoaded();
        Session s = uuid == null ? null : sessions.get( uuid );
        return s == null ? 0L : s.renewedAtMs;
    }

    /**
     * The in-flight refresh of an account, if any.
     *
     * @param uuid the account uuid
     *
     * @return the pending refresh, or {@code null} when none is running
     *
     * @since 2026.10
     */
    public synchronized CompletableFuture< User > pendingRefresh( String uuid )
    {
        ensureLoaded();
        Session s = uuid == null ? null : sessions.get( uuid );
        return s == null || s.pending == null || s.pending.isDone() ? null : s.pending;
    }

    // ---------------------------------------------------------------- changes

    /**
     * Makes an account the default for launches. Takes effect immediately.
     *
     * @param uuid the account to make the default, or {@code null} for none (the next start
     *             then shows the login screen while keeping every account signed in)
     *
     * @return {@code true} when the default is now {@code uuid}
     *
     * @since 2026.10
     */
    public boolean setDefault( String uuid )
    {
        synchronized ( this ) {
            ensureLoaded();
            if ( uuid != null && !sessions.containsKey( uuid ) ) {
                return false;
            }
            if ( java.util.Objects.equals( uuid, defaultUuid ) ) {
                return true;
            }
            defaultUuid = uuid;
            if ( uuid != null ) {
                touch( sessions.get( uuid ) );
            }
            persistDefault();
        }
        fireChanged();
        return true;
    }

    /**
     * Adds or replaces an account after an interactive Microsoft sign-in, and makes it the
     * default. Equivalent to {@code addSignedIn(user, gzippedAuthFile, remember, true)}.
     *
     * @param user            the signed-in user
     * @param gzippedAuthFile the authentication file from the sign-in
     * @param remember        whether the account should survive a restart
     *
     * @return {@code true} on success; {@code false} when the user has no usable uuid
     *
     * @since 2026.10
     */
    public boolean addSignedIn( User user, byte[] gzippedAuthFile, boolean remember )
    {
        return addSignedIn( user, gzippedAuthFile, remember, true );
    }

    /**
     * Adds or replaces an account after an interactive Microsoft sign-in. When
     * {@code remember} is {@code false} the account is held in memory only and
     * any copy stored by an earlier "remember me" sign-in is deleted, so the account can't be
     * silently resurrected on the next start.
     *
     * @param user            the signed-in user
     * @param gzippedAuthFile the authentication file from the sign-in
     * @param remember        whether the account should survive a restart
     * @param makeDefault     whether it becomes the default; an account added while there is
     *                        no default becomes the default regardless
     *
     * @return {@code true} on success; {@code false} when the user has no usable uuid
     *
     * @since 2026.10
     */
    public boolean addSignedIn( User user, byte[] gzippedAuthFile, boolean remember, boolean makeDefault )
    {
        if ( user == null || !AccountStore.isSafeUuid( user.uuid() ) ) {
            return false;
        }
        synchronized ( this ) {
            ensureLoaded();
            long now = clock.getAsLong();
            Session session = sessions.computeIfAbsent( user.uuid(), Session::new );
            session.user = user;
            session.displayName = user.name();
            session.renewedAtMs = now;
            session.lastUsedMs = now;
            session.status = Status.READY;
            session.remembered = remember;
            if ( remember ) {
                session.memoryAuthFile = null;
                try {
                    persist( user, gzippedAuthFile, now, now );
                }
                catch ( IOException e ) {
                    // Keep the sign-in usable for this session even though it won't survive
                    // a restart; the next sign-in tries to persist again.
                    Logger.logErrorAsync( LocalizationManager.format( "log.accountStore.persistFailed", e.getMessage() ) );
                    session.remembered = false;
                    session.memoryAuthFile = gzippedAuthFile;
                }
            }
            else {
                session.memoryAuthFile = gzippedAuthFile;
                store.remove( user.uuid() );
            }
            if ( makeDefault || defaultUuid == null ) {
                defaultUuid = user.uuid();
            }
            persistDefault();
        }
        fireChanged();
        return true;
    }

    /**
     * Signs an account out and forgets it, deleting its stored credentials. When it was the
     * default, the most recently used remaining account becomes the default.
     *
     * @param uuid the account to remove
     *
     * @return {@code true} when the account was signed in and is now gone
     *
     * @since 2026.10
     */
    public boolean remove( String uuid )
    {
        synchronized ( this ) {
            ensureLoaded();
            Session session = uuid == null ? null : sessions.remove( uuid );
            if ( session == null ) {
                return false;
            }
            if ( session.remembered ) {
                store.remove( uuid );
            }
            if ( uuid.equals( defaultUuid ) ) {
                List< Session > rest = byRecentUse();
                defaultUuid = rest.isEmpty() ? null : rest.get( 0 ).uuid;
                persistDefault();
            }
        }
        fireChanged();
        return true;
    }

    /**
     * Refreshes an account's token when it is due, or always when {@code force} is set. At
     * most one refresh per account is in flight; a second call while one runs returns the
     * same future. Refreshes of different accounts are independent.
     *
     * <p>When the token is fresh and the user known, the returned future is already
     * complete and nothing touches the network.</p>
     *
     * @param uuid  the account to refresh
     * @param force refresh even when the token is still fresh
     *
     * @return the refreshed user; completes exceptionally with a
     *         {@link RenewalFailedException} when the refresh fails or the account is unknown
     *
     * @since 2026.10
     */
    public CompletableFuture< User > refresh( String uuid, boolean force )
    {
        CompletableFuture< User > future;
        byte[] authFile;
        synchronized ( this ) {
            ensureLoaded();
            Session session = uuid == null ? null : sessions.get( uuid );
            if ( session == null ) {
                return CompletableFuture.failedFuture( new RenewalFailedException( "unknown account", false ) );
            }
            if ( session.pending != null && !session.pending.isDone() ) {
                return session.pending;
            }
            if ( !force && !isRefreshDue( uuid ) ) {
                return CompletableFuture.completedFuture( session.user );
            }
            authFile = session.remembered ? store.readAuthFile( uuid ) : session.memoryAuthFile;
            if ( authFile == null ) {
                session.status = Status.NEEDS_SIGN_IN;
                fireChangedLater();
                return CompletableFuture.failedFuture(
                        new RenewalFailedException( "no saved credentials", true ) );
            }
            future = new CompletableFuture<>();
            session.pending = future;
            session.status = Status.REFRESHING;
        }
        fireChanged();
        byte[] toRenew = authFile;
        executor.execute( () -> completeRefresh( uuid, toRenew, future ) );
        return future;
    }

    /**
     * Refreshes the default account when due, the way the launcher's startup path needs it.
     * Also identifies an old single-account login whose cached user was unreadable: its
     * renewal reveals the account, which is then stored and made the default.
     *
     * @return the default user after any refresh; fails like {@link #refresh(String, boolean)}
     *
     * @since 2026.10
     */
    public CompletableFuture< User > refreshDefault()
    {
        byte[] legacy;
        String current;
        synchronized ( this ) {
            ensureLoaded();
            legacy = unidentifiedLegacyAuthFile;
            current = defaultUuid;
        }
        if ( current != null || legacy == null ) {
            return current == null
                   ? CompletableFuture.failedFuture( new RenewalFailedException( "no default account", true ) )
                   : refresh( current, false );
        }
        CompletableFuture< User > future = new CompletableFuture<>();
        executor.execute( () -> {
            try {
                Renewal renewal = renewer.renew( legacy );
                synchronized ( this ) {
                    unidentifiedLegacyAuthFile = null;
                }
                deleteLegacyFiles();
                addSignedIn( renewal.user(), renewal.gzippedAuthFile(), true );
                future.complete( renewal.user() );
            }
            catch ( RenewalFailedException e ) {
                if ( e.credentialsRejected() ) {
                    synchronized ( this ) {
                        unidentifiedLegacyAuthFile = null;
                    }
                    deleteLegacyFiles();
                }
                future.completeExceptionally( e );
            }
            catch ( RuntimeException e ) {
                future.completeExceptionally( e );
            }
        } );
        return future;
    }

    /**
     * Registers a listener for any change to the account list, an account's status, or the
     * default.
     *
     * @param listener the listener; runs on the thread that made the change
     *
     * @since 2026.10
     */
    public void addListener( Runnable listener )
    {
        listeners.add( listener );
    }

    /**
     * @param listener a listener added with {@link #addListener(Runnable)}
     *
     * @since 2026.10
     */
    public void removeListener( Runnable listener )
    {
        listeners.remove( listener );
    }

    // ---------------------------------------------------------------- internals

    private void completeRefresh( String uuid, byte[] authFile, CompletableFuture< User > future )
    {
        Renewal renewal = null;
        RenewalFailedException failure = null;
        try {
            renewal = renewer.renew( authFile );
            if ( renewal == null || renewal.user() == null || !uuid.equals( renewal.user().uuid() ) ) {
                // A refresh that comes back as a different account would silently swap the
                // identity behind this entry. Treat it as a failed refresh.
                failure = new RenewalFailedException( "renewal returned a different account", true );
                renewal = null;
            }
        }
        catch ( RenewalFailedException e ) {
            failure = e;
        }
        catch ( RuntimeException e ) {
            failure = new RenewalFailedException( e.getClass().getSimpleName(), false );
        }

        synchronized ( this ) {
            Session session = sessions.get( uuid );
            if ( session != null && session.pending == future ) {
                session.pending = null;
                if ( renewal != null ) {
                    long now = clock.getAsLong();
                    session.user = renewal.user();
                    session.displayName = renewal.user().name();
                    session.renewedAtMs = now;
                    session.status = Status.READY;
                    if ( session.remembered ) {
                        try {
                            persist( renewal.user(), renewal.gzippedAuthFile(), now, session.lastUsedMs );
                        }
                        catch ( IOException e ) {
                            Logger.logErrorAsync( LocalizationManager.format( "log.accountStore.persistFailed",
                                                                         e.getMessage() ) );
                        }
                    }
                    else {
                        session.memoryAuthFile = renewal.gzippedAuthFile();
                    }
                }
                else {
                    // A transient failure leaves the account usable on its current token,
                    // which outlives the refresh interval by hours.
                    session.status = failure.credentialsRejected() ? Status.NEEDS_SIGN_IN : Status.READY;
                }
            }
            // A session removed while its refresh ran stays removed; nothing is persisted.
        }
        fireChanged();
        if ( renewal != null ) {
            future.complete( renewal.user() );
        }
        else {
            future.completeExceptionally( failure );
        }
    }

    /** Writes every file of a remembered account. Caller holds the monitor. */
    private void persist( User user, byte[] authFile, long renewedAtMs, long lastUsedMs ) throws IOException
    {
        store.writeAuthFile( user.uuid(), authFile );
        store.writeCachedUser( user );
        store.writeRenewalMs( user.uuid(), renewedAtMs );
        store.writeMeta( user.uuid(), user.name(), lastUsedMs );
    }

    /** Marks an account as just used, persisting it for remembered accounts. Caller holds the monitor. */
    private void touch( Session session )
    {
        session.lastUsedMs = clock.getAsLong();
        if ( session.remembered ) {
            try {
                store.writeMeta( session.uuid, session.displayName, session.lastUsedMs );
            }
            catch ( IOException e ) {
                Logger.logWarningSilent( LocalizationManager.format( "log.accountStore.persistFailed", e.getMessage() ) );
            }
        }
    }

    /** Sessions, most recently used first. Caller holds the monitor. */
    private List< Session > byRecentUse()
    {
        List< Session > list = new ArrayList<>( sessions.values() );
        list.sort( Comparator.comparingLong( ( Session s ) -> s.lastUsedMs ).reversed() );
        return list;
    }

    /**
     * Persists the default. A session-only account can't be the default after a restart, so
     * the most recently used remembered account is persisted in its place. Caller holds the
     * monitor.
     */
    private void persistDefault()
    {
        Session current = defaultUuid == null ? null : sessions.get( defaultUuid );
        if ( current == null || current.remembered ) {
            defaultSetting.set( defaultUuid == null ? "" : defaultUuid );
            return;
        }
        for ( Session s : byRecentUse() ) {
            if ( s.remembered ) {
                defaultSetting.set( s.uuid );
                return;
            }
        }
        defaultSetting.set( "" );
    }

    private AccountInfo snapshot( Session s )
    {
        return new AccountInfo( s.uuid, s.displayName == null ? "" : s.displayName, s.status,
                                s.uuid.equals( defaultUuid ), s.remembered, s.lastUsedMs );
    }

    private void fireChanged()
    {
        for ( Runnable listener : listeners ) {
            try {
                listener.run();
            }
            catch ( RuntimeException e ) {
                Logger.logWarningSilent( LocalizationManager.format( "log.accountStore.listenerFailed",
                                                                     e.getClass().getSimpleName() ) );
            }
        }
    }

    /** Fires listeners from the executor; used where the caller holds the monitor. */
    private void fireChangedLater()
    {
        executor.execute( this::fireChanged );
    }
}
