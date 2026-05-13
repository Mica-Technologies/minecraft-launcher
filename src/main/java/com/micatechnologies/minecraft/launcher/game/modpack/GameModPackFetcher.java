/*
 * Copyright (c) 2021 Mica Technologies
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

import com.micatechnologies.minecraft.launcher.utilities.JSONUtilities;
import com.micatechnologies.minecraft.launcher.files.LocalPathManager;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.utilities.NetworkUtilities;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Class for fetching mod pack objects from their manifest URL. Supports offline mode by caching manifests locally and
 * falling back to the cache when the network is unavailable.
 *
 * @author Mica Technologies
 * @version 2.0
 * @since 2.0
 */
public class GameModPackFetcher
{
    /**
     * Subdirectory under the launcher folder where cached manifests are stored.
     */
    private static final String MANIFEST_CACHE_DIR = "manifest_cache";

    /** Hard cap on the size of a modpack manifest response. Legitimate manifests,
     *  even for thousand-mod packs, top out in the few-hundred-KB range — 50 MB is
     *  overhead headroom. Bounded download throws if the body would exceed this,
     *  which stops a compromised manifest host from OOMing the launcher. */
    private static final long MANIFEST_MAX_BYTES = 50L * 1024 * 1024;

    /** Threshold for warning the user that an offline-loaded cached manifest is
     *  stale. We don't refuse to launch (offline play would become unusable for
     *  long-disconnected users) but a visible warning encourages reconnect so the
     *  pack picks up any security-relevant updates pushed since the cache was
     *  written. */
    private static final long MANIFEST_STALE_THRESHOLD_MS = 7L * 24L * 60L * 60L * 1000L;

    /**
     * Returns a {@link GameModPack} parsed from the local on-disk manifest cache without
     * touching the network. Used by the launcher's cold-start path to paint the main menu
     * from cached data immediately while a background revalidate pulls fresh manifests
     * in parallel.
     *
     * @param manifestUrl       mod pack manifest URL
     * @param createEnvironment whether to call {@code prepareEnvironment()} on the loaded pack
     *
     * @return the cached mod pack, or {@code null} if no cache file exists for this URL or
     *         the cache could not be parsed
     *
     * @since 3.5
     */
    public static GameModPack getFromCache( String manifestUrl, boolean createEnvironment ) {
        try {
            String body = loadCachedManifest( manifestUrl );
            if ( body == null ) {
                return null;
            }
            GameModPack pack = JSONUtilities.getGson().fromJson( body, GameModPack.class );
            if ( pack == null ) {
                return null;
            }
            pack.manifestUrl = manifestUrl;
            // Capture the content hash so VerifyState.decideMode can compare against
            // the last-verified hash on the next launch — same body bytes → fast-path
            // eligible (subject to TTL etc.), different bytes → full verify.
            pack.setManifestContentSha256( VerifyState.computeManifestSha256( body ) );
            if ( createEnvironment ) {
                pack.prepareEnvironment();
            }
            return pack;
        }
        catch ( Exception e ) {
            // Cache read failures aren't fatal — the caller falls back to a network fetch.
            Logger.logWarningSilent( "Cached manifest unreadable for " + manifestUrl + ": " + e.getMessage() );
            return null;
        }
    }

    /**
     * Fetches the mod pack object from the specified manifest URL. If the network is available, downloads the latest
     * manifest and caches it locally. If offline, falls back to the cached version.
     *
     * @param manifestUrl       mod pack manifest URL
     * @param createEnvironment whether to create the mod pack environment
     *
     * @return mod pack object
     *
     * @since 1.0
     */
    public static GameModPack get( String manifestUrl, boolean createEnvironment ) {
        GameModPack gameModPack;
        try {
            String manifestBody;
            if ( NetworkUtilities.isOffline() ) {
                // Offline: load from cache
                manifestBody = loadCachedManifest( manifestUrl );
                if ( manifestBody == null ) {
                    throw new IOException( "No cached manifest available for offline mode" );
                }
                Logger.logStd( "Loaded cached manifest for offline mode: " + manifestUrl );
            }
            else {
                // Online: HTTPS-only bounded fetch with conditional-GET support. The 50 MB
                // size cap stops a compromised manifest host from OOMing the launcher.
                // The conditional request — If-None-Match / If-Modified-Since — lets the
                // server reply with a 304 + no body when nothing has changed since the
                // last fetch, cutting the response to a few hundred bytes and skipping
                // the Gson parse entirely. The Mica blob backs all our manifests and does
                // honor both validators, so the steady-state warm path is one-RTT 304s.
                ManifestCacheMeta prev = loadCacheMeta( manifestUrl );
                NetworkUtilities.BoundedFetchResult result =
                        NetworkUtilities.downloadFileFromURLBoundedConditional(
                                new URL( manifestUrl ),
                                MANIFEST_MAX_BYTES,
                                prev != null ? prev.etag : null,
                                prev != null ? prev.lastModified : null );
                if ( result.isNotModified() ) {
                    manifestBody = loadCachedManifest( manifestUrl );
                    if ( manifestBody == null ) {
                        // 304 but local cache is gone — degenerate case (cache cleared
                        // out-of-band between launches). Re-request unconditionally to
                        // rebuild the cache. We could be smarter here, but a one-shot
                        // re-fetch is the simplest correct fallback.
                        manifestBody = NetworkUtilities.downloadFileFromURLBounded(
                                manifestUrl, MANIFEST_MAX_BYTES );
                        cacheManifest( manifestUrl, manifestBody );
                    }
                    // Refresh the validators in case the server updated them on the 304.
                    saveCacheMeta( manifestUrl, result.etag(), result.lastModified() );
                }
                else {
                    manifestBody = result.body();
                    cacheManifest( manifestUrl, manifestBody );
                    saveCacheMeta( manifestUrl, result.etag(), result.lastModified() );
                }
            }
            gameModPack = JSONUtilities.getGson().fromJson( manifestBody, GameModPack.class );
            // Capture manifest body hash for fast-path verify decisions (see VerifyState).
            gameModPack.setManifestContentSha256( VerifyState.computeManifestSha256( manifestBody ) );
            if ( createEnvironment ) {
                gameModPack.prepareEnvironment();
                if ( !NetworkUtilities.isOffline() ) {
                    gameModPack.cacheImages();
                }
            }
        }
        catch ( Exception e ) {
            // Use silent logging to avoid blocking error dialogs for each failed pack
            Logger.logErrorSilent( "The following installed mod pack could not be loaded: " + manifestUrl );
            Logger.logThrowable( e );
            gameModPack = GameModPack.createFailedModPack( manifestUrl, e.getMessage() );
        }

        gameModPack.manifestUrl = manifestUrl;
        return gameModPack;
    }

    /**
     * Returns the cache file path for the given manifest URL. SHA-256 of the URL is
     * used as the filename so two URLs cannot collide (Java's {@code String.hashCode}
     * is a 32-bit non-cryptographic hash; collisions are findable and would let one
     * manifest's cache shadow another).
     */
    private static Path getCacheFilePath( String manifestUrl )
    {
        return cacheDir().resolve( sha256Hex( manifestUrl ) + ".json" );
    }

    /**
     * Legacy cache file path computed from {@code Integer.toHexString(url.hashCode())}.
     * Kept around so that during the transition we still find caches written by old
     * launcher versions; once an upgraded launcher fetches an online refresh it writes
     * to the new SHA-256 path and the legacy file is left as harmless orphan data.
     */
    private static Path getLegacyCacheFilePath( String manifestUrl )
    {
        return cacheDir().resolve( Integer.toHexString( manifestUrl.hashCode() ) + ".json" );
    }

    private static Path cacheDir()
    {
        return Path.of( LocalPathManager.getLauncherModpackFolderPath(), MANIFEST_CACHE_DIR );
    }

    private static String sha256Hex( String input )
    {
        try {
            byte[] hash = java.security.MessageDigest.getInstance( "SHA-256" )
                    .digest( input.getBytes( java.nio.charset.StandardCharsets.UTF_8 ) );
            StringBuilder sb = new StringBuilder( hash.length * 2 );
            for ( byte b : hash ) {
                sb.append( String.format( "%02x", b ) );
            }
            return sb.toString();
        }
        catch ( java.security.NoSuchAlgorithmException e ) {
            // SHA-256 is mandatory in every JRE we support; this branch is unreachable
            // in practice. Fall back to the legacy hashCode form to keep caching working
            // rather than throwing in a hot path.
            return Integer.toHexString( input.hashCode() );
        }
    }

    /**
     * Caches the manifest body to disk for offline use.
     */
    private static void cacheManifest( String manifestUrl, String manifestBody )
    {
        try {
            Path cacheFile = getCacheFilePath( manifestUrl );
            //noinspection ResultOfMethodCallIgnored
            cacheFile.getParent().toFile().mkdirs();
            Files.writeString( cacheFile, manifestBody, StandardCharsets.UTF_8 );
        }
        catch ( IOException e ) {
            Logger.logWarningSilent( "Unable to cache manifest for " + manifestUrl );
        }
    }

    /**
     * Loads a cached manifest from disk, or returns null if no cache exists.
     * Reads the SHA-256 path first; falls back to the legacy {@code hashCode}-based
     * path so existing installs continue to find their caches after the upgrade.
     * Once the launcher fetches a fresh manifest online it persists to the SHA-256
     * path; the legacy entry, if any, is left as harmless orphan data.
     *
     * <p>When the cache is older than {@link #MANIFEST_STALE_THRESHOLD_MS} the
     * load logs a user-visible warning naming the URL and recommending a
     * reconnect — but the manifest is still returned so offline play keeps
     * working. A hard refusal would break long-disconnected users; the warning
     * is enough to surface the staleness without taking play away.
     */
    private static String loadCachedManifest( String manifestUrl )
    {
        try {
            Path cacheFile = getCacheFilePath( manifestUrl );
            if ( Files.exists( cacheFile ) ) {
                warnIfStale( cacheFile, manifestUrl );
                return Files.readString( cacheFile, StandardCharsets.UTF_8 );
            }
            Path legacy = getLegacyCacheFilePath( manifestUrl );
            if ( Files.exists( legacy ) ) {
                warnIfStale( legacy, manifestUrl );
                return Files.readString( legacy, StandardCharsets.UTF_8 );
            }
        }
        catch ( IOException e ) {
            Logger.logWarningSilent( "Unable to load cached manifest for " + manifestUrl );
        }
        return null;
    }

    /**
     * Sidecar holding the ETag + Last-Modified validators captured from the last
     * successful network fetch of a manifest. Lives at {@code manifest_cache/<sha256>.meta.json}
     * next to the cached manifest body itself.
     */
    private static class ManifestCacheMeta
    {
        String etag;
        String lastModified;
    }

    private static Path getCacheMetaPath( String manifestUrl )
    {
        return cacheDir().resolve( sha256Hex( manifestUrl ) + ".meta.json" );
    }

    private static ManifestCacheMeta loadCacheMeta( String manifestUrl )
    {
        try {
            Path file = getCacheMetaPath( manifestUrl );
            if ( !Files.exists( file ) ) {
                return null;
            }
            String json = Files.readString( file, StandardCharsets.UTF_8 );
            return JSONUtilities.getGson().fromJson( json, ManifestCacheMeta.class );
        }
        catch ( Exception e ) {
            // Treat unreadable meta as no-meta — the next response writes a fresh
            // one. Returning null forces an unconditional GET on this fetch, which
            // is the right behaviour when we can't trust the stored validators.
            return null;
        }
    }

    private static void saveCacheMeta( String manifestUrl, String etag, String lastModified )
    {
        try {
            Path file = getCacheMetaPath( manifestUrl );
            //noinspection ResultOfMethodCallIgnored
            file.getParent().toFile().mkdirs();
            ManifestCacheMeta meta = new ManifestCacheMeta();
            meta.etag = etag;
            meta.lastModified = lastModified;
            Files.writeString( file, JSONUtilities.getGson().toJson( meta ),
                                StandardCharsets.UTF_8 );
        }
        catch ( IOException e ) {
            // Non-fatal: failing to persist meta just means the next fetch can't go
            // conditional — it'll still get the right body, just less efficiently.
            Logger.logWarningSilent( "Unable to save manifest cache meta for " + manifestUrl );
        }
    }

    /** Emits a user-visible warning if the cache file's mtime is older than the
     *  staleness threshold. No-op if the file's mtime can't be read. */
    private static void warnIfStale( Path cacheFile, String manifestUrl )
    {
        try {
            long ageMs = System.currentTimeMillis()
                    - Files.getLastModifiedTime( cacheFile ).toMillis();
            if ( ageMs > MANIFEST_STALE_THRESHOLD_MS ) {
                long days = ageMs / ( 24L * 60L * 60L * 1000L );
                Logger.logWarning( "Using cached manifest for " + manifestUrl
                                           + " — last refreshed " + days
                                           + " days ago. Reconnect to pick up any updates." );
            }
        }
        catch ( IOException ignored ) {
            // Best-effort — if mtime is unreadable we don't surface anything.
        }
    }
}
