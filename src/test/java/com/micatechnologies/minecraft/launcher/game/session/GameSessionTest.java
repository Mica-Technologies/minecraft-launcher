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

import com.micatechnologies.minecraft.launcher.game.session.GameSession.Phase;
import com.micatechnologies.minecraft.launcher.game.session.LaunchAdmission.Outcome;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link GameSession}, {@link LaunchAdmission} and {@link GameSessionRegistry}: which
 * launches may run side by side, how a session moves through its life, and that each launch's
 * cancellation is its own.
 */
class GameSessionTest
{
    private static GameSession session( String pack, String account )
    {
        return new GameSession( null, pack, "Pack " + pack, account, account == null ? null : "Player " + account,
                                () -> 1_000L );
    }

    // =========================================================================
    //  Admission
    // =========================================================================

    @Test
    void differentPacksOnDifferentAccountsRunSideBySide()
    {
        assertTrue( LaunchAdmission.check( session( "p2", "b" ), List.of( session( "p1", "a" ) ), false ).ok() );
    }

    @Test
    void aPackCanOnlyRunOnce()
    {
        GameSession running = session( "p1", "a" );
        LaunchAdmission.Decision d = LaunchAdmission.check( session( "p1", "b" ), List.of( running ), false );
        assertEquals( Outcome.PACK_ALREADY_RUNNING, d.outcome() );
        assertSame( running, d.conflicting() );
    }

    @Test
    void anAccountCanOnlyPlayOneGame()
    {
        GameSession running = session( "p1", "a" );
        LaunchAdmission.Decision d = LaunchAdmission.check( session( "p2", "a" ), List.of( running ), false );
        assertEquals( Outcome.ACCOUNT_BUSY, d.outcome() );
        assertSame( running, d.conflicting() );
    }

    @Test
    void serverLaunchesHaveNoAccountToCollideOn()
    {
        assertTrue( LaunchAdmission.check( session( "p2", null ), List.of( session( "p1", null ) ), false ).ok() );
    }

    @Test
    void endedSessionsDoNotBlock()
    {
        GameSession ended = session( "p1", "a" );
        ended.endWithoutGame();
        assertTrue( LaunchAdmission.check( session( "p1", "a" ), List.of( ended ), true ).ok() );
    }

    @Test
    void oneAtATimeBlocksAnyOtherActiveGame()
    {
        LaunchAdmission.Decision d = LaunchAdmission.check( session( "p2", "b" ), List.of( session( "p1", "a" ) ), true );
        assertEquals( Outcome.ANOTHER_GAME_RUNNING, d.outcome() );
    }

    @Test
    void theSpecificConflictIsReportedEvenInOneAtATimeMode()
    {
        assertEquals( Outcome.PACK_ALREADY_RUNNING,
                      LaunchAdmission.check( session( "p1", "b" ), List.of( session( "p1", "a" ) ), true ).outcome() );
    }

    // =========================================================================
    //  Session lifecycle
    // =========================================================================

    @Test
    void aSessionRunsThenExits()
    {
        GameSession s = session( "p1", "a" );
        FakeProcess p = new FakeProcess();
        s.attachProcess( p );
        assertEquals( Phase.RUNNING, s.phase() );
        assertEquals( 1_000L, s.startedMs() );

        p.finish( 0 );
        assertEquals( Phase.EXITED, s.phase() );
        assertEquals( 0, s.exitCode() );
    }

    @Test
    void aNonZeroExitIsACrash()
    {
        GameSession s = session( "p1", "a" );
        FakeProcess p = new FakeProcess();
        s.attachProcess( p );
        p.finish( 1 );
        assertEquals( Phase.CRASHED, s.phase() );
        assertEquals( 1, s.exitCode() );
    }

    @Test
    void aLaunchThatNeverStartsFailsOrIsCancelled()
    {
        GameSession failed = session( "p1", "a" );
        failed.endWithoutGame();
        assertEquals( Phase.FAILED, failed.phase() );

        GameSession cancelled = session( "p2", "b" );
        cancelled.cancel();
        cancelled.endWithoutGame();
        assertEquals( Phase.CANCELLED, cancelled.phase() );
    }

    @Test
    void endWithoutGameLeavesARunningGameAlone()
    {
        GameSession s = session( "p1", "a" );
        s.attachProcess( new FakeProcess() );
        s.endWithoutGame();
        assertEquals( Phase.RUNNING, s.phase() );
    }

    @Test
    void cancellingOneLaunchLeavesAnotherAlone() throws Exception
    {
        GameSession first = session( "p1", "a" );
        GameSession second = session( "p2", "b" );
        Thread firstWorker = new Thread( () -> { } );
        Thread secondWorker = new Thread( () -> { } );
        first.bindWorker( firstWorker );
        second.bindWorker( secondWorker );

        first.cancel();
        assertTrue( first.isCancelled() );
        assertTrue( firstWorker.isInterrupted() );
        assertFalse( second.isCancelled(), "the old global current-launch cancelled every launch" );
        assertFalse( secondWorker.isInterrupted() );
    }

    @Test
    void cancelDoesNothingOnceTheGameIsRunning()
    {
        GameSession s = session( "p1", "a" );
        s.attachProcess( new FakeProcess() );
        s.cancel();
        assertFalse( s.isCancelled() );
    }

    @Test
    void stopAsksAndKillForces()
    {
        GameSession asked = session( "p1", "a" );
        FakeProcess p1 = new FakeProcess();
        asked.attachProcess( p1 );
        asked.stop( false );
        assertTrue( p1.destroyed );

        GameSession killed = session( "p2", "b" );
        FakeProcess p2 = new FakeProcess();
        killed.attachProcess( p2 );
        killed.stop( true );
        assertTrue( p2.destroyedForcibly );
    }

    @Test
    void listenersHearEveryPhaseChange()
    {
        GameSession s = session( "p1", "a" );
        List< Phase > seen = new ArrayList<>();
        s.addListener( x -> seen.add( x.phase() ) );
        FakeProcess p = new FakeProcess();
        s.attachProcess( p );
        p.finish( 0 );
        assertEquals( List.of( Phase.RUNNING, Phase.EXITED ), seen );
    }

    // =========================================================================
    //  Registry
    // =========================================================================

    @Test
    void theRegistryAdmitsAtomically()
    {
        GameSessionRegistry registry = new GameSessionRegistry();
        registry.setOneAtATime( false );
        assertTrue( registry.tryRegister( session( "p1", "a" ) ).ok() );
        assertTrue( registry.tryRegister( session( "p2", "b" ) ).ok() );
        assertEquals( Outcome.PACK_ALREADY_RUNNING, registry.tryRegister( session( "p1", "c" ) ).outcome() );
        assertEquals( Outcome.ACCOUNT_BUSY, registry.tryRegister( session( "p3", "a" ) ).outcome() );
        assertEquals( 2, registry.active().size(), "refused sessions are not added" );
    }

    @Test
    void anEndedGameFreesItsPackAndAccount()
    {
        GameSessionRegistry registry = new GameSessionRegistry();
        GameSession first = session( "p1", "a" );
        registry.tryRegister( first );
        FakeProcess p = new FakeProcess();
        first.attachProcess( p );
        assertTrue( registry.hasActive() );
        assertSame( first, registry.activeForPack( "p1" ) );

        p.finish( 0 );
        assertFalse( registry.hasActive() );
        assertNull( registry.activeForPack( "p1" ) );
        assertTrue( registry.tryRegister( session( "p1", "a" ) ).ok() );
        assertEquals( 2, registry.sessions().size(), "the ended session is kept for display" );
    }

    @Test
    void onlyEndedSessionsCanBeDismissed()
    {
        GameSessionRegistry registry = new GameSessionRegistry();
        GameSession live = session( "p1", "a" );
        registry.tryRegister( live );
        assertFalse( registry.dismiss( live.id() ) );
        live.endWithoutGame();
        assertTrue( registry.dismiss( live.id() ) );
        assertTrue( registry.sessions().isEmpty() );
    }

    @Test
    void oldEndedSessionsArePruned()
    {
        GameSessionRegistry registry = new GameSessionRegistry();
        for ( int i = 0; i < GameSessionRegistry.MAX_ENDED + 5; i++ ) {
            GameSession s = session( "p" + i, "a" + i );
            registry.tryRegister( s );
            s.endWithoutGame();
        }
        assertEquals( GameSessionRegistry.MAX_ENDED, registry.sessions().size() );
    }

    @Test
    void listenersHearRegistrationsAndPhaseChanges()
    {
        GameSessionRegistry registry = new GameSessionRegistry();
        AtomicInteger changes = new AtomicInteger();
        registry.addListener( changes::incrementAndGet );
        GameSession s = session( "p1", "a" );
        registry.tryRegister( s );
        s.endWithoutGame();
        assertEquals( 2, changes.get() );
    }
}
