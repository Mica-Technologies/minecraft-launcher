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

import com.micatechnologies.minecraft.launcher.consts.LauncherConstants;
import com.micatechnologies.minecraft.launcher.consts.ModPackConstants;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.WinReg;
import org.apache.commons.lang3.SystemUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Runtime self-registration of the {@code mmcl://} URL scheme and the
 * {@link ModPackConstants#MODPACK_FILE_EXTENSION .mmcjson} file association with the host OS.
 * Idempotent — running on every launcher startup just rewrites the same values.
 *
 * <p>Why runtime instead of installer-time:</p>
 * <ul>
 *     <li>Works for users who installed via portable JAR or jpackage, without requiring
 *         platform-specific installer scripts to be in sync.</li>
 *     <li>Picks up launcher-exe path changes automatically across upgrades (the registry
 *         entries on Windows / the {@code .desktop} file on Linux always point at the
 *         currently-running binary).</li>
 *     <li>No admin rights required — everything writes under HKCU / {@code ~/.local}.</li>
 * </ul>
 *
 * <p>macOS is intentionally a no-op here. Cocoa's URL-scheme + UTI registration is driven
 * by {@code Info.plist} entries that jpackage emits at build time — runtime self-registration
 * would race with Launch Services' cache. The follow-up to wire macOS is a custom
 * {@code Info.plist} in jpackage's {@code --resource-dir} (tracked in
 * {@code OS_INTEGRATION_POLISH.md}).</p>
 *
 * <p>Dev-mode runs are also skipped — registering the IDE's javaw.exe as the
 * {@code mmcl://} handler would persist across the dev session and conflict with any
 * production install on the same machine.</p>
 */
public final class SchemeRegistrar
{
    /** ProgID for the {@code .mmcjson} file extension on Windows. Namespaced under our product
     *  to avoid colliding with another app that might want a generic "Modpack" handler. */
    private static final String WIN_MMCJSON_PROG_ID = "MicaMinecraftLauncher.Modpack";

    /** Friendly description shown next to the {@code mmcl://} entry in Windows' "Default Apps"
     *  by-protocol view. */
    private static final String WIN_URL_DESCRIPTION = "URL:Mica Minecraft Launcher Protocol";

    private SchemeRegistrar() { /* static-only */ }

    /**
     * Idempotently registers the URL scheme + file extension with the host OS. Safe to call
     * from any thread; long enough to want to run off the launcher thread on Linux because of
     * the optional {@code update-desktop-database} sub-process.
     */
    public static void registerIfNeeded()
    {
        if ( LauncherConstants.LAUNCHER_IS_DEV ) {
            Logger.logDebug( "SchemeRegistrar: dev mode, skipping OS scheme/file-extension registration." );
            return;
        }
        String exePath = resolveLauncherExePath();
        if ( exePath == null ) {
            Logger.logDebug( "SchemeRegistrar: launcher exe path unknown (not jpackage-launched?), skipping." );
            return;
        }
        try {
            if ( SystemUtils.IS_OS_WINDOWS ) {
                registerWindows( exePath );
            }
            else if ( SystemUtils.IS_OS_LINUX ) {
                registerLinux( exePath );
            }
            // macOS handled via Info.plist in the jpackage --resource-dir customization
            // (documented in OS_INTEGRATION_POLISH.md). Runtime registration would race
            // with Launch Services' cache.
        }
        catch ( Exception | Error e ) {
            Logger.logWarningSilent( "Scheme/file-association registration failed: " + e.getMessage() );
        }
    }

    /**
     * Re-runs the platform-specific registration step, which on Linux also
     * rewrites the {@code Actions=} block in the {@code .desktop} file with
     * the current recently-played modpack list. Called from
     * {@link com.micatechnologies.minecraft.launcher.LauncherCore#play} after
     * a successful launch so the next time the user right-clicks the launcher
     * icon in the application menu, the just-played pack is at the top of
     * the recents.
     *
     * <p>Cheap on Linux (one file write + an optional desktop-database
     * refresh). On Windows + macOS this currently just rewrites the URL
     * scheme registry / .plist entries — same as a no-op call to
     * {@link #registerIfNeeded()}; the Windows jump-list refresh is wired
     * separately through {@link JumpListManager}.</p>
     */
    public static void refreshRecentPacks()
    {
        // Same path as the startup registration — the only state that
        // changed between calls is the list of recent packs, which the
        // Linux registration path re-reads via RecentPacks.getRecent.
        registerIfNeeded();
    }

    /** Resolves the absolute path of the user-facing launcher executable. jpackage sets
     *  {@code jpackage.app-path} as a system property pointing at the wrapper exe — without
     *  that, we're running from a raw JAR / IDE classpath and there's no stable exe to
     *  register. */
    private static String resolveLauncherExePath()
    {
        String jpackagePath = System.getProperty( "jpackage.app-path" );
        if ( jpackagePath != null && !jpackagePath.isBlank() ) {
            return jpackagePath;
        }
        return null;
    }

    // =========================================================================================
    //  Windows — HKCU\Software\Classes registry writes via jna-platform Advapi32Util
    // =========================================================================================

    private static void registerWindows( String exePath )
    {
        WinReg.HKEY root = WinReg.HKEY_CURRENT_USER;
        String classes = "Software\\Classes";
        String command = "\"" + exePath + "\" \"%1\"";

        // ---- mmcl:// URL scheme ----
        // The canonical Windows shape is documented at
        // https://learn.microsoft.com/en-us/previous-versions/windows/internet-explorer/ie-developer/platform-apis/aa767914(v=vs.85)
        //   HKCU\Software\Classes\mmcl
        //     (default) = "URL:Mica Minecraft Launcher Protocol"
        //     "URL Protocol" = ""               ← presence of this empty value is what marks the key as a URL handler
        //   HKCU\Software\Classes\mmcl\shell\open\command
        //     (default) = "<launcher exe>" "%1"
        String mmclKey = classes + "\\mmcl";
        ensureKey( root, classes, "mmcl" );
        Advapi32Util.registrySetStringValue( root, mmclKey, "", WIN_URL_DESCRIPTION );
        Advapi32Util.registrySetStringValue( root, mmclKey, "URL Protocol", "" );
        ensureCommandSubkey( root, mmclKey, command );

        // ---- .mmcjson file extension → ProgID → handler ----
        String extension = ModPackConstants.MODPACK_FILE_EXTENSION; // ".mmcjson"
        ensureKey( root, classes, extension );
        Advapi32Util.registrySetStringValue( root, classes + "\\" + extension, "", WIN_MMCJSON_PROG_ID );

        String progIdKey = classes + "\\" + WIN_MMCJSON_PROG_ID;
        ensureKey( root, classes, WIN_MMCJSON_PROG_ID );
        Advapi32Util.registrySetStringValue( root, progIdKey, "", ModPackConstants.MODPACK_FILE_DESCRIPTION );
        ensureCommandSubkey( root, progIdKey, command );

        // DefaultIcon points at the bundled .mmcjson icon so Explorer
        // distinguishes modpack files from generic launcher-association
        // entries. The .ico is extracted from JAR resources to a stable
        // path under the launcher config folder; the registry entry uses
        // the {@code <path>,0} form (icon index 0 = the first / largest
        // image in the .ico's directory).
        String iconPath = extractMmcjsonIconForWindows();
        if ( iconPath != null ) {
            ensureKey( root, progIdKey, "DefaultIcon" );
            Advapi32Util.registrySetStringValue( root, progIdKey + "\\DefaultIcon", "",
                                                  "\"" + iconPath + "\",0" );
        }

        Logger.logDebug( "Windows scheme + file-association registered for " + exePath );
    }

    /** Extracts the bundled {@code mmcjson-file.ico} from JAR resources to a
     *  stable path under the launcher's config folder. Returns the absolute
     *  path on success, or {@code null} when the resource can't be found /
     *  extracted (e.g. running from a stripped JAR). Idempotent — re-running
     *  overwrites the existing file so launcher updates that ship a refreshed
     *  icon land automatically on next startup. */
    private static String extractMmcjsonIconForWindows()
    {
        try {
            Path destDir = Paths.get(
                    com.micatechnologies.minecraft.launcher.files.LocalPathManager
                            .getLauncherConfigFolderPath(),
                    "icons" );
            Files.createDirectories( destDir );
            Path dest = destDir.resolve( "mmcjson-file.ico" );

            try ( java.io.InputStream in = SchemeRegistrar.class
                    .getResourceAsStream( "/mmcjson-file.ico" ) ) {
                if ( in == null ) {
                    Logger.logWarningSilent( "Bundled mmcjson-file.ico not found in JAR resources." );
                    return null;
                }
                Files.copy( in, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING );
            }
            return dest.toAbsolutePath().toString();
        }
        catch ( Exception | Error e ) {
            Logger.logWarningSilent( "Could not stage .mmcjson Explorer icon: " + e.getMessage() );
            return null;
        }
    }

    /** Creates {@code parent\name} if it doesn't already exist. Wraps the per-call boolean
     *  return of {@code Advapi32Util.registryCreateKey} so the call sites read cleanly. */
    private static void ensureKey( WinReg.HKEY root, String parent, String name )
    {
        try {
            Advapi32Util.registryCreateKey( root, parent, name );
        }
        catch ( Exception | Error ignored ) {
            // registryCreateKey throws if the parent is missing AND can't be created. With
            // HKCU\Software\Classes always present this practically never fires; if it does
            // the higher-level catch in registerIfNeeded surfaces it.
        }
    }

    /** Writes the standard {@code <key>\shell\open\command} chain pointing at the launcher exe. */
    private static void ensureCommandSubkey( WinReg.HKEY root, String parentKey, String command )
    {
        ensureKey( root, parentKey, "shell" );
        ensureKey( root, parentKey + "\\shell", "open" );
        ensureKey( root, parentKey + "\\shell\\open", "command" );
        Advapi32Util.registrySetStringValue( root, parentKey + "\\shell\\open\\command", "", command );
    }

    // =========================================================================================
    //  Linux — write a .desktop file under ~/.local/share/applications
    // =========================================================================================

    private static void registerLinux( String exePath ) throws IOException
    {
        Path home = Paths.get( System.getProperty( "user.home" ) );
        Path desktopDir = home.resolve( ".local/share/applications" );
        Files.createDirectories( desktopDir );
        Path desktopFile = desktopDir.resolve( "mica-minecraft-launcher.desktop" );

        // %u expands to the URI / file path the OS hands us. Quote the exec so launcher paths
        // containing spaces work. NoDisplay=false because users want the launcher in their
        // app menu too — it's both a registered handler and a regular app.
        StringBuilder content = new StringBuilder( 512 );
        content.append( "[Desktop Entry]\n" );
        content.append( "Type=Application\n" );
        content.append( "Name=" ).append( LauncherConstants.LAUNCHER_APPLICATION_NAME ).append( "\n" );
        content.append( "Exec=\"" ).append( exePath ).append( "\" %u\n" );
        content.append( "Terminal=false\n" );
        content.append( "Icon=mica-minecraft-launcher\n" );
        content.append( "MimeType=x-scheme-handler/mmcl;application/x-mmcjson;\n" );
        content.append( "Categories=Game;\n" );
        // Surfaces the launcher in app-menu / overview search under common terms, not just its name.
        content.append( "Keywords=minecraft;modpack;forge;launcher;mica;\n" );
        content.append( "NoDisplay=false\n" );
        appendRecentPacksActions( content, exePath );
        Files.writeString( desktopFile, content.toString(), StandardCharsets.UTF_8 );

        // Portable / non-jpackage installs don't get the deb/rpm's icon-theme + MIME registration,
        // so stage them here too (both idempotent — skipped once present, so the post-launch recents
        // refresh doesn't redo the work every launch):
        //   - the launcher icon into the user hicolor theme so Icon=mica-minecraft-launcher resolves
        //   - the .mmcjson MIME type so double-clicking a modpack file maps to the handler above
        installLinuxAppIcon();
        registerLinuxMimeType();

        // Best-effort cache refresh. Both commands are no-ops if not installed — we don't
        // want missing helpers to fail the whole registration.
        runQuietly( "update-desktop-database", desktopDir.toString() );

        Logger.logDebug( "Linux scheme + file-association registered via " + desktopFile );
    }

    /** Number of recently-played modpacks surfaced in the {@code .desktop}
     *  {@code Actions=} field. The Desktop Entry Spec doesn't impose a hard
     *  cap, but most file managers / panel launchers truncate after 5-6
     *  entries in their right-click menus, so stay inside that window. */
    private static final int RECENT_PACKS_IN_DESKTOP_FILE = 5;

    /**
     * Appends an {@code Actions=} field + per-pack {@code [Desktop Action ...]}
     * sections to the in-progress {@code .desktop} file content, one entry per
     * recently-played modpack. Each entry shells the launcher exe with a
     * {@code mmcl://play?name=...} URL — the single-instance lock forwards
     * that to the already-running launcher (or cold-starts it) which then
     * dispatches the action via {@code LauncherUriHandler}.
     *
     * <p>No-op when the user has never played a pack yet; the resulting
     * {@code .desktop} file just doesn't carry an {@code Actions=} line.</p>
     */
    private static void appendRecentPacksActions( StringBuilder content, String exePath )
    {
        java.util.List< com.micatechnologies.minecraft.launcher.game.modpack.GameModPack > recent;
        try {
            recent = RecentPacks.getRecent( RECENT_PACKS_IN_DESKTOP_FILE );
        }
        catch ( Throwable t ) {
            // The recent-packs lookup touches per-pack metadata files; a
            // mid-uninstall race could surface as an IOException etc.
            // Skip the Actions= block entirely rather than failing the
            // whole .desktop registration.
            Logger.logWarningSilent( "RecentPacks lookup failed during .desktop refresh: "
                                             + t.getClass().getSimpleName() );
            return;
        }
        if ( recent.isEmpty() ) return;

        StringBuilder actionsList = new StringBuilder();
        StringBuilder actionsSections = new StringBuilder();
        int idx = 0;
        for ( com.micatechnologies.minecraft.launcher.game.modpack.GameModPack p : recent ) {
            String packName = p.getPackName();
            if ( packName == null || packName.isBlank() ) continue;
            // Desktop Entry Spec: action identifiers must be [A-Za-z0-9-] only.
            // Use a stable but spec-conformant ID derived from the loop index
            // so repeated refreshes overwrite the same entries.
            String actionId = "play-pack-" + idx;
            String display = p.getFriendlyName();
            if ( display == null || display.isBlank() ) display = packName;
            actionsList.append( actionId ).append( ';' );
            actionsSections.append( "\n[Desktop Action " ).append( actionId ).append( "]\n" );
            actionsSections.append( "Name=" ).append( desktopEntryEscape( display ) ).append( '\n' );
            actionsSections.append( "Exec=\"" ).append( exePath ).append( "\" \"mmcl://play?name=" )
                           .append( urlEncodeForDesktopExec( packName ) ).append( "\"\n" );
            idx++;
        }
        if ( idx == 0 ) return;
        content.append( "Actions=" ).append( actionsList ).append( '\n' );
        content.append( actionsSections );
    }

    /** Escapes a string for safe inclusion in a Desktop Entry value. The spec
     *  treats CR/LF as separators and a handful of characters as field-quote
     *  triggers; strip those defensively. */
    private static String desktopEntryEscape( String value )
    {
        StringBuilder sb = new StringBuilder( value.length() );
        for ( int i = 0; i < value.length(); i++ ) {
            char c = value.charAt( i );
            if ( c == '\n' || c == '\r' ) continue;     // line terminators
            if ( c < 0x20 ) continue;                   // control chars
            sb.append( c );
        }
        return sb.toString();
    }

    /** Percent-encodes a string for safe inclusion in the {@code mmcl://play?name=...}
     *  query string. Spec-correct URL encoding via {@code URLEncoder} would emit
     *  {@code +} for spaces, which we don't want (URI parsers handle %20 better);
     *  do the small subset ourselves. */
    private static String urlEncodeForDesktopExec( String value )
    {
        StringBuilder sb = new StringBuilder( value.length() );
        for ( int i = 0; i < value.length(); i++ ) {
            char c = value.charAt( i );
            if ( ( c >= 'a' && c <= 'z' ) || ( c >= 'A' && c <= 'Z' )
                    || ( c >= '0' && c <= '9' )
                    || c == '-' || c == '_' || c == '.' || c == '~' ) {
                sb.append( c );
            }
            else {
                byte[] bytes = String.valueOf( c ).getBytes( StandardCharsets.UTF_8 );
                for ( byte b : bytes ) {
                    sb.append( '%' );
                    sb.append( String.format( "%02X", b & 0xFF ) );
                }
            }
        }
        return sb.toString();
    }

    /** Extracts the bundled launcher PNG and writes scaled copies into the per-user hicolor icon
     *  theme as {@code mica-minecraft-launcher.png}, so the {@code .desktop} file's
     *  {@code Icon=mica-minecraft-launcher} resolves on portable / non-jpackage installs (the
     *  deb/rpm ship this icon themselves). Several standard sizes are emitted so panels that only
     *  scan specific sizes still find it. Idempotent: skips the work when already staged, so the
     *  post-launch recents refresh doesn't re-scale every launch. Remove the dir to force a refresh
     *  after an icon change. Offscreen image work — safe on headless ({@code --cli}) Linux. */
    private static void installLinuxAppIcon()
    {
        try {
            Path hicolor = Paths.get( System.getProperty( "user.home" ), ".local", "share", "icons", "hicolor" );
            Path sentinel = hicolor.resolve( "256x256/apps/mica-minecraft-launcher.png" );
            if ( Files.exists( sentinel ) ) {
                return;
            }
            java.awt.image.BufferedImage src;
            try ( java.io.InputStream in = SchemeRegistrar.class.getClassLoader()
                    .getResourceAsStream( "micaminecraftlauncher.png" ) ) {
                if ( in == null ) {
                    Logger.logWarningSilent( "Launcher icon resource missing; skipping Linux icon install." );
                    return;
                }
                src = javax.imageio.ImageIO.read( in );
            }
            if ( src == null ) {
                return;
            }
            for ( int size : new int[] { 256, 128, 64, 48, 32 } ) {
                Path dir = hicolor.resolve( size + "x" + size ).resolve( "apps" );
                Files.createDirectories( dir );
                java.awt.image.BufferedImage scaled =
                        new java.awt.image.BufferedImage( size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB );
                java.awt.Graphics2D g = scaled.createGraphics();
                g.setRenderingHint( java.awt.RenderingHints.KEY_INTERPOLATION,
                                    java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR );
                g.setRenderingHint( java.awt.RenderingHints.KEY_RENDERING,
                                    java.awt.RenderingHints.VALUE_RENDER_QUALITY );
                g.drawImage( src, 0, 0, size, size, null );
                g.dispose();
                javax.imageio.ImageIO.write( scaled, "png",
                                             dir.resolve( "mica-minecraft-launcher.png" ).toFile() );
            }
            Logger.logDebug( "Linux launcher icon installed into the user hicolor theme." );
        }
        catch ( Exception | Error e ) {
            Logger.logWarningSilent( "Could not install Linux launcher icon: " + e.getMessage() );
        }
    }

    /** Registers the {@code application/x-mmcjson} MIME type with a {@code *.mmcjson} glob in the
     *  per-user shared-mime-info database, so double-clicking a modpack file resolves to the type
     *  the {@code .desktop} file declares it handles. Without this the runtime registration covers
     *  the URL scheme but not the file association on portable installs (the deb/rpm register the
     *  type at install time via jpackage's {@code --file-associations}). Idempotent. */
    private static void registerLinuxMimeType()
    {
        try {
            Path mimeBase = Paths.get( System.getProperty( "user.home" ), ".local", "share", "mime" );
            Path packagesDir = mimeBase.resolve( "packages" );
            Path mimeFile = packagesDir.resolve( "mica-minecraft-launcher.xml" );
            if ( Files.exists( mimeFile ) ) {
                return;
            }
            Files.createDirectories( packagesDir );

            String glob = "*" + ModPackConstants.MODPACK_FILE_EXTENSION;   // "*.mmcjson"
            String comment = xmlEscape( ModPackConstants.MODPACK_FILE_DESCRIPTION );
            String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                    + "<mime-info xmlns=\"http://www.freedesktop.org/standards/shared-mime-info\">\n"
                    + "  <mime-type type=\"application/x-mmcjson\">\n"
                    + "    <comment>" + comment + "</comment>\n"
                    + "    <glob pattern=\"" + glob + "\"/>\n"
                    + "  </mime-type>\n"
                    + "</mime-info>\n";
            Files.writeString( mimeFile, xml, StandardCharsets.UTF_8 );

            // Rebuild the user MIME cache so the new glob takes effect. No-op if the helper is absent.
            runQuietly( "update-mime-database", mimeBase.toString() );
            Logger.logDebug( "Linux .mmcjson MIME type registered via " + mimeFile );
        }
        catch ( Exception | Error e ) {
            Logger.logWarningSilent( "Could not register Linux .mmcjson MIME type: " + e.getMessage() );
        }
    }

    /** Minimal XML text escaping for the few characters that matter inside an element body. */
    private static String xmlEscape( String s )
    {
        if ( s == null ) {
            return "";
        }
        return s.replace( "&", "&amp;" ).replace( "<", "&lt;" ).replace( ">", "&gt;" );
    }

    /** Fire-and-forget invocation of an external command with a 2-second timeout. Used for
     *  the optional {@code update-desktop-database} refresh on Linux. */
    private static void runQuietly( String... command )
    {
        try {
            ProcessBuilder pb = new ProcessBuilder( command );
            pb.redirectErrorStream( true );
            Process p = pb.start();
            // Drain output so the child doesn't block on a full pipe.
            try ( var is = p.getInputStream() ) {
                is.transferTo( OutputStream.nullOutputStream() );
            }
            p.waitFor( 2, java.util.concurrent.TimeUnit.SECONDS );
        }
        catch ( Exception | Error ignored ) {
            // Command not present, denied, or timed out — non-fatal.
        }
    }
}
