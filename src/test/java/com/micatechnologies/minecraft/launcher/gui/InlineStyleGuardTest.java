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
 * Keeps fixed styling out of inline styles. The theme can't reach a value set with
 * {@code setStyle(...)} or an FXML {@code style="..."} attribute, so a font size, text colour,
 * padding, radius or literal colour set that way silently escapes every theme and the Material 3
 * refresh. Those belong in a CSS class in {@code ui-base.css}. Inline styles stay for values
 * computed at runtime: background images, gradients, window transparency, title-bar insets,
 * theme swatches.
 *
 * @since 2026.10
 */
class InlineStyleGuardTest
{
    /** Properties that are always fixed design values, never computed at runtime. */
    private static final Pattern FIXED_PROPERTY = Pattern.compile(
            "-fx-(font-size|font-weight|font-family|font-style|text-fill|padding|background-radius|border-radius)\\b" );

    /** A literal hex colour, e.g. {@code #0C1017}. */
    private static final Pattern HEX_COLOUR = Pattern.compile( "#[0-9A-Fa-f]{3,8}\\b" );

    /**
     * Inline styles that are allowed to break the rule, with the reason.
     * <ul>
     *     <li>The cold-start placeholder is painted before any stylesheet is loaded, so it can't
     *     use a token.</li>
     * </ul>
     */
    private static final Set< String > ALLOWED = Set.of(
            "placeholder.setStyle( \"-fx-background-color: #0C1017;\" )" );

    @Test
    void javaSetsNoFixedStylesInline() throws IOException
    {
        List< String > offenders = new ArrayList<>();
        try ( Stream< Path > files = Files.walk( Path.of( "src/main/java" ) ) ) {
            for ( Path file : files.filter( p -> p.toString().endsWith( ".java" ) ).toList() ) {
                String source = Files.readString( file );
                for ( String call : setStyleCalls( source ) ) {
                    if ( !ALLOWED.contains( call ) && breaksRule( call ) ) {
                        offenders.add( file.getFileName() + ": " + call );
                    }
                }
            }
        }
        assertTrue( offenders.isEmpty(),
                    "Fixed styling set inline; use a CSS class in ui-base.css instead:\n  "
                    + String.join( "\n  ", offenders ) );
    }

    @Test
    void fxmlHasNoInlineStyles() throws IOException
    {
        List< String > offenders = new ArrayList<>();
        Pattern style = Pattern.compile( "\\sstyle=\"[^\"]*\"" );
        try ( Stream< Path > files = Files.walk( Path.of( "src/main/resources/gui" ) ) ) {
            for ( Path file : files.filter( p -> p.toString().endsWith( ".fxml" ) ).toList() ) {
                Matcher m = style.matcher( Files.readString( file ) );
                while ( m.find() ) {
                    offenders.add( file.getFileName() + ":" + m.group().strip() );
                }
            }
        }
        assertTrue( offenders.isEmpty(),
                    "FXML style attributes; use styleClass instead:\n  " + String.join( "\n  ", offenders ) );
    }

    @Test
    void ruleCatchesWhatItShould()
    {
        assertTrue( breaksRule( "label.setStyle( \"-fx-font-size: 11px;\" )" ) );
        assertTrue( breaksRule( "cell.setStyle( \"-fx-text-fill: \" + colour + \";\" )" ) );
        assertTrue( breaksRule( "box.setStyle( \"-fx-background-color: #1E2533;\" )" ) );
        assertTrue( !breaksRule( "bgLayer.setStyle( existing + \" -fx-background-image: url('\" + url + \"');\" )" ) );
        assertTrue( !breaksRule( "root.setStyle( \"-fx-background-color: \" + bg + \";\" )" ) );
    }

    /** Whether an inline style sets a fixed design value. */
    static boolean breaksRule( String call )
    {
        return FIXED_PROPERTY.matcher( call ).find() || HEX_COLOUR.matcher( call ).find();
    }

    /** Every {@code setStyle( ... )} call in the source, comments excluded, normalised to one line. */
    static List< String > setStyleCalls( String source )
    {
        String code = source.replaceAll( "(?s)/\\*.*?\\*/", "" ).replaceAll( "//[^\n]*", "" );
        List< String > calls = new ArrayList<>();
        int at = code.indexOf( ".setStyle(" );
        while ( at >= 0 ) {
            int start = at;
            while ( start > 0 && Character.isJavaIdentifierPart( code.charAt( start - 1 ) ) ) {
                start--;
            }
            int depth = 0;
            int end = code.indexOf( '(', at );
            boolean inString = false;
            for ( ; end < code.length(); end++ ) {
                char c = code.charAt( end );
                if ( c == '"' && code.charAt( end - 1 ) != '\\' ) {
                    inString = !inString;
                }
                else if ( !inString && c == '(' ) {
                    depth++;
                }
                else if ( !inString && c == ')' && --depth == 0 ) {
                    break;
                }
            }
            calls.add( code.substring( start, Math.min( end + 1, code.length() ) ).replaceAll( "\\s+", " " ) );
            at = code.indexOf( ".setStyle(", end );
        }
        return calls;
    }
}
