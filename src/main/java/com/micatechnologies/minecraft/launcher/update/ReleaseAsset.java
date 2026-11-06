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

import java.util.Locale;

/**
 * One downloadable file of a launcher release on GitHub.
 *
 * @param name   the file name as GitHub serves it (spaces already turned into dots)
 * @param url    the direct download URL
 * @param size   the size in bytes, or {@code -1} when unknown
 * @param sha256 the lowercase hex SHA-256 GitHub computed for the file, or {@code null} when the
 *               release API gave none (releases before 2025)
 *
 * @since 2026.11
 */
public record ReleaseAsset( String name, String url, long size, String sha256 )
{
    /**
     * Whether the file name ends with the given suffix, ignoring case.
     *
     * @param suffix the suffix, such as {@code "-user.msi"}
     *
     * @return {@code true} when it does
     *
     * @since 2026.11
     */
    public boolean nameEndsWith( String suffix )
    {
        return name.toLowerCase( Locale.ROOT ).endsWith( suffix.toLowerCase( Locale.ROOT ) );
    }
}
