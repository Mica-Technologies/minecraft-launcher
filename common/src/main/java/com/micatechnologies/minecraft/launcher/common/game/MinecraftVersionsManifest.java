package com.micatechnologies.minecraft.launcher.common.game;

import com.google.gson.annotations.SerializedName;
import java.util.List;

/**
 * Class representing the Minecraft versions manifest in a GSON serializable format.
 *
 * <p>This format is described and linked at <a
 * href="https://minecraft.fandom.com/wiki/Version_manifest.json">
 * https://minecraft.fandom.com/wiki/Version_manifest.json</a></p>
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
public class MinecraftVersionsManifest {

  /**
   * The information object for the IDs of the latest versions of Minecraft, both release and
   * snapshot.
   *
   * @see Latest
   * @since 2024.1
   */
  @SerializedName("latest")
  private Latest latestVersions;

  /**
   * The list of all versions of Minecraft.
   *
   * @see Version
   * @since 2024.1
   */
  @SerializedName("versions")
  private List<Version> versions;

  /**
   * Inner class representing the latest versions of Minecraft, both release and snapshot.
   *
   * @author Mica Technologies
   * @implNote This class contains portions which were adapted from previous versions of the
   *     Mica Minecraft Launcher. This class therefore predates version 2024.1, but versioning has
   *     been introduced or made consistent at version 2024.1.
   * @since 2024.1
   */
  public static class Latest {

    /**
     * The ID of the latest release version of Minecraft.
     *
     * @see Version
     * @since 2024.1
     */
    @SerializedName("release")
    private String release;

    /**
     * The ID of the latest snapshot version of Minecraft.
     *
     * @see Version
     * @since 2024.1
     */
    @SerializedName("snapshot")
    private String snapshot;

    /**
     * Gets the ID of the latest release version of Minecraft.
     *
     * @return The ID of the latest release version of Minecraft.
     *
     * @since 2024.1
     */
    public String getRelease() {
      return release;
    }

    /**
     * Gets the ID of the latest snapshot version of Minecraft.
     *
     * @return The ID of the latest snapshot version of Minecraft.
     *
     * @since 2024.1
     */
    public String getSnapshot() {
      return snapshot;
    }
  }

  /**
   * Inner class representing a (one) version of Minecraft.
   *
   * @author Mica Technologies
   * @implNote This class contains portions which were adapted from previous versions of the
   *     Mica Minecraft Launcher. This class therefore predates version 2024.1, but versioning has
   *     been introduced or made consistent at version 2024.1.
   * @since 2024.1
   */
  public static class Version {

    /**
     * The ID of the version of Minecraft.
     *
     * <p>Typically this is something along the lines of {@code 1.17.1} or {@code 21w37a}.</p>
     *
     * <p>Release versions are typically in the form {@code <major>.<minor>.<patch>}, while
     * snapshot versions are typically in the form {@code <year>w<week><letter>}. Release versions
     * may not have a patch version, and snapshot versions may not have a letter.</p>
     *
     * @since 2024.1
     */
    @SerializedName("id")
    private String id;

    /**
     * The type of the version of Minecraft.
     *
     * <p>Typically, this is either {@code release} or {@code snapshot}.</p>
     *
     * @since 2024.1
     */
    @SerializedName("type")
    private String type;

    /**
     * The URL of the version-specific JSON manifest for the version of Minecraft.
     *
     * <p>This is typically derived from the {@link #sha1} and {@link #id} of the version.</p>
     *
     * @since 2024.1
     */
    @SerializedName("url")
    private String url;

    /**
     * The time at which the version of Minecraft was last updated.
     *
     * <p>This is typically in the form {@code yyyy-MM-dd'T'HH:mm:ssZ}. It is expected that this
     * will consistently follow the ISO 8601 standard, but this is not necessarily guaranteed.</p>
     *
     * @since 2024.1
     */
    @SerializedName("time")
    private String updateTime;

    /**
     * The time at which the version of Minecraft was released.
     *
     * <p>This is typically in the form {@code yyyy-MM-dd'T'HH:mm:ssZ}. It is expected that this
     * will consistently follow the ISO 8601 standard, but this is not necessarily guaranteed.</p>
     *
     * @since 2024.1
     */
    @SerializedName("releaseTime")
    private String releaseTime;

    /**
     * The SHA-1 hash of the version-specific JSON manifest for the version of Minecraft.
     *
     * <p>This is typically used to derive the {@link #url} of the version.</p>
     *
     * @since 2024.1
     */
    @SerializedName("sha1")
    private String sha1;

    /**
     * The compliance level of the version of Minecraft.
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
     * Gets the ID of the version of Minecraft.
     *
     * <p>Typically this is something along the lines of {@code 1.17.1} or {@code 21w37a}.</p>
     *
     * <p>Release versions are typically in the form {@code <major>.<minor>.<patch>}, while
     * snapshot versions are typically in the form {@code <year>w<week><letter>}. Release versions
     * may not have a patch version, and snapshot versions may not have a letter.</p>
     *
     * @return The ID of the version of Minecraft.
     *
     * @since 2024.1
     */
    public String getId() {
      return id;
    }

    /**
     * Gets the type of the version of Minecraft.
     *
     * <p>Typically, this is either {@code release} or {@code snapshot}.</p>
     *
     * @return The type of the version of Minecraft.
     *
     * @since 2024.1
     */
    public String getType() {
      return type;
    }

    /**
     * Gets the URL of the version-specific JSON manifest for the version of Minecraft.
     *
     * <p>This is typically derived from the {@link #sha1} and {@link #id} of the version.</p>
     *
     * @return The URL of the version-specific JSON manifest for the version of Minecraft.
     *
     * @since 2024.1
     */
    public String getUrl() {
      return url;
    }

    /**
     * Gets the time at which the version of Minecraft was last updated.
     *
     * <p>This is typically in the form {@code yyyy-MM-dd'T'HH:mm:ssZ}. It is expected that this
     * will consistently follow the ISO 8601 standard, but this is not necessarily guaranteed.</p>
     *
     * @return The time at which the version of Minecraft was last updated.
     *
     * @since 2024.1
     */
    public String getUpdateTime() {
      return updateTime;
    }

    /**
     * Gets the time at which the version of Minecraft was released.
     *
     * <p>This is typically in the form {@code yyyy-MM-dd'T'HH:mm:ssZ}. It is expected that this
     * will consistently follow the ISO 8601 standard, but this is not necessarily guaranteed.</p>
     *
     * @return The time at which the version of Minecraft was released.
     *
     * @since 2024.1
     */
    public String getReleaseTime() {
      return releaseTime;
    }

    /**
     * Gets the SHA-1 hash of the version-specific JSON manifest for the version of Minecraft.
     *
     * <p>This is typically used to derive the {@link #url} of the version.</p>
     *
     * @return The SHA-1 hash of the version-specific JSON manifest for the version of Minecraft.
     *
     * @since 2024.1
     */
    public String getSha1() {
      return sha1;
    }

    /**
     * Gets the compliance level of the version of Minecraft.
     *
     * <p>For older versions of the game, this is typically {@code 0}. Older versions of the game
     * do not support the latest player safety features, thus the compliance level value was
     * established as a way to indicate this in the version manifest and native launcher. Newer
     * versions of the game typically have {@code 1} for this field.</p>
     *
     * @return The compliance level of the version of Minecraft.
     *
     * @since 2024.1
     */
    public int getComplianceLevel() {
      return complianceLevel;
    }
  }

  /**
   * Gets the information object for the IDs of the latest versions of Minecraft, both release and
   * snapshot.
   *
   * @return The information object for the IDs of the latest versions of Minecraft, both release
   *     and snapshot.
   *
   * @see Latest
   * @since 2024.1
   */
  public Latest getLatestVersions() {
    return latestVersions;
  }

  /**
   * Gets the ID of the latest release version of Minecraft.
   *
   * @return The ID of the latest release version of Minecraft.
   *
   * @see Latest
   * @since 2024.1
   */
  public String getLatestVersionReleaseId() {
    return latestVersions.getRelease();
  }

  /**
   * Gets the ID of the latest snapshot version of Minecraft.
   *
   * @return The ID of the latest snapshot version of Minecraft.
   *
   * @see Latest
   * @since 2024.1
   */
  public String getLatestVersionSnapshotId() {
    return latestVersions.getSnapshot();
  }

  /**
   * Gets the list of all versions of Minecraft.
   *
   * @return The list of all versions of Minecraft.
   *
   * @see Version
   * @since 2024.1
   */
  public List<Version> getVersions() {
    return versions;
  }

  /**
   * Gets the version of Minecraft with the specified ID. If no version with the specified ID is
   * found, {@code null} is returned.
   *
   * @param id The ID of the version to get.
   *
   * @return The version of Minecraft with the specified ID, or {@code null} if no version with the
   *     specified ID is found.
   *
   * @since 2024.1
   */
  public Version getVersion(String id) {
    for (Version version : versions) {
      if (version.id.equals(id)) {
        return version;
      }
    }
    return null;
  }

  /**
   * Gets the latest release version of Minecraft.
   *
   * @return The latest release version of Minecraft.
   *
   * @see #getLatestVersionReleaseId()
   * @see #getVersion(String)
   * @see Version
   * @see Latest
   * @since 2024.1
   */
  public Version getLatestVersionRelease() {
    return getVersion(getLatestVersionReleaseId());
  }

  /**
   * Gets the latest snapshot version of Minecraft.
   *
   * @return The latest snapshot version of Minecraft.
   *
   * @see #getLatestVersionSnapshotId()
   * @see #getVersion(String)
   * @see Version
   * @see Latest
   * @since 2024.1
   */
  public Version getLatestVersionSnapshot() {
    return getVersion(getLatestVersionSnapshotId());
  }
}
