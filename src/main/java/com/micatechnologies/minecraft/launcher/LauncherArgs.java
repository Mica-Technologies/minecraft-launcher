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

import com.micatechnologies.minecraft.launcher.consts.LauncherConstants;
import com.micatechnologies.minecraft.launcher.utilities.LauncherUriHandler;

/**
 * Pure decision result of parsing the launcher's command-line arguments.
 *
 * <p>This type exists to separate <em>deciding</em> what the arguments mean from
 * <em>acting</em> on that decision. {@link LauncherCore#parseLauncherArgs(String[])}
 * previously did both at once: it mutated {@link com.micatechnologies.minecraft.launcher.config.GameModeManager},
 * stashed the pending deep-link URI, wrote to the log, and could call
 * {@code closeApp()} — all inline, which made the argument grammar impossible to
 * test without starting a launcher. {@link #parse(String[], boolean)} is a pure
 * function returning this record; the caller applies the side effects.</p>
 *
 * <p>The grammar is small but load-bearing: it fixes the game mode (which in turn
 * selects the config folder), carries the initial modpack selection, and routes
 * {@code mmcl://} deep links. It is also about to grow additional flags, which is
 * the immediate reason for pinning the current behaviour down first.</p>
 *
 * @param modeAction  what to do about the game mode
 * @param modPackSelection the modpack name to pre-select, or {@code ""} when none was given
 * @param pendingUri  the {@code mmcl://} deep link found in argv, or {@code null} when absent
 * @param invalid     {@code true} when the arguments do not match any accepted form, in which
 *                    case the caller should print usage and exit
 *
 * @since 2026.9
 */
public record LauncherArgs( ModeAction modeAction,
                            String modPackSelection,
                            String pendingUri,
                            boolean invalid )
{
    /**
     * What the caller should do about the game mode. Distinct from
     * {@link com.micatechnologies.minecraft.launcher.utilities.objects.GameMode} because two
     * of the outcomes are not modes at all.
     */
    public enum ModeAction
    {
        /** Explicitly select client mode. */
        CLIENT,
        /** Explicitly select server mode. */
        SERVER,
        /** No mode given; the caller should infer it from the environment. */
        INFER,
        /**
         * Leave the current mode untouched.
         *
         * <p>Reached only by the bare {@code launcher.jar <modpack_name>} form, which
         * historically sets no game mode at all — it neither selects one nor infers one.
         * That is preserved here deliberately: it is existing behaviour, and changing it
         * would shift which config folder that invocation resolves to. Worth revisiting
         * on its own, but not as a side effect of an extraction.</p>
         */
        NONE
    }

    /** Convenience accessor for the common "no deep link present" check. */
    public boolean hasPendingUri()
    {
        return pendingUri != null;
    }

    /**
     * Decides what a raw argv means. Pure: touches no singleton, performs no I/O, and
     * never exits the process.
     *
     * <p>Precedence, highest first:</p>
     * <ol>
     *   <li>An {@code mmcl://} deep link anywhere in argv wins outright and implies client
     *       mode. The URI is returned rather than dispatched, because the session must
     *       finish auth and pack loading before the deep-link action can run.</li>
     *   <li>TUI mode ({@code --cli} / {@code --tui}, already detected by the caller) implies
     *       client mode; the last non-flag token becomes the modpack selection.</li>
     *   <li>Otherwise the positional grammar:
     *       {@code [] | -c | -s | <name> | -c <name> | -s <name>}.</li>
     * </ol>
     *
     * @param args       the raw command-line arguments; {@code null} is treated as empty
     * @param tuiEnabled whether TUI mode was requested (captured by the caller before the
     *                   logger reassigns the console streams)
     *
     * @return the parsed decision; never {@code null}
     */
    public static LauncherArgs parse( String[] args, boolean tuiEnabled )
    {
        String[] safeArgs = ( args == null ) ? new String[ 0 ] : args;

        // A deep link anywhere in argv takes precedence over every other form.
        for ( String arg : safeArgs ) {
            if ( LauncherUriHandler.isLauncherUri( arg ) ) {
                return new LauncherArgs( ModeAction.CLIENT, "", arg, false );
            }
        }

        // TUI runs as a normal client; strip the mode flags and take the last bare token.
        if ( tuiEnabled ) {
            String tuiSelection = "";
            for ( String a : safeArgs ) {
                if ( "--cli".equals( a )
                        || "--tui".equals( a )
                        || LauncherConstants.PROGRAM_ARG_CLIENT_MODE.equalsIgnoreCase( a )
                        || LauncherConstants.PROGRAM_ARG_SERVER_MODE.equalsIgnoreCase( a ) ) {
                    continue;
                }
                tuiSelection = a;
            }
            return new LauncherArgs( ModeAction.CLIENT, tuiSelection, null, false );
        }

        if ( safeArgs.length == 0 ) {
            return new LauncherArgs( ModeAction.INFER, "", null, false );
        }
        if ( safeArgs.length == 1 ) {
            if ( LauncherConstants.PROGRAM_ARG_CLIENT_MODE.equalsIgnoreCase( safeArgs[ 0 ] ) ) {
                return new LauncherArgs( ModeAction.CLIENT, "", null, false );
            }
            if ( LauncherConstants.PROGRAM_ARG_SERVER_MODE.equalsIgnoreCase( safeArgs[ 0 ] ) ) {
                return new LauncherArgs( ModeAction.SERVER, "", null, false );
            }
            // Bare modpack name: historically sets no mode at all. See ModeAction.NONE.
            return new LauncherArgs( ModeAction.NONE, safeArgs[ 0 ], null, false );
        }
        if ( safeArgs.length == 2 ) {
            if ( LauncherConstants.PROGRAM_ARG_CLIENT_MODE.equalsIgnoreCase( safeArgs[ 0 ] ) ) {
                return new LauncherArgs( ModeAction.CLIENT, safeArgs[ 1 ], null, false );
            }
            if ( LauncherConstants.PROGRAM_ARG_SERVER_MODE.equalsIgnoreCase( safeArgs[ 0 ] ) ) {
                return new LauncherArgs( ModeAction.SERVER, safeArgs[ 1 ], null, false );
            }
        }
        return new LauncherArgs( ModeAction.NONE, "", null, true );
    }
}
