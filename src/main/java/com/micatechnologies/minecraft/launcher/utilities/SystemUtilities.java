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

package com.micatechnologies.minecraft.launcher.utilities;

import java.io.*;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.UUID;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
import com.micatechnologies.minecraft.launcher.exceptions.ModpackException;
import com.micatechnologies.minecraft.launcher.files.LocalPathManager;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.files.SynchronizedFileManager;
import org.apache.commons.io.FileUtils;

/**
 * Class containing utility methods and other functionality that pertains to the executing system and/or operating
 * system.
 *
 * @author Mica Technologies
 * @version 1.1
 * @since 1.0
 */
public class SystemUtilities
{

    /**
     * Variable containing the client token for the current installation.
     *
     * @since 1.0
     */
    private static String clientToken = null;

    /**
     * Executes the specified string command in the specified working directory.
     *
     * @param command          command to execute
     * @param workingDirectory working directory to execute in
     *
     * @throws IOException          if unable to start execution
     * @throws InterruptedException if unable to wait for execution to complete
     * @since 1.0
     */
    public static void executeStringCommand( String command, String workingDirectory )
    throws IOException, InterruptedException
    {
        // Build process call
        ProcessBuilder processBuilder = new ProcessBuilder( command.split( " " ) ).inheritIO()
                                                                                  .directory(
                                                                                          SynchronizedFileManager.getSynchronizedFile(
                                                                                                  workingDirectory ) );

        // Start process and wait for finish
        Logger.logStd( "Executing command: " + command );
        processBuilder.start().waitFor();
    }

    /**
     * Extract the specified source JarFile to the specified destination path.
     *
     * @param source      extract from
     * @param destination extract to
     */
    public static void extractJarFile( JarFile source, String destination ) throws ModpackException
    {
        // Create an enumeration over JarFile entries
        Enumeration< JarEntry > jarFileFiles = source.entries();

        // Loop through each file in Jar file
        while ( jarFileFiles.hasMoreElements() ) {
            // Store current Jar file file
            JarEntry jarFileFile = jarFileFiles.nextElement();

            // Skip META-INF file(s)
            if ( jarFileFile.getName().contains( "META-INF" ) ) {
                continue;
            }

            // Create extracted file File object
            File extractedJarFileFile = SynchronizedFileManager.getSynchronizedFile(
                    destination + File.separator + jarFileFile.getName() );

            // Create directory if expected
            if ( extractedJarFileFile.isDirectory() ) {
                //noinspection ResultOfMethodCallIgnored
                extractedJarFileFile.mkdir();
                continue;
            }

            // Make sure the parent folders exist
            //noinspection ResultOfMethodCallIgnored
            extractedJarFileFile.getParentFile().mkdirs();

            // Create file if doesn't exist
            if ( !extractedJarFileFile.exists() ) {
                try {
                    //noinspection ResultOfMethodCallIgnored
                    extractedJarFileFile.createNewFile();
                }
                catch ( IOException e ) {
                    throw new ModpackException(
                            "Unable to create file for extraction. " + extractedJarFileFile.getPath(), e );
                }
            }

            // Read file from jar to extracted file
            InputStream inputStream;
            FileOutputStream fileOutputStream;
            try {
                inputStream = source.getInputStream( jarFileFile );
                fileOutputStream = new FileOutputStream( extractedJarFileFile );
                while ( inputStream.available() > 0 ) {
                    fileOutputStream.write( inputStream.read() );
                }
            }
            catch ( IOException e ) {
                throw new ModpackException( "Unable to read file from jar during extraction.", e );
            }

            // Close streams
            try {
                fileOutputStream.close();
                inputStream.close();
            }
            catch ( IOException e ) {
                System.err.println( "Unable to close streams after extracting JAR file." );
            }
        }
    }

    /**
     * Executes the specified runnable task on a newly created thread.
     *
     * @param runnable task
     *
     * @since 1.1
     */
    public static void spawnNewTask( Runnable runnable ) {
        new Thread( runnable ).start();
    }

    /**
     * Builds a file path using the specified file path parts.
     *
     * @param parts file path parts
     *
     * @return built file path
     *
     * @since 1.1
     */
    public static String buildFilePath( String... parts ) {
        StringBuilder stringBuilder = new StringBuilder();
        final var iterator = Arrays.stream( parts ).iterator();
        while ( iterator.hasNext() ) {
            stringBuilder.append( iterator.next() );
            if ( iterator.hasNext() ) {
                stringBuilder.append( File.separator );
            }
        }
        return stringBuilder.toString();
    }

    /**
     * Compares the specified version numbers and returns: 0 if <code>version1</code> and <code>version2</code> are
     * equal, -1 if <code>version1</code> is less than <code>version2</code>, or (+)1 if <code>version1</code> is
     * greater than <code>version2</code>.
     *
     * @param version1 first version number
     * @param version2 second version number
     *
     * @return 0 if equal, -1 if <code>version1</code> lower, 1 if <code>version1</code> higher
     */
    public static int compareVersionNumbers( String version1, String version2 ) {
        String[] arr1 = version1.split( "\\." );
        String[] arr2 = version2.split( "\\." );

        int i = 0;
        while ( i < arr1.length || i < arr2.length ) {
            if ( i < arr1.length && i < arr2.length ) {
                if ( Integer.parseInt( arr1[ i ] ) < Integer.parseInt( arr2[ i ] ) ) {
                    return -1;
                }
                else if ( Integer.parseInt( arr1[ i ] ) > Integer.parseInt( arr2[ i ] ) ) {
                    return 1;
                }
            }
            else if ( i < arr1.length ) {
                if ( Integer.parseInt( arr1[ i ] ) != 0 ) {
                    return 1;
                }
            }
            else {
                if ( Integer.parseInt( arr2[ i ] ) != 0 ) {
                    return -1;
                }
            }

            i++;
        }

        return 0;
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
