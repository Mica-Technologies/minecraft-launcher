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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests for reading a pack's account override out of the config document. Read-only on an
 * injected document: the setter schedules a write to the real config file, so it isn't
 * exercised here.
 */
class AccountOverrideConfigTest
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

    private static void inject( JsonObject overrides )
    {
        JsonObject doc = new JsonObject();
        if ( overrides != null ) {
            doc.add( ConfigConstants.ACCOUNT_OVERRIDE_BY_PACK_KEY, overrides );
        }
        ConfigStore.setJson( doc );
    }

    @Test
    void anOverrideIsReadByPackKey()
    {
        JsonObject map = new JsonObject();
        map.addProperty( "https://example.com/pack.json", "uuid-b" );
        map.addProperty( "vanilla:1.20.4", "uuid-c" );
        inject( map );
        assertEquals( "uuid-b", ModPackConfig.getAccountOverrideForPack( "https://example.com/pack.json" ) );
        assertEquals( "uuid-c", ModPackConfig.getAccountOverrideForPack( "vanilla:1.20.4" ) );
    }

    @Test
    void noEntryMeansTheDefaultAccount()
    {
        inject( null );
        assertNull( ModPackConfig.getAccountOverrideForPack( "https://example.com/pack.json" ) );
        inject( new JsonObject() );
        assertNull( ModPackConfig.getAccountOverrideForPack( "https://example.com/pack.json" ) );
    }

    @Test
    void blankKeysAndValuesMeanTheDefaultAccount()
    {
        JsonObject map = new JsonObject();
        map.addProperty( "p", "  " );
        inject( map );
        assertNull( ModPackConfig.getAccountOverrideForPack( "p" ) );
        assertNull( ModPackConfig.getAccountOverrideForPack( null ) );
        assertNull( ModPackConfig.getAccountOverrideForPack( "" ) );
    }

    @Test
    void aMalformedSectionMeansTheDefaultAccount()
    {
        JsonObject doc = new JsonObject();
        doc.addProperty( ConfigConstants.ACCOUNT_OVERRIDE_BY_PACK_KEY, "not an object" );
        ConfigStore.setJson( doc );
        assertNull( ModPackConfig.getAccountOverrideForPack( "p" ) );
    }
}
