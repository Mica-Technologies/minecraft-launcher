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

import com.micatechnologies.minecraft.launcher.utilities.SingleInstanceLock.ForwardResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers how a second launch reads the running launcher's reply to a forward. The reply decides
 * whether the second launch exits quietly (a window is coming forward) or tells the user why
 * nothing appeared, so a misread either hides a stuck launcher or nags over a working one.
 *
 * @since 2026.10
 */
class SingleInstanceLockReplyTest
{
    @Test
    void aHolderWithAWindowIsDelivered()
    {
        assertEquals( ForwardResult.DELIVERED, SingleInstanceLock.parseReply( "OK WINDOW" ) );
    }

    @Test
    void aHolderWithoutAWindowIsReported()
    {
        assertEquals( ForwardResult.DELIVERED_NO_WINDOW, SingleInstanceLock.parseReply( "OK NOWINDOW" ) );
    }

    @Test
    void trailingWhitespaceIsTolerated()
    {
        assertEquals( ForwardResult.DELIVERED_NO_WINDOW, SingleInstanceLock.parseReply( "OK NOWINDOW \r" ) );
    }

    @Test
    void noReplyMeansAnOlderLauncherAndCountsAsDelivered()
    {
        // Launchers from before the reply never send one; their window still comes forward.
        assertEquals( ForwardResult.UNCONFIRMED, SingleInstanceLock.parseReply( null ) );
        assertTrue( ForwardResult.UNCONFIRMED.delivered() );
    }

    @Test
    void aRejectedTokenIsReportedNotTreatedAsDelivered()
    {
        // A stale token file must not look like an older launcher's silent hang-up.
        assertEquals( ForwardResult.REJECTED, SingleInstanceLock.parseReply( "REJECTED" ) );
        assertFalse( ForwardResult.REJECTED.delivered() );
    }

    @Test
    void anUnknownReplyIsUnconfirmed()
    {
        assertEquals( ForwardResult.UNCONFIRMED, SingleInstanceLock.parseReply( "HELLO" ) );
        assertEquals( ForwardResult.UNCONFIRMED, SingleInstanceLock.parseReply( "" ) );
    }

    @Test
    void onlyDeliveredResultsLetTheSecondLaunchExitQuietly()
    {
        assertTrue( ForwardResult.DELIVERED.delivered() );
        assertFalse( ForwardResult.DELIVERED_NO_WINDOW.delivered() );
        assertFalse( ForwardResult.NO_TOKEN.delivered() );
        assertFalse( ForwardResult.UNREACHABLE.delivered() );
    }
}
