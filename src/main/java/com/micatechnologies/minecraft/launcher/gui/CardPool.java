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

package com.micatechnologies.minecraft.launcher.gui;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Reusable scene-graph pool for the card-rebuild pattern that both
 * {@link MCLauncherMainGui} (hero cards) and
 * {@link MCLauncherGameLibraryGui} (Browse / Library cards) use to
 * keep larger page sizes responsive. The constructed-but-not-currently-
 * displayed cards live in this pool's deque so the per-card scene-graph
 * allocation + CSS-apply cost is paid once per slot for the lifetime of
 * the GUI instance instead of once per filter / sort / page change.
 *
 * <p>All operations run on the FX thread (rebuildCards always does),
 * so no internal synchronisation is needed and {@link ArrayDeque} is
 * the right backing store.</p>
 *
 * <h3>Lifecycle</h3>
 * <p>The typical rebuild loop in the controller looks like:</p>
 * <pre>
 * // Recycle currently-displayed cards
 * pool.recycleAll( flowPane.getChildren(), MyCard.class );
 * flowPane.getChildren().clear();
 * // Build new page
 * for ( var datum : visibleSlice ) {
 *     MyCard card = pool.acquireOr( () -&gt; new MyCard( datum ) );
 *     // ... bind / append
 * }
 * </pre>
 *
 * @param <T> card node type stored in the pool
 * @since 2026.5
 */
public final class CardPool< T >
{
    /**
     * The internal deque used to store and manage the pooled cards.
     */
    private final Deque< T > pool = new ArrayDeque<>();

    /** Returns the pool's current size — primarily a diagnostic /
     *  test hook. */
    public int size()
    {
        return pool.size();
    }

    /**
     * Pushes {@code card} onto the pool. The caller is expected to
     * have already removed it from the scene graph.
     *
     * @param card the card to release back into the pool
     */
    public void release( T card )
    {
        if ( card == null ) return;
        pool.push( card );
    }

    /**
     * Sweeps every node in {@code children} that's an instance of
     * {@code cardClass} onto the pool. Non-card children
     * (empty-state placeholder Labels, import-progress cards, etc.)
     * are skipped by the cast guard so they don't pollute the pool.
     * Caller still needs to clear() the children list afterwards.
     *
     * @param children the iterable collection of nodes to recycle
     * @param cardClass the class type of the card nodes to be recycled
     */
    public void recycleAll( Iterable< ? extends javafx.scene.Node > children, Class< T > cardClass )
    {
        for ( javafx.scene.Node child : children ) {
            if ( cardClass.isInstance( child ) ) {
                pool.push( cardClass.cast( child ) );
            }
        }
    }

    /**
     * Pop a card from the pool if available, otherwise invoke
     * {@code factory} to build a fresh one. The factory's card is
     * expected to be pre-bound to its data; pool cards are returned
     * as-is and need a {@code bind()} call to reset their visual
     * state. Use {@link #acquireOrNull} when the caller needs to
     * distinguish the two paths.
     *
     * @param factory the supplier function to create a new card if none is available in the pool
     * @return the acquired or newly created card
     */
    public T acquireOr( java.util.function.Supplier< T > factory )
    {
        T card = pool.poll();
        return card == null ? factory.get() : card;
    }

    /**
     * Pop a card from the pool, returning null when the pool is
     * empty. Lets the caller branch between "construct + bind once"
     * (fresh card path) and "rebind only" (recycled card path)
     * without doubling the bind work on first-paint.
     *
     * @return the acquired card from the pool, or null if the pool is empty
     */
    public T acquireOrNull()
    {
        return pool.poll();
    }

    /**
     * Runs {@code action} over every pooled (not-currently-displayed) card.
     * Used by the owning GUI's cleanup to tear down per-card subscriptions
     * (e.g. the image-cycle clock) on cards that aren't in the scene graph.
     *
     * @param action the consumer function to apply to each card in the pool
     */
    public void forEach( java.util.function.Consumer< ? super T > action )
    {
        for ( T card : pool ) {
            action.accept( card );
        }
    }
}
