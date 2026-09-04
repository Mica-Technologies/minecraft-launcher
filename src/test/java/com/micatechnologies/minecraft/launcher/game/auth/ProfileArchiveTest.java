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

package com.micatechnologies.minecraft.launcher.game.auth;

import com.micatechnologies.minecraft.launcher.consts.LocalPathConstants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Tests for {@link ProfileArchive} — the file-shuffle primitive behind the
 * (not-yet-wired-up) account-switcher feature: archiving a signed-in
 * Microsoft account's credential files under {@code config/profiles/<uuid>/},
 * listing them for a switcher UI, restoring one to the active slot, and
 * deleting one permanently.
 *
 * <p>This class holds a person's cached Minecraft/Microsoft session. A bug
 * here either silently loses a saved profile (so "Switch Account" quietly
 * has nothing to switch to), corrupts the active login by half-copying
 * sibling files, or — worse — leaves stray plaintext-adjacent credential
 * copies with loose file permissions lying around after a "forget". None of
 * that surfaces as a crash; it surfaces as "my saved accounts disappeared"
 * or a security review finding, weeks later.</p>
 *
 * <h3>A real bug found while writing these tests</h3>
 * <p>{@link ProfileArchive#archiveActive} and {@link ProfileArchive#activate}
 * both resolve the per-profile login-file path via
 * {@code someDirectory.resolve( LocalPathConstants.AUTH_ACCOUNT_REMEMBERED_FILE_NAME )}.
 * That constant is {@code File.separator + "player.mica"} — it carries a
 * <em>leading</em> separator because its only other use is string
 * concatenation onto {@code LocalPathManager.getLauncherConfigFolderPath()}.
 * {@link Path#resolve(String)} treats a leading-separator argument as an
 * <em>absolute</em> path and — per its documented contract — returns that
 * argument verbatim, discarding the directory it was resolved against
 * entirely. See {@link #archivedLoginConstantResolvesAwayFromItsParentDirectory()}
 * for a live demonstration with no filesystem access at all.
 *
 * <p>The practical effect: {@code archiveActive}'s copy of the login file
 * targets the real OS filesystem root (or the current drive's root on
 * Windows) instead of {@code <profiles>/<uuid>/player.mica}, and
 * {@code activate}'s existence check reads from that same wrong location —
 * so it always reports "no login file" regardless of whether a profile was
 * ever archived. Per the test-writing brief this is pinned, not fixed. It is
 * NOT exercised end-to-end here: doing so would mean either attempting a
 * real write to the machine's actual filesystem root from a test (unsafe
 * even though it happens to fail closed as a non-root user — see
 * {@link #archiveActiveWithNoActiveLoginReturnsNullAndCreatesNoProfilesDirectory()}'s
 * javadoc) or asserting on the contents of the real machine's root directory
 * (outside any {@code @TempDir} sandbox). {@link #activateNeverFindsAnArchivedLoginRegardlessOfWhetherOneWasArchived()}
 * demonstrates the read-side symptom safely, since a failed existence check
 * is read-only.</p>
 *
 * <h3>Why the file-shuffle tests run in a child JVM</h3>
 * <p>{@link ProfileArchive} resolves every path through
 * {@code LocalPathManager.getLauncherConfigFolderPath()}, which — with no
 * game mode set, as in this test JVM — falls back to
 * {@code <current working directory>/config}
 * ({@code LocalPathConstants.SERVER_MODE_LAUNCHER_FOLDER_PATH} is a
 * {@code static final} snapshot of the JVM's working directory taken at
 * class-load time). Calling {@link ProfileArchive}'s methods directly from
 * this test process would read and write a stray {@code config/profiles/}
 * tree wherever Maven happens to run from. Every test that needs real file
 * effects instead shells out to {@link ProfileArchiveSubprocessHarness} with
 * the child process's working directory pinned to a {@code @TempDir},
 * confining those effects to the sandbox while still running the exact
 * production code. Guard-clause tests that return before touching
 * {@code LocalPathManager} at all run directly, with no subprocess.</p>
 */
class ProfileArchiveTest
{
    private static final String LOGIN_FILE_NAME = "player.mica";
    private static final String META_FILE        = "profile.json";

    // ===================================================================
    // Guard clauses — return before ProfileArchive touches LocalPathManager
    // or the filesystem at all, so these run in-process with no subprocess.
    // ===================================================================

    @Test
    void archiveActiveWithNullUuidReturnsNull()
    {
        assertNull( ProfileArchive.archiveActive( null, "Steve" ) );
    }

    @Test
    void archiveActiveWithBlankUuidReturnsNull()
    {
        assertNull( ProfileArchive.archiveActive( "   ", "Steve" ) );
    }

    @Test
    void activateWithNullUuidReturnsFalse()
    {
        assertFalse( ProfileArchive.activate( null ) );
    }

    @Test
    void activateWithBlankUuidReturnsFalse()
    {
        assertFalse( ProfileArchive.activate( "   " ) );
    }

    @Test
    void forgetWithNullUuidReturnsFalse()
    {
        assertFalse( ProfileArchive.forget( null ) );
    }

    @Test
    void forgetWithBlankUuidReturnsFalse()
    {
        assertFalse( ProfileArchive.forget( "   " ) );
    }

    // ===================================================================
    // The path-resolution bug, pinned with zero filesystem access.
    // ===================================================================

    /**
     * Pins the root cause described in this class's javadoc: resolving the
     * exact constant {@code ProfileArchive} uses for the per-profile login
     * filename against a parent directory does not join the two paths — it
     * discards the parent entirely, because the constant carries a leading
     * separator and {@link Path#resolve(String)} treats that as absolute.
     * A correctly-relative filename (no leading separator) resolves the
     * normal way. This is the only place this defect is exercised directly;
     * see the class javadoc for why the live archive/activate paths are not
     * driven end-to-end in these tests.
     */
    @Test
    void archivedLoginConstantResolvesAwayFromItsParentDirectory()
    {
        Path parent = Path.of( "some", "profile", "directory" );

        Path correctlyRelative = parent.resolve( LOGIN_FILE_NAME );
        assertTrue( correctlyRelative.startsWith( parent ),
                "a filename with no leading separator should resolve underneath its parent" );

        Path viaProductionConstant = parent.resolve( LocalPathConstants.AUTH_ACCOUNT_REMEMBERED_FILE_NAME );
        assertNotEquals( correctlyRelative, viaProductionConstant,
                "AUTH_ACCOUNT_REMEMBERED_FILE_NAME carries a leading separator, so resolving it " +
                        "must NOT land in the same place as a correctly-relative filename" );
        assertFalse( viaProductionConstant.startsWith( parent ),
                "the constant's leading separator makes it an absolute path, so resolving it " +
                        "against a parent directory discards that parent instead of joining it — " +
                        "this is exactly what ProfileArchive.archiveActive/activate do internally" );
    }

    // ===================================================================
    // archiveActive — only the branches reachable without hitting the bug.
    // ===================================================================

    /**
     * The early-return branch: no active login file at all. This runs
     * before {@code archiveActive} ever computes the buggy resolved path
     * (see class javadoc), so it is safe to execute for real — nothing is
     * written anywhere, sandboxed or not.
     */
    @Test
    void archiveActiveWithNoActiveLoginReturnsNullAndCreatesNoProfilesDirectory( @TempDir Path tempDir ) throws Exception
    {
        // Deliberately do NOT create config/player.mica.
        List< String > lines = runHarness( tempDir, "archive", "uuid-none", "Steve" );
        assertTrue( lines.contains( "ARCHIVE:NULL" ), "unexpected output: " + lines );
        assertFalse( Files.exists( profilesRoot( tempDir ) ),
                "no active login means archival must bail out before creating anything" );
    }

    // ===================================================================
    // activate — the read-side symptom of the resolve() bug, pinned safely.
    // ===================================================================

    /**
     * Demonstrates the practical effect of the bug documented in this
     * class's javadoc: even when a profile has been placed at exactly the
     * location {@code activate} is supposed to look for it — set up here by
     * writing the files directly, not via the broken {@code archiveActive}
     * — {@code activate} still reports failure, because its existence check
     * resolves to the wrong path entirely (the real OS/drive root, not
     * {@code <profiles>/<uuid>/player.mica}). This is safe to run for real:
     * the check is a read of a fixed real-machine path that essentially
     * never exists, never a write.
     */
    @Test
    void activateNeverFindsAnArchivedLoginRegardlessOfWhetherOneWasArchived( @TempDir Path tempDir ) throws Exception
    {
        String uuid = "uuid-legit-profile";
        writeFile( profileDir( tempDir, uuid ).resolve( LOGIN_FILE_NAME ), "FAKE-ARCHIVED-LOGIN-CONTENT-NOT-REAL" );
        writeFile( profileDir( tempDir, uuid ).resolve( META_FILE ),
                "{\"uuid\":\"" + uuid + "\",\"displayName\":\"Steve\",\"lastUsedMs\":1000}" );

        List< String > lines = runHarness( tempDir, "activate", uuid );

        assertTrue( lines.contains( "ACTIVATE:false" ),
                "activate() cannot find even a correctly-placed login file — see class javadoc: " + lines );
        assertFalse( Files.exists( configDir( tempDir ).resolve( LOGIN_FILE_NAME ) ),
                "since activate() bails out, the active login slot must be untouched" );
    }

    @Test
    void activateWithNoSuchProfileReturnsFalse( @TempDir Path tempDir ) throws Exception
    {
        List< String > lines = runHarness( tempDir, "activate", "uuid-does-not-exist" );
        assertTrue( lines.contains( "ACTIVATE:false" ), "unexpected output: " + lines );
    }

    // ===================================================================
    // list — unaffected by the resolve() bug (relative filenames only).
    // ===================================================================

    @Test
    void listReturnsEmptyWhenNoProfilesDirectoryExists( @TempDir Path tempDir ) throws Exception
    {
        List< String > lines = runHarness( tempDir, "list" );
        assertTrue( lines.contains( "LIST_COUNT:0" ), "unexpected output: " + lines );
    }

    @Test
    void listSortsEntriesByLastUsedDescending( @TempDir Path tempDir ) throws Exception
    {
        writeFile( profileDir( tempDir, "uuid-old" ).resolve( META_FILE ),
                "{\"uuid\":\"uuid-old\",\"displayName\":\"Old\",\"lastUsedMs\":1000}" );
        writeFile( profileDir( tempDir, "uuid-new" ).resolve( META_FILE ),
                "{\"uuid\":\"uuid-new\",\"displayName\":\"New\",\"lastUsedMs\":2000}" );

        List< String > lines = runHarness( tempDir, "list" );

        assertTrue( lines.contains( "LIST_COUNT:2" ), "unexpected output: " + lines );
        int newIndex = lines.indexOf( "LIST_ENTRY:uuid-new:New:2000" );
        int oldIndex = lines.indexOf( "LIST_ENTRY:uuid-old:Old:1000" );
        assertTrue( newIndex >= 0 && oldIndex >= 0, "expected both entries present: " + lines );
        assertTrue( newIndex < oldIndex, "most-recently-used profile must sort first: " + lines );
    }

    @Test
    void listSkipsProfileDirectoryMissingMetadataFile( @TempDir Path tempDir ) throws Exception
    {
        // A profile folder with a login file but no profile.json sidecar
        // (e.g. an interrupted archive) must be silently skipped, not throw.
        writeFile( profileDir( tempDir, "uuid-no-meta" ).resolve( LOGIN_FILE_NAME ), "FAKE-LOGIN-BYTES" );

        List< String > lines = runHarness( tempDir, "list" );

        assertTrue( lines.contains( "LIST_COUNT:0" ), "unexpected output: " + lines );
    }

    @Test
    void listSkipsProfileWithCorruptMetadataJson( @TempDir Path tempDir ) throws Exception
    {
        writeFile( profileDir( tempDir, "uuid-corrupt" ).resolve( META_FILE ), "{ this is not valid json" );

        List< String > lines = runHarness( tempDir, "list" );

        assertTrue( lines.contains( "LIST_COUNT:0" ),
                "a malformed profile.json must be skipped rather than failing the whole listing: " + lines );
    }

    @Test
    void listFallsBackToFolderNameWhenMetadataOmitsUuid( @TempDir Path tempDir ) throws Exception
    {
        writeFile( profileDir( tempDir, "uuid-from-folder-name" ).resolve( META_FILE ),
                "{\"displayName\":\"NoUuidField\",\"lastUsedMs\":500}" );

        List< String > lines = runHarness( tempDir, "list" );

        assertTrue( lines.contains( "LIST_ENTRY:uuid-from-folder-name:NoUuidField:500" ),
                "missing uuid field should fall back to the containing folder's name: " + lines );
    }

    @Test
    void listDefaultsDisplayNameAndLastUsedWhenMetadataOmitsThem( @TempDir Path tempDir ) throws Exception
    {
        writeFile( profileDir( tempDir, "uuid-minimal" ).resolve( META_FILE ), "{\"uuid\":\"uuid-minimal\"}" );

        List< String > lines = runHarness( tempDir, "list" );

        assertTrue( lines.contains( "LIST_ENTRY:uuid-minimal::0" ),
                "missing displayName/lastUsedMs should default to empty string / zero: " + lines );
    }

    // ===================================================================
    // forget — unaffected by the resolve() bug (relative filenames only).
    // ===================================================================

    @Test
    void forgetRemovesProfileDirectoryEntirely( @TempDir Path tempDir ) throws Exception
    {
        writeFile( profileDir( tempDir, "uuid-to-forget" ).resolve( LOGIN_FILE_NAME ), "FAKE-LOGIN-BYTES" );
        writeFile( profileDir( tempDir, "uuid-to-forget" ).resolve( META_FILE ),
                "{\"uuid\":\"uuid-to-forget\",\"displayName\":\"Gone\",\"lastUsedMs\":1}" );

        List< String > lines = runHarness( tempDir, "forget", "uuid-to-forget" );

        assertTrue( lines.contains( "FORGET:true" ), "unexpected output: " + lines );
        assertFalse( Files.exists( profileDir( tempDir, "uuid-to-forget" ) ),
                "the profile directory must be gone entirely after forget()" );
    }

    @Test
    void forgetOnNonExistentProfileReturnsFalseWithoutCreatingAnything( @TempDir Path tempDir ) throws Exception
    {
        List< String > lines = runHarness( tempDir, "forget", "uuid-never-archived" );

        assertTrue( lines.contains( "FORGET:false" ), "unexpected output: " + lines );
        assertFalse( Files.exists( profilesRoot( tempDir ) ),
                "forgetting a profile that was never archived must not create a profiles directory" );
    }

    /**
     * {@code forget()} deletes only the direct children of the profile
     * folder, swallowing each per-file {@link java.io.IOException}
     * individually, then unconditionally tries to delete the folder itself.
     * A nested, non-empty subdirectory can't be removed by the single
     * {@code Files.deleteIfExists} call on it (that throws
     * {@code DirectoryNotEmptyException}, caught and ignored per-file), so
     * it survives — and the final {@code Files.deleteIfExists(folder)} then
     * fails for the same reason, this time propagating to the outer catch.
     * Net effect, pinned here: the top-level file IS deleted, the nested
     * subdirectory and the profile folder itself are NOT, and the method
     * reports failure despite having partially mutated disk state. No
     * profile is expected to ever contain a nested directory in practice,
     * but the non-atomicity is real and worth having pinned.
     */
    @Test
    void forgetWithNestedDirectoryLeavesPartialStateAndReturnsFalse( @TempDir Path tempDir ) throws Exception
    {
        Path dir = profileDir( tempDir, "uuid-nested" );
        writeFile( dir.resolve( "a.txt" ), "top-level file" );
        writeFile( dir.resolve( "nested" ).resolve( "b.txt" ), "file inside a nested directory" );

        List< String > lines = runHarness( tempDir, "forget", "uuid-nested" );

        assertTrue( lines.contains( "FORGET:false" ), "unexpected output: " + lines );
        assertFalse( Files.exists( dir.resolve( "a.txt" ) ), "the top-level file should have been deleted" );
        assertTrue( Files.exists( dir.resolve( "nested" ).resolve( "b.txt" ) ),
                "the nested subdirectory could not be deleted by deleteIfExists and must survive" );
        assertTrue( Files.exists( dir ), "the profile folder itself must survive since it never became empty" );
    }

    // ===================================================================
    // Harness plumbing
    // ===================================================================

    private static Path configDir( Path tempDir )
    {
        return tempDir.resolve( "config" );
    }

    private static Path profilesRoot( Path tempDir )
    {
        return configDir( tempDir ).resolve( ProfileArchive.PROFILES_DIR );
    }

    private static Path profileDir( Path tempDir, String uuid )
    {
        return profilesRoot( tempDir ).resolve( uuid );
    }

    private static void writeFile( Path path, String content ) throws Exception
    {
        Files.createDirectories( path.getParent() );
        Files.writeString( path, content );
    }

    /**
     * Runs {@link ProfileArchiveSubprocessHarness} in a child JVM whose
     * working directory is {@code cwd}, reusing this test JVM's own java
     * executable and classpath. Returns stdout (with stderr merged in) as a
     * list of trimmed lines.
     *
     * <p>If this test JVM was itself launched under the JaCoCo agent (as it
     * is under {@code mvn test}), the exact same {@code -javaagent} flag is
     * forwarded to the child so the production code the child actually
     * executes — {@code list()}, {@code forget()}, the safe branches of
     * {@code archiveActive}/{@code activate} — is credited to the coverage
     * report instead of silently vanishing because it ran in an
     * uninstrumented process. The agent's default {@code append=true}
     * merges the child's execution data into the same {@code jacoco.exec}
     * this test JVM is already writing to.</p>
     */
    private static List< String > runHarness( Path cwd, String... harnessArgs ) throws Exception
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
        command.add( ProfileArchiveSubprocessHarness.class.getName() );
        command.addAll( List.of( harnessArgs ) );

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
        return lines;
    }

    /**
     * Finds the {@code -javaagent} flag JaCoCo's {@code prepare-agent} goal
     * added to this JVM's own launch command, if any, so it can be forwarded
     * to a harness subprocess. Returns {@code null} outside a JaCoCo-
     * instrumented run (e.g. running a single test from an IDE without the
     * Maven build), in which case the subprocess simply runs uninstrumented,
     * same as before this method existed.
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
