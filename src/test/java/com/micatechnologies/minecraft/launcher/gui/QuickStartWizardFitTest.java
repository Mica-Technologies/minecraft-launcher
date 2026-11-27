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

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Covers how the quick-start wizard and a first-run main window size themselves to the screen.
 * A Steam Deck's 1280x800 display, less its panel and the title bar, is shorter than the main
 * window's designed 800, and at a raised scale the wizard didn't fit either.
 *
 * @since 2026.11
 */
class QuickStartWizardFitTest
{
    @Test
    void aSizeThatFitsIsKept()
    {
        assertEquals( 620, MCLauncherQuickStartWizard.fitToScreen( 620, 1280 ) );
    }

    @Test
    void aSizeThatDoesNotFitShrinksToTheScreenLessItsMargin()
    {
        // Deck at 150%: the wizard wants 810 of a 756-high usable area, less the title bar.
        double available = 756 - MCLauncherQuickStartWizard.TITLE_BAR_ALLOWANCE;
        assertEquals( available - ScaledMinSize.SCREEN_MARGIN, MCLauncherQuickStartWizard.fitToScreen( 810, available ) );
    }

    @Test
    void anUnknownScreenKeepsTheDesignedSize()
    {
        assertEquals( 540, MCLauncherQuickStartWizard.fitToScreen( 540, 0 ) );
        assertEquals( 540, MCLauncherQuickStartWizard.fitToScreen( 540, Double.NaN ) );
    }
}
