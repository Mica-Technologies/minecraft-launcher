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

package com.micatechnologies.minecraft.launcher.consts.localization;

import java.util.ResourceBundle;

/**
 * Class for managing the application localization components and access to the display string resources manifest in
 * multiple languages.
 *
 * @author Mica Technologies
 * @version 1.0
 * @creator hawka97
 * @editors hawka97
 * @since 1.0
 */
public class LocalizationManager
{
    /**
     * The base name of the display strings local resource bundle collection.
     *
     * @since 1.0
     */
    private static final String localResourceBundleDisplayStringsBaseName = "lang.DisplayStrings";

    /**
     * The resource bundle for the display strings collection in the current locale.
     *
     * @since 1.0
     */
    private static final ResourceBundle localResourceBundle = ResourceBundle.getBundle(
            localResourceBundleDisplayStringsBaseName );

    /**
     * The initial value of the upper label on the progress window when fetching mod pack information.
     *
     * @since 1.0
     */
    public static final String MODPACK_INSTALL_FETCH_UPPER_LABEL = localResourceBundle.getString(
            "MODPACK_INSTALL_FETCH_UPPER_LABEL" );

    /**
     * The initial value of the lower label on the progress window when fetching mod pack information.
     *
     * @since 1.0
     */
    public static final String MODPACK_INSTALL_FETCH_LOWER_LABEL = localResourceBundle.getString(
            "MODPACK_INSTALL_FETCH_LOWER_LABEL" );

    /**
     * The initial value of the upper label on the progress window when installing or updating the runtime.
     *
     * @since 1.0
     */
    public static final String RUNTIME_INSTALL_PROGRESS_UPPER_LABEL = localResourceBundle.getString(
            "RUNTIME_INSTALL_PROGRESS_UPPER_LABEL" );

    /**
     * The initial value of the lower label on the progress window when installing or updating the runtime.
     *
     * @since 1.0
     */
    public static final String RUNTIME_INSTALL_PROGRESS_LOWER_LABEL = localResourceBundle.getString(
            "RUNTIME_INSTALL_PROGRESS_LOWER_LABEL" );

    /**
     * The progress text shown when an asset has been verified.
     *
     * @since 1.0
     */
    public static final String VERIFIED_ASSET_PROGRESS_TEXT = localResourceBundle.getString(
            "VERIFIED_ASSET_PROGRESS_TEXT" );

    /**
     * The error message shown when a configuration file is found but cannot be loaded and will be reset.
     *
     * @since 1.0
     */
    public static final String CONFIG_EXISTS_CORRUPT_RESET_ERROR_TEXT = localResourceBundle.getString(
            "CONFIG_EXISTS_CORRUPT_RESET_ERROR_TEXT" );

    /**
     * The message shown when the configuration has been successfully reset.
     *
     * @since 1.0
     */
    public static final String CONFIG_RESET_SUCCESS_TEXT = localResourceBundle.getString( "CONFIG_RESET_SUCCESS_TEXT" );

    /**
     * The message shown when the configuration cannot be saved because it has not been loaded.
     *
     * @since 1.0
     */
    public static final String CONFIG_NOT_LOADED_CANT_SAVE_ERROR_TEXT = localResourceBundle.getString(
            "CONFIG_NOT_LOADED_CANT_SAVE_ERROR_TEXT" );

    /**
     * The message shown when an error prevents the configuration from being saved.
     *
     * @since 1.0
     */
    public static final String CONFIG_SAVE_ERROR_TEXT = localResourceBundle.getString( "CONFIG_SAVE_ERROR_TEXT" );

    /**
     * The prefix of the message shown when the game mode is being set.
     *
     * @since 1.0
     */
    public static final String GAME_MODE_BEING_SET_TO_TEXT = localResourceBundle.getString(
            "GAME_MODE_BEING_SET_TO_TEXT" );

    /**
     * The prefix of the message shown when the game mode is being automatically set by inference.
     *
     * @since 1.0
     */
    public static final String GAME_MODE_INFERRED_SET_TO_TEXT = localResourceBundle.getString(
            "GAME_MODE_INFERRED_SET_TO_TEXT" );

    /**
     * The prefix of all error logs that are submitted via {@link com.micatechnologies.minecraft.launcher.files.Logger#logError(String)}.
     *
     * @since 1.0
     */
    public static final String LOG_ERROR_PREFIX = localResourceBundle.getString( "LOG_ERROR_PREFIX" );

    /**
     * The prefix of all warning logs that are submitted via {@link com.micatechnologies.minecraft.launcher.files.Logger#logWarning(String)}.
     *
     * @since 1.0
     */
    public static final String LOG_WARNING_PREFIX = localResourceBundle.getString( "LOG_WARNING_PREFIX" );

    /**
     * The prefix of all standard logs that are submitted via {@link com.micatechnologies.minecraft.launcher.files.Logger#logStd(String)}.
     *
     * @since 1.0
     */
    public static final String LOG_STANDARD_PREFIX = localResourceBundle.getString( "LOG_STANDARD_PREFIX" );

    /**
     * The prefix of all debug logs that are submitted via {@link com.micatechnologies.minecraft.launcher.files.Logger#logDebug(String)}.
     *
     * @since 1.0
     */
    public static final String LOG_DEBUG_PREFIX = localResourceBundle.getString( "LOG_DEBUG_PREFIX" );

    /**
     * The message shown when the log file directory was not created by the logging subsystem.
     *
     * @since 1.0
     */
    public static final String LOG_FILE_DIR_NOT_CREATED_TEXT = localResourceBundle.getString(
            "LOG_FILE_DIR_NOT_CREATED_TEXT" );

    /**
     * The message shown when a log file was not created by the logging subsystem.
     *
     * @since 1.0
     */
    public static final String LOG_FILE_NOT_CREATED_TEXT = localResourceBundle.getString( "LOG_FILE_NOT_CREATED_TEXT" );

    /**
     * The message shown when the logging subsystem has been initialized.
     *
     * @since 1.0
     */
    public static final String LOG_SYSTEM_INITIALIZED_TEXT = localResourceBundle.getString(
            "LOG_SYSTEM_INITIALIZED_TEXT" );

    /**
     * The message shown when the runtime installation folder is being verified.
     *
     * @since 1.0
     */
    public static final String VERIFYING_RUNTIME_INSTALL_FOLDER_TEXT = localResourceBundle.getString(
            "VERIFYING_RUNTIME_INSTALL_FOLDER_TEXT" );

    /**
     * Message shown when the runtime folder has been created.
     *
     * @since 1.0
     */
    public static final String CREATED_FOLDER_RUNTIME_TEXT = localResourceBundle.getString(
            "CREATED_FOLDER_RUNTIME_TEXT" );

    /**
     * Message shown when the runtime folder has not been created.
     *
     * @since 1.0
     */
    public static final String DIDNT_CREATE_FOLDER_RUNTIME_TEXT = localResourceBundle.getString(
            "DIDNT_CREATE_FOLDER_RUNTIME_TEXT" );

    /**
     * Message shown when the runtime folder has been set as readable.
     *
     * @since 1.0
     */
    public static final String RUNTIME_FOLDER_SET_READABLE_TEXT = localResourceBundle.getString(
            "RUNTIME_FOLDER_SET_READABLE_TEXT" );

    /**
     * Message shown when the runtime folder has not been set as readable.
     *
     * @since 1.0
     */
    public static final String DIDNT_SET_RUNTIME_FOLDER_READABLE_TEXT = localResourceBundle.getString(
            "DIDNT_SET_RUNTIME_FOLDER_READABLE_TEXT" );

    /**
     * Message shown when the runtime folder has been set as writable.
     *
     * @since 1.0
     */
    public static final String RUNTIME_FOLDER_SET_WRITABLE_TEXT = localResourceBundle.getString(
            "RUNTIME_FOLDER_SET_WRITABLE_TEXT" );

    /**
     * Message shown when the runtime folder has not been set as writable.
     *
     * @since 1.0
     */
    public static final String DIDNT_SET_RUNTIME_FOLDER_WRITABLE_TEXT = localResourceBundle.getString(
            "DIDNT_SET_RUNTIME_FOLDER_WRITABLE_TEXT" );

    /**
     * Status message shown when gathering information about the runtime.
     *
     * @since 1.0
     */
    public static final String GATHERING_RUNTIME_INFO_TEXT = localResourceBundle.getString(
            "GATHERING_RUNTIME_INFO_TEXT" );

    /**
     * Message shown when downloading a runtime for an unidentified/unhandled operating system.
     *
     * @since 1.0
     */
    public static final String UNIDENTIFIED_OS_RUNTIME_TEXT = localResourceBundle.getString(
            "UNIDENTIFIED_OS_RUNTIME_TEXT" );

    /**
     * Message shown when downloading the checksum of the runtime.
     *
     * @since 1.0
     */
    public static final String DOWNLOADING_RUNTIME_CHECKSUM_TEXT = localResourceBundle.getString(
            "DOWNLOADING_RUNTIME_CHECKSUM_TEXT" );

    /**
     * Message shown when a failure occurs during the download of the runtime checksum.
     *
     * @since 1.0
     */
    public static final String RUNTIME_CHECKSUM_DOWNLOAD_FAIL_TEXT = localResourceBundle.getString(
            "RUNTIME_CHECKSUM_DOWNLOAD_FAIL_TEXT" );

    /**
     * Message shown when the local runtime is being verified.
     *
     * @since 1.0
     */
    public static final String VERIFYING_LOCAL_RUNTIME_TEXT = localResourceBundle.getString(
            "VERIFYING_LOCAL_RUNTIME_TEXT" );

    /**
     * Message shown when the local runtime is being downloaded.
     *
     * @since 1.0
     */
    public static final String DOWNLOADING_RUNTIME_TEXT = localResourceBundle.getString( "DOWNLOADING_RUNTIME_TEXT" );

    /**
     * Message shown when the local runtime has been downloaded successfully.
     *
     * @since 1.0
     */
    public static final String DOWNLOADED_RUNTIME_SUCCESS_TEXT = localResourceBundle.getString(
            "DOWNLOADED_RUNTIME_SUCCESS_TEXT" );

    /**
     * Message shown when the local runtime environment is being cleaned (emptied).
     *
     * @since 1.0
     */
    public static final String CLEANING_RUNTIME_ENV_TEXT = localResourceBundle.getString( "CLEANING_RUNTIME_ENV_TEXT" );

    /**
     * Message shown when the local runtime is being extracted to the runtime environment.
     *
     * @since 1.0
     */
    public static final String EXTRACTING_RUNTIME_TO_ENV_TEXT = localResourceBundle.getString(
            "EXTRACTING_RUNTIME_TO_ENV_TEXT" );

    /**
     * Message shown when the local runtime is unable to be downloaded.
     *
     * @since 1.0
     */
    public static final String UNABLE_DOWNLOAD_RUNTIME_TEXT = localResourceBundle.getString(
            "UNABLE_DOWNLOAD_RUNTIME_TEXT" );

    /**
     * Completion text shown when a task or process has been completed.
     *
     * @since 1.0
     */
    public static final String COMPLETED_TEXT = localResourceBundle.getString( "COMPLETED_TEXT" );

    /**
     * Message shown when the application is unable to wait for a progress window to cleanly finish and close before
     * returning to the parent/calling task.
     *
     * @since 1.0
     */
    public static final String UNABLE_WAIT_FOR_PROGRESS_WINDOW_TEXT = localResourceBundle.getString(
            "UNABLE_WAIT_FOR_PROGRESS_WINDOW_TEXT" );

    /**
     * Message shown when a user account is not logged in and the application is going to check for a remembered user
     * account on persistent storage.
     *
     * @since 1.0
     */
    public static final String NO_LOGIN_CHECKING_FOR_SAVED_TEXT = localResourceBundle.getString(
            "NO_LOGIN_CHECKING_FOR_SAVED_TEXT" );

    /**
     * Message shown when a user account was not remembered or could not be found on persistent storage.
     *
     * @since 1.0
     */
    public static final String NO_REMEMBERED_ACCOUNT_TEXT = localResourceBundle.getString(
            "NO_REMEMBERED_ACCOUNT_TEXT" );

    /**
     * Message prefix shown when a user account was loaded from persistent storage.
     *
     * @since 1.0
     */
    public static final String REMEMBERED_USER_LOADED_TEXT = localResourceBundle.getString(
            "REMEMBERED_USER_LOADED_TEXT" );

    /**
     * Message shown when the remember me option was selected, and the account is being saved to storage.
     *
     * @since 1.0
     */
    public static final String REMEMBERED_USER_WRITING_TEXT = localResourceBundle.getString(
            "REMEMBERED_USER_WRITING_TEXT" );

    /**
     * Message shown when the remembered account was successfully saved to storage.
     *
     * @since 1.0
     */
    public static final String REMEMBERED_USER_WRITE_FINISHED_TEXT = localResourceBundle.getString(
            "REMEMBERED_USER_WRITE_FINISHED_TEXT" );

    /**
     * Message shown when the access token of a user account could not be invalidated.
     *
     * @since 1.0
     */
    public static final String UNABLE_TO_INVALIDATE_LOGIN_TEXT = localResourceBundle.getString(
            "UNABLE_TO_INVALIDATE_LOGIN_TEXT" );

    /**
     * Message shown when the saved user account file cannot be deleted from disk.
     *
     * @since 1.0
     */
    public static final String UNABLE_REMOVE_USER_FROM_DISK_TEXT = localResourceBundle.getString(
            "UNABLE_REMOVE_USER_FROM_DISK_TEXT" );

    /**
     * Message shown when a saved user account is not present on the disk, and loading one will be skipped.
     *
     * @since 1.0
     */
    public static final String NO_USER_ON_DISK_SKIPPING_TEXT = localResourceBundle.getString(
            "NO_USER_ON_DISK_SKIPPING_TEXT" );

    /**
     * Message shown when a problem prevents a saved user account from being read from the disk.
     *
     * @since 1.0
     */
    public static final String PROBLEM_READING_ACCOUNT_FROM_DISK_TEXT = localResourceBundle.getString(
            "PROBLEM_READING_ACCOUNT_FROM_DISK_TEXT" );

    /**
     * Message shown when a problem prevents a saved user account from being written to disk.
     *
     * @since 1.0
     */
    public static final String PROBLEM_WRITING_ACCOUNT_TO_DISK_TEXT = localResourceBundle.getString(
            "PROBLEM_WRITING_ACCOUNT_TO_DISK_TEXT" );

    /**
     * Message shown when the client token has not been loading and the application is checking for a stored token.
     *
     * @since 1.0
     */
    public static final String CLIENT_TOKEN_CHECKING_TEXT = localResourceBundle.getString(
            "CLIENT_TOKEN_CHECKING_TEXT" );

    /**
     * Message shown when a stored client token could not be read.
     *
     * @since 1.0
     */
    public static final String UNABLE_READ_STORED_CLIENT_TOKEN_TEXT = localResourceBundle.getString(
            "UNABLE_READ_STORED_CLIENT_TOKEN_TEXT" );

    /**
     * Message shown when a new client token has been generated.
     *
     * @since 1.0
     */
    public static final String NEW_CLIENT_TOKEN_TEXT = localResourceBundle.getString( "NEW_CLIENT_TOKEN_TEXT" );

    /**
     * Message shown when the client token has been written to file.
     *
     * @since 1.0
     */
    public static final String STORED_CLIENT_TOKEN_TEXT = localResourceBundle.getString( "STORED_CLIENT_TOKEN_TEXT" );

    /**
     * Message shown when the client token could not be saved to file.
     *
     * @since 1.0
     */
    public static final String UNABLE_SAVE_CLIENT_TOKEN_TEXT = localResourceBundle.getString(
            "UNABLE_SAVE_CLIENT_TOKEN_TEXT" );

    /**
     * Message shown when the client token has been loaded.
     *
     * @since 1.0
     */
    public static final String LOADED_CLIENT_TOKEN_TEXT = localResourceBundle.getString( "LOADED_CLIENT_TOKEN_TEXT" );

    /**
     * Message shown when an access token could not be found in an authentication response.
     *
     * @since 1.0
     */
    public static final String AUTH_RESPONSE_NO_ACCESS_TOKEN_TEXT = localResourceBundle.getString(
            "AUTH_RESPONSE_NO_ACCESS_TOKEN_TEXT" );

    /**
     * Message shown when an authentication response did not include a game/user profile name.
     *
     * @since 1.0
     */
    public static final String AUTH_RESPONSE_NO_PROFILE_NAME_TEXT = localResourceBundle.getString(
            "AUTH_RESPONSE_NO_PROFILE_NAME_TEXT" );

    /**
     * Message shown when an authentication response did not include a game/user profile ID.
     *
     * @since 1.0
     */
    public static final String AUTH_RESPONSE_NO_PROFILE_ID_TEXT = localResourceBundle.getString(
            "AUTH_RESPONSE_NO_PROFILE_ID_TEXT" );

    /**
     * Message shown when the application is unable to install a mod pack from the specified location.
     *
     * @since 1.0
     */
    public static final String UNABLE_TO_INSTALL_MOD_PACK_FROM_TEXT = localResourceBundle.getString(
            "UNABLE_TO_INSTALL_MOD_PACK_FROM_TEXT" );

    /**
     * Message shown when unable to install the specified item.
     *
     * @since 1.0
     */
    public static final String UNABLE_TO_INSTALL_TEXT = localResourceBundle.getString( "UNABLE_TO_INSTALL_TEXT" );

    /**
     * Message shown to describe that a reason is because it is not an available mod pack.
     *
     * @since 1.0
     */
    public static final String BECAUSE_NOT_AVAILABLE_MOD_PACK_TEXT = localResourceBundle.getString(
            "BECAUSE_NOT_AVAILABLE_MOD_PACK_TEXT" );

    /**
     * Message shown when unable to uninstall a mod pack.
     *
     * @since 1.0
     */
    public static final String UNABLE_TO_UNINSTALL_MOD_PACK_TEXT = localResourceBundle.getString(
            "UNABLE_TO_UNINSTALL_MOD_PACK_TEXT" );

    /**
     * Message shown when unable to create an object for an installed mod pack at a specified URL.
     *
     * @since 1.0
     */
    public static final String UNABLE_CREATE_OBJ_FOR_INSTALLED_MOD_PACK_FROM_TEXT = localResourceBundle.getString(
            "UNABLE_CREATE_OBJ_FOR_INSTALLED_MOD_PACK_FROM_TEXT" );

    /**
     * Message shown when downloading updates for installed mod packs.
     *
     * @since 1.0
     */
    public static final String DOWNLOADING_INSTALLED_MOD_PACK_UPDATES_TEXT = localResourceBundle.getString(
            "DOWNLOADING_INSTALLED_MOD_PACK_UPDATES_TEXT" );

    /**
     * Message shown when the application has downloaded the latest version of the specified item.
     *
     * @since 1.0
     */
    public static final String GOT_LATEST_VERSION_OF_TEXT = localResourceBundle.getString(
            "GOT_LATEST_VERSION_OF_TEXT" );

    /**
     * Message shown when updating the list of available mod packs.
     *
     * @since 1.0
     */
    public static final String UPDATING_LIST_APPLICABLE_MOD_PACKS_TEXT = localResourceBundle.getString(
            "UPDATING_LIST_APPLICABLE_MOD_PACKS_TEXT" );

    /**
     * Message shown when download the available mod packs list.
     *
     * @since 1.0
     */
    public static final String DOWNLOADING_AVAILABLE_MOD_PACKS_LIST_TEXT = localResourceBundle.getString(
            "DOWNLOADING_AVAILABLE_MOD_PACKS_LIST_TEXT" );

    /**
     * Message shown when the application is contacting a server.
     *
     * @since 1.0
     */
    public static final String CONTACTING_SERVER_TEXT = localResourceBundle.getString( "CONTACTING_SERVER_TEXT" );

    /**
     * Message shown when unable to download information about installable mod packs.
     *
     * @since 1.0
     */
    public static final String UNABLE_FETCH_INFO_INSTALLABLE_MOD_PACKS_TEXT = localResourceBundle.getString(
            "UNABLE_FETCH_INFO_INSTALLABLE_MOD_PACKS_TEXT" );

    /**
     * Message shown when a specified item has already been installed.
     *
     * @since 1.0
     */
    public static final String ALREADY_INSTALLED_TEXT = localResourceBundle.getString( "ALREADY_INSTALLED_TEXT" );

    /**
     * Message shown when a specified item has been added.
     *
     * @since 1.0
     */
    public static final String ADDED_TEXT = localResourceBundle.getString( "ADDED_TEXT" );

    /**
     * Partial message used to reference adding something to the list of available mod packs.
     *
     * @since 1.0
     */
    public static final String TO_AVAILABLE_MOD_PACKS_TEXT = localResourceBundle.getString(
            "TO_AVAILABLE_MOD_PACKS_TEXT" );

    /**
     * Message shown when a specified mod pack manifest is not marked as installable because it is installed already.
     *
     * @since 1.0
     */
    public static final String NOT_MARKING_INSTALLABLE_ALREADY_INSTALLED_TEXT = localResourceBundle.getString(
            "NOT_MARKING_INSTALLABLE_ALREADY_INSTALLED_TEXT" );

    /**
     * Message shown when unable to create an object required by (or for) an available mod pack.
     *
     * @since 1.0
     */
    public static final String UNABLE_CREATE_OBJ_FOR_AVAILABLE_MOD_PACK_TEXT = localResourceBundle.getString(
            "UNABLE_CREATE_OBJ_FOR_AVAILABLE_MOD_PACK_TEXT" );

    /**
     * Message shown when unable to download a file locally to the specified location.
     *
     * @since 1.0
     */
    public static final String UNABLE_DOWNLOAD_FILE_LOCALLY_TO_TEXT = localResourceBundle.getString(
            "UNABLE_DOWNLOAD_FILE_LOCALLY_TO_TEXT" );

    /**
     * Message shown when unable to read a local file to a JSON object.
     *
     * @since 1.0
     */
    public static final String UNABLE_READ_LOCAL_FILE_TO_JSON_EXCEPTION_TEXT = localResourceBundle.getString(
            "UNABLE_READ_LOCAL_FILE_TO_JSON_EXCEPTION_TEXT" );

    /**
     * Message shown when unable to find the version file of Forge.
     *
     * @since 1.0
     */
    public static final String UNABLE_FIND_FORGE_VERSION_FILE_TEXT = localResourceBundle.getString(
            "UNABLE_FIND_FORGE_VERSION_FILE_TEXT" );

    /**
     * Message shown when unable to close a stream or streams.
     *
     * @since 1.0
     */
    public static final String UNABLE_CLOSE_STREAMS_TEXT = localResourceBundle.getString( "UNABLE_CLOSE_STREAMS_TEXT" );

    /**
     * Message shown when unable to open the Forge version manifest for parsing.
     *
     * @since 1.0
     */
    public static final String UNABLE_OPEN_FORGE_VERSION_MANIFEST_PARSING_TEXT = localResourceBundle.getString(
            "UNABLE_OPEN_FORGE_VERSION_MANIFEST_PARSING_TEXT" );

    /**
     * Message shown when unable to access a Forge .jar file.
     *
     * @since 1.0
     */
    public static final String UNABLE_ACCESS_FORGE_JAR_TEXT = localResourceBundle.getString(
            "UNABLE_ACCESS_FORGE_JAR_TEXT" );

    /**
     * Message shown when unable to reach Mojang authentication servers on application startup.
     *
     * @since 1.0
     */
    public static final String UNABLE_TO_REACH_MOJANG_CANT_START_TEXT = localResourceBundle.getString(
            "UNABLE_TO_REACH_MOJANG_CANT_START_TEXT" );

    /**
     * Message shown when the launcher is in client mode and is starting the login process.
     *
     * @since 1.0
     */
    public static final String LAUNCHER_CLIENT_MODE_STARTING_LOGIN_TEXT = localResourceBundle.getString(
            "LAUNCHER_CLIENT_MODE_STARTING_LOGIN_TEXT" );

    /**
     * Message shown when the login process has been completed.
     *
     * @since 1.0
     */
    public static final String LOGIN_PROCESS_FINISHED_TEXT = localResourceBundle.getString(
            "LOGIN_PROCESS_FINISHED_TEXT" );

    /**
     * Message shown when the launcher is not in client mode and is skipping the login process.
     *
     * @since 1.0
     */
    public static final String LAUNCHER_NOT_CLIENT_MODE_SKIPPING_LOGIN_TEXT = localResourceBundle.getString(
            "LAUNCHER_NOT_CLIENT_MODE_SKIPPING_LOGIN_TEXT" );

    /**
     * Message prefix shown when displaying the mod pack that is being launched.
     *
     * @since 1.0
     */
    public static final String LAUNCHING_MOD_PACK_TEXT = localResourceBundle.getString( "LAUNCHING_MOD_PACK_TEXT" );

    /**
     * Message shown when unable to start the game due to an exception.
     *
     * @since 1.0
     */
    public static final String UNABLE_START_GAME_EXCEPTION_TEXT = localResourceBundle.getString(
            "UNABLE_START_GAME_EXCEPTION_TEXT" );

    /**
     * Text string used to display minimum RAM required in a message when a mod pack requires more RAM than is
     * configured.
     *
     * @since 1.0
     */
    public static final String REQUIRES_MIN_OF_TEXT = localResourceBundle.getString( "REQUIRES_MIN_OF_TEXT" );

    /**
     * Text string for labeling a value as 'GB of RAM'.
     *
     * @since 1.0
     */
    public static final String GB_OF_RAM_TEXT = localResourceBundle.getString( "GB_OF_RAM_TEXT" );

    /**
     * Message shown when the maximum configured RAM must be increased.
     *
     * @since 1.0
     */
    public static final String MAX_RAM_SETTING_MUST_INCREASE_TEXT = localResourceBundle.getString(
            "MAX_RAM_SETTING_MUST_INCREASE_TEXT" );

    /**
     * Message suffix shown when a specified mod pack is not installed and the launcher will default to the first mod
     * pack.
     *
     * @since 1.0
     */
    public static final String PACK_NOT_INSTALLED_WILL_DEFAULT_TO_FIRST_TEXT = localResourceBundle.getString(
            "PACK_NOT_INSTALLED_WILL_DEFAULT_TO_FIRST_TEXT" );

    /**
     * Message shown when no mod pack is specified and the launcher will default to the first mod pack.
     *
     * @since 1.0
     */
    public static final String NO_MOD_PACK_SPECIFIED_WILL_DEFAULT_TO_FIRST_TEXT = localResourceBundle.getString(
            "NO_MOD_PACK_SPECIFIED_WILL_DEFAULT_TO_FIRST_TEXT" );

    /**
     * Message shown when no mod packs are installed thus the first mod pack cannot be selected.
     *
     * @since 1.0
     */
    public static final String NO_MOD_PACKS_INSTALLED_CANT_SELECT_FIRST_TEXT = localResourceBundle.getString(
            "NO_MOD_PACKS_INSTALLED_CANT_SELECT_FIRST_TEXT" );

    /**
     * Message shown when no mod packs are installed thus the launcher cannot start a server.
     *
     * @since 1.0
     */
    public static final String NO_MOD_PACKS_INSTALLED_CANT_LAUNCH_SERVER_TEXT = localResourceBundle.getString(
            "NO_MOD_PACKS_INSTALLED_CANT_LAUNCH_SERVER_TEXT" );

    /**
     * Message shown when an error prevents handling the completion of a GUI, such as waiting for a GUI to close
     * cleanly.
     *
     * @since 1.0
     */
    public static final String ERROR_PREVENTING_GUI_COMPLETE_HANDLING_TEXT = localResourceBundle.getString(
            "ERROR_PREVENTING_GUI_COMPLETE_HANDLING_TEXT" );

    /**
     * Message shown when a remembered user account was not found and the login screen will be shown.
     *
     * @since 1.0
     */
    public static final String REMEMBERED_ACCOUNT_NOT_FOUND_SHOWING_LOGIN = localResourceBundle.getString(
            "REMEMBERED_ACCOUNT_NOT_FOUND_SHOWING_LOGIN" );

    /**
     * Message shown when the application has shut down and is terminating.
     *
     * @since 1.0
     */
    public static final String SEE_YOU_SOON_TEXT = localResourceBundle.getString( "SEE_YOU_SOON_TEXT" );

    /**
     * Message shown when the application is performing cleanup.
     *
     * @since 1.0
     */
    public static final String PERFORMING_APP_CLEANUP_TEXT = localResourceBundle.getString(
            "PERFORMING_APP_CLEANUP_TEXT" );

    /**
     * Message shown when the application has finished performing cleanup.
     *
     * @since 1.0
     */
    public static final String FINISHED_APP_CLEANUP_TEXT = localResourceBundle.getString( "FINISHED_APP_CLEANUP_TEXT" );

    /**
     * Message shown when an error occurred while configuring the application logging system.
     *
     * @since 1.0
     */
    public static final String ERROR_CONFIGURING_LOG_SYSTEM_TEXT = localResourceBundle.getString(
            "ERROR_CONFIGURING_LOG_SYSTEM_TEXT" );

    /**
     * Message shown when displaying the user name that has been logged in to the launcher.
     *
     * @since 1.0
     */
    public static final String WAS_LOGGED_IN_TO_LAUNCHER_TEXT = localResourceBundle.getString(
            "WAS_LOGGED_IN_TO_LAUNCHER_TEXT" );

    /**
     * Text word for 'usage'
     *
     * @since 1.0
     */
    public static final String USAGE_TEXT = localResourceBundle.getString( "USAGE_TEXT" );

    /**
     * Message shown when invalid program arguments have been specified.
     *
     * @since 1.0
     */
    public static final String INVALID_ARGS_SPECIFIED_TEXT = localResourceBundle.getString(
            "INVALID_ARGS_SPECIFIED_TEXT" );

    /**
     * Message shown when unable to wait for a successful login to complete.
     *
     * @since 1.0
     */
    public static final String UNABLE_WAIT_PENDING_LOGIN_TEXT = localResourceBundle.getString(
            "UNABLE_WAIT_PENDING_LOGIN_TEXT" );

    /**
     * Message shown when the authentication of the loaded user account could not be refreshed.
     *
     * @since 1.0
     */
    public static final String AUTH_NOT_REFRESHED_TEXT = localResourceBundle.getString( "AUTH_NOT_REFRESHED_TEXT" );

    /**
     * Message shown when the authentication of the loaded user account was unable to be refreshed due to an exception.
     *
     * @since 1.0
     */
    public static final String AUTH_UNABLE_TO_REFRESH_TEXT = localResourceBundle.getString(
            "AUTH_UNABLE_TO_REFRESH_TEXT" );
}
