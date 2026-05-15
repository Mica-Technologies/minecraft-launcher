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

package com.micatechnologies.minecraft.launcher.gui;

import com.micatechnologies.minecraft.launcher.consts.ModPackConstants;
import com.micatechnologies.minecraft.launcher.game.modpack.GameModPack;
import javafx.scene.image.Image;

import java.io.File;

/**
 * Shared logo / background image resolution for the launcher's card-grid screens.
 * Both {@link MCLauncherMainGui} (home hero cards) and {@link MCLauncherGameLibraryGui}
 * (Browse cards) used to carry their own copies of "look for the cached pack image
 * on disk, fall back to the bundled default URL when absent" — same code, same FX-
 * thread-safety constraints, but with subtle differences in how missing-file vs.
 * not-yet-cached was handled.
 *
 * <p>All methods are FX-thread-safe — the {@link Image} constructors used here are
 * called with {@code backgroundLoading=true} so decoding happens off the FX thread.
 * Crucially, none of them call {@link GameModPack#getPackLogoFilepath} or
 * {@link GameModPack#getPackBackgroundFilepath} (which trigger a synchronous
 * network-backed {@code cacheImages} download); they all go through the
 * {@code *Raw} accessors which are side-effect-free. Callers that want a missing
 * image cached should kick {@link GameModPack#cacheImages} on a worker thread and
 * re-call the resolver once the file lands.</p>
 *
 * <p>For transparency / edge detection on the resolved Image, use
 * {@link LogoTransparencyDetector} — kept separate because it operates on the
 * already-loaded Image rather than on the path resolution.</p>
 *
 * @since 2026.5
 */
public final class ModpackImageResolver
{
    private ModpackImageResolver() { /* static-only */ }

    /**
     * Returns the pack's logo as a JavaFX {@link Image} loaded from the on-disk
     * cache. Returns {@code null} when the pack doesn't have a logo path or the
     * file isn't yet on disk — caller can decide whether to fall back to a
     * placeholder, the default URL, or skip rendering entirely.
     *
     * <p>Uses {@link GameModPack#getPackLogoFilepathRaw()} (side-effect-free)
     * rather than {@link GameModPack#getPackLogoFilepath()} so the FX thread
     * never blocks on a synchronous cache download.</p>
     */
    public static Image resolveLogoFromDisk( GameModPack pack ) {
        if ( pack == null ) return null;
        try {
            String path = pack.getPackLogoFilepathRaw();
            if ( path != null ) {
                File f = new File( path );
                if ( f.exists() ) return new Image( f.toURI().toString(), true );
            }
        }
        catch ( Exception ignored ) { /* fall through to null */ }
        return null;
    }

    /**
     * Same as {@link #resolveLogoFromDisk(GameModPack)} but falls back to the
     * bundled default-logo URL when the on-disk cache is empty. Used by callers
     * that want SOME image to render even on cold start (the home hero cards)
     * vs. the Browse cards which prefer their own placeholder factory for
     * missing artwork.
     */
    public static Image resolveLogoOrDefault( GameModPack pack ) {
        Image fromDisk = resolveLogoFromDisk( pack );
        if ( fromDisk != null ) return fromDisk;
        try {
            return new Image( ModPackConstants.MODPACK_DEFAULT_LOGO_URL, true );
        }
        catch ( Exception ignored ) {
            return null;
        }
    }

    /**
     * Returns the pack's background image as a {@code file:} URL string, or
     * {@code null} when the pack ships no custom background OR the cached file
     * doesn't exist yet on disk. Returning a String (vs. an Image) lets the
     * caller plug the URL straight into a CSS {@code -fx-background-image}
     * style or build an Image with its own size hints, since the card-grid
     * code consumes the value as a CSS string.
     *
     * <p>{@link GameModPack#hasCustomBackground()} is the canonical "does this
     * pack ship its own image" signal. The cached file at
     * {@code getPackBackgroundFilepath()} exists for default-image packs too
     * (the environment downloads {@link ModPackConstants#MODPACK_DEFAULT_BG_URL}
     * into a local cache), so checking just file existence would wrongly point
     * at the cached-bundled-default and skip the procedural-background path —
     * the {@code hasCustomBackground} gate stops that.</p>
     */
    public static String resolveBackgroundUrlFromDisk( GameModPack pack ) {
        if ( pack == null ) return null;
        try {
            if ( !pack.hasCustomBackground() ) {
                return null;
            }
            String path = pack.getPackBackgroundFilepathRaw();
            if ( path != null ) {
                File f = new File( path );
                if ( f.exists() && f.length() > 0 ) return f.toURI().toString();
            }
        }
        catch ( Exception ignored ) { /* fall through to null */ }
        return null;
    }
}
