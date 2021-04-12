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

package com.micatechnologies.minecraft.launcher.utilities;

import com.micatechnologies.minecraft.launcher.consts.AuthConstants;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;

/**
 * Class containing utility methods and other functionality that pertains to the network and/or network connections in
 * the launcher.
 *
 * @author Mica Technologies
 * @version 1.1
 * @editors hawka97
 * @creator hawka97
 * @since 1.0
 */
public class NetworkUtilities
{
    /**
     * Tests a connection to the Mojang/Minecraft authentication server and returns a boolean indicating success or
     * failure.
     *
     * @return true if successful, false if unsuccessful
     *
     * @since 1.0
     */
    public static boolean isMojangAuthReachable() {
        try {
            // Attempt connection to auth server
            URL url = new URL( AuthConstants.AUTH_SERVER_URL );
            URLConnection connection = url.openConnection();

            // Return true/success if content not empty
            if ( connection.getContentLength() != -1 ) {
                return true;
            }
        }
        catch ( Exception ignored ) {
        }

        // Return false if did not meet connection criteria
        return false;
    }

    /**
     * Downloads the file from the specified URL (as string) to the specified file.
     *
     * @param source      source URL (as string)
     * @param destination destination file
     *
     * @throws IOException if unable to download or save file
     * @since 1.1
     */
    public static void downloadFileFromURL( String source, File destination ) throws IOException {
        downloadFileFromURL( new URL( source ), destination );
    }

    /**
     * Downloads the file from the specified URL to the specified file.
     *
     * @param source      source URL
     * @param destination destination file
     *
     * @throws IOException if unable to download or save file
     * @since 1.1
     */
    public static void downloadFileFromURL( URL source, File destination ) throws IOException {
        URLConnection connection = source.openConnection();
        connection.setUseCaches( false );
        FileUtils.copyInputStreamToFile( connection.getInputStream(), destination );
    }
}
