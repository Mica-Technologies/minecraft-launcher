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

package com.micatechnologies.minecraft.launcher.update;

import com.micatechnologies.minecraft.launcher.consts.LauncherConstants;
import com.micatechnologies.minecraft.launcher.utilities.VersionUtilities;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Decides how an available launcher update reaches this install, and installs one that is staged
 * when the launcher quits. The GUI ({@code MCLauncherUpdateDialog}) drives the download.
 *
 * @since 2026.11
 */
public final class LauncherUpdater
{
    private LauncherUpdater() { /* static-only */ }

    /**
     * How an update reaches an install.
     *
     * @since 2026.11
     */
    public enum Route
    {
        /** The launcher downloads the update and installs it on restart or quit. */
        IN_APP,
        /** The install is a Flatpak: the software store updates it (plan decision D3). */
        SOFTWARE_STORE,
        /** Open the release page; the user downloads and installs it. */
        RELEASE_PAGE
    }

    /**
     * The route for an install kind, given the release file chosen for it.
     *
     * @param kind  how the launcher was installed
     * @param asset the release file for that install, if the release has one with a checksum
     *
     * @return the route
     *
     * @since 2026.11
     */
    public static Route routeFor( InstallKind kind, Optional< ReleaseAsset > asset )
    {
        if ( kind == InstallKind.FLATPAK ) {
            return Route.SOFTWARE_STORE;
        }
        return asset.isPresent() && installsInApp( kind ) ? Route.IN_APP : Route.RELEASE_PAGE;
    }

    /**
     * Whether the launcher can install updates itself for this kind yet. Grows as each
     * platform's installer lands (plan phases 3 to 6).
     *
     * @param kind how the launcher was installed
     *
     * @return {@code true} for kinds with an in-app installer
     *
     * @since 2026.11
     */
    public static boolean installsInApp( InstallKind kind )
    {
        return kind == InstallKind.LINUX_APPIMAGE;
    }

    /**
     * Installs the staged update now, so it runs from the next start. For the AppImage this swaps
     * the file (see {@link AppImageUpdateInstaller}); the running launcher is unaffected.
     *
     * @param staged the staged update
     *
     * @throws IOException when it cannot be installed; the current version stays in place
     * @since 2026.11
     */
    public static void install( StagedUpdate staged ) throws IOException
    {
        if ( staged.kind() != InstallKind.LINUX_APPIMAGE ) {
            throw new IOException( "No in-app installer for " + staged.kind() );
        }
        String appImage = System.getenv( "APPIMAGE" );
        if ( appImage == null || appImage.isBlank() ) {
            throw new IOException( "Not running from an AppImage" );
        }
        AppImageUpdateInstaller.standard().install( staged, Path.of( appImage ),
                                                     LauncherConstants.LAUNCHER_APPLICATION_VERSION );
    }

    /**
     * Run as the launcher quits: installs a staged update that is newer than the running
     * version and was downloaded for this kind of install, so "Later" still updates by the next
     * start. Failures are left for the next attempt; the staged file stays.
     *
     * @return the version installed, or empty when there was nothing to install
     *
     * @throws IOException when installing failed
     * @since 2026.11
     */
    public static Optional< String > installStagedOnExit() throws IOException
    {
        InstallKind kind = InstallKind.current( LauncherConstants.LAUNCHER_IS_DEV );
        if ( !installsInApp( kind ) ) {
            return Optional.empty();
        }
        Optional< StagedUpdate > staged = UpdateStager.standard().staged();
        if ( staged.isEmpty() || !shouldInstall( staged.get(), kind,
                                                 LauncherConstants.LAUNCHER_APPLICATION_VERSION ) ) {
            return Optional.empty();
        }
        install( staged.get() );
        return Optional.of( staged.get().version() );
    }

    /**
     * Whether a staged update belongs on this install: same kind, and newer than what runs.
     *
     * @param staged         the staged update
     * @param kind           this install's kind
     * @param runningVersion the running version
     *
     * @return {@code true} when it should be installed
     *
     * @since 2026.11
     */
    static boolean shouldInstall( StagedUpdate staged, InstallKind kind, String runningVersion )
    {
        return staged.kind() == kind
               && VersionUtilities.compareVersionNumbers( runningVersion, staged.version() ) < 0;
    }
}
