/*
 * Copyright (c) 2021 Mica Technologies
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

package com.micatechnologies.minecraft.launcher.exceptions;

import me.cortex.jarscanner.Results;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Exception wrapper class for handling and reporting mod pack scan detections with a user-friendly message that
 * surfaces directly in the error popup.
 *
 * <p>The message format intentionally avoids implementation jargon ("Stage 1", "Stage 2", etc.) because the popup
 * is the user's only window into what blocked their launch. The dialog reads:
 *
 * <pre>
 * The pre-launch security scan blocked "Alto" — 4 issues found:
 *
 * • mods/optifine.jar — reference to launcher credential file 'launcher_profiles.json' in optifine/Installer.updateLauncherJson
 * • mods/city-super-mod.jar — embedded executable / script entry: marytts/machinelearning/GMMTrainer.exe
 * ...
 *
 * Remove or replace the flagged file(s) before launching again. If you trust this content,
 * you can change this pack's Security Scan Frequency to "Disabled" in its Advanced settings.
 * </pre>
 *
 * @author Mica Technologies
 * @version 2.0.0
 * @see LauncherException
 * @see ModpackException
 * @since 2023.2.3
 */
public class ModpackScanDetectionException extends ModpackException
{

    /**
     * Create a {@link ModpackScanDetectionException} with no pack context. Used when a caller doesn't have the
     * pack name / root handy (legacy code paths); the message degrades to "this modpack" and leaves the full
     * absolute paths in the listing.
     *
     * @param scanResults results of the scan
     *
     * @since 1.0.0
     */
    public ModpackScanDetectionException( Results scanResults ) {
        this( scanResults, null, null );
    }

    /**
     * Create a {@link ModpackScanDetectionException} with pack context for nicer display — pack name appears in
     * the header line and {@code packRootFolder} is stripped from absolute paths in the per-finding list so
     * users see {@code mods/foo.jar} instead of {@code C:\Users\...\installs\Alto\mods\foo.jar}.
     *
     * @param scanResults    results of the scan
     * @param packName       human-readable pack name (e.g. "Alto"); nullable
     * @param packRootFolder absolute filesystem path of the pack root, used to compute relative paths; nullable
     *
     * @since 2026.3
     */
    public ModpackScanDetectionException( Results scanResults, String packName, String packRootFolder ) {
        super( getExceptionMsg( scanResults, packName, packRootFolder ) );
    }

    /**
     * Create a {@link ModpackScanDetectionException} with specified message and backtrace.
     *
     * @param scanResults    results of the scan
     * @param exceptionTrace exception backtrace
     *
     * @since 1.0.0
     */
    public ModpackScanDetectionException( Results scanResults, Throwable exceptionTrace ) {
        super( getExceptionMsg( scanResults, null, null ), exceptionTrace );
    }

    /**
     * Build the user-facing message rendered in the launch-failure popup. Pulls every detection from the
     * results (Stage 1 + Stage 2 from Nekodetector, plus any HIGH-severity findings merged in from the
     * supplemental scanner), de-duplicates, strips internal prefixes, and assembles a readable list.
     */
    private static String getExceptionMsg( Results scanResults, String packName, String packRootFolder ) {
        List< String > all = new ArrayList<>();
        if ( scanResults.getStage1Detections() != null ) {
            all.addAll( scanResults.getStage1Detections() );
        }
        if ( scanResults.getStage2Detections() != null ) {
            all.addAll( scanResults.getStage2Detections() );
        }
        // De-dup while preserving order — Stage 1 and Stage 2 lists can overlap when a detector
        // hits the same file via different paths; showing the same line twice in the popup is
        // worse than slightly wrong counts.
        List< String > uniq = new ArrayList<>( all.size() );
        for ( String s : all ) {
            if ( s != null && !uniq.contains( s ) ) uniq.add( s );
        }

        if ( uniq.isEmpty() ) {
            return "The pre-launch security scan completed without any findings.";
        }

        String packLabel = ( packName != null && !packName.isBlank() )
                ? "\"" + packName + "\""
                : "this modpack";
        int count = uniq.size();

        StringBuilder sb = new StringBuilder();
        sb.append( "The pre-launch security scan blocked " ).append( packLabel )
          .append( " — " ).append( count ).append( count == 1 ? " issue" : " issues" )
          .append( " found:\n\n" );
        for ( String raw : uniq ) {
            sb.append( "• " ).append( cleanFindingLine( raw, packRootFolder ) ).append( "\n" );
        }
        sb.append( "\nRemove or replace the flagged file(s) before launching again. " )
          .append( "If you trust this content, you can change this pack's Security Scan Frequency " )
          .append( "to \"Disabled\" in its Advanced settings to bypass the scan." );

        return sb.toString();
    }

    /**
     * Strips the noisy prefixes off a raw detection line so the popup reads cleanly.
     *
     * <p>Input examples (from {@code SupplementalScanner.Finding.toString} / Nekodetector format):
     * <pre>
     *   [HIGH] C:\Users\ahawk\.MicaMinecraftLauncherDEV\installs\Alto\mods\optifine.jar — reference to ...
     *   [HIGH] /home/u/.MicaMinecraftLauncher/installs/Alto/mods/foo.jar — embedded executable ...
     * </pre>
     *
     * <p>Output for those, with {@code packRootFolder} = {@code C:\Users\ahawk\.MicaMinecraftLauncherDEV\installs\Alto}:
     * <pre>
     *   mods\optifine.jar — reference to ...
     *   mods/foo.jar — embedded executable ...
     * </pre>
     */
    private static String cleanFindingLine( String raw, String packRootFolder ) {
        if ( raw == null ) return "Unknown detection";
        String s = raw.trim();

        // Drop the leading severity tag — the dialog header already establishes that everything
        // here is blocking-severity. Pattern: "[HIGH] " or "[MEDIUM] " or similar.
        s = s.replaceFirst( "^\\[[A-Za-z]+]\\s+", "" );

        // Strip the pack root prefix when possible so users see a path relative to the pack.
        if ( packRootFolder != null && !packRootFolder.isBlank() ) {
            String prefix = packRootFolder;
            // Try the OS-native form first.
            if ( !prefix.endsWith( File.separator ) ) prefix = prefix + File.separator;
            if ( s.startsWith( prefix ) ) {
                s = s.substring( prefix.length() );
            }
            else {
                // Fall back to a slash-normalized comparison — Finding.toString uses Path.toString
                // which is OS-dependent, but absolute paths embedded in Nekodetector output may
                // use forward slashes even on Windows.
                String prefixSlash = packRootFolder.replace( '\\', '/' );
                if ( !prefixSlash.endsWith( "/" ) ) prefixSlash = prefixSlash + "/";
                String sSlash = s.replace( '\\', '/' );
                if ( sSlash.startsWith( prefixSlash ) ) {
                    // Trim the same number of chars off the original — preserves original separator style.
                    s = s.substring( prefixSlash.length() );
                }
            }
        }
        return s;
    }
}
