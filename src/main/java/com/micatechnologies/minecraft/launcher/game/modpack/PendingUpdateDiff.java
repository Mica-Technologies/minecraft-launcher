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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.micatechnologies.minecraft.launcher.utilities.objects.GameMode;

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
 * <p><b>Optional mods.</b> Changes to the {@code packOptionalMods} the player has on are listed
 * like any other mod change and tagged as optional ({@link Result#optionalNames()}). An optional
 * mod that is off is never on disk, so its changes are hidden; one that is new since the last
 * successful sync is listed as "available, off" ({@link Result#newOptionalNames()}). "New" is
 * judged against the {@code .known_optional_mods} sidecar the sync writes, since the diff has no
 * copy of the previous manifest. Optional jars never count as "removed".</p>
 *
 * @since 2026.6
 */
public final class PendingUpdateDiff
{
    /**
     * Private constructor to prevent instantiation of this utility class.
     */
    private PendingUpdateDiff() { /* static-only */ }

    /**
     * Summary of the manifest-vs-installed mod diff. Counts are by version-grouped mod, and the
     * name lists hold the base mod names (version-stripped) for display.
     *
     * @param added        number of mods the new manifest declares that aren't installed
     * @param removed      number of installed mods absent from the new manifest
     * @param updated      number of mods present on both sides whose installed bytes differ from
     *                     the manifest's declared hash
     * @param addedNames   version-stripped display names of the added mods
     * @param removedNames version-stripped display names of the removed mods
     * @param updatedNames version-stripped display names of the updated mods
     * @param optionalNames    the names in {@code addedNames} / {@code updatedNames} that belong to
     *                         optional mods the player has on, for an "optional" tag
     * @param newOptionalNames display names of optional mods that are new since the last sync and
     *                         off (available for the player to switch on)
     *
     * @since 2026.6
     */
    public record Result(
            int added,
            int removed,
            int updated,
            List< String > addedNames,
            List< String > removedNames,
            List< String > updatedNames,
            Set< String > optionalNames,
            List< String > newOptionalNames )
    {
        /**
         * Reports whether this diff contains no changes at all.
         *
         * @return {@code true} when the added, removed, and updated counts are all zero and no
         *         new optional mod is available
         *
         * @since 2026.6
         */
        public boolean isEmpty()
        {
            return added == 0 && removed == 0 && updated == 0 && newOptionalNames.isEmpty();
        }
    }

    /**
     * Computes the pending-change summary for {@code pack}. Compares the manifest's {@code packMods}
     * (new) to the jars currently in {@code <packRoot>/mods/} (installed): a mod is <i>added</i>
     * when the manifest declares it but no installed jar matches its key, <i>removed</i> when an
     * installed jar's key is absent from the manifest, and <i>updated</i> when both sides share a
     * key but the installed bytes don't match the manifest's declared hash. Returns an empty result
     * if the pack is vanilla, has no manifest mods, or the diff is empty.
     *
     * @param pack the modpack to diff; may be {@code null} (yields an empty result)
     * @return the manifest-vs-installed change summary, never {@code null}
     *
     * @since 2026.6
     */
    public static Result compute( GameModPack pack )
    {
        if ( pack == null || pack.isVanillaVersion() || pack.packMods == null ) {
            return empty();
        }

        File modsDir = new File( pack.getPackRootFolder(), "mods" );
        File[] onDisk = modsDir.listFiles( File::isFile );

        // The summary is client-facing: an optional mod counts as "on" when the player's choice
        // (or its default) installs it on this client.
        List< GameOptionalMod > optional = pack.getPackOptionalMods();
        Set< String > enabled = new HashSet<>();
        for ( GameOptionalMod mod : OptionalModResolver.selectForSide( optional, GameMode.CLIENT,
                                                                       OptionalMods.choicesFor( pack ) ) ) {
            enabled.add( mod.key() );
        }
        List< String > known = PackDotFile.readLines( pack.getPackRootFolder(),
                                                      GameModPackFileSync.KNOWN_OPTIONAL_MODS_FILE );
        return compute( pack.packMods, optional, enabled, new HashSet<>( known ), onDisk );
    }

    /**
     * The diff itself, over plain inputs so it can be tested without a pack folder.
     *
     * @param required    the manifest's {@code packMods}
     * @param optional    the manifest's {@code packOptionalMods}
     * @param enabledKeys identity keys of the optional mods installed on this client
     * @param knownKeys   identity keys of the optional mods the manifest had at the last
     *                    successful sync (empty when that was never recorded)
     * @param onDisk      the files in the pack's {@code mods} folder; {@code null} reads as none
     *
     * @return the change summary, never {@code null}
     *
     * @since 2026.11
     */
    static Result compute( List< GameMod > required, List< GameOptionalMod > optional, Set< String > enabledKeys,
                           Set< String > knownKeys, File[] onDisk )
    {
        // Installed jars on disk, grouped by a stable, version-stripped key so a version bump of the
        // same mod groups with its manifest entry instead of reading as a remove + add pair.
        Map< String, File >   oldFileByKey    = new LinkedHashMap<>();   // key -> on-disk file
        Map< String, String > oldDisplayByKey = new LinkedHashMap<>();   // key -> display base name
        if ( onDisk != null ) {
            for ( File f : onDisk ) {
                String n = f.getName().toLowerCase( Locale.ROOT );
                if ( !n.endsWith( ".jar" ) && !n.endsWith( ".jar.disabled" ) ) {
                    continue;
                }
                String key = modKey( f.getName() );
                oldFileByKey.putIfAbsent( key, f );
                oldDisplayByKey.putIfAbsent( key, baseName( f.getName() ) );
            }
        }

        List< String > added = new ArrayList<>();
        List< String > updated = new ArrayList<>();
        Set< String > optionalNames = new LinkedHashSet<>();
        List< String > newOptional = new ArrayList<>();
        // Track which installed keys the manifest accounts for; whatever's left over is "removed".
        Set< String > newKeys = new HashSet<>();
        for ( GameMod mod : required == null ? List.< GameMod >of() : required ) {
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
            classify( mod, filename, oldFileByKey, added, updated );
        }

        for ( GameOptionalMod mod : optional == null ? List.< GameOptionalMod >of() : optional ) {
            String filename = safeFileName( mod );
            if ( filename == null ) {
                continue;
            }
            // An optional jar is never "removed" by an update: when it is off, the player chose that.
            newKeys.add( modKey( filename ) );
            if ( enabledKeys != null && enabledKeys.contains( mod.key() ) ) {
                String change = classify( mod, filename, oldFileByKey, added, updated );
                if ( change != null ) {
                    optionalNames.add( change );
                }
            }
            else if ( knownKeys == null || !knownKeys.contains( mod.key() ) ) {
                newOptional.add( mod.getDisplayName() );
            }
        }

        List< String > removed = new ArrayList<>();
        for ( var e : oldFileByKey.entrySet() ) {
            if ( !newKeys.contains( e.getKey() ) ) {
                removed.add( oldDisplayByKey.get( e.getKey() ) );
            }
        }
        return new Result( added.size(), removed.size(), updated.size(), added, removed, updated, optionalNames,
                           newOptional );
    }

    /**
     * Files one manifest mod as added or updated against what is installed.
     *
     * @param mod          the manifest mod
     * @param filename     its file name
     * @param oldFileByKey the installed jars by grouping key
     * @param added        accumulator for added names
     * @param updated      accumulator for updated names
     *
     * @return the display name it was filed under, or {@code null} when it is unchanged
     */
    private static String classify( GameMod mod, String filename, Map< String, File > oldFileByKey,
                                    List< String > added, List< String > updated )
    {
        File installed = oldFileByKey.get( modKey( filename ) );
        String name = baseName( filename );
        if ( installed == null ) {
            added.add( name );
            return name;
        }
        // Content-based change detection. matchesDeclaredHash hashes the installed bytes against
        // the new manifest's declared hash; a mismatch is the only reliable "this mod actually
        // changed" signal when filenames are stable across versions. It returns true (= no
        // change reported) when the mod declares no usable hash, so we never cry wolf.
        if ( !safeMatchesDeclaredHash( mod, installed ) ) {
            updated.add( name );
            return name;
        }
        return null;
    }

    /**
     * Hashes {@code installed} against {@code mod}'s declared manifest hash, treating any failure
     * (unreadable file, hashing error) as "unchanged" so a transient I/O hiccup can't spam the
     * summary with false "updated" entries.
     *
     * @param mod       the game mod to check
     * @param installed the installed file of the mod
     * @return {@code true} if the installed file matches the declared hash, {@code false} otherwise
     */
    private static boolean safeMatchesDeclaredHash( GameMod mod, File installed )
    {
        try {
            return mod.matchesDeclaredHash( installed );
        }
        catch ( Throwable t ) {
            return true;
        }
    }

    /**
     * Returns an empty result instance.
     *
     * @return an empty {@link Result} instance
     */
    private static Result empty()
    {
        return new Result( 0, 0, 0, List.of(), List.of(), List.of(), Set.of(), List.of() );
    }

    /**
     * Safely retrieves the file name of a game mod.
     *
     * @param mod the game mod to get the file name from
     * @return the file name of the mod, or {@code null} if an exception occurs
     */
    private static String safeFileName( GameMod mod )
    {
        try {
            // The manifest's local path, not getFileName(): that one joins the local-path prefix,
            // which a pack that has not synced in this session has not set yet.
            String local = mod.getLocalFilePath();
            if ( local == null || local.isBlank() ) {
                return null;
            }
            String normalized = local.replace( '\\', '/' );
            return normalized.substring( normalized.lastIndexOf( '/' ) + 1 );
        }
        catch ( Throwable t ) {
            return null;
        }
    }

    /**
     * Extracts the base name from a file name by removing version tokens and extensions.
     *
     * @param filename the file name to process
     * @return the base name of the file
     */
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

    /**
     * Generates a version-grouping key for a file name by removing the trailing version token and
     * converting to lower case.
     *
     * @param filename the file name to process
     * @return the version-grouping key of the file
     */
    private static String modKey( String filename )
    {
        return baseName( filename ).toLowerCase( Locale.ROOT );
    }
}
