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

/**
 * Pure arithmetic behind the game console's two independent log buffers.
 *
 * <p>The console keeps a visible {@code TextArea} (capped in <em>lines</em> by the
 * Settings value) and a separate in-memory capture of the full session log (capped in
 * <em>characters</em>). Both caps are bounded-growth policies whose only job is to stop a
 * long session from exhausting heap. That arithmetic used to live inline inside
 * the old game console screen, entangled with JavaFX calls, so it could not be tested and
 * its edge cases were invisible. It now serves {@code GameLog} (the in-memory capture) and
 * {@code GameSessionPane} (the visible log).</p>
 *
 * <p><b>Why this is worth isolating.</b> There is an open report of the launcher dying or
 * disappearing during long sessions — typically 45+ minutes of gameplay, inconsistently.
 * Unbounded growth in one of these buffers is a leading hypothesis, and
 * {@link #isUnlimited(int)} is the branch that makes it possible: the Settings dropdown
 * offers an explicit "unlimited" option, and when it is selected the visible buffer grows
 * for the entire session with no cap at all. Naming that branch and testing it makes the
 * risk explicit rather than implicit in a private method.</p>
 *
 * @since 2026.9
 */
public final class LogTrimPolicy
{
    /**
     * Floor on the trim slack. Without a floor, a small cap (say 200 lines) would give
     * 40 lines of slack and re-trim on nearly every flush.
     */
    public static final int MIN_SLACK = 50;

    /** Private constructor to prevent instantiation of this utility class. */
    private LogTrimPolicy() { /* static-only */ }

    /**
     * Whether the configured line cap means "grow without bound".
     *
     * <p>{@code 0} is the user-selectable "unlimited" preset (see
     * {@code ConfigConstants.CONSOLE_LOG_MAX_LINES_PRESETS}); negative values are treated
     * the same way defensively. When this returns {@code true} nothing ever trims the
     * visible buffer, and a chatty modpack over a long session can put an unbounded amount
     * of text into the scene graph.</p>
     *
     * @param maxLines the configured cap
     *
     * @return {@code true} when no display trimming should ever occur
     */
    public static boolean isUnlimited( int maxLines )
    {
        return maxLines <= 0;
    }

    /**
     * Slack allowed above the cap before a trim fires, so the O(n) scan amortizes instead
     * of running on every flush once the buffer sits at the limit.
     *
     * @param maxLines the configured cap; assumed already checked with {@link #isUnlimited}
     *
     * @return the number of lines of overshoot tolerated
     */
    public static int slackFor( int maxLines )
    {
        return Math.max( MIN_SLACK, maxLines / 5 );
    }

    /**
     * Whether the visible buffer has grown far enough past its cap to justify a trim.
     *
     * @param displayLineCount lines currently displayed
     * @param maxLines         the configured cap ({@code <= 0} means unlimited)
     *
     * @return {@code true} when the caller should trim
     */
    public static boolean shouldTrimDisplay( int displayLineCount, int maxLines )
    {
        if ( isUnlimited( maxLines ) ) {
            return false;
        }
        return displayLineCount > maxLines + slackFor( maxLines );
    }

    /**
     * Character offset marking the end of the first {@code linesToDrop} lines of
     * {@code text}, i.e. how much to delete from the front to drop that many lines.
     *
     * @param text        the current buffer contents
     * @param linesToDrop how many leading lines to remove
     *
     * @return the offset to delete up to (exclusive); {@code 0} when nothing should be
     *         dropped, and never greater than {@code text.length()}
     */
    public static int displayDropOffset( String text, int linesToDrop )
    {
        if ( text == null || text.isEmpty() || linesToDrop <= 0 ) {
            return 0;
        }
        int idx = 0;
        for ( int i = 0; i < linesToDrop && idx < text.length(); i++ ) {
            int next = text.indexOf( '\n', idx );
            if ( next == -1 ) {
                break;
            }
            idx = next + 1;
        }
        return idx;
    }

    /**
     * Character offset to delete from the front of the full-log capture buffer so it drops
     * back to the retain size, trimmed forward to a line boundary so the buffer never
     * starts mid-line.
     *
     * <p>Returns {@code 0} while the buffer is still under the trigger, which is the
     * common case on every appended line.</p>
     *
     * @param buf          the capture buffer
     * @param triggerChars length past which a trim fires
     * @param retainChars  length to drop back to; should be less than {@code triggerChars}
     *
     * @return the offset to delete up to (exclusive), or {@code 0} when no trim is due
     */
    public static int fullLogDropOffset( CharSequence buf, int triggerChars, int retainChars )
    {
        if ( buf == null || buf.length() <= triggerChars ) {
            return 0;
        }
        int dropTo = buf.length() - retainChars;
        if ( dropTo <= 0 ) {
            return 0;
        }
        for ( int i = dropTo; i < buf.length(); i++ ) {
            if ( buf.charAt( i ) == '\n' ) {
                return i + 1;
            }
        }
        // No newline after the drop point — fall back to the raw offset rather than
        // retaining more than asked. Matches the pre-extraction behaviour.
        return dropTo;
    }
}
