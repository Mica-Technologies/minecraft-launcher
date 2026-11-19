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
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;

/**
 * Installs a macOS launcher update.
 *
 * <p>A bundle the user can replace (dragged in from the DMG): while the launcher still runs, the
 * DMG is mounted and the new bundle copied with {@code ditto} beside the old one as a hidden
 * {@code .<name>.app.new}. A small helper script then waits for the launcher to exit, moves the old
 * bundle aside, moves the new one into its place (putting the old one back if that fails), and
 * opens it again. The swap is two renames in the same folder, so it is quick and never leaves a
 * half-copied bundle.
 *
 * <p>A bundle the PKG installed as root: the helper waits for the launcher to exit and opens the
 * package in Installer, which asks for the administrator password; the user starts the launcher
 * again once it is done.
 *
 * <p>The helper outlives the launcher, so it is a {@code /bin/sh} script (see {@link #HELPER});
 * every path reaches it as a separate argument, never inside the script text, so names with spaces
 * or quotes need no escaping. It appends what it did to the given log file.
 *
 * @since 2026.11
 */
public final class MacUpdateInstaller
{
    /**
     * The helper script. Modes:
     * <pre>
     *   swap &lt;pid&gt; &lt;new bundle&gt; &lt;bundle&gt; &lt;previous&gt; &lt;relaunch 1|0&gt; &lt;log&gt;
     *   pkg  &lt;pid&gt; &lt;package&gt; &lt;log&gt;
     * </pre>
     * It waits up to five minutes for {@code pid} to exit, then deletes itself when done.
     */
    static final String HELPER = """
            #!/bin/sh
            # Finishes a Mica Minecraft Launcher update once the launcher has exited.
            # Written by MacUpdateInstaller; deletes itself when done.
            trap '' HUP
            waitfor() {
                n=0
                while kill -0 "$1" 2>/dev/null; do
                    sleep 0.5
                    n=$((n + 1))
                    if [ "$n" -gt 600 ]; then
                        return 1
                    fi
                done
                return 0
            }
            note() {
                printf '%s %s\\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*" >> "$LOG"
            }
            mode="$1"
            pid="$2"
            case "$mode" in
            swap)
                new="$3"; app="$4"; old="$5"; relaunch="$6"; LOG="$7"
                if ! waitfor "$pid"; then
                    note "The launcher (process $pid) did not exit; the update was not installed"
                    rm -rf "$new"
                else
                    rm -rf "$old"
                    if mv "$app" "$old"; then
                        if mv "$new" "$app"; then
                            rm -rf "$old"
                            note "Installed the update into $app"
                        else
                            mv "$old" "$app"
                            rm -rf "$new"
                            note "Could not move the update into place; kept the current version"
                        fi
                    else
                        rm -rf "$new"
                        note "Could not move $app aside; the update was not installed"
                    fi
                fi
                if [ "$relaunch" = 1 ]; then
                    open "$app"
                fi
                ;;
            pkg)
                pkg="$3"; LOG="$4"
                if ! waitfor "$pid"; then
                    note "The launcher (process $pid) did not exit; the installer was not opened"
                elif open "$pkg"; then
                    note "Opened the installer package $pkg"
                else
                    note "Could not open the installer package $pkg"
                fi
                ;;
            esac
            rm -f "$0"
            """;

    private MacUpdateInstaller() { /* static-only */ }

    /**
     * The app bundle that contains a jpackage launcher.
     *
     * @param appPath the launcher's path ({@code jpackage.app-path}), inside {@code <name>.app/Contents/}
     *
     * @return the bundle, or {@code null} when the path is not inside one
     *
     * @since 2026.11
     */
    static Path bundleOf( String appPath )
    {
        if ( appPath == null ) {
            return null;
        }
        int end = appPath.indexOf( ".app/Contents/" );
        return end < 0 ? null : Path.of( appPath.substring( 0, end + ".app".length() ) );
    }

    /**
     * Where the new bundle waits beside the old one until the swap.
     *
     * @param bundle the installed bundle
     *
     * @return the hidden sibling {@code .<name>.app.new}
     */
    static Path incomingFor( Path bundle )
    {
        return bundle.resolveSibling( "." + bundle.getFileName() + ".new" );
    }

    /**
     * Where the old bundle goes during the swap.
     *
     * @param bundle the installed bundle
     *
     * @return the hidden sibling {@code .<name>.app.previous}
     */
    static Path previousFor( Path bundle )
    {
        return bundle.resolveSibling( "." + bundle.getFileName() + ".previous" );
    }

    /** @return {@code hdiutil} attaching a DMG read-only, unseen, at a chosen mount point */
    static List< String > attachCommand( Path dmg, Path mountPoint )
    {
        return List.of( "/usr/bin/hdiutil", "attach", "-nobrowse", "-readonly", "-noautoopen", "-mountpoint",
                        mountPoint.toString(), dmg.toString() );
    }

    /** @return {@code hdiutil} detaching a mount point, forcing it if something still holds it */
    static List< String > detachCommand( Path mountPoint, boolean force )
    {
        return force ? List.of( "/usr/bin/hdiutil", "detach", "-force", mountPoint.toString() )
                     : List.of( "/usr/bin/hdiutil", "detach", mountPoint.toString() );
    }

    /** @return {@code ditto} copying a bundle with its attributes and signature intact */
    static List< String > dittoCommand( Path from, Path to )
    {
        return List.of( "/usr/bin/ditto", from.toString(), to.toString() );
    }

    /** @return the helper's swap invocation */
    static List< String > swapCommand( Path script, long pid, Path incoming, Path bundle, boolean relaunch, Path log )
    {
        return List.of( "/bin/sh", script.toString(), "swap", Long.toString( pid ), incoming.toString(),
                        bundle.toString(), previousFor( bundle ).toString(), relaunch ? "1" : "0", log.toString() );
    }

    /** @return the helper's installer-package invocation */
    static List< String > pkgCommand( Path script, long pid, Path pkg, Path log )
    {
        return List.of( "/bin/sh", script.toString(), "pkg", Long.toString( pid ), pkg.toString(), log.toString() );
    }

    /**
     * Copies the new bundle out of the staged DMG next to the installed one, ready for the swap.
     *
     * @param dmg    the staged DMG
     * @param bundle the installed bundle
     * @param runner runs {@code hdiutil} and {@code ditto}
     *
     * @return the copied bundle ({@link #incomingFor})
     *
     * @throws IOException when the DMG cannot be mounted, holds no app, or the copy fails
     * @since 2026.11
     */
    static Path copyFromDmg( Path dmg, Path bundle, ProcessRunner runner ) throws IOException
    {
        Path incoming = incomingFor( bundle );
        deleteTree( incoming );
        Path mountPoint = Files.createTempDirectory( "mmcl-update-" );
        boolean attached = false;
        try {
            if ( runner.run( attachCommand( dmg, mountPoint ) ) != 0 ) {
                throw new IOException( "Could not open the update's disk image" );
            }
            attached = true;
            Path app = findApp( mountPoint, bundle.getFileName().toString() );
            if ( runner.run( dittoCommand( app, incoming ) ) != 0 || !Files.isDirectory( incoming ) ) {
                deleteTree( incoming );
                throw new IOException( "Could not copy the new version out of the disk image" );
            }
            return incoming;
        }
        finally {
            if ( attached && runner.run( detachCommand( mountPoint, false ) ) != 0 ) {
                runner.run( detachCommand( mountPoint, true ) );
            }
            try {
                Files.deleteIfExists( mountPoint );
            }
            catch ( IOException ignored ) {
                // Still mounted after a forced detach: macOS removes it on the next restart.
            }
        }
    }

    /**
     * The app in a mounted DMG: the one named like the installed bundle, else the only one.
     *
     * @throws IOException when there is none, or several and none matches
     */
    static Path findApp( Path mountPoint, String bundleName ) throws IOException
    {
        List< Path > apps = new ArrayList<>();
        try ( DirectoryStream< Path > entries = Files.newDirectoryStream( mountPoint, "*.app" ) ) {
            for ( Path entry : entries ) {
                if ( Files.isDirectory( entry ) ) {
                    if ( entry.getFileName().toString().equals( bundleName ) ) {
                        return entry;
                    }
                    apps.add( entry );
                }
            }
        }
        if ( apps.size() != 1 ) {
            throw new IOException( "The update's disk image does not hold one launcher app" );
        }
        return apps.get( 0 );
    }

    /**
     * Writes the helper script to a new temporary file.
     *
     * @return the script
     *
     * @throws IOException when it cannot be written
     */
    static Path writeHelper() throws IOException
    {
        Path script = Files.createTempFile( "mmcl-update-", ".sh" );
        Files.writeString( script, HELPER, StandardCharsets.UTF_8 );
        Files.setPosixFilePermissions( script, PosixFilePermissions.fromString( "rwx------" ) );
        return script;
    }

    /**
     * Prepares a DMG update and starts the helper that swaps it in once the launcher has exited.
     *
     * @param dmg      the staged DMG
     * @param bundle   the installed bundle
     * @param pid      the launcher's process, which the helper waits for
     * @param relaunch whether the helper opens the launcher again
     * @param log      the file the helper notes its result in
     * @param runner   runs the tools and starts the helper
     *
     * @throws IOException when preparing or starting the helper fails; nothing was changed
     * @since 2026.11
     */
    static void installDmg( Path dmg, Path bundle, long pid, boolean relaunch, Path log, ProcessRunner runner )
            throws IOException
    {
        Path incoming = copyFromDmg( dmg, bundle, runner );
        try {
            Path script = writeHelper();
            runner.startDetached( swapCommand( script, pid, incoming, bundle, relaunch, log ) );
        }
        catch ( IOException | RuntimeException e ) {
            deleteTree( incoming );
            throw e;
        }
    }

    /**
     * Starts the helper that opens the staged installer package once the launcher has exited.
     *
     * @param pkg    the staged package
     * @param pid    the launcher's process
     * @param log    the file the helper notes its result in
     * @param runner starts the helper
     *
     * @throws IOException when the helper cannot be started
     * @since 2026.11
     */
    static void schedulePkg( Path pkg, long pid, Path log, ProcessRunner runner ) throws IOException
    {
        runner.startDetached( pkgCommand( writeHelper(), pid, pkg, log ) );
    }

    /** Deletes a folder tree, if there is one; symbolic links are removed, never followed. */
    static void deleteTree( Path root ) throws IOException
    {
        if ( !Files.exists( root, java.nio.file.LinkOption.NOFOLLOW_LINKS ) ) {
            return;
        }
        Files.walkFileTree( root, new SimpleFileVisitor<>()
        {
            @Override
            public FileVisitResult visitFile( Path file, BasicFileAttributes attrs ) throws IOException
            {
                Files.delete( file );
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory( Path dir, IOException exc ) throws IOException
            {
                if ( exc != null ) {
                    throw exc;
                }
                Files.delete( dir );
                return FileVisitResult.CONTINUE;
            }
        } );
    }
}
