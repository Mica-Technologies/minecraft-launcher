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

    // endregion
}
