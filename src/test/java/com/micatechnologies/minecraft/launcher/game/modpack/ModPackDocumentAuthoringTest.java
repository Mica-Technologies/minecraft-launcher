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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ModPackDocument}'s authoring operations — fork, mod add/remove, and writing
 * a local manifest.
 *
 * <p>These exist to be driven by something other than a human at a keyboard: the MCP
 * {@code create_modpack} / {@code fork_modpack} / {@code add_mod_to_modpack} tools call them
 * with model-chosen arguments. So the properties worth pinning are the ones a careless caller
 * would otherwise get wrong.</p>
 *
 * <p><b>A fork must be independent.</b> Sharing state with its source would mean editing the
 * fork silently rewrote the pack it came from. <b>Adding a mod must not collide on local
 * path</b>, case-insensitively, or the installed result depends on sync order and differs
 * between Windows and Linux. And <b>writing a manifest must stay inside the directory it was
 * given</b>, whatever the pack calls itself.</p>
 */
class ModPackDocumentAuthoringTest
{
    // region forking

    @Test
    void aForkTakesTheNewNameAndBumpsTheMinorVersion()
    {
        ModPackDocument source = packNamed( "Original", "1.4.7" );
        ModPackDocument fork = source.forkOf( "My Fork" );

        assertEquals( "My Fork", fork.getString( ModPackDocument.KEY_PACK_NAME ) );
        assertEquals( "1.5.0", fork.getString( ModPackDocument.KEY_PACK_VERSION ) );
    }

    @Test
    void aForkCarriesEverythingElseOver()
    {
        ModPackDocument source = packNamed( "Original", "1.0.0" );
        source.putString( "packModLoader", "fabric" );
        source.putBool( "packUnstable", true );
        source.addMod( mod( "JEI", "mods/jei.jar" ) );

        ModPackDocument fork = source.forkOf( "My Fork" );

        assertEquals( "fabric", fork.getString( "packModLoader" ) );
        assertTrue( fork.getBool( "packUnstable" ) );
        assertEquals( 1, fork.mods().size() );
        assertEquals( "JEI", fork.mods().get( 0 ).getName() );
    }

    /**
     * The property that makes forking safe: editing the fork must not reach back into the pack
     * it came from. A shallow copy here would silently corrupt the user's original.
     */
    @Test
    void aForkIsIndependentOfItsSource()
    {
        ModPackDocument source = packNamed( "Original", "1.0.0" );
        source.addMod( mod( "JEI", "mods/jei.jar" ) );

        ModPackDocument fork = source.forkOf( "My Fork" );
        fork.addMod( mod( "REI", "mods/rei.jar" ) );
        fork.putString( "packModLoader", "fabric" );

        assertEquals( 1, source.mods().size(), "the source's mod list must not change" );
        assertEquals( "Original", source.getString( ModPackDocument.KEY_PACK_NAME ) );
        assertEquals( "forge", source.getString( "packModLoader" ) );
        assertEquals( 2, fork.mods().size() );
    }

    @Test
    void aForkNeedsAName()
    {
        ModPackDocument source = packNamed( "Original", "1.0.0" );
        assertThrows( IllegalArgumentException.class, () -> source.forkOf( null ) );
        assertThrows( IllegalArgumentException.class, () -> source.forkOf( "   " ) );
    }

    @Test
    void aForkNameIsTrimmed()
    {
        assertEquals( "My Fork",
                      packNamed( "Original", "1.0.0" ).forkOf( "  My Fork  " )
                              .getString( ModPackDocument.KEY_PACK_NAME ) );
    }

    // endregion

    // region adding mods

    @Test
    void aModIsAddedToTheList()
    {
        ModPackDocument doc = packNamed( "Pack", "1.0.0" );
        assertTrue( doc.addMod( mod( "JEI", "mods/jei.jar" ) ) );
        assertEquals( 1, doc.mods().size() );
        assertEquals( "mods/jei.jar", doc.mods().get( 0 ).getLocal() );
    }

    @Test
    void severalModsKeepTheirOrder()
    {
        ModPackDocument doc = packNamed( "Pack", "1.0.0" );
        doc.addMod( mod( "First", "mods/a.jar" ) );
        doc.addMod( mod( "Second", "mods/b.jar" ) );
        assertEquals( "First", doc.mods().get( 0 ).getName() );
        assertEquals( "Second", doc.mods().get( 1 ).getName() );
    }

    /**
     * Two entries writing the same file would make the installed result depend on sync order.
     */
    @Test
    void aDuplicateLocalPathIsRefused()
    {
        ModPackDocument doc = packNamed( "Pack", "1.0.0" );
        assertTrue( doc.addMod( mod( "JEI", "mods/jei.jar" ) ) );
        assertFalse( doc.addMod( mod( "JEI (again)", "mods/jei.jar" ) ) );
        assertEquals( 1, doc.mods().size() );
    }

    /**
     * Case-insensitively, because the launcher runs on Windows and macOS where the filesystem
     * is too — treating these as distinct would produce a manifest that behaves differently
     * per platform.
     */
    @Test
    void aDuplicateLocalPathIsRefusedRegardlessOfCase()
    {
        ModPackDocument doc = packNamed( "Pack", "1.0.0" );
        doc.addMod( mod( "JEI", "mods/JEI.jar" ) );
        assertFalse( doc.addMod( mod( "jei", "mods/jei.jar" ) ) );
        assertEquals( 1, doc.mods().size() );
    }

    @Test
    void anEntryWithNoLocalPathIsRefused()
    {
        ModPackDocument doc = packNamed( "Pack", "1.0.0" );
        assertFalse( doc.addMod( null ) );
        assertFalse( doc.addMod( mod( "Nameless", "" ) ) );
        assertFalse( doc.addMod( mod( "Nameless", "   " ) ) );
        assertTrue( doc.mods().isEmpty() );
    }

    @Test
    void anAddedModSurvivesASerializationRoundTrip()
    {
        ModPackDocument doc = packNamed( "Pack", "1.0.0" );
        ModPackFileEntry entry = mod( "JEI", "mods/jei.jar" );
        entry.setHash( "abc" );
        entry.setModrinthSlug( "jei" );
        doc.addMod( entry );

        ModPackDocument reloaded = ModPackDocument.fromJson( doc.toPrettyJson() );
        ModPackFileEntry read = reloaded.mods().get( 0 );
        assertEquals( "abc", read.getHash() );
        assertEquals( "jei", read.getModrinthSlug() );
    }

    // endregion

    // region removing mods

    @Test
    void aModIsRemovedByLocalPath()
    {
        ModPackDocument doc = packWithTwoMods();
        assertTrue( doc.removeMod( "mods/jei.jar" ) );
        assertEquals( 1, doc.mods().size() );
        assertEquals( "REI", doc.mods().get( 0 ).getName() );
    }

    @Test
    void aModIsRemovedByName()
    {
        ModPackDocument doc = packWithTwoMods();
        assertTrue( doc.removeMod( "JEI" ) );
        assertEquals( 1, doc.mods().size() );
    }

    @Test
    void aModIsRemovedByModrinthSlug()
    {
        ModPackDocument doc = packNamed( "Pack", "1.0.0" );
        ModPackFileEntry entry = mod( "Just Enough Items", "mods/jei.jar" );
        entry.setModrinthSlug( "jei" );
        doc.addMod( entry );

        assertTrue( doc.removeMod( "jei" ) );
        assertTrue( doc.mods().isEmpty() );
    }

    @Test
    void removalIsCaseInsensitiveAndTrimmed()
    {
        ModPackDocument doc = packWithTwoMods();
        assertTrue( doc.removeMod( "  jei  " ) );
        assertEquals( 1, doc.mods().size() );
    }

    @Test
    void removingSomethingAbsentReportsFailureAndChangesNothing()
    {
        ModPackDocument doc = packWithTwoMods();
        assertFalse( doc.removeMod( "not-installed" ) );
        assertFalse( doc.removeMod( null ) );
        assertFalse( doc.removeMod( "  " ) );
        assertEquals( 2, doc.mods().size() );
    }

    /**
     * At most one entry goes per call. An ambiguous request — two mods sharing a name — must
     * not quietly delete both.
     */
    @Test
    void onlyOneModIsRemovedPerCall()
    {
        ModPackDocument doc = packNamed( "Pack", "1.0.0" );
        doc.addMod( mod( "Shared", "mods/a.jar" ) );
        doc.addMod( mod( "Shared", "mods/b.jar" ) );

        assertTrue( doc.removeMod( "Shared" ) );
        assertEquals( 1, doc.mods().size() );
        assertEquals( "mods/b.jar", doc.mods().get( 0 ).getLocal() );
    }

    // endregion

    // region writing a local manifest

    @Test
    void aManifestIsWrittenAndItsUrlPointsAtIt( @TempDir Path tempDir ) throws IOException
    {
        ModPackDocument doc = packNamed( "My Pack", "1.0.0" );
        String url = doc.writeLocalManifest( tempDir );

        assertTrue( url.startsWith( "file:" ), url );
        Path written = Path.of( java.net.URI.create( url ) );
        assertTrue( Files.isRegularFile( written ) );
        assertEquals( "My Pack",
                      ModPackDocument.fromJson( Files.readString( written, StandardCharsets.UTF_8 ) )
                              .getString( ModPackDocument.KEY_PACK_NAME ) );
    }

    @Test
    void aMissingDirectoryIsCreated( @TempDir Path tempDir ) throws IOException
    {
        Path nested = tempDir.resolve( "imported-manifests" );
        packNamed( "Pack", "1.0.0" ).writeLocalManifest( nested );
        assertTrue( Files.isDirectory( nested ) );
    }

    /**
     * The containment property. Pack names are author-supplied, and a fork's name can come
     * straight from a model, so the filename is derived from the sanitized base name rather
     * than the raw one — a name full of separators cannot steer the write out of the directory
     * it was handed.
     */
    @Test
    void aHostilePackNameCannotEscapeTheTargetDirectory( @TempDir Path tempDir ) throws IOException
    {
        for ( String hostile : new String[]{ "../escaped", "../../etc/passwd", "a/b/c",
                                             "..\\\\windows", "with\nnewline" } ) {
            ModPackDocument doc = packNamed( hostile, "1.0.0" );
            Path written = Path.of( java.net.URI.create( doc.writeLocalManifest( tempDir ) ) );
            assertEquals( tempDir.toRealPath(), written.getParent().toRealPath(),
                          "escaped the target directory with name: " + hostile );
        }
    }

    @Test
    void aNameThatSanitizesToNothingIsRefused( @TempDir Path tempDir )
    {
        ModPackDocument doc = packNamed( "!!! ---", "1.0.0" );
        assertThrows( IllegalArgumentException.class, () -> doc.writeLocalManifest( tempDir ) );
    }

    @Test
    void aNullDirectoryIsRefused()
    {
        ModPackDocument doc = packNamed( "Pack", "1.0.0" );
        assertThrows( IllegalArgumentException.class, () -> doc.writeLocalManifest( null ) );
    }

    /**
     * Names that differ only in case, spacing or punctuation share a file. Creating a new pack
     * must therefore refuse to write over an existing one rather than silently replace another
     * pack's manifest.
     */
    @Test
    void aNearDuplicateNameCannotOverwriteWhenOverwritingIsRefused( @TempDir Path tempDir ) throws IOException
    {
        String first = packNamed( "My Pack", "1.0.0" ).writeLocalManifest( tempDir, false );

        assertThrows( java.nio.file.FileAlreadyExistsException.class,
                      () -> packNamed( "my-pack", "9.9.9" ).writeLocalManifest( tempDir, false ) );
        assertEquals( "My Pack",
                      ModPackDocument.fromJson( Files.readString( Path.of( java.net.URI.create( first ) ),
                                                                  StandardCharsets.UTF_8 ) )
                              .getString( ModPackDocument.KEY_PACK_NAME ),
                      "the original manifest must be untouched" );
    }

    @Test
    void theLocalManifestPathIsDerivedFromTheSanitizedLowerCasedName( @TempDir Path tempDir )
    {
        assertEquals( tempDir.resolve( ModPackDocument.LOCAL_MANIFEST_PREFIX + "mypack.json" ),
                      packNamed( "My Pack!", "1.0.0" ).localManifestPathIn( tempDir ) );
        assertEquals( "mypack", ModPackDocument.localManifestKeyOf( "my-PACK" ) );
        assertEquals( "", ModPackDocument.localManifestKeyOf( "!!! ---" ) );
        assertEquals( "", ModPackDocument.localManifestKeyOf( null ) );
    }

    /** Rewriting the same pack overwrites rather than accumulating stale manifests. */
    @Test
    void rewritingTheSamePackReusesTheSameFile( @TempDir Path tempDir ) throws IOException
    {
        ModPackDocument doc = packNamed( "Pack", "1.0.0" );
        String first = doc.writeLocalManifest( tempDir );
        doc.putString( ModPackDocument.KEY_PACK_VERSION, "2.0.0" );
        String second = doc.writeLocalManifest( tempDir );

        assertEquals( first, second );
        try ( var files = Files.list( tempDir ) ) {
            assertEquals( 1, files.count() );
        }
        assertEquals( "2.0.0",
                      ModPackDocument.fromJson( Files.readString( Path.of( java.net.URI.create( second ) ),
                                                                  StandardCharsets.UTF_8 ) )
                              .getString( ModPackDocument.KEY_PACK_VERSION ) );
    }

    // endregion

    // region helpers

    private static ModPackDocument packNamed( String name, String version )
    {
        ModPackDocument doc = ModPackDocument.blank();
        doc.putString( ModPackDocument.KEY_PACK_NAME, name );
        doc.putString( ModPackDocument.KEY_PACK_VERSION, version );
        return doc;
    }

    private static ModPackDocument packWithTwoMods()
    {
        ModPackDocument doc = packNamed( "Pack", "1.0.0" );
        doc.addMod( mod( "JEI", "mods/jei.jar" ) );
        doc.addMod( mod( "REI", "mods/rei.jar" ) );
        return doc;
    }

    private static ModPackFileEntry mod( String name, String local )
    {
        return new ModPackFileEntry( name, "https://example.test/" + name + ".jar", local,
                                     "", "sha1", true, true );
    }

    // endregion
}
