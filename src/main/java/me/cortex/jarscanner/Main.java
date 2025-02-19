package me.cortex.jarscanner;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.jar.JarFile;

/**
 * Main class for Nekodetector, which scans for malicious code signatures from the Nekoclient malware.
 * <p>ORIGINAL SOURCE: https://github.com/MCRcortex/nekodetector</p>
 *
 * @author MCRcortex (https://github.com/MCRcortex)
 * @author Huskydog9988 (https://github.com/Huskydog9988)
 * @author mica-alex (https://github.com/mica-alex)
 */
public class Main
{

    /**
     * The executor service used to scan jars in parallel.
     */
    private static ExecutorService executorService;

    /**
     * Runs a check and scans a folder for jars with malicious code signatures. An object containing lists of infected
     * files found during scan stages is returned.
     *
     * @param nThreads       the number of threads to use for scanning
     * @param dirToCheck     the directory to scan
     * @param emitWalkErrors whether to emit errors when walking the directory tree
     * @param logOutput      the function to use for logging output
     * @param progessOutput  the function to use for logging progress
     *
     * @return a scan results object
     *
     * @throws IllegalArgumentException if the specified directory does not exist or is not a directory, or if the
     *                                  number of threads is less than 1.
     * @throws IOException              if an I/O error occurs while walking the directory tree
     */
    public static Results run( int nThreads,
                               Path dirToCheck,
                               boolean emitWalkErrors,
                               List< String > excludeFolders,
                               Function< String, String > logOutput,
                               Function< Progress, Progress > progessOutput ) throws IOException, InterruptedException
    {
        // Output scan start
        long startTime = System.currentTimeMillis();
        logOutput.apply( Constants.ANSI_GREEN +
                                 "Starting All Scans - " +
                                 Constants.ANSI_RESET +
                                 "This may take a while depending on the size of the directories and JAR files." );

        // Check that specified directory is valid, exists, and is a directory
        File dirToCheckFile = dirToCheck.toFile();
        if ( !dirToCheckFile.exists() ) {
            throw new IllegalArgumentException( "Specified directory does not exist: " + dirToCheck );
        }
        if ( !dirToCheckFile.isDirectory() ) {
            throw new IllegalArgumentException( "Specified directory is not a directory: " + dirToCheck );
        }

        // Check number of threads is valid
        if ( nThreads < 1 ) {
            throw new IllegalArgumentException( "Number of threads must be at least 1" );
        }

        // Create executor service with number of threads
        executorService = Executors.newFixedThreadPool( nThreads );

        // Scan all jars in path
        final double[] progress = { 0.0, 0.0 };
        // set progress[1] to the total number of files in the directory
        logOutput.apply( Constants.ANSI_GREEN + "Preparing Scanner..." + Constants.ANSI_RESET );
        long scannerPrepStartTime = System.currentTimeMillis();
        progress[1] = countValidFiles(dirToCheck, excludeFolders);
        long scannerPrepEndTime = System.currentTimeMillis();
        long scannerPrepTime = scannerPrepEndTime - scannerPrepStartTime;
        logOutput.apply( Constants.ANSI_GREEN +
                                 "Scanner Prepared - " +
                                 Constants.ANSI_RESET +
                                 "Took  " +
                                 scannerPrepTime +
                                 "ms." );

        long stage1StartTime = System.currentTimeMillis();
        logOutput.apply( Constants.ANSI_GREEN + "Running Stage 1 Scan..." + Constants.ANSI_RESET );
        final List< String > stage1InfectionsList = new ArrayList<>();
        Files.walkFileTree( dirToCheck, new FileVisitor< Path >()
        {
            /**
             * Invoked for a directory before entries in the directory are visited.
             * @param dir  a reference to the directory
             * @param attrs the directory's basic attributes
             *
             * @return {@link FileVisitResult#CONTINUE}.
             */
            @Override
            public FileVisitResult preVisitDirectory( Path dir, BasicFileAttributes attrs ) {
                // Check if the directory should be excluded
                if ( isPathExcludedFromScan( dirToCheck, dir, excludeFolders ) ) {
                    return FileVisitResult.SKIP_SUBTREE; // Skip visiting contents of this directory
                }
                return FileVisitResult.CONTINUE;
            }

            /**
             * Invoked for a file in a directory.
             * @param file a reference to the file
             * @param attrs the file's basic attributes
             *
             * @return {@link FileVisitResult#CONTINUE}
             */
            @Override
            public FileVisitResult visitFile( Path file, BasicFileAttributes attrs ) {
                // Check if the file should be excluded
                if ( isPathExcludedFromScan( dirToCheck, file, excludeFolders ) ) {
                    return FileVisitResult.CONTINUE; // Skip scanning this file
                }

                // Check if file is a scannable Jar file
                boolean isScannable = file.toString().toLowerCase().endsWith( Constants.JAR_FILE_EXTENSION );

                // If file is scannable, submit it to the executor service for scanning
                progress[ 0 ]++;
                if ( isScannable ) {
                    executorService.submit( () -> {
                        try ( JarFile scannableJarFile = new JarFile( file.toFile() ) ) {
                            logOutput.apply( "Scanning Jar file for infection: " + file.getFileName() );
                            boolean infectionDetected = Detector.scan( scannableJarFile, file, logOutput );
                            if ( infectionDetected ) {
                                synchronized ( stage1InfectionsList ) {
                                    stage1InfectionsList.add( file.toString() );
                                }
                            }
                        }
                        catch ( Exception e ) {
                            if ( emitWalkErrors ) {
                                logOutput.apply( "Failed to scan Jar file: " + file );
                                e.printStackTrace();
                            }
                        }
                    } );
                }

                // If progress output is not null, update progress
                if ( progessOutput != null ) {
                    progessOutput.apply( new Progress( progress[ 0 ], progress[ 1 ] ) );
                }

                return FileVisitResult.CONTINUE;
            }

            /**
             * Invoked for a file that could not be visited.
             * @param file a reference to the file
             * @param exc the I/O exception that prevented the file from being visited
             *
             * @return {@link FileVisitResult#CONTINUE}
             */
            @Override
            public FileVisitResult visitFileFailed( Path file, IOException exc ) {
                if ( emitWalkErrors ) {
                    logOutput.apply( "Failed to access file: " + file );
                }
                return FileVisitResult.CONTINUE;
            }

            /**
             * Invoked for a directory after entries in the directory, and all of their
             * descendants, have been visited. This method is also invoked when iteration
             * of the directory completes prematurely (by a {@link #visitFile visitFile}
             * failure, or by throwing an exception).
             *
             * @param dir a reference to the directory
             * @param exc {@code null} if the iteration of the directory completes without
             *          an error; otherwise the I/O exception that caused the iteration
             *          of the directory to complete prematurely
             *
             * @return {@link FileVisitResult#CONTINUE}
             */
            @Override
            public FileVisitResult postVisitDirectory( Path dir, IOException exc ) {
                if ( exc != null && emitWalkErrors ) {
                    logOutput.apply( "Failed to access directory: " + dir );
                }
                return FileVisitResult.CONTINUE;
            }
        } );

        // Shutdown executor service and wait for all tasks to complete
        executorService.shutdown();
        boolean timedOut = !executorService.awaitTermination( 100000, TimeUnit.DAYS );
        if ( timedOut ) {
            logOutput.apply( "Timed out while waiting for Jar scanning to complete." );
        }
        long stage1EndTime = System.currentTimeMillis();
        long stage1Time = stage1EndTime - stage1StartTime;
        logOutput.apply( Constants.ANSI_GREEN +
                                 "Stage 1 Scan Complete - " +
                                 Constants.ANSI_RESET +
                                 "Took  " +
                                 stage1Time +
                                 "ms." );

        // Run stage 2 scan
        long stage2StartTime = System.currentTimeMillis();
        logOutput.apply( Constants.ANSI_GREEN + "Running Stage 2 Scan..." + Constants.ANSI_RESET );
        List< String > stage2InfectionsList = Detector.checkForStage2();
        long stage2EndTime = System.currentTimeMillis();
        long stage2Time = stage2EndTime - stage2StartTime;
        logOutput.apply( Constants.ANSI_GREEN +
                                 "Stage 2 Scan Complete - " +
                                 Constants.ANSI_RESET +
                                 "Took  " +
                                 stage2Time +
                                 "ms." );

        // Output scan end
        long endTime = System.currentTimeMillis();
        long totalTime = endTime - startTime;
        logOutput.apply(
                Constants.ANSI_GREEN + "All Scans Complete - " + Constants.ANSI_RESET + "Total " + totalTime + "ms." );

        // Build results and return
        return new Results( stage1InfectionsList, stage2InfectionsList );
    }

    /**
     * Cancels the current scan, if one is running, by shutting down the executor service.
     */
    public static void cancelScanIfRunning() {
        if ( executorService != null ) {
            executorService.shutdownNow();
        }
    }

    private static String normalizeExcludedPath( String exclusion ) {
        // Trim leading and trailing whitespace
        String normalized = exclusion.trim();

        // Remove leading slash if present
        if ( normalized.startsWith( "/" ) ) {
            normalized = normalized.substring( 1 );
        }

        // Remove trailing slash if present
        if ( normalized.endsWith( "/" ) ) {
            normalized = normalized.substring( 0, normalized.length() - 1 );
        }

        return normalized;
    }

    private static long countValidFiles(Path dirToCheck, List<String> excludeFolders) throws IOException {
        final long[] fileCount = {0};
        Files.walkFileTree(dirToCheck, new FileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (isPathExcludedFromScan(dirToCheck, dir, excludeFolders)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (!isPathExcludedFromScan(dirToCheck, file, excludeFolders) && !file.toFile().isDirectory()) {
                    fileCount[0]++;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
        return fileCount[0];
    }


    private static boolean isPathExcludedFromScan( Path scanPath, Path checkPath, List< String > excludePaths ) {
        // Check if the checkPath is the same as the scanPath
        if ( scanPath.equals( checkPath ) ) {
            return false; // The scanPath itself should not be excluded
        }

        // Get checkPath relative to scanPath (and parent)
        Path relativeCheckPath = scanPath.relativize( checkPath );
        Path relativeCheckPathParent = relativeCheckPath.getParent();
        String relativeCheckPathString = relativeCheckPath.toString().toLowerCase();
        String relativeCheckPathParentString = relativeCheckPath.toString().toLowerCase();
        String relativeCheckPathFileName = relativeCheckPath.getFileName().toString().toLowerCase();

        // Iterate over each exclusion pattern to check for a match
        for ( String excludePath : excludePaths ) {
            // Get the normalized and lowercase exclusion pattern
            String normalizedExclude = normalizeExcludedPath( excludePath ).toLowerCase();

            // Check if the exclusion pattern matches the exact path (case-insensitive)
            if ( normalizedExclude.equals( relativeCheckPathString ) ) {
                return true; // The path is excluded from the scan
            }

            // Check if the exclusion pattern matches the parent folder path (case-insensitive)
            if ( relativeCheckPathParent != null && normalizedExclude.equals( relativeCheckPathParentString ) ) {
                return true; // The path is excluded from the scan
            }

            // Check if the exclusion pattern matches a parent folder path (case-insensitive)
            if ( relativeCheckPathParent != null &&
                    relativeCheckPathString.startsWith( normalizedExclude + File.separator ) ) {
                return true; // The path is excluded from the scan
            }
        }

        return false; // The path is not excluded from the scan
    }

}
