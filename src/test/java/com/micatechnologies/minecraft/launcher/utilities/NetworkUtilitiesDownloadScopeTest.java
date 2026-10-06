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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URL;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Covers routing download notices to the launch they belong to. With launches running at the
 * same time, every launch used to hear every download, so each pack's rows showed the other
 * pack's files and retries.
 *
 * @since 2026.10
 */
class NetworkUtilitiesDownloadScopeTest
{
    private final Object launchA = new Object();
    private final Object launchB = new Object();
    private final List< String > heardByA = new CopyOnWriteArrayList<>();
    private final List< String > heardByB = new CopyOnWriteArrayList<>();
    private final Runnable removeA = NetworkUtilities.addRetryNoticeListener( launchA, heardByA::add );
    private final Runnable removeB = NetworkUtilities.addRetryNoticeListener( launchB, heardByB::add );

    @AfterEach
    void cleanUp()
    {
        removeA.run();
        removeB.run();
        NetworkUtilities.setDownloadScope( null );
    }

    private static URL url() throws Exception
    {
        return URI.create( "https://example.com/mods/jei.jar" ).toURL();
    }

    @Test
    void aScopedDownloadReachesOnlyItsLaunch() throws Exception
    {
        NetworkUtilities.setDownloadScope( launchA );
        NetworkUtilities.notifyRetry( url(), 1, 3 );
        assertEquals( 1, heardByA.size() );
        assertEquals( 0, heardByB.size() );
    }

    @Test
    void anUnscopedDownloadStillReachesEveryLaunch() throws Exception
    {
        NetworkUtilities.notifyRetry( url(), 1, 3 );
        assertEquals( 1, heardByA.size() );
        assertEquals( 1, heardByB.size() );
    }

    @Test
    void theScopeTravelsWithWorkHandedToAPool() throws Exception
    {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            NetworkUtilities.setDownloadScope( launchB );
            Object seen = pool.submit( NetworkUtilities.inCurrentScope( () -> {
                NetworkUtilities.notifyRetry( url(), 2, 3 );
                return NetworkUtilities.currentDownloadScope();
            } ) ).get( 5, TimeUnit.SECONDS );
            assertEquals( launchB, seen );
            assertEquals( 0, heardByA.size() );
            assertEquals( 1, heardByB.size() );
            // The pool thread's own scope is restored afterwards.
            assertNull( pool.submit( NetworkUtilities::currentDownloadScope ).get( 5, TimeUnit.SECONDS ) );
        }
        finally {
            pool.shutdownNow();
        }
    }
}
