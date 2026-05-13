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

package com.micatechnologies.minecraft.launcher.game.modpack.import_;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.utilities.JSONUtilities;
import com.micatechnologies.minecraft.launcher.utilities.NetworkUtilities;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal HTTP client against Modrinth's public v2 API (api.modrinth.com).
 * Used only to back the Add-by-URL preview dialog right now — full
 * {@code .mrpack} download / translation lands as a follow-up.
 *
 * <p>Modrinth's API doesn't require an API key for read endpoints, which is
 * why this lands ahead of any CurseForge equivalent. We hit two endpoints:
 *
 * <ul>
 *   <li>{@code /v2/project/{slug}} — name, description, supported MC
 *       versions, supported mod loaders, etc.</li>
 *   <li>{@code /v2/version/{id}} or {@code /v2/project/{slug}/version} —
 *       version metadata + the {@code .mrpack} file URL.</li>
 * </ul>
 *
 * <p>Every fetch uses {@link NetworkUtilities#downloadFileFromURLBounded} so
 * the size cap + HTTPS-only checks apply uniformly to all the launcher's
 * outbound HTTP. The bound here is 5 MB which is comfortably larger than
 * any Modrinth JSON response would be — large modpack version-lists top
 * out around 200 KB.</p>
 *
 * @since 2026.3
 */
public final class ModrinthClient
{
    private ModrinthClient() { /* static-only */ }

    /** Modrinth API base. {@code api.modrinth.com} is the production host;
     *  there's also {@code staging-api.modrinth.com} for testing but we
     *  never want to hit that from the production launcher. */
    private static final String API_BASE = "https://api.modrinth.com/v2";

    /** Hard cap on a Modrinth JSON response. Their project / version objects
     *  are typically 5–50 KB; 5 MB is overkill insurance against a buggy
     *  server response without leaving the launcher exposed to a hostile
     *  one. */
    private static final long MAX_RESPONSE_BYTES = 5L * 1024 * 1024;

    /**
     * Card-rendering subset of a Modrinth project + its latest version. Just
     * what the preview dialog needs; the deeper file-list / dependency
     * mapping happens in the import worker.
     */
    public record ProjectSummary(
            String projectId,
            String slug,
            String title,
            String description,
            String iconUrl,
            List< String > minecraftVersions,
            List< String > loaders,
            VersionSummary latestVersion )
    {}

    /**
     * Minimal slice of a {@code /v2/version/{id}} response. Carries the file
     * URLs + hashes so the eventual import worker can fetch the {@code .mrpack}
     * without an extra round-trip back through the project endpoint.
     */
    public record VersionSummary(
            String versionId,
            String name,
            String versionNumber,
            List< String > minecraftVersions,
            List< String > loaders,
            List< FileRef > files )
    {}

    /** One file inside a Modrinth version. The .mrpack is one of these. */
    public record FileRef(
            String filename,
            String url,
            String sha1,
            String sha512,
            long size,
            boolean primary )
    {}

    // ===== fetchers =====

    /**
     * Fetches the project summary for the given slug or project ID. Modrinth
     * accepts either at the {@code /project/{idOrSlug}} endpoint, so callers
     * don't need to know which they have.
     *
     * @return summary, or {@code null} on any network / parse failure (logged
     *         silently — the preview dialog falls back to a generic "couldn't
     *         load details" message rather than blowing up)
     */
    public static ProjectSummary fetchProject( String slugOrId, String versionPinId )
    {
        if ( slugOrId == null || slugOrId.isBlank() ) return null;
        try {
            JsonObject project = fetchJsonObject( API_BASE + "/project/" + slugOrId );
            if ( project == null ) return null;

            String projectId   = optString( project, "id" );
            String slug        = optString( project, "slug" );
            String title       = optString( project, "title" );
            String description = optString( project, "description" );
            String iconUrl     = optString( project, "icon_url" );
            List< String > mcVersions = optStringArray( project, "game_versions" );
            List< String > loaders    = optStringArray( project, "loaders" );

            // Pick the version: pinned ID from the URL if present, else the
            // first entry in the project's "versions" array which Modrinth
            // returns newest-first.
            VersionSummary version = null;
            if ( versionPinId != null && !versionPinId.isBlank() ) {
                version = fetchVersion( versionPinId );
            }
            if ( version == null ) {
                List< String > versionIds = optStringArray( project, "versions" );
                if ( !versionIds.isEmpty() ) {
                    // The "versions" array is oldest-first, so reach for the
                    // tail to get the latest. (Modrinth's docs warn this
                    // ordering is implementation-defined; the project's
                    // /version endpoint returns newest-first but requires a
                    // second round-trip.)
                    version = fetchVersion( versionIds.get( versionIds.size() - 1 ) );
                }
            }

            return new ProjectSummary( projectId, slug, title, description,
                                        iconUrl, mcVersions, loaders, version );
        }
        catch ( Throwable t ) {
            Logger.logWarningSilent( "Modrinth project fetch failed for " + slugOrId
                                             + ": " + t.getClass().getSimpleName() );
            return null;
        }
    }

    private static VersionSummary fetchVersion( String versionId )
    {
        if ( versionId == null || versionId.isBlank() ) return null;
        try {
            JsonObject v = fetchJsonObject( API_BASE + "/version/" + versionId );
            if ( v == null ) return null;
            List< FileRef > files = new ArrayList<>();
            JsonArray filesArr = v.has( "files" ) && v.get( "files" ).isJsonArray()
                    ? v.getAsJsonArray( "files" ) : new JsonArray();
            for ( JsonElement fe : filesArr ) {
                if ( !fe.isJsonObject() ) continue;
                JsonObject fo = fe.getAsJsonObject();
                String filename = optString( fo, "filename" );
                String url      = optString( fo, "url" );
                long size       = fo.has( "size" ) && fo.get( "size" ).isJsonPrimitive()
                        ? fo.get( "size" ).getAsLong() : 0;
                boolean primary = fo.has( "primary" )
                        && fo.get( "primary" ).getAsBoolean();
                String sha1 = null, sha512 = null;
                if ( fo.has( "hashes" ) && fo.get( "hashes" ).isJsonObject() ) {
                    JsonObject h = fo.getAsJsonObject( "hashes" );
                    sha1   = optString( h, "sha1" );
                    sha512 = optString( h, "sha512" );
                }
                files.add( new FileRef( filename, url, sha1, sha512, size, primary ) );
            }
            return new VersionSummary(
                    optString( v, "id" ),
                    optString( v, "name" ),
                    optString( v, "version_number" ),
                    optStringArray( v, "game_versions" ),
                    optStringArray( v, "loaders" ),
                    files );
        }
        catch ( Throwable t ) {
            Logger.logWarningSilent( "Modrinth version fetch failed for " + versionId
                                             + ": " + t.getClass().getSimpleName() );
            return null;
        }
    }

    // ===== JSON helpers =====

    private static JsonObject fetchJsonObject( String url ) throws Exception
    {
        String body = NetworkUtilities.downloadFileFromURLBounded( url, MAX_RESPONSE_BYTES );
        if ( body == null || body.isBlank() ) return null;
        JsonElement parsed = JSONUtilities.getGson().fromJson( body, JsonElement.class );
        return parsed != null && parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
    }

    private static String optString( JsonObject obj, String key )
    {
        if ( obj == null || !obj.has( key ) ) return null;
        JsonElement e = obj.get( key );
        if ( e == null || e.isJsonNull() || !e.isJsonPrimitive() ) return null;
        return e.getAsString();
    }

    private static List< String > optStringArray( JsonObject obj, String key )
    {
        List< String > out = new ArrayList<>();
        if ( obj == null || !obj.has( key ) ) return out;
        JsonElement e = obj.get( key );
        if ( e == null || !e.isJsonArray() ) return out;
        for ( JsonElement el : e.getAsJsonArray() ) {
            if ( el != null && el.isJsonPrimitive() ) out.add( el.getAsString() );
        }
        return out;
    }
}
