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
    /**
     * Constructs a {@link GameModLoaderNeoForge} for the supplied NeoForge
     * installer JAR. Delegates entirely to {@link GameModLoaderForge}'s
     * constructor, which downloads/verifies the installer and parses its
     * embedded {@code version.json} — NeoForge installers share the modern
     * Forge installer shape, so no NeoForge-specific construction is needed.
     *
     * @param remoteURL     URL of the NeoForge installer JAR (typically on
     *                      {@code maven.neoforged.net})
     * @param sha1Hash      expected SHA-1 hash of the installer JAR
     * @param parentModPack mod pack this loader belongs to
     *
     * @throws ModpackException if the installer cannot be downloaded, verified,
     *                          or its version manifest is missing required fields
     * @since 2026.5
     */
    GameModLoaderNeoForge( String remoteURL, String sha1Hash, GameModPack parentModPack )
            throws ModpackException
    {
        super( remoteURL, sha1Hash, parentModPack );
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns {@code "NeoForge"}, distinguishing this loader from the
     * {@code "Forge"} reported by the superclass in launcher and GUI
     * displays.</p>
     *
     * @return the constant {@code "NeoForge"}
     * @since 2026.5
     */
    @Override
    public String getName() {
        return "NeoForge";
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns the NeoForge Maven coordinate prefix
     * ({@code net.neoforged:neoforge:}) so the inherited universal-jar /
     * install-jar detection in {@code getForgeAssets} keys on NeoForge's own
     * library entries rather than upstream Forge's.</p>
     *
     * @return the constant {@code "net.neoforged:neoforge:"}
     * @since 2026.5
     */
    @Override
    protected String loaderCoordPrefix() {
        return "net.neoforged:neoforge:";
    }
}
