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

package com.micatechnologies.minecraft.launcher.files;

import com.micatechnologies.minecraft.launcher.config.GameModeManager;
import com.micatechnologies.minecraft.launcher.utilities.objects.GameMode;

/**
 * NOT a JUnit test — a small subprocess entry point used only by
 * {@link LocalPathManagerClientServerPathsTest}.
 *
 * <p>{@link GameModeManager}'s current game mode is process-global, static, mutable state read by
 * every {@link LocalPathManager} getter. No test in this suite mutates it in-process (see the
 * class javadoc on {@code LocalPathManagerClientConfigTest} and the several subprocess harnesses
 * elsewhere in this codebase that document the same constraint) — every other test relies on the
 * ambient default (unset / {@code null}, which resolves like server mode) staying untouched for
 * the lifetime of the shared test JVM. Exercising the {@link GameMode#CLIENT} branch (and
 * {@link GameMode#SERVER} explicitly, for symmetry) therefore has to happen in a short-lived child
 * JVM instead.</p>
 */
public final class LocalPathManagerSubprocessHarness
{
    private LocalPathManagerSubprocessHarness() { /* entry point only */ }

    /**
     * Sets the game mode from {@code args[0]} ({@code "client"} or {@code "server"}), then prints
     * one {@code LABEL:value} line per {@link LocalPathManager} getter so the parent test can
     * assert on the composed paths.
     *
     * @param args {@code args[0]} must be {@code "client"} or {@code "server"}
     */
    public static void main( String[] args )
    {
        GameMode mode = "client".equals( args[ 0 ] ) ? GameMode.CLIENT : GameMode.SERVER;
        GameModeManager.setCurrentGameMode( mode );

        System.out.println( "LOCAL:" + LocalPathManager.getLauncherLocalPath() );
        System.out.println( "CONFIG:" + LocalPathManager.getLauncherConfigFolderPath() );
        System.out.println( "METADATA:" + LocalPathManager.getLauncherMetadataFolderPath() );
        System.out.println( "MODPACK:" + LocalPathManager.getLauncherModpackFolderPath() );
        System.out.println( "LOG:" + LocalPathManager.getLauncherLogFolderPath() );
        System.out.println( "RUNTIME:" + LocalPathManager.getLauncherRuntimeFolderPath() );
        System.out.println( "SHARED_ASSETS:" + LocalPathManager.getLauncherSharedAssetsFolderPath() );
        System.out.println( "CLIENT_TOKEN:" + LocalPathManager.getClientTokenFilePath() );
        System.out.println( "REMEMBERED_ACCOUNT:" + LocalPathManager.getRememberedAccountFilePath() );
        System.out.println( "UPDATE_INFO:" + LocalPathManager.getUpdateInfoFilePath() );
        System.out.println( "VERSION_MANIFEST:" + LocalPathManager.getMinecraftVersionManifestFilePath() );
    }
}
