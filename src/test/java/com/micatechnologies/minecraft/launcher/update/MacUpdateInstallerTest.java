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
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MacUpdateInstallerTest
{
    private static final String NAME = "Mica Minecraft Launcher.app";

    /**
     * Pretends to be hdiutil and ditto: attaching puts an app in the mount point, ditto copies a
     * marker file. Exit codes can be set per tool.
     */
    private static final class FakeTools implements ProcessRunner
    {
        final List< List< String > > commands = new ArrayList<>();
        final List< List< String > > detached = new ArrayList<>();
        int attachCode = 0;
        int dittoCode = 0;

        @Override
        public int run( List< String > command ) throws IOException
        {
            commands.add( command );
            String tool = command.get( 0 );
            if ( tool.endsWith( "hdiutil" ) && command.get( 1 ).equals( "attach" ) ) {
                if ( attachCode == 0 ) {
                    Path mount = Path.of( command.get( command.size() - 2 ) );
                    Files.createDirectories( mount.resolve( NAME ).resolve( "Contents" ) );
                }
                return attachCode;
            }
            if ( tool.endsWith( "ditto" ) ) {
                if ( dittoCode == 0 ) {
                    Path to = Path.of( command.get( 2 ) );
                    Files.createDirectories( to.resolve( "Contents" ) );
                    Files.writeString( to.resolve( "Contents" ).resolve( "version" ), "new" );
                }
                return dittoCode;
            }
            if ( tool.endsWith( "hdiutil" ) && command.get( 1 ).equals( "detach" ) ) {
                Path mount = Path.of( command.get( command.size() - 1 ) );
                MacUpdateInstaller.deleteTree( mount.resolve( NAME ) );
                return 0;
            }
            throw new AssertionError( "Unexpected command " + command );
        }

        @Override
        public void startDetached( List< String > command )
        {
            detached.add( command );
        }

        long count( String tool, String verb )
        {
            return commands.stream().filter( c -> c.get( 0 ).endsWith( tool ) && ( verb == null || c.get( 1 ).equals(
                    verb ) ) ).count();
        }
    }

    @Test
    void findsTheBundleAroundTheLauncher()
    {
        assertEquals( Path.of( "/Applications/" + NAME ),
                      MacUpdateInstaller.bundleOf( "/Applications/" + NAME + "/Contents/MacOS/Mica Minecraft Launcher Core" ) );
        assertNull( MacUpdateInstaller.bundleOf( "/opt/launcher/bin/launcher" ) );
        assertNull( MacUpdateInstaller.bundleOf( null ) );
    }

    @Test
    void theNewAndPreviousBundlesAreHiddenSiblings()
    {
        Path bundle = Path.of( "/Applications/" + NAME );

        assertEquals( Path.of( "/Applications/." + NAME + ".new" ), MacUpdateInstaller.incomingFor( bundle ) );
        assertEquals( Path.of( "/Applications/." + NAME + ".previous" ), MacUpdateInstaller.previousFor( bundle ) );
    }

    @Test
    void everyPathIsItsOwnArgument()
    {
        Path bundle = Path.of( "/Users/player/Applications/" + NAME );
        Path log = Path.of( "/Users/player/.MicaMinecraftLauncher/logs/update-install.log" );

        List< String > swap = MacUpdateInstaller.swapCommand( Path.of( "/tmp/h.sh" ), 42, Path.of( "/x/new app" ),
                                                              bundle, true, log );

        assertEquals( List.of( "/bin/sh", "/tmp/h.sh", "swap", "42", "/x/new app", bundle.toString(),
                               MacUpdateInstaller.previousFor( bundle ).toString(), "1", log.toString() ), swap );
        assertEquals( List.of( "/bin/sh", "/tmp/h.sh", "pkg", "7", "/x/My Update.pkg", log.toString() ),
                      MacUpdateInstaller.pkgCommand( Path.of( "/tmp/h.sh" ), 7, Path.of( "/x/My Update.pkg" ), log ) );
        assertEquals( "/x/disk image.dmg",
                      MacUpdateInstaller.attachCommand( Path.of( "/x/disk image.dmg" ), Path.of( "/m" ) ).get( 7 ) );
    }

    @Test
    void copiesTheAppOutOfTheDiskImageAndDetachesIt( @TempDir Path dir ) throws IOException
    {
        Path bundle = Files.createDirectories( dir.resolve( NAME ) );
        FakeTools tools = new FakeTools();

        Path incoming = MacUpdateInstaller.copyFromDmg( dir.resolve( "update.dmg" ), bundle, tools );

        assertEquals( MacUpdateInstaller.incomingFor( bundle ), incoming );
        assertEquals( "new", Files.readString( incoming.resolve( "Contents" ).resolve( "version" ) ) );
        assertEquals( 1, tools.count( "hdiutil", "detach" ) );
        assertTrue( tools.commands.get( 1 ).get( 1 ).endsWith( NAME ), "ditto copies the matching app" );
    }

    @Test
    void anUnreadableDiskImageChangesNothing( @TempDir Path dir ) throws IOException
    {
        Path bundle = Files.createDirectories( dir.resolve( NAME ) );
        FakeTools tools = new FakeTools();
        tools.attachCode = 1;

        assertThrows( IOException.class, () -> MacUpdateInstaller.copyFromDmg( dir.resolve( "u.dmg" ), bundle, tools ) );
        assertEquals( 0, tools.count( "hdiutil", "detach" ), "nothing was attached" );
        assertFalse( Files.exists( MacUpdateInstaller.incomingFor( bundle ) ) );
    }

    @Test
    void aFailedCopyStillDetachesAndLeavesNoHalfBundle( @TempDir Path dir ) throws IOException
    {
        Path bundle = Files.createDirectories( dir.resolve( NAME ) );
        FakeTools tools = new FakeTools();
        tools.dittoCode = 1;

        assertThrows( IOException.class, () -> MacUpdateInstaller.copyFromDmg( dir.resolve( "u.dmg" ), bundle, tools ) );
        assertEquals( 1, tools.count( "hdiutil", "detach" ) );
        assertFalse( Files.exists( MacUpdateInstaller.incomingFor( bundle ) ) );
    }

    @Test
    void findsTheAppByNameOrTheOnlyOne( @TempDir Path dir ) throws IOException
    {
        Files.createDirectories( dir.resolve( "Other.app" ) );
        assertEquals( dir.resolve( "Other.app" ), MacUpdateInstaller.findApp( dir, NAME ), "the only one" );

        Files.createDirectories( dir.resolve( NAME ) );
        assertEquals( dir.resolve( NAME ), MacUpdateInstaller.findApp( dir, NAME ), "the matching one" );

        Files.createDirectories( dir.resolve( "Third.app" ) );
        assertThrows( IOException.class, () -> MacUpdateInstaller.findApp( dir, "Missing.app" ) );
    }

    @Test
    void installDmgStartsTheSwapHelperForThisProcess( @TempDir Path dir ) throws IOException
    {
        Path bundle = Files.createDirectories( dir.resolve( NAME ) );
        FakeTools tools = new FakeTools();

        MacUpdateInstaller.installDmg( dir.resolve( "u.dmg" ), bundle, 1234, true, dir.resolve( "log" ), tools );

        assertEquals( 1, tools.detached.size() );
        List< String > helper = tools.detached.get( 0 );
        assertEquals( List.of( "swap", "1234" ), helper.subList( 2, 4 ) );
        assertEquals( MacUpdateInstaller.HELPER, Files.readString( Path.of( helper.get( 1 ) ) ) );
        Files.deleteIfExists( Path.of( helper.get( 1 ) ) );
    }

    // ---- the helper script itself, run by /bin/sh -----------------------------------------

    /** Runs the helper in the foreground with the pid of a process that has already exited. */
    private static void runHelper( String... args ) throws IOException, InterruptedException
    {
        Path script = MacUpdateInstaller.writeHelper();
        Process gone = new ProcessBuilder( "true" ).start();
        gone.waitFor();
        List< String > command = new ArrayList<>( List.of( "/bin/sh", script.toString(), args[ 0 ],
                                                           Long.toString( gone.pid() ) ) );
        command.addAll( List.of( args ).subList( 1, args.length ) );
        Process helper = new ProcessBuilder( command ).redirectErrorStream( true ).start();
        helper.getInputStream().readAllBytes();
        assertEquals( 0, helper.waitFor() );
        assertFalse( Files.exists( script ), "the helper deletes itself" );
    }

    @Test
    @DisabledOnOs( OS.WINDOWS )
    void theHelperSwapsTheBundles( @TempDir Path dir ) throws Exception
    {
        Path bundle = Files.createDirectories( dir.resolve( NAME ) );
        Files.writeString( bundle.resolve( "version" ), "old" );
        Path incoming = Files.createDirectories( MacUpdateInstaller.incomingFor( bundle ) );
        Files.writeString( incoming.resolve( "version" ), "new" );
        Path log = dir.resolve( "update-install.log" );

        runHelper( "swap", incoming.toString(), bundle.toString(), MacUpdateInstaller.previousFor( bundle ).toString(),
                   "0", log.toString() );

        assertEquals( "new", Files.readString( bundle.resolve( "version" ) ) );
        assertFalse( Files.exists( incoming ) );
        assertFalse( Files.exists( MacUpdateInstaller.previousFor( bundle ) ), "the old bundle is removed" );
        assertTrue( Files.readString( log, StandardCharsets.UTF_8 ).contains( "Installed the update" ) );
    }

    @Test
    @DisabledOnOs( OS.WINDOWS )
    void theHelperKeepsTheCurrentBundleWhenTheNewOneIsMissing( @TempDir Path dir ) throws Exception
    {
        Path bundle = Files.createDirectories( dir.resolve( NAME ) );
        Files.writeString( bundle.resolve( "version" ), "old" );
        Path log = dir.resolve( "update-install.log" );

        runHelper( "swap", MacUpdateInstaller.incomingFor( bundle ).toString(), bundle.toString(),
                   MacUpdateInstaller.previousFor( bundle ).toString(), "0", log.toString() );

        assertEquals( "old", Files.readString( bundle.resolve( "version" ) ), "moved back" );
        assertFalse( Files.exists( MacUpdateInstaller.previousFor( bundle ) ) );
        assertTrue( Files.readString( log ).contains( "kept the current version" ) );
    }

    // ---- real disk image, opt-in ------------------------------------------------------------

    /**
     * End to end with the real hdiutil and ditto: builds a disk image holding a newer bundle,
     * installs it over an older one while a stand-in launcher process runs, and checks the swap
     * after that process exits. Opt-in ({@code MMCL_RUN_MAC_UPDATE_E2E=true}): it mounts a disk
     * image, which CI machines may not allow.
     */
    @Test
    @EnabledOnOs( OS.MAC )
    @EnabledIfEnvironmentVariable( named = "MMCL_RUN_MAC_UPDATE_E2E", matches = "true" )
    void installsARealDiskImageAfterTheLauncherExits( @TempDir Path dir ) throws Exception
    {
        Path source = Files.createDirectories( dir.resolve( "source" ).resolve( NAME ).resolve( "Contents" ) );
        Files.writeString( source.resolve( "version" ), "new" );
        Path dmg = dir.resolve( "update.dmg" );
        Process create = new ProcessBuilder( "/usr/bin/hdiutil", "create", "-quiet", "-fs", "HFS+", "-volname",
                                             "Mica Update", "-srcfolder", dir.resolve( "source" ).toString(),
                                             dmg.toString() ).inheritIO().start();
        assertEquals( 0, create.waitFor() );

        Path apps = Files.createDirectories( dir.resolve( "Applications" ) );
        Path bundle = Files.createDirectories( apps.resolve( NAME ).resolve( "Contents" ) ).getParent();
        Files.writeString( bundle.resolve( "Contents" ).resolve( "version" ), "old" );
        Path log = dir.resolve( "update-install.log" );
        Process launcher = new ProcessBuilder( "sleep", "3" ).start();

        MacUpdateInstaller.installDmg( dmg, bundle, launcher.pid(), false, log, ProcessRunner.system() );
        assertEquals( "old", Files.readString( bundle.resolve( "Contents" ).resolve( "version" ) ),
                      "nothing changes while the launcher runs" );

        launcher.waitFor();
        long deadline = System.currentTimeMillis() + 20_000;
        while ( !( Files.exists( log ) && Files.readString( log ).contains( "Installed" ) )
                && System.currentTimeMillis() < deadline ) {
            Thread.sleep( 200 );
        }
        assertEquals( "new", Files.readString( bundle.resolve( "Contents" ).resolve( "version" ) ) );
        assertFalse( Files.exists( MacUpdateInstaller.incomingFor( bundle ) ) );
        assertFalse( Files.exists( MacUpdateInstaller.previousFor( bundle ) ) );
    }
}
