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

package com.micatechnologies.minecraft.launcher.files;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.launcher.consts.RuntimeConstants;
import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
import com.micatechnologies.minecraft.launcher.gui.MCLauncherGuiController;
import com.micatechnologies.minecraft.launcher.gui.MCLauncherProgressGui;
import com.micatechnologies.minecraft.launcher.utilities.ArchiveExtractor;
import com.micatechnologies.minecraft.launcher.utilities.FileUtilities;
import com.micatechnologies.minecraft.launcher.utilities.HashUtilities;
import com.micatechnologies.minecraft.launcher.utilities.JsonHelper;
import com.micatechnologies.minecraft.launcher.utilities.NetworkUtilities;
import com.micatechnologies.minecraft.launcher.utilities.SystemUtilities;
import io.github.palexdev.materialfx.controls.MFXProgressBar;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Class for managing the download and usage of JREs required for Minecraft. Uses Mojang's official Java runtime
 * distribution to ensure compatibility with all Minecraft versions. Supports multiple runtime components
 * (jre-legacy, java-runtime-alpha, java-runtime-beta, java-runtime-gamma, java-runtime-delta, java-runtime-epsilon).
 *
 * @author Mica Technologies
 * @version 3.0
 * @since 1.1
 */
public class RuntimeManager
{
    /**
     * Cache of verified Java executable paths keyed by runtime component name.
     */
    private static final Map< String, String > verifiedPaths = new ConcurrentHashMap<>();

    /**
     * Cache of verified Java version strings keyed by runtime component name.
     */
    private static final Map< String, String > verifiedVersions = new ConcurrentHashMap<>();

    /**
     * Cached Mojang runtime index JSON.
     */
    private static JsonObject runtimeIndex = null;

    /**
     * Verifies the Mojang runtime for the given component name, downloading files as needed.
     *
     * @param component  the Mojang runtime component name (e.g. "jre-legacy", "java-runtime-gamma")
     * @param showProgress whether to show a progress GUI window
     *
     * @since 3.0
     */
    /**
     * Functional interface for receiving runtime verification progress updates.
     */
    @FunctionalInterface
    public interface RuntimeProgressCallback
    {
        void onProgress( String statusText );
    }

    /**
     * Verifies the Mojang runtime for the given component name, downloading files as needed.
     *
     * @param component  the Mojang runtime component name
     * @param showProgress whether to show a progress GUI window
     */
    public static void verifyRuntime( String component, boolean showProgress ) {
        verifyRuntime( component, showProgress, null );
    }

    /**
     * Verifies the Mojang runtime with an optional progress callback for inline status updates.
     *
     * @param component        the Mojang runtime component name
     * @param showProgress     whether to show a standalone progress GUI window
     * @param progressCallback optional callback for status text updates (used when embedded in another progress flow)
     */
    public static void verifyRuntime( String component, boolean showProgress, RuntimeProgressCallback progressCallback ) {
        // Mojang's jre-legacy is Java 8u51, which is too old for Forge (needs 8u121+ for sun.misc.ObjectInputFilter).
        // Use Bell-SW Liberica 8u392 instead, which is the last known-good JRE 8 for Minecraft + Forge.
        if ( "jre-legacy".equals( component ) ) {
            verifyLegacyJre( showProgress, progressCallback );
            return;
        }

        MCLauncherProgressGui progressWindow = null;
        if ( showProgress ) {
            try {
                if ( MCLauncherGuiController.shouldCreateGui() ) {
                    progressWindow = MCLauncherGuiController.goToProgressGui();
                }
            }
            catch ( IOException e ) {
                Logger.logError( LocalizationManager.get( "log.runtimeManager.progressGuiLoadFailed" ) );
                Logger.logThrowable( e );
            }
        }

        String label = LocalizationManager.format( "runtime.label.named", component );
        reportProgress( progressWindow, progressCallback, label,
                        LocalizationManager.get( "runtime.status.checking" ), 5 );

        String runtimeFolderPath = getComponentRuntimeFolderPath( component );
        File runtimeFolder = SynchronizedFileManager.getSynchronizedFile( runtimeFolderPath );
        runtimeFolder.mkdirs();

        String newJavaPath = null;
        String newJavaVersion = null;

        // Fast path: a runtime is already installed (version marker + java exec
        // both present). Trust it and resolve immediately, WITHOUT the synchronous
        // Mojang index fetch below — that ~500 KB round trip otherwise blocks every
        // launch (even fully warm) on the network, stalling up to ~40 s on a flaky
        // link before the cached fallback. We can't tell from disk alone whether a
        // newer runtime exists, so the update check runs in the background: if
        // Mojang ships a newer versionName it drops the version marker, and the
        // NEXT launch falls through to a full verify + install.
        File installedVersionFile = new File( runtimeFolderPath, RuntimeConstants.RUNTIME_VERSION_FILE_NAME );
        File installedJavaExec = new File( runtimeFolderPath, RuntimeConstants.getJavaExecPathForOs() );
        if ( installedVersionFile.exists() && installedJavaExec.exists() ) {
            String installedVersion;
            try {
                installedVersion = org.apache.commons.io.FileUtils.readFileToString(
                        installedVersionFile, "UTF-8" ).trim();
            }
            catch ( IOException e ) {
                installedVersion = "Unknown";
            }
            Logger.logStd( LocalizationManager.format( "log.runtimeManager.alreadyInstalledBgCheck",
                                                       component, installedVersion ) );
            verifiedPaths.put( component, installedJavaExec.getAbsolutePath() );
            verifiedVersions.put( component, installedVersion );
            reportProgress( progressWindow, progressCallback, label,
                            LocalizationManager.get( "runtime.status.alreadyInstalled" ), 100 );
            final String installedVersionFinal = installedVersion;
            SystemUtilities.spawnNewTask(
                    () -> backgroundRuntimeUpdateCheck( component, runtimeFolderPath, installedVersionFinal ) );
            return;
        }

        try {
            // Get the Mojang runtime index
            reportProgress( progressWindow, progressCallback, label,
                            LocalizationManager.get( "runtime.status.fetchingIndex" ), 10 );
            JsonObject index = getMojangRuntimeIndex();
            String platform = RuntimeConstants.getMojangPlatformKey();

            if ( !index.has( platform ) ) {
                throw new Exception( "Platform '" + platform + "' not found in Mojang runtime index." );
            }

            JsonObject platformObj = index.getAsJsonObject( platform );
            if ( !platformObj.has( component ) ) {
                throw new Exception( "Runtime component '" + component + "' not found for platform '" + platform + "'." );
            }

            JsonArray componentArray = platformObj.getAsJsonArray( component );
            if ( componentArray.isEmpty() ) {
                throw new Exception( "No runtime entries for component '" + component + "' on platform '" + platform + "'." );
            }

            JsonObject componentEntry = componentArray.get( 0 ).getAsJsonObject();
            JsonObject manifestObj = JsonHelper.getRequiredJsonObject( componentEntry, "manifest" );
            String manifestUrl = JsonHelper.getRequiredString( manifestObj, "url" );
            JsonObject versionObj = JsonHelper.getRequiredJsonObject( componentEntry, "version" );
            String versionName = JsonHelper.getRequiredString( versionObj, "name" );

            // Check if we already have this version installed
            File versionFile = new File( runtimeFolderPath, RuntimeConstants.RUNTIME_VERSION_FILE_NAME );
            if ( versionFile.exists() ) {
                String installedVersion = org.apache.commons.io.FileUtils.readFileToString( versionFile,
                                                                                             "UTF-8" ).trim();
                File javaExec = new File( runtimeFolderPath, RuntimeConstants.getJavaExecPathForOs() );
                if ( installedVersion.equals( versionName ) && javaExec.exists() ) {
                    Logger.logStd( LocalizationManager.format( "log.runtimeManager.alreadyInstalled",
                                                               component, versionName ) );
                    newJavaPath = javaExec.getAbsolutePath();
                    newJavaVersion = versionName;
                    reportProgress( progressWindow, progressCallback, label,
                                    LocalizationManager.get( "runtime.status.alreadyInstalled" ), 100 );
                    verifiedPaths.put( component, newJavaPath );
                    verifiedVersions.put( component, newJavaVersion );
                    return;
                }
            }

            // Download the file manifest
            reportProgress( progressWindow, progressCallback, label,
                            LocalizationManager.get( "runtime.status.downloadingManifest" ), 15 );
            File manifestFile = new File( runtimeFolderPath, RuntimeConstants.RUNTIME_MANIFEST_FILE_NAME );
            NetworkUtilities.downloadFileFromURL( manifestUrl, manifestFile );
            JsonObject manifest = FileUtilities.readAsJsonObject( manifestFile );
            JsonObject files = manifest.getAsJsonObject( "files" );

            // Count total files for progress
            int totalFiles = files.entrySet().size();

            Logger.logStd( LocalizationManager.format( "log.runtimeManager.installing",
                                                       component, versionName, totalFiles ) );

            // Process each file entry. Each "relativePath" is attacker-controllable in
            // principle (Mojang publishes the manifest, but defense-in-depth: a path
            // like "../../launcher/config.json" would escape the runtime folder). Treat
            // the runtime folder as the containment root and reject anything that
            // resolves outside, mirroring the same check ArchiveExtractor uses.
            final java.nio.file.Path runtimeBase = runtimeFolder.toPath()
                                                                .toAbsolutePath()
                                                                .normalize();

            // First pass (sequential): validate entry names, create directories, and
            // collect the file entries that need downloading. Directories must exist
            // before the parallel file writes below target them.
            final java.util.concurrent.atomic.AtomicInteger processedCounter =
                    new java.util.concurrent.atomic.AtomicInteger( 0 );
            final int totalFilesFinal = totalFiles;
            final MCLauncherProgressGui progressWindowFinal = progressWindow;
            List< java.util.concurrent.Callable< Void > > fileTasks = new ArrayList<>();
            for ( Map.Entry< String, JsonElement > entry : files.entrySet() ) {
                String relativePath = entry.getKey();
                JsonObject fileEntry = entry.getValue().getAsJsonObject();
                String type = JsonHelper.getRequiredString( fileEntry, "type" );

                if ( relativePath.indexOf( '\0' ) >= 0
                        || relativePath.startsWith( "/" )
                        || relativePath.startsWith( "\\" )
                        || ( relativePath.length() >= 2 && relativePath.charAt( 1 ) == ':' ) ) {
                    Logger.logWarningSilent(
                            "Skipping unsafe runtime manifest entry name: " + relativePath );
                    continue;
                }
                java.nio.file.Path resolved = runtimeBase.resolve( relativePath ).normalize();
                if ( !resolved.startsWith( runtimeBase ) ) {
                    Logger.logWarningSilent(
                            "Skipping runtime manifest entry that escapes base dir: " + relativePath );
                    continue;
                }
                final File localFile = resolved.toFile();

                if ( "directory".equals( type ) ) {
                    localFile.mkdirs();
                }
                else if ( "file".equals( type ) ) {
                    JsonObject downloads = JsonHelper.getRequiredJsonObject( fileEntry, "downloads" );
                    JsonObject rawDownload = JsonHelper.getRequiredJsonObject( downloads, "raw" );
                    final String sha1 = JsonHelper.getRequiredString( rawDownload, "sha1" );
                    final String url = JsonHelper.getRequiredString( rawDownload, "url" );
                    final boolean executable = fileEntry.has( "executable" )
                            && fileEntry.get( "executable" ).getAsBoolean();
                    final String relativePathFinal = relativePath;
                    fileTasks.add( () -> {
                        // Only download if file doesn't exist or hash doesn't match
                        if ( !localFile.exists() || !HashUtilities.verifySHA1( localFile, sha1 ) ) {
                            localFile.getParentFile().mkdirs();
                            // Post-download integrity gate: the manifest's SHA-1 must hold
                            // for the bytes actually received, otherwise the mismatched
                            // file would be installed and executed as the JRE this session.
                            // Bounded retry absorbs transient corruption.
                            final int maxAttempts = 3;
                            boolean downloadVerified = false;
                            for ( int attempt = 1; attempt <= maxAttempts && !downloadVerified; attempt++ ) {
                                NetworkUtilities.downloadFileFromURL( url, localFile );
                                downloadVerified = HashUtilities.verifySHA1( localFile, sha1 );
                                if ( !downloadVerified ) {
                                    //noinspection ResultOfMethodCallIgnored
                                    localFile.delete();
                                    Logger.logWarningSilent(
                                            "Runtime file failed hash verification (attempt " + attempt + " of " +
                                                    maxAttempts + "): " + relativePathFinal );
                                }
                            }
                            if ( !downloadVerified ) {
                                throw new IOException(
                                        "Runtime file failed hash verification after " + maxAttempts +
                                                " attempts: " + relativePathFinal + " (from " + url + ")" );
                            }
                        }

                        // Set executable permission if needed
                        if ( executable ) {
                            localFile.setExecutable( true );
                        }

                        int done = processedCounter.incrementAndGet();
                        if ( progressWindowFinal != null && done % 20 == 0 ) {
                            double pct = 15 + ( 80.0 * done / totalFilesFinal );
                            reportProgress( progressWindowFinal, progressCallback, label,
                                            LocalizationManager.format( "runtime.status.installingFiles",
                                                                        done, totalFilesFinal ), pct );
                        }
                        return null;
                    } );
                }
                // Skip "link" type entries on Windows (symlinks require elevated privileges)
            }

            // Second pass (parallel): a runtime is ~500-700 small files; downloading
            // them one at a time made first install take minutes. Latency-bound, so
            // allow more concurrency than core count.
            if ( !fileTasks.isEmpty() ) {
                int poolSize = Math.max( 1, Math.min( fileTasks.size(),
                        Math.max( 12, Runtime.getRuntime().availableProcessors() ) ) );
                java.util.concurrent.ExecutorService filePool =
                        java.util.concurrent.Executors.newFixedThreadPool( poolSize );
                List< java.util.concurrent.Future< Void > > futures;
                try {
                    futures = filePool.invokeAll( fileTasks );
                }
                catch ( InterruptedException ie ) {
                    filePool.shutdownNow();
                    Thread.currentThread().interrupt();
                    throw new IOException( "Interrupted while installing the Java runtime.", ie );
                }
                finally {
                    filePool.shutdown();
                }
                // Surface the first per-file failure.
                for ( java.util.concurrent.Future< Void > f : futures ) {
                    try {
                        f.get();
                    }
                    catch ( InterruptedException ie ) {
                        Thread.currentThread().interrupt();
                        throw new IOException( "Interrupted while installing the Java runtime.", ie );
                    }
                    catch ( java.util.concurrent.ExecutionException ee ) {
                        Throwable cause = ee.getCause();
                        throw new IOException( "Failed to install a Java runtime file: "
                                + ( cause == null ? ee.getMessage() : cause.getMessage() ), cause );
                    }
                }
            }

            // Write version marker
            org.apache.commons.io.FileUtils.writeStringToFile( versionFile, versionName, "UTF-8" );

            // Resolve java executable path
            File javaExec = new File( runtimeFolderPath, RuntimeConstants.getJavaExecPathForOs() );
            if ( javaExec.exists() ) {
                newJavaPath = javaExec.getAbsolutePath();
                newJavaVersion = versionName;
                Logger.logStd( LocalizationManager.format( "log.runtimeManager.installedSuccess",
                                                           component, versionName ) );
            }
            else {
                // Try finding java executable by searching
                Logger.logError( LocalizationManager.format( "log.runtimeManager.javaExecNotFound",
                                                             javaExec.getAbsolutePath() ) );
                newJavaPath = findJavaExecutable( runtimeFolder );
                newJavaVersion = versionName;
                if ( newJavaPath == null ) {
                    Logger.logError( LocalizationManager.get( "log.runtimeManager.javaExecNotFoundFallback" ) );
                    newJavaPath = "java";
                    newJavaVersion = "Unknown (System Java)";
                }
            }
        }
        catch ( Exception e ) {
            Logger.logError( LocalizationManager.format( "log.runtimeManager.installFailed",
                                                         component, e.getMessage() ) );
            Logger.logThrowable( e );
            // Try to use existing installation even if update check failed
            File javaExec = new File( runtimeFolderPath, RuntimeConstants.getJavaExecPathForOs() );
            if ( javaExec.exists() ) {
                newJavaPath = javaExec.getAbsolutePath();
                File versionFile = new File( runtimeFolderPath, RuntimeConstants.RUNTIME_VERSION_FILE_NAME );
                try {
                    newJavaVersion = org.apache.commons.io.FileUtils.readFileToString( versionFile, "UTF-8" ).trim();
                }
                catch ( Exception ignored ) {
                    newJavaVersion = "Unknown";
                }
                Logger.logStd( LocalizationManager.format( "log.runtimeManager.usingExisting", newJavaPath ) );
            }
            else {
                newJavaPath = "java";
                newJavaVersion = "Unknown (System Java)";
            }
        }

        reportProgress( progressWindow, progressCallback, label,
                        LocalizationManager.get( "runtime.status.completed" ), 100 );

        verifiedPaths.put( component, newJavaPath );
        verifiedVersions.put( component, newJavaVersion );
    }

    /**
     * Gets the Java executable path for the specified runtime component. Verifies/downloads on demand.
     *
     * @param component the Mojang runtime component name
     *
     * @return the path to the Java executable
     *
     * @since 3.0
     */
    public static String getJavaPath( String component ) {
        if ( !verifiedPaths.containsKey( component ) ) {
            verifyRuntime( component, false );
        }
        return verifiedPaths.get( component );
    }

    /**
     * Gets the Java version string for the specified runtime component.
     *
     * @param component the runtime component name
     *
     * @return the version string, or null if not verified
     *
     * @since 3.0
     */
    public static String getJavaVersion( String component ) {
        return verifiedVersions.get( component );
    }

    /**
     * Deletes the runtime installation for the specified component.
     *
     * @param component the runtime component name
     *
     * @throws IOException if unable to delete the runtime
     *
     * @since 3.0
     */
    public static void clearRuntime( String component ) throws IOException {
        String folderPath = getComponentRuntimeFolderPath( component );
        File folder = SynchronizedFileManager.getSynchronizedFile( folderPath );
        if ( folder.exists() ) {
            FileUtils.deleteDirectory( folder );
        }
        verifiedPaths.remove( component );
        verifiedVersions.remove( component );
    }

    /**
     * Returns a list of installed runtime entries with component name, version, and size.
     *
     * @return list of installed runtime info maps
     *
     * @since 3.0
     */
    public static List< Map< String, String > > getInstalledRuntimes() {
        List< Map< String, String > > runtimes = new ArrayList<>();
        String runtimeRootPath = LocalPathManager.getLauncherRuntimeFolderPath();
        File runtimeRoot = SynchronizedFileManager.getSynchronizedFile( runtimeRootPath );
        if ( !runtimeRoot.exists() || !runtimeRoot.isDirectory() ) {
            return runtimes;
        }

        File[] children = runtimeRoot.listFiles();
        if ( children == null ) {
            return runtimes;
        }

        for ( File child : children ) {
            if ( !child.isDirectory() ) {
                continue;
            }

            File versionFile = new File( child, RuntimeConstants.RUNTIME_VERSION_FILE_NAME );
            if ( !versionFile.exists() ) {
                continue;
            }

            Map< String, String > info = new LinkedHashMap<>();
            info.put( "component", child.getName() );
            try {
                info.put( "version", org.apache.commons.io.FileUtils.readFileToString( versionFile, "UTF-8" ).trim() );
            }
            catch ( IOException e ) {
                info.put( "version", "Unknown" );
            }
            info.put( "path", child.getAbsolutePath() );

            long sizeBytes = FileUtils.sizeOfDirectory( child );
            long sizeMB = sizeBytes / ( 1024 * 1024 );
            info.put( "sizeMB", String.valueOf( sizeMB ) );

            runtimes.add( info );
        }

        return runtimes;
    }

    // --- Backward-compatible wrappers (use component names internally) ---

    /**
     * Verifies a runtime by Java major version. Maps the major version to the default component name.
     *
     * @param majorVersion the Java major version
     * @param showProgress whether to show progress GUI
     *
     * @since 2.0
     */
    public static void verifyJre( int majorVersion, boolean showProgress ) {
        verifyRuntime( majorVersionToComponent( majorVersion ), showProgress );
    }

    /**
     * Verifies a runtime by Java major version with progress GUI.
     *
     * @param majorVersion the Java major version
     *
     * @since 2.0
     */
    public static void verifyJre( int majorVersion ) {
        verifyJre( majorVersion, true );
    }

    /**
     * Gets the Java executable path for the specified major version.
     *
     * @param majorVersion the Java major version
     *
     * @return the path to the Java executable
     *
     * @since 2.0
     */
    public static String getJavaPath( int majorVersion ) {
        return getJavaPath( majorVersionToComponent( majorVersion ) );
    }

    /**
     * Gets the Java version string for the specified major version.
     *
     * @param majorVersion the Java major version
     *
     * @return the version string
     *
     * @since 2.0
     */
    public static String getJavaVersion( int majorVersion ) {
        return getJavaVersion( majorVersionToComponent( majorVersion ) );
    }

    /**
     * Deletes the runtime for the specified major version.
     *
     * @param majorVersion the Java major version
     *
     * @throws IOException if unable to delete
     *
     * @since 2.0
     */
    public static void clearRuntime( int majorVersion ) throws IOException {
        clearRuntime( majorVersionToComponent( majorVersion ) );
    }

    /** @since 1.0 */
    public static void verifyJre8() { verifyJre( 8 ); }

    /** @since 1.0 */
    public static void clearJre8() throws IOException { clearRuntime( 8 ); }

    /** @since 1.0 */
    public static String getJre8Path() { return getJavaPath( 8 ); }

    /** @since 1.0 */
    public static String getJre8Version() { return getJavaVersion( 8 ); }

    // --- Legacy JRE 8 via Bell-SW Liberica (Mojang's 8u51 is too old for Forge) ---

    /**
     * Bell-SW Liberica API URL for JRE 8u392 (last known-good for Minecraft + Forge).
     */
    private static final String LIBERICA_JRE8_API_TEMPLATE
            = "https://api.bell-sw.com/v1/liberica/releases?version-feature=8&version-update=392&bitness=64&installation-type=archive&os={OS}&arch={ARCH}&bundle-type=jre";

    /**
     * Verifies/downloads the legacy JRE 8 using Bell-SW Liberica 8u392 instead of Mojang's too-old 8u51.
     */
    private static void verifyLegacyJre( boolean showProgress, RuntimeProgressCallback progressCallback ) {
        String component = "jre-legacy";
        MCLauncherProgressGui progressWindow = null;
        if ( showProgress ) {
            try {
                if ( MCLauncherGuiController.shouldCreateGui() ) {
                    progressWindow = MCLauncherGuiController.goToProgressGui();
                }
            }
            catch ( IOException e ) {
                Logger.logError( LocalizationManager.get( "log.runtimeManager.progressGuiLoadFailedLegacy" ) );
            }
        }

        String label = LocalizationManager.get( "runtime.label.legacy" );
        reportProgress( progressWindow, progressCallback, label,
                        LocalizationManager.get( "runtime.status.checking" ), 5 );

        String runtimeFolderPath = getComponentRuntimeFolderPath( component );
        File runtimeFolder = SynchronizedFileManager.getSynchronizedFile( runtimeFolderPath );
        runtimeFolder.mkdirs();

        String newJavaPath = null;
        String newJavaVersion = null;

        try {
            // Build API URL for current OS
            String os, arch;
            if ( org.apache.commons.lang3.SystemUtils.IS_OS_WINDOWS ) {
                os = "windows"; arch = "x86";
            }
            else if ( org.apache.commons.lang3.SystemUtils.IS_OS_MAC ) {
                os = "macos";
                arch = System.getProperty( "os.arch", "" ).contains( "aarch64" ) ? "arm" : "x86";
            }
            else {
                os = "linux";
                arch = System.getProperty( "os.arch", "" ).contains( "aarch64" ) ? "arm" : "x86";
            }
            String apiUrl = LIBERICA_JRE8_API_TEMPLATE.replace( "{OS}", os ).replace( "{ARCH}", arch );

            // Download API info
            reportProgress( progressWindow, progressCallback, label,
                            LocalizationManager.get( "runtime.status.fetchingJre8Info" ), 15 );
            String apiDataFileName = "jre-legacy.api.json";
            File apiFile = new File( runtimeFolderPath, apiDataFileName );

            JsonArray apiData = null;
            try {
                NetworkUtilities.downloadFileFromURL( apiUrl, apiFile, "application/json" );
                apiData = FileUtilities.readAsJsonArray( apiFile );
            }
            catch ( Exception e ) {
                if ( apiFile.exists() ) {
                    apiData = FileUtilities.readAsJsonArray( apiFile );
                    Logger.logWarningSilent( LocalizationManager.get( "log.runtimeManager.usingCachedJre8Info" ) );
                }
                else {
                    throw e;
                }
            }

            if ( apiData == null || apiData.isEmpty() ) {
                throw new Exception( "No JRE 8 info available from Liberica API." );
            }

            JsonObject info = apiData.get( 0 ).getAsJsonObject();
            newJavaVersion = JsonHelper.getRequiredString( info, "version" );

            // Check if already installed
            File versionFile = new File( runtimeFolderPath, RuntimeConstants.RUNTIME_VERSION_FILE_NAME );
            String bundleType = JsonHelper.getRequiredString( info, "bundleType" );
            int featureVersion = JsonHelper.getInt( info, "featureVersion", 8 );
            int updateVersion = JsonHelper.getInt( info, "updateVersion", 0 );
            String extractedFolderName = bundleType + featureVersion + "u" + updateVersion;
            File extractedFolder = new File( runtimeFolderPath, extractedFolderName );
            // On macOS, Liberica JRE bundles extract with a .jre suffix (e.g. jre8u392.jre)
            File altFolder = new File( runtimeFolderPath, extractedFolderName + "." + bundleType );
            File effectiveFolder = extractedFolder.exists() ? extractedFolder :
                                   altFolder.exists() ? altFolder : extractedFolder;

            if ( versionFile.exists() && effectiveFolder.exists() ) {
                String installed = org.apache.commons.io.FileUtils.readFileToString( versionFile, "UTF-8" ).trim();
                if ( installed.equals( newJavaVersion ) ) {
                    File javaExec = new File( effectiveFolder, RuntimeConstants.getJavaExecPathForOs() );
                    if ( !javaExec.exists() ) {
                        // Liberica archive layout (no .bundle wrapper) — find java by name.
                        String found = findJavaExecutable( effectiveFolder );
                        if ( found != null ) {
                            javaExec = new File( found );
                        }
                    }
                    if ( javaExec.exists() ) {
                        // Heal pre-2026.x installs that landed without execute bits set.
                        markJavaBinariesExecutable( javaExec );
                        Logger.logStd( LocalizationManager.format( "log.runtimeManager.jre8AlreadyInstalled",
                                                                   newJavaVersion ) );
                        verifiedPaths.put( component, javaExec.getAbsolutePath() );
                        verifiedVersions.put( component, newJavaVersion );
                        reportProgress( progressWindow, progressCallback, label,
                                        LocalizationManager.get( "runtime.status.alreadyInstalled" ), 100 );
                        return;
                    }
                }
            }

            // Verify/download archive
            reportProgress( progressWindow, progressCallback, label,
                            LocalizationManager.get( "runtime.status.verifyingJre8" ), 30 );
            String archiveHash = JsonHelper.getRequiredString( info, "sha1" );
            File archiveFile = new File( runtimeFolderPath, JsonHelper.getRequiredString( info, "filename" ) );
            if ( !HashUtilities.verifySHA1( archiveFile, archiveHash ) ) {
                reportProgress( progressWindow, progressCallback, label,
                                LocalizationManager.get( "runtime.status.downloadingJre8" ), -1 );
                NetworkUtilities.downloadFileFromURL( JsonHelper.getRequiredString( info, "downloadUrl" ),
                                                      archiveFile );
            }

            // Extract
            reportProgress( progressWindow, progressCallback, label,
                            LocalizationManager.get( "runtime.status.extractingJre8" ), 75 );
            if ( extractedFolder.exists() ) {
                FileUtils.deleteDirectory( extractedFolder );
            }
            if ( altFolder.exists() ) {
                FileUtils.deleteDirectory( altFolder );
            }
            // Hardened extract: containment check on every
            // entry, symlink/hardlink/device rejection (TAR can carry them and a
            // hostile community-built JRE could escape the runtime folder via one),
            // size caps to bound zip-bomb damage. See ArchiveExtractor for the
            // accept/reject rules.
            String pkgType = JsonHelper.getString( info, "packageType", "tar.gz" );
            if ( pkgType.equals( "tar.gz" ) ) {
                ArchiveExtractor.extractTarGz( archiveFile.toPath(), runtimeFolder.toPath() );
            }
            else {
                ArchiveExtractor.extractZip( archiveFile.toPath(), runtimeFolder.toPath() );
            }

            // Find the extracted folder (may have a different suffix, e.g. .jre on macOS)
            effectiveFolder = extractedFolder.exists() ? extractedFolder :
                              altFolder.exists() ? altFolder : extractedFolder;

            // Resolve java executable
            File javaExec = new File( effectiveFolder, RuntimeConstants.getJavaExecPathForOs() );
            if ( javaExec.exists() ) {
                newJavaPath = javaExec.getAbsolutePath();
            }
            else {
                newJavaPath = findJavaExecutable( effectiveFolder );
            }

            if ( newJavaPath == null ) {
                newJavaPath = "java";
                newJavaVersion = "Unknown (System Java)";
            }
            else {
                // ArchiveExtractor drops POSIX permissions, so the freshly
                // extracted java binary lands non-executable on macOS/Linux.
                // Restore the execute bit on every launcher binary.
                markJavaBinariesExecutable( new File( newJavaPath ) );
            }

            // Write version marker
            org.apache.commons.io.FileUtils.writeStringToFile( versionFile, newJavaVersion, "UTF-8" );

            Logger.logStd( LocalizationManager.format( "log.runtimeManager.jre8InstalledSuccess",
                                                       newJavaVersion ) );
        }
        catch ( Exception e ) {
            Logger.logError( LocalizationManager.format( "log.runtimeManager.jre8InstallFailed",
                                                         e.getMessage() ) );
            Logger.logThrowable( e );
            newJavaPath = "java";
            newJavaVersion = "Unknown (System Java)";
        }

        reportProgress( progressWindow, progressCallback, label,
                        LocalizationManager.get( "runtime.status.completed" ), 100 );
        verifiedPaths.put( component, newJavaPath );
        verifiedVersions.put( component, newJavaVersion );
    }

    // --- Internal helpers ---

    /**
     * Maps a Java major version to the Mojang runtime component name.
     */
    static String majorVersionToComponent( int majorVersion ) {
        return switch ( majorVersion ) {
            case 8 -> "jre-legacy";
            case 16 -> "java-runtime-alpha";
            case 17 -> "java-runtime-gamma";
            case 21 -> "java-runtime-delta";
            case 25 -> "java-runtime-epsilon";
            default -> {
                // For unknown versions, try to find a close match
                if ( majorVersion <= 8 ) yield "jre-legacy";
                else if ( majorVersion <= 16 ) yield "java-runtime-alpha";
                else if ( majorVersion <= 17 ) yield "java-runtime-gamma";
                else if ( majorVersion <= 21 ) yield "java-runtime-delta";
                else yield "java-runtime-epsilon";
            }
        };
    }

    /**
     * Returns the per-component runtime folder path.
     */
    private static String getComponentRuntimeFolderPath( String component ) {
        return SystemUtilities.buildFilePath( LocalPathManager.getLauncherRuntimeFolderPath(), component );
    }

    /**
     * Off-launch-path check for a newer Java runtime. Fetches the Mojang index,
     * compares the latest {@code versionName} for {@code component} against the
     * installed one, and — if newer — deletes the on-disk version marker so the
     * fast path in {@link #verifyRuntime} misses on the next launch and runs a
     * full verify + install. Best-effort: any failure (offline, parse error) is
     * logged and ignored, leaving the working runtime in place.
     */
    private static void backgroundRuntimeUpdateCheck( String component, String runtimeFolderPath,
                                                      String installedVersion ) {
        try {
            JsonObject index = getMojangRuntimeIndex();
            String platform = RuntimeConstants.getMojangPlatformKey();
            if ( !index.has( platform ) ) {
                return;
            }
            JsonObject platformObj = index.getAsJsonObject( platform );
            if ( !platformObj.has( component ) ) {
                return;
            }
            JsonArray componentArray = platformObj.getAsJsonArray( component );
            if ( componentArray.isEmpty() ) {
                return;
            }
            JsonObject versionObj = JsonHelper.getRequiredJsonObject(
                    componentArray.get( 0 ).getAsJsonObject(), "version" );
            String latestVersion = JsonHelper.getRequiredString( versionObj, "name" );
            if ( !latestVersion.equals( installedVersion ) ) {
                Logger.logStd( LocalizationManager.format( "log.runtimeManager.newerAvailable",
                                                           component, installedVersion, latestVersion ) );
                File versionFile = new File( runtimeFolderPath, RuntimeConstants.RUNTIME_VERSION_FILE_NAME );
                //noinspection ResultOfMethodCallIgnored
                versionFile.delete();
            }
        }
        catch ( Exception e ) {
            Logger.logWarningSilent( LocalizationManager.format( "log.runtimeManager.bgUpdateCheckFailed",
                                                                 e.getClass().getSimpleName() ) );
        }
    }

    /**
     * Fetches and caches the Mojang runtime index.
     */
    private static synchronized JsonObject getMojangRuntimeIndex() throws Exception {
        if ( runtimeIndex != null ) {
            return runtimeIndex;
        }

        String runtimeFolderPath = LocalPathManager.getLauncherRuntimeFolderPath();
        File runtimeFolder = SynchronizedFileManager.getSynchronizedFile( runtimeFolderPath );
        runtimeFolder.mkdirs();

        File indexFile = new File( runtimeFolderPath, RuntimeConstants.MOJANG_RUNTIME_INDEX_FILE_NAME );
        try {
            NetworkUtilities.downloadFileFromURL( RuntimeConstants.MOJANG_RUNTIME_INDEX_URL, indexFile );
        }
        catch ( IOException e ) {
            if ( indexFile.exists() ) {
                Logger.logWarningSilent( LocalizationManager.get( "log.runtimeManager.indexUpdateFailedCached" ) );
            }
            else {
                throw e;
            }
        }

        runtimeIndex = FileUtilities.readAsJsonObject( indexFile );
        return runtimeIndex;
    }

    /**
     * Attempts to find a java executable by searching the runtime folder.
     */
    private static String findJavaExecutable( File runtimeFolder ) {
        String javaName = org.apache.commons.lang3.SystemUtils.IS_OS_WINDOWS ? "java.exe" : "java";
        return searchForFile( runtimeFolder, javaName );
    }

    /**
     * Restores the owner execute bit on every regular file in the {@code bin/}
     * directory containing {@code javaExec}, plus {@code lib/jspawnhelper} when
     * present. No-op on Windows, where execution is governed by file extension.
     * Used by the Liberica legacy-JRE flow, which extracts via
     * {@link ArchiveExtractor} that intentionally drops POSIX permissions.
     */
    static void markJavaBinariesExecutable( File javaExec ) {
        if ( javaExec == null || org.apache.commons.lang3.SystemUtils.IS_OS_WINDOWS ) {
            return;
        }
        File binDir = javaExec.getParentFile();
        if ( binDir == null || !binDir.isDirectory() ) {
            return;
        }
        File[] binFiles = binDir.listFiles();
        if ( binFiles != null ) {
            for ( File f : binFiles ) {
                if ( f.isFile() && !f.canExecute() && !f.setExecutable( true ) ) {
                    Logger.logWarningSilent(
                            "Failed to mark JRE binary executable: " + f.getAbsolutePath() );
                }
            }
        }
        File jreRoot = binDir.getParentFile();
        if ( jreRoot != null ) {
            File jspawnHelper = new File( jreRoot, "lib" + File.separator + "jspawnhelper" );
            if ( jspawnHelper.isFile() && !jspawnHelper.canExecute() ) {
                jspawnHelper.setExecutable( true );
            }
        }
    }

    /**
     * Recursively searches for a file by name.
     */
    private static String searchForFile( File dir, String name ) {
        File[] children = dir.listFiles();
        if ( children == null ) {
            return null;
        }
        for ( File child : children ) {
            if ( child.isFile() && child.getName().equals( name ) ) {
                return child.getAbsolutePath();
            }
            if ( child.isDirectory() ) {
                String result = searchForFile( child, name );
                if ( result != null ) {
                    return result;
                }
            }
        }
        return null;
    }

    /**
     * Reports progress via the standalone progress window, the inline callback, or the console log.
     */
    private static void reportProgress( MCLauncherProgressGui progressWindow, RuntimeProgressCallback callback,
                                         String upperLabel, String lowerText, double percent )
    {
        if ( progressWindow != null ) {
            progressWindow.setUpperLabelText( upperLabel );
            progressWindow.setSectionText( lowerText );
            progressWindow.setProgress( percent );
        }
        if ( callback != null ) {
            callback.onProgress( lowerText );
        }
        String percentStr = percent >= 0 ? " (" + ( int ) percent + "%)" : "";
        Logger.logStd( upperLabel + ": " + lowerText + percentStr );
    }
}
