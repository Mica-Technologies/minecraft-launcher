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

import com.micatechnologies.minecraft.launcher.files.LocalPathManager;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.utilities.JSONUtilities;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Single-file persistent summary of every installed modpack — name, version,
 * logo + background image URLs/hashes, min RAM, and a few flags — keyed by
 * manifest URL. Lets the launcher paint the main menu's card grid from a single
 * file read at cold start instead of opening N per-manifest cache files.
 *
 * <p>On disk at {@code <launcher>/modpacks/install_index.json}, alongside the
 * existing per-manifest {@code manifest_cache/} folder. The two stores cohabit:
 * the per-manifest cache holds the full GSON-deserializable body (needed for
 * game launch and the detail modal), while this index holds only the subset
 * needed to render a card. After a successful manifest fetch the index entry
 * is upserted from the freshly-parsed pack so the two stay in sync.</p>
 *
 * <p><b>Scale rationale.</b> At today's 5–20 packs the win over per-manifest
 * file reads is modest (~100ms saved). The motivation is the CurseForge /
 * Modrinth import flow on the roadmap — a launcher with a few hundred or
 * thousand imported packs would otherwise pay one disk-seek per pack on every
 * cold start, which compounds visibly. With the index, cold-start "time to
 * first card" stays sub-50ms regardless of how many packs the user has
 * installed.</p>
 *
 * @since 2026.3
 */
public final class InstallIndex
{
    /** Schema version. Bumped when {@link Entry} gains a field that meaningfully
     *  changes how readers interpret existing entries. A reader seeing a higher
     *  version treats the file as untrusted (returns empty) and lets the
     *  per-manifest fetch path rebuild the index from scratch. */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    /** Filename of the index sidecar inside {@code modpacks/}. */
    private static final String INDEX_FILENAME = "install_index.json";

    public int version = CURRENT_SCHEMA_VERSION;

    /** Manifest URL → summary. LinkedHashMap preserves insertion / refresh order
     *  so the on-disk file lists packs in a stable order across launches. */
    public Map< String, Entry > packs = new LinkedHashMap<>();

    /**
     * Card-rendering subset of a {@link GameModPack}. Only fields the main-menu
     * card needs — name + version for labels, logo / background URL+SHA-1 for
     * the image cache, RAM minimum for the RAM-too-low gate, and a couple of
     * UI flags. Game-launch fields ({@code packMods}, {@code packForgeURL},
     * etc.) deliberately don't live here: they're only needed when the user
     * clicks Play, at which point the full manifest can be loaded from the
     * per-manifest cache or the network in the existing path.
     */
    public static final class Entry
    {
        public String packName;
        public String packVersion;
        /** Website URL ({@code packURL} in the manifest), not the manifest URL. */
        public String packURL;
        /** Logo URL(s) — string or array, mirroring the manifest. See {@link com.micatechnologies.minecraft.launcher.utilities.StringOrArray}. */
        public com.micatechnologies.minecraft.launcher.utilities.StringOrArray packLogoURL;
        public String packLogoSha1;
        /** Background URL(s) — string or array, mirroring the manifest. */
        public com.micatechnologies.minecraft.launcher.utilities.StringOrArray packBackgroundURL;
        public String packBackgroundSha1;
        public String packMinRAMGB;
        public boolean packUnstable;
        public boolean packCustomDiscordRpc;
        /** Epoch millis of the last successful refresh. Surfaces as a "refreshed
         *  X minutes ago" hint and lets us age out entries whose URL has long
         *  since been removed from the installed list. */
        public long updatedAt;
    }

    // ===== load / save =====

    /** Test seam: when non-null, overrides the on-disk index location so concurrency /
     *  round-trip tests can target a temp file instead of the real launcher folder. */
    static volatile Path testPathOverride;

    /** Monotonic counter giving each write a unique temp filename — see {@link #save()}. */
    private static final java.util.concurrent.atomic.AtomicLong TMP_SEQ =
            new java.util.concurrent.atomic.AtomicLong();

    private static Path indexPath()
    {
        if ( testPathOverride != null ) {
            return testPathOverride;
        }
        return Path.of( LocalPathManager.getLauncherModpackFolderPath(), INDEX_FILENAME );
    }

    /** Reads the index from disk, or returns an empty instance if the file is
     *  missing / unreadable / from a future schema version. Treating any read
     *  failure as empty forces the per-manifest fallback path on this launch,
     *  which transparently rebuilds the index as packs refresh. */
    public static synchronized InstallIndex load()
    {
        try {
            Path p = indexPath();
            if ( !Files.isRegularFile( p ) ) return new InstallIndex();
            String json = Files.readString( p, StandardCharsets.UTF_8 );
            InstallIndex parsed = JSONUtilities.getGson().fromJson( json, InstallIndex.class );
            if ( parsed == null ) return new InstallIndex();
            if ( parsed.version > CURRENT_SCHEMA_VERSION ) {
                Logger.logWarningSilent( "Install index version " + parsed.version
                                                 + " is newer than this launcher supports ("
                                                 + CURRENT_SCHEMA_VERSION + "). Treating as empty." );
                return new InstallIndex();
            }
            if ( parsed.packs == null ) parsed.packs = new LinkedHashMap<>();
            return parsed;
        }
        catch ( Exception e ) {
            Logger.logWarningSilent( "Could not read install index: " + e.getClass().getSimpleName() );
            return new InstallIndex();
        }
    }

    /** Best-effort atomic-ish write. Atomic rename is used where supported so a
     *  partial write doesn't leave behind a corrupted JSON the next load has to
     *  discard. Falls back to a direct write if either the parent-directory
     *  create or the atomic move fails — the file's still useful even without
     *  the atomicity guarantee, and we'd rather end up with a written index
     *  than an empty one. */
    public synchronized void save()
    {
        Path p = indexPath();
        Path parent = p.getParent();

        // Ensure the parent directory exists before any write attempt. Use
        // Files.createDirectories instead of File.mkdirs so a permission or
        // path-resolution failure surfaces as a real exception we can log,
        // rather than silently returning false and tripping the writeString
        // below with NoSuchFileException.
        try {
            if ( parent != null ) Files.createDirectories( parent );
        }
        catch ( IOException e ) {
            Logger.logWarningSilent( "Could not create install-index parent dir "
                                             + parent + ": " + e.getClass().getSimpleName() );
            return;
        }

        String json = JSONUtilities.getGson().toJson( this );

        // Primary path: write to a sibling .tmp file and atomically rename
        // into place so a partial write can't corrupt the live index. The temp
        // name is unique per write so two concurrent writes can never clobber
        // each other's staging file (the exact corruption a shared ".tmp" caused),
        // and a leftover temp from a crashed write can't collide with a live one.
        Path tmp = p.resolveSibling( p.getFileName() + ".tmp." + TMP_SEQ.incrementAndGet() );
        try {
            Files.writeString( tmp, json, StandardCharsets.UTF_8 );
            try {
                Files.move( tmp, p, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                                     java.nio.file.StandardCopyOption.ATOMIC_MOVE );
                return;
            }
            catch ( Exception atomicFail ) {
                // Filesystem doesn't support atomic move (some network mounts,
                // older Windows configs); fall back to plain replace.
                Files.move( tmp, p, java.nio.file.StandardCopyOption.REPLACE_EXISTING );
                return;
            }
        }
        catch ( IOException tmpFail ) {
            // Tmp-file path failed. Best-effort cleanup, then try a direct
            // write — losing atomicity is OK, losing the whole index isn't.
            try { Files.deleteIfExists( tmp ); } catch ( IOException ignored ) {}
            try {
                Files.writeString( p, json, StandardCharsets.UTF_8 );
            }
            catch ( IOException directFail ) {
                Logger.logWarningSilent( "Could not write install index: "
                                                 + directFail.getClass().getSimpleName()
                                                 + " — " + directFail.getMessage() );
            }
        }
    }

    // ===== atomic disk mutations (serialized + crash-safe) =====

    /**
     * Atomically loads the on-disk index, upserts {@code pack} under {@code manifestUrl},
     * and writes it back — the whole read-modify-write under one lock (the same
     * {@code InstallIndex.class} monitor {@link #load()} uses), so concurrent writers can
     * neither lose each other's updates nor corrupt the file.
     *
     * <p>This is the ONLY safe way to mutate the persisted index from the parallel
     * manifest-load paths ({@code fetchInstalledModPacks} fans out over a
     * {@code parallelStream}). A bare {@code load() / upsert() / save()} sequence on
     * separate instances races two ways: two loaders read the same snapshot so the later
     * save drops the earlier's entry, and two {@code save()} calls — instance-synchronized
     * on <em>different</em> instances, hence not mutually excluded — used to interleave
     * their writes to a single shared temp file and publish a garbled JSON that the next
     * {@code load()} rejected with a {@code JsonSyntaxException}.</p>
     *
     * @since 3.6
     */
    public static synchronized void upsertAndSave( String manifestUrl, GameModPack pack )
    {
        if ( manifestUrl == null || manifestUrl.isBlank() || pack == null ) return;
        InstallIndex idx = load();
        idx.upsert( manifestUrl, pack );
        idx.save();
    }

    /**
     * Atomic load-remove-save counterpart of {@link #upsertAndSave}. Use on uninstall so
     * dropping an entry can't race a concurrent upsert into a lost update or a corrupt file.
     *
     * @since 3.6
     */
    public static synchronized void removeAndSave( String manifestUrl )
    {
        if ( manifestUrl == null || manifestUrl.isBlank() ) return;
        InstallIndex idx = load();
        idx.remove( manifestUrl );
        idx.save();
    }

    // ===== mutation =====

    /** Adds-or-replaces the entry for {@code manifestUrl} with the card-rendering
     *  subset of {@code pack}. NOT safe to pair with a separate {@link #save()} from
     *  concurrent threads — use {@link #upsertAndSave} for any disk-backed mutation
     *  that can run in parallel. */
    public synchronized void upsert( String manifestUrl, GameModPack pack )
    {
        if ( manifestUrl == null || manifestUrl.isBlank() || pack == null ) return;
        if ( packs == null ) packs = new LinkedHashMap<>();
        Entry e = new Entry();
        // Same-package access of the GSON-deserialized fields directly — saves us
        // from threading a parallel set of getters through GameModPackMetadata
        // just for the index. Read the raw values so the wire shape stays intact
        // (e.g. packMinRAMGB is a String in the manifest, not a parsed double).
        e.packName             = pack.packName;
        e.packVersion          = pack.packVersion;
        e.packURL              = pack.packURL;
        e.packLogoURL          = pack.packLogoURL;
        e.packLogoSha1         = pack.packLogoSha1;
        e.packBackgroundURL    = pack.packBackgroundURL;
        e.packBackgroundSha1   = pack.packBackgroundSha1;
        e.packMinRAMGB         = pack.packMinRAMGB;
        e.packUnstable         = pack.packUnstable;
        e.packCustomDiscordRpc = pack.packCustomDiscordRpc;
        e.updatedAt            = System.currentTimeMillis();
        packs.put( manifestUrl, e );
    }

    /** Removes a single entry — used when the user uninstalls a pack so the
     *  index doesn't accumulate ghost entries that age never reaps. */
    public synchronized void remove( String manifestUrl )
    {
        if ( manifestUrl == null || manifestUrl.isBlank() || packs == null ) return;
        packs.remove( manifestUrl );
    }

    /** Returns the entry for the given URL, or {@code null} if absent. */
    public synchronized Entry get( String manifestUrl )
    {
        if ( manifestUrl == null || packs == null ) return null;
        return packs.get( manifestUrl );
    }
}
