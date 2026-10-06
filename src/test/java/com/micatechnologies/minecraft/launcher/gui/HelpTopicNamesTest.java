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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Every help topic's name resolves through the bundle: a missing key would show as the raw
 * {@code help.topic.name.*} key in the help window's topic list.
 */
class HelpTopicNamesTest
{
    @Test
    void everyTopicNameResolvesToBundleText()
    {
        for ( HelpTopic topic : HelpTopic.values() ) {
            String name = topic.getDisplayName();
            assertFalse( name.isBlank(), topic + " has a blank name" );
            assertFalse( name.startsWith( "help.topic.name." ), topic + " name key is missing: " + name );
            assertEquals( name, topic.toString() );
        }
    }
}
