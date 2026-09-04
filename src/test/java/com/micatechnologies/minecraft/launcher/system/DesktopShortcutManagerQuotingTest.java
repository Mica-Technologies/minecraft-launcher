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

/**
 * Tests for the shell/command-line quoting helpers {@link DesktopShortcutManager} uses to
 * embed a <b>server-supplied</b> modpack name (and, on Windows, a launcher/JAR path) into a
 * generated script or shortcut without letting it execute as code:
 * {@code shellSingleQuote} (macOS {@code launch.sh}), {@code desktopExecQuote} (Linux
 * {@code .desktop} {@code Exec=} line), {@code windowsCmdQuote} (Windows {@code .lnk}
 * {@code Arguments}), and {@code stripLineTerminators} (the pre-pass shared by all three
 * platforms that keeps an embedded newline from splitting a single field into a forged
 * second record/line).
 *
 * <p>Every one of these exists because {@code pack.getPackName()} comes from a modpack
 * manifest the launcher does not otherwise trust. A gap in any of them is a command
 * injection: a modpack author (or a compromised/MITM'd manifest host) could get arbitrary
 * shell commands to run on a user's machine the moment they create a desktop shortcut for
 * that pack.</p>
 */
class DesktopShortcutManagerQuotingTest
{
    // ============================================================= stripLineTerminators

    @Test
    void nullInputReturnsEmptyString()
    {
        assertEquals( "", DesktopShortcutManager.stripLineTerminators( null ) );
    }

    @Test
    void plainTextPassesThroughUnchanged()
    {
        assertEquals( "SkyFactory 5", DesktopShortcutManager.stripLineTerminators( "SkyFactory 5" ) );
    }

    @Test
    void carriageReturnLineFeedAndNulAreAllRemoved()
    {
        assertEquals( "abc", DesktopShortcutManager.stripLineTerminators( "a\rb\nc" ) );
        assertEquals( "abc", DesktopShortcutManager.stripLineTerminators( "a\u0000bc" ) );
        assertEquals( "abc", DesktopShortcutManager.stripLineTerminators( "a\r\n\u0000bc" ) );
    }

    @Test
    void aForgedExecLineInjectionAttemptCollapsesToOneLine()
    {
        // Without stripping, this would turn a single Name= field in a .desktop file into
        // a second Exec= record controlling what actually runs.
        String malicious = "Innocent Pack\nExec=rm -rf ~";
        assertEquals( "Innocent PackExec=rm -rf ~", DesktopShortcutManager.stripLineTerminators( malicious ) );
    }

    // =================================================================== shellSingleQuote

    @Test
    void shellSingleQuoteNullBecomesEmptyQuotedString()
    {
        assertEquals( "''", DesktopShortcutManager.shellSingleQuote( null ) );
    }

    @Test
    void shellSingleQuoteWrapsPlainTextInQuotes()
    {
        assertEquals( "'SkyFactory'", DesktopShortcutManager.shellSingleQuote( "SkyFactory" ) );
    }

    @Test
    void shellSingleQuoteEscapesEmbeddedSingleQuotesPosixStyle()
    {
        assertEquals( "'Bob'\\''s Pack'", DesktopShortcutManager.shellSingleQuote( "Bob's Pack" ) );
    }

    /**
     * A malicious pack name containing a command substitution / semicolon must come out
     * still wrapped in single quotes with no unescaped {@code '} to break out of them —
     * i.e. it must remain inert data to the shell that later runs {@code exec <this>}.
     */
    @Test
    void shellSingleQuoteNeutralizesCommandInjectionAttempt()
    {
        String malicious = "pack'; rm -rf ~ #";
        String quoted = DesktopShortcutManager.shellSingleQuote( malicious );
        assertEquals( "'pack'\\''; rm -rf ~ #'", quoted );
    }

    @Test
    void shellSingleQuoteDoesNotInterpretDollarSignsOrBackticks()
    {
        // Single quotes suppress all shell expansion, so these characters need no escaping
        // at all -- they pass through literally inside the quotes.
        assertEquals( "'$(whoami) && `id`'", DesktopShortcutManager.shellSingleQuote( "$(whoami) && `id`" ) );
    }

    // =================================================================== desktopExecQuote

    @Test
    void desktopExecQuoteNullBecomesEmptyQuotedString()
    {
        assertEquals( "\"\"", DesktopShortcutManager.desktopExecQuote( null ) );
    }

    @Test
    void desktopExecQuoteWrapsPlainTextInDoubleQuotes()
    {
        assertEquals( "\"SkyFactory\"", DesktopShortcutManager.desktopExecQuote( "SkyFactory" ) );
    }

    @Test
    void desktopExecQuoteEscapesTheFourSpecialCharacters()
    {
        // ", `, $, \  each get a leading backslash per the Desktop Entry Specification.
        String input = "a\"b`c$d\\e";
        assertEquals( "\"a\\\"b\\`c\\$d\\\\e\"", DesktopShortcutManager.desktopExecQuote( input ) );
    }

    @Test
    void desktopExecQuoteNeutralizesCommandSubstitutionAttempt()
    {
        String malicious = "$(rm -rf ~)";
        assertEquals( "\"\\$(rm -rf ~)\"", DesktopShortcutManager.desktopExecQuote( malicious ) );
    }

    @Test
    void desktopExecQuoteNeutralizesBacktickInjectionAttempt()
    {
        String malicious = "`rm -rf ~`";
        assertEquals( "\"\\`rm -rf ~\\`\"", DesktopShortcutManager.desktopExecQuote( malicious ) );
    }

    // =================================================================== windowsCmdQuote

    @Test
    void windowsCmdQuoteNullBecomesEmptyQuotedString()
    {
        assertEquals( "\"\"", DesktopShortcutManager.windowsCmdQuote( null ) );
    }

    @Test
    void windowsCmdQuoteWrapsPlainTextInDoubleQuotes()
    {
        assertEquals( "\"SkyFactory\"", DesktopShortcutManager.windowsCmdQuote( "SkyFactory" ) );
    }

    @Test
    void windowsCmdQuoteEscapesEmbeddedDoubleQuotes()
    {
        // pack" --launcher-flag "  ->  the embedded quote must not terminate the argument
        // early -- CommandLineToArgvW must see it as one literal argument.
        assertEquals( "\"pack\\\" --launcher-flag \\\"\"",
                      DesktopShortcutManager.windowsCmdQuote( "pack\" --launcher-flag \"" ) );
    }

    @Test
    void windowsCmdQuoteDoublesBackslashesOnlyWhenImmediatelyBeforeAQuote()
    {
        // A lone trailing backslash before the closing quote must be doubled so
        // CommandLineToArgvW doesn't read it as escaping that closing quote.
        assertEquals( "\"C:\\path\\\\\"", DesktopShortcutManager.windowsCmdQuote( "C:\\path\\" ) );
        // Backslashes NOT adjacent to a quote are left alone (no doubling needed).
        assertEquals( "\"C:\\path\\to\\file\"", DesktopShortcutManager.windowsCmdQuote( "C:\\path\\to\\file" ) );
    }

    @Test
    void windowsCmdQuoteHandlesBackslashesImmediatelyPrecedingAnEmbeddedQuote()
    {
        // Each backslash directly before an embedded " must be doubled, and the quote
        // itself escaped, per CommandLineToArgvW's rules.
        assertEquals( "\"a\\\\\\\"b\"", DesktopShortcutManager.windowsCmdQuote( "a\\\"b" ) );
    }
}
