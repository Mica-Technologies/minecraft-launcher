/*
 * Copyright (c) 2021 Mica Technologies
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

package com.micatechnologies.minecraft.launcher.game.modpack;

import com.micatechnologies.minecraft.launcher.config.ConfigManager;
import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.utilities.NetworkUtilities;
import com.micatechnologies.minecraft.launcher.utilities.SystemUtilities;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;

/**
 * Utility class that patches LWJGL2 native libraries for ARM64 (aarch64) platforms. Minecraft 1.12.2 and below use
 * LWJGL 2.9.x, which only shipped x86/x86_64 native binaries. On ARM64 macOS (Apple Silicon) and ARM64 Linux, the
 * x86_64 natives will not load without Rosetta/emulation.
 * <p>
 * This patcher downloads community-built ARM64 native JARs and extracts them over the x86_64 natives in the game's
 * natives folder. The approach mirrors what Prism Launcher and MultiMC do for ARM64 support:
 * <ul>
 *   <li>LWJGL ARM64 macOS natives from MinecraftMachina/lwjgl</li>
 *   <li>jinput ARM64 macOS natives from r58Playz/jinput-m1</li>
 * </ul>
 *
 * @author Mica Technologies
 * @version 1.0
 * @since 3.0
 */
public class Lwjgl2ArmPatcher
{
    // region ARM64 macOS native replacement URLs

    /**
     * ARM64 macOS LWJGL platform natives JAR. Built from the LWJGL 2.9.4-nightly-20150209 source with aarch64 support.
     * Source: <a href="https://github.com/MinecraftMachina/lwjgl">MinecraftMachina/lwjgl</a>
     */
    private static final String LWJGL_NATIVES_MAC_ARM64_URL
            = "https://github.com/MinecraftMachina/lwjgl/releases/download/2.9.4-20150209-mmachina.2/lwjgl-platform-2.9.4-nightly-20150209-natives-osx.jar";

    /**
     * ARM64 macOS jinput platform natives JAR. Built with aarch64 HID support.
     * Source: <a href="https://github.com/r58Playz/jinput-m1">r58Playz/jinput-m1</a>
     */
    private static final String JINPUT_NATIVES_MAC_ARM64_URL
            = "https://github.com/r58Playz/jinput-m1/raw/main/plugins/OSX/bin/jinput-platform-2.0.5.jar";

    // endregion

    // region ARM64 Linux native replacement URLs

    /**
     * ARM64 Linux LWJGL platform natives JAR.
     * Source: <a href="https://github.com/theofficialgman/lwjgl3-binaries-arm64">theofficialgman/lwjgl3-binaries-arm64</a>
     */
    private static final String LWJGL_NATIVES_LINUX_ARM64_URL
            = "https://github.com/theofficialgman/lwjgl3-binaries-arm64/raw/lwjgl-2.9.4/lwjgl-platform-2.9.4-nightly-20150209-natives-linux.jar";

    /**
     * ARM64 Linux jinput platform natives JAR.
     * Source: <a href="https://github.com/theofficialgman/lwjgl3-binaries-arm64">theofficialgman/lwjgl3-binaries-arm64</a>
     */
    private static final String JINPUT_NATIVES_LINUX_ARM64_URL
            = "https://github.com/theofficialgman/lwjgl3-binaries-arm64/raw/lwjgl-2.9.4/jinput-platform-2.0.5-natives-linux.jar";

    // endregion

    /**
     * JNA 5.15.0 with ARM64 support — drop-in replacement for the x86_64-only JNA 4.4.0 bundled with MC 1.12.2.
     * Used by Minecraft's text2speech (narrator) and various mods. JNA 5.x is API-compatible with 4.x for the
     * subset used by Minecraft.
     */
    private static final String JNA_ARM64_URL
            = "https://repo1.maven.org/maven2/net/java/dev/jna/jna/5.15.0/jna-5.15.0.jar";

    /**
     * Classpath resource path for the no-op text2speech stub JAR. This tiny JAR provides
     * {@code com.mojang.text2speech.Narrator} that returns a silent no-op narrator, preventing crashes on ARM64
     * where the bundled text2speech library depends on x86_64-only native code (java-objc-bridge on macOS).
     */
    private static final String TEXT2SPEECH_STUB_RESOURCE = "/arm64/text2speech-noop.jar";

    /**
     * Local file names for cached ARM64 native JARs.
     */
    private static final String LWJGL_ARM64_NATIVES_CACHE_NAME = "lwjgl-arm64-natives.jar";
    private static final String JINPUT_ARM64_NATIVES_CACHE_NAME = "jinput-arm64-natives.jar";
    private static final String JNA_ARM64_CACHE_NAME = "jna-arm64.jar";

    /**
     * Determines whether LWJGL2 ARM64 patching is needed for the given Minecraft version. Patching is needed when all
     * of the following are true:
     * <ol>
     *   <li>The Minecraft version uses LWJGL2 (1.12.x and below)</li>
     *   <li>The current system is ARM64 (aarch64)</li>
     *   <li>The current system is macOS or Linux</li>
     *   <li>The LWJGL ARM patching setting is enabled in the launcher config</li>
     * </ol>
     *
     * @param mcVersion the Minecraft version string (e.g. "1.12.2")
     *
     * @return true if ARM64 native patching should be applied
     *
     * @since 3.0
     */
    public static boolean isNeeded( String mcVersion )
    {
        // Check if the config setting is enabled
        if ( !ConfigManager.getLwjglArmPatchEnable() ) {
            return false;
        }

        // Check architecture: must be aarch64/arm64
        String osArch = System.getProperty( "os.arch", "" );
        boolean isArm64 = osArch.contains( "aarch64" ) || osArch.contains( "arm64" );
        if ( !isArm64 ) {
            return false;
        }

        // Check OS: must be macOS or Linux (Windows ARM64 has different considerations)
        if ( org.apache.commons.lang3.SystemUtils.IS_OS_WINDOWS ) {
            return false;
        }

        // Check MC version: 1.12.x and below use LWJGL2
        return isLwjgl2Version( mcVersion );
    }

    /**
     * Downloads ARM64 native JARs, extracts them into the game's natives folder, and replaces the x86_64 native JARs
     * in the libraries folder so that any runtime extraction (by LWJGL, mods, etc.) also yields ARM64 binaries. This
     * should be called after the standard library download and native extraction completes.
     *
     * @param nativesFolderPath  path to the game's natives folder (e.g. {@code .../installs/Alto/bin/natives})
     * @param librariesFolder    path to the game's libraries folder (e.g. {@code .../installs/Alto/libraries})
     * @param cacheFolder        path to a folder for caching downloaded ARM64 JARs
     * @param progressProvider   optional progress provider for UI updates
     *
     * @since 3.0
     */
    public static void patchNatives( String nativesFolderPath, String librariesFolder, String cacheFolder,
                                     GameModPackProgressProvider progressProvider )
    {
        Logger.logStd( LocalizationManager.get( "log.lwjgl2Patcher.replacingNatives" ) );

        // Determine which URLs to use based on OS
        String lwjglUrl;
        String jinputUrl;
        String nativeSuffix;
        if ( org.apache.commons.lang3.SystemUtils.IS_OS_MAC ) {
            lwjglUrl = LWJGL_NATIVES_MAC_ARM64_URL;
            jinputUrl = JINPUT_NATIVES_MAC_ARM64_URL;
            nativeSuffix = "-natives-osx.jar";
        }
        else {
            lwjglUrl = LWJGL_NATIVES_LINUX_ARM64_URL;
            jinputUrl = JINPUT_NATIVES_LINUX_ARM64_URL;
            nativeSuffix = "-natives-linux.jar";
        }

        File cacheFolderFile = new File( cacheFolder );
        if ( !cacheFolderFile.exists() ) {
            cacheFolderFile.mkdirs();
        }

        File lwjglCacheFile = new File( cacheFolder, LWJGL_ARM64_NATIVES_CACHE_NAME );
        File jinputCacheFile = new File( cacheFolder, JINPUT_ARM64_NATIVES_CACHE_NAME );

        try {
            // Download LWJGL ARM64 natives
            if ( progressProvider != null ) {
                progressProvider.submitProgress( LocalizationManager.get( "lwjgl2Patcher.progress.downloadingLwjgl" ), 0 );
            }
            if ( !lwjglCacheFile.exists() ) {
                Logger.logStd( LocalizationManager.format( "log.lwjgl2Patcher.downloadingLwjglFrom", lwjglUrl ) );
                NetworkUtilities.downloadFileFromURL( lwjglUrl, lwjglCacheFile );
            }

            // Download jinput ARM64 natives
            if ( progressProvider != null ) {
                progressProvider.submitProgress( LocalizationManager.get( "lwjgl2Patcher.progress.downloadingJinput" ), 0 );
            }
            if ( !jinputCacheFile.exists() ) {
                Logger.logStd( LocalizationManager.format( "log.lwjgl2Patcher.downloadingJinputFrom", jinputUrl ) );
                NetworkUtilities.downloadFileFromURL( jinputUrl, jinputCacheFile );
            }

            // Download ARM64-compatible JNA to replace the x86_64-only JNA 4.4.0 bundled with MC 1.12.2
            // (used by narrator/text2speech and mods via java-objc-bridge)
            File jnaCacheFile = new File( cacheFolder, JNA_ARM64_CACHE_NAME );
            if ( !jnaCacheFile.exists() ) {
                if ( progressProvider != null ) {
                    progressProvider.submitProgress( LocalizationManager.get( "lwjgl2Patcher.progress.downloadingJna" ), 0 );
                }
                Logger.logStd( LocalizationManager.format( "log.lwjgl2Patcher.downloadingJnaFrom", JNA_ARM64_URL ) );
                NetworkUtilities.downloadFileFromURL( JNA_ARM64_URL, jnaCacheFile );
            }

            // Replace native JARs in the libraries folder so that ANY extraction path
            // (LWJGL's own extractor, mods like MC Mouser, etc.) yields ARM64 binaries
            if ( progressProvider != null ) {
                progressProvider.submitProgress( LocalizationManager.get( "lwjgl2Patcher.progress.replacingNativeJars" ), 0 );
            }
            replaceNativeJars( new File( librariesFolder ), lwjglCacheFile, jinputCacheFile, jnaCacheFile,
                               nativeSuffix );

            // Replace text2speech JAR with a no-op stub to prevent crashes from x86_64-only
            // native dependencies (java-objc-bridge on macOS, flite on Linux)
            replaceText2SpeechWithStub( new File( librariesFolder ) );

            // Also extract ARM64 natives directly into the game's natives folder
            if ( progressProvider != null ) {
                progressProvider.submitProgress( LocalizationManager.get( "lwjgl2Patcher.progress.extractingNatives" ), 0 );
            }
            Logger.logStd( LocalizationManager.format( "log.lwjgl2Patcher.extractingNativesTo", nativesFolderPath ) );
            // try-with-resources so the file handle releases even if the extract
            // throws — extractJarFile doesn't close the source itself.
            try ( JarFile lwjglNativesJar = new JarFile( lwjglCacheFile.getAbsolutePath() ) ) {
                SystemUtilities.extractJarFile( lwjglNativesJar, nativesFolderPath );
            }
            try ( JarFile jinputNativesJar = new JarFile( jinputCacheFile.getAbsolutePath() ) ) {
                SystemUtilities.extractJarFile( jinputNativesJar, nativesFolderPath );
            }

            // Set executable permissions on extracted native files
            File nativesDir = new File( nativesFolderPath );
            File[] nativeFiles = nativesDir.listFiles();
            if ( nativeFiles != null ) {
                for ( File f : nativeFiles ) {
                    if ( f.getName().endsWith( ".dylib" ) || f.getName().endsWith( ".so" ) ||
                            f.getName().endsWith( ".jnilib" ) ) {
                        f.setExecutable( true );
                        f.setReadable( true );
                    }
                }
            }

            Logger.logStd( LocalizationManager.get( "log.lwjgl2Patcher.completedSuccessfully" ) );
        }
        catch ( IOException e ) {
            Logger.logError( LocalizationManager.format( "log.lwjgl2Patcher.patchingFailed", e.getMessage() ) );
            Logger.logThrowable( e );
            // Clean up cached files on failure so they'll be re-downloaded next time
            if ( lwjglCacheFile.exists() ) {
                lwjglCacheFile.delete();
            }
            if ( jinputCacheFile.exists() ) {
                jinputCacheFile.delete();
            }
        }
        catch ( Exception e ) {
            Logger.logError( LocalizationManager.format( "log.lwjgl2Patcher.patchingFailedUnexpectedly", e.getMessage() ) );
            Logger.logThrowable( e );
        }
    }

    /**
     * Recursively scans the libraries folder for LWJGL, jinput, and JNA JARs and replaces them with ARM64-compatible
     * versions. This ensures that any code extracting natives from the classpath JARs at runtime gets ARM64 binaries.
     *
     * @param dir              directory to scan
     * @param lwjglArm64Jar    the cached ARM64 LWJGL native JAR
     * @param jinputArm64Jar   the cached ARM64 jinput native JAR
     * @param jnaArm64Jar      the cached ARM64-compatible JNA JAR
     * @param nativeSuffix     the platform-specific native JAR suffix (e.g. "-natives-osx.jar")
     *
     * @since 3.0
     */
    private static void replaceNativeJars( File dir, File lwjglArm64Jar, File jinputArm64Jar, File jnaArm64Jar,
                                           String nativeSuffix )
    {
        File[] children = dir.listFiles();
        if ( children == null ) {
            return;
        }
        for ( File child : children ) {
            if ( child.isDirectory() ) {
                // Skip the arm64-natives cache folder to avoid copying a file over itself
                if ( child.getName().equals( "arm64-natives" ) ) {
                    continue;
                }
                replaceNativeJars( child, lwjglArm64Jar, jinputArm64Jar, jnaArm64Jar, nativeSuffix );
            }
            else {
                String name = child.getName();
                try {
                    if ( name.endsWith( nativeSuffix ) ) {
                        if ( name.contains( "lwjgl-platform" ) ) {
                            Logger.logStd( LocalizationManager.format( "log.lwjgl2Patcher.replacingFile", child.getAbsolutePath() ) );
                            org.apache.commons.io.FileUtils.copyFile( lwjglArm64Jar, child );
                        }
                        else if ( name.contains( "jinput-platform" ) ) {
                            Logger.logStd( LocalizationManager.format( "log.lwjgl2Patcher.replacingFile", child.getAbsolutePath() ) );
                            org.apache.commons.io.FileUtils.copyFile( jinputArm64Jar, child );
                        }
                    }
                    else if ( name.startsWith( "jna-" ) && name.endsWith( ".jar" ) &&
                            !name.contains( "platform" ) ) {
                        Logger.logStd( LocalizationManager.format( "log.lwjgl2Patcher.replacingFile", child.getAbsolutePath() ) );
                        org.apache.commons.io.FileUtils.copyFile( jnaArm64Jar, child );
                    }
                }
                catch ( IOException e ) {
                    Logger.logError( LocalizationManager.format( "log.lwjgl2Patcher.failedToReplace", child.getAbsolutePath(),
                                             e.getMessage() ) );
                }
            }
        }
    }

    /**
     * Replaces Mojang's text2speech JAR with a no-op stub to prevent crashes from x86_64-only native dependencies
     * (java-objc-bridge on macOS). The stub provides a {@code Narrator} that returns a silent no-op implementation.
     *
     * @param librariesDir the libraries directory to scan
     *
     * @since 3.0
     */
    private static void replaceText2SpeechWithStub( File librariesDir )
    {
        // Find text2speech JARs
        List< File > text2speechJars = new ArrayList<>();
        findFilesByPrefix( librariesDir, "text2speech-", text2speechJars );

        if ( text2speechJars.isEmpty() ) {
            return;
        }

        try {
            // Build a minimal stub JAR in memory with a no-op Narrator
            byte[] stubJarBytes = buildText2SpeechStubJar();

            for ( File jar : text2speechJars ) {
                Logger.logStd( LocalizationManager.format( "log.lwjgl2Patcher.replacingText2speech",
                                       jar.getAbsolutePath() ) );
                org.apache.commons.io.FileUtils.writeByteArrayToFile( jar, stubJarBytes );
            }
        }
        catch ( Exception e ) {
            Logger.logError( LocalizationManager.format( "log.lwjgl2Patcher.failedToCreateText2speechStub", e.getMessage() ) );
            Logger.logThrowable( e );
        }
    }

    /**
     * Recursively finds files whose name starts with the given prefix.
     */
    private static void findFilesByPrefix( File dir, String prefix, List< File > results )
    {
        File[] children = dir.listFiles();
        if ( children == null ) {
            return;
        }
        for ( File child : children ) {
            if ( child.isDirectory() ) {
                if ( !child.getName().equals( "arm64-natives" ) ) {
                    findFilesByPrefix( child, prefix, results );
                }
            }
            else if ( child.getName().startsWith( prefix ) && child.getName().endsWith( ".jar" ) ) {
                results.add( child );
            }
        }
    }

    /**
     * Builds a minimal JAR containing a no-op {@code com.mojang.text2speech.Narrator} class. The stub's
     * {@code getNarrator()} returns a narrator whose methods do nothing, preventing native library loading.
     * <p>
     * The bytecode is generated directly using ASM to avoid runtime compilation dependencies.
     *
     * @return the stub JAR as a byte array
     *
     * @since 3.0
     */
    private static byte[] buildText2SpeechStubJar() throws IOException
    {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        java.util.jar.JarOutputStream jos = new java.util.jar.JarOutputStream( baos );

        // The original Narrator is an INTERFACE with a static getNarrator() method.
        // We need: 1) the Narrator interface, 2) a concrete NarratorNoop implementation.
        // getNarrator() returns the no-op implementation so no platform-specific native code is loaded.

        // --- NarratorNoop class (implements Narrator) ---
        org.objectweb.asm.ClassWriter noopCw = new org.objectweb.asm.ClassWriter( 0 );
        noopCw.visit( org.objectweb.asm.Opcodes.V1_8, org.objectweb.asm.Opcodes.ACC_PUBLIC,
                      "com/mojang/text2speech/NarratorNoop", null, "java/lang/Object",
                      new String[]{ "com/mojang/text2speech/Narrator" } );

        // public <init>()
        org.objectweb.asm.MethodVisitor noopInit = noopCw.visitMethod( org.objectweb.asm.Opcodes.ACC_PUBLIC,
                                                                        "<init>", "()V", null, null );
        noopInit.visitCode();
        noopInit.visitVarInsn( org.objectweb.asm.Opcodes.ALOAD, 0 );
        noopInit.visitMethodInsn( org.objectweb.asm.Opcodes.INVOKESPECIAL, "java/lang/Object",
                                  "<init>", "()V", false );
        noopInit.visitInsn( org.objectweb.asm.Opcodes.RETURN );
        noopInit.visitMaxs( 1, 1 );
        noopInit.visitEnd();

        // No-op void methods: say(String, boolean), clear(), destroy(), close()
        for ( String[] method : new String[][]{
                { "say", "(Ljava/lang/String;Z)V" },
                { "clear", "()V" },
                { "destroy", "()V" },
                { "close", "()V" }
        } ) {
            org.objectweb.asm.MethodVisitor mv = noopCw.visitMethod( org.objectweb.asm.Opcodes.ACC_PUBLIC,
                                                                      method[0], method[1], null, null );
            mv.visitCode();
            mv.visitInsn( org.objectweb.asm.Opcodes.RETURN );
            mv.visitMaxs( 0, 3 );
            mv.visitEnd();
        }

        // boolean active() -> return false
        org.objectweb.asm.MethodVisitor active = noopCw.visitMethod( org.objectweb.asm.Opcodes.ACC_PUBLIC,
                                                                      "active", "()Z", null, null );
        active.visitCode();
        active.visitInsn( org.objectweb.asm.Opcodes.ICONST_0 );
        active.visitInsn( org.objectweb.asm.Opcodes.IRETURN );
        active.visitMaxs( 1, 1 );
        active.visitEnd();

        noopCw.visitEnd();
        byte[] noopClass = noopCw.toByteArray();

        // --- Narrator interface ---
        org.objectweb.asm.ClassWriter ifCw = new org.objectweb.asm.ClassWriter( 0 );
        ifCw.visit( org.objectweb.asm.Opcodes.V1_8,
                    org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_INTERFACE |
                            org.objectweb.asm.Opcodes.ACC_ABSTRACT,
                    "com/mojang/text2speech/Narrator", null, "java/lang/Object", null );

        // Abstract interface methods
        for ( String[] method : new String[][]{
                { "say", "(Ljava/lang/String;Z)V" },
                { "clear", "()V" },
                { "destroy", "()V" },
                { "close", "()V" },
                { "active", "()Z" }
        } ) {
            ifCw.visitMethod( org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_ABSTRACT,
                              method[0], method[1], null, null ).visitEnd();
        }

        // public static Narrator getNarrator() — returns NarratorNoop
        org.objectweb.asm.MethodVisitor getNarrator = ifCw.visitMethod(
                org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_STATIC,
                "getNarrator", "()Lcom/mojang/text2speech/Narrator;", null, null );
        getNarrator.visitCode();
        getNarrator.visitTypeInsn( org.objectweb.asm.Opcodes.NEW, "com/mojang/text2speech/NarratorNoop" );
        getNarrator.visitInsn( org.objectweb.asm.Opcodes.DUP );
        getNarrator.visitMethodInsn( org.objectweb.asm.Opcodes.INVOKESPECIAL,
                                     "com/mojang/text2speech/NarratorNoop", "<init>", "()V", false );
        getNarrator.visitInsn( org.objectweb.asm.Opcodes.ARETURN );
        getNarrator.visitMaxs( 2, 0 );
        getNarrator.visitEnd();

        ifCw.visitEnd();
        byte[] narratorInterface = ifCw.toByteArray();

        // Write both classes to JAR
        jos.putNextEntry( new java.util.jar.JarEntry( "com/mojang/text2speech/Narrator.class" ) );
        jos.write( narratorInterface );
        jos.closeEntry();

        jos.putNextEntry( new java.util.jar.JarEntry( "com/mojang/text2speech/NarratorNoop.class" ) );
        jos.write( noopClass );
        jos.closeEntry();

        jos.close();
        return baos.toByteArray();
    }

    /**
     * Checks whether the given Minecraft version uses LWJGL2 (version 1.12.x and below). MC 1.13+ switched to LWJGL3,
     * which has official ARM64 support.
     *
     * @param mcVersion the Minecraft version string
     *
     * @return true if this version uses LWJGL2
     *
     * @since 3.0
     */
    static boolean isLwjgl2Version( String mcVersion )
    {
        try {
            String[] parts = mcVersion.split( "\\." );
            if ( parts.length >= 2 ) {
                int minor = Integer.parseInt( parts[ 1 ] );
                return minor <= 12;
            }
        }
        catch ( NumberFormatException e ) {
            Logger.logWarningSilent( LocalizationManager.format( "log.lwjgl2Patcher.couldNotParseMcVersion", mcVersion ) );
        }
        return false;
    }

    /**
     * List of mod JAR file names known to crash on ARM64 due to missing native libraries. These mods are temporarily
     * disabled (renamed to .disabled) when ARM64 patching is active. This is a stopgap until modpack manifests can
     * express per-architecture mod exclusions.
     *
     * @since 3.0
     */
    private static final String[] ARM64_INCOMPATIBLE_MODS = {
            "controllable.jar"  // MrCrayfish Controllable — uses SDL2 natives without ARM64 builds
    };

    /**
     * Disables mods known to be incompatible with ARM64 by renaming their JAR files so Forge won't load them.
     * Previously disabled files are left alone. This should be called after mod downloads complete.
     *
     * @param modsFolderPath path to the modpack's mods folder
     *
     * @since 3.0
     */
    public static void disableIncompatibleMods( String modsFolderPath )
    {
        File modsFolder = new File( modsFolderPath );
        if ( !modsFolder.exists() ) {
            return;
        }

        for ( String modFileName : ARM64_INCOMPATIBLE_MODS ) {
            File modFile = new File( modsFolder, modFileName );
            if ( modFile.exists() ) {
                File disabledFile = new File( modsFolder, modFileName + ".arm64disabled" );
                if ( modFile.renameTo( disabledFile ) ) {
                    Logger.logStd( LocalizationManager.format( "log.lwjgl2Patcher.disabledIncompatibleMod", modFileName,
                                           disabledFile.getName() ) );
                }
                else {
                    Logger.logError( LocalizationManager.format( "log.lwjgl2Patcher.failedToDisableIncompatibleMod", modFileName ) );
                }
            }
        }
    }

    /**
     * Private constructor to prevent instantiation of this utility class.
     */
    private Lwjgl2ArmPatcher() {}
}
