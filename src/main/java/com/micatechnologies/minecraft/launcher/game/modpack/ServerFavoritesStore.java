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
    private ServerFavoritesStore() { /* static-only */ }

    private static final String FILE_NAME = "server-favorites.json";

    /**
     * Returns true when the user has explicitly opted out of
     * auto-joining the pack's manifest-declared default server.
     * Defaults to {@code false} (auto-join is enabled when the pack
     * declares a default).
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

    /** Overwrites the favorites file with the given list, preserving
     *  the existing {@code disableDefaultServer} flag. */
    public static void save( GameModPack pack, List< ServerFavorite > favorites ) throws IOException
    {
        writeAll( pack, favorites, isDefaultServerDisabled( pack ) );
    }

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

    private static JsonObject readRoot( File f ) throws IOException
    {
        String body = Files.readString( f.toPath(), StandardCharsets.UTF_8 );
        JsonElement parsed = JSONUtilities.getGson().fromJson( body, JsonElement.class );
        return parsed != null && parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
    }

    private static String optString( JsonObject o, String key )
    {
        if ( !o.has( key ) ) return null;
        JsonElement e = o.get( key );
        if ( e == null || e.isJsonNull() || !e.isJsonPrimitive() ) return null;
        return e.getAsString();
    }
}
