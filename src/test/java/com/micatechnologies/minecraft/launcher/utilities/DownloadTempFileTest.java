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

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link NetworkUtilities#uniqueTempFile}: two launches downloading the same shared
 * asset at once must not write the same temp file.
 */
class DownloadTempFileTest
{
    @Test
    void eachAttemptGetsItsOwnTempFileBesideTheDestination()
    {
        File destination = new File( "/tmp/assets/objects/ab/abcdef" );
        File first = NetworkUtilities.uniqueTempFile( destination );
        File second = NetworkUtilities.uniqueTempFile( destination );

        assertNotEquals( first, second );
        assertEquals( destination.getAbsoluteFile().getParentFile(), first.getParentFile() );
        assertTrue( first.getName().startsWith( "abcdef." ) );
        assertTrue( first.getName().endsWith( ".tmp" ) );
    }
}
