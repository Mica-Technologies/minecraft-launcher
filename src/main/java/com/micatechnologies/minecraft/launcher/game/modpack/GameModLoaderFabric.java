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
import com.micatechnologies.minecraft.launcher.consts.LocalPathConstants;
import com.micatechnologies.minecraft.launcher.consts.ModPackConstants;
import com.micatechnologies.minecraft.launcher.exceptions.ModpackException;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.game.modpack.manifests.ManifestRuleUtilities;
import com.micatechnologies.minecraft.launcher.utilities.JSONUtilities;
import com.micatechnologies.minecraft.launcher.utilities.JsonHelper;
import com.micatechnologies.minecraft.launcher.utilities.SystemUtilities;
import com.micatechnologies.minecraft.launcher.utilities.objects.GameMode;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Fabric implementation of {@link GameModLoader}.
 *
 * <h3>Why Fabric is structurally simpler than Forge / NeoForge</h3>
 *
 * <p>Fabric is a runtime loader — there's no install-time JAR
 * patching, no install_profile.json processors, no built artifact that
 * has to be assembled before launch. The launcher just needs to
 * download the libraries Fabric lists (fabric-loader, intermediary, a
 * few support jars) and the standard Minecraft client; at launch,
 * Fabric's {@code KnotClient} entry point patches the game in-memory.
 * </p>
 *
 * <h3>Manifest convention</h3>
 *
 * <p>The pack manifest's {@code packModLoaderURL} should point at the
 * Fabric meta service profile JSON for the desired loader + MC
 * version, e.g.:</p>
 * <pre>
 * https://meta.fabricmc.net/v2/versions/loader/1.21.5/0.16.10/profile/json
 * </pre>
 *
 * <p>{@code packModLoaderHash} may be omitted; the Fabric meta service
 * doesn't publish stable SHA-1s for these dynamically-served profile
 * JSONs. Verification falls back to "file exists" rather than hash
 * match, which is the same fallback path used by other
 * {@link ManagedGameFile} consumers that pass a null hash. The profile
 * JSON itself is small (low single-digit KB) so re-downloading is
 * cheap when verification fails.</p>
 *
 * @since 2026.5
 */
class GameModLoaderFabric extends ManagedGameFile implements GameModLoader
{
    /** Default Maven repository for fabric-* artifacts when a library
     *  entry in the profile JSON doesn't provide its own URL. */
    private static final String DEFAULT_FABRIC_MAVEN = "https://maven.fabricmc.net/";

    /** Mojang's libraries Maven for vanilla {@code net.minecraft:*}
     *  entries that occasionally appear in Fabric profiles. */
    private static final String MOJANG_LIBRARIES_MAVEN = "https://libraries.minecraft.net/";

    /** Maven Central for everything else (asm, jansi, etc.). */
    private static final String MAVEN_CENTRAL = "https://repo1.maven.org/maven2/";

    /**
     * The modpack that owns this loader. Supplies the pack root folder
     * used to resolve the on-disk locations of the profile JSON and the
     * downloaded Fabric libraries.
     *
     * @since 2026.5
     */
    private final GameModPack parentModPack;

    /**
     * Fabric loader version (e.g. {@code "0.16.10"}), derived from the
     * profile JSON {@code id} field at construction time and cached so
     * {@link #getLoaderVersion()} never re-reads disk.
     *
     * @since 2026.5
     */
    private final String fabricLoaderVersion;

    /**
     * The Minecraft version this Fabric profile inherits from (the
     * profile JSON {@code inheritsFrom} field), cached at construction
     * time.
     *
     * @since 2026.5
     */
    private final String minecraftVersion;

    /**
     * The client launch entry point named by the profile JSON
     * {@code mainClass} field (Fabric's {@code KnotClient}), cached at
     * construction time.
     *
     * @since 2026.5
     */
    private final String minecraftMainClass;

    /**
     * The flattened, space-separated game argument string derived from
     * the profile JSON {@code arguments.game} array (usually empty for
     * Fabric), cached at construction time.
     *
     * @since 2026.5
     */
    private final String minecraftArguments;

    /**
     * The flattened, space-separated JVM argument string derived from
     * the profile JSON {@code arguments.jvm} array (usually empty for
     * Fabric), cached at construction time.
     *
     * @since 2026.5
     */
    private final String jvmArguments;

    /**
     * The raw {@code libraries} array from the profile JSON, cached at
     * construction time so {@link #buildClasspath} can iterate it
     * without re-reading and re-parsing the profile file from disk.
     *
     * @since 2026.5
     */
    private final JsonArray librariesArray;

    /**
     * Construct a Fabric loader handle for a pack. Downloads (and, when
     * a hash is supplied, verifies) the Fabric profile JSON, then parses
     * and caches the fields the launcher needs at launch time
     * (main class, inherited Minecraft version, derived loader version,
     * flattened argument strings, and the raw libraries array).
     *
     * @param remoteURL     URL of the Fabric profile JSON served by the
     *                      Fabric meta service.
     * @param sha1Hash      optional SHA-1 of the profile JSON; may be
     *                      {@code null} or blank, in which case
     *                      verification falls back to a file-exists
     *                      check (the Fabric meta service does not
     *                      publish stable hashes for these JSONs).
     * @param parentModPack the pack that owns this loader; provides the
     *                      pack root folder for local file resolution.
     *
     * @throws ModpackException if the profile JSON cannot be downloaded,
     *                          read, or parsed, or if it is missing a
     *                          required field such as the
     *                          {@code libraries} array.
     *
     * @since 2026.5
     */
    GameModLoaderFabric( String remoteURL, String sha1Hash, GameModPack parentModPack )
            throws ModpackException
    {
        super( remoteURL,
                SystemUtilities.buildFilePath( parentModPack.getPackRootFolder(),
                                                ModPackConstants.MODPACK_FABRIC_PROFILE_LOCAL_PATH ),
                sha1Hash != null && !sha1Hash.isBlank() ? sha1Hash : null,
                ManagedGameFileHashType.SHA1 );
        this.parentModPack = parentModPack;

        updateLocalFile();

        JsonObject profile = readProfileJson();
        // Required fields. Fabric profile JSONs always carry these.
        this.minecraftMainClass = JsonHelper.getRequiredString( profile, "mainClass" );
        this.minecraftVersion = JsonHelper.getRequiredString( profile, "inheritsFrom" );

        // Derive loader version from id ("fabric-loader-0.16.10-1.21.5" → "0.16.10").
        String id = JsonHelper.getString( profile, "id", "" );
        this.fabricLoaderVersion = deriveLoaderVersion( id, minecraftVersion );

        // Argument arrays are optional and usually empty for Fabric —
        // KnotClient handles its own launch-target negotiation rather
        // than relying on tweaker-style game args.
        this.minecraftArguments = flattenArgs( profile, "game" );
        this.jvmArguments       = flattenArgs( profile, "jvm" );

        // Library list is required for classpath build. Cache the
        // raw JsonArray so buildClasspath doesn't re-read the file.
        if ( !profile.has( "libraries" ) || !profile.get( "libraries" ).isJsonArray() ) {
            throw new ModpackException( "Fabric profile JSON missing required \"libraries\" array" );
        }
        this.librariesArray = profile.getAsJsonArray( "libraries" );
    }

    /**
     * {@inheritDoc}
     *
     * <p>Always returns the literal {@code "Fabric"} for this loader
     * implementation.</p>
     *
     * @return the loader display name, {@code "Fabric"}.
     *
     * @since 2026.5
     */
    @Override
    public String getName() { return "Fabric"; }

    /**
     * {@inheritDoc}
     *
     * <p>Returns the cached Minecraft version this profile inherits
     * from (the profile JSON {@code inheritsFrom} field).</p>
     *
     * @return the inherited Minecraft version.
     *
     * @since 2026.5
     */
    @Override
    public String getMinecraftVersion() { return minecraftVersion; }

    /**
     * {@inheritDoc}
     *
     * <p>Returns the Fabric loader version derived from the profile
     * JSON {@code id} field at construction time.</p>
     *
     * @return the Fabric loader version (e.g. {@code "0.16.10"}).
     *
     * @since 2026.5
     */
    @Override
    public String getLoaderVersion() { return fabricLoaderVersion; }

    /**
     * {@inheritDoc}
     *
     * <p>Returns the flattened game argument string; usually empty for
     * Fabric, which negotiates its own launch target at runtime.</p>
     *
     * @return the space-separated game arguments, possibly empty.
     *
     * @since 2026.5
     */
    @Override
    public String getMinecraftArguments() { return minecraftArguments; }

    /**
     * {@inheritDoc}
     *
     * <p>Returns the flattened JVM argument string; usually empty for
     * Fabric.</p>
     *
     * @return the space-separated JVM arguments, possibly empty.
     *
     * @since 2026.5
     */
    @Override
    public String getJvmArguments() { return jvmArguments; }

    /**
     * {@inheritDoc}
     *
     * <p>Returns the client launch entry point named by the profile
     * JSON {@code mainClass} field (Fabric's {@code KnotClient}).</p>
     *
     * @return the client main class name.
     *
     * @since 2026.5
     */
    @Override
    public String getMinecraftMainClass() { return minecraftMainClass; }

    /**
     * Fabric's server side has its own bootstrap class —
     * {@code KnotServer} via the {@code FabricServerLauncher} shim —
     * distinct from the client's {@code KnotClient}. The Fabric meta
     * service's server profile JSON would name this explicitly, but
     * we only fetch the client profile; hardcoding the canonical
     * server entry is safe across Fabric loader 0.13+ (the class
     * hasn't moved in years).
     *
     * @return the canonical Fabric server launcher class name.
     *
     * @since 2026.5
     */
    @Override
    public String getServerMainClass() {
        return "net.fabricmc.loader.impl.launch.server.FabricServerLauncher";
    }

    /**
     * Build the Fabric portion of the classpath. Downloads each library
     * in {@link #librariesArray} via {@link GameAsset} (so hash
     * verification + retry logic comes for free), collects the local
     * paths into a {@code File.pathSeparator}-joined string. The
     * launcher concatenates this with the vanilla MC classpath at
     * launch time.
     *
     * @param gameAppMode      the game mode (client / server) driving
     *                         per-asset download behavior.
     * @param progressProvider sink for download progress updates; may be
     *                         {@code null}.
     *
     * @return a {@code File.pathSeparator}-joined string of local
     *         library paths in profile-declaration order.
     *
     * @throws ModpackException if any required library fails to download
     *                          or verify.
     *
     * @since 2026.5
     */
    @Override
    public String buildClasspath( GameMode gameAppMode, GameModPackProgressProvider progressProvider )
            throws ModpackException
    {
        String libsFolder = SystemUtilities.buildFilePath( parentModPack.getPackRootFolder(),
                                                            ModPackConstants.MODPACK_FORGE_LIBS_LOCAL_FOLDER );

        LinkedHashSet< String > classpathEntries = new LinkedHashSet<>();

        for ( JsonElement libEl : librariesArray ) {
            if ( !libEl.isJsonObject() ) continue;
            JsonObject lib = libEl.getAsJsonObject();
            String name = JsonHelper.getString( lib, "name", null );
            if ( name == null || name.isBlank() ) continue;

            // Derive Maven path + filename from the coordinate.
            String mavenPath = mavenCoordToPath( name );
            if ( mavenPath == null ) continue;

            // URL: explicit > Fabric default > group-aware fallback.
            String baseUrl = JsonHelper.getString( lib, "url", null );
            if ( baseUrl == null || baseUrl.isBlank() ) {
                baseUrl = defaultMavenForCoord( name );
            }
            if ( !baseUrl.endsWith( "/" ) ) baseUrl += "/";
            String remoteUrl = baseUrl + mavenPath;

            String sha1 = null;
            if ( lib.has( "sha1" ) ) sha1 = JsonHelper.getString( lib, "sha1", null );

            String localPath = libsFolder + File.separator + mavenPath.replace( "/", File.separator );

            GameAsset asset = sha1 != null
                    ? new GameAsset( remoteUrl, localPath, sha1, ManagedGameFileHashType.SHA1, true, true )
                    : new GameAsset( remoteUrl, localPath, true, true );
            asset.updateLocalFile( gameAppMode );

            classpathEntries.add( localPath );
        }

        // Build the classpath string in insertion order — LinkedHashSet
        // preserves it. Fabric doesn't have a Forge-style "patched
        // client jar"; the launcher's vanilla MC classpath supplies
        // minecraft.jar separately.
        StringBuilder cp = new StringBuilder();
        for ( String entry : classpathEntries ) {
            if ( cp.length() > 0 ) cp.append( File.pathSeparator );
            cp.append( entry );
        }
        Logger.logStd( "Fabric: " + classpathEntries.size() + " library/libraries on classpath." );
        return cp.toString();
    }

    // ====================================================================
    // Helpers
    // ====================================================================

    /**
     * Read and parse the cached Fabric profile JSON file from disk.
     *
     * @return the parsed profile JSON as a {@link JsonObject}.
     *
     * @throws ModpackException if the file cannot be read, does not parse
     *                          to a JSON object, or is malformed JSON.
     *
     * @since 2026.5
     */
    private JsonObject readProfileJson() throws ModpackException
    {
        File profileFile = new File( getFullLocalFilePath() );
        try {
            String json = Files.readString( profileFile.toPath() );
            JsonElement el = JSONUtilities.getGson().fromJson( json, JsonElement.class );
            if ( el == null || !el.isJsonObject() ) {
                throw new ModpackException( "Fabric profile JSON did not parse to an object at "
                                                    + profileFile );
            }
            return el.getAsJsonObject();
        }
        catch ( IOException e ) {
            throw new ModpackException( "Unable to read Fabric profile JSON: " + profileFile, e );
        }
        catch ( com.google.gson.JsonSyntaxException e ) {
            throw new ModpackException( "Fabric profile JSON is malformed: " + profileFile, e );
        }
    }

    /**
     * Derive the Fabric loader version from a profile {@code id}. For
     * example {@code "fabric-loader-0.16.10-1.21.5"} with MC version
     * {@code "1.21.5"} yields {@code "0.16.10"}. Strips the
     * {@code fabric-loader-} prefix and the trailing MC-version suffix
     * when present, and falls back to the raw id when the expected shape
     * doesn't match.
     *
     * @param id        the profile JSON {@code id} field; may be
     *                  {@code null} or blank.
     * @param mcVersion the inherited Minecraft version used to strip the
     *                  trailing suffix; may be {@code null} or blank.
     *
     * @return the derived loader version, the trimmed id, or an empty
     *         string when {@code id} is {@code null}/blank.
     *
     * @since 2026.5
     */
    private static String deriveLoaderVersion( String id, String mcVersion )
    {
        if ( id == null || id.isBlank() ) return "";
        String working = id;
        if ( working.startsWith( "fabric-loader-" ) ) {
            working = working.substring( "fabric-loader-".length() );
        }
        if ( mcVersion != null && !mcVersion.isBlank()
                && working.endsWith( "-" + mcVersion ) ) {
            working = working.substring( 0, working.length() - mcVersion.length() - 1 );
        }
        return working;
    }

    /**
     * Flatten an optional {@code arguments.<kind>} array from a profile
     * JSON into a single space-separated string. Returns an empty string
     * when the {@code arguments} object, the requested {@code kind}
     * section, or its array form is absent. Mirrors the same shape
     * {@link ManifestRuleUtilities#flattenArguments} produces for
     * vanilla / Forge manifests.
     *
     * @param profile the profile JSON object to read from.
     * @param kind    the argument section name, e.g. {@code "game"} or
     *                {@code "jvm"}.
     *
     * @return the flattened, space-separated argument string, possibly
     *         empty.
     *
     * @since 2026.5
     */
    private static String flattenArgs( JsonObject profile, String kind )
    {
        if ( !profile.has( "arguments" ) ) return "";
        JsonObject argsObj = profile.getAsJsonObject( "arguments" );
        if ( !argsObj.has( kind ) || !argsObj.get( kind ).isJsonArray() ) return "";
        return ManifestRuleUtilities.flattenArguments( argsObj.getAsJsonArray( kind ) );
    }

    /**
     * Convert a Maven coordinate
     * ({@code "group:artifact:version"}) into its relative repository
     * path. Delegates to the shared {@link MavenArtifactPath} utility so
     * malformed coordinates return {@code null} (Fabric's manifest-skip
     * semantics), while path-traversal attempts still throw.
     *
     * @param coord the Maven coordinate string.
     *
     * @return the relative repository path, or {@code null} when the
     *         coordinate is malformed.
     *
     * @since 2026.5
     */
    private static String mavenCoordToPath( String coord )
    {
        return MavenArtifactPath.toRelativePathOrNull( coord );
    }

    /**
     * Pick a sensible default Maven base URL for a coordinate whose
     * profile-JSON entry didn't supply one. {@code net.fabricmc:*}
     * artifacts go to Fabric's own Maven; {@code net.minecraft:*} fall
     * back to Mojang's libraries server; everything else lands on Maven
     * Central.
     *
     * @param coord the Maven coordinate string.
     *
     * @return the default Maven base URL for the coordinate's group.
     *
     * @since 2026.5
     */
    private static String defaultMavenForCoord( String coord )
    {
        if ( coord.startsWith( "net.fabricmc:" ) ) return DEFAULT_FABRIC_MAVEN;
        if ( coord.startsWith( "net.minecraft:" ) ) return MOJANG_LIBRARIES_MAVEN;
        return MAVEN_CENTRAL;
    }

    /**
     * Defensive utility — materialize {@link #librariesArray} as a
     * contiguous {@code ArrayList} for any caller that wants concrete
     * generics rather than the raw {@link JsonArray}. Non-object
     * elements are skipped. Not currently used inside this class but
     * kept so a future iterator (e.g. a "verify only" pass) doesn't have
     * to re-iterate the raw {@code JsonArray}.
     *
     * @return a new list containing only the JSON-object library
     *         entries, in declaration order.
     *
     * @since 2026.5
     */
    @SuppressWarnings( "unused" )
    private List< JsonObject > librariesAsList()
    {
        ArrayList< JsonObject > out = new ArrayList<>( librariesArray.size() );
        for ( JsonElement el : librariesArray ) {
            if ( el.isJsonObject() ) out.add( el.getAsJsonObject() );
        }
        return out;
    }
}
