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

import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.property.ReadOnlyDoubleWrapper;
import javafx.scene.Parent;
import javafx.scene.Scene;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;

/**
 * The launcher's interface scale (Settings › Appearance › Interface scale): 50 % to 150 %, 100 %
 * being the layout as designed. Every window's content sits in a {@link ScaledRoot}, which zooms
 * it by {@link #scaleProperty()}; changing the scale applies at once, no restart.
 *
 * <p>Popups (tooltips, menus, dropdown lists) are separate windows outside that zoom, so at a
 * scale other than 100 % each scene also gets a small generated stylesheet ({@link
 * #install(Scene)}) that scales their text and padding to match. Popups take their owner scene's
 * stylesheets, and the rules are scoped under {@code .root} to outrank the theme's own.</p>
 *
 * @since 2026.10
 */
public final class UiScale
{
    /** The offered scales, in percent. */
    public static final int[] PERCENTS = { 50, 75, 100, 125, 150 };

    /** The default scale, in percent: the layout as designed. */
    public static final int DEFAULT_PERCENT = 100;

    private static final ReadOnlyDoubleWrapper SCALE = new ReadOnlyDoubleWrapper( 1.0 );

    private UiScale()
    {
    }

    /** @return the current scale (1.0 = 100 %) */
    public static double get()
    {
        return SCALE.get();
    }

    /** @return the scale, as a property windows follow */
    public static ReadOnlyDoubleProperty scaleProperty()
    {
        return SCALE.getReadOnlyProperty();
    }

    /**
     * Sets the scale. Call on the FX thread (windows re-lay out in response).
     *
     * @param percent the scale in percent; snapped to the nearest offered value
     */
    public static void setPercent( int percent )
    {
        SCALE.set( nearest( percent ) / 100.0 );
    }

    /** @return the current scale in percent */
    public static int percent()
    {
        return (int) Math.round( get() * 100 );
    }

    /**
     * The offered scale nearest a value, so a hand-edited or out-of-range config setting still
     * lands on a supported scale. Pure, for testing.
     *
     * @param percent any value
     *
     * @return one of {@link #PERCENTS}
     */
    static int nearest( int percent )
    {
        int best = DEFAULT_PERCENT;
        for ( int p : PERCENTS ) {
            if ( Math.abs( p - percent ) < Math.abs( best - percent ) ) {
                best = p;
            }
        }
        return best;
    }

    /**
     * Wraps a window's content so it follows the scale.
     *
     * @param content the window's own root
     *
     * @return the wrapper, to use as the scene's root
     */
    public static Parent wrap( Parent content )
    {
        return content instanceof ScaledRoot ? content : new ScaledRoot( content );
    }

    /**
     * The inverse of {@link #wrap(Parent)}: a window's own root, given its scene's root. Code that
     * needs the screen itself (its type, its grid, the sheets installed on it) must go through
     * this, because {@code scene.getRoot()} is the wrapper.
     *
     * @param sceneRoot a scene's root, wrapped or not; {@code null} passes through
     *
     * @return the wrapped content, or {@code sceneRoot} itself when it isn't a wrapper
     *
     * @since 2026.10
     */
    public static Parent unwrap( Parent sceneRoot )
    {
        return sceneRoot instanceof ScaledRoot scaled ? scaled.content() : sceneRoot;
    }

    /**
     * Keeps a scene's popup-scaling stylesheet in step with the scale. Idempotent per scene.
     *
     * @param scene the scene
     */
    public static void install( Scene scene )
    {
        if ( scene == null || scene.getProperties().containsKey( UiScale.class ) ) {
            return;
        }
        scene.getProperties().put( UiScale.class, Boolean.TRUE );
        Runnable sync = () -> {
            String sheet = popupStylesheet( get() );
            scene.getStylesheets().removeIf( s -> s.contains( SHEET_TAG ) );
            if ( sheet != null ) {
                scene.getStylesheets().add( sheet );
            }
        };
        sync.run();
        // A weak listener: the property outlives every scene.
        javafx.beans.InvalidationListener listener = o -> sync.run();
        scene.getProperties().put( SHEET_TAG, listener );
        SCALE.addListener( new javafx.beans.WeakInvalidationListener( listener ) );
    }

    /** Marks the generated stylesheet among a scene's sheets (a base64 fragment of its header). */
    private static final String SHEET_TAG = Base64.getEncoder()
            .encodeToString( "/* ui-scale */".getBytes( StandardCharsets.UTF_8 ) )
            .substring( 0, 12 );

    /**
     * The popup-scaling stylesheet for a scale, as a data URI, or {@code null} at 100 %. Pure, for
     * testing.
     *
     * @param scale the scale
     *
     * @return the stylesheet URI, or {@code null}
     */
    static String popupStylesheet( double scale )
    {
        if ( Math.abs( scale - 1.0 ) < 0.001 ) {
            return null;
        }
        String css = "/* ui-scale */\n"
                + ".root .tooltip, .root .mcl-tooltip { -fx-font-size: " + px( 12, scale ) + "; -fx-padding: "
                + px( 4, scale ) + " " + px( 8, scale ) + "; }\n"
                + ".root .context-menu { -fx-font-size: " + px( 14, scale ) + "; -fx-padding: " + px( 6, scale ) + " "
                + px( 4, scale ) + "; }\n"
                + ".root .context-menu .menu-item { -fx-padding: " + px( 8, scale ) + " " + px( 12, scale ) + "; }\n"
                + ".root .mfx-combo-box-cell { -fx-padding: " + px( 10, scale ) + " " + px( 16, scale ) + "; }\n"
                + ".root .mfx-combo-box-cell .data-label { -fx-font-size: " + px( 14, scale ) + "; }\n"
                // Dialogs: the pane is its scene's root. Its text inherits this base size (the
                // header is sized in em), and the dialog grows its window to fit.
                + ".root.dialog-pane { -fx-font-size: " + px( 14, scale ) + "; }\n"
                + ".root.dialog-pane .button-bar .button { -fx-padding: " + px( 6, scale ) + " " + px( 16, scale )
                + "; }\n";
        return "data:text/css;base64," + Base64.getEncoder().encodeToString( css.getBytes( StandardCharsets.UTF_8 ) );
    }

    private static String px( double value, double scale )
    {
        return String.format( Locale.ROOT, "%.1fpx", value * scale );
    }
}
