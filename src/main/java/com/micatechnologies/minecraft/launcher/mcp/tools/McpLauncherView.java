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

package com.micatechnologies.minecraft.launcher.mcp.tools;

import java.util.List;

/**
 * The read-only slice of launcher state the MCP tools are allowed to see.
 * <p>
 * Every read-only tool depends on this rather than on {@code GameModPackManager},
 * {@code MCLauncherAuthManager}, and friends directly. Two reasons, in order of importance.
 * <p>
 * <b>It bounds what a tool can reach.</b> The types below carry no tokens, no account UUID, and
 * no filesystem paths — a tool holding this interface has no way to obtain them, so the plan's
 * section 5.6 invariant is a property of the type rather than a rule an author has to remember.
 * The signed-in <em>username</em> is present because it is the one piece of account context
 * that is genuinely useful and is not a credential.
 * <p>
 * <b>It makes the tools testable.</b> The live implementation touches process-global
 * singletons that a unit test cannot stand up; a stub implementing this interface lets every
 * tool's formatting and error handling be exercised headlessly.
 *
 * @author Mica Technologies
 * @version 1.0
 * @since 3.0
 */
public interface McpLauncherView
{
    /**
     * One modpack, as an MCP client sees it.
     *
     * @param friendlyName    the pack's display name, and the key every tool addresses it by
     * @param version         the version actually installed, or the published version for a
     *                        pack that is not installed; {@code ""} when unknown
     * @param latestVersion   the version the pack's author currently publishes, or {@code ""}
     * @param updateAvailable whether {@code latestVersion} is newer than what is installed
     * @param modLoader       the mod loader identifier, or {@code ""} when unknown
     * @param installed       whether the pack is installed locally
     * @param unstable        whether the pack is flagged unstable by its author
     *
     * @since 3.0
     */
    record PackSummary( String friendlyName, String version, String latestVersion,
                        boolean updateAvailable, String modLoader, boolean installed,
                        boolean unstable )
    {
    }

    /**
     * Launcher-level status.
     *
     * @param launcherVersion     the running launcher version
     * @param signedIn            whether an account is signed in
     * @param username            the signed-in account's username, or {@code ""} when signed
     *                            out. <b>Never a UUID and never a token</b> — see section 5.6
     * @param installedPackCount  how many packs are installed
     *
     * @since 3.0
     */
    record LauncherStatus( String launcherVersion, boolean signedIn, String username,
                           int installedPackCount )
    {
    }

    /**
     * A crash report and what the launcher makes of it.
     *
     * @param reportText  the raw crash report text, or {@code ""} when there is none
     * @param title       the diagnosis title, or {@code ""} when undiagnosed
     * @param summary     the diagnosis summary, or {@code ""} when undiagnosed
     * @param category    the failure family, or {@code ""} when undiagnosed
     * @param suggestions human-readable suggested fixes, possibly empty
     *
     * @since 3.0
     */
    record CrashInfo( String reportText, String title, String summary, String category,
                      List< String > suggestions )
    {
    }

    /**
     * What a modpack occupies on disk, for a destructive-action prompt.
     *
     * @param sizeBytes   total bytes under the pack folder
     * @param worldCount  how many worlds are saved inside it
     * @param approximate whether the walk was cut short, making {@code sizeBytes} a lower
     *                    bound rather than an exact figure
     *
     * @since 3.0
     */
    record PackFootprint( long sizeBytes, int worldCount, boolean approximate )
    {
    }

    /**
     * Measures what a pack occupies on disk.
     * <p>
     * Called when building a destructive consent prompt, so implementations must bound the
     * work rather than walking an arbitrarily large tree to completion.
     *
     * @param friendlyName the pack to measure
     *
     * @return the footprint, or {@code null} when it cannot be determined
     *
     * @since 3.0
     */
    PackFootprint footprintOf( String friendlyName );

    /**
     * Returns every pack the launcher knows about, installed and available.
     *
     * @return the packs; empty when none are known
     *
     * @since 3.0
     */
    List< PackSummary > packs();

    /**
     * Returns one pack by friendly name.
     *
     * @param friendlyName the pack to look up
     *
     * @return the pack, or {@code null} when no pack has that name
     *
     * @since 3.0
     */
    PackSummary pack( String friendlyName );

    /**
     * Returns a pack's raw manifest JSON.
     *
     * @param friendlyName the pack to read
     *
     * @return the manifest text, or {@code null} when the pack is unknown or its manifest
     *         cannot be read
     *
     * @since 3.0
     */
    String manifestOf( String friendlyName );

    /**
     * Returns a pack's most recent crash report and diagnosis.
     *
     * @param friendlyName the pack to read
     *
     * @return the crash information, or {@code null} when the pack is unknown or has never
     *         crashed
     *
     * @since 3.0
     */
    CrashInfo latestCrashOf( String friendlyName );

    /**
     * Returns launcher-level status.
     *
     * @return the status; never {@code null}
     *
     * @since 3.0
     */
    LauncherStatus status();
}
