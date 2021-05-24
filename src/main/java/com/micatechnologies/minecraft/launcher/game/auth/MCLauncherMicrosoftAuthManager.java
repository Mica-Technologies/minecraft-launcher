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

package com.micatechnologies.minecraft.launcher.game.auth;

/**
 * Class providing an interface for Minecraft authentication using the Microsoft/Xbox Live authentication system.
 *
 * @author Mica Technologies
 * @implNote This implementation of the Microsoft/Xbox Live authentication system has been built using the
 *         information at https://wiki.vg/Microsoft_Authentication_Scheme.
 * @since 2021.2
 */
public class MCLauncherMicrosoftAuthManager extends MCLauncherAbstractAuthManager
{
    /**
     * Method which returns the game access token, or a null value if the token can not be acquired.
     *
     * @return game access token or null
     */
    @Override
    public String getAccessTokenOrNull() {
        return null;
    }
}
