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


package com.micatechnologies.minecraft.launcher.game.modpack;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for {@link LaunchProgressTracker#overallFraction()}, which drives the taskbar bar for
 * each launch.
 */
class LaunchProgressOverallTest
{
    @Test
    void finishedRunningAndPendingStepsCountTheirShare()
    {
        LaunchProgressTracker t = LaunchProgressTracker.forSteps(
                LaunchProgressTracker.StepId.MC_LIBS_ASSETS, LaunchProgressTracker.StepId.JRE_INSTALL,
                LaunchProgressTracker.StepId.SECURITY_SCAN, LaunchProgressTracker.StepId.MODPACK_CONTENT );
        assertEquals( 0.0, t.overallFraction(), 1e-9 );
        t.markDone( LaunchProgressTracker.StepId.MC_LIBS_ASSETS );
        t.markRunning( LaunchProgressTracker.StepId.JRE_INSTALL );
        t.setProgress( LaunchProgressTracker.StepId.JRE_INSTALL, 0.5 );
        t.markSkipped( LaunchProgressTracker.StepId.MODPACK_CONTENT );
        assertEquals( ( 1 + 0.5 + 0 + 1 ) / 4.0, t.overallFraction(), 1e-9 );
    }

    @Test
    void noStepsIsZero()
    {
        assertEquals( 0.0, LaunchProgressTracker.forSteps().overallFraction(), 1e-9 );
    }
}
