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
     * Modpack install folder relative path to Forge jar
     *
     * @since 1.0
     */
    public static final String MODPACK_FORGE_JAR_LOCAL_PATH = "bin" + File.separator + "modpack.jar";

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
     * The URL of the mod pack background image that is used as a default. This background image displays for mod packs
     * that do not have a background image, and when no mod packs are installed.
     *
     * @since 2.0
     */
    public static final String MODPACK_DEFAULT_BG_URL = "https://i.ytimg.com/vi/oi3A9Wkn8XA/maxresdefault.jpg";

    /**
     * The URL of the mod pack logo image that is used as a default. This mod pack logo image displays for mod packs
     * that do not have a mod pack logo image, and when no mod packs are installed.
     *
     * @since 2.0
     */
    public static final String MODPACK_DEFAULT_LOGO_URL
            = "https://cdn.freebiesupply.com/logos/large/2x/minecraft-1-logo-png-transparent.png";

    /**
     * URL of manifest containing the manifest URLs of installable mod packs.
     *
     * @since 1.0
     */
    public static final String AVAILABLE_PACKS_MANIFEST_URL
            = "https://minecraft.micatechnologies.com/files/packs/installable.json";

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
            = "https://launchermeta.mojang.com/mc/game/version_manifest.json";

}
