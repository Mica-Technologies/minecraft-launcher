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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.micatechnologies.minecraft.launcher.game.auth.AccountTestSupport.FAKE_CIPHER;
import static com.micatechnologies.minecraft.launcher.game.auth.AccountTestSupport.OTHER_MACHINE;
import static com.micatechnologies.minecraft.launcher.game.auth.AccountTestSupport.authFile;
import static com.micatechnologies.minecraft.launcher.game.auth.AccountTestSupport.user;
import static com.micatechnologies.minecraft.launcher.game.auth.AccountTestSupport.uuid;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link AccountStore}, the per-account credential folders.
 *
 * <p>These run in-process against a temp directory with a fake cipher, so they never derive
 * the real machine key or touch the user's config folder. What matters here: every file
 * round-trips, folders written by the old single-account {@code ProfileArchive} still read,
 * a uuid can't steer a write outside the store, and credentials from another machine are
 * refused rather than half-read.</p>
 */
class AccountStoreTest
{
    @TempDir
    Path tempDir;

    private Path         root;
    private AccountStore store;

    @BeforeEach
    void setUp()
    {
        root = tempDir.resolve( AccountStore.PROFILES_DIR );
        store = new AccountStore( root, FAKE_CIPHER );
    }

    // =========================================================================
    //  Round trips
    // =========================================================================

    @Test
    void everyFileRoundTrips() throws IOException
    {
        User steve = user( 'a', "token-a" );
        store.writeAuthFile( steve.uuid(), authFile( "a" ) );
        store.writeCachedUser( steve );
        store.writeRenewalMs( steve.uuid(), 1_700_000_000_000L );
        store.writeMeta( steve.uuid(), steve.name(), 42L );

        assertArrayEquals( authFile( "a" ), store.readAuthFile( steve.uuid() ) );
        assertEquals( steve, store.readCachedUser( steve.uuid() ) );
        assertEquals( 1_700_000_000_000L, store.readRenewalMs( steve.uuid() ) );
        assertEquals( List.of( new AccountStore.Entry( steve.uuid(), steve.name(), 42L ) ), store.list() );
    }

    @Test
    void credentialsAreEncryptedOnDisk() throws IOException
    {
        User steve = user( 'a', "secret-token" );
        store.writeAuthFile( steve.uuid(), authFile( "a" ) );
        store.writeCachedUser( steve );

        byte[] rawAuth = Files.readAllBytes( root.resolve( steve.uuid() ).resolve( AccountStore.AUTH_FILE ) );
        String rawUser = Files.readString( root.resolve( steve.uuid() ).resolve( AccountStore.CACHED_USER_FILE ) );
        assertFalse( java.util.Arrays.equals( authFile( "a" ), rawAuth ), "auth file must go through the cipher" );
        assertFalse( rawUser.contains( "secret-token" ), "cached user must not hold the token in the clear" );
    }

    @Test
    void writesLeaveNoTempFilesBehind() throws IOException
    {
        User steve = user( 'a', "t" );
        store.writeAuthFile( steve.uuid(), authFile( "a" ) );
        store.writeCachedUser( steve );
        store.writeRenewalMs( steve.uuid(), 1L );
        store.writeMeta( steve.uuid(), steve.name(), 1L );
        try ( var files = Files.list( root.resolve( steve.uuid() ) ) ) {
            assertTrue( files.noneMatch( p -> p.getFileName().toString().endsWith( ".tmp" ) ) );
        }
    }

    @Test
    void listIsMostRecentlyUsedFirst() throws IOException
    {
        for ( char d : new char[]{ 'a', 'b', 'c' } ) {
            store.writeAuthFile( uuid( d ), authFile( "" + d ) );
        }
        store.writeMeta( uuid( 'a' ), "A", 10L );
        store.writeMeta( uuid( 'b' ), "B", 30L );
        store.writeMeta( uuid( 'c' ), "C", 20L );

        assertEquals( List.of( uuid( 'b' ), uuid( 'c' ), uuid( 'a' ) ),
                      store.list().stream().map( AccountStore.Entry::uuid ).toList() );
    }

    // =========================================================================
    //  Compatibility with what earlier versions wrote
    // =========================================================================

    @Test
    void readsAFolderInTheOldProfileArchiveLayout() throws Exception
    {
        // ProfileArchive wrote exactly these names and this metadata shape.
        Path folder = Files.createDirectories( root.resolve( uuid( 'a' ) ) );
        Files.write( folder.resolve( "player.mica" ), FAKE_CIPHER.encrypt( authFile( "a" ) ) );
        Files.writeString( folder.resolve( "profile.json" ),
                           "{\"uuid\":\"" + uuid( 'a' ) + "\",\"displayName\":\"Steve\",\"lastUsedMs\":7}" );

        assertEquals( List.of( new AccountStore.Entry( uuid( 'a' ), "Steve", 7L ) ), store.list() );
        assertArrayEquals( authFile( "a" ), store.readAuthFile( uuid( 'a' ) ) );
    }

    @Test
    void acceptsALegacyUnencryptedGzipAuthFile() throws IOException
    {
        byte[] gzip = { (byte) 0x1F, (byte) 0x8B, 8, 0, 1, 2, 3 };
        Path folder = Files.createDirectories( root.resolve( uuid( 'a' ) ) );
        Files.write( folder.resolve( AccountStore.AUTH_FILE ), gzip );

        assertArrayEquals( gzip, store.readAuthFile( uuid( 'a' ) ) );
    }

    @Test
    void acceptsALegacyPlainDecimalRenewalTimestamp()
    {
        assertEquals( 1_735_689_600_000L, AccountStore.parseRenewalTimestamp( "1735689600000", FAKE_CIPHER ) );
        assertEquals( 0L, AccountStore.parseRenewalTimestamp( "not a number", FAKE_CIPHER ) );
        assertEquals( 0L, AccountStore.parseRenewalTimestamp( "  ", FAKE_CIPHER ) );
    }

    @Test
    void metadataIsOptionalForListing() throws IOException
    {
        store.writeAuthFile( uuid( 'a' ), authFile( "a" ) );
        assertEquals( List.of( new AccountStore.Entry( uuid( 'a' ), "", 0L ) ), store.list() );
    }

    @Test
    void corruptMetadataKeepsTheAccount() throws IOException
    {
        store.writeAuthFile( uuid( 'a' ), authFile( "a" ) );
        Files.writeString( root.resolve( uuid( 'a' ) ).resolve( AccountStore.META_FILE ), "{not json" );
        assertEquals( List.of( uuid( 'a' ) ), store.list().stream().map( AccountStore.Entry::uuid ).toList() );
    }

    // =========================================================================
    //  Refusals
    // =========================================================================

    @Test
    void listSkipsFoldersWithoutCredentialsAndUnsafeNames() throws IOException
    {
        store.writeAuthFile( uuid( 'a' ), authFile( "a" ) );
        Files.createDirectories( root.resolve( uuid( 'b' ) ) );                       // no player.mica
        Path odd = Files.createDirectories( root.resolve( "not-a-uuid!" ) );
        Files.write( odd.resolve( AccountStore.AUTH_FILE ), new byte[]{ 1 } );

        assertEquals( List.of( uuid( 'a' ) ), store.list().stream().map( AccountStore.Entry::uuid ).toList() );
    }

    @Test
    void unsafeUuidsCannotEscapeTheStore()
    {
        for ( String bad : new String[]{ "..", "../x", "a/b", "a\\b", "", "zz", null } ) {
            assertFalse( AccountStore.isSafeUuid( bad ), "should refuse " + bad );
            assertThrows( IOException.class, () -> store.writeAuthFile( bad, authFile( "x" ) ) );
            assertNull( store.readAuthFile( bad ) );
            assertFalse( store.remove( bad ) );
        }
        assertFalse( Files.exists( tempDir.resolve( "x" ) ) );
    }

    @Test
    void credentialsFromAnotherMachineAreRefused() throws IOException
    {
        store.writeAuthFile( uuid( 'a' ), authFile( "a" ) );
        store.writeCachedUser( user( 'a', "t" ) );
        store.writeRenewalMs( uuid( 'a' ), 5L );

        AccountStore elsewhere = new AccountStore( root, OTHER_MACHINE );
        assertNull( elsewhere.readAuthFile( uuid( 'a' ) ) );
        assertNull( elsewhere.readCachedUser( uuid( 'a' ) ) );
        assertEquals( 0L, elsewhere.readRenewalMs( uuid( 'a' ) ) );
    }

    @Test
    void aCachedUserWithoutANameIsTreatedAsMissing() throws Exception
    {
        Path folder = Files.createDirectories( root.resolve( uuid( 'a' ) ) );
        byte[] json = ( "{\"uuid\":\"" + uuid( 'a' ) + "\"}" ).getBytes( StandardCharsets.UTF_8 );
        Files.writeString( folder.resolve( AccountStore.CACHED_USER_FILE ),
                           java.util.Base64.getEncoder().encodeToString( FAKE_CIPHER.encrypt( json ) ) );
        assertNull( store.readCachedUser( uuid( 'a' ) ) );
    }

    @Test
    void missingFilesReadAsAbsent()
    {
        assertNull( store.readAuthFile( uuid( 'a' ) ) );
        assertNull( store.readCachedUser( uuid( 'a' ) ) );
        assertEquals( 0L, store.readRenewalMs( uuid( 'a' ) ) );
        assertTrue( store.list().isEmpty() );
    }

    // =========================================================================
    //  Removal
    // =========================================================================

    @Test
    void removeDeletesTheWholeFolder() throws IOException
    {
        store.writeAuthFile( uuid( 'a' ), authFile( "a" ) );
        store.writeCachedUser( user( 'a', "t" ) );
        Files.createDirectories( root.resolve( uuid( 'a' ) ).resolve( "nested" ) );

        assertTrue( store.remove( uuid( 'a' ) ) );
        assertFalse( Files.exists( root.resolve( uuid( 'a' ) ) ) );
        assertTrue( store.remove( uuid( 'a' ) ), "removing an absent account is a success" );
    }
}
