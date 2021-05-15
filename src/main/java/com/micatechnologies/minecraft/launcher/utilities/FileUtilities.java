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

package com.micatechnologies.minecraft.launcher.utilities;

import com.google.gson.*;

import java.io.*;
import java.nio.charset.Charset;

/**
 * Utility class for reading and writing strings and JSON objects to a persistent file.
 *
 * @author Mica Technologies
 * @version 1.0.1
 * @editors hawka97
 * @creator hawka97
 * @since 1.0
 */
public class FileUtilities
{
    /**
     * The charset used for reading and writing files to persistent storage.
     *
     * @since 1.0
     */
    public static final Charset persistenceCharset = Charset.defaultCharset();

    /**
     * Reads the contents of the specified file to a string and returns the resulting string.
     *
     * @param f file to read
     *
     * @return file contents as string
     *
     * @throws IOException if unable to access or read file
     * @since 1.0
     */
    public static String readAsString( File f ) throws IOException {
        return org.apache.commons.io.FileUtils.readFileToString( f, persistenceCharset );
    }

    /**
     * Writes the specified string contents to the specified file on persistent storage.
     *
     * @param s string to write
     * @param f file to write to
     *
     * @throws IOException if unable to access or write to file
     * @since 1.0
     */
    public static void writeFromString( String s, File f ) throws IOException {
        org.apache.commons.io.FileUtils.writeStringToFile( f, s, persistenceCharset );
    }

    /**
     * Reads the contents of the specified file as JSON and returns the resulting {@link JsonObject}.
     *
     * @param f file to read
     *
     * @return file contents as a {@link JsonObject}
     *
     * @throws IOException if unable to access or read file
     * @since 1.0
     */
    public static JsonObject readAsJsonObject( File f ) throws IOException {
        return JSONUtilities.stringToObject( readAsString( f ) );
    }

    /**
     * Writes the specified {@link JsonObject} to the specified file on persistent storage.
     *
     * @param j {@link JsonObject} to write
     * @param f file to write to
     *
     * @throws IOException if unable to access or write to file
     * @since 1.0
     */
    public static void writeFromJson( JsonObject j, File f ) throws IOException {
        writeFromString( JSONUtilities.objectToString( j ), f );
    }

    /**
     * Reads the contents of the specified file as a Java object and returns the resulting {@link Object}.
     *
     * @param f file to read
     *
     * @return file contents as an {@link Object}
     *
     * @throws IOException if unable to access or read file
     * @since 1.0
     */
    public static Object readAsObject( File f ) throws IOException, ClassNotFoundException {
        // Create saved account file stream
        FileInputStream fileInputStream = new FileInputStream( f );

        // Create object input stream from created file input stream and return saved account
        ObjectInputStream objectInputStream = new ObjectInputStream( fileInputStream );
        return objectInputStream.readObject();
    }

    /**
     * Writes the specified {@link Object} to the specified file on persistent storage.
     *
     * @param o {@link Object} to write
     * @param f file to write to
     *
     * @throws IOException if unable to access or write to file
     * @since 1.0
     */
    public static void writeFromObject( Object o, File f ) throws IOException {
        FileOutputStream fileOutputStream = new FileOutputStream( f );

        // Create object output stream from created file object stream and save account to file via stream
        ObjectOutputStream objectOutputStream = new ObjectOutputStream( fileOutputStream );
        objectOutputStream.writeObject( o );
    }
}
