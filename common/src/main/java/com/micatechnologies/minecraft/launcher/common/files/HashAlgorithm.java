package com.micatechnologies.minecraft.launcher.common.files;

/**
 * Enumeration of the supported hash algorithms. This is used to determine which algorithm to use
 * when hashing a file.
 *
 * @author Mica Technologies
 * @version 2024.1
 * @since 2024.1
 */
public enum HashAlgorithm {

  /**
   * The SHA-1 hash algorithm.
   *
   * @since 2024.1
   */
  SHA_1("SHA-1"),

  /**
   * The SHA-256 hash algorithm.
   *
   * @since 2024.1
   */
  SHA_256("SHA-256"),

  /**
   * The MD5 hash algorithm.
   *
   * @since 2024.1
   */
  MD5("MD5");

  /**
   * The algorithm name.
   *
   * @since 2024.1
   */
  private final String algorithm;

  /**
   * Private constructor for creating a new hash algorithm.
   *
   * @param algorithm The algorithm name.
   *
   * @since 2024.1
   */
  HashAlgorithm(String algorithm) {
    this.algorithm = algorithm;
  }

  /**
   * Gets the algorithm name.
   *
   * @return The algorithm name.
   *
   * @since 2024.1
   */
  public String getAlgorithm() {
    return algorithm;
  }
}
