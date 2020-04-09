package com.micatechnologies.minecraft.forgelauncher.exceptions;

/**
 * A custom exception that provides for a specified message in an exception backtrace.
 *
 * @author Mica Technologies/hawka97
 * @version 1.0
 * @see java.lang.Exception
 */
public class FLAuthenticationException extends Exception {

    /**
     * Create an MCAuthException with specified message and backtrace.
     *
     * @param exceptionMsg   exception message
     * @param exceptionTrace exception backtrace
     *
     * @see java.lang.Throwable
     * @since 1.0
     */
    public FLAuthenticationException( String exceptionMsg, Throwable exceptionTrace ) {
        super( exceptionMsg, exceptionTrace );
    }

    /**
     * Create an MCAuthException with specified message.
     *
     * @param exceptionMsg exception message
     *
     * @since 1.0
     */
    public FLAuthenticationException( String exceptionMsg ) {
        super( exceptionMsg );
    }
}
