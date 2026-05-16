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
    private final StringProperty name = new SimpleStringProperty( "" );
    private final StringProperty remote = new SimpleStringProperty( "" );
    private final StringProperty local = new SimpleStringProperty( "" );
    private final StringProperty hash = new SimpleStringProperty( "" );
    private final StringProperty hashType = new SimpleStringProperty( "sha1" );
    private final BooleanProperty clientReq = new SimpleBooleanProperty( true );
    private final BooleanProperty serverReq = new SimpleBooleanProperty( true );
    private final StringProperty modrinthSlug = new SimpleStringProperty( "" );

    /**
     * Round-trip preservation for the OTHER hash types (i.e. the ones not currently
     * shown in the Hash column). Editor reads can populate the entry from a JSON
     * object that carries multiple hashes; the visible hash + hashType render the
     * strongest one, but the rest survive the edit cycle so saving doesn't
     * silently drop them. Keys are {@code "sha1"} / {@code "md5"} / {@code "sha256"};
     * the slot matching the primary {@link #hashType} is intentionally NOT stored
     * here (that one's in {@link #hash}). Empty / null when the source manifest
     * only had the one hash.
     */
    private final java.util.Map< String, String > extraHashes = new java.util.HashMap<>();

    public ModPackEditorFileEntry()
    {
    }

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

    public StringProperty nameProperty() { return name; }
    public String getName() { return name.get(); }
    public void setName( String value ) { name.set( value ); }

    public StringProperty remoteProperty() { return remote; }
    public String getRemote() { return remote.get(); }
    public void setRemote( String value ) { remote.set( value ); }

    public StringProperty localProperty() { return local; }
    public String getLocal() { return local.get(); }
    public void setLocal( String value ) { local.set( value ); }

    public StringProperty hashProperty() { return hash; }
    public String getHash() { return hash.get(); }
    public void setHash( String value ) { hash.set( value ); }

    public StringProperty hashTypeProperty() { return hashType; }
    public String getHashType() { return hashType.get(); }
    public void setHashType( String value ) { hashType.set( value ); }

    public BooleanProperty clientReqProperty() { return clientReq; }
    public boolean isClientReq() { return clientReq.get(); }
    public void setClientReq( boolean value ) { clientReq.set( value ); }

    public BooleanProperty serverReqProperty() { return serverReq; }
    public boolean isServerReq() { return serverReq.get(); }
    public void setServerReq( boolean value ) { serverReq.set( value ); }

    public StringProperty modrinthSlugProperty() { return modrinthSlug; }
    public String getModrinthSlug() { return modrinthSlug.get(); }
    public void setModrinthSlug( String value ) { modrinthSlug.set( value != null ? value : "" ); }

    /** Stores a non-primary hash for round-trip preservation. {@code algo} is
     *  one of {@code "sha1"} / {@code "md5"} / {@code "sha256"}; passing
     *  {@code null} or empty {@code value} clears that slot. */
    public void putExtraHash( String algo, String value ) {
        if ( algo == null ) return;
        if ( value == null || value.isBlank() ) {
            extraHashes.remove( algo.toLowerCase( java.util.Locale.ROOT ) );
        }
        else {
            extraHashes.put( algo.toLowerCase( java.util.Locale.ROOT ), value );
        }
    }

    /** Returns the stored extra hash for the given algorithm, or {@code null} when
     *  none is stored. The primary hash is NOT returned here — query
     *  {@link #getHash} / {@link #getHashType} for that. */
    public String getExtraHash( String algo ) {
        if ( algo == null ) return null;
        return extraHashes.get( algo.toLowerCase( java.util.Locale.ROOT ) );
    }

    // endregion
}
