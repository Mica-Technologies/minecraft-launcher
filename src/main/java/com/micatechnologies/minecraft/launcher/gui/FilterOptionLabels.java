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

import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
import javafx.util.StringConverter;

/**
 * Display labels for the filter and sort dropdowns on Home and Browse.
 *
 * <p>The dropdowns carry stable ids ({@code "modpacks"}, {@code "vanilla.release"},
 * {@code "nameAz"}) and render them through a converter that looks the label up under
 * {@code <prefix><id>}. They used to carry the English labels themselves, so every language
 * saw English menus, and the filtering logic compared against those English strings.</p>
 *
 * @since 2026.10
 */
final class FilterOptionLabels
{
    /** Key prefix for content-type filters. */
    static final String TYPE   = "filter.type.";
    /** Key prefix for install-status filters. */
    static final String STATUS = "filter.status.";
    /** Key prefix for sort orders. */
    static final String SORT   = "filter.sort.";

    private FilterOptionLabels() { }

    /**
     * The label key for an option id. Pure, for testing.
     *
     * @param prefix one of {@link #TYPE}, {@link #STATUS}, {@link #SORT}
     * @param id     the option id
     *
     * @return the localization key
     *
     * @since 2026.10
     */
    static String key( String prefix, String id )
    {
        return prefix + id;
    }

    /**
     * A converter that shows each option id as its localized label.
     *
     * @param prefix one of {@link #TYPE}, {@link #STATUS}, {@link #SORT}
     *
     * @return the converter
     *
     * @since 2026.10
     */
    static StringConverter< String > converter( String prefix )
    {
        return new StringConverter<>()
        {
            @Override
            public String toString( String id )
            {
                return id == null ? "" : LocalizationManager.get( key( prefix, id ) );
            }

            @Override
            public String fromString( String label )
            {
                // The dropdowns aren't editable, so there is never typed text to parse.
                return null;
            }
        };
    }
}
