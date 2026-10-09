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

package com.micatechnologies.minecraft.launcher.utilities;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link HardwareTunedJvmArgs#generate(int)}: every heap-size branch keeps explicit GC
 * working ({@code -XX:+ExplicitGCInvokesConcurrent}) rather than disabling it, since
 * {@code -XX:+DisableExplicitGC} stops the JDK reclaiming dead direct buffers and games died with
 * "OutOfMemoryError: Direct buffer memory".
 */
class HardwareTunedJvmArgsTest
{
    /** One heap size from each branch of the recommender: small, Aikar's sweet spot, big. */
    private static final int[] HEAP_SIZES_GB = { 2, 4, 8, 12, 16, 32 };

    @Test
    void everyBranchUsesConcurrentExplicitGc()
    {
        for ( int maxRamGB : HEAP_SIZES_GB ) {
            String args = HardwareTunedJvmArgs.generate( maxRamGB );
            assertTrue( args.contains( "-XX:+ExplicitGCInvokesConcurrent" ), args );
            assertFalse( args.contains( "DisableExplicitGC" ), args );
        }
    }

    @Test
    void generatedArgsPassTheValidator()
    {
        for ( int maxRamGB : HEAP_SIZES_GB ) {
            String args = HardwareTunedJvmArgs.generate( maxRamGB );
            assertTrue( JvmArgsValidator.isClean( args ), args );
        }
    }
}
