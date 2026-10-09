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

package com.micatechnologies.minecraft.launcher.config;

import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.launcher.consts.ConfigConstants;
import com.micatechnologies.minecraft.launcher.utilities.JvmArgsValidator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the config v6 to v7 correction, {@link ConfigManager#migrateDefaultJvmArgs}: the saved
 * JVM args are rewritten to the new default only when they are exactly the pre-2026.10 default,
 * which carried {@code -XX:+DisableExplicitGC} and let direct buffer memory run out. Exercises the
 * pure seam rather than {@code migrateConfigIfNeeded}, which writes the real config file.
 */
class ConfigManagerJvmArgsMigrationTest
{
    private static JsonObject configWithArgs( String args )
    {
        JsonObject config = new JsonObject();
        config.addProperty( ConfigConstants.JVM_ARGS_KEY, args );
        return config;
    }

    private static String savedArgs( JsonObject config )
    {
        return config.get( ConfigConstants.JVM_ARGS_KEY ).getAsString();
    }

    @Test
    void theNewDefaultUsesConcurrentExplicitGc()
    {
        assertTrue( ConfigConstants.JVM_ARGS_VALUE_DEFAULT.contains( "-XX:+ExplicitGCInvokesConcurrent" ) );
        assertFalse( ConfigConstants.JVM_ARGS_VALUE_DEFAULT.contains( "DisableExplicitGC" ) );
        assertEquals( ConfigConstants.JVM_ARGS_VALUE_DEFAULT, ConfigConstants.JVM_PRESET_PERFORMANCE_ARGS );
        assertTrue( ConfigConstants.JVM_ARGS_VALUE_DEFAULT_PRE_2026_10.contains( "-XX:+DisableExplicitGC" ) );
        // The migrated value must survive the read-side validation, or it would be dropped on read.
        assertTrue( JvmArgsValidator.isClean( ConfigConstants.JVM_ARGS_VALUE_DEFAULT ) );
    }

    @Test
    void theOldDefaultIsReplacedWithTheNewDefault()
    {
        JsonObject config = configWithArgs( ConfigConstants.JVM_ARGS_VALUE_DEFAULT_PRE_2026_10 );
        assertTrue( ConfigManager.migrateDefaultJvmArgs( config ) );
        assertEquals( ConfigConstants.JVM_ARGS_VALUE_DEFAULT, savedArgs( config ) );
    }

    @Test
    void theOldDefaultWithSurroundingWhitespaceIsReplaced()
    {
        JsonObject config = configWithArgs( "  " + ConfigConstants.JVM_ARGS_VALUE_DEFAULT_PRE_2026_10 + " \n" );
        assertTrue( ConfigManager.migrateDefaultJvmArgs( config ) );
        assertEquals( ConfigConstants.JVM_ARGS_VALUE_DEFAULT, savedArgs( config ) );
    }

    @Test
    void aCustomisedValueIsLeftUntouched()
    {
        String[] customised = {
                ConfigConstants.JVM_ARGS_VALUE_DEFAULT_PRE_2026_10 + " -Xss2m",
                ConfigConstants.JVM_ARGS_VALUE_DEFAULT_PRE_2026_10.replace( "MaxGCPauseMillis=200",
                                                                            "MaxGCPauseMillis=100" ),
                ConfigConstants.JVM_ARGS_VALUE_DEFAULT_PRE_2026_10.replace( " -XX:+AlwaysPreTouch", "" ),
                ConfigConstants.JVM_ARGS_VALUE_DEFAULT_PRE_2026_10.replace( " ", "  " ),
                // The pre-2026.10 hardware-tuned output also carried DisableExplicitGC; it is
                // a value the player chose, so it is not rewritten.
                "-XX:+UseG1GC -XX:+UnlockExperimentalVMOptions -XX:MaxGCPauseMillis=200 " +
                        "-XX:G1HeapRegionSize=4M -XX:+AlwaysPreTouch -XX:+DisableExplicitGC",
                ConfigConstants.JVM_PRESET_LOW_MEMORY_ARGS,
                "",
        };
        for ( String value : customised ) {
            JsonObject config = configWithArgs( value );
            assertFalse( ConfigManager.migrateDefaultJvmArgs( config ), value );
            assertEquals( value, savedArgs( config ) );
        }
    }

    @Test
    void theNewDefaultIsLeftUntouched()
    {
        JsonObject config = configWithArgs( ConfigConstants.JVM_ARGS_VALUE_DEFAULT );
        assertFalse( ConfigManager.migrateDefaultJvmArgs( config ) );
        assertEquals( ConfigConstants.JVM_ARGS_VALUE_DEFAULT, savedArgs( config ) );
    }

    @Test
    void theMigrationIsIdempotent()
    {
        JsonObject config = configWithArgs( ConfigConstants.JVM_ARGS_VALUE_DEFAULT_PRE_2026_10 );
        assertTrue( ConfigManager.migrateDefaultJvmArgs( config ) );
        JsonObject afterFirst = config.deepCopy();
        assertFalse( ConfigManager.migrateDefaultJvmArgs( config ) );
        assertEquals( afterFirst, config );
    }

    @Test
    void anAbsentOrNonStringValueIsLeftAlone()
    {
        JsonObject empty = new JsonObject();
        assertFalse( ConfigManager.migrateDefaultJvmArgs( empty ) );
        assertFalse( empty.has( ConfigConstants.JVM_ARGS_KEY ) );

        JsonObject numeric = new JsonObject();
        numeric.addProperty( ConfigConstants.JVM_ARGS_KEY, 42 );
        assertFalse( ConfigManager.migrateDefaultJvmArgs( numeric ) );
        assertEquals( 42, numeric.get( ConfigConstants.JVM_ARGS_KEY ).getAsInt() );

        JsonObject nested = new JsonObject();
        nested.add( ConfigConstants.JVM_ARGS_KEY, new JsonObject() );
        assertFalse( ConfigManager.migrateDefaultJvmArgs( nested ) );

        assertFalse( ConfigManager.migrateDefaultJvmArgs( null ) );
    }

    @Test
    void theOldAndNewDefaultsDifferOnlyInTheExplicitGcFlag()
    {
        assertNotEquals( ConfigConstants.JVM_ARGS_VALUE_DEFAULT_PRE_2026_10, ConfigConstants.JVM_ARGS_VALUE_DEFAULT );
        assertEquals( ConfigConstants.JVM_ARGS_VALUE_DEFAULT,
                      ConfigConstants.JVM_ARGS_VALUE_DEFAULT_PRE_2026_10.replace( "-XX:+DisableExplicitGC",
                                                                                  "-XX:+ExplicitGCInvokesConcurrent" ) );
    }
}
