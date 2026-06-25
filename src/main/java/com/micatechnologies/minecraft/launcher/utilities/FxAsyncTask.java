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

import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.gui.GUIUtilities;

/**
 * Standardised "run this on a worker, finish on the FX thread" helper.
 *
 * <p>The launcher fans out hundreds of background tasks across
 * {@link SystemUtilities#spawnNewTask}, most of which need to flip
 * something on the UI when they finish. The historical pattern looks
 * like this everywhere:</p>
 *
 * <pre>
 * SystemUtilities.spawnNewTask( () -&gt; {
 *     try {
 *         // ... background work, may throw
 *         GUIUtilities.JFXPlatformRun( () -&gt; { ... on success ... } );
 *     }
 *     catch ( SomeException ex ) {
 *         Logger.logError( "..." );
 *         Logger.logThrowable( ex );
 *         GUIUtilities.JFXPlatformRun( () -&gt; { ... on error ... } );
 *     }
 *     finally {
 *         GUIUtilities.JFXPlatformRun( () -&gt; hideProgress() );
 *     }
 * } );
 * </pre>
 *
 * <p>That pattern is duplicated dozens of times with inconsistent
 * error handling — some swallow exceptions silently, some log without
 * a stack trace, some surface a notification, some forget the
 * finally block entirely. {@code FxAsyncTask.run} standardises the
 * three callback semantics ({@code background}, optional
 * {@code onSuccess} on FX thread, optional {@code onError} on FX
 * thread) and routes uncaught exceptions to {@link Logger} so a
 * forgotten {@code onError} never silently drops a stack trace.</p>
 *
 * <h3>Why not {@code CompletableFuture}?</h3>
 * <p>{@code CompletableFuture.runAsync(...).thenRunAsync(..., Platform::runLater)}
 * is conceptually equivalent, but the launcher's existing wiring
 * already targets the cached executor in {@link SystemUtilities} and
 * {@link GUIUtilities#JFXPlatformRun}'s same-thread fast path /
 * blocking semantics aren't quite a drop-in for the FX
 * {@code Executor} a future would want. Keeping the helper thin and
 * matching the existing primitives makes migration mechanical — no
 * call site has to swap mental models.</p>
 *
 * @since 2026.5
 */
public final class FxAsyncTask
{
    /**
     * Private constructor to prevent instantiation of this utility class.
     */
    private FxAsyncTask() { /* static-only */ }

    /** A worker-thread step that may throw — captures the launcher's
     *  pervasive pattern of "background work that can fail" without
     *  forcing every call site to wrap a {@link Runnable} in its own
     *  try/catch boilerplate. */
    @FunctionalInterface
    public interface ThrowingRunnable
    {
        /**
         * Runs the task which may throw an exception.
         *
         * @throws Exception if any error occurs during execution
         */
        void run() throws Exception;
    }

    /**
     * Fire-and-forget — runs {@code background} on the cached worker
     * pool with no FX-thread follow-up. Uncaught exceptions are routed
     * to {@link Logger#logError(String)} + {@link Logger#logThrowable}
     * instead of bubbling up into the worker's uncaughtExceptionHandler.
     *
     * <p>Equivalent to wrapping {@link SystemUtilities#spawnNewTask}
     * in a try/catch — but consistent. New code should prefer this
     * over the bare {@code spawnNewTask} when the work can fail.</p>
     *
     * @param background the task to run on the worker thread
     */
    public static void run( ThrowingRunnable background )
    {
        run( background, null, null );
    }

    /**
     * Worker + FX-thread success callback. Errors are logged but no
     * error callback is invoked — use the three-argument overload
     * when the UI needs to react to a failure.
     *
     * @param background  the task to run on the worker thread
     * @param onSuccessFx the task to run on the FX thread upon successful completion
     */
    public static void run( ThrowingRunnable background, Runnable onSuccessFx )
    {
        run( background, onSuccessFx, null );
    }

    /**
     * Full three-callback form. Runs {@code background} on a worker
     * thread; on normal return, schedules {@code onSuccessFx} on the
     * FX thread; on exception, logs the throwable via {@link Logger}
     * and (if {@code onErrorFx} is non-null) schedules it on the FX
     * thread with the exception that caused the failure.
     *
     * <p>The success / error callbacks are mutually exclusive — at
     * most one fires per task. Neither runs if {@code background}
     * silently completes without success-or-error signalling (i.e.
     * just returns).</p>
     *
     * @param background  worker-thread work; throws to signal failure
     * @param onSuccessFx FX-thread continuation on normal return;
     *                    may be null
     * @param onErrorFx   FX-thread continuation on thrown exception;
     *                    receives the caught throwable; may be null
     */
    public static void run( ThrowingRunnable background,
                            Runnable onSuccessFx,
                            java.util.function.Consumer< Throwable > onErrorFx )
    {
        SystemUtilities.spawnNewTask( () -> {
            try {
                background.run();
                if ( onSuccessFx != null ) {
                    GUIUtilities.JFXPlatformRun( onSuccessFx );
                }
            }
            catch ( Throwable t ) {
                Logger.logError( LocalizationManager.format( "log.fxAsyncTask.taskFailed", t.getMessage() ) );
                Logger.logThrowable( t );
                if ( onErrorFx != null ) {
                    GUIUtilities.JFXPlatformRun( () -> onErrorFx.accept( t ) );
                }
            }
        } );
    }

    /**
     * Worker + unconditional FX-thread cleanup. The cleanup runs in a
     * {@code finally}, so it fires after either success or failure —
     * matches the launcher's "show progress / hide progress" pattern
     * where the caller doesn't care WHY the task ended, just that
     * progress UI should be torn down.
     *
     * <p>Uncaught exceptions in {@code background} are still routed
     * through {@link Logger}, so silent failures are visible in the
     * logs even when the caller skipped explicit error handling.</p>
     *
     * @param background the task to run on the worker thread
     * @param finallyFx  the task to run on the FX thread after completion or failure
     */
    public static void runWithFinally( ThrowingRunnable background, Runnable finallyFx )
    {
        SystemUtilities.spawnNewTask( () -> {
            try {
                background.run();
            }
            catch ( Throwable t ) {
                Logger.logError( LocalizationManager.format( "log.fxAsyncTask.taskFailed", t.getMessage() ) );
                Logger.logThrowable( t );
            }
            finally {
                if ( finallyFx != null ) {
                    GUIUtilities.JFXPlatformRun( finallyFx );
                }
            }
        } );
    }
}
