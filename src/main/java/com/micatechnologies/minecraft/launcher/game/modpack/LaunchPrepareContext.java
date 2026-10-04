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
 * The settings one pack's prepare run (a launch, or a "verify this pack" run) applies to the
 * files it checks: how hard to verify them, and where to record re-downloads.
 *
 * <p>Several packs can prepare at once, and their downloads share one thread pool, so this
 * can't be global or thread-local state. The launcher puts it on the pack being prepared
 * ({@link GameModPackMetadata#getPrepareContext()}) for the length of the run, and every
 * {@link ManagedGameFile} check takes it as an argument: the pack's loader, manifests and
 * file sync pass their pack's context to the files they update.</p>
 *
 * <p>Immutable. {@link #NONE} (full verify, nothing recorded) applies outside a prepare
 * run.</p>
 *
 * @since 2026.10
 */
public final class LaunchPrepareContext
{
    /**
     * Outside any prepare run: hash every file, record nothing.
     *
     * @since 2026.10
     */
    public static final LaunchPrepareContext NONE = new LaunchPrepareContext( LaunchVerifyMode.FULL, null, -1 );

    private final LaunchVerifyMode verifyMode;
    private final String           auditPackRoot;
    private final int              auditLaunchId;

    private LaunchPrepareContext( LaunchVerifyMode verifyMode, String auditPackRoot, int auditLaunchId )
    {
        this.verifyMode = verifyMode != null ? verifyMode : LaunchVerifyMode.FULL;
        this.auditPackRoot = auditPackRoot;
        this.auditLaunchId = auditLaunchId;
    }

    /**
     * The context for a launch: verify in {@code verifyMode}, and record re-downloads of
     * existing files in the pack's audit log under {@code launchId}.
     *
     * @param verifyMode how hard to verify; {@code null} means {@link LaunchVerifyMode#FULL}
     * @param packRoot   the pack's root folder, where its audit log lives; {@code null}
     *                   records nothing
     * @param launchId   the launch number to record re-downloads under
     *
     * @return the context
     *
     * @since 2026.10
     */
    public static LaunchPrepareContext forLaunch( LaunchVerifyMode verifyMode, String packRoot, int launchId )
    {
        return new LaunchPrepareContext( verifyMode, packRoot, launchId );
    }

    /**
     * The context for a "verify this pack" run: hash every file, record nothing (it isn't a
     * launch, so it has no launch number to file re-downloads under).
     *
     * @return the context
     *
     * @since 2026.10
     */
    public static LaunchPrepareContext fullVerify()
    {
        return NONE;
    }

    /**
     * @return how hard to verify files; never {@code null}
     *
     * @since 2026.10
     */
    public LaunchVerifyMode verifyMode()
    {
        return verifyMode;
    }

    /**
     * @return whether re-downloads of existing files are recorded in an audit log
     *
     * @since 2026.10
     */
    public boolean isAuditing()
    {
        return auditPackRoot != null;
    }

    /**
     * @return the pack root whose audit log receives re-downloads, or {@code null}
     *
     * @since 2026.10
     */
    public String auditPackRoot()
    {
        return auditPackRoot;
    }

    /**
     * @return the launch number re-downloads are recorded under, or {@code -1}
     *
     * @since 2026.10
     */
    public int auditLaunchId()
    {
        return auditLaunchId;
    }

    /**
     * Records a re-download of an existing file in this context's audit log. No-op when
     * {@link #isAuditing()} is {@code false}. Never throws.
     *
     * @param fullLocalPath the file's full local path
     * @param oldHash       hash of the file before the download (may be null)
     * @param newHash       hash of the file after the download (may be null)
     * @param expectedHash  the manifest's declared hash (may be null)
     * @param algo          the hash algorithm used (may be null)
     *
     * @since 2026.10
     */
    public void recordRedownload( String fullLocalPath, String oldHash, String newHash,
                                  String expectedHash, String algo )
    {
        ModPackAuditLog.recordRedownload( auditPackRoot, auditLaunchId, fullLocalPath, oldHash, newHash,
                                          expectedHash, algo );
    }
}
