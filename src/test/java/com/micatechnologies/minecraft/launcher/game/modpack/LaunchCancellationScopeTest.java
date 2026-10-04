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

import com.micatechnologies.minecraft.launcher.exceptions.ModpackException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A launch's cancellation check lasts only as long as the launch. The launcher is cached per
 * pack and "Verify this pack" runs the same steps, so a check left behind by a cancelled
 * launch used to make every later verify fail as cancelled until the next launch.
 */
class LaunchCancellationScopeTest
{
    private final GameModPackLauncher launcher = new GameModPackLauncher( new GameModPack(), null );

    @Test
    void cancelledLaunchDoesNotLeaveLaterWorkCancelled()
    {
        assertThrows( ModpackException.class, () -> launcher.withCancellationCheck( () -> true, () -> {
            launcher.checkCancelled();
            return null;
        } ) );

        assertDoesNotThrow( launcher::checkCancelled,
                            "after the cancelled launch ends, a verify must not see it as cancelled" );
    }

    @Test
    void checkAppliesDuringTheLaunchAndClearsAfterSuccess() throws Exception
    {
        boolean[] cancelled = { false };
        String result = launcher.withCancellationCheck( () -> cancelled[ 0 ], () -> {
            launcher.checkCancelled();
            cancelled[ 0 ] = true;
            assertThrows( ModpackException.class, launcher::checkCancelled );
            return "done";
        } );

        assertEquals( "done", result );
        assertDoesNotThrow( launcher::checkCancelled );
    }

    @Test
    void nullCheckNeverCancels()
    {
        assertDoesNotThrow( () -> launcher.withCancellationCheck( null, () -> {
            launcher.checkCancelled();
            return null;
        } ) );
    }
}
