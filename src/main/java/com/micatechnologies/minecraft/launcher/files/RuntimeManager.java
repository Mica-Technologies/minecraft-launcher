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

package com.micatechnologies.minecraft.launcher.files;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.launcher.consts.RuntimeConstants;
import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
import com.micatechnologies.minecraft.launcher.gui.MCLauncherGuiController;
import com.micatechnologies.minecraft.launcher.gui.MCLauncherProgressGui;
import com.micatechnologies.minecraft.launcher.utilities.FileUtilities;
import com.micatechnologies.minecraft.launcher.utilities.HashUtilities;
import com.micatechnologies.minecraft.launcher.utilities.NetworkUtilities;
import com.micatechnologies.minecraft.launcher.utilities.SystemUtilities;
import io.github.palexdev.materialfx.controls.MFXProgressBar;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.SystemUtils;
import org.rauschig.jarchivelib.ArchiveFormat;
import org.rauschig.jarchivelib.Archiver;
import org.rauschig.jarchivelib.ArchiverFactory;
import org.rauschig.jarchivelib.CompressionType;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Class for managing the download and usage of JREs required for Minecraft.
 *
 * @author Mica Technologies
 * @version 1.0
 * @since 1.1
 */
public class RuntimeManager
{
    /**
     * The path to the downloaded and verified JRE 8 installation. This value is <code>null</code> until populated by
     * verifying the JRE 8 with {@link #verifyJre8()}.
     *
     * @since 1.0
     */
    private static String jre8VerifiedPath = null;

    /**
     * The version of the downloaded and verified JRE 8 installation. This value is <code>null</code> until populated by
     * verifying the JRE 8 with {@link #verifyJre8()}.
     */
    private static String jre8VerifiedVersion = null;

    /**
     * Verifies the integrity of the local JRE 8 installation, and downloads or replaces files as necessary. This method
     * must be called before calling {@link #getJre8Path()}.
     *
     * @since 1.0
     */
    public static void verifyJre8() {
        // Create progress window if applicable
        MCLauncherProgressGui progressWindow = null;
        try {
            if ( MCLauncherGuiController.shouldCreateGui() ) {
                progressWindow = MCLauncherGuiController.goToProgressGui();
            }
        }
        catch ( IOException e ) {
            Logger.logError( "Unable to load progress GUI due to an incomplete response from the GUI subsystem." );
            Logger.logThrowable( e );
        }

        if ( progressWindow != null ) {
            progressWindow.setLabelTexts( LocalizationManager.RUNTIME_INSTALL_PROGRESS_UPPER_LABEL,
                                          LocalizationManager.RUNTIME_INSTALL_PROGRESS_LOWER_LABEL );
        }

        // Create runtime folder and file objects
        String runtimeFolderPath = LocalPathManager.getLauncherRuntimeFolderPath();
        File runtimeFolderFile = SynchronizedFileManager.getSynchronizedFile(
                SystemUtilities.buildFilePath( runtimeFolderPath ) );

        // Verify runtime folder exists and is valid
        if ( progressWindow != null ) {
            progressWindow.setLowerLabelText( LocalizationManager.VERIFYING_RUNTIME_INSTALL_FOLDER_TEXT );
            progressWindow.setProgress( 5 );
        }
        else {
            Logger.logStd( LocalizationManager.RUNTIME_INSTALL_PROGRESS_UPPER_LABEL +
                                   ": " +
                                   LocalizationManager.VERIFYING_RUNTIME_INSTALL_FOLDER_TEXT +
                                   " (5%)" );
        }
        final var mkdirs = runtimeFolderFile.mkdirs();
        final var readable = runtimeFolderFile.setReadable( true );
        final var writable = runtimeFolderFile.setWritable( true );
        if ( mkdirs ) {
            Logger.logDebug( LocalizationManager.CREATED_FOLDER_RUNTIME_TEXT );
        }
        else {
            Logger.logDebug( LocalizationManager.DID_NOT_CREATE_FOLDER_RUNTIME_TEXT );
        }
        if ( readable ) {
            Logger.logDebug( LocalizationManager.RUNTIME_FOLDER_SET_READABLE_TEXT );
        }
        else {
            Logger.logDebug( LocalizationManager.DID_NOT_SET_RUNTIME_FOLDER_READABLE_TEXT );
        }
        if ( writable ) {
            Logger.logDebug( LocalizationManager.RUNTIME_FOLDER_SET_WRITABLE_TEXT );
        }
        else {
            Logger.logDebug( LocalizationManager.DID_NOT_SET_RUNTIME_FOLDER_WRITABLE_TEXT );
        }

        // Get proper URLs and archive information for specific OS
        if ( progressWindow != null ) {
            progressWindow.setLowerLabelText( LocalizationManager.GATHERING_RUNTIME_INFO_TEXT );
            progressWindow.setProgress( 20 );
        }
        else {
            Logger.logStd( LocalizationManager.RUNTIME_INSTALL_PROGRESS_UPPER_LABEL +
                                   ": " +
                                   LocalizationManager.GATHERING_RUNTIME_INFO_TEXT +
                                   " (20%)" );
        }
        String latestJre8InfoApiUrlForOs = getLatestJre8InfoUrlForOs();

        // Download archive information
        if ( progressWindow != null ) {
            progressWindow.setLowerLabelText( LocalizationManager.DOWNLOADING_RUNTIME_CHECKSUM_TEXT );
            progressWindow.setProgress( 25 );
        }
        else {
            Logger.logStd( LocalizationManager.RUNTIME_INSTALL_PROGRESS_UPPER_LABEL +
                                   ": " +
                                   LocalizationManager.DOWNLOADING_RUNTIME_CHECKSUM_TEXT +
                                   " (25%)" );
        }
        ArchiveFormat jreArchiveFormat = null;
        CompressionType jreArchiveCompressionType = null;
        JsonObject jreLatestInformationObject = null;
        String extractedJreFolderName1 = null;
        String extractedJreFolderName2 = null;
        File extractedJreFolderFile1 = null;
        File extractedJreFolderFile2 = null;
        String newJavaPath = null;
        String newJavaVersion = null;
        boolean usedApiFallback = false;
        try {
            JsonArray jreLatestInformation = null;
            try {
                jreLatestInformation = downloadLatestJre8Info( latestJre8InfoApiUrlForOs );
            } catch (Exception apiEx) {
                // Fallback: read local file if API fails
                Logger.logError("Failed to download latest JRE 8 API info, falling back to local cache.");
                File jre8InfoFile = SynchronizedFileManager.getSynchronizedFile(
                        SystemUtilities.buildFilePath( runtimeFolderPath, RuntimeConstants.JRE_8_API_DATA_FILE_NAME ) );
                if (jre8InfoFile.exists()) {
                    jreLatestInformation = FileUtilities.readAsJsonArray(jre8InfoFile);
                    usedApiFallback = true;
                } else {
                    throw apiEx;
                }
            }
            if ( jreLatestInformation == null || jreLatestInformation.size() == 0 ) {
                throw new Exception( "No JRE 8 information available." );
            }
            jreLatestInformationObject = jreLatestInformation.get( 0 ).getAsJsonObject();
            jreArchiveFormat = jreLatestInformationObject.get( "packageType" ).getAsString().equals( "tar.gz" ) ?
                               ArchiveFormat.TAR :
                               ArchiveFormat.ZIP;
            jreArchiveCompressionType = jreLatestInformationObject.get( "packageType" )
                                                                  .getAsString()
                                                                  .equals( "tar.gz" ) ? CompressionType.GZIP : null;
            newJavaVersion = jreLatestInformationObject.get( "version" ).getAsString();

            extractedJreFolderName1 = jreLatestInformationObject.get( "bundleType" ).getAsString() +
                    jreLatestInformationObject.get( "featureVersion" ).getAsInt() +
                    "u" +
                    jreLatestInformationObject.get( "updateVersion" ).getAsInt();
            extractedJreFolderName2 = extractedJreFolderName1 +
                    "." +
                    jreLatestInformationObject.get( "bundleType" ).getAsString();
            extractedJreFolderFile1 = SynchronizedFileManager.getSynchronizedFile(
                    SystemUtilities.buildFilePath( runtimeFolderPath, extractedJreFolderName1 ) );
            extractedJreFolderFile2 = SynchronizedFileManager.getSynchronizedFile(
                    SystemUtilities.buildFilePath( runtimeFolderPath, extractedJreFolderName2 ) );
        }
        catch ( Exception e ) {
            Logger.logError( LocalizationManager.RUNTIME_CHECKSUM_DOWNLOAD_FAIL_TEXT );
            Logger.logThrowable( e );
            newJavaPath = "java";
            newJavaVersion = "Unknown (System Java)";
        }

        // Verify and download runtime locally
        if ( progressWindow != null ) {
            progressWindow.setLowerLabelText( LocalizationManager.VERIFYING_LOCAL_RUNTIME_TEXT );
            progressWindow.setProgress( 30 );
        }
        else {
            Logger.logStd( LocalizationManager.RUNTIME_INSTALL_PROGRESS_UPPER_LABEL +
                                   ": " +
                                   LocalizationManager.VERIFYING_LOCAL_RUNTIME_TEXT +
                                   " (30%)" );
        }
        try {
            if ( jreLatestInformationObject == null ) {
                throw new Exception( "Unable to download JRE 8 information." );
            }
            else {
                String jreArchiveHash = jreLatestInformationObject.get( "sha1" ).getAsString();
                File jreArchiveFile = SynchronizedFileManager.getSynchronizedFile(
                        SystemUtilities.buildFilePath( runtimeFolderPath,
                                                       jreLatestInformationObject.get( "filename" ).getAsString() ) );
                boolean isExistingValid = HashUtilities.verifySHA1( jreArchiveFile, jreArchiveHash );
                if ( !isExistingValid ) {
                    // Download archive from URL
                    if ( progressWindow != null ) {
                        progressWindow.setLowerLabelText( LocalizationManager.DOWNLOADING_RUNTIME_TEXT );
                        progressWindow.setProgress( MFXProgressBar.INDETERMINATE_PROGRESS );

                    }
                    else {
                        Logger.logStd( LocalizationManager.RUNTIME_INSTALL_PROGRESS_UPPER_LABEL +
                                               ": " +
                                               LocalizationManager.DOWNLOADING_RUNTIME_TEXT );
                    }
                    NetworkUtilities.downloadFileFromURL( jreLatestInformationObject.get( "downloadUrl" ).getAsString(),
                                                          jreArchiveFile );
                    if ( progressWindow != null ) {
                        progressWindow.setLowerLabelText( LocalizationManager.DOWNLOADED_RUNTIME_SUCCESS_TEXT );
                        progressWindow.setProgress( 65 );
                    }
                    else {
                        Logger.logStd( LocalizationManager.RUNTIME_INSTALL_PROGRESS_UPPER_LABEL +
                                               ": " +
                                               LocalizationManager.DOWNLOADED_RUNTIME_SUCCESS_TEXT +
                                               " (65%)" );
                    }

                    // Delete previous extracted JRE
                    if ( progressWindow != null ) {
                        progressWindow.setLowerLabelText( LocalizationManager.CLEANING_RUNTIME_ENV_TEXT );
                        progressWindow.setProgress( 70 );
                    }
                    else {
                        Logger.logStd( LocalizationManager.RUNTIME_INSTALL_PROGRESS_UPPER_LABEL +
                                               ": " +
                                               LocalizationManager.CLEANING_RUNTIME_ENV_TEXT +
                                               " (70%)" );
                    }
                    if ( extractedJreFolderFile1 != null && extractedJreFolderFile1.exists() ) {
                        FileUtils.deleteDirectory( extractedJreFolderFile1 );
                    }
                    if ( extractedJreFolderFile2 != null && extractedJreFolderFile2.exists() ) {
                        FileUtils.deleteDirectory( extractedJreFolderFile2 );
                    }

                    // Extract downloaded JRE
                    if ( progressWindow != null ) {
                        progressWindow.setLowerLabelText( LocalizationManager.EXTRACTING_RUNTIME_TO_ENV_TEXT );
                        progressWindow.setProgress( 75 );
                    }
                    else {
                        Logger.logStd( LocalizationManager.RUNTIME_INSTALL_PROGRESS_UPPER_LABEL +
                                               ": " +
                                               LocalizationManager.EXTRACTING_RUNTIME_TO_ENV_TEXT +
                                               " (75%)" );
                    }
                    Archiver archiver;
                    if ( jreArchiveCompressionType == null ) {
                        archiver = ArchiverFactory.createArchiver( jreArchiveFormat );
                    }
                    else {
                        archiver = ArchiverFactory.createArchiver( jreArchiveFormat, jreArchiveCompressionType );
                    }
                    archiver.extract( jreArchiveFile, runtimeFolderFile );
                }

                // Set java path if not already set by an error
                if ( newJavaPath == null && extractedJreFolderFile1 != null && extractedJreFolderFile1.exists() ) {
                    newJavaPath = SystemUtilities.buildFilePath( extractedJreFolderFile1.getAbsolutePath(),
                                                                 getJreExecutablePathForOs() );
                }
                else if ( newJavaPath == null && extractedJreFolderFile2 != null && extractedJreFolderFile2.exists() ) {
                    newJavaPath = SystemUtilities.buildFilePath( extractedJreFolderFile2.getAbsolutePath(),
                                                                 getJreExecutablePathForOs() );
                }
                else {
                    Logger.logDebug( "Unable to find Java executable in successfully downloaded JRE!" );
                    Logger.logError( LocalizationManager.UNABLE_DOWNLOAD_RUNTIME_TEXT );
                    newJavaPath = "java";
                    newJavaVersion = "Unknown (System Java)";
                }

                // --- Create/Update symlink to latest JRE folder ---
                try {
                    String symlinkName = "jre8-latest";
                    Path symlinkPath = Paths.get(runtimeFolderPath, symlinkName);
                    Path targetPath = null;
                    if (extractedJreFolderFile1 != null && extractedJreFolderFile1.exists()) {
                        targetPath = extractedJreFolderFile1.toPath();
                    } else if (extractedJreFolderFile2 != null && extractedJreFolderFile2.exists()) {
                        targetPath = extractedJreFolderFile2.toPath();
                    }
                    if (targetPath != null) {
                        // Remove old symlink if exists
                        if (Files.exists(symlinkPath)) {
                            Files.delete(symlinkPath);
                        }
                        Files.createSymbolicLink(symlinkPath, targetPath);
                        Logger.logStd("Created/updated symlink: " + symlinkPath + " -> " + targetPath);
                    }
                } catch (Exception symlinkEx) {
                    Logger.logWarningSilent("Failed to create symlink for latest JRE: " + symlinkEx.getMessage());
                }
            }
        }
        catch ( Exception e ) {
            Logger.logError( LocalizationManager.UNABLE_DOWNLOAD_RUNTIME_TEXT );
            // --- Fallback: Try to use local API info and existing JRE folder ---
            try {
                File jre8InfoFile = SynchronizedFileManager.getSynchronizedFile(
                        SystemUtilities.buildFilePath( runtimeFolderPath, RuntimeConstants.JRE_8_API_DATA_FILE_NAME ) );
                if (jre8InfoFile.exists()) {
                    JsonArray jreLatestInformation = FileUtilities.readAsJsonArray(jre8InfoFile);
                    if (jreLatestInformation != null && jreLatestInformation.size() > 0) {
                         jreLatestInformationObject = jreLatestInformation.get(0).getAsJsonObject();
                        String fallbackFolderName = jreLatestInformationObject.get("bundleType").getAsString() +
                                jreLatestInformationObject.get("featureVersion").getAsInt() +
                                "u" +
                                jreLatestInformationObject.get("updateVersion").getAsInt();
                        File fallbackJreFolderFile = SynchronizedFileManager.getSynchronizedFile(
                                SystemUtilities.buildFilePath(runtimeFolderPath, fallbackFolderName));
                        if (fallbackJreFolderFile.exists()) {
                            newJavaPath = SystemUtilities.buildFilePath(fallbackJreFolderFile.getAbsolutePath(),
                                    getJreExecutablePathForOs());
                            newJavaVersion = jreLatestInformationObject.get("version").getAsString();
                            Logger.logStd("Fell back to existing JRE in folder: " + fallbackJreFolderFile.getAbsolutePath());
                            // Try to update symlink as well
                            try {
                                String symlinkName = "jre8-latest";
                                Path symlinkPath = Paths.get(runtimeFolderPath, symlinkName);
                                Path targetPath = fallbackJreFolderFile.toPath();
                                if (Files.exists(symlinkPath)) {
                                    Files.delete(symlinkPath);
                                }
                                Files.createSymbolicLink(symlinkPath, targetPath);
                                Logger.logStd("Created/updated symlink: " + symlinkPath + " -> " + targetPath);
                            } catch (Exception symlinkEx) {
                                Logger.logWarningSilent("Failed to create symlink for fallback JRE: " + symlinkEx.getMessage());
                            }
                        } else {
                            Logger.logError("No existing JRE folder found for fallback.");
                            newJavaPath = "java";
                            newJavaVersion = "Unknown (System Java)";
                        }
                    }
                }
            } catch (Exception fallbackEx) {
                Logger.logError("Failed to fallback to local JRE info: " + fallbackEx.getMessage());
                newJavaPath = "java";
                newJavaVersion = "Unknown (System Java)";
            }
        }

        // Close progress window if applicable
        if ( progressWindow != null ) {
            progressWindow.setLowerLabelText( LocalizationManager.COMPLETED_TEXT );
            progressWindow.setProgress( 100 );
        }
        else {
            Logger.logStd( LocalizationManager.RUNTIME_INSTALL_PROGRESS_UPPER_LABEL +
                                   ": " +
                                   LocalizationManager.COMPLETED_TEXT +
                                   " (100%)" );
        }

        // Store new Java path
        jre8VerifiedPath = newJavaPath;
        jre8VerifiedVersion = newJavaVersion;

        // Log if fallback was used
        if (usedApiFallback) {
            Logger.logStd("Used cached JRE 8 API info due to network/API failure.");
        }
    }

    /**
     * Deletes the existing local runtime if it exists.
     *
     * @throws IOException if unable to delete local runtime
     * @since 1.0
     */
    public static void clearJre8() throws IOException {
        FileUtils.deleteDirectory(
                SynchronizedFileManager.getSynchronizedFile( LocalPathManager.getLauncherRuntimeFolderPath() ) );
    }

    /**
     * Gets the path to the local JRE 8 that has been verified.
     *
     * @return JRE 8 path
     *
     * @since 1.0
     */
    public static String getJre8Path() {
        if ( jre8VerifiedPath == null ) {
            verifyJre8();
        }
        return jre8VerifiedPath;
    }

    /**
     * Gets the version of the local JRE 8 that has been verified.
     *
     * @return JRE 8 version
     *
     * @since 1.0
     */
    public static String getJre8Version() {
        return jre8VerifiedVersion;
    }

    /**
     * Gets the API URL for the latest JRE 8 information for the current OS.
     *
     * @return API URL for the latest JRE 8 information for the current OS.
     *
     * @since 1.1
     */
    public static String getLatestJre8InfoUrlForOs() {
        String apiUrl;
        if ( SystemUtils.IS_OS_WINDOWS ) {
            apiUrl = RuntimeConstants.JRE_8_WIN_API_URL;
        }
        else if ( SystemUtils.IS_OS_MAC ) {
            apiUrl = RuntimeConstants.JRE_8_MAC_API_URL;
        }
        else if ( SystemUtils.IS_OS_LINUX ) {
            return RuntimeConstants.JRE_8_LNX_API_URL;
        }
        else {
            Logger.logError( "Unable to determine JRE API URL for OS: " +
                                     SystemUtils.OS_NAME +
                                     ". Using Linux API URL" +
                                     "..." );
            apiUrl = RuntimeConstants.JRE_8_LNX_API_URL;
        }
        return apiUrl;
    }

    /**
     * Downloads the latest JRE 8 information from the API for the current OS.
     *
     * @param apiUrl the API URL to use
     *
     * @return latest JRE 8 information from the API for the current OS
     *
     * @throws Exception if an error occurs while downloading the API data file or reading it as a JSON object
     * @since 1.1
     */
    public static JsonArray downloadLatestJre8Info( String apiUrl ) throws Exception {
        File jre8InfoFile = SynchronizedFileManager.getSynchronizedFile(
                SystemUtilities.buildFilePath( LocalPathManager.getLauncherRuntimeFolderPath(),
                                               RuntimeConstants.JRE_8_API_DATA_FILE_NAME ) );
        NetworkUtilities.downloadFileFromURL( apiUrl, jre8InfoFile, "application/json" );
        return FileUtilities.readAsJsonArray( jre8InfoFile );
    }

    private static String getJreExecutablePathForOs() {
        if ( SystemUtils.IS_OS_WINDOWS ) {
            return RuntimeConstants.JRE_8_WIN_JAVA_EXEC_PATH;
        }
        else if ( SystemUtils.IS_OS_MAC ) {
            return RuntimeConstants.JRE_8_MAC_JAVA_EXEC_PATH;
        }
        else if ( SystemUtils.IS_OS_LINUX ) {
            return RuntimeConstants.JRE_8_LNX_JAVA_EXEC_PATH;
        }
        else {
            Logger.logError( "Unable to determine JRE executable path for OS: " +
                                     SystemUtils.OS_NAME +
                                     ". Using Linux executable path..." );
            return RuntimeConstants.JRE_8_LNX_JAVA_EXEC_PATH;
        }
    }
}
