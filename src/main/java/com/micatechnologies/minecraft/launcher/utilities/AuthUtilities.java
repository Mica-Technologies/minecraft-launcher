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

package com.micatechnologies.minecraft.launcher.utilities;

import com.micatechnologies.minecraft.launcher.consts.AuthConstants;
import com.micatechnologies.minecraft.launcher.exceptions.AuthException;

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
 * @author Mica Technologies
 * @version 1.1
 * @editors hawka97
 * @creator hawka97
 */
public class AuthUtilities
{

    /**
     * Perform an HTTP POST to the specified endpoint on the Mojang/Minecraft authentication servers with the specified
     * content.
     *
     * @param endpoint endpoint to use
     * @param content  POST content
     *
     * @return json response
     *
     * @throws AuthException if HTTP POST fails
     * @since 1.0
     */
    public static String doHTTPPOST( String endpoint, String content ) throws AuthException {
        // Create URL and Connection Objects
        URL httpURL;
        try {
            httpURL = new URL( AuthConstants.AUTH_SERVER_URL + "/" + endpoint );
        }
        catch ( MalformedURLException e ) {
            throw new AuthException( "Unable to create HTTP URL.", e );
        }
        HttpURLConnection httpURLConnection;
        try {
            httpURLConnection = ( HttpURLConnection ) httpURL.openConnection();
        }
        catch ( IOException e ) {
            throw new AuthException( "Unable to create HTTP connection.", e );
        }

        // Configure Connection Properties
        try {
            httpURLConnection.setRequestMethod( "POST" );
        }
        catch ( ProtocolException e ) {
            throw new AuthException( "Unable to assign HTTP connection type.", e );
        }
        httpURLConnection.setRequestProperty( AuthConstants.AUTH_POST_CONTENT_TYPE._1(),
                                              AuthConstants.AUTH_POST_CONTENT_TYPE._2() );
        httpURLConnection.setUseCaches( false );
        httpURLConnection.setDoInput( true );
        httpURLConnection.setDoOutput( true );

        // Write Request Content to Connection Stream
        DataOutputStream dataOutputStream;
        try {
            dataOutputStream = new DataOutputStream( httpURLConnection.getOutputStream() );
        }
        catch ( IOException e ) {
            throw new AuthException( "Unable to create connection content stream.", e );
        }
        try {
            dataOutputStream.writeBytes( content );
        }
        catch ( IOException e ) {
            throw new AuthException( "Failure while writing to connection content stream.", e );
        }
        try {
            dataOutputStream.flush();
        }
        catch ( IOException e ) {
            throw new AuthException( "Error while flusing connection content stream.", e );
        }
        try {
            dataOutputStream.close();
        }
        catch ( IOException e ) {
            throw new AuthException( "Unable to close connection content stream.", e );
        }

        // Handle/Process Response from Connection if Connection Successful
        try {
            if ( httpURLConnection.getResponseCode() == HttpURLConnection.HTTP_OK ) {
                // Create Input Stream and Reader to Read Response
                InputStream inputStream;
                try {
                    inputStream = httpURLConnection.getInputStream();
                }
                catch ( IOException e ) {
                    throw new AuthException( "Unable to create connection response stream.", e );
                }
                BufferedReader bufferedReader = new BufferedReader( new InputStreamReader( inputStream ) );

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
                        throw new AuthException( "Error while building assembling body.", e );
                    }
                    response.append( line );
                    response.append( '\r' );
                }

                // Close Streams and Readers then Return
                try {
                    bufferedReader.close();
                }
                catch ( IOException e ) {
                    throw new AuthException( "Unable to close response reader.", e );
                }
                try {
                    inputStream.close();
                }
                catch ( IOException e ) {
                    throw new AuthException( "Unable to close response stream.", e );
                }
                return response.toString();
            }
        }
        catch ( IOException e ) {
            throw new AuthException( "Unable to assess connection response.", e );
        }

        // Return Empty Response if Not Successful
        return AuthConstants.AUTH_RESPONSE_EMPTY;
    }
}
