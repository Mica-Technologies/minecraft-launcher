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

package com.micatechnologies.minecraft.launcher.mcp.resources;

import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;

/**
 * Something an MCP client can read repeatedly, addressed by a {@code mica://} URI.
 * <p>
 * Resources are the right shape for content a client wants to <em>read</em> rather than
 * <em>act on</em> — a pack index, a manifest, a crash report. They are read-only by
 * construction: there is no write path, so no resource needs an approval decision.
 * <p>
 * A resource is either concrete ({@code mica://packs}) or a template
 * ({@code mica://modpack/{friendlyName}/manifest}). A template additionally enumerates its
 * current instances through {@link #concreteUris()} so clients can discover what exists.
 *
 * @author Mica Technologies
 * @version 1.0
 * @since 3.0
 */
public interface McpResource
{
    /**
     * Returns this resource's URI, or its URI template when parameterized.
     *
     * @return the URI or template
     *
     * @since 3.0
     */
    String uriTemplate();

    /**
     * Returns a short human-readable name.
     *
     * @return the display name
     *
     * @since 3.0
     */
    String name();

    /**
     * Returns a description of what this resource contains.
     *
     * @return the description
     *
     * @since 3.0
     */
    String description();

    /**
     * Returns the MIME type of the content, e.g. {@code application/json} or
     * {@code text/plain}.
     *
     * @return the MIME type
     *
     * @since 3.0
     */
    String mimeType();

    /**
     * Reads the resource.
     * <p>
     * Every placeholder value in {@code params} has already passed
     * {@link McpResourceUri#isSafeSegment} — it is a single, non-blank path segment with no
     * separators or control characters. Implementations must still treat it as untrusted
     * <em>content</em> and look it up rather than concatenating it into a path.
     *
     * @param params the decoded placeholder values; empty for a concrete resource
     *
     * @return the content
     *
     * @throws Exception if the resource cannot be read
     * @since 3.0
     */
    String read( Map< String, String > params ) throws Exception;

    /**
     * Reports whether this resource is parameterized.
     *
     * @return {@code true} when {@link #uriTemplate()} contains a placeholder
     *
     * @since 3.0
     */
    default boolean isTemplate()
    {
        return McpResourceUri.isTemplate( uriTemplate() );
    }

    /**
     * Enumerates the concrete URIs this resource currently exposes.
     * <p>
     * A concrete resource returns just its own URI. A template returns one URI per instance
     * that exists right now — one per installed modpack, say. The default is an empty list, so
     * a template that cannot cheaply enumerate itself simply does not appear in
     * {@code resources/list} while remaining readable and discoverable through
     * {@code resources/templates/list}.
     *
     * @return the concrete URIs
     *
     * @since 3.0
     */
    default List< String > concreteUris()
    {
        return isTemplate() ? List.of() : List.of( uriTemplate() );
    }

    /**
     * Builds this resource's entry in a {@code resources/templates/list} response.
     *
     * @return the descriptor object
     *
     * @since 3.0
     */
    default JsonObject describeTemplate()
    {
        JsonObject descriptor = new JsonObject();
        descriptor.addProperty( "uriTemplate", uriTemplate() );
        descriptor.addProperty( "name", name() );
        descriptor.addProperty( "description", description() );
        descriptor.addProperty( "mimeType", mimeType() );
        return descriptor;
    }

    /**
     * Builds an entry in a {@code resources/list} response for one concrete URI of this
     * resource.
     *
     * @param uri the concrete URI to describe
     *
     * @return the descriptor object
     *
     * @since 3.0
     */
    default JsonObject describeConcrete( String uri )
    {
        JsonObject descriptor = new JsonObject();
        descriptor.addProperty( "uri", uri );
        descriptor.addProperty( "name", name() );
        descriptor.addProperty( "description", description() );
        descriptor.addProperty( "mimeType", mimeType() );
        return descriptor;
    }
}
