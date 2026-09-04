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

package com.micatechnologies.minecraft.launcher.game.modpack.manifests;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ManifestRuleUtilities} — the shared rule engine that decides which
 * Mojang/Forge manifest entries apply to the machine the launcher is running on.
 *
 * <p>Why this matters: {@code evaluateRules} governs which libraries and native
 * binaries get downloaded, and {@code flattenArguments} builds part of the JVM and game
 * command line. A regression here does not throw — it silently produces a classpath
 * missing a native, or an argument list with a Windows-only flag on macOS, and the
 * failure surfaces much later as an opaque crash inside the game. Both methods are pure
 * functions over Gson trees, which makes them cheap to pin down exhaustively.</p>
 *
 * <p><b>Platform independence.</b> The rule engine consults the real runtime OS, so
 * these tests never hard-code {@code "windows"} or {@code "osx"}. They ask
 * {@link ManifestRuleUtilities#getCurrentPlatformName()} what the current platform is
 * and build both a matching and a deliberately non-matching rule from it, so the suite
 * asserts identical behaviour on all three CI platforms.</p>
 */
class ManifestRuleUtilitiesTest
{
    /** The platform name the rule engine will match against at runtime. */
    private static final String CURRENT = ManifestRuleUtilities.getCurrentPlatformName();

    /** A platform name guaranteed to differ from {@link #CURRENT}. */
    private static final String NOT_CURRENT = "windows".equals( CURRENT ) ? "linux" : "windows";

    // =========================================================================
    //  Helpers
    // =========================================================================

    /** Builds a rule object: {@code {"action": <action>}} with no OS restriction, so it
     *  matches every runtime. */
    private static JsonObject rule( String action )
    {
        JsonObject r = new JsonObject();
        r.addProperty( "action", action );
        return r;
    }

    /** Builds a rule object scoped to a single OS name. */
    private static JsonObject rule( String action, String osName )
    {
        JsonObject r = rule( action );
        JsonObject os = new JsonObject();
        os.addProperty( "name", osName );
        r.add( "os", os );
        return r;
    }

    private static JsonArray arrayOf( JsonObject... objects )
    {
        JsonArray a = new JsonArray();
        for ( JsonObject o : objects ) {
            a.add( o );
        }
        return a;
    }

    // =========================================================================
    //  evaluateRules — defaults
    // =========================================================================

    @Test
    void nullRulesAllowTheItem()
    {
        assertTrue( ManifestRuleUtilities.evaluateRules( null ),
                    "an entry with no rules array applies everywhere" );
    }

    @Test
    void emptyRulesAllowTheItem()
    {
        assertTrue( ManifestRuleUtilities.evaluateRules( new JsonArray() ),
                    "an empty rules array applies everywhere" );
    }

    @Test
    void currentPlatformNameIsReported()
    {
        assertNotNull( CURRENT, "the rule engine must be able to name the current platform" );
        assertFalse( CURRENT.isBlank(), "platform name must not be blank" );
    }

    // =========================================================================
    //  evaluateRules — unrestricted rules
    // =========================================================================

    @Test
    void unrestrictedAllowRuleAllows()
    {
        assertTrue( ManifestRuleUtilities.evaluateRules( arrayOf( rule( "allow" ) ) ) );
    }

    @Test
    void unrestrictedDisallowRuleDenies()
    {
        assertFalse( ManifestRuleUtilities.evaluateRules( arrayOf( rule( "disallow" ) ) ) );
    }

    @Test
    void missingActionDefaultsToAllow()
    {
        assertTrue( ManifestRuleUtilities.evaluateRules( arrayOf( new JsonObject() ) ),
                    "a rule with no action key is specified to default to allow" );
    }

    @Test
    void actionComparisonIsCaseInsensitive()
    {
        assertTrue( ManifestRuleUtilities.evaluateRules( arrayOf( rule( "ALLOW" ) ) ) );
    }

    // =========================================================================
    //  evaluateRules — OS scoping
    // =========================================================================

    @Test
    void ruleScopedToCurrentPlatformApplies()
    {
        assertTrue( ManifestRuleUtilities.evaluateRules( arrayOf( rule( "allow", CURRENT ) ) ) );
    }

    /**
     * A rule for a different OS is skipped entirely rather than treated as a denial.
     * Because the accumulator starts at {@code false}, skipping every rule leaves the
     * item disallowed — which is exactly how Mojang's manifests express
     * "this native is Windows-only".
     */
    @Test
    void ruleScopedToAnotherPlatformIsSkippedLeavingItemDisallowed()
    {
        assertFalse( ManifestRuleUtilities.evaluateRules( arrayOf( rule( "allow", NOT_CURRENT ) ) ) );
    }

    @Test
    void disallowRuleForAnotherPlatformDoesNotAffectThisOne()
    {
        assertTrue( ManifestRuleUtilities.evaluateRules(
                arrayOf( rule( "allow" ), rule( "disallow", NOT_CURRENT ) ) ) );
    }

    // =========================================================================
    //  evaluateRules — last matching rule wins
    // =========================================================================

    /**
     * The canonical Mojang shape: a blanket allow followed by a platform-specific
     * disallow. Order matters, and the later matching rule must win.
     */
    @Test
    void laterMatchingRuleOverridesEarlierOne()
    {
        assertFalse( ManifestRuleUtilities.evaluateRules(
                             arrayOf( rule( "allow" ), rule( "disallow", CURRENT ) ) ),
                     "a platform-specific disallow must override a preceding blanket allow" );

        assertTrue( ManifestRuleUtilities.evaluateRules(
                            arrayOf( rule( "disallow" ), rule( "allow", CURRENT ) ) ),
                    "a platform-specific allow must override a preceding blanket disallow" );
    }

    // =========================================================================
    //  evaluateRules — malformed entries
    // =========================================================================

    @Test
    void nonObjectRuleEntriesAreSkipped()
    {
        JsonArray rules = new JsonArray();
        rules.add( new JsonPrimitive( "garbage" ) );
        rules.add( rule( "allow" ) );
        assertTrue( ManifestRuleUtilities.evaluateRules( rules ),
                    "a stray primitive in the rules array must not derail evaluation" );
    }

    // =========================================================================
    //  flattenArguments
    // =========================================================================

    @Test
    void flattenNullAndEmptyYieldEmptyString()
    {
        assertEquals( "", ManifestRuleUtilities.flattenArguments( null ) );
        assertEquals( "", ManifestRuleUtilities.flattenArguments( new JsonArray() ) );
    }

    @Test
    void flattenIncludesPlainStringArguments()
    {
        JsonArray args = new JsonArray();
        args.add( "-Xmx2G" );
        args.add( "-Dfoo=bar" );
        assertEquals( "-Xmx2G -Dfoo=bar", ManifestRuleUtilities.flattenArguments( args ) );
    }

    @Test
    void flattenIncludesConditionalArgumentWhenRulesAllow()
    {
        JsonObject conditional = new JsonObject();
        conditional.add( "rules", arrayOf( rule( "allow", CURRENT ) ) );
        conditional.addProperty( "value", "-XstartOnFirstThread" );

        JsonArray args = new JsonArray();
        args.add( conditional );
        assertEquals( "-XstartOnFirstThread", ManifestRuleUtilities.flattenArguments( args ) );
    }

    @Test
    void flattenExcludesConditionalArgumentWhenRulesDeny()
    {
        JsonObject conditional = new JsonObject();
        conditional.add( "rules", arrayOf( rule( "allow", NOT_CURRENT ) ) );
        conditional.addProperty( "value", "-XotherPlatformOnly" );

        JsonArray args = new JsonArray();
        args.add( conditional );
        assertEquals( "", ManifestRuleUtilities.flattenArguments( args ),
                      "an argument gated to another platform must not reach the command line" );
    }

    @Test
    void flattenExpandsArrayValuedArguments()
    {
        JsonArray values = new JsonArray();
        values.add( "-Done=1" );
        values.add( "-Dtwo=2" );

        JsonObject conditional = new JsonObject();
        conditional.addProperty( "action", "allow" );
        conditional.add( "value", values );

        JsonArray args = new JsonArray();
        args.add( conditional );
        assertEquals( "-Done=1 -Dtwo=2", ManifestRuleUtilities.flattenArguments( args ) );
    }

    @Test
    void flattenSkipsObjectsWithNoValue()
    {
        JsonObject noValue = new JsonObject();
        noValue.add( "rules", arrayOf( rule( "allow" ) ) );

        JsonArray args = new JsonArray();
        args.add( noValue );
        assertEquals( "", ManifestRuleUtilities.flattenArguments( args ) );
    }

    // =========================================================================
    //  flattenArguments — quoting
    // =========================================================================

    /**
     * Mojang manifests contain arguments such as {@code -Dos.name=Windows 10}. Without
     * quoting these split into two tokens during command-line parsing and the JVM
     * receives a bare {@code 10} argument.
     */
    @Test
    void argumentsContainingWhitespaceAreQuoted()
    {
        JsonArray args = new JsonArray();
        args.add( "-Dos.name=Windows 10" );
        assertEquals( "\"-Dos.name=Windows 10\"", ManifestRuleUtilities.flattenArguments( args ) );
    }

    @Test
    void argumentsWithoutWhitespaceAreNotQuoted()
    {
        JsonArray args = new JsonArray();
        args.add( "-Xmx4G" );
        assertEquals( "-Xmx4G", ManifestRuleUtilities.flattenArguments( args ) );
    }

    /**
     * Placeholder-bearing arguments are left unquoted so the later substitution pass
     * can expand them cleanly; quoting them here would embed quotes mid-token.
     */
    @Test
    void argumentsContainingPlaceholdersAreLeftUnquoted()
    {
        JsonArray args = new JsonArray();
        args.add( "-Dpath=${library_directory}/foo bar" );
        String out = ManifestRuleUtilities.flattenArguments( args );
        assertFalse( out.startsWith( "\"" ),
                     "placeholder arguments must not be quoted before substitution" );
    }
}
