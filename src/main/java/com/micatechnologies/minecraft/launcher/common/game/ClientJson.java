package com.micatechnologies.minecraft.launcher.common.game;

import com.google.gson.annotations.SerializedName;
import com.micatechnologies.minecraft.launcher.common.files.ManagedGameFile;
import com.micatechnologies.minecraft.launcher.common.game.MinecraftVersionManifest.Argument;
import com.micatechnologies.minecraft.launcher.common.game.MinecraftVersionManifest.Download;
import com.micatechnologies.minecraft.launcher.common.game.MinecraftVersionManifest.OS;
import com.microsoft.aad.msal4j.OSHelper;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Class representing the manifest of specific Minecraft version in a GSON serializable format.
 *
 * <p>This format is described and linked at <a
 * href="https://minecraft.fandom.com/wiki/Client.json">
 * https://minecraft.fandom.com/wiki/Client.json</a></p>
 *
 * <p>Additional information and more about specific fields not already detailed below may be found
 * at <a href="https://wiki.vg/Game_files">https://wiki.vg/Game_files</a></p>
 *
 * @author Mica Technologies
 * @version 2024.1
 * @implNote This class contains portions which were adapted from previous versions of the Mica
 *     Minecraft Launcher. This class therefore predates version 2024.1, but versioning has been
 *     introduced or made consistent at version 2024.1.
 * @since 2024.1
 */
public class ClientJson {


  /**
   * The arguments for the specific version of Minecraft.
   *
   * <p>The arguments are the command-line arguments that are passed to the game when it is
   * launched. These arguments are used to configure the game, such as setting the maximum amount of
   * memory that the game can use, or enabling or disabling certain features.</p>
   *
   * @since 2024.1
   */
  @SerializedName("arguments")
  private Arguments arguments;

  /**
   * The asset index information for the specific version of Minecraft.
   *
   * <p>The asset index is a JSON file that contains a list of all the assets that are used by
   * the game. This includes textures, sounds, and other miscellaneous files. The asset index also
   * contains the SHA-1 hash of each asset, which is used to verify the integrity of the asset.</p>
   *
   * @since 2024.1
   */
  @SerializedName("assetIndex")
  private AssetIndex assetIndex;

  /**
   * The assets version for the specific version of Minecraft.
   *
   * <p>The assets version is a string that is used to identify the version of the assets that are
   * used by the game.</p>
   *
   * @since 2024.1
   */
  @SerializedName("assets")
  private String assets;

  /**
   * The compliance level for the specific version of Minecraft.
   *
   * <p>For older versions of the game, this is typically {@code 0}. Older versions of the game
   * do not support the latest player safety features, thus the compliance level value was
   * established as a way to indicate this in the version manifest and native launcher. Newer
   * versions of the game typically have {@code 1} for this field.</p>
   *
   * @since 2024.1
   */
  @SerializedName("complianceLevel")
  private int complianceLevel;

  /**
   * The downloads information for the specific version of Minecraft.
   *
   * <p>The downloads information is a JSON object that contains the download information for the
   * client and server JAR files, as well as the mappings for the client and server JAR files.</p>
   *
   * @since 2024.1
   */
  @SerializedName("downloads")
  private Downloads downloads;

  /**
   * The ID for the specific version of Minecraft.
   *
   * @since 2024.1
   */
  @SerializedName("id")
  private String id;

  /**
   * The Java version information for the specific version of Minecraft.
   *
   * <p>The Java version information is a JSON object that contains the component and major
   * version of the Java version that is required to run the game.</p>
   *
   * @since 2024.1
   */
  @SerializedName("javaVersion")
  private JavaVersion javaVersion;

  /**
   * The libraries information for the specific version of Minecraft.
   *
   * <p>The libraries information is a list of {@link Library} objects that contain the information
   * about the libraries that are required to run the game. Each {@link Library} object contains
   * information about the library, such as the name, URL, and SHA-1 hash of the library.</p>
   *
   * <p>The libraries information also contains the rules that define the conditions under which
   * the library should be used. The rules contain an action, which is either "allow" or "disallow",
   * a list of features, and an {@link OS} object, which defines the operating system conditions for
   * the rule.</p>
   *
   * @since 2024.1
   */
  @SerializedName("libraries")
  private List<Library> libraries;

  /**
   * The logging information for the specific version of Minecraft.
   *
   * <p>The logging information is a JSON object that contains the information about the logging
   * configuration for the game. The logging configuration is used to configure the logging behavior
   * of the game, such as the log file location and log level.</p>
   *
   * @since 2024.1
   */
  @SerializedName("logging")
  private Logging logging;

  /**
   * The main class for the specific version of Minecraft.
   *
   * <p>The main class is the class that contains the main method of the game. This is the class
   * that is executed when the game is launched.</p>
   *
   * @since 2024.1
   */
  @SerializedName("mainClass")
  private String mainClass;

  /**
   * The Minecraft arguments for the specific version of Minecraft.
   *
   * <p>The Minecraft arguments are the arguments that are passed to the game when it is launched.
   * These arguments are used to configure the game, such as setting the maximum amount of memory
   * that the game can use, or enabling or disabling certain features.</p>
   *
   * @since 2024.1
   */
  @SerializedName("minecraftArguments")
  private String minecraftArguments;

  /**
   * The minimum launcher version required to run the specific version of Minecraft.
   *
   * <p>The minimum launcher version is the version of the native launcher that is required to run
   * the game. If the version of the native launcher is less than the minimum launcher version, the
   * game will not run.</p>
   *
   * @since 2024.1
   */
  @SerializedName("minimumLauncherVersion")
  private int minimumLauncherVersion;

  /**
   * The release time for the specific version of Minecraft.
   *
   * <p>The release time is the time at which the specific version of Minecraft was released. This
   * is typically in the format "yyyy-MM-dd'T'HH:mm:ssZ".</p>
   *
   * @since 2024.1
   */
  @SerializedName("releaseTime")
  private String releaseTime;

  /**
   * The time for the specific version of Minecraft.
   *
   * <p>The time is the time at which the specific version of Minecraft was released. This is
   * typically in the format "yyyy-MM-dd'T'HH:mm:ssZ".</p>
   *
   * @since 2024.1
   */
  @SerializedName("time")
  private String time;

  /**
   * The type for the specific version of Minecraft.
   *
   * <p>The type is the type for the specific version of Minecraft. This is typically "release" for
   * official releases, "snapshot" for development snapshots, and "old_beta" or "old_alpha" for
   * older versions of the game.</p>
   *
   * @since 2024.1
   */
  @SerializedName("type")
  private String type;

  /**
   * Gets the arguments for the specific version of Minecraft.
   *
   * <p>The arguments are the command-line arguments that are passed to the game when it is
   * launched. These arguments are used to configure the game, such as setting the maximum amount of
   * memory that the game can use, or enabling or disabling certain features.</p>
   *
   * @return The arguments for the specific version of Minecraft.
   *
   * @since 2024.1
   */
  public Arguments getArguments() {
    return arguments;
  }

  /**
   * Gets the asset index information for the specific version of Minecraft.
   *
   * <p>The asset index is a JSON file that contains a list of all the assets that are used by
   * the game. This includes textures, sounds, and other miscellaneous files. The asset index also
   * contains the SHA-1 hash of each asset, which is used to verify the integrity of the asset.</p>
   *
   * @return The asset index information for the specific version of Minecraft.
   *
   * @since 2024.1
   */
  public AssetIndex getAssetIndex() {
    return assetIndex;
  }

  /**
   * Gets the assets version for the specific version of Minecraft.
   *
   * <p>The assets version is a string that is used to identify the version of the assets that are
   * used by the game.</p>
   *
   * @return The assets version for the specific version of Minecraft.
   *
   * @since 2024.1
   */
  public String getAssets() {
    return assets;
  }

  /**
   * Gets the compliance level for the specific version of Minecraft.
   *
   * <p>For older versions of the game, this is typically {@code 0}. Older versions of the game
   * do not support the latest player safety features, thus the compliance level value was
   * established as a way to indicate this in the version manifest and native launcher. Newer
   * versions of the game typically have {@code 1} for this field.</p>
   *
   * @return The compliance level for the specific version of Minecraft.
   *
   * @since 2024.1
   */
  public int getComplianceLevel() {
    return complianceLevel;
  }

  /**
   * Gets the downloads information for the specific version of Minecraft.
   *
   * <p>The downloads information is a JSON object that contains the download information for the
   * client and server JAR files, as well as the mappings for the client and server JAR files.</p>
   *
   * @return The downloads information for the specific version of Minecraft.
   *
   * @since 2024.1
   */
  public Downloads getDownloads() {
    return downloads;
  }

  /**
   * Gets the ID for the specific version of Minecraft.
   *
   * @return The ID for the specific version of Minecraft.
   *
   * @since 2024.1
   */
  public String getId() {
    return id;
  }

  /**
   * Gets the Java version information for the specific version of Minecraft.
   *
   * <p>The Java version information is a JSON object that contains the component and major
   * version of the Java version that is required to run the game.</p>
   *
   * @return The Java version information for the specific version of Minecraft.
   *
   * @since 2024.1
   */
  public JavaVersion getJavaVersion() {
    return javaVersion;
  }

  /**
   * Gets the libraries information for the specific version of Minecraft.
   *
   * <p>The libraries information is a list of {@link Library} objects that contain the information
   * about the libraries that are required to run the game. Each {@link Library} object contains
   * information about the library, such as the name, URL, and SHA-1 hash of the library.</p>
   *
   * <p>The libraries information also contains the rules that define the conditions under which
   * the library should be used. The rules contain an action, which is either "allow" or "disallow",
   * a list of features, and an {@link OS} object, which defines the operating system conditions for
   * the rule.</p>
   *
   * @return The libraries information for the specific version of Minecraft.
   *
   * @since 2024.1
   */
  public List<Library> getLibraries() {
    return libraries;
  }

  /**
   * Gets the logging information for the specific version of Minecraft.
   *
   * <p>The logging information is a JSON object that contains the information about the logging
   * configuration for the game. The logging configuration is used to configure the logging behavior
   * of the game, such as the log file location and log level.</p>
   *
   * @return The logging information for the specific version of Minecraft.
   *
   * @since 2024.1
   */
  public Logging getLogging() {
    return logging;
  }

  /**
   * Gets the main class for the specific version of Minecraft.
   *
   * <p>The main class is the class that contains the main method of the game. This is the class
   * that is executed when the game is launched.</p>
   *
   * @return The main class for the specific version of Minecraft.
   *
   * @since 2024.1
   */
  public String getMainClass() {
    return mainClass;
  }

  /**
   * Gets the Minecraft arguments for the specific version of Minecraft.
   *
   * <p>The Minecraft arguments are the arguments that are passed to the game when it is launched.
   * These arguments are used to configure the game, such as setting the maximum amount of memory
   * that the game can use, or enabling or disabling certain features.</p>
   *
   * @return The Minecraft arguments for the specific version of Minecraft.
   *
   * @since 2024.1
   */
  public String getMinecraftArguments() {
    return minecraftArguments;
  }

  /**
   * Gets the minimum launcher version required to run the specific version of Minecraft.
   *
   * <p>The minimum launcher version is the version of the native launcher that is required to run
   * the game. If the version of the native launcher is less than the minimum launcher version, the
   * game will not run.</p>
   *
   * @return The minimum launcher version required to run the specific version of Minecraft.
   *
   * @since 2024.1
   */
  public int getMinimumLauncherVersion() {
    return minimumLauncherVersion;
  }

  /**
   * Gets the release time for the specific version of Minecraft.
   *
   * <p>The release time is the time at which the specific version of Minecraft was released. This
   * is typically in the format "yyyy-MM-dd'T'HH:mm:ssZ".</p>
   *
   * @return The release time for the specific version of Minecraft.
   *
   * @since 2024.1
   */
  public String getReleaseTime() {
    return releaseTime;
  }

  /**
   * Gets the time for the specific version of Minecraft.
   *
   * <p>The time is the time at which the specific version of Minecraft was released. This is
   * typically in the format "yyyy-MM-dd'T'HH:mm:ssZ".</p>
   *
   * @return The time for the specific version of Minecraft.
   *
   * @since 2024.1
   */
  public String getTime() {
    return time;
  }

  /**
   * Gets the type for the specific version of Minecraft.
   *
   * <p>The type is the type for the specific version of Minecraft. This is typically "release" for
   * official releases, "snapshot" for development snapshots, and "old_beta" or "old_alpha" for
   * older versions of the game.</p>
   *
   * @return The type for the specific version of Minecraft.
   *
   * @since 2024.1
   */
  public String getType() {
    return type;
  }

  // region: Utility Methods
  public List<Library> getLibrariesForHost() {
    List<Library> eligibleLibraries = new ArrayList<>();
    for (Library library : libraries) {
      if (isLibraryEligible(library)) {
        eligibleLibraries.add(library);
      }
    }
    return eligibleLibraries;
  }

  private boolean isLibraryEligible(Library library) {
    if (library.getRules() == null || library.getRules().isEmpty()) {
      return true;
    }
    for (Rule rule : library.getRules()) {
      if (!isRuleSatisfied(rule)) {
        return false;
      }
    }
    return true;
  }

  private boolean isRuleSatisfied(Rule rule) {
    String action = rule.getAction();
    Os os = rule.getOs();

    if (os == null) {
      return "allow".equals(action);
    }

    boolean isCurrentOs =
        (os.getName() == null || isCurrentOs(os.getName())) && (os.getVersion() == null
            || os.getVersion().matches(System.getProperty("os.version"))) && (os.getArch() == null
            || os.getArch().equals(System.getProperty("os.arch")));

    return "allow".equals(action) == isCurrentOs;
  }

  private boolean isCurrentOs(String osName) {
    if ("windows".equals(osName)) {
      return OSHelper.isWindows();
    } else if ("osx".equals(osName)) {
      return OSHelper.isMac();
    } else if ("linux".equals(osName)) {
      return OSHelper.isLinux();
    }
    return false;
  }

  public List<DownloadInfo> getNativesForHost() {
    List<DownloadInfo> natives = new ArrayList<>();
    for (Library library : libraries) {
      if (library.getNatives() != null) {
        String classifier = getClassifierForHost(library.getNatives());
        if (classifier != null && library.getDownloads().getClassifiers() != null) {
          DownloadInfo nativeDownload = library.getDownloads().getClassifiers().get(classifier);
          if (nativeDownload != null) {
            natives.add(nativeDownload);
          }
        }
      }
    }
    return natives;
  }

  private String getClassifierForHost(Map<String, String> natives) {
    if (OSHelper.isWindows()) {
      return natives.get("windows");
    } else if (OSHelper.isMac()) {
      return natives.get("osx");
    } else if (OSHelper.isLinux()) {
      return natives.get("linux");
    }
    return null;
  }

  // endregion

  // region: Nested classes

  /**
   * Inner class representing the arguments information for a specific Minecraft version.
   *
   * <p>The arguments information is a JSON object that contains the game and JVM arguments for the
   * specific version of Minecraft.</p>
   *
   * <p>The game arguments are the arguments that are passed to the game itself, while the JVM
   * arguments are the arguments that are passed to the Java Virtual Machine (JVM) that runs the
   * game.</p>
   *
   * <p>The game arguments are typically a list of strings, while the JVM arguments are typically a
   * list of {@link Argument} objects.</p>
   *
   * <p>The {@link Argument} objects contain a list of {@link Rule} objects, which define the
   * conditions under which the argument should be used. The {@link Argument} objects also contain
   * the value of the argument, which can be a string or a list of strings.</p>
   *
   * <p>The {@link Rule} objects define the conditions under which the argument should be used. The
   * {@link Rule} objects contain an action, which is either "allow" or "disallow", a list of
   * features, and an {@link OS} object, which defines the operating system conditions for the
   * rule.</p>
   *
   * <p>The {@link OS} object defines the name, version, and architecture of the operating system
   * for the rule.</p>
   *
   * <p>For more information, see the <a
   * href="https://minecraft.fandom.com/wiki/Arguments">Minecraft Wiki</a>.</p>
   *
   * @see Argument
   * @see Rule
   * @see OS
   * @see <a href="https://minecraft.fandom.com/wiki/Arguments">Minecraft Wiki</a>
   * @since 2024.1
   */
  public static class Arguments {

    /**
     * The game arguments for the specific version of Minecraft.
     *
     * <p>The game arguments are the arguments that are passed to the game itself.</p>
     *
     * <p>This is a list of strings and/or {@link Argument} objects. Commonly, both are
     * simultaneously in the list.</p>
     *
     * @since 2024.1
     */
    @SerializedName("game")
    private List<Object> game;

    /**
     * The JVM arguments for the specific version of Minecraft.
     *
     * <p>The JVM arguments are the arguments that are passed to the Java Virtual Machine (JVM)
     * that runs the game.</p>
     *
     * <p>This is a list of strings and/or {@link Argument} objects. Commonly, both are
     * simultaneously in the list.</p>
     *
     * @since 2024.1
     */
    @SerializedName("jvm")
    private List<Object> jvm;

    /**
     * Gets the game arguments for the specific version of Minecraft.
     *
     * <p>The game arguments are the arguments that are passed to the game itself.</p>
     *
     * <p>This is a list of strings and/or {@link Argument} objects. Commonly, both are
     * simultaneously in the list.</p>
     *
     * @return The game arguments for the specific version of Minecraft.
     *
     * @since 2024.1
     */
    public List<Object> getGame() {
      return game;
    }

    /**
     * Gets the JVM arguments for the specific version of Minecraft.
     *
     * <p>The JVM arguments are the arguments that are passed to the Java Virtual Machine (JVM)
     * that runs the game.</p>
     *
     * <p>This is a list of strings and/or {@link Argument} objects. Commonly, both are
     * simultaneously in the list.</p>
     *
     * @return The JVM arguments for the specific version of Minecraft.
     *
     * @since 2024.1
     */
    public List<Object> getJvm() {
      return jvm;
    }
  }

  /**
   * Inner class representing the asset index information of a specific Minecraft version.
   *
   * @author Mica Technologies
   * @implNote This class contains portions which were adapted from previous versions of the
   *     Mica Minecraft Launcher. This class therefore predates version 2024.1, but versioning has
   *     been introduced or made consistent at version 2024.1.
   * @since 2024.1
   */
  public static class AssetIndex {

    /**
     * The ID (version) of the asset index for the specific version of Minecraft.
     *
     * @since 2024.1
     */
    @SerializedName("id")
    private String id;

    /**
     * The SHA-1 hash of the asset index for the specific version of Minecraft.
     *
     * @since 2024.1
     */
    @SerializedName("sha1")
    private String sha1;

    /**
     * The size of the asset index for the specific version of Minecraft.
     *
     * @since 2024.1
     */
    @SerializedName("size")
    private int size;

    /**
     * The URL of the asset index for the specific version of Minecraft.
     *
     * @since 2024.1
     */
    @SerializedName("totalSize")
    private int totalSize;

    /**
     * The URL of the asset index for the specific version of Minecraft.
     *
     * @since 2024.1
     */
    @SerializedName("url")
    private String url;

    /**
     * Gets the ID (version) of the asset index for the specific version of Minecraft.
     *
     * @return The ID (version) of the asset index for the specific version of Minecraft.
     *
     * @since 2024.1
     */
    public String getId() {
      return id;
    }

    /**
     * Gets the SHA-1 hash of the asset index for the specific version of Minecraft.
     *
     * @return The SHA-1 hash of the asset index for the specific version of Minecraft.
     *
     * @since 2024.1
     */
    public String getSha1() {
      return sha1;
    }

    /**
     * Gets the size of the asset index for the specific version of Minecraft.
     *
     * @return The size of the asset index for the specific version of Minecraft.
     *
     * @since 2024.1
     */
    public int getSize() {
      return size;
    }

    /**
     * Gets the total size of the asset index for the specific version of Minecraft.
     *
     * @return The total size of the asset index for the specific version of Minecraft.
     *
     * @since 2024.1
     */
    public int getTotalSize() {
      return totalSize;
    }

    /**
     * Gets the URL of the asset index for the specific version of Minecraft.
     *
     * @return The URL of the asset index for the specific version of Minecraft.
     *
     * @since 2024.1
     */
    public String getUrl() {
      return url;
    }
  }

  /**
   * Inner class representing the various downloads information of a specific Minecraft version.
   *
   * <p>The downloads information is a JSON object that contains the download information for the
   * client and server JAR files, as well as the mappings for the client and server JAR files.</p>
   *
   * @author Mica Technologies
   * @implNote This class contains portions which were adapted from previous versions of the
   *     Mica Minecraft Launcher. This class therefore predates version 2024.1, but versioning has
   *     been introduced or made consistent at version 2024.1.
   * @since 2024.1
   */
  public static class Downloads {

    /**
     * The download information object for the client JAR file of the specific version of
     * Minecraft.
     *
     * @since 2024.1
     */
    @SerializedName("client")
    private DownloadInfo client;

    /**
     * The download information object for the server JAR file of the specific version of
     * Minecraft.
     *
     * @since 2024.1
     */
    @SerializedName("server")
    private DownloadInfo server;

    /**
     * The download information object for the client mappings JAR file of the specific version of
     * Minecraft.
     *
     * @since 2024.1
     */
    @SerializedName("client_mappings")
    private DownloadInfo clientMappings;

    /**
     * The download information object for the server mappings JAR file of the specific version of
     * Minecraft.
     *
     * @since 2024.1
     */
    @SerializedName("server_mappings")
    private DownloadInfo serverMappings;


    /**
     * Gets the download information object for the client JAR file of the specific version of
     * Minecraft.
     *
     * @return The download information object for the client JAR file of the specific version of
     *     Minecraft.
     *
     * @since 2024.1
     */
    public DownloadInfo getClient() {
      return client;
    }

    /**
     * Gets the download information object for the server JAR file of the specific version of
     * Minecraft.
     *
     * @return The download information object for the server JAR file of the specific version of
     *     Minecraft.
     *
     * @since 2024.1
     */
    public DownloadInfo getServer() {
      return server;
    }

    /**
     * Gets the download information object for the client mappings JAR file of the specific version
     * of Minecraft.
     *
     * @return The download information object for the client mappings JAR file of the specific
     *     version of Minecraft.
     *
     * @since 2024.1
     */
    public DownloadInfo getClientMappings() {
      return clientMappings;
    }

    /**
     * Gets the download information object for the server mappings JAR file of the specific version
     * of Minecraft.
     *
     * @return The download information object for the server mappings JAR file of the specific
     *     version of Minecraft.
     *
     * @since 2024.1
     */
    public DownloadInfo getServerMappings() {
      return serverMappings;
    }
  }

  /**
   * Inner class representing the download information of a file for a specific Minecraft version.
   *
   * <p>The download information object is a JSON object that contains the SHA-1 hash, size, and
   * URL of a file.</p>
   *
   * @author Mica Technologies
   * @implNote This class contains portions which were adapted from previous versions of the
   *     Mica Minecraft Launcher. This class therefore predates version 2024.1, but versioning has
   *     been introduced or made consistent at version 2024.1.
   * @since 2024.1
   */
  public static class DownloadInfo {

    /**
     * Path where the artifact is stored relative to the libraries directory.
     *
     * @since 2024.1
     */
    @SerializedName("path")
    private String path;

    /**
     * The ID of the file download for the specific version of Minecraft.
     *
     * @apiNote This field is not present for all downloads in the JSON manifest. When present,
     *     this field typically defines the name of the file.
     * @since 2024.1
     */
    @SerializedName("id")
    private String id;

    /**
     * The SHA-1 hash of the file download for the specific version of Minecraft.
     *
     * @since 2024.1
     */
    @SerializedName("sha1")
    private String sha1;

    /**
     * The size of the file download (in bytes) for the specific version of Minecraft.
     *
     * @since 2024.1
     */
    @SerializedName("size")
    private int size;

    /**
     * The URL of the file download for the specific version of Minecraft.
     *
     * @since 2024.1
     */
    @SerializedName("url")
    private String url;

    /**
     * Gets the path where the artifact is stored relative to the libraries directory.
     *
     * @return The path where the artifact is stored relative to the libraries directory.
     *
     * @since 2024.1
     */
    public String getPath() {
      return path;
    }

    /**
     * Gets the ID of the file download for the specific version of Minecraft.
     *
     * @return The ID of the file download for the specific version of Minecraft.
     *
     * @since 2024.1
     */
    public String getId() {
      return id;
    }

    /**
     * Gets the SHA-1 hash of the file download for the specific version of Minecraft.
     *
     * @return The SHA-1 hash of the file download for the specific version of Minecraft.
     *
     * @since 2024.1
     */
    public String getSha1() {
      return sha1;
    }

    /**
     * Gets the size of the file download (in bytes) for the specific version of Minecraft.
     *
     * @return The size of the file download (in bytes) for the specific version of Minecraft.
     *
     * @since 2024.1
     */
    public int getSize() {
      return size;
    }

    /**
     * Gets the URL of the file download for the specific version of Minecraft.
     *
     * @return The URL of the file download for the specific version of Minecraft.
     *
     * @since 2024.1
     */
    public String getUrl() {
      return url;
    }


    /**
     * Converts this download information object into a {@link ManagedGameFile} object using the
     * default file name, derived from the URL.
     *
     * @return A {@link ManagedGameFile} object representing this download information object using
     *     the default file name, derived from the URL.
     *
     * @see ManagedGameFile
     * @see ManagedGameFile#deriveFileNameFromURL(String)
     * @since 2024.1
     */
    public ManagedGameFile toManagedGameFileRelative() {
      if (id != null && !id.isEmpty()) {
        return new ManagedGameFile(id, url, size, sha1);
      } else {
        return new ManagedGameFile(url, size, sha1);
      }
    }

    /**
     * Converts this download information object into a {@link ManagedGameFile} object using the
     * specified file name.
     *
     * @param fileName The file name to use for the {@link ManagedGameFile} object.
     *
     * @return A {@link ManagedGameFile} object representing this download information object using
     *     the specified file name.
     *
     * @see ManagedGameFile
     * @since 2024.1
     */
    public ManagedGameFile toManagedGameFileRelative(String fileName) {
      return new ManagedGameFile(fileName, url, size, sha1);
    }

    /**
     * Converts this download information object into a {@link ManagedGameFile} object using the
     * specified parent directory and default file name, derived from the URL.
     *
     * @param parentDirectory The parent directory to use for the {@link ManagedGameFile} object.
     *
     * @return A {@link ManagedGameFile} object representing this download information object using
     *     the specified parent directory and default file name, derived from the URL.
     *
     * @see ManagedGameFile
     * @see ManagedGameFile#deriveFileNameFromURL(String)
     * @since 2024.1
     */
    public ManagedGameFile toManagedGameFileAbsolute(File parentDirectory) {
      String fileName;
      if (id != null && !id.isEmpty()) {
        fileName = parentDirectory.getAbsolutePath() + File.separator + id;
      } else {
        fileName = parentDirectory.getAbsolutePath() + File.separator
            + ManagedGameFile.deriveFileNameFromURL(url);
      }
      return new ManagedGameFile(fileName, url, size, sha1);
    }

    /**
     * Converts this download information object into a {@link ManagedGameFile} object using the
     * specified parent directory and default file name, derived from the URL.
     *
     * @param parentDirectory The parent directory to use for the {@link ManagedGameFile} object.
     *
     * @return A {@link ManagedGameFile} object representing this download information object using
     *     the specified parent directory and default file name, derived from the URL.
     *
     * @see ManagedGameFile
     * @see ManagedGameFile#deriveFileNameFromURL(String)
     * @since 2024.1
     */
    public ManagedGameFile toManagedGameFileAbsolute(String parentDirectory) {
      // Ensure that the parent directory ends with the file separator
      if (!parentDirectory.endsWith(File.separator)) {
        parentDirectory += File.separator;
      }

      String fileName;
      if (id != null && !id.isEmpty()) {
        fileName = parentDirectory + id;
      } else {
        fileName = parentDirectory + ManagedGameFile.deriveFileNameFromURL(url);
      }
      return new ManagedGameFile(fileName, url, size, sha1);
    }


    /**
     * Converts this download information object into a {@link ManagedGameFile} object using the
     * specified parent directory and file name.
     *
     * @param parentDirectory The parent directory to use for the {@link ManagedGameFile} object.
     * @param fileName        The file name to use for the {@link ManagedGameFile} object.
     *
     * @return A {@link ManagedGameFile} object representing this download information object using
     *     the specified parent directory and file name.
     *
     * @see ManagedGameFile
     * @since 2024.1
     */
    public ManagedGameFile toManagedGameFileAbsolute(File parentDirectory, String fileName) {
      final String absoluteFileName = parentDirectory.getAbsolutePath() + File.separator + fileName;
      return new ManagedGameFile(absoluteFileName, url, size, sha1);
    }

    /**
     * Converts this download information object into a {@link ManagedGameFile} object using the
     * specified parent directory and file name.
     *
     * @param parentDirectory The parent directory to use for the {@link ManagedGameFile} object.
     * @param fileName        The file name to use for the {@link ManagedGameFile} object.
     *
     * @return A {@link ManagedGameFile} object representing this download information object using
     *     the specified parent directory and file name.
     *
     * @see ManagedGameFile
     * @since 2024.1
     */
    public ManagedGameFile toManagedGameFileAbsolute(String parentDirectory, String fileName) {
      // Ensure that the parent directory ends with the file separator
      if (!parentDirectory.endsWith(File.separator)) {
        parentDirectory += File.separator;
      }

      final String absoluteFileName = parentDirectory + fileName;
      return new ManagedGameFile(absoluteFileName, url, size, sha1);
    }
  }

  /**
   * Inner class representing the Java version information for a specific Minecraft version.
   *
   * <p>The Java version information is a JSON object that contains the component and major version
   * of the Java version that is required to run the game.</p>
   *
   * @author Mica Technologies
   * @implNote This class contains portions which were adapted from previous versions of the
   *     Mica Minecraft Launcher. This class therefore predates version 2024.1, but versioning has
   *     been introduced or made consistent at version 2024.1.
   * @since 2024.1
   */
  public static class JavaVersion {

    /**
     * The component of the Java version that is required to run the game.
     *
     * <p>Typically, this is "jre-legacy" for older versions of the game, or "java-runtime-ABC" for
     * newer versions, where "ABC" is a codename such as "alpha" or "gamma".</p>
     *
     * @since 2024.1
     */
    @SerializedName("component")
    private String component;

    /**
     * The major version of the Java version that is required to run the game.
     *
     * <p>Typically, this is "8" for older versions of the game, or "16" for newer versions.</p>
     *
     * @since 2024.1
     */
    @SerializedName("majorVersion")
    private int majorVersion;

    /**
     * Gets the component of the Java version that is required to run the game.
     *
     * <p>Typically, this is "jre-legacy" for older versions of the game, or "java-runtime-ABC" for
     * newer versions, where "ABC" is a codename such as "alpha" or "gamma".</p>
     *
     * @return The component of the Java version that is required to run the game.
     *
     * @since 2024.1
     */
    public String getComponent() {
      return component;
    }

    /**
     * Gets the major version of the Java version that is required to run the game.
     *
     * <p>Typically, this is "8" for older versions of the game, or "16" for newer versions.</p>
     *
     * @return The major version of the Java version that is required to run the game.
     *
     * @since 2024.1
     */
    public int getMajorVersion() {
      return majorVersion;
    }
  }

  /**
   * Inner class representing the library information for a specific Minecraft version.
   *
   * <p>The library information is a JSON object that contains the download information for the
   * library, as well as the Maven name, native libraries, extraction information, and rules for the
   * library.</p>
   *
   * @author Mica Technologies
   * @implNote This class contains portions which were adapted from previous versions of the
   *     Mica Minecraft Launcher. This class therefore predates version 2024.1, but versioning has
   *     been introduced or made consistent at version 2024.1.
   * @since 2024.1
   */
  public static class Library {

    /**
     * The library's download information.
     *
     * @since 2024.1
     */
    @SerializedName("downloads")
    private LibraryDownloads downloads;

    /**
     * A Maven name for the library, in the format "groupId:artifactId:version".
     *
     * @since 2024.1
     */
    @SerializedName("name")
    private String name;

    /**
     * Rules defining conditions under which this library should be used.
     *
     * @since 2024.1
     */
    @SerializedName("rules")
    private List<Rule> rules;

    /**
     * Information about native libraries, key is the system, and value is the classifier.
     *
     * @since 2024.1
     */
    @SerializedName("natives")
    private Map<String, String> natives;

    /**
     * Information about extraction, including what to exclude.
     *
     * @since 2024.1
     */
    @SerializedName("extract")
    private Extract extract;

    /**
     * Gets the library's download information.
     *
     * @return The library's download information.
     *
     * @since 2024.1
     */
    public LibraryDownloads getDownloads() {
      return downloads;
    }

    /**
     * Gets the Maven name for the library, in the format "groupId:artifactId:version".
     *
     * @return The Maven name for the library, in the format "groupId:artifactId:version".
     *
     * @since 2024.1
     */
    public String getName() {
      return name;
    }

    /**
     * Gets the rules defining conditions under which this library should be used.
     *
     * @return The rules defining conditions under which this library should be used.
     *
     * @since 2024.1
     */
    public List<Rule> getRules() {
      return rules;
    }

    /**
     * Gets the information about native libraries, key is the system, and value is the classifier.
     *
     * @return The information about native libraries, key is the system, and value is the
     *     classifier.
     *
     * @since 2024.1
     */
    public Map<String, String> getNatives() {
      return natives;
    }

    /**
     * Gets the information about extraction, including what to exclude.
     *
     * @return The information about extraction, including what to exclude.
     *
     * @since 2024.1
     */
    public Extract getExtract() {
      return extract;
    }
  }

  /**
   * Inner class representing the download information for a library.
   *
   * @author Mica Technologies
   * @implNote This class contains portions which were adapted from previous versions of the
   *     Mica Minecraft Launcher. This class therefore predates version 2024.1, but versioning has
   *     been introduced or made consistent at version 2024.1.
   * @since 2024.1
   */
  public static class LibraryDownloads {

    /**
     * Information about the main artifact to be downloaded for this library.
     *
     * @since 2024.1
     */
    @SerializedName("artifact")
    private DownloadInfo artifact;

    /**
     * Classifiers for platform-specific artifacts.
     *
     * @since 2024.1
     */
    @SerializedName("classifiers")
    private Map<String, DownloadInfo> classifiers;

    /**
     * Gets the information about the main artifact to be downloaded for this library.
     *
     * @return The information about the main artifact to be downloaded for this library.
     *
     * @since 2024.1
     */
    public DownloadInfo getArtifact() {
      return artifact;
    }

    /**
     * Gets the classifiers for platform-specific artifacts.
     *
     * @return The classifiers for platform-specific artifacts.
     *
     * @since 2024.1
     */
    public Map<String, DownloadInfo> getClassifiers() {
      return classifiers;
    }
  }

  /**
   * Inner class representing a rule for a library, which defines conditions under which the library
   * should be used.
   *
   * @author Mica Technologies
   * @implNote This class contains portions which were adapted from previous versions of the
   *     Mica Minecraft Launcher. This class therefore predates version 2024.1, but versioning has
   *     been introduced or made consistent at version 2024.1.
   * @since 2024.1
   */
  public static class Rule {

    /**
     * The action to be taken ("allow" or "disallow") based on the OS conditions.
     *
     * @since 2024.1
     */
    @SerializedName("action")
    private String action;

    /**
     * Represents the operating system condition for this rule.
     *
     * @since 2024.1
     */
    @SerializedName("os")
    private Os os;

    /**
     * Gets the action to be taken ("allow" or "disallow") based on the OS conditions.
     *
     * @return The action to be taken ("allow" or "disallow") based on the OS conditions.
     *
     * @since 2024.1
     */
    public String getAction() {
      return action;
    }

    /**
     * Gets the operating system condition for this rule.
     *
     * @return The operating system condition for this rule.
     *
     * @since 2024.1
     */
    public Os getOs() {
      return os;
    }
  }

  /**
   * Inner class representing an operating system condition for a rule, which defines the name,
   * version, and architecture of the operating system.
   *
   * @author Mica Technologies
   * @implNote This class contains portions which were adapted from previous versions of the
   *     Mica Minecraft Launcher. This class therefore predates version 2024.1, but versioning has
   *     been introduced or made consistent at version 2024.1.
   * @since 2024.1
   */
  public static class Os {

    /**
     * The name of the operating system (e.g., "linux", "windows").
     *
     * @since 2024.1
     */
    @SerializedName("name")
    private String name;

    /**
     * The version of the operating system, if applicable.
     *
     * @since 2024.1
     */
    @SerializedName("version")
    private String version;

    /**
     * The architecture of the operating system (e.g., "x86").
     *
     * @since 2024.1
     */
    @SerializedName("arch")
    private String arch;

    /**
     * Gets the name of the operating system (e.g., "linux", "windows").
     *
     * @return The name of the operating system (e.g., "linux", "windows").
     *
     * @since 2024.1
     */
    public String getName() {
      return name;
    }

    /**
     * Gets the version of the operating system, if applicable.
     *
     * @return The version of the operating system, if applicable.
     *
     * @since 2024.1
     */
    public String getVersion() {
      return version;
    }

    /**
     * Gets the architecture of the operating system (e.g., "x86").
     *
     * @return The architecture of the operating system (e.g., "x86").
     *
     * @since 2024.1
     */
    public String getArch() {
      return arch;
    }
  }

  /**
   * Inner class representing the extraction information for a library, including the exclusions for
   * the extraction.
   *
   * @author Mica Technologies
   * @implNote This class contains portions which were adapted from previous versions of the
   *     Mica Minecraft Launcher. This class therefore predates version 2024.1, but versioning has
   *     been introduced or made consistent at version 2024.1.
   * @since 2024.1
   */
  public static class Extract {

    /**
     * List of patterns to exclude when extracting the library.
     *
     * @since 2024.1
     */
    @SerializedName("exclude")
    private List<String> exclude;

    /**
     * Gets the list of patterns to exclude when extracting the library.
     *
     * @return The list of patterns to exclude when extracting the library.
     *
     * @since 2024.1
     */
    public List<String> getExclude() {
      return exclude;
    }
  }

  /**
   * Inner class representing the logging information for a specific Minecraft version.
   *
   * @author Mica Technologies
   * @implNote This class contains portions which were adapted from previous versions of the
   *     Mica Minecraft Launcher. This class therefore predates version 2024.1, but versioning has
   *     been introduced or made consistent at version 2024.1.
   * @since 2024.1
   */
  public static class Logging {

    /**
     * The logging configuration information for the 'client' mode in the specific Minecraft
     * version.
     *
     * @since 2024.1
     */
    @SerializedName("client")
    private ClientLogging client;

    /**
     * Gets the logging configuration information for the 'client' mode in the specific Minecraft
     * version.
     *
     * @return The logging configuration information for the 'client' mode in the specific Minecraft
     *     version.
     *
     * @since 2024.1
     */
    public ClientLogging getClient() {
      return client;
    }
  }

  /**
   * Inner class representing the logging configuration information of a 'mode' in a specific
   * Minecraft version.
   *
   * @author Mica Technologies
   * @implNote This class contains portions which were adapted from previous versions of the
   *     Mica Minecraft Launcher. This class therefore predates version 2024.1, but versioning has
   *     been introduced or made consistent at version 2024.1.
   * @since 2024.1
   */
  public static class ClientLogging {

    /**
     * The JVM argument to use for the logging configuration of the specific 'mode' in the specific
     * Minecraft version.
     *
     * <p>Typically, this value is of the format: "-Dlog4j.configurationFile=${path}". This
     * specifies the argument necessary to activate the logging configuration file defined by the
     * {@link #file} field.</p>
     *
     * @since 2024.1
     */
    @SerializedName("argument")
    private String argument;

    /**
     * The download information for the logging configuration file of the specific 'mode' in the
     * specific Minecraft version.
     *
     * @see Download
     * @since 2024.1
     */
    @SerializedName("file")
    private DownloadInfo file;

    /**
     * The type of logging configuration of the specific 'mode' in the specific Minecraft version.
     *
     * <p>Typically, this value is "log4j2-xml".</p>
     *
     * @since 2024.1
     */
    @SerializedName("type")
    private String type;

    /**
     * Gets the JVM argument to use for the logging configuration of the specific 'mode' in the
     * specific Minecraft version.
     *
     * <p>Typically, this value is of the format: "-Dlog4j.configurationFile=${path}". This
     * specifies the argument necessary to activate the logging configuration file defined by the
     * {@link #file} field.</p>
     *
     * @return The JVM argument to use for the logging configuration of the specific 'mode' in the
     *     specific Minecraft version.
     *
     * @since 2024.1
     */
    public String getArgument() {
      return argument;
    }

    /**
     * Gets the download information for the logging configuration file of the specific 'mode' in
     * the specific Minecraft version.
     *
     * @return The download information for the logging configuration file of the specific 'mode' in
     *     the specific Minecraft version.
     *
     * @see Download
     * @since 2024.1
     */
    public DownloadInfo getFile() {
      return file;
    }

    /**
     * Gets the type of logging configuration of the specific 'mode' in the specific Minecraft
     * version.
     *
     * <p>Typically, this value is "log4j2-xml".</p>
     *
     * @return The type of logging configuration of the specific 'mode' in the specific Minecraft
     *     version.
     *
     * @since 2024.1
     */
    public String getType() {
      return type;
    }
  }
  // endregion
}