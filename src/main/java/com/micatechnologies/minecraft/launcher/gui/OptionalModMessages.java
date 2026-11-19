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
import com.micatechnologies.minecraft.launcher.game.modpack.ModPackDocument;
import com.micatechnologies.minecraft.launcher.game.modpack.OptionalModResolver;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the text the detail modal's Optional mods section shows: what a toggle did and why other
 * mods switched with it, and each mod's requirement and conflict notes. Pure, with a pluggable
 * resolver, so the wording logic is testable without JavaFX or a locale.
 *
 * @author Mica Technologies
 * @since 2026.11
 */
final class OptionalModMessages
{
    /**
     * Not instantiable.
     */
    private OptionalModMessages()
    {
        throw new AssertionError( "OptionalModMessages is static-only" );
    }

    /**
     * Describes a toggle's outcome, one sentence per line: the toggled mod's new state (or why it
     * could not change), then every other mod that switched with it and why.
     *
     * @param result   the toggle's outcome
     * @param name     the toggled mod's display name
     * @param enable   the state the player asked for
     * @param resolver resolves localization keys to display text
     *
     * @return the lines, never empty
     *
     * @since 2026.11
     */
    static List< String > describeToggle( OptionalModResolver.ToggleResult result, String name, boolean enable,
                                          ModPackDocument.MessageResolver resolver )
    {
        List< String > lines = new ArrayList<>();
        if ( !result.applied() ) {
            lines.add( resolver.resolve( result.refusal() == OptionalModResolver.Reason.UNKNOWN
                                         ? "modal.optionalMods.refused.unknown"
                                         : "modal.optionalMods.refused.unsatisfiable", name ) );
            return lines;
        }
        lines.add( resolver.resolve( enable ? "modal.optionalMods.status.on" : "modal.optionalMods.status.off",
                                     name ) );
        for ( OptionalModResolver.Change change : result.changes() ) {
            boolean hasCause = change.cause() != null && !change.cause().isEmpty();
            String key = switch ( change.reason() ) {
                case REQUIRED_BY -> hasCause ? "modal.optionalMods.reason.requiredBy"
                                             : "modal.optionalMods.reason.turnedOn";
                case CONFLICTS_WITH -> "modal.optionalMods.reason.conflictsWith";
                default -> hasCause ? "modal.optionalMods.reason.requiresOff"
                                    : "modal.optionalMods.reason.turnedOff";
            };
            lines.add( hasCause ? resolver.resolve( key, change.name(), change.cause() )
                                : resolver.resolve( key, change.name() ) );
        }
        return lines;
    }

    /**
     * Describes what a mod needs and what it cannot be used with, naming the other optional mods
     * by their display names. Names that are not optional mods of the pack are left out.
     *
     * @param mod      the mod
     * @param all      the pack's optional mods
     * @param resolver resolves localization keys to display text
     *
     * @return zero, one or two lines
     *
     * @since 2026.11
     */
    static List< String > describeRelations( GameOptionalMod mod, List< GameOptionalMod > all,
                                             ModPackDocument.MessageResolver resolver )
    {
        Map< String, String > names = new LinkedHashMap<>();
        for ( GameOptionalMod other : all ) {
            names.putIfAbsent( other.key(), other.getDisplayName() );
        }
        List< String > lines = new ArrayList<>();
        List< String > needs = displayNames( mod.getRequires(), names );
        if ( !needs.isEmpty() ) {
            lines.add( resolver.resolve( "modal.optionalMods.requires", String.join( ", ", needs ) ) );
        }
        // Conflicts are symmetric: list the mods this one names and the mods that name it.
        List< String > conflicts = displayNames( mod.getConflictsWith(), names );
        for ( GameOptionalMod other : all ) {
            if ( other.key().equals( mod.key() ) ) {
                continue;
            }
            for ( String name : other.getConflictsWith() ) {
                if ( GameOptionalMod.keyOf( name ).equals( mod.key() )
                        && !conflicts.contains( other.getDisplayName() ) ) {
                    conflicts.add( other.getDisplayName() );
                }
            }
        }
        if ( !conflicts.isEmpty() ) {
            lines.add( resolver.resolve( "modal.optionalMods.conflicts", String.join( ", ", conflicts ) ) );
        }
        return lines;
    }

    /**
     * Maps local names to display names, keeping only optional mods of the pack.
     *
     * @param locals the local names as written
     * @param names  identity key to display name
     *
     * @return the display names, without duplicates
     */
    private static List< String > displayNames( List< String > locals, Map< String, String > names )
    {
        List< String > out = new ArrayList<>();
        for ( String local : locals ) {
            String name = names.get( GameOptionalMod.keyOf( local ) );
            if ( name != null && !out.contains( name ) ) {
                out.add( name );
            }
        }
        return out;
    }
}
