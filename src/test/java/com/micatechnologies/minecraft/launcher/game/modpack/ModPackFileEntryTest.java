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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ModPackFileEntry} — the headless manifest file-list entry.
 *
 * <p>Two behaviours here are load-bearing rather than incidental. Every string field
 * coalesces {@code null} to {@code ""}, which is what lets {@link ModPackDocument}'s writer
 * call {@code getHash().isEmpty()} without a null check on entries built by callers who
 * skipped a field. And the extra-hash map normalizes its keys to lower case, so an entry
 * stashed as {@code "SHA1"} is still found by the writer's lower-case lookup — get that
 * wrong and the hash is silently dropped on save.</p>
 */
class ModPackFileEntryTest
{
    @Test
    void aNewEntryDefaultsToSha1AndRequiredOnBothSides()
    {
        ModPackFileEntry entry = new ModPackFileEntry();
        assertEquals( "sha1", entry.getHashType() );
        assertTrue( entry.isClientReq() );
        assertTrue( entry.isServerReq() );
        assertEquals( "", entry.getName() );
        assertEquals( "", entry.getHash() );
    }

    @Test
    void theConstructorCoalescesNullStringsToEmpty()
    {
        ModPackFileEntry entry = new ModPackFileEntry( null, null, null, null, null, false, false );
        assertEquals( "", entry.getName() );
        assertEquals( "", entry.getRemote() );
        assertEquals( "", entry.getLocal() );
        assertEquals( "", entry.getHash() );
        assertEquals( "sha1", entry.getHashType(), "a null hash type falls back to the default" );
    }

    @Test
    void settersCoalesceNullStringsToEmpty()
    {
        ModPackFileEntry entry = new ModPackFileEntry();
        entry.setName( null );
        entry.setRemote( null );
        entry.setLocal( null );
        entry.setHash( null );
        entry.setModrinthSlug( null );
        assertEquals( "", entry.getName() );
        assertEquals( "", entry.getRemote() );
        assertEquals( "", entry.getLocal() );
        assertEquals( "", entry.getHash() );
        assertEquals( "", entry.getModrinthSlug() );
    }

    @Test
    void settingANullHashTypeRestoresTheDefault()
    {
        ModPackFileEntry entry = new ModPackFileEntry();
        entry.setHashType( "sha256" );
        entry.setHashType( null );
        assertEquals( "sha1", entry.getHashType() );
    }

    @Test
    void extraHashLookupIsCaseInsensitive()
    {
        ModPackFileEntry entry = new ModPackFileEntry();
        entry.putExtraHash( "SHA256", "abc" );
        assertEquals( "abc", entry.getExtraHash( "sha256" ) );
        assertEquals( "abc", entry.getExtraHash( "Sha256" ) );
    }

    @Test
    void storingABlankOrNullValueClearsTheSlot()
    {
        ModPackFileEntry entry = new ModPackFileEntry();
        entry.putExtraHash( "sha1", "abc" );
        entry.putExtraHash( "sha1", "   " );
        assertNull( entry.getExtraHash( "sha1" ) );

        entry.putExtraHash( "md5", "def" );
        entry.putExtraHash( "md5", null );
        assertNull( entry.getExtraHash( "md5" ) );
    }

    @Test
    void aNullAlgorithmIsIgnoredRatherThanThrowing()
    {
        ModPackFileEntry entry = new ModPackFileEntry();
        entry.putExtraHash( null, "abc" );
        assertNull( entry.getExtraHash( null ) );
        assertTrue( entry.getExtraHashes().isEmpty() );
    }

    @Test
    void theExtraHashViewIsUnmodifiable()
    {
        ModPackFileEntry entry = new ModPackFileEntry();
        entry.putExtraHash( "sha1", "abc" );
        assertThrows( UnsupportedOperationException.class, () -> entry.getExtraHashes().put( "md5", "x" ) );
    }

    @Test
    void theExtraHashViewReflectsLaterWrites()
    {
        ModPackFileEntry entry = new ModPackFileEntry();
        var view = entry.getExtraHashes();
        assertTrue( view.isEmpty() );
        entry.putExtraHash( "sha1", "abc" );
        assertEquals( 1, view.size() );
        assertFalse( view.isEmpty() );
    }
}
