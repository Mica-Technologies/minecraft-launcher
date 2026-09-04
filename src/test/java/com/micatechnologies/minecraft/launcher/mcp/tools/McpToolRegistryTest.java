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

package com.micatechnologies.minecraft.launcher.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.launcher.mcp.approval.McpRiskClass;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link McpToolRegistry} — the lookup behind {@code tools/call}.
 *
 * <p>Why this matters: the registry decides which code a model-chosen tool name reaches.
 * A duplicate registration that silently shadowed an earlier tool would mean a call landing
 * somewhere other than where the approval policy was configured — the policy is keyed by
 * name, so shadowing would let a tool inherit another tool's consent. Registration therefore
 * throws rather than overwrites, and that is a correctness property, not tidiness.</p>
 *
 * <p>Tool names are also validated on the way in, because they end up in consent dialogs and
 * audit-log lines. A name carrying a newline could forge an extra line in either.</p>
 */
class McpToolRegistryTest
{
    /** Minimal hand-rolled tool, per this repo's no-mocking-framework convention. */
    private static final class StubTool implements McpTool
    {
        private final String name;
        private final McpRiskClass risk;

        StubTool( String name, McpRiskClass risk )
        {
            this.name = name;
            this.risk = risk;
        }

        @Override
        public String name() { return name; }

        @Override
        public String title() { return "Title of " + name; }

        @Override
        public String description() { return "Describes " + name; }

        @Override
        public JsonObject inputSchema()
        {
            JsonObject schema = new JsonObject();
            schema.addProperty( "type", "object" );
            return schema;
        }

        @Override
        public McpRiskClass riskClass() { return risk; }

        @Override
        public McpToolResult invoke( McpCallContext context, JsonObject arguments )
        {
            return McpToolResult.text( "ran " + name );
        }
    }

    private static StubTool tool( String name )
    {
        return new StubTool( name, McpRiskClass.READ_ONLY );
    }

    // region registration

    @Test
    void aRegisteredToolIsFoundByName()
    {
        McpToolRegistry registry = new McpToolRegistry();
        StubTool listPacks = tool( "list_modpacks" );
        registry.register( listPacks );
        assertSame( listPacks, registry.find( "list_modpacks" ) );
        assertEquals( 1, registry.size() );
    }

    @Test
    void anUnknownNameResolvesToNothing()
    {
        McpToolRegistry registry = new McpToolRegistry();
        assertNull( registry.find( "nope" ) );
        assertNull( registry.find( null ) );
    }

    /**
     * The property that matters: a duplicate name is refused rather than shadowing the earlier
     * tool. Approval policy is keyed by tool name, so a shadowed tool would run under another
     * tool's consent.
     */
    @Test
    void aDuplicateNameIsRefusedRatherThanShadowing()
    {
        McpToolRegistry registry = new McpToolRegistry();
        registry.register( tool( "list_modpacks" ) );
        assertThrows( IllegalStateException.class, () -> registry.register( tool( "list_modpacks" ) ) );
    }

    @Test
    void aNullToolIsRefused()
    {
        assertThrows( IllegalArgumentException.class, () -> new McpToolRegistry().register( null ) );
    }

    @Test
    void aToolWithNoRiskClassIsRefused()
    {
        McpToolRegistry registry = new McpToolRegistry();
        assertThrows( IllegalArgumentException.class,
                      () -> registry.register( new StubTool( "no_risk", null ) ) );
    }

    // endregion

    // region name validation

    @Test
    void ordinaryToolNamesAreAccepted()
    {
        McpToolRegistry registry = new McpToolRegistry();
        for ( String name : new String[]{ "a", "ab", "list_modpacks", "get-crash-report", "tool2" } ) {
            registry.register( tool( name ) );
        }
        assertEquals( 5, registry.size() );
    }

    /**
     * Names reach consent dialogs and audit-log lines. A newline or a control character in one
     * could forge an extra line in either, so the shape is validated at registration.
     */
    @Test
    void namesWithWhitespaceOrControlCharactersAreRefused()
    {
        McpToolRegistry registry = new McpToolRegistry();
        for ( String name : new String[]{ "has space", "has\nnewline", "has\ttab", "has\rcr" } ) {
            assertThrows( IllegalArgumentException.class, () -> registry.register( tool( name ) ), name );
        }
    }

    @Test
    void namesWithShellOrPathMetacharactersAreRefused()
    {
        McpToolRegistry registry = new McpToolRegistry();
        for ( String name : new String[]{ "../etc", "a/b", "a;b", "a$b", "a|b", "a`b", "a*b" } ) {
            assertThrows( IllegalArgumentException.class, () -> registry.register( tool( name ) ), name );
        }
    }

    @Test
    void upperCaseNamesAreRefused()
    {
        McpToolRegistry registry = new McpToolRegistry();
        assertThrows( IllegalArgumentException.class, () -> registry.register( tool( "ListModpacks" ) ) );
    }

    @Test
    void blankAndNullNamesAreRefused()
    {
        McpToolRegistry registry = new McpToolRegistry();
        assertThrows( IllegalArgumentException.class, () -> registry.register( tool( "" ) ) );
        assertThrows( IllegalArgumentException.class, () -> registry.register( tool( null ) ) );
    }

    @Test
    void namesCannotStartOrEndWithASeparator()
    {
        McpToolRegistry registry = new McpToolRegistry();
        for ( String name : new String[]{ "_leading", "trailing_", "-leading", "trailing-" } ) {
            assertThrows( IllegalArgumentException.class, () -> registry.register( tool( name ) ), name );
        }
    }

    @Test
    void anOverlongNameIsRefused()
    {
        McpToolRegistry registry = new McpToolRegistry();
        assertThrows( IllegalArgumentException.class, () -> registry.register( tool( "a".repeat( 65 ) ) ) );
        registry.register( tool( "a".repeat( 64 ) ) );
    }

    // endregion

    // region listing

    /** A client that diffs {@code tools/list} should not see churn from map ordering. */
    @Test
    void listingPreservesRegistrationOrder()
    {
        McpToolRegistry registry = new McpToolRegistry();
        registry.register( tool( "zebra" ) );
        registry.register( tool( "alpha" ) );
        registry.register( tool( "middle" ) );

        JsonArray tools = registry.listResult().getAsJsonArray( "tools" );
        assertEquals( "zebra", tools.get( 0 ).getAsJsonObject().get( "name" ).getAsString() );
        assertEquals( "alpha", tools.get( 1 ).getAsJsonObject().get( "name" ).getAsString() );
        assertEquals( "middle", tools.get( 2 ).getAsJsonObject().get( "name" ).getAsString() );
        assertEquals( "zebra", registry.all().get( 0 ).name() );
    }

    @Test
    void eachListingEntryCarriesTheFieldsAClientNeeds()
    {
        McpToolRegistry registry = new McpToolRegistry();
        registry.register( tool( "list_modpacks" ) );
        JsonObject entry = registry.listResult().getAsJsonArray( "tools" ).get( 0 ).getAsJsonObject();
        assertEquals( "list_modpacks", entry.get( "name" ).getAsString() );
        assertEquals( "Title of list_modpacks", entry.get( "title" ).getAsString() );
        assertEquals( "Describes list_modpacks", entry.get( "description" ).getAsString() );
        assertEquals( "object", entry.getAsJsonObject( "inputSchema" ).get( "type" ).getAsString() );
    }

    @Test
    void anEmptyRegistryListsAnEmptyArrayRatherThanOmittingTheField()
    {
        JsonObject result = new McpToolRegistry().listResult();
        assertTrue( result.has( "tools" ) );
        assertEquals( 0, result.getAsJsonArray( "tools" ).size() );
    }

    @Test
    void theToolViewIsUnmodifiable()
    {
        McpToolRegistry registry = new McpToolRegistry();
        registry.register( tool( "one" ) );
        assertThrows( UnsupportedOperationException.class, () -> registry.all().add( tool( "two" ) ) );
    }

    // endregion
}
