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

package com.micatechnologies.minecraft.launcher.gui;

import com.micatechnologies.minecraft.launcher.game.modpack.GameOptionalMod;
import com.micatechnologies.minecraft.launcher.game.modpack.OptionalModResolver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The explanations the detail modal shows when a toggle switches other optional mods, and the
 * per-mod requirement and conflict notes. Keys are echoed so the assertions are locale-free.
 */
class OptionalModMessagesTest
{
    private static String echo( String key, Object... args )
    {
        StringBuilder sb = new StringBuilder( key );
        for ( Object arg : args ) {
            sb.append( '|' ).append( arg );
        }
        return sb.toString();
    }

    private static GameOptionalMod mod( String name, String local, boolean defaultOn, List< String > requires,
                                        List< String > conflicts )
    {
        return new GameOptionalMod( name, "https://example.invalid/" + local, local, null, true, false, "",
                                    defaultOn, requires, conflicts );
    }

    private final List< GameOptionalMod > mods = List.of(
            mod( "AUSM", "ausm.jar", false, List.of( "lib.jar" ), List.of( "fast.jar" ) ),
            mod( "Lib", "lib.jar", false, null, null ),
            mod( "Fast", "fast.jar", true, null, null ),
            mod( "Fast Addon", "addon.jar", true, List.of( "fast.jar" ), null ) );

    @Test
    void aToggleExplainsEveryOtherModThatSwitched()
    {
        var result = OptionalModResolver.toggle( mods, Map.of(), "ausm.jar", true );
        assertEquals( List.of( "modal.optionalMods.status.on|AUSM",
                               "modal.optionalMods.reason.requiredBy|Lib|AUSM",
                               "modal.optionalMods.reason.conflictsWith|Fast|AUSM",
                               "modal.optionalMods.reason.requiresOff|Fast Addon|Fast" ),
                      OptionalModMessages.describeToggle( result, "AUSM", true, OptionalModMessagesTest::echo ) );
    }

    @Test
    void aPlainToggleOffSaysWhenItApplies()
    {
        var result = OptionalModResolver.toggle( mods, Map.of(), "addon.jar", false );
        assertEquals( List.of( "modal.optionalMods.status.off|Fast Addon" ),
                      OptionalModMessages.describeToggle( result, "Fast Addon", false, OptionalModMessagesTest::echo ) );
    }

    @Test
    void aRefusedToggleSaysNothingChanged()
    {
        var bad = List.of( mod( "Bad", "bad.jar", false, List.of( "lib.jar" ), List.of( "lib.jar" ) ),
                           mod( "Lib", "lib.jar", false, null, null ) );
        var result = OptionalModResolver.toggle( bad, Map.of(), "bad.jar", true );
        assertEquals( List.of( "modal.optionalMods.refused.unsatisfiable|Bad" ),
                      OptionalModMessages.describeToggle( result, "Bad", true, OptionalModMessagesTest::echo ) );
    }

    @Test
    void relationsNameOtherModsByDisplayNameAndShowConflictsBothWays()
    {
        assertEquals( List.of( "modal.optionalMods.requires|Lib", "modal.optionalMods.conflicts|Fast" ),
                      OptionalModMessages.describeRelations( mods.get( 0 ), mods, OptionalModMessagesTest::echo ) );
        // Fast names nothing, but AUSM names it.
        assertEquals( List.of( "modal.optionalMods.conflicts|AUSM" ),
                      OptionalModMessages.describeRelations( mods.get( 2 ), mods, OptionalModMessagesTest::echo ) );
        assertEquals( List.of(),
                      OptionalModMessages.describeRelations( mods.get( 1 ), mods, OptionalModMessagesTest::echo ) );
    }
}
