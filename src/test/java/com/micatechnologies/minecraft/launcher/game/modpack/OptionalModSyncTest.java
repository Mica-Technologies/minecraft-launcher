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

import com.micatechnologies.minecraft.launcher.utilities.JSONUtilities;
import com.micatechnologies.minecraft.launcher.utilities.objects.GameMode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How {@code packOptionalMods} is parsed and what the sync installs and keeps: the side defaults
 * of the Gson model, the expected set on a client and on a server, and the verify fingerprint
 * that makes a toggle force a full verify.
 */
class OptionalModSyncTest
{
    private static final String MANIFEST = """
            {
              "packName": "Alto",
              "packVersion": "1.0.0",
              "packMods": [
                { "name": "JEI", "remote": "https://example.invalid/jei.jar", "local": "jei.jar",
                  "sha1": "-1", "md5": "-1", "sha256": "-1", "clientReq": true, "serverReq": true }
              ],
              "packOptionalMods": [
                { "name": "AUSM", "remote": "https://example.invalid/ausm.jar", "local": "ausm.jar",
                  "sha256": "abc", "description": "Shader support", "requires": ["lib.jar"],
                  "conflictsWith": ["celeritas-extra.jar"] },
                { "name": "Lib", "remote": "https://example.invalid/lib.jar", "local": "lib.jar",
                  "serverReq": true, "defaultEnabled": true },
                { "name": "Extra", "remote": "https://example.invalid/x.jar", "local": "celeritas-extra.jar",
                  "clientReq": false, "serverReq": true },
                { "name": "Dup", "remote": "https://example.invalid/jei2.jar", "local": "JEI.jar",
                  "defaultEnabled": true },
                { "name": "Nested", "remote": "https://example.invalid/n.jar", "local": "mods/nested.jar",
                  "defaultEnabled": true, "serverReq": true }
              ]
            }
            """;

    private static GameModPack pack()
    {
        return JSONUtilities.getGson().fromJson( MANIFEST, GameModPack.class );
    }

    private static List< String > keys( List< GameMod > mods )
    {
        List< String > out = new ArrayList<>();
        for ( GameMod mod : mods ) {
            out.add( GameOptionalMod.keyOf( mod.getLocalFilePath() ) );
        }
        return out;
    }

    @Test
    void theGsonModelAppliesOptionalSideDefaultsAndReadsTheExtraFields()
    {
        List< GameOptionalMod > optional = pack().getPackOptionalMods();
        assertEquals( 5, optional.size() );

        GameOptionalMod ausm = optional.get( 0 );
        assertTrue( ausm.isClientSide(), "absent clientReq means client-side" );
        assertFalse( ausm.isServerSide(), "absent serverReq means not on servers" );
        assertFalse( ausm.isDefaultEnabled(), "absent defaultEnabled means off" );
        assertEquals( "Shader support", ausm.getDescription() );
        assertEquals( List.of( "lib.jar" ), ausm.getRequires() );
        assertEquals( List.of( "celeritas-extra.jar" ), ausm.getConflictsWith() );
        assertEquals( "AUSM", ausm.getDisplayName() );

        GameOptionalMod lib = optional.get( 1 );
        assertEquals( "lib.jar", lib.getLocalName() );
        assertTrue( lib.isPlainFileName() );
        assertTrue( lib.isServerSide() );
        assertTrue( lib.isDefaultEnabled() );

        GameOptionalMod extra = optional.get( 2 );
        assertFalse( extra.isClientSide() );

        GameOptionalMod nested = optional.get( 4 );
        assertEquals( "nested.jar", nested.getLocalName(), "a leading mods/ is not part of the name" );
        assertFalse( nested.isPlainFileName() );
    }

    @Test
    void aManifestWithoutTheKeyHasNoOptionalMods()
    {
        GameModPack plain = JSONUtilities.getGson().fromJson( "{\"packName\":\"P\",\"packMods\":[]}", GameModPack.class );
        assertTrue( plain.getPackOptionalMods().isEmpty() );
        assertEquals( List.of(), GameModPackFileSync.modsForSide( plain, GameMode.CLIENT, Map.of() ) );
    }

    @Test
    void aClientSyncsRequiredModsPlusTheOptionalModsThatAreOn()
    {
        // Defaults: only lib.jar (and the duplicate of jei.jar, which the required entry shadows).
        assertEquals( List.of( "jei.jar", "lib.jar" ),
                      keys( GameModPackFileSync.modsForSide( pack(), GameMode.CLIENT, Map.of() ) ) );

        // Turning AUSM on pulls in lib.jar; the duplicate stays out.
        assertEquals( List.of( "jei.jar", "ausm.jar", "lib.jar" ),
                      keys( GameModPackFileSync.modsForSide( pack(), GameMode.CLIENT, Map.of( "ausm.jar", true ) ) ) );
    }

    @Test
    void aDisabledOptionalModIsNotInTheExpectedSetSoTheSweepRemovesIt()
    {
        List< String > expected = keys( GameModPackFileSync.modsForSide( pack(), GameMode.CLIENT,
                                                                         Map.of( "lib.jar", false ) ) );
        assertFalse( expected.contains( "lib.jar" ) );
        assertFalse( expected.contains( "ausm.jar" ) );
    }

    @Test
    void aServerIgnoresPlayerChoicesAndUsesServerSideDefaults()
    {
        assertEquals( List.of( "jei.jar", "lib.jar" ),
                      keys( GameModPackFileSync.modsForSide( pack(), GameMode.SERVER,
                                                             Map.of( "celeritas-extra.jar", true,
                                                                     "lib.jar", false ) ) ) );
    }

    @Test
    void anOptionalModWhoseFileNameHoldsAFolderIsNeverInstalled()
    {
        // mods/nested.jar would land in mods/mods/, where the game never loads it.
        assertFalse( keys( GameModPackFileSync.modsForSide( pack(), GameMode.CLIENT, Map.of() ) ).contains( "nested.jar" ) );
        assertFalse( keys( GameModPackFileSync.modsForSide( pack(), GameMode.SERVER, Map.of() ) ).contains( "nested.jar" ) );
    }

    @Test
    void noAuthoritativeModListMeansNoExpectedSet()
    {
        GameModPack stub = new GameModPack();
        assertNull( GameModPackFileSync.modsForSide( stub, GameMode.CLIENT, Map.of() ) );
    }

    @Test
    void theFingerprintIsTheManifestHashUntilAnOptionalModIsOn()
    {
        assertSame( "abc", GameModPack.verifyFingerprint( "abc", List.of() ) );
        assertNull( GameModPack.verifyFingerprint( null, List.of( "a.jar" ) ) );

        String on = GameModPack.verifyFingerprint( "abc", List.of( "a.jar", "b.jar" ) );
        assertNotEquals( "abc", on );
        assertEquals( on, GameModPack.verifyFingerprint( "abc", List.of( "b.jar", "a.jar" ) ),
                      "order must not matter" );
        assertNotEquals( on, GameModPack.verifyFingerprint( "abc", List.of( "a.jar" ) ) );
    }

    @Test
    void enabledKeysFollowTheResolverForTheParsedList()
    {
        assertEquals( Set.of( "lib.jar", "jei.jar", "nested.jar" ),
                      OptionalModResolver.resolveEnabled( pack().getPackOptionalMods(), Map.of() ) );
    }
}
