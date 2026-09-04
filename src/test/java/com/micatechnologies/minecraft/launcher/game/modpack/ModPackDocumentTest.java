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
import com.google.gson.JsonSyntaxException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ModPackDocument} — the manifest rules extracted out of the modpack
 * editor screen.
 *
 * <p>Why this matters: every one of these rules previously lived in a private method of a
 * 2,533-line JavaFX controller and had no coverage at all. They are the rules that decide
 * what ends up in a user's manifest file, and several of them are lossy if they get them
 * wrong — the hash round-trip in particular silently drops a checksum the user's manifest
 * carried, which turns into a file the launcher can no longer verify.</p>
 *
 * <p>Validation messages are asserted as <b>keys</b>, never as rendered text, via an
 * echoing {@link ModPackDocument.MessageResolver}. That keeps the suite locale-independent
 * — the same reason {@link PlayTimeFormatting} returns keys.</p>
 */
class ModPackDocumentTest
{
    /**
     * Renders a message as {@code key(arg, arg)} so assertions read against localization
     * keys rather than English text.
     */
    private static final ModPackDocument.MessageResolver ECHO = ( key, args ) -> {
        if ( args == null || args.length == 0 ) {
            return key;
        }
        StringBuilder sb = new StringBuilder( key ).append( "(" );
        for ( int i = 0; i < args.length; i++ ) {
            if ( i > 0 ) {
                sb.append( ", " );
            }
            sb.append( args[ i ] );
        }
        return sb.append( ")" ).toString();
    };

    // region blank()

    @Test
    void blankDocumentCarriesTheCurrentManifestFormat()
    {
        assertEquals( 2, ModPackDocument.blank().json().get( "manifestFormat" ).getAsInt() );
    }

    @Test
    void blankDocumentDefaultsVersionToOneZeroZero()
    {
        assertEquals( "1.0.0", ModPackDocument.blank().getString( ModPackDocument.KEY_PACK_VERSION ) );
    }

    @Test
    void blankDocumentDefaultsMinimumRamToTwoGigabytes()
    {
        assertEquals( "2", ModPackDocument.blank().getString( ModPackDocument.KEY_PACK_MIN_RAM_GB ) );
    }

    /**
     * Forge is the default so the editor's existing "Pick Forge Version" flow keeps working
     * on a brand-new pack without the user first choosing a loader.
     */
    @Test
    void blankDocumentDefaultsTheModLoaderToForge()
    {
        assertEquals( "forge", ModPackDocument.blank().getString( "packModLoader" ) );
    }

    @Test
    void blankDocumentGivesEveryFileListAnEmptyArray()
    {
        ModPackDocument doc = ModPackDocument.blank();
        for ( ModPackDocument.FileList list : ModPackDocument.FileList.values() ) {
            assertTrue( doc.json().get( list.getKey() ).isJsonArray(),
                        list.getKey() + " should be an array" );
            assertTrue( doc.readFileList( list ).isEmpty(), list.getKey() + " should be empty" );
        }
    }

    @Test
    void blankDocumentGivesScanExclusionsAnEmptyArray()
    {
        assertTrue( ModPackDocument.blank().json().get( ModPackDocument.KEY_SCAN_EXCLUSIONS ).isJsonArray() );
    }

    /**
     * The blank document must survive its own validation round-trip check — otherwise
     * creating a new pack would immediately report an error the user cannot act on.
     */
    @Test
    void blankDocumentOnlyFailsValidationOnTheEmptyRequiredName()
    {
        List< String > issues = ModPackDocument.blank().validate( ECHO );
        assertEquals( 1, issues.size(), "unexpected issues: " + issues );
        assertTrue( issues.get( 0 ).startsWith( "editor.validate.required" ), issues.get( 0 ) );
    }

    // endregion

    // region fromJson / wrapping

    @Test
    void fromJsonParsesAManifest()
    {
        ModPackDocument doc = ModPackDocument.fromJson( "{\"packName\":\"Test Pack\"}" );
        assertEquals( "Test Pack", doc.getString( ModPackDocument.KEY_PACK_NAME ) );
    }

    /**
     * Empty input is the one case Gson answers with a bare {@code null} rather than throwing,
     * so it is the case the explicit guard exists for.
     */
    @Test
    void fromJsonRejectsEmptyInput()
    {
        assertThrows( IllegalArgumentException.class, () -> ModPackDocument.fromJson( "" ) );
    }

    /**
     * Anything else non-object fails inside Gson instead, as a {@link JsonSyntaxException}.
     * Both paths reject; they just reject with different types, which callers need to know.
     */
    @Test
    void fromJsonRejectsJsonThatIsNotAnObject()
    {
        assertThrows( JsonSyntaxException.class, () -> ModPackDocument.fromJson( "null" ) );
        assertThrows( JsonSyntaxException.class, () -> ModPackDocument.fromJson( "[1,2]" ) );
        assertThrows( JsonSyntaxException.class, () -> ModPackDocument.fromJson( "not json" ) );
    }

    @Test
    void wrappingRejectsNull()
    {
        assertThrows( IllegalArgumentException.class, () -> ModPackDocument.wrapping( null ) );
    }

    /**
     * {@code wrapping} deliberately adopts the caller's object rather than copying it, so the
     * editor can hand over an already-parsed manifest and keep its own reference in sync.
     */
    @Test
    void wrappingAdoptsTheCallersObjectWithoutCopying()
    {
        JsonObject backing = new JsonObject();
        ModPackDocument doc = ModPackDocument.wrapping( backing );
        doc.putString( ModPackDocument.KEY_PACK_NAME, "Live" );
        assertSame( backing, doc.json() );
        assertEquals( "Live", backing.get( ModPackDocument.KEY_PACK_NAME ).getAsString() );
    }

    // endregion

    // region scalar accessors

    @Test
    void getStringReturnsEmptyForAnAbsentKey()
    {
        assertEquals( "", ModPackDocument.wrapping( new JsonObject() ).getString( "nope" ) );
    }

    @Test
    void getStringReturnsEmptyForAJsonNullValue()
    {
        assertEquals( "", ModPackDocument.fromJson( "{\"packURL\":null}" ).getString( "packURL" ) );
    }

    @Test
    void getBoolReturnsFalseForAnAbsentKey()
    {
        assertFalse( ModPackDocument.wrapping( new JsonObject() ).getBool( "packUnstable" ) );
    }

    @Test
    void getBoolReturnsFalseForAJsonNullValue()
    {
        assertFalse( ModPackDocument.fromJson( "{\"packUnstable\":null}" ).getBool( "packUnstable" ) );
    }

    @Test
    void putBoolAndGetBoolRoundTrip()
    {
        ModPackDocument doc = ModPackDocument.blank();
        doc.putBool( "packUnstable", true );
        assertTrue( doc.getBool( "packUnstable" ) );
    }

    // endregion

    // region string | string[] fields

    @Test
    void aSingleLineIsStoredAsABareString()
    {
        ModPackDocument doc = ModPackDocument.blank();
        doc.putStringOrArray( "packLogoURL", "https://example.test/logo.png" );
        assertTrue( doc.json().get( "packLogoURL" ).isJsonPrimitive() );
        assertEquals( "https://example.test/logo.png", doc.getStringOrArrayLines( "packLogoURL" ) );
    }

    @Test
    void severalLinesAreStoredAsAnArrayInOrder()
    {
        ModPackDocument doc = ModPackDocument.blank();
        doc.putStringOrArray( "packLogoURL", "one\ntwo\nthree" );
        assertTrue( doc.json().get( "packLogoURL" ).isJsonArray() );
        assertEquals( 3, doc.json().getAsJsonArray( "packLogoURL" ).size() );
        assertEquals( "one\ntwo\nthree", doc.getStringOrArrayLines( "packLogoURL" ) );
    }

    @Test
    void noNonBlankLinesStoresAnEmptyString()
    {
        ModPackDocument doc = ModPackDocument.blank();
        doc.putStringOrArray( "packLogoURL", "   \n\n  " );
        assertTrue( doc.json().get( "packLogoURL" ).isJsonPrimitive() );
        assertEquals( "", doc.getStringOrArrayLines( "packLogoURL" ) );
    }

    @Test
    void blankLinesAreDroppedAndSurvivingLinesAreTrimmed()
    {
        ModPackDocument doc = ModPackDocument.blank();
        doc.putStringOrArray( "packLogoURL", "  a  \n\n   \n  b  " );
        assertEquals( "a\nb", doc.getStringOrArrayLines( "packLogoURL" ) );
    }

    @Test
    void putStringOrArrayTreatsNullAsEmpty()
    {
        ModPackDocument doc = ModPackDocument.blank();
        doc.putStringOrArray( "packLogoURL", null );
        assertEquals( "", doc.getStringOrArrayLines( "packLogoURL" ) );
    }

    @Test
    void getStringOrArrayLinesSkipsNullArrayElements()
    {
        ModPackDocument doc = ModPackDocument.fromJson( "{\"packLogoURL\":[\"a\",null,\"b\"]}" );
        assertEquals( "a\nb", doc.getStringOrArrayLines( "packLogoURL" ) );
    }

    @Test
    void getStringOrArrayLinesReturnsEmptyForAnAbsentKey()
    {
        assertEquals( "", ModPackDocument.wrapping( new JsonObject() ).getStringOrArrayLines( "packLogoURL" ) );
    }

    /**
     * A two-element array collapses to two lines, which then write back as an array — the
     * shape must be stable across an edit cycle that changes nothing.
     */
    @Test
    void stringOrArrayShapeIsStableAcrossAnEditCycle()
    {
        ModPackDocument doc = ModPackDocument.fromJson( "{\"packLogoURL\":[\"a\",\"b\"]}" );
        doc.putStringOrArray( "packLogoURL", doc.getStringOrArrayLines( "packLogoURL" ) );
        assertTrue( doc.json().get( "packLogoURL" ).isJsonArray() );
        assertEquals( "a\nb", doc.getStringOrArrayLines( "packLogoURL" ) );
    }

    /**
     * A one-element array does <b>not</b> survive as an array — it collapses to a bare
     * string. That is the editor's long-standing behaviour and it is lossless as far as the
     * manifest reader is concerned, but it is a real shape change, so it is pinned here.
     */
    @Test
    void aSingleElementArrayCollapsesToABareStringAcrossAnEditCycle()
    {
        ModPackDocument doc = ModPackDocument.fromJson( "{\"packLogoURL\":[\"only\"]}" );
        doc.putStringOrArray( "packLogoURL", doc.getStringOrArrayLines( "packLogoURL" ) );
        assertTrue( doc.json().get( "packLogoURL" ).isJsonPrimitive() );
        assertEquals( "only", doc.getStringOrArrayLines( "packLogoURL" ) );
    }

    // endregion

    // region always-array fields

    @Test
    void putArrayLinesStoresAnArrayEvenWhenThereIsOneLine()
    {
        ModPackDocument doc = ModPackDocument.blank();
        doc.putArrayLines( ModPackDocument.KEY_SCAN_EXCLUSIONS, "mods/onlyone.jar" );
        assertTrue( doc.json().get( ModPackDocument.KEY_SCAN_EXCLUSIONS ).isJsonArray() );
        assertEquals( "mods/onlyone.jar", doc.getArrayLines( ModPackDocument.KEY_SCAN_EXCLUSIONS ) );
    }

    @Test
    void putArrayLinesStoresAnEmptyArrayForBlankInput()
    {
        ModPackDocument doc = ModPackDocument.blank();
        doc.putArrayLines( ModPackDocument.KEY_SCAN_EXCLUSIONS, "  \n  " );
        assertEquals( 0, doc.json().getAsJsonArray( ModPackDocument.KEY_SCAN_EXCLUSIONS ).size() );
    }

    @Test
    void getArrayLinesReturnsEmptyWhenTheValueIsNotAnArray()
    {
        ModPackDocument doc = ModPackDocument.fromJson( "{\"packScanExclusions\":\"mods/\"}" );
        assertEquals( "", doc.getArrayLines( ModPackDocument.KEY_SCAN_EXCLUSIONS ) );
    }

    // endregion

    // region file list reads

    @Test
    void readFileListReturnsEmptyForAnAbsentKey()
    {
        ModPackDocument doc = ModPackDocument.wrapping( new JsonObject() );
        assertTrue( doc.readFileList( ModPackDocument.FileList.MODS ).isEmpty() );
    }

    @Test
    void readFileListReturnsEmptyWhenTheValueIsNotAnArray()
    {
        ModPackDocument doc = ModPackDocument.fromJson( "{\"packMods\":\"not an array\"}" );
        assertTrue( doc.readFileList( ModPackDocument.FileList.MODS ).isEmpty() );
    }

    @Test
    void readFileListSkipsNonObjectElements()
    {
        ModPackDocument doc = ModPackDocument.fromJson(
                "{\"packMods\":[\"junk\",{\"remote\":\"r\",\"local\":\"l\"},7]}" );
        List< ModPackFileEntry > entries = doc.readFileList( ModPackDocument.FileList.MODS );
        assertEquals( 1, entries.size() );
        assertEquals( "r", entries.get( 0 ).getRemote() );
    }

    /**
     * sha256 outranks sha1, which outranks md5. The weaker hashes are kept as extras so a
     * save does not drop them.
     */
    @Test
    void sha256WinsAsThePrimaryHashAndTheOthersArePreserved()
    {
        ModPackDocument doc = ModPackDocument.fromJson(
                "{\"packMods\":[{\"remote\":\"r\",\"local\":\"l\"," +
                        "\"sha1\":\"aaa\",\"md5\":\"bbb\",\"sha256\":\"ccc\"}]}" );
        ModPackFileEntry entry = doc.readFileList( ModPackDocument.FileList.MODS ).get( 0 );
        assertEquals( "sha256", entry.getHashType() );
        assertEquals( "ccc", entry.getHash() );
        assertEquals( "aaa", entry.getExtraHash( "sha1" ) );
        assertEquals( "bbb", entry.getExtraHash( "md5" ) );
        assertNull( entry.getExtraHash( "sha256" ), "the primary hash is not also an extra" );
    }

    @Test
    void sha1WinsOverMd5WhenThereIsNoSha256()
    {
        ModPackDocument doc = ModPackDocument.fromJson(
                "{\"packMods\":[{\"remote\":\"r\",\"local\":\"l\",\"sha1\":\"aaa\",\"md5\":\"bbb\"}]}" );
        ModPackFileEntry entry = doc.readFileList( ModPackDocument.FileList.MODS ).get( 0 );
        assertEquals( "sha1", entry.getHashType() );
        assertEquals( "aaa", entry.getHash() );
        assertEquals( "bbb", entry.getExtraHash( "md5" ) );
    }

    @Test
    void md5IsUsedWhenItIsTheOnlyHash()
    {
        ModPackDocument doc = ModPackDocument.fromJson(
                "{\"packMods\":[{\"remote\":\"r\",\"local\":\"l\",\"md5\":\"bbb\"}]}" );
        ModPackFileEntry entry = doc.readFileList( ModPackDocument.FileList.MODS ).get( 0 );
        assertEquals( "md5", entry.getHashType() );
        assertEquals( "bbb", entry.getHash() );
    }

    /**
     * {@code "-1"} is the launcher's "no usable hash" sentinel, so a slot holding it must not
     * be mistaken for a real hash. Getting this wrong would make the launcher try to verify a
     * file against the literal string "-1" and fail every check.
     */
    @Test
    void theMinusOneSentinelDoesNotCountAsAHash()
    {
        ModPackDocument doc = ModPackDocument.fromJson(
                "{\"packMods\":[{\"remote\":\"r\",\"local\":\"l\"," +
                        "\"sha1\":\"-1\",\"md5\":\"-1\",\"sha256\":\"-1\"}]}" );
        ModPackFileEntry entry = doc.readFileList( ModPackDocument.FileList.MODS ).get( 0 );
        assertEquals( "", entry.getHash() );
        assertEquals( "sha1", entry.getHashType(), "the default type is kept when there is no hash" );
        assertTrue( entry.getExtraHashes().isEmpty() );
    }

    @Test
    void aBlankHashSlotDoesNotCountAsAHash()
    {
        ModPackDocument doc = ModPackDocument.fromJson(
                "{\"packMods\":[{\"remote\":\"r\",\"local\":\"l\",\"sha1\":\"   \",\"md5\":\"bbb\"}]}" );
        ModPackFileEntry entry = doc.readFileList( ModPackDocument.FileList.MODS ).get( 0 );
        assertEquals( "md5", entry.getHashType() );
        assertNull( entry.getExtraHash( "sha1" ) );
    }

    /** Requirement flags default to "required on both sides" when the manifest omits them. */
    @Test
    void absentRequirementFlagsDefaultToRequired()
    {
        ModPackDocument doc = ModPackDocument.fromJson(
                "{\"packMods\":[{\"remote\":\"r\",\"local\":\"l\"}]}" );
        ModPackFileEntry entry = doc.readFileList( ModPackDocument.FileList.MODS ).get( 0 );
        assertTrue( entry.isClientReq() );
        assertTrue( entry.isServerReq() );
    }

    @Test
    void presentRequirementFlagsAreHonoured()
    {
        ModPackDocument doc = ModPackDocument.fromJson(
                "{\"packMods\":[{\"remote\":\"r\",\"local\":\"l\"," +
                        "\"clientReq\":true,\"serverReq\":false}]}" );
        ModPackFileEntry entry = doc.readFileList( ModPackDocument.FileList.MODS ).get( 0 );
        assertTrue( entry.isClientReq() );
        assertFalse( entry.isServerReq() );
    }

    /**
     * Resource and shader packs are client-side by construction, so their entries read as
     * required on both sides no matter what the JSON says.
     */
    @Test
    void listsWithoutRequirementFlagsAlwaysReadAsRequired()
    {
        ModPackDocument doc = ModPackDocument.fromJson(
                "{\"packResourcePacks\":[{\"remote\":\"r\",\"local\":\"l\",\"serverReq\":false}]}" );
        ModPackFileEntry entry = doc.readFileList( ModPackDocument.FileList.RESOURCE_PACKS ).get( 0 );
        assertTrue( entry.isServerReq() );
    }

    /** Only the mods list carries names; a name on any other list is ignored on read. */
    @Test
    void namesAreIgnoredOnListsThatDoNotCarryThem()
    {
        ModPackDocument doc = ModPackDocument.fromJson(
                "{\"packConfigs\":[{\"name\":\"ignored\",\"remote\":\"r\",\"local\":\"l\"}]}" );
        ModPackFileEntry entry = doc.readFileList( ModPackDocument.FileList.CONFIGS ).get( 0 );
        assertEquals( "", entry.getName() );
    }

    @Test
    void theModrinthSlugIsReadWhenPresent()
    {
        ModPackDocument doc = ModPackDocument.fromJson(
                "{\"packMods\":[{\"remote\":\"r\",\"local\":\"l\",\"modrinthSlug\":\"jei\"}]}" );
        assertEquals( "jei", doc.readFileList( ModPackDocument.FileList.MODS ).get( 0 ).getModrinthSlug() );
    }

    // endregion

    // region file list writes

    @Test
    void writeFileListAlwaysEmitsAllThreeHashSlots()
    {
        ModPackDocument doc = ModPackDocument.blank();
        doc.writeFileList( ModPackDocument.FileList.MODS,
                           List.of( new ModPackFileEntry( "n", "r", "l", "abc", "sha1", true, true ) ) );
        JsonObject written = doc.json().getAsJsonArray( "packMods" ).get( 0 ).getAsJsonObject();
        assertEquals( "abc", written.get( "sha1" ).getAsString() );
        assertEquals( "-1", written.get( "md5" ).getAsString() );
        assertEquals( "-1", written.get( "sha256" ).getAsString() );
    }

    @Test
    void writeFileListOmitsTheNameOnListsThatDoNotCarryOne()
    {
        ModPackDocument doc = ModPackDocument.blank();
        doc.writeFileList( ModPackDocument.FileList.CONFIGS,
                           List.of( new ModPackFileEntry( "n", "r", "l", "", "sha1", true, true ) ) );
        JsonObject written = doc.json().getAsJsonArray( "packConfigs" ).get( 0 ).getAsJsonObject();
        assertFalse( written.has( "name" ) );
    }

    @Test
    void writeFileListOmitsRequirementFlagsOnListsThatDoNotCarryThem()
    {
        ModPackDocument doc = ModPackDocument.blank();
        doc.writeFileList( ModPackDocument.FileList.SHADER_PACKS,
                           List.of( new ModPackFileEntry( "", "r", "l", "", "sha1", true, false ) ) );
        JsonObject written = doc.json().getAsJsonArray( "packShaderPacks" ).get( 0 ).getAsJsonObject();
        assertFalse( written.has( "clientReq" ) );
        assertFalse( written.has( "serverReq" ) );
    }

    @Test
    void writeFileListOmitsAnEmptyModrinthSlug()
    {
        ModPackDocument doc = ModPackDocument.blank();
        doc.writeFileList( ModPackDocument.FileList.MODS,
                           List.of( new ModPackFileEntry( "n", "r", "l", "", "sha1", true, true ) ) );
        JsonObject written = doc.json().getAsJsonArray( "packMods" ).get( 0 ).getAsJsonObject();
        assertFalse( written.has( "modrinthSlug" ) );
    }

    @Test
    void writeFileListReplacesWhateverWasThere()
    {
        ModPackDocument doc = ModPackDocument.fromJson(
                "{\"packMods\":[{\"remote\":\"old\",\"local\":\"old\"}]}" );
        doc.writeFileList( ModPackDocument.FileList.MODS, new ArrayList<>() );
        assertEquals( 0, doc.json().getAsJsonArray( "packMods" ).size() );
    }

    @Test
    void writeFileListTreatsNullEntriesAsEmpty()
    {
        ModPackDocument doc = ModPackDocument.blank();
        doc.writeFileList( ModPackDocument.FileList.MODS, null );
        assertEquals( 0, doc.json().getAsJsonArray( "packMods" ).size() );
    }

    /**
     * The property that actually protects users: reading a manifest and writing it straight
     * back must not lose a hash. A manifest carrying both sha1 and sha256 has to come out the
     * other side of an edit cycle carrying both.
     */
    @Test
    void aReadWriteCycleDoesNotDropAnyHash()
    {
        ModPackDocument doc = ModPackDocument.fromJson(
                "{\"packMods\":[{\"name\":\"JEI\",\"remote\":\"r\",\"local\":\"l\"," +
                        "\"sha1\":\"aaa\",\"md5\":\"bbb\",\"sha256\":\"ccc\"," +
                        "\"clientReq\":true,\"serverReq\":false,\"modrinthSlug\":\"jei\"}]}" );

        doc.writeFileList( ModPackDocument.FileList.MODS,
                           doc.readFileList( ModPackDocument.FileList.MODS ) );

        JsonObject written = doc.json().getAsJsonArray( "packMods" ).get( 0 ).getAsJsonObject();
        assertEquals( "aaa", written.get( "sha1" ).getAsString() );
        assertEquals( "bbb", written.get( "md5" ).getAsString() );
        assertEquals( "ccc", written.get( "sha256" ).getAsString() );
        assertEquals( "JEI", written.get( "name" ).getAsString() );
        assertEquals( "jei", written.get( "modrinthSlug" ).getAsString() );
        assertFalse( written.get( "serverReq" ).getAsBoolean() );
    }

    /** The same property, held across a second cycle — the write shape must be a fixed point. */
    @Test
    void aSecondReadWriteCycleChangesNothingFurther()
    {
        ModPackDocument doc = ModPackDocument.fromJson(
                "{\"packMods\":[{\"name\":\"JEI\",\"remote\":\"r\",\"local\":\"l\"," +
                        "\"sha1\":\"aaa\",\"sha256\":\"ccc\"}]}" );

        doc.writeFileList( ModPackDocument.FileList.MODS, doc.readFileList( ModPackDocument.FileList.MODS ) );
        String afterFirst = doc.toPrettyJson();
        doc.writeFileList( ModPackDocument.FileList.MODS, doc.readFileList( ModPackDocument.FileList.MODS ) );

        assertEquals( afterFirst, doc.toPrettyJson() );
    }

    // endregion

    // region bumpVersion

    @Test
    void bumpingMajorResetsMinorAndPatch()
    {
        assertEquals( "2.0.0", ModPackDocument.bumpVersion( "1.4.7", 0 ) );
    }

    @Test
    void bumpingMinorResetsPatchOnly()
    {
        assertEquals( "1.5.0", ModPackDocument.bumpVersion( "1.4.7", 1 ) );
    }

    @Test
    void bumpingPatchLeavesTheRestAlone()
    {
        assertEquals( "1.4.8", ModPackDocument.bumpVersion( "1.4.7", 2 ) );
    }

    @Test
    void aNullVersionIsTreatedAsZeroZeroZero()
    {
        assertEquals( "0.1.0", ModPackDocument.bumpVersion( null, 1 ) );
    }

    @Test
    void aBlankVersionIsTreatedAsZeroZeroZero()
    {
        assertEquals( "1.0.0", ModPackDocument.bumpVersion( "   ", 0 ) );
    }

    @Test
    void aNonNumericSegmentIsTreatedAsZero()
    {
        assertEquals( "1.0.0", ModPackDocument.bumpVersion( "0.x.9", 0 ) );
    }

    @Test
    void aShortVersionIsPaddedToThreeSegments()
    {
        assertEquals( "2.0.0", ModPackDocument.bumpVersion( "1", 0 ) );
    }

    /**
     * A four-segment version is bumped in place but rendered back with only three segments,
     * so the fourth is dropped. Long-standing behaviour, pinned because it is lossy.
     */
    @Test
    void aFourSegmentVersionLosesItsFourthSegment()
    {
        assertEquals( "1.5.0", ModPackDocument.bumpVersion( "1.4.7.3", 1 ) );
    }

    @Test
    void aPositionPastTheEndLeavesTheVersionUnchanged()
    {
        assertEquals( "1.4.7", ModPackDocument.bumpVersion( "1.4.7", 9 ) );
    }

    @Test
    void aNegativePositionLeavesTheVersionUnchanged()
    {
        assertEquals( "1.4.7", ModPackDocument.bumpVersion( "1.4.7", -1 ) );
    }

    // endregion

    // region firstLine / sanitizedFileBaseName

    @Test
    void firstLineReturnsTheFirstNonBlankLineTrimmed()
    {
        assertEquals( "second", ModPackDocument.firstLine( "   \n  second  \nthird" ) );
    }

    @Test
    void firstLineReturnsEmptyForNullOrBlank()
    {
        assertEquals( "", ModPackDocument.firstLine( null ) );
        assertEquals( "", ModPackDocument.firstLine( "  \n \n " ) );
    }

    @Test
    void sanitizedFileBaseNameStripsEverythingOutsideLettersAndDigits()
    {
        ModPackDocument doc = ModPackDocument.blank();
        doc.putString( ModPackDocument.KEY_PACK_NAME, "My Pack v2.1 (beta)!" );
        assertEquals( "MyPackv21beta", doc.sanitizedFileBaseName() );
    }

    /**
     * A name made entirely of stripped characters sanitizes to the empty string. The save
     * dialog then opens with no filename rather than a bad one, which is the safe outcome —
     * but it is worth knowing the method can return empty for non-empty input.
     */
    @Test
    void aNameOfOnlyPunctuationSanitizesToEmpty()
    {
        ModPackDocument doc = ModPackDocument.blank();
        doc.putString( ModPackDocument.KEY_PACK_NAME, "!!! ---" );
        assertEquals( "", doc.sanitizedFileBaseName() );
    }

    // endregion

    // region validate

    @Test
    void aMissingNameAndVersionAreBothReported()
    {
        ModPackDocument doc = ModPackDocument.wrapping( new JsonObject() );
        List< String > issues = doc.validate( ECHO );
        assertTrue( issues.contains( "editor.validate.required(editor.validate.label.packName)" ), "" + issues );
        assertTrue( issues.contains( "editor.validate.required(editor.validate.label.packVersion)" ), "" + issues );
    }

    @Test
    void aBlankNameIsReportedAsMissing()
    {
        ModPackDocument doc = ModPackDocument.fromJson( "{\"packName\":\"   \",\"packVersion\":\"1.0.0\"}" );
        assertTrue( doc.validate( ECHO )
                            .contains( "editor.validate.required(editor.validate.label.packName)" ) );
    }

    @Test
    void anUnparseableMinimumRamIsReported()
    {
        ModPackDocument doc = ModPackDocument.blank();
        doc.putString( ModPackDocument.KEY_PACK_NAME, "Pack" );
        doc.putString( ModPackDocument.KEY_PACK_MIN_RAM_GB, "lots" );
        assertTrue( doc.validate( ECHO ).contains( "editor.validate.invalidMinRam" ) );
    }

    @Test
    void aFractionalMinimumRamIsAccepted()
    {
        ModPackDocument doc = ModPackDocument.blank();
        doc.putString( ModPackDocument.KEY_PACK_NAME, "Pack" );
        doc.putString( ModPackDocument.KEY_PACK_MIN_RAM_GB, "1.5" );
        assertFalse( doc.validate( ECHO ).contains( "editor.validate.invalidMinRam" ) );
    }

    @Test
    void anAbsentMinimumRamIsNotReported()
    {
        ModPackDocument doc = ModPackDocument.fromJson( "{\"packName\":\"P\",\"packVersion\":\"1\"}" );
        assertFalse( doc.validate( ECHO ).contains( "editor.validate.invalidMinRam" ) );
    }

    @Test
    void anEntryWithNoRemoteOrLocalIsReportedTwice()
    {
        ModPackDocument doc = ModPackDocument.fromJson(
                "{\"packName\":\"P\",\"packVersion\":\"1\"," +
                        "\"packMods\":[{\"name\":\"JEI\",\"remote\":\"\",\"local\":\"\"}]}" );
        List< String > issues = doc.validate( ECHO );
        assertTrue( issues.contains( "editor.validate.remoteEmpty(editor.validate.label.mods \"JEI\")" ),
                    "" + issues );
        assertTrue( issues.contains( "editor.validate.localEmpty(editor.validate.label.mods \"JEI\")" ),
                    "" + issues );
    }

    /** An unnamed entry is identified by its 1-based position instead. */
    @Test
    void anUnnamedEntryIsLabelledByItsPosition()
    {
        ModPackDocument doc = ModPackDocument.fromJson(
                "{\"packName\":\"P\",\"packVersion\":\"1\"," +
                        "\"packConfigs\":[{\"remote\":\"r\",\"local\":\"l\"},{\"remote\":\"\",\"local\":\"l\"}]}" );
        assertTrue( doc.validate( ECHO )
                            .contains( "editor.validate.remoteEmpty(editor.validate.label.configs #2)" ) );
    }

    @Test
    void aFullyPopulatedManifestValidatesClean()
    {
        ModPackDocument doc = ModPackDocument.blank();
        doc.putString( ModPackDocument.KEY_PACK_NAME, "Complete Pack" );
        doc.writeFileList( ModPackDocument.FileList.MODS,
                           List.of( new ModPackFileEntry( "JEI", "https://example.test/jei.jar",
                                                          "mods/jei.jar", "abc", "sha1", true, true ) ) );
        assertEquals( List.of(), doc.validate( ECHO ) );
    }

    /**
     * Every issue is a standalone message. The editor used to concatenate them into one
     * buffer and forgot the newline after the min-RAM message, so it ran into the next issue;
     * returning a list makes that structurally impossible.
     */
    @Test
    void eachIssueIsASeparateMessageWithNoEmbeddedNewlines()
    {
        ModPackDocument doc = ModPackDocument.wrapping( new JsonObject() );
        doc.putString( ModPackDocument.KEY_PACK_MIN_RAM_GB, "nope" );
        doc.writeFileList( ModPackDocument.FileList.MODS,
                           List.of( new ModPackFileEntry( "", "", "", "", "sha1", true, true ) ) );
        List< String > issues = doc.validate( ECHO );
        assertTrue( issues.size() >= 4, "" + issues );
        for ( String issue : issues ) {
            assertFalse( issue.contains( "\n" ), "issue should not embed a newline: " + issue );
        }
    }

    /**
     * {@code checkRequired} reads the raw element, so a JSON-null name throws rather than
     * being reported as missing. Pinned as-is: it is the editor's existing behaviour, and a
     * manifest with a null name is malformed rather than merely incomplete.
     */
    @Test
    void aJsonNullNameThrowsRatherThanValidating()
    {
        ModPackDocument doc = ModPackDocument.fromJson( "{\"packName\":null}" );
        assertThrows( UnsupportedOperationException.class, () -> doc.validate( ECHO ) );
    }

    // endregion

    // region FileList shape

    /**
     * Pins the per-list entry shape the editor depends on. These flags decide whether a name
     * and requirement flags are read and written, so a change here silently alters what lands
     * in users' manifests.
     */
    @Test
    void fileListShapesMatchTheManifestFormat()
    {
        assertEquals( "packMods", ModPackDocument.FileList.MODS.getKey() );
        assertTrue( ModPackDocument.FileList.MODS.hasName() );
        assertTrue( ModPackDocument.FileList.MODS.hasClientServerReq() );

        assertFalse( ModPackDocument.FileList.CONFIGS.hasName() );
        assertTrue( ModPackDocument.FileList.CONFIGS.hasClientServerReq() );

        assertFalse( ModPackDocument.FileList.RESOURCE_PACKS.hasClientServerReq() );
        assertFalse( ModPackDocument.FileList.SHADER_PACKS.hasClientServerReq() );

        assertFalse( ModPackDocument.FileList.INITIAL_FILES.hasName() );
        assertTrue( ModPackDocument.FileList.INITIAL_FILES.hasClientServerReq() );
    }

    @Test
    void everyFileListHasALabelKey()
    {
        for ( ModPackDocument.FileList list : ModPackDocument.FileList.values() ) {
            assertNotNull( list.getLabelKey() );
            assertTrue( list.getLabelKey().startsWith( "editor.validate.label." ), list.getLabelKey() );
        }
    }

    // endregion
}
