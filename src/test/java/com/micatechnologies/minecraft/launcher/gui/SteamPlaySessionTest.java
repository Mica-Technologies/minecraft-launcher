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

import com.micatechnologies.minecraft.launcher.game.session.GameSession.Phase;
import org.junit.jupiter.api.Test;

import static com.micatechnologies.minecraft.launcher.gui.SteamPlaySession.Step;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SteamPlaySessionTest
{
    @Test
    void preparingChangesNothing()
    {
        assertEquals( Step.NONE, SteamPlaySession.decide( Phase.PREPARING, true, false ) );
    }

    @Test
    void theRunningGameGetsTheScreenOnlyInGameMode()
    {
        assertEquals( Step.HIDE_WINDOWS, SteamPlaySession.decide( Phase.RUNNING, true, false ) );
        assertEquals( Step.NONE, SteamPlaySession.decide( Phase.RUNNING, false, false ) );
    }

    @Test
    void aNormalEndOrCancelReturnsToSteam()
    {
        assertEquals( Step.QUIT, SteamPlaySession.decide( Phase.EXITED, true, false ) );
        assertEquals( Step.QUIT, SteamPlaySession.decide( Phase.CANCELLED, false, false ) );
    }

    @Test
    void anotherRunningGameKeepsTheLauncherOpen()
    {
        assertEquals( Step.NONE, SteamPlaySession.decide( Phase.EXITED, true, true ) );
    }

    @Test
    void aFailureStaysOnScreenToBeRead()
    {
        assertEquals( Step.SHOW_WINDOWS, SteamPlaySession.decide( Phase.CRASHED, true, false ) );
        assertEquals( Step.SHOW_WINDOWS, SteamPlaySession.decide( Phase.FAILED, false, true ) );
    }
}
