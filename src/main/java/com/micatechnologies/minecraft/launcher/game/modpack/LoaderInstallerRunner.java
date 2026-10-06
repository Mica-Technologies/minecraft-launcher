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

package com.micatechnologies.minecraft.launcher.game.modpack;

import com.micatechnologies.minecraft.launcher.consts.ModPackConstants;
import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.utilities.NetworkUtilities;
import org.apache.commons.lang3.SystemUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.TimeUnit;

/**
 * Phase 4 follow-up to {@link OfficialLauncherExporter}: when the user
 * exports a modded pack to the Mojang launcher and the matching version
 * directory isn't installed yet, this helper drives the loader's
 * installer headlessly so the export profile works on first click
 * instead of showing the user a "Version not found" red entry until
 * they manually run the Forge / NeoForge / Fabric installer.
 *
 * <h3>Per-loader install strategy</h3>
 *
 * <ul>
 *   <li><b>Forge / NeoForge</b> — both ship a Swing-fronted installer
 *       JAR that also accepts {@code --installClient <minecraft-dir>}
 *       as a headless flag. Forge 1.13+ supports it cleanly; 1.12.2 and
 *       earlier varies — the {@link Result#errorMessage} surfaces the
 *       process's stderr so the user can see exactly which version
 *       refused. We spawn the same JVM Mica is running under via
 *       {@code java.home}, so no separate JRE install is required.</li>
 *   <li><b>Fabric</b> — Fabric is a runtime loader; the "install" is
 *       just dropping the profile JSON under
 *       {@code versions/<id>/<id>.json}. Mica already knows the JSON
 *       URL (the pack's {@code packModLoaderURL}), so we fetch and
 *       write directly without going through Fabric's stand-alone
 *       installer JAR. Same result; simpler control flow.</li>
 *   <li><b>Vanilla</b> — nothing to install. Mojang's launcher
 *       resolves vanilla versions on first launch via piston-meta.
 *       Returns success immediately so the export flow's
 *       "loader installed?" precondition treats vanilla as always-met.</li>
 * </ul>
 *
 * <h3>Timeouts + cancellation</h3>
 *
 * <p>The Forge / NeoForge installer normally completes in 5-30s
 * depending on the version (more for older Forge that fetches MCP
 * mappings). A hard {@link #INSTALLER_TIMEOUT_SECONDS} cap kills the
 * process if something hangs — better to surface a clear timeout error
 * than leave the user staring at a frozen toast for minutes.</p>
 *
 * @since 2026.5
 */
public final class LoaderInstallerRunner
{
    /** Max wall-clock seconds we'll wait for the Forge / NeoForge
     *  installer process to finish. 180s is generous — typical runs
     *  finish in 10-30s; older Forge versions that download additional
     *  artifacts can take longer. */
    private static final int INSTALLER_TIMEOUT_SECONDS = 180;

    /** Non-instantiable; all entry points are static. */
    private LoaderInstallerRunner() { /* static-only */ }

    /**
     * Outcome of an install attempt.
     *
     * @param success         {@code true} if the loader was installed (or no
     *                        install was needed); {@code false} on any failure
     * @param message         human-readable summary suitable for a toast / log
     * @param installerStderr captured stderr from the installer process when
     *                        relevant to a failure, otherwise {@code null}
     */
    public record Result(
            boolean success,
            String message,
            String installerStderr
    ) {
        /** Builds a success result with no captured stderr.
         *
         *  @param msg the success summary
         *  @return a successful {@code Result} */
        public static Result success( String msg ) { return new Result( true, msg, null ); }

        /** Builds a failure result with no captured stderr.
         *
         *  @param msg the failure summary
         *  @return a failed {@code Result} */
        public static Result failure( String msg ) { return new Result( false, msg, null ); }

        /** Builds a failure result carrying the installer's captured stderr.
         *
         *  @param msg    the failure summary
         *  @param stderr the installer process stderr to surface for diagnosis
         *  @return a failed {@code Result} */
        public static Result failure( String msg, String stderr ) {
            return new Result( false, msg, stderr );
        }
    }

    /**
     * Installs the loader's version files into the Mojang launcher's
     * {@code .minecraft} directory so subsequent launches from the
     * Mojang launcher recognise the version. Idempotent — re-running
     * over an existing install rewrites the same files.
     *
     * @param pack  the pack whose loader to install
     * @param dotMc the resolved Mojang launcher data folder
     * @return a {@link Result} describing success (including the no-op vanilla
     *         case) or the specific failure encountered
     */
    public static Result install( GameModPack pack, Path dotMc )
    {
        if ( pack == null ) return Result.failure( LocalizationManager.get( "import.export.noPack" ) );
        if ( dotMc == null || !Files.isDirectory( dotMc ) ) {
            return Result.failure( LocalizationManager.format( "import.export.dataFolderMissing",
                                                               String.valueOf( dotMc ) ) );
        }
        if ( pack.isVanillaVersion() ) {
            // Mojang launcher resolves vanilla versions itself — nothing to install.
            return Result.success( LocalizationManager.get( "officialExport.loader.vanilla" ) );
        }

        String loaderType;
        try {
            loaderType = pack.getModLoaderType();
        }
        catch ( Exception e ) {
            return Result.failure( LocalizationManager.format( "officialExport.loader.typeReadFailed",
                                                               String.valueOf( e.getMessage() ) ) );
        }
        if ( loaderType == null ) {
            return Result.failure( LocalizationManager.get( "officialExport.loader.noType" ) );
        }

        return switch ( loaderType ) {
            case ModPackConstants.MOD_LOADER_FORGE,
                 ModPackConstants.MOD_LOADER_NEOFORGE -> runInstallerJar( pack, dotMc );
            case ModPackConstants.MOD_LOADER_FABRIC   -> writeFabricProfileJson( pack, dotMc );
            default -> Result.failure( LocalizationManager.format( "officialExport.loader.unsupportedType",
                                                                   loaderType ) );
        };
    }

    // ====================================================================
    // Forge / NeoForge — spawn the installer JAR with --installClient
    // ====================================================================

    /** Spawns the Forge / NeoForge installer JAR headlessly with
     *  {@code --installClient} pointed at the Mojang launcher data folder,
     *  draining its stdout / stderr, enforcing {@link #INSTALLER_TIMEOUT_SECONDS},
     *  and confirming the expected version directory was created before reporting
     *  success.
     *
     *  @param pack  the pack whose loader installer to run
     *  @param dotMc the Mojang launcher data folder to install into
     *  @return a {@link Result} reflecting the install outcome (including
     *          timeout, non-zero exit, or a missing version directory) */
    private static Result runInstallerJar( GameModPack pack, Path dotMc )
    {
        // Resolve the installer JAR through the pack's managed loader file,
        // the same https + declared-hash path a launch uses. Never fetched
        // ad hoc: the result is executed below.
        File installerJar;
        try {
            installerJar = resolveInstallerJar( pack );
        }
        catch ( Exception e ) {
            return Result.failure( LocalizationManager.format( "officialExport.loader.obtainFailed",
                                                               String.valueOf( e.getMessage() ) ) );
        }

        // Spawn: java -jar <installer.jar> --installClient <dotMc>
        // The Forge / NeoForge installers detect headless + the flag and
        // skip the Swing UI, doing the install + writing
        // .minecraft/versions/<id>/<id>.json on success.
        Path javaExe = currentJavaExecutable();
        ProcessBuilder pb = new ProcessBuilder(
                javaExe.toString(),
                "-jar",
                installerJar.getAbsolutePath(),
                "--installClient",
                dotMc.toAbsolutePath().toString()
        );
        pb.redirectErrorStream( false );

        Process started = null;
        try {
            Logger.logStd( LocalizationManager.format( "log.loaderInstallerRunner.running", String.join( " ", pb.command() ) ) );
            Process proc = pb.start();
            started = proc;

            // Read stderr in a background thread so the process can't
            // deadlock if it writes more than the OS pipe buffer.
            StringBuilder stderr = new StringBuilder();
            Thread errReader = new Thread( () -> {
                try ( BufferedReader r = new BufferedReader(
                        new InputStreamReader( proc.getErrorStream(), StandardCharsets.UTF_8 ) ) ) {
                    String line;
                    while ( ( line = r.readLine() ) != null ) {
                        synchronized ( stderr ) { stderr.append( line ).append( '\n' ); }
                    }
                }
                catch ( IOException ignored ) { /* process exited */ }
            }, "loader-installer-stderr" );
            errReader.setDaemon( true );
            errReader.start();

            // Drain stdout too (don't fail if it overflows).
            Thread outReader = new Thread( () -> {
                try ( BufferedReader r = new BufferedReader(
                        new InputStreamReader( proc.getInputStream(), StandardCharsets.UTF_8 ) ) ) {
                    String line;
                    while ( ( line = r.readLine() ) != null ) {
                        Logger.logDebug( "[loader-installer] " + line );
                    }
                }
                catch ( IOException ignored ) { /* process exited */ }
            }, "loader-installer-stdout" );
            outReader.setDaemon( true );
            outReader.start();

            boolean finished = proc.waitFor( INSTALLER_TIMEOUT_SECONDS, TimeUnit.SECONDS );
            if ( !finished ) {
                proc.destroyForcibly();
                return Result.failure( LocalizationManager.format( "officialExport.loader.timedOut",
                                                                   INSTALLER_TIMEOUT_SECONDS ) );
            }
            int code = proc.exitValue();
            String stderrText;
            synchronized ( stderr ) { stderrText = stderr.toString(); }

            if ( code != 0 ) {
                return Result.failure(
                        LocalizationManager.format( "officialExport.loader.exitCode", String.valueOf( code ) ),
                        stderrText );
            }

            // Sanity-check the install actually produced the expected
            // version manifest. The installer returning exit code 0
            // doesn't always guarantee the .minecraft/versions/<id>/
            // directory is fully populated (especially on older Forge).
            String versionId;
            try {
                versionId = OfficialLauncherExporter.computeVersionId( pack );
            }
            catch ( Exception e ) {
                return Result.failure( LocalizationManager.format( "officialExport.loader.versionIdFailed",
                                                                   String.valueOf( e.getMessage() ) ) );
            }
            if ( !OfficialLauncherExporter.isVersionInstalled( dotMc, versionId ) ) {
                return Result.failure(
                        LocalizationManager.format( "officialExport.loader.versionDirMissing", versionId ),
                        stderrText );
            }
            return Result.success( LocalizationManager.format( "officialExport.loader.installed", versionId ) );
        }
        catch ( IOException e ) {
            return Result.failure( LocalizationManager.format( "officialExport.loader.spawnFailed",
                                                               String.valueOf( e.getMessage() ) ) );
        }
        catch ( InterruptedException e ) {
            Thread.currentThread().interrupt();
            // Cancelled: don't leave the installer JVM writing into .minecraft behind us.
            if ( started != null && started.isAlive() ) {
                started.destroyForcibly();
            }
            return Result.failure( LocalizationManager.get( "officialExport.loader.interrupted" ) );
        }
    }

    /** Returns the loader installer JAR file, verified the same way a launch
     *  verifies it. The installer is the pack's loader {@link ManagedGameFile}
     *  (under the pack folder), so it is only ever fetched over https and
     *  checked against the manifest's declared hash before it is run. It is
     *  re-checked here even if it was verified earlier this session, since it
     *  may have been deleted or replaced since, and this method's caller
     *  executes it with {@code java -jar}.
     *
     *  @param pack the pack whose loader installer JAR to resolve
     *  @return the local installer JAR file, ready to spawn
     *  @throws Exception if the installer can't be downloaded or verified, or
     *                   the pack's loader isn't a verifiable managed file */
    private static File resolveInstallerJar( GameModPack pack ) throws Exception
    {
        // Constructing the loader (on first use) already runs the managed
        // download + hash check, so a never-launched pack is covered too.
        GameModLoader loader = pack.getModLoader();
        if ( !( loader instanceof ManagedGameFile mgf ) ) {
            throw new IOException( LocalizationManager.get( "officialExport.loader.unverifiable" ) );
        }
        mgf.reverifyLocalFile();
        File local = new File( mgf.getFullLocalFilePath() );
        if ( !local.isFile() || local.length() == 0 ) {
            throw new IOException( LocalizationManager.get( "officialExport.loader.unverifiable" ) );
        }
        Logger.logDebug( LocalizationManager.format( "log.loaderInstallerRunner.verifiedInstaller", local ) );
        return local;
    }

    /** Resolves the {@code java} executable from the JVM Mica is running
     *  under. Same JVM as the launcher itself — guaranteed present, no
     *  separate runtime install required.
     *
     *  @return the path to this JVM's {@code java}/{@code java.exe} binary */
    private static Path currentJavaExecutable()
    {
        String javaHome = System.getProperty( "java.home" );
        String exe = SystemUtils.IS_OS_WINDOWS ? "java.exe" : "java";
        return Paths.get( javaHome, "bin", exe );
    }

    // ====================================================================
    // Fabric — write the profile JSON straight into the versions folder
    // ====================================================================

    /** Fabric is a runtime loader; its "install" for the Mojang
     *  launcher is just dropping the profile JSON under
     *  {@code .minecraft/versions/<id>/<id>.json}. Mica's pack manifest
     *  already carries the profile-JSON URL, so we fetch and write
     *  directly rather than running Fabric's stand-alone installer JAR.
     *
     *  <p>The {@code id} field inside the profile JSON is authoritative
     *  for the version folder name — Fabric meta currently emits
     *  {@code fabric-loader-<loader>-<mc>}, but if that ever changes we
     *  honour what the JSON actually says rather than guessing.</p>
     *
     *  @param pack  the Fabric pack whose profile JSON to install
     *  @param dotMc the Mojang launcher data folder to write the profile into
     *  @return a {@link Result} reflecting the write outcome (failure on a
     *          missing URL, empty body, absent {@code id} field, or I/O error) */
    private static Result writeFabricProfileJson( GameModPack pack, Path dotMc )
    {
        String url;
        try {
            url = pack.getModLoaderURL();
        }
        catch ( Exception e ) {
            return Result.failure( LocalizationManager.format( "officialExport.loader.urlReadFailed",
                                                               String.valueOf( e.getMessage() ) ) );
        }
        if ( url == null || url.isBlank() ) {
            return Result.failure( LocalizationManager.get( "officialExport.loader.noFabricUrl" ) );
        }
        try {
            String body = NetworkUtilities.downloadFileFromURL( url );
            if ( body == null || body.isBlank() ) {
                return Result.failure( LocalizationManager.get( "officialExport.loader.fabricEmpty" ) );
            }
            com.google.gson.JsonObject root =
                    com.micatechnologies.minecraft.launcher.utilities.JSONUtilities
                            .stringToObject( body );
            if ( root == null || !root.has( "id" ) ) {
                return Result.failure( LocalizationManager.get( "officialExport.loader.fabricNoId" ) );
            }
            String versionId = root.get( "id" ).getAsString();
            Path versionDir = dotMc.resolve( "versions" ).resolve( versionId );
            Files.createDirectories( versionDir );
            Path target = versionDir.resolve( versionId + ".json" );

            // Atomic write via a tmp file — same discipline as
            // OfficialLauncherExporter's profile write so a crash mid-
            // copy can't leave a half-written manifest the Mojang
            // launcher will refuse to parse.
            Path tmp = versionDir.resolve( versionId + ".json.tmp" );
            Files.writeString( tmp, body, StandardCharsets.UTF_8 );
            try {
                Files.move( tmp, target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING );
            }
            catch ( java.nio.file.AtomicMoveNotSupportedException atomicEx ) {
                Files.move( tmp, target, StandardCopyOption.REPLACE_EXISTING );
            }
            return Result.success( LocalizationManager.format( "officialExport.loader.fabricInstalled",
                                                               versionId ) );
        }
        catch ( Exception e ) {
            return Result.failure( LocalizationManager.format( "officialExport.loader.fabricFailed",
                                                               String.valueOf( e.getMessage() ) ) );
        }
    }
}
