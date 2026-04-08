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

package com.micatechnologies.minecraft.launcher.utilities;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Utility class providing null-safe accessor methods for GSON {@link JsonObject} instances. Methods in this class
 * eliminate the common pattern of {@code obj.get(key).getAsString()} which throws {@link NullPointerException} when the
 * key is missing or the value is {@code null}.
 *
 * @author Mica Technologies
 * @version 1.0
 * @since 2.0
 */
public class JsonHelper
{
    /**
     * Returns the string value for the specified key, or the default value if the key is missing or null.
     *
     * @param obj          the JSON object to read from
     * @param key          the key to look up
     * @param defaultValue the value to return if the key is absent or null
     *
     * @return the string value, or {@code defaultValue}
     *
     * @since 1.0
     */
    public static String getString( JsonObject obj, String key, String defaultValue )
    {
        if ( obj != null && obj.has( key ) && !obj.get( key ).isJsonNull() )
        {
            return obj.get( key ).getAsString();
        }
        return defaultValue;
    }

    /**
     * Returns the string value for the specified key, or throws an exception if the key is missing or null.
     *
     * @param obj the JSON object to read from
     * @param key the key to look up
     *
     * @return the string value
     *
     * @throws IllegalArgumentException if the key is missing or the value is null
     * @since 1.0
     */
    public static String getRequiredString( JsonObject obj, String key )
    {
        if ( obj == null || !obj.has( key ) || obj.get( key ).isJsonNull() )
        {
            throw new IllegalArgumentException( "Required JSON key '" + key + "' is missing or null" );
        }
        return obj.get( key ).getAsString();
    }

    /**
     * Returns the integer value for the specified key, or the default value if the key is missing or null.
     *
     * @param obj          the JSON object to read from
     * @param key          the key to look up
     * @param defaultValue the value to return if the key is absent or null
     *
     * @return the integer value, or {@code defaultValue}
     *
     * @since 1.0
     */
    public static int getInt( JsonObject obj, String key, int defaultValue )
    {
        if ( obj != null && obj.has( key ) && !obj.get( key ).isJsonNull() )
        {
            return obj.get( key ).getAsInt();
        }
        return defaultValue;
    }

    /**
     * Returns the long value for the specified key, or the default value if the key is missing or null.
     *
     * @param obj          the JSON object to read from
     * @param key          the key to look up
     * @param defaultValue the value to return if the key is absent or null
     *
     * @return the long value, or {@code defaultValue}
     *
     * @since 1.0
     */
    public static long getLong( JsonObject obj, String key, long defaultValue )
    {
        if ( obj != null && obj.has( key ) && !obj.get( key ).isJsonNull() )
        {
            return obj.get( key ).getAsLong();
        }
        return defaultValue;
    }

    /**
     * Returns the boolean value for the specified key, or the default value if the key is missing or null.
     *
     * @param obj          the JSON object to read from
     * @param key          the key to look up
     * @param defaultValue the value to return if the key is absent or null
     *
     * @return the boolean value, or {@code defaultValue}
     *
     * @since 1.0
     */
    public static boolean getBoolean( JsonObject obj, String key, boolean defaultValue )
    {
        if ( obj != null && obj.has( key ) && !obj.get( key ).isJsonNull() )
        {
            return obj.get( key ).getAsBoolean();
        }
        return defaultValue;
    }

    /**
     * Returns the nested {@link JsonObject} for the specified key, or {@code null} if the key is missing, null, or not
     * an object.
     *
     * @param obj the JSON object to read from
     * @param key the key to look up
     *
     * @return the nested JSON object, or {@code null}
     *
     * @since 1.0
     */
    public static JsonObject getJsonObject( JsonObject obj, String key )
    {
        if ( obj != null && obj.has( key ) )
        {
            JsonElement element = obj.get( key );
            if ( element.isJsonObject() )
            {
                return element.getAsJsonObject();
            }
        }
        return null;
    }

    /**
     * Returns the nested {@link JsonObject} for the specified key, or throws an exception if the key is missing or not
     * an object.
     *
     * @param obj the JSON object to read from
     * @param key the key to look up
     *
     * @return the nested JSON object
     *
     * @throws IllegalArgumentException if the key is missing or the value is not a JSON object
     * @since 1.0
     */
    public static JsonObject getRequiredJsonObject( JsonObject obj, String key )
    {
        if ( obj == null || !obj.has( key ) || !obj.get( key ).isJsonObject() )
        {
            throw new IllegalArgumentException(
                    "Required JSON key '" + key + "' is missing or not a JSON object" );
        }
        return obj.get( key ).getAsJsonObject();
    }

    /**
     * Returns the {@link JsonArray} for the specified key, or {@code null} if the key is missing, null, or not an
     * array.
     *
     * @param obj the JSON object to read from
     * @param key the key to look up
     *
     * @return the JSON array, or {@code null}
     *
     * @since 1.0
     */
    public static JsonArray getJsonArray( JsonObject obj, String key )
    {
        if ( obj != null && obj.has( key ) )
        {
            JsonElement element = obj.get( key );
            if ( element.isJsonArray() )
            {
                return element.getAsJsonArray();
            }
        }
        return null;
    }

    /**
     * Returns the {@link JsonArray} for the specified key, or throws an exception if the key is missing or not an
     * array.
     *
     * @param obj the JSON object to read from
     * @param key the key to look up
     *
     * @return the JSON array
     *
     * @throws IllegalArgumentException if the key is missing or the value is not a JSON array
     * @since 1.0
     */
    public static JsonArray getRequiredJsonArray( JsonObject obj, String key )
    {
        if ( obj == null || !obj.has( key ) || !obj.get( key ).isJsonArray() )
        {
            throw new IllegalArgumentException(
                    "Required JSON key '" + key + "' is missing or not a JSON array" );
        }
        return obj.get( key ).getAsJsonArray();
    }

    /**
     * Returns the double value for the specified key, or the default value if the key is missing or null.
     *
     * @param obj          the JSON object to read from
     * @param key          the key to look up
     * @param defaultValue the value to return if the key is absent or null
     *
     * @return the double value, or {@code defaultValue}
     *
     * @since 1.0
     */
    public static double getDouble( JsonObject obj, String key, double defaultValue )
    {
        if ( obj != null && obj.has( key ) && !obj.get( key ).isJsonNull() )
        {
            return obj.get( key ).getAsDouble();
        }
        return defaultValue;
    }
}
