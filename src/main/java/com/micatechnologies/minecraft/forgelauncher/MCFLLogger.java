package com.micatechnologies.minecraft.forgelauncher;

/**
 * Class for handling logging processes. Error messages are logged to System.err,
 * and an error dialog is shown in client mode. Log and debug messages are logged
 * to System.out, and debug messages hidden unless enabled.
 *
 * @author Mica Technologies/hawka97
 * @version 1.1
 */
public class MCFLLogger {
    //region: Functional Methods

    /**
     * Log an error to the appropriate destination(s).
     * <p>
     * Client Mode: Log to System.err and show JavaFX Dialog
     * Server Mode: Log to System.err
     *
     * @param msg     error message
     * @param errorID error ID
     *
     * @since 1.0
     */
    static void error( String msg, int errorID ) {
        // Output to System.err and show JFX Dialog for client mode
        if ( App.getMode() == App.MODE_CLIENT ) {
            System.err.println( "[" + MCFLConstants.LAUNCHER_APPLICATION_NAME + "/ERR-" + errorID + "] " + msg );
            GUIController.showErrorMessage( "Oops", "Error", msg, errorID );
        }
        // Output to System.err for server mode
        else if ( App.getMode() == App.MODE_SERVER ) {
            System.err.println( "[" + MCFLConstants.LAUNCHER_APPLICATION_NAME + "/ERR-" + errorID + "] " + msg );
        }
        // Handle invalid mode
        else {
            handleInvalidMode();
        }
    }

    /**
     * Log a standard message to the appropriate destination(s).
     * <p>
     * Client Mode: Log to System.out
     * Server Mode: Log to System.out
     *
     * @param msg log message
     *
     * @since 1.0
     */
    static void log( String msg ) {
        // Output to System.out for client mode
        if ( App.getMode() == App.MODE_CLIENT ) {
            System.out.println( "[" + MCFLConstants.LAUNCHER_APPLICATION_NAME + "/LOG] " + msg );
        }
        // Output to System.out for server mode
        else if ( App.getMode() == App.MODE_SERVER ) {
            System.out.println( "[" + MCFLConstants.LAUNCHER_APPLICATION_NAME + "/LOG] " + msg );
        }
        // Handle invalid mode
        else {
            handleInvalidMode();
        }
    }

    /**
     * Log a debug message to the appropriate destination(s).
     * <p>
     * Client Mode: Log to System.out (if debug enabled)
     * Server Mode: Log to System.out (if debug enabled)
     *
     * @param msg debug message
     *
     * @since 1.0
     */
    static void debug( String msg ) {
        // Return if debug mode not enabled.
        if ( !App.getLauncherConfig().getDebug() ) return;

        // Output to System.out for client mode
        if ( App.getMode() == App.MODE_CLIENT ) {
            System.out.println( "[" + MCFLConstants.LAUNCHER_APPLICATION_NAME + "/DBG] " + msg );
        }
        // Output to System.out for server mode
        else if ( App.getMode() == App.MODE_SERVER ) {
            System.out.println( "[" + MCFLConstants.LAUNCHER_APPLICATION_NAME + "/DBG] " + msg );
        }
        // Handle invalid mode
        else {
            handleInvalidMode();
        }

    }
    //endregion

    //region: Utility Methods

    /**
     * Handle an invalid launcher mode selection.
     * <p>
     * Client Mode: Throws IllegalStateException
     * Server Mode: Throws IllegalStateException
     *
     * @since 1.1
     */
    private static void handleInvalidMode() {
        throw new IllegalStateException( "Launcher is operating with an invalid launcher mode. Application will terminate!" );
    }
    //endregion
}
