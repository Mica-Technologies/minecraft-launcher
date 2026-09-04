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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the MCP tools that change state — install, create, fork, edit, uninstall, launch
 * and stop.
 *
 * <p>These are the first tools that can alter a user's machine, so the tests are weighted
 * towards the gates rather than the happy paths. Three properties carry most of the value.</p>
 *
 * <p><b>Risk classes must be right.</b> They are what decides whether a call prompts, and a
 * tool mislabelled {@code READ_ONLY} would run unattended under the auto-approve toggle. Every
 * tool here is asserted against its intended class.</p>
 *
 * <p><b>Content gates run before consent.</b> A hostile install URL, a traversing mod path, or
 * a request to delete a pack whose game is running must be refused by
 * {@code validateBeforeApproval} — which the dispatcher calls <em>ahead</em> of the approval
 * engine. Asking a user to approve something the launcher already knows is wrong is how a
 * consent dialog turns into noise.</p>
 *
 * <p><b>A refused call must not reach the action.</b> Asserted by the stub recording what it
 * was asked to do, rather than by inspecting the returned message.</p>
 */
class MutatingToolsTest
{
    private StubView view;
    private StubActions actions;
    private McpToolRegistry registry;

    /** Hand-rolled read-only view, per this repo's no-mocking-framework convention. */
    private static final class StubView implements McpLauncherView
    {
        private final List< PackSummary > packs = new ArrayList<>();
        private PackFootprint footprint;
        private LauncherStatus status = new LauncherStatus( "3.0-test", true, "Steve", 0 );

        @Override
        public List< PackSummary > packs() { return packs; }

        @Override
        public PackSummary pack( String friendlyName )
        {
            return packs.stream().filter( p -> p.friendlyName().equals( friendlyName ) )
                    .findFirst().orElse( null );
        }

        @Override
        public String manifestOf( String friendlyName ) { return null; }

        @Override
        public CrashInfo latestCrashOf( String friendlyName ) { return null; }

        @Override
        public PackFootprint footprintOf( String friendlyName ) { return footprint; }

        @Override
        public LauncherStatus status() { return status; }
    }

    /** Hand-rolled actions that record what they were asked to do rather than doing it. */
    private static final class StubActions implements McpLauncherActions
    {
        private final List< String > performed = new ArrayList<>();
        private boolean gameRunning;
        private Outcome nextOutcome = Outcome.ok( "done" );

        @Override
        public Outcome installFromUrl( String url )
        {
            performed.add( "install:" + url );
            return nextOutcome;
        }

        @Override
        public Outcome uninstall( String friendlyName )
        {
            performed.add( "uninstall:" + friendlyName );
            return nextOutcome;
        }

        @Override
        public Outcome launch( String friendlyName )
        {
            performed.add( "launch:" + friendlyName );
            return nextOutcome;
        }

        @Override
        public Outcome stopGame()
        {
            performed.add( "stop" );
            return nextOutcome;
        }

        @Override
        public boolean isGameRunning() { return gameRunning; }

        @Override
        public Outcome createPack( String name, String modLoader, String modLoaderUrl )
        {
            performed.add( "create:" + name + ":" + modLoader + ":" + modLoaderUrl );
            return nextOutcome;
        }

        @Override
        public Outcome forkPack( String sourceFriendlyName, String newName )
        {
            performed.add( "fork:" + sourceFriendlyName + ":" + newName );
            return nextOutcome;
        }

        @Override
        public Outcome addMod( String friendlyName, String modName, String remoteUrl, String localPath )
        {
            performed.add( "addMod:" + friendlyName + ":" + localPath );
            return nextOutcome;
        }

        @Override
        public Outcome removeMod( String friendlyName, String identifier )
        {
            performed.add( "removeMod:" + friendlyName + ":" + identifier );
            return nextOutcome;
        }
    }

    @BeforeEach
    void setUp()
    {
        view = new StubView();
        actions = new StubActions();
        registry = new McpToolRegistry();
        MutatingTools.registerAll( registry, view, actions );
        view.packs.add( new McpLauncherView.PackSummary( "Installed Pack", "1.0", "forge", true, false ) );
        view.packs.add( new McpLauncherView.PackSummary( "Not Installed", "1.0", "forge", false, false ) );
    }

    // region registration and risk classes

    @Test
    void everyMutatingToolRegisters()
    {
        assertEquals( MutatingTools.toolNames().size(), registry.size() );
        for ( String name : MutatingTools.toolNames() ) {
            assertNotNull( registry.find( name ), name + " should be registered" );
        }
    }

    /**
     * The classification that decides whether a call prompts. A tool mislabelled
     * {@code READ_ONLY} would run unattended under the auto-approve toggle, so each of these
     * is pinned deliberately rather than inferred.
     */
    @Test
    void eachToolCarriesItsIntendedRiskClass()
    {
        assertEquals( McpRiskClass.MUTATING, registry.find( "install_modpack" ).riskClass() );
        assertEquals( McpRiskClass.MUTATING, registry.find( "create_modpack" ).riskClass() );
        assertEquals( McpRiskClass.MUTATING, registry.find( "fork_modpack" ).riskClass() );
        assertEquals( McpRiskClass.MUTATING, registry.find( "add_mod_to_modpack" ).riskClass() );
        assertEquals( McpRiskClass.MUTATING, registry.find( "remove_mod_from_modpack" ).riskClass() );
        assertEquals( McpRiskClass.DESTRUCTIVE, registry.find( "uninstall_modpack" ).riskClass() );
        assertEquals( McpRiskClass.EXECUTE, registry.find( "launch_modpack" ).riskClass() );
        assertEquals( McpRiskClass.EXECUTE, registry.find( "stop_game" ).riskClass() );
    }

    /** None of these may ever be read-only — that is the whole reason they are gated. */
    @Test
    void noMutatingToolIsMarkedReadOnly()
    {
        for ( McpTool tool : registry.all() ) {
            assertTrue( tool.riskClass().mutatesState(), tool.name() + " must not be read-only" );
        }
    }

    @Test
    void everyToolDescribesWhatItChanges()
    {
        for ( McpTool tool : registry.all() ) {
            assertTrue( tool.description().length() > 40, tool.name() + " needs a real description" );
            assertEquals( "object", tool.inputSchema().get( "type" ).getAsString() );
        }
    }

    @Test
    void registrationRequiresEveryCollaborator()
    {
        assertThrows( IllegalArgumentException.class,
                      () -> MutatingTools.registerAll( null, view, actions ) );
        assertThrows( IllegalArgumentException.class,
                      () -> MutatingTools.registerAll( new McpToolRegistry(), null, actions ) );
        assertThrows( IllegalArgumentException.class,
                      () -> MutatingTools.registerAll( new McpToolRegistry(), view, null ) );
    }

    // endregion

    // region install_modpack — the install-source gate

    @Test
    void aWellFormedHttpsUrlPassesTheGateAndInstalls()
    {
        assertNull( validate( "install_modpack", args( "url", "https://example.test/pack.json" ) ) );
        invoke( "install_modpack", args( "url", "https://example.test/pack.json" ) );
        assertEquals( List.of( "install:https://example.test/pack.json" ), actions.performed );
    }

    /**
     * The gate that matters: a URL the launcher already refuses must never become a consent
     * prompt. These are the shapes {@code classifyInstallUrl} rejects outright.
     */
    @Test
    void aRefusedInstallUrlIsRejectedBeforeTheUserIsAsked()
    {
        for ( String hostile : new String[]{ "http://example.test/pack.json",
                                             "file:///etc/passwd",
                                             "ftp://example.test/pack.json",
                                             "not a url",
                                             "" } ) {
            assertNotNull( validate( "install_modpack", args( "url", hostile ) ),
                           "should have been refused: " + hostile );
        }
        assertTrue( actions.performed.isEmpty(), "nothing may be installed: " + actions.performed );
    }

    /**
     * An untrusted-but-well-formed host is exactly what the consent dialog is for, so it must
     * NOT be rejected here — refusing it would make the tool useless for any pack not on the
     * allowlist.
     */
    @Test
    void anUntrustedButWellFormedHostReachesTheConsentGate()
    {
        assertNull( validate( "install_modpack",
                              args( "url", "https://some-random-host.test/pack.json" ) ),
                    "an unknown https host should be asked about, not refused outright" );
    }

    // endregion

    // region add_mod_to_modpack — URL and path containment

    @Test
    void aValidModAdditionPasses()
    {
        assertNull( validate( "add_mod_to_modpack", modArgs( "mods/jei.jar" ) ) );
    }

    @Test
    void aModUrlFacesTheSameInstallSourceGate()
    {
        JsonObject arguments = modArgs( "mods/jei.jar" );
        arguments.addProperty( "remoteUrl", "http://example.test/jei.jar" );
        assertNotNull( validate( "add_mod_to_modpack", arguments ) );
    }

    /**
     * The path is joined to the pack root before anything is downloaded to it, so this is the
     * same containment question as the archive extractors — and it is answered before consent,
     * because a traversal attempt is not something to ask the user to adjudicate.
     */
    @Test
    void aTraversingModPathIsRefusedBeforeTheUserIsAsked()
    {
        for ( String hostile : new String[]{ "../outside.jar", "mods/../../outside.jar",
                                             "/etc/passwd", "\\\\windows\\\\system32",
                                             "C:\\\\evil.jar", "mods/..\\\\..\\\\out.jar" } ) {
            assertNotNull( validate( "add_mod_to_modpack", modArgs( hostile ) ),
                           "should have been refused: " + hostile );
        }
        assertTrue( actions.performed.isEmpty() );
    }

    @Test
    void aModPathWithControlCharactersIsRefused()
    {
        assertNotNull( MutatingTools.rejectUnsafeLocalPath( "mods/evil\u0000.jar" ) );
        assertNotNull( MutatingTools.rejectUnsafeLocalPath( "mods/evil\nname.jar" ) );
    }

    /** A dot inside a filename is fine — only the traversal segment is refused. */
    @Test
    void ordinaryModPathsAreAccepted()
    {
        for ( String fine : new String[]{ "mods/jei.jar", "mods/sub/dir/mod.jar",
                                          "mods/mod.1.2.3.jar", "mods/.hidden.jar" } ) {
            assertNull( MutatingTools.rejectUnsafeLocalPath( fine ), fine );
        }
    }

    @Test
    void addingToAnUnknownPackIsRefused()
    {
        JsonObject arguments = modArgs( "mods/jei.jar" );
        arguments.addProperty( "friendlyName", "Ghost Pack" );
        assertNotNull( validate( "add_mod_to_modpack", arguments ) );
    }

    // endregion

    // region create and fork — name collisions

    @Test
    void creatingAPackThatAlreadyExistsIsRefused()
    {
        assertNotNull( validate( "create_modpack", args( "name", "Installed Pack" ) ) );
        assertNull( validate( "create_modpack", args( "name", "Brand New" ) ) );
    }

    @Test
    void optionalCreateArgumentsAreForwardedAsNullWhenAbsent()
    {
        invoke( "create_modpack", args( "name", "Brand New" ) );
        assertEquals( List.of( "create:Brand New:null:null" ), actions.performed );
    }

    @Test
    void forkingRequiresAnExistingSourceAndAFreeName()
    {
        JsonObject good = new JsonObject();
        good.addProperty( "friendlyName", "Installed Pack" );
        good.addProperty( "newName", "My Fork" );
        assertNull( validate( "fork_modpack", good ) );

        JsonObject unknownSource = new JsonObject();
        unknownSource.addProperty( "friendlyName", "Ghost" );
        unknownSource.addProperty( "newName", "My Fork" );
        assertNotNull( validate( "fork_modpack", unknownSource ) );

        JsonObject takenName = new JsonObject();
        takenName.addProperty( "friendlyName", "Installed Pack" );
        takenName.addProperty( "newName", "Not Installed" );
        assertNotNull( validate( "fork_modpack", takenName ) );
    }

    // endregion

    // region uninstall — the destructive gate

    @Test
    void uninstallingAnExistingPackPasses()
    {
        assertNull( validate( "uninstall_modpack", args( "friendlyName", "Installed Pack" ) ) );
    }

    /**
     * Deleting files out from under a live game corrupts whatever it writes next, and the user
     * would blame the game rather than a tool call they approved minutes earlier.
     */
    @Test
    void uninstallingWhileAGameIsRunningIsRefused()
    {
        actions.gameRunning = true;
        assertNotNull( validate( "uninstall_modpack", args( "friendlyName", "Installed Pack" ) ) );
        assertTrue( actions.performed.isEmpty() );
    }

    @Test
    void uninstallingAnUnknownPackIsRefused()
    {
        assertNotNull( validate( "uninstall_modpack", args( "friendlyName", "Ghost" ) ) );
    }

    // endregion

    // region what the destructive prompt tells the user

    /**
     * Plan section 5.4: a destructive prompt must say what would be <b>lost</b>, not only what
     * would be run. "Delete All the Mods 9?" and "Delete All the Mods 9 — 4.2 GB, 3 worlds?"
     * are different questions, and only the second one can be answered responsibly.
     */
    @Test
    void theUninstallPromptNamesTheSizeAndWorldsAtStake()
    {
        view.footprint = new McpLauncherView.PackFootprint( 4_509_715_660L, 3, false );
        String detail = registry.find( "uninstall_modpack" )
                .consentDetail( args( "friendlyName", "Installed Pack" ) );

        assertNotNull( detail );
        assertTrue( detail.contains( "4.2 GB" ), detail );
        assertTrue( detail.contains( "3 saved worlds" ), detail );
        assertTrue( detail.contains( "cannot be undone" ), detail );
    }

    /** Worlds are the unrecoverable part, so a pack with none must not imply otherwise. */
    @Test
    void aPackWithNoWorldsDoesNotClaimAny()
    {
        view.footprint = new McpLauncherView.PackFootprint( 1_048_576L, 0, false );
        String detail = registry.find( "uninstall_modpack" )
                .consentDetail( args( "friendlyName", "Installed Pack" ) );
        assertFalse( detail.contains( "world" ), detail );
    }

    @Test
    void oneWorldIsDescribedInTheSingular()
    {
        view.footprint = new McpLauncherView.PackFootprint( 1_048_576L, 1, false );
        assertTrue( registry.find( "uninstall_modpack" )
                            .consentDetail( args( "friendlyName", "Installed Pack" ) )
                            .contains( "1 saved world." ) );
    }

    /**
     * A truncated measurement must say "at least" rather than quietly under-reporting. A user
     * told "1 GB" about a 40 GB pack was misinformed by the prompt meant to inform them.
     */
    @Test
    void aTruncatedMeasurementIsReportedAsALowerBound()
    {
        view.footprint = new McpLauncherView.PackFootprint( 1_073_741_824L, 2, true );
        assertTrue( registry.find( "uninstall_modpack" )
                            .consentDetail( args( "friendlyName", "Installed Pack" ) )
                            .contains( "at least" ) );
    }

    /** A measurement that failed still yields a prompt, just a less specific one. */
    @Test
    void anUnmeasurablePackStillGetsAWarning()
    {
        view.footprint = null;
        String detail = registry.find( "uninstall_modpack" )
                .consentDetail( args( "friendlyName", "Installed Pack" ) );
        assertNotNull( detail );
        assertTrue( detail.contains( "permanently deletes" ), detail );
    }

    /** Only the destructive tool volunteers extra detail; the rest have nothing to add. */
    @Test
    void nonDestructiveToolsAddNoConsentDetail()
    {
        for ( McpTool tool : registry.all() ) {
            if ( tool.name().equals( "uninstall_modpack" ) ) {
                continue;
            }
            assertNull( tool.consentDetail( args( "friendlyName", "Installed Pack" ) ),
                        tool.name() + " should not add consent detail" );
        }
    }

    @Test
    void sizesAreRenderedTheWayAPersonReadsThem()
    {
        assertEquals( "512 bytes", MutatingTools.describeSize( 512L ) );
        assertEquals( "1.0 KB", MutatingTools.describeSize( 1024L ) );
        assertEquals( "1.0 MB", MutatingTools.describeSize( 1024L * 1024 ) );
        assertEquals( "4.2 GB", MutatingTools.describeSize( 4_509_715_660L ) );
        assertEquals( "512 MB", MutatingTools.describeSize( 512L * 1024 * 1024 ) );
        assertEquals( "0 bytes", MutatingTools.describeSize( 0L ) );
    }

    // endregion

    // region launch and stop

    @Test
    void launchingAnInstalledPackWhileSignedInPasses()
    {
        assertNull( validate( "launch_modpack", args( "friendlyName", "Installed Pack" ) ) );
    }

    @Test
    void launchingAPackThatIsNotInstalledIsRefused()
    {
        assertNotNull( validate( "launch_modpack", args( "friendlyName", "Not Installed" ) ) );
    }

    /** Launching without an account would fail deep in the launch pipeline; refuse early. */
    @Test
    void launchingWhileSignedOutIsRefused()
    {
        view.status = new McpLauncherView.LauncherStatus( "3.0-test", false, "", 1 );
        assertNotNull( validate( "launch_modpack", args( "friendlyName", "Installed Pack" ) ) );
    }

    @Test
    void launchingASecondGameIsRefused()
    {
        actions.gameRunning = true;
        assertNotNull( validate( "launch_modpack", args( "friendlyName", "Installed Pack" ) ) );
        assertTrue( actions.performed.isEmpty() );
    }

    @Test
    void stoppingIsRefusedWhenNothingIsRunning()
    {
        assertNotNull( validate( "stop_game", new JsonObject() ) );
        actions.gameRunning = true;
        assertNull( validate( "stop_game", new JsonObject() ) );
    }

    @Test
    void stoppingTerminatesTheGame()
    {
        actions.gameRunning = true;
        invoke( "stop_game", new JsonObject() );
        assertEquals( List.of( "stop" ), actions.performed );
    }

    // endregion

    // region outcome mapping

    @Test
    void aFailedActionBecomesAToolError()
    {
        actions.nextOutcome = McpLauncherActions.Outcome.failed( "the download timed out" );
        McpToolResult result = invoke( "install_modpack", args( "url", "https://example.test/p.json" ) );
        assertTrue( result.isError() );
        assertEquals( "the download timed out", result.rawTextBlocks().get( 0 ) );
    }

    @Test
    void aSuccessfulActionBecomesAToolSuccess()
    {
        actions.nextOutcome = McpLauncherActions.Outcome.ok( "installed Foo" );
        McpToolResult result = invoke( "install_modpack", args( "url", "https://example.test/p.json" ) );
        assertFalse( result.isError() );
        assertEquals( "installed Foo", result.rawTextBlocks().get( 0 ) );
    }

    /** An implementation returning nothing must not be read as success. */
    @Test
    void anAbsentOutcomeBecomesAnError()
    {
        actions.nextOutcome = null;
        assertTrue( invoke( "install_modpack", args( "url", "https://example.test/p.json" ) ).isError() );
    }

    // endregion

    // region helpers

    private String validate( String toolName, JsonObject arguments )
    {
        return registry.find( toolName ).validateBeforeApproval( arguments );
    }

    private McpToolResult invoke( String toolName, JsonObject arguments )
    {
        try {
            return registry.find( toolName )
                    .invoke( new McpCallContext( "Test Client", "session-1" ), arguments );
        }
        catch ( Exception e ) {
            throw new AssertionError( "tool threw instead of returning a result", e );
        }
    }

    private static JsonObject args( String key, String value )
    {
        JsonObject arguments = new JsonObject();
        arguments.addProperty( key, value );
        return arguments;
    }

    private static JsonObject modArgs( String localPath )
    {
        JsonObject arguments = new JsonObject();
        arguments.addProperty( "friendlyName", "Installed Pack" );
        arguments.addProperty( "modName", "JEI" );
        arguments.addProperty( "remoteUrl", "https://example.test/jei.jar" );
        arguments.addProperty( "localPath", localPath );
        return arguments;
    }

    // endregion
}
