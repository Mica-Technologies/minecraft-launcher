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

package com.micatechnologies.minecraft.launcher.consts;

import com.micatechnologies.minecraft.launcher.LauncherCore;

import java.util.Map;

/**
 * Class of constants/statics for use across package.
 * <p>
 * NOTE: This class should NOT contain display strings that are visible to the end-user. All localizable strings MUST be
 * stored and retrieved using {@link com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager}.
 *
 * @author Mica Technologies
 * @version 1.1
 * @since 1.0
 */
public class LauncherConstants
{
    /**
     * Launcher boolean indicating if the application is in development mode. True when:
     * 1. Running from IDE (no implementation version in manifest), OR
     * 2. Built with the {@code -Pdev} Maven profile (manifest contains {@code Launcher-Environment: DEV})
     */
    public final static boolean LAUNCHER_IS_DEV = detectDevMode();

    /**
     * Launcher application name. Auto-filled from manifest, with DEV suffix when in dev mode.
     *
     * @since 1.0
     */
    public final static String LAUNCHER_APPLICATION_NAME = resolveAppName();

    /**
     * Launcher application version. Resolved at class-init time in this order:
     * <ol>
     *   <li>JAR manifest's {@code Implementation-Version} (packaged builds — set by Maven from
     *       {@code ${project.version}}, which the {@code derive-revision-from-git} step in pom.xml
     *       resolves from git describe or {@code -Drevision=})</li>
     *   <li>{@code git describe --tags --dirty=.dirty --always} run against the repo containing
     *       the running class (IDE / unpackaged runs — same string the packaged build would have
     *       baked in)</li>
     *   <li>{@code "0.0.0-dev"} (no manifest, no .git ancestor found, or git unavailable)</li>
     * </ol>
     *
     * @since 1.0
     */
    public final static String LAUNCHER_APPLICATION_VERSION = resolveAppVersion();

    /**
     * Launcher application name without spaces.
     *
     * @since 1.0
     */
    public final static String LAUNCHER_APPLICATION_NAME_TRIMMED = LAUNCHER_APPLICATION_NAME.replaceAll( " ", "" );

    /**
     * Determines whether the launcher is running in development mode. Returns {@code true} when the launcher's package
     * has no implementation version (typical of an IDE / unpackaged run) or when the JAR manifest contains a
     * {@code Launcher-Environment: DEV} entry (set by the {@code -Pdev} Maven profile).
     *
     * @return {@code true} if the launcher is running in development mode, {@code false} otherwise
     */
    private static boolean detectDevMode() {
        // No manifest version means running from IDE -- always dev mode
        if ( LauncherCore.class.getPackage().getImplementationVersion() == null ) {
            return true;
        }
        // Check for Launcher-Environment: DEV manifest entry (set by -Pdev Maven profile)
        try {
            java.util.jar.Manifest manifest = new java.util.jar.Manifest(
                    LauncherCore.class.getResourceAsStream( "/META-INF/MANIFEST.MF" ) );
            String env = manifest.getMainAttributes().getValue( "Launcher-Environment" );
            return "DEV".equalsIgnoreCase( env );
        }
        catch ( Exception e ) {
            return false;
        }
    }

    /**
     * Resolves the launcher application name from the JAR manifest's implementation title, appending a {@code " DEV"}
     * suffix when dev mode is active and the title does not already advertise it. Falls back to a hard-coded dev name
     * when no manifest title is present (e.g. an IDE run).
     *
     * @return the resolved launcher application name
     */
    private static String resolveAppName() {
        String title = LauncherCore.class.getPackage().getImplementationTitle();
        if ( title != null ) {
            // If dev mode is active but the manifest title doesn't include DEV, append it
            if ( LAUNCHER_IS_DEV && !title.toUpperCase().contains( "DEV" ) ) {
                return title + " DEV";
            }
            return title;
        }
        return "Mica Minecraft Launcher DEV";
    }

    /**
     * Resolves the launcher application version. Prefers the JAR manifest's {@code Implementation-Version} (present in
     * packaged builds); when absent, falls back to {@code git describe} against the repository containing the running
     * class, and finally to the {@code "0.0.0-dev"} sentinel when neither is available.
     *
     * @return the resolved launcher application version string
     */
    private static String resolveAppVersion() {
        String fromManifest = LauncherCore.class.getPackage().getImplementationVersion();
        if ( fromManifest != null ) {
            return fromManifest;
        }
        String fromGit = tryGitDescribe();
        return fromGit != null ? fromGit : "0.0.0-dev";
    }

    /**
     * Attempts to derive a version string by running {@code git describe --tags --dirty=.dirty --always} in the
     * repository that contains the running class. The invocation is bounded by a short (2 second) timeout so a stuck
     * git process cannot hang launcher startup; on timeout, non-zero exit, blank output, or any error the method
     * returns {@code null} so the caller can fall back to the dev sentinel.
     *
     * @return the trimmed {@code git describe} output, or {@code null} if the repository root cannot be found, git is
     *         unavailable, the invocation fails or times out, or the output is empty
     */
    private static String tryGitDescribe() {
        java.nio.file.Path repoRoot = findRepoRoot();
        if ( repoRoot == null ) {
            return null;
        }
        try {
            Process p = new ProcessBuilder( "git", "describe", "--tags", "--dirty=.dirty", "--always" )
                    .directory( repoRoot.toFile() )
                    .redirectErrorStream( true )
                    .start();
            // 2s is comfortable headroom for a local git describe; we'd rather fall back to
            // the "0.0.0-dev" sentinel than hang the splash screen on a stuck git invocation.
            if ( !p.waitFor( 2, java.util.concurrent.TimeUnit.SECONDS ) ) {
                p.destroyForcibly();
                return null;
            }
            if ( p.exitValue() != 0 ) {
                return null;
            }
            try ( java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader( p.getInputStream(),
                                                   java.nio.charset.StandardCharsets.UTF_8 ) ) ) {
                String line = reader.readLine();
                return ( line != null && !line.isBlank() ) ? line.trim() : null;
            }
        }
        catch ( Exception ignored ) {
            return null;
        }
    }

    /**
     * Locates the Git repository root for the running class by walking up from its {@code CodeSource} location until a
     * {@code .git} entry is found. Both worktree layouts (where {@code .git} is a regular file) and standard clones
     * (where it is a directory) are handled.
     *
     * @return the repository root path, or {@code null} if no {@code .git} ancestor is found or the location cannot be
     *         resolved (e.g. when invoked from a JAR URL outside a repository)
     */
    // Walks up from the running class's CodeSource until it hits a `.git` entry — works for the
    // regular IDE case (target/classes or out/production/...) and for git worktrees (where .git
    // is a file, not a directory). Returns null if invoked from a JAR URL outside a repo, in
    // which case getImplementationVersion() was almost certainly non-null and we never reach here.
    private static java.nio.file.Path findRepoRoot() {
        try {
            java.net.URL location = LauncherCore.class.getProtectionDomain().getCodeSource().getLocation();
            if ( location == null ) {
                return null;
            }
            java.nio.file.Path current = java.nio.file.Paths.get( location.toURI() );
            while ( current != null ) {
                java.nio.file.Path git = current.resolve( ".git" );
                if ( java.nio.file.Files.isDirectory( git ) || java.nio.file.Files.isRegularFile( git ) ) {
                    return current;
                }
                current = current.getParent();
            }
        }
        catch ( Exception ignored ) {
        }
        return null;
    }

    /**
     * Argument used to open application in forced client mode.
     *
     * @since 1.0
     */
    public static final String PROGRAM_ARG_CLIENT_MODE = "-c";

    /**
     * Argument used to open application in forced server mode.
     *
     * @since 1.0
     */
    public static final String PROGRAM_ARG_SERVER_MODE = "-s";

    /**
     * The minimum value allowed for the minimum RAM configuration in settings.
     *
     * @since 1.1
     */
    public static final double SETTINGS_MIN_RAM_MIN = 0.2;

    /**
     * The maximum value allowed for the minimum RAM configuration in settings.
     *
     * @since 1.1
     */
    public static final double SETTINGS_MIN_RAM_MAX = 32.0;

    /**
     * The maximum value allowed for the maximum RAM configuration in settings.
     *
     * @since 1.1
     */
    public static final double SETTINGS_MAX_RAM_MIN = 1.0;

    /**
     * The maximum value allowed for the maximum RAM configuration in settings.
     *
     * @since 1.1
     */
    public static final double SETTINGS_MAX_RAM_MAX = 64.0;

    /**
     * Exit code used when the launcher is closing cleanly/normally.
     *
     * @since 1.1
     */
    public static final int EXIT_STATUS_CODE_GOOD = 0;

    /**
     * Port used by the single-instance lock socket for release builds. Chosen to be high and unlikely to conflict.
     *
     * @since 2.0
     */
    public static final int SINGLE_INSTANCE_PORT = 47821;

    /**
     * Port used by the single-instance lock socket for development builds. Separate from release so both can run
     * simultaneously.
     *
     * @since 2.0
     */
    public static final int SINGLE_INSTANCE_PORT_DEV = 47822;

    /**
     * Map containing the JVM properties that must be applied at startup of each instance
     * of the application.
     *
     * <p>Pipeline selection: previously this map forced {@code prism.order=sw} (CPU
     * software rendering). That predated JavaFX 25 and the move to MaterialFX /
     * Mica / GPU-composited surfaces; on modern hardware it just made scroll lag
     * and made the Mica focus-regain repaint stall. We let JavaFX pick the best
     * available pipeline now: D3D on Windows, Metal on macOS, ES2 on Linux, with
     * software as the implicit fallback.</p>
     *
     * <p>{@code prism.forceUploadingPainter=true} switches Prism from the default
     * {@code PresentingPainter} to {@code UploadingPainter}. The PresentingPainter
     * uses the GPU compositor's back-buffer directly — fast for opaque windows,
     * but on Windows it doesn't preserve alpha through the DWM redirection bitmap,
     * so a transparent JavaFX scene over Mica comes out solid black. The
     * UploadingPainter rasterizes to a CPU buffer first and uploads with an
     * alpha-aware texture, which gives DWM/Mica the transparent pixels it needs
     * while still keeping the rest of the rendering on the GPU.</p>
     *
     * @since 1.1
     */
    public static final Map< String, String > JVM_PROPERTIES = Map.of(
            "prism.lcdtext", "false",
            "prism.text", "t2k",
            "prism.forceUploadingPainter", "true",
            "sun.net.http.allowRestrictedHeaders", "true" );
}
