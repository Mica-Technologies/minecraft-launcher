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

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parity checks between {@code DisplayStrings.properties} (the English
 * source of truth) and its 16 per-locale companions listed in
 * {@link SupportedLocales#ENTRIES}.
 *
 * <p>These files are hand-off targets for an auto-translator
 * ({@code tools/i18n/translate-locales.js}) that nobody proofreads line by
 * line. A key added to English and never re-translated, a key dropped from
 * a translation pass, or a {@code {0}}/{@code {1}} slot mangled by the
 * translation round-trip are all invisible until a user actually switches
 * the launcher to that language — at which point they see either the raw
 * key name, missing dynamic content, or a runtime {@link MessageFormat}
 * glitch. This class is the first automated guard against that class of
 * bug.</p>
 *
 * <h3>Two different notions of "the locale file"</h3>
 * <p>This class distinguishes between:
 * <ul>
 *   <li><b>The shipped file</b> — the actual resource on the classpath,
 *       loaded here by its literal on-disk name,
 *       {@code DisplayStrings_<entry.tag()>.properties} (e.g.
 *       {@code DisplayStrings_pt_BR.properties}, underscore as
 *       BCP-47 tag is written). The key-parity and placeholder checks below
 *       audit the content of this file, because the content is real and
 *       shipped regardless of whether the runtime can currently reach it.</li>
 *   <li><b>The runtime-resolved bundle</b> — what
 *       {@code LocalizationManager}'s {@code ResourceBundle.getBundle(
 *       "lang.DisplayStrings", locale)} call actually finds for a given
 *       {@link SupportedLocales.Entry#toLocale()}. The JDK's default
 *       {@link ResourceBundle} lookup builds candidate file suffixes from
 *       {@link Locale#toString()}, which separates language/country with an
 *       <em>underscore</em> (e.g. {@code Locale.forLanguageTag("pt-BR")
 *       .toString()} is {@code "pt_BR"}), not a hyphen. See
 *       {@link #everySupportedLocaleResolvesItsOwnBundleAtRuntime()} for
 *       what this mismatch means in practice — confirmed empirically before
 *       writing this test by round-tripping a scratch bundle through
 *       {@code ResourceBundle.getBundle} both with and without an
 *       underscore-named file present.</li>
 * </ul>
 */
class DisplayStringsBundleParityTest
{
    private static final String BUNDLE_BASE_NAME = "lang.DisplayStrings";

    private static final String ENGLISH_RESOURCE_PATH = "/lang/DisplayStrings.properties";

    /** Matches a MessageFormat numbered slot like {@code {0}} or
     *  {@code {1,number,integer}} and captures just the index. */
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile( "\\{(\\d+)" );

    private static Properties english;

    @BeforeAll
    static void loadEnglishSource()
            throws IOException
    {
        english = loadProperties( ENGLISH_RESOURCE_PATH );
    }

    /**
     * Loads a {@code .properties} resource from the test classpath by its
     * exact path. {@link Properties#load(InputStream)} decodes
     * {@code \\uXXXX} escapes regardless of the declared stream encoding,
     * and every non-ASCII character in these bundles is already escaped
     * that way by {@code translate-locales.js}, so a plain
     * {@code InputStream} load is faithful to what the JVM sees at runtime.
     */
    private static Properties loadProperties( String classpathResourcePath )
            throws IOException
    {
        Properties props = new Properties();
        try ( InputStream is = DisplayStringsBundleParityTest.class.getResourceAsStream( classpathResourcePath ) ) {
            assertNotNull( is, "Resource missing from classpath: " + classpathResourcePath );
            props.load( is );
        }
        return props;
    }

    /**
     * Loads the shipped locale file for {@code entry} by its on-disk name.
     *
     * <p>The filename uses Java's resource-bundle convention, which separates language
     * from region with an UNDERSCORE ({@code DisplayStrings_pt_BR.properties}) rather than
     * the BCP-47 hyphen used by the tag itself ({@code pt-BR}). That distinction is the
     * whole substance of the bug this class guards: naming the file after the tag verbatim
     * makes {@code ResourceBundle.getBundle} miss it entirely and fall back to English.
     * {@link #bundleSuffixFor(String)} is the single place that conversion happens.</p>
     */
    private static Properties loadShippedLocaleFile( SupportedLocales.Entry entry )
            throws IOException
    {
        return loadProperties( "/lang/DisplayStrings_" + bundleSuffixFor( entry.tag() ) + ".properties" );
    }

    /**
     * Converts a BCP-47 tag to the resource-bundle filename suffix Java looks for, i.e.
     * {@code Locale#toString()} form. Mirrors the same conversion in
     * {@code tools/i18n/translate-locales.js}; if the two ever diverge, the affected
     * locale silently ships as English and
     * {@link #everySupportedLocaleResolvesItsOwnBundleAtRuntime()} is what catches it.
     */
    private static String bundleSuffixFor( String tag )
    {
        return tag.replace( '-', '_' );
    }

    /** Extracts the set of MessageFormat slot indices referenced by
     *  {@code value} (e.g. {@code "Page {0} of {1}"} → {@code {0, 1}}). */
    private static Set< Integer > extractPlaceholderIndices( String value )
    {
        Set< Integer > indices = new TreeSet<>();
        Matcher matcher = PLACEHOLDER_PATTERN.matcher( value );
        while ( matcher.find() ) {
            indices.add( Integer.valueOf( matcher.group( 1 ) ) );
        }
        return indices;
    }

    /**
     * Every locale in {@link SupportedLocales#ENTRIES} must have a
     * corresponding {@code .properties} file that {@code ResourceBundle
     * .getBundle("lang.DisplayStrings", locale)} — the exact call
     * {@code LocalizationManager} makes — actually resolves to. A bundle
     * that falls all the way back to the root/English bundle reports a
     * locale with an empty {@link Locale#getLanguage()}; that's the
     * observable signature of "the runtime never found this file."
     *
     * <p><b>Regression guard for a shipped bug, now fixed.</b> {@code pt-BR},
     * {@code zh-CN} and {@code zh-TW} originally shipped as
     * {@code DisplayStrings_pt-BR.properties} and friends — the BCP-47 tag verbatim.
     * But {@link Locale#forLanguageTag(String)} followed by {@link Locale#toString()}
     * renders those locales with an <em>underscore</em> ({@code pt_BR}, {@code zh_CN},
     * {@code zh_TW}), and that is the suffix {@link ResourceBundle}'s default
     * {@code Control} looks for on the classpath. The hyphen-named files were therefore
     * never consulted: {@code getBundle} fell all the way back to plain
     * {@code DisplayStrings.properties}, so selecting "Português (Brasil)", "简体中文" or
     * "繁體中文" in Settings produced an English UI with no error or warning.</p>
     *
     * <p>Fixed by renaming the three files to the underscore form Java has always used
     * for resource bundles, and by teaching {@code tools/i18n/translate-locales.js} to
     * emit that form. Tags stay hyphenated everywhere else; only the filename converts.
     * This test is the guard — if a future locale with a region subtag is added and the
     * generator regresses, it fails here rather than shipping another silently-English
     * language.</p>
     */
    @Test
    void everySupportedLocaleResolvesItsOwnBundleAtRuntime()
    {
        java.util.List< String > fellBackToRoot = new ArrayList<>();
        for ( SupportedLocales.Entry entry : SupportedLocales.ENTRIES ) {
            ResourceBundle bundle = ResourceBundle.getBundle( BUNDLE_BASE_NAME, entry.toLocale() );
            if ( bundle.getLocale().getLanguage().isEmpty() ) {
                fellBackToRoot.add( entry.tag() );
            }
        }
        assertTrue( fellBackToRoot.isEmpty(),
                "Locales whose ResourceBundle.getBundle() call fell back to the root/English "
                        + "bundle instead of resolving their own translation file: " + fellBackToRoot );
    }

    /**
     * No shipped locale file may be missing a key present in the English
     * source — a missing key means {@code LocalizationManager.get} falls
     * back to raw English for that one string, a partial-translation
     * inconsistency inside an otherwise-translated screen.
     */
    @Test
    void noShippedLocaleIsMissingKeysPresentInEnglish()
            throws IOException
    {
        Map< String, Set< String > > missingByLocale = new TreeMap<>();
        for ( SupportedLocales.Entry entry : SupportedLocales.ENTRIES ) {
            Properties locale = loadShippedLocaleFile( entry );
            Set< String > missing = new TreeSet<>( english.stringPropertyNames() );
            missing.removeAll( locale.stringPropertyNames() );
            if ( !missing.isEmpty() ) {
                missingByLocale.put( entry.tag(), missing );
            }
        }
        assertTrue( missingByLocale.isEmpty(),
                "Locales missing keys present in English (locale -> missing keys): " + missingByLocale );
    }

    /**
     * No shipped locale file may contain a key absent from the English
     * source — an orphan key is dead weight at best, and at worst a sign
     * a key was renamed in English without updating (or regenerating) the
     * translations, leaving the old name stuck untranslated forever since
     * nothing looks it up anymore.
     *
     * <p>Currently green: none of the 16 shipped locale files carry a key
     * absent from {@code DisplayStrings.properties}.</p>
     */
    @Test
    void noShippedLocaleHasOrphanKeysAbsentFromEnglish()
            throws IOException
    {
        Map< String, Set< String > > orphansByLocale = new TreeMap<>();
        for ( SupportedLocales.Entry entry : SupportedLocales.ENTRIES ) {
            Properties locale = loadShippedLocaleFile( entry );
            Set< String > orphans = new TreeSet<>( locale.stringPropertyNames() );
            orphans.removeAll( english.stringPropertyNames() );
            if ( !orphans.isEmpty() ) {
                orphansByLocale.put( entry.tag(), orphans );
            }
        }
        assertTrue( orphansByLocale.isEmpty(),
                "Locales with keys not present in English (locale -> orphan keys): " + orphansByLocale );
    }

    /**
     * For every English key that uses MessageFormat slots ({@code {0}},
     * {@code {1}}, ...), every locale's translation of that key must
     * reference the exact same set of slot indices. A translation that
     * drops a slot silently discards dynamic content at runtime; one that
     * invents a slot index the English template never used will throw
     * (or silently no-op) when {@link MessageFormat#format} runs.
     *
     * <p><b>Previously disabled; fixed 2026-09-04.</b> Five mismatches had shipped —
     * two in {@code es}, one in {@code pt-BR}, two in {@code zh-TW}. The cause was
     * {@code translate-locales.js}: it protects placeholders by swapping them for
     * {@code __MMCL_PHn__} sentinels around each API call, but the translation service does
     * not treat those as opaque — it lowercased them and injected spaces
     * ({@code __ MMCL_PH0 __}), and the exact-match restore regex then never matched, so the
     * mangled sentinel shipped in place of {@code {0}}.</p>
     *
     * <p>The translator now matches the sentinel tolerantly <em>and</em> verifies placeholder
     * parity against the English source before accepting a value, falling back to English
     * rather than shipping a broken string. This assertion is what proves the bundles are
     * clean; it stays strict on purpose.</p>
     */
    @Test
    void messageFormatPlaceholdersMatchAcrossTranslations()
            throws IOException
    {
        Map< String, java.util.List< String > > mismatchesByLocale = new TreeMap<>();
        for ( SupportedLocales.Entry entry : SupportedLocales.ENTRIES ) {
            Properties locale = loadShippedLocaleFile( entry );
            java.util.List< String > mismatches = new ArrayList<>();
            for ( String key : english.stringPropertyNames() ) {
                Set< Integer > englishSlots = extractPlaceholderIndices( english.getProperty( key ) );
                if ( englishSlots.isEmpty() ) {
                    continue;
                }
                String translatedValue = locale.getProperty( key );
                if ( translatedValue == null ) {
                    // Covered by noShippedLocaleIsMissingKeysPresentInEnglish; don't double-report.
                    continue;
                }
                Set< Integer > translatedSlots = extractPlaceholderIndices( translatedValue );
                if ( !englishSlots.equals( translatedSlots ) ) {
                    mismatches.add( key + " englishSlots=" + englishSlots + " translatedSlots=" + translatedSlots );
                }
            }
            if ( !mismatches.isEmpty() ) {
                mismatchesByLocale.put( entry.tag(), mismatches );
            }
        }
        assertTrue( mismatchesByLocale.isEmpty(),
                "Locales with MessageFormat slot mismatches (locale -> [key englishSlots=.. translatedSlots=..]): "
                        + mismatchesByLocale );
    }

    /**
     * Every translation must keep its English source's line structure and must
     * not carry a backslash the English value doesn't have. A stray backslash in
     * a <em>loaded</em> value is never intentional: it is an escape that was
     * written twice, so the user sees a literal {@code \n} or {@code \"}.
     *
     * <p>Added after exactly that shipped: {@code translate-locales.js} read
     * {@code \n} as a backslash plus {@code n} and then doubled the backslash
     * on write, so 14 multi-line dialogs showed a literal {@code \n} in all 16
     * locales, and the translation service sometimes "translated" it further
     * into {@code \ N}.</p>
     */
    @Test
    void translationsKeepEnglishLineBreaksAndAddNoBackslashes()
            throws IOException
    {
        Map< String, java.util.List< String > > problemsByLocale = new TreeMap<>();
        for ( SupportedLocales.Entry entry : SupportedLocales.ENTRIES ) {
            Properties locale = loadShippedLocaleFile( entry );
            java.util.List< String > problems = new ArrayList<>();
            for ( String key : new TreeSet<>( english.stringPropertyNames() ) ) {
                String englishValue = english.getProperty( key );
                String translatedValue = locale.getProperty( key );
                if ( translatedValue == null ) {
                    // Covered by noShippedLocaleIsMissingKeysPresentInEnglish; don't double-report.
                    continue;
                }
                if ( translatedValue.indexOf( '\\' ) >= 0 && englishValue.indexOf( '\\' ) < 0 ) {
                    problems.add( key + " has a stray backslash" );
                }
                long englishBreaks = englishValue.chars().filter( c -> c == '\n' ).count();
                long translatedBreaks = translatedValue.chars().filter( c -> c == '\n' ).count();
                if ( englishBreaks != translatedBreaks ) {
                    problems.add( key + " has " + translatedBreaks + " line breaks, English has " + englishBreaks );
                }
            }
            if ( !problems.isEmpty() ) {
                problemsByLocale.put( entry.tag(), problems );
            }
        }
        assertTrue( problemsByLocale.isEmpty(),
                "Locales with broken escapes (locale -> problems): " + problemsByLocale );
    }

    /**
     * Bundle text uses ordinary apostrophes; {@code LocalizationManager.format} escapes them.
     * A doubled {@code ''} would show up doubled wherever a string is used without formatting
     * (FXML, {@code get}).
     */
    @Test
    void noBundleUsesDoubledApostrophes()
            throws IOException
    {
        java.util.List< String > doubled = new ArrayList<>();
        for ( String key : english.stringPropertyNames() ) {
            if ( english.getProperty( key ).contains( "''" ) ) {
                doubled.add( "en:" + key );
            }
        }
        for ( SupportedLocales.Entry entry : SupportedLocales.ENTRIES ) {
            Properties locale = loadShippedLocaleFile( entry );
            for ( String key : locale.stringPropertyNames() ) {
                if ( locale.getProperty( key ).contains( "''" ) ) {
                    doubled.add( entry.tag() + ":" + key );
                }
            }
        }
        assertTrue( doubled.isEmpty(), "Doubled apostrophes: " + doubled );
    }

    /**
     * Suffix strings such as {@code " (+{0} more)"} are appended to another sentence, so their
     * leading space matters. Java's {@code Properties} drops an unescaped one ({@code key= text}),
     * which glued the suffix to the previous word. It must be escaped ({@code key=\\ text}), and
     * every locale must keep it, except Japanese and Chinese, which join phrases without spaces.
     */
    @Test
    void leadingSpacesSurviveLoadingInEveryLocale()
            throws IOException
    {
        java.util.List< String > lost = new ArrayList<>();
        java.util.Set< String > joinWithoutSpaces = java.util.Set.of( "ja", "zh-CN", "zh-TW" );
        for ( SupportedLocales.Entry entry : SupportedLocales.ENTRIES ) {
            if ( joinWithoutSpaces.contains( entry.tag() ) ) {
                continue;
            }
            Properties locale = loadShippedLocaleFile( entry );
            for ( String key : english.stringPropertyNames() ) {
                String translated = locale.getProperty( key );
                if ( english.getProperty( key ).startsWith( " " ) && translated != null
                        && !translated.startsWith( " " ) ) {
                    lost.add( entry.tag() + ":" + key );
                }
            }
        }
        assertTrue( lost.isEmpty(), "Leading space lost: " + lost );
    }

    /**
     * FXML's {@code %key} inserts bundle text as-is, so an XML entity copied out of an FXML file
     * ({@code &quot;}, {@code &amp;}) shows up literally in the UI.
     */
    @Test
    void noBundleContainsXmlEntities()
            throws IOException
    {
        java.util.regex.Pattern entity = java.util.regex.Pattern.compile( "&(quot|amp|lt|gt|apos|#\\d+);" );
        java.util.List< String > found = new ArrayList<>();
        for ( String key : english.stringPropertyNames() ) {
            if ( entity.matcher( english.getProperty( key ) ).find() ) {
                found.add( "en:" + key );
            }
        }
        for ( SupportedLocales.Entry entry : SupportedLocales.ENTRIES ) {
            Properties locale = loadShippedLocaleFile( entry );
            for ( String key : locale.stringPropertyNames() ) {
                if ( entity.matcher( locale.getProperty( key ) ).find() ) {
                    found.add( entry.tag() + ":" + key );
                }
            }
        }
        assertTrue( found.isEmpty(), "XML entities: " + found );
    }
}
