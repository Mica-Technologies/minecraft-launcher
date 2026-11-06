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

package com.micatechnologies.minecraft.launcher.update;

import java.io.IOException;

/**
 * Thrown when the user declined the permission an update install asked for (the administrator
 * password prompt was dismissed). Nothing was changed; the update stays downloaded.
 *
 * @since 2026.11
 */
public final class UpdateCancelledException extends IOException
{
    /**
     * @param message what was declined
     */
    public UpdateCancelledException( String message )
    {
        super( message );
    }
}
