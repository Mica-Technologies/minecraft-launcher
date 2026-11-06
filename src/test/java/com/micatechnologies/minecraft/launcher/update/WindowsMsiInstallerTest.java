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

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowsMsiInstallerTest
{
    private static final String STARTER = "C:\\Program Files\\Mica Minecraft Launcher\\Mica Minecraft Launcher.exe";
    private static final Path MSI = Path.of( "updates", "Mica.Minecraft.Launcher-26.11.20-user.msi" );
    private static final Path LOG = Path.of( "logs", "update-install.log" );

    @Test
    void waitsForTheStarterWhenItStartedThisLauncher()
    {
        assertEquals( List.of( 200L, 100L ),
                      WindowsMsiInstaller.processesToWaitFor( 200, Optional.of( 100L ), Optional.of( STARTER ),
                                                              STARTER ) );
        assertEquals( List.of( 200L, 100L ),
                      WindowsMsiInstaller.processesToWaitFor( 200, Optional.of( 100L ),
                                                              Optional.of( STARTER.toUpperCase() ), STARTER ),
                      "Windows paths compare ignoring case" );
    }

    @Test
    void anyOtherParentIsLeftAlone()
    {
        assertEquals( List.of( 200L ),
                      WindowsMsiInstaller.processesToWaitFor( 200, Optional.of( 100L ),
                                                              Optional.of( "C:\\Windows\\explorer.exe" ), STARTER ) );
        assertEquals( List.of( 200L ),
                      WindowsMsiInstaller.processesToWaitFor( 200, Optional.empty(), Optional.empty(), STARTER ) );
        assertEquals( List.of( 200L ),
                      WindowsMsiInstaller.processesToWaitFor( 200, Optional.of( 100L ), Optional.of( STARTER ), null ) );
    }

    @Test
    void theHelperGetsEveryValueAsANamedParameter()
    {
        List< String > command = WindowsMsiInstaller.helperCommand( Path.of( "h.ps1" ), MSI, true, List.of( 200L, 100L ),
                                                                    STARTER, LOG );

        assertEquals( List.of( "powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                               "-WindowStyle", "Hidden", "-File", "h.ps1", "-Msi", MSI.toString(), "-Pids", "200,100",
                               "-Elevate", "1", "-Log", LOG.toString(), "-Relaunch", STARTER ), command );
    }

    @Test
    void withoutARelaunchThereIsNoRelaunchParameter()
    {
        List< String > command = WindowsMsiInstaller.helperCommand( Path.of( "h.ps1" ), MSI, false, List.of( 200L ),
                                                                    null, LOG );

        assertFalse( command.contains( "-Relaunch" ) );
        assertEquals( "0", command.get( command.indexOf( "-Elevate" ) + 1 ) );
    }

    @Test
    void schedulingWritesTheScriptAndStartsIt() throws IOException
    {
        List< List< String > > started = new ArrayList<>();
        ProcessRunner runner = new ProcessRunner()
        {
            @Override
            public int run( List< String > command )
            {
                throw new AssertionError( "Nothing runs in the foreground" );
            }

            @Override
            public void startDetached( List< String > command )
            {
                started.add( command );
            }
        };

        WindowsMsiInstaller.schedule( MSI, false, List.of( 200L ), STARTER, LOG, runner );

        assertEquals( 1, started.size() );
        Path script = Path.of( started.get( 0 ).get( 8 ) );
        assertEquals( WindowsMsiInstaller.HELPER, Files.readString( script, StandardCharsets.UTF_8 ) );
        Files.deleteIfExists( script );
    }

    @Test
    void aHelperThatCannotStartLeavesNoScriptBehind()
    {
        List< Path > scripts = new ArrayList<>();
        ProcessRunner failing = new ProcessRunner()
        {
            @Override
            public int run( List< String > command )
            {
                return 0;
            }

            @Override
            public void startDetached( List< String > command ) throws IOException
            {
                scripts.add( Path.of( command.get( 8 ) ) );
                throw new IOException( "powershell.exe not found" );
            }
        };

        assertThrows( IOException.class,
                      () -> WindowsMsiInstaller.schedule( MSI, false, List.of( 200L ), null, LOG, failing ) );
        assertFalse( Files.exists( scripts.get( 0 ) ) );
    }

    @Test
    void theScriptInstallsPassivelyAndElevatesOnlyWhenAsked()
    {
        String script = WindowsMsiInstaller.HELPER;

        assertTrue( script.contains( "'/i', ('\"' + $Msi + '\"'), '/passive', '/norestart'" ),
                    "the MSI path is quoted for msiexec" );
        assertTrue( script.contains( "if ($Elevate -eq '1')" ) );
        assertEquals( 1, script.split( "-Verb RunAs", -1 ).length - 1, "UAC only on the elevated branch" );
        assertTrue( script.contains( "$code -eq 3010" ), "restart-required counts as success" );
        assertTrue( script.contains( "Remove-Item -LiteralPath $MyInvocation.MyCommand.Path" ) );
        assertTrue( script.chars().allMatch( c -> c < 128 ), "plain ASCII: Windows PowerShell reads BOM-less files as ANSI" );
    }
}
