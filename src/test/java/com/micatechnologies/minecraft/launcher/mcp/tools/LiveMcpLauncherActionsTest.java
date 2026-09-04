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

package com.micatechnologies.minecraft.launcher.mcp.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests for the containment rule in {@link LiveMcpLauncherActions} that decides which manifests
 * the launcher may edit in place.
 *
 * <p>Why this specific check, out of everything in that class: the rest of it drives launcher
 * singletons that a unit test cannot stand up, but this is the gate that decides whether a
 * model-initiated edit is permitted at all. "Does this URL point inside our directory?" is
 * exactly the sort of question that is easy to answer subtly wrongly — a prefix comparison on
 * strings would accept a sibling directory whose name merely starts the same way, and skipping
 * normalization would accept a path that threads {@code ..} straight back out.</p>
 *
 * <p>Editing a manifest the launcher does not own is not a crash, it is a slow-motion data
 * loss: the local copy diverges from the URL it claims to come from, and the next update
 * overwrites the edit without warning.</p>
 */
class LiveMcpLauncherActionsTest
{
    @Test
    void aManifestInsideTheAuthoredDirectoryIsAccepted( @TempDir Path tempDir )
    {
        Path authored = tempDir.resolve( "mcp-manifests" );
        Path manifest = authored.resolve( "created-mypack.json" );

        Path resolved = LiveMcpLauncherActions.resolveAuthoredManifest( manifest.toUri().toString(),
                                                                        authored );
        assertNotNull( resolved );
        assertEquals( manifest.toAbsolutePath().normalize(), resolved );
    }

    @Test
    void aManifestInANestedSubdirectoryIsStillInside( @TempDir Path tempDir )
    {
        Path authored = tempDir.resolve( "mcp-manifests" );
        Path manifest = authored.resolve( "nested" ).resolve( "created-mypack.json" );
        assertNotNull( LiveMcpLauncherActions.resolveAuthoredManifest( manifest.toUri().toString(),
                                                                       authored ) );
    }

    /**
     * The user's own imported manifest is local, but it is not ours. A {@code file:} scheme
     * alone must not qualify.
     */
    @Test
    void aLocalManifestOutsideTheAuthoredDirectoryIsRejected( @TempDir Path tempDir )
    {
        Path authored = tempDir.resolve( "mcp-manifests" );
        Path imported = tempDir.resolve( "imported-manifests" ).resolve( "mmcjson-theirs.json" );
        assertNull( LiveMcpLauncherActions.resolveAuthoredManifest( imported.toUri().toString(),
                                                                    authored ) );
    }

    /**
     * A sibling whose name merely begins with the same characters is outside. This is what a
     * string prefix comparison would get wrong; {@code Path.startsWith} compares whole name
     * elements.
     */
    @Test
    void aSiblingDirectoryWithASharedNamePrefixIsRejected( @TempDir Path tempDir )
    {
        Path authored = tempDir.resolve( "mcp-manifests" );
        Path lookalike = tempDir.resolve( "mcp-manifests-evil" ).resolve( "created-pack.json" );
        assertNull( LiveMcpLauncherActions.resolveAuthoredManifest( lookalike.toUri().toString(),
                                                                    authored ) );
    }

    /** Normalization happens before comparison, so a path cannot thread its way back out. */
    @Test
    void aPathThatEscapesViaDotDotIsRejected( @TempDir Path tempDir )
    {
        Path authored = tempDir.resolve( "mcp-manifests" );
        Path escaping = authored.resolve( ".." ).resolve( "elsewhere" ).resolve( "pack.json" );
        assertNull( LiveMcpLauncherActions.resolveAuthoredManifest( escaping.toUri().toString(),
                                                                    authored ) );
    }

    /** The directory itself is not a manifest. */
    @Test
    void theAuthoredDirectoryItselfIsRejected( @TempDir Path tempDir )
    {
        Path authored = tempDir.resolve( "mcp-manifests" );
        assertNull( LiveMcpLauncherActions.resolveAuthoredManifest( authored.toUri().toString(),
                                                                    authored ) );
    }

    /** A remote pack is the common case, and is never editable in place. */
    @Test
    void aRemoteManifestUrlIsRejected( @TempDir Path tempDir )
    {
        Path authored = tempDir.resolve( "mcp-manifests" );
        for ( String url : new String[]{ "https://example.test/pack.json",
                                         "http://example.test/pack.json",
                                         "ftp://example.test/pack.json" } ) {
            assertNull( LiveMcpLauncherActions.resolveAuthoredManifest( url, authored ), url );
        }
    }

    @Test
    void missingOrMalformedInputIsRejected( @TempDir Path tempDir )
    {
        Path authored = tempDir.resolve( "mcp-manifests" );
        assertNull( LiveMcpLauncherActions.resolveAuthoredManifest( null, authored ) );
        assertNull( LiveMcpLauncherActions.resolveAuthoredManifest( "", authored ) );
        assertNull( LiveMcpLauncherActions.resolveAuthoredManifest( "file:not a uri", authored ) );
        assertNull( LiveMcpLauncherActions.resolveAuthoredManifest(
                tempDir.resolve( "x.json" ).toUri().toString(), null ) );
    }
}
