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

import com.micatechnologies.minecraft.launcher.consts.GUIConstants;
import javafx.scene.image.Image;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Player avatars, fetched once per account per session.
 *
 * <p>Home, Settings and the account lists each built a fresh {@link Image} from the avatar
 * service on every visit, so every return to Home re-downloaded the same picture. Images
 * here load in the background ({@code backgroundLoading = true}) so the FX thread never
 * waits on the network, and a failed load is retried on the next request rather than
 * cached.</p>
 *
 * @since 2026.10
 */
public final class AvatarImages
{
    private static final Map< String, Image > CACHE = new ConcurrentHashMap<>();

    private AvatarImages() { }

    /**
     * The avatar service URL for an account. Pure, for testing.
     *
     * @param uuid the account's Minecraft profile id
     *
     * @return the URL
     *
     * @since 2026.10
     */
    static String url( String uuid )
    {
        return GUIConstants.URL_MINECRAFT_USER_ICONS.replace( GUIConstants.URL_MINECRAFT_USER_ICONS_USER_REPLACE_KEY,
                                                              uuid );
    }

    /**
     * An account's avatar, loading in the background on first request.
     *
     * @param uuid the account's Minecraft profile id
     *
     * @return the avatar image (possibly still loading), or {@code null} for a blank uuid
     *
     * @since 2026.10
     */
    public static Image get( String uuid )
    {
        if ( uuid == null || uuid.isBlank() ) {
            return null;
        }
        return CACHE.compute( uuid, ( key, cached ) ->
                cached != null && !cached.isError() ? cached : new Image( url( key ), true ) );
    }
}
