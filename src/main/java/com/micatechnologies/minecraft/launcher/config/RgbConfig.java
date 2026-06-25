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

package com.micatechnologies.minecraft.launcher.config;

import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.launcher.consts.ConfigConstants;

/**
 * RGB hardware integration domain — master enable, per-backend enable
 * flags, in-game effect toggles, and effect-style selection. Third
 * slice of the A6-deep ConfigManager domain split tracked in the
 * 2026-05-14 review plan.
 *
 * <p>Backend identifiers and effect-style identifiers are validated
 * here against the canonical lists in {@link ConfigConstants}; an
 * unknown identifier on read falls back to the default rather than
 * propagating to the RGB controller (where it would surface as a
 * crash). Setters refuse unknown identifiers outright so the on-disk
 * config doesn't accumulate garbage from a future build that dropped
 * a backend / style and got rolled back.</p>
 *
 * @since 2026.5
 */
public final class RgbConfig
{
    private RgbConfig() { /* static-only */ }

    // ====================================================================
    // Master enable
    // ====================================================================

    /** Master kill-switch for the entire RGB-integration subsystem. When
     *  {@code false} the {@code RgbController} stays inert — no backend
     *  probes, no worker thread, no socket / DLL activity. Defaults
     *  off; users opt in via Settings. */
    public static synchronized boolean getRgbEnable() {
        return readBooleanWithDefault( ConfigConstants.RGB_ENABLE_KEY,
                                       ConfigConstants.RGB_ENABLE_DEFAULT );
    }

    /**
     * Sets the master kill-switch for the entire RGB-integration subsystem.
     *
     * @param enable {@code true} to allow the RGB controller to probe backends
     *               and drive lighting, {@code false} to keep it inert
     *
     * @since 2026.5
     */
    public static synchronized void setRgbEnable( boolean enable ) {
        writeBoolean( ConfigConstants.RGB_ENABLE_KEY, enable );
    }

    // ====================================================================
    // Backend selection
    // ====================================================================

    /** Selected RGB backend identifier — one of the known constants on
     *  {@link ConfigConstants}: {@code RGB_BACKEND_AUTO},
     *  {@code _OPENRGB}, {@code _CHROMA}, {@code _CHROMA_NATIVE},
     *  {@code _WINDOWS_DL}, {@code _NONE}. Unknown values fall back
     *  to the default ({@code "auto"}). */
    public static synchronized String getRgbBackend() {
        JsonObject json = ConfigStore.ensureLoaded();
        if ( !json.has( ConfigConstants.RGB_BACKEND_KEY ) ) {
            json.addProperty( ConfigConstants.RGB_BACKEND_KEY,
                              ConfigConstants.RGB_BACKEND_DEFAULT );
        }
        String value = json.get( ConfigConstants.RGB_BACKEND_KEY ).getAsString();
        return switch ( value ) {
            case ConfigConstants.RGB_BACKEND_AUTO,
                 ConfigConstants.RGB_BACKEND_OPENRGB,
                 ConfigConstants.RGB_BACKEND_CHROMA,
                 ConfigConstants.RGB_BACKEND_CHROMA_NATIVE,
                 ConfigConstants.RGB_BACKEND_WINDOWS_DL,
                 ConfigConstants.RGB_BACKEND_NONE -> value;
            default -> ConfigConstants.RGB_BACKEND_DEFAULT;
        };
    }

    /**
     * Sets the selected RGB backend identifier. Only the known identifiers
     * validated by {@link #getRgbBackend()} are accepted; any unknown or
     * {@code null} value is coerced to the default ({@code "auto"}) so the
     * on-disk config never accumulates garbage.
     *
     * @param backend the backend identifier to store
     *
     * @since 2026.5
     */
    public static synchronized void setRgbBackend( String backend ) {
        // Defensive: only accept known identifiers — same set
        // {@link #getRgbBackend} validates. Unknown strings would
        // silently fall back to "auto" on read, but it's cleaner to
        // refuse them at the setter so the on-disk config stays
        // predictable.
        String normalized = switch ( backend ) {
            case ConfigConstants.RGB_BACKEND_AUTO,
                 ConfigConstants.RGB_BACKEND_OPENRGB,
                 ConfigConstants.RGB_BACKEND_CHROMA,
                 ConfigConstants.RGB_BACKEND_CHROMA_NATIVE,
                 ConfigConstants.RGB_BACKEND_WINDOWS_DL,
                 ConfigConstants.RGB_BACKEND_NONE -> backend;
            case null, default -> ConfigConstants.RGB_BACKEND_DEFAULT;
        };
        ConfigStore.ensureLoaded().addProperty( ConfigConstants.RGB_BACKEND_KEY, normalized );
        ConfigStore.scheduleWrite();
    }

    // ====================================================================
    // In-game effect toggles
    // ====================================================================

    /** Whether in-game effects use the running modpack's logo dominant
     *  colors. When false, effects use the launcher theme's accent
     *  palette. */
    public static synchronized boolean getRgbUsePackColors() {
        return readBooleanWithDefault( ConfigConstants.RGB_USE_PACK_COLORS_KEY,
                                       ConfigConstants.RGB_USE_PACK_COLORS_DEFAULT );
    }

    /**
     * Sets whether in-game effects use the running modpack's logo dominant
     * colors rather than the launcher theme's accent palette.
     *
     * @param usePackColors {@code true} to drive effects from the pack's logo
     *                      colors
     *
     * @since 2026.5
     */
    public static synchronized void setRgbUsePackColors( boolean usePackColors ) {
        writeBoolean( ConfigConstants.RGB_USE_PACK_COLORS_KEY, usePackColors );
    }

    /** Whether the in-game effect highlights WASD / E / Space / Shift in
     *  a contrasting accent over the pack-color background. */
    public static synchronized boolean getRgbHighlightKeys() {
        return readBooleanWithDefault( ConfigConstants.RGB_HIGHLIGHT_KEYS_KEY,
                                       ConfigConstants.RGB_HIGHLIGHT_KEYS_DEFAULT );
    }

    /**
     * Sets whether the in-game effect highlights the WASD / E / Space / Shift
     * keys in a contrasting accent over the pack-color background.
     *
     * @param highlight {@code true} to highlight the movement / action keys
     *
     * @since 2026.5
     */
    public static synchronized void setRgbHighlightKeys( boolean highlight ) {
        writeBoolean( ConfigConstants.RGB_HIGHLIGHT_KEYS_KEY, highlight );
    }

    // ====================================================================
    // Per-backend enable flags
    //
    // When the master RGB_ENABLE_KEY is on, the controller starts every
    // backend whose enable flag is true AND whose isAvailable probe
    // returns true at runtime — so a user with mixed-vendor hardware
    // (Razer + Logitech-WinDL + ...) can drive all of it at once
    // instead of having to pick a single backend.
    // ====================================================================

    /**
     * Whether the OpenRGB backend is permitted to start. Effective only when the
     * master {@link #getRgbEnable()} switch is on and the backend's runtime
     * availability probe passes.
     *
     * @return {@code true} if the OpenRGB backend is enabled
     *
     * @since 2026.5
     */
    public static synchronized boolean getRgbEnableOpenRgb() {
        return readBooleanWithDefault( ConfigConstants.RGB_ENABLE_OPENRGB_KEY,
                                       ConfigConstants.RGB_ENABLE_OPENRGB_DEFAULT );
    }

    /**
     * Sets whether the OpenRGB backend is permitted to start.
     *
     * @param enable {@code true} to enable the OpenRGB backend
     *
     * @since 2026.5
     */
    public static synchronized void setRgbEnableOpenRgb( boolean enable ) {
        writeBoolean( ConfigConstants.RGB_ENABLE_OPENRGB_KEY, enable );
    }

    /**
     * Whether the native Razer Chroma backend is permitted to start. Effective
     * only when the master {@link #getRgbEnable()} switch is on and the backend's
     * runtime availability probe passes.
     *
     * @return {@code true} if the native Chroma backend is enabled
     *
     * @since 2026.5
     */
    public static synchronized boolean getRgbEnableChromaNative() {
        return readBooleanWithDefault( ConfigConstants.RGB_ENABLE_CHROMA_NATIVE_KEY,
                                       ConfigConstants.RGB_ENABLE_CHROMA_NATIVE_DEFAULT );
    }

    /**
     * Sets whether the native Razer Chroma backend is permitted to start.
     *
     * @param enable {@code true} to enable the native Chroma backend
     *
     * @since 2026.5
     */
    public static synchronized void setRgbEnableChromaNative( boolean enable ) {
        writeBoolean( ConfigConstants.RGB_ENABLE_CHROMA_NATIVE_KEY, enable );
    }

    /**
     * Whether the Razer Chroma REST backend is permitted to start. Effective only
     * when the master {@link #getRgbEnable()} switch is on and the backend's
     * runtime availability probe passes.
     *
     * @return {@code true} if the Chroma REST backend is enabled
     *
     * @since 2026.5
     */
    public static synchronized boolean getRgbEnableChromaRest() {
        return readBooleanWithDefault( ConfigConstants.RGB_ENABLE_CHROMA_REST_KEY,
                                       ConfigConstants.RGB_ENABLE_CHROMA_REST_DEFAULT );
    }

    /**
     * Sets whether the Razer Chroma REST backend is permitted to start.
     *
     * @param enable {@code true} to enable the Chroma REST backend
     *
     * @since 2026.5
     */
    public static synchronized void setRgbEnableChromaRest( boolean enable ) {
        writeBoolean( ConfigConstants.RGB_ENABLE_CHROMA_REST_KEY, enable );
    }

    /**
     * Whether the Windows DLL (WinDL) backend is permitted to start. Effective
     * only when the master {@link #getRgbEnable()} switch is on and the backend's
     * runtime availability probe passes.
     *
     * @return {@code true} if the Windows DLL backend is enabled
     *
     * @since 2026.5
     */
    public static synchronized boolean getRgbEnableWindowsDl() {
        return readBooleanWithDefault( ConfigConstants.RGB_ENABLE_WINDOWS_DL_KEY,
                                       ConfigConstants.RGB_ENABLE_WINDOWS_DL_DEFAULT );
    }

    /**
     * Sets whether the Windows DLL (WinDL) backend is permitted to start.
     *
     * @param enable {@code true} to enable the Windows DLL backend
     *
     * @since 2026.5
     */
    public static synchronized void setRgbEnableWindowsDl( boolean enable ) {
        writeBoolean( ConfigConstants.RGB_ENABLE_WINDOWS_DL_KEY, enable );
    }

    /**
     * Whether the Corsair iCUE backend is permitted to start. Effective only when
     * the master {@link #getRgbEnable()} switch is on and the backend's runtime
     * availability probe passes.
     *
     * @return {@code true} if the Corsair backend is enabled
     *
     * @since 2026.5
     */
    public static synchronized boolean getRgbEnableCorsair() {
        return readBooleanWithDefault( ConfigConstants.RGB_ENABLE_CORSAIR_KEY,
                                       ConfigConstants.RGB_ENABLE_CORSAIR_DEFAULT );
    }

    /**
     * Sets whether the Corsair iCUE backend is permitted to start.
     *
     * @param enable {@code true} to enable the Corsair backend
     *
     * @since 2026.5
     */
    public static synchronized void setRgbEnableCorsair( boolean enable ) {
        writeBoolean( ConfigConstants.RGB_ENABLE_CORSAIR_KEY, enable );
    }

    /**
     * Whether the ASUS Aura backend is permitted to start. Effective only when
     * the master {@link #getRgbEnable()} switch is on and the backend's runtime
     * availability probe passes.
     *
     * @return {@code true} if the ASUS Aura backend is enabled
     *
     * @since 2026.5
     */
    public static synchronized boolean getRgbEnableAsusAura() {
        return readBooleanWithDefault( ConfigConstants.RGB_ENABLE_ASUS_AURA_KEY,
                                       ConfigConstants.RGB_ENABLE_ASUS_AURA_DEFAULT );
    }

    /**
     * Sets whether the ASUS Aura backend is permitted to start.
     *
     * @param enable {@code true} to enable the ASUS Aura backend
     *
     * @since 2026.5
     */
    public static synchronized void setRgbEnableAsusAura( boolean enable ) {
        writeBoolean( ConfigConstants.RGB_ENABLE_ASUS_AURA_KEY, enable );
    }

    // ====================================================================
    // Menu effect
    // ====================================================================

    /**
     * Whether the ambient launcher-menu RGB effect runs while the user is in the
     * launcher UI (as opposed to the in-game effect).
     *
     * @return {@code true} if the menu effect is enabled
     *
     * @since 2026.5
     */
    public static synchronized boolean getRgbMenuEffectEnable() {
        return readBooleanWithDefault( ConfigConstants.RGB_MENU_EFFECT_ENABLE_KEY,
                                       ConfigConstants.RGB_MENU_EFFECT_ENABLE_DEFAULT );
    }

    /**
     * Sets whether the ambient launcher-menu RGB effect runs while the user is in
     * the launcher UI.
     *
     * @param enable {@code true} to enable the menu effect
     *
     * @since 2026.5
     */
    public static synchronized void setRgbMenuEffectEnable( boolean enable ) {
        writeBoolean( ConfigConstants.RGB_MENU_EFFECT_ENABLE_KEY, enable );
    }

    /** Effect style identifier — must be in {@link ConfigConstants#RGB_EFFECT_STYLES}.
     *  An unknown stored value (stale config from a future build that
     *  dropped a style + got rolled back) falls back to the default
     *  rather than crashing the RGB subsystem. */
    public static synchronized String getRgbEffectStyle() {
        JsonObject json = ConfigStore.ensureLoaded();
        if ( !json.has( ConfigConstants.RGB_EFFECT_STYLE_KEY ) ) {
            json.addProperty( ConfigConstants.RGB_EFFECT_STYLE_KEY,
                              ConfigConstants.RGB_EFFECT_STYLE_DEFAULT );
            ConfigStore.scheduleWrite();
        }
        String value = json.get( ConfigConstants.RGB_EFFECT_STYLE_KEY ).getAsString();
        if ( !ConfigConstants.RGB_EFFECT_STYLES.contains( value ) ) {
            return ConfigConstants.RGB_EFFECT_STYLE_DEFAULT;
        }
        return value;
    }

    /**
     * Sets the RGB effect-style identifier. A {@code null} value or one not in
     * {@link ConfigConstants#RGB_EFFECT_STYLES} is silently ignored so the
     * on-disk config never persists an unknown style.
     *
     * @param style the effect-style identifier to store
     *
     * @since 2026.5
     */
    public static synchronized void setRgbEffectStyle( String style ) {
        if ( style == null || !ConfigConstants.RGB_EFFECT_STYLES.contains( style ) ) {
            return; // ignore — don't persist garbage
        }
        ConfigStore.ensureLoaded().addProperty( ConfigConstants.RGB_EFFECT_STYLE_KEY, style );
        ConfigStore.scheduleWrite();
    }

    // ====================================================================
    // Helpers — local copy of the read-or-default / write pattern
    // shared across the RGB-section getters. Identical shape to the
    // ConfigManager originals; lives here so the RGB slice doesn't
    // depend on a package-private helper in the facade class.
    // ====================================================================

    /**
     * Reads a boolean config value, seeding the live JSON with the supplied
     * default (without scheduling a write) when the key is absent.
     *
     * @param key  the config key to read
     * @param dflt the default value used when the key is missing
     *
     * @return the stored boolean value, or {@code dflt} when the key was absent
     *
     * @since 2026.5
     */
    private static boolean readBooleanWithDefault( String key, boolean dflt ) {
        JsonObject json = ConfigStore.ensureLoaded();
        if ( !json.has( key ) ) {
            json.addProperty( key, dflt );
        }
        return json.get( key ).getAsBoolean();
    }

    /**
     * Writes a boolean config value and schedules a debounced disk flush.
     *
     * @param key   the config key to write
     * @param value the boolean value to store
     *
     * @since 2026.5
     */
    private static void writeBoolean( String key, boolean value ) {
        ConfigStore.ensureLoaded().addProperty( key, value );
        ConfigStore.scheduleWrite();
    }
}
