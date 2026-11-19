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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The pure decisions behind the modpack detail window's layout: which tabs a pack gets, the
 * Optional mods summary counts, and where the scroll area lands after a tab switch. Kept apart
 * from {@link MCLauncherModpackDetailModal} so they can be tested without JavaFX.
 *
 * @since 2026.11
 */
final class DetailModalLayout
{
    /** The Overview tab: what's new, the optional mods summary, news, links, stats, quick actions. */
    static final String TAB_OVERVIEW = "overview";
    /** The Problems tab: files the launcher re-downloads on every launch. */
    static final String TAB_PROBLEMS = "problems";
    /** The Mods tab: optional mods, then the installed mods. */
    static final String TAB_MODS     = "mods";
    /** The Worlds &amp; files tab: worlds, servers, screenshots, shader and resource packs. */
    static final String TAB_FILES    = "files";
    /** The Activity tab: update log and crash history. */
    static final String TAB_ACTIVITY = "activity";
    /** The Settings tab: the per-pack account, verify, scan and official-launcher controls. */
    static final String TAB_SETTINGS = "settings";

    /** How close (in pixels) the scroll offset must be to the tab bar's position to count as
     *  pinned; absorbs rounding from the fractional scroll value. */
    static final double PIN_TOLERANCE = 0.5;

    private DetailModalLayout() { /* static-only */ }

    /**
     * Returns the tabs a pack gets, in display order. Overview always comes first and Problems
     * follows it when there are problems. Mods needs a modded pack with an install folder or a
     * settings key (optional mods can be chosen before the first install). Worlds &amp; files and
     * Activity need an install folder. Settings needs a settings key (a manifest, or a vanilla
     * version).
     *
     * @param hasProblems    whether the pack's audit found problems
     * @param vanilla        whether the pack is a vanilla Minecraft install
     * @param hasFolder      whether the pack has an install folder
     * @param hasSettingsKey whether the pack has a stable settings key
     *
     * @return the tab ids, never empty
     *
     * @since 2026.11
     */
    static List< String > tabsFor( boolean hasProblems, boolean vanilla, boolean hasFolder, boolean hasSettingsKey )
    {
        List< String > tabs = new ArrayList<>();
        tabs.add( TAB_OVERVIEW );
        if ( hasProblems ) {
            tabs.add( TAB_PROBLEMS );
        }
        if ( !vanilla && ( hasFolder || hasSettingsKey ) ) {
            tabs.add( TAB_MODS );
        }
        if ( hasFolder ) {
            tabs.add( TAB_FILES );
            tabs.add( TAB_ACTIVITY );
        }
        if ( hasSettingsKey ) {
            tabs.add( TAB_SETTINGS );
        }
        return tabs;
    }

    /**
     * The Optional mods summary's two numbers: how many optional mods the player can choose from
     * here and how many of those are on.
     *
     * @param available the optional mods a client can turn on
     * @param on        how many of them are on
     *
     * @since 2026.11
     */
    record OptionalCounts( int available, int on ) { }

    /**
     * Counts the optional mods the way the Mods tab lists them: client-side mods only (a
     * server-only one gets no switch), each identity key once, and "on" within that set.
     *
     * @param mods    the pack's optional mods, possibly {@code null}
     * @param enabled the identity keys that are on, possibly {@code null}
     *
     * @return the counts
     *
     * @since 2026.11
     */
    static OptionalCounts optionalCounts( List< GameOptionalMod > mods, Set< String > enabled )
    {
        if ( mods == null ) {
            return new OptionalCounts( 0, 0 );
        }
        Set< String > seen = new HashSet<>();
        int on = 0;
        for ( GameOptionalMod mod : mods ) {
            if ( mod == null || !mod.isClientSide() || !seen.add( mod.key() ) ) {
                continue;
            }
            if ( enabled != null && enabled.contains( mod.key() ) ) {
                on++;
            }
        }
        return new OptionalCounts( seen.size(), on );
    }

    /**
     * Whether the tab bar is pinned at a scroll offset: the area has scrolled at least as far as
     * the tab bar's place in the content.
     *
     * @param offset the scroll offset in pixels
     * @param pinAt  the tab bar's top within the scrolled content
     *
     * @return {@code true} when the bar sits at the top of the scroll area
     *
     * @since 2026.11
     */
    static boolean isPinned( double offset, double pinAt )
    {
        return pinAt > 0 && offset >= pinAt - PIN_TOLERANCE;
    }

    /**
     * Where the scroll area goes after a tab switch: back to the pinned position when the bar was
     * pinned (so a long tab doesn't leave the new one scrolled half-way down), otherwise where it
     * was, so the switch never jumps back over the hero.
     *
     * @param offset the scroll offset before the switch, in pixels
     * @param pinAt  the tab bar's top within the scrolled content
     *
     * @return the offset to restore
     *
     * @since 2026.11
     */
    static double offsetAfterTabSwitch( double offset, double pinAt )
    {
        return isPinned( offset, pinAt ) ? pinAt : Math.max( 0, offset );
    }

    /**
     * How far the tab bar moves down to stay at the top of the scroll area: nothing until the area
     * has scrolled to it, then the distance scrolled past it.
     *
     * @param offset the scroll offset in pixels
     * @param pinAt  the tab bar's top within the scrolled content
     *
     * @return the bar's vertical translation, never negative
     *
     * @since 2026.11
     */
    static double pinTranslation( double offset, double pinAt )
    {
        return Math.max( 0, offset - pinAt );
    }
}
