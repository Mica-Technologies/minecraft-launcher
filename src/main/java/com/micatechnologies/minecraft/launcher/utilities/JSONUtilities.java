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
     * Converts the specified string to a JSON object.
     *
     * @param json JSON string
     *
     * @return JSON object
     *
     * @since 1.0
     */
    public static JsonObject stringToObject( String json ) {
        return new Gson().fromJson( json, JsonObject.class );
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
        return new Gson().toJson( object );
    }


}
