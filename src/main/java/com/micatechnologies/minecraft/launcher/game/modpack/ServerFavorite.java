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

/**
 * One per-pack Minecraft server favorite. Stored in the launcher's config
 * file keyed by manifest URL → list-of-favorites, surfaced in the modpack
 * detail modal's Servers section, and threaded through
 * {@link com.micatechnologies.minecraft.launcher.LauncherCore#play} as
 * {@code --server} + {@code --port} argv when the user clicks Connect.
 *
 * <p>{@link #port} defaults to {@code 25565} (Minecraft's well-known port)
 * when the user omits it from the host string. Parsing of host:port
 * strings (the form the user enters in the UI) lives on {@link #parse}.</p>
 *
 * @param name human-readable label shown in the modal's server-list row
 * @param host hostname or IP address, no port
 * @param port TCP port, defaults to 25565 when omitted
 *
 * @since 2026.5
 */
public record ServerFavorite( String name, String host, int port )
{
    /** Minecraft's default server port. */
    public static final int DEFAULT_PORT = 25565;

    /** Compact-canonical form: {@code host} when port is the default,
     *  {@code host:port} otherwise. Mirrors what the user typed.
     *
     *  @return the bare host when {@link #port} is {@link #DEFAULT_PORT},
     *          otherwise {@code host:port}
     *
     *  @since 2026.5 */
    public String displayAddress()
    {
        return port == DEFAULT_PORT ? host : ( host + ":" + port );
    }

    /**
     * Parses a {@code host} or {@code host:port} string into a favorite
     * with the given display name. Returns {@code null} on malformed input
     * (empty host, non-numeric port, port out of range). When no port is
     * present the favorite uses {@link #DEFAULT_PORT}; a blank {@code name}
     * falls back to the host.
     *
     * @param name     human-readable label, or {@code null}/blank to default
     *                 to the host
     * @param hostPort the user-entered {@code host} or {@code host:port}
     *                 string
     * @return the parsed favorite, or {@code null} when {@code hostPort} is
     *         blank, the host is empty, or the port is non-numeric or outside
     *         {@code 1..65535}
     *
     * @since 2026.5
     */
    public static ServerFavorite parse( String name, String hostPort )
    {
        if ( hostPort == null || hostPort.isBlank() ) return null;
        String trimmed = hostPort.trim();
        int colon = trimmed.lastIndexOf( ':' );
        String host;
        int port = DEFAULT_PORT;
        if ( colon < 0 ) {
            host = trimmed;
        }
        else {
            host = trimmed.substring( 0, colon );
            String portStr = trimmed.substring( colon + 1 );
            try {
                port = Integer.parseInt( portStr );
            }
            catch ( NumberFormatException ex ) {
                return null;
            }
            if ( port < 1 || port > 65535 ) return null;
        }
        if ( host.isBlank() ) return null;
        // Display name falls back to the host when the user didn't
        // provide one — pragmatic default.
        String safeName = ( name == null || name.isBlank() ) ? host : name.trim();
        return new ServerFavorite( safeName, host, port );
    }
}
