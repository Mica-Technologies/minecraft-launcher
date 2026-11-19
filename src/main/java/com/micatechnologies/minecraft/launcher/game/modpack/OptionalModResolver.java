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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decides which of a pack's optional mods are on, and what a player's toggle changes.
 * <p>
 * Pure logic over a manifest's {@code packOptionalMods} list and a player's stored choices
 * (the {@code optionalModsByPack} map in {@code configuration.json}). Nothing here reads the
 * config or touches the disk, so the sync, the detail modal and the MCP tools all get the same
 * answer from the same rules.
 *
 * <h2>Resolution rules</h2>
 * <ol>
 *   <li>Each mod's <em>wish</em> is the player's stored choice, or the entry's
 *       {@code defaultEnabled} when the player never chose.</li>
 *   <li>Mods are visited in manifest order. A mod that is wished on is turned on together with
 *       everything it {@code requires}, transitively. A required mod is turned on even if its own
 *       wish is off: what is on must be loadable.</li>
 *   <li>If that group would conflict with a mod already on (in either direction:
 *       {@code conflictsWith} is symmetric), or within itself, the whole group stays off. So when
 *       stored choices or defaults disagree, <b>the mod listed earlier wins</b>.</li>
 *   <li>Names in {@code requires} or {@code conflictsWith} that are not optional mods of the
 *       same pack are ignored here (the editor refuses to save them).</li>
 * </ol>
 * Rule 3 bites when a manifest update adds a conflict after the player chose, or when defaults
 * disagree; the toggle stores {@code false} for mods it forces off so it does not bite there.
 *
 * <h2>What a toggle stores</h2>
 * A toggle ({@link #toggle}) stores only what the player decided:
 * <ul>
 *   <li>the toggled mod's own new state;</li>
 *   <li>{@code false} for every mod that conflicts with the toggled mod's requires-group, and for
 *       everything that (transitively) requires one of those: the player chose the toggled mod
 *       over them;</li>
 *   <li>when turning a mod off, {@code false} for everything that (transitively) requires it.</li>
 * </ul>
 * Requirements a toggle turns on are <em>not</em> stored. Rule 2 keeps them on while a mod that
 * needs them is on; once none is, each falls back to its own wish. So turning a mod on and then
 * off again turns its requirements back off, unless the player switched one on themselves or
 * another mod that is on still needs it.
 *
 * <h2>Sides</h2>
 * A client installs the resolved set's mods that are client-side. A dedicated server has no
 * player to choose, so it ignores stored choices and installs exactly the mods that are both
 * {@code serverReq} and {@code defaultEnabled}.
 *
 * @author Mica Technologies
 * @since 2026.11
 */
public final class OptionalModResolver
{
    /**
     * Why a toggle changed a mod the player did not touch, or why a toggle was refused.
     *
     * @since 2026.11
     */
    public enum Reason
    {
        /** Turned on because a mod that is on requires it. */
        REQUIRED_BY,
        /** Turned off because it conflicts with a mod that is on. */
        CONFLICTS_WITH,
        /** Turned off because it requires a mod that is now off. */
        REQUIRES_OFF,
        /**
         * Turned off because it was only on as a requirement of a mod that is now off; it fell
         * back to its own wish.
         */
        NO_LONGER_REQUIRED,
        /**
         * Turned on because the mod it conflicts with is now off; it was wished on but held off
         * by that conflict.
         */
        CONFLICT_CLEARED,
        /** Refused: the mod needs a group of mods that conflict among themselves. */
        UNSATISFIABLE,
        /** Refused: no optional mod has that name. */
        UNKNOWN
    }

    /**
     * A mod a toggle switched as a side effect.
     *
     * @param key     the switched mod's identity key
     * @param name    the switched mod's display name
     * @param enabled its new state
     * @param reason  why it switched
     * @param cause   the display name of the mod that caused it, or {@code ""}
     *
     * @since 2026.11
     */
    public record Change( String key, String name, boolean enabled, Reason reason, String cause ) { }

    /**
     * What a toggle does.
     *
     * @param applied   whether the toggle can be applied; {@code false} means nothing changes
     * @param refusal   why it was refused, or {@code null} when applied
     * @param choices   the player's choices after the toggle (identity key to on/off), to store:
     *                  the input choices plus the toggled mod's state and the {@code false}s for
     *                  mods it forced off (see the class notes); requirements turned on are not
     *                  among them. Equal to the input choices when refused
     * @param enabled   the identity keys that are on after the toggle
     * @param changes   the mods other than the toggled one that switched, in manifest order
     *
     * @since 2026.11
     */
    public record ToggleResult( boolean applied, Reason refusal, Map< String, Boolean > choices,
                                Set< String > enabled, List< Change > changes ) { }

    /**
     * Not instantiable.
     */
    private OptionalModResolver()
    {
        throw new AssertionError( "OptionalModResolver is static-only" );
    }

    /**
     * Resolves which optional mods are on for a player.
     *
     * @param mods    the pack's optional mods, in manifest order; {@code null} reads as none
     * @param choices the player's stored choices, keyed by any spelling of the mod's local name;
     *                {@code null} reads as none
     *
     * @return the identity keys ({@link GameOptionalMod#key()}) of the mods that are on, in
     *         manifest order
     *
     * @since 2026.11
     */
    public static Set< String > resolveEnabled( List< GameOptionalMod > mods, Map< String, Boolean > choices )
    {
        Map< String, GameOptionalMod > byKey = index( mods );
        Map< String, Boolean > wishes = normalizeChoices( choices );
        Set< String > enabled = new LinkedHashSet<>();
        for ( GameOptionalMod mod : byKey.values() ) {
            String key = mod.key();
            if ( enabled.contains( key ) ) {
                continue;
            }
            boolean wished = wishes.getOrDefault( key, mod.isDefaultEnabled() );
            if ( !wished ) {
                continue;
            }
            Set< String > group = requiresClosure( byKey, key );
            if ( conflictsWithin( byKey, group ) || conflictsBetween( byKey, group, enabled ) ) {
                continue;
            }
            enabled.addAll( group );
        }
        return orderLike( byKey, enabled );
    }

    /**
     * Picks the optional mods a sync installs on one side.
     *
     * @param mods    the pack's optional mods, in manifest order
     * @param side    the side being synced
     * @param choices the player's stored choices; ignored on {@link GameMode#SERVER}
     *
     * @return the mods to download, keep and verify, in manifest order
     *
     * @since 2026.11
     */
    public static List< GameOptionalMod > selectForSide( List< GameOptionalMod > mods, GameMode side,
                                                          Map< String, Boolean > choices )
    {
        List< GameOptionalMod > selected = new ArrayList<>();
        if ( mods == null || mods.isEmpty() ) {
            return selected;
        }
        if ( side == GameMode.SERVER ) {
            for ( GameOptionalMod mod : index( mods ).values() ) {
                if ( mod.isServerSide() && mod.isDefaultEnabled() ) {
                    selected.add( mod );
                }
            }
            return selected;
        }
        Map< String, GameOptionalMod > byKey = index( mods );
        for ( String key : resolveEnabled( mods, choices ) ) {
            GameOptionalMod mod = byKey.get( key );
            if ( mod != null && mod.isClientSide() ) {
                selected.add( mod );
            }
        }
        return selected;
    }

    /**
     * Works out what turning one optional mod on or off does.
     * <p>
     * Turning a mod on stores it as on and stores {@code false} for everything that conflicts
     * with it or with anything it requires (transitively), plus everything that needs one of
     * those. Its requirements are not stored: they are on because it is on. Turning a mod off
     * stores it as off and stores {@code false} for everything that needs it; its requirements
     * fall back to their own wish (stored choice, else {@code defaultEnabled}) unless another mod
     * that is on still needs them. See the class notes.
     *
     * @param mods    the pack's optional mods, in manifest order
     * @param choices the player's current stored choices
     * @param local   any spelling of the toggled mod's local name
     * @param enable  the requested state
     *
     * @return the outcome; never {@code null}
     *
     * @since 2026.11
     */
    public static ToggleResult toggle( List< GameOptionalMod > mods, Map< String, Boolean > choices,
                                       String local, boolean enable )
    {
        Map< String, GameOptionalMod > byKey = index( mods );
        Map< String, Boolean > before = normalizeChoices( choices );
        Set< String > enabledBefore = resolveEnabled( mods, before );
        String target = GameOptionalMod.keyOf( local );
        if ( !byKey.containsKey( target ) ) {
            return refused( Reason.UNKNOWN, before, enabledBefore );
        }

        Set< String > turnOn = new LinkedHashSet<>();
        Set< String > turnOff = new LinkedHashSet<>();
        if ( enable ) {
            Set< String > group = requiresClosure( byKey, target );
            if ( conflictsWithin( byKey, group ) ) {
                return refused( Reason.UNSATISFIABLE, before, enabledBefore );
            }
            turnOn.addAll( group );
            for ( String other : byKey.keySet() ) {
                if ( !group.contains( other ) && conflictsBetween( byKey, group, Set.of( other ) ) ) {
                    turnOff.add( other );
                }
            }
        }
        else {
            turnOff.add( target );
        }
        turnOff.addAll( dependentsOf( byKey, turnOff ) );
        if ( turnOff.removeAll( turnOn ) && enable ) {
            // A mod in the group needs something the group conflicts with.
            return refused( Reason.UNSATISFIABLE, before, enabledBefore );
        }

        // Only the player's own pick and the mods it forces off are stored. Requirements stay on
        // through the requires-closure while a mod that needs them is on, and fall back to their
        // own wish once none is.
        Map< String, Boolean > after = new LinkedHashMap<>( before );
        after.put( target, enable );
        for ( String key : turnOff ) {
            after.put( key, false );
        }
        Set< String > enabledAfter = resolveEnabled( mods, after );
        if ( enabledAfter.contains( target ) != enable ) {
            return refused( Reason.UNSATISFIABLE, before, enabledBefore );
        }

        List< Change > changes = new ArrayList<>();
        for ( GameOptionalMod mod : byKey.values() ) {
            String key = mod.key();
            if ( key.equals( target ) ) {
                continue;
            }
            boolean wasOn = enabledBefore.contains( key );
            boolean isOn = enabledAfter.contains( key );
            if ( wasOn != isOn ) {
                changes.add( explain( byKey, mod, isOn, turnOff.contains( key ), enabledBefore, enabledAfter ) );
            }
        }
        return new ToggleResult( true, null, after, enabledAfter, changes );
    }

    /**
     * Explains why a mod switched. Every cause is picked deterministically: the first candidate
     * in manifest order.
     * <ul>
     *   <li>Turned on: {@link Reason#REQUIRED_BY} the first mod on afterwards that directly
     *       requires it; else {@link Reason#CONFLICT_CLEARED} by the first mod that was on, is off
     *       now and conflicts with it; else {@link Reason#REQUIRED_BY} with no cause.</li>
     *   <li>Turned off and stored off by the toggle ({@code forced}):
     *       {@link Reason#CONFLICTS_WITH} the first mod on afterwards that it conflicts with; else
     *       {@link Reason#REQUIRES_OFF} the first mod it directly requires that is off afterwards;
     *       else {@link Reason#REQUIRES_OFF} with no cause.</li>
     *   <li>Turned off without being stored (it fell back to its own wish):
     *       {@link Reason#NO_LONGER_REQUIRED} by the first mod that was on, is off now and directly
     *       requires it; else {@link Reason#CONFLICTS_WITH} the first mod on afterwards that it
     *       conflicts with; else {@link Reason#REQUIRES_OFF} with no cause.</li>
     * </ul>
     *
     * @param byKey  the pack's optional mods by key
     * @param mod    the switched mod
     * @param isOn   its new state
     * @param forced whether the toggle stored it as off
     * @param before the keys that were on before the toggle
     * @param after  the keys that are on after the toggle
     *
     * @return the change
     */
    private static Change explain( Map< String, GameOptionalMod > byKey, GameOptionalMod mod, boolean isOn,
                                   boolean forced, Set< String > before, Set< String > after )
    {
        String key = mod.key();
        String name = mod.getDisplayName();
        if ( isOn ) {
            String requirer = firstRequirer( byKey, key, after, Set.of() );
            if ( requirer != null ) {
                return new Change( key, name, true, Reason.REQUIRED_BY, requirer );
            }
            String cleared = firstConflict( byKey, key, before, after );
            if ( cleared != null ) {
                return new Change( key, name, true, Reason.CONFLICT_CLEARED, cleared );
            }
            return new Change( key, name, true, Reason.REQUIRED_BY, "" );
        }
        if ( !forced ) {
            String requirer = firstRequirer( byKey, key, before, after );
            if ( requirer != null ) {
                return new Change( key, name, false, Reason.NO_LONGER_REQUIRED, requirer );
            }
        }
        String conflict = firstConflict( byKey, key, after, Set.of() );
        if ( conflict != null ) {
            return new Change( key, name, false, Reason.CONFLICTS_WITH, conflict );
        }
        if ( forced ) {
            for ( String required : keysOf( mod.getRequires() ) ) {
                GameOptionalMod requiredMod = byKey.get( required );
                if ( requiredMod != null && !after.contains( required ) ) {
                    return new Change( key, name, false, Reason.REQUIRES_OFF, requiredMod.getDisplayName() );
                }
            }
        }
        return new Change( key, name, false, Reason.REQUIRES_OFF, "" );
    }

    /**
     * Finds the first mod, in manifest order, that is in {@code in}, not in {@code notIn}, and
     * directly requires {@code key}.
     *
     * @param byKey the pack's optional mods by key, in manifest order
     * @param key   the required mod
     * @param in    the mods to look among
     * @param notIn the mods to leave out
     *
     * @return the requirer's display name, or {@code null} when there is none
     */
    private static String firstRequirer( Map< String, GameOptionalMod > byKey, String key, Set< String > in,
                                         Set< String > notIn )
    {
        for ( GameOptionalMod candidate : byKey.values() ) {
            String other = candidate.key();
            if ( in.contains( other ) && !notIn.contains( other ) && keysOf( candidate.getRequires() ).contains( key ) ) {
                return candidate.getDisplayName();
            }
        }
        return null;
    }

    /**
     * Finds the first mod, in manifest order, that is in {@code in}, not in {@code notIn}, and
     * conflicts with {@code key} in either direction.
     *
     * @param byKey the pack's optional mods by key, in manifest order
     * @param key   the mod
     * @param in    the mods to look among
     * @param notIn the mods to leave out
     *
     * @return the conflicting mod's display name, or {@code null} when there is none
     */
    private static String firstConflict( Map< String, GameOptionalMod > byKey, String key, Set< String > in,
                                         Set< String > notIn )
    {
        for ( GameOptionalMod candidate : byKey.values() ) {
            String other = candidate.key();
            if ( in.contains( other ) && !notIn.contains( other ) && conflicts( byKey, key, other ) ) {
                return candidate.getDisplayName();
            }
        }
        return null;
    }

    /**
     * Builds a refusal that changes nothing.
     *
     * @param reason  why
     * @param choices the unchanged choices
     * @param enabled the unchanged enabled set
     *
     * @return the refusal
     */
    private static ToggleResult refused( Reason reason, Map< String, Boolean > choices, Set< String > enabled )
    {
        return new ToggleResult( false, reason, choices, enabled, List.of() );
    }

    /**
     * Indexes mods by identity key, keeping manifest order and the first entry for a repeated key.
     *
     * @param mods the mods; {@code null} reads as none
     *
     * @return the index
     */
    static Map< String, GameOptionalMod > index( List< GameOptionalMod > mods )
    {
        Map< String, GameOptionalMod > byKey = new LinkedHashMap<>();
        if ( mods != null ) {
            for ( GameOptionalMod mod : mods ) {
                if ( mod != null && !mod.key().isEmpty() ) {
                    byKey.putIfAbsent( mod.key(), mod );
                }
            }
        }
        return byKey;
    }

    /**
     * Re-keys stored choices by identity key, so any spelling of a local name matches.
     *
     * @param choices the stored choices; {@code null} reads as none
     *
     * @return the choices by identity key
     */
    static Map< String, Boolean > normalizeChoices( Map< String, Boolean > choices )
    {
        Map< String, Boolean > normalized = new LinkedHashMap<>();
        if ( choices != null ) {
            for ( Map.Entry< String, Boolean > e : choices.entrySet() ) {
                String key = GameOptionalMod.keyOf( e.getKey() );
                if ( !key.isEmpty() && e.getValue() != null ) {
                    normalized.put( key, e.getValue() );
                }
            }
        }
        return normalized;
    }

    /**
     * Returns a mod and everything it requires, transitively, limited to optional mods of the
     * same pack.
     *
     * @param byKey the pack's optional mods by key
     * @param start the mod to start from
     *
     * @return the group, starting with {@code start}
     */
    static Set< String > requiresClosure( Map< String, GameOptionalMod > byKey, String start )
    {
        Set< String > group = new LinkedHashSet<>();
        Deque< String > pending = new ArrayDeque<>();
        pending.add( start );
        while ( !pending.isEmpty() ) {
            String key = pending.poll();
            GameOptionalMod mod = byKey.get( key );
            if ( mod == null || !group.add( key ) ) {
                continue;
            }
            pending.addAll( keysOf( mod.getRequires() ) );
        }
        return group;
    }

    /**
     * Returns the mods that need any of the given ones, transitively.
     *
     * @param byKey the pack's optional mods by key
     * @param keys  the mods being turned off
     *
     * @return the dependents, not including {@code keys} themselves
     */
    private static Set< String > dependentsOf( Map< String, GameOptionalMod > byKey, Collection< String > keys )
    {
        Set< String > off = new LinkedHashSet<>( keys );
        Set< String > dependents = new LinkedHashSet<>();
        boolean grew = true;
        while ( grew ) {
            grew = false;
            for ( GameOptionalMod mod : byKey.values() ) {
                String key = mod.key();
                if ( off.contains( key ) ) {
                    continue;
                }
                for ( String required : keysOf( mod.getRequires() ) ) {
                    if ( off.contains( required ) ) {
                        off.add( key );
                        dependents.add( key );
                        grew = true;
                        break;
                    }
                }
            }
        }
        return dependents;
    }

    /**
     * Reports whether two mods conflict, in either direction.
     *
     * @param byKey the pack's optional mods by key
     * @param a     one key
     * @param b     the other key
     *
     * @return {@code true} when either names the other in {@code conflictsWith}
     */
    private static boolean conflicts( Map< String, GameOptionalMod > byKey, String a, String b )
    {
        if ( a.equals( b ) ) {
            return false;
        }
        GameOptionalMod modA = byKey.get( a );
        GameOptionalMod modB = byKey.get( b );
        return ( modA != null && keysOf( modA.getConflictsWith() ).contains( b ) )
                || ( modB != null && keysOf( modB.getConflictsWith() ).contains( a ) );
    }

    /**
     * Reports whether any two mods of a group conflict.
     *
     * @param byKey the pack's optional mods by key
     * @param group the group
     *
     * @return {@code true} on a conflict inside the group
     */
    private static boolean conflictsWithin( Map< String, GameOptionalMod > byKey, Set< String > group )
    {
        return conflictsBetween( byKey, group, group );
    }

    /**
     * Reports whether any mod of one set conflicts with any mod of another.
     *
     * @param byKey  the pack's optional mods by key
     * @param first  one set
     * @param second the other set
     *
     * @return {@code true} on a conflict across the sets
     */
    private static boolean conflictsBetween( Map< String, GameOptionalMod > byKey, Collection< String > first,
                                             Collection< String > second )
    {
        for ( String a : first ) {
            for ( String b : second ) {
                if ( conflicts( byKey, a, b ) ) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Turns a list of local names into identity keys.
     *
     * @param names the names as written; {@code null} reads as none
     *
     * @return the keys, without blanks
     */
    private static Set< String > keysOf( List< String > names )
    {
        Set< String > keys = new LinkedHashSet<>();
        if ( names != null ) {
            for ( String name : names ) {
                String key = GameOptionalMod.keyOf( name );
                if ( !key.isEmpty() ) {
                    keys.add( key );
                }
            }
        }
        return keys;
    }

    /**
     * Orders a set of keys the way the manifest lists them.
     *
     * @param byKey the pack's optional mods by key, in manifest order
     * @param keys  the keys to order
     *
     * @return the keys in manifest order
     */
    private static Set< String > orderLike( Map< String, GameOptionalMod > byKey, Set< String > keys )
    {
        Set< String > ordered = new LinkedHashSet<>();
        for ( String key : byKey.keySet() ) {
            if ( keys.contains( key ) ) {
                ordered.add( key );
            }
        }
        return ordered;
    }
}
