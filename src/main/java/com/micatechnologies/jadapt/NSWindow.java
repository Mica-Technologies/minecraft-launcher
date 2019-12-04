package com.micatechnologies.jadapt;

import org.rococoa.ObjCClass;
import org.rococoa.ObjCObject;
import org.rococoa.Rococoa;
import org.rococoa.cocoa.foundation.NSUInteger;

public abstract class NSWindow implements ObjCObject {

    //region: NSWindow Style Masks
    public static final int StyleMaskBorderless = 0;
    public static final int StyleMaskTitled = 1;
    public static final int StyleMaskClosable = 1 << 1;
    public static final int StyleMaskMiniaturizable = 1 << 2;
    public static final int StyleMaskResizable = 1 << 3;
    public static final int StyleMaskUtilityWindow = 1 << 4;
    public static final int StyleMaskDocModalWindow = 1 << 6;
    public static final int StyleMaskNonactivatingPanel = 1 << 7;
    public static final int StyleMaskUnifiedTitleAndToolbar = 1 << 12;
    public static final int StyleMaskHUDWindow = 1 << 13;
    public static final int StyleMaskFullScreen = 1 << 14;
    public static final int StyleMaskFullSizeContentView = 1 << 15;
    //endregion

    Class CLASS = Rococoa.createClass( "NSWindow", Class.class );

    interface Class extends ObjCClass {
        boolean titlebarAppearsTransparent = false;
    }

    abstract public void setTitle( String title );

    abstract public void setDocumentEdited( boolean edited );

    abstract public void setStyleMask( NSUInteger styleMask );

    abstract public NSUInteger styleMask();

    abstract public void setTitlebarAppearsTransparent( boolean titlebarAppearsTransparent );

    abstract public boolean getTitlebarAppearsTransparent();

    abstract public void setMovableByWindowBackground( boolean movableByWindowBackground );
    abstract public void setMovable( boolean movable );
}