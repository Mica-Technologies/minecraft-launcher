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

package com.micatechnologies.minecraft.launcher;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link LauncherArgs#parse(String[], boolean)} — the launcher's command-line
 * grammar.
 *
 * <p>Why this matters: the parsed result fixes the game mode, and the game mode selects
 * which folder the launcher reads its configuration from. Getting it wrong does not throw
 * — it silently points the launcher at the wrong config directory, which historically
 * produced a stale empty config that no amount of user activity could persist correctly
 * (see the warnings in {@code LauncherCore.main}). It also routes {@code mmcl://} deep
 * links, which are attacker-reachable from any web page.</p>
 *
 * <p>The grammar was untestable until now because parsing and acting were interleaved in
 * {@code LauncherCore.parseLauncherArgs}: it mutated the game-mode singleton, wrote to the
 * log, and could terminate the process. {@code LauncherArgs.parse} is the pure half.</p>
 */
class LauncherArgsTest
{
    private static final boolean NO_TUI = false;
    private static final boolean TUI    = true;

    // =========================================================================
    //  No arguments
    // =========================================================================

    @Test
    void noArgumentsInfersTheGameMode()
    {
        LauncherArgs a = LauncherArgs.parse( new String[ 0 ], NO_TUI );
        assertEquals( LauncherArgs.ModeAction.INFER, a.modeAction() );
        assertEquals( "", a.modPackSelection() );
        assertFalse( a.invalid() );
        assertFalse( a.hasPendingUri() );
    }

    @Test
    void nullArgumentsAreTreatedAsEmpty()
    {
        assertEquals( LauncherArgs.ModeAction.INFER,
                      LauncherArgs.parse( null, NO_TUI ).modeAction() );
    }

    // =========================================================================
    //  Explicit mode flags
    // =========================================================================

    @Test
    void clientFlagAloneSelectsClientMode()
    {
        LauncherArgs a = LauncherArgs.parse( new String[]{ "-c" }, NO_TUI );
        assertEquals( LauncherArgs.ModeAction.CLIENT, a.modeAction() );
        assertEquals( "", a.modPackSelection() );
    }

    @Test
    void serverFlagAloneSelectsServerMode()
    {
        LauncherArgs a = LauncherArgs.parse( new String[]{ "-s" }, NO_TUI );
        assertEquals( LauncherArgs.ModeAction.SERVER, a.modeAction() );
        assertEquals( "", a.modPackSelection() );
    }

    @Test
    void modeFlagsAreCaseInsensitive()
    {
        assertEquals( LauncherArgs.ModeAction.CLIENT,
                      LauncherArgs.parse( new String[]{ "-C" }, NO_TUI ).modeAction() );
        assertEquals( LauncherArgs.ModeAction.SERVER,
                      LauncherArgs.parse( new String[]{ "-S" }, NO_TUI ).modeAction() );
    }

    @Test
    void clientFlagWithModpackSelectsBoth()
    {
        LauncherArgs a = LauncherArgs.parse( new String[]{ "-c", "All the Mods 9" }, NO_TUI );
        assertEquals( LauncherArgs.ModeAction.CLIENT, a.modeAction() );
        assertEquals( "All the Mods 9", a.modPackSelection() );
        assertFalse( a.invalid() );
    }

    @Test
    void serverFlagWithModpackSelectsBoth()
    {
        LauncherArgs a = LauncherArgs.parse( new String[]{ "-s", "SkyFactory" }, NO_TUI );
        assertEquals( LauncherArgs.ModeAction.SERVER, a.modeAction() );
        assertEquals( "SkyFactory", a.modPackSelection() );
    }

    // =========================================================================
    //  Bare modpack name
    // =========================================================================

    /**
     * Pins long-standing behaviour that is arguably a bug: the bare
     * {@code launcher.jar <modpack_name>} form selects a modpack but sets no game mode at
     * all — it neither picks one nor infers one. Whatever mode the process already had
     * stands, which for a cold start means "unset". Preserved deliberately during the
     * extraction; changing it would move which config folder this invocation resolves to,
     * and that deserves its own commit.
     */
    @Test
    void bareModpackNameSetsNoModeAtAll()
    {
        LauncherArgs a = LauncherArgs.parse( new String[]{ "MyPack" }, NO_TUI );
        assertEquals( LauncherArgs.ModeAction.NONE, a.modeAction(),
                      "if this now infers or selects a mode, the historical quirk was "
                      + "intentionally fixed — update this test" );
        assertEquals( "MyPack", a.modPackSelection() );
        assertFalse( a.invalid() );
    }

    // =========================================================================
    //  Invalid forms
    // =========================================================================

    @Test
    void twoBarePositionalsAreInvalid()
    {
        LauncherArgs a = LauncherArgs.parse( new String[]{ "one", "two" }, NO_TUI );
        assertTrue( a.invalid() );
    }

    @Test
    void threeOrMoreArgumentsAreInvalid()
    {
        assertTrue( LauncherArgs.parse( new String[]{ "-c", "a", "b" }, NO_TUI ).invalid() );
    }

    @Test
    void invalidFormSelectsNoModeAndNoModpack()
    {
        LauncherArgs a = LauncherArgs.parse( new String[]{ "one", "two" }, NO_TUI );
        assertEquals( LauncherArgs.ModeAction.NONE, a.modeAction() );
        assertEquals( "", a.modPackSelection() );
    }

    // =========================================================================
    //  Deep links
    // =========================================================================

    @Test
    void deepLinkIsCapturedAndImpliesClientMode()
    {
        LauncherArgs a = LauncherArgs.parse( new String[]{ "mmcl://play?name=Test" }, NO_TUI );
        assertTrue( a.hasPendingUri() );
        assertEquals( "mmcl://play?name=Test", a.pendingUri() );
        assertEquals( LauncherArgs.ModeAction.CLIENT, a.modeAction() );
        assertEquals( "", a.modPackSelection(),
                      "a deep link never carries a modpack pre-selection" );
        assertFalse( a.invalid() );
    }

    /**
     * The deep link wins from any position in argv, and beats forms that would otherwise
     * be rejected as invalid. The OS scheme handler controls argv layout, so the parser
     * cannot assume the URI arrives first.
     */
    @Test
    void deepLinkWinsFromAnyPositionAndOverridesOtherForms()
    {
        LauncherArgs a = LauncherArgs.parse(
                new String[]{ "-s", "somepack", "mmcl://add?url=x" }, NO_TUI );
        assertTrue( a.hasPendingUri() );
        assertEquals( LauncherArgs.ModeAction.CLIENT, a.modeAction() );
        assertFalse( a.invalid(), "a deep link must not be rejected by the positional grammar" );
    }

    @Test
    void nonLauncherUriIsNotTreatedAsADeepLink()
    {
        LauncherArgs a = LauncherArgs.parse( new String[]{ "https://example.com" }, NO_TUI );
        assertFalse( a.hasPendingUri() );
        assertNull( a.pendingUri() );
    }

    // =========================================================================
    //  TUI mode
    // =========================================================================

    @Test
    void tuiModeImpliesClientMode()
    {
        LauncherArgs a = LauncherArgs.parse( new String[]{ "--tui" }, TUI );
        assertEquals( LauncherArgs.ModeAction.CLIENT, a.modeAction() );
        assertEquals( "", a.modPackSelection() );
    }

    @Test
    void tuiModeStripsFlagsAndKeepsTheBareToken()
    {
        LauncherArgs a = LauncherArgs.parse( new String[]{ "--tui", "-c", "MyPack" }, TUI );
        assertEquals( LauncherArgs.ModeAction.CLIENT, a.modeAction() );
        assertEquals( "MyPack", a.modPackSelection() );
    }

    @Test
    void tuiModeAcceptsTheCliAlias()
    {
        assertEquals( "MyPack",
                      LauncherArgs.parse( new String[]{ "--cli", "MyPack" }, TUI )
                                  .modPackSelection() );
    }

    /**
     * Pins current behaviour: TUI parsing takes the <em>last</em> bare token rather than
     * rejecting multiple, so extra positionals silently win over earlier ones instead of
     * producing a usage error.
     */
    @Test
    void tuiModeTakesTheLastBareTokenWhenSeveralAreGiven()
    {
        assertEquals( "second",
                      LauncherArgs.parse( new String[]{ "--tui", "first", "second" }, TUI )
                                  .modPackSelection() );
    }

    @Test
    void tuiModeNeverReportsInvalid()
    {
        assertFalse( LauncherArgs.parse( new String[]{ "--tui", "a", "b", "c" }, TUI ).invalid() );
    }

    @Test
    void deepLinkStillWinsOverTuiMode()
    {
        LauncherArgs a = LauncherArgs.parse( new String[]{ "--tui", "mmcl://open" }, TUI );
        assertTrue( a.hasPendingUri() );
    }
}
