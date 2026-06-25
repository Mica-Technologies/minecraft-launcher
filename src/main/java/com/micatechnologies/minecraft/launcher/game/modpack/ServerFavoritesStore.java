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
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.utilities.JSONUtilities;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Sidecar JSON persistence for {@link ServerFavorite} per modpack. The
 * list lives at {@code <packRoot>/server-favorites.json} so it travels
 * with the pack folder and isn't tied to the launcher config (which is
 * keyed by manifest URL — fine, but the per-pack sidecar is friendlier
 * for export/backup/move-folder workflows already in the launcher).
 *
 * <p>File schema is a JSON object with one key, {@code "favorites"},
 * whose value is an array of {@code {name, host, port}} objects.
 * Malformed entries are skipped silently; this is a UI convenience
 * file, not load-bearing.</p>
 *
 * @since 2026.5
 */
public final class ServerFavoritesStore
{
    /** Non-instantiable: this is a static-only utility holder. */
    private ServerFavoritesStore() { /* static-only */ }

    /** Sidecar filename written into the pack root folder. */
    private static final String FILE_NAME = "server-favorites.json";

    /**
     * Returns true when the user has explicitly opted out of
     * auto-joining the pack's manifest-declared default server.
     * Defaults to {@code false} (auto-join is enabled when the pack
     * declares a default).
     *
     * @param pack the modpack whose sidecar is consulted; a {@code null}
     *             pack is treated as "not disabled"
     *
     * @return {@code true} if the sidecar records the default-server
     *         auto-join as disabled, {@code false} otherwise (including
     *         when the sidecar is missing or unreadable)
     *
     * @since 2026.5
     */
    public static boolean isDefaultServerDisabled( GameModPack pack )
    {
        if ( pack == null ) return false;
        File f = new File( pack.getPackRootFolder(), FILE_NAME );
        if ( !f.isFile() ) return false;
        try {
            JsonObject root = readRoot( f );
            if ( root == null ) return false;
            return root.has( "disableDefaultServer" )
                    && root.get( "disableDefaultServer" ).getAsBoolean();
        }
        catch ( Exception e ) {
            return false;
        }
    }

    /**
     * Persists the user's choice to disable / re-enable auto-join for
     * the pack's manifest-declared default server. Leaves the existing
     * favorites list untouched.
     *
     * @param pack     the modpack whose sidecar is rewritten
     * @param disabled {@code true} to suppress auto-joining the
     *                 manifest-declared default server, {@code false} to
     *                 re-enable it
     *
     * @throws IOException if {@code pack} is {@code null} or the sidecar
     *                     cannot be written
     *
     * @since 2026.5
     */
    public static void setDefaultServerDisabled( GameModPack pack, boolean disabled ) throws IOException
    {
        if ( pack == null ) throw new IOException( "pack is null" );
        List< ServerFavorite > existing = load( pack );
        writeAll( pack, existing, disabled );
    }

    /**
     * Returns the favorites list for the given pack. Never null; an
     * empty list is returned when the file is missing or malformed.
     * Individual malformed array entries are skipped silently, and
     * out-of-range ports / blank names are normalized rather than
     * rejected.
     *
     * @param pack the modpack whose favorites are read; a {@code null}
     *             pack yields an empty list
     *
     * @return an immutable-or-fresh list of valid {@link ServerFavorite}
     *         entries, never {@code null}
     *
     * @since 2026.5
     */
    public static List< ServerFavorite > load( GameModPack pack )
    {
        if ( pack == null ) return Collections.emptyList();
        File f = new File( pack.getPackRootFolder(), FILE_NAME );
        if ( !f.isFile() ) return Collections.emptyList();
        try {
            JsonObject root = readRoot( f );
            if ( root == null ) return Collections.emptyList();
            if ( !root.has( "favorites" ) || !root.get( "favorites" ).isJsonArray() ) {
                return Collections.emptyList();
            }
            JsonArray arr = root.getAsJsonArray( "favorites" );
            List< ServerFavorite > out = new ArrayList<>( arr.size() );
            for ( JsonElement el : arr ) {
                if ( el == null || !el.isJsonObject() ) continue;
                JsonObject o = el.getAsJsonObject();
                String name = optString( o, "name" );
                String host = optString( o, "host" );
                int port = o.has( "port" ) && o.get( "port" ).isJsonPrimitive()
                        ? o.get( "port" ).getAsInt()
                        : ServerFavorite.DEFAULT_PORT;
                if ( host == null || host.isBlank() ) continue;
                if ( port < 1 || port > 65535 ) port = ServerFavorite.DEFAULT_PORT;
                if ( name == null || name.isBlank() ) name = host;
                out.add( new ServerFavorite( name, host, port ) );
            }
            return out;
        }
        catch ( Exception e ) {
            Logger.logWarningSilent( "Failed to read server favorites for " + pack.getPackName()
                                             + ": " + e.getClass().getSimpleName() );
            return Collections.emptyList();
        }
    }

    /**
     * Overwrites the favorites file with the given list, preserving
     * the existing {@code disableDefaultServer} flag.
     *
     * @param pack      the modpack whose sidecar is rewritten
     * @param favorites the favorites to persist; {@code null} or
     *                  host-less entries are dropped
     *
     * @throws IOException if {@code pack} is {@code null} or the sidecar
     *                     cannot be written
     *
     * @since 2026.5
     */
    public static void save( GameModPack pack, List< ServerFavorite > favorites ) throws IOException
    {
        writeAll( pack, favorites, isDefaultServerDisabled( pack ) );
    }

    /**
     * Serializes the favorites list and the default-server flag to the
     * sidecar file, creating parent directories as needed. Host-less or
     * {@code null} favorite entries are dropped, and the
     * {@code disableDefaultServer} flag is only emitted when {@code true}
     * to keep the common-case file minimal.
     *
     * @param pack                  the modpack whose sidecar is written
     * @param favorites             the favorites to persist (may be
     *                              {@code null} for an empty list)
     * @param defaultServerDisabled whether to record the default-server
     *                              auto-join as disabled
     *
     * @throws IOException if {@code pack} is {@code null} or the file
     *                     cannot be written
     */
    private static void writeAll( GameModPack pack, List< ServerFavorite > favorites,
                                  boolean defaultServerDisabled ) throws IOException
    {
        if ( pack == null ) throw new IOException( "pack is null" );
        JsonObject root = new JsonObject();
        JsonArray arr = new JsonArray();
        if ( favorites != null ) {
            for ( ServerFavorite fv : favorites ) {
                if ( fv == null || fv.host() == null || fv.host().isBlank() ) continue;
                JsonObject o = new JsonObject();
                o.addProperty( "name", fv.name() );
                o.addProperty( "host", fv.host() );
                o.addProperty( "port", fv.port() );
                arr.add( o );
            }
        }
        root.add( "favorites", arr );
        // Only emit disableDefaultServer when true — the absence of the
        // flag is the default (auto-join enabled), and omitting it
        // keeps the sidecar file minimal for the common case.
        if ( defaultServerDisabled ) {
            root.addProperty( "disableDefaultServer", true );
        }
        Path dest = new File( pack.getPackRootFolder(), FILE_NAME ).toPath();
        Files.createDirectories( dest.getParent() );
        Files.writeString( dest, JSONUtilities.getGson().toJson( root ), StandardCharsets.UTF_8 );
    }

    /**
     * Reads the sidecar file and returns its parsed root object.
     *
     * @param f the sidecar file to read
     *
     * @return the parsed root {@link JsonObject}, or {@code null} when
     *         the body does not parse to a JSON object
     *
     * @throws IOException if the file cannot be read
     */
    private static JsonObject readRoot( File f ) throws IOException
    {
        String body = Files.readString( f.toPath(), StandardCharsets.UTF_8 );
        JsonElement parsed = JSONUtilities.getGson().fromJson( body, JsonElement.class );
        return parsed != null && parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
    }

    /**
     * Reads a string-valued property, tolerating missing keys, JSON
     * nulls, and non-primitive values.
     *
     * @param o   the JSON object to read from
     * @param key the property name to look up
     *
     * @return the property's string value, or {@code null} when absent
     *         or not a JSON primitive
     */
    private static String optString( JsonObject o, String key )
    {
        if ( !o.has( key ) ) return null;
        JsonElement e = o.get( key );
        if ( e == null || e.isJsonNull() || !e.isJsonPrimitive() ) return null;
        return e.getAsString();
    }
}
