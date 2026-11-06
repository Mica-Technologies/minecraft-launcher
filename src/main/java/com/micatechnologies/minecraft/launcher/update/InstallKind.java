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

import org.apache.commons.lang3.SystemUtils;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * How this copy of the launcher was installed, which decides which release file replaces it and
 * how. Each kind maps to one release asset (see {@link UpdateAssetSelector}).
 *
 * @since 2026.11
 */
public enum InstallKind
{
    /** The per-user MSI, under {@code %LOCALAPPDATA%\Programs}; installs without UAC. */
    WINDOWS_MSI_USER( true ),
    /** The per-machine MSI, under Program Files; installing needs UAC. */
    WINDOWS_MSI_SYSTEM( true ),
    /** A macOS app bundle the user can write (dragged in from the DMG): replaced from the DMG. */
    MAC_APP_DMG( true ),
    /** A macOS app bundle the user cannot write (installed by the PKG as root): updated by the PKG. */
    MAC_APP_PKG( true ),
    /** The Linux AppImage, a single file in the user's home folder. */
    LINUX_APPIMAGE( true ),
    /** The Debian / Ubuntu package under {@code /opt}. */
    LINUX_DEB( true ),
    /** The RPM package under {@code /opt}. */
    LINUX_RPM( true ),
    /** A Flatpak: Flatpak / the software store updates it, never the launcher. */
    FLATPAK( false ),
    /** The fat jar, run with the user's own Java. */
    JAR( true ),
    /** An IDE run, a DEV build, or anything not recognized: never updated. */
    UNSUPPORTED( false );

    /** The jpackage launcher's path inside a Linux package. */
    private static final String LINUX_PACKAGE_ROOT = "/opt/";

    /** dpkg's file list for each package name the Linux packaging uses. */
    private static final String[] DPKG_LISTS = { "/var/lib/dpkg/info/mica-minecraft-launcher.list",
                                                 "/var/lib/dpkg/info/mica-minecraft-launcher-dev.list" };

    private final boolean selfUpdating;

    InstallKind( boolean selfUpdating )
    {
        this.selfUpdating = selfUpdating;
    }

    /**
     * Whether the launcher may download and install updates for this kind of install. Flatpaks
     * are updated by Flatpak, and unrecognized installs are never touched.
     *
     * @return {@code true} when the launcher handles its own updates
     *
     * @since 2026.11
     */
    public boolean selfUpdating()
    {
        return selfUpdating;
    }

    /**
     * What is known about the running launcher, gathered in one place so detection is a pure
     * function and testable on any platform.
     *
     * @param osName     the {@code os.name} system property
     * @param env        the environment ({@code APPIMAGE}, {@code FLATPAK_ID}, {@code LOCALAPPDATA})
     * @param appPath    the jpackage launcher ({@code jpackage.app-path}), or {@code null} without one
     * @param codeSource the launcher's jar or classes folder, or {@code null} when unknown
     * @param dev        whether this is a DEV build, which never updates
     * @param exists     whether a path exists
     * @param writable   whether the user can write a path
     *
     * @since 2026.11
     */
    public record Facts( String osName, Map< String, String > env, String appPath, String codeSource, boolean dev,
                         Predicate< Path > exists, Predicate< Path > writable )
    {
    }

    /**
     * The install kind of the running launcher.
     *
     * @param dev whether this is a DEV build
     *
     * @return the kind; {@link #UNSUPPORTED} when it cannot be told
     *
     * @since 2026.11
     */
    public static InstallKind current( boolean dev )
    {
        String codeSource = null;
        try {
            codeSource = Path.of( InstallKind.class.getProtectionDomain().getCodeSource().getLocation().toURI() )
                             .toString();
        }
        catch ( Exception ignored ) {
            // Unknown: treated as an unrecognized install.
        }
        return detect( new Facts( SystemUtils.OS_NAME, System.getenv(), System.getProperty( "jpackage.app-path" ),
                                  codeSource, dev, Files::exists, Files::isWritable ) );
    }

    /**
     * Works out the install kind from the given facts.
     *
     * @param facts what is known about the running launcher
     *
     * @return the kind; {@link #UNSUPPORTED} when it cannot be told
     *
     * @since 2026.11
     */
    public static InstallKind detect( Facts facts )
    {
        if ( facts.dev() ) {
            return UNSUPPORTED;
        }
        String os = facts.osName() == null ? "" : facts.osName().toLowerCase( Locale.ROOT );
        if ( os.startsWith( "linux" ) ) {
            // The Flatpak and AppImage runtimes say so in the environment; both wrap an app image,
            // so they must be checked before the package layout.
            if ( present( facts.env().get( "FLATPAK_ID" ) ) ) {
                return FLATPAK;
            }
            if ( present( facts.env().get( "APPIMAGE" ) ) ) {
                return LINUX_APPIMAGE;
            }
        }
        String appPath = facts.appPath();
        if ( !present( appPath ) ) {
            String codeSource = facts.codeSource();
            return codeSource != null && codeSource.toLowerCase( Locale.ROOT ).endsWith( ".jar" ) ? JAR : UNSUPPORTED;
        }
        if ( os.startsWith( "windows" ) ) {
            String localAppData = facts.env().get( "LOCALAPPDATA" );
            boolean perUser = present( localAppData ) && appPath.toLowerCase( Locale.ROOT )
                                                                .startsWith( withSeparator( localAppData, '\\' )
                                                                                     .toLowerCase( Locale.ROOT ) );
            return perUser ? WINDOWS_MSI_USER : WINDOWS_MSI_SYSTEM;
        }
        if ( os.startsWith( "mac" ) ) {
            int bundleEnd = appPath.indexOf( ".app/Contents/" );
            if ( bundleEnd < 0 ) {
                return UNSUPPORTED;
            }
            Path bundle = path( appPath.substring( 0, bundleEnd + ".app".length() ) );
            if ( bundle == null ) {
                return UNSUPPORTED;
            }
            // Replacing a bundle means writing the folder it sits in, not just the bundle.
            Path parent = bundle.getParent();
            return facts.writable().test( bundle ) && parent != null && facts.writable().test( parent ) ? MAC_APP_DMG
                                                                                                         : MAC_APP_PKG;
        }
        if ( os.startsWith( "linux" ) ) {
            if ( !appPath.startsWith( LINUX_PACKAGE_ROOT ) ) {
                return UNSUPPORTED;
            }
            for ( String list : DPKG_LISTS ) {
                Path listPath = path( list );
                if ( listPath != null && facts.exists().test( listPath ) ) {
                    return LINUX_DEB;
                }
            }
            return LINUX_RPM;
        }
        return UNSUPPORTED;
    }

    private static boolean present( String value )
    {
        return value != null && !value.isBlank();
    }

    private static String withSeparator( String folder, char separator )
    {
        return folder.endsWith( "\\" ) || folder.endsWith( "/" ) ? folder : folder + separator;
    }

    private static Path path( String value )
    {
        try {
            return Path.of( value );
        }
        catch ( InvalidPathException e ) {
            return null;
        }
    }
}
