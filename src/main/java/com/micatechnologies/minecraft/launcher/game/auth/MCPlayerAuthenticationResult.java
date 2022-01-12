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
 * @version 1.0
 * @since 2021.2
 */
public record MCPlayerAuthenticationResult(User minecraftUser)
{
    /**
     * Constant {@link MCPlayerAuthenticationResult} indicating an authentication error due to bad username/password
     * combination.
     *
     * @since 1.0
     */
    public static final MCPlayerAuthenticationResult ERROR_BAD_USERNAME_PASSWORD = new MCPlayerAuthenticationResult( null );

    /**
     * Constant {@link MCPlayerAuthenticationResult} indicating an authentication error due to an expired login.
     *
     * @since 1.0
     */
    public static final MCPlayerAuthenticationResult ERROR_LOGIN_EXPIRED = new MCPlayerAuthenticationResult( null );

    /**
     * Constant {@link MCPlayerAuthenticationResult} indicating an authentication error due to not owning Minecraft.
     *
     * @since 1.0
     */
    public static final MCPlayerAuthenticationResult ERROR_NOT_OWNED = new MCPlayerAuthenticationResult( null );

    /**
     * Constant {@link MCPlayerAuthenticationResult} indicating an authentication error due to unknown value reason.
     *
     * @since 1.0
     */
    public static final MCPlayerAuthenticationResult ERROR_NO_VAL = new MCPlayerAuthenticationResult( null );

    /**
     * Constant {@link MCPlayerAuthenticationResult} indicating an authentication error due to other/unknown reason.
     *
     * @since 1.0
     */
    public static final MCPlayerAuthenticationResult ERROR_OTHER = new MCPlayerAuthenticationResult( null );

    /**
     * Gets the resulting Minecraft user from authentication.
     *
     * @return Minecraft user from authentication
     *
     * @since 1.0
     */
    public User getMinecraftUser() {
        return minecraftUser;
    }
}
