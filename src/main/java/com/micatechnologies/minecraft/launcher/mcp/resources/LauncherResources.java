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

package com.micatechnologies.minecraft.launcher.mcp.resources;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.launcher.mcp.tools.McpLauncherView;
import com.micatechnologies.minecraft.launcher.utilities.JSONUtilities;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The read-only MCP resources — the plan's section 7.5.
 * <p>
 * Resources cover what a client wants to <em>read repeatedly</em>, as opposed to act on: the
 * pack index, a manifest, a crash report. They read through the same {@link McpLauncherView} as
 * the tools, so they are subject to the same bound on what they can reach, and their text goes
 * out through the same redaction path. Each one also names its equivalent tool, whose approval
 * policy gates reading it — a resource must not be a way round a tool the user disabled.
 * <p>
 * The per-pack resources are templates. They enumerate their instances from the live pack list,
 * so a client can call {@code resources/list} and see one entry per installed pack rather than
 * having to guess names.
 *
 * @author Mica Technologies
 * @version 1.0
 * @since 3.0
 */
public final class LauncherResources
{
    /** URI of the pack index. */
    public static final String PACKS_URI = "mica://packs";

    /** URI template for a pack's manifest. */
    public static final String MANIFEST_TEMPLATE = "mica://modpack/{friendlyName}/manifest";

    /** URI template for a pack's latest crash report. */
    public static final String CRASH_TEMPLATE = "mica://modpack/{friendlyName}/crash-report";

    /**
     * Registers every read-only resource against a view.
     *
     * @param registry the registry to populate
     * @param view     the launcher state the resources may read
     *
     * @throws IllegalArgumentException if either argument is {@code null}
     * @since 3.0
     */
    public static void registerAll( McpResourceRegistry registry, McpLauncherView view )
    {
        if ( registry == null || view == null ) {
            throw new IllegalArgumentException( "A registry and a launcher view are required" );
        }
        registry.register( new PackIndex( view ) );
        registry.register( new PackManifest( view ) );
        registry.register( new PackCrashReport( view ) );
    }

    /** The pack index, as JSON. */
    private record PackIndex( McpLauncherView view ) implements McpResource
    {
        @Override
        public String governingToolName() { return "list_modpacks"; }

        @Override
        public String uriTemplate() { return PACKS_URI; }

        @Override
        public String name() { return "Modpack index"; }

        @Override
        public String description()
        {
            return "Every modpack the launcher knows about, installed and available.";
        }

        @Override
        public String mimeType() { return "application/json"; }

        @Override
        public String read( Map< String, String > params )
        {
            JsonArray packs = new JsonArray();
            for ( McpLauncherView.PackSummary pack : view.packs() ) {
                JsonObject entry = new JsonObject();
                entry.addProperty( "friendlyName", pack.friendlyName() );
                entry.addProperty( "version", pack.version() );
                entry.addProperty( "latestVersion", pack.latestVersion() );
                entry.addProperty( "updateAvailable", pack.updateAvailable() );
                entry.addProperty( "modLoader", pack.modLoader() );
                entry.addProperty( "installed", pack.installed() );
                entry.addProperty( "unstable", pack.unstable() );
                packs.add( entry );
            }
            JsonObject root = new JsonObject();
            root.add( "modpacks", packs );
            return JSONUtilities.getPrettyGson().toJson( root );
        }
    }

    /** One pack's manifest JSON. */
    private record PackManifest( McpLauncherView view ) implements McpResource
    {
        @Override
        public String governingToolName() { return "get_modpack_manifest"; }

        @Override
        public String uriTemplate() { return MANIFEST_TEMPLATE; }

        @Override
        public String name() { return "Modpack manifest"; }

        @Override
        public String description() { return "The raw manifest JSON for one modpack."; }

        @Override
        public String mimeType() { return "application/json"; }

        @Override
        public String read( Map< String, String > params )
        {
            String friendlyName = params.get( "friendlyName" );
            String manifest = view.manifestOf( friendlyName );
            if ( manifest == null ) {
                throw new IllegalArgumentException( "No manifest for modpack: " + friendlyName );
            }
            return manifest;
        }

        @Override
        public List< String > concreteUris()
        {
            return enumeratePacks( view, MANIFEST_TEMPLATE, false );
        }
    }

    /** One pack's latest crash report. */
    private record PackCrashReport( McpLauncherView view ) implements McpResource
    {
        @Override
        public String governingToolName() { return "get_crash_report"; }

        @Override
        public String uriTemplate() { return CRASH_TEMPLATE; }

        @Override
        public String name() { return "Modpack crash report"; }

        @Override
        public String description()
        {
            return "The most recent crash report for one modpack, with credentials stripped.";
        }

        @Override
        public String mimeType() { return "text/plain"; }

        @Override
        public String read( Map< String, String > params )
        {
            String friendlyName = params.get( "friendlyName" );
            McpLauncherView.CrashInfo crash = view.latestCrashOf( friendlyName );
            if ( crash == null || crash.reportText() == null ) {
                throw new IllegalArgumentException( "No crash report for modpack: " + friendlyName );
            }
            return crash.reportText();
        }

        @Override
        public List< String > concreteUris()
        {
            return enumeratePacks( view, CRASH_TEMPLATE, true );
        }
    }

    /**
     * Builds one concrete URI per pack.
     * <p>
     * A pack whose name is not a safe URI segment is skipped rather than emitted, so a listing
     * never advertises a URI that {@link McpResourceUri} would then refuse to resolve.
     *
     * @param view          the launcher state to enumerate
     * @param template      the template to fill
     * @param installedOnly whether to skip packs that are not installed, for resources that
     *                      only make sense locally
     *
     * @return the concrete URIs
     */
    private static List< String > enumeratePacks( McpLauncherView view, String template,
                                                  boolean installedOnly )
    {
        List< String > uris = new ArrayList<>();
        for ( McpLauncherView.PackSummary pack : view.packs() ) {
            if ( installedOnly && !pack.installed() ) {
                continue;
            }
            if ( !McpResourceUri.isSafeSegment( pack.friendlyName() ) ) {
                continue;
            }
            uris.add( McpResourceUri.build( template, pack.friendlyName() ) );
        }
        return uris;
    }

    /**
     * Not instantiable.
     */
    private LauncherResources()
    {
        throw new AssertionError( "LauncherResources is a factory holder and must not be instantiated" );
    }
}
