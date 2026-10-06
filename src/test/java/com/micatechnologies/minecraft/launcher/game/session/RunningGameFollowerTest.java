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


package com.micatechnologies.minecraft.launcher.game.session;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link RunningGameFollower}: keyboard RGB and Discord presence follow the most
 * recently started running game, and only return to the menu state when the last one exits.
 */
class RunningGameFollowerTest
{
    private final AtomicLong   clock = new AtomicLong( 1_000 );
    private final List< String > calls = new ArrayList<>();
    private final RunningGameFollower follower = new RunningGameFollower( new RunningGameFollower.Sink()
    {
        @Override
        public void showGame( GameSession session )
        {
            calls.add( "game:" + session.packKey() );
        }

        @Override
        public void showNoGame()
        {
            calls.add( "menu" );
        }
    } );

    private GameSession start( String pack, FakeProcess process )
    {
        GameSession s = new GameSession( null, pack, pack, pack + "-account", null, clock::get );
        clock.addAndGet( 10 );
        s.attachProcess( process );
        return s;
    }

    @Test
    void followsTheMostRecentlyStartedGame()
    {
        FakeProcess p1 = new FakeProcess();
        FakeProcess p2 = new FakeProcess();
        GameSession first = start( "p1", p1 );
        follower.update( List.of( first ) );
        GameSession second = start( "p2", p2 );
        follower.update( List.of( first, second ) );
        assertEquals( List.of( "game:p1", "game:p2" ), calls );
    }

    @Test
    void theFirstGameExitingDoesNotClearTheOthers()
    {
        FakeProcess p1 = new FakeProcess();
        FakeProcess p2 = new FakeProcess();
        GameSession first = start( "p1", p1 );
        GameSession second = start( "p2", p2 );
        follower.update( List.of( first, second ) );
        calls.clear();

        p1.finish( 0 );
        follower.update( List.of( first, second ) );
        assertTrue( calls.isEmpty(), "still showing p2; no menu reset while it runs" );

        p2.finish( 0 );
        follower.update( List.of( first, second ) );
        assertEquals( List.of( "menu" ), calls );
    }

    @Test
    void whenTheShownGameExitsTheOtherTakesOver()
    {
        FakeProcess p1 = new FakeProcess();
        FakeProcess p2 = new FakeProcess();
        GameSession first = start( "p1", p1 );
        GameSession second = start( "p2", p2 );
        follower.update( List.of( first, second ) );
        calls.clear();

        p2.finish( 0 );
        follower.update( List.of( first, second ) );
        assertEquals( List.of( "game:p1" ), calls );
    }

    @Test
    void repeatedUpdatesDoNothing()
    {
        GameSession first = start( "p1", new FakeProcess() );
        follower.update( List.of( first ) );
        follower.update( List.of( first ) );
        assertEquals( List.of( "game:p1" ), calls );
    }

    @Test
    void resyncShowsTheFollowedGameAgain()
    {
        GameSession first = start( "p1", new FakeProcess() );
        follower.update( List.of( first ) );
        // After a launcher restart the presence and keyboard were reset; resync re-pushes.
        follower.resync( List.of( first ) );
        assertEquals( List.of( "game:p1", "game:p1" ), calls );
    }

    @Test
    void resyncWithNoGameRunningDoesNothing()
    {
        follower.resync( List.of() );
        assertTrue( calls.isEmpty() );
    }

    @Test
    void preparingGamesAreNotShownAndNothingResetsBeforeAnyGameRan()
    {
        GameSession preparing = new GameSession( null, "p1", "p1", "a", null, clock::get );
        follower.update( List.of( preparing ) );
        preparing.endWithoutGame();
        follower.update( List.of( preparing ) );
        assertTrue( calls.isEmpty() );
    }
}
