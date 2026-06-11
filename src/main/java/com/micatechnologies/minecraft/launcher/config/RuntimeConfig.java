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
 * Game runtime configuration domain — RAM allocation + custom JVM
 * arguments. First slice of the A6-deep ConfigManager domain split
 * tracked in the 2026-05-14 review plan.
 *
 * <p>{@link ConfigManager}'s {@code getMinRam} / {@code setMinRam} /
 * {@code getMaxRam} / {@code setMaxRam} / {@code getCustomJvmArgs} /
 * {@code setCustomJvmArgs} now delegate here so the actual key
 * names + default-fallback logic live with their domain rather than
 * inside the 1900-line god class. The legacy API on
 * {@code ConfigManager} stays so existing call sites work unchanged
 * during the rest of the split.</p>
 *
 * <p>State is read through {@link ConfigStore#ensureLoaded()} so
 * this class never holds its own copy of the JsonObject — every call
 * sees the live state, and writes are routed through the same
 * debounced disk-flush queue.</p>
 *
 * @since 2026.5
 */
public final class RuntimeConfig
{
    private RuntimeConfig() { /* static-only */ }

    // ====================================================================
    // RAM allocation
    // ====================================================================

    /** Minimum heap size in megabytes (the {@code -Xms} value). */
    public static synchronized long getMinRam() {
        JsonObject json = ConfigStore.ensureLoaded();
        if ( !json.has( ConfigConstants.MIN_RAM_KEY ) ) {
            json.addProperty( ConfigConstants.MIN_RAM_KEY,
                              ConfigConstants.MIN_RAM_MEGABYTES_DEFAULT );
        }
        return json.get( ConfigConstants.MIN_RAM_KEY ).getAsLong();
    }

    public static synchronized void setMinRam( long minRam ) {
        ConfigStore.ensureLoaded().addProperty( ConfigConstants.MIN_RAM_KEY, minRam );
        ConfigStore.scheduleWrite();
    }

    /** Maximum heap size in megabytes (the {@code -Xmx} value). */
    public static synchronized long getMaxRam() {
        JsonObject json = ConfigStore.ensureLoaded();
        if ( !json.has( ConfigConstants.MAX_RAM_KEY ) ) {
            json.addProperty( ConfigConstants.MAX_RAM_KEY,
                              ConfigConstants.MAX_RAM_MEGABYTES_DEFAULT );
        }
        return json.get( ConfigConstants.MAX_RAM_KEY ).getAsLong();
    }

    /** Convenience: maximum RAM in gigabytes. */
    public static double getMaxRamInGb() {
        return getMaxRam() / 1024.0;
    }

    public static synchronized void setMaxRam( long maxRam ) {
        ConfigStore.ensureLoaded().addProperty( ConfigConstants.MAX_RAM_KEY, maxRam );
        ConfigStore.scheduleWrite();
    }

    // ====================================================================
    // JVM args
    // ====================================================================

    /** Custom JVM arguments string appended to the launch command.
     *  Defaults to the Performance preset (Aikar's flags) when no
     *  value is stored yet. */
    public static synchronized String getCustomJvmArgs() {
        JsonObject json = ConfigStore.ensureLoaded();
        if ( !json.has( ConfigConstants.JVM_ARGS_KEY ) ) {
            json.addProperty( ConfigConstants.JVM_ARGS_KEY,
                              ConfigConstants.JVM_ARGS_VALUE_DEFAULT );
            ConfigStore.scheduleWrite();
        }
        return json.get( ConfigConstants.JVM_ARGS_KEY ).getAsString();
    }

    public static synchronized void setCustomJvmArgs( String jvmArgs ) {
        ConfigStore.ensureLoaded().addProperty( ConfigConstants.JVM_ARGS_KEY,
                                                  jvmArgs == null ? "" : jvmArgs );
        // Flush now rather than debounce: custom JVM args control what gets
        // executed at launch, so the value shouldn't be lost to a crash inside
        // the debounce window.
        ConfigStore.flushNow();
    }
}
