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

import javafx.scene.layout.Region;
import javafx.scene.shape.ClosePath;
import javafx.scene.shape.LineTo;
import javafx.scene.shape.MoveTo;
import javafx.scene.shape.Path;
import javafx.scene.shape.PathElement;

import java.util.ArrayList;
import java.util.List;

/**
 * A decorative strip of blocky Minecraft hills for the bottom of the progress screens: two
 * layers of block-sized columns (a taller one behind, a shorter one in front with a grass-coloured
 * top), redrawn to fit the width. The heights come from a fixed function of the column index, so
 * the skyline is the same every time and doesn't jump on resize. Colours come from
 * {@code ui-base.css} ({@code .blockyHorizon*}); it ignores the mouse.
 *
 * @since 2026.10
 */
public class BlockyHorizon extends Region
{
    /** Width and height of one block. */
    static final double BLOCK = 24;

    private final Path back = new Path();
    private final Path front = new Path();
    private final Path grass = new Path();
    private final Path seams = new Path();

    /** Creates the horizon. */
    public BlockyHorizon()
    {
        getStyleClass().add( "blockyHorizon" );
        back.getStyleClass().add( "blockyHorizonBack" );
        front.getStyleClass().add( "blockyHorizonFront" );
        grass.getStyleClass().add( "blockyHorizonGrass" );
        seams.getStyleClass().add( "blockyHorizonSeams" );
        seams.setStrokeWidth( 1 );
        for ( Path p : new Path[]{ back, front, grass } ) {
            p.setManaged( false );
            p.setStrokeWidth( 0 );
        }
        seams.setManaged( false );
        getChildren().addAll( back, front, seams, grass );
        setMouseTransparent( true );
        setMinHeight( 0 );
        setPrefHeight( BLOCK * 6 );
        setMaxHeight( BLOCK * 6 );
    }

    /**
     * How many blocks tall a column is, between 1 and {@code max}: a sum of slow waves, so
     * neighbouring columns form hills rather than noise.
     *
     * @param column the column index
     * @param layer  0 for the back layer, 1 for the front, which uses different waves
     * @param max    the tallest column, in blocks
     *
     * @return the column height in blocks
     */
    static int columnHeight( int column, int layer, int max )
    {
        double x = column + layer * 37.0;
        double v = 0.55 * Math.sin( x * 0.31 ) + 0.30 * Math.sin( x * 0.67 + 1.3 ) + 0.15 * Math.sin( x * 1.9 + 0.4 );
        return 1 + (int) Math.round( ( v + 1 ) / 2 * ( max - 1 ) );
    }

    @Override
    protected void layoutChildren()
    {
        double w = getWidth();
        double h = getHeight();
        int columns = (int) Math.ceil( w / BLOCK ) + 1;
        back.getElements().setAll( skyline( columns, 0, 6, h, 0 ) );
        front.getElements().setAll( skyline( columns, 1, 3, h, BLOCK / 2 ) );
        seams.getElements().setAll( seams( columns, 3, h, BLOCK / 2 ) );
        grass.getElements().setAll( grassCaps( columns, 3, h, BLOCK / 2 ) );
    }

    /** The outline of one layer: a stepped top edge closed along the bottom. */
    private static List< PathElement > skyline( int columns, int layer, int max, double h, double offset )
    {
        List< PathElement > els = new ArrayList<>();
        els.add( new MoveTo( -offset, h ) );
        for ( int c = 0; c < columns; c++ ) {
            double top = h - columnHeight( c, layer, max ) * BLOCK;
            double x = c * BLOCK - offset;
            els.add( new LineTo( x, top ) );
            els.add( new LineTo( x + BLOCK, top ) );
        }
        els.add( new LineTo( columns * BLOCK - offset, h ) );
        els.add( new ClosePath() );
        return els;
    }

    /** The block grid inside the front layer, so the hills read as stacked blocks. */
    private static List< PathElement > seams( int columns, int max, double h, double offset )
    {
        List< PathElement > els = new ArrayList<>();
        for ( int c = 0; c < columns; c++ ) {
            int height = columnHeight( c, 1, max );
            double x = c * BLOCK - offset;
            double top = h - height * BLOCK;
            // Left edge of the column, then each block's top edge below the surface one.
            els.add( new MoveTo( x + 0.5, top ) );
            els.add( new LineTo( x + 0.5, h ) );
            for ( int b = 1; b < height; b++ ) {
                double y = top + b * BLOCK + 0.5;
                els.add( new MoveTo( x, y ) );
                els.add( new LineTo( x + BLOCK, y ) );
            }
        }
        return els;
    }

    /** A thin grass strip on top of each front-layer column. */
    private static List< PathElement > grassCaps( int columns, int max, double h, double offset )
    {
        List< PathElement > els = new ArrayList<>();
        double cap = BLOCK / 6;
        for ( int c = 0; c < columns; c++ ) {
            double top = h - columnHeight( c, 1, max ) * BLOCK;
            double x = c * BLOCK - offset;
            els.add( new MoveTo( x, top ) );
            els.add( new LineTo( x + BLOCK, top ) );
            els.add( new LineTo( x + BLOCK, top + cap ) );
            els.add( new LineTo( x, top + cap ) );
            els.add( new ClosePath() );
        }
        return els;
    }
}
