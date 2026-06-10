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

package com.micatechnologies.minecraft.launcher.system;

import com.micatechnologies.minecraft.launcher.LauncherCore;
import com.micatechnologies.minecraft.launcher.consts.LauncherConstants;
import com.micatechnologies.minecraft.launcher.files.LocalPathManager;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.game.modpack.GameModPack;
import org.apache.commons.lang3.SystemUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Manages creation of platform-specific desktop shortcuts for modpacks. Handles launcher path resolution, icon
 * conversion (PNG to ICO/ICNS), and shortcut file creation for Windows (.lnk), macOS (.app bundle), and Linux
 * (.desktop file).
 *
 * @author Mica Technologies
 * @version 1.0
 * @since 2.0
 */
public class DesktopShortcutManager
{
    /**
     * Creates a desktop shortcut for the specified modpack on the current platform. The shortcut launches the launcher
     * with the modpack pre-selected via CLI argument.
     *
     * @param pack the modpack to create a shortcut for
     *
     * @throws IOException if shortcut creation fails
     * @since 1.0
     */
    public static void createShortcut( GameModPack pack ) throws IOException
    {
        String packName = pack.getPackName();
        String shortcutName = sanitizeFileName( packName );
        File desktopDir = getDesktopDirectory();
        if ( desktopDir == null || !desktopDir.isDirectory() ) {
            throw new IOException( "Unable to locate the desktop directory." );
        }

        // Resolve the launcher executable path and build the launch command
        String launcherPath = resolveLauncherPath();

        // Convert pack logo to platform icon format
        String iconPath = convertIcon( pack );

        if ( SystemUtils.IS_OS_WINDOWS ) {
            createWindowsShortcut( desktopDir, shortcutName, launcherPath, packName, iconPath );
        }
        else if ( SystemUtils.IS_OS_MAC ) {
            createMacOsShortcut( desktopDir, shortcutName, launcherPath, packName, iconPath );
        }
        else {
            createLinuxShortcut( desktopDir, shortcutName, launcherPath, packName, iconPath );
        }

        Logger.logStd( "Desktop shortcut created for: " + packName );
    }

    // ---- Path Resolution ----

    /**
     * Resolves the path to the launcher executable. For native installations (jpackage/javapackager), this returns the
     * native executable path. For JAR-based launches, this returns the path to the JAR file.
     *
     * @return the launcher executable or JAR path
     *
     * @since 1.0
     */
    private static String resolveLauncherPath()
    {
        // Check for jpackage app path (set by jpackage-based launchers)
        String jpackagePath = System.getProperty( "jpackage.app-path" );
        if ( jpackagePath != null && !jpackagePath.isEmpty() ) {
            return jpackagePath;
        }

        // Check ProcessHandle for native executable
        String processCommand = ProcessHandle.current().info().command().orElse( null );
        if ( processCommand != null ) {
            // If it's a native executable (not java/javaw), use it directly
            String lowerCmd = processCommand.toLowerCase();
            if ( !lowerCmd.endsWith( "java" ) && !lowerCmd.endsWith( "java.exe" ) &&
                 !lowerCmd.endsWith( "javaw" ) && !lowerCmd.endsWith( "javaw.exe" ) ) {
                return processCommand;
            }
        }

        // Check for a native launcher executable next to the running JAR.
        // fvarrui javapackager layout:  InstallDir/AppName.exe + InstallDir/launcher.jar + InstallDir/jre/
        // JDK jpackage layout:          InstallDir/AppName.exe + InstallDir/app/launcher.jar + InstallDir/runtime/
        try {
            File jarFile = new File(
                    LauncherCore.class.getProtectionDomain().getCodeSource().getLocation().toURI() );
            if ( jarFile.isFile() ) {
                File jarDir = jarFile.getParentFile();

                // fvarrui layout: JAR is at install root alongside the .exe
                if ( jarDir != null ) {
                    String nativeExe = findNativeExecutable( jarDir );
                    if ( nativeExe != null ) {
                        return nativeExe;
                    }
                }

                // JDK jpackage layout: JAR is in app/ subfolder, .exe is one level up
                if ( jarDir != null && jarDir.getName().equalsIgnoreCase( "app" ) ) {
                    File installDir = jarDir.getParentFile();
                    if ( installDir != null ) {
                        String nativeExe = findNativeExecutable( installDir );
                        if ( nativeExe != null ) {
                            return nativeExe;
                        }
                    }
                }

                // No native exe found — return the JAR path for java -jar invocation
                if ( jarFile.getName().endsWith( ".jar" ) ) {
                    return jarFile.getAbsolutePath();
                }
            }
        }
        catch ( Exception e ) {
            Logger.logWarningSilent( "Unable to resolve launcher path: " + e.getMessage() );
        }

        // Last resort: use the process command (java binary)
        return processCommand != null ? processCommand : "java";
    }

    /**
     * Searches an install directory for a native launcher executable. On Windows, looks for .exe files. On macOS, looks
     * for executables in Contents/MacOS/. On Linux, looks for executable files in bin/.
     *
     * @param installDir the installation root directory
     *
     * @return the path to the native executable, or {@code null} if not found
     *
     * @since 1.0
     */
    private static String findNativeExecutable( File installDir )
    {
        if ( SystemUtils.IS_OS_WINDOWS ) {
            // Look for .exe files that are NOT java/javaw (those belong to the bundled JRE)
            File[] exeFiles = installDir.listFiles( ( dir, name ) -> {
                String lower = name.toLowerCase();
                return lower.endsWith( ".exe" ) && !lower.equals( "java.exe" ) && !lower.equals( "javaw.exe" );
            } );
            if ( exeFiles != null && exeFiles.length > 0 ) {
                return exeFiles[ 0 ].getAbsolutePath();
            }
        }
        else if ( SystemUtils.IS_OS_MAC ) {
            File macosDir = new File( installDir, "Contents" + File.separator + "MacOS" );
            if ( macosDir.isDirectory() ) {
                File[] files = macosDir.listFiles( f -> f.isFile() && f.canExecute() );
                if ( files != null && files.length > 0 ) {
                    return files[ 0 ].getAbsolutePath();
                }
            }
        }
        else {
            // Linux: look for executable files directly in installDir (fvarrui) or bin/ (jpackage)
            File[] files = installDir.listFiles( f -> {
                String name = f.getName().toLowerCase();
                return f.isFile() && f.canExecute() && !name.equals( "java" ) && !name.endsWith( ".jar" );
            } );
            if ( files != null && files.length > 0 ) {
                return files[ 0 ].getAbsolutePath();
            }
            File binDir = new File( installDir, "bin" );
            if ( binDir.isDirectory() ) {
                File[] binFiles = binDir.listFiles( f -> f.isFile() && f.canExecute() );
                if ( binFiles != null && binFiles.length > 0 ) {
                    return binFiles[ 0 ].getAbsolutePath();
                }
            }
        }
        return null;
    }

    /**
     * Determines whether the launcher is running as a native executable (as opposed to via {@code java -jar}).
     *
     * @param launcherPath the resolved launcher path
     *
     * @return {@code true} if the launcher is a native executable
     *
     * @since 1.0
     */
    private static boolean isNativeExecutable( String launcherPath )
    {
        String lower = launcherPath.toLowerCase();
        return !lower.endsWith( ".jar" ) && !lower.endsWith( "java" ) && !lower.endsWith( "java.exe" ) &&
               !lower.endsWith( "javaw" ) && !lower.endsWith( "javaw.exe" );
    }

    /**
     * Builds the command-line arguments for launching the specified modpack. For native executables, this is just the
     * client mode flag and pack name. For JAR-based launches, this includes {@code -jar <path>}.
     *
     * @param launcherPath the resolved launcher path
     * @param packName     the modpack name to pre-select
     *
     * @return the argument string
     *
     * @since 1.0
     */
    private static String buildArguments( String launcherPath, String packName )
    {
        String quotedPackName = packName.contains( " " ) ? "\"" + packName + "\"" : packName;
        if ( isNativeExecutable( launcherPath ) ) {
            return LauncherConstants.PROGRAM_ARG_CLIENT_MODE + " " + quotedPackName;
        }
        else if ( launcherPath.toLowerCase().endsWith( ".jar" ) ) {
            return "-jar \"" + launcherPath + "\" " + LauncherConstants.PROGRAM_ARG_CLIENT_MODE + " " + quotedPackName;
        }
        else {
            // java binary -- need to find the JAR
            try {
                File jarFile = new File(
                        LauncherCore.class.getProtectionDomain().getCodeSource().getLocation().toURI() );
                return "-jar \"" + jarFile.getAbsolutePath() + "\" " + LauncherConstants.PROGRAM_ARG_CLIENT_MODE +
                       " " + quotedPackName;
            }
            catch ( Exception e ) {
                return LauncherConstants.PROGRAM_ARG_CLIENT_MODE + " " + quotedPackName;
            }
        }
    }

    // ---- Icon Conversion ----

    /**
     * Converts the modpack's cached PNG logo to the platform-appropriate icon format and returns the path to the
     * converted icon file.
     *
     * @param pack the modpack whose logo to convert
     *
     * @return the path to the platform icon file, or {@code null} if conversion failed
     *
     * @since 1.0
     */
    private static String convertIcon( GameModPack pack )
    {
        String pngPath = pack.getPackLogoFilepath();
        if ( pngPath == null || !new File( pngPath ).exists() ) {
            return null;
        }

        try {
            String iconDir = LocalPathManager.getLauncherMetadataFolderPath();
            String baseName = "icon_" + pack.getPackSanitizedName();

            if ( SystemUtils.IS_OS_WINDOWS ) {
                String icoPath = iconDir + File.separator + baseName + ".ico";
                convertPngToIco( pngPath, icoPath );
                return icoPath;
            }
            else if ( SystemUtils.IS_OS_MAC ) {
                String icnsPath = iconDir + File.separator + baseName + ".icns";
                if ( convertPngToIcns( pngPath, icnsPath ) ) {
                    return icnsPath;
                }
                return pngPath; // Fall back to PNG
            }
            else {
                return pngPath; // Linux uses PNG directly
            }
        }
        catch ( Exception e ) {
            Logger.logWarningSilent( "Icon conversion failed, shortcut will use default icon: " + e.getMessage() );
            return null;
        }
    }

    /**
     * Converts a PNG image to ICO format. Modern ICO files support embedded PNG data directly, so this writes an ICO
     * container with PNG payloads at multiple sizes (256, 48, 32, 16).
     *
     * @param pngPath the source PNG file path
     * @param icoPath the destination ICO file path
     *
     * @throws IOException if reading or writing fails
     * @since 1.0
     */
    private static void convertPngToIco( String pngPath, String icoPath ) throws IOException
    {
        BufferedImage original = ImageIO.read( new File( pngPath ) );
        if ( original == null ) {
            throw new IOException( "Unable to read PNG image: " + pngPath );
        }

        int[] sizes = { 256, 48, 32, 16 };
        byte[][] pngDataArray = new byte[ sizes.length ][];

        for ( int i = 0; i < sizes.length; i++ ) {
            BufferedImage scaled = scaleImage( original, sizes[ i ], sizes[ i ] );
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write( scaled, "png", baos );
            pngDataArray[ i ] = baos.toByteArray();
        }

        // Write ICO file: ICONDIR + ICONDIRENTRY[] + PNG data
        try ( DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream( new FileOutputStream( icoPath ) ) ) ) {
            // ICONDIR header
            out.writeShort( 0 );                           // Reserved
            out.writeShort( swapShort( (short) 1 ) );      // Type: 1 = ICO
            out.writeShort( swapShort( (short) sizes.length ) ); // Count

            // Calculate data offset: header(6) + entries(16 * count)
            int dataOffset = 6 + ( 16 * sizes.length );

            // ICONDIRENTRY for each size
            for ( int i = 0; i < sizes.length; i++ ) {
                int w = sizes[ i ] == 256 ? 0 : sizes[ i ]; // 0 means 256
                int h = sizes[ i ] == 256 ? 0 : sizes[ i ];
                out.writeByte( w );                          // Width
                out.writeByte( h );                          // Height
                out.writeByte( 0 );                          // Color palette count
                out.writeByte( 0 );                          // Reserved
                out.writeShort( swapShort( (short) 1 ) );   // Color planes
                out.writeShort( swapShort( (short) 32 ) );  // Bits per pixel
                out.writeInt( swapInt( pngDataArray[ i ].length ) ); // Data size
                out.writeInt( swapInt( dataOffset ) );       // Data offset
                dataOffset += pngDataArray[ i ].length;
            }

            // PNG data
            for ( byte[] pngData : pngDataArray ) {
                out.write( pngData );
            }
        }
    }

    /**
     * Converts a PNG image to ICNS format using the macOS {@code iconutil} command-line tool. Creates a temporary
     * {@code .iconset} directory with scaled PNG files and invokes {@code iconutil --convert icns}.
     *
     * @param pngPath  the source PNG file path
     * @param icnsPath the destination ICNS file path
     *
     * @return {@code true} if conversion succeeded, {@code false} if {@code iconutil} is unavailable or failed
     *
     * @since 1.0
     */
    private static boolean convertPngToIcns( String pngPath, String icnsPath )
    {
        try {
            BufferedImage original = ImageIO.read( new File( pngPath ) );
            if ( original == null ) {
                return false;
            }

            // Create temporary .iconset directory
            Path iconsetDir = Files.createTempDirectory( "modpack_icon" );
            File iconsetFile = new File( iconsetDir.toFile(), "icon.iconset" );
            iconsetFile.mkdirs();

            // Write required sizes for iconutil
            int[][] iconSizes = {
                    { 16, 1 }, { 16, 2 }, { 32, 1 }, { 32, 2 }, { 128, 1 }, { 128, 2 }, { 256, 1 }, { 256, 2 },
                    { 512, 1 }, { 512, 2 }
            };
            for ( int[] spec : iconSizes ) {
                int size = spec[ 0 ];
                int scale = spec[ 1 ];
                int pixels = size * scale;
                String suffix = scale == 2 ? "@2x" : "";
                String filename = "icon_" + size + "x" + size + suffix + ".png";
                BufferedImage scaled = scaleImage( original, pixels, pixels );
                ImageIO.write( scaled, "png", new File( iconsetFile, filename ) );
            }

            // Run iconutil
            ProcessBuilder pb = new ProcessBuilder( "iconutil", "--convert", "icns",
                                                     "--output", icnsPath,
                                                     iconsetFile.getAbsolutePath() );
            pb.redirectErrorStream( true );
            Process process = pb.start();
            int exitCode = process.waitFor();

            // Clean up temp directory
            deleteRecursively( iconsetDir.toFile() );

            return exitCode == 0;
        }
        catch ( Exception e ) {
            Logger.logWarningSilent( "iconutil conversion failed: " + e.getMessage() );
            return false;
        }
    }

    // ---- Shortcut Creation ----

    /**
     * Creates a Windows .lnk shortcut using PowerShell's WScript.Shell COM object.
     *
     * @param desktopDir   the desktop directory
     * @param shortcutName the shortcut file name (without extension)
     * @param launcherPath the launcher executable path
     * @param packName     the modpack name
     * @param iconPath     the icon file path (ICO format), or {@code null}
     *
     * @throws IOException if shortcut creation fails
     * @since 1.0
     */
    private static void createWindowsShortcut( File desktopDir, String shortcutName, String launcherPath,
                                                String packName, String iconPath ) throws IOException
    {
        File lnkFile = new File( desktopDir, shortcutName + ".lnk" );
        String targetPath;
        String arguments;

        // packName is server-supplied JSON. Windows .lnk Arguments are parsed by
        // CommandLineToArgvW when the shortcut is invoked, which honors backslash-
        // escaped quotes (\"). Without escaping, a packName like
        // `pack" --launcher-flag "` would split into multiple args. Strip newlines
        // (a stray \n could break the PowerShell here-arg or the shortcut metadata)
        // and escape backslashes + double quotes per Windows command-line rules.
        String safePackArg = windowsCmdQuote( stripWindowsLineTerminators( packName ) );

        if ( isNativeExecutable( launcherPath ) ) {
            targetPath = launcherPath;
            arguments = LauncherConstants.PROGRAM_ARG_CLIENT_MODE + " " + safePackArg;
        }
        else {
            // JAR-based: target is javaw.exe, arguments include -jar
            String javaHome = System.getProperty( "java.home" );
            targetPath = javaHome + "\\bin\\javaw.exe";
            try {
                File jarFile = new File(
                        LauncherCore.class.getProtectionDomain().getCodeSource().getLocation().toURI() );
                arguments = "-jar " + windowsCmdQuote( jarFile.getAbsolutePath() ) + " "
                            + LauncherConstants.PROGRAM_ARG_CLIENT_MODE + " " + safePackArg;
            }
            catch ( Exception e ) {
                throw new IOException( "Unable to resolve JAR path for shortcut.", e );
            }
        }

        // Build PowerShell script. Single-quoted strings in PowerShell are literal -- they preserve
        // embedded double quotes exactly as-is, which is what we need for .lnk Arguments.
        StringBuilder ps = new StringBuilder();
        ps.append( "$ws = New-Object -ComObject WScript.Shell; " );
        ps.append( "$s = $ws.CreateShortcut('" ).append( lnkFile.getAbsolutePath().replace( "'", "''" ) )
          .append( "'); " );
        ps.append( "$s.TargetPath = '" ).append( targetPath.replace( "'", "''" ) ).append( "'; " );
        ps.append( "$s.Arguments = '" ).append( arguments.replace( "'", "''" ) ).append( "'; " );
        if ( iconPath != null && new File( iconPath ).exists() ) {
            ps.append( "$s.IconLocation = '" ).append( iconPath.replace( "'", "''" ) ).append( ",0'; " );
        }
        ps.append( "$s.Save()" );

        ProcessBuilder pb = new ProcessBuilder( "powershell", "-NoProfile", "-NonInteractive",
                                                 "-Command", ps.toString() );
        pb.redirectErrorStream( true );
        Process process = pb.start();
        try {
            int exitCode = process.waitFor();
            if ( exitCode != 0 ) {
                String output = new String( process.getInputStream().readAllBytes(), StandardCharsets.UTF_8 );
                throw new IOException( "PowerShell shortcut creation failed (exit " + exitCode + "): " + output );
            }
        }
        catch ( InterruptedException e ) {
            Thread.currentThread().interrupt();
            throw new IOException( "Shortcut creation was interrupted.", e );
        }
    }

    /**
     * Creates a macOS .app bundle on the desktop. The bundle contains a shell script that launches the launcher with
     * the modpack pre-selected and an ICNS icon.
     *
     * @param desktopDir   the desktop directory
     * @param shortcutName the shortcut name (used for the .app bundle name)
     * @param launcherPath the launcher executable path
     * @param packName     the modpack name
     * @param iconPath     the icon file path (ICNS or PNG), or {@code null}
     *
     * @throws IOException if shortcut creation fails
     * @since 1.0
     */
    private static void createMacOsShortcut( File desktopDir, String shortcutName, String launcherPath,
                                              String packName, String iconPath ) throws IOException
    {
        File appBundle = new File( desktopDir, shortcutName + ".app" );
        File contentsDir = new File( appBundle, "Contents" );
        File macosDir = new File( contentsDir, "MacOS" );
        File resourcesDir = new File( contentsDir, "Resources" );
        macosDir.mkdirs();
        resourcesDir.mkdirs();

        // Copy icon to Resources if available
        String iconFileName = "icon.icns";
        if ( iconPath != null && new File( iconPath ).exists() ) {
            File destIcon = new File( resourcesDir, iconFileName );
            Files.copy( new File( iconPath ).toPath(), destIcon.toPath(),
                         java.nio.file.StandardCopyOption.REPLACE_EXISTING );
        }

        // Create Info.plist
        String escapedName = shortcutName.replace( "&", "&amp;" ).replace( "<", "&lt;" );
        String plist = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                       "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" " +
                       "\"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n" +
                       "<plist version=\"1.0\">\n" +
                       "<dict>\n" +
                       "    <key>CFBundleExecutable</key>\n" +
                       "    <string>launch.sh</string>\n" +
                       "    <key>CFBundleIconFile</key>\n" +
                       "    <string>" + iconFileName + "</string>\n" +
                       "    <key>CFBundleName</key>\n" +
                       "    <string>" + escapedName + "</string>\n" +
                       "    <key>CFBundlePackageType</key>\n" +
                       "    <string>APPL</string>\n" +
                       "    <key>CFBundleVersion</key>\n" +
                       "    <string>" + LauncherConstants.LAUNCHER_APPLICATION_VERSION + "</string>\n" +
                       "</dict>\n" +
                       "</plist>\n";
        Files.writeString( new File( contentsDir, "Info.plist" ).toPath(), plist, StandardCharsets.UTF_8 );

        // Create launch script. The pack name is server-supplied JSON and is
        // inserted into bash. Double-quoted strings still expand $, backticks,
        // and `\` sequences, so packName.replace("\"", "\\\"") on its own would
        // let a manifest with `"; rm -rf $HOME; #"` in the pack name execute
        // arbitrary commands when the shortcut was invoked. Single-quoting with
        // POSIX-style ' -> '\'' is robust to every shell metacharacter (CR/LF
        // inside '...' is preserved as literal data, not a command separator).
        // What single-quoting can't protect against is a NUL byte: most C-based
        // exec paths treat NUL as end-of-string and will truncate the argv slot
        // mid-name. Strip CR/LF/NUL up front — same defense the Linux .desktop
        // branch applies at the Name=/Exec= level (line 603 below).
        String safePackName = shellSingleQuote( stripLineTerminators( packName ) );
        String script;
        if ( isNativeExecutable( launcherPath ) ) {
            script = "#!/bin/bash\nexec " + shellSingleQuote( launcherPath ) + " "
                     + LauncherConstants.PROGRAM_ARG_CLIENT_MODE + " " + safePackName + "\n";
        }
        else {
            String javaHome = System.getProperty( "java.home" );
            String jarPath;
            try {
                jarPath = new File(
                        LauncherCore.class.getProtectionDomain().getCodeSource().getLocation().toURI() )
                        .getAbsolutePath();
            }
            catch ( Exception e ) {
                jarPath = launcherPath;
            }
            script = "#!/bin/bash\nexec " + shellSingleQuote( javaHome + "/bin/java" )
                     + " -jar " + shellSingleQuote( jarPath ) + " "
                     + LauncherConstants.PROGRAM_ARG_CLIENT_MODE + " " + safePackName + "\n";
        }

        File launchScript = new File( macosDir, "launch.sh" );
        Files.writeString( launchScript.toPath(), script, StandardCharsets.UTF_8 );
        launchScript.setExecutable( true, false );
    }

    /**
     * Creates a Linux .desktop file on the desktop.
     *
     * @param desktopDir   the desktop directory
     * @param shortcutName the shortcut name (used for the .desktop file name)
     * @param launcherPath the launcher executable path
     * @param packName     the modpack name
     * @param iconPath     the icon file path (PNG), or {@code null}
     *
     * @throws IOException if shortcut creation fails
     * @since 1.0
     */
    private static void createLinuxShortcut( File desktopDir, String shortcutName, String launcherPath,
                                              String packName, String iconPath ) throws IOException
    {
        File desktopFile = new File( desktopDir, shortcutName + ".desktop" );

        // packName is server-supplied JSON. The .desktop file format is line-based
        // (newlines split records) and the Exec= field is interpreted by the desktop
        // environment as a shell-style command line — so embedded newlines, double
        // quotes, dollar signs, and backticks all need escaping before reaching disk.
        // Strip line-terminators first (any embedded \n in Name= breaks the parser
        // entirely), then escape according to the desktop-entry spec for Exec.
        String safePackName = stripLineTerminators( packName );

        String execLine;
        if ( isNativeExecutable( launcherPath ) ) {
            execLine = desktopExecQuote( launcherPath ) + " "
                       + LauncherConstants.PROGRAM_ARG_CLIENT_MODE + " "
                       + desktopExecQuote( safePackName );
        }
        else {
            String javaHome = System.getProperty( "java.home" );
            String jarPath;
            try {
                jarPath = new File(
                        LauncherCore.class.getProtectionDomain().getCodeSource().getLocation().toURI() )
                        .getAbsolutePath();
            }
            catch ( Exception e ) {
                jarPath = launcherPath;
            }
            execLine = desktopExecQuote( javaHome + "/bin/java" ) + " -jar "
                       + desktopExecQuote( jarPath ) + " "
                       + LauncherConstants.PROGRAM_ARG_CLIENT_MODE + " "
                       + desktopExecQuote( safePackName );
        }

        StringBuilder desktop = new StringBuilder();
        desktop.append( "[Desktop Entry]\n" );
        desktop.append( "Type=Application\n" );
        desktop.append( "Name=" ).append( safePackName ).append( "\n" );
        desktop.append( "Exec=" ).append( execLine ).append( "\n" );
        if ( iconPath != null && new File( iconPath ).exists() ) {
            desktop.append( "Icon=" ).append( stripLineTerminators( iconPath ) ).append( "\n" );
        }
        desktop.append( "Terminal=false\n" );
        desktop.append( "Categories=Game;\n" );

        Files.writeString( desktopFile.toPath(), desktop.toString(), StandardCharsets.UTF_8 );
        desktopFile.setExecutable( true, false );

        // Some desktop environments require gio to trust the .desktop file. Bound the wait so a
        // hung/blocked gio (e.g. a stuck D-Bus call) can't stall shortcut creation indefinitely.
        try {
            Process gio = new ProcessBuilder( "gio", "set", desktopFile.getAbsolutePath(),
                                              "metadata::trusted", "true" )
                    .redirectErrorStream( true ).start();
            if ( !gio.waitFor( 2, java.util.concurrent.TimeUnit.SECONDS ) ) {
                gio.destroyForcibly();
            }
        }
        catch ( Exception ignored ) {
            // gio may not be available on all systems
        }
    }

    // ---- Utility Methods ----

    /**
     * Returns the user's desktop directory.
     *
     * @return the desktop directory, or {@code null} if it cannot be determined
     *
     * @since 1.0
     */
    private static File getDesktopDirectory()
    {
        // Try XDG user dir on Linux
        if ( SystemUtils.IS_OS_LINUX ) {
            try {
                ProcessBuilder pb = new ProcessBuilder( "xdg-user-dir", "DESKTOP" );
                pb.redirectErrorStream( true );
                Process p = pb.start();
                String result = new String( p.getInputStream().readAllBytes(), StandardCharsets.UTF_8 ).trim();
                p.waitFor();
                if ( !result.isEmpty() ) {
                    File dir = new File( result );
                    if ( dir.isDirectory() ) {
                        return dir;
                    }
                }
            }
            catch ( Exception ignored ) {
            }
        }

        // Standard fallback for all platforms
        File desktop = new File( System.getProperty( "user.home" ), "Desktop" );
        if ( desktop.isDirectory() ) {
            return desktop;
        }

        return null;
    }

    /**
     * Scales a {@link BufferedImage} to the specified dimensions using smooth scaling.
     *
     * @param original the source image
     * @param width    the target width
     * @param height   the target height
     *
     * @return the scaled image
     *
     * @since 1.0
     */
    private static BufferedImage scaleImage( BufferedImage original, int width, int height )
    {
        java.awt.Image scaled = original.getScaledInstance( width, height, java.awt.Image.SCALE_SMOOTH );
        BufferedImage result = new BufferedImage( width, height, BufferedImage.TYPE_INT_ARGB );
        java.awt.Graphics2D g2d = result.createGraphics();
        g2d.drawImage( scaled, 0, 0, null );
        g2d.dispose();
        return result;
    }

    /**
     * Sanitizes a file name by removing characters that are invalid in file names across platforms.
     *
     * @param name the raw name
     *
     * @return the sanitized name
     *
     * @since 1.0
     */
    private static String sanitizeFileName( String name )
    {
        return name.replaceAll( "[<>:\"/\\\\|?*]", "_" ).trim();
    }

    /**
     * Recursively deletes a file or directory.
     *
     * @param file the file or directory to delete
     *
     * @since 1.0
     */
    private static void deleteRecursively( File file )
    {
        if ( file.isDirectory() ) {
            File[] children = file.listFiles();
            if ( children != null ) {
                for ( File child : children ) {
                    deleteRecursively( child );
                }
            }
        }
        file.delete();
    }

    /**
     * Swaps endianness of a short value (Java is big-endian, ICO is little-endian).
     */
    private static short swapShort( short value )
    {
        return (short) ( ( ( value & 0xFF ) << 8 ) | ( ( value >> 8 ) & 0xFF ) );
    }

    /**
     * Swaps endianness of an int value (Java is big-endian, ICO is little-endian).
     */
    private static int swapInt( int value )
    {
        return Integer.reverseBytes( value );
    }

    /**
     * Wraps a string in POSIX single quotes, with embedded single quotes escaped
     * as {@code '\''}. Single-quoted shell strings suppress every form of
     * expansion (variables, command substitution, glob, backslash), so this is
     * the safe form for embedding untrusted data — server-supplied modpack names
     * in this codebase — into a generated bash script.
     */
    private static String shellSingleQuote( String input )
    {
        if ( input == null ) {
            return "''";
        }
        return "'" + input.replace( "'", "'\\''" ) + "'";
    }

    /**
     * Quotes a value for inclusion in a freedesktop {@code Exec=} line. Per the
     * Desktop Entry Specification: the argument is wrapped in double quotes, and
     * the special characters {@code "}, {@code `}, {@code $}, and {@code \} are
     * each escaped with a leading backslash. This is what stops a pack name
     * containing {@code $(...)} or backticks from getting expanded by the launching
     * shell.
     */
    private static String desktopExecQuote( String input )
    {
        if ( input == null ) {
            return "\"\"";
        }
        StringBuilder sb = new StringBuilder( input.length() + 2 );
        sb.append( '"' );
        for ( int i = 0; i < input.length(); i++ ) {
            char c = input.charAt( i );
            if ( c == '"' || c == '`' || c == '$' || c == '\\' ) {
                sb.append( '\\' );
            }
            sb.append( c );
        }
        sb.append( '"' );
        return sb.toString();
    }

    /**
     * Quotes a single command-line argument for Windows. Wraps in double quotes,
     * doubles internal backslashes that immediately precede a closing quote, and
     * escapes embedded {@code "} as {@code \"}. This matches what
     * {@code CommandLineToArgvW} (used by the Windows runtime for most exes)
     * expects. Robust to packNames containing spaces, double quotes, or
     * trailing backslashes.
     */
    private static String windowsCmdQuote( String input )
    {
        if ( input == null ) {
            return "\"\"";
        }
        StringBuilder sb = new StringBuilder( input.length() + 2 );
        sb.append( '"' );
        int trailingBackslashes = 0;
        for ( int i = 0; i < input.length(); i++ ) {
            char c = input.charAt( i );
            if ( c == '\\' ) {
                trailingBackslashes++;
                sb.append( c );
            }
            else if ( c == '"' ) {
                // Double every trailing backslash before the quote, then escape the quote.
                for ( int j = 0; j < trailingBackslashes; j++ ) {
                    sb.append( '\\' );
                }
                trailingBackslashes = 0;
                sb.append( '\\' ).append( '"' );
            }
            else {
                trailingBackslashes = 0;
                sb.append( c );
            }
        }
        // If we end on a run of backslashes, double them so the closing quote isn't
        // misread as an escape.
        for ( int j = 0; j < trailingBackslashes; j++ ) {
            sb.append( '\\' );
        }
        sb.append( '"' );
        return sb.toString();
    }

    /** Strips CR/LF/NUL — same idea as the .desktop helper but applies to any
     *  value that has to survive the PowerShell single-line invocation that
     *  creates the .lnk shortcut. */
    private static String stripWindowsLineTerminators( String input )
    {
        return stripLineTerminators( input );
    }

    /**
     * Removes CR / LF / NUL from a string. .desktop files are line-based and an
     * embedded newline would split a single field into two — turning a malicious
     * pack name into a forged {@code Exec=} override below the user-visible
     * {@code Name=} field. Applied to every user-supplied value that lands in a
     * .desktop record.
     */
    private static String stripLineTerminators( String input )
    {
        if ( input == null ) {
            return "";
        }
        StringBuilder sb = new StringBuilder( input.length() );
        for ( int i = 0; i < input.length(); i++ ) {
            char c = input.charAt( i );
            if ( c == '\r' || c == '\n' || c == '\0' ) {
                continue;
            }
            sb.append( c );
        }
        return sb.toString();
    }
}
