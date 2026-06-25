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
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Pre-launch heuristic scan of a pack's {@code mods/} folder for known-bad
 * mod combinations. Returns one {@link Conflict} per detected incompatibility
 * so the launch flow can prompt the user to disable one of the offenders
 * BEFORE the game starts crashing, rather than relying on
 * {@link com.micatechnologies.minecraft.launcher.game.crash.CrashReportAnalyzer}
 * after the fact.
 *
 * <p>Filename-based heuristics only — no jar-opening — so the scan is cheap
 * enough to run on the play-button click path. Each rule encodes a known
 * incompatibility between two mod families (e.g. OptiFine + Sodium). The
 * matcher looks for paired filename patterns; both halves must be present
 * for a conflict to fire.</p>
 *
 * <p>Detected conflicts are intentionally NON-FATAL. The user sees a
 * confirmation prompt with one-click "Disable X" actions but can also opt
 * to launch anyway — some users actually want unsupported combinations
 * (e.g. testing a mod author's fork that does coexist).</p>
 *
 * @since 2026.5
 */
public final class ModConflictDetector
{
    /**
     * Private constructor to prevent instantiation of this utility class.
     */
    private ModConflictDetector() { /* static-only */ }

    /**
     * A detected conflict between two mods. {@link #firstJarName} and
     * {@link #secondJarName} are the {@code .jar} filenames (no path) the
     * user can disable to resolve it.
     *
     * @param title         short one-line label for the prompt
     * @param description   multi-sentence explanation of why the combination breaks
     * @param firstJarName  filename of the first offending mod jar in {@code mods/}
     * @param secondJarName filename of the second offending mod jar in {@code mods/}
     *
     * @since 2026.5
     */
    public record Conflict( String title, String description,
                             String firstJarName, String secondJarName ) { }

    /**
     * A rule representing a known incompatibility between two mod families.
     * The matcher looks for paired filename patterns; both halves must be present
     * for a conflict to fire.
     */
    private record Rule( String title, String description,
                          Pattern firstPattern, Pattern secondPattern ) { }

    /**
     * Rule set, evaluated in order. New rules add here; one rule per known
     * incompatibility. The same patterns mirror what
     * {@link com.micatechnologies.minecraft.launcher.game.crash.CrashReportAnalyzer}
     * looks for in crash text — just applied to filenames instead.
     */
    private static final List< Rule > RULES = List.of(
            // OptiFine + Sodium — patch the same render internals incompatibly.
            new Rule(
                    "OptiFine and Sodium are incompatible",
                    "Both OptiFine and Sodium are installed. They patch the same Minecraft "
                            + "rendering internals in incompatible ways and crash on launch. "
                            + "Disable one — Sodium is the Fabric-native choice; OptiFine works on "
                            + "Forge or with an OptiFabric bridge.",
                    Pattern.compile( "(?i)\\boptifine\\b|\\boptifabric\\b" ),
                    Pattern.compile( "(?i)\\bsodium(?:fabric)?\\b" )
            ),
            // JEI + REI — both register recipe-viewer UI; duplicate registries crash on world load.
            new Rule(
                    "JEI and REI are both installed",
                    "Just Enough Items (JEI) and Roughly Enough Items (REI) both register "
                            + "recipe-viewer state with the same identifiers and crash with "
                            + "\"Duplicate recipe id\" errors on world load. Disable one — "
                            + "they have feature-equivalent UIs, so the choice is preference.",
                    Pattern.compile( "(?i)\\bjei\\b|just[- ]?enough[- ]?items|mezz\\.jei" ),
                    Pattern.compile( "(?i)\\brei\\b|roughly[- ]?enough[- ]?items|shedaniel\\.rei" )
            )
    );

    /**
     * Scans the pack's {@code mods/} folder and returns the conflicts found.
     * Empty list = no known incompatibilities; the launch can proceed.
     *
     * <p>Disabled jars ({@code .jar.disabled}) are ignored — they're already
     * out of the loader's path. Only files ending in {@code .jar} count.</p>
     *
     * @param pack the modpack to scan; safe to pass a vanilla pack (returns empty)
     * @return non-null list, may be empty
     *
     * @since 2026.5
     */
    public static List< Conflict > scan( GameModPack pack )
    {
        if ( pack == null || pack.getPackRootFolder() == null ) return List.of();
        File modsDir = new File( pack.getPackRootFolder(), "mods" );
        if ( !modsDir.isDirectory() ) return List.of();
        File[] jars = modsDir.listFiles( f -> f.isFile()
                && f.getName().toLowerCase( Locale.ROOT ).endsWith( ".jar" ) );
        if ( jars == null || jars.length == 0 ) return List.of();
        List< String > names = new ArrayList<>( jars.length );
        for ( File j : jars ) names.add( j.getName() );
        return detectFromNames( names );
    }

    /**
     * Pattern-matching core, separated from filesystem I/O so the
     * conflict-detection logic can be unit-tested with synthetic name
     * lists. Returns the same conflicts {@link #scan} produces for a
     * pack whose {@code mods/} folder contained exactly those jar
     * filenames. Package-private.
     *
     * @param jarFileNames non-null list of jar filenames (no path prefix);
     *                     filenames ending in {@code .jar.disabled} should
     *                     not be included by the caller — the scan loop
     *                     already filters them out.
     * @return non-null list of detected conflicts, may be empty
     */
    static List< Conflict > detectFromNames( List< String > jarFileNames )
    {
        if ( jarFileNames == null || jarFileNames.isEmpty() ) return List.of();
        List< Conflict > hits = new ArrayList<>();
        for ( Rule rule : RULES ) {
            String first = firstMatch( jarFileNames, rule.firstPattern );
            String second = firstMatch( jarFileNames, rule.secondPattern );
            if ( first != null && second != null && !first.equals( second ) ) {
                hits.add( new Conflict( rule.title, rule.description, first, second ) );
            }
        }
        return hits;
    }

    /**
     * Returns the first jar filename whose name matches the pattern, or {@code null}.
     *
     * @param names   list of jar filenames to search
     * @param pattern pattern to match against each filename
     * @return the first matching filename, or {@code null} if no match is found
     */
    private static String firstMatch( List< String > names, Pattern pattern )
    {
        for ( String name : names ) {
            if ( pattern.matcher( name ).find() ) {
                return name;
            }
        }
        return null;
    }

    /**
     * Disables a mod jar by renaming it to {@code <name>.jar.disabled}. Used
     * by the conflict-prompt's "Disable X" buttons. Returns {@code true} on
     * success, {@code false} on failure (already disabled, file locked,
     * permission denied — caller falls back to "Open Mods Folder").
     *
     * <p>Atomic-move based, same primitive the in-modal toggle uses.</p>
     *
     * @param pack        the modpack whose {@code mods/} folder holds the jar
     * @param jarFileName the jar filename (no path) to disable
     * @return {@code true} if the jar was renamed to {@code <name>.jar.disabled};
     *         {@code false} on any failure (bad arguments, missing file,
     *         already-disabled target, locked file, or permission denied)
     *
     * @since 2026.5
     */
    public static boolean disableJar( GameModPack pack, String jarFileName )
    {
        if ( pack == null || pack.getPackRootFolder() == null
                || jarFileName == null || jarFileName.isBlank() ) {
            return false;
        }
        File modsDir = new File( pack.getPackRootFolder(), "mods" );
        File jar = new File( modsDir, jarFileName );
        if ( !jar.isFile() ) return false;
        File disabled = new File( modsDir, jarFileName + ".disabled" );
        if ( disabled.exists() ) return false;
        try {
            java.nio.file.Files.move( jar.toPath(), disabled.toPath(),
                                      java.nio.file.StandardCopyOption.ATOMIC_MOVE );
            return true;
        }
        catch ( Exception ex ) {
            return false;
        }
    }
}
