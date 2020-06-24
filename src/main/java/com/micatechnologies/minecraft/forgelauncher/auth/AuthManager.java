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

package com.micatechnologies.minecraft.forgelauncher.auth;

import com.micatechnologies.minecraft.forgelauncher.LauncherConstants;
import com.micatechnologies.minecraft.forgelauncher.utilities.annotations.ClientModeOnly;
import com.micatechnologies.minecraft.forgelauncher.utilities.LogUtils;
import org.apache.commons.io.FileUtils;

import java.io.*;

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
@ClientModeOnly
public class AuthManager
{
    /**
     * Variable containing the current logged in game account.
     *
     * @since 1.0
     */
    private static AuthAccount loggedInAccount = null;

    /**
     * Gets and returns the game account that is currently logged in. If a user account is not logged in, an attempt
     * will be made to load a remember user account from persistent storage. If a game account is not loaded, a null
     * value will be returned.
     *
     * @return logged in user account (or null)
     *
     * @since 1.0
     */
    @ClientModeOnly
    public static synchronized AuthAccount getLoggedInAccount() {
        // Attempt to load remember game account from disk if no user is logged in
        if ( loggedInAccount == null ) {
            LogUtils.logDebug( "An account is not logged in. Checking persistent storage for a remembered account..." );
            readAccountFromDisk();
            if ( loggedInAccount == null ) {
                LogUtils.logDebug(
                        "Finished checking persistent storage for a remembered account and was unable to locate one." );
            }
            else {
                LogUtils.logDebug( "Finished checking persistent storage for a remembered account and the account [" +
                                           loggedInAccount.getFriendlyName() + "] was located." );
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
    @ClientModeOnly
    public static synchronized void login( AuthAccount authAccount, boolean remember ) {
        // Store the game account
        loggedInAccount = authAccount;

        // Write game account to disk if option chosen
        if ( remember ) {
            LogUtils.logDebug( "The remember me option was enabled, writing account to persistent storage..." );
            writeAccountToDisk();
            LogUtils.logDebug( "Finished writing account to persistent storage." );
        }
    }

    /**
     * Logs out the currently logged in game account and removes the file on persistent storage that stores a remembered
     * user.
     *
     * @throws IOException if unable to delete remembered user file
     * @since 1.0
     */
    @ClientModeOnly
    public static synchronized void logout() throws IOException {
        // Clear logged in account
        loggedInAccount = null;

        // Delete remember game account persistent file
        deleteRememberedUserFile();
    }

    /**
     * Deletes the file that stores a game account that has been remembered on persistent storage.
     *
     * @throws IOException if unable to delete remembered user file
     * @since 1.0
     */
    @ClientModeOnly
    private static void deleteRememberedUserFile() throws IOException {
        File userDiskFile = new File( LauncherConstants.LAUNCHER_CLIENT_SAVED_USER_FILE );
        FileUtils.forceDelete( userDiskFile );
    }

    /**
     * Reads a remembered game account if present in persistent storage and stores it as the logged in user.
     *
     * @since 1.0
     */
    @ClientModeOnly
    public static void readAccountFromDisk() {
        // Create saved account file object
        File userDiskFile = new File( LauncherConstants.LAUNCHER_CLIENT_SAVED_USER_FILE );

        // Return if saved account file does not exist, otherwise read save account from file
        if ( !userDiskFile.isFile() ) {
            LogUtils.logDebug( "There is no saved account file present on disk. Skipping read from disk." );
        }
        else {
            try {
                // Create saved account file stream
                FileInputStream fileInputStream = new FileInputStream( userDiskFile );

                // Create object input stream from created file input stream and return saved account
                ObjectInputStream objectInputStream = new ObjectInputStream( fileInputStream );
                loggedInAccount = ( AuthAccount ) objectInputStream.readObject();
            }
            catch ( Exception e ) {
                LogUtils.logError(
                        "A problem occurred while reading the saved user account from disk. Login may be required." );
                e.printStackTrace();
            }
        }
    }

    /**
     * Writes the current logged in user to a file in persistent storage.
     *
     * @since 1.0
     */
    @ClientModeOnly
    public static void writeAccountToDisk() {
        try {
            // Create saved account file object and create stream from it
            File userDiskFile = new File( LauncherConstants.LAUNCHER_CLIENT_SAVED_USER_FILE );
            FileOutputStream fileOutputStream = new FileOutputStream( userDiskFile );

            // Create object output stream from created file object stream and save account to file via stream
            ObjectOutputStream objectOutputStream = new ObjectOutputStream( fileOutputStream );
            objectOutputStream.writeObject( loggedInAccount );
        }
        catch ( Exception e ) {
            LogUtils.logError(
                    "A problem occurred while writing the remembered game account to disk. The game account may not be remembered." );
            e.printStackTrace();
        }
    }
}
