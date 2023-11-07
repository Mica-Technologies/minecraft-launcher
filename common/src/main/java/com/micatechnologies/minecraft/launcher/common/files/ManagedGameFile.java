package com.micatechnologies.minecraft.launcher.common.files;

import java.nio.file.InvalidPathException;
import java.nio.file.Paths;

/**
 * Class representing a managed game file. A managed game file is a file that is managed by the
 * launcher and is typically defined in a Minecraft manifest file, or a mod pack manifest file.
 *
 * @author Mica Technologies
 * @version 2024.1
 * @implNote This class contains portions which were adapted from previous versions of the Mica
 *     Minecraft Launcher. This class therefore predates version 2024.1, but versioning has been
 *     introduced or made consistent at version 2024.1.
 * @since 2024.1
 */
public class ManagedGameFile {

  /**
   * The name of the file (local file name).
   *
   * @since 2024.1
   */
  private final String name;

  /**
   * The URL of the file (remote file name).
   *
   * @since 2024.1
   */
  private final String url;

  /**
   * The hash of the file.
   *
   * @since 2024.1
   */
  private final String hash;

  /**
   * The hash algorithm used to generate the hash of the file, stored in the {@link #hash} field.
   *
   * @since 2024.1
   */
  private final HashAlgorithm hashAlgorithm;

  /**
   * The size of the file in bytes.
   *
   * @since 2024.1
   */
  private final long size;

  /**
   * Constructs a new managed game file with the specified name, URL, size, hash, and hash
   * algorithm.
   *
   * @param name          The name of the file (local file name).
   * @param url           The URL of the file (remote file name).
   * @param size          The size of the file in bytes.
   * @param hash          The hash of the file.
   * @param hashAlgorithm The hash algorithm used to generate the hash of the file, stored in the
   *                      {@link #hash} field.
   *
   * @since 2024.1
   */
  public ManagedGameFile(String name, String url,
      long size, String hash, HashAlgorithm hashAlgorithm) {
    this.name = name;
    this.url = url;
    this.hash = hash;
    this.hashAlgorithm = hashAlgorithm;
    this.size = size;
  }

  /**
   * Constructs a new managed game file with the specified name, URL, size, and SHA-1 hash.
   *
   * @param name     The name of the file (local file name).
   * @param url      The URL of the file (remote file name).
   * @param size     The size of the file in bytes.
   * @param sha1Hash The SHA-1 hash of the file.
   *
   * @apiNote This constructor is provided for convenience. It is equivalent to calling
   *     {@link #ManagedGameFile(String, String, long, String, HashAlgorithm)} with the
   *     {@link HashAlgorithm#SHA_1} hash algorithm.
   * @since 2024.1
   */
  public ManagedGameFile(String name, String url, long size, String sha1Hash) {
    this(name, url, size, sha1Hash, HashAlgorithm.SHA_1);
  }

  /**
   * Constructs a new managed game file with the specified URL, size, and SHA-1 hash. The file name
   * is derived from the specified URL.
   *
   * @param url      The URL of the file (remote file name).
   * @param size     The size of the file in bytes.
   * @param sha1Hash The SHA-1 hash of the file.
   *
   * @apiNote This constructor is provided for convenience. It is equivalent to calling
   *     {@link #ManagedGameFile(String, String, long, String, HashAlgorithm)} with the
   *     {@link HashAlgorithm#SHA_1} hash algorithm and the file name derived from the specified
   *     URL.
   * @since 2024.1
   */
  public ManagedGameFile(String url, long size, String sha1Hash) {
    this(deriveFileNameFromURL(url), url, size, sha1Hash);
  }

  /**
   * Constructs a new managed game file with the specified URL, size, hash, and hash algorithm. The
   * file name is derived from the specified URL.
   *
   * @param url           The URL of the file (remote file name).
   * @param size          The size of the file in bytes.
   * @param hash          The hash of the file.
   * @param hashAlgorithm The hash algorithm used to generate the hash of the file, stored in the
   *                      {@link #hash} field.
   *
   * @apiNote This constructor is provided for convenience. It is equivalent to calling
   *     {@link #ManagedGameFile(String, String, long, String, HashAlgorithm)} with the file name
   *     derived from the specified URL.
   * @since 2024.1
   */
  public ManagedGameFile(String url, long size, String hash, HashAlgorithm hashAlgorithm) {
    this(deriveFileNameFromURL(url), url, size, hash, hashAlgorithm);
  }

  /**
   * Gets the name of the file (local file name).
   *
   * @return The name of the file (local file name).
   *
   * @since 2024.1
   */
  public String getName() {
    return name;
  }

  /**
   * Gets the URL of the file (remote file name).
   *
   * @return The URL of the file (remote file name).
   *
   * @since 2024.1
   */
  public String getUrl() {
    return url;
  }

  /**
   * Gets the hash of the file.
   *
   * @return The hash of the file.
   *
   * @see #getHashAlgorithm()
   * @since 2024.1
   */
  public String getHash() {
    return hash;
  }

  /**
   * Gets the hash algorithm used to generate the hash of the file, which is accessible via the
   * {@link #getHash()} method.
   *
   * @return The hash algorithm used to generate the hash of the file, which is accessible via the
   *     {@link #getHash()} method.
   *
   * @see #getHash()
   * @since 2024.1
   */
  public HashAlgorithm getHashAlgorithm() {
    return hashAlgorithm;
  }

  /**
   * Gets the size of the file in bytes.
   *
   * @return The size of the file in bytes.
   *
   * @since 2024.1
   */
  public long getSize() {
    return size;
  }

  /**
   * Gets the string representation of this managed game file. The string representation is
   * formatted as follows:
   * <pre>
   *   ManagedGameFile [name={@link #getName()}, url={@link #getUrl()}, hash={@link #getHash()},
   *    hashAlgorithm={@link #getHashAlgorithm()}, size={@link #getSize()}]
   * </pre>
   *
   * @since 2024.1
   */
  @Override
  public String toString() {
    return "ManagedGameFile [name=" + name + ", url=" + url + ", hash=" + hash + ", hashAlgorithm="
        + hashAlgorithm + ", size=" + size + "]";
  }

  /**
   * Derives/extrapolates the file name from the specified URL by converting the URL into a path
   * object and then extracting the file name from the path object.
   *
   * @param url The URL to derive the file name from.
   *
   * @return The derived file name.
   *
   * @throws InvalidPathException If the specified URL is not a valid path or cannot be converted
   *                              into a path object.
   * @since 2024.1
   */
  public static String deriveFileNameFromURL(String url) {
    return Paths.get(url).getFileName().toString();
  }
}
