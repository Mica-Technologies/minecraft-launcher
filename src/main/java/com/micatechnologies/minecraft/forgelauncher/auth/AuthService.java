package com.micatechnologies.minecraft.forgelauncher.auth;

import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.forgelauncher.consts.AuthConstants;
import com.micatechnologies.minecraft.forgelauncher.exceptions.AuthException;
import com.micatechnologies.minecraft.forgelauncher.utilities.AuthenticationUtils;
import com.micatechnologies.minecraft.forgelauncher.utilities.annotations.ClientModeOnly;
import com.micatechnologies.minecraft.forgelauncher.utilities.JsonUtils;

/**
 * Class for interacting with Minecraft authentication server endpoints using MinecraftAccount objects.
 *
 * @author Mica Technologies
 * @version 1.1
 * @editors hawka97
 * @see AuthAccount
 * @since 1.0
 * @creator hawka97
 */
@ClientModeOnly
public class AuthService
{

    /**
     * Perform authentication for the specified account using the specified password and client token.
     *
     * @param account     account to authenticate
     * @param password    password to authenticate with
     * @param clientToken persistent client/device token
     *
     * @return true for success, false for failure
     *
     * @throws AuthException if an error occurs
     * @since 1.0
     */
    @ClientModeOnly
    public static boolean usernamePasswordAuth( AuthAccount account, String password,
                                                String clientToken ) throws AuthException
    {
        // Build JSON Objects for Request
        JsonObject root = new JsonObject();
        JsonObject agent = new JsonObject();

        // Populate agent object
        agent.addProperty( AuthConstants.AUTH_AGENT_NAME._1,
                           AuthConstants.AUTH_AGENT_NAME._2 );
        agent.addProperty( AuthConstants.AUTH_AGENT_VERSION._1,
                           AuthConstants.AUTH_AGENT_VERSION._2 );

        // Populate root object
        root.add( AuthConstants.AUTH_ENDPOINT_KEY_AGENT, agent );
        root.addProperty( AuthConstants.AUTH_ENDPOINT_KEY_USERNAME, account.getAccountName() );
        root.addProperty( AuthConstants.AUTH_ENDPOINT_KEY_PASSWORD, password );
        root.addProperty( AuthConstants.AUTH_ENDPOINT_KEY_CLIENT_TOKEN, clientToken );

        // Perform HTTP Post Call to Mojang Endpoint
        String response = AuthenticationUtils.doHTTPPOST( AuthConstants.AUTH_PASSWORD_ENDPOINT,
                                                          root.toString() );

        // Convert response to Json Object
        JsonObject responseObject = JsonUtils.stringToObject( response );

        // Process response only if error or error message not present
        if ( !responseObject.has( AuthConstants.AUTH_RESPONSE_KEY_ERROR ) && !responseObject
                .has( AuthConstants.AUTH_RESPONSE_KEY_ERROR_MSG ) ) {
            // Read and save acquired access token
            if ( responseObject.has( AuthConstants.AUTH_ENDPOINT_KEY_ACCESS_TOKEN ) ) {
                account.setLastAccessToken(
                        responseObject.get( AuthConstants.AUTH_ENDPOINT_KEY_ACCESS_TOKEN )
                                      .getAsString() );
            }
            else {
                throw new AuthException( "Unable to process token from Mojang response." );
            }

            // Read and save profile name and id, if present
            // If not present, method will continue
            if ( responseObject.has( AuthConstants.AUTH_RESPONSE_KEY_SELECTED_PROFILE ) ) {
                if ( responseObject.getAsJsonObject(
                        AuthConstants.AUTH_RESPONSE_KEY_SELECTED_PROFILE ).has(
                        AuthConstants.AUTH_RESPONSE_KEY_SELECTED_PROFILE_NAME ) ) {
                    account.setFriendlyName( responseObject.getAsJsonObject(
                            AuthConstants.AUTH_RESPONSE_KEY_SELECTED_PROFILE ).get(
                            AuthConstants.AUTH_RESPONSE_KEY_SELECTED_PROFILE_NAME )
                                                           .getAsString() );
                }
                if ( responseObject.getAsJsonObject(
                        AuthConstants.AUTH_RESPONSE_KEY_SELECTED_PROFILE ).has(
                        AuthConstants.AUTH_RESPONSE_KEY_SELECTED_PROFILE_ID ) ) {
                    account.setUserIdentifier( responseObject.getAsJsonObject(
                            AuthConstants.AUTH_RESPONSE_KEY_SELECTED_PROFILE ).get(
                            AuthConstants.AUTH_RESPONSE_KEY_SELECTED_PROFILE_ID ).getAsString() );
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
     * @param account     account to authenticate
     * @param clientToken persistent client/device token
     *
     * @return true for success, false for failure
     *
     * @throws AuthException if an error occurs
     * @since 1.0
     */
    @ClientModeOnly
    public static boolean refreshAuth( AuthAccount account, String clientToken )
    throws AuthException
    {
        // Check for presence of existing access token
        // If none, return immediately
        if ( account.getLastAccessToken() == null ) {
            return false;
        }

        // Build JSON Object for Request
        JsonObject root = new JsonObject();
        root.addProperty( AuthConstants.AUTH_ENDPOINT_KEY_ACCESS_TOKEN,
                          account.getLastAccessToken() );
        root.addProperty( AuthConstants.AUTH_ENDPOINT_KEY_CLIENT_TOKEN, clientToken );

        // Perform HTTP Post Call to Mojang Endpoint
        String response = AuthenticationUtils.doHTTPPOST( AuthConstants.AUTH_REFRESH_TOKEN_ENDPOINT,
                                                          root.toString() );

        // Convert response to Json Object
        JsonObject responseObject = JsonUtils.stringToObject( response );

        // Process response only if error or error message not present
        if ( !responseObject.has( AuthConstants.AUTH_RESPONSE_KEY_ERROR ) && !responseObject
                .has( AuthConstants.AUTH_RESPONSE_KEY_ERROR_MSG ) ) {
            // Read and save acquired access token
            if ( responseObject.has( AuthConstants.AUTH_ENDPOINT_KEY_ACCESS_TOKEN ) ) {
                account.setLastAccessToken(
                        responseObject.get( AuthConstants.AUTH_ENDPOINT_KEY_ACCESS_TOKEN )
                                      .getAsString() );
            }
            else {
                throw new AuthException( "Unable to process token from Mojang response." );
            }

            // Read and save profile name and id, if present
            // If not present, method will continue
            if ( responseObject.has( AuthConstants.AUTH_RESPONSE_KEY_SELECTED_PROFILE ) ) {
                if ( responseObject.getAsJsonObject(
                        AuthConstants.AUTH_RESPONSE_KEY_SELECTED_PROFILE ).has(
                        AuthConstants.AUTH_RESPONSE_KEY_SELECTED_PROFILE_NAME ) ) {
                    account.setFriendlyName( responseObject.getAsJsonObject(
                            AuthConstants.AUTH_RESPONSE_KEY_SELECTED_PROFILE ).get(
                            AuthConstants.AUTH_RESPONSE_KEY_SELECTED_PROFILE_NAME )
                                                           .getAsString() );
                }
                if ( responseObject.getAsJsonObject(
                        AuthConstants.AUTH_RESPONSE_KEY_SELECTED_PROFILE ).has(
                        AuthConstants.AUTH_RESPONSE_KEY_SELECTED_PROFILE_ID ) ) {
                    account.setUserIdentifier( responseObject.getAsJsonObject(
                            AuthConstants.AUTH_RESPONSE_KEY_SELECTED_PROFILE ).get(
                            AuthConstants.AUTH_RESPONSE_KEY_SELECTED_PROFILE_ID ).getAsString() );
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
     * @param account     account to validate
     * @param clientToken persistent client/device token
     *
     * @return true for success, false for failure
     *
     * @throws AuthException if an error occurs
     * @since 1.0
     */
    @ClientModeOnly
    public static boolean validateLogin( AuthAccount account, String clientToken )
    throws AuthException
    {
        // Check for presence of existing access token
        // If none, return immediately
        if ( account.getLastAccessToken() == null ) {
            return false;
        }

        // Build JSON Object for Request
        JsonObject root = new JsonObject();
        root.addProperty( AuthConstants.AUTH_ENDPOINT_KEY_ACCESS_TOKEN,
                          account.getLastAccessToken() );
        root.addProperty( AuthConstants.AUTH_ENDPOINT_KEY_CLIENT_TOKEN, clientToken );

        // Perform HTTP Post Call to Mojang Endpoint
        String response = AuthenticationUtils.doHTTPPOST( AuthConstants.AUTH_VALIDATE_TOKEN_ENDPOINT,
                                                          root.toString() );

        // Convert response to Json Object
        JsonObject responseObject = JsonUtils.stringToObject( response );

        // Return true if no error in response
        return !responseObject.has( AuthConstants.AUTH_RESPONSE_KEY_ERROR ) && !responseObject
                .has( AuthConstants.AUTH_RESPONSE_KEY_ERROR_MSG );
    }

    /**
     * Invalidate the authentication of the specified account.
     *
     * @param account     account to invalidate
     * @param clientToken persistent client/device token
     *
     * @return true for success, false for failure
     *
     * @throws AuthException if an error occurs
     * @since 1.0
     */
    @ClientModeOnly
    public static boolean invalidateLogin( AuthAccount account, String clientToken )
    throws AuthException
    {
        // Check for presence of existing access token
        // If none, return immediately
        if ( account.getLastAccessToken() == null ) {
            return false;
        }

        // Build JSON Object for Request
        JsonObject root = new JsonObject();
        root.addProperty( AuthConstants.AUTH_ENDPOINT_KEY_ACCESS_TOKEN,
                          account.getLastAccessToken() );
        root.addProperty( AuthConstants.AUTH_ENDPOINT_KEY_CLIENT_TOKEN, clientToken );

        // Perform HTTP Post Call to Mojang Endpoint
        String response = AuthenticationUtils.doHTTPPOST( AuthConstants.AUTH_INVALIDATE_TOKEN_ENDPOINT,
                                                          root.toString() );

        // Convert response to Json Object
        JsonObject responseObject = JsonUtils.stringToObject( response );

        // Return true if no error in response
        boolean isSuccess = !responseObject.has( AuthConstants.AUTH_RESPONSE_KEY_ERROR )
                && !responseObject.has( AuthConstants.AUTH_RESPONSE_KEY_ERROR_MSG );
        if ( isSuccess ) {
            account.setLastAccessToken( null );
        }
        return isSuccess;
    }
}
