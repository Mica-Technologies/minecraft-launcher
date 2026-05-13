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

import com.micatechnologies.minecraft.launcher.files.Logger;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Heuristic scanner that runs alongside the bundled Nekodetector
 * ({@link me.cortex.jarscanner.Detector}) and looks for malware signatures that
 * are <em>not</em> Fractureiser-specific.
 *
 * <p>Nekodetector is narrowly targeted to the Fractureiser bytecode pattern. It
 * misses everything else — info-stealers, coin miners, Discord-token grabbers,
 * RAT droppers — which is a real gap given how often modpacks ship code from
 * less-curated sources. The supplemental scanner here adds a small set of
 * high-signal heuristics chosen for low false-positive rate, since a noisy
 * scanner is one users disable.
 *
 * <h3>What gets flagged</h3>
 * <ul>
 *   <li><b>HIGH severity (treated as infection, blocks launch):</b>
 *     <ul>
 *       <li>PE / script entries inside a mod JAR: {@code .exe}, {@code .scr},
 *           {@code .com}, {@code .bat}, {@code .cmd}, {@code .ps1}, {@code .vbs},
 *           {@code .msi}, {@code .lnk}. Legitimate Minecraft mods do not bundle
 *           Windows executables or shell scripts.</li>
 *       <li>Discord webhook URL embedded in a class constant pool. Webhooks
 *           are the modal exfil channel for token / credential stealers, and
 *           there is no legitimate reason a Minecraft mod would have one
 *           hard-coded.</li>
 *       <li>Paste-host / file-host URLs commonly used for stage-2 payload
 *           fetch (pastebin raw, anonfiles, transfer.sh, tmpfiles.org,
 *           bashupload.com).</li>
 *     </ul>
 *   </li>
 *   <li><b>MEDIUM severity (logged; does not block launch):</b>
 *     <ul>
 *       <li>Hard-coded IPv4 literal in a class constant pool. Legitimate mods
 *           almost always use DNS names; an IP literal is a strong sign of a
 *           C2 endpoint chosen to dodge DNS-level filtering.</li>
 *       <li>Native binary ({@code .dll}, {@code .so}, {@code .dylib}) inside a
 *           mod JAR <em>outside</em> a recognized natives path. Mods that
 *           legitimately ship natives put them under {@code META-INF/native}
 *           prefixes, {@code natives/}, or the LWJGL layout — a {@code .dll}
 *           dropped at the JAR root is almost always a side-loader.</li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * <h3>What is deliberately not flagged</h3>
 * <ul>
 *   <li>General reflection. Mods use it constantly via Mixin / coremods.</li>
 *   <li>Generic outbound HTTP. Update-check pings and CurseForge metadata
 *       fetches are routine.</li>
 *   <li>{@code Runtime.exec} on its own. Some mods legitimately spawn helpers
 *       (e.g. opening a URL via {@code xdg-open}).</li>
 *   <li>JAR-in-JAR. Shading is endemic to the modding ecosystem.</li>
 * </ul>
 *
 * @since 2026.2
 */
public final class SupplementalScanner
{
    private SupplementalScanner() { /* static-only */ }

    /** Per-entry filename suffixes (case-insensitive) that are not legitimate
     *  payloads inside a Minecraft mod JAR. PE images, common Windows / shell
     *  script droppers, and shortcut files that can chain to one. */
    private static final Set< String > FORBIDDEN_EMBEDDED_SUFFIXES = Set.of(
            ".exe", ".scr", ".com", ".bat", ".cmd",
            ".ps1", ".psm1", ".vbs", ".vbe", ".js", ".jse", ".wsf",
            ".msi", ".lnk", ".reg" );

    /** Path-segment prefixes that legitimately carry native binaries. Native
     *  files outside any of these locations are suspicious; inside one,
     *  legitimate (LWJGL, JNA, JOML, etc.). All checked case-insensitively
     *  against forward-slash-normalized entry names. */
    private static final List< String > LEGIT_NATIVE_PATH_PREFIXES = List.of(
            "meta-inf/native",
            "natives/",
            "native/",
            "org/lwjgl/",
            "lwjgl/native",
            "linux/", "windows/", "macos/", "mac/", "osx/",
            "x86/", "x86_64/", "x64/", "aarch64/", "arm64/", "arm/",
            "amd64/" );

    /** Filename suffixes for native binaries the legitimate-paths whitelist
     *  applies to. .so / .dylib / .dll are the load-time forms LWJGL et al.
     *  use; .a (static archive) is rare in mods. */
    private static final Set< String > NATIVE_BINARY_SUFFIXES = Set.of(
            ".dll", ".so", ".dylib", ".jnilib" );

    /** Hard-coded Discord webhook URL. The {@code webhooks/<id>/<token>}
     *  segment is the giveaway: it's the actual exfil endpoint, not just
     *  Discord's API base. */
    private static final Pattern DISCORD_WEBHOOK = Pattern.compile(
            "https?://(?:[a-z0-9.-]*\\.)?discord(?:app)?\\.com/api/webhooks/[\\w-]+/[\\w-]+",
            Pattern.CASE_INSENSITIVE );

    /** Paste / file-host URL prefixes that are common stage-2 payload sources
     *  in info-stealer families. Match against the {@code (https?://host/...)}
     *  prefix so the full URL is captured for the report. */
    private static final List< Pattern > SUSPICIOUS_HOST_PATTERNS = List.of(
            Pattern.compile( "https?://(?:www\\.)?pastebin\\.com/raw/[\\w-]+",       Pattern.CASE_INSENSITIVE ),
            Pattern.compile( "https?://(?:www\\.)?anonfiles\\.com/[\\w./?=&-]+",     Pattern.CASE_INSENSITIVE ),
            Pattern.compile( "https?://(?:www\\.)?transfer\\.sh/[\\w./?=&-]+",       Pattern.CASE_INSENSITIVE ),
            Pattern.compile( "https?://(?:www\\.)?tmpfiles\\.org/[\\w./?=&-]+",      Pattern.CASE_INSENSITIVE ),
            Pattern.compile( "https?://(?:www\\.)?bashupload\\.com/[\\w./?=&-]+",    Pattern.CASE_INSENSITIVE ),
            Pattern.compile( "https?://(?:www\\.)?file\\.io/[\\w./?=&-]+",           Pattern.CASE_INSENSITIVE ),
            Pattern.compile( "https?://(?:www\\.)?gofile\\.io/[\\w./?=&-]+",         Pattern.CASE_INSENSITIVE ) );

    /** Public-IPv4-literal pattern. Excludes:
     *  <ul>
     *    <li>{@code 127.x.x.x} / {@code 0.x.x.x} — loopback and zero networks;
     *        legitimate code uses them for local-only checks.</li>
     *    <li>{@code 224.x.x.x} - {@code 239.x.x.x} — multicast / SSDP ranges.
     *        Minecraft itself hard-codes {@code 224.0.2.60} for the LAN-game
     *        discovery multicast; legitimate apps embed multicast targets
     *        regularly enough that flagging them is noise.</li>
     *    <li>Trailing {@code .digit} via the {@code (?!\.\d)} negative lookahead —
     *        rejects OID-shape strings like {@code 1.3.6.1.5.5.2} (the Kerberos
     *        SPNEGO OID, embedded in httpclient's NegotiateScheme) where the
     *        first four dotted-decimal components would otherwise match.</li>
     *  </ul>
     *  What remains is plain-ol' IPv4 literals, which in the context of a
     *  modpack JAR are still a strong C2-endpoint smell. */
    private static final Pattern IPV4_LITERAL = Pattern.compile(
            "\\b(?!127\\.)(?!0\\.)(?!22[4-9]\\.)(?!23\\d\\.)" +
            "(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\b(?!\\.\\d)" );

    /** Filename pattern for Mojang's pre-packaged native-libraries JARs (e.g.
     *  {@code lwjgl-platform-2.9.1-natives-windows.jar},
     *  {@code jinput-platform-2.0.5-natives-windows.jar},
     *  {@code twitch-platform-5.16-natives-windows-64.jar}). These are
     *  expected to carry {@code .dll}/{@code .so}/{@code .dylib} entries at
     *  the JAR root by design — the entire JAR's purpose is to ship native
     *  binaries. Skip the "native outside known natives path" check when the
     *  containing JAR matches this pattern. */
    private static final Pattern NATIVES_JAR_FILENAME = Pattern.compile(
            ".*-natives-.*\\.jar$", Pattern.CASE_INSENSITIVE );

    /** Names of Mojang / Microsoft launcher files that store cached account
     *  credentials. A class with one of these as a string literal is almost
     *  certainly trying to read another launcher's auth state. No legitimate
     *  Minecraft mod has any reason to touch these.
     *
     *  <p>Deliberately <strong>not</strong> in this list: {@code usercache.json}.
     *  Despite its name, that file is the vanilla Minecraft server's
     *  username→UUID cache (written by {@code net.minecraft.server.MinecraftServer.<clinit>})
     *  — not a launcher credential store. Adding it FPs every vanilla
     *  modpack launch.</p> */
    private static final Set< String > LAUNCHER_CREDENTIAL_FILES = Set.of(
            "launcher_profiles.json",
            "launcher_accounts.json",
            "launcher_msa_credentials.bin",
            "credentials.json" );

    /** {@code java.awt.Robot} owner pattern. Used alongside clipboard access
     *  detection — the combination is a classic clipboard-stealer fingerprint. */
    private static final String AWT_ROBOT_OWNER = "java/awt/Robot";

    /** Clipboard-access method-call patterns. {@code Toolkit.getSystemClipboard()}
     *  is the cross-platform entry point; {@code Clipboard.getData} reads the
     *  contents. Either one alongside AWT Robot in the same class is the
     *  clipboard-stealer giveaway. */
    private static final String CLIPBOARD_OWNER = "java/awt/datatransfer/Clipboard";

    /** Pack-root-relative paths that the supplemental heuristic scanner always
     *  skips on top of the caller-supplied exclude list. These are launcher-
     *  controlled directories whose contents come from hash-verified upstream
     *  sources (Mojang piston-meta for {@code libraries/} + {@code bin/}, the
     *  Forge installer's libraries.json for the rest of {@code libraries/}).
     *  The Nekodetector pass still scans them for Fractureiser-specific
     *  bytecode; this supplemental pass adds heuristic checks that produce
     *  too many FPs against vanilla content (e.g. {@code minecraft.jar}
     *  references {@code usercache.json}, Mojang natives JARs drop .dll at
     *  the JAR root, the LAN-discovery multicast {@code 224.0.2.60} is hard-
     *  coded in MC's networking code).
     *
     *  <p>Baked into the scanner rather than into the caller's exclude list
     *  so a malicious modpack manifest cannot countermand the protection by
     *  supplying its own pack-scan-exclusions.</p> */
    private static final List< String > BUILT_IN_EXCLUSIONS = List.of(
            "libraries",
            "bin",
            "runtime" );

    /** Severity label for a single finding. HIGH blocks launch; MEDIUM is
     *  reported but launch proceeds. */
    public enum Severity { HIGH, MEDIUM }

    /** A single finding produced by the scanner. */
    public record Finding(Severity severity, Path file, String message) {
        @Override public String toString() {
            return "[" + severity + "] " + file + " — " + message;
        }
    }

    /**
     * Scans every {@code .jar} file under {@code root}, returning a list of
     * findings. Excluded paths (modpack-supplied scan exclusions) are skipped
     * just like the upstream {@link me.cortex.jarscanner.Main}.
     */
    public static List< Finding > scanFolder( Path root, List< String > excludeFolders, int nThreads )
            throws IOException
    {
        List< Finding > findings = Collections.synchronizedList( new ArrayList<>() );
        ConcurrentLinkedQueue< Path > toScan = new ConcurrentLinkedQueue<>();
        final Path rootAbs = root.toAbsolutePath().normalize();
        // Combine the modpack-supplied exclusions with the launcher's hardcoded
        // BUILT_IN_EXCLUSIONS (libraries/, bin/, runtime/) so heuristic scanning
        // never fires on Mojang/Forge-controlled hash-verified content. The
        // hardcoded list is appended after the caller's so it can't be undone
        // by a manifest-supplied empty exclusions list.
        List< String > combinedExcludes = new ArrayList<>(
                normalizeExclusions( excludeFolders ) );
        combinedExcludes.addAll( BUILT_IN_EXCLUSIONS );
        final List< String > normalizedExclusions = combinedExcludes;

        Files.walkFileTree( root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory( Path dir, BasicFileAttributes attrs ) {
                if ( isExcluded( rootAbs, dir, normalizedExclusions ) ) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile( Path file, BasicFileAttributes attrs ) {
                if ( isExcluded( rootAbs, file, normalizedExclusions ) ) {
                    return FileVisitResult.CONTINUE;
                }
                if ( file.getFileName().toString().toLowerCase( Locale.ROOT ).endsWith( ".jar" ) ) {
                    toScan.add( file );
                }
                return FileVisitResult.CONTINUE;
            }
        } );

        ExecutorService pool = Executors.newFixedThreadPool( Math.max( 1, nThreads ) );
        try {
            for ( Path jar : toScan ) {
                pool.submit( () -> {
                    try ( JarFile jf = new JarFile( jar.toFile() ) ) {
                        findings.addAll( scanJar( jf, jar ) );
                    }
                    catch ( IOException e ) {
                        Logger.logWarningSilent( "Supplemental scan failed to open "
                                                         + jar.getFileName() + ": "
                                                         + e.getClass().getSimpleName() );
                    }
                } );
            }
        }
        finally {
            pool.shutdown();
            try {
                if ( !pool.awaitTermination( 30, TimeUnit.MINUTES ) ) {
                    pool.shutdownNow();
                }
            }
            catch ( InterruptedException e ) {
                pool.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        return findings;
    }

    /**
     * Scans a single JAR. Returns an empty list when nothing flagged.
     */
    static List< Finding > scanJar( JarFile jar, Path jarPath )
    {
        List< Finding > findings = new ArrayList<>();

        // If the JAR's filename itself declares it's a natives-distribution JAR
        // (Mojang's *-natives-* packaging), suppress the "native binary outside
        // known natives path" check on its entries. The whole purpose of the
        // JAR is to ship .dll/.so/.dylib files; they're expected at the root.
        // The forbidden-executable check (.exe, .bat, .ps1, etc.) still runs —
        // Mojang's natives JARs ship .dll files only, not Windows executables,
        // so a .exe inside one would still be a red flag.
        boolean isNativesJar = jarPath != null
                && NATIVES_JAR_FILENAME.matcher( jarPath.getFileName().toString() ).matches();

        // Walk every entry once. Two passes' worth of checks fit naturally
        // into one walk: filename-based (entry name) and content-based
        // (class bytecode / constant pool).
        for ( JarEntry entry : Collections.list( jar.entries() ) ) {
            if ( entry.isDirectory() ) {
                continue;
            }
            String name = entry.getName();
            String lowerName = name.toLowerCase( Locale.ROOT );
            String normalized = lowerName.replace( '\\', '/' );

            // (1) Forbidden embedded executable / script.
            for ( String suffix : FORBIDDEN_EMBEDDED_SUFFIXES ) {
                if ( lowerName.endsWith( suffix ) ) {
                    findings.add( new Finding( Severity.HIGH, jarPath,
                            "embedded executable / script entry: " + name ) );
                    break;
                }
            }

            // (2) Native binary outside a recognized natives path. Skipped
            //     when the entry isn't a native binary at all (most entries),
            //     and skipped entirely when the containing JAR is a Mojang
            //     natives-distribution JAR.
            if ( !isNativesJar ) {
                for ( String suffix : NATIVE_BINARY_SUFFIXES ) {
                    if ( lowerName.endsWith( suffix ) ) {
                        if ( !isInLegitNativePath( normalized ) ) {
                            findings.add( new Finding( Severity.MEDIUM, jarPath,
                                    "native binary outside a known natives path: " + name ) );
                        }
                        break;
                    }
                }
            }

            // (3) Class-content checks: scan the constant pool of every class
            //     for IoC string literals.
            if ( lowerName.endsWith( ".class" ) ) {
                try ( InputStream is = jar.getInputStream( entry ) ) {
                    byte[] bytes = readAll( is );
                    scanClassConstants( bytes, jarPath, findings );
                }
                catch ( Exception e ) {
                    // Parse failure on a single class is not actionable;
                    // continue with the next.
                }
            }
        }
        return findings;
    }

    /** Walks the class's instruction stream looking for LDC string constants
     *  that match the IoC patterns, plus the AWT-Robot + Clipboard combination
     *  that fingerprints clipboard stealers. ASM's tree API gives us the
     *  constants directly without needing a full visitor implementation. */
    private static void scanClassConstants( byte[] classBytes, Path jarPath, List< Finding > findings )
    {
        ClassReader reader;
        try {
            reader = new ClassReader( classBytes );
        }
        catch ( Exception e ) {
            return; // class file version we can't parse — same defensive stance as the Nekodetector
        }
        ClassNode node = new ClassNode();
        try {
            reader.accept( node, 0 );
        }
        catch ( Exception e ) {
            return;
        }
        if ( node.methods == null ) {
            return;
        }

        boolean sawAwtRobot = false;
        boolean sawClipboard = false;
        for ( MethodNode method : node.methods ) {
            if ( method.instructions == null ) {
                continue;
            }
            for ( int i = 0; i < method.instructions.size(); i++ ) {
                AbstractInsnNode insn = method.instructions.get( i );
                if ( insn instanceof LdcInsnNode ldc && ldc.cst instanceof String s ) {
                    inspectStringConstant( s, jarPath, node, method, findings );
                }
                else if ( insn instanceof MethodInsnNode mi ) {
                    if ( AWT_ROBOT_OWNER.equals( mi.owner ) ) {
                        sawAwtRobot = true;
                    }
                    if ( CLIPBOARD_OWNER.equals( mi.owner ) ) {
                        sawClipboard = true;
                    }
                }
            }
        }
        // AWT Robot + Clipboard inside the same class is the textbook
        // clipboard-stealer fingerprint (Robot triggers focus, Clipboard
        // reads the contents). Flagged once per class, MEDIUM — a few
        // utility mods legitimately script the clipboard for "click to
        // copy server IP" features, so blocking outright would FP.
        if ( sawAwtRobot && sawClipboard ) {
            findings.add( new Finding( Severity.MEDIUM, jarPath,
                    "clipboard-stealer pattern (AWT Robot + Clipboard) in " + node.name ) );
        }
    }

    private static void inspectStringConstant( String s, Path jarPath, ClassNode owner,
                                                MethodNode method, List< Finding > findings )
    {
        if ( s == null || s.isEmpty() ) {
            return;
        }
        // (A) Discord webhook — HIGH.
        if ( DISCORD_WEBHOOK.matcher( s ).find() ) {
            findings.add( new Finding( Severity.HIGH, jarPath,
                    "Discord webhook URL in " + owner.name + "." + method.name ) );
            return; // one HIGH per class is enough; don't spam findings
        }
        // (B) Suspicious paste / file-host URL — HIGH.
        for ( Pattern p : SUSPICIOUS_HOST_PATTERNS ) {
            if ( p.matcher( s ).find() ) {
                findings.add( new Finding( Severity.HIGH, jarPath,
                        "suspicious host URL in " + owner.name + "." + method.name + " (" + p.pattern() + ")" ) );
                return;
            }
        }
        // (C) Mojang / MS launcher credential file name — HIGH. A class
        //     literal-encoding the path to another launcher's credential
        //     store is functionally indistinguishable from credential theft.
        String lowerS = s.toLowerCase( Locale.ROOT );
        for ( String credFile : LAUNCHER_CREDENTIAL_FILES ) {
            if ( lowerS.contains( credFile ) ) {
                findings.add( new Finding( Severity.HIGH, jarPath,
                        "reference to launcher credential file '" + credFile + "' in "
                                + owner.name + "." + method.name ) );
                return;
            }
        }
        // (D) IPv4 literal — MEDIUM. Skip strings that contain dotted version
        //     numbers (e.g. "1.21.4") which would otherwise FP — require the
        //     match to look like a real address (all four octets <= 255).
        Matcher m = IPV4_LITERAL.matcher( s );
        if ( m.find() && looksLikeRealIp( m ) ) {
            findings.add( new Finding( Severity.MEDIUM, jarPath,
                    "hard-coded IPv4 literal in " + owner.name + "." + method.name + ": " + m.group() ) );
        }
    }

    private static boolean looksLikeRealIp( Matcher m )
    {
        try {
            for ( int i = 1; i <= 4; i++ ) {
                int octet = Integer.parseInt( m.group( i ) );
                if ( octet < 0 || octet > 255 ) {
                    return false;
                }
            }
            return true;
        }
        catch ( NumberFormatException e ) {
            return false;
        }
    }

    private static boolean isInLegitNativePath( String normalizedName )
    {
        for ( String prefix : LEGIT_NATIVE_PATH_PREFIXES ) {
            if ( normalizedName.contains( prefix ) ) {
                return true;
            }
        }
        return false;
    }

    private static byte[] readAll( InputStream in ) throws IOException
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ( ( n = in.read( buf ) ) != -1 ) {
            out.write( buf, 0, n );
        }
        return out.toByteArray();
    }

    private static List< String > normalizeExclusions( List< String > raw )
    {
        if ( raw == null || raw.isEmpty() ) {
            return Collections.emptyList();
        }
        List< String > out = new ArrayList<>( raw.size() );
        for ( String x : raw ) {
            String s = x == null ? "" : x.trim();
            while ( s.startsWith( "/" ) || s.startsWith( "\\" ) ) {
                s = s.substring( 1 );
            }
            while ( s.endsWith( "/" ) || s.endsWith( "\\" ) ) {
                s = s.substring( 0, s.length() - 1 );
            }
            if ( !s.isEmpty() ) {
                out.add( s.toLowerCase( Locale.ROOT ).replace( '\\', '/' ) );
            }
        }
        return out;
    }

    private static boolean isExcluded( Path root, Path target, List< String > exclusions )
    {
        if ( exclusions.isEmpty() || root.equals( target ) ) {
            return false;
        }
        Path rel = root.relativize( target.toAbsolutePath().normalize() );
        String relStr = rel.toString().toLowerCase( Locale.ROOT ).replace( '\\', '/' );
        for ( String exclude : exclusions ) {
            if ( relStr.equals( exclude ) || relStr.startsWith( exclude + "/" ) ) {
                return true;
            }
        }
        return false;
    }
}
