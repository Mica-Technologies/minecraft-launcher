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
import com.micatechnologies.minecraft.launcher.consts.ModPackConstants;
import com.micatechnologies.minecraft.launcher.utilities.JSONUtilities;

import java.util.ArrayList;
import java.util.List;

/**
 * A headless, editable modpack manifest document.
 * <p>
 * This is the domain model behind the modpack editor: it owns the manifest {@link JsonObject} and
 * every rule for reading and writing it — default values for a new pack, the {@code string |
 * string[]} duality of the image-URL fields, the three-slot hash model of the file lists, version
 * bumping, and validation. It deliberately has no JavaFX dependency, so manifest authoring is
 * reachable from unit tests and from headless callers, not only from the editor screen.
 * <p>
 * The editor GUI is a view over this class. Where the editor previously read and wrote
 * {@code workingDocument} directly through a dozen private helpers, it now delegates here, which is
 * what makes the manifest rules testable for the first time.
 *
 * @author Mica Technologies
 * @version 1.0
 * @since 3.0
 */
public class ModPackDocument
{
    /**
     * The {@code manifestFormat} version stamped onto documents created by {@link #blank()}.
     * Format 2 added the optional per-entry {@code modrinthSlug} field.
     */
    public static final int CURRENT_MANIFEST_FORMAT = 2;

    /** Manifest key holding the pack's display name. */
    public static final String KEY_PACK_NAME = "packName";

    /** Manifest key holding the pack's version string. */
    public static final String KEY_PACK_VERSION = "packVersion";

    /** Manifest key holding the pack's minimum RAM allocation, in gigabytes, stored as a string. */
    public static final String KEY_PACK_MIN_RAM_GB = "packMinRAMGB";

    /** Manifest key holding the pack's scan-exclusion path list. */
    public static final String KEY_SCAN_EXCLUSIONS = "packScanExclusions";

    /**
     * One of the manifest's file lists, together with the shape of its entries.
     * <p>
     * The editor previously repeated the {@code (key, hasName, hasClientServerReq)} triple at four
     * separate call sites that had to stay in agreement by hand. Encoding it once removes that
     * class of drift.
     *
     * @since 3.0
     */
    public enum FileList
    {
        /** Mod files. The only list whose entries carry a display name. */
        MODS( "packMods", true, true, "editor.validate.label.mods" ),

        /** Configuration files. */
        CONFIGS( "packConfigs", false, true, "editor.validate.label.configs" ),

        /** Resource packs. Installed on clients only, so they carry no requirement flags. */
        RESOURCE_PACKS( "packResourcePacks", false, false, "editor.validate.label.resourcePacks" ),

        /** Shader packs. Installed on clients only, so they carry no requirement flags. */
        SHADER_PACKS( "packShaderPacks", false, false, "editor.validate.label.shaderPacks" ),

        /** Files written once at install time and never re-synced afterwards. */
        INITIAL_FILES( "packInitialFiles", false, true, "editor.validate.label.initialFiles" );

        /** The manifest key this list is stored under. */
        private final String key;

        /** Whether entries in this list carry a {@code name} field. */
        private final boolean hasName;

        /** Whether entries in this list carry {@code clientReq}/{@code serverReq} flags. */
        private final boolean hasClientServerReq;

        /** Localization key for the human-readable label naming this list in validation messages. */
        private final String labelKey;

        /**
         * Constructs a file-list descriptor.
         *
         * @param key                the manifest key
         * @param hasName            whether entries carry a name
         * @param hasClientServerReq whether entries carry requirement flags
         * @param labelKey           localization key for the list's display label
         */
        FileList( String key, boolean hasName, boolean hasClientServerReq, String labelKey )
        {
            this.key = key;
            this.hasName = hasName;
            this.hasClientServerReq = hasClientServerReq;
            this.labelKey = labelKey;
        }

        /**
         * Returns the manifest key this list is stored under.
         *
         * @return the manifest key, e.g. {@code "packMods"}
         *
         * @since 3.0
         */
        public String getKey() { return key; }

        /**
         * Reports whether entries in this list carry a {@code name} field.
         *
         * @return {@code true} for {@link #MODS}, {@code false} otherwise
         *
         * @since 3.0
         */
        public boolean hasName() { return hasName; }

        /**
         * Reports whether entries in this list carry {@code clientReq}/{@code serverReq} flags.
         *
         * @return {@code true} when the list is side-aware
         *
         * @since 3.0
         */
        public boolean hasClientServerReq() { return hasClientServerReq; }

        /**
         * Returns the localization key for this list's display label.
         *
         * @return the label's localization key
         *
         * @since 3.0
         */
        public String getLabelKey() { return labelKey; }
    }

    /**
     * Resolves a localization key and its arguments to display text.
     * <p>
     * {@link ModPackDocument#validate} takes one of these rather than calling
     * {@code LocalizationManager} directly. Production code passes the real localizer; tests pass an
     * echoing resolver, which keeps the assertions locale-independent — the same reason
     * {@link PlayTimeFormatting} returns keys instead of text.
     *
     * @since 3.0
     */
    @FunctionalInterface
    public interface MessageResolver
    {
        /**
         * Resolves one message.
         *
         * @param key  the localization key
         * @param args MessageFormat arguments, possibly empty
         *
         * @return the resolved display text
         *
         * @since 3.0
         */
        String resolve( String key, Object... args );
    }

    /** The manifest being edited. Never {@code null}. */
    private final JsonObject document;

    /**
     * Constructs a document wrapping the given manifest object.
     *
     * @param document the manifest to wrap
     */
    private ModPackDocument( JsonObject document )
    {
        this.document = document;
    }

    /**
     * Creates a new document populated with the defaults for a blank modpack: an empty name,
     * version {@code 1.0.0}, a 2 GB minimum RAM allocation, Forge as the mod loader, and an empty
     * array for every file list.
     * <p>
     * The mod loader defaults to Forge so that existing "Pick Forge Version" muscle memory keeps
     * working out of the box.
     *
     * @return a new blank document
     *
     * @since 3.0
     */
    public static ModPackDocument blank()
    {
        JsonObject doc = new JsonObject();
        doc.addProperty( "manifestFormat", CURRENT_MANIFEST_FORMAT );
        doc.addProperty( KEY_PACK_NAME, "" );
        doc.addProperty( KEY_PACK_VERSION, "1.0.0" );
        doc.addProperty( "packURL", "" );
        doc.addProperty( "packUnstable", false );
        doc.addProperty( "packCustomDiscordRpc", false );
        doc.addProperty( KEY_PACK_MIN_RAM_GB, "2" );
        doc.addProperty( "packLogoURL", "" );
        doc.addProperty( "packLogoSha1", "" );
        doc.addProperty( "packBackgroundURL", "" );
        doc.addProperty( "packBackgroundSha1", "" );
        doc.addProperty( "packForgeURL", "" );
        doc.addProperty( "packForgeHash", "" );
        doc.addProperty( "packModLoader", ModPackConstants.MOD_LOADER_FORGE );
        doc.addProperty( "packModLoaderURL", "" );
        doc.addProperty( "packModLoaderHash", "" );
        doc.addProperty( "packMinecraftVersion", "" );
        doc.add( KEY_SCAN_EXCLUSIONS, new JsonArray() );
        for ( FileList list : FileList.values() ) {
            doc.add( list.getKey(), new JsonArray() );
        }
        return new ModPackDocument( doc );
    }

    /**
     * Parses a manifest from JSON text.
     *
     * @param json the manifest JSON
     *
     * @return a document wrapping the parsed manifest
     *
     * @throws com.google.gson.JsonSyntaxException if {@code json} is not valid JSON
     * @throws IllegalArgumentException            if {@code json} does not parse to a JSON object
     * @since 3.0
     */
    public static ModPackDocument fromJson( String json )
    {
        JsonObject parsed = JSONUtilities.getGson().fromJson( json, JsonObject.class );
        if ( parsed == null ) {
            throw new IllegalArgumentException( "Modpack manifest JSON did not parse to an object" );
        }
        return new ModPackDocument( parsed );
    }

    /**
     * Wraps an already-parsed manifest object without copying it. Mutations through this document
     * are visible through the passed object and vice versa.
     *
     * @param parsed the manifest object to adopt
     *
     * @return a document wrapping {@code parsed}
     *
     * @throws IllegalArgumentException if {@code parsed} is {@code null}
     * @since 3.0
     */
    public static ModPackDocument wrapping( JsonObject parsed )
    {
        if ( parsed == null ) {
            throw new IllegalArgumentException( "Cannot wrap a null modpack manifest" );
        }
        return new ModPackDocument( parsed );
    }

    /**
     * Returns the underlying manifest object. Exposed for callers that need to read or write keys
     * this class does not model; prefer the typed accessors where one exists.
     *
     * @return the live manifest object, never {@code null}
     *
     * @since 3.0
     */
    public JsonObject json()
    {
        return document;
    }

    /**
     * Reads a scalar manifest value as a string.
     *
     * @param key the manifest key to read
     *
     * @return the value as a string, or {@code ""} when absent or JSON-null
     *
     * @since 3.0
     */
    public String getString( String key )
    {
        if ( document.has( key ) && !document.get( key ).isJsonNull() ) {
            return document.get( key ).getAsString();
        }
        return "";
    }

    /**
     * Writes a scalar string manifest value.
     *
     * @param key   the manifest key to write
     * @param value the value to store
     *
     * @since 3.0
     */
    public void putString( String key, String value )
    {
        document.addProperty( key, value );
    }

    /**
     * Reads a scalar manifest value as a boolean.
     *
     * @param key the manifest key to read
     *
     * @return the value as a boolean, or {@code false} when absent or JSON-null
     *
     * @since 3.0
     */
    public boolean getBool( String key )
    {
        if ( document.has( key ) && !document.get( key ).isJsonNull() ) {
            return document.get( key ).getAsBoolean();
        }
        return false;
    }

    /**
     * Writes a scalar boolean manifest value.
     *
     * @param key   the manifest key to write
     * @param value the value to store
     *
     * @since 3.0
     */
    public void putBool( String key, boolean value )
    {
        document.addProperty( key, value );
    }

    /**
     * Reads a {@code string | string[]} manifest value (such as {@code packLogoURL}) as
     * newline-joined lines: a single string becomes one line, an array one line per element, and an
     * absent or JSON-null value the empty string.
     * <p>
     * The inverse of {@link #putStringOrArray}.
     *
     * @param key the manifest key to read
     *
     * @return the value's lines joined by {@code \n}, or {@code ""} when absent
     *
     * @since 3.0
     */
    public String getStringOrArrayLines( String key )
    {
        if ( !document.has( key ) || document.get( key ).isJsonNull() ) {
            return "";
        }
        JsonElement el = document.get( key );
        if ( el.isJsonArray() ) {
            StringBuilder sb = new StringBuilder();
            for ( JsonElement item : el.getAsJsonArray() ) {
                if ( item != null && !item.isJsonNull() ) {
                    if ( sb.length() > 0 ) {
                        sb.append( "\n" );
                    }
                    sb.append( item.getAsString() );
                }
            }
            return sb.toString();
        }
        return el.getAsString();
    }

    /**
     * Writes a multi-line value back as a {@code string | string[]} manifest value: one non-blank
     * line becomes a bare string, several become a JSON array in order, and none becomes the empty
     * string. Blank lines are dropped and each retained line is trimmed.
     * <p>
     * The inverse of {@link #getStringOrArrayLines}.
     *
     * @param key           the manifest key to write
     * @param multilineText the newline-separated text to store; {@code null} is treated as empty
     *
     * @since 3.0
     */
    public void putStringOrArray( String key, String multilineText )
    {
        List< String > lines = splitNonBlankLines( multilineText );
        if ( lines.isEmpty() ) {
            document.addProperty( key, "" );
        }
        else if ( lines.size() == 1 ) {
            document.addProperty( key, lines.get( 0 ) );
        }
        else {
            JsonArray arr = new JsonArray();
            for ( String line : lines ) {
                arr.add( line );
            }
            document.add( key, arr );
        }
    }

    /**
     * Reads an always-array manifest value (such as {@code packScanExclusions}) as newline-joined
     * lines. Unlike {@link #getStringOrArrayLines}, a value that is present but not an array reads
     * as empty rather than as a single line.
     *
     * @param key the manifest key to read
     *
     * @return the array's elements joined by {@code \n}, or {@code ""} when absent or not an array
     *
     * @since 3.0
     */
    public String getArrayLines( String key )
    {
        if ( !document.has( key ) || !document.get( key ).isJsonArray() ) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for ( JsonElement el : document.getAsJsonArray( key ) ) {
            if ( sb.length() > 0 ) {
                sb.append( "\n" );
            }
            sb.append( el.getAsString() );
        }
        return sb.toString();
    }

    /**
     * Writes a multi-line value back as an always-array manifest value. Blank lines are dropped and
     * each retained line is trimmed; empty input stores an empty array rather than an empty string.
     *
     * @param key           the manifest key to write
     * @param multilineText the newline-separated text to store; {@code null} is treated as empty
     *
     * @since 3.0
     */
    public void putArrayLines( String key, String multilineText )
    {
        JsonArray arr = new JsonArray();
        for ( String line : splitNonBlankLines( multilineText ) ) {
            arr.add( line );
        }
        document.add( key, arr );
    }

    /**
     * Reads one of the manifest's file lists into headless entries.
     * <p>
     * Each entry's strongest available hash becomes its primary hash, in the precedence order
     * {@code sha256} then {@code sha1} then {@code md5}; the remaining populated slots are stashed
     * as extra hashes so a save round-trips them instead of dropping them. A hash slot counts as
     * populated only when it holds a non-blank string other than the {@code "-1"} sentinel, matching
     * {@code ManagedGameFile}'s "is this a real hash" rule. Non-object array elements are skipped.
     * <p>
     * A missing key, a key whose value is not an array, and an empty array all read as no entries.
     *
     * @param list the file list to read
     *
     * @return the list's entries, in manifest order; empty when the list is absent
     *
     * @since 3.0
     */
    public List< ModPackFileEntry > readFileList( FileList list )
    {
        List< ModPackFileEntry > entries = new ArrayList<>();
        if ( !document.has( list.getKey() ) || !document.get( list.getKey() ).isJsonArray() ) {
            return entries;
        }

        for ( JsonElement el : document.getAsJsonArray( list.getKey() ) ) {
            if ( !el.isJsonObject() ) {
                continue;
            }
            JsonObject obj = el.getAsJsonObject();
            String name = list.hasName() && obj.has( "name" ) ? obj.get( "name" ).getAsString() : "";
            String remote = obj.has( "remote" ) ? obj.get( "remote" ).getAsString() : "";
            String local = obj.has( "local" ) ? obj.get( "local" ).getAsString() : "";

            String sha1Val = readHashValue( obj, "sha1" );
            String md5Val = readHashValue( obj, "md5" );
            String sha256Val = readHashValue( obj, "sha256" );

            String hash = "";
            String hashType = "sha1";
            if ( sha256Val != null ) {
                hash = sha256Val;
                hashType = "sha256";
            }
            else if ( sha1Val != null ) {
                hash = sha1Val;
                hashType = "sha1";
            }
            else if ( md5Val != null ) {
                hash = md5Val;
                hashType = "md5";
            }

            boolean clientReq = !list.hasClientServerReq() || !obj.has( "clientReq" ) ||
                    obj.get( "clientReq" ).getAsBoolean();
            boolean serverReq = !list.hasClientServerReq() || !obj.has( "serverReq" ) ||
                    obj.get( "serverReq" ).getAsBoolean();

            ModPackFileEntry entry = new ModPackFileEntry( name, remote, local, hash, hashType,
                                                           clientReq, serverReq );
            if ( !hashType.equals( "sha1" ) && sha1Val != null ) {
                entry.putExtraHash( "sha1", sha1Val );
            }
            if ( !hashType.equals( "md5" ) && md5Val != null ) {
                entry.putExtraHash( "md5", md5Val );
            }
            if ( !hashType.equals( "sha256" ) && sha256Val != null ) {
                entry.putExtraHash( "sha256", sha256Val );
            }
            if ( obj.has( "modrinthSlug" ) ) {
                entry.setModrinthSlug( obj.get( "modrinthSlug" ).getAsString() );
            }
            entries.add( entry );
        }
        return entries;
    }

    /**
     * Writes entries back into one of the manifest's file lists, replacing whatever was there.
     * <p>
     * All three hash slots are always emitted: the primary hash goes into the slot matching its
     * type and the other two are filled from the entry's extra hashes, so a manifest carrying both
     * a sha1 and a sha256 keeps both across an edit cycle. A slot with no value gets the
     * {@code "-1"} sentinel that {@code ManagedGameFile} treats as "no hash". Name, requirement
     * flags, and the Modrinth slug are emitted only where they apply.
     *
     * @param list    the file list to write
     * @param entries the entries to store, in order
     *
     * @since 3.0
     */
    public void writeFileList( FileList list, List< ModPackFileEntry > entries )
    {
        JsonArray array = new JsonArray();
        if ( entries != null ) {
            for ( ModPackFileEntry entry : entries ) {
                JsonObject obj = new JsonObject();
                if ( list.hasName() ) {
                    obj.addProperty( "name", entry.getName() );
                }
                obj.addProperty( "remote", entry.getRemote() );
                obj.addProperty( "local", entry.getLocal() );

                String ht = entry.getHashType();
                String hv = entry.getHash();
                String sha1Out = "md5".equalsIgnoreCase( ht ) || "sha256".equalsIgnoreCase( ht )
                                 ? entry.getExtraHash( "sha1" )
                                 : ( hv.isEmpty() ? null : hv );
                String md5Out = "md5".equalsIgnoreCase( ht )
                                ? ( hv.isEmpty() ? null : hv )
                                : entry.getExtraHash( "md5" );
                String sha256Out = "sha256".equalsIgnoreCase( ht )
                                   ? ( hv.isEmpty() ? null : hv )
                                   : entry.getExtraHash( "sha256" );
                obj.addProperty( "sha1", sha1Out == null || sha1Out.isBlank() ? "-1" : sha1Out );
                obj.addProperty( "md5", md5Out == null || md5Out.isBlank() ? "-1" : md5Out );
                obj.addProperty( "sha256", sha256Out == null || sha256Out.isBlank() ? "-1" : sha256Out );

                if ( list.hasClientServerReq() ) {
                    obj.addProperty( "clientReq", entry.isClientReq() );
                    obj.addProperty( "serverReq", entry.isServerReq() );
                }
                if ( !entry.getModrinthSlug().isEmpty() ) {
                    obj.addProperty( "modrinthSlug", entry.getModrinthSlug() );
                }
                array.add( obj );
            }
        }
        document.add( list.getKey(), array );
    }

    /**
     * Serializes the manifest to pretty-printed JSON.
     *
     * @return the manifest as pretty-printed JSON
     *
     * @since 3.0
     */
    public String toPrettyJson()
    {
        return JSONUtilities.getPrettyGson().toJson( document );
    }

    /**
     * Returns a filename-safe base name derived from the pack name, with every character outside
     * {@code [a-zA-Z0-9]} removed. Used to seed the save dialog's initial filename.
     *
     * @return the sanitized base name; {@code ""} when the pack has no name, or when the name
     *         consists entirely of removed characters
     *
     * @since 3.0
     */
    public String sanitizedFileBaseName()
    {
        return getString( KEY_PACK_NAME ).replaceAll( "[^a-zA-Z0-9]", "" );
    }

    /**
     * Validates the manifest, returning one message per problem found.
     * <p>
     * Checks, in order: that {@code packName} and {@code packVersion} are present and non-blank;
     * that {@code packMinRAMGB} parses as a number when present; that every file-list entry has a
     * non-blank remote URL and local path; and that the manifest still deserializes into a
     * {@link GameModPack}.
     *
     * @param resolver resolves localization keys to display text
     *
     * @return the validation messages, in the order listed above; empty when the manifest is valid
     *
     * @throws UnsupportedOperationException if {@code packName} or {@code packVersion} is present
     *                                       but JSON-null, or is a JSON array or object — the same
     *                                       behaviour the editor has always had
     * @since 3.0
     */
    public List< String > validate( MessageResolver resolver )
    {
        List< String > issues = new ArrayList<>();

        checkRequired( issues, resolver, KEY_PACK_NAME, "editor.validate.label.packName" );
        checkRequired( issues, resolver, KEY_PACK_VERSION, "editor.validate.label.packVersion" );

        if ( document.has( KEY_PACK_MIN_RAM_GB ) ) {
            try {
                Double.parseDouble( document.get( KEY_PACK_MIN_RAM_GB ).getAsString() );
            }
            catch ( NumberFormatException e ) {
                issues.add( resolver.resolve( "editor.validate.invalidMinRam" ) );
            }
        }

        for ( FileList list : FileList.values() ) {
            validateFileEntries( issues, resolver, list );
        }

        try {
            JSONUtilities.getGson().fromJson( toPrettyJson(), GameModPack.class );
        }
        catch ( Exception e ) {
            issues.add( resolver.resolve( "editor.validate.roundTripFailed", e.getMessage() ) );
        }

        return issues;
    }

    /**
     * Bumps one segment of a dotted version string, incrementing it and resetting every segment to
     * its right to zero. A blank or {@code null} version is treated as {@code "0.0.0"}; a segment
     * that does not parse as an integer is treated as zero. The result always has exactly three
     * segments, regardless of how many the input had.
     *
     * @param current  the version string to bump
     * @param position the segment to increment — {@code 0} major, {@code 1} minor, {@code 2} patch
     *
     * @return the bumped version string
     *
     * @since 3.0
     */
    public static String bumpVersion( String current, int position )
    {
        if ( current == null || current.isBlank() ) {
            current = "0.0.0";
        }

        String[] parts = current.split( "\\." );
        int[] segments = new int[ Math.max( 3, parts.length ) ];
        for ( int i = 0; i < parts.length; i++ ) {
            try {
                segments[ i ] = Integer.parseInt( parts[ i ] );
            }
            catch ( NumberFormatException ignored ) {
                segments[ i ] = 0;
            }
        }

        if ( position >= 0 && position < segments.length ) {
            segments[ position ]++;
            for ( int i = position + 1; i < segments.length; i++ ) {
                segments[ i ] = 0;
            }
        }

        return segments[ 0 ] + "." + segments[ 1 ] + "." + segments[ 2 ];
    }

    /**
     * Returns the first non-blank line of a multi-line value — the "primary" of a
     * {@code string | string[]} field.
     *
     * @param text the multi-line text to inspect
     *
     * @return the first non-blank line, trimmed, or {@code ""} when there is none
     *
     * @since 3.0
     */
    public static String firstLine( String text )
    {
        if ( text == null ) {
            return "";
        }
        for ( String line : text.split( "\n" ) ) {
            String trimmed = line.trim();
            if ( !trimmed.isEmpty() ) {
                return trimmed;
            }
        }
        return "";
    }

    /**
     * Splits multi-line text into trimmed, non-blank lines.
     *
     * @param multilineText the text to split; {@code null} yields an empty list
     *
     * @return the trimmed non-blank lines, in order
     */
    private static List< String > splitNonBlankLines( String multilineText )
    {
        List< String > lines = new ArrayList<>();
        if ( multilineText != null ) {
            for ( String line : multilineText.split( "\n" ) ) {
                String trimmed = line.trim();
                if ( !trimmed.isEmpty() ) {
                    lines.add( trimmed );
                }
            }
        }
        return lines;
    }

    /**
     * Returns the value of {@code obj.get(key)} when it is a non-blank string other than the
     * {@code "-1"} sentinel, and {@code null} otherwise. Mirrors {@code ManagedGameFile}'s
     * "is this a real hash" semantics.
     *
     * @param obj the entry object to read
     * @param key the hash slot to read
     *
     * @return the hash value, or {@code null} when the slot holds no usable hash
     */
    private static String readHashValue( JsonObject obj, String key )
    {
        if ( !obj.has( key ) ) {
            return null;
        }
        try {
            String v = obj.get( key ).getAsString();
            if ( v == null || v.isBlank() || v.equals( "-1" ) ) {
                return null;
            }
            return v;
        }
        catch ( Exception e ) {
            return null;
        }
    }

    /**
     * Appends a message when a required scalar field is absent or blank.
     *
     * @param issues   accumulator the message is appended to
     * @param resolver resolves localization keys to display text
     * @param key      the manifest key that must be present and non-blank
     * @param labelKey localization key for the label naming the field in the message
     */
    private void checkRequired( List< String > issues, MessageResolver resolver, String key, String labelKey )
    {
        if ( !document.has( key ) || document.get( key ).getAsString().isBlank() ) {
            issues.add( resolver.resolve( "editor.validate.required", resolver.resolve( labelKey ) ) );
        }
    }

    /**
     * Appends one message per empty required field across a file list's entries. Entries are named
     * by their display name where they have one, and by their 1-based position otherwise.
     *
     * @param issues   accumulator the messages are appended to
     * @param resolver resolves localization keys to display text
     * @param list     the file list to validate
     */
    private void validateFileEntries( List< String > issues, MessageResolver resolver, FileList list )
    {
        String label = resolver.resolve( list.getLabelKey() );
        int idx = 0;
        for ( ModPackFileEntry entry : readFileList( list ) ) {
            idx++;
            String entryLabel = label + " #" + idx;
            if ( list.hasName() && !entry.getName().isBlank() ) {
                entryLabel = label + " \"" + entry.getName() + "\"";
            }
            if ( entry.getRemote().isBlank() ) {
                issues.add( resolver.resolve( "editor.validate.remoteEmpty", entryLabel ) );
            }
            if ( entry.getLocal().isBlank() ) {
                issues.add( resolver.resolve( "editor.validate.localEmpty", entryLabel ) );
            }
        }
    }
}
