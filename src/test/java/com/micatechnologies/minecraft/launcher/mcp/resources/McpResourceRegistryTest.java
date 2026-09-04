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

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link McpResourceRegistry} — the lookup behind {@code resources/read}.
 *
 * <p>Why this matters beyond plumbing: resolution is where the URI containment gate actually
 * takes effect. A traversal attempt has to read as "no such resource" and stop at the
 * registry, rather than reaching a resource implementation that then has to defend itself.
 * The test for that is the one to keep if the rest are ever trimmed.</p>
 */
class McpResourceRegistryTest
{
    /** Minimal hand-rolled resource, per this repo's no-mocking-framework convention. */
    private static final class StubResource implements McpResource
    {
        private final String template;
        private final List< String > concrete;

        StubResource( String template, List< String > concrete )
        {
            this.template = template;
            this.concrete = concrete;
        }

        @Override
        public String uriTemplate() { return template; }

        @Override
        public String name() { return "Name of " + template; }

        @Override
        public String description() { return "Describes " + template; }

        @Override
        public String mimeType() { return "application/json"; }

        @Override
        public String read( Map< String, String > params ) { return "read " + params; }

        @Override
        public List< String > concreteUris()
        {
            return concrete == null ? McpResource.super.concreteUris() : concrete;
        }
    }

    private static StubResource resource( String template )
    {
        return new StubResource( template, null );
    }

    // region registration

    @Test
    void aRegisteredResourceResolvesByUri()
    {
        McpResourceRegistry registry = new McpResourceRegistry();
        StubResource packs = resource( "mica://packs" );
        registry.register( packs );

        McpResourceRegistry.Match match = registry.resolve( "mica://packs" );
        assertNotNull( match );
        assertSame( packs, match.resource() );
        assertTrue( match.params().isEmpty() );
    }

    @Test
    void aTemplateResolvesWithItsPlaceholderValues()
    {
        McpResourceRegistry registry = new McpResourceRegistry();
        registry.register( resource( "mica://modpack/{friendlyName}/manifest" ) );

        McpResourceRegistry.Match match = registry.resolve( "mica://modpack/AllTheMods9/manifest" );
        assertNotNull( match );
        assertEquals( "AllTheMods9", match.params().get( "friendlyName" ) );
    }

    @Test
    void anUnknownUriResolvesToNothing()
    {
        McpResourceRegistry registry = new McpResourceRegistry();
        registry.register( resource( "mica://packs" ) );
        assertNull( registry.resolve( "mica://nope" ) );
        assertNull( registry.resolve( null ) );
    }

    /**
     * The property worth keeping: a traversal attempt stops at the registry. It must never
     * reach a resource implementation carrying {@code ..} as a lookup key.
     */
    @Test
    void aTraversalAttemptReadsAsNoSuchResource()
    {
        McpResourceRegistry registry = new McpResourceRegistry();
        registry.register( resource( "mica://modpack/{friendlyName}/manifest" ) );

        assertNull( registry.resolve( "mica://modpack/../manifest" ) );
        assertNull( registry.resolve( "mica://modpack/%2e%2e/manifest" ) );
        assertNull( registry.resolve( "mica://modpack/a%2Fb/manifest" ) );
        assertNull( registry.resolve( "mica://modpack/pack%00/manifest" ) );
    }

    @Test
    void aNullResourceIsRefused()
    {
        assertThrows( IllegalArgumentException.class, () -> new McpResourceRegistry().register( null ) );
    }

    @Test
    void aResourceOutsideTheMicaSchemeIsRefused()
    {
        McpResourceRegistry registry = new McpResourceRegistry();
        for ( String template : new String[]{ "file:///etc/passwd", "http://example.test/x", "packs", null } ) {
            assertThrows( IllegalArgumentException.class, () -> registry.register( resource( template ) ),
                          String.valueOf( template ) );
        }
    }

    @Test
    void aDuplicateUriIsRefusedRatherThanShadowing()
    {
        McpResourceRegistry registry = new McpResourceRegistry();
        registry.register( resource( "mica://packs" ) );
        assertThrows( IllegalStateException.class, () -> registry.register( resource( "mica://packs" ) ) );
    }

    /** First registration wins, so a special case can be layered over a general one. */
    @Test
    void resolutionTakesTheFirstMatchingRegistration()
    {
        McpResourceRegistry registry = new McpResourceRegistry();
        StubResource specific = resource( "mica://modpack/special/manifest" );
        StubResource general = resource( "mica://modpack/{friendlyName}/manifest" );
        registry.register( specific );
        registry.register( general );

        assertSame( specific, registry.resolve( "mica://modpack/special/manifest" ).resource() );
        assertSame( general, registry.resolve( "mica://modpack/other/manifest" ).resource() );
    }

    // endregion

    // region listing

    @Test
    void concreteResourcesListThemselves()
    {
        McpResourceRegistry registry = new McpResourceRegistry();
        registry.register( resource( "mica://packs" ) );
        assertEquals( 1, registry.listResult().getAsJsonArray( "resources" ).size() );
        assertEquals( "mica://packs", registry.listResult().getAsJsonArray( "resources" )
                .get( 0 ).getAsJsonObject().get( "uri" ).getAsString() );
    }

    @Test
    void aTemplateListsItsCurrentInstances()
    {
        McpResourceRegistry registry = new McpResourceRegistry();
        registry.register( new StubResource( "mica://modpack/{friendlyName}/manifest",
                                             List.of( "mica://modpack/One/manifest",
                                                      "mica://modpack/Two/manifest" ) ) );
        assertEquals( 2, registry.listResult().getAsJsonArray( "resources" ).size() );
    }

    /**
     * A template that cannot cheaply enumerate itself contributes nothing to
     * {@code resources/list} while staying readable and discoverable via
     * {@code resources/templates/list}.
     */
    @Test
    void aTemplateThatEnumeratesNothingIsAbsentFromTheConcreteListing()
    {
        McpResourceRegistry registry = new McpResourceRegistry();
        registry.register( resource( "mica://modpack/{friendlyName}/manifest" ) );
        assertEquals( 0, registry.listResult().getAsJsonArray( "resources" ).size() );
        assertEquals( 1, registry.templatesListResult().getAsJsonArray( "resourceTemplates" ).size() );
    }

    @Test
    void concreteResourcesAreAbsentFromTheTemplateListing()
    {
        McpResourceRegistry registry = new McpResourceRegistry();
        registry.register( resource( "mica://packs" ) );
        assertEquals( 0, registry.templatesListResult().getAsJsonArray( "resourceTemplates" ).size() );
    }

    @Test
    void listingsCarryTheFieldsAClientNeeds()
    {
        McpResourceRegistry registry = new McpResourceRegistry();
        registry.register( resource( "mica://packs" ) );
        var entry = registry.listResult().getAsJsonArray( "resources" ).get( 0 ).getAsJsonObject();
        assertEquals( "Name of mica://packs", entry.get( "name" ).getAsString() );
        assertEquals( "application/json", entry.get( "mimeType" ).getAsString() );
        assertTrue( entry.has( "description" ) );
    }

    @Test
    void emptyRegistriesListEmptyArraysRatherThanOmittingTheField()
    {
        McpResourceRegistry registry = new McpResourceRegistry();
        assertTrue( registry.listResult().has( "resources" ) );
        assertTrue( registry.templatesListResult().has( "resourceTemplates" ) );
        assertEquals( 0, registry.size() );
    }

    @Test
    void theResourceViewIsUnmodifiable()
    {
        McpResourceRegistry registry = new McpResourceRegistry();
        registry.register( resource( "mica://packs" ) );
        assertThrows( UnsupportedOperationException.class,
                      () -> registry.all().add( resource( "mica://other" ) ) );
    }

    // endregion
}
