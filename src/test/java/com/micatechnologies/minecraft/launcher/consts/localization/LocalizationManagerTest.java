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

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Behavioural tests for {@link LocalizationManager}'s dynamic lookup API —
 * {@link LocalizationManager#get(String)}, {@link LocalizationManager#getOr(String, String)},
 * and {@link LocalizationManager#format(String, Object...)}.
 *
 * <h3>Why only the dynamic API</h3>
 * <p>{@code LocalizationManager} also exposes ~89 legacy {@code public
 * static final String} fields (e.g. {@code COMPLETED_TEXT}) that are bound
 * once, at class-initialization time, to whatever {@link Locale#getDefault()}
 * happened to be at that moment — see the class javadoc on
 * {@code LocalizationManager} and {@link LocaleBootstrap}. Those fields
 * cannot be exercised meaningfully by a runtime locale switch: calling
 * {@link LocalizationManager#setLocale} updates the <em>active bundle</em>
 * used by {@code get}/{@code format}, but every one of those static-final
 * fields keeps the value it captured at class load and will never change
 * for the lifetime of the JVM. A test that switched locale and then
 * re-read {@code LocalizationManager.COMPLETED_TEXT} expecting it to track
 * the switch would be asserting on a behaviour the class deliberately
 * does not have — not a bug. This class does not attempt that; it only
 * exercises {@code get}/{@code getOr}/{@code format}.</p>
 *
 * <h3>Why {@code setLocale(Locale.US)} in {@code @BeforeAll} is still needed</h3>
 * <p>The <em>active bundle</em> that {@code get}/{@code format} read from
 * is itself seeded from {@link Locale#getDefault()} the first time
 * {@code LocalizationManager} loads, and every locale-specific
 * {@code DisplayStrings_*.properties} file in this repo is written in a
 * different language than English. Without pinning the locale, these
 * tests would pass or fail depending on the machine's OS-configured
 * default locale — non-deterministic and untrue to what's being tested.
 * Calling {@link LocalizationManager#setLocale(Locale)} explicitly is the
 * supported way to point the <em>dynamic</em> lookup path at a known
 * locale (English) without touching the frozen static-final fields, and
 * is restored in {@code @AfterAll} since Surefire may reuse this JVM for
 * other test classes.</p>
 */
class LocalizationManagerTest
{
    private static Locale originalDefaultLocale;

    private static java.util.Properties english;

    @BeforeAll
    static void pinEnglishLocale()
            throws IOException
    {
        originalDefaultLocale = Locale.getDefault();
        LocalizationManager.setLocale( Locale.US );

        english = new java.util.Properties();
        try ( InputStream is = LocalizationManagerTest.class.getResourceAsStream(
                "/lang/DisplayStrings.properties" ) ) {
            assertNotNull( is, "English source bundle missing from classpath" );
            english.load( is );
        }
    }

    @AfterAll
    static void restoreOriginalLocale()
    {
        LocalizationManager.setLocale( originalDefaultLocale );
    }

    @Test
    void get_knownLegacyKey_returnsEnglishValue()
    {
        assertEquals( english.getProperty( "COMPLETED_TEXT" ), LocalizationManager.get( "COMPLETED_TEXT" ) );
    }

    @Test
    void get_knownDotNamespacedKey_returnsEnglishValue()
    {
        // main.card.lastPlayed carries a MessageFormat slot in its raw
        // template ("Last played {0}") — get() must return the raw
        // template unsubstituted; substitution is format()'s job.
        assertEquals( english.getProperty( "main.card.lastPlayed" ),
                LocalizationManager.get( "main.card.lastPlayed" ) );
    }

    @Test
    void get_missingKey_returnsTheKeyItself()
    {
        // Real behaviour per source: MissingResourceException is caught
        // and the key itself is returned, so an untranslated string
        // surfaces in the UI as a visible "KEY_NAME" rather than crashing
        // or silently rendering empty.
        String missingKey = "this.key.definitely.does.not.exist.anywhere";
        assertEquals( missingKey, LocalizationManager.get( missingKey ) );
    }

    @Test
    void get_nullKey_returnsEmptyString()
    {
        assertEquals( "", LocalizationManager.get( null ) );
    }

    @Test
    void getOr_missingKey_returnsSuppliedFallback()
    {
        String fallback = "fallback-value-should-appear";
        assertEquals( fallback,
                LocalizationManager.getOr( "this.key.definitely.does.not.exist.anywhere", fallback ) );
    }

    @Test
    void getOr_knownKey_returnsEnglishValueIgnoringFallback()
    {
        assertEquals( english.getProperty( "COMPLETED_TEXT" ),
                LocalizationManager.getOr( "COMPLETED_TEXT", "SHOULD_NOT_APPEAR" ) );
    }

    @Test
    void getOr_nullKey_returnsSuppliedFallback()
    {
        String fallback = "fallback-for-null-key";
        assertEquals( fallback, LocalizationManager.getOr( null, fallback ) );
    }

    @Test
    void format_singlePlaceholder_substitutesArg()
    {
        // English template: "Last played {0}"
        String expected = english.getProperty( "main.card.lastPlayed" ).replace( "{0}", "Yesterday" );
        assertEquals( expected, LocalizationManager.format( "main.card.lastPlayed", "Yesterday" ) );
    }

    @Test
    void format_multiplePlaceholders_substitutesInOrder()
    {
        // English template: "Page {0} of {1}"
        assertEquals( "Page 2 of 5", LocalizationManager.format( "main.pagination.pageOfPages", 2, 5 ) );
    }

    @Test
    void format_threePlaceholders_substitutesAllInOrder()
    {
        // English template: "RGB renderFrame on {0} threw (health={1}, failures={2})"
        assertEquals( "RGB renderFrame on OpenRGB threw (health=42, failures=3)",
                LocalizationManager.format( "log.rgb.controller.renderFrameThrew", "OpenRGB", 42, 3 ) );
    }

    @Test
    void format_noArgs_returnsRawTemplateWithPlaceholdersUnsubstituted()
    {
        // Real behaviour per source: args.length == 0 short-circuits
        // before MessageFormat ever runs, so the literal "{0}" stays in
        // the string rather than being treated as "no value available".
        assertEquals( english.getProperty( "main.card.lastPlayed" ),
                LocalizationManager.format( "main.card.lastPlayed" ) );
    }

    @Test
    void format_tooFewArgs_leavesUnfilledSlotAsLiteralBraces()
    {
        // Real behaviour per java.text.MessageFormat: an argument index
        // referenced by the pattern but absent from the array is left as
        // literal "{1}" text rather than throwing or blanking it out.
        // Verified directly against java.text.MessageFormat before
        // writing this assertion.
        assertEquals( "Page 2 of {1}", LocalizationManager.format( "main.pagination.pageOfPages", 2 ) );
    }

    @Test
    void format_tooManyArgs_ignoresExtraArgs()
    {
        // Real behaviour per java.text.MessageFormat: extra trailing
        // arguments with no corresponding {n} slot in the pattern are
        // silently ignored.
        assertEquals( "Last played Yesterday",
                LocalizationManager.format( "main.card.lastPlayed", "Yesterday", "unused-extra-arg" ) );
    }

    @Test
    void format_missingKeyWithNoBraceSyntax_returnsKeyUnchangedAndSilentlyDropsArgs()
    {
        // Real behaviour, and worth calling out because it reads as a
        // surprise next to the class javadoc's claim that a missing key
        // "returns the key followed by the args joined with commas": that
        // debug-string fallback only fires when MessageFormat.format
        // itself throws IllegalArgumentException (malformed pattern
        // syntax in the key text). A plain dot-namespaced key has no "{"
        // in it at all, so MessageFormat.format treats it as a pattern
        // with zero placeholders and returns it completely unchanged —
        // the supplied args are silently discarded, not appended.
        // Verified directly against java.text.MessageFormat before
        // writing this assertion.
        String missingKey = "this.key.definitely.does.not.exist.anywhere";
        assertEquals( missingKey, LocalizationManager.format( missingKey, "unused-arg" ) );
    }

    @Test
    void format_missingKeyWithMalformedPlaceholderSyntax_returnsDebugStringWithArgs()
    {
        // The one shape of missing key that DOES trigger the
        // IllegalArgumentException catch block described in the class
        // javadoc: the "key" text itself parses as a MessageFormat
        // pattern with invalid syntax (an unknown format type). Exercises
        // the debug fallback path directly.
        String malformedKey = "missing.key.{0,badtype}";
        assertEquals( malformedKey + " [arg1]", LocalizationManager.format( malformedKey, "arg1" ) );
    }

    @Test
    void currentBundle_isNotNullAndBackedByEnglishAfterPinning()
    {
        assertNotNull( LocalizationManager.currentBundle() );
        assertEquals( english.getProperty( "COMPLETED_TEXT" ),
                LocalizationManager.currentBundle().getString( "COMPLETED_TEXT" ) );
    }

    @Test
    void apostrophesInTemplatesSurviveFormatting()
    {
        // MessageFormat treats ' as a quote: "l'outil {0}" used to render "loutil {0}".
        String template = LocalizationManager.escapeApostrophes( "l'outil {0} n'a pas démarré" );
        assertEquals( "l'outil X n'a pas démarré", java.text.MessageFormat.format( template, "X" ) );
    }

    @Test
    void templatesWithoutApostrophesAreUntouched()
    {
        String template = "Page {0} of {1}";
        assertSame( template, LocalizationManager.escapeApostrophes( template ) );
    }
}
