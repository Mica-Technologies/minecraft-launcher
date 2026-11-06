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

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Picks the release file that updates a given install. Matches on the end of the file name, which
 * the release workflow keeps stable: {@code ...-user.msi}, {@code ...-system.msi},
 * {@code ...-arm64.dmg}, {@code ...-intel.pkg}, {@code ...-x86_64.AppImage}, {@code ..._amd64.deb},
 * {@code ....x86_64.rpm}, {@code ...-jar-with-dependencies.jar}.
 *
 * @since 2026.11
 */
public final class UpdateAssetSelector
{
    private UpdateAssetSelector() { /* static-only */ }

    /**
     * The processor architectures a release is built for.
     *
     * @since 2026.11
     */
    enum Arch
    {
        X64, ARM64, OTHER;

        /**
         * Reads the {@code os.arch} system property.
         *
         * @param osArch the property, such as {@code amd64}, {@code x86_64} or {@code aarch64}
         *
         * @return the architecture
         */
        static Arch of( String osArch )
        {
            String arch = osArch == null ? "" : osArch.toLowerCase( Locale.ROOT );
            return switch ( arch ) {
                case "amd64", "x86_64", "x64" -> X64;
                case "aarch64", "arm64" -> ARM64;
                default -> OTHER;
            };
        }
    }

    /**
     * The release file that updates the given install, if the release has one. Only files with a
     * SHA-256 are offered: without one the download cannot be checked.
     *
     * @param kind   how the launcher was installed
     * @param osArch the {@code os.arch} system property
     * @param assets the release's files
     *
     * @return the file, or empty when there is none for this install (then link to the release page)
     *
     * @since 2026.11
     */
    public static Optional< ReleaseAsset > select( InstallKind kind, String osArch, List< ReleaseAsset > assets )
    {
        String suffix = suffix( kind, Arch.of( osArch ) );
        if ( suffix == null ) {
            return Optional.empty();
        }
        return assets.stream()
                     .filter( asset -> asset.sha256() != null && asset.nameEndsWith( suffix ) )
                     .findFirst();
    }

    /**
     * The file-name ending for an install kind and architecture.
     *
     * @return the ending, or {@code null} when no release file fits
     */
    static String suffix( InstallKind kind, Arch arch )
    {
        return switch ( kind ) {
            // One x64 MSI for every Windows machine: Windows on Arm runs it emulated.
            case WINDOWS_MSI_USER -> "-user.msi";
            case WINDOWS_MSI_SYSTEM -> "-system.msi";
            case MAC_APP_DMG -> macSuffix( arch, ".dmg" );
            case MAC_APP_PKG -> macSuffix( arch, ".pkg" );
            case LINUX_APPIMAGE -> arch == Arch.X64 ? "-x86_64.AppImage" : null;
            case LINUX_DEB -> arch == Arch.X64 ? "_amd64.deb" : null;
            case LINUX_RPM -> arch == Arch.X64 ? ".x86_64.rpm" : null;
            case JAR -> "-jar-with-dependencies.jar";
            case FLATPAK, UNSUPPORTED -> null;
        };
    }

    private static String macSuffix( Arch arch, String extension )
    {
        return switch ( arch ) {
            case ARM64 -> "-arm64" + extension;
            case X64 -> "-intel" + extension;
            case OTHER -> null;
        };
    }
}
