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

import com.google.gson.{Gson, JsonObject}

/**
 * A utility object for JSON related constants and functions.
 *
 * @since 2023.1
 * @version 2.0
 * @author Mica Technologies
 */
object JsonUtilities {

  /**
   * Converts the specified string to a JSON object.
   *
   * @param jsonString JSON string to convert
   * @return resulting JSON object from the specified string
   * @since 1.0
   */
  def stringToObject(jsonString: String): JsonObject = new Gson().fromJson(jsonString, classOf[JsonObject])

  /**
   * Converts the specified string to a JSON object of the specified type.
   *
   * @param json     JSON string to convert
   * @param classOfT class of type of [[JsonObject]] to return
   * @tparam T type of [[JsonObject]] to return
   * @return resulting JSON object of the specified type from the specified string
   * @since 1.0
   */
  def stringToObject[T](json: String, classOfT: Class[T]): T = new Gson().fromJson(json, classOfT)

  /**
   * Converts the specified JSON object to a string.
   *
   * @param jsonObject JSON object to convert
   * @return resulting string from the specified JSON object
   * @since 1.0
   */
  def objectToString(jsonObject: JsonObject): String = new Gson().toJson(jsonObject)
}
