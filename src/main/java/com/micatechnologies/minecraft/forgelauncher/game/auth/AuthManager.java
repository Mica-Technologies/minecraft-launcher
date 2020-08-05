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

package com.micatechnologies.minecraft.forgelauncher.game.auth;

import com.micatechnologies.minecraft.forgelauncher.consts.localization.LocalizationManager;
import com.micatechnologies.minecraft.forgelauncher.exceptions.AuthException;
import com.micatechnologies.minecraft.forgelauncher.files.LocalPathManager;
import com.micatechnologies.minecraft.forgelauncher.files.SynchronizedFileManager;
import com.micatechnologies.minecraft.forgelauncher.utilities.FileUtilities;
import com.micatechnologies.minecraft.forgelauncher.files.Logger;
import org.apache.commons.io.FileUtils;

import java.io.*;
import java.util.UUID;

/**
 * Launcher login manager class that handles the login and logout of game accounts as well as storing them and
 * retrieving from a persistent volume.
 *
 * @author Mica Technologies
 * @version 1.0
 * @creator hawka97
 * @editors hawka97
 * @since 1.1
 */
public class AuthManager
{
    /**
     * Variable containing the current logged in game account.
     *
     * @since 1.0
     */
    private static AuthAccount loggedInAccount = null;

    /**
     * Variable containing the client token for the current installation.
     *
     * @since 1.0
     */
    private static String clientToken = null;

    /**
     * Boolean flag indicating if the currently logged in account is remembered (stored on disk).
     *
     * @since 1.0
     */
    private static boolean rememberAccount = false;

    /**
     * Gets and returns the game account that is currently logged in. If a user account is not logged in, an attempt
     * will be made to load a remember user account from persistent storage. If a game account is not loaded, a null
     * value will be returned.
     *
     * @return logged in user account (or null)
     *
     * @since 1.0
     */
    public static synchronized AuthAccount getLoggedInAccount() {
        // Attempt to load remember game account from disk if no user is logged in
        if ( loggedInAccount == null ) {
            Logger.logDebug( LocalizationManager.NO_LOGIN_CHECKING_FOR_SAVED_TEXT );
            readAccountFromDisk();
            if ( loggedInAccount == null ) {
                Logger.logDebug( LocalizationManager.NO_REMEMBERED_ACCOUNT_TEXT );
            }
            else {
                rememberAccount = true;
                Logger.logDebug(
                        LocalizationManager.REMEMBERED_USER_LOADED_TEXT + " " + loggedInAccount.getFriendlyName() );
            }
        }

        // Return logged in game account
        return loggedInAccount;
    }

    /**
     * Sets the specified game account as the currently logged in game account. If the remember me option is
     * enabled/selected/true, the specified game account will also be written to persistent storage.
     *
     * @param authAccount game account to log in
     * @param remember    true if remember me option selected
     *
     * @since 1.0
     */
    public static synchronized void login( AuthAccount authAccount, boolean remember ) {
        // Store the game account
        loggedInAccount = authAccount;
        rememberAccount = remember;

        // Write game account to disk if option chosen
        if ( remember ) {
            Logger.logDebug( LocalizationManager.REMEMBERED_USER_WRITING_TEXT );
            writeAccountToDisk();
            Logger.logDebug( LocalizationManager.REMEMBERED_USER_WRITE_FINISHED_TEXT );
        }
    }

    /**
     * Logs out the currently logged in game account and removes the file on persistent storage that stores a remembered
     * user.
     *
     * @since 1.0
     */
    public static synchronized void logout() {
        // Invalidate user login
        if ( loggedInAccount != null ) {
            try {
                AuthService.invalidateLogin( loggedInAccount );
            }
            catch ( AuthException e ) {
                Logger.logError( LocalizationManager.UNABLE_TO_INVALIDATE_LOGIN_TEXT );
                Logger.logThrowable( e );
            }
        }

        // Clear logged in account
        loggedInAccount = null;

        // Delete remember game account persistent file
        try {
            deleteRememberedUserFile();
        }
        catch ( IOException e ) {
            Logger.logError( LocalizationManager.UNABLE_REMOVE_USER_FROM_DISK_TEXT );
            Logger.logThrowable( e );
        }
    }

    /**
     * Deletes the file that stores a game account that has been remembered on persistent storage.
     *
     * @throws IOException if unable to delete remembered user file
     * @since 1.0
     */
    private static void deleteRememberedUserFile() throws IOException {
        File userDiskFile = SynchronizedFileManager.getSynchronizedFile(
                LocalPathManager.getRememberedAccountFilePath() );
        FileUtils.forceDelete( userDiskFile );
    }

    /**
     * Reads a remembered game account if present in persistent storage and stores it as the logged in user.
     *
     * @since 1.0
     */
    public static void readAccountFromDisk() {
        // Create saved account file object
        File userDiskFile = SynchronizedFileManager.getSynchronizedFile(
                LocalPathManager.getRememberedAccountFilePath() );

        // Return if saved account file does not exist, otherwise read save account from file
        if ( !userDiskFile.isFile() ) {
            Logger.logDebug( LocalizationManager.NO_USER_ON_DISK_SKIPPING_TEXT );
        }
        else {
            try {
                loggedInAccount = ( AuthAccount ) FileUtilities.readAsObject( userDiskFile );
            }
            catch ( Exception e ) {
                Logger.logError( LocalizationManager.PROBLEM_READING_ACCOUNT_FROM_DISK_TEXT );
                Logger.logThrowable( e );
            }
        }
    }

    /**
     * If the currently logged in user account is set to be remembered, write the account to disk.
     *
     * @since 1.0
     */
    public static void writeAccountToDiskIfRemembered() {
        if ( rememberAccount ) {
            writeAccountToDisk();
        }

    }

    /**
     * Writes the current logged in user to a file in persistent storage.
     *
     * @since 1.0
     */
    public static void writeAccountToDisk() {
        try {
            // Create saved account file object and write account
            File userDiskFile = SynchronizedFileManager.getSynchronizedFile(
                    LocalPathManager.getRememberedAccountFilePath() );
            FileUtilities.writeFromObject( loggedInAccount, userDiskFile );
        }
        catch ( Exception e ) {
            Logger.logError( LocalizationManager.PROBLEM_WRITING_ACCOUNT_TO_DISK_TEXT );
            Logger.logThrowable( e );
        }
    }

    /**
     * Gets the client token that is applicable for the current installation.
     *
     * @return client token
     *
     * @since 1.0
     */
    public static String getClientToken() {
        if ( clientToken == null ) {
            Logger.logDebug( LocalizationManager.CLIENT_TOKEN_CHECKING_TEXT );

            // Attempt to read client token from saved file
            final File clientTokenFile = SynchronizedFileManager.getSynchronizedFile(
                    LocalPathManager.getClientTokenFilePath() );
            if ( clientTokenFile.isFile() ) {
                try {
                    clientToken = FileUtils.readFileToString( clientTokenFile, FileUtilities.persistenceCharset );
                }
                catch ( Exception e ) {
                    clientToken = null;
                    Logger.logError( LocalizationManager.UNABLE_READ_STORED_CLIENT_TOKEN_TEXT );
                    Logger.logThrowable( e );
                }
            }

            // Generate new client token if unable to load from saved file and save to file
            if ( clientToken == null ) {
                clientToken = UUID.randomUUID().toString();
                Logger.logStd( LocalizationManager.NEW_CLIENT_TOKEN_TEXT + " " + clientToken );
                try {
                    FileUtils.writeStringToFile( clientTokenFile, clientToken, FileUtilities.persistenceCharset );
                    Logger.logDebug( LocalizationManager.STORED_CLIENT_TOKEN_TEXT );
                }
                catch ( Exception e ) {
                    Logger.logError( LocalizationManager.UNABLE_SAVE_CLIENT_TOKEN_TEXT );
                    Logger.logThrowable( e );
                }
            }

            if ( clientToken != null ) {
                Logger.logDebug( LocalizationManager.LOADED_CLIENT_TOKEN_TEXT + " " + clientToken );
            }
        }

        return clientToken;
    }
}
