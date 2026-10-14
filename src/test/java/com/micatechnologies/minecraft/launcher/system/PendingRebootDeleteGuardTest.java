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

package com.micatechnologies.minecraft.launcher.system;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PendingRebootDeleteGuardTest
{
    private static final Path INSTALL = Path.of( "C:\\Program Files\\Mica Minecraft Launcher" );

    private static final String MODULES = INSTALL + "\\runtime\\lib\\modules";

    private static final String OLD_JAR = INSTALL + "\\app\\launcher-2026.10.09-jar-with-dependencies.jar";

    @Test
    void decodeKeepsEmptyDeleteTargetsSoPairsStayAligned()
    {
        // Two deletes then a move, as Windows stores them: each entry NUL-terminated, the list
        // ending in an extra NUL.
        String raw = "\\??\\C:\\a\0\0\\??\\C:\\b\0\0\\??\\C:\\c\0!\\??\\C:\\d\0\0";

        List< String > entries = PendingRebootDeleteGuard.decodeMultiString( raw );

        assertEquals( List.of( "\\??\\C:\\a", "", "\\??\\C:\\b", "", "\\??\\C:\\c", "!\\??\\C:\\d" ), entries );
    }

    @Test
    void decodeRestoresATrailingDeleteTarget()
    {
        String raw = "\\??\\C:\\a\0\0\0";

        assertEquals( List.of( "\\??\\C:\\a", "" ), PendingRebootDeleteGuard.decodeMultiString( raw ) );
    }

    @Test
    void decodeOfEmptyValueIsEmpty()
    {
        assertTrue( PendingRebootDeleteGuard.decodeMultiString( "\0\0" ).isEmpty() );
    }

    @Test
    void parseStripsNtPrefixAndReplaceMarker()
    {
        var operations = PendingRebootDeleteGuard.parse(
                List.of( "\\??\\C:\\a", "", "\\??\\C:\\c", "!\\??\\C:\\d" ) );

        assertEquals( List.of( new PendingRebootDeleteGuard.PendingOperation( "C:\\a", "" ),
                               new PendingRebootDeleteGuard.PendingOperation( "C:\\c", "C:\\d" ) ),
                      operations );
        assertTrue( operations.get( 0 ).isDelete() );
        assertFalse( operations.get( 1 ).isDelete() );
    }

    @Test
    void parseStripsTheWindows11FlagPrefix()
    {
        // Seen on Windows 11 26100: every entry carries a "*1" flag in front of the NT prefix.
        assertEquals( "C:\\Program Files\\x", PendingRebootDeleteGuard.stripPrefix( "*1\\??\\C:\\Program Files\\x" ) );
        assertEquals( "C:\\y", PendingRebootDeleteGuard.stripPrefix( "!*1\\??\\C:\\y" ) );
        assertEquals( "C:\\z", PendingRebootDeleteGuard.stripPrefix( "C:\\z" ) );
    }

    @Test
    void theOctoberIncidentFlagsModulesButNotTheOldJar()
    {
        // What 26.10.09's uninstall queued on 2026-10-13 (Info 1903 in the MSI log), as decoded.
        var operations = List.of( new PendingRebootDeleteGuard.PendingOperation( OLD_JAR, "" ),
                                  new PendingRebootDeleteGuard.PendingOperation( MODULES, "" ),
                                  new PendingRebootDeleteGuard.PendingOperation( MODULES, "" ),
                                  new PendingRebootDeleteGuard.PendingOperation( "C:\\Config.Msi\\84b6586.rbf", "" ) );
        Set< String > classpath = Set.of( "launcher-2026.10.10-jar-with-dependencies.jar" );

        List< Path > lost = PendingRebootDeleteGuard.threatenedFiles(
                operations, INSTALL, path -> true,
                file -> PendingRebootDeleteGuard.isNeeded( INSTALL, file, classpath ) );

        assertEquals( List.of( Path.of( MODULES ) ), lost );
    }

    @Test
    void deleteFollowedByMoveOntoTheSamePathIsAReplacement()
    {
        var operations = List.of( new PendingRebootDeleteGuard.PendingOperation( MODULES, "" ),
                                  new PendingRebootDeleteGuard.PendingOperation( "C:\\Config.Msi\\new.tmp", MODULES ) );

        assertTrue( PendingRebootDeleteGuard.threatenedFiles( operations, INSTALL, path -> true, path -> true )
                                            .isEmpty() );
    }

    @Test
    void comparisonIgnoresCaseAndSlashes()
    {
        var operations = List.of( new PendingRebootDeleteGuard.PendingOperation(
                "c:/program files/mica minecraft launcher/RUNTIME/lib/modules", "" ) );

        assertEquals( 1, PendingRebootDeleteGuard.threatenedFiles( operations, INSTALL, path -> true,
                                                                   path -> true ).size() );
    }

    @Test
    void filesThatNoLongerExistOrSitOutsideTheInstallAreIgnored()
    {
        var operations = List.of( new PendingRebootDeleteGuard.PendingOperation( MODULES, "" ),
                                  new PendingRebootDeleteGuard.PendingOperation( "C:\\Program Files\\Mica Minecraft Launcher Other\\x", "" ),
                                  new PendingRebootDeleteGuard.PendingOperation( "C:\\Windows\\Temp\\y", "" ) );

        assertTrue( PendingRebootDeleteGuard.threatenedFiles( operations, INSTALL, path -> false, path -> true )
                                            .isEmpty() );
        assertTrue( PendingRebootDeleteGuard.threatenedFiles(
                operations.subList( 1, 3 ), INSTALL, path -> true, path -> true ).isEmpty() );
    }

    @Test
    void classpathJarsReadFromLauncherConfig()
    {
        var jars = PendingRebootDeleteGuard.classpathJars( List.of(
                "[Application]",
                "app.classpath=$APPDIR\\launcher-2026.10.10-jar-with-dependencies.jar",
                "app.mainclass=com.micatechnologies.minecraft.launcher.LauncherCore" ) );

        assertEquals( Set.of( "launcher-2026.10.10-jar-with-dependencies.jar" ), jars );
    }

    @Test
    void everythingOutsideAppJarsIsNeeded()
    {
        Set< String > classpath = Set.of( "current.jar" );

        assertTrue( PendingRebootDeleteGuard.isNeeded( INSTALL, Path.of( MODULES ), classpath ) );
        assertTrue( PendingRebootDeleteGuard.isNeeded( INSTALL, INSTALL.resolve( "app\\Current.JAR" ), classpath ) );
        assertTrue( PendingRebootDeleteGuard.isNeeded( INSTALL, INSTALL.resolve( "app\\Mica Minecraft Launcher.cfg" ),
                                                       classpath ) );
        assertFalse( PendingRebootDeleteGuard.isNeeded( INSTALL, INSTALL.resolve( "app\\old.jar" ), classpath ) );
    }

    @Test
    void removalScriptTargetsSessionManagerAndBothValues()
    {
        String script = PendingRebootDeleteGuard.removalScript();

        assertTrue( script.contains( "SYSTEM\\CurrentControlSet\\Control\\Session Manager" ) );
        assertTrue( script.contains( "'PendingFileRenameOperations','PendingFileRenameOperations2'" ) );
        assertTrue( script.contains( "$dst -eq ''" ), "only delete entries may be dropped" );
    }
}
