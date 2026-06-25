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
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * A small JSON "OR" datatype — the Java equivalent of TypeScript's {@code string | string[]}. A
 * field declared as a {@code StringOrArray} accepts <b>either</b> a single JSON string <b>or</b> a
 * JSON array of strings, normalizing both into an ordered, de-blanked list. Used for modpack
 * manifest fields (logo / background URLs) where an author may supply a single URL or a list of
 * mirror / fallback URLs, but it is deliberately generic and reusable for any such field.
 *
 * <p>Backward compatibility is the whole point: existing manifests + cache files written as a bare
 * string keep deserializing unchanged (a string becomes a one-element list). On the way back out,
 * {@link Adapter#serialize} re-emits a single value as a bare string and only uses an array when
 * there are two or more, so round-tripped JSON stays minimal.</p>
 *
 * <p>Register {@link Adapter} on the shared Gson (see {@link JSONUtilities}); Gson then handles the
 * union shape anywhere a {@code StringOrArray} field appears.</p>
 *
 * @author Mica Technologies
 * @since 3.6
 */
public final class StringOrArray
{
    /** Ordered, unmodifiable, trimmed + blank-free values. Never {@code null}; may be empty. */
    private final List< String > values;

    private StringOrArray( List< String > normalizedValues )
    {
        this.values = Collections.unmodifiableList( normalizedValues );
    }

    /** Builds a {@code StringOrArray} from individual values (null / blank entries dropped). */
    public static StringOrArray of( String... values )
    {
        return of( values == null ? null : Arrays.asList( values ) );
    }

    /** Builds a {@code StringOrArray} from a list (null / blank entries dropped, order preserved). */
    public static StringOrArray of( List< String > values )
    {
        List< String > normalized = new ArrayList<>();
        if ( values != null ) {
            for ( String v : values ) {
                if ( v != null ) {
                    String trimmed = v.trim();
                    if ( !trimmed.isEmpty() ) {
                        normalized.add( trimmed );
                    }
                }
            }
        }
        return new StringOrArray( normalized );
    }

    /** @return the ordered values (never {@code null}; possibly empty). */
    public List< String > all()
    {
        return values;
    }

    /** @return the first value (the "primary"), or {@code null} if empty. */
    public String first()
    {
        return values.isEmpty() ? null : values.get( 0 );
    }

    /** @return {@code true} if there are no values. */
    public boolean isEmpty()
    {
        return values.isEmpty();
    }

    /**
     * Value equality: two instances are equal when their backing value lists are
     * equal (same strings in the same order).
     *
     * @param o the object to compare against
     *
     * @return {@code true} if {@code o} is a {@code StringOrArray} with equal values
     *
     * @since 2026.6
     */
    @Override
    public boolean equals( Object o )
    {
        if ( this == o ) {
            return true;
        }
        if ( !( o instanceof StringOrArray other ) ) {
            return false;
        }
        return values.equals( other.values );
    }

    /**
     * Hash code consistent with {@link #equals(Object)}, derived from the backing
     * value list.
     *
     * @return the hash code of the backing value list
     *
     * @since 2026.6
     */
    @Override
    public int hashCode()
    {
        return values.hashCode();
    }

    /**
     * Returns the backing value list's string form, e.g. {@code [a, b]}.
     *
     * @return a debug string of the contained values
     *
     * @since 2026.6
     */
    @Override
    public String toString()
    {
        return values.toString();
    }

    /**
     * Gson (de)serializer for {@link StringOrArray}. Deserializes a JSON string into a one-element
     * list and a JSON array into a multi-element list; serializes a single value back to a bare
     * string and two-or-more to an array (empty → JSON null).
     */
    public static final class Adapter
            implements JsonDeserializer< StringOrArray >, JsonSerializer< StringOrArray >
    {
        @Override
        public StringOrArray deserialize( JsonElement json, Type typeOfT, JsonDeserializationContext ctx )
        {
            if ( json == null || json.isJsonNull() ) {
                return StringOrArray.of();
            }
            if ( json.isJsonArray() ) {
                List< String > list = new ArrayList<>();
                for ( JsonElement el : json.getAsJsonArray() ) {
                    if ( el != null && !el.isJsonNull() ) {
                        list.add( el.getAsString() );
                    }
                }
                return StringOrArray.of( list );
            }
            // Single primitive (a string — or a number/bool coerced to its string form).
            return StringOrArray.of( json.getAsString() );
        }

        @Override
        public JsonElement serialize( StringOrArray src, Type typeOfSrc, JsonSerializationContext ctx )
        {
            if ( src == null || src.isEmpty() ) {
                return JsonNull.INSTANCE;
            }
            List< String > vals = src.all();
            if ( vals.size() == 1 ) {
                return new JsonPrimitive( vals.get( 0 ) );
            }
            JsonArray arr = new JsonArray();
            for ( String v : vals ) {
                arr.add( v );
            }
            return arr;
        }
    }
}
