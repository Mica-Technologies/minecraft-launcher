package com.micatechnologies.minecraft.authlib;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInput;
import java.io.ObjectInputStream;
import java.io.ObjectOutput;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.Serializable;

/**
 * Minecraft Authentication Account object.
 * <p>
 * MCAuthAccount objects can be safely serialized. Read from file and write to file are functions of
 * this class.
 *
 * @author Mica Technologies/hawka97
 * @version 1.1
 * @see java.io.Serializable
 */
public class MCAuthAccount implements Serializable {

    /**
     * Class serialization identifier. DO NOT MODIFY. Modification will invalidate all existing
     * serialized instances.
     */
    private static final long   serialVersionUID = 0x6f40b3a5d4216db6L;

    /**
     * Account name. For legacy Minecraft accounts, this should be the Minecraft username. For
     * Mojang accounts, this should be the account holders email address.
     */
    private final        String accountName;

    /**
     * Account friendly name. This should be the Minecraft username.
     */
    private              String friendlyName     = null;

    /**
     * Account identifier. Unique and assigned by Minecraft/Mojang.
     */
    private              String userIdentifier   = null;

    /**
     * Account last access token. This needs to renew for each game launch.
     */
    private              String lastAccessToken  = null;

    /**
     * Create a new MCAuthAccount with specified account name. Token and account information are not
     * populated.
     *
     * @param accountName authentication username
     *
     * @since 1.0
     */
    public MCAuthAccount( final String accountName ) {
        this.accountName = accountName;
    }

    /**
     * Create a new MCAuthAccount with specified account name, then automatically retrieve token and
     * account information using supplied password and client token.
     *
     * @param accountName     authentication username
     * @param accountPassword authentication password
     * @param clientToken     client token
     *
     * @since 1.1
     */
    public MCAuthAccount( final String accountName, final String accountPassword,
                          final String clientToken ) throws MCAuthException {
        this.accountName = accountName;
        MCAuthService.usernamePasswordAuth( this, accountPassword, clientToken );
    }

    /**
     * Get the name of this account.
     *
     * @return account name
     *
     * @since 1.0
     */
    public String getAccountName() {
        return this.accountName;
    }

    /**
     * Get the last used access token of this account. Note, this does not include tokens outside of
     * this instance.
     *
     * @return last access token
     *
     * @since 1.0
     */
    public String getLastAccessToken() {
        return this.lastAccessToken;
    }

    /**
     * Get the friendly name of this account.
     *
     * @return friendly name
     *
     * @since 1.0
     */
    public String getFriendlyName() {
        return this.friendlyName;
    }

    /**
     * Get the user identifier of this account.
     *
     * @return user identifier
     *
     * @since 1.0
     */
    public String getUserIdentifier() {
        return userIdentifier;
    }

    /**
     * Set the friendly name of this account. Non-external method.
     *
     * @param friendlyName new friendly name
     *
     * @since 1.0
     */
    void setFriendlyName( final String friendlyName ) {
        this.friendlyName = friendlyName;
    }

    /**
     * Set the last used access token of this account. Non-external method.
     *
     * @param lastAccessToken last used access token
     *
     * @since 1.0
     */
    void setLastAccessToken( final String lastAccessToken ) {
        this.lastAccessToken = lastAccessToken;
    }

    /**
     * Set the user identifier of this account. Non-external method.
     *
     * @param userIdentifier user identifier
     *
     * @since 1.0
     */
    void setUserIdentifier( final String userIdentifier ) {
        this.userIdentifier = userIdentifier;
    }

    /**
     * Reads a saved MCAuthAccount from the specified file location.
     *
     * @param fileURL file location
     *
     * @return opened MCAuthAccount file
     *
     * @throws MCAuthException if read fails
     * @since 1.0
     */
    public static MCAuthAccount readFromFile( String fileURL ) throws MCAuthException {
        // Create file input stream and read
        InputStream inputStream;
        InputStream buffer;
        ObjectInput objectInput;
        MCAuthAccount read;
        try {
            inputStream = new FileInputStream( new File( fileURL ) );
            buffer = new BufferedInputStream( inputStream );
            objectInput = new ObjectInputStream( buffer );
            read = ( MCAuthAccount ) objectInput.readObject();
        }
        catch ( IOException | ClassNotFoundException e ) {
            throw new MCAuthException( "Unable to read input stream from specified file.", e );
        }

        // Close streams and return
        try {
            objectInput.close();
            buffer.close();
            inputStream.close();
        }
        catch ( IOException e ) {
            System.out.println( "readFromFile: unable to close streams." );
        }

        return read;
    }

    /**
     * Writes an MCAuthAccount object to the specified file location.
     *
     * @param fileURL       file location
     * @param mcAuthAccount account to write
     *
     * @throws MCAuthException if write fails
     * @since 1.0
     */
    public static void writeToFile( String fileURL, MCAuthAccount mcAuthAccount )
        throws MCAuthException {
        // Verify file exists before writing
        File diskFile = new File( fileURL );
        if ( !diskFile.exists() ) {
            try {
                diskFile.createNewFile();
            }
            catch ( IOException e ) {
                throw new MCAuthException( "writeToFile: Error creating file to write.", e );
            }
        }

        // Create file output stream and write
        OutputStream outputStream;
        OutputStream buffer;
        ObjectOutput objectOutput;
        try {
            outputStream = new FileOutputStream( diskFile );
            buffer = new BufferedOutputStream( outputStream );
            objectOutput = new ObjectOutputStream( buffer );
            objectOutput.writeObject( mcAuthAccount );
        }
        catch ( IOException e ) {
            throw new MCAuthException( "writeToFile: Unable to write to file via stream.", e );
        }

        // Close streams and return
        try {
            objectOutput.close();
            buffer.close();
            outputStream.close();
        }
        catch ( IOException e ) {
            System.out.println( "writeToFile: unable to close streams." );
        }
    }
}
