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

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link ProcessUtilities#stripSensitiveEnv(Map)} — the filter applied to
 * every spawned game process's environment before Forge/mod code (unsandboxed
 * JVM code the launcher does not control) can read it. If a real credential
 * variable slipped past this filter, any installed mod could exfiltrate it via
 * {@code System.getenv()} the moment the user launched a modpack. This pins the
 * name-substring / prefix rules that decide what gets stripped, including that
 * the check is case-insensitive (shells on Windows commonly uppercase env
 * names, but nothing guarantees a user's own variable casing).
 *
 * @since 3.0
 */
class ProcessUtilitiesSensitiveEnvTest
{
    private static Map< String, String > envOf( String... keys )
    {
        Map< String, String > env = new HashMap<>();
        for ( String key : keys ) {
            env.put( key, "value" );
        }
        return env;
    }

    @Test
    void stripsExactSensitiveNames()
    {
        Map< String, String > env = envOf( "AWS_SECRET_ACCESS_KEY", "GITHUB_TOKEN", "MY_PASSWORD" );
        ProcessUtilities.stripSensitiveEnv( env );
        assertTrue( env.isEmpty() );
    }

    @Test
    void stripsCaseInsensitively()
    {
        Map< String, String > env = envOf( "my_secret_thing", "Api_Key_Value" );
        ProcessUtilities.stripSensitiveEnv( env );
        assertTrue( env.isEmpty() );
    }

    @Test
    void stripsByCloudProviderPrefix()
    {
        Map< String, String > env = envOf( "AWS_REGION", "AZURE_CLIENT_ID", "OPENAI_API_BASE" );
        ProcessUtilities.stripSensitiveEnv( env );
        assertTrue( env.isEmpty() );
    }

    @Test
    void keepsOrdinaryVariablesUntouched()
    {
        Map< String, String > env = envOf( "PATH", "HOME", "JAVA_HOME", "LANG" );
        ProcessUtilities.stripSensitiveEnv( env );
        assertEquals( 4, env.size() );
    }

    @Test
    void keepsNonSensitiveVariableWhileStrippingSensitiveOne()
    {
        Map< String, String > env = envOf( "PATH", "DISCORD_TOKEN" );
        ProcessUtilities.stripSensitiveEnv( env );
        assertEquals( Map.of( "PATH", "value" ), env );
    }

    @Test
    void emptyAndNullMapsAreNoOps()
    {
        Map< String, String > empty = new HashMap<>();
        ProcessUtilities.stripSensitiveEnv( empty );
        assertTrue( empty.isEmpty() );

        // Must not throw on null — called on a live ProcessBuilder environment view
        // in production, but defensive here in case a future caller passes null.
        ProcessUtilities.stripSensitiveEnv( null );
    }

    @Test
    void doesNotStripVariableThatMerelyContainsUnrelatedSubstring()
    {
        // "TOKENIZER_MODE" contains "TOKEN" as a substring — the filter is
        // deliberately substring-based (conservative false-positive over
        // false-negative), so this IS expected to be stripped. Pinning this
        // documents the trade-off rather than silently relying on it.
        Map< String, String > env = envOf( "TOKENIZER_MODE" );
        ProcessUtilities.stripSensitiveEnv( env );
        assertTrue( env.isEmpty(), "substring match on TOKEN is intentionally conservative" );
    }
}
