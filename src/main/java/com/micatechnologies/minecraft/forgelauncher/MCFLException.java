package com.micatechnologies.minecraft.forgelauncher;

/**
 * Exception wrapper class for handling and reporting errors and exceptions with user-friendly wording/explanation.
 *
 * @author Mica Technologies/hawka97
 * @version 1.1
 * @see java.lang.Exception
 */
public class MCFLException extends Exception {

    //region: Functional Methods

    /**
     * Create an MCFLException with specified message and backtrace.
     *
     * @param exceptionMsg   exception message
     * @param exceptionTrace exception backtrace
     *
     * @see java.lang.Throwable
     * @since 1.0
     */
    MCFLException( String exceptionMsg, Throwable exceptionTrace ) {
        super( exceptionMsg, exceptionTrace );
    }

    /**
     * Create an MCFLException with specified message.
     *
     * @param exceptionMsg exception message
     *
     * @since 1.0
     */
    MCFLException( String exceptionMsg ) {
        super( exceptionMsg );
    }
    //endregion
}
