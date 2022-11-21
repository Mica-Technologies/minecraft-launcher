/*
 * Copyright (c) 2022 Mica Technologies
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

package com.micatechnologies.minecraft.launcher.files.hash;

import com.micatechnologies.minecraft.launcher.files.ManagedRemoteFile;

import java.io.File;

/**
 * Basic class used to identify no hash method for a {@link ManagedRemoteFile}.
 *
 * @author Mica Technologies
 * @version 1.0
 * @since 2022.1
 */
public class FileChecksumNONE extends FileChecksum
{
    /**
     * Constructor for an instance of {@link FileChecksum} with no specified hash.
     *
     * @since 1.0
     */
    public FileChecksumNONE() {
        super( "-1" );
    }

    /**
     * Returns true to disable hash verification.
     *
     * @param file {@link File} to verify hash against
     *
     * @return true if specified {@link File} hash matches
     */
    @Override
    public boolean verifyFile( File file ) {
        return true;
    }
}
