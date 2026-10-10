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
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Locks in that the once-per-session verified flag never outlives the file it vouches for.
 * A file verified earlier in the session and deleted (or emptied) since must go back through
 * the full verify/download path instead of being skipped, or a launch and "verify this pack"
 * both leave it missing until the launcher restarts.
 *
 * <p>Uses a file with no remote URL so the test needs no network: such a file is accepted
 * while present and raises a clear error once missing, which is exactly the path the session
 * flag used to short-circuit.</p>
 */
class ManagedGameFileSessionVerifiedTest
{
    @Test
    void deletedFileIsRecheckedAfterSessionVerify( @TempDir Path tmp ) throws Exception
    {
        Files.write( tmp.resolve( "mod.jar" ), "hello\n".getBytes() );
        ManagedGameFile f = new ManagedGameFile( "", "mod.jar" );
        f.setLocalPathPrefix( tmp.toString() );

        assertFalse( f.updateLocalFile( LaunchPrepareContext.NONE ) );

        Files.delete( tmp.resolve( "mod.jar" ) );
        assertThrows( ModpackException.class, () -> f.updateLocalFile( LaunchPrepareContext.NONE ),
                      "a file deleted after its session verify must be re-checked, not skipped" );
    }

    @Test
    void presentFileStaysSkippedAfterSessionVerify( @TempDir Path tmp ) throws Exception
    {
        Files.write( tmp.resolve( "mod.jar" ), "hello\n".getBytes() );
        ManagedGameFile f = new ManagedGameFile( "", "mod.jar" );
        f.setLocalPathPrefix( tmp.toString() );

        assertFalse( f.updateLocalFile( LaunchPrepareContext.NONE ) );
        assertFalse( f.updateLocalFile( LaunchPrepareContext.NONE ) );
    }
}
