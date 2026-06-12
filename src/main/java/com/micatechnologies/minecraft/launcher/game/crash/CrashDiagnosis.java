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

import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;

import java.util.List;

/**
 * Structured result of running a Minecraft crash report through {@link CrashReportAnalyzer}.
 * Carries a one-sentence title, a 1-2 sentence plain-English summary, and zero or more
 * {@link Suggestion}s the user can act on.
 *
 * @param category   broad failure family (drives icon / color in the GUI)
 * @param severity   how critical the failure is (drives banner color)
 * @param title      short, headline-style label (~5-7 words). E.g. "Out of memory."
 * @param summary    1-2 sentences explaining the cause in user terms. Avoid stack-trace jargon.
 * @param suggestions ordered list of suggested actions; first item is the primary call-to-action
 */
public record CrashDiagnosis(
        Category category,
        Severity severity,
        String title,
        String summary,
        List< Suggestion > suggestions
)
{
    /** Failure family — picks the icon and broad category text in the GUI. */
    public enum Category
    {
        /** Java heap exhausted ({@code java.lang.OutOfMemoryError}). */
        OUT_OF_MEMORY,
        /** Mod compiled for a newer Java than the running runtime ({@code UnsupportedClassVersionError}). */
        JAVA_VERSION,
        /** Mod loading failure — typically a missing dependency, version conflict, or load order issue. */
        MOD_LOADING,
        /** Mixin transformation failure — a mod's mixin couldn't apply to its target class. */
        MIXIN_CONFLICT,
        /** Two mods that are known to be mutually incompatible were both loaded (e.g.
         *  OptiFine + Sodium). Distinct from MIXIN_CONFLICT because the failure is
         *  structural, not transformation-time. */
        LOADER_CONFLICT,
        /** GPU / OpenGL / display init failure (LWJGL, GLFW, pixel format). */
        GPU,
        /** Audio subsystem init failure — OpenAL device open, ALSA / PulseAudio mismatch. */
        AUDIO,
        /** Native library load failure ({@code UnsatisfiedLinkError}). */
        NATIVE_LIBRARY,
        /** Save data corruption — chunk / region / NBT read failure. */
        WORLD_CORRUPTION,
        /** Disk full / IO write failure. */
        DISK_IO,
        /** File locked by another process — most often antivirus on Windows. */
        FILE_LOCK,
        /** Authentication failure — bad session, expired token. */
        AUTH,
        /** Couldn't pattern-match the crash. Default fallback. */
        UNKNOWN
    }

    /** How severe the failure is — drives the banner color in the GUI. */
    public enum Severity
    {
        /** Game cannot run. Most crashes are critical. */
        CRITICAL,
        /** Game may still run, but a feature is degraded. (Currently unused, reserved.) */
        WARNING,
        /** Informational note — we know what happened and it's expected. */
        INFO
    }

    /** Fallback when no detector matches. Generic prompt to use the raw report. */
    public static CrashDiagnosis unknown( int exitCode )
    {
        return new CrashDiagnosis(
                Category.UNKNOWN,
                Severity.CRITICAL,
                LocalizationManager.get( "crash.unknown.title" ),
                LocalizationManager.format( "crash.unknown.summary", exitCode ),
                List.of() );
    }
}
