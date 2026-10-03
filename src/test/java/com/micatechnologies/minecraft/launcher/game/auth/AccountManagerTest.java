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

import net.hycrafthd.minecraft_authenticator.login.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static com.micatechnologies.minecraft.launcher.game.auth.AccountTestSupport.FAKE_CIPHER;
import static com.micatechnologies.minecraft.launcher.game.auth.AccountTestSupport.OTHER_MACHINE;
import static com.micatechnologies.minecraft.launcher.game.auth.AccountTestSupport.authFile;
import static com.micatechnologies.minecraft.launcher.game.auth.AccountTestSupport.user;
import static com.micatechnologies.minecraft.launcher.game.auth.AccountTestSupport.uuid;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link AccountManager}: several accounts signed in at once, instant default
 * switching, per-account refreshes, and the one-shot migration from the old single-account
 * files.
 *
 * <p>Everything is injected: a temp-dir store with a fake cipher, an in-memory default
 * setting, a scripted renewer, a settable clock, and an executor the test drains by hand, so
 * "a refresh is in flight" is a state the test controls rather than a race it hopes to
 * win.</p>
 */
class AccountManagerTest
{
    private static final long INTERVAL = 4L * 60 * 60 * 1000;
    private static final long T0       = 1_700_000_000_000L;

    @TempDir
    Path tempDir;

    private Path              legacyFolder;
    private AccountStore      store;
    private final AtomicLong  now      = new AtomicLong( T0 );
    private final Deque< Runnable > queue = new ArrayDeque<>();
    private final Executor    manual   = queue::add;
    private final Executor    inline   = Runnable::run;
    private final InMemoryDefault defaults = new InMemoryDefault();
    private ScriptedRenewer   renewer;

    @BeforeEach
    void setUp()
    {
        legacyFolder = tempDir;
        store = new AccountStore( tempDir.resolve( AccountStore.PROFILES_DIR ), FAKE_CIPHER );
        renewer = new ScriptedRenewer();
    }

    private AccountManager manager( Executor executor )
    {
        return new AccountManager( store, legacyFolder, FAKE_CIPHER, defaults, renewer, now::get, executor,
                                   INTERVAL );
    }

    /** Drains the manual executor. */
    private void runQueued()
    {
        while ( !queue.isEmpty() ) {
            queue.poll().run();
        }
    }

    // =========================================================================
    //  Adding, persisting, restarting
    // =========================================================================

    @Test
    void noAccountsMeansNoDefault()
    {
        AccountManager m = manager( inline );
        assertTrue( m.accounts().isEmpty() );
        assertFalse( m.hasDefault() );
        assertNull( m.defaultUser() );
    }

    @Test
    void aRememberedSignInSurvivesARestartAsTheDefault()
    {
        manager( inline ).addSignedIn( user( 'a', "token-a" ), authFile( "a" ), true );

        AccountManager restarted = manager( inline );
        assertEquals( uuid( 'a' ), restarted.defaultUuid() );
        assertEquals( "token-a", restarted.defaultUser().accessToken() );
        assertTrue( restarted.account( uuid( 'a' ) ).remembered() );
    }

    @Test
    void severalAccountsStaySignedInTogether()
    {
        AccountManager m = manager( inline );
        m.addSignedIn( user( 'a', "ta" ), authFile( "a" ), true );
        now.addAndGet( 1000 );
        m.addSignedIn( user( 'b', "tb" ), authFile( "b" ), true );

        assertEquals( List.of( uuid( 'b' ), uuid( 'a' ) ), uuids( m.accounts() ) );
        assertEquals( "ta", m.user( uuid( 'a' ) ).accessToken() );
        assertEquals( "tb", m.user( uuid( 'b' ) ).accessToken() );
        assertEquals( uuid( 'b' ), m.defaultUuid(), "the newest sign-in becomes the default" );
        assertEquals( 2, manager( inline ).accounts().size(), "both are on disk" );
    }

    @Test
    void aSessionOnlySignInIsNeverWrittenAndIsGoneAfterARestart()
    {
        AccountManager m = manager( inline );
        m.addSignedIn( user( 'a', "ta" ), authFile( "a" ), true );
        now.addAndGet( 1000 );
        m.addSignedIn( user( 'b', "tb" ), authFile( "b" ), false );

        assertEquals( uuid( 'b' ), m.defaultUuid() );
        assertFalse( m.account( uuid( 'b' ) ).remembered() );
        assertFalse( Files.exists( tempDir.resolve( AccountStore.PROFILES_DIR ).resolve( uuid( 'b' ) ) ) );

        AccountManager restarted = manager( inline );
        assertNull( restarted.account( uuid( 'b' ) ) );
        assertEquals( uuid( 'a' ), restarted.defaultUuid(),
                      "the remembered account stands in as the default after a restart" );
    }

    @Test
    void signingInAgainWithoutRememberMeDeletesTheStoredCopy()
    {
        AccountManager m = manager( inline );
        m.addSignedIn( user( 'a', "ta" ), authFile( "a" ), true );
        m.addSignedIn( user( 'a', "ta2" ), authFile( "a2" ), false );

        assertNull( store.readAuthFile( uuid( 'a' ) ) );
        assertNull( manager( inline ).account( uuid( 'a' ) ) );
    }

    @Test
    void aUserWithoutAUsableUuidIsRefused()
    {
        AccountManager m = manager( inline );
        assertFalse( m.addSignedIn( new User( "../evil", "x", "t", "msa", "", "" ), authFile( "x" ), true ) );
        assertFalse( m.addSignedIn( null, authFile( "x" ), true ) );
        assertTrue( m.accounts().isEmpty() );
    }

    // =========================================================================
    //  Default account
    // =========================================================================

    @Test
    void switchingTheDefaultIsImmediateAndNotifiesListeners()
    {
        AccountManager m = manager( inline );
        m.addSignedIn( user( 'a', "ta" ), authFile( "a" ), true );
        m.addSignedIn( user( 'b', "tb" ), authFile( "b" ), true );
        AtomicInteger changes = new AtomicInteger();
        m.addListener( changes::incrementAndGet );

        assertTrue( m.setDefault( uuid( 'a' ) ) );
        assertEquals( "ta", m.defaultUser().accessToken() );
        assertEquals( 1, changes.get() );
        assertEquals( uuid( 'a' ), defaults.value );
        assertTrue( m.setDefault( uuid( 'a' ) ), "re-selecting the default is a no-op success" );
        assertEquals( 1, changes.get() );
    }

    @Test
    void anUnknownAccountCannotBecomeTheDefault()
    {
        AccountManager m = manager( inline );
        m.addSignedIn( user( 'a', "ta" ), authFile( "a" ), true );
        assertFalse( m.setDefault( uuid( 'f' ) ) );
        assertEquals( uuid( 'a' ), m.defaultUuid() );
    }

    @Test
    void clearingTheDefaultKeepsEveryAccount()
    {
        AccountManager m = manager( inline );
        m.addSignedIn( user( 'a', "ta" ), authFile( "a" ), true );
        assertTrue( m.setDefault( null ) );

        assertFalse( m.hasDefault() );
        assertEquals( 1, m.accounts().size() );
        assertFalse( manager( inline ).hasDefault(), "the next start shows the login screen" );
    }

    @Test
    void removingTheDefaultPromotesTheMostRecentlyUsedAccount()
    {
        AccountManager m = manager( inline );
        m.addSignedIn( user( 'a', "ta" ), authFile( "a" ), true );
        now.addAndGet( 1000 );
        m.addSignedIn( user( 'b', "tb" ), authFile( "b" ), true );
        now.addAndGet( 1000 );
        m.addSignedIn( user( 'c', "tc" ), authFile( "c" ), true );
        now.addAndGet( 1000 );
        m.setDefault( uuid( 'a' ) );  // a is now the most recently used...

        assertTrue( m.remove( uuid( 'a' ) ) );
        assertEquals( uuid( 'c' ), m.defaultUuid(), "...so c, the next most recent, takes over" );
        assertNull( store.readAuthFile( uuid( 'a' ) ) );
        assertFalse( m.remove( uuid( 'a' ) ) );
    }

    @Test
    void resolveDefaultOnlyAcceptsASignedInAccount()
    {
        assertEquals( "a", AccountManager.resolveDefault( "a", Set.of( "a", "b" ) ) );
        assertNull( AccountManager.resolveDefault( "z", Set.of( "a", "b" ) ) );
        assertNull( AccountManager.resolveDefault( "", Set.of( "a" ) ) );
        assertNull( AccountManager.resolveDefault( null, Set.of( "a" ) ) );
    }

    // =========================================================================
    //  Refreshing
    // =========================================================================

    @Test
    void aFreshTokenIsNotRefreshed() throws Exception
    {
        AccountManager m = manager( inline );
        m.addSignedIn( user( 'a', "ta" ), authFile( "a" ), true );

        assertEquals( "ta", m.refresh( uuid( 'a' ), false ).get().accessToken() );
        assertEquals( 0, renewer.calls.get() );
    }

    @Test
    void aStaleTokenIsRefreshedAndPersisted() throws Exception
    {
        AccountManager m = manager( inline );
        m.addSignedIn( user( 'a', "old" ), authFile( "a" ), true );
        now.addAndGet( INTERVAL );
        renewer.next = in -> new AccountManager.Renewal( user( 'a', "new" ), authFile( "a-renewed" ) );

        assertEquals( "new", m.refresh( uuid( 'a' ), false ).get().accessToken() );
        assertArrayEquals( authFile( "a" ), renewer.lastInput, "renewed from the stored credentials" );
        assertArrayEquals( authFile( "a-renewed" ), store.readAuthFile( uuid( 'a' ) ) );
        assertEquals( now.get(), store.readRenewalMs( uuid( 'a' ) ) );
        assertEquals( "new", manager( inline ).user( uuid( 'a' ) ).accessToken() );
    }

    @Test
    void oneRefreshPerAccountIsInFlightAtATime()
    {
        AccountManager m = manager( manual );
        m.addSignedIn( user( 'a', "ta" ), authFile( "a" ), true );
        m.addSignedIn( user( 'b', "tb" ), authFile( "b" ), true );
        now.addAndGet( INTERVAL );

        CompletableFuture< User > first = m.refresh( uuid( 'a' ), false );
        CompletableFuture< User > second = m.refresh( uuid( 'a' ), true );
        CompletableFuture< User > other = m.refresh( uuid( 'b' ), false );
        assertSame( first, second, "a second request joins the one in flight" );
        assertFalse( first == other, "another account refreshes independently" );
        assertEquals( AccountManager.Status.REFRESHING, m.account( uuid( 'a' ) ).status() );
        assertSame( first, m.pendingRefresh( uuid( 'a' ) ) );

        runQueued();
        assertEquals( 2, renewer.calls.get() );
        assertTrue( first.isDone() && other.isDone() );
        assertNull( m.pendingRefresh( uuid( 'a' ) ) );
        assertEquals( AccountManager.Status.READY, m.account( uuid( 'a' ) ).status() );
    }

    @Test
    void rejectedCredentialsMeanSignInAgain()
    {
        AccountManager m = manager( inline );
        m.addSignedIn( user( 'a', "ta" ), authFile( "a" ), true );
        renewer.next = in -> { throw new AccountManager.RenewalFailedException( "invalid credentials", true ); };

        ExecutionException e = assertThrows( ExecutionException.class, () -> m.refresh( uuid( 'a' ), true ).get() );
        assertInstanceOf( AccountManager.RenewalFailedException.class, e.getCause() );
        assertEquals( AccountManager.Status.NEEDS_SIGN_IN, m.account( uuid( 'a' ) ).status() );
    }

    @Test
    void aTransientFailureKeepsTheAccountUsable()
    {
        AccountManager m = manager( inline );
        m.addSignedIn( user( 'a', "ta" ), authFile( "a" ), true );
        renewer.next = in -> { throw new AccountManager.RenewalFailedException( "timed out", false ); };

        assertThrows( ExecutionException.class, () -> m.refresh( uuid( 'a' ), true ).get() );
        assertEquals( AccountManager.Status.READY, m.account( uuid( 'a' ) ).status() );
        assertEquals( "ta", m.user( uuid( 'a' ) ).accessToken(), "the current token is kept" );
    }

    @Test
    void aRenewalThatComesBackAsAnotherAccountIsRejected()
    {
        AccountManager m = manager( inline );
        m.addSignedIn( user( 'a', "ta" ), authFile( "a" ), true );
        renewer.next = in -> new AccountManager.Renewal( user( 'b', "tb" ), authFile( "b" ) );

        assertThrows( ExecutionException.class, () -> m.refresh( uuid( 'a' ), true ).get() );
        assertEquals( "ta", m.user( uuid( 'a' ) ).accessToken() );
        assertNull( m.account( uuid( 'b' ) ), "no account appears out of a refresh" );
    }

    @Test
    void aSessionOnlyAccountRefreshesFromMemory() throws Exception
    {
        AccountManager m = manager( inline );
        m.addSignedIn( user( 'a', "ta" ), authFile( "mem" ), false );
        renewer.next = in -> new AccountManager.Renewal( user( 'a', "ta2" ), authFile( "mem2" ) );

        assertEquals( "ta2", m.refresh( uuid( 'a' ), true ).get().accessToken() );
        assertArrayEquals( authFile( "mem" ), renewer.lastInput );
        assertNull( store.readAuthFile( uuid( 'a' ) ), "still never written" );
    }

    @Test
    void refreshingAnUnknownAccountFails()
    {
        assertThrows( ExecutionException.class, () -> manager( inline ).refresh( uuid( 'f' ), true ).get() );
    }

    @Test
    void aRemovedAccountStaysRemovedWhenItsRefreshLands()
    {
        AccountManager m = manager( manual );
        m.addSignedIn( user( 'a', "ta" ), authFile( "a" ), true );
        CompletableFuture< User > pending = m.refresh( uuid( 'a' ), true );
        m.remove( uuid( 'a' ) );
        runQueued();

        assertTrue( pending.isDone() );
        assertNull( m.account( uuid( 'a' ) ) );
        assertNull( store.readAuthFile( uuid( 'a' ) ), "the late refresh must not resurrect it on disk" );
    }

    // =========================================================================
    //  Migration from the old single-account files
    // =========================================================================

    private void writeLegacyFiles( User user, byte[] auth, long renewedAt ) throws Exception
    {
        Files.write( legacyFolder.resolve( "player.mica" ), FAKE_CIPHER.encrypt( auth ) );
        if ( user != null ) {
            String json = "{\"uuid\":\"" + user.uuid() + "\",\"name\":\"" + user.name()
                          + "\",\"accessToken\":\"" + user.accessToken() + "\",\"type\":\"msa\"}";
            Files.writeString( legacyFolder.resolve( "cached_user.json" ), java.util.Base64.getEncoder()
                    .encodeToString( FAKE_CIPHER.encrypt( json.getBytes( java.nio.charset.StandardCharsets.UTF_8 ) ) ) );
        }
        Files.writeString( legacyFolder.resolve( "renewal.timestamp" ), Long.toString( renewedAt ) );
    }

    private boolean legacyFilesRemain()
    {
        return Files.exists( legacyFolder.resolve( "player.mica" ) )
               || Files.exists( legacyFolder.resolve( "cached_user.json" ) )
               || Files.exists( legacyFolder.resolve( "renewal.timestamp" ) );
    }

    @Test
    void theOldActiveAccountMovesIntoTheStoreAsTheDefault() throws Exception
    {
        writeLegacyFiles( user( 'a', "ta" ), authFile( "a" ), T0 - 1000 );

        AccountManager m = manager( inline );
        assertEquals( uuid( 'a' ), m.defaultUuid() );
        assertEquals( "ta", m.defaultUser().accessToken() );
        assertEquals( T0 - 1000, m.renewedAtMs( uuid( 'a' ) ), "the renewal time carries over" );
        assertArrayEquals( authFile( "a" ), store.readAuthFile( uuid( 'a' ) ) );
        assertFalse( legacyFilesRemain() );
    }

    @Test
    void theOldActiveAccountOverwritesAStaleArchivedCopy() throws Exception
    {
        store.writeAuthFile( uuid( 'a' ), authFile( "stale" ) );
        store.writeMeta( uuid( 'a' ), "Playera", 1L );
        writeLegacyFiles( user( 'a', "ta" ), authFile( "current" ), T0 );

        AccountManager m = manager( inline );
        assertEquals( 1, m.accounts().size() );   // loading runs the migration
        assertArrayEquals( authFile( "current" ), store.readAuthFile( uuid( 'a' ) ) );
    }

    @Test
    void archivedAccountsSitAlongsideTheMigratedDefault() throws Exception
    {
        store.writeAuthFile( uuid( 'b' ), authFile( "b" ) );
        store.writeMeta( uuid( 'b' ), "Playerb", 1L );
        writeLegacyFiles( user( 'a', "ta" ), authFile( "a" ), T0 );

        AccountManager m = manager( inline );
        assertEquals( Set.of( uuid( 'a' ), uuid( 'b' ) ), Set.copyOf( uuids( m.accounts() ) ) );
        assertEquals( uuid( 'a' ), m.defaultUuid() );
    }

    @Test
    void migrationRunsOnce() throws Exception
    {
        writeLegacyFiles( user( 'a', "ta" ), authFile( "a" ), T0 );
        manager( inline ).accounts();
        defaults.value = uuid( 'a' );

        AccountManager again = manager( inline );
        assertEquals( 1, again.accounts().size() );
        assertEquals( uuid( 'a' ), again.defaultUuid() );
    }

    @Test
    void anUnidentifiedOldLoginIsIdentifiedByItsFirstRenewal() throws Exception
    {
        writeLegacyFiles( null, authFile( "a" ), T0 );   // credentials, but no readable cached user
        renewer.next = in -> new AccountManager.Renewal( user( 'a', "ta" ), authFile( "a2" ) );

        AccountManager m = manager( inline );
        assertTrue( m.hasDefault(), "the login screen is skipped" );
        assertNull( m.defaultUuid() );

        assertEquals( uuid( 'a' ), m.refreshDefault().get().uuid() );
        assertEquals( uuid( 'a' ), m.defaultUuid() );
        assertArrayEquals( authFile( "a2" ), store.readAuthFile( uuid( 'a' ) ) );
        assertFalse( legacyFilesRemain() );
    }

    @Test
    void anOldLoginFromAnotherMachineIsDropped() throws Exception
    {
        Files.write( legacyFolder.resolve( "player.mica" ), new byte[]{ 9, 9, 9 } );

        AccountManager m = new AccountManager( store, legacyFolder, OTHER_MACHINE, defaults, renewer,
                                               now::get, inline, INTERVAL );
        assertFalse( m.hasDefault() );
        assertFalse( Files.exists( legacyFolder.resolve( "player.mica" ) ) );
    }

    @Test
    void refreshDefaultWithNoAccountFailsAsLoginExpired()
    {
        ExecutionException e = assertThrows( ExecutionException.class,
                                              () -> manager( inline ).refreshDefault().get() );
        assertTrue( ( (AccountManager.RenewalFailedException) e.getCause() ).credentialsRejected() );
    }

    // =========================================================================
    //  Doubles
    // =========================================================================

    private static List< String > uuids( List< AccountManager.AccountInfo > accounts )
    {
        List< String > out = new ArrayList<>();
        accounts.forEach( a -> out.add( a.uuid() ) );
        return out;
    }

    private static final class InMemoryDefault implements AccountManager.DefaultAccountSetting
    {
        String value = "";

        @Override
        public String get()
        {
            return value;
        }

        @Override
        public void set( String uuid )
        {
            value = uuid;
        }
    }

    @FunctionalInterface
    private interface RenewStep
    {
        AccountManager.Renewal apply( byte[] in ) throws AccountManager.RenewalFailedException;
    }

    /** Renews by echoing the input's account unless a test scripts {@link #next}. */
    private final class ScriptedRenewer implements AccountManager.Renewer
    {
        final AtomicInteger calls = new AtomicInteger();
        RenewStep next;
        byte[]    lastInput;

        @Override
        public AccountManager.Renewal renew( byte[] gzippedAuthFile ) throws AccountManager.RenewalFailedException
        {
            calls.incrementAndGet();
            lastInput = gzippedAuthFile;
            if ( next != null ) {
                return next.apply( gzippedAuthFile );
            }
            // Default: the auth file "auth:<digit>" renews to that account with a new token.
            String label = new String( gzippedAuthFile, java.nio.charset.StandardCharsets.UTF_8 ).substring( 5 );
            return new AccountManager.Renewal( user( label.charAt( 0 ), "renewed" ), gzippedAuthFile );
        }
    }
}
