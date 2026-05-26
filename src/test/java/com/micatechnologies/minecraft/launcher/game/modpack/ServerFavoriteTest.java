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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests for the {@link ServerFavorite#parse} parser and {@link ServerFavorite#displayAddress}
 * round-trip. These are pure-logic tests — no FX, no I/O. Each detector lives in its own
 * method so a failure clearly identifies which case regressed.
 */
class ServerFavoriteTest
{
    @Test
    void parse_hostOnly_assignsDefaultPort()
    {
        ServerFavorite f = ServerFavorite.parse( "My Server", "play.example.com" );
        assertEquals( "My Server", f.name() );
        assertEquals( "play.example.com", f.host() );
        assertEquals( ServerFavorite.DEFAULT_PORT, f.port() );
    }

    @Test
    void parse_hostAndCustomPort()
    {
        ServerFavorite f = ServerFavorite.parse( "Custom", "alto.tacomano.example.com:8585" );
        assertEquals( "Custom", f.name() );
        assertEquals( "alto.tacomano.example.com", f.host() );
        assertEquals( 8585, f.port() );
    }

    @Test
    void parse_blankName_fallsBackToHost()
    {
        ServerFavorite f = ServerFavorite.parse( "  ", "play.example.com" );
        assertEquals( "play.example.com", f.name() );
    }

    @Test
    void parse_nullName_fallsBackToHost()
    {
        ServerFavorite f = ServerFavorite.parse( null, "play.example.com:25566" );
        assertEquals( "play.example.com", f.name() );
        assertEquals( 25566, f.port() );
    }

    @Test
    void parse_emptyHostPort_returnsNull()
    {
        assertNull( ServerFavorite.parse( "x", "" ) );
        assertNull( ServerFavorite.parse( "x", "   " ) );
        assertNull( ServerFavorite.parse( "x", null ) );
    }

    @Test
    void parse_blankHostWithPort_returnsNull()
    {
        // Colon-prefixed string with no host — invalid even though the port parses.
        assertNull( ServerFavorite.parse( "x", ":25565" ) );
    }

    @Test
    void parse_nonNumericPort_returnsNull()
    {
        assertNull( ServerFavorite.parse( "x", "host:abc" ) );
    }

    @Test
    void parse_portOutOfRange_returnsNull()
    {
        assertNull( ServerFavorite.parse( "x", "host:0" ) );
        assertNull( ServerFavorite.parse( "x", "host:65536" ) );
        assertNull( ServerFavorite.parse( "x", "host:-1" ) );
    }

    @Test
    void parse_portAtLimits_accepted()
    {
        assertEquals( 1, ServerFavorite.parse( "x", "host:1" ).port() );
        assertEquals( 65535, ServerFavorite.parse( "x", "host:65535" ).port() );
    }

    @Test
    void displayAddress_defaultPort_omitsPort()
    {
        ServerFavorite f = new ServerFavorite( "x", "play.example.com",
                                                ServerFavorite.DEFAULT_PORT );
        assertEquals( "play.example.com", f.displayAddress() );
    }

    @Test
    void displayAddress_customPort_includesPort()
    {
        ServerFavorite f = new ServerFavorite( "x", "play.example.com", 8585 );
        assertEquals( "play.example.com:8585", f.displayAddress() );
    }

    @Test
    void parse_trimsLeadingTrailingSpaces()
    {
        // The user typing into the modal's Address field often leaves a trailing
        // space; the parser should tolerate that without a "host  doesn't exist"
        // mid-game.
        ServerFavorite f = ServerFavorite.parse( "  Name  ", "  play.example.com  " );
        assertEquals( "Name", f.name() );
        assertEquals( "play.example.com", f.host() );
    }
}
