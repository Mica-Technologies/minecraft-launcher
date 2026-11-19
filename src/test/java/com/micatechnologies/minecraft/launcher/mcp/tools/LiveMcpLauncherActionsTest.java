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

import com.micatechnologies.minecraft.launcher.game.modpack.GameOptionalMod;
import com.micatechnologies.minecraft.launcher.game.modpack.ModPackDocument;
import com.micatechnologies.minecraft.launcher.game.modpack.ModPackFileEntry;
import com.micatechnologies.minecraft.launcher.game.modpack.OptionalModResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    // ---- add_mod_to_modpack hashes (issue 101) ------------------------------------------

    private static final LiveMcpLauncherActions.ModHashes JAR =
            new LiveMcpLauncherActions.ModHashes( "1".repeat( 40 ), "2".repeat( 64 ) );

    @Test
    void expectedHashesMustMatchTheDownloadedJar()
    {
        assertNull( LiveMcpLauncherActions.hashMismatch( JAR, null, null ), "nothing expected" );
        assertNull( LiveMcpLauncherActions.hashMismatch( JAR, "1".repeat( 40 ), "2".repeat( 64 ) ) );
        assertNotNull( LiveMcpLauncherActions.hashMismatch( JAR, null, "3".repeat( 64 ) ) );
        assertNotNull( LiveMcpLauncherActions.hashMismatch( JAR, "3".repeat( 40 ), null ) );
    }

    /**
     * The entry carries both hashes, SHA-256 first, and they survive the manifest's write, so
     * the sync can verify the jar and replace an older file at the same path.
     */
    @Test
    void anAddedModRecordsBothHashesInTheManifest()
    {
        ModPackDocument doc = ModPackDocument.blank();
        assertTrue( doc.addMod( LiveMcpLauncherActions.modEntry( "JEI", "https://example.test/jei.jar",
                                                                 "jei.jar", JAR ) ) );

        ModPackFileEntry written = ModPackDocument.fromJson( doc.toPrettyJson() ).mods().get( 0 );
        assertEquals( "jei.jar", written.getLocal() );
        assertEquals( "sha256", written.getHashType() );
        assertEquals( JAR.sha256(), written.getHash() );
        assertEquals( JAR.sha1(), written.getExtraHash( "sha1" ) );
    }

    /** A toggle's result names every other optional mod that switched, and when it applies. */
    @Test
    void anOptionalModToggleNamesWhatElseChanged()
    {
        List< GameOptionalMod > mods = List.of(
                new GameOptionalMod( "AUSM", "https://example.test/a.jar", "ausm.jar", null, true, false, "", false,
                                     List.of( "lib.jar" ), List.of( "fast.jar" ) ),
                new GameOptionalMod( "Lib", "https://example.test/l.jar", "lib.jar", null, true, false, "", false,
                                     null, null ),
                new GameOptionalMod( "Fast", "https://example.test/f.jar", "fast.jar", null, true, false, "", true,
                                     null, null ) );
        var outcome = LiveMcpLauncherActions.describeToggle(
                OptionalModResolver.toggle( mods, Map.of(), "ausm.jar", true ), "Alto", "AUSM", true );
        assertTrue( outcome.ok() );
        assertEquals( "Turned \"AUSM\" on in \"Alto\". Also turned on \"Lib\", which \"AUSM\" requires. "
                              + "Turned off \"Fast\", which conflicts with \"AUSM\". Takes effect the next time the "
                              + "pack launches or syncs.", outcome.message() );

        var refused = LiveMcpLauncherActions.describeToggle(
                OptionalModResolver.toggle( mods, Map.of(), "ghost.jar", true ), "Alto", "ghost.jar", true );
        assertFalse( refused.ok() );
    }

    /**
     * {@code create_modpack} accepts Cleanroom (any case) and, like the editor, names its installer
     * in {@code packForgeURL} as well as {@code packModLoaderURL}; an unknown loader is refused with
     * all four loaders named.
     */
    @Test
    void createModpackAcceptsCleanroomAndNamesItsInstallerInPackForgeUrl()
    {
        String url = "https://github.com/CleanroomMC/Cleanroom/releases/download/0.7.14-alpha/cleanroom-0.7.14-alpha-installer.jar";
        ModPackDocument doc = ModPackDocument.blank();
        assertNull( LiveMcpLauncherActions.applyLoader( doc, "Cleanroom", url ) );
        assertEquals( "cleanroom", doc.getString( "packModLoader" ) );
        assertEquals( url, doc.getString( "packModLoaderURL" ) );
        assertEquals( url, doc.getString( "packForgeURL" ) );

        ModPackDocument fabric = ModPackDocument.blank();
        assertNull( LiveMcpLauncherActions.applyLoader( fabric, "fabric", "https://meta.fabricmc.net/x" ) );
        assertEquals( "", fabric.getString( "packForgeURL" ), "only Cleanroom mirrors its URL" );

        String refused = LiveMcpLauncherActions.applyLoader( ModPackDocument.blank(), "quilt", null );
        assertNotNull( refused );
        assertTrue( refused.contains( "cleanroom" ), refused );
    }
}
