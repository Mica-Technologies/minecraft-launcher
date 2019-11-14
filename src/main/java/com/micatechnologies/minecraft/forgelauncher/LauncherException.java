package com.micatechnologies.minecraft.forgelauncher;

/**
 * A custom exception that provides for a specified message in an exception backtrace.
 *
 * @author Mica Technologies/hawka97
 * @version 1.0
 * @see java.lang.Exception
 */
public class LauncherException extends Exception {

    /**
     * Create a LauncherException with specified message and backtrace.
     *
     * @param exceptionMsg   exception message
     * @param exceptionTrace exception backtrace
     * @see java.lang.Throwable
     * @since 1.0
     */
    LauncherException(String exceptionMsg, Throwable exceptionTrace) {
        super(exceptionMsg, exceptionTrace);
    }

    /**
     * Create a LauncherException with specified message.
     *
     * @param exceptionMsg exception message
     * @since 1.0
     */
    LauncherException(String exceptionMsg) {
        super(exceptionMsg);
    }
}
