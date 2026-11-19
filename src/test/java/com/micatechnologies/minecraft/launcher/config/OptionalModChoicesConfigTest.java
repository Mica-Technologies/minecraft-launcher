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

package com.micatechnologies.minecraft.launcher.config;

import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.launcher.consts.ConfigConstants;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for reading a pack's optional-mod choices out of the config document. Read-only on an
 * injected document: the setter schedules a write to the real config file, so it isn't
 * exercised here.
 */
class OptionalModChoicesConfigTest
{
    private static JsonObject originalJson;

    @BeforeAll
    static void captureOriginal()
    {
        originalJson = ConfigStore.peek();
    }

    @AfterAll
    static void restoreOriginal()
    {
        ConfigStore.setJson( originalJson );
    }

    private static void inject( JsonObject byPack )
    {
        JsonObject doc = new JsonObject();
        if ( byPack != null ) {
            doc.add( ConfigConstants.OPTIONAL_MODS_BY_PACK_KEY, byPack );
        }
        ConfigStore.setJson( doc );
    }

    @Test
    void theKeyMatchesTheDocumentedName()
    {
        assertEquals( "optionalModsByPack", ConfigConstants.OPTIONAL_MODS_BY_PACK_KEY );
    }

    @Test
    void choicesAreReadByPackKey()
    {
        JsonObject pack = new JsonObject();
        pack.addProperty( "ausm.jar", true );
        pack.addProperty( "lib.jar", false );
        JsonObject byPack = new JsonObject();
        byPack.add( "https://example.com/pack.json", pack );
        inject( byPack );

        assertEquals( Map.of( "ausm.jar", true, "lib.jar", false ),
                      ModPackConfig.getOptionalModChoices( "https://example.com/pack.json" ) );
        assertTrue( ModPackConfig.getOptionalModChoices( "https://example.com/other.json" ).isEmpty() );
    }

    @Test
    void nonBooleanValuesAreSkipped()
    {
        JsonObject pack = new JsonObject();
        pack.addProperty( "ausm.jar", "yes" );
        pack.addProperty( "lib.jar", true );
        JsonObject byPack = new JsonObject();
        byPack.add( "p", pack );
        inject( byPack );
        assertEquals( Map.of( "lib.jar", true ), ModPackConfig.getOptionalModChoices( "p" ) );
    }

    @Test
    void absentBlankOrMalformedMeansNoChoices()
    {
        inject( null );
        assertTrue( ModPackConfig.getOptionalModChoices( "p" ).isEmpty() );
        assertTrue( ModPackConfig.getOptionalModChoices( null ).isEmpty() );
        assertTrue( ModPackConfig.getOptionalModChoices( " " ).isEmpty() );

        JsonObject byPack = new JsonObject();
        byPack.addProperty( "p", "not an object" );
        inject( byPack );
        assertTrue( ModPackConfig.getOptionalModChoices( "p" ).isEmpty() );

        JsonObject doc = new JsonObject();
        doc.addProperty( ConfigConstants.OPTIONAL_MODS_BY_PACK_KEY, 42 );
        ConfigStore.setJson( doc );
        assertTrue( ModPackConfig.getOptionalModChoices( "p" ).isEmpty() );
    }
}
