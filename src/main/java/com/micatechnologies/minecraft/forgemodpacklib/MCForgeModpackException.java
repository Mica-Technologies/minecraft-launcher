package com.micatechnologies.minecraft.forgemodpacklib;

/**
 * Class for exceptions thrown in {@link com.micatechnologies.minecraft.forgemodpacklib}
 *
 * @author Mica Technologies/hawka97
 * @version 1.0
 * @see java.lang.Exception
 */
public class MCForgeModpackException extends Exception {

    /**
     * Create an MCForgeModpackException with error message and backtrace.
     *
     * @param exceptionMsg   exception message
     * @param exceptionTrace exception backtrace
     *
     * @since 1.0
     */
    MCForgeModpackException( String exceptionMsg, Throwable exceptionTrace ) {
        super( exceptionMsg, exceptionTrace );
    }

    /**
     * Create an MCForgeModpackException with error message.
     *
     * @param exceptionMsg exception message
     *
     * @since 1.0
     */
    MCForgeModpackException( String exceptionMsg ) {
        super( exceptionMsg );
    }
}
