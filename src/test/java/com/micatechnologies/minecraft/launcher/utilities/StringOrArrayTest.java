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

package com.micatechnologies.minecraft.launcher.utilities;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the {@code string | string[]} union type {@link StringOrArray} and its Gson adapter — the
 * exact shape modpack manifests use for {@code packLogoURL} / {@code packBackgroundURL}. Exercises
 * deserialization of both JSON shapes (back-compat for the bare-string form), minimal
 * re-serialization, and the normalization / accessor contract.
 */
class StringOrArrayTest
{
    /** The shared Gson with {@link StringOrArray.Adapter} registered (same instance the app uses). */
    private final Gson gson = JSONUtilities.getGson();

    /** A holder mirroring how the field appears on a manifest model. */
    private static final class Holder
    {
        StringOrArray packLogoURL;
    }

    @Test
    void deserializesBareStringToSingletonList()
    {
        Holder h = gson.fromJson( "{\"packLogoURL\":\"https://a/logo.png\"}", Holder.class );
        assertEquals( List.of( "https://a/logo.png" ), h.packLogoURL.all() );
        assertEquals( "https://a/logo.png", h.packLogoURL.first() );
        assertFalse( h.packLogoURL.isEmpty() );
    }

    @Test
    void deserializesArrayPreservingOrder()
    {
        Holder h = gson.fromJson(
                "{\"packLogoURL\":[\"https://a/logo.png\",\"https://b/logo.png\"]}", Holder.class );
        assertEquals( List.of( "https://a/logo.png", "https://b/logo.png" ), h.packLogoURL.all() );
        assertEquals( "https://a/logo.png", h.packLogoURL.first() );
    }

    @Test
    void absentFieldLeavesNullAndEmptyStringIsEmpty()
    {
        Holder absent = gson.fromJson( "{}", Holder.class );
        assertNull( absent.packLogoURL );

        Holder blank = gson.fromJson( "{\"packLogoURL\":\"\"}", Holder.class );
        assertTrue( blank.packLogoURL.isEmpty() );
        assertNull( blank.packLogoURL.first() );
    }

    @Test
    void normalizationTrimsAndDropsBlanks()
    {
        StringOrArray v = StringOrArray.of( "  https://a/logo.png  ", "", "   ", null, "https://b/x" );
        assertEquals( List.of( "https://a/logo.png", "https://b/x" ), v.all() );
    }

    @Test
    void serializesSingleAsBareStringAndManyAsArray()
    {
        Holder single = new Holder();
        single.packLogoURL = StringOrArray.of( "https://a/logo.png" );
        assertEquals( "{\"packLogoURL\":\"https://a/logo.png\"}", gson.toJson( single ) );

        Holder many = new Holder();
        many.packLogoURL = StringOrArray.of( "https://a/logo.png", "https://b/logo.png" );
        assertEquals(
                "{\"packLogoURL\":[\"https://a/logo.png\",\"https://b/logo.png\"]}",
                gson.toJson( many ) );
    }

    @Test
    void roundTripsArrayThroughJson()
    {
        StringOrArray original = StringOrArray.of( "https://a", "https://b", "https://c" );
        Holder h = new Holder();
        h.packLogoURL = original;
        Holder back = gson.fromJson( gson.toJson( h ), Holder.class );
        assertEquals( original, back.packLogoURL );
    }

    @Test
    void valueEqualityIgnoresStringVsSingletonArrayOrigin()
    {
        StringOrArray fromString = gson.fromJson( "\"https://a\"", StringOrArray.class );
        StringOrArray fromArray = gson.fromJson( "[\"https://a\"]", StringOrArray.class );
        assertEquals( fromString, fromArray );
        assertEquals( fromString.hashCode(), fromArray.hashCode() );
    }
}
