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

package com.micatechnologies.minecraft.launcher.utilities;

import com.micatechnologies.minecraft.launcher.files.Logger;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Removes Mojang's META-INF signing from legacy {@code minecraft.jar} files. Pre-1.6 jars are
 * signed with {@code MOJANG_C.SF}/{@code MOJANG_C.DSA}, and launchwrapper's
 * {@link net.minecraft.launchwrapper.LaunchClassLoader} transforms the {@code Minecraft}
 * class at load time — which trips JarVerifier and can leave the {@code JarFile} in a state
 * where subsequent resource lookups (e.g. {@code /lang/en_US.lang} from StringTranslate) fail
 * silently and return {@code null}, producing an NPE inside the game.
 *
 * <p>Stripping the signature is the standard workaround used by every modded MC launcher
 * (MultiMC, Prism, ATLauncher, the Forge installer) for pre-1.6 packs. The launcher already
 * verifies the downloaded jar via SHA-1, so removing Mojang's signature doesn't weaken
 * integrity.</p>
 *
 * <p><b>Don't apply this to 1.6+.</b> Forge 1.6+ ships {@code FMLSanityChecker}, which
 * validates the Mojang fingerprint on {@code ClientBrandRetriever.class} and aborts with
 * "CRITICAL TAMPERING WITH MINECRAFT" if the signature has been removed. Callers must gate on
 * {@link #isStripRequiredFor(String)} so the strip only runs where it's actually needed.</p>
 */
public final class JarSigningStripper
{
    /** Signature-related entries removed when stripping. SF/DSA/RSA/EC are the per-signer
     *  files; the others are commonly seen in JCE/JAR-signed bundles. */
    private static final String[] SIG_SUFFIXES = { ".SF", ".DSA", ".RSA", ".EC" };

    /** Matches the leading {@code 1.<minor>} of a release version string. Snapshots
     *  ({@code 20w28a}, {@code 1.21-pre3}) and oddball strings simply don't match and are
     *  treated as "modern" (no strip). */
    private static final Pattern RELEASE_MINOR = Pattern.compile( "^1\\.(\\d+)" );

    private JarSigningStripper() { /* static-only */ }

    /**
     * True iff the strip is needed for the given Minecraft version — i.e. {@code 1.0}–{@code 1.5.x},
     * the era where launchwrapper's class transformer trips JarVerifier and breaks
     * StringTranslate's lang lookups. For {@code 1.6+} (Forge's {@code FMLSanityChecker} era)
     * and for anything that doesn't parse as a normal release version, returns false — those
     * either don't need it or would actively crash if we stripped.
     *
     * @param mcVersion Minecraft version string (e.g. {@code "1.5.2"}, {@code "1.12.2"})
     * @return true if signing should be stripped for this version
     */
    public static boolean isStripRequiredFor( String mcVersion )
    {
        if ( mcVersion == null || mcVersion.isEmpty() ) {
            return false;
        }
        Matcher m = RELEASE_MINOR.matcher( mcVersion );
        if ( !m.find() ) {
            return false;
        }
        try {
            return Integer.parseInt( m.group( 1 ) ) < 6;
        }
        catch ( NumberFormatException e ) {
            return false;
        }
    }

    /**
     * Rewrites {@code jarFile} in place with all signature entries removed and per-entry
     * digest attributes stripped from the manifest. Idempotent: if the jar isn't signed,
     * returns {@code false} without touching the file.
     *
     * @param jarFile the jar to strip
     * @return true if the jar was rewritten, false if no signing was found
     * @throws IOException if reading or writing fails
     */
    public static boolean stripSigning( File jarFile ) throws IOException
    {
        if ( jarFile == null || !jarFile.isFile() ) {
            return false;
        }

        boolean signed;
        try ( JarFile jf = new JarFile( jarFile, false ) ) {
            signed = hasSignatureFiles( jf );
        }
        if ( !signed ) {
            return false;
        }

        File temp = new File( jarFile.getAbsolutePath() + ".unsigned.tmp" );
        try ( JarFile in = new JarFile( jarFile, false );
              JarOutputStream out = new JarOutputStream(
                      new BufferedOutputStream( new FileOutputStream( temp ) ) ) )
        {
            Enumeration< JarEntry > entries = in.entries();
            byte[] buffer = new byte[ 8192 ];
            while ( entries.hasMoreElements() ) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if ( isSignatureFile( name ) ) {
                    continue;
                }
                if ( "META-INF/MANIFEST.MF".equalsIgnoreCase( name ) ) {
                    writeStrippedManifest( in, out );
                    continue;
                }
                JarEntry copied = new JarEntry( entry.getName() );
                if ( entry.getTime() != -1 ) {
                    copied.setTime( entry.getTime() );
                }
                out.putNextEntry( copied );
                try ( InputStream is = in.getInputStream( entry ) ) {
                    int n;
                    while ( ( n = is.read( buffer ) ) != -1 ) {
                        out.write( buffer, 0, n );
                    }
                }
                out.closeEntry();
            }
        }

        Files.move( temp.toPath(), jarFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE );
        Logger.logDebug( "Stripped signing from " + jarFile.getName() );
        return true;
    }

    private static boolean hasSignatureFiles( JarFile jf )
    {
        Enumeration< JarEntry > e = jf.entries();
        while ( e.hasMoreElements() ) {
            if ( isSignatureFile( e.nextElement().getName() ) ) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSignatureFile( String name )
    {
        if ( name == null ) return false;
        String upper = name.toUpperCase();
        if ( !upper.startsWith( "META-INF/" ) ) {
            return false;
        }
        for ( String suffix : SIG_SUFFIXES ) {
            if ( upper.endsWith( suffix ) ) {
                return true;
            }
        }
        return false;
    }

    /** Reads MANIFEST.MF, removes per-entry sections (which hold the now-meaningless digest
     *  attributes), and writes the result. The main attributes — {@code Manifest-Version},
     *  {@code Created-By}, {@code Main-Class}, etc. — are preserved unchanged. */
    private static void writeStrippedManifest( JarFile in, JarOutputStream out ) throws IOException
    {
        JarEntry manifestEntry = in.getJarEntry( "META-INF/MANIFEST.MF" );
        Manifest manifest = new Manifest();
        try ( InputStream is = new BufferedInputStream( in.getInputStream( manifestEntry ) ) ) {
            manifest.read( is );
        }

        // Drop all per-entry sections; preserve main attributes minus any signature ones.
        Attributes mainAttrs = manifest.getMainAttributes();
        mainAttrs.remove( new Attributes.Name( "Signature-Version" ) );
        manifest.getEntries().clear();

        ByteArrayOutputStream buf = new ByteArrayOutputStream( 1024 );
        manifest.write( buf );

        JarEntry copied = new JarEntry( "META-INF/MANIFEST.MF" );
        if ( manifestEntry.getTime() != -1 ) {
            copied.setTime( manifestEntry.getTime() );
        }
        out.putNextEntry( copied );
        try ( InputStream is = new ByteArrayInputStream( buf.toByteArray() ) ) {
            byte[] buffer = new byte[ 1024 ];
            int n;
            while ( ( n = is.read( buffer ) ) != -1 ) {
                out.write( buffer, 0, n );
            }
        }
        out.closeEntry();
    }
}
