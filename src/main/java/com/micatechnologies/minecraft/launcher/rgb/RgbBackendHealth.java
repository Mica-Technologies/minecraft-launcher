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

package com.micatechnologies.minecraft.launcher.rgb;

/**
 * Per-backend circuit breaker. Three states with the following semantics:
 *
 * <ul>
 *   <li>{@link State#HEALTHY} — every call goes through. Default starting
 *       state.</li>
 *   <li>{@link State#DEGRADED} — recent failures crossed a threshold. Calls
 *       are gated by an exponential-backoff retry clock; one probe call is
 *       allowed when the clock expires. A success returns to HEALTHY; a
 *       failure ratchets the backoff and stays DEGRADED.</li>
 *   <li>{@link State#DEAD} — too many failures even after retries. No
 *       further calls until the next launcher start. The UI shows a single
 *       "RGB backend unavailable" status; log lines explain why.</li>
 * </ul>
 *
 * <p>Thresholds are tuned for the failure profile of vendor RGB SDKs:
 * transient socket hiccups should self-heal quickly without losing the
 * backend permanently, while a driver update that breaks an SDK across
 * every call should land in DEAD within a few seconds rather than spinning
 * on retries for the rest of the launcher session.</p>
 *
 * <p>All state mutation is synchronized — the controller's worker thread
 * is the only mutator in practice, but the Settings UI may read state for
 * the status chip from the FX thread.</p>
 *
 * @since 2026.5
 */
public final class RgbBackendHealth
{
    /**
     * Circuit-breaker state for a single backend.
     *
     * @since 2026.5
     */
    public enum State
    {
        /** Every call goes through; the default starting state. */
        HEALTHY,
        /** Recent failures crossed the degraded threshold; calls are gated by an
         *  exponential-backoff retry clock that permits one probe call when it
         *  expires. */
        DEGRADED,
        /** Too many failures even after retries; no further calls are made until
         *  the next launcher start. */
        DEAD
    }

    /** Consecutive failures to enter DEGRADED. Small enough that a transient
     *  network blip won't ping the user, large enough that one stray timeout
     *  doesn't flip the breaker. */
    private static final int DEGRADED_THRESHOLD = 3;

    /** Consecutive failures (across the whole session) to enter DEAD. Past
     *  this point we stop trying — assume something fundamental is wrong
     *  (DLL missing post-update, SDK protocol break, etc.). */
    private static final int DEAD_THRESHOLD = 10;

    /** Initial backoff in DEGRADED state, doubled on each repeated failure
     *  up to {@link #MAX_BACKOFF_MS}. */
    private static final long INITIAL_BACKOFF_MS = 30_000L;

    /** Cap on backoff — past this point further failures just extend at
     *  the cap rather than growing unboundedly. */
    private static final long MAX_BACKOFF_MS = 30L * 60_000L;

    /**
     * Lock object used for synchronizing access to shared state.
     *
     * @since 2026.5
     */
    private final Object lock = new Object();

    /**
     * Current state of the circuit breaker.
     *
     * @since 2026.5
     */
    private State state = State.HEALTHY;

    /**
     * Counter for consecutive failures.
     *
     * @since 2026.5
     */
    private int consecutiveFailures = 0;

    /**
     * Timestamp of the next retry attempt in milliseconds.
     *
     * @since 2026.5
     */
    private long nextRetryAt = 0L;

    /**
     * Current backoff duration in milliseconds.
     *
     * @since 2026.5
     */
    private long currentBackoffMs = INITIAL_BACKOFF_MS;

    /**
     * Returns true when a backend call is currently allowed. In DEGRADED
     * state, returns true once {@code nowMs >= nextRetryAt} — one probe
     * call is permitted; the next failure ratchets the backoff. DEAD
     * always returns false.
     *
     * @param nowMs the current time in epoch milliseconds, used to evaluate
     *              the DEGRADED-state retry clock
     *
     * @return {@code true} if a call may proceed now, {@code false} otherwise
     *
     * @since 2026.5
     */
    public boolean canCall( long nowMs )
    {
        synchronized ( lock ) {
            return switch ( state ) {
                case HEALTHY -> true;
                case DEGRADED -> nowMs >= nextRetryAt;
                case DEAD -> false;
            };
        }
    }

    /**
     * Record a successful call. Resets failure counters and clears
     * DEGRADED back to HEALTHY. Does nothing in DEAD (the breaker is
     * intentionally one-way after that point — we don't want a single
     * lucky call after a session-long outage to re-enable a backend
     * whose driver is broken).
     *
     * @since 2026.5
     */
    public void recordSuccess()
    {
        synchronized ( lock ) {
            if ( state == State.DEAD ) return;
            consecutiveFailures = 0;
            currentBackoffMs = INITIAL_BACKOFF_MS;
            state = State.HEALTHY;
        }
    }

    /**
     * Record a failed call. Increments counters and may advance the
     * breaker state. Idempotent for repeat calls past DEAD.
     *
     * @param nowMs the current time in epoch milliseconds, used to schedule
     *              the next DEGRADED-state retry when crossing the degraded
     *              threshold
     *
     * @since 2026.5
     */
    public void recordFailure( long nowMs )
    {
        synchronized ( lock ) {
            consecutiveFailures++;
            if ( consecutiveFailures >= DEAD_THRESHOLD ) {
                state = State.DEAD;
                return;
            }
            if ( consecutiveFailures >= DEGRADED_THRESHOLD ) {
                state = State.DEGRADED;
                nextRetryAt = nowMs + currentBackoffMs;
                // Ratchet backoff for the NEXT failure, capped.
                currentBackoffMs = Math.min( currentBackoffMs * 2, MAX_BACKOFF_MS );
            }
        }
    }

    /**
     * Returns the current circuit-breaker state.
     *
     * <p>Read under the same lock that guards mutation, so the value is a
     * consistent snapshot. Primarily consulted by the Settings status chip
     * (from the FX thread) while the RGB worker thread is the only mutator.</p>
     *
     * @return the current {@link State}
     *
     * @since 2026.5
     */
    public State state()
    {
        synchronized ( lock ) {
            return state;
        }
    }

    /**
     * Snapshot of the consecutive-failure counter — exposed mainly for
     * diagnostic logging and the Settings status chip.
     *
     * @return the number of consecutive failures recorded since the last
     *         success (or, in DEAD state, since the session-long failure run
     *         began)
     *
     * @since 2026.5
     */
    public int consecutiveFailures()
    {
        synchronized ( lock ) {
            return consecutiveFailures;
        }
    }
}
