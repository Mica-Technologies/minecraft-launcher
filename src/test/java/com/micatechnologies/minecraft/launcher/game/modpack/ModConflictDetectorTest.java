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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pattern-matching tests for {@link ModConflictDetector#detectFromNames}.
 * Bypass the filesystem scan to exercise the rules directly against
 * synthetic jar-name lists. Each test names the conflict it's verifying so
 * a regression names the offending rule.
 */
class ModConflictDetectorTest
{
    @Test
    void emptyListProducesNoConflicts()
    {
        assertTrue( ModConflictDetector.detectFromNames( List.of() ).isEmpty() );
    }

    @Test
    void nullListProducesNoConflicts()
    {
        assertTrue( ModConflictDetector.detectFromNames( null ).isEmpty() );
    }

    @Test
    void optifinePlusSodium_detected()
    {
        var conflicts = ModConflictDetector.detectFromNames( List.of(
                "optifine-1.20.1.jar",
                "sodium-fabric-mc1.20.1.jar",
                "fabric-api-0.92.0.jar" ) );
        assertEquals( 1, conflicts.size() );
        assertEquals( "OptiFine and Sodium are incompatible", conflicts.get( 0 ).title() );
    }

    @Test
    void optifabricPlusSodium_detected()
    {
        // OptiFabric pulls OptiFine into Fabric's loader — same incompatibility.
        var conflicts = ModConflictDetector.detectFromNames( List.of(
                "optifabric-1.13.42.jar",
                "sodium-mc1.20.1.jar" ) );
        assertEquals( 1, conflicts.size() );
        assertTrue( conflicts.get( 0 ).title().contains( "Sodium" ) );
    }

    @Test
    void jeiPlusRei_detected()
    {
        var conflicts = ModConflictDetector.detectFromNames( List.of(
                "jei-1.20.1-forge-15.2.0.27.jar",
                "rei-12.0.684-forge.jar" ) );
        assertEquals( 1, conflicts.size() );
        assertEquals( "JEI and REI are both installed", conflicts.get( 0 ).title() );
    }

    @Test
    void justEnoughItems_longForm_detected()
    {
        // The rule also matches the spelled-out form some authors use.
        var conflicts = ModConflictDetector.detectFromNames( List.of(
                "just-enough-items-1.20.1.jar",
                "roughly-enough-items-1.20.1.jar" ) );
        assertEquals( 1, conflicts.size() );
    }

    @Test
    void onlyOneOfTheConflictPair_returnsNothing()
    {
        // OptiFine alone shouldn't trigger — the rule requires BOTH.
        assertTrue( ModConflictDetector.detectFromNames( List.of(
                "optifine-1.20.1.jar",
                "fabric-api-0.92.0.jar" ) ).isEmpty() );

        // JEI alone is fine.
        assertTrue( ModConflictDetector.detectFromNames( List.of(
                "jei-1.20.1.jar" ) ).isEmpty() );
    }

    @Test
    void caseInsensitiveMatching()
    {
        // Author-cased filenames shouldn't dodge the detector. Uppercased
        // "OPTIFINE" + "SODIUM" should still trip the OptiFine + Sodium rule.
        var conflicts = ModConflictDetector.detectFromNames( List.of(
                "OPTIFINE-1.20.1.jar",
                "SODIUM-fabric.jar" ) );
        assertEquals( 1, conflicts.size() );
    }

    @Test
    void multipleConflictsDetectedTogether()
    {
        // OptiFine+Sodium AND JEI+REI in one pack → two conflicts reported.
        var conflicts = ModConflictDetector.detectFromNames( List.of(
                "optifine-1.20.1.jar",
                "sodium-fabric.jar",
                "jei-1.20.1.jar",
                "rei-1.20.1.jar" ) );
        assertEquals( 2, conflicts.size() );
    }

    @Test
    void unrelatedMods_returnNothing()
    {
        assertTrue( ModConflictDetector.detectFromNames( List.of(
                "create-1.20.1-0.5.1.jar",
                "journeymap-1.20.1-5.9.18.jar",
                "iron-chests-1.20.1.jar",
                "biomes-o-plenty-1.20.1.jar" ) ).isEmpty() );
    }
}
