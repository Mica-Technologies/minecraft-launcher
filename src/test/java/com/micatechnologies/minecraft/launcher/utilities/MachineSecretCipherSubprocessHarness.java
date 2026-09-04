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

import java.security.SecureRandom;
import java.util.Base64;

/**
 * NOT a JUnit test — a small subprocess entry point used only by
 * {@link MachineSecretCipherSecurityTest}.
 *
 * <p>{@link MachineSecretCipher#encrypt} / {@code #decrypt} always derive
 * their AES-256 key through {@code deriveMachineKey}, which unconditionally
 * mixes in a per-install secret persisted at
 * {@code <launcher config folder>/machine-key.bin}. Under a unit-test JVM,
 * {@code GameModeManager} never has a game mode set, so
 * {@code LocalPathManager} resolves that config folder to
 * {@code <current working directory>/config} — the server-mode fallback.
 * Calling the cipher directly from the test process would therefore create
 * a stray {@code config/machine-key.bin} wherever Maven happens to be
 * invoked from, not inside a JUnit {@code @TempDir}.</p>
 *
 * <p>Running the real encrypt/decrypt calls in a short-lived child JVM
 * whose working directory is pinned to a {@code @TempDir} confines that
 * incidental disk write to the sandbox while still exercising the exact
 * production code path end to end. Communication back to the parent test
 * is a handful of {@code LABEL:value} lines on stdout — see the individual
 * modes below.</p>
 */
public final class MachineSecretCipherSubprocessHarness
{
    private MachineSecretCipherSubprocessHarness() { /* entry point only */ }

    /**
     * Dispatches to one of the harness modes based on {@code args[0]}.
     *
     * <ul>
     *   <li>{@code encrypt <plaintext>} — prints {@code ENVELOPE:<base64>} or
     *       {@code ENCRYPT_THROW:<ExceptionSimpleName>}.</li>
     *   <li>{@code decrypt <envelope>} — prints {@code RESULT:OK:<plaintext>},
     *       {@code RESULT:NULL}, or {@code RESULT:THROW:<ExceptionSimpleName>}.</li>
     *   <li>{@code fulltest <plaintext>} — encrypts, decrypts the untampered
     *       envelope as a baseline, flips one byte each at the start / middle /
     *       end of the envelope and attempts to decrypt each, then attempts to
     *       decrypt same-length random bytes. Prints one result line per step;
     *       see the individual print statements below for the exact labels.</li>
     * </ul>
     *
     * @param args harness mode followed by its argument(s)
     * @throws Exception never expected to escape — all cipher failures are
     *                    caught and reported as {@code *_THROW:} lines so the
     *                    parent test can assert on them
     */
    public static void main( String[] args ) throws Exception
    {
        String mode = args.length > 0 ? args[ 0 ] : "";
        switch ( mode )
        {
            case "encrypt" -> runEncryptOnly( args[ 1 ] );
            case "decrypt" -> printDecryptResult( "RESULT", args[ 1 ] );
            case "fulltest" -> runFullTest( args[ 1 ] );
            default -> System.out.println( "UNKNOWN_MODE:" + mode );
        }
    }

    private static void runEncryptOnly( String plaintext )
    {
        try {
            String envelope = MachineSecretCipher.encrypt( plaintext );
            System.out.println( "ENVELOPE:" + envelope );
        }
        catch ( Throwable t ) {
            System.out.println( "ENCRYPT_THROW:" + t.getClass().getSimpleName() );
        }
    }

    private static void printDecryptResult( String label, String envelope )
    {
        try {
            String plain = MachineSecretCipher.decrypt( envelope );
            if ( plain == null ) {
                System.out.println( label + ":NULL" );
            }
            else {
                System.out.println( label + ":OK:" + plain );
            }
        }
        catch ( Throwable t ) {
            System.out.println( label + ":THROW:" + t.getClass().getSimpleName() );
        }
    }

    private static void runFullTest( String plaintext ) throws Exception
    {
        String envelope = MachineSecretCipher.encrypt( plaintext );
        System.out.println( "ENCRYPT:" + ( envelope != null ? "OK" : "NULL" ) );
        if ( envelope == null ) {
            return;
        }

        printDecryptResult( "BASELINE", envelope );

        byte[] raw = Base64.getDecoder().decode( envelope );
        flipAndDecrypt( "TAMPER_START", raw, 0 );
        flipAndDecrypt( "TAMPER_MIDDLE", raw, raw.length / 2 );
        flipAndDecrypt( "TAMPER_END", raw, raw.length - 1 );

        // Random bytes the same length as a real envelope — long enough to
        // pass the "could be a real envelope" size gate, but not produced by
        // encrypt() at all. Must fail, not silently decrypt.
        byte[] garbage = new byte[ raw.length ];
        new SecureRandom().nextBytes( garbage );
        printDecryptResult( "GARBAGE", Base64.getEncoder().encodeToString( garbage ) );
    }

    private static void flipAndDecrypt( String label, byte[] original, int index )
    {
        byte[] tampered = original.clone();
        tampered[ index ] ^= (byte) 0xFF;
        printDecryptResult( label, Base64.getEncoder().encodeToString( tampered ) );
    }
}
