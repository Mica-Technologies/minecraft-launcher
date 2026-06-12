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

import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.sun.jna.Native;
import com.sun.jna.WString;
import com.sun.jna.win32.StdCallLibrary;
import org.apache.commons.lang3.SystemUtils;

/**
 * Removes the current working directory from the Windows DLL search order at
 * startup, closing a DLL-planting vector for the bare-name native loads the
 * launcher performs (the RGB vendor SDKs — {@code RzChromaSDK64},
 * {@code iCUESDK.x64_*}, {@code AURA_SDK} — are loaded by name via JNA and are
 * not System32 KnownDLLs, so the loader would otherwise fall through to the
 * application directory, the current directory, and {@code PATH}).
 *
 * <p>An attacker who can drop e.g. {@code RzChromaSDK64.dll} into the launcher's
 * working directory (running the portable build from {@code Downloads}, say)
 * would get it loaded into the launcher process when RGB is enabled.
 * {@code SetDllDirectory("")} removes the current-directory slot from the search
 * path while leaving the application directory, System32, and {@code PATH} in
 * place — so a legitimately-installed vendor SDK (which registers its folder on
 * {@code PATH}) still loads, but the current-directory plant no longer resolves.</p>
 *
 * <p>Deliberately the narrow {@code SetDllDirectory("")} rather than
 * {@code SetDefaultDllDirectories(...)}: the latter would also drop {@code PATH}
 * from the search order and break vendor-SDK resolution for users who actually
 * have the RGB software installed.</p>
 *
 * <p>Best-effort and idempotent — any failure logs a warning and leaves the
 * default search order in place. Must run before the first bare-name
 * {@code Native.load} of a non-KnownDLL (i.e. before the RGB auto-probe), so it
 * is called at the very top of {@code main()}.</p>
 *
 * @since 2026.6
 */
public final class WindowsDllSearchHardening
{
    private WindowsDllSearchHardening() { /* static-only */ }

    /** Minimal kernel32 binding. kernel32.dll is a System32 KnownDLL, so loading
     *  it by name is itself safe from planting. */
    public interface Kernel32Dll extends StdCallLibrary
    {
        Kernel32Dll INSTANCE = SystemUtils.IS_OS_WINDOWS
                               ? Native.load( "kernel32", Kernel32Dll.class )
                               : null;

        /** {@code BOOL SetDllDirectoryW(LPCWSTR)}. An empty string removes the
         *  current directory from the search path; {@code null} would restore the
         *  default (current-directory-included) order. */
        boolean SetDllDirectoryW( WString lpPathName );
    }

    /**
     * Removes the current working directory from this process's DLL search order.
     * No-op off Windows.
     */
    public static void removeCurrentDirectoryFromSearchPath()
    {
        if ( !SystemUtils.IS_OS_WINDOWS ) {
            return;
        }
        try {
            if ( !Kernel32Dll.INSTANCE.SetDllDirectoryW( new WString( "" ) ) ) {
                Logger.logWarningSilent( LocalizationManager.get( "log.dllHardening.returnedFalse" ) );
            }
        }
        catch ( Throwable t ) {
            Logger.logWarningSilent( LocalizationManager.format( "log.dllHardening.failed", t.getMessage() ) );
        }
    }
}
