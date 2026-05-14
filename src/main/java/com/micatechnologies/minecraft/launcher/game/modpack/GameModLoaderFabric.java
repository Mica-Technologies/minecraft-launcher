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

    private final GameModPack parentModPack;

    /** Cached fields parsed once from the Fabric profile JSON at
     *  construction time so the getters never re-read disk. */
    private final String fabricLoaderVersion;
    private final String minecraftVersion;
    private final String minecraftMainClass;
    private final String minecraftArguments;
    private final String jvmArguments;
    private final JsonArray librariesArray;

    /**
     * @param remoteURL     URL of the Fabric profile JSON (meta service).
     * @param sha1Hash      optional SHA-1 of the profile JSON; may be null.
     * @param parentModPack owning pack.
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

    @Override
    public String getName() { return "Fabric"; }

    @Override
    public String getMinecraftVersion() { return minecraftVersion; }

    @Override
    public String getLoaderVersion() { return fabricLoaderVersion; }

    @Override
    public String getMinecraftArguments() { return minecraftArguments; }

    @Override
    public String getJvmArguments() { return jvmArguments; }

    @Override
    public String getMinecraftMainClass() { return minecraftMainClass; }

    /**
     * Build the Fabric portion of the classpath. Downloads each library
     * in {@link #librariesArray} via {@link GameAsset} (so hash
     * verification + retry logic comes for free), collects the local
     * paths into a {@code File.pathSeparator}-joined string. The
     * launcher concatenates this with the vanilla MC classpath at
     * launch time.
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

    /** Read + parse the cached profile JSON file from disk. */
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

    /** {@code "fabric-loader-0.16.10-1.21.5"} + {@code "1.21.5"}
     *  → {@code "0.16.10"}. Falls back to the raw id when the prefix /
     *  MC-suffix shape doesn't match. */
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

    /** Flatten an optional {@code arguments.<kind>} array into a single
     *  space-separated string. Returns empty when the section is
     *  missing or empty. Mirrors the same shape
     *  {@code ManifestRuleUtilities.flattenArguments} produces for
     *  vanilla / Forge manifests. */
    private static String flattenArgs( JsonObject profile, String kind )
    {
        if ( !profile.has( "arguments" ) ) return "";
        JsonObject argsObj = profile.getAsJsonObject( "arguments" );
        if ( !argsObj.has( kind ) || !argsObj.get( kind ).isJsonArray() ) return "";
        return ManifestRuleUtilities.flattenArguments( argsObj.getAsJsonArray( kind ) );
    }

    /** Maven coord ("group:artifact:version") → relative repo path
     *  ("group-as-path/artifact/version/artifact-version.jar"). */
    private static String mavenCoordToPath( String coord )
    {
        String[] parts = coord.split( ":" );
        if ( parts.length < 3 ) return null;
        String group = parts[ 0 ].replace( '.', '/' );
        String artifact = parts[ 1 ];
        String version = parts[ 2 ];
        String classifier = parts.length >= 4 ? parts[ 3 ] : null;
        StringBuilder sb = new StringBuilder();
        sb.append( group ).append( '/' ).append( artifact ).append( '/' ).append( version )
          .append( '/' ).append( artifact ).append( '-' ).append( version );
        if ( classifier != null && !classifier.isBlank() ) sb.append( '-' ).append( classifier );
        sb.append( LocalPathConstants.JAR_FILE_EXTENSION );
        return sb.toString();
    }

    /** Pick a sensible default Maven base URL for a coord whose
     *  profile-JSON entry didn't include one. fabric-* go to Fabric's
     *  own maven; net.minecraft:* fall back to Mojang's libraries
     *  server; everything else lands on Maven Central. */
    private static String defaultMavenForCoord( String coord )
    {
        if ( coord.startsWith( "net.fabricmc:" ) ) return DEFAULT_FABRIC_MAVEN;
        if ( coord.startsWith( "net.minecraft:" ) ) return MOJANG_LIBRARIES_MAVEN;
        return MAVEN_CENTRAL;
    }

    /** Defensive utility — make {@code librariesArray} a contiguous
     *  ArrayList for the rare caller that wants concrete generics. Not
     *  currently used inside this class but kept so a future iterator
     *  (e.g. a "verify only" pass) doesn't have to re-iterate the raw
     *  JsonArray. */
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
