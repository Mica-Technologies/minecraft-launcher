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
import com.micatechnologies.minecraft.launcher.utilities.objects.GameMode;

/**
 * Modloader-agnostic surface the launcher uses to drive a modpack's
 * launch pipeline. {@link GameModLoaderForge} was historically the
 * only implementation and exposes Forge-named methods directly; this
 * interface lets {@code GameModPack} and {@code GameModPackLauncher}
 * dispatch polymorphically so NeoForge and Fabric (and any future
 * loaders) can plug in without touching the launch flow.
 *
 * <h3>Contract</h3>
 *
 * <p>An implementation is constructed from the manifest's
 * {@code packModLoaderURL} + {@code packModLoaderHash} (falling back
 * to the legacy {@code packForgeURL} / {@code packForgeHash} fields
 * when the new ones are absent — see
 * {@link GameModPack#getModLoader()}). The constructor is expected to:
 *
 * <ol>
 *   <li>Download and verify whatever installer / profile artifact the
 *       loader needs to bootstrap.</li>
 *   <li>Resolve the launch-time metadata that the rest of the launcher
 *       reads via the getters on this interface (MC version, loader
 *       version, main class, default args). These should be cached as
 *       fields so the getters don't re-do work on every call.</li>
 * </ol>
 *
 * <p>The launcher then calls {@link #buildClasspath} once per launch
 * to download / validate every library the loader needs and produce
 * a {@code File.pathSeparator}-joined classpath string, optionally
 * {@link #runPostInstallSteps} to execute a modloader-specific
 * patching pipeline (Forge install processors today; NeoForge will
 * follow the same shape; Fabric is a no-op).</p>
 *
 * @since 2026.5
 */
public interface GameModLoader
{
    /** Human-readable name of the modloader — "Forge", "NeoForge",
     *  "Fabric". Used by GUI chips and log lines; never the empty
     *  string. */
    String getName();

    /** Minecraft version this loader instance targets. */
    String getMinecraftVersion();

    /** The modloader's own version string — Forge's
     *  {@code 14.23.5.2855}, NeoForge's {@code 21.0.123}, Fabric loader's
     *  {@code 0.16.10}. Stable identifier for log lines and the UI. */
    String getLoaderVersion();

    /** Game-arguments string to append to the launch command. Includes
     *  the loader's tweakers / launch targets / etc. Empty when the
     *  loader doesn't need any extra game args. */
    String getMinecraftArguments();

    /** JVM-arguments string to append to the launch command. Modern
     *  Forge / NeoForge use this for module-system flags
     *  ({@code --add-opens}, {@code -p}, etc.); legacy Forge and Fabric
     *  return an empty string. Throws because some loaders re-read the
     *  installer to compute this on demand. */
    String getJvmArguments() throws ModpackException;

    /** Client-mode main class. Resolved from the loader's version
     *  manifest at construction time. */
    String getMinecraftMainClass();

    /**
     * Download and verify every library this loader needs, then return
     * a {@code File.pathSeparator}-joined classpath string suitable for
     * appending to the launcher's vanilla-Minecraft classpath.
     *
     * <p>Implementations are expected to consult
     * {@link com.micatechnologies.minecraft.launcher.files.RuntimeManager}
     * if they need a specific Java runtime for processor execution,
     * but the runtime needed for the GAME launch itself is owned by
     * {@link GameLibraryManifest}.</p>
     */
    String buildClasspath( GameMode mode, GameModPackProgressProvider progress )
            throws ModpackException;

    /**
     * Run any post-download patching the loader needs before the game
     * can be launched. Modern Forge runs its install processors here;
     * NeoForge follows the same shape; Fabric, OpenRGB, and any future
     * "runtime-only" loaders default to a no-op.
     *
     * @param mode game mode (client/server)
     * @param progress UI progress sink
     * @param runtimeComponent the Java runtime component name to use
     *                         for processor execution (Mojang's
     *                         {@code java-runtime-gamma} etc.).
     */
    default void runPostInstallSteps( GameMode mode,
                                       GameModPackProgressProvider progress,
                                       String runtimeComponent ) throws ModpackException
    {
        // Default: no post-install pipeline.
    }

    /**
     * Server-mode main class to launch. Returns {@code null} to fall
     * back to {@link #getMinecraftMainClass()} (the client main); the
     * dispatcher then assumes the loader's client + server entry
     * points are the same class differentiated by launch-target args
     * (modern Forge / NeoForge via {@code BootstrapLauncher} +
     * {@code --launchTarget forgeserver}).
     *
     * <p>Loaders with a distinct server bootstrap class — legacy Forge
     * 1.7-1.12 ({@code ServerLaunchWrapper}), Fabric
     * ({@code FabricServerLauncher}) — override to return it
     * explicitly.</p>
     */
    default String getServerMainClass() {
        return null;
    }

    /**
     * A dedicated server's game arguments, space-separated. Empty by default: legacy Forge's
     * {@code ServerLaunchWrapper} picks its tweaker itself, and modern Forge / NeoForge / Fabric
     * servers need none from the launcher. Cleanroom returns {@code --tweakClass} per
     * {@code serverTweakers} of its install profile.
     *
     * @return the server game arguments, or an empty string
     *
     * @since 2026.11
     */
    default String getServerArguments() {
        return "";
    }

    /**
     * Extra JVM arguments a dedicated server of this loader needs because nobody is at its
     * console, space-separated. Empty by default. Cleanroom returns
     * {@code -Dfml.queryResult=confirm}: without it, a pack lacking Fugue and Scalar stops at a
     * console prompt, the server watchdog kills it, and the restart loop brings it back to the
     * same prompt.
     *
     * @return the server JVM arguments, or an empty string
     *
     * @since 2026.11
     */
    default String getServerJvmArguments() {
        return "";
    }

    /**
     * The loader's standalone version profile, which the launch uses <em>instead of</em> the
     * vanilla client.json: libraries and natives, Java runtime, asset index, client and server
     * jars. {@code null} (the default) for a loader that sits on top of vanilla, as Forge,
     * NeoForge and Fabric do. Cleanroom returns its {@code version.json} without the libraries its
     * installer embeds.
     *
     * @return a copy of the standalone profile the caller may keep, or {@code null}
     *
     * @throws ModpackException if the profile cannot be read
     *
     * @since 2026.11
     */
    default com.google.gson.JsonObject getStandaloneVersionManifest() throws ModpackException {
        return null;
    }
}
