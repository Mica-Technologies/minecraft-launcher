package com.micatechnologies.minecraft.forgelauncher.auth;

import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.forgelauncher.exceptions.FLAuthenticationException;
import com.micatechnologies.minecraft.forgelauncher.utilities.FLAuthUtils;

/**
 * Class for interacting with Mojang/Minecraft authentication server endpoints using MCAuthAccount
 * objects.
 *
 * @author Mica Technologies/hawka97
 * @version 1.1
 * @see MCAuthAccount
 */
public class MCAuthService {

    /**
     * Perform authentication for the specified account using the specified password and client
     * token.
     *
     * @param account     account to authenticate
     * @param password    password to authenticate with
     * @param clientToken persistent client/device token
     *
     * @return true for success, false for failure
     *
     * @throws FLAuthenticationException if an error occurs
     * @since 1.0
     */
    public static boolean usernamePasswordAuth( MCAuthAccount account, String password,
                                                String clientToken ) throws FLAuthenticationException {
        // Build JSON Objects for Request
        JsonObject root = new JsonObject();
        JsonObject agent = new JsonObject();

        // Populate agent object
        agent.addProperty( MCAuthConstants.MC_AUTH_AGENT_NAME.getKey(),
                           MCAuthConstants.MC_AUTH_AGENT_NAME.getValue() );
        agent.addProperty( MCAuthConstants.MC_AUTH_AGENT_VERSION.getKey(),
                           MCAuthConstants.MC_AUTH_AGENT_VERSION.getValue() );

        // Populate root object
        root.add( MCAuthConstants.MC_AUTH_ENDPOINT_AGENT_KEY, agent );
        root.addProperty( MCAuthConstants.MC_AUTH_ENDPOINT_USERNAME_KEY, account.getAccountName() );
        root.addProperty( MCAuthConstants.MC_AUTH_ENDPOINT_PASSWORD_KEY, password );
        root.addProperty( MCAuthConstants.MC_AUTH_ENDPOINT_CLIENT_TOKEN_KEY, clientToken );

        // Perform HTTP Post Call to Mojang Endpoint
        String response = FLAuthUtils.doHTTPPOST( MCAuthConstants.MC_AUTH_PASSWORD_ENDPOINT,
                                                  root.toString() );

        // Convert response to Json Object
        JsonObject responseObject = FLAuthUtils.stringToJsonObj( response );

        // Process response only if error or error message not present
        if ( !responseObject.has( MCAuthConstants.MC_AUTH_RESPONSE_ERROR_KEY ) && !responseObject
                .has( MCAuthConstants.MC_AUTH_RESPONSE_ERROR_MSG_KEY ) ) {
            // Read and save acquired access token
            if ( responseObject.has( MCAuthConstants.MC_AUTH_ENDPOINT_ACCESS_TOKEN_KEY ) ) {
                account.setLastAccessToken(
                        responseObject.get( MCAuthConstants.MC_AUTH_ENDPOINT_ACCESS_TOKEN_KEY )
                                      .getAsString() );
            }
            else {
                throw new FLAuthenticationException( "Unable to process token from Mojang response." );
            }

            // Read and save profile name and id, if present
            // If not present, method will continue
            if ( responseObject.has( MCAuthConstants.MC_AUTH_RESPONSE_SELECTED_PROFILE_KEY ) ) {
                if ( responseObject.getAsJsonObject(
                        MCAuthConstants.MC_AUTH_RESPONSE_SELECTED_PROFILE_KEY ).has(
                        MCAuthConstants.MC_AUTH_RESPONSE_SELECTED_PROFILE_NAME_KEY ) ) {
                    account.setFriendlyName( responseObject.getAsJsonObject(
                            MCAuthConstants.MC_AUTH_RESPONSE_SELECTED_PROFILE_KEY ).get(
                            MCAuthConstants.MC_AUTH_RESPONSE_SELECTED_PROFILE_NAME_KEY )
                                                           .getAsString() );
                }
                if ( responseObject.getAsJsonObject(
                        MCAuthConstants.MC_AUTH_RESPONSE_SELECTED_PROFILE_KEY ).has(
                        MCAuthConstants.MC_AUTH_RESPONSE_SELECTED_PROFILE_ID_KEY ) ) {
                    account.setUserIdentifier( responseObject.getAsJsonObject(
                            MCAuthConstants.MC_AUTH_RESPONSE_SELECTED_PROFILE_KEY ).get(
                            MCAuthConstants.MC_AUTH_RESPONSE_SELECTED_PROFILE_ID_KEY ).getAsString() );
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
     * Refresh the authentication for the specified account by renewing the last access token. The
     * account must have a last access token.
     *
     * @param account     account to authenticate
     * @param clientToken persistent client/device token
     *
     * @return true for success, false for failure
     *
     * @throws FLAuthenticationException if an error occurs
     * @since 1.0
     */
    public static boolean refreshAuth( MCAuthAccount account, String clientToken )
    throws FLAuthenticationException {
        // Check for presence of existing access token
        // If none, return immediately
        if ( account.getLastAccessToken() == null ) {
            return false;
        }

        // Build JSON Object for Request
        JsonObject root = new JsonObject();
        root.addProperty( MCAuthConstants.MC_AUTH_ENDPOINT_ACCESS_TOKEN_KEY,
                          account.getLastAccessToken() );
        root.addProperty( MCAuthConstants.MC_AUTH_ENDPOINT_CLIENT_TOKEN_KEY, clientToken );

        // Perform HTTP Post Call to Mojang Endpoint
        String response = FLAuthUtils.doHTTPPOST( MCAuthConstants.MC_AUTH_REFRESH_TOKEN_ENDPOINT,
                                                  root.toString() );

        // Convert response to Json Object
        JsonObject responseObject = FLAuthUtils.stringToJsonObj( response );

        // Process response only if error or error message not present
        if ( !responseObject.has( MCAuthConstants.MC_AUTH_RESPONSE_ERROR_KEY ) && !responseObject
                .has( MCAuthConstants.MC_AUTH_RESPONSE_ERROR_MSG_KEY ) ) {
            // Read and save acquired access token
            if ( responseObject.has( MCAuthConstants.MC_AUTH_ENDPOINT_ACCESS_TOKEN_KEY ) ) {
                account.setLastAccessToken(
                        responseObject.get( MCAuthConstants.MC_AUTH_ENDPOINT_ACCESS_TOKEN_KEY )
                                      .getAsString() );
            }
            else {
                throw new FLAuthenticationException( "Unable to process token from Mojang response." );
            }

            // Read and save profile name and id, if present
            // If not present, method will continue
            if ( responseObject.has( MCAuthConstants.MC_AUTH_RESPONSE_SELECTED_PROFILE_KEY ) ) {
                if ( responseObject.getAsJsonObject(
                        MCAuthConstants.MC_AUTH_RESPONSE_SELECTED_PROFILE_KEY ).has(
                        MCAuthConstants.MC_AUTH_RESPONSE_SELECTED_PROFILE_NAME_KEY ) ) {
                    account.setFriendlyName( responseObject.getAsJsonObject(
                            MCAuthConstants.MC_AUTH_RESPONSE_SELECTED_PROFILE_KEY ).get(
                            MCAuthConstants.MC_AUTH_RESPONSE_SELECTED_PROFILE_NAME_KEY )
                                                           .getAsString() );
                }
                if ( responseObject.getAsJsonObject(
                        MCAuthConstants.MC_AUTH_RESPONSE_SELECTED_PROFILE_KEY ).has(
                        MCAuthConstants.MC_AUTH_RESPONSE_SELECTED_PROFILE_ID_KEY ) ) {
                    account.setUserIdentifier( responseObject.getAsJsonObject(
                            MCAuthConstants.MC_AUTH_RESPONSE_SELECTED_PROFILE_KEY ).get(
                            MCAuthConstants.MC_AUTH_RESPONSE_SELECTED_PROFILE_ID_KEY ).getAsString() );
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
     * @throws FLAuthenticationException if an error occurs
     * @since 1.0
     */
    public static boolean validateLogin( MCAuthAccount account, String clientToken )
    throws FLAuthenticationException {
        // Check for presence of existing access token
        // If none, return immediately
        if ( account.getLastAccessToken() == null ) {
            return false;
        }

        // Build JSON Object for Request
        JsonObject root = new JsonObject();
        root.addProperty( MCAuthConstants.MC_AUTH_ENDPOINT_ACCESS_TOKEN_KEY,
                          account.getLastAccessToken() );
        root.addProperty( MCAuthConstants.MC_AUTH_ENDPOINT_CLIENT_TOKEN_KEY, clientToken );

        // Perform HTTP Post Call to Mojang Endpoint
        String response = FLAuthUtils.doHTTPPOST( MCAuthConstants.MC_AUTH_VALIDATE_TOKEN_ENDPOINT,
                                                  root.toString() );

        // Convert response to Json Object
        JsonObject responseObject = FLAuthUtils.stringToJsonObj( response );

        // Return true if no error in response
        return !responseObject.has( MCAuthConstants.MC_AUTH_RESPONSE_ERROR_KEY ) && !responseObject
                .has( MCAuthConstants.MC_AUTH_RESPONSE_ERROR_MSG_KEY );
    }

    /**
     * Invalidate the authentication of the specified account.
     *
     * @param account     account to invalidate
     * @param clientToken persistent client/device token
     *
     * @return true for success, false for failure
     *
     * @throws FLAuthenticationException if an error occurs
     * @since 1.0
     */
    public static boolean invalidateLogin( MCAuthAccount account, String clientToken )
    throws FLAuthenticationException {
        // Check for presence of existing access token
        // If none, return immediately
        if ( account.getLastAccessToken() == null ) {
            return false;
        }

        // Build JSON Object for Request
        JsonObject root = new JsonObject();
        root.addProperty( MCAuthConstants.MC_AUTH_ENDPOINT_ACCESS_TOKEN_KEY,
                          account.getLastAccessToken() );
        root.addProperty( MCAuthConstants.MC_AUTH_ENDPOINT_CLIENT_TOKEN_KEY, clientToken );

        // Perform HTTP Post Call to Mojang Endpoint
        String response = FLAuthUtils.doHTTPPOST( MCAuthConstants.MC_AUTH_INVALIDATE_TOKEN_ENDPOINT,
                                                  root.toString() );

        // Convert response to Json Object
        JsonObject responseObject = FLAuthUtils.stringToJsonObj( response );

        // Return true if no error in response
        boolean isSuccess = !responseObject.has( MCAuthConstants.MC_AUTH_RESPONSE_ERROR_KEY )
                && !responseObject.has( MCAuthConstants.MC_AUTH_RESPONSE_ERROR_MSG_KEY );
        if ( isSuccess ) {
            account.setLastAccessToken( null );
        }
        return isSuccess;
    }
}
