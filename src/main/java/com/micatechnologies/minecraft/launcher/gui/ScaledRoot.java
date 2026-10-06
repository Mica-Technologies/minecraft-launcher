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

import javafx.beans.InvalidationListener;
import javafx.beans.WeakInvalidationListener;
import javafx.geometry.Insets;
import javafx.scene.Parent;
import javafx.scene.layout.Region;
import javafx.scene.transform.Scale;

/**
 * A window's root that zooms its content by the {@link UiScale}: the content is laid out at the
 * window's size divided by the scale, then drawn scaled up (or down) to fill it. Text and vector
 * shapes stay sharp; sizes, minimums and hit-testing all follow, since they go through the
 * transform.
 *
 * <p>The content also carries the {@code root} style class, which JavaFX gives only the scene's
 * root: theme sheets installed on the content define their tokens on {@code .root}, and those
 * rules must still match it.</p>
 *
 * <p>The region itself paints nothing: its content and the scene fill draw the window's
 * background, which lets the Native theme stay transparent for Mica.</p>
 *
 * <p>It can also hold a dock: a region along the bottom edge, zoomed the same way, that the
 * content makes room for. The main window docks the Running Games view here; since each screen
 * has its own scene and wrapper, the dock moves to the new wrapper on every screen change (see
 * {@link #setDock(Region)}). The dock gets its preferred height when the content can spare it,
 * and always at least the dock's own minimum, even when that squeezes the content below its
 * minimum; the content is then clipped at the dock's edge.</p>
 *
 * @since 2026.10
 */
final class ScaledRoot extends Region
{
    private final Parent content;
    private final Scale zoom = new Scale( 1, 1, 0, 0 );
    private final InvalidationListener relayout = o -> requestLayout();
    private final Scale dockZoom = new Scale( 1, 1, 0, 0 );
    private final javafx.scene.shape.Rectangle contentClip = new javafx.scene.shape.Rectangle();
    private Region dock;

    ScaledRoot( Parent content )
    {
        this.content = content;
        zoom.xProperty().bind( UiScale.scaleProperty() );
        zoom.yProperty().bind( UiScale.scaleProperty() );
        content.getTransforms().add( zoom );
        if ( !content.getStyleClass().contains( "root" ) ) {
            content.getStyleClass().add( "root" );
        }
        getStyleClass().add( "scaledRoot" );
        // As the scene's root this region gets JavaFX's default .root rule, which paints modena's
        // light -fx-background; the theme sheets live on the content, so they never override it
        // here. Opaque themes cover it, but the Native theme's content is transparent so Mica can
        // show through, and the light fill showed instead, behind dark-theme text. An inline style
        // outranks the default stylesheet. The content and scene fill paint the real background.
        setStyle( "-fx-background-color: transparent;" );
        getChildren().add( content );
        UiScale.scaleProperty().addListener( new WeakInvalidationListener( relayout ) );
        dockZoom.xProperty().bind( UiScale.scaleProperty() );
        dockZoom.yProperty().bind( UiScale.scaleProperty() );
    }

    /**
     * Docks a region along the bottom edge, or removes the current one. A dock that is invisible
     * takes no room.
     *
     * @param newDock the region to dock, or {@code null} for none
     */
    void setDock( Region newDock )
    {
        if ( dock == newDock ) {
            return;
        }
        if ( dock != null ) {
            dock.getTransforms().remove( dockZoom );
            getChildren().remove( dock );
        }
        dock = newDock;
        if ( dock != null ) {
            // A node has one parent: take it from whichever screen it was docked in.
            if ( dock.getParent() instanceof ScaledRoot previous ) {
                previous.setDock( null );
            }
            dock.getTransforms().add( dockZoom );
            getChildren().add( dock );
        }
        requestLayout();
    }

    /** @return the docked region, or {@code null} */
    Region dock()
    {
        return dock;
    }

    /**
     * The dock's height in this region's (scaled) pixels for a given height: its preferred
     * height, held between its minimum and what the content can spare. Package-private for tests.
     *
     * @param height this region's inner height
     *
     * @return the dock's height, or 0 when there is no visible dock
     */
    double dockHeight( double height )
    {
        if ( dock == null || !dock.isVisible() ) {
            return 0;
        }
        double scale = s();
        double width = ( getWidth() - getInsets().getLeft() - getInsets().getRight() ) / scale;
        double min = dock.minHeight( width ) * scale;
        double spare = height - content.minHeight( -1 ) * scale;
        double wanted = dock.prefHeight( width ) * scale;
        return Math.min( height, Math.max( min, Math.min( wanted, spare ) ) );
    }

    /** @return the window's own root */
    Parent content()
    {
        return content;
    }

    private static double s()
    {
        return UiScale.get();
    }

    @Override
    protected void layoutChildren()
    {
        Insets in = getInsets();
        double w = getWidth() - in.getLeft() - in.getRight();
        double h = getHeight() - in.getTop() - in.getBottom();
        double docked = dockHeight( h );
        content.resizeRelocate( in.getLeft(), in.getTop(), w / s(), ( h - docked ) / s() );
        if ( dock != null ) {
            dock.resizeRelocate( in.getLeft(), in.getTop() + h - docked, w / s(), docked / s() );
        }
        // Squeezed below its minimum, the content would spill under the dock (and show through
        // it in the Native theme); clip it to its own area while a dock takes room.
        if ( docked > 0 ) {
            contentClip.setWidth( w / s() );
            contentClip.setHeight( ( h - docked ) / s() );
            content.setClip( contentClip );
        }
        else if ( content.getClip() == contentClip ) {
            content.setClip( null );
        }
    }

    @Override
    protected double computeMinWidth( double height )
    {
        return content.minWidth( -1 ) * s() + getInsets().getLeft() + getInsets().getRight();
    }

    @Override
    protected double computeMinHeight( double width )
    {
        return content.minHeight( -1 ) * s() + getInsets().getTop() + getInsets().getBottom();
    }

    @Override
    protected double computePrefWidth( double height )
    {
        return content.prefWidth( -1 ) * s() + getInsets().getLeft() + getInsets().getRight();
    }

    @Override
    protected double computePrefHeight( double width )
    {
        return content.prefHeight( -1 ) * s() + getInsets().getTop() + getInsets().getBottom();
    }

    @Override
    protected double computeMaxWidth( double height )
    {
        return Double.MAX_VALUE;
    }

    @Override
    protected double computeMaxHeight( double width )
    {
        return Double.MAX_VALUE;
    }
}
