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

import com.micatechnologies.minecraft.launcher.config.GameModeManager;
import com.micatechnologies.minecraft.launcher.consts.ModPackConstants;
import com.micatechnologies.minecraft.launcher.exceptions.ModpackException;
import com.micatechnologies.minecraft.launcher.files.LocalPathManager;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.files.SynchronizedFileManager;
import com.micatechnologies.minecraft.launcher.utilities.HashUtilities;
import com.micatechnologies.minecraft.launcher.utilities.JSONUtilities;
import com.micatechnologies.minecraft.launcher.utilities.NetworkUtilities;
import com.micatechnologies.minecraft.launcher.utilities.StringOrArray;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Handles modpack environment setup: directory creation, image caching, and local path resolution. Extracted from
 * {@link GameModPack} to separate environment concerns from game launch logic.
 *
 * @author Mica Technologies
 * @version 1.0
 * @since 2.0
 */
class GameModPackEnvironment
{
    private final GameModPackMetadata metadata;
    private volatile boolean          didCacheImages = false;

    GameModPackEnvironment( GameModPackMetadata metadata )
    {
        this.metadata = metadata;
    }

    /**
     * Creates all required directories for the modpack (bin, mods, config, natives, resourcepacks, shaderpacks).
     */
    void prepareEnvironment()
    {
        File binPath = SynchronizedFileManager.getSynchronizedFile(
                metadata.getPackRootFolder() + File.separator + "bin" );
        File modsPath = SynchronizedFileManager.getSynchronizedFile(
                metadata.getPackRootFolder() + File.separator + "mods" );
        File configPath = SynchronizedFileManager.getSynchronizedFile(
                metadata.getPackRootFolder() + File.separator + "config" );
        File nativePath = SynchronizedFileManager.getSynchronizedFile(
                metadata.getPackRootFolder() + File.separator + "bin" + File.separator + "natives" );
        File resPackPath = SynchronizedFileManager.getSynchronizedFile(
                metadata.getPackRootFolder() + File.separator + "resourcepacks" );
        File shaderPackPath = SynchronizedFileManager.getSynchronizedFile(
                metadata.getPackRootFolder() + File.separator + "shaderpacks" );

        //noinspection ResultOfMethodCallIgnored
        binPath.getParentFile().mkdirs();

        if ( !binPath.exists() ) {
            //noinspection ResultOfMethodCallIgnored
            binPath.mkdir();
        }
        if ( !modsPath.exists() ) {
            //noinspection ResultOfMethodCallIgnored
            modsPath.mkdir();
        }
        if ( !configPath.exists() ) {
            //noinspection ResultOfMethodCallIgnored
            configPath.mkdir();
        }
        if ( !nativePath.exists() ) {
            //noinspection ResultOfMethodCallIgnored
            nativePath.mkdir();
        }
        if ( !resPackPath.exists() && GameModeManager.isClient() ) {
            //noinspection ResultOfMethodCallIgnored
            resPackPath.mkdir();
        }
        if ( !shaderPackPath.exists() && GameModeManager.isClient() ) {
            //noinspection ResultOfMethodCallIgnored
            shaderPackPath.mkdir();
        }
    }

    /**
     * Returns the file path to the cached logo image, downloading it first if necessary.
     *
     * @return absolute path to the logo image file
     */
    synchronized String getPackLogoFilepath()
    {
        if ( !didCacheImages ) {
            cacheImages();
        }
        return getRawLogoFilePath();
    }

    /**
     * Returns the file path to the cached background image, downloading it first if necessary.
     *
     * @return absolute path to the background image file
     */
    synchronized String getPackBackgroundFilepath()
    {
        if ( !didCacheImages ) {
            cacheImages();
        }
        return getRawBackgroundFilePath();
    }

    /**
     * Returns the on-disk paths of <em>every</em> cached logo the pack declares (the
     * manifest's {@code packLogoURL} may be a single string or an array of distinct
     * showcase images — see {@link StringOrArray}). Downloads first if necessary.
     *
     * @return ordered list of cached logo image paths; never {@code null}, possibly empty
     */
    synchronized List< String > getPackLogoFilepaths()
    {
        if ( !didCacheImages ) {
            cacheImages();
        }
        return getRawLogoFilePaths();
    }

    /**
     * Background-image counterpart of {@link #getPackLogoFilepaths()}.
     *
     * @return ordered list of cached background image paths; never {@code null}, possibly empty
     */
    synchronized List< String > getPackBackgroundFilepaths()
    {
        if ( !didCacheImages ) {
            cacheImages();
        }
        return getRawBackgroundFilePaths();
    }

    /**
     * Downloads and caches both the logo and background images for this modpack.
     */
    synchronized void cacheImages()
    {
        try {
            fetchLatestModpackLogo();
            fetchLatestModpackBackground();
            didCacheImages = true;
        }
        catch ( Exception e ) {
            didCacheImages = false;
            Logger.logError( "Unable to download image assets for mod pack: " + metadata.getFriendlyName() );
            Logger.logThrowable( e );
        }
    }

    /**
     * Returns the absolute on-disk path of the pack's <em>primary</em> logo (the first
     * declared image). Convenience wrapper over {@link #getRawLogoFilePaths()} for the
     * many single-image callers; falls back to a stable legacy filename (which may not
     * exist yet) when nothing is cached, so callers always get a non-null path to probe.
     */
    String getRawLogoFilePath()
    {
        List< String > all = getRawLogoFilePaths();
        if ( !all.isEmpty() ) {
            return all.get( 0 );
        }
        String filename = ( metadata.packLogoSha1 != null )
                          ? metadata.packLogoSha1 + ".png"
                          : "logo_" + metadata.getPackSanitizedName() + ".png";
        return LocalPathManager.getLauncherMetadataFolderPath() + File.separator + filename;
    }

    /**
     * Background-image counterpart of {@link #getRawLogoFilePath()}.
     */
    String getRawBackgroundFilePath()
    {
        List< String > all = getRawBackgroundFilePaths();
        if ( !all.isEmpty() ) {
            return all.get( 0 );
        }
        String filename = ( metadata.packBackgroundSha1 != null )
                          ? metadata.packBackgroundSha1 + ".png"
                          : "background_" + metadata.getPackSanitizedName() + ".png";
        return LocalPathManager.getLauncherMetadataFolderPath() + File.separator + filename;
    }

    /**
     * Returns the on-disk paths for every declared logo whose bytes are already cached
     * (content-addressed {@code <sha1>.png}). Side-effect-free — resolves only from the
     * manifest-declared SHA-1 (for the primary) and the {@code .image_cache} sidecar
     * (for the rest); never touches the network. A declared image that hasn't been
     * downloaded yet is simply absent from the list. See {@link #resolveSlotPaths}.
     */
    List< String > getRawLogoFilePaths()
    {
        return resolveSlotPaths( metadata.packLogoURL, metadata.packLogoSha1,
                                 ModPackConstants.MODPACK_DEFAULT_LOGO_URL,
                                 loadImageCacheSafe().logos );
    }

    /**
     * Background-image counterpart of {@link #getRawLogoFilePaths()}.
     */
    List< String > getRawBackgroundFilePaths()
    {
        return resolveSlotPaths( metadata.packBackgroundURL, metadata.packBackgroundSha1,
                                 ModPackConstants.MODPACK_DEFAULT_BG_URL,
                                 loadImageCacheSafe().backgrounds );
    }

    /**
     * Maps each declared URL for an image slot to its cached {@code <sha1>.png} path, in
     * declaration order, dropping any whose SHA-1 isn't known yet (i.e. not downloaded).
     * The primary image (index 0) honors a manifest-declared SHA-1; every other image is
     * content-addressed via the sidecar's recorded {@code url -> sha1} pairs.
     */
    private List< String > resolveSlotPaths( StringOrArray urls, String declaredSha1,
                                             String defaultUrl, List< ImageRef > sidecar )
    {
        List< String > urlList = ( urls != null && !urls.isEmpty() )
                                 ? urls.all()
                                 : List.of( defaultUrl );
        List< String > paths = new ArrayList<>();
        for ( int i = 0; i < urlList.size(); i++ ) {
            String url = urlList.get( i );
            String sha1 = sidecarSha1( sidecar, url );
            if ( sha1 == null && i == 0 && declaredSha1 != null ) {
                sha1 = declaredSha1;
            }
            if ( sha1 != null ) {
                paths.add( computedImageFile( sha1 ) );
            }
        }
        return paths;
    }

    private synchronized void fetchLatestModpackLogo() throws ModpackException
    {
        cacheAllSlotImages( metadata.packLogoURL, metadata.packLogoSha1,
                            ModPackConstants.MODPACK_DEFAULT_LOGO_URL, "modpack logo",
                            ".logo_download.tmp", true );
    }

    private synchronized void fetchLatestModpackBackground() throws ModpackException
    {
        cacheAllSlotImages( metadata.packBackgroundURL, metadata.packBackgroundSha1,
                            ModPackConstants.MODPACK_DEFAULT_BG_URL, "modpack background",
                            ".background_download.tmp", false );
    }

    /**
     * Caches <em>every</em> image a slot declares. The manifest's {@code string | string[]}
     * field is treated as a list of <b>distinct showcase images</b> (cycled in the UI per
     * issue #43) — <em>not</em> mirror fallbacks for one image. Each URL is downloaded,
     * transcoded to a JavaFX-decodable PNG, hashed, and moved into its own shared
     * content-addressed {@code <sha1>.png}. A per-URL failure (bad scheme, network error)
     * logs and skips just that one image, keeping the rest. The bundled default is fetched
     * only as a last resort when the slot declares nothing usable AND nothing cached; only
     * if even that fails does it raise {@link ModpackException}.
     *
     * <p>The sidecar's {@code logos} / {@code backgrounds} list is rebuilt from this pass so
     * {@link #resolveSlotPaths} can map declared URLs back to their cached files without any
     * network access. Images whose bytes are already on disk for the same URL are reused
     * (no re-download).</p>
     *
     * @param urls         the manifest's URL(s) for this slot (may be {@code null} / empty)
     * @param declaredSha1 manifest-declared SHA-1 for the primary image, or {@code null}
     * @param defaultUrl   bundled-default URL, used only as the all-sources-failed fallback
     * @param label        human-readable image label for logs / errors
     * @param tempFileName per-pack temp filename for the download-and-hash path
     * @param isLogo       {@code true} populates the logo slot, {@code false} the background slot
     */
    private synchronized void cacheAllSlotImages( StringOrArray urls, String declaredSha1,
                                                  String defaultUrl, String label,
                                                  String tempFileName, boolean isLogo )
            throws ModpackException
    {
        // Declared images, de-duplicated, declaration order preserved.
        List< String > declared = new ArrayList<>();
        if ( urls != null ) {
            declared.addAll( urls.all() );
        }
        declared = new ArrayList<>( new LinkedHashSet<>( declared ) );

        ImageCacheInfo cache = loadImageCacheSafe();
        List< ImageRef > existing = isLogo ? cache.logos : cache.backgrounds;
        List< ImageRef > resolved = new ArrayList<>();
        IOException lastError = null;

        for ( int i = 0; i < declared.size(); i++ ) {
            String url = declared.get( i );
            try {
                requireHttps( url, label );
                String sha1;
                if ( i == 0 && declaredSha1 != null ) {
                    // Primary image with a manifest-declared hash: verify / heal / download
                    // into <declaredSha1>.png. Naturally dedupes across packs declaring the
                    // same hash.
                    File destFile = SynchronizedFileManager.getSynchronizedFile(
                            computedImageFile( declaredSha1 ) );
                    resolveDeclaredImage( destFile, declaredSha1, url );
                    sha1 = declaredSha1;
                }
                else {
                    // Reuse an already-cached copy for this exact URL when the file still
                    // exists; otherwise download + hash into the shared content-addressed slot.
                    String known = sidecarSha1( existing, url );
                    sha1 = ( known != null && new File( computedImageFile( known ) ).exists() )
                           ? known
                           : downloadAndHashToContentAddressedFile( url, tempFileName );
                }
                resolved.add( new ImageRef( url, sha1 ) );
            }
            catch ( IOException e ) {
                lastError = e;
                Logger.logWarningSilent( "Mod pack " + label + ": source URL failed (" + url + "): "
                                                 + e.getMessage() + " — skipping this image." );
            }
        }

        // Nothing usable declared, or every declared URL failed → fall back to the bundled
        // default so the pack still renders something.
        if ( resolved.isEmpty() ) {
            try {
                requireHttps( defaultUrl, label );
                String sha1 = downloadAndHashToContentAddressedFile( defaultUrl, tempFileName );
                resolved.add( new ImageRef( defaultUrl, sha1 ) );
            }
            catch ( IOException e ) {
                lastError = e;
            }
        }

        if ( resolved.isEmpty() ) {
            throw new ModpackException( "Unable to download/fetch " + label
                                                + " (all sources failed).", lastError );
        }

        if ( isLogo ) {
            cache.logos = resolved;
        }
        else {
            cache.backgrounds = resolved;
        }
        saveImageCache( cache );
    }

    // region Content-addressed image cache

    /**
     * Per-pack sidecar file holding locally-computed SHA-1 hashes for fallback images
     * (those without a manifest-declared {@code packLogoSha1} / {@code packBackgroundSha1}).
     * Lives at {@code <packRoot>/.image_cache} alongside {@code .installed_version} and
     * {@code .launch_history}; survives remote manifest refreshes because nothing in the
     * GSON-deserialized {@link GameModPackMetadata} ever touches it.
     */
    private static final String IMAGE_CACHE_FILE = ".image_cache";

    /**
     * Schema for the {@code .image_cache} sidecar. Each slot (logo / background) holds an
     * ordered list of {@code url -> sha1} pairs — one per declared showcase image — so a
     * future URL change (image added / removed / swapped) invalidates the affected entry and
     * triggers a re-download.
     *
     * <p>The four scalar {@code *Url} / {@code *Sha1} fields are the legacy pre-3.6 single-image
     * schema, retained only so old sidecars deserialize; {@link #migrateLegacyImageCache} folds
     * them into the lists on load and nulls them out so re-saved files use the list form.</p>
     */
    private static class ImageCacheInfo
    {
        // Legacy single-image fields (pre-3.6) — migrated into the lists below on load.
        String logoUrl;
        String logoSha1;
        String backgroundUrl;
        String backgroundSha1;
        // Current schema: ordered url -> sha1 for every cached image in each slot.
        List< ImageRef > logos;
        List< ImageRef > backgrounds;
    }

    /** A single cached image: the source URL paired with the SHA-1 of its (transcoded) bytes. */
    private static final class ImageRef
    {
        String url;
        String sha1;

        @SuppressWarnings( "unused" )
        ImageRef() { /* GSON */ }

        ImageRef( String url, String sha1 )
        {
            this.url = url;
            this.sha1 = sha1;
        }
    }

    /** Looks up the cached SHA-1 for {@code url} in a slot list, or {@code null} if absent. */
    private static String sidecarSha1( List< ImageRef > list, String url )
    {
        if ( list == null || url == null ) {
            return null;
        }
        for ( ImageRef r : list ) {
            if ( r != null && url.equals( r.url ) && r.sha1 != null ) {
                return r.sha1;
            }
        }
        return null;
    }

    /**
     * Folds the legacy single-image scalar fields into the {@code logos} / {@code backgrounds}
     * lists (a one-element list each) and clears the scalars, so old {@code .image_cache} files
     * keep working and re-save in the current list form. Idempotent.
     */
    private static void migrateLegacyImageCache( ImageCacheInfo c )
    {
        if ( c.logos == null ) {
            c.logos = new ArrayList<>();
            if ( c.logoUrl != null && c.logoSha1 != null ) {
                c.logos.add( new ImageRef( c.logoUrl, c.logoSha1 ) );
            }
        }
        if ( c.backgrounds == null ) {
            c.backgrounds = new ArrayList<>();
            if ( c.backgroundUrl != null && c.backgroundSha1 != null ) {
                c.backgrounds.add( new ImageRef( c.backgroundUrl, c.backgroundSha1 ) );
            }
        }
        c.logoUrl = null;
        c.logoSha1 = null;
        c.backgroundUrl = null;
        c.backgroundSha1 = null;
    }

    private transient ImageCacheInfo imageCache;

    private synchronized ImageCacheInfo loadImageCacheSafe()
    {
        if ( imageCache != null ) {
            return imageCache;
        }
        Path file = Path.of( metadata.getPackRootFolder(), IMAGE_CACHE_FILE );
        if ( Files.exists( file ) ) {
            try {
                String json = Files.readString( file, StandardCharsets.UTF_8 );
                imageCache = JSONUtilities.getGson().fromJson( json, ImageCacheInfo.class );
            }
            catch ( Exception e ) {
                Logger.logWarningSilent( "Unable to read image cache for " + metadata.getPackName() );
            }
        }
        if ( imageCache == null ) {
            imageCache = new ImageCacheInfo();
        }
        migrateLegacyImageCache( imageCache );
        return imageCache;
    }

    private synchronized void saveImageCache( ImageCacheInfo cache )
    {
        Path file = Path.of( metadata.getPackRootFolder(), IMAGE_CACHE_FILE );
        try {
            //noinspection ResultOfMethodCallIgnored
            file.getParent().toFile().mkdirs();
            Files.writeString( file, JSONUtilities.getGson().toJson( cache ), StandardCharsets.UTF_8 );
            imageCache = cache;
        }
        catch ( IOException e ) {
            Logger.logWarningSilent( "Unable to save image cache for " + metadata.getPackName() );
        }
    }

    private static String computedImageFile( String sha1 )
    {
        return LocalPathManager.getLauncherMetadataFolderPath() + File.separator + sha1 + ".png";
    }

    /**
     * Declared-SHA1 image resolution with WebP-aware auto-heal. Branches:
     *
     * <ul>
     *   <li><b>Healthy fast path</b> — file matches declared hash AND is in a
     *       JavaFX-native format (PNG / JPEG / GIF / BMP): return as-is.</li>
     *   <li><b>Pre-transcode auto-heal</b> — file matches declared hash but
     *       JavaFX can't render the bytes (declared hash is the WebP source's
     *       SHA-1 from a launcher build that predated WebP transcoding).
     *       Transcode in place so the icon actually displays. The on-disk
     *       file now hashes to something other than the declared SHA-1 — the
     *       short-circuit below catches that on subsequent calls so we don't
     *       loop on re-download.</li>
     *   <li><b>Trust local</b> — file exists, doesn't match declared hash,
     *       but IS JavaFX-decodable: assume a previous transcode pass put it
     *       there and skip re-download. This is what prevents the loop after
     *       a pre-transcode auto-heal.</li>
     *   <li><b>Genuine miss</b> — file missing, or present but not decodable
     *       and SHA-1 doesn't match: download from the URL + transcode if
     *       needed.</li>
     * </ul>
     */
    private static void resolveDeclaredImage( File destFile, String declaredSha1, String effectiveUrl ) throws IOException
    {
        if ( HashUtilities.verifySHA1( destFile, declaredSha1 ) ) {
            if ( !com.micatechnologies.minecraft.launcher.utilities.ImageFormatUtilities
                    .isJavaFxDecodable( destFile ) ) {
                com.micatechnologies.minecraft.launcher.utilities.ImageFormatUtilities
                        .ensureJavaFxDecodable( destFile );
            }
            return;
        }
        if ( destFile.exists() && com.micatechnologies.minecraft.launcher.utilities.ImageFormatUtilities
                .isJavaFxDecodable( destFile ) ) {
            return;
        }
        NetworkUtilities.downloadFileFromURL( new URL( effectiveUrl ), destFile );
        com.micatechnologies.minecraft.launcher.utilities.ImageFormatUtilities
                .ensureJavaFxDecodable( destFile );
    }

    /**
     * Downloads {@code url} into a per-pack temp file under the pack's root folder, hashes
     * it, then atomically moves it to {@code metadata/<sha1>.png}. If a file with the same
     * SHA-1 already exists (i.e. another pack downloaded the identical bytes), the temp
     * file is deleted instead. Returns the computed SHA-1.
     */
    private String downloadAndHashToContentAddressedFile( String url, String tempFileName ) throws IOException
    {
        File tempFile = SynchronizedFileManager.getSynchronizedFile(
                metadata.getPackRootFolder() + File.separator + tempFileName );
        //noinspection ResultOfMethodCallIgnored
        tempFile.getParentFile().mkdirs();
        NetworkUtilities.downloadFileFromURL( new URL( url ), tempFile );

        // Transcode WebP / other ImageIO-readable formats to PNG in place
        // before hashing, so the content-addressed slot always holds a PNG
        // that JavaFX's Image class can actually decode. Modrinth's CDN
        // serves most pack icons as WebP, and custom modpack manifests can
        // point packLogoURL / packBackgroundURL at anything the author
        // happens to host — this is the single chokepoint where every
        // image enters the cache, so handling the transcode here keeps the
        // rest of the launcher format-agnostic. No-op for inputs that are
        // already PNG / JPEG / GIF / BMP.
        com.micatechnologies.minecraft.launcher.utilities.ImageFormatUtilities
                .ensureJavaFxDecodable( tempFile );

        String sha1 = HashUtilities.getFileSHA1( tempFile );
        if ( sha1 == null ) {
            throw new IOException( "Failed to hash downloaded image from " + url );
        }
        File finalFile = new File( computedImageFile( sha1 ) );
        //noinspection ResultOfMethodCallIgnored
        finalFile.getParentFile().mkdirs();
        if ( finalFile.exists() ) {
            //noinspection ResultOfMethodCallIgnored
            tempFile.delete();
        }
        else {
            Files.move( tempFile.toPath(), finalFile.toPath(), StandardCopyOption.REPLACE_EXISTING );
        }
        return sha1;
    }

    /**
     * Refuses any URL that isn't {@code https://}, with a narrow allowance for the
     * launcher's own bundled-default classpath resources (which resolve to
     * {@code file:} or {@code jar:} URLs depending on whether the launcher is
     * running from an IDE classes folder or a packaged JAR).
     *
     * <p>The image-download paths take URLs straight from the modpack manifest
     * JSON, which makes {@code file://}, {@code http://}, {@code jar://}, etc.
     * an information-disclosure / passive-MITM hazard. {@code file://} is the
     * worst: a malicious manifest could redirect the launcher into copying
     * {@code /etc/shadow} (or the user's keyring) into the launcher's metadata
     * folder.
     *
     * <p>The two bundled-default URLs are launcher-resolved at startup from the
     * classpath ({@link ModPackConstants#resolveResourceUrl} via
     * {@link Class#getResource}), so a manifest cannot guess the exact runtime
     * value (it varies per install path / packaging mode) — exact-match on those
     * two constants is a safe whitelist that doesn't reopen the broader file://
     * arbitrary-read hole.
     */
    private static void requireHttps( String url, String purpose ) throws ModpackException
    {
        if ( url == null || url.isBlank() ) {
            throw new ModpackException( "Missing URL for " + purpose );
        }
        if ( url.equals( ModPackConstants.MODPACK_DEFAULT_LOGO_URL )
                || url.equals( ModPackConstants.MODPACK_DEFAULT_BG_URL ) ) {
            return;
        }
        int colon = url.indexOf( ':' );
        if ( colon < 0 || !"https".equalsIgnoreCase( url.substring( 0, colon ) ) ) {
            throw new ModpackException( "Refusing non-https URL for " + purpose + ": " + url );
        }
    }

    // endregion
}
