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

package com.micatechnologies.minecraft.launcher.update;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LauncherReleaseTest
{
    private static final String HEX = "4208444788552e9cde1acbd674525eecfba25fc80bba3704d8ccbc52a687b513";

    private static JsonObject json( String text )
    {
        return JsonParser.parseString( text ).getAsJsonObject();
    }

    @Test
    void readsTagPageAndAssetsWithTheirDigests()
    {
        Optional< LauncherRelease > release = LauncherRelease.fromJson( json( """
                {
                  "tag_name": "2026.10.10",
                  "html_url": "https://example.invalid/releases/tag/2026.10.10",
                  "assets": [
                    { "name": "launcher-2026.10.10-jar-with-dependencies.jar", "size": 92905264,
                      "digest": "sha256:%s",
                      "browser_download_url": "https://example.invalid/launcher.jar" },
                    { "name": "Mica.Minecraft.Launcher-26.10.10-user.msi", "size": 10,
                      "browser_download_url": "https://example.invalid/user.msi" }
                  ]
                }
                """.formatted( HEX.toUpperCase() ) ) );

        assertTrue( release.isPresent() );
        assertEquals( "2026.10.10", release.get().version() );
        assertEquals( "https://example.invalid/releases/tag/2026.10.10", release.get().pageUrl() );
        assertEquals( 2, release.get().assets().size() );
        ReleaseAsset jar = release.get().assets().get( 0 );
        assertEquals( "launcher-2026.10.10-jar-with-dependencies.jar", jar.name() );
        assertEquals( 92905264L, jar.size() );
        assertEquals( HEX, jar.sha256(), "digests are lowercased" );
        assertNull( release.get().assets().get( 1 ).sha256(), "no digest (a release before 2025)" );
    }

    @Test
    void skipsAssetsWithoutANameOrUrl()
    {
        LauncherRelease release = LauncherRelease.fromJson( json( """
                { "tag_name": "1", "html_url": "https://example.invalid/r",
                  "assets": [ { "name": "a.msi" }, { "browser_download_url": "https://example.invalid/b" }, 5 ] }
                """ ) ).orElseThrow();

        assertTrue( release.assets().isEmpty() );
    }

    @Test
    void aResponseWithoutTagOrPageIsNoRelease()
    {
        assertTrue( LauncherRelease.fromJson( json( "{}" ) ).isEmpty() );
        assertTrue( LauncherRelease.fromJson( json( "{ \"tag_name\": \"1\" }" ) ).isEmpty() );
        assertTrue( LauncherRelease.fromJson( json( "{ \"html_url\": \"https://example.invalid\" }" ) ).isEmpty() );
        assertTrue( LauncherRelease.fromJson( json( "{ \"tag_name\": \" \", \"html_url\": \"x\" }" ) ).isEmpty() );
    }

    @Test
    void onlyAWellFormedSha256DigestCounts()
    {
        assertEquals( HEX, LauncherRelease.sha256Of( "sha256:" + HEX ) );
        assertNull( LauncherRelease.sha256Of( null ) );
        assertNull( LauncherRelease.sha256Of( HEX ), "no algorithm prefix" );
        assertNull( LauncherRelease.sha256Of( "sha1:" + HEX.substring( 0, 40 ) ) );
        assertNull( LauncherRelease.sha256Of( "sha256:" + HEX.substring( 1 ) ), "too short" );
        assertNull( LauncherRelease.sha256Of( "sha256:" + HEX.substring( 1 ) + "g" ), "not hex" );
    }
}
