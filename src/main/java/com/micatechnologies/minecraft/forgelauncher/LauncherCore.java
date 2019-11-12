package com.micatechnologies.minecraft.forgelauncher;

import com.google.common.hash.Hashing;
import com.google.common.io.Files;
import com.google.gson.Gson;
import com.micatechnologies.minecraft.authlib.MCAuthAccount;
import com.micatechnologies.minecraft.authlib.MCAuthException;
import com.micatechnologies.minecraft.authlib.MCAuthService;
import com.micatechnologies.minecraft.forgemodpacklib.MCForgeModpack;
import com.micatechnologies.minecraft.forgemodpacklib.MCForgeModpackProgressProvider;
import com.micatechnologies.minecraft.forgemodpacklib.MCModpackOSUtils;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Paths;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import javafx.application.Platform;
import javafx.scene.control.ProgressIndicator;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javax.swing.JFrame;
import org.apache.commons.io.FileUtils;
import org.rauschig.jarchivelib.ArchiveFormat;
import org.rauschig.jarchivelib.Archiver;
import org.rauschig.jarchivelib.ArchiverFactory;
import org.rauschig.jarchivelib.CompressionType;

public class LauncherCore {

    public static  int    launcherMode;

    private static String gameJavePath = "java";

    public static int inferLauncherMode() {
        try {
            JFrame test = new JFrame( "TESTING" );
            test.setVisible( true );
            test.setVisible( false );
            return LauncherConstants.LAUNCHER_CLIENT_MODE;
        }
        catch ( Exception ex ) {
            return LauncherConstants.LAUNCHER_SERVER_MODE;
        }
    }

    public static LauncherConfig getConfig() {
        // Get config path and file
        String configFilePath = LauncherConstants.LAUNCHER_CONFIG_NAME;
        if ( launcherMode == LauncherConstants.LAUNCHER_CLIENT_MODE ) {
            configFilePath = LauncherConstants.LAUNCHER_CLIENT_INSTALL_PATH + configFilePath;
        }
        File configFileFile = new File( configFilePath );

        // Read config file to JSON object
        try {
            return new Gson().fromJson( new FileReader( configFileFile ), LauncherConfig.class );
        }
        catch ( FileNotFoundException e ) {
            try {
                configFileFile.getParentFile().mkdirs();
                configFileFile.createNewFile();
                FileUtils.writeStringToFile( configFileFile,
                                             LauncherConstants.LAUNCHER_CONFIG_DEFAULT_FILE,
                                             Charset.defaultCharset() );
                System.err.println(
                    "Launcher config file was missing. Created new at " + configFileFile
                        .getAbsolutePath() + ". Please edit default config." );
            }
            catch ( IOException ex ) {
                System.err.println(
                    "Unable to create missing launcher config file at " + configFileFile
                        .getAbsolutePath() );
                ex.printStackTrace();
            }
        }

        // Terminate program if config file was missing.
        System.exit( -1 );
        return null;
    }

    public static String getGameJavaPath() {
        return gameJavePath;
    }

    /**
     * Handle the running of the launcher for client enviornments.
     */
    public static void runClientLauncher( int modpackIndex ) {
        // Get launcher configuration and print RAM
        LauncherConfig config = getConfig();
        System.out.println( "Minimum RAM: " + config.minRAM );
        System.out.println( "Maximum RAM: " + config.maxRAM );

        // Get selected modpack
        String url1 = config.modpacks.get( modpackIndex );

        try {
            // Download current modpack manifest
            MCForgeModpack pack = MCForgeModpack.downloadFromURL( new URL( url1 ), Paths.get(
                LauncherConstants.LAUNCHER_CLIENT_INSTALL_PATH + "modpacks" + File.separator
                    + modpackIndex ), LauncherConstants.LAUNCHER_CLIENT_MODE );

            // Make GUI
            LauncherProgressGUI launcherProgressGUI = new LauncherProgressGUI();
            try {
                Platform.startup( () -> {
                    try {
                        launcherProgressGUI.start( new Stage() );
                    }
                    catch ( Exception e ) {
                        System.err.println(
                            "Unable to create application GUI for client launcher." );
                        e.printStackTrace();
                        System.exit( -1 );
                    }
                } );
            }
            catch ( IllegalStateException e ) {
                Platform.runLater( () -> {
                    try {
                        launcherProgressGUI.start( new Stage() );
                    }
                    catch ( Exception ee ) {
                        System.err.println(
                            "Unable to create application GUI for client launcher." );
                        ee.printStackTrace();
                        System.exit( -1 );
                    }
                } );
            }
            launcherProgressGUI.readyLatch.await();

            // Create progress handler
            pack.setProgressProvider( new MCForgeModpackProgressProvider() {
                @Override
                public void updateProgressHandler( final double v, final String s ) {
                    System.out.println( "[Launcher/client] " + ( ( int ) v ) + "% - " + s );
                    // Show filename not full path (if applicable)
                    if ( s.lastIndexOf( File.separator ) >= 0 ) {
                        Platform.runLater( () -> launcherProgressGUI.lowerText
                            .setText( s.substring( s.lastIndexOf( File.separator ) ) ) );
                    }
                    else {
                        Platform.runLater( () -> launcherProgressGUI.lowerText.setText( s ) );
                    }
                    Platform.runLater( () -> launcherProgressGUI.progressBar.setProgress( v ) );

                    // Hide window if at 100%
                    if ( v == 100.0 ) {
                        Platform.runLater( () -> {
                            launcherProgressGUI.upperText.setText( "Complete!" );
                            launcherProgressGUI.lowerText.setText( "Passing off to Minecraft..." );
                        } );
                        Platform.runLater( () -> {
                            try {
                                Thread.sleep( 2500 );
                            }
                            catch ( InterruptedException e ) {
                                e.printStackTrace();
                            }
                            launcherProgressGUI.getCurrStage().close();
                        } );
                    }
                }
            } );

            // Download/update JRE/JDK
            Platform.runLater( () -> {
                launcherProgressGUI.upperText.setText( "Downloading Java Runtime" );
                launcherProgressGUI.lowerText.setText( "This might take a while!" );
                launcherProgressGUI.progressBar.setProgress(
                    ProgressIndicator.INDETERMINATE_PROGRESS );
            } );
            downloadPlatformJDK();

            // Get java executable path
            String javaPath = getGameJavaPath();

            // Start the game
            Platform.runLater(
                () -> launcherProgressGUI.upperText.setText( "Launching " + pack.getPackName() ) );
            pack.startGame( javaPath, "test", "test", "test", config.minRAM, config.maxRAM );

            // TODO: Show main launcher screen after game closes
        }
        catch ( Exception e ) {
            e.printStackTrace();
            System.exit( -1 );
        }
    }

    static void downloadPlatformJDK() {
        try {
            // Build paths and File objects
            String fullJDKFolderPath = LauncherConstants.LAUNCHER_CLIENT_INSTALL_PATH
                + LauncherConstants.LAUNCHER_JDK_PATH;
            File fullJDKArchiveFile = new File(
                fullJDKFolderPath + File.separator + "jdk.archive" );
            File fullJDKHashFile = new File(
                fullJDKFolderPath + File.separator + "jdk.archive.hash" );
            File fullJDKFolderFile = new File( fullJDKFolderPath );

            // Verify JRE/JDK folder exists
            fullJDKFolderFile.mkdirs();
            fullJDKFolderFile.setReadable( true );
            fullJDKFolderFile.setWritable( true );

            // Handle per OS
            if ( MCModpackOSUtils.isWindows() ) {
                // Get Windows JDK Hash
                FileUtils.copyURLToFile( new URL( LauncherConstants.LAUNCHER_JDK_WIN_URL
                                                      + LauncherConstants.LAUNCHER_JDK_HASH_POSTFIX ),
                                         fullJDKHashFile );

                // Check if existing archive matches
                if ( !fullJDKArchiveFile.exists() || !Files.hash( fullJDKArchiveFile,
                                                                  Hashing.sha256() ).toString()
                                                           .equals( FileUtils.readFileToString(
                                                               fullJDKHashFile,
                                                               Charset.defaultCharset() ) ) ) {
                    // Not valid...download archive
                    FileUtils.copyURLToFile( new URL( LauncherConstants.LAUNCHER_JDK_WIN_URL ),
                                             fullJDKArchiveFile );

                    // Delete old extracted if exists
                    File extractedFolder = new File( fullJDKFolderPath + File.separator
                                                         + LauncherConstants.LAUNCHER_JDK_LOCAL_FOLDER_NAME );
                    if ( extractedFolder.exists() ) {
                        FileUtils.deleteDirectory( extractedFolder );
                    }

                    // Extract archive
                    Archiver archiver = ArchiverFactory.createArchiver( ArchiveFormat.ZIP );
                    archiver.extract( fullJDKArchiveFile, fullJDKFolderFile );
                }

                // Set java path
                gameJavePath = fullJDKFolderPath + File.separator
                    + LauncherConstants.LAUNCHER_JDK_LOCAL_FOLDER_NAME + File.separator
                    + LauncherConstants.LAUNCHER_JDK_WIN_LOCAL_JAVA_PATH;
            }
            else if ( MCModpackOSUtils.isUnix() ) {
                // Get Linux/Unix JDK Hash
                FileUtils.copyURLToFile( new URL( LauncherConstants.LAUNCHER_JDK_LINUX_URL
                                                      + LauncherConstants.LAUNCHER_JDK_HASH_POSTFIX ),
                                         fullJDKHashFile );

                // Check if existing archive matches
                if ( !fullJDKArchiveFile.exists() || !Files.hash( fullJDKArchiveFile,
                                                                  Hashing.sha256() ).toString()
                                                           .equals( FileUtils.readFileToString(
                                                               fullJDKHashFile,
                                                               Charset.defaultCharset() ) ) ) {
                    // Not valid...download archive
                    FileUtils.copyURLToFile( new URL( LauncherConstants.LAUNCHER_JDK_LINUX_URL ),
                                             fullJDKArchiveFile );

                    // Delete old extracted if exists
                    File extractedFolder = new File( fullJDKFolderPath + File.separator
                                                         + LauncherConstants.LAUNCHER_JDK_LOCAL_FOLDER_NAME );
                    if ( extractedFolder.exists() ) {
                        FileUtils.deleteDirectory( extractedFolder );
                    }

                    // Extract archive
                    Archiver archiver = ArchiverFactory.createArchiver( ArchiveFormat.TAR,
                                                                        CompressionType.GZIP );
                    archiver.extract( fullJDKArchiveFile, fullJDKFolderFile );
                }

                // Set java path
                gameJavePath = fullJDKFolderPath + File.separator
                    + LauncherConstants.LAUNCHER_JDK_LOCAL_FOLDER_NAME + File.separator
                    + LauncherConstants.LAUNCHER_JDK_LINUX_LOCAL_JAVA_PATH;
            }
            else if ( MCModpackOSUtils.isMac() ) {
                // Get macOS JDK Hash
                FileUtils.copyURLToFile( new URL( LauncherConstants.LAUNCHER_JDK_MAC_URL
                                                      + LauncherConstants.LAUNCHER_JDK_HASH_POSTFIX ),
                                         fullJDKHashFile );

                // Check if existing archive matches
                if ( !fullJDKArchiveFile.exists() || !Files.hash( fullJDKArchiveFile,
                                                                  Hashing.sha256() ).toString()
                                                           .equals( FileUtils.readFileToString(
                                                               fullJDKHashFile,
                                                               Charset.defaultCharset() ) ) ) {
                    // Not valid...download archive
                    FileUtils.copyURLToFile( new URL( LauncherConstants.LAUNCHER_JDK_MAC_URL ),
                                             fullJDKArchiveFile );

                    // Delete old extracted if exists
                    File extractedFolder = new File( fullJDKFolderPath + File.separator
                                                         + LauncherConstants.LAUNCHER_JDK_LOCAL_FOLDER_NAME );
                    if ( extractedFolder.exists() ) {
                        FileUtils.deleteDirectory( extractedFolder );
                    }

                    // Extract archive
                    Archiver archiver = ArchiverFactory.createArchiver( ArchiveFormat.TAR,
                                                                        CompressionType.GZIP );
                    archiver.extract( fullJDKArchiveFile, fullJDKFolderFile );
                }

                // Set java path
                gameJavePath = fullJDKFolderPath + File.separator
                    + LauncherConstants.LAUNCHER_JDK_LOCAL_FOLDER_NAME + File.separator
                    + LauncherConstants.LAUNCHER_JDK_MAC_LOCAL_JAVA_PATH;
            }
            else {
                System.err.println(
                    "Unable to detect platform as Windows, Unix or macOS. Using system JDK." );
            }
        }
        catch ( IOException e ) {
            e.printStackTrace();
        }
    }

    /**
     * Handle the running of the launcher for server environments.
     */
    public static void runServerLauncher() {
        // Get launcher configuration and print RAM
        LauncherConfig config = getConfig();
        System.out.println( "Minimum RAM: " + config.minRAM );
        System.out.println( "Maximum RAM: " + config.maxRAM );

        // Get first configured modpack
        String url1 = config.modpacks.get( 0 );

        try {
            // Download current modpack manifest
            MCForgeModpack pack = MCForgeModpack.downloadFromURL( new URL( url1 ),
                                                                  Paths.get( "" ).toAbsolutePath(),
                                                                  LauncherConstants.LAUNCHER_SERVER_MODE );

            // Create progress handler
            pack.setProgressProvider( new MCForgeModpackProgressProvider() {
                @Override
                public void updateProgressHandler( final double v, final String s ) {
                    System.out.println( "[Launcher/server] " + ( ( int ) v ) + "% - " + s );
                }
            } );

            // Download/update JRE/JDK
            System.out.println( "[Launcher/server] Downloading Java Runtime..." );
            downloadPlatformJDK();
            System.out.println( "[Launcher/server] Downloading Java Runtime...DONE" );

            // Get java executable path
            String javaPath = getGameJavaPath();

            // Start the game
            pack.startGame( javaPath, "", "", "", config.minRAM, config.maxRAM );
        }
        catch ( Exception e ) {
            e.printStackTrace();
        }
    }

    static void testMain( int mode ) throws IOException, InterruptedException {
        launcherMode = mode;
        if ( launcherMode == LauncherConstants.LAUNCHER_CLIENT_MODE ) {
            System.out.println( "TEST -- Detected Minecraft Client Mode...now loading" );
            doReadClientToken();
            doLogin();
            runClientLauncher( 0 );
        }
        else if ( launcherMode == LauncherConstants.LAUNCHER_SERVER_MODE ) {
            System.out.println( "TEST -- Detected Minecraft Server Mode...now loading" );
            runServerLauncher();
        }
        else {
            System.err.println(
                "TEST -- Unable to detect launcher mode. Terminating immediately." );
            System.exit( -1 );
        }
    }

    static void doLogin() throws InterruptedException {
        // TODO: Check for saved login
        File savedUserFile = new File( LauncherConstants.LAUNCHER_SAVED_USER_FILE_PATH );

        // If saved user file exists, try to read from file
        if ( savedUserFile.exists() ) {
            try {
                currentUser = MCAuthAccount.readFromFile(
                    LauncherConstants.LAUNCHER_SAVED_USER_FILE_PATH );
                return;
            }
            catch ( MCAuthException ignored ) {
                // Ignore exception, just show login screen again.
            }
        }

        // Create Login GUI
        LauncherLoginGUI launcherLoginGUI = new LauncherLoginGUI();
        try {
            Platform.startup( () -> {
                try {
                    launcherLoginGUI.start( new Stage() );
                }
                catch ( Exception e ) {
                    System.err.println( "Unable to create application GUI for client launcher." );
                    e.printStackTrace();
                    System.exit( -1 );
                }
            } );
        }
        catch ( IllegalStateException e ) {
            Platform.runLater( () -> {
                try {
                    launcherLoginGUI.start( new Stage() );
                }
                catch ( Exception ee ) {
                    System.err.println( "Unable to create application GUI for client launcher." );
                    ee.printStackTrace();
                    System.exit( -1 );
                }
            } );
        }
        launcherLoginGUI.readyLatch.await();

        CountDownLatch latch = new CountDownLatch( 1 );

        // Setup login button listener
        launcherLoginGUI.loginButton.setOnAction( actionEvent -> {
            // Get username and password
            String username = launcherLoginGUI.emailField.getText();
            String password = launcherLoginGUI.passwordField.getText();

            // Create AuthAccount
            MCAuthAccount account = new MCAuthAccount( username );

            // Attempt authentication
            try {
                MCAuthService.usernamePasswordAuth( account, password, clientToken );
                currentUser = account;
                if ( launcherLoginGUI.rememberCheckBox.isSelected() ) {
                    MCAuthAccount.writeToFile( LauncherConstants.LAUNCHER_SAVED_USER_FILE_PATH,
                                               currentUser );
                }
                latch.countDown();
            }
            catch ( MCAuthException e ) {
                launcherLoginGUI.passwordField.clear();
                launcherLoginGUI.loginButton.setText( "Try Again" );
            }
        } );

        latch.await();
    }

    public static String        clientToken = "";

    public static MCAuthAccount currentUser = null;

    public static void doReadClientToken() throws IOException {
        File clientTokenFile = new File(
            LauncherConstants.LAUNCHER_CLIENT_INSTALL_PATH + LauncherConstants.LAUNCHER_UUID_PATH );
        if ( clientTokenFile.exists() ) {
            clientToken = FileUtils.readFileToString( clientTokenFile, Charset.defaultCharset() );
        }
        else {
            clientToken = UUID.randomUUID().toString();
            FileUtils.writeStringToFile( clientTokenFile, clientToken, Charset.defaultCharset() );
        }
    }

    /**
     * Main execution method of launcher.
     *
     * @param args arguments
     */
    public static void main( String[] args ) throws IOException, InterruptedException {
        // Try to automatically detect if launcher is on a server
        launcherMode = inferLauncherMode();
        if ( launcherMode == LauncherConstants.LAUNCHER_CLIENT_MODE ) {
            System.out.println( "Detected Minecraft Client Mode...now loading" );
            doReadClientToken();
            doLogin();
            runClientLauncher( 0 );
        }
        else if ( launcherMode == LauncherConstants.LAUNCHER_SERVER_MODE ) {
            System.out.println( "Detected Minecraft Server Mode...now loading" );
            runServerLauncher();
        }
        else {
            System.err.println( "Unable to detect launcher mode. Terminating immediately." );
            System.exit( -1 );
        }
    }
}
