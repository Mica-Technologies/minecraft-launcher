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

package com.micatechnologies.minecraft.launcher.utilities

import com.google.gson.JsonObject
import org.apache.commons.io.FileUtils

import java.io.{File, IOException}
import java.nio.charset.Charset

/**
 * A utility object for file access and management related constants and functions.
 *
 * @since 2023.1
 * @version 2.0
 * @author Mica Technologies
 */
object FileUtilities {

  /**
   * The charset used for reading and writing files to persistent storage.
   *
   * @since 1.0
   */
  private val persistenceCharset: Charset = Charset.defaultCharset

  /**
   * Reads the contents of the specified file to a string and returns the resulting string.
   *
   * @param readFile file to read from
   * @return string containing the contents of the specified file
   * @throws IOException if unable to access or read from the specified file
   * @since 1.0
   */
  @throws[IOException]
  def readAsString(readFile: File): String = FileUtils.readFileToString(readFile, persistenceCharset)

  /**
   * Reads the contents of the specified file as JSON and returns the resulting [[JsonObject]].
   *
   * @param readFile file to read from
   * @return contents of the specified file as a [[JsonObject]]
   * @throws IOException if unable to access or read from the specified file
   * @since 1.0
   */
  @throws[IOException]
  def readAsJsonObject(readFile: File): JsonObject = JsonUtilities.stringToObject(readAsString(readFile))

  /**
   * Reads the contents of the specified file as JSON and returns the resulting [[JsonObject]] of the specified type.
   *
   * @param readFile file to read from
   * @param classOfT class of type of [[JsonObject]] to return
   * @tparam T type of [[JsonObject]] to return
   * @throws IOException if unable to access or read from the specified file
   * @return file contents as a [[JsonObject]] of the specified type
   * @since 1.0
   */
  @throws[IOException]
  def readAsJsonObject[T](readFile: File, classOfT: Class[T]): T = JsonUtilities.stringToObject(readAsString(readFile),
    classOfT)

  /**
   * Writes the specified string contents to the specified file on persistent storage.
   *
   * @param writeString string to write to the specified file
   * @param writeFile   file to write to
   * @throws IOException if unable to access or write to the specified file
   * @since 1.0
   */
  @throws[IOException]
  def writeFromString(writeString: String, writeFile: File): Unit =
    FileUtils.writeStringToFile(writeFile, writeString, persistenceCharset)


  /**
   * Writes the specified [[JsonObject]] to the specified file on persistent storage.
   *
   * @param jsonObject [[JsonObject]] to write to the specified file
   * @param writeFile  file to write to
   * @throws IOException if unable to access or write to the specified file
   * @since 1.0
   */
  @throws[IOException]
  def writeFromJson(jsonObject: JsonObject, writeFile: File): Unit =
    writeFromString(JsonUtilities.objectToString(jsonObject), writeFile)
}
