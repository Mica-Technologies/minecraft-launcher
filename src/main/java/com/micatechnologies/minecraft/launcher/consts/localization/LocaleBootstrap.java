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

import java.util.Locale;

/**
 * Startup-time locale resolution. Lives in its own class — separate from
 * {@link LocalizationManager} — so it can run BEFORE {@code LocalizationManager}
 * first loads.
 *
 * <h3>Why a separate class?</h3>
 * <p>{@link LocalizationManager} keeps ~89 {@code public static final String}
 * fields that bind translation keys to the active bundle at class-load time.
 * Those fields are populated by a static initializer block that reads
 * {@link Locale#getDefault()}. If startup logic that wants to apply a user
 * override or OS detection lived inside {@code LocalizationManager}, just
 * <em>calling that method</em> would trigger the class load — and the static
 * fields would lock to whatever locale was active at that moment, ignoring
 * the override applied milliseconds later.</p>
 *
 * <p>{@code LocaleBootstrap} sidesteps the problem by being a standalone
 * class with no dependency on {@code LocalizationManager}. Calling
 * {@link #apply(String)} just calls {@link Locale#setDefault} — when
 * something later touches {@code LocalizationManager.SOMETHING}, the class
 * loads against the now-correct {@link Locale#getDefault()} and every static
 * field initializes to the right translation.</p>
 *
 * <h3>Call order at startup</h3>
 * <p>Called from {@code LauncherSession.run()} between
 * {@code RgbIntegration.bootstrap()} (which loads {@code ConfigManager}) and
 * the first {@code LocalizationManager.*} reference in the same method.
 * Anything earlier in the startup chain (DPI, single-instance lock, argv
 * parse) must not reference {@code LocalizationManager}.</p>
 *
 * @since 2026.5
 */
public final class LocaleBootstrap
{
    private LocaleBootstrap() { /* static-only */ }

    /**
     * Resolves the effective startup locale and sets it as the JVM default
     * via {@link Locale#setDefault}. Idempotent — safe to call more than
     * once (e.g. after the user toggles the language in Settings without a
     * restart, though existing {@code LocalizationManager} static fields
     * won't pick up the change).
     *
     * @param overrideTag a BCP-47 locale tag from the user's settings
     *                    (e.g. {@code "fr-FR"}); null / empty falls back to
     *                    {@link #detectOsLocale()}
     */
    public static void apply( String overrideTag )
    {
        Locale.setDefault( resolve( overrideTag ) );
    }

    /** Resolves the effective locale without mutating any JVM state.
     *  Exposed for tests + the Settings dropdown's "current detection
     *  shows up here when override is blank" preview. */
    public static Locale resolve( String overrideTag )
    {
        if ( overrideTag == null || overrideTag.isBlank() ) {
            return detectOsLocale();
        }
        try {
            Locale parsed = Locale.forLanguageTag( overrideTag );
            if ( parsed == null || parsed.toLanguageTag().isBlank() || "und".equalsIgnoreCase(
                    parsed.toLanguageTag() ) ) {
                return detectOsLocale();
            }
            return parsed;
        }
        catch ( Exception ex ) {
            return detectOsLocale();
        }
    }

    /**
     * Returns the OS default locale with a sanity-check fallback. Some
     * Unix-y systems report {@code POSIX} or {@code C} as their locale
     * when no user-level setting is configured; both are nonsense for our
     * UI bundles and would surface as a missing-translation cascade. Those
     * cases fall back to {@code en-US}.
     */
    public static Locale detectOsLocale()
    {
        Locale def = Locale.getDefault();
        if ( def == null ) return Locale.US;
        String tag = def.toLanguageTag();
        if ( tag == null || tag.isBlank()
                || "und".equalsIgnoreCase( tag )
                || "POSIX".equalsIgnoreCase( tag )
                || "C".equalsIgnoreCase( tag ) ) {
            return Locale.US;
        }
        return def;
    }
}
