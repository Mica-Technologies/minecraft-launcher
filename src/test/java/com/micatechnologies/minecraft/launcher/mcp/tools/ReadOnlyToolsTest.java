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

import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.launcher.mcp.approval.McpRiskClass;
import com.micatechnologies.minecraft.launcher.utilities.JSONUtilities;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the read-only MCP tools.
 *
 * <p>Why this matters beyond formatting: these are the first tools a model will actually
 * reach, and two properties decide whether they are safe and usable.</p>
 *
 * <p><b>Safe:</b> every one is declared {@code READ_ONLY}, which is what makes them eligible
 * for auto-approval. A tool that mutated anything while carrying that class would run
 * unattended. And none of them can surface a credential — that is enforced by
 * {@link McpLauncherView} having nowhere to put one, which is asserted here by checking that
 * a view whose username field is stuffed with a token still cannot leak the account UUID.</p>
 *
 * <p><b>Usable:</b> a wrong pack name has to produce an error the model can recover from
 * rather than a stack trace. Each failure message names what to do next.</p>
 */
class ReadOnlyToolsTest
{
    private StubView view;
    private McpToolRegistry registry;

    /** Hand-rolled view, per this repo's no-mocking-framework convention. */
    private static final class StubView implements McpLauncherView
    {
        private final List< PackSummary > packs = new ArrayList<>();
        private PackFootprint footprint;
        private String manifest;
        private CrashInfo crash;
        private LauncherStatus status =
                new LauncherStatus( "3.0-test", false, "", 0 );

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
        public LauncherStatus status() { return status; }
    }

    @BeforeEach
    void setUp()
    {
        view = new StubView();
        registry = new McpToolRegistry();
        ReadOnlyTools.registerAll( registry, view );
    }

    // region registration

    @Test
    void everyReadOnlyToolRegisters()
    {
        assertEquals( 6, registry.size() );
        for ( String name : new String[]{ "list_modpacks", "get_modpack_info", "get_modpack_manifest",
                                          "get_crash_report", "diagnose_launch_failure",
                                          "get_launcher_status" } ) {
            assertNotNull( registry.find( name ), name + " should be registered" );
        }
    }

    /**
     * The load-bearing property: {@code READ_ONLY} is what makes a tool eligible for
     * auto-approval, so a tool carrying it that actually mutated something would run
     * unattended. Nothing in this set may drift out of that class without the drift being
     * deliberate.
     */
    @Test
    void everyToolInThisSetIsDeclaredReadOnly()
    {
        for ( McpTool tool : registry.all() ) {
            assertEquals( McpRiskClass.READ_ONLY, tool.riskClass(),
                          tool.name() + " must stay read-only" );
        }
    }

    /** The description is what the model reasons over, so an empty one is a real defect. */
    @Test
    void everyToolDescribesItselfUsefully()
    {
        for ( McpTool tool : registry.all() ) {
            assertTrue( tool.description().length() > 40, tool.name() + " needs a real description" );
            assertFalse( tool.title().isBlank(), tool.name() + " needs a title" );
            assertEquals( "object", tool.inputSchema().get( "type" ).getAsString() );
        }
    }

    @Test
    void registrationRequiresBothArguments()
    {
        assertThrows( IllegalArgumentException.class,
                      () -> ReadOnlyTools.registerAll( null, view ) );
        assertThrows( IllegalArgumentException.class,
                      () -> ReadOnlyTools.registerAll( new McpToolRegistry(), null ) );
    }

    // endregion

    // region list_modpacks

    @Test
    void listingReportsEveryPack()
    {
        view.packs.add( new McpLauncherView.PackSummary( "All the Mods 9", "1.2.3", "1.2.3", false, "forge", true, false ) );
        view.packs.add( new McpLauncherView.PackSummary( "Vault Hunters", "3.0", "3.0", false, "fabric", false, true ) );

        JsonObject result = jsonOf( call( "list_modpacks", new JsonObject() ) );
        assertEquals( 2, result.get( "count" ).getAsInt() );

        JsonObject first = result.getAsJsonArray( "modpacks" ).get( 0 ).getAsJsonObject();
        assertEquals( "All the Mods 9", first.get( "friendlyName" ).getAsString() );
        assertEquals( "forge", first.get( "modLoader" ).getAsString() );
        assertTrue( first.get( "installed" ).getAsBoolean() );
        assertFalse( first.get( "unstable" ).getAsBoolean() );
    }

    /**
     * A pack's friendly name embeds the version its author publishes, which is not necessarily
     * what is installed — a real library had "Alto: 26.9.3" sitting at version 26.6.12.
     * Reporting only the installed version leaves a reader unable to reconcile the two, so both
     * are reported alongside the launcher's own update verdict.
     */
    @Test
    void anOutdatedPackReportsBothVersionsAndFlagsTheUpdate()
    {
        view.packs.add( new McpLauncherView.PackSummary( "Alto: 26.9.3", "26.6.12", "26.9.3",
                                                         true, "forge", true, false ) );
        JsonObject entry = jsonOf( call( "list_modpacks", new JsonObject() ) )
                .getAsJsonArray( "modpacks" ).get( 0 ).getAsJsonObject();

        assertEquals( "26.6.12", entry.get( "version" ).getAsString() );
        assertEquals( "26.9.3", entry.get( "latestVersion" ).getAsString() );
        assertTrue( entry.get( "updateAvailable" ).getAsBoolean() );

        JsonObject info = jsonOf( call( "get_modpack_info", packArgs( "Alto: 26.9.3" ) ) );
        assertEquals( "26.9.3", info.get( "latestVersion" ).getAsString() );
        assertTrue( info.get( "updateAvailable" ).getAsBoolean() );
    }

    @Test
    void anUpToDatePackIsNotFlagged()
    {
        view.packs.add( new McpLauncherView.PackSummary( "Current", "1.0", "1.0", false,
                                                         "forge", true, false ) );
        assertFalse( jsonOf( call( "get_modpack_info", packArgs( "Current" ) ) )
                             .get( "updateAvailable" ).getAsBoolean() );
    }

    /** An empty library is a normal state, not a failure. */
    @Test
    void listingAnEmptyLibraryIsNotAnError()
    {
        McpToolResult result = call( "list_modpacks", new JsonObject() );
        assertFalse( result.isError() );
        assertEquals( 0, jsonOf( result ).get( "count" ).getAsInt() );
    }

    // endregion

    // region get_modpack_info

    @Test
    void packInfoIsReported()
    {
        view.packs.add( new McpLauncherView.PackSummary( "All the Mods 9", "1.2.3", "1.2.3", false, "forge", true, false ) );
        JsonObject result = jsonOf( call( "get_modpack_info", packArgs( "All the Mods 9" ) ) );
        assertEquals( "1.2.3", result.get( "version" ).getAsString() );
    }

    /**
     * The recovery property: the error names {@code list_modpacks}, so a model that guessed a
     * name has an obvious next move instead of retrying the same guess.
     */
    @Test
    void anUnknownPackNameTellsTheModelHowToRecover()
    {
        McpToolResult result = call( "get_modpack_info", packArgs( "Nonexistent" ) );
        assertTrue( result.isError() );
        assertTrue( result.rawTextBlocks().get( 0 ).contains( "list_modpacks" ),
                    result.rawTextBlocks().get( 0 ) );
    }

    @Test
    void aMissingPackNameArgumentIsReported()
    {
        for ( String tool : new String[]{ "get_modpack_info", "get_modpack_manifest",
                                          "get_crash_report", "diagnose_launch_failure" } ) {
            assertTrue( call( tool, new JsonObject() ).isError(), tool );
        }
    }

    @Test
    void aBlankOrNonStringPackNameIsReported()
    {
        JsonObject blank = new JsonObject();
        blank.addProperty( "friendlyName", "   " );
        assertTrue( call( "get_modpack_info", blank ).isError() );

        JsonObject numeric = new JsonObject();
        numeric.addProperty( "friendlyName", 7 );
        // A number is a JSON primitive, so it reads as the string "7" and simply finds no pack.
        assertTrue( call( "get_modpack_info", numeric ).isError() );
    }

    /** Surrounding whitespace is forgiven — models routinely include it. */
    @Test
    void aPackNameIsTrimmedBeforeLookup()
    {
        view.packs.add( new McpLauncherView.PackSummary( "Pack", "1", "1", false, "forge", true, false ) );
        assertFalse( call( "get_modpack_info", packArgs( "  Pack  " ) ).isError() );
    }

    // endregion

    // region manifest and crash report

    @Test
    void aManifestIsReturnedVerbatim()
    {
        view.packs.add( new McpLauncherView.PackSummary( "Pack", "1", "1", false, "forge", true, false ) );
        view.manifest = "{\"packName\":\"Pack\"}";
        McpToolResult result = call( "get_modpack_manifest", packArgs( "Pack" ) );
        assertFalse( result.isError() );
        assertEquals( "{\"packName\":\"Pack\"}", result.rawTextBlocks().get( 0 ) );
    }

    @Test
    void anUnreachableManifestIsReported()
    {
        view.packs.add( new McpLauncherView.PackSummary( "Pack", "1", "1", false, "forge", true, false ) );
        view.manifest = null;
        assertTrue( call( "get_modpack_manifest", packArgs( "Pack" ) ).isError() );
    }

    @Test
    void aCrashReportIsReturned()
    {
        view.packs.add( new McpLauncherView.PackSummary( "Pack", "1", "1", false, "forge", true, false ) );
        view.crash = new McpLauncherView.CrashInfo( "java.lang.OutOfMemoryError", "Out of memory",
                                                    "The game ran out of heap", "OUT_OF_MEMORY",
                                                    List.of( "Raise the memory allocation" ) );
        McpToolResult result = call( "get_crash_report", packArgs( "Pack" ) );
        assertFalse( result.isError() );
        assertTrue( result.rawTextBlocks().get( 0 ).contains( "OutOfMemoryError" ) );
    }

    @Test
    void aPackThatHasNotCrashedReportsThatClearly()
    {
        view.packs.add( new McpLauncherView.PackSummary( "Pack", "1", "1", false, "forge", true, false ) );
        view.crash = null;
        assertTrue( call( "get_crash_report", packArgs( "Pack" ) ).isError() );
    }

    @Test
    void aBlankCrashReportCountsAsNoReport()
    {
        view.packs.add( new McpLauncherView.PackSummary( "Pack", "1", "1", false, "forge", true, false ) );
        view.crash = new McpLauncherView.CrashInfo( "   ", "", "", "", List.of() );
        assertTrue( call( "get_crash_report", packArgs( "Pack" ) ).isError() );
    }

    // endregion

    // region diagnose_launch_failure

    @Test
    void diagnosisCombinesTheReportWithItsAnalysisAndPackContext()
    {
        view.packs.add( new McpLauncherView.PackSummary( "Pack", "1.2.3", "1.2.3", false, "forge", true, true ) );
        view.crash = new McpLauncherView.CrashInfo( "java.lang.OutOfMemoryError", "Out of memory",
                                                    "The game ran out of heap", "OUT_OF_MEMORY",
                                                    List.of( "Raise the memory allocation",
                                                             "Remove a memory-hungry mod" ) );

        JsonObject result = jsonOf( call( "diagnose_launch_failure", packArgs( "Pack" ) ) );

        assertTrue( result.get( "crashReportAvailable" ).getAsBoolean() );
        assertEquals( "OUT_OF_MEMORY", result.get( "category" ).getAsString() );
        assertEquals( "The game ran out of heap", result.get( "summary" ).getAsString() );
        assertEquals( 2, result.getAsJsonArray( "suggestions" ).size() );
        assertTrue( result.get( "crashReport" ).getAsString().contains( "OutOfMemoryError" ) );
        assertEquals( "1.2.3", result.get( "version" ).getAsString() );
        assertTrue( result.get( "unstable" ).getAsBoolean(),
                    "an unstable pack is context worth having when reading a crash" );
    }

    /**
     * "This pack has not crashed" is a useful answer, not a failure. Reporting it as an error
     * would push the model to retry a call that will never succeed.
     */
    @Test
    void diagnosingAPackThatHasNotCrashedSucceedsWithANote()
    {
        view.packs.add( new McpLauncherView.PackSummary( "Pack", "1", "1", false, "forge", true, false ) );
        view.crash = null;

        McpToolResult result = call( "diagnose_launch_failure", packArgs( "Pack" ) );
        assertFalse( result.isError(), "not having crashed is not a tool failure" );
        assertFalse( jsonOf( result ).get( "crashReportAvailable" ).getAsBoolean() );
    }

    @Test
    void diagnosisToleratesAnAbsentSuggestionList()
    {
        view.packs.add( new McpLauncherView.PackSummary( "Pack", "1", "1", false, "forge", true, false ) );
        view.crash = new McpLauncherView.CrashInfo( "boom", "", "", "", null );
        JsonObject result = jsonOf( call( "diagnose_launch_failure", packArgs( "Pack" ) ) );
        assertEquals( 0, result.getAsJsonArray( "suggestions" ).size() );
    }

    // endregion

    // region get_launcher_status

    @Test
    void statusReportsTheSignedInUsername()
    {
        view.status = new McpLauncherView.LauncherStatus( "3.0-test", true, "Steve", 4 );
        JsonObject result = jsonOf( call( "get_launcher_status", new JsonObject() ) );
        assertTrue( result.get( "signedIn" ).getAsBoolean() );
        assertEquals( "Steve", result.get( "username" ).getAsString() );
        assertEquals( 4, result.get( "installedModpackCount" ).getAsInt() );
    }

    @Test
    void statusWorksWhileSignedOut()
    {
        JsonObject result = jsonOf( call( "get_launcher_status", new JsonObject() ) );
        assertFalse( result.get( "signedIn" ).getAsBoolean() );
        assertEquals( "", result.get( "username" ).getAsString() );
    }

    /**
     * The status payload has no field for a UUID or a token, and the view it reads from has no
     * method returning one. This asserts the shape rather than the values: a future field added
     * carelessly would show up here.
     */
    @Test
    void theStatusPayloadHasNoPlaceToPutACredential()
    {
        view.status = new McpLauncherView.LauncherStatus( "3.0-test", true, "Steve", 1 );
        JsonObject result = jsonOf( call( "get_launcher_status", new JsonObject() ) );
        assertEquals( 4, result.size(), "unexpected extra field in the status payload: " + result );
        for ( String forbidden : new String[]{ "uuid", "accessToken", "clientToken", "token" } ) {
            assertFalse( result.has( forbidden ), "status must never carry " + forbidden );
        }
    }

    /**
     * Belt and braces on the serialization boundary: even if something upstream put a
     * credential-shaped string into the username, it is redacted on the way out because every
     * result crosses {@link McpToolResult#toJson()}.
     */
    @Test
    void aCredentialShapedUsernameIsStillRedactedOnTheWire()
    {
        view.status = new McpLauncherView.LauncherStatus(
                "3.0-test", true, "069a79f4-44e9-4726-a5be-fca90e38aaf5", 1 );
        String wire = call( "get_launcher_status", new JsonObject() ).toJson().toString();
        assertFalse( wire.contains( "069a79f4-44e9-4726-a5be-fca90e38aaf5" ), wire );
    }

    // endregion

    // region helpers

    private McpToolResult call( String toolName, JsonObject arguments )
    {
        McpTool tool = registry.find( toolName );
        assertNotNull( tool, "no such tool: " + toolName );
        try {
            return tool.invoke( new McpCallContext( "Test Client", "session-1" ), arguments );
        }
        catch ( Exception e ) {
            throw new AssertionError( "tool threw instead of returning an error result", e );
        }
    }

    private static JsonObject packArgs( String friendlyName )
    {
        JsonObject arguments = new JsonObject();
        arguments.addProperty( "friendlyName", friendlyName );
        return arguments;
    }

    private static JsonObject jsonOf( McpToolResult result )
    {
        assertFalse( result.isError(), "unexpected error: " + result.rawTextBlocks() );
        return JSONUtilities.getGson().fromJson( result.rawTextBlocks().get( 0 ), JsonObject.class );
    }

    // endregion
}
