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

import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.gui.GUIUtilities;
import com.micatechnologies.minecraft.launcher.utilities.NotificationManager;
import com.micatechnologies.minecraft.launcher.utilities.StartupDiagnostics;
import com.micatechnologies.minecraft.launcher.utilities.SystemUtilities;
import com.sun.jna.Memory;
import com.sun.jna.platform.win32.Advapi32;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.W32Errors;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.platform.win32.WinReg;
import com.sun.jna.ptr.IntByReference;
import javafx.stage.Stage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Catches a Windows "delete at next reboot" that would wipe the launcher's own install, while the
 * launcher can still say so.
 *
 * <p>When an MSI upgrade runs while the launcher is open, the old product's uninstall cannot delete
 * the files the running JVM has memory-mapped ({@code runtime\lib\modules} above all). Windows
 * Installer queues a delete for each in {@code PendingFileRenameOperations}. That entry is keyed by
 * <em>path</em>, so after the new product writes its own copy at the same path, the next boot
 * deletes the new file. The bundled JVM then cannot start, and the launcher exits before any of its
 * own code runs: no window, no log, nothing. That happened on 2026-10-13/14.
 *
 * <p>The installer now refuses to upgrade a running launcher, but this check also covers installs
 * made by older installers and anything else that queues such a delete. At startup it reads the
 * pending operations, replays them in order, and works out which files the install needs that
 * would be gone after the next boot. If any would, the user is offered a fix that removes exactly
 * those delete entries (elevated, since the value lives under {@code HKLM}), after backing up the
 * original value.
 *
 * <p>Windows-only, and only for a packaged install ({@code jpackage.app-path} set). Everything
 * here is best-effort: a failed check is logged and never stops the launcher.
 *
 * @since 2026.10
 */
public final class PendingRebootDeleteGuard
{
    /** Registry key holding the pending operations. */
    static final String SESSION_MANAGER_KEY = "SYSTEM\\CurrentControlSet\\Control\\Session Manager";

    /** The values Windows reads at boot, in the order it processes them. */
    static final List< String > PENDING_VALUES = List.of( "PendingFileRenameOperations",
                                                          "PendingFileRenameOperations2" );

    /** Prefix Windows puts on NT object paths in these values. */
    private static final String NT_PREFIX = "\\??\\";

    /** Only check once per process, however many times the main screen is shown. */
    private static final AtomicBoolean CHECKED = new AtomicBoolean( false );

    private PendingRebootDeleteGuard() { /* static-only */ }

    /**
     * One queued boot-time operation: a delete when {@code target} is empty, otherwise a move of
     * {@code source} onto {@code target}.
     *
     * @param source the file the operation acts on, with the NT prefix removed
     * @param target the destination, empty for a delete
     */
    record PendingOperation( String source, String target )
    {
        boolean isDelete()
        {
            return target.isEmpty();
        }
    }

    /**
     * Runs the check once per process, off the FX thread, and prompts on {@code owner} if the
     * install is at risk. Does nothing outside a packaged Windows install.
     *
     * @param owner the stage that owns the prompt
     */
    public static void checkOnceAsync( Stage owner )
    {
        if ( !org.apache.commons.lang3.SystemUtils.IS_OS_WINDOWS || !CHECKED.compareAndSet( false, true ) ) {
            return;
        }
        Path installDir = installDir();
        if ( installDir == null ) {
            return;
        }
        SystemUtilities.spawnNewTask( () -> {
            try {
                List< Path > threatened = findThreatenedFiles( installDir );
                if ( threatened.isEmpty() ) {
                    return;
                }
                StartupDiagnostics.record( "Pending reboot operations will delete " + threatened.size()
                                           + " install file(s): " + threatened );
                Logger.logWarningSilent( LocalizationManager.format( "log.installGuard.threatened",
                                                                     threatened.size(), threatened ) );
                promptAndFix( installDir, threatened, owner );
            }
            catch ( Exception e ) {
                Logger.logError( LocalizationManager.get( "log.installGuard.checkFailed" ) );
                Logger.logThrowable( e );
            }
        } );
    }

    /**
     * The install folder, from the path of the jpackage launcher exe; {@code null} when not running
     * from a packaged install (an IDE or {@code java -jar} run).
     */
    static Path installDir()
    {
        String exe = System.getProperty( "jpackage.app-path" );
        if ( exe == null || exe.isBlank() ) {
            return null;
        }
        Path parent = Path.of( exe ).toAbsolutePath().getParent();
        return parent != null && Files.isDirectory( parent.resolve( "runtime" ) ) ? parent : null;
    }

    /**
     * Reads the pending operations and returns the install files they would delete.
     *
     * @param installDir the install folder
     *
     * @return the threatened files, empty if none
     */
    static List< Path > findThreatenedFiles( Path installDir )
    {
        List< PendingOperation > operations = new ArrayList<>();
        for ( String value : PENDING_VALUES ) {
            String raw = readRawMultiString( value );
            if ( raw != null ) {
                operations.addAll( parse( decodeMultiString( raw ) ) );
            }
        }
        if ( operations.isEmpty() ) {
            return List.of();
        }
        Set< String > classpathJars = classpathJars( installDir.resolve( "app" ) );
        return threatenedFiles( operations, installDir, Files::isRegularFile,
                                file -> isNeeded( installDir, file, classpathJars ) );
    }

    /**
     * Splits a raw {@code REG_MULTI_SZ} string into its entries. Unlike the usual decoders this
     * keeps empty entries, which is what a delete's target is; dropping them would pair every
     * later source with the wrong target. Trailing terminators are trimmed, then an odd count
     * gets its missing final empty target back.
     *
     * @param raw the value's characters, terminators included
     *
     * @return the entries, always an even number
     */
    static List< String > decodeMultiString( String raw )
    {
        int end = raw.length();
        while ( end > 0 && raw.charAt( end - 1 ) == '\0' ) {
            end--;
        }
        if ( end == 0 ) {
            return List.of();
        }
        List< String > entries = new ArrayList<>( List.of( raw.substring( 0, end ).split( "\0", -1 ) ) );
        if ( entries.size() % 2 != 0 ) {
            entries.add( "" );
        }
        return entries;
    }

    /**
     * Pairs decoded entries into operations, stripping the NT prefix and the {@code !} that marks a
     * replace-existing move.
     *
     * @param entries the decoded entries
     *
     * @return the operations, in order
     */
    static List< PendingOperation > parse( List< String > entries )
    {
        List< PendingOperation > operations = new ArrayList<>();
        for ( int i = 0; i + 1 < entries.size(); i += 2 ) {
            String source = stripPrefix( entries.get( i ) );
            if ( source.isEmpty() ) {
                continue;
            }
            operations.add( new PendingOperation( source, stripPrefix( entries.get( i + 1 ) ) ) );
        }
        return operations;
    }

    /**
     * Strips the markers in front of a path: {@code !} (replace existing) and, on current Windows
     * 11 builds, a {@code *N} flag such as {@code *1\??\C:\...}, then the NT prefix. Missing the
     * {@code *N} form would make every entry look like it is outside the install.
     */
    static String stripPrefix( String path )
    {
        String stripped = LEADING_MARKERS.matcher( path ).replaceFirst( "" );
        return stripped.startsWith( NT_PREFIX ) ? stripped.substring( NT_PREFIX.length() ) : stripped;
    }

    private static final java.util.regex.Pattern LEADING_MARKERS = java.util.regex.Pattern.compile( "^(?:\\*\\d+|!)*" );

    /**
     * Replays the operations in order and returns the install files that exist now, are needed,
     * and would not exist afterwards. A delete followed by a move onto the same path is a
     * replacement, not a loss.
     *
     * @param operations the pending operations, in boot order
     * @param installDir the install folder
     * @param exists     whether a path is a file today
     * @param needed     whether the install needs a file
     *
     * @return the files that would be lost, in the order first touched
     */
    static List< Path > threatenedFiles( List< PendingOperation > operations, Path installDir,
                                         Predicate< Path > exists, Predicate< Path > needed )
    {
        String root = key( installDir.toString() );
        Map< String, Boolean > presentAfter = new LinkedHashMap<>();
        Map< String, Path > paths = new LinkedHashMap<>();
        for ( PendingOperation operation : operations ) {
            String source = key( operation.source() );
            if ( isUnder( source, root ) ) {
                presentAfter.put( source, false );
                paths.putIfAbsent( source, Path.of( operation.source() ) );
            }
            if ( !operation.isDelete() ) {
                String target = key( operation.target() );
                if ( isUnder( target, root ) ) {
                    presentAfter.put( target, true );
                    paths.putIfAbsent( target, Path.of( operation.target() ) );
                }
            }
        }
        List< Path > lost = new ArrayList<>();
        presentAfter.forEach( ( key, present ) -> {
            Path path = paths.get( key );
            if ( !present && exists.test( path ) && needed.test( path ) ) {
                lost.add( path );
            }
        } );
        return lost;
    }

    /**
     * Whether the install needs a file. Everything does, except jars in {@code app\} that no
     * launcher config puts on the classpath: those are the previous version's, and deleting them
     * is the point of the pending operation.
     */
    static boolean isNeeded( Path installDir, Path file, Set< String > classpathJars )
    {
        // String-based like the rest of the comparison, so it behaves the same on every OS.
        String root = key( installDir.toString() );
        String path = key( file.toString() );
        if ( !isUnder( path, root ) ) {
            return true;
        }
        String relative = path.substring( root.length() + 1 );
        boolean appJar = relative.startsWith( "app\\" ) && relative.indexOf( '\\', 4 ) < 0
                         && relative.endsWith( ".jar" );
        return !appJar || classpathJars.contains( "*" ) || classpathJars.contains( relative.substring( 4 ) );
    }

    /**
     * File names (lower case) of the jars that the {@code app\*.cfg} launcher configs put on the
     * classpath.
     */
    static Set< String > classpathJars( Path appDir )
    {
        Set< String > jars = new HashSet<>();
        try ( Stream< Path > configs = Files.list( appDir ) ) {
            for ( Path config : configs.filter( p -> p.toString().endsWith( ".cfg" ) ).toList() ) {
                jars.addAll( classpathJars( Files.readAllLines( config, StandardCharsets.UTF_8 ) ) );
            }
        }
        catch ( IOException | RuntimeException e ) {
            // No configs readable: treat every jar as needed, the safe direction.
            jars.add( "*" );
        }
        return jars;
    }

    /** Jar file names (lower case) from a launcher config's {@code app.classpath} lines. */
    static Set< String > classpathJars( Collection< String > configLines )
    {
        Set< String > jars = new HashSet<>();
        for ( String line : configLines ) {
            if ( !line.startsWith( "app.classpath=" ) ) {
                continue;
            }
            for ( String entry : line.substring( "app.classpath=".length() ).split( ";" ) ) {
                String name = entry.substring( Math.max( entry.lastIndexOf( '\\' ), entry.lastIndexOf( '/' ) ) + 1 );
                if ( !name.isBlank() ) {
                    jars.add( name.trim().toLowerCase( Locale.ROOT ) );
                }
            }
        }
        return jars;
    }

    private static String key( String path )
    {
        String normalized = path.replace( '/', '\\' ).toLowerCase( Locale.ROOT );
        return normalized.endsWith( "\\" ) ? normalized.substring( 0, normalized.length() - 1 ) : normalized;
    }

    private static boolean isUnder( String path, String root )
    {
        return path.startsWith( root + "\\" );
    }

    /**
     * Reads a {@code REG_MULTI_SZ} value under {@link #SESSION_MANAGER_KEY} as raw characters.
     * JNA's {@code registryGetStringArray} is not used because a decoder that drops empty entries
     * would lose every delete's empty target.
     *
     * @return the raw characters, or {@code null} if the value does not exist
     */
    private static String readRawMultiString( String valueName )
    {
        WinReg.HKEY key = Advapi32Util.registryGetKey( WinReg.HKEY_LOCAL_MACHINE, SESSION_MANAGER_KEY,
                                                       WinNT.KEY_READ ).getValue();
        try {
            IntByReference type = new IntByReference();
            IntByReference size = new IntByReference();
            int result = Advapi32.INSTANCE.RegQueryValueEx( key, valueName, 0, type, ( com.sun.jna.Pointer ) null,
                                                            size );
            if ( result == W32Errors.ERROR_FILE_NOT_FOUND || size.getValue() == 0 ) {
                return null;
            }
            if ( result != W32Errors.ERROR_SUCCESS && result != W32Errors.ERROR_MORE_DATA ) {
                throw new IllegalStateException( "RegQueryValueEx(" + valueName + ") failed: " + result );
            }
            Memory data = new Memory( size.getValue() + 2L );
            data.clear();
            result = Advapi32.INSTANCE.RegQueryValueEx( key, valueName, 0, type, data, size );
            if ( result != W32Errors.ERROR_SUCCESS ) {
                throw new IllegalStateException( "RegQueryValueEx(" + valueName + ") failed: " + result );
            }
            if ( type.getValue() != WinNT.REG_MULTI_SZ ) {
                return null;
            }
            byte[] bytes = data.getByteArray( 0, size.getValue() - ( size.getValue() % 2 ) );
            return new String( bytes, StandardCharsets.UTF_16LE );
        }
        finally {
            Advapi32Util.registryCloseKey( key );
        }
    }

    /**
     * Asks the user, then removes the delete entries for {@code threatened} with an elevated
     * PowerShell, and reports the result.
     */
    private static void promptAndFix( Path installDir, List< Path > threatened, Stage owner )
    {
        int response = GUIUtilities.showQuestionMessage(
                LocalizationManager.get( "dialog.installGuard.title" ),
                LocalizationManager.get( "dialog.installGuard.header" ),
                LocalizationManager.format( "dialog.installGuard.body", threatened.size() ),
                LocalizationManager.get( "dialog.installGuard.button.fix" ),
                LocalizationManager.get( "dialog.installGuard.button.later" ),
                owner );
        if ( response != 1 ) {
            StartupDiagnostics.record( "User postponed removing the pending install-file deletes" );
            return;
        }
        String outcome;
        try {
            int exit = runElevatedRemoval( threatened );
            Logger.logStd( LocalizationManager.format( "log.installGuard.fixExit", exit ) );
            List< Path > remaining = findThreatenedFiles( installDir );
            outcome = remaining.isEmpty() ? null : "exit " + exit + ", " + remaining.size() + " still queued";
        }
        catch ( Exception e ) {
            Logger.logThrowable( e );
            outcome = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        StartupDiagnostics.record( "Pending install-file delete fix: " + ( outcome == null ? "done" : outcome ) );
        if ( outcome == null ) {
            NotificationManager.info( LocalizationManager.get( "dialog.installGuard.fixed.title" ),
                                      LocalizationManager.get( "dialog.installGuard.fixed.body" ) );
        }
        else {
            GUIUtilities.showErrorMessage( LocalizationManager.format( "dialog.installGuard.failed.body", outcome ),
                                           owner );
        }
    }

    /**
     * Writes the removal script and the list of paths to a temp folder and runs the script
     * elevated. The script backs up the original values beside itself before changing them, and
     * only drops delete entries whose source is in the list.
     *
     * @return the elevated script's exit code
     */
    private static int runElevatedRemoval( List< Path > threatened ) throws IOException, InterruptedException
    {
        Path work = Files.createTempDirectory( "mmcl-pfro-" );
        Path list = work.resolve( "paths.txt" );
        Path script = work.resolve( "remove-pending-deletes.ps1" );
        Files.write( list, threatened.stream().map( Path::toString ).toList(), StandardCharsets.UTF_8 );
        Files.writeString( script, removalScript(), StandardCharsets.UTF_8 );

        String inner = "'-NoProfile','-ExecutionPolicy','Bypass','-File','\"" + psQuote( script.toString() )
                       + "\"','\"" + psQuote( list.toString() ) + "\"'";
        Process process = new ProcessBuilder( "powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                                              "try { $p = Start-Process powershell.exe -Verb RunAs -Wait -PassThru"
                                              + " -WindowStyle Hidden -ArgumentList " + inner
                                              + "; exit $p.ExitCode } catch { exit 1223 }" )
                .redirectErrorStream( true ).start();
        process.getInputStream().transferTo( java.io.OutputStream.nullOutputStream() );
        if ( !process.waitFor( 5, TimeUnit.MINUTES ) ) {
            process.destroyForcibly();
            throw new IOException( "Timed out waiting for the elevated fix" );
        }
        return process.exitValue();
    }

    private static String psQuote( String value )
    {
        return value.replace( "'", "''" );
    }

    /**
     * The elevated script. It reads each value as .NET does (empty entries kept), drops delete
     * pairs whose source is listed, writes a backup of the original first, and writes back (or
     * removes the value when nothing is left).
     */
    static String removalScript()
    {
        return """
               param([string]$ListFile)
               $ErrorActionPreference = 'Stop'
               $targets = @(Get-Content -LiteralPath $ListFile -Encoding UTF8 | ForEach-Object { $_.ToLowerInvariant() })
               $key = [Microsoft.Win32.Registry]::LocalMachine.OpenSubKey('%s', $true)
               $backup = Join-Path (Split-Path -Parent $ListFile) 'backup.txt'
               foreach ($name in @(%s)) {
                 $v = $key.GetValue($name, $null, 'DoNotExpandEnvironmentNames')
                 if ($null -eq $v) { continue }
                 Add-Content -LiteralPath $backup -Encoding UTF8 -Value (@("[$name]") + $v)
                 $out = New-Object System.Collections.Generic.List[string]
                 for ($i = 0; $i -lt $v.Count; $i += 2) {
                   $src = $v[$i]
                   $dst = if ($i + 1 -lt $v.Count) { $v[$i + 1] } else { '' }
                   $plain = $src -replace '^(?:\\*\\d+|!)*\\\\\\?\\?\\\\', ''
                   if ($dst -eq '' -and $targets -contains $plain.ToLowerInvariant()) { continue }
                   $out.Add($src); $out.Add($dst)
                 }
                 if ($out.Count -eq 0) { $key.DeleteValue($name) }
                 else { $key.SetValue($name, [string[]]$out, [Microsoft.Win32.RegistryValueKind]::MultiString) }
               }
               $key.Close()
               exit 0
               """.formatted( SESSION_MANAGER_KEY,
                              String.join( ",", PENDING_VALUES.stream().map( v -> "'" + v + "'" ).toList() ) );
    }
}
