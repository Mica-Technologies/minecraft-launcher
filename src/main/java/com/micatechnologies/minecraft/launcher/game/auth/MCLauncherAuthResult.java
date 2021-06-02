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

import net.hycrafthd.minecraft_authenticator.login.User;

/**
 * Class which provides an interface for storing the result of Minecraft authentication, and provides constant result
 * values which can be used to identify error cases.
 *
 * @author Mica Technologies
 * @since 2021.2
 */
public record MCLauncherAuthResult(User minecraftUser)
{
    /**
     * Constant {@link MCLauncherAuthResult} indicating an authentication error due to bad username/password
     * combination.
     */
    public static final MCLauncherAuthResult ERROR_BAD_USERNAME_PASSWORD = new MCLauncherAuthResult( null );

    /**
     * Constant {@link MCLauncherAuthResult} indicating an authentication error due to an expired login.
     */
    public static final MCLauncherAuthResult ERROR_LOGIN_EXPIRED = new MCLauncherAuthResult( null );

    /**
     * Constant {@link MCLauncherAuthResult} indicating an authentication error due to not owning Minecraft.
     */
    public static final MCLauncherAuthResult ERROR_NOT_OWNED = new MCLauncherAuthResult( null );

    /**
     * Constant {@link MCLauncherAuthResult} indicating an authentication error due to unknown value reason.
     */
    public static final MCLauncherAuthResult ERROR_NO_VAL = new MCLauncherAuthResult( null );

    /**
     * Constant {@link MCLauncherAuthResult} indicating an authentication error due to other/unknown reason.
     */
    public static final MCLauncherAuthResult ERROR_OTHER = new MCLauncherAuthResult( null );

    /**
     * Gets the resulting Minecraft user from authentication.
     *
     * @return Minecraft user from authentication
     */
    public User getMinecraftUser() {
        return minecraftUser;
    }
}
