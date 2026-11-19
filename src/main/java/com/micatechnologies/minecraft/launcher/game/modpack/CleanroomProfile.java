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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.micatechnologies.minecraft.launcher.consts.ForgeConstants;
import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
import com.micatechnologies.minecraft.launcher.exceptions.ModpackException;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.game.modpack.manifests.ManifestRuleUtilities;
import com.micatechnologies.minecraft.launcher.utilities.JsonHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * What a Cleanroom installer JAR says about the game it installs, read from its two profiles, and
 * the launch decisions that follow from them. Pure: it takes the parsed JSON (and, for the
 * embedded libraries, predicates over the installer's entries), so every decision is tested
 * against the real 0.7.14-alpha profiles without an installer, a pack or the network.
 *
 * <p>Cleanroom is the Forge 1.12.2 fork on Java 25 and LWJGL 3. Its installer carries:</p>
 * <ul>
 *   <li>{@code version.json}: a <em>standalone</em> launcher profile (no {@code inheritsFrom})
 *       with its own {@code javaVersion} ({@code java-runtime-epsilon}, Java 25),
 *       {@code assetIndex}, client and server downloads, and every library including LWJGL 3,
 *       its natives, and LWJGLY (the LWJGL 2 API shim). The launch uses it <em>in place of</em>
 *       Mojang's 1.12.2 client.json ({@link #standaloneManifest()}), which keeps vanilla LWJGL 2
 *       and every other vanilla library off the classpath.</li>
 *   <li>{@code install_profile.json}: Forge's spec-1 shape with zero processors, plus the
 *       Minecraft version, the server main class and tweakers, and JVM arguments.</li>
 * </ul>
 *
 * <p>The one library the installer embeds (the universal jar: {@code downloads.artifact.url}
 * blank, the file under the installer's {@code maven/}) is the loader's own classpath; every
 * other library is in the standalone manifest and is fetched like a vanilla library.</p>
 *
 * @since 2026.11
 */
final class CleanroomProfile
{
    /** The Minecraft version every Cleanroom release targets (the installer's own constant). */
    static final String MINECRAFT_VERSION = "1.12.2";

    /** The server tweaker the installer falls back to when {@code serverTweakers} is empty. */
    static final String DEFAULT_SERVER_TWEAKER = "net.minecraftforge.fml.common.launcher.FMLServerTweaker";

    /** The JVM flag LWJGL 3 needs on macOS (the installer adds it to the client profile there). */
    static final String START_ON_FIRST_THREAD = "-XstartOnFirstThread";

    /** The installer's install-profile entry name. */
    static final String INSTALL_PROFILE_ENTRY = "install_profile.json";

    /** {@code id} of {@code version.json} (e.g. {@code Cleanroom-0.7.14-alpha}). */
    private final String id;

    /** {@code mainClass} of {@code version.json}. */
    private final String mainClass;

    /** The Minecraft version, never the standalone id. */
    private final String minecraftVersion;

    /** {@code minecraftArguments} of {@code version.json}. */
    private final String minecraftArguments;

    /** {@code version.json} without its embedded libraries (a private deep copy). */
    private final JsonObject standaloneManifest;

    /** The libraries the installer embeds (deep copies). */
    private final List< JsonObject > embeddedLibraries;

    /** {@code install_profile.json}, or {@code null} when the installer has no readable one. */
    private final JsonObject installProfile;

    /**
     * Builds the profile from the two parsed files.
     *
     * @param versionJson    the installer's {@code version.json}
     * @param installProfile the installer's {@code install_profile.json}, or {@code null} when it
     *                       has none (only the server facts it carries are lost)
     *
     * @throws ModpackException if {@code version.json} lacks {@code id} or {@code mainClass}
     */
    private CleanroomProfile( JsonObject versionJson, JsonObject installProfile ) throws ModpackException
    {
        for ( String key : new String[]{ ForgeConstants.FORGE_VERSION_MANIFEST_ID_KEY,
                                         ForgeConstants.FORGE_VERSION_MANIFEST_MAIN_CLASS_KEY } ) {
            String value = JsonHelper.getString( versionJson, key, null );
            if ( value == null || value.isBlank() ) {
                throw new ModpackException( LocalizationManager.format( "forge.error.manifestMissingField", key ) );
            }
        }
        this.id = versionJson.get( ForgeConstants.FORGE_VERSION_MANIFEST_ID_KEY ).getAsString();
        this.mainClass = versionJson.get( ForgeConstants.FORGE_VERSION_MANIFEST_MAIN_CLASS_KEY ).getAsString();
        this.minecraftArguments = JsonHelper.getString( versionJson,
                ForgeConstants.FORGE_VERSION_MANIFEST_MINECRAFT_ARGS_KEY, "" );
        this.installProfile = installProfile == null ? null : installProfile.deepCopy();
        this.minecraftVersion = minecraftVersionOf( installProfile, versionJson );

        // Split the libraries: the embedded ones are the loader's own classpath, the rest form the
        // standalone manifest the Minecraft stage resolves (a library with a blank URL could not be
        // downloaded there).
        JsonObject standalone = versionJson.deepCopy();
        JsonArray downloadable = new JsonArray();
        List< JsonObject > embedded = new ArrayList<>();
        JsonArray libraries = versionJson.has( "libraries" ) && versionJson.get( "libraries" ).isJsonArray()
                              ? versionJson.getAsJsonArray( "libraries" ) : new JsonArray();
        for ( JsonElement lib : libraries ) {
            if ( lib.isJsonObject() && isEmbeddedLibrary( lib.getAsJsonObject() ) ) {
                embedded.add( lib.getAsJsonObject().deepCopy() );
            }
            else {
                downloadable.add( lib.deepCopy() );
            }
        }
        standalone.add( "libraries", downloadable );
        this.standaloneManifest = standalone;
        this.embeddedLibraries = Collections.unmodifiableList( embedded );
    }

    /**
     * Builds the profile from the two parsed files.
     *
     * @param versionJson    the installer's {@code version.json}
     * @param installProfile the installer's {@code install_profile.json}, or {@code null}
     *
     * @return the profile
     *
     * @throws ModpackException if {@code version.json} is missing or lacks {@code id} or
     *                          {@code mainClass}
     *
     * @since 2026.11
     */
    static CleanroomProfile of( JsonObject versionJson, JsonObject installProfile ) throws ModpackException
    {
        if ( versionJson == null ) {
            throw new ModpackException( LocalizationManager.UNABLE_FIND_FORGE_VERSION_FILE_TEXT );
        }
        return new CleanroomProfile( versionJson, installProfile );
    }

    /**
     * Builds the profile from the two files' text, as read out of the installer. An unreadable
     * install profile only loses the server facts it carries (logged, then the defaults apply);
     * an unreadable {@code version.json} fails.
     *
     * @param versionJsonText    the text of {@code version.json}, or {@code null} when absent
     * @param installProfileText the text of {@code install_profile.json}, or {@code null} when
     *                           absent
     *
     * @return the profile
     *
     * @throws ModpackException if {@code version.json} is absent or unreadable, or lacks a
     *                          required field
     *
     * @since 2026.11
     */
    static CleanroomProfile parse( String versionJsonText, String installProfileText ) throws ModpackException
    {
        if ( versionJsonText == null ) {
            throw new ModpackException( LocalizationManager.UNABLE_FIND_FORGE_VERSION_FILE_TEXT );
        }
        JsonObject versionJson;
        try {
            versionJson = JsonParser.parseString( versionJsonText ).getAsJsonObject();
        }
        catch ( RuntimeException e ) {
            throw new ModpackException( LocalizationManager.UNABLE_OPEN_FORGE_VERSION_MANIFEST_PARSING_TEXT, e );
        }
        JsonObject profile = null;
        if ( installProfileText != null ) {
            try {
                profile = JsonParser.parseString( installProfileText ).getAsJsonObject();
            }
            catch ( RuntimeException e ) {
                Logger.logWarningSilent( LocalizationManager.format( "log.cleanroomLoader.profileUnreadable",
                                                                     String.valueOf( e.getMessage() ) ) );
            }
        }
        return of( versionJson, profile );
    }

    /**
     * The Minecraft version of a Cleanroom install: the install profile's {@code minecraft}, else
     * {@code version.json}'s {@code inheritsFrom} (no current release has one), else
     * {@link #MINECRAFT_VERSION}. Never the standalone id ({@code Cleanroom-0.7.14-alpha}), which
     * is not a Minecraft version: the asset, runtime and UI code all need {@code 1.12.2}.
     *
     * @param installProfile the install profile, or {@code null}
     * @param versionJson    the version profile
     *
     * @return the Minecraft version
     *
     * @since 2026.11
     */
    static String minecraftVersionOf( JsonObject installProfile, JsonObject versionJson )
    {
        String fromProfile = JsonHelper.getString( installProfile, "minecraft", null );
        if ( fromProfile != null && !fromProfile.isBlank() ) {
            return fromProfile.trim();
        }
        String inherits = JsonHelper.getString( versionJson,
                ForgeConstants.FORGE_VERSION_MANIFEST_INHERITS_FROM_KEY, null );
        if ( inherits != null && !inherits.isBlank() ) {
            return inherits.trim();
        }
        return MINECRAFT_VERSION;
    }

    /**
     * Returns whether a library is one the installer carries itself: a
     * {@code downloads.artifact} whose {@code url} is absent or blank (the universal jar, under
     * the installer's {@code maven/}).
     *
     * @param library a {@code version.json} library entry
     *
     * @return {@code true} for an embedded library
     *
     * @since 2026.11
     */
    static boolean isEmbeddedLibrary( JsonObject library )
    {
        JsonObject downloads = JsonHelper.getJsonObject( library, "downloads" );
        JsonObject artifact = JsonHelper.getJsonObject( downloads, "artifact" );
        if ( artifact == null ) {
            return false;
        }
        String url = JsonHelper.getString( artifact, "url", null );
        return url == null || url.isBlank();
    }

    /**
     * @return {@code id} of {@code version.json} (e.g. {@code Cleanroom-0.7.14-alpha}), which is
     *         also the version folder the installer writes
     *
     * @since 2026.11
     */
    String id()
    {
        return id;
    }

    /**
     * @return the client main class ({@code top.outlands.foundation.boot.Foundation})
     *
     * @since 2026.11
     */
    String mainClass()
    {
        return mainClass;
    }

    /**
     * @return the Minecraft version ({@code 1.12.2}), see {@link #minecraftVersionOf}
     *
     * @since 2026.11
     */
    String minecraftVersion()
    {
        return minecraftVersion;
    }

    /**
     * @return {@code minecraftArguments} of {@code version.json}: the whole client game argument
     *         template, with its one {@code --tweakClass}
     *
     * @since 2026.11
     */
    String minecraftArguments()
    {
        return minecraftArguments;
    }

    /**
     * Returns {@code version.json} without the libraries the installer embeds: the manifest the
     * launch uses in place of Mojang's client.json (libraries and natives, Java runtime, asset
     * index, client and server jars). Returns a fresh copy each call, so a caller may keep it.
     *
     * @return the standalone manifest
     *
     * @since 2026.11
     */
    JsonObject standaloneManifest()
    {
        return standaloneManifest.deepCopy();
    }

    /**
     * @return the libraries the installer embeds, in profile order (the universal jar for
     *         0.7.14-alpha)
     *
     * @since 2026.11
     */
    List< JsonObject > embeddedLibraries()
    {
        return embeddedLibraries;
    }

    /**
     * @return whether the installer had a readable {@code install_profile.json}
     *
     * @since 2026.11
     */
    boolean hasInstallProfile()
    {
        return installProfile != null;
    }

    /**
     * @return {@code jvmArgs} of the install profile (JVM arguments for both sides; empty for
     *         0.7.14-alpha)
     *
     * @since 2026.11
     */
    List< String > jvmArgs()
    {
        return strings( installProfile, "jvmArgs" );
    }

    /**
     * @return the server main class: the install profile's {@code serverMainClass}, else the
     *         client main class (the installer's server mode does the same)
     *
     * @since 2026.11
     */
    String serverMainClass()
    {
        String value = JsonHelper.getString( installProfile, "serverMainClass", null );
        return value == null || value.isBlank() ? mainClass : value.trim();
    }

    /**
     * @return the install profile's {@code serverTweakers}, or {@link #DEFAULT_SERVER_TWEAKER}
     *         when absent or empty (as the installer does)
     *
     * @since 2026.11
     */
    List< String > serverTweakers()
    {
        List< String > list = strings( installProfile, "serverTweakers" );
        return list.isEmpty() ? List.of( DEFAULT_SERVER_TWEAKER ) : list;
    }

    /**
     * @return a dedicated server's game arguments: {@code --tweakClass <tweaker>} per
     *         {@linkplain #serverTweakers() server tweaker}, as the installer's server mode
     *         writes them
     *
     * @since 2026.11
     */
    List< String > serverGameArgs()
    {
        List< String > out = new ArrayList<>();
        for ( String tweaker : serverTweakers() ) {
            out.add( "--tweakClass" );
            out.add( tweaker );
        }
        return out;
    }

    /**
     * Returns the client JVM arguments as the installer writes them into a launcher profile: the
     * profile's {@code jvmArgs}, plus {@link #START_ON_FIRST_THREAD} on macOS (LWJGL 3 must own
     * the main thread there, and the standalone {@code version.json} has no
     * {@code arguments.jvm} to say so). Never adds the flag twice.
     *
     * @param os the host OS family
     *
     * @return the client JVM arguments
     *
     * @since 2026.11
     */
    List< String > clientJvmArgs( ManifestRuleUtilities.HostOs os )
    {
        List< String > args = new ArrayList<>( jvmArgs() );
        if ( os == ManifestRuleUtilities.HostOs.MACOS && !args.contains( START_ON_FIRST_THREAD ) ) {
            args.add( START_ON_FIRST_THREAD );
        }
        return args;
    }

    /**
     * One installer-embedded library, planned for download out of the installer.
     *
     * @param url       the {@code jar:} URL of its entry in the installer
     * @param localPath its Maven path under the pack's libraries folder (forward slashes)
     * @param sha1      the SHA-1 the profile lists for it, or {@code null} when it lists none
     *
     * @since 2026.11
     */
    record EmbeddedLibrary( String url, String localPath, String sha1 ) {}

    /**
     * Plans the {@linkplain #embeddedLibraries() embedded libraries}: each must really be in the
     * installer under {@code maven/<path>}. One that is not would otherwise be fetched from a
     * made-up repository URL, so the installer is refused instead.
     *
     * @param hasEntry   whether the installer has an entry of the given name
     * @param urlOfEntry the {@code jar:} URL of the installer entry of the given name
     *
     * @return the planned libraries, in profile order
     *
     * @throws ModpackException if a listed library is not in the installer, or its path cannot
     *                          be read
     *
     * @since 2026.11
     */
    List< EmbeddedLibrary > planEmbedded( Predicate< String > hasEntry, Function< String, String > urlOfEntry )
            throws ModpackException
    {
        List< EmbeddedLibrary > out = new ArrayList<>();
        for ( JsonObject lib : embeddedLibraries ) {
            JsonObject artifact = JsonHelper.getJsonObject( JsonHelper.getJsonObject( lib, "downloads" ), "artifact" );
            String name = JsonHelper.getString( lib, "name", "" );
            String path = JsonHelper.getString( artifact, "path", null );
            if ( path == null || path.isBlank() ) {
                path = MavenArtifactPath.toRelativePathStrict( name );
            }
            String entry = "maven/" + path;
            if ( path.contains( ".." ) || !hasEntry.test( entry ) ) {
                String fileName = path.substring( path.lastIndexOf( '/' ) + 1 );
                throw new ModpackException( LocalizationManager.format(
                        "launch.error.cleanroomEmbeddedLibraryMissing", fileName ) );
            }
            String sha1 = JsonHelper.getString( artifact, "sha1", null );
            out.add( new EmbeddedLibrary( urlOfEntry.apply( entry ), path,
                                          sha1 == null || sha1.isBlank() ? null : sha1 ) );
        }
        return out;
    }

    /**
     * The command line that installs Cleanroom's client into a Minecraft Launcher data folder.
     * The installer is mode-based ({@code client | server | mmc}) and has no
     * {@code --installClient}: {@code client --dir <dotMc> --no-gui --yes}, plus
     * {@code --no-profile} (the export writes the pack's own launcher profile) and
     * {@code --java <java> --no-provision-java} (never download a second Java).
     *
     * @param java      the Java executable that runs the installer
     * @param installer the installer JAR
     * @param dotMc     the Minecraft Launcher data folder
     *
     * @return the command line
     *
     * @since 2026.11
     */
    static List< String > installerCommand( String java, String installer, String dotMc )
    {
        return List.of( java, "-jar", installer,
                        "client", "--dir", dotMc, "--no-gui", "--yes", "--no-profile",
                        "--java", java, "--no-provision-java" );
    }

    /**
     * Reads a string array from {@code obj}, skipping blank and non-string entries.
     *
     * @param obj the object to read from, or {@code null}
     * @param key the array key
     *
     * @return the strings, empty when the key is absent
     */
    private static List< String > strings( JsonObject obj, String key )
    {
        List< String > out = new ArrayList<>();
        if ( obj == null || !obj.has( key ) || !obj.get( key ).isJsonArray() ) {
            return out;
        }
        for ( JsonElement e : obj.getAsJsonArray( key ) ) {
            if ( e != null && e.isJsonPrimitive() ) {
                String s = e.getAsString();
                if ( !s.isBlank() ) {
                    out.add( s.trim() );
                }
            }
        }
        return out;
    }
}
