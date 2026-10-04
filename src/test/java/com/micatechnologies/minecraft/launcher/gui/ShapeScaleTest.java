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


package com.micatechnologies.minecraft.launcher.gui;

import javafx.scene.shape.Rectangle;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps every corner radius on Material 3's shape scale ({@link ShapeScale}): CSS radii take only
 * scale values, and Java rounds clips only through {@link ShapeScale#round}.
 *
 * @since 2026.10
 */
class ShapeScaleTest
{
    /** 0 (square), the five scale steps, and 999 (fully round). */
    private static final Set< String > SCALE = Set.of( "0", "4", "8", "12", "16", "28", "999" );

    @Test
    void stylesheetRadiiAreOnTheScale() throws IOException
    {
        List< String > off = new ArrayList<>();
        try ( Stream< Path > sheets = Files.list( Path.of( "src/main/resources/ui" ) ) ) {
            for ( Path sheet : sheets.filter( p -> p.toString().endsWith( ".css" ) ).toList() ) {
                String css = Files.readString( sheet ).replaceAll( "(?s)/\\*.*?\\*/", "" );
                Matcher m = Pattern.compile( "-fx-(?:background|border)-radius\\s*:\\s*([^;]+);" ).matcher( css );
                while ( m.find() ) {
                    for ( String value : m.group( 1 ).replace( "!important", "" ).trim().split( "[\\s,]+" ) ) {
                        if ( !SCALE.contains( value.replace( "px", "" ) ) ) {
                            off.add( sheet.getFileName() + ": " + m.group().trim() );
                            break;
                        }
                    }
                }
            }
        }
        assertTrue( off.isEmpty(), "Radii off the shape scale " + SCALE + ":\n  " + String.join( "\n  ", off ) );
    }

    @Test
    void javaRoundsClipsOnlyThroughTheScale() throws IOException
    {
        List< String > off = new ArrayList<>();
        try ( Stream< Path > files = Files.walk( Path.of( "src/main/java" ) ) ) {
            for ( Path file : files.filter( p -> p.toString().endsWith( ".java" ) ).toList() ) {
                if ( file.getFileName().toString().equals( "ShapeScale.java" ) ) {
                    continue;
                }
                String code = Files.readString( file ).replaceAll( "(?s)/\\*.*?\\*/", "" );
                if ( code.contains( ".setArcWidth(" ) || code.contains( ".setArcHeight(" ) ) {
                    off.add( file.getFileName().toString() );
                }
            }
        }
        assertTrue( off.isEmpty(), "Use ShapeScale.round for rounded clips: " + off );
    }

    @Test
    void roundSetsTheDiameterAndInnerStaysConcentric()
    {
        Rectangle clip = ShapeScale.round( new Rectangle( 10, 10 ), ShapeScale.MEDIUM );
        assertEquals( 24, clip.getArcWidth() );
        assertEquals( 24, clip.getArcHeight() );
        assertEquals( 12, ShapeScale.inner( ShapeScale.LARGE, 4 ) );
        assertEquals( 0, ShapeScale.inner( ShapeScale.EXTRA_SMALL, 8 ) );
    }
}
