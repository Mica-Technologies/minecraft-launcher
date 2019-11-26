package com.micatechnologies.minecraft.forgelauncher;

import com.google.common.hash.Hashing;
import com.google.common.io.Files;
import com.micatechnologies.minecraft.authlib.MCAuthAccount;
import com.micatechnologies.minecraft.authlib.MCAuthException;
import com.micatechnologies.minecraft.authlib.MCAuthService;
import com.micatechnologies.minecraft.forgemodpacklib.MCForgeModpack;
import com.micatechnologies.minecraft.forgemodpacklib.MCForgeModpackConsts;
import com.micatechnologies.minecraft.forgemodpacklib.MCForgeModpackException;
import com.micatechnologies.minecraft.forgemodpacklib.MCModpackOSUtils;
import org.apache.commons.io.FileUtils;
import org.rauschig.jarchivelib.ArchiveFormat;
import org.rauschig.jarchivelib.Archiver;
import org.rauschig.jarchivelib.ArchiverFactory;
import org.rauschig.jarchivelib.CompressionType;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class MCFLApp {
    //region: Statics/Constants
    public static final int MODE_CLIENT = MCForgeModpackConsts.MINECRAFT_CLIENT_MODE;
    public static final int MODE_SERVER = MCForgeModpackConsts.MINECRAFT_SERVER_MODE;
    //endregion

    //region: App Configuration & Information
    private static final boolean ALLOW_SAVED_USERS = false;
    private static int mode = MODE_CLIENT;
    private static String javaPath = "java";
    private static String clientToken = "";
    private static MCAuthAccount currentUser = null;
    private static MCFLConfiguration launcherConfig = null;
    private static List< MCForgeModpack > modpacks = new ArrayList<>();
    //endregion

    //region: Get/Set Methods
    public static int getMode() {
        return mode;
    }

    public static String getJavaPath() {
        return javaPath;
    }

    public static String getClientToken() {
        // Client token not loaded to memory, need to load first
        if ( clientToken.equals( "" ) ) {
            // Create file object for local token file
            File tokenFile = new File( MCFLConstants.LAUNCHER_CLIENT_TOKEN_FILE );

            // Check if file exists.
            if ( tokenFile.isFile() ) {
                try {
                    // If file exists, read client token from file
                    clientToken = FileUtils.readFileToString( tokenFile, Charset.defaultCharset() );
                }
                catch ( IOException e ) {
                    // If an error occurs during read, generate new client token.
                    clientToken = UUID.randomUUID().toString();
                    try {
                        // Attempt to write new client token to file.
                        FileUtils.writeStringToFile( tokenFile, clientToken, Charset.defaultCharset() );
                    }
                    catch ( IOException ee ) {
                        // Output error if attemp to write new client token fails
                        MCFLLogger.error( "The client token could not be written to persistent storage. Remember me login functionality will not work.", 305, null );
                    }
                }

            }
            else {
                // If an error occurs during read, generate new client token.
                clientToken = UUID.randomUUID().toString();
                try {
                    // Attempt to write new client token to file.
                    FileUtils.writeStringToFile( tokenFile, clientToken, Charset.defaultCharset() );
                }
                catch ( IOException ee ) {
                    // Output error if attemp to write new client token fails
                    MCFLLogger.error( "The client token could not be written to persistent storage. Remember me login functionality will not work.", 304, null );
                }
            }
        }

        return clientToken;
    }

    public static MCAuthAccount getCurrentUser() {
        return currentUser;
    }

    public static MCFLConfiguration getLauncherConfig() {
        if ( launcherConfig == null ) {
            try {
                launcherConfig = MCFLConfiguration.open();
            }
            catch ( IOException e ) {
                MCFLLogger.error( "Unable to load launcher configuration from persistent storage. Configuration may be reset.", 306, null );
            }
        }
        return launcherConfig;
    }

    public static List< MCForgeModpack > getModpacks() {
        return modpacks;
    }

    //endregion

    //region: Helper Methods
    public static void saveConfig() {
        try {
            getLauncherConfig().save();
        }
        catch ( IOException e ) {
            MCFLLogger.error( "Unable to save launcher configuration to disk. Configuration may be lost!", 302, null );
        }
    }

    public static String getInstallPath() {
        if ( mode == MODE_CLIENT ) return MCFLConstants.LAUNCHER_CLIENT_INSTALLATION_DIRECTORY;
        else if ( mode == MODE_SERVER ) return MCFLConstants.LAUNCHER_SERVER_INSTALLATION_DIRECTORY;
        else {
            errorIllegalLauncherMode();
            return MCFLConstants.LAUNCHER_SERVER_INSTALLATION_DIRECTORY;
        }
    }

    public static String getJREFolderPath() {
        return getInstallPath() + File.separator + "runtime";
    }

    public static String getModpacksInstallPath() {
        return getInstallPath() + File.separator + "installs";
    }

    public static void errorIllegalLauncherMode() {
        String msg = "An illegal launcher mode is in use. This should not happen!";
        MCFLLogger.error( msg, 307, null );
    }

    public static void buildMemoryModpackList() {
        for ( String s : getLauncherConfig().getModpacks() ) {
            Path modpackRootFolder = Paths.get( getModpacksInstallPath() + File.separator + "sandbox" );
            try {
                MCForgeModpack tempPack = MCForgeModpack.downloadFromURL( new URL( s ), modpackRootFolder, mode );
                try {
                    modpackRootFolder = Paths.get( getModpacksInstallPath() + File.separator + tempPack.getPackName().replaceAll( "[^a-zA-Z0-9]", "" ) );
                    modpacks.add( MCForgeModpack.downloadFromURL( new URL( s ), modpackRootFolder, mode ) );
                }
                catch ( MCForgeModpackException | MalformedURLException e ) {
                    MCFLLogger.error( "Unable to download modpack manifest from specified URL " + s + "!", 310, null );
                }
            }
            catch ( MCForgeModpackException | MalformedURLException e ) {
                MCFLLogger.error( "Unable to download modpack manifest from specified URL " + s + "!", 311, null );
            }
        }
    }
    //endregion

    //region: Function Methods
    public static void play( int modpack, MCFLGenericGUI gui ) {
        if ( mode == MODE_CLIENT ) playClient( modpack, gui );
        else if ( mode == MODE_SERVER ) playServer( modpack );
    }

    private static void playClient( int modpack, MCFLGenericGUI gui ) {
        // Verify user logged in and mode is client
        if ( currentUser == null ) return;
        if ( mode != MODE_CLIENT ) return;

        // Launch selected modpack
        int minRAMMB = ( int ) ( getLauncherConfig().getMinRAM() * 1024 );
        int maxRAMMB = ( int ) ( getLauncherConfig().getMaxRAM() * 1024 );
        try {
            MCForgeModpack mp = modpacks.get( modpack );
            mp.startGame( getJavaPath(), currentUser.getFriendlyName(), currentUser.getUserIdentifier(), currentUser.getLastAccessToken(), minRAMMB, maxRAMMB );
        }
        catch ( MCForgeModpackException e ) {
            MCFLLogger.error( "Unable to start game.", 312, gui.getCurrentStage() );
        }
    }

    private static void playServer( int modpack ) {
        if ( mode != MODE_CLIENT ) return;

        int minRAMMB = ( int ) ( getLauncherConfig().getMinRAM() * 1024 );
        int maxRAMMB = ( int ) ( getLauncherConfig().getMaxRAM() * 1024 );
        try {
            modpacks.get( modpack ).startGame( getJavaPath(), currentUser.getFriendlyName(), currentUser.getUserIdentifier(), currentUser.getLastAccessToken(), minRAMMB, maxRAMMB );
        }
        catch ( MCForgeModpackException e ) {
            MCFLLogger.error( "Unable to start game.", 313, null );
        }
    }

    private static void doModpackSelection( int initPackIndex ) {
        if ( mode == MODE_CLIENT ) {
            MCFLModpacksGUI modpacksGUI = new MCFLModpacksGUI();
            modpacksGUI.open();
            modpacksGUI.setSelectedModpackIndex( initPackIndex );
        }
        else if ( mode == MODE_SERVER ) {
            playServer( initPackIndex );
        }
        else {
            errorIllegalLauncherMode();
        }
    }

    private static void doLocalJDK() {
        // Create a progress GUI if in client mod
        MCFLProgressGUI progressGUI = null;
        if ( mode == MODE_CLIENT ) {
            progressGUI = new MCFLProgressGUI();
            progressGUI.open();
            progressGUI.setUpperText( "Preparing Minecraft Runtime" );
            progressGUI.setLowerText( "Preparing JRE Folder" );
            progressGUI.setProgress( 0.0 );
        }

        // Store JRE path and create file objects
        String jreFolderPath = getJREFolderPath();
        File jreArchiveFile = new File( jreFolderPath + File.separator + "rt.archive" );
        File jreHashFile = new File( jreFolderPath + File.separator + "rt.hash" );
        File jreFolderFile = new File( jreFolderPath );

        // Verify JRE folder exists
        if ( progressGUI != null ) {
            progressGUI.setLowerText( "Verifying JRE Folder" );
            progressGUI.setProgress( 10.0 );
        }
        jreFolderFile.mkdirs();
        jreFolderFile.setReadable( true );
        jreFolderFile.setWritable( true );

        // Get proper URL and archive format
        if ( progressGUI != null ) {
            progressGUI.setLowerText( "Preparing JRE Information" );
            progressGUI.setProgress( 20.0 );
        }
        String jreArchiveDownloadURL;
        String jreHashDownloadURL;
        ArchiveFormat jreArchiveFormat;
        CompressionType jreArchiveCompressionType;
        if ( MCModpackOSUtils.isWindows() ) {
            jreArchiveFormat = ArchiveFormat.ZIP;
            jreArchiveCompressionType = null;
            jreArchiveDownloadURL = MCFLConstants.URL_JRE_WIN;
            jreHashDownloadURL = MCFLConstants.URL_JRE_WIN_HASH;
            javaPath = getJREFolderPath() + File.separator + MCFLConstants.JRE_EXTRACTED_FOLDER_NAME + File.separator + "bin" + File.separator + "java.exe";
        }
        else if ( MCModpackOSUtils.isMac() ) {
            jreArchiveFormat = ArchiveFormat.TAR;
            jreArchiveCompressionType = CompressionType.GZIP;
            jreArchiveDownloadURL = MCFLConstants.URL_JRE_MAC;
            jreHashDownloadURL = MCFLConstants.URL_JRE_MAC_HASH;
            javaPath = getJREFolderPath() + File.separator + MCFLConstants.JRE_EXTRACTED_FOLDER_NAME + File.separator + "Contents" + File.separator + "Home" + File.separator + "bin" + File.separator + "java";
        }
        else if ( MCModpackOSUtils.isUnix() ) {
            jreArchiveFormat = ArchiveFormat.TAR;
            jreArchiveCompressionType = CompressionType.GZIP;
            jreArchiveDownloadURL = MCFLConstants.URL_JRE_WIN;
            jreHashDownloadURL = MCFLConstants.URL_JRE_WIN_HASH;
            javaPath = getJREFolderPath() + File.separator + MCFLConstants.JRE_EXTRACTED_FOLDER_NAME + File.separator + "bin" + File.separator + "java";
        }
        else {
            if ( progressGUI != null )
                MCFLLogger.error( "Unable to identify operating system. Launcher will not cache JRE for gameplay.", 308, progressGUI.getCurrentStage() );
            else
                MCFLLogger.error( "Unable to identify operating system. Launcher will not cache JRE for gameplay.", 308, null );

            return;
        }

        // Get hash of JRE from URL
        if ( progressGUI != null ) {
            progressGUI.setLowerText( "Downloading JRE Hash" );
            progressGUI.setProgress( 25.0 );
        }
        try {
            FileUtils.copyURLToFile( new URL( jreHashDownloadURL ), jreHashFile );
        }
        catch ( IOException e ) {
            if ( progressGUI != null )
                MCFLLogger.error( "Unable to create a file necessary for maintaining launcher integrity. Using system Java for safety.", 309, progressGUI.getCurrentStage() );
            else
                MCFLLogger.error( "Unable to create a file necessary for maintaining launcher integrity. Using system Java for safety.", 309, null );
            javaPath = "java";
            return;
        }

        try {
            if ( progressGUI != null ) {
                progressGUI.setLowerText( "Verifying Local JRE" );
                progressGUI.setProgress( 35.0 );
            }
            // Check if archive either doesn't exist or doesn't match hash
            if ( !jreArchiveFile.exists() || !Files.hash( jreArchiveFile, Hashing.sha256() ).toString().equalsIgnoreCase(
                    FileUtils.readFileToString( jreHashFile, Charset.defaultCharset() ).split( " " )[ 0 ] ) ) {
                if ( progressGUI != null ) {
                    progressGUI.setLowerText( "Downloading Configured JRE" );
                    progressGUI.setProgress( 45.0 );
                }
                // Download archive from URL
                FileUtils.copyURLToFile( new URL( jreArchiveDownloadURL ), jreArchiveFile );

                // Delete previous extracted JRE
                File extractedJREFolder = new File( getJREFolderPath() + File.separator + MCFLConstants.JRE_EXTRACTED_FOLDER_NAME );
                if ( extractedJREFolder.exists() ) {
                    FileUtils.deleteDirectory( extractedJREFolder );
                }

                // Extract downloaded JRE
                if ( progressGUI != null ) {
                    progressGUI.setLowerText( "Extracting Downloaded JRE" );
                    progressGUI.setProgress( 70.0 );
                }
                Archiver archiver;
                if ( jreArchiveCompressionType != null ) {
                    archiver = ArchiverFactory.createArchiver( jreArchiveFormat, jreArchiveCompressionType );
                }
                else {
                    archiver = ArchiverFactory.createArchiver( jreArchiveFormat );
                }
                archiver.extract( jreArchiveFile, jreFolderFile );
            }
        }
        catch ( IOException e ) {
            if ( progressGUI != null )
                MCFLLogger.error( "Unable to create local runtime. Using system Java.", 309, progressGUI.getCurrentStage() );
            else MCFLLogger.error( "Unable to create local runtime. Using system Java.", 309, null );
            javaPath = "java";
            return;
        }
        if ( progressGUI != null ) {
            progressGUI.setLowerText( "Finished JRE Preparation" );
            progressGUI.setProgress( 100.0 );
        }
    }

    public static void loginUser( MCAuthAccount account ) {
        // Store account to current user
        currentUser = account;
    }

    public static void logoutCurrentUser() {
        // Check if current user is not null
        if ( currentUser != null ) {
            try {
                // Invalidate current user
                MCAuthService.invalidateLogin( getCurrentUser(), getClientToken() );

                // Delete current user information on disk (if exists)
                File savedUserFile = new File( MCFLConstants.LAUNCHER_CLIENT_SAVED_USER_FILE );
                savedUserFile.delete();
            }
            catch ( MCAuthException e ) {
                MCFLLogger.error( "Unable to invalidate cached token prior to logout. Local account information will still be destroyed.", 303, null );
            }
        }

        // TODO: Close existing GUIs and return to login screen
    }

    public static void doLogin() {
        // Login should only be handled in client mode
        if ( mode == MODE_CLIENT ) {
            // Check for saved user on disk. Load and return if found
            File savedUserFile = new File( MCFLConstants.LAUNCHER_CLIENT_SAVED_USER_FILE );
            if ( ALLOW_SAVED_USERS && savedUserFile.isFile() ) {
                try {
                    currentUser = MCAuthAccount.readFromFile( MCFLConstants.LAUNCHER_CLIENT_SAVED_USER_FILE );
                    MCAuthService.refreshAuth( getCurrentUser(), getClientToken() );
                    return;
                }
                catch ( MCAuthException e ) {
                    MCFLLogger.error( "Unable to load remembered user account.", 301, null );
                }
            }

            // Show login screen
            MCFLLoginGUI loginGUI = new MCFLLoginGUI();
            loginGUI.open();

            // Wait for login screen to complete
            try {
                loginGUI.loginSuccessLatch.await();
            }
            catch ( InterruptedException e ) {
                MCFLLogger.error( "Unable to wait for pending login task.", 300, null );
            }

            // Close login screen once complete
            loginGUI.close();
        }
    }
    //endregion

    //region: Core Methods
    public static void main( String[] args ) {
        // NOTE: Saved users DISABLED right now to due bug.
        doLogin();
        doLocalJDK();
        buildMemoryModpackList();
        doModpackSelection( 1 );
        // TODO: Download modpack files and assets
    }
    //endregion
}
