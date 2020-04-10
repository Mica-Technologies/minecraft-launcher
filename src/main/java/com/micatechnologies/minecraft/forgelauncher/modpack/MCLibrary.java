package com.micatechnologies.minecraft.forgelauncher.modpack;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * Class representing a Minecraft game library that can be downloaded from remote.
 *
 * @author Mica Technologies/hawka97
 * @version 1.0
 * @see MCRemoteFile
 */
class MCLibrary extends MCRemoteFile {

    /**
     * Boolean flag for strict OS checking, used with {@link #applicableOSes}
     */
    private final boolean             strictOSCheck;

    /**
     * List of applicable OSes, used with {@link #strictOSCheck}
     */
    private final ArrayList< String > applicableOSes;

    /**
     * Boolean flag representing if library is a native of another
     */
    private final boolean             isNativeLib;

    /**
     * Create an MCLibrary object with the given remote and local file information and applicability
     * information.
     *
     * @param remoteURL      library remote URL
     * @param localPath      library local path
     * @param sha1Hash       library SHA-1 hash
     * @param strictOSCheck  strict OS checking flag
     * @param applicableOSes strict OS list
     * @param isNativeLib    native library flag
     *
     * @since 1.0
     */
    MCLibrary( String remoteURL, String localPath, String sha1Hash, boolean strictOSCheck,
               ArrayList< String > applicableOSes, boolean isNativeLib ) {
        // Setup remote file configuration
        super( remoteURL, localPath, sha1Hash );

        // Store applicability information
        this.strictOSCheck = strictOSCheck;
        this.applicableOSes = applicableOSes;
        this.isNativeLib = isNativeLib;
    }

    /**
     * Get a list of the OSes that this library applies to.
     *
     * @return list of applicable OSes
     *
     * @since 1.0
     */
    ArrayList< String > getApplicableOSes() {
        if ( this.strictOSCheck ) {
            return applicableOSes;
        }
        else {
            return new ArrayList<>( Arrays.asList( MCForgeModpackConsts.PLATFORM_WINDOWS,
                                                   MCForgeModpackConsts.PLATFORM_MACOS,
                                                   MCForgeModpackConsts.PLATFORM_UNIX ) );
        }
    }

    /**
     * Return if this MCLibrary is marked as a native library.
     *
     * @return true if native library
     */
    boolean isNativeLib() {
        return isNativeLib;
    }
}
