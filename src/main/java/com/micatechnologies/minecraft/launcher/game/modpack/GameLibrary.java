/*
 * Copyright (c) 2020 Mica Technologies
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

package com.micatechnologies.minecraft.launcher.game.modpack;

import com.micatechnologies.minecraft.launcher.consts.ModPackConstants;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * Class representing a Minecraft game library that can be downloaded from remote.
 *
 * @author Mica Technologies
 * @version 1.0
 * @creator hawka97
 * @editors hawka97
 * @see ManagedGameFile
 */
public class GameLibrary extends ManagedGameFile
{

    /**
     * Boolean flag for strict OS checking, used with {@link #applicableOSes}.
     * @since 1.0
     */
    private final boolean strictOSCheck;

    /**
     * List of applicable OSes, used with {@link #strictOSCheck}.
     * @since 1.0
     */
    private final ArrayList< String > applicableOSes;

    /**
     * Boolean flag representing if library is a native of another.
     * @since 1.0
     */
    private final boolean isNativeLib;

    /**
     * Create a GameLibrary object with the given remote and local file information and applicability information.
     *
     * @param remoteURL      library remote URL
     * @param localPath      library local path
     * @param sha1Hash       library SHA-1 hash
     * @param strictOSCheck  strict OS checking flag
     * @param applicableOSes strict OS list
     * @param isNativeLib    native library flag
     *
     * @since 1.0
     */
    public GameLibrary( String remoteURL, String localPath, String sha1Hash, boolean strictOSCheck,
                        ArrayList< String > applicableOSes, boolean isNativeLib )
    {
        // Setup remote file configuration
        super( remoteURL, localPath, sha1Hash );

        // Store applicability information
        this.strictOSCheck = strictOSCheck;
        this.applicableOSes = applicableOSes;
        this.isNativeLib = isNativeLib;
    }

    /**
     * Get a list of the OSes that this library applies to.
     *
     * @return list of applicable OSes
     *
     * @since 1.0
     */
    public ArrayList< String > getApplicableOSes() {
        if ( this.strictOSCheck ) {
            return applicableOSes;
        }
        else {
            return new ArrayList<>( Arrays.asList( ModPackConstants.PLATFORM_WINDOWS,
                                                   ModPackConstants.PLATFORM_MACOS,
                                                   ModPackConstants.PLATFORM_UNIX ) );
        }
    }

    /**
     * Return if this MCLibrary is marked as a native library.
     *
     * @return true if native library
     */
    public boolean isNativeLib() {
        return isNativeLib;
    }
}
