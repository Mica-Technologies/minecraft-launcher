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

import java.util.List;
import java.util.Map;

/**
 * GSON-deserialized shape of a {@code modrinth.index.json} — the manifest
 * Modrinth stores at the root of every {@code .mrpack} archive. See
 * <a href="https://docs.modrinth.com/modpacks/format/">Modrinth Modpack
 * Format</a> for the canonical spec; the fields here cover format version 1
 * which is the only version published as of 2026.
 *
 * <p>Used by {@link MrpackImporter} after it downloads + unzips an
 * {@code .mrpack} from the URL the launcher's preview-confirm flow handed it.
 * The parsed object then gets translated into a Mica-format manifest by the
 * importer so the rest of the launcher (pack installer, mod scanner, etc.)
 * sees an imported pack the same way it sees a natively-authored one.</p>
 *
 * @since 2026.3
 */
public final class ModrinthIndex
{
    /** Modrinth modpack format version; {@code 1} is the only published value
     *  as of 2026. */
    public int formatVersion;

    /** Target game identifier. Always {@code "minecraft"} for the packs we
     *  accept. */
    public String game;          // Always "minecraft" for the packs we accept.

    /** Pack version string ({@code "1.2.3"}, etc.) as authored on Modrinth. */
    public String versionId;     // Pack version string ("1.2.3", etc.).

    /** Human-readable pack display name. */
    public String name;          // Display name.

    /** Optional short description of the pack; may be {@code null}. */
    public String summary;       // Optional short description.

    /** Every bundled file (mods, configs, resource/shader packs) the pack
     *  fetches from CDN URLs. */
    public List< File > files;

    /** Loader / environment dependencies keyed by component ID (e.g.
     *  {@code "minecraft"}, {@code "forge"}, {@code "fabric-loader"}) mapped to
     *  the required version string. */
    public Map< String, String > dependencies;

    /** One entry in the {@code files} array — a single mod, config, resource
     *  pack, or shader pack bundled with the modpack. Modrinth's CDN
     *  guarantees the {@code downloads} URLs serve a file matching the
     *  declared SHA-1; the launcher's existing managed-file pipeline
     *  re-verifies that on download. */
    public static final class File
    {
        /** Pack-root-relative path where this file should land. Determines
         *  whether it's a mod ({@code mods/...}), config ({@code config/...}),
         *  resource pack ({@code resourcepacks/...}), shader pack
         *  ({@code shaderpacks/...}), or something else. */
        public String path;

        /** SHA-1 / SHA-512 hashes of this file, used to verify the download. */
        public Hashes hashes;

        /** Optional environment-specific install gate. Either side may be
         *  "required", "optional", or "unsupported"; we treat anything other
         *  than "unsupported" as "include this file" for the corresponding
         *  Mica clientReq / serverReq flags. */
        public Env env;

        /** Ordered list of CDN URLs to download from. Modrinth provides at
         *  least one; we use the first (the others are mirrors). The
         *  launcher's NetworkUtilities download path can fall back if needed. */
        public List< String > downloads;

        /** Declared file size in bytes — useful for the import confirmation
         *  dialog ("23 MB across 47 mods…") but not load-bearing for the
         *  download itself. */
        public long fileSize;
    }

    /** SHA family hashes Modrinth ships for a bundled file. The launcher's
     *  managed-file pipeline verifies downloads against {@link #sha1}. */
    public static final class Hashes
    {
        /** Hex-encoded SHA-1 of the file. */
        public String sha1;

        /** Hex-encoded SHA-512 of the file. */
        public String sha512;
    }

    /** Per-environment install requirement for a bundled file. Each side is one
     *  of {@code "required"}, {@code "optional"}, or {@code "unsupported"}. */
    public static final class Env
    {
        /** Client-side requirement ({@code "required"} / {@code "optional"} /
         *  {@code "unsupported"}). */
        public String client;

        /** Server-side requirement ({@code "required"} / {@code "optional"} /
         *  {@code "unsupported"}). */
        public String server;
    }
}
