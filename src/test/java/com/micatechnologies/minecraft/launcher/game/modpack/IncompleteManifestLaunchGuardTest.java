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

package com.micatechnologies.minecraft.launcher.game.modpack;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the "launched with an empty mods folder" incident.
 */
class IncompleteManifestLaunchGuardTest
{
    private static final String MANIFEST_URL =
            "https://example.invalid/mc-launcher-api/alto/manifest.mmcjson";

    /**
     * The proximate cause of the modless launch: when the per-manifest cache is missing, the
     * network-fetched pack used to be {@code add()}ed to the installed list rather than replacing
     * the Phase-1a index stub already sitting there. Both entries then shared a manifest URL and
     * pack name, and {@code getInstalledModPackByName} returns the FIRST match — so every lookup
     * resolved to the unpopulated stub even though the real manifest had downloaded fine.
     *
     * <p>Note this is the "I deleted manifest_cache to force a refresh" flow: the fetch succeeding
     * is precisely what created the duplicate.
     */
    @Test
    void freshFetchReplacesIndexStubInsteadOfAppending()
    {
        GameModPack stub = new GameModPack();
        stub.packName = "Alto";
        stub.manifestUrl = MANIFEST_URL;
        stub.markAsStub();

        GameModPack full = new GameModPack();
        full.packName = "Alto";
        full.manifestUrl = MANIFEST_URL;

        List< GameModPack > installed = new ArrayList<>();
        installed.add( stub );

        GameModPackManager.replaceOrAppendByUrl( installed, MANIFEST_URL, full );

        assertEquals( 1, installed.size(),
                      "fresh pack must replace the stub, not sit beside it" );
        assertSame( full, installed.get( 0 ),
                    "the surviving entry must be the fully-loaded pack" );
        assertTrue( !installed.get( 0 ).isStub(),
                    "no stub may remain reachable by a by-name lookup" );
    }

    /**
     * A URL with no existing entry still appends — the helper must not silently drop new packs.
     */
    @Test
    void unknownUrlIsAppended()
    {
        GameModPack existing = new GameModPack();
        existing.packName = "Other";
        existing.manifestUrl = "https://example.invalid/other/manifest.mmcjson";

        GameModPack fresh = new GameModPack();
        fresh.packName = "Alto";
        fresh.manifestUrl = MANIFEST_URL;

        List< GameModPack > installed = new ArrayList<>();
        installed.add( existing );

        GameModPackManager.replaceOrAppendByUrl( installed, MANIFEST_URL, fresh );

        assertEquals( 2, installed.size(), "a genuinely new pack must be appended" );
        assertSame( fresh, installed.get( 1 ) );
    }
}
