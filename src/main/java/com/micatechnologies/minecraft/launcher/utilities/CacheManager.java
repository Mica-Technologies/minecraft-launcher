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

package com.micatechnologies.minecraft.launcher.utilities;

import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
import com.micatechnologies.minecraft.launcher.files.LocalPathManager;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.files.SynchronizedFileManager;

import java.io.File;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Simple file cache manager that stores downloaded content in a local cache directory with TTL-based expiry.
 * Content is stored using URL-derived hash filenames to avoid name collisions. Thread-safe for concurrent access.
 *
 * @author Mica Technologies
 * @version 1.0
 * @since 3.0
 */
public class CacheManager
{
    /**
     * Default TTL for cached files: 24 hours.
     */
    private static final long DEFAULT_TTL_MS = 24L * 60L * 60L * 1000L;

    /**
     * Maximum total cache size before cleanup: 100 MB.
     */
    private static final long MAX_CACHE_SIZE_BYTES = 100L * 1024L * 1024L;

    /**
     * Cache directory name under the launcher's local path.
     */
    private static final String CACHE_DIR_NAME = "cache";

    /**
     * Returns the cache directory, creating it if necessary.
     *
     * @return the cache directory File
     */
    private static File getCacheDir()
    {
        String path = LocalPathManager.getLauncherLocalPath() + File.separator + CACHE_DIR_NAME;
        File dir = SynchronizedFileManager.getSynchronizedFile( path );
        if ( !dir.exists() ) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    /**
     * Computes a SHA-256 hash of the URL string for use as a cache filename.
     *
     * @param url the URL to hash
     *
     * @return hex-encoded hash string
     */
    private static String hashUrl( String url )
    {
        try {
            MessageDigest digest = MessageDigest.getInstance( "SHA-256" );
            byte[] hash = digest.digest( url.getBytes( StandardCharsets.UTF_8 ) );
            StringBuilder sb = new StringBuilder();
            for ( byte b : hash ) {
                sb.append( String.format( "%02x", b ) );
            }
            return sb.toString();
        }
        catch ( Exception e ) {
            // Fallback: use hashCode (less collision-resistant but functional)
            return Integer.toHexString( url.hashCode() );
        }
    }

    /**
     * Extracts a file extension from a URL (e.g., ".png", ".jar"). Returns empty string if none found.
     */
    private static String getExtension( String url )
    {
        int queryIdx = url.indexOf( '?' );
        String path = queryIdx >= 0 ? url.substring( 0, queryIdx ) : url;
        int dotIdx = path.lastIndexOf( '.' );
        int slashIdx = path.lastIndexOf( '/' );
        if ( dotIdx > slashIdx && dotIdx < path.length() - 1 ) {
            String ext = path.substring( dotIdx );
            // Limit extension length to avoid garbage
            if ( ext.length() <= 6 ) {
                return ext;
            }
        }
        return "";
    }

    /**
     * Returns the cached file for the given URL if it exists and hasn't expired, or null if not cached or expired.
     *
     * @param url   the URL to look up
     * @param ttlMs the maximum age in milliseconds (use {@link #DEFAULT_TTL_MS} for default)
     *
     * @return the cached File, or null if not cached or expired
     */
    public static synchronized File getCached( String url, long ttlMs )
    {
        String hash = hashUrl( url );
        String ext = getExtension( url );
        File cached = new File( getCacheDir(), hash + ext );
        if ( cached.exists() ) {
            long age = System.currentTimeMillis() - cached.lastModified();
            if ( age < ttlMs ) {
                return cached;
            }
            // Expired -- delete
            //noinspection ResultOfMethodCallIgnored
            cached.delete();
        }
        return null;
    }

    /**
     * Returns the cached file for the given URL using the default TTL, or null.
     *
     * @param url the URL to look up
     *
     * @return the cached File, or null
     */
    public static File getCached( String url )
    {
        return getCached( url, DEFAULT_TTL_MS );
    }

    /**
     * Downloads the given URL and stores it in the cache. Returns the cached file. If the URL is already cached and
     * not expired, returns the existing cached file without re-downloading.
     *
     * @param url   the URL to download and cache
     * @param ttlMs the TTL for the cached entry
     *
     * @return the cached File
     *
     * @throws Exception if the download fails
     */
    public static synchronized File downloadAndCache( String url, long ttlMs ) throws Exception
    {
        File existing = getCached( url, ttlMs );
        if ( existing != null ) {
            return existing;
        }

        // Only cache network URLs. Reject file:/jar:/etc. so a (potentially
        // user/manifest-supplied) URL can't make the launcher copy an arbitrary
        // local file into the content cache.
        String lower = url == null ? "" : url.toLowerCase( java.util.Locale.ROOT );
        if ( !lower.startsWith( "https://" ) && !lower.startsWith( "http://" ) ) {
            throw new java.io.IOException( "Refusing to cache non-http(s) URL: " + url );
        }

        String hash = hashUrl( url );
        String ext = getExtension( url );
        File cacheFile = new File( getCacheDir(), hash + ext );

        NetworkUtilities.downloadFileFromURL( new URL( url ), cacheFile );

        return cacheFile;
    }

    /**
     * Downloads the given URL and stores it in the cache using the default TTL.
     *
     * @param url the URL to download and cache
     *
     * @return the cached File
     *
     * @throws Exception if the download fails
     */
    public static File downloadAndCache( String url ) throws Exception
    {
        return downloadAndCache( url, DEFAULT_TTL_MS );
    }

    /**
     * Cleans up expired files and enforces the maximum cache size limit. Oldest files are removed first when the
     * cache exceeds the size limit.
     */
    public static synchronized void cleanup()
    {
        File cacheDir = getCacheDir();
        File[] files = cacheDir.listFiles();
        if ( files == null || files.length == 0 ) {
            return;
        }

        // Delete expired files
        long now = System.currentTimeMillis();
        long totalSize = 0;
        for ( File file : files ) {
            long age = now - file.lastModified();
            if ( age > DEFAULT_TTL_MS ) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
            else {
                totalSize += file.length();
            }
        }

        // If still over size limit, delete oldest files first
        if ( totalSize > MAX_CACHE_SIZE_BYTES ) {
            files = cacheDir.listFiles();
            if ( files == null ) {
                return;
            }
            java.util.Arrays.sort( files, java.util.Comparator.comparingLong( File::lastModified ) );
            for ( File file : files ) {
                if ( totalSize <= MAX_CACHE_SIZE_BYTES ) {
                    break;
                }
                totalSize -= file.length();
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        }

        Logger.logDebug( LocalizationManager.format( "log.cacheManager.cleanupComplete", totalSize / 1024 ) );
    }
}
