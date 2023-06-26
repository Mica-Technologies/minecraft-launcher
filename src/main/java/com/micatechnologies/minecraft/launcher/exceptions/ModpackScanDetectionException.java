/*
 * Copyright (c) 2021 Mica Technologies
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

package com.micatechnologies.minecraft.launcher.exceptions;

import me.cortex.jarscanner.Results;

/**
 * Exception wrapper class for handling and reporting mod pack scan detections with user-friendly working/explanation if
 * they are found.
 *
 * @author Mica Technologies
 * @version 1.0.0
 * @see LauncherException
 * @see ModpackException
 * @since 2023.2.3
 */
public class ModpackScanDetectionException extends ModpackException
{

    /**
     * Create a {@link ModpackScanDetectionException} with specified message.
     *
     * @param scanResults results of the scan
     *
     * @since 1.0.0
     */
    public ModpackScanDetectionException( Results scanResults ) {
        super( getExceptionMsg( scanResults ) );
    }

    /**
     * Create a {@link ModpackScanDetectionException} with specified message and backtrace.
     *
     * @param scanResults    results of the scan
     * @param exceptionTrace exception backtrace
     *
     * @since 1.0.0
     */
    public ModpackScanDetectionException( Results scanResults, Throwable exceptionTrace ) {
        super( getExceptionMsg( scanResults ), exceptionTrace );
    }

    /**
     * Build a user-friendly message for the exception using the specified {@link Results} object.
     *
     * @return user-friendly message for the exception
     *
     * @since 1.0.0
     */
    private static String getExceptionMsg( Results scanResults ) {
        boolean infectionFound = false;
        StringBuilder exceptionMsg = new StringBuilder();
        if ( scanResults.getStage1Detections() != null && !scanResults.getStage1Detections().isEmpty() ) {
            infectionFound = true;
            exceptionMsg.append( "Stage 1 infections found: " )
                        .append( scanResults.getStage1Detections().size() )
                        .append( "\n" );
            for ( String infection : scanResults.getStage1Detections() ) {
                exceptionMsg.append( "   " ).append( infection ).append( "\n" );
            }
        }
        if ( scanResults.getStage2Detections() != null && !scanResults.getStage2Detections().isEmpty() ) {
            infectionFound = true;
            exceptionMsg.append( "Stage 2 infections found: " )
                        .append( scanResults.getStage2Detections().size() )
                        .append( "\n" );
            for ( String infection : scanResults.getStage2Detections() ) {
                exceptionMsg.append( "   " ).append( infection ).append( "\n" );
            }
        }

        // Add no infection message if no infections were found
        return infectionFound ? exceptionMsg.toString() : "No mod pack infections have been detected.";
    }
}
