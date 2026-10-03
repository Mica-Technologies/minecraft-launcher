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


package com.micatechnologies.minecraft.launcher.game.session;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CompletableFuture;

/**
 * A {@link Process} the test ends on demand, so session lifecycles are deterministic and no
 * real JVM is spawned.
 */
public final class FakeProcess extends Process
{
    private final CompletableFuture< Process > exit = new CompletableFuture<>();
    private volatile int     code = -1;
    volatile boolean destroyed;
    volatile boolean destroyedForcibly;

    /** Ends the "game" with the given exit code. */
    public void finish( int exitCode )
    {
        code = exitCode;
        exit.complete( this );
    }

    @Override public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
    @Override public InputStream getInputStream() { return InputStream.nullInputStream(); }
    @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
    @Override public int waitFor() { return exit.join().exitValue(); }

    @Override
    public int exitValue()
    {
        if ( !exit.isDone() ) {
            throw new IllegalThreadStateException( "still running" );
        }
        return code;
    }

    @Override public void destroy() { destroyed = true; finish( 143 ); }
    @Override public Process destroyForcibly() { destroyedForcibly = true; finish( 137 ); return this; }
    @Override public boolean isAlive() { return !exit.isDone(); }
    @Override public CompletableFuture< Process > onExit() { return exit; }
}
