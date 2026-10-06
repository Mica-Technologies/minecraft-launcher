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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic coverage for the checks Settings makes when saving: a proxy switched on with no
 * host, and a max-RAM change that crosses the JVM's compressed-oops limit.
 */
class MCLauncherSettingsValidationLogicTest
{
    @Test
    void anEnabledProxyNeedsAHost()
    {
        assertTrue( MCLauncherSettingsGui.proxyHostMissing( true, "" ) );
        assertTrue( MCLauncherSettingsGui.proxyHostMissing( true, "   " ) );
        assertTrue( MCLauncherSettingsGui.proxyHostMissing( true, null ) );
        assertFalse( MCLauncherSettingsGui.proxyHostMissing( true, "127.0.0.1" ) );
        assertFalse( MCLauncherSettingsGui.proxyHostMissing( false, "" ) );
    }

    @Test
    void warnsOnlyWhenMaxRamFirstCrossesTheLimit()
    {
        long gb = 1024L;
        assertTrue( MCLauncherSettingsGui.crossesCompressedOopsLimit( 16 * gb, 32 * gb ) );
        assertFalse( MCLauncherSettingsGui.crossesCompressedOopsLimit( 16 * gb, 31 * gb ), "31 GB keeps compressed oops" );
        assertFalse( MCLauncherSettingsGui.crossesCompressedOopsLimit( 32 * gb, 48 * gb ), "already past: no repeat warning" );
        assertFalse( MCLauncherSettingsGui.crossesCompressedOopsLimit( 48 * gb, 16 * gb ) );
    }
}
