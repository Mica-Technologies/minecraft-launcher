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

package com.micatechnologies.minecraft.launcher.game.modpack;

/**
 * Pure bucketing rules behind the library's "last played" and "total play time" labels.
 *
 * <p>These decide <em>which</em> localization key applies and <em>what number</em> fills
 * its slot; they never look a string up. That split is deliberate: the bucketing is the
 * part with edge cases worth testing (unit boundaries, singular versus plural, the
 * never-played sentinel), while the lookup is locale-dependent and would force tests to
 * assert English text. Keeping them apart means these tests pass in every locale.</p>
 *
 * <p>Both rules previously lived inline in {@link GameModPackMetadata}, interleaved with
 * {@code LocalizationManager} calls and {@code System.currentTimeMillis()}, so neither the
 * boundaries nor the pluralisation could be tested at all.</p>
 *
 * @since 2026.9
 */
public final class PlayTimeFormatting
{
    /** Private constructor to prevent instantiation of this utility class. */
    private PlayTimeFormatting() { /* static-only */ }

    /**
     * A localization key plus the numeric value that fills its {@code {0}} slot.
     *
     * @param key    the localization key to look up
     * @param amount the value for the key's slot; meaningless for keys that take no slot
     */
    public record Label( String key, double amount ) {}

    // ---------------------------------------------------------------------
    // Last played
    // ---------------------------------------------------------------------

    /**
     * Chooses the relative-time label for a last-played timestamp.
     *
     * <p>Buckets, in order: never (timestamp {@code 0}), under a minute, minutes, hours,
     * then days. Singular and plural keys are distinct because several supported languages
     * inflect differently and a single key with a raw count reads wrong in them.</p>
     *
     * @param lastPlayedMs epoch millis of the last launch, or {@code 0} if never launched
     * @param nowMs        current epoch millis
     *
     * @return the key and slot value to render
     */
    public static Label lastPlayed( long lastPlayedMs, long nowMs )
    {
        if ( lastPlayedMs == 0 ) {
            return new Label( "metadata.lastPlayed.never", 0 );
        }
        long elapsed = nowMs - lastPlayedMs;
        if ( elapsed < 60_000 ) {
            return new Label( "metadata.lastPlayed.justNow", 0 );
        }
        if ( elapsed < 3_600_000 ) {
            long mins = elapsed / 60_000;
            return new Label( mins == 1 ? "metadata.lastPlayed.minuteAgo"
                                        : "metadata.lastPlayed.minutesAgo", mins );
        }
        if ( elapsed < 86_400_000 ) {
            long hours = elapsed / 3_600_000;
            return new Label( hours == 1 ? "metadata.lastPlayed.hourAgo"
                                         : "metadata.lastPlayed.hoursAgo", hours );
        }
        long days = elapsed / 86_400_000;
        return new Label( days == 1 ? "metadata.lastPlayed.dayAgo"
                                    : "metadata.lastPlayed.daysAgo", days );
    }

    // ---------------------------------------------------------------------
    // Total play time
    // ---------------------------------------------------------------------

    /**
     * Chooses the total-play-time label for an accumulated duration.
     *
     * <p>Buckets: zero, minutes (under an hour), hours (under a day), then days. The hour
     * and day buckets carry a fractional amount so the UI can render "3.5 hours"; the
     * minute bucket is whole, and has singular and plural keys.</p>
     *
     * @param totalMs accumulated play time in millis
     *
     * @return the key and slot value to render
     */
    public static Label totalPlayTime( long totalMs )
    {
        if ( totalMs == 0 ) {
            return new Label( "gameModPackMetadata.totalPlayTime.zero", 0 );
        }
        long totalMinutes = totalMs / 60_000;
        if ( totalMinutes < 60 ) {
            return new Label( totalMinutes == 1 ? "gameModPackMetadata.totalPlayTime.minute"
                                                : "gameModPackMetadata.totalPlayTime.minutes",
                              totalMinutes );
        }
        double hours = totalMinutes / 60.0;
        if ( hours < 24 ) {
            return new Label( "gameModPackMetadata.totalPlayTime.hours", hours );
        }
        return new Label( "gameModPackMetadata.totalPlayTime.days", hours / 24.0 );
    }
}
