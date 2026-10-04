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
 * @since 2026.10
 */
final class ScaledRoot extends Region
{
    private final Parent content;
    private final Scale zoom = new Scale( 1, 1, 0, 0 );
    private final InvalidationListener relayout = o -> requestLayout();

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
        content.resizeRelocate( in.getLeft(), in.getTop(), w / s(), h / s() );
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
