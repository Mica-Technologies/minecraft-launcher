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

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

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
     * Returns the absolute on-disk path for the modpack logo. Resolution order:
     * <ol>
     *   <li>If the manifest declares a SHA-1, use {@code metadata/<declaredSha1>.png}.</li>
     *   <li>Else if the {@code .image_cache} sidecar has a previously-computed SHA-1 for
     *       the current effective URL, use {@code metadata/<cachedSha1>.png}.</li>
     *   <li>Else fall back to the legacy per-pack filename so first-launch callers still
     *       get a stable path (the file may not exist yet — {@link #fetchLatestModpackLogo()}
     *       will create the content-addressed copy on first download).</li>
     * </ol>
     */
    String getRawLogoFilePath()
    {
        String sha1 = ( metadata.packLogoSha1 != null )
                      ? metadata.packLogoSha1
                      : loadImageCacheSafe().logoSha1;
        String filename = ( sha1 != null )
                          ? sha1 + ".png"
                          : "logo_" + metadata.getPackSanitizedName() + ".png";
        return LocalPathManager.getLauncherMetadataFolderPath() + File.separator + filename;
    }

    /**
     * Returns the absolute on-disk path for the modpack background image. See
     * {@link #getRawLogoFilePath()} for the resolution order.
     */
    String getRawBackgroundFilePath()
    {
        String sha1 = ( metadata.packBackgroundSha1 != null )
                      ? metadata.packBackgroundSha1
                      : loadImageCacheSafe().backgroundSha1;
        String filename = ( sha1 != null )
                          ? sha1 + ".png"
                          : "background_" + metadata.getPackSanitizedName() + ".png";
        return LocalPathManager.getLauncherMetadataFolderPath() + File.separator + filename;
    }

    private synchronized void fetchLatestModpackLogo() throws ModpackException
    {
        try {
            String effectiveUrl = Objects.requireNonNullElse(
                    metadata.packLogoURL, ModPackConstants.MODPACK_DEFAULT_LOGO_URL );
            requireHttps( effectiveUrl, "modpack logo" );

            // Declared-SHA1 path: download once to <declaredSha1>.png and verify on subsequent
            // launches. Naturally dedupes across packs that declare the same hash.
            if ( metadata.packLogoSha1 != null ) {
                File destFile = SynchronizedFileManager.getSynchronizedFile(
                        computedImageFile( metadata.packLogoSha1 ) );
                resolveDeclaredImage( destFile, metadata.packLogoSha1, effectiveUrl );
                return;
            }

            // Undeclared-SHA1 path: if the sidecar already cached a SHA-1 for this exact URL
            // and the corresponding file still exists, we're done — no network at all.
            ImageCacheInfo cache = loadImageCacheSafe();
            if ( cache.logoSha1 != null
                 && Objects.equals( cache.logoUrl, effectiveUrl )
                 && new File( computedImageFile( cache.logoSha1 ) ).exists() ) {
                return;
            }

            // Otherwise download to a per-pack temp file, hash, then move into the shared
            // content-addressed slot. Two packs sharing the same fallback URL converge on the
            // same <sha1>.png and the second one's move becomes a no-op delete.
            String computedSha1 = downloadAndHashToContentAddressedFile( effectiveUrl, ".logo_download.tmp" );
            cache.logoUrl = effectiveUrl;
            cache.logoSha1 = computedSha1;
            saveImageCache( cache );
        }
        catch ( IOException e ) {
            throw new ModpackException( "Unable to download/fetch mod pack logo.", e );
        }
    }

    private synchronized void fetchLatestModpackBackground() throws ModpackException
    {
        try {
            String effectiveUrl = Objects.requireNonNullElse(
                    metadata.packBackgroundURL, ModPackConstants.MODPACK_DEFAULT_BG_URL );
            requireHttps( effectiveUrl, "modpack background" );

            if ( metadata.packBackgroundSha1 != null ) {
                File destFile = SynchronizedFileManager.getSynchronizedFile(
                        computedImageFile( metadata.packBackgroundSha1 ) );
                resolveDeclaredImage( destFile, metadata.packBackgroundSha1, effectiveUrl );
                return;
            }

            ImageCacheInfo cache = loadImageCacheSafe();
            if ( cache.backgroundSha1 != null
                 && Objects.equals( cache.backgroundUrl, effectiveUrl )
                 && new File( computedImageFile( cache.backgroundSha1 ) ).exists() ) {
                return;
            }

            String computedSha1 = downloadAndHashToContentAddressedFile( effectiveUrl, ".background_download.tmp" );
            cache.backgroundUrl = effectiveUrl;
            cache.backgroundSha1 = computedSha1;
            saveImageCache( cache );
        }
        catch ( IOException e ) {
            throw new ModpackException( "Unable to download/fetch mod pack background.", e );
        }
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
     * Schema for the {@code .image_cache} sidecar. URL is recorded alongside the hash so
     * a future URL change (custom logo added to an existing pack, default URL swapped,
     * etc.) invalidates the entry and triggers a re-download.
     */
    private static class ImageCacheInfo
    {
        String logoUrl;
        String logoSha1;
        String backgroundUrl;
        String backgroundSha1;
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
