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

import com.micatechnologies.minecraft.launcher.consts.LauncherConstants;
import com.micatechnologies.minecraft.launcher.consts.localization.LocalizationManager;
import com.micatechnologies.minecraft.launcher.files.Logger;
import com.sun.jna.Function;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Guid;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.ptr.PointerByReference;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import org.apache.commons.lang3.SystemUtils;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Tells the Windows taskbar what to run when the user pins a launcher window.
 *
 * <p>The launcher sets an explicit AppUserModelID for its process (see
 * {@code LauncherCore.applySystemProperties}). A window with an explicit ID and no relaunch
 * command gets pinned as the process's own executable: the jpackage launcher
 * ({@code ... Core.exe}), which skips the native starter and its error reporting. So each launcher
 * window carries, through {@code SHGetPropertyStoreForWindow}, the same AppUserModelID plus a
 * relaunch command, display name and icon that point at the starter. A pin made from the running
 * window then starts the launcher the same way its shortcuts do.
 *
 * <p>Windows keeps these window properties until they are removed, so they are cleared when the
 * window hides, as the property-store documentation requires. Windows only; every failure is
 * logged and ignored, since the worst case is the old pin behaviour.
 *
 * @since 2026.10
 */
public final class WindowsRelaunchProperties
{
    /** {@code PKEY_AppUserModel_*} format id. */
    private static final Guid.GUID PKEY_APP_USER_MODEL =
            new Guid.GUID( "{9F4C2855-9F79-4B39-A8D0-E1D42DE1D5F3}" );

    /** {@code PKEY_AppUserModel_RelaunchCommand}. */
    private static final int PID_RELAUNCH_COMMAND = 2;

    /** {@code PKEY_AppUserModel_RelaunchIconResource}. */
    private static final int PID_RELAUNCH_ICON = 3;

    /** {@code PKEY_AppUserModel_RelaunchDisplayNameResource}. */
    private static final int PID_RELAUNCH_DISPLAY_NAME = 4;

    /** {@code PKEY_AppUserModel_ID}. */
    private static final int PID_ID = 5;

    /** IID of {@code IPropertyStore}. */
    private static final Guid.IID IID_IPROPERTY_STORE = new Guid.IID( "{886d8eeb-8cf2-4446-8d02-cdba1dbdcf99}" );

    /** {@code IUnknown::Release}. */
    private static final int IUNKNOWN_RELEASE = 2;

    /** {@code IPropertyStore::SetValue}. */
    private static final int IPS_SET_VALUE = 6;

    /** {@code VT_EMPTY}: removes the property. */
    private static final short VT_EMPTY = 0;

    /** {@code VT_LPWSTR}. */
    private static final short VT_LPWSTR = 31;

    /** Stages that currently carry the properties. */
    private static final Set< Stage > APPLIED = Collections.newSetFromMap( new WeakHashMap<>() );

    private WindowsRelaunchProperties() { /* static-only */ }

    /**
     * The AppUserModelID of the launcher's process and windows; DEV builds get their own so the two
     * flavours never share a taskbar group or pin.
     *
     * @return the id
     */
    public static String appUserModelId()
    {
        String base = "com.micatechnologies.minecraft.launcher.LauncherCore";
        return LauncherConstants.LAUNCHER_IS_DEV ? base + "DEV" : base;
    }

    /**
     * The command a pin should run: the quoted executable alone. A pin starts the launcher; any
     * arguments this run had (a deep link) belong to this run only.
     *
     * @param executable the launcher executable
     *
     * @return the command line
     */
    static String relaunchCommand( String executable )
    {
        return "\"" + executable + "\"";
    }

    /** Whether {@link #applyToAllWindows()} has hooked the window list. */
    private static boolean watching;

    /**
     * Applies the properties to every launcher window now showing and to every one shown later
     * (the main window, Running Games, help, wizards): Windows may pin any window of the group.
     * Call on the FX thread; later calls do nothing.
     */
    public static void applyToAllWindows()
    {
        if ( !SystemUtils.IS_OS_WINDOWS || watching ) {
            return;
        }
        watching = true;
        javafx.collections.ObservableList< javafx.stage.Window > windows = javafx.stage.Window.getWindows();
        for ( javafx.stage.Window window : windows ) {
            watch( window );
        }
        windows.addListener( ( javafx.collections.ListChangeListener< javafx.stage.Window > ) change -> {
            while ( change.next() ) {
                for ( javafx.stage.Window window : change.getAddedSubList() ) {
                    watch( window );
                }
            }
        } );
    }

    private static void watch( javafx.stage.Window window )
    {
        if ( !( window instanceof Stage stage ) ) {
            return;   // popups and menus get no taskbar button
        }
        if ( stage.isShowing() ) {
            apply( stage );
        }
        stage.addEventHandler( WindowEvent.WINDOW_SHOWN, event -> apply( stage ) );
    }

    /**
     * Gives the stage's window the relaunch properties once it is showing, and removes them when it
     * hides. Call on the FX thread after the stage is shown; later calls for the same stage are
     * free.
     *
     * @param stage the launcher window
     */
    static void apply( Stage stage )
    {
        if ( !SystemUtils.IS_OS_WINDOWS || stage == null || APPLIED.contains( stage ) ) {
            return;
        }
        String executable = StarterHandshake.launcherExecutable();
        if ( executable == null ) {
            return;   // IDE or raw-jar run: nothing stable to pin.
        }
        HWND hwnd = WindowChromeManager.resolveHwnd( stage );
        if ( hwnd == null ) {
            return;
        }
        if ( write( hwnd, executable ) ) {
            APPLIED.add( stage );
            stage.addEventHandler( WindowEvent.WINDOW_HIDDEN, new javafx.event.EventHandler<>()
            {
                @Override
                public void handle( WindowEvent event )
                {
                    stage.removeEventHandler( WindowEvent.WINDOW_HIDDEN, this );
                    APPLIED.remove( stage );
                    write( hwnd, null );
                }
            } );
        }
    }

    /**
     * Sets (or, with a {@code null} executable, removes) the four properties on the window.
     *
     * @return whether every value was written
     */
    private static boolean write( HWND hwnd, String executable )
    {
        Pointer store = null;
        try {
            PointerByReference ppv = new PointerByReference();
            Function get = Function.getFunction( "shell32", "SHGetPropertyStoreForWindow", Function.ALT_CONVENTION );
            int hr = ( Integer ) get.invoke( Integer.class, new Object[] { hwnd, iidRef(), ppv } );
            if ( hr != 0 ) {
                throw new IllegalStateException( "SHGetPropertyStoreForWindow 0x" + Integer.toHexString( hr ) );
            }
            store = ppv.getValue();
            // Windows reads the ID first; the relaunch values are ignored on a window without one.
            boolean ok = set( store, PID_ID, executable == null ? null : appUserModelId() );
            ok &= set( store, PID_RELAUNCH_COMMAND, executable == null ? null : relaunchCommand( executable ) );
            ok &= set( store, PID_RELAUNCH_DISPLAY_NAME,
                       executable == null ? null : LauncherConstants.LAUNCHER_APPLICATION_NAME );
            ok &= set( store, PID_RELAUNCH_ICON, executable == null ? null : executable + ",0" );
            return ok;
        }
        catch ( Throwable t ) {
            Logger.logWarningSilent( LocalizationManager.format( "log.relaunchProperties.failed", String.valueOf( t ) ) );
            return false;
        }
        finally {
            if ( store != null ) {
                methodAt( store, IUNKNOWN_RELEASE ).invoke( Integer.class, new Object[] { store } );
            }
        }
    }

    /** {@code IPropertyStore::SetValue} with a string, or {@code VT_EMPTY} to remove the value. */
    private static boolean set( Pointer store, int pid, String value )
    {
        // PROPERTYKEY: GUID (16 bytes) + DWORD pid, padded to 24.
        Memory key = new Memory( 24 );
        key.clear();
        key.setInt( 0, PKEY_APP_USER_MODEL.Data1 );
        key.setShort( 4, PKEY_APP_USER_MODEL.Data2 );
        key.setShort( 6, PKEY_APP_USER_MODEL.Data3 );
        key.write( 8, PKEY_APP_USER_MODEL.Data4, 0, 8 );
        key.setInt( 16, pid );
        // PROPVARIANT: vt at 0, string pointer at 8 (x64), 24 bytes.
        Memory variant = new Memory( 24 );
        variant.clear();
        Memory text = null;
        if ( value != null ) {
            text = new Memory( ( value.length() + 1 ) * 2L );
            text.setWideString( 0, value );
            variant.setShort( 0, VT_LPWSTR );
            variant.setPointer( 8, text );
        }
        else {
            variant.setShort( 0, VT_EMPTY );
        }
        int hr = ( Integer ) methodAt( store, IPS_SET_VALUE ).invoke( Integer.class, new Object[] { store, key, variant } );
        // Only the variant points at the string; keep it alive until SetValue has copied it.
        java.lang.ref.Reference.reachabilityFence( text );
        if ( hr != 0 ) {
            Logger.logWarningSilent( LocalizationManager.format( "log.relaunchProperties.failed",
                                                                 "IPropertyStore::SetValue(" + pid + ") 0x"
                                                                 + Integer.toHexString( hr ) ) );
        }
        return hr == 0;
    }

    private static Guid.IID.ByReference iidRef()
    {
        Guid.IID.ByReference ref = new Guid.IID.ByReference();
        ref.Data1 = IID_IPROPERTY_STORE.Data1;
        ref.Data2 = IID_IPROPERTY_STORE.Data2;
        ref.Data3 = IID_IPROPERTY_STORE.Data3;
        ref.Data4 = IID_IPROPERTY_STORE.Data4.clone();
        ref.write();
        return ref;
    }

    private static Function methodAt( Pointer iface, int index )
    {
        Pointer vtable = iface.getPointer( 0 );
        return Function.getFunction( vtable.getPointer( ( long ) index * Native.POINTER_SIZE ) );
    }
}
