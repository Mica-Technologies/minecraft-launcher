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
import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.utilities.MachineSecretCipher;

/**
 * Encrypted-secret slice of the launcher config. Owns the user-supplied
 * third-party API tokens that need machine-bound encryption — currently
 * just the CurseForge Core API key. Fifth slice of the A6-deep
 * ConfigManager domain split tracked in the 2026-05-14 review plan.
 *
 * <p>The Microsoft / Mojang auth tokens that drive game launch live
 * separately under {@code .MicaMinecraftLauncher/auth/cached_user.json}
 * (encrypted via the same {@link MachineSecretCipher} primitive but
 * managed by the auth pipeline, not this config slice). When a future
 * domain emerges that needs additional encrypted secrets in the main
 * config — Modrinth API tokens, GitHub release-channel tokens, etc. —
 * this is the natural home.</p>
 *
 * <p>All secrets are stored as Base64 envelopes of AES-256-GCM
 * ciphertext keyed to the host machine. A copy of the config file
 * moved to another machine will see the encrypted field but won't be
 * able to decrypt it; the getters return {@code null} on decryption
 * failure rather than throwing, so the consuming code path can
 * gracefully degrade ("no key available → fall back to manual flow")
 * instead of failing the whole settings load.</p>
 *
 * @since 2026.5
 */
public final class AuthTokenStore
{
    /**
     * Private constructor to prevent instantiation of this utility class.
     */
    private AuthTokenStore() { /* static-only */ }

    // ====================================================================
    // CurseForge API key
    // ====================================================================

    /**
     * Decrypts and returns the user-supplied CurseForge Core API key, or
     * {@code null} when none is configured (or the on-disk envelope can't
     * be decrypted on this machine — wrong host, tampered file, etc.).
     * The key is stored under {@link ConfigConstants#CURSEFORGE_API_KEY_KEY}
     * as a Base64 envelope of machine-bound AES-256-GCM ciphertext.
     *
     * <p>A decryption failure returns {@code null} (not an exception) so
     * the CurseForge import path can simply degrade to "no key available
     * → show manual-workaround preview" rather than failing the entire
     * settings load.</p>
     */
    public static synchronized String getCurseForgeApiKey() {
        JsonObject json = ConfigStore.ensureLoaded();
        if ( !json.has( ConfigConstants.CURSEFORGE_API_KEY_KEY ) ) return null;
        String encoded;
        try {
            encoded = json.get( ConfigConstants.CURSEFORGE_API_KEY_KEY ).getAsString();
        }
        catch ( Exception e ) {
            return null;
        }
        if ( encoded == null || encoded.isBlank() ) return null;
        try {
            return MachineSecretCipher.decrypt( encoded );
        }
        catch ( Throwable t ) {
            Logger.logWarningSilent( LocalizationManager.format( "log.authTokenStore.cfKeyDecryptFailed",
                                                                 t.getClass().getSimpleName() ) );
            return null;
        }
    }

    /**
     * Encrypts and persists the user-supplied CurseForge API key. Passing
     * {@code null} or a blank string clears the stored value — useful when
     * the user wants to revoke launcher access to their CF account.
     *
     * <p>The encrypted envelope is bound to this machine; a copy of the
     * config file on another machine will see the field but won't be able
     * to decrypt it. If encryption itself fails (vanishingly rare —
     * cipher init failures on this JRE), the call returns silently
     * without persisting; the previous on-disk value is left intact.</p>
     */
    public static synchronized void setCurseForgeApiKey( String apiKey ) {
        JsonObject json = ConfigStore.ensureLoaded();
        if ( apiKey == null || apiKey.isBlank() ) {
            json.remove( ConfigConstants.CURSEFORGE_API_KEY_KEY );
            // Flush now rather than debounce: a credential change shouldn't be lost
            // to a crash / hard-kill inside the 500 ms debounce window.
            ConfigStore.flushNow();
            return;
        }
        try {
            String envelope = MachineSecretCipher.encrypt( apiKey );
            json.addProperty( ConfigConstants.CURSEFORGE_API_KEY_KEY, envelope );
            ConfigStore.flushNow();
        }
        catch ( Throwable t ) {
            Logger.logWarningSilent( LocalizationManager.format( "log.authTokenStore.cfKeyEncryptFailed",
                                                                 t.getClass().getSimpleName() ) );
        }
    }

    /** Cheap presence check — true when an encrypted CurseForge key is
     *  on disk. Doesn't attempt decryption, so it can't tell you the
     *  key is decryptable on this machine; use {@link #getCurseForgeApiKey}
     *  for that. Used by the Settings UI to decide whether to show the
     *  "key configured" badge or the "Add key" button. */
    public static synchronized boolean hasCurseForgeApiKey() {
        JsonObject json = ConfigStore.ensureLoaded();
        if ( !json.has( ConfigConstants.CURSEFORGE_API_KEY_KEY ) ) return false;
        try {
            String v = json.get( ConfigConstants.CURSEFORGE_API_KEY_KEY ).getAsString();
            return v != null && !v.isBlank();
        }
        catch ( Exception e ) {
            return false;
        }
    }
}
