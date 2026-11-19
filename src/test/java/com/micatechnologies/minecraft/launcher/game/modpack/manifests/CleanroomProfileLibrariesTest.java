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

package com.micatechnologies.minecraft.launcher.game.modpack.manifests;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.micatechnologies.minecraft.launcher.exceptions.ModpackException;
import com.micatechnologies.minecraft.launcher.game.modpack.GameLibrary;
import com.micatechnologies.minecraft.launcher.game.modpack.manifests.ManifestRuleUtilities.Host;
import com.micatechnologies.minecraft.launcher.game.modpack.manifests.ManifestRuleUtilities.HostOs;
import com.micatechnologies.minecraft.launcher.utilities.objects.GameMode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static com.micatechnologies.minecraft.launcher.game.modpack.manifests.ManifestRuleHostTest.FREEBSD_X64;
import static com.micatechnologies.minecraft.launcher.game.modpack.manifests.ManifestRuleHostTest.LINUX_ARM64;
import static com.micatechnologies.minecraft.launcher.game.modpack.manifests.ManifestRuleHostTest.LINUX_X64;
import static com.micatechnologies.minecraft.launcher.game.modpack.manifests.ManifestRuleHostTest.MAC_ARM;
import static com.micatechnologies.minecraft.launcher.game.modpack.manifests.ManifestRuleHostTest.MAC_INTEL;
import static com.micatechnologies.minecraft.launcher.game.modpack.manifests.ManifestRuleHostTest.WINDOWS_ARM64;
import static com.micatechnologies.minecraft.launcher.game.modpack.manifests.ManifestRuleHostTest.WINDOWS_X64;
import static com.micatechnologies.minecraft.launcher.game.modpack.manifests.ManifestRuleHostTest.WINDOWS_X86;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Resolves the real Cleanroom 0.7.14-alpha {@code version.json} (a test fixture, byte for byte
 * from the installer) through {@link GameLibraryManifest#extractLibraries(JsonObject, Host, GameMode)}
 * on every desktop host. Its 82 natives entries use the legacy {@code natives}/{@code extract}
 * format with three things a Mojang client.json never has: {@code os.arch} rules written as
 * {@code x64}/{@code arm64}/{@code x86}, classifier downloads without a {@code path}, and
 * {@code "side": "client"}. Also checks that a Mojang-shaped entry resolves as it always did.
 */
class CleanroomProfileLibrariesTest
{
    /** The Cleanroom 0.7.14-alpha version.json. */
    static JsonObject versionJson()
    {
        return readFixture( "version.json" );
    }

    /** Reads a Cleanroom 0.7.14-alpha fixture from the test resources. */
    static JsonObject readFixture( String name )
    {
        try ( InputStream in = CleanroomProfileLibrariesTest.class.getResourceAsStream(
                "/cleanroom-0.7.14-alpha/" + name ) ) {
            if ( in == null ) {
                throw new IllegalStateException( "missing fixture " + name );
            }
            return JsonParser.parseString( new String( in.readAllBytes(), StandardCharsets.UTF_8 ) )
                             .getAsJsonObject();
        }
        catch ( IOException e ) {
            throw new IllegalStateException( e );
        }
    }

    private static List< GameLibrary > resolve( Host host, GameMode side ) throws ModpackException
    {
        return GameLibraryManifest.extractLibraries( versionJson(), host, side );
    }

    /** The classifier jar file names (without {@code .jar}) the host extracts as natives. */
    private static List< String > natives( Host host ) throws ModpackException
    {
        List< String > out = new ArrayList<>();
        for ( GameLibrary l : resolve( host, GameMode.CLIENT ) ) {
            if ( l.isNativeLib() ) {
                String p = l.getLocalFilePath().replace( '\\', '/' );
                out.add( p.substring( p.lastIndexOf( '/' ) + 1, p.length() - ".jar".length() ) );
            }
        }
        return out;
    }

    private static Set< String > classifiers( Host host ) throws ModpackException
    {
        Set< String > out = new TreeSet<>();
        for ( String n : natives( host ) ) {
            out.add( n.substring( n.indexOf( "-natives-" ) + 1 ) );
        }
        return out;
    }

    @Test
    void legacyNativesEntriesResolvePerHost() throws ModpackException
    {
        // Windows x64: 7 LWJGL 3 modules, text2speech and jinput, all natives-windows.
        assertEquals( 9, natives( WINDOWS_X64 ).size() );
        assertEquals( Set.of( "natives-windows" ), classifiers( WINDOWS_X64 ) );
        assertEquals( 7, natives( WINDOWS_ARM64 ).size() );
        assertEquals( Set.of( "natives-windows-arm64" ), classifiers( WINDOWS_ARM64 ) );
        assertEquals( Set.of( "natives-windows-x86" ), classifiers( WINDOWS_X86 ) );
        // Linux x64: LWJGL, text2speech and jinput.
        assertEquals( 9, natives( LINUX_X64 ).size() );
        assertEquals( Set.of( "natives-linux" ), classifiers( LINUX_X64 ) );
        assertEquals( 7, natives( LINUX_ARM64 ).size() );
        assertEquals( Set.of( "natives-linux-arm64" ), classifiers( LINUX_ARM64 ) );
        // macOS Intel: LWJGL and jinput (no text2speech natives for macOS).
        assertEquals( 8, natives( MAC_INTEL ).size() );
        assertEquals( Set.of( "natives-macos", "natives-osx" ), classifiers( MAC_INTEL ) );
        assertEquals( 7, natives( MAC_ARM ).size() );
        assertEquals( Set.of( "natives-macos-arm64" ), classifiers( MAC_ARM ) );
        // A BSD host gets none (Cleanroom's freebsd entries name an OS the rules never match).
        assertTrue( natives( FREEBSD_X64 ).isEmpty() );
    }

    @Test
    void aNativeWithoutAPathGetsItsMavenPath() throws ModpackException
    {
        GameLibrary lwjgl = null;
        for ( GameLibrary l : resolve( WINDOWS_X64, GameMode.CLIENT ) ) {
            if ( l.isNativeLib() && l.getLocalFilePath().replace( '\\', '/' ).endsWith( "/lwjgl-3.4.3-natives-windows.jar" ) ) {
                lwjgl = l;
            }
        }
        assertTrue( lwjgl != null, "the LWJGL core natives resolve on Windows x64" );
        assertEquals( "org/lwjgl/lwjgl/3.4.3/lwjgl-3.4.3-natives-windows.jar",
                      lwjgl.getLocalFilePath().replace( '\\', '/' ) );
    }

    @Test
    void aNameThatIsNoCoordinateStillFailsRatherThanInventingAPath()
    {
        JsonObject classifier = new JsonObject();
        classifier.addProperty( "url", "https://example.invalid/x.jar" );
        classifier.addProperty( "sha1", "00" );
        assertThrows( IllegalArgumentException.class,
                      () -> GameLibraryManifest.pathOrDerived( classifier, "not-a-coordinate", "natives-windows" ) );
        assertThrows( IllegalArgumentException.class,
                      () -> GameLibraryManifest.pathOrDerived( classifier, "a:b:1", "../escape" ) );
        classifier.addProperty( "path", "given/path.jar" );
        assertEquals( "given/path.jar", GameLibraryManifest.pathOrDerived( classifier, "a:b:1", "natives-x" ),
                      "a path the manifest gives always wins" );
    }

    @Test
    void nativesStayOffAServer() throws ModpackException
    {
        List< GameLibrary > client = resolve( WINDOWS_X64, GameMode.CLIENT );
        List< GameLibrary > server = resolve( WINDOWS_X64, GameMode.SERVER );
        assertTrue( server.stream().noneMatch( GameLibrary::isNativeLib ),
                    "every natives entry is side client; the installer's server mode skips them" );
        assertEquals( client.stream().filter( l -> !l.isNativeLib() ).count(), server.size() );
        // No side given: every entry, as before.
        assertEquals( client.size(), resolve( WINDOWS_X64, null ).size() );
    }

    @Test
    void noLwjgl2OrVanillaLibraryIsInTheProfile() throws ModpackException
    {
        for ( Host host : List.of( WINDOWS_X64, WINDOWS_ARM64, LINUX_X64, LINUX_ARM64, MAC_INTEL, MAC_ARM ) ) {
            for ( GameLibrary l : resolve( host, GameMode.CLIENT ) ) {
                String p = l.getLocalFilePath().replace( '\\', '/' );
                assertFalse( p.startsWith( "org/lwjgl/lwjgl/lwjgl/" ) || p.contains( "/2.9." ), "LWJGL 2: " + p );
                assertFalse( p.startsWith( "net/minecraft/" ), p );
            }
        }
    }

    @Test
    void archRuledArtifactsFollowTheHostToo() throws ModpackException
    {
        assertEquals( List.of( "netty-codec-native-quic-4.2.16.Final-windows-x86_64.jar" ), quic( WINDOWS_X64 ) );
        assertEquals( List.of( "netty-codec-native-quic-4.2.16.Final-osx-aarch_64.jar" ), quic( MAC_ARM ) );
        assertTrue( quic( WINDOWS_ARM64 ).isEmpty() );
    }

    private static List< String > quic( Host host ) throws ModpackException
    {
        List< String > out = new ArrayList<>();
        for ( GameLibrary l : resolve( host, GameMode.CLIENT ) ) {
            String p = l.getLocalFilePath().replace( '\\', '/' );
            if ( p.contains( "/netty-codec-native-quic/" ) ) {
                out.add( p.substring( p.lastIndexOf( '/' ) + 1 ) );
            }
        }
        return out;
    }

    // =========================================================================
    //  Mojang-shaped entries resolve as they always did
    // =========================================================================

    /** A 1.12.2-style LWJGL 2 platform entry: natives with ${arch}, disallowed on macOS. */
    private static JsonObject mojangLwjglPlatform()
    {
        return JsonParser.parseString( """
                {"name": "org.lwjgl.lwjgl:lwjgl-platform:2.9.4-nightly-20150209",
                 "downloads": {"classifiers": {
                   "natives-linux": {"path": "org/lwjgl/lwjgl/lwjgl-platform/2.9.4/lwjgl-platform-2.9.4-natives-linux.jar",
                                     "sha1": "aa", "url": "https://libraries.minecraft.net/l.jar"},
                   "natives-windows-64": {"path": "org/lwjgl/lwjgl/lwjgl-platform/2.9.4/lwjgl-platform-2.9.4-natives-windows-64.jar",
                                          "sha1": "bb", "url": "https://libraries.minecraft.net/w64.jar"},
                   "natives-windows-32": {"path": "org/lwjgl/lwjgl/lwjgl-platform/2.9.4/lwjgl-platform-2.9.4-natives-windows-32.jar",
                                          "sha1": "cc", "url": "https://libraries.minecraft.net/w32.jar"}}},
                 "natives": {"linux": "natives-linux", "windows": "natives-windows-${arch}"},
                 "rules": [{"action": "allow"}, {"action": "disallow", "os": {"name": "osx"}}]}
                """ ).getAsJsonObject();
    }

    private static JsonObject manifestOf( JsonObject... entries )
    {
        JsonArray libs = new JsonArray();
        for ( JsonObject e : entries ) {
            libs.add( e );
        }
        JsonObject m = new JsonObject();
        m.add( "libraries", libs );
        return m;
    }

    @Test
    void mojangNativesStillResolvePerHostAndSide() throws ModpackException
    {
        JsonObject m = manifestOf( mojangLwjglPlatform() );
        assertEquals( "org/lwjgl/lwjgl/lwjgl-platform/2.9.4/lwjgl-platform-2.9.4-natives-windows-64.jar",
                      GameLibraryManifest.extractLibraries( m, WINDOWS_X64, GameMode.CLIENT ).get( 0 )
                                         .getLocalFilePath() );
        assertEquals( "org/lwjgl/lwjgl/lwjgl-platform/2.9.4/lwjgl-platform-2.9.4-natives-windows-32.jar",
                      GameLibraryManifest.extractLibraries( m, WINDOWS_X86, GameMode.CLIENT ).get( 0 )
                                         .getLocalFilePath() );
        assertEquals( 1, GameLibraryManifest.extractLibraries( m, LINUX_X64, GameMode.CLIENT ).size() );
        assertTrue( GameLibraryManifest.extractLibraries( m, MAC_INTEL, GameMode.CLIENT ).isEmpty(),
                    "disallowed on macOS" );
        // Mojang entries have no side: a server resolves them the same.
        assertEquals( 1, GameLibraryManifest.extractLibraries( m, LINUX_X64, GameMode.SERVER ).size() );
    }

    @Test
    void sideMarkedEntriesFollowTheGameSide()
    {
        JsonObject client = new JsonObject();
        client.addProperty( "side", "Client" );
        JsonObject server = new JsonObject();
        server.addProperty( "side", "server" );
        JsonObject none = new JsonObject();
        assertTrue( GameLibraryManifest.appliesToSide( client, GameMode.CLIENT ) );
        assertFalse( GameLibraryManifest.appliesToSide( client, GameMode.SERVER ) );
        assertTrue( GameLibraryManifest.appliesToSide( server, GameMode.SERVER ) );
        assertFalse( GameLibraryManifest.appliesToSide( server, GameMode.CLIENT ) );
        assertTrue( GameLibraryManifest.appliesToSide( none, GameMode.SERVER ) );
        assertTrue( GameLibraryManifest.appliesToSide( client, null ) );
    }
}
