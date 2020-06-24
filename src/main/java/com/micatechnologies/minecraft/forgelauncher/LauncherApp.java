package com.micatechnologies.minecraft.forgelauncher;

import com.google.common.hash.Hashing;
import com.google.common.io.Files;
import com.jfoenix.controls.JFXProgressBar;
import com.micatechnologies.minecraft.forgelauncher.auth.AuthAccount;
import com.micatechnologies.minecraft.forgelauncher.config.ConfigurationManager;
import com.micatechnologies.minecraft.forgelauncher.exceptions.GameAccountException;
import com.micatechnologies.minecraft.forgelauncher.auth.AuthService;
import com.micatechnologies.minecraft.forgelauncher.exceptions.FLModpackException;
import com.micatechnologies.minecraft.forgelauncher.utilities.objects.GameMode;
import com.micatechnologies.minecraft.forgelauncher.gui.*;
import com.micatechnologies.minecraft.forgelauncher.modpack.ModPack;
import com.micatechnologies.minecraft.forgelauncher.modpack.MCForgeModpackProgressProvider;
import com.micatechnologies.minecraft.forgelauncher.modpack.ModPackInstallManager;
import com.micatechnologies.minecraft.forgelauncher.utilities.GuiUtils;
import com.micatechnologies.minecraft.forgelauncher.utilities.LogUtils;
import com.micatechnologies.minecraft.forgelauncher.utilities.NetworkUtils;
import com.micatechnologies.minecraft.forgelauncher.utilities.SystemUtils;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import org.apache.commons.io.FileUtils;
import org.rauschig.jarchivelib.ArchiveFormat;
import org.rauschig.jarchivelib.Archiver;
import org.rauschig.jarchivelib.ArchiverFactory;
import org.rauschig.jarchivelib.CompressionType;

import java.awt.*;
import java.io.*;
import java.net.URL;
import java.nio.charset.Charset;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

public class LauncherApp {
    //region: App Configuration & Information
    private static final boolean ALLOW_SAVED_USERS = true;
    private static boolean loopLogin = true;
    private static GameMode gameMode = GameMode.CLIENT;
    private static String javaPath = "java";
    private static String clientToken = "";
    private static AuthAccount currentUser = null;
    private static ConfigurationManager launcherConfig = null;
    private static final File savedUserFile = new File(LauncherConstants.LAUNCHER_CLIENT_SAVED_USER_FILE);
    //endregion

    //region: Get/Set Methods
    public static GameMode getMode() {
        return gameMode;
    }

    public static String getJavaPath() {
        return javaPath;
    }

    public static boolean getLoopLogin() {
        return loopLogin;
    }

    public static String getClientToken() {
        // Client token not loaded to memory, need to load first
        if (clientToken.equals("")) {
            // Create file object for local token file
            File tokenFile = new File(LauncherConstants.LAUNCHER_CLIENT_TOKEN_FILE);

            // Check if file exists.
            if (tokenFile.isFile()) {
                try {
                    // If file exists, read client token from file
                    clientToken = FileUtils.readFileToString(tokenFile, Charset.defaultCharset());
                } catch (IOException e) {
                    // If an error occurs during read, generate new client token.
                    clientToken = UUID.randomUUID().toString();
                    try {
                        // Attempt to write new client token to file.
                        FileUtils.writeStringToFile(tokenFile, clientToken, Charset.defaultCharset());
                    } catch (IOException ee) {
                        // Output error if attempt to write new client token fails
                        LogUtils.logError( "The client token could not be written to persistent storage. Remember me login functionality will not work.");
                    }
                }

            } else {
                // If an error occurs during read, generate new client token.
                clientToken = UUID.randomUUID().toString();
                try {
                    // Attempt to write new client token to file.
                    FileUtils.writeStringToFile(tokenFile, clientToken, Charset.defaultCharset());
                } catch (IOException ee) {
                    // Output error if attempt to write new client token fails
                    LogUtils.logError( "The client token could not be written to persistent storage. Remember me login functionality will not work.");
                }
            }
        }
        return clientToken;
    }

    public static AuthAccount getCurrentUser() {
        return currentUser;
    }

    public static ConfigurationManager getLauncherConfig() {
        if (launcherConfig == null) {
            try {
                launcherConfig = ConfigurationManager.open();
            } catch (IOException e) {
                LogUtils.logError( "Unable to load launcher configuration from persistent storage. Configuration may be reset.");
            }
        }

        return launcherConfig;
    }

    //endregion

    //region: Helper Methods
    public static void saveConfig() {
        try {
            getLauncherConfig().save();
        } catch (IOException e) {
            LogUtils.logError( "Unable to save launcher configuration to disk. Configuration may be lost!");
        }
    }

    public static String getInstallPath() {
        return gameMode == GameMode.CLIENT ? LauncherConstants.LAUNCHER_CLIENT_INSTALLATION_DIRECTORY : LauncherConstants.LAUNCHER_SERVER_INSTALLATION_DIRECTORY;
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
        LogUtils.logError( "An illegal launcher mode is in use. This should not happen!");
    }

    public static void buildMemoryModpackList() {
        // Create progress GUI if running in client mode
        ProgressWindow progressWindow = null;
        if (gameMode == GameMode.CLIENT) progressWindow = new ProgressWindow();

        // Get installed mod packs
        if ( progressWindow != null) {
            progressWindow.setUpperLabelText( "Loading Mod Packs");
            progressWindow.setLowerLabelText( "Downloading latest manifests");
            progressWindow.setProgress( JFXProgressBar.INDETERMINATE_PROGRESS);
            progressWindow.show();
        }
        List<ModPack> installedModPacks = ModPackInstallManager.getInstalledModPacks();

        // Prepare environment for each mod pack
        final double progressBase = 100.0;
        final double progressStart = 20.0;
        final double progressFinish = 100.0;
        final double progressIncrementSize = (progressFinish - progressStart) / installedModPacks.size();
        double progressNow = progressStart;
        for (ModPack modPack : installedModPacks) {
            if ( progressWindow != null) {
                progressWindow.setLowerLabelText( "Preparing " + modPack.getFriendlyName());
                progressWindow.setProgress( progressNow / progressBase);
                progressNow += progressIncrementSize;
            }
        }

        // Close progress GUI if open
        if ( progressWindow != null) {
            progressWindow.close();
        }
    }

    public static GameMode inferMode() {
        if (!GraphicsEnvironment.isHeadless()) {
            LogUtils.logStd( "Inferred client mode");
            return GameMode.CLIENT;
        } else {
            LogUtils.logStd( "Inferred server mode");
            return GameMode.SERVER;
        }
    }
    //endregion

    //region: Function Methods
    public static void play(String modpackName, AbstractWindow gui) {
        if (gameMode == GameMode.CLIENT) playClient(modpackName, gui);
        else if (gameMode == GameMode.SERVER) playServer(modpackName);
    }

    private static void playClient(String modpackName, AbstractWindow gui) {
        // Verify user logged in and mode is client
        if (currentUser == null) return;
        if (gameMode != GameMode.CLIENT) return;

        // Launch selected modpack
        int minRAMMB = (int) (getLauncherConfig().getMinRAM() * 1024);
        int maxRAMMB = (int) (getLauncherConfig().getMaxRAM() * 1024);
        ProgressWindow progressWindow = null;
        try {
            ModPack clientModPack = null;
            for (ModPack modPack : ModPackInstallManager.getInstalledModPacks()) {
                if (modPack.getPackName().equalsIgnoreCase(modpackName) || modPack.getFriendlyName().equalsIgnoreCase(modpackName)) {
                    clientModPack = modPack;
                    break;
                }
            }

            if (clientModPack != null) {
                progressWindow = new ProgressWindow();
                progressWindow.show();

                // Verify configured RAM
                if (Integer.parseInt(clientModPack.getPackMinRAMGB()) > getLauncherConfig().getMaxRAM()) {
                    LogUtils.logError( "Modpack requires a minimum of " + clientModPack.getPackMinRAMGB() + "GB of RAM. Please change your RAM settings in the settings menu.");
                    progressWindow.close();
                    return;
                }


                ProgressWindow finalProgressWindow = progressWindow;
                ModPack finalClientModPack = clientModPack;
                clientModPack.setProgressProvider(new MCForgeModpackProgressProvider() {
                    @Override
                    public void updateProgressHandler(double percent, String text) {
                        finalProgressWindow.setUpperLabelText( "Loading " + finalClientModPack.getPackName());
                        finalProgressWindow.setLowerLabelText( text);
                        finalProgressWindow.setProgress( percent);
                        if (percent == 100.0) {
                            new Thread(() -> {
                                finalProgressWindow.setLowerLabelText( "Passing to Minecraft");
                                try {
                                    Thread.sleep(3000);
                                } catch (InterruptedException ignored) {
                                }
                                finalProgressWindow.close();
                            }).start();
                        }
                    }
                });
                clientModPack.startGame(getJavaPath(), currentUser.getFriendlyName(), currentUser.getUserIdentifier(), currentUser.getLastAccessToken(), minRAMMB, maxRAMMB);
            } else {
                LogUtils.logError( "Unable to find mod pack: " + modpackName);
            }
        } catch (FLModpackException e) {
            e.printStackTrace();
            LogUtils.logError( "Unable to start game.");
            if ( progressWindow != null) progressWindow.close();
        }
    }

    private static void playServer(String modpackName) {
        if (gameMode != GameMode.SERVER) return;

        int minRAMMB = (int) (getLauncherConfig().getMinRAM() * 1024);
        int maxRAMMB = (int) (getLauncherConfig().getMaxRAM() * 1024);
        try {
            ModPack serverModPack = null;
            for (ModPack modPack : ModPackInstallManager.getInstalledModPacks()) {
                if (modPack.getPackName().equalsIgnoreCase(modpackName) || modPack.getFriendlyName().equalsIgnoreCase(modpackName)) {
                    serverModPack = modPack;
                    break;
                }
            }

            if (serverModPack != null) {
                serverModPack.setProgressProvider(new MCForgeModpackProgressProvider() {
                    @Override
                    public void updateProgressHandler(double percent, String text) {
                        LogUtils.logStd( "Play: " + percent + "% - " + text);
                    }
                });
                serverModPack.startGame(getJavaPath(), "", "", "", minRAMMB, maxRAMMB);
            } else {
                LogUtils.logError( "Unable to find mod pack: " + modpackName);
            }
        } catch (FLModpackException e) {
            LogUtils.logError( "Unable to start mod pack: " + modpackName);
        }
    }

    private static void doModpackSelection(String modpackName) {
        String actualModPackName = modpackName;
        if (modpackName.equals("")) actualModPackName = ModPackInstallManager.getInstalledModPackFriendlyNames().get(0);

        if (gameMode == GameMode.CLIENT) {
            MainWindow mainWindow = new MainWindow();
            mainWindow.show( actualModPackName);
            try {
                mainWindow.closedLatch.await();
            } catch (InterruptedException ignored) {
                LogUtils.logError( "An error is preventing GUI completion handling. The login screen may not appear after logout.");
            }
        } else if (gameMode == GameMode.SERVER) {
            playServer(actualModPackName);
        } else {
            errorIllegalLauncherMode();
        }
    }

    public static void clearLocalJDK() throws IOException {
        FileUtils.deleteDirectory(new File(getJREFolderPath()));
    }

    public static void closeApp() {
        GUIController.closeAllWindows();
        System.exit(0);
    }

    public static void doLocalJDK() {
        // Create a progress GUI if in client mod
        ProgressWindow progressWindow = null;
        if (gameMode == GameMode.CLIENT) {
            progressWindow = new ProgressWindow();
            progressWindow.show();
            progressWindow.setUpperLabelText( "Preparing Minecraft Runtime");
            progressWindow.setLowerLabelText( "Preparing JRE Folder");
            progressWindow.setProgress( 0.0);
        }

        // Store JRE path and create file objects
        String jreFolderPath = getJREFolderPath();
        File jreArchiveFile = new File(jreFolderPath + File.separator + "rt.archive");
        File jreHashFile = new File(jreFolderPath + File.separator + "rt.hash");
        File jreFolderFile = new File(jreFolderPath);

        // Verify JRE folder exists
        if ( progressWindow != null) {
            progressWindow.setLowerLabelText( "Verifying JRE Folder");
            progressWindow.setProgress( 10.0);
        }
        jreFolderFile.mkdirs();
        jreFolderFile.setReadable(true);
        jreFolderFile.setWritable(true);

        // Get proper URL and archive format
        if ( progressWindow != null) {
            progressWindow.setLowerLabelText( "Preparing JRE Information");
            progressWindow.setProgress( 20.0);
        }
        String jreArchiveDownloadURL;
        String jreHashDownloadURL;
        ArchiveFormat jreArchiveFormat;
        CompressionType jreArchiveCompressionType;
        if (org.apache.commons.lang3.SystemUtils.IS_OS_WINDOWS) {
            jreArchiveFormat = ArchiveFormat.ZIP;
            jreArchiveCompressionType = null;
            jreArchiveDownloadURL = LauncherConstants.URL_JRE_WIN;
            jreHashDownloadURL = LauncherConstants.URL_JRE_WIN_HASH;
            javaPath = getJREFolderPath() + File.separator + LauncherConstants.JRE_EXTRACTED_FOLDER_NAME + File.separator + "bin" + File.separator + "java.exe";
        } else if (org.apache.commons.lang3.SystemUtils.IS_OS_MAC) {
            jreArchiveFormat = ArchiveFormat.TAR;
            jreArchiveCompressionType = CompressionType.GZIP;
            jreArchiveDownloadURL = LauncherConstants.URL_JRE_MAC;
            jreHashDownloadURL = LauncherConstants.URL_JRE_MAC_HASH;
            javaPath = getJREFolderPath() + File.separator + LauncherConstants.JRE_EXTRACTED_FOLDER_NAME + File.separator + "Contents" + File.separator + "Home" + File.separator + "bin" + File.separator + "java";
        } else if (org.apache.commons.lang3.SystemUtils.IS_OS_LINUX) {
            jreArchiveFormat = ArchiveFormat.TAR;
            jreArchiveCompressionType = CompressionType.GZIP;
            jreArchiveDownloadURL = LauncherConstants.URL_JRE_UNX;
            jreHashDownloadURL = LauncherConstants.URL_JRE_UNX_HASH;
            javaPath = getJREFolderPath() + File.separator + LauncherConstants.JRE_EXTRACTED_FOLDER_NAME + File.separator + "bin" + File.separator + "java";
        } else {

            LogUtils.logError( "Unable to identify operating system. Launcher will not cache JRE for gameplay.");
            if ( progressWindow != null) {
                Platform.setImplicitExit(false);
                new Thread( progressWindow::close).start();
            }
            return;
        }

        // Get hash of JRE from URL
        if ( progressWindow != null) {
            progressWindow.setLowerLabelText( "Downloading JRE Hash");
            progressWindow.setProgress( 25.0);
        }
        try {
            SystemUtils.downloadFileFromURL(new URL(jreHashDownloadURL), jreHashFile);
        } catch (IOException e) {

            LogUtils.logError( "Unable to create a file necessary for maintaining launcher integrity. Using system Java for safety.");
            javaPath = "java";
            if ( progressWindow != null) {
                Platform.setImplicitExit(false);
                new Thread( progressWindow::close).start();
            }
            return;
        }

        try {
            if ( progressWindow != null) {
                progressWindow.setLowerLabelText( "Verifying Local JRE");
                progressWindow.setProgress( 35.0);
            }
            // Check if archive either doesn't exist or doesn't match hash
            if (!jreArchiveFile.exists() || !Files.hash(jreArchiveFile, Hashing.sha256()).toString().equalsIgnoreCase(
                    FileUtils.readFileToString(jreHashFile, Charset.defaultCharset()).split(" ")[0])) {
                if ( progressWindow != null) {
                    progressWindow.setLowerLabelText( "Downloading Configured JRE");
                    progressWindow.setProgress( JFXProgressBar.INDETERMINATE_PROGRESS);
                }
                // Download archive from URL
                SystemUtils.downloadFileFromURL(new URL(jreArchiveDownloadURL), jreArchiveFile);

                // Delete previous extracted JRE
                File extractedJREFolder = new File(getJREFolderPath() + File.separator + LauncherConstants.JRE_EXTRACTED_FOLDER_NAME);
                if (extractedJREFolder.exists()) {
                    FileUtils.deleteDirectory(extractedJREFolder);
                }

                // Extract downloaded JRE
                if ( progressWindow != null) {
                    progressWindow.setLowerLabelText( "Extracting Downloaded JRE");
                    progressWindow.setProgress( 70.0);
                }
                Archiver archiver;
                if (jreArchiveCompressionType != null) {
                    archiver = ArchiverFactory.createArchiver(jreArchiveFormat, jreArchiveCompressionType);
                } else {
                    archiver = ArchiverFactory.createArchiver(jreArchiveFormat);
                }
                archiver.extract(jreArchiveFile, jreFolderFile);
            }
        } catch (IOException e) {

            LogUtils.logError( "Unable to create local runtime. Using system Java.");
            javaPath = "java";
            if ( progressWindow != null) {
                Platform.setImplicitExit(false);
                new Thread( progressWindow::close).start();
            }
            return;
        }
        if ( progressWindow != null) {
            progressWindow.setLowerLabelText( "Finished JRE Preparation");
            progressWindow.setProgress( 100.0);
            Platform.setImplicitExit(false);
            new Thread( progressWindow::close).start();
        }
    }

    public static boolean getDebugLogEnabled() {
        return launcherConfig.getDebug();
    }

    public static void logoutCurrentUser() {
        // Check if current user is not null
        if (currentUser != null) {
            try {
                // Invalidate current user
                AuthService.invalidateLogin( getCurrentUser(), getClientToken());

                // Delete current user information on disk (if exists)
                synchronized (savedUserFile) {
                    FileUtils.forceDelete(savedUserFile);
                }
            } catch ( GameAccountException | IOException e) {
                LogUtils.logError( "Unable to invalidate cached token prior to logout. Local account information will still be destroyed.");
            }
        }

        // Set login to display instead of application close
        loopLogin = true;
    }

    public static void doLogin() {
        // Login should only be handled in client mode
        if (gameMode == GameMode.CLIENT) {
            // Check for active internet connection
            boolean offlineMode = false;
            if (!NetworkUtils.isMojangAuthReachable()) {
                CountDownLatch waitForDialog = new CountDownLatch(1);
                AtomicReference<Alert> alert = new AtomicReference<>();
                GuiUtils.JFXPlatformRun( () -> {
                    // Show alert and prompt user for offline mode
                    alert.set(new Alert(Alert.AlertType.ERROR));
                    alert.get().setTitle("Offline Mode");
                    alert.get().setHeaderText("Can't Connect to Mojang!");
                    alert.get().setContentText("Check your internet connection and/or try again later.");
                    alert.get().showAndWait();
                    waitForDialog.countDown();
                });
                try {
                    waitForDialog.await();
                    System.exit(0);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }

            // Check for saved user on disk. Load and return if found
            boolean dirtyLogin = false;
            synchronized (savedUserFile) {
                if (ALLOW_SAVED_USERS && savedUserFile.isFile()) {
                    try {
                        currentUser = AuthAccount.readFromFile( LauncherConstants.LAUNCHER_CLIENT_SAVED_USER_FILE);

                        // Refresh auth only if not in offline mode
                        if (!offlineMode) {
                            AuthService.refreshAuth( getCurrentUser(), getClientToken());
                            AuthAccount.writeToFile( savedUserFile.getPath(), getCurrentUser());
                        }
                        return;
                    } catch ( GameAccountException e) {
                        dirtyLogin = true;
                    }
                }
            }

            // Show login screen
            LoginWindow loginWindow = new LoginWindow();
            loginWindow.show();

            // Show error if exception encountered above (need to wait for GUI)
            if (dirtyLogin) {
                LogUtils.logError( "Unable to load remembered user account.");
            }

            // Wait for login screen to complete
            try {
                currentUser = loginWindow.waitForLoginInfo();
            } catch (InterruptedException e) {
                LogUtils.logError( "Unable to wait for pending login task.");
                closeApp();
            }

            // Close login screen once complete
            loginWindow.close();
        }
    }
    //endregion

    //region: Core Methods
    public static void main(String[] args) {
        // Before the weird font glitches make people crazy, fix them
        System.setProperty("prism.lcdtext", "false");
        System.setProperty("prism.text", "t2k");
        String initPackName = "";

        if (args.length == 0) gameMode = inferMode();
        else if (args.length == 1 && args[0].equals("-c")) gameMode = GameMode.CLIENT;
        else if (args.length == 1 && args[0].equals("-s")) gameMode = GameMode.SERVER;
        else if (args.length == 1) initPackName = args[0];
        else if (args.length == 2 && args[0].equals("-c")) {
            gameMode = GameMode.CLIENT;
            initPackName = args[1];
        } else if (args.length == 2 && args[0].equals("-s")) {
            gameMode = GameMode.SERVER;
            initPackName = args[1];
        } else {
            System.out.println("ERROR: Your argument(s) are invalid.\nUsage: launcher.jar [ -s [modpack] | -c [modpack] | modpack | -a ]");
            return;
        }

        // Configure logging to file in launcher directory
        Timestamp logTimeStamp = new Timestamp(System.currentTimeMillis());
        SimpleDateFormat logFileNameTimeStampFormat = new SimpleDateFormat("yyyy-MM-dd--HH-mm-ss");
        String modeStr = gameMode == GameMode.SERVER ? "SRV" : "CLIENT";
        File logFile = new File(getLogFolderPath() + File.separator + "Log_" + modeStr + "_" + logFileNameTimeStampFormat.format(logTimeStamp) + ".log");
        try {
            LogUtils.initLogSys( logFile);
        } catch (IOException e) {
            e.printStackTrace();
            System.err.println("OH NO! The logger didn't configure correctly.");
        }


        // Run main functions of launcher (and loop if login re-required)
        while (loopLogin) {
            loopLogin = false;
            launcherConfig = null;
            doLogin();
            doLocalJDK();
            buildMemoryModpackList();
            doModpackSelection(initPackName);
        }

        // Force call to exit
        System.exit(0);
    }
    //endregion
}
