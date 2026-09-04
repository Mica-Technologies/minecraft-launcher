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

package com.micatechnologies.minecraft.launcher.system;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link DesktopShortcutManager#sanitizeFileName(String)} — the
 * injection defense used before a server-supplied modpack name is turned
 * into an on-disk shortcut file name ({@code createShortcut} feeds it
 * {@code pack.getPackName()} straight from the modpack manifest, which the
 * launcher does not otherwise trust). A regression here could let a
 * malicious manifest author write a shortcut file outside the intended
 * Desktop directory (via a path separator) or corrupt the on-disk file
 * system entry with characters the host OS rejects.
 *
 * <p>Reading the implementation shows it does exactly one thing:
 * replace the nine characters Windows/macOS/Linux forbid in a single
 * path segment ({@code < > : " / \ | ? *}) with {@code _}, then
 * {@link String#trim()} the result. It does <b>not</b> strip control
 * characters, does <b>not</b> collapse {@code ".."}, and throws a
 * {@link NullPointerException} on {@code null} input — this test class
 * pins down that actual behaviour rather than the behaviour one might
 * assume from the method's javadoc ("removing characters that are
 * invalid in file names").</p>
 */
class DesktopShortcutManagerSanitizeTest
{
    @Test
    void ordinaryNamePassesThroughUnchanged()
    {
        assertEquals( "My Modpack 1.0", DesktopShortcutManager.sanitizeFileName( "My Modpack 1.0" ) );
    }

    @Test
    void forwardSlashIsReplacedWithUnderscore()
    {
        assertEquals( "a_b", DesktopShortcutManager.sanitizeFileName( "a/b" ) );
    }

    @Test
    void backslashIsReplacedWithUnderscore()
    {
        assertEquals( "a_b", DesktopShortcutManager.sanitizeFileName( "a\\b" ) );
    }

    @Test
    void allNineForbiddenCharactersAreReplaced()
    {
        // < > : " / \ | ? *
        String input = "<>:\"/\\|?*";
        assertEquals( "_________", DesktopShortcutManager.sanitizeFileName( input ) );
    }

    @Test
    void pathTraversalSeparatorsAreNeutralized()
    {
        // The ".." text itself is untouched (no forbidden chars in it), but the
        // path separators around it are replaced, so the traversal can no
        // longer walk up a directory once this becomes a single file-name
        // segment passed to `new File(desktopDir, shortcutName)`.
        assertEquals( ".._.._etc", DesktopShortcutManager.sanitizeFileName( "../../etc" ) );
    }

    @Test
    void bareDotDotIsNotStrippedBecauseItContainsNoForbiddenCharacter()
    {
        // Documents a real gap: sanitizeFileName only strips the nine
        // OS-forbidden characters. Two literal dots with no separator around
        // them are not among those characters, so they survive verbatim.
        assertEquals( "..", DesktopShortcutManager.sanitizeFileName( ".." ) );
    }

    @Test
    void controlCharactersAreNotStripped()
    {
        // BUG / surprising behaviour: despite the javadoc's "removing
        // characters that are invalid in file names" framing, ASCII control
        // characters (here, a raw NUL) are not in the forbidden-character
        // regex and pass straight through unmodified.
        String input = "name\u0000withNul";
        assertEquals( "name\u0000withNul", DesktopShortcutManager.sanitizeFileName( input ) );
    }

    @Test
    void carriageReturnAndLineFeedAreNotStripped()
    {
        // Same gap as above, specifically for CR/LF — a name containing an
        // embedded newline is returned unchanged rather than sanitized.
        String input = "line1\r\nline2";
        assertEquals( "line1\r\nline2", DesktopShortcutManager.sanitizeFileName( input ) );
    }

    @Test
    void leadingAndTrailingWhitespaceIsTrimmed()
    {
        assertEquals( "trimmed", DesktopShortcutManager.sanitizeFileName( "  trimmed  " ) );
    }

    @Test
    void emptyStringInputProducesEmptyStringOutput()
    {
        assertEquals( "", DesktopShortcutManager.sanitizeFileName( "" ) );
    }

    @Test
    void whitespaceOnlyInputProducesEmptyOutput()
    {
        // BUG / surprising behaviour: sanitizeFileName does NOT guarantee a
        // non-empty result for non-empty input. An all-whitespace name
        // contains no forbidden characters (so nothing gets replaced with
        // "_"), and the subsequent trim() then strips it down to "" — a
        // non-empty input yielding an empty shortcut file name.
        assertEquals( "", DesktopShortcutManager.sanitizeFileName( "   " ) );
    }

    @Test
    void nullInputThrowsNullPointerException()
    {
        // BUG / surprising behaviour: there is no null-guard, so
        // sanitizeFileName(null) throws NPE rather than returning e.g. "" or
        // a placeholder name. Pinning down the actual (crashing) behaviour.
        assertThrows( NullPointerException.class, () -> DesktopShortcutManager.sanitizeFileName( null ) );
    }

    @Test
    void nameThatIsOnlyForbiddenCharactersStaysNonEmpty()
    {
        // Contrast with the whitespace-only case above: forbidden characters
        // become underscores (not removed), so trim() has nothing to strip
        // and the result stays non-empty.
        assertEquals( "___", DesktopShortcutManager.sanitizeFileName( "///" ) );
    }
}
