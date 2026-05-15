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

import javafx.animation.PauseTransition;
import javafx.util.Duration;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Filter / sort / search / pagination state for the launcher's grid screens
 * ({@link MCLauncherMainGui} hero cards and {@link MCLauncherGameLibraryGui}
 * Browse cards). Both controllers used to carry their own near-identical
 * copies of {@code currentPage}, {@code pageSize}, the search-debounce
 * {@link PauseTransition}, and the "reset page → trigger rebuild" pattern
 * sprinkled through every filter listener; A7 in the 2026-05-14 review plan
 * consolidated those into this single VM so:
 *
 * <ul>
 *   <li>Each controller swaps from "five fields + a debounce timer + listener
 *       boilerplate" to "one VM field + setter calls in the listeners."</li>
 *   <li>State changes that should reset the page automatically do so
 *       (changing the sort key with the user three pages deep no longer
 *       silently lands them on an out-of-range page that the rebuild then
 *       has to clamp).</li>
 *   <li>The pagination math (clamp current page to {@code [1, totalPages]},
 *       compute slice indices) lives in one place — {@link #clampAndSlice}.</li>
 *   <li>The search-token splitter lives in one place too — {@link #searchTokens}.</li>
 *   <li>Future tests can drive filter / sort / search transitions without a
 *       live FX runtime: construct a VM, register a counting callback as
 *       {@link #setOnStateChanged}, and assert that setting the search query
 *       fires once per debounce window instead of once per keystroke.</li>
 * </ul>
 *
 * <p>Filter-name keys ({@code "type"}, {@code "status"}, {@code "updatesOnly"})
 * are conventional strings rather than an enum so each controller can carry
 * its own set without forcing every screen to know about every other
 * screen's filter dimensions. The Main screen has type + updatesOnly; the
 * Browse screen adds status. {@link #setFilter} accepts any value and the
 * controllers cast on read via {@link #getStringFilter} / {@link #getBooleanFilter}.</p>
 *
 * <p>Threading: all mutating methods must be called from the FX thread —
 * the VM owns a {@link PauseTransition} for search debouncing, which is FX-
 * thread-affined. The state-changed callback is invoked on the FX thread for
 * the same reason. Reading getters is safe from any thread but typically
 * happens inline with the rebuild callback.</p>
 *
 * @since 2026.5
 */
public final class LibraryViewModel
{
    /** Default search debounce when the constructor isn't given an explicit
     *  value. Matches the historical {@code SEARCH_DEBOUNCE_MS} both
     *  controllers used. */
    public static final int DEFAULT_SEARCH_DEBOUNCE_MS = 120;

    private static final String[] EMPTY_TOKENS = new String[0];

    private final int searchDebounceMs;

    private int     pageSize;
    private int     currentPage = 1;
    private String  searchQuery = "";
    private String  sortKey     = "";
    private final Map< String, Object > filters = new HashMap<>();

    /** Notified whenever the VM's filter / sort / page state changes in a way
     *  that should trigger a grid rebuild. Synchronous for everything except
     *  search-text changes, which the VM internally debounces. */
    private Runnable onStateChanged = () -> {};

    /** Lazily created on first search-query change because PauseTransition
     *  wants to be on the FX thread by the time it's first scheduled —
     *  matches the historical setup pattern in both controllers. */
    private PauseTransition searchDebounce;

    /** Constructs a VM with the given initial page size and the default
     *  search-debounce window. */
    public LibraryViewModel( int defaultPageSize )
    {
        this( defaultPageSize, DEFAULT_SEARCH_DEBOUNCE_MS );
    }

    /** Constructs a VM with the given initial page size and an explicit
     *  search-debounce window in milliseconds. */
    public LibraryViewModel( int defaultPageSize, int searchDebounceMs )
    {
        if ( defaultPageSize <= 0 ) {
            throw new IllegalArgumentException( "defaultPageSize must be > 0" );
        }
        this.pageSize = defaultPageSize;
        this.searchDebounceMs = Math.max( 0, searchDebounceMs );
    }

    /** Replaces the rebuild callback. Pass {@code null} to clear (the VM
     *  retains a no-op default so internal {@code fire()} calls never NPE). */
    public void setOnStateChanged( Runnable callback )
    {
        this.onStateChanged = callback == null ? () -> {} : callback;
    }

    // ===== Read accessors =====

    public int getPageSize()      { return pageSize; }
    public int getCurrentPage()   { return currentPage; }
    public String getSearchQuery(){ return searchQuery; }
    public String getSortKey()    { return sortKey; }

    /** Returns the filter value for {@code key} cast to {@link String}, or
     *  {@code fallback} when the filter is unset or not a string. */
    public String getStringFilter( String key, String fallback )
    {
        Object v = filters.get( key );
        return v instanceof String s ? s : fallback;
    }

    /** Returns the filter value for {@code key} cast to {@link Boolean}, or
     *  {@code fallback} when the filter is unset or not a boolean. */
    public boolean getBooleanFilter( String key, boolean fallback )
    {
        Object v = filters.get( key );
        return v instanceof Boolean b ? b : fallback;
    }

    // ===== Mutators (each fires onStateChanged when state actually changes) =====

    public void setPageSize( int sz )
    {
        if ( sz <= 0 || sz == pageSize ) return;
        pageSize = sz;
        currentPage = 1;
        fire();
    }

    public void setSortKey( String key )
    {
        String normalized = key == null ? "" : key;
        if ( normalized.equals( sortKey ) ) return;
        sortKey = normalized;
        // Reset to page 1 so the new top-of-sort items are visible — without
        // this, switching from "Last Played" to "Name A→Z" while three pages
        // deep would leave the user looking at the middle of the new sort.
        currentPage = 1;
        fire();
    }

    /** Updates the search query, debouncing the rebuild callback so a rapid
     *  burst of keystrokes coalesces into one rebuild rather than one per
     *  character. The page is reset to 1 immediately (before the debounce
     *  fires) so the rebuild pass sees the right page. */
    public void setSearchQuery( String text )
    {
        String q = text == null ? "" : text;
        if ( q.equals( searchQuery ) ) return;
        searchQuery = q;
        currentPage = 1;
        debounceFire();
    }

    /** Sets a generic filter dimension by name. {@code value} can be a
     *  String, Boolean, or anything the controller wants to read back via
     *  {@link #getStringFilter} / {@link #getBooleanFilter}. */
    public void setFilter( String key, Object value )
    {
        if ( key == null ) return;
        Object prev = filters.put( key, value );
        if ( Objects.equals( prev, value ) ) return;
        currentPage = 1;
        fire();
    }

    public void prevPage()
    {
        if ( currentPage > 1 ) {
            currentPage--;
            fire();
        }
    }

    public void nextPage()
    {
        currentPage++;
        // No clamping here — clampAndSlice() corrects out-of-range on the
        // rebuild pass that the fire() triggers. Lets the controller stay
        // ignorant of how many items currently match the filters; the VM
        // doesn't know either, since the data list lives in the controller.
        fire();
    }

    // ===== Helpers used during rebuild =====

    /**
     * Clamps {@link #currentPage} to {@code [1, totalPages]} and returns the
     * page bounds for a list of {@code totalItems}. Mutates the VM's current
     * page on out-of-range — call this once per rebuild pass to keep state
     * consistent. The caller reads {@code startIdx() / endIdx()} to slice
     * its own data list.
     */
    public PageBounds clampAndSlice( int totalItems )
    {
        int totalPages = Math.max( 1, ( totalItems + pageSize - 1 ) / pageSize );
        if ( currentPage < 1 )           currentPage = 1;
        if ( currentPage > totalPages )  currentPage = totalPages;
        int startIdx = ( currentPage - 1 ) * pageSize;
        int endIdx   = Math.min( totalItems, startIdx + pageSize );
        return new PageBounds( totalItems, totalPages, startIdx, endIdx );
    }

    /**
     * Lowercased, whitespace-tokenised version of the current search query.
     * Returns an empty array when the query is null / blank — lets controllers
     * short-circuit the per-pack haystack iteration without a separate
     * {@code isEmpty()} check.
     */
    public String[] searchTokens()
    {
        String s = searchQuery.trim().toLowerCase( Locale.ROOT );
        return s.isEmpty() ? EMPTY_TOKENS : s.split( "\\s+" );
    }

    // ===== Internal =====

    private void fire()
    {
        onStateChanged.run();
    }

    private void debounceFire()
    {
        if ( searchDebounce == null ) {
            searchDebounce = new PauseTransition( Duration.millis( searchDebounceMs ) );
            searchDebounce.setOnFinished( e -> fire() );
        }
        searchDebounce.playFromStart();
    }

    /** Pagination bounds returned by {@link #clampAndSlice}. {@code totalItems}
     *  is the input the slice was computed against; {@code totalPages} is the
     *  rounded-up page count; {@code startIdx} / {@code endIdx} are
     *  half-open list-slice indices ([startIdx, endIdx)). */
    public record PageBounds( int totalItems, int totalPages, int startIdx, int endIdx ) {}
}
