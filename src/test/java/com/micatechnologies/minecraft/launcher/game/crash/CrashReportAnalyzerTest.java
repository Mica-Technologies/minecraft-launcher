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

package com.micatechnologies.minecraft.launcher.game.crash;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pattern-coverage tests for {@link CrashReportAnalyzer}. One representative crash-text
 * snippet per detector, asserting the resulting {@link CrashDiagnosis.Category} +
 * a key phrase that proves the right branch fired.
 *
 * <p>The detector list is priority-ordered ({@link CrashReportAnalyzer#DETECTORS}), so
 * tests that exercise the more-specific detectors deliberately include text that would
 * also match the catch-all detectors — proving the priority order holds. A future
 * Forge format drift that breaks one detector will show up as the catch-all winning,
 * which is much easier to diagnose than silent regressions.</p>
 *
 * <p>All snippets are inline literals (no fixture files) so the tests stay trivially
 * portable across the repo and don't depend on resource-loading. Each one is short —
 * enough text to fire the regex, not a full crash dump. Pack is always {@code null}
 * since the detectors don't consult pack state for their match decision (only for
 * suggestion population, which we don't assert against here — the suggestion lists
 * touch filesystem / GUI globals and the unit-test surface intentionally stays
 * runtime-free).</p>
 */
class CrashReportAnalyzerTest
{
    private static final int FAKE_EXIT_CODE = -1;

    // =========================================================================================
    //  Existing detectors — pin coverage so a future refactor doesn't silently regress them.
    // =========================================================================================

    @Test
    void outOfMemoryDetected()
    {
        String crash = """
                ---- Minecraft Crash Report ----
                Description: Exception in server tick loop

                java.lang.OutOfMemoryError: Java heap space
                \tat net.minecraft.world.World.tick(World.java:1234)
                """;
        CrashDiagnosis diag = CrashReportAnalyzer.analyze( crash, null, FAKE_EXIT_CODE );
        assertEquals( CrashDiagnosis.Category.OUT_OF_MEMORY, diag.category() );
        assertTrue( diag.title().toLowerCase().contains( "memory" ),
                "OOM title should mention memory: " + diag.title() );
    }

    @Test
    void javaVersionMismatchDetected()
    {
        String crash = """
                Caused by: java.lang.UnsupportedClassVersionError:
                  com/example/Mod has been compiled by a more recent version of the Java Runtime
                  (class file version 61.0), this version of the Java Runtime only recognizes
                  class file versions up to 55.0
                """;
        CrashDiagnosis diag = CrashReportAnalyzer.analyze( crash, null, FAKE_EXIT_CODE );
        assertEquals( CrashDiagnosis.Category.JAVA_VERSION, diag.category() );
        assertTrue( diag.summary().contains( "Java 17" ),
                "Should translate class file 61 → Java 17: " + diag.summary() );
    }

    @Test
    void diskFullDetected()
    {
        String crash = "java.io.IOException: No space left on device";
        CrashDiagnosis diag = CrashReportAnalyzer.analyze( crash, null, FAKE_EXIT_CODE );
        assertEquals( CrashDiagnosis.Category.DISK_IO, diag.category() );
    }

    @Test
    void nativeLibraryFailureDetected()
    {
        String crash = """
                java.lang.UnsatisfiedLinkError: Could not load library lwjgl64.dll
                  at org.lwjgl.system.Library.loadNative(Library.java:1)
                """;
        CrashDiagnosis diag = CrashReportAnalyzer.analyze( crash, null, FAKE_EXIT_CODE );
        assertEquals( CrashDiagnosis.Category.NATIVE_LIBRARY, diag.category() );
    }

    @Test
    void mixinConflictDetected()
    {
        String crash = """
                org.spongepowered.asm.mixin.transformer.throwables.MixinApplyError:
                  Mixin [mixins.examplemod.json:HudOverlay] from phase
                  could not apply to net.minecraft.client.gui.GuiIngame
                """;
        CrashDiagnosis diag = CrashReportAnalyzer.analyze( crash, null, FAKE_EXIT_CODE );
        assertEquals( CrashDiagnosis.Category.MIXIN_CONFLICT, diag.category() );
        assertTrue( diag.summary().contains( "examplemod" ),
                "Mixin owner extraction should name the mod: " + diag.summary() );
    }

    @Test
    void forgeDependencyMismatchDetected()
    {
        String crash = "Mod 'JEI' (jei) requires patchouli [1.20.1,) loaded";
        CrashDiagnosis diag = CrashReportAnalyzer.analyze( crash, null, FAKE_EXIT_CODE );
        assertEquals( CrashDiagnosis.Category.MOD_LOADING, diag.category() );
        assertTrue( diag.title().toLowerCase().contains( "dependency" )
                            || diag.title().toLowerCase().contains( "mismatched" ),
                "Forge dep mismatch title: " + diag.title() );
    }

    @Test
    void modLoadingGenericFallbackDetected()
    {
        // No structured Forge message — just a NoClassDefFoundError. Should land on the
        // generic MOD_LOADING detector, not bubble through to UNKNOWN.
        String crash = "java.lang.NoClassDefFoundError: com/example/SomeApi";
        CrashDiagnosis diag = CrashReportAnalyzer.analyze( crash, null, FAKE_EXIT_CODE );
        assertEquals( CrashDiagnosis.Category.MOD_LOADING, diag.category() );
        assertTrue( diag.summary().contains( "com.example.SomeApi" ),
                "Missing class should be named: " + diag.summary() );
    }

    @Test
    void gpuOpenGlFailureDetected()
    {
        String crash = "Failed to create OpenGL context: GLFW error 65540";
        CrashDiagnosis diag = CrashReportAnalyzer.analyze( crash, null, FAKE_EXIT_CODE );
        assertEquals( CrashDiagnosis.Category.GPU, diag.category() );
    }

    @Test
    void worldCorruptionDetected()
    {
        String crash = "java.io.IOException: Could not load level — RegionFileException at chunk 12,34";
        CrashDiagnosis diag = CrashReportAnalyzer.analyze( crash, null, FAKE_EXIT_CODE );
        assertEquals( CrashDiagnosis.Category.WORLD_CORRUPTION, diag.category() );
    }

    @Test
    void authFailureDetected()
    {
        String crash = "Could not authenticate against Mojang: Invalid session ID";
        CrashDiagnosis diag = CrashReportAnalyzer.analyze( crash, null, FAKE_EXIT_CODE );
        assertEquals( CrashDiagnosis.Category.AUTH, diag.category() );
    }

    @Test
    void unknownFallbackOnEmptyCrashText()
    {
        CrashDiagnosis diag = CrashReportAnalyzer.analyze( "", null, 42 );
        assertEquals( CrashDiagnosis.Category.UNKNOWN, diag.category() );
        assertTrue( diag.summary().contains( "42" ),
                "Unknown summary should surface the exit code: " + diag.summary() );
    }

    @Test
    void unknownFallbackOnUnrelatedException()
    {
        String crash = "java.lang.NullPointerException at com.example.Random.thing(Random.java:1)";
        CrashDiagnosis diag = CrashReportAnalyzer.analyze( crash, null, FAKE_EXIT_CODE );
        assertEquals( CrashDiagnosis.Category.UNKNOWN, diag.category() );
    }

    // =========================================================================================
    //  New detectors (the five additions in this commit)
    // =========================================================================================

    @Test
    void audioInitFailureDetected()
    {
        String crash = """
                com.mojang.blaze3d.audio.AudioException: Could not initialize OpenAL
                  Cause: ALSA lib pcm_dmix.c:1052:(snd_pcm_dmix_open) unable to open slave
                """;
        CrashDiagnosis diag = CrashReportAnalyzer.analyze( crash, null, FAKE_EXIT_CODE );
        assertEquals( CrashDiagnosis.Category.AUDIO, diag.category() );
        assertEquals( CrashDiagnosis.Severity.WARNING, diag.severity(),
                "Audio failures are non-fatal — gameplay continues silent" );
    }

    @Test
    void fileLockDetected()
    {
        String crash = "java.nio.file.FileSystemException: C:/...: The process cannot access the file because it is being used by another process";
        CrashDiagnosis diag = CrashReportAnalyzer.analyze( crash, null, FAKE_EXIT_CODE );
        assertEquals( CrashDiagnosis.Category.FILE_LOCK, diag.category() );
        assertTrue( diag.summary().toLowerCase().contains( "antivirus" ),
                "File-lock should mention antivirus as the likely cause: " + diag.summary() );
    }

    @Test
    void optifineSodiumConflictDetected()
    {
        // Both names present — should fire the LOADER_CONFLICT detector, not the
        // mixin / mod-loading catches, even though those patterns would also match
        // some of the surrounding noise.
        String crash = """
                java.lang.RuntimeException: Cannot load mod 'sodium' alongside 'optifine'
                  at me.jellysquid.mods.sodium.client.SodiumClientMod.<init>
                  Caused by: org.spongepowered.asm.mixin.transformer.throwables.MixinApplyError
                """;
        CrashDiagnosis diag = CrashReportAnalyzer.analyze( crash, null, FAKE_EXIT_CODE );
        assertEquals( CrashDiagnosis.Category.LOADER_CONFLICT, diag.category() );
        assertTrue( diag.title().toLowerCase().contains( "optifine" )
                            && diag.title().toLowerCase().contains( "sodium" ),
                "OptiFine+Sodium title should name both: " + diag.title() );
    }

    @Test
    void optifineWithoutSodiumDoesNotTriggerConflict()
    {
        // Lone OptiFine — must NOT trigger the conflict detector. The crash should
        // bubble through to whichever other detector matches (MOD_LOADING fallback
        // for a NoClassDefFoundError tail here).
        String crash = """
                Mod 'optifine' threw: java.lang.NoClassDefFoundError: missing/Dep
                """;
        CrashDiagnosis diag = CrashReportAnalyzer.analyze( crash, null, FAKE_EXIT_CODE );
        // Detector priority puts MOD_LOADING after LOADER_CONFLICT — without Sodium
        // the conflict detector returns null and we land here.
        assertEquals( CrashDiagnosis.Category.MOD_LOADING, diag.category() );
    }

    @Test
    void modMinecraftVersionMismatchDetected()
    {
        String crash = "Mod ExampleMod was compiled for Minecraft 1.20.1 but is being loaded by Minecraft 1.20.4";
        CrashDiagnosis diag = CrashReportAnalyzer.analyze( crash, null, FAKE_EXIT_CODE );
        assertEquals( CrashDiagnosis.Category.MOD_LOADING, diag.category() );
        assertTrue( diag.title().contains( "1.20.1" ) && diag.title().contains( "1.20.4" ),
                "MC-version mismatch title should name both versions: " + diag.title() );
    }

    @Test
    void jeiReiDualPresenceDetected()
    {
        // Both project namespaces present in a single crash — JEI + REI installed
        // alongside each other.
        String crash = """
                Caused by: java.lang.RuntimeException: Failed to register recipes
                  at mezz.jei.library.runtime.JeiRecipeManager
                  ... then ...
                  at me.shedaniel.rei.impl.ClientHelperImpl
                """;
        CrashDiagnosis diag = CrashReportAnalyzer.analyze( crash, null, FAKE_EXIT_CODE );
        assertEquals( CrashDiagnosis.Category.MOD_LOADING, diag.category() );
        assertTrue( diag.title().toLowerCase().contains( "jei" )
                            && diag.title().toLowerCase().contains( "rei" ),
                "JEI+REI title should name both: " + diag.title() );
    }

    @Test
    void duplicateRecipeIdDetected()
    {
        // Single-source duplicate recipe message — no dual JEI+REI presence required.
        String crash = "java.lang.IllegalStateException: Duplicate recipe id minecraft:crafting/example";
        CrashDiagnosis diag = CrashReportAnalyzer.analyze( crash, null, FAKE_EXIT_CODE );
        assertEquals( CrashDiagnosis.Category.MOD_LOADING, diag.category() );
        assertTrue( diag.title().toLowerCase().contains( "recipe" ),
                "Recipe collision title: " + diag.title() );
    }

    // =========================================================================================
    //  Priority-order regression tests
    // =========================================================================================

    @Test
    void oomBeatsDownstreamModLoadingFailure()
    {
        // OOM during mod loading would trip both detectors; the priority list
        // puts OOM first because it's the actionable root cause.
        String crash = """
                java.lang.OutOfMemoryError: Java heap space
                  at net.minecraftforge.fml.loading.moddiscovery.ModFile.<init>
                  Caused by: java.lang.NoClassDefFoundError
                """;
        CrashDiagnosis diag = CrashReportAnalyzer.analyze( crash, null, FAKE_EXIT_CODE );
        assertEquals( CrashDiagnosis.Category.OUT_OF_MEMORY, diag.category(),
                "OOM must beat the downstream mod-loading + NoClassDef noise" );
    }

    @Test
    void modMcVersionBeatsGenericModLoading()
    {
        // The MC-version detector picks the more specific diagnosis even though
        // the same text would also match the generic mod-loading pattern.
        String crash = "Mod ExampleMod was compiled for Minecraft 1.20.1 but is being loaded by Minecraft 1.20.4. Caused by: NoClassDefFoundError";
        CrashDiagnosis diag = CrashReportAnalyzer.analyze( crash, null, FAKE_EXIT_CODE );
        assertEquals( CrashDiagnosis.Category.MOD_LOADING, diag.category() );
        assertTrue( diag.title().contains( "1.20.1" ),
                "Specific MC-version detector must win over generic mod-loading" );
    }

    @Test
    void optifineSodiumBeatsMixinConflict()
    {
        // Same crash as the dedicated test but with extra mixin noise — proves the
        // structural conflict detector wins over the mixin-failure detector.
        String crash = """
                org.spongepowered.asm.mixin.transformer.throwables.MixinApplyError
                  Caused by: java.lang.RuntimeException sodium and optifine cannot coexist
                """;
        CrashDiagnosis diag = CrashReportAnalyzer.analyze( crash, null, FAKE_EXIT_CODE );
        assertEquals( CrashDiagnosis.Category.LOADER_CONFLICT, diag.category() );
    }

    @Test
    void analyzeNullCrashTextSurvives()
    {
        CrashDiagnosis diag = CrashReportAnalyzer.analyze( null, null, FAKE_EXIT_CODE );
        assertNotNull( diag );
        assertEquals( CrashDiagnosis.Category.UNKNOWN, diag.category() );
    }
}
