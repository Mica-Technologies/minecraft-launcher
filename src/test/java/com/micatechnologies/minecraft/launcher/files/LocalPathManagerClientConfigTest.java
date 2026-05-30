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
import com.micatechnologies.minecraft.launcher.consts.LocalPathConstants;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Regression guard for {@link LocalPathManager#getClientConfigFolderPath()}.
 *
 * <p>The single-instance IPC token is persisted from {@code LauncherCore.main()}, which runs
 * before {@code parseLauncherArgs} sets the game mode — so at that point
 * {@code GameModeManager.getCurrentGameMode()} is still {@code null} and the
 * game-mode-sensitive {@link LocalPathManager#getLauncherConfigFolderPath()} resolves to the
 * server-mode (current-working-directory) path. A second forwarding process spawned by the OS
 * scheme handler has a different working directory, so a CWD-relative token path never matched
 * between the two processes and {@code mmcl://} forwarding to an already-open launcher silently
 * failed. {@code getClientConfigFolderPath()} exists precisely so the token path is stable
 * regardless of game mode; these tests lock that in.</p>
 *
 * <p>Deliberately does <b>not</b> mutate {@link GameModeManager}'s global game-mode state — the
 * tests run under the suite's ambient default (game mode unset / {@code null}), which is exactly
 * the {@code main()}-entry condition that produced the bug. That keeps the test self-contained
 * and leak-free across sibling tests.</p>
 */
class LocalPathManagerClientConfigTest
{
    /** Sanity: the suite runs with the game mode unset — the same state as {@code main()} entry,
     *  where {@code isClient()} is {@code false} and the mode-sensitive getter falls back to the
     *  server/CWD path. If this ever changes, the contrast assertions below must be revisited. */
    @Test
    void gameModeIsUnsetUnderTest()
    {
        assertNull( GameModeManager.getCurrentGameMode(),
                    "Expected game mode to be unset; another test may be leaking global state." );
    }

    /** The client config path is the fixed user-home location, independent of game mode. */
    @Test
    void clientConfigPathIsUserHomeAnchored()
    {
        assertEquals( LocalPathConstants.CLIENT_MODE_LAUNCHER_FOLDER_PATH + LocalPathConstants.CONFIG_FOLDER,
                      LocalPathManager.getClientConfigFolderPath() );
    }

    /**
     * The crux of the bug: with the game mode unset (the {@code main()} condition), the
     * mode-sensitive getter resolves to the CWD-based server path, while the client-config
     * getter stays anchored to the user-home client folder. The IPC token must use the latter
     * so both the running launcher and the forwarding second process agree on its location.
     */
    @Test
    void clientConfigPathDivergesFromModeSensitiveGetterWhenModeUnset()
    {
        String clientConfig = LocalPathManager.getClientConfigFolderPath();
        String modeSensitive = LocalPathManager.getLauncherConfigFolderPath();

        assertNotNull( clientConfig );
        assertEquals( LocalPathConstants.CLIENT_MODE_LAUNCHER_FOLDER_PATH + LocalPathConstants.CONFIG_FOLDER,
                      clientConfig );
        // Mode unset -> mode-sensitive getter points at the current working directory.
        assertEquals( LocalPathConstants.SERVER_MODE_LAUNCHER_FOLDER_PATH + LocalPathConstants.CONFIG_FOLDER,
                      modeSensitive );
        // The two must differ here — that mismatch is exactly what broke IPC forwarding when the
        // token used the mode-sensitive getter. (CWD != user-home/.MicaMinecraftLauncher[DEV].)
        assertNotEquals( clientConfig, modeSensitive );
    }
}
