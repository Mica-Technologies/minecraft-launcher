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

package com.micatechnologies.minecraft.launcher.steam;

import com.micatechnologies.minecraft.launcher.consts.LauncherConstants;
import com.micatechnologies.minecraft.launcher.utilities.StarterHandshake;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Adds the launcher and its modpacks to a Steam library as non-Steam games, with artwork, and
 * removes them again (plan phase 7). Steam must be closed while this runs (see
 * {@link SteamProcess}); the GUI arranges that.
 *
 * @since 2026.11
 */
public final class SteamLibrary
{
    private SteamLibrary() { /* static-only */ }

    /**
     * Where entries go: one account of one Steam installation.
     *
     * @param install the installation
     * @param account the account
     *
     * @since 2026.11
     */
    public record Target( SteamLocator.SteamInstall install, SteamLocator.Account account )
    {
        /** @return {@code userdata/<account>/config/shortcuts.vdf} */
        public Path shortcutsFile()
        {
            return install.config( account.accountId() ).resolve( "shortcuts.vdf" );
        }

        /** @return {@code userdata/<account>/config/grid} */
        public Path gridFolder()
        {
            return install.config( account.accountId() ).resolve( "grid" );
        }
    }

    /**
     * One entry to add.
     *
     * @param appName       the title in the library
     * @param launchOptions {@link SteamShortcuts#launcherOptions()} or
     *                      {@link SteamShortcuts#packOptions(String, boolean)}
     * @param logo          the logo for the artwork, or {@code null}
     * @param background    the background for the artwork, or {@code null}
     *
     * @since 2026.11
     */
    public record Request( String appName, String launchOptions, BufferedImage logo, BufferedImage background )
    {
    }

    /**
     * Every account of every Steam installation that entries can be added to, the most recently
     * used first. Flatpak Steam is left out for now: its entries run inside Steam's sandbox and
     * need a {@code flatpak-spawn --host} wrapper that isn't built yet.
     *
     * @return the targets; empty when Steam isn't installed or no one has signed in
     *
     * @since 2026.11
     */
    public static List< Target > targets()
    {
        List< Target > targets = new ArrayList<>();
        for ( SteamLocator.SteamInstall install : SteamLocator.find() ) {
            if ( install.flatpak() ) {
                continue;
            }
            for ( SteamLocator.Account account : SteamLocator.accounts( install ) ) {
                targets.add( new Target( install, account ) );
            }
        }
        targets.sort( ( a, b ) -> {
            int recent = Boolean.compare( b.account().mostRecent(), a.account().mostRecent() );
            return recent != 0 ? recent : Long.compare( b.account().timestamp(), a.account().timestamp() );
        } );
        return targets;
    }

    /**
     * The program Steam entries run: the native starter (on Linux, the AppImage file itself).
     *
     * @return the path, or {@code null} outside a packaged install (an IDE or bare-jar run), where
     *         there is nothing stable for Steam to start
     *
     * @since 2026.11
     */
    public static Path launcherProgram()
    {
        String path = StarterHandshake.launcherExecutable();
        if ( path == null || path.isBlank() || path.toLowerCase( Locale.ROOT ).endsWith( ".jar" ) ) {
            return null;
        }
        try {
            return Path.of( path ).toAbsolutePath();
        }
        catch ( InvalidPathException e ) {
            return null;
        }
    }

    /**
     * The launcher's own entry.
     *
     * @return the request, with the launcher's icon as its logo
     *
     * @since 2026.11
     */
    public static Request launcherRequest()
    {
        BufferedImage icon = null;
        try ( InputStream in = SteamLibrary.class.getResourceAsStream( "/micaminecraftlauncher.png" ) ) {
            if ( in != null ) {
                icon = ImageIO.read( in );
            }
        }
        catch ( IOException ignored ) {
            // No logo: Steam shows the title as text.
        }
        BufferedImage background = null;
        try ( InputStream in = SteamLibrary.class.getResourceAsStream( "/default_background.png" ) ) {
            if ( in != null ) {
                background = ImageIO.read( in );
            }
        }
        catch ( IOException ignored ) {
            // Plain panel.
        }
        return new Request( LauncherConstants.LAUNCHER_APPLICATION_NAME, SteamShortcuts.launcherOptions(), icon,
                            background );
    }

    /**
     * Adds (or updates) entries in the target's library. Steam must be closed.
     *
     * @param target   the account
     * @param program  the program the entries run ({@link #launcherProgram()})
     * @param requests the entries
     *
     * @throws IOException when the shortcut list can't be read or written; a damaged list is
     *                     never overwritten
     * @since 2026.11
     */
    public static void add( Target target, Path program, List< Request > requests ) throws IOException
    {
        BinaryVdf.Node root = readShortcuts( target );
        String startDir = program.getParent() != null ? program.getParent().toString() : program.toString();
        for ( Request request : requests ) {
            SteamShortcuts.Entry entry = new SteamShortcuts.Entry( request.appName(), program.toString(), startDir, "",
                                                                   request.launchOptions() );
            int appId = SteamShortcuts.upsert( root, entry );
            Path icon = SteamArtwork.write( target.gridFolder(), SteamShortcuts.gridId( appId ), request.logo(),
                                            request.background() );
            SteamShortcuts.upsert( root, new SteamShortcuts.Entry( entry.appName(), entry.exe(), entry.startDir(),
                                                                   icon.toString(), entry.launchOptions() ) );
        }
        writeShortcuts( target, root );
    }

    /**
     * Removes the launcher's entry with these launch options, and its artwork. Steam must be
     * closed.
     *
     * @param target        the account
     * @param launchOptions the entry's launch options
     *
     * @return whether there was such an entry
     *
     * @throws IOException when the shortcut list can't be read or written
     * @since 2026.11
     */
    public static boolean remove( Target target, String launchOptions ) throws IOException
    {
        BinaryVdf.Node root = readShortcuts( target );
        int appId = SteamShortcuts.remove( root, launchOptions );
        if ( appId == 0 ) {
            return false;
        }
        writeShortcuts( target, root );
        for ( Path file : SteamArtwork.files( target.gridFolder(), SteamShortcuts.gridId( appId ) ) ) {
            Files.deleteIfExists( file );
        }
        return true;
    }

    /**
     * Whether the target's library has the launcher's entry with these launch options.
     *
     * @param target        the account
     * @param launchOptions the entry's launch options
     *
     * @return {@code true} when it does
     *
     * @since 2026.11
     */
    public static boolean has( Target target, String launchOptions )
    {
        try {
            return SteamShortcuts.find( readShortcuts( target ), launchOptions ) != null;
        }
        catch ( IOException e ) {
            return false;
        }
    }

    private static BinaryVdf.Node readShortcuts( Target target ) throws IOException
    {
        Path file = target.shortcutsFile();
        if ( !Files.exists( file ) || Files.size( file ) == 0 ) {
            return SteamShortcuts.emptyRoot();
        }
        return BinaryVdf.read( Files.readAllBytes( file ) );
    }

    /** Writes through a temporary file, keeping the previous list as {@code shortcuts.vdf.bak}. */
    private static void writeShortcuts( Target target, BinaryVdf.Node root ) throws IOException
    {
        Path file = target.shortcutsFile();
        Files.createDirectories( file.getParent() );
        if ( Files.exists( file ) ) {
            Files.copy( file, file.resolveSibling( "shortcuts.vdf.bak" ), StandardCopyOption.REPLACE_EXISTING );
        }
        Path temp = file.resolveSibling( "shortcuts.vdf.tmp" );
        Files.write( temp, BinaryVdf.write( root ) );
        Files.move( temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE );
    }
}
