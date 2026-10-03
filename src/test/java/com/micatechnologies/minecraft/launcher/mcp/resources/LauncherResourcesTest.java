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
import com.micatechnologies.minecraft.launcher.mcp.tools.McpLauncherView;
import com.micatechnologies.minecraft.launcher.utilities.JSONUtilities;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the read-only MCP resources.
 *
 * <p>The property worth the most here is the one about <b>enumeration and resolution
 * agreeing</b>. A resource listing advertises concrete URIs, and a client will read back
 * exactly what it was handed. If a pack's name cannot survive
 * {@link McpResourceUri}'s containment checks, advertising a URI for it would hand the client
 * something guaranteed to fail — so such packs are skipped from the listing rather than
 * emitted and rejected later. A pack named with a slash is not hypothetical: friendly names
 * come from author-controlled manifest JSON.</p>
 */
class LauncherResourcesTest
{
    private StubView view;
    private McpResourceRegistry registry;

    /** Hand-rolled view, per this repo's no-mocking-framework convention. */
    private static final class StubView implements McpLauncherView
    {
        private final List< PackSummary > packs = new ArrayList<>();
        private PackFootprint footprint;
        private String manifest = "{}";
        private CrashInfo crash;

        @Override
        public List< PackSummary > packs() { return packs; }

        @Override
        public PackSummary pack( String friendlyName )
        {
            return packs.stream().filter( p -> p.friendlyName().equals( friendlyName ) )
                    .findFirst().orElse( null );
        }

        @Override
        public String manifestOf( String friendlyName )
        {
            return pack( friendlyName ) == null ? null : manifest;
        }

        @Override
        public CrashInfo latestCrashOf( String friendlyName )
        {
            return pack( friendlyName ) == null ? null : crash;
        }

        @Override
        public PackFootprint footprintOf( String friendlyName ) { return footprint; }

        @Override
        public LauncherStatus status()
        {
            return new LauncherStatus( "3.0-test", false, "", packs.size() );
        }

        @Override
        public java.util.List< RunningGame > runningGames() { return java.util.List.of(); }
    }

    @BeforeEach
    void setUp()
    {
        view = new StubView();
        registry = new McpResourceRegistry();
        LauncherResources.registerAll( registry, view );
    }

    // region registration

    @Test
    void everyResourceRegisters()
    {
        assertEquals( 3, registry.size() );
        assertNotNull( registry.resolve( LauncherResources.PACKS_URI ) );
        assertNotNull( registry.resolve( "mica://modpack/Pack/manifest" ) );
        assertNotNull( registry.resolve( "mica://modpack/Pack/crash-report" ) );
    }

    @Test
    void registrationRequiresBothArguments()
    {
        assertThrows( IllegalArgumentException.class,
                      () -> LauncherResources.registerAll( null, view ) );
        assertThrows( IllegalArgumentException.class,
                      () -> LauncherResources.registerAll( new McpResourceRegistry(), null ) );
    }

    @Test
    void onlyThePerPackResourcesAreTemplates()
    {
        assertEquals( 2, registry.templatesListResult()
                .getAsJsonArray( "resourceTemplates" ).size() );
    }

    // endregion

    // region reading

    @Test
    void thePackIndexListsEveryPack() throws Exception
    {
        view.packs.add( new McpLauncherView.PackSummary( "Pack A", "1", "1", false, "forge", true, false ) );
        view.packs.add( new McpLauncherView.PackSummary( "Pack B", "2", "2", false, "fabric", false, true ) );

        McpResourceRegistry.Match match = registry.resolve( LauncherResources.PACKS_URI );
        JsonObject index = JSONUtilities.getGson()
                .fromJson( match.resource().read( match.params() ), JsonObject.class );

        assertEquals( 2, index.getAsJsonArray( "modpacks" ).size() );
        assertEquals( "Pack A", index.getAsJsonArray( "modpacks" ).get( 0 ).getAsJsonObject()
                .get( "friendlyName" ).getAsString() );
    }

    @Test
    void aManifestIsReadThroughItsTemplate() throws Exception
    {
        view.packs.add( new McpLauncherView.PackSummary( "Pack", "1", "1", false, "forge", true, false ) );
        view.manifest = "{\"packName\":\"Pack\"}";

        McpResourceRegistry.Match match = registry.resolve( "mica://modpack/Pack/manifest" );
        assertEquals( "{\"packName\":\"Pack\"}", match.resource().read( match.params() ) );
    }

    @Test
    void aCrashReportIsReadThroughItsTemplate() throws Exception
    {
        view.packs.add( new McpLauncherView.PackSummary( "Pack", "1", "1", false, "forge", true, false ) );
        view.crash = new McpLauncherView.CrashInfo( "boom", "", "", "", List.of() );

        McpResourceRegistry.Match match = registry.resolve( "mica://modpack/Pack/crash-report" );
        assertEquals( "boom", match.resource().read( match.params() ) );
    }

    /**
     * Reading a resource for a pack that does not exist throws, and the dispatcher turns that
     * into an internal error rather than leaking the message — verified separately in
     * {@code McpRequestHandlerTest}.
     */
    @Test
    void readingAnUnknownPackFails()
    {
        McpResourceRegistry.Match match = registry.resolve( "mica://modpack/Ghost/manifest" );
        assertNotNull( match, "the URI shape matches even though the pack does not exist" );
        assertThrows( IllegalArgumentException.class, () -> match.resource().read( match.params() ) );
    }

    // endregion

    // region enumeration

    @Test
    void manifestsAreEnumeratedForEveryPack()
    {
        view.packs.add( new McpLauncherView.PackSummary( "Installed", "1", "1", false, "forge", true, false ) );
        view.packs.add( new McpLauncherView.PackSummary( "Available", "2", "2", false, "forge", false, false ) );

        List< String > uris = uriListing();
        assertTrue( uris.contains( "mica://modpack/Installed/manifest" ), uris.toString() );
        assertTrue( uris.contains( "mica://modpack/Available/manifest" ), uris.toString() );
    }

    /** A pack that is not installed cannot have crashed locally, so it gets no crash URI. */
    @Test
    void crashReportsAreEnumeratedOnlyForInstalledPacks()
    {
        view.packs.add( new McpLauncherView.PackSummary( "Installed", "1", "1", false, "forge", true, false ) );
        view.packs.add( new McpLauncherView.PackSummary( "Available", "2", "2", false, "forge", false, false ) );

        List< String > uris = uriListing();
        assertTrue( uris.contains( "mica://modpack/Installed/crash-report" ), uris.toString() );
        assertFalse( uris.contains( "mica://modpack/Available/crash-report" ), uris.toString() );
    }

    @Test
    void aPackNameWithSpacesIsEnumeratedEncodedAndResolvesBack()
    {
        view.packs.add( new McpLauncherView.PackSummary( "All the Mods 9", "1", "1", false, "forge", true, false ) );

        String uri = uriListing().stream().filter( u -> u.endsWith( "/manifest" ) ).findFirst()
                .orElseThrow();
        assertFalse( uri.contains( " " ), uri );

        McpResourceRegistry.Match match = registry.resolve( uri );
        assertNotNull( match, "an advertised URI must resolve: " + uri );
        assertEquals( "All the Mods 9", match.params().get( "friendlyName" ) );
    }

    /**
     * The property this class exists for. Friendly names come from author-controlled manifest
     * JSON, so a name containing a separator is reachable by anyone who can get a user to add
     * their pack. Advertising a URI for it would hand the client something the containment gate
     * is guaranteed to refuse, so those packs are skipped from the listing instead.
     */
    @Test
    void aPackWhoseNameCannotBeAUriSegmentIsSkippedRatherThanAdvertised()
    {
        view.packs.add( new McpLauncherView.PackSummary( "good", "1", "1", false, "forge", true, false ) );
        for ( String hostile : new String[]{ "../etc", "a/b", "..", ".", "   ", "with\nnewline" } ) {
            view.packs.add( new McpLauncherView.PackSummary( hostile, "1", "1", false, "forge", true, false ) );
        }

        List< String > uris = uriListing();
        assertEquals( 2, uris.size(), "only the well-named pack may be advertised: " + uris );
        for ( String uri : uris ) {
            assertNotNull( registry.resolve( uri ), "every advertised URI must resolve: " + uri );
            assertEquals( "good", registry.resolve( uri ).params().get( "friendlyName" ) );
        }
    }

    /**
     * Everything advertised must resolve. This is the general form of the property above, and
     * the one that would catch a future encoding change breaking the round trip.
     */
    @Test
    void everyAdvertisedUriResolves()
    {
        for ( String name : new String[]{ "simple", "with space", "with-dash", "with_underscore",
                                          "100% Pack", "パック", "dots.in.name" } ) {
            view.packs.add( new McpLauncherView.PackSummary( name, "1", "1", false, "forge", true, false ) );
        }
        List< String > uris = uriListing();
        assertEquals( 14, uris.size(), "7 packs x 2 per-pack resources" );
        for ( String uri : uris ) {
            assertNotNull( registry.resolve( uri ), "advertised but unresolvable: " + uri );
        }
    }

    @Test
    void anEmptyLibraryAdvertisesOnlyThePackIndex()
    {
        assertEquals( 1, registry.listResult().getAsJsonArray( "resources" ).size() );
        assertEquals( LauncherResources.PACKS_URI,
                      registry.listResult().getAsJsonArray( "resources" ).get( 0 ).getAsJsonObject()
                              .get( "uri" ).getAsString() );
    }

    // endregion

    /**
     * Returns every concrete per-pack URI the registry advertises, excluding the pack index.
     *
     * @return the advertised per-pack URIs
     */
    private List< String > uriListing()
    {
        List< String > uris = new ArrayList<>();
        registry.listResult().getAsJsonArray( "resources" ).forEach( element -> {
            String uri = element.getAsJsonObject().get( "uri" ).getAsString();
            if ( !uri.equals( LauncherResources.PACKS_URI ) ) {
                uris.add( uri );
            }
        } );
        return uris;
    }
}
