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

package com.micatechnologies.minecraft.launcher.mcp.approval;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.launcher.utilities.JSONUtilities;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The user's per-tool approval choices, persisted across launcher runs.
 * <p>
 * Unlike the session grants in {@link McpGrantStore}, these are deliberate, durable settings
 * made on the Settings page: "always allow this tool", "always ask", "never".
 * <p>
 * <b>Every failure resolves to "unset", never to a policy.</b> Malformed JSON, an unrecognised
 * policy name, a wrong JSON type, a backing store that throws — all of them read as no policy,
 * which sends the tool to its risk-class default, and that default is {@code ASK} for anything
 * that can change or run something. The opposite convention would mean a corrupted config file
 * could silently grant standing permission to delete modpacks, so the safe direction is not
 * negotiable here.
 *
 * @author Mica Technologies
 * @version 1.0
 * @since 3.0
 */
public final class McpToolPolicyStore
{
    /**
     * Where the serialized policies live. Abstracted so the store can be tested without
     * standing up the launcher's config machinery.
     *
     * @since 3.0
     */
    public interface Backing
    {
        /**
         * Reads the stored JSON.
         *
         * @return the JSON text, or {@code ""} / {@code null} when nothing is stored
         *
         * @since 3.0
         */
        String read();

        /**
         * Writes the JSON.
         *
         * @param json the JSON text to store
         *
         * @since 3.0
         */
        void write( String json );
    }

    /** Where the serialized policies live. */
    private final Backing backing;

    /**
     * Constructs a store.
     *
     * @param backing where the serialized policies live
     *
     * @throws IllegalArgumentException if {@code backing} is {@code null}
     * @since 3.0
     */
    public McpToolPolicyStore( Backing backing )
    {
        if ( backing == null ) {
            throw new IllegalArgumentException( "A backing store is required" );
        }
        this.backing = backing;
    }

    /**
     * Returns the user's explicit policy for one tool.
     *
     * @param toolName the tool
     *
     * @return the policy, or {@code null} when the user has set none — including every case
     *         where the stored value could not be understood
     *
     * @since 3.0
     */
    public McpApprovalPolicy policyFor( String toolName )
    {
        String key = normalize( toolName );
        if ( key == null ) {
            return null;
        }
        return load().get( key );
    }

    /**
     * Sets or clears the policy for one tool.
     *
     * @param toolName the tool
     * @param policy   the policy to store, or {@code null} to clear it back to the risk-class
     *                 default
     *
     * @since 3.0
     */
    public void setPolicy( String toolName, McpApprovalPolicy policy )
    {
        String key = normalize( toolName );
        if ( key == null ) {
            return;
        }
        Map< String, McpApprovalPolicy > policies = load();
        if ( policy == null ) {
            policies.remove( key );
        }
        else {
            policies.put( key, policy );
        }
        save( policies );
    }

    /**
     * Returns every explicitly set policy, keyed by tool name.
     *
     * @return the policies, in tool-name order; empty when none are set
     *
     * @since 3.0
     */
    public Map< String, McpApprovalPolicy > all()
    {
        return new TreeMap<>( load() );
    }

    /**
     * Clears every explicit policy, returning all tools to their risk-class defaults.
     *
     * @since 3.0
     */
    public void clear()
    {
        save( new LinkedHashMap<>() );
    }

    /**
     * Parses the stored JSON.
     * <p>
     * Every unreadable case yields an empty map rather than throwing: this is read on the tool
     * dispatch path, and a corrupted config must degrade to "ask the user" rather than
     * breaking every call.
     *
     * @return the stored policies, keyed by normalized tool name
     */
    private Map< String, McpApprovalPolicy > load()
    {
        Map< String, McpApprovalPolicy > policies = new LinkedHashMap<>();
        String json;
        try {
            json = backing.read();
        }
        catch ( Exception e ) {
            return policies;
        }
        if ( json == null || json.isBlank() ) {
            return policies;
        }

        JsonObject root;
        try {
            root = JSONUtilities.getGson().fromJson( json, JsonObject.class );
        }
        catch ( Exception e ) {
            return policies;
        }
        if ( root == null ) {
            return policies;
        }

        for ( Map.Entry< String, JsonElement > entry : root.entrySet() ) {
            String key = normalize( entry.getKey() );
            if ( key == null || entry.getValue() == null || !entry.getValue().isJsonPrimitive() ) {
                continue;
            }
            McpApprovalPolicy policy = parsePolicy( entry.getValue().getAsString() );
            if ( policy != null ) {
                policies.put( key, policy );
            }
        }
        return policies;
    }

    /**
     * Serializes and stores the policies.
     *
     * @param policies the policies to store
     */
    private void save( Map< String, McpApprovalPolicy > policies )
    {
        JsonObject root = new JsonObject();
        for ( Map.Entry< String, McpApprovalPolicy > entry : new TreeMap<>( policies ).entrySet() ) {
            root.addProperty( entry.getKey(), entry.getValue().name() );
        }
        try {
            backing.write( JSONUtilities.getGson().toJson( root ) );
        }
        catch ( Exception ignored ) {
            // A failed write leaves the in-memory decision correct for this call and the
            // stored state unchanged. Throwing out of a Settings toggle would be worse.
        }
    }

    /**
     * Parses a stored policy name.
     *
     * @param value the stored value
     *
     * @return the policy, or {@code null} when the value is not one this build understands —
     *         which includes a policy name added by a newer build
     */
    private static McpApprovalPolicy parsePolicy( String value )
    {
        if ( value == null ) {
            return null;
        }
        try {
            return McpApprovalPolicy.valueOf( value.trim().toUpperCase( Locale.ROOT ) );
        }
        catch ( IllegalArgumentException e ) {
            return null;
        }
    }

    /**
     * Normalizes a tool name for use as a key.
     *
     * @param toolName the tool name
     *
     * @return the normalized name, or {@code null} when it is unusable
     */
    private static String normalize( String toolName )
    {
        if ( toolName == null || toolName.isBlank() ) {
            return null;
        }
        return toolName.trim().toLowerCase( Locale.ROOT );
    }
}
