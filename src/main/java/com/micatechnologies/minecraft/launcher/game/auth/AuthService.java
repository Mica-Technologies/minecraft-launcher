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

package com.micatechnologies.minecraft.launcher.game.auth;

import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.launcher.consts.AuthConstants;
import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
import com.micatechnologies.minecraft.launcher.exceptions.AuthException;
import com.micatechnologies.minecraft.launcher.utilities.AuthUtilities;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.utilities.JSONUtilities;

/**
 * Class for interacting with Minecraft authentication server endpoints using MinecraftAccount objects.
 *
 * @author Mica Technologies
 * @version 1.2
 * @editors hawka97
 * @creator hawka97
 * @see AuthAccount
 * @since 1.0
 */
public class AuthService
{

    /**
     * Perform authentication for the specified account using the specified password and client token.
     *
     * @param account  account to authenticate
     * @param password password to authenticate with
     *
     * @return true for success, false for failure
     *
     * @throws AuthException if an error occurs
     * @since 1.0
     */
    public static boolean usernamePasswordAuth( AuthAccount account, String password ) throws AuthException
    {
        // Build JSON Objects for Request
        JsonObject root = new JsonObject();
        JsonObject agent = new JsonObject();

        // Populate agent object
        agent.addProperty( AuthConstants.AUTH_AGENT_NAME._1, AuthConstants.AUTH_AGENT_NAME._2 );
        agent.addProperty( AuthConstants.AUTH_AGENT_VERSION._1, AuthConstants.AUTH_AGENT_VERSION._2 );

        // Populate root object
        root.add( AuthConstants.AUTH_ENDPOINT_KEY_AGENT, agent );
        root.addProperty( AuthConstants.AUTH_ENDPOINT_KEY_USERNAME, account.getAccountName() );
        root.addProperty( AuthConstants.AUTH_ENDPOINT_KEY_PASSWORD, password );
        root.addProperty( AuthConstants.AUTH_ENDPOINT_KEY_CLIENT_TOKEN, AuthManager.getClientToken() );

        // Perform HTTP Post Call to Mojang Endpoint
        String response = AuthUtilities.doHTTPPOST( AuthConstants.AUTH_PASSWORD_ENDPOINT, root.toString() );

        // Convert response to Json Object
        JsonObject responseObject = JSONUtilities.stringToObject( response );

        // Process response only if error or error message not present
        if ( !responseObject.has( AuthConstants.AUTH_RESPONSE_KEY_ERROR ) &&
                !responseObject.has( AuthConstants.AUTH_RESPONSE_KEY_ERROR_MSG ) ) {
            // Read and save acquired access token
            if ( responseObject.has( AuthConstants.AUTH_ENDPOINT_KEY_ACCESS_TOKEN ) ) {
                account.setLastAccessToken(
                        responseObject.get( AuthConstants.AUTH_ENDPOINT_KEY_ACCESS_TOKEN ).getAsString() );
            }
            else {
                throw new AuthException( LocalizationManager.AUTH_RESPONSE_NO_ACCESS_TOKEN_TEXT );
            }

            // Read and save profile name and id, if present
            // If not present, method will continue
            if ( responseObject.has( AuthConstants.AUTH_RESPONSE_KEY_SELECTED_PROFILE ) ) {
                if ( responseObject.getAsJsonObject( AuthConstants.AUTH_RESPONSE_KEY_SELECTED_PROFILE )
                                   .has( AuthConstants.AUTH_RESPONSE_KEY_SELECTED_PROFILE_NAME ) ) {
                    account.setFriendlyName(
                            responseObject.getAsJsonObject( AuthConstants.AUTH_RESPONSE_KEY_SELECTED_PROFILE )
                                          .get( AuthConstants.AUTH_RESPONSE_KEY_SELECTED_PROFILE_NAME )
                                          .getAsString() );
                }
                else {
                    Logger.logDebug( LocalizationManager.AUTH_RESPONSE_NO_PROFILE_NAME_TEXT );
                }
                if ( responseObject.getAsJsonObject( AuthConstants.AUTH_RESPONSE_KEY_SELECTED_PROFILE )
                                   .has( AuthConstants.AUTH_RESPONSE_KEY_SELECTED_PROFILE_ID ) ) {
                    account.setUserIdentifier(
                            responseObject.getAsJsonObject( AuthConstants.AUTH_RESPONSE_KEY_SELECTED_PROFILE )
                                          .get( AuthConstants.AUTH_RESPONSE_KEY_SELECTED_PROFILE_ID )
                                          .getAsString() );
                }
                else {
                    Logger.logDebug( LocalizationManager.AUTH_RESPONSE_NO_PROFILE_ID_TEXT );
                }
            }
        }
        else {
            return false;
        }

        // Return true on success
        return true;
    }

    /**
     * Refresh the authentication for the specified account by renewing the last access token. The account must have a
     * last access token.
     *
     * @param account account to authenticate
     *
     * @return true for success, false for failure
     *
     * @throws AuthException if an error occurs
     * @since 1.0
     */
    public static boolean refreshAuth( AuthAccount account ) throws AuthException
    {
        // Check for presence of existing access token
        // If none, return immediately
        if ( account.getLastAccessToken() == null ) {
            return false;
        }

        // Build JSON Object for Request
        JsonObject root = new JsonObject();
        root.addProperty( AuthConstants.AUTH_ENDPOINT_KEY_ACCESS_TOKEN, account.getLastAccessToken() );
        root.addProperty( AuthConstants.AUTH_ENDPOINT_KEY_CLIENT_TOKEN, AuthManager.getClientToken() );

        // Perform HTTP Post Call to Mojang Endpoint
        String response = AuthUtilities.doHTTPPOST( AuthConstants.AUTH_REFRESH_TOKEN_ENDPOINT, root.toString() );

        // Convert response to Json Object
        JsonObject responseObject = JSONUtilities.stringToObject( response );

        // Process response only if error or error message not present
        if ( !responseObject.has( AuthConstants.AUTH_RESPONSE_KEY_ERROR ) &&
                !responseObject.has( AuthConstants.AUTH_RESPONSE_KEY_ERROR_MSG ) ) {
            // Read and save acquired access token
            if ( responseObject.has( AuthConstants.AUTH_ENDPOINT_KEY_ACCESS_TOKEN ) ) {
                account.setLastAccessToken(
                        responseObject.get( AuthConstants.AUTH_ENDPOINT_KEY_ACCESS_TOKEN ).getAsString() );
            }
            else {
                throw new AuthException( LocalizationManager.AUTH_RESPONSE_NO_ACCESS_TOKEN_TEXT );
            }

            // Read and save profile name and id, if present
            // If not present, method will continue
            if ( responseObject.has( AuthConstants.AUTH_RESPONSE_KEY_SELECTED_PROFILE ) ) {
                if ( responseObject.getAsJsonObject( AuthConstants.AUTH_RESPONSE_KEY_SELECTED_PROFILE )
                                   .has( AuthConstants.AUTH_RESPONSE_KEY_SELECTED_PROFILE_NAME ) ) {
                    account.setFriendlyName(
                            responseObject.getAsJsonObject( AuthConstants.AUTH_RESPONSE_KEY_SELECTED_PROFILE )
                                          .get( AuthConstants.AUTH_RESPONSE_KEY_SELECTED_PROFILE_NAME )
                                          .getAsString() );
                }
                else {
                    Logger.logDebug( LocalizationManager.AUTH_RESPONSE_NO_PROFILE_NAME_TEXT );
                }
                if ( responseObject.getAsJsonObject( AuthConstants.AUTH_RESPONSE_KEY_SELECTED_PROFILE )
                                   .has( AuthConstants.AUTH_RESPONSE_KEY_SELECTED_PROFILE_ID ) ) {
                    account.setUserIdentifier(
                            responseObject.getAsJsonObject( AuthConstants.AUTH_RESPONSE_KEY_SELECTED_PROFILE )
                                          .get( AuthConstants.AUTH_RESPONSE_KEY_SELECTED_PROFILE_ID )
                                          .getAsString() );
                }
                else {
                    Logger.logDebug( LocalizationManager.AUTH_RESPONSE_NO_PROFILE_ID_TEXT );
                }
            }
        }
        else {
            return false;
        }

        // Return true on success
        return true;
    }

    /**
     * Validate the authentication of the specified account.
     *
     * @param account account to validate
     *
     * @return true for success, false for failure
     *
     * @throws AuthException if an error occurs
     * @since 1.0
     */
    public static boolean validateLogin( AuthAccount account ) throws AuthException
    {
        // Check for presence of existing access token
        // If none, return immediately
        if ( account.getLastAccessToken() == null ) {
            return false;
        }

        // Build JSON Object for Request
        JsonObject root = new JsonObject();
        root.addProperty( AuthConstants.AUTH_ENDPOINT_KEY_ACCESS_TOKEN, account.getLastAccessToken() );
        root.addProperty( AuthConstants.AUTH_ENDPOINT_KEY_CLIENT_TOKEN, AuthManager.getClientToken() );

        // Perform HTTP Post Call to Mojang Endpoint
        String response = AuthUtilities.doHTTPPOST( AuthConstants.AUTH_VALIDATE_TOKEN_ENDPOINT, root.toString() );

        // Convert response to Json Object
        JsonObject responseObject = JSONUtilities.stringToObject( response );

        // Return true if no error in response
        return !responseObject.has( AuthConstants.AUTH_RESPONSE_KEY_ERROR ) &&
                !responseObject.has( AuthConstants.AUTH_RESPONSE_KEY_ERROR_MSG );
    }

    /**
     * Invalidate the authentication of the specified account.
     *
     * @param account account to invalidate
     *
     * @return true for success, false for failure
     *
     * @throws AuthException if an error occurs
     * @since 1.0
     */
    public static boolean invalidateLogin( AuthAccount account ) throws AuthException
    {
        // Check for presence of existing access token
        // If none, return immediately
        if ( account.getLastAccessToken() == null ) {
            return false;
        }

        // Build JSON Object for Request
        JsonObject root = new JsonObject();
        root.addProperty( AuthConstants.AUTH_ENDPOINT_KEY_ACCESS_TOKEN, account.getLastAccessToken() );
        root.addProperty( AuthConstants.AUTH_ENDPOINT_KEY_CLIENT_TOKEN, AuthManager.getClientToken() );

        // Perform HTTP Post Call to Mojang Endpoint
        String response = AuthUtilities.doHTTPPOST( AuthConstants.AUTH_INVALIDATE_TOKEN_ENDPOINT, root.toString() );

        // Convert response to Json Object
        JsonObject responseObject = JSONUtilities.stringToObject( response );

        // Return true if no error in response
        boolean isSuccess = !responseObject.has( AuthConstants.AUTH_RESPONSE_KEY_ERROR ) &&
                !responseObject.has( AuthConstants.AUTH_RESPONSE_KEY_ERROR_MSG );
        if ( isSuccess ) {
            account.setLastAccessToken( null );
        }
        return isSuccess;
    }
}
