package com.micatechnologies.minecraft.launcher.common.game;

import com.google.gson.annotations.SerializedName;
import com.micatechnologies.minecraft.launcher.common.files.ManagedGameFile;
import com.microsoft.aad.msal4j.OSHelper;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.commons.lang3.SystemUtils;

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
public class MinecraftVersionManifest {

  /**
   * The asset index information of the specific version of Minecraft.
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
   * The assets version of the specific version of Minecraft.
   *
   * <p>The assets version is a string that is used to identify the version of the assets that are
   * used by the game.</p>
   *
   * @since 2024.1
   */
  @SerializedName("assets")
  private String assets;

  /**
   * The compliance level of the specific version of Minecraft.
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
   * The downloads information of the specific version of Minecraft.
   *
   * <p>The downloads information is a JSON object that contains the download information for the
   * client and server JAR files, as well as the mappings for the client and server JAR files.</p>
   *
   * @since 2024.1
   */
  @SerializedName("downloads")
  private Downloads downloads;

  /**
   * The ID of the specific version of Minecraft.
   *
   * @since 2024.1
   */
  @SerializedName("id")
  private String id;

  /**
   * The Java version information of the specific version of Minecraft.
   *
   * <p>The Java version information is a JSON object that contains the component and major
   * version of the Java version that is required to run the game.</p>
   *
   * @since 2024.1
   */
  @SerializedName("javaVersion")
  private JavaVersion javaVersion;
  private List<Library> libraries;
  private Logging logging;
  private String mainClass;
  private String minecraftArguments;
  private Arguments arguments;
  private int minimumLauncherVersion;
  private String releaseTime;
  private String time;
  private String type;


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

    private String id;
    private String sha1;
    private long size;
    private long totalSize;
    private String url;

    // Getters and Setters
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
    private Download client;

    /**
     * The download information object for the client mappings JAR file of the specific version of
     * Minecraft.
     *
     * @since 2024.1
     */
    @SerializedName("client_mappings")
    private Download clientMappings;

    /**
     * The download information object for the server JAR file of the specific version of
     * Minecraft.
     *
     * @since 2024.1
     */
    @SerializedName("server")
    private Download server;

    /**
     * The download information object for the server mappings JAR file of the specific version of
     * Minecraft.
     *
     * @since 2024.1
     */
    @SerializedName("server_mappings")
    private Download serverMappings;

    /**
     * Gets the download information object for the client JAR file of the specific version of
     * Minecraft.
     *
     * @return The download information object for the client JAR file of the specific version of
     *     Minecraft.
     *
     * @since 2024.1
     */
    public Download getClient() {
      return client;
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
    public Download getClientMappings() {
      return clientMappings;
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
    public Download getServer() {
      return server;
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
    public Download getServerMappings() {
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
  public static class Download {

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
    private long size;

    /**
     * The URL of the file download for the specific version of Minecraft.
     *
     * @since 2024.1
     */
    @SerializedName("url")
    private String url;

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
    public long getSize() {
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
    private Downloads downloads;

    /**
     * A Maven name for the library, in the format "groupId:artifactId:version".
     *
     * @since 2024.1
     */
    @SerializedName("name")
    private String name;

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
     * Rules defining conditions under which this library should be used.
     *
     * @since 2024.1
     */
    @SerializedName("rules")
    private List<Rule> rules;

    /**
     * Inner class representing the download information for a library.
     *
     * @author Mica Technologies
     * @implNote This class contains portions which were adapted from previous versions of the
     *     Mica Minecraft Launcher. This class therefore predates version 2024.1, but versioning has
     *     been introduced or made consistent at version 2024.1.
     * @since 2024.1
     */
    public static class Downloads {

      /**
       * Information about the main artifact to be downloaded for this library.
       *
       * @since 2024.1
       */
      @SerializedName("artifact")
      private Artifact artifact;

      /**
       * Classifiers for platform-specific artifacts.
       *
       * @since 2024.1
       */
      @SerializedName("classifiers")
      private Map<String, Artifact> classifiers;

      /**
       * Gets the information about the main artifact to be downloaded for this library.
       *
       * @return The information about the main artifact to be downloaded for this library.
       *
       * @since 2024.1
       */
      public Artifact getArtifact() {
        return artifact;
      }

      /**
       * Gets the classifiers for platform-specific artifacts.
       *
       * @return The classifiers for platform-specific artifacts.
       *
       * @since 2024.1
       */
      public Map<String, Artifact> getClassifiers() {
        return classifiers;
      }
    }

    /**
     * Inner class representing the artifact information for a library, including its path, SHA-1
     * hash, size, and URL.
     *
     * @author Mica Technologies
     * @implNote This class contains portions which were adapted from previous versions of the
     *     Mica Minecraft Launcher. This class therefore predates version 2024.1, but versioning has
     *     been introduced or made consistent at version 2024.1.
     * @since 2024.1
     */
    public static class Artifact {

      // TODO: Note to self, can this be combined with ManagedGameFile?

      /**
       * Path where the artifact is stored relative to the libraries directory.
       *
       * @since 2024.1
       */
      @SerializedName("path")
      private String path;

      /**
       * The SHA-1 hash of the artifact.
       *
       * @since 2024.1
       */
      @SerializedName("sha1")
      private String sha1;

      /**
       * The size of the artifact file.
       *
       * @since 2024.1
       */
      @SerializedName("size")
      private long size;

      /**
       * The URL from which the artifact can be downloaded.
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
       * Gets the SHA-1 hash of the artifact.
       *
       * @return The SHA-1 hash of the artifact.
       *
       * @since 2024.1
       */
      public String getSha1() {
        return sha1;
      }

      /**
       * Gets the size of the artifact file.
       *
       * @return The size of the artifact file.
       *
       * @since 2024.1
       */
      public long getSize() {
        return size;
      }

      /**
       * Gets the URL from which the artifact can be downloaded.
       *
       * @return The URL from which the artifact can be downloaded.
       *
       * @since 2024.1
       */
      public String getUrl() {
        return url;
      }
    }

    /**
     * Inner class representing the extraction information for a library, including the exclusions
     * for the extraction.
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
     * Inner class representing a rule for a library, which defines conditions under which the
     * library should be used.
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
      private OS os;

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
      public OS getOs() {
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
    public static class OS {

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

    // Getters and Setters for Library class

    /**
     * Gets the library's download information.
     *
     * @return The library's download information.
     *
     * @since 2024.1
     */
    public Downloads getDownloads() {
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
    private LoggingConfig client;

    /**
     * Gets the logging configuration information for the 'client' mode in the specific Minecraft
     * version.
     *
     * @return The logging configuration information for the 'client' mode in the specific Minecraft
     *     version.
     *
     * @since 2024.1
     */
    public LoggingConfig getClient() {
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
  public static class LoggingConfig {

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
    private Download file;

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
    public Download getFile() {
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
    private List<Object> jvm;

    // Getters and Setters

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

    public List<String> getEffectiveGameArguments() {
      // TODO: Process and only return applicable args
      return processArgumentList(game);
    }

    public List<String> getEffectiveJvmArguments() {
      // TODO: Process and only return applicable args
      OSHelper.isWindows();
      return processArgumentList(jvm);
    }
  }

  /**
   * Inner class representing an argument for a specific Minecraft version.
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
   * @see Rule
   * @see OS
   * @see <a href="https://minecraft.fandom.com/wiki/Arguments">Minecraft Wiki</a>
   * @since 2024.1
   */
  public static class Argument {

    /**
     * The rules defining conditions under which this argument should be used.
     *
     * @since 2024.1
     */
    @SerializedName("rules")
    private List<Rule> rules;

    /**
     * The value of the argument, which can be a string or a list of strings.
     *
     * @since 2024.1
     */
    @SerializedName("value")
    private Object value;

    // Getters and Setters

    /**
     * Gets the rules defining conditions under which this argument should be used.
     *
     * @return The rules defining conditions under which this argument should be used.
     *
     * @since 2024.1
     */
    public List<Rule> getRules() {
      return rules;
    }

    /**
     * Gets the value of the argument, which can be a string or a list of strings.
     *
     * @return The value of the argument, which can be a string or a list of strings.
     *
     * @since 2024.1
     */
    public Object getValue() {
      return value;
    }

    /**
     * Gets the value of the argument as a string, if possible. If the value is not a string, then
     * this method returns {@code null}.
     *
     * @return The value of the argument as a string, otherwise {@code null}.
     *
     * @since 2024.1
     */
    public String getValueAsString() {
      return value instanceof String ? (String) value : null;
    }

    /**
     * Gets the value of the argument as a list of strings, if possible. If the value is not a list
     * of strings, then this method returns {@code null}.
     *
     * @return The value of the argument as a list of strings, otherwise {@code null}.
     *
     * @since 2024.1
     */
    @SuppressWarnings("unchecked")
    public List<String> getValueAsList() {
      return value instanceof List ? (List<String>) value : null;
    }
  }


  public static class Rule {

    private String action;
    private Map<String, Boolean> features;
    private OS os;

    // Getters and Setters
  }

  public static class OS {

    private String name;
    private String version;
    private String arch;

    // Getters and Setters
  }

  // Getters and Setters for all fields in MinecraftVersion class


  public List<String> getProcessedArguments() {
    if (minecraftArguments != null && !minecraftArguments.isEmpty()) {
      // Process simple string arguments
      return processSimpleArguments(minecraftArguments);
    } else if (arguments != null) {
      // Process structured arguments
      return processStructuredArguments(arguments);
    }
    return new ArrayList<>();
  }

  private List<String> processSimpleArguments(String arguments) {
    // Split the arguments string into a list, respecting quotes
    // This is a simple implementation, consider a more robust method if needed
    return List.of(arguments.split(" "));
  }

  private List<String> processStructuredArguments(Arguments arguments) {
    List<String> processedArgs = new ArrayList<>();
    // Process game arguments
    processedArgs.addAll(processArgumentList(arguments.game));
    // Process JVM arguments
    processedArgs.addAll(processArgumentList(arguments.jvm));
    return processedArgs;
  }

  private List<String> processArgumentList(List<Object> argumentList) {
    if (argumentList == null) {
      return new ArrayList<>();
    }

    return argumentList.stream().flatMap(arg -> {
      if (arg instanceof String) {
        return List.of((String) arg).stream();
      } else if (arg instanceof Argument) {
        return processComplexArgument((Argument) arg).stream();
      }
      return new ArrayList<String>().stream();
    }).collect(Collectors.toList());
  }

  private List<String> processComplexArgument(Argument argument) {
    if (argument.rules == null || argument.rules.isEmpty() || argument.value == null) {
      return new ArrayList<>();
    }

    // Example rule processing, adapt based on actual rules logic
    for (Rule rule : argument.rules) {
      if (rule.action.equals("allow") && rule.os != null) {
        if ((rule.os.name.equals("windows") && SystemUtils.IS_OS_WINDOWS) || (
            rule.os.name.equals("osx")
                && SystemUtils.IS_OS_MAC) || (rule.os.name.equals("linux")
            && SystemUtils.IS_OS_LINUX)) {
          // This is just a basic example. Expand the logic to handle other rule conditions.
          return argument.value instanceof String
              ? List.of((String) argument.value)
              : (List<String>) argument.value;
        }
      }
    }
    return new ArrayList<>();
  }
}
