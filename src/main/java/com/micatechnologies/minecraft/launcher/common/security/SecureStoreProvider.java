package com.micatechnologies.minecraft.launcher.common.security;

import com.microsoft.credentialstorage.SecretStore;
import com.microsoft.credentialstorage.StorageProvider;
import com.microsoft.credentialstorage.StorageProvider.SecureOption;
import com.microsoft.credentialstorage.model.StoredToken;
import java.util.Optional;

/**
 * Provider for secure storage of sensitive data, such as authentication tokens.
 * <p>
 * The underlying implementation is platform-dependent and uses the operating system's secure
 * storage facilities, such as the Keychain on macOS, the Windows Credential Manager on Windows, and
 * the Secret Service API on Linux. If the operating system does not provide a secure storage
 * facility, an exception will be thrown, as secure storage cannot be guaranteed (not that it can be
 * 100% guaranteed anyway).
 *
 * @author Mica Technologies
 * @version 2024.1
 * @since 2024.1
 */
public class SecureStoreProvider {

  /**
   * Boolean indicating whether the secure store is persistent. If {@code true}, the secure store
   * will be persistent, meaning that it will survive reboots. If {@code false}, the secure store
   * will be non-persistent, meaning that it will be cleared when the application exits.
   *
   * @since 2024.1
   */
  private static final boolean SECURE_STORE_PERSISTENT = true;

  /**
   * Option indicating whether the secure store is required to be backed by an operating system
   * secure storage facility.
   * <p>
   * If set to {@link SecureOption#REQUIRED}, the secure store will be required to be backed by an
   * operating system secure storage facility. If {@link SecureOption#PREFERRED}, the secure store
   * will be backed by an operating system secure storage facility if possible, but will fall back
   * to a file-based implementation if not.
   */
  private static final SecureOption SECURE_STORE_OPTION = SecureOption.REQUIRED;

  /**
   * The secure store for authentication tokens.
   * <p>
   * This is a {@link SecretStore} of {@link StoredToken}s, and is initialized upon calling
   * {@link #init()}. It is persistent and backed by the operating system's secure storage facility,
   * defined by the {@link #SECURE_STORE_PERSISTENT} and {@link #SECURE_STORE_OPTION} parameter
   * constants.
   *
   * @since 2024.1
   */
  private static SecretStore<StoredToken> secureStoreTokens = null;

  /**
   * Initializes the secure store.
   * <p>
   * This method must be called before the secure store can be used.
   *
   * @return {@code true} if the initialization was successful, {@code false} otherwise.
   *
   * @since 2024.1
   */
  public static synchronized boolean init() {
    secureStoreTokens =
        StorageProvider.getTokenStorage(SECURE_STORE_PERSISTENT, SECURE_STORE_OPTION);
    return isInitialized();
  }

  /**
   * Checks if the secure store has been successfully initialized.
   *
   * @return {@code true} if the secure store is initialized, {@code false} otherwise.
   *
   * @since 2024.1
   */
  public static synchronized boolean isInitialized() {
    return secureStoreTokens != null;
  }

  /**
   * Saves an authentication token for a specific account identifier.
   *
   * @param accountId The unique identifier for the account.
   * @param token     The authentication token to store.
   *
   * @since 2024.1
   */
  public synchronized static void saveToken(String accountId, StoredToken token) {
    guardInit();
    secureStoreTokens.add(accountId, token);
  }

  /**
   * Checks if the secure store has been initialized. If not, an {@link IllegalStateException} is
   * thrown.
   *
   * @throws IllegalStateException If the secure store has not been initialized.
   * @since 2024.1
   */
  private synchronized static void guardInit() {
    if (secureStoreTokens == null) {
      throw new IllegalStateException("Secure store not initialized");
    }
  }

  /**
   * Retrieves an authentication token for a specific account identifier.
   *
   * @param accountId The unique identifier for the account.
   *
   * @return An {@link Optional} containing the authentication token if present, or empty if not.
   *
   * @since 2024.1
   */
  public synchronized static Optional<StoredToken> getToken(String accountId) {
    guardInit();
    return Optional.ofNullable(secureStoreTokens.get(accountId));
  }

  /**
   * Removes an authentication token for a specific account identifier.
   *
   * @param accountId The unique identifier for the account.
   *
   * @since 2024.1
   */
  public synchronized static void removeToken(String accountId) {
    guardInit();
    secureStoreTokens.delete(accountId);
  }
}
