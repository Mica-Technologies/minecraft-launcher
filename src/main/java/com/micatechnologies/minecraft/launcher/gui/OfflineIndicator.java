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

package com.micatechnologies.minecraft.launcher.gui;

import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
import com.micatechnologies.minecraft.launcher.utilities.NetworkUtilities;
import javafx.scene.control.Label;

/**
 * Shared wiring for the per-screen "Offline" indicator chip.
 *
 * <p>Several screens carry a {@code chip-offline}-styled {@link Label}
 * in their navbar or bottom bar that's hidden when the launcher has
 * network connectivity and visible (with a tooltip explaining the
 * impact) when {@link NetworkUtilities#isOffline()} reports true. The
 * toggle + tooltip text are identical across screens, so they live
 * here rather than getting duplicated in every controller's
 * {@code setup()}.</p>
 *
 * <p>The check is one-shot on the scene's first show. If the network
 * comes back during a session the indicator stays stale until the
 * user navigates between screens (each transition re-runs this
 * helper). That's acceptable for a passive informational chip;
 * adding a property-based live-update mechanism would require
 * plumbing observable state through {@link NetworkUtilities}.</p>
 *
 * @since 2026.5
 */
public final class OfflineIndicator
{
    private OfflineIndicator() { /* static-only */ }

    /**
     * Toggles {@code label} visible / managed based on
     * {@link NetworkUtilities#isOffline()} and installs an explanatory
     * tooltip via {@link TooltipManager}.
     *
     * <p>Safe to call multiple times — TooltipManager.install replaces
     * any prior tooltip on the same node.</p>
     *
     * @param label the FXML-declared label to toggle; no-op when null
     */
    public static void applyTo( Label label )
    {
        if ( label == null ) return;
        boolean offline = NetworkUtilities.isOffline();
        label.setVisible( offline );
        label.setManaged( offline );
        TooltipManager.install( label, LocalizationManager.get( "tooltip.offline" ) );
    }
}
