/*
 * Copyright (c) 2021 Mica Technologies
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

import com.google.gson.Gson;
import com.micatechnologies.minecraft.launcher.files.Logger;
import org.apache.commons.io.IOUtils;

import java.net.URL;
import java.nio.charset.Charset;

/**
 * Class for fetching mod pack objects from their manifest URL.
 *
 * @author Mica Technologies
 * @version 1.0
 * @since 2.0
 */
public class GameModPackFetcher
{

    /**
     * Fetches the mod pack object from the specified manifest URL.
     *
     * @param manifestUrl mod pack manifest URL
     *
     * @return mod pack object
     *
     * @since 1.0
     */
    public static GameModPack get( String manifestUrl ) {
        // Fetch contents of available mod pack manifest
        GameModPack gameModPack;
        try {
            String manifestBody = IOUtils.toString( new URL( manifestUrl ), Charset.defaultCharset() );
            gameModPack = new Gson().fromJson( manifestBody, GameModPack.class );
            gameModPack.prepareEnvironment();
            gameModPack.cacheImages();
        }
        catch ( Exception e ) {
            Logger.logError( "The following installed mod pack could not be loaded: " + manifestUrl );
            Logger.logThrowable( e );
            gameModPack = new GameModPack();
        }

        // Parse available mod pack manifest contents
        gameModPack.manifestUrl = manifestUrl;
        return gameModPack;
    }
}
