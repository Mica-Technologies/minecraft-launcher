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
 * Network configuration domain — proxy enable / host / port / type.
 * Second slice of the A6-deep ConfigManager domain split tracked in
 * the 2026-05-14 review plan; mirrors {@link RuntimeConfig}'s shape
 * (thin static facade reading + writing through {@link ConfigStore}).
 *
 * <p>{@link ConfigManager}'s legacy proxy getters / setters delegate
 * here so the actual key names + default fallbacks live with their
 * domain rather than in the 1700+ line god class. Call sites stay on
 * the {@code ConfigManager.getProxyXxx} / {@code setProxyXxx} API
 * during the migration.</p>
 *
 * @since 2026.5
 */
public final class NetworkConfig
{
    private NetworkConfig() { /* static-only */ }

    /** Whether a manual SOCKS / HTTP proxy is in effect. When {@code false}
     *  the launcher uses the JVM-default proxy resolver (typically system
     *  proxy or "no proxy"). */
    public static synchronized boolean getProxyEnable() {
        JsonObject json = ConfigStore.ensureLoaded();
        if ( !json.has( ConfigConstants.PROXY_ENABLE_KEY ) ) {
            json.addProperty( ConfigConstants.PROXY_ENABLE_KEY, ConfigConstants.PROXY_ENABLE_DEFAULT );
            ConfigStore.scheduleWrite();
        }
        return json.get( ConfigConstants.PROXY_ENABLE_KEY ).getAsBoolean();
    }

    /**
     * Sets whether a manual SOCKS / HTTP proxy is in effect.
     *
     * @param enable {@code true} to route launcher traffic through the
     *               configured proxy, {@code false} to use the JVM-default
     *               resolver
     *
     * @since 2026.5
     */
    public static synchronized void setProxyEnable( boolean enable ) {
        ConfigStore.ensureLoaded().addProperty( ConfigConstants.PROXY_ENABLE_KEY, enable );
        ConfigStore.scheduleWrite();
    }

    /** Proxy host (FQDN or IP). Read together with {@link #getProxyPort}
     *  / {@link #getProxyType} when {@link #getProxyEnable} is true. */
    public static synchronized String getProxyHost() {
        JsonObject json = ConfigStore.ensureLoaded();
        if ( !json.has( ConfigConstants.PROXY_HOST_KEY ) ) {
            json.addProperty( ConfigConstants.PROXY_HOST_KEY, ConfigConstants.PROXY_HOST_DEFAULT );
            ConfigStore.scheduleWrite();
        }
        return json.get( ConfigConstants.PROXY_HOST_KEY ).getAsString();
    }

    /**
     * Sets the proxy host (FQDN or IP). A {@code null} value is stored as the
     * empty string.
     *
     * @param host the proxy host, or {@code null} to clear it
     *
     * @since 2026.5
     */
    public static synchronized void setProxyHost( String host ) {
        ConfigStore.ensureLoaded().addProperty( ConfigConstants.PROXY_HOST_KEY,
                                                  host != null ? host : "" );
        ConfigStore.scheduleWrite();
    }

    /** Proxy port. Range validation belongs at the UI / consumer layer —
     *  the store is intentionally schema-loose. */
    public static synchronized int getProxyPort() {
        JsonObject json = ConfigStore.ensureLoaded();
        if ( !json.has( ConfigConstants.PROXY_PORT_KEY ) ) {
            json.addProperty( ConfigConstants.PROXY_PORT_KEY, ConfigConstants.PROXY_PORT_DEFAULT );
            ConfigStore.scheduleWrite();
        }
        return json.get( ConfigConstants.PROXY_PORT_KEY ).getAsInt();
    }

    /**
     * Sets the proxy port. The store is schema-loose — range validation belongs
     * at the UI / consumer layer.
     *
     * @param port the proxy port to store
     *
     * @since 2026.5
     */
    public static synchronized void setProxyPort( int port ) {
        ConfigStore.ensureLoaded().addProperty( ConfigConstants.PROXY_PORT_KEY, port );
        ConfigStore.scheduleWrite();
    }

    /** Proxy type — {@code "HTTP"} or {@code "SOCKS"}. Defaults to HTTP
     *  when null is passed to {@link #setProxyType}; the launcher's
     *  proxy resolver maps the string to {@link java.net.Proxy.Type}. */
    public static synchronized String getProxyType() {
        JsonObject json = ConfigStore.ensureLoaded();
        if ( !json.has( ConfigConstants.PROXY_TYPE_KEY ) ) {
            json.addProperty( ConfigConstants.PROXY_TYPE_KEY, ConfigConstants.PROXY_TYPE_DEFAULT );
            ConfigStore.scheduleWrite();
        }
        return json.get( ConfigConstants.PROXY_TYPE_KEY ).getAsString();
    }

    /**
     * Sets the proxy type ({@code "HTTP"} or {@code "SOCKS"}). A {@code null}
     * value is coerced to {@code "HTTP"}.
     *
     * @param type the proxy type, or {@code null} to default to {@code "HTTP"}
     *
     * @since 2026.5
     */
    public static synchronized void setProxyType( String type ) {
        ConfigStore.ensureLoaded().addProperty( ConfigConstants.PROXY_TYPE_KEY,
                                                  type != null ? type : "HTTP" );
        ConfigStore.scheduleWrite();
    }
}
