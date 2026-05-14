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
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

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
 *       OS name, oshi hardware UUID (motherboard / BIOS), first available
 *       MAC address.</li>
 *   <li>When hardware UUID or MAC lookup fails (cloud VMs, restricted
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

        SecretKey key = deriveMachineKey( salt );
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

        SecretKey key = deriveMachineKey( salt );
        Cipher cipher = Cipher.getInstance( "AES/GCM/NoPadding" );
        cipher.init( Cipher.DECRYPT_MODE, key, new GCMParameterSpec( 128, iv ) );
        return cipher.doFinal( ciphertext );
    }

    // ===== internals =====

    /**
     * Derives an AES-256 key from a hardware + OS fingerprint via PBKDF2.
     * Salt comes from the caller (so each encryption gets a fresh key).
     * Hardware UUID + MAC fall back to a per-install random secret rather
     * than constant strings when detection fails — closes the "every cloud
     * VM derives the same key" hole.
     */
    private static SecretKey deriveMachineKey( byte[] salt ) throws Exception
    {
        StringBuilder fingerprint = new StringBuilder();
        fingerprint.append( resolveOsUsername() ).append( '|' );
        fingerprint.append( System.getProperty( "os.name", "" ) ).append( '|' );

        // Hardware UUID via oshi. Fallback: per-install random secret.
        try {
            fingerprint.append( new SystemInfo().getHardware().getComputerSystem().getHardwareUUID() );
        }
        catch ( Exception e ) {
            fingerprint.append( "install:" ).append( getOrCreateInstallSecret() );
        }
        fingerprint.append( '|' );

        // Stable MAC address. macOS enumeration order is not deterministic —
        // awdl0 (AirDrop), llw0 (low-latency WLAN), and similar interfaces
        // carry randomized, locally-administered MACs that rotate over time,
        // and any of them can come back first from getNetworkInterfaces(). If
        // we picked one of those we'd derive a different key on the next
        // launch and every cached file would fail with AEADBadTagException.
        // Filter to universally-administered (real OUI) MACs, skip loopback /
        // VPN tunnels, then sort so the choice is stable regardless of OS
        // enumeration order. Same fallback if nothing usable is found.
        boolean macFound = false;
        try {
            List< byte[] > rawMacs = new ArrayList<>();
            Enumeration< NetworkInterface > nets = NetworkInterface.getNetworkInterfaces();
            while ( nets.hasMoreElements() ) {
                NetworkInterface ni = nets.nextElement();
                try {
                    if ( ni.isLoopback() || ni.isPointToPoint() ) {
                        continue;
                    }
                }
                catch ( SocketException ignored ) {
                    continue;
                }
                byte[] mac = ni.getHardwareAddress();
                if ( mac != null ) {
                    rawMacs.add( mac );
                }
            }
            List< String > stable = filterAndSortStableMacs( rawMacs );
            if ( !stable.isEmpty() ) {
                fingerprint.append( stable.get( 0 ) );
                macFound = true;
            }
        }
        catch ( Exception ignored ) {
            // Fall through to install-secret fallback.
        }
        if ( !macFound ) {
            fingerprint.append( "install:" ).append( getOrCreateInstallSecret() );
        }

        KeySpec spec = new PBEKeySpec(
                fingerprint.toString().toCharArray(), salt,
                PBKDF2_ITERATIONS, PBKDF2_KEY_LENGTH_BITS );
        SecretKeyFactory factory = SecretKeyFactory.getInstance( "PBKDF2WithHmacSHA256" );
        byte[] keyBytes = factory.generateSecret( spec ).getEncoded();
        return new SecretKeySpec( keyBytes, "AES" );
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
                Logger.logWarningSilent( "Unable to persist install secret ("
                                                 + e.getClass().getSimpleName()
                                                 + "); using ephemeral fallback." );
                byte[] fresh = new byte[ INSTALL_SECRET_BYTES ];
                new SecureRandom().nextBytes( fresh );
                cachedInstallSecret = bytesToHex( fresh );
                return cachedInstallSecret;
            }
        }
    }

    /**
     * Filters {@code rawMacs} down to addresses that look stable across
     * reboots and over time, returning a sorted hex-encoded list. Used by
     * {@link #deriveMachineKey} to anchor the fingerprint to a hardware
     * address that won't rotate out from under us.
     *
     * <p>Rejected:</p>
     * <ul>
     *   <li>MACs shorter than 6 bytes (malformed, point-to-point pseudo-MACs).</li>
     *   <li>Locally-administered MACs — IEEE 802 first-octet bit {@code 0x02}.
     *       This is the bit that's set on Apple's awdl0 / llw0 randomized
     *       interfaces, on most VM virtual NICs, on Android's privacy-randomized
     *       Wi-Fi MAC, and on macOS's bridge / utun bridges. Filtering it out
     *       is what makes the fingerprint stable across launches.</li>
     *   <li>Multicast MACs — first-octet bit {@code 0x01}. Never valid as a
     *       hardware address; would only show up as garbage if an interface
     *       lied about its hwaddr.</li>
     *   <li>All-zero MACs — the JDK returns these for some loopback / virtual
     *       interfaces on certain platforms.</li>
     * </ul>
     *
     * <p>Sorted output guarantees that {@code stable.get(0)} is the same MAC
     * regardless of the order the OS happened to enumerate interfaces in.
     * Package-private so the filter can be unit-tested in isolation without
     * touching {@code NetworkInterface}.</p>
     */
    static List< String > filterAndSortStableMacs( List< byte[] > rawMacs ) {
        List< String > out = new ArrayList<>();
        if ( rawMacs == null ) {
            return out;
        }
        for ( byte[] mac : rawMacs ) {
            if ( mac == null || mac.length < 6 ) continue;
            if ( ( mac[ 0 ] & 0x02 ) != 0 ) continue;
            if ( ( mac[ 0 ] & 0x01 ) != 0 ) continue;
            boolean allZero = true;
            for ( byte b : mac ) {
                if ( b != 0 ) { allZero = false; break; }
            }
            if ( allZero ) continue;
            StringBuilder hex = new StringBuilder( mac.length * 2 );
            for ( byte b : mac ) hex.append( String.format( "%02x", b ) );
            out.add( hex.toString() );
        }
        Collections.sort( out );
        return out;
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
