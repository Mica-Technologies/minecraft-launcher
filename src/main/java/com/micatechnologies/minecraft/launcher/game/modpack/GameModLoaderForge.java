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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.launcher.utilities.JSONUtilities;
import com.micatechnologies.minecraft.launcher.utilities.JsonHelper;
import com.micatechnologies.minecraft.launcher.consts.ForgeConstants;
import com.micatechnologies.minecraft.launcher.consts.LocalPathConstants;
import com.micatechnologies.minecraft.launcher.consts.ModPackConstants;
import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
import com.micatechnologies.minecraft.launcher.exceptions.ModpackException;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.files.RuntimeManager;
import com.micatechnologies.minecraft.launcher.files.SynchronizedFileManager;
import com.micatechnologies.minecraft.launcher.game.modpack.manifests.ManifestRuleUtilities;
import com.micatechnologies.minecraft.launcher.utilities.HashUtilities;
import com.micatechnologies.minecraft.launcher.utilities.NetworkUtilities;
import com.micatechnologies.minecraft.launcher.utilities.SystemUtilities;
import com.micatechnologies.minecraft.launcher.utilities.objects.GameMode;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Class representation of a modpack Forge jar application
 *
 * @author Mica Technologies
 * @version 1.0.1
 * @since 1.0
 */
class GameModLoaderForge extends ManagedGameFile implements GameModLoader
{

    /**
     * Forge jar version
     *
     * @since 1.0
     */
    private final String forgeVersion;

    /**
     * Forge jar Minecraft version
     *
     * @since 1.0
     */
    private final String minecraftVersion;

    /**
     * Forge jar Minecraft arguments
     *
     * @since 1.0
     */
    private final String minecraftArguments;

    /**
     * Forge jar Minecraft main class
     *
     * @since 1.0
     */
    private final String minecraftMainClass;

    /**
     * Parent mod pack
     *
     * @since 1.0
     */
    private final GameModPack parentModPack;

    /** One-pass snapshot of the installer JAR's entry names, so the per-library
     *  {@link #hasEmbeddedMavenEntry}/{@link #hasEmbeddedTopLevelEntry} probes
     *  become O(1) set lookups instead of each re-opening the multi-MB installer
     *  and re-parsing its full central directory (100-250+ opens per launch).
     *  Cleared when the installer is re-downloaded. */
    private transient java.util.Set< String > cachedJarEntryNames;

    /** Cached parse of the Forge version manifest (modern {@code version.json} or
     *  legacy {@code versionInfo}). Re-opening + re-GSON-parsing it on every
     *  accessor was a major per-launch cost. */
    private transient JsonObject cachedForgeVersionManifest;

    /** Cached library list built from {@link #cachedForgeVersionManifest}; built
     *  once instead of twice per launch (classpath + download both ask). */
    private transient ArrayList< GameAsset > cachedForgeLibrariesList;

    /**
     * Constructor for {@link GameModLoaderForge}. Creates a {@link GameModLoaderForge} object with the specified remote
     * URL, SHA-1 hash and associated mod pack.
     *
     * @param remoteURL     URL of Forge
     * @param sha1Hash      hash of Forge
     * @param parentModPack parent mod pack
     *
     * @throws ModpackException if unable to download or update
     * @since 1.0
     */
    public GameModLoaderForge( String remoteURL, String sha1Hash, GameModPack parentModPack ) throws ModpackException
    {
        // Populate remote file information/configuration
        super( remoteURL, SystemUtilities.buildFilePath( parentModPack.getPackRootFolder(),
                                                         ModPackConstants.MODPACK_FORGE_JAR_LOCAL_PATH ), sha1Hash, ManagedGameFileHashType.SHA1 );

        // Store parent mod pack
        this.parentModPack = parentModPack;

        // Download Forge app
        updateLocalFile();

        // Store Forge/MC information
        JsonObject forgeVersionManifest = getForgeVersionManifest();
        if ( !forgeVersionManifest.has( ForgeConstants.FORGE_VERSION_MANIFEST_ID_KEY ) ) {
            throw new ModpackException( "Forge version manifest is missing required field: " +
                                                ForgeConstants.FORGE_VERSION_MANIFEST_ID_KEY );
        }
        if ( !forgeVersionManifest.has( ForgeConstants.FORGE_VERSION_MANIFEST_MAIN_CLASS_KEY ) ) {
            throw new ModpackException( "Forge version manifest is missing required field: " +
                                                ForgeConstants.FORGE_VERSION_MANIFEST_MAIN_CLASS_KEY );
        }
        forgeVersion = forgeVersionManifest.get( ForgeConstants.FORGE_VERSION_MANIFEST_ID_KEY ).getAsString();
        minecraftVersion = parseMinecraftVersion( forgeVersionManifest, forgeVersion );
        minecraftArguments = parseMinecraftArguments( forgeVersionManifest );
        minecraftMainClass = forgeVersionManifest.get( ForgeConstants.FORGE_VERSION_MANIFEST_MAIN_CLASS_KEY )
                                                 .getAsString();
    }

    /**
     * Derives the Minecraft version this Forge build targets from its version
     * manifest. Prefers the manifest's {@code inheritsFrom} field (modern Forge
     * names the base MC version there); failing that, strips the {@code -forge}
     * suffix from the manifest's own version id; and as a last resort returns
     * the supplied fallback id unchanged.
     *
     * @param forgeVersionManifest the parsed Forge version manifest
     * @param fallbackVersionId    the Forge version id to fall back on when no
     *                             {@code inheritsFrom} is present
     *
     * @return the resolved Minecraft version string
     * @since 1.0
     */
    private static String parseMinecraftVersion( JsonObject forgeVersionManifest, String fallbackVersionId ) {
        if ( forgeVersionManifest.has( ForgeConstants.FORGE_VERSION_MANIFEST_INHERITS_FROM_KEY ) ) {
            return forgeVersionManifest.get( ForgeConstants.FORGE_VERSION_MANIFEST_INHERITS_FROM_KEY ).getAsString();
        }

        if ( fallbackVersionId != null && fallbackVersionId.contains( "-forge" ) ) {
            return fallbackVersionId.substring( 0, fallbackVersionId.indexOf( "-forge" ) );
        }
        return fallbackVersionId;
    }

    /**
     * Extracts the Minecraft game arguments from a Forge version manifest,
     * normalising across Forge eras. Legacy Forge stores them as a single
     * pre-joined {@code minecraftArguments} string; modern Forge (1.13+) stores
     * them as an {@code arguments.game} array, which is flattened via
     * {@link ManifestRuleUtilities#flattenArguments}. Returns an empty string
     * when neither form is present.
     *
     * @param forgeVersionManifest the parsed Forge version manifest
     *
     * @return the game arguments as a single space-joined string, or an empty
     *         string when the manifest declares none
     * @since 1.0
     */
    private static String parseMinecraftArguments( JsonObject forgeVersionManifest ) {
        if ( forgeVersionManifest.has( ForgeConstants.FORGE_VERSION_MANIFEST_MINECRAFT_ARGS_KEY ) ) {
            return forgeVersionManifest.get( ForgeConstants.FORGE_VERSION_MANIFEST_MINECRAFT_ARGS_KEY ).getAsString();
        }

        if ( forgeVersionManifest.has( "arguments" ) ) {
            JsonObject argumentsObject = forgeVersionManifest.getAsJsonObject( "arguments" );
            if ( argumentsObject.has( "game" ) ) {
                return ManifestRuleUtilities.flattenArguments( argumentsObject.getAsJsonArray( "game" ) );
            }
        }

        return "";
    }

    /**
     * Gets the version of Forge for this mod loader instance.
     *
     * @return Forge version
     *
     * @since 1.0
     */
    public String getForgeVersion() {
        return forgeVersion;
    }

    /**
     * Gets the version of Minecraft for this Forge mod loader instance.
     *
     * @return Forge Minecraft version
     *
     * @since 1.0
     */
    public String getMinecraftVersion() {
        return minecraftVersion;
    }

    /**
     * Gets the arguments for this Forge mod loader instance.
     *
     * @return Minecraft Forge arguments
     *
     * @since 1.0
     */
    public String getMinecraftArguments() {
        return minecraftArguments;
    }

    /**
     * Gets the JVM arguments from this Forge mod loader's version manifest, if present. Modern Forge versions
     * (1.13+) may specify JVM arguments like module system flags in {@code arguments.jvm}.
     *
     * @return Forge JVM arguments string, or empty string if none
     *
     * @throws ModpackException if unable to read the Forge manifest
     *
     * @since 2.0
     */
    public String getForgeJvmArguments() throws ModpackException {
        JsonObject forgeVersionManifest = getForgeVersionManifest();
        if ( forgeVersionManifest.has( "arguments" ) ) {
            JsonObject argumentsObject = forgeVersionManifest.getAsJsonObject( "arguments" );
            if ( argumentsObject.has( "jvm" ) ) {
                return ManifestRuleUtilities.flattenArguments( argumentsObject.getAsJsonArray( "jvm" ) );
            }
        }
        return "";
    }

    /**
     * Gets the main class for this Forge mod loader instance.
     *
     * @return Minecraft Forge main class
     *
     * @since 1.0
     */
    public String getMinecraftMainClass() {
        return minecraftMainClass;
    }

    // ====================================================================
    // GameModLoader interface — generalised names that the launcher and
    // GUI use polymorphically across Forge / NeoForge / Fabric. The
    // Forge-named methods above stay as aliases for callers that
    // specifically need the Forge implementation (notably the modpack
    // editor and the legacy gradle-style call sites that haven't
    // migrated yet).
    // ====================================================================

    /**
     * {@inheritDoc}
     *
     * <p>Returns {@code "Forge"}; the NeoForge subclass overrides this to
     * report its own name.</p>
     *
     * @return the constant {@code "Forge"}
     * @since 1.0
     */
    @Override
    public String getName() {
        return "Forge";
    }

    /** Maven-coord prefix the installer's own artifact lives under.
     *  Used in {@code getForgeAssets} to detect the loader's own
     *  library entries (which need universal-jar / install-jar
     *  redirection rather than a direct maven download). Overridden
     *  by NeoForge to point at its own group / artifact id. */
    protected String loaderCoordPrefix() {
        return "net.minecraftforge:forge:";
    }

    /**
     * {@inheritDoc}
     *
     * <p>For Forge this is the Forge version id (the same value as
     * {@link #getForgeVersion()}).</p>
     *
     * @return the Forge version id
     * @since 1.0
     */
    @Override
    public String getLoaderVersion() {
        return forgeVersion;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Delegates to {@link #getForgeJvmArguments()} — modern Forge (1.13+)
     * may declare module-system JVM flags in its manifest's
     * {@code arguments.jvm} section.</p>
     *
     * @return the Forge JVM arguments string, or an empty string if none
     *
     * @throws ModpackException if the Forge version manifest cannot be read
     * @since 1.0
     */
    @Override
    public String getJvmArguments() throws ModpackException {
        return getForgeJvmArguments();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Delegates to {@link #buildForgeClasspath(GameMode, GameModPackProgressProvider)},
     * which resolves, downloads/verifies, and assembles the deduplicated Forge
     * library classpath (including the patched client and {@code MC_EXTRA}
     * artifacts for modern Forge).</p>
     *
     * @param gameAppMode      client or server side the classpath is built for
     * @param progressProvider progress sink for UI feedback, or {@code null}
     *
     * @return the platform-separated classpath string
     *
     * @throws ModpackException if libraries cannot be resolved, downloaded, or verified
     * @since 1.0
     */
    @Override
    public String buildClasspath( GameMode gameAppMode,
                                   GameModPackProgressProvider progressProvider )
            throws ModpackException
    {
        return buildForgeClasspath( gameAppMode, progressProvider );
    }

    /**
     * {@inheritDoc}
     *
     * <p>Delegates to {@link #runForgeProcessors(GameMode, GameModPackProgressProvider, String)},
     * which executes the Forge install-processor pipeline (modern Forge 1.13+)
     * to produce the patched client JAR. A no-op for legacy Forge installers
     * that carry no processors.</p>
     *
     * @param gameAppMode      client or server side the processors run for
     * @param progressProvider progress sink for UI feedback, or {@code null}
     * @param runtimeComponent the Java runtime component used to launch processors
     *
     * @throws ModpackException if any install processor fails
     * @since 1.0
     */
    @Override
    public void runPostInstallSteps( GameMode gameAppMode,
                                      GameModPackProgressProvider progressProvider,
                                      String runtimeComponent ) throws ModpackException
    {
        runForgeProcessors( gameAppMode, progressProvider, runtimeComponent );
    }

    /**
     * Legacy Forge (1.7-1.12) ships a dedicated server bootstrap class
     * — {@code net.minecraftforge.fml.relauncher.ServerLaunchWrapper}
     * — that the client main ({@code net.minecraft.launchwrapper.Launch})
     * doesn't double for. Modern Forge (1.13+) uses
     * {@code cpw.mods.modlauncher.Launcher} or
     * {@code cpw.mods.bootstraplauncher.BootstrapLauncher} for both
     * sides, differentiated by {@code --launchTarget fmlclient} vs
     * {@code forgeserver}; return null so the dispatcher reuses the
     * client main and we let arg-construction do the rest.
     *
     * <p>NeoForge inherits this — its installer is always modern, so
     * the {@code cpw.mods.*} branch fires and the default (null,
     * meaning "use client main") is returned.</p>
     */
    @Override
    public String getServerMainClass() {
        if ( minecraftMainClass != null
                && ( minecraftMainClass.startsWith( "cpw.mods.modlauncher" )
                        || minecraftMainClass.startsWith( "cpw.mods.bootstraplauncher" ) ) ) {
            // Modern Forge / NeoForge — client + server share the
            // entry point; the dispatcher will reuse the client main.
            return null;
        }
        // Legacy Forge 1.7-1.12 — distinct server class.
        return "net.minecraftforge.fml.relauncher.ServerLaunchWrapper";
    }

    /**
     * Invalidates the per-instance installer caches (entry-name set, version
     * manifest, library list) whenever the installer JAR is re-downloaded, so a
     * fresh jar is re-read rather than serving stale cached metadata.
     */
    @Override
    public boolean updateLocalFile() throws ModpackException {
        boolean changed = super.updateLocalFile();
        if ( changed ) {
            cachedJarEntryNames = null;
            cachedForgeVersionManifest = null;
            cachedForgeLibrariesList = null;
        }
        return changed;
    }

    /**
     * Gets the {@link JarFile} for this Forge mod loader instance.
     *
     * @return Forge {@link JarFile}
     *
     * @throws ModpackException if unable to get Forge {@link JarFile}
     * @since 1.0
     */
    private JarFile getForgeJarFile() throws ModpackException {
        try {
            return new JarFile( getFullLocalFilePath() );
        }
        catch ( IOException e ) {
            throw new ModpackException( LocalizationManager.UNABLE_ACCESS_FORGE_JAR_TEXT, e );
        }
    }

    /**
     * Gets the list of libraries for this Forge mod loader instance.
     *
     * @return Forge library list
     *
     * @throws ModpackException if unable to load Forge version manifest
     * @since 1.0
     */
    private ArrayList< GameAsset > getForgeLibrariesList() throws ModpackException {
        ArrayList< GameAsset > cachedList = cachedForgeLibrariesList;
        if ( cachedList != null ) {
            return cachedList;
        }
        // Get Forge version manifest libraries array
        JsonObject forgeVersionManifest = getForgeVersionManifest();
        JsonArray forgeAssetsArray = forgeVersionManifest.getAsJsonArray(
                ForgeConstants.FORGE_VERSION_MANIFEST_LIBRARIES_KEY );

        // Create list for storing processed libraries
        ArrayList< GameAsset > forgeAssets = new ArrayList<>();

        // Loop Through Each Asset and Process
        for ( JsonElement forgeAsset : forgeAssetsArray ) {
            // Get Asset Object and Information
            JsonObject forgeAssetObj = forgeAsset.getAsJsonObject();
            String forgeAssetName = JsonHelper.getRequiredString( forgeAssetObj,
                    ForgeConstants.FORGE_VERSION_MANIFEST_LIBRARY_NAME_KEY );

            // Get Asset Downloads Information
            JsonObject forgeAssetDownloadsObj;
            JsonObject forgeAssetDownloadsArtifactObj = null;
            if ( forgeAssetObj.has( ForgeConstants.FORGE_VERSION_MANIFEST_LIBRARY_DOWNLOADS_KEY ) ) {

                forgeAssetDownloadsObj = forgeAssetObj.getAsJsonObject(
                        ForgeConstants.FORGE_VERSION_MANIFEST_LIBRARY_DOWNLOADS_KEY );
                forgeAssetDownloadsArtifactObj = forgeAssetDownloadsObj.getAsJsonObject(
                        ForgeConstants.FORGE_VERSION_MANIFEST_LIBRARY_ARTIFACT_KEY );
            }

            // Get Repo Path
            String forgeAssetRepoPath;
            boolean isSpecifiedRepoPath = false;
            String inferredForgeAssetRepoPath = forgeAssetName.substring( forgeAssetName.indexOf( ":" ) + 1 )
                                                              .replace( ":", "-" );
            if ( forgeAssetDownloadsArtifactObj != null &&
                    forgeAssetDownloadsArtifactObj.has( ForgeConstants.FORGE_VERSION_MANIFEST_LIBRARY_PATH_KEY ) ) {
                forgeAssetRepoPath = forgeAssetDownloadsArtifactObj.get(
                        ForgeConstants.FORGE_VERSION_MANIFEST_LIBRARY_PATH_KEY ).getAsString();
                isSpecifiedRepoPath = true;
            }
            else {
                forgeAssetRepoPath = inferredForgeAssetRepoPath;
            }

            // Get SHA-1, if present
            String sha1 = null;
            if ( forgeAssetDownloadsArtifactObj != null &&
                    forgeAssetDownloadsArtifactObj.has( ForgeConstants.FORGE_VERSION_MANIFEST_LIBRARY_SHA1_KEY ) ) {
                sha1 = forgeAssetDownloadsArtifactObj.get( ForgeConstants.FORGE_VERSION_MANIFEST_LIBRARY_SHA1_KEY )
                                                     .getAsString();
            }

            // Modern Forge installers (1.13+) embed both "forge-<ver>.jar" (containing launch target services like
            // fmlclient) and "forge-<ver>-universal.jar" (containing main Forge code). Both must be on the classpath.
            // Legacy Forge (1.7-1.12) only has the universal JAR.
            boolean addUniversalAsExtra = false;
            String universalRepoPath = null;
            if ( isSpecifiedRepoPath && forgeAssetName.startsWith( loaderCoordPrefix() ) &&
                    !forgeAssetRepoPath.contains( "-universal" ) ) {
                universalRepoPath = forgeAssetRepoPath.replace( ".jar", "-universal.jar" );
                if ( hasEmbeddedMavenEntry( universalRepoPath ) && hasEmbeddedMavenEntry( forgeAssetRepoPath ) ) {
                    // Modern Forge: both JARs exist. Keep the base JAR as-is and add universal separately.
                    addUniversalAsExtra = true;
                }
                else if ( hasEmbeddedMavenEntry( universalRepoPath ) ) {
                    // Legacy Forge: only universal exists. Replace the base path.
                    forgeAssetRepoPath = universalRepoPath;
                    sha1 = null;
                }
            }

            // Legacy Forge (1.7-1.12 era) puts the universal jar at the TOP LEVEL of the
            // installer — e.g. "/forge-1.7.10-10.13.4.1614-1.7.10-universal.jar" — not
            // under "maven/...". install_profile.json's versionInfo.libraries lists the
            // Forge artifact as "net.minecraftforge:forge:VERSION" with a maven URL, but
            // Forge's maven doesn't actually serve that file — trying to download it
            // 404s. Redirect to the embedded top-level entry via a jar:file:// URL
            // (the same mechanism the modern path uses for maven/-embedded artifacts).
            boolean legacyForgeUniversalEmbedded = false;
            String legacyForgeUniversalEntry = null;
            if ( !isSpecifiedRepoPath && forgeAssetName.startsWith( loaderCoordPrefix() ) ) {
                String topLevelUniversalName = inferredForgeAssetRepoPath + "-universal.jar";
                if ( hasEmbeddedTopLevelEntry( topLevelUniversalName ) ) {
                    legacyForgeUniversalEmbedded = true;
                    legacyForgeUniversalEntry = topLevelUniversalName;
                    // No SHA-1 to verify against — the installer bundles the universal jar
                    // but Forge's manifest doesn't carry a hash for the bare library entry.
                    sha1 = null;
                }
            }

            String forgeAssetURL = resolveForgeAssetUrl( forgeAssetObj, forgeAssetDownloadsArtifactObj,
                                                          forgeAssetName, forgeAssetRepoPath,
                                                          isSpecifiedRepoPath,
                                                          legacyForgeUniversalEmbedded,
                                                          legacyForgeUniversalEntry );
            String localForgeAssetFilePath = buildForgeAssetLocalPath(
                    forgeAssetName, forgeAssetRepoPath, isSpecifiedRepoPath, inferredForgeAssetRepoPath );
            ForgeAssetRequirements reqs = readForgeAssetRequirements( forgeAssetObj );

            // Build Forge Asset Object and Add to List of Assets
            if ( sha1 != null ) {
                forgeAssets.add( new GameAsset( forgeAssetURL, localForgeAssetFilePath, sha1,
                                                ManagedGameFileHashType.SHA1, reqs.clientReq(),
                                                reqs.serverReq() ) );
            }
            else {
                forgeAssets.add( new GameAsset( forgeAssetURL, localForgeAssetFilePath,
                                                reqs.clientReq(), reqs.serverReq() ) );
            }

            // For modern Forge: also add the universal JAR as a separate classpath entry
            if ( addUniversalAsExtra && universalRepoPath != null ) {
                String universalURL = getEmbeddedMavenEntryURL( universalRepoPath );
                String universalLocalPath = universalRepoPath.replace( "/", File.separator );
                forgeAssets.add( new GameAsset( universalURL, universalLocalPath,
                                                reqs.clientReq(), reqs.serverReq() ) );
            }
        }

        // Return resulting list of Forge Assets
        cachedForgeLibrariesList = forgeAssets;
        return forgeAssets;
    }

    /** clientReq + serverReq pair extracted from a Forge library entry — both
     *  default true when the keys are absent, matching pre-extraction behaviour. */
    private record ForgeAssetRequirements( boolean clientReq, boolean serverReq ) {}

    /**
     * Resolves the download URL for one Forge library entry. Branches in priority order:
     * <ol>
     *   <li>{@code downloads.artifact.url} from the manifest (modern Forge 1.13+).</li>
     *   <li>{@code jar:file://} pointing inside the installer's {@code maven/} subtree
     *       when {@link #hasEmbeddedMavenEntry} confirms the artifact is bundled.</li>
     *   <li>{@code jar:file://} pointing at the installer's top-level universal-jar
     *       entry when the legacy detection above set
     *       {@code legacyForgeUniversalEmbedded} (pre-1.13 path).</li>
     *   <li>Fall back to a Maven repository URL — top-level {@code "url"} on the
     *       library entry, else well-known repos picked from the artifact's group ID
     *       (Mojang for {@code net.minecraft:}, Forge for {@code net.minecraftforge:},
     *       Maven Central otherwise). Special-cased {@code lzma:lzma:0.0.1} routes
     *       to SpongePowered's repo since neither Central nor Forge Maven serves it.</li>
     * </ol>
     */
    private String resolveForgeAssetUrl( JsonObject forgeAssetObj,
                                          JsonObject forgeAssetDownloadsArtifactObj,
                                          String forgeAssetName,
                                          String forgeAssetRepoPath,
                                          boolean isSpecifiedRepoPath,
                                          boolean legacyForgeUniversalEmbedded,
                                          String legacyForgeUniversalEntry ) {
        if ( forgeAssetDownloadsArtifactObj != null
                && forgeAssetDownloadsArtifactObj.has( ForgeConstants.FORGE_VERSION_MANIFEST_LIBRARY_URL_KEY )
                && forgeAssetDownloadsArtifactObj.get( ForgeConstants.FORGE_VERSION_MANIFEST_LIBRARY_URL_KEY )
                                                   .getAsString().trim().length() > 0 ) {
            return forgeAssetDownloadsArtifactObj.get(
                    ForgeConstants.FORGE_VERSION_MANIFEST_LIBRARY_URL_KEY ).getAsString();
        }
        if ( isSpecifiedRepoPath && hasEmbeddedMavenEntry( forgeAssetRepoPath ) ) {
            return getEmbeddedMavenEntryURL( forgeAssetRepoPath );
        }
        if ( legacyForgeUniversalEmbedded ) {
            return getEmbeddedTopLevelEntryURL( legacyForgeUniversalEntry );
        }

        // Fall through to a Maven repository URL.
        String repoURL = JsonHelper.getString( forgeAssetObj, "url", null );
        if ( repoURL == null || repoURL.isBlank() ) {
            repoURL = "https://repo1.maven.org/maven2/";
            if ( forgeAssetName.contains( "net.minecraft:" ) ) {
                repoURL = "https://libraries.minecraft.net/";
            }
            else if ( forgeAssetName.contains( "net.minecraftforge:" ) ) {
                repoURL = "https://maven.minecraftforge.net/";
            }
        }
        if ( !repoURL.endsWith( "/" ) ) {
            repoURL += "/";
        }

        String url;
        if ( isSpecifiedRepoPath ) {
            url = repoURL + forgeAssetRepoPath;
        }
        else {
            int colon = forgeAssetName.indexOf( ":" );
            url = repoURL
                    + forgeAssetName.substring( 0, colon ).replace( ".", "/" )
                    + "/"
                    + forgeAssetName.substring( colon + 1 ).replace( ":", "/" )
                    + "/"
                    + forgeAssetRepoPath
                    + ".jar";
        }

        // Fallback for lzma:lzma:0.0.1 which is not hosted on Maven Central or Forge Maven.
        // Required by Forge 1.7-1.12, only available from SpongePowered.
        if ( url.contains( "lzma/lzma/0.0.1" ) && !url.contains( "spongepowered" ) ) {
            url = "https://repo.spongepowered.org/maven/lzma/lzma/0.0.1/lzma-0.0.1.jar";
        }
        return url;
    }

    /** Builds the on-disk relative path the launcher caches a Forge library at.
     *  Modern entries with an explicit {@code downloads.artifact.path} map directly
     *  to the platform-separator form of that path; legacy entries get a path
     *  reconstructed from the Maven coordinate ({@code group/artifact/version-classifier.jar}). */
    private String buildForgeAssetLocalPath( String forgeAssetName,
                                              String forgeAssetRepoPath,
                                              boolean isSpecifiedRepoPath,
                                              String inferredForgeAssetRepoPath ) {
        if ( isSpecifiedRepoPath ) {
            return forgeAssetRepoPath.replace( "/", File.separator );
        }
        int colon = forgeAssetName.indexOf( ":" );
        return forgeAssetName.substring( 0, colon ).replace( ".", File.separator )
                + File.separator
                + forgeAssetName.substring( colon + 1 ).replace( ":", File.separator )
                + File.separator
                + inferredForgeAssetRepoPath
                + LocalPathConstants.JAR_FILE_EXTENSION;
    }

    /** Reads the {@code clientreq} / {@code serverreq} flags off a Forge library
     *  entry. Both default to {@code true} when absent — matches Forge's own
     *  default + the launcher's pre-extraction behaviour. */
    private ForgeAssetRequirements readForgeAssetRequirements( JsonObject forgeAssetObj ) {
        boolean clientReq = !forgeAssetObj.has( ForgeConstants.FORGE_VERSION_MANIFEST_LIBRARY_CLIENT_REQ_KEY )
                || forgeAssetObj.get( ForgeConstants.FORGE_VERSION_MANIFEST_LIBRARY_CLIENT_REQ_KEY ).getAsBoolean();
        boolean serverReq = !forgeAssetObj.has( ForgeConstants.FORGE_VERSION_MANIFEST_LIBRARY_SERVER_REQ_KEY )
                || forgeAssetObj.get( ForgeConstants.FORGE_VERSION_MANIFEST_LIBRARY_SERVER_REQ_KEY ).getAsBoolean();
        return new ForgeAssetRequirements( clientReq, serverReq );
    }

    /** Lazily builds (once) the set of every entry name in the installer JAR.
     *  Converts the hot {@code hasEmbedded*} probes from a fresh JarFile open +
     *  central-directory parse each call into an O(1) set lookup. A failed read
     *  returns an empty set without caching so a transient error can be retried. */
    private java.util.Set< String > jarEntryNames() {
        java.util.Set< String > cached = cachedJarEntryNames;
        if ( cached != null ) {
            return cached;
        }
        java.util.Set< String > names = new java.util.HashSet<>();
        try ( JarFile forgeJarFile = getForgeJarFile() ) {
            Enumeration< JarEntry > entries = forgeJarFile.entries();
            while ( entries.hasMoreElements() ) {
                names.add( entries.nextElement().getName() );
            }
        }
        catch ( IOException | ModpackException e ) {
            return names;  // don't cache a failed read
        }
        cachedJarEntryNames = names;
        return names;
    }

    private boolean hasEmbeddedMavenEntry( String repoPath ) {
        return jarEntryNames().contains( "maven/" + repoPath );
    }

    private String getEmbeddedMavenEntryURL( String repoPath ) {
        File forgeInstaller = SynchronizedFileManager.getSynchronizedFile( getFullLocalFilePath() );
        return "jar:" + forgeInstaller.toURI() + "!/maven/" + repoPath;
    }

    /** Legacy Forge sibling of {@link #hasEmbeddedMavenEntry} — checks
     *  for an entry at the TOP LEVEL of the installer jar (not under
     *  the {@code maven/} subtree). Used to detect the bundled
     *  universal jar in pre-1.13 installers, which sit at the root. */
    private boolean hasEmbeddedTopLevelEntry( String entryName ) {
        return jarEntryNames().contains( entryName );
    }

    /** Build a {@code jar:file://} URL pointing at a top-level entry
     *  inside the installer jar. Mirror of
     *  {@link #getEmbeddedMavenEntryURL} for the legacy installer
     *  layout. */
    private String getEmbeddedTopLevelEntryURL( String entryName ) {
        File forgeInstaller = SynchronizedFileManager.getSynchronizedFile( getFullLocalFilePath() );
        return "jar:" + forgeInstaller.toURI() + "!/" + entryName;
    }

    /**
     * Downloads and SHA-1 verifies every Forge library required for the given
     * side, in parallel on the shared bounded download pool. Each asset is
     * assigned the Forge libs folder as its local-path prefix, then
     * downloaded/verified concurrently; the batch is bounded at 30 minutes and
     * pending downloads are cancelled on interrupt, timeout, or failure.
     *
     * @param gameAppMode      client or server side libraries are fetched for
     * @param progressProvider progress sink for UI feedback, or {@code null}
     *
     * @throws ModpackException if the library list cannot be built, a download
     *                          fails verification, or the batch times out / is
     *                          interrupted
     * @since 1.0
     */
    private void downloadForgeAssets( GameMode gameAppMode, GameModPackProgressProvider progressProvider )
    throws ModpackException
    {
        // Get list of Forge Assets
        ArrayList< GameAsset > forgeAssetsList = getForgeLibrariesList();
        final String localPathPrefix = SystemUtilities.buildFilePath( parentModPack.getPackRootFolder(),
                                                                       ModPackConstants.MODPACK_FORGE_LIBS_LOCAL_FOLDER );

        // Verify + download in parallel — these are 40-80 multi-MB JARs and the
        // sequential loop serialized both the cold-install downloads and the
        // warm-launch SHA-1 hashing onto one core. ManagedGameFile's per-path
        // locks + verify cache are concurrent and each asset writes a distinct
        // path, so this is safe. Mirrors GameLibraryManifest.downloadVerifyLibraries.
        List< java.util.concurrent.Future< ? > > futures = new ArrayList<>();
        for ( GameAsset forgeAsset : forgeAssetsList ) {
            futures.add( com.micatechnologies.minecraft.launcher.utilities.DownloadExecutor.submit(
                    ( java.util.concurrent.Callable< Void > ) () -> {
                forgeAsset.setLocalPathPrefix( localPathPrefix );
                forgeAsset.updateLocalFile( gameAppMode );
                if ( progressProvider != null ) {
                    progressProvider.submitProgress( LocalizationManager.format(
                            "forgeLoader.verifiedAsset",
                            SynchronizedFileManager.getSynchronizedFile(
                                    forgeAsset.getFullLocalFilePath() ).getName() ),
                                                     ( 60.0 / ( double ) forgeAssetsList.size() ) );
                }
                return null;
            } ) );
        }
        // Drain the futures on the shared bounded download pool, bounded at 30 minutes.
        // awaitAll cancels still-pending siblings on interrupt/timeout/failure.
        try {
            com.micatechnologies.minecraft.launcher.utilities.DownloadExecutor.awaitAll(
                    futures, 30 * 60 * 1000L );
        }
        catch ( java.util.concurrent.TimeoutException e ) {
            throw new ModpackException( "Forge library downloads did not complete within 30 minutes." );
        }
        catch ( InterruptedException e ) {
            Thread.currentThread().interrupt();
            throw new ModpackException( "Interrupted while downloading Forge libraries.", e );
        }
        catch ( java.util.concurrent.ExecutionException e ) {
            Throwable cause = e.getCause();
            if ( cause instanceof ModpackException modpackException ) {
                throw modpackException;
            }
            throw new ModpackException( "Failed to download a Forge library: "
                                                + ( cause == null ? e.getMessage() : cause.getMessage() ), e );
        }
    }

    /**
     * Runs the Forge install processors defined in install_profile.json. Modern Forge (1.13+) requires a multi-step
     * patching process to produce the patched client JAR from the vanilla Minecraft client. This method downloads
     * processor libraries, resolves data variables, and runs each processor in sequence.
     *
     * @param gameAppMode      client or server mode
     * @param progressProvider progress provider for UI feedback
     * @param javaMajorVersion the Java major version to use for running processors
     *
     * @throws ModpackException if processors fail
     *
     * @since 2.0
     */
    void runForgeProcessors( GameMode gameAppMode, GameModPackProgressProvider progressProvider, String runtimeComponent )
    throws ModpackException
    {
        String side = gameAppMode == GameMode.CLIENT ? "client" : "server";
        String libsFolder = SystemUtilities.buildFilePath( parentModPack.getPackRootFolder(),
                                                            ModPackConstants.MODPACK_FORGE_LIBS_LOCAL_FOLDER );

        // Read install_profile.json from the Forge installer
        JsonObject installProfile;
        try ( JarFile forgeJar = getForgeJarFile() ) {
            JarEntry profileEntry = forgeJar.getJarEntry( "install_profile.json" );
            if ( profileEntry == null ) {
                Logger.logDebug( LocalizationManager.get( "log.forgeLoader.noInstallProfileLegacy" ) );
                return;
            }
            try ( InputStream is = forgeJar.getInputStream( profileEntry );
                  InputStreamReader reader = new InputStreamReader( is ) ) {
                installProfile = JSONUtilities.getGson().fromJson( reader, JsonObject.class );
            }
        }
        catch ( IOException e ) {
            throw new ModpackException( "Unable to read install_profile.json from Forge installer.", e );
        }

        if ( !installProfile.has( "processors" ) || !installProfile.has( "data" ) ) {
            Logger.logDebug( LocalizationManager.get( "log.forgeLoader.noProcessorsSkipping" ) );
            return;
        }

        // Check if the patched output already exists
        JsonObject data = installProfile.getAsJsonObject( "data" );
        JsonObject patchedObj = JsonHelper.getJsonObject( data, "PATCHED" );
        if ( patchedObj != null ) {
            String patchedCoord = JsonHelper.getString( patchedObj, side, null );
            if ( patchedCoord != null ) {
                String patchedPath = mavenCoordToPath( patchedCoord );
                File patchedFile = new File( libsFolder, patchedPath );
                if ( patchedFile.exists() && patchedFile.length() > 0 ) {
                    Logger.logStd( LocalizationManager.get( "log.forgeLoader.patchedClientExists" ) );
                    return;
                }
            }
        }

        Logger.logStd( LocalizationManager.format( "log.forgeLoader.runningProcessors", side ) );

        // Download install_profile libraries. These JARs are about to be
        // executed as child JVMs (processor pipeline below), so any SHA-1 the
        // profile declares for them is enforced: on already-present files, on
        // bytes extracted from the installer, and on bytes downloaded from
        // the network. The declared hashes are collected (keyed by the
        // forward-slash relative path under the libs folder) so the processor
        // loop can re-check each JAR right before execution.
        java.util.Map< String, String > declaredLibSha1ByPath = new java.util.HashMap<>();
        java.util.Set< String > verifiedLibPaths = new java.util.HashSet<>();
        JsonArray profileLibs = installProfile.getAsJsonArray( "libraries" );
        for ( JsonElement libEl : profileLibs ) {
            JsonObject lib = libEl.getAsJsonObject();
            JsonObject downloads = lib.has( "downloads" ) ? lib.getAsJsonObject( "downloads" ) : null;
            JsonObject artifact = downloads != null && downloads.has( "artifact" ) ?
                                  downloads.getAsJsonObject( "artifact" ) : null;
            if ( artifact == null ) {
                continue;
            }

            String path = JsonHelper.getRequiredString( artifact, "path" );
            String url = JsonHelper.getString( artifact, "url", "" );
            String sha1 = JsonHelper.getString( artifact, "sha1", null );
            boolean hasSha1 = sha1 != null && !sha1.isBlank();
            if ( hasSha1 ) {
                declaredLibSha1ByPath.put( path, sha1 );
            }
            else {
                Logger.logWarningSilent( LocalizationManager.format(
                        "log.forgeLoader.libraryNoHash", path ) );
            }

            // The Forge installer's library descriptors contribute both a relative
            // path and a URL. Both come straight from JSON inside the installer
            // JAR. A hostile installer (compromised packForgeURL host with a
            // matching attacker-supplied hash) could specify path="../../something"
            // to escape the libs folder, or url="http://..." to enable passive
            // MITM. Validate both before use.
            if ( path.indexOf( '\0' ) >= 0
                    || path.startsWith( "/" )
                    || path.startsWith( "\\" )
                    || ( path.length() >= 2 && path.charAt( 1 ) == ':' ) ) {
                throw new ModpackException( "Refusing Forge library with unsafe path: " + path );
            }
            java.nio.file.Path libsBase = new File( libsFolder ).toPath().toAbsolutePath().normalize();
            java.nio.file.Path resolved = libsBase.resolve(
                    path.replace( "/", File.separator ) ).normalize();
            if ( !resolved.startsWith( libsBase ) ) {
                throw new ModpackException( "Refusing Forge library path that escapes libs folder: " + path );
            }
            File localFile = resolved.toFile();

            if ( localFile.exists() ) {
                if ( !hasSha1 || HashUtilities.verifySHA1( localFile, sha1 ) ) {
                    if ( hasSha1 ) {
                        verifiedLibPaths.add( path );
                    }
                    continue;
                }
                // Existing file no longer matches the profile's declared hash —
                // fall through and re-acquire it instead of executing it as-is.
                Logger.logWarningSilent( LocalizationManager.format(
                        "log.forgeLoader.existingLibraryFailedHash", path ) );
                //noinspection ResultOfMethodCallIgnored
                localFile.delete();
            }

            if ( url.isEmpty() ) {
                // Embedded in installer JAR
                if ( hasEmbeddedMavenEntry( path ) ) {
                    try {
                        extractEmbeddedMavenEntry( path, localFile );
                    }
                    catch ( IOException e ) {
                        throw new ModpackException( "Failed to extract embedded library: " + path, e );
                    }
                    if ( hasSha1 && !HashUtilities.verifySHA1( localFile, sha1 ) ) {
                        //noinspection ResultOfMethodCallIgnored
                        localFile.delete();
                        throw new ModpackException(
                                "Embedded processor library failed hash verification: " + path );
                    }
                    if ( hasSha1 ) {
                        verifiedLibPaths.add( path );
                    }
                }
                continue;
            }

            // Refuse any URL scheme that isn't https — same rationale as the
            // ManagedGameFile download gate.
            int schemeEnd = url.indexOf( ':' );
            if ( schemeEnd < 0 || !"https".equalsIgnoreCase( url.substring( 0, schemeEnd ) ) ) {
                throw new ModpackException( "Refusing Forge library with non-https URL: " + url );
            }

            localFile.getParentFile().mkdirs();
            // Post-download integrity gate: these JARs run as child JVMs, so
            // the profile's declared SHA-1 must hold for the bytes actually
            // received. Bounded retry absorbs transient corruption.
            final int maxAttempts = 3;
            boolean downloadAccepted = false;
            for ( int attempt = 1; attempt <= maxAttempts && !downloadAccepted; attempt++ ) {
                try {
                    NetworkUtilities.downloadFileFromURL( url, localFile );
                }
                catch ( IOException e ) {
                    throw new ModpackException( "Failed to download processor library: " + url, e );
                }
                downloadAccepted = !hasSha1 || HashUtilities.verifySHA1( localFile, sha1 );
                if ( !downloadAccepted ) {
                    //noinspection ResultOfMethodCallIgnored
                    localFile.delete();
                    Logger.logWarningSilent( LocalizationManager.format(
                            "log.forgeLoader.libraryFailedHashAttempt", attempt, maxAttempts, path ) );
                }
            }
            if ( !downloadAccepted ) {
                throw new ModpackException(
                        "Processor library failed hash verification after " + maxAttempts + " attempts: " +
                                path + " (from " + url + ")" );
            }
            if ( hasSha1 ) {
                verifiedLibPaths.add( path );
            }

            if ( progressProvider != null ) {
                progressProvider.submitProgress( LocalizationManager.format(
                        "forgeLoader.downloadedProcessorLib", localFile.getName() ), 1.0 );
            }
        }

        // Get the vanilla Minecraft JAR path
        String minecraftJarPath = parentModPack.getPackRootFolder() + File.separator +
                ModPackConstants.MODPACK_MINECRAFT_JAR_LOCAL_PATH;

        // Ensure the Java runtime is available for running processors
        String javaExec = RuntimeManager.getJavaPath( runtimeComponent );

        // Run each processor
        JsonArray processors = installProfile.getAsJsonArray( "processors" );
        for ( int i = 0; i < processors.size(); i++ ) {
            JsonObject proc = processors.get( i ).getAsJsonObject();

            // Check side filter
            if ( proc.has( "sides" ) ) {
                JsonArray sides = proc.getAsJsonArray( "sides" );
                boolean sideMatch = false;
                for ( JsonElement s : sides ) {
                    if ( s.getAsString().equals( side ) ) {
                        sideMatch = true;
                        break;
                    }
                }
                if ( !sideMatch ) {
                    continue;
                }
            }

            String processorJar = JsonHelper.getRequiredString( proc, "jar" );
            Logger.logStd( LocalizationManager.format( "log.forgeLoader.runningProcessor",
                                                       ( i + 1 ), processors.size(), processorJar ) );

            // Build classpath for this processor, verifying each JAR against the
            // profile's declared hash right before it gets executed. Artifacts
            // verified earlier in this run are skipped via verifiedLibPaths;
            // coordinates the profile carries no hash for have nothing to check.
            String processorJarPath = mavenCoordToPath( processorJar );
            verifyProcessorArtifact( libsFolder, processorJarPath, declaredLibSha1ByPath, verifiedLibPaths );
            StringBuilder procClasspath = new StringBuilder();
            procClasspath.append( new File( libsFolder, processorJarPath ).getAbsolutePath() );

            if ( proc.has( "classpath" ) ) {
                for ( JsonElement cpEl : proc.getAsJsonArray( "classpath" ) ) {
                    String cpPath = mavenCoordToPath( cpEl.getAsString() );
                    verifyProcessorArtifact( libsFolder, cpPath, declaredLibSha1ByPath, verifiedLibPaths );
                    procClasspath.append( File.pathSeparator );
                    procClasspath.append( new File( libsFolder, cpPath ).getAbsolutePath() );
                }
            }

            // Find main class from processor JAR manifest
            String mainClass;
            try ( JarFile procJarFile = new JarFile(
                    new File( libsFolder, mavenCoordToPath( processorJar ) ) ) ) {
                mainClass = procJarFile.getManifest().getMainAttributes().getValue( "Main-Class" );
            }
            catch ( IOException e ) {
                throw new ModpackException( "Cannot read processor JAR manifest: " + processorJar, e );
            }

            if ( mainClass == null ) {
                throw new ModpackException( "Processor JAR has no Main-Class: " + processorJar );
            }

            // Resolve args
            JsonArray argsArray = JsonHelper.getRequiredJsonArray( proc, "args" );
            List< String > resolvedArgs = new ArrayList<>();
            for ( JsonElement argEl : argsArray ) {
                String arg = argEl.getAsString();
                arg = resolveProcessorArg( arg, data, side, libsFolder, minecraftJarPath );
                resolvedArgs.add( arg );
            }

            // Build the command
            List< String > command = new ArrayList<>();
            command.add( javaExec );
            command.add( "-cp" );
            command.add( procClasspath.toString() );
            command.add( mainClass );
            command.addAll( resolvedArgs );

            // Run the processor (10-minute timeout to prevent indefinite hangs)
            try {
                ProcessBuilder pb = new ProcessBuilder( command );
                pb.directory( new File( parentModPack.getPackRootFolder() ) );
                pb.inheritIO();
                Process process = pb.start();
                boolean completed = process.waitFor( 10, java.util.concurrent.TimeUnit.MINUTES );
                if ( !completed ) {
                    process.destroyForcibly();
                    throw new ModpackException(
                            "Forge processor timed out after 10 minutes: " + processorJar );
                }
                int exitCode = process.exitValue();
                if ( exitCode != 0 ) {
                    process.destroyForcibly();
                    throw new ModpackException(
                            "Forge processor failed (exit code " + exitCode + "): " + processorJar );
                }
            }
            catch ( IOException | InterruptedException e ) {
                throw new ModpackException( "Failed to run Forge processor: " + processorJar, e );
            }

            if ( progressProvider != null ) {
                progressProvider.submitProgress( LocalizationManager.format(
                        "forgeLoader.completedProcessor", processorJar ),
                                                 10.0 / processors.size() );
            }
        }

        Logger.logStd( LocalizationManager.get( "log.forgeLoader.processorsCompleted" ) );
    }

    /**
     * Converts a Maven coordinate to a local path under a libraries
     * folder. Delegates to {@link MavenArtifactPath#toRelativePathStrict}
     * so coordinate parsing + path-traversal validation are shared
     * across every modloader.
     */
    private static String mavenCoordToPath( String coord ) throws ModpackException {
        return MavenArtifactPath.toRelativePathStrict( coord );
    }

    /**
     * Verifies a processor-pipeline JAR against the SHA-1 the install profile
     * declared for it, immediately before the JAR is placed on a child JVM's
     * classpath. No-op when the profile carries no hash for the path (nothing
     * to check) or when this run already verified it ({@code alreadyVerified}
     * avoids re-hashing shared libraries once per processor).
     *
     * @param libsFolder      libraries folder the relative path resolves under
     * @param relativePath    forward-slash relative path of the artifact
     * @param declaredSha1    profile-declared SHA-1 hashes keyed by relative path
     * @param alreadyVerified relative paths verified earlier in this run
     *
     * @throws ModpackException if the on-disk JAR no longer matches its declared hash
     *
     * @since 2026.6
     */
    private static void verifyProcessorArtifact( String libsFolder, String relativePath,
                                                  java.util.Map< String, String > declaredSha1,
                                                  java.util.Set< String > alreadyVerified )
    throws ModpackException
    {
        String sha1 = declaredSha1.get( relativePath );
        if ( sha1 == null || alreadyVerified.contains( relativePath ) ) {
            return;
        }
        File artifactFile = new File( libsFolder, relativePath.replace( "/", File.separator ) );
        if ( !HashUtilities.verifySHA1( artifactFile, sha1 ) ) {
            throw new ModpackException(
                    "Processor JAR failed hash verification before execution: " + relativePath );
        }
        alreadyVerified.add( relativePath );
    }

    /**
     * Resolves a processor argument by substituting data variables and special tokens.
     *
     * <p>Forge's official installer (see {@code PostProcessors.java} in the
     * Forge installer source) replaces tokens in this order:</p>
     * <ol>
     *   <li>Entries from the {@code data} section (per-side {@code client}/
     *       {@code server} values, often Maven coords or literal strings).</li>
     *   <li>Magic tokens the installer fills in at runtime — {@code SIDE},
     *       {@code MINECRAFT_JAR}, {@code MINECRAFT_VERSION}, {@code ROOT},
     *       {@code LIBRARY_DIR}, {@code INSTALLER}. These never appear in
     *       {@code data}; the installer just knows what they mean.</li>
     * </ol>
     *
     * <p>The data branch is tried first so a pack that explicitly redirects
     * one of these names (rare but allowed) wins over the magic default.
     * Forge 1.20.1's DOWNLOAD_MOJMAPS processor exercises the {@code SIDE}
     * fallback — without magic-token handling the literal string
     * {@code "{SIDE}"} reached installertools and the processor blew up
     * with "Missing download info for {SIDE} mappings".</p>
     */
    private String resolveProcessorArg( String arg, JsonObject data, String side, String libsFolder,
                                         String minecraftJarPath )
    throws ModpackException
    {
        // {VARIABLE} -> resolved from data section, falling back to magic tokens
        if ( arg.startsWith( "{" ) && arg.endsWith( "}" ) ) {
            String key = arg.substring( 1, arg.length() - 1 );

            // 1) Data-section lookup. Returns the per-side value if the key
            //    has a matching entry.
            JsonObject dataEntry = JsonHelper.getJsonObject( data, key );
            if ( dataEntry != null ) {
                String value = JsonHelper.getString( dataEntry, side, null );
                if ( value != null ) {
                    return resolveDataValue( value, libsFolder );
                }
            }

            // 2) Magic-token fallbacks. Match Forge's installer one-for-one
            //    so any 1.13+ install_profile.json processes identically to
            //    when run through forge-installer.jar directly.
            switch ( key ) {
                case "SIDE":
                    return side;
                case "MINECRAFT_JAR":
                    return minecraftJarPath;
                case "MINECRAFT_VERSION":
                    return minecraftVersion;
                case "ROOT":
                    return parentModPack.getPackRootFolder();
                case "LIBRARY_DIR":
                    return libsFolder;
                case "INSTALLER":
                    return getFullLocalFilePath();
                default:
                    // Unknown token — log so a future Forge release that
                    // adds a new magic placeholder fails loudly here
                    // instead of silently passing the literal "{FOO}" to
                    // the processor and producing a cryptic downstream
                    // error.
                    Logger.logWarningSilent( LocalizationManager.format(
                            "log.forgeLoader.unrecognizedProcessorToken", arg ) );
                    return arg;
            }
        }

        // [maven:coord] -> path to library
        if ( arg.startsWith( "[" ) && arg.endsWith( "]" ) ) {
            return new File( libsFolder, mavenCoordToPath( arg ) ).getAbsolutePath();
        }

        return arg;
    }

    /**
     * Resolves a data value which can be a Maven coordinate [group:artifact:version], a path inside the installer JAR
     * (/data/file.lzma), or a literal string ('value').
     */
    private String resolveDataValue( String value, String libsFolder ) throws ModpackException {
        // Literal string in single quotes
        if ( value.startsWith( "'" ) && value.endsWith( "'" ) ) {
            return value.substring( 1, value.length() - 1 );
        }

        // Maven coordinate in brackets
        if ( value.startsWith( "[" ) && value.endsWith( "]" ) ) {
            return new File( libsFolder, mavenCoordToPath( value ) ).getAbsolutePath();
        }

        // Path inside the installer JAR (e.g. /data/client.lzma)
        if ( value.startsWith( "/" ) ) {
            String entryName = value.substring( 1 ); // remove leading /
            File extractedFile = new File( libsFolder, "forge-installer-data" + File.separator +
                    entryName.replace( "/", File.separator ) );
            if ( !extractedFile.exists() ) {
                try ( JarFile forgeJar = getForgeJarFile() ) {
                    JarEntry entry = forgeJar.getJarEntry( entryName );
                    if ( entry == null ) {
                        throw new ModpackException( "Missing entry in Forge installer: " + entryName );
                    }
                    extractedFile.getParentFile().mkdirs();
                    try ( InputStream entryStream = forgeJar.getInputStream( entry ) ) {
                        Files.copy( entryStream, extractedFile.toPath(), StandardCopyOption.REPLACE_EXISTING );
                    }
                }
                catch ( IOException e ) {
                    throw new ModpackException( "Failed to extract from Forge installer: " + entryName, e );
                }
            }
            return extractedFile.getAbsolutePath();
        }

        return value;
    }

    /**
     * Extracts an artifact embedded under the installer JAR's {@code maven/}
     * subtree to a local file, creating parent directories and overwriting any
     * existing destination.
     *
     * @param repoPath    the artifact's repo-relative path beneath {@code maven/}
     *                    inside the installer JAR
     * @param destination the local file to write the extracted bytes to
     *
     * @throws IOException      if the entry is missing or cannot be read/copied
     * @throws ModpackException if the installer JAR cannot be opened
     * @since 1.0
     */
    private void extractEmbeddedMavenEntry( String repoPath, File destination ) throws IOException, ModpackException {
        try ( JarFile forgeJar = getForgeJarFile() ) {
            JarEntry entry = forgeJar.getJarEntry( "maven/" + repoPath );
            if ( entry == null ) {
                throw new IOException( "Embedded maven entry not found: maven/" + repoPath );
            }
            destination.getParentFile().mkdirs();
            try ( InputStream entryStream = forgeJar.getInputStream( entry ) ) {
                Files.copy( entryStream, destination.toPath(), StandardCopyOption.REPLACE_EXISTING );
            }
        }
    }

    /**
     * Resolves, downloads/verifies, and assembles the full Forge classpath for
     * the given side. Builds the library list, fetches every required artifact
     * via {@link #downloadForgeAssets(GameMode, GameModPackProgressProvider)},
     * then collects the side-required library paths into a {@link LinkedHashSet}
     * (deduplicated, insertion-ordered). For modern Forge (1.13+) it also appends
     * the processor-produced {@code PATCHED} client JAR and the {@code MC_EXTRA}
     * resources JAR named in {@code install_profile.json}, when present on disk.
     *
     * @param gameAppMode      client or server side the classpath is built for
     * @param progressProvider progress sink for UI feedback, or {@code null}
     *
     * @return the platform-separated classpath string
     *
     * @throws ModpackException if libraries cannot be resolved, downloaded, or verified
     * @since 1.0
     */
    String buildForgeClasspath( GameMode gameAppMode, GameModPackProgressProvider progressProvider )
    throws ModpackException
    {
        // Get list of Forge Assets
        ArrayList< GameAsset > forgeAssetsList = getForgeLibrariesList();
        // Update progress provider if present
        if ( progressProvider != null ) {
            progressProvider.submitProgress( LocalizationManager.get( "forgeLoader.gotAssetList" ), 20.0 );
        }

        // Download the assets
        downloadForgeAssets( gameAppMode, progressProvider );

        // For each asset, add to classpath
        StringBuilder classpath = new StringBuilder();
        LinkedHashSet< String > classpathEntries = new LinkedHashSet<>();
        for ( GameAsset forgeAsset : forgeAssetsList ) {
            // Skip assets not required for the active side: downloadForgeAssets() never
            // fetched them, so adding their path here would put a non-existent file on the
            // classpath (tolerated today only because the JVM ignores missing -cp entries).
            if ( !forgeAsset.isRequiredFor( gameAppMode ) ) {
                continue;
            }
            String localPathPrefix = SystemUtilities.buildFilePath( parentModPack.getPackRootFolder(),
                                                                    ModPackConstants.MODPACK_FORGE_LIBS_LOCAL_FOLDER );
            forgeAsset.setLocalPathPrefix( localPathPrefix );
            classpathEntries.add( forgeAsset.getFullLocalFilePath() );

            // Update progress provider if present
            if ( progressProvider != null ) {
                progressProvider.submitProgress( LocalizationManager.format(
                        "forgeLoader.addedToClasspath", forgeAsset.getLocalFilePath() ),
                                                 ( 20.0 / ( double ) forgeAssetsList.size() ) );
            }
        }

        // Add the patched client JAR from Forge processors if it exists (modern Forge 1.13+)
        String libsFolder = SystemUtilities.buildFilePath( parentModPack.getPackRootFolder(),
                                                            ModPackConstants.MODPACK_FORGE_LIBS_LOCAL_FOLDER );
        try ( JarFile forgeJar = getForgeJarFile() ) {
            JarEntry profileEntry = forgeJar.getJarEntry( "install_profile.json" );
            if ( profileEntry != null ) {
                JsonObject installProfile;
                try ( InputStream is = forgeJar.getInputStream( profileEntry );
                      InputStreamReader reader = new InputStreamReader( is ) ) {
                    installProfile = JSONUtilities.getGson().fromJson( reader, JsonObject.class );
                }
                if ( installProfile.has( "data" ) ) {
                    JsonObject data = installProfile.getAsJsonObject( "data" );
                    String side = gameAppMode == GameMode.CLIENT ? "client" : "server";
                    if ( data.has( "PATCHED" ) && data.getAsJsonObject( "PATCHED" ).has( side ) ) {
                        String patchedCoord = data.getAsJsonObject( "PATCHED" ).get( side ).getAsString();
                        String patchedPath = mavenCoordToPath( patchedCoord );
                        File patchedFile = new File( libsFolder, patchedPath );
                        if ( patchedFile.exists() ) {
                            classpathEntries.add( patchedFile.getAbsolutePath() );
                            Logger.logDebug( LocalizationManager.format(
                                    "log.forgeLoader.addedPatchedClient", patchedFile.getName() ) );
                        }
                    }
                    // Also add MC_EXTRA (contains resources split from the vanilla JAR)
                    if ( data.has( "MC_EXTRA" ) && data.getAsJsonObject( "MC_EXTRA" ).has( side ) ) {
                        String extraCoord = data.getAsJsonObject( "MC_EXTRA" ).get( side ).getAsString();
                        String extraPath = mavenCoordToPath( extraCoord );
                        File extraFile = new File( libsFolder, extraPath );
                        if ( extraFile.exists() ) {
                            classpathEntries.add( extraFile.getAbsolutePath() );
                            Logger.logDebug( LocalizationManager.format(
                                    "log.forgeLoader.addedMcExtra", extraFile.getName() ) );
                        }
                    }
                }
            }
        }
        catch ( IOException e ) {
            Logger.logWarningSilent( LocalizationManager.format(
                    "log.forgeLoader.couldNotCheckPatchedClient", e.getMessage() ) );
        }

        for ( String cpEntry : classpathEntries ) {
            if ( classpath.length() > 0 ) {
                classpath.append( File.pathSeparator );
            }
            classpath.append( cpEntry );
        }

        return classpath.toString();
    }

    /**
     * Returns the parsed Forge version manifest, caching it on first read so
     * repeated accessors (libraries list, main class, arguments) don't re-open
     * and re-parse the installer JAR each launch. The cache is invalidated by
     * {@link #updateLocalFile()} when the installer is re-downloaded.
     *
     * @return the cached Forge version manifest as a {@link JsonObject}
     *
     * @throws ModpackException if the manifest cannot be located or parsed
     * @since 1.0
     */
    private JsonObject getForgeVersionManifest() throws ModpackException {
        JsonObject cached = cachedForgeVersionManifest;
        if ( cached != null ) {
            return cached;
        }
        JsonObject manifest = readForgeVersionManifest();
        cachedForgeVersionManifest = manifest;
        return manifest;
    }

    /**
     * Reads and parses the Forge version manifest from the installer JAR,
     * normalising across Forge eras. Modern Forge (1.13+, plus some late 1.12.x
     * builds) carries a top-level {@code version.json} with the full
     * launcher-version manifest shape; legacy Forge (1.7.10 through early
     * 1.12.x) has no such file and instead nests the equivalent metadata under
     * {@code versionInfo} inside {@code install_profile.json}. This method
     * returns whichever is found, so downstream callers need not branch on era.
     *
     * @return the Forge version manifest as a {@link JsonObject}
     *
     * @throws ModpackException if the installer cannot be opened, neither
     *                          manifest form can be parsed, or no version
     *                          metadata is found
     * @since 1.0
     */
    private JsonObject readForgeVersionManifest() throws ModpackException {
        try ( JarFile forgeJarFile = getForgeJarFile() ) {
            // Modern Forge (1.13+, plus some 1.12.x builds): version.json
            // lives at the root of the installer jar with the full
            // launcher-version manifest shape.
            JarEntry versionEntry = forgeJarFile.getJarEntry( ForgeConstants.FORGE_JAR_VERSION_FILE_NAME );
            if ( versionEntry != null ) {
                try ( InputStream inputStream = forgeJarFile.getInputStream( versionEntry );
                      InputStreamReader inputStreamReader = new InputStreamReader( inputStream ) ) {
                    return JSONUtilities.getGson().fromJson( inputStreamReader, JsonObject.class );
                }
                catch ( IOException e ) {
                    throw new ModpackException(
                            LocalizationManager.UNABLE_OPEN_FORGE_VERSION_MANIFEST_PARSING_TEXT, e );
                }
            }

            // Legacy Forge (1.7.10 through ~1.12.x early builds): no
            // top-level version.json. The same metadata — id, mainClass,
            // libraries, inheritsFrom, minecraftArguments — lives nested
            // inside install_profile.json under "versionInfo" with the
            // same field shape as the modern version.json. Returning that
            // nested object lets every downstream caller of
            // getForgeVersionManifest (libraries list, main-class read,
            // arguments parse) work without branching on Forge era.
            JarEntry profileEntry = forgeJarFile.getJarEntry( "install_profile.json" );
            if ( profileEntry != null ) {
                try ( InputStream is = forgeJarFile.getInputStream( profileEntry );
                      InputStreamReader reader = new InputStreamReader( is ) ) {
                    JsonObject installProfile = JSONUtilities.getGson().fromJson( reader, JsonObject.class );
                    if ( installProfile != null && installProfile.has( "versionInfo" ) ) {
                        com.google.gson.JsonElement versionInfo = installProfile.get( "versionInfo" );
                        if ( versionInfo.isJsonObject() ) {
                            return versionInfo.getAsJsonObject();
                        }
                    }
                }
                catch ( IOException e ) {
                    throw new ModpackException(
                            LocalizationManager.UNABLE_OPEN_FORGE_VERSION_MANIFEST_PARSING_TEXT, e );
                }
            }
        }
        catch ( IOException e ) {
            throw new ModpackException( LocalizationManager.UNABLE_CLOSE_STREAMS_TEXT, e );
        }

        throw new ModpackException( LocalizationManager.UNABLE_FIND_FORGE_VERSION_FILE_TEXT );
    }
}
