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

import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
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
            // Imported-pack short-circuit. file:// URLs are translated Mica
            // manifests on local disk (written by MrpackImporter); they ARE
            // the authoritative source, not a cached snapshot of something
            // else. Reading them here means fetchInstalledModPacks' cold-
            // start Phase 1b succeeds without falling through to the
            // network-fetch path (which would call cacheImages and pay the
            // image-download cost on every launch, slow for imported packs
            // whose icons live on Modrinth's CDN).
            String body;
            if ( manifestUrl != null && manifestUrl.startsWith( "file:" ) ) {
                body = readLocalManifest( manifestUrl );
            }
            else {
                body = loadCachedManifest( manifestUrl );
            }
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
            // Keep the install-index entry in sync with what's actually in the
            // per-manifest cache — covers the case where the index file got
            // wiped (or was never written) but the per-manifest cache exists.
            updateInstallIndex( manifestUrl, pack );
            return pack;
        }
        catch ( Exception e ) {
            // Cache read failures aren't fatal — the caller falls back to a network fetch.
            Logger.logWarningSilent( LocalizationManager.format( "log.gameModPackFetcher.cachedManifestUnreadable", manifestUrl, e.getMessage() ) );
            return null;
        }
    }

    /**
     * Fast-path cold-start loader: builds a {@link GameModPack} stub from the
     * {@link InstallIndex} entry for {@code manifestUrl} without touching the
     * network or the per-manifest cache. The returned pack carries only the
     * card-rendering subset (name, version, image URLs, RAM, flags); fields
     * like {@code packMods} are null until the full manifest fetch upgrades
     * it. Use {@link GameModPack#isStub} to detect this state.
     *
     * @param manifestUrl mod pack manifest URL keying the index lookup;
     *                    a {@code null} or blank value yields {@code null}
     *
     * @return the stub pack, or {@code null} if the index has no entry for
     *         this URL (first launch, fresh install, etc.) — caller should
     *         fall back to {@link #getFromCache} / {@link #get}
     *
     * @since 2026.3
     */
    public static GameModPack getStubFromIndex( String manifestUrl )
    {
        if ( manifestUrl == null || manifestUrl.isBlank() ) return null;
        InstallIndex.Entry entry = InstallIndex.load().get( manifestUrl );
        if ( entry == null ) return null;
        return GameModPack.createStubFromIndex( manifestUrl, entry );
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
            // file:// URLs are imported modpacks — their manifest lives on
            // local disk under the launcher's imported-manifests folder
            // (see MrpackImporter). No network, no conditional revalidate,
            // just read the bytes and parse. Same machinery downstream so
            // imported packs round-trip through the rest of the launcher
            // identically to network-fetched ones.
            if ( manifestUrl != null && manifestUrl.startsWith( "file:" ) ) {
                manifestBody = readLocalManifest( manifestUrl );
                if ( manifestBody == null ) {
                    throw new IOException( "Local manifest not found at " + manifestUrl );
                }
                Logger.logStd( LocalizationManager.format( "log.gameModPackFetcher.loadedImportedManifest", manifestUrl ) );
            }
            else if ( NetworkUtilities.isOffline() ) {
                // Offline: load from cache
                manifestBody = loadCachedManifest( manifestUrl );
                if ( manifestBody == null ) {
                    throw new IOException( "No cached manifest available for offline mode" );
                }
                Logger.logStd( LocalizationManager.format( "log.gameModPackFetcher.loadedCachedManifestOffline", manifestUrl ) );
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
                    // Image-cache failures (404 on a moved logo URL, transient
                    // network blip, bad SHA-1, etc.) are non-fatal — the pack
                    // itself is valid even if we can't fetch its branding
                    // assets. The card UI will fall through to the bundled
                    // default logo + the procedural gradient. Without this
                    // tolerance, ONE broken image URL in an imported pack's
                    // manifest puts the launcher into a permanent retry loop
                    // because every cold start + every background revalidate
                    // re-runs this code path and re-throws.
                    try {
                        gameModPack.cacheImages();
                    }
                    catch ( Throwable t ) {
                        Logger.logWarningSilent( LocalizationManager.format( "log.gameModPackFetcher.imageCacheFailed",
                                                         manifestUrl, t.getClass().getSimpleName() ) );
                    }
                }
            }
            // Refresh the install-index entry alongside the per-manifest cache so
            // the next cold start can paint cards from the unified index file.
            gameModPack.manifestUrl = manifestUrl;
            updateInstallIndex( manifestUrl, gameModPack );
        }
        catch ( Exception e ) {
            // Use silent logging to avoid blocking error dialogs for each failed pack
            Logger.logErrorSilent( LocalizationManager.format( "log.gameModPackFetcher.installedModPackNotLoaded", manifestUrl ) );
            Logger.logThrowable( e );
            gameModPack = GameModPack.createFailedModPack( manifestUrl, e.getMessage() );
        }

        gameModPack.manifestUrl = manifestUrl;
        return gameModPack;
    }

    /** Reads a manifest file from a local {@code file://} URL — used for
     *  imported modpacks whose translated Mica manifest was written by
     *  {@link com.micatechnologies.minecraft.launcher.game.modpack.import_.MrpackImporter}.
     *  Returns the body string on success, {@code null} when the file is
     *  missing or unreadable. */
    /**
     * Returns the raw manifest body text for {@code manifestUrl} without
     * parsing it into a {@link GameModPack}. Used by the modpack editor
     * when the user clicks Edit on an installed pack — the editor wants
     * the source JSON, not a GSON-roundtripped {@link GameModPack}, so
     * unknown / future-schema fields stay intact through the edit cycle.
     *
     * <p>Resolution order matches {@link #getFromCache} so the editor sees
     * exactly the same bytes the launcher used to render the card:
     * imported packs ({@code file:} URLs) read straight off disk; network
     * packs come from {@code manifest_cache/<sha256>.json}. Returns
     * {@code null} when neither source has the manifest — caller falls
     * back to a blank document.
     *
     * @param manifestUrl pack manifest URL (file: or https:)
     * @return manifest body text, or {@code null} if unavailable
     *
     * @since 2026.5
     */
    public static String loadManifestText( String manifestUrl )
    {
        if ( manifestUrl == null || manifestUrl.isBlank() ) return null;
        if ( manifestUrl.startsWith( "file:" ) ) {
            return readLocalManifest( manifestUrl );
        }
        return loadCachedManifest( manifestUrl );
    }

    /**
     * Reads a manifest file from a local {@code file://} URL — used for imported
     * modpacks whose translated Mica manifest was written by
     * {@link com.micatechnologies.minecraft.launcher.game.modpack.import_.MrpackImporter}.
     *
     * @param fileUrl the {@code file://} URL pointing at the on-disk manifest
     *
     * @return the manifest body text on success, or {@code null} when {@code fileUrl}
     *         is {@code null}, the target is not a regular file, or it is unreadable
     */
    private static String readLocalManifest( String fileUrl )
    {
        if ( fileUrl == null ) return null;
        try {
            Path p = Path.of( new java.net.URI( fileUrl ) );
            if ( !Files.isRegularFile( p ) ) return null;
            return Files.readString( p, StandardCharsets.UTF_8 );
        }
        catch ( Exception e ) {
            Logger.logWarningSilent( LocalizationManager.format( "log.gameModPackFetcher.couldNotReadLocalManifest", fileUrl,
                                             e.getClass().getSimpleName() ) );
            return null;
        }
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

    /**
     * Returns the directory holding all cached manifest bodies and their metadata
     * sidecars ({@code <launcher-modpack-folder>/manifest_cache}).
     *
     * @return the manifest cache directory path
     */
    private static Path cacheDir()
    {
        return Path.of( LocalPathManager.getLauncherModpackFolderPath(), MANIFEST_CACHE_DIR );
    }

    /**
     * Computes the lowercase hex SHA-256 of the given string for use as a cache
     * filename. SHA-256 is mandatory in every supported JRE, so the fallback to
     * the legacy {@code hashCode} hex form is effectively unreachable; it exists
     * only to keep caching working rather than returning a {@code null} filename.
     *
     * @param input the string to hash (typically a manifest URL)
     *
     * @return the SHA-256 hex digest, or the legacy {@code hashCode} hex form if
     *         SHA-256 is somehow unavailable
     */
    private static String sha256Hex( String input )
    {
        // SHA-256 is mandatory in every JRE we support, so the null branch is
        // unreachable in practice; fall back to the legacy hashCode form to keep
        // caching working rather than producing a null filename.
        String hex = com.micatechnologies.minecraft.launcher.utilities.HashUtilities.sha256Hex( input );
        return hex != null ? hex : Integer.toHexString( input.hashCode() );
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
            Logger.logWarningSilent( LocalizationManager.format( "log.gameModPackFetcher.unableToCacheManifest", manifestUrl ) );
        }
    }

    /**
     * Upserts the given parsed pack into the unified {@link InstallIndex} so
     * the next cold start can paint cards from a single file read instead of
     * N per-manifest cache reads. Called after every successful manifest
     * load (both fresh-from-network and cache-hit), so the index stays in
     * sync without separate write paths. Best-effort: an index update
     * failure is logged but never breaks the caller.
     *
     * @param manifestUrl the pack manifest URL keying the index entry; a
     *                    {@code null} value is ignored (no-op)
     * @param parsedPack  the freshly parsed pack whose card-rendering subset
     *                    is written into the index; a {@code null} value is
     *                    ignored (no-op)
     *
     * @since 2026.3
     */
    static void updateInstallIndex( String manifestUrl, GameModPack parsedPack )
    {
        if ( manifestUrl == null || parsedPack == null ) return;
        try {
            // Atomic load-upsert-save: this runs from fetchInstalledModPacks' parallel
            // manifest loads, so a non-atomic load()/upsert()/save() here would race
            // concurrent writers into lost updates / a corrupt index.
            InstallIndex.upsertAndSave( manifestUrl, parsedPack );
        }
        catch ( Throwable t ) {
            Logger.logWarningSilent( LocalizationManager.format( "log.gameModPackFetcher.couldNotUpdateInstallIndex", manifestUrl,
                                             t.getClass().getSimpleName() ) );
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
            Logger.logWarningSilent( LocalizationManager.format( "log.gameModPackFetcher.unableToLoadCachedManifest", manifestUrl ) );
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
        /** The {@code ETag} validator from the manifest's last successful fetch, or {@code null}. */
        String etag;

        /** The {@code Last-Modified} validator from the manifest's last successful fetch, or {@code null}. */
        String lastModified;
    }

    /**
     * Returns the sidecar metadata file path ({@code manifest_cache/<sha256>.meta.json})
     * holding the conditional-GET validators for the given manifest URL.
     *
     * @param manifestUrl mod pack manifest URL
     *
     * @return the {@code .meta.json} sidecar path next to the cached manifest body
     */
    private static Path getCacheMetaPath( String manifestUrl )
    {
        return cacheDir().resolve( sha256Hex( manifestUrl ) + ".meta.json" );
    }

    /**
     * Loads the cached ETag / Last-Modified validators for the given manifest URL.
     * Returns {@code null} when no sidecar exists or it cannot be parsed; a
     * {@code null} result forces the next fetch to issue an unconditional GET,
     * which is the safe behaviour when the stored validators cannot be trusted.
     *
     * @param manifestUrl mod pack manifest URL
     *
     * @return the parsed cache metadata, or {@code null} if absent or unreadable
     */
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

    /**
     * Persists the ETag / Last-Modified validators returned by a successful
     * network fetch so the next fetch of this manifest can be conditional.
     * Best-effort: a write failure is logged but never propagated — it just
     * means the following fetch falls back to an unconditional GET.
     *
     * @param manifestUrl  mod pack manifest URL
     * @param etag         the ETag validator from the response, or {@code null}
     * @param lastModified the Last-Modified validator from the response, or {@code null}
     */
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
            Logger.logWarningSilent( LocalizationManager.format( "log.gameModPackFetcher.unableToSaveCacheMeta", manifestUrl ) );
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
                Logger.logWarning( LocalizationManager.format( "log.gameModPackFetcher.usingStaleCachedManifest", manifestUrl,
                                           days ) );
            }
        }
        catch ( IOException ignored ) {
            // Best-effort — if mtime is unreadable we don't surface anything.
        }
    }
}
