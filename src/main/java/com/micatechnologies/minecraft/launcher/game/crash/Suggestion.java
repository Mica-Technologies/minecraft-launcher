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

package com.micatechnologies.minecraft.launcher.game.crash;

/**
 * Single actionable suggestion attached to a {@link CrashDiagnosis}. Carries either an
 * inline action (a {@link Runnable} the GUI fires when the user clicks the button) or
 * — when {@link #action} is {@code null} — a text-only hint with no button.
 *
 * @param label  short button text (~3-5 words). E.g. "Increase max RAM to 8 GB".
 * @param action what happens when the user clicks the suggestion's button. {@code null}
 *               renders the suggestion as a plain bullet (no button).
 * @param primary if true, the GUI styles this suggestion as the primary call-to-action
 *                (filled brand-color button) rather than the default tonal style.
 */
public record Suggestion( String label, Runnable action, boolean primary )
{
    /** Convenience constructor for non-primary actionable suggestions. */
    public static Suggestion of( String label, Runnable action )
    {
        return new Suggestion( label, action, false );
    }

    /** Convenience constructor for the primary call-to-action suggestion. */
    public static Suggestion primary( String label, Runnable action )
    {
        return new Suggestion( label, action, true );
    }

    /** Convenience constructor for a text-only hint (no clickable button). */
    public static Suggestion hint( String label )
    {
        return new Suggestion( label, null, false );
    }
}
