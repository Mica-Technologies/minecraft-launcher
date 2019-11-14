package com.micatechnologies.minecraft.forgelauncher;

import com.google.common.hash.Hashing;
import com.google.common.io.Files;
import com.micatechnologies.minecraft.authlib.MCAuthAccount;
import com.micatechnologies.minecraft.authlib.MCAuthException;
import com.micatechnologies.minecraft.authlib.MCAuthService;
import com.micatechnologies.minecraft.forgemodpacklib.MCForgeModpack;
import com.micatechnologies.minecraft.forgemodpacklib.MCForgeModpackException;
import com.micatechnologies.minecraft.forgemodpacklib.MCForgeModpackProgressProvider;
import com.micatechnologies.minecraft.forgemodpacklib.MCModpackOSUtils;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.SingleSelectionModel;
import javafx.scene.image.Image;
import javafx.stage.Stage;

import javax.imageio.ImageIO;
import javax.swing.JFrame;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.rauschig.jarchivelib.ArchiveFormat;
import org.rauschig.jarchivelib.Archiver;
import org.rauschig.jarchivelib.ArchiverFactory;
import org.rauschig.jarchivelib.CompressionType;

public class LauncherCore {

    static int launcherMode;

    private static String gameJavePath = "java";

    private static String clientToken = "";

    private static MCAuthAccount currentUser = null;

    static LauncherConfig launcherConfig = null;

    private static List<MCForgeModpack> modpacks = new ArrayList<>();

    public static int inferLauncherMode() {
        try {
            JFrame test = new JFrame("TESTING");
            test.setVisible(true);
            test.setVisible(false);
            return LauncherConstants.LAUNCHER_CLIENT_MODE;
        } catch (Exception ex) {
            return LauncherConstants.LAUNCHER_SERVER_MODE;
        }
    }

    public static String getGameJavaPath() {
        return gameJavePath;
    }

    private static void buildModpackCatalog()
            throws MalformedURLException, MCForgeModpackException {
        // Loop through all modpacks in config
        for (String modpackURL : launcherConfig.modpacks) {
            // Download manifest into sandbox to read name
            MCForgeModpack sandbox = MCForgeModpack.downloadFromURL(new URL(modpackURL), Paths
                    .get(LauncherConstants.PATH_MODPACKS_SANDBOX_FOLDER), launcherMode);

            // Download actual modpack manifest into folder with modpack name
            MCForgeModpack modpackObj = MCForgeModpack.downloadFromURL(new URL(modpackURL), Paths
                            .get(
                                    LauncherConstants.PATH_MODPACKS_FOLDER + File.separator + sandbox.getPackName()
                                            .replaceAll(
                                                    " ",
                                                    "")),
                    launcherMode);

            // Add manifest to launcher catalog
            modpacks.add(modpackObj);
        }
    }

    private static void doModpacksWindow()
            throws IllegalAccessException, InterruptedException, InstantiationException, MCForgeModpackException {
        // Create modpacks GUI
        final LauncherModpackGUI launcherModpackGUI = launchGUI(LauncherModpackGUI.class);

        // Populate user information
        Platform.runLater(
                () -> launcherModpackGUI.userMsg.setText("Hello, " + currentUser.getFriendlyName()));
        Platform.runLater(() -> {
            launcherModpackGUI.userIcon.setImage(new Image(
                    LauncherConstants.URL_MINECRAFT_USER_ICONS
                            .replace("user", currentUser.getUserIdentifier())));
        });

        // Populate list of modpacks
        final List<String> packListItems = new ArrayList<>();
        for (MCForgeModpack pack : modpacks) {
            packListItems.add(pack.getPackName());
        }
        Platform.runLater(() -> {
            launcherModpackGUI.packList.setItems(FXCollections.observableList(packListItems));
            launcherModpackGUI.packList.getSelectionModel().selectFirst();
        });

        // Setup exit button
        Platform.runLater(
                () -> launcherModpackGUI.exitBtn.setOnAction(actionEvent -> System.exit(-1)));

        // Setup settings button
        // TODO: make the settings button work

        // Setup play button
        launcherModpackGUI.playBtn.setOnAction(actionEvent -> {
            int selectedPackIndex = launcherModpackGUI.packList.getSelectionModel()
                    .getSelectedIndex();

            new Thread(() -> {
                try {
                    Platform.runLater(() -> launcherModpackGUI.getCurrStage().hide());
                    doLaunchModpack(selectedPackIndex);
                    Platform.runLater(() -> {
                        launcherModpackGUI.getCurrStage().show();
                        launcherModpackGUI.getCurrStage().requestFocus();
                    });
                } catch (MalformedURLException | MCForgeModpackException | IllegalAccessException | InterruptedException | InstantiationException e) {
                    e.printStackTrace();
                }
            }).start();
        });

        // Setup log out button
        launcherModpackGUI.logoutBtn.setOnAction(actionEvent -> {
            currentUser = null;
            new File(LauncherConstants.PATH_SAVED_USER_FILE).delete();
            Platform.runLater(() -> launcherModpackGUI.getCurrStage().close());
            try {
                runWithMode(launcherMode, 0);
            } catch (IOException | InterruptedException | IllegalAccessException | InstantiationException | MCForgeModpackException e) {
                e.printStackTrace();
                System.exit(-1);
            }
        });
    }

    /**
     * Downloads/updates the modpack with specified index, then launches the modpack game.
     * <p>
     * Applies: Client, Server
     *
     * @param modpackIndex index of modpack in config
     * @throws MalformedURLException   if unable to access modpack at config file URL
     * @throws MCForgeModpackException if unable to download modpack from config file URL
     * @throws IllegalAccessException  if unable to create progress GUI in client  mode
     * @throws InterruptedException    if unable to complete progress GUI initialization
     * @throws InstantiationException  if unable to create progress GUI class in client mode
     */
    private static void doLaunchModpack(int modpackIndex)
            throws MalformedURLException, MCForgeModpackException, IllegalAccessException, InterruptedException, InstantiationException {
        // Validate modpack index
        if (modpackIndex < 0 || modpackIndex >= modpacks.size()) {
            LauncherLogger.doErrorLog("");
        }

        // Print out information
        LauncherLogger.doDebugLog("Minimum RAM (MB): " + launcherConfig.minRAM);
        LauncherLogger.doDebugLog("Maximum RAM (MB): " + launcherConfig.maxRAM);

        // Get desired modpack
        URL modpackURL = new URL(launcherConfig.modpacks.get(modpackIndex));

        // Download current modpack manifest
        MCForgeModpack modpack = modpacks.get(modpackIndex);

        // Create progress GUI if in client mode
        final LauncherProgressGUI launcherProgressGUI =
                launcherMode == LauncherConstants.LAUNCHER_CLIENT_MODE ? launchGUI(
                        LauncherProgressGUI.class) : null;

        // Create progress handler
        modpack.setProgressProvider(new MCForgeModpackProgressProvider() {
            @Override
            public void updateProgressHandler(final double v, final String s) {
                // Create traditional output
                LauncherLogger.doStandardLog("Loading Modpack: " + ((int) v) + "% - " + s);

                // Handle GUI progress update (if applicable)
                if (launcherProgressGUI != null) {
                    Platform.runLater(() -> launcherProgressGUI.lowerText.setText(s));
                    Platform.runLater(() -> launcherProgressGUI.progressBar.setProgress(v));

                    // Hide window when complete (100%)
                    if (v == 100.0) {
                        Platform.runLater(() -> {
                            launcherProgressGUI.upperText.setText("Complete!");
                            launcherProgressGUI.lowerText.setText("Passing off to Minecraft...");
                        });
                        Platform.runLater(() -> {
                            try {
                                Thread.sleep(2500);
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                            launcherProgressGUI.getCurrStage().close();
                        });
                    }
                }
            }
        });

        // Start the game
        if (currentUser != null && launcherMode == LauncherConstants.LAUNCHER_CLIENT_MODE) {
            modpack.startGame(getGameJavaPath(), currentUser.getFriendlyName(),
                    currentUser.getUserIdentifier(), currentUser.getLastAccessToken(),
                    launcherConfig.minRAM, launcherConfig.maxRAM);
        } else if (launcherMode == LauncherConstants.LAUNCHER_SERVER_MODE) {
            modpack.startGame(getGameJavaPath(), "", "", "", launcherConfig.minRAM,
                    launcherConfig.maxRAM);
        } else {
            throw new IllegalStateException(
                    "Authenticated user is null or corrupt in a reliant launcher mode.");
        }
    }

    /**
     * Downloads a JRE for the current platform/operating system to the launcher installation
     * folder.
     * <p>
     * Applies: Client, Server
     */
    private static void downloadPlatformJDK() {
        try {
            // Build paths and File objects
            String fullJDKFolderPath = LauncherConstants.PATH_JRE_FOLDER;
            File jreArchiveFile = new File(LauncherConstants.PATH_JRE_ARCHIVE);
            File jreHashFile = new File(LauncherConstants.PATH_JRE_HASH);
            File jreFolder = new File(fullJDKFolderPath);

            // Verify JRE/JDK folder exists
            jreFolder.mkdirs();
            jreFolder.setReadable(true);
            jreFolder.setWritable(true);

            // Get proper URL and archive format for OS
            String jreDownloadURL;
            String jreHashDownloadURL;
            ArchiveFormat jreArchiveFormat;
            CompressionType jreArchiveCompressionType;
            if (MCModpackOSUtils.isWindows()) {
                jreDownloadURL = LauncherConstants.URL_JRE_WIN;
                jreHashDownloadURL = LauncherConstants.URL_JRE_WIN_HASH;
                jreArchiveFormat = ArchiveFormat.ZIP;
                jreArchiveCompressionType = null;
                gameJavePath = LauncherConstants.PATH_JRE_WIN_EXEC;
            } else if (MCModpackOSUtils.isUnix()) {
                jreDownloadURL = LauncherConstants.URL_JRE_UNX;
                jreHashDownloadURL = LauncherConstants.URL_JRE_UNX_HASH;
                jreArchiveFormat = ArchiveFormat.TAR;
                jreArchiveCompressionType = CompressionType.GZIP;
                gameJavePath = LauncherConstants.PATH_JRE_UNX_EXEC;
            } else if (MCModpackOSUtils.isMac()) {
                jreDownloadURL = LauncherConstants.URL_JRE_MAC;
                jreHashDownloadURL = LauncherConstants.URL_JRE_MAC_HASH;
                jreArchiveFormat = ArchiveFormat.TAR;
                jreArchiveCompressionType = CompressionType.GZIP;
                gameJavePath = LauncherConstants.PATH_JRE_MAC_EXEC;
            } else {
                System.err.println(
                        "Unable to identify the current operating system. Supported: Windows, macOS, Unix/Linux. Current OS: "
                                + System.getProperty("os.name"));
                return;
            }

            // Get JRE hash from url
            FileUtils.copyURLToFile(new URL(jreHashDownloadURL), jreHashFile);

            // Check if archive either 1. doesn't exist or 2. has a non-matching hash.
            if (!jreArchiveFile.exists() || !Files.hash(jreArchiveFile, Hashing.sha256())
                    .toString().equalsIgnoreCase(
                            FileUtils.readFileToString(jreHashFile, Charset.defaultCharset()))) {
                // Archive is either missing or invalid. Download from URL
                FileUtils.copyURLToFile(new URL(jreDownloadURL), jreArchiveFile);

                // Delete old extracted JRE (if it exists)
                File extractedJreFolder = new File(LauncherConstants.PATH_JRE_EXTRACTED_FOLDER);
                if (extractedJreFolder.exists()) {
                    FileUtils.deleteDirectory(extractedJreFolder);
                }

                // Extract downloaded archive
                Archiver archiver;
                if (jreArchiveCompressionType != null) {
                    archiver = ArchiverFactory.createArchiver(jreArchiveFormat,
                            jreArchiveCompressionType);
                } else {
                    archiver = ArchiverFactory.createArchiver(jreArchiveFormat);
                }
                archiver.extract(jreArchiveFile, jreFolder);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Creates and launches the JavaFX application gui (specified as a generic)
     * <p>
     * Applies: Client
     *
     * @param type gui class type object
     * @param <T>  gui class type parameter
     * @return created JavaFX gui object
     * @throws IllegalAccessException if invalid gui class specified
     * @throws InstantiationException if unable to create gui
     */
    private static <T> T launchGUI(Class<T> type)
            throws IllegalAccessException, InstantiationException, InterruptedException {
        // Do not allow classes that aren't subclasses of Application
        if (!Application.class.isAssignableFrom(type)) {
            throw new IllegalAccessException(
                    type.getTypeName() + " type parameter is not a subclass of javafx.Application. ");
        }

        // Create GUI
        T gui = type.newInstance();

        // Make sure JavaFX runtime doesn't close when hiding
        Platform.setImplicitExit(false);

        // Try to open GUI with platform startup (JavaFX platform will be started)
        try {
            Platform.startup(() -> {
                try {
                    ((Application) gui).start(new Stage());
                } catch (Exception e) {
                    System.err.println("Unable to create application GUI.");
                    e.printStackTrace();
                    System.exit(-1);
                }
            });
        }
        // Try to open login GUI with platform runLater (JavaFX platform must be running)
        // Catch will be called if platform already started up
        catch (IllegalStateException e) {
            Platform.runLater(() -> {
                try {
                    ((Application) gui).start(new Stage());
                } catch (Exception ee) {
                    System.err.println("Unable to create application GUI.");
                    ee.printStackTrace();
                    System.exit(-1);
                }
            });
        }

        // Return GUI object once it is ready
        if (gui instanceof LauncherProgressGUI) {
            ((LauncherProgressGUI) gui).readyLatch.await();
        } else if (gui instanceof LauncherLoginGUI) {
            ((LauncherLoginGUI) gui).readyLatch.await();
        } else if (gui instanceof LauncherModpackGUI) {
            ((LauncherModpackGUI) gui).readyLatch.await();
        }

        return gui;
    }

    /**
     * Attempts to read a saved user account from file (if saved/exists). If unable to read, or file
     * does not exist, shows login gui and manages login.
     * <p>
     * Applies: Client
     *
     * @throws InterruptedException if unable to complete login
     */
    private static void doLogin()
            throws InterruptedException, InstantiationException, IllegalAccessException {
        // Crete file object for saved user file
        File savedUserFile = new File(LauncherConstants.PATH_SAVED_USER_FILE);

        // If saved user file exists on disk, read its
        // DISABLED BECAUSE SAVED USERS DONT VALID FOR SOME REASON
        if (false && savedUserFile.exists()) {
            try {
                // Try to read saved user account from file
                currentUser = MCAuthAccount.readFromFile(LauncherConstants.PATH_SAVED_USER_FILE);

                // If successful, continue to renew access token, then return
                try {
                    MCAuthService.refreshAuth(currentUser, clientToken);
                    return;
                } catch (MCAuthException e) {
                    e.printStackTrace();
                    LauncherLogger.doErrorLog(
                            "An error occurred while refreshing the account access token.");
                }
            } catch (MCAuthException ignored) {
                // Show warning for corrupt saved user file
                System.err.println(
                        "An error occurred while attempting to load remembered user account. Cached account may be corrupt. Login is required to continue.");
            }
        }

        // Create login GUI
        LauncherLoginGUI launcherLoginGUI = launchGUI(LauncherLoginGUI.class);

        // Create latch to manage waiting for successful authentication
        CountDownLatch latch = new CountDownLatch(1);

        // Setup GUI login button listener
        launcherLoginGUI.loginButton.setOnAction(actionEvent -> {
            // Get username and password
            String username = launcherLoginGUI.emailField.getText();
            String password = launcherLoginGUI.passwordField.getText();

            // Create AuthAccount with given username
            MCAuthAccount account = new MCAuthAccount(username);

            // Attempt authentication with given password
            try {
                MCAuthService.usernamePasswordAuth(account, password, clientToken);
                currentUser = account;
                if (launcherLoginGUI.rememberCheckBox.isSelected()) {
                    MCAuthAccount.writeToFile(LauncherConstants.PATH_SAVED_USER_FILE,
                            currentUser);
                }
                Platform.runLater(() -> launcherLoginGUI.loginButton.setText("Continuing..."));
                Platform.runLater(() -> launcherLoginGUI.getCurrStage().close());

                // Count down the latch to allow launcher to continue
                latch.countDown();
            }
            // Handle authentication failure
            catch (MCAuthException e) {
                // Clear password field and show 'Try Again'
                Platform.runLater(() -> launcherLoginGUI.passwordField.clear());
                Platform.runLater(() -> launcherLoginGUI.loginButton.setText("Try Again"));
            }
        });

        // Wait on latch for successful authentication. (Login button listener counts down the latch)
        latch.await();
    }

    /**
     * Reads the client token to memory from saved file location. If file does not exist, a new
     * client token will be generated, written to file, then read to memory.
     * <p>
     * Applies: Client
     *
     * @throws IOException if unable to read/write to file
     */
    private static void doReadClientToken() throws IOException {
        File clientTokenFile = new File(LauncherConstants.PATH_LAUNCHER_CLIENT_TOKEN);
        if (clientTokenFile.exists()) {
            clientToken = FileUtils.readFileToString(clientTokenFile, Charset.defaultCharset());
        } else {
            clientToken = UUID.randomUUID().toString();
            FileUtils.writeStringToFile(clientTokenFile, clientToken, Charset.defaultCharset());
        }
    }

    /**
     * Setup, initialize and run the launcher with the specified launcher mode.
     * <p>
     * Applies: Client, Server
     *
     * @param mode                   launcher mode
     * @param serverModpackSelection modpack index for server mode
     * @throws IOException          if unable to read client token
     * @throws InterruptedException if unable to handle account login
     */
    static void runWithMode(int mode, int serverModpackSelection)
            throws IOException, InterruptedException, IllegalAccessException, InstantiationException, MCForgeModpackException {
        if (launcherMode != LauncherConstants.LAUNCHER_CLIENT_MODE
                && launcherMode != LauncherConstants.LAUNCHER_SERVER_MODE) {
            throw new IllegalArgumentException(
                    "Specified launcher mode does not correspond to a valid launcher mode.");
        }

        // Store desired launcher mode in memory
        launcherMode = mode;

        // Read launcher configuration to memory
        LauncherProgressGUI initProgressGUI = null;
        if (launcherMode == LauncherConstants.LAUNCHER_CLIENT_MODE) {
            initProgressGUI = launchGUI(LauncherProgressGUI.class);
            final LauncherProgressGUI finalProgressGUI = initProgressGUI;
            Platform.runLater(() -> {
                finalProgressGUI.progressBar.setProgress(
                        ProgressIndicator.INDETERMINATE_PROGRESS);
                finalProgressGUI.upperText.setText("Initializing Launcher");
                finalProgressGUI.lowerText.setText("Loading launcher configuration...");
            });
        }
        final LauncherProgressGUI finalProgressGUI = initProgressGUI;
        launcherConfig = LauncherConfig.open();
        if (launcherMode == LauncherConstants.LAUNCHER_CLIENT_MODE && finalProgressGUI != null) {
            Platform.runLater(() -> finalProgressGUI.lowerText
                    .setText("Loading launcher configuration...DONE"));
        }

        // Build catalog of modpacks
        if (launcherMode == LauncherConstants.LAUNCHER_CLIENT_MODE && finalProgressGUI != null) {
            Platform.runLater(
                    () -> finalProgressGUI.lowerText.setText("Building local modpack catalog..."));
        }
        buildModpackCatalog();
        if (launcherMode == LauncherConstants.LAUNCHER_CLIENT_MODE && finalProgressGUI != null) {
            Platform.runLater(() -> finalProgressGUI.lowerText
                    .setText("Building local modpack catalog...DONE"));
        }

        // Get JRE
        if (launcherMode == LauncherConstants.LAUNCHER_CLIENT_MODE && finalProgressGUI != null) {
            Platform.runLater(
                    () -> finalProgressGUI.lowerText.setText("Fetching platform JRE..."));
        }
        downloadPlatformJDK();
        if (launcherMode == LauncherConstants.LAUNCHER_CLIENT_MODE && finalProgressGUI != null) {
            Platform.runLater(
                    () -> finalProgressGUI.lowerText.setText("Fetching platform JRE...DONE"));
            Platform.runLater(
                    () -> finalProgressGUI.upperText.setText("Initializing Launcher Complete"));
        }

        // Read client token, handle login, then show modpacks GUI (if client)
        if (launcherMode == LauncherConstants.LAUNCHER_CLIENT_MODE) {
            doReadClientToken();
            if (finalProgressGUI != null) {
                Platform.runLater(() -> finalProgressGUI.getCurrStage().close());
            }
            doLogin();
            doModpacksWindow();
        }

        // Launch server
        if (launcherMode == LauncherConstants.LAUNCHER_SERVER_MODE) {
            doLaunchModpack(0);
        }
    }

    /**
     * Launcher main method. This is the primary program entry point.
     * <p>
     * Applies: Client, Server
     *
     * @param args program arguments (ignored)
     */
    public static void main(String[] args)
            throws IOException, InterruptedException, InstantiationException, IllegalAccessException, MCForgeModpackException {
        // Mode not specified. Run with inferred launcher mode.
        if (args.length == 0) {
            runWithMode(inferLauncherMode(), 0);
        }
        // Server mode specified via `-server` or `-s`. Run with server mode.
        else if (args.length == 1 && (args[0].equals("-server") || args[0].equals(
                "-s"))) {
            runWithMode(LauncherConstants.LAUNCHER_SERVER_MODE, 0);
        }
        // Server mode specified via `-server` or `-s` with modpack selection override. Run with server mode.
        else if (args.length == 2 && (args[0].equals("-server") || args[0].equals("-s"))
                && NumberUtils.isCreatable(args[1])) {
            runWithMode(LauncherConstants.LAUNCHER_SERVER_MODE,
                    NumberUtils.createInteger(args[1]));
        }
        // Client mode specified via `-client` or `-c`. Run with client mode.
        else if (args.length == 1 && (args[0].equals("-client") || args[0].equals(
                "-c"))) {
            runWithMode(LauncherConstants.LAUNCHER_CLIENT_MODE, 0);
        }
        // Valid mode or arguments not specified. Show error and output proper usage information.
        else {
            System.err.println(
                    "Launcher cannot start. Unknown argument(s) or bad argument(s) format.");
            System.out.println(LauncherConstants.LAUNCHER_FULL_NAME);
            System.out.println(
                    "Usage: launcher.jar [-server | -s [modpack index]] [-client | -c]");
        }
    }
}
