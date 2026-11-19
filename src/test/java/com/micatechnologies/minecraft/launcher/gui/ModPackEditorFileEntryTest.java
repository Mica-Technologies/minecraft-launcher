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

package com.micatechnologies.minecraft.launcher.gui;

import com.micatechnologies.minecraft.launcher.game.modpack.ModPackFileEntry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The editor row's conversion to and from the headless manifest entry: the editor saves through
 * it, so anything it drops is lost on every save. JavaFX properties need no toolkit, so this runs
 * in the default build.
 */
class ModPackEditorFileEntryTest
{
    @Test
    void optionalModFieldsSurviveTheEditorRow()
    {
        ModPackFileEntry source = new ModPackFileEntry( "AUSM", "https://example.invalid/ausm.jar", "ausm.jar",
                                                        "abc", "sha256", true, false );
        source.putExtraHash( "sha1", "def" );
        source.setModrinthSlug( "ausm" );
        source.setDescription( "Shader support" );
        source.setDefaultEnabled( true );
        source.setRequires( List.of( "lib.jar", "core.jar" ) );
        source.setConflictsWith( List.of( "fast.jar" ) );

        ModPackEditorFileEntry row = ModPackEditorFileEntry.fromDocumentEntry( source );
        assertEquals( "lib.jar, core.jar", row.requiresProperty().get() );

        ModPackFileEntry back = row.toDocumentEntry();
        assertEquals( "AUSM", back.getName() );
        assertEquals( "abc", back.getHash() );
        assertEquals( "sha256", back.getHashType() );
        assertEquals( "def", back.getExtraHash( "sha1" ) );
        assertEquals( "ausm", back.getModrinthSlug() );
        assertTrue( back.isClientReq() );
        assertFalse( back.isServerReq() );
        assertEquals( "Shader support", back.getDescription() );
        assertTrue( back.isDefaultEnabled() );
        assertEquals( List.of( "lib.jar", "core.jar" ), back.getRequires() );
        assertEquals( List.of( "fast.jar" ), back.getConflictsWith() );
    }

    @Test
    void typedNameListsAreSplitOnCommasAndSemicolons()
    {
        assertEquals( List.of( "a.jar", "b.jar", "c.jar" ), ModPackEditorFileEntry.splitNames( " a.jar ,b.jar;; c.jar, " ) );
        assertTrue( ModPackEditorFileEntry.splitNames( null ).isEmpty() );
        assertTrue( ModPackEditorFileEntry.splitNames( " , " ).isEmpty() );
    }
}
