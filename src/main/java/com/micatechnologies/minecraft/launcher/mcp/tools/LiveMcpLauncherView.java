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

import com.micatechnologies.minecraft.launcher.consts.LauncherConstants;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.game.auth.MCLauncherAuthManager;
import com.micatechnologies.minecraft.launcher.game.crash.CrashDiagnosis;
import com.micatechnologies.minecraft.launcher.game.crash.CrashReportAnalyzer;
import com.micatechnologies.minecraft.launcher.game.crash.Suggestion;
import com.micatechnologies.minecraft.launcher.game.modpack.GameModPack;
import com.micatechnologies.minecraft.launcher.game.modpack.GameModPackManager;
import com.micatechnologies.minecraft.launcher.game.modpack.ModpackExporter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The production {@link McpLauncherView}, reading real launcher state.
 * <p>
 * Everything below is a narrowing: the launcher's own objects expose far more than MCP clients
 * may see, and this class is where that is cut down. The clearest case is {@link #status()},
 * which reads the signed-in {@code User} and takes <b>only</b> {@code name()} — never
 * {@code uuid()}, never {@code accessToken()}. The plan's section 5.6 forbids both, and the
 * narrow {@link McpLauncherView} record types mean a tool downstream has no way to ask for
 * them even if it wanted to.
 * <p>
 * Every method is defensive about failure, because these run on a request thread serving a
 * remote client: a pack whose manifest is unreachable yields {@code null} rather than throwing
 * out through the transport.
 *
 * @author Mica Technologies
 * @version 1.0
 * @since 3.0
 */
public final class LiveMcpLauncherView implements McpLauncherView
{
    /**
     * The exit code handed to the crash analyzer.
     * <p>
     * The launcher does not record the exit code alongside the stored crash report, and the
     * analyzer only surfaces this value in its fallback "could not classify" result. A generic
     * non-zero is therefore honest — the process did fail — without fabricating a specific
     * code that would read as recorded fact.
     */
    private static final int UNRECORDED_FAILURE_EXIT_CODE = 1;

    @Override
    public List< PackSummary > packs()
    {
        // Installed first, so an installed pack wins when the same pack appears on both lists.
        Map< String, PackSummary > byName = new LinkedHashMap<>();
        for ( GameModPack pack : safeList( true ) ) {
            PackSummary summary = summarize( pack, true );
            if ( summary != null ) {
                byName.putIfAbsent( summary.friendlyName(), summary );
            }
        }
        for ( GameModPack pack : safeList( false ) ) {
            PackSummary summary = summarize( pack, false );
            if ( summary != null ) {
                byName.putIfAbsent( summary.friendlyName(), summary );
            }
        }
        return new ArrayList<>( byName.values() );
    }

    @Override
    public PackSummary pack( String friendlyName )
    {
        if ( friendlyName == null || friendlyName.isBlank() ) {
            return null;
        }
        for ( PackSummary summary : packs() ) {
            if ( friendlyName.equals( summary.friendlyName() ) ) {
                return summary;
            }
        }
        return null;
    }

    @Override
    public String manifestOf( String friendlyName )
    {
        GameModPack pack = findPack( friendlyName );
        if ( pack == null ) {
            return null;
        }
        try {
            return ModpackExporter.loadManifestText( pack );
        }
        catch ( Exception e ) {
            Logger.logWarningSilent( "MCP could not load the manifest for " + friendlyName );
            return null;
        }
    }

    @Override
    public CrashInfo latestCrashOf( String friendlyName )
    {
        GameModPack pack = findPack( friendlyName );
        if ( pack == null ) {
            return null;
        }

        String report;
        try {
            report = pack.getLatestCrashReport();
        }
        catch ( Exception e ) {
            Logger.logWarningSilent( "MCP could not read the crash report for " + friendlyName );
            return null;
        }
        if ( report == null || report.isBlank() ) {
            return null;
        }

        try {
            CrashDiagnosis diagnosis = CrashReportAnalyzer.analyze( report, pack,
                                                                    UNRECORDED_FAILURE_EXIT_CODE );
            List< String > suggestions = new ArrayList<>();
            if ( diagnosis.suggestions() != null ) {
                for ( Suggestion suggestion : diagnosis.suggestions() ) {
                    // Only the label. A Suggestion also carries a Runnable, which is a launcher
                    // action and has no meaning across the MCP boundary.
                    suggestions.add( suggestion.label() );
                }
            }
            return new CrashInfo( report,
                                  nullToEmpty( diagnosis.title() ),
                                  nullToEmpty( diagnosis.summary() ),
                                  diagnosis.category() == null ? "" : diagnosis.category().name(),
                                  suggestions );
        }
        catch ( Exception e ) {
            // The report itself is still worth returning even when diagnosis fails -- that is
            // the part a model can actually read.
            Logger.logWarningSilent( "MCP could not diagnose the crash report for " + friendlyName );
            return new CrashInfo( report, "", "", "", List.of() );
        }
    }

    @Override
    public LauncherStatus status()
    {
        boolean signedIn = false;
        String username = "";
        try {
            var user = MCLauncherAuthManager.getLoggedInUser();
            if ( user != null ) {
                signedIn = true;
                // Username ONLY. uuid() and accessToken() are on this same object and are
                // forbidden by section 5.6; McpLauncherView has nowhere to put them.
                username = nullToEmpty( user.name() );
            }
        }
        catch ( Exception e ) {
            Logger.logWarningSilent( "MCP could not read the signed-in account" );
        }

        int installed = 0;
        try {
            installed = safeList( true ).size();
        }
        catch ( Exception ignored ) {
            // Leave the count at zero rather than failing the whole status call.
        }

        return new LauncherStatus( LauncherConstants.LAUNCHER_APPLICATION_VERSION, signedIn,
                                   username, installed );
    }

    /**
     * Finds an installed or available pack by friendly name.
     *
     * @param friendlyName the name to look up
     *
     * @return the pack, or {@code null} when there is no match
     */
    private static GameModPack findPack( String friendlyName )
    {
        if ( friendlyName == null || friendlyName.isBlank() ) {
            return null;
        }
        for ( boolean installed : new boolean[]{ true, false } ) {
            for ( GameModPack pack : safeList( installed ) ) {
                try {
                    if ( friendlyName.equals( pack.getFriendlyName() ) ) {
                        return pack;
                    }
                }
                catch ( Exception ignored ) {
                    // A pack whose metadata will not load cannot be the one being asked for.
                }
            }
        }
        return null;
    }

    /**
     * Returns the installed or available pack list, substituting an empty list on failure so a
     * broken pack entry cannot fail an entire listing.
     *
     * @param installed {@code true} for installed packs, {@code false} for available ones
     *
     * @return the packs
     */
    private static List< GameModPack > safeList( boolean installed )
    {
        try {
            List< GameModPack > packs = installed ? GameModPackManager.getInstalledModPacks()
                                                  : GameModPackManager.getAvailableModPacks();
            return packs == null ? List.of() : packs;
        }
        catch ( Exception e ) {
            Logger.logWarningSilent( "MCP could not list "
                                             + ( installed ? "installed" : "available" ) + " modpacks" );
            return List.of();
        }
    }

    /**
     * Narrows a launcher pack to the fields MCP clients may see.
     *
     * @param pack      the pack to narrow
     * @param installed whether it came from the installed list
     *
     * @return the summary, or {@code null} when the pack has no usable name
     */
    private static PackSummary summarize( GameModPack pack, boolean installed )
    {
        try {
            String friendlyName = pack.getFriendlyName();
            if ( friendlyName == null || friendlyName.isBlank() ) {
                return null;
            }
            String version = installed ? firstNonBlank( pack.getInstalledVersion(), pack.getPackVersion() )
                                       : nullToEmpty( pack.getPackVersion() );
            return new PackSummary( friendlyName, version, nullToEmpty( pack.getModLoaderType() ),
                                    installed, pack.getPackUnstable() );
        }
        catch ( Exception e ) {
            // One unreadable pack must not take out the whole listing.
            return null;
        }
    }

    /**
     * Returns the first non-blank value, or {@code ""} when both are blank.
     *
     * @param first  the preferred value
     * @param second the fallback
     *
     * @return the chosen value
     */
    private static String firstNonBlank( String first, String second )
    {
        if ( first != null && !first.isBlank() ) {
            return first;
        }
        return nullToEmpty( second );
    }

    /**
     * Coalesces {@code null} to the empty string.
     *
     * @param value the value
     *
     * @return the value, or {@code ""}
     */
    private static String nullToEmpty( String value )
    {
        return value == null ? "" : value;
    }
}
