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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code packOptionalMods} in the headless manifest document: reading and writing its extra
 * fields, its side defaults, and proof that every path that rewrites a manifest (fork, editor
 * save, the MCP add/remove mod edits) keeps the list intact. Also the editor's validation rules.
 */
class ModPackDocumentOptionalModsTest
{
    private static final String MANIFEST = """
            {
              "packName": "Alto",
              "packVersion": "1.2.0",
              "packMods": [
                { "name": "JEI", "remote": "https://example.invalid/jei.jar", "local": "jei.jar",
                  "sha1": "1111111111111111111111111111111111111111", "md5": "-1", "sha256": "-1",
                  "clientReq": true, "serverReq": true }
              ],
              "packOptionalMods": [
                { "name": "AUSM", "remote": "https://example.invalid/ausm.jar", "local": "ausm.jar",
                  "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "description": "Shader pack support. Slower terrain rendering.",
                  "defaultEnabled": false, "requires": ["lib.jar"], "conflictsWith": ["fast.jar"] },
                { "name": "Lib", "remote": "https://example.invalid/lib.jar", "local": "lib.jar",
                  "defaultEnabled": true, "serverReq": true },
                { "name": "Fast", "remote": "https://example.invalid/fast.jar", "local": "fast.jar" }
              ]
            }
            """;

    /** Echoes keys and arguments so assertions do not depend on the locale. */
    private static String echo( String key, Object... args )
    {
        StringBuilder sb = new StringBuilder( key );
        for ( Object arg : args ) {
            sb.append( '|' ).append( arg );
        }
        return sb.toString();
    }

    private static JsonArray optionalArray( ModPackDocument doc )
    {
        return doc.json().getAsJsonArray( "packOptionalMods" );
    }

    @Test
    void readingKeepsTheExtraFieldsAndOptionalSideDefaults()
    {
        List< ModPackFileEntry > optional = ModPackDocument.fromJson( MANIFEST ).optionalMods();
        assertEquals( 3, optional.size() );

        ModPackFileEntry ausm = optional.get( 0 );
        assertEquals( "AUSM", ausm.getName() );
        assertEquals( "Shader pack support. Slower terrain rendering.", ausm.getDescription() );
        assertFalse( ausm.isDefaultEnabled() );
        assertEquals( List.of( "lib.jar" ), ausm.getRequires() );
        assertEquals( List.of( "fast.jar" ), ausm.getConflictsWith() );
        assertTrue( ausm.isClientReq(), "absent clientReq means client-side" );
        assertFalse( ausm.isServerReq(), "absent serverReq means client-only for optional mods" );
        assertEquals( "sha256", ausm.getHashType() );

        assertTrue( optional.get( 1 ).isDefaultEnabled() );
        assertTrue( optional.get( 1 ).isServerReq() );
    }

    @Test
    void writingRoundTripsEveryField()
    {
        ModPackDocument doc = ModPackDocument.fromJson( MANIFEST );
        JsonArray before = optionalArray( doc ).deepCopy();
        doc.writeFileList( ModPackDocument.FileList.OPTIONAL_MODS, doc.optionalMods() );

        ModPackDocument reread = ModPackDocument.fromJson( doc.toPrettyJson() );
        List< ModPackFileEntry > optional = reread.optionalMods();
        assertEquals( 3, optional.size() );
        assertEquals( "Shader pack support. Slower terrain rendering.", optional.get( 0 ).getDescription() );
        assertEquals( List.of( "lib.jar" ), optional.get( 0 ).getRequires() );
        assertEquals( List.of( "fast.jar" ), optional.get( 0 ).getConflictsWith() );
        assertFalse( optional.get( 0 ).isServerReq() );
        assertTrue( optional.get( 1 ).isDefaultEnabled() );

        JsonObject ausm = optionalArray( reread ).get( 0 ).getAsJsonObject();
        assertEquals( before.get( 0 ).getAsJsonObject().get( "sha256" ), ausm.get( "sha256" ) );
        assertFalse( ausm.get( "serverReq" ).getAsBoolean(),
                     "a save writes the explicit false, so a server never reads it as true" );
    }

    @Test
    void anEmptyListLeavesTheKeyOut()
    {
        ModPackDocument doc = ModPackDocument.fromJson( "{\"packName\":\"P\",\"packVersion\":\"1\",\"packMods\":[]}" );
        doc.writeFileList( ModPackDocument.FileList.OPTIONAL_MODS, new ArrayList<>() );
        assertFalse( doc.json().has( "packOptionalMods" ) );
        assertTrue( doc.optionalMods().isEmpty() );
    }

    @Test
    void theGameModelReadsWhatTheDocumentWrites()
    {
        ModPackDocument doc = ModPackDocument.fromJson( MANIFEST );
        doc.writeFileList( ModPackDocument.FileList.OPTIONAL_MODS, doc.optionalMods() );
        List< GameOptionalMod > parsed = OptionalMods.parse( doc.toPrettyJson() );
        assertEquals( 3, parsed.size() );
        assertFalse( parsed.get( 0 ).isServerSide() );
        assertEquals( List.of( "lib.jar" ), parsed.get( 0 ).getRequires() );
        assertTrue( parsed.get( 1 ).isDefaultEnabled() );
    }

    // region every rewrite path keeps the list (issue acceptance item 5)

    @Test
    void forkingKeepsTheOptionalList()
    {
        ModPackDocument source = ModPackDocument.fromJson( MANIFEST );
        ModPackDocument fork = source.forkOf( "Alto Fork" );
        assertEquals( optionalArray( source ), optionalArray( fork ) );
    }

    @Test
    void anEditorSaveKeepsTheOptionalList()
    {
        // The editor reads every list into rows and writes every list back on save.
        ModPackDocument doc = ModPackDocument.fromJson( MANIFEST );
        for ( ModPackDocument.FileList list : ModPackDocument.FileList.values() ) {
            doc.writeFileList( list, doc.readFileList( list ) );
        }
        List< ModPackFileEntry > optional = ModPackDocument.fromJson( doc.toPrettyJson() ).optionalMods();
        assertEquals( List.of( "ausm.jar", "lib.jar", "fast.jar" ),
                      optional.stream().map( ModPackFileEntry::getLocal ).toList() );
        assertEquals( List.of( "fast.jar" ), optional.get( 0 ).getConflictsWith() );
    }

    @Test
    void addingAModKeepsTheOptionalList()
    {
        ModPackDocument doc = ModPackDocument.fromJson( MANIFEST );
        JsonArray before = optionalArray( doc ).deepCopy();
        assertTrue( doc.addMod( new ModPackFileEntry( "Other", "https://example.invalid/o.jar", "o.jar", "", "sha1",
                                                      true, true ) ) );
        assertEquals( before, optionalArray( ModPackDocument.fromJson( doc.toPrettyJson() ) ) );
    }

    @Test
    void removingAModKeepsTheOptionalList()
    {
        ModPackDocument doc = ModPackDocument.fromJson( MANIFEST );
        JsonArray before = optionalArray( doc ).deepCopy();
        assertTrue( doc.removeMod( "jei.jar" ) );
        assertEquals( before, optionalArray( ModPackDocument.fromJson( doc.toPrettyJson() ) ) );
    }

    @Test
    void removeModDoesNotTouchOptionalMods()
    {
        ModPackDocument doc = ModPackDocument.fromJson( MANIFEST );
        assertFalse( doc.removeMod( "ausm.jar" ) );
        assertEquals( 3, doc.optionalMods().size() );
    }

    @Test
    void addingAModWhoseFileIsAnOptionalModIsRefused()
    {
        ModPackDocument doc = ModPackDocument.fromJson( MANIFEST );
        assertFalse( doc.addMod( new ModPackFileEntry( "AUSM", "https://example.invalid/a.jar", "mods/AUSM.jar", "",
                                                       "sha1", true, true ) ) );
        assertEquals( 1, doc.mods().size() );
    }

    // endregion

    // region validation

    @Test
    void aValidPackHasNoOptionalProblems()
    {
        assertTrue( ModPackDocument.fromJson( MANIFEST ).validateOptionalMods( ModPackDocumentOptionalModsTest::echo )
                                   .isEmpty() );
        assertTrue( ModPackDocument.fromJson( MANIFEST ).validate( ModPackDocumentOptionalModsTest::echo ).isEmpty() );
    }

    @Test
    void aFileInBothListsIsRefused()
    {
        ModPackDocument doc = ModPackDocument.fromJson( MANIFEST );
        List< ModPackFileEntry > optional = doc.optionalMods();
        optional.add( new ModPackFileEntry( "JEI", "https://example.invalid/jei.jar", "JEI.jar", "", "sha1",
                                            true, false ) );
        doc.writeFileList( ModPackDocument.FileList.OPTIONAL_MODS, optional );
        assertEquals( List.of( "editor.validate.optional.inBothLists|JEI.jar" ),
                      doc.validateOptionalMods( ModPackDocumentOptionalModsTest::echo ) );
    }

    @Test
    void anOptionalModMustBeAPlainFileName()
    {
        ModPackDocument doc = ModPackDocument.fromJson( MANIFEST );
        List< ModPackFileEntry > optional = doc.optionalMods();
        optional.get( 1 ).setLocal( "mods/lib.jar" );
        optional.get( 2 ).setLocal( "extras\\fast.jar" );
        doc.writeFileList( ModPackDocument.FileList.OPTIONAL_MODS, optional );
        assertEquals( List.of( "editor.validate.optional.folder|mods/lib.jar|lib.jar",
                               "editor.validate.optional.folder|extras\\fast.jar|fast.jar",
                               "editor.validate.optional.unknownConflict|ausm.jar|fast.jar" ),
                      doc.validateOptionalMods( ModPackDocumentOptionalModsTest::echo ) );
    }

    @Test
    void requiresAndConflictsMustNameOptionalModsOfThisPack()
    {
        ModPackDocument doc = ModPackDocument.fromJson( MANIFEST );
        List< ModPackFileEntry > optional = doc.optionalMods();
        optional.get( 0 ).setRequires( List.of( "lib.jar", "jei.jar" ) );
        optional.get( 0 ).setConflictsWith( List.of( "nothing.jar" ) );
        optional.get( 2 ).setRequires( List.of( "mods/FAST.jar" ) );
        doc.writeFileList( ModPackDocument.FileList.OPTIONAL_MODS, optional );
        assertEquals( List.of( "editor.validate.optional.unknownRequires|ausm.jar|jei.jar",
                               "editor.validate.optional.unknownConflict|ausm.jar|nothing.jar",
                               "editor.validate.optional.selfReference|fast.jar" ),
                      doc.validateOptionalMods( ModPackDocumentOptionalModsTest::echo ) );
    }

    @Test
    void aRepeatedOptionalModIsReportedOnce()
    {
        ModPackDocument doc = ModPackDocument.fromJson( MANIFEST );
        List< ModPackFileEntry > optional = doc.optionalMods();
        optional.add( optional.get( 2 ) );
        optional.add( optional.get( 2 ) );
        doc.writeFileList( ModPackDocument.FileList.OPTIONAL_MODS, optional );
        assertEquals( List.of( "editor.validate.optional.duplicate|fast.jar" ),
                      doc.validateOptionalMods( ModPackDocumentOptionalModsTest::echo ) );
    }

    @Test
    void validateIncludesTheOptionalRules()
    {
        ModPackDocument doc = ModPackDocument.fromJson( MANIFEST );
        List< ModPackFileEntry > optional = doc.optionalMods();
        optional.get( 0 ).setRequires( List.of( "ghost.jar" ) );
        doc.writeFileList( ModPackDocument.FileList.OPTIONAL_MODS, optional );
        assertTrue( doc.validate( ModPackDocumentOptionalModsTest::echo )
                       .contains( "editor.validate.optional.unknownRequires|ausm.jar|ghost.jar" ) );
    }

    // endregion
}
