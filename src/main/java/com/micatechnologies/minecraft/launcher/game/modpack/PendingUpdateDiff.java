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

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Computes a human-readable "what will this update change?" summary for a modpack with a pending
 * manifest update, by diffing the <b>current manifest's</b> declared mod list against the
 * <b>installed</b> mods on disk (the last-applied state). The result powers a "What's new" preview
 * in the detail modal so the user can see roughly what applying the update will do before launching.
 *
 * <p>Mods are grouped by a version-stripped base key so a version bump of the same mod reads as
 * <i>updated</i> rather than one removed + one added. Crucially, "updated" is decided by
 * <b>content hash</b> — the new manifest's declared SHA against the bytes already on disk — not by
 * comparing filenames. Many manifests reuse a stable, version-less filename per mod (e.g.
 * {@code jei.jar}) and express a new release purely through a changed download URL + hash, so a
 * filename comparison would (a) miss every real update and (b) report spurious add/remove churn the
 * moment an installed jar's name diverges from the manifest's (load-order prefixes, hand renames,
 * a different install origin). Hashing sidesteps both: a mod is "updated" iff its installed bytes
 * differ from what the new manifest declares.</p>
 *
 * <p>The diff is still a best-effort approximation: mods the user added by hand (outside the
 * manifest) can appear under "removed". It's intended as an at-a-glance hint, not an exact
 * transaction log — which is why the UI only surfaces it when an update is actually pending.</p>
 *
 * @since 2026.6
 */
public final class PendingUpdateDiff
{
    private PendingUpdateDiff() { /* static-only */ }

    /**
     * Summary of the manifest-vs-installed mod diff. Counts are by version-grouped mod, and the
     * name lists hold the base mod names (version-stripped) for display.
     */
    public record Result(
            int added,
            int removed,
            int updated,
            List< String > addedNames,
            List< String > removedNames,
            List< String > updatedNames )
    {
        public boolean isEmpty()
        {
            return added == 0 && removed == 0 && updated == 0;
        }
    }

    /**
     * Computes the pending-change summary for {@code pack}. Compares the manifest's {@code packMods}
     * (new) to the jars currently in {@code <packRoot>/mods/} (installed): a mod is <i>added</i>
     * when the manifest declares it but no installed jar matches its key, <i>removed</i> when an
     * installed jar's key is absent from the manifest, and <i>updated</i> when both sides share a
     * key but the installed bytes don't match the manifest's declared hash. Returns an empty result
     * if the pack is vanilla, has no manifest mods, or the diff is empty.
     */
    public static Result compute( GameModPack pack )
    {
        if ( pack == null || pack.isVanillaVersion() || pack.packMods == null ) {
            return empty();
        }

        // Installed jars on disk, grouped by a stable, version-stripped key so a version bump of the
        // same mod groups with its manifest entry instead of reading as a remove + add pair.
        Map< String, File >   oldFileByKey    = new LinkedHashMap<>();   // key -> on-disk file
        Map< String, String > oldDisplayByKey = new LinkedHashMap<>();   // key -> display base name
        File modsDir = new File( pack.getPackRootFolder(), "mods" );
        File[] onDisk = modsDir.listFiles( f -> {
            if ( !f.isFile() ) {
                return false;
            }
            String n = f.getName().toLowerCase( Locale.ROOT );
            return n.endsWith( ".jar" ) || n.endsWith( ".jar.disabled" );
        } );
        if ( onDisk != null ) {
            for ( File f : onDisk ) {
                String key = modKey( f.getName() );
                oldFileByKey.putIfAbsent( key, f );
                oldDisplayByKey.putIfAbsent( key, baseName( f.getName() ) );
            }
        }

        List< String > added = new ArrayList<>();
        List< String > updated = new ArrayList<>();
        // Track which installed keys the manifest accounts for; whatever's left over is "removed".
        Set< String > newKeys = new HashSet<>();
        for ( GameMod mod : pack.packMods ) {
            String filename = safeFileName( mod );
            if ( filename == null ) {
                continue;
            }
            String key = modKey( filename );
            newKeys.add( key );

            // Server-only mods are never installed on a client, so they'd otherwise masquerade as a
            // perpetual "added" entry the user can't act on. Skip them in the client-facing summary.
            if ( !mod.clientReq ) {
                continue;
            }

            File installed = oldFileByKey.get( key );
            if ( installed == null ) {
                added.add( baseName( filename ) );
            }
            // Content-based change detection. matchesDeclaredHash hashes the installed bytes against
            // the new manifest's declared hash; a mismatch is the only reliable "this mod actually
            // changed" signal when filenames are stable across versions. It returns true (= no
            // change reported) when the mod declares no usable hash, so we never cry wolf.
            else if ( !safeMatchesDeclaredHash( mod, installed ) ) {
                updated.add( baseName( filename ) );
            }
        }

        List< String > removed = new ArrayList<>();
        for ( var e : oldFileByKey.entrySet() ) {
            if ( !newKeys.contains( e.getKey() ) ) {
                removed.add( oldDisplayByKey.get( e.getKey() ) );
            }
        }
        return new Result( added.size(), removed.size(), updated.size(), added, removed, updated );
    }

    /** Hashes {@code installed} against {@code mod}'s declared manifest hash, treating any failure
     *  (unreadable file, hashing error) as "unchanged" so a transient I/O hiccup can't spam the
     *  summary with false "updated" entries. */
    private static boolean safeMatchesDeclaredHash( GameMod mod, File installed )
    {
        try {
            return mod.matchesDeclaredHash( installed );
        }
        catch ( Throwable t ) {
            return true;
        }
    }

    private static Result empty()
    {
        return new Result( 0, 0, 0, List.of(), List.of(), List.of() );
    }

    private static String safeFileName( GameMod mod )
    {
        try {
            return mod.getFileName();
        }
        catch ( Throwable t ) {
            return null;
        }
    }

    /** Display base name: the version-stripped mod key in its original-ish form (no extension). */
    private static String baseName( String filename )
    {
        String n = filename;
        if ( n.toLowerCase( Locale.ROOT ).endsWith( ".jar.disabled" ) ) {
            n = n.substring( 0, n.length() - ".jar.disabled".length() );
        }
        else if ( n.toLowerCase( Locale.ROOT ).endsWith( ".jar" ) ) {
            n = n.substring( 0, n.length() - ".jar".length() );
        }
        String stripped = n.replaceAll( "[-_]v?\\d.*$", "" );
        return stripped.isBlank() ? n : stripped;
    }

    /** Version-grouping key: lower-cased base name with the trailing version token removed, so
     *  {@code jei-1.20.1-11.5.0.jar} and {@code jei-1.20.1-11.6.0.jar} share a key. */
    private static String modKey( String filename )
    {
        return baseName( filename ).toLowerCase( Locale.ROOT );
    }
}
