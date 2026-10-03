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


package com.micatechnologies.minecraft.launcher.files;

import com.micatechnologies.minecraft.launcher.game.session.FakeProcess;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that a runtime used by a running game can't be deleted, and stops counting as in use
 * once the game exits. Uses component names no real install has, so nothing on disk is touched.
 */
class RuntimeInUseTest
{
    @Test
    void aRuntimeIsInUseWhileItsGameRuns()
    {
        FakeProcess game = new FakeProcess();
        RuntimeManager.markInUse( "test-runtime-in-use", game );
        assertTrue( RuntimeManager.isInUse( "test-runtime-in-use" ) );
        game.finish( 0 );
        assertFalse( RuntimeManager.isInUse( "test-runtime-in-use" ) );
    }

    @Test
    void twoGamesOnOneRuntimeKeepItInUseUntilBothExit()
    {
        FakeProcess first = new FakeProcess();
        FakeProcess second = new FakeProcess();
        RuntimeManager.markInUse( "test-runtime-shared", first );
        RuntimeManager.markInUse( "test-runtime-shared", second );
        first.finish( 0 );
        assertTrue( RuntimeManager.isInUse( "test-runtime-shared" ) );
        second.finish( 0 );
        assertFalse( RuntimeManager.isInUse( "test-runtime-shared" ) );
    }

    @Test
    void aRuntimeInUseCannotBeDeleted()
    {
        FakeProcess game = new FakeProcess();
        RuntimeManager.markInUse( "test-runtime-delete", game );
        assertThrows( java.io.IOException.class, () -> RuntimeManager.clearRuntime( "test-runtime-delete" ) );
        game.finish( 0 );
    }

    @Test
    void unknownAndNullComponentsAreNotInUse()
    {
        assertFalse( RuntimeManager.isInUse( "test-runtime-never-used" ) );
        assertFalse( RuntimeManager.isInUse( null ) );
        RuntimeManager.markInUse( null, new FakeProcess() );
        RuntimeManager.markInUse( "test-runtime-null-process", null );
        assertFalse( RuntimeManager.isInUse( "test-runtime-null-process" ) );
    }
}
