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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The set of resources this server exposes, and the lookup behind {@code resources/read}.
 * <p>
 * Registration order is preserved so listings are stable between calls. Resolution walks the
 * registrations in order and takes the first match, so a concrete resource registered ahead of
 * a template that would also match wins — which is what lets a special case be layered over a
 * general one deliberately.
 *
 * @author Mica Technologies
 * @version 1.0
 * @since 3.0
 */
public final class McpResourceRegistry
{
    /** Registered resources, keyed by URI template, in registration order. */
    private final Map< String, McpResource > resources = new LinkedHashMap<>();

    /**
     * One successful resolution of a URI to a resource plus its placeholder values.
     *
     * @param resource the resource that matched
     * @param params   the decoded placeholder values; empty for a concrete resource
     *
     * @since 3.0
     */
    public record Match( McpResource resource, Map< String, String > params )
    {
    }

    /**
     * Registers a resource.
     *
     * @param resource the resource to register
     *
     * @throws IllegalArgumentException if the resource or its URI template is missing, or the
     *                                  template does not use the {@code mica://} scheme
     * @throws IllegalStateException    if a resource with the same template is already
     *                                  registered
     * @since 3.0
     */
    public void register( McpResource resource )
    {
        if ( resource == null ) {
            throw new IllegalArgumentException( "Cannot register a null resource" );
        }
        String template = resource.uriTemplate();
        if ( template == null || !template.startsWith( McpResourceUri.SCHEME ) ) {
            throw new IllegalArgumentException( "Resource URI must start with " + McpResourceUri.SCHEME
                                                        + ", got: " + template );
        }
        if ( resources.containsKey( template ) ) {
            throw new IllegalStateException( "Duplicate MCP resource URI: " + template );
        }
        resources.put( template, resource );
    }

    /**
     * Resolves a URI to a resource and its placeholder values.
     * <p>
     * A URI whose placeholder values fail {@link McpResourceUri}'s containment checks does not
     * match, so a traversal attempt reads as "no such resource" rather than reaching a
     * resource implementation.
     *
     * @param uri the URI to resolve
     *
     * @return the match, or {@code null} when nothing matches
     *
     * @since 3.0
     */
    public Match resolve( String uri )
    {
        if ( uri == null ) {
            return null;
        }
        for ( McpResource resource : resources.values() ) {
            Map< String, String > params = McpResourceUri.match( resource.uriTemplate(), uri );
            if ( params != null ) {
                return new Match( resource, params );
            }
        }
        return null;
    }

    /**
     * Returns every registered resource, in registration order.
     *
     * @return an unmodifiable view of the registered resources
     *
     * @since 3.0
     */
    public List< McpResource > all()
    {
        return Collections.unmodifiableList( new ArrayList<>( resources.values() ) );
    }

    /**
     * Returns how many resources are registered.
     *
     * @return the resource count
     *
     * @since 3.0
     */
    public int size()
    {
        return resources.size();
    }

    /**
     * Builds the {@code resources/list} result, expanding each registration into its concrete
     * URIs.
     *
     * @return the result object, carrying a {@code resources} array
     *
     * @since 3.0
     */
    public JsonObject listResult()
    {
        JsonArray array = new JsonArray();
        for ( McpResource resource : resources.values() ) {
            for ( String uri : resource.concreteUris() ) {
                array.add( resource.describeConcrete( uri ) );
            }
        }
        JsonObject result = new JsonObject();
        result.add( "resources", array );
        return result;
    }

    /**
     * Builds the {@code resources/templates/list} result, carrying only the parameterized
     * registrations.
     *
     * @return the result object, carrying a {@code resourceTemplates} array
     *
     * @since 3.0
     */
    public JsonObject templatesListResult()
    {
        JsonArray array = new JsonArray();
        for ( McpResource resource : resources.values() ) {
            if ( resource.isTemplate() ) {
                array.add( resource.describeTemplate() );
            }
        }
        JsonObject result = new JsonObject();
        result.add( "resourceTemplates", array );
        return result;
    }
}
