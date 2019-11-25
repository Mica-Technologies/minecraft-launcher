package com.micatechnologies.minecraft.authlib;

import java.util.AbstractMap.SimpleEntry;

/**
 * Class of constants used by the Minecraft Authentication Library
 *
 * @author Mica Technologies/hawka97
 * @version 1.1
 */
class MCAuthConstants {

    //
    // Address of Authentication Server
    //
    static final String                        MC_AUTH_SERVER_URL                         = "https://authserver.mojang.com";

    //
    // Authentication Endpoints
    //
    static final String                        MC_AUTH_PASSWORD_ENDPOINT                  = "authenticate";

    static final String                        MC_AUTH_REFRESH_TOKEN_ENDPOINT             = "refresh";

    static final String                        MC_AUTH_VALIDATE_TOKEN_ENDPOINT            = "validate";

    static final String                        MC_AUTH_INVALIDATE_TOKEN_ENDPOINT          = "invalidate";

    //
    // Authentication Endpoint Request Keys
    //
    static final String                        MC_AUTH_ENDPOINT_USERNAME_KEY              = "username";

    static final String                        MC_AUTH_ENDPOINT_PASSWORD_KEY              = "password";

    static final String                        MC_AUTH_ENDPOINT_CLIENT_TOKEN_KEY          = "clientToken";

    static final String                        MC_AUTH_ENDPOINT_AGENT_KEY                 = "agent";

    static final String                        MC_AUTH_ENDPOINT_ACCESS_TOKEN_KEY          = "accessToken";

    //
    // Authentication Agent Properties
    //
    static final SimpleEntry< String, String > MC_AUTH_AGENT_NAME                         = new SimpleEntry<>(
        "name", "Minecraft" );

    static final SimpleEntry< String, String > MC_AUTH_AGENT_VERSION                      = new SimpleEntry<>(
        "version", "1" );

    //
    // Authentication POST Request Properties
    //
    static final SimpleEntry< String, String > MC_AUTH_POST_CONTENT_TYPE                  = new SimpleEntry<>(
        "Content-Type", "application/json" );

    //
    // Authentication Endpoint Response Keys
    //
    static final String                        MC_AUTH_RESPONSE_ACCESS_TOKEN_KEY          = "accessToken";

    static final String                        MC_AUTH_RESPONSE_SELECTED_PROFILE_KEY      = "selectedProfile";

    static final String                        MC_AUTH_RESPONSE_SELECTED_PROFILE_NAME_KEY = "name";

    static final String                        MC_AUTH_RESPONSE_SELECTED_PROFILE_ID_KEY   = "id";

    static final String                        MC_AUTH_RESPONSE_ERROR_KEY                 = "error";

    static final String                        MC_AUTH_RESPONSE_ERROR_MSG_KEY             = "errorMessage";

    //
    // Other Constants
    //
    static final String                        MC_AUTH_EMPTY_JSON_OBJ_STR                 = "{}";
}
