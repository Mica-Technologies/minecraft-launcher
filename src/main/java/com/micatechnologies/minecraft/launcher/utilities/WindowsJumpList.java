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

/**
 * Windows jump-list management via {@code ICustomDestinationList} COM.
 * Populates the launcher's taskbar + Start-menu right-click jump list
 * with a "Recent Modpacks" category sourced from {@link RecentPacks}.
 *
 * <p>Stub for now — full COM wiring via JNA lands in a follow-up commit.
 * {@link #refresh()} is currently a no-op so the JumpListManager façade
 * can ship on Linux first; Windows users continue to use the existing
 * system-tray right-click "Play Last" until the full implementation
 * lands.</p>
 *
 * @since 2026.5
 */
final class WindowsJumpList
{
    private WindowsJumpList() { /* static-only */ }

    /**
     * Rebuilds the launcher's jump list to reflect the current recently-
     * played modpacks. Currently a no-op — see class doc.
     */
    static void refresh()
    {
        // Intentionally empty until the ICustomDestinationList JNA bindings
        // are committed. The Linux .desktop Actions= path already handles
        // its half of the OS-shell recents surface.
    }
}
