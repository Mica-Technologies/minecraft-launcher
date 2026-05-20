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

import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
import com.micatechnologies.minecraft.launcher.config.ConfigManager;
import com.micatechnologies.minecraft.launcher.consts.ModPackConstants;
import com.micatechnologies.minecraft.launcher.exceptions.ModpackException;
import com.micatechnologies.minecraft.launcher.files.LocalPathManager;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.utilities.JSONUtilities;
import org.apache.commons.lang3.SystemUtils;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.Base64;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;

/**
 * Publishes a Mica modpack (or vanilla version) as a profile in the
 * official Mojang launcher's {@code launcher_profiles.json}, so the
 * user can launch the same pack from either launcher.
 *
 * <h3>What this does</h3>
 *
 * <ol>
 *   <li>Computes the export {@code gameDir} under the Mica launcher's
 *       own data folder ({@code <launcher-data>/official-launcher-exports/<pack-name>/})
 *       so the export footprint stays self-contained and Mica-cleanable.</li>
 *   <li>Copies the pack's {@code mods/} and {@code config/} directories
 *       into that gameDir. Vanilla packs skip the copy entirely — there's
 *       nothing to ship.</li>
 *   <li>Computes the Mojang-launcher version ID for the pack's loader
 *       ({@code 1.20.1-forge-47.3.0}, {@code neoforge-21.1.95},
 *       {@code fabric-loader-0.16.5-1.21.1}, or the raw MC version for
 *       vanilla).</li>
 *   <li>Detects whether the matching version directory exists under
 *       {@code .minecraft/versions/<id>/} so the caller can warn the
 *       user "you need to install Forge first" before the profile shows
 *       up as a red "Version not found" entry in the Mojang UI.</li>
 *   <li>Reads {@code launcher_profiles.json}, merges in (or replaces)
 *       the Mica-owned profile keyed on a UUID derived from the pack's
 *       manifest URL, and writes it back atomically (temp + fsync +
 *       ATOMIC_MOVE).</li>
 * </ol>
 *
 * <p>Re-export refreshes in place: the profile UUID is stable across
 * runs because it's derived from the pack URL, so pushing a Mica pack
 * update to the Mojang side is a single click.</p>
 *
 * <p>See {@code docs/agent-progress-plans/OFFICIAL_LAUNCHER_EXPORT.md}
 * for the full design, including Phase 4 plans to also auto-install
 * the loader installer when the version directory is missing.</p>
 *
 * @since 2026.5
 */
public final class OfficialLauncherExporter
{
    /** UUIDv5-equivalent namespace for stable per-pack profile IDs. The
     *  Mica DNS namespace + the pack's manifest URL (or vanilla version
     *  id) produces a deterministic UUID so re-exports update the same
     *  profile rather than accumulating duplicates. */
    private static final UUID MICA_NAMESPACE = UUID.fromString( "7b21f6a4-3b4a-4c0d-9c2e-1de9b1c8a420" );

    private OfficialLauncherExporter() { /* static-only */ }

    /** Outcome of an export attempt. Carries enough state for the GUI
     *  to render either the success toast or the "you need to install
     *  the loader first" follow-up dialog. */
    public record Result(
            boolean success,
            String profileName,
            String gameDir,
            String versionId,
            boolean loaderInstalled,
            String loaderInstallerUrl,
            String errorMessage
    ) {
        public static Result success( String name, String dir, String versionId,
                                       boolean loaderInstalled, String installerUrl )
        {
            return new Result( true, name, dir, versionId, loaderInstalled, installerUrl, null );
        }
        public static Result failure( String message ) {
            return new Result( false, null, null, null, false, null, message );
        }
    }

    /**
     * Resolves the Mojang launcher's data folder per platform.
     * Windows: {@code %APPDATA%\.minecraft}. macOS:
     * {@code ~/Library/Application Support/minecraft}. Linux:
     * {@code ~/.minecraft}.
     */
    public static Path resolveDotMinecraft()
    {
        String home = System.getProperty( "user.home", "." );
        if ( SystemUtils.IS_OS_WINDOWS ) {
            String appdata = System.getenv( "APPDATA" );
            if ( appdata != null && !appdata.isBlank() ) {
                return Paths.get( appdata, ".minecraft" );
            }
            return Paths.get( home, "AppData", "Roaming", ".minecraft" );
        }
        if ( SystemUtils.IS_OS_MAC ) {
            return Paths.get( home, "Library", "Application Support", "minecraft" );
        }
        // Linux + everything else falls back to ~/.minecraft, which is
        // the convention every third-party launcher already uses.
        return Paths.get( home, ".minecraft" );
    }

    /** Returns {@code true} when the Mojang launcher data folder + the
     *  {@code launcher_profiles.json} file are both present. Used as a
     *  precondition check before kicking off an export. */
    public static boolean isOfficialLauncherAvailable()
    {
        Path dotMc = resolveDotMinecraft();
        if ( !Files.isDirectory( dotMc ) ) return false;
        return Files.isRegularFile( dotMc.resolve( "launcher_profiles.json" ) );
    }

    /**
     * Performs the export. Caller is expected to have shown a
     * confirmation dialog already; this method just runs the work.
     * Safe to call from any thread — does no UI work.
     *
     * @param pack the pack (or vanilla version) to export
     * @return a {@link Result} describing what happened
     */
    public static Result exportPack( GameModPack pack )
    {
        if ( pack == null ) {
            return Result.failure( "No pack supplied." );
        }
        Path dotMc = resolveDotMinecraft();
        Path profilesFile = dotMc.resolve( "launcher_profiles.json" );
        if ( !Files.isRegularFile( profilesFile ) ) {
            return Result.failure(
                    "The Minecraft Launcher data folder wasn't found at "
                            + dotMc + ". Install the Minecraft Launcher (and run it once) "
                            + "before exporting." );
        }

        // Resolve version ID + loader install state up front so the
        // caller can surface the warning even if the copy / write
        // happens successfully.
        String versionId;
        boolean loaderInstalled;
        String installerUrl;
        try {
            versionId = computeVersionId( pack );
            loaderInstalled = isVersionInstalled( dotMc, versionId );
            installerUrl = pack.isVanillaVersion() ? null : safeGetLoaderInstallerUrl( pack );
        }
        catch ( Exception e ) {
            return Result.failure( "Could not resolve loader version for this pack: "
                                           + e.getMessage() );
        }

        // Compute + create the export gameDir, then copy mods + configs
        // (modpacks only — vanilla profiles run out of the Mojang
        // launcher's own gameDir).
        Path gameDir;
        try {
            gameDir = computeExportGameDir( pack );
            Files.createDirectories( gameDir );
            if ( !pack.isVanillaVersion() ) {
                copyPackContentsToExport( pack, gameDir );
            }
        }
        catch ( Exception e ) {
            Logger.logThrowable( e );
            return Result.failure( "Couldn't prepare the export directory: " + e.getMessage() );
        }

        // Read existing profiles, merge our entry, write back atomically.
        try {
            JsonObject profilesJson = readProfilesJson( profilesFile );
            JsonObject profiles = profilesJson.has( "profiles" )
                    && profilesJson.get( "profiles" ).isJsonObject()
                    ? profilesJson.getAsJsonObject( "profiles" )
                    : new JsonObject();

            String profileKey = stableProfileKey( pack );
            JsonObject profile = profiles.has( profileKey )
                    && profiles.get( profileKey ).isJsonObject()
                    ? profiles.getAsJsonObject( profileKey )
                    : new JsonObject();

            String profileName = profileDisplayName( pack );
            populateProfileEntry( profile, pack, gameDir, versionId, profileName );

            profiles.add( profileKey, profile );
            profilesJson.add( "profiles", profiles );

            atomicWriteProfilesJson( profilesFile, profilesJson );
            Logger.logStd( "OfficialLauncherExporter: wrote profile \""
                                   + profileName + "\" to " + profilesFile );
            return Result.success( profileName, gameDir.toString(), versionId,
                                    loaderInstalled, installerUrl );
        }
        catch ( Exception e ) {
            Logger.logThrowable( e );
            return Result.failure( "Couldn't update launcher_profiles.json: " + e.getMessage() );
        }
    }

    /**
     * Reverse of {@link #exportPack} — removes the Mica-owned profile
     * from {@code launcher_profiles.json} and deletes the export gameDir.
     * Idempotent: missing profile / missing gameDir count as success
     * (the desired end-state is "no Mica profile exists for this pack",
     * which is true either way).
     *
     * <p>Phase 5 completion of the lifecycle that Phase 1-3 started:
     * lets users back out of an export cleanly instead of having to
     * hand-edit launcher_profiles.json. Surfaced from the right-click
     * menu + detail modal only when a Mica profile for the pack exists,
     * so users don't see a no-op button on un-exported packs.</p>
     */
    public static Result removeExport( GameModPack pack )
    {
        if ( pack == null ) {
            return Result.failure( "No pack supplied." );
        }
        Path dotMc = resolveDotMinecraft();
        Path profilesFile = dotMc.resolve( "launcher_profiles.json" );
        if ( !Files.isRegularFile( profilesFile ) ) {
            // Mojang launcher isn't around — there's nothing to remove,
            // but we still try to clean up the gameDir below.
            Logger.logStd( "OfficialLauncherExporter.removeExport: launcher_profiles.json missing — "
                                   + "skipping profile removal." );
        }
        else {
            try {
                JsonObject profilesJson = readProfilesJson( profilesFile );
                if ( profilesJson.has( "profiles" )
                        && profilesJson.get( "profiles" ).isJsonObject() ) {
                    JsonObject profiles = profilesJson.getAsJsonObject( "profiles" );
                    String key = stableProfileKey( pack );
                    if ( profiles.has( key ) ) {
                        profiles.remove( key );
                        atomicWriteProfilesJson( profilesFile, profilesJson );
                        Logger.logStd( "OfficialLauncherExporter.removeExport: removed profile "
                                               + key );
                    }
                    else {
                        Logger.logDebug( "OfficialLauncherExporter.removeExport: no profile "
                                                 + "with key " + key + " — already removed." );
                    }
                }
            }
            catch ( Exception e ) {
                Logger.logThrowable( e );
                return Result.failure( "Couldn't update launcher_profiles.json: " + e.getMessage() );
            }
        }

        // Delete the export gameDir last so a failure to update the
        // profile doesn't orphan the data. Best-effort: failure to
        // delete files (e.g. a Mojang launcher mid-launch holding a
        // lock on a mod) is logged but not treated as a removal failure
        // because the profile-side cleanup already succeeded.
        try {
            Path gameDir = computeExportGameDir( pack );
            if ( Files.isDirectory( gameDir ) ) {
                deleteRecursively( gameDir );
                Logger.logStd( "OfficialLauncherExporter.removeExport: deleted " + gameDir );
            }
        }
        catch ( Exception e ) {
            Logger.logWarningSilent( "Couldn't fully delete export gameDir: " + e.getMessage() );
        }
        return Result.success(
                profileDisplayName( pack ),
                computeExportGameDir( pack ).toString(),
                null, true, null );
    }

    /** Returns {@code true} when a Mica-owned profile for this pack
     *  exists in launcher_profiles.json. Used by the UI to decide
     *  whether to surface the Remove action. */
    public static boolean hasExportedProfile( GameModPack pack )
    {
        if ( pack == null ) return false;
        Path profilesFile = resolveDotMinecraft().resolve( "launcher_profiles.json" );
        if ( !Files.isRegularFile( profilesFile ) ) return false;
        try {
            JsonObject profilesJson = readProfilesJson( profilesFile );
            if ( !profilesJson.has( "profiles" )
                    || !profilesJson.get( "profiles" ).isJsonObject() ) {
                return false;
            }
            return profilesJson.getAsJsonObject( "profiles" )
                    .has( stableProfileKey( pack ) );
        }
        catch ( Exception e ) {
            Logger.logWarningSilent( "Couldn't read launcher_profiles.json to check for export: "
                                             + e.getMessage() );
            return false;
        }
    }

    // ====================================================================
    // Version-ID derivation
    // ====================================================================

    /** Computes the Mojang-launcher version ID for the pack. Format
     *  depends on loader — see the plan doc's "Version ID format per
     *  loader" section. Vanilla packs return their raw MC version.
     *
     *  <p>Public so the GUI confirmation flow can pre-flight the value
     *  before showing the dialog (and warn the user about a missing
     *  loader version directory without doing the full copy first).</p> */
    public static String computeVersionId( GameModPack pack ) throws Exception
    {
        if ( pack.isVanillaVersion() ) {
            String mc = pack.getMinecraftVersion();
            if ( mc == null || mc.isBlank() ) {
                throw new ModpackException( "Vanilla pack has no MC version." );
            }
            return mc;
        }
        String loaderType = pack.getModLoaderType();
        String mcVersion = pack.getMinecraftVersion();
        String loaderVersion = pack.getLoaderVersion();
        if ( loaderType == null ) {
            // Defensive — getModLoaderType returns null only for vanilla,
            // which we handled above. If we somehow land here, fall back
            // to the raw MC version.
            return mcVersion;
        }
        return switch ( loaderType ) {
            case ModPackConstants.MOD_LOADER_FORGE ->
                    mcVersion + "-forge-" + loaderVersion;
            case ModPackConstants.MOD_LOADER_NEOFORGE ->
                    "neoforge-" + loaderVersion;
            case ModPackConstants.MOD_LOADER_FABRIC ->
                    "fabric-loader-" + loaderVersion + "-" + mcVersion;
            default ->
                    // Unknown loader — best-effort fallback. The Mojang
                    // launcher will surface a "Version not found" until
                    // the user installs the matching version directory.
                    mcVersion + "-" + loaderType + "-" + loaderVersion;
        };
    }

    /** Returns {@code true} when {@code .minecraft/versions/<id>/<id>.json}
     *  exists — the Mojang launcher's "this version is installed" marker.
     *  Public so the confirmation dialog can drive the loader-missing
     *  warning without invoking the full export pipeline first. */
    public static boolean isVersionInstalled( Path dotMc, String versionId )
    {
        if ( versionId == null || versionId.isBlank() ) return false;
        Path versionJson = dotMc.resolve( "versions" ).resolve( versionId ).resolve( versionId + ".json" );
        return Files.isRegularFile( versionJson );
    }

    /** Best-effort fetch of the loader installer URL from the manifest.
     *  Surfaced to the caller so the warning dialog can offer a direct
     *  link instead of a generic "install Forge first" message. */
    private static String safeGetLoaderInstallerUrl( GameModPack pack )
    {
        try {
            return pack.getModLoaderURL();
        }
        catch ( Exception e ) {
            return null;
        }
    }

    // ====================================================================
    // gameDir / file copy
    // ====================================================================

    /** Where the per-pack export gameDir lives. Public so the
     *  Phase 5 remove flow can surface the path in its confirmation
     *  dialog (so users know exactly what gets deleted). */
    public static Path computeExportGameDir( GameModPack pack )
    {
        String launcherLocal = LocalPathManager.getLauncherLocalPath();
        Path base = Paths.get( launcherLocal, "official-launcher-exports" );
        return base.resolve( sanitizeFolderName( profileDisplayName( pack ) ) );
    }

    /** Strips characters Windows / macOS / Linux disallow in folder names
     *  and trims to a reasonable length. Result is never empty — falls
     *  back to {@code "modpack"}. */
    static String sanitizeFolderName( String name )
    {
        if ( name == null || name.isBlank() ) return "modpack";
        StringBuilder sb = new StringBuilder( name.length() );
        for ( int i = 0; i < name.length(); i++ ) {
            char c = name.charAt( i );
            if ( c < 0x20 || c == 0x7F ) continue;
            if ( "<>:\"/\\|?*".indexOf( c ) >= 0 ) {
                sb.append( '_' );
            }
            else {
                sb.append( c );
            }
        }
        String cleaned = sb.toString().trim();
        if ( cleaned.length() > 80 ) cleaned = cleaned.substring( 0, 80 ).trim();
        return cleaned.isEmpty() ? "modpack" : cleaned;
    }

    /** Copies the pack's {@code mods/} + {@code config/} into the
     *  export gameDir. Replaces existing content (re-export semantics
     *  per plan: refresh in place). */
    private static void copyPackContentsToExport( GameModPack pack, Path gameDir ) throws IOException
    {
        String packRoot = pack.getPackRootFolder();
        if ( packRoot == null ) {
            throw new IOException( "Pack has no install folder; install it before exporting." );
        }
        Path packPath = Paths.get( packRoot );
        copySubfolderIfPresent( packPath, gameDir, "mods" );
        copySubfolderIfPresent( packPath, gameDir, "config" );
    }

    /** Recursively copies a subfolder, replacing the target if it exists.
     *  Silent no-op when the source doesn't exist (some packs ship no
     *  config/ directory). */
    private static void copySubfolderIfPresent( Path packRoot, Path gameDir, String name ) throws IOException
    {
        Path src = packRoot.resolve( name );
        Path dst = gameDir.resolve( name );
        if ( !Files.isDirectory( src ) ) {
            return;
        }
        // Wipe the destination first so removed mods are reflected in
        // the re-export. The Mojang side shouldn't see ghost mods from
        // a previous Mica pack version.
        if ( Files.exists( dst ) ) {
            deleteRecursively( dst );
        }
        Files.createDirectories( dst );
        copyRecursively( src, dst );
    }

    private static void copyRecursively( Path src, Path dst ) throws IOException
    {
        try ( var stream = Files.walk( src ) ) {
            stream.forEach( path -> {
                try {
                    Path target = dst.resolve( src.relativize( path ).toString() );
                    if ( Files.isDirectory( path ) ) {
                        Files.createDirectories( target );
                    }
                    else {
                        Files.copy( path, target, StandardCopyOption.REPLACE_EXISTING );
                    }
                }
                catch ( IOException e ) {
                    throw new RuntimeException( e );
                }
            } );
        }
        catch ( RuntimeException re ) {
            if ( re.getCause() instanceof IOException ioe ) throw ioe;
            throw re;
        }
    }

    private static void deleteRecursively( Path dir ) throws IOException
    {
        try ( var stream = Files.walk( dir ) ) {
            stream.sorted( ( a, b ) -> b.getNameCount() - a.getNameCount() )
                  .forEach( path -> {
                      try { Files.deleteIfExists( path ); }
                      catch ( IOException e ) {
                          throw new RuntimeException( e );
                      }
                  } );
        }
        catch ( RuntimeException re ) {
            if ( re.getCause() instanceof IOException ioe ) throw ioe;
            throw re;
        }
    }

    // ====================================================================
    // Profile entry construction
    // ====================================================================

    static String profileDisplayName( GameModPack pack )
    {
        String base = pack.getPackName();
        if ( base == null || base.isBlank() ) {
            base = pack.isVanillaVersion() ? "Minecraft" : "Modpack";
        }
        // Tag with " (Mica)" so users can tell Mica-owned profiles apart
        // from any vanilla / loader-installer-created ones in the Mojang UI.
        return base + " (Mica)";
    }

    /** Deterministic UUID per pack. Two packs with different manifest
     *  URLs / vanilla IDs produce different keys; the same pack across
     *  re-exports produces the same key (so the profile updates in
     *  place rather than duplicating). */
    static String stableProfileKey( GameModPack pack )
    {
        String identity;
        if ( pack.isVanillaVersion() ) {
            identity = "mica-vanilla:" + safeVanillaVersionId( pack );
        }
        else {
            String url = pack.getPackURL();
            identity = "mica-modpack:" + ( url == null ? pack.getPackName() : url );
        }
        // UUIDv5-style: name-based using the MICA_NAMESPACE. Java's
        // UUID.nameUUIDFromBytes is technically UUIDv3 (MD5) but the
        // determinism property we need is identical.
        byte[] nameBytes = ( MICA_NAMESPACE.toString() + ":" + identity )
                .getBytes( StandardCharsets.UTF_8 );
        return UUID.nameUUIDFromBytes( nameBytes ).toString();
    }

    private static String safeVanillaVersionId( GameModPack pack )
    {
        try {
            return pack.getMinecraftVersion();
        }
        catch ( Exception e ) {
            return pack.getPackName();
        }
    }

    /** Fills in / refreshes the profile JSON object's fields. The
     *  caller has already located the profile by stable key — this
     *  method is responsible for the per-field merge: preserves
     *  user-customised values where appropriate (lastUsed) and
     *  overwrites where Mica is authoritative (gameDir, lastVersionId,
     *  javaArgs, icon). */
    private static void populateProfileEntry( JsonObject profile, GameModPack pack, Path gameDir,
                                                String versionId, String profileName )
    {
        String nowIso = isoNow();

        profile.addProperty( "name", profileName );
        profile.addProperty( "type", "custom" );
        profile.addProperty( "lastVersionId", versionId );
        profile.addProperty( "gameDir", gameDir.toAbsolutePath().toString() );

        // Created timestamp is set only if missing — re-exports preserve
        // the original creation date.
        if ( !profile.has( "created" ) || profile.get( "created" ).isJsonNull() ) {
            profile.addProperty( "created", nowIso );
        }
        // lastUsed: refresh on export so the profile floats to the top
        // of the Mojang launcher's recent-profiles list.
        profile.addProperty( "lastUsed", nowIso );

        // Java args: Mica's user-configured JVM args, plus an -Xmx
        // derived from getMaxRam (Mojang launcher uses --memory but
        // also honours -Xmx in javaArgs).
        String customArgs = ConfigManager.getCustomJvmArgs();
        long maxRamMb = ConfigManager.getMaxRam();
        StringBuilder args = new StringBuilder();
        args.append( "-Xmx" ).append( maxRamMb ).append( "M" );
        if ( customArgs != null && !customArgs.isBlank() ) {
            args.append( ' ' ).append( customArgs.trim() );
        }
        profile.addProperty( "javaArgs", args.toString() );

        // Icon: base64-encode the pack logo at 64×64 if we have one.
        // Mojang clips bigger icons internally; pre-resizing keeps the
        // profile JSON small.
        String iconDataUri = encodePackLogoAsIcon( pack );
        if ( iconDataUri != null ) {
            profile.addProperty( "icon", iconDataUri );
        }
    }

    private static String isoNow()
    {
        SimpleDateFormat sdf = new SimpleDateFormat( "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT );
        sdf.setTimeZone( TimeZone.getTimeZone( "UTC" ) );
        return sdf.format( new Date() );
    }

    private static String encodePackLogoAsIcon( GameModPack pack )
    {
        try {
            // Pack logo lives in metadata-folder/<pack>/logo.png typically.
            // GameModPackEnvironment exposes the resolved local path.
            String installFolder = pack.getPackRootFolder();
            if ( installFolder == null ) return null;
            Path logo = Paths.get( installFolder, "logo.png" );
            if ( !Files.isRegularFile( logo ) ) return null;
            BufferedImage src = ImageIO.read( logo.toFile() );
            if ( src == null ) return null;
            BufferedImage resized = new BufferedImage( 64, 64, BufferedImage.TYPE_INT_ARGB );
            Graphics2D g = resized.createGraphics();
            g.setRenderingHint( RenderingHints.KEY_INTERPOLATION,
                                RenderingHints.VALUE_INTERPOLATION_BICUBIC );
            g.drawImage( src, 0, 0, 64, 64, null );
            g.dispose();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write( resized, "PNG", bos );
            return "data:image/png;base64,"
                    + Base64.getEncoder().encodeToString( bos.toByteArray() );
        }
        catch ( Exception e ) {
            Logger.logWarningSilent( "Couldn't encode pack icon for export: " + e.getMessage() );
            return null;
        }
    }

    // ====================================================================
    // launcher_profiles.json IO — atomic write mirrors ConfigStore's
    // discipline so a crash mid-export can't trash the Mojang launcher's
    // own state.
    // ====================================================================

    private static JsonObject readProfilesJson( Path file ) throws IOException
    {
        String text = Files.readString( file, StandardCharsets.UTF_8 );
        JsonObject obj = JSONUtilities.stringToObject( text );
        return obj != null ? obj : new JsonObject();
    }

    private static void atomicWriteProfilesJson( Path target, JsonObject json ) throws IOException
    {
        // Preserve the user's existing keys we don't touch (settings,
        // authenticationDatabase, etc.) by re-serialising the merged object
        // through pretty-printed Gson.
        String payload = new com.google.gson.GsonBuilder()
                .setPrettyPrinting()
                .disableHtmlEscaping()
                .create()
                .toJson( json );
        byte[] bytes = payload.getBytes( StandardCharsets.UTF_8 );

        Path tmp = target.resolveSibling( target.getFileName() + ".tmp" );
        try ( FileChannel ch = FileChannel.open( tmp,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE ) ) {
            ByteBuffer buf = ByteBuffer.wrap( bytes );
            while ( buf.hasRemaining() ) ch.write( buf );
            ch.force( true );
        }
        try {
            Files.move( tmp, target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING );
        }
        catch ( AtomicMoveNotSupportedException atomicEx ) {
            Logger.logWarningSilent( "Atomic move not supported for launcher_profiles.json — "
                                             + "falling back to non-atomic replace." );
            Files.move( tmp, target, StandardCopyOption.REPLACE_EXISTING );
        }
    }
}
