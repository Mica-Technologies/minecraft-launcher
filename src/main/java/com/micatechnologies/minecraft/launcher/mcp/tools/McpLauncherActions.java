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

/**
 * The state-changing operations MCP tools are allowed to perform.
 * <p>
 * The read-only counterpart is {@link McpLauncherView}, and the split is deliberate: a tool
 * holding only the view <em>cannot</em> change anything, which makes "is this tool read-only?"
 * a question about its dependencies rather than about reading its body. Every tool that takes
 * this interface is one the approval engine must gate.
 * <p>
 * Implementations are called from the single-threaded tool executor, so they need not guard
 * against concurrent calls to themselves — but they must not block indefinitely, and they must
 * never throw for an ordinary failure. A pack that will not install is an {@link Outcome} the
 * model can read and react to; an exception is not.
 *
 * @author Mica Technologies
 * @version 1.0
 * @since 3.0
 */
public interface McpLauncherActions
{
    /**
     * What happened.
     *
     * @param ok      whether the operation succeeded
     * @param message a human-readable description, phrased for a model to act on
     *
     * @since 3.0
     */
    record Outcome( boolean ok, String message )
    {
        /**
         * Builds a success.
         *
         * @param message what happened
         *
         * @return the outcome
         *
         * @since 3.0
         */
        public static Outcome ok( String message )
        {
            return new Outcome( true, message == null ? "" : message );
        }

        /**
         * Builds a failure.
         *
         * @param message why, in terms the caller can act on
         *
         * @return the outcome
         *
         * @since 3.0
         */
        public static Outcome failed( String message )
        {
            return new Outcome( false, message == null ? "" : message );
        }
    }

    /**
     * Installs a modpack from a manifest URL.
     * <p>
     * The URL has already passed {@code LauncherUriHandler.classifyInstallUrl} before this is
     * reached — see {@code McpTool.validateBeforeApproval} — so implementations do not repeat
     * that check, but they also must not assume the URL is <em>trusted</em>, only that it is
     * not outright rejected.
     *
     * @param url the manifest URL
     *
     * @return what happened
     *
     * @since 3.0
     */
    Outcome installFromUrl( String url );

    /**
     * Uninstalls a modpack, deleting its files.
     *
     * @param friendlyName the pack to remove
     *
     * @return what happened
     *
     * @since 3.0
     */
    Outcome uninstall( String friendlyName );

    /**
     * Launches a modpack.
     *
     * @param friendlyName the pack to launch
     * @param account      the username of the signed-in account to play as, or {@code null} for
     *                     the pack's usual account (its override, else the default)
     *
     * @return what happened — success means the launch was <em>started</em>, not that the game
     *         has finished loading
     *
     * @since 3.0
     */
    Outcome launch( String friendlyName, String account );

    /**
     * Why a launch can't start right now, checked before asking the user to approve it: the
     * pack is already running, the account is already playing another pack, or the account
     * isn't signed in.
     *
     * @param friendlyName the pack to launch
     * @param account      the requested account's username, or {@code null}
     *
     * @return the reason, or {@code null} when nothing blocks it
     *
     * @since 2026.10
     */
    String whyLaunchBlocked( String friendlyName, String account );

    /**
     * Terminates a running game.
     *
     * @param friendlyName the pack whose game to stop
     *
     * @return what happened
     *
     * @since 3.0
     */
    Outcome stopGame( String friendlyName );

    /**
     * Creates a new, empty modpack and installs it locally.
     *
     * @param name           the new pack's name
     * @param modLoader      the mod loader identifier ({@code forge} / {@code neoforge} /
     *                       {@code fabric}), or {@code null} for the default
     * @param modLoaderUrl   the loader installer URL, or {@code null} to leave it unset
     *
     * @return what happened
     *
     * @since 3.0
     */
    Outcome createPack( String name, String modLoader, String modLoaderUrl );

    /**
     * Copies an existing pack under a new name and installs the copy.
     *
     * @param sourceFriendlyName the pack to copy
     * @param newName            the copy's name
     *
     * @return what happened
     *
     * @since 3.0
     */
    Outcome forkPack( String sourceFriendlyName, String newName );

    /**
     * Adds a mod to a pack's manifest.
     *
     * @param friendlyName the pack to edit
     * @param modName      the mod's display name
     * @param remoteUrl    where to download it
     * @param localPath    where it installs, relative to the pack root
     *
     * @return what happened
     *
     * @since 3.0
     */
    Outcome addMod( String friendlyName, String modName, String remoteUrl, String localPath );

    /**
     * Removes a mod from a pack's manifest.
     *
     * @param friendlyName the pack to edit
     * @param identifier   the mod's local path, display name, or Modrinth slug
     *
     * @return what happened
     *
     * @since 3.0
     */
    Outcome removeMod( String friendlyName, String identifier );
}
