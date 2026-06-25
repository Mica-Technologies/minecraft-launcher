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

import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
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
    /**
     * Private no-op constructor. This is a static-only utility class and is
     * never instantiated.
     *
     * @since 2026.2
     */
    private SupplementalScanner() { /* static-only */ }

    /** Per-entry filename suffixes (case-insensitive) that are not legitimate
     *  payloads inside a Minecraft mod JAR. PE images, common Windows / shell
     *  script droppers, and shortcut files that can chain to one.
     *
     *  <p>Deliberately not in this list: {@code .js} / {@code .jse}. A
     *  JavaScript file inside a JAR has no auto-execution path in the JVM —
     *  it's a resource that some script engine inside the mod (Rhino,
     *  Nashorn, KubeJS' wrapper, OpenComputers' Lua-via-JS layer, FAWE's
     *  command scripting) has to load and evaluate. Plenty of legitimate
     *  modpack content ships {@code .js} resources (FAWE bundles
     *  {@code cs_adv.js}, MaryTTS bundles {@code mary.js} as a web-UI
     *  asset, etc.), and flagging them as HIGH was producing false-positive
     *  launch blocks. The Windows {@code .wsf} / {@code .vbs} / {@code .vbe}
     *  variants remain blocked because those <em>do</em> auto-execute via
     *  {@code wscript.exe} if the user (or a future bundled launcher script)
     *  invokes them. */
    private static final Set< String > FORBIDDEN_EMBEDDED_SUFFIXES = Set.of(
            ".exe", ".scr", ".com", ".bat", ".cmd",
            ".ps1", ".psm1", ".vbs", ".vbe", ".wsf",
            ".msi", ".lnk", ".reg" );

    /** Path-segment substrings that legitimately carry native binaries. Native
     *  files outside any of these locations are suspicious; inside one,
     *  legitimate (LWJGL, JNA, JOML, junixsocket, zstd-jni, jnlua, sdl2-gdx,
     *  spark/asyncProfiler, etc.). All checked case-insensitively against
     *  forward-slash-normalized entry names via {@code String.contains}, so a
     *  substring like {@code "jni/"} matches whether it appears at the root
     *  or nested under platform-triple folders.
     *
     *  <p>The list is broad because modpack natives ship in many conventions:
     *  {@code lib/<arch>-<os>-<compiler>/jni/} (junixsocket),
     *  {@code assets/<mod>/lib/} (opencomputers/jnlua),
     *  {@code <os>/<arch>/} (zstd-jni's {@code aix/ppc64/}), and bare-root
     *  drops are common for single-platform native bundles (spark, sdl2-gdx).
     *  Narrower whitelists FP'd on virtually every native-shipping mod. */
    private static final List< String > LEGIT_NATIVE_PATH_PREFIXES = List.of(
            // Conventional native-resource roots
            "meta-inf/native",
            "natives/",
            "native/",
            "jni/",                                     // matches anywhere — distinctive
            "lib/", "libs/",
            // Mod-namespaced asset roots (e.g. assets/opencomputers/lib/)
            "assets/",
            // Native bundling libraries that put their stuff under their own package
            "org/lwjgl/",
            "lwjgl/native",
            "com/sun/jna/",
            // OS-name folders (zstd-jni, sqlite-jdbc, many others)
            "linux/", "windows/", "macos/", "mac/", "osx/",
            "freebsd/", "netbsd/", "openbsd/", "solaris/", "aix/",
            // Arch-name folders
            "x86/", "x86_64/", "x64/", "i686/", "i386/",
            "aarch64/", "arm64/", "arm/", "armv7/", "armv6/",
            "amd64/", "ppc/", "ppc64/", "ppc64le/", "riscv64/",
            "loongarch64/", "mips/", "mips64/", "s390x/", "sparc/",
            // Platform triples used by BridJ / Rococoa style packagers, e.g.
            // "aarch64-MacOSX-clang/jni/...". We don't have a regex match here;
            // the "jni/" entry above covers the inner segment, and the
            // OS/arch entries above cover the outer prefixes too.
            "darwin-", "linux-", "windows-", "macos-",
            "-macosx-", "-linux-", "-windows-" );

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
     *  reported but launch proceeds.
     *
     *  @since 2026.2 */
    public enum Severity {
        /** High-confidence infection signal. A finding at this severity is
         *  treated as malware and blocks the modpack from launching until the
         *  pack manifest explicitly acknowledges it.
         *
         *  @since 2026.2 */
        HIGH,
        /** Suspicious-but-not-conclusive signal. A finding at this severity is
         *  logged and surfaced in the scan report, but launch still proceeds.
         *
         *  @since 2026.2 */
        MEDIUM
    }

    /** Stable identifier for the rule that fired a finding. These names are
     *  part of the public manifest schema for {@code packScanAcknowledgements}
     *  — renaming or removing an entry is a breaking change for any modpack
     *  manifest that references it. Add new entries freely; never repurpose
     *  an existing one.
     *
     *  @since 2026.2 */
    public enum Kind {
        /** Entry filename matched a Windows-executable or script suffix
         *  ({@code .exe}, {@code .bat}, {@code .ps1}, etc.). */
        EMBEDDED_EXECUTABLE,
        /** Entry filename matched a native-binary suffix
         *  ({@code .dll}/{@code .so}/{@code .dylib}/{@code .jnilib}) outside
         *  the legit-paths whitelist and not matching the {@code lib*} /
         *  {@code *.dll} filename convention. */
        NATIVE_OUTSIDE_KNOWN_PATH,
        /** Class constant pool contained a Discord webhook URL. */
        DISCORD_WEBHOOK_URL,
        /** Class constant pool contained a paste/file-host URL of the kind
         *  used as a stage-2 payload source. */
        SUSPICIOUS_HOST_URL,
        /** Class constant pool referenced a launcher credential filename
         *  ({@code launcher_profiles.json}, etc.). */
        LAUNCHER_CREDENTIAL_FILE_REF,
        /** Class constant pool contained a hard-coded IPv4 literal that
         *  survived all the version-string / reserved-range FP guards. */
        IPV4_LITERAL,
        /** Class contained both AWT Robot and clipboard-access call sites,
         *  the canonical clipboard-stealer fingerprint. */
        CLIPBOARD_STEALER_PATTERN
    }

    /** A single finding produced by the scanner.
     *
     *  @param severity    HIGH (blocks launch) / MEDIUM (warns only)
     *  @param kind        stable rule identifier; used as an acknowledgement key
     *  @param file        absolute path of the containing JAR
     *  @param fileSha256  hex SHA-256 of the JAR file contents. Used as an
     *                     acknowledgement key so a hash change (new mod version)
     *                     invalidates an ack rather than silently inheriting it.
     *                     The drawback: a repackage of the outer mod
     *                     invalidates the ack even when the actual offending
     *                     inner element is byte-identical, so packs that
     *                     update their outer mods regularly end up re-doing
     *                     the same ack each release — see {@link #innerSha256}
     *                     for the survives-mod-updates alternative.
     *  @param innerSha256 hex SHA-256 of the inner element the finding points
     *                     at: the JAR entry's bytes for filename-based
     *                     findings (EMBEDDED_EXECUTABLE / NATIVE_OUTSIDE_KNOWN_PATH),
     *                     or the {@code .class} bytecode bytes for class-
     *                     content findings. Survives an outer-mod update as
     *                     long as the bundled artifact / class itself didn't
     *                     change — exactly the right key for "the marytts
     *                     binary shaded inside city-super-mod" or "OptiFine's
     *                     Installer class that writes launcher_profiles.json"
     *                     cases. May be {@code null} if the bytes couldn't be
     *                     read; ack matching against null behaves identically
     *                     to a null {@link #fileSha256}.
     *  @param locator     stable inner identifier — for class-content findings
     *                     this is {@code <className>.<methodName>}, for entry-
     *                     based findings it's the inner-JAR entry path. Used as
     *                     a third acknowledgement key so a pack author can
     *                     silence one specific class+method pair without
     *                     silencing every finding of that kind in the same JAR.
     *  @param message     human-readable description; surfaces in the
     *                     scan-blocked popup and the warning log. NOT used for
     *                     acknowledgement matching — the message wording can
     *                     change between launcher versions, the (kind, locator)
     *                     identity stays the same.
     *
     * @since 2026.2
     */
    public record Finding(Severity severity, Kind kind, Path file, String fileSha256,
                           String innerSha256, String locator, String message)
    {
        /**
         * Renders a compact one-line, human-readable form of this finding
         * suitable for log output, of the shape
         * {@code [SEVERITY] <jar path> — <message>}.
         *
         * @return a single-line summary combining the severity, containing JAR
         *         path, and human-readable message
         *
         * @since 2026.2
         */
        @Override public String toString() {
            return "[" + severity + "] " + file + " — " + message;
        }
    }

    /**
     * Scans every {@code .jar} file under {@code root}, returning a list of
     * findings. Excluded paths (modpack-supplied scan exclusions) are skipped
     * just like the upstream {@link me.cortex.jarscanner.Main}.
     *
     * <p>The caller-supplied {@code excludeFolders} are first run through
     * {@link ScanExclusionPolicy#filterUntrusted} and then combined with the
     * launcher's hard-coded {@link #BUILT_IN_EXCLUSIONS} ({@code libraries/},
     * {@code bin/}, {@code runtime/}). The built-in list is appended last so a
     * malicious manifest cannot countermand it by supplying an empty exclusion
     * set. The directory tree is walked once; matching {@code .jar} files are
     * queued and then scanned across a fixed thread pool. A JAR that fails to
     * open is logged and skipped rather than aborting the scan.</p>
     *
     * @param root          pack root directory to walk; scanning is recursive
     * @param excludeFolders pack-root-relative paths (modpack-supplied scan
     *                       exclusions) to skip, in addition to the built-in
     *                       exclusions; may be {@code null} or empty
     * @param nThreads      desired worker-thread count; clamped to a minimum of
     *                      one
     * @return the aggregated findings across every scanned JAR; empty when
     *         nothing was flagged
     * @throws IOException if walking the directory tree under {@code root} fails
     *
     * @since 2026.2
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
        // by a manifest-supplied empty exclusions list. Caller exclusions are
        // additionally run through ScanExclusionPolicy here (not just at the
        // GameModPack call site) so this scanner structurally cannot be made
        // to skip protected content roots, regardless of caller discipline.
        List< String > combinedExcludes = new ArrayList<>(
                normalizeExclusions( ScanExclusionPolicy.filterUntrusted( excludeFolders ) ) );
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
                        Logger.logWarningSilent( LocalizationManager.format(
                                "log.supplementalScanner.openFailed",
                                jar.getFileName(), e.getClass().getSimpleName() ) );
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
     *
     * <p>The JAR's SHA-256 is computed once up front and stamped onto every
     * {@link Finding} so it can be matched against a manifest acknowledgement.
     * Each entry is visited a single time; filename-based checks
     * (forbidden-executable suffix, native-binary-outside-known-path) and
     * content-based checks (class constant-pool inspection) all run in that one
     * pass. When the JAR's own filename matches {@link #NATIVES_JAR_FILENAME}
     * the native-outside-known-path check is suppressed, since such JARs ship
     * native binaries at their root by design. A parse failure on an individual
     * class entry is swallowed and scanning continues with the next entry.</p>
     *
     * @param jar     the open JAR file to scan
     * @param jarPath absolute path of the JAR on disk, used both for SHA-256
     *                computation and as the {@code file} field of each finding;
     *                may be {@code null}, in which case no file hash is computed
     * @return the findings produced for this JAR; empty when nothing was flagged
     *
     * @since 2026.2
     */
    static List< Finding > scanJar( JarFile jar, Path jarPath )
    {
        List< Finding > findings = new ArrayList<>();

        // Compute the JAR's SHA-256 once up front; every Finding produced for
        // this JAR carries the hash so it can be matched against a manifest
        // acknowledgement. A null hash (computation failure) means findings
        // CAN still be produced and shown, but no ack will ever match them —
        // safer than silently silencing on a missing key.
        String fileSha256 = computeJarSha256( jarPath );

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
                    // Hash the entry's raw bytes so an ack written against
                    // this finding survives an outer-JAR repackage. Read +
                    // hash is paid only on a match (rare), not per-entry.
                    String innerSha = readEntrySha256( jar, entry );
                    findings.add( new Finding( Severity.HIGH, Kind.EMBEDDED_EXECUTABLE,
                            jarPath, fileSha256, innerSha, name,
                            "embedded executable / script entry: " + name ) );
                    break;
                }
            }

            // (2) Native binary outside a recognized natives path. Skipped
            //     when the entry isn't a native binary at all (most entries),
            //     and skipped entirely when the containing JAR is a Mojang
            //     natives-distribution JAR. Also skipped when the filename
            //     itself matches a hard Unix/Windows native-library naming
            //     convention — bare-root entries like libasyncProfiler.so
            //     (spark), liblwjgl.dylib (mc-mouser), sdl2gdx64.dll
            //     (controllable) are universally legitimate native bundles
            //     by filename alone, regardless of where in the JAR they sit.
            if ( !isNativesJar ) {
                for ( String suffix : NATIVE_BINARY_SUFFIXES ) {
                    if ( lowerName.endsWith( suffix ) ) {
                        if ( !isInLegitNativePath( normalized )
                                && !hasLegitNativeFilename( normalized ) ) {
                            String innerSha = readEntrySha256( jar, entry );
                            findings.add( new Finding( Severity.MEDIUM, Kind.NATIVE_OUTSIDE_KNOWN_PATH,
                                    jarPath, fileSha256, innerSha, name,
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
                    // Hash the class bytes once and hand the digest to every
                    // finding produced from this class — cheaper than rehashing
                    // per-finding and the bytes are already in memory.
                    String innerSha = sha256Hex( bytes );
                    scanClassConstants( bytes, jarPath, fileSha256, innerSha, findings );
                }
                catch ( Exception e ) {
                    // Parse failure on a single class is not actionable;
                    // continue with the next.
                }
            }
        }
        return findings;
    }

    /** Reads the entry's full bytes and returns the lowercased hex SHA-256.
     *  Returns {@code null} on any read failure — callers treat a null inner
     *  hash the same way they'd treat a null outer hash: findings still
     *  surface, but acks against the null field never match. */
    private static String readEntrySha256( JarFile jar, JarEntry entry )
    {
        try ( InputStream is = jar.getInputStream( entry ) ) {
            return sha256Hex( readAll( is ) );
        }
        catch ( Exception e ) {
            return null;
        }
    }

    /** Hex-encoded SHA-256 of an in-memory byte array. Shared between the
     *  inner-entry hash path and the class-bytes hash path. */
    private static String sha256Hex( byte[] bytes )
    {
        if ( bytes == null ) return null;
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance( "SHA-256" );
            byte[] digest = md.digest( bytes );
            StringBuilder sb = new StringBuilder( digest.length * 2 );
            for ( byte b : digest ) sb.append( String.format( "%02x", b ) );
            return sb.toString();
        }
        catch ( Exception e ) {
            return null;
        }
    }

    /** Streaming SHA-256 of a JAR file, hex-encoded lowercase. Used as the
     *  file-identity key in {@link
     *  com.micatechnologies.minecraft.launcher.game.modpack.ScanAcknowledgement}
     *  so a hash change (new mod version, repackaged JAR) invalidates the ack
     *  rather than silently inheriting it. Returns {@code null} on read or
     *  digest failure — callers treat null as "no hash available, no ack
     *  match possible." */
    private static String computeJarSha256( Path jarPath )
    {
        if ( jarPath == null ) return null;
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance( "SHA-256" );
            try ( InputStream is = Files.newInputStream( jarPath ) ) {
                byte[] buf = new byte[ 16384 ];
                int n;
                while ( ( n = is.read( buf ) ) != -1 ) {
                    md.update( buf, 0, n );
                }
            }
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder( digest.length * 2 );
            for ( byte b : digest ) sb.append( String.format( "%02x", b ) );
            return sb.toString();
        }
        catch ( Exception e ) {
            return null;
        }
    }

    /** Walks the class's instruction stream looking for LDC string constants
     *  that match the IoC patterns, plus the AWT-Robot + Clipboard combination
     *  that fingerprints clipboard stealers. ASM's tree API gives us the
     *  constants directly without needing a full visitor implementation. */
    private static void scanClassConstants( byte[] classBytes, Path jarPath, String fileSha256,
                                             String innerSha256, List< Finding > findings )
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
                    inspectStringConstant( s, jarPath, fileSha256, innerSha256, node, method, findings );
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
            findings.add( new Finding( Severity.MEDIUM, Kind.CLIPBOARD_STEALER_PATTERN,
                    jarPath, fileSha256, innerSha256, node.name,
                    "clipboard-stealer pattern (AWT Robot + Clipboard) in " + node.name ) );
        }
    }

    private static void inspectStringConstant( String s, Path jarPath, String fileSha256,
                                                String innerSha256, ClassNode owner,
                                                MethodNode method, List< Finding > findings )
    {
        if ( s == null || s.isEmpty() ) {
            return;
        }
        String locator = owner.name + "." + method.name;
        // (A) Discord webhook — HIGH.
        if ( DISCORD_WEBHOOK.matcher( s ).find() ) {
            findings.add( new Finding( Severity.HIGH, Kind.DISCORD_WEBHOOK_URL,
                    jarPath, fileSha256, innerSha256, locator,
                    "Discord webhook URL in " + locator ) );
            return; // one HIGH per class is enough; don't spam findings
        }
        // (B) Suspicious paste / file-host URL — HIGH.
        for ( Pattern p : SUSPICIOUS_HOST_PATTERNS ) {
            if ( p.matcher( s ).find() ) {
                findings.add( new Finding( Severity.HIGH, Kind.SUSPICIOUS_HOST_URL,
                        jarPath, fileSha256, innerSha256, locator,
                        "suspicious host URL in " + locator + " (" + p.pattern() + ")" ) );
                return;
            }
        }
        // (C) Mojang / MS launcher credential file name — HIGH. A class
        //     literal-encoding the path to another launcher's credential
        //     store is functionally indistinguishable from credential theft.
        String lowerS = s.toLowerCase( Locale.ROOT );
        for ( String credFile : LAUNCHER_CREDENTIAL_FILES ) {
            if ( lowerS.contains( credFile ) ) {
                findings.add( new Finding( Severity.HIGH, Kind.LAUNCHER_CREDENTIAL_FILE_REF,
                        jarPath, fileSha256, innerSha256, locator,
                        "reference to launcher credential file '" + credFile + "' in " + locator ) );
                return;
            }
        }
        // (D) IPv4 literal — MEDIUM. Three layers of FP suppression on top of
        //     the regex's existing 127.x / 0.x / multicast exclusions:
        //       1. All four octets must parse 0..255 (drops "1.21.456" style noise).
        //       2. Reserved / private / documentation / benchmarking ranges are
        //          skipped — those appear in code as filter-list entries
        //          (opencomputers/InternetFilteringRule) rather than as C2.
        //       3. Bare version-string-shaped literals (whole LDC string equals
        //          the IP, no URL context, small leading octet OR trailing-zero
        //          pad) are skipped — those are version numbers like "1.7.0.25"
        //          or "3.5.0.0" that happen to parse as IPv4.
        Matcher m = IPV4_LITERAL.matcher( s );
        if ( m.find() && looksLikeRealIp( m ) && !isFalsePositiveIp( s, m ) ) {
            findings.add( new Finding( Severity.MEDIUM, Kind.IPV4_LITERAL,
                    jarPath, fileSha256, innerSha256, locator,
                    "hard-coded IPv4 literal in " + locator + ": " + m.group() ) );
        }
    }

    /**
     * Returns whether a regex match of a dotted-quad looks like a real IPv4
     * address — i.e. all four captured octets parse and fall in {@code 0..255}.
     * Filters out version strings and other dotted numbers that merely resemble
     * an IP.
     *
     * @param m a {@link Matcher} positioned on a dotted-quad with four numeric groups
     *
     * @return {@code true} if every octet is a valid {@code 0..255} value
     */
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

    /** Returns true when the matched IPv4 is overwhelmingly likely to be a
     *  version string, subnet sentinel, or filter-list entry rather than a
     *  real outbound destination.
     *
     *  <p>{@code fullString} is the entire LDC the match came from;
     *  {@code m} is the matched IPv4 regex group (with its four numeric
     *  capture groups already validated 0..255 by {@link #looksLikeRealIp}).</p>
     */
    private static boolean isFalsePositiveIp( String fullString, Matcher m )
    {
        try {
            int a = Integer.parseInt( m.group( 1 ) );
            int b = Integer.parseInt( m.group( 2 ) );
            int c = Integer.parseInt( m.group( 3 ) );
            int d = Integer.parseInt( m.group( 4 ) );

            // (1) RFC-reserved / private / documentation / benchmarking /
            //     link-local. Code that contains these is enumerating ranges
            //     for an internal filter, not phoning home.
            //     Multicast (224-239) and reserved (240+) are already excluded
            //     by the IPV4_LITERAL pattern's negative lookahead.
            if ( a == 10 ) return true;                              // 10.0.0.0/8 (RFC 1918)
            if ( a == 100 && b >= 64 && b <= 127 ) return true;      // 100.64.0.0/10 (RFC 6598, CGN)
            if ( a == 169 && b == 254 ) return true;                 // 169.254.0.0/16 (link-local)
            if ( a == 172 && b >= 16 && b <= 31 ) return true;       // 172.16.0.0/12 (RFC 1918)
            if ( a == 192 && b == 168 ) return true;                 // 192.168.0.0/16 (RFC 1918)
            if ( a == 192 && b == 0 && c == 0 ) return true;         // 192.0.0.0/24 (IETF protocol assignments)
            if ( a == 192 && b == 0 && c == 2 ) return true;         // 192.0.2.0/24 (TEST-NET-1)
            if ( a == 198 && ( b == 18 || b == 19 ) ) return true;   // 198.18.0.0/15 (benchmarking)
            if ( a == 198 && b == 51 && c == 100 ) return true;      // 198.51.100.0/24 (TEST-NET-2)
            if ( a == 203 && b == 0 && c == 113 ) return true;       // 203.0.113.0/24 (TEST-NET-3)

            // (1b) Well-known public DNS resolvers. Mods regularly hard-code
            //      these as a connectivity probe (FancyMenu's WebUtils
            //      _isInternetAvailable is the canonical example: it dials
            //      8.8.8.8 to decide whether the user is online before
            //      attempting a network fetch). A real C2 stub would use a
            //      domain or attacker-controlled IP, not a well-publicized
            //      free recursive resolver, so the FP floor on these is
            //      effectively zero.
            //
            //      Listed: Google (8.8.8.8 / 8.8.4.4), Cloudflare (1.1.1.1 /
            //      1.0.0.1), Quad9 (9.9.9.9 / 149.112.112.112), OpenDNS
            //      (208.67.222.222 / 208.67.220.220).
            if ( a == 8 && b == 8 && c == 8 && d == 8 ) return true;
            if ( a == 8 && b == 8 && c == 4 && d == 4 ) return true;
            if ( a == 1 && b == 1 && c == 1 && d == 1 ) return true;
            if ( a == 1 && b == 0 && c == 0 && d == 1 ) return true;
            if ( a == 9 && b == 9 && c == 9 && d == 9 ) return true;
            if ( a == 149 && b == 112 && c == 112 && d == 112 ) return true;
            if ( a == 208 && b == 67 && c == 222 && d == 222 ) return true;
            if ( a == 208 && b == 67 && c == 220 && d == 220 ) return true;

            // (2) Trailing-zero pad — X.0.0.0 / X.Y.0.0. Almost always a
            //     version-major sentinel (e.g. "114.0.0.0" from OneConfig)
            //     or a subnet base address in a filter list, not an endpoint.
            if ( c == 0 && d == 0 ) return true;

            // (3) Version-string heuristic. Skip when the leading octet is
            //     small (< 32) AND the IPv4 match is NOT acting as a URL's
            //     host. The host distinction matters because version strings
            //     show up in many shapes:
            //
            //     <ul>
            //       <li>Bare: {@code "6.1.7.2"} (FAWE's Jars constant)</li>
            //       <li>Embedded in metadata: {@code "User-Agent: FTBLib/1.9.0.3"},
            //           {@code "BiomesOPlenty-1.9.0.11"}</li>
            //       <li>Embedded in URL paths/queries:
            //           {@code "https://empcraft.com/fawe/6.1.7.2/file.jar"},
            //           {@code ".../api?v=1.2.3.4"}</li>
            //     </ul>
            //
            //     An IPv4 is a URL host only when immediately preceded by
            //     {@code ://} (scheme separator) or {@code @} (after
            //     userinfo). All other positions — JAR-root path
            //     fragments, query string values, bare strings, free
            //     prose — are not endpoint references.
            //
            //     Trade-off: motivated attackers using public IPs in the
            //     1-31/8 ranges (1/8 APNIC, 3/8 Amazon, 4/8 Level3, 8/8
            //     Level3, 23/8 Akamai, etc.) in non-URL form would escape
            //     this filter. Acceptable — Nekodetector still runs, and
            //     the higher-signal HIGH checks (webhooks, paste hosts,
            //     credential files, embedded executables) are not weakened.
            boolean smallLeadingOctet = a < 32;
            boolean ipIsUrlHost = isIpUrlHost( fullString, m.start() );
            if ( smallLeadingOctet && !ipIsUrlHost ) {
                return true;
            }

            return false;
        }
        catch ( NumberFormatException e ) {
            // Couldn't parse — let the original positive match through; the
            // outer guard already validated octet ranges.
            return false;
        }
    }

    /** Returns true when an IPv4 match at {@code ipStart} in {@code fullString}
     *  is positioned as that URL's host — i.e., immediately preceded by
     *  {@code ://} (scheme→host transition) or {@code @} (userinfo→host
     *  transition). Anything else (path fragment, query value, bare string,
     *  free prose) is treated as not-a-host so the version-string heuristic
     *  upstream can drop it.
     *
     *  <p>This is deliberately narrow — there's no host-port-validation,
     *  no IDN unwrap, no scheme allowlist. The only question is "did the
     *  string look like the IP was meant to be dialed?" and the {@code ://}
     *  / {@code @} sentinels are the cleanest signal for that.</p> */
    private static boolean isIpUrlHost( String fullString, int ipStart )
    {
        if ( fullString == null || ipStart < 0 ) return false;
        // Preceded by "://" — typical "http://1.2.3.4..." host position.
        if ( ipStart >= 3
                && fullString.charAt( ipStart - 1 ) == '/'
                && fullString.charAt( ipStart - 2 ) == '/'
                && fullString.charAt( ipStart - 3 ) == ':' ) {
            return true;
        }
        // Preceded by "@" — userinfo-then-host, e.g. "http://user:pass@1.2.3.4".
        if ( ipStart >= 1 && fullString.charAt( ipStart - 1 ) == '@' ) {
            return true;
        }
        return false;
    }

    /**
     * Returns whether a JAR entry path sits under a known-legitimate native
     * library location, used to suppress false-positive findings on bundled
     * native binaries shipped by reputable libraries.
     *
     * @param normalizedName the lower-cased, forward-slash-normalized entry name
     *
     * @return {@code true} if the name contains a recognized native-path prefix
     */
    private static boolean isInLegitNativePath( String normalizedName )
    {
        for ( String prefix : LEGIT_NATIVE_PATH_PREFIXES ) {
            if ( normalizedName.contains( prefix ) ) {
                return true;
            }
        }
        return false;
    }

    /** Filename-only legitimacy check for native binaries. Mods bundle
     *  natives at every conceivable path layout (root, {@code lib/}, mod
     *  asset folders, platform-triple subdirs), so location-based heuristics
     *  alone leak too many false positives. A filename matching the hard
     *  platform convention is itself a strong "this is a real native
     *  library" signal:
     *
     *  <ul>
     *    <li>{@code lib<name>.{so,dylib,jnilib}} — Unix convention.
     *        The {@code lib} prefix is required by {@code dlopen} on
     *        Linux/macOS; droppers don't bother with it. {@code lib*.so} at
     *        a JAR root is essentially proving it's a real shared library.</li>
     *    <li>{@code <name>.dll} — Windows convention. DLLs in mod JARs are
     *        almost universally cross-platform native bundles (SDL,
     *        Discord-RPC, junixsocket Windows half, etc.). Real DLL-side-
     *        loaders need {@code Runtime.exec}/{@code regsvr32}/{@code
     *        rundll32} machinery the embedded-executable check
     *        (.exe/.bat/.ps1/etc.) catches first and at HIGH severity.</li>
     *  </ul>
     *
     *  <p>This widens the legitimate set so far that the path-based check
     *  is effectively informational — it'll still fire on truly weird cases
     *  like a JAR root drop of {@code random.jnilib} without a {@code lib}
     *  prefix, which is rare enough to be worth a MEDIUM log line.</p> */
    private static boolean hasLegitNativeFilename( String normalizedName )
    {
        int slashIdx = normalizedName.lastIndexOf( '/' );
        String base = slashIdx < 0 ? normalizedName : normalizedName.substring( slashIdx + 1 );
        return base.startsWith( "lib" ) || base.endsWith( ".dll" );
    }

    /** Hard ceiling on how many bytes a single scanned JAR entry may inflate to
     *  before {@link #readAll} aborts. The scanner inspects attacker-supplied mod
     *  JARs, so an entry whose compressed size is tiny but inflates to gigabytes
     *  (a zip bomb) could OOM the launcher mid-scan — and {@code scanFolder}
     *  inflates several entries in parallel. No legitimate class or bundled native
     *  approaches this; entries that exceed it degrade gracefully (the class is
     *  skipped / the inner hash comes back null) rather than exhausting the heap. */
    private static final long MAX_SCAN_ENTRY_BYTES = 256L * 1024L * 1024L;

    /**
     * Reads an input stream fully into a byte array, aborting if it exceeds
     * {@link #MAX_SCAN_ENTRY_BYTES} — a zip-bomb guard so a maliciously-inflating
     * JAR entry can't exhaust the heap during scanning.
     *
     * @param in the stream to read (not closed by this method)
     *
     * @return the stream's contents as a byte array
     *
     * @throws IOException on read failure, or if the read ceiling is exceeded
     */
    private static byte[] readAll( InputStream in ) throws IOException
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        long total = 0L;
        while ( ( n = in.read( buf ) ) != -1 ) {
            total += n;
            if ( total > MAX_SCAN_ENTRY_BYTES ) {
                throw new IOException( "Scanned JAR entry exceeds the " + MAX_SCAN_ENTRY_BYTES
                                               + "-byte read ceiling (possible zip bomb); aborting read." );
            }
            out.write( buf, 0, n );
        }
        return out.toByteArray();
    }

    /**
     * Normalizes a list of user-supplied scan-exclusion paths: trims, strips
     * leading/trailing slashes, lower-cases, converts backslashes to forward
     * slashes, and drops blanks — so they can be matched uniformly against
     * relativized entry paths in {@link #isExcluded}.
     *
     * @param raw the raw exclusion strings (may be {@code null})
     *
     * @return a normalized, blank-free exclusion list (never {@code null})
     */
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

    /**
     * Returns whether a target path is covered by any normalized exclusion,
     * matching either the exact relative path or a directory prefix of it.
     *
     * @param root       the scan root the target is relativized against
     * @param target     the file/dir being considered
     * @param exclusions normalized exclusions from {@link #normalizeExclusions}
     *
     * @return {@code true} if {@code target} is excluded from scanning
     */
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
