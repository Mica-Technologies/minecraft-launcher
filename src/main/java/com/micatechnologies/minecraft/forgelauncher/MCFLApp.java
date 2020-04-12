package com.micatechnologies.minecraft.forgelauncher;

import com.google.common.hash.Hashing;
import com.google.common.io.Files;
import com.jfoenix.controls.JFXProgressBar;
import com.micatechnologies.minecraft.forgelauncher.auth.MCAuthAccount;
import com.micatechnologies.minecraft.forgelauncher.exceptions.FLAuthenticationException;
import com.micatechnologies.minecraft.forgelauncher.auth.MCAuthService;
import com.micatechnologies.minecraft.forgelauncher.exceptions.FLModpackException;
import com.micatechnologies.minecraft.forgelauncher.gui.FLGUIController;
import com.micatechnologies.minecraft.forgelauncher.gui.FLLoginGUI;
import com.micatechnologies.minecraft.forgelauncher.gui.FLProgressGUI;
import com.micatechnologies.minecraft.forgelauncher.modpack.MCForgeModpack;
import com.micatechnologies.minecraft.forgelauncher.modpack.MCForgeModpackConsts;
import com.micatechnologies.minecraft.forgelauncher.modpack.MCForgeModpackProgressProvider;
import com.micatechnologies.minecraft.forgelauncher.utilities.FLGUIUtils;
import com.micatechnologies.minecraft.forgelauncher.utilities.FLNetworkUtils;
import com.micatechnologies.minecraft.forgelauncher.utilities.FLSystemUtils;
import com.micatechnologies.minecraft.forgelauncher.utilities.FLLogUtil;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.SystemUtils;
import org.rauschig.jarchivelib.ArchiveFormat;
import org.rauschig.jarchivelib.Archiver;
import org.rauschig.jarchivelib.ArchiverFactory;
import org.rauschig.jarchivelib.CompressionType;

import java.awt.*;
import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

public class MCFLApp {
    //region: Statics/Constants
    public static final int MODE_CLIENT = MCForgeModpackConsts.MINECRAFT_CLIENT_MODE;
    public static final int MODE_SERVER = MCForgeModpackConsts.MINECRAFT_SERVER_MODE;
    //endregion

    //region: App Configuration & Information
    private static final boolean ALLOW_SAVED_USERS = true;
    private static boolean loopLogin = true;
    private static int mode = MODE_CLIENT;
    private static String javaPath = "java";
    private static String clientToken = "";
    private static MCAuthAccount currentUser = null;
    private static MCFLConfiguration launcherConfig = null;
    private static List< MCForgeModpack > modpacks = new ArrayList<>();
    private static final File savedUserFile = new File( MCFLConstants.LAUNCHER_CLIENT_SAVED_USER_FILE );
    //endregion

    //region: Get/Set Methods
    public static int getMode() {
        return mode;
    }

    public static String getJavaPath() {
        return javaPath;
    }

    public static boolean getLoopLogin() {
        return loopLogin;
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
                        // Output error if attempt to write new client token fails
                        FLLogUtil.error( "The client token could not be written to persistent storage. Remember me login functionality will not work.", 305, null );
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
                    // Output error if attempt to write new client token fails
                    FLLogUtil.error( "The client token could not be written to persistent storage. Remember me login functionality will not work.", 304, null );
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
                FLLogUtil.error( "Unable to load launcher configuration from persistent storage. Configuration may be reset.", 306, null );
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
            FLLogUtil.error( "Unable to save launcher configuration to disk. Configuration may be lost!", 302, null );
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

    public static String getLogFolderPath() {
        return getInstallPath() + File.separator + "logs";
    }

    public static String getModpacksInstallPath() {
        return getInstallPath() + File.separator + "installs";
    }

    public static void errorIllegalLauncherMode() {
        String msg = "An illegal launcher mode is in use. This should not happen!";
        FLLogUtil.error( msg, 307, null );
    }

    public static void buildMemoryModpackList() {
        FLProgressGUI progressGUI = null;
        if ( mode == MODE_CLIENT ) {
            progressGUI = new FLProgressGUI();
            progressGUI.show();
            progressGUI.setUpperLabelText( "Parsing Modpack List" );
            progressGUI.setLowerLabelText( "Setting Up" );
            progressGUI.setProgress( JFXProgressBar.INDETERMINATE_PROGRESS );
        }

        modpacks.clear();
        for ( String s : getLauncherConfig().getModpacks() ) {
            Path modpackRootFolder = Paths.get( getModpacksInstallPath() + File.separator + "sandbox" );
            if ( progressGUI != null ) {
                progressGUI.setLowerLabelText( "Checking " + s );
            }
            try {
                MCForgeModpack tempPack = MCForgeModpack.downloadFromURL( new URL( s ), modpackRootFolder, mode );
                try {
                    try {
                        FileUtils.deleteDirectory( modpackRootFolder.toFile() );
                    }
                    catch ( IOException e ) {
                        FLLogUtil.debug( "Failed to cleanup sandbox install folder." );
                    }
                    if ( progressGUI != null ) {
                        progressGUI.setLowerLabelText( "Configuring " + tempPack.getPackName() );
                    }
                    modpackRootFolder = Paths.get( getModpacksInstallPath() + File.separator + tempPack.getPackName().replaceAll( "[^a-zA-Z0-9]", "" ) );
                    MCForgeModpack pack = MCForgeModpack.downloadFromURL( new URL( s ), modpackRootFolder, mode );
                    modpacks.add( pack );
                }
                catch ( FLModpackException | MalformedURLException e ) {
                    if ( progressGUI != null ) {
                        FLLogUtil.error( "Unable to download modpack manifest from specified URL " + s + "!", 310, progressGUI.getCurrentJFXStage() );
                    }
                    else {
                        FLLogUtil.error( "Unable to download modpack manifest from specified URL " + s + "!", 310, null );
                    }
                }
            }
            catch ( FLModpackException | MalformedURLException e ) {
                if ( progressGUI != null ) {
                    FLLogUtil.error( "Unable to download modpack manifest from specified URL " + s + "!", 311, progressGUI.getCurrentJFXStage() );
                }
                else {
                    FLLogUtil.error( "Unable to download modpack manifest from specified URL " + s + "!", 311, null );
                }
            }
        }
        if ( progressGUI != null ) {
            progressGUI.close();
        }
    }

    public static int inferMode() {
        if ( !GraphicsEnvironment.isHeadless() ) {
            FLLogUtil.log( "Inferred client mode" );
            return MODE_CLIENT;
        }
        else {
            FLLogUtil.log( "Inferred server mode" );
            return MODE_SERVER;
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
        FLProgressGUI progressGUI = null;
        try {
            MCForgeModpack mp = modpacks.get( modpack );
            progressGUI = new FLProgressGUI();
            progressGUI.show();

            // Verify configured RAM
            if ( Integer.parseInt( mp.getPackMinRAMGB() ) > getLauncherConfig().getMaxRAM() ) {
                FLLogUtil.error( "Modpack requires a minimum of " + mp.getPackMinRAMGB() + "GB of RAM. Please change your RAM settings in the settings menu.", 50, progressGUI.getCurrentJFXStage() );
                progressGUI.close();
                return;
            }


            FLProgressGUI finalProgressGUI = progressGUI;
            mp.setProgressProvider( new MCForgeModpackProgressProvider() {
                @Override
                public void updateProgressHandler( double percent, String text ) {
                    finalProgressGUI.setUpperLabelText( "Loading " + mp.getPackName() );
                    finalProgressGUI.setLowerLabelText( text );
                    finalProgressGUI.setProgress( percent );
                    if ( percent == 100.0 ) {
                        new Thread( () -> {
                            finalProgressGUI.setLowerLabelText( "Passing to Minecraft" );
                            try {
                                Thread.sleep( 3000 );
                            }
                            catch ( InterruptedException ignored ) {
                            }
                            finalProgressGUI.close();
                        } ).start();
                    }
                }
            } );
            mp.startGame( getJavaPath(), currentUser.getFriendlyName(), currentUser.getUserIdentifier(), currentUser.getLastAccessToken(), minRAMMB, maxRAMMB );
        }
        catch ( FLModpackException e ) {
            e.printStackTrace();
            FLLogUtil.error( "Unable to start game.", 312, gui.getCurrentStage() );
            if ( progressGUI != null ) progressGUI.close();
        }
    }

    private static void playServer( int modpack ) {
        if ( mode != MODE_SERVER ) return;

        int minRAMMB = ( int ) ( getLauncherConfig().getMinRAM() * 1024 );
        int maxRAMMB = ( int ) ( getLauncherConfig().getMaxRAM() * 1024 );
        try {
            MCForgeModpack mp = modpacks.get( modpack );
            mp.setProgressProvider( new MCForgeModpackProgressProvider() {
                @Override
                public void updateProgressHandler( double percent, String text ) {
                    FLLogUtil.log( "Play: " + percent + "% - " + text );
                }
            } );
            mp.startGame( getJavaPath(), "", "", "", minRAMMB, maxRAMMB );
        }
        catch ( FLModpackException e ) {
            FLLogUtil.error( "Unable to start game.", 313, null );
        }
    }

    private static void doModpackSelection( int initPackIndex ) {
        if ( mode == MODE_CLIENT ) {
            MCFLModpacksGUI modpacksGUI = new MCFLModpacksGUI();
            modpacksGUI.open( initPackIndex );
            try {
                modpacksGUI.closedLatch.await();
            }
            catch ( InterruptedException ignored ) {
                FLLogUtil.error( "An error is preventing GUI completion handling. The login screen may not appear after logout.", 316, modpacksGUI.getCurrentStage() );
            }
        }
        else if ( mode == MODE_SERVER ) {
            playServer( initPackIndex );
        }
        else {
            errorIllegalLauncherMode();
        }
    }

    static void clearLocalJDK() throws IOException {
        FileUtils.deleteDirectory( new File( getJREFolderPath() ) );
    }

    public static void closeApp() {
        FLGUIController.closeAllWindows();
        System.exit( 0 );
    }

    static void doLocalJDK() {
        // Create a progress GUI if in client mod
        FLProgressGUI progressGUI = null;
        if ( mode == MODE_CLIENT ) {
            progressGUI = new FLProgressGUI();
            progressGUI.show();
            progressGUI.setUpperLabelText( "Preparing Minecraft Runtime" );
            progressGUI.setLowerLabelText( "Preparing JRE Folder" );
            progressGUI.setProgress( 0.0 );
        }

        // Store JRE path and create file objects
        String jreFolderPath = getJREFolderPath();
        File jreArchiveFile = new File( jreFolderPath + File.separator + "rt.archive" );
        File jreHashFile = new File( jreFolderPath + File.separator + "rt.hash" );
        File jreFolderFile = new File( jreFolderPath );

        // Verify JRE folder exists
        if ( progressGUI != null ) {
            progressGUI.setLowerLabelText( "Verifying JRE Folder" );
            progressGUI.setProgress( 10.0 );
        }
        jreFolderFile.mkdirs();
        jreFolderFile.setReadable( true );
        jreFolderFile.setWritable( true );

        // Get proper URL and archive format
        if ( progressGUI != null ) {
            progressGUI.setLowerLabelText( "Preparing JRE Information" );
            progressGUI.setProgress( 20.0 );
        }
        String jreArchiveDownloadURL;
        String jreHashDownloadURL;
        ArchiveFormat jreArchiveFormat;
        CompressionType jreArchiveCompressionType;
        if ( SystemUtils.IS_OS_WINDOWS ) {
            jreArchiveFormat = ArchiveFormat.ZIP;
            jreArchiveCompressionType = null;
            jreArchiveDownloadURL = MCFLConstants.URL_JRE_WIN;
            jreHashDownloadURL = MCFLConstants.URL_JRE_WIN_HASH;
            javaPath = getJREFolderPath() + File.separator + MCFLConstants.JRE_EXTRACTED_FOLDER_NAME + File.separator + "bin" + File.separator + "java.exe";
        }
        else if ( SystemUtils.IS_OS_MAC ) {
            jreArchiveFormat = ArchiveFormat.TAR;
            jreArchiveCompressionType = CompressionType.GZIP;
            jreArchiveDownloadURL = MCFLConstants.URL_JRE_MAC;
            jreHashDownloadURL = MCFLConstants.URL_JRE_MAC_HASH;
            javaPath = getJREFolderPath() + File.separator + MCFLConstants.JRE_EXTRACTED_FOLDER_NAME + File.separator + "Contents" + File.separator + "Home" + File.separator + "bin" + File.separator + "java";
        }
        else if ( SystemUtils.IS_OS_LINUX ) {
            jreArchiveFormat = ArchiveFormat.TAR;
            jreArchiveCompressionType = CompressionType.GZIP;
            jreArchiveDownloadURL = MCFLConstants.URL_JRE_UNX;
            jreHashDownloadURL = MCFLConstants.URL_JRE_UNX_HASH;
            javaPath = getJREFolderPath() + File.separator + MCFLConstants.JRE_EXTRACTED_FOLDER_NAME + File.separator + "bin" + File.separator + "java";
        }
        else {
            if ( progressGUI != null )
                FLLogUtil.error( "Unable to identify operating system. Launcher will not cache JRE for gameplay.", 308, progressGUI.getCurrentJFXStage() );
            else
                FLLogUtil.error( "Unable to identify operating system. Launcher will not cache JRE for gameplay.", 308, null );
            if ( progressGUI != null ) {
                Platform.setImplicitExit( false );
                new Thread( progressGUI::close ).start();
            }
            return;
        }

        // Get hash of JRE from URL
        if ( progressGUI != null ) {
            progressGUI.setLowerLabelText( "Downloading JRE Hash" );
            progressGUI.setProgress( 25.0 );
        }
        try {
            FLSystemUtils.downloadFileFromURL( new URL( jreHashDownloadURL ), jreHashFile );
        }
        catch ( IOException e ) {
            if ( progressGUI != null )
                FLLogUtil.error( "Unable to create a file necessary for maintaining launcher integrity. Using system Java for safety.", 309, progressGUI.getCurrentJFXStage() );
            else
                FLLogUtil.error( "Unable to create a file necessary for maintaining launcher integrity. Using system Java for safety.", 309, null );
            javaPath = "java";
            if ( progressGUI != null ) {
                Platform.setImplicitExit( false );
                new Thread( progressGUI::close ).start();
            }
            return;
        }

        try {
            if ( progressGUI != null ) {
                progressGUI.setLowerLabelText( "Verifying Local JRE" );
                progressGUI.setProgress( 35.0 );
            }
            // Check if archive either doesn't exist or doesn't match hash
            if ( !jreArchiveFile.exists() || !Files.hash( jreArchiveFile, Hashing.sha256() ).toString().equalsIgnoreCase(
                    FileUtils.readFileToString( jreHashFile, Charset.defaultCharset() ).split( " " )[ 0 ] ) ) {
                if ( progressGUI != null ) {
                    progressGUI.setLowerLabelText( "Downloading Configured JRE" );
                    progressGUI.setProgress( JFXProgressBar.INDETERMINATE_PROGRESS );
                }
                // Download archive from URL
                FLSystemUtils.downloadFileFromURL( new URL( jreArchiveDownloadURL ), jreArchiveFile );

                // Delete previous extracted JRE
                File extractedJREFolder = new File( getJREFolderPath() + File.separator + MCFLConstants.JRE_EXTRACTED_FOLDER_NAME );
                if ( extractedJREFolder.exists() ) {
                    FileUtils.deleteDirectory( extractedJREFolder );
                }

                // Extract downloaded JRE
                if ( progressGUI != null ) {
                    progressGUI.setLowerLabelText( "Extracting Downloaded JRE" );
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
                FLLogUtil.error( "Unable to create local runtime. Using system Java.", 309, progressGUI.getCurrentJFXStage() );
            else FLLogUtil.error( "Unable to create local runtime. Using system Java.", 309, null );
            javaPath = "java";
            if ( progressGUI != null ) {
                Platform.setImplicitExit( false );
                new Thread( progressGUI::close ).start();
            }
            return;
        }
        if ( progressGUI != null ) {
            progressGUI.setLowerLabelText( "Finished JRE Preparation" );
            progressGUI.setProgress( 100.0 );
            Platform.setImplicitExit( false );
            new Thread( progressGUI::close ).start();
        }
    }

    public static void logoutCurrentUser() {
        // Check if current user is not null
        if ( currentUser != null ) {
            try {
                // Invalidate current user
                MCAuthService.invalidateLogin( getCurrentUser(), getClientToken() );

                // Delete current user information on disk (if exists)
                synchronized ( savedUserFile ) {
                    FileUtils.forceDelete( savedUserFile );
                }
            }
            catch ( FLAuthenticationException | IOException e ) {
                FLLogUtil.error( "Unable to invalidate cached token prior to logout. Local account information will still be destroyed.", 303, null );
            }
        }

        // Set login to display instead of application close
        loopLogin = true;
    }

    public static void doLogin() {
        // Login should only be handled in client mode
        if ( mode == MODE_CLIENT ) {
            // Check for active internet connection
            boolean offlineMode = false;
            if ( !FLNetworkUtils.isMojangAuthReachable() ) {
                CountDownLatch waitForDialog = new CountDownLatch( 1 );
                AtomicReference< Alert > alert = new AtomicReference<>();
                FLGUIUtils.JFXPlatformRun( () -> {
                    // Show alert and prompt user for offline mode
                    alert.set( new Alert( Alert.AlertType.ERROR ) );
                    alert.get().setTitle( "Offline Mode" );
                    alert.get().setHeaderText( "Can't Connect to Mojang!" );
                    alert.get().setContentText( "Check your internet connection and/or try again later." );
                    alert.get().showAndWait();
                    waitForDialog.countDown();
                } );
                try {
                    waitForDialog.await();
                    System.exit( 0 );
                }
                catch ( InterruptedException e ) {
                    e.printStackTrace();
                }
            }

            // Check for saved user on disk. Load and return if found
            boolean dirtyLogin = false;
            synchronized ( savedUserFile ) {
                if ( ALLOW_SAVED_USERS && savedUserFile.isFile() ) {
                    try {
                        currentUser = MCAuthAccount.readFromFile( MCFLConstants.LAUNCHER_CLIENT_SAVED_USER_FILE );

                        // Refresh auth only if not in offline mode
                        if ( !offlineMode ) {
                            MCAuthService.refreshAuth( getCurrentUser(), getClientToken() );
                            MCAuthAccount.writeToFile( savedUserFile.getPath(), getCurrentUser() );
                        }
                        return;
                    }
                    catch ( FLAuthenticationException e ) {
                        dirtyLogin = true;
                    }
                }
            }

            // Show login screen
            FLLoginGUI loginGUI = new FLLoginGUI();
            loginGUI.show();

            // Show error if exception encountered above (need to wait for GUI)
            if ( dirtyLogin ) {
                FLLogUtil.error( "Unable to load remembered user account.", 301, loginGUI.getCurrentJFXStage() );
            }

            // Wait for login screen to complete
            try {
                currentUser = loginGUI.waitForLoginInfo();
            }
            catch ( InterruptedException e ) {
                FLLogUtil.error( "Unable to wait for pending login task.", 300, null );
                closeApp();
            }

            // Close login screen once complete
            loginGUI.close();
        }
    }
    //endregion

    //region: Core Methods
    public static void main( String[] args ) {
        // Before the weird font glitches make people crazy, fix them
        System.setProperty( "prism.lcdtext", "false" );
        System.setProperty( "prism.text", "t2k" );
        int initPackIndex = 0;

        if ( args.length == 0 ) mode = inferMode();
        else if ( args.length == 1 && args[ 0 ].equals( "-c" ) ) mode = MODE_CLIENT;
        else if ( args.length == 1 && args[ 0 ].equals( "-s" ) ) mode = MODE_SERVER;
        else if ( args.length == 1 && args[ 0 ].equals( "-a" ) ) {
            // Show admin UI and stop normal processes
            loopLogin = false;

            MCFLAdminGUI adminGUI = new MCFLAdminGUI();
            adminGUI.open();
            try {
                adminGUI.closedLatch.await();
            }
            catch ( InterruptedException e ) {
                System.err.println( "Unable to wait completion of GUI." );
            }
        }
        else if ( args.length == 1 && args[ 0 ].matches( "^\\d+$" ) ) initPackIndex = Integer.parseInt( args[ 0 ] );
        else if ( args.length == 2 && args[ 0 ].equals( "-c" ) && args[ 1 ].matches( "^\\d+$" ) ) {
            mode = MODE_CLIENT;
            initPackIndex = Integer.parseInt( args[ 1 ] );
        }
        else if ( args.length == 2 && args[ 0 ].equals( "-s" ) && args[ 1 ].matches( "^\\d+$" ) ) {
            mode = MODE_SERVER;
            initPackIndex = Integer.parseInt( args[ 1 ] );
        }
        else {
            System.out.println( "ERROR: Your argument(s) are invalid.\nUsage: launcher.jar [ -s [modpack] | -c [modpack] | modpack | -a ]" );
            return;
        }

        // Configure logging to file in launcher directory
        Timestamp logTimeStamp = new Timestamp( System.currentTimeMillis() );
        SimpleDateFormat logFileNameTimeStampFormat = new SimpleDateFormat( "yyyy-MM-dd--HH-mm-ss" );
        String modeStr = mode == MODE_SERVER ? "SRV" : "CLIENT";
        File logFile = new File( getLogFolderPath() + File.separator + "Log_" + modeStr + "_" + logFileNameTimeStampFormat.format( logTimeStamp ) + ".log" );
        PrintStream toLog = null;
        try {
            if ( !logFile.isFile() ) {
                logFile.getParentFile().mkdirs();
                logFile.createNewFile();
            }
            toLog = new PrintStream( logFile );
            System.setOut( toLog );
            System.setErr( toLog );
            System.out.println( "Configured err and out to file" );
        }
        catch ( Exception e ) {
            System.err.println( "Unable to configure logging for launcher." );
        }


        // Run main functions of launcher (and loop if login re-required)
        while ( loopLogin ) {
            loopLogin = false;
            launcherConfig = null;
            doLogin();
            doLocalJDK();
            buildMemoryModpackList();
            doModpackSelection( initPackIndex );
        }

        // Close log output
        if ( toLog != null ) toLog.close();

        // Force call to exit
        System.exit( 0 );
    }
    //endregion
}
