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

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * entirely.
 *
 * <p><b>Fixed 2026-09-04.</b> {@code ProfileArchive} now strips the leading
 * separator before resolving, so archived logins land in
 * {@code <profiles>/<uuid>/player.mica} as intended.
 * {@link #archiveThenActivateRoundTripsTheLoginFile(Path)} covers the round trip
 * that the bug made impossible, and
 * {@link #theSharedFilenameConstantIsUnsafeToResolveDirectly()} guards the
 * property of the shared constant that made the mistake easy to make.</p>
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
     * Regression guard for the fixed path bug. The shared constant
     * {@code AUTH_ACCOUNT_REMEMBERED_FILE_NAME} is built for string concatenation
     * onto a folder path, so it carries a leading separator — and
     * {@link Path#resolve(String)} treats a leading-separator argument as
     * <em>absolute</em>, discarding the parent entirely. Passing the constant
     * straight to {@code resolve} therefore lands at the filesystem root, which is
     * exactly what {@code ProfileArchive} used to do.
     *
     * <p>This test does not exercise {@code ProfileArchive} — it pins the property
     * of the constant that makes the mistake easy to repeat. Anyone reaching for
     * this constant with {@code resolve} in future has a failing-looking assertion
     * here explaining why they must strip the separator first. The fix itself is
     * covered end-to-end by
     * {@link #archiveThenActivateRoundTripsTheLoginFile(Path)}.</p>
     */
    @Test
    void theSharedFilenameConstantIsUnsafeToResolveDirectly()
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
                        "ProfileArchive must strip the separator before using it" );
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
     * Activating an archived profile copies its login file into the active slot.
     *
     * <p>This is the test that used to demonstrate the path bug: before the fix,
     * {@code activate} resolved its existence check to the filesystem root and so
     * reported "no login file" even for a correctly-placed profile. It now finds
     * and restores it.</p>
     */
    @Test
    void activateRestoresAnArchivedLoginIntoTheActiveSlot( @TempDir Path tempDir ) throws Exception
    {
        String uuid = "uuid-legit-profile";
        writeFile( profileDir( tempDir, uuid ).resolve( LOGIN_FILE_NAME ), "FAKE-ARCHIVED-LOGIN-CONTENT-NOT-REAL" );
        writeFile( profileDir( tempDir, uuid ).resolve( META_FILE ),
                "{\"uuid\":\"" + uuid + "\",\"displayName\":\"Steve\",\"lastUsedMs\":1000}" );

        List< String > lines = runHarness( tempDir, "activate", uuid );

        assertTrue( lines.contains( "ACTIVATE:true" ), "unexpected output: " + lines );
        assertEquals( "FAKE-ARCHIVED-LOGIN-CONTENT-NOT-REAL",
                Files.readString( configDir( tempDir ).resolve( LOGIN_FILE_NAME ) ),
                "the archived login must land in the active slot" );
    }

    /**
     * The round trip the path bug made impossible: archive the active login, then
     * activate it back. Before the fix, {@code archiveActive} copied to the
     * filesystem root (failing on any normal account) and {@code activate} looked
     * for it there, so profile switching could never carry a login across.
     *
     * <p>Sibling files are included because they travel with the credentials and
     * would have gone to the right place even while the login file did not —
     * making the failure look partial rather than total.</p>
     */
    @Test
    void archiveThenActivateRoundTripsTheLoginFile( @TempDir Path tempDir ) throws Exception
    {
        String uuid = "uuid-round-trip";
        writeFile( configDir( tempDir ).resolve( LOGIN_FILE_NAME ), "ACTIVE-LOGIN-CONTENT" );
        writeFile( configDir( tempDir ).resolve( "cached_user.json" ), "{\"cached\":true}" );

        List< String > archived = runHarness( tempDir, "archive", uuid, "Steve" );
        assertTrue( archived.stream().anyMatch( line -> line.startsWith( "ARCHIVE:OK:" ) ),
                "unexpected output: " + archived );
        assertTrue( Files.isRegularFile( profileDir( tempDir, uuid ).resolve( LOGIN_FILE_NAME ) ),
                "the login file must be archived inside the profile folder, not at the filesystem root" );

        Files.writeString( configDir( tempDir ).resolve( LOGIN_FILE_NAME ), "SOMEONE-ELSES-LOGIN" );

        List< String > activated = runHarness( tempDir, "activate", uuid );
        assertTrue( activated.contains( "ACTIVATE:true" ), "unexpected output: " + activated );
        assertEquals( "ACTIVE-LOGIN-CONTENT",
                Files.readString( configDir( tempDir ).resolve( LOGIN_FILE_NAME ) ),
                "activating must restore the archived login over whatever was in the active slot" );
        assertEquals( "{\"cached\":true}",
                Files.readString( configDir( tempDir ).resolve( "cached_user.json" ) ),
                "sibling files travel with the credentials" );
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
     * {@code forget()} removes the whole profile tree, nested directories included.
     *
     * <p>Before the fix it listed the folder once and called
     * {@code deleteIfExists} on each entry, swallowing the per-entry exception. A
     * non-empty subdirectory cannot be removed that way, so the folder — and the
     * supposedly forgotten credentials inside it — survived while the method
     * reported failure. "Forget my account" leaving credentials on disk is the
     * kind of failure a user would never notice.</p>
     */
    @Test
    void forgetRemovesNestedDirectoriesToo( @TempDir Path tempDir ) throws Exception
    {
        Path dir = profileDir( tempDir, "uuid-nested" );
        writeFile( dir.resolve( "a.txt" ), "top-level file" );
        writeFile( dir.resolve( "nested" ).resolve( "b.txt" ), "file inside a nested directory" );

        List< String > lines = runHarness( tempDir, "forget", "uuid-nested" );

        assertTrue( lines.contains( "FORGET:true" ), "unexpected output: " + lines );
        assertFalse( Files.exists( dir ), "nothing of the profile may survive being forgotten" );
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
