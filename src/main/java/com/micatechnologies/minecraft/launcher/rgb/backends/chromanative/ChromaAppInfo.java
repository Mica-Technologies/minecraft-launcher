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

package com.micatechnologies.minecraft.launcher.rgb.backends.chromanative;

import com.sun.jna.Memory;
import com.sun.jna.Pointer;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Marshals the Razer SDK's {@code ChromaSDK::APPINFOTYPE} struct so we
 * can pass it to {@link RzChromaSdkLibrary#InitSDK}.
 *
 * <p>The C++ definition is:</p>
 *
 * <pre>{@code
 * typedef struct APPINFOTYPE {
 *     wchar_t Title[256];
 *     wchar_t Description[1024];
 *     APPAUTHORTYPE Author;          // Name[256], Contact[256]
 *     DWORD SupportedDevice;         // bitfield of DEVICE_* flags
 *     DWORD Category;                // 1=application, 2=game
 * } APPINFOTYPE;
 * }</pre>
 *
 * <p>All wide-char fields are zero-padded UTF-16LE up to the field size.
 * Synapse displays Title + Author.Name in its Chroma Apps list — that's
 * how the user can confirm the launcher actually registered.</p>
 *
 * <h3>SupportedDevice bitfield</h3>
 *
 * <p>Razer's headers define one bit per device family:</p>
 * <ul>
 *   <li>0x01 — Keyboard</li>
 *   <li>0x02 — Mouse</li>
 *   <li>0x04 — Headset</li>
 *   <li>0x08 — Mousepad</li>
 *   <li>0x10 — Keypad</li>
 *   <li>0x20 — ChromaLink</li>
 * </ul>
 *
 * <p>We declare every family so Synapse accepts effect creation calls
 * for whichever device categories the user actually has connected. Bits
 * for missing devices are harmless — Synapse silently no-ops effects
 * for unconnected families.</p>
 *
 * @since 2026.5
 */
final class ChromaAppInfo
{
    static final int DEVICE_KEYBOARD    = 0x01;
    static final int DEVICE_MOUSE       = 0x02;
    static final int DEVICE_HEADSET     = 0x04;
    static final int DEVICE_MOUSEPAD    = 0x08;
    static final int DEVICE_KEYPAD      = 0x10;
    static final int DEVICE_CHROMALINK  = 0x20;

    static final int CATEGORY_APPLICATION = 1;
    static final int CATEGORY_GAME        = 2;

    // Struct field sizes (in wchar_t units, which are 2 bytes UTF-16LE
    // on Windows). Hard-coded against Razer's published header values —
    // exceeding any field's length silently truncates Synapse's display.
    private static final int TITLE_CHARS       = 256;
    private static final int DESCRIPTION_CHARS = 1024;
    private static final int NAME_CHARS        = 256;
    private static final int CONTACT_CHARS     = 256;

    private static final int BYTES_PER_WCHAR = 2;

    private ChromaAppInfo() { /* static-only */ }

    /**
     * Build a heap-allocated {@link Memory} block laid out as the
     * native {@code APPINFOTYPE} struct. The returned memory must be
     * kept alive (referenced) until {@code InitSDK} returns — JNA
     * doesn't track ownership through Pointer params.
     *
     * @param title             shown in Synapse's connected-apps list
     * @param description       displayed alongside the title
     * @param authorName        author block: name
     * @param authorContact     author block: contact URL / email
     * @param supportedDevices  OR of {@code DEVICE_*} flags
     * @param category          {@link #CATEGORY_APPLICATION} or
     *                          {@link #CATEGORY_GAME}
     */
    static Memory build( String title, String description,
                          String authorName, String authorContact,
                          int supportedDevices, int category )
    {
        int titleBytes   = TITLE_CHARS       * BYTES_PER_WCHAR;
        int descBytes    = DESCRIPTION_CHARS * BYTES_PER_WCHAR;
        int nameBytes    = NAME_CHARS        * BYTES_PER_WCHAR;
        int contactBytes = CONTACT_CHARS     * BYTES_PER_WCHAR;
        // Total = strings + 2 DWORDs (4 bytes each).
        int totalBytes = titleBytes + descBytes + nameBytes + contactBytes + 4 + 4;

        Memory mem = new Memory( totalBytes );
        mem.clear(); // ensures unfilled tail bytes stay zeroed

        long offset = 0;
        writeWideStr( mem, offset, title,         titleBytes );  offset += titleBytes;
        writeWideStr( mem, offset, description,   descBytes );   offset += descBytes;
        writeWideStr( mem, offset, authorName,    nameBytes );   offset += nameBytes;
        writeWideStr( mem, offset, authorContact, contactBytes );offset += contactBytes;
        mem.setInt( offset, supportedDevices );                  offset += 4;
        mem.setInt( offset, category );

        return mem;
    }

    /** Encodes {@code s} as UTF-16LE into {@code mem} at the given
     *  offset, capped at {@code byteCapacity}. Surplus capacity is
     *  zero-filled (which serves as the wchar_t null terminator). */
    private static void writeWideStr( Pointer mem, long offset,
                                       String s, int byteCapacity )
    {
        if ( s == null ) s = "";
        byte[] bytes = s.getBytes( StandardCharsets.UTF_16LE );
        // Reserve at least 2 bytes for a null terminator at the end.
        int copyLen = Math.min( bytes.length, byteCapacity - 2 );
        if ( copyLen > 0 ) {
            mem.write( offset, Arrays.copyOf( bytes, copyLen ), 0, copyLen );
        }
        // Trailing bytes were already zero from clear(); the wchar_t
        // null terminator is implicit.
    }
}
