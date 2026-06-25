/*
 * Copyright (c) 2026 Mica Technologies
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License,
 * or (at your option) any later version.
 */

package com.micatechnologies.minecraft.launcher.game.modpack.manifests;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.launcher.consts.ModPackConstants;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Utility helpers for evaluating Mojang/Forge launch rules and flattening launch argument arrays.
 */
public final class ManifestRuleUtilities
{
    /**
     * Private constructor to prevent instantiation of this utility class.
     */
    private ManifestRuleUtilities() {
    }

    /**
     * Evaluates a Mojang/Forge-style {@code rules} array against the current runtime and returns whether the gated item
     * (library, argument, etc.) is allowed.
     * <p>
     * Rules are processed in order; each rule whose conditions match the current runtime (OS name/version/arch and any
     * declared launcher features) updates the running decision based on its {@code action} ({@code "allow"} or
     * {@code "disallow"}, defaulting to {@code "allow"}). Rules that do not match the current runtime are skipped. A
     * {@code null} or empty rules array allows the item by default.
     *
     * @param rules the rules array to evaluate, or {@code null} if the item has no rules
     *
     * @return {@code true} if the item is allowed for the current runtime, {@code false} otherwise
     */
    public static boolean evaluateRules( JsonArray rules ) {
        if ( rules == null || rules.isEmpty() ) {
            return true;
        }

        boolean allowed = false;
        for ( JsonElement ruleEl : rules ) {
            if ( !ruleEl.isJsonObject() ) {
                continue;
            }

            JsonObject ruleObj = ruleEl.getAsJsonObject();
            if ( !ruleMatchesCurrentRuntime( ruleObj ) ) {
                continue;
            }

            String action = ruleObj.has( "action" ) ? ruleObj.get( "action" ).getAsString() : "allow";
            allowed = "allow".equalsIgnoreCase( action );
        }
        return allowed;
    }

    /**
     * Flattens a modern Mojang launch-argument array (1.13+ {@code arguments.jvm} / {@code arguments.game}) into a
     * single space-separated argument string for the current runtime.
     * <p>
     * Plain string entries are included directly. Object entries are included only when their {@code rules} array
     * {@linkplain #evaluateRules(JsonArray) evaluates} to allowed for the current runtime; their {@code value} may be a
     * single string or an array of strings, all of which are appended. Each emitted token is wrapped in double quotes
     * when it contains whitespace and is not already quoted (see {@link #quoteIfNeeded(String)}).
     *
     * @param arguments the argument array to flatten, or {@code null}
     *
     * @return the flattened, space-separated argument string, or an empty string if the array is {@code null} or empty
     */
    public static String flattenArguments( JsonArray arguments ) {
        if ( arguments == null || arguments.isEmpty() ) {
            return "";
        }

        List< String > flattened = new ArrayList<>();
        for ( JsonElement argEl : arguments ) {
            if ( argEl == null || argEl.isJsonNull() ) {
                continue;
            }

            if ( argEl.isJsonPrimitive() ) {
                flattened.add( quoteIfNeeded( argEl.getAsString() ) );
                continue;
            }

            if ( !argEl.isJsonObject() ) {
                continue;
            }

            JsonObject argObj = argEl.getAsJsonObject();
            if ( !evaluateRules( argObj.has( "rules" ) ? argObj.getAsJsonArray( "rules" ) : null ) ) {
                continue;
            }

            JsonElement value = argObj.get( "value" );
            if ( value == null || value.isJsonNull() ) {
                continue;
            }
            if ( value.isJsonPrimitive() ) {
                flattened.add( quoteIfNeeded( value.getAsString() ) );
            }
            else if ( value.isJsonArray() ) {
                for ( JsonElement val : value.getAsJsonArray() ) {
                    if ( val != null && val.isJsonPrimitive() ) {
                        flattened.add( quoteIfNeeded( val.getAsString() ) );
                    }
                }
            }
        }
        return String.join( " ", flattened );
    }

    /**
     * Wraps the argument in double quotes if it contains whitespace and is not already quoted. This prevents arguments
     * like {@code -Dos.name=Windows 10} from being split into multiple tokens during command-line parsing. Arguments
     * containing an unresolved {@code ${...}} placeholder are left unquoted so later substitution can occur cleanly.
     *
     * @param arg the argument to conditionally quote, may be {@code null} or empty
     *
     * @return the original argument when no quoting is required, otherwise the argument wrapped in double quotes
     */
    private static String quoteIfNeeded( String arg ) {
        if ( arg == null || arg.isEmpty() ) {
            return arg;
        }
        // Already quoted
        if ( arg.startsWith( "\"" ) && arg.endsWith( "\"" ) ) {
            return arg;
        }
        // Contains a space and is not a placeholder that will be replaced later
        if ( arg.contains( " " ) && !arg.contains( "${" ) ) {
            return "\"" + arg + "\"";
        }
        return arg;
    }

    /**
     * Returns the launcher's canonical platform name for the host operating system.
     *
     * @return {@link ModPackConstants#PLATFORM_WINDOWS} on Windows, {@link ModPackConstants#PLATFORM_MACOS} on macOS,
     *         or {@link ModPackConstants#PLATFORM_UNIX} otherwise
     */
    public static String getCurrentPlatformName() {
        if ( org.apache.commons.lang3.SystemUtils.IS_OS_WINDOWS ) {
            return ModPackConstants.PLATFORM_WINDOWS;
        }
        if ( org.apache.commons.lang3.SystemUtils.IS_OS_MAC ) {
            return ModPackConstants.PLATFORM_MACOS;
        }
        return ModPackConstants.PLATFORM_UNIX;
    }

    /**
     * Determines whether a single rule's conditions match the current runtime. A rule matches when its optional
     * {@code os} block matches the host OS and its optional {@code features} block matches the launcher's feature state.
     *
     * @param ruleObj the rule object to test
     *
     * @return {@code true} if the rule's conditions all match the current runtime, {@code false} otherwise
     */
    private static boolean ruleMatchesCurrentRuntime( JsonObject ruleObj ) {
        if ( ruleObj.has( "os" ) && !osMatches( ruleObj.getAsJsonObject( "os" ) ) ) {
            return false;
        }
        return !ruleObj.has( "features" ) || featuresMatch( ruleObj.getAsJsonObject( "features" ) );
    }

    /**
     * Evaluates a rule's {@code os} block against the host operating system. Any present subset of {@code name},
     * {@code version} (regex), {@code arch} (regex), and {@code versionRange} (min/max, MC 26.1+) must all match; absent
     * fields are not constraining. A {@code null} OS object matches.
     *
     * @param osObj the {@code os} block of a rule, or {@code null}
     *
     * @return {@code true} if every present OS constraint matches the host, {@code false} otherwise
     */
    private static boolean osMatches( JsonObject osObj ) {
        if ( osObj == null ) {
            return true;
        }

        if ( osObj.has( "name" ) ) {
            String name = osObj.get( "name" ).getAsString();
            if ( !osNameMatches( name ) ) {
                return false;
            }
        }

        if ( osObj.has( "version" ) ) {
            String regex = osObj.get( "version" ).getAsString();
            String osVersion = System.getProperty( "os.version", "" );
            if ( !regexMatches( regex, osVersion ) ) {
                return false;
            }
        }

        if ( osObj.has( "arch" ) ) {
            String regex = osObj.get( "arch" ).getAsString();
            String osArch = System.getProperty( "os.arch", "" );
            if ( !regexMatches( regex, osArch ) ) {
                return false;
            }
        }

        // MC 26.1+ uses versionRange with min/max instead of regex
        if ( osObj.has( "versionRange" ) ) {
            JsonObject versionRange = osObj.getAsJsonObject( "versionRange" );
            String osVersion = System.getProperty( "os.version", "" );
            if ( !versionRangeMatches( versionRange, osVersion ) ) {
                return false;
            }
        }

        return true;
    }

    /**
     * Matches a Mojang OS name token (case-insensitive, e.g. {@code "windows"}, {@code "osx"}, {@code "linux"}) against
     * the host operating system. Unrecognized names do not match.
     *
     * @param osName the OS name token from a rule's {@code os.name} field
     *
     * @return {@code true} if the token identifies the host operating system, {@code false} otherwise
     */
    private static boolean osNameMatches( String osName ) {
        String normalized = osName.toLowerCase( Locale.ROOT ).trim();
        if ( "bindoj".equals( normalized ) || "windows".equals( normalized ) || "win".equals( normalized ) ) {
            return org.apache.commons.lang3.SystemUtils.IS_OS_WINDOWS;
        }
        if ( "osx".equals( normalized ) || "mac".equals( normalized ) || "macos".equals( normalized ) ) {
            return org.apache.commons.lang3.SystemUtils.IS_OS_MAC;
        }
        if ( "linux".equals( normalized ) || "unix".equals( normalized ) ) {
            return org.apache.commons.lang3.SystemUtils.IS_OS_LINUX;
        }
        return false;
    }

    /**
     * Evaluates a rule's {@code features} block. This launcher does not implement Mojang's optional launcher feature
     * toggles, so every feature is treated as disabled; the block matches only if it requests no feature to be enabled.
     * A {@code null} features object matches.
     *
     * @param features the {@code features} block of a rule, or {@code null}
     *
     * @return {@code true} if no requested feature is expected to be enabled, {@code false} otherwise
     */
    private static boolean featuresMatch( JsonObject features ) {
        if ( features == null ) {
            return true;
        }

        for ( String featureKey : features.keySet() ) {
            // This launcher does not expose optional launcher feature toggles used by Mojang.
            // Treat all features as disabled unless implemented explicitly.
            boolean expectedValue = features.get( featureKey ).getAsBoolean();
            boolean actualValue = false;
            if ( expectedValue != actualValue ) {
                return false;
            }
        }
        return true;
    }

    /**
     * Tests whether the given regular expression finds a match anywhere within {@code value}. An invalid pattern is
     * treated as a non-match rather than propagating an exception.
     *
     * @param pattern the regular expression to compile and apply
     * @param value   the value to test against the pattern
     *
     * @return {@code true} if the pattern matches within the value, {@code false} on no match or an invalid pattern
     */
    private static boolean regexMatches( String pattern, String value ) {
        try {
            return Pattern.compile( pattern ).matcher( value ).find();
        }
        catch ( PatternSyntaxException ignored ) {
            return false;
        }
    }

    /**
     * Compares an OS version string against a versionRange object with optional min and max fields. Version components
     * are compared numerically (e.g. "10.0.17134" >= "10.0.17134"). A {@code null} range or empty OS version matches.
     *
     * @param versionRange the {@code versionRange} object holding optional {@code min} / {@code max} version strings
     * @param osVersion    the host OS version string to test
     *
     * @return {@code true} if the OS version falls within the inclusive range, {@code false} otherwise
     */
    private static boolean versionRangeMatches( JsonObject versionRange, String osVersion ) {
        if ( versionRange == null || osVersion.isEmpty() ) {
            return true;
        }

        int[] current = parseVersionComponents( osVersion );

        if ( versionRange.has( "min" ) ) {
            int[] min = parseVersionComponents( versionRange.get( "min" ).getAsString() );
            if ( compareVersionComponents( current, min ) < 0 ) {
                return false;
            }
        }

        if ( versionRange.has( "max" ) ) {
            int[] max = parseVersionComponents( versionRange.get( "max" ).getAsString() );
            if ( compareVersionComponents( current, max ) > 0 ) {
                return false;
            }
        }

        return true;
    }

    /**
     * Parses a version string like "10.0.17134" into an array of integer components. Non-numeric characters within a
     * component are stripped, and any component that cannot be parsed is treated as {@code 0}.
     *
     * @param version the dot-separated version string to parse
     *
     * @return an array of integer version components, one per dot-separated segment
     */
    private static int[] parseVersionComponents( String version ) {
        String[] parts = version.split( "\\." );
        int[] components = new int[ parts.length ];
        for ( int i = 0; i < parts.length; i++ ) {
            try {
                components[ i ] = Integer.parseInt( parts[ i ].replaceAll( "[^0-9]", "" ) );
            }
            catch ( NumberFormatException e ) {
                components[ i ] = 0;
            }
        }
        return components;
    }

    /**
     * Compares two version component arrays lexicographically, treating missing trailing components as {@code 0}.
     * Returns negative if a &lt; b, zero if equal, positive if a &gt; b.
     *
     * @param a the first version component array
     * @param b the second version component array
     *
     * @return a negative integer, zero, or a positive integer as {@code a} is less than, equal to, or greater than
     *         {@code b}
     */
    private static int compareVersionComponents( int[] a, int[] b ) {
        int length = Math.max( a.length, b.length );
        for ( int i = 0; i < length; i++ ) {
            int aVal = i < a.length ? a[ i ] : 0;
            int bVal = i < b.length ? b[ i ] : 0;
            if ( aVal != bVal ) {
                return Integer.compare( aVal, bVal );
            }
        }
        return 0;
    }
}
