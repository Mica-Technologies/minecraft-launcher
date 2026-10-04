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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.launcher.game.modpack.ModPackDocument;
import com.micatechnologies.minecraft.launcher.mcp.approval.McpRiskClass;
import com.micatechnologies.minecraft.launcher.utilities.LauncherUriHandler;

import java.util.List;

/**
 * The MCP tools that change state — the plan's sections 7.2, 7.3 and 7.4.
 * <p>
 * Every tool here depends on {@link McpLauncherActions}, which is the whole point of that
 * interface existing separately from {@link McpLauncherView}: a tool's risk class is visible
 * from what it is constructed with, not only from what its body does.
 * <p>
 * <b>None of these can run unattended by default.</b> Their risk classes resolve to
 * {@code ASK}, so each call raises a consent dialog naming the client, the tool, and the
 * arguments, unless the user has deliberately said otherwise in Settings — and a launcher with
 * no GUI to ask denies rather than assuming.
 *
 * @author Mica Technologies
 * @version 1.0
 * @since 3.0
 */
public final class MutatingTools
{
    /**
     * Registers every state-changing tool.
     *
     * @param registry the registry to populate
     * @param view     read-only launcher state, for existence checks
     * @param actions  the state-changing operations
     *
     * @throws IllegalArgumentException if any argument is {@code null}
     * @since 3.0
     */
    public static void registerAll( McpToolRegistry registry, McpLauncherView view,
                                    McpLauncherActions actions )
    {
        if ( registry == null || view == null || actions == null ) {
            throw new IllegalArgumentException( "A registry, launcher view and actions are required" );
        }
        registry.register( new InstallModpack( view, actions ) );
        registry.register( new CreateModpack( view, actions ) );
        registry.register( new ForkModpack( view, actions ) );
        registry.register( new AddModToModpack( view, actions ) );
        registry.register( new RemoveModFromModpack( view, actions ) );
        registry.register( new UninstallModpack( view, actions ) );
        registry.register( new LaunchModpack( view, actions ) );
        registry.register( new StopGame( view, actions ) );
    }

    /** Shared plumbing for the state-changing tools. */
    private abstract static class ActionTool implements McpTool
    {
        /** Read-only launcher state, for existence checks before acting. */
        protected final McpLauncherView view;

        /** The state-changing operations. */
        protected final McpLauncherActions actions;

        /**
         * Constructs a tool.
         *
         * @param view    read-only launcher state
         * @param actions the state-changing operations
         */
        ActionTool( McpLauncherView view, McpLauncherActions actions )
        {
            this.view = view;
            this.actions = actions;
        }

        /**
         * Turns an outcome into a tool result.
         *
         * @param outcome what happened
         *
         * @return the result
         */
        protected static McpToolResult resultOf( McpLauncherActions.Outcome outcome )
        {
            if ( outcome == null ) {
                return McpToolResult.error( "The operation reported no outcome." );
            }
            return outcome.ok() ? McpToolResult.text( outcome.message() )
                                : McpToolResult.error( outcome.message() );
        }

        /**
         * Reads a string argument.
         *
         * @param arguments the call arguments
         * @param key       the argument name
         *
         * @return the trimmed value, or {@code ""} when absent or not a primitive
         */
        protected static String stringArg( JsonObject arguments, String key )
        {
            if ( arguments == null || !arguments.has( key ) || !arguments.get( key ).isJsonPrimitive() ) {
                return "";
            }
            return arguments.get( key ).getAsString().trim();
        }

        /**
         * Builds a JSON Schema object from alternating name/description pairs, with every
         * property required.
         *
         * @param nameThenDescription alternating property names and descriptions
         *
         * @return the schema
         */
        protected static JsonObject schemaOf( String... nameThenDescription )
        {
            JsonObject properties = new JsonObject();
            JsonArray required = new JsonArray();
            for ( int i = 0; i + 1 < nameThenDescription.length; i += 2 ) {
                JsonObject property = new JsonObject();
                property.addProperty( "type", "string" );
                property.addProperty( "description", nameThenDescription[ i + 1 ] );
                properties.add( nameThenDescription[ i ], property );
                required.add( nameThenDescription[ i ] );
            }
            JsonObject schema = new JsonObject();
            schema.addProperty( "type", "object" );
            schema.add( "properties", properties );
            schema.add( "required", required );
            return schema;
        }

        /**
         * Builds the "no such pack" message, naming the tool that lists them.
         *
         * @param friendlyName the name that was not found
         *
         * @return the message
         */
        protected static String unknownPack( String friendlyName )
        {
            return "No modpack named \"" + friendlyName + "\". Call list_modpacks to see the "
                    + "available friendly names.";
        }

        /**
         * Checks that a name is usable for a new pack.
         * <p>
         * A new pack's manifest file is named from the letters and digits of its name,
         * lower-cased, so "my-pack" and "My Pack" would write the same file. An exact-name
         * comparison would let the second silently replace the first's manifest; comparing
         * the sanitized form refuses it before the user is ever asked.
         *
         * @param name the requested name
         *
         * @return why the name is refused, or {@code null} when it is free
         */
        protected String newPackNameRejection( String name )
        {
            String key = ModPackDocument.localManifestKeyOf( name );
            if ( key.isEmpty() ) {
                return "The name \"" + name + "\" has no letters or digits. Pick a name with at "
                        + "least one.";
            }
            for ( McpLauncherView.PackSummary pack : view.packs() ) {
                if ( key.equals( ModPackDocument.localManifestKeyOf( pack.friendlyName() ) ) ) {
                    return pack.friendlyName().equals( name )
                           ? "A modpack named \"" + name + "\" already exists. Pick a different name."
                           : "\"" + name + "\" is too close to the existing modpack \""
                                   + pack.friendlyName() + "\": names that differ only in case, "
                                   + "spacing or punctuation would share a manifest file. Pick a "
                                   + "more distinct name.";
                }
            }
            return null;
        }
    }

    /** Installs a modpack from a manifest URL. */
    private static final class InstallModpack extends ActionTool
    {
        InstallModpack( McpLauncherView view, McpLauncherActions actions ) { super( view, actions ); }

        @Override
        public String name() { return "install_modpack"; }

        @Override
        public String title() { return "Install modpack"; }

        @Override
        public String description()
        {
            return "Installs a modpack from a manifest URL. The URL must be https and is "
                    + "checked against the launcher's install-source rules before the user is "
                    + "asked to approve. Downloads mods and configuration from third-party "
                    + "hosts.";
        }

        @Override
        public JsonObject inputSchema()
        {
            return schemaOf( "url", "The https URL of the modpack manifest to install." );
        }

        @Override
        public McpRiskClass riskClass() { return McpRiskClass.MUTATING; }

        /**
         * Applies the launcher's existing install-source gate before the user is asked.
         * <p>
         * A URL the launcher already refuses — non-https, malformed, control characters — must
         * not become a consent prompt. {@code REQUIRE_CONFIRMATION} deliberately does
         * <em>not</em> reject here: an untrusted-but-well-formed host is exactly what the
         * consent dialog is for, and refusing it outright would make the tool useless for any
         * pack not on the allowlist.
         */
        @Override
        public String validateBeforeApproval( JsonObject arguments )
        {
            String url = stringArg( arguments, "url" );
            if ( url.isEmpty() ) {
                return "A url argument is required.";
            }
            if ( LauncherUriHandler.classifyInstallUrl( url )
                    == LauncherUriHandler.InstallUrlVerdict.REJECT ) {
                return "That install URL was refused by the launcher's install-source rules. "
                        + "Modpack URLs must be well-formed https links.";
            }
            return null;
        }

        @Override
        public McpToolResult invoke( McpCallContext context, JsonObject arguments )
        {
            return resultOf( actions.installFromUrl( stringArg( arguments, "url" ) ) );
        }
    }

    /** Creates a new, empty modpack. */
    private static final class CreateModpack extends ActionTool
    {
        CreateModpack( McpLauncherView view, McpLauncherActions actions ) { super( view, actions ); }

        @Override
        public String name() { return "create_modpack"; }

        @Override
        public String title() { return "Create modpack"; }

        @Override
        public String description()
        {
            return "Creates a new, empty modpack with the given name and installs it locally, "
                    + "ready to have mods added. Optionally sets the mod loader and its "
                    + "installer URL.";
        }

        @Override
        public JsonObject inputSchema()
        {
            JsonObject schema = schemaOf( "name", "The new modpack's name." );
            JsonObject loader = new JsonObject();
            loader.addProperty( "type", "string" );
            loader.addProperty( "description",
                                "Mod loader: forge, neoforge, or fabric. Defaults to forge." );
            JsonObject loaderUrl = new JsonObject();
            loaderUrl.addProperty( "type", "string" );
            loaderUrl.addProperty( "description", "URL of the mod loader installer." );
            schema.getAsJsonObject( "properties" ).add( "modLoader", loader );
            schema.getAsJsonObject( "properties" ).add( "modLoaderUrl", loaderUrl );
            return schema;
        }

        @Override
        public McpRiskClass riskClass() { return McpRiskClass.MUTATING; }

        @Override
        public String validateBeforeApproval( JsonObject arguments )
        {
            String name = stringArg( arguments, "name" );
            if ( name.isEmpty() ) {
                return "A name argument is required.";
            }
            // The loader installer is downloaded and run when the pack is first played, so its
            // URL gets the same install-source rules as a modpack or mod URL.
            String loaderUrl = stringArg( arguments, "modLoaderUrl" );
            if ( !loaderUrl.isEmpty() && LauncherUriHandler.classifyInstallUrl( loaderUrl )
                    == LauncherUriHandler.InstallUrlVerdict.REJECT ) {
                return "That mod loader URL was refused by the launcher's install-source rules. "
                        + "Mod loader URLs must be well-formed https links.";
            }
            return newPackNameRejection( name );
        }

        @Override
        public McpToolResult invoke( McpCallContext context, JsonObject arguments )
        {
            return resultOf( actions.createPack( stringArg( arguments, "name" ),
                                                 emptyToNull( stringArg( arguments, "modLoader" ) ),
                                                 emptyToNull( stringArg( arguments, "modLoaderUrl" ) ) ) );
        }
    }

    /** Copies an existing modpack under a new name. */
    private static final class ForkModpack extends ActionTool
    {
        ForkModpack( McpLauncherView view, McpLauncherActions actions ) { super( view, actions ); }

        @Override
        public String name() { return "fork_modpack"; }

        @Override
        public String title() { return "Fork modpack"; }

        @Override
        public String description()
        {
            return "Copies an existing modpack under a new name and installs the copy, so it "
                    + "can be edited without touching the original. The copy keeps every mod, "
                    + "config and setting, with its minor version bumped.";
        }

        @Override
        public JsonObject inputSchema()
        {
            return schemaOf( "friendlyName", "The modpack to copy.",
                             "newName", "The name for the copy." );
        }

        @Override
        public McpRiskClass riskClass() { return McpRiskClass.MUTATING; }

        @Override
        public String validateBeforeApproval( JsonObject arguments )
        {
            String source = stringArg( arguments, "friendlyName" );
            String newName = stringArg( arguments, "newName" );
            if ( source.isEmpty() || newName.isEmpty() ) {
                return "Both friendlyName and newName are required.";
            }
            if ( view.pack( source ) == null ) {
                return unknownPack( source );
            }
            return newPackNameRejection( newName );
        }

        @Override
        public McpToolResult invoke( McpCallContext context, JsonObject arguments )
        {
            return resultOf( actions.forkPack( stringArg( arguments, "friendlyName" ),
                                               stringArg( arguments, "newName" ) ) );
        }
    }

    /** Adds a mod to a modpack's manifest. */
    private static final class AddModToModpack extends ActionTool
    {
        AddModToModpack( McpLauncherView view, McpLauncherActions actions ) { super( view, actions ); }

        @Override
        public String name() { return "add_mod_to_modpack"; }

        @Override
        public String title() { return "Add mod to modpack"; }

        @Override
        public String description()
        {
            return "Adds a mod to a modpack's manifest, to be downloaded on the next sync. "
                    + "The remote URL must be https.";
        }

        @Override
        public JsonObject inputSchema()
        {
            return schemaOf( "friendlyName", "The modpack to edit.",
                             "modName", "The mod's display name.",
                             "remoteUrl", "The https URL to download the mod from.",
                             "localPath", "Where it installs, relative to the pack root, "
                                     + "e.g. mods/jei.jar" );
        }

        @Override
        public McpRiskClass riskClass() { return McpRiskClass.MUTATING; }

        /**
         * The mod URL faces the same install-source gate as a pack manifest, and for the same
         * reason: it is a model-chosen address that the launcher will later download and put
         * on the game's classpath.
         */
        @Override
        public String validateBeforeApproval( JsonObject arguments )
        {
            String pack = stringArg( arguments, "friendlyName" );
            String remoteUrl = stringArg( arguments, "remoteUrl" );
            String localPath = stringArg( arguments, "localPath" );
            if ( pack.isEmpty() || stringArg( arguments, "modName" ).isEmpty()
                    || remoteUrl.isEmpty() || localPath.isEmpty() ) {
                return "friendlyName, modName, remoteUrl and localPath are all required.";
            }
            if ( view.pack( pack ) == null ) {
                return unknownPack( pack );
            }
            if ( LauncherUriHandler.classifyInstallUrl( remoteUrl )
                    == LauncherUriHandler.InstallUrlVerdict.REJECT ) {
                return "That mod URL was refused by the launcher's install-source rules. "
                        + "Mod URLs must be well-formed https links.";
            }
            String rejection = rejectUnsafeLocalPath( localPath );
            return rejection;
        }

        @Override
        public McpToolResult invoke( McpCallContext context, JsonObject arguments )
        {
            return resultOf( actions.addMod( stringArg( arguments, "friendlyName" ),
                                             stringArg( arguments, "modName" ),
                                             stringArg( arguments, "remoteUrl" ),
                                             stringArg( arguments, "localPath" ) ) );
        }
    }

    /** Removes a mod from a modpack's manifest. */
    private static final class RemoveModFromModpack extends ActionTool
    {
        RemoveModFromModpack( McpLauncherView view, McpLauncherActions actions )
        {
            super( view, actions );
        }

        @Override
        public String name() { return "remove_mod_from_modpack"; }

        @Override
        public String title() { return "Remove mod from modpack"; }

        @Override
        public String description()
        {
            return "Removes a mod from a modpack's manifest, identified by its local path, "
                    + "display name, or Modrinth slug. The file is removed on the next sync.";
        }

        @Override
        public JsonObject inputSchema()
        {
            return schemaOf( "friendlyName", "The modpack to edit.",
                             "mod", "The mod's local path, display name, or Modrinth slug." );
        }

        @Override
        public McpRiskClass riskClass() { return McpRiskClass.MUTATING; }

        @Override
        public String validateBeforeApproval( JsonObject arguments )
        {
            String pack = stringArg( arguments, "friendlyName" );
            if ( pack.isEmpty() || stringArg( arguments, "mod" ).isEmpty() ) {
                return "Both friendlyName and mod are required.";
            }
            return view.pack( pack ) == null ? unknownPack( pack ) : null;
        }

        @Override
        public McpToolResult invoke( McpCallContext context, JsonObject arguments )
        {
            return resultOf( actions.removeMod( stringArg( arguments, "friendlyName" ),
                                                stringArg( arguments, "mod" ) ) );
        }
    }

    /** Uninstalls a modpack and deletes its files. */
    private static final class UninstallModpack extends ActionTool
    {
        UninstallModpack( McpLauncherView view, McpLauncherActions actions ) { super( view, actions ); }

        @Override
        public String name() { return "uninstall_modpack"; }

        @Override
        public String title() { return "Uninstall modpack"; }

        @Override
        public String description()
        {
            return "Permanently removes a modpack and deletes its files, including any worlds "
                    + "saved inside it. This cannot be undone.";
        }

        @Override
        public JsonObject inputSchema()
        {
            return schemaOf( "friendlyName", "The modpack to uninstall." );
        }

        @Override
        public McpRiskClass riskClass() { return McpRiskClass.DESTRUCTIVE; }

        /**
         * Refuses to delete a pack while its game is running.
         * <p>
         * Deleting the files out from under a live game corrupts whatever it writes next, and
         * the user would attribute the resulting crash to the game rather than to a tool call
         * they approved minutes earlier.
         */
        @Override
        public String validateBeforeApproval( JsonObject arguments )
        {
            String pack = stringArg( arguments, "friendlyName" );
            if ( pack.isEmpty() ) {
                return "A friendlyName argument is required.";
            }
            if ( view.pack( pack ) == null ) {
                return unknownPack( pack );
            }
            if ( isRunning( view, pack ) ) {
                return "\"" + pack + "\" is running. Stop it with stop_game before uninstalling it.";
            }
            return null;
        }

        /**
         * Names what the deletion would cost, per plan section 5.4.
         *
         * <p>"Delete All the Mods 9?" and "Delete All the Mods 9 — 4.2 GB, 3 worlds?" are
         * different questions, and only the second one can be answered responsibly. Worlds are
         * called out separately from bytes because they are the part that is genuinely
         * unrecoverable.</p>
         */
        @Override
        public String consentDetail( JsonObject arguments )
        {
            McpLauncherView.PackFootprint footprint =
                    view.footprintOf( stringArg( arguments, "friendlyName" ) );
            if ( footprint == null ) {
                return "This permanently deletes the modpack's files.";
            }
            StringBuilder detail = new StringBuilder( "This permanently deletes " );
            detail.append( footprint.approximate() ? "at least " : "" )
                  .append( describeSize( footprint.sizeBytes() ) );
            if ( footprint.worldCount() > 0 ) {
                detail.append( " including " ).append( footprint.worldCount() )
                      .append( footprint.worldCount() == 1 ? " saved world" : " saved worlds" );
            }
            return detail.append( ". This cannot be undone." ).toString();
        }

        @Override
        public McpToolResult invoke( McpCallContext context, JsonObject arguments )
        {
            return resultOf( actions.uninstall( stringArg( arguments, "friendlyName" ) ) );
        }
    }

    /** Launches a modpack. */
    private static final class LaunchModpack extends ActionTool
    {
        LaunchModpack( McpLauncherView view, McpLauncherActions actions ) { super( view, actions ); }

        @Override
        public String name() { return "launch_modpack"; }

        @Override
        public String title() { return "Launch modpack"; }

        @Override
        public String description()
        {
            return "Launches a modpack, starting Minecraft. It plays as the modpack's usual account "
                    + "(its own setting, else the default account) unless an account is named. Several "
                    + "games can run at once, one per modpack and one per account. This runs "
                    + "third-party mod code.";
        }

        @Override
        public JsonObject inputSchema()
        {
            JsonObject schema = schemaOf( "friendlyName", "The modpack to launch." );
            JsonObject account = new JsonObject();
            account.addProperty( "type", "string" );
            account.addProperty( "description", "Optional username of a signed-in account to play as." );
            schema.getAsJsonObject( "properties" ).add( "account", account );
            return schema;
        }

        @Override
        public McpRiskClass riskClass() { return McpRiskClass.EXECUTE; }

        @Override
        public String validateBeforeApproval( JsonObject arguments )
        {
            String pack = stringArg( arguments, "friendlyName" );
            if ( pack.isEmpty() ) {
                return "A friendlyName argument is required.";
            }
            McpLauncherView.PackSummary summary = view.pack( pack );
            if ( summary == null ) {
                return unknownPack( pack );
            }
            if ( !summary.installed() ) {
                return "\"" + pack + "\" is not installed. Install it before launching.";
            }
            if ( !view.status().signedIn() ) {
                return "No account is signed in. Sign in through the launcher before launching a "
                        + "modpack.";
            }
            String account = stringArg( arguments, "account" );
            return actions.whyLaunchBlocked( pack, account.isEmpty() ? null : account );
        }

        @Override
        public McpToolResult invoke( McpCallContext context, JsonObject arguments )
        {
            String account = stringArg( arguments, "account" );
            return resultOf( actions.launch( stringArg( arguments, "friendlyName" ),
                                             account.isEmpty() ? null : account ) );
        }
    }

    /** Terminates the running game. */
    private static final class StopGame extends ActionTool
    {
        StopGame( McpLauncherView view, McpLauncherActions actions ) { super( view, actions ); }

        @Override
        public String name() { return "stop_game"; }

        @Override
        public String title() { return "Stop game"; }

        @Override
        public String description()
        {
            return "Stops a running game (or cancels one still preparing). Name the modpack when "
                    + "several games are running; list_running_games shows them. Unsaved progress "
                    + "since the last autosave is lost.";
        }

        @Override
        public JsonObject inputSchema()
        {
            JsonObject name = new JsonObject();
            name.addProperty( "type", "string" );
            name.addProperty( "description", "The modpack whose game to stop. Optional when only one game is running." );
            JsonObject properties = new JsonObject();
            properties.add( "friendlyName", name );
            JsonObject schema = new JsonObject();
            schema.addProperty( "type", "object" );
            schema.add( "properties", properties );
            return schema;
        }

        @Override
        public McpRiskClass riskClass() { return McpRiskClass.EXECUTE; }

        @Override
        public String validateBeforeApproval( JsonObject arguments )
        {
            return stopProblem( view.runningGames(), stringArg( arguments, "friendlyName" ) );
        }

        @Override
        public String consentDetail( JsonObject arguments )
        {
            String target = targetOf( view.runningGames(), stringArg( arguments, "friendlyName" ) );
            return target == null ? null : "This stops \"" + target + "\".";
        }

        @Override
        public McpToolResult invoke( McpCallContext context, JsonObject arguments )
        {
            String target = targetOf( view.runningGames(), stringArg( arguments, "friendlyName" ) );
            if ( target == null ) {
                return McpToolResult.error( stopProblem( view.runningGames(), stringArg( arguments, "friendlyName" ) ) );
            }
            return resultOf( actions.stopGame( target ) );
        }

        /**
         * Which game a stop applies to: the named one, or the only one running. Pure, for testing.
         *
         * @param running the running games
         * @param named   the requested pack, possibly empty
         *
         * @return the pack's friendly name, or {@code null} when it can't be decided
         */
        static String targetOf( java.util.List< McpLauncherView.RunningGame > running, String named )
        {
            if ( named != null && !named.isEmpty() ) {
                for ( McpLauncherView.RunningGame g : running ) {
                    if ( g.friendlyName().equalsIgnoreCase( named ) ) {
                        return g.friendlyName();
                    }
                }
                return null;
            }
            return running.size() == 1 ? running.get( 0 ).friendlyName() : null;
        }

        /** Why a stop can't proceed, or {@code null}. */
        static String stopProblem( java.util.List< McpLauncherView.RunningGame > running, String named )
        {
            if ( running.isEmpty() ) {
                return "No game is currently running.";
            }
            if ( targetOf( running, named ) != null ) {
                return null;
            }
            StringBuilder names = new StringBuilder();
            for ( McpLauncherView.RunningGame g : running ) {
                names.append( names.length() == 0 ? "" : ", " ).append( '"' ).append( g.friendlyName() ).append( '"' );
            }
            return named != null && !named.isEmpty()
                   ? "\"" + named + "\" is not running. Running now: " + names + "."
                   : "Several games are running (" + names + "). Name the one to stop with friendlyName.";
        }
    }

    /**
     * Whether a pack is launching or running.
     *
     * @param view         the launcher view
     * @param friendlyName the pack
     *
     * @return {@code true} when it appears among the running games
     */
    private static boolean isRunning( McpLauncherView view, String friendlyName )
    {
        for ( McpLauncherView.RunningGame g : view.runningGames() ) {
            if ( g.friendlyName().equalsIgnoreCase( friendlyName ) ) {
                return true;
            }
        }
        return false;
    }

    /**
     * Renders a byte count the way a person reads one.
     *
     * @param bytes the size in bytes
     *
     * @return a short human-readable size
     *
     * @since 3.0
     */
    static String describeSize( long bytes )
    {
        if ( bytes < 1024L ) {
            return bytes + " bytes";
        }
        String[] units = { "KB", "MB", "GB", "TB" };
        double value = bytes / 1024.0;
        int unit = 0;
        while ( value >= 1024.0 && unit < units.length - 1 ) {
            value /= 1024.0;
            unit++;
        }
        return String.format( java.util.Locale.ROOT, value < 10 ? "%.1f %s" : "%.0f %s",
                              value, units[ unit ] );
    }

    /**
     * Refuses a mod install path that would write outside the pack.
     * <p>
     * The path comes from a model and is joined to the pack's root before anything is
     * downloaded to it. This is the same containment question as the archive extractors and
     * the {@code mica://} resource gate, and it is checked here — before consent — because a
     * traversal attempt is not something to ask the user to adjudicate.
     *
     * @param localPath the proposed install path
     *
     * @return a rejection reason, or {@code null} when the path is contained
     */
    static String rejectUnsafeLocalPath( String localPath )
    {
        if ( localPath == null || localPath.isBlank() ) {
            return "A localPath argument is required.";
        }
        String path = localPath.trim();
        if ( path.startsWith( "/" ) || path.startsWith( "\\" )
                || ( path.length() >= 2 && path.charAt( 1 ) == ':' ) ) {
            return "localPath must be relative to the modpack folder, not an absolute path.";
        }
        for ( String segment : path.split( "[/\\\\]" ) ) {
            if ( segment.equals( ".." ) ) {
                return "localPath must stay inside the modpack folder.";
            }
        }
        for ( int i = 0; i < path.length(); i++ ) {
            char c = path.charAt( i );
            if ( c == '\0' || Character.isISOControl( c ) ) {
                return "localPath contains an illegal character.";
            }
        }
        return null;
    }

    /**
     * Maps an empty string to {@code null}, for optional arguments.
     *
     * @param value the value
     *
     * @return the value, or {@code null} when empty
     */
    private static String emptyToNull( String value )
    {
        return value == null || value.isEmpty() ? null : value;
    }

    /**
     * Returns the names of every tool this class registers, for tests and documentation.
     *
     * @return the tool names
     *
     * @since 3.0
     */
    public static List< String > toolNames()
    {
        return List.of( "install_modpack", "create_modpack", "fork_modpack", "add_mod_to_modpack",
                        "remove_mod_from_modpack", "uninstall_modpack", "launch_modpack",
                        "stop_game" );
    }

    /**
     * Not instantiable.
     */
    private MutatingTools()
    {
        throw new AssertionError( "MutatingTools is a factory holder and must not be instantiated" );
    }
}
