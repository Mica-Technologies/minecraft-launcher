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

package com.micatechnologies.minecraft.launcher.consts.localization;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Every localization key the code names literally must exist in the English source bundle.
 *
 * <p>A key that is referenced but never added to {@code DisplayStrings.properties} renders as
 * the raw key name (or "key [args]") in every language, and nothing else catches it: the
 * bundle parity test only compares the bundles with each other. This scans the sources for
 * the unambiguous reference forms and checks each one:</p>
 * <ul>
 *   <li>{@code LocalizationManager.get/format/getOr( "key" ... )} with a whole literal key
 *       (a literal followed by {@code +} is a dynamic prefix and is skipped);</li>
 *   <li>the per-class wrappers {@code loc( "key" )}, {@code locf( "key", ... )} and
 *       {@code localize( "key", ... )};</li>
 *   <li>{@code %key} attribute values in FXML.</li>
 * </ul>
 *
 * <p>The scan reads the source tree relative to the Maven base directory and is skipped (not
 * failed) when run from somewhere that tree is not visible.</p>
 */
class LocalizationKeyReferencesTest
{
    /** {@code LocalizationManager.get/format/getOr( "key" )}, the literal closed by {@code ,} or {@code )}. */
    private static final Pattern MANAGER_CALL = Pattern.compile(
            "LocalizationManager\\s*\\.\\s*(?:get|format|getOr)\\s*\\(\\s*\"([^\"]+)\"\\s*[,)]" );

    /** The per-class wrapper helpers ({@code TuiApp.loc/locf}, the editor's {@code localize}). */
    private static final Pattern WRAPPER_CALL = Pattern.compile(
            "(?<![.\\w])(?:loc|locf|localize)\\s*\\(\\s*\"([^\"]+)\"\\s*[,)]" );

    /** An FXML attribute whose whole value is a resource-bundle reference. */
    private static final Pattern FXML_KEY = Pattern.compile( "=\"%([^\"]+)\"" );

    @Test
    void everyLiteralKeyReferenceExistsInTheEnglishBundle()
            throws IOException
    {
        Path javaRoot = Paths.get( "src", "main", "java" );
        Path fxmlRoot = Paths.get( "src", "main", "resources" );
        assumeTrue( Files.isDirectory( javaRoot ) && Files.isDirectory( fxmlRoot ),
                    "source tree not visible from the working directory" );

        Properties english = new Properties();
        try ( InputStream in = LocalizationKeyReferencesTest.class.getResourceAsStream(
                "/lang/DisplayStrings.properties" ) ) {
            assertNotNull( in, "DisplayStrings.properties is not on the test classpath" );
            english.load( in );
        }

        List< String > missing = new ArrayList<>();
        int checked = 0;
        for ( Path file : filesEndingWith( javaRoot, ".java" ) ) {
            String source = Files.readString( file, StandardCharsets.UTF_8 );
            checked += collectMissing( file, source, MANAGER_CALL, english, missing );
            checked += collectMissing( file, source, WRAPPER_CALL, english, missing );
        }
        for ( Path file : filesEndingWith( fxmlRoot, ".fxml" ) ) {
            String source = Files.readString( file, StandardCharsets.UTF_8 );
            checked += collectMissing( file, source, FXML_KEY, english, missing );
        }

        assertTrue( checked > 1000, "suspiciously few key references found (" + checked + ")" );
        assertTrue( missing.isEmpty(), "Keys referenced in code but absent from DisplayStrings.properties: "
                + missing );
    }

    private static List< Path > filesEndingWith( Path root, String suffix )
            throws IOException
    {
        try ( Stream< Path > walk = Files.walk( root ) ) {
            return walk.filter( p -> p.toString().endsWith( suffix ) ).toList();
        }
    }

    private static int collectMissing( Path file, String source, Pattern pattern, Properties english,
                                       List< String > missing )
    {
        int found = 0;
        Matcher m = pattern.matcher( source );
        while ( m.find() ) {
            found++;
            String key = m.group( 1 );
            if ( !english.containsKey( key ) ) {
                long line = source.substring( 0, m.start() ).chars().filter( c -> c == '\n' ).count() + 1;
                missing.add( file + ":" + line + " " + key );
            }
        }
        return found;
    }
}
