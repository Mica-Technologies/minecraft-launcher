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
    GETTING_STARTED( "Getting Started", "help/getting-started.html" ),
    MAIN_SCREEN( "Main Screen", "help/main-screen.html" ),
    LOGIN( "Login", "help/login.html" ),
    SETTINGS( "Settings", "help/settings.html" ),
    BROWSE_LIBRARY( "Browse", "help/browse-library.html" ),
    MODPACK_EDITOR( "Modpack Editor", "help/modpack-editor.html" ),
    GAME_CONSOLE( "Game Console", "help/game-console.html" ),
    VANILLA_VERSIONS( "Vanilla Versions", "help/vanilla-versions.html" ),
    RUNTIME_MANAGEMENT( "Runtime Management", "help/runtime-management.html" ),
    KEYBOARD_SHORTCUTS( "Keyboard Shortcuts", "help/keyboard-shortcuts.html" ),
    TROUBLESHOOTING( "Troubleshooting", "help/troubleshooting.html" );

    private final String displayName;
    private final String resourcePath;

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

    @Override
    public String toString()
    {
        return displayName;
    }
}
