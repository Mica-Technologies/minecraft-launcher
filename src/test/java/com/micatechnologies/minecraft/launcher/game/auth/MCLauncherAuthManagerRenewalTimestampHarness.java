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

/**
 * NOT a JUnit test — a small subprocess entry point used only by
 * {@link MCLauncherAuthManagerRenewalTimestampTest} to exercise
 * {@code MCLauncherAuthManager#readRenewalTimestamp}'s primary
 * successful-decrypt branch, which requires a real machine-key file.
 *
 * <p>The parent test first runs
 * {@code com.micatechnologies.minecraft.launcher.utilities.MachineSecretCipherSubprocessHarness}
 * with {@code encrypt} in a child JVM whose working directory is a shared
 * {@code @TempDir}, producing a real envelope bound to that temp
 * install's machine key. This harness is then run with its working
 * directory pinned to that <em>same</em> {@code @TempDir}, so the
 * per-install secret file it reads matches, and decryption succeeds via the
 * exact production code path.</p>
 */
public final class MCLauncherAuthManagerRenewalTimestampHarness
{
    private MCLauncherAuthManagerRenewalTimestampHarness() { /* entry point only */ }

    /**
     * {@code read <raw>} — calls {@code MCLauncherAuthManager.readRenewalTimestamp(raw)}
     * and prints {@code RESULT:<value>}.
     *
     * @param args {@code "read"} followed by the raw on-disk timestamp string
     */
    public static void main( String[] args )
    {
        String mode = args.length > 0 ? args[ 0 ] : "";
        if ( "read".equals( mode ) ) {
            long result = MCLauncherAuthManager.readRenewalTimestamp( args[ 1 ] );
            System.out.println( "RESULT:" + result );
        }
        else {
            System.out.println( "UNKNOWN_MODE:" + mode );
        }
    }
}
