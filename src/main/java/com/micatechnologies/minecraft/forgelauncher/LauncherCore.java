package com.micatechnologies.minecraft.forgelauncher;

import com.google.gson.Gson;
import com.micatechnologies.minecraft.forgemodpacklib.MCForgeModpack;
import com.micatechnologies.minecraft.forgemodpacklib.MCForgeModpackProgressProvider;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Paths;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;
import javax.swing.JFrame;
import org.apache.commons.io.FileUtils;

public class LauncherCore {

    public static int launcherMode;

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
        return "java";
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
            // Create progress GUI
            LauncherProgressGUI progressGUI = new LauncherProgressGUI();
            Thread progGUI = new Thread( progressGUI );
            progGUI.setDaemon( true );
            progGUI.start();

            // Download current modpack manifest
            MCForgeModpack pack = MCForgeModpack.downloadFromURL( new URL( url1 ), Paths.get(
                LauncherConstants.LAUNCHER_CLIENT_INSTALL_PATH + "modpacks" + File.separator
                    + modpackIndex ), LauncherConstants.LAUNCHER_CLIENT_MODE );

            // Get java executable path
            String javaPath = getGameJavaPath();

            // TODO: Get pack name for top line message on GUI

            // Create progress handler
            pack.setProgressProvider( new MCForgeModpackProgressProvider() {
                @Override
                public void updateProgressHandler( final double v, final String s ) {
                    System.out.println( "[Launcher/client] " + ( ( int ) v ) + "% - " + s );
                    progressGUI.setLowerText( s );
                    progressGUI.setProgressBarProgress( v );
                }
            } );

            // Start the game
            while ( !progressGUI.isLoaded() ) {
                Thread.sleep( 100 );
            }
            progressGUI.setUpperText( "Launching [pack name]" );
            pack.startGame( javaPath, "test", "test", "test", config.minRAM, config.maxRAM );
        }
        catch ( Exception e ) {
            e.printStackTrace();
            System.exit( -1 );
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
            // Get java executable path
            String javaPath = getGameJavaPath();

            // Create progress handler
            pack.setProgressProvider( new MCForgeModpackProgressProvider() {
                @Override
                public void updateProgressHandler( final double v, final String s ) {
                    System.out.println( "[Launcher/server] " + ( ( int ) v ) + "% - " + s );
                }
            } );

            // Start the game
            pack.startGame( javaPath, "", "", "", config.minRAM, config.maxRAM );
        }
        catch ( Exception e ) {
            e.printStackTrace();
        }
    }

    static void testMain( int mode ) {
        launcherMode = mode;
        if ( launcherMode == LauncherConstants.LAUNCHER_CLIENT_MODE ) {
            System.out.println( "Detected Minecraft Client Mode...now loading" );
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

    /**
     * Main execution method of launcher.
     *
     * @param args arguments
     */
    public static void main( String[] args ) {
        // Try to automatically detect if launcher is on a server
        launcherMode = inferLauncherMode();
        if ( launcherMode == LauncherConstants.LAUNCHER_CLIENT_MODE ) {
            System.out.println( "Detected Minecraft Client Mode...now loading" );
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
