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

import com.google.gson.*;

/**
 * Class of utility methods for working with and manipulating JSON objects and other structures.
 *
 * @author Mica Technologies
 * @version 1.0
 * @since 2.0
 */
public class JSONUtilities
{
    /**
     * Shared Gson instance. Gson is thread-safe and expensive to instantiate.
     *
     * <p>Registers the {@link StringOrArray} adapter so any {@code string | string[]} union field
     * (e.g. modpack logo / background URLs) deserializes from either shape and re-serializes
     * minimally. Plain {@link Gson} behaviour is otherwise unchanged.</p>
     */
    private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter( StringOrArray.class, new StringOrArray.Adapter() )
            .create();

    /**
     * Shared pretty-printing Gson instance. Same configuration as {@link #GSON}
     * (incl. the {@link StringOrArray} adapter) but with indentation enabled,
     * for human-readable output (exported manifests, hosting-manifest files).
     */
    private static final Gson PRETTY_GSON = new GsonBuilder()
            .registerTypeAdapter( StringOrArray.class, new StringOrArray.Adapter() )
            .setPrettyPrinting()
            .create();

    /**
     * Returns the shared {@link Gson} instance. Gson is thread-safe, so a single instance can be used across the
     * entire application without synchronization.
     *
     * @return the shared Gson instance
     *
     * @since 2.0
     */
    public static Gson getGson()
    {
        return GSON;
    }

    /**
     * Returns the shared pretty-printing {@link Gson} instance. Thread-safe and
     * cached; use instead of constructing a {@code new GsonBuilder().setPrettyPrinting()}
     * per call.
     *
     * @return the shared pretty-printing Gson instance
     *
     * @since 2026.6
     */
    public static Gson getPrettyGson()
    {
        return PRETTY_GSON;
    }

    /**
     * Converts the specified string to a JSON object.
     *
     * @param json JSON string
     *
     * @return JSON object
     *
     * @since 1.0
     */
    public static JsonObject stringToObject( String json ) {
        return GSON.fromJson( json, JsonObject.class );
    }

    /**
     * Converts the specified string to a JSON array.
     *
     * @param json JSON string
     *
     * @return JSON array
     *
     * @since 1.0
     */
    public static JsonArray stringToArray( String json ) {
        return GSON.fromJson( json, JsonArray.class );
    }

    /**
     * Converts the specified JSON object to a string.
     *
     * @param object JSON object
     *
     * @return JSON string
     *
     * @since 1.0
     */
    public static String objectToString( JsonObject object ) {
        return GSON.toJson( object );
    }

    /**
     * Converts the specified JSON array to a string.
     *
     * @param array JSON array
     *
     * @return JSON string
     *
     * @since 1.0
     */
    public static String arrayToString( JsonArray array ) {
        return GSON.toJson( array );
    }
}
