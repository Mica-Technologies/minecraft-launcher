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

package com.micatechnologies.minecraft.launcher.steam;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Whether the launcher runs on a Steam Deck, from the firmware's DMI fields: vendor {@code Valve},
 * product {@code Jupiter} (LCD model) or {@code Galileo} (OLED model). Read once.
 *
 * @since 2026.11
 */
public final class SteamDeck
{
    private static final Path DMI = Path.of( "/sys/devices/virtual/dmi/id" );

    private static volatile Boolean cached;

    private SteamDeck() { /* static-only */ }

    /**
     * Whether this computer is a Steam Deck.
     *
     * @return {@code true} on a Steam Deck
     *
     * @since 2026.11
     */
    public static boolean isSteamDeck()
    {
        Boolean known = cached;
        if ( known == null ) {
            known = matches( read( "sys_vendor" ), read( "product_name" ) );
            cached = known;
        }
        return known;
    }

    /**
     * Whether DMI vendor and product name are a Steam Deck's. Pure, for tests.
     *
     * @param vendor  {@code sys_vendor}, or {@code null}
     * @param product {@code product_name}, or {@code null}
     *
     * @return {@code true} for a Steam Deck
     *
     * @since 2026.11
     */
    static boolean matches( String vendor, String product )
    {
        if ( vendor == null || product == null || !vendor.trim().equalsIgnoreCase( "Valve" ) ) {
            return false;
        }
        String name = product.trim();
        return name.equalsIgnoreCase( "Jupiter" ) || name.equalsIgnoreCase( "Galileo" );
    }

    private static String read( String field )
    {
        try {
            return Files.readString( DMI.resolve( field ), StandardCharsets.UTF_8 );
        }
        catch ( IOException | RuntimeException e ) {
            return null;
        }
    }
}
