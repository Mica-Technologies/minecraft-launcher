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

package com.micatechnologies.minecraft.launcher.mcp;

import com.micatechnologies.minecraft.launcher.config.ConfigManager;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.mcp.approval.LauncherMcpAuthorizer;
import com.micatechnologies.minecraft.launcher.mcp.approval.McpApprovalPolicy;
import com.micatechnologies.minecraft.launcher.mcp.approval.McpGrantStore;
import com.micatechnologies.minecraft.launcher.mcp.approval.McpToolPolicyStore;
import com.micatechnologies.minecraft.launcher.mcp.resources.LauncherResources;
import com.micatechnologies.minecraft.launcher.mcp.resources.McpResourceRegistry;
import com.micatechnologies.minecraft.launcher.mcp.session.McpSession;
import com.micatechnologies.minecraft.launcher.mcp.tools.LiveMcpLauncherActions;
import com.micatechnologies.minecraft.launcher.mcp.tools.LiveMcpLauncherView;
import com.micatechnologies.minecraft.launcher.mcp.tools.McpLauncherActions;
import com.micatechnologies.minecraft.launcher.mcp.tools.MutatingTools;
import com.micatechnologies.minecraft.launcher.mcp.tools.McpLauncherView;
import com.micatechnologies.minecraft.launcher.mcp.approval.McpRiskClass;
import com.micatechnologies.minecraft.launcher.mcp.tools.McpTool;
import com.micatechnologies.minecraft.launcher.mcp.tools.McpToolRegistry;
import com.micatechnologies.minecraft.launcher.mcp.tools.ReadOnlyTools;

import java.util.List;

/**
 * Owns the launcher's single MCP server instance and its start/stop lifecycle.
 * <p>
 * <b>Off by default.</b> {@link #startIfEnabled()} does nothing at all unless the user has
 * turned the feature on: no port is bound, no token is generated, and no endpoint file is
 * written. That is the plan's first and most important gate, and it lives here rather than
 * inside {@link McpServer} so that the server object is only constructed when it is wanted.
 * <p>
 * Only the read-only tools are registered. Nothing here can install, delete, or launch
 * anything — the mutating tools land in a later phase, behind the consent dialog.
 *
 * @author Mica Technologies
 * @version 1.0
 * @since 3.0
 */
public final class McpBootstrap
{
    /** The running server, or {@code null} when the feature is off or not yet started. */
    private static McpServer server;

    /** Consent granted this run. Outlives individual server restarts within one launcher run. */
    private static final McpGrantStore GRANTS = new McpGrantStore();

    /** The user's durable per-tool choices, read from and written to launcher config. */
    private static final McpToolPolicyStore POLICIES = new McpToolPolicyStore(
            new McpToolPolicyStore.Backing()
            {
                @Override
                public String read()
                {
                    return ConfigManager.getMcpToolPolicies();
                }

                @Override
                public void write( String json )
                {
                    ConfigManager.setMcpToolPolicies( json );
                }
            } );

    /**
     * Starts the MCP server if the user has enabled it.
     * <p>
     * Safe to call when already running, and safe to call when the feature is off. A failure to
     * bind is logged and swallowed: the MCP server is an optional convenience, and refusing to
     * start the launcher because a port was busy would be a poor trade.
     *
     * @since 3.0
     */
    public static synchronized void startIfEnabled()
    {
        if ( server != null ) {
            return;
        }
        boolean enabled;
        try {
            enabled = ConfigManager.getMcpServerEnabled();
        }
        catch ( Exception e ) {
            // A config read that fails leaves the feature off, matching its default.
            Logger.logWarningSilent( "Could not read the MCP server setting; leaving it disabled" );
            return;
        }
        if ( !enabled ) {
            return;
        }

        try {
            McpLauncherView view = new LiveMcpLauncherView();

            McpToolRegistry tools = new McpToolRegistry();
            ReadOnlyTools.registerAll( tools, view );
            // State-changing tools are a second, separate opt-in. Enabling the MCP server on
            // its own gives a strictly read-only server; the step from "a model can read my
            // launcher" to "a model can install and launch things on my machine" is worth
            // making deliberately rather than as a side effect.
            if ( ConfigManager.getMcpAllowStateChanges() ) {
                MutatingTools.registerAll( tools, view, new LiveMcpLauncherActions() );
                Logger.logStd( "MCP state-changing tools are enabled" );
            }

            McpResourceRegistry resources = new McpResourceRegistry();
            LauncherResources.registerAll( resources, view );

            LauncherMcpAuthorizer authorizer = new LauncherMcpAuthorizer(
                    new ConfigSettings(), GRANTS, new FxConsentPrompt(), System::currentTimeMillis );

            McpServer started = new McpServer( tools, resources, authorizer );
            // Port 0: the OS picks a free loopback port and the endpoint file publishes it, so
            // there is no fixed port to collide with another launcher build or another app.
            started.start( 0, McpEndpointFile.defaultPath() );
            server = started;
        }
        catch ( Exception e ) {
            Logger.logError( "Could not start the MCP server; continuing without it" );
            Logger.logThrowable( e );
            server = null;
        }
    }

    /**
     * Stops the MCP server and revokes every session grant.
     * <p>
     * Called unconditionally from launcher cleanup, so it must be harmless when the server was
     * never started.
     *
     * @since 3.0
     */
    public static synchronized void stop()
    {
        if ( server != null ) {
            try {
                server.stop();
            }
            catch ( Exception e ) {
                Logger.logWarningSilent( "The MCP server did not stop cleanly" );
            }
            server = null;
        }
        // Consent is per-run. Leaving grants behind would mean a restart of the server within
        // one launcher run silently inherited approvals the user gave to a previous one.
        GRANTS.revokeAll();
    }

    /**
     * Restarts the server to pick up a change to the enabled setting.
     *
     * @since 3.0
     */
    public static synchronized void refresh()
    {
        stop();
        startIfEnabled();
    }

    /**
     * Reports whether the server is currently listening.
     *
     * @return {@code true} while running
     *
     * @since 3.0
     */
    public static synchronized boolean isRunning()
    {
        return server != null && server.isRunning();
    }

    /**
     * Returns the loopback port the server is listening on, for the Settings page.
     *
     * @return the port, or {@code 0} when not running
     *
     * @since 3.0
     */
    public static synchronized int getPort()
    {
        return server == null ? 0 : server.getPort();
    }

    /**
     * Returns the live MCP sessions, for the Settings page.
     *
     * @return the sessions; empty when the server is not running
     *
     * @since 3.0
     */
    public static synchronized List< McpSession > getSessions()
    {
        return server == null ? List.of() : server.getSessions();
    }

    /**
     * One tool as the Settings page needs to describe it.
     *
     * @param name      the tool's wire name, and the key its policy is stored under
     * @param title     the human-readable title
     * @param riskClass how much damage the tool can do, which sets its default
     *
     * @since 3.0
     */
    public record ToolDescriptor( String name, String title, McpRiskClass riskClass )
    {
    }

    /**
     * Describes every tool this build can expose, whether or not the server is running.
     * <p>
     * Built from a throwaway registry rather than the live server so the Settings page can
     * show — and let the user pre-configure — per-tool permissions while the feature is
     * switched off. A permissions screen that is empty until you enable the thing you are
     * trying to configure would be the wrong way round.
     *
     * @return the tools, in registration order
     *
     * @since 3.0
     */
    public static List< ToolDescriptor > describeTools()
    {
        List< ToolDescriptor > described = new java.util.ArrayList<>();
        try {
            McpToolRegistry registry = new McpToolRegistry();
            McpLauncherView view = new LiveMcpLauncherView();
            ReadOnlyTools.registerAll( registry, view );
            // Every tool this build can expose, whether or not state changes are switched on,
            // so the Settings page can show and pre-configure their policies either way.
            MutatingTools.registerAll( registry, view, new LiveMcpLauncherActions() );
            for ( McpTool tool : registry.all() ) {
                described.add( new ToolDescriptor( tool.name(), tool.title(), tool.riskClass() ) );
            }
        }
        catch ( Exception e ) {
            Logger.logWarningSilent( "Could not enumerate MCP tools for the Settings page" );
        }
        return described;
    }

    /**
     * Returns the user's durable per-tool policies, for the Settings page.
     *
     * @return the policy store
     *
     * @since 3.0
     */
    public static McpToolPolicyStore getPolicies()
    {
        return POLICIES;
    }

    /**
     * Returns the grants held this run, for the Settings page.
     *
     * @return the grant store
     *
     * @since 3.0
     */
    public static McpGrantStore getGrants()
    {
        return GRANTS;
    }

    /**
     * Reads approval settings from {@link ConfigManager}.
     */
    private static final class ConfigSettings implements LauncherMcpAuthorizer.Settings
    {
        @Override
        public boolean serverEnabled()
        {
            return ConfigManager.getMcpServerEnabled();
        }

        @Override
        public boolean autoApproveReadOnly()
        {
            return ConfigManager.getMcpAutoApproveReadOnly();
        }

        @Override
        public McpApprovalPolicy policyFor( String toolName )
        {
            return POLICIES.policyFor( toolName );
        }
    }

    /**
     * Not instantiable.
     */
    private McpBootstrap()
    {
        throw new AssertionError( "McpBootstrap is a lifecycle holder and must not be instantiated" );
    }
}
