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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Every game session this launcher has started: the live ones and recently ended ones (kept
 * so a UI can still show how they ended).
 *
 * <p>{@link #tryRegister} checks {@link LaunchAdmission} and adds the session in one step,
 * so two launches racing each other can't both get in for the same pack or account.</p>
 *
 * <p>Thread-safe. Listeners run on whichever thread made the change.</p>
 *
 * @since 2026.10
 */
public final class GameSessionRegistry
{
    /** Ended sessions kept for display; older ones are dropped. */
    static final int MAX_ENDED = 20;

    private static final GameSessionRegistry INSTANCE = new GameSessionRegistry();

    private final List< GameSession > sessions  = new ArrayList<>();
    private final List< Runnable >    listeners = new CopyOnWriteArrayList<>();
    private volatile boolean          oneAtATime = false;

    /**
     * Creates an empty registry. Production code uses {@link #get()}.
     *
     * @since 2026.10
     */
    GameSessionRegistry() { }

    /**
     * @return the launcher's registry
     *
     * @since 2026.10
     */
    public static GameSessionRegistry get()
    {
        return INSTANCE;
    }

    /**
     * Sets whether only one game may be active at a time. Off by default: the Running Games
     * window shows any number of games.
     *
     * @param value the rule
     *
     * @since 2026.10
     */
    public void setOneAtATime( boolean value )
    {
        oneAtATime = value;
    }

    /**
     * Admits and adds a session, unless an active session conflicts with it.
     *
     * @param session the new session, in {@link GameSession.Phase#PREPARING}
     *
     * @return the verdict; the session was added only when it is OK
     *
     * @since 2026.10
     */
    public LaunchAdmission.Decision tryRegister( GameSession session )
    {
        LaunchAdmission.Decision decision;
        synchronized ( this ) {
            decision = LaunchAdmission.check( session, sessions, oneAtATime );
            if ( !decision.ok() ) {
                return decision;
            }
            sessions.add( session );
            pruneEnded();
        }
        session.addListener( s -> {
            synchronized ( this ) {
                pruneEnded();
            }
            fireChanged();
        } );
        fireChanged();
        return decision;
    }

    /**
     * Would a launch be admitted now? For greying out a Play button; {@link #tryRegister}
     * is the authority.
     *
     * @param candidate the launch to check (not registered)
     *
     * @return the verdict
     *
     * @since 2026.10
     */
    public synchronized LaunchAdmission.Decision preview( GameSession candidate )
    {
        return LaunchAdmission.check( candidate, sessions, oneAtATime );
    }

    /**
     * @return every session, oldest first
     *
     * @since 2026.10
     */
    public synchronized List< GameSession > sessions()
    {
        return List.copyOf( sessions );
    }

    /**
     * @return the sessions still preparing or running, oldest first
     *
     * @since 2026.10
     */
    public synchronized List< GameSession > active()
    {
        List< GameSession > out = new ArrayList<>();
        for ( GameSession s : sessions ) {
            if ( s.phase().isActive() ) {
                out.add( s );
            }
        }
        return out;
    }

    /**
     * @return whether any game is preparing or running
     *
     * @since 2026.10
     */
    public boolean hasActive()
    {
        return !active().isEmpty();
    }

    /**
     * The active session for a pack.
     *
     * @param packKey the pack's stable identity
     *
     * @return the session, or {@code null} when the pack isn't launching or running
     *
     * @since 2026.10
     */
    public synchronized GameSession activeForPack( String packKey )
    {
        for ( GameSession s : sessions ) {
            if ( s.phase().isActive() && packKey != null && packKey.equals( s.packKey() ) ) {
                return s;
            }
        }
        return null;
    }

    /**
     * Whether a pack is launching or running. Anything that rewrites a pack's files (verify,
     * uninstall) must check this first.
     *
     * @param pack the pack
     *
     * @return {@code true} while it has an active session
     *
     * @since 2026.10
     */
    public boolean isPackActive( com.micatechnologies.minecraft.launcher.game.modpack.GameModPack pack )
    {
        return pack != null && activeForPack( GameSession.keyFor( pack ) ) != null;
    }

    /**
     * Forgets an ended session. Active sessions can't be dismissed.
     *
     * @param id the session id
     *
     * @return whether a session was removed
     *
     * @since 2026.10
     */
    public boolean dismiss( long id )
    {
        boolean removed;
        synchronized ( this ) {
            removed = sessions.removeIf( s -> s.id() == id && !s.phase().isActive() );
        }
        if ( removed ) {
            fireChanged();
        }
        return removed;
    }

    /**
     * @param listener runs after any session is added, changes phase or is dismissed
     *
     * @since 2026.10
     */
    public void addListener( Runnable listener )
    {
        listeners.add( listener );
    }

    /**
     * @param listener a listener added with {@link #addListener}
     *
     * @since 2026.10
     */
    public void removeListener( Runnable listener )
    {
        listeners.remove( listener );
    }

    /** Drops the oldest ended sessions beyond {@link #MAX_ENDED}. Caller holds the monitor. */
    private void pruneEnded()
    {
        int ended = 0;
        for ( GameSession s : sessions ) {
            if ( !s.phase().isActive() ) {
                ended++;
            }
        }
        for ( int i = 0; i < sessions.size() && ended > MAX_ENDED; ) {
            if ( !sessions.get( i ).phase().isActive() ) {
                sessions.remove( i );
                ended--;
            }
            else {
                i++;
            }
        }
    }

    private void fireChanged()
    {
        for ( Runnable l : listeners ) {
            try {
                l.run();
            }
            catch ( RuntimeException ignored ) {
                // One failing listener must not keep the others from hearing about it.
            }
        }
    }
}
