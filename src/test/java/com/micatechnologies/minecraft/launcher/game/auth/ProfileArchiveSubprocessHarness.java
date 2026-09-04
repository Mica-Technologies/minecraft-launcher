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

package com.micatechnologies.minecraft.launcher.game.auth;

import java.util.List;

/**
 * NOT a JUnit test — a small subprocess entry point used only by
 * {@link ProfileArchiveTest}.
 *
 * <p>{@link ProfileArchive} resolves every path it touches through
 * {@code LocalPathManager.getLauncherConfigFolderPath()}. Under a unit-test
 * JVM, {@code GameModeManager} never has a game mode set, so that resolves
 * to {@code <current working directory>/config} — the server-mode fallback
 * (see {@code LocalPathConstants.SERVER_MODE_LAUNCHER_FOLDER_PATH}, which is
 * a {@code static final} snapshotted from {@code Paths.get("")} at class-load
 * time). Calling {@link ProfileArchive}'s archive/list/activate/forget
 * methods directly from the test process would therefore read and write a
 * stray {@code config/profiles/} tree in whatever directory Maven happens to
 * run from, not inside a JUnit {@code @TempDir}.
 *
 * <p>Running the real calls in a short-lived child JVM whose working
 * directory is pinned to a {@code @TempDir} confines every read/write to the
 * sandbox while still exercising the exact production code path end to end.
 * The parent test inspects the resulting files directly on disk (they live
 * under the same {@code @TempDir} the test passed as the child's working
 * directory) and also reads back a handful of {@code LABEL:value} lines on
 * stdout for the values {@link ProfileArchive} returns in memory.</p>
 */
public final class ProfileArchiveSubprocessHarness
{
    private ProfileArchiveSubprocessHarness() { /* entry point only */ }

    /**
     * Dispatches to one of the harness modes based on {@code args[0]}.
     *
     * <ul>
     *   <li>{@code archive <uuid> <displayName>} — calls
     *       {@link ProfileArchive#archiveActive}. {@code displayName} of
     *       {@code -} is passed through as {@code null}. Prints
     *       {@code ARCHIVE:NULL} or
     *       {@code ARCHIVE:OK:<uuid>:<displayName>:<lastUsedMs>}.</li>
     *   <li>{@code list} — calls {@link ProfileArchive#list}. Prints
     *       {@code LIST_COUNT:<size>} followed by one
     *       {@code LIST_ENTRY:<uuid>:<displayName>:<lastUsedMs>} line per
     *       entry, in the order returned (already sorted by the production
     *       code).</li>
     *   <li>{@code activate <uuid>} — calls {@link ProfileArchive#activate}.
     *       Prints {@code ACTIVATE:<bool>}.</li>
     *   <li>{@code forget <uuid>} — calls {@link ProfileArchive#forget}.
     *       Prints {@code FORGET:<bool>}.</li>
     * </ul>
     *
     * @param args harness mode followed by its argument(s)
     */
    public static void main( String[] args )
    {
        String mode = args.length > 0 ? args[ 0 ] : "";
        switch ( mode )
        {
            case "archive" -> runArchive( args[ 1 ], "-".equals( args[ 2 ] ) ? null : args[ 2 ] );
            case "list" -> runList();
            case "activate" -> runActivate( args[ 1 ] );
            case "forget" -> runForget( args[ 1 ] );
            default -> System.out.println( "UNKNOWN_MODE:" + mode );
        }
    }

    private static void runArchive( String uuid, String displayName )
    {
        ProfileArchive.ProfileEntry entry = ProfileArchive.archiveActive( uuid, displayName );
        if ( entry == null ) {
            System.out.println( "ARCHIVE:NULL" );
        }
        else {
            System.out.println( "ARCHIVE:OK:" + entry.uuid() + ":" + entry.displayName() + ":" + entry.lastUsedMs() );
        }
    }

    private static void runList()
    {
        List< ProfileArchive.ProfileEntry > entries = ProfileArchive.list();
        System.out.println( "LIST_COUNT:" + entries.size() );
        for ( ProfileArchive.ProfileEntry entry : entries ) {
            System.out.println( "LIST_ENTRY:" + entry.uuid() + ":" + entry.displayName() + ":" + entry.lastUsedMs() );
        }
    }

    private static void runActivate( String uuid )
    {
        System.out.println( "ACTIVATE:" + ProfileArchive.activate( uuid ) );
    }

    private static void runForget( String uuid )
    {
        System.out.println( "FORGET:" + ProfileArchive.forget( uuid ) );
    }
}
