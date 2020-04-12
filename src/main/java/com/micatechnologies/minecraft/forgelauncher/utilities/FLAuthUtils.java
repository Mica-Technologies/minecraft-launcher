package com.micatechnologies.minecraft.forgelauncher.utilities;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.forgelauncher.auth.MCAuthConstants;
import com.micatechnologies.minecraft.forgelauncher.exceptions.FLAuthenticationException;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.net.URL;

/**
 * Class of utility functions to assist with functionality of other MCAuth classes.
 *
 * @author Mica Technologies/hawka97
 * @version 1.1
 */
public class FLAuthUtils {

    /**
     * Perform an HTTP POST to the specified endpoint on the Mojang/Minecraft authentication servers
     * with the specified content.
     *
     * @param endpoint endpoint to use
     * @param content  POST content
     *
     * @return json response
     *
     * @throws FLAuthenticationException if HTTP POST fails
     * @since 1.0
     */
    public static String doHTTPPOST( String endpoint, String content ) throws FLAuthenticationException {
        // Create URL and Connection Objects
        URL httpURL;
        try {
            httpURL = new URL( MCAuthConstants.MC_AUTH_SERVER_URL + "/" + endpoint );
        }
        catch ( MalformedURLException e ) {
            throw new FLAuthenticationException( "Unable to create HTTP URL.", e );
        }
        HttpURLConnection httpURLConnection = null;
        try {
            httpURLConnection = ( HttpURLConnection ) httpURL.openConnection();
        }
        catch ( IOException e ) {
            throw new FLAuthenticationException( "Unable to create HTTP connection.", e );
        }

        // Configure Connection Properties
        try {
            httpURLConnection.setRequestMethod( "POST" );
        }
        catch ( ProtocolException e ) {
            throw new FLAuthenticationException( "Unable to assign HTTP connection type.", e );
        }
        httpURLConnection.setRequestProperty( MCAuthConstants.MC_AUTH_POST_CONTENT_TYPE.getKey(),
                                              MCAuthConstants.MC_AUTH_POST_CONTENT_TYPE
                                                  .getValue() );
        httpURLConnection.setUseCaches( false );
        httpURLConnection.setDoInput( true );
        httpURLConnection.setDoOutput( true );

        // Write Request Content to Connection Stream
        DataOutputStream dataOutputStream;
        try {
            dataOutputStream = new DataOutputStream( httpURLConnection.getOutputStream() );
        }
        catch ( IOException e ) {
            throw new FLAuthenticationException( "Unable to create connection content stream.", e );
        }
        try {
            dataOutputStream.writeBytes( content );
        }
        catch ( IOException e ) {
            throw new FLAuthenticationException( "Failure while writing to connection content stream.", e );
        }
        try {
            dataOutputStream.flush();
        }
        catch ( IOException e ) {
            throw new FLAuthenticationException( "Error while flusing connection content stream.", e );
        }
        try {
            dataOutputStream.close();
        }
        catch ( IOException e ) {
            throw new FLAuthenticationException( "Unable to close connection content stream.", e );
        }

        // Handle/Process Response from Connection if Connection Successful
        try {
            if ( httpURLConnection.getResponseCode() == HttpURLConnection.HTTP_OK ) {
                // Create Input Stream and Reader to Read Response
                InputStream inputStream = null;
                try {
                    inputStream = httpURLConnection.getInputStream();
                }
                catch ( IOException e ) {
                    throw new FLAuthenticationException( "Unable to create connection response stream.", e );
                }
                BufferedReader bufferedReader = new BufferedReader(
                    new InputStreamReader( inputStream ) );

                // Read Response from Buffered Reader
                String line;
                StringBuilder response = new StringBuilder();
                while ( true ) {
                    try {
                        if ( ( line = bufferedReader.readLine() ) == null ) {
                            break;
                        }
                    }
                    catch ( IOException e ) {
                        throw new FLAuthenticationException( "Error while building assembling body.", e );
                    }
                    response.append( line );
                    response.append( '\r' );
                }

                // Close Streams and Readers then Return
                try {
                    bufferedReader.close();
                }
                catch ( IOException e ) {
                    throw new FLAuthenticationException( "Unable to close response reader.", e );
                }
                try {
                    inputStream.close();
                }
                catch ( IOException e ) {
                    throw new FLAuthenticationException( "Unable to close response stream.", e );
                }
                return response.toString();
            }
        }
        catch ( IOException e ) {
            throw new FLAuthenticationException( "Unable to assess connection response.", e );
        }

        // Return Empty Response if Not Successful
        return MCAuthConstants.MC_AUTH_EMPTY_JSON_OBJ_STR;
    }

    /**
     * Convert a JSON Object String to Gson JsonObject.
     *
     * @param jsonString JSON object string
     *
     * @return converted JsonObject
     *
     * @since 1.1
     */
    public static JsonObject stringToJsonObj( String jsonString ) {
        return new Gson().fromJson( jsonString, JsonObject.class );
    }
}
