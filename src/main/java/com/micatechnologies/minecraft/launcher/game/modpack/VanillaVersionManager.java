/*
 * Copyright (c) 2026 Mica Technologies
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License,
 * or (at your option) any later version.
 */

package com.micatechnologies.minecraft.launcher.game.modpack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.launcher.config.ConfigManager;
import com.micatechnologies.minecraft.launcher.config.GameModeManager;
import com.micatechnologies.minecraft.launcher.consts.ModPackConstants;
import com.micatechnologies.minecraft.launcher.consts.RuntimeConstants;
import com.micatechnologies.minecraft.launcher.exceptions.ModpackException;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.files.RuntimeManager;
import com.micatechnologies.minecraft.launcher.files.SynchronizedFileManager;
import com.micatechnologies.minecraft.launcher.game.modpack.manifests.GameLibraryManifest;
import com.micatechnologies.minecraft.launcher.game.modpack.manifests.GameVersionManifest;
import com.micatechnologies.minecraft.launcher.utilities.SystemUtilities;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Manages vanilla (non-Forge) Minecraft version installation, listing, and launching.
 *
 * @author Mica Technologies
 * @version 1.0
 * @since 3.0
 */
public class VanillaVersionManager
{
    /**
     * Cached list of all versions from the Mojang version manifest.
     */
    private static List< JsonObject > allVersions = null;

    /**
     * Fetches and caches all versions from the Mojang version manifest.
     *
     * @return list of version objects with fields: id, type, url, releaseTime;
     *         empty when the manifest cannot be downloaded or parsed
     *
     * @since 3.0
     */
    public static synchronized List< JsonObject > getAllVersions() {
        if ( allVersions != null ) {
            return allVersions;
        }

        allVersions = new ArrayList<>();

        // Ensure the Mojang version manifest is downloaded and cached
        try {
            GameVersionManifest.ensureManifestDownloaded();
        }
        catch ( ModpackException e ) {
            Logger.logError( "Failed to download Minecraft version manifest." );
            Logger.logThrowable( e );
            return allVersions;
        }

        // Read the cached manifest to get the versions array
        try {
            File manifestFile = SynchronizedFileManager.getSynchronizedFile(
                    com.micatechnologies.minecraft.launcher.files.LocalPathManager.getMinecraftVersionManifestFilePath() );
            JsonObject manifest = com.micatechnologies.minecraft.launcher.utilities.FileUtilities.readAsJsonObject(
                    manifestFile );
            JsonArray versions = manifest.getAsJsonArray( "versions" );
            for ( JsonElement el : versions ) {
                allVersions.add( el.getAsJsonObject() );
            }
        }
        catch ( Exception e ) {
            Logger.logError( "Failed to load Minecraft version manifest for vanilla versions." );
            Logger.logThrowable( e );
        }

        return allVersions;
    }

    /**
     * Returns versions filtered by type.
     *
     * @param type "release", "snapshot", "old_beta", "old_alpha", or "all"
     *
     * @return filtered list of version objects; the full list when
     *         {@code type} is {@code "all"}
     *
     * @since 3.0
     */
    public static List< JsonObject > getVersionsByType( String type ) {
        List< JsonObject > all = getAllVersions();
        if ( "all".equals( type ) ) {
            return all;
        }
        List< JsonObject > filtered = new ArrayList<>();
        for ( JsonObject v : all ) {
            if ( v.get( "type" ).getAsString().equals( type ) ) {
                filtered.add( v );
            }
        }
        return filtered;
    }

    /**
     * Returns the list of installed vanilla version IDs from config.
     *
     * @return the configured installed vanilla version IDs
     *
     * @since 3.0
     */
    public static List< String > getInstalledVersionIds() {
        return ConfigManager.getInstalledVanillaVersions();
    }

    /**
     * Installs a vanilla version by adding its ID to the config. No-op if
     * the version is already installed.
     *
     * @param versionId the Mojang version ID to mark installed
     *
     * @since 3.0
     */
    public static void installVersion( String versionId ) {
        List< String > installed = new ArrayList<>( getInstalledVersionIds() );
        if ( !installed.contains( versionId ) ) {
            installed.add( versionId );
            ConfigManager.setInstalledVanillaVersions( installed );
        }
    }

    /**
     * Uninstalls a vanilla version by removing its ID from the config.
     *
     * @param versionId the Mojang version ID to remove
     *
     * @since 3.0
     */
    public static void uninstallVersion( String versionId ) {
        List< String > installed = new ArrayList<>( getInstalledVersionIds() );
        installed.remove( versionId );
        ConfigManager.setInstalledVanillaVersions( installed );
    }

    /**
     * Checks if a version is currently installed.
     *
     * @param versionId the Mojang version ID to test
     *
     * @return {@code true} if the version is recorded as installed
     *
     * @since 3.0
     */
    public static boolean isInstalled( String versionId ) {
        return getInstalledVersionIds().contains( versionId );
    }

    /**
     * Returns the display name for a vanilla version (e.g. "Minecraft 1.20.4").
     *
     * @param versionId the Mojang version ID
     *
     * @return the human-readable display name
     *
     * @since 3.0
     */
    public static String getDisplayName( String versionId ) {
        return "Minecraft " + versionId;
    }

    /**
     * Returns the friendly name for use in the modpack selection list.
     *
     * @param versionId the Mojang version ID
     *
     * @return the friendly name including the {@code (Vanilla)} suffix
     *
     * @since 3.0
     */
    public static String getFriendlyName( String versionId ) {
        return "Minecraft " + versionId + " (Vanilla)";
    }
}
