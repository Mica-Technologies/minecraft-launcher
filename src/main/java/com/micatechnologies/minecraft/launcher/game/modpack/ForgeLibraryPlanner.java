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

import java.util.function.Predicate;

/**
 * Decides how one entry in a Forge installer's library list maps onto files the launcher must
 * put on the classpath.
 * <p>
 * This is the part of {@code GameModLoaderForge.getForgeLibrariesList()} that actually has to
 * be got right, and it is the part {@code CLAUDE.md} means when it calls that method the most
 * complex in the codebase. It is pure: the only questions it asks about the installer — "is
 * this artifact bundled under {@code maven/}?" and "is it at the top level?" — arrive as
 * predicates, so the whole decision table can be exercised without a real Forge installer.
 * <p>
 * <b>What it is deciding.</b> Modern Forge (1.13+) installers embed two jars for the loader
 * itself: {@code forge-<ver>.jar}, holding launch-target services, and
 * {@code forge-<ver>-universal.jar}, holding the actual Forge code. Both must reach the
 * classpath. Legacy Forge (1.7–1.12) has only the universal jar, and puts it at the
 * <em>top level</em> of the installer rather than under {@code maven/} — while still listing it
 * in the manifest with a Maven URL that Forge's own repository does not serve. Getting any of
 * this wrong produces a classpath that looks plausible and a game that will not start.
 *
 * @author Mica Technologies
 * @version 1.0
 * @since 3.0
 */
final class ForgeLibraryPlanner
{
    /**
     * How one library entry resolves.
     *
     * @param repoPath                the repository-relative path to fetch and cache the
     *                                artifact at, after any legacy substitution
     * @param addUniversalAsExtra     whether a second classpath entry must be added for the
     *                                universal jar — the modern-Forge case where both exist
     * @param universalRepoPath       the universal jar's path when one was considered, or
     *                                {@code null}; only meaningful alongside
     *                                {@code addUniversalAsExtra}
     * @param legacyTopLevelUniversal whether the artifact is the legacy universal jar sitting
     *                                at the installer's top level
     * @param legacyTopLevelEntry     that top-level entry's name, or {@code null}
     * @param suppressSha1            whether the manifest's SHA-1 must be discarded, because
     *                                the file actually being used is not the file the hash
     *                                describes
     *
     * @since 3.0
     */
    record Plan( String repoPath, boolean addUniversalAsExtra, String universalRepoPath,
                 boolean legacyTopLevelUniversal, String legacyTopLevelEntry, boolean suppressSha1 )
    {
    }

    /**
     * Derives the repository path implied by a Maven coordinate, for entries whose manifest
     * gives no explicit {@code downloads.artifact.path}.
     * <p>
     * {@code net.minecraftforge:forge:1.7.10-10.13.4.1614} yields
     * {@code forge-1.7.10-10.13.4.1614} — the group is dropped and the remaining segments are
     * joined with hyphens, which is the filename stem Forge uses.
     *
     * @param coordinate the Maven coordinate from the manifest
     *
     * @return the inferred path stem
     *
     * @throws IllegalArgumentException if the coordinate has no group separator
     * @since 3.0
     */
    static String inferredRepoPath( String coordinate )
    {
        if ( coordinate == null || coordinate.indexOf( ':' ) < 0 ) {
            throw new IllegalArgumentException( "Not a Maven coordinate: " + coordinate );
        }
        return coordinate.substring( coordinate.indexOf( ':' ) + 1 ).replace( ":", "-" );
    }

    /**
     * Plans how one library entry maps onto files.
     *
     * @param assetName           the entry's Maven coordinate
     * @param initialRepoPath     the path before any substitution — the manifest's explicit
     *                            path when it had one, otherwise the inferred stem
     * @param inferredRepoPath    the stem derived from the coordinate
     * @param isSpecifiedRepoPath whether the manifest gave an explicit path
     * @param loaderCoordPrefix   the coordinate prefix identifying the loader's own artifact,
     *                            e.g. {@code net.minecraftforge:forge:}
     * @param hasMavenEntry       whether a given path exists under the installer's
     *                            {@code maven/} subtree
     * @param hasTopLevelEntry    whether a given name exists at the installer's top level
     *
     * @return the plan
     *
     * @since 3.0
     */
    static Plan plan( String assetName, String initialRepoPath, String inferredRepoPath,
                      boolean isSpecifiedRepoPath, String loaderCoordPrefix,
                      Predicate< String > hasMavenEntry, Predicate< String > hasTopLevelEntry )
    {
        String repoPath = initialRepoPath;
        boolean addUniversalAsExtra = false;
        String universalRepoPath = null;
        boolean suppressSha1 = false;

        boolean isLoaderArtifact = assetName != null && loaderCoordPrefix != null
                && assetName.startsWith( loaderCoordPrefix );

        // Modern Forge: the manifest names an explicit path, and the installer may carry both
        // the base jar and the universal one beside it.
        if ( isSpecifiedRepoPath && isLoaderArtifact && repoPath != null
                && !repoPath.contains( "-universal" ) ) {
            universalRepoPath = repoPath.replace( ".jar", "-universal.jar" );
            if ( hasMavenEntry.test( universalRepoPath ) && hasMavenEntry.test( repoPath ) ) {
                // Both present: keep the base jar and add the universal one alongside it.
                addUniversalAsExtra = true;
            }
            else if ( hasMavenEntry.test( universalRepoPath ) ) {
                // Only the universal one: substitute it for the base path. The manifest's
                // SHA-1 described the base jar, so it no longer applies to what will be
                // downloaded and must be dropped rather than checked against the wrong file.
                repoPath = universalRepoPath;
                suppressSha1 = true;
            }
        }

        // Legacy Forge: no explicit path, and the universal jar sits at the installer's top
        // level. The manifest still points at a Maven URL that Forge's repository does not
        // actually serve, so the embedded copy has to be used instead.
        boolean legacyTopLevelUniversal = false;
        String legacyTopLevelEntry = null;
        if ( !isSpecifiedRepoPath && isLoaderArtifact && inferredRepoPath != null ) {
            String topLevelName = inferredRepoPath + "-universal.jar";
            if ( hasTopLevelEntry.test( topLevelName ) ) {
                legacyTopLevelUniversal = true;
                legacyTopLevelEntry = topLevelName;
                // The manifest carries no hash for the bare library entry, so there is
                // nothing to verify the embedded jar against.
                suppressSha1 = true;
            }
        }

        return new Plan( repoPath, addUniversalAsExtra, universalRepoPath, legacyTopLevelUniversal,
                         legacyTopLevelEntry, suppressSha1 );
    }

    /**
     * Builds the on-disk relative path a library is cached at.
     * <p>
     * An entry with an explicit manifest path maps straight onto it. One without gets a path
     * reconstructed from the Maven coordinate — {@code group/artifact/version/stem.jar} — which
     * is what the launcher's cache layout expects.
     *
     * @param assetName           the Maven coordinate
     * @param repoPath            the repository path, after any substitution
     * @param isSpecifiedRepoPath whether the manifest gave an explicit path
     * @param inferredRepoPath    the stem derived from the coordinate
     * @param separator           the platform path separator; a parameter so both the Windows
     *                            and Unix layouts can be exercised from one machine
     * @param jarExtension        the jar file extension, including the dot
     *
     * @return the relative local path
     *
     * @since 3.0
     */
    static String localPath( String assetName, String repoPath, boolean isSpecifiedRepoPath,
                             String inferredRepoPath, String separator, String jarExtension )
    {
        if ( isSpecifiedRepoPath ) {
            return repoPath.replace( "/", separator );
        }
        int colon = assetName.indexOf( ':' );
        return assetName.substring( 0, colon ).replace( ".", separator )
                + separator
                + assetName.substring( colon + 1 ).replace( ":", separator )
                + separator
                + inferredRepoPath
                + jarExtension;
    }

    /**
     * Not instantiable.
     */
    private ForgeLibraryPlanner()
    {
        throw new AssertionError( "ForgeLibraryPlanner is a utility class and must not be instantiated" );
    }
}
