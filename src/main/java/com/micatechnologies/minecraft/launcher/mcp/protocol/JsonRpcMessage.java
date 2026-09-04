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

package com.micatechnologies.minecraft.launcher.mcp.protocol;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * One well-formed inbound JSON-RPC 2.0 message — either a request (has an id, expects a
 * response) or a notification (no id, must not be answered).
 * <p>
 * Instances only ever come from {@link JsonRpcCodec#parse}, which is what guarantees the
 * invariants the rest of the server relies on: {@link #method()} is non-blank, and
 * {@link #id()} is either {@code null} or a JSON string or number — never an object, array,
 * or JSON null.
 *
 * @param id     the request id, or {@code null} when this is a notification
 * @param method the method name; never {@code null} or blank
 * @param params the raw params value, or {@code null} when the message carried none
 *
 * @author Mica Technologies
 * @version 1.0
 * @since 3.0
 */
public record JsonRpcMessage( JsonElement id, String method, JsonElement params )
{
    /**
     * Reports whether this message is a notification, meaning the caller expects no response.
     * <p>
     * Answering a notification is a protocol violation, so every dispatch path must check this
     * before writing anything back.
     *
     * @return {@code true} when the message carried no id
     *
     * @since 3.0
     */
    public boolean isNotification()
    {
        return id == null;
    }

    /**
     * Returns the params as an object, for the majority of methods whose params are
     * by-name. A message with no params, or with by-position (array) params, yields an empty
     * object rather than {@code null}, so callers can read optional fields without a null
     * check.
     *
     * @return the params object, or an empty object when there are none or they are an array
     *
     * @since 3.0
     */
    public JsonObject paramsObject()
    {
        return params != null && params.isJsonObject() ? params.getAsJsonObject() : new JsonObject();
    }
}
