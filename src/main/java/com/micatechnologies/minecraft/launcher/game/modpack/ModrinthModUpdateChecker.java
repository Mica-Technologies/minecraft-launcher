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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.utilities.JSONUtilities;
import com.micatechnologies.minecraft.launcher.utilities.NetworkUtilities;

import java.io.File;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Surfaces "is there a newer version of this mod on Modrinth?" answers for
 * a pack's {@code mods/} folder. Cheap heuristic: SHA-1 each {@code .jar},
 * look it up against Modrinth's {@code /v2/version_file/{hash}} endpoint
 * to identify the version it came from, then ask Modrinth for the
 * project's newest version and compare.
 *
 * <p>Mods that aren't on Modrinth (CurseForge exclusives, custom forks,
 * dev builds) get a {@link Status#NOT_ON_MODRINTH} result — the bulk of
 * the mod ecosystem is dual-listed, but the launcher shouldn't pretend
 * it knows about everything.</p>
 *
 * <p>The check is best-effort: any HTTP / parse / hash failure marks the
 * jar as {@code NOT_ON_MODRINTH} rather than propagating an error. The
 * UI surfaces "x of y mods on Modrinth · z have updates" and per-row
 * status badges; an unknown jar just doesn't get a badge.</p>
 *
 * @since 2026.5
 */
public final class ModrinthModUpdateChecker
{
    /**
     * Private constructor to prevent instantiation of this utility class.
     */
    private ModrinthModUpdateChecker() { /* static-only */ }

    /**
     * Base URL for the Modrinth API.
     */
    private static final String API_BASE = "https://api.modrinth.com/v2";

    /**
     * Maximum response size in bytes allowed for network requests.
     */
    private static final long MAX_RESPONSE_BYTES = 5L * 1024 * 1024;

    /**
     * Status of a single mod jar's update check.
     *
     * @since 2026.5
     */
    public enum Status
    {
        /**
         * The jar's SHA-1 didn't resolve to any Modrinth version, or a failure
         * (network / parse / hash) prevented identification.
         *
         * @since 2026.5
         */
        NOT_ON_MODRINTH,

        /**
         * The installed jar matches Modrinth's newest version for its project.
         *
         * @since 2026.5
         */
        UP_TO_DATE,

        /**
         * Modrinth has a newer version than the installed jar.
         *
         * @since 2026.5
         */
        UPDATE_AVAILABLE
    }

    /**
     * Result for one mod jar.
     *
     * @param status            classification
     * @param currentVersion    version number ("1.2.3") of the installed jar — null
     *                          when the jar isn't on Modrinth
     * @param latestVersion     latest version number on Modrinth — null when no
     *                          newer version exists or the jar isn't on Modrinth
     * @param latestDownloadUrl URL of the latest version's primary file — for
     *                          a future "click to update" action
     * @param projectName       human-readable project name from Modrinth
     *
     * @since 2026.5
     */
    public record ModUpdate(
            Status status,
            String currentVersion,
            String latestVersion,
            String latestDownloadUrl,
            String projectName )
    {
        /**
         * Builds the all-null sentinel result used for jars that aren't on
         * Modrinth or whose check failed.
         *
         * @return a {@link ModUpdate} with {@link Status#NOT_ON_MODRINTH} and no version data
         *
         * @since 2026.5
         */
        public static ModUpdate notOnModrinth() {
            return new ModUpdate( Status.NOT_ON_MODRINTH, null, null, null, null );
        }
    }

    /**
     * Scans {@code modsDir} for {@code .jar} files (ignores
     * {@code .jar.disabled}) and returns one {@link ModUpdate} per jar.
     * Map keys are the jar filenames; values are never null. Failures
     * (network, parse, hash) surface as {@link Status#NOT_ON_MODRINTH}
     * so the UI can render a uniform table without special-case
     * error rows.
     *
     * <p>Runs serially — one HTTP request per jar, two per known-on-
     * Modrinth jar. Typical modpacks have 20-100 mods; a serial scan
     * over those completes in a few seconds. Don't call from the FX
     * thread.</p>
     *
     * @param modsDir the pack's {@code mods/} folder; may be {@code null} or
     *                non-existent, in which case an empty map is returned
     *
     * @return a map of jar filename to its {@link ModUpdate} result; values are
     *         never {@code null}, and the map is empty when {@code modsDir} is
     *         missing or contains no jars
     *
     * @since 2026.5
     */
    public static Map< String, ModUpdate > scan( File modsDir )
    {
        if ( modsDir == null || !modsDir.isDirectory() ) return Map.of();
        File[] jars = modsDir.listFiles( f -> f.isFile()
                && f.getName().toLowerCase( Locale.ROOT ).endsWith( ".jar" ) );
        if ( jars == null || jars.length == 0 ) return Map.of();
        Map< String, ModUpdate > out = new HashMap<>();
        for ( File jar : jars ) {
            try {
                out.put( jar.getName(), checkOne( jar ) );
            }
            catch ( Throwable t ) {
                Logger.logWarningSilent( "Modrinth update check failed for " + jar.getName()
                                                 + ": " + t.getClass().getSimpleName() );
                out.put( jar.getName(), ModUpdate.notOnModrinth() );
            }
        }
        return out;
    }

    /**
     * Checks a single mod jar file to determine its update status.
     *
     * @param jar the mod jar file to check
     *
     * @return the {@link ModUpdate} result for the given jar
     *
     * @throws Exception if an error occurs during the check process
     *
     * @since 2026.5
     */
    private static ModUpdate checkOne( File jar ) throws Exception
    {
        String sha1 = sha1Hex( jar );
        if ( sha1 == null ) return ModUpdate.notOnModrinth();

        // Step 1: identify the version this jar belongs to.
        JsonObject currentVersion = fetchJson( API_BASE + "/version_file/" + sha1 );
        if ( currentVersion == null ) return ModUpdate.notOnModrinth();
        String currentVersionNumber = optString( currentVersion, "version_number" );
        String projectId = optString( currentVersion, "project_id" );
        if ( projectId == null ) return ModUpdate.notOnModrinth();

        // Step 2: project lookup gives the versions array (oldest first per
        // Modrinth's docs — we reach for the last entry to get the newest).
        JsonObject project = fetchJson( API_BASE + "/project/" + projectId );
        if ( project == null ) return ModUpdate.notOnModrinth();
        String projectName = optString( project, "title" );
        if ( !project.has( "versions" ) || !project.get( "versions" ).isJsonArray() ) {
            return new ModUpdate( Status.UP_TO_DATE, currentVersionNumber, null, null, projectName );
        }
        var versionsArr = project.get( "versions" ).getAsJsonArray();
        if ( versionsArr.isEmpty() ) {
            return new ModUpdate( Status.UP_TO_DATE, currentVersionNumber, null, null, projectName );
        }
        String latestVersionId = versionsArr.get( versionsArr.size() - 1 ).getAsString();
        String currentVersionId = optString( currentVersion, "id" );
        if ( latestVersionId.equals( currentVersionId ) ) {
            return new ModUpdate( Status.UP_TO_DATE, currentVersionNumber, null, null, projectName );
        }

        // Step 3: fetch the latest version's metadata for the version
        // number + download URL.
        JsonObject latest = fetchJson( API_BASE + "/version/" + latestVersionId );
        if ( latest == null ) {
            return new ModUpdate( Status.UP_TO_DATE, currentVersionNumber, null, null, projectName );
        }
        String latestVersionNumber = optString( latest, "version_number" );
        String latestDownloadUrl = primaryFileUrl( latest );
        return new ModUpdate( Status.UPDATE_AVAILABLE,
                              currentVersionNumber,
                              latestVersionNumber,
                              latestDownloadUrl,
                              projectName );
    }

    // -------------------------------------------------------------------
    //  HTTP / JSON helpers (small subset of ModrinthClient's pattern)
    // -------------------------------------------------------------------

    /**
     * Fetches a JSON object from the specified URL.
     *
     * @param url the URL to fetch the JSON from
     *
     * @return the fetched JSON object, or null if an error occurs
     *
     * @throws Exception if an error occurs during the fetch process
     *
     * @since 2026.5
     */
    private static JsonObject fetchJson( String url ) throws Exception
    {
        String body = NetworkUtilities.downloadFileFromURLBounded( url, MAX_RESPONSE_BYTES );
        if ( body == null || body.isBlank() ) return null;
        JsonElement parsed = JSONUtilities.getGson().fromJson( body, JsonElement.class );
        return parsed != null && parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
    }

    /**
     * Retrieves a string value from the specified JSON object.
     *
     * @param obj the JSON object to retrieve the value from
     * @param key the key of the value to retrieve
     *
     * @return the retrieved string value, or null if the key is not present or the value is not a string
     *
     * @since 2026.5
     */
    private static String optString( JsonObject obj, String key )
    {
        if ( obj == null || !obj.has( key ) ) return null;
        JsonElement e = obj.get( key );
        if ( e == null || e.isJsonNull() || !e.isJsonPrimitive() ) return null;
        return e.getAsString();
    }

    /**
     * Retrieves the primary file URL from the specified version JSON object.
     *
     * @param version the version JSON object to retrieve the URL from
     *
     * @return the primary file URL, or the first file URL if no primary flag is present, or null if no files are available
     *
     * @since 2026.5
     */
    private static String primaryFileUrl( JsonObject version )
    {
        if ( !version.has( "files" ) || !version.get( "files" ).isJsonArray() ) return null;
        var files = version.get( "files" ).getAsJsonArray();
        // Pick the primary file; fall back to first if no primary flag.
        String fallback = null;
        for ( JsonElement el : files ) {
            if ( el == null || !el.isJsonObject() ) continue;
            JsonObject fo = el.getAsJsonObject();
            String url = optString( fo, "url" );
            if ( url == null ) continue;
            if ( fo.has( "primary" ) && fo.get( "primary" ).getAsBoolean() ) return url;
            if ( fallback == null ) fallback = url;
        }
        return fallback;
    }

    /**
     * Computes the SHA-1 hash of the specified file.
     *
     * @param f the file to compute the hash for
     *
     * @return the hexadecimal representation of the SHA-1 hash, or null if an error occurs
     *
     * @since 2026.5
     */
    private static String sha1Hex( File f )
    {
        try ( var in = new java.io.FileInputStream( f ) ) {
            MessageDigest md = MessageDigest.getInstance( "SHA-1" );
            byte[] buf = new byte[ 32 * 1024 ];
            int read;
            while ( ( read = in.read( buf ) ) > 0 ) md.update( buf, 0, read );
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder( 40 );
            for ( byte b : digest ) sb.append( String.format( "%02x", b & 0xFF ) );
            return sb.toString();
        }
        catch ( Exception e ) {
            return null;
        }
    }
}
