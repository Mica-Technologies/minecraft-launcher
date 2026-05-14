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

package com.micatechnologies.minecraft.launcher.game.modpack.import_;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Classifies an arbitrary URL the user pasted into the launcher's Add Modpack
 * by URL field. The launcher's existing add-pack flow assumes the URL points
 * at a Mica-format {@code manifest.json}; pasting a CurseForge or Modrinth
 * project URL would otherwise route through the same fetch+parse code and
 * fail with a generic "not a valid manifest" error — frustrating to debug
 * and unhelpful to a user who reasonably expected platform support.
 *
 * <p>Detection is regex-only (no network) so the classifier is fast and
 * deterministic and can be called from any thread. The actual import /
 * preview work happens downstream and may hit the network, but only after
 * the classifier has decided which platform's API to talk to.</p>
 *
 * @since 2026.3
 */
public final class ModpackImportClassifier
{
    private ModpackImportClassifier() { /* static-only */ }

    /**
     * Result of a single classification pass. {@code slug} is the
     * platform-specific project identifier extracted from the URL ({@code
     * "shenanigans"} for Modrinth's {@code modrinth.com/modpack/shenanigans},
     * {@code "rlcraft"} for CurseForge's {@code curseforge.com/minecraft/modpacks/rlcraft}).
     * {@code versionId} is the URL's optional version pin — e.g. Modrinth's
     * {@code .../version/abc123} or CurseForge's {@code .../files/12345}.
     *
     * <p>Both fields may be {@code null} when the user pasted a bare project
     * URL with no version specified; downstream consumers fall back to
     * "latest" in that case.</p>
     */
    public record Classification(ModpackImportSource source, String slug, String versionId)
    {
        public static final Classification UNKNOWN =
                new Classification( ModpackImportSource.UNKNOWN, null, null );
    }

    // ===== platform URL patterns =====
    //
    // Each pattern is anchored at the protocol so a stray "modrinth.com" in
    // the middle of an unrelated URL doesn't trip the classifier. http/https
    // alternation accommodates the small fraction of users who type the
    // protocol-less form into the field and have the launcher's URL
    // sanitizer prepend `https://` upstream.

    /** Modrinth project URL: {@code https://modrinth.com/modpack/<slug>}
     *  optionally followed by {@code /version/<versionId>}. The path segment
     *  is fixed to {@code modpack} because the launcher only supports
     *  modpacks — pasting a mod, resourcepack, or shader URL should classify
     *  as UNKNOWN and route to the existing "not a manifest" feedback path. */
    private static final Pattern MODRINTH_URL = Pattern.compile(
            "^https?://(?:www\\.)?modrinth\\.com/modpack/([A-Za-z0-9_-]+)(?:/version/([A-Za-z0-9_-]+))?/?(?:\\?.*)?$",
            Pattern.CASE_INSENSITIVE );

    /** CurseForge modpack project URL:
     *  {@code https://www.curseforge.com/minecraft/modpacks/<slug>}
     *  optionally followed by {@code /files/<fileId>} or
     *  {@code /download/<fileId>}. Pluralized {@code modpacks} segment is
     *  enforced for the same reason as Modrinth's {@code modpack} restriction
     *  — non-modpack project URLs need to fall through. */
    private static final Pattern CURSEFORGE_URL = Pattern.compile(
            "^https?://(?:www\\.)?curseforge\\.com/minecraft/modpacks/([A-Za-z0-9_-]+)(?:/(?:files|download)/(\\d+))?/?(?:\\?.*)?$",
            Pattern.CASE_INSENSITIVE );

    /** Technic project URL: matches the website form
     *  {@code https://www.technicpack.net/modpack/<slug>} (optionally with
     *  an extra path segment like {@code /mods} for the site's tabbed views)
     *  AND the Platform API form {@code https://api.technicpack.net/modpack/<slug>}.
     *  Both route through the same handler; the handler always opens the
     *  website variant in the user's browser regardless of which form they
     *  pasted, since the API URL just returns JSON. The slug typically has
     *  a numeric suffix appended for SEO (e.g. {@code tekkit.552560}). */
    private static final Pattern TECHNIC_URL = Pattern.compile(
            "^https?://(?:www\\.|api\\.)?technicpack\\.net/modpack/([A-Za-z0-9._-]+)(?:/[A-Za-z0-9._-]+)?/?(?:\\?.*)?$",
            Pattern.CASE_INSENSITIVE );

    /** Mica-format manifest URL — anything that ends in {@code .json} on an
     *  http(s) scheme. Deliberately permissive: we don't try to match the
     *  exact host because users self-host manifests on a wide variety of
     *  domains. The downstream {@code installModPackByURL} call performs
     *  the real validation (HTTPS check, bounded fetch, GSON parse). */
    private static final Pattern MICA_MANIFEST_URL = Pattern.compile(
            "^https?://[^\\s]+\\.json(?:\\?.*)?$",
            Pattern.CASE_INSENSITIVE );

    /**
     * Classifies the given URL. Returns {@link Classification#UNKNOWN} for
     * any input the patterns above don't match — callers should treat that
     * as "fall through to the legacy add-by-URL path" rather than as a
     * hard error.
     *
     * <p>{@code null} / blank input is treated as UNKNOWN; the caller is
     * expected to short-circuit on empty input before reaching here.</p>
     */
    public static Classification classify( String url )
    {
        if ( url == null ) return Classification.UNKNOWN;
        String trimmed = url.trim();
        if ( trimmed.isEmpty() ) return Classification.UNKNOWN;

        Matcher m = MODRINTH_URL.matcher( trimmed );
        if ( m.matches() ) {
            return new Classification( ModpackImportSource.MODRINTH,
                                        m.group( 1 ),
                                        m.group( 2 ) );
        }
        m = CURSEFORGE_URL.matcher( trimmed );
        if ( m.matches() ) {
            return new Classification( ModpackImportSource.CURSEFORGE,
                                        m.group( 1 ),
                                        m.group( 2 ) );
        }
        m = TECHNIC_URL.matcher( trimmed );
        if ( m.matches() ) {
            return new Classification( ModpackImportSource.TECHNIC,
                                        m.group( 1 ),
                                        null );
        }
        if ( MICA_MANIFEST_URL.matcher( trimmed ).matches() ) {
            return new Classification( ModpackImportSource.MICA, null, null );
        }
        return Classification.UNKNOWN;
    }
}
