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

import java.util.List;
import java.util.Locale;

/**
 * The list of UI languages the launcher ships translations for. Drives:
 *
 * <ul>
 *   <li>the Language dropdown in Settings → Appearance</li>
 *   <li>the auto-translation script under {@code tools/translate-locales.js}
 *       which generates the {@code DisplayStrings_<tag>.properties} files
 *       from the English source via google-translate-api-x</li>
 * </ul>
 *
 * <p>Keep this list in sync with the {@code TARGET_LOCALES} array in the
 * translation script. The English source ({@code DisplayStrings.properties})
 * is the source of truth and isn't itself listed here — every locale below
 * is a translation target.</p>
 *
 * @since 2026.5
 */
public final class SupportedLocales
{
    /**
     * Private constructor to prevent instantiation of this utility class.
     *
     * @since 2026.5
     */
    private SupportedLocales() { /* static-only */ }

    /** One supported UI language. {@code tag} is the BCP-47 form used in
     *  config storage + resource bundle file suffixes (e.g.
     *  {@code DisplayStrings_es.properties}). {@code displayName} is shown
     *  in the Settings dropdown.
     *
     * @param tag         the BCP-47 locale tag (e.g. {@code "es"},
     *                    {@code "pt-BR"}) used for config storage and as the
     *                    resource-bundle file suffix
     * @param displayName the language name written in its own language, shown
     *                    in the Settings Language dropdown
     *
     * @since 2026.5
     */
    public record Entry( String tag, String displayName ) {
        /**
         * Converts this entry's BCP-47 {@link #tag()} into a {@link Locale}.
         *
         * @return the {@link Locale} parsed from {@link #tag()} via
         *         {@link Locale#forLanguageTag(String)}
         *
         * @since 2026.5
         */
        public Locale toLocale() { return Locale.forLanguageTag( tag ); }
    }

    /**
     * Translation targets, ordered roughly by global speaker count (English
     * is the source-of-truth bundle and isn't listed here — passing an
     * empty override or selecting "Use OS Language" with an English OS
     * locale resolves to the base bundle). Display names are written in
     * the target language so the dropdown reads correctly even when the
     * user can't read the launcher's current UI language (i.e. a Spanish
     * speaker looking at an English UI sees "Español" not "Spanish").
     *
     * @since 2026.5
     */
    public static final List< Entry > ENTRIES = List.of(
            new Entry( "es",    "Español" ),
            new Entry( "fr",    "Français" ),
            new Entry( "de",    "Deutsch" ),
            new Entry( "pt-BR", "Português (Brasil)" ),
            new Entry( "it",    "Italiano" ),
            new Entry( "ru",    "Русский" ),
            new Entry( "ja",    "日本語" ),
            new Entry( "ko",    "한국어" ),
            new Entry( "zh-CN", "简体中文" ),
            new Entry( "zh-TW", "繁體中文" ),
            new Entry( "ar",    "العربية" ),
            new Entry( "hi",    "हिन्दी" ),
            new Entry( "nl",    "Nederlands" ),
            new Entry( "pl",    "Polski" ),
            new Entry( "tr",    "Türkçe" ),
            new Entry( "sv",    "Svenska" )
    );

    /**
     * Sentinel display label for the "no override — use OS detection"
     *  Settings-dropdown option.
     *
     * @since 2026.5
     */
    public static final String OS_DEFAULT_LABEL_PREFIX = "Use OS Language";
}
