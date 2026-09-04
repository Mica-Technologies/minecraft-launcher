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

package com.micatechnologies.minecraft.launcher.mcp.approval;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link McpToolPolicyStore} — the durable per-tool approval choices.
 *
 * <p>The property this class exists to guarantee: <b>an unreadable stored value must resolve
 * to "unset", never to a policy</b>. Unset sends the tool to its risk-class default, which is
 * {@code ASK} for anything that can change or run something. The opposite convention would
 * mean a truncated write, a hand-edited config, or a policy name from a newer build could
 * silently grant standing permission to delete modpacks.</p>
 *
 * <p>So the malformed-input tests here are not defensive boilerplate — each one is a distinct
 * way a config file goes wrong in the field, and each must land on "ask the user".</p>
 */
class McpToolPolicyStoreTest
{
    private StubBacking backing;
    private McpToolPolicyStore store;

    /** Hand-rolled backing store, per this repo's no-mocking-framework convention. */
    private static final class StubBacking implements McpToolPolicyStore.Backing
    {
        String json = "";
        RuntimeException readFailure;
        RuntimeException writeFailure;
        int writeCount;

        @Override
        public String read()
        {
            if ( readFailure != null ) {
                throw readFailure;
            }
            return json;
        }

        @Override
        public void write( String value )
        {
            writeCount++;
            if ( writeFailure != null ) {
                throw writeFailure;
            }
            json = value;
        }
    }

    @BeforeEach
    void setUp()
    {
        backing = new StubBacking();
        store = new McpToolPolicyStore( backing );
    }

    // region round trip

    @Test
    void aBackingStoreIsRequired()
    {
        assertThrows( IllegalArgumentException.class, () -> new McpToolPolicyStore( null ) );
    }

    @Test
    void aPolicyRoundTrips()
    {
        store.setPolicy( "list_modpacks", McpApprovalPolicy.ALWAYS_ALLOW );
        assertEquals( McpApprovalPolicy.ALWAYS_ALLOW, store.policyFor( "list_modpacks" ) );
    }

    @Test
    void anUnsetToolHasNoPolicy()
    {
        assertNull( store.policyFor( "never_configured" ) );
    }

    @Test
    void aPolicyCanBeChanged()
    {
        store.setPolicy( "install_modpack", McpApprovalPolicy.ALWAYS_ALLOW );
        store.setPolicy( "install_modpack", McpApprovalPolicy.DISABLED );
        assertEquals( McpApprovalPolicy.DISABLED, store.policyFor( "install_modpack" ) );
    }

    @Test
    void aNullPolicyClearsBackToTheDefault()
    {
        store.setPolicy( "install_modpack", McpApprovalPolicy.ALWAYS_ALLOW );
        store.setPolicy( "install_modpack", null );
        assertNull( store.policyFor( "install_modpack" ) );
    }

    @Test
    void severalToolsAreKeptIndependently()
    {
        store.setPolicy( "list_modpacks", McpApprovalPolicy.ALWAYS_ALLOW );
        store.setPolicy( "uninstall_modpack", McpApprovalPolicy.DISABLED );

        assertEquals( McpApprovalPolicy.ALWAYS_ALLOW, store.policyFor( "list_modpacks" ) );
        assertEquals( McpApprovalPolicy.DISABLED, store.policyFor( "uninstall_modpack" ) );
        assertEquals( 2, store.all().size() );
    }

    @Test
    void clearingRemovesEveryPolicy()
    {
        store.setPolicy( "list_modpacks", McpApprovalPolicy.ALWAYS_ALLOW );
        store.setPolicy( "uninstall_modpack", McpApprovalPolicy.DISABLED );
        store.clear();
        assertTrue( store.all().isEmpty() );
        assertNull( store.policyFor( "list_modpacks" ) );
    }

    @Test
    void toolNamesAreMatchedCaseInsensitivelyAndTrimmed()
    {
        store.setPolicy( "  List_Modpacks  ", McpApprovalPolicy.ALWAYS_ALLOW );
        assertEquals( McpApprovalPolicy.ALWAYS_ALLOW, store.policyFor( "list_modpacks" ) );
        assertEquals( McpApprovalPolicy.ALWAYS_ALLOW, store.policyFor( "LIST_MODPACKS" ) );
    }

    @Test
    void aBlankToolNameIsIgnoredRatherThanStored()
    {
        store.setPolicy( "   ", McpApprovalPolicy.ALWAYS_ALLOW );
        store.setPolicy( null, McpApprovalPolicy.ALWAYS_ALLOW );
        assertTrue( store.all().isEmpty() );
        assertNull( store.policyFor( null ) );
    }

    /** Stable ordering keeps the config file diff-friendly across edits. */
    @Test
    void policiesAreStoredInToolNameOrder()
    {
        store.setPolicy( "zzz_tool", McpApprovalPolicy.ASK );
        store.setPolicy( "aaa_tool", McpApprovalPolicy.ASK );
        assertTrue( backing.json.indexOf( "aaa_tool" ) < backing.json.indexOf( "zzz_tool" ),
                    backing.json );
    }

    // endregion

    // region unreadable stored state must never grant permission

    @Test
    void malformedJsonReadsAsNoPolicy()
    {
        for ( String hostile : new String[]{ "not json", "{", "[]", "null", "\"a string\"", "42" } ) {
            backing.json = hostile;
            assertNull( store.policyFor( "install_modpack" ), "content: " + hostile );
            assertTrue( store.all().isEmpty(), "content: " + hostile );
        }
    }

    @Test
    void anEmptyOrMissingStoreReadsAsNoPolicy()
    {
        backing.json = "";
        assertNull( store.policyFor( "install_modpack" ) );
        backing.json = null;
        assertNull( store.policyFor( "install_modpack" ) );
    }

    /**
     * A policy name this build does not know — from a newer launcher, or a typo in a
     * hand-edited config — must not be guessed at. Unset is the safe reading.
     */
    @Test
    void anUnrecognisedPolicyNameReadsAsNoPolicy()
    {
        backing.json = "{\"install_modpack\":\"ALLOW_EVERYTHING\"}";
        assertNull( store.policyFor( "install_modpack" ) );
    }

    @Test
    void aWrongJsonTypeForAPolicyReadsAsNoPolicy()
    {
        for ( String hostile : new String[]{ "{\"install_modpack\":{\"nested\":1}}",
                                             "{\"install_modpack\":[\"ALWAYS_ALLOW\"]}",
                                             "{\"install_modpack\":null}" } ) {
            backing.json = hostile;
            assertNull( store.policyFor( "install_modpack" ), "content: " + hostile );
        }
    }

    /**
     * A partially corrupt file must not lose the entries that are still valid, but must also
     * not resurrect the broken one as anything.
     */
    @Test
    void oneBadEntryDoesNotDiscardTheGoodOnes()
    {
        backing.json = "{\"install_modpack\":\"NONSENSE\",\"list_modpacks\":\"ALWAYS_ALLOW\"}";
        assertNull( store.policyFor( "install_modpack" ) );
        assertEquals( McpApprovalPolicy.ALWAYS_ALLOW, store.policyFor( "list_modpacks" ) );
    }

    @Test
    void aBackingStoreThatThrowsOnReadYieldsNoPolicy()
    {
        backing.readFailure = new IllegalStateException( "config unavailable" );
        assertNull( store.policyFor( "install_modpack" ) );
        assertTrue( store.all().isEmpty() );
    }

    /**
     * A failed write must not throw out of a Settings toggle. The stored state is simply
     * unchanged, which the next read reports honestly.
     */
    @Test
    void aBackingStoreThatThrowsOnWriteDoesNotPropagate()
    {
        backing.writeFailure = new IllegalStateException( "disk full" );
        store.setPolicy( "install_modpack", McpApprovalPolicy.ALWAYS_ALLOW );
        assertEquals( 1, backing.writeCount );
        assertNull( store.policyFor( "install_modpack" ), "the write did not land, so nothing is set" );
    }

    /**
     * The end-to-end statement of the safety property, run against every hostile input at
     * once: nothing unreadable may ever resolve to a policy that would let a call through.
     */
    @Test
    void noHostileInputEverYieldsAPermissivePolicy()
    {
        for ( String hostile : new String[]{ "not json", "{", "[]", "null", "42",
                                             "{\"install_modpack\":\"ALLOW_EVERYTHING\"}",
                                             "{\"install_modpack\":true}",
                                             "{\"install_modpack\":{\"policy\":\"ALWAYS_ALLOW\"}}",
                                             "{\"\":\"ALWAYS_ALLOW\"}" } ) {
            backing.json = hostile;
            for ( Map.Entry< String, McpApprovalPolicy > entry : store.all().entrySet() ) {
                assertTrue( entry.getValue() != McpApprovalPolicy.ALWAYS_ALLOW,
                            "hostile input produced a permissive policy: " + hostile );
            }
            assertNull( store.policyFor( "install_modpack" ), "content: " + hostile );
        }
    }

    // endregion
}
