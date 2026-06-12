/*
 * Copyright (c) 2026 Mica Technologies
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License,
 * or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.micatechnologies.minecraft.launcher.utilities;

import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
import com.micatechnologies.minecraft.launcher.files.LocalPathManager;
import com.micatechnologies.minecraft.launcher.files.Logger;
import oshi.SystemInfo;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Base64;

/**
 * AES-256-GCM encryption + decryption tied to the local machine. Same
 * machine-bound primitive the {@code MCLauncherAuthManager} uses to protect
 * the cached Minecraft authentication tokens, lifted into a reusable
 * utility so the same guarantee can cover the CurseForge API key + any
 * future user-supplied secrets the launcher needs to keep at rest.
 *
 * <h3>Security model</h3>
 *
 * <ul>
 *   <li>Key is derived per-encryption via PBKDF2 (HMAC-SHA-256, 65536
 *       iterations, 256-bit output) using a machine fingerprint as the
 *       passphrase and a fresh random 16-byte salt.</li>
 *   <li>Fingerprint sources, in order: OS username (env-resistant — reads
 *       {@code $USER} / {@code $USERNAME} before {@code System.getProperty}),
 *       OS name, and the oshi hardware UUID (motherboard / BIOS). The UUID
 *       binds the key to the physical machine and is stable across launches —
 *       only a genuine hardware change rotates it. (A network MAC address was
 *       previously mixed in too, but modern macOS randomizes every interface's
 *       MAC, so it rotated the key intermittently and broke decryption — see
 *       {@link #deriveMachineKey}.)</li>
 *   <li>When the hardware UUID lookup fails (cloud VMs, restricted
 *       containers, sandboxed processes), a per-install random secret
 *       lazily generated at {@code <config>/machine-key.bin} substitutes.
 *       Same path the auth manager uses, so the two share a single secret
 *       file rather than each maintaining its own.</li>
 *   <li>Encryption uses AES-256-GCM with a fresh 12-byte IV per call.
 *       Output layout is {@code salt[16] | iv[12] | ciphertext+tag}. The
 *       128-bit GCM auth tag detects tampering / wrong-machine.</li>
 * </ul>
 *
 * <p>The encrypted blob is unreadable on any machine other than the one
 * that wrote it — a stolen disk image or a misconfigured cloud sync
 * doesn't trivially leak the underlying secret. Comes with the obvious
 * trade-off that hardware changes (motherboard swap, NIC replacement)
 * invalidate the cache and force the user to re-enter the secret. For
 * the auth token that means re-logging in; for a CF API key it means
 * pasting the key back into Settings.</p>
 *
 * @since 2026.3
 */
public final class MachineSecretCipher
{
    private MachineSecretCipher() { /* static-only */ }

    /** Filename of the per-install random fallback secret. Lives alongside the
     *  launcher's other config files so file ownership / backup behavior is
     *  consistent with the auth cache. Created lazily — installs on hardware
     *  where oshi + NIC enumeration both work never touch this file. */
    private static final String INSTALL_SECRET_FILE = "machine-key.bin";

    /** Length of the per-install secret in bytes. 32 = 256 bits, well above
     *  any reasonable guessability threshold. */
    private static final int INSTALL_SECRET_BYTES = 32;

    /** PBKDF2 iteration count. Same value as the auth manager used pre-extraction
     *  so existing encrypted-at-rest auth files keep decrypting after refactor. */
    private static final int PBKDF2_ITERATIONS = 65536;

    /** Output key length in bits. AES-256. */
    private static final int PBKDF2_KEY_LENGTH_BITS = 256;

    /** Combined-output header length: 16-byte salt + 12-byte IV + 16-byte GCM tag
     *  is the minimum size for a valid encrypted blob (28 bytes of metadata + the
     *  16-byte tag baked into the GCM ciphertext). */
    private static final int MIN_COMBINED_BYTES = 28;

    /** Cached per-install secret. Volatile so the double-checked-locking pattern
     *  in {@link #getOrCreateInstallSecret} is correct. */
    private static volatile String cachedInstallSecret = null;

    // ===== public API =====

    /**
     * Encrypts the given plaintext with a machine-bound AES-256-GCM key and
     * returns a Base64 envelope suitable for stashing in JSON config / a
     * shell variable / etc. Throws on cipher / key-derivation failure —
     * unexpected enough that callers should let the exception propagate
     * rather than retry.
     */
    public static String encrypt( String plaintext ) throws Exception
    {
        if ( plaintext == null ) return null;
        return Base64.getEncoder().encodeToString(
                encryptBytes( plaintext.getBytes( StandardCharsets.UTF_8 ) ) );
    }

    /**
     * Decrypts a Base64 envelope produced by {@link #encrypt}. Returns
     * {@code null} when the envelope is too short to be valid; throws when
     * the GCM auth tag mismatches (wrong machine, tampered data) so callers
     * can distinguish "missing" from "corrupted / wrong host."
     */
    public static String decrypt( String encoded ) throws Exception
    {
        if ( encoded == null || encoded.isBlank() ) return null;
        byte[] plain = decryptBytes( Base64.getDecoder().decode( encoded ) );
        return plain == null ? null : new String( plain, StandardCharsets.UTF_8 );
    }

    /**
     * Raw-bytes variant of {@link #encrypt}. Output layout:
     * {@code salt[16] | iv[12] | ciphertext+tag}.
     */
    public static byte[] encryptBytes( byte[] plaintext ) throws Exception
    {
        if ( plaintext == null ) return null;
        SecureRandom random = new SecureRandom();
        byte[] salt = new byte[ 16 ];
        random.nextBytes( salt );
        byte[] iv = new byte[ 12 ];
        random.nextBytes( iv );

        SecretKey key = deriveMachineKey( salt, false );
        Cipher cipher = Cipher.getInstance( "AES/GCM/NoPadding" );
        cipher.init( Cipher.ENCRYPT_MODE, key, new GCMParameterSpec( 128, iv ) );
        byte[] ciphertext = cipher.doFinal( plaintext );

        byte[] combined = new byte[ salt.length + iv.length + ciphertext.length ];
        System.arraycopy( salt, 0, combined, 0, salt.length );
        System.arraycopy( iv, 0, combined, salt.length, iv.length );
        System.arraycopy( ciphertext, 0, combined, salt.length + iv.length, ciphertext.length );
        return combined;
    }

    /**
     * Raw-bytes variant of {@link #decrypt}. Returns {@code null} for
     * envelopes too short to contain salt+iv+tag; throws on GCM
     * authentication failure.
     */
    public static byte[] decryptBytes( byte[] combined ) throws Exception
    {
        if ( combined == null || combined.length < MIN_COMBINED_BYTES ) return null;
        byte[] salt = new byte[ 16 ];
        byte[] iv = new byte[ 12 ];
        byte[] ciphertext = new byte[ combined.length - 28 ];
        System.arraycopy( combined, 0, salt, 0, 16 );
        System.arraycopy( combined, 16, iv, 0, 12 );
        System.arraycopy( combined, 28, ciphertext, 0, ciphertext.length );

        try {
            // Current fingerprint always mixes in the per-install random secret.
            return doDecrypt( salt, iv, ciphertext, false );
        }
        catch ( javax.crypto.AEADBadTagException newFingerprintFailed ) {
            // The blob may predate the always-mix-install-secret change (its key
            // was derived from the legacy UUID-only fingerprint). Retry with the
            // legacy fingerprint so existing auth tokens / CF keys keep
            // decrypting. A successful legacy decrypt is transparently upgraded:
            // the next time the caller re-encrypts the value (token renewal,
            // settings re-save) it is written under the new fingerprint. If the
            // legacy attempt also fails, this is a genuine wrong-machine /
            // tampered blob — surface the primary failure.
            try {
                return doDecrypt( salt, iv, ciphertext, true );
            }
            catch ( javax.crypto.AEADBadTagException legacyAlsoFailed ) {
                throw newFingerprintFailed;
            }
        }
    }

    /** Single decrypt attempt against the machine key for the given fingerprint
     *  mode ({@code legacy} = pre-2026.6 UUID-only). Separated so
     *  {@link #decryptBytes} can try the current fingerprint then fall back to
     *  the legacy one for transparent migration. */
    private static byte[] doDecrypt( byte[] salt, byte[] iv, byte[] ciphertext, boolean legacy )
            throws Exception
    {
        SecretKey key = deriveMachineKey( salt, legacy );
        Cipher cipher = Cipher.getInstance( "AES/GCM/NoPadding" );
        cipher.init( Cipher.DECRYPT_MODE, key, new GCMParameterSpec( 128, iv ) );
        return cipher.doFinal( ciphertext );
    }

    // ===== internals =====

    /**
     * Derives an AES-256 key from a hardware + OS + per-install-secret
     * fingerprint via PBKDF2. Salt comes from the caller (so each encryption
     * gets a fresh key).
     *
     * <p>The per-install random secret ({@code machine-key.bin}, owner-only) is
     * <b>always</b> mixed in (mode {@code legacy == false}). Without it the key
     * derived purely from {@code username | os.name | hardwareUUID} — all values
     * any local process can read — so a sibling user could reconstruct the key
     * and decrypt the tokens whenever the best-effort owner-only ACL on the
     * config files failed (FAT32/exFAT data partitions, network homes, some WSL
     * mounts). Mixing the secret in means an attacker also needs to read
     * {@code machine-key.bin}, which carries the same owner-only protection.</p>
     *
     * <p>{@code legacy == true} reproduces the pre-2026.6 fingerprint (hardware
     * UUID when present, else the install secret — never both) so blobs written
     * before this change still decrypt via the fallback in
     * {@link #decryptBytes}.</p>
     *
     * <p>Note: a network MAC address was previously mixed in too, but modern
     * macOS randomizes every interface's MAC, which rotated the key
     * intermittently and broke decryption ({@code AEADBadTagException} on ~90% of
     * launches). The hardware UUID already provides machine binding, so the
     * volatile MAC is gone.</p>
     */
    private static SecretKey deriveMachineKey( byte[] salt, boolean legacy ) throws Exception
    {
        String user = resolveOsUsername();
        String osName = System.getProperty( "os.name", "" );
        String hwUuid = resolveHardwareUuid();
        // Legacy only consulted the install secret when the UUID was missing;
        // the current fingerprint always needs it. Avoid creating the secret
        // file in the legacy-with-UUID case so the legacy fingerprint is a
        // byte-for-byte match of what old blobs were encrypted under.
        String installSecret = ( legacy && !hwUuid.isBlank() ) ? "" : getOrCreateInstallSecret();

        String fingerprint = assembleFingerprint( user, osName, hwUuid, installSecret, legacy );

        KeySpec spec = new PBEKeySpec(
                fingerprint.toCharArray(), salt,
                PBKDF2_ITERATIONS, PBKDF2_KEY_LENGTH_BITS );
        SecretKeyFactory factory = SecretKeyFactory.getInstance( "PBKDF2WithHmacSHA256" );
        byte[] keyBytes = factory.generateSecret( spec ).getEncoded();
        return new SecretKeySpec( keyBytes, "AES" );
    }

    /**
     * Assembles the PBKDF2 passphrase from its components. Pure +
     * package-private so the format — and crucially the legacy-vs-current
     * compatibility the decrypt fallback depends on — can be unit-tested
     * without filesystem / hardware coupling.
     *
     * @param legacy when true, reproduce the pre-2026.6 format exactly
     *               ({@code user|os|uuid} or, with no uuid, {@code user|os|install:secret});
     *               when false, always append the install secret
     *               ({@code user|os|uuid|install:secret}).
     */
    static String assembleFingerprint( String user, String osName, String hwUuid,
                                       String installSecret, boolean legacy )
    {
        StringBuilder fingerprint = new StringBuilder();
        fingerprint.append( user == null ? "" : user ).append( '|' );
        fingerprint.append( osName == null ? "" : osName ).append( '|' );
        String uuid = hwUuid == null ? "" : hwUuid;
        if ( legacy ) {
            if ( uuid.isBlank() ) {
                fingerprint.append( "install:" ).append( installSecret );
            }
            else {
                fingerprint.append( uuid );
            }
        }
        else {
            fingerprint.append( uuid ).append( '|' );
            fingerprint.append( "install:" ).append( installSecret );
        }
        return fingerprint.toString();
    }

    /** Hardware UUID via oshi (motherboard / BIOS), or {@code ""} when the query
     *  is unavailable (cloud VMs, restricted containers, sandboxed processes) —
     *  in which case the install secret alone carries the machine binding. */
    private static String resolveHardwareUuid()
    {
        try {
            String hwUuid = new SystemInfo().getHardware().getComputerSystem().getHardwareUUID();
            return ( hwUuid == null || hwUuid.isBlank() ) ? "" : hwUuid;
        }
        catch ( Exception e ) {
            return "";
        }
    }

    /**
     * Returns the hex-encoded per-install secret, lazily creating + persisting
     * it on first use. Owner-only file permissions applied so other local
     * users can't read it. Failure to persist falls back to an ephemeral
     * in-memory secret — every encrypted blob from that process is unreadable
     * after restart, which is no worse than losing the data outright.
     */
    private static String getOrCreateInstallSecret()
    {
        String cached = cachedInstallSecret;
        if ( cached != null ) return cached;
        synchronized ( MachineSecretCipher.class ) {
            if ( cachedInstallSecret != null ) return cachedInstallSecret;
            Path secretPath = Path.of(
                    LocalPathManager.getLauncherConfigFolderPath(), INSTALL_SECRET_FILE );
            try {
                if ( Files.isRegularFile( secretPath ) ) {
                    byte[] existing = Files.readAllBytes( secretPath );
                    if ( existing.length >= INSTALL_SECRET_BYTES ) {
                        cachedInstallSecret = bytesToHex( existing );
                        return cachedInstallSecret;
                    }
                    // Corrupt / truncated — regenerate below.
                }
                byte[] fresh = new byte[ INSTALL_SECRET_BYTES ];
                new SecureRandom().nextBytes( fresh );
                Files.createDirectories( secretPath.getParent() );
                Files.write( secretPath, fresh );
                FilePermissions.applyOwnerOnly( secretPath );
                cachedInstallSecret = bytesToHex( fresh );
                return cachedInstallSecret;
            }
            catch ( IOException e ) {
                Logger.logWarningSilent( LocalizationManager.format( "log.machineCipher.persistSecretFailed",
                                                 e.getClass().getSimpleName() ) );
                byte[] fresh = new byte[ INSTALL_SECRET_BYTES ];
                new SecureRandom().nextBytes( fresh );
                cachedInstallSecret = bytesToHex( fresh );
                return cachedInstallSecret;
            }
        }
    }

    /** OS username from env first, system property as fallback. {@code USER}
     *  is the POSIX shell convention, {@code USERNAME} is the Windows
     *  convention, {@code user.name} system property is the last resort
     *  (some sandboxed JVMs clear env but still populate the property). */
    private static String resolveOsUsername()
    {
        String user = System.getenv( "USER" );
        if ( user != null && !user.isEmpty() ) return user;
        user = System.getenv( "USERNAME" );
        if ( user != null && !user.isEmpty() ) return user;
        return System.getProperty( "user.name", "" );
    }

    private static String bytesToHex( byte[] bytes )
    {
        StringBuilder sb = new StringBuilder( bytes.length * 2 );
        for ( byte b : bytes ) sb.append( String.format( "%02x", b ) );
        return sb.toString();
    }
}
