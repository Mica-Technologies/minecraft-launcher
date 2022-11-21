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

package com.micatechnologies.minecraft.launcher.game.manifests;

import com.google.gson.JsonArray;
import com.google.gson.annotations.SerializedName;

import java.util.List;

/**
 * The information manifest for a specific Minecraft version containing the library and other download information for
 * the game.
 *
 * @author Mica Technologies
 * @version 1.0
 * @since 2022.1
 */
public class MCVersionManifest
{
    ///region: Instance Fields

    /**
     * Arguments for launching Minecraft for game version (if version is 1.13 or newer). This field replaces the
     * previous field ({@link #minecraftArguments}).
     *
     * @since 1.0
     */
    private Arguments arguments;

    /**
     * Information about the asset index for game version.
     *
     * @since 1.0
     */
    private AssetIndex assetIndex;

    /**
     * Version number of assets for game version.
     *
     * @since 1.0
     */
    private String assets;

    /**
     * Version compliance level. (If 0, the native Minecraft launcher warns the user about the version not being recent
     * enough to support the latest player features. Otherwise, it is 1.)
     *
     * @since 1.0
     */
    private int complianceLevel;

    /**
     * Information about the Minecraft game client/server downloads for game version.
     *
     * @since 1.0
     */
    private Downloads downloads;

    /**
     * Name of game version (i.e. 1.12.2, 1.14, etc).
     *
     * @since 1.0
     */
    private String id;

    /**
     * Information about the required Java version for game version.
     *
     * @since 1.0
     */
    private JavaVersion javaVersion;

    /**
     * List of information about the libraries for game version.
     *
     * @since 1.0
     */
    private List< Library > libraries;

    /**
     * The logging information of Minecraft for game version.
     *
     * @since 1.0
     */
    private Logging logging;

    /**
     * Arguments for launching Minecraft for game version (if version is less than 1.13). This field has been replaced
     * by the {@link #arguments} field.
     *
     * @since 1.0
     */
    private String minecraftArguments;

    /**
     * Main class for game version.
     *
     * @since 1.0
     */
    private String mainClass;

    /**
     * Minimum launcher version required for game version.
     *
     * @since 1.0
     */
    private int minimumLauncherVersion;

    /**
     * Release time of game version.
     *
     * @since 1.0
     */
    private String releaseTime;

    /**
     * Last update time of game version.
     *
     * @since 1.0
     */
    private String time;

    /**
     * The type of the game version (release, snapshot, old_beta, old_alpha).
     *
     * @since 1.0
     */
    private String type;

    /**
     * Gets the arguments for launching Minecraft for game version (if version is 1.13 or newer). This accessor replaces
     * the previous getter ({@link #getMinecraftArguments()}).
     *
     * @return arguments for launching Minecraft for game version (if version is 1.13 or newer)
     *
     * @since 1.0
     */
    public Arguments getArguments() {
        return arguments;
    }

    /**
     * Gets the arguments for launching Minecraft for game version (if version is less than 1.13). This accessor has
     * been replaced by the {@link #getArguments()} getter.
     *
     * @return arguments for launching Minecraft for game version (if version is less than 1.13)
     *
     * @since 1.0
     */
    public String getMinecraftArguments() {
        return minecraftArguments;
    }

    /**
     * Gets the version compliance level. (If 0, the native Minecraft launcher warns the user about the version not
     * being recent enough to support the latest player features. Otherwise, it is 1.)
     *
     * @return version compliance level
     *
     * @since 1.0
     */
    public int getComplianceLevel() {
        return complianceLevel;
    }

    /**
     * Gets the list of information about the libraries for game version.
     *
     * @return list of information about the libraries for game version
     *
     * @since 1.0
     */
    public List< Library > getLibraries() {
        return libraries;
    }

    /**
     * Gets the name of the version (i.e. 1.12.2, 1.14, etc).
     *
     * @return name of the version (i.e. 1.12.2, 1.14, etc)
     *
     * @since 1.0
     */
    public String getId() {
        return id;
    }

    /**
     * Gets the type of the game version (release, snapshot, old_beta, old_alpha).
     *
     * @return type of the game version (release, snapshot, old_beta, old_alpha)
     *
     * @since 1.0
     */
    public String getType() {
        return type;
    }

    /**
     * Gets the last update time of game version.
     *
     * @return last update time of game version
     *
     * @since 1.0
     */
    public String getTime() {
        return time;
    }

    /**
     * Gets the information about the asset index for game version.
     *
     * @return information about the asset index for game version
     *
     * @since 1.0
     */
    public AssetIndex getAssetIndex() {
        return assetIndex;
    }

    /**
     * Gets the release time of game version.
     *
     * @return release time of game version
     *
     * @since 1.0
     */
    public String getReleaseTime() {
        return releaseTime;
    }

    /**
     * Gets the minimum launcher version required for game version.
     *
     * @return minimum launcher version required for game version
     *
     * @since 1.0
     */
    public int getMinimumLauncherVersion() {
        return minimumLauncherVersion;
    }

    /**
     * Gets the main class for game version.
     *
     * @return main class for game version
     *
     * @since 1.0
     */
    public String getMainClass() {
        return mainClass;
    }

    /**
     * Gets the version number of assets for game version.
     *
     * @return version number of assets for game version
     *
     * @since 1.0
     */
    public String getAssets() {
        return assets;
    }

    /**
     * Gets the information about the Minecraft game client/server downloads for game version.
     *
     * @return information about the Minecraft game client/server downloads for game version
     *
     * @since 1.0
     */
    public Downloads getDownloads() {
        return downloads;
    }

    /**
     * Gets the information about the required Java version for game version.
     *
     * @return information about the required Java version for game version
     *
     * @since 1.0
     */
    public JavaVersion getJavaVersion() {
        return javaVersion;
    }

    /**
     * Gets the logging information of Minecraft for game version.
     *
     * @return logging information of Minecraft for game version
     *
     * @since 1.0
     */
    public Logging getLogging() {
        return logging;
    }

    /**
     * Class object for storing arguments for launching Minecraft for game version (if version is 1.13 or newer).
     *
     * @author Mica Technologies
     * @version 1.0
     * @implNote Adapted from https://minecraft.fandom.com/wiki/Client.json
     * @since 1.0
     */
    public static class Arguments
    {
        /**
         * JSON array of game arguments (string or conditional string arguments)
         *
         * @since 1.0
         */
        private JsonArray game;

        /**
         * JSON array of JVM arguments (string or conditional string arguments)
         *
         * @since 1.0
         */
        private JsonArray jvm;

        /**
         * Gets the JSON array of game arguments (string or conditional arguments).
         *
         * @return JSON array of game arguments (string or conditional arguments)
         *
         * @since 1.0
         */
        public JsonArray getGame() {
            return game;
        }

        /**
         * Gets the JSON array of game arguments (string or conditional arguments).
         *
         * @return JSON array of game arguments (string or conditional arguments)
         *
         * @since 1.0
         */
        public JsonArray getJvm() {
            return jvm;
        }
    }

    /**
     * Class object for storing information about the asset index for game version.
     *
     * @author Mica Technologies
     * @version 1.0
     * @implNote Adapted from https://minecraft.fandom.com/wiki/Client.json
     * @since 1.0
     */
    public static class AssetIndex
    {
        /**
         * The assets version.
         *
         * @since 1.0
         */
        private String id;

        /**
         * The SHA-1 hash of the assets file.
         *
         * @since 1.0
         */
        private String sha1;

        /**
         * The size of the version.
         *
         * @since 1.0
         */
        private int size;

        /**
         * The total size of the version.
         *
         * @since 1.0
         */
        private int totalSize;

        /**
         * The URL for downloading the assets.
         *
         * @since 1.0
         */
        private String url;

        /**
         * Gets the assets version.
         *
         * @since 1.0
         */
        public String getId() {
            return id;
        }

        /**
         * Gets the SHA-1 hash of the assets file.
         *
         * @since 1.0
         */
        public String getSha1() {
            return sha1;
        }

        /**
         * Gets the size of the version.
         *
         * @since 1.0
         */
        public int getSize() {
            return size;
        }

        /**
         * Gets the total size of the version.
         *
         * @since 1.0
         */
        public int getTotalSize() {
            return totalSize;
        }

        /**
         * Gets the URL for downloading the assets .
         *
         * @since 1.0
         */
        public String getUrl() {
            return url;
        }
    }

    /**
     * Class object for storing information about the Minecraft game client/server downloads for game version.
     *
     * @author Mica Technologies
     * @version 1.0
     * @implNote Adapted from https://minecraft.fandom.com/wiki/Client.json
     * @since 1.0
     */
    public static class Downloads
    {
        /**
         * The information for downloading the Minecraft client for game version.
         *
         * @since 1.0
         */
        private Download client;

        /**
         * The information for downloading the Minecraft client mappings for game version.
         *
         * @since 1.0
         */
        private Download client_mappings;

        /**
         * The information for downloading the Minecraft server for game version.
         *
         * @since 1.0
         */
        private Download server;

        /**
         * The information for downloading the Minecraft server mappings for game version.
         *
         * @since 1.0
         */
        private Download server_mappings;

        /**
         * Gets the information for downloading the Minecraft client for game version.
         *
         * @return information for downloading the Minecraft client for game version
         *
         * @since 1.0
         */
        public Download getClient() {
            return client;
        }

        /**
         * The information for downloading the Minecraft client mappings for game version.
         *
         * @return information for downloading the Minecraft client mappings for game version
         *
         * @since 1.0
         */
        public Download getClient_mappings() {
            return client_mappings;
        }

        /**
         * Gets the information for downloading the Minecraft server for game version.
         *
         * @return information for downloading the Minecraft server for game version
         *
         * @since 1.0
         */
        public Download getServer() {
            return server;
        }

        /**
         * The information for downloading the Minecraft server mappings for game version.
         *
         * @return information for downloading the Minecraft server mappings for game version
         *
         * @since 1.0
         */
        public Download getServer_mappings() {
            return server_mappings;
        }

        /**
         * Class object for storing information about a specific Minecraft game client/server download for game
         * version.
         *
         * @author Mica Technologies
         * @version 1.0
         * @implNote Adapted from https://minecraft.fandom.com/wiki/Client.json
         * @since 1.0
         */
        public static class Download
        {
            /**
             * The SHA-1 hash of the specific Minecraft game client/server download for game version.
             *
             * @since 1.0
             */
            private String sha1;

            /**
             * The size of the specific Minecraft game client/server download for game version.
             *
             * @since 1.0
             */
            private int size;

            /**
             * The URL of the specific Minecraft game client/server download for game version.
             *
             * @since 1.0
             */
            private String url;

            /**
             * Gets the SHA-1 hash of the specific Minecraft game client/server download for game version.
             *
             * @return SHA-1 hash of the specific Minecraft game client/server download for game version
             *
             * @since 1.0
             */
            public String getSha1() {
                return sha1;
            }

            /**
             * Gets the size of the specific Minecraft game client/server download for game version.
             *
             * @return size of the specific Minecraft game client/server download for game version
             *
             * @since 1.0
             */
            public int getSize() {
                return size;
            }

            /**
             * Gets the URL of the specific Minecraft game client/server download for game version.
             *
             * @return URL of the specific Minecraft game client/server download for game version
             *
             * @since 1.0
             */
            public String getUrl() {
                return url;
            }
        }
    }

    /**
     * Class object for storing information about the required Java version for game version.
     *
     * @author Mica Technologies
     * @version 1.0
     * @implNote Adapted from https://minecraft.fandom.com/wiki/Client.json
     * @since 1.0
     */
    public static class JavaVersion
    {
        /**
         * The Java runtime component to use for game version (jre-legacy, java-runtime-alpha).
         *
         * @since 1.0
         */
        private String component;

        /**
         * The Java runtime major version to use for game version.
         *
         * @since 1.0
         */
        private int majorVersion;

        /**
         * Gets the Java runtime component to use for game version (jre-legacy, java-runtime-alpha).
         *
         * @return Java runtime component to use for game version (jre-legacy, java-runtime-alpha)
         *
         * @since 1.0
         */
        public String getComponent() {
            return component;
        }

        /**
         * Gets the Java runtime major version to use for game version.
         *
         * @return Java runtime major version to use for game version
         *
         * @since 1.0
         */
        public int getMajorVersion() {
            return majorVersion;
        }
    }

    /**
     * Class object for storing information about a library for game version.
     *
     * @author Mica Technologies
     * @version 1.0
     * @implNote Adapted from https://minecraft.fandom.com/wiki/Client.json
     * @since 1.0
     */
    public static class Library
    {
        /**
         * The information for downloading the library for game version.
         *
         * @since 1.0
         */
        private LibraryDownloads downloads;

        /**
         * The name of the library for game version.
         *
         * @since 1.0
         */
        private String name;

        /**
         * The URL of the Maven repository for the library for game version.
         *
         * @since 1.0
         */
        private String url;

        /**
         * The information about native libraries (in C) bundled with the library for game version.
         *
         * @since 1.0
         */
        private LibraryNatives natives;

        /**
         * The information about excluded files during library extraction.
         *
         * @since 1.0
         */
        private LibraryExtract extract;

        /**
         * The information about the applicability of the library for game version on certain systems.
         *
         * @since 1.0
         */
        private List< LibraryRule > rules;

        /**
         * Gets the information for downloading the library for game version.
         *
         * @return information for downloading the library for game version
         *
         * @since 1.0
         */
        public LibraryDownloads getDownloads() {
            return downloads;
        }

        /**
         * Gets the name of the library for game version.
         *
         * @return name of the library for game version
         *
         * @since 1.0
         */
        public String getName() {
            return name;
        }

        /**
         * Gets the URL of the Maven repository for the library for game version.
         *
         * @return URL of the Maven repository for the library for game version
         *
         * @since 1.0
         */
        public String getUrl() {
            return url;
        }

        /**
         * Gets the information about native libraries (in C) bundled with the library for game version.
         *
         * @return information about native libraries (in C) bundled with the library for game version
         *
         * @since 1.0
         */
        public LibraryNatives getNatives() {
            return natives;
        }

        /**
         * Gets the information about excluded files during library extraction.
         *
         * @return information about excluded files during library extraction
         *
         * @since 1.0
         */
        public LibraryExtract getExtract() {
            return extract;
        }

        /**
         * Gets the information about the applicability of the library for game version on certain systems.
         *
         * @return information about the applicability of the library for game version on certain systems
         *
         * @since 1.0
         */
        public List< LibraryRule > getRules() {
            return rules;
        }

        /**
         * Class object for storing information about downloading the library for game version.
         *
         * @author Mica Technologies
         * @version 1.0
         * @implNote Adapted from https://minecraft.fandom.com/wiki/Client.json
         * @since 1.0
         */
        public static class LibraryDownloads
        {
            /**
             * The information for downloading a library artifact for the library for game version.
             *
             * @since 1.0
             */
            private LibraryDownloadsArtifact artifact;

            /**
             * The information for downloading library classifiers for the library for game version.
             *
             * @since 1.0
             */
            private LibraryDownloadsClassifiers classifiers;

            /**
             * Gets the information for downloading a library artifact for the library for game version.
             *
             * @return information for downloading a library artifact for the library for game version
             *
             * @since 1.0
             */
            public LibraryDownloadsArtifact getArtifact() {
                return artifact;
            }

            /**
             * Gets the information for downloading library classifiers for the library for game version.
             *
             * @return information for downloading library classifiers for the library for game version.
             *
             * @since 1.0
             */
            public LibraryDownloadsClassifiers getClassifiers() {
                return classifiers;
            }

            /**
             * Class object for storing download information of a library artifact for the associated library for game
             * version.
             *
             * @author Mica Technologies
             * @version 1.0
             * @implNote Adapted from https://minecraft.fandom.com/wiki/Client.json
             * @since 1.0
             */
            public static class LibraryDownloadsArtifact
            {
                /**
                 * The local file path of the library artifact.
                 *
                 * @since 1.0
                 */
                private String path;

                /**
                 * The SHA-1 hash of the library artifact.
                 *
                 * @since 1.0
                 */
                private String sha1;

                /**
                 * The size of the library artifact.
                 *
                 * @since 1.0
                 */
                private int size;

                /**
                 * The download URL of the library artifact.
                 *
                 * @since 1.0
                 */
                private String url;

                /**
                 * Gets the local file path of the bundled native library.
                 *
                 * @return local file path of the bundled native library
                 *
                 * @since 1.0
                 */
                public String getPath() {
                    return path;
                }

                /**
                 * Gets the SHA-1 hash of the bundled native library.
                 *
                 * @return SHA-1 hash of the bundled native library
                 *
                 * @since 1.0
                 */
                public String getSha1() {
                    return sha1;
                }

                /**
                 * Gets the size of the bundled native library.
                 *
                 * @return size of the bundled native library
                 *
                 * @since 1.0
                 */
                public int getSize() {
                    return size;
                }

                /**
                 * Gets the download URL of the bundled native library.
                 *
                 * @return download URL of the bundled native library
                 *
                 * @since 1.0
                 */
                public String getUrl() {
                    return url;
                }
            }

            /**
             * Class object for storing download information of a library classifiers for the associated library for
             * game version.
             *
             * @author Mica Technologies
             * @version 1.0
             * @implNote Adapted from https://minecraft.fandom.com/wiki/Client.json
             * @since 1.0
             */
            public static class LibraryDownloadsClassifiers
            {
                /**
                 * The download information for a Linux native library (in C) bundled with the associated library for
                 * game version. This field is only present when a native library (in C) is required for Linux.
                 *
                 * @since 1.0
                 */
                @SerializedName( "natives-linux" )
                private LibraryDownloadsArtifact nativesLinux;

                /**
                 * The download information for an Apple OS X/macOS native library (in C) bundled with the associated
                 * library for game version. This field is only present when a native library (in C) is required for
                 * Apple OS X/macOS.
                 *
                 * @since 1.0
                 */
                @SerializedName( "natives-osx" )
                private LibraryDownloadsArtifact nativesOSX;

                /**
                 * The download information for an Apple macOS/OS X native library (in C) bundled with the associated
                 * library for game version. This field is only present when a native library (in C) is required for
                 * Apple macOS/OS X.
                 *
                 * @since 1.0
                 */
                @SerializedName( "natives-macos" )
                private LibraryDownloadsArtifact nativesMacOS;

                /**
                 * The download information for a Windows native library (in C) bundled with the associated library for
                 * game version. This field is only present when a native library (in C) is required for Windows.
                 *
                 * @since 1.0
                 */
                @SerializedName( "natives-windows" )
                private LibraryDownloadsArtifact nativesWindows;

                /**
                 * The download information for the sources of the associated library for game version. This field is
                 * not always present.
                 *
                 * @since 1.0
                 */
                private LibraryDownloadsArtifact sources;

                /**
                 * The download information for the Javadocs of the associated library for game version. This field is
                 * not always present.
                 *
                 * @since 1.0
                 */
                private LibraryDownloadsArtifact javadoc;

                /**
                 * Gets the download information for a Linux native library (in C) bundled with the associated library
                 * for game version. This field is only present when a native library (in C) is required for Linux.
                 *
                 * @return download information for a Linux native library (in C) bundled with the associated library
                 *         for game version
                 *
                 * @since 1.0
                 */
                public LibraryDownloadsArtifact getNativesLinux() {
                    return nativesLinux;
                }

                /**
                 * Gets the download information for an Apple OS X/macOS native library (in C) bundled with the
                 * associated library for game version. This field is only present when a native library (in C) is
                 * required for Apple OS X/macOS.
                 *
                 * @return download information for an Apple OS X/macOS native library (in C) bundled with the
                 *         associated library for game version
                 *
                 * @since 1.0
                 */
                public LibraryDownloadsArtifact getNativesOSX() {
                    return nativesOSX;
                }

                /**
                 * Gets the download information for an Apple macOS/OS X native library (in C) bundled with the
                 * associated library for game version. This field is only present when a native library (in C) is
                 * required for Apple macOS/OS X.
                 *
                 * @return download information for an Apple macOS/OS X native library (in C) bundled with the
                 *         associated library for game version
                 *
                 * @since 1.0
                 */
                public LibraryDownloadsArtifact getNativesMacOS() {
                    return nativesMacOS;
                }

                /**
                 * Gets the download information for a Windows native library (in C) bundled with the associated library
                 * for game version. This field is only present when a native library (in C) is required for Windows.
                 *
                 * @return download information for a Windows native library (in C) bundled with the associated library
                 *         for game version
                 *
                 * @since 1.0
                 */
                public LibraryDownloadsArtifact getNativesWindows() {
                    return nativesWindows;
                }

                /**
                 * Gets the download information for the sources of the associated library for game version. This field
                 * is not always present.
                 *
                 * @return download information for the sources of the associated library for game version
                 *
                 * @since 1.0
                 */
                public LibraryDownloadsArtifact getSources() {
                    return sources;
                }

                /**
                 * Gets the download information for the Javadocs of the associated library for game version. This field
                 * is not always present.
                 *
                 * @return download information for the Javadocs of the associated library for game version
                 *
                 * @since 1.0
                 */
                public LibraryDownloadsArtifact getJavadoc() {
                    return javadoc;
                }
            }
        }

        /**
         * Class object for storing information about the applicability of the library for game version on certain
         * systems.
         *
         * @author Mica Technologies
         * @version 1.0
         * @implNote Adapted from https://minecraft.fandom.com/wiki/Client.json
         * @since 1.0
         */
        public static class LibraryRule
        {
            /**
             * The rule action (allow/deny) for the specified operating system, version, and architecture.
             *
             * @since 1.0
             */
            private String action;

            /**
             * The information about which specific operating system, version, and architecture the rule applies to.
             *
             * @since 1.0
             */
            private LibraryRuleOS os;

            /**
             * Gets the rule action (allow/deny) for the specified operating system, version, and architecture.
             *
             * @return rule action (allow/deny)
             *
             * @since 1.0
             */
            public String getAction() {
                return action;
            }

            /**
             * Gets the information about which specific operating system, version, and architecture the rule applies
             * to.
             *
             * @since 1.0
             */
            public LibraryRuleOS getOs() {
                return os;
            }

            /**
             * Class object for storing information about a specific operating system, version, and architecture.
             *
             * @author Mica Technologies
             * @version 1.0
             * @implNote Adapted from https://minecraft.fandom.com/wiki/Client.json
             * @since 1.0
             */
            public static class LibraryRuleOS
            {
                /**
                 * The name of the operating system for library rule.
                 *
                 * @since 1.0
                 */
                private String name;

                /**
                 * The version of the operating system for library rule.
                 *
                 * @since 1.0
                 */
                private String version;

                /**
                 * The architecture of the operating system for library rule.
                 *
                 * @since 1.0
                 */
                private String arch;

                /**
                 * Gets the name of the operating system for library rule.
                 *
                 * @return name of the operating system for library rule
                 *
                 * @since 1.0
                 */
                public String getName() {
                    return name;
                }

                /**
                 * Gets the version of the operating system for library rule.
                 *
                 * @return version of the operating system for library rule
                 *
                 * @since 1.0
                 */
                public String getVersion() {
                    return version;
                }

                /**
                 * Gets the architecture of the operating system for library rule.
                 *
                 * @return architecture of the operating system for library rule
                 *
                 * @since 1.0
                 */
                public String getArch() {
                    return arch;
                }
            }
        }

        /**
         * Class object for storing information about excluded files during library extraction.
         *
         * @author Mica Technologies
         * @version 1.0
         * @implNote Adapted from https://minecraft.fandom.com/wiki/Client.json
         * @since 1.0
         */
        public static class LibraryExtract
        {
            /**
             * The list of files to exclude during library extraction.
             *
             * @since 1.0
             */
            private List< String > exclude;

            /**
             * Gets the list of files to exclude during library extraction.
             *
             * @return list of files to exclude during library extraction
             *
             * @since 1.0
             */
            public List< String > getExclude() {
                return exclude;
            }
        }

        /**
         * Class object for storing information about accessing native library (in C) information bundled with the
         * library for game version.
         *
         * @author Mica Technologies
         * @version 1.0
         * @implNote Adapted from https://minecraft.fandom.com/wiki/Client.json
         * @since 1.0
         */
        public static class LibraryNatives
        {
            /**
             * The string key for accessing download information for a Linux native library (in C) bundled with the
             * associated library for game version.
             *
             * @since 1.0
             */
            private String linux;

            /**
             * The string key for accessing download information for an Apple OS X/macOS native library (in C) bundled
             * with the associated library for game version.
             *
             * @since 1.0
             */
            private String osx;

            /**
             * The string key for accessing download information for an Apple macOS/OS X native library (in C) bundled
             * with the associated library for game version.
             *
             * @since 1.0
             */
            private String macos;

            /**
             * The string key for accessing download information for a Windows native library (in C) bundled with the
             * associated library for game version.
             *
             * @since 1.0
             */
            private String windows;

            /**
             * Gets the string key for accessing download information for a Linux native library (in C) bundled with the
             * associated library for game version.
             *
             * @return download information for a Linux native library (in C) bundled with the associated library for
             *         game version
             *
             * @since 1.0
             */
            public String getLinux() {
                return linux;
            }

            /**
             * Gets the string key for accessing download information for an Apple OS X/macOS native library (in C)
             * bundled with the associated library for game version.
             *
             * @return download information for an Apple OS X/macOS native library (in C) bundled with the associated
             *         library for game version
             *
             * @since 1.0
             */
            public String getOsx() {
                return osx;
            }

            /**
             * Gets the string key for accessing download information for an Apple macOS/OS X native library (in C)
             * bundled with the associated library for game version.
             *
             * @return download information for an Apple macOS/OS X native library (in C) bundled with the associated
             *         library for game version
             *
             * @since 1.0
             */
            public String getMacos() {
                return macos;
            }

            /**
             * Gets the string key for accessing download information for a Windows native library (in C) bundled with
             * the associated library for game version.
             *
             * @return download information for a Windows native library (in C) bundled with the associated library for
             *         game version
             *
             * @since 1.0
             */
            public String getWindows() {
                return windows;
            }
        }
    }

    /**
     * Class object for storing logging information of Minecraft for game version.
     *
     * @author Mica Technologies
     * @version 1.0
     * @implNote Adapted from https://minecraft.fandom.com/wiki/Client.json
     * @since 1.0
     */
    public static class Logging
    {
        /**
         * Logging configuration object for Minecraft client for game version.
         *
         * @since 1.0
         */
        private LoggingConfig client;

        /**
         * Logging configuration object for Minecraft server for game version.
         *
         * @since 1.0
         */
        private LoggingConfig server;

        /**
         * Gets the logging configuration object for Minecraft client for game version.
         *
         * @return logging configuration object for Minecraft client for game version
         *
         * @since 1.0
         */
        public LoggingConfig getClient() {
            return client;
        }

        /**
         * Gets the logging configuration object for Minecraft server for game version.
         *
         * @return logging configuration object for Minecraft server for game version
         *
         * @since 1.0
         */
        public LoggingConfig getServer() {
            return server;
        }

        /**
         * Class object for storing logging configuration of Minecraft for game version.
         *
         * @author Mica Technologies
         * @version 1.0
         * @implNote Adapted from https://minecraft.fandom.com/wiki/Client.json
         * @since 1.0
         */
        public static class LoggingConfig
        {
            /**
             * The JVM argument for adding the log configuration.
             *
             * @since 1.0
             */
            private String argument;

            /**
             * The Log4j2 XML configuration file used by the Minecraft launcher for log screen.
             *
             * @since 1.0
             */
            private LoggingFile file;

            /**
             * The log configuration type (typically 'log4j2.xml').
             *
             * @since 1.0
             */
            private String type;

            /**
             * Gets the JVM argument for adding the log configuration.
             *
             * @return JVM argument for adding the log configuration
             *
             * @since 1.0
             */
            public String getArgument() {
                return argument;
            }

            /**
             * Gets the Log4j2 XML configuration file used by the Minecraft launcher for log screen.
             *
             * @return Log4j2 XML configuration file used by the Minecraft launcher for log screen.
             *
             * @since 1.0
             */
            public LoggingFile getFile() {
                return file;
            }

            /**
             * Gets the log configuration type (typically 'log4j2.xml').
             *
             * @return log configuration type (typically 'log4j2.xml')
             *
             * @since 1.0
             */
            public String getType() {
                return type;
            }

            /**
             * Class object for storing logging configuration file information of Minecraft for game version.
             *
             * @author Mica Technologies
             * @version 1.0
             * @implNote Adapted from https://minecraft.fandom.com/wiki/Client.json
             * @since 1.0
             */
            public static class LoggingFile
            {
                /**
                 * The ID of the logging configuration file of Minecraft for game version.
                 *
                 * @since 1.0
                 */
                private String id;

                /**
                 * The SHA-1 hash of the logging configuration file of Minecraft for game version.
                 *
                 * @since 1.0
                 */
                private String sha1;

                /**
                 * The size of the logging configuration file of Minecraft for game version.
                 *
                 * @since 1.0
                 */
                private int size;

                /**
                 * The URL of the logging configuration file of Minecraft for game version.
                 *
                 * @since 1.0
                 */
                private String url;

                /**
                 * Gets the ID of the logging configuration file of Minecraft for game version.
                 *
                 * @return ID of the logging configuration file of Minecraft for game version
                 *
                 * @since 1.0
                 */
                public String getId() {
                    return id;
                }

                /**
                 * Gets the SHA-1 hash of the logging configuration file of Minecraft for game version.
                 *
                 * @return SHA-1 hash of the logging configuration file of Minecraft for game version
                 *
                 * @since 1.0
                 */
                public String getSha1() {
                    return sha1;
                }

                /**
                 * Gets the size of the logging configuration file of Minecraft for game version.
                 *
                 * @return size of the logging configuration file of Minecraft for game version
                 *
                 * @since 1.0
                 */
                public int getSize() {
                    return size;
                }

                /**
                 * Gets the URL of the logging configuration file of Minecraft for game version.
                 *
                 * @return URL of the logging configuration file of Minecraft for game version
                 *
                 * @since 1.0
                 */
                public String getUrl() {
                    return url;
                }
            }
        }
    }

    ///endregion
}
