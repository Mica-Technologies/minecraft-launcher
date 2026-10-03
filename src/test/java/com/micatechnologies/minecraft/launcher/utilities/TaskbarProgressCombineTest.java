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

import io.github.palexdev.materialfx.controls.MFXProgressBar;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for {@link TaskbarProgressManager#combine}: several launches preparing at once share
 * one taskbar bar.
 */
class TaskbarProgressCombineTest
{
    @Test
    void averagesTheLaunches()
    {
        assertEquals( 0.5, TaskbarProgressManager.combine( List.of( 0.25, 0.75 ) ), 1e-9 );
    }

    @Test
    void oneLaunchIsItself()
    {
        assertEquals( 0.4, TaskbarProgressManager.combine( List.of( 0.4 ) ), 1e-9 );
    }

    @Test
    void anyIndeterminateLaunchMakesTheBarIndeterminate()
    {
        assertEquals( MFXProgressBar.INDETERMINATE_PROGRESS,
                      TaskbarProgressManager.combine( List.of( 0.5, MFXProgressBar.INDETERMINATE_PROGRESS ) ) );
    }

    @Test
    void overshootIsCapped()
    {
        assertEquals( 1.0, TaskbarProgressManager.combine( List.of( 1.7 ) ), 1e-9 );
    }

    @Test
    void noLaunchesIsZero()
    {
        assertEquals( 0.0, TaskbarProgressManager.combine( List.of() ), 1e-9 );
    }
}
