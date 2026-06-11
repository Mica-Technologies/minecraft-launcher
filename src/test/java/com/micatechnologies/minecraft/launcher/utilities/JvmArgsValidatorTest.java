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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link JvmArgsValidator} — the shared rules the Settings write path
 * and the launch read path both enforce on the custom JVM args string.
 */
class JvmArgsValidatorTest
{
    private static final String NEWLINE = "\n";
    private static final String TAB = "\t";

    @Test
    void cleanArgsAreAcceptedUnchanged()
    {
        String args = "-Xss1m -Dfoo=bar -XX:+UseG1GC";
        assertTrue( JvmArgsValidator.isClean( args ) );
        assertEquals( args, JvmArgsValidator.requireClean( args ) );
    }

    @Test
    void nullAndEmptyAreClean()
    {
        assertTrue( JvmArgsValidator.isClean( null ) );
        assertTrue( JvmArgsValidator.isClean( "" ) );
        assertEquals( "", JvmArgsValidator.requireClean( null ) );
    }

    @Test
    void controlCharactersAreRejected()
    {
        // A newline (multi-line paste smuggling extra args) or a tab both split
        // into extra tokens at launch, so both are rejected.
        assertFalse( JvmArgsValidator.isClean( "-Xmx2g" + NEWLINE + "-javaagent:/evil.jar" ) );
        assertThrows( IllegalArgumentException.class,
                () -> JvmArgsValidator.requireClean( "-Xmx2g" + TAB + "-Dx=1" ) );
    }

    @Test
    void placeholderSyntaxIsRejected()
    {
        assertFalse( JvmArgsValidator.isClean( "-Dtoken=${auth_access_token}" ) );
        assertThrows( IllegalArgumentException.class,
                () -> JvmArgsValidator.requireClean( "-Dtoken=${auth_access_token}" ) );
        // The opener alone is enough to reject — no closing brace required.
        assertFalse( JvmArgsValidator.isClean( "-Dx=${" ) );
    }
}
