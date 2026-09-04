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

package com.micatechnologies.minecraft.launcher.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link SupplementalScanner} — the heuristic malware scanner that
 * decides whether a modpack's jars are safe to run alongside Nekodetector. A
 * regression here is not cosmetic: a false negative in a HIGH-severity rule
 * (embedded executables, Discord webhooks, paste-host URLs, launcher
 * credential-file references) ships malware to the user's machine with the
 * launcher's blessing, and an over-eager rule that fires on vanilla Minecraft
 * content trains users to click through (or disable) the scan entirely.
 *
 * <p>Several {@code private static} decision-table methods were widened to
 * package-private (see the {@code SupplementalScanner} source for the
 * "widened for testing" comments) so they can be exercised directly, per the
 * repo's documented preference for a widened seam over reflection. The
 * higher-level {@link SupplementalScanner#scanJar} / {@link
 * SupplementalScanner#scanFolder} entry points are exercised against real
 * (tiny, synthetic) JAR files built in a {@code @TempDir} — including class
 * files generated on the fly with ASM — so the end-to-end verdict is pinned,
 * not just the individual helper predicates.</p>
 */
class SupplementalScannerTest
{
    // ------------------------------------------------------------------
    // looksLikeRealIp
    // ------------------------------------------------------------------

    private static Matcher dottedQuad( String s )
    {
        Matcher m = Pattern.compile( "(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})" ).matcher( s );
        assertTrue( m.find(), "test fixture string must contain a dotted-quad: " + s );
        return m;
    }

    @Test
    void looksLikeRealIp_acceptsValidOctets()
    {
        assertTrue( SupplementalScanner.looksLikeRealIp( dottedQuad( "1.2.3.4" ) ) );
        assertTrue( SupplementalScanner.looksLikeRealIp( dottedQuad( "255.255.255.255" ) ) );
        assertTrue( SupplementalScanner.looksLikeRealIp( dottedQuad( "0.0.0.0" ) ) );
    }

    @Test
    void looksLikeRealIp_rejectsOutOfRangeOctet()
    {
        // 256 doesn't match \d{1,3} as a single group value > 255.
        assertFalse( SupplementalScanner.looksLikeRealIp( dottedQuad( "256.1.1.1" ) ) );
        assertFalse( SupplementalScanner.looksLikeRealIp( dottedQuad( "999.1.1.1" ) ) );
    }

    // ------------------------------------------------------------------
    // isFalsePositiveIp
    // ------------------------------------------------------------------

    private static boolean isFp( String s )
    {
        return SupplementalScanner.isFalsePositiveIp( s, dottedQuad( s ) );
    }

    @Test
    void isFalsePositiveIp_flagsRfc1918AndReservedRanges()
    {
        assertTrue( isFp( "10.0.0.5" ), "10.0.0.0/8 is RFC 1918 private" );
        assertTrue( isFp( "192.168.1.5" ), "192.168.0.0/16 is RFC 1918 private" );
        assertTrue( isFp( "172.20.3.4" ), "172.16.0.0/12 is RFC 1918 private" );
        assertTrue( isFp( "169.254.1.1" ), "169.254.0.0/16 is link-local" );
        assertTrue( isFp( "100.64.0.1" ), "100.64.0.0/10 is CGN (RFC 6598)" );
        assertTrue( isFp( "192.0.2.5" ), "192.0.2.0/24 is TEST-NET-1" );
        assertTrue( isFp( "198.51.100.7" ), "198.51.100.0/24 is TEST-NET-2" );
        assertTrue( isFp( "203.0.113.9" ), "203.0.113.0/24 is TEST-NET-3" );
        assertTrue( isFp( "198.18.0.1" ), "198.18.0.0/15 is benchmarking" );
        assertTrue( isFp( "192.0.0.5" ), "192.0.0.0/24 is IETF protocol assignment" );
    }

    @Test
    void isFalsePositiveIp_flagsWellKnownPublicDnsResolvers()
    {
        // FancyMenu-style connectivity probes dial these; treated as noise, not C2.
        for ( String resolver : List.of( "8.8.8.8", "8.8.4.4", "1.1.1.1", "1.0.0.1",
                                         "9.9.9.9", "149.112.112.112", "208.67.222.222", "208.67.220.220" ) ) {
            assertTrue( isFp( resolver ), "well-known DNS resolver must be suppressed: " + resolver );
        }
    }

    @Test
    void isFalsePositiveIp_flagsTrailingZeroPad()
    {
        assertTrue( isFp( "114.0.0.0" ), "X.0.0.0 is a version-major sentinel, not an endpoint" );
        assertTrue( isFp( "1.7.0.0" ), "X.Y.0.0 pad also suppressed" );
    }

    @Test
    void isFalsePositiveIp_flagsBareVersionStringOutsideUrlContext()
    {
        // FAWE's Jars constant: a bare small-leading-octet literal with no URL context
        // is treated as a version string, not a dialed endpoint.
        assertTrue( isFp( "6.1.7.2" ) );
    }

    @Test
    void isFalsePositiveIp_doesNotSuppressSmallOctetIpUsedAsUrlHost()
    {
        // Security-critical case: an attacker could try to hide a C2 endpoint behind a
        // small leading octet (which the version-string heuristic would otherwise
        // suppress) by simply putting it in URL-host position. The isIpUrlHost override
        // must defeat the version-string suppression here.
        assertFalse( isFp( "http://6.1.7.2/payload" ),
                "an IPv4 in URL-host position must not be suppressed even with a small leading octet" );
    }

    @Test
    void isFalsePositiveIp_realPublicIpIsNotSuppressed()
    {
        // A real-world public IP (DigitalOcean-range) with a large leading octet, not in
        // any reserved range, not a resolver, not a trailing-zero pad — this must survive
        // as a genuine C2-endpoint smell.
        assertFalse( isFp( "45.33.32.156" ) );
    }

    // ------------------------------------------------------------------
    // isIpUrlHost
    // ------------------------------------------------------------------

    @Test
    void isIpUrlHost_detectsSchemeSeparatorPosition()
    {
        String s = "http://1.2.3.4/x";
        int ipStart = s.indexOf( '1' );
        assertTrue( SupplementalScanner.isIpUrlHost( s, ipStart ) );
    }

    @Test
    void isIpUrlHost_detectsUserinfoSeparatorPosition()
    {
        String s = "user:pass@1.2.3.4";
        int ipStart = s.indexOf( '1' );
        assertTrue( SupplementalScanner.isIpUrlHost( s, ipStart ) );
    }

    @Test
    void isIpUrlHost_returnsFalseForPathOrQueryPosition()
    {
        String s = "path/to/1.2.3.4/file";
        int ipStart = s.indexOf( '1' );
        assertFalse( SupplementalScanner.isIpUrlHost( s, ipStart ) );
    }

    @Test
    void isIpUrlHost_handlesDegenerateInputsWithoutCrashing()
    {
        assertFalse( SupplementalScanner.isIpUrlHost( null, 5 ) );
        assertFalse( SupplementalScanner.isIpUrlHost( "x", -1 ) );
        assertFalse( SupplementalScanner.isIpUrlHost( "1.2.3.4", 0 ) );
    }

    // ------------------------------------------------------------------
    // isInLegitNativePath / hasLegitNativeFilename
    // ------------------------------------------------------------------

    @Test
    void isInLegitNativePath_matchesKnownPrefixesAnywhereInPath()
    {
        assertTrue( SupplementalScanner.isInLegitNativePath( "natives/foo.dll" ) );
        assertTrue( SupplementalScanner.isInLegitNativePath( "lib/x86_64-linux/jni/libfoo.so" ) );
        assertTrue( SupplementalScanner.isInLegitNativePath( "assets/opencomputers/lib/libfoo.so" ) );
        assertTrue( SupplementalScanner.isInLegitNativePath( "org/lwjgl/system/libfoo.so" ) );
        assertTrue( SupplementalScanner.isInLegitNativePath( "aarch64-macosx-clang/jni/libfoo.dylib" ) );
    }

    @Test
    void isInLegitNativePath_rejectsBareRootDrop()
    {
        assertFalse( SupplementalScanner.isInLegitNativePath( "weird.dll" ) );
        assertFalse( SupplementalScanner.isInLegitNativePath( "com/evil/payload.dll" ) );
    }

    @Test
    void hasLegitNativeFilename_acceptsUnixLibPrefixAndWindowsDllSuffix()
    {
        assertTrue( SupplementalScanner.hasLegitNativeFilename( "libasyncprofiler.so" ) );
        assertTrue( SupplementalScanner.hasLegitNativeFilename( "path/to/libfoo.dylib" ) );
        assertTrue( SupplementalScanner.hasLegitNativeFilename( "sdl2gdx64.dll" ) );
    }

    @Test
    void hasLegitNativeFilename_rejectsNonConformingBareDrop()
    {
        assertFalse( SupplementalScanner.hasLegitNativeFilename( "random.jnilib" ) );
        assertFalse( SupplementalScanner.hasLegitNativeFilename( "path/random.so" ) );
    }

    // ------------------------------------------------------------------
    // normalizeExclusions / isExcluded
    // ------------------------------------------------------------------

    @Test
    void normalizeExclusions_trimsSlashesBackslashesCaseAndDropsBlanks()
    {
        List< String > out = SupplementalScanner.normalizeExclusions(
                List.of( " /Mods/ ", "\\Libraries\\", "", "   ", "custom-Tools" ) );
        assertEquals( List.of( "mods", "libraries", "custom-tools" ), out );
    }

    @Test
    void normalizeExclusions_handlesNullAndEmpty()
    {
        assertTrue( SupplementalScanner.normalizeExclusions( null ).isEmpty() );
        assertTrue( SupplementalScanner.normalizeExclusions( List.of() ).isEmpty() );
    }

    @Test
    void isExcluded_matchesExactRelativePathCaseInsensitively( @TempDir Path tmp ) throws IOException
    {
        Path target = tmp.resolve( "Libraries" );
        Files.createDirectories( target );
        assertTrue( SupplementalScanner.isExcluded( tmp, target, List.of( "libraries" ) ) );
    }

    @Test
    void isExcluded_matchesDirectoryPrefixOfDeeperPath( @TempDir Path tmp ) throws IOException
    {
        Path target = tmp.resolve( "libraries" ).resolve( "foo" ).resolve( "bar.jar" );
        Files.createDirectories( target.getParent() );
        Files.createFile( target );
        assertTrue( SupplementalScanner.isExcluded( tmp, target, List.of( "libraries" ) ) );
    }

    @Test
    void isExcluded_doesNotMatchUnrelatedSiblingWithSamePrefix( @TempDir Path tmp ) throws IOException
    {
        // "libraries-extra" must not be treated as under "libraries" just because it
        // starts with the same characters.
        Path target = tmp.resolve( "libraries-extra" );
        Files.createDirectories( target );
        assertFalse( SupplementalScanner.isExcluded( tmp, target, List.of( "libraries" ) ) );
    }

    @Test
    void isExcluded_rootItselfIsNeverExcluded( @TempDir Path tmp )
    {
        assertFalse( SupplementalScanner.isExcluded( tmp, tmp, List.of( "libraries" ) ) );
    }

    @Test
    void isExcluded_emptyExclusionListExcludesNothing( @TempDir Path tmp )
    {
        assertFalse( SupplementalScanner.isExcluded( tmp, tmp.resolve( "mods" ), List.of() ) );
    }

    // ------------------------------------------------------------------
    // sha256Hex / computeJarSha256
    // ------------------------------------------------------------------

    @Test
    void sha256Hex_nullBytesReturnsNull()
    {
        assertNull( SupplementalScanner.sha256Hex( null ) );
    }

    @Test
    void sha256Hex_isConsistentAndDistinguishesInput() throws Exception
    {
        byte[] a = "hello".getBytes( java.nio.charset.StandardCharsets.UTF_8 );
        byte[] b = "world".getBytes( java.nio.charset.StandardCharsets.UTF_8 );
        String hashA1 = SupplementalScanner.sha256Hex( a );
        String hashA2 = SupplementalScanner.sha256Hex( a );
        String hashB = SupplementalScanner.sha256Hex( b );
        assertEquals( hashA1, hashA2 );
        assertNotEquals( hashA1, hashB );
        assertEquals( 64, hashA1.length(), "hex-encoded SHA-256 must be 64 characters" );

        MessageDigest md = MessageDigest.getInstance( "SHA-256" );
        String expected = toHex( md.digest( a ) );
        assertEquals( expected, hashA1 );
    }

    @Test
    void computeJarSha256_nullPathReturnsNull()
    {
        assertNull( SupplementalScanner.computeJarSha256( null ) );
    }

    @Test
    void computeJarSha256_missingFileReturnsNullRatherThanThrowing( @TempDir Path tmp )
    {
        Path missing = tmp.resolve( "does-not-exist.jar" );
        assertNull( SupplementalScanner.computeJarSha256( missing ) );
    }

    @Test
    void computeJarSha256_matchesManualDigestOfFileBytes( @TempDir Path tmp ) throws Exception
    {
        Path file = tmp.resolve( "sample.bin" );
        byte[] content = "some jar-shaped bytes".getBytes( java.nio.charset.StandardCharsets.UTF_8 );
        Files.write( file, content );

        String actual = SupplementalScanner.computeJarSha256( file );
        MessageDigest md = MessageDigest.getInstance( "SHA-256" );
        String expected = toHex( md.digest( content ) );
        assertEquals( expected, actual );
    }

    private static String toHex( byte[] digest )
    {
        StringBuilder sb = new StringBuilder( digest.length * 2 );
        for ( byte b : digest ) sb.append( String.format( "%02x", b ) );
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // scanJar — filename-based checks
    // ------------------------------------------------------------------

    @Test
    void scanJar_flagsEmbeddedExecutableAsHigh( @TempDir Path tmp ) throws IOException
    {
        Path jarPath = buildJar( tmp, "mod.jar", Map.of( "payload.exe", new byte[]{ 1, 2, 3 } ) );
        List< SupplementalScanner.Finding > findings = scan( jarPath );

        assertEquals( 1, findings.size() );
        SupplementalScanner.Finding f = findings.get( 0 );
        assertEquals( SupplementalScanner.Severity.HIGH, f.severity() );
        assertEquals( SupplementalScanner.Kind.EMBEDDED_EXECUTABLE, f.kind() );
    }

    @Test
    void scanJar_flagsScriptSuffixesAsHigh( @TempDir Path tmp ) throws IOException
    {
        Path jarPath = buildJar( tmp, "mod.jar", Map.of(
                "installer.ps1", new byte[]{ 1 },
                "hidden.vbs", new byte[]{ 1 },
                "run.bat", new byte[]{ 1 } ) );
        List< SupplementalScanner.Finding > findings = scan( jarPath );

        assertEquals( 3, findings.size() );
        assertTrue( findings.stream().allMatch(
                f -> f.severity() == SupplementalScanner.Severity.HIGH
                        && f.kind() == SupplementalScanner.Kind.EMBEDDED_EXECUTABLE ) );
    }

    @Test
    void scanJar_doesNotFlagJavaScriptResourceFiles( @TempDir Path tmp ) throws IOException
    {
        // Deliberately not flagged per SupplementalScanner's documented rationale:
        // .js has no auto-execution path in the JVM and legitimate mods (FAWE,
        // MaryTTS) ship .js resources routinely.
        Path jarPath = buildJar( tmp, "mod.jar", Map.of( "cs_adv.js", new byte[]{ 1, 2, 3 } ) );
        assertTrue( scan( jarPath ).isEmpty() );
    }

    @Test
    void scanJar_flagsNativeBinaryOutsideKnownPathAsMedium( @TempDir Path tmp ) throws IOException
    {
        // Deliberately NOT "weird.dll" and NOT "libweird.so": hasLegitNativeFilename()
        // treats a bare .dll or a lib-prefixed name as legitimate by filename alone
        // regardless of location, so this fixture uses a non-lib-prefixed .jnilib to
        // actually exercise the "outside known path" branch.
        Path jarPath = buildJar( tmp, "suspicious-mod.jar", Map.of( "weird.jnilib", new byte[]{ 1, 2, 3 } ) );
        List< SupplementalScanner.Finding > findings = scan( jarPath );

        assertEquals( 1, findings.size() );
        SupplementalScanner.Finding f = findings.get( 0 );
        assertEquals( SupplementalScanner.Severity.MEDIUM, f.severity() );
        assertEquals( SupplementalScanner.Kind.NATIVE_OUTSIDE_KNOWN_PATH, f.kind() );
    }

    @Test
    void scanJar_doesNotFlagBareDllDropByFilenameConventionAlone( @TempDir Path tmp ) throws IOException
    {
        // Pins the documented (intentional, not a bug) behavior: hasLegitNativeFilename()
        // treats ANY bare-root ".dll" as legitimate by filename convention alone, since
        // real DLL side-loaders need Runtime.exec/regsvr32/rundll32 machinery that the
        // embedded-executable check catches first at HIGH severity.
        Path jarPath = buildJar( tmp, "suspicious-mod.jar", Map.of( "weird.dll", new byte[]{ 1, 2, 3 } ) );
        assertTrue( scan( jarPath ).isEmpty() );
    }

    @Test
    void scanJar_doesNotFlagNativeBinaryInsideLegitPath( @TempDir Path tmp ) throws IOException
    {
        Path jarPath = buildJar( tmp, "lwjgl.jar", Map.of( "natives/windows/lwjgl.dll", new byte[]{ 1 } ) );
        assertTrue( scan( jarPath ).isEmpty() );
    }

    @Test
    void scanJar_suppressesNativeCheckForMojangNativesJarButStillFlagsExecutable( @TempDir Path tmp )
            throws IOException
    {
        Path jarPath = buildJar( tmp, "lwjgl-platform-2.9.1-natives-windows.jar", Map.of(
                "lwjgl.dll", new byte[]{ 1 },
                "trojan.exe", new byte[]{ 1 } ) );
        List< SupplementalScanner.Finding > findings = scan( jarPath );

        assertEquals( 1, findings.size(), "the natives-jar suppression must not blanket-suppress .exe" );
        assertEquals( SupplementalScanner.Kind.EMBEDDED_EXECUTABLE, findings.get( 0 ).kind() );
    }

    @Test
    void scanJar_stampsMatchingFileAndInnerSha256OnFinding( @TempDir Path tmp ) throws Exception
    {
        byte[] entryBytes = "malicious content".getBytes( java.nio.charset.StandardCharsets.UTF_8 );
        Path jarPath = buildJar( tmp, "mod.jar", Map.of( "bad.exe", entryBytes ) );
        List< SupplementalScanner.Finding > findings = scan( jarPath );

        assertEquals( 1, findings.size() );
        SupplementalScanner.Finding f = findings.get( 0 );

        MessageDigest md = MessageDigest.getInstance( "SHA-256" );
        String expectedInner = toHex( md.digest( entryBytes ) );
        assertEquals( expectedInner, f.innerSha256() );

        String expectedOuter = SupplementalScanner.computeJarSha256( jarPath );
        assertEquals( expectedOuter, f.fileSha256() );
        assertNotNull( expectedOuter );
    }

    // ------------------------------------------------------------------
    // scanJar — class constant-pool checks (bytecode built with ASM)
    // ------------------------------------------------------------------

    /** Builds a minimal class whose {@code trigger()} method LDCs the given string constant. */
    private static byte[] classWithLdcString( String internalName, String constant )
    {
        ClassWriter cw = new ClassWriter( ClassWriter.COMPUTE_MAXS );
        cw.visit( Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null );
        MethodVisitor mv = cw.visitMethod( Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "trigger", "()V", null, null );
        mv.visitCode();
        mv.visitLdcInsn( constant );
        mv.visitInsn( Opcodes.POP );
        mv.visitInsn( Opcodes.RETURN );
        mv.visitMaxs( 0, 0 );
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Builds a minimal class with two static methods that each call a no-arg static method
     *  on the given owner class — used to fabricate the AWT-Robot + Clipboard co-occurrence
     *  fingerprint without needing the actual JDK classes to be invokable. */
    private static byte[] classWithTwoMethodCalls( String internalName, String owner1, String owner2 )
    {
        ClassWriter cw = new ClassWriter( ClassWriter.COMPUTE_MAXS );
        cw.visit( Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null );
        MethodVisitor m1 = cw.visitMethod( Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "step1", "()V", null, null );
        m1.visitCode();
        m1.visitMethodInsn( Opcodes.INVOKESTATIC, owner1, "op", "()V", false );
        m1.visitInsn( Opcodes.RETURN );
        m1.visitMaxs( 0, 0 );
        m1.visitEnd();

        MethodVisitor m2 = cw.visitMethod( Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "step2", "()V", null, null );
        m2.visitCode();
        m2.visitMethodInsn( Opcodes.INVOKESTATIC, owner2, "op", "()V", false );
        m2.visitInsn( Opcodes.RETURN );
        m2.visitMaxs( 0, 0 );
        m2.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    @Test
    void scanJar_flagsDiscordWebhookUrlAsHigh( @TempDir Path tmp ) throws IOException
    {
        String webhook = "https://discord.com/api/webhooks/111111111111111111/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
        byte[] classBytes = classWithLdcString( "com/evil/Stealer", webhook );
        Path jarPath = buildJar( tmp, "mod.jar", Map.of( "com/evil/Stealer.class", classBytes ) );

        List< SupplementalScanner.Finding > findings = scan( jarPath );
        assertEquals( 1, findings.size() );
        assertEquals( SupplementalScanner.Severity.HIGH, findings.get( 0 ).severity() );
        assertEquals( SupplementalScanner.Kind.DISCORD_WEBHOOK_URL, findings.get( 0 ).kind() );
    }

    @Test
    void scanJar_flagsSuspiciousPasteHostUrlAsHigh( @TempDir Path tmp ) throws IOException
    {
        byte[] classBytes = classWithLdcString( "com/evil/Stage2", "https://pastebin.com/raw/AbCdEfGh" );
        Path jarPath = buildJar( tmp, "mod.jar", Map.of( "com/evil/Stage2.class", classBytes ) );

        List< SupplementalScanner.Finding > findings = scan( jarPath );
        assertEquals( 1, findings.size() );
        assertEquals( SupplementalScanner.Severity.HIGH, findings.get( 0 ).severity() );
        assertEquals( SupplementalScanner.Kind.SUSPICIOUS_HOST_URL, findings.get( 0 ).kind() );
    }

    @Test
    void scanJar_flagsLauncherCredentialFileReferenceAsHigh( @TempDir Path tmp ) throws IOException
    {
        byte[] classBytes = classWithLdcString( "com/evil/Grabber",
                "AppData/Roaming/.minecraft/launcher_profiles.json" );
        Path jarPath = buildJar( tmp, "mod.jar", Map.of( "com/evil/Grabber.class", classBytes ) );

        List< SupplementalScanner.Finding > findings = scan( jarPath );
        assertEquals( 1, findings.size() );
        assertEquals( SupplementalScanner.Severity.HIGH, findings.get( 0 ).severity() );
        assertEquals( SupplementalScanner.Kind.LAUNCHER_CREDENTIAL_FILE_REF, findings.get( 0 ).kind() );
    }

    @Test
    void scanJar_doesNotFlagUsercacheJsonReference( @TempDir Path tmp ) throws IOException
    {
        // Documented deliberate exclusion: usercache.json is vanilla MC's username->UUID
        // cache written by MinecraftServer, not a launcher credential store. Flagging it
        // would FP every vanilla server-mode modpack launch.
        byte[] classBytes = classWithLdcString( "net/minecraft/server/MinecraftServer", "usercache.json" );
        Path jarPath = buildJar( tmp, "minecraft.jar", Map.of(
                "net/minecraft/server/MinecraftServer.class", classBytes ) );

        assertTrue( scan( jarPath ).isEmpty() );
    }

    @Test
    void scanJar_flagsGenuineHardcodedIpv4LiteralAsMedium( @TempDir Path tmp ) throws IOException
    {
        byte[] classBytes = classWithLdcString( "com/evil/C2", "45.33.32.156" );
        Path jarPath = buildJar( tmp, "mod.jar", Map.of( "com/evil/C2.class", classBytes ) );

        List< SupplementalScanner.Finding > findings = scan( jarPath );
        assertEquals( 1, findings.size() );
        assertEquals( SupplementalScanner.Severity.MEDIUM, findings.get( 0 ).severity() );
        assertEquals( SupplementalScanner.Kind.IPV4_LITERAL, findings.get( 0 ).kind() );
    }

    @Test
    void scanJar_doesNotFlagLoopbackOrMulticastLiterals( @TempDir Path tmp ) throws IOException
    {
        byte[] loopback = classWithLdcString( "com/mod/A", "127.0.0.1" );
        byte[] multicast = classWithLdcString( "com/mod/B", "224.0.2.60" ); // MC LAN-discovery address
        Path jarPath = buildJar( tmp, "mod.jar", Map.of(
                "com/mod/A.class", loopback,
                "com/mod/B.class", multicast ) );

        assertTrue( scan( jarPath ).isEmpty() );
    }

    @Test
    void scanJar_flagsClipboardStealerFingerprintWhenBothApisPresent( @TempDir Path tmp ) throws IOException
    {
        byte[] classBytes = classWithTwoMethodCalls( "com/evil/ClipStealer",
                "java/awt/Robot", "java/awt/datatransfer/Clipboard" );
        Path jarPath = buildJar( tmp, "mod.jar", Map.of( "com/evil/ClipStealer.class", classBytes ) );

        List< SupplementalScanner.Finding > findings = scan( jarPath );
        assertEquals( 1, findings.size() );
        assertEquals( SupplementalScanner.Severity.MEDIUM, findings.get( 0 ).severity() );
        assertEquals( SupplementalScanner.Kind.CLIPBOARD_STEALER_PATTERN, findings.get( 0 ).kind() );
    }

    @Test
    void scanJar_doesNotFlagClipboardApiAloneWithoutRobot( @TempDir Path tmp ) throws IOException
    {
        // A "click to copy server IP" utility mod uses Clipboard without Robot — must not
        // trip the stealer fingerprint, which requires BOTH APIs together.
        byte[] classBytes = classWithTwoMethodCalls( "com/util/CopyIp",
                "java/lang/String", "java/awt/datatransfer/Clipboard" );
        Path jarPath = buildJar( tmp, "mod.jar", Map.of( "com/util/CopyIp.class", classBytes ) );

        assertTrue( scan( jarPath ).isEmpty() );
    }

    // ------------------------------------------------------------------
    // scanFolder — exclusion structural guarantees
    // ------------------------------------------------------------------

    @Test
    void scanFolder_skipsBuiltInExclusionsButFindsMalwareElsewhere( @TempDir Path tmp ) throws IOException
    {
        buildJar( tmp.resolve( "libraries" ), "evil.jar", Map.of( "trojan.exe", new byte[]{ 1 } ) );
        buildJar( tmp.resolve( "mods" ), "evil2.jar", Map.of( "trojan2.exe", new byte[]{ 1 } ) );

        List< SupplementalScanner.Finding > findings = SupplementalScanner.scanFolder( tmp, null, 2 );

        assertEquals( 1, findings.size(), "libraries/ must be skipped by the built-in exclusion" );
        assertTrue( findings.get( 0 ).file().toString().contains( "evil2.jar" ) );
    }

    @Test
    void scanFolder_manifestCannotExemptProtectedContentRoot( @TempDir Path tmp ) throws IOException
    {
        buildJar( tmp.resolve( "mods" ), "evil.jar", Map.of( "trojan.exe", new byte[]{ 1 } ) );

        // A malicious manifest declares "mods" as a scan exclusion; ScanExclusionPolicy
        // must reject it (mods/ is a PROTECTED_ROOT), so the malware is still found.
        List< SupplementalScanner.Finding > findings =
                SupplementalScanner.scanFolder( tmp, List.of( "mods" ), 2 );

        assertEquals( 1, findings.size(),
                "a manifest-declared exclusion of a protected content root must be structurally unable to hide malware" );
    }

    @Test
    void scanFolder_honorsLegitimateCallerSuppliedExclusion( @TempDir Path tmp ) throws IOException
    {
        buildJar( tmp.resolve( "custom-tools" ), "evil.jar", Map.of( "trojan.exe", new byte[]{ 1 } ) );

        List< SupplementalScanner.Finding > findings =
                SupplementalScanner.scanFolder( tmp, List.of( "custom-tools" ), 2 );

        assertTrue( findings.isEmpty(),
                "a legitimate (non-protected) caller-supplied exclusion must still be honored" );
    }

    // ------------------------------------------------------------------
    // test fixtures
    // ------------------------------------------------------------------

    private static List< SupplementalScanner.Finding > scan( Path jarPath ) throws IOException
    {
        try ( JarFile jf = new JarFile( jarPath.toFile() ) ) {
            return SupplementalScanner.scanJar( jf, jarPath );
        }
    }

    private static Path buildJar( Path dir, String jarFileName, Map< String, byte[] > entries ) throws IOException
    {
        Files.createDirectories( dir );
        Path jarPath = dir.resolve( jarFileName );
        try ( JarOutputStream jos = new JarOutputStream( Files.newOutputStream( jarPath ) ) ) {
            for ( Map.Entry< String, byte[] > entry : new LinkedHashMap<>( entries ).entrySet() ) {
                jos.putNextEntry( new JarEntry( entry.getKey() ) );
                jos.write( entry.getValue() );
                jos.closeEntry();
            }
        }
        return jarPath;
    }
}
