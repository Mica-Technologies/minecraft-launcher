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

import com.micatechnologies.minecraft.launcher.config.ConfigManager;
import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.utilities.JSONUtilities;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The launcher-facing side of optional mods: loads a pack's optional-mod list, reads and stores
 * the player's choices, and applies a toggle. The rules themselves live in
 * {@link OptionalModResolver}; this class only connects them to the config and the manifest, so
 * the detail modal and the MCP tools behave identically.
 *
 * @author Mica Technologies
 * @since 2026.11
 */
public final class OptionalMods
{
    /**
     * Not instantiable.
     */
    private OptionalMods()
    {
        throw new AssertionError( "OptionalMods is static-only" );
    }

    /**
     * Returns a pack's optional mods, reading the manifest when the pack is only a stub (an
     * install-index entry whose manifest body was never loaded).
     * <p>
     * May read the manifest from disk or the manifest cache, so call it off the FX thread.
     *
     * @param pack the pack; {@code null} yields an empty list
     *
     * @return the optional mods in manifest order, never {@code null}
     *
     * @since 2026.11
     */
    public static List< GameOptionalMod > of( GameModPack pack )
    {
        if ( pack == null ) {
            return List.of();
        }
        if ( !pack.isStub() ) {
            return pack.getPackOptionalMods();
        }
        try {
            String text = GameModPackFetcher.loadManifestText( pack.getManifestUrl() );
            return text == null ? List.of() : parse( text );
        }
        catch ( Exception e ) {
            Logger.logWarningSilent( LocalizationManager.format( "log.optionalMods.manifestReadFailed",
                                                                 pack.getPackName(),
                                                                 e.getClass().getSimpleName() ) );
            return List.of();
        }
    }

    /**
     * Reads the optional mods out of manifest text, with the same Gson model the sync uses.
     *
     * @param manifestText the manifest JSON
     *
     * @return the optional mods in manifest order, never {@code null}
     *
     * @throws com.google.gson.JsonSyntaxException if the text is not valid JSON
     * @since 2026.11
     */
    public static List< GameOptionalMod > parse( String manifestText )
    {
        GameModPack parsed = JSONUtilities.getGson().fromJson( manifestText, GameModPack.class );
        return parsed == null ? List.of() : parsed.getPackOptionalMods();
    }

    /**
     * Returns the player's stored choices for a pack.
     *
     * @param pack the pack
     *
     * @return the choices (local name to on/off), never {@code null}; empty when the pack has no
     *         settings key
     *
     * @since 2026.11
     */
    public static Map< String, Boolean > choicesFor( GameModPack pack )
    {
        String key = pack == null ? null : pack.getSettingsKey();
        return key == null ? new LinkedHashMap<>() : ConfigManager.getOptionalModChoices( key );
    }

    /**
     * Returns which of a pack's optional mods are on for the player.
     *
     * @param pack the pack
     * @param mods the pack's optional mods (see {@link #of})
     *
     * @return the identity keys of the mods that are on
     *
     * @since 2026.11
     */
    public static Set< String > enabledKeys( GameModPack pack, List< GameOptionalMod > mods )
    {
        return OptionalModResolver.resolveEnabled( mods, choicesFor( pack ) );
    }

    /**
     * Turns one optional mod on or off for the player, with its {@code requires} and
     * {@code conflictsWith} applied, and stores the result. Takes effect on the next launch or
     * sync. A refused toggle stores nothing.
     *
     * @param pack   the pack
     * @param mods   the pack's optional mods (see {@link #of})
     * @param local  any spelling of the mod's local name
     * @param enable the requested state
     *
     * @return what the toggle did; see {@link OptionalModResolver#toggle}
     *
     * @since 2026.11
     */
    public static OptionalModResolver.ToggleResult toggle( GameModPack pack, List< GameOptionalMod > mods,
                                                           String local, boolean enable )
    {
        OptionalModResolver.ToggleResult result =
                OptionalModResolver.toggle( mods, choicesFor( pack ), local, enable );
        String key = pack == null ? null : pack.getSettingsKey();
        if ( result.applied() && key != null ) {
            ConfigManager.setOptionalModChoices( key, toStoredChoices( mods, result.choices() ) );
        }
        return result;
    }

    /**
     * Turns resolver choices (keyed by identity key) into the stored form, keyed by each mod's
     * local name as the manifest spells it. Choices for names the manifest no longer lists are
     * kept as they are, so a mod that comes back keeps the player's choice.
     *
     * @param mods    the pack's optional mods
     * @param choices the choices by identity key
     *
     * @return the choices to store
     *
     * @since 2026.11
     */
    static Map< String, Boolean > toStoredChoices( List< GameOptionalMod > mods, Map< String, Boolean > choices )
    {
        Map< String, GameOptionalMod > byKey = OptionalModResolver.index( mods );
        Map< String, Boolean > stored = new LinkedHashMap<>();
        for ( Map.Entry< String, Boolean > e : choices.entrySet() ) {
            GameOptionalMod mod = byKey.get( e.getKey() );
            stored.put( mod != null ? mod.getLocalName() : e.getKey(), e.getValue() );
        }
        return stored;
    }
}
