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


package com.micatechnologies.minecraft.launcher.game.auth;

import com.micatechnologies.minecraft.launcher.utilities.MachineSecretCipher;

/**
 * Encrypts and decrypts the credential files an {@link AccountStore} keeps on disk.
 *
 * <p>Production uses {@link #machineBound()}, which delegates to {@link MachineSecretCipher}
 * so the per-account files stay byte-compatible with the files the launcher has always
 * written. The interface exists so {@link AccountStore} can be exercised against a temp
 * directory with a trivial cipher, without deriving the real machine key or touching the
 * user's config folder.</p>
 *
 * @since 2026.10
 */
interface AccountCipher
{
    /**
     * Encrypts the given bytes.
     *
     * @param plaintext the bytes to protect
     *
     * @return the encrypted envelope
     *
     * @throws Exception if encryption fails
     * @since 2026.10
     */
    byte[] encrypt( byte[] plaintext ) throws Exception;

    /**
     * Decrypts an envelope produced by {@link #encrypt(byte[])}.
     *
     * @param envelope the encrypted bytes
     *
     * @return the plaintext, or {@code null} when the envelope is too short to be valid
     *
     * @throws Exception when the envelope is tampered with or was bound to another machine
     * @since 2026.10
     */
    byte[] decrypt( byte[] envelope ) throws Exception;

    /**
     * The machine-bound AES-256-GCM cipher every credential file has always used.
     *
     * @return the production cipher
     *
     * @since 2026.10
     */
    static AccountCipher machineBound()
    {
        return new AccountCipher()
        {
            @Override
            public byte[] encrypt( byte[] plaintext ) throws Exception
            {
                return MachineSecretCipher.encryptBytes( plaintext );
            }

            @Override
            public byte[] decrypt( byte[] envelope ) throws Exception
            {
                return MachineSecretCipher.decryptBytes( envelope );
            }
        };
    }
}
