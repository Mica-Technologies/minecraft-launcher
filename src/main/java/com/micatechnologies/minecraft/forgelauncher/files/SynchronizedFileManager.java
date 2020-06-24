/*
 * Copyright (c) 2020 Mica Technologies
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

package com.micatechnologies.minecraft.forgelauncher.files;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Class that manages access to files on the file system in a manner that only allows for one instance of a File object for each file. This allows for consistent synchronization when writing, reading and otherwise interacting with files in a multi-threaded environment.
 * @since 123123123213
 * @version 1.0
 * @author Mica Technologies
 * @creator hawka97
 * @editors hawka97
 */
public class SynchronizedFileManager
{
    private static final List< File > managedFiles = new ArrayList<>();
}
