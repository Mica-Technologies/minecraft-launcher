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

import java.text.NumberFormat;

/**
 * Localized, human-readable byte sizes and transfer rates for the UI ({@code "27.1 MB"},
 * {@code "812 KB"}, {@code "1.4 MB/s"}).
 *
 * <p>The number is formatted with the active UI locale's decimal separator and the unit comes
 * from the {@code units.*} bundle keys, so a French UI reads {@code "27,1 Mo"}. Precision is
 * fixed per unit: whole bytes and kilobytes, one decimal for megabytes, two for gigabytes.
 * Sizes use binary multiples (1 KB = 1024 bytes), matching what the launcher has always
 * shown.</p>
 *
 * <p>Not for machine-facing text (logs parsed by tools, MCP output): those should stay
 * locale-independent.</p>
 *
 * @author Mica Technologies
 * @since 2026.10
 */
public final class UnitFormatter
{
    private static final double KIB = 1024.0;
    private static final double MIB = KIB * 1024;
    private static final double GIB = MIB * 1024;

    private UnitFormatter()
    {
    }

    /**
     * Formats a byte count, e.g. {@code "945 B"}, {@code "812 KB"}, {@code "27.1 MB"},
     * {@code "1.50 GB"}. Negative counts are treated as zero.
     *
     * @param bytes the size in bytes
     *
     * @return the localized size
     *
     * @since 2026.10
     */
    public static String bytes( long bytes )
    {
        long b = Math.max( 0, bytes );
        if ( b < KIB ) {
            return LocalizationManager.format( "units.bytes", number( b, 0 ) );
        }
        if ( b < MIB ) {
            return LocalizationManager.format( "units.kilobytes", number( b / KIB, 0 ) );
        }
        if ( b < GIB ) {
            return LocalizationManager.format( "units.megabytes", number( b / MIB, 1 ) );
        }
        return LocalizationManager.format( "units.gigabytes", number( b / GIB, 2 ) );
    }

    /**
     * Formats a transfer rate, e.g. {@code "512 B/s"}, {@code "84.0 KB/s"}, {@code "2.4 MB/s"}.
     *
     * @param bytesPerSecond the rate in bytes per second
     *
     * @return the localized rate
     *
     * @since 2026.10
     */
    public static String bytesPerSecond( double bytesPerSecond )
    {
        double r = Math.max( 0, bytesPerSecond );
        if ( r < KIB ) {
            return LocalizationManager.format( "units.bytesPerSecond", number( r, 0 ) );
        }
        if ( r < MIB ) {
            return LocalizationManager.format( "units.kilobytesPerSecond", number( r / KIB, 1 ) );
        }
        return LocalizationManager.format( "units.megabytesPerSecond", number( r / MIB, 1 ) );
    }

    /**
     * Formats {@code value} with exactly {@code decimals} fraction digits in the active UI
     * locale. Returned as a string so MessageFormat inserts it verbatim instead of reformatting.
     */
    private static String number( double value, int decimals )
    {
        NumberFormat nf = NumberFormat.getNumberInstance( LocalizationManager.currentLocale() );
        nf.setMinimumFractionDigits( decimals );
        nf.setMaximumFractionDigits( decimals );
        nf.setGroupingUsed( false );
        return nf.format( value );
    }
}
