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
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.micatechnologies.minecraft.launcher.exceptions.ModpackException;
import com.micatechnologies.minecraft.launcher.game.modpack.manifests.GameLibraryManifest;
import com.micatechnologies.minecraft.launcher.game.modpack.manifests.ManifestRuleUtilities;
import com.micatechnologies.minecraft.launcher.game.modpack.manifests.ManifestRuleUtilities.Host;
import com.micatechnologies.minecraft.launcher.game.modpack.manifests.ManifestRuleUtilities.HostOs;
import com.micatechnologies.minecraft.launcher.utilities.ProcessUtilities;
import com.micatechnologies.minecraft.launcher.utilities.objects.GameMode;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for Cleanroom as a mod loader, against the real Cleanroom 0.7.14-alpha
 * {@code version.json} and {@code install_profile.json} (test fixtures, byte for byte from the
 * installer): what {@link CleanroomProfile} reads from them, how {@link GameModLoaderCleanroom}
 * reports it, the standalone manifest that replaces the vanilla client.json (runtime, assets,
 * libraries), the launch classpath and the client and server main class and arguments.
 */
class CleanroomLoaderTest
{
    private static final String UNIVERSAL =
            "com/cleanroommc/cleanroom/0.7.14-alpha/cleanroom-0.7.14-alpha-universal.jar";

    private static String fixture( String name )
    {
        try ( InputStream in = CleanroomLoaderTest.class.getResourceAsStream( "/cleanroom-0.7.14-alpha/" + name ) ) {
            assertNotNull( in, "missing fixture " + name );
            return new String( in.readAllBytes(), StandardCharsets.UTF_8 );
        }
        catch ( IOException e ) {
            throw new IllegalStateException( e );
        }
    }

    private static CleanroomProfile realProfile() throws ModpackException
    {
        return CleanroomProfile.parse( fixture( "version.json" ), fixture( "install_profile.json" ) );
    }

    private static GameModPack stubPack()
    {
        GameModPack pack = new GameModPack();
        pack.packName = "Cleanroom Pack";
        pack.packModLoader = "Cleanroom";
        return pack;
    }

    private static GameModLoaderCleanroom realLoader() throws ModpackException
    {
        return new GameModLoaderCleanroom( realProfile(), stubPack() );
    }

    // =========================================================================
    //  Loader kind
    // =========================================================================

    @Test
    void cleanroomIsALoaderKindInAnyCase()
    {
        for ( String type : new String[]{ "cleanroom", "Cleanroom", " CLEANROOM " } ) {
            GameModPack pack = new GameModPack();
            pack.packModLoader = type;
            assertEquals( "cleanroom", pack.getModLoaderType() );
            assertTrue( pack.usesStandaloneLoaderProfile() );
            assertFalse( pack.usesPostInstallSteps(), "zero install processors: no game patching step" );
        }
        GameModPack forge = new GameModPack();
        forge.packModLoader = "forge";
        assertFalse( forge.usesStandaloneLoaderProfile() );
        assertTrue( forge.usesPostInstallSteps() );
        GameModPack fabric = new GameModPack();
        fabric.packModLoader = "fabric";
        assertFalse( fabric.usesStandaloneLoaderProfile() );
    }

    @Test
    void theInstallerUrlComesFromPackForgeUrl()
    {
        GameModPack pack = JsonParserHolder.pack( """
                {"packName": "P", "packModLoader": "Cleanroom",
                 "packForgeURL": "https://github.com/CleanroomMC/Cleanroom/releases/download/0.7.14-alpha/cleanroom-0.7.14-alpha-installer.jar",
                 "packForgeHash": "8aad156fec391bb97ec6956c40fb795d08fc7c0d"}
                """ );
        assertEquals( "cleanroom", pack.getModLoaderType() );
        assertTrue( pack.getModLoaderURL().endsWith( "-installer.jar" ) );
        assertEquals( "8aad156fec391bb97ec6956c40fb795d08fc7c0d", pack.getModLoaderHash() );
    }

    /** Parses a pack manifest the way the launcher does. */
    private static final class JsonParserHolder
    {
        static GameModPack pack( String json )
        {
            return com.micatechnologies.minecraft.launcher.utilities.JSONUtilities.getGson()
                                                                                  .fromJson( json, GameModPack.class );
        }
    }

    // =========================================================================
    //  What the profiles say
    // =========================================================================

    @Test
    void theRealInstallerProfilesReadAsDocumented() throws ModpackException
    {
        GameModLoaderCleanroom loader = realLoader();
        assertEquals( "Cleanroom", loader.getName() );
        assertEquals( "Cleanroom-0.7.14-alpha", loader.getLoaderVersion() );
        // Never the standalone id: assets, version lookups and the UI need 1.12.2.
        assertEquals( "1.12.2", loader.getMinecraftVersion() );
        assertEquals( "top.outlands.foundation.boot.Foundation", loader.getMinecraftMainClass() );
        assertEquals( "top.outlands.foundation.boot.Foundation", loader.getServerMainClass() );
        List< String > args = ProcessUtilities.splitCommandLine( loader.getMinecraftArguments() );
        assertEquals( 1, args.stream().filter( "--tweakClass"::equals ).count() );
        assertTrue( args.contains( "net.minecraftforge.fml.common.launcher.FMLTweaker" ) );
        assertTrue( realProfile().jvmArgs().isEmpty() );
    }

    @Test
    void theMinecraftVersionIsNeverTheStandaloneId() throws ModpackException
    {
        JsonObject vj = JsonParser.parseString( "{\"id\":\"Cleanroom-0.7.14-alpha\",\"mainClass\":\"M\"}" )
                                  .getAsJsonObject();
        assertEquals( "1.12.2", CleanroomProfile.of( vj, null ).minecraftVersion() );
        JsonObject blank = JsonParser.parseString( "{\"minecraft\":\" \"}" ).getAsJsonObject();
        assertEquals( "1.12.2", CleanroomProfile.of( vj, blank ).minecraftVersion() );
        JsonObject inherits = JsonParser.parseString(
                "{\"id\":\"Cleanroom-9\",\"mainClass\":\"M\",\"inheritsFrom\":\"1.12.2\"}" ).getAsJsonObject();
        assertEquals( "1.12.2", CleanroomProfile.of( inherits, null ).minecraftVersion() );
    }

    @Test
    void aProfileLessInstallerStillNamesItsServerFacts() throws ModpackException
    {
        CleanroomProfile p = CleanroomProfile.parse( fixture( "version.json" ), "not json" );
        assertFalse( p.hasInstallProfile() );
        assertEquals( "1.12.2", p.minecraftVersion() );
        assertEquals( "top.outlands.foundation.boot.Foundation", p.serverMainClass() );
        assertEquals( List.of( "--tweakClass", CleanroomProfile.DEFAULT_SERVER_TWEAKER ), p.serverGameArgs() );
        assertThrows( ModpackException.class, () -> CleanroomProfile.parse( null, fixture( "install_profile.json" ) ) );
        assertThrows( ModpackException.class, () -> CleanroomProfile.parse( "{\"id\":\"x\"}", null ),
                      "mainClass is required" );
    }

    @Test
    void missingServerTweakersFallBackLikeTheInstaller() throws ModpackException
    {
        JsonObject vj = JsonParser.parseString( "{\"id\":\"x\",\"mainClass\":\"M\"}" ).getAsJsonObject();
        JsonObject profile = JsonParser.parseString( "{\"serverTweakers\":[],\"jvmArgs\":[\"-Dx=1\"]}" )
                                       .getAsJsonObject();
        CleanroomProfile p = CleanroomProfile.of( vj, profile );
        assertEquals( List.of( CleanroomProfile.DEFAULT_SERVER_TWEAKER ), p.serverTweakers() );
        assertEquals( "M", p.serverMainClass() );
        assertEquals( List.of( "-Dx=1" ), p.jvmArgs() );
    }

    // =========================================================================
    //  The standalone manifest replaces the vanilla client.json
    // =========================================================================

    @Test
    void theUniversalJarIsSplitOffAndComesFromTheInstaller() throws ModpackException
    {
        CleanroomProfile p = realProfile();
        assertEquals( 1, p.embeddedLibraries().size() );
        assertEquals( "com.cleanroommc:cleanroom:0.7.14-alpha:universal",
                      p.embeddedLibraries().get( 0 ).get( "name" ).getAsString() );
        int total = JsonParser.parseString( fixture( "version.json" ) ).getAsJsonObject()
                              .getAsJsonArray( "libraries" ).size();
        assertEquals( 217, total );
        assertEquals( total - 1, p.standaloneManifest().getAsJsonArray( "libraries" ).size() );

        Set< String > entries = Set.of( "maven/" + UNIVERSAL );
        List< CleanroomProfile.EmbeddedLibrary > plan = p.planEmbedded(
                entries::contains, e -> "jar:file:/installs/P/bin/modpack.jar!/" + e );
        assertEquals( 1, plan.size() );
        assertEquals( "jar:file:/installs/P/bin/modpack.jar!/maven/" + UNIVERSAL, plan.get( 0 ).url() );
        assertEquals( "9e7ed10176074d58903f72358d4f54dba0c50677", plan.get( 0 ).sha1() );
        assertEquals( UNIVERSAL, plan.get( 0 ).localPath() );

        // An installer without the jar it lists is refused, not fetched from a made-up URL.
        ModpackException e = assertThrows( ModpackException.class,
                                           () -> p.planEmbedded( x -> false, x -> x ) );
        assertTrue( e.getMessage().contains( "cleanroom-0.7.14-alpha-universal.jar" ), e.getMessage() );
    }

    @Test
    void theStandaloneManifestIsACopyTheCallerMayKeep() throws ModpackException
    {
        CleanroomProfile p = realProfile();
        JsonObject first = p.standaloneManifest();
        first.add( "libraries", new JsonArray() );
        assertEquals( 216, p.standaloneManifest().getAsJsonArray( "libraries" ).size() );
    }

    @Test
    void theRuntimeAssetsAndArgumentsComeFromTheLoaderProfileNotVanilla() throws ModpackException
    {
        GameModLoaderCleanroom loader = realLoader();
        GameLibraryManifest m = GameLibraryManifest.standalone( loader.getStandaloneVersionManifest(), stubPack() );
        assertTrue( m.isStandalone() );
        // Vanilla 1.12.2 would say jre-legacy / 8.
        assertEquals( "java-runtime-epsilon", m.getRequiredRuntimeComponent() );
        assertEquals( 25, m.getRequiredJavaMajorVersion() );
        assertEquals( "1.12", m.getAssetIndexVersion() );
        assertEquals( "", m.getJvmArguments(), "no arguments.jvm: -cp and the natives path are added by the launcher" );
        assertEquals( loader.getMinecraftArguments(), m.getGameArguments() );
        assertTrue( m.readToJsonObject().getAsJsonObject( "downloads" ).has( "server" ) );
        assertEquals( "0f275bc1547d01fa5f56ba34bdc87d981ee12daf",
                      m.readToJsonObject().getAsJsonObject( "downloads" ).getAsJsonObject( "client" )
                       .get( "sha1" ).getAsString(), "the vanilla 1.12.2 client jar" );
        assertEquals( "886945bfb2b978778c3a0288fd7fab09d315b25f",
                      m.readToJsonObject().getAsJsonObject( "downloads" ).getAsJsonObject( "server" )
                       .get( "sha1" ).getAsString(), "the vanilla 1.12.2 server jar" );
        assertFalse( m.updateLocalFile( LaunchPrepareContext.NONE ), "nothing to download" );
    }

    // =========================================================================
    //  Classpath
    // =========================================================================

    private static final Host WINDOWS_X64 = new Host( HostOs.WINDOWS, "amd64", "10.0" );

    @Test
    void theClasspathHasTheUniversalJarFirstEveryProfileLibraryAndTheGameJar() throws ModpackException
    {
        String root = File.separator + "installs" + File.separator + "P" + File.separator;
        String libs = root + "libraries" + File.separator;
        String loaderCp = libs + UNIVERSAL.replace( "/", File.separator );
        List< GameLibrary > resolved = GameLibraryManifest.extractLibraries(
                realProfile().standaloneManifest(), WINDOWS_X64, GameMode.CLIENT );
        List< String > mc = new ArrayList<>();
        for ( GameLibrary l : resolved ) {
            mc.add( libs + l.getLocalFilePath().replace( "/", File.separator ) );
        }
        mc.add( root + "bin" + File.separator + "minecraft.jar" );

        List< String > merged = Arrays.asList( GameModPackLauncher.mergeClasspathsLoaderPriority(
                loaderCp, String.join( File.pathSeparator, mc ) ).split( java.util.regex.Pattern.quote( File.pathSeparator ) ) );
        assertEquals( loaderCp, merged.get( 0 ), "the installer's jar first" );
        assertTrue( merged.get( merged.size() - 1 ).endsWith( File.separator + "bin" + File.separator + "minecraft.jar" ),
                    "no patched client: the vanilla game jar stays" );
        assertEquals( 1 + mc.size(), merged.size(), "no profile library is dropped by the version-agnostic dedup" );
        for ( String e : merged ) {
            String n = e.replace( '\\', '/' );
            assertFalse( n.contains( "/org/lwjgl/lwjgl/lwjgl/2." ) || n.contains( "/net/minecraft/launchwrapper/" ),
                         "no vanilla LWJGL 2 or LaunchWrapper: " + n );
        }
        assertTrue( merged.stream().anyMatch( e -> e.replace( '\\', '/' ).endsWith( "/lwjgl-3.4.3.jar" ) ) );
        assertTrue( merged.stream().anyMatch( e -> e.replace( '\\', '/' ).endsWith( "/lwjgly-2.3.0.jar" ) ) );
    }

    // =========================================================================
    //  Main class and arguments, client and server
    // =========================================================================

    @Test
    void clientLaunchUsesTheProfileTemplateOnce() throws ModpackException
    {
        GameModLoaderCleanroom loader = realLoader();
        GameModPackLauncher.MainClassAndGameArgs r = GameModPackLauncher.mainClassAndGameArgs(
                true, null, loader.getMinecraftArguments(), loader );
        assertEquals( "top.outlands.foundation.boot.Foundation", r.mainClass() );
        List< String > args = ProcessUtilities.splitCommandLine( r.gameArgs() );
        assertEquals( 1, args.stream().filter( "--tweakClass"::equals ).count() );
        assertEquals( 1, args.stream().filter( "--username"::equals ).count() );
    }

    @Test
    void serverLaunchMirrorsTheInstallerServerMode() throws ModpackException
    {
        GameModPackLauncher.MainClassAndGameArgs r = GameModPackLauncher.mainClassAndGameArgs(
                false, null, "", realLoader() );
        // serverMainClass and --tweakClass per serverTweakers, never legacy Forge's
        // ServerLaunchWrapper (which would pick its own tweaker).
        assertEquals( "top.outlands.foundation.boot.Foundation", r.mainClass() );
        assertEquals( List.of( "--tweakClass", "net.minecraftforge.fml.common.launcher.FMLServerTweaker" ),
                      ProcessUtilities.splitCommandLine( r.gameArgs() ) );
    }

    @Test
    void otherLoadersAndVanillaKeepTheirServerAndClientChoices() throws ModpackException
    {
        GameModLoader legacyForge = new GameModLoader()
        {
            public String getName() { return "Forge"; }
            public String getMinecraftVersion() { return "1.12.2"; }
            public String getLoaderVersion() { return "1.12.2-forge-14.23.5.2860"; }
            public String getMinecraftArguments() { return "--tweakClass net.minecraftforge.fml.common.launcher.FMLTweaker"; }
            public String getJvmArguments() { return ""; }
            public String getMinecraftMainClass() { return "net.minecraft.launchwrapper.Launch"; }
            public String getServerMainClass() { return "net.minecraftforge.fml.relauncher.ServerLaunchWrapper"; }
            public String buildClasspath( GameMode mode, GameModPackProgressProvider progress ) { return ""; }
        };
        GameModPackLauncher.MainClassAndGameArgs server = GameModPackLauncher.mainClassAndGameArgs(
                false, null, "", legacyForge );
        assertEquals( "net.minecraftforge.fml.relauncher.ServerLaunchWrapper", server.mainClass() );
        assertEquals( "", server.gameArgs(), "legacy Forge's server picks its own tweaker" );
        GameModPackLauncher.MainClassAndGameArgs client = GameModPackLauncher.mainClassAndGameArgs(
                true, null, "--username ${auth_player_name}", legacyForge );
        assertEquals( "--username ${auth_player_name} --tweakClass net.minecraftforge.fml.common.launcher.FMLTweaker",
                      client.gameArgs(), "vanilla arguments followed by the loader's" );
        GameModPackLauncher.MainClassAndGameArgs vanilla = GameModPackLauncher.mainClassAndGameArgs(
                false, null, "", null );
        assertEquals( "net.minecraft.client.main.Main", vanilla.mainClass() );
        assertEquals( "", vanilla.gameArgs() );
    }

    @Test
    void macosClientsStartOnTheFirstThreadLikeTheInstallerProfile() throws ModpackException
    {
        CleanroomProfile p = realProfile();
        assertTrue( p.clientJvmArgs( HostOs.WINDOWS ).isEmpty() );
        assertTrue( p.clientJvmArgs( HostOs.LINUX ).isEmpty() );
        assertEquals( List.of( "-XstartOnFirstThread" ), p.clientJvmArgs( HostOs.MACOS ) );
        JsonObject vj = JsonParser.parseString( "{\"id\":\"x\",\"mainClass\":\"M\"}" ).getAsJsonObject();
        JsonObject withFlag = JsonParser.parseString( "{\"jvmArgs\":[\"-XstartOnFirstThread\"]}" ).getAsJsonObject();
        assertEquals( List.of( "-XstartOnFirstThread" ), CleanroomProfile.of( vj, withFlag ).clientJvmArgs( HostOs.MACOS ),
                      "never twice" );
        assertEquals( String.join( " ", p.clientJvmArgs( ManifestRuleUtilities.Host.current().os() ) ),
                      realLoader().getJvmArguments() );
    }
}
