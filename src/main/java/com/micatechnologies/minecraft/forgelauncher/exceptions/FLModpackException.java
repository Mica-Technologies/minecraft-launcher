package com.micatechnologies.minecraft.forgelauncher.exceptions;

/**
 * Class for exceptions thrown in {@link com.micatechnologies.minecraft.forgelauncher.modpack}
 *
 * @author Mica Technologies
 * @version 1.0
 * @creator hawka97
 * @editors hawka97
 * @see java.lang.Exception
 */
public class FLModpackException extends FLLauncherException
{

    /**
     * Create an MCForgeModpackException with error message and backtrace.
     *
     * @param exceptionMsg   exception message
     * @param exceptionTrace exception backtrace
     *
     * @since 1.0
     */
    public FLModpackException( String exceptionMsg, Throwable exceptionTrace ) {
        super( exceptionMsg, exceptionTrace );
    }

    /**
     * Create an MCForgeModpackException with error message.
     *
     * @param exceptionMsg exception message
     *
     * @since 1.0
     */
    public FLModpackException( String exceptionMsg ) {
        super( exceptionMsg );
    }
}
