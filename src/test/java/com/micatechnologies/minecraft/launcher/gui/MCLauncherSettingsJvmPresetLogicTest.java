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

package com.micatechnologies.minecraft.launcher.gui;

import com.micatechnologies.minecraft.launcher.consts.ConfigConstants;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pure-logic coverage for the Settings JVM-preset picker: which entry saved args select, and what
 * a save writes. Guards the regression where generated or hand-edited args showed as
 * "Performance" and the next Save silently replaced them with Performance's args.
 */
class MCLauncherSettingsJvmPresetLogicTest
{
    private static final String GENERATED = "-XX:+UseG1GC -Xss2m -XX:MaxGCPauseMillis=37";

    @Test
    void presetArgsSelectTheirPreset()
    {
        for ( int i = 0; i < ConfigConstants.JVM_PRESET_ARGS.length; i++ ) {
            assertEquals( i, MCLauncherSettingsGui.jvmPresetIndexForArgs( ConfigConstants.JVM_PRESET_ARGS[ i ] ) );
        }
    }

    @Test
    void argsMatchingNoPresetAreCustom()
    {
        assertEquals( -1, MCLauncherSettingsGui.jvmPresetIndexForArgs( GENERATED ) );
        assertEquals( -1, MCLauncherSettingsGui.jvmPresetIndexForArgs( null ) );
    }

    @Test
    void theMigratedDefaultSelectsPerformance()
    {
        int performance = MCLauncherSettingsGui.jvmPresetIndexOf( ConfigConstants.JVM_PRESET_PERFORMANCE );
        assertEquals( performance, MCLauncherSettingsGui.jvmPresetIndexForArgs( ConfigConstants.JVM_ARGS_VALUE_DEFAULT ) );
        // An unmigrated pre-2026.10 default is not a preset any more: it shows as Custom and is kept.
        assertEquals( -1, MCLauncherSettingsGui.jvmPresetIndexForArgs(
                ConfigConstants.JVM_ARGS_VALUE_DEFAULT_PRE_2026_10 ) );
    }

    @Test
    void customSelectionNeverOverwritesTheSavedArgs()
    {
        assertNull( MCLauncherSettingsGui.jvmArgsToPersist( -1, GENERATED ) );
        assertNull( MCLauncherSettingsGui.jvmArgsToPersist(
                MCLauncherSettingsGui.jvmPresetIndexOf( "Custom" ), GENERATED ) );
    }

    @Test
    void anUnchangedPresetWritesNothing()
    {
        int performance = MCLauncherSettingsGui.jvmPresetIndexOf( ConfigConstants.JVM_PRESET_PERFORMANCE );
        assertNull( MCLauncherSettingsGui.jvmArgsToPersist( performance, ConfigConstants.JVM_PRESET_PERFORMANCE_ARGS ) );
    }

    @Test
    void pickingAPresetWritesItsArgs()
    {
        int lowMemory = MCLauncherSettingsGui.jvmPresetIndexOf( ConfigConstants.JVM_PRESET_LOW_MEMORY );
        assertEquals( ConfigConstants.JVM_PRESET_LOW_MEMORY_ARGS,
                      MCLauncherSettingsGui.jvmArgsToPersist( lowMemory, GENERATED ) );
    }
}
