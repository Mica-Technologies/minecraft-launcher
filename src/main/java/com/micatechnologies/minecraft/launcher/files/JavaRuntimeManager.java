/*
 * Copyright (c) 2022 Mica Technologies
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

import com.google.gson.annotations.SerializedName;
import com.micatechnologies.minecraft.launcher.files.hash.FileChecksumSHA1;
import com.micatechnologies.minecraft.launcher.utilities.FileUtilities;
import com.micatechnologies.minecraft.launcher.utilities.NetworkUtilities;
import org.apache.commons.lang3.SystemUtils;
import org.rauschig.jarchivelib.ArchiveFormat;
import org.rauschig.jarchivelib.Archiver;
import org.rauschig.jarchivelib.ArchiverFactory;
import org.rauschig.jarchivelib.CompressionType;

import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * Utility class for downloading and managing the latest release of a specified Java version.
 *
 * @author Mica Technologies
 * @version 1.0
 * @since 2022.1
 */
public class JavaRuntimeManager
{
    /**
     * The path for storing runtime download information caches and downloaded runtimes, relative to a specified base
     * folder.
     *
     * @since 1.0
     */
    private static final Path RUNTIME_DOWNLOAD_FOLDER = Path.of( "runtimes" );

    /**
     * The path for storing runtime download information caches (inside {@link #RUNTIME_DOWNLOAD_FOLDER}), relative to a
     * specified base folder.
     *
     * @since 1.0
     */
    private static final Path RUNTIME_JSON_DOWNLOAD_FOLDER = RUNTIME_DOWNLOAD_FOLDER.resolve( "json" );

    /**
     * The content type expected for responses from the Java runtime API.
     *
     * @since 1.0
     */
    private static final String DOWNLOAD_INFORMATION_CONTENT_TYPE = "application/json";

    /**
     * The file extension used for runtime download information caches.
     *
     * @since 1.0
     */
    private static final String DOWNLOAD_INFORMATION_CACHE_FILE_EXTENSION = ".json";

    /**
     * The name of the 'bin' folder used to identify a Java runtime archive installation folder.
     *
     * @since 1.0
     */
    private static final String RUNTIME_DOWNLOAD_EXTRACTED_FOLDER_BIN_FOLDER_NAME = "bin";

    /**
     * Gets the Java installation path of the specified {@link JAVA_RUNTIME_ARCHIVE}  in the specified
     * <code>baseFolder</code>.
     *
     * @param javaRuntimeArchive the {@link JAVA_RUNTIME_ARCHIVE} to gets the Java installation path of
     * @param baseFolder         the base folder (parent folder) for the 'runtimes' folder which stores information
     *                           caches and runtimes
     *
     * @return ava installation path of the specified {@link JAVA_RUNTIME_ARCHIVE}  in the specified
     *         <code>baseFolder</code>.
     *
     * @since 1.0
     */
    public static Path getJavaRuntimeArchivePath( JAVA_RUNTIME_ARCHIVE javaRuntimeArchive, Path baseFolder ) {
        // Get folder path for extracted runtime archive
        final File runtimeArchiveExtractedFolder = getRuntimeArchiveExtractedFolder( javaRuntimeArchive, baseFolder );

        // Loop through files in folder to find folder with 'bin' child folder (Java path)
        Path javaRuntimeArchivePath = null;
        File[] runtimeArchiveExtractedFolderFiles = runtimeArchiveExtractedFolder.listFiles();
        if ( runtimeArchiveExtractedFolderFiles != null ) {
            for ( File runtimeArchiveExtractedFolderFile : runtimeArchiveExtractedFolderFiles ) {
                // Check for 'bin' folder as child
                File[] runtimeArchiveFileFolders = runtimeArchiveExtractedFolderFile.listFiles();
                if ( runtimeArchiveFileFolders != null &&
                        Arrays.stream( runtimeArchiveFileFolders )
                              .anyMatch( file -> file.getName()
                                                     .equalsIgnoreCase(
                                                             RUNTIME_DOWNLOAD_EXTRACTED_FOLDER_BIN_FOLDER_NAME ) ) ) {
                    javaRuntimeArchivePath = runtimeArchiveExtractedFolderFile.toPath();
                }
            }
        }

        return javaRuntimeArchivePath;
    }

    /**
     * Downloads and extracts the specified {@link JAVA_RUNTIME_ARCHIVE} to its corresponding path in the specified
     * <code>baseFolder</code>.
     *
     * @param javaRuntimeArchive the {@link JAVA_RUNTIME_ARCHIVE} to download and extract
     * @param baseFolder         the base folder (parent folder) for the 'runtimes' folder which stores information
     *                           caches and runtimes
     *
     * @throws IOException if unable to download or extract the {@link JAVA_RUNTIME_ARCHIVE} file
     * @since 1.0
     */
    public static void downloadJavaRuntimeArchive( JAVA_RUNTIME_ARCHIVE javaRuntimeArchive, Path baseFolder )
    throws IOException
    {
        // Build file paths for download and extract location(s)
        final File runtimeArchiveFile = getRuntimeArchiveFile( javaRuntimeArchive, baseFolder );
        final File runtimeArchiveExtractedFolder = getRuntimeArchiveExtractedFolder( javaRuntimeArchive, baseFolder );

        // Download runtime archive file
        NetworkUtilities.downloadFileFromURL( javaRuntimeArchive.downloadUrl, runtimeArchiveFile );

        // Create extractor depending on package type
        Archiver archiver;
        if ( javaRuntimeArchive.packageType == JAVA_RUNTIME_ARCHIVE.PACKAGE_TYPE.ZIP ) {
            archiver = ArchiverFactory.createArchiver( ArchiveFormat.ZIP );
        }
        else if ( javaRuntimeArchive.packageType == JAVA_RUNTIME_ARCHIVE.PACKAGE_TYPE.TAR_GZ ) {
            archiver = ArchiverFactory.createArchiver( ArchiveFormat.TAR, CompressionType.GZIP );
        }
        else {
            throw new UnsupportedEncodingException(
                    "No supported runtime archive format could be found for the specified Java runtime archive: [" +
                            javaRuntimeArchive.filename +
                            "]" );
        }

        // Extract file to folder
        archiver.extract( runtimeArchiveFile, runtimeArchiveExtractedFolder );
    }

    /**
     * Gets a boolean indicating if the specified {@link JAVA_RUNTIME_ARCHIVE} in the specified
     * <code>baseFolder</code> passes checksum verification.
     *
     * @param javaRuntimeArchive {@link JAVA_RUNTIME_ARCHIVE} to verify checksum
     * @param baseFolder         the base folder (parent folder) for the 'runtimes' folder which stores information
     *                           caches and runtimes
     *
     * @return boolean indicating if the specified {@link JAVA_RUNTIME_ARCHIVE} in the specified
     *         <code>baseFolder</code> passes checksum verification
     *
     * @throws NoSuchAlgorithmException if unable to find or access the SHA-1 algorithm for checksum calculation
     * @throws IOException              if unable to access or read the local {@link JAVA_RUNTIME_ARCHIVE} files
     * @since 1.0
     */
    public static boolean verifyJavaRuntimeArchive( JAVA_RUNTIME_ARCHIVE javaRuntimeArchive, Path baseFolder )
    throws NoSuchAlgorithmException, IOException
    {
        boolean verified = true;

        // Build file paths for download and extract location(s)
        final File runtimeArchiveFile = getRuntimeArchiveFile( javaRuntimeArchive, baseFolder );
        final File runtimeArchiveExtractedFolder = getRuntimeArchiveExtractedFolder( javaRuntimeArchive, baseFolder );

        // Check integrity of existing file(s)
        FileChecksumSHA1 sha1HashArchiveDownloadFile = new FileChecksumSHA1( javaRuntimeArchive.sha1 );
        if ( !sha1HashArchiveDownloadFile.verifyFile( runtimeArchiveFile ) ) {
            verified = false;
        }
        if ( !runtimeArchiveExtractedFolder.exists() ||
                !runtimeArchiveExtractedFolder.isDirectory() ||
                runtimeArchiveExtractedFolder.list() == null ) {
            verified = false;
        }

        return verified;
    }

    /**
     * Gets the {@link JAVA_RUNTIME_ARCHIVE} object of download information for the latest release of the specified JRE
     * feature version as an archive (zip or tar.gz). The information is cached in the 'runtimes' folder specified by
     * the {@link #RUNTIME_DOWNLOAD_FOLDER} path in the given <code>baseFolder</code>.
     *
     * @param featureVersion feature version to get download information for
     * @param baseFolder     the base folder (parent folder) for the 'runtimes' folder which stores information caches
     *                       and runtimes
     *
     * @return the {@link JAVA_RUNTIME_ARCHIVE} object of download information for the latest release of the specified
     *         JRE feature version as an archive (zip or tar.gz)
     *
     * @throws IOException if unable to download or parse download information for the specified feature version of
     *                     Java
     * @apiNote When both a zip and tar.gz archive are available, the zip archive will be preferred.
     * @since 1.0
     */
    public static JAVA_RUNTIME_ARCHIVE getRuntimeArchiveInfoForFeatureVersion( String featureVersion, Path baseFolder )
    throws IOException
    {
        // Get the API URL for the latest version
        String apiUrl = getVersionInformationApiUrlForFeatureVersion( featureVersion );

        // Download the latest version information from API
        String runtimeDownloadInformationJsonFileName = featureVersion + DOWNLOAD_INFORMATION_CACHE_FILE_EXTENSION;
        Path runtimeDownloadInformationJsonFilePath = baseFolder.resolve( RUNTIME_JSON_DOWNLOAD_FOLDER )
                                                                .resolve( runtimeDownloadInformationJsonFileName );
        File latestVersionInformationFile = SynchronizedFileManager.getSynchronizedFile(
                runtimeDownloadInformationJsonFilePath );
        NetworkUtilities.downloadFileFromURL( apiUrl, latestVersionInformationFile, DOWNLOAD_INFORMATION_CONTENT_TYPE );

        // Read downloaded information file as JSON
        JAVA_RUNTIME_ARCHIVE[] javaRuntimeArchives = FileUtilities.readAsJsonObject( latestVersionInformationFile,
                                                                                     JAVA_RUNTIME_ARCHIVE[].class );

        // Get index of archive types (zip and tar.gz)
        int zipIndex = -1;
        int tarGzIndex = -1;
        int currentIndex = 0;
        for ( JAVA_RUNTIME_ARCHIVE javaRuntimeArchive : javaRuntimeArchives ) {
            if ( javaRuntimeArchive.packageType == JAVA_RUNTIME_ARCHIVE.PACKAGE_TYPE.ZIP ) {
                zipIndex = currentIndex;
            }
            else if ( javaRuntimeArchive.packageType == JAVA_RUNTIME_ARCHIVE.PACKAGE_TYPE.TAR_GZ ) {
                tarGzIndex = currentIndex;
            }
            currentIndex++;
        }

        // Get runtime archive object for preferred type (zip -> tar.gz)
        JAVA_RUNTIME_ARCHIVE javaRuntimeArchiveResult;
        if ( zipIndex > -1 ) {
            javaRuntimeArchiveResult = javaRuntimeArchives[ zipIndex ];
        }
        else if ( tarGzIndex > -1 ) {
            javaRuntimeArchiveResult = javaRuntimeArchives[ tarGzIndex ];
        }
        else {
            throw new EOFException( "No valid Java runtime archives could be found for specified feature version [" +
                                            featureVersion +
                                            "]." );
        }

        return javaRuntimeArchiveResult;
    }

    /**
     * Gets the synchronized {@link File} object from the {@link SynchronizedFileManager} for the downloaded runtime
     * archive file for the specified {@link JAVA_RUNTIME_ARCHIVE} in the specified <code>baseFolder</code>.
     *
     * @param javaRuntimeArchive {@link JAVA_RUNTIME_ARCHIVE} to get synchronized {@link File} object for
     * @param baseFolder         the base folder (parent folder) for the 'runtimes' folder which stores information
     *                           caches and runtimes
     *
     * @return synchronized {@link File} object from the {@link SynchronizedFileManager} for the downloaded runtime
     *         archive file for the specified {@link JAVA_RUNTIME_ARCHIVE} in the specified <code>baseFolder</code>
     *
     * @since 1.0
     */
    private static File getRuntimeArchiveFile( JAVA_RUNTIME_ARCHIVE javaRuntimeArchive, Path baseFolder ) {
        final Path archiveDownloadFilePath = baseFolder.resolve( RUNTIME_DOWNLOAD_FOLDER )
                                                       .resolve( javaRuntimeArchive.filename );
        return SynchronizedFileManager.getSynchronizedFile( archiveDownloadFilePath );
    }

    /**
     * Gets the synchronized {@link File} object from the {@link SynchronizedFileManager} for the downloaded runtime
     * archive extracted folder for the specified {@link JAVA_RUNTIME_ARCHIVE} in the specified
     * <code>baseFolder</code>.
     *
     * @param javaRuntimeArchive {@link JAVA_RUNTIME_ARCHIVE} to get synchronized {@link File} object for
     * @param baseFolder         the base folder (parent folder) for the 'runtimes' folder which stores information
     *                           caches and runtimes
     *
     * @return synchronized {@link File} object from the {@link SynchronizedFileManager} for the downloaded runtime
     *         archive extracted folder for the specified {@link JAVA_RUNTIME_ARCHIVE} in the specified
     *         <code>baseFolder</code>
     *
     * @since 1.0
     */
    private static File getRuntimeArchiveExtractedFolder( JAVA_RUNTIME_ARCHIVE javaRuntimeArchive, Path baseFolder ) {
        final int javaRuntimeArchiveFilenameExtensionIndex = javaRuntimeArchive.filename.indexOf(
                "." + javaRuntimeArchive.packageType.name() );
        final Path archiveDownloadExtractedFolderPath = baseFolder.resolve( RUNTIME_DOWNLOAD_FOLDER )
                                                                  .resolve( javaRuntimeArchive.filename.substring( 0,
                                                                                                                   javaRuntimeArchiveFilenameExtensionIndex ) );
        return SynchronizedFileManager.getSynchronizedFile( archiveDownloadExtractedFolderPath );
    }

    /**
     * Gets the API URL to retrieve the latest download information for a specified feature version of the Java JRE.
     * <p>
     * For example, https://api.bell-sw.com/v1/liberica/releases?version-feature=11&version-modifier=latest&bitness=64&os
     * =macos&installation-type=archive&bundle-type=jre&arch=arm will return download information for latest version of
     * JRE 11, in archive format (zip or tar.gz), for ARM 64 systems running macOS.
     *
     * @param featureVersion feature version of the Java JRE (8, 9, 10, etc.)
     *
     * @return API URL to retrieve the latest download information for a specified feature version of the Java JRE
     *
     * @since 1.0
     */
    private static String getVersionInformationApiUrlForFeatureVersion( String featureVersion ) {
        // Get os name for API
        String osName;
        if ( SystemUtils.IS_OS_WINDOWS ) {
            osName = JAVA_RUNTIME_ARCHIVE.OS.WINDOWS.getOs();
        }
        else if ( SystemUtils.IS_OS_MAC ) {
            osName = JAVA_RUNTIME_ARCHIVE.OS.MACOS.getOs();
        }
        else {
            osName = JAVA_RUNTIME_ARCHIVE.OS.LINUX.getOs();
        }

        // Get architecture and bitness
        String architecture;
        String bitness;
        switch ( System.getProperty( "os.arch" ) ) {
            case "i386" -> {
                architecture = JAVA_RUNTIME_ARCHIVE.ARCHITECTURE.X86.getArchitecture();
                bitness = JAVA_RUNTIME_ARCHIVE.BITNESS._32.getBitness();
            }
            case "aarch64" -> {
                architecture = JAVA_RUNTIME_ARCHIVE.ARCHITECTURE.ARM.getArchitecture();
                bitness = JAVA_RUNTIME_ARCHIVE.BITNESS._64.getBitness();
            }
            case "arm" -> {
                architecture = JAVA_RUNTIME_ARCHIVE.ARCHITECTURE.ARM.getArchitecture();
                bitness = JAVA_RUNTIME_ARCHIVE.BITNESS._32.getBitness();
            }
            default -> {
                architecture = JAVA_RUNTIME_ARCHIVE.ARCHITECTURE.X86.getArchitecture();
                bitness = JAVA_RUNTIME_ARCHIVE.BITNESS._64.getBitness();
            }
        }

        return "https://api.bell-sw.com/v1/liberica/releases?version-feature=" +
                featureVersion +
                "&version-modifier=latest&bitness=" +
                bitness +
                "&os=" +
                osName +
                "&installation-type=archive&bundle-type=jre&arch=" +
                architecture;
    }

    /**
     * Class used for storing the JSON download and release information for a Java runtime archive.
     *
     * @author Mica Technologies
     * @version 1.0
     * @apiNote See reference: https://api.bell-sw.com/api.html#/Binaries/get_liberica_releases
     * @since 1.0
     */
    private static final class JAVA_RUNTIME_ARCHIVE
    {
        /**
         * The file name of the Java runtime archive.
         *
         * @since 1.0
         */
        private String filename;

        /**
         * The download URL of the Java runtime archive.
         *
         * @since 1.0
         */
        private String downloadUrl;

        /**
         * The size of the Java runtime archive (in bytes)
         *
         * @since 1.0
         */
        private int size;

        /**
         * The SHA-1 checksum of the Java runtime archive.
         *
         * @since 1.0
         */
        private String sha1;

        /**
         * The Java runtime archive (full) version string.
         *
         * @since 1.0
         */
        private String version;

        /**
         * The Java runtime archive operating system (OS).
         *
         * @since 1.0
         */
        private OS os;

        /**
         * Enumeration used to identify the operating system (OS) of a Java runtime archive.
         *
         * @version 1.0
         * @since 1.0
         */
        enum OS
        {
            /**
             * Linux OS value.
             *
             * @since 1.0
             */
            @SerializedName( "linux" ) LINUX( "linux" ),

            /**
             * Linux musl OS value.
             *
             * @since 1.0
             */
            @SerializedName( "linux-musl" ) LINUX_MUSL( "linux-musl" ),

            /**
             * macOS OS value.
             *
             * @since 1.0
             */
            @SerializedName( "macos" ) MACOS( "macos" ),

            /**
             * Solaris OS value.
             *
             * @since 1.0
             */
            @SerializedName( "solaris" ) SOLARIS( "solaris" ),

            /**
             * Windows OS value.
             *
             * @since 1.0
             */
            @SerializedName( "windows" ) WINDOWS( "windows" );

            /**
             * Stored OS string value.
             *
             * @since 1.0
             */
            private final String os;

            /**
             * Constructor for OS enumeration with given OS value.
             *
             * @param os OS string value
             *
             * @since 1.0
             */
            OS( String os ) {
                this.os = os;
            }

            /**
             * Gets the OS string value.
             *
             * @return OS string value
             *
             * @since 1.0
             */
            public String getOs() {
                return os;
            }
        }

        /**
         * The Java runtime archive bitness.
         *
         * @since 1.0
         */
        private BITNESS bitness;

        /**
         * Enumeration used to identify the bitness of a Java runtime archive.
         *
         * @version 1.0
         * @since 1.0
         */
        enum BITNESS
        {
            /**
             * 32-Bit BITNESS value.
             *
             * @since 1.0
             */
            @SerializedName( "32" ) _32( "32" ),

            /**
             * 64-Bit BITNESS value.
             *
             * @since 1.0
             */
            @SerializedName( "64" ) _64( "64" );

            /**
             * Stored bitness string value.
             *
             * @since 1.0
             */
            private final String bitness;

            /**
             * Constructor for bitness enumeration with given string value.
             *
             * @param bitness bitness string value
             *
             * @since 1.0
             */
            BITNESS( String bitness ) {
                this.bitness = bitness;
            }

            /**
             * Gets the bitness string value.
             *
             * @return bitness string value
             *
             * @since 1.0
             */
            public String getBitness() {
                return bitness;
            }
        }

        /**
         * Integer value for the feature version of the Java runtime archive.
         *
         * @since 1.0
         */
        private int featureVersion;

        /**
         * Integer value for the interim version of the Java runtime archive.
         *
         * @since 1.0
         */
        private int interimVersion;

        /**
         * Integer value for the update version of the Java runtime archive.
         *
         * @since 1.0
         */
        private int updateVersion;

        /**
         * Integer value for the patch version of the Java runtime archive.
         *
         * @since 1.0
         */
        private int patchVersion;

        /**
         * Integer value for the build version of the Java runtime archive.
         *
         * @since 1.0
         */
        private int buildVersion;

        /**
         * Boolean flag indicating if the Java runtime archive is the latest version for the feature version. (Example:
         * 18.2, 18.1, 18.0, etc.)
         *
         * @since 1.0
         */
        private boolean latestInFeatureVersion;

        /**
         * Boolean flag indicating if the Java runtime archive is the latest version with long term support (LTS)
         * status.
         *
         * @since 1.0
         */
        private boolean latestLTS;

        /**
         * Boolean flag indicating if the Java runtime archive is the latest version. (Example: 18, 17, 16, etc.)
         *
         * @since 1.0
         */
        private boolean latest;

        /**
         * Boolean flag indicating if the Java runtime archive has long term support (LTS) status.
         *
         * @since 1.0
         */
        private boolean LTS;

        /**
         * Boolean flag indicating if the Java runtime archive has general availability (GA) status (stable).
         *
         * @since 1.0
         */
        private boolean GA;

        /**
         * Boolean flag indicating if the Java runtime archive has built-in JavaFX modules.
         *
         * @since 1.0
         */
        private boolean FX;

        /**
         * Boolean flag indicating if the Java runtime archive has reached end of life (EOL) status.
         *
         * @since 1.0
         */
        private boolean EOL;

        /**
         * The Java runtime archive architecture.
         *
         * @since 1.0
         */
        private ARCHITECTURE architecture;

        /**
         * Enumeration used to identify a Java runtime archive architecture.
         *
         * @version 1.0
         * @since 1.0
         */
        enum ARCHITECTURE
        {
            /**
             * ARM ARCHITECTURE value.
             *
             * @since 1.0
             */
            @SerializedName( "arm" ) ARM( "arm" ),

            /**
             * PPC ARCHITECTURE value.
             *
             * @since 1.0
             */
            @SerializedName( "ppc" ) PPC( "ppc" ),

            /**
             * SPARC ARCHITECTURE value.
             *
             * @since 1.0
             */
            @SerializedName( "sparc" ) SPARC( "sparc" ),

            /**
             * x86 ARCHITECTURE value.
             *
             * @since 1.0
             */
            @SerializedName( "x86" ) X86( "x86" );

            /**
             * Stored architecture string value.
             *
             * @since 1.0
             */
            private final String architecture;

            /**
             * Constructor for architecture enumeration with given string value.
             *
             * @param architecture architecture string value
             *
             * @since 1.0
             */
            ARCHITECTURE( String architecture ) {
                this.architecture = architecture;
            }

            /**
             * Gets the architecture string value.
             *
             * @return architecture string value
             *
             * @since 1.0
             */
            public String getArchitecture() {
                return architecture;
            }
        }

        /**
         * The Java runtime archive installation type.
         *
         * @since 1.0
         */
        private INSTALLATION_TYPE installationType;

        /**
         * Enumeration used to identify a Java runtime archive installation type.
         *
         * @version 1.0
         * @since 1.0
         */
        enum INSTALLATION_TYPE
        {
            @SerializedName( "archive" ) ARCHIVE, @SerializedName( "installer" ) INSTALLER

        }

        /**
         * The Java runtime archive package type.
         *
         * @since 1.0
         */
        private PACKAGE_TYPE packageType;

        /**
         * Enumeration used to identify a Java runtime archive package type.
         *
         * @version 1.0
         * @since 1.0
         */
        enum PACKAGE_TYPE
        {
            @SerializedName( "apk" ) APK,
            @SerializedName( "deb" ) DEB,
            @SerializedName( "dmg" ) DMG,
            @SerializedName( "msi" ) MSI,
            @SerializedName( "pkg" ) PKG,
            @SerializedName( "RPM" ) RPM,
            @SerializedName( "src.tar.gz" ) SRC_TAR_GZ,
            @SerializedName( "tar.gz" ) TAR_GZ,
            @SerializedName( "zip" ) ZIP
        }

        /**
         * The Java runtime archive bundle type.
         *
         * @since 1.0
         */
        private BUNDLE_TYPE bundleType;

        /**
         * Enumeration used to identify a Java runtime archive bundle type.
         *
         * @version 1.0
         * @since 1.0
         */
        enum BUNDLE_TYPE
        {
            @SerializedName( "jdk" ) JDK,
            @SerializedName( "jdk-full" ) JDK_FULL,
            @SerializedName( "jdk-lite" ) JDK_LITE,
            @SerializedName( "jre" ) JRE,
            @SerializedName( "jre-full" ) JRE_FULL
        }

        /**
         * Gets the file name of the Java runtime archive.
         *
         * @return file name of the Java runtime archive.
         *
         * @since 1.0
         */
        public String getFilename() {
            return filename;
        }

        /**
         * Gets the download URL of the Java runtime archive.
         *
         * @return download URL of the Java runtime archive.
         *
         * @since 1.0
         */
        public String getDownloadUrl() {
            return downloadUrl;
        }

        /**
         * Gets the size of the Java runtime archive (in bytes).
         *
         * @return size of the Java runtime archive (in bytes),
         *
         * @since 1.0
         */
        public int getSize() {
            return size;
        }

        /**
         * Gets the SHA-1 checksum of the Java runtime archive.
         *
         * @return SHA-1 checksum of the Java runtime archive.
         *
         * @since 1.0
         */
        public String getSha1() {
            return sha1;
        }

        /**
         * Gets the Java runtime archive (full) version string.
         *
         * @return Java runtime archive (full) version string.
         *
         * @since 1.0
         */
        public String getVersion() {
            return version;
        }

        /**
         * Gets the Java runtime archive operating system (OS).
         *
         * @return Java runtime archive operating system.
         *
         * @since 1.0
         */
        public OS getOs() {
            return os;
        }

        /**
         * Gets the Java runtime archive bitness.
         *
         * @return Java runtime archive bitness.
         *
         * @since 1.0
         */
        public BITNESS getBitness() {
            return bitness;
        }

        /**
         * Gets the feature version of the Java runtime archive.
         *
         * @return feature version of the Java runtime archive.
         *
         * @since 1.0
         */
        public int getFeatureVersion() {
            return featureVersion;
        }

        /**
         * Gets the interim version of the Java runtime archive.
         *
         * @return interim version of the Java runtime archive.
         *
         * @since 1.0
         */
        public int getInterimVersion() {
            return interimVersion;
        }

        /**
         * Gets the update version of the Java runtime archive.
         *
         * @return update version of the Java runtime archive.
         *
         * @since 1.0
         */
        public int getUpdateVersion() {
            return updateVersion;
        }

        /**
         * Gets the patch version of the Java runtime archive.
         *
         * @return patch version of the Java runtime archive.
         *
         * @since 1.0
         */
        public int getPatchVersion() {
            return patchVersion;
        }

        /**
         * Gets the build version of the Java runtime archive.
         *
         * @return build version of the Java runtime archive.
         *
         * @since 1.0
         */
        public int getBuildVersion() {
            return buildVersion;
        }

        /**
         * Gets a boolean indicating if the Java runtime archive is the latest version for the feature version.
         * (Example: 18.2, 18.2, 18.0, etc.)
         *
         * @return boolean indicating if the Java runtime archive is the latest version for the feature version.
         *
         * @since 1.0
         */
        public boolean isLatestInFeatureVersion() {
            return latestInFeatureVersion;
        }

        /**
         * Gets a boolean indicating if the Java runtime archive is the latest version with long term support (LTS)
         * status.
         *
         * @return boolean indicating if the Java runtime archive is the latest version with long term support (LTS)
         *         status.
         *
         * @since 1.0
         */
        public boolean isLatestLTS() {
            return latestLTS;
        }

        /**
         * Gets a boolean indicating if the Java runtime archive is the latest version. (Example: 18, 17, 16, etc.)
         *
         * @return boolean indicating if the Java runtime archive is the latest version.
         *
         * @since 1.0
         */
        public boolean isLatest() {
            return latest;
        }

        /**
         * Gets a boolean indicating if the Java runtime archive has long term support (LTS) status.
         *
         * @return boolean indicating if the Java runtime archive has long term support (LTS) status.
         *
         * @since 1.0
         */
        public boolean isLTS() {
            return LTS;
        }

        /**
         * Gets a boolean indicating if the Java runtime archive has general availability (GA) status (stable).
         *
         * @return boolean indicating if the Java runtime archive has general availability (GA) status.
         *
         * @since 1.0
         */
        public boolean isGA() {
            return GA;
        }

        /**
         * Gets a boolean indicating if the Java runtime archive has built-in JavaFX modules.
         *
         * @return boolean indicating if the Java runtime archive has built-in JavaFX modules.
         *
         * @since 1.0
         */
        public boolean isFX() {
            return FX;
        }

        /**
         * Gets a boolean indicating if the Java runtime archive has reached end of life (EOL) status.
         *
         * @return boolean indicating if the Java runtime archive has reached end of life (EOL) status.
         *
         * @since 1.0
         */
        public boolean isEOL() {
            return EOL;
        }

        /**
         * Gets the Java runtime archive architecture. (ARM, X86, etc.)
         *
         * @return Java runtime archive architecture.
         *
         * @since 1.0
         */
        public ARCHITECTURE getArchitecture() {
            return architecture;
        }

        /**
         * Gets the Java runtime archive installation type.
         *
         * @return Java runtime archive installation type.
         *
         * @since 1.0
         */
        public INSTALLATION_TYPE getInstallationType() {
            return installationType;
        }

        /**
         * Gets the Java runtime archive package type.
         *
         * @return Java runtime archive package type.
         *
         * @since 1.0
         */
        public PACKAGE_TYPE getPackageType() {
            return packageType;
        }

        /**
         * Gets the Java runtime archive bundle type.
         *
         * @return Java runtime archive bundle type.
         *
         * @since 1.0
         */
        public BUNDLE_TYPE getBundleType() {
            return bundleType;
        }
    }
}
