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
    public int formatVersion;
    public String game;          // Always "minecraft" for the packs we accept.
    public String versionId;     // Pack version string ("1.2.3", etc.).
    public String name;          // Display name.
    public String summary;       // Optional short description.
    public List< File > files;
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

    public static final class Hashes
    {
        public String sha1;
        public String sha512;
    }

    public static final class Env
    {
        public String client;
        public String server;
    }
}
