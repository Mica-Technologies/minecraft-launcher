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

package com.micatechnologies.minecraft.launcher.update;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Installs a Windows launcher update from the staged MSI.
 *
 * <p>Windows cannot replace the launcher's files while it runs, and the MSI refuses to upgrade a
 * running launcher (that is what once left an install whose JVM could not start). So a helper
 * outside the install folder does it after the launcher exits: a PowerShell script in the temp
 * folder ({@link #HELPER}) waits for the native starter and the Core launcher to exit, runs
 * {@code msiexec /i <msi> /passive} (through UAC for the per-machine MSI), and starts the launcher
 * again. It always installs the MSI of the scope that is installed, never the other one, and
 * notes the result in the given log file.
 *
 * <p>Every value reaches the script as a named parameter, never inside its text.
 *
 * @since 2026.11
 */
public final class WindowsMsiInstaller
{
    /**
     * The helper script, for Windows PowerShell 5.1 (every Windows 10 and 11 has it). Parameters:
     * {@code -Msi}, {@code -Pids} (comma-separated), {@code -Elevate} ({@code 1} or {@code 0}),
     * {@code -Relaunch} (the launcher to start afterwards; optional), {@code -Log}. It waits up to
     * five minutes for the processes to exit, then deletes itself when done. msiexec exit codes:
     * 0 and 3010 succeeded (3010: a restart is needed to finish), 1602 cancelled by the user.
     */
    static final String HELPER = """
            # Finishes a Mica Minecraft Launcher update once the launcher has exited.
            # Written by WindowsMsiInstaller; deletes itself when done.
            param(
                [Parameter(Mandatory = $true)] [string] $Msi,
                [Parameter(Mandatory = $true)] [string] $Pids,
                [Parameter(Mandatory = $true)] [string] $Elevate,
                [string] $Relaunch = '',
                [Parameter(Mandatory = $true)] [string] $Log
            )
            $ErrorActionPreference = 'Continue'
            function Write-Note([string] $Text) {
                try {
                    $line = (Get-Date -Format 'yyyy-MM-dd HH:mm:ss') + ' ' + $Text
                    Add-Content -LiteralPath $Log -Value $line -Encoding UTF8
                } catch { }
            }
            $ids = @($Pids -split ',' | Where-Object { $_ -match '^[0-9]+$' } | ForEach-Object { [int]$_ })
            $deadline = (Get-Date).AddMinutes(5)
            foreach ($id in $ids) {
                while ((Get-Process -Id $id -ErrorAction SilentlyContinue) -and ((Get-Date) -lt $deadline)) {
                    Start-Sleep -Milliseconds 500
                }
            }
            $running = @($ids | Where-Object { Get-Process -Id $_ -ErrorAction SilentlyContinue })
            if ($running.Count -gt 0) {
                Write-Note 'The launcher did not exit; the update was not installed'
            } else {
                $msiArgs = @('/i', ('"' + $Msi + '"'), '/passive', '/norestart')
                try {
                    if ($Elevate -eq '1') {
                        $p = Start-Process -FilePath 'msiexec.exe' -ArgumentList $msiArgs -Verb RunAs -Wait -PassThru
                    } else {
                        $p = Start-Process -FilePath 'msiexec.exe' -ArgumentList $msiArgs -Wait -PassThru
                    }
                    $code = $p.ExitCode
                    if ($code -eq 0 -or $code -eq 3010) {
                        Write-Note ('Installed the update from ' + $Msi)
                    } elseif ($code -eq 1602) {
                        Write-Note 'The update was cancelled in the installer'
                    } else {
                        Write-Note ('The installer stopped with code ' + $code)
                    }
                } catch {
                    Write-Note ('The installer did not start (permission declined?): ' + $_.Exception.Message)
                }
            }
            if ($Relaunch -ne '') {
                try {
                    Start-Process -FilePath $Relaunch
                } catch {
                    Write-Note ('Could not start the launcher again: ' + $_.Exception.Message)
                }
            }
            Remove-Item -LiteralPath $MyInvocation.MyCommand.Path -Force -ErrorAction SilentlyContinue
            """;

    private WindowsMsiInstaller() { /* static-only */ }

    /**
     * The processes the helper waits for: this one (the Core launcher, which hosts the JVM) and,
     * when this launcher was started by the native starter, the starter too, which waits for Core
     * and holds its own executable open.
     *
     * @param self          this process's id
     * @param parentPid     the parent process's id, if known
     * @param parentCommand the parent's executable, if known
     * @param starter       the starter's path ({@code StarterHandshake.launcherExecutable()}), or {@code null}
     *
     * @return the ids, this process first
     *
     * @since 2026.11
     */
    static List< Long > processesToWaitFor( long self, Optional< Long > parentPid, Optional< String > parentCommand,
                                            String starter )
    {
        Set< Long > ids = new LinkedHashSet<>();
        ids.add( self );
        if ( parentPid.isPresent() && parentCommand.isPresent() && starter != null
             && parentCommand.get().toLowerCase( Locale.ROOT ).equals( starter.toLowerCase( Locale.ROOT ) ) ) {
            ids.add( parentPid.get() );
        }
        return new ArrayList<>( ids );
    }

    /**
     * The helper's command line.
     *
     * @param script   the helper script
     * @param msi      the staged MSI
     * @param elevate  whether to run msiexec through UAC (the per-machine MSI)
     * @param pids     the processes to wait for
     * @param relaunch the launcher to start afterwards, or {@code null}
     * @param log      the file the helper notes its result in
     *
     * @return the command, starting with {@code powershell.exe}
     *
     * @since 2026.11
     */
    static List< String > helperCommand( Path script, Path msi, boolean elevate, List< Long > pids, String relaunch,
                                         Path log )
    {
        List< String > command = new ArrayList<>( List.of(
                "powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-WindowStyle",
                "Hidden", "-File", script.toString(), "-Msi", msi.toString(), "-Pids",
                pids.stream().map( String::valueOf ).collect( Collectors.joining( "," ) ), "-Elevate",
                elevate ? "1" : "0", "-Log", log.toString() ) );
        if ( relaunch != null && !relaunch.isBlank() ) {
            command.add( "-Relaunch" );
            command.add( relaunch );
        }
        return command;
    }

    /**
     * Writes the helper to a temporary file and starts it.
     *
     * @param msi      the staged MSI
     * @param elevate  whether the install needs UAC (the per-machine MSI)
     * @param pids     the processes to wait for
     * @param relaunch the launcher to start afterwards, or {@code null}
     * @param log      the file the helper notes its result in
     * @param runner   starts the helper
     *
     * @throws IOException when the helper cannot be written or started
     * @since 2026.11
     */
    static void schedule( Path msi, boolean elevate, List< Long > pids, String relaunch, Path log,
                          ProcessRunner runner ) throws IOException
    {
        Path script = Files.createTempFile( "mmcl-update-", ".ps1" );
        Files.writeString( script, HELPER, StandardCharsets.UTF_8 );
        try {
            runner.startDetached( helperCommand( script, msi, elevate, pids, relaunch, log ) );
        }
        catch ( IOException | RuntimeException e ) {
            Files.deleteIfExists( script );
            throw e;
        }
    }
}
