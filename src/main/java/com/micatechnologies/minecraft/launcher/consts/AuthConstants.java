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

package com.micatechnologies.minecraft.launcher.consts;

import com.micatechnologies.minecraft.launcher.utilities.objects.Pair;

/**
 * Class of constants used by the Minecraft Authentication Library
 * <p>
 * NOTE: This class should NOT contain display strings that are visible to the end-user. All localizable strings MUST be
 * stored and retrieved using {@link com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager}.
 *
 * @author Mica Technologies
 * @version 1.1
 * @editors hawka97
 * @creator hawka97
 * @since 1.0
 */
public class AuthConstants
{

    /**
     * Address of the Mojang/Minecraft authentication server.
     *
     * @since 1.0
     */
    public static final String AUTH_SERVER_URL = "https://authserver.mojang.com";

    /**
     * Endpoint on Mojang/Minecraft authentication server that allows for username/password authentication.
     *
     * @since 1.0
     */
    public static final String AUTH_PASSWORD_ENDPOINT = "authenticate";

    /**
     * Endpoint on Mojang/Minecraft authentication server that allows for an access token to be refreshed for continued
     * use.
     *
     * @since 1.0
     */
    public static final String AUTH_REFRESH_TOKEN_ENDPOINT = "refresh";

    /**
     * Endpoint on Mojang/Minecraft authentication server that allows for an access token to be invalidated.
     *
     * @since 1.0
     */
    public static final String AUTH_INVALIDATE_TOKEN_ENDPOINT = "invalidate";

    /**
     * The key for the username in requests to endpoints on the Mojang/Minecraft authentication server.
     *
     * @since 1.0
     */
    public static final String AUTH_ENDPOINT_KEY_USERNAME = "username";

    /**
     * The key for the password in requests to endpoints on the Mojang/Minecraft authentication server.
     *
     * @since 1.0
     */
    public static final String AUTH_ENDPOINT_KEY_PASSWORD = "password";

    /**
     * The key for the client token in requests to endpoints on the Mojang/Minecraft authentication server.
     *
     * @since 1.0
     */
    public static final String AUTH_ENDPOINT_KEY_CLIENT_TOKEN = "clientToken";

    /**
     * The key for the agent in requests to endpoints on the Mojang/Minecraft authentication server.
     *
     * @since 1.0
     */
    public static final String AUTH_ENDPOINT_KEY_AGENT = "agent";

    /**
     * The key for the access token in requests to endpoints on the Mojang/Minecraft authentication server.
     *
     * @since 1.0
     */
    public static final String AUTH_ENDPOINT_KEY_ACCESS_TOKEN = "accessToken";

    /**
     * The key and value pair for the agent name in requests to endpoints on the Mojang/Minecraft authentication
     * server.
     *
     * @since 1.0
     */
    public static final Pair< String, String > AUTH_AGENT_NAME = new Pair<>( "name", "Minecraft" );

    /**
     * The key and value pair for the agent version in requests to endpoints on the Mojang/Minecraft authentication
     * server.
     *
     * @since 1.0
     */
    public static final Pair< String, String > AUTH_AGENT_VERSION = new Pair<>( "version", "1" );

    /**
     * The key and value pair for the <code>Content-Type</code> header field used when contacting the Mojang/Minecraft
     * authentication server.
     *
     * @since 1.0
     */
    public static final Pair< String, String > AUTH_POST_CONTENT_TYPE = new Pair<>( "Content-Type",
                                                                                    "application/json" );

    /**
     * The key for the selected profile information that is returned in a response from the Mojang/Minecraft
     * authentication server.
     *
     * @since 1.0
     */
    public static final String AUTH_RESPONSE_KEY_SELECTED_PROFILE = "selectedProfile";

    /**
     * The key for the selected profile name part of the selected profile information that is returned in a response
     * from the Mojang/Minecraft authentication server.
     *
     * @since 1.0
     */
    public static final String AUTH_RESPONSE_KEY_SELECTED_PROFILE_NAME = "name";

    /**
     * The key for the selected profile ID part of the selected profile information that is returned in a response from
     * the Mojang/Minecraft authentication server.
     *
     * @since 1.0
     */
    public static final String AUTH_RESPONSE_KEY_SELECTED_PROFILE_ID = "id";

    /**
     * The key for an error that may be returned (in the event of an error) from the Mojang/Minecraft authentication
     * server.
     *
     * @since 1.0
     */
    public static final String AUTH_RESPONSE_KEY_ERROR = "error";

    /**
     * The key for an error message that may be returned (in the event of an error) from the Mojang/Minecraft
     * authentication server.
     *
     * @since 1.0
     */
    public static final String AUTH_RESPONSE_KEY_ERROR_MSG = "errorMessage";

    /**
     * The contents of an empty response from the Mojang/Minecraft authentication server.
     *
     * @since 1.0
     */
    public static final String AUTH_RESPONSE_EMPTY = "{}";
}
