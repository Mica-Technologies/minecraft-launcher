/*
 * Copyright (c) 2026 Mica Technologies
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

package com.micatechnologies.minecraft.launcher.gui;

/**
 * Registry of help topics mapping launcher screens and concepts to their HTML help resource files. Each enum entry
 * represents a single help page that can be displayed in the {@link MCLauncherHelpWindow}.
 *
 * @author Mica Technologies
 * @version 1.0
 * @since 2.0
 */
public enum HelpTopic
{
    /** Introductory orientation page shown as the default help topic. */
    GETTING_STARTED( "Getting Started", "help/getting-started.html" ),
    /** Help for the launcher's main menu / pack carousel screen. */
    MAIN_SCREEN( "Main Screen", "help/main-screen.html" ),
    /** Help for the Microsoft / Minecraft account login screen. */
    LOGIN( "Login", "help/login.html" ),
    /** Help for the Settings screen. */
    SETTINGS( "Settings", "help/settings.html" ),
    /** Help for the Browse (modpack library) screen. */
    BROWSE_LIBRARY( "Browse", "help/browse-library.html" ),
    /** Help for the modpack editor screen. */
    MODPACK_EDITOR( "Modpack Editor", "help/modpack-editor.html" ),
    /** Help for the in-game console / log viewer screen. */
    GAME_CONSOLE( "Game Console", "help/game-console.html" ),
    /** Help covering vanilla Minecraft version handling. */
    VANILLA_VERSIONS( "Vanilla Versions", "help/vanilla-versions.html" ),
    /** Help for the multi-version Java runtime management screen. */
    RUNTIME_MANAGEMENT( "Runtime Management", "help/runtime-management.html" ),
    /** Reference page listing the launcher's keyboard shortcuts. */
    KEYBOARD_SHORTCUTS( "Keyboard Shortcuts", "help/keyboard-shortcuts.html" ),
    /** Help for the JVM argument presets feature. */
    JVM_PRESETS( "JVM Presets", "help/jvm-presets.html" ),
    /** Reference page enumerating the launcher's actions. */
    ACTION_REFERENCE( "Action Reference", "help/action-reference.html" ),
    /** Help for language selection and localization. */
    LANGUAGE( "Language & Localization", "help/language.html" ),
    /** General troubleshooting and problem-solving page. */
    TROUBLESHOOTING( "Troubleshooting", "help/troubleshooting.html" );

    /** User-visible name shown for this topic in the help window's topic list. */
    private final String displayName;

    /** Classpath-relative path to the HTML resource rendered for this topic. */
    private final String resourcePath;

    /**
     * Constructs a help topic binding a display name to its HTML resource.
     *
     * @param displayName  user-visible name for the topic
     * @param resourcePath classpath-relative path to the topic's HTML file
     */
    HelpTopic( String displayName, String resourcePath )
    {
        this.displayName = displayName;
        this.resourcePath = resourcePath;
    }

    /**
     * Returns the user-visible display name for this help topic.
     *
     * @return display name
     */
    public String getDisplayName()
    {
        return displayName;
    }

    /**
     * Returns the classpath resource path to the HTML file for this topic.
     *
     * @return resource path
     */
    public String getResourcePath()
    {
        return resourcePath;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns the topic's {@linkplain #getDisplayName() display name} so the
     * enum renders sensibly in UI controls (e.g. list cells) that call
     * {@code toString()} directly.</p>
     *
     * @return the user-visible display name
     */
    @Override
    public String toString()
    {
        return displayName;
    }
}
