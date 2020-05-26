package com.micatechnologies.minecraft.forgelauncher.auth;

import com.micatechnologies.minecraft.forgelauncher.exceptions.FLAuthenticationException;

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
 * Minecraft account object that can be safely serialized for write to or read from file using the built-in class
 * functions.
 *
 * @author Mica Technologies/hawka97
 * @version 2.0
 * @see java.io.Serializable
 */
public class MinecraftAccount implements Serializable {

    /**
     * Class serialization identifier. DO NOT MODIFY. Modification will invalidate all existing serialized instances.
     *
     * @since 1.0
     */
    private static final long serialVersionUID = 0x6f40b3a5d4216db6L;

    /**
     * Account name. For legacy Minecraft accounts, this should be the Minecraft username. For Mojang accounts, this
     * should be the account holders email address.
     *
     * @since 1.0
     */
    private final String accountName;

    /**
     * Account friendly name. This should be the Minecraft username.
     *
     * @since 1.0
     */
    private String friendlyName = null;

    /**
     * Account identifier. Unique and assigned by Minecraft account service.
     *
     * @since 1.0
     */
    private String userIdentifier = null;

    /**
     * Account last access token. This needs to be renewed for each game launch.
     *
     * @since 1.0
     */
    private String lastAccessToken = null;

    /**
     * Create a new MinecraftAccount with specified account name. Token and account information are not populated.
     *
     * @param accountName authentication username
     *
     * @since 1.0
     */
    public MinecraftAccount( final String accountName ) {
        this.accountName = accountName;
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
     * Get the last used access token of this account. Note, this does not include tokens outside of this instance.
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
     * Reads a saved MinecraftAccount from the specified file location.
     *
     * @param fileURL file location
     *
     * @return opened MinecraftAccount file
     *
     * @throws FLAuthenticationException if read fails
     * @since 1.0
     */
    public static MinecraftAccount readFromFile( String fileURL ) throws FLAuthenticationException {
        // Create file input stream and read
        InputStream inputStream;
        InputStream buffer;
        ObjectInput objectInput;
        MinecraftAccount read;
        try {
            inputStream = new FileInputStream( new File( fileURL ) );
            buffer = new BufferedInputStream( inputStream );
            objectInput = new ObjectInputStream( buffer );
            read = ( MinecraftAccount ) objectInput.readObject();
        }
        catch ( IOException | ClassNotFoundException e ) {
            throw new FLAuthenticationException( "Unable to read input stream from specified file.", e );
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
     * Writes a MinecraftAccount object to the specified file location.
     *
     * @param fileURL          file location
     * @param minecraftAccount account to write
     *
     * @throws FLAuthenticationException if write fails
     * @since 1.0
     */
    public static void writeToFile( String fileURL, MinecraftAccount minecraftAccount )
    throws FLAuthenticationException {
        // Verify file exists before writing
        File diskFile = new File( fileURL );
        if ( !diskFile.exists() ) {
            try {
                diskFile.createNewFile();
            }
            catch ( IOException e ) {
                throw new FLAuthenticationException( "writeToFile: Error creating file to write.", e );
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
            objectOutput.writeObject( minecraftAccount );
        }
        catch ( IOException e ) {
            throw new FLAuthenticationException( "writeToFile: Unable to write to file via stream.", e );
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
