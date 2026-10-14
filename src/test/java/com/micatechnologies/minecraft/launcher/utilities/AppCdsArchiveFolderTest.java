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

package com.micatechnologies.minecraft.launcher.utilities;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppCdsArchiveFolderTest
{
    @Test
    void noPropertyMeansNothingToDo()
    {
        assertTrue( AppCdsArchiveFolder.archivePath( null ).isEmpty() );
        assertTrue( AppCdsArchiveFolder.archivePath( "  " ).isEmpty() );
    }

    @Test
    void anUnexpandedMacroIsIgnored()
    {
        // The packaged launcher expands $LOCALAPPDATA; an IDE run passing the raw option must not
        // create a folder literally named "$LOCALAPPDATA" in the working directory.
        assertTrue( AppCdsArchiveFolder.archivePath( "$LOCALAPPDATA\\Mica Technologies\\x\\launcher.jsa" ).isEmpty() );
    }

    @Test
    void relativePathsAreIgnored()
    {
        assertTrue( AppCdsArchiveFolder.archivePath( "launcher.jsa" ).isEmpty() );
        assertTrue( AppCdsArchiveFolder.archivePath( "appcds/launcher.jsa" ).isEmpty() );
    }

    @Test
    void anAbsolutePathIsReturned( @TempDir Path dir )
    {
        Path archive = dir.resolve( "Mica Technologies" ).resolve( "appcds" ).resolve( "launcher.jsa" );

        assertEquals( Optional.of( archive ), AppCdsArchiveFolder.archivePath( " " + archive + " " ) );
    }

    @Test
    void ensureExistsCreatesTheMissingFolder( @TempDir Path dir )
    {
        Path archive = dir.resolve( "a" ).resolve( "b" ).resolve( "launcher.jsa" );
        String previous = System.getProperty( AppCdsArchiveFolder.ARCHIVE_PROPERTY );
        System.setProperty( AppCdsArchiveFolder.ARCHIVE_PROPERTY, archive.toString() );
        try {
            AppCdsArchiveFolder.ensureExists();
        }
        finally {
            if ( previous == null ) {
                System.clearProperty( AppCdsArchiveFolder.ARCHIVE_PROPERTY );
            }
            else {
                System.setProperty( AppCdsArchiveFolder.ARCHIVE_PROPERTY, previous );
            }
        }

        assertTrue( Files.isDirectory( archive.getParent() ) );
    }

    @Test
    @EnabledOnOs( OS.WINDOWS )
    void legacyArchiveSitsInTheAppFolderBesideTheWindowsExecutable()
    {
        assertEquals( Optional.of( Path.of( "C:\\Program Files\\Mica Minecraft Launcher\\app\\launcher.jsa" ) ),
                      AppCdsArchiveFolder.legacyArchivePath(
                              "C:\\Program Files\\Mica Minecraft Launcher\\Mica Minecraft Launcher.exe",
                              "Windows 11" ) );
    }

    @Test
    void noLegacyArchiveOffWindowsOrOutsideAPackagedInstall()
    {
        assertTrue( AppCdsArchiveFolder.legacyArchivePath( "/opt/mica/bin/Mica Minecraft Launcher", "Linux" ).isEmpty() );
        assertTrue( AppCdsArchiveFolder.legacyArchivePath( null, "Windows 11" ).isEmpty() );
    }
}
