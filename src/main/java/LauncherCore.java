import com.google.gson.Gson;
import com.micatechnologies.minecraft.forgemodpacklib.MCForgeModpack;
import com.micatechnologies.minecraft.forgemodpacklib.MCForgeModpackProgressProvider;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.nio.file.Paths;
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

    /**
     * Handle the running of the launcher for client enviornments.
     */
    public static void runClientLauncher() {
        LauncherConfig config = getConfig();
        System.err.println( "MIN: " + config.minRAM );
        System.err.println( "MAX: " + config.maxRAM );
        String url1 = config.modpacks.get( 0 );
        try {
            MCForgeModpack test = MCForgeModpack.downloadFromURL( new URL( url1 ), Paths
                                                                      .get( System.getProperty( "user.home" ) + "/Desktop/LAUNCHER-TEST" ),
                                                                  LauncherConstants.LAUNCHER_CLIENT_MODE );
            String javaPath = "java";
            if ( true ) {
                Path jvms = Paths.get( "/Library/Java/JavaVirtualMachines" );
                if ( Paths.get( jvms.toString() + "/" + "adoptopenjdk-8.jdk" ).toFile().exists() ) {
                    javaPath = jvms.toString() + "/" + "adoptopenjdk-8.jdk/Contents/Home/bin/java";
                }
            }
            test.setProgressProvider( new MCForgeModpackProgressProvider() {
                @Override
                public void updateProgressHandler( final double v, final String s ) {
                    System.out.println( ( ( int ) v ) + "% - " + s );
                }
            } );
            test.startGame( javaPath, "test", "test", "test", config.minRAM, config.maxRAM );
        }
        catch ( Exception e ) {
            e.printStackTrace();
        }
    }

    /**
     * Handle the running of the launcher for server environments.
     */
    public static void runServerLauncher() {

    }

    static void testMain( int mode ) {
        launcherMode = mode;
        if ( launcherMode == LauncherConstants.LAUNCHER_CLIENT_MODE ) {
            System.out.println( "Detected Minecraft Client Mode...now loading" );
            runClientLauncher();
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
            runServerLauncher();
        }
        else if ( launcherMode == LauncherConstants.LAUNCHER_SERVER_MODE ) {
            System.out.println( "Detected Minecraft Server Mode...now loading" );
            runClientLauncher();
        }
        else {
            System.err.println( "Unable to detect launcher mode. Terminating immediately." );
            System.exit( -1 );
        }
    }
}
