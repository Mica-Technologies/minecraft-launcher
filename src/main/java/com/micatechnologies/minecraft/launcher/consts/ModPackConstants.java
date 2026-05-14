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

package com.micatechnologies.minecraft.launcher.consts;

import java.io.File;
import java.net.URL;

/**
 * Class containing constants that support the mod pack functionality of the launcher.
 * <p>
 * NOTE: This class should NOT contain display strings that are visible to the end-user. All localizable strings MUST be
 * stored and retrieved using {@link com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager}.
 *
 * @author Mica Technologies
 * @version 1.0
 * @since 2.0
 */
public class ModPackConstants
{
    /**
     * Canonical file extension for Mica Minecraft modpack manifest files. Distinct from a
     * generic {@code .json} so the OS can associate it with this launcher (planned
     * jpackage {@code --file-associations} hook), and the website can serve modpack
     * manifests with a content-type / name that's unambiguously ours rather than
     * looking like a random JSON download.
     *
     * <p>The leading dot is included to keep call-sites tidy: {@code "pack" + EXTENSION}
     * gives {@code "pack.mmcjson"}.</p>
     *
     * @since 3.1
     */
    public static final String MODPACK_FILE_EXTENSION = ".mmcjson";

    /** Glob pattern for file-chooser filters. Matches {@link #MODPACK_FILE_EXTENSION}. */
    public static final String MODPACK_FILE_GLOB = "*" + MODPACK_FILE_EXTENSION;

    /** Human-readable description shown in file-chooser filters and (eventually) by the
     *  installer when registering the file association. */
    public static final String MODPACK_FILE_DESCRIPTION = "Mica Minecraft Modpack";

    /**
     * Modpack install folder relative path to Forge jar
     *
     * @since 1.0
     */
    public static final String MODPACK_FORGE_JAR_LOCAL_PATH = "bin" + File.separator + "modpack.jar";

    /**
     * Modpack install folder relative path to the Fabric loader's
     * cached profile JSON. Distinct from the Forge jar path because the
     * artifact is JSON (not a JAR) and lives separately in case a pack
     * ever needs both (theoretical multi-loader future).
     *
     * @since 2026.5
     */
    public static final String MODPACK_FABRIC_PROFILE_LOCAL_PATH =
            "bin" + File.separator + "fabric-profile.json";

    // ====================================================================
    // Modloader type identifiers — values for the modpack manifest's
    // `packModLoader` field. Stored lowercase; matched case-insensitively.
    // ====================================================================

    public static final String MOD_LOADER_FORGE    = "forge";
    public static final String MOD_LOADER_NEOFORGE = "neoforge";
    public static final String MOD_LOADER_FABRIC   = "fabric";

    /** Default modloader assumed when {@code packModLoader} is absent
     *  from a manifest — the historically-only-supported value, kept as
     *  the default for back-compat with every existing modpack. */
    public static final String MOD_LOADER_DEFAULT  = MOD_LOADER_FORGE;

    /**
     * Modpack install folder relative path to Forge jar
     *
     * @since 1.0
     */
    public static final String MODPACK_MINECRAFT_JAR_LOCAL_PATH = "bin" + File.separator + "minecraft.jar";

    /**
     * Modpack install folder relative path to mods folder
     *
     * @since 1.0
     */
    public static final String MODPACK_FORGE_MODS_LOCAL_FOLDER = "mods";

    /**
     * Modpack install folder relative path to game libraries
     *
     * @since 1.0
     */
    public static final String MODPACK_FORGE_LIBS_LOCAL_FOLDER = "libraries";

    /**
     * Modpack install folder relative path to configs folder
     *
     * @since 1.0
     */
    public static final String MODPACK_FORGE_CONFIGS_LOCAL_FOLDER = "config";

    /**
     * Modpack install folder relative path to game natives
     *
     * @since 1.0
     */
    public static final String MODPACK_MINECRAFT_NATIVES_LOCAL_FOLDER = "bin" + File.separator + "natives";

    /**
     * Modpack install folder relative path to game resource packs
     *
     * @since 1.0
     */
    public static final String MODPACK_FORGE_RESOURCEPACKS_LOCAL_FOLDER = "resourcepacks";

    /**
     * Modpack install folder relative path to game shader packs
     *
     * @since 1.0
     */
    public static final String MODPACK_FORGE_SHADERPACKS_LOCAL_FOLDER = "shaderpacks";

    /**
     * Modpack install folder relative path to game assets
     *
     * @since 1.0
     */
    public static final String MODPACK_MINECRAFT_ASSETS_LOCAL_FOLDER = "assets";

    /**
     * Modpack install folder relative path to pack logo
     *
     * @since 1.0
     */
    public static final String MODPACK_LOGO_LOCAL_FILE = "bin" + File.separator + "logo.png";

    /**
     * Modpack install folder relative path to pack background
     *
     * @since 1.0
     */
    public static final String MODPACK_BACKGROUND_LOCAL_FILE = "bin" + File.separator + "background.png";

    /**
     * Constant for the Microsoft(r) Windows platform
     *
     * @since 1.0
     */
    public static final String PLATFORM_WINDOWS = "windows";

    /**
     * Constant for the Apple macOS(r) platform
     *
     * @since 1.0
     */
    public static final String PLATFORM_MACOS = "osx";

    /**
     * Constant for Linux(r) platforms
     *
     * @since 1.0
     */
    public static final String PLATFORM_UNIX = "linux";

    /**
     * The URL of the mod pack background image that is used as a default. Uses a bundled resource to avoid depending on
     * external CDNs that can disappear. Falls back to the launcher icon if the dedicated background resource is missing.
     *
     * @since 2.0
     */
    public static final String MODPACK_DEFAULT_BG_URL = resolveResourceUrl( "/default_background.png",
                                                                              "/micaminecraftlauncher.png" );

    /**
     * The URL of the mod pack logo image that is used as a default. Uses the bundled launcher icon instead of
     * external CDN URLs that can disappear.
     *
     * @since 2.0
     */
    public static final String MODPACK_DEFAULT_LOGO_URL = resolveResourceUrl( "/micaminecraftlauncher.png", null );

    /**
     * Resolves a classpath resource to a URL string suitable for JavaFX Image and CSS url() usage.
     *
     * @param primary  the primary resource path (e.g. "/default_background.png")
     * @param fallback the fallback resource path if primary is not found, or null
     *
     * @return a URL string for the resource
     */
    private static String resolveResourceUrl( String primary, String fallback ) {
        URL url = ModPackConstants.class.getResource( primary );
        if ( url == null && fallback != null ) {
            url = ModPackConstants.class.getResource( fallback );
        }
        return url != null ? url.toExternalForm() : "";
    }

    /**
     * URL of manifest containing the manifest URLs of installable mod packs.
     *
     * @since 1.0
     */
    public static final String AVAILABLE_PACKS_MANIFEST_URL
            = "https://micauseaststorage.blob.core.windows.net/mc-launcher-api/installable.json";

    /**
     * The key that identifies the list of installable mod pack manifest URLs in the available mod packs manifest.
     *
     * @since 1.0
     */
    public static final String AVAILABLE_PACKS_MANIFEST_LIST_KEY = "available";

    /**
     * Template for formatting mod pack friendly names. First string is the mod pack name and the second string is the
     * mod pack version.
     *
     * @since 1.0
     */
    public static final String MODPACK_FRIENDLY_NAME_TEMPLATE = "%s: %s";

    /**
     * Download URL of Minecraft version manifest
     *
     * @since 1.0
     */
    public static final String MINECRAFT_VERSION_MANIFEST_URL
            = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";

}
