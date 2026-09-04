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

import com.micatechnologies.minecraft.launcher.consts.RuntimeConstants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link RuntimeManager}'s runtime-manifest containment gate
 * ({@code isUnsafeRuntimeManifestEntryName} / {@code resolveWithinRuntimeBase}), the per-component
 * folder path builder ({@code getComponentRuntimeFolderPath}), and the installed-runtime scan
 * ({@code getInstalledRuntimes(File)}) — all widened to package-private and (for the containment
 * gate) extracted from {@code verifyRuntimeImpl} for direct testing.
 *
 * <p>Why this matters: the containment gate is the only thing standing between a runtime-manifest
 * file entry (fetched from Mojang's endpoint — externally sourced data, defense-in-depth applies
 * the same as any other downloaded manifest) and writing a file anywhere on disk the launcher
 * process can reach, via a {@code ../../..} relative path or an absolute path that ignores the
 * runtime folder entirely. This mirrors the zip-slip class of bug that
 * {@code ArchiveExtractor}/{@code ManagedGameFile} guard against elsewhere in the codebase — before
 * this extraction, the check lived inline in a 300-line method and had zero direct coverage.
 * {@code getInstalledRuntimes} feeds the runtime-management UI list; a wrong size or version
 * there is a cosmetic bug, not a launch failure, but it is pure scanning logic that costs nothing
 * to pin once extracted to accept an arbitrary root.</p>
 */
class RuntimeManifestContainmentTest
{
    // =========================================================================
    //  isUnsafeRuntimeManifestEntryName
    // =========================================================================

    @Test
    void ordinaryRelativePathsAreSafe()
    {
        assertFalse( RuntimeManager.isUnsafeRuntimeManifestEntryName( "bin/java" ) );
        assertFalse( RuntimeManager.isUnsafeRuntimeManifestEntryName( "lib/modules" ) );
        assertFalse( RuntimeManager.isUnsafeRuntimeManifestEntryName( "release" ) );
    }

    @Test
    void embeddedNulByteIsUnsafe()
    {
        assertTrue( RuntimeManager.isUnsafeRuntimeManifestEntryName( "bin/java\0.exe" ) );
    }

    @Test
    void unixStyleAbsolutePathIsUnsafe()
    {
        assertTrue( RuntimeManager.isUnsafeRuntimeManifestEntryName( "/etc/passwd" ) );
    }

    @Test
    void windowsStyleAbsolutePathIsUnsafe()
    {
        assertTrue( RuntimeManager.isUnsafeRuntimeManifestEntryName( "\\Windows\\System32\\evil.dll" ) );
    }

    @Test
    void windowsDriveLetterPathIsUnsafe()
    {
        assertTrue( RuntimeManager.isUnsafeRuntimeManifestEntryName( "C:\\Windows\\System32\\evil.dll" ) );
    }

    /**
     * A relative path that merely traverses upward ({@code ../}) is not rejected by the
     * name-shape check — that class of escape is caught by {@code resolveWithinRuntimeBase}'s
     * normalize-and-contain check instead. Pinned so the two-stage design (cheap shape check,
     * then resolve-and-verify) doesn't quietly collapse into one stage that only catches part of
     * the attack surface.
     */
    @Test
    void parentDirectoryTraversalAloneIsNotCaughtByTheNameShapeCheck()
    {
        assertFalse( RuntimeManager.isUnsafeRuntimeManifestEntryName( "../../etc/passwd" ) );
    }

    // =========================================================================
    //  resolveWithinRuntimeBase
    // =========================================================================

    @Test
    void resolvesAnOrdinaryEntryUnderTheBase( @TempDir File tempDir )
    {
        Path base = tempDir.toPath().toAbsolutePath().normalize();
        Path resolved = RuntimeManager.resolveWithinRuntimeBase( base, "bin/java" );

        assertEquals( base.resolve( "bin/java" ).normalize(), resolved );
    }

    @Test
    void rejectsAParentDirectoryEscapeFromTheBase( @TempDir File tempDir )
    {
        Path base = tempDir.toPath().toAbsolutePath().normalize();
        assertNull( RuntimeManager.resolveWithinRuntimeBase( base, "../../../../etc/passwd" ),
                    "an entry that normalizes outside the runtime base must be rejected" );
    }

    @Test
    void rejectsAnEscapeThatTunnelsThroughASiblingWithASharedPrefix( @TempDir File tempDir )
    {
        // "runtime-evil" shares the "runtime" prefix with a base named "runtime" but is a
        // different directory entirely -- a naive String#startsWith on unresolved paths (rather
        // than Path#startsWith on normalized paths) would wrongly accept this.
        Path base = tempDir.toPath().resolve( "runtime" ).toAbsolutePath().normalize();
        assertNull( RuntimeManager.resolveWithinRuntimeBase( base, "../runtime-evil/payload" ) );
    }

    @Test
    void aBareDotEntryResolvesToTheBaseItself( @TempDir File tempDir )
    {
        Path base = tempDir.toPath().toAbsolutePath().normalize();
        assertEquals( base, RuntimeManager.resolveWithinRuntimeBase( base, "." ) );
    }

    // =========================================================================
    //  getComponentRuntimeFolderPath
    // =========================================================================

    @Test
    void componentFolderPathIsNestedUnderTheRuntimeRootAndNamedForTheComponent()
    {
        String path = RuntimeManager.getComponentRuntimeFolderPath( "java-runtime-gamma" );

        assertTrue( path.startsWith( LocalPathManager.getLauncherRuntimeFolderPath() ),
                    "expected the component path to be nested under the runtime root, got: " + path );
        assertTrue( path.endsWith( "java-runtime-gamma" ) );
    }

    @Test
    void differentComponentsResolveToDifferentFolders()
    {
        assertFalse( RuntimeManager.getComponentRuntimeFolderPath( "jre-legacy" )
                             .equals( RuntimeManager.getComponentRuntimeFolderPath( "java-runtime-delta" ) ) );
    }

    // =========================================================================
    //  getInstalledRuntimes(File)
    // =========================================================================

    @Test
    void nonExistentRuntimeRootYieldsAnEmptyList( @TempDir File tempDir )
    {
        File missing = new File( tempDir, "does-not-exist" );
        assertTrue( RuntimeManager.getInstalledRuntimes( missing ).isEmpty() );
    }

    @Test
    void aFilePassedAsRuntimeRootYieldsAnEmptyListRatherThanThrowing( @TempDir File tempDir ) throws IOException
    {
        File notADirectory = new File( tempDir, "not-a-dir" );
        assertTrue( notADirectory.createNewFile() );
        assertTrue( RuntimeManager.getInstalledRuntimes( notADirectory ).isEmpty() );
    }

    @Test
    void skipsAComponentFolderMissingTheVersionMarker( @TempDir File tempDir ) throws IOException
    {
        File component = new File( tempDir, "java-runtime-gamma" );
        assertTrue( component.mkdirs() );
        // No .version file written -- should be skipped rather than reported with a fabricated version.

        assertTrue( RuntimeManager.getInstalledRuntimes( tempDir ).isEmpty() );
    }

    @Test
    void reportsAnInstalledComponentWithItsVersionAndSize( @TempDir File tempDir ) throws IOException
    {
        File component = new File( tempDir, "jre-legacy" );
        assertTrue( component.mkdirs() );
        Files.writeString( new File( component, RuntimeConstants.RUNTIME_VERSION_FILE_NAME ).toPath(),
                           "8u392" );
        // A small payload file so sizeOfDirectory has something non-zero to sum, without needing
        // to assert an exact byte count (that's incidental filesystem detail, not the contract).
        Files.writeString( new File( component, "payload.bin" ).toPath(), "hello world" );

        List< Map< String, String > > runtimes = RuntimeManager.getInstalledRuntimes( tempDir );

        assertEquals( 1, runtimes.size() );
        Map< String, String > info = runtimes.get( 0 );
        assertEquals( "jre-legacy", info.get( "component" ) );
        assertEquals( "8u392", info.get( "version" ) );
        assertEquals( component.getAbsolutePath(), info.get( "path" ) );
        assertTrue( info.containsKey( "sizeMB" ) );
    }

    @Test
    void ignoresPlainFilesDirectlyUnderTheRuntimeRoot( @TempDir File tempDir ) throws IOException
    {
        assertTrue( new File( tempDir, "stray-file.txt" ).createNewFile() );
        assertTrue( RuntimeManager.getInstalledRuntimes( tempDir ).isEmpty() );
    }

    /**
     * The version string is trimmed of surrounding whitespace -- a manifest/runtime install that
     * wrote a trailing newline to the marker file must not surface it in the reported version.
     */
    @Test
    void versionMarkerContentIsTrimmed( @TempDir File tempDir ) throws IOException
    {
        File component = new File( tempDir, "java-runtime-delta" );
        assertTrue( component.mkdirs() );
        Files.writeString( new File( component, RuntimeConstants.RUNTIME_VERSION_FILE_NAME ).toPath(),
                           "21.0.1\n" );

        List< Map< String, String > > runtimes = RuntimeManager.getInstalledRuntimes( tempDir );
        assertEquals( "21.0.1", runtimes.get( 0 ).get( "version" ) );
    }
}
