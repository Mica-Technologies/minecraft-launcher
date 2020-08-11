/*
 * Copyright (c) 2020 Mica Technologies
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

package com.micatechnologies.jadapt;

import org.rococoa.ObjCClass;
import org.rococoa.ObjCObject;
import org.rococoa.Rococoa;
import org.rococoa.cocoa.foundation.NSUInteger;

/**
 * Abstract adapter for modifying native macOS window properties of a Java Swing or JavaFX window.
 *
 * @author Mica Technologies
 * @version 1.0.1
 * @creator hawka97
 * @editors hawka97
 * @since 1.0
 */
public abstract class NSWindow implements ObjCObject
{
    /**
     * Constant for borderless style mask. The window displays none of the usual peripheral elements. Useful only for
     * display or caching purposes. A window that uses <code>StyleMaskBorderless</code> can't become key or main, unless
     * the value of <code>canBecomeKey()</code> or <code>canBecomeMain()</code> is <code>true</code>.
     *
     * @since 1.0
     */
    public static final int StyleMaskBorderless = 0;

    /**
     * Constant for titled style mask. The window displays a title bar.
     *
     * @since 1.0
     */
    public static final int StyleMaskTitled = 1;

    /**
     * Constant for closable style mask. The window displays a close button.
     *
     * @since 1.0
     */
    public static final int StyleMaskClosable = 1 << 1;

    /**
     * Constant for miniaturizable style mask. The window displays a minimize button.
     *
     * @since 1.0
     */
    public static final int StyleMaskMiniaturizable = 1 << 2;

    /**
     * Constant for resizable style mask. The window can be resized by the user.
     *
     * @since 1.0
     */
    public static final int StyleMaskResizable = 1 << 3;

    /**
     * Constant for utility window style mask. The window is a panel or a subclass of <code>NSPanel</code>.
     *
     * @since 1.0
     */
    public static final int StyleMaskUtilityWindow = 1 << 4;

    /**
     * Constant for document-modal panel style mask. The window is a document-modal panel (or a subclass of
     * <code>NSPanel</code>).
     *
     * @since 1.0
     */
    public static final int StyleMaskDocModalWindow = 1 << 6;

    /**
     * Constant for non-activating panel style mask. The window is a panel or a subclass of <code>NSPanel</code> that
     * does not activate the owning app.
     *
     * @since 1.0
     */
    public static final int StyleMaskNonactivatingPanel = 1 << 7;

    /**
     * Constant for unified title and toolbar style mask. This constant has no effect, because all windows that include
     * a toolbar use the unified style.
     *
     * @since 1.0
     */
    public static final int StyleMaskUnifiedTitleAndToolbar = 1 << 12;

    /**
     * Constant for HUD panel style mask. The window is a HUD panel.
     *
     * @since 1.0
     */
    public static final int StyleMaskHUDWindow = 1 << 13;

    /**
     * Constant for full screen style mask. The window can appear full screen. A fullscreen window does not draw its
     * title bar, and may have special handling for its toolbar. (This mask is automatically toggled when {@link
     * #toggleFullScreen()} is called.)
     *
     * @since 1.0
     */
    public static final int StyleMaskFullScreen = 1 << 14;

    /**
     * Constant for full size content view style mask. When set, the window's content view consumes the full size of the
     * window. Although you can combine this constant with other window style masks, it is respected only for windows
     * with a title bar. Note that using this masks opts in to layer-backing.
     *
     * @since 1.0
     */
    public static final int StyleMaskFullSizeContentView = 1 << 15;

    /**
     * Wrapped Rococoa class object.
     *
     * @since 1.0
     */
    Class CLASS = Rococoa.createClass( "NSWindow", Class.class );

    /**
     * Internal Objective-C class wrapper that contains variables from the adapting Objective-C class.
     *
     * @since 1.0
     */
    interface Class extends ObjCClass
    {
        /**
         * A boolean value that indicates whether the title bar draws its background.
         *
         * @since 1.0
         */
        boolean titlebarAppearsTransparent = false;
    }

    /**
     * Sets the title of the window.
     *
     * @param title window title
     *
     * @since 1.0
     */
    abstract public void setTitle( String title );

    /**
     * Sets the edited flag of the window to the specified boolean value.
     *
     * @param edited true if edited
     *
     * @since 1.0
     */
    abstract public void setDocumentEdited( boolean edited );

    /**
     * Sets the style mask of the window to the specified style mask.
     *
     * @param styleMask window style mask
     *
     * @since 1.0
     */
    abstract public void setStyleMask( NSUInteger styleMask );

    /**
     * Gets the style mask of the window.
     *
     * @return window style mask
     *
     * @since 1.0
     */
    abstract public NSUInteger styleMask();

    /**
     * Sets the boolean value that indicates whether the title bar draws its background.
     *
     * @param titlebarAppearsTransparent title bar appears transparent flag
     *
     * @since 1.0
     */
    abstract public void setTitlebarAppearsTransparent( boolean titlebarAppearsTransparent );

    /**
     * Gets the boolean value that indicates whether the title bar draws its background.
     *
     * @return title bar appears transparent flag
     *
     * @since 1.0
     */
    abstract public boolean getTitlebarAppearsTransparent();

    /**
     * Sets the boolean value that indicates whether the window is movable by clicking and dragging anywhere in its
     * background.
     *
     * @param movableByWindowBackground window movable by background flag
     *
     * @since 1.0
     */
    abstract public void setMovableByWindowBackground( boolean movableByWindowBackground );

    /**
     * Sets the boolean value that indicates whether the window can be dragged by clicking in its title bar or
     * background.
     *
     * @param movable window movable flag
     *
     * @since 1.0
     */
    abstract public void setMovable( boolean movable );

    /**
     * Takes the window into or out of fullscreen mode.
     *
     * @since 1.0
     */
    abstract public void toggleFullScreen();

    /**
     * Returns a boolean value representing the window's fullscreen state.
     *
     * @return fullscreen state
     *
     * @since 1.0
     */
    abstract public boolean isFullScreen();
}