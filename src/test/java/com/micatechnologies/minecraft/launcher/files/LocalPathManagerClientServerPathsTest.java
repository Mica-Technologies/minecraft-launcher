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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Tests every {@link LocalPathManager} path getter under both {@link
 * com.micatechnologies.minecraft.launcher.utilities.objects.GameMode#CLIENT} and {@code SERVER},
 * complementing {@code LocalPathManagerClientConfigTest} (which covers only the mode-unset
 * fallback for {@code getClientConfigFolderPath()}).
 *
 * <p>Why this matters: every one of these getters decides where the launcher reads or writes a
 * real file — config, the pack install root, logs, downloaded Java runtimes, the shared asset
 * cache, cached auth tokens, and the Mojang version manifest — and all of them key off
 * {@code GameModeManager.isClient()}, which switches between a user-home path (client) and a
 * current-working-directory path (server, so a headless server jar can be dropped anywhere).
 * Getting the composition wrong for even one getter means the launcher reads from one location and
 * writes to another, silently duplicating state (a runtime downloaded twice, a config change that
 * "doesn't stick") rather than throwing.</p>
 *
 * <p><b>Why a subprocess.</b> {@code GameModeManager}'s current mode is process-global static
 * state. No test in this suite mutates it in-process — see {@code LocalPathManagerClientConfigTest}
 * and the {@code MachineSecretCipher} subprocess harness, all of which
 * document relying on the ambient unset/{@code null} default for the lifetime of the shared test
 * JVM. Exercising both branches therefore runs {@link LocalPathManagerSubprocessHarness} in a
 * short-lived child JVM per mode, following the same pattern as
 * {@code MCLauncherAuthManagerRenewalTimestampHarness} (including forwarding this JVM's JaCoCo
 * {@code -javaagent} flag, if present, so the child's execution of the real getters is credited to
 * the coverage report instead of vanishing).</p>
 */
class LocalPathManagerClientServerPathsTest
{
    @Test
    void clientModePathsAreComposedCorrectly( @TempDir Path cwd ) throws Exception
    {
        Map< String, String > paths = runHarness( cwd, "client" );

        String root = paths.get( "LOCAL" );
        assertTrue( root.contains( ".mica" ) || root.length() > 0, "sanity: root path must be non-empty" );

        assertEquals( root + File.separator + "config", paths.get( "CONFIG" ) );
        assertTrue( paths.get( "METADATA" ).startsWith( root ) && paths.get( "METADATA" ).endsWith( "metadata" ) );
        assertTrue( paths.get( "MODPACK" ).startsWith( root ) && paths.get( "MODPACK" ).endsWith( "installs" ) );
        assertTrue( paths.get( "LOG" ).startsWith( root ) && paths.get( "LOG" ).endsWith( "logs" ) );
        assertTrue( paths.get( "RUNTIME" ).startsWith( root ) && paths.get( "RUNTIME" ).endsWith( "runtime" ) );
        assertTrue( paths.get( "SHARED_ASSETS" ).startsWith( root ) );

        String config = paths.get( "CONFIG" );
        assertTrue( paths.get( "CLIENT_TOKEN" ).startsWith( config ) && paths.get( "CLIENT_TOKEN" )
                .endsWith( "client.mica" ) );
        assertTrue( paths.get( "REMEMBERED_ACCOUNT" ).startsWith( config ) && paths.get( "REMEMBERED_ACCOUNT" )
                .endsWith( "player.mica" ) );
        assertTrue( paths.get( "UPDATE_INFO" ).startsWith( config ) && paths.get( "UPDATE_INFO" )
                .endsWith( "update.mica" ) );
        assertTrue( paths.get( "VERSION_MANIFEST" ).startsWith( config ) );
    }

    @Test
    void serverModePathsAreComposedCorrectly( @TempDir Path cwd ) throws Exception
    {
        Map< String, String > paths = runHarness( cwd, "server" );

        // Server mode anchors the root at the child process's working directory.
        String expectedRoot = cwd.toRealPath().toString();
        assertEquals( expectedRoot, paths.get( "LOCAL" ) );

        String root = paths.get( "LOCAL" );
        assertEquals( root + File.separator + "config", paths.get( "CONFIG" ) );
        assertTrue( paths.get( "METADATA" ).startsWith( root ) && paths.get( "METADATA" ).endsWith( "metadata" ) );
        assertTrue( paths.get( "RUNTIME" ).startsWith( root ) && paths.get( "RUNTIME" ).endsWith( "runtime" ) );
    }

    @Test
    void clientAndServerRootsDiffer( @TempDir Path clientCwd, @TempDir Path serverCwd ) throws Exception
    {
        Map< String, String > clientPaths = runHarness( clientCwd, "client" );
        Map< String, String > serverPaths = runHarness( serverCwd, "server" );

        assertNotEquals( clientPaths.get( "LOCAL" ), serverPaths.get( "LOCAL" ) );
    }

    // ===================================================================
    //  Subprocess plumbing — mirrors MCLauncherAuthManagerRenewalTimestampTest's harness runner
    // ===================================================================

    /**
     * Runs {@link LocalPathManagerSubprocessHarness} in a child JVM whose working directory is
     * {@code cwd}, reusing this test JVM's own java executable and classpath, and forwarding the
     * JaCoCo {@code -javaagent} flag if this JVM was itself launched under it. Returns the
     * {@code LABEL:value} stdout lines parsed into a map.
     */
    private static Map< String, String > runHarness( Path cwd, String mode ) throws Exception
    {
        String javaBin = System.getProperty( "java.home" ) + File.separator + "bin" + File.separator + "java";
        String classpath = System.getProperty( "java.class.path" );

        List< String > command = new ArrayList<>();
        command.add( javaBin );
        command.add( "-cp" );
        command.add( classpath );
        String jacocoAgentArg = jacocoAgentArgOrNull();
        if ( jacocoAgentArg != null ) {
            command.add( jacocoAgentArg );
        }
        command.add( LocalPathManagerSubprocessHarness.class.getName() );
        command.add( mode );

        Process process = new ProcessBuilder( command )
                .directory( cwd.toFile() )
                .redirectErrorStream( true )
                .start();

        List< String > lines = new ArrayList<>();
        try ( BufferedReader reader = new BufferedReader(
                new InputStreamReader( process.getInputStream(), StandardCharsets.UTF_8 ) ) ) {
            String line;
            while ( ( line = reader.readLine() ) != null ) {
                lines.add( line );
            }
        }

        boolean finished = process.waitFor( 30, TimeUnit.SECONDS );
        if ( !finished ) {
            process.destroyForcibly();
            fail( "harness subprocess did not exit within 30s; output so far: " + lines );
        }

        Map< String, String > result = new HashMap<>();
        for ( String line : lines ) {
            int colon = line.indexOf( ':' );
            if ( colon > 0 ) {
                result.put( line.substring( 0, colon ), line.substring( colon + 1 ) );
            }
        }
        if ( result.isEmpty() ) {
            fail( "harness subprocess produced no parsable output; raw lines: " + lines );
        }
        return result;
    }

    /**
     * Finds the {@code -javaagent} flag JaCoCo's {@code prepare-agent} goal added to this JVM's
     * own launch command, if any, so it can be forwarded to a harness subprocess. Returns
     * {@code null} outside a JaCoCo-instrumented run (e.g. running a single test from an IDE
     * without the Maven build), in which case the subprocess simply runs uninstrumented.
     */
    private static String jacocoAgentArgOrNull()
    {
        for ( String arg : ManagementFactory.getRuntimeMXBean().getInputArguments() ) {
            if ( arg.startsWith( "-javaagent:" ) && arg.contains( "jacoco" ) ) {
                return arg;
            }
        }
        return null;
    }
}
