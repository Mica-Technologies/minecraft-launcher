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
import com.micatechnologies.minecraft.launcher.game.modpack.GameModPack;
import com.sun.jna.Function;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.Guid;
import com.sun.jna.platform.win32.Ole32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.PointerByReference;
import org.apache.commons.lang3.SystemUtils;

import java.util.List;

/**
 * Windows jump-list population via {@code ICustomDestinationList} COM.
 *
 * <p>Builds a "Recent Modpacks" category in the launcher's taskbar / Start-
 * menu right-click jump list, one entry per pack returned by
 * {@link RecentPacks#getRecent(int)}. Each entry shells the launcher exe
 * with a {@code mmcl://play?name=<pack>} URL — the single-instance lock
 * forwards it to the running launcher (or cold-starts and dispatches via
 * {@code LauncherUriHandler}).</p>
 *
 * <p>Pure JNA — no native code added to the build. The COM dance is:</p>
 *
 * <ol>
 *   <li>{@code CoInitializeEx} the calling thread.</li>
 *   <li>{@code CoCreateInstance} an {@code ICustomDestinationList}.</li>
 *   <li>{@code BeginList} to start a new transaction.</li>
 *   <li>{@code CoCreateInstance} an {@code IObjectCollection}.</li>
 *   <li>For each recent pack:
 *     <ol>
 *       <li>{@code CoCreateInstance} an {@code IShellLinkW}.</li>
 *       <li>{@code SetPath} + {@code SetArguments} + {@code SetDescription}
 *           + {@code SetIconLocation}.</li>
 *       <li>{@code QueryInterface} for {@code IPropertyStore} +
 *           {@code SetValue(PKEY_Title, ...)} so the entry's visible
 *           label is the pack's friendly name rather than the exe path.</li>
 *       <li>{@code IObjectCollection::AddObject(IShellLinkW *)}.</li>
 *     </ol>
 *   </li>
 *   <li>{@code QueryInterface} the collection for {@code IObjectArray}.</li>
 *   <li>{@code AppendCategory("Recent Modpacks", IObjectArray *)}.</li>
 *   <li>{@code CommitList}.</li>
 *   <li>Release every interface pointer; {@code CoUninitialize}.</li>
 * </ol>
 *
 * <p>Any HRESULT failure is logged at warning level and the partial state
 * is released cleanly — the user's existing jump list (if any) survives
 * because {@code CommitList} only takes effect when the whole transaction
 * succeeds.</p>
 *
 * @since 2026.5
 */
final class WindowsJumpList
{
    /**
     * Private constructor to prevent instantiation of this utility class.
     */
    private WindowsJumpList() { /* static-only */ }

    // =========================================================================================
    //  GUIDs
    // =========================================================================================

    /**
     * CLSID for {@code ICustomDestinationList}.
     */
    private static final Guid.CLSID CLSID_DESTINATION_LIST =
            new Guid.CLSID( "{77f10cf0-3db5-4966-b520-b7c54fd35ed6}" );

    /**
     * IID for {@code ICustomDestinationList}.
     */
    private static final Guid.IID IID_ICUSTOM_DESTINATION_LIST =
            new Guid.IID( "{6332debf-87b5-4670-90c0-5e57b408a49e}" );

    /**
     * CLSID for {@code IObjectCollection}.
     */
    private static final Guid.CLSID CLSID_ENUMERABLE_OBJECT_COLLECTION =
            new Guid.CLSID( "{2d3468c1-36a7-43b6-ac24-d3f02fd9607a}" );

    /**
     * IID for {@code IObjectCollection}.
     */
    private static final Guid.IID IID_IOBJECT_COLLECTION =
            new Guid.IID( "{5632b1a4-e38a-400a-928a-d4cd63230295}" );

    /**
     * IID for {@code IObjectArray}.
     */
    private static final Guid.IID IID_IOBJECT_ARRAY =
            new Guid.IID( "{92ca9dcd-5622-4bba-a805-5e9f541bd8c9}" );

    /**
     * CLSID for {@code IShellLinkW}.
     */
    private static final Guid.CLSID CLSID_SHELL_LINK =
            new Guid.CLSID( "{00021401-0000-0000-c000-000000000046}" );

    /**
     * IID for {@code IShellLinkW}.
     */
    private static final Guid.IID IID_ISHELL_LINK_W =
            new Guid.IID( "{000214f9-0000-0000-c000-000000000046}" );

    /**
     * IID for {@code IPropertyStore}.
     */
    private static final Guid.IID IID_IPROPERTY_STORE =
            new Guid.IID( "{886d8eeb-8cf2-4446-8d02-cdba1dbdcf99}" );

    /** {@code PKEY_Title} — the property key that controls the visible label
     *  on a jump-list entry. fmtid + pid format per the Property System
     *  reference; verified against {@code propkey.h}. */
    private static final Guid.GUID PKEY_TITLE_FMTID =
            new Guid.GUID( "{F29F85E0-4FF9-1068-AB91-08002B27B3D9}" );

    /**
     * Property ID for {@code PKEY_Title}.
     */
    private static final int PKEY_TITLE_PID = 2;

    /** {@code VARTYPE} value for a wide-string {@code PROPVARIANT}. */
    private static final short VT_LPWSTR = 31;

    // =========================================================================================
    //  Vtable indices
    // =========================================================================================

    /**
     * Index for {@code IUnknown::QueryInterface}.
     */
    private static final int IUNKNOWN_QUERY_INTERFACE = 0;

    /**
     * Index for {@code IUnknown::Release}.
     */
    private static final int IUNKNOWN_RELEASE = 2;

    // ICustomDestinationList : IUnknown
    /**
     * Index for {@code ICustomDestinationList::BeginList}.
     */
    private static final int ICDL_BEGIN_LIST = 4;

    /**
     * Index for {@code ICustomDestinationList::AppendCategory}.
     */
    private static final int ICDL_APPEND_CATEGORY = 5;

    /**
     * Index for {@code ICustomDestinationList::CommitList}.
     */
    private static final int ICDL_COMMIT_LIST = 8;

    /**
     * Index for {@code ICustomDestinationList::AbortList}.
     */
    private static final int ICDL_ABORT_LIST = 11;

    // IObjectCollection : IObjectArray : IUnknown
    /**
     * Index for {@code IObjectCollection::AddObject}.
     */
    private static final int IOC_ADD_OBJECT = 5;

    /**
     * Index for {@code IObjectCollection::Clear}.
     */
    private static final int IOC_CLEAR = 8;

    // IShellLinkW : IUnknown
    /**
     * Index for {@code IShellLinkW::SetDescription}.
     */
    private static final int ISL_SET_DESCRIPTION = 7;

    /**
     * Index for {@code IShellLinkW::SetArguments}.
     */
    private static final int ISL_SET_ARGUMENTS = 11;

    /**
     * Index for {@code IShellLinkW::SetIconLocation}.
     */
    private static final int ISL_SET_ICON_LOCATION = 17;

    /**
     * Index for {@code IShellLinkW::SetPath}.
     */
    private static final int ISL_SET_PATH = 20;

    // IPropertyStore : IUnknown
    /**
     * Index for {@code IPropertyStore::SetValue}.
     */
    private static final int IPS_SET_VALUE = 6;

    /**
     * Index for {@code IPropertyStore::Commit}.
     */
    private static final int IPS_COMMIT = 7;

    // =========================================================================================
    //  Configuration
    // =========================================================================================

    /**
     * Maximum number of recently-played packs surfaced in the jump list.
     * Windows caps the visible category at ~10 entries and starts hiding older
     * ones; 5 matches the Linux {@code .desktop Actions=} count for
     * parity.
     */
    private static final int MAX_PACKS_IN_JUMP_LIST = 5;

    /**
     * Title of the jump list category for recent modpacks.
     */
    private static final String CATEGORY_TITLE = LocalizationManager.get( "jumpList.category.recentModpacks" );

    // =========================================================================================
    //  Entry point
    // =========================================================================================

    /**
     * Rebuilds the launcher's jump list to reflect the current recently-
     * played modpacks. No-op when not running from a jpackage-installed
     * exe (raw JAR / IDE launches have no stable exe path to point links
     * at). Any COM failure is contained — the previous jump list (if any)
     * survives because {@code CommitList} only fires after the full
     * transaction succeeds.
     */
    static void refresh()
    {
        if ( !SystemUtils.IS_OS_WINDOWS ) return;
        if ( LauncherConstants.LAUNCHER_IS_DEV ) return;

        String exePath = System.getProperty( "jpackage.app-path" );
        if ( exePath == null || exePath.isBlank() ) {
            Logger.logDebug( LocalizationManager.get( "log.jumpList.noExePath" ) );
            return;
        }

        List< GameModPack > recent = RecentPacks.getRecent( MAX_PACKS_IN_JUMP_LIST );
        try {
            buildAndCommit( exePath, recent );
        }
        catch ( Throwable t ) {
            Logger.logWarningSilent( LocalizationManager.format( "log.jumpList.refreshThrew",
                                                                 t.getClass().getSimpleName(), t.getMessage() ) );
        }
    }

    // =========================================================================================
    //  COM dance
    // =========================================================================================

    /**
     * Builds and commits the jump list with the given executable path and recent modpacks.
     *
     * @param exePath The path to the launcher executable.
     * @param recent  The list of recently played modpacks.
     */
    private static void buildAndCommit( String exePath, List< GameModPack > recent )
    {
        // Single-thread apartment is sufficient — we don't make concurrent COM
        // calls from this thread and ICustomDestinationList is fine in STA.
        WinNT.HRESULT hr = Ole32.INSTANCE.CoInitializeEx( null, Ole32.COINIT_APARTMENTTHREADED );
        // S_OK = 0, S_FALSE = 1 (already initialized — still OK to use).
        // RPC_E_CHANGED_MODE = 0x80010106 means a different threading model
        // is already in effect; the COM calls below still work, just without
        // our threading preference.
        int hrCode = hr == null ? 0 : hr.intValue();
        boolean weInitialized = hrCode == 0;
        boolean changedMode = hrCode == 0x80010106;
        if ( !weInitialized && hrCode != 1 && !changedMode ) {
            Logger.logWarningSilent( LocalizationManager.format( "log.jumpList.coInitFailed",
                                                                 Integer.toHexString( hrCode ) ) );
            return;
        }

        Pointer cdl = null;
        Pointer collection = null;
        Pointer objArray = null;
        Pointer removed = null;
        try {
            // 1) ICustomDestinationList.
            cdl = coCreate( CLSID_DESTINATION_LIST, IID_ICUSTOM_DESTINATION_LIST );
            if ( cdl == null ) return;

            // 2) BeginList. Returns the max-slot count + an IObjectArray of
            //    items the user removed from previous transactions (we don't
            //    need to honor it here — none of our items are user-pinnable).
            Memory minSlots = new Memory( 4 );
            minSlots.setInt( 0, 0 );
            PointerByReference removedRef = new PointerByReference();
            int hrBegin = invokeHr( cdl, ICDL_BEGIN_LIST, minSlots, asIidRef( IID_IOBJECT_ARRAY ), removedRef );
            removed = removedRef.getValue();
            if ( failed( hrBegin, "BeginList" ) ) return;

            // 3) IObjectCollection.
            collection = coCreate( CLSID_ENUMERABLE_OBJECT_COLLECTION, IID_IOBJECT_COLLECTION );
            if ( collection == null ) {
                abort( cdl );
                return;
            }
            // Clear so the collection starts empty even if Windows handed us
            // a recycled allocation.
            invokeHr( collection, IOC_CLEAR );

            // 4) Per-pack shell links.
            for ( GameModPack pack : recent ) {
                Pointer link = buildShellLink( exePath, pack );
                if ( link == null ) continue;
                try {
                    int hrAdd = invokeHr( collection, IOC_ADD_OBJECT, link );
                    if ( failed( hrAdd, "IObjectCollection::AddObject" ) ) {
                        // continue with the rest — one bad entry shouldn't
                        // tank the whole category.
                    }
                }
                finally {
                    release( link );
                }
            }

            // 5) Cast collection → IObjectArray for AppendCategory.
            objArray = queryInterface( collection, IID_IOBJECT_ARRAY );
            if ( objArray == null ) {
                abort( cdl );
                return;
            }

            // 6) AppendCategory. Empty collections succeed but render
            //    nothing — Windows just skips the category entirely.
            WString categoryW = new WString( CATEGORY_TITLE );
            int hrAppend = invokeHr( cdl, ICDL_APPEND_CATEGORY, categoryW, objArray );
            if ( failed( hrAppend, "AppendCategory" ) ) {
                abort( cdl );
                return;
            }

            // 7) Commit.
            int hrCommit = invokeHr( cdl, ICDL_COMMIT_LIST );
            failed( hrCommit, "CommitList" );
        }
        finally {
            release( removed );
            release( objArray );
            release( collection );
            release( cdl );
            if ( weInitialized ) {
                Ole32.INSTANCE.CoUninitialize();
            }
        }
    }

    /**
     * Builds + populates a single IShellLinkW for one pack. Returns an
     * AddRef'd pointer the caller must {@link #release(Pointer)}.
     *
     * @param exePath The path to the launcher executable.
     * @param pack    The modpack for which to create the shell link.
     * @return A pointer to the created shell link, or null if an error occurred.
     */
    private static Pointer buildShellLink( String exePath, GameModPack pack )
    {
        String packName = pack.getPackName();
        if ( packName == null || packName.isBlank() ) return null;
        String display = pack.getFriendlyName();
        if ( display == null || display.isBlank() ) display = packName;

        Pointer link = coCreate( CLSID_SHELL_LINK, IID_ISHELL_LINK_W );
        if ( link == null ) return null;
        Pointer propStore = null;
        try {
            int hr;
            hr = invokeHr( link, ISL_SET_PATH, new WString( exePath ) );
            if ( failed( hr, "IShellLinkW::SetPath" ) ) return releaseAndNull( link );

            // mmcl://play?name=<urlencoded>. The launcher's single-instance
            // IPC forwards URI args to the running instance, which dispatches
            // through LauncherUriHandler.handle.
            String args = "mmcl://play?name=" + urlEncode( packName );
            hr = invokeHr( link, ISL_SET_ARGUMENTS, new WString( args ) );
            if ( failed( hr, "IShellLinkW::SetArguments" ) ) return releaseAndNull( link );

            // Tooltip text — shown on hover over the jump-list entry.
            String tooltip = LocalizationManager.format( "jumpList.tooltip.play", display );
            hr = invokeHr( link, ISL_SET_DESCRIPTION, new WString( tooltip ) );
            if ( failed( hr, "IShellLinkW::SetDescription" ) ) return releaseAndNull( link );

            // Icon = the launcher exe itself (icon index 0). Per-pack icons
            // would be a nice future polish but require an .ico path per
            // pack — out of scope.
            hr = invokeHr( link, ISL_SET_ICON_LOCATION, new WString( exePath ), 0 );
            if ( failed( hr, "IShellLinkW::SetIconLocation" ) ) return releaseAndNull( link );

            // PKEY_Title — the visible label on the jump-list entry. Set
            // via IPropertyStore on the same shell link object.
            propStore = queryInterface( link, IID_IPROPERTY_STORE );
            if ( propStore != null ) {
                if ( setTitleViaPropertyStore( propStore, display ) ) {
                    invokeHr( propStore, IPS_COMMIT );
                }
            }
            return link;
        }
        finally {
            release( propStore );
        }
    }

    // =========================================================================================
    //  PROPVARIANT for PKEY_Title
    // =========================================================================================

    /**
     * Allocates a PROPERTYKEY + PROPVARIANT(VT_LPWSTR) on the native heap,
     * calls {@code IPropertyStore::SetValue}, then frees the memory. Returns
     * true on HRESULT-success.
     *
     * <p>The PROPVARIANT struct layout for VT_LPWSTR on x64:</p>
     * <pre>
     *   offset  0  short  vt  (== 31 = VT_LPWSTR)
     *   offset  2  short  wReserved1
     *   offset  4  short  wReserved2
     *   offset  6  short  wReserved3
     *   offset  8  ptr    pwszVal  (LPWSTR)
     * </pre>
     * <p>Total 24 bytes (8 for header padding + 16 for the union, of which
     * only the first 8 bytes hold the string pointer here).</p>
     *
     * @param propStore The property store interface.
     * @param title     The title to set via the property store.
     * @return true if the operation was successful, false otherwise.
     */
    private static boolean setTitleViaPropertyStore( Pointer propStore, String title )
    {
        Memory keyMem = null;
        Memory pvMem = null;
        Memory wsz = null;
        try {
            // PROPERTYKEY = GUID (16 bytes) + DWORD (4 bytes) = 20 bytes,
            // typically with 4 bytes padding to 24 for alignment.
            //
            // Lay out the GUID field-by-field rather than via toByteArray() —
            // JNA's Structure layout puts Data1/2/3 in native (little-endian)
            // byte order while toByteArray serialises into a buffer whose
            // byte-order setting can subtly differ depending on the JNA
            // version. Writing the four fields with setInt/setShort uses
            // native byte order on x64 Windows (LE), matching what COM
            // expects.
            keyMem = new Memory( 24 );
            keyMem.clear();
            keyMem.setInt(   0, PKEY_TITLE_FMTID.Data1 );
            keyMem.setShort( 4, PKEY_TITLE_FMTID.Data2 );
            keyMem.setShort( 6, PKEY_TITLE_FMTID.Data3 );
            keyMem.write(    8, PKEY_TITLE_FMTID.Data4, 0, 8 );
            keyMem.setInt(  16, PKEY_TITLE_PID );

            // PROPVARIANT(VT_LPWSTR, "title")
            pvMem = new Memory( 24 );
            pvMem.clear();
            pvMem.setShort( 0, VT_LPWSTR );
            // Wide string in native memory (UTF-16LE, null-terminated)
            wsz = new Memory( ( title.length() + 1 ) * 2L );
            wsz.setWideString( 0, title );
            // pwszVal pointer at offset 8
            pvMem.setPointer( 8, wsz );

            int hr = invokeHr( propStore, IPS_SET_VALUE, keyMem, pvMem );
            return !failed( hr, "IPropertyStore::SetValue(PKEY_Title)" );
        }
        catch ( Throwable t ) {
            Logger.logWarningSilent( LocalizationManager.format( "log.jumpList.propertyStoreFailed",
                                                                 t.getClass().getSimpleName() ) );
            return false;
        }
        finally {
            // JNA's Memory is auto-freed when GC'd; explicit close not
            // required (Memory.close was added in 5.10 but we keep this
            // simple).
        }
    }

    // =========================================================================================
    //  Helpers
    // =========================================================================================

    /**
     * {@code CoCreateInstance(CLSCTX_INPROC_SERVER)}. Returns the raw interface
     * pointer on success, or {@code null} on failure (with a warning logged).
     *
     * @param clsid The CLSID of the COM object to create.
     * @param iid   The IID of the interface to query for.
     * @return A pointer to the created COM object, or null if an error occurred.
     */
    private static Pointer coCreate( Guid.CLSID clsid, Guid.IID iid )
    {
        final int CLSCTX_INPROC_SERVER = 1;
        PointerByReference ppv = new PointerByReference();
        WinNT.HRESULT hr = Ole32.INSTANCE.CoCreateInstance( clsid, null, CLSCTX_INPROC_SERVER,
                                                            iid, ppv );
        if ( hr == null || hr.intValue() != 0 ) {
            Logger.logWarningSilent( LocalizationManager.format( "log.jumpList.coCreateFailed",
                                                                 clsid.toGuidString(),
                                                                 ( hr == null ? "null" : "0x" + Integer.toHexString( hr.intValue() ) ) ) );
            return null;
        }
        return ppv.getValue();
    }

    /**
     * {@code IUnknown::QueryInterface}. Returns the resolved interface pointer
     * or {@code null} on failure.
     *
     * @param iface The COM object to query.
     * @param iid   The IID of the interface to query for.
     * @return A pointer to the queried interface, or null if an error occurred.
     */
    private static Pointer queryInterface( Pointer iface, Guid.IID iid )
    {
        if ( iface == null ) return null;
        PointerByReference ppv = new PointerByReference();
        int hr = invokeHr( iface, IUNKNOWN_QUERY_INTERFACE, asIidRef( iid ), ppv );
        if ( hr != 0 ) {
            Logger.logWarningSilent( LocalizationManager.format( "log.jumpList.queryInterfaceFailed",
                                                                 iid.toGuidString(), Integer.toHexString( hr ) ) );
            return null;
        }
        return ppv.getValue();
    }

    /**
     * Serialize an {@link Guid.IID} into a JNA-managed native struct + return
     * it as a {@code ByReference}. JNA passes a Structure.ByReference as a
     * pointer to its native memory, which is exactly what COM expects for
     * a {@code REFIID} ({@code const GUID *}).
     *
     *  <p>We earlier tried hand-rolling this via Memory + {@code toByteArray}
     *  but that hit {@code E_NOINTERFACE} on {@code BeginList} — the field-
     *  layout produced by toByteArray didn't quite match what COM's QI
     *  expects in some edge of the alignment. Mirroring the existing
     *  {@code WinRt.asIidRef} pattern is the safe path.</p>
     *
     * @param iid The IID to serialize.
     * @return A reference to the serialized IID.
     */
    private static Guid.IID.ByReference asIidRef( Guid.IID iid )
    {
        Guid.IID.ByReference ref = new Guid.IID.ByReference();
        ref.Data1 = iid.Data1;
        ref.Data2 = iid.Data2;
        ref.Data3 = iid.Data3;
        ref.Data4 = iid.Data4.clone();
        ref.write();
        return ref;
    }

    /**
     * {@code IUnknown::Release}. Decrements the ref count; the object frees
     * itself at refcount 0. No-op when {@code iface} is null.
     *
     * @param iface The COM object to release.
     */
    private static void release( Pointer iface )
    {
        if ( iface == null || Pointer.nativeValue( iface ) == 0L ) return;
        try {
            invokeUlong( iface, IUNKNOWN_RELEASE );
        }
        catch ( Throwable ignored ) {
            // Shutdown / partial-state path; nothing we can do.
        }
    }

    /**
     * Releases the COM object and returns null.
     *
     * @param iface The COM object to release.
     * @return Always returns null.
     */
    private static Pointer releaseAndNull( Pointer iface )
    {
        release( iface );
        return null;
    }

    /**
     * Aborts the current COM transaction.
     *
     * @param cdl The ICustomDestinationList interface pointer.
     */
    private static void abort( Pointer cdl )
    {
        try {
            invokeHr( cdl, ICDL_ABORT_LIST );
        }
        catch ( Throwable ignored ) {
        }
    }

    /**
     * Checks if the HRESULT indicates a failure.
     *
     * @param hr  The HRESULT value to check.
     * @param where The location where the error occurred.
     * @return true if the HRESULT indicates a failure, false otherwise.
     */
    private static boolean failed( int hr, String where )
    {
        if ( hr == 0 ) return false;
        Logger.logWarningSilent( LocalizationManager.format( "log.jumpList.hresultFailure",
                                                             where, Integer.toHexString( hr ) ) );
        return true;
    }

    /**
     * Invokes a COM method that returns an HRESULT.
     *
     * @param iface The COM object to invoke the method on.
     * @param vtableIndex The index of the method in the vtable.
     * @param args The arguments to pass to the method.
     * @return The HRESULT value returned by the method.
     */
    private static int invokeHr( Pointer iface, int vtableIndex, Object... args )
    {
        Function fn = methodAt( iface, vtableIndex );
        Object[] all = new Object[ args.length + 1 ];
        all[ 0 ] = iface;
        System.arraycopy( args, 0, all, 1, args.length );
        Object res = fn.invoke( Integer.class, all );
        return ( (Integer) res ).intValue();
    }

    /**
     * Invokes a COM method that returns an unsigned long.
     *
     * @param iface The COM object to invoke the method on.
     * @param vtableIndex The index of the method in the vtable.
     * @param args The arguments to pass to the method.
     */
    private static void invokeUlong( Pointer iface, int vtableIndex, Object... args )
    {
        Function fn = methodAt( iface, vtableIndex );
        Object[] all = new Object[ args.length + 1 ];
        all[ 0 ] = iface;
        System.arraycopy( args, 0, all, 1, args.length );
        fn.invoke( Integer.class, all );
    }

    /**
     * Retrieves the function pointer for a COM method at the specified index.
     *
     * @param iface The COM object to retrieve the method from.
     * @param index The index of the method in the vtable.
     * @return The function pointer for the specified method.
     */
    private static Function methodAt( Pointer iface, int index )
    {
        // COM interface pointer → struct { vtable*, ... }. Read vtable at
        // offset 0, then the function pointer at index * sizeof(ptr).
        Pointer vtable = iface.getPointer( 0 );
        Pointer fnPtr = vtable.getPointer( (long) index * Native.POINTER_SIZE );
        return Function.getFunction( fnPtr );
    }

    /**
     * Minimal percent-encoder for the mmcl:// query value. Mirrors the
     * one used by SchemeRegistrar's .desktop writer.
     *
     * @param value The string to encode.
     * @return The encoded string.
     */
    private static String urlEncode( String value )
    {
        StringBuilder sb = new StringBuilder( value.length() );
        for ( int i = 0; i < value.length(); i++ ) {
            char c = value.charAt( i );
            if ( ( c >= 'a' && c <= 'z' ) || ( c >= 'A' && c <= 'Z' )
                    || ( c >= '0' && c <= '9' )
                    || c == '-' || c == '_' || c == '.' || c == '~' ) {
                sb.append( c );
            }
            else {
                byte[] bytes = String.valueOf( c ).getBytes( java.nio.charset.StandardCharsets.UTF_8 );
                for ( byte b : bytes ) {
                    sb.append( '%' );
                    sb.append( String.format( "%02X", b & 0xFF ) );
                }
            }
        }
        return sb.toString();
    }
}
