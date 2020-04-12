package com.micatechnologies.minecraft.forgelauncher.utilities;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Enumeration;
import java.util.Formatter;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import com.micatechnologies.minecraft.forgelauncher.exceptions.FLModpackException;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.SystemUtils;

/**
 * Class of utility methods to facilitate classes in the {@link com.micatechnologies.minecraft.forgelauncher}
 * package.
 *
 * @author Mica Technologies/hawka97
 * @version 1.0
 */
public class FLSystemUtils {

    /**
     * Get the classpath separator that is specific to the calling operating system.
     *
     * @return OS-specific classpath separator
     *
     * @since 1.0
     */
    public static String getClasspathSeparator() {
        if ( SystemUtils.IS_OS_WINDOWS ) {
            return ";";
        }
        return ":";
    }

    /**
     * Get the file separator that is specific to the calling operating system.
     *
     * @return OS-specific classpath separator
     *
     * @since 1.0
     */
    public static String getFileSeparator() {
        return File.separator;
    }

    /**
     * Check if the specified path exists and is a file.
     *
     * @param filePath path to check
     *
     * @return if path exists and is file
     *
     * @since 1.0
     */
    static boolean doesFileExist( Path filePath ) {
        return filePath.toFile().exists() && filePath.toFile().isFile();
    }

    /**
     * Check if the specified path exists and is a folder/directory.
     *
     * @param filePath path to check
     *
     * @return if path exists and is folder/directory
     *
     * @since 1.0
     */
    static boolean doesFolderExist( Path filePath ) {
        return filePath.toFile().exists() && filePath.toFile().isDirectory();
    }

    public static void executeStringCommand( String command, String workingDirectory )
    throws IOException, InterruptedException {
        // Output
        System.out.println( "Running command: " + command );

        // Build process call
        ProcessBuilder processBuilder = new ProcessBuilder( command.split( " " ) ).inheritIO()
                                                                                  .directory(
                                                                                          new File(
                                                                                                  workingDirectory ) );
        // Start process and wait for finish
        processBuilder.start().waitFor();
    }

    /**
     * Check if the specified file SHA-1 hash matches the specified hash.
     *
     * @param toCheck Path to file to check
     * @param sha1    SHA-1 hash to verify against
     *
     * @return true if hashes match
     *
     * @throws NoSuchAlgorithmException if unable to use SHA-1 algorithm
     * @throws IOException              if unable to find or hash file
     * @since 1.0
     */
    public static boolean verifySHA( Path toCheck, String sha1 )
    throws NoSuchAlgorithmException, IOException {
        if ( !doesFileExist( toCheck ) ) {
            return false;
        }

        final MessageDigest messageDigest = MessageDigest.getInstance( "SHA1" );
        InputStream is = new BufferedInputStream( new FileInputStream( toCheck.toFile() ) );
        final byte[] buffer = new byte[ 1024 ];
        for ( int read = 0; ( read = is.read( buffer ) ) != -1; ) {
            messageDigest.update( buffer, 0, read );
        }

        Formatter formatter = new Formatter();
        for ( final byte b : messageDigest.digest() ) {
            formatter.format( "%02x", b );
        }

        return formatter.toString().equals( sha1 );
    }

    public static void downloadFileFromURL( URL source, File destination ) throws IOException {
        URLConnection connection = source.openConnection();
        connection.setUseCaches( false );
        FileUtils.copyInputStreamToFile( connection.getInputStream(), destination );
    }

    /**
     * Extract the specified source JarFile to the specified destination path.
     *
     * @param source      extract from
     * @param destination extract to
     */
    public static void extractJarFile( JarFile source, String destination )
    throws FLModpackException {
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
            File extractedJarFileFile = new File(
                    destination + getFileSeparator() + jarFileFile.getName() );

            // Create directory if expected
            if ( extractedJarFileFile.isDirectory() ) {
                extractedJarFileFile.mkdir();
                continue;
            }

            // Make sure the parent folders exist
            extractedJarFileFile.getParentFile().mkdirs();

            // Create file if doesn't exist
            if ( !extractedJarFileFile.exists() ) {
                try {
                    extractedJarFileFile.createNewFile();
                }
                catch ( IOException e ) {
                    throw new FLModpackException(
                            "Unable to create file for extraction. " + extractedJarFileFile.getPath(),
                            e );
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
                throw new FLModpackException(
                        "Unable to read file from jar during extraction.", e );
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

    public static void spawnNewTask( Runnable runnable ) {
        new Thread( runnable ).start();
    }

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
}
