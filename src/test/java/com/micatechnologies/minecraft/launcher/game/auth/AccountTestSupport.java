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

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Test doubles shared by the account tests: a cipher that needs no machine key, and a
 * cipher that plays "another machine" by refusing everything.
 */
final class AccountTestSupport
{
    private AccountTestSupport() { }

    /** Prefix the fake cipher adds, so tests can tell encrypted bytes from plaintext. */
    static final byte[] FAKE_PREFIX = "FAKE:".getBytes( StandardCharsets.US_ASCII );

    /** Reversible, deterministic stand-in for {@code MachineSecretCipher}. */
    static final AccountCipher FAKE_CIPHER = new AccountCipher()
    {
        @Override
        public byte[] encrypt( byte[] plaintext )
        {
            byte[] out = Arrays.copyOf( FAKE_PREFIX, FAKE_PREFIX.length + plaintext.length );
            System.arraycopy( plaintext, 0, out, FAKE_PREFIX.length, plaintext.length );
            return out;
        }

        @Override
        public byte[] decrypt( byte[] envelope ) throws Exception
        {
            if ( envelope.length < FAKE_PREFIX.length
                    || !Arrays.equals( Arrays.copyOf( envelope, FAKE_PREFIX.length ), FAKE_PREFIX ) ) {
                throw new javax.crypto.AEADBadTagException( "not ours" );
            }
            return Arrays.copyOfRange( envelope, FAKE_PREFIX.length, envelope.length );
        }
    };

    /** A cipher bound to "another machine": it can't decrypt anything FAKE_CIPHER wrote. */
    static final AccountCipher OTHER_MACHINE = new AccountCipher()
    {
        @Override
        public byte[] encrypt( byte[] plaintext )
        {
            return plaintext;
        }

        @Override
        public byte[] decrypt( byte[] envelope ) throws Exception
        {
            throw new javax.crypto.AEADBadTagException( "wrong machine" );
        }
    };

    /** A 32-hex-digit profile id built from a single digit, e.g. {@code uuid('a')}. */
    static String uuid( char digit )
    {
        return String.valueOf( digit ).repeat( 32 );
    }

    /** A user whose name and token are derived from the uuid's digit. */
    static net.hycrafthd.minecraft_authenticator.login.User user( char digit, String token )
    {
        return new net.hycrafthd.minecraft_authenticator.login.User(
                uuid( digit ), "Player" + digit, token, "msa", "xuid" + digit, "client" );
    }

    /** Stand-in authentication-file bytes; the store treats them as opaque. */
    static byte[] authFile( String label )
    {
        return ( "auth:" + label ).getBytes( StandardCharsets.UTF_8 );
    }
}
