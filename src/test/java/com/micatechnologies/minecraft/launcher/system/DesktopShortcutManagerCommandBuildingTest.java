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

package com.micatechnologies.minecraft.launcher.system;

import com.micatechnologies.minecraft.launcher.LauncherCore;
import com.micatechnologies.minecraft.launcher.consts.LauncherConstants;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Tests for the launcher-relaunch command building used by
 * {@link DesktopShortcutManager#createShortcut}: {@code isNativeExecutable},
 * {@code buildArguments} (the cross-platform fallback used inside {@code convertIcon}'s
 * sibling — actually the pre-Windows/macOS/Linux-specific-builder shared shape), and
 * {@code pickBestExecutable} (deterministic choice among candidate native launcher
 * binaries found next to the running JAR).
 *
 * <p>These three decide what a desktop shortcut actually launches. A regression that
 * mis-detects "native executable vs. JAR/java binary" produces a shortcut that either
 * double-wraps a native exe in {@code -jar}, or drops the {@code -jar &lt;path&gt;} argument
 * a JAR-based install needs — either way, double-clicking the user's desktop shortcut
 * launches nothing (or the wrong thing), with no error until they try it.</p>
 */
class DesktopShortcutManagerCommandBuildingTest
{
    // =========================================================== isNativeExecutable

    @Test
    void jarPathIsNotNative()
    {
        assertEquals( false, DesktopShortcutManager.isNativeExecutable( "C:\\MicaLauncher\\launcher.jar" ) );
    }

    @Test
    void bareJavaAndJavawAreNotNative()
    {
        assertEquals( false, DesktopShortcutManager.isNativeExecutable( "/usr/bin/java" ) );
        assertEquals( false, DesktopShortcutManager.isNativeExecutable( "/usr/bin/javaw" ) );
        assertEquals( false, DesktopShortcutManager.isNativeExecutable( "C:\\jdk\\bin\\java.exe" ) );
        assertEquals( false, DesktopShortcutManager.isNativeExecutable( "C:\\jdk\\bin\\javaw.exe" ) );
    }

    @Test
    void aRealNativeExecutablePathIsNative()
    {
        assertEquals( true, DesktopShortcutManager.isNativeExecutable(
                "C:\\Program Files\\Mica Minecraft Launcher\\MicaLauncher.exe" ) );
        assertEquals( true, DesktopShortcutManager.isNativeExecutable(
                "/Applications/Mica Launcher.app/Contents/MacOS/Mica Launcher" ) );
    }

    /**
     * Documents an actual quirk: the check is a plain (case-insensitive) {@code endsWith},
     * not a match against the exact executable names "java"/"javaw". A native binary whose
     * name happens to end in those four letters — e.g. a hypothetical "CustomJava" — is
     * misclassified as the java runtime, not a native launcher.
     */
    @Test
    void anyPathEndingInJavaIsMisclassifiedAsNonNative()
    {
        assertEquals( false, DesktopShortcutManager.isNativeExecutable( "/opt/CustomJava" ) );
    }

    // ================================================================= buildArguments

    @Test
    void nativeExecutableGetsJustClientModeAndPackName()
    {
        String args = DesktopShortcutManager.buildArguments( "/opt/MicaLauncher/MicaLauncher", "SkyFactory" );
        assertEquals( LauncherConstants.PROGRAM_ARG_CLIENT_MODE + " SkyFactory", args );
    }

    @Test
    void nativeExecutableWithSpacesInPackNameGetsItDoubleQuoted()
    {
        String args = DesktopShortcutManager.buildArguments( "/opt/MicaLauncher/MicaLauncher", "All The Mods 9" );
        assertEquals( LauncherConstants.PROGRAM_ARG_CLIENT_MODE + " \"All The Mods 9\"", args );
    }

    @Test
    void jarPathGetsDashJarPrefixWithThePathQuoted()
    {
        String args = DesktopShortcutManager.buildArguments( "C:\\Mica\\launcher.jar", "SkyFactory" );
        assertEquals( "-jar \"C:\\Mica\\launcher.jar\" " + LauncherConstants.PROGRAM_ARG_CLIENT_MODE + " SkyFactory",
                      args );
    }

    /**
     * Neither native nor a {@code .jar} path (i.e. the bare {@code java}/{@code javaw}
     * binary) falls through to resolving the running JAR's own code-source location and
     * building a {@code -jar "<that path>"} argument. In this test JVM that resolves to
     * wherever Surefire put the compiled test classes (a directory, not an actual JAR) —
     * the method makes no distinction, it just wraps whatever URI the code source reports.
     */
    @Test
    void javaBinaryPathResolvesToTheRunningCodeSourceJarPath() throws Exception
    {
        File expectedJarFile = new File(
                LauncherCore.class.getProtectionDomain().getCodeSource().getLocation().toURI() );

        String args = DesktopShortcutManager.buildArguments( "/usr/bin/java", "SkyFactory" );

        String expected = "-jar \"" + expectedJarFile.getAbsolutePath() + "\" "
                + LauncherConstants.PROGRAM_ARG_CLIENT_MODE + " SkyFactory";
        assertEquals( expected, args );
    }

    // ============================================================= pickBestExecutable

    @Test
    void nullCandidatesReturnsNull()
    {
        assertNull( DesktopShortcutManager.pickBestExecutable( null ) );
    }

    @Test
    void emptyCandidatesReturnsNull()
    {
        assertNull( DesktopShortcutManager.pickBestExecutable( new File[ 0 ] ) );
    }

    @Test
    void exactApplicationNameMatchIsPreferredRegardlessOfArrayOrder()
    {
        String appName = LauncherConstants.LAUNCHER_APPLICATION_NAME_TRIMMED;
        File decoy = new File( "SomeOtherTool.exe" );
        File match = new File( appName + ".exe" );

        assertSame( match, DesktopShortcutManager.pickBestExecutable( new File[] { decoy, match } ) );
        // Order must not matter -- it's a name match, not "first candidate wins".
        assertSame( match, DesktopShortcutManager.pickBestExecutable( new File[] { match, decoy } ) );
    }

    @Test
    void withNoNameMatchTheAlphabeticallyFirstCandidateWins()
    {
        // Names chosen to be unrelated to the real application name so neither the
        // "equals"/"contains" branch fires -- this exercises the pure fallback tie-break.
        File zeta = new File( "Zeta-unrelated-9x7q.bin" );
        File alpha = new File( "alpha-unrelated-9x7q.bin" );
        File middle = new File( "Middle-unrelated-9x7q.bin" );

        File chosen = DesktopShortcutManager.pickBestExecutable( new File[] { zeta, middle, alpha } );

        assertSame( alpha, chosen );
    }

    @Test
    void singleCandidateIsAlwaysReturnedEvenWithoutAMatch()
    {
        File only = new File( "totally-unrelated-9x7q.bin" );
        assertSame( only, DesktopShortcutManager.pickBestExecutable( new File[] { only } ) );
    }
}
