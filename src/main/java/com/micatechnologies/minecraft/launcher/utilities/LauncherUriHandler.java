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

import com.micatechnologies.minecraft.launcher.LauncherCore;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.game.modpack.GameModPack;
import com.micatechnologies.minecraft.launcher.game.modpack.GameModPackManager;
import com.micatechnologies.minecraft.launcher.gui.GUIUtilities;
import com.micatechnologies.minecraft.launcher.gui.MCLauncherGuiController;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Parses and dispatches {@code mmcl://} URIs that deep-link into the launcher. The website
 * (and any future tooling that wants to hand a user off to the desktop launcher) emits URIs
 * shaped like:
 *
 * <pre>
 *   mmcl://add?url=&lt;url-encoded modpack manifest URL&gt;     — install/register a modpack
 *   mmcl://play?name=&lt;pack name or friendly name&gt;          — launch a specific installed pack
 *   mmcl://join?url=&lt;url-encoded modpack manifest URL&gt;    — install (if needed) AND launch a modpack
 *   mmcl://open                                                  — just bring the launcher to focus
 * </pre>
 *
 * <p>{@code mmcl://join} is the action Discord's "Join Game" button hands back through
 * {@link DiscordRpcUtility}'s onActivityJoin callback when a friend clicks to join a session.
 * It combines add + play so the friend doesn't have to install the pack manually first.</p>
 *
 * <p>Two delivery paths into this handler:</p>
 * <ol>
 *     <li><b>Cold start.</b> The OS hands the URI to the launcher via argv. {@code LauncherCore.parseLauncherArgs}
 *         detects the {@code mmcl://} prefix and stashes it in
 *         {@link LauncherCore#consumePendingLauncherUri()} for the launcher session to
 *         dispatch once the main GUI is up.</li>
 *     <li><b>Already running.</b> macOS routes URIs through
 *         {@code Desktop.setOpenURIHandler}, registered alongside the other system-app
 *         handlers in {@code SystemMenuBarManager}. Windows and Linux currently launch a
 *         fresh process per URI; cross-instance forwarding (IPC through the
 *         {@code SingleInstanceLock} channel) is the remaining piece to make
 *         already-running delivery work on those platforms — see
 *         {@code OS_INTEGRATION_POLISH.md}.</li>
 * </ol>
 *
 * <p>Install-time scheme registration is also still TODO. jpackage's per-platform install
 * scripts will need to declare {@code mmcl://} as a handled scheme:</p>
 * <ul>
 *     <li>Windows: registry keys under {@code HKCR\mmcl}.</li>
 *     <li>macOS: {@code CFBundleURLTypes} in {@code Info.plist}.</li>
 *     <li>Linux: {@code MimeType=x-scheme-handler/mmcl;} in the {@code .desktop} file.</li>
 * </ul>
 */
public final class LauncherUriHandler
{
    public static final  String SCHEME        = "mmcl";
    private static final String SCHEME_PREFIX = SCHEME + "://";

    /** Hosts whose modpack manifests we install without an extra confirmation prompt.
     *  These are the official Mica-controlled origins. Any other host the URI dispatcher
     *  sees triggers the untrusted-host confirmation dialog (security finding 1.6) —
     *  a malicious phishing link emitting {@code mmcl://add?url=http://attacker/x.json}
     *  can no longer install silently. */
    private static final Set< String > TRUSTED_INSTALL_HOSTS = Set.of(
            "micauseaststorage.blob.core.windows.net" );

    /** Result of validating an install URL coming in over the {@code mmcl://} channel. */
    private enum InstallUrlVerdict
    {
        /** URL is well-formed https, host is on the trusted allowlist. Install silently. */
        ACCEPT_TRUSTED,
        /** URL is well-formed https but the host is unknown. Prompt the user before install. */
        REQUIRE_CONFIRMATION,
        /** URL is missing, malformed, non-https, contains control chars, etc. Refuse. */
        REJECT
    }

    private LauncherUriHandler() { /* static-only */ }

    /** True if the string looks like a launcher URI (case-insensitive). */
    public static boolean isLauncherUri( String s )
    {
        return s != null && s.regionMatches( true, 0, SCHEME_PREFIX, 0, SCHEME_PREFIX.length() );
    }

    /**
     * Parses the URI and dispatches the corresponding action. Safe to call from any thread —
     * each action handler routes through {@link SystemUtilities#spawnNewTask} or the FX
     * thread as appropriate. Errors are logged and never propagated; a malformed URI from
     * an external link should never crash the launcher.
     */
    public static void handle( String uriString )
    {
        if ( !isLauncherUri( uriString ) ) {
            return;
        }
        URI uri;
        try {
            uri = URI.create( uriString );
        }
        catch ( Exception e ) {
            Logger.logWarningSilent( "Malformed launcher URI: " + uriString );
            return;
        }

        String action = uri.getHost();
        if ( action == null ) {
            // mmcl:add?... (no //) — URI parser puts everything after the colon in path/query.
            // Fall back to a manual extraction so authoring style is forgiving.
            String body = uriString.substring( SCHEME_PREFIX.length() );
            int q = body.indexOf( '?' );
            action = q < 0 ? body : body.substring( 0, q );
        }
        Map< String, String > params = parseQuery( uri.getRawQuery() );

        Logger.logDebug( "Launcher URI dispatch: action=" + action + " params=" + params );

        switch ( action.toLowerCase() ) {
            case "add"  -> handleAdd( params.get( "url" ) );
            case "play" -> handlePlay( params.get( "name" ) );
            case "join" -> handleJoin( params.get( "url" ), params.get( "vanilla" ) );
            case "open" -> handleOpen();
            default     -> Logger.logWarningSilent( "Unknown mmcl:// action: " + action );
        }
    }

    // =========================================================================================
    //  Action handlers
    // =========================================================================================

    /** {@code mmcl://add?url=...} — installs a modpack from its manifest URL. Toasts the
     *  result so the user sees feedback even if their attention has shifted away from the
     *  launcher window. Untrusted hosts trigger a confirmation prompt before install. */
    private static void handleAdd( String url )
    {
        if ( url == null || url.isBlank() ) {
            Logger.logWarningSilent( "mmcl://add missing required url parameter" );
            return;
        }
        SystemUtilities.spawnNewTask( () -> {
            if ( !confirmInstallUrl( url, "Install modpack from this site?" ) ) {
                return;
            }
            try {
                GameModPackManager.installModPackByURL( url );
                NotificationManager.success( "Modpack added",
                                             "Successfully registered the modpack from the link." );
                // Refresh the main GUI on the FX thread so the new pack appears in the
                // hero-card grid without the user needing to navigate away and back.
                GUIUtilities.JFXPlatformRun( () -> {
                    try {
                        MCLauncherGuiController.goToMainGui();
                    }
                    catch ( Exception ignored ) { /* user may not be on main; that's fine */ }
                } );
            }
            catch ( Exception e ) {
                Logger.logError( "Failed to install modpack via mmcl://add — " + e.getMessage() );
                Logger.logThrowable( e );
                NotificationManager.error( "Couldn't add modpack",
                                           "The link looked valid but the install failed. See log for detail." );
            }
        } );
    }

    /** {@code mmcl://play?name=...} — launches an installed modpack by pack name or friendly
     *  name. Refuses gracefully if the pack isn't installed, prompting the user to add it first. */
    private static void handlePlay( String name )
    {
        if ( name == null || name.isBlank() ) {
            Logger.logWarningSilent( "mmcl://play missing required name parameter" );
            return;
        }
        SystemUtilities.spawnNewTask( () -> {
            GameModPack pack = GameModPackManager.getInstalledModPackByName( name );
            if ( pack == null ) {
                for ( GameModPack p : GameModPackManager.getInstalledModPacks() ) {
                    if ( name.equalsIgnoreCase( p.getFriendlyName() ) ) {
                        pack = p;
                        break;
                    }
                }
            }
            if ( pack == null ) {
                NotificationManager.warn( "Modpack not installed",
                                          "“" + name + "” isn't installed. Add it first via the website link." );
                return;
            }
            LauncherCore.play( pack );
        } );
    }

    /** {@code mmcl://join?url=...} — install the modpack at the given manifest URL if it
     *  isn't already, then launch it. This is what Discord's "Join Game" button delivers
     *  via DiscordRpcUtility's onActivityJoin callback when a friend clicks to join a
     *  running session. Idempotent on the install step — if the friend already has the
     *  pack, install is a no-op and we go straight to play.
     *
     *  Vanilla flavor: {@code mmcl://join?vanilla=&lt;version-id&gt;} — install the Mojang
     *  vanilla version if needed, then launch it. Used for invite links built from
     *  vanilla-version packs in the main-menu context menu. */
    private static void handleJoin( String url, String vanillaVersionId )
    {
        if ( vanillaVersionId != null && !vanillaVersionId.isBlank() ) {
            handleJoinVanilla( vanillaVersionId );
            return;
        }
        if ( url == null || url.isBlank() ) {
            Logger.logWarningSilent( "mmcl://join missing required url or vanilla parameter" );
            return;
        }
        SystemUtilities.spawnNewTask( () -> {
            // If the pack is already installed at this URL, skip straight to play. Otherwise
            // install first, then play. installModPackByURL is itself idempotent — calling it
            // on an already-installed URL is a no-op.
            GameModPack existing = GameModPackManager.getInstalledModPackByURL( url );
            if ( existing != null ) {
                LauncherCore.play( existing );
                return;
            }

            if ( !confirmInstallUrl( url, "Join your friend's modpack from this site?" ) ) {
                return;
            }
            try {
                GameModPackManager.installModPackByURL( url );
                NotificationManager.success( "Joining via Discord",
                                             "Installed the modpack — starting it now." );
            }
            catch ( Exception e ) {
                Logger.logError( "Failed to install modpack via mmcl://join — " + e.getMessage() );
                Logger.logThrowable( e );
                NotificationManager.error( "Couldn't join",
                                           "Tried to install the friend's modpack but it failed. See log." );
                return;
            }

            // After install, the manager's installed-pack list should now contain the URL.
            GameModPack installed = GameModPackManager.getInstalledModPackByURL( url );
            if ( installed != null ) {
                LauncherCore.play( installed );
            }
            else {
                Logger.logErrorSilent( "mmcl://join: install reported success but pack not found in installed list." );
            }
        } );
    }

    /** Handles {@code mmcl://join?vanilla=&lt;version-id&gt;} — installs the Mojang vanilla
     *  version if not already present, then launches it. */
    private static void handleJoinVanilla( String versionId )
    {
        SystemUtilities.spawnNewTask( () -> {
            try {
                if ( !com.micatechnologies.minecraft.launcher.game.modpack.VanillaVersionManager
                        .isInstalled( versionId ) ) {
                    com.micatechnologies.minecraft.launcher.game.modpack.VanillaVersionManager
                            .installVersion( versionId );
                    NotificationManager.success( "Joining via Discord",
                                                 "Installed Minecraft " + versionId + " — starting it now." );
                }
                GameModPack vanilla = GameModPack.createVanillaModPack( versionId );
                LauncherCore.play( vanilla );
            }
            catch ( Exception e ) {
                Logger.logError( "Failed to join vanilla via mmcl://join?vanilla=" + versionId + " — "
                                         + e.getMessage() );
                Logger.logThrowable( e );
                NotificationManager.error( "Couldn't join",
                                           "Tried to install Minecraft " + versionId
                                                   + " but it failed. See log." );
            }
        } );
    }

    /** {@code mmcl://open} — bring the launcher to focus. Useful as a "click to surface me"
     *  link from elsewhere in the user's workflow. */
    private static void handleOpen()
    {
        GUIUtilities.JFXPlatformRun( MCLauncherGuiController::requestFocus );
    }

    // =========================================================================================
    //  Helpers
    // =========================================================================================

    /**
     * Validates an install URL coming in over the {@code mmcl://} channel and (if the
     * host is untrusted) prompts the user. Returns {@code true} only if the URL is safe
     * to hand to {@link GameModPackManager#installModPackByURL} — security finding 1.6.
     *
     * <p>Threading: this method blocks the calling background task until the user
     * answers the prompt. The dialog itself runs on the FX thread via
     * {@link GUIUtilities#showQuestionMessage}. Callers must therefore be invoked
     * from a worker thread (which both {@code handleAdd} and {@code handleJoin} already
     * are via {@code SystemUtilities.spawnNewTask}).
     */
    private static boolean confirmInstallUrl( String url, String headerText )
    {
        InstallUrlVerdict verdict = classifyInstallUrl( url );
        switch ( verdict ) {
            case ACCEPT_TRUSTED:
                return true;
            case REJECT:
                Logger.logWarningSilent( "Refusing mmcl:// install URL: " + url );
                NotificationManager.error( "Unsafe link",
                                           "The link's target couldn't be verified, so the launcher refused it. "
                                                   + "Only HTTPS modpack URLs are accepted." );
                return false;
            case REQUIRE_CONFIRMATION:
            default:
                String host = hostOf( url );
                int answer = GUIUtilities.showQuestionMessage(
                        "Confirm modpack source",
                        headerText,
                        "This link asks the launcher to install a modpack from:\n\n"
                                + "    " + host + "\n\n"
                                + "This isn't one of the official Mica modpack hosts. Only continue if you "
                                + "explicitly trust the site — installing a malicious modpack can run code on "
                                + "your computer.",
                        "Install",
                        "Cancel",
                        MCLauncherGuiController.getTopStageOrNull() );
                // showQuestionMessage returns 1 for the first button (Install), 2 for the
                // second, 0 for Cancel / dismiss. Anything other than 1 = refuse.
                if ( answer != 1 ) {
                    Logger.logStd( "User declined untrusted mmcl:// install for " + host );
                    return false;
                }
                return true;
        }
    }

    /**
     * Pure-function URL classifier used by {@link #confirmInstallUrl(String, String)}.
     * Decoupled so future tests / additional entry points can reuse it.
     */
    private static InstallUrlVerdict classifyInstallUrl( String url )
    {
        if ( url == null || url.isBlank() ) {
            return InstallUrlVerdict.REJECT;
        }
        // Reject control chars — protects against header / multi-line smuggling tricks
        // that could survive a poorly-validated downstream consumer.
        for ( int i = 0; i < url.length(); i++ ) {
            char c = url.charAt( i );
            if ( c < 0x20 || c == 0x7F ) {
                return InstallUrlVerdict.REJECT;
            }
        }
        URI parsed;
        try {
            parsed = URI.create( url );
        }
        catch ( Exception e ) {
            return InstallUrlVerdict.REJECT;
        }
        String scheme = parsed.getScheme();
        if ( scheme == null || !scheme.equalsIgnoreCase( "https" ) ) {
            return InstallUrlVerdict.REJECT;
        }
        String host = parsed.getHost();
        if ( host == null || host.isBlank() ) {
            return InstallUrlVerdict.REJECT;
        }
        if ( TRUSTED_INSTALL_HOSTS.contains( host.toLowerCase( Locale.ROOT ) ) ) {
            return InstallUrlVerdict.ACCEPT_TRUSTED;
        }
        return InstallUrlVerdict.REQUIRE_CONFIRMATION;
    }

    /** Returns the host of a URL string for display in the confirmation prompt, falling
     *  back to the raw URL on parse failure. */
    private static String hostOf( String url )
    {
        try {
            URI parsed = URI.create( url );
            String h = parsed.getHost();
            return h != null && !h.isBlank() ? h : url;
        }
        catch ( Exception e ) {
            return url;
        }
    }

    /** Decodes a {@code key1=value1&key2=value2} query string into a map. Both keys and
     *  values are URL-decoded. Empty / null query → empty map. */
    private static Map< String, String > parseQuery( String query )
    {
        Map< String, String > out = new HashMap<>();
        if ( query == null || query.isEmpty() ) {
            return out;
        }
        for ( String part : query.split( "&" ) ) {
            if ( part.isEmpty() ) {
                continue;
            }
            int eq = part.indexOf( '=' );
            String rawKey   = eq < 0 ? part : part.substring( 0, eq );
            String rawValue = eq < 0 ? ""   : part.substring( eq + 1 );
            try {
                String key   = URLDecoder.decode( rawKey,   StandardCharsets.UTF_8 );
                String value = URLDecoder.decode( rawValue, StandardCharsets.UTF_8 );
                out.put( key, value );
            }
            catch ( Exception ignored ) {
                // Skip malformed pair; don't fail the whole parse.
            }
        }
        return out;
    }
}
