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

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A single entry in one of a modpack manifest's file lists ({@code packMods}, {@code packConfigs},
 * {@code packResourcePacks}, {@code packShaderPacks}, {@code packInitialFiles}).
 * <p>
 * This is the headless counterpart of the editor's JavaFX-property-backed
 * {@code gui.ModPackEditorFileEntry}. It carries exactly the state that round-trips through the
 * manifest JSON and nothing that is purely a view concern, so manifest authoring works without a
 * JavaFX toolkit — from unit tests, from the MCP server, or from any other headless caller.
 * <p>
 * <b>Hash model.</b> A manifest entry may carry up to three hash slots ({@code sha1}, {@code md5},
 * {@code sha256}). One of them is the <em>primary</em> hash, exposed as {@link #getHash()} plus
 * {@link #getHashType()}; the remaining populated slots are kept in {@link #getExtraHash(String)}
 * so an edit cycle preserves them instead of silently dropping them. See
 * {@link ModPackDocument#readFileList} for the precedence rule that picks the primary.
 *
 * @author Mica Technologies
 * @version 1.0
 * @since 3.0
 */
public class ModPackFileEntry
{
    /**
     * Display name of the entry. Only the {@code packMods} list carries names; every other list
     * leaves this empty.
     */
    private String name = "";

    /** Remote URL the file is downloaded from. Required — an empty value is a validation issue. */
    private String remote = "";

    /** Local path the file is installed to, relative to the modpack root. Required. */
    private String local = "";

    /** The primary hash value, or empty when the entry carries no usable hash. */
    private String hash = "";

    /** Algorithm of {@link #hash} — one of {@code "sha1"}, {@code "md5"}, {@code "sha256"}. */
    private String hashType = "sha1";

    /** Whether the file is required on clients. Absent in JSON means {@code true}. */
    private boolean clientReq = true;

    /** Whether the file is required on servers. Absent in JSON means {@code true}. */
    private boolean serverReq = true;

    /** Optional Modrinth project slug (manifestFormat 2+); empty when not sourced from Modrinth. */
    private String modrinthSlug = "";

    /**
     * Non-primary hashes, keyed by lower-case algorithm name. Preserved verbatim across an edit
     * cycle so a manifest carrying both a sha1 and a sha256 still carries both after a save.
     */
    private final Map< String, String > extraHashes = new HashMap<>();

    /**
     * Constructs an empty entry with the default hash type ({@code sha1}) and both requirement
     * flags set.
     *
     * @since 3.0
     */
    public ModPackFileEntry()
    {
    }

    /**
     * Constructs a fully populated entry.
     *
     * @param name         display name, or {@code ""} for lists that carry no name
     * @param remote       remote download URL
     * @param local        local install path relative to the modpack root
     * @param hash         primary hash value, or {@code ""} when there is none
     * @param hashType     algorithm of {@code hash}
     * @param clientReq    whether the file is required on clients
     * @param serverReq    whether the file is required on servers
     *
     * @since 3.0
     */
    public ModPackFileEntry( String name, String remote, String local, String hash, String hashType,
                             boolean clientReq, boolean serverReq )
    {
        this.name = name == null ? "" : name;
        this.remote = remote == null ? "" : remote;
        this.local = local == null ? "" : local;
        this.hash = hash == null ? "" : hash;
        this.hashType = hashType == null ? "sha1" : hashType;
        this.clientReq = clientReq;
        this.serverReq = serverReq;
    }

    /**
     * Returns the entry's display name.
     *
     * @return the display name, never {@code null}
     *
     * @since 3.0
     */
    public String getName() { return name; }

    /**
     * Sets the entry's display name.
     *
     * @param value the new name; {@code null} is coalesced to {@code ""}
     *
     * @since 3.0
     */
    public void setName( String value ) { this.name = value == null ? "" : value; }

    /**
     * Returns the remote download URL.
     *
     * @return the remote URL, never {@code null}
     *
     * @since 3.0
     */
    public String getRemote() { return remote; }

    /**
     * Sets the remote download URL.
     *
     * @param value the new URL; {@code null} is coalesced to {@code ""}
     *
     * @since 3.0
     */
    public void setRemote( String value ) { this.remote = value == null ? "" : value; }

    /**
     * Returns the local install path, relative to the modpack root.
     *
     * @return the local path, never {@code null}
     *
     * @since 3.0
     */
    public String getLocal() { return local; }

    /**
     * Sets the local install path.
     *
     * @param value the new path; {@code null} is coalesced to {@code ""}
     *
     * @since 3.0
     */
    public void setLocal( String value ) { this.local = value == null ? "" : value; }

    /**
     * Returns the primary hash value.
     *
     * @return the primary hash, or {@code ""} when the entry has no usable hash
     *
     * @since 3.0
     */
    public String getHash() { return hash; }

    /**
     * Sets the primary hash value.
     *
     * @param value the new hash; {@code null} is coalesced to {@code ""}
     *
     * @since 3.0
     */
    public void setHash( String value ) { this.hash = value == null ? "" : value; }

    /**
     * Returns the algorithm of the primary hash.
     *
     * @return one of {@code "sha1"}, {@code "md5"}, {@code "sha256"}
     *
     * @since 3.0
     */
    public String getHashType() { return hashType; }

    /**
     * Sets the algorithm of the primary hash.
     *
     * @param value the new algorithm; {@code null} is coalesced to {@code "sha1"}
     *
     * @since 3.0
     */
    public void setHashType( String value ) { this.hashType = value == null ? "sha1" : value; }

    /**
     * Reports whether the file is required on clients.
     *
     * @return {@code true} when required on clients
     *
     * @since 3.0
     */
    public boolean isClientReq() { return clientReq; }

    /**
     * Sets whether the file is required on clients.
     *
     * @param value the new requirement flag
     *
     * @since 3.0
     */
    public void setClientReq( boolean value ) { this.clientReq = value; }

    /**
     * Reports whether the file is required on servers.
     *
     * @return {@code true} when required on servers
     *
     * @since 3.0
     */
    public boolean isServerReq() { return serverReq; }

    /**
     * Sets whether the file is required on servers.
     *
     * @param value the new requirement flag
     *
     * @since 3.0
     */
    public void setServerReq( boolean value ) { this.serverReq = value; }

    /**
     * Returns the Modrinth project slug.
     *
     * @return the slug, or {@code ""} when the entry is not sourced from Modrinth
     *
     * @since 3.0
     */
    public String getModrinthSlug() { return modrinthSlug; }

    /**
     * Sets the Modrinth project slug.
     *
     * @param value the new slug; {@code null} is coalesced to {@code ""}
     *
     * @since 3.0
     */
    public void setModrinthSlug( String value ) { this.modrinthSlug = value == null ? "" : value; }

    /**
     * Stores a non-primary hash for round-trip preservation. Passing a {@code null} or blank
     * {@code value} clears that slot. The algorithm name is normalized to lower case.
     *
     * @param algo  the hash algorithm name; a {@code null} algorithm is ignored
     * @param value the hash value to store; {@code null} or blank clears the slot
     *
     * @since 3.0
     */
    public void putExtraHash( String algo, String value )
    {
        if ( algo == null ) {
            return;
        }
        if ( value == null || value.isBlank() ) {
            extraHashes.remove( algo.toLowerCase( Locale.ROOT ) );
        }
        else {
            extraHashes.put( algo.toLowerCase( Locale.ROOT ), value );
        }
    }

    /**
     * Returns the stored non-primary hash for the given algorithm. The primary hash is not returned
     * here — query {@link #getHash()} / {@link #getHashType()} for that.
     *
     * @param algo the hash algorithm name to look up
     *
     * @return the stored value, or {@code null} when {@code algo} is {@code null} or no value is
     *         stored for that algorithm
     *
     * @since 3.0
     */
    public String getExtraHash( String algo )
    {
        if ( algo == null ) {
            return null;
        }
        return extraHashes.get( algo.toLowerCase( Locale.ROOT ) );
    }

    /**
     * Returns an unmodifiable view of every stored non-primary hash, keyed by lower-case algorithm
     * name.
     *
     * @return the extra-hash map; empty when the entry carries only a primary hash
     *
     * @since 3.0
     */
    public Map< String, String > getExtraHashes()
    {
        return Collections.unmodifiableMap( extraHashes );
    }
}
