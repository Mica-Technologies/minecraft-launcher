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
    private ManifestRuleUtilities() {
    }

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
     * like {@code -Dos.name=Windows 10} from being split into multiple tokens during command-line parsing.
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

    public static String getCurrentPlatformName() {
        if ( org.apache.commons.lang3.SystemUtils.IS_OS_WINDOWS ) {
            return ModPackConstants.PLATFORM_WINDOWS;
        }
        if ( org.apache.commons.lang3.SystemUtils.IS_OS_MAC ) {
            return ModPackConstants.PLATFORM_MACOS;
        }
        return ModPackConstants.PLATFORM_UNIX;
    }

    private static boolean ruleMatchesCurrentRuntime( JsonObject ruleObj ) {
        if ( ruleObj.has( "os" ) && !osMatches( ruleObj.getAsJsonObject( "os" ) ) ) {
            return false;
        }
        return !ruleObj.has( "features" ) || featuresMatch( ruleObj.getAsJsonObject( "features" ) );
    }

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
     * are compared numerically (e.g. "10.0.17134" >= "10.0.17134").
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
     * Parses a version string like "10.0.17134" into an array of integer components.
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
     * Compares two version component arrays. Returns negative if a &lt; b, zero if equal, positive if a &gt; b.
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
