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

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps text on Material 3's type scale: every font size in the stylesheets is one of the scale's
 * sizes, and FXML sets no fonts of its own (a {@code <Font>} element bypasses the stylesheet and
 * every theme). Use a {@code type-*} class from {@code ui-base.css} instead.
 *
 * @since 2026.10
 */
class TypeScaleTest
{
    /** label-small 11, body-small / label-medium 12, body-medium 14, body-large / title-medium 16,
     *  title-large 22, headline-small 24, headline-medium 28. */
    private static final Set< String > SCALE = Set.of( "11", "12", "14", "16", "22", "24", "28" );

    @Test
    void stylesheetFontSizesAreOnTheScale() throws IOException
    {
        List< String > off = new ArrayList<>();
        try ( Stream< Path > sheets = Files.list( Path.of( "src/main/resources/ui" ) ) ) {
            for ( Path sheet : sheets.filter( p -> p.toString().endsWith( ".css" ) ).toList() ) {
                String css = Files.readString( sheet ).replaceAll( "(?s)/\\*.*?\\*/", "" );
                Matcher m = Pattern.compile( "-fx-font-size\\s*:\\s*([0-9.]+)(px)?\\s*;" ).matcher( css );
                while ( m.find() ) {
                    if ( !SCALE.contains( m.group( 1 ) ) ) {
                        off.add( sheet.getFileName() + ": " + m.group().trim() );
                    }
                }
            }
        }
        assertTrue( off.isEmpty(), "Font sizes off the type scale " + SCALE + ":\n  " + String.join( "\n  ", off ) );
    }

    @Test
    void fxmlSetsNoFonts() throws IOException
    {
        List< String > off = new ArrayList<>();
        try ( Stream< Path > files = Files.walk( Path.of( "src/main/resources/gui" ) ) ) {
            for ( Path file : files.filter( p -> p.toString().endsWith( ".fxml" ) ).toList() ) {
                if ( Files.readString( file ).contains( "<Font" ) ) {
                    off.add( file.getFileName().toString() );
                }
            }
        }
        assertTrue( off.isEmpty(), "FXML <Font> elements; use a type-* style class instead: " + off );
    }

    /**
     * JavaFX only tells regular from bold within a font family, so weight 600 has to name the
     * bundled "Inter SemiBold" face (see BundledFonts); any other weight would silently render
     * regular.
     */
    @Test
    void semiboldNamesItsFaceAndNoOtherWeightsAreUsed() throws IOException
    {
        String css = Files.readString( Path.of( "src/main/resources/ui/ui-base.css" ) )
                          .replaceAll( "(?s)/\\*.*?\\*/", "" );
        List< String > off = new ArrayList<>();
        Matcher rule = Pattern.compile( "([^{}]+)\\{([^{}]*)}" ).matcher( css );
        while ( rule.find() ) {
            Matcher weight = Pattern.compile( "-fx-font-weight\\s*:\\s*([^;]+);" ).matcher( rule.group( 2 ) );
            if ( !weight.find() ) {
                continue;
            }
            String value = weight.group( 1 ).trim();
            String selector = rule.group( 1 ).trim().replaceAll( "\\s+", " " );
            if ( !Set.of( "normal", "400", "600", "700", "bold" ).contains( value ) ) {
                off.add( selector + ": weight " + value );
            }
            else if ( value.equals( "600" ) && !rule.group( 2 ).matches( "(?s).*-fx-font-family\\s*:\\s*\"Inter SemiBold\".*" ) ) {
                off.add( selector + ": weight 600 without -fx-font-family: \"Inter SemiBold\", ..." );
            }
        }
        assertTrue( off.isEmpty(), String.join( "\n  ", off ) );
    }
}
