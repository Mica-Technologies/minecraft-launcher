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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A launcher release as the GitHub releases API describes it: its version tag, its page, and the
 * files it offers.
 *
 * @param version  the release tag, such as {@code 2026.10.10}
 * @param pageUrl  the release's web page, the fallback when the launcher cannot update itself
 * @param assets   the release's files
 *
 * @since 2026.11
 */
public record LauncherRelease( String version, String pageUrl, List< ReleaseAsset > assets )
{
    /** The form GitHub gives each asset's {@code digest}: {@code sha256:} and 64 hex digits. */
    private static final Pattern SHA256_DIGEST = Pattern.compile( "sha256:[0-9a-fA-F]{64}" );

    /**
     * Copies the asset list, so a release never changes after it is built.
     *
     * @param version the release tag
     * @param pageUrl the release's web page
     * @param assets  the release's files
     */
    public LauncherRelease
    {
        assets = List.copyOf( assets );
    }

    /**
     * Reads a release from one object of the GitHub releases API ({@code releases/latest}).
     * Assets without a name or download URL are left out; a missing or malformed digest becomes
     * {@code null}, so such an asset can be offered as a page link but never installed.
     *
     * @param json the API response
     *
     * @return the release, or empty when the response has no tag or page URL
     *
     * @since 2026.11
     */
    public static Optional< LauncherRelease > fromJson( JsonObject json )
    {
        String version = string( json, "tag_name" );
        String pageUrl = string( json, "html_url" );
        if ( version == null || pageUrl == null ) {
            return Optional.empty();
        }
        List< ReleaseAsset > assets = new ArrayList<>();
        JsonElement assetsElement = json.get( "assets" );
        if ( assetsElement != null && assetsElement.isJsonArray() ) {
            JsonArray array = assetsElement.getAsJsonArray();
            for ( JsonElement element : array ) {
                if ( !element.isJsonObject() ) {
                    continue;
                }
                JsonObject asset = element.getAsJsonObject();
                String name = string( asset, "name" );
                String url = string( asset, "browser_download_url" );
                if ( name == null || url == null ) {
                    continue;
                }
                JsonElement size = asset.get( "size" );
                long bytes = size != null && size.isJsonPrimitive() && size.getAsJsonPrimitive().isNumber()
                             ? size.getAsLong() : -1;
                assets.add( new ReleaseAsset( name, url, bytes, sha256Of( string( asset, "digest" ) ) ) );
            }
        }
        return Optional.of( new LauncherRelease( version, pageUrl, assets ) );
    }

    /**
     * The hex part of a GitHub {@code digest} value, lowercased.
     *
     * @param digest the value, such as {@code sha256:4208...}, or {@code null}
     *
     * @return the 64 hex digits, or {@code null} when the value is missing or not a SHA-256
     */
    static String sha256Of( String digest )
    {
        if ( digest == null || !SHA256_DIGEST.matcher( digest ).matches() ) {
            return null;
        }
        return digest.substring( "sha256:".length() ).toLowerCase( Locale.ROOT );
    }

    private static String string( JsonObject json, String key )
    {
        JsonElement value = json.get( key );
        if ( value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString() ) {
            return null;
        }
        String text = value.getAsString();
        return text.isBlank() ? null : text;
    }
}
