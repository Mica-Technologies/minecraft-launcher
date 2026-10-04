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

import javafx.scene.shape.Rectangle;

/**
 * Material 3's corner-radius scale, the only radii the launcher's UI uses. CSS can't declare size
 * variables, so {@code ui-base.css} writes these values literally (and {@code ShapeScaleTest} keeps
 * it on the scale); Java code that clips images to a rounded card uses these constants, so a clip
 * always matches the CSS shape it sits in.
 *
 * <ul>
 *     <li>{@link #EXTRA_SMALL} (4): checkboxes, tooltips' inner parts, thumbnails, scroll thumbs</li>
 *     <li>{@link #SMALL} (8): text fields, menus and menu items, chips, small buttons</li>
 *     <li>{@link #MEDIUM} (12): cards, list items, logo frames</li>
 *     <li>{@link #LARGE} (16): hero and pack cards, larger logo frames</li>
 *     <li>{@link #EXTRA_LARGE} (28): modal surfaces such as the modpack detail window</li>
 *     <li>full ({@code 999} in CSS): pills, round buttons, progress bars</li>
 * </ul>
 *
 * @since 2026.10
 */
public final class ShapeScale
{
    /** 4 px. */
    public static final double EXTRA_SMALL = 4;

    /** 8 px. */
    public static final double SMALL = 8;

    /** 12 px. */
    public static final double MEDIUM = 12;

    /** 16 px. */
    public static final double LARGE = 16;

    /** 28 px. */
    public static final double EXTRA_LARGE = 28;

    private ShapeScale()
    {
    }

    /**
     * Rounds a clip rectangle's corners to a radius. A Rectangle's arc is the corner's diameter, so
     * this sets twice the radius.
     *
     * @param clip   the clip to round
     * @param radius the corner radius, normally one of this class's constants or {@link #inner}
     *
     * @return the same clip, for chaining
     */
    public static Rectangle round( Rectangle clip, double radius )
    {
        clip.setArcWidth( radius * 2 );
        clip.setArcHeight( radius * 2 );
        return clip;
    }

    /**
     * The radius for content inset inside a rounded frame, so the two curves stay concentric: the
     * frame's radius minus the inset, never below zero.
     *
     * @param outer the frame's radius
     * @param inset the gap between the frame's edge and the content
     *
     * @return the inner radius
     */
    public static double inner( double outer, double inset )
    {
        return Math.max( 0, outer - inset );
    }
}
