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

package com.micatechnologies.minecraft.launcher.game.modpack;

/**
 * Strategy for verifying a {@link ManagedGameFile} during pre-launch.
 *
 * <p>The pre-launch path historically only did one thing: SHA-hash every mod,
 * library, and asset against the manifest, then re-download anything that
 * didn't match. That's safe but unconditional, and a 500-mod warm-cache
 * install pays several seconds of disk-bound hashing on every Play click for
 * data that hasn't changed since the last successful launch.</p>
 *
 * <p>The two modes split that work apart:</p>
 *
 * <ul>
 *   <li>{@link #FULL} — the historical behaviour. Every file gets its hash
 *       computed and compared to the manifest. Slow but never wrong.</li>
 *   <li>{@link #FAST_PATH} — skip hashing; just check that each file exists
 *       on disk. A missing file still falls through to the download path
 *       (so a pack staying playable through a manual mod delete still works),
 *       but unchanged content is accepted on existence alone.</li>
 * </ul>
 *
 * <p>The mode is chosen per-launch by {@link VerifyState#decideMode} based on
 * the pack's verify-state sidecar, the manifest content hash, a TTL, the
 * per-pack "Verify every launch" toggle, and a global "force next verify"
 * flag. If anything looks off (manifest changed, TTL elapsed, no sidecar
 * yet, user opted into always-verify), the launch falls back to {@link #FULL}
 * — fast-path is strictly an optimization for the steady-state case.</p>
 *
 * @since 2026.3
 */
public enum LaunchVerifyMode
{
    /** Hash every file against the manifest. Always safe, never skipped. */
    FULL,

    /** Existence + size sanity check only. Used when the manifest hasn't
     *  changed and a recent full verify succeeded. */
    FAST_PATH
}
