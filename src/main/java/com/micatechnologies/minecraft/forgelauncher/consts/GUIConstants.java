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

package com.micatechnologies.minecraft.forgelauncher.consts;

/**
 * Class containing constants that are related or required by various components of the application's GUI subsystem.
 *
 * @author Mica Technologies
 * @version 1.0
 * @editors hawka97
 * @creator hawka97
 * @since 1.0
 */
public class GUIConstants
{
    /**
     * The regex/replacement key for the username in the Minecraft user icon URL.
     *
     * @since 1.0
     */
    public final static String URL_MINECRAFT_USER_ICONS_USER_REPLACE_KEY = "{user}";

    /**
     * The download URL template for the Minecraft user icon.
     *
     * @since 1.0
     */
    public final static String URL_MINECRAFT_USER_ICONS =
            "http://minotar.net/armor/bust/" + URL_MINECRAFT_USER_ICONS_USER_REPLACE_KEY + "/100.png";

    /**
     * The download URL of a default mod pack logo image. This logo is also used as a placeholder when no mod packs are
     * installed.
     *
     * @since 1.0
     */
    public final static String URL_MINECRAFT_NO_MOD_PACK_IMAGE =
            "https://cdn.pixabay.com/photo/2016/11/11/14/49/minecraft-1816996_960_720.png";
}
