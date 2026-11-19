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

package com.micatechnologies.minecraft.launcher.game.modpack.manifests;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.launcher.game.modpack.manifests.ManifestRuleUtilities.Host;
import com.micatechnologies.minecraft.launcher.game.modpack.manifests.ManifestRuleUtilities.HostOs;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for rule evaluation against an explicit {@link Host}: the seam that lets a manifest be
 * resolved for a machine other than the one running the tests, and the normalized architecture
 * names ({@code x64}, {@code arm64}, {@code x86}) Cleanroom's version profile writes in its
 * {@code os.arch} rules, which no Java {@code os.arch} contains.
 */
class ManifestRuleHostTest
{
    /** Hosts as Java reports them (os.arch values of real JDKs). */
    static final Host WINDOWS_X64 = new Host( HostOs.WINDOWS, "amd64", "10.0" );
    static final Host WINDOWS_ARM64 = new Host( HostOs.WINDOWS, "aarch64", "10.0" );
    static final Host WINDOWS_X86 = new Host( HostOs.WINDOWS, "x86", "10.0" );
    static final Host LINUX_X64 = new Host( HostOs.LINUX, "amd64", "6.1" );
    static final Host LINUX_ARM64 = new Host( HostOs.LINUX, "aarch64", "6.1" );
    static final Host MAC_INTEL = new Host( HostOs.MACOS, "x86_64", "14.0" );
    static final Host MAC_ARM = new Host( HostOs.MACOS, "aarch64", "14.0" );
    static final Host FREEBSD_X64 = new Host( HostOs.OTHER, "amd64", "14.0" );

    private static JsonArray rule( String os, String arch )
    {
        JsonObject osObj = new JsonObject();
        if ( os != null ) {
            osObj.addProperty( "name", os );
        }
        if ( arch != null ) {
            osObj.addProperty( "arch", arch );
        }
        JsonObject r = new JsonObject();
        r.addProperty( "action", "allow" );
        r.add( "os", osObj );
        JsonArray rules = new JsonArray();
        rules.add( r );
        return rules;
    }

    @Test
    void normalizedArchNamesMatchTheirHosts()
    {
        assertTrue( ManifestRuleUtilities.evaluateRules( rule( "windows", "x64" ), WINDOWS_X64 ) );
        assertFalse( ManifestRuleUtilities.evaluateRules( rule( "windows", "arm64" ), WINDOWS_X64 ) );
        assertFalse( ManifestRuleUtilities.evaluateRules( rule( "windows", "x86" ), WINDOWS_X64 ) );
        assertTrue( ManifestRuleUtilities.evaluateRules( rule( "windows", "arm64" ), WINDOWS_ARM64 ) );
        assertFalse( ManifestRuleUtilities.evaluateRules( rule( "windows", "x64" ), WINDOWS_ARM64 ) );
        assertTrue( ManifestRuleUtilities.evaluateRules( rule( "windows", "x86" ), WINDOWS_X86 ) );
        assertFalse( ManifestRuleUtilities.evaluateRules( rule( "windows", "x64" ), WINDOWS_X86 ) );
        assertTrue( ManifestRuleUtilities.evaluateRules( rule( "osx", "x64" ), MAC_INTEL ) );
        assertFalse( ManifestRuleUtilities.evaluateRules( rule( "osx", "arm64" ), MAC_INTEL ) );
        assertTrue( ManifestRuleUtilities.evaluateRules( rule( "osx", "arm64" ), MAC_ARM ) );
        assertFalse( ManifestRuleUtilities.evaluateRules( rule( "osx", "x64" ), MAC_ARM ) );
        assertTrue( ManifestRuleUtilities.evaluateRules( rule( "linux", "x64" ), LINUX_X64 ) );
        assertTrue( ManifestRuleUtilities.evaluateRules( rule( "linux", "ARM64" ), LINUX_ARM64 ) );
        assertFalse( ManifestRuleUtilities.evaluateRules( rule( "linux", "arm32" ), LINUX_ARM64 ) );
        assertFalse( ManifestRuleUtilities.evaluateRules( rule( "linux", "riscv64" ), LINUX_X64 ) );
        assertFalse( ManifestRuleUtilities.evaluateRules( rule( "freebsd", "x64" ), FREEBSD_X64 ) );
    }

    @Test
    void mojangRegexArchRulesBehaveAsBefore()
    {
        // Mojang's form is a regex found in the Java os.arch; the extra reading never widens it.
        assertTrue( ManifestRuleUtilities.evaluateRules( rule( "windows", "x86" ), WINDOWS_X86 ) );
        assertFalse( ManifestRuleUtilities.evaluateRules( rule( "windows", "x86" ), WINDOWS_X64 ) );
        assertTrue( ManifestRuleUtilities.evaluateRules( rule( "windows", "amd64" ), WINDOWS_X64 ) );
        assertTrue( ManifestRuleUtilities.evaluateRules( rule( "linux", "^aarch64$" ), LINUX_ARM64 ) );
        assertTrue( ManifestRuleUtilities.evaluateRules( rule( "osx", "x86" ), MAC_INTEL ),
                    "x86_64 contains x86: the regex reading matches, as it always did" );
        assertFalse( ManifestRuleUtilities.evaluateRules( rule( "linux", "[" ), LINUX_X64 ),
                     "an invalid regex is still a non-match" );
    }

    @Test
    void osNamesFollowTheHostNotTheMachineRunningTheTests()
    {
        assertTrue( ManifestRuleUtilities.evaluateRules( rule( "windows", null ), WINDOWS_X64 ) );
        assertFalse( ManifestRuleUtilities.evaluateRules( rule( "windows", null ), LINUX_X64 ) );
        assertTrue( ManifestRuleUtilities.evaluateRules( rule( "osx", null ), MAC_ARM ) );
        assertTrue( ManifestRuleUtilities.evaluateRules( rule( "linux", null ), LINUX_ARM64 ) );
        assertFalse( ManifestRuleUtilities.evaluateRules( rule( "linux", null ), FREEBSD_X64 ) );
    }

    @Test
    void ruleArchNames()
    {
        assertEquals( "x64", WINDOWS_X64.ruleArchName() );
        assertEquals( "x64", MAC_INTEL.ruleArchName() );
        assertEquals( "arm64", MAC_ARM.ruleArchName() );
        assertEquals( "x86", WINDOWS_X86.ruleArchName() );
        assertEquals( "x86", new Host( HostOs.LINUX, "i686", "" ).ruleArchName() );
        assertNull( new Host( HostOs.LINUX, "riscv64", "" ).ruleArchName() );
        assertNull( new Host( HostOs.LINUX, "arm", "" ).ruleArchName() );
    }

    @Test
    void platformNamesMatchTheLauncherConstants()
    {
        assertEquals( "windows", WINDOWS_X64.platformName() );
        assertEquals( "osx", MAC_ARM.platformName() );
        assertEquals( "linux", LINUX_X64.platformName() );
        assertEquals( "linux", FREEBSD_X64.platformName() );
        assertEquals( ManifestRuleUtilities.getCurrentPlatformName(), Host.current().platformName() );
    }
}
