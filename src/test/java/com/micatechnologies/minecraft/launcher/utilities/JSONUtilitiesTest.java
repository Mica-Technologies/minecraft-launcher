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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link JSONUtilities} — the single shared {@link com.google.gson.Gson}
 * configuration every modpack manifest, config file, and hosted-manifest
 * export in the launcher parses and writes through. Every downloaded modpack
 * definition is untrusted JSON; this class pins that the shared instances are
 * actually singletons (not rebuilt per call, which would silently drop the
 * registered {@link StringOrArray} adapter if a future edit constructed a
 * plain {@code new Gson()} instead), that round-tripping through
 * object/array and back to string is lossless, and that the pretty-printing variant
 * really does differ from the compact one (otherwise the "human-readable
 * export" feature would silently regress to unreadable single-line JSON).
 *
 * @since 3.0
 */
class JSONUtilitiesTest
{
    @Test
    void getGson_returnsSameSharedInstanceAcrossCalls()
    {
        assertSame( JSONUtilities.getGson(), JSONUtilities.getGson() );
    }

    @Test
    void getPrettyGson_returnsSameSharedInstanceAcrossCalls()
    {
        assertSame( JSONUtilities.getPrettyGson(), JSONUtilities.getPrettyGson() );
    }

    @Test
    void stringToObject_parsesJsonObject()
    {
        JsonObject obj = JSONUtilities.stringToObject( "{\"name\":\"Test Pack\",\"version\":2}" );
        assertNotNull( obj );
        assertEquals( "Test Pack", obj.get( "name" ).getAsString() );
        assertEquals( 2, obj.get( "version" ).getAsInt() );
    }

    @Test
    void stringToArray_parsesJsonArray()
    {
        JsonArray arr = JSONUtilities.stringToArray( "[\"a\",\"b\",\"c\"]" );
        assertNotNull( arr );
        assertEquals( 3, arr.size() );
        assertEquals( "b", arr.get( 1 ).getAsString() );
    }

    @Test
    void objectToStringAndBackRoundTripsExactly()
    {
        JsonObject original = new JsonObject();
        original.addProperty( "packName", "Example" );
        original.addProperty( "minRam", 4096 );

        String serialized = JSONUtilities.objectToString( original );
        JsonObject reparsed = JSONUtilities.stringToObject( serialized );

        assertEquals( original, reparsed );
    }

    @Test
    void arrayToStringAndBackRoundTripsExactly()
    {
        JsonArray original = new JsonArray();
        original.add( "one" );
        original.add( "two" );

        String serialized = JSONUtilities.arrayToString( original );
        JsonArray reparsed = JSONUtilities.stringToArray( serialized );

        assertEquals( original, reparsed );
    }

    @Test
    void prettyGsonProducesMultiLineOutputUnlikeCompactGson()
    {
        JsonObject obj = new JsonObject();
        obj.addProperty( "a", 1 );
        obj.addProperty( "b", 2 );

        String compact = JSONUtilities.getGson().toJson( obj );
        String pretty = JSONUtilities.getPrettyGson().toJson( obj );

        assertTrue( compact.lines().count() == 1, "compact Gson should emit a single line" );
        assertTrue( pretty.lines().count() > 1, "pretty Gson should emit multiple lines" );
    }

    @Test
    void bothSharedInstancesParseEquivalentJsonIdentically()
    {
        String json = "{\"packLogoURL\":\"https://a/logo.png\"}";
        JsonObject viaCompact = JSONUtilities.getGson().fromJson( json, JsonObject.class );
        JsonObject viaPretty = JSONUtilities.getPrettyGson().fromJson( json, JsonObject.class );
        assertEquals( viaCompact, viaPretty );
    }
}
