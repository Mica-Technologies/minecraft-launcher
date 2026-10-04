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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the theme token sheets: every theme defines the same tokens, and text colours meet WCAG
 * AA contrast against what they are drawn on. The {@code -md-*} Material 3 roles are generated
 * by {@code tools/theme-roles} from each theme's own {@code -color-*} palette; this guards both
 * the generator's output and hand edits to the palettes.
 *
 * @since 2026.10
 */
class ThemeTokensTest
{
    private static final String[] SHEETS = {
            "ui-tokens-dark.css", "ui-tokens-light.css", "ui-tokens-bluegray.css", "ui-tokens-orangepurple.css",
            "ui-tokens-creeper.css", "ui-tokens-native.css", "ui-tokens-native-light.css" };

    /** WCAG AA for normal text. */
    private static final double TEXT = 4.5;

    /** WCAG AA for non-text UI parts such as outlines. */
    private static final double NON_TEXT = 3.0;

    @Test
    void everyThemeDefinesTheSameTokens() throws IOException
    {
        Map< String, String > reference = tokens( SHEETS[ 0 ] );
        for ( String sheet : SHEETS ) {
            assertEquals( reference.keySet(), tokens( sheet ).keySet(), sheet + " defines a different token set" );
        }
        assertTrue( reference.keySet().stream().anyMatch( k -> k.startsWith( "-md-" ) ),
                    "no generated -md-* roles; run tools/theme-roles" );
    }

    @Test
    void textMeetsContrastOnEveryRoleItIsDrawnOn() throws IOException
    {
        List< String > failures = new ArrayList<>();
        for ( String sheet : SHEETS ) {
            Map< String, String > t = tokens( sheet );
            // Translucent (Native) surfaces are judged over the theme's opaque popup colour, the
            // closest stand-in for the OS backdrop they sit on.
            int backdrop = rgb( resolve( t, "-color-popup" ), 0 );
            for ( String group : new String[]{ "primary", "secondary", "error", "success", "warning", "tertiary" } ) {
                check( failures, sheet, t, "-md-on-" + group, "-md-" + group, backdrop, TEXT );
                check( failures, sheet, t, "-md-on-" + group + "-container", "-md-" + group + "-container", backdrop,
                       TEXT );
            }
            for ( String surface : new String[]{ "-md-surface-container-lowest", "-md-surface-container-low",
                                                 "-md-surface-container", "-md-surface-container-high",
                                                 "-md-surface-container-highest" } ) {
                check( failures, sheet, t, "-md-on-surface", surface, backdrop, TEXT );
                check( failures, sheet, t, "-md-on-surface-variant", surface, backdrop, TEXT );
            }
            check( failures, sheet, t, "-md-outline", "-md-surface-container", backdrop, NON_TEXT );
            check( failures, sheet, t, "-md-inverse-on-surface", "-md-inverse-surface", backdrop, TEXT );
            // The live tokens the current stylesheets use for text on primary buttons.
            check( failures, sheet, t, "-color-text-on-primary", "-color-primary", backdrop, TEXT );
            check( failures, sheet, t, "-color-text", "-color-popup", backdrop, TEXT );
        }
        assertTrue( failures.isEmpty(), "Contrast below WCAG AA:\n  " + String.join( "\n  ", failures ) );
    }

    @Test
    void contrastMatchesKnownValues()
    {
        assertEquals( 21.0, contrast( 0xFFFFFF, 0x000000 ), 0.01 );
        assertEquals( 1.0, contrast( 0x777777, 0x777777 ), 0.01 );
        // 50% white over black is mid grey.
        assertEquals( 0x808080, rgb( "rgba(255, 255, 255, 0.5)", 0x000000 ), 0x010101 );
    }

    private static void check( List< String > failures, String sheet, Map< String, String > t, String fg, String bg,
                               int backdrop, double minimum )
    {
        int background = rgb( resolve( t, bg ), backdrop );
        double ratio = contrast( rgb( resolve( t, fg ), background ), background );
        if ( ratio < minimum ) {
            failures.add( String.format( "%s: %s on %s = %.2f (needs %.1f)", sheet, fg, bg, ratio, minimum ) );
        }
    }

    /** Follows one level of lookup, e.g. {@code -color-popup: -md-surface-container-high}. */
    private static String resolve( Map< String, String > t, String token )
    {
        String value = t.get( token );
        return value != null && value.startsWith( "-" ) ? t.get( value ) : value;
    }

    /** The tokens declared in a sheet's {@code .root} block, in order. */
    static Map< String, String > tokens( String sheet ) throws IOException
    {
        String css = Files.readString( Path.of( "src/main/resources/ui", sheet ) ).replaceAll( "(?s)/\\*.*?\\*/", "" );
        Matcher root = Pattern.compile( "\\.root\\s*\\{([^}]*)}" ).matcher( css );
        assertTrue( root.find(), sheet + " has no .root block" );
        Map< String, String > out = new LinkedHashMap<>();
        Matcher decl = Pattern.compile( "(-(?:color|md)-[a-z0-9-]+)\\s*:\\s*([^;]+);" ).matcher( root.group( 1 ) );
        while ( decl.find() ) {
            out.put( decl.group( 1 ), decl.group( 2 ).trim() );
        }
        return out;
    }

    /** A CSS colour as opaque RGB, composited over {@code backdrop} when translucent. */
    static int rgb( String css, int backdrop )
    {
        String value = css.trim().toLowerCase();
        if ( value.equals( "white" ) ) {
            return 0xFFFFFF;
        }
        if ( value.equals( "black" ) ) {
            return 0x000000;
        }
        if ( value.startsWith( "#" ) ) {
            return Integer.parseInt( value.substring( 1 ), 16 );
        }
        Matcher m = Pattern.compile( "rgba?\\(([^)]*)\\)" ).matcher( value );
        if ( !m.find() ) {
            throw new IllegalArgumentException( "Unsupported colour: " + css );
        }
        String[] parts = m.group( 1 ).split( "," );
        double alpha = parts.length > 3 ? Double.parseDouble( parts[ 3 ].trim() ) : 1.0;
        int out = 0;
        for ( int i = 0; i < 3; i++ ) {
            int over = Integer.parseInt( parts[ i ].trim() );
            int under = ( backdrop >> ( 16 - 8 * i ) ) & 0xFF;
            out = ( out << 8 ) | (int) Math.round( over * alpha + under * ( 1 - alpha ) );
        }
        return out;
    }

    /** WCAG 2 contrast ratio between two opaque RGB colours. */
    static double contrast( int a, int b )
    {
        double la = luminance( a );
        double lb = luminance( b );
        return ( Math.max( la, lb ) + 0.05 ) / ( Math.min( la, lb ) + 0.05 );
    }

    private static double luminance( int rgb )
    {
        double[] c = new double[ 3 ];
        for ( int i = 0; i < 3; i++ ) {
            double s = ( ( rgb >> ( 16 - 8 * i ) ) & 0xFF ) / 255.0;
            c[ i ] = s <= 0.03928 ? s / 12.92 : Math.pow( ( s + 0.055 ) / 1.055, 2.4 );
        }
        return 0.2126 * c[ 0 ] + 0.7152 * c[ 1 ] + 0.0722 * c[ 2 ];
    }
}
