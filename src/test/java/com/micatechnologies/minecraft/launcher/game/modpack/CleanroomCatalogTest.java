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

import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.launcher.game.modpack.import_.ModrinthClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests for where Cleanroom shows up outside the launch: the version list Browse and the modpack
 * editor read from the CleanroomMC/Cleanroom GitHub releases, the bare-loader manifest Browse
 * installs, and the Modrinth loader facet a Cleanroom pack searches with.
 */
class CleanroomCatalogTest
{
    private static final String INSTALLER_0_7_14 =
            "https://github.com/CleanroomMC/Cleanroom/releases/download/0.7.14-alpha/cleanroom-0.7.14-alpha-installer.jar";

    @Test
    void releasesListOnlyTheInstallersAtTheirCanonicalUrl()
    {
        String json = """
                [
                  {"tag_name":"0.7.14-alpha","draft":false,"prerelease":true,"assets":[
                    {"name":"cleanroom-0.7.14-alpha-universal.jar","browser_download_url":"https://github.com/CleanroomMC/Cleanroom/releases/download/0.7.14-alpha/cleanroom-0.7.14-alpha-universal.jar"},
                    {"name":"cleanroom-0.7.14-alpha-installer.jar","browser_download_url":"https://github.com/CleanroomMC/Cleanroom/releases/download/0.7.14-alpha/cleanroom-0.7.14-alpha-installer.jar"}]},
                  {"tag_name":"0.8.0-alpha","draft":true,"assets":[
                    {"name":"cleanroom-0.8.0-alpha-installer.jar","browser_download_url":"https://x/i.jar"}]},
                  {"tag_name":"0.7.13-alpha","assets":[{"name":"notes.txt","browser_download_url":"https://x/n"}]},
                  {"tag_name":"0.7.12-alpha","assets":[
                    {"name":"cleanroom-0.7.12-alpha-installer.jar","browser_download_url":"http://insecure/i.jar"}]},
                  {"tag_name":"0.7.11","assets":[
                    {"name":"Cleanroom-0.7.11-installer.jar","browser_download_url":"https://github.com/CleanroomMC/Cleanroom/releases/download/0.7.11/Cleanroom-0.7.11-installer.jar"}]}
                ]
                """;
        List< LoaderVersionManager.LoaderVersion > v = LoaderVersionManager.parseCleanroomReleases( json );
        assertEquals( 1, v.size() );
        assertEquals( "cleanroom", v.get( 0 ).loaderType() );
        assertEquals( "1.12.2", v.get( 0 ).mcVersion() );
        assertEquals( "0.7.14-alpha", v.get( 0 ).loaderVersion() );
        assertEquals( INSTALLER_0_7_14, v.get( 0 ).installerUrl() );
        assertEquals( INSTALLER_0_7_14, LoaderVersionManager.cleanroomInstallerUrl( "0.7.14-alpha" ) );
        assertEquals( "Cleanroom 1.12.2 (0.7.14-alpha)", v.get( 0 ).displayName() );
        // A rate-limit (or any API error) body is an object, not a release list.
        assertNull( LoaderVersionManager.parseCleanroomReleases( "{\"message\":\"API rate limit exceeded\"}" ) );
        assertNull( LoaderVersionManager.parseCleanroomReleases( "not json" ) );
    }

    @Test
    void aBareCleanroomPackNamesItsInstallerInPackForgeUrl()
    {
        LoaderVersionManager.LoaderVersion v = new LoaderVersionManager.LoaderVersion(
                "cleanroom", "1.12.2", "0.7.14-alpha", INSTALLER_0_7_14 );
        JsonObject m = LoaderVersionManager.buildEmptyLoaderManifest( v );
        assertEquals( "cleanroom", m.get( "packModLoader" ).getAsString() );
        assertEquals( INSTALLER_0_7_14, m.get( "packModLoaderURL" ).getAsString() );
        assertEquals( INSTALLER_0_7_14, m.get( "packForgeURL" ).getAsString() );
        assertEquals( "https://github.com/CleanroomMC/Cleanroom", m.get( "packURL" ).getAsString() );
        // Fabric still leaves the legacy Forge field blank.
        JsonObject fabric = LoaderVersionManager.buildEmptyLoaderManifest( new LoaderVersionManager.LoaderVersion(
                "fabric", "1.21.5", "0.16.10", "https://meta.fabricmc.net/x" ) );
        assertEquals( "", fabric.get( "packForgeURL" ).getAsString() );
    }

    @Test
    void aCleanroomPackSearchesModrinthForForgeMods()
    {
        assertEquals( "forge", ModrinthClient.loaderFacetFor( "Cleanroom" ) );
        assertEquals( "forge", ModrinthClient.loaderFacetFor( "cleanroom" ) );
        assertEquals( "forge", ModrinthClient.loaderFacetFor( "Forge" ) );
        assertEquals( "fabric", ModrinthClient.loaderFacetFor( "Fabric" ) );
        assertEquals( "neoforge", ModrinthClient.loaderFacetFor( "neoforge" ) );
        assertNull( ModrinthClient.loaderFacetFor( null ) );
        assertNull( ModrinthClient.loaderFacetFor( " " ) );
    }
}
