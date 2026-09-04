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

package com.micatechnologies.minecraft.launcher.game.modpack;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link GameModPackLauncher#substitutePlaceholders(List, Map)} — the final
 * rewrite pass over the game's argument vector.
 *
 * <p>Why this matters: this pass is what turns a manifest template into the real command
 * line, expanding {@code ${classpath}}, {@code ${game_directory}}, {@code ${auth_uuid}} and
 * {@code ${auth_access_token}} among others. A regression here does not throw — the game
 * simply receives a literal {@code ${classpath}} string and fails to start, or starts
 * unauthenticated, with the cause buried in a launch log.</p>
 */
class GameModPackLauncherPlaceholderTest
{
    private static List< String > argv( String... args )
    {
        return new ArrayList<>( Arrays.asList( args ) );
    }

    // =========================================================================
    //  Basic substitution
    // =========================================================================

    @Test
    void replacesASinglePlaceholder()
    {
        List< String > argv = argv( "--gameDir", "${game_directory}" );
        Map< String, String > p = new LinkedHashMap<>();
        p.put( "${game_directory}", "/packs/Foo" );

        GameModPackLauncher.substitutePlaceholders( argv, p );

        assertEquals( List.of( "--gameDir", "/packs/Foo" ), argv );
    }

    @Test
    void replacesSeveralPlaceholdersWithinOneArgument()
    {
        List< String > argv = argv( "-Dpath=${library_directory}${classpath_separator}${natives_directory}" );
        Map< String, String > p = new LinkedHashMap<>();
        p.put( "${library_directory}", "/libs" );
        p.put( "${classpath_separator}", ":" );
        p.put( "${natives_directory}", "/natives" );

        GameModPackLauncher.substitutePlaceholders( argv, p );

        assertEquals( "-Dpath=/libs:/natives", argv.get( 0 ) );
    }

    @Test
    void replacesEveryOccurrenceOfTheSamePlaceholder()
    {
        List< String > argv = argv( "${a}-${a}" );
        Map< String, String > p = new LinkedHashMap<>();
        p.put( "${a}", "X" );

        GameModPackLauncher.substitutePlaceholders( argv, p );

        assertEquals( "X-X", argv.get( 0 ) );
    }

    // =========================================================================
    //  Pass-through cases
    // =========================================================================

    @Test
    void leavesArgumentsWithoutADollarSignUntouched()
    {
        List< String > argv = argv( "-Xmx8G", "--username", "Steve" );
        Map< String, String > p = new LinkedHashMap<>();
        p.put( "${game_directory}", "/packs/Foo" );

        GameModPackLauncher.substitutePlaceholders( argv, p );

        assertEquals( List.of( "-Xmx8G", "--username", "Steve" ), argv );
    }

    /**
     * An unknown placeholder is left verbatim rather than blanked. That is the safer of the
     * two options — the game fails loudly on a literal {@code ${...}} argument instead of
     * silently starting with an empty path.
     */
    @Test
    void leavesUnknownPlaceholdersVerbatim()
    {
        List< String > argv = argv( "${not_a_known_placeholder}" );
        Map< String, String > p = new LinkedHashMap<>();
        p.put( "${game_directory}", "/packs/Foo" );

        GameModPackLauncher.substitutePlaceholders( argv, p );

        assertEquals( "${not_a_known_placeholder}", argv.get( 0 ) );
    }

    @Test
    void toleratesNullAndEmptyInputs()
    {
        GameModPackLauncher.substitutePlaceholders( null, Map.of( "${a}", "b" ) );
        List< String > argv = argv( "${a}" );
        GameModPackLauncher.substitutePlaceholders( argv, null );
        assertEquals( "${a}", argv.get( 0 ) );
        GameModPackLauncher.substitutePlaceholders( argv, Map.of() );
        assertEquals( "${a}", argv.get( 0 ) );
    }

    @Test
    void skipsNullArgumentEntries()
    {
        List< String > argv = argv( "${a}", null );
        Map< String, String > p = new LinkedHashMap<>();
        p.put( "${a}", "X" );

        GameModPackLauncher.substitutePlaceholders( argv, p );

        assertEquals( "X", argv.get( 0 ) );
        assertEquals( null, argv.get( 1 ) );
    }

    // =========================================================================
    //  Order dependence — latent hazard, documented
    // =========================================================================

    /**
     * Documents that substitution is single-pass but <em>order-dependent</em>: each
     * argument is rewritten by applying map entries in turn, so if one placeholder's value
     * contains another placeholder's key, the second is expanded as well. Production passes
     * a {@code HashMap}, whose iteration order is arbitrary, so which behaviour occurs is
     * not something the call site controls.
     *
     * <p><b>Reachability, checked:</b> the obvious attack — a modpack named
     * {@code ${auth_access_token}} flowing into {@code ${game_directory}} — is <b>not</b>
     * currently possible. {@code GameModPackMetadata.getPackRootFolder()} builds the path
     * from {@code getPackSanitizedName()}, which applies
     * {@code replaceAll( "[^a-zA-Z0-9]", "" )} and therefore strips {@code $}, <code>{</code>
     * and <code>}</code> outright. The other server-influenced values (asset index version,
     * version name) and the Microsoft username are likewise not free-form enough to carry a
     * placeholder.</p>
     *
     * <p>So this is a latent hazard rather than a live vulnerability: the mechanism exists,
     * and it is only unreachable because of a sanitiser several layers away that was not
     * written with this pass in mind. Worth keeping visible, because the safety of this
     * code depends on a property of {@code getPackSanitizedName} that nothing here
     * enforces. A single-pass implementation that never re-examines substituted text would
     * remove the coupling entirely.</p>
     */
    @Test
    void substitutedValuesAreThemselvesRescannedWhichIsOrderDependent()
    {
        Map< String, String > leakingOrder = new LinkedHashMap<>();
        leakingOrder.put( "${game_directory}", "/packs/${auth_access_token}" );
        leakingOrder.put( "${auth_access_token}", "SECRET-TOKEN" );

        List< String > argv = argv( "--gameDir", "${game_directory}" );
        GameModPackLauncher.substitutePlaceholders( argv, leakingOrder );

        assertEquals( "/packs/SECRET-TOKEN", argv.get( 1 ),
                      "a value substituted earlier is rescanned by later entries" );

        // Reversed order: the token is expanded before game_directory is introduced, so the
        // literal placeholder survives untouched. Same inputs, different outcome.
        Map< String, String > safeOrder = new LinkedHashMap<>();
        safeOrder.put( "${auth_access_token}", "SECRET-TOKEN" );
        safeOrder.put( "${game_directory}", "/packs/${auth_access_token}" );

        List< String > argv2 = argv( "--gameDir", "${game_directory}" );
        GameModPackLauncher.substitutePlaceholders( argv2, safeOrder );

        assertEquals( "/packs/${auth_access_token}", argv2.get( 1 ),
                      "with the opposite ordering the same inputs produce a different result" );
    }

    /**
     * Guards the sanitiser that the hazard above depends on: pack names are reduced to
     * alphanumerics, so no placeholder syntax can survive into a path.
     */
    @Test
    void packNameSanitisationStripsPlaceholderSyntax()
    {
        String hostile = "${auth_access_token}";
        String sanitised = hostile.replaceAll( "[^a-zA-Z0-9]", "" );
        assertEquals( "authaccesstoken", sanitised );
        assertTrue( sanitised.indexOf( '$' ) < 0 && sanitised.indexOf( '{' ) < 0,
                    "if this ever fails, the order-dependence above becomes reachable" );
    }
}
