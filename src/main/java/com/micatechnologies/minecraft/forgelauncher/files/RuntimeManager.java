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

package com.micatechnologies.minecraft.forgelauncher.files;

import com.jfoenix.controls.JFXProgressBar;
import com.micatechnologies.minecraft.forgelauncher.consts.RuntimeConstants;
import com.micatechnologies.minecraft.forgelauncher.consts.localization.LocalizationManager;
import com.micatechnologies.minecraft.forgelauncher.gui.GUIController;
import com.micatechnologies.minecraft.forgelauncher.gui.ProgressWindow;
import com.micatechnologies.minecraft.forgelauncher.utilities.FileUtilities;
import com.micatechnologies.minecraft.forgelauncher.utilities.HashUtilities;
import com.micatechnologies.minecraft.forgelauncher.utilities.NetworkUtilities;
import com.micatechnologies.minecraft.forgelauncher.utilities.SystemUtilities;
import com.micatechnologies.minecraft.forgelauncher.utilities.annotations.ClientAndServer;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.SystemUtils;
import org.rauschig.jarchivelib.ArchiveFormat;
import org.rauschig.jarchivelib.Archiver;
import org.rauschig.jarchivelib.ArchiverFactory;
import org.rauschig.jarchivelib.CompressionType;

import java.io.File;
import java.io.IOException;

/**
 * Class for managing the download and usage of JREs required for Minecraft.
 *
 * @author Mica Technologies
 * @version 1.0
 * @editors hawka97
 * @creator hawka97
 * @since 1.1
 */
@ClientAndServer
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
     * Verifies the integrity of the local JRE 8 installation, and downloads or replaces files as necessary. This method
     * must be called before calling {@link #getJre8Path()}.
     *
     * @since 1.0
     */
    @ClientAndServer
    public static void verifyJre8() {
        // Create progress window if applicable
        ProgressWindow progressWindow = null;
        if ( GUIController.shouldCreateGui() ) {
            progressWindow = new ProgressWindow();
            progressWindow.show( LocalizationManager.RUNTIME_INSTALL_PROGRESS_UPPER_LABEL,
                                 LocalizationManager.RUNTIME_INSTALL_PROGRESS_LOWER_LABEL );
        }

        // Create runtime folder and file objects
        String runtimeFolderPath = LocalPathManager.getLauncherRuntimeFolderPath();
        String extractedJreFolder = SystemUtilities
                .buildFilePath( LocalPathManager.getLauncherRuntimeFolderPath(),
                                RuntimeConstants.JRE_8_EXTRACTED_FOLDER_NAME );
        File runtimeFolderFile =
                SynchronizedFileManager.getSynchronizedFile( SystemUtilities.buildFilePath( runtimeFolderPath ) );
        File extractedJreFolderFile = SynchronizedFileManager.getSynchronizedFile( extractedJreFolder );

        File jreArchiveFile =
                SynchronizedFileManager.getSynchronizedFile( SystemUtilities.buildFilePath( runtimeFolderPath,
                                                                                            RuntimeConstants.JRE_8_ARCHIVE_FILE_NAME ) );
        File jreArchiveHashFile =
                SynchronizedFileManager.getSynchronizedFile( SystemUtilities.buildFilePath( runtimeFolderPath,
                                                                                            RuntimeConstants.JRE_8_HASH_FILE_NAME ) );

        // Verify runtime folder exists and is valid
        if ( progressWindow != null ) {
            progressWindow.setLowerLabelText( "Verifying runtime install folder" );
            progressWindow.setProgress( 5 );
        }
        else {
            Logger.logStd( LocalizationManager.RUNTIME_INSTALL_PROGRESS_UPPER_LABEL + ": " +
                                   "Verifying runtime install folder (5%)" );
        }
        final var mkdirs = runtimeFolderFile.mkdirs();
        final var readable = runtimeFolderFile.setReadable( true );
        final var writable = runtimeFolderFile.setWritable( true );
        if ( mkdirs ) {
            Logger.logDebug( "Created folder for runtime." );
        }
        else {
            Logger.logDebug( "Did not create folder for runtime." );
        }
        if ( readable ) {
            Logger.logDebug( "Set runtime folder as readable." );
        }
        else {
            Logger.logDebug( "Did not set runtime folder as readable." );
        }
        if ( writable ) {
            Logger.logDebug( "Set runtime folder as writable." );
        }
        else {
            Logger.logDebug( "Did not set runtime folder as writable." );
        }

        // Get proper URLs and archive format information for specific OS
        if ( progressWindow != null ) {
            progressWindow.setLowerLabelText( "Gathering runtime information" );
            progressWindow.setProgress( 20 );
        }
        else {
            Logger.logStd( LocalizationManager.RUNTIME_INSTALL_PROGRESS_UPPER_LABEL + ": " +
                                   "Gathering runtime information (20%)" );
        }
        String jreArchiveDownloadURL;
        String jreArchiveHashDownloadURL;
        ArchiveFormat jreArchiveFormat;
        CompressionType jreArchiveCompressionType;
        String newJavaPath;
        if ( SystemUtils.IS_OS_WINDOWS ) {
            jreArchiveFormat = ArchiveFormat.ZIP;
            jreArchiveCompressionType = null;
            jreArchiveDownloadURL = RuntimeConstants.JRE_8_WIN_URL;
            jreArchiveHashDownloadURL = RuntimeConstants.JRE_8_WIN_HASH_URL;
            newJavaPath =
                    SystemUtilities.buildFilePath( extractedJreFolder, RuntimeConstants.JRE_8_WIN_JAVA_EXEC_PATH );
        }
        else if ( SystemUtils.IS_OS_MAC ) {
            jreArchiveFormat = ArchiveFormat.TAR;
            jreArchiveCompressionType = CompressionType.GZIP;
            jreArchiveDownloadURL = RuntimeConstants.JRE_8_MAC_URL;
            jreArchiveHashDownloadURL = RuntimeConstants.JRE_8_MAC_HASH_URL;
            newJavaPath =
                    SystemUtilities.buildFilePath( extractedJreFolder, RuntimeConstants.JRE_8_MAC_JAVA_EXEC_PATH );
        }
        else {
            if ( !SystemUtils.IS_OS_LINUX ) {
                Logger.logWarning(
                        "The current operating system could not be identified. A Linux runtime will be used but may not be compatible with your operating system." );
            }
            jreArchiveFormat = ArchiveFormat.TAR;
            jreArchiveCompressionType = CompressionType.GZIP;
            jreArchiveDownloadURL = RuntimeConstants.JRE_8_LNX_URL;
            jreArchiveHashDownloadURL = RuntimeConstants.JRE_8_LNX_HASH_URL;
            newJavaPath =
                    SystemUtilities.buildFilePath( extractedJreFolder, RuntimeConstants.JRE_8_LNX_JAVA_EXEC_PATH );
        }

        // Download archive hash
        if ( progressWindow != null ) {
            progressWindow.setLowerLabelText( "Downloading current runtime checksum" );
            progressWindow.setProgress( 25 );
        }
        else {
            Logger.logStd( LocalizationManager.RUNTIME_INSTALL_PROGRESS_UPPER_LABEL + ": " +
                                   "Downloading current runtime checksum (25%)" );
        }

        try {
            NetworkUtilities.downloadFileFromURL( jreArchiveHashDownloadURL, jreArchiveHashFile );
        }
        catch ( Exception e ) {
            Logger.logError(
                    "Unable to download integrity information for the runtime. Runtime will default to system Java!" );
            newJavaPath = "java";
        }

        // Verify and download runtime locally
        if ( progressWindow != null ) {
            progressWindow.setLowerLabelText( "Verifying local runtime" );
            progressWindow.setProgress( 30 );
        }
        else {
            Logger.logStd( LocalizationManager.RUNTIME_INSTALL_PROGRESS_UPPER_LABEL + ": " +
                                   "Verifying local runtime (30%)" );
        }
        try {
            String jreArchiveHash = FileUtilities.readAsString( jreArchiveHashFile ).split( " " )[ 0 ];
            boolean isExistingValid = HashUtilities.verifySHA256( jreArchiveFile, jreArchiveHash );
            if ( !isExistingValid ) {
                // Download archive from URL
                if ( progressWindow != null ) {
                    progressWindow.setLowerLabelText( "Downloading runtime..." );
                    progressWindow.setProgress( JFXProgressBar.INDETERMINATE_PROGRESS );

                }
                else {
                    Logger.logStd( LocalizationManager.RUNTIME_INSTALL_PROGRESS_UPPER_LABEL + ": " +
                                           "Downloading runtime..." );
                }
                NetworkUtilities.downloadFileFromURL( jreArchiveDownloadURL, jreArchiveFile );
                if ( progressWindow != null ) {
                    progressWindow.setLowerLabelText( "Downloaded runtime successfully" );
                    progressWindow.setProgress( 65 );
                }
                else {
                    Logger.logStd( LocalizationManager.RUNTIME_INSTALL_PROGRESS_UPPER_LABEL + ": " +
                                           "Downloaded runtime successfully (65%)" );
                }

                // Delete previous extracted JRE
                if ( progressWindow != null ) {
                    progressWindow.setLowerLabelText( "Cleaning local runtime environment" );
                    progressWindow.setProgress( 70 );
                }
                else {
                    Logger.logStd( LocalizationManager.RUNTIME_INSTALL_PROGRESS_UPPER_LABEL + ": " +
                                           "Cleaning local runtime environment (70%)" );
                }
                if ( extractedJreFolderFile.exists() ) {
                    FileUtils.deleteDirectory( extractedJreFolderFile );
                }

                // Extract downloaded JRE
                if ( progressWindow != null ) {
                    progressWindow.setLowerLabelText( "Extracting runtime to environment" );
                    progressWindow.setProgress( 75 );
                }
                else {
                    Logger.logStd( LocalizationManager.RUNTIME_INSTALL_PROGRESS_UPPER_LABEL + ": " +
                                           "Extracting runtime to environment (75%)" );
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
        }
        catch ( Exception e ) {
            Logger.logError( "Unable to download runtime. Runtime will default to system Java!" );
            newJavaPath = "java";
        }
        // Close progress window if applicable
        if ( progressWindow != null ) {
            progressWindow.setLowerLabelText( "Completed" );
            progressWindow.setProgress( 100 );
            progressWindow.close();
            try {
                progressWindow.closedLatch.await();
            }
            catch ( InterruptedException e ) {
                Logger.logError(
                        "Unable to wait for progress window to complete before returning from parent task." );
                e.printStackTrace();
            }
        }
        else {
            Logger.logStd( LocalizationManager.RUNTIME_INSTALL_PROGRESS_UPPER_LABEL + ": " +
                                   "Completed (100%)" );
        }

        // Store new Java path
        jre8VerifiedPath = newJavaPath;
    }

    /**
     * Deletes the existing local runtime if it exists.
     *
     * @throws IOException if unable to delete local runtime
     * @since 1.0
     */
    @ClientAndServer
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
    @ClientAndServer
    public static String getJre8Path() {
        if ( jre8VerifiedPath == null ) {
            verifyJre8();
        }
        return jre8VerifiedPath;
    }
}
