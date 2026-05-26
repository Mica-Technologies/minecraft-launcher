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

package com.micatechnologies.minecraft.launcher.game.modpack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ServerFavoritesStore}: JSON round-trip, missing-file
 * tolerance, malformed-file degradation, and the {@code disableDefaultServer}
 * flag.
 *
 * <p>Uses a {@link TestPack} that overrides {@link GameModPack#getPackRootFolder}
 * to point at a JUnit-provided temp directory — the store reads / writes
 * {@code <packRoot>/server-favorites.json} so nothing else from the pack is
 * exercised. Tests don't need the GSON-deserialized field state, only the
 * pack-root path, so the override is the minimum surface required.</p>
 */
class ServerFavoritesStoreTest
{
    /** Minimal {@link GameModPack} for tests — only the pack-root accessor is
     *  overridden because that's all {@link ServerFavoritesStore} reads. */
    private static final class TestPack extends GameModPack
    {
        private final String root;
        TestPack( Path root ) { this.root = root.toString(); }
        @Override public String getPackRootFolder() { return root; }
        @Override public String getPackName() { return "test-pack"; }
    }

    @Test
    void loadMissingFile_returnsEmptyList( @TempDir Path tempDir )
    {
        TestPack pack = new TestPack( tempDir );
        List< ServerFavorite > result = ServerFavoritesStore.load( pack );
        assertTrue( result.isEmpty() );
    }

    @Test
    void roundTrip_singleFavorite( @TempDir Path tempDir ) throws IOException
    {
        TestPack pack = new TestPack( tempDir );
        List< ServerFavorite > original = List.of(
                new ServerFavorite( "My Server", "play.example.com", 25565 )
        );
        ServerFavoritesStore.save( pack, original );

        List< ServerFavorite > loaded = ServerFavoritesStore.load( pack );
        assertEquals( 1, loaded.size() );
        assertEquals( "My Server", loaded.get( 0 ).name() );
        assertEquals( "play.example.com", loaded.get( 0 ).host() );
        assertEquals( 25565, loaded.get( 0 ).port() );
    }

    @Test
    void roundTrip_multipleFavorites( @TempDir Path tempDir ) throws IOException
    {
        TestPack pack = new TestPack( tempDir );
        List< ServerFavorite > original = List.of(
                new ServerFavorite( "Server A", "a.example.com", 25565 ),
                new ServerFavorite( "Server B", "b.example.com", 8585 ),
                new ServerFavorite( "Server C", "c.example.com", 19132 )
        );
        ServerFavoritesStore.save( pack, original );

        List< ServerFavorite > loaded = ServerFavoritesStore.load( pack );
        assertEquals( 3, loaded.size() );
        assertEquals( "a.example.com", loaded.get( 0 ).host() );
        assertEquals( 8585, loaded.get( 1 ).port() );
        assertEquals( "Server C", loaded.get( 2 ).name() );
    }

    @Test
    void saveEmptyList_writesEmptyFavoritesArray( @TempDir Path tempDir ) throws IOException
    {
        TestPack pack = new TestPack( tempDir );
        ServerFavoritesStore.save( pack, List.of() );
        // File should exist with empty favorites.
        Path file = tempDir.resolve( "server-favorites.json" );
        assertTrue( Files.exists( file ) );
        // load() returns empty list (not null, not throwing).
        assertTrue( ServerFavoritesStore.load( pack ).isEmpty() );
    }

    @Test
    void defaultServerDisabledFlag_defaultsFalse( @TempDir Path tempDir )
    {
        TestPack pack = new TestPack( tempDir );
        assertFalse( ServerFavoritesStore.isDefaultServerDisabled( pack ) );
    }

    @Test
    void defaultServerDisabledFlag_persists( @TempDir Path tempDir ) throws IOException
    {
        TestPack pack = new TestPack( tempDir );
        ServerFavoritesStore.setDefaultServerDisabled( pack, true );
        assertTrue( ServerFavoritesStore.isDefaultServerDisabled( pack ) );

        ServerFavoritesStore.setDefaultServerDisabled( pack, false );
        assertFalse( ServerFavoritesStore.isDefaultServerDisabled( pack ) );
    }

    @Test
    void defaultServerDisabledFlag_survivesFavoritesSave( @TempDir Path tempDir ) throws IOException
    {
        // Order of operations: set the flag, then save favorites. The
        // save() path uses writeAll under the hood which preserves the
        // existing disabled flag.
        TestPack pack = new TestPack( tempDir );
        ServerFavoritesStore.setDefaultServerDisabled( pack, true );

        ServerFavoritesStore.save( pack, List.of(
                new ServerFavorite( "Server", "host.example.com", 25565 ) ) );

        // Flag should still be true after the save.
        assertTrue( ServerFavoritesStore.isDefaultServerDisabled( pack ) );
        // Favorites also persisted.
        assertEquals( 1, ServerFavoritesStore.load( pack ).size() );
    }

    @Test
    void malformedJson_degradesToEmpty( @TempDir Path tempDir ) throws IOException
    {
        // Hostile / corrupted sidecar: shouldn't take down the modal.
        Path file = tempDir.resolve( "server-favorites.json" );
        Files.writeString( file, "{not valid json at all", StandardCharsets.UTF_8 );

        TestPack pack = new TestPack( tempDir );
        assertTrue( ServerFavoritesStore.load( pack ).isEmpty() );
        assertFalse( ServerFavoritesStore.isDefaultServerDisabled( pack ) );
    }

    @Test
    void entryMissingHost_isSkipped( @TempDir Path tempDir ) throws IOException
    {
        // Hand-written file with one malformed entry (no host) and one good
        // entry. The malformed entry should be silently skipped; the good
        // one should load.
        Path file = tempDir.resolve( "server-favorites.json" );
        Files.writeString( file,
                "{\"favorites\":[" +
                        "  {\"name\":\"bad\",\"port\":25565}," +
                        "  {\"name\":\"good\",\"host\":\"good.example.com\",\"port\":25565}" +
                        "]}", StandardCharsets.UTF_8 );

        TestPack pack = new TestPack( tempDir );
        List< ServerFavorite > loaded = ServerFavoritesStore.load( pack );
        assertEquals( 1, loaded.size() );
        assertEquals( "good.example.com", loaded.get( 0 ).host() );
    }

    @Test
    void portOutOfRange_clampsToDefault( @TempDir Path tempDir ) throws IOException
    {
        // The loader floors malformed port values to DEFAULT_PORT rather than
        // failing the whole entry — favorites are user-facing-edit-friendly,
        // not a security boundary.
        Path file = tempDir.resolve( "server-favorites.json" );
        Files.writeString( file,
                "{\"favorites\":[{\"name\":\"x\",\"host\":\"host.example.com\",\"port\":99999}]}",
                StandardCharsets.UTF_8 );

        TestPack pack = new TestPack( tempDir );
        List< ServerFavorite > loaded = ServerFavoritesStore.load( pack );
        assertEquals( 1, loaded.size() );
        assertEquals( ServerFavorite.DEFAULT_PORT, loaded.get( 0 ).port() );
    }
}
