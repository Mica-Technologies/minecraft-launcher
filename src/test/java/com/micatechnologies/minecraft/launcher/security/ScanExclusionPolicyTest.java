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

package com.micatechnologies.minecraft.launcher.security;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks in the {@link ScanExclusionPolicy} decision table: a modpack
 * manifest's {@code packScanExclusions} must never be able to exempt the
 * pack root or the protected content folders (mods/, config/, scripts/,
 * etc.) from the security scan, while legitimate exclusions (custom
 * folders, hash-verified launcher trees) pass through untouched.
 */
class ScanExclusionPolicyTest
{
    @Test
    void protectedContentRootsAreRejected()
    {
        for ( String root : List.of( "mods", "config", "scripts", "resourcepacks",
                                     "shaderpacks", "kubejs", "defaultconfigs" ) ) {
            assertNotNull( ScanExclusionPolicy.rejectionReason( root ),
                    "exclusion of protected root must be rejected: " + root );
        }
    }

    @Test
    void protectedRootsRejectedRegardlessOfFormVariations()
    {
        // Leading/trailing separators, backslashes, case, and "." segments
        // must not smuggle a protected root past the policy.
        for ( String variant : List.of( "MODS", "mods/", "/mods", "\\mods",
                                        "./mods", "mods\\", "mods/optifine.jar",
                                        "Config/secret" ) ) {
            assertNotNull( ScanExclusionPolicy.rejectionReason( variant ),
                    "variant must be rejected: " + variant );
        }
    }

    @Test
    void packRootCoveringExclusionsAreRejected()
    {
        for ( String rootish : Arrays.asList( "", " ", ".", "/", "\\", "./", null ) ) {
            assertNotNull( ScanExclusionPolicy.rejectionReason( rootish ),
                    "pack-root exclusion must be rejected: \"" + rootish + "\"" );
        }
    }

    @Test
    void traversalSegmentsAreRejected()
    {
        assertNotNull( ScanExclusionPolicy.rejectionReason( ".." ) );
        assertNotNull( ScanExclusionPolicy.rejectionReason( "custom/../mods" ) );
    }

    @Test
    void benignExclusionsAreAllowed()
    {
        // Hash-verified launcher trees and pack-custom folders are the
        // legitimate use case and must keep working.
        for ( String ok : List.of( "libraries", "bin", "runtime", "libraries/foo",
                                   "custom-tools", "docs/examples" ) ) {
            assertNull( ScanExclusionPolicy.rejectionReason( ok ),
                    "benign exclusion must be allowed: " + ok );
        }
    }

    @Test
    void filterDropsOnlyDisallowedEntriesAndPreservesOriginalForm()
    {
        List< String > filtered = ScanExclusionPolicy.filterUntrusted(
                Arrays.asList( "mods", "Libraries/Foo", ".", "custom-tools", null ) );
        assertEquals( List.of( "Libraries/Foo", "custom-tools" ), filtered );
    }

    @Test
    void filterHandlesNullAndEmptyLists()
    {
        assertTrue( ScanExclusionPolicy.filterUntrusted( null ).isEmpty() );
        assertTrue( ScanExclusionPolicy.filterUntrusted( Collections.emptyList() ).isEmpty() );
    }
}
