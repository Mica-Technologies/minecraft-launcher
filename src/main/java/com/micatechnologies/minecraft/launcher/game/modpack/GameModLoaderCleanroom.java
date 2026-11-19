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

package com.micatechnologies.minecraft.launcher.game.modpack;

import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.launcher.consts.ForgeConstants;
import com.micatechnologies.minecraft.launcher.consts.ModPackConstants;
import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
import com.micatechnologies.minecraft.launcher.exceptions.ModpackException;
import com.micatechnologies.minecraft.launcher.files.SynchronizedFileManager;
import com.micatechnologies.minecraft.launcher.game.modpack.manifests.ManifestRuleUtilities;
import com.micatechnologies.minecraft.launcher.utilities.DownloadExecutor;
import com.micatechnologies.minecraft.launcher.utilities.SystemUtilities;
import com.micatechnologies.minecraft.launcher.utilities.objects.GameMode;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Cleanroom implementation of {@link GameModLoader}: the Forge 1.12.2 fork on Java 25 and
 * LWJGL 3. The pack names the Cleanroom installer JAR in {@code packForgeURL} /
 * {@code packForgeHash} (or {@code packModLoaderURL} / {@code packModLoaderHash}); one installer
 * serves both sides. It is downloaded to {@code bin/modpack.jar}, like a Forge installer.
 *
 * <p>Unlike Forge, Cleanroom does not sit on top of the vanilla client.json: its
 * {@code version.json} is standalone, so {@link #getStandaloneVersionManifest()} hands the launch
 * that profile <em>in place of</em> Mojang's 1.12.2 client.json. The Java runtime
 * ({@code java-runtime-epsilon}), the libraries and natives (LWJGL 3, never vanilla LWJGL 2), the
 * asset index and the client or server jar all come from it. This loader's own classpath is only
 * what the installer embeds (the universal jar), verified against the SHA-1 the profile lists and
 * put first. There are no install processors.</p>
 *
 * <p>A dedicated server is assembled the way the installer's {@code server} mode does it, with
 * no installer run: the profile's libraries without the client-only natives, the server jar from
 * {@code downloads.server}, {@code serverMainClass}, and {@code --tweakClass} per
 * {@code serverTweakers} ({@link #getServerArguments()}).</p>
 *
 * <p>The decisions themselves live in the pure {@link CleanroomProfile}.</p>
 *
 * @since 2026.11
 */
final class GameModLoaderCleanroom extends ManagedGameFile implements GameModLoader
{
    /** The pack this loader belongs to. */
    private final GameModPack parentModPack;

    /** The installer's profiles, re-read whenever the installer is re-downloaded. */
    private volatile CleanroomProfile profile;

    /**
     * Downloads and verifies the installer, then reads its two profiles.
     *
     * @param remoteURL     URL of the Cleanroom installer JAR
     * @param sha1Hash      expected SHA-1 of the installer JAR
     * @param parentModPack the pack this loader belongs to
     *
     * @throws ModpackException if the installer cannot be downloaded, verified or read, or its
     *                          {@code version.json} lacks a required field
     * @since 2026.11
     */
    GameModLoaderCleanroom( String remoteURL, String sha1Hash, GameModPack parentModPack ) throws ModpackException
    {
        super( remoteURL, SystemUtilities.buildFilePath( parentModPack.getPackRootFolder(),
                                                         ModPackConstants.MODPACK_FORGE_JAR_LOCAL_PATH ),
               sha1Hash, ManagedGameFileHashType.SHA1 );
        this.parentModPack = parentModPack;
        updateLocalFile();
        if ( profile == null ) {
            profile = readProfile();
        }
    }

    /**
     * Builds a loader over an already-read profile, without touching the installer: a seam for
     * tests of how the loader reports its profile. {@link #buildClasspath} still needs the
     * installer on disk.
     *
     * @param profile       the installer's profiles
     * @param parentModPack the pack this loader belongs to
     *
     * @since 2026.11
     */
    GameModLoaderCleanroom( CleanroomProfile profile, GameModPack parentModPack )
    {
        super( "", SystemUtilities.buildFilePath( parentModPack.getPackRootFolder(),
                                                  ModPackConstants.MODPACK_FORGE_JAR_LOCAL_PATH ) );
        this.parentModPack = parentModPack;
        this.profile = profile;
    }

    /**
     * Checks the installer under its pack's current prepare run.
     *
     * @return the parent pack's prepare context
     */
    @Override
    protected LaunchPrepareContext prepareContext() {
        return parentModPack != null ? parentModPack.getPrepareContext() : LaunchPrepareContext.NONE;
    }

    /**
     * Re-reads the installer's profiles whenever the installer JAR is re-downloaded.
     *
     * @param context the prepare run this check belongs to
     *
     * @return {@code true} if the installer changed
     *
     * @throws ModpackException if the installer cannot be verified, downloaded or read
     */
    @Override
    public boolean updateLocalFile( LaunchPrepareContext context ) throws ModpackException {
        boolean changed = super.updateLocalFile( context );
        if ( changed ) {
            profile = readProfile();
        }
        return changed;
    }

    /**
     * Reads {@code version.json} (required) and {@code install_profile.json} (optional) out of the
     * installer.
     *
     * @return the parsed profile
     *
     * @throws ModpackException if the installer cannot be opened or {@code version.json} is
     *                          missing or unreadable
     */
    private CleanroomProfile readProfile() throws ModpackException {
        try ( JarFile installer = new JarFile( getFullLocalFilePath() ) ) {
            return CleanroomProfile.parse( readEntry( installer, ForgeConstants.FORGE_JAR_VERSION_FILE_NAME ),
                                           readEntry( installer, CleanroomProfile.INSTALL_PROFILE_ENTRY ) );
        }
        catch ( IOException e ) {
            throw new ModpackException( LocalizationManager.UNABLE_ACCESS_FORGE_JAR_TEXT, e );
        }
    }

    /**
     * Reads one entry of the installer as UTF-8 text.
     *
     * @param installer the open installer
     * @param name      the entry name
     *
     * @return the entry's text, or {@code null} when the installer has no such entry
     *
     * @throws IOException if the entry cannot be read
     */
    private static String readEntry( JarFile installer, String name ) throws IOException {
        JarEntry entry = installer.getJarEntry( name );
        if ( entry == null ) {
            return null;
        }
        try ( InputStream in = installer.getInputStream( entry ) ) {
            return new String( in.readAllBytes(), StandardCharsets.UTF_8 );
        }
    }

    /**
     * @return the installer's profiles as last read
     */
    CleanroomProfile profile() {
        return profile;
    }

    /**
     * {@inheritDoc}
     *
     * @return the constant {@code "Cleanroom"}
     * @since 2026.11
     */
    @Override
    public String getName() {
        return "Cleanroom";
    }

    /**
     * {@inheritDoc}
     *
     * <p>The install profile's {@code minecraft} ({@code 1.12.2}), never the standalone
     * {@code Cleanroom-0.7.14-alpha} id.</p>
     *
     * @return the Minecraft version
     * @since 2026.11
     */
    @Override
    public String getMinecraftVersion() {
        return profile.minecraftVersion();
    }

    /**
     * {@inheritDoc}
     *
     * @return the {@code version.json} id (e.g. {@code Cleanroom-0.7.14-alpha})
     * @since 2026.11
     */
    @Override
    public String getLoaderVersion() {
        return profile.id();
    }

    /**
     * {@inheritDoc}
     *
     * <p>The whole client game argument template of {@code version.json}, with its one
     * {@code --tweakClass}.</p>
     *
     * @return the client game arguments
     * @since 2026.11
     */
    @Override
    public String getMinecraftArguments() {
        return profile.minecraftArguments();
    }

    /**
     * {@inheritDoc}
     *
     * <p>The install profile's {@code jvmArgs}, plus {@code -XstartOnFirstThread} on macOS, as the
     * installer writes them into a launcher profile.</p>
     *
     * @return the client JVM arguments, space-separated
     * @since 2026.11
     */
    @Override
    public String getJvmArguments() {
        return String.join( " ", profile.clientJvmArgs( ManifestRuleUtilities.Host.current().os() ) );
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code top.outlands.foundation.boot.Foundation} for 0.7.14-alpha
     * @since 2026.11
     */
    @Override
    public String getMinecraftMainClass() {
        return profile.mainClass();
    }

    /**
     * {@inheritDoc}
     *
     * <p>The install profile's {@code serverMainClass} (else the client main class), never legacy
     * Forge's {@code ServerLaunchWrapper}.</p>
     *
     * @return the server main class
     * @since 2026.11
     */
    @Override
    public String getServerMainClass() {
        return profile.serverMainClass();
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code --tweakClass <tweaker>} per {@code serverTweakers}
     * @since 2026.11
     */
    @Override
    public String getServerArguments() {
        return String.join( " ", profile.serverGameArgs() );
    }

    /** Answers Cleanroom's startup confirmation prompts on an unattended server. */
    static final String SERVER_AUTO_CONFIRM = "-Dfml.queryResult=confirm";

    /**
     * {@inheritDoc}
     *
     * @return {@code -Dfml.queryResult=confirm}, which answers Cleanroom's "Fugue and Scalar are
     *         not installed" console prompt on a dedicated server
     * @since 2026.11
     */
    @Override
    public String getServerJvmArguments() {
        return SERVER_AUTO_CONFIRM;
    }

    /**
     * {@inheritDoc}
     *
     * @return a fresh copy of {@code version.json} without its embedded libraries
     * @since 2026.11
     */
    @Override
    public JsonObject getStandaloneVersionManifest() {
        return profile.standaloneManifest();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Extracts the libraries the installer embeds (the universal jar) into the pack's
     * libraries folder, verified against the SHA-1 the profile lists, and returns them as the
     * loader's classpath. Every other library comes from the standalone manifest.</p>
     *
     * @param mode     client or server
     * @param progress progress sink, or {@code null}
     *
     * @return the loader classpath
     *
     * @throws ModpackException if an embedded library is missing from the installer or fails to
     *                          extract or verify
     * @since 2026.11
     */
    @Override
    public String buildClasspath( GameMode mode, GameModPackProgressProvider progress ) throws ModpackException {
        updateLocalFile();
        CleanroomProfile current = profile;
        Set< String > entries = entryNames();
        File installer = SynchronizedFileManager.getSynchronizedFile( getFullLocalFilePath() );
        List< CleanroomProfile.EmbeddedLibrary > plan = current.planEmbedded(
                entries::contains, entry -> "jar:" + installer.toURI() + "!/" + entry );
        if ( progress != null ) {
            progress.submitProgress( LocalizationManager.get( "forgeLoader.gotAssetList" ), 20 );
        }

        String libsFolder = SystemUtilities.buildFilePath( parentModPack.getPackRootFolder(),
                                                            ModPackConstants.MODPACK_FORGE_LIBS_LOCAL_FOLDER );
        List< GameAsset > assets = new ArrayList<>();
        for ( CleanroomProfile.EmbeddedLibrary lib : plan ) {
            String local = lib.localPath().replace( "/", File.separator );
            GameAsset asset = lib.sha1() != null
                              ? new GameAsset( lib.url(), local, lib.sha1(), ManagedGameFileHashType.SHA1, true, true )
                              : new GameAsset( lib.url(), local, true, true );
            asset.setLocalPathPrefix( libsFolder );
            assets.add( asset );
        }

        List< Future< ? > > futures = new ArrayList<>();
        for ( GameAsset asset : assets ) {
            futures.add( DownloadExecutor.submit( ( Callable< Void > ) () -> {
                asset.updateLocalFile( mode, prepareContext() );
                if ( progress != null ) {
                    progress.submitProgress( LocalizationManager.format( "forgeLoader.verifiedAsset",
                                                                         asset.getFileName() ),
                                             60.0 / Math.max( 1, assets.size() ) );
                }
                return null;
            } ) );
        }
        try {
            DownloadExecutor.awaitAll( futures, 30 * 60 * 1000L );
        }
        catch ( TimeoutException e ) {
            throw new ModpackException( LocalizationManager.get( "forge.error.libsTimedOut" ) );
        }
        catch ( InterruptedException e ) {
            Thread.currentThread().interrupt();
            throw new ModpackException( LocalizationManager.get( "forge.error.libsInterrupted" ), e );
        }
        catch ( ExecutionException e ) {
            Throwable cause = e.getCause();
            if ( cause instanceof ModpackException modpackException ) {
                throw modpackException;
            }
            throw new ModpackException( LocalizationManager.format( "forge.error.libDownloadFailed",
                    String.valueOf( cause == null ? e.getMessage() : cause.getMessage() ) ), e );
        }

        List< String > classpath = new ArrayList<>();
        for ( GameAsset asset : assets ) {
            classpath.add( asset.getFullLocalFilePath() );
            if ( progress != null ) {
                progress.submitProgress( LocalizationManager.format( "forgeLoader.addedToClasspath",
                                                                     asset.getLocalFilePath() ),
                                         20.0 / Math.max( 1, assets.size() ) );
            }
        }
        return String.join( File.pathSeparator, classpath );
    }

    /**
     * Lists every entry name in the installer JAR.
     *
     * @return the entry names
     *
     * @throws ModpackException if the installer cannot be read
     */
    private Set< String > entryNames() throws ModpackException {
        Set< String > names = new HashSet<>();
        try ( JarFile installer = new JarFile( getFullLocalFilePath() ) ) {
            Enumeration< JarEntry > e = installer.entries();
            while ( e.hasMoreElements() ) {
                names.add( e.nextElement().getName() );
            }
        }
        catch ( IOException e ) {
            throw new ModpackException( LocalizationManager.UNABLE_ACCESS_FORGE_JAR_TEXT, e );
        }
        return names;
    }
}
