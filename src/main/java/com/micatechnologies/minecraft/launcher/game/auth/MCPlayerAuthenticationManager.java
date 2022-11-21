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

import com.micatechnologies.minecraft.launcher.consts.LocalPathConstants;
import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
import com.micatechnologies.minecraft.launcher.files.Logger;
import net.hycrafthd.minecraft_authenticator.login.AuthenticationFile;
import net.hycrafthd.minecraft_authenticator.login.Authenticator;
import net.hycrafthd.minecraft_authenticator.login.User;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Class for managing the authentication/login of players using either a legacy Mojang account, or modern Microsoft
 * account to access Minecraft.
 *
 * @author Mica Technologies
 * @version 2.0
 * @since 2021.4
 */
public class MCPlayerAuthenticationManager
{
    /**
     * The file path where the saved login file is stored.
     *
     * @since 1.0
     */
    private static final Path SAVED_LOGIN_FILE_PATH = LocalPathConstants.SAVED_AUTH_ACCOUNT_FILE_PATH;

    /**
     * The currently logged-in user account.
     *
     * @since 1.0
     */
    private static User loggedIn = null;

    /**
     * Gets a boolean indicating if there is an existing login file.
     *
     * @return true if an existing login file is present, otherwise false
     *
     * @since 1.0
     */
    public static boolean hasExistingLogin() {
        return Files.isRegularFile( SAVED_LOGIN_FILE_PATH );
    }

    /**
     * Gets the {@link User} object of the currently logged-in user.
     *
     * @return {@link User} object of the currently logged-in user
     *
     * @since 1.0
     */
    public static User getLoggedInUser() {
        return loggedIn;
    }

    /**
     * Logs out the currently logged-in user.
     *
     * @since 1.0
     */
    public static void logout() {
        // Clear local logged in file copy
        loggedIn = null;

        // Delete saved user file
        try {
            Files.deleteIfExists( SAVED_LOGIN_FILE_PATH );
        }
        catch ( IOException e ) {
            Logger.logWarningSilent( "Unable to delete saved user account file!" );
            Logger.logThrowable( e );
        }
    }

    /**
     * Renews the existing login if it is present and valid.
     *
     * @return an object indicating the result of the authentication procedure
     *
     * @since 1.0
     */
    public static MCPlayerAuthenticationResult renewExistingLogin() {
        // Try to read and renew login for saved account
        FileInputStream authFileInputStream = null;
        try {
            // Get previous authentication file
            authFileInputStream = new FileInputStream( SAVED_LOGIN_FILE_PATH.toFile() );
            final AuthenticationFile previousAuthFile = AuthenticationFile.readCompressed( authFileInputStream );
            authFileInputStream.close();
            Logger.logDebug( LocalizationManager.REMEMBERED_USER_LOADED_TEXT );

            // Update authentication
            final Authenticator authenticator = Authenticator.of( previousAuthFile ).shouldAuthenticate().build();
            authenticator.run();

            if ( authenticator.getResultFile() != null ) {
                // Get new authentication file and write to disk
                handleAuthFile( authenticator.getResultFile(), true );

                // Store logged in user (fallback to null if no user)
                loggedIn = authenticator.getUser().orElse( null );
            }
        }
        catch ( Exception e ) {
            Logger.logWarningSilent( LocalizationManager.PROBLEM_READING_ACCOUNT_FROM_DISK_TEXT );
            Logger.logThrowable( e );
            if ( authFileInputStream != null ) {
                try {
                    authFileInputStream.close();
                }
                catch ( IOException ex ) {
                    Logger.logWarningSilent( "An error occurred while closing an input stream for reading the saved " +
                                                     "user account file!" );
                    Logger.logThrowable( e );
                }
            }
            return MCPlayerAuthenticationResult.ERROR_OTHER;
        }

        // Ensure loggedIn was populated as expected and return result
        MCPlayerAuthenticationResult result;
        if ( loggedIn == null ) {
            result = MCPlayerAuthenticationResult.ERROR_LOGIN_EXPIRED;
        }
        else {
            result = new MCPlayerAuthenticationResult( loggedIn );
        }

        return result;
    }

    /**
     * Method which handles operations on the stored authentication file, either saving it or removing it.
     *
     * @param authFile authentication file to perform operations on
     * @param save     boolean indicating if the authentication file should be saved, otherwise it is deleted
     *
     * @since 1.0
     */
    private static void handleAuthFile( AuthenticationFile authFile, boolean save ) {
        if ( save ) {
            try {
                Logger.logDebug( LocalizationManager.REMEMBERED_USER_WRITING_TEXT );
                FileOutputStream authFileOutputStream = new FileOutputStream( SAVED_LOGIN_FILE_PATH.toFile() );
                authFile.writeCompressed( authFileOutputStream );
                authFileOutputStream.close();
                Logger.logDebug( LocalizationManager.REMEMBERED_USER_WRITE_FINISHED_TEXT );
            }
            catch ( Exception e ) {
                Logger.logError( LocalizationManager.PROBLEM_WRITING_ACCOUNT_TO_DISK_TEXT );
                Logger.logThrowable( e );
            }
        }
        else {
            try {
                Files.deleteIfExists( SAVED_LOGIN_FILE_PATH );
            }
            catch ( Exception e ) {
                Logger.logWarningSilent( LocalizationManager.UNABLE_REMOVE_USER_FROM_DISK_TEXT );
                Logger.logThrowable( e );
            }
        }
    }

    /**
     * Processes the specified {@link Exception} and returns the corresponding {@link MCPlayerAuthenticationResult}.
     *
     * @param e exception to process
     *
     * @return {@link MCPlayerAuthenticationResult} corresponding to the specified {@link Exception}
     *
     * @since 1.0
     */
    private static MCPlayerAuthenticationResult processAuthException( Exception e ) {
        MCPlayerAuthenticationResult result;
        if ( checkIfExceptionIsNotBought( e ) ) {
            result = MCPlayerAuthenticationResult.ERROR_NOT_OWNED;
        }
        else if ( checkIfExceptionIsInvalidCredentials( e ) ) {
            result = MCPlayerAuthenticationResult.ERROR_BAD_USERNAME_PASSWORD;
        }
        else if ( checkIfExceptionIsNoValuePresent( e ) ) {
            result = MCPlayerAuthenticationResult.ERROR_NO_VAL;
        }
        else {
            Logger.logWarningSilent( "Failed to login due to an exception while contacting the login service!" );
            Logger.logThrowable( e );
            result = MCPlayerAuthenticationResult.ERROR_OTHER;
        }
        return result;
    }

    /**
     * Helper method which returns a boolean indicating if the specified {@link Exception} is a 'no value present'
     * exception.
     *
     * @param e {@link Exception} to check
     *
     * @return boolean indicating if the specified {@link Exception} is a 'no value present' exception
     *
     * @since 1.0
     */
    private static boolean checkIfExceptionIsNoValuePresent( Exception e ) {
        return e.getMessage().toLowerCase().contains( "no value present" );
    }

    /**
     * Helper method which returns a boolean indicating if the specified {@link Exception} is a 'not have bought'
     * exception.
     *
     * @param e {@link Exception} to check
     *
     * @return boolean indicating if the specified {@link Exception} is a 'not have bought' exception
     *
     * @since 1.0
     */
    private static boolean checkIfExceptionIsNotBought( Exception e ) {
        return e.getMessage().toLowerCase().contains( "not have bought" );
    }

    /**
     * Helper method which returns a boolean indicating if the specified {@link Exception} is an 'invalid credentials'
     * exception.
     *
     * @param e {@link Exception} to check
     *
     * @return boolean indicating if the specified {@link Exception} is an 'invalid credentials' exception
     *
     * @since 1.0
     */
    private static boolean checkIfExceptionIsInvalidCredentials( Exception e ) {
        return e.getMessage().toLowerCase().contains( "invalid credentials" );
    }

    /**
     * Performs login using a Microsoft account with the specified <code>authCode</code> and boolean indicating if the
     * user should be saved/remembered ('Remember Me' checkbox).
     *
     * @param authCode Microsoft account authorization code
     * @param save     boolean indicating if the user should be saved/remembered
     *
     * @return authentication result object
     *
     * @since 1.0
     */
    public static MCPlayerAuthenticationResult loginWithMicrosoftAccount( String authCode, boolean save ) {
        // Try to login with Microsoft
        try {
            // Perform authentication
            final Authenticator authenticator = Authenticator.ofMicrosoft( authCode ).shouldAuthenticate().build();
            authenticator.run();

            if ( authenticator.getResultFile() != null ) {
                // Get new authentication file and write to disk if enabled
                handleAuthFile( authenticator.getResultFile(), save );

                // Store logged in user (fallback to null if no user)
                loggedIn = authenticator.getUser().orElse( null );
            }
        }
        catch ( Exception e ) {
            return processAuthException( e );
        }

        // Ensure loggedIn was populated as expected and return result
        MCPlayerAuthenticationResult result;
        if ( loggedIn == null ) {
            result = MCPlayerAuthenticationResult.ERROR_BAD_USERNAME_PASSWORD;
        }
        else {
            result = new MCPlayerAuthenticationResult( loggedIn );
        }

        return result;
    }

    /**
     * Checks the {@link MCPlayerAuthenticationResult}, logs an error if the result is not successful, and returns a
     * boolean indicating if the result is successful.
     *
     * @param authResult authentication result to check
     *
     * @return boolean indicating if the result is successful
     *
     * @since 2.0
     */
    public static boolean checkAuthResponse( MCPlayerAuthenticationResult authResult ) {
        boolean authSuccess = false;
        if ( authResult == MCPlayerAuthenticationResult.ERROR_BAD_USERNAME_PASSWORD ) {
            Logger.logError( "Unable to login: incorrect username/password" );
        }
        else if ( authResult == MCPlayerAuthenticationResult.ERROR_LOGIN_EXPIRED ) {
            Logger.logError( "Unable to login: login expired" );
        }
        else if ( authResult == MCPlayerAuthenticationResult.ERROR_NO_VAL ) {
            Logger.logError( "Unable to login: no value present (Account may not be registered to Xbox Live)" );
        }
        else if ( authResult == MCPlayerAuthenticationResult.ERROR_OTHER ) {
            Logger.logError( "Unable to login: unknown error" );
        }
        else if ( authResult == MCPlayerAuthenticationResult.ERROR_NOT_OWNED ) {
            Logger.logError( "Unable to login: account does not own Minecraft" );
        }
        else {
            authSuccess = true;
        }
        return authSuccess;
    }
}
