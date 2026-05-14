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

import com.micatechnologies.minecraft.launcher.files.LocalPathManager;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ManagedGameFile#isJarUrlContainedInLauncher(String)}.
 *
 * <p>The gate exists because the launcher's primary URL-scheme rule is
 * "https only" — a modpack JSON can't redirect a download to anywhere
 * but https. The exception is {@code jar:file:} URLs synthesized
 * internally by the Forge extraction pipeline (pulling embedded Maven
 * artifacts out of a hash-verified modpack.jar that lives in the
 * launcher's own installs folder). If this gate ever stops accepting
 * those, every Forge-based modpack launch breaks with the "Refusing
 * managed file with non-https URL scheme" exception we already shipped
 * a fix for. If it ever starts accepting URLs OUTSIDE the launcher
 * folder, we hand a hostile modpack JSON the ability to read arbitrary
 * files via a {@code jar:file:/etc/passwd!/} URL — a real attack we
 * specifically defended against. Both regressions are silent until
 * production, so they each get a test.</p>
 */
class ManagedGameFileUrlGateTest
{
    @Test
    void acceptsJarFileUrlUnderLauncherRoot()
    {
        // Build a URL whose inner file path is rooted at whatever
        // LocalPathManager reports as the launcher folder. This is the
        // exact shape GameModLoaderForge produces when resolving
        // embedded Maven artifacts out of a downloaded Forge installer.
        String root = LocalPathManager.getLauncherLocalPath();
        String url = "jar:" + fileUrlOf( root + "/installs/pack/bin/modpack.jar" )
                            + "!/maven/net/minecraftforge/forge/forge-1.12.2.jar";
        assertTrue( ManagedGameFile.isJarUrlContainedInLauncher( url ),
                     "Forge embedded jar URL under the launcher root must be accepted" );
    }

    @Test
    void rejectsJarFileUrlOutsideLauncherRoot()
    {
        // jar:file:/etc/passwd!/anything — classic local-file-read shape.
        // Independent of the launcher root, this absolute-path-into-
        // unrelated-area pattern must NOT pass the gate.
        String url = "jar:file:/etc/passwd!/whatever";
        assertFalse( ManagedGameFile.isJarUrlContainedInLauncher( url ),
                      "jar:file: URL pointing outside the launcher folder"
                              + " must be rejected — that's the entire reason"
                              + " the gate exists" );
    }

    @Test
    void rejectsJarHttpUrl()
    {
        // jar:http://attacker/x.jar!/payload — a malicious manifest could
        // try this to dodge the "https only" rule via the jar: wrapper.
        // The gate only allows jar:file:, never jar:http: / jar:https: /
        // jar:gopher:.
        String url = "jar:http://attacker.example/x.jar!/payload.class";
        assertFalse( ManagedGameFile.isJarUrlContainedInLauncher( url ),
                      "jar: URLs wrapping non-file inner schemes must be rejected" );
    }

    @Test
    void rejectsMalformedJarUrl()
    {
        // No "!/" entry separator — not a valid jar: URL. The gate
        // should default closed rather than throw.
        assertFalse( ManagedGameFile.isJarUrlContainedInLauncher( "jar:file:/whatever" ),
                      "jar: URLs without the !/ entry separator must be rejected" );
    }

    @Test
    void rejectsNonJarScheme()
    {
        // Anything that isn't a jar: URL at all — the caller is
        // expected to check the scheme first, but the gate must still
        // refuse if invoked with the wrong shape.
        assertFalse( ManagedGameFile.isJarUrlContainedInLauncher( "file:///etc/passwd" ),
                      "Non-jar: URLs must be rejected" );
        assertFalse( ManagedGameFile.isJarUrlContainedInLauncher( "https://example.com/x.jar" ),
                      "Non-jar: URLs must be rejected even when otherwise harmless" );
    }

    @Test
    void rejectsNullOrEmpty()
    {
        assertFalse( ManagedGameFile.isJarUrlContainedInLauncher( null ) );
        assertFalse( ManagedGameFile.isJarUrlContainedInLauncher( "" ) );
    }

    @Test
    void rejectsTraversalAttemptOutOfLauncherRoot()
    {
        // ../../.. components inside the inner file path should NOT
        // let the URL escape the containment check. We normalize before
        // comparing, so a "looks-like-under-root-but-actually-isn't"
        // shape must still fall through to false.
        String root = LocalPathManager.getLauncherLocalPath();
        String url = "jar:" + fileUrlOf( root + "/../../etc/passwd.jar" )
                            + "!/payload.class";
        assertFalse( ManagedGameFile.isJarUrlContainedInLauncher( url ),
                      "Traversal segments escaping the launcher root must be rejected" );
    }

    /** Builds a {@code file:} URL string from an absolute path. Uses
     *  {@link Path#toUri()} so the result is portable across platform
     *  separators — Windows drive-letter paths become
     *  {@code file:///C:/...}, Unix paths become {@code file:///foo/...}. */
    private static String fileUrlOf( String absPath )
    {
        return Path.of( absPath ).toUri().toString();
    }
}
