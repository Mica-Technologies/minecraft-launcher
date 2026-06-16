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

import com.micatechnologies.minecraft.launcher.exceptions.ModpackException;

/**
 * Single source-of-truth for Maven-coordinate → filesystem-path
 * conversion across the launcher's modloader implementations.
 *
 * <p>Both {@link GameModLoaderForge} and {@link GameModLoaderFabric}
 * historically carried their own {@code mavenCoordToPath} helper —
 * similar code, subtly different rules (Forge handled
 * {@code [group:artifact:version]} brackets and {@code @ext}
 * suffixes plus path-traversal validation; Fabric was the simpler
 * three-part form). This consolidation captures both behaviours in
 * one helper so future modloaders inherit the same shape and
 * fixes / hardening apply everywhere at once.</p>
 *
 * <h3>Coordinate grammar</h3>
 * <pre>
 *   coord = [opening-bracket] group ":" artifact ":" version [ ":" classifier ] [ "@" ext ] [closing-bracket]
 * </pre>
 * <p>{@code group} is dot-separated and gets translated to {@code /}
 * for the filesystem path. {@code ext} defaults to {@code jar}.
 * Brackets around the whole coordinate are stripped — Forge installer
 * manifests sometimes wrap their version-range constraints that way.</p>
 *
 * @since 2026.5
 */
public final class MavenArtifactPath
{
    private MavenArtifactPath() { /* static-only */ }

    /** One parsed Maven coordinate. {@code classifier} may be null;
     *  {@code ext} defaults to {@code "jar"}. */
    public record Coord( String group, String artifact, String version,
                          String classifier, String ext )
    {
        /** Relative filesystem path under a Maven repo root —
         *  {@code group-as-path/artifact/version/artifact-version[-classifier].ext}.
         *  Uses forward slashes; callers that need OS-specific
         *  separators should call {@code String.replace('/', File.separatorChar)}
         *  on the result. */
        public String toRelativePath()
        {
            String fileName = artifact + "-" + version
                    + ( classifier != null ? "-" + classifier : "" )
                    + "." + ext;
            return group.replace( '.', '/' )
                    + "/" + artifact
                    + "/" + version
                    + "/" + fileName;
        }
    }

    /**
     * Parses {@code coord} with strict validation. Throws on missing
     * required parts (need at least group:artifact:version) and on
     * any path-traversal smuggling attempt
     * ({@code "..", "/", "\\"} anywhere a single path component is
     * expected). This is the path the Forge loader uses — it places
     * the resulting file under {@code <packRoot>/libraries} and
     * therefore needs to ensure the result stays inside that root.
     *
     * @param coord raw Maven coordinate string, possibly with
     *              {@code [brackets]} and/or {@code @ext} suffix
     * @return parsed {@link Coord}
     *
     * @throws ModpackException when the input doesn't parse or
     *                          contains a path-traversal sequence
     */
    public static Coord parseStrict( String coord ) throws ModpackException
    {
        if ( coord == null ) {
            throw new ModpackException( "Maven coordinate is null" );
        }
        String working = coord;
        if ( working.startsWith( "[" ) && working.endsWith( "]" ) ) {
            working = working.substring( 1, working.length() - 1 );
        }
        String ext = "jar";
        int atIdx = working.indexOf( '@' );
        if ( atIdx >= 0 ) {
            ext = working.substring( atIdx + 1 );
            working = working.substring( 0, atIdx );
        }
        String[] parts = working.split( ":" );
        if ( parts.length < 3 ) {
            throw new ModpackException(
                    "Invalid Maven coordinate (expected group:artifact:version): " + coord );
        }
        String group = parts[ 0 ];
        String artifact = parts[ 1 ];
        String version = parts[ 2 ];
        String classifier = parts.length > 3 ? parts[ 3 ] : null;

        // Reject "..", "/" and "\" in every component (group's dots are translated to
        // path separators downstream, but a literal slash/backslash in any component
        // could still inject an extra path segment), matching the classifier/ext checks.
        if ( hasTraversalChars( group ) || hasTraversalChars( artifact ) || hasTraversalChars( version ) ) {
            throw new ModpackException( "Path traversal detected in Maven coordinate: " + coord );
        }
        if ( classifier != null && hasTraversalChars( classifier ) ) {
            throw new ModpackException( "Path traversal detected in Maven classifier: " + coord );
        }
        if ( hasTraversalChars( ext ) ) {
            throw new ModpackException( "Path traversal detected in Maven extension: " + coord );
        }
        return new Coord( group, artifact, version, classifier, ext );
    }

    /**
     * Returns true if the given coordinate component contains characters that could
     * escape or extend the libraries root: {@code ".."}, {@code "/"}, or {@code "\"}.
     */
    private static boolean hasTraversalChars( String component )
    {
        return component.contains( ".." )
                || component.indexOf( '/' ) >= 0
                || component.indexOf( '\\' ) >= 0;
    }

    /**
     * Lenient parse — returns null on any structural problem instead
     * of throwing. Matches the historical Fabric behaviour where a
     * bad coordinate is just skipped over rather than fatal. Path
     * traversal still throws (silently dropping a traversal attempt
     * is worse than crashing — better to fail loud on a likely attack).
     */
    public static Coord parseOrNull( String coord )
    {
        try {
            return parseStrict( coord );
        }
        catch ( ModpackException ex ) {
            // Bubble up traversal errors — they signal something
            // adversarial, not a benign malformed input.
            if ( ex.getMessage() != null && ex.getMessage().contains( "Path traversal" ) ) {
                throw new RuntimeException( ex );
            }
            return null;
        }
    }

    /** Convenience: strict parse + direct relative-path conversion. */
    public static String toRelativePathStrict( String coord ) throws ModpackException
    {
        return parseStrict( coord ).toRelativePath();
    }

    /** Convenience: lenient parse + direct relative-path conversion;
     *  returns null when the coordinate doesn't parse. */
    public static String toRelativePathOrNull( String coord )
    {
        Coord c = parseOrNull( coord );
        return c == null ? null : c.toRelativePath();
    }
}
