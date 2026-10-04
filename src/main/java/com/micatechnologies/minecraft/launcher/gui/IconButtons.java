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

import javafx.scene.control.Labeled;
import javafx.scene.control.Tooltip;
import javafx.scene.shape.SVGPath;

/**
 * Material 3 icon buttons: a 40 px round button showing one glyph from {@link LauncherIcons}, its
 * label moved to a tooltip and to the accessible text so screen readers still announce it.
 * Styled by {@code .iconBtn} in {@code ui-base.css} (a standard icon button: no container, a state
 * layer on hover).
 *
 * @since 2026.10
 */
public final class IconButtons
{
    private IconButtons()
    {
    }

    /**
     * Turns a text button into an icon button. The button's current text (from FXML, already
     * localised) becomes its tooltip and accessible text unless {@code label} is given.
     *
     * @param button the button
     * @param icon   SVG path data from {@link LauncherIcons}
     * @param label  what the button does, or {@code null} to use the button's text
     */
    public static void decorate( Labeled button, String icon, String label )
    {
        String text = label != null ? label : button.getText();
        SVGPath glyph = new SVGPath();
        glyph.setContent( icon );
        glyph.getStyleClass().add( "iconBtnGlyph" );
        button.setGraphic( glyph );
        button.setText( "" );
        button.setAccessibleText( text );
        if ( text != null && !text.isBlank() ) {
            button.setTooltip( new Tooltip( text ) );
        }
        if ( !button.getStyleClass().contains( "iconBtn" ) ) {
            button.getStyleClass().add( "iconBtn" );
        }
    }
}
