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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * One entry of a manifest's {@code packOptionalMods} list: a mod the pack offers but that each
 * player switches on or off for themselves.
 * <p>
 * The list is a top-level key of its own rather than a flag on a {@code packMods} entry so that
 * a launcher that does not know the feature never installs these jars: it ignores the unknown
 * key, and its mods-folder sweep removes any jar a newer launcher left behind.
 * <p>
 * An entry has every field of a {@code packMods} entry plus:
 * <ul>
 *   <li>{@code description}: a short text shown to the player next to the toggle;</li>
 *   <li>{@code defaultEnabled}: whether the mod is on for a player who has not chosen
 *       ({@code false} when absent);</li>
 *   <li>{@code requires}: the {@code local} names of other optional mods that must be on with
 *       this one;</li>
 *   <li>{@code conflictsWith}: the {@code local} names of other optional mods that must be off
 *       while this one is on.</li>
 * </ul>
 * <b>Sides.</b> Unlike {@code packMods}, an absent {@code clientReq} means {@code true} and an
 * absent {@code serverReq} means {@code false}: an optional mod is client-only unless the entry
 * says otherwise. Gson builds this class through its no-argument constructor, which seeds those
 * defaults before the JSON's own values are applied.
 *
 * @author Mica Technologies
 * @since 2026.11
 */
public class GameOptionalMod extends GameMod
{
    /**
     * Short text shown to the player next to the toggle. May be absent in JSON.
     *
     * @since 2026.11
     */
    @SuppressWarnings( "unused" )
    private String description;

    /**
     * Whether the mod is on for a player who has not chosen. Absent in JSON means {@code false}.
     *
     * @since 2026.11
     */
    @SuppressWarnings( "unused" )
    private boolean defaultEnabled;

    /**
     * The {@code local} names of other optional mods that must be on with this one.
     *
     * @since 2026.11
     */
    @SuppressWarnings( "unused" )
    private List< String > requires;

    /**
     * The {@code local} names of other optional mods that must be off while this one is on.
     *
     * @since 2026.11
     */
    @SuppressWarnings( "unused" )
    private List< String > conflictsWith;

    /**
     * Constructor Gson uses: seeds the optional-mod side defaults (client yes, server no) that
     * the manifest's own values then overwrite.
     */
    @SuppressWarnings( "unused" )
    private GameOptionalMod()
    {
        super( null, null, null, ManagedGameFileHashType.SHA1, null, true, false );
    }

    /**
     * Creates an optional mod. Used by tests and by callers that build entries in code.
     *
     * @param name           display name
     * @param remote         download URL
     * @param local          file name in the pack's {@code mods} folder
     * @param sha256         expected SHA-256, or {@code null}
     * @param clientReq      whether the mod is installed on clients
     * @param serverReq      whether the mod may be installed on dedicated servers
     * @param description    short text shown to the player
     * @param defaultEnabled whether the mod is on for a player who has not chosen
     * @param requires       the {@code local} names this mod needs on; may be {@code null}
     * @param conflictsWith  the {@code local} names that must be off while this is on; may be
     *                       {@code null}
     *
     * @since 2026.11
     */
    public GameOptionalMod( String name, String remote, String local, String sha256, boolean clientReq,
                            boolean serverReq, String description, boolean defaultEnabled,
                            List< String > requires, List< String > conflictsWith )
    {
        super( name, remote, sha256, ManagedGameFileHashType.SHA256, local, clientReq, serverReq );
        this.description = description;
        this.defaultEnabled = defaultEnabled;
        this.requires = requires == null ? null : new ArrayList<>( requires );
        this.conflictsWith = conflictsWith == null ? null : new ArrayList<>( conflictsWith );
    }

    /**
     * Returns the mod's display name, falling back to its file name when the entry has none.
     *
     * @return the name to show the player, never {@code null}
     *
     * @since 2026.11
     */
    public String getDisplayName()
    {
        if ( name != null && !name.isBlank() ) {
            return name;
        }
        return getLocalName();
    }

    /**
     * Returns the short description shown to the player.
     *
     * @return the description, {@code ""} when absent
     *
     * @since 2026.11
     */
    public String getDescription()
    {
        return description == null ? "" : description;
    }

    /**
     * Reports whether the mod is on for a player who has not chosen.
     *
     * @return the manifest's {@code defaultEnabled}, {@code false} when absent
     *
     * @since 2026.11
     */
    public boolean isDefaultEnabled()
    {
        return defaultEnabled;
    }

    /**
     * Returns the {@code local} names of the optional mods this one needs on.
     *
     * @return the names as written in the manifest, never {@code null}
     *
     * @since 2026.11
     */
    public List< String > getRequires()
    {
        return requires == null ? List.of() : Collections.unmodifiableList( requires );
    }

    /**
     * Returns the {@code local} names of the optional mods that must be off while this is on.
     *
     * @return the names as written in the manifest, never {@code null}
     *
     * @since 2026.11
     */
    public List< String > getConflictsWith()
    {
        return conflictsWith == null ? List.of() : Collections.unmodifiableList( conflictsWith );
    }

    /**
     * Reports whether the mod is installed on clients when it is on.
     *
     * @return the entry's {@code clientReq} ({@code true} when absent)
     *
     * @since 2026.11
     */
    public boolean isClientSide()
    {
        return clientReq;
    }

    /**
     * Reports whether a dedicated server may install the mod. A server, having no player to
     * choose, starts from the mods where this and {@link #isDefaultEnabled()} are both true and
     * adds what they require; a required mod for which this is false keeps its whole group off
     * the server (see {@link OptionalModResolver}).
     *
     * @return the entry's {@code serverReq} ({@code false} when absent)
     *
     * @since 2026.11
     */
    public boolean isServerSide()
    {
        return serverReq;
    }

    /**
     * Returns the mod's {@code local} name normalised the way every optional-mod lookup compares
     * it: through {@link ModPackDocument#normalizeModLocalPath(String)}, so {@code mods/x.jar} and
     * {@code x.jar} name the same mod.
     *
     * @return the normalised local name, {@code ""} when absent
     *
     * @since 2026.11
     */
    public String getLocalName()
    {
        return ModPackDocument.normalizeModLocalPath( rawLocal() );
    }

    /**
     * Reports whether the entry's {@code local} is a plain file name, which an optional mod's must
     * be: the sync joins it to the pack's {@code mods} folder as written, so {@code mods/x.jar}
     * would install to {@code mods/mods/x.jar}, where the game does not load it.
     *
     * @return {@code true} when {@code local} is non-blank and holds no folder
     *
     * @since 2026.11
     */
    public boolean isPlainFileName()
    {
        String local = rawLocal();
        return local != null && !local.isBlank() && !local.contains( "/" );
    }

    /**
     * Returns the identity key the player's choices and the {@code requires}/{@code conflictsWith}
     * lists are matched by: the normalised local name, lower-cased, since the launcher runs on
     * filesystems that ignore case.
     *
     * @return the key, {@code ""} when the entry has no local name
     *
     * @since 2026.11
     */
    public String key()
    {
        return keyOf( rawLocal() );
    }

    /**
     * Turns any spelling of an optional mod's local name into its identity key.
     *
     * @param local the local name as written anywhere (manifest, config, a tool argument)
     *
     * @return the key: normalised and lower-cased; {@code ""} for {@code null}
     *
     * @since 2026.11
     */
    public static String keyOf( String local )
    {
        return ModPackDocument.normalizeModLocalPath( local ).toLowerCase( Locale.ROOT );
    }

    /**
     * Reads the entry's local path as the manifest wrote it.
     *
     * @return the local path, or {@code null}
     */
    private String rawLocal()
    {
        String full = getLocalFilePath();
        return full == null ? null : full.replace( '\\', '/' );
    }
}
