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

import com.micatechnologies.minecraft.launcher.exceptions.ModpackException;

/**
 * NeoForge implementation of {@link GameModLoader}. Structurally
 * identical to modern Forge (1.13+): the installer JAR carries a
 * top-level {@code version.json}, an {@code install_profile.json}
 * with install processors + library data, and {@code maven/} embedded
 * artifact paths. Reuses {@link GameModLoaderForge}'s plumbing
 * verbatim — the only practical difference is the loader's own
 * Maven coordinate prefix ({@code net.neoforged:neoforge:} rather
 * than {@code net.minecraftforge:forge:}) which the universal-jar
 * detection branches key on.
 *
 * <h3>Manifest convention</h3>
 *
 * <p>Same shape as Forge — {@code packModLoaderURL} points at the
 * NeoForge installer JAR (on {@code maven.neoforged.net}),
 * {@code packModLoaderHash} is the installer's SHA-1.</p>
 *
 * <p>NeoForge's MC coverage starts at 1.20.1 — earlier MC versions
 * have no NeoForge releases. The pack manifest authoring side is
 * expected to enforce that; the launcher doesn't validate the
 * mc-version / loader-version pairing.</p>
 *
 * @since 2026.5
 */
final class GameModLoaderNeoForge extends GameModLoaderForge
{
    GameModLoaderNeoForge( String remoteURL, String sha1Hash, GameModPack parentModPack )
            throws ModpackException
    {
        super( remoteURL, sha1Hash, parentModPack );
    }

    @Override
    public String getName() {
        return "NeoForge";
    }

    @Override
    protected String loaderCoordPrefix() {
        return "net.neoforged:neoforge:";
    }
}
