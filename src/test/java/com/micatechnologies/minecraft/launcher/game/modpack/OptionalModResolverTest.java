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

import com.micatechnologies.minecraft.launcher.utilities.objects.GameMode;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rules for which optional mods are on and what a toggle changes: defaults, stored choices,
 * transitive {@code requires}, symmetric {@code conflictsWith}, the "earlier entry wins" tie
 * break, and the server's defaults-only rule.
 */
class OptionalModResolverTest
{
    private static GameOptionalMod mod( String local, boolean defaultOn, List< String > requires,
                                        List< String > conflicts )
    {
        return new GameOptionalMod( local.replace( ".jar", "" ), "https://example.invalid/" + local, local, null,
                                    true, false, "desc " + local, defaultOn, requires, conflicts );
    }

    private static GameOptionalMod plain( String local, boolean defaultOn )
    {
        return mod( local, defaultOn, null, null );
    }

    @Test
    void absentChoicesFollowDefaults()
    {
        List< GameOptionalMod > mods = List.of( plain( "a.jar", false ), plain( "b.jar", true ) );
        assertEquals( Set.of( "b.jar" ), OptionalModResolver.resolveEnabled( mods, null ) );
    }

    @Test
    void storedChoicesOverrideDefaultsInAnySpelling()
    {
        List< GameOptionalMod > mods = List.of( plain( "AUSM.jar", false ), plain( "b.jar", true ) );
        Map< String, Boolean > choices = new HashMap<>();
        choices.put( "mods/ausm.JAR", true );
        choices.put( "B.jar", false );
        assertEquals( Set.of( "ausm.jar" ), OptionalModResolver.resolveEnabled( mods, choices ) );
    }

    @Test
    void requiresAreTurnedOnTransitively()
    {
        List< GameOptionalMod > mods = List.of( mod( "a.jar", true, List.of( "b.jar" ), null ),
                                                mod( "b.jar", false, List.of( "c.jar" ), null ),
                                                plain( "c.jar", false ) );
        assertEquals( Set.of( "a.jar", "b.jar", "c.jar" ), OptionalModResolver.resolveEnabled( mods, Map.of() ) );
    }

    @Test
    void unknownRequiresAndConflictsAreIgnored()
    {
        List< GameOptionalMod > mods = List.of( mod( "a.jar", true, List.of( "ghost.jar" ),
                                                     List.of( "phantom.jar" ) ) );
        assertEquals( Set.of( "a.jar" ), OptionalModResolver.resolveEnabled( mods, Map.of() ) );
    }

    @Test
    void conflictingDefaultsLetTheEarlierEntryWinInEitherDirection()
    {
        // b names the conflict, a does not: conflicts are symmetric, and a comes first.
        List< GameOptionalMod > mods = List.of( plain( "a.jar", true ),
                                                mod( "b.jar", true, null, List.of( "a.jar" ) ) );
        assertEquals( Set.of( "a.jar" ), OptionalModResolver.resolveEnabled( mods, Map.of() ) );
    }

    @Test
    void aGroupThatConflictsWithItselfStaysOff()
    {
        List< GameOptionalMod > mods = List.of( mod( "a.jar", true, List.of( "b.jar" ), List.of( "b.jar" ) ),
                                                plain( "b.jar", false ) );
        assertTrue( OptionalModResolver.resolveEnabled( mods, Map.of() ).isEmpty() );
    }

    @Test
    void cyclicRequiresTerminate()
    {
        List< GameOptionalMod > mods = List.of( mod( "a.jar", true, List.of( "b.jar" ), null ),
                                                mod( "b.jar", false, List.of( "a.jar" ), null ) );
        assertEquals( Set.of( "a.jar", "b.jar" ), OptionalModResolver.resolveEnabled( mods, Map.of() ) );
    }

    @Test
    void togglingOnTurnsRequiresOnAndConflictsOffWithReasons()
    {
        List< GameOptionalMod > mods = List.of( plain( "shaders.jar", false ),
                                                mod( "ausm.jar", false, List.of( "lib.jar" ), List.of( "fast.jar" ) ),
                                                plain( "lib.jar", false ),
                                                plain( "fast.jar", true ),
                                                mod( "fastaddon.jar", true, List.of( "fast.jar" ), null ) );
        OptionalModResolver.ToggleResult result = OptionalModResolver.toggle( mods, Map.of(), "ausm.jar", true );

        assertTrue( result.applied() );
        assertNull( result.refusal() );
        assertEquals( Set.of( "ausm.jar", "lib.jar" ), result.enabled() );
        assertEquals( Boolean.TRUE, result.choices().get( "ausm.jar" ) );
        // A requirement is on because ausm is, not because the player chose it.
        assertFalse( result.choices().containsKey( "lib.jar" ) );
        assertEquals( Boolean.FALSE, result.choices().get( "fast.jar" ) );
        assertEquals( Boolean.FALSE, result.choices().get( "fastaddon.jar" ) );

        assertEquals( 3, result.changes().size() );
        OptionalModResolver.Change lib = result.changes().get( 0 );
        assertEquals( "lib.jar", lib.key() );
        assertTrue( lib.enabled() );
        assertEquals( OptionalModResolver.Reason.REQUIRED_BY, lib.reason() );
        assertEquals( "ausm", lib.cause() );
        OptionalModResolver.Change fast = result.changes().get( 1 );
        assertEquals( OptionalModResolver.Reason.CONFLICTS_WITH, fast.reason() );
        assertEquals( "ausm", fast.cause() );
        OptionalModResolver.Change addon = result.changes().get( 2 );
        assertFalse( addon.enabled() );
        assertEquals( OptionalModResolver.Reason.REQUIRES_OFF, addon.reason() );
        assertEquals( "fast", addon.cause() );
    }

    @Test
    void conflictsAreHonouredWhenOnlyTheOtherSideNamesThem()
    {
        List< GameOptionalMod > mods = List.of( plain( "a.jar", true ),
                                                mod( "b.jar", false, null, List.of( "a.jar" ) ) );
        OptionalModResolver.ToggleResult result = OptionalModResolver.toggle( mods, Map.of(), "b.jar", true );
        assertTrue( result.applied() );
        assertEquals( Set.of( "b.jar" ), result.enabled() );
        assertEquals( OptionalModResolver.Reason.CONFLICTS_WITH, result.changes().get( 0 ).reason() );
    }

    @Test
    void togglingARequirementOffTurnsItsDependentsOffAndStoresThem()
    {
        List< GameOptionalMod > mods = List.of( mod( "a.jar", false, List.of( "b.jar" ), null ),
                                                plain( "b.jar", false ),
                                                mod( "c.jar", false, List.of( "a.jar" ), null ) );
        OptionalModResolver.ToggleResult on = OptionalModResolver.toggle( mods, Map.of(), "c.jar", true );
        assertEquals( Set.of( "a.jar", "b.jar", "c.jar" ), on.enabled() );
        assertEquals( Map.of( "c.jar", true ), on.choices() );

        OptionalModResolver.ToggleResult off = OptionalModResolver.toggle( mods, on.choices(), "a.jar", false );
        assertTrue( off.applied() );
        // c needs a, so it goes and is stored off; b was only on for a, so it falls back to off.
        assertTrue( off.enabled().isEmpty() );
        assertEquals( Map.of( "c.jar", false, "a.jar", false ), off.choices() );
        assertEquals( 2, off.changes().size() );
        OptionalModResolver.Change b = off.changes().get( 0 );
        assertEquals( "b.jar", b.key() );
        assertEquals( OptionalModResolver.Reason.NO_LONGER_REQUIRED, b.reason() );
        assertEquals( "a", b.cause() );
        OptionalModResolver.Change c = off.changes().get( 1 );
        assertEquals( "c.jar", c.key() );
        assertEquals( OptionalModResolver.Reason.REQUIRES_OFF, c.reason() );
        assertEquals( "a", c.cause() );
    }

    @Test
    void turningAModOnThenOffReturnsToThePreviousState()
    {
        List< GameOptionalMod > mods = List.of( mod( "a.jar", false, List.of( "b.jar" ), null ),
                                                mod( "b.jar", false, List.of( "c.jar" ), null ),
                                                plain( "c.jar", false ),
                                                plain( "d.jar", true ) );
        Set< String > initial = OptionalModResolver.resolveEnabled( mods, Map.of() );
        OptionalModResolver.ToggleResult on = OptionalModResolver.toggle( mods, Map.of(), "a.jar", true );
        assertEquals( Set.of( "a.jar", "b.jar", "c.jar", "d.jar" ), on.enabled() );
        assertEquals( Map.of( "a.jar", true ), on.choices() );

        OptionalModResolver.ToggleResult off = OptionalModResolver.toggle( mods, on.choices(), "a.jar", false );
        assertTrue( off.applied() );
        assertEquals( initial, off.enabled() );
        assertEquals( Map.of( "a.jar", false ), off.choices() );
        assertEquals( 2, off.changes().size() );
        // Each dropped requirement names the mod that needed it directly.
        assertEquals( OptionalModResolver.Reason.NO_LONGER_REQUIRED, off.changes().get( 0 ).reason() );
        assertEquals( "a", off.changes().get( 0 ).cause() );
        assertEquals( OptionalModResolver.Reason.NO_LONGER_REQUIRED, off.changes().get( 1 ).reason() );
        assertEquals( "b", off.changes().get( 1 ).cause() );
    }

    @Test
    void aRequirementThePlayerTurnedOnStaysOn()
    {
        List< GameOptionalMod > mods = List.of( mod( "a.jar", false, List.of( "b.jar" ), null ),
                                                plain( "b.jar", false ) );
        OptionalModResolver.ToggleResult b = OptionalModResolver.toggle( mods, Map.of(), "b.jar", true );
        OptionalModResolver.ToggleResult a = OptionalModResolver.toggle( mods, b.choices(), "a.jar", true );
        OptionalModResolver.ToggleResult off = OptionalModResolver.toggle( mods, a.choices(), "a.jar", false );
        assertEquals( Set.of( "b.jar" ), off.enabled() );
        assertTrue( off.changes().isEmpty() );
    }

    @Test
    void aRequirementThatIsOnByDefaultStaysOn()
    {
        List< GameOptionalMod > mods = List.of( mod( "a.jar", false, List.of( "b.jar" ), null ),
                                                plain( "b.jar", true ) );
        OptionalModResolver.ToggleResult a = OptionalModResolver.toggle( mods, Map.of(), "a.jar", true );
        OptionalModResolver.ToggleResult off = OptionalModResolver.toggle( mods, a.choices(), "a.jar", false );
        assertEquals( Set.of( "b.jar" ), off.enabled() );
    }

    @Test
    void aSharedRequirementStaysOnWhileEitherModThatNeedsItIsOn()
    {
        List< GameOptionalMod > mods = List.of( mod( "a.jar", false, List.of( "b.jar" ), null ),
                                                plain( "b.jar", false ),
                                                mod( "c.jar", false, List.of( "b.jar" ), null ) );
        OptionalModResolver.ToggleResult a = OptionalModResolver.toggle( mods, Map.of(), "a.jar", true );
        OptionalModResolver.ToggleResult c = OptionalModResolver.toggle( mods, a.choices(), "c.jar", true );
        assertTrue( c.changes().isEmpty() );

        OptionalModResolver.ToggleResult aOff = OptionalModResolver.toggle( mods, c.choices(), "a.jar", false );
        assertEquals( Set.of( "b.jar", "c.jar" ), aOff.enabled() );
        assertTrue( aOff.changes().isEmpty() );

        OptionalModResolver.ToggleResult cOff = OptionalModResolver.toggle( mods, aOff.choices(), "c.jar", false );
        assertTrue( cOff.enabled().isEmpty() );
        assertEquals( 1, cOff.changes().size() );
        assertEquals( OptionalModResolver.Reason.NO_LONGER_REQUIRED, cOff.changes().get( 0 ).reason() );
        assertEquals( "c", cOff.changes().get( 0 ).cause() );
    }

    @Test
    void turningARequirementOffWhileItsDependentIsOnTurnsTheDependentOff()
    {
        List< GameOptionalMod > mods = List.of( mod( "a.jar", false, List.of( "b.jar" ), null ),
                                                plain( "b.jar", false ) );
        OptionalModResolver.ToggleResult a = OptionalModResolver.toggle( mods, Map.of(), "a.jar", true );
        OptionalModResolver.ToggleResult bOff = OptionalModResolver.toggle( mods, a.choices(), "b.jar", false );
        assertTrue( bOff.applied() );
        assertTrue( bOff.enabled().isEmpty() );
        assertEquals( Map.of( "a.jar", false, "b.jar", false ), bOff.choices() );
        assertEquals( OptionalModResolver.Reason.REQUIRES_OFF, bOff.changes().get( 0 ).reason() );
        assertEquals( "b", bOff.changes().get( 0 ).cause() );
    }

    @Test
    void turningOnStoresConflictsOffSoTurningOffDoesNotBringThemBack()
    {
        List< GameOptionalMod > mods = List.of( mod( "a.jar", false, null, List.of( "f.jar" ) ),
                                                plain( "f.jar", true ) );
        OptionalModResolver.ToggleResult on = OptionalModResolver.toggle( mods, Map.of(), "a.jar", true );
        assertEquals( Map.of( "a.jar", true, "f.jar", false ), on.choices() );
        OptionalModResolver.ToggleResult off = OptionalModResolver.toggle( mods, on.choices(), "a.jar", false );
        assertTrue( off.enabled().isEmpty() );
        assertTrue( off.changes().isEmpty() );
    }

    @Test
    void aModHeldOffByAConflictComesBackWhenTheOtherGoesOff()
    {
        // Both on by default: a is listed first and wins until the player turns it off.
        List< GameOptionalMod > mods = List.of( plain( "a.jar", true ),
                                                mod( "m.jar", true, null, List.of( "a.jar" ) ) );
        assertEquals( Set.of( "a.jar" ), OptionalModResolver.resolveEnabled( mods, Map.of() ) );
        OptionalModResolver.ToggleResult off = OptionalModResolver.toggle( mods, Map.of(), "a.jar", false );
        assertEquals( Set.of( "m.jar" ), off.enabled() );
        assertEquals( 1, off.changes().size() );
        OptionalModResolver.Change m = off.changes().get( 0 );
        assertTrue( m.enabled() );
        assertEquals( OptionalModResolver.Reason.CONFLICT_CLEARED, m.reason() );
        assertEquals( "a", m.cause() );
    }

    @Test
    void anUnsatisfiableToggleIsRefusedAndChangesNothing()
    {
        List< GameOptionalMod > mods = List.of( mod( "a.jar", false, List.of( "b.jar" ), List.of( "b.jar" ) ),
                                                plain( "b.jar", true ) );
        Map< String, Boolean > choices = Map.of( "b.jar", true );
        OptionalModResolver.ToggleResult result = OptionalModResolver.toggle( mods, choices, "a.jar", true );
        assertFalse( result.applied() );
        assertEquals( OptionalModResolver.Reason.UNSATISFIABLE, result.refusal() );
        assertEquals( Set.of( "b.jar" ), result.enabled() );
        assertTrue( result.changes().isEmpty() );
    }

    @Test
    void anUnknownModIsRefused()
    {
        OptionalModResolver.ToggleResult result =
                OptionalModResolver.toggle( List.of( plain( "a.jar", false ) ), Map.of(), "zzz.jar", true );
        assertFalse( result.applied() );
        assertEquals( OptionalModResolver.Reason.UNKNOWN, result.refusal() );
    }

    @Test
    void aClientInstallsOnlyClientSideModsThatAreOn()
    {
        GameOptionalMod serverOnly = new GameOptionalMod( "s", "https://example.invalid/s.jar", "s.jar", null,
                                                          false, true, "", true, null, null );
        List< GameOptionalMod > mods = List.of( plain( "a.jar", true ), plain( "b.jar", false ), serverOnly );
        List< GameOptionalMod > picked = OptionalModResolver.selectForSide( mods, GameMode.CLIENT, Map.of() );
        assertEquals( 1, picked.size() );
        assertEquals( "a.jar", picked.get( 0 ).key() );
    }

    @Test
    void aServerIgnoresChoicesAndInstallsOnlyServerSideDefaultOnMods()
    {
        GameOptionalMod serverDefaultOn = new GameOptionalMod( "s1", "https://example.invalid/s1.jar", "s1.jar",
                                                               null, true, true, "", true, null, null );
        GameOptionalMod serverDefaultOff = new GameOptionalMod( "s2", "https://example.invalid/s2.jar", "s2.jar",
                                                                null, true, true, "", false, null, null );
        List< GameOptionalMod > mods = List.of( plain( "client.jar", true ), serverDefaultOn, serverDefaultOff );
        Map< String, Boolean > choices = Map.of( "s2.jar", true, "s1.jar", false );
        List< GameOptionalMod > picked = OptionalModResolver.selectForSide( mods, GameMode.SERVER, choices );
        assertEquals( 1, picked.size() );
        assertEquals( "s1.jar", picked.get( 0 ).key() );
    }

    @Test
    void storedChoicesUseTheManifestSpelling()
    {
        List< GameOptionalMod > mods = List.of( plain( "AUSM.jar", false ) );
        OptionalModResolver.ToggleResult result = OptionalModResolver.toggle( mods, Map.of(), "ausm.jar", true );
        Map< String, Boolean > stored = OptionalMods.toStoredChoices( mods, result.choices() );
        assertEquals( Map.of( "AUSM.jar", true ), stored );
    }
}
