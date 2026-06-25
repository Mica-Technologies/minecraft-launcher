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
import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
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
    /**
     * Private no-op constructor — this is a static-only utility class and is
     * never instantiated.
     *
     * @since 2026.3
     */
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
     *
     * @param projectId         Modrinth's opaque internal project identifier
     * @param slug              the human-readable URL slug for the project
     * @param title             the project's display name
     * @param description       the project's short summary / description text
     * @param iconUrl           CDN URL of the project icon, or {@code null}
     * @param minecraftVersions Minecraft versions the project supports
     * @param loaders           mod loaders the project supports
     *                          (e.g. {@code forge}, {@code fabric})
     * @param latestVersion     the resolved latest version of the project,
     *                          or {@code null} if none could be fetched
     * @since 2026.3
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
     *
     * @param versionId         Modrinth's opaque version identifier
     * @param name              the version's display name
     * @param versionNumber     the author-supplied version number string
     * @param minecraftVersions Minecraft versions this version targets
     * @param loaders           mod loaders this version targets
     * @param files             downloadable files belonging to this version
     *                          (the {@code .mrpack} is one of these)
     * @since 2026.3
     */
    public record VersionSummary(
            String versionId,
            String name,
            String versionNumber,
            List< String > minecraftVersions,
            List< String > loaders,
            List< FileRef > files )
    {}

    /**
     * One file inside a Modrinth version. The {@code .mrpack} is one of these.
     *
     * @param filename the file's name as Modrinth reports it
     * @param url      the CDN URL the file can be downloaded from
     * @param sha1     the file's SHA-1 hash, or {@code null} if absent
     * @param sha512   the file's SHA-512 hash, or {@code null} if absent
     * @param size     the file's declared size in bytes
     * @param primary  {@code true} if Modrinth marks this as the version's
     *                 primary (canonical) file
     * @since 2026.3
     */
    public record FileRef(
            String filename,
            String url,
            String sha1,
            String sha512,
            long size,
            boolean primary )
    {}

    /**
     * One result row from a {@code /v2/search} query — enough to render a
     * pick-list.
     *
     * @param projectId   Modrinth's opaque internal project identifier
     * @param slug        the human-readable URL slug for the project
     * @param title       the project's display name
     * @param description the project's short summary / description text
     * @param iconUrl     CDN URL of the project icon, or {@code null}
     * @param author      the project's author / owner name
     * @param downloads   the project's all-time download count
     * @since 2026.3
     */
    public record SearchHit(
            String projectId,
            String slug,
            String title,
            String description,
            String iconUrl,
            String author,
            long downloads )
    {}

    // ===== search =====

    /**
     * Searches Modrinth for <b>mods</b> matching {@code query}, optionally constrained to a
     * Minecraft version and mod loader so the results are actually installable into the pack.
     *
     * @param query     free-text search (mod name / keywords)
     * @param mcVersion Minecraft version facet (e.g. {@code "1.20.1"}), or {@code null} for any
     * @param loader    loader facet (e.g. {@code "forge"} / {@code "fabric"} / {@code "neoforge"}),
     *                  or {@code null} for any
     * @param limit     max results (Modrinth caps at 100)
     * @return result rows (newest-relevance order), or an empty list on any failure
     * @since 2026.3
     */
    public static List< SearchHit > search( String query, String mcVersion, String loader, int limit )
    {
        List< SearchHit > out = new ArrayList<>();
        if ( query == null || query.isBlank() ) return out;
        try {
            // facets is a JSON array-of-arrays; each inner array is OR'd, the outer AND'd.
            List< String > facetGroups = new ArrayList<>();
            facetGroups.add( "[\"project_type:mod\"]" );
            if ( mcVersion != null && !mcVersion.isBlank() ) {
                facetGroups.add( "[\"versions:" + mcVersion + "\"]" );
            }
            if ( loader != null && !loader.isBlank() ) {
                facetGroups.add( "[\"categories:" + loader.toLowerCase() + "\"]" );
            }
            String facets = "[" + String.join( ",", facetGroups ) + "]";
            String url = API_BASE + "/search?limit=" + Math.max( 1, Math.min( limit, 100 ) )
                    + "&index=relevance"
                    + "&query=" + enc( query )
                    + "&facets=" + enc( facets );
            JsonObject root = fetchJsonObject( url );
            if ( root == null || !root.has( "hits" ) || !root.get( "hits" ).isJsonArray() ) return out;
            for ( JsonElement he : root.getAsJsonArray( "hits" ) ) {
                if ( !he.isJsonObject() ) continue;
                JsonObject h = he.getAsJsonObject();
                long downloads = h.has( "downloads" ) && h.get( "downloads" ).isJsonPrimitive()
                        ? h.get( "downloads" ).getAsLong() : 0;
                out.add( new SearchHit(
                        optString( h, "project_id" ),
                        optString( h, "slug" ),
                        optString( h, "title" ),
                        optString( h, "description" ),
                        optString( h, "icon_url" ),
                        optString( h, "author" ),
                        downloads ) );
            }
            return out;
        }
        catch ( Throwable t ) {
            Logger.logWarningSilent( LocalizationManager.format( "log.modrinthClient.searchFailed",
                                             query, t.getClass().getSimpleName() ) );
            return out;
        }
    }

    /**
     * Resolves the latest version of a project compatible with the given Minecraft version + loader
     * and returns its primary downloadable file (the jar to drop into a pack's {@code mods/}).
     *
     * @param projectId Modrinth slug or project ID to resolve a version for
     * @param mcVersion Minecraft version to constrain to, or {@code null} for any
     * @param loader    mod loader to constrain to (e.g. {@code "forge"}), or {@code null} for any
     * @return the primary {@link FileRef}, or {@code null} if no compatible version / file exists
     * @since 2026.3
     */
    public static FileRef resolveLatestCompatibleFile( String projectId, String mcVersion, String loader )
    {
        if ( projectId == null || projectId.isBlank() ) return null;
        try {
            StringBuilder url = new StringBuilder( API_BASE )
                    .append( "/project/" ).append( projectId ).append( "/version?" );
            if ( loader != null && !loader.isBlank() ) {
                url.append( "loaders=" ).append( enc( "[\"" + loader.toLowerCase() + "\"]" ) ).append( '&' );
            }
            if ( mcVersion != null && !mcVersion.isBlank() ) {
                url.append( "game_versions=" ).append( enc( "[\"" + mcVersion + "\"]" ) );
            }
            String body = NetworkUtilities.downloadFileFromURLBounded( url.toString(), MAX_RESPONSE_BYTES );
            if ( body == null || body.isBlank() ) return null;
            JsonElement parsed = JSONUtilities.getGson().fromJson( body, JsonElement.class );
            if ( parsed == null || !parsed.isJsonArray() ) return null;
            JsonArray versions = parsed.getAsJsonArray();
            // Modrinth returns this endpoint newest-first; take the first version's primary file.
            for ( JsonElement ve : versions ) {
                if ( !ve.isJsonObject() ) continue;
                FileRef primary = primaryFile( ve.getAsJsonObject() );
                if ( primary != null ) return primary;
            }
            return null;
        }
        catch ( Throwable t ) {
            Logger.logWarningSilent( LocalizationManager.format( "log.modrinthClient.versionResolveFailed",
                                             projectId, t.getClass().getSimpleName() ) );
            return null;
        }
    }

    /**
     * Extracts the primary (or first) downloadable {@link FileRef} from a
     * version JSON object. Prefers the file flagged {@code primary}; if none
     * is flagged, falls back to the first file carrying a usable URL.
     *
     * @param version a Modrinth version JSON object (may be {@code null})
     * @return the primary or first {@link FileRef}, or {@code null} when the
     *         object has no usable files
     * @since 2026.3
     */
    private static FileRef primaryFile( JsonObject version )
    {
        if ( version == null || !version.has( "files" ) || !version.get( "files" ).isJsonArray() ) {
            return null;
        }
        FileRef fallback = null;
        for ( JsonElement fe : version.getAsJsonArray( "files" ) ) {
            if ( !fe.isJsonObject() ) continue;
            JsonObject fo = fe.getAsJsonObject();
            String url = optString( fo, "url" );
            if ( url == null ) continue;
            String filename = optString( fo, "filename" );
            long size = fo.has( "size" ) && fo.get( "size" ).isJsonPrimitive() ? fo.get( "size" ).getAsLong() : 0;
            String sha1 = null, sha512 = null;
            if ( fo.has( "hashes" ) && fo.get( "hashes" ).isJsonObject() ) {
                JsonObject hh = fo.getAsJsonObject( "hashes" );
                sha1 = optString( hh, "sha1" );
                sha512 = optString( hh, "sha512" );
            }
            boolean primary = fo.has( "primary" ) && fo.get( "primary" ).getAsBoolean();
            FileRef ref = new FileRef( filename, url, sha1, sha512, size, primary );
            if ( primary ) return ref;
            if ( fallback == null ) fallback = ref;
        }
        return fallback;
    }

    /**
     * URL-encodes a query-string component using UTF-8.
     *
     * @param s the raw string to encode
     * @return the percent-encoded form suitable for a query parameter value
     * @since 2026.3
     */
    private static String enc( String s )
    {
        return java.net.URLEncoder.encode( s, java.nio.charset.StandardCharsets.UTF_8 );
    }

    // ===== fetchers =====

    /**
     * Fetches the project summary for the given slug or project ID. Modrinth
     * accepts either at the {@code /project/{idOrSlug}} endpoint, so callers
     * don't need to know which they have.
     *
     * @param slugOrId     the project slug or Modrinth project ID to fetch
     * @param versionPinId an explicit version ID to resolve (e.g. pinned from
     *                     the source URL), or {@code null} to use the project's
     *                     latest version
     * @return summary, or {@code null} on any network / parse failure (logged
     *         silently — the preview dialog falls back to a generic "couldn't
     *         load details" message rather than blowing up)
     * @since 2026.3
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
            Logger.logWarningSilent( LocalizationManager.format( "log.modrinthClient.projectFetchFailed",
                                             slugOrId, t.getClass().getSimpleName() ) );
            return null;
        }
    }

    /**
     * Fetches and parses a single Modrinth version by ID into a
     * {@link VersionSummary}, including its file list.
     *
     * @param versionId the Modrinth version ID to fetch
     * @return the parsed version summary, or {@code null} on any network /
     *         parse failure (logged silently)
     * @since 2026.3
     */
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
            Logger.logWarningSilent( LocalizationManager.format( "log.modrinthClient.versionFetchFailed",
                                             versionId, t.getClass().getSimpleName() ) );
            return null;
        }
    }

    // ===== JSON helpers =====

    /**
     * Performs a bounded HTTPS GET against {@code url} and parses the response
     * body as a JSON object.
     *
     * @param url the absolute Modrinth API URL to fetch
     * @return the parsed JSON object, or {@code null} when the body is empty or
     *         isn't a JSON object
     * @throws Exception if the bounded download fails (network error, size cap
     *                   exceeded, non-HTTPS rejection, etc.)
     * @since 2026.3
     */
    private static JsonObject fetchJsonObject( String url ) throws Exception
    {
        String body = NetworkUtilities.downloadFileFromURLBounded( url, MAX_RESPONSE_BYTES );
        if ( body == null || body.isBlank() ) return null;
        JsonElement parsed = JSONUtilities.getGson().fromJson( body, JsonElement.class );
        return parsed != null && parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
    }

    /**
     * Safely reads a string-valued field from a JSON object.
     *
     * @param obj the JSON object to read from (may be {@code null})
     * @param key the field name to read
     * @return the string value, or {@code null} when absent, JSON-null, or not
     *         a primitive
     * @since 2026.3
     */
    private static String optString( JsonObject obj, String key )
    {
        if ( obj == null || !obj.has( key ) ) return null;
        JsonElement e = obj.get( key );
        if ( e == null || e.isJsonNull() || !e.isJsonPrimitive() ) return null;
        return e.getAsString();
    }

    /**
     * Safely reads a string-array-valued field from a JSON object, skipping any
     * non-primitive elements.
     *
     * @param obj the JSON object to read from (may be {@code null})
     * @param key the field name to read
     * @return the list of string values; never {@code null}, empty when the
     *         field is absent or not an array
     * @since 2026.3
     */
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
