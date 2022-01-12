/*
 * Copyright (c) 2022 Mica Technologies
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

package com.micatechnologies.minecraft.launcher.game.manifests.loaders;

import com.micatechnologies.minecraft.launcher.files.Logger;
import com.micatechnologies.minecraft.launcher.files.ManagedRemoteFile;
import com.micatechnologies.minecraft.launcher.files.ManagedRemoteFileExtractableJar;
import com.micatechnologies.minecraft.launcher.files.hash.FileChecksum;
import com.micatechnologies.minecraft.launcher.files.hash.FileChecksumSHA1;
import com.micatechnologies.minecraft.launcher.game.manifests.MCVersionManifest;
import org.apache.commons.lang3.SystemUtils;

import java.io.File;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Utility for loading a list of valid libraries and associated natives/classifiers for the specified {@link
 * MCVersionManifest} in the desired installation folder location.
 *
 * @author Mica Technologies
 * @version 1.0
 * @since 2022.1
 */
public class MCLibraryLoader
{
    /**
     * Gets a list of valid libraries and associated natives/classifiers for the specified {@link
     * MCVersionManifest} in the desired installation folder location.
     *
     * @param installFolder   installation folder location
     * @param versionManifest {@link MCVersionManifest} to get valid libraries and natives/classifiers for
     *
     * @return list of valid libraries and associated natives/classifiers for the specified {@link
     *         MCVersionManifest} in the desired installation folder location
     *
     * @throws MalformedURLException if a library or native/classifier in the version manifest does not have a properly
     *                               formed URL and/or its URL is missing and could not be interpreted
     * @since 1.0
     */
    public List< ManagedRemoteFile > getValidLibraryList( String installFolder,
                                                          MCVersionManifest versionManifest )
    throws MalformedURLException
    {
        // Build list of completed managed file objects
        List< ManagedRemoteFile > managedRemoteFileList = new ArrayList<>();

        // Get libraries from version manifest
        List< MCVersionManifest.Library > libraryObjects = versionManifest.getLibraries();

        // Loop through each library and add managed file object to list if applicable
        for ( MCVersionManifest.Library libraryObject : libraryObjects ) {
            // Check rules for applicability to OS
            List< MCVersionManifest.Library.LibraryRule > libraryRules = libraryObject.getRules();
            boolean isOsApplicable;
            if ( libraryRules != null && libraryRules.size() > 0 ) {

                // Loop through each rule and process
                boolean hasGenericAllow = false;
                boolean hasGenericDisallow = false;
                boolean hasOsSpecificAllow = false;
                boolean hasOsSpecificDisallow = false;
                for ( MCVersionManifest.Library.LibraryRule libraryRule : libraryRules ) {
                    // Check for generic allow block
                    if ( libraryRule.getAction().equalsIgnoreCase( "allow" ) && libraryRule.getOs() == null ) {
                        hasGenericAllow = true;
                    }
                    // Check for generic disallow block
                    else if ( libraryRule.getAction().equalsIgnoreCase( "disallow" ) && libraryRule.getOs() == null ) {
                        hasGenericDisallow = true;
                    }
                    // Check for OS-specific allow block
                    else if ( libraryRule.getAction().equalsIgnoreCase( "allow" ) && libraryRule.getOs() != null ) {
                        hasOsSpecificAllow = doesLibraryRulesOsMatch( libraryRule );
                    }
                    // Check for OS-specific disallow block
                    else if ( libraryRule.getAction().equalsIgnoreCase( "disallow" ) && libraryRule.getOs() != null ) {
                        hasOsSpecificDisallow = doesLibraryRulesOsMatch( libraryRule );
                    }

                }

                // Process applicability
                if ( hasOsSpecificDisallow ) {
                    // Library is not applicable if an OS-specific disallow is present
                    isOsApplicable = false;
                }
                else if ( hasOsSpecificAllow ) {
                    // Library is applicable if an OS-specific allow is present
                    isOsApplicable = true;
                }
                else if ( hasGenericDisallow ) {
                    // Library is not applicable if a generic disallow is present
                    isOsApplicable = false;
                }
                else {
                    // Library is applicable if a generic allow is present, otherwise defaults to not applicable
                    isOsApplicable = hasGenericAllow;
                }
            }
            else {
                // Library is marked applicable if no rules list is present (empty list or populated)
                isOsApplicable = true;
            }

            // Build new managed file object for artifact
            MCVersionManifest.Library.LibraryDownloads.LibraryDownloadsArtifact libraryArtifact
                    = libraryObject.getDownloads().getArtifact();
            if ( isOsApplicable && libraryArtifact != null ) {
                managedRemoteFileList.add( buildLibraryManagedFileObject( installFolder, libraryArtifact, null ) );
            }

            // Build new managed file object(s) for natives/classifiers
            MCVersionManifest.Library.LibraryDownloads.LibraryDownloadsClassifiers libraryClassifiers
                    = libraryObject.getDownloads().getClassifiers();
            MCVersionManifest.Library.LibraryNatives libraryNatives = libraryObject.getNatives();
            if ( isOsApplicable && libraryNatives != null && libraryClassifiers != null ) {

                // Get classifier name from natives list
                MCVersionManifest.Library.LibraryDownloads.LibraryDownloadsArtifact libraryClassifier = null;
                if ( SystemUtils.IS_OS_WINDOWS ) {
                    // Check if Windows natives present
                    if ( libraryNatives.getWindows() != null ) {
                        libraryClassifier = libraryClassifiers.getNativesWindows();
                    }
                }
                else if ( SystemUtils.IS_OS_MAC ) {
                    // Check if macOS natives present
                    if ( libraryNatives.getMacos() != null ) {
                        libraryClassifier = libraryClassifiers.getNativesMacOS();
                    }
                    // Check if OS X natives present
                    else if ( libraryNatives.getOsx() != null ) {
                        libraryClassifier = libraryClassifiers.getNativesOSX();
                    }
                }
                else {
                    // Check if Linux natives present
                    if ( libraryNatives.getLinux() != null ) {
                        libraryClassifier = libraryClassifiers.getNativesLinux();
                    }
                }

                if ( libraryClassifier != null ) {
                    MCVersionManifest.Library.LibraryExtract libraryExtract = libraryObject.getExtract();
                    if ( libraryExtract != null ) {
                        managedRemoteFileList.add( buildLibraryManagedFileObject( installFolder, libraryClassifier,
                                                                                  libraryExtract.getExclude() ) );
                    }
                    else {
                        managedRemoteFileList.add(
                                buildLibraryManagedFileObject( installFolder, libraryClassifier, null ) );
                    }
                }
                else {
                    Logger.logDebug( "Skipping native(s)/classifier(s) for library [" +
                                             libraryObject.getName() +
                                             "] because " +
                                             "no applicable native(s)/classifier(s) were found!" );
                }
            }
            else if ( isOsApplicable && ( libraryNatives != null || libraryClassifiers == null ) ) {
                Logger.logWarning( "A library was encountered during loading with only one of the required " +
                                           "classifiers/natives objects! [LIBRARY: " +
                                           libraryObject.getName() +
                                           "]" );
            }
        }
        return managedRemoteFileList;
    }

    /**
     * Inspects a {@link MCVersionManifest.Library.LibraryRule} object and returns a boolean indicating if the
     * current operating system is targeted by the rule.
     *
     * @param libraryRule {@link MCVersionManifest.Library.LibraryRule} to inspect
     *
     * @return true if the library rule targets the current operating system, false otherwise
     *
     * @since 1.0
     */
    private boolean doesLibraryRulesOsMatch( MCVersionManifest.Library.LibraryRule libraryRule ) {
        String currentOsRegex = getOsNameRegex();
        boolean doesMatch = false;
        if ( libraryRule.getOs().getName().matches( currentOsRegex ) ) {
            if ( libraryRule.getOs().getVersion() == null &&
                    libraryRule.getOs().getArch() != null &&
                    SystemUtils.OS_ARCH.matches( libraryRule.getOs().getArch() ) ) {
                doesMatch = true;
            }
            else if ( libraryRule.getOs().getVersion() != null &&
                    SystemUtils.OS_VERSION.matches( libraryRule.getOs().getVersion() ) &&
                    libraryRule.getOs().getArch() != null &&
                    SystemUtils.OS_ARCH.matches( libraryRule.getOs().getArch() ) ) {
                doesMatch = true;
            }
            else if ( libraryRule.getOs().getArch() == null &&
                    libraryRule.getOs().getVersion() != null &&
                    SystemUtils.OS_VERSION.matches( libraryRule.getOs().getVersion() ) ) {
                doesMatch = true;
            }
            else if ( libraryRule.getOs().getVersion() == null && libraryRule.getOs().getArch() == null ) {
                doesMatch = true;
            }
            else {
                Logger.logDebug( "The following library rule was not applicable to the system: [NAME: " +
                                         libraryRule.getOs() +
                                         ", VERSION: " +
                                         libraryRule.getOs().getVersion() +
                                         ", ARCH: " +
                                         libraryRule.getOs().getArch() +
                                         ", ACTION: " +
                                         libraryRule.getAction() +
                                         "]. The following is applicable to this system: [NAME: " +
                                         currentOsRegex +
                                         ", " +
                                         "VERSION: " +
                                         SystemUtils.OS_VERSION +
                                         ", ARCH:" +
                                         SystemUtils.OS_ARCH +
                                         ", ACTION: " +
                                         "allow|disallow]." );
            }
        }
        return doesMatch;
    }

    /**
     * Gets a regex string which can be used to match the current running operating system.
     *
     * @return regex string which can be used to match the current running operating system
     *
     * @since 1.0
     */
    private String getOsNameRegex() {
        // Build regex for OS name
        String currentOsRegex;
        if ( SystemUtils.IS_OS_WINDOWS ) {
            currentOsRegex = "windows";
        }
        else if ( SystemUtils.IS_OS_MAC ) {
            currentOsRegex = "osx|macos";
        }
        else {
            currentOsRegex = "linux";
        }
        return currentOsRegex;
    }

    /**
     * Builds a managed file object for the specified library download object in the desired installation folder.
     *
     * @param installFolder     installation folder location
     * @param downloadObject    library download object to create managed file object for
     * @param extractionExclude list of files and folder paths to exclude during extraction (optional)
     *
     * @return managed file object for the specified library download object in the desired installation folder
     *
     * @throws MalformedURLException if the specified library download object does not have a properly formed URL and/or
     *                               its URL is missing and could not be interpreted
     * @since 1.0
     */
    private ManagedRemoteFile buildLibraryManagedFileObject( String installFolder,
                                                             MCVersionManifest.Library.LibraryDownloads.LibraryDownloadsArtifact downloadObject,
                                                             List< String > extractionExclude )
    throws MalformedURLException
    {
        // Build paths and hash for object
        String fileLocalPath = installFolder + File.separator + "libraries" + File.separator + downloadObject.getPath();
        String fileRemotePath = downloadObject.getUrl();
        FileChecksum fileHash = new FileChecksumSHA1( downloadObject.getSha1() );

        // Build managed file and add to list
        ManagedRemoteFile managedRemoteFile;
        if ( extractionExclude != null ) {
            String extractionFolder = installFolder + File.separator + "bin" + File.separator + "natives";
            managedRemoteFile = new ManagedRemoteFileExtractableJar( fileLocalPath, fileRemotePath, fileHash,
                                                                     extractionExclude, extractionFolder );
            // TODO: specify native extraction path
        }
        else {
            managedRemoteFile = new ManagedRemoteFile( fileLocalPath, fileRemotePath, fileHash );
        }
        return managedRemoteFile;
    }
}
