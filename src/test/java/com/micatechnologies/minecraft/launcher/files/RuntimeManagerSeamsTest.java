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

import com.micatechnologies.minecraft.launcher.gui.MCLauncherProgressGui;
import org.apache.commons.lang3.SystemUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the file-system-search and progress-reporting seams in {@link RuntimeManager}
 * that don't require the network-bound {@code verifyRuntime} flow: {@code searchForFile},
 * {@code findJavaExecutable}, {@code markJavaBinariesExecutable}, and {@code reportProgress}.
 *
 * <p>These are all off {@code LocalPathManager} — every directory used here is a JUnit
 * {@code @TempDir}, never the real runtime folder under the user's launcher data directory —
 * so they're safe to run without touching the checkout or the user's machine.</p>
 *
 * <p><b>Why this matters:</b> {@code findJavaExecutable}/{@code searchForFile} are the
 * fallback path used when a freshly extracted runtime doesn't have the java binary at its
 * expected, platform-specific location (an unusual archive layout). A regression here means
 * a runtime that installed successfully on disk is reported as unusable, and the launcher
 * falls back to "Unknown (System Java)" — silently running the game under whatever
 * unversioned {@code java} happens to be on the user's PATH instead of the runtime that was
 * just downloaded for them. {@code markJavaBinariesExecutable} heals a real shipped bug
 * (pre-2026.x installs extracted via {@link com.micatechnologies.minecraft.launcher.utilities.ArchiveExtractor}
 * lost their execute bit) — if it regresses, the game silently fails to launch with a
 * permission error on macOS/Linux.</p>
 */
class RuntimeManagerSeamsTest
{
    // =============================================================== searchForFile

    @Test
    void findsAFileDirectlyInTheGivenDirectory( @TempDir File dir ) throws IOException
    {
        File target = new File( dir, "java" );
        assertTrue( target.createNewFile() );

        assertEquals( target.getAbsolutePath(), RuntimeManager.searchForFile( dir, "java" ) );
    }

    @Test
    void findsAFileNestedSeveralDirectoriesDeep( @TempDir File dir ) throws IOException
    {
        File nested = new File( dir, "runtime/jre/bin" );
        assertTrue( nested.mkdirs() );
        File target = new File( nested, "java" );
        assertTrue( target.createNewFile() );

        assertEquals( target.getAbsolutePath(), RuntimeManager.searchForFile( dir, "java" ) );
    }

    @Test
    void returnsNullWhenNoMatchingFileExists( @TempDir File dir ) throws IOException
    {
        assertTrue( new File( dir, "not-java" ).createNewFile() );
        assertNull( RuntimeManager.searchForFile( dir, "java" ) );
    }

    @Test
    void returnsNullForANonExistentDirectory( @TempDir File dir )
    {
        File missing = new File( dir, "does-not-exist" );
        assertNull( RuntimeManager.searchForFile( missing, "java" ) );
    }

    @Test
    void matchIsExactNotPartial( @TempDir File dir ) throws IOException
    {
        // "javac" must not satisfy a search for "java".
        assertTrue( new File( dir, "javac" ).createNewFile() );
        assertNull( RuntimeManager.searchForFile( dir, "java" ) );
    }

    // =========================================================== findJavaExecutable

    @Test
    void findJavaExecutableUsesThePlatformSpecificExecutableName( @TempDir File dir ) throws IOException
    {
        String expectedName = SystemUtils.IS_OS_WINDOWS ? "java.exe" : "java";
        File bin = new File( dir, "bin" );
        assertTrue( bin.mkdirs() );
        File javaExec = new File( bin, expectedName );
        assertTrue( javaExec.createNewFile() );
        // A same-named-but-wrong-platform file must not be picked instead.
        String wrongName = SystemUtils.IS_OS_WINDOWS ? "java" : "java.exe";
        assertTrue( new File( bin, wrongName ).createNewFile() );

        assertEquals( javaExec.getAbsolutePath(), RuntimeManager.findJavaExecutable( dir ) );
    }

    @Test
    void findJavaExecutableReturnsNullWhenAbsent( @TempDir File dir )
    {
        assertNull( RuntimeManager.findJavaExecutable( dir ) );
    }

    // ================================================= markJavaBinariesExecutable

    @Test
    void nullJavaExecIsANoOp()
    {
        assertDoesNotThrow( () -> RuntimeManager.markJavaBinariesExecutable( null ) );
    }

    @Test
    void javaExecWithNoParentBinDirectoryIsANoOp()
    {
        // getParentFile() of a bare relative name with no directory component is null.
        File orphan = new File( "java" );
        assertDoesNotThrow( () -> RuntimeManager.markJavaBinariesExecutable( orphan ) );
    }

    @Test
    void restoresExecuteBitOnFilesInTheSiblingBinDirectory( @TempDir File dir ) throws IOException
    {
        File bin = new File( dir, "bin" );
        assertTrue( bin.mkdirs() );
        File javaExec = new File( bin, "java" );
        assertTrue( javaExec.createNewFile() );
        File otherBinary = new File( bin, "javac" );
        assertTrue( otherBinary.createNewFile() );
        assertTrue( javaExec.setExecutable( false ) );
        assertTrue( otherBinary.setExecutable( false ) );

        RuntimeManager.markJavaBinariesExecutable( javaExec );

        if ( SystemUtils.IS_OS_WINDOWS ) {
            // Windows execution is governed by file extension, not the POSIX bit —
            // the method is documented as a no-op there.
            return;
        }
        assertTrue( javaExec.canExecute(), "java binary should have its execute bit restored" );
        assertTrue( otherBinary.canExecute(), "every regular file in bin/ should be marked executable" );
    }

    @Test
    void restoresExecuteBitOnLibJspawnhelperWhenPresent( @TempDir File dir ) throws IOException
    {
        File bin = new File( dir, "bin" );
        assertTrue( bin.mkdirs() );
        File javaExec = new File( bin, "java" );
        assertTrue( javaExec.createNewFile() );
        File lib = new File( dir, "lib" );
        assertTrue( lib.mkdirs() );
        File jspawnHelper = new File( lib, "jspawnhelper" );
        assertTrue( jspawnHelper.createNewFile() );
        assertTrue( jspawnHelper.setExecutable( false ) );

        RuntimeManager.markJavaBinariesExecutable( javaExec );

        if ( !SystemUtils.IS_OS_WINDOWS ) {
            assertTrue( jspawnHelper.canExecute() );
        }
    }

    // ===================================================================== reportProgress

    @Test
    void reportProgressWithNoSinksDoesNotThrow()
    {
        assertDoesNotThrow( () ->
                RuntimeManager.reportProgress( null, null, "Java Runtime", "Checking...", 5 ) );
    }

    @Test
    void reportProgressInvokesTheInlineCallbackWithTheLowerText()
    {
        List< String > received = new ArrayList<>();
        RuntimeManager.RuntimeProgressCallback callback = received::add;

        RuntimeManager.reportProgress( null, callback, "Java Runtime", "Installing files (3/10)", 32 );

        assertEquals( List.of( "Installing files (3/10)" ), received );
    }

    @Test
    void reportProgressToleratesANegativeIndeterminatePercent()
    {
        List< String > received = new ArrayList<>();
        RuntimeManager.RuntimeProgressCallback callback = received::add;

        assertDoesNotThrow( () ->
                RuntimeManager.reportProgress( null, callback, "Legacy JRE", "Downloading...", -1 ) );
        assertEquals( List.of( "Downloading..." ), received );
    }

    /** A {@code null} progress window (the standalone GUI is never built in a headless test
     *  run) must not be dereferenced — only the callback and the console log fire. */
    @Test
    void nullProgressWindowIsSkippedRatherThanDereferenced()
    {
        MCLauncherProgressGui nullWindow = null;
        assertDoesNotThrow( () ->
                RuntimeManager.reportProgress( nullWindow, s -> { }, "Java Runtime", "Done", 100 ) );
    }
}
