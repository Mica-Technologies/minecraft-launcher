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

package com.micatechnologies.minecraft.launcher.game.modpack;

import com.google.gson.JsonObject;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;

import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
import com.micatechnologies.minecraft.launcher.exceptions.ModpackException;
import com.micatechnologies.minecraft.launcher.files.LocalPathManager;
import com.micatechnologies.minecraft.launcher.files.SynchronizedFileManager;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.utilities.DownloadTracker;
import com.micatechnologies.minecraft.launcher.utilities.FileUtilities;
import com.micatechnologies.minecraft.launcher.utilities.HashUtilities;
import com.micatechnologies.minecraft.launcher.utilities.NetworkUtilities;

/**
 * A Java class representation of a remote file that should be kept locally in sync.
 *
 * @author Mica Technologies
 * @version 2.1
 * @since 1.0
 */
public class ManagedGameFile
{

    /**
     * Launcher-wide verify mode applied to every {@link ManagedGameFile} during
     * the current pre-launch run. {@link LaunchVerifyMode#FULL} (the default)
     * runs the historical hash-everything path; {@link LaunchVerifyMode#FAST_PATH}
     * accepts each file on existence + non-zero size alone.
     *
     * <p>Static because passing the mode through every {@code fetchLatest*} /
     * {@code buildXClasspath} call site would balloon the API for what's
     * effectively a one-launch-at-a-time setting. The launcher serializes
     * launches at the {@code LauncherCore.play()} boundary, so two concurrent
     * launches can't race on this. Parallel branches inside a single launch
     * (see 3.2's CompletableFuture fan-out) all read the same value cleanly.</p>
     *
     * <p>Set at the top of {@code GameModPackLauncher.buildClasspath} and
     * cleared back to FULL in the finally so a subsequent launch starts from
     * a known state regardless of how the previous one ended.</p>
     *
     * @since 2026.3
     */
    private static volatile LaunchVerifyMode currentVerifyMode = LaunchVerifyMode.FULL;

    /** Reads the current launcher-wide verify mode. */
    public static LaunchVerifyMode getCurrentVerifyMode() { return currentVerifyMode; }

    /** Sets the launcher-wide verify mode that subsequent verifyLocalFile
     *  calls will honor. The launch orchestrator owns this lifecycle. */
    public static void setCurrentVerifyMode( LaunchVerifyMode mode )
    {
        currentVerifyMode = ( mode != null ) ? mode : LaunchVerifyMode.FULL;
    }

    /**
     * The URL of the remote file
     *
     * @since 1.0
     */
    private final String remote;

    /**
     * The path of the local file
     *
     * @since 1.0
     */
    private final String local;

    /**
     * The SHA-1 hash of the file
     *
     * @since 1.0
     */
    private final String sha1;

    /**
     * The MD5 hash of the file
     *
     * @since 2.0
     */
    private final String md5;

    /**
     * The SHA-256 hash of the file
     *
     * @since 2.1
     */
    private final String sha256;

    /**
     * The prefix added to the local file path in {@link #local}.
     *
     * @since 1.0
     */
    private transient String localPathPrefix = "";

    /**
     * Whether this file has already been verified or downloaded during the current session. Once set to true,
     * subsequent calls to {@link #updateLocalFile()} will skip re-verification.
     *
     * @since 2.2
     */
    private transient boolean sessionVerified = false;

    /**
     * Optional download tracker for byte-level progress reporting. Set via {@link #setDownloadTracker(DownloadTracker)}
     * before calling {@link #updateLocalFile()}.
     *
     * @since 2.3
     */
    private transient DownloadTracker downloadTracker = null;

    /**
     * Create an {@link ManagedGameFile} object with hash checking disabled, using the specified remote URL and local
     * file path.
     *
     * @param remote remote file url
     * @param local  local file path
     *
     * @since 1.0
     */
    public ManagedGameFile( String remote, String local ) {
        String localTemp;
        try {
            localTemp = local.replaceAll( "/", File.separator );
        }
        catch ( Exception e ) {
            localTemp = local;
        }

        this.local = localTemp;
        this.remote = remote;

        this.sha1 = "-1";
        this.md5 = "-1";
        this.sha256 = "-1";
    }

    /**
     * Create an MCRemoteFile object with hash checking enabled, using the specified remote URL, local file path and
     * hash configuration.
     *
     * @param remote   remote file URL
     * @param local    local file path
     * @param hash     file hash
     * @param hashType file hash type
     *
     * @since 1.0
     */
    public ManagedGameFile( String remote, String local, String hash, ManagedGameFileHashType hashType ) {
        String localTemp;
        try {
            localTemp = local.replaceAll( "/", File.separator );
        }
        catch ( Exception e ) {
            localTemp = local;
        }

        this.local = localTemp;
        this.remote = remote;

        if ( hashType == ManagedGameFileHashType.SHA1 ) {
            this.sha1 = hash;
            this.md5 = "-1";
            this.sha256 = "-1";
        }
        else if ( hashType == ManagedGameFileHashType.MD5 ) {
            this.sha1 = "-1";
            this.md5 = hash;
            this.sha256 = "-1";
        }
        else if ( hashType == ManagedGameFileHashType.SHA256 ) {
            this.sha1 = "-1";
            this.md5 = "-1";
            this.sha256 = hash;
        }
        else {
            this.sha1 = "-1";
            this.md5 = "-1";
            this.sha256 = "-1";
        }
    }

    /**
     * Set the local file path prefix of this remote file
     *
     * @param localPathPrefix local file path prefix
     *
     * @since 1.0
     */
    public void setLocalPathPrefix( String localPathPrefix ) {
        this.localPathPrefix = localPathPrefix;
    }

    /**
     * Sets the download tracker for byte-level progress reporting during file downloads.
     *
     * @param tracker the download tracker, or null to disable tracking
     *
     * @since 2.3
     */
    public void setDownloadTracker( DownloadTracker tracker ) {
        this.downloadTracker = tracker;
    }

    /**
     * Verify the integrity of the local copy of this remote file
     *
     * @return true if local copy is valid
     *
     * @since 1.0
     */
    private boolean verifyLocalFile() {
        // Defense against malicious modpack JSON: the "local" field is fully
        // attacker-controllable (it's deserialized straight from the manifest),
        // so a manifest with "local": "../../something" would escape the modpack
        // folder when joined to localPathPrefix. Refuse to even stat the file
        // unless the resolved path is contained inside the prefix.
        if ( !isContainedUnderPrefix() ) {
            Logger.logError( "Refusing managed file outside modpack folder: "
                                     + getFullLocalFilePath() );
            return false;
        }
        File localFile = SynchronizedFileManager.getSynchronizedFile( getFullLocalFilePath() );

        // FAST_PATH bypass: when the launch orchestrator decided the pack is
        // unchanged since the last successful FULL verify (manifest content
        // hash matches + within TTL + no opt-out), accept files on existence
        // + non-zero size alone. Empty / missing files fall through to the
        // download path on the caller's next move, so a manually-deleted mod
        // still gets repaired — we're only skipping the SHA computation, not
        // the "fix what's broken" half of updateLocalFile.
        if ( currentVerifyMode == LaunchVerifyMode.FAST_PATH ) {
            return localFile.exists() && localFile.isFile() && localFile.length() > 0;
        }

        // FULL path: strongest-first hash ordering. The constructor stores
        // exactly one hash today (so only one branch matches per file), but
        // should the launcher ever evolve to carry multiple hashes per file
        // we want SHA-256 to win — SHA-1 is collision-broken (SHAttered, 2017)
        // and MD5 is broken for both collision and preimage. Until upstream
        // manifests publish SHA-256 widely, the launcher consumes whatever
        // the manifest provides.
        if ( hasUsableHash( this.sha256 ) ) {
            return HashUtilities.verifySHA256( localFile, sha256 );
        }
        if ( hasUsableHash( this.sha1 ) ) {
            return HashUtilities.verifySHA1( localFile, sha1 );
        }
        if ( hasUsableHash( this.md5 ) ) {
            return HashUtilities.verifyMD5( localFile, md5 );
        }
        // No hash declared — accept existence only.
        return localFile.exists() && localFile.isFile();
    }

    /**
     * Whether a hash field represents a real declared hash (vs. a "no hash"
     * sentinel). Treats null, the documented {@code "-1"} sentinel, and any
     * empty/whitespace string as equivalent — historically only {@code "-1"}
     * was recognized, but the editor and a few legacy manifest examples
     * persist {@code ""} for the same intent. Without this normalization,
     * {@code verifySHA1(file, "")} returns false on every launch and the
     * file gets re-downloaded for no benefit.
     */
    private static boolean hasUsableHash( String hash ) {
        return hash != null && !hash.isBlank() && !hash.equals( "-1" );
    }

    /**
     * Download a copy of the remote file to the configured local file path
     *
     * @throws ModpackException if unable to download file
     * @since 1.0
     */
    private void downloadLocalFile() throws ModpackException {
        // Same containment check as verifyLocalFile. Crucial here because this is the
        // write side — a malicious manifest could otherwise write attacker payloads
        // to arbitrary locations relative to the modpack folder.
        if ( !isContainedUnderPrefix() ) {
            throw new ModpackException(
                    "Refusing to download managed file outside modpack folder: "
                            + getFullLocalFilePath() );
        }
        File localFile = SynchronizedFileManager.getSynchronizedFile( getFullLocalFilePath() );

        // Download file and return validation result
        try {
            // Reject schemes that aren't network HTTPS. http:// allows MITM payload
            // swap on networks without TLS termination; file:// would let a malicious
            // manifest cause the launcher to copy arbitrary local files into the
            // modpack folder (where they'd later be loaded onto the classpath as
            // "mods"). ftp://, gopher://, etc. similarly have no legitimate
            // use case for mod downloads. Accept https plus the narrow jar:file:
            // case — the Forge installer extraction pipeline synthesizes URLs of
            // the form jar:file:/.../installs/<pack>/bin/modpack.jar!/maven/... to
            // pull embedded Maven artifacts out of an already-hash-verified Forge
            // installer JAR. Those URLs are constructed by trusted launcher code,
            // not the modpack JSON, and the inner file path is restricted to the
            // launcher's local data folder so a hostile manifest can't redirect it
            // at arbitrary locations on disk.
            URL parsed = new URL( remote );
            String scheme = parsed.getProtocol();
            boolean acceptHttps    = scheme != null && scheme.equalsIgnoreCase( "https" );
            boolean acceptTrustedJar = scheme != null && scheme.equalsIgnoreCase( "jar" )
                    && isJarUrlContainedInLauncher( remote );
            if ( !acceptHttps && !acceptTrustedJar ) {
                throw new ModpackException(
                        "Refusing managed file with non-https URL scheme: " + remote );
            }
            //noinspection ResultOfMethodCallIgnored
            localFile.getParentFile().mkdirs();
            if ( downloadTracker != null ) {
                NetworkUtilities.downloadFileFromURL( parsed, localFile, downloadTracker );
            }
            else {
                NetworkUtilities.downloadFileFromURL( parsed, localFile );
            }
        }
        catch ( IOException e ) {
            throw new ModpackException(
                    LocalizationManager.UNABLE_DOWNLOAD_FILE_LOCALLY_TO_TEXT + " " + getFullLocalFilePath(), e );
        }
    }

    /**
     * Check for and download any new update(s) to the local file copy.
     *
     * @return true if changed
     *
     * @throws ModpackException if file cannot verify or download
     * @since 1.0
     */
    public boolean updateLocalFile() throws ModpackException {
        if ( sessionVerified ) {
            return false;
        }
        // No remote URL means the file is local-only (e.g. a Technic
        // server-pack mod referenced by filename, or a loader installer
        // the user hasn't filled in yet). Skip the verify-then-download
        // cycle: there's nothing to download, and re-running the SHA
        // check on every pack-list reload spams the launcher log with
        // "FILE FAILED VERIFICATION" warnings + futile download attempts
        // that throw MalformedURLException on the empty URL. If the
        // file exists on disk, accept it as-is. If it's missing, throw
        // a clear "not configured" error instead of the cryptic empty-URL
        // failure mode.
        if ( remote == null || remote.isBlank() ) {
            File localFile = SynchronizedFileManager.getSynchronizedFile( getFullLocalFilePath() );
            if ( localFile.exists() && localFile.isFile() ) {
                sessionVerified = true;
                return false;
            }
            sessionVerified = true;
            throw new ModpackException(
                    "Required file is missing and no remote URL is configured for it: "
                            + getFullLocalFilePath()
                            + ". Open the modpack in the Modpack Editor to set the loader "
                            + "installer URL (or restore the missing file)." );
        }
        if ( !verifyLocalFile() ) {
            // Offline mode: refuse to launch with a hash-mismatched file. Previously
            // we'd accept any on-disk content as a courtesy ("better than nothing"),
            // but a mismatched file is by definition unverified — an attacker who
            // can drop a file on disk wins, and the user has no signal that what
            // they're about to run isn't what the manifest expects. Surface a clear
            // "needs reconnect to verify" error instead.
            if ( NetworkUtilities.isOffline() ) {
                File localFile = SynchronizedFileManager.getSynchronizedFile( getFullLocalFilePath() );
                if ( localFile.exists() && localFile.isFile() ) {
                    throw new ModpackException(
                            "Offline mode: file hash mismatch and we can't re-verify without internet: "
                                    + getFullLocalFilePath() );
                }
                throw new ModpackException( "Offline mode: missing required file: " + getFullLocalFilePath() );
            }
            Logger.logWarningSilent( "FILE FAILED VERIFICATION, RE-DOWNLOADING: " + getFullLocalFilePath() );
            downloadLocalFile();
            sessionVerified = true;
            return true;
        }
        sessionVerified = true;
        return false;
    }

    /**
     * Get the local file path of this file.
     *
     * @return local file path
     *
     * @since 1.0
     */
    public String getLocalFilePath() {
        return local;
    }

    /**
     * Get the file name of this file.
     *
     * @return file name
     *
     * @since 1.1
     */
    public String getFileName() {
        return SynchronizedFileManager.getSynchronizedFile( getFullLocalFilePath() ).getName();
    }

    /**
     * Get the full local file path of this file, including local file path prefix.
     *
     * @return full local file path
     *
     * @since 1.0
     */
    public String getFullLocalFilePath() {
        if ( !localPathPrefix.isEmpty() ) {
            if ( localPathPrefix.endsWith( File.separator ) ) {
                return localPathPrefix + local;
            }
            else {
                return localPathPrefix + File.separator + local;
            }
        }
        else {
            return local;
        }
    }

    /**
     * Returns true if the file's resolved absolute path lies under
     * {@link #localPathPrefix}. Used to defang a malicious modpack JSON whose
     * {@code local} field contains {@code ../} segments — without this gate, a
     * manifest could redirect any mod download to an arbitrary path on disk.
     *
     * <p>When no prefix has been set (legacy / pre-sync code paths), no
     * containment is enforceable, so the method returns {@code true} and the
     * caller falls back to its existing behavior. The realistic attack only
     * fires once the prefix is set, which is the mod/config/resource-pack
     * sync path that this method protects.
     */
    private boolean isContainedUnderPrefix() {
        if ( localPathPrefix == null || localPathPrefix.isEmpty() ) {
            return true;
        }
        try {
            Path prefix = Path.of( localPathPrefix ).toAbsolutePath().normalize();
            Path full = Path.of( getFullLocalFilePath() ).toAbsolutePath().normalize();
            return full.startsWith( prefix );
        }
        catch ( Exception e ) {
            return false;
        }
    }

    /**
     * Returns true when {@code remote} is a {@code jar:file:/<path>!/<entry>}
     * URL whose inner file path canonicalizes to a location under the
     * launcher's local data folder ({@link LocalPathManager#getLauncherLocalPath()}).
     *
     * <p>Lets the Forge installer extraction pipeline pull embedded Maven
     * artifacts (e.g. {@code maven/net/minecraftforge/forge/.../forge-X.jar})
     * out of a hash-verified {@code modpack.jar} living in
     * {@code installs/<pack>/bin/}. Rejects anything pointing outside the
     * launcher folder, anything with a non-{@code file:} inner URL
     * (e.g. {@code jar:http://attacker/x.jar!/...}), and anything we fail to
     * parse — defaults closed so a parser surprise can't widen the rule.</p>
     */
    // Package-private (not private) so the unit test in the same package
    // can exercise the gate directly. Inlined into downloadLocalFile via
    // a static call so behavior is identical regardless of visibility.
    static boolean isJarUrlContainedInLauncher( String remote ) {
        if ( remote == null || !remote.startsWith( "jar:" ) ) {
            return false;
        }
        int bang = remote.indexOf( "!/" );
        if ( bang < 0 ) {
            return false;
        }
        try {
            String innerSpec = remote.substring( "jar:".length(), bang );
            URL innerUrl = new URL( innerSpec );
            if ( !"file".equalsIgnoreCase( innerUrl.getProtocol() ) ) {
                return false;
            }
            Path innerPath = Path.of( innerUrl.toURI() ).toAbsolutePath().normalize();
            Path root = Path.of( LocalPathManager.getLauncherLocalPath() )
                            .toAbsolutePath().normalize();
            return innerPath.startsWith( root );
        }
        catch ( Exception e ) {
            return false;
        }
    }

    /**
     * Read this file into a JsonObject.
     *
     * @return JsonObject of this file
     *
     * @throws ModpackException if reading fails
     * @since 1.0
     */
    public JsonObject readToJsonObject() throws ModpackException {
        // Verify file is locally downloaded
        updateLocalFile();

        // Return file contents as JSON object
        File localFileObject = SynchronizedFileManager.getSynchronizedFile( getFullLocalFilePath() );
        try {
            return FileUtilities.readAsJsonObject( localFileObject );
        }
        catch ( IOException e ) {
            throw new ModpackException( LocalizationManager.UNABLE_READ_LOCAL_FILE_TO_JSON_EXCEPTION_TEXT, e );
        }
    }

    /**
     * The enum with values indicating the type of hash supplied to the {@link ManagedGameFile}.
     *
     * @since 2.0
     */
    public enum ManagedGameFileHashType
    {
        SHA1, MD5, SHA256
    }
}
