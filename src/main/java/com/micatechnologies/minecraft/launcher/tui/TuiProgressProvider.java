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

package com.micatechnologies.minecraft.launcher.tui;

import com.micatechnologies.minecraft.launcher.game.modpack.GameModPackProgressProvider;

/**
 * Bridges the launcher's verify/download progress callbacks to the TUI's launch progress dialog.
 * Subclasses {@link GameModPackProgressProvider} (whose single abstract method fires on the launch
 * thread) and forwards each update to a {@link Listener} the TUI supplies — the TUI marshals it onto
 * the Lanterna GUI thread to update the dialog.
 *
 * @since 2026.6
 */
public final class TuiProgressProvider extends GameModPackProgressProvider
{
    /** Receives progress updates (called on the launch thread — implementors must marshal to UI). */
    @FunctionalInterface
    public interface Listener
    {
        void onProgress( double percent, String section, String detail, String downloadStatus );
    }

    private final Listener listener;

    public TuiProgressProvider( Listener listener )
    {
        this.listener = listener;
    }

    @Override
    public void updateProgressHandler( double percent, String sectionTitle, String detailText,
                                       String downloadStatus )
    {
        if ( listener != null ) {
            listener.onProgress( percent, sectionTitle, detailText, downloadStatus );
        }
    }
}
