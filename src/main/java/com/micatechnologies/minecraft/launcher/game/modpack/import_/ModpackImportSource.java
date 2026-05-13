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

package com.micatechnologies.minecraft.launcher.game.modpack.import_;

/**
 * Origin platform of a modpack URL the user pasted into the Add-by-URL field.
 * The launcher's add-pack flow checks this before assuming the URL points at
 * a Mica-format manifest — pasting a Modrinth / CurseForge project URL today
 * would otherwise fail with a generic "not a valid manifest" error and leave
 * the user wondering whether the launcher even saw the URL.
 *
 * <p>The detection / preview UI lives in the GUI layer; this enum is package
 * neutral so the classifier and the import workers (a follow-up step) share
 * a single source of truth for "what platform are we talking to."</p>
 *
 * @since 2026.3
 */
public enum ModpackImportSource
{
    /** A modrinth.com project URL (free public API, .mrpack file format). */
    MODRINTH,

    /** A curseforge.com project URL (Core API requires a key, CF zip format). */
    CURSEFORGE,

    /** A Mica-format {@code manifest.json} URL — the launcher's native shape.
     *  Direct install via the existing {@code installModPackByURL} path. */
    MICA,

    /** None of the above. Caller falls through to the legacy add-by-URL path
     *  which tries the input as a Mica manifest URL; if that fails, the user
     *  gets the existing error feedback (host not whitelisted, parse failure,
     *  etc.) rather than a misleading platform-specific message. */
    UNKNOWN
}
