package com.micatechnologies.minecraft.authlib;

/**
 * A custom exception that provides for a specified message in an exception backtrace.
 *
 * @author Mica Technologies/hawka97
 * @version 1.0
 * @see java.lang.Exception
 */
public class MCAuthException extends Exception {

    /**
     * Create an MCAuthException with specified message and backtrace.
     *
     * @param exceptionMsg   exception message
     * @param exceptionTrace exception backtrace
     *
     * @see java.lang.Throwable
     * @since 1.0
     */
    MCAuthException( String exceptionMsg, Throwable exceptionTrace ) {
        super( exceptionMsg, exceptionTrace );
    }

    /**
     * Create an MCAuthException with specified message.
     *
     * @param exceptionMsg exception message
     *
     * @since 1.0
     */
    MCAuthException( String exceptionMsg ) {
        super( exceptionMsg );
    }
}
