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

package com.micatechnologies.minecraft.launcher.rgb.effects;

import com.micatechnologies.minecraft.launcher.rgb.KeyboardKey;
import com.micatechnologies.minecraft.launcher.rgb.RgbColor;
import com.micatechnologies.minecraft.launcher.rgb.RgbEffect;
import com.micatechnologies.minecraft.launcher.rgb.RgbFrame;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The headline in-game effect: a stable pack-color background with a
 * contrasting highlight color overlaid on the Minecraft-relevant keys.
 *
 * <p>Two-tone by construction — the "background" is the pack's
 * primary dominant color (extracted from the modpack's logo by the
 * same pipeline that powers the main-menu hero card gradient), and the
 * "highlight" is the HSL-complement of that primary, which keeps the
 * key overlay visually distinct from whatever theme the pack
 * inherently has without requiring a second hand-picked color per
 * pack.</p>
 *
 * <p>The highlighted-keys set is configurable so users who don't
 * care about WASD highlighting can opt out via the "Highlight common
 * Minecraft keys" toggle in the Settings RGB tab. The default set is
 * the gameplay-default movement + interact keys
 * (W/A/S/D/E/Q/F/SPACE/LEFT_SHIFT/LEFT_CTRL). When the toggle is
 * off the caller hands in an empty set and the effect degrades to a
 * single solid pack-color background.</p>
 *
 * @since 2026.5
 */
public final class InGameEffect implements RgbEffect
{
    /** Default key set highlighted when the Settings toggle is on.
     *  Matches Minecraft's default movement + interaction keybinds —
     *  remappable in-game, but the vast majority of users keep the
     *  defaults. */
    public static final Set< KeyboardKey > DEFAULT_HIGHLIGHTED_KEYS = EnumSet.of(
            KeyboardKey.W, KeyboardKey.A, KeyboardKey.S, KeyboardKey.D,
            KeyboardKey.E, KeyboardKey.Q, KeyboardKey.F,
            KeyboardKey.SPACE, KeyboardKey.LEFT_SHIFT, KeyboardKey.LEFT_CTRL );

    private final String packDisplayName;
    private final RgbFrame frame;

    /**
     * Constructs a new {@code InGameEffect} with the specified parameters.
     *
     * @param packDisplayName     pack name surfaced in the effect's
     *                            {@link #name()} (for log lines and the
     *                            Settings status chip). Pass {@code ""}
     *                            when no pack is active.
     * @param background          pack-derived background color.
     * @param highlight           color to paint on the highlighted keys.
     *                            Typically {@code background.complement()}.
     * @param highlightedKeys     keys to overlay with {@code highlight}.
     *                            Pass an empty set to degrade to a solid
     *                            background.
     */
    public InGameEffect( String packDisplayName, RgbColor background,
                          RgbColor highlight, Set< KeyboardKey > highlightedKeys )
    {
        this.packDisplayName = packDisplayName == null ? "" : packDisplayName;
        Map< KeyboardKey, RgbColor > overrides = new EnumMap<>( KeyboardKey.class );
        if ( highlightedKeys != null && !highlightedKeys.isEmpty() ) {
            for ( KeyboardKey k : highlightedKeys ) {
                overrides.put( k, highlight );
            }
        }
        // Static frame — the in-game effect doesn't animate. Build
        // once at construction so the engine's per-tick frameAt() is
        // a single object return.
        this.frame = new RgbFrame( background, overrides );
    }

    /**
     * Returns the name of the effect.
     *
     * @return the name of the effect, which includes the pack display name if available.
     */
    @Override
    public String name()
    {
        return packDisplayName.isEmpty()
                ? "In-Game"
                : "In-Game (" + packDisplayName + ")";
    }

    /**
     * Returns the RGB frame at the specified elapsed time.
     *
     * @param elapsedMs the elapsed time in milliseconds.
     * @return the RGB frame for the effect.
     */
    @Override
    public RgbFrame frameAt( long elapsedMs )
    {
        return frame;
    }
}
