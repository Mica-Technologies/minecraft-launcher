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
import com.micatechnologies.minecraft.launcher.mcp.approval.McpRiskClass;

import java.util.List;

/**
 * The read-only MCP tools — the plan's section 7.1.
 * <p>
 * Every tool here is a thin adapter over {@link McpLauncherView}: it reads the argument it
 * needs, asks the view, and formats the answer. They hold no launcher state of their own, and
 * the view they depend on cannot surface a token, a UUID, or a filesystem path, so there is no
 * path from any of them to a credential.
 * <p>
 * A tool that cannot do what was asked returns {@link McpToolResult#error} rather than
 * throwing. The message says what went wrong in terms the model can act on — "no pack named X"
 * rather than a stack trace — because the model's next move should be to correct itself, not
 * to give up.
 *
 * @author Mica Technologies
 * @version 1.0
 * @since 3.0
 */
public final class ReadOnlyTools
{
    /**
     * Registers every read-only tool against a view.
     *
     * @param registry the registry to populate
     * @param view     the launcher state the tools may read
     *
     * @throws IllegalArgumentException if either argument is {@code null}
     * @since 3.0
     */
    public static void registerAll( McpToolRegistry registry, McpLauncherView view )
    {
        if ( registry == null || view == null ) {
            throw new IllegalArgumentException( "A registry and a launcher view are required" );
        }
        registry.register( new ListModpacks( view ) );
        registry.register( new GetModpackInfo( view ) );
        registry.register( new GetModpackManifest( view ) );
        registry.register( new GetCrashReport( view ) );
        registry.register( new DiagnoseLaunchFailure( view ) );
        registry.register( new GetLauncherStatus( view ) );
    }

    /**
     * Base class holding the view and the boilerplate every read-only tool shares.
     *
     * @since 3.0
     */
    private abstract static class ViewTool implements McpTool
    {
        /** The launcher state this tool may read. */
        protected final McpLauncherView view;

        /**
         * Constructs a tool.
         *
         * @param view the launcher state this tool may read
         */
        ViewTool( McpLauncherView view )
        {
            this.view = view;
        }

        @Override
        public McpRiskClass riskClass()
        {
            return McpRiskClass.READ_ONLY;
        }

        /**
         * Builds a schema for a tool taking a single required {@code friendlyName} string.
         *
         * @return the input schema
         */
        protected static JsonObject packNameSchema()
        {
            JsonObject name = new JsonObject();
            name.addProperty( "type", "string" );
            name.addProperty( "description", "The modpack's friendly (display) name." );

            JsonObject properties = new JsonObject();
            properties.add( "friendlyName", name );

            JsonArray required = new JsonArray();
            required.add( "friendlyName" );

            JsonObject schema = new JsonObject();
            schema.addProperty( "type", "object" );
            schema.add( "properties", properties );
            schema.add( "required", required );
            return schema;
        }

        /**
         * Builds a schema for a tool taking no arguments.
         *
         * @return the input schema
         */
        protected static JsonObject noArgumentsSchema()
        {
            JsonObject schema = new JsonObject();
            schema.addProperty( "type", "object" );
            schema.add( "properties", new JsonObject() );
            return schema;
        }

        /**
         * Reads the {@code friendlyName} argument.
         *
         * @param arguments the call arguments
         *
         * @return the value, or {@code ""} when absent or not a string
         */
        protected static String friendlyNameOf( JsonObject arguments )
        {
            if ( arguments == null || !arguments.has( "friendlyName" )
                    || !arguments.get( "friendlyName" ).isJsonPrimitive() ) {
                return "";
            }
            return arguments.get( "friendlyName" ).getAsString().trim();
        }
    }

    /** Lists every modpack the launcher knows about. */
    private static final class ListModpacks extends ViewTool
    {
        ListModpacks( McpLauncherView view ) { super( view ); }

        @Override
        public String name() { return "list_modpacks"; }

        @Override
        public String title() { return "List modpacks"; }

        @Override
        public String description()
        {
            return "Lists every modpack the launcher knows about, both installed and available "
                    + "to install. Returns each pack's friendly name, version, mod loader, "
                    + "whether it is installed, and whether its author flagged it unstable. Use "
                    + "the friendly name to address a pack in other tools.";
        }

        @Override
        public JsonObject inputSchema() { return noArgumentsSchema(); }

        @Override
        public McpToolResult invoke( McpCallContext context, JsonObject arguments )
        {
            JsonArray packs = new JsonArray();
            for ( McpLauncherView.PackSummary pack : view.packs() ) {
                JsonObject entry = new JsonObject();
                entry.addProperty( "friendlyName", pack.friendlyName() );
                entry.addProperty( "version", pack.version() );
                entry.addProperty( "modLoader", pack.modLoader() );
                entry.addProperty( "installed", pack.installed() );
                entry.addProperty( "unstable", pack.unstable() );
                packs.add( entry );
            }
            JsonObject result = new JsonObject();
            result.add( "modpacks", packs );
            result.addProperty( "count", packs.size() );
            return McpToolResult.json( result );
        }
    }

    /** Reports details of one modpack. */
    private static final class GetModpackInfo extends ViewTool
    {
        GetModpackInfo( McpLauncherView view ) { super( view ); }

        @Override
        public String name() { return "get_modpack_info"; }

        @Override
        public String title() { return "Get modpack info"; }

        @Override
        public String description()
        {
            return "Reports the version, mod loader, install state and stability flag of one "
                    + "modpack, addressed by its friendly name.";
        }

        @Override
        public JsonObject inputSchema() { return packNameSchema(); }

        @Override
        public McpToolResult invoke( McpCallContext context, JsonObject arguments )
        {
            String friendlyName = friendlyNameOf( arguments );
            if ( friendlyName.isEmpty() ) {
                return McpToolResult.error( "A friendlyName argument is required." );
            }
            McpLauncherView.PackSummary pack = view.pack( friendlyName );
            if ( pack == null ) {
                return McpToolResult.error( unknownPack( friendlyName ) );
            }
            JsonObject result = new JsonObject();
            result.addProperty( "friendlyName", pack.friendlyName() );
            result.addProperty( "version", pack.version() );
            result.addProperty( "modLoader", pack.modLoader() );
            result.addProperty( "installed", pack.installed() );
            result.addProperty( "unstable", pack.unstable() );
            return McpToolResult.json( result );
        }
    }

    /** Returns a modpack's raw manifest JSON. */
    private static final class GetModpackManifest extends ViewTool
    {
        GetModpackManifest( McpLauncherView view ) { super( view ); }

        @Override
        public String name() { return "get_modpack_manifest"; }

        @Override
        public String title() { return "Get modpack manifest"; }

        @Override
        public String description()
        {
            return "Returns the raw modpack manifest JSON for one pack: its mod, config, "
                    + "resource pack and shader lists, mod loader URLs, and metadata.";
        }

        @Override
        public JsonObject inputSchema() { return packNameSchema(); }

        @Override
        public McpToolResult invoke( McpCallContext context, JsonObject arguments )
        {
            String friendlyName = friendlyNameOf( arguments );
            if ( friendlyName.isEmpty() ) {
                return McpToolResult.error( "A friendlyName argument is required." );
            }
            String manifest = view.manifestOf( friendlyName );
            if ( manifest == null ) {
                return McpToolResult.error( "No manifest is available for modpack \"" + friendlyName
                                                    + "\". It may not exist, or its manifest may be "
                                                    + "unreachable." );
            }
            return McpToolResult.text( manifest );
        }
    }

    /** Returns a modpack's most recent crash report. */
    private static final class GetCrashReport extends ViewTool
    {
        GetCrashReport( McpLauncherView view ) { super( view ); }

        @Override
        public String name() { return "get_crash_report"; }

        @Override
        public String title() { return "Get crash report"; }

        @Override
        public String description()
        {
            return "Returns the most recent crash report for one modpack, as raw text. "
                    + "Credentials are stripped from the output.";
        }

        @Override
        public JsonObject inputSchema() { return packNameSchema(); }

        @Override
        public McpToolResult invoke( McpCallContext context, JsonObject arguments )
        {
            String friendlyName = friendlyNameOf( arguments );
            if ( friendlyName.isEmpty() ) {
                return McpToolResult.error( "A friendlyName argument is required." );
            }
            McpLauncherView.CrashInfo crash = view.latestCrashOf( friendlyName );
            if ( crash == null || crash.reportText() == null || crash.reportText().isBlank() ) {
                return McpToolResult.error( "No crash report is available for modpack \""
                                                    + friendlyName + "\"." );
            }
            return McpToolResult.text( crash.reportText() );
        }
    }

    /**
     * The composite diagnostic tool: crash report, the launcher's own diagnosis, and pack
     * context in one payload.
     * <p>
     * This is the highest-value read-only tool in the plan — it turns "why did my game crash"
     * into a question a model can actually answer, without the user pasting logs around.
     */
    private static final class DiagnoseLaunchFailure extends ViewTool
    {
        DiagnoseLaunchFailure( McpLauncherView view ) { super( view ); }

        @Override
        public String name() { return "diagnose_launch_failure"; }

        @Override
        public String title() { return "Diagnose launch failure"; }

        @Override
        public String description()
        {
            return "Explains why a modpack failed to launch or crashed. Returns the launcher's "
                    + "own diagnosis (category, summary, suggested fixes) together with the raw "
                    + "crash report and the pack's version and mod loader, so the report can be "
                    + "read in context. Credentials are stripped from the output. Start here for "
                    + "any 'why did my game crash' question.";
        }

        @Override
        public JsonObject inputSchema() { return packNameSchema(); }

        @Override
        public McpToolResult invoke( McpCallContext context, JsonObject arguments )
        {
            String friendlyName = friendlyNameOf( arguments );
            if ( friendlyName.isEmpty() ) {
                return McpToolResult.error( "A friendlyName argument is required." );
            }
            McpLauncherView.PackSummary pack = view.pack( friendlyName );
            if ( pack == null ) {
                return McpToolResult.error( unknownPack( friendlyName ) );
            }

            JsonObject result = new JsonObject();
            result.addProperty( "friendlyName", pack.friendlyName() );
            result.addProperty( "version", pack.version() );
            result.addProperty( "modLoader", pack.modLoader() );
            result.addProperty( "unstable", pack.unstable() );

            McpLauncherView.CrashInfo crash = view.latestCrashOf( friendlyName );
            if ( crash == null ) {
                result.addProperty( "crashReportAvailable", false );
                // Not an error result: "this pack has not crashed" is a useful answer, and
                // reporting it as a failure would push the model to retry pointlessly.
                result.addProperty( "note", "No crash report is on record for this modpack." );
                return McpToolResult.json( result );
            }

            result.addProperty( "crashReportAvailable", true );
            result.addProperty( "category", crash.category() );
            result.addProperty( "title", crash.title() );
            result.addProperty( "summary", crash.summary() );

            JsonArray suggestions = new JsonArray();
            if ( crash.suggestions() != null ) {
                for ( String suggestion : crash.suggestions() ) {
                    suggestions.add( suggestion );
                }
            }
            result.add( "suggestions", suggestions );
            result.addProperty( "crashReport", crash.reportText() );
            return McpToolResult.json( result );
        }
    }

    /** Reports launcher-level status. */
    private static final class GetLauncherStatus extends ViewTool
    {
        GetLauncherStatus( McpLauncherView view ) { super( view ); }

        @Override
        public String name() { return "get_launcher_status"; }

        @Override
        public String title() { return "Get launcher status"; }

        @Override
        public String description()
        {
            return "Reports the launcher version, whether an account is signed in and its "
                    + "username, and how many modpacks are installed.";
        }

        @Override
        public JsonObject inputSchema() { return noArgumentsSchema(); }

        @Override
        public McpToolResult invoke( McpCallContext context, JsonObject arguments )
        {
            McpLauncherView.LauncherStatus status = view.status();
            JsonObject result = new JsonObject();
            result.addProperty( "launcherVersion", status.launcherVersion() );
            result.addProperty( "signedIn", status.signedIn() );
            // Username only. The account UUID and every token are forbidden here by section
            // 5.6, and McpLauncherView deliberately offers no way to reach them.
            result.addProperty( "username", status.username() );
            result.addProperty( "installedModpackCount", status.installedPackCount() );
            return McpToolResult.json( result );
        }
    }

    /**
     * Builds the "no such pack" message, which names the tool that lists them so the model can
     * recover on its own rather than guessing again.
     *
     * @param friendlyName the name that was not found
     *
     * @return the message
     */
    private static String unknownPack( String friendlyName )
    {
        return "No modpack named \"" + friendlyName + "\". Call list_modpacks to see the "
                + "available friendly names.";
    }

    /**
     * Not instantiable.
     */
    private ReadOnlyTools()
    {
        throw new AssertionError( "ReadOnlyTools is a factory holder and must not be instantiated" );
    }
}
