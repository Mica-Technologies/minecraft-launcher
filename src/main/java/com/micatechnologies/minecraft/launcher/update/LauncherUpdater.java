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
import com.micatechnologies.minecraft.launcher.files.LocalPathManager;
import com.micatechnologies.minecraft.launcher.utilities.VersionUtilities;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

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
     * What the launcher does once {@link #install(StagedUpdate)} returns.
     *
     * @since 2026.11
     */
    public enum AfterInstall
    {
        /** The update is in place: restart into it. */
        RELAUNCH,
        /** A helper installs the update once the launcher has exited: quit, and the helper
         *  restarts the launcher if it can. */
        EXIT
    }

    /** Set once a helper that installs the update after exit has been started. */
    private static final AtomicBoolean HANDOFF_SCHEDULED = new AtomicBoolean();

    /**
     * Whether the launcher can install updates itself for this kind.
     *
     * @param kind how the launcher was installed
     *
     * @return {@code true} for kinds with an in-app installer
     *
     * @since 2026.11
     */
    public static boolean installsInApp( InstallKind kind )
    {
        return switch ( kind ) {
            case LINUX_APPIMAGE, LINUX_DEB, LINUX_RPM, MAC_APP_DMG, MAC_APP_PKG -> true;
            default -> false;
        };
    }

    /**
     * Whether installing an update for this kind asks for an administrator's permission. Such
     * updates are never installed on quit, where a password prompt would appear out of nowhere.
     *
     * @param kind how the launcher was installed
     *
     * @return {@code true} when a permission prompt is part of the install
     *
     * @since 2026.11
     */
    public static boolean needsPermission( InstallKind kind )
    {
        return kind == InstallKind.LINUX_DEB || kind == InstallKind.LINUX_RPM || kind == InstallKind.MAC_APP_PKG;
    }

    /**
     * Whether a downloaded update is installed when the launcher quits ("Later"). Only kinds that
     * install without asking anything.
     *
     * @param kind how the launcher was installed
     *
     * @return {@code true} when quitting installs a staged update
     *
     * @since 2026.11
     */
    public static boolean installsOnQuit( InstallKind kind )
    {
        return installsInApp( kind ) && !needsPermission( kind );
    }

    /**
     * Installs the staged update, as the user asked from the update dialog.
     *
     * @param staged the staged update
     *
     * @return whether to restart now or quit and leave the rest to a helper
     *
     * @throws UpdateCancelledException when the user declined the permission prompt
     * @throws IOException              when it cannot be installed; the current version stays
     * @since 2026.11
     */
    public static AfterInstall install( StagedUpdate staged ) throws IOException
    {
        return install( staged, true );
    }

    /**
     * Installs the staged update.
     *
     * @param staged   the staged update
     * @param relaunch whether a helper that runs after exit starts the launcher again
     *
     * @return whether to restart now or quit and leave the rest to a helper
     *
     * @throws IOException when it cannot be installed
     */
    private static AfterInstall install( StagedUpdate staged, boolean relaunch ) throws IOException
    {
        switch ( staged.kind() ) {
            case LINUX_APPIMAGE -> {
                String appImage = System.getenv( "APPIMAGE" );
                if ( appImage == null || appImage.isBlank() ) {
                    throw new IOException( "Not running from an AppImage" );
                }
                AppImageUpdateInstaller.standard().install( staged, Path.of( appImage ),
                                                             LauncherConstants.LAUNCHER_APPLICATION_VERSION );
                return AfterInstall.RELAUNCH;
            }
            case LINUX_DEB, LINUX_RPM -> {
                verify( staged );
                LinuxPackageInstaller.install( staged, ProcessRunner.system() );
                UpdateStager.standard().clear();
                return AfterInstall.RELAUNCH;
            }
            case MAC_APP_DMG -> {
                verify( staged );
                Path bundle = MacUpdateInstaller.bundleOf( System.getProperty( "jpackage.app-path" ) );
                if ( bundle == null ) {
                    throw new IOException( "Not running from an app bundle" );
                }
                MacUpdateInstaller.installDmg( staged.file(), bundle, ProcessHandle.current().pid(), relaunch,
                                               helperLog(), ProcessRunner.system() );
                markHandoffScheduled();
                // The new bundle is copied out; the disk image is no longer needed.
                UpdateStager.standard().clear();
                return AfterInstall.EXIT;
            }
            case MAC_APP_PKG -> {
                verify( staged );
                // The package stays staged until Installer has read it; the next start removes it.
                MacUpdateInstaller.schedulePkg( staged.file(), ProcessHandle.current().pid(), helperLog(),
                                                ProcessRunner.system() );
                markHandoffScheduled();
                return AfterInstall.EXIT;
            }
            default -> throw new IOException( "No in-app installer for " + staged.kind() );
        }
    }

    /** The log the after-exit helpers append their result to, beside the launcher's own logs. */
    private static Path helperLog()
    {
        return Path.of( LocalPathManager.getLauncherLogFolderPath(), "update-install.log" );
    }

    private static void verify( StagedUpdate staged ) throws IOException
    {
        if ( !UpdateStager.standard().verify( staged ) ) {
            throw new UpdateStager.ChecksumMismatchException( "The staged update no longer matches its checksum" );
        }
    }

    /**
     * Run as the launcher quits: installs a staged update that is newer than the running
     * version and was downloaded for this kind of install, so "Later" still updates by the next
     * start, where that needs no permission prompt. A staged update that is not newer than the
     * running version (already installed, or left over) is removed. Failures are left for the next
     * attempt; the staged file stays.
     *
     * @return the version installed, or empty when there was nothing to install
     *
     * @throws IOException when installing failed
     * @since 2026.11
     */
    public static Optional< String > installStagedOnExit() throws IOException
    {
        if ( HANDOFF_SCHEDULED.get() ) {
            return Optional.empty();   // A helper already installs it after this exit.
        }
        InstallKind kind = InstallKind.current( LauncherConstants.LAUNCHER_IS_DEV );
        if ( !installsInApp( kind ) ) {
            return Optional.empty();
        }
        UpdateStager stager = UpdateStager.standard();
        Optional< StagedUpdate > staged = stager.staged();
        if ( staged.isEmpty() ) {
            return Optional.empty();
        }
        if ( isStale( staged.get(), LauncherConstants.LAUNCHER_APPLICATION_VERSION ) ) {
            stager.clear();
            return Optional.empty();
        }
        if ( !installsOnQuit( kind ) || !shouldInstall( staged.get(), kind,
                                                        LauncherConstants.LAUNCHER_APPLICATION_VERSION ) ) {
            return Optional.empty();
        }
        install( staged.get(), false );
        return Optional.of( staged.get().version() );
    }

    /**
     * Records that a helper will install the update once the launcher has exited, so quitting
     * does not start a second one.
     */
    static void markHandoffScheduled()
    {
        HANDOFF_SCHEDULED.set( true );
    }

    /**
     * Whether a staged update is no longer needed: not newer than what runs.
     *
     * @param staged         the staged update
     * @param runningVersion the running version
     *
     * @return {@code true} when it can be removed
     *
     * @since 2026.11
     */
    static boolean isStale( StagedUpdate staged, String runningVersion )
    {
        return VersionUtilities.compareVersionNumbers( runningVersion, staged.version() ) >= 0;
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
