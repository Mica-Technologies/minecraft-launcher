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

import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
import com.micatechnologies.minecraft.launcher.files.LocalPathManager;
import com.micatechnologies.minecraft.launcher.files.Logger;
import net.hycrafthd.minecraft_authenticator.login.AuthenticationFile;
import net.hycrafthd.minecraft_authenticator.login.Authenticator;
import net.hycrafthd.minecraft_authenticator.login.User;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class MCLauncherAuthManager
{
    public static final String CLIENT_ID="e947042e-fc02-42c5-8159-950cde78ea75";
    public static final String AUTH_REDIRECT_URL="https://login.live.com/oauth20_desktop.srf";
    private static final Path SAVED_LOGIN_FILE_PATH = Path.of( LocalPathManager.getRememberedAccountFilePath() );
    private static       User loggedIn              = null;

    public static boolean hasExistingLogin() {
        return Files.isRegularFile( SAVED_LOGIN_FILE_PATH );
    }

    public static User getLoggedInUser() {
        return loggedIn;
    }

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

    public static MCLauncherAuthResult renewExistingLogin() {
        // Try to read and renew login for saved account
        FileInputStream authFileInputStream = null;
        try {
            // Get previous authentication file
            authFileInputStream = new FileInputStream( SAVED_LOGIN_FILE_PATH.toFile() );
            final AuthenticationFile previousAuthFile = AuthenticationFile.readCompressed( authFileInputStream );
            authFileInputStream.close();
            Logger.logDebug( LocalizationManager.REMEMBERED_USER_LOADED_TEXT );

            // Update authentication
            final Authenticator authenticator = Authenticator.of( previousAuthFile ).shouldRetrieveXBoxProfile().shouldAuthenticate().build();
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
            return MCLauncherAuthResult.ERROR_OTHER;
        }

        // Ensure loggedIn was populated as expected and return result
        MCLauncherAuthResult result;
        if ( loggedIn == null ) {
            result = MCLauncherAuthResult.ERROR_LOGIN_EXPIRED;
        }
        else {
            result = new MCLauncherAuthResult( loggedIn );
        }

        return result;
    }

    public static MCLauncherAuthResult loginWithMicrosoftAccount( String authCode, boolean save ) {
        // Try to login with Microsoft
        try {
            // Perform authentication
            final Authenticator authenticator =
                    Authenticator.ofMicrosoft( authCode ).customAzureApplication( CLIENT_ID,AUTH_REDIRECT_URL ).shouldRetrieveXBoxProfile().shouldAuthenticate().build();
//            final Authenticator authenticator =
//                    Authenticator.ofMicrosoft( authCode ).customAzureApplication( clientId,redirectUrl ).shouldRetrieveXBoxProfile().shouldAuthenticate().build();
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
        MCLauncherAuthResult result;
        if ( loggedIn == null ) {
            result = MCLauncherAuthResult.ERROR_BAD_USERNAME_PASSWORD;
        }
        else {
            result = new MCLauncherAuthResult( loggedIn );
        }

        return result;
    }

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

    private static MCLauncherAuthResult processAuthException( Exception e ) {
        MCLauncherAuthResult result;
        if ( checkIfExceptionIsNotBought( e ) ) {
            result = MCLauncherAuthResult.ERROR_NOT_OWNED;
        }
        else if ( checkIfExceptionIsInvalidCredentials( e ) ) {
            result = MCLauncherAuthResult.ERROR_BAD_USERNAME_PASSWORD;
        }
        else if ( checkIfExceptionIsNoValuePresent( e ) ) {
            result = MCLauncherAuthResult.ERROR_NO_VAL;
        }
        else {
            Logger.logWarningSilent( "Failed to login due to an exception while contacting the login service!" );
            Logger.logThrowable( e );
            result = MCLauncherAuthResult.ERROR_OTHER;
        }
        return result;
    }

    private static boolean checkIfExceptionIsNoValuePresent( Exception e ) {
        return e.getMessage().toLowerCase().contains( "no value present" );
    }

    private static boolean checkIfExceptionIsNotBought( Exception e ) {
        return e.getMessage().toLowerCase().contains( "not have bought" );
    }

    private static boolean checkIfExceptionIsInvalidCredentials( Exception e ) {
        return e.getMessage().toLowerCase().contains( "invalid credentials" );
    }
}
