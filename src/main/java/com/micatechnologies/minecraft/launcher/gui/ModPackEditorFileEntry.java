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

package com.micatechnologies.minecraft.launcher.gui;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

/**
 * Mutable JavaFX bean representing a single file entry (mod, config, resource pack, shader pack, or initial file) in
 * the modpack editor. Uses JavaFX properties for direct TableView cell binding.
 *
 * @author Mica Technologies
 * @version 1.0
 * @since 3.0
 */
public class ModPackEditorFileEntry
{
    /**
     * Display name of the file entry as shown in the editor's table. Defaults to the empty string.
     *
     * @since 3.0
     */
    private final StringProperty name = new SimpleStringProperty( "" );

    /**
     * Remote source URL the file is downloaded from. Defaults to the empty string.
     *
     * @since 3.0
     */
    private final StringProperty remote = new SimpleStringProperty( "" );

    /**
     * Local installation path of the file, relative to the modpack root. Defaults to the empty string.
     *
     * @since 3.0
     */
    private final StringProperty local = new SimpleStringProperty( "" );

    /**
     * Primary hash value used to verify the downloaded file, expressed in the algorithm named by
     * {@link #hashType}. Defaults to the empty string.
     *
     * @since 3.0
     */
    private final StringProperty hash = new SimpleStringProperty( "" );

    /**
     * Algorithm name for the primary {@link #hash} value (e.g. {@code "sha1"}, {@code "md5"},
     * {@code "sha256"}). Defaults to {@code "sha1"}.
     *
     * @since 3.0
     */
    private final StringProperty hashType = new SimpleStringProperty( "sha1" );

    /**
     * Whether this file is required on the client side of the modpack. Defaults to {@code true}.
     *
     * @since 3.0
     */
    private final BooleanProperty clientReq = new SimpleBooleanProperty( true );

    /**
     * Whether this file is required on the server side of the modpack. Defaults to {@code true}.
     *
     * @since 3.0
     */
    private final BooleanProperty serverReq = new SimpleBooleanProperty( true );

    /**
     * Modrinth project slug associated with this entry, when sourced from Modrinth. Used by the editor
     * to look up update metadata. Defaults to the empty string.
     *
     * @since 3.0
     */
    private final StringProperty modrinthSlug = new SimpleStringProperty( "" );

    /**
     * Transient, UI-only result of the editor's "Check URLs" action (e.g. "OK",
     * "404", "ERR"). NOT part of the persisted modpack document: it is never read by
     * the editor's collectFieldsToDocument() and is reset on each check. Empty when
     * the entry's URL has not been checked.
     *
     * @since 3.0
     */
    private final StringProperty urlStatus = new SimpleStringProperty( "" );

    /**
     * Round-trip preservation for the OTHER hash types (i.e. the ones not currently
     * shown in the Hash column). Editor reads can populate the entry from a JSON
     * object that carries multiple hashes; the visible hash + hashType render the
     * strongest one, but the rest survive the edit cycle so saving doesn't
     * silently drop them. Keys are {@code "sha1"} / {@code "md5"} / {@code "sha256"};
     * the slot matching the primary {@link #hashType} is intentionally NOT stored
     * here (that one's in {@link #hash}). Empty / null when the source manifest
     * only had the one hash.
     *
     * @since 3.0
     */
    private final java.util.Map< String, String > extraHashes = new java.util.HashMap<>();

    /**
     * Constructs an empty file entry with all properties at their defaults: blank name / remote /
     * local / hash / modrinth slug, {@code "sha1"} hash type, and both client- and server-required
     * flags set to {@code true}.
     *
     * @since 3.0
     */
    public ModPackEditorFileEntry()
    {
    }

    /**
     * Constructs a file entry pre-populated from the supplied field values. {@code null} string
     * arguments are coalesced to safe defaults: {@code name} / {@code remote} / {@code local} /
     * {@code hash} become the empty string and {@code hashType} becomes {@code "sha1"}.
     *
     * @param name      display name of the file entry
     * @param remote    remote source URL the file is downloaded from
     * @param local     local installation path relative to the modpack root
     * @param hash      primary hash value used to verify the file
     * @param hashType  algorithm name for {@code hash} (e.g. {@code "sha1"}); {@code null} defaults to
     *                  {@code "sha1"}
     * @param clientReq whether the file is required on the client side
     * @param serverReq whether the file is required on the server side
     *
     * @since 3.0
     */
    public ModPackEditorFileEntry( String name, String remote, String local, String hash, String hashType,
                                    boolean clientReq, boolean serverReq )
    {
        this.name.set( name != null ? name : "" );
        this.remote.set( remote != null ? remote : "" );
        this.local.set( local != null ? local : "" );
        this.hash.set( hash != null ? hash : "" );
        this.hashType.set( hashType != null ? hashType : "sha1" );
        this.clientReq.set( clientReq );
        this.serverReq.set( serverReq );
    }

    // region Property accessors

    /**
     * Returns the JavaFX property backing the file's display name, for TableView cell binding.
     *
     * @return the name property
     *
     * @since 3.0
     */
    public StringProperty nameProperty() { return name; }

    /**
     * Returns the file's current display name.
     *
     * @return the display name
     *
     * @since 3.0
     */
    public String getName() { return name.get(); }

    /**
     * Sets the file's display name.
     *
     * @param value the new display name
     *
     * @since 3.0
     */
    public void setName( String value ) { name.set( value ); }

    /**
     * Returns the JavaFX property backing the remote source URL, for TableView cell binding.
     *
     * @return the remote URL property
     *
     * @since 3.0
     */
    public StringProperty remoteProperty() { return remote; }

    /**
     * Returns the file's current remote source URL.
     *
     * @return the remote URL
     *
     * @since 3.0
     */
    public String getRemote() { return remote.get(); }

    /**
     * Sets the file's remote source URL.
     *
     * @param value the new remote URL
     *
     * @since 3.0
     */
    public void setRemote( String value ) { remote.set( value ); }

    /**
     * Returns the JavaFX property backing the local install path, for TableView cell binding.
     *
     * @return the local path property
     *
     * @since 3.0
     */
    public StringProperty localProperty() { return local; }

    /**
     * Returns the file's current local install path, relative to the modpack root.
     *
     * @return the local path
     *
     * @since 3.0
     */
    public String getLocal() { return local.get(); }

    /**
     * Sets the file's local install path, relative to the modpack root.
     *
     * @param value the new local path
     *
     * @since 3.0
     */
    public void setLocal( String value ) { local.set( value ); }

    /**
     * Returns the JavaFX property backing the primary hash value, for TableView cell binding.
     *
     * @return the hash property
     *
     * @since 3.0
     */
    public StringProperty hashProperty() { return hash; }

    /**
     * Returns the file's current primary hash value, expressed in the algorithm named by
     * {@link #getHashType}.
     *
     * @return the primary hash value
     *
     * @since 3.0
     */
    public String getHash() { return hash.get(); }

    /**
     * Sets the file's primary hash value.
     *
     * @param value the new primary hash value
     *
     * @since 3.0
     */
    public void setHash( String value ) { hash.set( value ); }

    /**
     * Returns the JavaFX property backing the primary hash algorithm name, for TableView cell binding.
     *
     * @return the hash type property
     *
     * @since 3.0
     */
    public StringProperty hashTypeProperty() { return hashType; }

    /**
     * Returns the algorithm name for the primary {@link #getHash hash} value (e.g. {@code "sha1"}).
     *
     * @return the hash algorithm name
     *
     * @since 3.0
     */
    public String getHashType() { return hashType.get(); }

    /**
     * Sets the algorithm name for the primary {@link #getHash hash} value.
     *
     * @param value the new hash algorithm name
     *
     * @since 3.0
     */
    public void setHashType( String value ) { hashType.set( value ); }

    /**
     * Returns the JavaFX property backing the client-required flag, for TableView cell binding.
     *
     * @return the client-required property
     *
     * @since 3.0
     */
    public BooleanProperty clientReqProperty() { return clientReq; }

    /**
     * Returns whether this file is required on the client side of the modpack.
     *
     * @return {@code true} if client-required
     *
     * @since 3.0
     */
    public boolean isClientReq() { return clientReq.get(); }

    /**
     * Sets whether this file is required on the client side of the modpack.
     *
     * @param value {@code true} to mark the file client-required
     *
     * @since 3.0
     */
    public void setClientReq( boolean value ) { clientReq.set( value ); }

    /**
     * Returns the JavaFX property backing the server-required flag, for TableView cell binding.
     *
     * @return the server-required property
     *
     * @since 3.0
     */
    public BooleanProperty serverReqProperty() { return serverReq; }

    /**
     * Returns whether this file is required on the server side of the modpack.
     *
     * @return {@code true} if server-required
     *
     * @since 3.0
     */
    public boolean isServerReq() { return serverReq.get(); }

    /**
     * Sets whether this file is required on the server side of the modpack.
     *
     * @param value {@code true} to mark the file server-required
     *
     * @since 3.0
     */
    public void setServerReq( boolean value ) { serverReq.set( value ); }

    /**
     * Returns the JavaFX property backing the Modrinth project slug, for TableView cell binding.
     *
     * @return the Modrinth slug property
     *
     * @since 3.0
     */
    public StringProperty modrinthSlugProperty() { return modrinthSlug; }

    /**
     * Returns the Modrinth project slug associated with this entry, or the empty string when none.
     *
     * @return the Modrinth slug
     *
     * @since 3.0
     */
    public String getModrinthSlug() { return modrinthSlug.get(); }

    /**
     * Sets the Modrinth project slug associated with this entry. {@code null} is coalesced to the
     * empty string.
     *
     * @param value the new Modrinth slug; {@code null} clears it to the empty string
     *
     * @since 3.0
     */
    public void setModrinthSlug( String value ) { modrinthSlug.set( value != null ? value : "" ); }

    /**
     * Returns the JavaFX property backing the transient URL-check status, for TableView cell binding.
     *
     * @return the URL status property
     *
     * @since 3.0
     */
    public StringProperty urlStatusProperty() { return urlStatus; }

    /**
     * Returns the transient result of the editor's "Check URLs" action for this entry (e.g.
     * {@code "OK"}, {@code "404"}, {@code "ERR"}), or the empty string when not yet checked.
     *
     * @return the URL-check status string
     *
     * @since 3.0
     */
    public String getUrlStatus() { return urlStatus.get(); }

    /**
     * Sets the transient URL-check status for this entry. {@code null} is coalesced to the empty
     * string.
     *
     * @param value the new URL-check status; {@code null} clears it to the empty string
     *
     * @since 3.0
     */
    public void setUrlStatus( String value ) { urlStatus.set( value != null ? value : "" ); }

    /**
     * Stores a non-primary hash for round-trip preservation. {@code algo} is
     * one of {@code "sha1"} / {@code "md5"} / {@code "sha256"}; passing
     * {@code null} or empty {@code value} clears that slot. The algorithm name
     * is normalized to lower case before use.
     *
     * @param algo  the hash algorithm name; a {@code null} value is ignored
     * @param value the hash value to store; {@code null} or blank clears the slot
     *
     * @since 3.0
     */
    public void putExtraHash( String algo, String value ) {
        if ( algo == null ) return;
        if ( value == null || value.isBlank() ) {
            extraHashes.remove( algo.toLowerCase( java.util.Locale.ROOT ) );
        }
        else {
            extraHashes.put( algo.toLowerCase( java.util.Locale.ROOT ), value );
        }
    }

    /**
     * Returns the stored extra hash for the given algorithm, or {@code null} when
     * none is stored. The primary hash is NOT returned here — query
     * {@link #getHash} / {@link #getHashType} for that. The algorithm name is
     * normalized to lower case before lookup.
     *
     * @param algo the hash algorithm name to look up
     *
     * @return the stored extra hash value, or {@code null} if {@code algo} is {@code null} or no
     *         value is stored for that algorithm
     *
     * @since 3.0
     */
    public String getExtraHash( String algo ) {
        if ( algo == null ) return null;
        return extraHashes.get( algo.toLowerCase( java.util.Locale.ROOT ) );
    }

    // endregion
}
