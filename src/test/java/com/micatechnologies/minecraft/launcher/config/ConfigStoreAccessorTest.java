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

package com.micatechnologies.minecraft.launcher.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ConfigStore}'s typed read accessors — the layer every
 * {@code AppConfig} getter in the launcher is built on.
 *
 * <p>Why this matters: every setting the user can change reaches disk through this
 * class, and every setting the launcher reads at startup comes back through it. The
 * behaviour that actually protects users is the <i>fallback</i> path — what happens
 * when a key is absent, or present but holding the wrong JSON type because a hand-edited
 * or partially-migrated config file put a string where an int belongs. If those
 * accessors throw instead of falling back, the launcher fails to start and the user has
 * no obvious way to recover. None of that was covered before this class.</p>
 *
 * <p><b>Isolation.</b> {@code ConfigStore} is static-singleton state backed by a real
 * file. These tests inject a synthetic document via {@link ConfigStore#setJson} in
 * {@code @BeforeEach}, which also stops {@code ensureLoaded()} from ever reading the
 * developer's real config from disk. The original document is captured once and restored
 * in {@code @AfterAll} so this class cannot perturb any other test.</p>
 *
 * <p><b>Known coverage gap — deliberate.</b> The {@code getOrInit*} family schedules a
 * real debounced disk write when the requested key is <i>absent</i>, and a JVM shutdown
 * hook flushes anything still pending. Exercising that branch from a test would drop a
 * stray {@code configuration.json} into the working directory, so only the
 * key-already-present branch is covered here. Closing that gap needs a small seam in
 * production code (an injectable writer, or a package-private switch to suppress the
 * flush); it is tracked in the unit-test coverage plan rather than worked around with a
 * sleep or a file-deleting teardown.</p>
 */
class ConfigStoreAccessorTest
{
    /** The document {@code ConfigStore} held before this class ran, restored afterwards. */
    private static JsonObject originalJson;

    @BeforeAll
    static void captureOriginal()
    {
        originalJson = ConfigStore.peek();
    }

    @AfterAll
    static void restoreOriginal()
    {
        ConfigStore.setJson( originalJson );
    }

    @BeforeEach
    void injectFreshDocument()
    {
        JsonObject doc = new JsonObject();
        doc.addProperty( "presentString", "hello" );
        doc.addProperty( "presentBoolean", true );
        doc.addProperty( "presentInt", 42 );
        doc.addProperty( "presentLong", 9_000_000_000L );
        doc.addProperty( "presentDouble", 2.5 );
        doc.addProperty( "wrongTypeForInt", "not-a-number" );
        ConfigStore.setJson( doc );
    }

    // =========================================================================
    //  Present keys return their stored value
    // =========================================================================

    @Test
    void readsPresentValuesOfEveryType()
    {
        assertEquals( "hello", ConfigStore.getString( "presentString", "fallback" ) );
        assertTrue( ConfigStore.getBoolean( "presentBoolean", false ) );
        assertEquals( 42, ConfigStore.getInt( "presentInt", -1 ) );
        assertEquals( 9_000_000_000L, ConfigStore.getLong( "presentLong", -1L ) );
        assertEquals( 2.5, ConfigStore.getDouble( "presentDouble", -1.0 ) );
    }

    // =========================================================================
    //  Absent keys fall back to the supplied default
    // =========================================================================

    @Test
    void absentKeysFallBackToDefaults()
    {
        assertEquals( "fallback", ConfigStore.getString( "missing", "fallback" ) );
        assertTrue( ConfigStore.getBoolean( "missing", true ) );
        assertFalse( ConfigStore.getBoolean( "missing", false ) );
        assertEquals( 7, ConfigStore.getInt( "missing", 7 ) );
        assertEquals( 7L, ConfigStore.getLong( "missing", 7L ) );
        assertEquals( 7.5, ConfigStore.getDouble( "missing", 7.5 ) );
    }

    @Test
    void plainReadOfAbsentKeyDoesNotMutateTheDocument()
    {
        ConfigStore.getInt( "missing", 7 );
        assertFalse( ConfigStore.peek().has( "missing" ),
                     "a plain get() must not persist its default — only getOrInit* does" );
    }

    // =========================================================================
    //  Wrong-typed values must not blow up startup
    // =========================================================================

    /**
     * A config file that has been hand-edited, partially migrated, or written by an older
     * build can hold a string where an int is expected. These accessors are reached during
     * startup, so an uncaught throw meant one bad value stopped the launcher from starting
     * -- with nothing pointing at the config file and no in-app way to recover.
     *
     * <p>The accessors now fall back to the caller's default and log the mismatch, which
     * degrades a corrupt entry to a working default rather than a dead launcher.</p>
     */
    @Test
    void wrongTypedValueFallsBackToTheDefault()
    {
        assertEquals( 99, ConfigStore.getInt( "wrongTypeForInt", 99 ) );
    }

    @Test
    void wrongTypedValueFallsBackForEveryNumericAccessor()
    {
        assertEquals( 7L, ConfigStore.getLong( "wrongTypeForInt", 7L ) );
        assertEquals( 7.5, ConfigStore.getDouble( "wrongTypeForInt", 7.5 ) );
    }

    /**
     * An object or array where a scalar belongs is the other shape a corrupted or
     * hand-edited file produces. Gson throws {@code UnsupportedOperationException} for
     * these rather than {@code NumberFormatException}, so they need covering separately.
     */
    @Test
    void structuredValueWhereAScalarIsExpectedFallsBack()
    {
        JsonObject doc = ConfigStore.peek();
        doc.add( "objectWhereScalarExpected", new JsonObject() );
        JsonArray multi = new JsonArray();
        multi.add( 1 );
        multi.add( 2 );
        doc.add( "multiElementArray", multi );

        assertEquals( 5, ConfigStore.getInt( "objectWhereScalarExpected", 5 ) );
        assertEquals( "fallback", ConfigStore.getString( "objectWhereScalarExpected", "fallback" ) );
        assertTrue( ConfigStore.getBoolean( "objectWhereScalarExpected", true ) );
        assertEquals( 5, ConfigStore.getInt( "multiElementArray", 5 ) );
    }

    /**
     * Documents a Gson behaviour worth knowing about rather than fighting: a
     * <em>single-element</em> array unwraps to its element, so {@code [42]} read as an int
     * yields {@code 42}, not the default. Only arrays of other sizes throw and therefore
     * fall back.
     *
     * <p>This is Gson's documented coercion, and it is harmless here -- a config written
     * as {@code [42]} where {@code 42} was meant still produces the intended value. Pinned
     * so nobody later "fixes" it as a bug in the coercion helpers, which do not control
     * it.</p>
     */
    @Test
    void singleElementArrayUnwrapsToItsElementPerGsonSemantics()
    {
        JsonArray single = new JsonArray();
        single.add( 42 );
        ConfigStore.peek().add( "singleElementArray", single );

        assertEquals( 42, ConfigStore.getInt( "singleElementArray", 5 ) );
    }

    /**
     * A wrong-typed value must not be silently rewritten. Falling back is a read-time
     * repair; overwriting the user's file on a mere read would destroy whatever they were
     * mid-way through editing.
     */
    @Test
    void fallingBackDoesNotOverwriteTheStoredValue()
    {
        ConfigStore.getInt( "wrongTypeForInt", 99 );
        assertEquals( "not-a-number",
                      ConfigStore.peek().get( "wrongTypeForInt" ).getAsString(),
                      "a failed read must leave the stored value untouched" );
    }

    // =========================================================================
    //  getOrInit — key-present branch (see class javadoc for the absent branch)
    // =========================================================================

    @Test
    void getOrInitReturnsExistingValueWithoutOverwritingIt()
    {
        assertEquals( 42, ConfigStore.getOrInitInt( "presentInt", 5 ) );
        assertEquals( 42, ConfigStore.peek().get( "presentInt" ).getAsInt(),
                      "getOrInit must not overwrite a value that is already set" );
    }

    @Test
    void getOrInitBooleanReturnsExistingValueWithoutOverwritingIt()
    {
        assertTrue( ConfigStore.getOrInitBoolean( "presentBoolean", false ) );
        assertTrue( ConfigStore.peek().get( "presentBoolean" ).getAsBoolean(),
                    "getOrInit must not overwrite a value that is already set" );
    }

    // =========================================================================
    //  snapshot() isolation
    // =========================================================================

    /**
     * {@code snapshot()} exists so diagnostic and export paths can read config without
     * racing the live document. If it handed back the live object, a consumer mutating
     * its copy would silently corrupt the user's settings.
     */
    @Test
    void snapshotReturnsAnIndependentCopy()
    {
        JsonObject snap = ConfigStore.snapshot();
        assertNotSame( ConfigStore.peek(), snap, "snapshot must not alias the live document" );

        snap.addProperty( "presentString", "mutated" );
        assertEquals( "hello", ConfigStore.getString( "presentString", "fallback" ),
                      "mutating a snapshot must not affect the live config" );
    }

    // =========================================================================
    //  mutate()
    // =========================================================================

    @Test
    void mutateAppliesChangesToTheLiveDocument()
    {
        ConfigStore.mutate( doc -> doc.addProperty( "addedByMutate", "yes" ) );
        assertEquals( "yes", ConfigStore.getString( "addedByMutate", "fallback" ) );
    }
}
