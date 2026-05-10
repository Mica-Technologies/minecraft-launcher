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

        Logger.logDebug( "Windows scheme + file-association registered for " + exePath );
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
        String content = "[Desktop Entry]\n" +
                "Type=Application\n" +
                "Name=" + LauncherConstants.LAUNCHER_APPLICATION_NAME + "\n" +
                "Exec=\"" + exePath + "\" %u\n" +
                "Terminal=false\n" +
                "Icon=mica-minecraft-launcher\n" +
                "MimeType=x-scheme-handler/mmcl;application/x-mmcjson;\n" +
                "Categories=Game;\n" +
                "NoDisplay=false\n";
        Files.writeString( desktopFile, content, StandardCharsets.UTF_8 );

        // Best-effort cache refresh. Both commands are no-ops if not installed — we don't
        // want missing helpers to fail the whole registration.
        runQuietly( "update-desktop-database", desktopDir.toString() );

        Logger.logDebug( "Linux scheme + file-association registered via " + desktopFile );
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
