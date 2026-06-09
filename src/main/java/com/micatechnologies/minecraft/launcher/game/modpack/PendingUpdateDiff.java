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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Computes a human-readable "what will this update change?" summary for a modpack with a pending
 * manifest update, by diffing the <b>current manifest's</b> declared mod list against the
 * <b>installed</b> mods on disk (the last-applied state). The result powers a "What's new" preview
 * in the detail modal so the user can see roughly what applying the update will do before launching.
 *
 * <p>Mods are grouped by a version-stripped base key so a version bump of the same mod reads as
 * <i>updated</i> rather than one removed + one added. The diff is a best-effort approximation: it's
 * derived from filenames, and mods the user added by hand (outside the manifest) can appear under
 * "removed". It's intended as an at-a-glance hint, not an exact transaction log — which is why the
 * UI only surfaces it when an update is actually pending.</p>
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
     * (new) to the jars currently in {@code <packRoot>/mods/} (installed). Returns an empty result
     * if the pack is vanilla, has no manifest mods, or the diff is empty.
     */
    public static Result compute( GameModPack pack )
    {
        if ( pack == null || pack.isVanillaVersion() || pack.packMods == null ) {
            return empty();
        }

        // New = manifest-declared mod filenames; Old = installed jars on disk.
        Map< String, String > newByKey = new LinkedHashMap<>();   // key -> display base name
        Map< String, String > newExactByKey = new LinkedHashMap<>(); // key -> exact filename
        for ( GameMod mod : pack.packMods ) {
            String filename = safeFileName( mod );
            if ( filename == null ) {
                continue;
            }
            String key = modKey( filename );
            newByKey.putIfAbsent( key, baseName( filename ) );
            newExactByKey.putIfAbsent( key, normalizeJar( filename ) );
        }

        Map< String, String > oldByKey = new LinkedHashMap<>();
        Map< String, String > oldExactByKey = new LinkedHashMap<>();
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
                oldByKey.putIfAbsent( key, baseName( f.getName() ) );
                oldExactByKey.putIfAbsent( key, normalizeJar( f.getName() ) );
            }
        }

        List< String > added = new ArrayList<>();
        List< String > removed = new ArrayList<>();
        List< String > updated = new ArrayList<>();
        for ( var e : newByKey.entrySet() ) {
            String key = e.getKey();
            if ( !oldByKey.containsKey( key ) ) {
                added.add( e.getValue() );
            }
            else if ( !normalizeJar( newExactByKey.get( key ) ).equals( oldExactByKey.get( key ) ) ) {
                updated.add( e.getValue() );
            }
        }
        for ( var e : oldByKey.entrySet() ) {
            if ( !newByKey.containsKey( e.getKey() ) ) {
                removed.add( e.getValue() );
            }
        }
        return new Result( added.size(), removed.size(), updated.size(), added, removed, updated );
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

    /** Strips the {@code .jar} / {@code .jar.disabled} suffix, lower-cased. */
    private static String normalizeJar( String filename )
    {
        String n = filename.toLowerCase( Locale.ROOT );
        if ( n.endsWith( ".jar.disabled" ) ) {
            n = n.substring( 0, n.length() - ".disabled".length() );
        }
        return n;
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
