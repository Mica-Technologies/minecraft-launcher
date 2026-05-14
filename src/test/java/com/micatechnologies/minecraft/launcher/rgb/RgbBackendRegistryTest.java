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

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link RgbBackendRegistry#filterCandidates(List)} — the
 * pure-logic seam that decides which backends Auto mode runs. The
 * Settings-tab toggles map directly onto the {@code enabled} flag in
 * each candidate, so any regression in this filter shows up as a
 * mismatch between what the user toggled and what actually drives
 * their devices.
 *
 * <p>Tests use stub {@link RgbBackend} implementations so we don't have
 * to install OpenRGB / Razer Synapse / a Windows DL host to run them.
 * The filter is intentionally pure — no static singletons reached
 * from inside — which is what makes this stubbing trivial.</p>
 */
class RgbBackendRegistryTest
{
    /** Inert stub backend whose {@code isAvailable} verdict is fixed at
     *  construction. Lets a test build a "fake rig" with whatever mix
     *  of available / unavailable / throwing backends it needs. */
    private static final class StubBackend implements RgbBackend
    {
        final String name;
        final boolean available;
        final RuntimeException throwOnAvailable;

        StubBackend( String name, boolean available )
        {
            this( name, available, null );
        }

        StubBackend( String name, boolean available, RuntimeException throwOnAvailable )
        {
            this.name = name;
            this.available = available;
            this.throwOnAvailable = throwOnAvailable;
        }

        @Override public String  name()        { return name; }
        @Override public boolean isAvailable() {
            if ( throwOnAvailable != null ) throw throwOnAvailable;
            return available;
        }
        @Override public void start() {}
        @Override public void renderFrame( RgbFrame frame ) {}
        @Override public void shutdown() {}
    }

    @Test
    void enabledAndAvailableBackendsPassThrough()
    {
        StubBackend a = new StubBackend( "a", true );
        StubBackend b = new StubBackend( "b", true );
        List< RgbBackend > out = RgbBackendRegistry.filterCandidates( List.of(
                new RgbBackendRegistry.Candidate( a, true ),
                new RgbBackendRegistry.Candidate( b, true )
        ) );
        assertEquals( 2, out.size() );
        assertSame( a, out.get( 0 ) );
        assertSame( b, out.get( 1 ) );
    }

    @Test
    void disabledBackendIsFilteredOutEvenIfAvailable()
    {
        // The user with both Razer Chroma and Windows DL hardware should
        // see only the ones they ticked in Settings. The disabled
        // backend's name should NOT appear in the result.
        StubBackend chroma = new StubBackend( "Razer Chroma", true );
        StubBackend winDl  = new StubBackend( "Windows DL",   true );
        List< RgbBackend > out = RgbBackendRegistry.filterCandidates( List.of(
                new RgbBackendRegistry.Candidate( chroma, true ),
                new RgbBackendRegistry.Candidate( winDl,  false )
        ) );
        assertEquals( 1, out.size() );
        assertSame( chroma, out.get( 0 ) );
    }

    @Test
    void unavailableBackendIsFilteredOutEvenIfEnabled()
    {
        // User has the toggle on but the SDK isn't installed / the
        // device isn't connected. Drop silently, don't crash.
        StubBackend chroma  = new StubBackend( "Razer Chroma", true );
        StubBackend missing = new StubBackend( "OpenRGB",     false );
        List< RgbBackend > out = RgbBackendRegistry.filterCandidates( List.of(
                new RgbBackendRegistry.Candidate( missing, true ),
                new RgbBackendRegistry.Candidate( chroma,  true )
        ) );
        assertEquals( 1, out.size() );
        assertSame( chroma, out.get( 0 ) );
    }

    @Test
    void throwingIsAvailableIsTreatedAsUnavailable()
    {
        // An UnsatisfiedLinkError or vendor-SDK Exception inside
        // isAvailable() must not propagate up — the controller treats
        // it as a "skip" so a single broken backend doesn't poison the
        // probe pass for the others.
        StubBackend bad = new StubBackend( "explodey", false,
                new RuntimeException( "synthetic SDK explosion" ) );
        StubBackend good = new StubBackend( "good", true );
        List< RgbBackend > out = RgbBackendRegistry.filterCandidates( List.of(
                new RgbBackendRegistry.Candidate( bad,  true ),
                new RgbBackendRegistry.Candidate( good, true )
        ) );
        assertEquals( 1, out.size() );
        assertSame( good, out.get( 0 ) );
    }

    @Test
    void emptyCandidatesProducesEmptyResult()
    {
        // The master "RGB enable" toggle being off lands here with an
        // empty list. No backends, no log, no controller activity.
        List< RgbBackend > out = RgbBackendRegistry.filterCandidates( List.of() );
        assertTrue( out.isEmpty() );
    }

    @Test
    void orderIsPreservedAcrossFiltering()
    {
        // The order matters: the first surviving candidate is what the
        // Settings status chip shows as the "primary" connected backend
        // and the order frame dispatch iterates. If filterCandidates
        // ever started reshuffling we'd silently surface the wrong
        // primary name.
        StubBackend a = new StubBackend( "a", true );
        StubBackend b = new StubBackend( "b", false );  // dropped
        StubBackend c = new StubBackend( "c", true );
        List< RgbBackend > out = RgbBackendRegistry.filterCandidates( List.of(
                new RgbBackendRegistry.Candidate( a, true ),
                new RgbBackendRegistry.Candidate( b, true ),
                new RgbBackendRegistry.Candidate( c, true )
        ) );
        assertEquals( 2, out.size() );
        assertSame( a, out.get( 0 ) );
        assertSame( c, out.get( 1 ) );
    }
}
