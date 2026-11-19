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
 * Server mode never starts the JavaFX toolkit, so it must never take a GUI path: on a desktop that
 * used to fail with "Toolkit not initialized" before the server was installed.
 */
class MCLauncherGuiControllerGuiRuleTest
{
    @Test
    void aDesktopClientUsesTheGui()
    {
        assertTrue( MCLauncherGuiController.guiAllowed( false, false ) );
    }

    @Test
    void serverModeNeverUsesTheGuiEvenOnADesktop()
    {
        assertFalse( MCLauncherGuiController.guiAllowed( false, true ) );
    }

    @Test
    void aHeadlessMachineNeverUsesTheGui()
    {
        assertFalse( MCLauncherGuiController.guiAllowed( true, false ) );
        assertFalse( MCLauncherGuiController.guiAllowed( true, true ) );
    }
}
