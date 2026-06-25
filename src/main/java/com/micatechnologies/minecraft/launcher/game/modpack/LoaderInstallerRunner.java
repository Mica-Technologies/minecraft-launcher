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
        if ( pack == null ) return Result.failure( "No pack supplied." );
        if ( dotMc == null || !Files.isDirectory( dotMc ) ) {
            return Result.failure( "Minecraft Launcher data folder not found at " + dotMc );
        }
        if ( pack.isVanillaVersion() ) {
            // Mojang launcher resolves vanilla versions itself — nothing to install.
            return Result.success( "Vanilla pack — Minecraft Launcher will fetch on first launch." );
        }

        String loaderType;
        try {
            loaderType = pack.getModLoaderType();
        }
        catch ( Exception e ) {
            return Result.failure( "Couldn't read pack's loader type: " + e.getMessage() );
        }
        if ( loaderType == null ) {
            return Result.failure( "Pack has no loader type set." );
        }

        return switch ( loaderType ) {
            case ModPackConstants.MOD_LOADER_FORGE,
                 ModPackConstants.MOD_LOADER_NEOFORGE -> runInstallerJar( pack, dotMc );
            case ModPackConstants.MOD_LOADER_FABRIC   -> writeFabricProfileJson( pack, dotMc );
            default -> Result.failure( "Auto-install not supported for loader type: " + loaderType );
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
        // Resolve a usable installer JAR. Mica's normal install flow
        // already downloads it to the pack's install folder when the pack
        // is launched; in that case we reuse it. If it's missing
        // (user is exporting a freshly-imported pack that's never been
        // launched), download to a temp file.
        File installerJar;
        try {
            installerJar = resolveInstallerJar( pack );
        }
        catch ( Exception e ) {
            return Result.failure( "Couldn't obtain the loader installer JAR: " + e.getMessage() );
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

        try {
            Logger.logStd( "LoaderInstallerRunner: running " + String.join( " ", pb.command() ) );
            Process proc = pb.start();

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
                return Result.failure( "Loader installer timed out after "
                                                + INSTALLER_TIMEOUT_SECONDS + "s." );
            }
            int code = proc.exitValue();
            String stderrText;
            synchronized ( stderr ) { stderrText = stderr.toString(); }

            if ( code != 0 ) {
                return Result.failure(
                        "Loader installer exited with code " + code
                                + ". This usually means the version doesn't support headless "
                                + "(--installClient) install — common on Forge versions older than 1.13. "
                                + "You can run the installer manually instead.",
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
                return Result.failure(
                        "Installer reported success but the launcher couldn't compute "
                                + "the expected version ID: " + e.getMessage() );
            }
            if ( !OfficialLauncherExporter.isVersionInstalled( dotMc, versionId ) ) {
                return Result.failure(
                        "Installer reported success but the expected version directory "
                                + "(.minecraft/versions/" + versionId + ") wasn't created.",
                        stderrText );
            }
            return Result.success( "Installed " + versionId + "." );
        }
        catch ( IOException e ) {
            return Result.failure( "Couldn't spawn loader installer process: " + e.getMessage() );
        }
        catch ( InterruptedException e ) {
            Thread.currentThread().interrupt();
            return Result.failure( "Loader installer wait was interrupted." );
        }
    }

    /** Returns the loader installer JAR file. Prefers the copy Mica's
     *  normal install pipeline downloaded under the pack folder; falls
     *  back to a fresh download into a per-launcher temp area if the
     *  pack hasn't been launched yet.
     *
     *  @param pack the pack whose loader installer JAR to resolve
     *  @return the local installer JAR file, ready to spawn
     *  @throws Exception if the pack has no installer URL or the fallback
     *                   download fails / produces an empty file */
    private static File resolveInstallerJar( GameModPack pack ) throws Exception
    {
        // Best path: Mica already has the installer locally because the
        // user launched the pack from Mica at least once.
        GameModLoader loader = pack.getModLoader();
        if ( loader instanceof ManagedGameFile mgf ) {
            File local = new File( mgf.getFullLocalFilePath() );
            if ( local.isFile() && local.length() > 0 ) {
                Logger.logDebug( "LoaderInstallerRunner: reusing installer at " + local );
                return local;
            }
        }
        // Fallback: download to a temp file. The pack carries the URL
        // (and ideally a SHA-1) so we can verify post-download.
        String url = pack.getModLoaderURL();
        if ( url == null || url.isBlank() ) {
            throw new IOException(
                    "Pack manifest has no packModLoaderURL — can't fetch the installer." );
        }
        Path tmp = Files.createTempFile( "mica-loader-installer-", ".jar" );
        // Mark for cleanup on JVM exit so a launcher kill mid-export
        // doesn't leave stale installer JARs lying around.
        tmp.toFile().deleteOnExit();
        Logger.logStd( "LoaderInstallerRunner: downloading installer from " + url );
        // Reuse Mica's bounded-download helper so the same trust gates
        // (https-only, redirect limits) cover this fetch path.
        File tmpFile = tmp.toFile();
        NetworkUtilities.downloadFileFromURL( url, tmpFile );
        if ( !tmpFile.isFile() || tmpFile.length() == 0 ) {
            throw new IOException( "Loader installer download produced an empty file." );
        }
        return tmpFile;
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
            return Result.failure( "Couldn't read pack's loader URL: " + e.getMessage() );
        }
        if ( url == null || url.isBlank() ) {
            return Result.failure( "Pack has no Fabric profile JSON URL." );
        }
        try {
            String body = NetworkUtilities.downloadFileFromURL( url );
            if ( body == null || body.isBlank() ) {
                return Result.failure( "Fabric profile JSON download returned an empty body." );
            }
            com.google.gson.JsonObject root =
                    com.micatechnologies.minecraft.launcher.utilities.JSONUtilities
                            .stringToObject( body );
            if ( root == null || !root.has( "id" ) ) {
                return Result.failure(
                        "Fabric profile JSON didn't contain an 'id' field — can't determine "
                                + "the version folder name." );
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
            return Result.success( "Installed Fabric profile for " + versionId + "." );
        }
        catch ( Exception e ) {
            return Result.failure( "Fabric install failed: " + e.getMessage() );
        }
    }
}
