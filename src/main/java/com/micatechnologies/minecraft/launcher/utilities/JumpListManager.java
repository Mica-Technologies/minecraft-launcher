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

package com.micatechnologies.minecraft.launcher.utilities;

import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
import com.micatechnologies.minecraft.launcher.files.Logger;
import org.apache.commons.lang3.SystemUtils;

/**
 * Cross-platform façade for the OS-shell "Recent Modpacks" surface.
 *
 * <ul>
 *   <li><b>Windows:</b> populates the launcher's jump list (taskbar + Start
 *       menu right-click) via {@code ICustomDestinationList} —
 *       implementation in {@link WindowsJumpList}.</li>
 *   <li><b>Linux:</b> rewrites the installed {@code .desktop} file's
 *       {@code Actions=} block so the user's app-menu / dock right-click
 *       surfaces the same recent-modpack list. Delegates to
 *       {@link SchemeRegistrar#refreshRecentPacks()} — the {@code .desktop}
 *       file is the same one SchemeRegistrar writes for the URL-scheme +
 *       MIME registration, so there's only one place that owns the file.</li>
 *   <li><b>macOS:</b> no-op — the macOS dock menu is driven from inside the
 *       running launcher via {@link LauncherActions}, not from a persistent
 *       OS-shell registration.</li>
 * </ul>
 *
 * <p>Safe to call from any thread. Best-effort: failure to refresh the
 * jump list is logged at warning level but never crashes the calling
 * flow.</p>
 *
 * @since 2026.5
 */
public final class JumpListManager
{
    private JumpListManager() { /* static-only */ }

    /**
     * Refreshes the OS-shell recent-modpacks surface to reflect the current
     * play history. Called at launcher startup (right after the URL-scheme
     * registration) and after every successful pack launch.
     */
    public static void refresh()
    {
        try {
            if ( SystemUtils.IS_OS_WINDOWS ) {
                WindowsJumpList.refresh();
            }
            else if ( SystemUtils.IS_OS_LINUX ) {
                // The Linux jump-list-equivalent is the .desktop file's
                // Actions= block. SchemeRegistrar owns that file (it also
                // declares the URL scheme + MIME handler), so route through
                // there to keep a single writer.
                SchemeRegistrar.refreshRecentPacks();
            }
            else if ( SystemUtils.IS_OS_MAC ) {
                // macOS has no persistent OS-shell registration — the dock menu lives in the
                // running launcher. Rebuild + reinstall it so its "Play Recent" submenu picks
                // up the new play history (AWT PopupMenu has no on-show hook to refresh lazily).
                MacOsDockManager.installDockMenu( LauncherActions.buildDockMenu() );
            }
        }
        catch ( Throwable t ) {
            Logger.logWarningSilent( LocalizationManager.format( "log.jumpListManager.refreshFailed",
                                             t.getClass().getSimpleName(), t.getMessage() ) );
        }
    }
}
